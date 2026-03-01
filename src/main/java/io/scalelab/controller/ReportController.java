package io.scalelab.controller;

import io.scalelab.dto.ReportResponse;
import io.scalelab.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/{userId}")
    public ResponseEntity<ReportResponse> generateReport(@PathVariable Long userId) {
        ReportResponse response = reportService.generateReport(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<ReportResponse>> getReportsByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(reportService.getReportsByUserId(userId));
    }
}

