package com.scm.core;

import com.scm.core.domain.InventoryItem;
import com.scm.core.domain.PurchaseOrder;
import com.scm.core.domain.Shipment;
import com.scm.core.service.AlertService;
import com.scm.core.service.InventoryService;
import com.scm.core.service.PurchaseOrderService;
import com.scm.core.service.ShipmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "scm.feeds.inbox=target/test-inbox")
class SupplyChainFlowTest {

    @Autowired InventoryService inventoryService;
    @Autowired PurchaseOrderService orderService;
    @Autowired ShipmentService shipmentService;
    @Autowired AlertService alertService;

    @Test
    void seededLowStockIsReported() {
        assertThat(inventoryService.findLowStock())
                .extracting(i -> i.getProduct().getSku() + "@" + i.getWarehouseCode())
                .contains("SKU-1001@WH-WEST", "SKU-3001@WH-EAST");
    }

    @Test
    void deliveringShipmentReceivesOrderIntoInventory() {
        int before = qty("SKU-1002", "WH-EAST");

        PurchaseOrder po = orderService.create("SKU-1002", "WH-EAST", 250);
        Shipment shipment = shipmentService.create(po.getOrderNumber(), "FedEx", "Ohio", "WH-EAST", Instant.now());
        shipmentService.updateStatus(shipment.getTrackingNumber(), Shipment.Status.IN_TRANSIT, null);
        shipmentService.updateStatus(shipment.getTrackingNumber(), Shipment.Status.DELIVERED, null);

        assertThat(orderService.get(po.getOrderNumber()).getStatus()).isEqualTo(PurchaseOrder.Status.RECEIVED);
        assertThat(qty("SKU-1002", "WH-EAST")).isEqualTo(before + 250);
    }

    @Test
    void droppingBelowReorderPointRaisesAlertWithoutKafka() {
        int current = qty("SKU-3002", "WH-EAST");
        inventoryService.setQuantity("SKU-3002", "WH-EAST", 10, "test");

        assertThat(alertService.recent())
                .anyMatch(a -> a.type().equals("LOW_STOCK") && a.entityId().equals("SKU-3002"));
        inventoryService.setQuantity("SKU-3002", "WH-EAST", current, "test-restore");
    }

    private int qty(String sku, String warehouse) {
        return inventoryService.findBySku(sku).stream()
                .filter(i -> i.getWarehouseCode().equals(warehouse))
                .mapToInt(InventoryItem::getQuantity)
                .findFirst()
                .orElseThrow();
    }
}
