package com.scm.order;

import com.scm.order.domain.PurchaseOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrderRequest(@NotBlank String sku, @NotBlank String warehouseCode, @Positive int quantity) {}

    public record StatusRequest(@NotNull PurchaseOrder.Status status) {}

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<PurchaseOrderView> all() {
        return orderService.findAll();
    }

    @GetMapping("/{orderNumber}")
    public PurchaseOrderView get(@PathVariable String orderNumber) {
        return orderService.get(orderNumber);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderView create(@Valid @RequestBody CreateOrderRequest req) {
        return orderService.create(req.sku(), req.warehouseCode(), req.quantity());
    }

    @PatchMapping("/{orderNumber}/status")
    public PurchaseOrderView updateStatus(@PathVariable String orderNumber, @Valid @RequestBody StatusRequest req) {
        return orderService.updateStatus(orderNumber, req.status());
    }
}
