package com.adgendoc.api;

import com.adgendoc.api.dto.ErrorResponse;
import com.adgendoc.application.RequestService;
import com.adgendoc.application.UnsupportedDocumentTypeException;
import com.adgendoc.application.ValidationRejectedException;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.RequestStatus;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.exceptions.DocumentNotFoundException;
import com.adgendoc.domain.exceptions.InvalidStatusException;
import com.adgendoc.domain.exceptions.RequestAlreadyClosedException;
import com.adgendoc.domain.exceptions.RequestNotFoundException;
import com.adgendoc.domain.exceptions.TemplateNotFoundException;
import com.adgendoc.infrastructure.config.CorrelationIdFilter;
import com.adgendoc.infrastructure.config.PayloadSizeLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversion des exceptions en {@code ErrorResponse} (contrat API §2.3,
 * architecture §4.1) : message FR, {@code correlationId} systématique,
 * aucune stack trace ni détail technique exposée au client ; les détails
 * techniques restent journalisés côté serveur.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PAYLOAD_FIELD = "payload";

    private final RequestService requestService;

    public GlobalExceptionHandler(RequestService requestService) {
        this.requestService = requestService;
    }

    @ExceptionHandler(RequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(RequestNotFoundException exception,
                                                        HttpServletRequest http) {
        return respond(HttpStatus.NOT_FOUND, ErrorCode.REQUEST_NOT_FOUND,
                exception.getMessage(), http, null, null, List.of());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(
            DocumentNotFoundException exception, HttpServletRequest http) {
        return respond(HttpStatus.NOT_FOUND, ErrorCode.DOCUMENT_NOT_FOUND,
                exception.getMessage(), http, null, null, List.of());
    }

    @ExceptionHandler(InvalidStatusException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStatus(InvalidStatusException exception,
                                                             HttpServletRequest http) {
        return respond(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS, exception.getMessage(),
                http, exception.getRequestId(), exception.getCurrentStatus(), List.of());
    }

    @ExceptionHandler(RequestAlreadyClosedException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyClosed(
            RequestAlreadyClosedException exception, HttpServletRequest http) {
        return respond(HttpStatus.CONFLICT, ErrorCode.REQUEST_ALREADY_CLOSED,
                exception.getMessage(), http, exception.getRequestId(),
                exception.getCurrentStatus(), List.of());
    }

    @ExceptionHandler(ValidationRejectedException.class)
    public ResponseEntity<ErrorResponse> handleValidationRejected(
            ValidationRejectedException exception, HttpServletRequest http) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.getMessage(), http,
                requestIdFrom(http), statusOf(requestIdFrom(http)),
                RequestResponseMapper.toFieldErrors(exception.getFieldErrors()));
    }

    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedType(
            UnsupportedDocumentTypeException exception, HttpServletRequest http) {
        ErrorCode code = ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE;
        List<ErrorResponse.FieldError> fieldErrors = List.of(
                ErrorResponse.FieldError.of("documentType", code,
                        code.getMessage("documentType")));
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.getMessage(), http, null, null, fieldErrors);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(
            MethodArgumentNotValidException exception, HttpServletRequest http) {
        List<ErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
                .getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.getMessage(), http, null, null, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException exception,
                                                          HttpServletRequest http) {
        if (causedByPayloadTooLarge(exception)) {
            return respond(HttpStatus.REQUEST_ENTITY_TOO_LARGE, ErrorCode.VALIDATION_ERROR,
                    "Corps de requ\u00eate trop volumineux.", http, null, null, List.of());
        }
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.getMessage(), http, null, null,
                List.of(payloadFieldError()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest http) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.getMessage(), http, null, null,
                List.of(payloadFieldError()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest http) {
        ErrorCode code = ErrorCode.ERR_PAYLOAD_INVALIDE;
        String message = code.getMessage() + " Cl\u00e9 : \u00ab "
                + exception.getName() + " \u00bb.";
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.getMessage(), http, null, null,
                List.of(ErrorResponse.FieldError.of(exception.getName(), code, message)));
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTemplateNotFound(
            TemplateNotFoundException exception, HttpServletRequest http) {
        LOGGER.error("Template introuvable [template={}, correlationId={}]",
                exception.getTemplateCode(), correlationId(http));
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.TEMPLATE_NOT_FOUND,
                ErrorCode.TEMPLATE_NOT_FOUND.getMessage(), http,
                requestIdFrom(http), statusOf(requestIdFrom(http)), List.of());
    }

    @ExceptionHandler(DocumentGenerationException.class)
    public ResponseEntity<ErrorResponse> handleGenerationFailure(
            DocumentGenerationException exception, HttpServletRequest http) {
        // Phase H.2 S-1 : DocumentGenerationService enveloppe aussi les
        // DataAccessException (asGenerationFailure) — le message brut (SQL,
        // valeurs, chemins) ne doit jamais atteindre le niveau ERROR.
        logTechnicalFailureSafely("\u00c9chec de g\u00e9n\u00e9ration",
                ErrorCode.DOCUMENT_GENERATION_ERROR, exception, http);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.DOCUMENT_GENERATION_ERROR,
                ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage(), http,
                requestIdFrom(http), statusOf(requestIdFrom(http)), List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDatabase(DataAccessException exception,
                                                        HttpServletRequest http) {
        logTechnicalFailureSafely("Erreur de persistance", ErrorCode.DATABASE_ERROR,
                exception, http);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.DATABASE_ERROR,
                ErrorCode.DATABASE_ERROR.getMessage(), http, requestIdFrom(http),
                statusOf(requestIdFrom(http)), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception,
                                                          HttpServletRequest http) {
        logTechnicalFailureSafely("Erreur interne non g\u00e9r\u00e9e", ErrorCode.INTERNAL_ERROR,
                exception, http);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getMessage(), http, null, null, List.of());
    }

    /**
     * Journalisation sûre d'une erreur technique côté serveur (Phase H.2 S-1,
     * AGENTS.md §13 « ne pas journaliser de données personnelles » et §15).
     *
     * <p>Le message d'une exception — base de données, stockage ou génération —
     * peut contenir des instructions SQL, des valeurs liées, des lignes
     * retournées, des données personnelles, des chemins ou des credentials
     * JDBC : il n'est <b>jamais</b> journalisé, ni en argument ni en
     * {@code throwable}.</p>
     *
     * <p>Au niveau ERROR, seules des métadonnées sûres sont émises : résumé
     * fixe, code applicatif, classe de l'exception, SQLState (code de
     * {@link java.sql.SQLException}, forme validée) et {@code correlationId}
     * (normalisé par {@code CorrelationIdFilter}, motif
     * {@code ^[A-Za-z0-9-]{1,64}$}). La stack trace (donc le message brut)
     * n'est émise qu'au niveau DEBUG ; la politique de journalisation
     * packaged ({@code application.yml}) garde la racine à {@code INFO}, donc
     * elle est coupée en production — prouvée par
     * {@code DatabaseErrorLogSanitizationTest}.</p>
     */
    private void logTechnicalFailureSafely(String summary, ErrorCode errorCode,
                                           Throwable exception, HttpServletRequest http) {
        LOGGER.error("{} [errorCode={}, exceptionClass={}, sqlState={}, correlationId={}]",
                summary, errorCode.name(), exception.getClass().getName(),
                sqlStateOf(exception), correlationId(http));
        LOGGER.debug("{} \u2014 stack trace [correlationId={}]",
                summary, correlationId(http), exception);
    }

    /**
     * SQLState de la cause {@link java.sql.SQLException} la plus proche
     * (profondeur bornée à 10), sinon {@code n/a}. Seule la forme normalisée
     * SQLSTATE ({@code 1 à 5 alphanumériques}) est retenue : toute autre
     * valeur vendor non contrôlée est écartée pour préserver le format du
     * journal.
     */
    private String sqlStateOf(Throwable exception) {
        Throwable current = exception;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.matches("[0-9A-Za-z]{1,5}")) {
                    return sqlState;
                }
            }
            current = current.getCause();
            depth++;
        }
        return "n/a";
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus httpStatus, ErrorCode code,
                                                  String message, HttpServletRequest http,
                                                  UUID requestId, RequestStatus status,
                                                  List<ErrorResponse.FieldError> fieldErrors) {
        ErrorResponse body = new ErrorResponse(code.name(), message, correlationId(http),
                List.of(), requestId, status, fieldErrors, null);
        return ResponseEntity.status(httpStatus).body(body);
    }

    private ErrorResponse.FieldError toFieldError(FieldError fieldError) {
        ErrorCode code = ErrorCode.ERR_PAYLOAD_INVALIDE;
        String message = code.getMessage() + " Cl\u00e9 : \u00ab "
                + fieldError.getField() + " \u00bb.";
        return ErrorResponse.FieldError.of(fieldError.getField(), code, message);
    }

    private ErrorResponse.FieldError payloadFieldError() {
        ErrorCode code = ErrorCode.ERR_PAYLOAD_INVALIDE;
        return ErrorResponse.FieldError.of(PAYLOAD_FIELD, code, code.getMessage());
    }

    private String correlationId(HttpServletRequest http) {
        return CorrelationIdFilter.currentCorrelationId(http);
    }

    private UUID requestIdFrom(HttpServletRequest http) {
        Object attribute = http.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attribute instanceof Map<?, ?> variables) {
            Object value = variables.get("requestId");
            if (value instanceof String text) {
                try {
                    return UUID.fromString(text);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private RequestStatus statusOf(UUID requestId) {
        if (requestId == null) {
            return null;
        }
        try {
            return requestService.get(requestId).getStatus();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean causedByPayloadTooLarge(Throwable exception) {
        Throwable current = exception;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof PayloadSizeLimitFilter.PayloadTooLargeIOException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
