package com.scm.catalog;

import com.scm.catalog.domain.Product;
import com.scm.catalog.domain.ProductRepository;
import com.scm.catalog.domain.Supplier;
import com.scm.catalog.domain.SupplierRepository;
import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.common.catalog.TemperatureRange;
import com.scm.common.web.NotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Transactional(readOnly = true)
public class CatalogController {

    /** Limits are optional: when left out, the profile's range applies. */
    public record TemperatureRangeRequest(String profile, Double minC, Double maxC) {}

    private final SupplierRepository suppliers;
    private final ProductRepository products;
    private final CatalogBroadcaster broadcaster;

    public CatalogController(SupplierRepository suppliers, ProductRepository products, CatalogBroadcaster broadcaster) {
        this.suppliers = suppliers;
        this.products = products;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/suppliers")
    public List<SupplierView> suppliers() {
        return suppliers.findAll().stream().map(Supplier::toView).toList();
    }

    @GetMapping("/suppliers/{id}")
    public SupplierView supplier(@PathVariable Long id) {
        return suppliers.findById(id).map(Supplier::toView)
                .orElseThrow(() -> new NotFoundException("Supplier " + id + " not found"));
    }

    @GetMapping("/products")
    public List<ProductView> products() {
        return products.findAll().stream().map(Product::toView).toList();
    }

    @GetMapping("/products/{sku}")
    public ProductView product(@PathVariable String sku) {
        return find(sku).toView();
    }

    /** Sets the cold-chain storage range; the change reaches other services on scm.catalog.events. */
    @PutMapping("/products/{sku}/temperature-range")
    @Transactional
    public ProductView setTemperatureRange(@PathVariable String sku, @RequestBody TemperatureRangeRequest req) {
        return updateRange(sku, TemperatureRange.of(req.profile(), req.minC(), req.maxC()));
    }

    /** Makes the product ambient again. */
    @DeleteMapping("/products/{sku}/temperature-range")
    @Transactional
    public ProductView clearTemperatureRange(@PathVariable String sku) {
        return updateRange(sku, null);
    }

    private ProductView updateRange(String sku, TemperatureRange range) {
        Product product = find(sku);
        product.setTemperatureRange(range);
        broadcaster.publish(product);
        return product.toView();
    }

    private Product find(String sku) {
        return products.findBySku(sku).orElseThrow(() -> new NotFoundException("Product " + sku + " not found"));
    }
}
