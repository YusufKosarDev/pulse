package com.pulse.ingest.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pulse.ingest.metric.MetricPoint;
import com.pulse.ingest.metric.MetricRepository;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private static final int MAX_MINUTES = 1440;

    private final MetricRepository metricRepository;

    public MetricsController(MetricRepository metricRepository) {
        this.metricRepository = metricRepository;
    }

    @GetMapping("/recent")
    public List<MetricPoint> recent(
            @RequestParam String metric,
            @RequestParam(defaultValue = "10") int minutes) {
        int clamped = Math.max(1, Math.min(minutes, MAX_MINUTES));
        return metricRepository.findRecent(metric, clamped);
    }

    @GetMapping("/names")
    public List<String> names() {
        return metricRepository.findMetricNames();
    }
}
