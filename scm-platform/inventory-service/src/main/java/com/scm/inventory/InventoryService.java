package com.scm.inventory;

import com.scm.common.catalog.ProductView;
import com.scm.common.event.EventPublisher;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import com.scm.common.web.NotFoundException;
import com.scm.inventory.domain.InventoryItem;
import com.scm.inventory.domain.ProcessedEvent;
import com.scm.inventory.domain.ProductRef;
import com.scm.inventory.domain.InventoryItemRepository;
import com.scm.inventory.domain.ProcessedEventRepository;
import com.scm.inventory.domain.ProductRefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryItemRepository inventory;
    private final ProductRefRepository products;
    private final ProcessedEventRepository processed;
    private final EventPublisher events;

    public InventoryService(InventoryItemRepository inventory, ProductRefRepository products,
                            ProcessedEventRepository processed, EventPublisher events) {
        this.inventory = inventory;
        this.products = products;
        this.processed = processed;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<InventoryItemView> findAll() {
        return render(inventory.findAll());
    }

    @Transactional(readOnly = true)
    public List<InventoryItemView> findBySku(String sku) {
        List<InventoryItem> items = inventory.findBySku(sku);
        if (items.isEmpty()) {
            throw new NotFoundException("No inventory for SKU " + sku);
        }
        return render(items);
    }

    @Transactional(readOnly = true)
    public List<InventoryItemView> findLowStock() {
        return render(inventory.findLowStock());
    }

    /** Relative change, e.g. picks (-) and manual corrections. */
    @Transactional
    public InventoryItemView adjust(String sku, String warehouseCode, int delta, String reason) {
        InventoryItem item = get(sku, warehouseCode);
        return render(List.of(apply(item, item.getQuantity() + delta, reason))).getFirst();
    }

    /** Absolute count, e.g. from a supplier/WMS stock feed or a cycle count. */
    @Transactional
    public void setQuantity(String sku, String warehouseCode, int quantity, String reason) {
        apply(get(sku, warehouseCode), quantity, reason);
    }

    /**
     * Saga step: order-service reports a purchase order RECEIVED, so the goods go into stock.
     * Idempotent: Kafka delivers at least once, and a redelivered event must not add stock twice.
     */
    @Transactional
    public void receivePurchaseOrder(SupplyChainEvent orderReceived) {
        if (processed.existsById(orderReceived.eventId())) {
            log.info("Skipping duplicate event {}", orderReceived.eventId());
            return;
        }
        String sku = orderReceived.str("sku");
        String warehouse = orderReceived.str("warehouseCode");
        var item = inventory.findBySkuAndWarehouseCode(sku, warehouse);
        if (item.isEmpty()) {
            log.error("PO {} received for unknown position {}@{}; needs manual put-away",
                    orderReceived.entityId(), sku, warehouse);
        } else {
            apply(item.get(), item.get().getQuantity() + orderReceived.integer("quantity"),
                    "PO receipt " + orderReceived.entityId());
        }
        processed.save(new ProcessedEvent(orderReceived.eventId()));
    }

    @Transactional
    public void upsertProduct(ProductView product) {
        ProductRef ref = products.findById(product.sku()).orElseGet(() -> new ProductRef(product.sku()));
        ref.update(product);
        products.save(ref);
    }

    private InventoryItem get(String sku, String warehouseCode) {
        return inventory.findBySkuAndWarehouseCode(sku, warehouseCode)
                .orElseThrow(() -> new NotFoundException("No inventory for " + sku + " in " + warehouseCode));
    }

    private InventoryItem apply(InventoryItem item, int newQuantity, String reason) {
        item.setQuantity(newQuantity);
        events.publish(Topics.INVENTORY, SupplyChainEvent.of("inventory-service", "INVENTORY_UPDATED",
                item.getSku() + "@" + item.getWarehouseCode(),
                Map.of("sku", item.getSku(),
                        "warehouseCode", item.getWarehouseCode(),
                        "quantity", item.getQuantity(),
                        "reorderPoint", item.getReorderPoint(),
                        "reason", reason == null ? "unspecified" : reason)));
        return item;
    }

    private List<InventoryItemView> render(List<InventoryItem> items) {
        Map<String, ProductView> catalog = products.findAllById(items.stream().map(InventoryItem::getSku).toList())
                .stream().map(ProductRef::toView).collect(Collectors.toMap(ProductView::sku, Function.identity()));
        return items.stream().map(i -> new InventoryItemView(
                i.getId(),
                // Until the catalog event arrives, show the SKU only (eventual consistency).
                catalog.getOrDefault(i.getSku(), new ProductView(null, i.getSku(), i.getSku(), null, null, null)),
                i.getWarehouseCode(), i.getQuantity(), i.getReorderPoint(), i.getReorderQuantity(),
                i.getUpdatedAt(), i.isLowStock())).toList();
    }
}
