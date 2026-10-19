package com.scm.inventory.domain;

import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryItemRepository extends CassandraRepository<InventoryItem, MapId> {

    Optional<InventoryItem> findBySkuAndWarehouseCode(String sku, String warehouseCode);

    /** One partition, in warehouse order. */
    List<InventoryItem> findBySku(String sku);
}
