package io.scalelab.controller;

import io.scalelab.dto.CreateOrderRequest;
import io.scalelab.dto.OrderAcceptedResponse;
import io.scalelab.dto.OrderResponse;
import io.scalelab.dto.PagedResponse;
import io.scalelab.service.OrderService;
import io.scalelab.service.OrderStreamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderStreamService orderStreamService;

    // =========================================================================
    // Phase 4 — Async Order Placement (CompletableFuture + Write Buffer)
    // =========================================================================

    /**
     * POST /orders — ASYNC (Phase 4 default)
     *
     * Returns 202 ACCEPTED immediately with:
     *   - trackingId: unique ID for this order
     *   - streamUrl:  "/orders/stream/{userId}" — UI opens this ONCE for real-time progress
     *   - trackUrl:   "/orders/track/{trackingId}" — fallback polling
     *
     * The UI should:
     *   1. Call POST /orders → get 202 response
     *   2. If no SSE connection exists for this userId → open EventSource(response.streamUrl)
     *   3. Add trackingId to progress drawer/dropdown
     *   4. SSE events auto-push: QUEUED → FLUSHING → SAVED (or FAILED)
     *   5. If user places 10 orders, all 10 share the SAME SSE connection
     */
    @PostMapping
    public ResponseEntity<OrderAcceptedResponse> placeOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderAcceptedResponse response = orderService.placeOrderAsync(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * POST /orders/sync — SYNC (backward compatible, Phase 1-3 behavior)
     *
     * Still blocks request thread for 3 DB round-trips.
     * Kept so k6 tests can compare sync vs async under same load.
     */
    @PostMapping("/sync")
    public ResponseEntity<OrderResponse> placeOrderSync(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.placeOrderSync(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /orders/track/{trackingId} — Poll order processing status
     *
     * Returns:
     * - RECEIVED:  queued, not yet flushed to DB
     * - PENDING:   saved to DB successfully (orderId populated)
     * - REJECTED:  validation failed (user/account not found)
     * - FAILED:    infrastructure failure (DB down, OOM, connection refused)
     */
    @GetMapping("/track/{trackingId}")
    public ResponseEntity<OrderAcceptedResponse> trackOrder(@PathVariable String trackingId) {
        OrderAcceptedResponse status = orderService.trackOrder(trackingId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * GET /orders/failures/{userId} — Get all FAILED + REJECTED orders for a user
     *
     * This is how the user discovers that their 202 ACCEPTED order actually failed.
     * Returns a list of orders that were accepted but could not be persisted,
     * each with a failureReason explaining what went wrong.
     *
     * Empty list = all orders processed successfully.
     */
    @GetMapping("/failures/{userId}")
    public ResponseEntity<List<OrderAcceptedResponse>> getFailedOrders(@PathVariable Long userId) {
        List<OrderAcceptedResponse> failures = orderService.getFailedOrders(userId);
        return ResponseEntity.ok(failures);
    }

    /**
     * GET /orders/buffer/metrics — Write buffer monitoring
     *
     * Returns: { buffered, flushed, flushes, pendingInQueue, trackingEntries }
     */
    @GetMapping("/buffer/metrics")
    public ResponseEntity<Map<String, Long>> getBufferMetrics() {
        return ResponseEntity.ok(orderService.getBufferMetrics());
    }

    /**
     * GET /orders/stream/{userId} — Server-Sent Events (SSE) for real-time progress
     *
     * This endpoint is NOT called manually. The UI opens it automatically:
     *   1. POST /orders returns 202 with { streamUrl: "/orders/stream/1" }
     *   2. UI does: new EventSource(response.streamUrl)  — opens once per userId
     *   3. All subsequent orders for that userId push events to the SAME connection
     *
     * Events pushed:
     *   QUEUED    → order entered write buffer (immediate)
     *   FLUSHING  → batch flush in progress (progress bar: 5/50, 25/50, 50/50)
     *   SAVED     → order persisted to DB (orderId populated)
     *   FAILED    → flush failed (failureReason populated)
     *   REJECTED  → validation failed (user/account not found)
     *   HEARTBEAT → keep-alive every 15s
     *
     * Each event includes trackingId, so the UI can match events to
     * individual orders in a progress drawer showing all 10 orders at once.
     *
     * Connection auto-closes after 5 minutes.
     */
    @GetMapping(value = "/stream/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOrderProgress(@PathVariable Long userId) {
        return orderStreamService.subscribe(userId);
    }

    // =========================================================================
    // Phase 3 — Paginated + Cached endpoints (unchanged)
    // All list endpoints still use ?page=0&size=20
    // =========================================================================

    /**
     * GET /orders/{userId}?page=0&size=20
     */
    @GetMapping("/{userId}")
    public ResponseEntity<PagedResponse<OrderResponse>> getOrdersByUserId(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getOrdersByUserId(userId, page, Math.min(size, 100)));
    }

    /**
     * GET /orders/search?status=EXECUTED&from=2026-02-01T00:00:00&page=0&size=20
     */
    @GetMapping("/search")
    public ResponseEntity<PagedResponse<OrderResponse>> searchOrders(
            @RequestParam String status,
            @RequestParam String from,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDateTime fromDate = LocalDateTime.parse(from);
        return ResponseEntity.ok(orderService.searchOrders(status, fromDate, page, Math.min(size, 100)));
    }

    /**
     * GET /orders/status/{status}?page=0&size=20
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<PagedResponse<OrderResponse>> getOrdersByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getOrdersByStatus(status, page, Math.min(size, 100)));
    }

    /**
     * GET /orders/user/{userId}/status/{status}?page=0&size=20
     */
    @GetMapping("/user/{userId}/status/{status}")
    public ResponseEntity<PagedResponse<OrderResponse>> getOrdersByUserIdAndStatus(
            @PathVariable Long userId,
            @PathVariable String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getOrdersByUserIdAndStatus(userId, status, page, Math.min(size, 100)));
    }

    /**
     * GET /orders/user/{userId}/recent?from=2026-02-01T00:00:00&page=0&size=20
     */
    @GetMapping("/user/{userId}/recent")
    public ResponseEntity<PagedResponse<OrderResponse>> getRecentOrdersByUser(
            @PathVariable Long userId,
            @RequestParam String from,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDateTime fromDate = LocalDateTime.parse(from);
        return ResponseEntity.ok(orderService.searchOrdersByUserAndDate(userId, fromDate, page, Math.min(size, 100)));
    }
}

