# Architecture — Administrative Document Generator (Itération 1)

| Rubrique | Valeur |
|---|---|
| Document | `docs/architecture.md` |
| Rédacteur | agent `architect` |
| Itération | **1** — fondations techniques uniquement + pilote `ATTESTATION_CONCORDANCE` |
| Périmètre | Aucun autre type de document ; PDF, authentification, multi-document hors périmètre |
| Sources | `AGENTS.md`, `requirements/ATTESTATION_CONCORDANCE.md` (v1.0), `requirements/API_CONTRACTS.md` (v1.0) |
| Statut | **PRÊT POUR IMPLÉMENTATION** — sous réserve des points ouverts §14 (dont OQ-1) |
| Contraintes env | Windows, Java 17 (`JAVA_HOME` = JDK 17 → `maven.compiler.release=17`), Maven 3.9.10, Docker, Node 20, Python 3.13 |
| Contrainte tests | Tests unitaires + MockMvc **sans Docker** : `mvn -f backend/pom.xml test` |

Ce document est la référence d'implémentation : les agents d'implémentation
(`backend-developer`, `database-engineer`, `n8n-developer`, `ai-engineer`)
le suivent **à la lettre**. Toute divergence doit être remontée à
l'orchestrateur avant exécution.

---

## 1. Vue d'ensemble et flux de bout en bout

### 1.1 Composants et responsabilités (AGENTS.md §2)

```
Frontend / Chat
   │  (1) message utilisateur en langage naturel
   ▼
 n8n  ────── (2) extraction JSON strict ──────►  Ollama / LLM
   │                                              (classifie, extrait,
   │                                              N'INVENTE JAMAIS)
   │  (3) POST /api/v1/extraction/validate
   │  (4) POST /api/v1/requests                 ┌─── PostgreSQL 16
   │  (5) POST /api/v1/requests/{id}/validate   │    (Flyway V1/V2)
   │  (6) PATCH /api/v1/requests/{id}  ◄ boucle │    document_request
   │  (7) POST /api/v1/requests/{id}/generate   │    generated_document
   │  (8) GET  /api/v1/requests/{id}            │    template_registry
   │  (9) GET  /api/v1/requests/{id}/documents/ │    audit_log
   ▼                              {documentId}  │
 Spring Boot Document API ──────────────────────┘
   │
   ├─► template DOCX officiel (templates/, fusion déterministe Apache POI)
   └─► archive fichier (app.document.storage-path) + SHA-256
```

Responsabilités (inchangées par rapport à AGENTS.md §2) :

| Composant | Responsabilités | Ne fait JAMAIS |
|---|---|---|
| n8n | orchestration, flux conversationnel, appels API, retries, notifications | règles métier déterministes complexes, calcul de statut |
| Ollama / LLM | classification d'intention/type, extraction structurée, détection des absences | inventer des données, valider, autoriser, générer le document final |
| Spring Boot | validation déterministe, normalisation, persistance, génération DOCX, stockage, API | inventer des valeurs, rédiger le wording du document |
| PostgreSQL | données métier, métadonnées templates, documents générés, audit | — |
| Template DOCX | wording officiel approuvé, placeholders `{{...}}` | être généré par un LLM |

### 1.2 Les 8 endpoints (contrat `API_CONTRACTS.md` §3)

| # | Méthode | Chemin | Rôle dans le flux |
|---|---|---|---|
| E1 | `POST` | `/api/v1/requests` | création + validation déterministe (201 / 400 / 422) |
| E2 | `GET` | `/api/v1/requests/{requestId}` | consultation d'état / polling n8n |
| E3 | `PATCH` | `/api/v1/requests/{requestId}` | complétion partielle (boucle `422` → utilisateur → merge → re-validation) |
| E4 | `POST` | `/api/v1/requests/{requestId}/validate` | re-validation idempotente (garde avant génération, reprise `FAILED`) |
| E5 | `POST` | `/api/v1/requests/{requestId}/generate` | fusion template → DOCX (201 / 409 / 500) |
| E6 | `GET` | `/api/v1/requests/{requestId}/documents/{documentId}` | téléchargement binaire DOCX |
| E7 | `GET` | `/api/v1/health` | santé du service (healthcheck Docker) |
| E8 | `POST` | `/api/v1/extraction/validate` | garde de forme du JSON d'extraction IA |

### 1.3 Chaîne exacte des appels — happy path

```
Frontend → n8n    : POST /webhook/document-generation {message}
n8n → Ollama      : POST {OLLAMA_BASE_URL}/api/chat  (prompt système du §9,
                    format JSON strict imposé par le schéma du §8)
n8n               : parse strict du JSON — échec ⇒ erreur AI_EXTRACTION_ERROR
n8n → Backend     : E8  POST /api/v1/extraction/validate    → 200 {valid:true}
n8n → Backend     : E1  POST /api/v1/requests               → 201 {status:VALIDATED}
n8n → Backend     : E4  POST /api/v1/requests/{id}/validate → 200 {status:VALIDATED}  (garde)
n8n → Backend     : E5  POST /api/v1/requests/{id}/generate → 201 {documentId, GENERATED}
n8n → Backend     : E6  GET  /api/v1/requests/{id}/documents/{documentId} → 200 DOCX
n8n → Frontend    : réponse {requestId, status, downloadPath}
```

### 1.4 Chaîne exacte des appels — informations manquantes (boucle)

```
n8n → Backend     : E1 POST /api/v1/requests (sans dateNaissance, lieuNaissance)
Backend           : persiste status=MISSING_INFORMATION,
                    missingFields=[dateNaissance, lieuNaissance]  (OQ-3 confirmée)
n8n → Frontend    : question posée à l'utilisateur — AUCUNE valeur inventée
Utilisateur       : fournit les champs manquants
n8n → Backend     : E3 PATCH /api/v1/requests/{requestId} {dateNaissance, lieuNaissance}
                    → 200 {status:VALIDATED, missingFields:[]}   (recalcul, jamais par DRAFT)
n8n → Backend     : E4 validate (garde) → E5 generate → E6 download (cf. §1.3)
```

Variante : si le `PATCH` ne complète pas tout → `200
{status:MISSING_INFORMATION}` et la boucle repart (T6b).
`E2 GET /api/v1/requests/{id}` sert au polling et à la reprise de
conversation (nouveau webhook muni du `requestId`).

### 1.5 Chaîne exacte des appels — reprise après échec

```
E5 generate → 500 TEMPLATE_NOT_FOUND | DOCUMENT_GENERATION_ERROR, status=FAILED
E4 validate → 200 status=VALIDATED            (transition T12)
E5 generate → 201 status=GENERATED
```

`E7 GET /api/v1/health` est hors flux métier : healthcheck Docker du
backend ; en it.2, unique endpoint non protégé.

---

## 2. Décisions techniques (tableau ADR)

| ADR | Sujet | Décision | Justification |
|---|---|---|---|
| ADR-01 | Langage / JDK | **Java 17**, `maven.compiler.release=17` | env. imposé (JDK 17, Maven 3.9.10) ; LTS stable pour Spring Boot 3.3 |
| ADR-02 | Framework | **Spring Boot 3.3.x** (dernière patch 3.3.x à l'implémentation) — starters `web`, `validation`, `data-jpa`, `actuator`, `postgresql` + `org.flywaydb:flyway-core` + `flyway-database-postgresql` | contrat REST + JPA + validation + health + migrations ; pas de saut de version (3.4/4.x) en it.1 |
| ADR-03 | Base de données | **PostgreSQL 16** (`postgres:16-alpine`) | JSONB et `TEXT[]` natifs requis par la DDL §3 ; image légère |
| ADR-04 | Migrations | **Flyway**, migrations versionnées dans `database/migrations/` (AGENTS.md §10), localisation `filesystem:${MIGRATIONS_DIR:../database/migrations}` | source de vérité unique hors classpath ; `MIGRATIONS_DIR=/app/migrations` en Docker ; aucun schéma modifié hors migration |
| ADR-05 | Génération DOCX | **Apache POI** (`poi-ooxml` 5.2.x) via `PoiTemplateEngine` | 100 % Java, aucune dépendance OS ; remplacement déterministe `{{...}}` |
| ADR-06 | Architecture | **Hexagonale par packages** `com.adgendoc.{api,application,domain,infrastructure}` | règles métier dans `domain`/`application`, ports implémentés par `infrastructure` ; nouveau type de document = nouvelles règles sans toucher au transport |
| ADR-07 | Sérialisation | **Jackson** ; `HttpMessageNotReadableException` → `400 ERR_PAYLOAD_INVALIDE` ; clés inconnues captées par `@JsonAnySetter` sur les DTO puis rejet `ERR_CHAMP_INCONNU` | codes métier exacts du contrat (S11, S12) ; pas de `fail-on-unknown-properties` global |
| ADR-08 | Validation d'entrée | **Bean Validation (jakarta.validation)** sur les DTO d'entrée (`@NotBlank`, `@Size`, `@Pattern`) + règles métier dans `ValidationService` | contraintes structurelles au bord, règles métier côté applicatif |
| ADR-09 | Tests | **JUnit 5 + AssertJ + MockMvc** (`spring-boot-starter-test`) ; **Testcontainers : NON en it.1** ; aucun `@SpringBootTest` avec `DataSource` dans la suite par défaut ; `@WebMvcTest` + services mockés | `mvn -f backend/pom.xml test` doit passer **sans Docker** (contrainte env) |
| ADR-10 | Schéma d'extraction | **JSON Schema draft 2020-12**, validateur `com.networknt:json-schema-validator` ; schéma canonique `prompts/extraction/attestation_concordance.schema.json` copié dans le classpath via ressource Maven `../prompts/extraction` | source de vérité unique versionnée sous `prompts/` (AGENTS.md §3) ; E8 et les tests utilisent le même fichier |
| ADR-11 | Stockage | **Système de fichiers** via `FileSystemStorageAdapter`, chemin `app.document.storage-path` ; nom `<requestId>/<documentId>.docx` **généré côté serveur** | it.1 sans objet externe ; aucun chemin utilisateur → zéro path traversal |
| ADR-12 | Génération | **Synchrone, `201`** pour `POST .../generate` | OQ-API-2 : `202` + polling reporté it.2 |
| ADR-13 | Santé du service | `GET /api/v1/health` maison (délègue à la santé DataSource) → `{"status":"UP"|"DOWN"}` ; `/actuator/health` aussi exposé | E7 du contrat + healthcheck Docker ; OQ-API-3 : les deux en it.1, arbitrage security en fin d'itération |
| ADR-14 | Horloge | Bean `java.time.Clock` UTC (`ZoneOffset.UTC`), injecté dans `ValidationService` | dates (N3, `ERR_DATE_FUTUR`) testées de façon déterministe (S05) |

---

## 3. DDL SQL — `database/migrations/`

### 3.1 `V1__init.sql` (contenu exact)

```sql
-- V1__init.sql — Administrative Document Generator, itération 1.
-- Source de vérité du schéma (AGENTS.md §10 : aucun changement hors migration).

CREATE TABLE document_request (
    request_id     UUID         PRIMARY KEY,
    document_type  VARCHAR(50)  NOT NULL
        CONSTRAINT ck_document_request_type
            CHECK (document_type IN ('ATTESTATION_CONCORDANCE')),
    status         VARCHAR(30)  NOT NULL
        CONSTRAINT ck_document_request_status
            CHECK (status IN ('DRAFT', 'MISSING_INFORMATION', 'VALIDATED',
                              'REJECTED', 'GENERATED', 'FAILED')),
    payload        JSONB        NOT NULL,
    missing_fields TEXT[]       NOT NULL DEFAULT '{}',
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE generated_document (
    document_id  UUID          PRIMARY KEY,
    request_id   UUID          NOT NULL
        CONSTRAINT fk_generated_document_request
            REFERENCES document_request (request_id) ON DELETE RESTRICT,
    storage_path VARCHAR(1024) NOT NULL,
    mime_type    VARCHAR(100)  NOT NULL,
    byte_size    BIGINT        NOT NULL
        CONSTRAINT ck_generated_document_byte_size CHECK (byte_size > 0),
    sha256       CHAR(64)      NOT NULL
        CONSTRAINT ck_generated_document_sha256
            CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_generated_document_request_sha256 UNIQUE (request_id, sha256)
);

CREATE TABLE template_registry (
    code       VARCHAR(100)  NOT NULL,
    version    VARCHAR(20)   NOT NULL,
    file_path  VARCHAR(1024) NOT NULL, -- nom de fichier résolu via app.document.template-dir
    checksum   CHAR(64)      NOT NULL
        CONSTRAINT ck_template_registry_checksum
            CHECK (checksum ~ '^[0-9a-f]{64}$'),
    active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_template_registry PRIMARY KEY (code, version),
    CONSTRAINT uq_template_registry_checksum UNIQUE (checksum)
);

-- Une seule version active par code de template.
CREATE UNIQUE INDEX uq_template_registry_active_code
    ON template_registry (code) WHERE active;

CREATE TABLE audit_log (
    id             BIGSERIAL    PRIMARY KEY,
    request_id     UUID
        CONSTRAINT fk_audit_log_request
            REFERENCES document_request (request_id) ON DELETE SET NULL,
    action         VARCHAR(100) NOT NULL, -- REQUEST_CREATED, REQUEST_PATCHED,
                                          -- VALIDATION_PASSED, DOCUMENT_GENERATED...
    actor          VARCHAR(100) NOT NULL, -- n8n, system
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    details        JSONB        NOT NULL DEFAULT '{}'::jsonb
    -- SANS PII : uniquement métadonnées techniques (status, errorCode,
    -- nbMissingFields, durationMs, documentType...).
    -- Jamais de valeurs de data (noms, dates, lieux, références...).
);

-- Index requis : status, correlation_id, request_id
CREATE INDEX idx_document_request_status         ON document_request (status);
CREATE INDEX idx_document_request_correlation_id ON document_request (correlation_id);
CREATE INDEX idx_generated_document_request_id   ON generated_document (request_id);
CREATE INDEX idx_audit_log_request_id            ON audit_log (request_id);
```

Notes d'implémentation JPA associées :

- `payload` → `@JdbcTypeCode(SqlTypes.JSON)` ; `missing_fields` →
  `@JdbcTypeCode(SqlTypes.ARRAY)` (sinon `spring.jpa.hibernate.ddl-auto:
  validate` échoue sur ces colonnes) ;
- `version` → `@Version` (optimistic locking) ;
- `payload` stocke l'enveloppe `{documentType, data:{données normalisées},
  extraction:{confidence, modelId, promptVersion}}` ;
- `missing_fields` = **liste ordonnée** selon l'ordre canonique (§5.3) ;
- `fieldErrors` ne sont **pas** stockés (recalculés à chaque validation) ;
  seuls `status` et `missing_fields` sont persistés à côté du payload.

### 3.2 `V2__seed_template.sql` (contenu exact)

```sql
-- V2__seed_template.sql — seed du template pilote.
-- IMPORTANT : le checksum est calculé APRÈS création de
-- templates/attestation_concordance_v1.docx :
--   PowerShell : (Get-FileHash -Algorithm SHA256 <fichier>).Hash.ToLower()
-- La valeur ci-dessous est un placeholder 64 zéros à remplacer avant exécution.
-- En exécution, mismatch checksum ⇒ TEMPLATE_NOT_FOUND (jamais de document
-- produit à partir d'un template altéré).

INSERT INTO template_registry (code, version, file_path, checksum, active)
VALUES (
    'ATTESTATION_CONCORDANCE',
    '1.0',
    'attestation_concordance_v1.docx',
    '0000000000000000000000000000000000000000000000000000000000000000',
    TRUE
);
```

---

## 4. Arborescence backend exacte

```
backend/
├── pom.xml
├── Dockerfile
├── src/
│   ├── main/
│   │   ├── resources/
│   │   │   └── application.yml
│   │   └── java/com/adgendoc/
│   │       ├── DocumentGeneratorApplication.java
│   │       ├── api/
│   │       │   ├── DocumentRequestController.java
│   │       │   ├── ExtractionController.java
│   │       │   ├── HealthController.java
│   │       │   ├── GlobalExceptionHandler.java
│   │       │   └── dto/
│   │       │       ├── CreateRequestRequest.java
│   │       │       ├── PatchRequestRequest.java
│   │       │       ├── RequestStatusResponse.java
│   │       │       ├── ErrorResponse.java
│   │       │       ├── GenerateDocumentResponse.java
│   │       │       └── ExtractionValidationResponse.java
│   │       ├── application/
│   │       │   ├── RequestService.java
│   │       │   ├── ValidationService.java
│   │       │   ├── NormalizationService.java
│   │       │   ├── DocumentGenerationService.java
│   │       │   ├── ExtractionValidationService.java
│   │       │   └── ValidationOutcome.java            (record interne complémentaire)
│   │       ├── domain/
│   │       │   ├── DocumentRequest.java
│   │       │   ├── RequestStatus.java                (enum, 6 statuts + T1–T14)
│   │       │   ├── ErrorCode.java                    (enum, codes API + ERR_*)
│   │       │   ├── Template.java
│   │       │   ├── GeneratedDocument.java            (complément, mappé à la DDL)
│   │       │   ├── exceptions/
│   │       │   │   ├── RequestNotFoundException.java
│   │       │   │   ├── DocumentNotFoundException.java
│   │       │   │   ├── InvalidStatusException.java
│   │       │   │   ├── RequestAlreadyClosedException.java
│   │       │   │   ├── TemplateNotFoundException.java
│   │       │   │   └── DocumentGenerationException.java
│   │       │   └── ports/
│   │       │       ├── RequestRepository.java
│   │       │       ├── GeneratedDocumentRepository.java
│   │       │       ├── TemplateRepository.java
│   │       │       ├── TemplateEngine.java
│   │       │       ├── DocumentStorage.java
│   │       │       └── AuditPort.java
│   │       └── infrastructure/
│   │           ├── persistence/
│   │           │   ├── DocumentRequestEntity.java
│   │           │   ├── DocumentRequestJpaRepository.java
│   │           │   ├── DocumentRequestMapper.java
│   │           │   ├── RequestRepositoryAdapter.java
│   │           │   ├── GeneratedDocumentEntity.java
│   │           │   ├── GeneratedDocumentJpaRepository.java
│   │           │   ├── GeneratedDocumentMapper.java
│   │           │   ├── GeneratedDocumentRepositoryAdapter.java
│   │           │   ├── TemplateRegistryEntity.java
│   │           │   ├── TemplateRegistryJpaRepository.java
│   │           │   ├── TemplateRepositoryAdapter.java
│   │           │   ├── AuditLogEntity.java
│   │           │   ├── AuditLogJpaRepository.java
│   │           │   └── AuditAdapter.java
│   │           ├── docx/
│   │           │   └── PoiTemplateEngine.java
│   │           ├── storage/
│   │           │   └── FileSystemStorageAdapter.java
│   │           └── config/
│   │               ├── AppProperties.java
│   │               ├── AppConfig.java
│   │               ├── CorrelationIdFilter.java
│   │               └── PayloadSizeLimitFilter.java
│   └── test/
│       └── java/com/adgendoc/
│           ├── application/
│           │   ├── NormalizationServiceTest.java
│           │   ├── ValidationServiceTest.java
│           │   ├── DocumentGenerationServiceTest.java   (complément)
│           │   └── ExtractionSchemaValidationTest.java
│           ├── domain/
│           │   └── RequestStatusTransitionTest.java
│           └── api/
│               ├── DocumentRequestControllerTest.java
│               └── ExtractionControllerTest.java        (complément)
```

### 4.1 Responsabilité de chaque classe (une ligne)

**`api` — transport HTTP uniquement, zéro règle métier :**

| Classe | Responsabilité |
|---|---|
| `DocumentRequestController` | Expose E1 `POST /requests`, E2 `GET /requests/{id}`, E3 `PATCH`, E4 `validate`, E5 `generate`, E6 download ; délègue intégralement à `RequestService` / `DocumentGenerationService`. |
| `ExtractionController` | Expose E8 `POST /extraction/validate` ; délègue à `ExtractionValidationService`, renvoie `ExtractionValidationResponse` (200/400). |
| `HealthController` | Expose E7 `GET /api/v1/health` ; renvoie `{"status":"UP"}` (200) ou `{"status":"DOWN"}` (503) selon la santé de la base. |
| `GlobalExceptionHandler` | Convertit exceptions et erreurs de validation en `ErrorResponse` (message FR, `correlationId`, **jamais de stack trace**) avec le bon code HTTP. |
| `dto/CreateRequestRequest` | DTO d'entrée E1 : `documentType`, `data` (`Map<String, JsonNode>`), `extraction` optionnel ; `@JsonAnySetter` pour les clés inconnues. |
| `dto/PatchRequestRequest` | DTO d'entrée E3 : `data` partiel (`Map<String, JsonNode>`) + `@JsonAnySetter` clés inconnues ; toutes les clés optionnelles. |
| `dto/RequestStatusResponse` | DTO de sortie E1–E4 : `requestId`, `referenceDemande`, `documentType`, `status`, `missingFields`, `fieldErrors`, `data`, `documents`, `createdAt`, `updatedAt`. |
| `dto/ErrorResponse` | DTO de sortie des erreurs : `code`, `message` (FR), `correlationId`, `missingFields`, `requestId?`, `status?`, `fieldErrors[]` (record imbriqué `field/code/message`), `errors[]` (record imbriqué `path/code/message` pour E8). |
| `dto/GenerateDocumentResponse` | DTO de sortie E5 : `requestId`, `documentId`, `status`, `fileName`, `contentType`, `downloadPath`, `generatedAt`. |
| `dto/ExtractionValidationResponse` | DTO de sortie E8 : `valid`, `correlationId`, `errors[]` (`path` = JSON Pointer, `code`, `message`). |

**`application` — cas d'usage et orchestration :**

| Classe | Responsabilité |
|---|---|
| `RequestService` | Orchestre E1 create, E2 get, E3 patch, E4 validate : enchaîne normalisation → validation → transition (§5.4) → persistance via ports → audit. |
| `ValidationService` | Validation déterministe dans l'ordre V1–V6 (§5.4) : enveloppe, clés, présence, format, cohérence ; produit un `ValidationOutcome` (statut + `missingFields` ordonnés + `fieldErrors`). |
| `NormalizationService` | Applique N1–N9 **toujours en amont** de la validation (trim, espaces, dates FR→ISO, apostrophes, capitalisation/particules, enums) ; vide après trim ⇒ champ absent (N9). |
| `DocumentGenerationService` | Vérifie `VALIDATED`, charge le template actif (checksum), fusionne via `TemplateEngine`, calcule SHA-256, stocke via `DocumentStorage`, persiste `generated_document`, transitions T8/T9, audit. |
| `ExtractionValidationService` | Valide un `ExtractionResult` contre `attestation_concordance.schema.json` (draft 2020-12) + `documentType` supporté ; renvoie `valid` + `errors[]` (forme uniquement, jamais métier). |
| `ValidationOutcome` | Record immuable `(RequestStatus status, List<String> missingFields, List<FieldError> fieldErrors)` — contrat interne service → service/controller. |

**`domain` — modèle et règles pures, aucune dépendance framework :**

| Classe | Responsabilité |
|---|---|
| `DocumentRequest` | Agrégat métier : `requestId`, `documentType`, `status`, `payload` (data normalisée), `missingFields`, `correlationId`, timestamps, `version` ; `canTransitionTo(...)`. |
| `RequestStatus` | Enum des 6 statuts + matrice des transitions autorisées T1–T14 (source de vérité, testée par `RequestStatusTransitionTest`). |
| `ErrorCode` | Enum de TOUS les codes : `VALIDATION_ERROR`, `MISSING_INFORMATION`, `INVALID_STATUS`, `REQUEST_ALREADY_CLOSED`, `REQUEST_NOT_FOUND`, `DOCUMENT_NOT_FOUND`, `TEMPLATE_NOT_FOUND`, `DOCUMENT_GENERATION_ERROR`, `DATABASE_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `INTERNAL_ERROR`, `AI_EXTRACTION_ERROR`, `EXTRACTION_SCHEMA_INVALID` **et** les sous-codes `ERR_*` utilisés dans `fieldErrors[].code`. |
| `Template` | Modèle du template : `code`, `version`, `filePath`, `checksum`, `active`. |
| `GeneratedDocument` | Modèle du document généré : `documentId`, `requestId`, `storagePath`, `mimeType`, `byteSize`, `sha256`, `createdAt`. |
| `exceptions/*` | Exceptions métier portant le code + le HTTP attendu (404/409/500) ; consommées par `GlobalExceptionHandler`. |
| `ports/RequestRepository` | Persistance de `DocumentRequest` (save, findById, update optimiste). |
| `ports/GeneratedDocumentRepository` | Persistance de `GeneratedDocument` (save, findByIdAndRequestId). |
| `ports/TemplateRepository` | Lecture de `template_registry` : template actif par code. |
| `ports/TemplateEngine` | Fusion déterministe template + variables → bytes DOCX (impl. `PoiTemplateEngine`). |
| `ports/DocumentStorage` | Écriture/lecture des fichiers DOCX (impl. `FileSystemStorageAdapter`). |
| `ports/AuditPort` | Journal d'audit (`action`, `actor`, `correlationId`, `details` sans PII). |

**`infrastructure` — implémentations techniques :**

| Classe | Responsabilité |
|---|---|
| `DocumentRequestEntity` | Entité JPA `document_request` (JSONB/array via `@JdbcTypeCode`, `@Version`). |
| `DocumentRequestJpaRepository` | Spring Data JPA sur `DocumentRequestEntity`. |
| `DocumentRequestMapper` | Mapping `DocumentRequestEntity` ⇄ `DocumentRequest` (domaine). |
| `RequestRepositoryAdapter` | Implémente `ports.RequestRepository` au-dessus du repo Spring Data + mapper. |
| `GeneratedDocumentEntity` / `GeneratedDocumentJpaRepository` / `GeneratedDocumentMapper` / `GeneratedDocumentRepositoryAdapter` | Idem pour `generated_document`. |
| `TemplateRegistryEntity` / `TemplateRegistryJpaRepository` / `TemplateRepositoryAdapter` | Idem pour `template_registry` (lecture du template actif). |
| `AuditLogEntity` / `AuditLogJpaRepository` / `AuditAdapter` | Idem pour `audit_log` — `AuditAdapter` refuse toute clé de `details` contenant des données personnelles. |
| `PoiTemplateEngine` | Remplace les placeholders `{{...}}` (paragraphes, tables, en-têtes, pieds) avec POI ; token sans valeur ⇒ chaîne vide ; échec ⇒ `TemplateNotFoundException`/`DocumentGenerationException`. |
| `FileSystemStorageAdapter` | Écrit/relit `<storage-path>/<requestId>/<documentId>.docx` (nom servi par le serveur, extension `.docx` contrôlée, chemin canonique vérifié contre échappement). |
| `AppProperties` | `@ConfigurationProperties(prefix="app")` : `document.storage-path`, `document.template-dir`, `document.max-payload-bytes`, `extraction.schema-path`. |
| `AppConfig` | Bean `Clock` UTC (`ZoneOffset.UTC`) + beans utilitaires (constructeurs injectés). |
| `CorrelationIdFilter` | Lit/génère `X-Correlation-Id` (UUID v4), le place en MDC + en-tête réponse, alimente `ErrorResponse.correlationId`. |
| `PayloadSizeLimitFilter` | Rejette tout corps > `app.document.max-payload-bytes` (413) avant désérialisation. |

---

## 5. Règles de couches

### 5.1 Sens d'appel

```
controller (api) → service (application) → port (domain) → adapter (infrastructure)
```

- `api` ne référence **jamais** les repositories JPA ni POI ;
- `infrastructure` ne référence **jamais** `api` ;
- les transitions de statut sont calculées par `domain.RequestStatus` et
  appliquées par `RequestService` — aucune transition codée en dur ailleurs.

### 5.2 Localisation des règles

- Règles métier (validation, normalisation, transitions, génération) :
  **`domain` + `application` uniquement** ;
- Règles d'infrastructure (HTTP, JPA, POI, fichiers) : `infrastructure` + `api` ;
- **Bean Validation sur les DTO d'entrée uniquement** (structure : présence,
  longueur, format) ; les règles métier (dates calendaires, concordance des
  noms, enums de domaine) restent dans `ValidationService` — jamais dans des
  annotations de DTO.

### 5.3 `MISSING_INFORMATION` = liste ordonnée

- Ordre canonique (immuable, identique au contrat pilote §3) :
  `["prenom", "nom", "dateNaissance", "lieuNaissance", "nomIncorrect", "nomCorrect"]`
- Après normalisation, un champ vide/null/espaces ⇒ **absent** (N9) ;
- `missingFields` = sous-ensemble de cette liste **dans l'ordre canonique**
  (jamais l'ordre de réception) ; identique dans la réponse 422, la colonne
  `document_request.missing_fields` et la question posée par n8n ;
- Dès qu'au moins 1 champ obligatoire est absent ⇒
  `status = MISSING_INFORMATION` (422) et **la validation s'arrête là** :
  aucun `fieldErrors` n'est produit (phase de présence avant phase de format).

### 5.4 Pipeline déterministe — TOUJOURS normaliser avant valider

```
entrée (DTO)
  → V1 enveloppe   : JSON bon, documentType supporté        [400 non persisté si échec]
  → V2 clés        : aucune inconnue, aucune réservée       [400 persisté REJECTED si échec]
  → N  NORMALISATION N1–N9 (NormalizationService)           [TOUJOURS exécutée]
  → V3 présence    : 6 obligatoires après normalisation     [422 MISSING_INFORMATION]
  → V4 format      : motifs, longueurs, dates, enums, email, téléphone
                                                       [400 persisté REJECTED]
  → V5 cohérence   : nomIncorrect ≠ nomCorrect (comparaison minuscules,
     sans espaces superflus)                                [400 persisté REJECTED]
  → V6 sinon VALIDATED                                      [201]
  → transitions conformes à RequestStatus (T1–T14)
     → 409 INVALID_STATUS / REQUEST_ALREADY_CLOSED si statut interdit/terminal
```

Règles complémentaires :

- `confidence` de l'extraction : **métadonnée d'audit uniquement** —
  n'influence jamais `ValidationService` (AGENTS.md §12) ;
- persistance (contrat §1.4) : 400 enveloppe = **non persisté** ;
  400 métier = persisté `REJECTED` avec `requestId` ; 422 = persisté
  `MISSING_INFORMATION` avec `requestId` ; 201 = persisté `VALIDATED` ;
- `PATCH` (E3) recalcule toujours le statut final **sans jamais passer par
  `DRAFT`** ; merge strict : seuls les champs fournis sont écrasés.

---

## 6. Pipeline DOCX

### 6.1 Variables de template (`TEMPLATE_VARIABLES`, contrat pilote §8)

| Variable | Origine | Valeur absente |
|---|---|---|
| `{{prenom}}` | utilisateur | jamais (VALIDATED requis) |
| `{{nom}}` | utilisateur | jamais |
| `{{date_naissance}}` | utilisateur (ISO puis format affichage — format exact à valider, OQ-8) | jamais |
| `{{lieu_naissance}}` | utilisateur | jamais |
| `{{nom_incorrect}}` | utilisateur | jamais |
| `{{nom_correct}}` | utilisateur | jamais |
| `{{sexe}}` | utilisateur (optionnel) | **vidé** (jamais inventé) |
| `{{nationalite}}` | utilisateur (optionnel) | vidé |
| `{{document_source_reference}}` | utilisateur (optionnel) | vidé |
| `{{langue_document}}` | utilisateur / défaut système `FR` | `FR` (défaut technique, pas une donnée administrative) |
| `{{demandeur}}` | utilisateur (optionnel) | vidé |
| `{{motif}}` | utilisateur (optionnel, OQ-6) | vidé |
| `{{reference_demande}}` | **système** = `requestId` (UUID v4) | jamais |
| `{{date_generation}}` | **système** = date UTC de génération | jamais |

### 6.2 Remplacement POI déterministe (`PoiTemplateEngine`)

1. Charger le DOCX du template actif (`template_registry` → `file_path`
   résolu via `app.document.template-dir`).
2. Vérifier SHA-256 du fichier == `checksum` de la registry ; fichier absent
   ou checksum mismatch ⇒ `TEMPLATE_NOT_FOUND` (500, statut `FAILED`).
3. Parcourir **paragraphes, tables, en-têtes et pieds de page** ; reconstruire
   le texte de chaque paragraphe pour gérer les `XWPFRun` découpés par Word
   (les tokens `{{...}}` peuvent être scindés sur plusieurs runs).
4. Remplacer chaque token par sa valeur ; token sans valeur ⇒ chaîne vide.
5. Aucune écriture hors tokens : le wording du document est **intouchable**
   (AGENTS.md §4.1).
6. Sérialiser les bytes, calculer le SHA-256, persister `generated_document`,
   stocker le fichier, transition `GENERATED` (T8) + audit.

### 6.3 Stockage et intégrité

- Racine : `app.document.storage-path` (local `../storage/documents`,
  Docker volume `/app/storage`) ;
- Structure : `<storage-path>/<requestId>/<documentId>.docx` — UUID générés
  côté serveur, extension `.docx` contrôlée à l'écriture **et** à la lecture ;
- Métadonnées persistées : `storage_path`, `mime_type`
  (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`),
  `byte_size` (> 0), `sha256` (hex minuscules) ;
- Téléchargement E6 : flux depuis `FileSystemStorageAdapter` avec
  `Content-Type` DOCX + `Content-Disposition: attachment;
  filename="attestation-concordance-<requestId>.docx"`.

### 6.4 PDF

**Hors périmètre it.1.** NEXT (it.2) : conversion DOCX → PDF (LibreOffice
headless ou API dédiée), statut/archivage dédiés, après stabilisation du
pilote (OQ-9).

---

## 7. Structure n8n

Fichier : `n8n/workflows/document-generation-v1.json` — export n8n **sans
aucune credential** (AGENTS.md §11) ; URLs via variables d'environnement
`BACKEND_BASE_URL`, `OLLAMA_BASE_URL` ; secrets = credentials n8n ou `.env`.

### 7.1 Nœuds (ordre du flux)

| # | Nœud | Type | Rôle |
|---|---|---|---|
| 1 | `Webhook` | Webhook (`POST /webhook/document-generation`, `responseMode: responseNode`) | entrée conversationnelle `{message, requestId?}` |
| 2 | `LoadPrompt` | Code — `fs.readFileSync('/prompts/extraction/system_prompt_attestation_concordance.md')` (volume monté en lecture seule, `NODE_FUNCTION_ALLOW_BUILTIN=fs`) | charge le prompt versionné — **source unique = `prompts/`** |
| 3 | `OllamaExtract` | HTTP Request → `POST {OLLAMA_BASE_URL}/api/chat` (modèle via `OLLAMA_MODEL`, `format` = schéma §8, `retryOnFail: true, maxTries: 3`) | extraction structurée |
| 4 | `ParseExtraction` | Code | `JSON.parse` strict ; échec ⇒ `AI_EXTRACTION_ERROR` (aucune récupération « au mieux ») ; conversion défensive `dd/MM/yyyy → yyyy-MM-dd` sur `dateNaissance` **uniquement si format reconnu** (sinon valeur telle quelle → le backend rejettera) |
| 5 | `ValidateExtraction` | HTTP Request → **E8** `POST /api/v1/extraction/validate` | garde de forme |
| 6 | `IF schemaValid` | IF | `valid=false` ⇒ réponse erreur `AI_EXTRACTION_ERROR` à l'utilisateur, aucune création |
| 7 | `CreateRequest` | HTTP Request → **E1** `POST /api/v1/requests` (`retryOnFail` sur 5xx) | création |
| 8 | `IF status` | IF (code HTTP) | `201` → nœud 12 ; `422` → nœud 9 ; `400` → réponse de l'erreur métier (`fieldErrors` affichés, aucune correction automatique) |
| 9 | `AskMissing` | Respond to Webhook | pose la question **dans l'ordre canonique** des `missingFields` ; renvoie `{requestId, status, missingFields}` ; aucune valeur proposée ni inventée |
| 10 | `PatchRequest` | HTTP Request → **E3** `PATCH /api/v1/requests/{requestId}` (déclenché au message suivant, corps = champs fournis uniquement) | complétion partielle |
| 11 | `ValidateGuard` | HTTP Request → **E4** `POST /api/v1/requests/{requestId}/validate` | garde idempotente avant génération |
| 12 | `Generate` | HTTP Request → **E5** `POST /api/v1/requests/{requestId}/generate` | génération DOCX |
| 13 | `Finalize` | Respond to Webhook | renvoie `{requestId, status, documentId, downloadPath}` ; `downloadPath` = lien **E6** |
| 14 | `PollState` (option) | HTTP Request → **E2** `GET /api/v1/requests/{requestId}` | reprise de conversation / état courant |

`E7 GET /api/v1/health` : hors workflow (healthcheck Docker du backend).

### 7.2 Règles respectées

- Aucune logique métier déterministe dans les nœuds Code (AGENTS.md §11) :
  les Code nodes ne font que formatage, parse et assemblage de prompt ;
- retries sur 5xx côté n8n (responsabilité n8n, AGENTS.md §2) ;
- export JSON réimportable à l'identique dans `n8n/workflows/`, champ
  `"credentials"` absent de tous les nœuds ;
- séparation nette : orchestration (nœuds 1, 6, 8, 9, 13) · extraction
  (nœuds 2–4) · appels API (5, 7, 10–12, 14) · génération (12) ·
  téléchargement (13).

---

## 8. Spec exacte — `prompts/extraction/attestation_concordance.schema.json`

JSON Schema **draft 2020-12** ; fichier canonique (source unique) versionné
sous `prompts/extraction/`, copié dans le classpath backend via la ressource
Maven `../prompts/extraction` (ADR-10).

Contraintes imposées :

- `required` racine : `[documentType, confidence, data, missingFields]` ;
- `documentType` : `const "ATTESTATION_CONCORDANCE"` ;
- `confidence` : `number`, `minimum 0`, `maximum 1` ;
- `data` : `additionalProperties: false`, **sans `required` interne** — une
  extraction incomplète est LÉGITIME (S09) : les absences vont dans
  `missingFields` ; la complétude est une règle métier (backend), pas de
  forme (AGENTS.md §4.1) ;
- `dateNaissance` : `pattern "^\\d{4}-\\d{2}-\\d{2}$"` ;
- `missingFields` : `array<string>`, `uniqueItems`, `items` limités aux
  6 clés obligatoires (sous-ensemble exact) ;
- `description` racine : interdiction d'inventer (« ne jamais inventer ») ;
- `allOf` conditionnel : toute clé obligatoire **absente de `data`** doit
  figurer dans `missingFields` (garde anti-oublie silencieuse).

Contenu exact à écrire :

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://adgendoc.local/prompts/extraction/attestation_concordance.schema.json",
  "title": "ExtractionResult ATTESTATION_CONCORDANCE",
  "description": "Sortie structurée de l'extraction IA pour ATTESTATION_CONCORDANCE. Ne jamais inventer : toute information administrative manquante (nom, date, lieu, référence...) doit rester absente de data et être déclarée dans missingFields. Aucune clé hors schéma.",
  "type": "object",
  "additionalProperties": false,
  "required": ["documentType", "confidence", "data", "missingFields"],
  "properties": {
    "documentType": {
      "const": "ATTESTATION_CONCORDANCE",
      "description": "Type de document supporté en itération 1."
    },
    "confidence": {
      "type": "number",
      "minimum": 0,
      "maximum": 1,
      "description": "Confiance estimée de l'extraction (métadonnée d'audit, jamais source de vérité)."
    },
    "data": {
      "type": "object",
      "additionalProperties": false,
      "description": "Champs uniquement s'ils sont présents dans la demande. Ne jamais inventer. Une clé obligatoire absente doit figurer dans missingFields.",
      "properties": {
        "prenom": { "type": "string", "minLength": 1, "maxLength": 100 },
        "nom": { "type": "string", "minLength": 1, "maxLength": 100 },
        "dateNaissance": {
          "type": "string",
          "pattern": "^\\d{4}-\\d{2}-\\d{2}$",
          "description": "Date ISO. 12/05/1985 devient 1985-05-12. Si absente ou ambiguë : la déclarer dans missingFields, ne jamais deviner."
        },
        "lieuNaissance": { "type": "string", "minLength": 1, "maxLength": 200 },
        "nomIncorrect": { "type": "string", "minLength": 1, "maxLength": 200 },
        "nomCorrect": { "type": "string", "minLength": 1, "maxLength": 200 },
        "sexe": { "type": "string", "enum": ["M", "F"] },
        "nationalite": { "type": "string", "minLength": 1, "maxLength": 100 },
        "documentSourceReference": { "type": "string", "minLength": 1, "maxLength": 100 },
        "langueDocument": { "type": "string", "enum": ["FR", "PT", "EN"] },
        "demandeur": { "type": "string", "minLength": 1, "maxLength": 200 },
        "contactEmail": { "type": "string", "minLength": 1, "maxLength": 254 },
        "contactTelephone": { "type": "string", "minLength": 6, "maxLength": 20 },
        "motif": { "type": "string", "minLength": 1, "maxLength": 500 }
      }
    },
    "missingFields": {
      "type": "array",
      "items": {
        "type": "string",
        "enum": ["prenom", "nom", "dateNaissance", "lieuNaissance", "nomIncorrect", "nomCorrect"]
      },
      "uniqueItems": true,
      "description": "Sous-ensemble des champs obligatoires réellement absents de data, dans l'ordre canonique. Jamais de valeur inventée pour les combler."
    }
  },
  "allOf": [
    { "if": { "required": ["data"],
              "properties": { "data": { "not": { "required": ["prenom"] } } } },
      "then": { "properties": { "missingFields": { "contains": { "const": "prenom" } } } } },
    { "if": { "required": ["data"],
              "properties": { "data": { "not": { "required": ["nom"] } } } },
      "then": { "properties": { "missingFields": { "contains": { "const": "nom" } } } } },
    { "if": { "required": ["data"],
              "properties": { "data": { "not": { "required": ["dateNaissance"] } } } },
      "then": { "properties": { "missingFields": { "contains": { "const": "dateNaissance" } } } } },
    { "if": { "required": ["data"],
              "properties": { "data": { "not": { "required": ["lieuNaissance"] } } } },
      "then": { "properties": { "missingFields": { "contains": { "const": "lieuNaissance" } } } } },
    { "if": { "required": ["data"],
              "properties": { "data": { "not": { "required": ["nomIncorrect"] } } } },
      "then": { "properties": { "missingFields": { "contains": { "const": "nomIncorrect" } } } } },
    { "if": { "required": ["data"],
              "properties": { "data": { "not": { "required": ["nomCorrect"] } } } },
      "then": { "properties": { "missingFields": { "contains": { "const": "nomCorrect" } } } } }
  ]
}
```

Notes d'interface :

- le `pattern` ISO impose que le nœud 4 (`ParseExtraction`) de n8n convertisse
  `dd/MM/yyyy` → `yyyy-MM-dd` avant E8 (cf. §14 R-06) ; la conversion reste
  supportée côté backend pour E1 direct (S03) ;
- validateur Java : `com.networknt:json-schema-validator` (draft 2020-12)
  dans `ExtractionValidationService` — contrôle de **forme uniquement** ;
  la validation métier complète reste l'exclusive de `POST /requests` (E1).

---

## 9. Spec — `prompts/extraction/system_prompt_attestation_concordance.md`

Contenu exact à écrire (versionné sous `prompts/extraction/`,
`promptVersion: v1`) :

````markdown
# System prompt — extraction ATTESTATION_CONCORDANCE (v1)

## Rôle

Tu es un extracteur d'informations pour documents administratifs.
À partir de la demande en langage naturel de l'utilisateur, tu identifies le
type de document demandé et tu extrais les informations qu'il a fournies, au
format JSON strict conforme au schéma `attestation_concordance.schema.json`.

Tu ne rédiges JAMAIS de document et tu ne prends JAMAIS de décision
administrative.

## Contraintes (toutes obligatoires)

1. **Sortie = un seul objet JSON**, rien d'autre : ni prose, ni markdown,
   ni bloc de code, ni commentaire, ni texte avant/après.
2. `documentType` vaut exactement `"ATTESTATION_CONCORDANCE"`. Tout autre
   type de document n'est pas supporté en itération 1 : ne le produis pas.
3. **Ne JAMAIS inventer de données administratives.** Il est interdit de
   produire, déduire ou « compléter » : noms, prénoms, dates, lieux de
   naissance, nationalités, adresses, identifiants, numéros de passeport ou
   de pièce, références administratives, énoncés légaux, ou toute
   information manquante du demandeur. Aucune valeur par défaut raisonnable.
4. N'inclus dans `data` que les informations **explicitement présentes** dans
   la demande de l'utilisateur. Une clé absente de la demande est absente de
   `data` — sauf si elle appartient aux 6 champs obligatoires (`prenom`,
   `nom`, `dateNaissance`, `lieuNaissance`, `nomIncorrect`, `nomCorrect`) :
   dans ce cas elle est **déclarée dans `missingFields`**.
5. **Date de naissance au format ISO** `yyyy-MM-dd`. Si l'utilisateur écrit
   `12/05/1985` ou `12-05-1985`, convertis en `1985-05-12`. Si la date est
   absente, partielle ou ambiguë, ne la devine jamais : déclare
   `dateNaissance` dans `missingFields`.
6. `missingFields` contient uniquement un sous-ensemble des 6 champs
   obligatoires réellement absents, dans cet ordre : `prenom`, `nom`,
   `dateNaissance`, `lieuNaissance`, `nomIncorrect`, `nomCorrect`.
   Jamais de doublon.
7. `confidence` ∈ [0, 1] : estimation honnête de ta fiabilité sur cette
   extraction. Elle ne remplace jamais une information manquante.
8. Aucune clé supplémentaire, ni à la racine ni dans `data`
   (`additionalProperties: false`). Il est interdit d'écrire
   `referenceDemande`, `requestId` ou tout champ réservé au système.
9. Enums : `sexe` ∈ {M, F} ; `langueDocument` ∈ {FR, PT, EN} — uniquement
   si l'utilisateur les a fournis.
10. Recopie fidèle des valeurs (accents, apostrophes, traits d'union). Tu ne
    corriges, ne traduis et ne reformules jamais un nom propre.

## Sortie attendue (exemple — ne jamais copier les valeurs)

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
````

---

## 10. Docker — `docker/docker-compose.yml`

### 10.1 Services

| Service | Image / build | Ports | Volumes | Healthcheck | Profil |
|---|---|---|---|---|---|
| `postgres` | `postgres:16-alpine` | `5432:5432` | `postgres_data:/var/lib/postgresql/data` | `pg_isready -U $POSTGRES_USER -d $POSTGRES_DB` (5 s, 10 retries) | défaut |
| `backend` | `build: {context: ., dockerfile: backend/Dockerfile}` | `8080:8080` | `storage_data:/app/storage` + `./templates:/app/templates:ro` | `curl -fsS http://localhost:8080/api/v1/health` | défaut |
| `n8n` | `n8nio/n8n:${N8N_VERSION:-latest}` | `5678:5678` | `n8n_data:/home/node/.n8n` + `./prompts:/prompts:ro` + `./n8n/workflows:/workflows:ro` | `wget -qO- http://localhost:5678/healthz` | défaut |
| `ollama` | `ollama/ollama` | `11434:11434` | `ollama_data:/root/.ollama` | `ollama list` | **`ollama`** (optionnel) |

Détails d'assemblage :

- réseau bridge unique `adgendoc-net` ; aucun service en `privileged` ;
- `backend` : `depends_on: postgres: condition: service_healthy` ;
  env `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}`,
  `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`,
  `MIGRATIONS_DIR=/app/migrations`, `DOCUMENT_STORAGE_PATH=/app/storage`,
  `TEMPLATE_DIR=/app/templates` ;
- `n8n` : `depends_on: backend` ; env `BACKEND_BASE_URL=http://backend:8080`,
  `OLLAMA_BASE_URL=http://ollama:11434`, `OLLAMA_MODEL`, `N8N_ENCRYPTION_KEY`,
  `NODE_FUNCTION_ALLOW_BUILTIN=fs` ;
- `ollama` : profil optionnel — le pipeline fonctionne sans lui en
  mode test (fixtures JSON) ;
- Dockerfile `backend/Dockerfile` avec **contexte = racine du dépôt** :
  étape 1 `maven:3.9-eclipse-temurin-17` →
  `mvn -f backend/pom.xml -DskipTests package` (ressources
  `../prompts/extraction` présentes) ; étape 2 `eclipse-temurin:17-jre` +
  `curl`, copie du JAR et de `database/migrations` → `/app/migrations` ;
- **Le schéma n'est appliqué que par Flyway** (AGENTS.md §10).

### 10.2 `docker/.env.example` (aucun secret réel)

```env
# docker/.env.example — copier vers docker/.env (docker/.env JAMAIS commité)
POSTGRES_DB=adgendoc
POSTGRES_USER=adgendoc
POSTGRES_PASSWORD=change_me_local_only
SPRING_DATASOURCE_USERNAME=adgendoc
SPRING_DATASOURCE_PASSWORD=change_me_local_only
N8N_ENCRYPTION_KEY=replace_by_openssl_rand_hex_32
N8N_VERSION=latest
OLLAMA_MODEL=llama3.1
```

Commandes locales :

```
docker compose -f docker/docker-compose.yml --env-file docker/.env up -d --build
docker compose -f docker/docker-compose.yml --profile ollama up -d   # + Ollama
```

---

## 11. Stratégie de tests

### 11.1 Classes de tests (sous `backend/src/test/java/com/adgendoc/`)

| Classe | Portée | Type |
|---|---|---|
| `application/NormalizationServiceTest` | règles N1–N9 (dates FR→ISO, apostrophes, particules, enums, vide ⇒ absent, casse) | unitaire pur, `Clock` fixé |
| `application/ValidationServiceTest` | ordre V1–V6, codes/messages `ERR_*`, `missingFields` ordonnés, `nomIncorrect ≠ nomCorrect`, dates calendaires/futures | unitaire pur, `Clock` fixé |
| `domain/RequestStatusTransitionTest` | matrice complète T1–T14 : transitions autorisées, interdites, statuts terminaux | unitaire pur |
| `api/DocumentRequestControllerTest` | un test **par scénario** S01–S16 (cf. 11.3) : corps, codes HTTP, `X-Correlation-Id`, absence de stack trace | `@WebMvcTest` + MockMvc, services mockés |
| `application/ExtractionSchemaValidationTest` | schéma draft 2020-12 : fixtures valides → `valid=true`, invalides → `valid=false` + `path`/`code` attendus | unitaire (validateur networknt), fixtures `tests/fixtures/` |
| `application/DocumentGenerationServiceTest` *(complément)* | T8/T9, checksum template, SHA-256, `TEMPLATE_NOT_FOUND` → `FAILED` → reprise T12 | unitaire, ports mockés |
| `api/ExtractionControllerTest` *(complément)* | E8 : 200/400, `ExtractionValidationResponse` | `@WebMvcTest` + MockMvc |

Contraintes :

- **aucune** de ces classes n'utilise Docker, `Testcontainers` ni un
  `DataSource` réel (ADR-09) ;
- fixtures lues depuis `tests/fixtures/` via la propriété système
  `fixtures.dir` (défaut `../tests/fixtures`, chemin relatif au module
  `backend/`) ;
- `mvn -f backend/pom.xml test` (env : `JAVA_HOME` = JDK 17) doit passer
  sur Windows **sans Docker**.

### 11.2 Fixtures — `tests/fixtures/` (JSON)

| Fichier | Attendu dans `ExtractionSchemaValidationTest` |
|---|---|
| `extraction_valid_complete.json` | `valid=true`, `errors=[]` |
| `extraction_missing_nom_correct.json` (S09) | `valid=true` — absence **déclarée** dans `missingFields` |
| `extraction_missing_undeclared.json` | `valid=false` — clé obligatoire absente de `data` ET non déclarée (règle `allOf`) |
| `extraction_invalid_date.json` | `valid=false` — `ERR_DATE_FORMAT_INVALIDE`, `path=/data/dateNaissance` |
| `extraction_unknown_key.json` | `valid=false` — `ERR_CHAMP_INCONNU` (clé hors schéma dans `data`) |
| `extraction_unsupported_document_type.json` | `valid=false` — `ERR_DOCUMENT_TYPE_NON_SUPPORTE`, `path=/documentType` |
| `extraction_confidence_out_of_range.json` | `valid=false` — `confidence > 1` |
| `extraction_malformed.json` | `valid=false` — `ERR_PAYLOAD_INVALIDE` (JSON tronqué) |

### 11.3 Matrice des 16 scénarios (Given / When / Then → type de test)

| # | Given | When | Then | Type de test | Classe |
|---|---|---|---|---|---|
| S01 | Payload complet valide (`Maria`/`Gomes`/`1985-05-12`/`Bissau`/`Maria Gomez`/`Maria Gomes`) | E1 `POST /requests` | `201`, `status=VALIDATED`, `requestId` UUID, `missingFields=[]`, `fieldErrors=[]` | MockMvc + unitaire Validation | `DocumentRequestControllerTest`, `ValidationServiceTest` |
| S02 | Payload sans `dateNaissance` ni `lieuNaissance` | E1 | `422`, `code=MISSING_INFORMATION`, `missingFields=[dateNaissance, lieuNaissance]` (ordre canonique), `status=MISSING_INFORMATION`, `requestId` présent, aucune valeur inventée | MockMvc + unitaire | `DocumentRequestControllerTest`, `ValidationServiceTest` |
| S03 | Payload complet, `dateNaissance="12/05/1985"` | E1 | `201`, `dateNaissance="1985-05-12"` stockée/retournée, `status=VALIDATED` | unitaire N3 + MockMvc | `NormalizationServiceTest`, `DocumentRequestControllerTest` |
| S04 | `dateNaissance="31/02/1985"` | E1 | `400`, `code=VALIDATION_ERROR`, `fieldErrors=[{dateNaissance, ERR_DATE_CALENDRIER_INVALIDE}]`, `status=REJECTED`, `requestId` présent | unitaire + MockMvc | `ValidationServiceTest`, `DocumentRequestControllerTest` |
| S05 | `dateNaissance` = demain (UTC, `Clock` fixé) | E1 | `400`, `ERR_DATE_FUTUR`, `status=REJECTED` | unitaire `Clock` + MockMvc | `ValidationServiceTest`, `DocumentRequestControllerTest` |
| S06 | 6 clés obligatoires dont `nom=""` (variante `nom="   "`) | E1 | `422`, `missingFields=[nom]` (vide après trim ⇒ **absent**), `status=MISSING_INFORMATION` | unitaire N9 + MockMvc | `NormalizationServiceTest`, `DocumentRequestControllerTest` |
| S07 | Payload complet, `nom="Gomes@123"` | E1 | `400`, `fieldErrors=[{nom, ERR_FORMAT_TEXTE_INVALIDE}]`, `status=REJECTED` | unitaire + MockMvc | `ValidationServiceTest`, `DocumentRequestControllerTest` |
| S08 | `nomIncorrect="Maria Gomez"`, `nomCorrect="maria gomez "` (identiques après normalisation) | E1 | `400`, `ERR_NOM_CONCORDANCE_IDENTIQUE`, `status=REJECTED`, aucune relecture « intelligente » | unitaire + MockMvc | `ValidationServiceTest`, `DocumentRequestControllerTest` |
| S09 | Extraction IA `confidence=0.61`, `data.nomCorrect` absent, déclaré dans `missingFields` | E8 puis E1 | E8 → `200 valid=true` ; E1 → `422`, `missingFields=[nomCorrect]`, `status=MISSING_INFORMATION` ; `confidence` ne fabrique jamais de valeur | schema + MockMvc | `ExtractionSchemaValidationTest`, `DocumentRequestControllerTest` |
| S10 | `documentType="ACTE_NAISSANCE"` | E1 | `400`, `ERR_DOCUMENT_TYPE_NON_SUPPORTE`, **non persisté** (aucun `requestId`) | MockMvc | `DocumentRequestControllerTest` |
| S11 | Corps HTTP non JSON / tronqué | E1 | `400`, `ERR_PAYLOAD_INVALIDE`, non persisté, aucune stack trace | MockMvc | `DocumentRequestControllerTest` |
| S12 | Payload complet + `data.passeport="X123"` | E1 | `400`, `ERR_CHAMP_INCONNU`, `status=REJECTED` (persisté + audité), aucune donnée hors schéma stockée | unitaire + MockMvc | `ValidationServiceTest`, `DocumentRequestControllerTest` |
| S13 | `status=MISSING_INFORMATION` | E5 `POST .../generate` | `409`, `code=INVALID_STATUS`, message citant statut actuel et `VALIDATED` attendu, aucune génération | transitions + MockMvc | `RequestStatusTransitionTest`, `DocumentRequestControllerTest` |
| S14 | `status=VALIDATED`, template DOCX présent | E5 puis E6 | E5 → `201`, `documentId`, `status=GENERATED` ; E6 → `200` binaire DOCX, `Content-Disposition: attachment` | unitaire génération + MockMvc | `DocumentGenerationServiceTest`, `DocumentRequestControllerTest` |
| S15 | `status=VALIDATED`, template introuvable/checksum mismatch sur disque | E5 | `500`, `code=TEMPLATE_NOT_FOUND`, `status=FAILED` ; reprise E4 → `200 VALIDATED` puis E5 → `201` | unitaire + MockMvc | `DocumentGenerationServiceTest`, `DocumentRequestControllerTest` |
| S16 | `requestId` inexistant | E2 `GET /requests/{id}` | `404`, `code=REQUEST_NOT_FOUND`, aucun détail technique fuité | MockMvc | `DocumentRequestControllerTest` |

Couvertures complémentaires (AGENTS.md §16) : JSON malformé d'extraction
(fixture), type inconnu, template absent, erreur base (mock port qui lève
`DataAccessException` → `500 DATABASE_ERROR`), transitions interdites
(matrice T1–T14), lecture de fichier inexistante (download → `404`).

### 11.4 Commande de test

```
mvn -f backend/pom.xml test
```

(Windows : `mvn -f backend\pom.xml test`, `JAVA_HOME` = JDK 17 — tests
unitaires + MockMvc, **aucun Docker requis**.)

Vérifications complémentaires (hors `mvn test`, post-implémentation) :

```
docker compose -f docker/docker-compose.yml --env-file docker/.env up -d --build
curl http://localhost:8080/api/v1/health
```

---

## 12. Sécurité itération 1

| Mesure | Détail implémentable |
|---|---|
| Aucun secret commité | credentials uniquement en variables d'env / `docker/.env` (gitignoré) ; `docker/.env.example` sans valeurs réelles ; export n8n sans `"credentials"` (AGENTS.md §11) |
| Pas de stack trace client | `GlobalExceptionHandler` renvoie toujours `ErrorResponse` structuré (code, message FR, `correlationId`) ; logs serveur uniquement ; `server.error.include-stacktrace=never`, `include-message=never` |
| Validation systématique | Bean Validation sur **tous** les DTO d'entrée + `ValidationService` déterministe ; aucune route sans validation d'entrée |
| Taille max payload | `PayloadSizeLimitFilter` : `app.document.max-payload-bytes` (défaut **65536** octets) → `413` avant désérialisation |
| Contrôle d'extension fichier | lecture/écriture limitées à `.docx` ; noms de fichiers **uniquement** générés côté serveur (UUID) ; canonicalisation de chemin contre path traversal |
| Minimisation PII | `audit_log.details` sans données personnelles (uniquement `status`, `errorCode`, `nbMissingFields`, `durationMs`, `documentType`) ; pas de `data` complet dans les logs applicatifs (AGENTS.md §13, OQ-API-4 en escalade) |
| Réseau Docker | réseau bridge dédié `adgendoc-net` ; PostgreSQL exposé sur `localhost` uniquement ; aucune auth HTTP en it.1 → déploiement périmètre restreint (localhost/réseau interne) |
| Accès documents | E6 ouvert en it.1 (**risque assumé**, contrat §1.3) ; fermeture obligatoire avant prod → escalade security en fin d'itération 1 |
| JWT | **reporté it.2** : `Authorization: Bearer` exigé sur tous les endpoints sauf E7 ; chemins déjà conçus sans contexte d'identité implicite |
| Intégrité template | checksum SHA-256 vérifié à chaque génération ; mismatch ⇒ `TEMPLATE_NOT_FOUND`, aucun document produit |

---

## 13. Plan d'implémentation ordonné (TOUS les fichiers à créer)

Ordre imposé : chaque phase dépend des précédentes.

**Phase A — base de données (`database-engineer`)**

1. `database/migrations/V1__init.sql` (DDL §3.1 exact)
2. `database/migrations/V2__seed_template.sql` (§3.2 — placeholder checksum,
   remplacé en Phase G)

**Phase B — fondations backend (`backend-developer`)**

3. `backend/pom.xml` (Java 17, Spring Boot 3.3.x, deps ADR-02/05/09/10,
   ressources `../prompts/extraction`, plugin compiler `release=17`)
4. `backend/src/main/resources/application.yml` (datasource env, Flyway
   `filesystem:${MIGRATIONS_DIR:../database/migrations}`, `ddl-auto: validate`,
   `open-in-view: false`, `server.error.include-stacktrace=never`,
   `app.document.*`, `app.extraction.schema-path`, actuator health)
5. `backend/src/main/java/com/adgendoc/DocumentGeneratorApplication.java`

**Phase C — domain (`backend-developer`)**

6. `.../domain/RequestStatus.java`
7. `.../domain/ErrorCode.java`
8. `.../domain/DocumentRequest.java`
9. `.../domain/Template.java`
10. `.../domain/GeneratedDocument.java`
11. `.../domain/ports/RequestRepository.java`
12. `.../domain/ports/GeneratedDocumentRepository.java`
13. `.../domain/ports/TemplateRepository.java`
14. `.../domain/ports/TemplateEngine.java`
15. `.../domain/ports/DocumentStorage.java`
16. `.../domain/ports/AuditPort.java`
17. `.../domain/exceptions/RequestNotFoundException.java`
18. `.../domain/exceptions/DocumentNotFoundException.java`
19. `.../domain/exceptions/InvalidStatusException.java`
20. `.../domain/exceptions/RequestAlreadyClosedException.java`
21. `.../domain/exceptions/TemplateNotFoundException.java`
22. `.../domain/exceptions/DocumentGenerationException.java`

(`...` = `backend/src/main/java/com/adgendoc`)

**Phase D — application (`backend-developer`)**

23. `.../application/ValidationOutcome.java`
24. `.../application/NormalizationService.java`
25. `.../application/ValidationService.java`
26. `.../application/RequestService.java`
27. `.../application/DocumentGenerationService.java`
28. `.../application/ExtractionValidationService.java`

**Phase E — api (`backend-developer`)**

29. `.../api/dto/CreateRequestRequest.java`
30. `.../api/dto/PatchRequestRequest.java`
31. `.../api/dto/RequestStatusResponse.java`
32. `.../api/dto/ErrorResponse.java`
33. `.../api/dto/GenerateDocumentResponse.java`
34. `.../api/dto/ExtractionValidationResponse.java`
35. `.../api/DocumentRequestController.java` (E1–E6)
36. `.../api/ExtractionController.java` (E8)
37. `.../api/HealthController.java` (E7)
38. `.../api/GlobalExceptionHandler.java`

**Phase F — infrastructure (`backend-developer`)**

39. `.../infrastructure/persistence/DocumentRequestEntity.java`
40. `.../infrastructure/persistence/DocumentRequestJpaRepository.java`
41. `.../infrastructure/persistence/DocumentRequestMapper.java`
42. `.../infrastructure/persistence/RequestRepositoryAdapter.java`
43. `.../infrastructure/persistence/GeneratedDocumentEntity.java`
44. `.../infrastructure/persistence/GeneratedDocumentJpaRepository.java`
45. `.../infrastructure/persistence/GeneratedDocumentMapper.java`
46. `.../infrastructure/persistence/GeneratedDocumentRepositoryAdapter.java`
47. `.../infrastructure/persistence/TemplateRegistryEntity.java`
48. `.../infrastructure/persistence/TemplateRegistryJpaRepository.java`
49. `.../infrastructure/persistence/TemplateRepositoryAdapter.java`
50. `.../infrastructure/persistence/AuditLogEntity.java`
51. `.../infrastructure/persistence/AuditLogJpaRepository.java`
52. `.../infrastructure/persistence/AuditAdapter.java`
53. `.../infrastructure/docx/PoiTemplateEngine.java`
54. `.../infrastructure/storage/FileSystemStorageAdapter.java`
55. `.../infrastructure/config/AppProperties.java`
56. `.../infrastructure/config/AppConfig.java`
57. `.../infrastructure/config/CorrelationIdFilter.java`
58. `.../infrastructure/config/PayloadSizeLimitFilter.java`

**Phase G — template (`business-analyst` + humain, OQ-1)**

59. `templates/attestation_concordance_v1.docx` (placeholder technique NON
    APPROUVÉ contenant les 14 variables §6.1 ; wording officiel en attente
    d'OQ-1)
60. mise à jour du checksum SHA-256 dans
    `database/migrations/V2__seed_template.sql` (fichier 2)

**Phase H — prompts (`ai-engineer`)**

61. `prompts/extraction/attestation_concordance.schema.json` (§8 exact)
62. `prompts/extraction/system_prompt_attestation_concordance.md` (§9 exact)

**Phase I — n8n (`n8n-developer`)**

63. `n8n/workflows/document-generation-v1.json` (§7, sans credentials)

**Phase J — docker**

64. `backend/Dockerfile` (contexte racine, multi-étapes §10.1)
65. `docker/docker-compose.yml` (§10.1)
66. `docker/.env.example` (§10.2)

**Phase K — tests (`tester`, avec `backend-developer`)**

67. `tests/fixtures/extraction_valid_complete.json`
68. `tests/fixtures/extraction_missing_nom_correct.json`
69. `tests/fixtures/extraction_missing_undeclared.json`
70. `tests/fixtures/extraction_invalid_date.json`
71. `tests/fixtures/extraction_unknown_key.json`
72. `tests/fixtures/extraction_unsupported_document_type.json`
73. `tests/fixtures/extraction_confidence_out_of_range.json`
74. `tests/fixtures/extraction_malformed.json`
75. `backend/src/test/java/com/adgendoc/application/NormalizationServiceTest.java`
76. `backend/src/test/java/com/adgendoc/application/ValidationServiceTest.java`
77. `backend/src/test/java/com/adgendoc/domain/RequestStatusTransitionTest.java`
78. `backend/src/test/java/com/adgendoc/application/ExtractionSchemaValidationTest.java`
79. `backend/src/test/java/com/adgendoc/api/DocumentRequestControllerTest.java`
80. `backend/src/test/java/com/adgendoc/application/DocumentGenerationServiceTest.java`
81. `backend/src/test/java/com/adgendoc/api/ExtractionControllerTest.java`

**Phase L — finalisation**

82. `.gitignore` racine : `target/`, `storage/`, `docker/.env`, `*.log`
83. vérification : `mvn -f backend/pom.xml test` (sans Docker) → PUIS
    démarrage Docker (migrations Flyway + healthchecks) → lifecycle
    AGENTS.md §7 (tester → reviewer → security)

---

## 14. Risques et points ouverts

| ID | Risque / point ouvert | Impact | Atténuation / statut |
|---|---|---|---|
| **OQ-1** | **Wording officiel de l'attestation inconnu** (libellé, autorité émettrice, mentions obligatoires) — `REQUIRES_BUSINESS_VALIDATION` | Template non approuvé ; bloquant avant toute mise en production, **pas** avant le dev des fondations | Template placeholder technique marqué NON APPROUVÉ (fichier 59) ; escalade humaine obligatoire avant revue finale (AGENTS.md §18) |
| R-01 | DDL `JSONB`/`TEXT[]` non mappés correctement → échec `ddl-auto: validate` | Démarrage backend | `@JdbcTypeCode(SqlTypes.JSON/ARRAY)` imposés (§3.1) ; vérif Docker obligatoire Phase L |
| R-02 | Aucun test de migration dans `mvn test` (pas de Docker, ADR-09) | DDL cassé non détecté par CI unitaire | Vérification Docker Phase L ; Testcontainers/`@SpringBootTest` reportés it.2 |
| R-03 | POI : tokens `{{...}}` scindés sur plusieurs `XWPFRun` → remplacement partiel | DOCX avec placeholders résiduels | Algorithme §6.2.3 (reconstruction paragraphe) ; `DocumentGenerationServiceTest` + test de non-résidu sur le template généré |
| R-04 | Checksum `V2` placeholder 60×`0` si oubli de mise à jour | Toute génération → `TEMPLATE_NOT_FOUND` | Étape explicite Phase G (fichier 60) ; vérification du checksum à chaque génération |
| R-05 | Chemin Flyway relatif au working directory (`../database/migrations`) | Flyway ne trouve pas les migrations selon le lancement | `MIGRATIONS_DIR` surchargeable ; Docker = `/app/migrations` (§10.1) ; noté dans `application.yml` |
| R-06 | Divergence `API_CONTRACTS` §2.4 (`dd/MM/yyyy` toléré en extraction) vs schéma ISO §8 (instruction d'architecture) | Rejet E8 d'une extraction en format FR | Conversion défensive n8n (nœud 4, §7.1) + E1 tolère toujours `dd/MM/yyyy` (S03) ; à confirmer à la revue |
| R-07 | OQ-4 : liste des particules de normalisation (N6) non vérifiée pour toutes les origines de noms | Capitalisation incorrecte de noms | Choix technique documenté, `REQUIRES_BUSINESS_VALIDATION` — pas de blocage dev, revue métier avant prod |
| R-08 | OQ-5 : seuil de `confidence` indéfini | Extraction peu fiable acceptée | `confidence` = audit seulement en it.1 ; décision n8n/ai-engineer reportée |
| R-09 | OQ-API-4 : `data` complet renvoyé par E2 (PII) | Minimisation des données | Escalade security tracée ; it.1 conformément au contrat, décision avant prod |
| R-10 | Pas d'auth en it.1 : téléchargement E6 ouvert | Accès documents non autorisé (AGENTS.md §13) | Risque assumé documenté (§12) ; fermeture obligatoire en it.2, escalade security fin it.1 |
| R-11 | OQ-API-2 : génération synchrone (`201`) | Templates lourds / timeout futur | Décision ADR-12 ; `202`+polling réservé it.2 |
| R-12 | OQ-8 : format d'affichage `date_naissance` et `reference_demande` dans le DOCX | Variables de template incertaines | Mapping §6.1 provisoire, `REQUIRES_BUSINESS_VALIDATION` avec OQ-1 |
| R-13 | Lecture du prompt n8n via `fs` (sandbox `NODE_FUNCTION_ALLOW_BUILTIN=fs`) | Workflow défaillant si env absente | Variable posée dans compose (§10.1) ; fallback = erreur explicite `AI_EXTRACTION_ERROR` |
| R-14 | OQ-API-5 (rate limit) et OQ-API-6 (politique de versionnement `v1`) non définis | Prod exposée | Hors périmètre it.1, listés avant mise en production |

**Escalade humaine déclenchée (AGENTS.md §18)** : OQ-1 (wording ambigu,
règle légale invérifiable) doit être résolue par un responsable
administratif avant validation du template et avant la revue finale de
l'itération 1.

