package com.adgendoc.application;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.RequestStatus;
import com.adgendoc.application.ValidationOutcome.FieldError;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";

    private final NormalizationService normalizationService = new NormalizationService();
    private final ValidationService validationService = new ValidationService(CLOCK);

    @Test
    void complete_payload_is_validated() {
        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, validData());

        assertThat(outcome.status()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(outcome.missingFields()).isEmpty();
        assertThat(outcome.fieldErrors()).isEmpty();
        assertThat(outcome.persistable()).isTrue();
    }

    @Test
    void missing_fields_follow_canonical_order() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nomCorrect", "Maria Gomes");
        data.put("lieuNaissance", "Bissau");
        data.put("nomIncorrect", "Maria Gomez");
        data.put("nom", "Gomes");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(outcome.missingFields()).containsExactly("prenom", "dateNaissance");
        assertThat(outcome.fieldErrors()).isEmpty();
        assertThat(outcome.persistable()).isTrue();
    }

    @Test
    void unknown_key_is_rejected() {
        Map<String, Object> data = validData();
        data.put("passeport", "X123");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.persistable()).isTrue();
        assertThat(outcome.fieldErrors()).hasSize(1);
        FieldError error = outcome.fieldErrors().get(0);
        assertThat(error.field()).isEqualTo("passeport");
        assertThat(error.code()).isEqualTo(ErrorCode.ERR_CHAMP_INCONNU);
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void reserved_key_is_rejected() {
        Map<String, Object> data = validData();
        data.put("referenceDemande", "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.persistable()).isTrue();
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).code()).isEqualTo(ErrorCode.ERR_CHAMP_RESERVE);
    }

    @Test
    void future_birth_date_is_rejected() {
        Map<String, Object> data = validData();
        data.put("dateNaissance", "2026-06-16");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).code()).isEqualTo(ErrorCode.ERR_DATE_FUTUR);
    }

    @Test
    void birth_date_equal_to_today_is_accepted() {
        Map<String, Object> data = validData();
        data.put("dateNaissance", "2026-06-15");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.VALIDATED);
    }

    @Test
    void impossible_calendar_date_is_rejected_after_normalization() {
        Map<String, Object> data = validData();
        data.put("dateNaissance", "31/02/1985");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE,
                normalizationService.normalize(data));

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).code())
                .isEqualTo(ErrorCode.ERR_DATE_CALENDRIER_INVALIDE);
    }

    @Test
    void non_iso_date_is_rejected_as_format_error() {
        Map<String, Object> data = validData();
        data.put("dateNaissance", "12/05/1985");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).code())
                .isEqualTo(ErrorCode.ERR_DATE_FORMAT_INVALIDE);
    }

    @Test
    void invalid_email_is_rejected() {
        Map<String, Object> data = validData();
        data.put("contactEmail", "maria.gomes@");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).code()).isEqualTo(ErrorCode.ERR_EMAIL_INVALIDE);
    }

    @Test
    void invalid_sexe_is_rejected_with_accepted_values() {
        Map<String, Object> data = validData();
        data.put("sexe", "H");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.fieldErrors()).hasSize(1);
        FieldError error = outcome.fieldErrors().get(0);
        assertThat(error.field()).isEqualTo("sexe");
        assertThat(error.code()).isEqualTo(ErrorCode.ERR_ENUM_INVALIDE);
        assertThat(error.message()).contains("Valeurs acceptées").contains("M, F");
    }

    @Test
    void identical_names_after_normalization_are_rejected() {
        Map<String, Object> data = validData();
        data.put("nomIncorrect", "Maria Gomez");
        data.put("nomCorrect", "maria gomez ");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).code())
                .isEqualTo(ErrorCode.ERR_NOM_CONCORDANCE_IDENTIQUE);
    }

    @Test
    void unsupported_document_type_is_not_persistable() {
        ValidationOutcome outcome = validationService.validate("ACTE_NAISSANCE", validData());

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.persistable()).isFalse();
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).field()).isEqualTo("documentType");
        assertThat(outcome.fieldErrors().get(0).code())
                .isEqualTo(ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE);
    }

    @Test
    void forbidden_character_is_rejected() {
        Map<String, Object> data = validData();
        data.put("nom", "Gomes@123");

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).code())
                .isEqualTo(ErrorCode.ERR_FORMAT_TEXTE_INVALIDE);
    }

    @Test
    void exceeding_length_is_rejected() {
        Map<String, Object> data = validData();
        data.put("prenom", "a".repeat(101));

        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, data);

        assertThat(outcome.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).code()).isEqualTo(ErrorCode.ERR_LONGUEUR_DEPASSEE);
    }

    @Test
    void absent_optional_fields_are_ignored() {
        ValidationOutcome outcome = validationService.validate(DOCUMENT_TYPE, validData());

        assertThat(outcome.status()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(outcome.fieldErrors()).isEmpty();
    }

    @Test
    void check_document_type_supported_is_persistable() {
        ValidationOutcome outcome = validationService.checkDocumentType(DOCUMENT_TYPE);

        assertThat(outcome.persistable()).isTrue();
        assertThat(outcome.fieldErrors()).isEmpty();
    }

    @Test
    void check_document_type_unsupported_is_not_persistable() {
        ValidationOutcome outcome = validationService.checkDocumentType("ACTE_NAISSANCE");

        assertThat(outcome.persistable()).isFalse();
        assertThat(outcome.fieldErrors()).hasSize(1);
        assertThat(outcome.fieldErrors().get(0).field()).isEqualTo("documentType");
        assertThat(outcome.fieldErrors().get(0).code())
                .isEqualTo(ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE);
    }

    @Test
    void sanitize_extraction_absent_or_empty_is_accepted() {
        assertThat(validationService.sanitizeExtraction(null)).isEmpty();
        assertThat(validationService.sanitizeExtraction(Map.of())).isEmpty();
    }

    @Test
    void sanitize_extraction_keeps_only_allowed_keys() {
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("confidence", 0.97);
        extraction.put("modelId", "ollama/llama3.1");
        extraction.put("promptVersion", "v1");

        assertThat(validationService.sanitizeExtraction(extraction))
                .containsExactlyInAnyOrderEntriesOf(extraction);
    }

    @Test
    void sanitize_extraction_rejects_unknown_key() {
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("confidence", 0.97);
        extraction.put("source", "ollama");

        assertThatThrownBy(() -> validationService.sanitizeExtraction(extraction))
                .isInstanceOf(ValidationRejectedException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR)
                .satisfies(thrown -> {
                    ValidationRejectedException exception = (ValidationRejectedException) thrown;
                    assertThat(exception.getFieldErrors()).hasSize(1);
                    assertThat(exception.getFieldErrors().get(0).field()).isEqualTo("extraction");
                    assertThat(exception.getFieldErrors().get(0).code())
                            .isEqualTo(ErrorCode.ERR_PAYLOAD_INVALIDE);
                });
    }

    @Test
    void sanitize_extraction_rejects_non_numeric_confidence() {
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("confidence", "0.97");

        assertThatThrownBy(() -> validationService.sanitizeExtraction(extraction))
                .isInstanceOf(ValidationRejectedException.class);
    }

    @Test
    void sanitize_extraction_rejects_confidence_out_of_range() {
        Map<String, Object> above = new LinkedHashMap<>();
        above.put("confidence", 1.5);
        Map<String, Object> below = new LinkedHashMap<>();
        below.put("confidence", -0.01);

        assertThatThrownBy(() -> validationService.sanitizeExtraction(above))
                .isInstanceOf(ValidationRejectedException.class);
        assertThatThrownBy(() -> validationService.sanitizeExtraction(below))
                .isInstanceOf(ValidationRejectedException.class);
    }

    @Test
    void sanitize_extraction_rejects_non_string_model_id_and_prompt_version() {
        Map<String, Object> modelId = new LinkedHashMap<>();
        modelId.put("modelId", 42);
        Map<String, Object> promptVersion = new LinkedHashMap<>();
        promptVersion.put("promptVersion", 7);

        assertThatThrownBy(() -> validationService.sanitizeExtraction(modelId))
                .isInstanceOf(ValidationRejectedException.class);
        assertThatThrownBy(() -> validationService.sanitizeExtraction(promptVersion))
                .isInstanceOf(ValidationRejectedException.class);
    }

    @Test
    void sanitize_extraction_accepts_boundary_confidence_values() {
        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("confidence", 0);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("confidence", 1);

        assertThat(validationService.sanitizeExtraction(zero)).containsEntry("confidence", 0);
        assertThat(validationService.sanitizeExtraction(one)).containsEntry("confidence", 1);
    }

    private Map<String, Object> validData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("prenom", "Maria");
        data.put("nom", "Gomes");
        data.put("dateNaissance", "1985-05-12");
        data.put("lieuNaissance", "Bissau");
        data.put("nomIncorrect", "Maria Gomez");
        data.put("nomCorrect", "Maria Gomes");
        return data;
    }
}
