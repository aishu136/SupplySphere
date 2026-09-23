package com.scm.catalog.domain;

import com.scm.common.catalog.ProductView;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    private String category;
    private BigDecimal unitPrice;

    @ManyToOne(optional = false)
    private Supplier supplier;

    protected Product() {}

    public Product(String sku, String name, String category, BigDecimal unitPrice, Supplier supplier) {
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.unitPrice = unitPrice;
        this.supplier = supplier;
    }

    public ProductView toView() {
        return new ProductView(id, sku, name, category, unitPrice, supplier.toView());
    }

    public String getSku() { return sku; }
}
