package com.scm.stream.model;

import java.time.Instant;
import java.util.UUID;

/** Mirrors com.scm.core.event.Alert. */
public class AlertEvent {

    public String alertId;
    public String type;
    public String severity;
    public String message;
    public String entityId;
    public String timestamp;
    public String source;

    public AlertEvent() {}

    public static AlertEvent of(String type, String severity, String message, String entityId) {
        AlertEvent a = new AlertEvent();
        a.alertId = UUID.randomUUID().toString();
        a.type = type;
        a.severity = severity;
        a.message = message;
        a.entityId = entityId;
        a.timestamp = Instant.now().toString();
        a.source = "scm-stream-processor";
        return a;
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + type + ": " + message;
    }
}
