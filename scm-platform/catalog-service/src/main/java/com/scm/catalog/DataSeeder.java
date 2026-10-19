package com.scm.catalog;

import com.scm.catalog.domain.Product;
import com.scm.catalog.domain.ProductRepository;
import com.scm.catalog.domain.Supplier;
import com.scm.catalog.domain.SupplierRepository;
import com.scm.common.catalog.TemperatureRange;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Demo catalog. The other services' seed data refers to these SKUs and supplier IDs. */
@Component
public class DataSeeder implements CommandLineRunner {

    private final SupplierRepository suppliers;
    private final ProductRepository products;

    public DataSeeder(SupplierRepository suppliers, ProductRepository products) {
        this.suppliers = suppliers;
        this.products = products;
    }

    @Override
    public void run(String... args) {
        // Products are written last, and the inserts are upserts on fixed keys: an interrupted seed reruns.
        if (products.count() > 0) {
            return;
        }
        Supplier acme = suppliers.save(new Supplier(1L, "Acme Components", "orders@acme.example", "USA", 4.6, 7));
        Supplier globex = suppliers.save(new Supplier(2L, "Globex Packaging", "supply@globex.example", "Germany", 4.1, 14));
        Supplier initech = suppliers.save(new Supplier(3L, "Initech Electronics", "sales@initech.example", "Taiwan", 3.8, 21));
        Supplier nordic = suppliers.save(new Supplier(4L, "Nordic Cold Foods", "orders@nordiccold.example", "Norway", 4.4, 5));

        products.save(new Product(1L, "SKU-1001", "Steel hex bolt M8 (box of 100)", "Hardware", new BigDecimal("12.50"), acme));
        products.save(new Product(2L, "SKU-1002", "Aluminium mounting bracket", "Hardware", new BigDecimal("4.20"), acme));
        products.save(new Product(3L, "SKU-2001", "Corrugated shipping carton 40x30x30", "Packaging", new BigDecimal("0.85"), globex));
        products.save(new Product(4L, "SKU-2002", "Stretch wrap roll 500m", "Packaging", new BigDecimal("18.00"), globex));
        products.save(new Product(5L, "SKU-3001", "Temperature sensor module", "Electronics", new BigDecimal("9.75"), initech));
        products.save(new Product(6L, "SKU-3002", "IoT gateway controller board", "Electronics", new BigDecimal("64.00"), initech));
        // Cold chain: temperature-controlled food.
        products.save(new Product(7L, "SKU-4001", "Frozen salmon fillets 10 kg", "Frozen food", new BigDecimal("89.00"), nordic,
                TemperatureRange.PROFILES.get("FROZEN")));
        products.save(new Product(8L, "SKU-4002", "Whole milk 12 x 1 L", "Dairy", new BigDecimal("14.40"), nordic,
                TemperatureRange.PROFILES.get("CHILLED")));
        products.save(new Product(9L, "SKU-4003", "Ice cream tubs 4 x 5 L", "Frozen food", new BigDecimal("46.00"), nordic,
                TemperatureRange.of("FROZEN", null, -25.0)));
    }
}
