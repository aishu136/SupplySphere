package com.scm.inventory.integration;

import com.scm.inventory.InventoryService;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.csv.CsvDataFormat;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Apache Camel: polls a drop folder for supplier/WMS stock CSVs (sku,warehouseCode,quantity),
 * splits the rows and applies each as an absolute stock count. Processed files move to .done/,
 * failures to .error/ after retries.
 */
@Component
public class SupplierFeedRoute extends RouteBuilder {

    private final InventoryService inventoryService;

    public SupplierFeedRoute(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public void configure() {
        errorHandler(deadLetterChannel("log:scm.dead-letter?level=ERROR&showCaughtException=true")
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .retryAttemptedLogLevel(LoggingLevel.WARN));

        CsvDataFormat csv = new CsvDataFormat();
        csv.setUseMaps(true);
        csv.setIgnoreEmptyLines(true);
        csv.setTrim(true);

        from("file:{{scm.feeds.inbox}}?include=.*\\.csv&move=.done&moveFailed=.error&readLock=changed")
                .routeId("supplier-feed-ingest")
                .log("Ingesting supplier stock feed ${header.CamelFileName}")
                .unmarshal(csv)
                .split(body()).streaming()
                    .process(exchange -> apply(exchange.getIn().getBody(Map.class)))
                .end()
                .log("Finished supplier stock feed ${header.CamelFileName}");
    }

    private void apply(Map<?, ?> row) {
        inventoryService.setQuantity(required(row, "sku"), required(row, "warehouseCode"),
                Integer.parseInt(required(row, "quantity")), "supplier-feed");
    }

    private static String required(Map<?, ?> row, String column) {
        Object value = row.get(column);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Feed row missing '" + column + "': " + row);
        }
        return value.toString().trim();
    }
}
