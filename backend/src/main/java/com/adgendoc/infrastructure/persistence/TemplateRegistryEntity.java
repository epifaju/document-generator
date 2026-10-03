package com.adgendoc.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Entité JPA de la table {@code template_registry} (V1__init.sql) — clé
 * primaire composite {@code (code, version)} via {@link TemplateKey}. Lecture
 * seule côté applicatif : le seed est porté par {@code V2__seed_template.sql}.
 */
@Entity
@Table(name = "template_registry")
@IdClass(TemplateRegistryEntity.TemplateKey.class)
public class TemplateRegistryEntity {

    @Id
    @Column(name = "code", length = 100)
    private String code;

    @Id
    @Column(name = "version", length = 20)
    private String version;

    @Column(name = "file_path", length = 1024, nullable = false)
    private String filePath;

    /** V1 : {@code CHAR(64)} (pas {@code VARCHAR}) — validation Hibernate. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum", length = 64, nullable = false)
    private String checksum;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Clé primaire composite de {@code template_registry}. */
    public static class TemplateKey implements Serializable {

        private static final long serialVersionUID = 1L;

        private String code;
        private String version;

        public TemplateKey() {
        }

        public TemplateKey(String code, String version) {
            this.code = code;
            this.version = version;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TemplateKey key)) {
                return false;
            }
            return Objects.equals(code, key.code) && Objects.equals(version, key.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, version);
        }
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
