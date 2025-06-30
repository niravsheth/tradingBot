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
    @Column(name = "target_price")
    private BigDecimal targetPrice;

    @Column(name = "stop_loss")
    private BigDecimal stopLoss;

    @Column(name = "original_signal_id")
    private Long originalSignalId;

    @Column(name = "exit_strategy")
    private String exitStrategy;

    // Add these fields to your existing Trade.java entity

    // Link to original signal
    @Column(name = "signal_id")
    private Long signalId;

    // Track broker order ID
    @Column(name = "order_id")
    private String orderId;

    // Track close reason
    @Column(name = "close_reason")
    private String closeReason;

    // Track realized P&L separately
    @Column(name = "realized_pnl")
    private BigDecimal realizedPnl;

    // Track unrealized P&L (from broker)
    @Column(name = "unrealized_pnl")
    private BigDecimal unrealizedPnl;

    @Column(name = "last_sync_time")
    private LocalDateTime lastSyncTime;


    @Column(name = "current_price")
    private BigDecimal currentPrice;

    // Add getters/setters

    public BigDecimal getTarget() { return targetPrice; }
    public void setTarget(BigDecimal target) { this.targetPrice = target; }


    // Add getters and setters for all new fields
    public Long getSignalId() {
        return signalId;
    }

    public void setSignalId(Long signalId) {
        this.signalId = signalId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(String closeReason) {
        this.closeReason = closeReason;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getTargetPrice() {
        return targetPrice;
    }

    public void setTargetPrice(BigDecimal targetPrice) {
        this.targetPrice = targetPrice;
    }

    public BigDecimal getStopLoss() {
        return stopLoss;
    }

    public void setStopLoss(BigDecimal stopLoss) {
        this.stopLoss = stopLoss;
    }

    @Column(name = "highest_price")
    private BigDecimal highestPrice;

    @Column(name = "original_stop")
    private BigDecimal originalStop;

    @Column(name = "original_target")
    private BigDecimal originalTarget;


    @Column(name = "exit_reason")
    private String exitReason;


    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private BigDecimal target;
    @Column(name = "trailing_activated")
    private Boolean trailingActivated = false;

    @Column(name = "last_adjustment_time")
    private LocalDateTime lastAdjustmentTime;

    public boolean isPaperTrade() {
        return optionSymbol != null && optionSymbol.startsWith("PAPER_");
    }


}
