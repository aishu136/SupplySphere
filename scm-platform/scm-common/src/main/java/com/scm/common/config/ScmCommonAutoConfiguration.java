package com.scm.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.common.event.EventJson;
import com.scm.common.event.EventPublisher;
import com.scm.common.event.KafkaMessageRelay;
import com.scm.common.event.Topics;
import com.scm.common.web.ApiExceptionHandler;
import com.scm.common.web.NotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;

/** Wires the shared event publishing, error handling and resilience defaults into every service. */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@Import(ApiExceptionHandler.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ScmCommonAutoConfiguration {

    @Bean
    public EventPublisher eventPublisher(ApplicationEventPublisher applicationEvents) {
        return new EventPublisher(applicationEvents);
    }

    @Bean
    public KafkaMessageRelay kafkaMessageRelay(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        return new KafkaMessageRelay(kafka, objectMapper);
    }

    @Bean
    public EventJson eventJson(ObjectMapper objectMapper) {
        return new EventJson(objectMapper);
    }

    /** Topic creation is idempotent, so every service declares the full set it may touch. */
    @Bean
    public KafkaAdmin.NewTopics scmTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(Topics.CATALOG).partitions(3).replicas(1).compact().build(),
                TopicBuilder.name(Topics.INVENTORY).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.ORDERS).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.SHIPMENTS).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.ALERTS).partitions(3).replicas(1)
                        .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(7).toMillis())).build(),
                TopicBuilder.name(Topics.VISION).partitions(3).replicas(1).build());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Resilience4JCircuitBreakerFactory.class)
    static class ResilienceDefaults {

        /**
         * Opens a circuit after half of the last 10 calls fail (min. 5), probes again after 10 s.
         * A 404 is a business answer, not a fault, so it never trips the breaker.
         */
        @Bean
        public Customizer<Resilience4JCircuitBreakerFactory> scmCircuitBreakerDefaults() {
            return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(5)
                            .failureRateThreshold(50)
                            .waitDurationInOpenState(Duration.ofSeconds(10))
                            .ignoreExceptions(NotFoundException.class)
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build())
                    .build());
        }
    }
}
