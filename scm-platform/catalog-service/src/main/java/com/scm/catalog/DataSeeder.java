package com.scm.catalog;

import com.scm.catalog.domain.Product;
import com.scm.catalog.domain.ProductRepository;
import com.scm.catalog.domain.Supplier;
import com.scm.catalog.domain.SupplierRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Demo catalog. The other services' seed data refers to these SKUs. */
@Component
public class DataSeeder implements CommandLineRunner {

    private final SupplierRepository suppliers;
    private final ProductRepository products;

    public DataSeeder(SupplierRepository suppliers, ProductRepository products) {
        this.suppliers = suppliers;
        this.products = products;
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

        products.save(new Product("SKU-1001", "Steel hex bolt M8 (box of 100)", "Hardware", new BigDecimal("12.50"), acme));
        products.save(new Product("SKU-1002", "Aluminium mounting bracket", "Hardware", new BigDecimal("4.20"), acme));
        products.save(new Product("SKU-2001", "Corrugated shipping carton 40x30x30", "Packaging", new BigDecimal("0.85"), globex));
        products.save(new Product("SKU-2002", "Stretch wrap roll 500m", "Packaging", new BigDecimal("18.00"), globex));
        products.save(new Product("SKU-3001", "Temperature sensor module", "Electronics", new BigDecimal("9.75"), initech));
        products.save(new Product("SKU-3002", "IoT gateway controller board", "Electronics", new BigDecimal("64.00"), initech));
    }
}
