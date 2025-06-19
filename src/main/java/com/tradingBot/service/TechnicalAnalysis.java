package com.tradingBot.service;

import com.tradingBot.model.DynamicParameters;
import com.tradingBot.model.MarketRegime;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Data
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

    // Reversal detection and market context
    private boolean potentialBearishReversal;
    private boolean potentialBullishReversal;
    private boolean volatilitySpike;

    // Add these new fields for Opening Drive strategy
    private BigDecimal previousClose;
    private BigDecimal preMarketHigh;
    private BigDecimal preMarketLow;

    // Add getter and setter for previousClose
    public BigDecimal getPreviousClose() {
        return previousClose;
    }

    public void setPreviousClose(BigDecimal previousClose) {
        this.previousClose = previousClose;
    }

    // Add getters and setters for pre-market data
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

    // If you don't have these methods, add them:
    public String getTrend() {
        // Simple trend determination based on VWAP position
        if (currentPrice != null && vwap != null) {
            int comparison = currentPrice.compareTo(vwap);
            if (comparison > 0 && momentumStrength > 0) {
                return "BULLISH";
            } else if (comparison < 0 && momentumStrength < 0) {
                return "BEARISH";
            }
        }
        return "NEUTRAL";
    }

    public double getMomentumStrength() {
        return momentumStrength;
    }

    public double getStrength() {
        // Return absolute momentum strength
        return Math.abs(momentumStrength);
    }

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
}