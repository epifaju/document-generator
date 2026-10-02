package com.adgendoc.application;

import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.RequestStatus;
import com.adgendoc.domain.exceptions.InvalidStatusException;
import com.adgendoc.domain.exceptions.RequestAlreadyClosedException;
import com.adgendoc.domain.exceptions.RequestNotFoundException;
import com.adgendoc.domain.ports.AuditPort;
import com.adgendoc.domain.ports.RequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final String CORRELATION_ID = "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70";

    private final InMemoryRequestRepository requestRepository = new InMemoryRequestRepository();
    private final RecordingAuditPort auditPort = new RecordingAuditPort();
    private final RequestService requestService = new RequestService(
            new NormalizationService(), new ValidationService(CLOCK), requestRepository,
            auditPort, CLOCK);

    @Test
    void create_valid_payload_is_validated_and_saved() {
        DocumentRequest request = requestService.create(DOCUMENT_TYPE, validData(), null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(request.getMissingFields()).isEmpty();
        assertThat(request.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(requestRepository.saveCount).isEqualTo(1);
        assertThat(requestRepository.findById(request.getRequestId())).containsSame(request);
        assertThat(auditPort.actions).containsExactly("REQUEST_CREATED");
    }

    @Test
    void create_with_missing_fields_is_persisted_in_canonical_order() {
        Map<String, Object> data = validData();
        data.remove("dateNaissance");
        data.remove("lieuNaissance");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(request.getMissingFields()).containsExactly("dateNaissance", "lieuNaissance");
        assertThat(requestRepository.saveCount).isEqualTo(1);
    }

    @Test
    void create_with_unknown_key_is_persisted_as_rejected() {
        Map<String, Object> data = validData();
        data.put("passeport", "X123");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(requestRepository.saveCount).isEqualTo(1);
        assertThat(request.getData()).doesNotContainKey("passeport");
    }

    private static Stream<Arguments> forbiddenKeysAndValues() {
        return Stream.of(
                Arguments.of("passeport", ""),
                Arguments.of("passeport", "   "),
                Arguments.of("passeport", null),
                Arguments.of("referenceDemande", ""),
                Arguments.of("referenceDemande", "   "),
                Arguments.of("referenceDemande", null));
    }

    @ParameterizedTest
    @MethodSource("forbiddenKeysAndValues")
    void create_checks_keys_on_raw_payload_before_normalization(String key, Object value) {
        RecordingValidationService recording = new RecordingValidationService();
        RequestService service = new RequestService(new NormalizationService(), recording,
                requestRepository, auditPort, CLOCK);
        Map<String, Object> data = validData();
        data.put(key, value);

        DocumentRequest request = service.create(DOCUMENT_TYPE, data, null, CORRELATION_ID);

        // Le contrôle des clés voit le payload BRUT : valeur vide/null encore présente
        // (une normalisation exécutée en amont l'aurait supprimée — règle N9).
        assertThat(recording.rawInputs).hasSize(1);
        assertThat(recording.rawInputs.get(0)).containsKey(key);
        assertThat(recording.rawInputs.get(0).get(key)).isEqualTo(value);

        // Code métier attendu (contrat pilote §5.3 clé inconnue / §5.1 clé réservée).
        assertThat(recording.capturedErrors).hasSize(1);
        ValidationOutcome.FieldError error = recording.capturedErrors.get(0);
        assertThat(error.field()).isEqualTo(key);
        assertThat(error.code()).isEqualTo("referenceDemande".equals(key)
                ? ErrorCode.ERR_CHAMP_RESERVE
                : ErrorCode.ERR_CHAMP_INCONNU);
        assertThat(error.message()).isNotBlank();

        // Comportement observable : REJECTED persisté, clé hors schéma non stockée, audité.
        assertThat(request.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(request.getMissingFields()).isEmpty();
        assertThat(requestRepository.saveCount).isOne();
        assertThat(request.getData()).doesNotContainKey(key);
        assertThat(auditPort.actions).containsExactly("REQUEST_CREATED");
    }

    @ParameterizedTest
    @MethodSource("forbiddenKeysAndValues")
    void patch_checks_keys_on_raw_payload_before_normalization(String key, Object value) {
        DocumentRequest created = requestService.create(DOCUMENT_TYPE, validData(), null,
                CORRELATION_ID);
        int savesBeforePatch = requestRepository.saveCount;

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(key, value);

        assertThatThrownBy(() -> requestService.patch(created.getRequestId(), patch))
                .isInstanceOf(ValidationRejectedException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR)
                .satisfies(thrown -> {
                    List<ValidationOutcome.FieldError> errors =
                            ((ValidationRejectedException) thrown).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).field()).isEqualTo(key);
                    assertThat(errors.get(0).code()).isEqualTo("referenceDemande".equals(key)
                            ? ErrorCode.ERR_CHAMP_RESERVE
                            : ErrorCode.ERR_CHAMP_INCONNU);
                    assertThat(errors.get(0).message()).isNotBlank();
                });

        assertThat(requestRepository.saveCount).isEqualTo(savesBeforePatch);
        DocumentRequest unchanged = requestService.get(created.getRequestId());
        assertThat(unchanged.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(unchanged.getData()).doesNotContainKey(key);
    }

    @ParameterizedTest
    @MethodSource("forbiddenKeysAndValues")
    void validate_checks_keys_on_raw_payload_before_normalization(String key, Object value) {
        Map<String, Object> storedData = validData();
        storedData.put(key, value);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentType", DOCUMENT_TYPE);
        payload.put("data", storedData);
        DocumentRequest stored = DocumentRequest.create(DOCUMENT_TYPE, payload, CORRELATION_ID,
                Instant.now(CLOCK));
        stored.transitionTo(RequestStatus.VALIDATED, List.of(), Instant.now(CLOCK));
        requestRepository.save(stored);
        int savesBeforeValidate = requestRepository.saveCount;

        RecordingValidationService recording = new RecordingValidationService();
        RequestService service = new RequestService(new NormalizationService(), recording,
                requestRepository, auditPort, CLOCK);

        DocumentRequest revalidated = service.validate(stored.getRequestId());

        // Payload BRUT contrôlé avant normalisation + code métier attendu.
        assertThat(recording.rawInputs).hasSize(1);
        assertThat(recording.rawInputs.get(0)).containsKey(key);
        assertThat(recording.rawInputs.get(0).get(key)).isEqualTo(value);
        assertThat(recording.capturedErrors).hasSize(1);
        assertThat(recording.capturedErrors.get(0).field()).isEqualTo(key);
        assertThat(recording.capturedErrors.get(0).code()).isEqualTo(
                "referenceDemande".equals(key)
                        ? ErrorCode.ERR_CHAMP_RESERVE
                        : ErrorCode.ERR_CHAMP_INCONNU);

        // Même comportement qu'en CREATE : REJECTED persisté, clé purgée, audité.
        assertThat(revalidated.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(requestRepository.saveCount).isEqualTo(savesBeforeValidate + 1);
        assertThat(revalidated.getData()).doesNotContainKey(key);
        assertThat(auditPort.actions).containsExactly("REQUEST_VALIDATED");
    }

    @Test
    void create_with_unsupported_document_type_is_not_saved() {
        assertThatThrownBy(() -> requestService.create("ACTE_NAISSANCE", validData(), null,
                CORRELATION_ID))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE);

        assertThat(requestRepository.saveCount).isZero();
        assertThat(auditPort.actions).isEmpty();
    }

    @Test
    void create_normalizes_french_date_to_iso() {
        Map<String, Object> data = validData();
        data.put("dateNaissance", "12/05/1985");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(request.getData()).containsEntry("dateNaissance", "1985-05-12");
    }

    @Test
    void create_keeps_iso_date_untouched() {
        Map<String, Object> data = validData();
        data.put("dateNaissance", "1985-05-12");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(request.getData()).containsEntry("dateNaissance", "1985-05-12");
    }

    @Test
    void create_with_invalid_format_is_persisted_as_rejected() {
        Map<String, Object> data = validData();
        data.put("nom", "Gomes@123");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(requestRepository.saveCount).isEqualTo(1);
    }

    @Test
    void create_with_identical_names_is_persisted_as_rejected() {
        Map<String, Object> data = validData();
        data.put("nomIncorrect", "Maria Gomez");
        data.put("nomCorrect", "maria gomez");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(requestRepository.saveCount).isEqualTo(1);
    }

    @Test
    void create_with_blank_required_field_is_missing_information_not_invalid() {
        Map<String, Object> data = validData();
        data.put("nom", "   ");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(request.getStatus()).isNotEqualTo(RequestStatus.DRAFT);
        assertThat(request.getMissingFields()).containsExactly("nom");
        assertThat(request.getData()).doesNotContainKey("nom");
        assertThat(requestRepository.saveCount).isEqualTo(1);
        assertThat(auditPort.actions).containsExactly("REQUEST_CREATED");
    }

    @Test
    void create_reports_missing_fields_in_canonical_order_without_inventing_defaults() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nomCorrect", "Maria Gomes");
        data.put("nomIncorrect", "Maria Gomez");
        data.put("prenom", "Maria");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(request.getMissingFields())
                .containsExactly("nom", "dateNaissance", "lieuNaissance");
        Map<String, Object> stored = request.getData();
        assertThat(stored.keySet())
                .containsOnly("prenom", "nomIncorrect", "nomCorrect", "langueDocument");
        assertThat(stored)
                .doesNotContainKey("nom")
                .doesNotContainKey("dateNaissance")
                .doesNotContainKey("lieuNaissance");
    }

    @Test
    void create_normalizes_values_before_running_validation() {
        Map<String, Object> data = validData();
        data.put("sexe", "f");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(request.getData()).containsEntry("sexe", "F");
        assertThat(request.getData()).containsEntry("langueDocument", "FR");
    }

    @Test
    void extraction_confidence_never_influences_validation() {
        DocumentRequest lowConfidence = requestService.create(DOCUMENT_TYPE, validData(),
                Map.<String, Object>of("confidence", 0.01), CORRELATION_ID);

        assertThat(lowConfidence.getStatus()).isEqualTo(RequestStatus.VALIDATED);

        Map<String, Object> incomplete = validData();
        incomplete.remove("nomCorrect");
        DocumentRequest highConfidence = requestService.create(DOCUMENT_TYPE, incomplete,
                Map.<String, Object>of("confidence", 0.99), CORRELATION_ID);

        assertThat(highConfidence.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(highConfidence.getMissingFields()).containsExactly("nomCorrect");
        assertThat(highConfidence.getData()).doesNotContainKey("nomCorrect");
    }

    private static Stream<Arguments> invalidExtractions() {
        Map<String, Object> unknownKey = new LinkedHashMap<>();
        unknownKey.put("confidence", 0.9);
        unknownKey.put("source", "ollama");

        Map<String, Object> confidenceAsString = new LinkedHashMap<>();
        confidenceAsString.put("confidence", "0.9");

        Map<String, Object> confidenceAboveRange = new LinkedHashMap<>();
        confidenceAboveRange.put("confidence", 1.5);

        Map<String, Object> confidenceBelowRange = new LinkedHashMap<>();
        confidenceBelowRange.put("confidence", -0.01);

        Map<String, Object> confidenceNull = new LinkedHashMap<>();
        confidenceNull.put("confidence", null);

        Map<String, Object> confidenceBoolean = new LinkedHashMap<>();
        confidenceBoolean.put("confidence", Boolean.TRUE);

        Map<String, Object> modelIdNumeric = new LinkedHashMap<>();
        modelIdNumeric.put("modelId", 42);

        Map<String, Object> promptVersionNumeric = new LinkedHashMap<>();
        promptVersionNumeric.put("promptVersion", 7);

        return Stream.of(
                Arguments.of(unknownKey),
                Arguments.of(confidenceAsString),
                Arguments.of(confidenceAboveRange),
                Arguments.of(confidenceBelowRange),
                Arguments.of(confidenceNull),
                Arguments.of(confidenceBoolean),
                Arguments.of(modelIdNumeric),
                Arguments.of(promptVersionNumeric));
    }

    @ParameterizedTest
    @MethodSource("invalidExtractions")
    void create_with_invalid_extraction_block_is_not_persisted(Map<String, Object> extraction) {
        assertThatThrownBy(() -> requestService.create(DOCUMENT_TYPE, validData(), extraction,
                CORRELATION_ID))
                .isInstanceOf(ValidationRejectedException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR)
                .satisfies(thrown -> assertThat(
                        ((ValidationRejectedException) thrown).getFieldErrors())
                        .extracting(ValidationOutcome.FieldError::field)
                        .containsOnly("extraction"));

        assertThat(requestRepository.saveCount).isZero();
        assertThat(auditPort.actions).isEmpty();
    }

    @Test
    void create_with_invalid_extraction_and_unknown_data_key_is_not_persisted() {
        Map<String, Object> data = validData();
        data.put("passeport", "X123");
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("source", "ollama");

        assertThatThrownBy(() -> requestService.create(DOCUMENT_TYPE, data, extraction,
                CORRELATION_ID))
                .isInstanceOf(ValidationRejectedException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);

        assertThat(requestRepository.saveCount).isZero();
        assertThat(auditPort.actions).isEmpty();
    }

    @Test
    void create_stores_only_allowed_extraction_keys() {
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("confidence", 0.97);
        extraction.put("modelId", "ollama/llama3.1");
        extraction.put("promptVersion", "v1");

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, validData(), extraction,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(request.getPayload()).containsEntry("extraction", extraction);
    }

    @Test
    void create_without_extraction_keeps_payload_without_extraction_block() {
        DocumentRequest withoutExtraction = requestService.create(DOCUMENT_TYPE, validData(),
                null, CORRELATION_ID);
        DocumentRequest emptyExtraction = requestService.create(DOCUMENT_TYPE, validData(),
                new LinkedHashMap<>(), CORRELATION_ID);

        assertThat(withoutExtraction.getPayload()).doesNotContainKey("extraction");
        assertThat(emptyExtraction.getPayload()).doesNotContainKey("extraction");
    }

    @Test
    void create_stores_partial_extraction_without_adding_default_keys() {
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("confidence", 0.5);

        DocumentRequest request = requestService.create(DOCUMENT_TYPE, validData(), extraction,
                CORRELATION_ID);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        Object stored = request.getPayload().get("extraction");
        assertThat(stored).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> storedExtraction = (Map<String, Object>) stored;
        assertThat(storedExtraction).containsOnlyKeys("confidence");
        assertThat(storedExtraction).containsEntry("confidence", 0.5);
    }

    @Test
    void validate_on_terminal_request_is_refused() {
        Map<String, Object> data = validData();
        data.put("passeport", "X123");
        DocumentRequest rejected = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);
        assertThat(rejected.getStatus()).isEqualTo(RequestStatus.REJECTED);

        assertThatThrownBy(() -> requestService.validate(rejected.getRequestId()))
                .isInstanceOf(InvalidStatusException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STATUS);
        assertThat(requestService.get(rejected.getRequestId()).getStatus())
                .isEqualTo(RequestStatus.REJECTED);
    }

    @Test
    void get_returns_the_persisted_instance() {
        DocumentRequest created = requestService.create(DOCUMENT_TYPE, validData(), null,
                CORRELATION_ID);

        DocumentRequest loaded = requestService.get(created.getRequestId());

        assertThat(loaded).isSameAs(created);
        assertThat(loaded).isSameAs(requestRepository.store.get(created.getRequestId()));
    }

    @Test
    void get_unknown_request_throws_not_found() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> requestService.get(unknownId))
                .isInstanceOf(RequestNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUEST_NOT_FOUND)
                .hasFieldOrPropertyWithValue("requestId", unknownId);
    }

    @Test
    void patch_completes_missing_information_into_validated() {
        Map<String, Object> data = validData();
        data.remove("dateNaissance");
        data.remove("lieuNaissance");
        DocumentRequest created = requestService.create(DOCUMENT_TYPE, data, null, CORRELATION_ID);
        assertThat(created.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("dateNaissance", "12/05/1985");
        patch.put("lieuNaissance", "Bissau");

        DocumentRequest patched = requestService.patch(created.getRequestId(), patch);

        assertThat(patched.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(patched.getMissingFields()).isEmpty();
        assertThat(patched.getData()).containsEntry("dateNaissance", "1985-05-12");
        assertThat(requestRepository.saveCount).isEqualTo(2);
        assertThat(auditPort.actions).containsExactly("REQUEST_CREATED", "REQUEST_PATCHED");
    }

    @Test
    void patch_with_unknown_key_is_not_persisted() {
        DocumentRequest created = requestService.create(DOCUMENT_TYPE, validData(), null,
                CORRELATION_ID);
        int savesBeforePatch = requestRepository.saveCount;

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("passeport", "X123");

        assertThatThrownBy(() -> requestService.patch(created.getRequestId(), patch))
                .isInstanceOf(ValidationRejectedException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);

        assertThat(requestRepository.saveCount).isEqualTo(savesBeforePatch);
        DocumentRequest unchanged = requestService.get(created.getRequestId());
        assertThat(unchanged.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(unchanged.getData()).doesNotContainKey("passeport");
    }

    @Test
    void patch_on_terminal_request_is_refused() {
        Map<String, Object> data = validData();
        data.put("passeport", "X123");
        DocumentRequest rejected = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);
        assertThat(rejected.getStatus()).isEqualTo(RequestStatus.REJECTED);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("prenom", "Maria");

        assertThatThrownBy(() -> requestService.patch(rejected.getRequestId(), patch))
                .isInstanceOf(RequestAlreadyClosedException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUEST_ALREADY_CLOSED);
    }

    @Test
    void validate_on_validated_request_stays_validated() {
        DocumentRequest created = requestService.create(DOCUMENT_TYPE, validData(), null,
                CORRELATION_ID);

        DocumentRequest validated = requestService.validate(created.getRequestId());

        assertThat(validated.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(validated.getMissingFields()).isEmpty();
        assertThat(requestRepository.saveCount).isEqualTo(2);
        assertThat(auditPort.actions).containsExactly("REQUEST_CREATED", "REQUEST_VALIDATED");
    }

    @Test
    void validate_on_missing_request_keeps_missing_information() {
        Map<String, Object> data = validData();
        data.remove("nomCorrect");
        DocumentRequest created = requestService.create(DOCUMENT_TYPE, data, null,
                CORRELATION_ID);
        assertThat(created.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);

        DocumentRequest validated = requestService.validate(created.getRequestId());

        assertThat(validated.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(validated.getMissingFields()).containsExactly("nomCorrect");
    }

    @Test
    void audit_details_contain_no_personal_data() {
        requestService.create(DOCUMENT_TYPE, validData(),
                Map.<String, Object>of("confidence", 0.97), CORRELATION_ID);

        assertThat(auditPort.details).hasSize(1);
        assertThat(auditPort.details.get(0).keySet())
                .containsExactlyInAnyOrder("documentType", "status", "nbMissingFields");
        assertThat(auditPort.actors).containsExactly("system");
        assertThat(auditPort.correlationIds).containsExactly(CORRELATION_ID);
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

    private static final class RecordingValidationService extends ValidationService {

        private final List<Map<String, Object>> rawInputs = new ArrayList<>();
        private final List<ValidationOutcome.FieldError> capturedErrors = new ArrayList<>();

        private RecordingValidationService() {
            super(CLOCK);
        }

        @Override
        public List<ValidationOutcome.FieldError> checkKeys(Map<String, Object> data) {
            rawInputs.add(data == null ? Map.of() : new LinkedHashMap<>(data));
            List<ValidationOutcome.FieldError> errors = super.checkKeys(data);
            if (!errors.isEmpty()) {
                capturedErrors.clear();
                capturedErrors.addAll(errors);
            }
            return errors;
        }
    }

    private static final class InMemoryRequestRepository implements RequestRepository {

        private final Map<UUID, DocumentRequest> store = new LinkedHashMap<>();
        private int saveCount;

        @Override
        public DocumentRequest save(DocumentRequest request) {
            saveCount++;
            store.put(request.getRequestId(), request);
            return request;
        }

        @Override
        public Optional<DocumentRequest> findById(UUID requestId) {
            return Optional.ofNullable(store.get(requestId));
        }
    }

    private static final class RecordingAuditPort implements AuditPort {

        private final List<String> actions = new ArrayList<>();
        private final List<String> actors = new ArrayList<>();
        private final List<String> correlationIds = new ArrayList<>();
        private final List<Map<String, Object>> details = new ArrayList<>();

        @Override
        public void record(UUID requestId, String action, String actor, String correlationId,
                           Map<String, Object> record) {
            actions.add(action);
            actors.add(actor);
            correlationIds.add(correlationId);
            details.add(new LinkedHashMap<>(record));
        }
    }
}
