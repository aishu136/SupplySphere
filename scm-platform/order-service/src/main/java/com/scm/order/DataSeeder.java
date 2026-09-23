package com.scm.order;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.order.domain.PurchaseOrder;
import com.scm.order.domain.PurchaseOrderRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Demo orders. Product/supplier snapshots match catalog-service's seed data. */
@Component
public class DataSeeder implements CommandLineRunner {

    private final PurchaseOrderRepository orders;

    public DataSeeder(PurchaseOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (orders.count() > 0) {
            return;
        }
        SupplierView globex = new SupplierView(2L, "Globex Packaging", null, null, 0, 14);
        SupplierView initech = new SupplierView(3L, "Initech Electronics", null, null, 0, 21);
        SupplierView acme = new SupplierView(1L, "Acme Components", null, null, 0, 7);

        PurchaseOrder cartons = new PurchaseOrder("PO-DEMO0001", new ProductView(null, "SKU-2001",
                "Corrugated shipping carton 40x30x30", "Packaging", new BigDecimal("0.85"), globex), "WH-WEST", 5000);
        cartons.setStatus(PurchaseOrder.Status.SHIPPED);
        cartons.setExpectedDelivery(LocalDate.now().plusDays(3));

        PurchaseOrder sensors = new PurchaseOrder("PO-DEMO0002", new ProductView(null, "SKU-3001",
                "Temperature sensor module", "Electronics", new BigDecimal("9.75"), initech), "WH-EAST", 300);
        sensors.setStatus(PurchaseOrder.Status.SHIPPED);
        sensors.setExpectedDelivery(LocalDate.now().minusDays(1));

        PurchaseOrder bolts = new PurchaseOrder("PO-DEMO0003", new ProductView(null, "SKU-1001",
                "Steel hex bolt M8 (box of 100)", "Hardware", new BigDecimal("12.50"), acme), "WH-WEST", 400);

        orders.save(cartons);
        orders.save(sensors);
        orders.save(bolts);
    }
}
