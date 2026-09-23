package com.scm.common.catalog;

import java.math.BigDecimal;

/** Product as exposed by catalog-service and carried on scm.catalog.events. */
public record ProductView(Long id, String sku, String name, String category, BigDecimal unitPrice,
                          SupplierView supplier) {
}
