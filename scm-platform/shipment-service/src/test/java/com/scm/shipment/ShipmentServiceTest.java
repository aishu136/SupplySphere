package com.scm.shipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import com.scm.shipment.domain.Shipment.Status;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import com.scm.common.test.CassandraTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.tracing.enabled=false"})
@EmbeddedKafka(partitions = 1)
@Import(CassandraTestcontainer.class)
class ShipmentServiceTest {

    @Autowired ShipmentService shipmentService;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;
    @Autowired EmbeddedKafkaBroker broker;

    @Test
    void deliveryStartsTheSagaWithTheOrderReference() throws Exception {
        try (Consumer<String, String> consumer = consumer("shipment-saga-test")) {
            consumer.subscribe(List.of(Topics.SHIPMENTS));
            shipmentService.updateStatus("TRK-DEMO000001", Status.DELIVERED, null);

            ConsumerRecord<String, String> record = awaitRecord(consumer, r -> r.value().contains("SHIPMENT_DELIVERED"));
            SupplyChainEvent event = objectMapper.readValue(record.value(), SupplyChainEvent.class);
            assertThat(event.type()).isEqualTo("SHIPMENT_DELIVERED");
            assertThat(event.str("orderNumber")).isEqualTo("PO-DEMO0001");
        }
    }

    @Test
    void camelRoutesDamagedInspectionToDamagedStatusAndCriticalAlert() throws Exception {
        try (Consumer<String, String> consumer = consumer("shipment-alert-test")) {
            consumer.subscribe(List.of(Topics.ALERTS));
            SupplyChainEvent inspection = SupplyChainEvent.of("scm-ai-service", "INSPECTION_COMPLETED", "TRK-DEMO000003",
                    Map.of("damaged", true, "summary", "detector found crushed"));
            kafka.send(Topics.VISION, inspection.entityId(), objectMapper.writeValueAsString(inspection)).get();

            ConsumerRecord<String, String> alert = awaitRecord(consumer, r -> "TRK-DEMO000003".equals(r.key()));
            assertThat(alert.value()).contains("SHIPMENT_DAMAGED").contains("CRITICAL").contains("crushed");
            assertThat(shipmentService.get("TRK-DEMO000003").status()).isEqualTo(Status.DAMAGED);
        }
    }

    @Test
    void coldChainBreachQuarantinesShipmentWithColdChainAlert() throws Exception {
        try (Consumer<String, String> consumer = consumer("shipment-cold-chain-test")) {
            consumer.subscribe(List.of(Topics.ALERTS));
            SupplyChainEvent inspection = SupplyChainEvent.of("scm-ai-service", "COLD_CHAIN_INSPECTED", "TRK-DEMO000001",
                    Map.of("breach", true, "severity", "critical", "profile", "frozen",
                            "summary", "critical cold-chain breach for frozen goods; 1 hot spot(s) up to 6.0 °C"));
            kafka.send(Topics.VISION, inspection.entityId(), objectMapper.writeValueAsString(inspection)).get();

            ConsumerRecord<String, String> alert = awaitRecord(consumer, r -> r.value().contains("COLD_CHAIN_BREACH"));
            assertThat(alert.value()).contains("COLD_CHAIN_BREACH").contains("CRITICAL").contains("hot spot");
            assertThat(shipmentService.get("TRK-DEMO000001").status()).isEqualTo(Status.DAMAGED);
        }
    }

    @Test
    void cleanInspectionOnlyRecordsNotes() throws Exception {
        SupplyChainEvent inspection = SupplyChainEvent.of("scm-ai-service", "INSPECTION_COMPLETED", "TRK-DEMO000002",
                Map.of("damaged", false, "summary", "no damage detected"));
        kafka.send(Topics.VISION, inspection.entityId(), objectMapper.writeValueAsString(inspection)).get();

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (shipmentService.get("TRK-DEMO000002").inspectionNotes() == null && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        ShipmentView s = shipmentService.get("TRK-DEMO000002");
        assertThat(s.inspectionNotes()).isEqualTo("no damage detected");
        assertThat(s.status()).isEqualTo(Status.IN_TRANSIT);
    }

    /** Tests share topics, so wait for the record this test caused rather than expecting the only one. */
    private static ConsumerRecord<String, String> awaitRecord(Consumer<String, String> consumer,
                                                              Predicate<ConsumerRecord<String, String>> match) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        throw new AssertionError("No matching record within 30s");
    }

    private Consumer<String, String> consumer(String group) {
        return new DefaultKafkaConsumerFactory<>(KafkaTestUtils.consumerProps(group, "false", broker),
                new StringDeserializer(), new StringDeserializer()).createConsumer();
    }
}
