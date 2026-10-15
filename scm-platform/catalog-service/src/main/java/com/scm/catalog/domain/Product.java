package com.scm.catalog.domain;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.TemperatureRange;
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

    /** Cold-chain storage requirement; all null for ambient products. */
    private String storageProfile;
    private Double minTempC;
    private Double maxTempC;

    protected Product() {}

    public Product(String sku, String name, String category, BigDecimal unitPrice, Supplier supplier) {
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.unitPrice = unitPrice;
        this.supplier = supplier;
    }

    public Product(String sku, String name, String category, BigDecimal unitPrice, Supplier supplier,
                   TemperatureRange temperatureRange) {
        this(sku, name, category, unitPrice, supplier);
        setTemperatureRange(temperatureRange);
    }

    public void setTemperatureRange(TemperatureRange range) {
        storageProfile = range == null ? null : range.profile();
        minTempC = range == null ? null : range.minC();
        maxTempC = range == null ? null : range.maxC();
    }

    public TemperatureRange getTemperatureRange() {
        return storageProfile == null ? null : new TemperatureRange(storageProfile, minTempC, maxTempC);
    }

    public ProductView toView() {
        return new ProductView(id, sku, name, category, unitPrice, supplier.toView(), getTemperatureRange());
    }

    public String getSku() { return sku; }
}
