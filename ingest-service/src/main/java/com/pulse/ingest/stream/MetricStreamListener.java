package com.pulse.ingest.stream;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

@Component
public class MetricStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(MetricStreamListener.class);

    private final StringRedisTemplate redisTemplate;
    private final StreamProperties properties;

    public MetricStreamListener(StringRedisTemplate redisTemplate, StreamProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        log.info("Received metric: {} {} value={} ts={}",
                fields.get("metric"),
                fields.get("sensor_id"),
                fields.get("value"),
                fields.get("timestamp"));

        redisTemplate.opsForStream().acknowledge(properties.key(), properties.group(), record.getId());
    }
}
