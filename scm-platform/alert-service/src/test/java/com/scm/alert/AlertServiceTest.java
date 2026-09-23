package com.scm.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.common.event.Alert;
import com.scm.common.event.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.tracing.enabled=false"})
@EmbeddedKafka(partitions = 1)
class AlertServiceTest {

    @Autowired AlertService alertService;
    @Autowired AlertController controller;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;

    @Test
    void collectsFlinkAlertsOnceEvenWhenRedelivered() throws Exception {
        Alert lowStock = Alert.of("scm-stream-processor", "LOW_STOCK", "HIGH", "SKU-3002 at WH-EAST is at 30 units", "SKU-3002");
        String json = objectMapper.writeValueAsString(lowStock);
        kafka.send(Topics.ALERTS, "SKU-3002", json).get();
        kafka.send(Topics.ALERTS, "SKU-3002", json).get();

        waitFor(() -> alertService.recent().stream().anyMatch(a -> a.alertId().equals(lowStock.alertId())));
        Thread.sleep(1000);
        assertThat(alertService.recent()).filteredOn(a -> a.alertId().equals(lowStock.alertId())).hasSize(1);
    }

    @Test
    void alertsRaisedOverRestGoThroughTheTopic() throws Exception {
        Alert raised = controller.raise(new AlertController.RaiseRequest("SHIPMENT_DAMAGED", "CRITICAL", "crushed pallet", "TRK-1"));
        waitFor(() -> alertService.recent().stream().anyMatch(a -> a.alertId().equals(raised.alertId())));
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 20s");
            }
            Thread.sleep(200);
        }
    }
}
