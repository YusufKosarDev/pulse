package com.pulse.ingest.metric;

import java.time.Instant;

public record MetricPoint(Instant time, double value) {
}
