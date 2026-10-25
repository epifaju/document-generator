# LA2 — Content-Type E1 / E3 (Option A)

## STATUS

**PASS** — un cycle. Décision humaine : Option A. Reviewer : PASS. Security : PASS. Tester : PASS sur la portée lancée.

Le rejet d'en-tête `Content-Type` de E1, E3 et E8 renvoie `400` `VALIDATION_ERROR`, `ErrorResponse`, `fieldErrors[0].field = "Content-Type"`, sous-code existant `ERR_PAYLOAD_INVALIDE`, message de champ fixe. Le JSON malformé d'E1 reste `field = "payload"`. Aucun code d'erreur nouveau. Contrats et ADR non modifiés.

## FINDINGS

- E1 et E3 n'avaient ni `consumes` ni garde charset (`DocumentRequestController.java`, avant le lot). `charset=ISO-8859-1` passait (E1 `201`, E3 `200`). Les autres rejets déjà en `400` portaient `field = "payload"`, identique au JSON malformé (`GlobalExceptionHandler` via `payloadFieldError()`).
- Correctif : `consumes = application/json` et `rejectNonUtf8Charset` en tête de `create` et `patch`, avant `requestService` (`DocumentRequestController.java:62-66`, `:84-90`, `:158-169`). Copie du mécanisme E8 (`ExtractionController.java:33`, `:56-67`). E4 et E5 inchangés (`DocumentRequestController.java:99-110`) : POST sans corps, hors §1.1.
- Handler partagé : `handleUnsupportedMediaType` (`GlobalExceptionHandler.java:129-135`) appelle `contentTypeFieldError()` (`:263-266`). Le paramètre d'exception n'est pas interpolé. Le message est `ERR_PAYLOAD_INVALIDE.getMessage()` (« Corps de requête invalide ou JSON malformé. »). Le JSON malformé reste sur `payloadFieldError()` (`:117-126`).
- Conséquence de la décision, pas une modification du mécanisme d'entrée du lot A : le corps du rejet d'en-tête E8 change aussi (`field` de `payload` à `Content-Type`). `ExtractionControllerTest.java:217` a été aligné pour ça. L'ingress E8 (`consumes`, `rejectNonUtf8Charset`) n'a pas été modifié.
- Test existant supplémentaire aligné et **non exécuté** : `HttpVerticalSliceIT.java` (assertion `field` `payload` → `Content-Type`). PostgreSQL interdit dans ce lot.
- Tests structurels E3 préexistants (clé inconnue / réservée, avec `requestId` et `status`) : non modifiés. `e1_non_json_content_type_returns_400_payload_invalid` non modifié (il ne vérifie que le sous-code).
- Discriminant pour n8n : `fieldErrors[0].field`. `Content-Type` = rejet d'en-tête. `payload` = JSON malformé. Les autres enveloppes restent sur leur champ (`documentType`, `data`, clé, `referenceDemande`).

## FILES_CHANGED

- `backend/src/main/java/com/adgendoc/api/DocumentRequestController.java`
- `backend/src/main/java/com/adgendoc/api/GlobalExceptionHandler.java`
- `backend/src/test/java/com/adgendoc/api/DocumentRequestControllerTest.java`
- `backend/src/test/java/com/adgendoc/api/ExtractionControllerTest.java` (conséquence Option A sur le handler partagé)
- `backend/src/test/java/com/adgendoc/e2e/HttpVerticalSliceIT.java` (assertion alignée, NOT_RUN)
- `docs/reports/evidence/LA2-before.txt`
- `docs/reports/evidence/LA2-lot.txt`
- `docs/reports/evidence/LA2-regression.txt`
- `docs/reports/evidence/LA2-diff.txt`
- `docs/reports/LA2-e1-e3-content-type.md`

Aucun contrat, ADR, fixture, workflow, `pom.xml`. Aucun commit.

## TESTS

Avant correctif (`docs/reports/evidence/LA2-before.txt`, `DocumentRequestControllerTest` + `ExtractionControllerTest`) :

```
[ERROR] Tests run: 68, Failures: 13, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Extraits : `e1_json_charset_iso_8859_1` Status expected `<400>` but was `<201>` ; `e3_json_charset_iso_8859_1` Status expected `<400>` but was `<200>` ; rejets `text/plain`, form-urlencoded, en-tête absent et E8 : `fieldErrors[0].field` expected `<Content-Type>` but was `<payload>`.

Après correctif, commande officielle du testeur (`docs/reports/evidence/LA2-lot.txt`) :

```
COMMANDE: mvn -q "-Dtest=DocumentRequestControllerTest,ExtractionControllerTest" test
EXIT_CODE: 0
```

Régression contrôleurs / handlers standalone (`docs/reports/evidence/LA2-regression.txt`) :

```
COMMANDE: mvn -q "-Dtest=DocumentRequestControllerTest,ExtractionControllerTest,PhaseEContractVerificationTest,DatabaseErrorHandlerTest,DatabaseErrorLogSanitizationTest" test
EXIT_CODE: 0
```

Même jeu de cinq classes, sans `-q`, juste après le correctif : `Tests run: 94, Failures: 0, Errors: 0, Skipped: 0` puis `BUILD SUCCESS` (DocumentRequestControllerTest 52, ExtractionControllerTest 16, PhaseEContractVerificationTest 23, DatabaseErrorHandlerTest 1, DatabaseErrorLogSanitizationTest 2).

`mvn -q` n'imprime pas le décompte Surefire. Le code de sortie 0 est celui des fichiers d'évidence.

Non lancés, parce qu'ils ouvrent un contexte avec datasource ou PostgreSQL : `RequestLoggingSanitizationTest`, `DocumentGenerationFlowTest`, `RequestPersistenceFlowTest`, `HttpVerticalSliceIT`.

### Gates

| Gate | Statut |
|---|---|
| G0 | NOT_RUN |
| G1 | PARTIEL — seulement `DocumentRequestControllerTest`, `ExtractionControllerTest`, `PhaseEContractVerificationTest`, `DatabaseErrorHandlerTest`, `DatabaseErrorLogSanitizationTest` |
| G2 | NOT_RUN |
| G3 | NOT_RUN |
| G4 | NOT_RUN |
| G5 | NOT_RUN |
| G6 | NOT_RUN |
| G7 | PASS (revue security de ce lot) |
| G8 | NOT_RUN |

Écart toujours ouvert : le workflow n8n exporté et les fixtures ne sont pas vérifiés pour la règle Content-Type du webhook. Ce lot ne les modifie pas et ne les exécute pas.

## Phrases proposées (non appliquées)

Lot documentaire séparé. Rien de ceci n'est écrit dans les contrats ni dans l'ADR.

**`requirements/API_CONTRACTS.md` §1.1**, à la suite de la phrase sur l'en-tête absent ou toute autre valeur :

> Le corps de ce rejet est un `ErrorResponse` (`code = VALIDATION_ERROR`) dont `fieldErrors[0].field` vaut `Content-Type` et `fieldErrors[0].code` vaut `ERR_PAYLOAD_INVALIDE`. Le message de cette entrée est fixe et ne reprend pas la valeur de l'en-tête reçu. Ce champ distingue le rejet d'en-tête d'une enveloppe invalide, dont le JSON malformé reste `fieldErrors[0].field = payload` avec le même sous-code.

**`requirements/API_CONTRACTS.md` §3.1**, en précision de la ligne `400` qui regroupe aujourd'hui enveloppe invalide et Content-Type :

> `Content-Type` absent ou non autorisé (§1.1) : requête non traitée et non persistée, `fieldErrors[0].field = Content-Type`, `fieldErrors[0].code = ERR_PAYLOAD_INVALIDE`. JSON malformé : même code HTTP et même sous-code, `fieldErrors[0].field = payload`.

**`requirements/N8N_CONTRACTS.md` §3.4**, en précision des lignes `VALIDATION_ERROR` et `INTERNAL_ERROR` :

> Le rejet d'en-tête Content-Type sur E1, E3 ou E8 est le `400` backend dont le corps est un `ErrorResponse`, `code = VALIDATION_ERROR`, et `fieldErrors[0].field = Content-Type`. n8n le route en `500` `INTERNAL_ERROR`. Tout autre `400` `VALIDATION_ERROR` non persistant d'E1 ou d'E3, y compris `fieldErrors[0].field = payload`, reste un `400` `INVALID_REQUEST`.

**Addendum ADR-015** (non appliqué) :

> Addendum — lot A2, décision humaine Option A. Le rejet d'en-tête (`400` `VALIDATION_ERROR`, `ErrorResponse`) porte `fieldErrors[0].field = Content-Type` et le sous-code existant `ERR_PAYLOAD_INVALIDE`. Aucun code d'erreur nouveau. Le handler partagé `handleUnsupportedMediaType` applique ce champ à E1, E3 et E8. Le JSON malformé reste `field = payload`. Le routage n8n de ce rejet vers `500` `INTERNAL_ERROR` s'appuie sur `fieldErrors[0].field = Content-Type`.

## RISKS

- `HttpVerticalSliceIT` aligné dans le source et non exécuté. La slice HTTP réelle n'est pas prouvée.
- Contrats et ADR encore muets sur `field = Content-Type`. Le code et le texte versionné divergent jusqu'au lot documentaire. n8n ne route pas encore sur ce champ : workflow et fixtures non vérifiés.
- `rejectNonUtf8Charset` est dupliquée entre `DocumentRequestController` et `ExtractionController`.
- `docs/reports/evidence/LA2-regression.txt` reprend la sortie DEBUG d'un test préexistant de sanitisation (`DatabaseErrorLogSanitizationTest`), avec des marqueurs synthétiques. Ce n'est pas une fuite du handler Content-Type. Ne pas rediffuser cet extrait.

## NEXT_ACTION

Lot documentaire : appliquer les phrases ci-dessus dans §1.1, §3.1, §3.4 et un addendum ADR-015, puis vérifier le workflow n8n exporté et les fixtures sur le routage `field = Content-Type` → `500` `INTERNAL_ERROR`. Exécuter `HttpVerticalSliceIT` seulement avec PostgreSQL autorisé.
