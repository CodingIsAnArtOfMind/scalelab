package io.scalelab.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Phase 4 — SSE progress event pushed to clients in real-time.
 *
 * Sent over GET /orders/stream/{userId} as Server-Sent Events.
 * Client opens connection once, server pushes updates as orders progress.
 *
 * Event types:
 *   QUEUED     → order accepted into buffer (immediate)
 *   FLUSHING   → batch flush in progress (progress bar data)
 *   SAVED      → order saved to DB successfully
 *   FAILED     → flush failed (DB down, OOM, etc.)
 *   REJECTED   → validation failed (user/account not found)
 *   HEARTBEAT  → keep-alive ping (every 15s to prevent timeout)
 */
@Data
public class OrderProgressEvent implements Serializable {

    private String eventType;       // QUEUED, FLUSHING, SAVED, FAILED, REJECTED, HEARTBEAT
    private String trackingId;
    private Long userId;
    private Long orderId;           // populated on SAVED
    private String message;
    private String failureReason;   // populated on FAILED

    // Progress bar data (populated on FLUSHING events)
    private int batchTotal;         // total orders in this flush batch
    private int batchSaved;         // how many saved so far
    private int pendingInQueue;     // orders still waiting in buffer

    // Running totals for this user
    private long userTotalBuffered;
    private long userTotalSaved;
    private long userTotalFailed;

    private LocalDateTime timestamp;

    public OrderProgressEvent() {
        this.timestamp = LocalDateTime.now();
    }

    public static OrderProgressEvent queued(String trackingId, Long userId, int pendingInQueue) {
        OrderProgressEvent e = new OrderProgressEvent();
        e.eventType = "QUEUED";
        e.trackingId = trackingId;
        e.userId = userId;
        e.pendingInQueue = pendingInQueue;
        e.message = "Order accepted into buffer";
        return e;
    }

    public static OrderProgressEvent flushing(Long userId, int batchTotal, int batchSaved, int pendingInQueue) {
        OrderProgressEvent e = new OrderProgressEvent();
        e.eventType = "FLUSHING";
        e.userId = userId;
        e.batchTotal = batchTotal;
        e.batchSaved = batchSaved;
        e.pendingInQueue = pendingInQueue;
        e.message = "Flushing batch: " + batchSaved + "/" + batchTotal;
        return e;
    }

    public static OrderProgressEvent saved(String trackingId, Long userId, Long orderId) {
        OrderProgressEvent e = new OrderProgressEvent();
        e.eventType = "SAVED";
        e.trackingId = trackingId;
        e.userId = userId;
        e.orderId = orderId;
        e.message = "Order saved with id: " + orderId;
        return e;
    }

    public static OrderProgressEvent failed(String trackingId, Long userId, String reason) {
        OrderProgressEvent e = new OrderProgressEvent();
        e.eventType = "FAILED";
        e.trackingId = trackingId;
        e.userId = userId;
        e.failureReason = reason;
        e.message = "Order failed to save";
        return e;
    }

    public static OrderProgressEvent rejected(String trackingId, Long userId, String reason) {
        OrderProgressEvent e = new OrderProgressEvent();
        e.eventType = "REJECTED";
        e.trackingId = trackingId;
        e.userId = userId;
        e.message = reason;
        return e;
    }

    public static OrderProgressEvent heartbeat(Long userId, int pendingInQueue) {
        OrderProgressEvent e = new OrderProgressEvent();
        e.eventType = "HEARTBEAT";
        e.userId = userId;
        e.pendingInQueue = pendingInQueue;
        e.message = "alive";
        return e;
    }
}

