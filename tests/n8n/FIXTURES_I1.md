# Fixtures I.1 — validation statique

Mission : `DG-I1-FIXTURES-001`.

Mission de correction : `DG-I1-FIXTURES-FIX-001` (assertions de résultat,
cohérence des identifiants, recherche PII sur payload analysé, signature
binaire synthétique, ordre des étapes, auto-tests négatifs à diagnostic
spécifique).

Ce document décrit le lot de **11 fixtures synthétiques** et le validateur
statique associé. Il s'agit d'une préparation de tests, **pas d'une preuve
d'exécution** des gates I-B, I-C ou I-D.

## Périmètre

- 5 cas de conversation pour I-C ;
- 6 cas de panne, idempotence et sécurité pour I-D ;
- aucune donnée personnelle réelle ;
- aucun service, réseau, conteneur, modèle Ollama ou workflow n8n exécuté ;
- aucune dépendance externe : PowerShell 5.1 et JSON uniquement.

Les fixtures sont volontairement stockées sous `tests/n8n/fixtures/`. Ce lot ne
remplace pas les chemins canoniques prévus par les contrats
(`tests/fixtures/conversation/` et `tests/fixtures/hostile/`) et ne complète pas
les checklists exhaustives de `docs/n8n-orchestration.md` §13.

## Fichiers

| Cas | Fichier | Intention | Attendu |
|---|---|---|---|
| I1C-01 | `i1c_01_initial_valid.json` | Demande initiale complète, sans `requestId` | `200 GENERATED`, parcours OLLAMA/E8/E1/E4/E5/E6 |
| I1C-02 | `i1c_02_second_turn_complete.json` | Deuxième tour avec `requestId` et champs manquants | `200 GENERATED`, E2 avant extraction delta, puis E3 |
| I1C-03 | `i1c_03_missing_information.json` | Deux champs obligatoires absents | `200 MISSING_INFORMATION`, E1 en 422, aucun E4/E5/E6 |
| I1C-04 | `i1c_04_invalid_request_reference.json` | `requestId` volontairement non UUID v4 | `400 INVALID_REQUEST`, `VALIDATION_ERROR`, aucun appel |
| I1C-05 | `i1c_05_round2_incomplete.json` | Deux tours : E1 422, puis delta partiel et E3 200 encore incomplet | `200 MISSING_INFORMATION`, `missingFields=[lieuNaissance]`, aucun E4/E5/E6 |
| I1D-05 | `i1d_05_duplicate_message.json` | Deux messages identiques avec le même `requestId` | `200 GENERATED`, même `documentId`, E2/E6 deux fois |
| I1D-06 | `i1d_06_backend_error.json` | E4 renvoie trois fois `500` | `500 ERROR`, `INTERNAL_ERROR` |
| I1D-07 | `i1d_07_ollama_unavailable.json` | Connexion Ollama refusée trois fois | `502 ERROR`, `AI_EXTRACTION_ERROR`, aucun appel backend |
| I1D-08 | `i1d_08_timeout.json` | Timeout Ollama simulé trois fois | `502 ERROR`, `AI_EXTRACTION_ERROR`, aucun appel backend |
| I1D-09 | `i1d_09_fictitious_pii.json` | Marqueurs PII strictement fictifs | Parcours nominal + `securityProbe` PII |
| I1D-10 | `i1d_10_binary_content.json` | Signature ZIP synthétique en base64 | Parcours nominal + `securityProbe` binaire |

## Schéma

Chaque fixture contient les clés suivantes :

| Clé | Rôle |
|---|---|
| `schemaVersion` | Version du format, actuellement `1.0` |
| `fixtureId` | Identifiant stable, par exemple `I1C-01` |
| `gate` | `I-C` ou `I-D` |
| `caseNumber` | Numéro de cas, unique |
| `caseKey` | Nom technique stable du cas |
| `title` / `description` | Description lisible |
| `synthetic` | Toujours `true` |
| `input` | Requête webhook simulée : méthode, chemin, `correlationId`, événements |
| `stubs` | Réponses simulées ordonnées des étapes OLLAMA/E8/E1/E2/E3/E4/E5/E6 |
| `expected` | Statut HTTP final, résultat, erreurs, identifiants, compteurs d'appels |
| `securityProbe` | Marqueurs PII ou binaire fictifs, ou `null` |

Les événements portent une séquence strictement croissante. Un `requestId`
fourni doit être un UUID v4, sauf dans I1C-04 où il est volontairement invalide.
Les compteurs `expected.calls` doivent correspondre exactement au nombre de
stubs par étape.

Codes de résultat utilisés : `GENERATED`, `MISSING_INFORMATION`,
`INVALID_REQUEST` et `ERROR`. Les erreurs attendues sont notamment
`VALIDATION_ERROR`, `INTERNAL_ERROR` et `AI_EXTRACTION_ERROR`.

## Validateur

Fichier : `tests/n8n/validate_i1_fixtures.ps1`.

Vérifications :

- présence exacte des 11 fichiers attendus, sans fichier supplémentaire ;
- encodage UTF-8 sans BOM et JSON valide ;
- schéma, identifiants et numéros de cas uniques ;
- méthode `POST` et chemin `/webhook/document-generation` ;
- UUID v4 pour les corrélations et références applicables ;
- cohérence des stubs, des modes et des compteurs d'appels ;
- cohérence entre résultat attendu, statut HTTP et code d'erreur ;
- règles spécifiques à chaque cas ;
- absence déclarée de PII dans les logs et de binaire dans les execution data ;
- présence effective des marqueurs PII et de la signature `504B0304` attendue.

### Assertions ajoutées par DG-I1-FIXTURES-FIX-001

- **FIX-01 — contrat métier par fixture.** Un contrat explicite, indexé par
  `fixtureId`, impose le résultat (`finalOutcome`), le statut HTTP final, le
  code d'erreur (y compris `null`) et le chemin d'appel ordonné exact des stubs
  (séquence des étapes, pas seulement les compteurs).
- **FIX-02 — cohérence des identifiants.** Nom du correctif
  `DG-I1-FIXTURES-FIX-001` : ce n'est pas un code HTTP. Il aligne les
  identifiants des stubs sur `expected`, et il compare les `missingFields` des
  stubs OLLAMA (succès), E1, E3 et E2 à `expected.missingFields` (liste
  backend de l'outcome webhook, ordre et casse compris). Tout `requestId` non
  nul des événements d'entrée et des corps de stubs doit être égal à
  `expected.requestId` (sauf I1C-04, volontairement invalide). Tout
  `documentId` trouvé récursivement dans les corps de stubs, y compris dans les
  tableaux `documents[]`, doit être égal à `expected.documentId`. L'état E2
  initial de I1C-02 reste l'exception documentée de cette comparaison. I1C-05
  conserve l'état du premier tour (`[dateNaissance, lieuNaissance]`) sur la
  première extraction, E1 et E2. E3 d'I1C-05 reste comparé à
  `expected.missingFields` (`[lieuNaissance]`). Le `caseNumber` de I1C-05 est
  **11** : 5 est déjà porté par I1D-05. Son stub E3 est un succès **200**
  (T6b), pas un 422.
- **FIX-02, exemption delta (lot L3).** Structurelle, sans identifiant de
  fixture. Un tour ≥ 2 est un événement d'entrée dont `requestId` est non nul.
  L'extraction de ce tour est l'OLLAMA en succès placé après le premier stub
  E2 (ordre du contrat : E2, puis extraction delta). Pour ce stub seulement,
  `missingFields` n'est pas comparé à `expected.missingFields`. Il doit
  re-déclarer, dans l'ordre canonique `prenom`, `nom`, `dateNaissance`,
  `lieuNaissance`, `nomIncorrect`, `nomCorrect`, toutes les clés obligatoires
  absentes de son propre `data` (règle `allOf`, `N8N_CONTRACTS.md` §5).
  I1C-02 : `["prenom", "nom", "nomIncorrect", "nomCorrect"]`. I1C-05, second
  OLLAMA : `["prenom", "nom", "lieuNaissance", "nomIncorrect", "nomCorrect"]`.
  `expected.missingFields` reste la liste backend (`[]` et `[lieuNaissance]`).
- **FIX-03 — payload analysé pour les PII.** Les marqueurs PII ne sont plus
  recherchés dans la fixture entière : le validateur construit un payload
  synthétique analysé à partir de `input` et `stubs` uniquement, en excluant
  totalement `securityProbe`. Les marqueurs doivent être au moins au nombre de
  quatre, non vides, uniques et présents dans ce payload analysé.
- **FIX-04 — contenu binaire synthétique.** La fixture I1D-10 porte un
  `documentBase64` synthétique (`UEsDBA==`) dans le corps du stub E5. Le
  validateur décode `securityProbe.base64`, exige
  `expectedMagicHex == "504B0304"`, vérifie que les octets décodés commencent
  par cette signature et que la même chaîne base64 figure dans le payload
  analysé (`input` + `stubs`). Aucun document réel n'est utilisé.
- **FIX-05 — ordre des étapes.** I1C-02 doit appeler E2 avant l'extraction
  OLLAMA et avant E3. I1C-05 doit appeler E2 avant la seconde extraction
  OLLAMA et avant E3. I1D-05 doit appeler E2 en premier et chaque E6 doit
  suivre une génération E2 (sémantique de tour).
- **FIX-06 — auto-tests à diagnostic spécifique.** Chaque mutation négative
  vérifie la présence du diagnostic attendu, et non simplement l'existence
  d'une erreur.

Le mode `-SelfTest` applique vingt-cinq mutations négatives en mémoire et
vérifie que le validateur les rejette avec le diagnostic attendu. Les deux
ajouts du lot L1 portent sur I1C-05 : ordre E2 avant la seconde extraction, et
refus d'un E3 qui ne serait pas un succès 200. Le lot L3 en ajoute quatre :
`message-place-absent`, `message-date-absent`, `delta-missing-undeclared`,
`error-code-absent`. Aucun fichier temporaire n'est écrit.

## Exécution

Validation statique :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tests\n8n\validate_i1_fixtures.ps1
```

Validation statique avec auto-tests négatifs :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tests\n8n\validate_i1_fixtures.ps1 -SelfTest
```

Un autre répertoire peut être fourni avec `-FixtureDir`.

Codes de sortie :

- `0` : toutes les vérifications passent ;
- `1` : au moins une vérification ou un auto-test échoue ;
- `2` : répertoire absent ou illisible.

Résultat de référence du 2026-10-24 (lot L3) :

```text
PASSED=12 FAILED=0
SELFTEST_PASSED=25 SELFTEST_FAILED=0
RESULT: PASS
```

## Lot L3 — cohérence message, delta et `code`

### Motif de naissance (contrôle C)

Le validateur n'inspecte que la phrase de naissance, jamais un « à » isolé
(« À partir de » ne matche pas). Motif, insensible à `Né` / `Née` :

- date : `né` ou `née`, puis `le`, puis `jj/mm/aaaa` ou `jj-mm-aaaa` ;
- lieu : la même phrase suivie de `à` minuscule puis d'un mot commençant par
  une majuscule (`Ville-Fictive`).

La date du message est comparée à `data.dateNaissance` en `yyyy-MM-dd`. Le mot
est comparé à `data.lieuNaissance`. L'appariement se fait depuis la fin entre
les événements et les OLLAMA en succès. Sans extraction réussie, le contrôle
ne s'applique pas.

| Fixture | Extraction réussie | Contrôle |
|---|---|---|
| I1C-01 | une, tour 1 | s'applique : `01/01/1980` et `Ville-Fictive` sont dans `data` ; passe |
| I1C-02 | une, après E2 (événement 2) | s'applique sur « née le 01/01/1980 à Ville-Fictive » ; passe. L'événement 1 n'a pas d'extraction appariée |
| I1C-03 | une, tour 1 | phrase de naissance absente ; contrôle non déclenché ; passe |
| I1C-05 | deux | événement 1 : phrase absente, non déclenché. Événement 2 : `Née le 01/01/1980` correspond à `1980-01-01` ; pas de `à <Mot>` ; passe |
| I1D-06 | une, tour 1 | s'applique : date et `Ville-Fictive` dans `data` ; passe |
| I1D-09 | une, tour 1 | s'applique sur la phrase de naissance, qui est dans `data` ; passe. Passeport et courriel hors motif |
| I1D-10 | une, tour 1 | s'applique : date et `Ville-Fictive` dans `data` ; passe |

Aucune fixture hostile n'a été modifiée pour ce contrôle.

### Corps 422 (contrôle B)

Les stubs E1 en `422` d'I1C-03 et d'I1C-05 portent
`"code": "MISSING_INFORMATION"` (`API_CONTRACTS.md` §2.3 et §3.1). Statut,
HTTP et `expected` inchangés. Aucun corps backend `400` dans ce corpus. Le
`400` d'E8 reste un `ExtractionValidationResponse`, hors de cette règle.

### I1D-09 — marqueurs de sonde

`securityProbe.markers` contient `FAKE-PP-0001` et `fictif@example.invalid`
(`i1d_09_fictitious_pii.json`, clés du tableau `markers`). Le validateur
exige chaque marqueur comme sous-chaîne du payload analysé (`input` + `stubs`
uniquement, `securityProbe` exclu). Il n'exige pas que la valeur soit dans
`data`. Les deux chaînes sont dans le message d'entrée ; cela suffit.

`contactEmail` est une clé valide de `data` (`requirements/API_CONTRACTS.md`
§2.1, exemple ligne 95). L'absence de `fictif@example.invalid` dans le `data`
OLLAMA est une **simulation délibérée** d'un LLM qui n'extrait pas un champ
optionnel. Le prompt d'extraction, contrainte 4
(`prompts/extraction/system_prompt_attestation_concordance.md` lignes 24-28),
n'inscrit dans `missingFields` que les six clés obligatoires ; une clé
optionnelle non émise reste absente de `data`. Seul `FAKE-PP-0001` est une
clé inconnue : `passeport` n'est pas une clé du schéma (pilote, scénario S12).

## Limites

Ce validateur prouve uniquement la cohérence statique du corpus. Il ne prouve
pas :

- l'exécution réelle du workflow n8n ;
- le respect effectif des retries, timeouts et de l'ordre des appels ;
- le comportement du backend Spring Boot ou de PostgreSQL ;
- l'absence réelle de PII dans les logs n8n ;
- l'absence réelle de binaire dans les execution data ;
- la couverture complète des gates I-C et I-D.

Ces points restent à couvrir par des tests d'exécution explicitement autorisés.
