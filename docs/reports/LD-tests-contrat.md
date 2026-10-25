# LD — tests de contrat E1 à E8, niveau contrôleur

Dix tests MockMvc ajoutés dans `DocumentRequestControllerTest`. Aucun code de production, aucun `pom.xml`, aucun contrat, aucune fixture, aucune assertion existante. Aucun commit. L'index n'a pas été touché (`docs/reports/evidence/LD-index.txt` vide ; `LD-git-status.txt` montre ` M` sur le fichier de test, pas d'entrée en première colonne).

## Où les tests sont écrits

Dans `backend/src/test/java/com/adgendoc/api/DocumentRequestControllerTest.java`. Les utilitaires (`validBody`, `createRequest`, `createMissingRequest`, `createRejectedRequest`, `generateDocument`, le dépôt en mémoire) sont privés à cette classe. Une classe nouvelle les aurait recopiés. Les méthodes déjà présentes n'ont pas été refactorées : dix `@Test` et des helpers privés ne servent que les tests nouveaux.

Chaque test est une instance JUnit neuve (`@BeforeEach` reconstruit les services sur un dépôt vide). Aucun ordre d'exécution.

## STATUS

G1 PASS. `mvnw.cmd -B test` : **308** tests, **0** échec, **0** erreur, **0** ignoré, code 0 (`docs/reports/evidence/LD-mvn-test.txt:589` et `:592`). Référence 298 + 10 tests ajoutés. Aucun test « écart connu » désactivé.

G4 NOT_RUN. Le contrat HTTP de bout en bout se prouve contre un serveur réel. Ici le contrat est vérifié au niveau contrôleur (MockMvc standalone, ports en mémoire, aucun PostgreSQL, aucun Docker, aucun n8n).

G7 PASS, sur le diff de ce lot seulement.

Reviewer : `VERDICT: PASS`. Aucun doublon, aucune assertion vacante, aucune assertion sur un détail d'implémentation. Le replantage `setPayload` est accepté comme fixture. [Relecture](cd607f5d-511a-43a3-bfaf-28095ff52a0f).

Security : `VERDICT: PASS`. Un seul fichier de test. `password=` et `jdbc:postgresql://` du journal sont `REDACTED` (`LD-mvn-test.txt:40` et `:120`). Les noms et le marqueur hostile encore visibles dans la stack du test de sanitation préexistant ne viennent pas de ce lot. [Revue](515661db-3ef6-43d5-990b-c8ebdd8426cc).

## FINDINGS

Les dix tests passent contre le code actuel. Le JSON malformé d'E3 produit bien `400` `VALIDATION_ERROR`, `fieldErrors[0].field = payload`, sans mutation. Ce n'est pas un écart de code. `API_CONTRACTS` §3.3 reste muet : voir les écarts à arbitrer.

Le cas 6 (E4, règle métier depuis `FAILED`) a été écrit. `DocumentRequest.setPayload` existe déjà. Le test replante `nomIncorrect` = `nomCorrect` sur l'agrégat tenu par le dépôt en mémoire, puis appelle `POST .../validate`. Aucune méthode de production n'a été ajoutée. Résultat observé : `400` `VALIDATION_ERROR`, corps `status = REJECTED`, `fieldErrors[0].code = ERR_NOM_CONCORDANCE_IDENTIQUE`, statut persisté `REJECTED` (T12b).

Cette cellule n'est pas produite par l'enchaînement public : E1 persiste déjà `REJECTED` quand la règle est violée, et E4 sur `REJECTED` répond `409`. Le test prouve le recalcul d'E4, pas un parcours client.

T10 (`VALIDATED` → `REJECTED` par un PATCH E3) n'était pas dans la liste approuvée. Il reste absent au niveau HTTP. Il est atteignable par l'API (PATCH sur une demande `VALIDATED`), contrairement à T12b. T6 par E3 est déjà couvert (`DocumentRequestControllerTest.java:543`).

### Tableau — cellules du contrat

« Avant » = couverture au moment du plan. « Après » = ce lot. Les lignes déjà couvertes n'ont pas reçu de second test.

| Cellule | HTTP / effet | Avant | Après |
|---|---|---|---|
| E1 201 `VALIDATED` | persisté | `#e1_valid_payload_returns_201_validated` `:121` | inchangé |
| E1 422 `MISSING_INFORMATION` | persisté | `#e1_missing_fields_returns_422_in_canonical_order` `:148` | inchangé |
| E1 400 règle métier | persisté `REJECTED` | `:199`, `:237` | inchangé |
| E1 400 enveloppe | non persisté, `field = payload` | `:287` | inchangé |
| E1 400 clé racine inconnue | non persisté | `:257` | inchangé |
| E1 400 `Content-Type` | `field = Content-Type` | `:304`–`:388` | inchangé |
| E1 413 | non persisté | `:396` | inchangé |
| Correlation absent / valide | généré / renvoyé | `:826`, `:841`, `PhaseEContractVerificationTest.java:450` | inchangé |
| Correlation invalide | remplacé, pas un 400 (ADR-003) | `PhaseEContractVerificationTest.java:468` (GET) | inchangé, pas de second test |
| E2 200 / 404 | | `:414`, `:426` | inchangé |
| E2 identifiant mal formé | voir écarts | statut 400 seulement, `PhaseEContractVerificationTest.java:272` | inchangé, assertion non modifiée |
| E3 depuis `MISSING_INFORMATION` | 200 `VALIDATED` ou conservé | `:460`, `:490` | inchangé |
| E3 clé inconnue / réservée | 400, aucune mutation | `:502`, `:528` | inchangé |
| E3 règle métier depuis `MISSING_INFORMATION` | 400, persisté `REJECTED` (T6) | `:543` | inchangé |
| E3 depuis `REJECTED` | 409 `REQUEST_ALREADY_CLOSED` | `:564` | inchangé |
| E3 depuis `GENERATED` | 409, non modifié | ABSENT | `#e3_patch_on_generated_returns_409_request_already_closed` `:858` |
| E3 depuis `FAILED` | 200 `VALIDATED` (T12) | ABSENT | `#e3_patch_on_failed_request_returns_200_validated` `:876` |
| E3 depuis `VALIDATED` conservé | 200 `VALIDATED` (T11) | ABSENT (E4 seulement, `:654`) | `#e3_patch_on_validated_request_keeps_validated` `:894` |
| E3 depuis `VALIDATED` rétrogradé | 200 `MISSING_INFORMATION` (T7) | ABSENT | `#e3_patch_clearing_required_field_returns_200_missing_information` `:912` |
| E3 JSON malformé | 400, `field = payload`, aucune mutation | ABSENT (question L0c-CT2) | `#e3_malformed_json_returns_400_payload_without_mutation` `:931` — le code est d'accord ; §3.3 reste muet |
| E3 depuis `DRAFT` | recalcul | ABSENT | non atteignable par le contrat (`API_CONTRACTS.md:356`) |
| E3 `Content-Type` / 404 | | `:577`, `:586` | inchangé |
| E4 `VALIDATED` | 200 idempotent | `:654` | inchangé |
| E4 champs absents | 422 | `:664` | inchangé |
| E4 `REJECTED` / `GENERATED` | 409 `INVALID_STATUS` | `:675` ; `PhaseEContractVerificationTest.java:377` | inchangé |
| E4 `FAILED`, données valides | 200 `VALIDATED` (T12) | ABSENT | `#e4_validate_on_failed_request_returns_200_validated` `:952` |
| E4 règle métier | 400, persisté `REJECTED` (T12b) | ABSENT | `#e4_validate_on_failed_request_with_broken_rule_returns_400_rejected` `:967` (replanté en mémoire) |
| E4 depuis `DRAFT` | 200, 400 ou 422 | ABSENT | non atteignable par le contrat (`API_CONTRACTS.md:356`) |
| E4 404 | | `PhaseEContractVerificationTest.java:436` | inchangé |
| E5 `VALIDATED` | 201 `GENERATED` | `:691` | inchangé |
| E5 `MISSING_INFORMATION` | 409 `INVALID_STATUS` | `:716` | inchangé |
| E5 `REJECTED` | 409, non modifié | ABSENT | `#e5_generate_on_rejected_request_returns_409_invalid_status` `:987` |
| E5 `GENERATED` | 409, non modifié | ABSENT | `#e5_generate_on_generated_request_returns_409_invalid_status` `:1003` |
| E5 `FAILED` | 409, non modifié | ABSENT | `#e5_generate_on_failed_request_returns_409_invalid_status` `:1020` |
| E5 depuis `DRAFT` | 409 `INVALID_STATUS` (`API_CONTRACTS.md:391`) | ABSENT | non atteignable par le contrat : E1 ne renvoie jamais `DRAFT` (`API_CONTRACTS.md:356`) |
| E5 500 template / fusion | passage à `FAILED` | `:732` ; `PhaseEContractVerificationTest.java:392` | inchangé |
| E5 404 | | `:751` | inchangé |
| E6 200 DOCX | type DOCX | `:762` | inchangé |
| E6 document inconnu | 404 `DOCUMENT_NOT_FOUND` | `:776` | inchangé |
| E6 demande inconnue | voir écarts | `:786` assert `DOCUMENT_NOT_FOUND` (`:793`) | assertion non modifiée |
| E7 200 `UP` / 503 `DOWN` | | `:801`, `:808` | inchangé |
| E8 200, 400 `errors[]`, type, `Content-Type`, date ISO | | `ExtractionControllerTest.java:41`, `:69`, `:93`, `:157` | inchangé |

Les 409 nouveaux comparent un instantané avant/après : statut, données, champs manquants, `updatedAt`, version, nombre de documents, nombre d'enregistrements. Le JSON malformé d'E3 fait la même comparaison.

### DRAFT — non atteignable par le contrat

`API_CONTRACTS.md:356` : le PATCH ne passe jamais par `DRAFT`, état interne réservé à la création. E1 ne le renvoie pas. Aucun test DRAFT n'a été écrit.

| Départ | Endpoint | Résultat qu'aurait le contrat | Statut de la cellule |
|---|---|---|---|
| `DRAFT` | E3 | recalcul (200 ou 400), pas un 409 | non atteignable par le contrat |
| `DRAFT` | E4 | 200, 400 ou 422 (`API_CONTRACTS.md:370-374`) | non atteignable par le contrat |
| `DRAFT` | E5 | 409 `INVALID_STATUS` (`API_CONTRACTS.md:391`) | non atteignable par le contrat |

### Écarts connus (code)

Aucun. Aucun test désactivé.

### Écarts de contrat à arbitrer

Les assertions existantes n'ont pas été modifiées.

1. **E2, identifiant mal formé.** `PhaseEContractVerificationTest.java:272` attend HTTP 400 pour `GET /api/v1/requests/not-a-uuid`. Le code métier n'y est pas nommé. Le tableau E2 (`requirements/API_CONTRACTS.md:306-310`) ne liste que 200, 404 et 500. §1.2 (`requirements/API_CONTRACTS.md:31`) dit que les identifiants sont des UUID v4, sans code HTTP pour une valeur mal formée.
2. **E6, demande inconnue.** `DocumentRequestControllerTest.java:793` attend `DOCUMENT_NOT_FOUND` alors que le `requestId` du chemin n'existe pas. `requirements/API_CONTRACTS.md:410` autorise `DOCUMENT_NOT_FOUND` ou `REQUEST_NOT_FOUND`.
3. **E3, JSON malformé.** Le test `:931` fige le comportement du code et de `requirements/N8N_CONTRACTS.md:193` (`400` `VALIDATION_ERROR`, `fieldErrors[0].field = payload`, aucune mutation). `requirements/API_CONTRACTS.md:344` ne décrit que le rejet d'en-tête. Question ouverte : `docs/reports/L0c-CT2.md:40-50`. Le code n'a pas été contredit, donc le test reste actif. Reste à décider si §3.3 doit le dire.

T10 (`VALIDATED` → `REJECTED` via E3) reste une cellule HTTP absente, atteignable sans planter le dépôt. Hors de la liste approuvée.

## FILES_CHANGED

- `backend/src/test/java/com/adgendoc/api/DocumentRequestControllerTest.java` — 230 lignes ajoutées (`LD-diff-stat.txt`). Non indexé.
- `docs/reports/evidence/LD-mvn-test.txt` — journal de `mvnw.cmd -B test`. Les `jdbc:postgresql://…` et `password=` du test de sanitation existant sont remplacés par `REDACTED`.
- `docs/reports/evidence/LD-diff.txt`, `LD-diff-stat.txt`, `LD-git-status.txt`, `LD-index.txt`
- `docs/reports/LD-tests-contrat.md` — ce rapport

## TESTS

```text
mvnw.cmd -B test
Tests run: 308, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`LD-mvn-test.txt:589-592`. Durée Maven 32,239 s. Aucun `*IT`. Tests « écart connu » désactivés : 0.

Contrôle ciblé préalable, même classe : 62 tests, 0 échec (52 anciens + 10 nouveaux).

## RISKS

- T10 n'est toujours pas verrouillé en HTTP.
- Le 400 d'E4 (T12b) n'est prouvé qu'après replantage en mémoire. Un client qui n'utilise que E1 puis E4 ne l'observe pas : il reçoit le 409 d'une demande déjà `REJECTED`.
- E3 JSON malformé est verrouillé par un test alors que `API_CONTRACTS` §3.3 ne le dit pas. Un arbitrage qui choisirait un autre corps casserait ce test, à dessein.
- G4 reste ouvert : MockMvc ne remplace pas `HttpVerticalSliceIT`.

## NEXT_ACTION

Arbitrer les trois écarts de contrat. Décider si un test HTTP de T10 (E3 depuis `VALIDATED` vers `REJECTED`) entre dans un lot suivant. Ne pas traiter ce lot comme une preuve G4.
