package com.adgendoc.domain;

public enum ErrorCode {

    VALIDATION_ERROR(400, "Données invalides : correction nécessaire."),
    MISSING_INFORMATION(422, "Information manquante pour générer le document."),
    INVALID_STATUS(409, "Opération impossible pour le statut actuel de la demande."),
    REQUEST_ALREADY_CLOSED(409, "Demande close : aucune modification possible."),
    REQUEST_NOT_FOUND(404, "Demande introuvable."),
    DOCUMENT_NOT_FOUND(404, "Document introuvable."),
    TEMPLATE_NOT_FOUND(500, "Template officiel introuvable ou intégrité altérée."),
    DOCUMENT_GENERATION_ERROR(500, "Échec de génération du document."),
    DATABASE_ERROR(500, "Erreur interne de persistance."),
    UNAUTHORIZED(401, "Authentification requise."),
    FORBIDDEN(403, "Accès refusé."),
    INTERNAL_ERROR(500, "Une erreur interne est survenue."),
    AI_EXTRACTION_ERROR(500, "Échec de l'extraction structurée."),
    EXTRACTION_SCHEMA_INVALID(400, "JSON d'extraction non conforme au schéma."),

    ERR_CHAMP_OBLIGATOIRE_ABSENT(422,
            "Information manquante : le champ « {field} » est obligatoire pour générer l'attestation."),
    ERR_DOCUMENT_TYPE_NON_SUPPORTE(400,
            "Type de document non pris en charge pour cette itération. Seul « ATTESTATION_CONCORDANCE » est supporté."),
    ERR_PAYLOAD_INVALIDE(400, "Corps de requête invalide ou JSON malformé."),
    ERR_CHAMP_INCONNU(400,
            "Champ inconnu ou non autorisé pour ce type de document : « {field} »."),
    ERR_CHAMP_RESERVE(400, "Le champ « referenceDemande » est réservé au système."),
    ERR_FORMAT_TEXTE_INVALIDE(400, "Le champ « {field} » contient des caractères non autorisés."),
    ERR_LONGUEUR_DEPASSEE(400,
            "Le champ « {field} » dépasse la longueur maximale autorisée."),
    ERR_DATE_FORMAT_INVALIDE(400,
            "Format de date invalide. Formats acceptés : yyyy-MM-dd ou dd/MM/yyyy."),
    ERR_DATE_CALENDRIER_INVALIDE(400,
            "La date de naissance n'est pas une date de calendrier valide."),
    ERR_DATE_FUTUR(400,
            "La date de naissance ne peut pas être postérieure à la date du jour."),
    ERR_ENUM_INVALIDE(400, "Valeur invalide pour « {field} »."),
    ERR_EMAIL_INVALIDE(400,
            "Le champ « contactEmail » n'est pas une adresse e-mail valide."),
    ERR_TELEPHONE_INVALIDE(400,
            "Le champ « contactTelephone » n'est pas un numéro de téléphone valide."),
    ERR_NOM_CONCORDANCE_IDENTIQUE(400,
            "Les formes « nomIncorrect » et « nomCorrect » doivent différer : rien à attester.");

    private final int httpStatus;
    private final String message;

    ErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String getCode() {
        return name();
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }

    public String getMessage(String field) {
        return message.replace("{field}", field);
    }
}
