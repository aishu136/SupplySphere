package com.scm.core.config;

import com.scm.core.domain.*;
import com.scm.core.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/** Loads demo data on an empty database. */
@Component
public class DataSeeder implements CommandLineRunner {

    private final SupplierRepository suppliers;
    private final ProductRepository products;
    private final InventoryItemRepository inventory;
    private final PurchaseOrderRepository orders;
    private final ShipmentRepository shipments;

    public DataSeeder(SupplierRepository suppliers, ProductRepository products, InventoryItemRepository inventory,
                      PurchaseOrderRepository orders, ShipmentRepository shipments) {
        this.suppliers = suppliers;
        this.products = products;
        this.inventory = inventory;
        this.orders = orders;
        this.shipments = shipments;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (suppliers.count() > 0) {
            return;
        }
        Supplier acme = suppliers.save(new Supplier("Acme Components", "orders@acme.example", "USA", 4.6, 7));
        Supplier globex = suppliers.save(new Supplier("Globex Packaging", "supply@globex.example", "Germany", 4.1, 14));
        Supplier initech = suppliers.save(new Supplier("Initech Electronics", "sales@initech.example", "Taiwan", 3.8, 21));

        Product bolts = products.save(new Product("SKU-1001", "Steel hex bolt M8 (box of 100)", "Hardware", new BigDecimal("12.50"), acme));
        Product brackets = products.save(new Product("SKU-1002", "Aluminium mounting bracket", "Hardware", new BigDecimal("4.20"), acme));
        Product cartons = products.save(new Product("SKU-2001", "Corrugated shipping carton 40x30x30", "Packaging", new BigDecimal("0.85"), globex));
        Product wrap = products.save(new Product("SKU-2002", "Stretch wrap roll 500m", "Packaging", new BigDecimal("18.00"), globex));
        Product sensor = products.save(new Product("SKU-3001", "Temperature sensor module", "Electronics", new BigDecimal("9.75"), initech));
        Product board = products.save(new Product("SKU-3002", "IoT gateway controller board", "Electronics", new BigDecimal("64.00"), initech));

        inventory.save(new InventoryItem(bolts, "WH-EAST", 420, 150, 500));
        inventory.save(new InventoryItem(bolts, "WH-WEST", 90, 120, 400));
        inventory.save(new InventoryItem(brackets, "WH-EAST", 800, 200, 600));
        inventory.save(new InventoryItem(cartons, "WH-EAST", 2500, 1000, 5000));
        inventory.save(new InventoryItem(cartons, "WH-WEST", 600, 1000, 5000));
        inventory.save(new InventoryItem(wrap, "WH-WEST", 45, 30, 100));
        inventory.save(new InventoryItem(sensor, "WH-EAST", 38, 50, 300));
        inventory.save(new InventoryItem(board, "WH-EAST", 120, 40, 100));

        PurchaseOrder po1 = orders.save(new PurchaseOrder("PO-DEMO0001", cartons, "WH-WEST", 5000, LocalDate.now().plusDays(3)));
        po1.setStatus(PurchaseOrder.Status.SHIPPED);
        PurchaseOrder po2 = orders.save(new PurchaseOrder("PO-DEMO0002", sensor, "WH-EAST", 300, LocalDate.now().minusDays(1)));
        po2.setStatus(PurchaseOrder.Status.SHIPPED);
        orders.save(new PurchaseOrder("PO-DEMO0003", bolts, "WH-WEST", 400, LocalDate.now().plusDays(7)));

        Instant now = Instant.now();
        Shipment s1 = shipments.save(new Shipment("TRK-DEMO000001", po1, "DHL", "Hamburg, DE", "Reno, NV", now.plus(Duration.ofDays(3))));
        s1.setStatus(Shipment.Status.IN_TRANSIT);
        Shipment s2 = shipments.save(new Shipment("TRK-DEMO000002", po2, "Maersk", "Kaohsiung, TW", "Newark, NJ", now.minus(Duration.ofHours(30))));
        s2.setStatus(Shipment.Status.IN_TRANSIT);
        shipments.save(new Shipment("TRK-DEMO000003", null, "UPS", "Newark, NJ", "Boston, MA", now.plus(Duration.ofDays(1))));
    }
}
