# ADR-008 — Contrats PROPOSÉ et questions OQ-API-2 à OQ-API-6

| | |
|---|---|
| Question | Q-08 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat ou test modifié par ce fichier. Le bandeau PROPOSÉ n'est pas modifié. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Les deux contrats portent encore le bandeau **PROPOSÉ**.

- `requirements/API_CONTRACTS.md:9` : « à valider par l'architecte et l'humain avant implémentation ».
- `requirements/N8N_CONTRACTS.md:3` et `:474-476` : PROPOSÉ, Phase I.0 ; toute modification après approbation exige une révision.
- L'implémentation E1–E8 existe néanmoins (audit G0). AGENTS.md §22 place ces deux fichiers en source de vérité n°1, devant le code et devant le PRD.
- `docs/PRD_adgendoc_v2_1_Cursor.md:319` demande un point de validation formel avant le lot L1b.
- Les questions encore ouvertes du contrat API sont OQ-API-2 à OQ-API-6 (`requirements/API_CONTRACTS.md:519-523`). OQ-API-1 est déjà arbitrée (`:518`).

Détail de chaque OQ, tel que le dépôt le montre aujourd'hui.

| ID | Texte | Déjà réalisé dans le dépôt |
|---|---|---|
| OQ-API-2 | `201` synchrone ou `202` + polling | `API_CONTRACTS.md:391-393` retient `201`. `docs/architecture.md:130` (ADR-12) dit la même chose. `N8N_CONTRACTS.md:464` met le `202` hors objectifs d'itération 1. |
| OQ-API-3 | Santé : quelle URL | `docs/architecture.md:131` (ADR-13) : `GET /api/v1/health` et `/actuator/health`. L'audit G0 confirme les deux. Corps `{ "status": "UP" \| "DOWN" }` (`API_CONTRACTS.md:417-425`). |
| OQ-API-4 | `data` complet sur E2 | Escalade security. Traité dans ADR-009. |
| OQ-API-5 | Rate limit | « À définir avant mise en production » (`API_CONTRACTS.md:522`). Aucun limiteur dans l'audit G0. |
| OQ-API-6 | Politique de version | Base path `/api/v1` (`API_CONTRACTS.md:6-7`). Pas d'en-tête `API-Version`. |

`confidence` n'est pas une de ces OQ. Elle reste une métadonnée d'audit (`API_CONTRACTS.md:111`). Le `[CONFIRM]` de `AGENTS.md` §12 (pas de seuil) n'est pas levé par ce fichier.

## Options

1. **Tenir les contrats comme source de vérité des lots suivants**, bandeau PROPOSÉ inclus, jusqu'à une ratification humaine séparée. Fermer les OQ par confirmation de l'existant, sans changer les endpoints : `201` synchrone ; les deux URL de santé ; pas de rate limit en itération 1 ; versionnement par le chemin `/api/v1` seulement. OQ-API-4 suit ADR-009.
2. **Bloquer tout lot tant que le bandeau dit PROPOSÉ**, y compris les lots qui ne font que coller au texte déjà codé.
3. **Trancher les OQ en changeant le comportement** (passer E5 en `202`, retirer une URL de santé, ajouter un `429`, ajouter un en-tête de version). Chaque point change ou étend la surface HTTP.

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | Le bandeau reste PROPOSÉ tant que l'humain ne le ratifie pas. Une ratification ne réécrit pas E1–E8. Les phrases « À trancher » de `:519-523` peuvent être annotées « confirmé par l'existant » dans un lot documentaire, après accord. |
| Code E1–E8 | Aucun changement pour OQ-API-2, 3, 5 et 6. Pas de `202`, pas de `429`, pas d'en-tête `API-Version`. |
| Tests | L1b (`openapi.yaml`, tests de contrat) décrit la surface actuelle. Il n'invente pas de résolution au-delà de ce tableau. |
| n8n | Le non-objectif `202` (`N8N_CONTRACTS.md:464`) reste. |

Un rate limit qui répondrait `429` serait un format d'erreur absent de `API_CONTRACTS.md` §2.3 et §4. Il attend la mise en production (lot L6), pas l'itération 1. Le périmètre loopback (`API_CONTRACTS.md:42-45`) est le contrôle actuel.

## Recommandation

Option 1. Les contrats sont opposables pour les lots suivants **dans leur texte actuel**, y compris les contradictions que ADR-001 demande de corriger avant de les recopier. Le bandeau PROPOSÉ signifie que la ratification humaine n'a pas eu lieu. Ce fichier ne la prononce pas.

Sous-recommandations, chacune encore **PROPOSÉ** :

- OQ-API-2 : garder le `201` synchrone.
- OQ-API-3 : garder les deux URL, corps limité à `UP` / `DOWN`.
- OQ-API-4 : voir ADR-009.
- OQ-API-5 : pas de rate limit en itération 1.
- OQ-API-6 : le chemin `/api/v1` est la seule version. Un changement incompatible passe par un chemin majeur nouveau, après ADR accepté.

Lot : L0c pour la ratification humaine. L1b peut décrire la surface codée ; il ne démarre pas un changement d'OQ.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
