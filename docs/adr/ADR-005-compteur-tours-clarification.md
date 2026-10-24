# ADR-005 — Le plafond de 5 tours reste dans n8n

| | |
|---|---|
| Question | Q-05 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

`MAX_CLARIFICATION_ROUNDS = 5` est une borne d'orchestration.

- `requirements/N8N_CONTRACTS.md:398` : 5 webhooks par `requestId`, round 1 inclus ; au 5ᵉ round, outcome `CLARIFICATION_LIMIT_REACHED` (200).
- `requirements/N8N_CONTRACTS.md:425` : compteur interne n8n.
- `requirements/N8N_CONTRACTS.md:506` (critère d'acceptation) prévoit le cas **sans compteur persistant**.
- `docs/PRD_adgendoc_v2_1_Cursor.md:315` propose pour l'itération 2 un champ additif `clarificationRounds` dans `RequestStatusResponse`.
- L'audit G0 : aucun champ `clarificationRounds` dans les DTO ; le 5 est dans le workflow (`getWorkflowStaticData`). Aucun compteur backend.

`confidence` ne participe pas à ce plafond. C'est une métadonnée d'audit (`requirements/API_CONTRACTS.md:111` et `:200`). `AGENTS.md` §12 (`[CONFIRM]`) : l'ambiguïté se traite par `missingFields`, le schéma et la garde D-1, pas par un seuil de confiance. Introduire un seuil exigerait un changement de contrat.

Limite connue : le compteur vit dans les static data n8n, effectives pour le webhook de production (audit G0). Il n'est pas dans PostgreSQL. Une exécution sans `requestId` ouvre une nouvelle demande (ADR-004) et un nouveau compte. La durabilité des static data après redémarrage n'a pas été prouvée dans cet exercice : **inconnue à l'exécution**.

## Options

1. **Laisser le compteur dans n8n pour l'itération 1.** Pas de champ nouveau dans `RequestStatusResponse`. Pas de code d'erreur nouveau. Le backend ne refuse pas un 6ᵉ appel direct à E3.
2. **Ajouter `clarificationRounds` dès l'itération 1.** Champ JSON additif. Le PRD le place en itération 2. Les tests qui comparent le corps exact peuvent casser.
3. **Faire refuser le backend au-delà de 5** avec un code métier nouveau. Nouveau format d'erreur : hors itération 1.

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | `N8N_CONTRACTS.md` §4 et §5 restent la borne. Aucune clé ajoutée à E2. |
| Code E1–E8 | Aucun compteur, aucun statut nouveau. Un client qui appelle E3 en direct n'est pas plafonné : la borne est celle du webhook. |
| Tests | Aucun test backend nouveau. Une fixture qui atteint le 5ᵉ tour relève du corpus n8n (gates G5 / G6), lot séparé. |
| n8n | Le workflow continue de porter le 5. L'outcome `CLARIFICATION_LIMIT_REACHED` reste un 200 webhook (`N8N_CONTRACTS.md:173`). |

L'option 2 peut revenir en itération 2, comme champ additif, après amendement du contrat. Elle ne devient pas une règle métier évaluée par le LLM.

## Recommandation

Option 1 pour l'itération 1. Le plafond reste une borne d'orchestration, pas une validation déterministe.

Lot : aucun en itération 1. Itération 2 / L6 si le champ additif est alors spécifié.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
