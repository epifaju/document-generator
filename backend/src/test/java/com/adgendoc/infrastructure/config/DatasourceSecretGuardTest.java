package com.adgendoc.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase H.1 — contrat du garde-fou fail-fast du secret de base de données
 * (AGENTS.md §13). Preuves : un datasource PostgreSQL sans secret est refusé
 * explicitement, avec un message net et sans valeur ; un secret fourni est
 * accepté ; les profils hors-ligne H2 ne sont pas concernés.
 */
class DatasourceSecretGuardTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://127.0.0.1:5432/adgendoc";
    private static final String H2_URL = "jdbc:h2:mem:adgendoc_test;DB_CLOSE_DELAY=-1";

    private final DatasourceSecretGuard guard = new DatasourceSecretGuard();

    // ------------------------------------------------------------------
    // Refus explicite
    // ------------------------------------------------------------------

    @Test
    void postgres_without_secret_is_rejected_explicitly() {
        MockEnvironment environment = postgresEnvironment();
        assertThatThrownBy(() -> guard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Secret de base de donnees absent")
                .hasMessageContaining(DatasourceSecretGuard.SECRET_VARIABLE);
    }

    @Test
    void postgres_without_url_property_is_rejected_too() {
        // DataSourceProperties.retourne jdbc:postgresql://... par défaut quand
        // l'URL est absente : le garde-fou ne doit pas laisser passer ce cas.
        MockEnvironment environment = new MockEnvironment();
        assertThatThrownBy(() -> guard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DatasourceSecretGuard.SECRET_VARIABLE);
    }

    @Test
    void postgres_with_placeholder_never_resolved_is_rejected() {
        MockEnvironment environment = postgresEnvironment();
        environment.setProperty("spring.datasource.password", "${SPRING_DATASOURCE_PASSWORD}");
        assertThatThrownBy(() -> guard.validate(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void postgres_with_blank_password_is_rejected() {
        MockEnvironment environment = postgresEnvironment();
        environment.setProperty("spring.datasource.password", "   ");
        assertThatThrownBy(() -> guard.validate(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failure_message_never_carries_a_secret_value() {
        MockEnvironment environment = postgresEnvironment();
        environment.setProperty("spring.datasource.password", "");
        assertThatThrownBy(() -> guard.validate(environment))
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("change_me_local_only")
                        .doesNotContain("jdbc:postgresql")
                        .doesNotContain("PhaseH_LocalOnly"));
    }

    // ------------------------------------------------------------------
    // Acceptation
    // ------------------------------------------------------------------

    @Test
    void postgres_with_secret_supplied_by_environment_is_accepted() {
        MockEnvironment environment = postgresEnvironment();
        environment.setProperty(DatasourceSecretGuard.SECRET_VARIABLE, "un-secret-local-non-committe");
        environment.setProperty("spring.datasource.password", "${SPRING_DATASOURCE_PASSWORD}");
        assertThatCode(() -> guard.validate(environment)).doesNotThrowAnyException();
    }

    @Test
    void postgres_with_literal_password_is_accepted() {
        MockEnvironment environment = postgresEnvironment();
        environment.setProperty("spring.datasource.password", "fourni-par-la-configuration");
        assertThatCode(() -> guard.validate(environment)).doesNotThrowAnyException();
    }

    @Test
    void offline_h2_datasource_is_not_concerned() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", H2_URL);
        assertThatCode(() -> guard.validate(environment)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------

    private MockEnvironment postgresEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", POSTGRES_URL);
        return environment;
    }
}
