package com.scm.shipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import com.scm.shipment.domain.Shipment.Status;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.tracing.enabled=false"})
@EmbeddedKafka(partitions = 1)
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

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, Topics.SHIPMENTS, Duration.ofSeconds(20));
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

            ConsumerRecord<String, String> alert = KafkaTestUtils.getSingleRecord(consumer, Topics.ALERTS, Duration.ofSeconds(30));
            assertThat(alert.value()).contains("SHIPMENT_DAMAGED").contains("CRITICAL").contains("crushed");
            assertThat(shipmentService.get("TRK-DEMO000003").status()).isEqualTo(Status.DAMAGED);
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

    private Consumer<String, String> consumer(String group) {
        return new DefaultKafkaConsumerFactory<>(KafkaTestUtils.consumerProps(group, "false", broker),
                new StringDeserializer(), new StringDeserializer()).createConsumer();
    }
}
