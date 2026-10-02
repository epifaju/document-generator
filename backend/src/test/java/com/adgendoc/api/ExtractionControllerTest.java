package com.adgendoc.api;

import com.adgendoc.application.ExtractionValidationService;
import com.adgendoc.infrastructure.config.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests MockMvc (standalone) de E8 {@code POST /api/v1/extraction/validate} :
 * validateur networknt réel sur le schéma draft 2020-12 du classpath.
 */
class ExtractionControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ExtractionValidationService service = new ExtractionValidationService(
                "classpath:prompts/extraction/attestation_concordance.schema.json");
        mockMvc = MockMvcBuilders.standaloneSetup(new ExtractionController(service))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void e8_valid_complete_extraction_returns_200_true() throws Exception {
        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validComplete()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void e8_declared_missing_field_returns_200_true() throws Exception {
        String payload = """
                {"documentType":"ATTESTATION_CONCORDANCE","confidence":0.61,
                 "data":{"prenom":"Maria","nom":"Gomes","dateNaissance":"1985-05-12",
                         "lieuNaissance":"Bissau","nomIncorrect":"Maria Gomez"},
                 "missingFields":["nomCorrect"]}
                """;

        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void e8_invalid_date_returns_400_date_format() throws Exception {
        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validComplete().replace("1985-05-12", "12/05/1985")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/data/dateNaissance"))
                .andExpect(jsonPath("$.errors[0].code").value("ERR_DATE_FORMAT_INVALIDE"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(containsString("Format de date invalide")));
    }

    @Test
    void e8_unknown_key_returns_400_champ_inconnu() throws Exception {
        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validComplete().replace("\"motif\"", "\"passeport\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/data/passeport"))
                .andExpect(jsonPath("$.errors[0].code").value("ERR_CHAMP_INCONNU"));
    }

    @Test
    void e8_unsupported_document_type_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validComplete().replace("ATTESTATION_CONCORDANCE",
                                "ACTE_NAISSANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/documentType"))
                .andExpect(jsonPath("$.errors[0].code")
                        .value("ERR_DOCUMENT_TYPE_NON_SUPPORTE"));
    }

    @Test
    void e8_missing_document_type_returns_400_champ_obligatoire() throws Exception {
        String payload = """
                {"confidence":0.97,"data":{},"missingFields":[]}
                """;

        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/documentType"))
                .andExpect(jsonPath("$.errors[0].code")
                        .value("ERR_CHAMP_OBLIGATOIRE_ABSENT"));
    }

    @Test
    void e8_confidence_out_of_range_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validComplete().replace("\"confidence\":0.97",
                                "\"confidence\":1.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/confidence"))
                .andExpect(jsonPath("$.errors[0].code").value("ERR_PAYLOAD_INVALIDE"));
    }

    @Test
    void e8_malformed_json_returns_400_payload_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"ATTESTATION_CONCORDANCE\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/"))
                .andExpect(jsonPath("$.errors[0].code").value("ERR_PAYLOAD_INVALIDE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("java."))));
    }

    @Test
    void e8_non_object_root_returns_400_payload_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2,3]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].code").value("ERR_PAYLOAD_INVALIDE"));
    }

    @Test
    void e8_correlation_id_is_echoed() throws Exception {
        mockMvc.perform(post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "n8n-trace-42")
                        .content(validComplete()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "n8n-trace-42"))
                .andExpect(jsonPath("$.correlationId").value("n8n-trace-42"));
    }

    private String validComplete() {
        return """
                {"documentType":"ATTESTATION_CONCORDANCE","confidence":0.97,
                 "data":{"prenom":"Maria","nom":"Gomes","dateNaissance":"1985-05-12",
                         "lieuNaissance":"Bissau","nomIncorrect":"Maria Gomez",
                         "nomCorrect":"Maria Gomes","motif":"Dossier bancaire"},
                 "missingFields":[]}
                """;
    }
}
