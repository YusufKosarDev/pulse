package com.pulse.ingest.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pulse.ingest.forecast.ForecastOutcome;
import com.pulse.ingest.forecast.ForecastOutcomeRepository;
import com.pulse.ingest.forecast.ForecastOutcomeStats;

@RestController
@RequestMapping("/api/forecast-outcomes")
public class ForecastOutcomesController {

    private static final int MAX_LIMIT = 100;
    private static final int MAX_HOURS = 24 * 30;

    private final ForecastOutcomeRepository outcomeRepository;

    public ForecastOutcomesController(ForecastOutcomeRepository outcomeRepository) {
        this.outcomeRepository = outcomeRepository;
    }

    @GetMapping
    public List<ForecastOutcome> latest(@RequestParam(defaultValue = "20") int limit) {
        int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return outcomeRepository.findLatest(clamped);
    }

    @GetMapping("/stats")
    public ForecastOutcomeStats stats(@RequestParam(defaultValue = "24") int hours) {
        int clamped = Math.max(1, Math.min(hours, MAX_HOURS));
        return outcomeRepository.stats(clamped);
    }
}
