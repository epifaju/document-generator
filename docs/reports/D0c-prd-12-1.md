# D0c — §12.1 du PRD aligné sur les contrats

Documentation seulement. Seul fichier de contenu modifié : `docs/PRD_adgendoc_v2_1_Cursor.md`, lignes 278 à 281. Aucun commit. L'index n'a pas été touché (`git diff --cached --name-only` vide ; `git status` montre ` M` sur ce fichier).

## STATUS

Reviewer : `VERDICT: PASS`. [Relecture](d62c4d9c-c0f5-4851-b58f-d4b832f85cff).

Security : `VERDICT: PASS`. [Revue](97e32f0b-7a00-4969-b21c-a641545be1c9).

TESTS : **NOT_RUN**. Capture : `docs/reports/evidence/LD0C-diff.txt`.

## FINDINGS

Le texte écrit est celui approuvé, relu contre `API_CONTRACTS.md:310`, `:346`, `:349`, `:412`, `:460`, `:463-464`.

La ligne 400 cite E2 lorsque le chemin n'est pas un UUID v4, effet non persisté. Elle précise le JSON malformé d'E3 : `field = payload`, requête non modifiée. Le rejet d'en-tête reste `field = Content-Type`.

La ligne 404 unique `E2–E6` est scindée :

- `REQUEST_NOT_FOUND` pour E2, E3, E4, E5 : UUID v4 valide, demande absente (`docs/PRD_adgendoc_v2_1_Cursor.md:280`).
- `DOCUMENT_NOT_FOUND` pour E6 seul : demande inconnue, document inconnu, ou document d'une autre demande (`:281`).

Les lignes 276, 279 et 282 à 287 sont inchangées. E6 reste sans authentification en itération 1. Le 401 et le 403 restent marqués itération 2 (`:279`).

## FILES_CHANGED

- `docs/PRD_adgendoc_v2_1_Cursor.md`
- `docs/reports/evidence/LD0C-diff.txt`
- `docs/reports/D0c-prd-12-1.md`

## TESTS

NOT_RUN.

## RISKS

Aucune règle nouvelle. Le PRD rattrape des décisions déjà écrites dans les contrats. Un lecteur qui s'arrête au PRD voit maintenant le même découpage que `API_CONTRACTS` §3.2, §3.3, §3.6 et §4.

## NEXT_ACTION

Aucun. Le lot est clos si ce rapport est accepté.
