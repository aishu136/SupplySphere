package com.scm.core.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    public enum Status { CREATED, APPROVED, SHIPPED, RECEIVED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @ManyToOne(optional = false)
    private Supplier supplier;

    @ManyToOne(optional = false)
    private Product product;

    @Column(nullable = false)
    private String warehouseCode;

    private int quantity;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant createdAt;
    private LocalDate expectedDelivery;

    protected PurchaseOrder() {}

    public PurchaseOrder(String orderNumber, Product product, String warehouseCode, int quantity, LocalDate expectedDelivery) {
        this.orderNumber = orderNumber;
        this.product = product;
        this.supplier = product.getSupplier();
        this.warehouseCode = warehouseCode;
        this.quantity = quantity;
        this.expectedDelivery = expectedDelivery;
        this.status = Status.CREATED;
        this.createdAt = Instant.now();
    }

    public void setStatus(Status status) { this.status = status; }

    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public Supplier getSupplier() { return supplier; }
    public Product getProduct() { return product; }
    public String getWarehouseCode() { return warehouseCode; }
    public int getQuantity() { return quantity; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDate getExpectedDelivery() { return expectedDelivery; }
}
