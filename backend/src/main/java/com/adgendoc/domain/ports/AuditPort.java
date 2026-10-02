package com.adgendoc.domain.ports;

import java.util.Map;
import java.util.UUID;

public interface AuditPort {

    void record(UUID requestId, String action, String actor, String correlationId,
                Map<String, Object> details);
}
