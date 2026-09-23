package com.scm.alert;

import com.scm.common.event.Alert;
import com.scm.common.event.EventJson;
import com.scm.common.event.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AlertListener {

    private final AlertService alertService;
    private final EventJson json;

    public AlertListener(AlertService alertService, EventJson json) {
        this.alertService = alertService;
        this.json = json;
    }

    /**
     * A fresh group id per instance with earliest offsets: every replica sees every alert, and a
     * restarted instance rebuilds its recent-alerts view from the retained topic.
     */
    @KafkaListener(topics = Topics.ALERTS, groupId = "alert-service-${random.uuid}",
            properties = "auto.offset.reset=earliest")
    public void onAlert(String message) {
        alertService.raise(json.read(message, Alert.class));
    }
}
