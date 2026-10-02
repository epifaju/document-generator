package com.adgendoc.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corps de {@code POST /api/v1/requests} (contrat API §2.1).
 *
 * <p>Les clés racine inconnues sont capturées par {@code @JsonAnySetter} afin
 * d'être refusées explicitement (jamais silencieusement perdues).</p>
 */
public class CreateRequestRequest {

    @NotBlank(message = "documentType est obligatoire.")
    private String documentType;

    @NotNull(message = "data est obligatoire.")
    private Map<String, JsonNode> data;

    private Map<String, JsonNode> extraction;

    private final Map<String, JsonNode> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void setUnknownField(String name, JsonNode value) {
        unknownFields.put(name, value);
    }

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public Map<String, JsonNode> getData() {
        return data;
    }

    public void setData(Map<String, JsonNode> data) {
        this.data = data;
    }

    public Map<String, JsonNode> getExtraction() {
        return extraction;
    }

    public void setExtraction(Map<String, JsonNode> extraction) {
        this.extraction = extraction;
    }

    public Map<String, JsonNode> getUnknownFields() {
        return unknownFields;
    }
}
