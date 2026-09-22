package com.scm.core.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope for every event on the scm.* Kafka topics. The Flink job and the
 * Python AI service produce and consume this same JSON shape.
 */
public record SupplyChainEvent(
        String eventId,
        String type,
        String source,
        String entityId,
        Instant timestamp,
        Map<String, Object> data) {

    public static SupplyChainEvent of(String type, String entityId, Map<String, Object> data) {
        return new SupplyChainEvent(UUID.randomUUID().toString(), type, "scm-core-service", entityId, Instant.now(), data);
    }
}
