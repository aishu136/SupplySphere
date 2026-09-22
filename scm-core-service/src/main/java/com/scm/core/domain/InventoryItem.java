package com.scm.core.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "inventory_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "warehouseCode"}))
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Product product;

    @Column(nullable = false)
    private String warehouseCode;

    private int quantity;
    private int reorderPoint;
    private int reorderQuantity;
    private Instant updatedAt;

    protected InventoryItem() {}

    public InventoryItem(Product product, String warehouseCode, int quantity, int reorderPoint, int reorderQuantity) {
        this.product = product;
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
            throw new IllegalArgumentException("Quantity cannot be negative for " + product.getSku() + "@" + warehouseCode);
        }
        this.quantity = quantity;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public String getWarehouseCode() { return warehouseCode; }
    public int getQuantity() { return quantity; }
    public int getReorderPoint() { return reorderPoint; }
    public int getReorderQuantity() { return reorderQuantity; }
    public Instant getUpdatedAt() { return updatedAt; }
}
