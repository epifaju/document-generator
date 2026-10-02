package com.adgendoc.domain;

import com.adgendoc.domain.exceptions.InvalidStatusException;
import com.adgendoc.domain.exceptions.RequestAlreadyClosedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestStatusTransitionTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void allowed_transitions_match_the_contract_matrix_t1_to_t14() {
        assertThat(RequestStatus.DRAFT.allowedTransitions())
                .containsExactlyInAnyOrder(RequestStatus.VALIDATED,
                        RequestStatus.MISSING_INFORMATION, RequestStatus.REJECTED);
        assertThat(RequestStatus.MISSING_INFORMATION.allowedTransitions())
                .containsExactlyInAnyOrder(RequestStatus.VALIDATED, RequestStatus.REJECTED,
                        RequestStatus.MISSING_INFORMATION);
        assertThat(RequestStatus.VALIDATED.allowedTransitions())
                .containsExactlyInAnyOrder(RequestStatus.MISSING_INFORMATION,
                        RequestStatus.GENERATED, RequestStatus.FAILED, RequestStatus.REJECTED,
                        RequestStatus.VALIDATED);
        assertThat(RequestStatus.FAILED.allowedTransitions())
                .containsExactlyInAnyOrder(RequestStatus.VALIDATED, RequestStatus.REJECTED,
                        RequestStatus.MISSING_INFORMATION);
        assertThat(RequestStatus.GENERATED.allowedTransitions()).isEmpty();
        assertThat(RequestStatus.REJECTED.allowedTransitions()).isEmpty();
    }

    @Test
    void no_status_can_transition_back_to_draft_after_creation() {
        for (RequestStatus status : RequestStatus.values()) {
            if (status == RequestStatus.DRAFT) {
                continue;
            }
            assertThat(status.canTransitionTo(RequestStatus.DRAFT))
                    .as("%s -> DRAFT doit être interdit", status)
                    .isFalse();
        }

        DocumentRequest validated = request(RequestStatus.VALIDATED);
        assertThatThrownBy(() -> validated.transitionTo(RequestStatus.DRAFT, List.of(), NOW))
                .isInstanceOf(InvalidStatusException.class);

        DocumentRequest generated = request(RequestStatus.GENERATED);
        assertThatThrownBy(() -> generated.transitionTo(RequestStatus.DRAFT, List.of(), NOW))
                .isInstanceOf(RequestAlreadyClosedException.class);
    }

    @Test
    void terminal_statuses_generated_and_rejected_are_final() {
        for (RequestStatus terminal : Set.of(RequestStatus.GENERATED, RequestStatus.REJECTED)) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(terminal.allowedTransitions()).isEmpty();

            DocumentRequest request = request(terminal);
            assertThatThrownBy(() -> request.transitionTo(RequestStatus.VALIDATED, List.of(), NOW))
                    .isInstanceOf(RequestAlreadyClosedException.class);
            assertThat(request.getStatus()).isEqualTo(terminal);
        }
    }

    @Test
    void generation_is_allowed_only_from_validated() {
        for (RequestStatus status : RequestStatus.values()) {
            assertThat(status.canGenerate())
                    .as("canGenerate pour %s", status)
                    .isEqualTo(status == RequestStatus.VALIDATED);
        }
    }

    @Test
    void missing_information_transition_requires_a_non_empty_missing_field_list() {
        DocumentRequest validated = request(RequestStatus.VALIDATED);

        assertThatThrownBy(
                () -> validated.transitionTo(RequestStatus.MISSING_INFORMATION, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);

        validated.transitionTo(RequestStatus.MISSING_INFORMATION, List.of("nomCorrect"), NOW);

        assertThat(validated.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(validated.getMissingFields()).containsExactly("nomCorrect");

        validated.transitionTo(RequestStatus.VALIDATED, List.of("ignored"), NOW);

        assertThat(validated.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(validated.getMissingFields()).isEmpty();
    }

    private DocumentRequest request(RequestStatus status) {
        return new DocumentRequest(UUID.randomUUID(), "ATTESTATION_CONCORDANCE", status,
                Map.of(), List.of(), "corr-1", NOW, NOW, 0);
    }
}
