package com.scm.core.service;

import com.scm.core.config.ScmProperties;
import com.scm.core.domain.PurchaseOrder;
import com.scm.core.domain.Shipment;
import com.scm.core.event.Alert;
import com.scm.core.event.EventPublisher;
import com.scm.core.event.SupplyChainEvent;
import com.scm.core.repository.ShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ShipmentService {

    private final ShipmentRepository shipments;
    private final PurchaseOrderService orderService;
    private final AlertService alerts;
    private final EventPublisher events;
    private final ScmProperties props;

    public ShipmentService(ShipmentRepository shipments, PurchaseOrderService orderService,
                           AlertService alerts, EventPublisher events, ScmProperties props) {
        this.shipments = shipments;
        this.orderService = orderService;
        this.alerts = alerts;
        this.events = events;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public List<Shipment> findAll() {
        return shipments.findAllByOrderByEtaAsc();
    }

    @Transactional(readOnly = true)
    public List<Shipment> findDelayed() {
        return shipments.findDelayed(Instant.now());
    }

    @Transactional(readOnly = true)
    public Shipment get(String trackingNumber) {
        return shipments.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new NotFoundException("Shipment " + trackingNumber + " not found"));
    }

    @Transactional
    public Shipment create(String orderNumber, String carrier, String origin, String destination, Instant eta) {
        PurchaseOrder po = orderNumber == null || orderNumber.isBlank() ? null : orderService.get(orderNumber);
        String tracking = "TRK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        Shipment s = shipments.save(new Shipment(tracking, po, carrier, origin, destination, eta));
        publish("SHIPMENT_CREATED", s);
        return s;
    }

    @Transactional
    public Shipment updateStatus(String trackingNumber, Shipment.Status status, Instant newEta) {
        Shipment s = get(trackingNumber);
        s.setStatus(status);
        if (newEta != null) {
            s.setEta(newEta);
        }
        PurchaseOrder po = s.getPurchaseOrder();
        if (po != null && status == Shipment.Status.IN_TRANSIT
                && (po.getStatus() == PurchaseOrder.Status.CREATED || po.getStatus() == PurchaseOrder.Status.APPROVED)) {
            orderService.updateStatus(po.getOrderNumber(), PurchaseOrder.Status.SHIPPED);
        }
        if (po != null && status == Shipment.Status.DELIVERED
                && po.getStatus() != PurchaseOrder.Status.RECEIVED && po.getStatus() != PurchaseOrder.Status.CANCELLED) {
            orderService.updateStatus(po.getOrderNumber(), PurchaseOrder.Status.RECEIVED);
        }
        publish("SHIPMENT_" + status.name(), s);
        return s;
    }

    /** Invoked by the Camel vision route when computer vision flags a shipment as damaged. */
    @Transactional
    public void handleDamagedInspection(SupplyChainEvent inspection) {
        Shipment s = get(inspection.entityId());
        s.setStatus(Shipment.Status.DAMAGED);
        s.setInspectionNotes(String.valueOf(inspection.data().getOrDefault("summary", "Damage detected")));
        publish("SHIPMENT_DAMAGED", s);
        alerts.raise(Alert.of("SHIPMENT_DAMAGED", "CRITICAL",
                "Vision inspection flagged damage on " + s.getTrackingNumber() + ": " + s.getInspectionNotes(),
                s.getTrackingNumber()));
    }

    @Transactional
    public void handleCleanInspection(SupplyChainEvent inspection) {
        Shipment s = get(inspection.entityId());
        s.setInspectionNotes(String.valueOf(inspection.data().getOrDefault("summary", "Passed visual inspection")));
    }

    private void publish(String type, Shipment s) {
        Map<String, Object> data = new HashMap<>();
        data.put("trackingNumber", s.getTrackingNumber());
        data.put("status", s.getStatus().name());
        data.put("carrier", s.getCarrier());
        data.put("destination", s.getDestination());
        data.put("eta", s.getEta() == null ? null : s.getEta().toString());
        data.put("orderNumber", s.getPurchaseOrder() == null ? null : s.getPurchaseOrder().getOrderNumber());
        events.publish(props.topics().shipments(), SupplyChainEvent.of(type, s.getTrackingNumber(), data));
    }
}
