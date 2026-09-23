package com.scm.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sends {@link EventPublisher} messages to Kafka as JSON once the transaction has committed. */
public class KafkaMessageRelay {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageRelay.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public KafkaMessageRelay(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void relay(EventPublisher.OutgoingMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize message for " + message.topic(), e);
        }
        kafka.send(message.topic(), message.key(), json).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to {} (key {})", message.topic(), message.key(), ex);
            }
        });
    }
}
