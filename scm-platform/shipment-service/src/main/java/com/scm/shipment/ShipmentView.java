package com.scm.shipment;

import com.scm.shipment.domain.Shipment;

import java.time.Instant;

/** API shape: purchaseOrder stays an object ({orderNumber}) so existing clients keep working. */
public record ShipmentView(Long id, String trackingNumber, OrderRef purchaseOrder, String carrier, String origin,
                           String destination, Shipment.Status status, Instant eta, Instant lastUpdated,
                           String inspectionNotes) {

    public record OrderRef(String orderNumber) {}

    public static ShipmentView of(Shipment s) {
        return new ShipmentView(s.getId(), s.getTrackingNumber(),
                s.getOrderNumber() == null ? null : new OrderRef(s.getOrderNumber()),
                s.getCarrier(), s.getOrigin(), s.getDestination(), s.getStatus(), s.getEta(), s.getLastUpdated(),
                s.getInspectionNotes());
    }
}
