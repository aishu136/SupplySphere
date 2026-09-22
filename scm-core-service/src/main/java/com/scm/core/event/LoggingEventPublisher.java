package com.scm.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when Kafka is disabled (local dev without a broker). */
@Component
@ConditionalOnProperty(name = "scm.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(String topic, SupplyChainEvent event) {
        log.info("[kafka disabled] {} -> {} {} {}", topic, event.type(), event.entityId(), event.data());
    }
}
