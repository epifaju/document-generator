# ADR-011 — Pas de purge tant que les durées ne sont pas écrites

| | |
|---|---|
| Question | Q-11 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat, migration ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Aucun mécanisme de rétention n'existe.

- `docs/PRD_adgendoc_v2_1_Cursor.md:322` : pas de purge (finding `F-I-4`), politique et job rattachés à FR-16.
- `requirements/N8N_CONTRACTS.md:433-435` : une seconde ligne `document_request` peut porter les mêmes données personnelles lorsqu'un message repart sans `requestId`. La politique de rétention est nommée, pas chiffrée.
- L'audit G0 : recherche `purge` / `retention` sous `backend/src/main` : 0 classe. FR-16 absent.
- Vérification de ce tour : recherche `purge`, `retention` et `REQUIRES_BUSINESS` sous `backend/src/main/java` : aucune occurrence.
- Les tables concernées sont `document_request`, `generated_document`, `template_registry`, `audit_log` (`database/migrations/V1__init.sql:4-68`). `generated_document.request_id` est `ON DELETE RESTRICT` (`:25`).
- Le PRD demande un audit append-only (`docs/PRD_adgendoc_v2_1_Cursor.md:332`).
- ADR-004 (PROPOSÉ) laisse `REJECTED` terminal : les lignes rejetées et les doublons F-I-4 s'accumulent.
- ADR-010 (PROPOSÉ) laisse `contactEmail` et `contactTelephone` dans le payload.

Aucune durée légale (demande, DOCX, journal d'audit, exécutions n8n) n'est écrite dans les contrats. En inventer une serait une règle administrative non vérifiée. AGENTS.md §18 impose l'arrêt dans ce cas.

## Options

1. **Pas de job de purge en itération 1.** Règle intérimaire : aucune suppression automatique. Les lignes `REJECTED`, les doublons et les DOCX restent jusqu'à une politique écrite (durées par catégorie, sort de `audit_log`, sort des exécutions n8n). FR-16 reste non implémenté.
2. **Ajouter maintenant un job avec une durée choisie ici** (par exemple un nombre de jours). Durée sans source.
3. **Effacer les données personnelles dès le passage à `REJECTED`.** Suppression non spécifiée, contraire à la persistance d'audit du `400` métier (`requirements/API_CONTRACTS.md:60`).

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | Aucune durée ajoutée. Une politique future est un texte humain, puis un ADR accepté, puis une migration nouvelle si le schéma doit porter une date de fin de vie. `V1` n'est pas éditée. |
| Code | Aucune classe de purge. `ON DELETE RESTRICT` reste. |
| Tests | Aucun test de rétention tant qu'il n'y a pas de règle. |
| n8n | Les execution data ne sont pas purgées par le backend. Leur durée est un paramètre d'exploitation n8n, à fixer dans la même politique, pas dans un nœud métier. |

Le job, quand il existera, ne peut pas traiter `audit_log` comme les demandes tant que la règle « append-only » tient. Un effacement d'audit est une décision explicite de cette politique, pas un effet de bord du job.

## Recommandation

Option 1. FR-16 attend les durées. Ce fichier ne propose aucun nombre de jours.

Lot : L6, et seulement après une politique écrite. Pas de lot d'itération 1.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
