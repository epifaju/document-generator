# LC1 — PostgresPersistenceIT sur Testcontainers

Un conteneur PostgreSQL éphémère pour `PostgresPersistenceIT`, dans le profil Maven `it`. `HttpVerticalSliceIT` n'a pas été modifié. Aucun commit. L'index n'a pas été touché (`git diff --cached --name-only` vide).

## Plan appliqué

Docker Engine **29.8.0**, API **1.56**, minimum annoncé **1.40** (`docs/reports/evidence/LC1-docker-version.txt`). Ce n'est pas le minimum 1.44 supposé pour Docker 29. Testcontainers 1.19.8, celui du parent Spring Boot 3.3.13, parle l'API 1.32. La 1.21.3 aussi : elle échoue sur Docker 29 (issue testcontainers-java 11235). La 1.21.4 est la première 1.21.x qui négocie l'API récente. Propriété seule ajoutée : `testcontainers.version` = **1.21.4**. Le journal le confirme (`LC1-mvn-verify.txt:574`).

`@ServiceConnection` n'est pas compatible avec `DatasourceSecretGuard`. La garde est un `ApplicationContextInitializer` : elle lit `spring.datasource.url` et `spring.datasource.password` dans l'environnement, avant tout bean (`DatasourceSecretGuard.java:36-55`). `@ServiceConnection` de Spring Boot 3.3.13 n'écrit pas ces propriétés : `ServiceConnectionContextCustomizer` enregistre des définitions `ConnectionDetails`. Le mot de passe littéral est donc fourni par `@DynamicPropertySource` (`PostgresPersistenceIT`, url, username, password). La garde n'a pas été assouplie. Les 8 tests `DatasourceSecretGuardTest` restent verts.

Image `postgres:16-alpine`. Un seul `@Container`. Identifiants générés par Testcontainers. Port hôte aléatoire. Aucun port fixe.

## STATUS

G1 PASS. G2 PASS : `PostgresPersistenceIT` a exécuté **13** tests, 0 échec, 0 ignoré, code 0.

Reviewer : `VERDICT: PASS`.
Security : `VERDICT: PASS`. Dépendances de test, versions gérées, propriété `testcontainers.version` justifiée. Aucun secret réel. Aucun port fixe.

## FINDINGS

Commande exacte, parce que Failsafe inclut `**/*IT.java` et que `HttpVerticalSliceIT` n'est pas câblé :

```text
mvnw.cmd -B -Pit -Dit.test=PostgresPersistenceIT verify
```

`HttpVerticalSliceIT` n'apparaît pas dans le journal. Le conteneur `postgres:16-alpine` a démarré (`LC1-mvn-verify.txt:593`) puis a disparu. `docker ps -a` avant et après : 120 conteneurs, mêmes identifiants, aucun ajout, aucun retrait. Les conteneurs déjà présents, dont `adgendoc-postgres-1`, n'ont pas été touchés.

`mvnw.cmd -B test` : 298 tests, 0 échec, code 0 (`LC1-mvn-test.txt`). Aucun `*IT`.

Les URL `jdbc:postgresql://…` et les `password=` ont été remplacés par `REDACTED` dans `LC1-mvn-verify.txt` et dans `LC1-mvn-test.txt`. Le fichier brut temporaire a été effacé.

### Écart ADR-09

`docs/architecture.md:127` (ADR-09) dit « Testcontainers : NON en it.1 ». Ce lot ne modifie pas ce fichier. Écart connu : la décision du 26/10/2026 autorise Testcontainers dans le profil `it` seulement. `mvnw.cmd -B test` reste sans Docker.

### Note de conception pour C2 — non appliquée

`HttpVerticalSliceIT` n'est pas un `@SpringBootTest`. Il parle en HTTP et en JDBC à un serveur déjà lancé. Les URL sont lues dans des champs `static final` au chargement de la classe (`HttpVerticalSliceIT.java:85-89`), donc avant `@BeforeAll`. Un harnais C2 devrait :

1. démarrer son propre `PostgreSQLContainer` `postgres:16-alpine`, distinct de celui de `PostgresPersistenceIT` ;
2. copier le template versionné vers un fichier hors dépôt, comme l'exigent déjà les contrôles de `@BeforeAll` ;
3. lancer `SpringApplication` sur `127.0.0.1` et un port éphémère, dans la même JVM ;
4. n'assigner base URL, JDBC et chemins qu'après ce démarrage.

Ce test changerait de nature : le serveur n'est plus un processus séparé, il vit dans la JVM du test. Les assertions HTTP et JDBC resteraient celles d'un socket réel.

Interaction avec la garde : le même obstacle que C1. `@ServiceConnection` ne remplit pas `spring.datasource.password` avant `DatasourceSecretGuard`. Le harnais doit passer url, username et password littéraux (arguments de `SpringApplication` ou `@DynamicPropertySource`) avant le rafraîchissement du contexte. Ne pas assouplir la garde.

Décision laissée à l'utilisateur. Rien de ce harnais n'est dans le code.

## FILES_CHANGED

- `backend/pom.xml` — propriété `testcontainers.version` et trois dépendances de test, sans `<version>`
- `backend/src/test/java/com/adgendoc/persistence/PostgresPersistenceIT.java` — conteneur et propriétés dynamiques
- `docs/reports/evidence/LC1-docker-version.txt`
- `docs/reports/evidence/LC1-docker-ps-before.txt`
- `docs/reports/evidence/LC1-docker-ps-after.txt`
- `docs/reports/evidence/LC1-mvn-test.txt`
- `docs/reports/evidence/LC1-mvn-verify.txt`
- `docs/reports/evidence/LC1-diff.txt`
- `docs/reports/LC1-testcontainers-g2.md`

Non modifiés : `HttpVerticalSliceIT`, `application.yml`, `application-test.yml`, les migrations, le compose, `docs/architecture.md`.

## TESTS

| Contrôle | Statut |
|---|---|
| `docker version` / `docker ps` | PASS — Engine 29.8.0, API min 1.40. Lecture seule |
| `mvnw.cmd -B test` | PASS — 298, 0 échec, code 0, mur 350,578 s. Aucun `*IT` |
| `mvnw.cmd -B -Pit -Dit.test=PostgresPersistenceIT verify` | PASS — code 0, mur 358,854 s. `PostgresPersistenceIT` : 13 tests, 0 échec, 0 ignoré |
| `docker ps -a` avant / après | PASS — 120 identifiants identiques |
| G1 | PASS |
| G2 | PASS |
| G3, G4, G5, G6, G8 | NOT_RUN |
| G7 | PASS |

## RISKS

- `spring-boot-testcontainers` est sur le classpath de test, comme prévu, mais `@ServiceConnection` n'est pas utilisé. La dépendance ne câble rien dans ce lot.
- Sans `distributionSha256Sum` du wrapper Maven, sujet du lot précédent, inchangé ici.
- C2 reste nécessaire pour `HttpVerticalSliceIT`. `-Pit verify` sans `-Dit.test=PostgresPersistenceIT` lancerait cette classe, qui échouerait faute de serveur.

## NEXT_ACTION

Aucun commit. Décider C2 à partir de la note de conception.

## Verdicts recopiés

Reviewer ([relecture LC1](8fee940b-557d-4a75-90a7-e01dea98e173)) :

```
VERDICT: PASS
```

Security ([revue LC1](5f221446-9199-4ab5-8066-c68a3693bf90)) :

```
VERDICT: PASS
```
