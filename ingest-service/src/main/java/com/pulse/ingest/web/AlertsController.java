package com.pulse.ingest.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pulse.ingest.alert.Alert;
import com.pulse.ingest.alert.AlertRepository;

@RestController
@RequestMapping("/api/alerts")
public class AlertsController {

    private static final int MAX_LIMIT = 100;

    private final AlertRepository alertRepository;

    public AlertsController(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
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
        return alertRepository.acknowledge(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable long id) {
        return alertRepository.resolve(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
