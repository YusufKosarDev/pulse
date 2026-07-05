package com.pulse.ingest.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pulse.ingest.forecast.ForecastRepository;
import com.pulse.ingest.forecast.ForecastSeries;
import com.pulse.ingest.forecast.PredictedAlert;

@RestController
public class ForecastsController {

    private final ForecastRepository forecastRepository;

    public ForecastsController(ForecastRepository forecastRepository) {
        this.forecastRepository = forecastRepository;
    }

    @GetMapping("/api/forecasts")
    public ForecastSeries forecast(@RequestParam String metric) {
        return forecastRepository.findByMetric(metric);
    }

    @GetMapping("/api/predicted-alerts")
    public List<PredictedAlert> predictedAlerts() {
        return forecastRepository.findActiveAlerts();
    }
}
