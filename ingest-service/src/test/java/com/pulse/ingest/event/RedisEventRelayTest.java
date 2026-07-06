package com.pulse.ingest.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class RedisEventRelayTest {

    private static final byte[] CHANNEL = "pulse-events".getBytes(StandardCharsets.UTF_8);

    private EventBroadcaster broadcaster;
    private WebhookNotifier webhookNotifier;
    private EmailNotifier emailNotifier;
    private RedisEventRelay relay;

    @BeforeEach
    void setUp() {
        broadcaster = mock(EventBroadcaster.class);
        webhookNotifier = mock(WebhookNotifier.class);
        emailNotifier = mock(EmailNotifier.class);
        relay = new RedisEventRelay(broadcaster, webhookNotifier, emailNotifier,
                new ObjectMapper(), "ewma");
    }

    private void receive(String body) {
        relay.onMessage(new DefaultMessage(CHANNEL, body.getBytes(StandardCharsets.UTF_8)), null);
    }

    @Test
    void dropsMalformedJson() {
        receive("{not json");
        verifyNoInteractions(broadcaster, webhookNotifier, emailNotifier);
    }

    @Test
    void dropsEventsWithoutType() {
        receive("{\"metricName\": \"energy_kwh\"}");
        verifyNoInteractions(broadcaster, webhookNotifier, emailNotifier);
    }

    @Test
    void filtersAnomaliesFromShadowDetector() {
        receive("{\"type\": \"anomaly\", \"detector\": \"zscore\", \"metricName\": \"energy_kwh\"}");
        verifyNoInteractions(broadcaster, webhookNotifier, emailNotifier);
    }

    @Test
    void forwardsDisplayDetectorAnomaliesToSseOnly() {
        String body = "{\"type\": \"anomaly\", \"detector\": \"ewma\", \"metricName\": \"energy_kwh\"}";
        receive(body);
        verify(broadcaster).send("anomaly", body);
        verifyNoInteractions(webhookNotifier, emailNotifier);
    }

    @Test
    void routesAlertLifecycleEventsToNotifiersAsWell() {
        String opened = "{\"type\": \"alert-opened\", \"alert\": {\"id\": 1}}";
        String resolved = "{\"type\": \"alert-resolved\", \"reason\": \"auto\", \"alert\": {\"id\": 1}}";
        receive(opened);
        receive(resolved);
        verify(broadcaster).send("alert-opened", opened);
        verify(broadcaster).send("alert-resolved", resolved);
        verify(webhookNotifier).notify(opened);
        verify(webhookNotifier).notify(resolved);
        verify(emailNotifier, org.mockito.Mockito.times(2)).notify(any(JsonNode.class));
    }

    @Test
    void forwardsOtherEventsWithoutNotifiers() {
        String body = "{\"type\": \"forecast-changed\", \"metricName\": \"energy_kwh\"}";
        receive(body);
        verify(broadcaster).send("forecast-changed", body);
        verify(webhookNotifier, never()).notify(anyString());
        verifyNoInteractions(emailNotifier);
    }
}
