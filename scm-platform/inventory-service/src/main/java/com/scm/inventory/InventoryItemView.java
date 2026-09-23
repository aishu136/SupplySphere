package com.scm.inventory;

import com.scm.common.catalog.ProductView;

import java.time.Instant;

/** API shape of a stock position, with product details from the local catalog read model. */
public record InventoryItemView(Long id, ProductView product, String warehouseCode, int quantity, int reorderPoint,
                                int reorderQuantity, Instant updatedAt, boolean lowStock) {
}
