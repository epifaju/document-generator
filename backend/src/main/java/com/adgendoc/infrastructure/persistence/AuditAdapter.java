package com.adgendoc.infrastructure.persistence;

import com.adgendoc.domain.ports.AuditPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter du port {@code AuditPort} vers {@code audit_log} (architecture
 * §4.1) : <b>refuse toute clé de {@code details} portant une donnée
 * personnelle</b> (les 14 champs métier + l'enveloppe {@code data}) —
 * seules des métadonnées techniques sont journalisées (V1__init.sql,
 * commentaire « SANS PII »). {@code created_at} provient de l'horloge
 * applicative UTC.
 */
@Repository
public class AuditAdapter implements AuditPort {

    /** Champs métier dont la clé ne doit JAMAIS apparaître dans l'audit. */
    private static final Set<String> PII_KEYS = Set.of(
            "prenom", "nom", "dateNaissance", "lieuNaissance", "nomIncorrect", "nomCorrect",
            "sexe", "nationalite", "documentSourceReference", "langueDocument", "demandeur",
            "contactEmail", "contactTelephone", "motif", "data", "payload");

    private final AuditLogJpaRepository repository;
    private final Clock clock;

    public AuditAdapter(AuditLogJpaRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public void record(UUID requestId, String action, String actor, String correlationId,
                       Map<String, Object> details) {
        Map<String, Object> safeDetails = new LinkedHashMap<>();
        if (details != null) {
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                if (isPiiKey(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Audit details must not contain personal data key: "
                                    + entry.getKey());
                }
                safeDetails.put(entry.getKey(), entry.getValue());
            }
        }

        AuditLogEntity entity = new AuditLogEntity();
        entity.setRequestId(requestId);
        entity.setAction(action);
        entity.setActor(actor);
        entity.setCorrelationId(correlationId);
        entity.setCreatedAt(Instant.now(clock));
        entity.setDetails(safeDetails);
        repository.save(entity);
    }

    private boolean isPiiKey(String key) {
        return key != null && PII_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }
}
