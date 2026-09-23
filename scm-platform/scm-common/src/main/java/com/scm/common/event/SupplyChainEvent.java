package com.scm.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope for every domain event on the scm.* Kafka topics. The Flink job and the Python AI
 * service produce and consume this same JSON shape.
 */
public record SupplyChainEvent(
        String eventId,
        String type,
        String source,
        String entityId,
        Instant timestamp,
        Map<String, Object> data) {

    public static SupplyChainEvent of(String source, String type, String entityId, Map<String, Object> data) {
        return new SupplyChainEvent(UUID.randomUUID().toString(), type, source, entityId, Instant.now(), data);
    }

    public String str(String key) {
        Object v = data == null ? null : data.get(key);
        return v == null ? null : v.toString();
    }

    public int integer(String key) {
        Object v = data == null ? null : data.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            throw new IllegalArgumentException("Event " + type + " has no '" + key + "'");
        }
        return Integer.parseInt(v.toString());
    }
}
