package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "account_metrics")
@Data
public class AccountMetrics {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private BigDecimal totalValue;
    private BigDecimal cashBalance;
    private BigDecimal dayPnl;
    private BigDecimal totalPnl;
    private LocalDateTime timestamp;
}