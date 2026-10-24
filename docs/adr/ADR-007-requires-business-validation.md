# ADR-007 — `REQUIRES_BUSINESS_VALIDATION` n'est pas un statut

| | |
|---|---|
| Question | Q-07 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Le nom `REQUIRES_BUSINESS_VALIDATION` apparaît comme marqueur de questions ouvertes, pas comme état de la machine.

- L'enum de statut est fermée : `DRAFT`, `MISSING_INFORMATION`, `VALIDATED`, `REJECTED`, `GENERATED`, `FAILED` (`requirements/API_CONTRACTS.md:35-36`, `requirements/ATTESTATION_CONCORDANCE.md:543-550`, `RequestStatus.java:11-16`, `database/migrations/V1__init.sql:11-12`).
- Le persona « responsable de validation » est hors contrats d'itération 1 (`docs/PRD_adgendoc_v2_1_Cursor.md:60` et `:86`).
- `docs/PRD_adgendoc_v2_1_Cursor.md:306` et `:318` : aucun endpoint pour ce persona.
- Dans le pilote, la même expression marque des points non tranchés : le champ `motif` (`requirements/ATTESTATION_CONCORDANCE.md:116`) et la liste des variables de template (`:510`). Les questions OQ-1, OQ-6 et OQ-8 (`:664`, `:669`, `:671`) restent `REQUIRES_BUSINESS_VALIDATION`.
- L'audit G0 : le libellé est absent de `RequestStatus` et des contrôleurs.
- Recherche `REQUIRES_BUSINESS` sous `backend/src/main/java` : aucune occurrence.

Le LLM ne crée pas cet état. `confidence` reste une métadonnée d'audit (`requirements/API_CONTRACTS.md:111`, `:200` ; `AGENTS.md` §12 `[CONFIRM]`). Un seuil de confiance qui déclencherait une revue humaine est la question OQ-5 du pilote (`ATTESTATION_CONCORDANCE.md:668`) et exigerait un changement de contrat.

L'itération 1 n'a pas d'authentification (`requirements/API_CONTRACTS.md:42-45`). Un endpoint réservé à un valideur humain serait un mécanisme d'autorisation.

## Options

1. **Ne pas introduire le statut ni d'endpoint.** Le marqueur reste, dans le pilote, le nom des questions de wording encore ouvertes (OQ-1, OQ-6, OQ-8). La machine à états ne change pas. Aucun rôle n'est ajouté.
2. **Ajouter le statut et un endpoint de validation humaine** en itération 1. Nouveau statut, hors enum, et authentification hors périmètre.
3. **Effacer le marqueur du pilote maintenant.** Cela édite un contrat et masque des questions que personne n'a fermées.

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | Aucune édition dans ce lot. Une reformulation du marqueur n'intervient qu'après fermeture humaine d'OQ-1 et d'OQ-6. |
| Code E1–E8 | Aucun statut, aucun endpoint, aucun filtre d'identité. |
| Tests | Aucun scénario nouveau. |
| n8n | Le workflow ne route pas vers une file de validation humaine. Les outcomes restent ceux de `N8N_CONTRACTS.md` §3.2. |

## Recommandation

Option 1. `REQUIRES_BUSINESS_VALIDATION` n'entre pas dans l'enum et n'a pas d'endpoint en itération 1. Les questions de wording officiel restent ouvertes : ce fichier ne les ferme pas.

Lot : aucun lot de code. L0c peut seulement renvoyer le PRD vers cette formulation, après accord sur le texte.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
