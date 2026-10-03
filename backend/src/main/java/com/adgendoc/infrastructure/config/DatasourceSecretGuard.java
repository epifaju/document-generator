package com.adgendoc.infrastructure.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * Phase H.1 — <b>garde-fou fail-fast du secret de base de données</b>
 * (AGENTS.md §13 : « aucun secret committé », jamais de défaut faible).
 *
 * <p>Pourquoi un composant et pas seulement {@code application.yml} :
 * Spring résout <b>lenientement</b> les placeholders des valeurs de
 * configuration — {@code password: ${SPRING_DATASOURCE_PASSWORD}} sans valeur
 * fournie ne lève aucune exception, la property est simplement laissée telle
 * quelle (ou vidée), et le service démarre puis échoue plus tard sur
 * « authentification par mot de passe échouée ». Le démarrage doit au
 * contraire <b>échouer explicitement et immédiatement</b>.</p>
 *
 * <p>Exécuté comme {@link ApplicationContextInitializer} : c'est le dernier
 * point garanti <b>après</b> le chargement de {@code application.yml} et
 * <b>avant</b> la création du moindre bean (donc avant Hikari/Flyway), ce qui
 * donne un message d'erreur net.</p>
 *
 * <p>Portée : uniquement les URL {@code jdbc:postgresql}. Les profils de test
 * hors-ligne (H2, {@code application-test.yml}) ne sont pas concernés.</p>
 */
public class DatasourceSecretGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** Nom de la variable d'environnement portant le secret. */
    public static final String SECRET_VARIABLE = "SPRING_DATASOURCE_PASSWORD";

    private static final String POSTGRES_PREFIX = "jdbc:postgresql";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        validate(applicationContext.getEnvironment());
    }

    /**
     * Contrat testable : lève {@link IllegalStateException} si un datasource
     * PostgreSQL est configuré sans secret exploitable.
     */
    public void validate(ConfigurableEnvironment environment) {
        String url = readSafely(environment, "spring.datasource.url");
        // Une URL absente vaut « postgresql » par défaut (DataSourceProperties) :
        // le garde-fou s'applique donc aussi dans ce cas.
        if (url != null && !url.isBlank() && !url.startsWith(POSTGRES_PREFIX)) {
            return;
        }
        String rawPassword = readSafely(environment, "spring.datasource.password");
        if (isUsable(rawPassword, environment)) {
            return;
        }
        throw new IllegalStateException(missingSecretMessage());
    }

    /**
     * {@code Environment#getProperty} résout <b>sévéremment</b> les placeholders
     * et lève {@link IllegalArgumentException} si la variable est absente, alors
     * que le Binder de Spring Boot résout <b>lenientement</b> (valeur laissée
     * telle quelle). Les deux comportements sont ramenés au même échec explicite.
     */
    private String readSafely(ConfigurableEnvironment environment, String key) {
        try {
            return environment.getProperty(key);
        } catch (RuntimeException unresolved) {
            throw new IllegalStateException(missingSecretMessage(), unresolved);
        }
    }

    /** Message d'échec explicite — ne contient et ne doit contenir aucune valeur. */
    public static String missingSecretMessage() {
        return "Secret de base de donnees absent : " + SECRET_VARIABLE
                + " n'est pas definie et aucun mot de passe n'est fourni."
                + " Aucun secret ni mot de passe par defaut n'est committé (AGENTS.md §13) :"
                + " le demarrage est refuse tant que le secret obligatoire est absent."
                + " Definissez " + SECRET_VARIABLE + " (variable d'environnement) avant de demarrer.";
    }

    /**
     * Une valeur est exploitable si, après résolution des placeholders contre
     * l'environnement, elle n'est ni vide ni un placeholder non résolu.
     */
    private boolean isUsable(String rawPassword, ConfigurableEnvironment environment) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        String resolved;
        try {
            PropertyPlaceholderHelper helper =
                    new PropertyPlaceholderHelper("${", "}", ":", true);
            resolved = helper.replacePlaceholders(rawPassword, environment::getProperty);
        } catch (RuntimeException unresolved) {
            return false;
        }
        return resolved != null && !resolved.isBlank() && !resolved.contains("${");
    }
}
