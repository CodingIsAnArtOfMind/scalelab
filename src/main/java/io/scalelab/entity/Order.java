package io.scalelab.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Relationships:
 * - Many Orders → One User (N:1) — a user places many orders
 * - Many Orders → One Account (N:1) — orders are placed from a specific account
 *
 * Note: user_id and account_id are stored as raw Longs, NOT as JPA @ManyToOne mappings.
 * This is intentional for Phase 1 to keep queries explicit and observable.
 * In later experiments, this will help us observe the N+1 query problem.
 */
@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "account_id")
    private Long accountId;

    private String symbol;

    private Integer quantity;

    private BigDecimal price;

    @Column(name = "order_type")
    private String orderType;

    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

