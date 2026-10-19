package com.scm.inventory.domain;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.common.catalog.TemperatureRange;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.math.BigDecimal;

/** Local read model of a catalog product, kept current from scm.catalog.events. */
@Table("product_refs")
public class ProductRef {

    @PrimaryKey
    private String sku;
    private Long productId;
    private String name;
    private String category;
    private BigDecimal unitPrice;
    private Long supplierId;
    private String supplierName;
    private String supplierEmail;
    private String supplierCountry;
    private double supplierRating;
    private int leadTimeDays;
    private String storageProfile;
    private Double minTempC;
    private Double maxTempC;

    protected ProductRef() {}

    public ProductRef(String sku) {
        this.sku = sku;
    }

    public void update(ProductView p) {
        productId = p.id();
        name = p.name();
        category = p.category();
        unitPrice = p.unitPrice();
        SupplierView s = p.supplier();
        if (s != null) {
            supplierId = s.id();
            supplierName = s.name();
            supplierEmail = s.contactEmail();
            supplierCountry = s.country();
            supplierRating = s.rating();
            leadTimeDays = s.leadTimeDays();
        }
        TemperatureRange range = p.temperatureRange();
        storageProfile = range == null ? null : range.profile();
        minTempC = range == null ? null : range.minC();
        maxTempC = range == null ? null : range.maxC();
    }

    public ProductView toView() {
        SupplierView supplier = supplierId == null ? null
                : new SupplierView(supplierId, supplierName, supplierEmail, supplierCountry, supplierRating, leadTimeDays);
        TemperatureRange range = storageProfile == null ? null : new TemperatureRange(storageProfile, minTempC, maxTempC);
        return new ProductView(productId, sku, name, category, unitPrice, supplier, range);
    }
}
