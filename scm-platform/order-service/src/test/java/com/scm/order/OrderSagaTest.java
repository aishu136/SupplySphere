package com.scm.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import com.scm.order.domain.PurchaseOrder.Status;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.tracing.enabled=false"})
@EmbeddedKafka(partitions = 1)
class OrderSagaTest {

    @Autowired OrderService orderService;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;
    @Autowired EmbeddedKafkaBroker broker;
    @MockitoBean CatalogClient catalog;

    @Test
    void createsOrderFromCurrentCatalogData() {
        when(catalog.product("SKU-1002")).thenReturn(new ProductView(2L, "SKU-1002", "Aluminium mounting bracket",
                "Hardware", new BigDecimal("4.20"), new SupplierView(1L, "Acme Components", null, "USA", 4.6, 7)));

        PurchaseOrderView po = orderService.create("SKU-1002", "WH-EAST", 250);

        assertThat(po.status()).isEqualTo(Status.CREATED);
        assertThat(po.supplier().name()).isEqualTo("Acme Components");
        assertThat(po.expectedDelivery()).isEqualTo(java.time.LocalDate.now().plusDays(7));
    }

    @Test
    void deliveredShipmentReceivesOrderAndTellsInventory() throws Exception {
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("order-saga-test", "false", broker),
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(Topics.ORDERS));

            SupplyChainEvent delivered = SupplyChainEvent.of("shipment-service", "SHIPMENT_DELIVERED", "TRK-DEMO000001",
                    Map.of("trackingNumber", "TRK-DEMO000001", "orderNumber", "PO-DEMO0001", "status", "DELIVERED"));
            kafka.send(Topics.SHIPMENTS, delivered.entityId(), objectMapper.writeValueAsString(delivered)).get();

            var record = KafkaTestUtils.getSingleRecord(consumer, Topics.ORDERS, Duration.ofSeconds(20));
            SupplyChainEvent received = objectMapper.readValue(record.value(), SupplyChainEvent.class);
            assertThat(received.type()).isEqualTo("ORDER_RECEIVED");
            assertThat(received.str("sku")).isEqualTo("SKU-2001");
            assertThat(received.integer("quantity")).isEqualTo(5000);
            assertThat(orderService.get("PO-DEMO0001").status()).isEqualTo(Status.RECEIVED);

            // A redelivered event must not re-run the step (idempotent by state).
            kafka.send(Topics.SHIPMENTS, delivered.entityId(), objectMapper.writeValueAsString(delivered)).get();
            assertThat(KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3)).count()).isZero();
        }
    }
}
