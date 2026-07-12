package com.pulse.ingest.forecast;

public record ForecastOutcomeStats(
        int hits,
        int misses,
        int unwarned,
        Double hitRate,                // hits / (hits + misses); null before any graded episode
        Double medianAbsErrorMinutes,  // over hits; robust to long-lived outlier episodes
        Double medianLeadMinutes,      // over hits
        Double avgAbsErrorMinutes,     // over hits
        Double avgLeadMinutes) {       // over hits
}
