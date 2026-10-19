package com.scm.shipment;

import com.scm.common.event.Alert;
import com.scm.common.event.EventPublisher;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import com.scm.common.web.NotFoundException;
import com.scm.shipment.domain.Shipment;
import com.scm.shipment.domain.ShipmentRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ShipmentService {

    private static final String SOURCE = "shipment-service";

    /** Sorted here: the table is partitioned by tracking number, which has no order of its own. */
    private static final Comparator<Shipment> BY_ETA =
            Comparator.comparing(Shipment::getEta, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ShipmentRepository shipments;
    private final OrderClient orders;
    private final EventPublisher events;

    public ShipmentService(ShipmentRepository shipments, OrderClient orders, EventPublisher events) {
        this.shipments = shipments;
        this.orders = orders;
        this.events = events;
    }

    public List<ShipmentView> findAll() {
        return shipments.findAll().stream().sorted(BY_ETA).map(ShipmentView::of).toList();
    }

    public List<ShipmentView> findDelayed() {
        Instant now = Instant.now();
        return shipments.findAll().stream().filter(s -> s.isOverdue(now)).sorted(BY_ETA).map(ShipmentView::of).toList();
    }

    public ShipmentView get(String trackingNumber) {
        return ShipmentView.of(find(trackingNumber));
    }

    public ShipmentView create(String orderNumber, String carrier, String origin, String destination, Instant eta) {
        boolean linked = orderNumber != null && !orderNumber.isBlank();
        if (linked) {
            orders.requireOrder(orderNumber);
        }
        return book(new Shipment("TRK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase(),
                linked ? orderNumber : null, carrier, origin, destination, eta));
    }

    private ShipmentView book(Shipment shipment) {
        shipments.save(shipment);
        publish("SHIPMENT_CREATED", shipment);
        return ShipmentView.of(shipment);
    }

    /** IN_TRANSIT and DELIVERED events drive the order -> inventory saga downstream. */
    public ShipmentView updateStatus(String trackingNumber, Shipment.Status status, Instant newEta) {
        Shipment s = find(trackingNumber);
        s.setStatus(status);
        if (newEta != null) {
            s.setEta(newEta);
        }
        shipments.save(s);
        publish("SHIPMENT_" + status.name(), s);
        return ShipmentView.of(s);
    }

    /**
     * Called by the Camel vision route when computer vision flags damage or a cold-chain breach.
     * Either way the goods are quarantined (DAMAGED), which also closes the shipment's ETA timer in Flink.
     */
    public void handleDamagedInspection(SupplyChainEvent inspection) {
        boolean coldChain = "COLD_CHAIN_INSPECTED".equals(inspection.type());
        Shipment s = find(inspection.entityId());
        s.setStatus(Shipment.Status.DAMAGED);
        s.setInspectionNotes(String.valueOf(inspection.data().getOrDefault("summary",
                coldChain ? "Cold-chain breach" : "Damage detected")));
        shipments.save(s);
        publish("SHIPMENT_DAMAGED", s);
        events.publish(Topics.ALERTS, s.getTrackingNumber(), coldChain
                ? Alert.of(SOURCE, "COLD_CHAIN_BREACH", "CRITICAL",
                        "Cold-chain inspection of " + s.getTrackingNumber() + ": " + s.getInspectionNotes(),
                        s.getTrackingNumber())
                : Alert.of(SOURCE, "SHIPMENT_DAMAGED", "CRITICAL",
                        "Vision inspection flagged damage on " + s.getTrackingNumber() + ": " + s.getInspectionNotes(),
                        s.getTrackingNumber()));
    }

    public void handleCleanInspection(SupplyChainEvent inspection) {
        Shipment s = find(inspection.entityId());
        s.setInspectionNotes(String.valueOf(inspection.data().getOrDefault("summary", "Passed visual inspection")));
        shipments.save(s);
    }

    private Shipment find(String trackingNumber) {
        return shipments.findById(trackingNumber)
                .orElseThrow(() -> new NotFoundException("Shipment " + trackingNumber + " not found"));
    }

    private void publish(String type, Shipment s) {
        Map<String, Object> data = new HashMap<>();
        data.put("trackingNumber", s.getTrackingNumber());
        data.put("status", s.getStatus().name());
        data.put("carrier", s.getCarrier());
        data.put("destination", s.getDestination());
        data.put("eta", s.getEta() == null ? null : s.getEta().toString());
        data.put("orderNumber", s.getOrderNumber());
        events.publish(Topics.SHIPMENTS, SupplyChainEvent.of(SOURCE, type, s.getTrackingNumber(), data));
    }
}
