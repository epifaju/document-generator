package com.adgendoc.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizationServiceTest {

    private final NormalizationService normalizationService = new NormalizationService();

    @Test
    void n1_trims_extremities() {
        Map<String, Object> result = normalizationService.normalize(data("prenom", "  Maria  "));

        assertThat(result).containsEntry("prenom", "Maria");
    }

    @Test
    void n2_collapses_multiple_spaces() {
        Map<String, Object> result = normalizationService.normalize(data("nom", "Maria   Gomes"));

        assertThat(result).containsEntry("nom", "Maria Gomes");
    }

    @Test
    void n3_converts_slash_date_to_iso() {
        Map<String, Object> result = normalizationService.normalize(
                data("dateNaissance", "12/05/1985"));

        assertThat(result).containsEntry("dateNaissance", "1985-05-12");
    }

    @Test
    void n3_converts_single_digit_date_to_iso() {
        Map<String, Object> result = normalizationService.normalize(
                data("dateNaissance", "12/5/1985"));

        assertThat(result).containsEntry("dateNaissance", "1985-05-12");
    }

    @Test
    void n3_converts_dash_date_to_iso() {
        Map<String, Object> result = normalizationService.normalize(
                data("dateNaissance", "12-05-1985"));

        assertThat(result).containsEntry("dateNaissance", "1985-05-12");
    }

    @Test
    void n3_keeps_iso_date_untouched() {
        Map<String, Object> result = normalizationService.normalize(
                data("dateNaissance", "1985-05-12"));

        assertThat(result).containsEntry("dateNaissance", "1985-05-12");
    }

    @Test
    void n3_leaves_unknown_date_format_intact() {
        Map<String, Object> result = normalizationService.normalize(
                data("dateNaissance", "le 12 mai 1985"));

        assertThat(result).containsEntry("dateNaissance", "le 12 mai 1985");
    }

    @Test
    void n3_converts_structure_without_calendar_check() {
        Map<String, Object> result = normalizationService.normalize(
                data("dateNaissance", "31/02/1985"));

        assertThat(result).containsEntry("dateNaissance", "1985-02-31");
    }

    @Test
    void n4_replaces_typographic_apostrophe() {
        Map<String, Object> result = normalizationService.normalize(data("nom", "gomes’"));

        assertThat(result).containsEntry("nom", "Gomes'");
    }

    @Test
    void n5_capitalizes_each_word() {
        Map<String, Object> result = normalizationService.normalize(data("nom", "maria gomes"));

        assertThat(result).containsEntry("nom", "Maria Gomes");
    }

    @Test
    void n6_keeps_particles_lowercase_except_first_position() {
        Map<String, Object> result = normalizationService.normalize(
                data("nom", "joao da silva"));

        assertThat(result).containsEntry("nom", "Joao da Silva");
    }

    @Test
    void n6_capitalizes_particle_in_first_position() {
        Map<String, Object> result = normalizationService.normalize(data("nom", "da silva"));

        assertThat(result).containsEntry("nom", "Da Silva");
    }

    @Test
    void n5_capitalizes_lieu_naissance_and_prenom() {
        Map<String, Object> result = normalizationService.normalize(
                data("prenom", "joana", "lieuNaissance", "bissau"));

        assertThat(result)
                .containsEntry("prenom", "Joana")
                .containsEntry("lieuNaissance", "Bissau");
    }

    @Test
    void n7_uppercases_enums() {
        Map<String, Object> result = normalizationService.normalize(
                data("sexe", "f", "langueDocument", "pt"));

        assertThat(result)
                .containsEntry("sexe", "F")
                .containsEntry("langueDocument", "PT");
    }

    @Test
    void n8_capitalizes_nationalite() {
        Map<String, Object> result = normalizationService.normalize(
                data("nationalite", "française"));

        assertThat(result).containsEntry("nationalite", "Française");
    }

    @Test
    void n9_removes_empty_null_and_blank_values() {
        Map<String, Object> input = data("prenom", null, "nom", "", "motif", "   ");

        Map<String, Object> result = normalizationService.normalize(input);

        assertThat(result)
                .doesNotContainKey("prenom")
                .doesNotContainKey("nom")
                .doesNotContainKey("motif");
    }

    @Test
    void defaults_langue_document_to_fr_when_absent() {
        Map<String, Object> result = normalizationService.normalize(data("prenom", "Maria"));

        assertThat(result).containsEntry("langueDocument", "FR");
    }

    @Test
    void defaults_langue_document_to_fr_when_blank() {
        Map<String, Object> result = normalizationService.normalize(
                data("langueDocument", "  "));

        assertThat(result).containsEntry("langueDocument", "FR");
    }

    private Map<String, Object> data(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
