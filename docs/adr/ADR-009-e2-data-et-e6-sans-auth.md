# ADR-009 — E2 renvoie `data` ; E6 sans autorisation en itération 1

| | |
|---|---|
| Question | Q-09 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Deux expositions de données personnelles sont déjà qualifiées de risque assumé, bloquant avant toute production.

- `RequestStatusResponse.data` est toujours présent : données normalisées persistées (`requirements/API_CONTRACTS.md:143`). E2 renvoie ce schéma (`:306`).
- OQ-API-4 pose la question d'un résumé (statut + `missingFields`) et la marque escalade security (`requirements/API_CONTRACTS.md:521`).
- E6 est un téléchargement du DOCX (`requirements/API_CONTRACTS.md:397-409`). Les `401` / `403` sont réservés à l'itération 2 (`:50-53`, `:408`, `:455-456`).
- Le risque assumé est écrit : accès DOCX ouvert tant que l'authentification n'existe pas ; fermeture obligatoire avant production (`API_CONTRACTS.md:50-53`).
- Le webhook n8n n'est pas authentifié en itération 1. Avant production, deux obligations cumulées : jeton de webhook et autorisation E6 (`requirements/N8N_CONTRACTS.md:56-67`).
- L'audit G0 : bind loopback par défaut (`application.yml:36`, Compose sur `127.0.0.1`). E6 répond 200 sans contrôle d'identité. Itération 1 sans JWT : conforme au contrat.
- `docs/PRD_adgendoc_v2_1_Cursor.md:329` répète la fermeture obligatoire avant production.

Retirer `data` de E2 changerait le corps d'une réponse 200 existante. Ajouter un JWT en itération 1 contredit `API_CONTRACTS.md:42-45` et `N8N_CONTRACTS.md` §7 (`:463`).

**Observation liée, non tranchée par Q-09.** `requirements/N8N_CONTRACTS.md:447-455` interdit de relayer le binaire et de le conserver dans les données d'exécution n8n. L'audit G0 indique que le nœud E6 de l'export utilise `responseFormat: text`, ce qui garde le DOCX dans les execution data, et que FinalizeResponse ne renvoie que métadonnées + `downloadPath`. Le webhook n'a pas été exécuté ici. L'écart export / §6 est donc lu, pas rejoué.

## Options

1. **Conserver E2 avec `data` complet et E6 sans authentification** pour l'itération 1, sur loopback. La mise en production reste bloquée tant que le jeton webhook et l'autorisation E6 ne sont pas spécifiés dans un ADR d'itération 2. Aucun JWT ajouté maintenant.
2. **Réduire E2 à un résumé** (statut, `missingFields`, sans `data`) dès maintenant. Corps de réponse incompatible avec `API_CONTRACTS.md:143`.
3. **Ajouter l'authentification en itération 1.** Interdit par le contrat d'itération et par la contrainte « pas d'authentification en it.1 ».

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | E2 et E6 inchangés. OQ-API-4 reste une escalade security, pas une modification silencieuse. |
| Code E1–E8 | Aucun Spring Security, aucun champ retiré de E2. |
| Tests | Les tests E2 et E6 existants restent valides. Un test d'itération 2 devra couvrir `401` / `403` quand le contrat d'itération 2 existera. |
| n8n | Le webhook continue de ne pas renvoyer `data` ni le binaire (`N8N_CONTRACTS.md:50-52`, `:450-455`). Aligner l'export E6 sur l'absence de corps dans les execution data est un lot workflow séparé : il ne change pas le HTTP de E6. |

Le moment de ce lot workflow (avant ou après un gate I-B autorisé) n'est pas fixé ici.

## Recommandation

Option 1. Le risque loopback est celui déjà écrit dans les contrats. Il devient bloquant à la première exposition hors loopback. La réduction de `data` et l'autorisation E6 sont des changements de contrat d'itération 2.

Lot : aucun en itération 1. L6 (ou le lot security pré-production) pour le jeton webhook et l'autorisation E6. Lot n8n séparé pour l'écart `responseFormat` / `N8N_CONTRACTS.md:450-455`, après accord de séquencement.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
