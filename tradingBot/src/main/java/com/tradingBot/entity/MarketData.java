package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Entity
@Table(name = "market_data")
@Data
public class MarketData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private BigDecimal price;
    private BigDecimal bid;
    private BigDecimal ask;
    private Integer bidSize;
    private Integer askSize;
    private Long volume;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal previousClose;
    private LocalDateTime timestamp;
}