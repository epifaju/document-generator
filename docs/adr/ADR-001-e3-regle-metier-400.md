# ADR-001 — E3 : une règle métier violée répond 400

| | |
|---|---|
| Question | Q-01 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

`PATCH /api/v1/requests/{requestId}` a deux lignes 400 et une ligne 200 qui se contredisent sur `REJECTED`.

- `requirements/API_CONTRACTS.md:341` place la rétrogradation `REJECTED` (T10) dans la réponse **200**.
- `requirements/API_CONTRACTS.md:343` place la même règle métier violée en **400** `VALIDATION_ERROR`, statut persisté `REJECTED` (T4 / T6 / T10 / T12b).
- `requirements/API_CONTRACTS.md:342` réserve un autre 400, structurel (`ERR_CHAMP_INCONNU`, `ERR_CHAMP_RESERVE`) : aucune mutation, jamais `REJECTED`.
- `requirements/ATTESTATION_CONCORDANCE.md:578-582` mappe le `PATCH` ainsi : `400` → T4 / T6 / T10 / T12b ; `409` sur les statuts terminaux.
- `requirements/N8N_CONTRACTS.md:170` et `docs/PRD_adgendoc_v2_1_Cursor.md:296` routent un **400 backend persisté `REJECTED`** vers l'outcome webhook `REJECTED`.
- `requirements/N8N_CONTRACTS.md:423` dit au contraire : PATCH **200** et statut `REJECTED` → outcome `REJECTED`.
- `docs/PRD_adgendoc_v2_1_Cursor.md:300` envoie le 400 E3 **non mutant** vers `INVALID_REQUEST`.

Le code suit la ligne 400 métier : `backend/src/main/java/com/adgendoc/api/DocumentRequestController.java:87-90`. L'audit G0 cite le test HTTP `DocumentRequestControllerTest.java:458-475`. Le chemin 200 ne porte pas `REJECTED`.

## Options

1. **Conserver le 400 persisté `REJECTED`.** Aligner ensuite les deux phrases contradictoires (`API_CONTRACTS.md:341`, `N8N_CONTRACTS.md:423`) sur `API_CONTRACTS.md:343`, le pilote `:580` et `N8N_CONTRACTS.md:170`. Le 400 structurel (`API_CONTRACTS.md:342`) reste `INVALID_REQUEST`.
2. **Faire répondre 200** avec `RequestStatusResponse.status = REJECTED`, pour coller à `API_CONTRACTS.md:341` et `N8N_CONTRACTS.md:423`. Cela change le code HTTP d'un endpoint déjà publié et testé.
3. **Ajouter un code ou un corps d'erreur nouveau** pour distinguer les deux 400. Hors contraintes d'itération 1 (pas de nouveau format d'erreur).

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | Édition documentaire ultérieure, après accord : retirer T10 / `REJECTED` de la cellule 200 (`API_CONTRACTS.md:341`) ; réécrire `N8N_CONTRACTS.md:423` pour qu'un PATCH 400 persisté `REJECTED` produise l'outcome `REJECTED`. `N8N_CONTRACTS.md:170` reste la table normative. |
| Code E1–E8 | Aucun changement de chemin, de code HTTP ni de `ErrorResponse`. |
| Tests | Le cas 400 existant demeure. Un contrôle statique du workflow doit exiger l'outcome `REJECTED` pour ce 400 métier, et `INVALID_REQUEST` pour le 400 non mutant. Gate G5. Ce contrôle n'est pas écrit ici. |
| n8n | Si le graphe range aujourd'hui tout 400 E3 en `INVALID_REQUEST` ou `ERROR`, c'est un écart au contrat `N8N_CONTRACTS.md:170`, à corriger dans un lot workflow séparé. L'audit G0 n'a pas exécuté le webhook : le routage réel est **inconnu à l'exécution**. |

## Recommandation

Option 1. Le comportement codé et testé (400, statut persisté `REJECTED`) est celui à figer. La correction porte sur les deux phrases de contrat, pas sur le contrôleur.

Lot : L0c pour le texte des contrats, puis le lot « figer Q-01 » (assertion statique workflow, gate G5) décrit par l'audit G0. Pas de lot de changement HTTP.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
