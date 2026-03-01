package io.scalelab.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * Relationships:
 * - Many Reports → One User (N:1) — reports are generated per user
 *
 * Note: user_id is stored as a raw Long, NOT as a JPA @ManyToOne mapping.
 * Reports are computed on-the-fly by scanning the orders table — intentionally
 * slow for Phase 1 to demonstrate OLTP vs OLAP separation later.
 */
@Entity
@Table(name = "reports")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "total_orders")
    private Integer totalOrders;

    @Column(name = "total_volume")
    private Double totalVolume;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.generatedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

