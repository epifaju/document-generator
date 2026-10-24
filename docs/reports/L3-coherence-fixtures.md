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

Relecture du 2026-10-24, même commande, code de sortie 0. La sortie est identique à ce bloc : `PASSED=12 FAILED=0`, `SELFTEST_PASSED=25 SELFTEST_FAILED=0` (21 auto-tests déjà présents, plus `message-place-absent`, `message-date-absent`, `delta-missing-undeclared`, `error-code-absent`). Aucun écart. Fixtures, validateur, contrats et `AGENTS.md` non modifiés à cette relecture.

`git status --short` ne liste aucun chemin `tests/n8n/` ni `docs/reports/L3-coherence-fixtures.md`. `git ls-files` les montre suivis, sans différence et sans fichier non suivi du lot. Les autres lignes de l'arbre (`docs/PRD_adgendoc_v2_1_Cursor.md`, ADR-001, `docs/n8n-orchestration.md`, les deux contrats, `docs/reports/L0c-Q01.md`, `.cursor/agents/`, `docs/packs/`, `split_cursor_packs.ps1`) sont hors L3. Aucun commit.

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
- Limite connue du contrôle C : un seul motif, « né/née le … à Mot ». La date est `jj/mm/aaaa` ou `jj-mm-aaaa` après `né` ou `née le`. Le lieu n'est lu que s'il suit cette phrase (`à` puis un mot à majuscule). Un « à » isolé, une autre tournure de date ou un autre toponyme ne sont pas vus.
- Backlog, non traité : contrôle inverse `data` → message, pour signaler une valeur présente dans l'extraction réussie et absente du message apparié (donnée inventée par le LLM).
- Backlog, non traité : un cas « tour 2, règle métier violée » à stubber en E3 `400` (`ErrorResponse`, statut `REJECTED` persisté), décision Q-01 / ADR-001. Ce corpus ne le contient pas.
- G5 n'est pas acquis : les scénarios I-C et I-D de `docs/n8n-orchestration.md` §13 restent hors de ce corpus. Le PASS ci-dessus est statique.

## NEXT_ACTION

Ce rapport ne contient aucun verdict reviewer ni security (aucune ligne `VERDICT`). Ces revues, en contexte indépendant, sont à lancer par l'humain. Elles ne sont pas écrites ici. Ne pas traiter ce rapport comme une validation runtime.

## Revues

Texte recopié tel quel. Reviewer : [Revue L3](40476425-1449-4692-aca2-3a068e6144d2). Security : [Sécurité L3](8f9a5d92-2ca3-4b46-a2cf-984cd81b0e1a).

### Reviewer

VERDICT: FAIL

Relecture statique du lot L3 (`tests/n8n`, rapport, contrats cités). Les points (a)–(e) tiennent en lecture de code ; le critère (f) et la vérification de portée par diff n’ont pas pu être exécutés (shell bloqué en Ask mode).

## Constats

1. `docs/reports/L3-coherence-fixtures.md:199`, **bloquant** — critère (f) non exécuté par cette revue : `powershell … validate_i1_fixtures.ps1 -SelfTest` et `git diff b113e87..HEAD` ont été refusés en Ask mode. Impossible de comparer indépendamment la sortie live au rapport (`SELFTEST_PASSED=25 SELFTEST_FAILED=0`, `PASSED=12 FAILED=0`). Gate (f) = **NOT_RUN** pour le reviewer.

2. `tests/n8n/validate_i1_fixtures.ps1:1213-1268`, **info** — (a) Les quatre auto-tests mutent bien l’objet ciblé et ciblent un diagnostic absent sans le contrôle : `events[1].body.message` (I1C-05, lieu), `events[0].body.message` (I1C-03, date), `delta.body.missingFields` tronqué (I1C-02), `E1.body.code` supprimé (I1C-03). Le rapport documente l’échec initial 21/4 avec diagnostics `<none>` ou FIX-02, cohérent avec l’état pré-contrôle.

3. `tests/n8n/validate_i1_fixtures.ps1:231-261`, **info** — (b) Appariement depuis la fin, OLLAMA `success` uniquement : I1D-05 (0 succès), I1D-07/08 (0 succès) non déclenchés ; I1D-06/09/10 date/lieu alignés avec `data`. Pas de faux positif hostile identifié.

4. `tests/n8n/fixtures/i1c_02_second_turn_complete.json:57`, `tests/n8n/fixtures/i1c_05_round2_incomplete.json:93`, `tests/n8n/validate_i1_fixtures.ps1:204-211`, **info** — (c) Delta conformes à N8N_CONTRACTS §5 (l.422, règle `allOf`) : I1C-02 `["prenom","nom","nomIncorrect","nomCorrect"]`, I1C-05 second OLLAMA `["prenom","nom","lieuNaissance","nomIncorrect","nomCorrect"]`, ordre canonique `Get-AbsentMandatoryKeys`.

5. `tests/n8n/FIXTURES_I1.md:210-225`, **info** — (d) Citations valides : marqueurs `i1d_09_fictitious_pii.json:119-124`, `contactEmail` à `requirements/API_CONTRACTS.md:95`, contrainte 4 du prompt `prompts/extraction/system_prompt_attestation_concordance.md:24-28`.

6. `tests/n8n/fixtures/i1c_03_missing_information.json:57`, `tests/n8n/fixtures/i1c_05_round2_incomplete.json:64`, **info** — (e) Portée fixture/contrat cohérente en lecture : `code: "MISSING_INFORMATION"` sur les deux E1 `422` (API_CONTRACTS l.170, l.277) ; fixtures hostiles I1D inchangées dans le contenu inspecté ; contrats `requirements/*.md` sans modification L3 visible. **Non confirmé par diff git** (bloqué).

7. `docs/reports/L3-coherence-fixtures.md:93-145`, **info** — Rapport d’échec initial 21/4 puis vert 25/0 : séquence et diagnostics plausibles vs code actuel ; relecture reviewer live absente (cf. constat 1).

**Conclusion :** relancer la revue en mode Agent (ou exécuter manuellement `-SelfTest` + `git diff b113e87..HEAD`) pour lever le blocage sur (f) ; si la sortie live confirme 25/0, le lot devrait passer.

### Security

VERDICT: PASS

Revue read-only du lot L3 (fixtures n8n, `validate_i1_fixtures.ps1`, rapport `L3-coherence-fixtures.md`). Le diff `b113e87..HEAD` n’a pas pu être exécuté en Ask mode ; analyse basée sur la lecture directe des fichiers et le contenu documenté du lot.

## Constats

1. **`tests/n8n/validate_i1_fixtures.ps1:253-258`** — **info** — Les nouveaux diagnostics de cohérence naissance (`message birth date/place is absent from the paired extraction`) sont génériques : aucune valeur de `data` (prénom, nom, date ISO, lieu) n’est émise dans le message d’erreur.

2. **`tests/n8n/validate_i1_fixtures.ps1:458-464,582`** — **info** — Les autres contrôles ajoutés (`backend 422/400 body.code is absent`, règle delta `missingFields`) utilisent aussi des libellés structurels sans valeur de champ personnel.

3. **`tests/n8n/fixtures/*.json` (11 fichiers)** — **info** — Toutes les fixtures portent `"synthetic": true` ; le validateur l’impose (`:349`). Données manifestement fictives : `Camille Fictive`, `Ville-Fictive`, `Camil Fictif`, `FAKE-PP-0001`, `fictif@example.invalid` (domaine réservé). Aucun secret détecté (`password`, `secret`, `Bearer`, clés API, etc.).

4. **`tests/n8n/fixtures/i1d_09_fictitious_pii.json:111-114`, `i1d_10_binary_content.json:112-115`** — **info** — Cas hostiles I1D-09/I1D-10 non modifiés par le lot (confirmé par le rapport). `expected.securityScan.piiInLogs` et `binaryInExecutionData` restent `false` ; logique `securityProbe` PII/binaire inchangée (`validate_i1_fixtures.ps1:674-683,841-898`).

5. **`tests/n8n/validate_i1_fixtures.ps1`** — **info** — Aucune nouvelle dépendance (script PowerShell autonome, pas d’import externe, pas de manifeste package).

6. **`docs/reports/L3-coherence-fixtures.md:95,155`** — **info** — Le rapport colle la sortie réelle du validateur incluant un chemin local `C:\Users\epifa\...`. Ce n’est pas un secret applicatif, mais expose un identifiant machine ; préférable de généraliser (`<repo>/tests/n8n/fixtures`) dans les prochains rapports.

7. **`docs/reports/L3-coherence-fixtures.md:52,55,64`** — **info** — Le rapport cite des valeurs synthétiques (`01/01/1980`, `Ville-Fictive`, marqueurs I1D-09) à titre de documentation d’intention de test, pas de PII réelle. Acceptable pour ce lot ; rester vigilant à ne pas y coller de sorties contenant des `data` en cas d’échec futur.

8. **`tests/n8n/validate_i1_fixtures.ps1:859,861`** — **info** — Dette préexistante (hors delta du lot) : en échec I1D-09, le diagnostic peut inclure la chaîne du marqueur (`$marker`), y compris des noms/dates fictifs. Non introduit par L3 ; à traiter si la politique « zéro valeur personnelle même synthétique dans les diagnostics » devient stricte.

9. **Périmètre E6 / auth it.1** — **info** — Lot statique n8n uniquement ; le risque it.1 « téléchargement sans auth » (`AGENTS.md` §13) reste documenté et non adressé ici — ni aggravé ni corrigé par ce lot.

## Synthèse

Le lot renforce la cohérence structurelle (delta, `code` 422, phrase de naissance) sans affaiblir les garde-fous PII/binaire, sans secret, sans dépendance nouvelle, avec fixtures et nouveaux diagnostics conformes à la règle « pas de valeurs de `data` dans les diagnostics ». Aucun constat bloquant.

### Reviewer — revue 2

Texte recopié tel quel. [Revue 2 L3](93d01a0b-e75c-471a-b3f7-ce32b822e968). Security non relancé (PASS de la revue 1, même diff).

VERDICT: BLOCKED

Revue indépendante (revue 2) du lot L3, lecture seule des fichiers autorisés uniquement.

## Synthèse par critère

| Critère | Résultat |
|---|---|
| (a) Auto-tests non vacuaires | **OK** (lecture code) |
| (b) Pas de faux positif hostile | **OK** (lecture code + fixtures) |
| (c) Delta `allOf` + ordre canonique | **OK** |
| (d) Citations I1D-09 | **OK** |
| (e) Portée fixture/contrat | **NOT_RUN** — preuve diff absente |
| (f) Échec 21/4 puis vert 25/0 | **OK** — cohérent |

## Constats

1. **`docs/reports/evidence/L3-diff.txt` (fichier vide)**, **bloquant pour (e)** — La preuve attendue (`git diff b113e87..HEAD -- tests/n8n docs/reports/L3-coherence-fixtures.md`) ne contient aucun hunk. Avec `L3-head.txt` = `b113e87089ad61c3d18bd7f7e5dc1a59b99b6fef`, un diff vide peut signifier HEAD = base, pas l’énumération des changements du lot. Impossible de démontrer qu’aucune fixture ni contrat n’a été modifié hors portée. **Correction attendue :** recapturer un diff non vide (ex. `git diff b113e87^..HEAD` ou plage de commit correcte) couvrant `tests/n8n` et le rapport L3.

2. **`tests/n8n/validate_i1_fixtures.ps1:1213-1268`**, **info** — **(a)** Les quatre auto-tests mutent l’objet ciblé : `events[1].body.message` (I1C-05, lieu), `events[0].body.message` (I1C-03, date), `delta.body.missingFields` tronqué (I1C-02), suppression de `E1.body.code` (I1C-03). `Assert-Negative-Diagnostic` (`:905-930`) exige un diagnostic exact ; sans contrôle, échec documenté (`actual errors: <none>` ou FIX-02) — non vacuité confirmée.

3. **`tests/n8n/validate_i1_fixtures.ps1:231-261`**, **info** — **(b)** Appariement depuis la fin, OLLAMA `success` uniquement ; `pairCount = 0` si aucun succès (`:241-242`). I1D-05 (0 OLLAMA succès), I1D-07/08 (modes `connection_refused`/`timeout`) non déclenchés. I1D-06/09/10 et I1C-01 : message et `data` alignés sur date/lieu — pas de faux positif hostile identifié.

4. **`tests/n8n/fixtures/i1c_02_second_turn_complete.json:57`**, **`tests/n8n/fixtures/i1c_05_round2_incomplete.json:93`**, **`tests/n8n/validate_i1_fixtures.ps1:204-211`**, **info** — **(c)** Conforme à `requirements/N8N_CONTRACTS.md:422` (règle `allOf`) : I1C-02 `["prenom","nom","nomIncorrect","nomCorrect"]` ; I1C-05 delta `["prenom","nom","lieuNaissance","nomIncorrect","nomCorrect"]` ; ordre = `Get-AbsentMandatoryKeys`.

5. **`tests/n8n/FIXTURES_I1.md:210-225`**, **info** — **(d)** Citations valides : marqueurs `i1d_09_fictitious_pii.json:119-124` ; `contactEmail` à `requirements/API_CONTRACTS.md:95` ; contrainte 4 du prompt `prompts/extraction/system_prompt_attestation_concordance.md:24-28`.

6. **`tests/n8n/fixtures/i1c_03_missing_information.json:57`**, **`tests/n8n/fixtures/i1c_05_round2_incomplete.json:64`**, **info** — Lecture directe : les deux E1 `422` portent `"code": "MISSING_INFORMATION"`, aligné avec `requirements/API_CONTRACTS.md:170` et `:277`. Ne remplace pas la vérification (e) par diff.

7. **`docs/reports/evidence/L3-selftest.txt:41-43`**, **`docs/reports/L3-coherence-fixtures.md:93-145`**, **`docs/reports/L3-coherence-fixtures.md:153-197`**, **info** — **(f)** Preuve finale : `SELFTEST_PASSED=25 SELFTEST_FAILED=0`, `PASSED=12 FAILED=0`, `RESULT: PASS` — conforme à l’attendu. Premier bloc rapport : `21/4` avec les 4 nouveaux tests en FAIL ; second bloc identique à `L3-selftest.txt`. Séquence échec initial → vert cohérente, aucun écart démontré.

## Ce qui manque pour lever le blocage

- Un **`L3-diff.txt` non vide** permettant de juger (e) : fichiers touchés vs portée déclarée (`validate_i1_fixtures.ps1`, `i1c_02/03/05`, `FIXTURES_I1.md`, rapport L3 ; absence de modification des contrats `requirements/*.md` et des fixtures hostiles I1D).

## Vérifications NOT_RUN

- Exécution live du validateur (interdit par consigne ; couvert par `L3-selftest.txt` pour l’état final).
- Diff git live (interdit ; bloquant car capture vide).

Aucun défaut **démontré** dans le code ou les fixtures relues ; le verdict **BLOCKED** tient uniquement à l’absence de preuve diff pour (e).

### Reviewer — revue 3

Texte recopié tel quel. [Revue 3 L3](fb9af844-639f-4adc-a765-f3e4db00f67d). Security non relancé (PASS de la revue 1, même diff).

VERDICT: PASS

Revue 3 indépendante du lot L3, lecture seule des fichiers autorisés uniquement.

## Synthèse par critère

| Critère | Résultat |
|---|---|
| (a) Auto-tests non vacuaires | **OK** |
| (b) Pas de faux positif hostile | **OK** |
| (c) Delta `allOf` + ordre canonique | **OK** |
| (d) Citations I1D-09 | **OK** |
| (e) Portée fixture/contrat | **OK** |
| (f) Échec 21/4 puis vert 25/0 | **OK** — cohérent |

## Constats

1. **`tests/n8n/validate_i1_fixtures.ps1:1213-1268`**, **info** — **(a)** Les quatre auto-tests mutent l’objet ciblé : `input.events[1].body.message` (I1C-05, lieu via `[char]0x00E0`), `input.events[0].body.message` (I1C-03, date), `delta.body.missingFields` tronqué (I1C-02), suppression de `E1.body.code` (I1C-03). `Assert-Negative-Diagnostic` (`:905-930`) exige le diagnostic exact ; sans contrôle, l’échec initial documenté (`docs/reports/L3-coherence-fixtures.md:130-141`) montre `<none>` ou FIX-02 — non vacuité confirmée.

2. **`tests/n8n/validate_i1_fixtures.ps1:231-261`**, **info** — **(b)** Appariement depuis la fin, OLLAMA `success` uniquement ; retour anticipé si `pairCount -le 0` (`:241-242`). I1D-05 : stubs E2/E6 uniquement, zéro OLLAMA succès. I1D-07/08 : OLLAMA en `connection_refused`/`timeout`, exclus de `$success`. I1D-06/09/10 : message et `data` alignés sur date/lieu — pas de faux positif hostile identifié.

3. **`tests/n8n/fixtures/i1c_02_second_turn_complete.json:57`**, **`tests/n8n/fixtures/i1c_05_round2_incomplete.json:93`**, **`tests/n8n/validate_i1_fixtures.ps1:204-211`**, **info** — **(c)** Conforme à `requirements/N8N_CONTRACTS.md:422` (règle `allOf`) : I1C-02 `["prenom","nom","nomIncorrect","nomCorrect"]` ; I1C-05 delta `["prenom","nom","lieuNaissance","nomIncorrect","nomCorrect"]` ; ordre = `Get-AbsentMandatoryKeys`.

4. **`tests/n8n/FIXTURES_I1.md:210-225`**, **info** — **(d)** Citations valides : marqueurs `tests/n8n/fixtures/i1d_09_fictitious_pii.json:119-124` ; `contactEmail` à `requirements/API_CONTRACTS.md:95` ; contrainte 4 du prompt `prompts/extraction/system_prompt_attestation_concordance.md:24-28`.

5. **`docs/reports/evidence/L3-commit-stat.txt:1-38`**, **`docs/reports/evidence/L3-worktree-status.txt:2-7`**, **`docs/reports/evidence/L3-diff.txt`** (14 fichiers `diff --git`, aucun `requirements/*`), **info** — **(e)** Commit `b113e87` ne touche aucun contrat `requirements/*`. Modifications non commitées (`requirements/API_CONTRACTS.md`, `requirements/N8N_CONTRACTS.md`, PRD, ADR-001, `docs/n8n-orchestration.md`) correspondent au lot L0c-Q01 documenté dans `docs/reports/L0c-Q01.md` — hors périmètre L3, pas d’écart. Mélange L1+L3 dans le commit accepté par le contexte de portée.

6. **`tests/n8n/fixtures/i1c_03_missing_information.json:57`**, **`tests/n8n/fixtures/i1c_05_round2_incomplete.json:64`**, **info** — Les deux E1 `422` portent `"code": "MISSING_INFORMATION"`, aligné avec `requirements/API_CONTRACTS.md:170` et `:277`.

7. **`docs/reports/evidence/L3-selftest.txt:41-43`**, **`docs/reports/L3-coherence-fixtures.md:93-145`**, **`docs/reports/L3-coherence-fixtures.md:153-197`**, **info** — **(f)** Preuve finale : `SELFTEST_PASSED=25 SELFTEST_FAILED=0`, `PASSED=12 FAILED=0`, `RESULT: PASS`. Premier bloc rapport (section TESTS) : `21/4` avec les 4 nouveaux tests en FAIL. Second bloc identique à `L3-selftest.txt`. Séquence échec initial → vert cohérente ; aucun écart démontré.

## Vérifications NOT_RUN

- Exécution live du validateur (interdit par consigne ; couvert par `L3-selftest.txt` pour l’état final).
- Diff git live (interdit ; couvert par `L3-commit-stat.txt`, `L3-worktree-status.txt` et `L3-diff.txt` pour (e)).

Aucun défaut bloquant démontré dans le code, les fixtures ou les preuves relues.
