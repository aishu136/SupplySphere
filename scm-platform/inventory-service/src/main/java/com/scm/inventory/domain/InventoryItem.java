package com.scm.inventory.domain;

import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/** Partitioned by SKU, one row per warehouse: a SKU's positions are read together. */
@Table("inventory_items")
public class InventoryItem {

    @PrimaryKeyColumn(ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String sku;

    @PrimaryKeyColumn(ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private String warehouseCode;

    private int quantity;
    private int reorderPoint;
    private int reorderQuantity;
    private Instant updatedAt;

    protected InventoryItem() {}

    public InventoryItem(String sku, String warehouseCode, int quantity, int reorderPoint, int reorderQuantity) {
        this.sku = sku;
        this.warehouseCode = warehouseCode;
        this.quantity = quantity;
        this.reorderPoint = reorderPoint;
        this.reorderQuantity = reorderQuantity;
        this.updatedAt = Instant.now();
    }

    public boolean isLowStock() {
        return quantity <= reorderPoint;
    }

    public void setQuantity(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative for " + sku + "@" + warehouseCode);
        }
        this.quantity = quantity;
        this.updatedAt = Instant.now();
    }

    public String getSku() { return sku; }
    public String getWarehouseCode() { return warehouseCode; }
    public int getQuantity() { return quantity; }
    public int getReorderPoint() { return reorderPoint; }
    public int getReorderQuantity() { return reorderQuantity; }
    public Instant getUpdatedAt() { return updatedAt; }
}
