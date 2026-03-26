package io.scalelab.service;

import io.scalelab.dto.OrderProgressEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 4 — Server-Sent Events (SSE) Manager
 *
 * Manages SSE connections per userId.
 * When the write buffer flushes (or fails), this service pushes real-time
 * progress events to any connected client for that userId.
 *
 * Flow:
 *   1. Client opens:  GET /orders/stream/{userId}
 *   2. Server creates SseEmitter, registers it under userId
 *   3. On each buffer flush: push FLUSHING → SAVED (or FAILED) events
 *   4. Client sees live progress (usable as a progress bar in UI)
 *   5. Connection auto-closes after 5 minutes (timeout)
 *
 * Thread model (SseEmitter on Spring MVC):
 *   - SseEmitter uses Servlet 3.1 async support
 *   - Tomcat request thread is RELEASED after subscribe() returns
 *   - Each SSE connection holds: 1 open TCP socket + async context (NO thread)
 *   - emitter.send() briefly borrows a Tomcat NIO thread to write bytes
 *   - At 100-500 connections this is fine; at 10K+ connections, send() writes
 *     compete with HTTP requests for the Tomcat thread pool
 *   - WebFlux Flux SSE would eliminate write contention (non-blocking epoll/kqueue)
 *     but requires rewriting the entire stack to reactive
 */
@Slf4j
@Service
public class OrderStreamService {

    /** userId → list of active SSE emitters (one user can have multiple browser tabs) */
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Register a new SSE connection for a user.
     * Timeout: 5 minutes (closes automatically).
     */
    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5 min timeout

        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(e -> removeEmitter(userId, emitter));

        log.info("SSE subscribed — userId: {}, total connections: {}", userId, countConnections());

        // Send initial connected event
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"message\":\"Connected — listening for order updates\",\"userId\":" + userId + "}"));
        } catch (IOException e) {
            log.warn("Failed to send initial SSE event to userId: {}", userId);
            removeEmitter(userId, emitter);
        }

        return emitter;
    }

    /**
     * Push a progress event to all SSE connections for a specific user.
     * Called by OrderWriteBufferService during flush.
     */
    public void pushEvent(Long userId, OrderProgressEvent event) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return; // no one listening — skip (zero cost)
        }

        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getEventType().toLowerCase())
                        .data(event));
            } catch (IOException e) {
                log.debug("SSE send failed for userId: {} — removing dead connection", userId);
                removeEmitter(userId, emitter);
            }
        }
    }


    /**
     * Heartbeat — keeps SSE connections alive (prevents proxy/LB timeout).
     * Runs every 15 seconds.
     */
    @Scheduled(fixedRate = 15000)
    public void sendHeartbeats() {
        if (emitters.isEmpty()) return;

        for (Map.Entry<Long, List<SseEmitter>> entry : emitters.entrySet()) {
            Long userId = entry.getKey();
            OrderProgressEvent heartbeat = OrderProgressEvent.heartbeat(userId, 0);
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(heartbeat));
                } catch (IOException e) {
                    removeEmitter(userId, emitter);
                }
            }
        }
    }


    /** Total active SSE connections */
    public int countConnections() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                emitters.remove(userId);
            }
        }
        log.debug("SSE removed — userId: {}, remaining connections: {}", userId, countConnections());
    }
}

