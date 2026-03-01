package io.scalelab.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReportResponse {

    private Long id;
    private Long userId;
    private Integer totalOrders;
    private Double totalVolume;
    private LocalDateTime generatedAt;
    private LocalDateTime updatedAt;
}

