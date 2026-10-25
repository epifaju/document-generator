# L0c-ARB — arbitrages de contrat (LD et LC1)

Documentation seulement. Aucun code, test, fixture, `pom.xml`, workflow. Aucun commit. L'index n'a pas été touché (`git diff --cached --name-only` vide après l'écriture).

Date machine (`Get-Date -Format yyyy-MM-dd`) : **2026-10-25**. D4 et D5 portent cette date.

`docs/reports/LC1-testcontainers-g2.md:36` date la même décision Testcontainers du **26/10/2026**. Ce rapport de lot n'a pas été corrigé.

## STATUS

Reviewer, cycle 1 : `VERDICT: FAIL`. Quatre phrases actives de `docs/architecture.md` (§5.3 et §5.4) présentaient encore le 422 comme seul HTTP de `MISSING_INFORMATION`. [Relecture](890e2d49-18b9-4b07-826a-04c2d05e5113).

Cycle 2, après qualification (422 sur E1 et E4, 200 sur E3) : `VERDICT: PASS`. [Relecture](045b4af7-743d-4024-83b6-aac1980e0fe4).

Security : `VERDICT: PASS`. [Revue](f831d1dd-82b6-4f72-8dea-11119411cef8).

TESTS : **NOT_RUN**. La seule capture du tester est `docs/reports/evidence/LARB-diff.txt`.

## FINDINGS

### D1 — E2, identifiant mal formé

Ligne ajoutée au tableau §3.2 (`requirements/API_CONTRACTS.md:310`) : `400` `VALIDATION_ERROR`, `requestId` qui n'est pas un UUID v4, rien de persisté. Pas de nom de champ. Le tableau §4 (`:460`) cite ce GET sur la ligne 400. La ligne 404 (`:463`) vise un UUID v4 valide dont la demande est absente.

Ingress webhook : `requirements/N8N_CONTRACTS.md:93` impose déjà le motif UUID v4 sur `requestId`, sinon `400` `VALIDATION_ERROR`, avant tout appel backend. Aucune ligne n8n ajoutée. Aucune ligne d'historique. Un `400` backend d'E2, s'il était atteint, reste un bug d'orchestration : la règle par défaut (`N8N_CONTRACTS.md:197-203`) en fait un `500` `INTERNAL_ERROR`.

### D2 — E6, un seul code

`requirements/API_CONTRACTS.md:412` : `404` `DOCUMENT_NOT_FOUND` pour une demande inconnue, un document inconnu, ou un document d'une autre demande. §4 (`:464`) isole ce code. `REQUEST_NOT_FOUND` reste le 404 d'E2, d'E4 et d'E5 (`:463`).

`requirements/ATTESTATION_CONCORDANCE.md:627` : sur E6, le seul code est `DOCUMENT_NOT_FOUND`.

`N8N_CONTRACTS.md:468` route un 404 d'E6 après un 201 vers `INTERNAL_ERROR`, sans dépendre de `REQUEST_NOT_FOUND`. Fichier non modifié.

### D3 — E3, JSON malformé

Ligne ajoutée au tableau §3.3 (`requirements/API_CONTRACTS.md:346`) : `400` `VALIDATION_ERROR`, `fieldErrors[0].field = payload`, `fieldErrors[0].code = ERR_PAYLOAD_INVALIDE`, `document_request` non modifié. Le même sous-code distingue le rejet d'en-tête (`field = Content-Type`, ADR-015). `N8N_CONTRACTS.md:193` le disait déjà. Pas d'historique.

### D4 — ADR-002 accepté

`docs/adr/ADR-002-e3-missing-information-200.md:6` et `:47` : **ACCEPTÉ** (2026-10-25). Option 1 : E3 renvoie `200` avec `status = MISSING_INFORMATION`. E1 et E4 conservent `422`. La date de proposition (`:7`, 2026-10-24) n'a pas été réécrite.

Alignés : `ATTESTATION_CONCORDANCE.md:170`, `:402-404`, `:599`, la prescription du Then de S17 (`:658`). Le Given de S17 garde « outcome constaté `422` » (constat de l'exécution 86). `docs/architecture.md:62`, puis, au cycle 2, `:441-444`, `:454`, `:469-470`.

`docs/PRD_adgendoc_v2_1_Cursor.md:314` : Q-02 **RÉSOLUE**, sur le modèle de Q-01, avec renvoi à ADR-002.

### D5 — addendum ADR-09

La cellule `docs/architecture.md:127` reste « Testcontainers : NON en it.1 ». Addendum `:134` : profil Maven `it` uniquement, version 1.21.4, compatibilité Docker 29, build par défaut sans Docker, date 2026-10-25. La cellule d'atténuation de R-02 (`:1308`) dit la même chose. `:865-866` vise les classes unitaires du tableau §11.1 ; elles n'ont pas été modifiées.

### Propositions §12.1 — non appliquées

`docs/PRD_adgendoc_v2_1_Cursor.md:276-284` n'a pas été modifié. Q-02 (`:314`) l'a été.

| Ligne | Écart avec D1–D4 | Correction proposée, non écrite |
|---|---|---|
| `:278` | La ligne 400 cite E1, E3, E4. Elle ne cite pas le 400 d'E2 (chemin qui n'est pas un UUID v4, rien de persisté). Le JSON malformé y est « non persisté », sans dire que sur E3 le champ est `payload`. | Ajouter E2 à la cellule Endpoints pour ce seul cas, et dans l'effet : « E2 : chemin non UUID v4 → non persisté. E3 JSON malformé : `field = payload`, requête non modifiée ; le rejet d'en-tête reste `field = Content-Type`. » |
| `:280` | `404` `REQUEST_NOT_FOUND` / `DOCUMENT_NOT_FOUND` pour E2–E6. D2 réserve `DOCUMENT_NOT_FOUND` à E6. | Scinder : `REQUEST_NOT_FOUND` pour E2, E3, E4, E5 (UUID valide, demande absente) ; `DOCUMENT_NOT_FOUND` pour E6 seul. |
| `:276` et `:284` | E3 y est déjà un 200 avec statut recalculé, et le 422 est limité à E1 et E4. | Aucune correction pour D4. |

### Occurrences restantes, justifiées

Le reviewer (cycle 2) n'en retient aucune comme contradiction active.

| Occurrence | Justification |
|---|---|
| `docs/architecture.md:127` | Ligne d'origine ADR-09, conservée. L'addendum `:134` porte la règle du 2026-10-25. |
| `docs/architecture.md:550` | Branche E1 (`CreateRequest` puis `422`). Pas la réponse d'E3. |
| `ATTESTATION_CONCORDANCE.md:658`, colonne Given | Constat historique « outcome constaté `422` ». Le Then prescrit le 200. |
| `docs/reports/LC1-testcontainers-g2.md:36` | Rapport de lot. Date 26/10/2026, différente de la date machine. Non corrigé. |
| `docs/reports/LD-tests-contrat.md`, `G0-audit-backend.md`, `L3-coherence-fixtures.md` | Rapports antérieurs (écarts alors ouverts, ADR-002 encore proposé). Non réécrits. |

## FILES_CHANGED

- `requirements/API_CONTRACTS.md`
- `requirements/ATTESTATION_CONCORDANCE.md`
- `docs/adr/ADR-002-e3-missing-information-200.md`
- `docs/architecture.md`
- `docs/PRD_adgendoc_v2_1_Cursor.md` (Q-02 seulement ; §12.1 inchangé)
- `docs/reports/evidence/LARB-diff.txt`
- `docs/reports/L0c-ARB.md`

`requirements/N8N_CONTRACTS.md` n'est pas dans le diff.

## TESTS

NOT_RUN. Aucune suite Maven. Preuve du périmètre : `docs/reports/evidence/LARB-diff.txt`.

## RISKS

- Le PRD §12.1 (`:278`, `:280`) décrit encore l'ancien 404 groupé et omet le 400 d'E2. Les contrats passent devant, mais un lecteur du seul PRD se trompe.
- LC1 date la décision Testcontainers du 26/10/2026 ; ce lot la date du 2026-10-25, date de la machine. Les deux textes coexistent.
- E6 reste sans authentification en itération 1. D2 ne change que le code du 404. Risque inchangé.
- Le 400 d'E2 n'est pas un parcours n8n : l'ingress le refuse avant. S'il est atteint quand même, le webhook le classe `500`.

## NEXT_ACTION

Décider si les deux lignes proposées pour le PRD §12.1 (`:278` et `:280`) entrent dans un lot documentaire. Ne pas réécrire les rapports LD, LC1, G0 ou L3.
