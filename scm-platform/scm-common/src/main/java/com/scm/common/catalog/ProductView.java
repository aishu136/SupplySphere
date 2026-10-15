package com.scm.common.catalog;

import java.math.BigDecimal;

/**
 * Product as exposed by catalog-service and carried on scm.catalog.events. {@code temperatureRange}
 * is null for ambient products (and in events published before it existed).
 */
public record ProductView(Long id, String sku, String name, String category, BigDecimal unitPrice,
                          SupplierView supplier, TemperatureRange temperatureRange) {

    /** An ambient product. */
    public ProductView(Long id, String sku, String name, String category, BigDecimal unitPrice, SupplierView supplier) {
        this(id, sku, name, category, unitPrice, supplier, null);
    }
}
