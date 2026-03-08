package io.scalelab.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class ReportResponse implements Serializable {

    private Long id;
    private Long userId;
    private Integer totalOrders;
    private Double totalVolume;
    private LocalDateTime generatedAt;
    private LocalDateTime updatedAt;
}

