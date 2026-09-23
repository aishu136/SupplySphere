package com.scm.catalog;

import com.scm.catalog.domain.Product;
import com.scm.catalog.domain.ProductRepository;
import com.scm.catalog.domain.Supplier;
import com.scm.catalog.domain.SupplierRepository;
import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.common.web.NotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Transactional(readOnly = true)
public class CatalogController {

    private final SupplierRepository suppliers;
    private final ProductRepository products;

    public CatalogController(SupplierRepository suppliers, ProductRepository products) {
        this.suppliers = suppliers;
        this.products = products;
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
        return products.findBySku(sku).map(Product::toView)
                .orElseThrow(() -> new NotFoundException("Product " + sku + " not found"));
    }
}
