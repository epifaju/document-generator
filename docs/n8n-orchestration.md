# N8N_ORCHESTRATION — Architecture d'orchestration n8n (Phase I.0)

| | |
|---|---|
| **Phase** | I.0 — Architecture et contrats **uniquement** (aucun workflow JSON, aucune implémentation) |
| **Statut** | PROPOSÉ — soumis à revue (Architect → Tester → Security → Reviewer, MAX_REVIEW_CYCLES = 3) |
| **Date** | 2026-10-04 |
| **Baseline git** | `e9bc6cc` (arbre propre avant création des livrables I.0) |
| **Périmètre fonctionnel** | Slice vertical unique : `ATTESTATION_CONCORDANCE` (AGENTS.md §5) |
| **Livrables I.0** | `docs/n8n-orchestration.md` (ce document) · `requirements/N8N_CONTRACTS.md` (contrat webhook v1) |
| **Aucune modification** | Java de production, migrations, templates DOCX, schéma d'extraction, prompts, sémantique E1–E8, workflow JSON |

---

## 1. Objet, périmètre, non-objectifs

**Objet** : définir comment n8n orchestre le cycle de vie complet d'une
demande d'attestation de concordance — de la réception du message
conversationnel jusqu'au DOCX téléchargeable — sans jamais être source de
vérité ni de règles métier.

**Inclus en I.0** : modèle d'état, flux heureux / boucle / reprise,
matrice des pannes, décomposition du workflow, frontière IA et adaptateur
de modèle, variables d'environnement, politique sécurité/PII minimal,
analyse d'idempotence, observabilité, stratégie de tests (gates
I-A…I-D), findings.

**Exclus (INTERDITS en I.0)** : `n8n/workflows/*.json`, code Java de
production, migrations, templates, `prompts/extraction/*`, changement de
sémantique des endpoints existants. Si un blocage exigeait une telle
modification → **STOP + rapport** (AGENTS.md §18) — *aucun blocage de ce
type n'a été identifié pendant l'analyse (§16)*.

**Non-objectifs produit (it.1)** : authentification, génération
asynchrone, PDF, autres types de documents — cf. contrat §7.

---

## 2. Sources de vérité et principes directeurs

| # | Principe | Référence |
|---|---|---|
| P1 | Le **backend est la source unique de vérité** : statut, données, validations, transitions T1–T14. n8n lit, ne calcule jamais d'état métier. | AGENTS.md §2, §4.1 |
| P2 | **Aucune invention de données** : toute absence → `missingFields` backend → question à l'utilisateur. n8n ne propose, ne déduit, ne complète jamais. | AGENTS.md §4.2, pilote §5.2 |
| P3 | **n8n = orchestration fine** : nœuds Code limités à formatage, parse strict et assemblage de prompt ; retries et timeouts sont de responsabilité n8n. | AGENTS.md §2, §11 |
| P4 | **Pas de conversion de date en n8n** : la normalisation (N1–N9) appartient au backend ; le prompt impose l'ISO et E8 l'impose. | Décision F-07 / R-06, API_CONTRACTS §2.4 |
| P5 | **LLM = extraction structurée uniquement**, jamais validation, autorisation, génération ; sortie = données, jamais instructions. | AGENTS.md §12, §4.3 |
| P6 | **Toute attente bornée**, toute exécution bornée, aucun retry ambigu (leçons Phase H.3). | Contrat §4 |
| P7 | **PII minimal** : la moindre donnée possible part vers l'LLM ; aucune valeur dans les logs. | AGENTS.md §13 |
| P8 | **Aucun secret dans l'export n8n** ; credentials = env / n8n credentials. | AGENTS.md §11, H.2 S-3 |

**Fichiers sources consultés (baseline I.0)** : `AGENTS.md` ·
`requirements/API_CONTRACTS.md` (v1) ·
`requirements/ATTESTATION_CONCORDANCE.md` ·
`requirements/N8N_CONTRACTS.md` (nouveau) · `docs/architecture.md` §1, §7,
§10, §12 · `prompts/extraction/attestation_concordance.schema.json` ·
`docker/docker-compose.yml`, `docker/.env.example` · contrôleurs
`DocumentRequestController`, `ExtractionController`, `HealthController`.

---

## 3. Modèle d'état

### 3.1 État persistant — backend (inchangé, jamais dupliqué)

Le cycle de vie `DRAFT → MISSING_INFORMATION | VALIDATED | REJECTED`,
`VALIDATED → GENERATED | FAILED`, `FAILED → VALIDATED` (T1–T14) est
**intégralement** celui du contrat pilote §9. n8n :

- ne stocke **jamais** le statut comme donnée de référence (il n'existe
  aucune copie « cache » du statut côté n8n) ;
- re-lecture systématique via `E2 GET /api/v1/requests/{id}` à chaque
  reprise de conversation (§3.3) ;
- rejette toute tentation de transition « à la place » du backend (ex. :
  ne passe pas de `FAILED` à `VALIDATED` lui-même — T12 est déclenché par
  `E4`).

### 3.2 État transitoire d'orchestration — par exécution n8n

Vivant uniquement dans l'exécution en cours (jamais persisté, jamais
partagé entre exécutions) :

```
RECEIVED → EXTRACTING → SHAPE_CHECK → [CREATING | COMPLETING]
        → (attente utilisateur = fin d'exécution, aucun état gardé)
        → GUARDING → GENERATING → VERIFYING → SETTLED
        ↘ ERROR (à toute étape, issue n8n-localisée)
```

| État transitoire | Nœud (§7 architecture) | Sortie |
|---|---|---|
| `RECEIVED` | 1 Webhook + validation ingress | `EXTRACTING` ou `INVALID_REQUEST` |
| `EXTRACTING` | 2 LoadPrompt · 3 OllamaExtract | `SHAPE_CHECK` ou `AI_EXTRACTION_ERROR` |
| `SHAPE_CHECK` | 4 ParseExtraction · 5 E8 · 6 IF | `CREATING`/`COMPLETING` ou erreur/skip type |
| `CREATING` | 7 E1 · 8 IF | `SETTLED` (201/400) · `ASKING` (422) |
| `COMPLETING` | 10 E3 (+ E2 de reprise) | routage par statut (§3.3) |
| `ASKING` | 9 Respond | fin d'exécution (aucun état n8n conservé) |
| `GUARDING` | 11 E4 | `GENERATING` ou boucle/REJECTED |
| `GENERATING` | 12 E5 | `VERIFYING` ou récupération T12 |
| `VERIFYING` | E6 (contrat §6) | `SETTLED` (`GENERATED`) ou erreur |
| `SETTLED` | 13 Finalize | réponse finale |

`PollState` (nœud 14, E2) est utilisable à tout moment de reprise — c'est
le **seul** mécanisme de reprise.

### 3.3 Table de routage à la reprise (E2 d'abord)

Tout message entrant portant un `requestId` exécute **E2 avant toute
autre chose** ; le statut backend décide seul de la suite :

| Statut lu (E2) | Action n8n | Issue |
|---|---|---|
| `MISSING_INFORMATION` | extraction delta (hint = `missingFields`) → E8 → E3 `PATCH` plat | selon PATCH (§5) |
| `VALIDATED` (non encore générée) | E4 (garde) → E5 → E6 | `GENERATED` |
| `VALIDATED` + `documents[]` non vide (cas limite) | route comme `GENERATED` | `GENERATED` |
| `GENERATED` | reprendre `documentId` de `documents[]` — si plusieurs entrées (fenêtre `F-I-9`) : la **plus récente** (`generatedAt` max), **un seul** `documentId` exposé → E6 vérif. | `GENERATED` |
| `FAILED` | E4 (T12) → si `VALIDATED` : E5 → E6 ; sinon route | `GENERATED` / `GENERATION_FAILED` / boucle |
| `REJECTED` | **aucun appel mutatif** (T14 terminal) | `REJECTED` |
| `DRAFT` (interne, jamais exposé normalement) | E4 → route | idem `VALIDATED` |
| `404 REQUEST_NOT_FOUND` (E2) | erreur d'entrée client : aucune persistance, log orchestration | `404 REQUEST_NOT_FOUND` |

---

## 4. Flux heureux (pas-à-pas normatif)

Conforme à `docs/architecture.md` §1.3, avec headers et mapping
d'egress du contrat (réf. `requirements/N8N_CONTRACTS.md`) :

```
Frontend → n8n   : POST /webhook/document-generation {message} (+X-Correlation-Id?)
n8n              : validation ingress, correlationId retenu (UUID v4 si absent)
n8n → Ollama     : POST {OLLAMA_BASE_URL}/api/chat — prompt système versionné
                   (PROMPTS_DIR/extraction/…), format = schéma d'extraction,
                   bornes AI_TIMEOUT_MS / AI_MAX_ATTEMPTS
n8n              : JSON.parse strict (échec ⇒ AI_EXTRACTION_ERROR, 0 appel backend)
n8n → Backend    : E8 POST /api/v1/extraction/validate (+X-Correlation-Id)
                   → 200 {valid:true}          [400 ⇒ errors[] contient ERR_DOCUMENT_TYPE_NON_SUPPORTE
                                                ? 200 UNSUPPORTED_DOCUMENT_TYPE : 502 EXTRACTION_SCHEMA_INVALID]
n8n → Backend    : E1 POST /api/v1/requests    → 201 {status:VALIDATED}
n8n → Backend    : E4 POST .../validate (garde) → 200 {VALIDATED}
n8n → Backend    : E5 POST .../generate         → 201 {documentId, GENERATED}
n8n → Backend    : E6 GET  .../documents/{id}   → 200 (vérification servabilité)
n8n → Frontend   : 200 outcome=GENERATED {requestId, documentId, downloadPath, …}
```

Règles : chaque appel backend porte `X-Correlation-Id` identique ;
chaque réponse **émise par le workflow** (succès comme échec — exception
native §3.1 du contrat : kill/transport exclu) porte `correlationId` en
corps et en-tête ; aucune valeur de `data` dans la réponse (le client
reçoit métadonnées + `downloadPath`, pas le binaire, contrat §6).

---

## 5. Boucle d'information manquante (bornée)

Conforme à `docs/architecture.md` §1.4 + contrat §5 :

1. **Round 1** : E1 `422` → backend persiste `MISSING_INFORMATION` +
   `requestId` (OQ-3 confirmée) → outcome `MISSING_INFORMATION` avec
   `missingFields` **ordonnés par le backend**, message FR à clés
   uniquement, aucune valeur suggérée.
2. **Rounds ≥ 2** : le client renvoie `requestId` → **E2 d'abord** (§3.3)
   → extraction **delta** : le prompt reçoit en hint les
   `missingFields` connus (métadonnées de schéma, **pas de PII**) +
   `message` utilisateur actuel ; **aucune donnée extraite auparavant
   n'est renvoyée au LLM** (minimisation).
   **Invariant de forme (chaque round)** : seul `data` est partiel —
   l'enveloppe doit rester valide en entier : `missingFields`
   re-déclare **toutes** les clés obligatoires encore absentes (règle
   `allOf` du schéma d'extraction, `ERR_CHAMP_OBLIGATOIRE_ABSENT`) ; le
   hint l'impose au modèle. Un round dont l'enveloppe respecte cet
   invariant ne peut pas échouer E8 **faute de déclaration** ; si le
   modèle omet malgré tout une clé obligatoire absente, E8 rejette
   déterministement (`502`, cas couvert par la fixture I-D).
3. E8 (garde de forme) → **E3 `PATCH` payload plat**, corps = **seuls les
   champs fournis** (API_CONTRACTS §3.3).
4. Routage PATCH : `VALIDATED` → E4 → E5 → E6 (fin de boucle) ;
   toujours `MISSING_INFORMATION` (T6b) → outcome `MISSING_INFORMATION`
   (round suivant) ; `REJECTED` → outcome `REJECTED` (terminal) ;
   `409 REQUEST_ALREADY_CLOSED` → E2 → route.
5. **Borne** : `MAX_CLARIFICATION_ROUNDS = 5` → outcome
   `CLARIFICATION_LIMIT_REACHED` (contrat §4).
   - Compteur stocké en *workflow static data* n8n (clé = `requestId`),
     **best-effort** : le flux **ne dépend jamais** de ce compteur pour
     sa **terminaison** (bornes par exécution + transitions backend
     suffisent ; finding `F-I-5` : pas de compteur de rounds côté
     backend).
   - **Observabilité — 2 assertions distinctes (gate I-C)** :
     (i) terminaison bornée **sans** compteur (toujours testable) ;
     (ii) outcome `CLARIFICATION_LIMIT_REACHED` seulement en mode où
     les *static data* persistent (webhook de production) — en mode de
     test sans persistance, (ii) est dégradée en « termine bornée sans
     compteur » et le cas reste tracé `F-I-5`.
6. **Terminaison naturelle** : toute fourniture complète des 6 champs
   obligatoires sort de la boucle par T5 (backend), indépendamment de la
   borne.

**Interdits de boucle** : inventer une valeur manquante · proposer des
valeurs « plausibles » · relancer E1 sur un `requestId` existant
(→ nouvelle demande interdite, OQ-2) · convertir une date entre les
rounds (P4) · persister un état de conversation dans n8n.

---

## 6. Matrice des pannes (routage)

Les **bornes chiffrées** (timeouts, tentatives) sont normatives dans le
contrat §4. Ce tableau décrit le **routage** ; « 0 backend » signifie
qu'aucune requête n'est émise vers E1–E6 avant l'échec.

| # | Étape | Échec observé | Traitement n8n | Issue | Notes / garanties |
|---|---|---|---|---|---|
| 1 | Ingress | corps invalide, clé inconnue, `message` > 4000, `requestId` malformé | rejet immédiat | `400 INVALID_REQUEST` | 0 backend |
| 2 | Ollama | timeout / `5xx` / connexion (3 tent.) | arrêt | `502 AI_EXTRACTION_ERROR` | 0 backend, 0 persistance |
| 3 | Parse | sortie LLM non JSON strict | arrêt | `502 AI_EXTRACTION_ERROR` | aucun « parse au mieux » |
| 4 | E8 | `400` + `ERR_DOCUMENT_TYPE_NON_SUPPORTE` | message type | `200 UNSUPPORTED_DOCUMENT_TYPE` | 0 création |
| 5 | E8 | `400` dont `errors[]` **sans** `ERR_DOCUMENT_TYPE_NON_SUPPORTE` | arrêt | `502 EXTRACTION_SCHEMA_INVALID` | 0 création, `errors[]` loggé (sans PII) |
| 6 | E8 | `5xx`/timeout | retry borné (2) | `503 BACKEND_UNAVAILABLE` | pure → sûre |
| 7 | E1 | `422` | question | `200 MISSING_INFORMATION` | persistée (OQ-3) → boucle §5 |
| 8 | E1 | `400` + `requestId` | rejet métier | `200 REJECTED` | persistée T4 (audit) |
| 9 | E1 | `400` sans `requestId` | rejet enveloppe | `400 INVALID_REQUEST` | non persistée |
| 10 | E1 | `5xx` **reçu** | retry (2) puis arrêt | `503 BACKEND_UNAVAILABLE` | tx annulée → retry sûr **sauf échec post-commit (audit)** → doublon tracé `F-I-1` |
| 11 | E1 | timeout / connexion perdue | **aucun retry** | `503` + `correlationId` | ambiguïté → réconciliation audit (`F-I-1`) |
| 12 | E3 | `200` | route par statut (§3.3/§5) | `GENERATED`/`MISSING`/`REJECTED` | jamais de DRAFT (T-…) |
| 13 | E3 | `400` structurel (clé inconnue/réservée) | **bug n8n** (il n'envoie que des clés du schéma) | `400 INVALID_REQUEST` + log ERROR | 0 mutation (contrat F-03) |
| 14 | E3 | `400` métier | rejet | `200 REJECTED` | persistée T6/T10 |
| 15 | E3 | `409 REQUEST_ALREADY_CLOSED` | E2 → route | `GENERATED`/`REJECTED` | jamais de décision sur la réponse 409 seule |
| 16 | E3 | timeout | E2 réconciliation | route | requestId connu → pas d'ambiguïté ; `500 DATABASE_ERROR` = conflit `@Version` possible (`F-I-3`) → **1 retry 5xx borné (§4)** puis E2, jamais « backend down » ni `503` |
| 17 | E4 | `200` | E5 | suite | garde T11 |
| 18 | E4 | `422` / `400` | question / rejet | `200 MISSING_INFORMATION` / `200 REJECTED` | T6b / T4–T6–T12b |
| 19 | E4 | `409 INVALID_STATUS` | E2 → route | selon statut | ex. `GENERATED` → succès |
| 20 | E4 | `5xx`/timeout | retry borné (3, idempotent) | `503` | T11 sûr |
| 21 | E5 | `201` | E6 vérif. → fin | `200 GENERATED` | chaîne §4 |
| 22 | E5 | `500 TEMPLATE_NOT_FOUND`/`DOCUMENT_GENERATION_ERROR` (→ `FAILED`, T9) | **1** récupération : E4 (T12) → E5 | `200 GENERATED` si réussi, sinon `200 GENERATION_FAILED` + `cause` | reprise conforme §1.5 architecture |
| 23 | E5 | `409 INVALID_STATUS` | E2 : `GENERATED` → E6 → succès ; sinon route | variable | double livraison résolue sans double document |
| 24 | E5 | timeout | E2 poll borné (3×5 s, contrat §4) → route comme 23 ; toujours `VALIDATED` → 1 retry E5 | variable | jamais de retry E5 « à l'aveugle » ; fenêtre TOCTOU possible (2 générations) → outcome limité à **1** `documentId` (plus récent, `F-I-9`) |
| 25 | E5 | `404` | `INTERNAL_ERROR` | `500` | bug d'orchestration (requestId déjà validé) |
| 26 | E6 | `5xx`/timeout post-`201` | retry borné (3) | `503` + `requestId` | ne **jamais** déclarer `GENERATED` sans vérif. |
| 27 | E6 | `404` post-`201` | `INTERNAL_ERROR` + log | `500` | incohérence serveur, pas de faux succès |
| 28 | Toute E1–E6 | connexion refusée (backend down) | retries bornés (§4) | `503 BACKEND_UNAVAILABLE` | sans stack trace, avec `correlationId` |
| 29 | Boucle | `MAX_CLARIFICATION_ROUNDS` atteint | arrêt dialogue | `200 CLARIFICATION_LIMIT_REACHED` | plus de PII échangée |
| 30 | Exécution | `EXECUTIONS_TIMEOUT` (900 s, contrat §4) | kill borné | erreur **native n8n** sans enveloppe `outcome` (contrat §3.1) | aucune exécution suspendue (H.3) |
| 31 | Toute E1–E6 | statut backend **non listé** ci-dessus | (request-scoped) E2 d'abord ; sinon résolution directe → arrêt | `500 INTERNAL_ERROR` + log du statut reçu | jamais de chute silencieuse ; gate I-D |

---

## 7. Décomposition du workflow (options I01–I06)

### 7.1 Critères d'évaluation

| # | Critère |
|---|---|
| C1 | Conformité AGENTS.md §2/§11 : orchestration n8n fine, logique métier backend, Code nodes maigres |
| C2 | Surface PII (volume de données personnelles par unité d'orchestration et par exécution) |
| C3 | Exécution bornée et reprise (clarté de la table §3.3, leçons H.3) |
| C4 | Testabilité des gates I-B…I-D (fixtures déterministes, isolation) |
| C5 | Fidélité aux contrats existants (0 changement backend, 0 nouveau endpoint) |
| C6 | Complexité opérationnelle (export/import n8n, versioning, credentials, relecture) |
| C7 | Coût d'évolution it.2 (JWT, async 202, nouveaux types de documents) |

### 7.2 Options évaluées

| ID | Décision | Description | Évaluation |
|---|---|---|---|
| **I01** | **RETENUE** | **Workflow monolithique unique** : webhook → extraction → E8 → E1/E3 → E4 → E5 → E6 → respond (≈14 nœuds, structure existante `docs/architecture.md` §7.1) | C1 ✅ (nœuds Code = parse/format/prompt uniquement) · C2 ✅ (une seule exécution = un seul message LLM) · C3 ✅ (états transitoires tous dans une exécution, reprise = E2) · C4 ✅ (I-B = une exécution) · C5 ✅ (aucun nouveau endpoint) · C6 ✅ (un export JSON, credentials absents) · C7 ⚠️ acceptable (voir chemin de migration) |
| I02 | écartée | Deux workflows séquentiels : `extract` (webhook+LLM+E8) puis `fulfil` (E1→E6) | C3 ❌ (passage d'état entre workflows = persistance artificielle, source de vérité divergente) · C6 ❌ (2 exports, contract de liaison interne à inventer) · C4 ❌ (I-B chevauche deux exécutions) |
| I03 | écartée | Workflow par phase : `entry` (round 1) + `resume` (rounds ≥ 2) | C3 ❌ (logique de routage dupliquée entre les deux) · C4 ❌ (I-C devient multi-workflow) · C7 ⚠️ même coût que I06 pour moins de gain |
| I04 | écartée | Backend pilote : n8n se limite à webhook + LLM, Spring enchaîne E1→E6 | C1 ❌ **viole AGENTS.md §2** (« n8n : orchestration… ») · C5 ❌ (nouveau endpoint d'entrée backend requis) · C7 ❌ |
| I05 | écartée | Asynchrone : webhook → file d'attente → worker, réponse différée | C5 ❌ (contredit ADR-12 : génération synchrone `201`, OQ-API-2 reportée it.2) · C3 ❌ (deux canaux de réponse à orchestrer) |
| I06 | **différée** | Entrée + sous-workflows : `ai-extract`, `request-lifecycle` (sub-workflows n8n) | C2 ✅✅ (moindre surface PII par unité) · C6 ⚠️ (3 exports, versions liées) · C4 ⚠️ (composition à mocker) · **identique fonctionnellement à I01** → surcoût non justifié en it.1 |

### 7.3 Décision

**I01 — un seul workflow `document-generation-v1.json`**, avec une
**structure interne en groupes de nœuds** (`ingress`, `ai-extract`,
`request-lifecycle`, `finalize`) calqués sur I06 : la migration vers I06
en it.2 (ou dès que le volume l'exige) ne sera qu'un découpage mécanique
des groupes, sans changement de contrat ni de routage.

---

## 8. Frontière IA et adaptateur de modèle

### 8.1 Chaîne d'extraction

```
[2 LoadPrompt]  lecture SEULE de ${PROMPTS_DIR}/extraction/
                system_prompt_attestation_concordance.md
                (montage ../prompts:/prompts:ro, source unique versionnée)
       ↓
[3 OllamaExtract] POST {OLLAMA_BASE_URL}/api/chat
                model = ${OLLAMA_MODEL}
                format = schéma d'extraction (JSON Schema draft 2020-12)
                bornes : ${AI_TIMEOUT_MS}, ${AI_MAX_ATTEMPTS}
                adaptateur = ${AI_PROVIDER}  (it.1 : « ollama » seulement)
       ↓
[4 ParseExtraction] JSON.parse strict — échec ⇒ AI_EXTRACTION_ERROR
       ↓
[5 E8] POST /api/v1/extraction/validate  (garde de forme, contrat §3.8)
       ↓
[6 IF] errors[] contient ERR_DOCUMENT_TYPE_NON_SUPPORTE ? → UNSUPPORTED_DOCUMENT_TYPE
       valid=false (autre violation) ?                → EXTRACTION_SCHEMA_INVALID (0 création)
       valid=true                                     → E1/E3
```

### 8.2 Règles de frontière (inviolables)

| # | Règle |
|---|---|
| A1 | **Aucune conversion de date en n8n** (P4) : `dd/MM/yyyy` → rejet E8, normalisation = backend (N3) |
| A2 | **Aucune branche sur `confidence`** en it.1 : OQ-5 est ouverte (« à définir hors contrat pilote ») ; inventer un seuil = inventer une règle. `confidence` est transmise, jamais utilisée pour contourner une validation (pilote §6) |
| A3 | Sortie LLM = **données** : validée par E8 **avant** tout usage ; aucune instruction du texte LLM n'est interprétée (anti prompt-injection : ni exécution de code, ni appel réseau, ni routage basé sur son contenu — seuls les codes d'erreur E8 décident, §6 lignes 4-5) |
| A4 | Hint de round = **clés** `missingFields` uniquement (§5.2) — jamais de données précédemment extraites revues vers l'LLM |
| A5 | Le prompt système n'est modifiable que sous `prompts/` (versionné, revu séparément) ; le workflow ne contient **pas** de copie du prompt |
| A6 | Échec = arrêt propre (`AI_EXTRACTION_ERROR`) : jamais de « best effort » sur un JSON approximatif |
| A7 | `documentType` = `const` du schéma : toute autre valeur → outcome `UNSUPPORTED_DOCUMENT_TYPE`, zéro appel E1 |
| A8 | Adaptateur `AI_PROVIDER` : it.1 implémente `ollama` ; toute autre valeur → `INTERNAL_ERROR` explicite au démarrage (fail-fast, jamais de fallback silencieux vers un autre fournisseur) |

---

## 9. Variables d'environnement (reconciliation)

Inspectées : `docker/docker-compose.yml` (§10 architecture) et
`docker/.env.example`. **Aucune variable existante n'est dupliquée ni
renommée** (le rename casserait `RuntimeSecurityContractTest` et §10.2).

| Variable | Statut | Défaut | Rôle / justification |
|---|---|---|---|
| `BACKEND_BASE_URL` | **existant — réutilisé** | `http://backend:8080` | base de tous les appels E1–E8 |
| `OLLAMA_BASE_URL` | **existant — réutilisé** | `http://ollama:11434` | endpoint du modèle |
| `OLLAMA_MODEL` | **existant — réutilisé** | `llama3.1` | nom du modèle (it.1) |
| `PROMPTS_DIR` | **existant — réutilisé** | `/prompts` | montage lecture seule du prompt |
| `N8N_ENCRYPTION_KEY` | **existant — conservé fail-fast** (`:?…`, H.2 S-3) | — (aucun défaut) | chiffrement des credentials n8n |
| `N8N_BASIC_AUTH_*`, `N8N_VERSION` | **existant — conservés** | — | éditeur / version — **à vérifier en I.1** : n8n ≥ 1.0 a retiré le basic-auth (image `latest`) → variables peut-être **inertes** ; protection éditeur effective = compte propriétaire + loopback (`F-I-10`) |
| `AI_PROVIDER` | **NOUVELLE — proposée (I.1)** | `ollama` | sélecteur d'adaptateur d'extraction (§8.2 A8) ; future extension sans changer le workflow |
| `AI_TIMEOUT_MS` | **NOUVELLE — proposée (I.1)** | `60000` | borne unique d'appel LLM (contrat §4) |
| `AI_MAX_ATTEMPTS` | **NOUVELLE — proposée (I.1)** | `3` | tentatives LLM bornées (contrat §4) |
| `EXECUTIONS_TIMEOUT` | **NOUVELLE — proposée (I.1)** | `900` | plafond d'exécution n8n — **nom réel n8n** (`EXECUTION_TIMEOUT` n'existe pas, ignoré silencieusement) ; 900 s couvre le pire cas **par exécution** (≈330 s, retries bornés §4 inclus ; agrégat 5 rounds ≈455 s sans retry = 5 exécutions, indicatif, contrat §4) (H.3 borné) |
| `EXECUTIONS_DATA_PRUNE` / `EXECUTIONS_DATA_MAX_AGE` | **NOUVELLES — proposées (I.1)** | `true` / `24` | purge des données d'exécution n8n contenant PII (§10) |
| `EXECUTIONS_DATA_SAVE_ON_SUCCESS` / `EXECUTIONS_DATA_SAVE_MANUAL_EXECUTIONS` | **NOUVELLES — proposées (I.1)** | `none` / `false` | ne persister ni les exécutions réussies ni les exécutions manuelles (tests) → réduit le résidu PII (§10 SEC-5, `F-I-8`) |
| `DB_SQLITE_VACUUM_ON_STARTUP` | **NOUVELLE — proposée (I.1)** | `true` | libère réellement l'espace des pages purgées (SQLite ne le fait pas sinon, `F-I-8`) |
| `N8N_DIAGNOSTICS_ENABLED` | **NOUVELLE — proposée (I.1)** | `false` | pas de télémétrie sortante |
| `AI_MODEL` | **REJETÉE** | — | **doublon exact d'`OLLAMA_MODEL`** (§10.2 + `RuntimeSecurityContractTest`) ; introduire un second nom de modèle = deux sources de vérité contradictoires |
| `BACKEND_HTTP_TIMEOUT`… | **REJETÉES** | — | bornes backend = constantes du workflow (contrat §4), pas d'explosion d'env |
| `POSTGRES_*` côté n8n | **REJETÉES** | — | n8n n'accède **jamais** à PostgreSQL directement (AGENTS.md §2 : tout passe par l'API) |

Les ajouts d'env à `docker-compose.yml` / `.env.example` sont un
travail d'**implémentation I.1**, pas I.0.

**Notes de périmètre (I.1)** :
- `N8N_BLOCK_FILE_ACCESS_TO_N8N_FILES` : rester `true` (défaut), ne
  jamais le désactiver (montages `../prompts` / `../n8n` lecture seule).
- Le workflow lit sa config via `$env` ; le gate I-A **interdit** toute
  référence `$env` / `process.env` vers `N8N_*` ou `POSTGRES_*`
  (y compris `N8N_ENCRYPTION_KEY`) ; l'option
  `N8N_BLOCK_ENV_ACCESS_IN_NODE` sera évaluée en I.1.

---

## 10. Sécurité et PII minimal

| # | Mesure | Détail | Ligne d'origine |
|---|---|---|---|
| SEC-1 | Périmètre réseau | n8n `127.0.0.1:5678`, ollama `127.0.0.1:11434`, backend `127.0.0.1:8080` ; échanges inter-conteneurs via `adgendoc-internal` uniquement | H.2 S-2, architecture §10.1/§12 |
| SEC-2 | Secrets | `N8N_ENCRYPTION_KEY` sans défaut (fail-fast) ; aucun credential dans l'export JSON (champ `credentials` absent) ; `POSTGRES_PASSWORD` jamais exposé à n8n | H.2 S-3, AGENTS.md §11 |
| SEC-3 | PII vers l'LLM | **Un seul message utilisateur + hint de clés** par appel ; aucune donnée backend, aucun historique (§5.2, A4) | AGENTS.md §13, P7 |
| SEC-4 | PII dans les logs n8n | allowlist stricte (§12) : jamais `message`, valeurs `data`, prompt, sortie LLM brute, bodies backend | AGENTS.md §13, §15 |
| SEC-5 | PII stockée (execution data) | Les données d'exécution n8n (inputs de nœuds ≈PII) sont stockées **en clair** dans SQLite — `N8N_ENCRYPTION_KEY` ne chiffre que les **credentials**. Contrôles : boucle + accès éditeur (compte propriétaire, `F-I-10`) + purge `EXECUTIONS_DATA_PRUNE=true` / `MAX_AGE=24h` + `EXECUTIONS_DATA_SAVE_ON_SUCCESS=none` + `EXECUTIONS_DATA_SAVE_MANUAL_EXECUTIONS=false` + `DB_SQLITE_VACUUM_ON_STARTUP=true` (§9) ; résidu documenté `F-I-8` | constat n8n |
| SEC-6 | Binaire DOCX | jamais relayé par le webhook (contrat §6) → ni réponse, ni données d'exécution n8n ne contiennent le document | minimisation |
| SEC-7 | Anti prompt-injection | sortie LLM = données validées par schéma avant usage ; aucun code/URL/commande issu du LLM n'est exécuté (A3) | AGENTS.md §12 |
| SEC-8 | Ingress borné | `message` ≤ 4000 car., clés racine strictes, `X-Correlation-Id` formaté (anti log-injection) | contrat §1.1/§2 |
| SEC-9 | Pas de stack/PII en réponse | messages FR fixes ; E1/E3/E4/E5 statuses routés, **corps backend complets** jamais relayés (contrat §3.1) | architecture §12 |
| SEC-10 | Webhook non authentifié (it.1) | accepté **uniquement** par loopback + réseau interne ; fermeture obligatoire avant prod | finding `F-I-6` (escalade security) |
| SEC-11 | E6 ouvert (it.1) | risque assumé R-10, borné loopback ; n8n ne devient **pas** un second point de diffusion (SEC-6) | API_CONTRACTS §1.3 |
| SEC-12 | Sanitisation n8n | erreurs backend réceptionnées : `error.code` + `message` affichable seuls ; corps complet en log **jamais** (alignement log-sanitization H.2 S-1) | H.2 S-1 |
| SEC-13 | Flux PII entrant principal | Chaque reprise appelle E2 qui renvoie l'objet `data` **complet** (OQ-API-4, R-09) dans n8n → n8n n'en extrait que `status` / `missingFields` / `documents[]`, **jamais** de re-feed LLM (A4) ; résumé backend = escalade avant prod (`OQ-API-4`) | OQ-API-4 |

---

## 11. Idempotence et exécutions dupliquées

### 11.1 Menaces

1. Relance client du webhook (timeout réseau) → exécution n8n doublée.
2. `retryOnFail` n8n interne sur un appel dont l'issue est inconnue.
3. Double appel de génération → double document ou `409` inexpliqué.

### 11.2 Analyse par endpoint

| Endpoint | Nature | Double appel = ? | Règle n8n (contrat §4) |
|---|---|---|---|
| E8 | pure (sans effet de bord) | identique | retry libre borné |
| E1 `POST /requests` | **création non idempotente** | **2 demandes distinctes** (2 `requestId`) | retry **seulement** si `5xx` reçu (tx annulé) ; **timeout → 0 retry** + réconciliation `correlationId` → finding `F-I-1` (pas de clé d'idempotence dans le contrat v1) |
| E3 `PATCH` | merge idempotent à payload identique | idem (payload identique) ; payloads concurrents ⇒ conflit `@Version` (résulte en `500 DATABASE_ERROR`, `F-I-3`) | retry sur `5xx` ; timeout → E2 ; concurrence → finding `F-I-3` (verrou `@Version` non exposé au contrat) |
| E4 `POST .../validate` | idempotent (T11) | identique | retry libre borné |
| E5 `POST .../generate` | effet terminal `GENERATED` (T13) | 1er → `201` ; 2e → `409 INVALID_STATUS` **sans** second document | **409/timeout → E2 d'abord** ; `GENERATED` → succès via E6 (§6 l.23-24) ; finding `F-I-2` (réponse non idempotente → atténué en n8n) |
| E6 `GET .../documents` | lecture seule | identique | retry libre borné |
| Ollama | inference | coût seul | retry libre borné |
| E2 `GET` | lecture seule | identique | retry libre borné |

### 11.3 Règles de conception anti-doublon (normatives)

- **R1** : aucune décision n8n sur un timeout — seulement après E2
  (E3/E5) ou jamais (E1).
- **R2** : `409` n'est jamais un échec : c'est un signal de routage E2.
- **R3** : la déclaration `GENERATED` exige E5 `201` **ou** E2
  `GENERATED`, **et** E6 `200` (vérif. servabilité).
- **R4** : les retries n8n sont activés nœud par nœud avec la condition
  exacte du tableau §4 — jamais un `retryOnFail` global.
- **R5** : l'exécution n8n ne contient qu'un seul `message` et qu'un seul
  round : aucun batch, aucune boucle interne de resoumission.

### 11.4 Écarts contractuels constatés (→ findings, aucun fix en I.0)

`F-I-1` (E1 sans clé d'idempotence), `F-I-2` (réponse E5 non
idempotente — atténué), `F-I-3` (PATCH sans verrou) : ces **gaps API**
sont rapportés pour arbitrage humain ; toute correction exigerait une
évolution d'`API_CONTRACTS.md` → hors périmètre I.0 (§15).

---

## 12. Observabilité et correlationId

### 12.1 Cycle de vie

```
ingress (X-Correlation-Id? → UUID v4 si absent)
  → tous les appels backend portent X-Correlation-Id identique
  → le backend l'échoit en réponse + ErrorResponse.correlationId + audit
  → réponse n8n (émise par le workflow, exception §3.1 contrat) : en-tête X-Correlation-Id + corps correlationId
  → logs n8n : correlationId à chaque ligne de l'exécution
```

Corrélation de bout en bout possible : `correlationId` → ligne
`document_request`/`audit_log` backend (E1 le transmet) → exécution n8n
→ réponse client.

### 12.2 Allowlist (seules métadonnées loggables)

`correlationId` · `requestId` · `documentType` · `workflowId` ·
`executionId` · étape/nœud · `outcome` · `error.code` · `status` backend ·
`httpStatus` des appels · `durationMs` · `retryCount` · **clés** de
`missingFields` (jamais leurs valeurs) · compteur de rounds.

### 12.3 Denylist (interdit absolu dans les logs n8n)

texte du `message` utilisateur · toute valeur de `data` (prénom, nom,
dates, lieux…) · contenu du prompt · sortie LLM brute · corps de réponse
backend (ils peuvent ré-echoer `data`) · en-têtes `Authorization` ·
credentials/env · binaire DOCX · stack traces n8n vers l'utilisateur ·
**messages d'exception journalisés côté n8n** (erreurs `JSON.parse`
embarquant un extrait d'entrée, erreurs HTTP embarquant un corps de
réponse) : seuls classe d'erreur + étape + `correlationId` sont
loggables. Le gate I-D scanne les **sous-chaînes PII des fixtures**,
pas seulement des mots-clés.

### 12.4 Classification d'erreur (AGENTS.md §14)

Mapping outcom/code ↔ HTTP : contrat §3.2-§3.4. Toute erreur loggée
respecte : un code, un `correlationId`, zéro PII, zéro stack côté
client. Les erreurs `BACKEND_UNAVAILABLE` / `INTERNAL_ERROR` sont
distinguées dès les premières lignes d'exécution (décision d'exploitabilité
qui justifie le code local `BACKEND_UNAVAILABLE`).

### 12.5 Limites connues

Les *execution data* n8n contiennent les inputs de nœuds (PII) —
**intrinsèque** au fonctionnement n8n → mitigation SEC-5 + finding
`F-I-8` (pas de journal « allowlist » possible sur ces fichiers : ils ne
sont pas des logs, ils sont purgés à durée de vie courte).

---

## 13. Stratégie de tests — gates I-A…I-D

Règles (AGENTS.md §16) : comportements **observables** (corps, codes,
états backend, absence de PII), jamais les détails d'implémentation ;
fixtures déterministes — **aucun modèle live requis** : `OLLAMA_BASE_URL`
pointe vers un **stub local** qui sert les fixtures JSON de
`docs/architecture.md` §11.2 (une exécution = une réponse du modèle) ;
exécution du stack via le harnais de vie Phase H.3
(`tests/e2e/ServerLifecycle.psm1`), bornes H.3 applicables aux gates,
avec les **clés d'évidence H.3** exigées par gate (`TEST_EXIT_CODE`,
`CLEANUP_EXIT_CODE`, `PORT_*_FREE`, `PROJECT_JVM_LEFT`).

**Canaux d'observation nommés** : corps/headers HTTP (ingress et
egress) · compteurs du stub (appels Ollama / E1–E8) · comptages SQL
`document_request` / `audit_log` · scan de la sortie du nœud log de
l'exécution n8n · ordre des nœuds dans les *execution data*.

**Catalogue des fixtures d'extraction (existant, versionné) :**
- `extraction_valid_complete.json` — happy path (6 champs obligatoires + optionnels)
- `extraction_missing_nom_correct.json` — 1 champ obligatoire absent (nomCorrect)
- `extraction_malformed.json` — JSON tronqué/invalide
- `extraction_invalid_date.json` — date en `dd/MM/yyyy` au lieu d'ISO (violation schéma)
- `extraction_unsupported_document_type.json` — `documentType: "ACTE_NAISSANCE"`
- `extraction_unknown_key.json` — clé `passeport` non déclarée dans le schéma
- `extraction_missing_undeclared.json` — `prenom` absent MAIS non listé dans `missingFields`
- `extraction_confidence_out_of_range.json` — `confidence: 1.5` (hors [0,1])

**Régression (AGENTS.md §16)** : chaque campagne de gate exécute aussi
les suites existantes (`mvn -f backend/pom.xml test`, gates 1–3,
`tests/e2e/lifecycle_tests.ps1` L1–L8) et rapporte leur statut.

**Autorisation (AGENTS.md §16)** : couverture `authorization` **différée
en it.1** (webhook et E6 ouverts, périmètre loopback — contrats §1.3 ;
findings `F-I-6` / `F-I-10`) ; ré-exigée par la checklist de mise en
production (jeton webhook **et** autorisation E6).

| Gate | Objectif | Entrées | Assertions clés |
|---|---|---|---|
| **I-A — Contrat statique du workflow** | Le JSON (dès I.1) respecte le contrat **avant** toute exécution | `n8n/workflows/*.json` + env | JSON parse OK ; champ `credentials` absent partout ; seules les variables de §9 référencées (aucun `AI_MODEL`, aucune URL hardcodée, aucun secret) ; chemins E1–E8 exacts ; `responseMode: responseNode` ; timeout/retry renseignés conformes au contrat §4 ; fichier prompt `prompts/extraction/system_prompt_…md` présent ; encodage UTF-8 sans BOM (leçon H.3) ; `X-Correlation-Id` posé sur chaque HTTP node ; **aucune** référence `$env` / `process.env` vers `N8N_*` ou `POSTGRES_*` (§9) |
| **I-B — Bout-en-bout heureux** | La chaîne §4 produit un DOCX téléchargeable | webhook + fixture extraction complète (6 champs valides) | exécution unique → `200 GENERATED` ; `requestId`/`documentId` UUID ; E2 = `VALIDATED`→`GENERATED` ; `downloadPath` E6 → `200` (**éventuel**, ≤ 3 tentatives §4) + `Content-Disposition` + MIME DOCX + octets ≠ 0 ; `correlationId` identique ingress→réponses backend→egress **et** `X-Correlation-Id` fourni à l'ingress survit inchangé E1→E8 (filtre backend non déclenché) ; transition `VALIDATED→GENERATED` observée par lecture E2 **avant et après** l'exécution ; **0** champ PII dans les logs de l'exécution ; **0** binaire dans les *execution data* (aucune signature `PK\x03\x04`, contrat §6) |
| **I-C — Boucle d'information manquante** | T5/T6b bornés, aucune invention | **Fixtures de conversation (niveau webhook, à créer en I.1) :**<br/>• `round1_missing_datenaissance_lieunaissance.json` — webhook sans `requestId`, extraction → E8 → E1 422<br/>• `round2_complete.json` — webhook **avec** `requestId`, extraction delta (dateNaissance+lieuNaissance) → E8 → E3 plat → VALIDATED → GENERATED<br/>• `round2_incomplete.json` — webhook avec `requestId`, extraction delta partielle (1 seul champ) → E8 → E3 → MISSING_INFORMATION<br/>• `round2_invalid_date.json` — webhook avec `requestId`, extraction delta date invalide (dd/MM/yyyy) → E8 502 → outcome ERROR<br/>• `round2_rejected_guard.json` — webhook avec `requestId`, extraction delta valide mais E4 422 (règle métier) → REJECTED<br/>• `round2_stale_missingfields.json` — webhook avec `requestId`, extraction `data` complète MAIS `missingFields` déclare encore des clés **déjà présentes** (stale/over-declared) → E8 **200 valid** (invariant `allOf` : sur-déclaration autorisée)<br/>• `round2_e4_422.json` — webhook avec `requestId`, E3 200 puis E4 422 → MISSING_INFORMATION (garde T6b) | round 1 → `422` backend, outcome `MISSING_INFORMATION`, `missingFields` ordonnés identiques au backend ; round 2 → E3 **plat**, seuls champs fournis, puis `GENERATED` ; variante T6b → 2e question ; variante `REJECTED` → outcome `REJECTED` terminal ; 0 valeur inventée ; **boucle en 2 assertions** : (i) terminaison bornée **sans** compteur (toujours), (ii) `CLARIFICATION_LIMIT_REACHED` en mode persistance des *static data* (webhook de production) — sans compteur, (i) reste exigée (§5, étapes 5–6) ; fixture « round 2 : `data` **complet** mais `missingFields` en retard (stale/sur-déclaré) → **reste vivant**, E8 200 valide » (invariant §5, étape 2 : sur-déclaration de clés absentes autorisée ; la variante clé absente **non déclarée** → `502`, couverte en I-D) ; variante `E4 422` → `MISSING_INFORMATION` (garde T6b) |
| **I-D — Pannes, idempotence, sécurité** | Matrice §6 + règles §11 + SEC-4 | **Fixtures hostiles d'orchestration (à créer en I.1) :**<br/>• Extraction-level (existant) : `extraction_malformed.json`, `extraction_invalid_date.json`, `extraction_unsupported_document_type.json`, `extraction_unknown_key.json`, `extraction_missing_undeclared.json`, `extraction_confidence_out_of_range.json`<br/>• **Réseau/Backend stub (nouveau) :**<br/>  - `ollama_unreachable` — stub Ollama refuse la connexion / timeout<br/>  - `backend_e8_5xx` — stub backend E8 renvoie 500/503 (2 tentatives)<br/>  - `backend_e1_5xx_received` — stub backend E1 renvoie 500 (réponse reçue → retry 1×)<br/>  - `backend_e1_timeout` — stub backend E1 ne répond pas (0 retry, réconciliation correlationId)<br/>  - `backend_e3_5xx_version_conflict` — stub backend E3 renvoie 500 DATABASE_ERROR (@Version) → retry 1× puis E2<br/>  - `backend_e3_409_closed` — stub backend E3 renvoie 409 REQUEST_ALREADY_CLOSED → E2 → route<br/>  - `backend_e4_5xx` — stub backend E4 renvoie 500 (3 tentatives)<br/>  - `backend_e5_500_transient` — stub backend E5 : 1er appel 500 TEMPLATE_NOT_FOUND, 2e appel 201<br/>  - `backend_e5_500_persistent` — stub backend E5 : 2 appels 500 → GENERATION_FAILED<br/>  - `backend_e5_timeout` — stub backend E5 timeout → E2 polling (3×5s) → route<br/>  - `backend_e5_409_generated` — stub backend E5 409 INVALID_STATUS + E2 GENERATED → succès via E6<br/>  - `backend_e6_404_post201` — stub backend E6 404 après E5 201 → 500 INTERNAL_ERROR<br/>  - `backend_unknown_status` — stub backend renvoie statut non listé → catch-all 500<br/>  - `backend_down` — stub backend injoignable → 503 BACKEND_UNAVAILABLE borné<br/>• **Idempotence/Duplication (nouveau) :**<br/>  - `duplicate_webhook_with_requestid` — 2 webhooks identiques avec `requestId` → 1 seule document_request<br/>  - `duplicate_webhook_no_requestid` — 2 webhooks sans `requestId` → 2 document_request (limite F-I-1)<br/>• **Sécurité (nouveau) :**<br/>  - `pii_scan_fixtures` — corpus de sous-chaînes PII extraites des fixtures pour scan denylist logs n8n<br/>  - `binary_scan` — vérification absence signature `PK\x03\x04` dans execution data | JSON malformé → `502 AI_EXTRACTION_ERROR` + **0 ligne E1** (comptage SQL `document_request`/`audit_log`) ; schéma violé → `502 EXTRACTION_SCHEMA_INVALID`, 0 création ; type → `UNSUPPORTED_DOCUMENT_TYPE`, 0 création ; Ollama injoignable → `502` borné (`AI_TIMEOUT_MS`×`AI_MAX_ATTEMPTS`), **0 appel backend** (compteurs stub) ; E5 `500` → récupération T12 → `GENERATED` (branche transitoire : premier `500` injecté au stub réseau, second `201`) ou `GENERATION_FAILED` si persistant ; **livraison dupliquée épinglée** : round **avec `requestId`** (PATCH idempotent + E2 en tête) → **1 seule** ligne `document_request`, outcome `GENERATED` ; variante round 1 **sans** `requestId` → **2 lignes attendues** (limite documentée `F-I-1`/`F-I-4` et contrat §5, pas un bug de gate) ; timeout E1 → **0 retry** ; timeout E3/E5 → E2 **avant** toute décision (ordre observable dans les *execution data*) ; `5xx` E8/E1/E4 → tentatives conformes §4 ; `5xx` E3 (conflit `@Version`) → retry borné puis E2, **jamais** `503 BACKEND_UNAVAILABLE` ; E4/E3 `409` → E2 d'abord puis routage ; E2 id inconnu → `404 REQUEST_NOT_FOUND` ; E5/E6 `404` post-`201` → `500` ; statut non listé → catch-all §3.2 ; backend down → `503 BACKEND_UNAVAILABLE` borné (< contrat §4), aucun stack ; outcome final = **1 seul** `documentId` même sous timeout E5 (`F-I-9`) ; **scan des logs n8n** : 0 occurrence denylist (§12.3) **et** 0 sous-chaîne PII des fixtures ; **0 binaire dans les *execution data*** (pas de signature `PK\x03\x04`, contrat §6) ; ingress invalide → `400` + 0 appel backend |

Chaque gate produit un rapport STATUS/FINDINGS/FILES_CHANGED/TESTS/
RISKS/NEXT_ACTION (AGENTS.md §17) avec preuves (codes HTTP, comptages,
extraits de logs désensibilisés).

---

## 14. Conformité des phases H.1–H.3 (aucune régression)

| H | Garantie | Contrôle en I.0/I.1 |
|---|---|---|
| H.1 | `DatasourceSecretGuard` : le backend refuse de démarrer sans secret — **inchangé** | aucun toucher au backend ; gates I-B…D démarrent le stack normalement |
| H.2 S-1 | Sanitisation des logs de bases — **inchangée** | n8n ne relaye pas les bodies d'erreur (SEC-12) |
| H.2 S-2 | Publications loopback `127.0.0.1` (n8n 5678, ollama 11434) — **inchangées** | aucune nouvelle publication de port |
| H.2 S-3 | `N8N_ENCRYPTION_KEY` fail-fast sans défaut — **conservée** | §9 : variable réutilisée telle quelle |
| H.3 | Attentes bornées, cycle de vie processus propre, zéro kill par nom | §4/§6 : toute attente n8n bornée ; tests via harnais H.3 ; aucun `taskkill`, aucun kill par port dans les scripts I.1 |
| Git | Arbre propre avant I.0 (`e9bc6cc`), pas de commit non demandé | livrables I.0 **non committés pendant les revues** ; un seul commit de documentation après les 4 verdicts PASS |

---

## 15. Findings et questions ouvertes

### 15.1 Findings I.0 (constatés, NON corrigés — décision humaine requise)

| ID | Finding | Impact | Proposition (hors I.0) | Sévérité |
|---|---|---|---|---|
| F-I-1 | E1 sans clé d'idempotence : un timeout après commit laisse une demande orpheline (réconciliable via `correlationId`) | doublons potentiels rares | évolution `API_CONTRACTS.md` : header `Idempotency-Key` sur E1 (it.2) | MEDIUM |
| F-I-2 | Réponse E5 non idempotente (`409` si déjà `GENERATED`) | atténué en n8n (réconciliation E2, R2-R3) | E5 : `200` si déjà `GENERATED` + même `documentId` (it.2) | LOW (mitigué) |
| F-I-3 | E3 : verrou optimiste `@Version` **existe** en base mais est **invisible du contrat** (aucun champ `version`) et un conflit ressort en `500 DATABASE_ERROR` (pas de handler dédié) → n8n peut le classer `503` à tort | conflit de PATCH concurrent mal classé, réconcilié par E2 (§6, rang 16) | handler `409 VERSION_CONFLICT` + champ `version` dans `API_CONTRACTS` (migration, AGENTS.md §10) | LOW |
| F-I-4 | Aucun TTL/purge des demandes `MISSING_INFORMATION`/`DRAFT` | accumulation de lignes PII en base | politique de rétention (migration si schéma requis) | MEDIUM |
| F-I-5 | Pas de compteur de rounds côté backend : borne de boucle best-effort en n8n (static data) | le plafond de 5 n'est pas garanti hors production n8n | soit acceptable (chaque exécution reste bornée), soit champ `clarificationRounds` côté backend | LOW |
| F-I-6 | Webhook n8n **non authentifié** (basic auth = éditeur) | exposition si le périmètre loopback saute | jeton webhook header avant prod (escalade security, it.1 fin) | MEDIUM (accepté it.1) |
| F-I-7 | `downloadPath` relatif sans base publique ni `DOWNLOAD_BASE_URL` | le frontend doit connaître la base backend | variable `DOWNLOAD_BASE_URL` si besoin réel apparaît | INFO |
| F-I-8 | *Execution data* n8n stocke les inputs PII **en clair** (SQLite — `N8N_ENCRYPTION_KEY` ne chiffre que les credentials) ; les pages purgées ne libèrent pas l'espace sans vacuum ; les exécutions manuelles/tests sont persistées par défaut | résidu PII au-delà de 24 h, espace disque non libéré | `EXECUTIONS_DATA_SAVE_ON_SUCCESS=none`, `EXECUTIONS_DATA_SAVE_MANUAL_EXECUTIONS=false`, `DB_SQLITE_VACUUM_ON_STARTUP=true` (§9), purge 24 h, éditeur = compte propriétaire | LOW |
| F-I-9 | Fenêtre E5 timeout : générations concurrentes possibles (pas de single-flight côté backend) → `documents[]` potentiellement multiple | double document officiel rare + sélection ambiguë | outcome limité à **1** `documentId` (plus récent) ; correctif durable = CAS/verrou dans `generate` (it.2) | LOW |
| F-I-10 | Éditeur n8n : `N8N_BASIC_AUTH_*` peut être **inerte** sur n8n ≥ 1.0 (image `latest`) | faux contrôle de protection des données d'exécution | vérifier/effectiver en I.1 (compte propriétaire) ; `N8N_BLOCK_FILE_ACCESS_TO_N8N_FILES` jamais désactivé (§9) | MEDIUM (obligation I.1) |

Aucun finding ne bloque la revue I.0 ; `F-I-6`, `F-I-4` et `F-I-10`
portent une **échéance d'obligation** (avant production / en I.1), pas
une correction I.0.

**Blocages architecturaux suivis hors I.0** (revue architecte, non
bloquants pour l'approbation I.0) : **B1** = `F-I-10` (éditeur/compose,
I.1) · **B2** = `F-I-1` (idempotence E1, it.2) · **B3** = `F-I-6` +
`SEC-11` (authentification webhook E6, avant production).

### 15.2 Questions ouvertes existantes (référence croisée)

| ID | Sujet | Lien avec l'orchestration |
|---|---|---|
| OQ-5 | Seuil de `confidence` IA | **non branché** en it.1 (§8.2 A2) — arbitrage requis avant tout seuil |
| OQ-API-2 | `202` + polling (génération asynchrone) | contredirait I01 synchrone (ADR-12) ; réévaluer si `AI_TIMEOUT_MS`/E5 deviennent trop longs |
| OQ-API-4 / R-10 | Auth E6 / PII audit | fermeture obligatoire avant prod (SEC-10/SEC-11) ; flux E2 complet = principal inflow PII (SEC-13) |
| OQ-9 | PDF / archivage | hors it.1 ; le point d'extension serait un outcome `GENERATED_PDF` à discuter |

---

## 16. Analyse d'impact et préparation I.1

**Rien de ce qui suit n'a été créé en I.0** (liste contractuelle pour la
phase d'implémentation, sous réserve des revues) :

| Fichier (I.1) | Contenu |
|---|---|
| `n8n/workflows/document-generation-v1.json` | workflow I01 (§7.3), sans aucun champ `credentials` |
| `docker/docker-compose.yml` | ajouts env §9 (AI_*, EXECUTION_*, EXECUTIONS_DATA_*, N8N_DIAGNOSTICS_ENABLED) — **sans** renommer l'existant |
| `docker/.env.example` | mêmes variables, placeholders uniquement |
| `tests/` (gates I-A…I-D) | scripts statiques + E2E/fixtures selon §13 |
| `docs/architecture.md` §7 | mise à jour si l'implémentation diverge du plan §7 |
| `requirements/N8N_CONTRACTS.md` | reste inchangé sauf findings acceptés |

**Écarts constatés vs. documents existants (information, aucun blocage)** :
aucun endpoint manquant, aucune contradiction de contrat ; les écarts sont
uniquement les findings §15.1 (espaces d'idempotence/rétention/auth) qui
ne demandent **aucune** modification pour construire I.1.

---

## 17. Processus de revue exigé (Phase I.0)

```
BUSINESS/CONTRAT (fait : §2-§6, contrat v1)
        → ARCHITECT  (§3, §7, §8, §11, §16)
        → TESTER     (§13 : testabilité des gates I-A…I-D)
        → SECURITY   (§10, §12, findings F-I-6/F-I-8)
        → REVIEWER   (cohérence globale, conformité AGENTS.md §8)
```

- Chaque agent : **report-only** (aucune modification de dépôt, aucun état
  Git modifié) ; verdict `PASS`/`FAIL` motivé avec preuves.
- `MAX_REVIEW_CYCLES = 3` ; 3 échecs → **STOP + rapport bloquant**
  (AGENTS.md §7).
- Aucun agent d'implémentation en I.0.
- Issue : **PHASE I.0 — CHECKPOINT REPORT** →
  `STOP — WAIT_FOR_HUMAN_APPROVAL` (I.1 n'est pas démarré automatiquement).
