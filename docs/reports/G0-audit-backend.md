# G0 — Audit backend (lecture seule)

| | |
|---|---|
| Rôle | orchestrator |
| Périmètre | `backend/`, `database/migrations/`, contrats et PRD cités |
| Date | 2026-10-25 |
| Commandes | Aucune (pas de Docker, base, réseau, n8n, Ollama, Maven) |
| Modification | Ce rapport uniquement |

VERDICT: FAIL

Trois lectures démontrent un écart (architecture, sécurité, DOCX). L’analyse métier des endpoints E1–E8 ne démontre pas d’écart bloquant avec `API_CONTRACTS` §3–§4. Aucun gate d’exécution n’a de sortie.

Architecte : [Architecte G0](05c6edee-aee7-4e6c-bd52-6c236cf8286d). Analyste : [Analyste G0](ce2d44db-a54d-421e-afcb-950ff63d926d). Sécurité : [Sécurité G0](3b4f4bac-b384-4b1f-902d-f0b0b596f33a). Testeur : [Testeur G0](9603c971-7f67-4e1a-9574-4e6887df28e5).

## Verdicts recopiés

### Architecte

VERDICT: FAIL

Écart bloquant : `docs/PRD_adgendoc_v2_1_Cursor.md:206` exige une classe `RequestStateMachine`. Aucune occurrence dans `backend/`. La matrice est dans `domain/RequestStatus.java:18` et la garde dans `domain/DocumentRequest.java` (transitions appelées depuis `RequestService` et `DocumentGenerationService`). Les six statuts (`RequestStatus.java:11-16`) sont ceux du PRD (`:179`). Le domaine n’importe ni Spring, ni JPA, ni POI. Stack lue dans `backend/pom.xml` : Java 17 (`:21`), Spring Boot parent 3.3.13 (`:7-12`), POI 5.2.5 (`:24-25`, `:63-66`). Migrations `database/migrations/V1__init.sql` et `V2__seed_template.sql`, lues par Flyway, non éditées par le code.

### Analyste métier

VERDICT: PASS

E1–E8 sont présents dans les contrôleurs. Le mapping HTTP / `code` lu dans le code suit `API_CONTRACTS` §3 pour les scénarios cités. Aucun écart bloquant code ↔ §3–§4. FR-01 à FR-04 et FR-08 à FR-10 : implémentés. FR-05 à FR-07, FR-11, FR-14, FR-15 : partiels. FR-12, FR-13, FR-16 : absents du backend (FR-12 et FR-13 sont du périmètre n8n).

### Sécurité

VERDICT: FAIL

Écart bloquant : `requirements/API_CONTRACTS.md:24` et `:434` imposent `Content-Type: application/json` sur les POST. `ExtractionController.java:31-33` est un `@PostMapping("/validate")` sans `consumes`, avec `@RequestBody String`. E1 (`DocumentRequestController.java:61-62`) lie un DTO ; le rejet de type non supporté est dans `GlobalExceptionHandler.java:128-134`. Le rejet runtime d’un `text/plain` sur E8 n’a pas été exécuté. Secrets : mot de passe par variable (`application.yml:16`) et `DatasourceSecretGuard`. Taille 65 536 octets et `X-Correlation-Id` : conformes d’après la lecture du filtre (non réexécutée ici). E6 sans authentification : risque it.1 documenté (`API_CONTRACTS.md:50-53`, PRD `:329`), pas un défaut d’itération 1.

### Testeur

VERDICT: FAIL

Écart bloquant : PRD FR-06 (`docs/PRD_adgendoc_v2_1_Cursor.md:145`) exige zéro résidu `${…}` et `{{…}}`. `PoiTemplateEngine.java:61` ne reconnaît que `{{…}}` ; le contrôle après fusion (`:172-176`) ne porte que sur `{{` et `}}`. Runs éclatés (`:157-184`), tableaux (`:132-141`) et en-têtes/pieds (`:87-94`) sont parcourus. Le mapping camelCase → snake_case est dans `DocumentGenerationService.java:163-181`, pas dans le moteur. `backend/mvnw` et `backend/mvnw.cmd` sont absents. 23 classes de test inventoriées ; aucune lancée.

## Écarts dépôt ↔ PRD §12.1 et §12.2

Source de vérité : `requirements/API_CONTRACTS.md` prime sur le PRD. Un écart PRD ↔ contrat n’est pas un défaut du code lorsque le code suit le contrat.

### §12.1 — ce que la lecture aligne

D’après l’analyste (code lu, HTTP non appelé) : E3 `MISSING_INFORMATION` reste un **200** (`docs/PRD_adgendoc_v2_1_Cursor.md:283`, `API_CONTRACTS` §3.3) ; le **422** est réservé à E1 et E4 (`:283`) ; **409** `INVALID_STATUS` sur E4/E5 et `REQUEST_ALREADY_CLOSED` sur E3 (`:280-281`) ; **413** `VALIDATION_ERROR` au-dessus de 65 536 octets (`:282`) ; **500** de génération et **503** de santé sont présents dans le code cité. **401/403** absents, conforme à l’itération 1 (`:278`).

### §12.1 — écarts

| Écart | Preuve | Gravité |
|---|---|---|
| E8 n’exige pas `Content-Type: application/json`, alors que §12.1:277 et `API_CONTRACTS.md:24` rejettent tout autre type par **400** `VALIDATION_ERROR`, non persisté | `ExtractionController.java:31-33` | Bloquant (sécurité) |
| §12.1:277 range E8 dans **400** `VALIDATION_ERROR` + `fieldErrors[]`. Le code renvoie `ExtractionValidationResponse` | `ExtractionController.java:41-43` suit `API_CONTRACTS.md:440`. Contradiction PRD ↔ contrat, non tranchée | Info — question Q3 |
| E6, `requestId` inconnu : le code produit toujours `DOCUMENT_NOT_FOUND`. §12.1:279 cite les deux codes ; §3.6 autorise aussi `REQUEST_NOT_FOUND` | Constat analyste, `DocumentGenerationService.java:139-141` | Info — question Q6 |

### §12.2 — webhook n8n

**Inconnu.** Cet audit n’a pas lu `n8n/workflows/` ni relancé le validateur de fixtures. Aucun écart dépôt ↔ §12.2 n’est démontré, et aucun alignement n’est affirmé. FR-12 et FR-13 restent hors backend. G5 et G6 = `NOT_RUN`.

## Gates

| Gate | Statut | Preuve |
|---|---|---|
| G0 | NOT_RUN | Inventaire de lecture = ce fichier. `git status` non exécuté. Le PRD (`:350`) inclut cette sortie : sans elle, G0 n’est pas PASS |
| G1 | NOT_RUN | `mvn -f backend/pom.xml verify` non lancé. `./mvnw` absent (`docs/PRD_adgendoc_v2_1_Cursor.md:351`) |
| G2 | NOT_RUN | `mvn -f backend/pom.xml test -Dtest=PostgresPersistenceIT` non lancé. La classe vise un PostgreSQL externe, pas Testcontainers |
| G3 | NOT_RUN | `PoiTemplateEngineTest` et `DocumentGenerationFlowTest` non lancés. Lecture seule du moteur |
| G4 | NOT_RUN | `HttpVerticalSliceIT` non lancé |
| G5 | NOT_RUN | Validateur n8n non lancé dans cet audit |
| G6 | NOT_RUN | Runtime n8n non autorisé |
| G7 | FAIL | Revue sécurité exécutée en lecture. Défaut : `ExtractionController.java:31-33` vs `API_CONTRACTS.md:24`. Scan CVE des dépendances : NOT_RUN |
| G8 | NOT_RUN | Aucune CI lancée |

Le pattern Surefire par défaut ne prend pas les classes `*IT` (`PostgresPersistenceIT`, `HttpVerticalSliceIT`). Un futur `mvn test` sans `-Dtest` ne constitue pas G2 ni G4.

## Cinq risques

1. **E8 et le type de corps.** Un POST qui n’est pas du JSON peut être lu comme une chaîne (`ExtractionController.java:31-33`), à l’encontre de `API_CONTRACTS.md:24`.
2. **DOCX et `${…}`.** Le moteur échoue sur un `{{…}}` résiduel (`PoiTemplateEngine.java:172-176`) et ne voit pas `${…}` (`:61`), alors que FR-06 (`PRD:145`) cite les deux formes.
3. **Machine à états dispersée.** Pas de `RequestStateMachine` (PRD `:206` et règle `.cursor/rules/10-architecture.mdc`). La matrice testée existe (`RequestStatusTransitionTest`), l’audit dans la même transaction que la transition n’est pas prouvé (NOT_RUN).
4. **Téléchargement E6 et `data` sur E2.** Sans authentification en itération 1, documenté (PRD `:329`, Q-09). Bloquant avant toute exposition hors loopback, pas un défaut de ce gate d’itération 1.
5. **Aucun gate d’exécution.** G1–G6 et G8 sont `NOT_RUN`. Java du dépôt = 17 (`pom.xml:21`) ; le PRD §10 recommande 21. Les `*IT` sortent du `verify` par défaut.

## Plan — cinq tâches

Chaque tâche est petite, test d’abord, et s’arrête à son critère. Pas de refactor mêlé.

| # | Portée | Fichiers | Test d’abord | Critère | Gate |
|---|---|---|---|---|---|
| 1 | E8 refuse un `Content-Type` autre que JSON, même réponse que E1 | `ExtractionController.java`, `ExtractionControllerTest.java` | Cas `text/plain` attendu **400** `VALIDATION_ERROR`, corps non traité | `API_CONTRACTS.md:24` et `:434` | G1. G4 reste NOT_RUN tant que l’IT n’est pas lancée |
| 2 | La fusion échoue si `${…}` reste dans le texte fusionné | `PoiTemplateEngine.java`, `PoiTemplateEngineTest.java` | Un DOCX de test avec `${reste}` doit lever `DocumentGenerationException` | FR-06, PRD `:145` | G1 pour le test ; G3 NOT_RUN tant que non exécuté |
| 3 | L’oracle DOCX lit aussi en-têtes et pieds | `PoiTemplateEngineTest.java` (`documentText`, `:348-356`) | Le test d’en-têtes/pieds échoue si le texte extrait ignore ces zones | PRD `:340` | G1 / G3 comme la tâche 2 |
| 4 | Rendre l’exclusion des `*IT` explicite | `backend/pom.xml` | Aucun test Java nouveau : la commande sans `-Dtest` ne doit pas prétendre lancer `PostgresPersistenceIT` | Propriété documentée, G2 seulement avec `-Dtest=PostgresPersistenceIT` et accord base | G1 pour le build ; G2 NOT_RUN |
| 5 | Une seule classe de transitions, après la réponse à Q1 | Si Q1 = classe : extraire `RequestStateMachine` depuis `RequestStatus.java` et `DocumentRequest.java`, tests `RequestStatusTransitionTest` inchangés dans leur oracle. Si Q1 = ADR : l’ADR seulement, pas de déplacement de code | `RequestStatusTransitionTest` reste la matrice T1–T14 | PRD `:206` ou ADR qui écarte cette phrase | G1 |

La tâche 5 ne démarre pas avant la réponse à Q1. Les tâches 1 à 4 n’attendent pas cette réponse.

## Questions bloquantes

1. **Machine à états.** Introduire `RequestStateMachine`, ou un ADR qui acte `RequestStatus` + `DocumentRequest` comme forme acceptée du PRD `:206` ?
2. **JDK.** Rester sur Java 17 (`pom.xml:21`) ou aligner le PRD §10 (21) ?
3. **Corps 400 de E8.** `ExtractionValidationResponse` (`API_CONTRACTS.md:440`, code actuel) ou `ErrorResponse` `VALIDATION_ERROR` (PRD §12.1:277 et tableau §4) ? Non tranché ici.
4. **`${…}`.** FR-06 est-il contraignant, ou seuls les `{{…}}` du template officiel le sont ?
5. **G2.** Testcontainers (PRD §14) ou PostgreSQL externe, comme `PostgresPersistenceIT` aujourd’hui ?
6. **E6 et demande inconnue.** Garder uniquement `DOCUMENT_NOT_FOUND`, ou produire aussi `REQUEST_NOT_FOUND` lorsque le contrat §3.6 le permet ?
7. **`mvn verify`.** Doit-il inclure les `*IT`, ou rester sur les `*Test` avec une commande G2 séparée ?
8. **FR-15 (proposé).** Empreinte du template officiel, ou empreinte du DOCX produit (comportement lu dans `DocumentGenerationService.java:91`) ?

## STATUS

Audit de lecture terminé. Verdict d’ensemble FAIL. Aucun fichier de code modifié. Aucun commit.

## FINDINGS

- Hexagone présent : six ports et six adapters ; domaine sans Spring, JPA ni POI.
- E1–E8 implémentés ; mapping §3 tenu d’après la lecture de l’analyste.
- Trois écarts bloquants démontrés : pas de `RequestStateMachine` ; E8 sans contrainte de `Content-Type` ; `${…}` ignoré par le moteur DOCX.
- §12.2 non lu. G0–G6 et G8 = NOT_RUN. G7 = FAIL.

## FILES_CHANGED

- `docs/reports/G0-audit-backend.md` — créé.

## TESTS

NOT_RUN. Commandes proposées par le testeur, non lancées : `mvn -f backend/pom.xml verify` ; `mvn -f backend/pom.xml test -Dtest=PostgresPersistenceIT` ; `mvn -f backend/pom.xml test -Dtest=PoiTemplateEngineTest,DocumentGenerationFlowTest` ; `mvn -f backend/pom.xml test -Dtest=HttpVerticalSliceIT`.

## RISKS

Les cinq risques de la section homonyme. Le plus immédiat hors décision humaine est E8 (tâche 1), parce que le contrat §1.1 est déjà tranché.

## NEXT_ACTION

Réponses aux questions 1 à 8, puis tâche 1 (E8) en test d’abord. Pas de Docker, base, réseau, n8n ni Ollama sans accord explicite. G5 reste NOT_RUN.
