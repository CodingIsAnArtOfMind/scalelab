package io.scalelab.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderResponse {

    private Long id;
    private Long userId;
    private Long accountId;
    private String symbol;
    private Integer quantity;
    private BigDecimal price;
    private String orderType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

