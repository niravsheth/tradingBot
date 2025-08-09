package com.tradingBot.entity;

import com.tradingBot.model.MarketRegime;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Complete Trade Entity with all fields for comprehensive tracking
 */
@Entity
@Table(name = "trades")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =====================================
    // BASIC TRADE INFORMATION
    // =====================================
    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "option_symbol")
    private String optionSymbol;

    @Column(name = "strategy", nullable = false)
    private String strategy;

    @Column(name = "side") // CALL, PUT
    private String side;

    @Column(name = "type") // CALL or PUT (alternative to side)
    private String type;

    @Column(name = "action") // BUY or SELL
    private String action;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "volume")
    private Integer volume;

    @Column(name = "strike_price", precision = 10, scale = 4)
    private BigDecimal strikePrice;

    // =====================================
    // PRICING INFORMATION
    // =====================================
    @Column(name = "entry_price", precision = 10, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 10, scale = 4)
    private BigDecimal exitPrice;

    @Column(name = "current_price", precision = 10, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "highest_price", precision = 10, scale = 4)
    private BigDecimal highestPrice;

    @Column(name = "lowest_price", precision = 10, scale = 4)
    private BigDecimal lowestPrice;

    @Column(name = "target_price", precision = 10, scale = 4)
    private BigDecimal targetPrice;

    @Column(name = "original_target", precision = 10, scale = 4)
    private BigDecimal originalTarget;

    @Column(name = "stop_loss", precision = 10, scale = 4)
    private BigDecimal stopLoss;

    @Column(name = "original_stop", precision = 10, scale = 4)
    private BigDecimal originalStop;

    @Column(name = "fill_price", precision = 10, scale = 4)
    private BigDecimal fillPrice;

    @Column(name = "bid_at_entry", precision = 10, scale = 4)
    private BigDecimal bidAtEntry;

    @Column(name = "ask_at_entry", precision = 10, scale = 4)
    private BigDecimal askAtEntry;

    @Column(name = "spread_at_entry", precision = 10, scale = 4)
    private BigDecimal spreadAtEntry;

    // =====================================
    // P&L AND PERFORMANCE
    // =====================================
    @Column(name = "realized_pnl", precision = 12, scale = 2)
    private BigDecimal realizedPnl;

    @Column(name = "unrealized_pnl", precision = 12, scale = 2)
    private BigDecimal unrealizedPnl;

    @Column(name = "profit", precision = 12, scale = 2)
    private BigDecimal profit;

    @Column(name = "max_profit_reached", precision = 12, scale = 2)
    private BigDecimal maxProfitReached;

    @Column(name = "max_drawdown", precision = 12, scale = 2)
    private BigDecimal maxDrawdown;

    @Column(name = "profit_percentage")
    private Double profitPercentage;

    @Column(name = "max_profit_percentage")
    private Double maxProfitPercentage;

    @Column(name = "max_drawdown_percentage")
    private Double maxDrawdownPercentage;

    // =====================================
    // TIMESTAMPS
    // =====================================
    @Column(name = "created_at")
    private ZonedDateTime createdAt;

    @Column(name = "closed_at")
    private ZonedDateTime closedAt;

    @Column(name = "entry_time")
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @Column(name = "last_sync_time")
    private LocalDateTime lastSyncTime;

    @Column(name = "last_adjustment_time")
    private LocalDateTime lastAdjustmentTime;

    @Column(name = "signal_generated_at")
    private LocalDateTime signalGeneratedAt;

    @Column(name = "execution_attempted_at")
    private LocalDateTime executionAttemptedAt;

    @Column(name = "order_filled_at")
    private LocalDateTime orderFilledAt;

    // =====================================
    // EXECUTION AND ORDER TRACKING
    // =====================================
    @Column(name = "order_id")
    private String orderId;

    @Column(name = "order_status")
    private String orderStatus;

    @Column(name = "order_side")
    private String orderSide;

    @Column(name = "order_type") // MARKET, LIMIT
    private String orderType;

    @Column(name = "order_duration") // DAY, GTC
    private String orderDuration;

    @Column(name = "execution_price", precision = 10, scale = 4)
    private BigDecimal executionPrice;

    @Column(name = "actual_quantity")
    private Integer actualQuantity;

    @Column(name = "commission", precision = 10, scale = 2)
    private BigDecimal commission;

    @Column(name = "fees", precision = 10, scale = 2)
    private BigDecimal fees;

    // =====================================
    // FAILURE AND ERROR TRACKING
    // =====================================
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "execution_errors", columnDefinition = "TEXT")
    private String executionErrors;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    // =====================================
    // BAYESIAN ANALYSIS FIELDS
    // =====================================
    @Column(name = "bayesian_probability")
    private Double bayesianProbability;

    @Column(name = "signal_confidence")
    private Double signalConfidence;

    @Column(name = "original_confidence")
    private Double originalConfidence;

    @Column(name = "adjusted_confidence")
    private Double adjustedConfidence;

    @Column(name = "prior_probability")
    private Double priorProbability;

    @Column(name = "likelihood")
    private Double likelihood;

    @Column(name = "posterior_probability")
    private Double posteriorProbability;

    @Column(name = "confidence_gap")
    private Double confidenceGap;

    // ML Learning Metadata
    @Column(name = "feature_importance_json", columnDefinition = "TEXT")
    private String featureImportanceJson;

    @Column(name = "ml_weights_json", columnDefinition = "TEXT")
    private String mlWeightsJson;

    @Column(name = "prediction_error")
    private Double predictionError;

    @Column(name = "confidence_bucket")
    private Integer confidenceBucket; // 0-19 for 5% buckets

    @Column(name = "trade_outcome")
    private String tradeOutcome; // SUCCESS, FAILURE

    @Column(name = "outcome_predicted_correctly")
    private Boolean outcomePredictedCorrectly;

    // Analysis Timestamps
    @Column(name = "bayesian_analysis_timestamp")
    private ZonedDateTime bayesianAnalysisTimestamp;

    @Column(name = "trade_closed_timestamp")
    private ZonedDateTime tradeClosedTimestamp;

    // =====================================
    // THRESHOLD OPTIMIZATION FIELDS
    // =====================================
    @Column(name = "applied_threshold")
    private Double appliedThreshold;

    @Column(name = "market_threshold")
    private Double marketThreshold;

    @Column(name = "personal_threshold")
    private Double personalThreshold;

    @Column(name = "threshold_weight")
    private Double thresholdWeight;

    @Column(name = "threshold_optimization_method")
    private String thresholdOptimizationMethod;

    @Column(name = "threshold_calculation_timestamp")
    private ZonedDateTime thresholdCalculationTimestamp;

    @Column(name = "optimal_threshold_at_time")
    private Double optimalThresholdAtTime;

    @Column(name = "expected_success_rate")
    private Double expectedSuccessRate;

    @Column(name = "actual_success_rate")
    private Double actualSuccessRate;

    @Column(name = "threshold_confidence")
    private Double thresholdConfidence;

    @Column(name = "threshold_deviation")
    private Double thresholdDeviation; // Applied - Optimal

    @Column(name = "threshold_accuracy")
    private Double thresholdAccuracy; // 1.0 if threshold was correct, 0.0 if wrong

    @Column(name = "threshold_performance_impact")
    private Double thresholdPerformanceImpact;

    // =====================================
    // MARKET CONTEXT FIELDS
    // =====================================
    @Column(name = "market_regime")
    @Enumerated(EnumType.STRING)
    private MarketRegime marketRegime;

    @Column(name = "market_trend") // UP, DOWN, NEUTRAL
    private String marketTrend;

    @Column(name = "market_trend_strength")
    private Double marketTrendStrength;

    @Column(name = "time_slot") // MORNING, MIDDAY, AFTERNOON, CLOSE
    private String timeSlot;

    @Column(name = "expiration_date")
    private ZonedDateTime expirationDate;

    @Column(name = "days_to_expiry")
    private Integer daysToExpiry;

    @Column(name = "minutes_to_expiry")
    private Integer minutesToExpiry;

    @Column(name = "time_decay_factor")
    private Double timeDecayFactor;

    @Column(name = "vix_at_entry")
    private Double vixAtEntry;

    @Column(name = "market_breadth_at_entry")
    private Double marketBreadthAtEntry;

    @Column(name = "underlying_price_at_entry", precision = 10, scale = 4)
    private BigDecimal underlyingPriceAtEntry;

    @Column(name = "underlying_price_at_exit", precision = 10, scale = 4)
    private BigDecimal underlyingPriceAtExit;

    @Column(name = "underlying_move_percentage")
    private Double underlyingMovePercentage;

    // =====================================
    // OPTION GREEKS
    // =====================================
    @Column(name = "implied_volatility")
    private Double impliedVolatility;

    @Column(name = "iv_rank")
    private Double ivRank;

    @Column(name = "iv_percentile")
    private Double ivPercentile;

    @Column(name = "delta")
    private Double delta;

    @Column(name = "gamma")
    private Double gamma;

    @Column(name = "theta")
    private Double theta;

    @Column(name = "vega")
    private Double vega;

    @Column(name = "rho")
    private Double rho;

    // Greeks at exit
    @Column(name = "delta_at_exit")
    private Double deltaAtExit;

    @Column(name = "gamma_at_exit")
    private Double gammaAtExit;

    @Column(name = "theta_at_exit")
    private Double thetaAtExit;

    @Column(name = "vega_at_exit")
    private Double vegaAtExit;

    // =====================================
    // TECHNICAL ANALYSIS DATA
    // =====================================
    @Column(name = "atr_ratio")
    private Double atrRatio;

    @Column(name = "volume_ratio")
    private Double volumeRatio;

    @Column(name = "momentum_strength")
    private Double momentumStrength;

    @Column(name = "vwap_deviation")
    private Double vwapDeviation;

    @Column(name = "constituent_alignment")
    private Double constituentAlignment;

    @Column(name = "rsi_at_entry")
    private Double rsiAtEntry;

    @Column(name = "macd_at_entry")
    private Double macdAtEntry;

    @Column(name = "bollinger_position")
    private Double bollingerPosition;

    @Column(name = "support_level", precision = 10, scale = 4)
    private BigDecimal supportLevel;

    @Column(name = "resistance_level", precision = 10, scale = 4)
    private BigDecimal resistanceLevel;

    // =====================================
    // POSITION MANAGEMENT
    // =====================================
    @Column(name = "position_size_percentage")
    private Double positionSizePercentage;

    @Column(name = "portfolio_heat")
    private Double portfolioHeat;

    @Column(name = "correlation_adjustment")
    private Double correlationAdjustment;

    @Column(name = "trailing_activated")
    private Boolean trailingActivated;

    @Column(name = "trailing_stop_distance", precision = 10, scale = 4)
    private BigDecimal trailingStopDistance;

    @Column(name = "stop_adjustments_count")
    private Integer stopAdjustmentsCount;

    @Column(name = "target_adjustments_count")
    private Integer targetAdjustmentsCount;

    // =====================================
    // TRADE STATUS AND MANAGEMENT
    // =====================================
    @Column(name = "status") // OPEN, CLOSED, FAILED, BAYESIAN_REJECTED, etc.
    private String status;

    @Column(name = "close_reason") // PROFIT_TARGET, STOP_LOSS, EXPIRATION, MANUAL
    private String closeReason;

    @Column(name = "exit_reason")
    private String exitReason;

    @Column(name = "exit_strategy")
    private String exitStrategy;

    @Column(name = "auto_closed")
    private Boolean autoClosed;

    @Column(name = "manual_intervention")
    private Boolean manualIntervention;

    // =====================================
    // RISK MANAGEMENT
    // =====================================
    @Column(name = "max_risk", precision = 12, scale = 2)
    private BigDecimal maxRisk;

    @Column(name = "max_profit_potential", precision = 12, scale = 2)
    private BigDecimal maxProfitPotential;

    @Column(name = "risk_reward_ratio")
    private Double riskRewardRatio;

    @Column(name = "actual_risk_reward_ratio")
    private Double actualRiskRewardRatio;

    @Column(name = "kelly_fraction")
    private Double kellyFraction;

    @Column(name = "sharpe_ratio")
    private Double sharpeRatio;

    @Column(name = "var_95")
    private Double var95;

    @Column(name = "expected_value")
    private Double expectedValue;

    // =====================================
    // LINKING AND TRACKING
    // =====================================
    @Column(name = "signal_id")
    private Long signalId;

    @Column(name = "original_signal_id")
    private Long originalSignalId;

    @Column(name = "parent_trade_id")
    private Long parentTradeId;

    @Column(name = "strategy_instance_id")
    private String strategyInstanceId;

    @Column(name = "execution_session_id")
    private String executionSessionId;

    // =====================================
    // LEARNING AND ADAPTATION
    // =====================================
    @Column(name = "learning_phase") // MARKET_ONLY, TRANSITION, PERSONAL_INTEGRATED
    private String learningPhase;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "feature_set_version")
    private String featureSetVersion;

    @Column(name = "training_data_size")
    private Integer trainingDataSize;

    @Column(name = "cross_validation_score")
    private Double crossValidationScore;

    @Column(name = "overfitting_score")
    private Double overfittingScore;

    // =====================================
    // PERFORMANCE ATTRIBUTION
    // =====================================
    @Column(name = "alpha_contribution")
    private Double alphaContribution;

    @Column(name = "beta_contribution")
    private Double betaContribution;

    @Column(name = "timing_contribution")
    private Double timingContribution;

    @Column(name = "selection_contribution")
    private Double selectionContribution;

    @Column(name = "luck_factor")
    private Double luckFactor;

    // =====================================
    // METADATA STORAGE
    // =====================================
    @ElementCollection
    @CollectionTable(name = "trade_metadata", joinColumns = @JoinColumn(name = "trade_id"))
    @MapKeyColumn(name = "metadata_key")
    @Column(name = "metadata_value")
    private Map<String, String> metadataMap = new HashMap<>();

    // =====================================
    // HELPER METHODS
    // =====================================

    /**
     * Check if trade was successful
     */
    public boolean isSuccessful() {
        return realizedPnl != null && realizedPnl.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Get confidence value with fallback logic
     */
    public Double getEffectiveConfidence() {
        if (adjustedConfidence != null) return adjustedConfidence;
        if (bayesianProbability != null) return bayesianProbability;
        if (signalConfidence != null) return signalConfidence;
        if (originalConfidence != null) return originalConfidence;
        return null;
    }

    /**
     * Calculate trade duration in minutes
     */
    public Long getTradeDurationMinutes() {
        if (createdAt == null || closedAt == null) return null;
        return java.time.Duration.between(createdAt, closedAt).toMinutes();
    }

    /**
     * Get return percentage
     */
    public Double getReturnPercentage() {
        if (entryPrice == null || realizedPnl == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        int qty = quantity != null ? quantity : 1;
        return realizedPnl.divide(entryPrice.multiply(BigDecimal.valueOf(qty)), 6, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Check if threshold prediction was accurate
     */
    public Boolean wasThresholdAccurate() {
        if (appliedThreshold == null || signalConfidence == null) return null;

        boolean signalPassedThreshold = signalConfidence >= appliedThreshold;
        boolean tradeWasSuccessful = isSuccessful();

        return signalPassedThreshold == tradeWasSuccessful;
    }

    /**
     * Check if this is a failed trade (not executed)
     */
    public boolean isFailed() {
        return "FAILED".equals(status) ||
                "BAYESIAN_REJECTED".equals(status) ||
                "ORDER_FAILED".equals(status) ||
                "QUOTE_FAILED".equals(status) ||
                "EXECUTION_ERROR".equals(status);
    }

    /**
     * Check if this is an active trade (being monitored)
     */
    public boolean isActive() {
        return "OPEN".equals(status);
    }

    /**
     * Calculate actual vs expected performance
     */
    public Double getPerformanceVsExpected() {
        if (expectedValue == null || profitPercentage == null) return null;
        return profitPercentage - expectedValue;
    }

    // =====================================
    // METADATA METHODS
    // =====================================

    public Map<String, Object> getMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        if (metadataMap != null) {
            metadataMap.forEach((key, value) -> metadata.put(key, value));
        }
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadataMap = new HashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> this.metadataMap.put(key, value != null ? value.toString() : null));
        }
    }

    public void addMetadata(String key, String value) {
        if (metadataMap == null) {
            metadataMap = new HashMap<>();
        }
        metadataMap.put(key, value);
    }

    public String getMetadataValue(String key) {
        if (metadataMap == null) return null;
        return metadataMap.get(key);
    }

    // =====================================
    // COMPATIBILITY METHODS
    // =====================================

    public BigDecimal getTarget() {
        if (targetPrice != null) return targetPrice;
        return originalTarget;
    }

    public void setTarget(BigDecimal target) {
        this.targetPrice = target;
        if (this.originalTarget == null) {
            this.originalTarget = target;
        }
    }

    public BigDecimal getProfit() {
        if (profit != null) return profit;
        return realizedPnl;
    }

    public void setProfit(BigDecimal profit) {
        this.profit = profit;
        if (this.realizedPnl == null) {
            this.realizedPnl = profit;
        }
    }

    public LocalDateTime getEntryTime() {
        if (entryTime != null) return entryTime;
        return createdAt != null ? createdAt.toLocalDateTime() : null;
    }

    public void setEntryTime(LocalDateTime entryTime) {
        this.entryTime = entryTime;
        if (this.createdAt == null && entryTime != null) {
            this.createdAt = entryTime.atZone(java.time.ZoneId.systemDefault());
        }
    }

    public LocalDateTime getExitTime() {
        if (exitTime != null) return exitTime;
        return closedAt != null ? closedAt.toLocalDateTime() : null;
    }

    public void setExitTime(LocalDateTime exitTime) {
        this.exitTime = exitTime;
        if (this.closedAt == null && exitTime != null) {
            this.closedAt = exitTime.atZone(java.time.ZoneId.systemDefault());
        }
    }

    public Boolean getTrailingActivated() {
        return trailingActivated != null ? trailingActivated : false;
    }

    public Integer getQuantity() {
        return quantity != null ? quantity : 1;
    }

    public Boolean getAutoClosed() {
        return autoClosed != null ? autoClosed : false;
    }

    public Boolean getManualIntervention() {
        return manualIntervention != null ? manualIntervention : false;
    }

    public Integer getRetryCount() {
        return retryCount != null ? retryCount : 0;
    }

    public Integer getStopAdjustmentsCount() {
        return stopAdjustmentsCount != null ? stopAdjustmentsCount : 0;
    }

    public Integer getTargetAdjustmentsCount() {
        return targetAdjustmentsCount != null ? targetAdjustmentsCount : 0;
    }

    // =====================================
    // ENUM DEFINITIONS
    // =====================================

    public enum TradeStatus {
        OPEN,
        CLOSED,
        FAILED,
        BAYESIAN_REJECTED,
        ORDER_FAILED,
        QUOTE_FAILED,
        EXECUTION_ERROR,
        EXPIRED,
        CANCELLED
    }

    public enum TradeOutcome {
        SUCCESS,
        FAILURE,
        PENDING
    }

    public enum CloseReason {
        PROFIT_TARGET,
        STOP_LOSS,
        EXPIRATION,
        MANUAL,
        TIME_DECAY,
        RISK_MANAGEMENT,
        TREND_CHANGE,
        EMERGENCY
    }
}