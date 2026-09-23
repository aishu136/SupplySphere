package com.scm.inventory;

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
    public List<InventoryItemView> all() {
        return inventoryService.findAll();
    }

    @GetMapping("/low-stock")
    public List<InventoryItemView> lowStock() {
        return inventoryService.findLowStock();
    }

    @GetMapping("/{sku}")
    public List<InventoryItemView> bySku(@PathVariable String sku) {
        return inventoryService.findBySku(sku);
    }

    @PostMapping("/adjust")
    public InventoryItemView adjust(@Valid @RequestBody AdjustRequest req) {
        return inventoryService.adjust(req.sku(), req.warehouseCode(), req.delta(), req.reason());
    }
}
