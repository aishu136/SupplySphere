package com.scm.core.event;

public interface EventPublisher {

    void publish(String topic, SupplyChainEvent event);
}
