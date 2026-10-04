package com.adgendoc.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.adgendoc.api.dto.ErrorResponse;
import com.adgendoc.application.NormalizationService;
import com.adgendoc.application.RequestService;
import com.adgendoc.application.ValidationService;
import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.ports.AuditPort;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.infrastructure.config.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase H.2 — <b>S-1 : journalisation sûre des erreurs base de données</b>
 * (AGENTS.md §13 « ne pas journaliser de données personnelles inutilement »,
 * §14 « ne pas exposer de stack trace », §15 observabilité minimale).
 *
 * <p>Test comportemental : un <b>synthetic hostile</b> {@code DataAccessException}
 * contenant des marqueurs reconnaissables de PII / secret / SQL / JDBC est
 * passé aux handlers {@code handleDatabase}, {@code handleUnexpected} et
 * {@code handleGenerationFailure} (ce dernier reçoit les
 * {@code DataAccessException} enveloppées par {@code asGenerationFailure}).
 * Le test prouve que :</p>
 *
 * <ul>
 *   <li>les enregistrements INFO/ERROR ne reproduisent <b>aucun</b> marqueur
 *       (message brut, arguments, cause incluse) ;</li>
 *   <li>aucun événement INFO/ERROR ne porte de {@code throwable} (donc pas de
 *       stack trace ni de message d'exception à ce niveau) ;</li>
 *   <li>les métadonnées sûres restent présentes : classe d'exception,
 *       SQLState, code applicatif, {@code correlationId} ;</li>
 *   <li>la stack trace reste disponible <b>uniquement</b> au niveau DEBUG,
 *       niveau coupé par la politique packagée ({@code application.yml} :
 *       racine {@code INFO}).</li>
 * </ul>
 *
 * <p>Le contrat HTTP n'est pas touché : vérifié par
 * {@link DatabaseErrorHandlerTest} (réponse {@code 500 DATABASE_ERROR} sans
 * détail technique).</p>
 */
class DatabaseErrorLogSanitizationTest {

    private static final String HOSTILE_DB_MESSAGE = """
            ERROR: duplicate key value violates unique constraint "document_request_pkey"
              Detail: Key (prenom, nom, date_naissance)=(Maria, Gomes, 1985-05-12) already exists.
              SQL: INSERT INTO document_request (prenom, nom, contact_telephone) VALUES ($1, $2, $3)
              Bind values: [Maria, Gomes, +33612345678]
              JDBC: jdbc:postgresql://secret-host:5432/adgendoc user=adgendoc password=S3cr3t_Pg_PASS
              Authorization: Bearer sk_live_51H8HostileMarker
            """;

    /** Marqueurs qui ne doivent JAMAIS apparaître dans un log INFO/ERROR. */
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "Maria", "Gomes", "1985-05-12", "+33612345678",
            "INSERT INTO", "Bind values", "duplicate key", "document_request_pkey",
            "secret-host", "S3cr3t_Pg_PASS", "sk_live_51H8HostileMarker",
            "password", "jdbc:postgresql");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(minimalService());
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private Logger targetLogger;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        targetLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        originalLevel = targetLogger.getLevel();
        appender.list.clear();
        appender.start();
        targetLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        targetLogger.detachAppender(appender);
        targetLogger.setLevel(originalLevel);
        appender.stop();
    }

    @Test
    void database_error_logs_at_info_and_error_carry_no_hostile_content() {
        targetLogger.setLevel(Level.INFO);

        ResponseEntity<ErrorResponse> database = handler.handleDatabase(
                hostileDatabaseException(), request("corr-s1-db"));
        ResponseEntity<ErrorResponse> unexpected = handler.handleUnexpected(
                new IllegalStateException(HOSTILE_DB_MESSAGE), request("corr-s1-unexpected"));
        // Revue H.2 : DocumentGenerationService enveloppe les DataAccessException
        // (asGenerationFailure) — ce canal ERROR doit être assaini lui aussi.
        ResponseEntity<ErrorResponse> generation = handler.handleGenerationFailure(
                new DocumentGenerationException(HOSTILE_DB_MESSAGE, hostileDatabaseException()),
                request("corr-s1-generation"));

        // Contrat HTTP inchangé (S-1 : « do not change HTTP error contracts »).
        assertThat(database.getStatusCode().value()).isEqualTo(500);
        assertThat(unexpected.getStatusCode().value()).isEqualTo(500);
        assertThat(generation.getStatusCode().value()).isEqualTo(500);

        List<ILoggingEvent> errorEvents = eventsAt(Level.ERROR);
        assertThat(errorEvents)
                .as("au moins un log ERROR par handler (test non vide)")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(errorEvents)
                .as("aucun événement ERROR ne doit porter la stack trace "
                        + "(le message brut de l'exception fuiterait)")
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        assertThat(appender.list)
                .as("aucun événement INFO+ ne doit porter de throwable")
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());

        for (String record : capturedRecords()) {
            for (String marker : FORBIDDEN_MARKERS) {
                assertThat(record)
                        .as("marqueur PII/secret/SQL présent dans un log INFO/ERROR : « %s »",
                                marker)
                        .doesNotContain(marker);
            }
        }

        // Métadonnées sûres toujours présentes (observabilité §15).
        String databaseRecord = formattedMessageContaining("corr-s1-db");
        assertThat(databaseRecord)
                .contains("DATABASE_ERROR")
                .contains("DataAccessResourceFailureException")
                .contains("sqlState=23505")
                .contains("corr-s1-db");
        String unexpectedRecord = formattedMessageContaining("corr-s1-unexpected");
        assertThat(unexpectedRecord)
                .contains("INTERNAL_ERROR")
                .contains("IllegalStateException")
                .contains("sqlState=n/a")
                .contains("corr-s1-unexpected");
        String generationRecord = formattedMessageContaining("corr-s1-generation");
        assertThat(generationRecord)
                .contains("DOCUMENT_GENERATION_ERROR")
                .contains("DocumentGenerationException")
                .contains("sqlState=23505")
                .contains("corr-s1-generation");

        // Le correlationId journalisé reste normalisé (motif du
        // CorrelationIdFilter) — non vacuité : valeur extraite du log, puis
        // vérifiée, et comparée à celle injectée dans la requête.
        assertThat(correlationIdsInLogs())
                .as("correlationId journalisé hors du motif autorisé")
                .isNotEmpty()
                .allSatisfy(value -> assertThat(value).matches("^[A-Za-z0-9\\-]{1,64}$"))
                .contains("corr-s1-db", "corr-s1-unexpected", "corr-s1-generation");
    }

    @Test
    void stack_trace_is_emitted_at_debug_only() {
        targetLogger.setLevel(Level.DEBUG);

        handler.handleDatabase(hostileDatabaseException(), request("corr-s1-debug"));

        assertThat(eventsAt(Level.DEBUG))
                .as("la stack trace doit rester accessible au niveau DEBUG")
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNotNull());

        assertThat(eventsAt(Level.ERROR))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());

        // Même en DEBUG, le message formaté (hors throwable) reste propre.
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage())
                    .doesNotContain("Maria")
                    .doesNotContain("S3cr3t_Pg_PASS")
                    .doesNotContain("jdbc:postgresql");
        }

        // Politique packagée : racine INFO → le DEBUG est coupé par défaut.
        assertThat(packagedApplicationYaml())
                .as("la configuration emballée doit garder la racine à INFO "
                        + "(DEBUG donc coupé en production)")
                .contains("root: INFO");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static DataAccessException hostileDatabaseException() {
        // SQLState sûr (« 23505 ») mais message SQL/PII/secret hostile.
        SQLException hostileCause = new SQLException(HOSTILE_DB_MESSAGE, "23505", 23505);
        return new DataAccessResourceFailureException(HOSTILE_DB_MESSAGE, hostileCause);
    }

    private static MockHttpServletRequest request(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, correlationId);
        return request;
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    private String formattedMessageContaining(String fragment) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(fragment))
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "aucun log ne contient « " + fragment + " » : test non probant"));
    }

    /** Tous les textes émis : message formaté, arguments, MDC, causes comprises. */
    private List<String> capturedRecords() {
        List<String> records = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            records.add(event.getFormattedMessage());
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    records.add(String.valueOf(argument));
                }
            }
            records.addAll(event.getMDCPropertyMap().values());
            IThrowableProxy proxy = event.getThrowableProxy();
            int depth = 0;
            while (proxy != null && depth < 10) {
                if (proxy.getMessage() != null) {
                    records.add(proxy.getMessage());
                }
                proxy = proxy.getCause();
                depth++;
            }
        }
        return records;
    }

    /**
     * Valeurs {@code correlationId=…} réellement émises dans les messages
     * formatés. La MDC est vide ici (le {@code CorrelationIdFilter} ne tourne
     * pas sur un {@code MockHttpServletRequest} nu) : c'est donc le format du
     * message ERROR lui-même qui est contrôlé. La couverture MDC complète
     * (filtre → MDC → réponse) relève de {@code RequestLoggingSanitizationTest}.
     */
    private List<String> correlationIdsInLogs() {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern
                .compile("correlationId=([A-Za-z0-9\\-]+)")
                .matcher("");
        for (ILoggingEvent event : appender.list) {
            matcher.reset(event.getFormattedMessage());
            while (matcher.find()) {
                values.add(matcher.group(1));
            }
        }
        return values;
    }

    private String packagedApplicationYaml() {
        try (InputStream stream = DatabaseErrorLogSanitizationTest.class
                .getResourceAsStream("/application.yml")) {
            assertThat(stream).as("application.yml absente du classpath").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("lecture impossible : application.yml", exception);
        }
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
                // aucun appel : service jamais invoqué par ce test
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
        return new RequestService(new NormalizationService(), new ValidationService(clock),
                unusedRepository, unusedAudit, clock);
    }
}
