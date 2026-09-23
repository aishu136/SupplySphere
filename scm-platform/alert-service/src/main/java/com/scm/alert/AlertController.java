package com.scm.alert;

import com.scm.common.event.Alert;
import com.scm.common.event.EventPublisher;
import com.scm.common.event.Topics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    public record RaiseRequest(@NotBlank String type, @NotBlank String severity, @NotBlank String message,
                               String entityId) {}

    private final AlertService alertService;
    private final EventPublisher events;

    public AlertController(AlertService alertService, EventPublisher events) {
        this.alertService = alertService;
        this.events = events;
    }

    @GetMapping
    public List<Alert> recent() {
        return alertService.recent();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return alertService.subscribe();
    }

    /** Raised alerts go through the topic too, so every alert-service replica shows them. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Alert raise(@Valid @RequestBody RaiseRequest req) {
        Alert alert = Alert.of("api", req.type(), req.severity(), req.message(), req.entityId());
        events.publish(Topics.ALERTS, req.entityId(), alert);
        return alert;
    }
}
