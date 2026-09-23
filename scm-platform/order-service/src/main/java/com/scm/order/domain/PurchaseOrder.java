package com.scm.order.domain;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Product and supplier details are copied in when the order is placed: a PO records what was
 * ordered, from whom, at what price, even if the catalog changes later.
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    public enum Status { CREATED, APPROVED, SHIPPED, RECEIVED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Column(nullable = false)
    private String sku;
    private String productName;
    private String category;
    private BigDecimal unitPrice;
    private Long supplierId;
    private String supplierName;
    private int leadTimeDays;

    @Column(nullable = false)
    private String warehouseCode;
    private int quantity;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant createdAt;
    private LocalDate expectedDelivery;

    protected PurchaseOrder() {}

    public PurchaseOrder(String orderNumber, ProductView product, String warehouseCode, int quantity) {
        this.orderNumber = orderNumber;
        this.sku = product.sku();
        this.productName = product.name();
        this.category = product.category();
        this.unitPrice = product.unitPrice();
        this.supplierId = product.supplier().id();
        this.supplierName = product.supplier().name();
        this.leadTimeDays = product.supplier().leadTimeDays();
        this.warehouseCode = warehouseCode;
        this.quantity = quantity;
        this.status = Status.CREATED;
        this.createdAt = Instant.now();
        this.expectedDelivery = LocalDate.now().plusDays(leadTimeDays);
    }

    public boolean isClosed() {
        return status == Status.RECEIVED || status == Status.CANCELLED;
    }

    public void setStatus(Status status) { this.status = status; }
    public void setExpectedDelivery(LocalDate expectedDelivery) { this.expectedDelivery = expectedDelivery; }

    public SupplierView supplierView() {
        return new SupplierView(supplierId, supplierName, null, null, 0, leadTimeDays);
    }

    public ProductView productView() {
        return new ProductView(null, sku, productName, category, unitPrice, supplierView());
    }

    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public String getSku() { return sku; }
    public Long getSupplierId() { return supplierId; }
    public String getSupplierName() { return supplierName; }
    public String getWarehouseCode() { return warehouseCode; }
    public int getQuantity() { return quantity; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDate getExpectedDelivery() { return expectedDelivery; }
}
