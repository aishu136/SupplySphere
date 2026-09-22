package com.scm.core.web;

import com.scm.core.domain.PurchaseOrder;
import com.scm.core.service.PurchaseOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class PurchaseOrderController {

    public record CreateOrderRequest(@NotBlank String sku, @NotBlank String warehouseCode, @Positive int quantity) {}

    public record StatusRequest(@NotNull PurchaseOrder.Status status) {}

    private final PurchaseOrderService orderService;

    public PurchaseOrderController(PurchaseOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<PurchaseOrder> all() {
        return orderService.findAll();
    }

    @GetMapping("/{orderNumber}")
    public PurchaseOrder get(@PathVariable String orderNumber) {
        return orderService.get(orderNumber);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrder create(@Valid @RequestBody CreateOrderRequest req) {
        return orderService.create(req.sku(), req.warehouseCode(), req.quantity());
    }

    @PatchMapping("/{orderNumber}/status")
    public PurchaseOrder updateStatus(@PathVariable String orderNumber, @Valid @RequestBody StatusRequest req) {
        return orderService.updateStatus(orderNumber, req.status());
    }
}
