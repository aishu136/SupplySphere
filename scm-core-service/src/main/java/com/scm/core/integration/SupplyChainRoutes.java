package com.scm.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.core.event.Alert;
import com.scm.core.event.SupplyChainEvent;
import com.scm.core.service.AlertService;
import com.scm.core.service.ShipmentService;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.dataformat.csv.CsvDataFormat;
import org.springframework.stereotype.Component;

/**
 * Apache Camel enterprise-integration routes:
 * <ol>
 *   <li>supplier-feed-ingest: polls a drop folder for supplier/WMS stock CSVs, splits rows,
 *       updates inventory (file -> CSV unmarshal -> splitter -> bean).</li>
 *   <li>flink-alerts: consumes alerts computed by the Flink job and fans them out to the UI.</li>
 *   <li>vision-inspections: consumes computer-vision inspection results and routes them with a
 *       content-based router (damaged vs. clean).</li>
 * </ol>
 * The two Kafka routes only start when {@code scm.kafka.enabled=true}.
 */
@Component
public class SupplyChainRoutes extends RouteBuilder {

    private final ObjectMapper objectMapper;
    private final InventoryFeedProcessor feedProcessor;
    private final AlertService alertService;
    private final ShipmentService shipmentService;

    public SupplyChainRoutes(ObjectMapper objectMapper, InventoryFeedProcessor feedProcessor,
                             AlertService alertService, ShipmentService shipmentService) {
        this.objectMapper = objectMapper;
        this.feedProcessor = feedProcessor;
        this.alertService = alertService;
        this.shipmentService = shipmentService;
    }

    @Override
    public void configure() {
        errorHandler(deadLetterChannel("log:scm.dead-letter?level=ERROR&showCaughtException=true&showStackTrace=true")
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .retryAttemptedLogLevel(LoggingLevel.WARN));

        CsvDataFormat csv = new CsvDataFormat();
        csv.setUseMaps(true);           // first line is the header: sku,warehouseCode,quantity
        csv.setIgnoreEmptyLines(true);
        csv.setTrim(true);

        from("file:{{scm.feeds.inbox}}?include=.*\\.csv&move=.done&moveFailed=.error&readLock=changed")
                .routeId("supplier-feed-ingest")
                .log("Ingesting supplier stock feed ${header.CamelFileName}")
                .unmarshal(csv)
                .split(body()).streaming()
                    .bean(feedProcessor, "apply")
                .end()
                .log("Finished supplier stock feed ${header.CamelFileName}");

        String kafka = "kafka:%s?brokers={{spring.kafka.bootstrap-servers}}&groupId=%s&autoOffsetReset=latest";

        from(kafka.formatted("{{scm.topics.alerts}}", "scm-core-alerts"))
                .routeId("flink-alerts")
                .autoStartup("{{scm.kafka.enabled}}")
                .unmarshal(new JacksonDataFormat(objectMapper, Alert.class))
                .bean(alertService, "raise");

        Predicate damaged = exchange -> {
            SupplyChainEvent e = exchange.getIn().getBody(SupplyChainEvent.class);
            return e.data() != null && Boolean.TRUE.equals(e.data().get("damaged"));
        };

        from(kafka.formatted("{{scm.topics.vision}}", "scm-core-vision"))
                .routeId("vision-inspections")
                .autoStartup("{{scm.kafka.enabled}}")
                .unmarshal(new JacksonDataFormat(objectMapper, SupplyChainEvent.class))
                .filter(simple("${body.type} == 'INSPECTION_COMPLETED'"))
                .choice()
                    .when(damaged)
                        .log(LoggingLevel.WARN, "Damage detected on shipment ${body.entityId}")
                        .bean(shipmentService, "handleDamagedInspection")
                    .otherwise()
                        .bean(shipmentService, "handleCleanInspection")
                .end();
    }
}
