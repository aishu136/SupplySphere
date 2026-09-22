package com.scm.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scm.kafka.enabled", havingValue = "true")
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topic, SupplyChainEvent event) {
        try {
            // Keyed by entity so all events for one SKU/order/shipment land on one partition, in order.
            kafkaTemplate.send(topic, event.entityId(), objectMapper.writeValueAsString(event))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} to {}", event.type(), topic, ex);
                        }
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + event.type(), e);
        }
    }
}
