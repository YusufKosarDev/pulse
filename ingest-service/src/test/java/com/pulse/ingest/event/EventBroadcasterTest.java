package com.pulse.ingest.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class EventBroadcasterTest {

    @Test
    void sendDropsDeadEmittersAndKeepsLiveOnes() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        broadcaster.subscribe();
        SseEmitter dead = broadcaster.subscribe();
        assertThat(broadcaster.clientCount()).isEqualTo(2);

        // A completed emitter rejects further sends; the broadcaster must prune it.
        dead.complete();
        broadcaster.send("metric", "{}");
        assertThat(broadcaster.clientCount()).isEqualTo(1);
    }

    @Test
    void heartbeatAlsoPrunesDeadEmitters() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        SseEmitter dead = broadcaster.subscribe();
        dead.complete();
        broadcaster.heartbeat();
        assertThat(broadcaster.clientCount()).isZero();
    }
}
