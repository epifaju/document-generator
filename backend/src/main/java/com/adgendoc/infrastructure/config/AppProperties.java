package com.adgendoc.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés {@code app.*} (architecture §4.1) : chemins de stockage et de
 * templates, limite de payload, chemin du schéma d'extraction. Aucun secret
 * ici — credentials injectés par variables d'environnement (AGENTS.md §13).
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Document document = new Document();
    private final Extraction extraction = new Extraction();

    public Document getDocument() {
        return document;
    }

    public Extraction getExtraction() {
        return extraction;
    }

    public static class Document {

        /** Répertoire d'écriture des DOCX générés (non utilisé en phase F1). */
        private String storagePath = "./storage";

        /** Répertoire des templates DOCX (résolu en nom de fichier). */
        private String templateDir = "../templates";

        /** Taille maximale d'un corps de requête, en octets. */
        private long maxPayloadBytes = 65536;

        public String getStoragePath() {
            return storagePath;
        }

        public void setStoragePath(String storagePath) {
            this.storagePath = storagePath;
        }

        public String getTemplateDir() {
            return templateDir;
        }

        public void setTemplateDir(String templateDir) {
            this.templateDir = templateDir;
        }

        public long getMaxPayloadBytes() {
            return maxPayloadBytes;
        }

        public void setMaxPayloadBytes(long maxPayloadBytes) {
            this.maxPayloadBytes = maxPayloadBytes;
        }
    }

    public static class Extraction {

        /** Schéma JSON Schema du pilote (draft 2020-12). */
        private String schemaPath = "classpath:prompts/extraction/"
                + "attestation_concordance.schema.json";

        public String getSchemaPath() {
            return schemaPath;
        }

        public void setSchemaPath(String schemaPath) {
            this.schemaPath = schemaPath;
        }
    }
}
