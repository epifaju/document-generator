# LA — E8 contrôle du Content-Type

| | |
|---|---|
| Rôle | orchestrateur |
| Exigence | `POST /api/v1/extraction/validate` (E8), Content-Type |
| Date | 2026-10-25 |
| Cycle de correction | 0 / 3 (aucune revue FAIL) |
| Contrat / ADR | non modifiés |

## Décisions humaines (24-25/10/2026)

Constatées, non rediscutées.

- **D1.** Pour tout POST dont le Content-Type n'est pas JSON, E8 compris : `400` `VALIDATION_ERROR` avec corps `ErrorResponse` (règle générale `requirements/API_CONTRACTS.md` §1.1), pas `ExtractionValidationResponse`.
- **D2.** Valeurs acceptées : `application/json` avec ou sans `;charset=UTF-8`. Toute autre valeur, ou en-tête absent, est rejetée selon D1. L'harmonisation §1.1 / §3.x est proposée plus bas et n'est pas appliquée.

## STATUS

Correctif E8 en place. Tester : **VERDICT: PASS**. Reviewer : **VERDICT: PASS**. Security : **VERDICT: PASS**.

## FINDINGS

- `requirements/API_CONTRACTS.md:24` existe encore : toute valeur de Content-Type autre que celle du tableau → `400` `VALIDATION_ERROR`, corps `ErrorResponse`.
- `requirements/API_CONTRACTS.md:434` existe encore : E8 documente `Content-Type: application/json`.
- `requirements/API_CONTRACTS.md:440` existe encore : le `400` d'E8 y est un `ExtractionValidationResponse`. Cet écart de rédaction n'a pas été modifié.
- E1/E3 rejettent un type non JSON parce que le corps est un DTO : `HttpMediaTypeNotSupportedException` est convertie par `GlobalExceptionHandler.handleUnsupportedMediaType` (`backend/src/main/java/com/adgendoc/api/GlobalExceptionHandler.java:128-134`).
- E8 prenait un `@RequestBody String` sans `consumes`, donc un mauvais type restait traité. Le correctif est `consumes = application/json` (`ExtractionController.java:33`) plus `rejectNonUtf8Charset` (`ExtractionController.java:56-67`), qui relance la même exception. `HttpMediaTypeNotSupportedException` étend `ServletException` (exception contrôlée) : elle est déclarée sur `validate` (`ExtractionController.java:36`).
- Le `400` de schéma reste un `ExtractionValidationResponse` (`ExtractionController.java:47`).
- Le correctif a été écrit dans la conversation orchestrateur, après accord du plan. Les revues sont des contextes neufs. Le type d'agent `reviewer` / `security` n'était plus dans le catalogue ; les deux revues ont tourné en agents neufs avec ce mandat.

## FILES_CHANGED

Lot :

- `backend/src/main/java/com/adgendoc/api/ExtractionController.java`
- `backend/src/test/java/com/adgendoc/api/ExtractionControllerTest.java`
- `docs/reports/evidence/LA-diff.txt`
- `docs/reports/evidence/LA-extraction-controller-test.txt`
- `docs/reports/evidence/LA-controller-regression.txt`
- `docs/reports/LA-e8-content-type.md`

Hors lot, déjà présents avant ce travail, non touchés ici : `docs/PRD_adgendoc_v2_1_Cursor.md` (modifié) et les fichiers `docs/reports/D0-*` / `docs/reports/evidence/D0-*` (non suivis). Contrat, ADR, autres endpoints, fixtures n8n, `pom.xml` : inchangés.

## TESTS

### Échec avant correctif

Commande, cwd `backend/` : `mvn -Dtest=ExtractionControllerTest test`. Fin `2026-10-25T01:01:42+02:00`. BUILD FAILURE.

```
[ERROR] Tests run: 16, Failures: 4, Errors: 0, Skipped: 0, Time elapsed: 5.793 s <<< FAILURE! -- in com.adgendoc.api.ExtractionControllerTest
[ERROR] com.adgendoc.api.ExtractionControllerTest.e8_text_plain_content_type_returns_400_validation_error -- Time elapsed: 0.040 s <<< FAILURE!
java.lang.AssertionError: Status expected:<400> but was:<200>
[ERROR] com.adgendoc.api.ExtractionControllerTest.e8_form_urlencoded_content_type_returns_400_validation_error -- Time elapsed: 0.041 s <<< FAILURE!
java.lang.AssertionError: No value at JSON path "$.code"
[ERROR] com.adgendoc.api.ExtractionControllerTest.e8_missing_content_type_returns_400_validation_error -- Time elapsed: 0.023 s <<< FAILURE!
java.lang.AssertionError: Status expected:<400> but was:<200>
[ERROR] com.adgendoc.api.ExtractionControllerTest.e8_json_charset_iso_8859_1_returns_400_validation_error -- Time elapsed: 0.042 s <<< FAILURE!
java.lang.AssertionError: Status expected:<400> but was:<200>
[ERROR] Tests run: 16, Failures: 4, Errors: 0, Skipped: 0
```

`text/plain`, en-tête absent et `charset=ISO-8859-1` répondaient `200`. `application/x-www-form-urlencoded` répondait `400` sans `$.code`.

### Après correctif (orchestrateur)

Même commande. Fin `2026-10-25T01:03:59+02:00`.

```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.260 s -- in com.adgendoc.api.ExtractionControllerTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Preuves du testeur

`mvn -q` depuis `backend/`. Les journaux complets sont dans les fichiers d'évidence. Maven `-q` n'imprime pas `Tests run`. Les comptes viennent des rapports Surefire relus après ces exécutions.

- `docs/reports/evidence/LA-extraction-controller-test.txt:58` — `EXIT_CODE: 0`. Lignes 1-7 : PowerShell a classé l'avertissement JVM en erreur native ; le code de sortie reste 0.
- `docs/reports/evidence/LA-controller-regression.txt:196` — `EXIT_CODE: 0`.
- `TEST-com.adgendoc.api.ExtractionControllerTest.xml` : `tests="16" errors="0" skipped="0" failures="0"`.
- `TEST-com.adgendoc.api.DocumentRequestControllerTest.xml` : `tests="37" errors="0" skipped="0" failures="0"`.
- `TEST-com.adgendoc.api.PhaseEContractVerificationTest.xml` : `tests="23" errors="0" skipped="0" failures="0"`.

Aucune de ces trois classes ne porte `@SpringBootTest`. `HttpVerticalSliceIT` et les tests à datasource : non lancés.

## RISKS

- E8 refuse un charset autre que UTF-8. E1 et E3 ne le font pas : leur `consumes` implicite ignore ce paramètre. Ce lot ne les modifie pas.
- Un nom de charset illégal peut échouer dans le convertisseur avant `rejectNonUtf8Charset`. Non exercé.
- Le champ `message` du `400` vient de `GlobalExceptionHandler`, hors diff. Les tests vérifient `code`, `correlationId`, `missingFields`, `fieldErrors`, et l'absence de `valid`.

## Verdicts recopiés

Tester :

```
VERDICT: PASS
```

Reviewer :

```
VERDICT: PASS
```

Security :

```
VERDICT: PASS
```

## Gates

| Gate | Statut |
|---|---|
| G0 inventaire | NOT_RUN |
| G1 build + unitaires exécutés (`ExtractionControllerTest`, `DocumentRequestControllerTest`, `PhaseEContractVerificationTest`) | PASS |
| G2 persistance | NOT_RUN |
| G3 DOCX | NOT_RUN |
| G4 HTTP E2E | NOT_RUN |
| G5 fixtures | NOT_RUN |
| G6 n8n runtime | NOT_RUN |
| G7 sécurité (revue lecture seule de ce lot) | PASS |
| G8 CI | NOT_RUN |

G7 est la revue sécurité de ce lot, pas un scan de dépendances.

## Phrase de contrat proposée (non appliquée)

À poser par un lot documentaire après accord. Elle n'est pas écrite dans `API_CONTRACTS.md`.

> Pour tout POST à corps JSON, les seules valeurs de Content-Type acceptées sont `application/json` et `application/json; charset=UTF-8` (casse du charset indifférente). En-tête absent ou toute autre valeur : `400` `VALIDATION_ERROR`, corps `ErrorResponse`, requête non traitée et non persistée. Sur `POST /api/v1/extraction/validate`, ce rejet d'en-tête est cet `ErrorResponse` ; le `400` de schéma (JSON malformé, clé inconnue, type, enum, `documentType`, date) reste un `ExtractionValidationResponse`. Le `400` `VALIDATION_ERROR` de `POST /extraction/validate` au tableau §4 vise le rejet d'en-tête.

Conséquence si cette phrase est adoptée telle quelle : E1 et E3 devront, dans un lot de code séparé, rejeter eux aussi un charset autre que UTF-8. Aujourd'hui ils ne le font pas.

## NEXT_ACTION

Accord humain sur la phrase ci-dessus, puis lot documentaire séparé. Pas de commit dans ce lot.
