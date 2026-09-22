package com.scm.core.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Creates the supply chain topics on startup when Kafka is enabled. */
@Configuration
@ConditionalOnProperty(name = "scm.kafka.enabled", havingValue = "true")
public class KafkaTopicsConfig {

    @Bean
    public KafkaAdmin.NewTopics scmTopics(ScmProperties props) {
        ScmProperties.Topics t = props.topics();
        return new KafkaAdmin.NewTopics(
                topic(t.inventory()),
                topic(t.orders()),
                topic(t.shipments()),
                topic(t.alerts()),
                topic(t.vision()));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
