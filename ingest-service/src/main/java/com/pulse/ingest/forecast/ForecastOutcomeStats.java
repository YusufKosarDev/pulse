package com.pulse.ingest.forecast;

public record ForecastOutcomeStats(
        int hits,
        int misses,
        int unwarned,
        Double hitRate,             // hits / (hits + misses); null before any graded episode
        Double avgAbsErrorMinutes,  // over hits
        Double avgLeadMinutes) {    // over hits
}
