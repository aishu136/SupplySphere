package com.scm.core.event;

import java.time.Instant;
import java.util.UUID;

/** Operational alert, raised locally or by the Flink stream processor. */
public record Alert(
        String alertId,
        String type,
        String severity,
        String message,
        String entityId,
        Instant timestamp,
        String source) {

    public static Alert of(String type, String severity, String message, String entityId) {
        return new Alert(UUID.randomUUID().toString(), type, severity, message, entityId, Instant.now(), "scm-core-service");
    }
}
