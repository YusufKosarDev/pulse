package com.pulse.ingest.stream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-claims stream entries that were delivered to a consumer but
 * never acknowledged (e.g. the consumer crashed mid-processing), so they are
 * not stuck in the pending list forever. Entries that keep failing are moved
 * to a dead-letter stream after {@code maxDeliveries} attempts.
 */
@Component
public class PendingReclaimer {

    private static final Logger log = LoggerFactory.getLogger(PendingReclaimer.class);
    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate redisTemplate;
    private final StreamProperties properties;
    private final MetricStreamListener listener;
    private final Duration minIdle;
    private final long maxDeliveries;
    private final String dlqKey;

    public PendingReclaimer(StringRedisTemplate redisTemplate,
                            StreamProperties properties,
                            MetricStreamListener listener,
                            @Value("${pulse.stream.reclaim.min-idle-ms}") long minIdleMs,
                            @Value("${pulse.stream.reclaim.max-deliveries}") long maxDeliveries,
                            @Value("${pulse.stream.dlq-key}") String dlqKey) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.listener = listener;
        this.minIdle = Duration.ofMillis(minIdleMs);
        this.maxDeliveries = maxDeliveries;
        this.dlqKey = dlqKey;
    }

    @Scheduled(fixedDelayString = "${pulse.stream.reclaim.interval-ms}")
    public void reclaim() {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();

        PendingMessages pending;
        try {
            pending = ops.pending(properties.key(), properties.group(), Range.unbounded(), BATCH_SIZE);
        } catch (Exception e) {
            log.warn("Could not read pending entries: {}", e.getMessage());
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }

        Map<RecordId, Long> deliveryCounts = new HashMap<>();
        List<RecordId> claimable = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) < 0) {
                continue;
            }
            deliveryCounts.put(message.getId(), message.getTotalDeliveryCount());
            claimable.add(message.getId());
        }
        if (claimable.isEmpty()) {
            return;
        }

        List<MapRecord<String, String, String>> claimed = ops.claim(
                properties.key(), properties.group(), properties.consumer(),
                minIdle, claimable.toArray(RecordId[]::new));

        for (MapRecord<String, String, String> record : claimed) {
            long deliveries = deliveryCounts.getOrDefault(record.getId(), 1L);
            if (deliveries >= maxDeliveries) {
                deadLetter(ops, record, deliveries);
            } else {
                log.info("Reclaimed pending entry {} (delivery #{})", record.getId(), deliveries + 1);
                listener.onMessage(record);
            }
        }
    }

    private void deadLetter(StreamOperations<String, String, String> ops,
                            MapRecord<String, String, String> record, long deliveries) {
        Map<String, String> fields = new LinkedHashMap<>(record.getValue());
        fields.put("original_id", record.getId().getValue());
        fields.put("reason", "max deliveries exceeded (" + deliveries + ")");
        ops.add(StreamRecords.newRecord().in(dlqKey).ofMap(fields));
        ops.acknowledge(properties.key(), properties.group(), record.getId());
        log.error("Moved poison entry {} to '{}' after {} deliveries", record.getId(), dlqKey, deliveries);
    }
}
