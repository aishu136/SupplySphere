package com.scm.stream;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real pipeline on a local mini-cluster with events shaped exactly as the Spring Boot
 * microservices publish them (the same sequence the end-to-end smoke test produces).
 */
class SupplyChainAlertJobTest {

    private static final String ETA = Instant.now().plus(Duration.ofDays(2)).toString();

    private static String event(String type, String source, String entityId, String data) {
        return """
                {"eventId":"%s","type":"%s","source":"%s","entityId":"%s","timestamp":"%s","data":%s}"""
                .formatted(java.util.UUID.randomUUID(), type, source, entityId, Instant.now(), data);
    }

    private static List<String> e2eSequence() {
        List<String> events = new ArrayList<>();
        String order = """
                {"orderNumber":"PO-AB12CD34","supplierId":1,"supplierName":"Acme Components","sku":"SKU-1002",\
                "warehouseCode":"WH-EAST","quantity":250,"status":"%s"}""";
        events.add(event("ORDER_CREATED", "order-service", "PO-AB12CD34", order.formatted("CREATED")));
        String shipment = """
                {"trackingNumber":"TRK-0123456789","status":"%s","carrier":"FedEx","destination":"WH-EAST",\
                "eta":"%s","orderNumber":"PO-AB12CD34"}""";
        events.add(event("SHIPMENT_CREATED", "shipment-service", "TRK-0123456789", shipment.formatted("CREATED", ETA)));
        events.add(event("SHIPMENT_IN_TRANSIT", "shipment-service", "TRK-0123456789", shipment.formatted("IN_TRANSIT", ETA)));
        events.add(event("ORDER_SHIPPED", "order-service", "PO-AB12CD34", order.formatted("SHIPPED")));
        events.add(event("SHIPMENT_DELIVERED", "shipment-service", "TRK-0123456789", shipment.formatted("DELIVERED", ETA)));
        events.add(event("ORDER_RECEIVED", "order-service", "PO-AB12CD34", order.formatted("RECEIVED")));
        events.add(event("INVENTORY_UPDATED", "inventory-service", "SKU-1002@WH-EAST", """
                {"sku":"SKU-1002","warehouseCode":"WH-EAST","quantity":1050,"reorderPoint":200,\
                "reason":"PO receipt PO-AB12CD34"}"""));
        // Shipment with no linked order and no ETA, as seeded data can produce.
        events.add(event("SHIPMENT_CREATED", "shipment-service", "TRK-NOORDER", """
                {"trackingNumber":"TRK-NOORDER","status":"CREATED","carrier":"UPS","destination":"Boston, MA",\
                "eta":null,"orderNumber":null}"""));
        events.add(event("INVENTORY_UPDATED", "inventory-service", "SKU-3002@WH-EAST", """
                {"sku":"SKU-3002","warehouseCode":"WH-EAST","quantity":30,"reorderPoint":40,"reason":"e2e"}"""));
        return events;
    }

    @Test
    void pipelineProcessesServiceEventsAndRaisesLowStock() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        DataStream<String> alerts = SupplyChainAlertJob.alerts(env.fromData(e2eSequence()),
                Duration.ZERO, Duration.ofMinutes(10), 3, 10_000);

        List<String> out = new ArrayList<>();
        alerts.executeAndCollect().forEachRemaining(out::add);

        assertTrue(out.stream().anyMatch(a -> a.contains("\"LOW_STOCK\"") && a.contains("SKU-3002")),
                "expected a LOW_STOCK alert for SKU-3002, got " + out);
    }
}
