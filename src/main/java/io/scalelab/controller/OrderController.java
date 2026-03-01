package io.scalelab.controller;

import io.scalelab.dto.CreateOrderRequest;
import io.scalelab.dto.OrderResponse;
import io.scalelab.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

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

    @GetMapping("/{userId}")
    public ResponseEntity<List<OrderResponse>> getOrdersByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.getOrdersByUserId(userId));
    }

    // =========================================================================
    // BOTTLENECK APIs — for load testing experiments
    // All queries below hit columns with NO indexes → full table scans
    // =========================================================================

    /**
     * GET /orders/search?status=EXECUTED&from=2026-02-01T00:00:00
     * Heaviest query — filters by status + date range + sorts
     * No index on status or created_at → full table scan
     */
    @GetMapping("/search")
    public ResponseEntity<List<OrderResponse>> searchOrders(
            @RequestParam String status,
            @RequestParam String from) {
        LocalDateTime fromDate = LocalDateTime.parse(from);
        return ResponseEntity.ok(orderService.searchOrders(status, fromDate));
    }

    /**
     * GET /orders/status/{status}
     * Filters all orders by status — no index → full table scan
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<OrderResponse>> getOrdersByStatus(@PathVariable String status) {
        return ResponseEntity.ok(orderService.getOrdersByStatus(status));
    }

    /**
     * GET /orders/user/{userId}/status/{status}
     * Filters by user + status — no index on either → full table scan
     */
    @GetMapping("/user/{userId}/status/{status}")
    public ResponseEntity<List<OrderResponse>> getOrdersByUserIdAndStatus(
            @PathVariable Long userId,
            @PathVariable String status) {
        return ResponseEntity.ok(orderService.getOrdersByUserIdAndStatus(userId, status));
    }

    /**
     * GET /orders/user/{userId}/recent?from=2026-02-01T00:00:00
     * Filters by user + date range — no index → full table scan
     */
    @GetMapping("/user/{userId}/recent")
    public ResponseEntity<List<OrderResponse>> getRecentOrdersByUser(
            @PathVariable Long userId,
            @RequestParam String from) {
        LocalDateTime fromDate = LocalDateTime.parse(from);
        return ResponseEntity.ok(orderService.searchOrdersByUserAndDate(userId, fromDate));
    }
}

