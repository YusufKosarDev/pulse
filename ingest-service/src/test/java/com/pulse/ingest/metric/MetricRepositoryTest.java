package com.pulse.ingest.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetricRepositoryTest {

    @Test
    void bucketSizeScalesWithRangeTowardsTargetPointCount() {
        // <= sensor cadence (2 s): served raw
        assertThat(MetricRepository.bucketSeconds(10)).isEqualTo(2);
        // longer ranges: ~300 points each
        assertThat(MetricRepository.bucketSeconds(60)).isEqualTo(12);
        assertThat(MetricRepository.bucketSeconds(360)).isEqualTo(72);
        assertThat(MetricRepository.bucketSeconds(1440)).isEqualTo(288);
    }
}
