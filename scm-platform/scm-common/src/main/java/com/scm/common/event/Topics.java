package com.scm.common.event;

public final class Topics {

    /** Compacted, keyed by SKU: latest product + supplier data (event-carried state transfer). */
    public static final String CATALOG = "scm.catalog.events";
    public static final String INVENTORY = "scm.inventory.events";
    public static final String ORDERS = "scm.order.events";
    public static final String SHIPMENTS = "scm.shipment.events";
    public static final String ALERTS = "scm.alerts";
    public static final String VISION = "scm.vision.events";

    private Topics() {}
}
