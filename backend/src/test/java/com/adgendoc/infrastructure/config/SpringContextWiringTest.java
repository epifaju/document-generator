package com.adgendoc.infrastructure.config;

import com.adgendoc.api.DocumentRequestController;
import com.adgendoc.api.ExtractionController;
import com.adgendoc.api.GlobalExceptionHandler;
import com.adgendoc.api.HealthController;
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
import com.adgendoc.infrastructure.docx.PoiTemplateEngine;
import com.adgendoc.infrastructure.persistence.AuditAdapter;
import com.adgendoc.infrastructure.persistence.GeneratedDocumentRepositoryAdapter;
import com.adgendoc.infrastructure.persistence.RequestRepositoryAdapter;
import com.adgendoc.infrastructure.persistence.TemplateRepositoryAdapter;
import com.adgendoc.infrastructure.storage.FileSystemStorageAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F2 — test de câblage Spring : le contexte complet démarre avec les
 * adapters de persistance réels (aucun mock), les beans applicatifs injectés
 * par {@code AppConfig}, et les adapters documentaires réels (POI +
 * filesystem). Profil {@code test} (H2) : le schéma PostgreSQL réel est
 * validé par le gate séparé {@code PostgresPersistenceIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpringContextWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AppProperties appProperties;

    @Test
    void application_bean_graph_is_wired() {
        assertThat(context.getBean(NormalizationService.class)).isNotNull();
        assertThat(context.getBean(ValidationService.class)).isNotNull();
        assertThat(context.getBean(RequestService.class)).isNotNull();
        assertThat(context.getBean(DocumentGenerationService.class)).isNotNull();
        assertThat(context.getBean(ExtractionValidationService.class)).isNotNull();
        assertThat(context.getBean(RequestResponseMapper.class)).isNotNull();
    }

    @Test
    void controllers_and_handler_are_wired() {
        assertThat(context.getBean(DocumentRequestController.class)).isNotNull();
        assertThat(context.getBean(ExtractionController.class)).isNotNull();
        assertThat(context.getBean(HealthController.class)).isNotNull();
        assertThat(context.getBean(GlobalExceptionHandler.class)).isNotNull();
        assertThat(context.getBean(CorrelationIdFilter.class)).isNotNull();
        assertThat(context.getBean(PayloadSizeLimitFilter.class)).isNotNull();
    }

    @Test
    void persistence_adapters_implement_domain_ports() {
        assertThat(context.getBean(RequestRepository.class))
                .isInstanceOf(RequestRepositoryAdapter.class);
        assertThat(context.getBean(GeneratedDocumentRepository.class))
                .isInstanceOf(GeneratedDocumentRepositoryAdapter.class);
        assertThat(context.getBean(TemplateRepository.class))
                .isInstanceOf(TemplateRepositoryAdapter.class);
        assertThat(context.getBean(AuditPort.class)).isInstanceOf(AuditAdapter.class);
    }

    @Test
    void app_properties_are_bound_from_application_yaml() {
        assertThat(appProperties.getDocument().getMaxPayloadBytes()).isEqualTo(65536);
        assertThat(appProperties.getDocument().getStoragePath()).isEqualTo("./storage");
        assertThat(appProperties.getDocument().getTemplateDir()).isEqualTo("../templates");
        assertThat(appProperties.getExtraction().getSchemaPath()).isEqualTo(
                "classpath:prompts/extraction/attestation_concordance.schema.json");
    }

    @Test
    void clock_bean_is_utc() {
        assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
    }

    /**
     * F2 : les implémentations réelles sont câblées —
     * {@code PoiTemplateEngine} (DOCX + checksum) et
     * {@code FileSystemStorageAdapter} ; les placeholders {@code Unavailable*}
     * de F1 n'existent plus.
     */
    @Test
    void phase_f2_wires_real_docx_implementations() {
        assertThat(context.getBean(TemplateEngine.class))
                .isInstanceOf(PoiTemplateEngine.class);
        assertThat(context.getBean(DocumentStorage.class))
                .isInstanceOf(FileSystemStorageAdapter.class);
        assertThat(doesNotExist("com.adgendoc.infrastructure.docx.UnavailableTemplateEngine"))
                .isTrue();
        assertThat(doesNotExist("com.adgendoc.infrastructure.storage.UnavailableDocumentStorage"))
                .isTrue();
    }

    private boolean doesNotExist(String className) {
        try {
            Class.forName(className);
            return false;
        } catch (ClassNotFoundException expected) {
            return true;
        }
    }
}
