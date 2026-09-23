package com.scm.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parses JSON messages consumed from Kafka. */
public class EventJson {

    private final ObjectMapper objectMapper;

    public EventJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SupplyChainEvent event(String json) {
        return read(json, SupplyChainEvent.class);
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed " + type.getSimpleName() + " message", e);
        }
    }

    /** Converts an event's data map into a typed view, e.g. a ProductView. */
    public <T> T data(SupplyChainEvent event, Class<T> type) {
        return objectMapper.convertValue(event.data(), type);
    }
}
