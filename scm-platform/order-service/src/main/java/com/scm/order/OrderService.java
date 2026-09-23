package com.scm.order;

import com.scm.common.catalog.ProductView;
import com.scm.common.event.EventPublisher;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import com.scm.common.web.NotFoundException;
import com.scm.order.domain.PurchaseOrder;
import com.scm.order.domain.PurchaseOrder.Status;
import com.scm.order.domain.PurchaseOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final Set<String> WAREHOUSES = Set.of("WH-EAST", "WH-WEST");

    private final PurchaseOrderRepository orders;
    private final CatalogClient catalog;
    private final EventPublisher events;

    public OrderService(PurchaseOrderRepository orders, CatalogClient catalog, EventPublisher events) {
        this.orders = orders;
        this.catalog = catalog;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderView> findAll() {
        return orders.findAllByOrderByCreatedAtDesc().stream().map(PurchaseOrderView::of).toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrderView get(String orderNumber) {
        return PurchaseOrderView.of(find(orderNumber));
    }

    public PurchaseOrderView create(String sku, String warehouseCode, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (!WAREHOUSES.contains(warehouseCode)) {
            throw new IllegalArgumentException("Unknown warehouse " + warehouseCode);
        }
        // Remote call happens outside the DB transaction so no connection is held while waiting.
        ProductView product = catalog.product(sku);
        return save(new PurchaseOrder("PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                product, warehouseCode, quantity));
    }

    @Transactional
    public PurchaseOrderView save(PurchaseOrder po) {
        PurchaseOrder saved = orders.save(po);
        publish("ORDER_CREATED", saved);
        return PurchaseOrderView.of(saved);
    }

    @Transactional
    public PurchaseOrderView updateStatus(String orderNumber, Status status) {
        PurchaseOrder po = find(orderNumber);
        if (po.isClosed()) {
            throw new IllegalStateException("Order " + orderNumber + " is already " + po.getStatus());
        }
        transition(po, status);
        return PurchaseOrderView.of(po);
    }

    /**
     * Saga step, driven by shipment-service events. Idempotent by state: a redelivered or
     * out-of-date event for an order that has already moved on is ignored.
     */
    @Transactional
    public void onShipmentEvent(SupplyChainEvent shipment) {
        String orderNumber = shipment.str("orderNumber");
        if (orderNumber == null) {
            return;
        }
        var found = orders.findByOrderNumber(orderNumber);
        if (found.isEmpty()) {
            log.warn("{} refers to unknown order {}", shipment.type(), orderNumber);
            return;
        }
        PurchaseOrder po = found.get();
        switch (shipment.type()) {
            case "SHIPMENT_IN_TRANSIT" -> {
                if (po.getStatus() == Status.CREATED || po.getStatus() == Status.APPROVED) {
                    transition(po, Status.SHIPPED);
                }
            }
            case "SHIPMENT_DELIVERED" -> {
                if (!po.isClosed()) {
                    transition(po, Status.RECEIVED);
                }
            }
            default -> { }
        }
    }

    private void transition(PurchaseOrder po, Status status) {
        po.setStatus(status);
        // ORDER_RECEIVED is the saga's next step: inventory-service puts the goods into stock.
        publish("ORDER_" + status.name(), po);
    }

    private PurchaseOrder find(String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new NotFoundException("Purchase order " + orderNumber + " not found"));
    }

    private void publish(String type, PurchaseOrder po) {
        events.publish(Topics.ORDERS, SupplyChainEvent.of("order-service", type, po.getOrderNumber(),
                Map.of("orderNumber", po.getOrderNumber(),
                        "supplierId", po.getSupplierId(),
                        "supplierName", po.getSupplierName(),
                        "sku", po.getSku(),
                        "warehouseCode", po.getWarehouseCode(),
                        "quantity", po.getQuantity(),
                        "status", po.getStatus().name())));
    }
}
