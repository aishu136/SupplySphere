package com.scm.catalog;

import com.scm.catalog.domain.Product;
import com.scm.catalog.domain.ProductRepository;
import com.scm.catalog.domain.Supplier;
import com.scm.catalog.domain.SupplierRepository;
import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.common.catalog.TemperatureRange;
import com.scm.common.web.NotFoundException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalog reads are served from Redis (order-service looks up a product for every PO, and the dashboard
 * and AI tools read the catalog constantly), and Cassandra on a miss. catalog-service is the only writer,
 * so a change refreshes the product's entry and drops the cached list; the TTL is only a safety net.
 */
@Service
public class CatalogService {

    public static final String PRODUCT = "product";
    public static final String PRODUCTS = "products";
    public static final String SUPPLIER = "supplier";
    public static final String SUPPLIERS = "suppliers";

    private final SupplierRepository suppliers;
    private final ProductRepository products;
    private final CatalogBroadcaster broadcaster;

    public CatalogService(SupplierRepository suppliers, ProductRepository products, CatalogBroadcaster broadcaster) {
        this.suppliers = suppliers;
        this.products = products;
        this.broadcaster = broadcaster;
    }

    @Cacheable(SUPPLIERS)
    public List<SupplierView> suppliers() {
        return suppliers.findAll().stream().map(Supplier::toView).sorted(Comparator.comparing(SupplierView::id)).toList();
    }

    @Cacheable(SUPPLIER)
    public SupplierView supplier(Long id) {
        return suppliers.findById(id).map(Supplier::toView)
                .orElseThrow(() -> new NotFoundException("Supplier " + id + " not found"));
    }

    @Cacheable(PRODUCTS)
    public List<ProductView> products() {
        return loadAll();
    }

    @Cacheable(PRODUCT)
    public ProductView product(String sku) {
        return view(find(sku));
    }

    /** Sets the cold-chain storage range, or makes the product ambient again ({@code null}). */
    @Caching(put = @CachePut(cacheNames = PRODUCT, key = "#sku"),
            evict = @CacheEvict(cacheNames = PRODUCTS, allEntries = true))
    public ProductView setTemperatureRange(String sku, TemperatureRange range) {
        Product product = find(sku);
        product.setTemperatureRange(range);
        products.save(product);
        ProductView view = view(product);
        broadcaster.publish(view);
        return view;
    }

    /** Event-carried state transfer: republish the whole catalog (from the database, not the cache) on startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void broadcastAll() {
        broadcaster.publishAll(loadAll());
    }

    private List<ProductView> loadAll() {
        Map<Long, Supplier> byId = suppliers.findAll().stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        return products.findAll().stream()
                .map(p -> p.toView(supplierOf(p, byId.get(p.getSupplierId()))))
                .sorted(Comparator.comparing(ProductView::sku))
                .toList();
    }

    private ProductView view(Product product) {
        return product.toView(supplierOf(product, suppliers.findById(product.getSupplierId()).orElse(null)));
    }

    private static Supplier supplierOf(Product product, Supplier supplier) {
        if (supplier == null) {
            throw new IllegalStateException("Product " + product.getSku() + " refers to unknown supplier "
                    + product.getSupplierId());
        }
        return supplier;
    }

    private Product find(String sku) {
        return products.findById(sku).orElseThrow(() -> new NotFoundException("Product " + sku + " not found"));
    }
}
