package io.scalelab.repository;

import io.scalelab.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // === Phase 1/2 queries (kept for backward compatibility) ===

    List<Order> findByUserId(Long userId);

    List<Order> findByStatusOrderByCreatedAtDesc(String status);

    List<Order> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt >= :from ORDER BY o.createdAt DESC")
    List<Order> searchByStatusAndDateRange(@Param("status") String status, @Param("from") LocalDateTime from);

    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.createdAt >= :from ORDER BY o.createdAt DESC")
    List<Order> searchByUserIdAndDateRange(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    // === Phase 3 — Paginated queries ===
    // Same queries but return Page<Order> instead of List<Order>
    // Spring Data automatically adds LIMIT/OFFSET + COUNT query

    Page<Order> findByUserId(Long userId, Pageable pageable);

    Page<Order> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<Order> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status, Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt >= :from ORDER BY o.createdAt DESC")
    Page<Order> searchByStatusAndDateRange(@Param("status") String status, @Param("from") LocalDateTime from, Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.createdAt >= :from ORDER BY o.createdAt DESC")
    Page<Order> searchByUserIdAndDateRange(@Param("userId") Long userId, @Param("from") LocalDateTime from, Pageable pageable);
}

