package com.scm.catalog;

import com.scm.common.catalog.ProductView;
import com.scm.common.event.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.tracing.enabled=false"})
@EmbeddedKafka(partitions = 1)
class CatalogServiceTest {

    @Autowired CatalogController controller;
    @Autowired EmbeddedKafkaBroker broker;

    @Test
    void servesProductsWithTheirSupplier() {
        ProductView board = controller.product("SKU-3002");
        assertThat(board.supplier().name()).isEqualTo("Initech Electronics");
        assertThat(board.supplier().leadTimeDays()).isEqualTo(21);
        assertThat(controller.products()).hasSize(6);
    }

    @Test
    void broadcastsEveryProductOnStartup() {
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("catalog-test", "false", broker),
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(Topics.CATALOG));
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 6);
            assertThat(records.count()).isGreaterThanOrEqualTo(6);
            assertThat(records.records(Topics.CATALOG)).anySatisfy(r -> {
                assertThat(r.key()).isEqualTo("SKU-3002");
                assertThat(r.value()).contains("PRODUCT_UPSERTED").contains("Initech Electronics");
            });
        }
    }
}
