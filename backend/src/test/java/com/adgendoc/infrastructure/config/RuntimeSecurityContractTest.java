package com.adgendoc.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase H-HTTP — contrat de sécurité de la <b>configuration runtime telle
 * qu'emballée</b> (classpath, donc ce qui part réellement en production) :
 *
 * <ul>
 *   <li>aucun secret ni défaut faible committé (AGENTS.md §13) ;</li>
 *   <li>échec <b>explicite</b> au démarrage quand un secret obligatoire est
 *       absent, plutôt qu'un démarrage silencieux avec un mot de passe
 *       devinable (fail-fast) ;</li>
 *   <li>périmètre restreint contrôlé : bind loopback par défaut (contrat API
 *       §1.3, architecture §12) ;</li>
 *   <li>aucune fuite technique vers le client (§12 « Pas de stack trace
 *       client »).</li>
 * </ul>
 *
 * <p>Aucune connexion réseau, aucun Docker : test purement contractuel.</p>
 */
class RuntimeSecurityContractTest {

    private static final String WEAK_MARKER = "change_me_local_only";

    // ------------------------------------------------------------------
    // 1 — Secret obligatoire : aucun défaut committé, échec explicite
    // ------------------------------------------------------------------

    @Test
    void datasource_password_placeholder_has_no_committed_default() {
        String yaml = applicationYaml();
        assertThat(yaml)
                .as("spring.datasource.password doit être sans défaut committé")
                .contains("password: ${SPRING_DATASOURCE_PASSWORD}");
        assertThat(yaml)
                .as("aucun mot de passe par défaut ne doit subsister dans la config emballée")
                .doesNotContain("password: ${SPRING_DATASOURCE_PASSWORD:")
                .doesNotContain(WEAK_MARKER);
    }

    /**
     * Le placeholder seul ne suffit pas : Spring résout les valeurs de
     * configuration <b>lenientement</b> (aucune exception, la property reste
     * littérale ou videe) — vérifié et documenté en Phase H.1. Le fail-fast
     * est donc assuré par {@link DatasourceSecretGuard}, enregistré comme
     * {@code ApplicationContextInitializer} : sans cet enregistrement, le
     * service démarrerait avec un secret absent.
     */
    @Test
    void datasource_fail_fast_guard_is_registered_as_context_initializer() {
        String factories = classpathResource("/META-INF/spring.factories");
        assertThat(factories)
                .as("le garde-fou fail-fast doit être déclaré dans spring.factories")
                .contains("org.springframework.context.ApplicationContextInitializer")
                .contains(DatasourceSecretGuard.class.getName());
        assertThat(DatasourceSecretGuard.class)
                .as("le garde-fou doit être un ApplicationContextInitializer")
                .isAssignableTo(org.springframework.context.ApplicationContextInitializer.class);
    }

    // ------------------------------------------------------------------
    // 2 — Périmètre restreint contrôlé : loopback par défaut
    // ------------------------------------------------------------------

    @Test
    void server_binds_loopback_by_default() {
        assertThat(applicationYaml())
                .as("le serveur doit écouter en loopback par défaut "
                        + "(contrat API §1.3 « périmètre restreint »)")
                .contains("address: ${SERVER_BIND_ADDRESS:127.0.0.1}");
    }

    @Test
    void docker_publishes_the_api_on_loopback_only() {
        String compose = repositoryFile("docker", "docker-compose.yml");
        assertThat(compose)
                .as("la publication du port backend doit être épinglée au loopback")
                .contains("127.0.0.1:8080:8080");
        assertThat(compose)
                .as("publication non épinglée (exposition sur toutes les interfaces)")
                .doesNotContain("\"8080:8080\"");
        assertThat(compose)
                .as("le bind du conteneur doit être ouvrable explicitement pour n8n "
                        + "sur le réseau interne")
                .contains("SERVER_BIND_ADDRESS");
        assertThat(compose)
                .as("le bind du conteneur ne doit PAS hériter de la variable hôte "
                        + "(sinon conteneur loopback-only : n8n cassé, healthcheck vert)")
                .doesNotContain("${SERVER_BIND_ADDRESS:-0.0.0.0}")
                .contains("SERVER_BIND_ADDRESS: \"0.0.0.0\"");
        assertThat(compose)
                .as("le mot de passe PostgreSQL doit rester exigé par compose")
                .contains("SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?");
    }

    // ------------------------------------------------------------------
    // 3 — Aucune donnée technique sensible dans la configuration emballée
    // ------------------------------------------------------------------

    @Test
    void packaged_main_resources_contain_no_weak_credential() throws IOException {
        Path resources = resolvePath("src/main/resources", "backend/src/main/resources");
        assertThat(resources)
                .as("répertoire des ressources principales introuvable").isNotNull();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(resources)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.contains(WEAK_MARKER)) {
                    offenders.add(resources.relativize(file).toString());
                }
            }
        }
        assertThat(offenders)
                .as("marqueur de mot de passe faible présent dans %s", resources)
                .isEmpty();
    }

    @Test
    void error_responses_never_expose_internals() {
        String yaml = applicationYaml();
        assertThat(yaml)
                .contains("include-stacktrace: never")
                .contains("include-message: never")
                .contains("include-exception: false")
                .contains("show-details: never")
                .contains("show-sql: false");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String applicationYaml() {
        return classpathResource("/application.yml");
    }

    /** Lecture d'une ressource telle qu'emballée (classpath du module principal). */
    private String classpathResource(String name) {
        try (InputStream stream = RuntimeSecurityContractTest.class
                .getResourceAsStream(name)) {
            assertThat(stream).as("ressource absente du classpath : %s", name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("lecture impossible : " + name, exception);
        }
    }

    private String repositoryFile(String... segments) {
        String relative = String.join("/", segments);
        Path moduleSide = Path.of("..").resolve(relative).normalize();
        Path rootSide = Path.of(relative).normalize();
        Path resolved = Files.isRegularFile(moduleSide) ? moduleSide
                : (Files.isRegularFile(rootSide) ? rootSide : null);
        assertThat(resolved)
                .as("fichier du dépôt introuvable : %s (essayé %s et %s)",
                        relative, moduleSide, rootSide)
                .isNotNull();
        try {
            return Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("lecture impossible : " + resolved, exception);
        }
    }

    /** Résout depuis {@code backend/} (Maven) ou depuis la racine du dépôt. */
    private Path resolvePath(String fromModule, String fromRoot) {
        Path moduleSide = Path.of(fromModule.replace('/', java.io.File.separatorChar));
        if (Files.exists(moduleSide)) {
            return moduleSide;
        }
        Path rootSide = Path.of(fromRoot.replace('/', java.io.File.separatorChar));
        return Files.exists(rootSide) ? rootSide : null;
    }
}
