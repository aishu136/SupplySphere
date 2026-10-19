package com.scm.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.common.catalog.ProductView;
import com.scm.common.event.EventPublisher;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Event-carried state transfer: publishes products (with their supplier) to the compacted
 * scm.catalog.events topic, keyed by SKU. Other services keep a local read model from it, so they
 * can render product details without calling catalog-service on every request. The whole catalog
 * is republished on startup ({@link CatalogService#broadcastAll()}).
 */
@Component
public class CatalogBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(CatalogBroadcaster.class);

    private final EventPublisher events;
    private final ObjectMapper objectMapper;

    public CatalogBroadcaster(EventPublisher events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public void publishAll(List<ProductView> products) {
        products.forEach(this::publish);
        log.info("Broadcast {} products to {}", products.size(), Topics.CATALOG);
    }

    public void publish(ProductView product) {
        Map<String, Object> data = objectMapper.convertValue(product, new TypeReference<>() {});
        events.publish(Topics.CATALOG, SupplyChainEvent.of("catalog-service", "PRODUCT_UPSERTED", product.sku(), data));
    }
}
