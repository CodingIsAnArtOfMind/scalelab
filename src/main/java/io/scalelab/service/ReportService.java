package io.scalelab.service;

import io.scalelab.dto.ReportResponse;
import io.scalelab.entity.Order;
import io.scalelab.entity.Report;
import io.scalelab.exception.ResourceNotFoundException;
import io.scalelab.repository.OrderRepository;
import io.scalelab.repository.ReportRepository;
import io.scalelab.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    @CacheEvict(value = "reports", key = "#userId")
    public ReportResponse generateReport(Long userId) {
        long start = System.currentTimeMillis();
        log.info("Generating report for user id: {}", userId);

        // Verify user exists
        userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        // Intentionally computing report on-the-fly every time
        // No caching, no materialized view — this is a synchronous bottleneck
        List<Order> allOrders = orderRepository.findByUserId(userId);

        int totalOrders = allOrders.size();
        double totalVolume = allOrders.stream()
                .mapToDouble(o -> o.getPrice().doubleValue() * o.getQuantity())
                .sum();

        log.info("Report computed: totalOrders={}, totalVolume={} for user: {}", totalOrders, totalVolume, userId);

        Report report = new Report();
        report.setUserId(userId);
        report.setTotalOrders(totalOrders);
        report.setTotalVolume(totalVolume);

        Report saved = reportRepository.save(report);
        log.info("Report saved with id: {} — took {} ms", saved.getId(), System.currentTimeMillis() - start);

        return mapToResponse(saved);
    }

    @Cacheable(value = "reports", key = "#userId")
    public List<ReportResponse> getReportsByUserId(Long userId) {
        long start = System.currentTimeMillis();
        log.info("Fetching reports for user id: {}", userId);
        // Intentionally no index, full table scan
        List<Report> reports = reportRepository.findByUserId(userId);
        log.info("Found {} reports for user: {} — took {} ms", reports.size(), userId, System.currentTimeMillis() - start);
        return reports.stream().map(this::mapToResponse).toList();
    }

    private ReportResponse mapToResponse(Report report) {
        ReportResponse response = new ReportResponse();
        response.setId(report.getId());
        response.setUserId(report.getUserId());
        response.setTotalOrders(report.getTotalOrders());
        response.setTotalVolume(report.getTotalVolume());
        response.setGeneratedAt(report.getGeneratedAt());
        response.setUpdatedAt(report.getUpdatedAt());
        return response;
    }
}

