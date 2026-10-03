package com.adgendoc.api;

import com.adgendoc.api.dto.ErrorResponse;
import com.adgendoc.application.NormalizationService;
import com.adgendoc.application.RequestService;
import com.adgendoc.application.ValidationService;
import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.ports.AuditPort;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.infrastructure.config.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F1 — une erreur base de données ({@code DataAccessException}, cas des
 * échecs repository/pessimistes) est convertie en {@code 500 DATABASE_ERROR}
 * avec message FR, {@code correlationId} conservé et aucune fuite de détail
 * technique (contrat API §2.3, AGENTS.md §14).
 */
class DatabaseErrorHandlerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(minimalService());

    @Test
    void data_access_exception_maps_to_500_database_error_without_leaking_details() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "corr-db-1");

        ResponseEntity<ErrorResponse> response = handler.handleDatabase(
                new DataAccessResourceFailureException(
                        "connection refused at jdbc:postgresql://secret-host:5432/adgendoc"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("DATABASE_ERROR");
        assertThat(body.message()).isEqualTo(ErrorCode.DATABASE_ERROR.getMessage());
        assertThat(body.correlationId()).isEqualTo("corr-db-1");
        assertThat(body.missingFields()).isEmpty();
        assertThat(body.fieldErrors()).isEmpty();

        String serialized = String.valueOf(body);
        assertThat(serialized)
                .doesNotContain("connection refused")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("secret-host")
                .doesNotContain("Exception");
    }

    private static RequestService minimalService() {
        RequestRepository unusedRepository = new RequestRepository() {
            @Override
            public DocumentRequest save(DocumentRequest request) {
                return request;
            }

            @Override
            public Optional<DocumentRequest> findById(UUID requestId) {
                return Optional.empty();
            }
        };
        AuditPort unusedAudit = new AuditPort() {
            @Override
            public void record(UUID requestId, String action, String actor,
                               String correlationId, Map<String, Object> details) {
                // aucune action : service jamais invoqué par ce test
            }
        };
        return new RequestService(new NormalizationService(), new ValidationService(CLOCK),
                unusedRepository, unusedAudit, CLOCK);
    }
}
