# L0c-CT2 — discriminant Content-Type (Option A) dans les contrats

| | |
|---|---|
| Rôle | orchestrateur |
| Date | 2026-10-25 |
| Cycle | 1 / 3 |
| Nature | Documentation uniquement. Aucun code, test, fixture, workflow, ni commit. |

## STATUS

Documentation écrite. Cycle 1 / 3. Aucun commit.

Tester (capture) : **PASS**. Reviewer : **PASS**. Security : **PASS**. Contextes neufs, lecture seule.

Les gates d'exécution sont **NOT_RUN**. G7 est la revue sécurité de ce lot : **PASS**.

## FINDINGS

Décision humaine du 2026-10-25, Option A (lot A2), appliquée aux contrats. Le rejet d'en-tête `Content-Type` sur E1, E3 et E8 est un `400` `VALIDATION_ERROR`, `ErrorResponse`, sous-code existant `ERR_PAYLOAD_INVALIDE`. Le JSON malformé d'E1 reste `field = payload`. Aucun code d'erreur nouveau.

La ligne `400` `INVALID_REQUEST` de `requirements/N8N_CONTRACTS.md:174` ne fait qu'exclure le rejet d'en-tête (`fieldErrors[0].field = Content-Type`). Elle ne dit plus que ce rejet est routé en `500`. Ce routage est dans `:180`, le bloc `:182-194` et le paragraphe `:373`.

Entrée unique. Les tests du lot A2 affirment `fieldErrors.length() = 1` pour le rejet d'en-tête :

- E1 : `DocumentRequestControllerTest.java:316` (`e1_text_plain_content_type_is_rejected_without_calling_service`) via `expectE1ContentTypeRejected`, assertions `:864-865`.
- E3 : `DocumentRequestControllerTest.java:586` (`e3_text_plain_content_type_is_rejected_without_calling_service`) via `expectE3ContentTypeRejected`, assertions `:889-890`.

§1.1 (`requirements/API_CONTRACTS.md:24`) et le paragraphe §3.4 (`requirements/N8N_CONTRACTS.md:373`) disent donc « entrée unique, `fieldErrors[0].field = Content-Type` ». Le test E8 aligné au lot A2 (`ExtractionControllerTest.java:217`) affirme `fieldErrors[0].field = Content-Type` et n'affirme pas la longueur. Le handler partagé construit une liste d'un seul élément (`GlobalExceptionHandler.java:134`, `List.of(contentTypeFieldError())`).

Grep du reviewer sur « sans errors[] » / « sans `errors[]` » dans `requirements/`, `docs/adr/` et `docs/` : aucun passage actif ne s'en sert encore comme discriminant d'E1 ou d'E3. Occurrences restantes, autorisées :

- historique, première ligne du 2026-10-25 : `requirements/N8N_CONTRACTS.md:492` ;
- décision d'origine, non réécrite : `docs/adr/ADR-015-content-type-post-patch.md:52` ;
- rapport du lot précédent : `docs/reports/L0c-CT.md:24` et `:31` ;
- critère limité à la séparation du `400` de schéma E8 : `requirements/N8N_CONTRACTS.md:186-187`, `:373`, `:493` ; addendum `docs/adr/ADR-015-content-type-post-patch.md:72`.

`API_CONTRACTS` §4 et `docs/reports/L0c-CT.md` sont inchangés. La précision §3.1 dit « du tableau ci-dessus » (`requirements/API_CONTRACTS.md:281`) pour ne pas s'accrocher à la ligne `500` qui la précède immédiatement.

### QUESTION OUVERTE — contrat E3 muet sur le JSON malformé

Ce lot n'ajoute rien au contrat E3 pour le JSON malformé. `requirements/API_CONTRACTS.md:344` ne décrit que le rejet d'en-tête.

Lecture, sans exécution :

- Le PATCH E3 est un `@RequestBody` (`DocumentRequestController.java:84-86`).
- `HttpMessageNotReadableException` est traité par `handleUnreadable`, qui renvoie une seule entrée `payloadFieldError()` (`GlobalExceptionHandler.java:117-126`). La constante vaut `"payload"` (`:48`, `:258-260`).
- `DocumentRequestControllerTest` ne contient aucun test de JSON malformé sur E3. Le cas `field = payload` prouvé par un test est E1 : `e1_malformed_json_returns_400_without_stack_trace` (`DocumentRequestControllerTest.java:287-293`).

Le handler partagé produirait `field = payload` sur un JSON malformé d'E3. Aucun test E3 ne l'affirme, et §3.3 ne le dit pas.

### Observation — message fixe

Le message de `ERR_PAYLOAD_INVALIDE` est « Corps de requête invalide ou JSON malformé. » (`DocumentRequestControllerTest.java:72`, repris par `contentTypeFieldError()` à `GlobalExceptionHandler.java:263-265`). Il mentionne « JSON malformé » alors que le même texte s'applique au rejet d'en-tête. Le contrat dit que le message est fixe et qu'il ne reprend pas la valeur reçue (`requirements/API_CONTRACTS.md:24`). Ni le code ni le contrat n'ont été modifiés pour cet écart de libellé.

### Écarts connus, non corrigés ici

- Le workflow n8n exporté et les fixtures ne sont pas vérifiés : routage sur `field = Content-Type`, en-tête du webhook.
- `HttpVerticalSliceIT` a été aligné au lot A2 et n'est pas exécuté (PostgreSQL non autorisé dans ce lot).

## FILES_CHANGED

- `requirements/API_CONTRACTS.md` — §1.1, précision §3.1, ligne Content-Type §3.3 et §3.8
- `requirements/N8N_CONTRACTS.md` — §3.2 `:174` et `:180`, bloc `:182-194`, tableau et paragraphe §3.4, historique §8
- `docs/adr/ADR-015-content-type-post-patch.md` — addendum Option A ; décision d'origine inchangée
- `docs/reports/evidence/LCT2-diff.txt` — capture du tester (écrite après le `git status` qu'elle contient ; absente de ce status)
- `docs/reports/L0c-CT2.md` — ce rapport

## TESTS

NOT_RUN. La capture `docs/reports/evidence/LCT2-diff.txt` est une preuve statique (`git status`, `git diff --stat`, `git diff` sur les trois fichiers). Elle n'est pas une exécution de tests.

`git diff --stat` capturé : 3 files changed, 24 insertions, 11 deletions. Seuls les trois fichiers de contrat et d'ADR étaient modifiés au moment de la capture.

## RISKS

- Le workflow exporté peut encore router le rejet d'en-tête avec l'ancien critère, ou traiter tout `ErrorResponse` sans `errors[]` comme un `500`.
- La question ouverte E3 : un lecteur du seul §3.3 ne sait pas que le code partagerait `field = payload` pour un JSON malformé.
- Le libellé « JSON malformé » sur un rejet d'en-tête peut faire confondre les deux cas si l'on lit le message et pas le champ.
- Itération 1 : webhook n8n et téléchargement E6 sans authentification. Inchangés. Hors de ce lot.
- L'addendum et l'historique disent « lot A2 » : c'est le lot qui a décidé l'Option A. Ce lot-ci est L0c-CT2, qui écrit cette décision.

## NEXT_ACTION

Lot ultérieur, hors de celui-ci : vérifier le workflow n8n exporté et les fixtures sur `fieldErrors[0].field = Content-Type` → `500` `INTERNAL_ERROR`, et sur l'en-tête du webhook (qui reste un `400`). Exécuter `HttpVerticalSliceIT` seulement avec PostgreSQL autorisé. Trancher la question ouverte E3 (JSON malformé, `field = payload`) par un test et, si le comportement est retenu, par une ligne de contrat. Aucun commit n'a été créé.
