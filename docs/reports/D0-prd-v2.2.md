# D0 — PRD v2.2 (documentation)

| | |
|---|---|
| Rôle | orchestrator |
| Fichier | `docs/PRD_adgendoc_v2_1_Cursor.md` (nom inchangé ; titre v2.2) |
| Date | 2026-10-25 |
| Commit de départ | `4a5bfe7` « maj de PRD », parent `c313cbb` |
| Tests | NOT_RUN |

## STATUS

Documentation seule. La régression Q-01 est remise. Le §8 dit que la matrice est dans `RequestStatus` et que `RequestStateMachine` est une proposition. Le nom de fichier du PRD n'a pas changé. Aucun contrat, aucun ADR, aucun code, aucun test, aucun commit.

## FINDINGS

### Classement du diff `c313cbb..4a5bfe7` (`docs/reports/evidence/D0-diff-commit.txt`)

L'arbre de travail était vide (`D0-diff-docs.txt`, `D0-status.txt`). Le log est dans `D0-log.txt`. Classement : [Classement D0](3944ea8f-cabb-4ec8-8b88-e7f29f0322d3).

**A — changements voulus, déjà dans le commit `4a5bfe7`**

| # | Présent | Ligne actuelle |
|---|---|---|
| 1. §12.1, 400 de E8 = `ExtractionValidationResponse` | Oui | `docs/PRD_adgendoc_v2_1_Cursor.md:278` |
| 2. FR-06, motif `{{…}}` à confirmer | Oui | `:146` |
| 3. `mvn verify` (wrapper absent) | Oui | `:166`, `:352`, `:384` |
| 4. §10 : Boot 3.3.13, Java 17 / 21 `[DÉCISION]`, POI 5.2.5 | Oui | `:233-234` |
| 5. G1 : Failsafe pour les `*IT` | Oui | `:352` |
| 6. `RequestStateMachine` = proposition | Était seulement dans le changelog `:4` | Corps §8 complété dans ce lot, `:207` |

**B — régression remise**

`4a5bfe7` avait rouvert Q-01. Ligne restaurée (`:313`) : **RÉSOLUE**, 2026-10-24, E3 `REJECTED` = HTTP 400, renvoi à `docs/adr/ADR-001-e3-regle-metier-400.md`. Source : `docs/reports/L0c-Q01.md:74`, ADR-001 statut **ACCEPTÉ** (`:6`). Aucune autre régression B.

**C — autre, non modifié dans ce lot**

Ajouts du commit : `docs/packs/cursor_agents_adgendoc.md`, `docs/packs/cursor_rules_adgendoc.md`, `docs/reports/G0-audit-backend.md`, `docs/reports/L0c-Q01.md`, preuves `docs/reports/evidence/L3-*.txt`.

### §8

Phrase remplacée, diagramme Mermaid et le reste du §8 inchangés. Le renvoi du caractère terminal de `REJECTED` pointe Q-04 (`:207`), plus Q-06.

### §12.1 et Q-01

Cohérent avec `requirements/API_CONTRACTS.md` §3.3 et avec ADR-001, sans modification du contrat :

- 200 d'E3 : statuts recalculés `VALIDATED`, `MISSING_INFORMATION`, reprise de `FAILED` (`PRD:276` ; contrat `:341`). `REJECTED` n'est pas un 200.
- 400 d'E3 : règle métier violée → `REJECTED` persisté (`PRD:278` ; contrat `:343`). Clé inconnue ou réservée → aucune mutation, jamais `REJECTED` (`PRD:278` ; contrat `:342`).
- 409 d'E3 : `REQUEST_ALREADY_CLOSED` si `GENERATED` ou `REJECTED` (`PRD:282` ; contrat `:345`).
- 400 d'E8 : corps `ExtractionValidationResponse` (`PRD:278` ; contrat `:440`).

### Références au nom `PRD_adgendoc_v2`

Recensées avant les écritures de ce lot. Le fichier réel est `docs/PRD_adgendoc_v2_1_Cursor.md`. Aucune occurrence dans `prompts/`. `AGENTS.md` §19 n'en contient pas. §22 (`AGENTS.md:659`) porte déjà le nom réel : non modifié. `.cursor/rules/00-project-context.mdc:16` et `.cursor/agents/backend-developer.md:6` aussi. Les ADR, packs et rapports citent le nom réel : non modifiés (les ADR sont hors portée).

Seule référence fausse : le prompt §15.3 citait `PRD_adgendoc_v2_2_Cursor.md`, fichier absent. Corrigé vers `PRD_adgendoc_v2_1_Cursor.md` (`:392`).

## FILES_CHANGED

- `docs/PRD_adgendoc_v2_1_Cursor.md` — Q-01, §8, prompt §15.3.
- `docs/reports/evidence/D0-status.txt`, `D0-diff-docs.txt`, `D0-log.txt`, `D0-diff-commit.txt` — captures (le diff de travail initial est vide ; le diff de commit est celui de `4a5bfe7`).
- `docs/reports/D0-prd-v2.2.md` — ce rapport.
- `docs/reports/evidence/D0-diff-final.txt` — diff de ce lot, après capture tester.

## TESTS

NOT_RUN. Aucun Maven, Docker, base, réseau, n8n ni Ollama.

## RISKS

Le changelog (`:4`) décrit le changement 6 ; le corps §8 le précise maintenant. Les preuves L3 et le rapport G0 citent encore d'anciens numéros de ligne du PRD : ce sont des archives, pas le PRD. G1 à G8 restent NOT_RUN.

## NEXT_ACTION

Revue close. Aucun commit.

## Revues

Texte recopié tel quel. [Revue D0](320a1d2d-045f-4872-8424-4813e24df28d).

VERDICT: PASS

Relecture indépendante du lot D0 (documentation PRD uniquement). Verdict identique ci-dessus.

## Portée du diff final

`docs/reports/evidence/D0-diff-final.txt` modifie **un seul fichier** (`docs/PRD_adgendoc_v2_1_Cursor.md`) en **trois hunks**, conformes à la portée annoncée :

| # | Thème | Lignes du diff |
|---|---|---|
| 1 | §8 — phrase « Règle » (`RequestStatus`, `RequestStateMachine` en proposition, renvoi Q-04) | l.9–10 |
| 2 | §12.3 — Q-01 **RÉSOLUE**, renvoi ADR-001 | l.18–19 |
| 3 | §15.3 — nom de fichier corrigé vers `PRD_adgendoc_v2_1_Cursor.md` | l.27–28 |

Aucun autre fichier, aucun contrat, ADR, code, test ni section `AGENTS.md` dans ce diff.

## Q-01 et cohérence ADR / L0c

- `docs/PRD_adgendoc_v2_1_Cursor.md:313` — Q-01 **RÉSOLUE**, date 2026-10-24, `REJECTED` via E3 = HTTP `400` (`ErrorResponse`, `VALIDATION_ERROR`, statut `REJECTED` persisté), renvoi `docs/adr/ADR-001-e3-regle-metier-400.md`.
- `docs/adr/ADR-001-e3-regle-metier-400.md:6` — statut **ACCEPTÉ**, option 1 (400 persisté `REJECTED`).
- `docs/reports/L0c-Q01.md:74` — même formulation et renvoi ADR-001.

Alignement démontré entre les trois sources.

## §12.1 vs `API_CONTRACTS.md`

Comparaison effectuée sur l’état actuel du PRD (hors diff D0, déjà présent avant ce lot) :

| Point vérifié | PRD | Contrat | Résultat |
|---|---|---|---|
| 200 E3 sans `REJECTED` | `:276` — recalcul `VALIDATED`, `MISSING_INFORMATION`, reprise `FAILED` | `:341` — pas de `REJECTED` dans la cellule 200 | Cohérent |
| 400 E3 règle métier → `REJECTED` persisté | `:278` | `:343` | Cohérent |
| 400 E3 clé inconnue/réservée → aucune mutation | `:278` (queue E3) | `:342` | Cohérent |
| 400 E8 → `ExtractionValidationResponse` | `:278` (note E8) | `:440` | Cohérent |
| 409 E3 `REQUEST_ALREADY_CLOSED` | `:282` | `:345` | Cohérent |

Aucun écart démontré entre §12.1 et les sections citées du contrat API.

## §8

- Le diagramme Mermaid (`:195-203`) n’apparaît **pas** dans `D0-diff-final.txt` — conforme.
- `:207` — renvoi terminal de `REJECTED` vers **Q-04** (plus Q-06) ; `RequestStateMachine` marqué proposition `[PROPOSÉ]`, matrice dans `RequestStatus`.

## Rapport et tests

- `docs/reports/D0-prd-v2.2.md:9` — `Tests | NOT_RUN`
- `docs/reports/D0-prd-v2.2.md:68` — « NOT_RUN. Aucun Maven, Docker, base, réseau, n8n ni Ollama. »

Le rapport ne prétend pas qu’un test a été exécuté.

## Constats

1. `docs/reports/D0-prd-v2.2.md:17-30` — **info** : le rapport recense aussi des changements du commit `4a5bfe7` (§12.1 E8, FR-06, §10, etc.) hors diff D0 ; traçabilité attendue, non bloquant pour la portée du lot.

2. `docs/PRD_adgendoc_v2_1_Cursor.md:195-203` — **info** : le diagramme Mermaid conserve `VALIDATED --> REJECTED: T10` ; hors diff D0, cohérent avec ADR-001 (transition d’état via 400, pas via 200 HTTP).

3. `docs/reports/D0-prd-v2.2.md:68` — **info** : gates G1–G8 = NOT_RUN ; attendu pour un lot documentation seule.

## Gates (revue)

| Gate | Statut |
|---|---|
| Portée diff final | PASS |
| Q-01 / ADR-001 / L0c | PASS |
| §12.1 vs contrat API | PASS |
| §8 (phrase Règle, Q-04, pas de Mermaid dans diff) | PASS |
| Rapport honnête (TESTS) | PASS |
| Exécution Maven/tests | NOT_RUN (attendu) |

**Conclusion :** le lot D0 respecte sa portée, restaure correctement Q-01, ajuste §8 et §15.3 comme demandé, et le PRD §12.1 est aligné avec `API_CONTRACTS.md` §3.3 et §3.8 sur les points vérifiés. Aucun défaut bloquant démontré.
