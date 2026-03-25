package io.scalelab.service;

import io.scalelab.dto.CreateOrderRequest;
import io.scalelab.dto.OrderAcceptedResponse;
import io.scalelab.dto.OrderProgressEvent;
import io.scalelab.dto.OrderResponse;
import io.scalelab.dto.PagedResponse;
import io.scalelab.entity.Order;
import io.scalelab.exception.ResourceNotFoundException;
import io.scalelab.repository.AccountRepository;
import io.scalelab.repository.OrderRepository;
import io.scalelab.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final OrderWriteBufferService writeBufferService;
    private final OrderStreamService orderStreamService;

    // =========================================================================
    // Phase 4 — Async Order Placement (CompletableFuture + Write Buffer)
    // =========================================================================

    /**
     * SYNC place order — kept for backward compatibility (Phase 1-3 behavior).
     * Still blocks the request thread for 3 DB round-trips.
     * Used by: POST /orders/sync
     */
    @CacheEvict(value = "orders", allEntries = true)
    public OrderResponse placeOrderSync(CreateOrderRequest request) {
        long start = System.currentTimeMillis();
        log.info("[SYNC] Placing order for user: {}, symbol: {}, type: {}",
                request.getUserId(), request.getSymbol(), request.getOrderType());

        userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + request.getUserId()));

        accountRepository.findById(request.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + request.getAccountId()));

        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setAccountId(request.getAccountId());
        order.setSymbol(request.getSymbol());
        order.setQuantity(request.getQuantity());
        order.setPrice(request.getPrice());
        order.setOrderType(request.getOrderType());
        order.setStatus("PENDING");

        Order saved = orderRepository.save(order);
        log.info("[SYNC] Order placed with id: {}, status: {} — took {} ms",
                saved.getId(), saved.getStatus(), System.currentTimeMillis() - start);

        return mapToResponse(saved);
    }

    /**
     * ASYNC place order — Phase 4 optimized path.
     *
     * Flow:
     * 1. Request thread: build Order entity → push to write buffer → return 202 ACCEPTED (< 1ms)
     * 2. Background @Scheduled: flush buffer batch to DB every 100ms (1 saveAll instead of N saves)
     * 3. Background @Async: validate user/account existence asynchronously (non-blocking)
     *
     * What's different from Phase 3:
     * - Request thread does ZERO DB calls (was 3: findUser + findAccount + save)
     * - Orders are batched (100 individual inserts → 1 batch insert)
     * - Client gets tracking ID to poll for final status
     */
    public OrderAcceptedResponse placeOrderAsync(CreateOrderRequest request) {
        long start = System.currentTimeMillis();
        log.info("[ASYNC] Accepting order for user: {}, symbol: {}, type: {}",
                request.getUserId(), request.getSymbol(), request.getOrderType());

        // Build order entity — no DB call
        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setAccountId(request.getAccountId());
        order.setSymbol(request.getSymbol());
        order.setQuantity(request.getQuantity());
        order.setPrice(request.getPrice());
        order.setOrderType(request.getOrderType());
        order.setStatus("RECEIVED");  // New status: accepted but not yet in DB

        // Push to write buffer — returns immediately with tracking ID
        OrderAcceptedResponse response = writeBufferService.bufferOrder(order);

        // Fire-and-forget async validation (runs on orderProcessingExecutor)
        validateOrderAsync(request, response.getTrackingId());

        log.info("[ASYNC] Order accepted — trackingId: {}, took: {} ms (zero DB calls)",
                response.getTrackingId(), System.currentTimeMillis() - start);

        return response;
    }

    /**
     * Async validation — runs on background thread pool.
     * Validates user/account exist. If not, marks tracking as REJECTED.
     * Does NOT block the request thread.
     */
    @Async("orderProcessingExecutor")
    public void validateOrderAsync(CreateOrderRequest request, String trackingId) {
        try {
            log.debug("[ASYNC-VALIDATE] Validating order {} — user: {}, account: {}",
                    trackingId, request.getUserId(), request.getAccountId());

            boolean userExists = userRepository.existsById(request.getUserId());
            boolean accountExists = accountRepository.existsById(request.getAccountId());

            if (!userExists || !accountExists) {
                OrderAcceptedResponse tracking = writeBufferService.getTrackingStatus(trackingId);
                String reason = !userExists ? "User not found: " + request.getUserId()
                        : "Account not found: " + request.getAccountId();
                if (tracking != null) {
                    tracking.setStatus("REJECTED");
                    tracking.setMessage(reason);
                }

                // Push REJECTED event to user's SSE stream
                orderStreamService.pushEvent(request.getUserId(),
                        OrderProgressEvent.rejected(trackingId, request.getUserId(), reason));

                log.warn("[ASYNC-VALIDATE] Order {} REJECTED — user exists: {}, account exists: {}",
                        trackingId, userExists, accountExists);
            } else {
                log.debug("[ASYNC-VALIDATE] Order {} validated OK", trackingId);
            }
        } catch (Exception e) {
            log.error("[ASYNC-VALIDATE] Validation failed for {} — {}", trackingId, e.getMessage());
        }
    }

    /**
     * Track order status by tracking ID.
     */
    public OrderAcceptedResponse trackOrder(String trackingId) {
        return writeBufferService.getTrackingStatus(trackingId);
    }

    /**
     * Get write buffer metrics for monitoring.
     */
    public Map<String, Long> getBufferMetrics() {
        return writeBufferService.getMetrics();
    }

    /**
     * Get all failed/rejected orders for a user.
     * This is how the user discovers their 202 ACCEPTED order actually failed.
     */
    public List<OrderAcceptedResponse> getFailedOrders(Long userId) {
        return writeBufferService.getFailedOrders(userId);
    }

    // =========================================================================
    // Phase 3 — Paginated + Cached endpoints
    // =========================================================================

    /**
     * GET /orders/{userId}?page=0&size=20
     * Paginated + cached. Returns only 20 orders per page instead of ALL.
     */
    @Cacheable(value = "orders", key = "'user:' + #userId + ':page:' + #page + ':size:' + #size")
    public PagedResponse<OrderResponse> getOrdersByUserId(Long userId, int page, int size) {
        long start = System.currentTimeMillis();
        log.info("Fetching orders for user: {} — page: {}, size: {}", userId, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orderPage = orderRepository.findByUserId(userId, pageable);

        PagedResponse<OrderResponse> response = toPagedResponse(orderPage);
        log.info("Found {} orders (page {}/{}) for user: {} — took {} ms",
                orderPage.getNumberOfElements(), page, orderPage.getTotalPages(), userId, System.currentTimeMillis() - start);
        return response;
    }

    /**
     * GET /orders/status/{status}?page=0&size=20
     * Previously returned ALL 60K+ EXECUTED orders. Now paginated to 20.
     */
    @Cacheable(value = "orders", key = "'status:' + #status + ':page:' + #page + ':size:' + #size")
    public PagedResponse<OrderResponse> getOrdersByStatus(String status, int page, int size) {
        long start = System.currentTimeMillis();
        log.info("Searching orders by status: {} — page: {}, size: {}", status, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orderPage = orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable);

        PagedResponse<OrderResponse> response = toPagedResponse(orderPage);
        log.info("Found {} orders with status: {} (page {}/{}) — took {} ms",
                orderPage.getNumberOfElements(), status, page, orderPage.getTotalPages(), System.currentTimeMillis() - start);
        return response;
    }

    /**
     * GET /orders/user/{userId}/status/{status}?page=0&size=20
     */
    @Cacheable(value = "orders", key = "'user:' + #userId + ':status:' + #status + ':page:' + #page + ':size:' + #size")
    public PagedResponse<OrderResponse> getOrdersByUserIdAndStatus(Long userId, String status, int page, int size) {
        long start = System.currentTimeMillis();
        log.info("Searching orders for user: {} with status: {} — page: {}, size: {}", userId, status, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orderPage = orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);

        PagedResponse<OrderResponse> response = toPagedResponse(orderPage);
        log.info("Found {} orders for user: {} with status: {} (page {}/{}) — took {} ms",
                orderPage.getNumberOfElements(), userId, status, page, orderPage.getTotalPages(), System.currentTimeMillis() - start);
        return response;
    }

    /**
     * GET /orders/search?status=EXECUTED&from=2026-02-01T00:00:00&page=0&size=20
     * The heaviest query — now paginated. Returns 20 rows instead of 60K.
     */
    @Cacheable(value = "orders", key = "'search:' + #status + ':from:' + #from + ':page:' + #page + ':size:' + #size")
    public PagedResponse<OrderResponse> searchOrders(String status, LocalDateTime from, int page, int size) {
        long start = System.currentTimeMillis();
        log.info("Searching orders — status: {}, from: {}, page: {}, size: {}", status, from, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orderPage = orderRepository.searchByStatusAndDateRange(status, from, pageable);

        PagedResponse<OrderResponse> response = toPagedResponse(orderPage);
        log.info("Search found {} orders (page {}/{}) — took {} ms",
                orderPage.getNumberOfElements(), page, orderPage.getTotalPages(), System.currentTimeMillis() - start);
        return response;
    }

    /**
     * GET /orders/user/{userId}/recent?from=2026-02-01T00:00:00&page=0&size=20
     */
    @Cacheable(value = "orders", key = "'user:' + #userId + ':from:' + #from + ':page:' + #page + ':size:' + #size")
    public PagedResponse<OrderResponse> searchOrdersByUserAndDate(Long userId, LocalDateTime from, int page, int size) {
        long start = System.currentTimeMillis();
        log.info("Searching orders for user: {} from: {} — page: {}, size: {}", userId, from, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orderPage = orderRepository.searchByUserIdAndDateRange(userId, from, pageable);

        PagedResponse<OrderResponse> response = toPagedResponse(orderPage);
        log.info("Found {} orders for user: {} from date (page {}/{}) — took {} ms",
                orderPage.getNumberOfElements(), userId, page, orderPage.getTotalPages(), System.currentTimeMillis() - start);
        return response;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PagedResponse<OrderResponse> toPagedResponse(Page<Order> orderPage) {
        List<OrderResponse> content = orderPage.getContent().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return new PagedResponse<>(
                content,
                orderPage.getNumber(),
                orderPage.getSize(),
                orderPage.getTotalElements(),
                orderPage.getTotalPages(),
                orderPage.isLast()
        );
    }

    private OrderResponse mapToResponse(Order order) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setUserId(order.getUserId());
        response.setAccountId(order.getAccountId());
        response.setSymbol(order.getSymbol());
        response.setQuantity(order.getQuantity());
        response.setPrice(order.getPrice());
        response.setOrderType(order.getOrderType());
        response.setStatus(order.getStatus());
        response.setCreatedAt(order.getCreatedAt());
        response.setUpdatedAt(order.getUpdatedAt());
        return response;
    }
}

