package com.scm.core.web;

import com.scm.core.event.Alert;
import com.scm.core.service.AlertService;
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

    public record RaiseRequest(@NotBlank String type, @NotBlank String severity, @NotBlank String message, String entityId) {}

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<Alert> recent() {
        return alertService.recent();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return alertService.subscribe();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Alert raise(@Valid @RequestBody RaiseRequest req) {
        Alert alert = Alert.of(req.type(), req.severity(), req.message(), req.entityId());
        alertService.raise(alert);
        return alert;
    }
}
