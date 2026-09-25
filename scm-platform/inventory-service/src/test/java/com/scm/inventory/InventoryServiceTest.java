package com.scm.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "scm.feeds.inbox=build/test-inbox",
        "management.tracing.enabled=false"})
@EmbeddedKafka(partitions = 1)
class InventoryServiceTest {

    @Autowired InventoryService inventoryService;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;

    @Test
    void rendersProductDetailsFromCatalogEvents() throws Exception {
        ProductView board = new ProductView(6L, "SKU-3002", "IoT gateway controller board", "Electronics",
                new BigDecimal("64.00"), new SupplierView(3L, "Initech Electronics", "sales@initech.example", "Taiwan", 3.8, 21));
        send(Topics.CATALOG, SupplyChainEvent.of("test", "PRODUCT_UPSERTED", "SKU-3002",
                objectMapper.convertValue(board, Map.class)));

        eventually(() -> inventoryService.findBySku("SKU-3002").getFirst().product().supplier() != null);
        InventoryItemView item = inventoryService.findBySku("SKU-3002").getFirst();
        assertThat(item.product().name()).isEqualTo("IoT gateway controller board");
        assertThat(item.product().supplier().leadTimeDays()).isEqualTo(21);
    }

    @Test
    void orderReceivedEventAddsStockExactlyOnce() throws Exception {
        int before = qty("SKU-1002", "WH-EAST");
        SupplyChainEvent received = SupplyChainEvent.of("order-service", "ORDER_RECEIVED", "PO-TEST0001",
                Map.of("orderNumber", "PO-TEST0001", "sku", "SKU-1002", "warehouseCode", "WH-EAST", "quantity", 250));

        send(Topics.ORDERS, received);
        send(Topics.ORDERS, received); // Kafka redelivery of the same event

        eventually(() -> qty("SKU-1002", "WH-EAST") == before + 250);
        Thread.sleep(1500); // give a (wrong) second application time to show up
        assertThat(qty("SKU-1002", "WH-EAST")).isEqualTo(before + 250);
    }

    @Test
    void reportsLowStockPositions() {
        assertThat(inventoryService.findLowStock())
                .extracting(i -> i.product().sku() + "@" + i.warehouseCode())
                .contains("SKU-1001@WH-WEST", "SKU-3001@WH-EAST");
    }

    private void send(String topic, SupplyChainEvent event) throws Exception {
        kafka.send(topic, event.entityId(), objectMapper.writeValueAsString(event)).get();
    }

    private int qty(String sku, String warehouse) {
        return inventoryService.findBySku(sku).stream()
                .filter(i -> i.warehouseCode().equals(warehouse))
                .mapToInt(InventoryItemView::quantity).findFirst().orElseThrow();
    }

    static void eventually(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 20s");
            }
            Thread.sleep(200);
        }
    }
}
