package com.pulse.ingest.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class WebhookNotifierTest {

    @Test
    void disabledWhenUrlIsBlank() {
        WebhookNotifier notifier = new WebhookNotifier("  ");
        assertThat(notifier.enabled()).isFalse();
        notifier.notify("{\"type\": \"alert-opened\"}"); // must be a no-op, not an error
    }

    @Test
    void postsPayloadAsJsonToConfiguredUrl() throws IOException, InterruptedException {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                body.set(new String(in.readAllBytes()));
            }
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/hook";
            WebhookNotifier notifier = new WebhookNotifier(url);
            assertThat(notifier.enabled()).isTrue();

            String payload = "{\"type\": \"alert-opened\", \"alert\": {\"id\": 7}}";
            notifier.notify(payload);

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(body.get()).isEqualTo(payload);
            assertThat(contentType.get()).isEqualTo("application/json");
        } finally {
            server.stop(0);
        }
    }
}
