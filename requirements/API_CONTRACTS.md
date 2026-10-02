# API_CONTRACTS — Contrats REST du backend Spring Boot (v1)

| Rubrique | Valeur |
|---|---|
| Service | Spring Boot Document API |
| Version | `v1` (base path `/api/v1`) |
| Version du contrat | 1.0 |
| Itération | 1 — fondations techniques + document pilote `ATTESTATION_CONCORDANCE` |
| Statut | **PROPOSÉ** — à valider par l'architecte et l'humain avant implémentation |
| Rédacteur | agent `business-analyst` |
| Contrat métier associé | `requirements/ATTESTATION_CONCORDANCE.md` |

Format : OpenAPI-like synthétique (pas de fichier YAML générée — décisions de
contrat uniquement, aucun code d'implémentation).

---

## 1. CONVENTIONS GÉNÉRALES

### 1.1 En-têtes

| En-tête | Direction | Règle |
|---|---|---|
| `Content-Type: application/json; charset=UTF-8` | requête (tous les POST) | obligatoire pour les corps JSON ; **toute autre valeur** → `400` `VALIDATION_ERROR` (corps `ErrorResponse`, requête non traitée, **non persistée** — comportement F-06) |
| `Accept: application/json` | requête | recommandé (sauf téléchargement binaire) |
| `X-Correlation-Id` | requête + réponse | **optionnel en entrée** : si absent, le backend en génère un (UUID v4) ; toujours renvoyé en réponse et stocké dans `ErrorResponse.correlationId` + les logs techniques (AGENTS.md §15) |
| `Authorization: Bearer <JWT>` | requête | **hors périmètre de l'itération 1** (voir §1.3) |

### 1.2 Formats

- Identifiants : UUID v4 (`requestId` = `referenceDemande`, `documentId`).
- Horodatages : ISO 8601 UTC, `yyyy-MM-dd'T'HH:mm:ss'Z'`.
- Dates de naissance : `yyyy-MM-dd` (formes d'entrée acceptées : cf. contrat
  pilote §3).
- Enum de statut : `DRAFT` | `MISSING_INFORMATION` | `VALIDATED` |
  `REJECTED` | `GENERATED` | `FAILED`.
- Langue des `message` : **français**. Clés techniques : anglais/camelCase.
- Aucune stack trace ni détail technique interne n'est exposée au client.

### 1.3 Authentification (décision d'itération)

- **Itération 1 : authentification HORS PÉRIMÈTRE.** Les endpoints sont
  appelables sans `Authorization` (déploiement à périmètre restreint :
  réseau interne / localhost). Les chemins sont néanmoins **conçus pour être
  protégés** : aucun endpoint ne dépend d'un contexte d'identité implicite.
- **Itération 2 :** `Authorization: Bearer <JWT>` exigé sur **tous les
  endpoints sauf** `GET /api/v1/health`. En cas d'absence/expiration →
  `401 UNAUTHORIZED` ; en cas d'accès non autorisé à une demande d'un autre
  utilisateur → `403 FORBIDDEN`.
- **Risque assumé en it.1** (AGENTS.md §13 : « Generated document access
  must be authorized ») : le téléchargement DOCX est ouvert tant que l'auth
  n'est pas en place. Fermeture obligatoire avant toute mise en production →
  escalade security en fin d'itération 1.

### 1.4 Politique de persistance (OQ-3 ARBITRÉE — persistance `422` confirmée)

| Issue du `POST /api/v1/requests` | Persistance |
|---|---|
| `400` — enveloppe invalide (`ERR_PAYLOAD_INVALIDE`, `ERR_DOCUMENT_TYPE_NON_SUPPORTE`, **clé racine inconnue** `ERR_CHAMP_INCONNU`) | **non persistée** (aucun `requestId`, aucune `DocumentRequest` créée, **jamais** `REJECTED` — arbitrage F-04, cohérent F-03 §3.3) |
| `400` — règle métier violée **dans `data`** (ex. `ERR_DATE_FUTUR`, `ERR_NOM_CONCORDANCE_IDENTIQUE`, clé inconnue **dans `data`** `ERR_CHAMP_INCONNU`) | **persistée**, `status = REJECTED`, `requestId` retourné (audit) |
| `422` — champs obligatoires manquants | **persistée**, `status = MISSING_INFORMATION`, `requestId` retourné (permet le suivi de la collecte) |
| `201` — tout est valide | **persistée**, `status = VALIDATED` |

**Arbitrages de l'orchestrateur :**

- **OQ-3 RÉSOLUE** : la persistance du `422` (statut `MISSING_INFORMATION`
  avec `requestId`) est **confirmée**.
- **OQ-2 RÉSOLUE par ajout d'endpoint** : la complétion d'une demande se fait
  via **`PATCH /api/v1/requests/{requestId}`** (§3.3) — n8n met à jour la
  demande existante quand l'utilisateur fournit l'information manquante.
  La « nouvelle création » n'est plus le mécanisme de complétion ; elle reste
  possible (POST) mais n'est plus nécessaire.

---

## 2. SCHÉMAS COMMUNS

### 2.1 `CreateRequestRequest` (request body de `POST /api/v1/requests`)

```json
{
  "documentType": "ATTESTATION_CONCORDANCE",
  "data": {
    "prenom": "Maria",
    "nom": "Gomes",
    "dateNaissance": "1985-05-12",
    "lieuNaissance": "Bissau",
    "nomIncorrect": "Maria Gomez",
    "nomCorrect": "Maria Gomes",
    "sexe": "F",
    "nationalite": "Française",
    "documentSourceReference": "ACTE-1985-000123",
    "langueDocument": "FR",
    "demandeur": "João Silva",
    "contactEmail": "maria.gomes@example.com",
    "contactTelephone": "+33612345678",
    "motif": "Dossier bancaire"
  },
  "extraction": {
    "confidence": 0.97,
    "modelId": "ollama/llama3.1",
    "promptVersion": "v1"
  }
}
```

| Champ | Type | Obligatoire | Règles |
|---|---|---|---|
| `documentType` | string (enum) | oui | enum it.1 : uniquement `ATTESTATION_CONCORDANCE` |
| `data` | object | oui | **`additionalProperties: false`** ; clés et règles : cf. contrat pilote §3/§4 ; `referenceDemande` interdit dans `data` |
| `extraction.confidence` | number | non | 0..1 ; métadonnée d'audit uniquement — **n'influence jamais la validation déterministe** |
| `extraction.modelId` | string ≤ 200 | non | audit |
| `extraction.promptVersion` | string ≤ 50 | non | audit |

Champs `data` présents mais `null`/vides ⇒ traités comme **absents**
(contrat pilote §6).

### 2.2 `RequestStatusResponse`

```json
{
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "referenceDemande": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "documentType": "ATTESTATION_CONCORDANCE",
  "status": "MISSING_INFORMATION",
  "missingFields": ["dateNaissance", "lieuNaissance"],
  "fieldErrors": [],
  "data": { "prenom": "Maria", "nom": "Gomes" },
  "documents": [],
  "createdAt": "2026-10-02T09:15:04Z",
  "updatedAt": "2026-10-02T09:15:04Z"
}
```

| Champ | Type | Présent lorsque |
|---|---|---|
| `requestId` | UUID | toujours |
| `referenceDemande` | UUID | toujours (= `requestId`, cf. pilote §5.1) |
| `documentType` | string | toujours |
| `status` | enum | toujours |
| `missingFields` | string[] | `status = MISSING_INFORMATION`, sinon `[]` |
| `fieldErrors[]` | array | `status = REJECTED` (sinon `[]` — un `MISSING_INFORMATION` s'arrête à la phase de présence) |
| `data` | object | toujours (données normalisées persistées) |
| `documents[]` | array d'objets `{documentId, fileName, contentType, generatedAt}` | `status = GENERATED`, sinon `[]` |
| `createdAt` / `updatedAt` | ISO 8601 UTC | toujours |

### 2.3 `ErrorResponse` (corps des réponses d'erreur)

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Données invalides : correction nécessaire.",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "missingFields": [],
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "status": "REJECTED",
  "fieldErrors": [
    { "field": "dateNaissance", "code": "ERR_DATE_FORMAT_INVALIDE",
      "message": "Format de date invalide. Formats acceptés : yyyy-MM-dd ou dd/MM/yyyy." }
  ]
}
```

*(Variante `422` : `code = MISSING_INFORMATION`, `missingFields` renseigné,
`fieldErrors = []` — la validation s'arrête à la phase de présence,
cf. ordre du contrat pilote §6.)*

| Champ | Type | Obligatoire | Règle |
|---|---|---|---|
| `code` | string (enum) | **oui** | `VALIDATION_ERROR` \| `MISSING_INFORMATION` \| `INVALID_STATUS` \| `REQUEST_ALREADY_CLOSED` \| `REQUEST_NOT_FOUND` \| `DOCUMENT_NOT_FOUND` \| `TEMPLATE_NOT_FOUND` \| `DOCUMENT_GENERATION_ERROR` \| `DATABASE_ERROR` \| `UNAUTHORIZED` \| `FORBIDDEN` \| `INTERNAL_ERROR` |
| `message` | string | **oui** | texte FR destiné à l'utilisateur, aucune stack trace |
| `correlationId` | UUID | **oui** | = en-tête `X-Correlation-Id` |
| `missingFields[]` | string[] | **oui** (vide si non applicable) | liste des clés obligatoires absentes |
| `requestId` | UUID | non | seulement si la demande est persistée |
| `status` | enum | non | statut de la demande si persistée |
| `fieldErrors[].field` / `.code` / `.message` | string | non | détail par champ (familles `ERR_*` du pilote §10) |
| `errors[].path` / `.code` / `.message` | string | non | utilisé par `POST /extraction/validate` (violations de schéma) |

### 2.4 `ExtractionResult` (sortie d'IA — alignée AGENTS.md §4.3)

```json
{
  "documentType": "ATTESTATION_CONCORDANCE",
  "confidence": 0.97,
  "data": {
    "prenom": "Maria",
    "nom": "Gomes",
    "dateNaissance": "1985-05-12",
    "lieuNaissance": "Bissau",
    "nomIncorrect": "Maria Gomez",
    "nomCorrect": "Maria Gomes"
  },
  "missingFields": []
}
```

| Champ | Type | Obligatoire | Règles |
|---|---|---|---|
| `documentType` | string (enum) | oui | doit être supporté |
| `confidence` | number | oui | 0..1 ; métadonnée, jamais source de vérité |
| `data` | object | oui | `additionalProperties: false` ; même schéma que `CreateRequestRequest.data` ; `dateNaissance` peut encore être au format `dd/MM/yyyy` (normalisation backend) |
| `missingFields` | string[] | oui | sous-ensemble des clés obligatoires ; l'IA **signalera** les absences, elle ne les comblera jamais |

### 2.5 `GenerateDocumentResponse`

```json
{
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "documentId": "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e",
  "status": "GENERATED",
  "fileName": "attestation-concordance-6f0b3a7e.docx",
  "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "downloadPath": "/api/v1/requests/6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d/documents/b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e",
  "generatedAt": "2026-10-02T09:16:40Z"
}
```

### 2.6 `ExtractionValidationResponse`

```json
{
  "valid": false,
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "errors": [
    { "path": "/data/dateNaissance", "code": "ERR_DATE_FORMAT_INVALIDE",
      "message": "Format de date invalide. Formats acceptés : yyyy-MM-dd ou dd/MM/yyyy." },
    { "path": "/documentType", "code": "ERR_DOCUMENT_TYPE_NON_SUPPORTE",
      "message": "Type de document non pris en charge pour cette itération." }
  ]
}
```

### 2.7 `HealthResponse`

```json
{ "status": "UP" }
```

---

## 3. ENDPOINTS

Récapitulatif :

| Méthode | Chemin | Objet |
|---|---|---|
| `POST` | `/api/v1/requests` | Créer une demande d'attestation |
| `GET` | `/api/v1/requests/{requestId}` | Consulter l'état d'une demande |
| `PATCH` | `/api/v1/requests/{requestId}` | Complétion partielle d'une demande (information manquante fournie par l'utilisateur) |
| `POST` | `/api/v1/requests/{requestId}/validate` | Relancer la validation déterministe |
| `POST` | `/api/v1/requests/{requestId}/generate` | Générer le DOCX |
| `GET` | `/api/v1/requests/{requestId}/documents/{documentId}` | Télécharger le document généré |
| `GET` | `/api/v1/health` | Santé du service (alternatif : `/actuator/health`) |
| `POST` | `/api/v1/extraction/validate` | Valider un JSON d'extraction IA contre le schéma |

---

### 3.1 `POST /api/v1/requests`

Crée une demande d'attestation à partir de données extraites puis normalisées,
exécute la validation déterministe et retourne le statut résultant.

- **Headers** : `Content-Type: application/json` · `X-Correlation-Id?`
- **Request** : `CreateRequestRequest` (§2.1)

| Code | Corps | Condition |
|---|---|---|
| `201 Created` | `RequestStatusResponse` (`status = VALIDATED`, `missingFields = []`) | Toutes les règles passent |
| `400 Bad Request` | `ErrorResponse` (`code = VALIDATION_ERROR`) | Enveloppe invalide (JSON malformé, `documentType` inconnu → **non persistée**) **ou** règle métier violée (`fieldErrors[]` → **persistée en `REJECTED`**, `requestId` retourné) |
| `422 Unprocessable Entity` | `ErrorResponse` (`code = MISSING_INFORMATION`, `missingFields[]`) | ≥ 1 champ obligatoire absent → **persistée en `MISSING_INFORMATION`**, `requestId` retourné |
| `413 Payload Too Large` | `ErrorResponse` (`code = VALIDATION_ERROR`) | Corps dépassant la taille maximale (`app.document.max-payload-bytes`, défaut 65 536 octets) → rejeté avant désérialisation, **non persistée** (documentation D2 ; valable pour tout endpoint à corps) |
| `500 Internal Server Error` | `ErrorResponse` (`code = DATABASE_ERROR` \| `INTERNAL_ERROR`) | Erreur technique ; `correlationId` obligatoire |

Exemple `422` :

```json
{
  "code": "MISSING_INFORMATION",
  "message": "Information manquante pour générer le document.",
  "correlationId": "c9e1f0a2-5f3d-4b7e-8a10-2b3c4d5e6f70",
  "missingFields": ["dateNaissance", "lieuNaissance"],
  "requestId": "6f0b3a7e-3a4e-4d0a-9d9a-1f7c1a2b3c4d",
  "status": "MISSING_INFORMATION",
  "fieldErrors": []
}
```

---

### 3.2 `GET /api/v1/requests/{requestId}`

Consultation d'état (polling n8n, affichage conversationnel).

- **Headers** : `Accept: application/json` · `X-Correlation-Id?`
- **Path params** : `requestId` (UUID v4)

| Code | Corps | Condition |
|---|---|---|
| `200 OK` | `RequestStatusResponse` (§2.2) | Demande existante |
| `404 Not Found` | `ErrorResponse` (`code = REQUEST_NOT_FOUND`) | `requestId` inconnu / inexistant |
| `500` | `ErrorResponse` | Erreur technique |

---

### 3.3 `PATCH /api/v1/requests/{requestId}`

Complétion partielle d'une demande : utilisé par n8n quand l'utilisateur
fournit l'information manquante. Effectue une **fusion (merge)** des champs
fournis sur les données persistées, puis **recalcule le statut** par
validation déterministe. Aucune donnée n'est jamais inventée : seuls les
champs présents dans le corps sont modifiés.

- **Headers** : `Content-Type: application/json` · `X-Correlation-Id?`
- **Request** : objet partiel des champs du document (toutes les clés sont
  **optionnelles** dans ce payload — au minimum les 6 champs obligatoires
  et les champs optionnels du pilote §3/§4) :

```json
{
  "dateNaissance": "12/05/1985",
  "lieuNaissance": "Bissau"
}
```

| Règle du payload | Détail |
|---|---|
| Clés | un sous-ensemble des clés de `CreateRequestRequest.data` (cf. pilote §3/§4) ; **clés inconnues refusées** → `400 ERR_CHAMP_INCONNU` |
| `referenceDemande` | interdit (réservé au système) → `400 ERR_CHAMP_RESERVE` |
| Fusion | seuls les champs fournis sont écrasés ; les autres sont conservés |
| Recalcul de statut | exécuté systématiquement après fusion, depuis tout statut **non terminal** (`DRAFT`, `MISSING_INFORMATION`, `VALIDATED`, `FAILED`) |

| Code | Corps | Condition |
|---|---|---|
| `200 OK` | `RequestStatusResponse` (statut recalculé) | Statut **net** recalculé après fusion : `MISSING_INFORMATION` → `VALIDATED` si les 6 champs obligatoires sont désormais tous présents et toutes les règles passent (T5) ; `MISSING_INFORMATION` conservé sinon (T6b) ; `VALIDATED` conservé (T11) ou rétrogradé (`MISSING_INFORMATION` T7 / `REJECTED` T10) ; `FAILED` repris (T12 / T12c) |
| `400 Bad Request` | `ErrorResponse` (`code = VALIDATION_ERROR`, `fieldErrors[].code = ERR_CHAMP_INCONNU` \| `ERR_CHAMP_RESERVE`) | **Clé inconnue ou réservée = erreur structurelle de requête** (arbitrage humain F-03) : `document_request` **non modifié**, **jamais** passé à `REJECTED`, aucune mutation métier persistée (un événement d'audit technique sans PII reste possible) |
| `400 Bad Request` | `ErrorResponse` (`code = VALIDATION_ERROR`, `fieldErrors[]`) | Règle métier violée par les données fusionnées → `status = REJECTED` (persistée, transitions T4 / T6 / T10 / T12b) |
| `404 Not Found` | `ErrorResponse` (`code = REQUEST_NOT_FOUND`) | Demande inconnue |
| `409 Conflict` | `ErrorResponse` (`code = REQUEST_ALREADY_CLOSED`) | Statut terminal : `GENERATED` ou `REJECTED` — demande close, aucune modification possible |
| `500 Internal Server Error` | `ErrorResponse` (`code = DATABASE_ERROR` \| `INTERNAL_ERROR`) | Erreur technique ; `correlationId` obligatoire |

> **Note d'itération** : les transitions `MISSING_INFORMATION →
> MISSING_INFORMATION` (T6b) et `MISSING_INFORMATION → VALIDATED` (T5)
> existaient déjà pour `POST .../validate` ; ce `PATCH` en est le
> **déclencheur principal en itération 1** (alimentation par la
> conversation). Le `PATCH` expose directement le statut final : il ne passe
> **jamais** par `DRAFT` (état interne réservé à la création, T1 → T2/T3/T4).

---

### 3.4 `POST /api/v1/requests/{requestId}/validate`

Relance **idempotente** la validation déterministe sur les données persistées
(garde avant génération, reprise après `FAILED`). Ne prend **aucun corps**.

- **Headers** : `Accept: application/json` · `X-Correlation-Id?`
- **Request body** : aucun

| Code | Corps | Condition |
|---|---|---|
| `200 OK` | `RequestStatusResponse` (`status = VALIDATED`, `missingFields = []`) | Toutes les règles passent (transition T2/T5/T11/T12) |
| `400 Bad Request` | `ErrorResponse` (`code = VALIDATION_ERROR`, `fieldErrors[]`) | Règle métier violée → `status = REJECTED` (transitions T4 / T6 / T12b) |
| `422 Unprocessable Entity` | `ErrorResponse` (`code = MISSING_INFORMATION`, `missingFields[]`) | ≥ 1 champ obligatoire toujours absent → `status = MISSING_INFORMATION` conservé (boucle T6b) |
| `404 Not Found` | `ErrorResponse` (`code = REQUEST_NOT_FOUND`) | Demande inconnue |
| `409 Conflict` | `ErrorResponse` (`code = INVALID_STATUS`) | Statut source interdit : `GENERATED` ou `REJECTED` (transitions T13/T14) |
| `500` | `ErrorResponse` (`code = DATABASE_ERROR` \| `INTERNAL_ERROR`) | Erreur technique |

---

### 3.5 `POST /api/v1/requests/{requestId}/generate`

Génère le DOCX en fusionnant les données validées dans le template officiel.
Aucun corps (le template et les données sont déterminés par le `requestId`).

- **Headers** : `Accept: application/json` · `X-Correlation-Id?`
- **Request body** : aucun

| Code | Corps | Condition |
|---|---|---|
| `201 Created` | `GenerateDocumentResponse` (§2.5), `status = GENERATED` | `status = VALIDATED` **et** template trouvé **et** fusion réussie (transition T8) |
| `404 Not Found` | `ErrorResponse` (`code = REQUEST_NOT_FOUND`) | Demande inconnue |
| `409 Conflict` | `ErrorResponse` (`code = INVALID_STATUS`, message citant le statut actuel) | `status ≠ VALIDATED` (`DRAFT`, `MISSING_INFORMATION`, `REJECTED`, `GENERATED`, `FAILED`) |
| `500 Internal Server Error` | `ErrorResponse` (`code = TEMPLATE_NOT_FOUND` \| `DOCUMENT_GENERATION_ERROR`) | Template absent ou échec de fusion → `status = FAILED` (transition T9), `correlationId` obligatoire |

**Décision** : réponse **`201`** (génération synchrone en itération 1).
Le `202 Accepted` + polling est réservé à une éventuelle génération
asynchrone en itération 2 (point OQ-API-2).

---

### 3.6 `GET /api/v1/requests/{requestId}/documents/{documentId}`

Téléchargement (autorisé) du document généré.

- **Headers** : `Accept: application/octet-stream` · `X-Correlation-Id?`
- **Path params** : `requestId`, `documentId` (UUID v4)

| Code | Corps | Headers de réponse | Condition |
|---|---|---|---|
| `200 OK` | binaire DOCX (octets) | `Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document` · `Content-Disposition: attachment; filename="attestation-concordance-<requestId>.docx"` | Document existant et appartenant à la demande |
| `404 Not Found` | `ErrorResponse` (`code = DOCUMENT_NOT_FOUND` \| `REQUEST_NOT_FOUND`) | JSON | `documentId` ou `requestId` inconnu, ou document d'une autre demande |
| `401` (itération 2) | `ErrorResponse` (`code = UNAUTHORIZED`) | JSON | Token absent/expiré |
| `500` | `ErrorResponse` (`code = INTERNAL_ERROR`) | JSON | Erreur de lecture du stockage |

---

### 3.7 `GET /api/v1/health`

- **Headers** : aucun requis (seul endpoint non protégé en itération 2)

| Code | Corps | Condition |
|---|---|---|
| `200 OK` | `HealthResponse` `{ "status": "UP" }` | Service opérationnel |
| `503 Service Unavailable` | `HealthResponse` `{ "status": "DOWN" }` | Dépendance critique indisponible (base de données) |

**Alternative** : Spring Actuator expose `/actuator/health` avec le même
sens métier ; l'un ou l'autre fait foi (décision d'implémentation — voir
OQ-API-3). Aucune information technique détaillée (versions, beans) exposée
en production.

---

### 3.8 `POST /api/v1/extraction/validate`

Valide un JSON d'extraction IA contre le schéma **avant** toute création de
demande (utile à n8n comme garde de forme).

- **Headers** : `Content-Type: application/json` · `X-Correlation-Id?`
- **Request** : `ExtractionResult` (§2.4) — corps brut à contrôler

| Code | Corps | Condition |
|---|---|---|
| `200 OK` | `ExtractionValidationResponse` (`valid = true`, `errors = []`) | Schéma respecté : types, clés autorisées, enums, `confidence` ∈ [0,1], `missingFields` ⊆ clés obligatoires, `documentType` supporté |
| `400 Bad Request` | `ExtractionValidationResponse` (`valid = false`, `errors[]` avec `path` JSON Pointer) | JSON malformé (`ERR_PAYLOAD_INVALIDE`), clé inconnue, type erroné, enum invalide, `documentType` non supporté (`ERR_DOCUMENT_TYPE_NON_SUPPORTE`), format de date non reconnu (`ERR_DATE_FORMAT_INVALIDE`) |
| `500` | `ErrorResponse` | Erreur technique |

**Portée** : contrôle de **forme** uniquement. La validation métier complète
(champs obligatoires de fond, cohérence inter-champs) reste l'exclusive du
`POST /api/v1/requests` (AGENTS.md §4.1 — le LLM n'est jamais source de
vérité).

---

## 4. MAPPING HTTP ↔ CODES MÉTIER

| HTTP | `code` (ErrorResponse) | Utilisé par |
|---|---|---|
| `400` | `VALIDATION_ERROR` (sous-codes `ERR_*` du pilote §10.2) | `POST /requests`, `POST /validate`, `POST /extraction/validate` |
| `401` | `UNAUTHORIZED` | tous (sauf health) — **itération 2** |
| `403` | `FORBIDDEN` | accès à une demande d'un tiers — **itération 2** |
| `404` | `REQUEST_NOT_FOUND` / `DOCUMENT_NOT_FOUND` | `GET /requests/{id}`, `GET .../documents/{id}`, `POST /validate`, `POST /generate` |
| `409` | `INVALID_STATUS` \| `REQUEST_ALREADY_CLOSED` | `POST /validate`, `POST /generate` (`INVALID_STATUS`) ; `PATCH /requests/{id}` (`REQUEST_ALREADY_CLOSED` : demande close) |
| `422` | `MISSING_INFORMATION` (+ `missingFields[]`) | `POST /requests`, `POST /validate` |
| `500` | `TEMPLATE_NOT_FOUND` / `DOCUMENT_GENERATION_ERROR` / `DATABASE_ERROR` / `INTERNAL_ERROR` | `POST /generate` et endpoints généraux |
| `503` | `HealthResponse.status = DOWN` | `GET /health` |

Familles supplémentaires hors API (flux n8n, AGENTS.md §14) :
`AI_EXTRACTION_ERROR`, `EXTRACTION_SCHEMA_INVALID` (interne au comportement
`400` de `/extraction/validate`).

---

## 5. FLUX TYPES

### 5.1 Happy path

```
n8n (LLM)                Spring Boot API                    Stockage / Template
   |  POST /extraction/validate  {ExtractionResult}             |
   |------------------------->|  200 {valid:true}               |
   |  POST /requests {CreateRequestRequest}                     |
   |------------------------->|  201 {requestId, status:VALIDATED}
   |  POST /requests/{id}/generate                              |
   |------------------------->|  201 {documentId, GENERATED} -->| merge template DOCX
   |  GET  /requests/{id}/documents/{documentId}                |
   |------------------------->|  200 (binaire DOCX)  <----------|
```

### 5.2 Informations manquantes (conversation — complétion par `PATCH`)

```
n8n                         Spring Boot API
   |  POST /requests (sans dateNaissance, lieuNaissance)
   |------------------------->|  422 {code:MISSING_INFORMATION,
   |                            missingFields:[dateNaissance,lieuNaissance],
   |                            requestId, status:MISSING_INFORMATION}
   |  → pose la question à l'utilisateur (aucune invention)
   |  PATCH /requests/{requestId} {dateNaissance, lieuNaissance}
   |------------------------->|  200 {requestId, status:VALIDATED,
   |                            missingFields:[]}   ← statut recalculé
   |  POST /requests/{requestId}/generate → 201 {documentId}
```

Variante : si le `PATCH` ne complète pas tous les champs obligatoires, la
réponse reste `200 {status: MISSING_INFORMATION, missingFields: [...]}` et
la conversation poursuit la collecte sur la **même** demande.

### 5.3 Reprise après échec de génération

```
POST /requests/{id}/generate   → 500 TEMPLATE_NOT_FOUND, status=FAILED
POST /requests/{id}/validate   → 200 status=VALIDATED   (reprise T12)
POST /requests/{id}/generate   → 201 status=GENERATED
```

---

## 6. QUESTIONS OUVERTES API (OPEN_API_QUESTIONS)

| ID | Question | Statut |
|---|---|---|
| OQ-API-1 | ~~Confirmer la politique de persistance du `422` et l'absence de `PUT /requests/{id}`.~~ | **ARBITRÉE** — Orchestrateur : persistance `422` **confirmée** (§1.4) ; complétion assurée par `PATCH /api/v1/requests/{requestId}` (§3.3) — OQ-2 / OQ-3 du pilote résolues |
| OQ-API-2 | `201` (synchrone, décision retenue) vs `202 Accepted` + polling pour `/generate` : le pipeline de génération restera-t-il synchrone ? | À trancher avec l'architecte |
| OQ-API-3 | Exposer `/api/v1/health`, `/actuator/health` ou les deux ? Quel niveau de détail en production ? | À trancher avec l'architecte/security |
| OQ-API-4 | Champs `data` retournés par `GET /requests/{id}` (données personnelles) : le retour complet est-il nécessaire à n8n, ou un résumé (statut + missingFields) suffit-il ? | **Escalade security** (minimisation des données, AGENTS.md §13) |
| OQ-API-5 | Pagination / limitation de débit (rate limit) : non défini en itération 1 ? | À définir avant mise en production |
| OQ-API-6 | Versionnement `v1` : politique de breaking changes (header `API-Version` ? `Accept` versionné ?) | À trancher avec l'architecte |

---

## 7. ACCEPTANCE CRITERIA — Contrats API

- [ ] Les 8 endpoints sont documentés : chemin, méthode, headers, request
      body, réponses, codes d'erreur.
- [ ] Schémas `CreateRequestRequest`, `ExtractionResult`, `ErrorResponse`
      fournis ; schémas complémentaires (`RequestStatusResponse`,
      `GenerateDocumentResponse`, `ExtractionValidationResponse`,
      `HealthResponse`) fournis pour lever l'ambiguïté.
- [ ] `ErrorResponse` contient bien `code`, `message`, `correlationId`,
      `missingFields[]`.
- [ ] En-têtes `X-Correlation-Id`, `Content-Type`, `Authorization` documentés,
      périmètre d'authentification explicite (hors it.1, protégé en it.2).
- [ ] Toutes les conditions HTTP correspondent aux statuts et transitions du
      contrat pilote (§9) — aucune contradiction détectée en relecture.
- [ ] Chaque `code` métier du contrat pilote (§10) a un mapping HTTP.
- [ ] Aucun secret, identifiant de service ou credential présent dans le
      contrat.
- [ ] Escalades OQ-API-1 à OQ-API-6 tracées avant implémentation.
