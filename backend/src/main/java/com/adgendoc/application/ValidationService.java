package com.adgendoc.application;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.application.ValidationOutcome.FieldError;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class ValidationService {

    public static final String SUPPORTED_DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";

    private static final List<String> REQUIRED_FIELDS = List.of(
            "prenom", "nom", "dateNaissance", "lieuNaissance", "nomIncorrect", "nomCorrect");

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "prenom", "nom", "dateNaissance", "lieuNaissance", "nomIncorrect", "nomCorrect",
            "sexe", "nationalite", "documentSourceReference", "langueDocument", "demandeur",
            "contactEmail", "contactTelephone", "motif");

    private static final String RESERVED_FIELD = "referenceDemande";

    private static final List<String> SEX_VALUES = List.of("M", "F");
    private static final List<String> LANGUE_VALUES = List.of("FR", "PT", "EN");

    private static final Pattern NAME_100 =
            Pattern.compile("^[A-Za-zÀ-ÖØ-öø-ÿ' -]{1,100}$");
    private static final Pattern NAME_200 =
            Pattern.compile("^[A-Za-zÀ-ÖØ-öø-ÿ' -]{1,200}$");
    private static final Pattern LIEU_NAISSANCE =
            Pattern.compile("^[A-Za-zÀ-ÖØ-öø-ÿ'’\\-,\\.() ]{1,200}$");
    private static final Pattern NATIONALITE =
            Pattern.compile("^[\\p{L}][\\p{L}\\s'\\-\\.]{0,99}$");
    private static final Pattern DOCUMENT_SOURCE_REFERENCE =
            Pattern.compile("^[A-Za-z0-9À-ÖØ-öø-ÿ'’\\-,\\.\\/ ]{1,100}$");
    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern TELEPHONE =
            Pattern.compile("^\\+?[0-9][0-9\\s\\-.]{5,19}$");
    private static final Pattern ISO_DATE =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private static final DateTimeFormatter STRICT_ISO_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private static final int MAX_PRENOM = 100;
    private static final int MAX_NOM = 100;
    private static final int MAX_LIEU_NAISSANCE = 200;
    private static final int MAX_NOM_LONG = 200;
    private static final int MAX_NATIONALITE = 100;
    private static final int MAX_DOCUMENT_SOURCE = 100;
    private static final int MAX_DEMANDEUR = 200;
    private static final int MAX_EMAIL = 254;
    private static final int MAX_TELEPHONE = 20;
    private static final int MAX_MOTIF = 500;

    private final Clock clock;

    public ValidationService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ValidationOutcome validate(String documentType, Map<String, Object> data) {
        Map<String, Object> safeData = data == null ? Map.of() : data;

        ValidationOutcome envelope = checkDocumentType(documentType);
        if (!envelope.persistable()) {
            return envelope;
        }

        List<FieldError> keyErrors = checkKeys(safeData);
        if (!keyErrors.isEmpty()) {
            return ValidationOutcome.rejected(keyErrors);
        }

        List<String> missing = missingRequiredFields(safeData);
        if (!missing.isEmpty()) {
            return ValidationOutcome.missing(missing);
        }

        FieldError formatError = checkFormats(safeData);
        if (formatError != null) {
            return ValidationOutcome.rejected(List.of(formatError));
        }

        if (sameConcordance(safeData.get("nomIncorrect"), safeData.get("nomCorrect"))) {
            return ValidationOutcome.rejected(List.of(
                    fieldError("nomIncorrect", ErrorCode.ERR_NOM_CONCORDANCE_IDENTIQUE)));
        }

        return ValidationOutcome.validated();
    }

    public ValidationOutcome checkDocumentType(String documentType) {
        if (!SUPPORTED_DOCUMENT_TYPE.equals(documentType)) {
            return ValidationOutcome.rejectedNotPersistable(List.of(
                    fieldError("documentType", ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE)));
        }
        return ValidationOutcome.validated();
    }

    public Map<String, Object> sanitizeExtraction(Map<String, Object> extraction) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (extraction == null || extraction.isEmpty()) {
            return sanitized;
        }
        List<FieldError> errors = new ArrayList<>();
        for (Map.Entry<String, Object> entry : extraction.entrySet()) {
            FieldError error = checkExtractionEntry(entry.getKey(), entry.getValue());
            if (error == null) {
                sanitized.put(entry.getKey(), entry.getValue());
            } else {
                errors.add(error);
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationRejectedException(errors);
        }
        return sanitized;
    }

    public List<FieldError> checkKeys(Map<String, Object> data) {
        List<FieldError> errors = new ArrayList<>();
        if (data == null) {
            return errors;
        }
        for (String key : data.keySet()) {
            if (RESERVED_FIELD.equals(key)) {
                errors.add(fieldError(key, ErrorCode.ERR_CHAMP_RESERVE));
            } else if (!KNOWN_FIELDS.contains(key)) {
                errors.add(fieldError(key, ErrorCode.ERR_CHAMP_INCONNU));
            }
        }
        return errors;
    }

    public Map<String, Object> knownFieldsOnly(Map<String, Object> data) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        if (data == null) {
            return filtered;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (KNOWN_FIELDS.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private List<String> missingRequiredFields(Map<String, Object> data) {
        List<String> missing = new ArrayList<>();
        for (String field : REQUIRED_FIELDS) {
            if (isAbsent(data.get(field))) {
                missing.add(field);
            }
        }
        return missing;
    }

    private boolean isAbsent(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private FieldError checkFormats(Map<String, Object> data) {
        FieldError error;
        error = checkText(data, "prenom", MAX_PRENOM, NAME_100);
        if (error != null) {
            return error;
        }
        error = checkText(data, "nom", MAX_NOM, NAME_100);
        if (error != null) {
            return error;
        }
        error = checkDateNaissance(data);
        if (error != null) {
            return error;
        }
        error = checkText(data, "lieuNaissance", MAX_LIEU_NAISSANCE, LIEU_NAISSANCE);
        if (error != null) {
            return error;
        }
        error = checkText(data, "nomIncorrect", MAX_NOM_LONG, NAME_200);
        if (error != null) {
            return error;
        }
        error = checkText(data, "nomCorrect", MAX_NOM_LONG, NAME_200);
        if (error != null) {
            return error;
        }
        error = checkEnum(data, "sexe", SEX_VALUES);
        if (error != null) {
            return error;
        }
        error = checkText(data, "nationalite", MAX_NATIONALITE, NATIONALITE);
        if (error != null) {
            return error;
        }
        error = checkText(data, "documentSourceReference", MAX_DOCUMENT_SOURCE,
                DOCUMENT_SOURCE_REFERENCE);
        if (error != null) {
            return error;
        }
        error = checkEnum(data, "langueDocument", LANGUE_VALUES);
        if (error != null) {
            return error;
        }
        error = checkText(data, "demandeur", MAX_DEMANDEUR, NAME_200);
        if (error != null) {
            return error;
        }
        error = checkContactEmail(data);
        if (error != null) {
            return error;
        }
        error = checkContactTelephone(data);
        if (error != null) {
            return error;
        }
        return checkMotif(data);
    }

    private FieldError checkText(Map<String, Object> data, String field, int maxLength,
                                 Pattern pattern) {
        Object value = data.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            return fieldError(field, ErrorCode.ERR_FORMAT_TEXTE_INVALIDE);
        }
        if (text.length() > maxLength) {
            return fieldError(field, ErrorCode.ERR_LONGUEUR_DEPASSEE);
        }
        if (!pattern.matcher(text).matches()) {
            return fieldError(field, ErrorCode.ERR_FORMAT_TEXTE_INVALIDE);
        }
        return null;
    }

    private FieldError checkDateNaissance(Map<String, Object> data) {
        Object value = data.get("dateNaissance");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || !ISO_DATE.matcher(text).matches()) {
            return fieldError("dateNaissance", ErrorCode.ERR_DATE_FORMAT_INVALIDE);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(text, STRICT_ISO_DATE);
        } catch (DateTimeParseException exception) {
            return fieldError("dateNaissance", ErrorCode.ERR_DATE_CALENDRIER_INVALIDE);
        }
        if (date.isAfter(LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC))) {
            return fieldError("dateNaissance", ErrorCode.ERR_DATE_FUTUR);
        }
        return null;
    }

    private FieldError checkEnum(Map<String, Object> data, String field, List<String> accepted) {
        Object value = data.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || !accepted.contains(text)) {
            return new FieldError(field, ErrorCode.ERR_ENUM_INVALIDE,
                    ErrorCode.ERR_ENUM_INVALIDE.getMessage(field)
                            + " Valeurs acceptées : " + String.join(", ", accepted) + ".");
        }
        return null;
    }

    private FieldError checkContactEmail(Map<String, Object> data) {
        Object value = data.get("contactEmail");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > MAX_EMAIL
                || !EMAIL.matcher(text).matches()) {
            return fieldError("contactEmail", ErrorCode.ERR_EMAIL_INVALIDE);
        }
        return null;
    }

    private FieldError checkContactTelephone(Map<String, Object> data) {
        Object value = data.get("contactTelephone");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > MAX_TELEPHONE
                || !TELEPHONE.matcher(text).matches()) {
            return fieldError("contactTelephone", ErrorCode.ERR_TELEPHONE_INVALIDE);
        }
        return null;
    }

    private FieldError checkMotif(Map<String, Object> data) {
        Object value = data.get("motif");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            return fieldError("motif", ErrorCode.ERR_FORMAT_TEXTE_INVALIDE);
        }
        if (text.length() > MAX_MOTIF) {
            return fieldError("motif", ErrorCode.ERR_LONGUEUR_DEPASSEE);
        }
        return null;
    }

    private boolean sameConcordance(Object nomIncorrect, Object nomCorrect) {
        if (!(nomIncorrect instanceof String incorrect) || !(nomCorrect instanceof String correct)) {
            return false;
        }
        return comparisonKey(incorrect).equals(comparisonKey(correct));
    }

    private String comparisonKey(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private FieldError checkExtractionEntry(String key, Object value) {
        if ("confidence".equals(key)) {
            if (!(value instanceof Number number)) {
                return extractionError(key);
            }
            double confidence = number.doubleValue();
            if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
                return extractionError(key);
            }
            return null;
        }
        if ("modelId".equals(key) || "promptVersion".equals(key)) {
            if (value instanceof String) {
                return null;
            }
            return extractionError(key);
        }
        return extractionError(key);
    }

    private FieldError extractionError(String key) {
        return new FieldError("extraction", ErrorCode.ERR_PAYLOAD_INVALIDE,
                ErrorCode.ERR_PAYLOAD_INVALIDE.getMessage() + " Clé : « " + key + " ».");
    }

    private FieldError fieldError(String field, ErrorCode code) {
        return new FieldError(field, code, code.getMessage(field));
    }
}
