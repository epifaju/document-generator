package com.adgendoc.infrastructure.docx;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.Template;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.exceptions.TemplateNotFoundException;
import com.adgendoc.domain.ports.TemplateEngine;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moteur de fusion DOCX déterministe (ADR-05, architecture §6.2) :
 *
 * <ol>
 *   <li>charge le fichier du template actif
 *       ({@code app.document.template-dir} + {@code file_path}, nom de
 *       fichier simple uniquement — toute tentative de traversal est refusée
 *       par {@code TEMPLATE_NOT_FOUND}) ;</li>
 *   <li>vérifie SHA-256 du fichier == {@code checksum} de la registry ;
 *       fichier absent ou mismatch ⇒ {@link TemplateNotFoundException}
 *       ({@code TEMPLATE_NOT_FOUND}, 500) — jamais de document produit à
 *       partir d'un template altéré ;</li>
 *   <li>parcourt paragraphes, tables, en-têtes et pieds, en reconstruisant
 *       le texte de chaque paragraphe pour absorber les tokens
 *       {@code {{...}}} scindés sur plusieurs {@code XWPFRun} (R-03) ;</li>
 *   <li>remplace chaque token par sa valeur ; token sans valeur ⇒ chaîne
 *       vide ; valeur {@code null} interdite ;</li>
 *   <li>aucune écriture hors tokens (wording intouchable) ;</li>
 *   <li>token résiduel non résolu après fusion ⇒
 *       {@link DocumentGenerationException} — jamais de document incomplet.</li>
 * </ol>
 *
 * <p>Logs : uniquement {@code templateCode} et codes d'erreur — aucune
 * valeur de variable, aucun contenu du DOCX (AGENTS.md §13).</p>
 */
public class PoiTemplateEngine implements TemplateEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(PoiTemplateEngine.class);

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

    private final Path templateDirectory;

    public PoiTemplateEngine(String templateDirectory) {
        Objects.requireNonNull(templateDirectory, "templateDirectory");
        this.templateDirectory = Path.of(templateDirectory).toAbsolutePath().normalize();
    }

    @Override
    public byte[] merge(Template template, Map<String, String> templateVariables) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(templateVariables, "templateVariables");
        for (Map.Entry<String, String> entry : templateVariables.entrySet()) {
            if (entry.getValue() == null) {
                throw new DocumentGenerationException(
                        "Valeur nulle interdite pour la variable de template : "
                                + entry.getKey());
            }
        }

        byte[] raw = loadVerifiedTemplate(template);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(raw))) {
            processParagraphs(document.getParagraphs(), templateVariables, template);
            processTables(document.getTables(), templateVariables, template);
            for (XWPFHeader header : document.getHeaderList()) {
                processParagraphs(header.getParagraphs(), templateVariables, template);
                processTables(header.getTables(), templateVariables, template);
            }
            for (XWPFFooter footer : document.getFooterList()) {
                processParagraphs(footer.getParagraphs(), templateVariables, template);
                processTables(footer.getTables(), templateVariables, template);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage(), exception);
        }
    }

    /** Étapes 1–2 : existence, nom de fichier sûr, intégrité SHA-256. */
    private byte[] loadVerifiedTemplate(Template template) {
        String filePath = template.getFilePath();
        if (filePath == null || filePath.isBlank() || filePath.contains("/")
                || filePath.contains("\\") || filePath.contains("..")) {
            LOGGER.warn("Chemin de template refuse pour le template {}",
                    template.getCode());
            throw new TemplateNotFoundException(template.getCode());
        }
        Path file = templateDirectory.resolve(filePath).normalize();
        if (!file.startsWith(templateDirectory) || !Files.isRegularFile(file)) {
            LOGGER.warn("Template absent : code={}", template.getCode());
            throw new TemplateNotFoundException(template.getCode());
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new TemplateNotFoundException(template.getCode(), exception);
        }
        if (!sha256Hex(raw).equals(template.getChecksum())) {
            LOGGER.warn("Checksum template invalide : code={} attendu={}", // pas de
                    template.getCode(), template.getChecksum());          // contenu
            throw new TemplateNotFoundException(template.getCode());
        }
        return raw;
    }

    private void processTables(List<XWPFTable> tables, Map<String, String> variables,
                               Template template) {
        for (XWPFTable table : tables) {
            for (var row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    processParagraphs(cell.getParagraphs(), variables, template);
                    processTables(cell.getTables(), variables, template);
                }
            }
        }
    }

    private void processParagraphs(List<XWPFParagraph> paragraphs,
                                   Map<String, String> variables, Template template) {
        for (XWPFParagraph paragraph : paragraphs) {
            replaceInParagraph(paragraph, variables, template);
        }
    }

    /**
     * Reconstruction paragraphe (R-03) : le texte de tous les runs est
     * concaténé, les tokens remplacés, puis le résultat est réécrit dans le
     * premier run (les suivants sont vidés) — seuls les paragraphes
     * contenant un token sont touchés.
     */
    private void replaceInParagraph(XWPFParagraph paragraph, Map<String, String> variables,
                                    Template template) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            return;
        }
        StringBuilder original = new StringBuilder();
        for (XWPFRun run : runs) {
            original.append(run.text());
        }
        String text = original.toString();
        if (!text.contains("{{") && !text.contains("}}")) {
            return;
        }
        String replaced = replaceTokens(text, variables);
        if (replaced.contains("{{") || replaced.contains("}}")) {
            LOGGER.warn("Placeholder non resolu apres fusion : code={}",
                    template.getCode());
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
        }
        if (replaced.equals(text)) {
            return;
        }
        runs.get(0).setText(replaced, 0);
        for (int i = 1; i < runs.size(); i++) {
            runs.get(i).setText("", 0);
        }
    }

    /** Étape 4 : token sans valeur dans la map ⇒ chaîne vide. */
    private String replaceTokens(String text, Map<String, String> variables) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.getOrDefault(name, "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage(), exception);
        }
    }
}
