package io.scalelab.service;

import io.scalelab.dto.OrderAcceptedResponse;
import io.scalelab.dto.OrderProgressEvent;
import io.scalelab.entity.Order;
import io.scalelab.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 4 — Write Buffer Service
 *
 * Problem: Under heavy POST /orders load, every request does an individual INSERT.
 * With 100 VUs firing POSTs, that's 100 separate DB round-trips for 100 inserts.
 *
 * Solution: Queue orders in memory, flush in batches every 100ms.
 * - 100 individual INSERTs → 1 batch saveAll() call
 * - Reduces DB round-trips by ~50-100x under load
 * - Uses ConcurrentLinkedQueue (lock-free, thread-safe)
 *
 * Tracking:
 * - Each order gets a trackingId when accepted
 * - After batch flush, tracking map is updated with orderId + final status
 * - Client polls GET /orders/track/{trackingId} to check result
 *
 * Trade-off: Orders are NOT immediately visible in DB.
 * Max delay = flush interval (100ms). Acceptable for a trading queue.
 * Kafka (Phase 5) will replace this with durable async processing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderWriteBufferService {

    private final OrderRepository orderRepository;
    private final OrderStreamService orderStreamService;

    /** In-memory queue — orders waiting to be flushed to DB */
    private final ConcurrentLinkedQueue<BufferedOrder> writeQueue = new ConcurrentLinkedQueue<>();

    /** Tracking map — trackingId → processing result */
    private final Map<String, OrderAcceptedResponse> trackingMap = new ConcurrentHashMap<>();

    /** Counter for generating unique tracking IDs */
    private final AtomicLong trackingCounter = new AtomicLong(0);

    /** Metrics */
    private final AtomicLong totalBuffered = new AtomicLong(0);
    private final AtomicLong totalFlushed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalFlushes = new AtomicLong(0);

    /** Failure index — userId → list of failed trackingIds (for GET /orders/failures/{userId}) */
    private final Map<Long, List<String>> failuresByUser = new ConcurrentHashMap<>();

    /**
     * Accept an order into the write buffer.
     * Returns immediately with a tracking ID — does NOT hit DB.
     */
    public OrderAcceptedResponse bufferOrder(Order order) {
        String trackingId = "ORD-" + System.currentTimeMillis() + "-" + trackingCounter.incrementAndGet();

        OrderAcceptedResponse response = new OrderAcceptedResponse(
                trackingId,
                "RECEIVED",
                "Order queued — poll GET /orders/track/" + trackingId + " for final status",
                order.getUserId()
        );

        trackingMap.put(trackingId, response);
        writeQueue.offer(new BufferedOrder(trackingId, order));
        totalBuffered.incrementAndGet();

        // Push QUEUED SSE event to any listening client for this user
        orderStreamService.pushEvent(order.getUserId(),
                OrderProgressEvent.queued(trackingId, order.getUserId(), writeQueue.size()));

        log.debug("Order buffered — trackingId: {}, queue size: {}", trackingId, writeQueue.size());
        return response;
    }

    /**
     * Get the current status of a tracked order.
     * Returns null if trackingId not found.
     */
    public OrderAcceptedResponse getTrackingStatus(String trackingId) {
        return trackingMap.get(trackingId);
    }

    /**
     * Scheduled flush — runs every 100ms.
     * Drains all queued orders and batch-inserts them.
     *
     * Why 100ms?
     * - Fast enough that orders appear in DB within 100ms
     * - Slow enough to batch 10-100+ orders per flush under load
     * - Under low load, might flush 1-2 orders (still fine, negligible overhead)
     */
    @Scheduled(fixedRate = 100)
    @CacheEvict(value = "orders", allEntries = true, condition = "true")
    public void flushWriteBuffer() {
        if (writeQueue.isEmpty()) {
            return;
        }

        List<BufferedOrder> batch = new ArrayList<>();
        BufferedOrder item;
        while ((item = writeQueue.poll()) != null) {
            batch.add(item);
        }

        if (batch.isEmpty()) {
            return;
        }

        long start = System.currentTimeMillis();
        long flushNumber = totalFlushes.incrementAndGet();

        try {
            // Extract Order entities for batch save
            List<Order> orders = batch.stream()
                    .map(BufferedOrder::order)
                    .toList();

            // Single batch insert — 1 DB call instead of N
            List<Order> saved = orderRepository.saveAll(orders);

            // Update tracking for each saved order + push SSE events
            for (int i = 0; i < batch.size(); i++) {
                BufferedOrder buffered = batch.get(i);
                Order savedOrder = saved.get(i);

                OrderAcceptedResponse tracking = trackingMap.get(buffered.trackingId());
                if (tracking != null) {
                    tracking.setStatus("PENDING");
                    tracking.setOrderId(savedOrder.getId());
                    tracking.setMessage("Order saved with id: " + savedOrder.getId());
                }

                // Push SAVED event to user's SSE stream
                Long userId = buffered.order().getUserId();
                orderStreamService.pushEvent(userId,
                        OrderProgressEvent.saved(buffered.trackingId(), userId, savedOrder.getId()));

                // Push FLUSHING progress every 10 orders (avoid flooding SSE)
                if ((i + 1) % 10 == 0 || i == batch.size() - 1) {
                    orderStreamService.pushEvent(userId,
                            OrderProgressEvent.flushing(userId, batch.size(), i + 1, writeQueue.size()));
                }
            }

            totalFlushed.addAndGet(batch.size());
            long elapsed = System.currentTimeMillis() - start;

            log.info("Write buffer flush #{} — batch: {} orders, took: {} ms, total flushed: {}",
                    flushNumber, batch.size(), elapsed, totalFlushed.get());

        } catch (Exception e) {
            log.error("Write buffer flush #{} FAILED — batch: {} orders, error: {}",
                    flushNumber, batch.size(), e.getMessage(), e);

            totalFailed.addAndGet(batch.size());

            // Mark all orders in this batch as FAILED and index by userId
            for (BufferedOrder buffered : batch) {
                OrderAcceptedResponse tracking = trackingMap.get(buffered.trackingId());
                if (tracking != null) {
                    tracking.setStatus("FAILED");
                    tracking.setMessage("Order failed to save — check GET /orders/track/" + buffered.trackingId());
                    tracking.setFailureReason(e.getClass().getSimpleName() + ": " + e.getMessage());

                    // Index failure by userId so user can query GET /orders/failures/{userId}
                    Long userId = buffered.order().getUserId();
                    if (userId != null) {
                        failuresByUser.computeIfAbsent(userId, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                                .add(buffered.trackingId());

                        // Push FAILED event to user's SSE stream — client knows immediately
                        orderStreamService.pushEvent(userId,
                                OrderProgressEvent.failed(buffered.trackingId(), userId,
                                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                    }
                }
            }
        }
    }

    /**
     * Cleanup old tracking entries every 60 seconds.
     * Prevents memory leak from accumulated tracking data.
     * Entries older than 5 minutes are removed.
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupTracking() {
        if (trackingMap.isEmpty()) return;

        int before = trackingMap.size();
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusMinutes(5);

        trackingMap.entrySet().removeIf(entry ->
                entry.getValue().getReceivedAt() != null &&
                        entry.getValue().getReceivedAt().isBefore(cutoff));

        int removed = before - trackingMap.size();
        if (removed > 0) {
            log.info("Tracking cleanup — removed: {}, remaining: {}", removed, trackingMap.size());
        }
    }

    /**
     * Get all failed/rejected orders for a specific user.
     * This is how the user discovers that their 202 ACCEPTED order actually failed.
     *
     * Flow:
     *   User calls POST /orders → gets 202 + trackingId
     *   Later:  GET /orders/failures/{userId} → sees list of failed orders with reasons
     */
    public List<OrderAcceptedResponse> getFailedOrders(Long userId) {
        // Scan tracking map for FAILED + REJECTED orders for this user
        return trackingMap.values().stream()
                .filter(t -> userId.equals(t.getUserId()))
                .filter(t -> "FAILED".equals(t.getStatus()) || "REJECTED".equals(t.getStatus()))
                .collect(java.util.stream.Collectors.toList());
    }

    /** Get buffer metrics for monitoring */
    public Map<String, Long> getMetrics() {
        return Map.of(
                "buffered", totalBuffered.get(),
                "flushed", totalFlushed.get(),
                "failed", totalFailed.get(),
                "flushes", totalFlushes.get(),
                "pendingInQueue", (long) writeQueue.size(),
                "trackingEntries", (long) trackingMap.size()
        );
    }

    /** Internal record to pair tracking ID with the Order entity */
    private record BufferedOrder(String trackingId, Order order) {}
}

