package com.scm.catalog.domain;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.TemperatureRange;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.math.BigDecimal;

@Table("products")
public class Product {

    @PrimaryKey
    private String sku;

    /** Assigned by the catalog (Cassandra has no sequences). */
    private Long id;

    private String name;
    private String category;
    private BigDecimal unitPrice;

    /** Cassandra has no joins: the supplier is looked up by ID when the product is rendered. */
    private Long supplierId;

    /** Cold-chain storage requirement; all null for ambient products. */
    private String storageProfile;
    private Double minTempC;
    private Double maxTempC;

    protected Product() {}

    public Product(Long id, String sku, String name, String category, BigDecimal unitPrice, Supplier supplier) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.unitPrice = unitPrice;
        this.supplierId = supplier.getId();
    }

    public Product(Long id, String sku, String name, String category, BigDecimal unitPrice, Supplier supplier,
                   TemperatureRange temperatureRange) {
        this(id, sku, name, category, unitPrice, supplier);
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

    public ProductView toView(Supplier supplier) {
        return new ProductView(id, sku, name, category, unitPrice, supplier.toView(), getTemperatureRange());
    }

    public String getSku() { return sku; }
    public Long getSupplierId() { return supplierId; }
}
