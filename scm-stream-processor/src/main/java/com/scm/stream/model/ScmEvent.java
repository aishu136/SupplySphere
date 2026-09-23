package com.scm.stream.model;

/**
 * The fields of the services' SupplyChainEvent envelope (and its data map) that the detectors use,
 * flattened. Public fields of basic types make it a Flink POJO with native serializers only: a
 * Map field would fall back to Kryo, which is slow and broke on JDK 17+ module restrictions.
 */
public class ScmEvent {

    public String eventId;
    public String type;
    public String source;
    public String entityId;
    public String timestamp;

    // From data: inventory and order events
    public String sku;
    public String warehouseCode;
    public Integer quantity;
    public Integer reorderPoint;

    // From data: shipment events
    public String status;
    public String eta;
    public String carrier;
    public String destination;

    public ScmEvent() {}

    public int quantity() {
        return required(quantity, "quantity");
    }

    public int reorderPoint() {
        return required(reorderPoint, "reorderPoint");
    }

    private int required(Integer value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Event " + type + " (" + entityId + ") has no " + field);
        }
        return value;
    }

    @Override
    public String toString() {
        return type + "(" + entityId + ")";
    }
}
