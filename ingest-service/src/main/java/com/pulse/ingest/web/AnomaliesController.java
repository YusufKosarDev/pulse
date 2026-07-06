package com.pulse.ingest.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pulse.ingest.anomaly.Anomaly;
import com.pulse.ingest.anomaly.AnomalyRepository;

@RestController
@RequestMapping("/api/anomalies")
public class AnomaliesController {

    private static final int MAX_MINUTES = 1440;
    private static final int MAX_LIMIT = 100;

    private final AnomalyRepository anomalyRepository;

    public AnomaliesController(AnomalyRepository anomalyRepository) {
        this.anomalyRepository = anomalyRepository;
    }

    @GetMapping("/recent")
    public List<Anomaly> recent(
            @RequestParam String metric,
            @RequestParam(defaultValue = "10") int minutes) {
        int clamped = Math.max(1, Math.min(minutes, MAX_MINUTES));
        return anomalyRepository.findRecent(metric, clamped);
    }

    @GetMapping("/latest")
    public List<Anomaly> latest(@RequestParam(defaultValue = "20") int limit) {
        int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return anomalyRepository.findLatest(clamped);
    }
}
