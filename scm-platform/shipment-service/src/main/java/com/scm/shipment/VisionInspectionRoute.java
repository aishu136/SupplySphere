package com.scm.shipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.springframework.stereotype.Component;

/**
 * Apache Camel content-based router: consumes computer-vision inspection results published by the
 * AI service and sends damaged shipments down the exception path (status DAMAGED + critical alert).
 */
@Component
public class VisionInspectionRoute extends RouteBuilder {

    private final ObjectMapper objectMapper;
    private final ShipmentService shipmentService;

    public VisionInspectionRoute(ObjectMapper objectMapper, ShipmentService shipmentService) {
        this.objectMapper = objectMapper;
        this.shipmentService = shipmentService;
    }

    @Override
    public void configure() {
        errorHandler(deadLetterChannel("log:scm.dead-letter?level=ERROR&showCaughtException=true")
                .maximumRedeliveries(3)
                .redeliveryDelay(1000));

        Predicate damaged = exchange -> {
            SupplyChainEvent e = exchange.getIn().getBody(SupplyChainEvent.class);
            return e.data() != null && Boolean.TRUE.equals(e.data().get("damaged"));
        };

        from("kafka:" + Topics.VISION + "?brokers={{spring.kafka.bootstrap-servers}}&groupId=shipment-service"
                + "&autoOffsetReset=earliest")
                .routeId("vision-inspections")
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
