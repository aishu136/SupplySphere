package com.scm.inventory;

import com.scm.inventory.domain.InventoryItem;
import com.scm.inventory.domain.InventoryItemRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo stock positions for the SKUs seeded by catalog-service. */
@Component
public class DataSeeder implements CommandLineRunner {

    private final InventoryItemRepository inventory;

    public DataSeeder(InventoryItemRepository inventory) {
        this.inventory = inventory;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (inventory.count() > 0) {
            return;
        }
        inventory.save(new InventoryItem("SKU-1001", "WH-EAST", 420, 150, 500));
        inventory.save(new InventoryItem("SKU-1001", "WH-WEST", 90, 120, 400));
        inventory.save(new InventoryItem("SKU-1002", "WH-EAST", 800, 200, 600));
        inventory.save(new InventoryItem("SKU-2001", "WH-EAST", 2500, 1000, 5000));
        inventory.save(new InventoryItem("SKU-2001", "WH-WEST", 600, 1000, 5000));
        inventory.save(new InventoryItem("SKU-2002", "WH-WEST", 45, 30, 100));
        inventory.save(new InventoryItem("SKU-3001", "WH-EAST", 38, 50, 300));
        inventory.save(new InventoryItem("SKU-3002", "WH-EAST", 120, 40, 100));
    }
}
