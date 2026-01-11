package com.tradingBot.service;


import com.tradingBot.entity.MarketData;
import com.tradingBot.repository.MarketDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * BounceDetectionService - Historical Bounce Analysis
 *
 * Purpose: Identify if VWAP level has proven support with previous bounces
 * Replaces: Broken bounce history that never found bounces
 *
 * Key Logic:
 * - Find bars that touched VWAP (within 0.05%)
 * - Check if price reversed on same or next bar
 * - Count successful bounces in recent history
 * - Establishes VWAP as proven support level
 */
@Service
public class BounceDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(BounceDetectionService.class);

    @Autowired
    private MarketDataRepository marketDataRepository;

    @Autowired
    private CandlePatternService candlePatternService;

    // Thresholds
    private static final BigDecimal TOUCH_TOLERANCE = new BigDecimal("0.0005"); // 0.05% tolerance
    private static final BigDecimal REVERSAL_THRESHOLD = new BigDecimal("0.001"); // 0.1% reversal minimum
    private static final int LOOKBACK_MINUTES = 60; // Look for bounces in past hour

    /**
     * Count proper bounces at a VWAP level
     *
     * A "proper bounce" means:
     * 1. Price touched VWAP (within 0.05%)
     * 2. Price reversed on same or next bar
     * 3. Reversal sustained (not immediately failed)
     */
    public int countProperBounces(String symbol, BigDecimal vwapLevel, int minutesBack) {
        logger.debug("[BOUNCE-DETECTION] Searching for bounces at VWAP level {} over last {} minutes",
                vwapLevel, minutesBack);

        try {
            // Get historical bars
            LocalDateTime startTime = LocalDateTime.now().minusMinutes(minutesBack);
            List<MarketData> bars = marketDataRepository.findBySymbolAndTimestampAfter(symbol, startTime);

            if (bars == null || bars.isEmpty()) {
                logger.warn("[BOUNCE-DETECTION] No historical data returned from repository");
                return 0;
            }

            // CRITICAL: Filter out bars with NULL OHLC fields (prevents NPE in candle analysis)
            List<MarketData> validBars = bars.stream()
                    .filter(bar -> bar.getOpen() != null && bar.getHigh() != null &&
                            bar.getLow() != null && bar.getClose() != null)
                    .collect(Collectors.toList());

            long badCount = bars.size() - validBars.size();
            if (badCount > 0) {
                logger.warn("[BOUNCE-DETECTION] Filtered out {} bars with NULL fields for {}",
                        badCount, symbol);
            }

            if (validBars.size() < 2) {
                logger.warn("[BOUNCE-DETECTION] Insufficient valid bars after filtering: {} bars", validBars.size());
                return 0;
            }

            // Use validBars for analysis
            bars = validBars;

            int bounceCount = 0;
            List<String> bounceDetails = new ArrayList<>();

            // Check each bar for VWAP touch + reversal
            for (int i = 0; i < bars.size() - 1; i++) {
                MarketData touchBar = bars.get(i);
                MarketData nextBar = bars.get(i + 1);

                // Check if this bar touched VWAP
                if (touchedVWAP(touchBar, vwapLevel)) {
                    // Check if reversal occurred
                    if (isBounceConfirmed(touchBar, nextBar, vwapLevel)) {
                        bounceCount++;
                        String detail = String.format("Bounce %d at %s: touched %.2f, reversed to %.2f",
                                bounceCount, touchBar.getTimestamp(), touchBar.getLow(), nextBar.getClose());
                        bounceDetails.add(detail);
                        logger.info("[BOUNCE-DETECTED] {}", detail);
                    }
                }
            }

            if (bounceCount > 0) {
                logger.info("[BOUNCE-DETECTION] ✓ Found {} bounces at VWAP level {} in last {} minutes",
                        bounceCount, vwapLevel, minutesBack);
                bounceDetails.forEach(detail -> logger.debug("[BOUNCE-DETAIL] {}", detail));
            } else {
                logger.debug("[BOUNCE-DETECTION] No historical bounces found at VWAP level {}", vwapLevel);
            }

            return bounceCount;

        } catch (Exception e) {
            logger.error("[BOUNCE-DETECTION-ERROR] Error detecting bounces: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Check if bar touched VWAP level
     * Bar's low must be within tolerance of VWAP
     */
    private boolean touchedVWAP(MarketData bar, BigDecimal vwapLevel) {
        if (bar == null || bar.getLow() == null || vwapLevel == null) {
            return false;
        }

        // Calculate distance from VWAP
        BigDecimal distance = bar.getLow().subtract(vwapLevel).abs();
        BigDecimal percentDistance = distance.divide(vwapLevel, 6, RoundingMode.HALF_UP);

        boolean touched = percentDistance.compareTo(TOUCH_TOLERANCE) <= 0;

        if (touched) {
            logger.debug("[BOUNCE-TOUCH] Bar touched VWAP: low={}, vwap={}, distance={}%",
                    bar.getLow(), vwapLevel, percentDistance.multiply(BigDecimal.valueOf(100)));
        }

        return touched;
    }

    /**
     * Check if bounce was confirmed on next bar
     *
     * Confirmation means:
     * 1. Touch bar shows reversal (green bar or long wick)
     * 2. Next bar continues higher (close > touch bar's low)
     * 3. Move is meaningful (not just tick movement)
     */
    private boolean isBounceConfirmed(MarketData touchBar, MarketData nextBar, BigDecimal vwapLevel) {
        if (touchBar == null || nextBar == null) {
            return false;
        }

        // Check if touch bar has reversal characteristics
        boolean touchHasReversal = candlePatternService.isGreenBar(touchBar)
                || candlePatternService.hasLongLowerWick(touchBar)
                || candlePatternService.isBullishReversal(touchBar);

        if (!touchHasReversal) {
            logger.debug("[BOUNCE-CONFIRM] Touch bar has no reversal pattern");
            return false;
        }

        // Check if next bar confirms (higher close)
        boolean nextBarHigher = nextBar.getClose().compareTo(touchBar.getLow()) > 0;

        if (!nextBarHigher) {
            logger.debug("[BOUNCE-CONFIRM] Next bar did not continue higher");
            return false;
        }

        // Calculate reversal magnitude
        BigDecimal reversalPercent = nextBar.getClose().subtract(touchBar.getLow())
                .divide(touchBar.getLow(), 6, RoundingMode.HALF_UP);

        boolean meaningfulReversal = reversalPercent.compareTo(REVERSAL_THRESHOLD) >= 0;

        if (!meaningfulReversal) {
            logger.debug("[BOUNCE-CONFIRM] Reversal too small: {}%",
                    reversalPercent.multiply(BigDecimal.valueOf(100)));
            return false;
        }

        logger.info("[BOUNCE-CONFIRM] ✓ Bounce confirmed: reversal of {}%",
                reversalPercent.multiply(BigDecimal.valueOf(100)));

        return true;
    }

    /**
     * Check if current setup has historical support
     * Returns true if 1-2 bounces found in lookback period
     */
    public boolean hasHistoricalSupport(String symbol, BigDecimal vwapLevel) {
        int bounces = countProperBounces(symbol, vwapLevel, LOOKBACK_MINUTES);
        boolean hasSupport = bounces >= 1;

        if (hasSupport) {
            logger.info("[BOUNCE-SUPPORT] ✓ VWAP level {} has historical support: {} bounces in past hour",
                    vwapLevel, bounces);
        } else {
            logger.warn("[BOUNCE-SUPPORT] ✗ VWAP level {} has NO historical support", vwapLevel);
        }

        return hasSupport;
    }

    /**
     * Get detailed bounce analysis for logging
     */
    public BounceAnalysis analyzeBounceHistory(String symbol, BigDecimal vwapLevel) {
        BounceAnalysis analysis = new BounceAnalysis();
        analysis.vwapLevel = vwapLevel;
        analysis.bounceCount = countProperBounces(symbol, vwapLevel, LOOKBACK_MINUTES);
        analysis.hasSupport = analysis.bounceCount >= 1;
        analysis.reason = String.format("%d bounces found in past %d minutes",
                analysis.bounceCount, LOOKBACK_MINUTES);

        return analysis;
    }

    /**
     * Check if current bar is attempting a bounce
     * (For real-time detection during trading)
     */
    public boolean isBouncingNow(MarketData currentBar, BigDecimal vwapLevel) {
        if (!touchedVWAP(currentBar, vwapLevel)) {
            return false;
        }

        // Check if bar shows reversal characteristics
        boolean hasReversal = candlePatternService.isBullishReversal(currentBar);

        if (hasReversal) {
            logger.info("[BOUNCE-NOW] ✓ Current bar touching VWAP and showing reversal");
        }

        return hasReversal;
    }

    /**
     * Data class for bounce analysis results
     */
    public static class BounceAnalysis {
        public BigDecimal vwapLevel;
        public int bounceCount = 0;
        public boolean hasSupport = false;
        public String reason = "";

        @Override
        public String toString() {
            return String.format("BounceAnalysis[vwap=%.2f, bounces=%d, hasSupport=%s, reason='%s']",
                    vwapLevel, bounceCount, hasSupport, reason);
        }
    }
}