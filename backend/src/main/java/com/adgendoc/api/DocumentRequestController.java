package com.adgendoc.api;

import com.adgendoc.api.dto.CreateRequestRequest;
import com.adgendoc.api.dto.ErrorResponse;
import com.adgendoc.api.dto.GenerateDocumentResponse;
import com.adgendoc.api.dto.PatchRequestRequest;
import com.adgendoc.api.dto.RequestStatusResponse;
import com.adgendoc.application.DocumentContent;
import com.adgendoc.application.DocumentGenerationService;
import com.adgendoc.application.RequestService;
import com.adgendoc.application.ValidationOutcome.FieldError;
import com.adgendoc.application.ValidationRejectedException;
import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.GeneratedDocument;
import com.adgendoc.domain.RequestStatus;
import com.adgendoc.infrastructure.config.CorrelationIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transport HTTP de E1–E6 (contrat API §3.1–§3.6) : aucune règle métier ;
 * toute la logique est déléguée à {@code RequestService},
 * {@code DocumentGenerationService} et {@code RequestResponseMapper}.
 */
@RestController
@RequestMapping("/api/v1/requests")
public class DocumentRequestController {

    private final RequestService requestService;
    private final DocumentGenerationService documentGenerationService;
    private final RequestResponseMapper mapper;
    private final ObjectMapper objectMapper;

    public DocumentRequestController(RequestService requestService,
                                     DocumentGenerationService documentGenerationService,
                                     RequestResponseMapper mapper,
                                     ObjectMapper objectMapper) {
        this.requestService = requestService;
        this.documentGenerationService = documentGenerationService;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRequestRequest body,
                                    HttpServletRequest http) {
        List<FieldError> envelopeErrors = envelopeKeyErrors(body.getUnknownFields());
        if (!envelopeErrors.isEmpty()) {
            throw new ValidationRejectedException(envelopeErrors);
        }

        DocumentRequest request = requestService.create(
                body.getDocumentType(),
                toObjectMap(body.getData()),
                toObjectMap(body.getExtraction()),
                CorrelationIdFilter.currentCorrelationId(http));

        String correlationId = CorrelationIdFilter.currentCorrelationId(http);
        return switch (request.getStatus()) {
            case VALIDATED -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(mapper.toStatusResponse(request));
            case MISSING_INFORMATION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(mapper.toMissingResponse(request, correlationId));
            case REJECTED -> ResponseEntity.badRequest().body(mapper.toRejectedResponse(
                    request, correlationId, toObjectMap(body.getData())));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorBody(ErrorCode.INTERNAL_ERROR, correlationId));
        };
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<RequestStatusResponse> get(@PathVariable UUID requestId) {
        DocumentRequest request = requestService.get(requestId);
        return ResponseEntity.ok(mapper.toStatusResponse(request));
    }

    @PatchMapping("/{requestId}")
    public ResponseEntity<?> patch(@PathVariable UUID requestId,
                                   @RequestBody PatchRequestRequest body,
                                   HttpServletRequest http) {
        DocumentRequest request = requestService.patch(requestId, mergedPatch(body));
        String correlationId = CorrelationIdFilter.currentCorrelationId(http);
        if (request.getStatus() == RequestStatus.REJECTED) {
            return ResponseEntity.badRequest()
                    .body(mapper.toRejectedResponse(request, correlationId));
        }
        return ResponseEntity.ok(mapper.toStatusResponse(request));
    }

    @PostMapping("/{requestId}/validate")
    public ResponseEntity<?> validate(@PathVariable UUID requestId, HttpServletRequest http) {
        DocumentRequest request = requestService.validate(requestId);
        String correlationId = CorrelationIdFilter.currentCorrelationId(http);
        return switch (request.getStatus()) {
            case VALIDATED -> ResponseEntity.ok(mapper.toStatusResponse(request));
            case MISSING_INFORMATION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(mapper.toMissingResponse(request, correlationId));
            case REJECTED -> ResponseEntity.badRequest()
                    .body(mapper.toRejectedResponse(request, correlationId));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorBody(ErrorCode.INTERNAL_ERROR, correlationId));
        };
    }

    @PostMapping("/{requestId}/generate")
    public ResponseEntity<GenerateDocumentResponse> generate(@PathVariable UUID requestId) {
        GeneratedDocument document = documentGenerationService.generate(requestId);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toGenerateResponse(document));
    }

    @GetMapping("/{requestId}/documents/{documentId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID requestId,
                                           @PathVariable UUID documentId) {
        DocumentContent content = documentGenerationService.getDocument(requestId, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf(content.metadata().getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + RequestResponseMapper.fileName(requestId)
                                + "\"")
                .contentLength(content.bytes().length)
                .body(content.bytes());
    }

    private ErrorResponse errorBody(ErrorCode code, String correlationId) {
        return new ErrorResponse(code.name(), code.getMessage(), correlationId,
                List.of(), null, null, List.of(), null);
    }

    private List<FieldError> envelopeKeyErrors(Map<String, JsonNode> unknownFields) {
        if (unknownFields == null || unknownFields.isEmpty()) {
            return List.of();
        }
        List<FieldError> errors = new ArrayList<>();
        for (String key : unknownFields.keySet()) {
            ErrorCode code = "referenceDemande".equals(key)
                    ? ErrorCode.ERR_CHAMP_RESERVE
                    : ErrorCode.ERR_CHAMP_INCONNU;
            errors.add(new FieldError(key, code, code.getMessage(key)));
        }
        return errors;
    }

    private Map<String, Object> mergedPatch(PatchRequestRequest body) {
        Map<String, Object> merged = new LinkedHashMap<>();
        toObjectMap(body.getFlatFields()).forEach(merged::put);
        toObjectMap(body.getData()).forEach(merged::put);
        return merged;
    }

    private Map<String, Object> toObjectMap(Map<String, JsonNode> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        source.forEach((key, node) -> converted.put(key,
                node == null || node.isNull() ? null : objectMapper.convertValue(node, Object.class)));
        return converted;
    }
}
