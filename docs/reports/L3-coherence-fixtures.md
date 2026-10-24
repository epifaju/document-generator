# L3 — Cohérence des fixtures n8n

| | |
|---|---|
| Rôle | n8n-developer / tester |
| Exigences | FR-12 (couverture I-C / I-D statique), forme delta `allOf`, `ErrorResponse.code` |
| ADR | aucun (ADR-002 reste PROPOSÉ, non modifié) |
| Date | 2026-10-24 |
| Cycle | 1/3 |
| Décision humaine | Accord d'écriture avec quatre ajustements (simulation `contactEmail`, auto-tests avant contrôle, exemption delta structurelle, motif de naissance borné). |

## STATUS

Cycle 1 terminé. Les fixtures delta re-déclarent les clés obligatoires absentes de leur `data`, les corps E1 `422` portent `code`, le validateur refuse un message de naissance incohérent avec l'extraction réussie, et I1D-09 est documenté. Sortie finale : `SELFTEST_PASSED=25 SELFTEST_FAILED=0`, `PASSED=12 FAILED=0`. Revue fraîche non faite. G5 reste `NOT_RUN`.

## FINDINGS

ADR-002 (`docs/adr/ADR-002-e3-missing-information-200.md:3`) est **PROPOSÉ**. Aucun point de ce lot ne choisit le HTTP d'E3. E3 d'I1C-05 reste un succès `200`. Les `422` corrigés sont des E1.

### A — forme delta

FIX-02 est le correctif `DG-I1-FIXTURES-FIX-001` : il compare les `missingFields` des stubs OLLAMA, E1, E3 et E2 à `expected.missingFields`, la liste backend de l'outcome. Ce n'est pas un code HTTP.

L'exemption ajoutée ne cite aucun `fixtureId`. Un tour ≥ 2 est un événement dont `requestId` est non nul. Son extraction est l'OLLAMA en succès situé après le premier stub E2 (`tests/n8n/validate_i1_fixtures.ps1:214-229`). Pour ce stub, `missingFields` doit égaler, ordre canonique, les six clés obligatoires absentes de son `data` (`:204-211`, `:582`).

Inventaire : seules I1C-02 (unique OLLAMA, après E2) et I1C-05 (second OLLAMA) sont des extractions de tour ≥ 2. I1D-05 a deux événements et zéro OLLAMA.

| Fixture | `missingFields` du delta |
|---|---|
| I1C-02 | `prenom`, `nom`, `nomIncorrect`, `nomCorrect` |
| I1C-05, second OLLAMA | `prenom`, `nom`, `lieuNaissance`, `nomIncorrect`, `nomCorrect` |

`expected.missingFields` reste `[]` (I1C-02) et `[lieuNaissance]` (I1C-05, aussi le corps E3). Le premier OLLAMA d'I1C-05 n'est pas un delta.

### B — `code` des 422

Corpus : deux corps backend `422`, aucun `400`. E1 d'I1C-03 et d'I1C-05 portent `"code": "MISSING_INFORMATION"` (`requirements/API_CONTRACTS.md:170`, `:277`). Statut, HTTP et `expected` inchangés. E8 `400` reste un `ExtractionValidationResponse` (`requirements/API_CONTRACTS.md:440`) et n'est pas soumis à cette règle.

### C — phrase de naissance

Motif, sans « à » isolé :

- date : `né` ou `née`, puis `le`, puis `jj/mm/aaaa` ou `jj-mm-aaaa` ;
- lieu : la même phrase, puis `à` minuscule, puis un mot à majuscule initiale.

Sonde locale du motif : « À partir de la demande » et « à partir de rien » donnent date=0 et lieu=0. « né le 01/01/1980 à partir » donne date=1 et lieu=0.

Appariement depuis la fin, uniquement avec un OLLAMA en succès. Fixtures à extraction réussie :

| Fixture | Contrôle |
|---|---|
| I1C-01 | s'applique ; `01/01/1980` et `Ville-Fictive` sont dans `data` ; passe |
| I1C-02 | s'applique à l'événement 2 ; passe. L'événement 1 n'a pas d'extraction appariée |
| I1C-03 | phrase absente ; non déclenché ; passe |
| I1C-05 | événement 1 non déclenché. Événement 2 : `Née le 01/01/1980` correspond à `1980-01-01` ; pas de lieu ; passe |
| I1D-06 | s'applique ; passe |
| I1D-09 | s'applique sur la phrase de naissance ; passe. Passeport et courriel hors motif |
| I1D-10 | s'applique ; passe |

Aucune fixture hostile n'a été modifiée.

### D — I1D-09

`FAKE-PP-0001` et `fictif@example.invalid` sont les marqueurs `securityProbe.markers` (`tests/n8n/fixtures/i1d_09_fictitious_pii.json:123-124`). Le validateur les cherche comme sous-chaînes du payload analysé `input` + `stubs`, `securityProbe` exclu (`validate_i1_fixtures.ps1:264-274`, `:851-862`). Il n'exige pas leur présence dans `data`. Les deux chaînes sont dans le message ; cela suffit.

`contactEmail` est une clé valide de `data` (`requirements/API_CONTRACTS.md:95`). L'absence de `fictif@example.invalid` dans le `data` OLLAMA est une simulation délibérée d'un LLM qui n'extrait pas un champ optionnel. La contrainte 4 du prompt (`prompts/extraction/system_prompt_attestation_concordance.md:24-28`) ne verse dans `missingFields` que les six clés obligatoires ; une clé optionnelle non émise reste absente de `data`. Seul `FAKE-PP-0001` est une clé inconnue (`passeport`, pilote S12).

## FILES_CHANGED

Fichiers du lot, tous non suivis (`git status` : `??`) :

- `tests/n8n/validate_i1_fixtures.ps1` — exemption delta structurelle, contrôle `code` des 400/422 backend, contrôle de phrase de naissance, quatre auto-tests.
- `tests/n8n/fixtures/i1c_02_second_turn_complete.json` — `missingFields` de l'OLLAMA delta.
- `tests/n8n/fixtures/i1c_03_missing_information.json` — `code` sur l'E1 422.
- `tests/n8n/fixtures/i1c_05_round2_incomplete.json` — `missingFields` du second OLLAMA et `code` sur l'E1 422.
- `tests/n8n/FIXTURES_I1.md` — FIX-02, exemption, motif, décision I1D-09, décompte 25.
- `docs/reports/L3-coherence-fixtures.md` — ce rapport.

`tests/n8n/fixtures/` est non suivi. Les fixtures I1D, I1C-01 et I1C-04 n'ont pas été modifiées.

Hors lot : `AGENTS.md` est modifié dans l'arbre (`git status` : `M`, +178 / −14). Ce cycle ne l'a pas touché. Aucun `git add`, aucun commit. Aucun contrat, aucun backend, aucun ADR.

## TESTS

Commande (locale, sans Docker, réseau, base, n8n ni Ollama) :

```text
powershell -NoProfile -ExecutionPolicy Bypass -File .\tests\n8n\validate_i1_fixtures.ps1 -SelfTest
```

Échec réel après les quatre auto-tests, avant les contrôles A, B et C. Code de sortie 1 :

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
SELFTEST message-place-absent FAIL
      expected diagnostic: message birth place is absent from the paired extraction
      actual errors: <none>
SELFTEST message-date-absent FAIL
      expected diagnostic: message birth date is absent from the paired extraction
      actual errors: <none>
SELFTEST delta-missing-undeclared FAIL
      expected diagnostic: delta extraction missingFields must redeclare every mandatory key absent from data
      actual error: missingFields do not match expected.missingFields for stub stage OLLAMA
SELFTEST error-code-absent FAIL
      expected diagnostic: backend 422/400 body.code is absent
      actual errors: <none>
SELFTEST_PASSED=21 SELFTEST_FAILED=4
PASSED=12 FAILED=4
RESULT: FAIL
```

`delta-missing-undeclared` tombait encore sur la comparaison FIX-02. Les trois autres diagnostics étaient absents.

Les littéraux accentués des mutations C, écrits dans le script UTF-8 sans BOM, ne survivent pas à la lecture Windows PowerShell 5.1. Après branchement du contrôle, ces deux auto-tests restaient à `actual errors: <none>`. Les mutations ont été reconstruites avec `[char]0x00E9` et `[char]0x00E0`. Le contrôle les rejette ensuite.

Même commande après corrections. Code de sortie 0 :

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
SELFTEST message-place-absent PASS
SELFTEST message-date-absent PASS
SELFTEST delta-missing-undeclared PASS
SELFTEST error-code-absent PASS
SELFTEST_PASSED=25 SELFTEST_FAILED=0
PASSED=12 FAILED=0
RESULT: PASS
```

| Gate | Statut | Note |
|---|---|---|
| G0 | NOT_RUN | Hors lot |
| G1 | NOT_RUN | `./mvnw verify` non lancé |
| G2 | NOT_RUN | |
| G3 | NOT_RUN | |
| G4 | NOT_RUN | |
| G5 | NOT_RUN | Le validateur statique de ce corpus est PASS (sortie ci-dessus). Aucune validation runtime n'a été autorisée. La définition G5 (`requirements/N8N_CONTRACTS.md:513-514`) n'a pas été exécutée |
| G6 | NOT_RUN | |
| G7 | NOT_RUN | Revue security non faite |
| G8 | NOT_RUN | |

Validation statique uniquement. Ce n'est pas une preuve d'exécution n8n.

## RISKS

- ADR-002 reste PROPOSÉ. Le validateur continue de suivre les tableaux E3 = 200. Ce lot ne l'arbitre pas.
- Le contrôle C ne couvre que la phrase `né` / `née le <date>` et le lieu qui la suit. Une date ou un lieu écrits autrement ne sont pas vus.
- Backlog, non traité : contrôle inverse `data` → message, pour signaler une valeur présente dans l'extraction réussie et absente du message apparié (donnée inventée par le LLM).
- G5 n'est pas acquis : les scénarios I-C et I-D de `docs/n8n-orchestration.md` §13 restent hors de ce corpus.

## NEXT_ACTION

Revue fraîche (reviewer, puis security) sur ce cycle. Ne pas traiter ce rapport comme une validation runtime.
