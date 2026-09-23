package com.scm.shipment.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "shipments")
public class Shipment {

    public enum Status { CREATED, IN_TRANSIT, DELAYED, DELIVERED, DAMAGED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String trackingNumber;

    /** Reference by business key to order-service's purchase order (no cross-service foreign key). */
    private String orderNumber;

    private String carrier;
    private String origin;
    private String destination;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant eta;
    private Instant lastUpdated;

    @Column(length = 2000)
    private String inspectionNotes;

    protected Shipment() {}

    public Shipment(String trackingNumber, String orderNumber, String carrier, String origin, String destination,
                    Instant eta) {
        this.trackingNumber = trackingNumber;
        this.orderNumber = orderNumber;
        this.carrier = carrier;
        this.origin = origin;
        this.destination = destination;
        this.eta = eta;
        this.status = Status.CREATED;
        this.lastUpdated = Instant.now();
    }

    public void setStatus(Status status) {
        this.status = status;
        this.lastUpdated = Instant.now();
    }

    public void setEta(Instant eta) {
        this.eta = eta;
        this.lastUpdated = Instant.now();
    }

    public void setInspectionNotes(String inspectionNotes) {
        this.inspectionNotes = inspectionNotes;
        this.lastUpdated = Instant.now();
    }

    public Long getId() { return id; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getOrderNumber() { return orderNumber; }
    public String getCarrier() { return carrier; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public Status getStatus() { return status; }
    public Instant getEta() { return eta; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getInspectionNotes() { return inspectionNotes; }
}
