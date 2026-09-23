package com.scm.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "inventory_items", uniqueConstraints = @UniqueConstraint(columnNames = {"sku", "warehouseCode"}))
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
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

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getWarehouseCode() { return warehouseCode; }
    public int getQuantity() { return quantity; }
    public int getReorderPoint() { return reorderPoint; }
    public int getReorderQuantity() { return reorderQuantity; }
    public Instant getUpdatedAt() { return updatedAt; }
}
