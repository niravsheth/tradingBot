package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "pending_flows")
@Data
public class PendingFlow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String optionSymbol;
    private BigDecimal flowSize;
    private String flowType;
    private LocalDateTime detectedAt;
    private BigDecimal detectionPrice;
    private BigDecimal underlyingPrice;
    private String status;
    private Integer confirmationWindow;
    private BigDecimal requiredMove;
    private LocalDateTime confirmedAt;
    private BigDecimal confirmationPrice;
    private String confirmationReason;
}