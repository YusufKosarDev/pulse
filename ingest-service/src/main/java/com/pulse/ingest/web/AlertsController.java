package com.pulse.ingest.web;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.ingest.alert.Alert;
import com.pulse.ingest.alert.AlertRepository;
import com.pulse.ingest.event.EventBroadcaster;
import com.pulse.ingest.event.WebhookNotifier;

@RestController
@RequestMapping("/api/alerts")
public class AlertsController {

    private static final Logger log = LoggerFactory.getLogger(AlertsController.class);
    private static final int MAX_LIMIT = 100;

    private final AlertRepository alertRepository;
    private final EventBroadcaster broadcaster;
    private final WebhookNotifier webhookNotifier;
    private final ObjectMapper objectMapper;

    public AlertsController(AlertRepository alertRepository,
                            EventBroadcaster broadcaster,
                            WebhookNotifier webhookNotifier,
                            ObjectMapper objectMapper) {
        this.alertRepository = alertRepository;
        this.broadcaster = broadcaster;
        this.webhookNotifier = webhookNotifier;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Alert> latest(@RequestParam(defaultValue = "20") int limit) {
        int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return alertRepository.findLatest(clamped);
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Void> acknowledge(@PathVariable long id) {
        // 409 covers both an unknown id and a transition that no longer
        // applies (e.g. already acknowledged); the client just refetches.
        if (!alertRepository.acknowledge(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        broadcaster.send("alerts-changed", "{}");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable long id) {
        if (!alertRepository.resolve(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        broadcaster.send("alerts-changed", "{}");
        // Auto-resolves are notified by ml-service; manual ones originate here.
        if (webhookNotifier.enabled()) {
            alertRepository.findById(id).ifPresent(this::notifyManualResolve);
        }
        return ResponseEntity.noContent().build();
    }

    private void notifyManualResolve(Alert alert) {
        try {
            webhookNotifier.notify(objectMapper.writeValueAsString(
                    Map.of("type", "alert-resolved", "reason", "manual", "alert", alert)));
        } catch (Exception e) {
            log.warn("Could not serialize webhook payload for alert {}: {}", alert.id(), e.getMessage());
        }
    }
}
