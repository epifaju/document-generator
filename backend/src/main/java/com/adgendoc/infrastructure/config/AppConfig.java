package com.adgendoc.infrastructure.config;

import com.adgendoc.api.RequestResponseMapper;
import com.adgendoc.application.DocumentGenerationService;
import com.adgendoc.application.ExtractionValidationService;
import com.adgendoc.application.NormalizationService;
import com.adgendoc.application.RequestService;
import com.adgendoc.application.ValidationService;
import com.adgendoc.domain.ports.AuditPort;
import com.adgendoc.domain.ports.DocumentStorage;
import com.adgendoc.domain.ports.GeneratedDocumentRepository;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.domain.ports.TemplateEngine;
import com.adgendoc.domain.ports.TemplateRepository;
import com.adgendoc.infrastructure.docx.UnavailableTemplateEngine;
import com.adgendoc.infrastructure.storage.UnavailableDocumentStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Câblage Spring des beans applicatifs (architecture §4.1) : les classes de
 * {@code application} et de {@code domain} restent pures (aucune annotation
 * framework), les beans sont déclarés ici avec injection par constructeur.
 *
 * <p>Phase F1 : {@code TemplateEngine} et {@code DocumentStorage} sont des
 * <b>placeholders</b> (classes {@code Unavailable*}) — les implémentations
 * réelles ({@code PoiTemplateEngine}, {@code FileSystemStorageAdapter}) sont
 * hors périmètre F1.</p>
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public NormalizationService normalizationService() {
        return new NormalizationService();
    }

    @Bean
    public ValidationService validationService(Clock clock) {
        return new ValidationService(clock);
    }

    @Bean
    public RequestService requestService(NormalizationService normalizationService,
                                         ValidationService validationService,
                                         RequestRepository requestRepository,
                                         AuditPort auditPort,
                                         Clock clock) {
        return new RequestService(normalizationService, validationService, requestRepository,
                auditPort, clock);
    }

    @Bean
    public DocumentGenerationService documentGenerationService(
            RequestRepository requestRepository,
            GeneratedDocumentRepository generatedDocumentRepository,
            TemplateRepository templateRepository,
            TemplateEngine templateEngine,
            DocumentStorage documentStorage,
            AuditPort auditPort,
            Clock clock) {
        return new DocumentGenerationService(requestRepository, generatedDocumentRepository,
                templateRepository, templateEngine, documentStorage, auditPort, clock);
    }

    @Bean
    public ExtractionValidationService extractionValidationService(AppProperties properties,
                                                                   ObjectMapper objectMapper) {
        return new ExtractionValidationService(properties.getExtraction().getSchemaPath(),
                objectMapper);
    }

    @Bean
    public RequestResponseMapper requestResponseMapper(ValidationService validationService,
                                                       DocumentGenerationService generationService) {
        return new RequestResponseMapper(validationService, generationService);
    }

    /** Placeholder F1 : remplacé par {@code PoiTemplateEngine} (phase ultérieure). */
    @Bean
    public TemplateEngine templateEngine() {
        return new UnavailableTemplateEngine();
    }

    /** Placeholder F1 : remplacé par {@code FileSystemStorageAdapter} (phase ultérieure). */
    @Bean
    public DocumentStorage documentStorage() {
        return new UnavailableDocumentStorage();
    }
}
