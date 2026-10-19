package com.scm.inventory.domain;

import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/** Idempotent consumer: records each applied event so a Kafka redelivery never books stock twice. */
@Table("processed_events")
public class ProcessedEvent {

    @PrimaryKey
    private String eventId;
    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }
}
