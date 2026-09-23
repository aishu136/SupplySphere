package com.scm.alert;

import com.scm.common.event.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the most recent alerts in memory and pushes new ones to connected UIs over SSE.
 * The durable record is the scm.alerts topic (7-day retention); on restart this service
 * replays it, so the in-memory view is rebuilt rather than lost.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final int MAX_ALERTS = 200;

    private final Deque<Alert> recent = new ArrayDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public void raise(Alert alert) {
        log.warn("ALERT [{}] {} - {}", alert.severity(), alert.type(), alert.message());
        synchronized (recent) {
            if (recent.stream().anyMatch(a -> a.alertId().equals(alert.alertId()))) {
                return; // redelivered
            }
            recent.addFirst(alert);
            while (recent.size() > MAX_ALERTS) {
                recent.removeLast();
            }
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("alert").data(alert));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    public List<Alert> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }
}
