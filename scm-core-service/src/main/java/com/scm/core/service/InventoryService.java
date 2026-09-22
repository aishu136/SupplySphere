package com.scm.core.service;

import com.scm.core.config.ScmProperties;
import com.scm.core.domain.InventoryItem;
import com.scm.core.event.Alert;
import com.scm.core.event.EventPublisher;
import com.scm.core.event.SupplyChainEvent;
import com.scm.core.repository.InventoryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class InventoryService {

    private final InventoryItemRepository inventory;
    private final EventPublisher events;
    private final AlertService alerts;
    private final ScmProperties props;

    public InventoryService(InventoryItemRepository inventory, EventPublisher events,
                            AlertService alerts, ScmProperties props) {
        this.inventory = inventory;
        this.events = events;
        this.alerts = alerts;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> findAll() {
        return inventory.findAll();
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> findBySku(String sku) {
        List<InventoryItem> items = inventory.findByProductSku(sku);
        if (items.isEmpty()) {
            throw new NotFoundException("No inventory for SKU " + sku);
        }
        return items;
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> findLowStock() {
        return inventory.findLowStock();
    }

    /** Relative change, e.g. picks (-) and receipts (+). */
    @Transactional
    public InventoryItem adjust(String sku, String warehouseCode, int delta, String reason) {
        InventoryItem item = get(sku, warehouseCode);
        return apply(item, item.getQuantity() + delta, reason);
    }

    /** Absolute count, e.g. from a supplier/WMS stock feed or a cycle count. */
    @Transactional
    public InventoryItem setQuantity(String sku, String warehouseCode, int quantity, String reason) {
        return apply(get(sku, warehouseCode), quantity, reason);
    }

    private InventoryItem get(String sku, String warehouseCode) {
        return inventory.findByProductSkuAndWarehouseCode(sku, warehouseCode)
                .orElseThrow(() -> new NotFoundException("No inventory for " + sku + " in " + warehouseCode));
    }

    private InventoryItem apply(InventoryItem item, int newQuantity, String reason) {
        boolean wasLow = item.isLowStock();
        item.setQuantity(newQuantity);
        events.publish(props.topics().inventory(), SupplyChainEvent.of("INVENTORY_UPDATED",
                item.getProduct().getSku() + "@" + item.getWarehouseCode(),
                Map.of("sku", item.getProduct().getSku(),
                        "warehouseCode", item.getWarehouseCode(),
                        "quantity", item.getQuantity(),
                        "reorderPoint", item.getReorderPoint(),
                        "reason", reason == null ? "unspecified" : reason)));
        // Flink raises this alert from the stream; this covers local runs without Kafka/Flink.
        if (!props.kafka().enabled() && item.isLowStock() && !wasLow) {
            alerts.raise(Alert.of("LOW_STOCK", "HIGH",
                    "%s at %s dropped to %d (reorder point %d)".formatted(item.getProduct().getSku(),
                            item.getWarehouseCode(), item.getQuantity(), item.getReorderPoint()),
                    item.getProduct().getSku()));
        }
        return item;
    }
}
