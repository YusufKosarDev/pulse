package com.pulse.ingest.event;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Posts alert lifecycle events as JSON to a configured webhook URL. Delivery
 * is best-effort and asynchronous: a slow or failing receiver must never slow
 * down stream processing or the API, so failures are only logged.
 * Disabled entirely when no URL is configured.
 */
@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String webhookUrl;

    public WebhookNotifier(@Value("${pulse.notifications.webhook-url}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        if (enabled()) {
            log.info("Webhook notifications enabled -> {}", this.webhookUrl);
        }
    }

    public boolean enabled() {
        return !webhookUrl.isBlank();
    }

    public void notify(String jsonPayload) {
        if (!enabled()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warn("Webhook delivery failed: {}", error.getMessage());
                    } else if (response.statusCode() >= 400) {
                        log.warn("Webhook receiver returned {}", response.statusCode());
                    }
                });
    }
}
