# L1 — Synchroniser `i1c_05_round2_incomplete.json` avec le validateur

| | |
|---|---|
| Rôle | n8n-developer |
| Exigences | FR-12 (couverture I-C), gate G5 pour ce seul écart |
| ADR | aucun (ADR-002 reste PROPOSÉ, non modifié) |
| Date | 2026-10-24 |
| Cycle | 2 (correction 1/3) |
| Décision humaine | Option A : stub E3 en `success` / `200`, `caseNumber` 11, puis enregistrement. Cycle 2 : accord sur la réécriture du seul message du second événement. |

## STATUS

Cycle 2. Correctif appliqué : le second événement d'I1C-05 ne porte plus qu'une date de naissance. Le validateur statique et ses auto-tests restent verts (`PASSED=12 FAILED=0`, `SELFTEST_PASSED=21 SELFTEST_FAILED=0`). Revue fraîche de ce cycle non faite. G5 reste `NOT_RUN`.

## FINDINGS

- Avant le lot, `tests/n8n/validate_i1_fixtures.ps1` rejetait le fichier : `FAIL unexpected fixture: i1c_05_round2_incomplete.json`. Les 10 fixtures enregistrées passaient, ainsi que 19 auto-tests. `PASSED=11 FAILED=1`.
- L'issue webhook de la fixture était déjà conforme : `200` `MISSING_INFORMATION`, `errorCode` null, `missingFields=[lieuNaissance]`, chemin `OLLAMA, E8, E1, E2, OLLAMA, E8, E3`, aucun E4/E5/E6 (`requirements/N8N_CONTRACTS.md:169`, `:423`).
- Le stub E3 était `mode: error`, `httpStatus: 422`. Les tableaux applicables disent 200 pour un PATCH encore incomplet (T6b) : `requirements/API_CONTRACTS.md:341` et `:459`, `requirements/N8N_CONTRACTS.md:169` et `:423`, `requirements/ATTESTATION_CONCORDANCE.md:578-579`. E1 reste un `422`, ce qui est le cas création (`requirements/API_CONTRACTS.md:277`).
- Phrase non arbitrée, laissée telle quelle : `requirements/ATTESTATION_CONCORDANCE.md:402-404` parle d'un HTTP 422 après recalcul fusionné, sans nommer l'endpoint. ADR-002 (PROPOSÉ) recommande le 200. Ce lot ne modifie ni le pilote ni l'ADR.
- `caseNumber` 5 entrait en collision avec I1D-05 (`Test-Uniqueness`, numéros globaux uniques). Valeur retenue : 11.
- Les `missingFields` du premier tour (`[dateNaissance, lieuNaissance]`) restent sur la première extraction, E1 et E2. La liste finale `[lieuNaissance]` est exigée sur la seconde extraction et sur E3. Même idée que l'exception E2 de I1C-02, sans l'élargir aux autres fixtures.

### Cycle 2 — stimulus du delta partiel

Revue (majeure) : la description disait qu'un seul des deux champs manquants était fourni, alors que le message du tour 2 contenait une date et un lieu. I1C-02 l.25 et l.52-54 lit déjà « née le 01/01/1980 à Ville-Fictive » comme `dateNaissance` et `lieuNaissance`. L'extraction simulée d'I1C-05 (l.89-92) ne contient que `dateNaissance`.

Recherche en lecture seule avant écriture. La phrase « Née le 01/01/1980. » n'apparaissait dans aucun fichier du dépôt. Comparaison message / extraction réussie sur les 11 fixtures :

- I1C-01, I1C-02 (événement 2), I1C-03, I1C-05 (événement 1), I1D-06, I1D-10 : chaque valeur d'attestation écrite dans le message est présente dans le `data` OLLAMA apparié.
- I1C-04, I1D-05, I1D-07, I1D-08 : pas d'extraction réussie (`stubs` vides, ou corps OLLAMA `null`). I1D-07 l.18 et I1D-08 l.18 contiennent une date et un lieu, mais l'échec survient avant toute extraction. Ce n'est pas le défaut du delta partiel.
- I1D-09 l.18 contient, en plus des champs d'attestation extraits (l.33-40), « Passeport fictif FAKE-PP-0001 » et « fictif@example.invalid ». Ces deux segments sont des marqueurs de `securityProbe` (l.123-124), absents de `data`. Constat pour L3. Non corrigé ici.

Lignes modifiées dans `tests/n8n/fixtures/i1c_05_round2_incomplete.json` :

- l.8, avant : `Le client fournit seulement 1 des 2 champs manquants ; le workflow doit retourner MISSING_INFORMATION avec le champ restant.`
- l.8, après : `Le client fournit uniquement la date de naissance ; le lieu de naissance reste absent. Le workflow doit retourner MISSING_INFORMATION avec lieuNaissance.`
- l.25, avant : `Née le 01/01/1980 à Ville-Fictive.`
- l.25, après : `Née le 01/01/1980.`

Inchangé : E3 `success` / `200`, `missingFields` = `[lieuNaissance]`, `caseNumber` 11, extraction = `dateNaissance` seule (`1980-01-01`). `tests/n8n/FIXTURES_I1.md` ne cite pas l'ancienne phrase ; il n'est pas modifié. Findings 2, 3 et 4 du reviewer restent hors périmètre.

Limite connue, à traiter en L3 : `tests/n8n/validate_i1_fixtures.ps1:325-327` vérifie seulement que `message` est une chaîne de 1 à 4 000 caractères. Le validateur ne contrôle pas la cohérence entre le texte du message et l'extraction simulée. Le vert ci-dessous ne prouve pas cette cohérence ; elle a été relue à la main pour ce cycle.

Échec réel après enregistrement dans le validateur, avant correction de la fixture :

```text
FAIL i1c_05_round2_incomplete.json
      - caseNumber must be 11
      - I1C-05 E3 must be a success 200
PASSED=10 FAILED=1
RESULT: FAIL
```

## FILES_CHANGED

Fichiers du lot, y compris non suivis (`git status` : `??`) :

- `tests/n8n/validate_i1_fixtures.ps1` — non suivi. Cycle 1 : `I1C-05` dans le registre, le contrat de chemin, la règle `missingFields` du premier tour, les assertions de cas (E1 422, E3 succès 200, E2 avant la seconde extraction, aucun E4/E5/E6), deux auto-tests négatifs. Inchangé au cycle 2.
- `tests/n8n/fixtures/i1c_05_round2_incomplete.json` — non suivi. Cycle 1 : `caseNumber` 5 → 11 ; stub E3 `error`/`422` → `success`/`200`. Cycle 2 : description l.8 et message l.25 (avant/après ci-dessus). Attendu inchangé.
- `tests/n8n/fixtures/` — répertoire non suivi ; les dix autres fixtures n'ont pas été modifiées.
- `tests/n8n/FIXTURES_I1.md` — non suivi. Cycle 1 : décompte 11 fixtures, 5 cas I-C, exception `missingFields`, ordre E2, 21 auto-tests, résultat de référence. Inchangé au cycle 2.
- `docs/reports/L1-i1c-05-validateur.md` — non suivi. Ce rapport.

Hors lot : `AGENTS.md` est modifié dans l'arbre de travail (`git status` : `M`, +178 / −14). Ce cycle ne l'a pas touché. Aucun `git add`, aucun commit.

Aucun contrat, aucune migration, aucun code backend, aucun ADR.

## TESTS

Commande (locale, sans Docker, réseau, base, n8n ni Ollama) :

```text
powershell -NoProfile -ExecutionPolicy Bypass -File .\tests\n8n\validate_i1_fixtures.ps1 -SelfTest
```

Sortie réelle du cycle 1, code de sortie 0 :

```text
DG-I1-FIXTURES-001 static validator
fixtureDir=C:\Users\epifa\cursor-workspace\document-generator\tests\n8n\fixtures
PASS i1c_01_initial_valid.json
PASS i1c_02_second_turn_complete.json
PASS i1c_03_missing_information.json
PASS i1c_04_invalid_request_reference.json
PASS i1c_05_round2_incomplete.json
PASS i1d_05_duplicate_message.json
PASS i1d_06_backend_error.json
PASS i1d_07_ollama_unavailable.json
PASS i1d_08_timeout.json
PASS i1d_09_fictitious_pii.json
PASS i1d_10_binary_content.json
PASS fixture identifiers unique
--- SELF-TEST (negative mutations) ---
SELFTEST missing-finalOutcome PASS
SELFTEST invalid-finalOutcome PASS
SELFTEST generated-to-rejected PASS
SELFTEST generated-to-error PASS
SELFTEST incorrect-http-status PASS
SELFTEST incorrect-error-code PASS
SELFTEST invalid-requestId PASS
SELFTEST inconsistent-requestId PASS
SELFTEST inconsistent-documentId PASS
SELFTEST inconsistent-missingFields PASS
SELFTEST stub-count-mismatch PASS
SELFTEST duplicate-fixtureId PASS
SELFTEST missing-pii-markers PASS
SELFTEST pii-marker-absent-from-payload PASS
SELFTEST binary-signature-absent PASS
SELFTEST bad-binary-magic PASS
SELFTEST step-order-i1c-05 PASS
SELFTEST e3-http-i1c-05 PASS
SELFTEST step-order-i1c-02 PASS
SELFTEST step-order-i1d-05 PASS
SELFTEST utf8-bom-rejected PASS
SELFTEST_PASSED=21 SELFTEST_FAILED=0
PASSED=12 FAILED=0
RESULT: PASS
```

Les 19 auto-tests préexistants restent verts. Deux auto-tests ajoutés : `step-order-i1c-05`, `e3-http-i1c-05`.

Même commande relancée au cycle 2, après réécriture du message. Code de sortie 0 :

```text
DG-I1-FIXTURES-001 static validator
fixtureDir=C:\Users\epifa\cursor-workspace\document-generator\tests\n8n\fixtures
PASS i1c_01_initial_valid.json
PASS i1c_02_second_turn_complete.json
PASS i1c_03_missing_information.json
PASS i1c_04_invalid_request_reference.json
PASS i1c_05_round2_incomplete.json
PASS i1d_05_duplicate_message.json
PASS i1d_06_backend_error.json
PASS i1d_07_ollama_unavailable.json
PASS i1d_08_timeout.json
PASS i1d_09_fictitious_pii.json
PASS i1d_10_binary_content.json
PASS fixture identifiers unique
--- SELF-TEST (negative mutations) ---
SELFTEST missing-finalOutcome PASS
SELFTEST invalid-finalOutcome PASS
SELFTEST generated-to-rejected PASS
SELFTEST generated-to-error PASS
SELFTEST incorrect-http-status PASS
SELFTEST incorrect-error-code PASS
SELFTEST invalid-requestId PASS
SELFTEST inconsistent-requestId PASS
SELFTEST inconsistent-documentId PASS
SELFTEST inconsistent-missingFields PASS
SELFTEST stub-count-mismatch PASS
SELFTEST duplicate-fixtureId PASS
SELFTEST missing-pii-markers PASS
SELFTEST pii-marker-absent-from-payload PASS
SELFTEST binary-signature-absent PASS
SELFTEST bad-binary-magic PASS
SELFTEST step-order-i1c-05 PASS
SELFTEST e3-http-i1c-05 PASS
SELFTEST step-order-i1c-02 PASS
SELFTEST step-order-i1d-05 PASS
SELFTEST utf8-bom-rejected PASS
SELFTEST_PASSED=21 SELFTEST_FAILED=0
PASSED=12 FAILED=0
RESULT: PASS
```

Ce vert ne couvre pas la cohérence message ↔ extraction simulée (`validate_i1_fixtures.ps1:325-327`).

| Gate | Statut | Note |
|---|---|---|
| G0 | NOT_RUN | Hors lot |
| G1 | NOT_RUN | `./mvnw verify` non lancé |
| G2 | NOT_RUN | |
| G3 | NOT_RUN | |
| G4 | NOT_RUN | |
| G5 | NOT_RUN | Le validateur statique de ce corpus est PASS (sorties ci-dessus). Aucune validation runtime n'a été autorisée. La définition G5 complète (7 fixtures de conversation et 18+ hostiles de `requirements/N8N_CONTRACTS.md:513-514`, scans runtime PII / `PK\x03\x04`) n'a pas été exécutée |
| G6 | NOT_RUN | |
| G7 | NOT_RUN | Revue security non faite |
| G8 | NOT_RUN | |

Validation statique uniquement. Ce n'est pas une preuve d'exécution n8n.

## RISKS

- `requirements/ATTESTATION_CONCORDANCE.md:402-404` continue de mentionner un HTTP 422 après recalcul fusionné. Le validateur suit les tableaux E3 = 200. Un arbitrage L0c / ADR-002 reste ouvert.
- Le second stub OLLAMA ne redéclare pas toutes les clés absentes du payload du tour (`requirements/N8N_CONTRACTS.md:422`, règle `allOf`). I1C-02, déjà vert, a la même forme delta. Écart laissé à L3.
- G5 n'est pas acquis : il manque les scénarios I-C et I-D listés dans `docs/n8n-orchestration.md` §13.
- Le validateur ne compare pas le message à l'extraction simulée (`tests/n8n/validate_i1_fixtures.ps1:325-327`). Limite connue, à traiter en L3.
- I1D-09 l.18 est plus large que son `data` OLLAMA : passeport et courriel fictifs, réservés au `securityProbe`. Constat pour L3. Non corrigé dans ce cycle.

## NEXT_ACTION

Revue fraîche (reviewer, puis security) sur le cycle 2. Findings 2, 3 et 4 du reviewer restent hors de ce correctif. L3 : contrôle message ↔ extraction, et le constat I1D-09. Ne pas traiter ce rapport comme une validation runtime.
