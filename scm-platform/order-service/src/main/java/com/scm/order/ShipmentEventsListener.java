package com.scm.order;

import com.scm.common.event.EventJson;
import com.scm.common.event.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ShipmentEventsListener {

    private final OrderService orderService;
    private final EventJson json;

    public ShipmentEventsListener(OrderService orderService, EventJson json) {
        this.orderService = orderService;
        this.json = json;
    }

    @KafkaListener(topics = Topics.SHIPMENTS, groupId = "order-service")
    public void onShipmentEvent(String message) {
        orderService.onShipmentEvent(json.event(message));
    }
}
