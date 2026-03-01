package io.scalelab.service;

import io.scalelab.dto.CreateOrderRequest;
import io.scalelab.dto.OrderResponse;
import io.scalelab.entity.Order;
import io.scalelab.exception.ResourceNotFoundException;
import io.scalelab.repository.AccountRepository;
import io.scalelab.repository.OrderRepository;
import io.scalelab.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public OrderResponse placeOrder(CreateOrderRequest request) {
        long start = System.currentTimeMillis();
        log.info("Placing order for user: {}, symbol: {}, type: {}",
                request.getUserId(), request.getSymbol(), request.getOrderType());

        // Verify user exists
        userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + request.getUserId()));

        // Verify account exists
        accountRepository.findById(request.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + request.getAccountId()));

        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setAccountId(request.getAccountId());
        order.setSymbol(request.getSymbol());
        order.setQuantity(request.getQuantity());
        order.setPrice(request.getPrice());
        order.setOrderType(request.getOrderType());
        order.setStatus("PENDING"); // All orders start as PENDING — no async processing

        Order saved = orderRepository.save(order);
        log.info("Order placed with id: {}, status: {} — took {} ms", saved.getId(), saved.getStatus(), System.currentTimeMillis() - start);

        return mapToResponse(saved);
    }

    public List<OrderResponse> getOrdersByUserId(Long userId) {
        long start = System.currentTimeMillis();
        log.info("Fetching orders for user id: {}", userId);
        // Intentionally no index on user_id — will do full table scan
        // Intentionally no pagination — returns ALL orders
        List<Order> orders = orderRepository.findByUserId(userId);
        log.info("Found {} orders for user: {} — took {} ms", orders.size(), userId, System.currentTimeMillis() - start);
        return orders.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /**
     * Search orders by status — no index on status → full table scan
     * Sorted by created_at DESC — no index on created_at → in-memory sort
     */
    public List<OrderResponse> getOrdersByStatus(String status) {
        long start = System.currentTimeMillis();
        log.info("Searching orders by status: {}", status);
        List<Order> orders = orderRepository.findByStatusOrderByCreatedAtDesc(status);
        log.info("Found {} orders with status: {} — took {} ms", orders.size(), status, System.currentTimeMillis() - start);
        return orders.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /**
     * Search orders by user + status — no index on user_id or status → full table scan
     */
    public List<OrderResponse> getOrdersByUserIdAndStatus(Long userId, String status) {
        long start = System.currentTimeMillis();
        log.info("Searching orders for user: {} with status: {}", userId, status);
        List<Order> orders = orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        log.info("Found {} orders for user: {} with status: {} — took {} ms", orders.size(), userId, status, System.currentTimeMillis() - start);
        return orders.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /**
     * Search orders by status + date range — HEAVIEST QUERY
     * Scans entire table, filters by status AND created_at, sorts result
     * No index on any of these columns → guaranteed full table scan
     */
    public List<OrderResponse> searchOrders(String status, LocalDateTime from) {
        long start = System.currentTimeMillis();
        log.info("Searching orders — status: {}, from: {}", status, from);
        List<Order> orders = orderRepository.searchByStatusAndDateRange(status, from);
        log.info("Search found {} orders — took {} ms", orders.size(), System.currentTimeMillis() - start);
        return orders.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /**
     * Search orders by user + date range
     * No index on user_id or created_at → full table scan
     */
    public List<OrderResponse> searchOrdersByUserAndDate(Long userId, LocalDateTime from) {
        long start = System.currentTimeMillis();
        log.info("Searching orders for user: {} from: {}", userId, from);
        List<Order> orders = orderRepository.searchByUserIdAndDateRange(userId, from);
        log.info("Found {} orders for user: {} from date — took {} ms", orders.size(), userId, System.currentTimeMillis() - start);
        return orders.stream().map(this::mapToResponse).collect(Collectors.toList());
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

