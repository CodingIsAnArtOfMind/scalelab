package io.scalelab.controller;

import io.scalelab.dto.CreateOrderRequest;
import io.scalelab.dto.OrderResponse;
import io.scalelab.dto.PagedResponse;
import io.scalelab.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.placeOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // =========================================================================
    // Phase 3 — Paginated + Cached endpoints
    // All list endpoints now accept ?page=0&size=20
    // Default: page=0, size=20 (returns only 20 rows per page)
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

