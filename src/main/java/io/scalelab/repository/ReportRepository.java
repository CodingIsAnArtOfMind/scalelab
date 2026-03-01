package io.scalelab.repository;

import io.scalelab.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {

    // Intentionally no index — will scan full table
    List<Report> findByUserId(Long userId);
}

