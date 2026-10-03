package com.adgendoc.infrastructure.persistence;

import com.adgendoc.domain.ports.AuditPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase F1 — {@code AuditAdapter} sur vrai repository : les clés nominatives
 * sont refusées (« SANS PII », V1__init.sql), les détails techniques sont
 * persistés avec roundtrip JSONB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditAdapterTest {

    @Autowired
    private AuditPort auditPort;

    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanAuditLog() {
        auditLogJpaRepository.deleteAll();
    }

    @Test
    void pii_key_is_refused_and_nothing_is_persisted() {
        Map<String, Object> details = Map.of("nom", "Maria Gomes");
        long before = auditLogJpaRepository.count();

        // AuditAdapter est un @Repository : PersistenceExceptionTranslation
        // PostProcessor (Boot par défaut) convertit l'IllegalArgumentException
        // d'origine en InvalidDataAccessApiUsageException, en conservant le
        // message (clé PII) et la cause. Aucune ligne ne doit être persistée.
        assertThatThrownBy(() -> auditPort.record(UUID.randomUUID(), "TEST_ACTION", "system",
                "corr-1", details))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nom");

        assertThat(auditLogJpaRepository.count()).isEqualTo(before);
    }

    @Test
    void pii_key_refusal_is_case_insensitive() {
        Map<String, Object> details = Map.of("NOM", "Maria Gomes");

        assertThatThrownBy(() -> auditPort.record(UUID.randomUUID(), "TEST_ACTION", "system",
                "corr-1", details))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("NOM");
        assertThat(auditLogJpaRepository.count()).isZero();
    }

    @Test
    void technical_details_are_persisted_with_roundtrip_and_utc_timestamp() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> details = Map.of(
                "documentType", "ATTESTATION_CONCORDANCE",
                "status", "VALIDATED",
                "nbMissingFields", 0);

        auditPort.record(requestId, "REQUEST_CREATED", "system", "corr-technical", details);

        List<AuditLogEntity> rows = auditLogJpaRepository.findAll();
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getRequestId()).isEqualTo(requestId);
        assertThat(row.getAction()).isEqualTo("REQUEST_CREATED");
        assertThat(row.getActor()).isEqualTo("system");
        assertThat(row.getCorrelationId()).isEqualTo("corr-technical");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getDetails())
                .containsEntry("documentType", "ATTESTATION_CONCORDANCE")
                .containsEntry("status", "VALIDATED")
                .containsEntry("nbMissingFields", 0);
        assertThat(row.getCreatedAt()).isBeforeOrEqualTo(Instant.now(clock).plusSeconds(1));
    }
}
