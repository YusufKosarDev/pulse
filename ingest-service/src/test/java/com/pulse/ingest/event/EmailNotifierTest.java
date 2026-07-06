package com.pulse.ingest.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class EmailNotifierTest {

    private static final String OPENED_EVENT = """
            {"type": "alert-opened", "alert": {"id": 7, "metricName": "energy_kwh",
             "sensorId": "sensor-energy-1", "severity": "critical", "anomalyCount": 3,
             "firstSeen": "2026-07-06T12:00:00Z", "lastSeen": "2026-07-06T12:02:00Z",
             "lastValue": 95.0, "maxZScore": 31.5}}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JavaMailSender sender = mock(JavaMailSender.class);

    private ObjectProvider<JavaMailSender> provider(JavaMailSender instance) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private JsonNode event(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void disabledWithoutRecipientOrSmtpHost() throws Exception {
        EmailNotifier noRecipient = new EmailNotifier(provider(sender), "smtp.local", " ", "pulse@x");
        EmailNotifier noHost = new EmailNotifier(provider(sender), "", "ops@x", "pulse@x");

        assertThat(noRecipient.enabled()).isFalse();
        assertThat(noHost.enabled()).isFalse();
        noRecipient.notify(event(OPENED_EVENT));
        noHost.notify(event(OPENED_EVENT));
        verifyNoInteractions(sender);
    }

    @Test
    void mailsOpenedEventWithAlertDetails() throws Exception {
        EmailNotifier notifier = new EmailNotifier(provider(sender), "smtp.local", "ops@x", "pulse@x");
        assertThat(notifier.enabled()).isTrue();

        notifier.notify(event(OPENED_EVENT));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(sender).send(captor.capture()));
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("ops@x");
        assertThat(message.getSubject()).isEqualTo("[Pulse] alert opened: energy_kwh critical");
        assertThat(message.getText())
                .contains("energy_kwh (sensor-energy-1)")
                .contains("95.0")
                .contains("Detections: 3");
    }

    @Test
    void resolvedSubjectCarriesTheReason() throws Exception {
        EmailNotifier notifier = new EmailNotifier(provider(sender), "smtp.local", "ops@x", "pulse@x");

        notifier.notify(event("""
                {"type": "alert-resolved", "reason": "auto", "alert":
                 {"metricName": "temperature_c", "severity": "warning"}}
                """));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(sender).send(captor.capture()));
        assertThat(captor.getValue().getSubject())
                .isEqualTo("[Pulse] alert resolved (auto): temperature_c warning");
    }
}
