package com.scm.core.web;

import com.scm.core.domain.InventoryItem;
import com.scm.core.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    public record AdjustRequest(@NotBlank String sku, @NotBlank String warehouseCode, int delta, String reason) {}

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public List<InventoryItem> all() {
        return inventoryService.findAll();
    }

    @GetMapping("/low-stock")
    public List<InventoryItem> lowStock() {
        return inventoryService.findLowStock();
    }

    @GetMapping("/{sku}")
    public List<InventoryItem> bySku(@PathVariable String sku) {
        return inventoryService.findBySku(sku);
    }

    @PostMapping("/adjust")
    public InventoryItem adjust(@Valid @RequestBody AdjustRequest req) {
        return inventoryService.adjust(req.sku(), req.warehouseCode(), req.delta(), req.reason());
    }
}
