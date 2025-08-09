package com.tradingBot.entity;

import com.tradingBot.model.MarketRegime;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "signals")
@Data
public class Signal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =====================================
    // BASIC SIGNAL INFORMATION
    // =====================================
    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "option_symbol")
    private String optionSymbol;

    @Column(name = "signal_type") // BUY, SELL, HOLD
    private String signalType;

    @Column(name = "strategy", nullable = false)
    private String strategy;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "original_confidence")
    private Double originalConfidence;

    @Column(name = "adjusted_confidence")
    private Double adjustedConfidence;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    @Column(name = "executed")
    private Boolean executed = false;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "status") // PENDING, EXECUTED, EXPIRED, FAILED, BAYESIAN_REJECTED, etc.
    private String status = "PENDING";

    // =====================================
    // FAILURE AND ERROR TRACKING
    // =====================================
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "validation_errors", columnDefinition = "TEXT")
    private String validationErrors;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @Column(name = "execution_attempts")
    private Integer executionAttempts = 0;

    // =====================================
    // PRICING INFORMATION
    // =====================================
    @Column(name = "entry_price", precision = 15, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "execution_price", precision = 15, scale = 4)
    private BigDecimal executionPrice;

    @Column(name = "fill_price", precision = 15, scale = 4)
    private BigDecimal fillPrice;

    @Column(name = "target_price", precision = 15, scale = 4)
    private BigDecimal targetPrice;

    @Column(name = "stop_loss", precision = 15, scale = 4)
    private BigDecimal stopLoss;

    @Column(name = "entry_assumption_price", precision = 15, scale = 4)
    private BigDecimal entryAssumptionPrice;

    @Column(name = "original_option_price", precision = 15, scale = 4)
    private BigDecimal originalOptionPrice;

    @Column(name = "bid_at_signal", precision = 15, scale = 4)
    private BigDecimal bidAtSignal;

    @Column(name = "ask_at_signal", precision = 15, scale = 4)
    private BigDecimal askAtSignal;

    @Column(name = "spread_at_signal", precision = 15, scale = 4)
    private BigDecimal spreadAtSignal;

    @Column(name = "underlying_price_at_signal", precision = 15, scale = 4)
    private BigDecimal underlyingPriceAtSignal;

    // =====================================
    // POSITION INFORMATION
    // =====================================
    @Column(name = "side") // CALL, PUT
    private String side;

    @Column(name = "quantity")
    private Integer quantity = 1;

    @Column(name = "actual_quantity")
    private Integer actualQuantity;

    @Column(name = "strike_price", precision = 15, scale = 4)
    private BigDecimal strikePrice;

    @Column(name = "option_type") // CALL, PUT
    private String optionType;

    // =====================================
    // OPTION GREEKS AND DETAILS
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

    @Column(name = "open_interest")
    private Integer openInterest;

    @Column(name = "volume")
    private Integer volume;

    @Column(name = "average_volume")
    private Integer averageVolume;

    @Column(name = "volume_oi_ratio")
    private Double volumeOiRatio;

    @Column(name = "expiration_date")
    private ZonedDateTime expirationDate;

    @Column(name = "days_to_expiry")
    private Integer daysToExpiry;

    @Column(name = "minutes_to_expiry")
    private Integer minutesToExpiry;

    @Column(name = "time_decay_factor")
    private Double timeDecayFactor;

    // =====================================
    // MARKET CONTEXT
    // =====================================
    @Column(name = "market_regime")
    @Enumerated(EnumType.STRING)
    private MarketRegime marketRegime;

    @Column(name = "market_trend")
    private String marketTrend;

    @Column(name = "market_trend_strength")
    private Double marketTrendStrength;

    @Column(name = "market_breadth")
    private Double marketBreadth;

    @Column(name = "vix_level")
    private Double vixLevel;

    @Column(name = "sector_performance")
    private Double sectorPerformance;

    @Column(name = "correlation_spy")
    private Double correlationSpy;

    // =====================================
    // TECHNICAL ANALYSIS
    // =====================================
    @Column(name = "rsi_value")
    private Double rsiValue;

    @Column(name = "macd_signal")
    private String macdSignal;

    @Column(name = "bollinger_position")
    private Double bollingerPosition;

    @Column(name = "vwap_distance")
    private Double vwapDistance;

    @Column(name = "volume_ratio")
    private Double volumeRatio;

    @Column(name = "momentum_strength")
    private Double momentumStrength;

    @Column(name = "trend_alignment")
    private Boolean trendAlignment;

    @Column(name = "support_level", precision = 15, scale = 4)
    private BigDecimal supportLevel;

    @Column(name = "resistance_level", precision = 15, scale = 4)
    private BigDecimal resistanceLevel;

    // =====================================
    // BAYESIAN ANALYSIS FIELDS
    // =====================================
    @Column(name = "bayesian_probability")
    private Double bayesianProbability;

    @Column(name = "prior_probability")
    private Double priorProbability;

    @Column(name = "likelihood")
    private Double likelihood;

    @Column(name = "posterior_probability")
    private Double posteriorProbability;

    @Column(name = "confidence_gap")
    private Double confidenceGap;

    @Column(name = "threshold_applied")
    private Double thresholdApplied;

    @Column(name = "threshold_method")
    private String thresholdMethod;

    @Column(name = "learning_phase")
    private String learningPhase;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "feature_importance_json", columnDefinition = "TEXT")
    private String featureImportanceJson;

    // =====================================
    // TIMING INFORMATION
    // =====================================
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "signal_generated_at")
    private LocalDateTime signalGeneratedAt;

    @Column(name = "analysis_started_at")
    private LocalDateTime analysisStartedAt;

    @Column(name = "analysis_completed_at")
    private LocalDateTime analysisCompletedAt;

    @Column(name = "bayesian_analysis_at")
    private LocalDateTime bayesianAnalysisAt;

    @Column(name = "expiration_time")
    private LocalDateTime expirationTime;

    @Column(name = "execution_start_time")
    private LocalDateTime executionStartTime;

    @Column(name = "execution_end_time")
    private LocalDateTime executionEndTime;

    @Column(name = "order_submitted_at")
    private LocalDateTime orderSubmittedAt;

    @Column(name = "order_filled_at")
    private LocalDateTime orderFilledAt;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    // =====================================
    // ORDER AND EXECUTION TRACKING
    // =====================================
    @Column(name = "order_id")
    private String orderId;

    @Column(name = "order_status")
    private String orderStatus;

    @Column(name = "order_type") // MARKET, LIMIT
    private String orderType;

    @Column(name = "order_side") // buy_to_open, sell_to_close
    private String orderSide;

    @Column(name = "commission", precision = 10, scale = 2)
    private BigDecimal commission;

    @Column(name = "fees", precision = 10, scale = 2)
    private BigDecimal fees;

    @Column(name = "slippage", precision = 10, scale = 4)
    private BigDecimal slippage;

    // =====================================
    // TRADE LINKING AND PERFORMANCE
    // =====================================
    @Column(name = "trade_id")
    private Long tradeId;

    @Column(name = "parent_signal_id")
    private Long parentSignalId;

    @Column(name = "strategy_instance_id")
    private String strategyInstanceId;

    @Column(name = "realized_pnl", precision = 15, scale = 2)
    private BigDecimal realizedPnl;

    @Column(name = "unrealized_pnl", precision = 15, scale = 2)
    private BigDecimal unrealizedPnl;

    @Column(name = "max_profit", precision = 15, scale = 2)
    private BigDecimal maxProfit;

    @Column(name = "max_loss", precision = 15, scale = 2)
    private BigDecimal maxLoss;

    @Column(name = "exit_price", precision = 15, scale = 4)
    private BigDecimal exitPrice;

    @Column(name = "exit_reason")
    private String exitReason;

    // =====================================
    // SIGNAL CLASSIFICATION
    // =====================================
    @Column(name = "priority")
    private Integer priority = 5; // 1=highest, 10=lowest

    @Column(name = "signal_strength") // WEAK, MODERATE, STRONG, VERY_STRONG
    private String signalStrength;

    @Column(name = "signal_quality") // LOW, MEDIUM, HIGH, PREMIUM
    private String signalQuality;

    @Column(name = "risk_level") // LOW, MEDIUM, HIGH
    private String riskLevel;

    @Column(name = "generated_by")
    private String generatedBy;

    @Column(name = "signal_source") // TECHNICAL, FLOW, BAYESIAN, HYBRID
    private String signalSource;

    @Column(name = "catalyst", columnDefinition = "TEXT")
    private String catalyst;

    // =====================================
    // RISK MANAGEMENT
    // =====================================
    @Column(name = "max_risk", precision = 12, scale = 2)
    private BigDecimal maxRisk;

    @Column(name = "risk_reward_ratio")
    private Double riskRewardRatio;

    @Column(name = "kelly_fraction")
    private Double kellyFraction;

    @Column(name = "position_size_recommended")
    private Double positionSizeRecommended;

    @Column(name = "expected_value")
    private Double expectedValue;

    @Column(name = "win_probability")
    private Double winProbability;

    // =====================================
    // NOTES AND DOCUMENTATION
    // =====================================
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "execution_notes", columnDefinition = "TEXT")
    private String executionNotes;

    @Column(name = "analyst_comments", columnDefinition = "TEXT")
    private String analystComments;

    @Column(name = "post_mortem_notes", columnDefinition = "TEXT")
    private String postMortemNotes;

    // =====================================
    // VALIDATION AND FILTERS
    // =====================================
    @Column(name = "passed_pre_trade_checks")
    private Boolean passedPreTradeChecks;

    @Column(name = "passed_bayesian_filter")
    private Boolean passedBayesianFilter;

    @Column(name = "passed_risk_checks")
    private Boolean passedRiskChecks;

    @Column(name = "passed_trend_alignment")
    private Boolean passedTrendAlignment;

    @Column(name = "shap_explanation_strength")
    private Double shapExplanationStrength;

    @Column(name = "mtf_validation_passed")
    private Boolean mtfValidationPassed;

    // =====================================
    // METADATA STORAGE
    // =====================================
    @ElementCollection
    @CollectionTable(name = "signal_metadata", joinColumns = @JoinColumn(name = "signal_id"))
    @MapKeyColumn(name = "metadata_key")
    @Column(name = "metadata_value")
    private Map<String, String> metadata = new HashMap<>();

    // =====================================
    // HELPER METHODS
    // =====================================

    /**
     * Get execution price with fallback to entry price
     */
    public BigDecimal getExecutionPrice() {
        if (executionPrice != null) return executionPrice;
        if (fillPrice != null) return fillPrice;
        if (entryPrice != null) return entryPrice;
        return originalOptionPrice;
    }

    /**
     * Set execution price and update entry price if needed
     */
    public void setExecutionPrice(BigDecimal executionPrice) {
        this.executionPrice = executionPrice;
        if (this.entryPrice == null) {
            this.entryPrice = executionPrice;
        }
    }

    /**
     * Get effective quantity (never null)
     */
    public Integer getQuantity() {
        return quantity != null ? quantity : 1;
    }

    /**
     * Get effective confidence with fallback logic
     */
    public Double getEffectiveConfidence() {
        if (adjustedConfidence != null) return adjustedConfidence;
        if (bayesianProbability != null) return bayesianProbability;
        if (confidence != null) return confidence;
        if (originalConfidence != null) return originalConfidence;
        return 0.5; // Default neutral confidence
    }

    /**
     * Get days to expiry with fallback calculation
     */
    public Integer getDaysToExpiry() {
        if (daysToExpiry != null) return daysToExpiry;

        if (expirationDate != null) {
            long days = java.time.Duration.between(
                    ZonedDateTime.now(), expirationDate).toDays();
            return (int) Math.max(0, days);
        }

        return 0; // 0DTE default
    }

    /**
     * Calculate minutes to expiry
     */
    public Integer getMinutesToExpiry() {
        if (minutesToExpiry != null) return minutesToExpiry;

        if (expirationDate != null) {
            long minutes = java.time.Duration.between(
                    ZonedDateTime.now(), expirationDate).toMinutes();
            return (int) Math.max(0, minutes);
        }

        return 0;
    }

    /**
     * Check if signal is still valid
     */
    public boolean isValid() {
        if (executed != null && executed) return false;
        if ("EXPIRED".equals(status) || "FAILED".equals(status) ||
                "BAYESIAN_REJECTED".equals(status)) return false;
        if (expirationTime != null && LocalDateTime.now().isAfter(expirationTime)) return false;
        return true;
    }

    /**
     * Check if signal was rejected
     */
    public boolean isRejected() {
        return "BAYESIAN_REJECTED".equals(status) ||
                "FAILED".equals(status) ||
                "TREND_REJECTED".equals(status) ||
                "RISK_REJECTED".equals(status);
    }

    /**
     * Check if signal was successfully executed
     */
    public boolean isSuccessfullyExecuted() {
        return "EXECUTED".equals(status) && tradeId != null;
    }

    /**
     * Mark signal as executed
     */
    public void markAsExecuted(Long tradeId) {
        this.executed = true;
        this.tradeId = tradeId;
        this.status = "EXECUTED";
        this.executionEndTime = LocalDateTime.now();
        this.lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * Mark signal as failed with reason
     */
    public void markAsFailed(String reason, String errorMessage) {
        this.status = "FAILED";
        this.failureReason = reason;
        this.errorMessage = errorMessage;
        this.lastErrorAt = LocalDateTime.now();
        this.lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * Mark signal as rejected by Bayesian filter
     */
    public void markAsRejected(String reason, String rejectionReason) {
        this.status = "BAYESIAN_REJECTED";
        this.failureReason = reason;
        this.rejectionReason = rejectionReason;
        this.passedBayesianFilter = false;
        this.lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * Increment retry count
     */
    public void incrementRetryCount() {
        this.retryCount = (this.retryCount != null ? this.retryCount : 0) + 1;
        this.executionAttempts = (this.executionAttempts != null ? this.executionAttempts : 0) + 1;
        this.lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * Add metadata helper
     */
    public void addMetadata(String key, String value) {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put(key, value);
    }

    /**
     * Get metadata helper
     */
    public String getMetadata(String key) {
        if (metadata == null) return null;
        return metadata.get(key);
    }

    /**
     * Calculate signal age in minutes
     */
    public Long getSignalAgeMinutes() {
        LocalDateTime created = createdAt != null ? createdAt : signalGeneratedAt;
        if (created == null) return 0L;
        return java.time.Duration.between(created, LocalDateTime.now()).toMinutes();
    }

    /**
     * Get spread percentage
     */
    public Double getSpreadPercentage() {
        if (bidAtSignal == null || askAtSignal == null ||
                bidAtSignal.compareTo(BigDecimal.ZERO) == 0) return null;

        BigDecimal spread = askAtSignal.subtract(bidAtSignal);
        BigDecimal midPrice = bidAtSignal.add(askAtSignal).divide(BigDecimal.valueOf(2));

        return spread.divide(midPrice, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100;
    }

    /**
     * Check if signal passed all validation checks
     */
    public boolean passedAllChecks() {
        return Boolean.TRUE.equals(passedPreTradeChecks) &&
                Boolean.TRUE.equals(passedBayesianFilter) &&
                Boolean.TRUE.equals(passedRiskChecks) &&
                Boolean.TRUE.equals(passedTrendAlignment);
    }

    // =====================================
    // GETTERS AND SETTERS WITH DEFAULTS
    // =====================================

    public Map<String, String> getMetadata() {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public Boolean getExecuted() {
        return executed != null ? executed : false;
    }

    public void setExecuted(Boolean executed) {
        this.executed = executed;
    }

    public Integer getRetryCount() {
        return retryCount != null ? retryCount : 0;
    }

    public Integer getExecutionAttempts() {
        return executionAttempts != null ? executionAttempts : 0;
    }

    public Boolean getPassedPreTradeChecks() {
        return passedPreTradeChecks != null ? passedPreTradeChecks : false;
    }

    public Boolean getPassedBayesianFilter() {
        return passedBayesianFilter != null ? passedBayesianFilter : false;
    }

    public Boolean getPassedRiskChecks() {
        return passedRiskChecks != null ? passedRiskChecks : false;
    }

    public Boolean getPassedTrendAlignment() {
        return passedTrendAlignment != null ? passedTrendAlignment : false;
    }

    public Boolean getMtfValidationPassed() {
        return mtfValidationPassed != null ? mtfValidationPassed : false;
    }

    @Column(name = "ai_strategy")
    private String aiStrategy;

    @Column(name = "ai_correlation", precision = 5)
    private Double aiCorrelation;

    @Column(name = "ai_signal_strength", precision = 5)
    private Double aiSignalStrength;

    @Column(name = "ai_market_regime")
    private String aiMarketRegime;



    // =====================================
    // STATUS ENUMS
    // =====================================

    public enum SignalStatus {
        PENDING,
        ANALYZING,
        APPROVED,
        EXECUTED,
        FAILED,
        BAYESIAN_REJECTED,
        TREND_REJECTED,
        RISK_REJECTED,
        EXPIRED,
        CANCELLED
    }

    public enum SignalStrength {
        WEAK,
        MODERATE,
        STRONG,
        VERY_STRONG
    }

    public enum SignalQuality {
        LOW,
        MEDIUM,
        HIGH,
        PREMIUM
    }

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        EXTREME
    }

    public enum SignalSource {
        TECHNICAL,
        FLOW,
        BAYESIAN,
        HYBRID,
        MANUAL
    }
}