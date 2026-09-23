package com.scm.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Idempotent consumer: records each applied event so a Kafka redelivery never books stock twice. */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    private String eventId;
    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }
}
