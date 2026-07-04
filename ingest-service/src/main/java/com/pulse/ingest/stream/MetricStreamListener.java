package com.pulse.ingest.stream;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import com.pulse.ingest.metric.MetricRepository;

@Component
public class MetricStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(MetricStreamListener.class);

    private final StringRedisTemplate redisTemplate;
    private final StreamProperties properties;
    private final MetricRepository metricRepository;

    public MetricStreamListener(StringRedisTemplate redisTemplate,
                                StreamProperties properties,
                                MetricRepository metricRepository) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metricRepository = metricRepository;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        String metric = fields.get("metric");
        String sensorId = fields.get("sensor_id");
        String value = fields.get("value");
        String timestamp = fields.get("timestamp");

        log.info("Received metric: {} {} value={} ts={}", metric, sensorId, value, timestamp);

        try {
            metricRepository.insert(
                    Instant.ofEpochMilli(Long.parseLong(timestamp)),
                    metric,
                    sensorId,
                    Double.parseDouble(value));
        } catch (Exception e) {
            // Leave the entry pending (no ack) so it can be reclaimed once the issue is fixed.
            log.error("Failed to persist metric {} ({}): {}", record.getId(), metric, e.getMessage());
            return;
        }

        redisTemplate.opsForStream().acknowledge(properties.key(), properties.group(), record.getId());
    }
}
