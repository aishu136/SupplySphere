package com.scm.core.web;

import com.scm.core.domain.Product;
import com.scm.core.domain.Supplier;
import com.scm.core.repository.ProductRepository;
import com.scm.core.repository.SupplierRepository;
import com.scm.core.service.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final SupplierRepository suppliers;
    private final ProductRepository products;

    public CatalogController(SupplierRepository suppliers, ProductRepository products) {
        this.suppliers = suppliers;
        this.products = products;
    }

    @GetMapping("/suppliers")
    public List<Supplier> suppliers() {
        return suppliers.findAll();
    }

    @GetMapping("/suppliers/{id}")
    public Supplier supplier(@PathVariable Long id) {
        return suppliers.findById(id).orElseThrow(() -> new NotFoundException("Supplier " + id + " not found"));
    }

    @GetMapping("/products")
    public List<Product> products() {
        return products.findAll();
    }
}
