package com.adgendoc.api;

import com.adgendoc.api.dto.ErrorResponse;
import com.adgendoc.api.dto.ExtractionValidationResponse;
import com.adgendoc.application.ExtractionValidationService;
import com.adgendoc.infrastructure.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Transport HTTP de E8 {@code POST /api/v1/extraction/validate}
 * (contrat API §3.8) : contrôle de forme du corps brut contre le schéma
 * d'extraction, réponse {@code ExtractionValidationResponse} en 200/400.
 */
@RestController
@RequestMapping("/api/v1/extraction")
public class ExtractionController {

    private final ExtractionValidationService extractionValidationService;

    public ExtractionController(ExtractionValidationService extractionValidationService) {
        this.extractionValidationService = extractionValidationService;
    }

    @PostMapping("/validate")
    public ResponseEntity<ExtractionValidationResponse> validate(
            @RequestBody String rawBody, HttpServletRequest http) {
        List<ExtractionValidationService.SchemaViolation> violations =
                extractionValidationService.validate(rawBody);
        boolean valid = violations.isEmpty();
        List<ErrorResponse.SchemaError> errors = violations.stream()
                .map(violation -> ErrorResponse.SchemaError.of(
                        violation.path(), violation.code(), violation.message()))
                .toList();
        ExtractionValidationResponse body = new ExtractionValidationResponse(
                valid, CorrelationIdFilter.currentCorrelationId(http), errors);
        return valid ? ResponseEntity.ok(body) : ResponseEntity.badRequest().body(body);
    }
}
