package com.scm.inventory.domain;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Local read model of a catalog product, kept current from scm.catalog.events. */
@Entity
@Table(name = "product_refs")
public class ProductRef {

    @Id
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
    }

    public ProductView toView() {
        SupplierView supplier = supplierId == null ? null
                : new SupplierView(supplierId, supplierName, supplierEmail, supplierCountry, supplierRating, leadTimeDays);
        return new ProductView(productId, sku, name, category, unitPrice, supplier);
    }
}
