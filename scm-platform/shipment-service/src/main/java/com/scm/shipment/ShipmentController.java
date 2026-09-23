package com.scm.shipment;

import com.scm.shipment.domain.Shipment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    public record CreateShipmentRequest(String orderNumber, @NotBlank String carrier, @NotBlank String origin,
                                        @NotBlank String destination, @NotNull Instant eta) {}

    public record StatusRequest(@NotNull Shipment.Status status, Instant eta) {}

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @GetMapping
    public List<ShipmentView> all() {
        return shipmentService.findAll();
    }

    @GetMapping("/delayed")
    public List<ShipmentView> delayed() {
        return shipmentService.findDelayed();
    }

    @GetMapping("/{trackingNumber}")
    public ShipmentView get(@PathVariable String trackingNumber) {
        return shipmentService.get(trackingNumber);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShipmentView create(@Valid @RequestBody CreateShipmentRequest req) {
        return shipmentService.create(req.orderNumber(), req.carrier(), req.origin(), req.destination(), req.eta());
    }

    @PatchMapping("/{trackingNumber}/status")
    public ShipmentView updateStatus(@PathVariable String trackingNumber, @Valid @RequestBody StatusRequest req) {
        return shipmentService.updateStatus(trackingNumber, req.status(), req.eta());
    }
}
