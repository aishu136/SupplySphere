package com.scm.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.catalog.domain.Product;
import com.scm.catalog.domain.ProductRepository;
import com.scm.common.event.EventPublisher;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Event-carried state transfer: publishes every product (with its supplier) to the compacted
 * scm.catalog.events topic, keyed by SKU. Other services keep a local read model from it, so they
 * can render product details without calling catalog-service on every request.
 */
@Component
public class CatalogBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(CatalogBroadcaster.class);

    private final ProductRepository products;
    private final EventPublisher events;
    private final ObjectMapper objectMapper;

    public CatalogBroadcaster(ProductRepository products, EventPublisher events, ObjectMapper objectMapper) {
        this.products = products;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void broadcastAll() {
        List<Product> all = products.findAll();
        all.forEach(this::publish);
        log.info("Broadcast {} products to {}", all.size(), Topics.CATALOG);
    }

    public void publish(Product product) {
        Map<String, Object> data = objectMapper.convertValue(product.toView(), new TypeReference<>() {});
        events.publish(Topics.CATALOG, SupplyChainEvent.of("catalog-service", "PRODUCT_UPSERTED", product.getSku(), data));
    }
}
