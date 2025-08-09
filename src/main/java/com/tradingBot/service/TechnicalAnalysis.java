package com.tradingBot.service;

import com.tradingBot.model.DynamicParameters;
import com.tradingBot.model.MarketRegime;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Comprehensive Technical Analysis data class for 0DTE options trading
 * Combines existing trading indicators with Bayesian filter requirements
 */
@Getter
@Setter
@Data
@Slf4j
public class TechnicalAnalysis {
    private String symbol;
    private LocalDateTime timestamp;
    private BigDecimal currentPrice;

    // VWAP fields
    private BigDecimal vwap;
    private BigDecimal vwapUpperBand;
    private BigDecimal vwapLowerBand;
    private boolean vwapBreakout;
    private String vwapTrend; // BULLISH, BEARISH, NEUTRAL
    private boolean vwapAsSupport;
    private boolean vwapAsResistance;

    // Trend analysis
    private String trend; // Overall trend
    private double strength; // Trend strength 0-1
    private List<BigDecimal> supportLevels;
    private List<BigDecimal> resistanceLevels;

    // Volatility fields (replaced VolatilityMetrics)
    private BigDecimal currentVolatility;
    private BigDecimal averageVolatility;
    private BigDecimal averageTrueRange; // ATR for dynamic targets
    private double volatilityPercentile; // 0-100

    // Volume fields (replaced VolumeAnalysis)
    private long currentVolume;
    private long averageVolume;
    private double volumeRatio; // current/average
    private boolean highVolume;
    private String volumeTrend; // INCREASING, DECREASING, STABLE

    // Momentum fields (replaced MomentumAnalysis)
    private double rsi;
    private String macdSignal; // BULLISH, BEARISH, NEUTRAL
    private double momentumStrength; // -1 to 1

    private boolean potentialBearishReversal;
    private boolean potentialBullishReversal;
    private boolean volatilitySpike;

    // Opening Drive strategy fields
    private BigDecimal previousClose;
    private BigDecimal preMarketHigh;
    private BigDecimal preMarketLow;

    // Dynamic parameters for strategy adjustments
    private DynamicParameters dynamicParameters;

    // Market structure
    private MarketRegime marketRegime;
    private BigDecimal openingRangeHigh;
    private BigDecimal openingRangeLow;
    private boolean hasRsiDivergence;
    private boolean hasMacdDivergence;

    // Additional metrics for 0DTE
    private BigDecimal vwapStandardDeviation;
    private double priceToVwapRatio;
    private boolean isExtendedFromVwap;

    // ===== ADDITIONAL FIELDS FOR BAYESIAN FILTER =====

    // Options-specific volatility metrics
    private Double impliedVolatilityRank; // IV rank for options strategies
    private Double impliedVolatility;     // Current IV level
    private Double historicalVolatility;  // HV for comparison
    private Double volatilitySkew;        // Put/call skew

    // Enhanced RSI metrics
    private Double rsi14;  // 14-period RSI (main)
    private Double rsi21;  // 21-period RSI (confirmation)

    // Moving averages for trend confirmation
    private BigDecimal sma20;
    private BigDecimal sma50;
    private BigDecimal ema12;
    private BigDecimal ema26;

    // Bollinger Bands
    private BigDecimal bollingerUpper;
    private BigDecimal bollingerLower;
    private BigDecimal bollingerMiddle;

    // Enhanced MACD
    private Double macd;
    private Double macdSignalLine;
    private Double macdHistogram;

    // Stochastic oscillator
    private Double stochasticK;
    private Double stochasticD;

    // Volume indicators
    private Double volumeRSI;

    // Support/Resistance levels (enhanced)
    private BigDecimal supportLevel1;
    private BigDecimal supportLevel2;
    private BigDecimal resistanceLevel1;
    private BigDecimal resistanceLevel2;

    // ===== EXISTING GETTER/SETTER METHODS =====


    public BigDecimal getPreviousClose() {
        return previousClose;
    }

    public void setPreviousClose(BigDecimal previousClose) {
        this.previousClose = previousClose;
    }

    public BigDecimal getPreMarketHigh() {
        return preMarketHigh;
    }

    public void setPreMarketHigh(BigDecimal preMarketHigh) {
        this.preMarketHigh = preMarketHigh;
    }

    public BigDecimal getPreMarketLow() {
        return preMarketLow;
    }

    public void setPreMarketLow(BigDecimal preMarketLow) {
        this.preMarketLow = preMarketLow;
    }
    public String getTrend() {
        return trend;
    }

    public double getMomentumStrength() {
        return momentumStrength;
    }

    public double getStrength() {
        // Return absolute momentum strength
        return Math.abs(momentumStrength);
    }

    // ===== NEW METHODS FOR BAYESIAN FILTER =====

    /**
     * Calculate price distance from VWAP as percentage
     * Critical for 0DTE mean reversion strategies
     */
    public double getVwapDeviationPercent() {
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return Math.abs(priceToVwapRatio - 1.0) * 100;
    }

//    /**
//     * Check if price is above VWAP
//     * Important for directional bias in 0DTE options
//     */
//    public boolean isAboveVwap() {
//        return priceToVwapRatio > 1.0;
//    }

//    /**
//     * Get RSI momentum classification for strategy selection
//     */
//    public String getRsiClassification() {
//        // Use rsi14 if available, otherwise fall back to rsi
//        Double currentRsi = rsi14 != null ? rsi14 : rsi;
//
//        if (currentRsi == null) return "UNKNOWN";
//
//        if (currentRsi > 70) return "OVERBOUGHT";
//        else if (currentRsi < 30) return "OVERSOLD";
//        else if (currentRsi > 55) return "BULLISH";
//        else if (currentRsi < 45) return "BEARISH";
//        else return "NEUTRAL";
//    }

//    /**
//     * Check if volume is above average - key for 0DTE breakout confirmation
//     */
//    public boolean isVolumeAboveAverage() {
//        return volumeRatio > 1.0;
//    }

//    /**
//     * Get volatility rank classification for options strategies
//     */
//    public String getVolatilityClassification() {
//        // Use impliedVolatilityRank if available, otherwise volatilityPercentile
//        Double volRank = impliedVolatilityRank != null ? impliedVolatilityRank : volatilityPercentile;
//
//        if (volRank == null) return "UNKNOWN";
//
//        if (volRank > 80) return "VERY_HIGH";
//        else if (volRank > 60) return "HIGH";
//        else if (volRank > 40) return "MODERATE";
//        else if (volRank > 20) return "LOW";
//        else return "VERY_LOW";
//    }

//    /**
//     * Calculate overall technical score (0-100) for signal strength
//     * Combines multiple indicators weighted for 0DTE trading
//     */
//    public double getTechnicalScore() {
//        double score = 50.0; // Start neutral
//
//        // RSI component (weight: 20%)
//        Double currentRsi = rsi14 != null ? rsi14 : rsi;
//        if (currentRsi != null) {
//            if (currentRsi > 70) score -= 10; // Overbought negative for new longs
//            else if (currentRsi < 30) score -= 10; // Oversold negative for new shorts
//            else if (currentRsi > 50) score += (currentRsi - 50) / 5; // Bullish momentum
//            else score -= (50 - currentRsi) / 5; // Bearish momentum
//        }
//
//        // Volume component (weight: 15%) - Critical for 0DTE
//        if (volumeRatio > 1.5) score += 10; // High volume positive
//        else if (volumeRatio < 0.8) score -= 5; // Low volume negative
//
//        // VWAP component (weight: 15%) - Key for 0DTE entries
//        if (isAboveVwap() && getVwapDeviationPercent() < 2.0) score += 5;
//        else if (!isAboveVwap() && getVwapDeviationPercent() < 2.0) score -= 5;
//
//        // MACD component (weight: 10%)
//        if (macd != null && macdSignalLine != null) {
//            if (macd > macdSignalLine) score += 5; // Bullish crossover
//            else score -= 5; // Bearish crossover
//        } else if ("BULLISH".equals(macdSignal)) {
//            score += 5;
//        } else if ("BEARISH".equals(macdSignal)) {
//            score -= 5;
//        }
//
//        // Market regime component (weight: 20%) - Very important for 0DTE
//        if (marketRegime != null) {
//            switch (marketRegime) {
//                case TRENDING_UP -> score += 15;
//                case TRENDING_DOWN -> score -= 15;
//                case HIGH_VOLATILITY -> score -= 5;
//                case LOW_VOLATILITY -> score += 5;
//                case CHOPPY -> score -= 10;
//                case BREAKOUT_PENDING -> score += 8;
//                case REVERSAL_PATTERN -> {
//                    // Reversal score depends on current position
//                    if (isAboveVwap()) score -= 8; // Bearish reversal risk
//                    else score += 8; // Bullish reversal opportunity
//                }
//            }
//        }
//
//        // IV Rank component (weight: 10%) - Important for options
//        Double volRank = impliedVolatilityRank != null ? impliedVolatilityRank : volatilityPercentile;
//        if (volRank != null) {
//            if (volRank > 80) score -= 5; // Very high IV often bad for buying
//            else if (volRank < 20) score += 5; // Low IV good for buying
//        }
//
//        // Momentum component (weight: 10%)
//        if (momentumStrength > 0.5) score += 8;
//        else if (momentumStrength < -0.5) score -= 8;
//
//        // Clamp to 0-100 range
//        return Math.max(0.0, Math.min(100.0, score));
//    }

//    /**
//     * Check if conditions favor breakout strategy for 0DTE
//     */
//    public boolean favorsBreakout() {
//        return volumeRatio > 1.3 &&
//                Math.abs(momentumStrength) > 0.3 &&
//                getVwapDeviationPercent() > 1.0 &&
//                !volatilitySpike; // Avoid breakouts during vol spikes
//    }

//    /**
//     * Check if conditions favor mean reversion strategy for 0DTE
//     */
//    public boolean favorsMeanReversion() {
//        Double currentRsi = rsi14 != null ? rsi14 : rsi;
//
//        return (currentRsi != null && (currentRsi > 70 || currentRsi < 30)) ||
//                getVwapDeviationPercent() > 2.0 ||
//                marketRegime == MarketRegime.CHOPPY ||
//                potentialBullishReversal ||
//                potentialBearishReversal;
//    }

//    /**
//     * Check if conditions favor opening drive strategy
//     */
//    public boolean favorsOpeningDrive() {
//        // Check if we have pre-market data and significant gap
//        if (previousClose == null || currentPrice == null) {
//            return false;
//        }
//
//        BigDecimal gapPercent = currentPrice.subtract(previousClose)
//                .divide(previousClose, 4, BigDecimal.ROUND_HALF_UP)
//                .multiply(BigDecimal.valueOf(100));
//
//        return Math.abs(gapPercent.doubleValue()) > 0.5 && // >0.5% gap
//                volumeRatio > 1.2 && // Above average volume
//                Math.abs(momentumStrength) > 0.4; // Strong momentum
//    }

    /**
     * Get comprehensive summary of all indicators for logging/debugging
     */
//    public String getSummary() {
//        Double currentRsi = rsi14 != null ? rsi14 : rsi;
//
//        return String.format(
//                "Symbol: %s, Price: $%.2f, RSI: %.1f, Volume: %.1fx, VWAP Dev: %.2f%%, " +
//                        "Momentum: %.2f, Regime: %s, Trend: %s, Score: %.1f, IV Rank: %.1f",
//                symbol,
//                currentPrice != null ? currentPrice.doubleValue() : 0.0,
//                currentRsi != null ? currentRsi : 0.0,
//                volumeRatio,
//                getVwapDeviationPercent(),
//                momentumStrength,
//                marketRegime != null ? marketRegime.name() : "UNKNOWN",
//                getTrend(),
//                getTechnicalScore(),
//                impliedVolatilityRank != null ? impliedVolatilityRank : 0.0
//        );
//    }

//    /**
//     * Get strategy recommendations based on current technical conditions
//     */
//    public String[] getRecommendedStrategies() {
//        if (favorsBreakout()) {
//            return new String[]{"BREAKOUT", "MOMENTUM", "UNUSUAL_FLOW"};
//        } else if (favorsMeanReversion()) {
//            return new String[]{"REVERSION", "SCALPING", "PREMIUM_SELLING"};
//        } else if (favorsOpeningDrive()) {
//            return new String[]{"OPENING_DRIVE", "GAP_FILL", "MOMENTUM"};
//        } else {
//            return new String[]{"NEUTRAL", "WAIT_FOR_SETUP"};
//        }
//    }

    /**
     * Calculate risk level for 0DTE trading (1-10 scale)
     */
//    public int getRiskLevel() {
//        int risk = 5; // Start neutral
//
//        // High volatility increases risk
//        if (volatilitySpike) risk += 2;
//        Double volRank = impliedVolatilityRank != null ? impliedVolatilityRank : volatilityPercentile;
//        if (volRank != null && volRank > 80) risk += 1;
//
//        // Low volume increases risk
//        if (volumeRatio < 0.8) risk += 1;
//
//        // Extended from VWAP increases risk
//        if (getVwapDeviationPercent() > 3.0) risk += 1;
//
//        // Market regime adjustments
//        if (marketRegime == MarketRegime.CHOPPY) risk += 2;
//        else if (marketRegime == MarketRegime.HIGH_VOLATILITY) risk += 1;
//        else if (marketRegime == MarketRegime.TRENDING_UP || marketRegime == MarketRegime.TRENDING_DOWN) risk -= 1;
//
//        // Divergences increase risk
//        if (hasRsiDivergence || hasMacdDivergence) risk += 1;
//
//        return Math.max(1, Math.min(10, risk));
//    }

    // ===== BACKWARD COMPATIBILITY METHODS =====

    /**
     * Get averageTrueRange (existing field) for Bayesian filter compatibility
     */
    public BigDecimal getAverageTrueRange() {
        return averageTrueRange;
    }

    /**
     * Get impliedVolatilityRank with fallback to volatilityPercentile
     */
    public Double getImpliedVolatilityRank() {
        return impliedVolatilityRank != null ? impliedVolatilityRank : volatilityPercentile;
    }

    /**
     * Set rsi14 field when RSI is updated
     */
    public void setRsi(double rsi) {
        this.rsi = rsi;
        this.rsi14 = rsi; // Keep both fields in sync
    }

    /**
     * Set MACD values from macdSignal string
     */
    public void setMacdSignal(String macdSignal) {
        this.macdSignal = macdSignal;

        // Convert string signal to numeric values for Bayesian filter
        switch (macdSignal) {
            case "BULLISH" -> {
                this.macd = 0.5;
                this.macdSignalLine = 0.3;
                this.macdHistogram = 0.2;
            }
            case "BEARISH" -> {
                this.macd = -0.5;
                this.macdSignalLine = -0.3;
                this.macdHistogram = -0.2;
            }
            default -> {
                this.macd = 0.0;
                this.macdSignalLine = 0.0;
                this.macdHistogram = 0.0;
            }
        }
    }
}