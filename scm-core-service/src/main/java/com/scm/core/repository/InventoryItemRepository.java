package com.scm.core.repository;

import com.scm.core.domain.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findByProductSkuAndWarehouseCode(String sku, String warehouseCode);

    List<InventoryItem> findByProductSku(String sku);

    @Query("select i from InventoryItem i where i.quantity <= i.reorderPoint order by i.quantity asc")
    List<InventoryItem> findLowStock();
}
