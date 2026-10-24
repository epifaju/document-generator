# ADR-010 — `contactEmail` et `contactTelephone` restent hors du DOCX

| | |
|---|---|
| Question | Q-10 |
| Statut | **PROPOSÉ** |
| Date | 2026-10-24 |
| Périmètre | Itération 1 — documentation. Aucun code, contrat, template ou test modifié par ce fichier. |
| Hors numérotation | Ne remplace pas le tableau ADR-01…ADR-14 de `docs/architecture.md:117-132`. |

## Contexte

Les deux champs sont des données optionnelles de la demande. Ils ne sont pas des variables du template.

- Définition, motifs et codes : `requirements/ATTESTATION_CONCORDANCE.md:114-115` (`ERR_EMAIL_INVALIDE`, `ERR_TELEPHONE_INVALIDE`).
- Ils figurent dans l'exemple de corps E1 : `requirements/API_CONTRACTS.md:95-96`.
- La table des variables de template (`requirements/ATTESTATION_CONCORDANCE.md:512-527`) ne les contient pas. Cette table est elle-même sous `REQUIRES_BUSINESS_VALIDATION` (`:510`).
- Le code les connaît et les valide (`backend/src/main/java/com/adgendoc/application/ValidationService.java:32-33`, contrôle e-mail `:310-316`).
- Le mapping DOCX ne les injecte pas (`backend/src/main/java/com/adgendoc/application/DocumentGenerationService.java:163-181`). `motif` y est, les contacts n'y sont pas.
- L'audit les refuse aussi dans l'audit log métier (`AuditAdapter.java:30-31` refuse ces clés).
- `docs/PRD_adgendoc_v2_1_Cursor.md:321` et `:331` demandent de confirmer leur nécessité (minimisation).

Les stocker alors qu'ils ne sont pas attestés sur le document est une donnée personnelle supplémentaire dans `document_request.payload`. La nécessité administrative de les garder n'est pas établie par le dépôt. L'inventer serait une donnée administrative créée par le système, ce que le pilote interdit (`ATTESTATION_CONCORDANCE.md:130` et suivants).

Les placer dans le DOCX ajouterait des mentions au document officiel. Le wording officiel n'est pas vérifié (OQ-1, `:664`). Le LLM ne rédige pas cette mention.

## Options

1. **Continuer à les accepter, les valider et les stocker s'ils sont fournis et valides, et les laisser hors du template.** Ils ne sont pas des faits attestés. Leur durée de vie suit la politique de rétention (ADR-011).
2. **Cesser de les accepter.** Une clé aujourd'hui connue deviendrait `ERR_CHAMP_INCONNU`, et `data` renvoyé par E2 changerait. Changement de comportement d'E1 et d'E2.
3. **Les ajouter au template** `attestation_concordance_v1.docx`. Étend le document officiel sans validation humaine du wording, et exige une nouvelle version de template plus une empreinte (règle DOCX du dépôt).

## Conséquences si l'option 1 est retenue

| Zone | Effet |
|---|---|
| Contrats | Aucune édition. Le pilote §4 et le mapping §8 restent la référence. |
| Code | Aucun retrait de champ, aucun jeton `{{contact_email}}` ajouté. |
| Tests | Les tests de validation e-mail / téléphone existants restent. Aucun test DOCX ne doit exiger ces jetons. |
| n8n | L'extraction peut continuer à porter ces clés si le schéma les autorise. Elles ne deviennent pas des phrases du document. `confidence` ne décide pas de les remplir (`API_CONTRACTS.md:111`). |

Si l'arbitrage humain conclut qu'ils sont inutiles, le retrait est un lot de contrat ultérieur (option 2), avec test d'abord. Ce fichier ne l'ouvre pas.

## Recommandation

Option 1 pour l'itération 1. Les contacts restent des champs optionnels de la demande, absents du DOCX. La confirmation de nécessité (minimisation) est l'arbitrage humain : ce fichier ne statue pas qu'ils sont juridiquement requis.

Lot : aucun lot de code tant que l'option 1 tient. Un retrait ou un ajout au template est un autre lot, après accord.

Statut de cet ADR : **PROPOSÉ**. L'arbitrage reste humain.
