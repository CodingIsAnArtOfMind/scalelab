package io.scalelab.service;

import io.scalelab.dto.CreateOrderRequest;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    /**
     * Place order — evicts cached orders for this user since data changed
     */
    @CacheEvict(value = "orders", allEntries = true)
    public OrderResponse placeOrder(CreateOrderRequest request) {
        long start = System.currentTimeMillis();
        log.info("Placing order for user: {}, symbol: {}, type: {}",
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
        log.info("Order placed with id: {}, status: {} — took {} ms", saved.getId(), saved.getStatus(), System.currentTimeMillis() - start);

        return mapToResponse(saved);
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

