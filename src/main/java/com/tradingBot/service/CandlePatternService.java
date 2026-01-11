package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * CandlePatternService - Candle Pattern Detection
 *
 * Purpose: Detect bullish reversal patterns and candle characteristics
 * Replaces: Broken bullish candle detection with NULL open prices
 *
 * Key Patterns:
 * - Green bar (close > open)
 * - Long lower wick (potential hammer/reversal)
 * - Bullish reversal patterns (hammer, engulfing)
 * - Close in upper range (strength indicator)
 */
@Service
public class CandlePatternService {

    private static final Logger logger = LoggerFactory.getLogger(CandlePatternService.class);

    // Thresholds for pattern detection
    private static final BigDecimal LONG_WICK_RATIO = new BigDecimal("0.50"); // Wick > 50% of range
    private static final BigDecimal UPPER_RANGE_RATIO = new BigDecimal("0.75"); // Close in top 25%
    private static final BigDecimal HAMMER_BODY_RATIO = new BigDecimal("0.30"); // Body < 30% of range

    /**
     * Check if bar is green (bullish)
     * close > open
     */
    public boolean isGreenBar(MarketData bar) {
        if (bar == null || bar.getOpen() == null || bar.getClose() == null) {
            logger.warn("[CANDLE-PATTERN] Cannot check green bar - null OHLC data");
            return false;
        }

        return bar.getClose().compareTo(bar.getOpen()) > 0;
    }

    /**
     * Check if bar is red (bearish)
     * close < open
     */
    public boolean isRedBar(MarketData bar) {
        if (bar == null || bar.getOpen() == null || bar.getClose() == null) {
            return false;
        }

        return bar.getClose().compareTo(bar.getOpen()) < 0;
    }

    /**
     * Check if bar has long lower wick (potential reversal)
     * Lower wick > 50% of total range suggests buying pressure from lower levels
     */
    public boolean hasLongLowerWick(MarketData bar) {
        if (!isValidBar(bar)) {
            return false;
        }

        BigDecimal range = bar.getHigh().subtract(bar.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }

        // Lower wick = low to min(open, close)
        BigDecimal bodyLow = bar.getOpen().min(bar.getClose());
        BigDecimal lowerWick = bodyLow.subtract(bar.getLow());

        // Wick ratio = wick / total range
        BigDecimal wickRatio = lowerWick.divide(range, 4, RoundingMode.HALF_UP);

        boolean hasLongWick = wickRatio.compareTo(LONG_WICK_RATIO) >= 0;

        if (hasLongWick) {
            logger.debug("[CANDLE-PATTERN] Long lower wick detected: wick={}% of range",
                    wickRatio.multiply(BigDecimal.valueOf(100)).intValue());
        }

        return hasLongWick;
    }

    /**
     * Check if bar closed in upper portion of range
     * Indicates strength - buyers in control
     */
    public boolean closedInUpperRange(MarketData bar) {
        if (!isValidBar(bar)) {
            return false;
        }

        BigDecimal range = bar.getHigh().subtract(bar.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return bar.getClose().compareTo(bar.getHigh()) == 0; // Closed at high
        }

        // Position in range: (close - low) / (high - low)
        BigDecimal closePosition = bar.getClose().subtract(bar.getLow())
                .divide(range, 4, RoundingMode.HALF_UP);

        boolean inUpperRange = closePosition.compareTo(UPPER_RANGE_RATIO) >= 0;

        if (inUpperRange) {
            logger.debug("[CANDLE-PATTERN] Closed in upper range: {}% of range",
                    closePosition.multiply(BigDecimal.valueOf(100)).intValue());
        }

        return inUpperRange;
    }

    /**
     * Detect hammer pattern (bullish reversal)
     *
     * Characteristics:
     * - Small body (< 30% of range)
     * - Long lower wick (> 50% of range)
     * - Little to no upper wick
     * - Can be green or red
     */
    public boolean isHammer(MarketData bar) {
        if (!isValidBar(bar)) {
            return false;
        }

        BigDecimal range = bar.getHigh().subtract(bar.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }

        // Body size
        BigDecimal body = bar.getClose().subtract(bar.getOpen()).abs();
        BigDecimal bodyRatio = body.divide(range, 4, RoundingMode.HALF_UP);

        // Check conditions
        boolean smallBody = bodyRatio.compareTo(HAMMER_BODY_RATIO) <= 0;
        boolean longLowerWick = hasLongLowerWick(bar);

        boolean isHammer = smallBody && longLowerWick;

        if (isHammer) {
            logger.info("[CANDLE-PATTERN] ✓ HAMMER detected: body={}%, wick=long",
                    bodyRatio.multiply(BigDecimal.valueOf(100)).intValue());
        }

        return isHammer;
    }

    /**
     * Detect bullish engulfing pattern
     *
     * Requires two bars:
     * - Previous bar: red (bearish)
     * - Current bar: green (bullish) and completely engulfs previous body
     */
    public boolean isBullishEngulfing(MarketData previousBar, MarketData currentBar) {
        if (!isValidBar(previousBar) || !isValidBar(currentBar)) {
            return false;
        }

        // Previous must be red
        if (!isRedBar(previousBar)) {
            return false;
        }

        // Current must be green
        if (!isGreenBar(currentBar)) {
            return false;
        }

        // Current open < previous close AND current close > previous open
        boolean engulfs = currentBar.getOpen().compareTo(previousBar.getClose()) <= 0
                && currentBar.getClose().compareTo(previousBar.getOpen()) >= 0;

        if (engulfs) {
            logger.info("[CANDLE-PATTERN] ✓ BULLISH ENGULFING detected");
        }

        return engulfs;
    }

    /**
     * Check if bar represents a bullish reversal
     *
     * Combines multiple signals:
     * - Is green bar OR has long lower wick
     * - Closed in upper range
     * - Could be hammer pattern
     */
    public boolean isBullishReversal(MarketData bar) {
        if (!isValidBar(bar)) {
            return false;
        }

        boolean greenOrWick = isGreenBar(bar) || hasLongLowerWick(bar);
        boolean upperClose = closedInUpperRange(bar);
        boolean hammer = isHammer(bar);

        boolean isReversal = (greenOrWick && upperClose) || hammer;

        if (isReversal) {
            logger.info("[CANDLE-PATTERN] ✓ Bullish reversal bar: green={}, wick={}, upper={}, hammer={}",
                    isGreenBar(bar), hasLongLowerWick(bar), upperClose, hammer);
        }

        return isReversal;
    }

    /**
     * Calculate body size as percentage of range
     */
    public BigDecimal getBodyPercentage(MarketData bar) {
        if (!isValidBar(bar)) {
            return BigDecimal.ZERO;
        }

        BigDecimal range = bar.getHigh().subtract(bar.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal body = bar.getClose().subtract(bar.getOpen()).abs();
        return body.divide(range, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Get detailed candle analysis string (for logging)
     */
    public String analyzeCandleDetails(MarketData bar) {
        if (!isValidBar(bar)) {
            return "Invalid bar";
        }

        return String.format("O=%.2f H=%.2f L=%.2f C=%.2f | Green=%s, Wick=%s, Upper=%s, Hammer=%s",
                bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose(),
                isGreenBar(bar), hasLongLowerWick(bar), closedInUpperRange(bar), isHammer(bar));
    }

    /**
     * Validate bar has all required OHLC data
     */
    private boolean isValidBar(MarketData bar) {
        if (bar == null) {
            logger.warn("[CANDLE-PATTERN] Bar is null");
            return false;
        }

        if (bar.getOpen() == null || bar.getHigh() == null ||
                bar.getLow() == null || bar.getClose() == null) {
            logger.warn("[CANDLE-PATTERN] Bar missing OHLC data: O={}, H={}, L={}, C={}",
                    bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose());
            return false;
        }

        return true;
    }
}