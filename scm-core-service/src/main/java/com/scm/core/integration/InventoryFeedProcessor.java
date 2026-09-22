package com.scm.core.integration;

import com.scm.core.service.InventoryService;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Applies one row of a supplier/WMS stock feed: sku,warehouseCode,quantity. */
@Component
public class InventoryFeedProcessor {

    private final InventoryService inventoryService;

    public InventoryFeedProcessor(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    public void apply(Map<String, String> row) {
        String sku = required(row, "sku");
        String warehouse = required(row, "warehouseCode");
        int quantity = Integer.parseInt(required(row, "quantity"));
        inventoryService.setQuantity(sku, warehouse, quantity, "supplier-feed");
    }

    private static String required(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Feed row missing '" + column + "': " + row);
        }
        return value.trim();
    }
}
