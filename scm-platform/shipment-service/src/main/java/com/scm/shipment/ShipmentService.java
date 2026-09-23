package com.scm.shipment;

import com.scm.common.event.Alert;
import com.scm.common.event.EventPublisher;
import com.scm.common.event.SupplyChainEvent;
import com.scm.common.event.Topics;
import com.scm.common.web.NotFoundException;
import com.scm.shipment.domain.Shipment;
import com.scm.shipment.domain.ShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ShipmentService {

    private static final String SOURCE = "shipment-service";

    private final ShipmentRepository shipments;
    private final OrderClient orders;
    private final EventPublisher events;

    public ShipmentService(ShipmentRepository shipments, OrderClient orders, EventPublisher events) {
        this.shipments = shipments;
        this.orders = orders;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<ShipmentView> findAll() {
        return shipments.findAllByOrderByEtaAsc().stream().map(ShipmentView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<ShipmentView> findDelayed() {
        return shipments.findDelayed(Instant.now()).stream().map(ShipmentView::of).toList();
    }

    @Transactional(readOnly = true)
    public ShipmentView get(String trackingNumber) {
        return ShipmentView.of(find(trackingNumber));
    }

    public ShipmentView create(String orderNumber, String carrier, String origin, String destination, Instant eta) {
        boolean linked = orderNumber != null && !orderNumber.isBlank();
        if (linked) {
            orders.requireOrder(orderNumber); // remote check, outside the DB transaction
        }
        return save(new Shipment("TRK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase(),
                linked ? orderNumber : null, carrier, origin, destination, eta));
    }

    @Transactional
    public ShipmentView save(Shipment shipment) {
        Shipment saved = shipments.save(shipment);
        publish("SHIPMENT_CREATED", saved);
        return ShipmentView.of(saved);
    }

    /** IN_TRANSIT and DELIVERED events drive the order -> inventory saga downstream. */
    @Transactional
    public ShipmentView updateStatus(String trackingNumber, Shipment.Status status, Instant newEta) {
        Shipment s = find(trackingNumber);
        s.setStatus(status);
        if (newEta != null) {
            s.setEta(newEta);
        }
        publish("SHIPMENT_" + status.name(), s);
        return ShipmentView.of(s);
    }

    /** Called by the Camel vision route when computer vision flags damage. */
    @Transactional
    public void handleDamagedInspection(SupplyChainEvent inspection) {
        Shipment s = find(inspection.entityId());
        s.setStatus(Shipment.Status.DAMAGED);
        s.setInspectionNotes(String.valueOf(inspection.data().getOrDefault("summary", "Damage detected")));
        publish("SHIPMENT_DAMAGED", s);
        events.publish(Topics.ALERTS, s.getTrackingNumber(), Alert.of(SOURCE, "SHIPMENT_DAMAGED", "CRITICAL",
                "Vision inspection flagged damage on " + s.getTrackingNumber() + ": " + s.getInspectionNotes(),
                s.getTrackingNumber()));
    }

    @Transactional
    public void handleCleanInspection(SupplyChainEvent inspection) {
        find(inspection.entityId()).setInspectionNotes(
                String.valueOf(inspection.data().getOrDefault("summary", "Passed visual inspection")));
    }

    private Shipment find(String trackingNumber) {
        return shipments.findByTrackingNumber(trackingNumber)
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
