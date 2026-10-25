# LB1 — Baseline d'exécution backend

Lot en lecture seule. Aucune modification de `pom.xml`, du code, des tests ou des contrats. Aucun commit. Aucun Docker, aucune base, aucun n8n, aucun Ollama, aucun service démarré.

HEAD au moment de la capture : `e3bd43888dcad22124ee0ea1bbae7cdd4b7ca89e` (`docs/reports/evidence/LB1-env.txt:16-17`). `git status --short` était vide (`LB1-env.txt:19-20`).

## STATUS

FAIL. `mvn -B test` s'est exécuté et s'est terminé en code 1 (BUILD FAILURE, 2 échecs Surefire). Le build n'est pas bloqué : JDK et Maven sont présents, les dépendances étaient déjà en cache (aucune ligne `Downloading`), la compilation était à jour.

Tester : `VERDICT: FAIL`.
Business-analyst : `CLASSIFICATION: <a=2 b=0 c=0>` (échecs Surefire seulement).
Reviewer : `VERDICT: PASS`.
Security : `VERDICT: PASS`. Scan CVE des dépendances : NOT_RUN. Aucune ligne Maven `[WARNING]`.

## FINDINGS

### Exécution

Commande exacte, depuis `backend/` : `mvn -B test` (`LB1-mvn-test.txt:1-2`).

| Mesure | Valeur | Preuve |
|---|---|---|
| Code de sortie | 1 | `LB1-mvn-test.txt:3` |
| Durée murale | 31,764 s | `LB1-mvn-test.txt:4-5` |
| Durée Maven | 29,354 s | `LB1-mvn-test.txt:1164` |
| Fin | 2026-10-25T12:25:10+01:00 | `LB1-mvn-test.txt:1165` |
| Résumé Surefire | Tests run: 298, Failures: 2, Errors: 0, Skipped: 0 | `LB1-mvn-test.txt:1159` |
| Résultat | BUILD FAILURE — There are test failures | `LB1-mvn-test.txt:1162`, `:1167` |
| Surefire | `maven-surefire-plugin:3.2.5` | `LB1-mvn-test.txt:30` |
| JVM du test | Java 17.0.10 | `LB1-mvn-test.txt:148` |
| Spring Boot affiché | 3.3.13 | `LB1-mvn-test.txt:146` |

`backend/pom.xml:10` fixe le parent Spring Boot `3.3.13`. `backend/pom.xml:21` fixe `java.version` à 17. Les plugins déclarés (`backend/pom.xml:84-115`) sont `spring-boot-maven-plugin` et `maven-resources-plugin`. Aucun `maven-surefire-plugin` ni `maven-failsafe-plugin` : les inclusions par défaut de Surefire s'appliquent (`LB1-test-inventory.txt:46-52`).

### Bilan des tests

| | Nombre |
|---|---|
| Classes source (`*Test`, `*Tests`, `*IT`, `*ITCase`) | 23 (21 `*Test`, 0 `*Tests`, 2 `*IT`, 0 `*ITCase`) |
| Classes exécutées par Surefire sur ce run | 21 |
| Tests exécutés | 298 |
| Réussis | 296 |
| En échec (failures + errors) | 2 |
| Ignorés (skipped) | 0 |
| Classes non exécutées par Surefire (`*IT`) | 2 : `HttpVerticalSliceIT`, `PostgresPersistenceIT` |
| Durée | 31,764 s murale ; 29,354 s Maven |

Détail par classe : `LB1-test-inventory.txt:62-83`. La somme des comptes de ce tableau est 298, avec 1 échec dans `ExtractionSchemaDocCoherenceTest` et 1 dans `RuntimeSecurityContractTest`.

`HttpVerticalSliceIT` a un rapport Surefire daté du 2026-04-10 (11 tests, 0 échec). Il n'y a pas de ligne `Running` pour cette classe dans `LB1-mvn-test.txt`. Ce rapport est périmé et n'entre pas dans les totaux (`LB1-test-inventory.txt:92-93`). `PostgresPersistenceIT` n'a aucun rapport (`LB1-test-inventory.txt:98`).

### Échecs — classification business-analyst

`CLASSIFICATION: <a=2 b=0 c=0>`. (a) défaut de code ou de test. (b) environnement. (c) dette G0 limitée à Java 17, Spring Boot 3.3.13, wrapper absent, `*IT` hors Surefire. Aucun échec (b) ou (c).

1. **(a)** `com.adgendoc.application.ExtractionSchemaDocCoherenceTest.architecture_section8_is_identical_to_schema_file` — `ExtractionSchemaDocCoherenceTest.java:162`. Le bloc JSON comparé n'est pas identique. Écart lisible dans la preuve : `dateNaissance.pattern` attendu `^[0-9]{4}-[0-9]{2}-[0-9]{2}$`, obtenu `^\d{4}-\d{2}-\d{2}$` (`LB1-mvn-test.txt:526-527`, `:558`, `:633`, `:869`). Le message d'assertion vise la section 8 de `docs/architecture.md`. G0 n'a pas exécuté ce test et ne le range pas dans la dette JDK / Boot / wrapper / `*IT`.

2. **(a)** `com.adgendoc.infrastructure.config.RuntimeSecurityContractTest.docker_publishes_n8n_and_ollama_on_loopback_only` — `RuntimeSecurityContractTest.java:117`. L'assertion exige la chaîne `127.0.0.1:5678:5678` (`LB1-mvn-test.txt:702-705`, `:841-843`, `:1020`). Le texte de `docker/docker-compose.yml` recopié dans l'échec contient `127.0.0.1:5681:5678` pour n8n et `127.0.0.1:11434:11434` pour ollama (`LB1-mvn-test.txt:806-819`). Le test lit un fichier ; Docker n'a pas été démarré. Catégorie (a), pas (b).

### Avertissements

- Écart de JDK : `java -version` annonce 21.0.10 (`LB1-env.txt:2-3`) ; `mvn -v` annonce Java 17.0.10, runtime `C:\Program Files\Java\jdk-17` (`LB1-env.txt:10-11`). Le process de test tourne en 17.0.10 (`LB1-mvn-test.txt:148`). Dette G0 confirmée (`G0-audit-backend.md:85`, `:104`), catégorie (c) comme avertissement, pas comme échec Surefire.
- JVM, une fois : `Java HotSpot(TM) 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended` (`LB1-mvn-test.txt:166`). Le business-analyst a écrit qu'aucun avertissement JVM n'était visible ; cette ligne est dans la preuve. Ce n'est pas un échec de test. Bruit d'environnement (b), hors comptage `<a=2 b=0 c=0>`.
- Aucune ligne Maven `[WARNING]` dans `LB1-mvn-test.txt`. Aucun avertissement de dépendance (plugin de scan non lancé).
- Encodage de la preuve : Maven signale `platform encoding: Cp1252` (`LB1-env.txt:11`). Les messages français de l'assertion sont altérés dans `LB1-mvn-test.txt` (caractères de remplacement). Les chaînes ASCII comparées (pattern, ports) restent lisibles. Ce n'est pas un échec produit.
- Logs `ERROR` et `WARN` applicatifs dans des classes à `Failures: 0` (handler de persistance, template introuvable, placeholders DOCX). Bruit de cas négatifs passants. Exemples : `LB1-mvn-test.txt:37-38`, `:134`, `:853-858`. Non comptés comme échecs.
- La stack DEBUG de `DatabaseErrorLogSanitizationTest` (test passant, `LB1-mvn-test.txt:134`) contient un JDBC, un mot de passe et un Bearer synthétiques, fabriqués par `hostileDatabaseException` (`LB1-mvn-test.txt:46-49`, `:126-129`). Le compose recopié dans l'autre échec n'a que des références `${…}` (`LB1-mvn-test.txt:722`, `:752`, `:786`).

### Dette G0 confirmée, sans faire échouer cette commande

| Point G0 | Observation LB1 |
|---|---|
| Java 17 | Build et tests en 17.0.10 ; le `java` du PATH est en 21 |
| Spring Boot 3.3.13 | Bannière de test et `pom.xml:10` |
| Wrapper absent | Commande `mvn` système 3.9.10 (`LB1-env.txt:7-8`). `mvnw` non utilisé |
| `*IT` hors Surefire | Les deux classes `*IT` n'ont pas été lancées par `mvn test` |

## FILES_CHANGED

Aucun fichier de projet. Écritures de ce lot :

- `docs/reports/evidence/LB1-env.txt`
- `docs/reports/evidence/LB1-mvn-test.txt`
- `docs/reports/evidence/LB1-test-inventory.txt`
- `docs/reports/LB1-baseline-backend.md` (ce rapport)

## TESTS

Sorties réelles (extraits). Le fichier `LB1-mvn-test.txt` contient la sortie brute complète.

Environnement (`LB1-env.txt`) :

```
java version "21.0.10" 2026-01-20 LTS
Java version: 17.0.10, vendor: Oracle Corporation, runtime: C:\Program Files\Java\jdk-17
Apache Maven 3.9.10
e3bd43888dcad22124ee0ea1bbae7cdd4b7ca89e
```

`git status --short` : sortie vide, code 0.

En-tête et fin de `mvn -B test` :

```
cwd: C:\Users\epifa\cursor-workspace\document-generator\backend
command: mvn -B test
exit_code: 1
duration_seconds: 31.764
[INFO] Building document-generator 1.0.0-SNAPSHOT
[INFO] Nothing to compile - all classes are up to date.
[ERROR] Tests run: 298, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[INFO] Total time:  29.354 s
[INFO] Finished at: 2026-10-25T12:25:10+01:00
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project document-generator: There are test failures.
```

Échecs Surefire :

```
[ERROR] ExtractionSchemaDocCoherenceTest.architecture_section8_is_identical_to_schema_file:162
[ERROR] RuntimeSecurityContractTest.docker_publishes_n8n_and_ollama_on_loopback_only:117
```

### Gates

| Gate | Statut | Preuve |
|---|---|---|
| G1 build + tests Surefire (`mvn -B test`) | FAIL | Code 1. Tests unitaires et standalone seulement ; `*IT` NOT_RUN |
| G2 persistance | NOT_RUN | `PostgresPersistenceIT` non lancé. Pas de base, pas de Docker |
| G3 DOCX vérifié hors Surefire | NOT_RUN | `PoiTemplateEngineTest` a tourné dans G1 (14 tests, 0 échec, `LB1-mvn-test.txt:858`) ; pas de contrôle DOCX séparé |
| G4 HTTP contrat E2E | NOT_RUN | `HttpVerticalSliceIT` non lancé. Le XML du 2026-04-10 n'est pas une preuve de ce run |
| G5 fixtures | NOT_RUN | Validateur n8n non lancé |
| G6 n8n runtime | NOT_RUN | Runtime non autorisé |
| G7 sécurité | PASS | Revue lecture seule de ce lot. Aucun secret réel dans les preuves. Scan CVE : NOT_RUN. Aucune ligne Maven `[WARNING]` |
| G8 CI | NOT_RUN | Aucune CI |

G1 est FAIL parce que le code de sortie n'est pas 0. La phrase de périmètre reste : tests unitaires et standalone seulement ; `*IT` NOT_RUN.

## RISKS

- Les deux échecs (a) sont des écarts dépôt (schéma / architecture, oracle de port n8n `5678` vs `5681`). Ils ne disent pas qu'un service a refusé de démarrer.
- G2 et G4 restent ouverts : une persistance PostgreSQL ou un parcours HTTP peut régresser sans casser `mvn test`.
- Le JDK du PATH (21) et le JDK de Maven (17) diffèrent. Ce run prouve le comportement en 17, celui du `pom.xml`.
- La preuve `LB1-mvn-test.txt` contient des marqueurs synthétiques (JDBC, mot de passe, Bearer) parce qu'un test de sanitisation logue sa stack au niveau DEBUG, et le texte du compose (variables `${…}`, pas de secret en dur). La revue sécurité les a classés comme fixtures, `VERDICT: PASS`.
- L'encodage Cp1252 dégrade les messages français dans la preuve.

## NEXT_ACTION

Lot B1 clos. Aucune correction de test dans ce lot.

Hors de ce lot, un lot ultérieur peut trancher les deux écarts (a) : identité du bloc section 8 avec le schéma (`dateNaissance.pattern` `[0-9]` vs `\d`), et chaîne de port attendue par `RuntimeSecurityContractTest` (`127.0.0.1:5678:5678`) face au texte lu `127.0.0.1:5681:5678`. Dans cette preuve, n8n, ollama et le backend restent sur `127.0.0.1` (`LB1-mvn-test.txt:767`, `:807`, `:819`) ; postgres n'a pas de port hôte publié (`LB1-mvn-test.txt:737`). G2 et G4 demandent un accord explicite (base, Docker ou service).

## Revue sécurité (constats, verdict recopié plus bas)

`VERDICT: PASS`. Aucun secret de production dans les preuves. Marqueurs synthétiques dans la stack DEBUG d'un test passant (`LB1-mvn-test.txt:46-47`). Compose recopié : références `${…}` non résolues (`LB1-mvn-test.txt:722`, `:752`, `:786`). Identifiant Windows `epifa` dans le log Spring (`LB1-mvn-test.txt:148`). L'échec de port est une dérive d'oracle (5681 vs 5678), avec publication loopback visible dans le texte recopié. Aucune ligne Maven `[WARNING]`. Scan CVE : NOT_RUN.

## Verdicts recopiés

Tester :

```
VERDICT: FAIL
```

Business-analyst :

```
CLASSIFICATION: <a=2 b=0 c=0>
```

Reviewer :

```
VERDICT: PASS
```

Security :

```
VERDICT: PASS
```
