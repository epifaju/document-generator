# ADR-015 — Content-Type des POST et PATCH à corps JSON

| | |
|---|---|
| Question | Harmonisation Content-Type (lot L0c-CT) |
| Statut | **ACCEPTÉ** |
| Date | 2026-10-25 |
| Périmètre | Itération 1 — documentation. Aucun code, test ou fixture modifié par ce lot. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. Ce fichier n'ajoute pas de ligne à ce tableau. |

## Numérotation

Deux listes existent. Elles partagent les entiers, elles ne partagent pas les sujets.

Entiers déjà utilisés dans `docs/architecture.md:119-132` :

| Entier | Emplacement | Sujet |
|---|---|---|
| 01 | `:119` | Langage / JDK |
| 02 | `:120` | Framework |
| 03 | `:121` | Base de données |
| 04 | `:122` | Migrations |
| 05 | `:123` | Génération DOCX |
| 06 | `:124` | Architecture hexagonale |
| 07 | `:125` | Sérialisation |
| 08 | `:126` | Validation d'entrée |
| 09 | `:127` | Tests |
| 10 | `:128` | Schéma d'extraction |
| 11 | `:129` | Stockage |
| 12 | `:130` | Génération synchrone, `201` |
| 13 | `:131` | Santé du service |
| 14 | `:132` | Horloge |

Entiers déjà utilisés comme fichiers dans `docs/adr/` : 001, 002, 003, 004, 005, 006, 007, 008, 009, 010, 011. Chaque fichier de cette série indique qu'il ne remplace pas le tableau d'architecture.

Le prochain entier libre dans les deux listes est **15**. ADR-013 et ADR-014 sont déjà le tableau d'architecture (santé, horloge). Cet ADR est donc **ADR-015**. Il documente une décision de contrat. Il ne modifie pas `docs/architecture.md`.

## Contexte

`requirements/API_CONTRACTS.md` §1.1 n'acceptait qu'une valeur, `application/json; charset=UTF-8`, et limitait la règle aux POST. E3 est un PATCH. Les en-têtes de §3.1, §3.3 et §3.8 citaient `application/json` seul. Le `400` d'E8 était entièrement un `ExtractionValidationResponse`. Le tableau §4 mêlait ce `400` de schéma au `VALIDATION_ERROR`.

`requirements/N8N_CONTRACTS.md` §1.1 (webhook) mirrorait l'ancienne valeur unique. Le §3.2 envoyait tout `400` E8 sans `ERR_DOCUMENT_TYPE_NON_SUPPORTE` vers `502` `EXTRACTION_SCHEMA_INVALID`, y compris un rejet d'en-tête au corps `ErrorResponse`.

## Décision

Décision humaine, 2026-10-25.

Pour tout POST ou PATCH à corps JSON, les seules valeurs de `Content-Type` acceptées sont `application/json` et `application/json; charset=UTF-8` (casse du charset indifférente). En-tête absent ou toute autre valeur : `400` `VALIDATION_ERROR`, corps `ErrorResponse`, requête non traitée et non persistée.

Exception de forme : sur `POST /api/v1/extraction/validate`, les `400` de schéma (JSON malformé, clé inconnue, type, enum, `documentType`, date) restent des `ExtractionValidationResponse`. Seul le rejet de l'en-tête est un `ErrorResponse`.

Précision du même jour, routage n8n : un rejet d'en-tête (`ErrorResponse`, `VALIDATION_ERROR`, sans `errors[]`) sur E1, E3 ou E8 vient d'un défaut d'orchestration, parce que n8n envoie l'en-tête. n8n le route vers `500` `INTERNAL_ERROR`, comme les autres bugs d'orchestration, sans détail du corps backend. Il ne le route pas vers `400` `INVALID_REQUEST`. Le `400` de schéma d'E8 reste discriminé par `errors[]` : `ERR_DOCUMENT_TYPE_NON_SUPPORTE` → `200` `UNSUPPORTED_DOCUMENT_TYPE` ; tout autre contenu de `errors[]` → `502` `EXTRACTION_SCHEMA_INVALID`. Aucun outcome ni code nouveau.

Le rejet d'en-tête à l'ingress du webhook reste un `400` `VALIDATION_ERROR` : c'est l'en-tête du client du webhook.

## Conséquences

| Zone | Effet |
|---|---|
| `requirements/API_CONTRACTS.md` | §1.1, en-têtes E1/E3/E8, `400` E1 et E3, deux `400` E8, tableau §4. Pas de section d'historique dans ce contrat : elle n'existait pas. |
| `requirements/N8N_CONTRACTS.md` | §1.1 webhook, §3.2, §3.4, ligne d'historique §8 du 2026-10-25. |
| Code E1/E3 | Aucun code modifié. E1 et E3 ne rejettent pas un charset autre que UTF-8. Écart connu jusqu'au lot A2. Constat antérieur : `docs/reports/LA-e8-content-type.md:90`. |
| Workflow exporté et fixtures | Non vérifiés pour la règle du webhook §1.1. Écart connu, lot ultérieur. Ce lot ne les modifie pas. |
| `docs/architecture.md` | Inchangé. ADR-12 du tableau reste la génération synchrone. |

## Décision retenue

Statut de cet ADR : **ACCEPTÉ**.
