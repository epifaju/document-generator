package com.adgendoc.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de forme de l'{@code ExtractionResult} contre
 * {@code prompts/extraction/attestation_concordance.schema.json}
 * (draft 2020-12) — architecture §8/§11.1/§11.2.
 *
 * <p>Deux sources distinctes, exprès :</p>
 * <ul>
 *   <li>le <strong>schéma du classpath</strong> ({@code prompts/extraction/…}),
 *       identique à celui chargé en runtime par
 *       {@code ExtractionValidationService} — les {@code @Test} qui
 *       conditionnent le comportement du produit ;</li>
 *   <li>des <strong>variants mutés en mémoire</strong> de ce même schéma
 *       (ex. {@code allOf} supprimé) — le test 10 prouve la CAUSALITÉ de la
 *       règle {@code allOf} (un schéma sans {@code allOf} valide le payload,
 *       le schéma réel le rejette).</li>
 * </ul>
 *
 * <p>Fixtures : {@code tests/fixtures/} (architecture §11.2), chemin relatif
 * au working dir Maven {@code backend/} ; surchargeable par la propriété
 * système {@code fixtures.dir}.</p>
 */
class ExtractionSchemaValidationTest {

    private static final String SCHEMA_CLASSPATH =
            "prompts/extraction/attestation_concordance.schema.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode schemaDocument;
    private static JsonSchema schema;
    private static ExtractionValidationService service;

    // ------------------------------------------------------------------
    // Chargement
    // ------------------------------------------------------------------

    @BeforeAll
    static void loadSchemaFromClasspath() throws IOException {
        try (InputStream stream = ExtractionSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream(SCHEMA_CLASSPATH)) {
            assertThat(stream)
                    .as("Schéma introuvable dans le classpath : %s "
                            + "(ressource Maven copiée depuis ../prompts/extraction)", SCHEMA_CLASSPATH)
                    .isNotNull();
            JsonNode node = MAPPER.readTree(stream);
            assertThat(node.isObject()).as("Le schéma doit être un objet JSON").isTrue();
            schemaDocument = (ObjectNode) node;
        }
        schema = schemaFor(schemaDocument);
        service = new ExtractionValidationService("classpath:" + SCHEMA_CLASSPATH);
    }

    private static JsonSchema schemaFor(JsonNode schemaNode) {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaNode);
    }

    /** Variante mutée du schéma réel : {@code allOf} retiré (preuve de causalité). */
    private static JsonSchema schemaWithoutAllOf() {
        ObjectNode mutated = schemaDocument.deepCopy();
        mutated.remove("allOf");
        return schemaFor(mutated);
    }

    private static Set<ValidationMessage> validate(JsonNode payload, JsonSchema target) {
        return target.validate(payload);
    }

    private static Set<ValidationMessage> validate(JsonNode payload) {
        return validate(payload, schema);
    }

    /**
     * networknt 1.5.9 expose le chemin d'instance en notation legacy
     * ({@code $.data.dateNaissance}, {@code $} à la racine) ; conversion en
     * JSON Pointer, à l'identique de
     * {@code ExtractionValidationService.toJsonPointer}.
     */
    private static String pointer(ValidationMessage message) {
        String path = message.getInstanceLocation().toString();
        if (path == null || path.isEmpty() || "$".equals(path)) {
            return "/";
        }
        if (path.startsWith("$.")) {
            path = path.substring(2);
        } else if (path.startsWith("$")) {
            path = path.substring(1);
        }
        path = path.replace('.', '/');
        path = path.replaceAll("\\[(\\d+)]", "/$1");
        return path.startsWith("/") ? path : "/" + path;
    }

    private static boolean isValid(JsonNode payload, JsonSchema target) {
        return validate(payload, target).isEmpty();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Répertoire des fixtures. Priorité à la propriété système
     * {@code fixtures.dir} (architecture §11.1), sinon
     * {@code ../tests/fixtures} depuis le working dir Maven {@code backend/}.
     */
    private static List<Path> fixtureCandidates() {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("fixtures.dir");
        if (configured != null && !configured.isBlank()) {
            candidates.add(Paths.get(configured));
        }
        candidates.add(Paths.get("../tests/fixtures"));
        candidates.add(Paths.get("tests/fixtures"));
        return candidates;
    }

    private static Path fixtureFile(String name) {
        List<Path> tried = new ArrayList<>();
        for (Path candidate : fixtureCandidates()) {
            Path resolved = candidate.toAbsolutePath().normalize().resolve(name);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
            tried.add(resolved);
        }
        throw new AssertionError("Fixture absente : " + name
                + ". Chemins testés : " + tried
                + " (working dir = " + Paths.get("").toAbsolutePath()
                + ", propriété 'fixtures.dir' = " + System.getProperty("fixtures.dir") + ").");
    }

    private static String fixtureText(String name) {
        Path file = fixtureFile(name);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Lecture impossible : " + file, exception);
        }
    }

    private static JsonNode fixtureJson(String name) {
        String text = fixtureText(name);
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new AssertionError("Fixture malformée (JSON invalide) : " + fixtureFile(name),
                    exception);
        }
    }

    private static ObjectNode validCompleteNode() {
        return (ObjectNode) fixtureJson("extraction_valid_complete.json");
    }

    // ------------------------------------------------------------------
    // 1 — payload structurellement valide
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1. fixture complète → VALID (aucune violation)")
    void valid_complete_fixture_is_valid() {
        JsonNode payload = fixtureJson("extraction_valid_complete.json");

        Set<ValidationMessage> messages = validate(payload);

        assertThat(messages)
                .as("Payload valide : aucune violation attendue, obtenu %s", messages)
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // 2 — documentType
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2. documentType conforme → VALID ; documentType différent → INVALID (const)")
    void document_type_must_be_attestation_concordance() {
        assertThat(isValid(validCompleteNode(), schema)).isTrue();

        ObjectNode unsupported = validCompleteNode();
        unsupported.put("documentType", "ACTE_NAISSANCE");
        Set<ValidationMessage> messages = validate(unsupported);

        assertThat(messages).isNotEmpty();
        assertThat(messages)
                .anySatisfy(message -> {
                    assertThat(message.getType()).isEqualTo("const");
                    assertThat(pointer(message)).isEqualTo("/documentType");
                });
    }

    // ------------------------------------------------------------------
    // 3 — data absent
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3. data absent → INVALID (required racine)")
    void missing_data_root_is_invalid() {
        ObjectNode payload = validCompleteNode();
        payload.remove("data");

        Set<ValidationMessage> messages = validate(payload);

        assertThat(messages).isNotEmpty();
        assertThat(messages)
                .anySatisfy(message -> {
                    assertThat(message.getType()).isEqualTo("required");
                    assertThat(message.getMessage()).contains("data");
                });
    }

    // ------------------------------------------------------------------
    // 4 — champ conditionnel PRÉSENT dans data
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4. prenom présent dans data → l'allOf ne s'applique pas : VALID, "
            + "missingFields le déclare ou non")
    void conditional_field_present_in_data_triggers_no_allof_check() {
        // (a) prenom présent, non déclaré dans missingFields
        ObjectNode notDeclared = validCompleteNode();
        assertThat(notDeclared.get("data").has("prenom")).isTrue();
        assertThat(isValid(notDeclared, schema)).isTrue();

        // (b) prenom présent MAIS déclaré quand même dans missingFields :
        // le `if` (data NOT required prenom) est faux => `then` inappliqué,
        // aucune contrainte n'est générée => VALID.
        ObjectNode declared = validCompleteNode();
        ((ArrayNode) declared.get("missingFields")).add("prenom");
        assertThat(isValid(declared, schema)).isTrue();
    }

    // ------------------------------------------------------------------
    // 5 — champ absent MAIS déclaré
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5. nomCorrect absent de data mais déclaré dans missingFields → VALID (S09)")
    void conditional_field_absent_but_declared_is_valid() {
        JsonNode payload = fixtureJson("extraction_missing_nom_correct.json");

        assertThat(payload.get("data").has("nomCorrect")).isFalse();
        assertThat(payload.get("missingFields").toString()).contains("nomCorrect");

        assertThat(validate(payload))
                .as("Absence déclarée => conforme").isEmpty();
    }

    // ------------------------------------------------------------------
    // 5b — champ absent ET non déclaré  (cœur du fix)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5b. prenom absent de data ET non déclaré dans missingFields → INVALID "
            + "(règle allOf anti-oublie silencieux)")
    void conditional_field_absent_and_undeclared_is_invalid() {
        JsonNode payload = fixtureJson("extraction_missing_undeclared.json");

        assertThat(payload.get("data").has("prenom")).isFalse();

        Set<ValidationMessage> messages = validate(payload);

        assertThat(messages)
                .as("Oublie silencieuse de « prenom » : rejet attendu, obtenu %s", messages)
                .isNotEmpty();
        assertThat(messages)
                .anySatisfy(message -> assertThat(pointer(message)).contains("missingFields"));

        // Le service E8 doit lui aussi refuser ce payload.
        List<ExtractionValidationService.SchemaViolation> violations =
                service.validate(payload.toString());
        assertThat(violations)
                .as("Service E8 : rejet aussi, obtenu %s", violations)
                .isNotEmpty();
        assertThat(violations.get(0).path()).isEqualTo("/missingFields");
        // Le keyword networknt est « minContains » : ExtractionValidationService
        // n'a pas de branche dédiée => repli ERR_PAYLOAD_INVALIDE (voir OPEN_ISSUES).
        assertThat(violations.get(0).code())
                .isEqualTo(com.adgendoc.domain.ErrorCode.ERR_PAYLOAD_INVALIDE);
    }

    // ------------------------------------------------------------------
    // 6 — clé inconnue dans data
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6. clé inconnue dans data → INVALID (additionalProperties)")
    void unknown_key_in_data_is_invalid() {
        JsonNode payload = fixtureJson("extraction_unknown_key.json");

        Set<ValidationMessage> messages = validate(payload);

        assertThat(messages).isNotEmpty();
        assertThat(messages)
                .anySatisfy(message -> {
                    assertThat(message.getType()).isEqualTo("additionalProperties");
                    assertThat(message.getMessage()).contains("passeport");
                });
    }

    // ------------------------------------------------------------------
    // 7 — mauvais types
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7. confidence = \"0.9\" (string) et prenom = 42 (number) → INVALID (type)")
    void wrong_types_are_invalid() {
        ObjectNode confidenceAsString = validCompleteNode();
        confidenceAsString.put("confidence", "0.9");
        Set<ValidationMessage> confidenceMessages = validate(confidenceAsString);
        assertThat(confidenceMessages).isNotEmpty();
        assertThat(confidenceMessages)
                .anySatisfy(message -> {
                    assertThat(message.getType()).isEqualTo("type");
                    assertThat(pointer(message)).isEqualTo("/confidence");
                });

        ObjectNode prenomAsNumber = validCompleteNode();
        ((ObjectNode) prenomAsNumber.get("data")).put("prenom", 42);
        Set<ValidationMessage> prenomMessages = validate(prenomAsNumber);
        assertThat(prenomMessages).isNotEmpty();
        assertThat(prenomMessages)
                .anySatisfy(message -> {
                    assertThat(message.getType()).isEqualTo("type");
                    assertThat(pointer(message)).isEqualTo("/data/prenom");
                });
    }

    // ------------------------------------------------------------------
    // 8 — date ISO
    // ------------------------------------------------------------------

    @Test
    @DisplayName("8. dateNaissance ISO 1985-05-12 → VALID")
    void iso_date_is_valid() {
        JsonNode payload = validCompleteNode();

        assertThat(payload.get("data").get("dateNaissance").asText()).isEqualTo("1985-05-12");
        assertThat(validate(payload))
                .as("Date ISO conforme au pattern").isEmpty();
    }

    // ------------------------------------------------------------------
    // 9 — date d'entrée dd/MM/yyyy
    // ------------------------------------------------------------------

    /**
     * Le schéma d'EXTRACTION impose le pattern ISO (architecture §8) :
     * « le {@code pattern} ISO est strict » — n8n ne porte aucune règle de
     * conversion de date (décision humaine F-07 / R-06), la conversion des
     * formats d'entrée {@code dd/MM/yyyy} → {@code yyyy-MM-dd} reste côté
     * backend pour E1 direct (S03, via {@code NormalizationService}).
     * <b>F-07 / R-06 RÉSOLUS</b> : {@code API_CONTRACTS.md §2.4 / §2.6} a été
     * corrigé — la sortie d'extraction est strictement ISO-8601. Comportement
     * attendu ici, conforme au schéma : <b>INVALID</b>.
     */
    @Test
    @DisplayName("9. dateNaissance \"12/05/1985\" → INVALID côté schéma d'extraction (pattern ISO)")
    void french_date_entry_is_invalid_at_extraction_level() {
        JsonNode payload = fixtureJson("extraction_invalid_date.json");

        assertThat(payload.get("data").get("dateNaissance").asText()).isEqualTo("12/05/1985");

        Set<ValidationMessage> messages = validate(payload);

        assertThat(messages).isNotEmpty();
        assertThat(messages)
                .anySatisfy(message -> {
                    assertThat(message.getType()).isEqualTo("pattern");
                    assertThat(pointer(message)).isEqualTo("/data/dateNaissance");
                });
    }

    // ------------------------------------------------------------------
    // 10 — preuve de causalité du allOf (test de mutation)
    // ------------------------------------------------------------------

    /**
     * Payload A : {@code prenom} absent de {@code data}, {@code missingFields}
     * vide, tout le reste conforme. Il n'est INVALID que grâce à la règle
     * {@code allOf} : la même saisie validée contre une copie du schéma sans
     * {@code allOf} doit être VALID. Si quelqu'un retire le {@code allOf} du
     * schéma du classpath, la première assertion échoue (le payload devient
     * VALID) et l'assertion sur la présence des 6 entrées échoue aussi.
     */
    @Test
    @DisplayName("10. allOf prouvé causal : INVALID avec le schéma réel, VALID sans allOf")
    void allof_is_the_sole_cause_of_the_rejection_mutation_proof() {
        assertThat(schemaDocument.get("allOf"))
                .as("Le schéma du classpath doit contenir le bloc allOf corrigé")
                .isNotNull();
        assertThat(schemaDocument.get("allOf").size())
                .as("Le bloc allOf doit contenir les 6 entrées (prenom, nom, dateNaissance, "
                        + "lieuNaissance, nomIncorrect, nomCorrect)")
                .isEqualTo(6);

        // Payload A : prenom absent, missingFields sans prenom, reste complet.
        ObjectNode payloadA = validCompleteNode();
        ((ObjectNode) payloadA.get("data")).remove("prenom");
        assertThat(payloadA.get("missingFields").toString()).doesNotContain("prenom");

        Set<ValidationMessage> withAllOf = validate(payloadA);
        assertThat(withAllOf)
                .as("Schéma réel (avec allOf) : rejet attendu, obtenu %s", withAllOf)
                .isNotEmpty();
        assertThat(withAllOf)
                .anySatisfy(message -> {
                    // networknt nomme l'échec de `contains` « minContains »
                    // (« doit contenir au moins 1 élément… ») selon la version.
                    assertThat(Set.of("contains", "minContains")).contains(message.getType());
                    assertThat(pointer(message)).isEqualTo("/missingFields");
                    assertThat(message.getMessage()).contains("prenom");
                });

        // Mutation en mémoire : même schéma SANS allOf => le payload est accepte.
        Set<ValidationMessage> withoutAllOf = validate(payloadA, schemaWithoutAllOf());
        assertThat(withoutAllOf)
                .as("Schéma muté (sans allOf) : le payload A doit devenir VALID "
                        + "— preuve que allOf est la seule cause du rejet, obtenu %s",
                        withoutAllOf)
                .isEmpty();

        // Inverse : sans allOf, l'absence non déclarée passe (c'était le bug Phase E).
        JsonNode undeclared = fixtureJson("extraction_missing_undeclared.json");
        assertThat(validate(undeclared, schemaWithoutAllOf()))
                .as("Sans allOf, l'oublie silencieux passe => le test 5b protège bien le fix")
                .isEmpty();
        assertFalse(isValid(undeclared, schema), "Schéma réel : rejet obligatoire");
        assertTrue(isValid(payloadA, schemaWithoutAllOf()), "Schéma muté : acceptation obligatoire");
    }

    // ------------------------------------------------------------------
    // Compléments (architecture §11.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("JSON malformé (fixture tronquée) → rejet explicite")
    void malformed_fixture_is_rejected_as_invalid_json() {
        String text = fixtureText("extraction_malformed.json");

        assertThatThrownBy(() -> MAPPER.readTree(text))
                .isInstanceOf(JsonProcessingException.class);

        // Côté service E8 : ERR_PAYLOAD_INVALIDE, jamais de stack trace.
        List<ExtractionValidationService.SchemaViolation> violations = service.validate(text);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).code())
                .isEqualTo(com.adgendoc.domain.ErrorCode.ERR_PAYLOAD_INVALIDE);
    }

    @Test
    @DisplayName("Autres fixtures d'échec de §11.2 → INVALID avec le path/code attendu")
    void failing_fixtures_report_expected_paths_and_codes() {
        assertViolation("extraction_invalid_date.json",
                "/data/dateNaissance", com.adgendoc.domain.ErrorCode.ERR_DATE_FORMAT_INVALIDE);
        assertViolation("extraction_unknown_key.json",
                "/data/passeport", com.adgendoc.domain.ErrorCode.ERR_CHAMP_INCONNU);
        assertViolation("extraction_unsupported_document_type.json",
                "/documentType", com.adgendoc.domain.ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE);
        assertViolation("extraction_confidence_out_of_range.json",
                "/confidence", com.adgendoc.domain.ErrorCode.ERR_PAYLOAD_INVALIDE);
    }

    @Test
    @DisplayName("Type de document non supporté (fixture) → INVALID côté schéma aussi")
    void unsupported_document_type_fixture_is_invalid_at_schema_level() {
        JsonNode payload = fixtureJson("extraction_unsupported_document_type.json");
        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    @DisplayName("Confidence hors bornes (fixture) → INVALID côté schéma aussi")
    void confidence_out_of_range_fixture_is_invalid_at_schema_level() {
        JsonNode payload = fixtureJson("extraction_confidence_out_of_range.json");
        assertThat(validate(payload)).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void assertViolation(String fixture, String expectedPath,
                                 com.adgendoc.domain.ErrorCode expectedCode) {
        JsonNode payload = fixtureJson(fixture);

        assertThat(validate(payload))
                .as("Fixture %s : invalide au niveau schéma", fixture)
                .isNotEmpty();

        List<ExtractionValidationService.SchemaViolation> violations = service.validate(payload.toString());
        assertThat(violations)
                .as("Fixture %s : violation service attendue", fixture)
                .isNotEmpty();
        assertThat(violations.get(0).path()).isEqualTo(expectedPath);
        assertThat(violations.get(0).code()).isEqualTo(expectedCode);
    }
}
