# ADR-004 — `REJECTED` reste terminal en itération 1

| | |
|---|---|
| Question | Q-04 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Une règle métier violée dans `data` persiste la demande en `REJECTED` (`requirements/API_CONTRACTS.md:60`). Ce statut n'a pas de successeur.

- `requirements/ATTESTATION_CONCORDANCE.md:548` et la transition T14 `:572` : `REJECTED` est terminal en itération 1 ; la correction est une nouvelle demande.
- `requirements/API_CONTRACTS.md:345` : un PATCH sur `GENERATED` ou `REJECTED` répond `409` `REQUEST_ALREADY_CLOSED`.
- `requirements/N8N_CONTRACTS.md:424` : un PATCH `409` est réconcilié par E2 ; un statut `REJECTED` devient l'outcome `REJECTED`.
- `RequestStatus.isTerminal()` est vrai pour `REJECTED` et `GENERATED` (`backend/src/main/java/com/adgendoc/domain/RequestStatus.java:36-37`). `assertModifiable` lève la demande close (`DocumentRequest.java:100-103`).
- L'audit G0 cite le test HTTP E3 sur `REJECTED` → 409 (`DocumentRequestControllerTest.java:479-487`).

Conséquence produit, déjà écrite : une date invalide fige la ligne. Le message suivant sans `requestId` crée une seconde ligne avec les mêmes données personnelles (`requirements/N8N_CONTRACTS.md:429-435`, finding `F-I-4`). La rétention de ces lignes est Q-11.

Écart de renvoi dans le PRD : `docs/PRD_adgendoc_v2_1_Cursor.md:206` attribue le caractère terminal de `REJECTED` à Q-06. Le tableau §12.3 l'attribue à Q-04 (`:315`). Q-06 porte l'idempotence (`:317`). Ce fichier suit §12.3 et ne modifie pas le PRD.

## Options

1. **Laisser `REJECTED` terminal** pour l'itération 1. La correction d'une donnée invalide passe par une nouvelle E1. La duplication de lignes est assumée et renvoyée à la politique de rétention (ADR-011).
2. **Autoriser un PATCH correctif depuis `REJECTED`.** Le `409 REQUEST_ALREADY_CLOSED` de `API_CONTRACTS.md:345` ne s'appliquerait plus à ce statut. C'est un changement de code HTTP sur E3.
3. **Ajouter un endpoint de réouverture.** Surface absente de E1–E8. Hors itération 1.

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | Aucune édition obligatoire. Les textes `:345`, `:548` et T14 sont déjà alignés avec le code. |
| Code E1–E8 | Aucun changement de transition ni de 409. |
| Tests | Le test 409 cité reste la preuve. Étendre la matrice statut × endpoint est un lot séparé (PRD L4b), pas une condition de cet ADR. |
| n8n | Le client qui veut corriger envoie un nouveau round 1 (sans `requestId`). Le webhook ne « rouvre » pas la ligne. |

Le coût est réel : deux lignes pour une même personne tant que la purge n'existe pas. Le lever en itération 1 exigerait de modifier le 409, ce que les contraintes de ce lot interdisent sans un accord explicite sur ce changement incompatible.

## Recommandation

Option 1 pour l'itération 1. `GENERATED` reste terminal au même titre (T13, `RequestStatus.java:36-37`).

Lot : aucun lot de code. La douleur produit (donnée figée, seconde ligne) est tranchée ici seulement si l'arbitrage humain confirme l'option 1. Un choix de l'option 2 est un changement de contrat : arrêt, pas d'implémentation dans la foulée.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
