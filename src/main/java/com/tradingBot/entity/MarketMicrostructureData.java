package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "market_microstructure_data")
@Data
public class MarketMicrostructureData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private BigDecimal bidAskSpread;
    private BigDecimal orderImbalance;
    private Double toxicityScore;
    private Double liquidityScore;
    private Integer quoteCount;
    private Integer tradeCount;
    private LocalDateTime timestamp;
}