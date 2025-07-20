package com.tradingBot.entity;

import com.tradingBot.model.MarketRegime;
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
    private LocalDateTime expirationTime;
    private String status; // PENDING, EXECUTED, EXPIRED, FAILED, INVALIDATED

    private BigDecimal entryPrice;
    @Column(name = "realized_pnl", precision = 10, scale = 2)
    private BigDecimal realizedPnl;

    @Column(name = "entry_assumption_price", precision = 10, scale = 4)
    private BigDecimal entryAssumptionPrice;

    @Column(name = "market_regime")
    @Enumerated(EnumType.STRING)
    private MarketRegime marketRegime;

    // Getters and setters
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }

    public BigDecimal getEntryAssumptionPrice() { return entryAssumptionPrice; }
    public void setEntryAssumptionPrice(BigDecimal entryAssumptionPrice) { this.entryAssumptionPrice = entryAssumptionPrice; }

    public MarketRegime getMarketRegime() { return marketRegime; }
    public void setMarketRegime(MarketRegime marketRegime) { this.marketRegime = marketRegime; }
    // Link to executed trade
    @Column(name = "trade_id")
    private Long tradeId;

    @Column(name = "market_trend")
    private String marketTrend;

    // Add getter and setter
    public String getMarketTrend() {
        return marketTrend;
    }

    public void setMarketTrend(String marketTrend) {
        this.marketTrend = marketTrend;
    }

    private LocalDateTime createdAt;
    private LocalDateTime executionStartTime;
    private LocalDateTime executionEndTime;
    private BigDecimal originalOptionPrice;
    private String executionNotes;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "generated_by")
    private String generatedBy;

    @Column(name = "notes")
    private String notes;

    // Add getters and setters
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getOptionSymbol() {
        return this.optionSymbol; // Assuming field is 'optionSymbol'
    }

}
