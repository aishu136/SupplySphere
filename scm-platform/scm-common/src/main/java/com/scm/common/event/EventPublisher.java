package com.scm.common.event;

import org.springframework.context.ApplicationEventPublisher;

/**
 * Publishes messages to Kafka only after the surrounding database transaction commits, so other
 * services never see an event for a change that was rolled back. Outside a transaction the
 * message is sent immediately. (For guaranteed delivery across crashes, the next step is a
 * transactional outbox table.)
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
