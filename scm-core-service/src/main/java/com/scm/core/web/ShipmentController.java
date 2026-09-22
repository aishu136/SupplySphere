package com.scm.core.web;

import com.scm.core.domain.Shipment;
import com.scm.core.service.ShipmentService;
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
    public List<Shipment> all() {
        return shipmentService.findAll();
    }

    @GetMapping("/delayed")
    public List<Shipment> delayed() {
        return shipmentService.findDelayed();
    }

    @GetMapping("/{trackingNumber}")
    public Shipment get(@PathVariable String trackingNumber) {
        return shipmentService.get(trackingNumber);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Shipment create(@Valid @RequestBody CreateShipmentRequest req) {
        return shipmentService.create(req.orderNumber(), req.carrier(), req.origin(), req.destination(), req.eta());
    }

    @PatchMapping("/{trackingNumber}/status")
    public Shipment updateStatus(@PathVariable String trackingNumber, @Valid @RequestBody StatusRequest req) {
        return shipmentService.updateStatus(trackingNumber, req.status(), req.eta());
    }
}
