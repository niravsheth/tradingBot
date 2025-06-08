package com.tradingBot.entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Entity
@Table(name = "trades")
@Data
public class Trade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String optionSymbol;
    private String type; // CALL or PUT
    private BigDecimal strikePrice;
    private LocalDateTime expirationDate;
    private String action; // BUY or SELL
    private Integer quantity;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private BigDecimal profit;
    private String status; // OPEN, CLOSED, CANCELLED
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private String strategy;
    private BigDecimal impliedVolatility;
    private Integer volume;
    private BigDecimal delta;
    private BigDecimal gamma;
    private BigDecimal theta;
}
