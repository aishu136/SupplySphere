package com.scm.inventory.messaging;

import com.scm.common.catalog.ProductView;
import com.scm.common.event.EventJson;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import com.scm.inventory.InventoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryListeners {

    private final InventoryService inventoryService;
    private final EventJson json;

    public InventoryListeners(InventoryService inventoryService, EventJson json) {
        this.inventoryService = inventoryService;
        this.json = json;
    }

    /** Keeps the local product read model in sync with catalog-service. */
    @KafkaListener(topics = Topics.CATALOG, groupId = "inventory-service")
    public void onCatalogEvent(String message) {
        SupplyChainEvent event = json.event(message);
        if ("PRODUCT_UPSERTED".equals(event.type())) {
            inventoryService.upsertProduct(json.data(event, ProductView.class));
        }
    }

    /** Saga: purchase order received -> put the goods into stock. */
    @KafkaListener(topics = Topics.ORDERS, groupId = "inventory-service")
    public void onOrderEvent(String message) {
        SupplyChainEvent event = json.event(message);
        if ("ORDER_RECEIVED".equals(event.type())) {
            inventoryService.receivePurchaseOrder(event);
        }
    }
}
