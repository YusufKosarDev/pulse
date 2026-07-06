package com.pulse.ingest.event;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Forwards events published by ml-service on the Redis pub/sub channel to the
 * connected dashboards. The payload's {@code type} field becomes the SSE event
 * name; the JSON is passed through untouched. Alert lifecycle events are
 * additionally handed to the webhook notifier.
 */
@Component
public class RedisEventRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisEventRelay.class);

    private final EventBroadcaster broadcaster;
    private final WebhookNotifier webhookNotifier;
    private final EmailNotifier emailNotifier;
    private final ObjectMapper objectMapper;
    private final String displayDetector;

    public RedisEventRelay(EventBroadcaster broadcaster,
                           WebhookNotifier webhookNotifier,
                           EmailNotifier emailNotifier,
                           ObjectMapper objectMapper,
                           @Value("${pulse.anomalies.display-detector}") String displayDetector) {
        this.broadcaster = broadcaster;
        this.webhookNotifier = webhookNotifier;
        this.emailNotifier = emailNotifier;
        this.objectMapper = objectMapper;
        this.displayDetector = displayDetector;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            log.warn("Discarding malformed event: {}", e.getMessage());
            return;
        }
        String type = node.path("type").asText("");
        if (type.isEmpty()) {
            return;
        }
        // Both detectors publish their findings; the dashboard shows only one.
        if ("anomaly".equals(type) && !displayDetector.equals(node.path("detector").asText())) {
            return;
        }
        broadcaster.send(type, body);
        if ("alert-opened".equals(type) || "alert-resolved".equals(type)) {
            webhookNotifier.notify(body);
            emailNotifier.notify(node);
        }
    }
}
