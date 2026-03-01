package io.scalelab.repository;

import io.scalelab.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Intentionally no index on user_id — this will be slow on large datasets
    List<Order> findByUserId(Long userId);

    // Search by status — no index on status column → full table scan
    List<Order> findByStatusOrderByCreatedAtDesc(String status);

    // Search by user + status — no index on either → full table scan
    List<Order> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    // Search by status + date range — heaviest query, scans everything
    // No index on status, no index on created_at → full table scan + sort
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt >= :from ORDER BY o.createdAt DESC")
    List<Order> searchByStatusAndDateRange(@Param("status") String status, @Param("from") LocalDateTime from);

    // Search by user + date range — another heavy query
    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.createdAt >= :from ORDER BY o.createdAt DESC")
    List<Order> searchByUserIdAndDateRange(@Param("userId") Long userId, @Param("from") LocalDateTime from);
}

