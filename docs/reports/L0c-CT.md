# L0c-CT — harmonisation du contrat Content-Type

| | |
|---|---|
| Rôle | orchestrateur |
| Date | 2026-10-25 |
| Cycle | 1 / 3 |
| Nature | Documentation uniquement. Aucun code, test, fixture, ni commit. |

## STATUS

Documentation écrite. Cycle 1 / 3. Aucun commit.

Reviewer : **PASS**. Security : **PASS**. Les deux sont des contextes neufs, lecture seule, limités au diff, aux contrats, à l'ADR et à ce rapport.

Les gates d'exécution restent NOT_RUN. G7 est la revue sécurité de ce lot, pas un scan de dépendances : **PASS**.

## FINDINGS

Décision humaine du 2026-10-25, appliquée.

Pour tout POST ou PATCH à corps JSON, les seules valeurs de `Content-Type` acceptées sont `application/json` et `application/json; charset=UTF-8` (casse du charset indifférente). En-tête absent ou toute autre valeur : `400` `VALIDATION_ERROR`, corps `ErrorResponse`, requête non traitée et non persistée. Sur `POST /api/v1/extraction/validate`, le rejet d'en-tête est cet `ErrorResponse` ; le `400` de schéma reste un `ExtractionValidationResponse`.

Précision du même jour : un rejet d'en-tête (`ErrorResponse`, `VALIDATION_ERROR`, sans `errors[]`) sur E1, E3 ou E8 est un défaut d'orchestration (n8n envoie l'en-tête). Il est routé vers `500` `INTERNAL_ERROR`, sans détail du corps backend. Le `400` de schéma d'E8 reste discriminé par `errors[]`. Aucun outcome ni code nouveau. L'ingress webhook, lui, reste un `400` `VALIDATION_ERROR` : c'est l'en-tête du client du webhook.

Formulation écrite dans `requirements/N8N_CONTRACTS.md` :

- `:174` — le `400` `INVALID_REQUEST` conserve l'ingress invalide, le `400` E1 sans `requestId` et le `400` E3 non persistant (clé inconnue ou réservée), **sauf** le rejet d'en-tête Content-Type, renvoyé vers `500` `INTERNAL_ERROR`.
- `:178` — `EXTRACTION_SCHEMA_INVALID` vise le `400` E8 dont le corps est un `ExtractionValidationResponse` (`errors[]` présent) sans `ERR_DOCUMENT_TYPE_NON_SUPPORTE`.
- `:180` — le `500` `INTERNAL_ERROR` inclut ce rejet d'en-tête sur E1, E3 ou E8, sans détail du corps backend.
- `:182-191` — discrimination : `errors[]` présent → `200` `UNSUPPORTED_DOCUMENT_TYPE` ou `502` `EXTRACTION_SCHEMA_INVALID` ; corps `ErrorResponse` sans `errors[]` → `500` `INTERNAL_ERROR`.
- `:362`, `:364`, `:366` — le tableau §3.4 reprend le même partage, pour ne pas laisser « tout E1 `400` non persistant » et « tout E8 `400` détaillé » contredire §3.2.

Numéros d'ADR déjà pris :

- `docs/architecture.md:119-132` : ADR-01 à ADR-14. ADR-12 (`:130`) est la génération synchrone. ADR-13 (`:131`) est la santé. ADR-14 (`:132`) est l'horloge.
- `docs/adr/` : ADR-001 à ADR-011.

Le suivant est **ADR-015**. Il explique cet écart avec le tableau d'architecture et ne modifie pas `docs/architecture.md`.

`requirements/API_CONTRACTS.md` n'a pas de section d'historique. Aucune n'a été créée. `requirements/N8N_CONTRACTS.md` §8 a une ligne au 2026-10-25.

### Écarts connus, non corrigés dans ce lot

1. **E1 et E3, charset autre que UTF-8.** Le contrat exige désormais le rejet. Le code d'E1 et d'E3 ne le fait pas. Ils ne sont pas conformes tant que le lot A2 n'est pas fait. Constat antérieur : `docs/reports/LA-e8-content-type.md:90`. Aucun code touché.
2. **Webhook §1.1.** `requirements/N8N_CONTRACTS.md:30` est aligné sur les deux valeurs acceptées et sur l'en-tête absent. Le workflow exporté et les fixtures ne sont pas vérifiés pour cette règle. Écart connu, lot ultérieur. Aucune fixture touchée.

## FILES_CHANGED

- `requirements/API_CONTRACTS.md` — §1.1, en-têtes E1/E3/E8, `400` E1, ligne `400` d'en-tête E3, deux `400` E8, tableau §4 et phrase des familles n8n.
- `requirements/N8N_CONTRACTS.md` — §1.1 webhook, §3.2, §3.4, historique §8.
- `docs/adr/ADR-015-content-type-post-patch.md` — créé, statut ACCEPTÉ, décision humaine du 2026-10-25.
- `docs/reports/L0c-CT.md` — ce rapport.
- `docs/reports/evidence/LCT-diff.txt` — diff capturé par le testeur.

Non modifiés : code, tests, fixtures, workflows exportés, `docs/adr/ADR-001-e3-regle-metier-400.md`, `docs/reports/LA-e8-content-type.md`, `docs/architecture.md`.

## TESTS

NOT_RUN

Aucun test, aucune fixture, aucun runtime n8n.

| Gate | Statut |
|---|---|
| G0 inventaire | NOT_RUN |
| G1 build + unitaires | NOT_RUN |
| G2 persistance | NOT_RUN |
| G3 DOCX | NOT_RUN |
| G4 HTTP E2E | NOT_RUN |
| G5 fixtures | NOT_RUN |
| G6 n8n runtime | NOT_RUN |
| G7 revue sécurité de ce lot | PASS |
| G8 CI | NOT_RUN |

## RISKS

- E1/E3 acceptent encore un charset autre que UTF-8 jusqu'au lot A2. Le contrat est en avance sur le code.
- Le workflow exporté et les fixtures peuvent encore n'envoyer ou n'accepter qu'une seule valeur de `Content-Type`. Non vérifié ici.
- Un `400` E1 sans `requestId` qui n'est pas un rejet d'en-tête (enveloppe invalide) reste `400` `INVALID_REQUEST`. Le partage repose sur la cause « rejet d'en-tête », pas sur l'absence seule de `errors[]` pour E1 et E3.

## NEXT_ACTION

Lot A2 : rejeter sur E1 et E3 un charset autre que UTF-8, avec tests. Lot ultérieur séparé : vérifier le workflow exporté et les fixtures contre `N8N_CONTRACTS.md` §1.1. Pas de commit dans ce lot.

## REVUES

`docs/reports/evidence/LCT-diff.txt` a été capturé avant le collage de ces verdicts. Dans ce fichier, la section REVUES figure encore « en attente ». Les hunks des contrats et de l'ADR n'ont pas changé après cette capture.

Reviewer, première ligne :

```
VERDICT: PASS
```

Aucun écart bloquant. Le diff ne contient pas de code, de test ni de fixture.

Security, première ligne :

```
VERDICT: PASS
```

Aucun constat bloquant. Le rejet d'en-tête reste non persisté. Le `500` n8n est sans détail du corps backend. Aucune PII ni secret ajouté dans le diff.
