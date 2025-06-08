package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Entity
@Table(name = "signals")
@Data
public class Signal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String optionSymbol;
    private String signalType; // BUY, SELL, HOLD
    private BigDecimal targetPrice;
    private BigDecimal stopLoss;
    private String strategy;
    private Double confidence;
    private LocalDateTime timestamp;
    private Boolean executed;
    private String reason;
}
