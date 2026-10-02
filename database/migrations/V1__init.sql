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
