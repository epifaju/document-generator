# ADR-006 — Un seul DOCX par demande ; pas de clé d'idempotence E1

| | |
|---|---|
| Question | Q-06 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat, migration ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Deux trous distincts sont regroupés dans Q-06 (`docs/PRD_adgendoc_v2_1_Cursor.md:317`).

**E1.** Chaque création tire un UUID nouveau (`backend/src/main/java/com/adgendoc/domain/DocumentRequest.java:45`). Il n'existe pas de clé d'idempotence. Le PRD v2.1 retire explicitement `Idempotency-Key` des propositions (`docs/PRD_adgendoc_v2_1_Cursor.md:4`). `requirements/N8N_CONTRACTS.md:387` interdit le retry n8n sur timeout E1 : l'issue est inconnue. La réconciliation reste le `correlationId` (`:405-407`, finding `F-I-1`). Un `5xx` après commit peut déjà laisser une ligne (`:387`, caveat audit).

**E5.** L'unicité SQL est `UNIQUE (request_id, sha256)` (`database/migrations/V1__init.sql:34`). Deux DOCX de contenu différent peuvent coexister pour la même demande. Après un succès, le statut `GENERATED` bloque un second appel séquentiel : E5 n'est autorisé que depuis `VALIDATED` (`requirements/API_CONTRACTS.md:386-388`, `DocumentRequest.java:112-115`). Le trou est la concurrence : deux appels qui lisent encore `VALIDATED`. `N8N_CONTRACTS.md:410-411` traite un `409` alors que le statut est déjà `GENERATED` comme un succès (le `201` a été perdu).

`V1__init.sql` est une migration déjà écrite. Toute contrainte supplémentaire est une migration nouvelle (AGENTS.md §10). Aucune base n'a été interrogée ici : la présence de doublons réels est **inconnue**.

## Options

1. **Deux mesures séparées.** E1 : pas de clé d'idempotence en itération 1 ; le timeout reste sans retry ; la réconciliation reste le `correlationId`. E5 : au plus une ligne `generated_document` par `request_id`, dans un lot ultérieur (garde applicative et, si la contrainte l'exige, migration `V3`). Le second appel, une fois le premier commis en `GENERATED`, répond `409` `INVALID_STATUS` comme `API_CONTRACTS.md:388` le prévoit déjà pour un statut autre que `VALIDATED`. Pas de second `documentId`. Pas de fichier orphelin. Pas de code d'erreur nouveau.
2. **Ajouter `Idempotency-Key` sur E1 maintenant.** En-tête absent des contrats. Le PRD v2.1 l'a retiré. Reporté.
3. **Laisser les deux trous.** Deux documents officiels restent possibles si les empreintes diffèrent.

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | E1 et E5 gardent leurs codes HTTP. Le lot E5 doit mapper le perdant d'une course sur le `409` **après relecture** : si le gagnant est `GENERATED`, le message cite ce statut (`API_CONTRACTS.md:388`). Répondre 409 pendant que le statut est encore `VALIDATED` contredirait la condition du contrat. |
| Code | Lot séparé, test d'abord : deux générations concurrentes ou un second insert d'empreinte différente → une seule ligne, second appel `409`, aucun fichier laissé. Gate G1, puis G2 seulement si une base est autorisée. `V1` n'est pas éditée. |
| Tests | Le test d'acceptation est celui de l'audit G0 (tâche 4). Il n'est pas écrit ici. |
| n8n | Inchangé : pas de retry E1 sur timeout ; un `409` E5 avec statut `GENERATED` suit déjà `N8N_CONTRACTS.md:410-411`. |

La migration `V3` n'est lancée qu'après accord, et seulement si aucune ligne existante ne la ferait échouer. Cet accord n'est pas donné par le présent ADR.

## Recommandation

Option 1. La clé d'idempotence E1 attend une itération ultérieure et un amendement de contrat. L'unicité du document est un lot d'implémentation qui conserve les codes HTTP existants.

Lot : tâche 4 de l'audit G0 (garde E5, gate G1 puis G2). Pas de lot E1 en itération 1.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
