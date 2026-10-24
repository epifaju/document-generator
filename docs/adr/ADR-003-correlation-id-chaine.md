# ADR-003 — `correlationId` : chaîne bornée, UUID à la génération

| | |
|---|---|
| Question | Q-03 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Trois textes décrivent le même champ.

- `requirements/API_CONTRACTS.md:172` type `ErrorResponse.correlationId` en **UUID**.
- `requirements/API_CONTRACTS.md:26` : l'en-tête est optionnel ; s'il est absent, le backend génère un UUID v4.
- `requirements/N8N_CONTRACTS.md:32` accepte en entrée `^[A-Za-z0-9-]{8,64}$` et le décrit comme un sous-ensemble du filtre backend.
- `requirements/N8N_CONTRACTS.md:134` type le `correlationId` de l'enveloppe webhook en string de ce motif, UUID v4 lorsque n8n l'a généré.
- Le filtre backend est `^[A-Za-z0-9-]{1,64}$` (`backend/src/main/java/com/adgendoc/infrastructure/config/CorrelationIdFilter.java:30`). Une valeur hors motif est écartée et remplacée par un UUID (`:52-54`). Elle ne produit pas une erreur.
- Le record Java est un `String` (`backend/src/main/java/com/adgendoc/api/dto/ErrorResponse.java:22`).
- La colonne SQL est `VARCHAR(64)` (`database/migrations/V1__init.sql:15`).
- L'audit G0 cite un test qui renvoie `trace-abc-123` (`DocumentRequestControllerTest.java:692-697`). Cette valeur est légale pour le filtre, illégale pour un type UUID strict, et légale pour le motif n8n (longueur ≥ 8).

Le fil JSON est déjà une chaîne. Changer le mot « UUID » dans le tableau §2.3 ne change pas la forme de `ErrorResponse`.

## Options

1. **Documenter `correlationId` comme string** `^[A-Za-z0-9-]{1,64}$` côté API, en conservant l'UUID v4 comme seule valeur **générée** (absent ou invalide). n8n garde son motif plus strict `^[A-Za-z0-9-]{8,64}$` à l'ingress webhook. Le code du filtre ne change pas.
2. **Resserrer le filtre sur un UUID** pour rendre vrai le type écrit en `API_CONTRACTS.md:172`. Les valeurs aujourd'hui acceptées, dont `trace-abc-123`, seraient remplacées par un UUID généré : la chaîne de corrélation fournie par l'appelant serait coupée.
3. **Resserrer le backend sur le motif n8n `{8,64}`.** Les identifiants de 1 à 7 caractères, acceptés aujourd'hui, seraient régénérés.

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | Lot documentaire ultérieur : remplacer le type « UUID » de `API_CONTRACTS.md:172` par string + motif, et rappeler que l'UUID v4 est la valeur générée (`:26`). Le motif n8n `:32` et `:134` reste inchangé. |
| Code E1–E8 | Aucun changement de filtre, de corps d'erreur ou de code HTTP. Une valeur invalide continue d'être remplacée, pas refusée. |
| Tests | Le test `trace-abc-123` reste valide. Aucun test nouveau exigé tant que le filtre ne change pas. |
| n8n | Une valeur de 8 à 64 caractères du motif commun traverse webhook et backend. Une valeur de 1 à 7 caractères n'entre pas dans n8n ; si elle atteignait le backend directement, le filtre actuel la garderait. Cet écart de borne est assumé : n8n est le sous-ensemble strict (`N8N_CONTRACTS.md:32`). |

## Recommandation

Option 1. Le type du contrat se cale sur le filtre, la colonne et le record. La génération reste un UUID v4. Pas de nouveau format d'erreur.

Lot : L0c, texte du contrat API seulement.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
