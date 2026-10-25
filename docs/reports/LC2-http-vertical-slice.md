# LC2 — HttpVerticalSliceIT, HTTP loopback

`HttpVerticalSliceIT` démarre son serveur et son PostgreSQL. Aucun code de production, aucun `pom.xml`, aucun contrat. Aucun commit. L'index n'a pas été touché (`LC2-index-before.txt` et `LC2-index-after.txt` vides).

## STATUS

Reviewer : `VERDICT: PASS`. [Relecture](ff551e38-97f1-454e-9afb-4a264b4e97fd).

Security : `VERDICT: PASS`. [Revue](13d9834d-e270-42b8-b6ae-696dc6a6ff4a).

G4 : partiel, JVM unique, TCP réel. `tests/e2e/run_http_e2e.ps1` reste NOT_RUN.

## FINDINGS

Le serveur est celui de Spring Boot, sur `127.0.0.1` et un port éphémère (`HttpVerticalSliceIT.java:71-72`, `:121`). Le client reste `HttpClient`. `@LocalServerPort` est un champ d'instance (`:100-101`). `@BeforeEach` le copie dans `BASE_URL` (`:157-159`). Pas de `@TestInstance(PER_CLASS)`. `send()` n'est pas dans le diff : elle lit toujours `BASE_URL`.

PostgreSQL est un `postgres:16-alpine` Testcontainers. Url, utilisateur et mot de passe viennent du conteneur (`:118-120`, `:126-128`). `server.address` est forcé à `127.0.0.1`, donc un `SERVER_BIND_ADDRESS` du poste ne s'applique pas. Pas de `@ServiceConnection`. La garde et `application.yml` ne sont pas dans le diff (`LC2-diff-names.txt`).

La copie du template et le stockage sont deux répertoires temporaires. `configure()` copie `templates/attestation_concordance_v1.docx` dans le temporaire (`:129-132`) et conserve l'assertion « hors dépôt » (`:147-149`). Le fichier versionné n'est que lu.

`git diff -w` est `docs/reports/evidence/LC2-diff-w.txt`. Il contient les imports, la Javadoc, les annotations de classe, les champs, le remplacement de `env` / `required` par `@DynamicPropertySource`, `configure()`, et le `@BeforeEach` du port. Aucune méthode `@Test`. Les 11 scénarios n'ont pas changé.

Un premier `verify` s'est arrêté dans `configure()` : `templateCopy` était encore nul, parce que `@DynamicPropertySource` s'exécute après `@BeforeAll`. Ce n'était pas un des 11 tests. La copie a été déplacée dans `configure()`. Le journal de cet essai a été remplacé par le `verify` suivant, qui est vert.

L'assumption IPv4 n'a pas sauté le test : 0 ignoré.

`git diff --name-only` ne liste que `HttpVerticalSliceIT.java`. `docker ps -a` : 120 identifiants avant, 120 après, aucun écart. Aucun conteneur du lot ne reste. Les conteneurs déjà présents, dont `adgendoc-postgres-1`, n'ont pas été retirés.

`jdbc:postgresql://` et `password=` des trois journaux Maven sont `REDACTED`.

## FILES_CHANGED

- `backend/src/test/java/com/adgendoc/e2e/HttpVerticalSliceIT.java` — non indexé
- `docs/reports/evidence/LC2-mvn-test.txt`
- `docs/reports/evidence/LC2-mvn-verify-http.txt`
- `docs/reports/evidence/LC2-mvn-verify-all.txt`
- `docs/reports/evidence/LC2-diff-w.txt`
- `docs/reports/evidence/LC2-diff-names.txt`
- `docs/reports/evidence/LC2-git-status-before.txt`
- `docs/reports/evidence/LC2-git-status-after.txt`
- `docs/reports/evidence/LC2-index-before.txt`
- `docs/reports/evidence/LC2-index-after.txt`
- `docs/reports/evidence/LC2-docker-ps-before.txt`
- `docs/reports/evidence/LC2-docker-ps-after.txt`
- `docs/reports/LC2-http-vertical-slice.md`

## TESTS

```text
mvnw.cmd -B test
Tests run: 310, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`LC2-mvn-test.txt:599` et `:602`. Aucune ligne `HttpVerticalSliceIT`. EXIT=0 (`:607`).

```text
mvnw.cmd -B -Pit -Dit.test=HttpVerticalSliceIT verify
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`LC2-mvn-verify-http.txt:685` et `:689`. EXIT=0 (`:699`).

```text
mvnw.cmd -B -Pit verify
HttpVerticalSliceIT : 11, 0 échec, 0 ignoré
PostgresPersistenceIT : 13, 0 échec, 0 ignoré
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`LC2-mvn-verify-all.txt:682`, `:725`, `:729`. EXIT=0 (`:739`).

| Contrôle | Statut |
|---|---|
| G1 `mvnw.cmd -B test` | PASS — 310, 0 échec, sans cette classe |
| Slice HTTP | PASS — 11 exécutés, 0 ignoré |
| Profil `it` sans filtre | PASS — 11 + 13 |
| G4 | partiel, JVM unique, TCP réel |
| `run_http_e2e.ps1` | NOT_RUN |
| G6 | NOT_RUN |

## RISKS

Le script qui lance un processus séparé sur le port 18099 n'a pas été exécuté. Cette classe ne le remplace pas.

Le téléchargement reste sans authentification, risque déjà accepté pour l'itération 1. Le serveur de test n'écoute que le loopback.

## NEXT_ACTION

Aucun.
