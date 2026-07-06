package com.pulse.ingest.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out of server-sent events to every connected dashboard. Emitters never
 * time out on their own; dead connections are detected by a failed send and
 * dropped, with a periodic heartbeat guaranteeing an upper bound on how long
 * that takes.
 */
@Component
public class EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.info("SSE client connected ({} total)", emitters.size());
        return emitter;
    }

    public void send(String event, String jsonData) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(jsonData));
            } catch (Exception e) {
                emitters.remove(emitter);
                emitter.complete();
            }
        }
    }

    /** Comment-only frame: keeps idle connections open and flushes dead ones. */
    @Scheduled(fixedDelay = 25000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                emitters.remove(emitter);
                emitter.complete();
            }
        }
    }
}
