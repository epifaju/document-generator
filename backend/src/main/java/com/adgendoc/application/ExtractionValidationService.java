package com.adgendoc.application;

import com.adgendoc.domain.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validation de forme d'un {@code ExtractionResult} contre
 * {@code attestation_concordance.schema.json} (draft 2020-12) — E8
 * (architecture §4.1/§8). Contrôle de forme uniquement : les règles métier
 * restent l'exclusive de {@code POST /api/v1/requests}.
 *
 * <p>Seuls {@code type}, chemin d'instance et nom de propriété des messages
 * du validateur sont exploités : les codes et messages renvoyés proviennent
 * de {@link ErrorCode} (déterministes, en français, indépendants de la
 * locale JVM).</p>
 */
public class ExtractionValidationService {

    public record SchemaViolation(String path, ErrorCode code, String message) {

        public SchemaViolation {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    private static final String CLASSPATH_PREFIX = "classpath:";

    private final JsonSchema schema;
    private final ObjectMapper objectMapper;

    public ExtractionValidationService(String schemaLocation) {
        this(schemaLocation, new ObjectMapper());
    }

    public ExtractionValidationService(String schemaLocation, ObjectMapper objectMapper) {
        Objects.requireNonNull(schemaLocation, "schemaLocation");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(loadSchema(schemaLocation));
    }

    public List<SchemaViolation> validate(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of(payloadInvalid());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (JsonProcessingException exception) {
            return List.of(payloadInvalid());
        }
        if (root == null || !root.isObject()) {
            return List.of(payloadInvalid());
        }

        JsonNode documentType = root.get("documentType");
        if (documentType != null && documentType.isTextual()
                && !ValidationService.SUPPORTED_DOCUMENT_TYPE.equals(documentType.textValue())) {
            return List.of(unsupportedDocumentType());
        }

        Set<ValidationMessage> messages;
        try {
            messages = schema.validate(root);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "\u00c9chec de validation du sch\u00e9ma d'extraction.", exception);
        }

        Set<SchemaViolation> violations = new LinkedHashSet<>();
        for (ValidationMessage message : messages) {
            violations.add(toViolation(message));
        }
        return List.copyOf(violations);
    }

    private SchemaViolation toViolation(ValidationMessage message) {
        String keyword = message.getType();
        String pointer = toJsonPointer(message.getInstanceLocation().toString());
        String property = message.getProperty();

        if ("required".equals(keyword)) {
            String field = property != null && !property.isBlank()
                    ? property
                    : lastSegment(pointer);
            String path = childPath(pointer, field);
            return new SchemaViolation(path, ErrorCode.ERR_CHAMP_OBLIGATOIRE_ABSENT,
                    ErrorCode.ERR_CHAMP_OBLIGATOIRE_ABSENT.getMessage(field));
        }
        if ("additionalProperties".equals(keyword)) {
            String field = property != null && !property.isBlank()
                    ? property
                    : lastSegment(pointer);
            String path = childPath(pointer, field);
            return new SchemaViolation(path, ErrorCode.ERR_CHAMP_INCONNU,
                    ErrorCode.ERR_CHAMP_INCONNU.getMessage(field));
        }
        if ("const".equals(keyword)) {
            if ("/documentType".equals(pointer)) {
                return unsupportedDocumentType();
            }
            return payloadInvalid(pointer, lastSegment(pointer));
        }
        if ("pattern".equals(keyword)) {
            String field = lastSegment(pointer);
            ErrorCode code = "dateNaissance".equals(field)
                    ? ErrorCode.ERR_DATE_FORMAT_INVALIDE
                    : ErrorCode.ERR_FORMAT_TEXTE_INVALIDE;
            return new SchemaViolation(pointer, code, code.getMessage(field));
        }
        if ("enum".equals(keyword)) {
            String field = lastSegment(pointer);
            ErrorCode code = ErrorCode.ERR_ENUM_INVALIDE;
            String accepted = acceptedValues(message.getArguments());
            String base = code.getMessage(field);
            return new SchemaViolation(pointer, code, accepted.isEmpty()
                    ? base
                    : base + " Valeurs accept\u00e9es : " + accepted + ".");
        }
        if ("if".equals(keyword) || "allOf".equals(keyword) || "then".equals(keyword)
                || "not".equals(keyword)) {
            String field = lastSegment(pointer);
            return new SchemaViolation(pointer, ErrorCode.ERR_CHAMP_OBLIGATOIRE_ABSENT,
                    ErrorCode.ERR_CHAMP_OBLIGATOIRE_ABSENT.getMessage(field));
        }
        if ("minLength".equals(keyword) || "maxLength".equals(keyword)) {
            String field = lastSegment(pointer);
            return new SchemaViolation(pointer, ErrorCode.ERR_LONGUEUR_DEPASSEE,
                    ErrorCode.ERR_LONGUEUR_DEPASSEE.getMessage(field));
        }
        return payloadInvalid(pointer, lastSegment(pointer));
    }

    private SchemaViolation unsupportedDocumentType() {
        ErrorCode code = ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE;
        return new SchemaViolation("/documentType", code, code.getMessage("documentType"));
    }

    private SchemaViolation payloadInvalid() {
        return payloadInvalid("/", "payload");
    }

    private SchemaViolation payloadInvalid(String path) {
        return payloadInvalid(path, "payload");
    }

    private SchemaViolation payloadInvalid(String path, String field) {
        ErrorCode code = ErrorCode.ERR_PAYLOAD_INVALIDE;
        return new SchemaViolation(path, code,
                code.getMessage() + " Cl\u00e9 : \u00ab " + field + " \u00bb.");
    }

    private JsonNode loadSchema(String schemaLocation) {
        String path = schemaLocation.startsWith(CLASSPATH_PREFIX)
                ? schemaLocation.substring(CLASSPATH_PREFIX.length())
                : schemaLocation;
        ClassLoader classLoader = ExtractionValidationService.class.getClassLoader();
        try (InputStream stream = classLoader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Sch\u00e9ma d'extraction introuvable dans le classpath : " + path);
            }
            return objectMapper.readTree(stream);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Lecture du sch\u00e9ma d'extraction impossible : " + path, exception);
        }
    }

    /**
     * Convertit le chemin legacy du validateur ({@code $.data.dateNaissance},
     * {@code $.missingFields[0]}, {@code $}) en JSON Pointer.
     */
    private String toJsonPointer(String legacyPath) {
        if (legacyPath == null || legacyPath.isEmpty() || "$".equals(legacyPath)) {
            return "/";
        }
        String path = legacyPath;
        if (path.startsWith("$.")) {
            path = path.substring(2);
        } else if (path.startsWith("$")) {
            path = path.substring(1);
        }
        path = path.replace('.', '/');
        path = path.replaceAll("\\[(\\d+)]", "/$1");
        return path.startsWith("/") ? path : "/" + path;
    }

    private String childPath(String parentPointer, String child) {
        String prefix = "/".equals(parentPointer) ? "" : parentPointer;
        return prefix + "/" + child;
    }

    private String lastSegment(String pointer) {
        int index = pointer.lastIndexOf('/');
        String segment = index >= 0 ? pointer.substring(index + 1) : pointer;
        if (!segment.isEmpty() && segment.chars().allMatch(Character::isDigit)) {
            int parentIndex = pointer.lastIndexOf('/', index - 1);
            return parentIndex >= 0 ? pointer.substring(parentIndex + 1, index) : "payload";
        }
        return segment.isEmpty() ? "payload" : segment;
    }

    private String acceptedValues(Object[] arguments) {
        if (arguments == null || arguments.length == 0 || !(arguments[0] instanceof Collection<?> values)) {
            return "";
        }
        List<String> rendered = new ArrayList<>();
        for (Object value : values) {
            rendered.add(String.valueOf(value));
        }
        return String.join(", ", rendered);
    }
}
