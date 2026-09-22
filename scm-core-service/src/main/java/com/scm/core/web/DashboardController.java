package com.scm.core.web;

import com.scm.core.domain.InventoryItem;
import com.scm.core.domain.PurchaseOrder;
import com.scm.core.domain.Shipment;
import com.scm.core.repository.InventoryItemRepository;
import com.scm.core.repository.PurchaseOrderRepository;
import com.scm.core.repository.ShipmentRepository;
import com.scm.core.service.AlertService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class DashboardController {

    public record Summary(long skus, long unitsOnHand, long lowStockItems, long openOrders,
                          long shipmentsInTransit, long delayedShipments, long recentAlerts) {}

    private final InventoryItemRepository inventory;
    private final PurchaseOrderRepository orders;
    private final ShipmentRepository shipments;
    private final AlertService alerts;

    public DashboardController(InventoryItemRepository inventory, PurchaseOrderRepository orders,
                               ShipmentRepository shipments, AlertService alerts) {
        this.inventory = inventory;
        this.orders = orders;
        this.shipments = shipments;
        this.alerts = alerts;
    }

    @GetMapping("/api/dashboard")
    @Transactional(readOnly = true)
    public Summary summary() {
        List<InventoryItem> items = inventory.findAll();
        List<Shipment> all = shipments.findAll();
        return new Summary(
                items.stream().map(i -> i.getProduct().getSku()).distinct().count(),
                items.stream().mapToLong(InventoryItem::getQuantity).sum(),
                items.stream().filter(InventoryItem::isLowStock).count(),
                orders.countByStatusIn(List.of(PurchaseOrder.Status.CREATED, PurchaseOrder.Status.APPROVED,
                        PurchaseOrder.Status.SHIPPED)),
                all.stream().filter(s -> s.getStatus() == Shipment.Status.IN_TRANSIT).count(),
                shipments.findDelayed(Instant.now()).size(),
                alerts.recent().size());
    }
}
