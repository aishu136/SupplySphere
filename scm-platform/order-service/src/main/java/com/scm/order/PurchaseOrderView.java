package com.scm.order;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.order.domain.PurchaseOrder;

import java.time.Instant;
import java.time.LocalDate;

public record PurchaseOrderView(Long id, String orderNumber, SupplierView supplier, ProductView product,
                                String warehouseCode, int quantity, PurchaseOrder.Status status,
                                Instant createdAt, LocalDate expectedDelivery) {

    public static PurchaseOrderView of(PurchaseOrder po) {
        return new PurchaseOrderView(po.getId(), po.getOrderNumber(), po.supplierView(), po.productView(),
                po.getWarehouseCode(), po.getQuantity(), po.getStatus(), po.getCreatedAt(), po.getExpectedDelivery());
    }
}
