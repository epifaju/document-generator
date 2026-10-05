# N8N_CONTRACTS — Contrat webhook d'orchestration n8n (v1)

| | |
|---|---|
| **Statut** | PROPOSÉ — Phase I.0 (architecture + contrats seulement, aucun workflow JSON) |
| **Date** | 2026-10-04 |
| **Périmètre** | Pilote `ATTESTATION_CONCORDANCE`, itération 1 |
| **Dépendances** | `requirements/API_CONTRACTS.md` (v1, contrats E1–E8) · `requirements/ATTESTATION_CONCORDANCE.md` (contrat métier, transitions T1–T14) · `docs/architecture.md` §1.3–1.5, §7, §10, §12 · `AGENTS.md` §2, §4, §11–§16 |
| **Revue exigée** | Architect → Tester (testabilité) → Security → Reviewer, MAX_REVIEW_CYCLES = 3 |

Ce document définit le **contrat HTTP du point d'entrée n8n** (webhook
conversationnel) et ses réponses : ingress, enveloppes d'egress, codes
d'erreur, bornes temporelles, boucle de conversation et téléchargement.
L'architecture (modèle d'état, décomposition, frontière IA, PII,
idempotence, observabilité, gates de test) est dans
`docs/n8n-orchestration.md`.

Aucune règle métier n'est définie ici : les statuts, transitions et codes
métier restent exclusivement ceux de `API_CONTRACTS.md` et du contrat
pilote. n8n ne fait que **router** ces résultats vers l'utilisateur.

---

## 1. CONVENTIONS GÉNÉRALES

### 1.1 En-têtes

| En-tête | Direction | Règle |
|---|---|---|
| `Content-Type: application/json; charset=UTF-8` | requête (webhook) | obligatoire ; toute autre valeur → `400` `VALIDATION_ERROR`, corps non traité (miroir F-06 du contrat backend) |
| `Content-Type: application/json` | réponse | toujours ; n8n ne renvoie **jamais** de binaire (cf. §6) |
| `X-Correlation-Id` | requête | **optionnel en entrée** ; format accepté `^[A-Za-z0-9-]{8,64}$` (anti log-injection **et** sous-ensemble strict du filtre backend `^[A-Za-z0-9-]{1,64}$` — `CorrelationIdFilter` : une valeur non conforme est **écartée en entier** et remplacée par un UUID v4, cassant la chaîne §12.1) ; absent ou invalide → n8n génère un UUID v4 |
| `X-Correlation-Id` | réponse | **toujours renvoyé par toute réponse émise par le workflow** (exception native §3.1 : kill `EXECUTIONS_TIMEOUT` / transport exclu), identique à la valeur retenue ; idem dans le corps (`correlationId`) |
| `X-Correlation-Id` | vers le backend | **toujours transmis tel quel** sur E1–E8 (miroir API_CONTRACTS §1.1) |
| `Authorization` | — | hors périmètre it.1 (cf. §1.3) |

Une seule valeur de `correlationId` couvre **toute la vie d'une exécution
n8n** (webhook → extraction → appels backend → réponse) ; aucune requête
sortante de l'exécution n'en porte une autre.

### 1.2 Formats

- Identifiants : UUID v4 (`requestId`, `documentId` — identiques à ceux du
  backend, `requestId = referenceDemande`).
- Horodatages : ISO 8601 UTC, `yyyy-MM-dd'T'HH:mm:ss'Z'` (les timestamps
  affichés proviennent du backend, jamais de `Date.now()` côté n8n).
- Messages utilisateur : **français**, texte FR sans stack trace ni détail
  technique. Clés techniques : anglais/camelCase (identiques au schéma
  backend).
- Aucune valeur de `data` (prénom, nom, date, lieu…) n'apparaît jamais dans
  un message d'erreur ou de succès n8n : uniquement des **clés de champs**
  et des codes.

### 1.3 Authentification (décision d'itération)

- **Itération 1 : le webhook n8n n'est pas authentifié.** La protection est
  le périmètre : publication hôte épinglée à `127.0.0.1:5678` (Phase H.2,
  S-2) + réseau interne `adgendoc-internal`. `N8N_BASIC_AUTH_*` vise
  l'**éditeur** n8n, pas les webhooks — **à vérifier en I.1** : n8n ≥ 1.0
  a retiré le basic-auth et l'image `latest` le rend peut-être **inerte** ;
  la protection éditeur effective repose alors sur le compte propriétaire
  (User Management) + loopback (finding `F-I-10`,
  `docs/n8n-orchestration.md` §15).
- **Avant mise en production — obligations cumulatives (escalade
  security) :** (1) jeton de webhook en header (finding `F-I-6`) **et**
  (2) autorisation d'accès au téléchargement E6 (R-10 / OQ-API-4,
  `API_CONTRACTS.md` §1.3) ; aucune des deux n'est satisfaite en it.1.
- It.2 : s'alignera sur le modèle JWT d'`API_CONTRACTS.md` §1.3.

### 1.4 Point d'entrée

- Méthode/chemin : **`POST /webhook/document-generation`**
  (`docs/architecture.md` §7.1, nœud 1, `responseMode: responseNode`).
- En environnement de test n8n, l'URL `/webhook-test/...` expose le **même**
  corps et le **même** contrat ; seul le préfixe change (aucune règle
  d'affaire n'en dépend).
- `E7 GET /api/v1/health` reste hors workflow (healthcheck Docker).

---

## 2. INGRESS — corps de la requête webhook

```json
{
  "message": "Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.",
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d"
}
```

| Champ | Type | Obligatoire | Règle | Sinon |
|---|---|---|---|---|
| `message` | string | **oui** | 1–4000 caractères après trim | `400` `VALIDATION_ERROR` |
| `requestId` | UUID v4 | non | reprise de conversation (boucle T5/T6b, polling §5) ; format strict `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$` (minuscules) | `400` `VALIDATION_ERROR` |

Règles supplémentaires :

- **Clés racine inconnues interdites** → `400` `VALIDATION_ERROR` (miroir
  de la philosophie `@JsonAnySetter` d'E1, F-03/F-04) : jamais de
  traitement silencieux d'une extension inconnue.
- Corps non JSON / tronqué / vide → `400` `VALIDATION_ERROR`.
- **`413` hors périmètre ingress n8n** : le corps est borné par
  `message` ≤ 4 000 car. + clés racines fermées (aucun binaire relaie) ;
  la charge utile n8n → E1 reste inférieure aux 65 536 B du backend
  (longueurs bornées par le schéma d'extraction). Un `413` backend
  éventuel serait couvert par la règle par défaut du §3.2.
- En it.1, **aucun autre champ de conversation** n'existe : pas de
  compteur `round` côté client, pas d'historique, pas de choix
  utilisateur structuré (bornes de boucle internes, cf. architecture §5).

Réponse `400` (ingress) :

```json
{
  "outcome": "INVALID_REQUEST",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Requête invalide : champ « message » absent ou invalide."
  }
}
```

---

## 3. EGRESS — enveloppes de réponse

### 3.1 Enveloppe commune

Toute réponse porte :

| Champ | Type | Présence | Règle |
|---|---|---|---|
| `outcome` | string (enum) | **toujours** | discriminant unique du résultat (§3.3) |
| `correlationId` | string (`^[A-Za-z0-9-]{8,64}$`, §1.1 ; UUID v4 quand n8n l'a généré) | **toujours** (réponses du workflow — exception native ci-dessous) | = en-tête `X-Correlation-Id` |
| `error.code` | string | si échec technique, ingress invalide, ou outcome `REQUEST_NOT_FOUND` (§3.2) | codes du §3.4 |
| `error.message` | string | idem | texte FR destiné à l'utilisateur |
| `requestId` | UUID | selon outcome | présent si et seulement si une demande est persistée (politique d'E1, API_CONTRACTS §1.4) |
| `status` | enum backend | selon outcome | statut **backend** courant, jamais recalculé par n8n |
| payload métier | selon outcome | §3.3 | |

**Séparation des plans** (règle unique à retenir) :

- `200` = l'orchestration a **fonctionné**, le résultat est un **état
  métier** de la conversation (y compris `REJECTED`, `GENERATION_FAILED`) ;
- `400` = ingress invalide, refus d'E1 **non persistant**, ou refus
  structurel d'E3 non muté (clé inconnue/réservée, API_CONTRACTS §3.3) ;
- `404` = erreur d'**entrée client** (`requestId` inconnu — aucune
  persistance, cf. §3.2) ;
- `5xx` = échec **technique** (IA, backend, orchestration) — aucune
  décision métier n'a été prise.

n8n renvoie **jamais** : sortie brute du LLM, stack trace, corps de
réponse backend **complet**, valeurs de `data`, en-têtes backend — seuls
les champs explicitement listés au §3.3 peuvent être recopiés.

**Exception native n8n** : l'enveloppe ci-dessus est garantie pour tous
les outcomes **émis par le workflow** (tout chemin atteignant un nœud
Respond). Chemins hors workflow : exécution tuée par `EXECUTIONS_TIMEOUT`
(§4) → erreur native n8n **sans** `outcome` métier ; n8n injoignable ou
exécution interrompue → **aucune réponse** (échec transport côté
client). Ces chemins sont distincts d'un échec backend et ne doivent
jamais être confondus avec lui.

### 3.2 Mapping HTTP

| HTTP | `outcome` | `error.code` | Cause |
|---|---|---|---|
| `200` | `GENERATED` | — | DOCX généré et vérifié (E5 `201` + E6 `200`) |
| `200` | `MISSING_INFORMATION` | — | E1 `422` / E3 `200` ou E4 `422` avec champs absents |
| `200` | `REJECTED` | — | `400` backend persistant en `REJECTED` (T4/T6/T10/T12b) |
| `200` | `GENERATION_FAILED` | — | `500` E5 persistant après récupération bornée (T9 sans T12 réussi) |
| `200` | `UNSUPPORTED_DOCUMENT_TYPE` | — | E8 `400` avec `ERR_DOCUMENT_TYPE_NON_SUPPORTE` |
| `200` | `CLARIFICATION_LIMIT_REACHED` | — | borne `MAX_CLARIFICATION_ROUNDS` atteinte (§5) |
| `400` | `INVALID_REQUEST` | `VALIDATION_ERROR` | ingress invalide (§2) ; E1 `400` **sans** `requestId` (non persisté) ; E3 `400` **non persistant** (clé inconnue/réservée — `document_request` non modifié, API_CONTRACTS §3.3) |
| `404` | `REQUEST_NOT_FOUND` | `REQUEST_NOT_FOUND` | E2 `404` : `requestId` client inconnu (erreur d'entrée, aucune persistance) |
| — (jamais relayé) | — | — | `409` backend (E3 `REQUEST_ALREADY_CLOSED`, E4 `INVALID_STATUS`) → **réconciliation `E2` d'abord** puis routage (§4/§5) — jamais de `409` sortant de n8n |
| `502` | `ERROR` | `AI_EXTRACTION_ERROR` | LLM injoignable/timeout après `AI_MAX_ATTEMPTS` ou sortie non JSON strict (**jamais** utilisé pour un rejet E8) |
| `502` | `ERROR` | `EXTRACTION_SCHEMA_INVALID` | E8 `400` dont `errors[]` ne contient **pas** `ERR_DOCUMENT_TYPE_NON_SUPPORTE` |
| `503` | `ERROR` | `BACKEND_UNAVAILABLE` | backend injoignable / échecs bornés sur E1–E8 (hors cas routés ci-dessus) |
| `500` | `ERROR` | `INTERNAL_ERROR` | bug d'orchestration n8n (ex. `404` inattendu, E6 `404` post-génération) |

**Discrimination E8 `400` (règle unique)** : `errors[]` contient
`ERR_DOCUMENT_TYPE_NON_SUPPORTE` → outcome `200`
`UNSUPPORTED_DOCUMENT_TYPE` ; tout autre `400` E8 → `502`
`EXTRACTION_SCHEMA_INVALID`. `AI_EXTRACTION_ERROR` est réservé aux
échecs LLM/parse (jamais à un rejet E8 déterministe).

**Règle par défaut** : tout statut backend non listé ci-dessus **et non
géré par §4/§5 (ex. `409` ou `500 DATABASE_ERROR` → d'abord E2)**
→ si l'appel est **request-scoped** : réconciliation `E2` d'abord ; sinon
(pas de `requestId` connu, ex. E1) : résolution directe. Dans les deux
cas : `500` `INTERNAL_ERROR` + log structuré du statut reçu ; jamais
d'enveloppe native n8n en guise de réponse (exception §3.1), jamais de
chute silencieuse.

### 3.3 Payloads par outcome

**`GENERATED` (200)**

```json
{
  "outcome": "GENERATED",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "status": "GENERATED",
  "documentId": "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e",
  "fileName": "attestation-concordance-6f0b3a7e.docx",
  "downloadPath": "/api/v1/requests/6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d/documents/b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e",
  "generatedAt": "2026-10-02T09:16:40Z"
}
```

Champs repris tels quels de `GenerateDocumentResponse` (API_CONTRACTS
§2.5) — n8n ne fabrique ni `fileName` ni `generatedAt`. `downloadPath`
est **relatif** (cf. §6). Si `documents[]` contenait plusieurs entrées
(fenêtre de double génération, finding `F-I-9`), n8n retient la **plus
récente** (`generatedAt` max) et n'expose **qu'un seul** `documentId`
dans l'outcome.

**`MISSING_INFORMATION` (200)**

```json
{
  "outcome": "MISSING_INFORMATION",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "status": "MISSING_INFORMATION",
  "missingFields": ["dateNaissance", "lieuNaissance"],
  "message": "Informations manquantes pour générer l'attestation : dateNaissance, lieuNaissance. Merci de les fournir dans votre prochain message."
}
```

- `missingFields` : **liste ordonnée renvoyée par le backend**
  (`docs/architecture.md` §5.3 — ordre canonique) ; n8n la recopie sans
  réordonner, sans compléter, sans proposer de valeurs.
- `message` : template FR fixe construit à partir des **clés uniquement**
  (pas de libellés dupliqués hors schéma, pas de données).

**`REJECTED` (200)**

```json
{
  "outcome": "REJECTED",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "status": "REJECTED",
  "fieldErrors": [
    { "field": "dateNaissance", "code": "ERR_DATE_CALENDRIER_INVALIDE",
      "message": "La date de naissance n'est pas une date de calendrier valide." }
  ],
  "message": "La demande a été rejetée : correction impossible sur cette demande (statut terminal en itération 1)."
}
```

- `requestId` est **toujours** un UUID pour `REJECTED` (une demande
  `REJECTED` est persistée, `API_CONTRACTS` §1.4) ; un E1 `400` **non
  persisté** ne produit aucun `REJECTED` mais l'outcome
  `INVALID_REQUEST`/`400` sans `requestId` (cf. §3.2).
- `fieldErrors` repris tels quels (`ErrorResponse.fieldErrors`, API_CONTRACTS
  §2.3).

**`GENERATION_FAILED` (200)**

```json
{
  "outcome": "GENERATION_FAILED",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "status": "FAILED",
  "cause": "TEMPLATE_NOT_FOUND",
  "message": "La génération du document a échoué. Vous pouvez relancer la demande."
}
```

`cause` = code technique du backend (`TEMPLATE_NOT_FOUND` |
`DOCUMENT_GENERATION_ERROR` | `INTERNAL_ERROR`), transmis pour
observabilité ; `status` = état backend courant (`FAILED`, T9).

**`UNSUPPORTED_DOCUMENT_TYPE` (200)**

```json
{
  "outcome": "UNSUPPORTED_DOCUMENT_TYPE",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "message": "Seul le type de document ATTESTATION_CONCORDANCE est pris en charge en itération 1."
}
```

Aucune demande n'est créée (garde E8 antérieure à E1).

**`CLARIFICATION_LIMIT_REACHED` (200)**

```json
{
  "outcome": "CLARIFICATION_LIMIT_REACHED",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "status": "MISSING_INFORMATION",
  "missingFields": ["nomCorrect"],
  "message": "Nombre maximal de demandes de clarification atteint. Veuillez relancer une nouvelle demande."
}
```

**`REQUEST_NOT_FOUND` (404)**

```json
{
  "outcome": "REQUEST_NOT_FOUND",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "error": {
    "code": "REQUEST_NOT_FOUND",
    "message": "Demande introuvable. Veuillez relancer une nouvelle demande."
  }
}
```

E2 a répondu `404` pour le `requestId` fourni (entrée client : id
inconnu, expiré ou forgé) — aucune persistance, aucun `requestId`
retourné (il n'existe pas côté backend).

**`INVALID_REQUEST` (400)**

```json
{
  "outcome": "INVALID_REQUEST",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Votre message ne respecte pas le format attendu. Veuillez le reformuler."
  }
}
```

Ingress invalide (§2, exemple complet au même format), E1 `400` sans
`requestId` ou E3 `400` non persistant — aucune donnée `data` relayée.

**`ERROR` (500/502/503)**

```json
{
  "outcome": "ERROR",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "error": {
    "code": "AI_EXTRACTION_ERROR",
    "message": "L'extraction automatique a échoué. Veuillez reformuler votre demande."
  }
}
```

`requestId` présent uniquement si la demande est déjà persistée (cas
`BACKEND_UNAVAILABLE` en cours de boucle).

### 3.4 Codes d'erreur n8n

| Code | HTTP | Origine | Statut contrat |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | ingress webhook (§2) ; E1 `400` non persistant | existant (AGENTS.md §14, API_CONTRACTS) |
| `AI_EXTRACTION_ERROR` | 502 | LLM injoignable/timeout/`5xx` après tentatives bornées, sortie non JSON strict (**jamais** un rejet E8, cf. §3.2) | existant (AGENTS.md §14 : « flux n8n ») |
| `EXTRACTION_SCHEMA_INVALID` | 502 | E8 `400` détaillé (hors type non supporté) | existant (contrat pilote §10.3) |
| `BACKEND_UNAVAILABLE` | 503 | échecs bornés d'accès backend | **nouveau, local n8n** — AGENTS.md §14 donne des « Examples » non exhaustifs ; justifié : distinguer « backend down » d'« erreur interne » dans l'observabilité (§12 du doc d'architecture) |
| `INTERNAL_ERROR` | 500 | bug d'orchestration | existant |
| `REQUEST_NOT_FOUND` | 404 | E2 : `requestId` client inconnu | existant (relayé depuis le code backend `REQUEST_NOT_FOUND`, jamais produit par n8n) |

Codes **réservés à l'it.2** : `UNAUTHORIZED` / `FORBIDDEN` (dès
l'introduction du JWT, §1.3). Les codes **métier** (`MISSING_INFORMATION`,
`ERR_*`, `INVALID_STATUS`, `REQUEST_ALREADY_CLOSED`, `TEMPLATE_NOT_FOUND`…)
ne sont jamais produits par n8n : ils sont transmis depuis les réponses
backend dans les payloads `§3.3`.

---

## 4. BORNES TEMPORELLES ET RÈGLES D'AMBIGUÏTÉ (normatif)

Toutes les valeurs sont **maximales** ; toute attente n8n est bornée
(leçon Phase H.3). Le détail du routage par échec est dans
`docs/n8n-orchestration.md` §6 ; les nombres ci-dessous font foi.

Les variables `AI_TIMEOUT_MS`, `AI_MAX_ATTEMPTS`, `EXECUTIONS_TIMEOUT`
sont proposées en I.1 (architecture §9) ; leurs valeurs sont **intégrées
par défaut dans le workflow** lorsque la variable est absente — l'ajout
des variables en I.1 ne change donc aucun comportement.

| Appel | Timeout | Tentatives totales | Retry sur | Jamais de retry sur |
|---|---|---|---|---|
| Ollama `POST /api/chat` | `AI_TIMEOUT_MS` (déf. 60 000 ms) | `AI_MAX_ATTEMPTS` (déf. 3) | connexion refusée, timeout, `5xx` | — |
| E8 `POST /extraction/validate` | 10 000 ms | 2 | `5xx`, timeout (pure, sans effet de bord) | `400` (signal réel) |
| E1 `POST /requests` | 10 000 ms | 2 **si et seulement si une réponse `5xx` a été reçue** (transaction backend annulée → sûr dans le cas courant) | `5xx` reçu — **caveat** : un `5xx` post-commit (échec d'audit après sauvegarde) peut laisser une ligne existante → doublon tracé `F-I-1` (architecture §6) | **timeout / connexion interrompue** (issue inconnue : la ligne peut exister) → `503` + `correlationId` pour réconciliation (audit), sans auto-retry |
| E3 `PATCH /requests/{id}` | 10 000 ms | 2 | `5xx` reçu (merge = idempotent à payload identique) — un `500 DATABASE_ERROR` peut être un conflit d'optimistic locking (`@Version`, finding `F-I-3`) : le retry ré-applique le merge, sinon réconciliation E2 (jamais classé « backend down ») | timeout → **réconciliation E2** (le `requestId` est connu) |
| E4 `POST .../validate` | 10 000 ms | 3 | `5xx`, timeout (idempotent, T11) | `400`/`422`/`409` (états réels) |
| E5 `POST .../generate` | 15 000 ms | 2 (initial + **1** récupération T12 via E4) | `5xx` reçu, `409 INVALID_STATUS` après vérification E2 | timeout → **réconciliation E2** (bornée : 3 × 5 000 ms, cf. ligne E2) avant toute décision |
| E2 `GET /requests/{id}` | 5 000 ms | 3 (usage : réconciliation) | `5xx`, timeout | — |
| E6 `GET .../documents/{id}` | 10 000 ms | 3 (lecture seule) | `5xx`, timeout | — |

Bornes de conversation / d'exécution :

| Borne | Valeur | Comportement à la limite |
|---|---|---|
| `MAX_CLARIFICATION_ROUNDS` | **5** rounds de conversation par `requestId` — compteur = **nombre total de webhooks par `requestId`, round 1 inclus** (un webhook = un round = une exécution) | `CLARIFICATION_LIMIT_REACHED` (200) au **5ᵉ** round |
| `message` ingress | 4 000 caractères | `400` `VALIDATION_ERROR` |
| `EXECUTIONS_TIMEOUT` (plafond d'exécution n8n ; nom réel n8n — `EXECUTION_TIMEOUT` n'existe pas) | 900 s | pire cas conforme **par exécution** (un round = une exécution, §3.2) ≈ 330 s **retries bornés §4 inclus** (round final complet : E2 + Ollama + E8 + E3 + E4 + E5 + E6 ≤ 18 appels ; agrégat 5 rounds ≈ 455 s sans retry = 5 exécutions, indicatif) ; à la limite : exécution tuée → **erreur native n8n sans enveloppe `outcome`** (exception §3.1) — jamais d'exécution suspendue indéfiniment (H.3) |
| Appels LLM + backend par exécution | ≤ 18 (pire cas : round final avec retries bornés §4 — E2 3 + Ollama 3 + E8 2 + E3 2 + E4 3 + E5 2 + E6 3 ; agrégat 5 rounds sans retry = 22 = 3 + 3×4 + 7, indicatif) | détecte les boucles en fuite, jamais une combinaison de retries légitime → tout dépassement = bug d'orchestration → `INTERNAL_ERROR` + log |

Règles de réconciliation (issue ambiguë, design anti-doublon) :

1. **E1 timeout** → aucune reprise automatique : `503` avec
   `correlationId` ; toute ligne orpheline est réconciliable côté backend
   par `correlationId` (audit) — finding `F-I-1`.
2. **E3/E5 timeout ou `409`** → `E2` d'abord ; le **statut backend** tranche
   (table de routage architecture §3.3) ; n8n ne suppose jamais l'issue.
3. **E5 `409 INVALID_STATUS` + statut `GENERATED`** → succès : vérification
   E6 puis outcome `GENERATED` (le `201` a été perdu, pas l'effet).
4. Aucun `retryOnFail` n8n n'est activé **sans** une condition de
   reprise explicite ci-dessus.

---

## 5. BOUCLE DE CONVERSATION

| Règle | Détail |
|---|---|
| Round 1 (sans `requestId`) | extraction → E8 → E1 ; `422` → outcome `MISSING_INFORMATION` (requestId désormais obligatoire côté client) |
| Rounds ≥ 2 (avec `requestId`) | **E2 d'abord** (état courant) → extraction delta (hint = `missingFields` connus) → E8 → **E3 `PATCH` payload plat** (API_CONTRACTS §3.3 : `{"dateNaissance": "...", "lieuNaissance": "..."}`) — uniquement les champs fournis. **Invariant de forme chaque round** : seul `data` est partiel ; l'enveloppe reste valide en entier — `missingFields` re-déclare **toutes** les clés obligatoires encore absentes (règle `allOf` du schéma d'extraction), sinon E8 rejetterait un round pourtant correct (`502` — fixture gate I-D) |
| PATCH `200` | routage par statut : `VALIDATED` → E4 puis E5 ; toujours `MISSING_INFORMATION` → outcome `MISSING_INFORMATION` (T6b) ; `REJECTED` → outcome `REJECTED` |
| PATCH `409 REQUEST_ALREADY_CLOSED` | E2 → route (`GENERATED` → succès ; `REJECTED` → `REJECTED`) |
| Borne | `MAX_CLARIFICATION_ROUNDS = 5` (compteur interne n8n, cf. architecture §5 : meilleure estimation, la correction du flux n'en dépend pas) |
| Jamais | valeur suggérée/inventée, `missingFields` réordonné, donnée renvoyée au LLM (minimisation PII), complétion d'une demande existante par une nouvelle création **lorsque le client renvoie son `requestId`** (OQ-2 — cf. contrat client ci-dessous) |

**Contrat client** : `requestId` est **obligatoire dès le round 2** —
c'est au client de le conserver et de le renvoyer. Sans `requestId`,
n8n n'a aucun état de session et **doit** traiter le message comme un
round 1 (nouvelle extraction → nouvelle E1) : c'est le seul
comportement qui lui est disponible. Conséquence documentée : une
**seconde ligne `document_request` contenant la même PII** (lié au
finding `F-I-4`, politique de rétention). Aucune session n8n n'est
requise pour la correction du flux ; la reprise se fait uniquement par
`requestId` explicite.

---

## 6. TÉLÉCHARGEMENT

- `downloadPath` = chemin **relatif** ` /api/v1/requests/{requestId}/documents/{documentId}`
  (reçu d'E5) — la base d'origine (frontend) est assemblée côté client ;
  n8n n'invente aucune URL publique (finding `F-I-7` : future variable
  `DOWNLOAD_BASE_URL` si un besoin apparaît).
- Après E5 `201`, n8n exécute une **phase de vérification E6 bornée**
  (≤ 3 tentatives, §4) pour vérifier que le document est réellement
  servable avant de déclarer `GENERATED` (conformité à la chaîne
  `docs/architecture.md` §1.3) ; **le binaire n'est jamais relayé** :
  le nœud E6 ne conserve que statut + en-têtes (aucun corps dans les
  données d'exécution n8n), la réponse du webhook ne porte que
  métadonnées + `downloadPath` (minimisation PII — décision
  d'architecture I.0, soumise à revue ; vérifié par les gates I-B/I-D :
  aucune signature `PK\x03\x04` dans les données d'exécution).
- E6 `404` après un `201` → `INTERNAL_ERROR` (`500`) + log (incohérence
  serveur), jamais de succès déclaré.

---

## 7. NON-OBJECTIFS (itération 1)

- Authentification / JWT (it.2, API_CONTRACTS §1.3, finding `F-I-6`).
- Génération asynchrone `202` + polling (ADR-12, OQ-API-2).
- Relais binaire du DOCX via le webhook (§6).
- Historique de conversation persisté côté n8n, multi-documents,
  notifications, PDF (OQ-9).
- Toute modification des contrats E1–E8 existants.

---

## 8. VERSIONING

- Contrat `v1`, statut PROPOSÉ (Phase I.0). Toute modification après
  approbation exige une révision par Architect + Reviewer et une entrée
  dans l'historique ci-dessous.
- Historique : `v1` — 2026-10-04 — création (Phase I.0).

---

## 9. ACCEPTANCE CRITERIA — contrat webhook

- [ ] Tout corps de réponse émis par le workflow contient `outcome` +
      `correlationId`, et l'en-tête `X-Correlation-Id` est identique
      (chemins natifs n8n hors enveloppe : cf. exception §3.1).
- [ ] Un `X-Correlation-Id` conforme au §1.1 fourni à l'ingress survit
      inchangé de E1 à E8 et réapparaît en réponse (filtre backend
      non déclenché).
- [ ] `200` ⇔ outcome métier ; toute réponse **non-200** porte un
      `error.code` du §3.4 (`400` ingress, `404` requestId inconnu,
      `500/502/503` panne technique).
- [ ] Aucune réponse ne contient : valeur de `data`, sortie LLM brute,
      stack trace, corps backend, binaire.
- [ ] Ingress strict : clé inconnue / `message` hors bornes / `requestId`
      malformé → `400` sans appel backend.
- [ ] Toutes les attentes respectent le tableau §4 (bornes maximales).
- [ ] E1 n'est jamais retenté après un timeout (ambiguïté non résolue).
- [ ] E5 en ambiguïté passe toujours par E2 avant toute décision.
- [ ] `missingFields` de l'outcome = liste backend, ordre préservé.
- [ ] `MAX_CLARIFICATION_ROUNDS = 5` : (i) **sans compteur persistant**,
      le flux reste borné et termine (bornes d'exécution + transitions
      backend) ; (ii) **avec persistance des *static data* n8n**
      (webhook de production), l'outcome `CLARIFICATION_LIMIT_REACHED`
      est atteint au **5ᵉ** round (round 1 inclus — cf. §4). Jamais de
      boucle infinie dans les deux cas.
- [ ] `downloadPath` identique à celui d'E5, relatif, non réécrit.
- [ ] **Gate I-C fixtures** : les 7 fixtures de conversation listées dans `docs/n8n-orchestration.md` §13 existent sous `tests/fixtures/conversation/` et couvrent : round 1 incomplet, round 2 complet, round 2 incomplet, round 2 champ invalide, round 2 garde REJECTED, round 2 stale missingFields, round 2 garde E4 422
- [ ] **Gate I-D fixtures** : les 18+ fixtures hostiles d'orchestration listées dans `docs/n8n-orchestration.md` §13 existent sous `tests/fixtures/hostile/` et couvrent : toutes les entrées de la matrice §6, idempotence (duplicata avec/sans requestId), injection pannes réseau/backend, scans sécurité (PII denylist, binaire DOCX)
