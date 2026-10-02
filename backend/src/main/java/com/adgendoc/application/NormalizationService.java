package com.adgendoc.application;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NormalizationService {

    private static final Set<String> PARTICLES = Set.of(
            "de", "da", "do", "dos", "das", "du", "des", "la", "le", "van", "von",
            "bin", "el", "al", "di", "del", "della", "der", "den", "ter", "ten");

    private static final Set<String> CAPITALIZED_FIELDS = Set.of(
            "prenom", "nom", "nomIncorrect", "nomCorrect", "demandeur", "lieuNaissance");

    private static final Set<String> ENUM_FIELDS = Set.of("sexe", "langueDocument");

    private static final String DATE_FIELD = "dateNaissance";
    private static final String NATIONALITE_FIELD = "nationalite";
    private static final String LANGUE_FIELD = "langueDocument";
    private static final String DEFAULT_LANGUE = "FR";

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern SLASH_DATE = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
    private static final Pattern DASH_DATE = Pattern.compile("^(\\d{1,2})-(\\d{1,2})-(\\d{4})$");

    public Map<String, Object> normalize(Map<String, Object> data) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (!(value instanceof String text)) {
                    normalized.put(entry.getKey(), value);
                    continue;
                }
                String cleaned = clean(text);
                if (cleaned.isEmpty()) {
                    continue;
                }
                String result = applyFieldRules(entry.getKey(), cleaned);
                if (result.isEmpty()) {
                    continue;
                }
                normalized.put(entry.getKey(), result);
            }
        }
        if (!normalized.containsKey(LANGUE_FIELD)) {
            normalized.put(LANGUE_FIELD, DEFAULT_LANGUE);
        }
        return normalized;
    }

    private String clean(String text) {
        String result = text.trim().replaceAll("\\s+", " ");
        return result.replace('\u2019', '\'');
    }

    private String applyFieldRules(String field, String value) {
        if (DATE_FIELD.equals(field)) {
            return toIsoDate(value);
        }
        if (CAPITALIZED_FIELDS.contains(field)) {
            return capitalizeNames(value);
        }
        if (ENUM_FIELDS.contains(field)) {
            return value.toUpperCase(Locale.ROOT);
        }
        if (NATIONALITE_FIELD.equals(field)) {
            return capitalizeFirst(value);
        }
        return value;
    }

    private String toIsoDate(String value) {
        if (ISO_DATE.matcher(value).matches()) {
            return value;
        }
        Matcher matcher = SLASH_DATE.matcher(value);
        if (!matcher.matches()) {
            matcher = DASH_DATE.matcher(value);
        }
        if (!matcher.matches()) {
            return value;
        }
        int year = Integer.parseInt(matcher.group(3));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(1));
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }

    private String capitalizeNames(String value) {
        String[] words = value.split(" ");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            String word = words[i];
            if (word.isEmpty()) {
                continue;
            }
            if (i > 0 && PARTICLES.contains(word.toLowerCase(Locale.ROOT))) {
                builder.append(word.toLowerCase(Locale.ROOT));
            } else {
                builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return builder.toString();
    }

    private String capitalizeFirst(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
