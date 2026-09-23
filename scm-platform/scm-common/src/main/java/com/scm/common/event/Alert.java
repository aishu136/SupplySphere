package com.scm.common.event;

import java.time.Instant;
import java.util.UUID;

/** Operational alert on the scm.alerts topic, raised by services or the Flink stream processor. */
public record Alert(
        String alertId,
        String type,
        String severity,
        String message,
        String entityId,
        Instant timestamp,
        String source) {

    public static Alert of(String source, String type, String severity, String message, String entityId) {
        return new Alert(UUID.randomUUID().toString(), type, severity, message, entityId, Instant.now(), source);
    }
}
