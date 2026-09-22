package com.scm.core.service;

import com.scm.core.config.ScmProperties;
import com.scm.core.domain.Product;
import com.scm.core.domain.PurchaseOrder;
import com.scm.core.event.EventPublisher;
import com.scm.core.event.SupplyChainEvent;
import com.scm.core.repository.ProductRepository;
import com.scm.core.repository.PurchaseOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository orders;
    private final ProductRepository products;
    private final InventoryService inventoryService;
    private final EventPublisher events;
    private final ScmProperties props;

    public PurchaseOrderService(PurchaseOrderRepository orders, ProductRepository products,
                                InventoryService inventoryService, EventPublisher events, ScmProperties props) {
        this.orders = orders;
        this.products = products;
        this.inventoryService = inventoryService;
        this.events = events;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrder> findAll() {
        return orders.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public PurchaseOrder get(String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new NotFoundException("Purchase order " + orderNumber + " not found"));
    }

    @Transactional
    public PurchaseOrder create(String sku, String warehouseCode, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        Product product = products.findBySku(sku)
                .orElseThrow(() -> new NotFoundException("Product " + sku + " not found"));
        boolean stocked = inventoryService.findBySku(sku).stream()
                .anyMatch(i -> i.getWarehouseCode().equals(warehouseCode));
        if (!stocked) {
            throw new NotFoundException(sku + " is not stocked in " + warehouseCode);
        }

        String orderNumber = "PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocalDate expected = LocalDate.now().plusDays(product.getSupplier().getLeadTimeDays());
        PurchaseOrder po = orders.save(new PurchaseOrder(orderNumber, product, warehouseCode, quantity, expected));
        publish("ORDER_CREATED", po);
        return po;
    }

    @Transactional
    public PurchaseOrder updateStatus(String orderNumber, PurchaseOrder.Status status) {
        PurchaseOrder po = get(orderNumber);
        if (po.getStatus() == PurchaseOrder.Status.RECEIVED || po.getStatus() == PurchaseOrder.Status.CANCELLED) {
            throw new IllegalStateException("Order " + orderNumber + " is already " + po.getStatus());
        }
        po.setStatus(status);
        if (status == PurchaseOrder.Status.RECEIVED) {
            inventoryService.adjust(po.getProduct().getSku(), po.getWarehouseCode(), po.getQuantity(),
                    "PO receipt " + orderNumber);
        }
        publish("ORDER_" + status.name(), po);
        return po;
    }

    private void publish(String type, PurchaseOrder po) {
        events.publish(props.topics().orders(), SupplyChainEvent.of(type, po.getOrderNumber(),
                Map.of("orderNumber", po.getOrderNumber(),
                        "supplierId", po.getSupplier().getId(),
                        "supplierName", po.getSupplier().getName(),
                        "sku", po.getProduct().getSku(),
                        "warehouseCode", po.getWarehouseCode(),
                        "quantity", po.getQuantity(),
                        "status", po.getStatus().name())));
    }
}
