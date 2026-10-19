package com.scm.catalog;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.TemperatureRange;
import com.scm.common.event.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import com.scm.common.test.CassandraTestcontainer;
import com.scm.common.test.RedisTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.tracing.enabled=false"})
@EmbeddedKafka(partitions = 1)
@Import({CassandraTestcontainer.class, RedisTestcontainer.class})
class CatalogServiceTest {

    @Autowired CatalogController controller;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired StringRedisTemplate redis;

    @Test
    void servesProductsWithTheirSupplier() {
        ProductView board = controller.product("SKU-3002");
        assertThat(board.supplier().name()).isEqualTo("Initech Electronics");
        assertThat(board.supplier().leadTimeDays()).isEqualTo(21);
        assertThat(controller.products()).hasSize(9);
        assertThat(board.temperatureRange()).isNull(); // ambient
    }

    @Test
    void foodProductsCarryTheirTemperatureRange() {
        assertThat(controller.product("SKU-4001").temperatureRange()).isEqualTo(new TemperatureRange("FROZEN", -30, -18));
        assertThat(controller.product("SKU-4003").temperatureRange()).isEqualTo(new TemperatureRange("FROZEN", -30, -25));
    }

    @Test
    void temperatureRangeCanBeSetAndCleared() {
        ProductView milk = controller.setTemperatureRange("SKU-4002",
                new CatalogController.TemperatureRangeRequest("chilled", 1.0, null));
        assertThat(milk.temperatureRange()).isEqualTo(new TemperatureRange("CHILLED", 1, 4));
        assertThat(controller.product("SKU-4002").temperatureRange().minC()).isEqualTo(1);

        assertThat(controller.clearTemperatureRange("SKU-4002").temperatureRange()).isNull();
        controller.setTemperatureRange("SKU-4002", new CatalogController.TemperatureRangeRequest("CHILLED", null, null));
    }

    @Test
    void rejectsInvalidRanges() {
        assertThatThrownBy(() -> controller.setTemperatureRange("SKU-4001",
                new CatalogController.TemperatureRangeRequest("TROPICAL", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown profile");
        assertThatThrownBy(() -> controller.setTemperatureRange("SKU-4001",
                new CatalogController.TemperatureRangeRequest("FROZEN", -10.0, -20.0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("below");
    }

    @Test
    void cachesReadsInRedisAndRefreshesThemOnChange() {
        controller.product("SKU-4001");
        controller.products();
        assertThat(redis.opsForValue().get("catalog-service::product::SKU-4001"))
                .contains("Frozen salmon fillets").contains("\"minC\":-30.0");
        assertThat(redis.keys("catalog-service::products::*")).hasSize(1);

        try {
            controller.setTemperatureRange("SKU-4001", new CatalogController.TemperatureRangeRequest("FROZEN", -28.0, null));
            // The product's entry is replaced and the list dropped, so neither read is stale.
            assertThat(redis.opsForValue().get("catalog-service::product::SKU-4001")).contains("\"minC\":-28.0");
            assertThat(redis.keys("catalog-service::products::*")).isEmpty();
            assertThat(controller.products()).filteredOn(p -> p.sku().equals("SKU-4001"))
                    .singleElement().extracting(p -> p.temperatureRange().minC()).isEqualTo(-28.0);
        } finally {
            controller.setTemperatureRange("SKU-4001", new CatalogController.TemperatureRangeRequest("FROZEN", null, null));
        }
    }

    @Test
    void broadcastsEveryProductOnStartup() {
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("catalog-test", "false", broker),
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(Topics.CATALOG));
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 9);
            assertThat(records.count()).isGreaterThanOrEqualTo(9);
            assertThat(records.records(Topics.CATALOG)).anySatisfy(r -> {
                assertThat(r.key()).isEqualTo("SKU-3002");
                assertThat(r.value()).contains("PRODUCT_UPSERTED").contains("Initech Electronics");
            });
        }
    }
}
