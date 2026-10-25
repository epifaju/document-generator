# ADR-002 — E3 : champs encore absents répond 200

| | |
|---|---|
| Question | Q-02 |
| Statut | **ACCEPTÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Quand il reste des champs obligatoires absents :

- E1 `POST /api/v1/requests` répond **422** `MISSING_INFORMATION` (`requirements/API_CONTRACTS.md:277`, persistance `:61`).
- E4 `POST .../validate` répond **422** (`requirements/API_CONTRACTS.md:369`).
- E3 `PATCH /api/v1/requests/{requestId}` répond **200** `RequestStatusResponse` avec `status = MISSING_INFORMATION` (T6b) (`requirements/API_CONTRACTS.md:341`).
- Le mapping HTTP (`requirements/API_CONTRACTS.md:459`) limite le 422 à `POST /requests` et `POST /validate`. Le PATCH n'y figure pas.
- Le webhook route ce PATCH 200 vers l'outcome `MISSING_INFORMATION` (`requirements/N8N_CONTRACTS.md:423`, `docs/PRD_adgendoc_v2_1_Cursor.md:295`).
- Le pilote décrit la même boucle T6b sur le PATCH (`requirements/ATTESTATION_CONCORDANCE.md:562`, `:578-579`).

L'audit G0 confirme le code : E3 champs encore absents → 200 `MISSING_INFORMATION` (test cité `DocumentRequestControllerTest.java:405-413`). E1 et E4 restent en 422.

L'asymétrie est déjà écrite. Elle n'est pas un trou d'implémentation.

## Options

1. **Conserver le 200 sur E3** et le 422 sur E1 et E4. Le PATCH est la complétion d'une ressource déjà identifiée : il renvoie l'état recalculé. E1 et E4 signalent un résultat de validation dans `ErrorResponse`.
2. **Passer E3 à 422** lorsque des champs manquent encore. Le webhook qui branche sur PATCH 200 (`N8N_CONTRACTS.md:423`) changerait de code HTTP.
3. **Passer E1 et E4 à 200** pour uniformiser. Cela change deux codes HTTP déjà fixés au §1.4 et au §3.1 / §3.4.

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | Une phrase explicite, dans un lot documentaire ultérieur, peut nommer l'asymétrie comme voulue. Les tableaux actuels la portent déjà (`API_CONTRACTS.md:341` et `:459`). |
| Code E1–E8 | Aucun changement. |
| Tests | Les tests MockMvc cités par l'audit restent la preuve. Aucun nouveau cas HTTP exigé par cette recommandation. |
| n8n | Le routage « PATCH 200 + `MISSING_INFORMATION` → outcome `MISSING_INFORMATION` » reste valide. `confidence` ne choisit pas ce statut (`requirements/API_CONTRACTS.md:111`, `AGENTS.md` §12). |

## Recommandation

Option 1. La complétion conversationnelle (E3) et la validation ponctuelle (E1, E4) gardent des codes HTTP distincts, déjà spécifiés.

Lot : L0c, mention documentaire seulement. Pas de lot de code.

Statut de cet ADR : **ACCEPTÉ** (2026-10-25). Option 1 retenue : E3 renvoie `200` avec `status = MISSING_INFORMATION`. E1 et E4 conservent `422`.
