package io.scalelab.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Phase 4 — Async order acceptance response.
 *
 * Returned immediately with 202 ACCEPTED when POST /orders is called.
 *
 * The response includes everything the UI needs to show real-time progress:
 *   - trackingId: unique ID for this order
 *   - streamUrl:  SSE endpoint to open for real-time progress (auto-pushes events)
 *   - trackUrl:   polling endpoint to check status manually
 *
 * UI Flow:
 *   1. User clicks "Place Order" → POST /orders → gets this response (202)
 *   2. UI checks: is SSE stream already open for this userId?
 *      - NO  → open new EventSource(streamUrl) — one connection for ALL orders
 *      - YES → reuse existing connection (events already flowing)
 *   3. UI adds this trackingId to a progress list (drawer/dropdown)
 *   4. SSE events arrive: QUEUED → FLUSHING (5/10) → SAVED (orderId)
 *   5. UI updates the progress bar for each trackingId as events come in
 *   6. If user places 10 orders, all 10 appear in the progress drawer
 *      with individual progress bars, all fed by the SAME SSE connection
 *
 * Status lifecycle:
 *   RECEIVED → order accepted into write buffer (not yet in DB)
 *   PENDING  → order saved to DB successfully (orderId populated)
 *   REJECTED → validation failed (user/account not found)
 *   FAILED   → infrastructure failure (DB down, OOM, connection refused)
 */
@Data
public class OrderAcceptedResponse implements Serializable {

    private String trackingId;
    private String status;       // RECEIVED, PENDING, REJECTED, FAILED
    private String message;
    private LocalDateTime receivedAt;

    // Populated after async processing completes
    private Long orderId;

    // Populated on all responses — lets us query failures by user
    private Long userId;

    // URLs for the UI to connect to (included in 202 response)
    private String streamUrl;    // SSE endpoint: /orders/stream/{userId}
    private String trackUrl;     // Polling endpoint: /orders/track/{trackingId}

    // Populated only when status = FAILED — contains the actual error
    private String failureReason;

    public OrderAcceptedResponse() {}

    public OrderAcceptedResponse(String trackingId, String status, String message, Long userId) {
        this.trackingId = trackingId;
        this.status = status;
        this.message = message;
        this.userId = userId;
        this.receivedAt = LocalDateTime.now();
        this.streamUrl = "/orders/stream/" + userId;
        this.trackUrl = "/orders/track/" + trackingId;
    }
}

