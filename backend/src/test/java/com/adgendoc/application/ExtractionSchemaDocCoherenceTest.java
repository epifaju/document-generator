package com.adgendoc.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde anti-dérive Phase E.1 : le schéma d'extraction existe en TROIS
 * copies (classpath Maven, fichier {@code prompts/}, bloc §8 de
 * {@code docs/architecture.md}). Ce test les compare octet à octet et
 * fige les invariants exigés par la mission E.1 :
 *
 * <ul>
 *   <li>draft {@code 2020-12} ;</li>
 *   <li>{@code required} racine et {@code additionalProperties: false} intacts ;</li>
 *   <li>bloc {@code allOf} corrigé : chaque {@code if} teste {@code data}
 *       ({@code required: ["data"]} + {@code properties.data.not.required}),
 *       chaque {@code then} utilise le mot-clé réel
 *       {@code properties.missingFields.contains} ;</li>
 *   <li>les trois copies sont strictement identiques.</li>
 * </ul>
 *
 * <p>Chemins : relatifs au working dir Maven {@code backend/} (convention
 * {@code ../tests/fixtures}, {@code ../prompts}, {@code ../docs}),
 * surchargeables par {@code repo.root}.</p>
 */
class ExtractionSchemaDocCoherenceTest {

    private static final String SCHEMA_RESOURCE =
            "prompts/extraction/attestation_concordance.schema.json";
    private static final List<String> ALL_OF_FIELDS = List.of(
            "prenom", "nom", "dateNaissance", "lieuNaissance", "nomIncorrect", "nomCorrect");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // Chargement des trois copies
    // ------------------------------------------------------------------

    private static String classpathSchema() throws IOException {
        try (InputStream stream = ExtractionSchemaDocCoherenceTest.class.getClassLoader()
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(stream)
                    .as("Schéma introuvable dans le classpath : %s", SCHEMA_RESOURCE)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<Path> repoCandidates(String relative) {
        List<Path> candidates = new ArrayList<>();
        String root = System.getProperty("repo.root");
        if (root != null && !root.isBlank()) {
            candidates.add(Paths.get(root).resolve(relative));
        }
        candidates.add(Paths.get("..").resolve(relative));
        candidates.add(Paths.get(relative));
        return candidates;
    }

    private static String repoFile(String relative) {
        List<Path> tried = new ArrayList<>();
        for (Path candidate : repoCandidates(relative)) {
            Path resolved = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(resolved)) {
                try {
                    return Files.readString(resolved, StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    throw new AssertionError("Lecture impossible : " + resolved, exception);
                }
            }
            tried.add(resolved);
        }
        throw new AssertionError("Fichier introuvable : " + relative
                + ". Chemins testés : " + tried
                + " (working dir = " + Paths.get("").toAbsolutePath()
                + ", propriété 'repo.root' = " + System.getProperty("repo.root") + ").");
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").trim();
    }

    /** Extrait le bloc {@code ```json} de la section §8 de {@code docs/architecture.md}. */
    private static String architectureSection8() {
        String architecture = repoFile("docs/architecture.md");
        List<String> lines = List.of(architecture.replace("\r\n", "\n").split("\n", -1));

        int section = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith("## 8.")) {
                section = index;
                break;
            }
        }
        assertThat(section)
                .as("Section « ## 8. » absente de docs/architecture.md")
                .isNotNegative();

        int open = -1;
        for (int index = section; index < lines.size(); index++) {
            if (lines.get(index).equals("```json")) {
                open = index;
                break;
            }
        }
        assertThat(open)
                .as("Bloc ```json absent de la section §8 de docs/architecture.md")
                .isNotNegative();

        int close = -1;
        for (int index = open + 1; index < lines.size(); index++) {
            if (lines.get(index).equals("```")) {
                close = index;
                break;
            }
        }
        assertThat(close)
                .as("Bloc ```json de §8 non fermé dans docs/architecture.md")
                .isNotNegative();

        return String.join("\n", lines.subList(open + 1, close));
    }

    // ------------------------------------------------------------------
    // 1 — la copie classpath = fichier prompts/ (ce que le produit charge)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1. schéma classpath == fichier prompts/ (aucune dérive de copie)")
    void classpath_schema_matches_prompts_file_on_disk() throws IOException {
        assertThat(normalize(classpathSchema()))
                .as("La ressource copiée dans le classpath (ressource Maven ../prompts/extraction) "
                        + "doit être identique au fichier prompts/ versionné")
                .isEqualTo(normalize(repoFile("prompts/extraction/attestation_concordance.schema.json")));
    }

    // ------------------------------------------------------------------
    // 2 — la copie §8 de l'architecture = fichier prompts/ (mission E.1 b)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2. bloc §8 de docs/architecture.md == fichier prompts/ (copie identique)")
    void architecture_section8_is_identical_to_schema_file() {
        String schema = normalize(repoFile("prompts/extraction/attestation_concordance.schema.json"));

        assertThat(normalize(architectureSection8()))
                .as("Le bloc JSON de docs/architecture.md §8 doit être identique au schéma "
                        + "(mission E.1 : correction du bloc allOf + copie §8)")
                .isEqualTo(schema);
    }

    // ------------------------------------------------------------------
    // 3 — invariants racine figés (mission E.1 2.iv)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3. invariants racine intacts : draft 2020-12, required, additionalProperties")
    void root_invariants_are_intact() throws IOException {
        JsonNode schema = MAPPER.readTree(classpathSchema());

        assertThat(schema.get("$schema").asText())
                .as("Le schéma doit rester en draft 2020-12")
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean())
                .as("additionalProperties racine doit rester false")
                .isFalse();
        JsonNode required = schema.get("required");
        assertThat(required.isArray())
                .as("required racine doit être un tableau, obtenu %s", required)
                .isTrue();
        List<String> requiredKeys = new ArrayList<>();
        required.forEach(node -> requiredKeys.add(node.asText()));
        assertThat(requiredKeys)
                .as("required racine doit rester les 4 clés, obtenu %s", requiredKeys)
                .containsExactly("documentType", "confidence", "data", "missingFields");
        assertThat(schema.get("properties").get("data").get("additionalProperties").asBoolean())
                .as("additionalProperties de data doit rester false")
                .isFalse();
        assertThat(schema.get("properties").get("missingFields").get("uniqueItems").asBoolean())
                .isTrue();
    }

    // ------------------------------------------------------------------
    // 4 — forme du bloc allOf corrigé (mission E.1 2.i / 2.ii)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4. allOf : if teste data (et non la racine), then utilise properties.missingFields.contains")
    void allof_tests_data_and_uses_real_contains_keyword() throws IOException {
        JsonNode schema = MAPPER.readTree(classpathSchema());
        JsonNode allOf = schema.get("allOf");

        assertThat(allOf).as("Bloc allOf absent du schéma").isNotNull();
        assertThat(allOf.size())
                .as("Le bloc allOf doit contenir les 6 entrées")
                .isEqualTo(6);

        for (int index = 0; index < allOf.size(); index++) {
            JsonNode entry = allOf.get(index);
            String field = ALL_OF_FIELDS.get(index);

            JsonNode ifNode = entry.get("if");
            assertThat(ifNode).as("allOf[%d].if absent", index).isNotNull();
            assertThat(ifNode.get("required").toString())
                    .as("allOf[%d] doit tester required racine [\"data\"], obtenu %s",
                            index, ifNode.get("required"))
                    .contains("data");
            assertThat(ifNode.get("properties").get("data").get("not").get("required").toString())
                    .as("allOf[%d] doit tester data.not.required [\"%s\"] (et non la racine)",
                            index, field)
                    .contains(field);

            JsonNode thenNode = entry.get("then");
            assertThat(thenNode).as("allOf[%d].then absent", index).isNotNull();
            JsonNode contains = thenNode.get("properties").get("missingFields").get("contains");
            assertThat(contains)
                    .as("allOf[%d].then doit utiliser properties.missingFields.contains "
                            + "(mot-clé JSON Schema réel), obtenu %s", index, thenNode)
                    .isNotNull();
            assertThat(contains.get("const").asText())
                    .as("allOf[%d].contains.const doit être « %s »", index, field)
                    .isEqualTo(field);
        }
    }

    // ------------------------------------------------------------------
    // 5 — anti-régression du bug corrigé : l'ancienne forme (if racine +
    //     then sans properties) ne doit jamais réapparaître
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5. aucune entrée allOf ne teste la racine (ancien bug) ni oublie properties dans then")
    void no_legacy_root_test_and_no_bare_missing_fields_in_then() throws IOException {
        JsonNode allOf = MAPPER.readTree(classpathSchema()).get("allOf");

        for (int index = 0; index < allOf.size(); index++) {
            JsonNode entry = allOf.get(index);

            // Ancien bug : {"if": {"not": {"required": ["prenom"]}}} testait la RACINE.
            assertThat(entry.get("if").has("not"))
                    .as("allOf[%d] : l'ancienne forme « if.not.required » (test racine) "
                            + "a réapparu", index)
                    .isFalse();
            // Ancien bug : {"then": {"missingFields": {...}}} sans « properties ».
            assertThat(entry.get("then").has("missingFields"))
                    .as("allOf[%d] : « then.missingFields » sans « properties » "
                            + "(mot-clé inerte) a réapparu", index)
                    .isFalse();
        }
    }
}
