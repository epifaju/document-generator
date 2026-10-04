package com.adgendoc.infrastructure.docx;

import com.adgendoc.domain.Template;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.exceptions.TemplateNotFoundException;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase F2 — {@code PoiTemplateEngine} sur <b>DOCX réels</b> (aucun mock) :
 * chargement du template de développement versionné, vérification SHA-256
 * valide/incorrecte, fusion des 14 variables, accents UTF-8, valeur nulle
 * interdite, token inconnu/non résolu, token fragmenté sur plusieurs runs,
 * réouverture du résultat avec Apache POI et conservation de la marque
 * « NON APPROUVÉ POUR PRODUCTION ».
 */
class PoiTemplateEngineTest {

    private static final Path REPOSITORY_TEMPLATE_DIR = Path.of("..", "templates");
    private static final String REAL_TEMPLATE_FILE = "attestation_concordance_v1.docx";
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final String MARKER = "NON APPROUVÉ POUR PRODUCTION";

    @TempDir
    Path fixtureDirectory;

    private Path realTemplateFile;
    private PoiTemplateEngine repositoryEngine;
    private PoiTemplateEngine fixtureEngine;

    @BeforeEach
    void setUp() {
        realTemplateFile = REPOSITORY_TEMPLATE_DIR.resolve(REAL_TEMPLATE_FILE);
        repositoryEngine = new PoiTemplateEngine(REPOSITORY_TEMPLATE_DIR.toString());
        fixtureEngine = new PoiTemplateEngine(fixtureDirectory.toString());
    }

    // 1 — Template chargé + 2 — SHA-256 valide

    @Test
    void real_development_template_is_loaded_and_checksum_matches() {
        Template template = realTemplate();
        Map<String, String> variables = allVariables();

        byte[] merged = repositoryEngine.merge(template, variables);

        assertThat(merged).isNotEmpty();
    }

    // 3 — SHA-256 incorrect

    @Test
    void checksum_mismatch_is_refused_with_template_not_found() {
        Template tampered = new Template(DOCUMENT_TYPE, "1.0", REAL_TEMPLATE_FILE,
                "0".repeat(64), true);

        assertThatThrownBy(() -> repositoryEngine.merge(tampered, allVariables()))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        com.adgendoc.domain.ErrorCode.TEMPLATE_NOT_FOUND);
    }

    @Test
    void missing_template_file_is_refused_with_template_not_found() {
        Template absent = new Template(DOCUMENT_TYPE, "1.0", "absent_v9.docx",
                "0".repeat(64), true);

        assertThatThrownBy(() -> repositoryEngine.merge(absent, allVariables()))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void path_traversal_in_file_path_is_refused() {
        Template hostile = new Template(DOCUMENT_TYPE, "1.0", "../pom.xml",
                "0".repeat(64), true);

        assertThatThrownBy(() -> repositoryEngine.merge(hostile, allVariables()))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    // 4 — Remplacement des 14 variables + 5 — UTF-8 + 9 — réouverture POI
    // + 10 — marque conservée

    @Test
    void all_fourteen_variables_are_merged_and_docx_reopens_with_poi()
            throws IOException {
        Template template = realTemplate();
        Map<String, String> variables = allVariables();

        byte[] merged = repositoryEngine.merge(template, variables);

        try (XWPFDocument document = reopen(merged)) {
            String text = documentText(document);
            assertThat(text).contains("Maria").contains("Gomes")
                    .contains("1985-05-12").contains("Bissau")
                    .contains("Maria Gomez").contains("Maria Gomes")
                    .contains("Feminin").contains("Bissau-Guinée")
                    .contains("REF-SRC-1").contains("FR")
                    .contains("João Fernandes").contains("Dossier bancaire");
            assertThat(text).contains(REAL_TEMPLATE_LINE_ID);
            assertThat(text).doesNotContain("{{").doesNotContain("}}");
            for (String name : variables.keySet()) {
                assertThat(text).doesNotContain("{{" + name + "}}");
            }
            assertThat(text).contains(MARKER);
            assertThat(document.getParagraphs()).isNotEmpty();
        }
    }

    @Test
    void merged_document_preserves_accented_placeholders_labels()
            throws IOException {
        byte[] merged = repositoryEngine.merge(realTemplate(), allVariables());

        try (XWPFDocument document = reopen(merged)) {
            String text = documentText(document);
            assertThat(text).contains("Prénom :").contains("Nationalité :")
                    .contains("Forme erronée :").contains("Date de génération :");
        }
    }

    // 6 — valeur vide/null interdite

    @Test
    void null_variable_value_is_refused() {
        Map<String, String> variables = allVariables();
        variables.put("sexe", null);

        assertThatThrownBy(() -> repositoryEngine.merge(realTemplate(), variables))
                .isInstanceOf(DocumentGenerationException.class)
                .hasMessageContaining("sexe");
    }

    @Test
    void empty_string_value_for_optional_variable_is_merged_to_empty()
            throws IOException {
        Map<String, String> variables = allVariables();
        variables.put("sexe", "");

        byte[] merged = repositoryEngine.merge(realTemplate(), variables);

        try (XWPFDocument document = reopen(merged)) {
            String text = documentText(document);
            assertThat(text).contains("Sexe :");
            assertThat(text).doesNotContain("{{sexe}}");
        }
    }

    // 7 — placeholder inconnu / non résolu

    @Test
    void unknown_but_wellformed_token_is_emptied_without_residual() throws IOException {
        byte[] custom = fixtureWithParagraphs("Valeur : {{champ_inconnu}} fin");

        byte[] merged = fixtureEngine.merge(fixtureTemplate(custom), Map.of());

        // L'invariant « aucun résidu {{ }} » est vérifié sur le CONTENU du
        // document (paragraphes réouverts), pas sur les octets ZIP compressés :
        // ces octets varient d'une exécution à l'autre (horodatage POI dans
        // docProps/core.xml) et le flux deflate peut contenir « {{ » par
        // hasard — assertion non déterministe, déjà observée fautive sur le
        // checkpoint 6544074 (preuve baseline, Phase H.2).
        try (XWPFDocument document = reopen(merged)) {
            String text = documentText(document);
            assertThat(text).doesNotContain("{{").doesNotContain("}}");
            assertThat(text.trim()).isEqualTo("Valeur :  fin");
        }
    }

    @Test
    void unresolved_malformed_token_fails_the_generation() {
        byte[] custom = fixtureWithParagraphs("Cassé : {{prenom fin sans fermeture");

        assertThatThrownBy(() -> fixtureEngine.merge(fixtureTemplate(custom), Map.of()))
                .isInstanceOf(DocumentGenerationException.class);
    }

    @Test
    void value_containing_a_token_is_refused_as_residual_placeholder() {
        byte[] custom = fixtureWithParagraphs("Prénom : {{prenom}}");
        Map<String, String> variables = Map.of("prenom", "{{autre}}");

        assertThatThrownBy(() -> fixtureEngine.merge(fixtureTemplate(custom), variables))
                .isInstanceOf(DocumentGenerationException.class);
    }

    // 8 — placeholder fragmenté entre plusieurs runs Word

    @Test
    void token_split_across_runs_is_reconstructed_and_merged() throws IOException {
        byte[] custom = fixtureWithFragmentedToken();

        byte[] merged = fixtureEngine.merge(fixtureTemplate(custom),
                Map.of("prenom", "Maria"));

        try (XWPFDocument document = reopen(merged)) {
            String text = documentText(document);
            assertThat(text).contains("Bonjour Maria !");
            assertThat(text).doesNotContain("{{");
        }
    }

    @Test
    void tokens_in_tables_headers_and_footers_are_merged() throws IOException {
        byte[] custom = fixtureWithTableHeaderAndFooter();

        byte[] merged = fixtureEngine.merge(fixtureTemplate(custom),
                Map.of("prenom", "Maria", "nom", "Gomes"));

        try (XWPFDocument document = reopen(merged)) {
            assertThat(documentText(document)).doesNotContain("{{");
            String headerText = "";
            for (var header : document.getHeaderList()) {
                for (XWPFParagraph paragraph : header.getParagraphs()) {
                    headerText += paragraph.getText();
                }
            }
            assertThat(headerText).contains("Maria");
        }
    }

    @Test
    void wording_outside_tokens_is_untouched() throws IOException {
        byte[] custom = fixtureWithParagraphs("Texte intact : {{prenom}} intact");

        byte[] merged = fixtureEngine.merge(fixtureTemplate(custom),
                Map.of("prenom", "Maria"));

        try (XWPFDocument document = reopen(merged)) {
            assertThat(documentText(document).trim())
                    .isEqualTo("Texte intact : Maria intact");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static final String REAL_TEMPLATE_LINE_ID =
            "Référence de la demande :";

    private Template realTemplate() {
        return new Template(DOCUMENT_TYPE, "1.0", REAL_TEMPLATE_FILE,
                sha256Hex(readRealTemplate()), true);
    }

    private byte[] readRealTemplate() {
        try {
            return Files.readAllBytes(realTemplateFile);
        } catch (IOException exception) {
            throw new AssertionError(
                    "Template reel absent : " + realTemplateFile.toAbsolutePath(), exception);
        }
    }

    private Map<String, String> allVariables() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("prenom", "Maria");
        variables.put("nom", "Gomes");
        variables.put("date_naissance", "1985-05-12");
        variables.put("lieu_naissance", "Bissau");
        variables.put("nom_incorrect", "Maria Gomez");
        variables.put("nom_correct", "Maria Gomes");
        variables.put("sexe", "Feminin");
        variables.put("nationalite", "Bissau-Guinée");
        variables.put("document_source_reference", "REF-SRC-1");
        variables.put("langue_document", "FR");
        variables.put("demandeur", "João Fernandes");
        variables.put("motif", "Dossier bancaire");
        variables.put("reference_demande", "b3a1f0d2-1111-2222-3333-444455556666");
        variables.put("date_generation", "2026-10-03");
        return variables;
    }

    private Template fixtureTemplate(byte[] content) {
        Path file = fixtureDirectory.resolve("fixture.docx");
        try {
            Files.write(file, content);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        return new Template("FIXTURE", "1.0", "fixture.docx", sha256Hex(content), true);
    }

    private byte[] fixtureWithParagraphs(String paragraphText) {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(paragraphText);
            return writeToBytes(document);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private byte[] fixtureWithFragmentedToken() {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun first = paragraph.createRun();
            first.setText("Bonjour {{pre");
            XWPFRun second = paragraph.createRun();
            second.setText("nom}} !");
            return writeToBytes(document);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private byte[] fixtureWithTableHeaderAndFooter() {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Corps {{prenom}}");
            XWPFTable table = document.createTable();
            var row = table.createRow();
            var cell = row.createCell();
            cell.getParagraphs().get(0).createRun().setText("Cellule {{nom}}");
            var header = document.createHeader(HeaderFooterType.DEFAULT);
            header.createParagraph().createRun().setText("Entête {{prenom}}");
            var footer = document.createFooter(HeaderFooterType.DEFAULT);
            footer.createParagraph().createRun().setText("Pied {{nom}}");
            return writeToBytes(document);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private XWPFDocument reopen(byte[] docx) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(docx));
    }

    private String documentText(XWPFDocument document) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            text.append(paragraph.getText()).append('\n');
        }
        for (XWPFTable table : document.getTables()) {
            text.append(table.getText()).append('\n');
        }
        return text.toString();
    }

    private byte[] writeToBytes(XWPFDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        return output.toByteArray();
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
