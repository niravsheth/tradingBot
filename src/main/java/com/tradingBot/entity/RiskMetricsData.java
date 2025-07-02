package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "risk_metrics_data")
@Data
public class RiskMetricsData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id")
    private Long tradeId;

    private BigDecimal positionVar;
    private BigDecimal portfolioVar;
    private Double sharpeRatio;
    private Double correlationScore;
    private BigDecimal optimalPositionSize;
    private LocalDateTime calculatedAt;
}