package io.scalelab.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderResponse implements Serializable {

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

