package com.adgendoc.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corps de {@code PATCH /api/v1/requests/{requestId}} (contrat API §3.3).
 *
 * <p>Deux formes sont acceptées : le payload plat du contrat
 * ({@code {"dateNaissance": "..."}}) et la forme imbriquée
 * ({@code {"data": {"dateNaissance": "..."}}}) de l'architecture §4.1.
 * Les clés racine capturées par {@code @JsonAnySetter} sont fusionnées avec
 * le bloc {@code data} ; toute clé inconnue ou réservée est ensuite refusée
 * par {@code RequestService} avant toute mutation (arbitrage F-03).</p>
 */
public class PatchRequestRequest {

    private Map<String, JsonNode> data;

    private final Map<String, JsonNode> flatFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void setFlatField(String name, JsonNode value) {
        flatFields.put(name, value);
    }

    public Map<String, JsonNode> getData() {
        return data;
    }

    public void setData(Map<String, JsonNode> data) {
        this.data = data;
    }

    public Map<String, JsonNode> getFlatFields() {
        return flatFields;
    }
}
