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
import org.springframework.data.cassandra.core.CassandraBatchOperations;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    /** Far longer than Kafka keeps the events, so a redelivery always finds its record. */
    private static final InsertOptions PROCESSED_EVENT_TTL = InsertOptions.builder().ttl(Duration.ofDays(30)).build();

    private static final Comparator<InventoryItem> BY_POSITION =
            Comparator.comparing(InventoryItem::getSku).thenComparing(InventoryItem::getWarehouseCode);

    private final InventoryItemRepository inventory;
    private final ProductRefRepository products;
    private final ProcessedEventRepository processed;
    private final CassandraOperations cassandra;
    private final EventPublisher events;

    public InventoryService(InventoryItemRepository inventory, ProductRefRepository products,
                            ProcessedEventRepository processed, CassandraOperations cassandra, EventPublisher events) {
        this.inventory = inventory;
        this.products = products;
        this.processed = processed;
        this.cassandra = cassandra;
        this.events = events;
    }

    public List<InventoryItemView> findAll() {
        return render(inventory.findAll().stream().sorted(BY_POSITION).toList());
    }

    public List<InventoryItemView> findBySku(String sku) {
        List<InventoryItem> items = inventory.findBySku(sku);
        if (items.isEmpty()) {
            throw new NotFoundException("No inventory for SKU " + sku);
        }
        return render(items);
    }

    /** Cassandra can't compare two columns, so this filters here; there is one row per SKU and warehouse. */
    public List<InventoryItemView> findLowStock() {
        return render(inventory.findAll().stream().filter(InventoryItem::isLowStock)
                .sorted(Comparator.comparingInt(InventoryItem::getQuantity)).toList());
    }

    /** Relative change, e.g. picks (-) and manual corrections. */
    public InventoryItemView adjust(String sku, String warehouseCode, int delta, String reason) {
        InventoryItem item = get(sku, warehouseCode);
        return render(List.of(change(item, item.getQuantity() + delta, reason))).getFirst();
    }

    /** Absolute count, e.g. from a supplier/WMS stock feed or a cycle count. */
    public void setQuantity(String sku, String warehouseCode, int quantity, String reason) {
        change(get(sku, warehouseCode), quantity, reason);
    }

    /**
     * Saga step: order-service reports a purchase order RECEIVED, so the goods go into stock.
     * Idempotent: Kafka delivers at least once, and a redelivered event must not add stock twice. The
     * stock and the processed-event record are written in one logged batch, so either both are stored or
     * neither is. Redeliveries of one order's event share its partition, so they arrive one at a time.
     */
    public void receivePurchaseOrder(SupplyChainEvent orderReceived) {
        if (processed.existsById(orderReceived.eventId())) {
            log.info("Skipping duplicate event {}", orderReceived.eventId());
            return;
        }
        String sku = orderReceived.str("sku");
        String warehouse = orderReceived.str("warehouseCode");
        var item = inventory.findBySkuAndWarehouseCode(sku, warehouse);
        String reason = "PO receipt " + orderReceived.entityId();

        CassandraBatchOperations batch = cassandra.batchOps();
        if (item.isEmpty()) {
            log.error("PO {} received for unknown position {}@{}; needs manual put-away",
                    orderReceived.entityId(), sku, warehouse);
        } else {
            item.get().setQuantity(item.get().getQuantity() + orderReceived.integer("quantity"));
            batch.update(item.get());
        }
        batch.insert(new ProcessedEvent(orderReceived.eventId()), PROCESSED_EVENT_TTL);
        batch.execute();
        item.ifPresent(i -> publishUpdate(i, reason));
    }

    public void upsertProduct(ProductView product) {
        ProductRef ref = products.findById(product.sku()).orElseGet(() -> new ProductRef(product.sku()));
        ref.update(product);
        products.save(ref);
    }

    private InventoryItem get(String sku, String warehouseCode) {
        return inventory.findBySkuAndWarehouseCode(sku, warehouseCode)
                .orElseThrow(() -> new NotFoundException("No inventory for " + sku + " in " + warehouseCode));
    }

    private InventoryItem change(InventoryItem item, int newQuantity, String reason) {
        item.setQuantity(newQuantity);
        inventory.save(item);
        publishUpdate(item, reason);
        return item;
    }

    /** Only after the write has succeeded (see {@link EventPublisher}). */
    private void publishUpdate(InventoryItem item, String reason) {
        events.publish(Topics.INVENTORY, SupplyChainEvent.of("inventory-service", "INVENTORY_UPDATED",
                item.getSku() + "@" + item.getWarehouseCode(),
                Map.of("sku", item.getSku(),
                        "warehouseCode", item.getWarehouseCode(),
                        "quantity", item.getQuantity(),
                        "reorderPoint", item.getReorderPoint(),
                        "reason", reason == null ? "unspecified" : reason)));
    }

    private List<InventoryItemView> render(List<InventoryItem> items) {
        List<String> skus = items.stream().map(InventoryItem::getSku).distinct().toList();
        Map<String, ProductView> catalog = products.findAllById(skus)
                .stream().map(ProductRef::toView).collect(Collectors.toMap(ProductView::sku, Function.identity()));
        return items.stream().map(i -> new InventoryItemView(
                // Until the catalog event arrives, show the SKU only (eventual consistency).
                catalog.getOrDefault(i.getSku(), new ProductView(null, i.getSku(), i.getSku(), null, null, null)),
                i.getWarehouseCode(), i.getQuantity(), i.getReorderPoint(), i.getReorderQuantity(),
                i.getUpdatedAt(), i.isLowStock())).toList();
    }
}
