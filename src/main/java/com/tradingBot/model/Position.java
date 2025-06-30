package com.tradingBot.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class Position {
    private String symbol;
    private Integer quantity;
    private BigDecimal costBasis;
    private BigDecimal marketValue;
    private BigDecimal unrealizedPl;
    private BigDecimal unrealizedPlPercent;
}