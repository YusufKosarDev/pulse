package com.pulse.ingest.event;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Emails alert lifecycle events to a configured recipient. Like the webhook
 * notifier, delivery is asynchronous and best-effort — SMTP hiccups are only
 * logged. Disabled unless both an SMTP host and a recipient are configured.
 */
@Component
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String to;
    private final String from;
    private final boolean enabled;

    public EmailNotifier(ObjectProvider<JavaMailSender> mailSender,
                         @Value("${spring.mail.host:}") String smtpHost,
                         @Value("${pulse.notifications.email.to}") String to,
                         @Value("${pulse.notifications.email.from}") String from) {
        this.mailSender = mailSender;
        this.to = to == null ? "" : to.trim();
        this.from = from;
        this.enabled = !this.to.isBlank() && smtpHost != null && !smtpHost.isBlank();
        if (enabled) {
            log.info("Email notifications enabled -> {}", this.to);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void notify(JsonNode event) {
        if (!enabled) {
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("Email notifications configured but no mail sender is available");
            return;
        }
        SimpleMailMessage message = compose(event);
        CompletableFuture.runAsync(() -> sender.send(message))
                .whenComplete((unused, error) -> {
                    if (error != null) {
                        log.warn("Email delivery failed: {}", error.getMessage());
                    }
                });
    }

    private SimpleMailMessage compose(JsonNode event) {
        JsonNode alert = event.path("alert");
        String type = event.path("type").asText();
        String metric = alert.path("metricName").asText();
        String severity = alert.path("severity").asText();

        String what = "alert-resolved".equals(type)
                ? "alert resolved (" + event.path("reason").asText("unknown") + ")"
                : "alert opened";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setFrom(from);
        message.setSubject("[Pulse] " + what + ": " + metric + " " + severity);
        message.setText("""
                %s

                Metric:     %s (%s)
                Severity:   %s
                Last value: %s (max |z| %s)
                Detections: %s
                First seen: %s
                Last seen:  %s
                """.formatted(
                what,
                metric, alert.path("sensorId").asText(),
                severity,
                alert.path("lastValue").asText(), alert.path("maxZScore").asText(),
                alert.path("anomalyCount").asText(),
                alert.path("firstSeen").asText(),
                alert.path("lastSeen").asText()));
        return message;
    }
}
