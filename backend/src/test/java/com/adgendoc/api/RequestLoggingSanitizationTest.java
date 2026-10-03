package com.adgendoc.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase H-HTTP — <b>sécurité des journaux</b> (AGENTS.md §13 « Ne pas
 * journaliser de données personnelles inutilement », §15, architecture §12
 * « Minimisation PII »).
 *
 * <p>Le test est <b>comportemental</b> : il attache un appender Logback sur le
 * logger racine, rejoue des scénarios HTTP contenant des données nominatives
 * (création valide, création rejetée, complétion 422, PATCH avec date FR,
 * lecture 404, JSON malformé, échec de génération 500, santé), puis vérifie
 * qu'<b>aucun</b> message journalisé — arguments et message d'exception
 * compris — ne reproduit une donnée du demandeur.</p>
 *
 * <p>Le scénario d'échec de génération (500) garantit qu'au moins un log
 * <b>émis par l'application</b> est effectivement capturé et analysé : le test
 * n'est donc pas vacu.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestLoggingSanitizationTest {

    private static final String API = "/api/v1/requests";
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final String PROBE_LOGGER = "com.adgendoc.test.logprobe";

    /** Données du demandeur qui ne doivent JAMAIS apparaître dans un log. */
    private static final List<String> FORBIDDEN_VALUES = List.of(
            "Maria", "Gomes", "Gomez", "Bissau", "1985-05-12", "12/05/1985",
            "Dossier bancaire", "change_me_local_only");

    @Autowired
    private MockMvc mockMvc;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger rootLogger;

    @BeforeEach
    void captureLogs() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.list.clear();
        appender.start();
        rootLogger.addAppender(appender);
        // Preuve que l'appender est bien branché : ce journal, émis par le test,
        // doit être capturé. Sans lui, l'assertion de vacuité serait fausse.
        LoggerFactory.getLogger(PROBE_LOGGER).info("probe");
    }

    @AfterEach
    void stopCapture() {
        rootLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void request_payloads_and_personal_data_never_reach_the_logs() throws Exception {
        // 1 — création valide (201) : le payload complet contient la PII.
        mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated());

        // 2 — valeur rejetée par validation (400) : cas classique de fuite par
        // message d'exception Bean Validation (« rejected value [...] »).
        mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody().replace("1985-05-12", "abc")))
                .andExpect(status().isBadRequest());

        // 3 — complétion 422 puis 4 — PATCH date française (200).
        String missingId = createdRequestId(missingBody(), status().isUnprocessableEntity());
        mockMvc.perform(patch(API + "/" + missingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"data":{"dateNaissance":"12/05/1985","lieuNaissance":"Bissau"}}
                                """))
                .andExpect(status().isOk());

        // 5 — lecture d'une demande inconnue (404).
        mockMvc.perform(get(API + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        // 6 — JSON malformé contenant la PII (400) : HttpMessageNotReadable.
        mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prenom\":\"Maria\",\"nom\":\"Gomes\",\"data\":"))
                .andExpect(status().isBadRequest());

        // 7 — échec de génération (500 TEMPLATE_NOT_FOUND) : chemin ERROR
        // journalisé côté serveur, c'est-à-dire le plus exposé.
        String validId = createdRequestId(validBody(), status().isCreated());
        mockMvc.perform(post(API + "/" + validId + "/generate"))
                .andExpect(status().isInternalServerError());

        // 8 — santé (200).
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());

        // Le mécanisme de capture fonctionne (probe présent)…
        assertThat(appender.list)
                .as("appender non branché : le test serait non probant")
                .isNotEmpty();
        // …et des logs APPLICATIFS ont bien été capturés et analysés.
        assertThat(appender.list)
                .as("aucun log applicatif capturé pendant les scénarios : test non probant")
                .filteredOn(event -> !event.getLoggerName().startsWith(PROBE_LOGGER))
                .isNotEmpty();

        for (String record : capturedRecords()) {
            for (String forbidden : FORBIDDEN_VALUES) {
                assertThat(record)
                        .as("donnée du demandeur ou secret journalisé : « %s »", forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Tous les textes émis par les logs : message formaté, arguments, MDC, throwable. */
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
            if (event.getThrowableProxy() != null
                    && event.getThrowableProxy().getMessage() != null) {
                records.add(event.getThrowableProxy().getMessage());
            }
        }
        return records;
    }

    private String createdRequestId(String body,
                                    org.springframework.test.web.servlet.ResultMatcher matcher)
            throws Exception {
        String response = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(matcher)
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("requestId").asText();
    }

    private String validBody() {
        return """
                {"documentType":"%s",
                 "data":{"prenom":"Maria","nom":"Gomes","dateNaissance":"1985-05-12",
                         "lieuNaissance":"Bissau","nomIncorrect":"Maria Gomez",
                         "nomCorrect":"Maria Gomes","motif":"Dossier bancaire"},
                 "extraction":{"confidence":0.97,"modelId":"ollama/llama3.1",
                               "promptVersion":"v1"}}
                """.formatted(DOCUMENT_TYPE);
    }

    private String missingBody() {
        return """
                {"documentType":"%s",
                 "data":{"prenom":"Maria","nom":"Gomes",
                         "nomIncorrect":"Maria Gomez","nomCorrect":"Maria Gomes"}}
                """.formatted(DOCUMENT_TYPE);
    }
}
