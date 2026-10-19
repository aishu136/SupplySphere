package com.scm.catalog;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.common.catalog.TemperatureRange;
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
public class CatalogController {

    /** Limits are optional: when left out, the profile's range applies. */
    public record TemperatureRangeRequest(String profile, Double minC, Double maxC) {}

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/suppliers")
    public List<SupplierView> suppliers() {
        return catalog.suppliers();
    }

    @GetMapping("/suppliers/{id}")
    public SupplierView supplier(@PathVariable Long id) {
        return catalog.supplier(id);
    }

    @GetMapping("/products")
    public List<ProductView> products() {
        return catalog.products();
    }

    @GetMapping("/products/{sku}")
    public ProductView product(@PathVariable String sku) {
        return catalog.product(sku);
    }

    /** Sets the cold-chain storage range; the change reaches other services on scm.catalog.events. */
    @PutMapping("/products/{sku}/temperature-range")
    public ProductView setTemperatureRange(@PathVariable String sku, @RequestBody TemperatureRangeRequest req) {
        return catalog.setTemperatureRange(sku, TemperatureRange.of(req.profile(), req.minC(), req.maxC()));
    }

    /** Makes the product ambient again. */
    @DeleteMapping("/products/{sku}/temperature-range")
    public ProductView clearTemperatureRange(@PathVariable String sku) {
        return catalog.setTemperatureRange(sku, null);
    }
}
