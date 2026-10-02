package com.adgendoc.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Transport HTTP de E7 {@code GET /api/v1/health} (contrat API §3.7) :
 * {@code UP} (200) ou {@code DOWN} (503) selon la disponibilité de la base.
 * Aucune information technique détaillée n'est exposée.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return isUp()
                ? ResponseEntity.ok(Map.of("status", "UP"))
                : ResponseEntity.status(503).body(Map.of("status", "DOWN"));
    }

    private boolean isUp() {
        if (dataSource == null) {
            return true;
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException exception) {
            return false;
        }
    }
}
