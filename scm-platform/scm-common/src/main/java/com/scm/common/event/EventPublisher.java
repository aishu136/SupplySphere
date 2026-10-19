package com.scm.common.event;

import org.springframework.context.ApplicationEventPublisher;

/**
 * Publishes messages to Kafka. Inside a Spring transaction they are held until it commits; outside
 * one they are sent immediately. The services store their data in Cassandra, which has no
 * multi-row transactions, so they write first and publish only once the write has succeeded:
 * no service sees an event for a change that was not stored. (For guaranteed delivery across
 * crashes, the next step is an outbox table written in the same logged batch as the change.)
 */
public class EventPublisher {

    public record OutgoingMessage(String topic, String key, Object payload) {}

    private final ApplicationEventPublisher applicationEvents;

    public EventPublisher(ApplicationEventPublisher applicationEvents) {
        this.applicationEvents = applicationEvents;
    }

    public void publish(String topic, SupplyChainEvent event) {
        publish(topic, event.entityId(), event);
    }

    public void publish(String topic, String key, Object payload) {
        applicationEvents.publishEvent(new OutgoingMessage(topic, key, payload));
    }
}
