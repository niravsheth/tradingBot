package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.repository.MarketDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * LeaderAnalysisService - MOST CRITICAL MISSING PIECE
 *
 * Purpose: Detect actual bullish momentum in leader stocks (AAPL, MSFT, NVDA)
 * This is THE key confirmation that was missing from the original system
 *
 * Key Philosophy:
 * - Don't rely on correlation % alone
 * - Check ACTUAL price action: 2-3 consecutive green bars
 * - Verify leaders making higher lows
 * - Require 2 of 3 leaders confirming
 */
@Service
public class LeaderAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(LeaderAnalysisService.class);

    @Autowired
    private MarketDataRepository marketDataRepository;

    @Autowired
    private BarAggregationService barAggregationService;

    @Autowired
    private CandlePatternService candlePatternService;

    // Leader stocks that lead QQQ moves
    private static final String[] LEADER_SYMBOLS = {"AAPL", "MSFT", "NVDA"};

    // Thresholds
    private static final int MIN_GREEN_BARS = 2; // Need at least 2 consecutive green bars
    private static final int LOOKBACK_BARS = 5;  // Check last 5 bars

    /**
     * Main confirmation method: Are leaders showing bullish momentum?
     *
     * Returns true if 2 of 3 leaders show 2-3 consecutive green bars
     */
    public boolean areLeadersConfirming() {
        logger.debug("[LEADER-CHECK] Checking if leaders are confirming bullish momentum");

        int confirmingLeaders = 0;
        Map<String, LeaderMomentum> leaderStatus = new HashMap<>();

        for (String leader : LEADER_SYMBOLS) {
            LeaderMomentum momentum = analyzeBullishMomentum(leader, LOOKBACK_BARS);
            leaderStatus.put(leader, momentum);

            if (momentum.isConfirming) {
                confirmingLeaders++;
                logger.info("[LEADER-CONFIRMING] {} is confirming: {} green bars, higher lows: {}",
                        leader, momentum.consecutiveGreenBars, momentum.hasHigherLows);
            } else {
                logger.debug("[LEADER-NOT-CONFIRMING] {} not confirming: green={}, higherLows={}, reason={}",
                        leader, momentum.consecutiveGreenBars, momentum.hasHigherLows, momentum.reason);
            }
        }

        boolean confirmation = confirmingLeaders >= 2;

        if (confirmation) {
            logger.info("[LEADER-CONFIRMATION] ✓ Leaders confirming: {}/3 showing bullish momentum", confirmingLeaders);
        } else {
            logger.warn("[LEADER-CONFIRMATION] ✗ Leaders NOT confirming: only {}/3 showing bullish momentum", confirmingLeaders);
        }

        return confirmation;
    }

    /**
     * Analyze bullish momentum for a single leader
     *
     * Checks:
     * 1. How many consecutive green bars (close > open)
     * 2. Are lows getting higher (making higher lows)
     * 3. Overall strength of the move
     */
    public LeaderMomentum analyzeBullishMomentum(String symbol, int numBars) {
        LeaderMomentum momentum = new LeaderMomentum(symbol);

        try {
            List<MarketData> bars = barAggregationService.getRecentBars(symbol, numBars);

            if (bars == null || bars.size() < 3) {
                momentum.reason = "Insufficient bar data";
                logger.warn("[LEADER-MOMENTUM] {} - Insufficient data: {} bars", symbol, bars == null ? 0 : bars.size());
                return momentum;
            }

            // Count consecutive green bars from most recent
            int consecutiveGreen = 0;
            for (int i = bars.size() - 1; i >= 0; i--) {
                MarketData bar = bars.get(i);
                if (candlePatternService.isGreenBar(bar)) {
                    consecutiveGreen++;
                } else {
                    break; // Stop at first non-green bar
                }
            }

            momentum.consecutiveGreenBars = consecutiveGreen;

            // Check for higher lows (bullish structure)
            boolean hasHigherLows = checkHigherLows(bars);
            momentum.hasHigherLows = hasHigherLows;

            // Calculate percentage of green bars in lookback
            int totalGreen = 0;
            for (MarketData bar : bars) {
                if (candlePatternService.isGreenBar(bar)) {
                    totalGreen++;
                }
            }
            momentum.percentGreen = (totalGreen * 100.0) / bars.size();

            // Determine if confirming
            if (consecutiveGreen >= MIN_GREEN_BARS && hasHigherLows) {
                momentum.isConfirming = true;
                momentum.strength = calculateStrength(bars);
                momentum.reason = String.format("%d consecutive green bars + higher lows", consecutiveGreen);
            } else if (consecutiveGreen >= MIN_GREEN_BARS) {
                momentum.reason = String.format("Has %d green bars but no higher lows", consecutiveGreen);
            } else if (hasHigherLows) {
                momentum.reason = String.format("Has higher lows but only %d consecutive green", consecutiveGreen);
            } else {
                momentum.reason = String.format("Only %d consecutive green, no higher lows", consecutiveGreen);
            }

            logger.debug("[LEADER-MOMENTUM] {}: green={}/{}, consecutive={}, higherLows={}, confirming={}",
                    symbol, totalGreen, bars.size(), consecutiveGreen, hasHigherLows, momentum.isConfirming);

        } catch (Exception e) {
            logger.error("[LEADER-MOMENTUM-ERROR] Error analyzing {}: {}", symbol, e.getMessage());
            momentum.reason = "Error: " + e.getMessage();
        }

        return momentum;
    }

    /**
     * Check if bars are making higher lows (bullish structure)
     */
    private boolean checkHigherLows(List<MarketData> bars) {
        if (bars.size() < 3) return false;

        // Check last 3 bars for higher lows
        int higherLowCount = 0;
        for (int i = bars.size() - 2; i >= Math.max(0, bars.size() - 4); i--) {
            BigDecimal currentLow = bars.get(i + 1).getLow();
            BigDecimal previousLow = bars.get(i).getLow();

            if (currentLow.compareTo(previousLow) > 0) {
                higherLowCount++;
            }
        }

        // Need at least 2 of 3 comparisons showing higher lows
        return higherLowCount >= 2;
    }

    /**
     * Calculate momentum strength (0-100)
     * Based on: green bar consistency, price movement, volume
     */
    private int calculateStrength(List<MarketData> bars) {
        if (bars.size() < 2) return 0;

        // Simple strength: % price gain over period
        BigDecimal firstClose = bars.get(0).getClose();
        BigDecimal lastClose = bars.get(bars.size() - 1).getClose();

        BigDecimal percentGain = lastClose.subtract(firstClose)
                .divide(firstClose, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // Convert to 0-100 scale (0.5% = 50 strength, 1% = 100 strength)
        int strength = percentGain.multiply(BigDecimal.valueOf(100)).intValue();
        return Math.max(0, Math.min(100, strength));
    }

    /**
     * Get detailed analysis for all leaders (for logging/debugging)
     */
    public Map<String, LeaderMomentum> getAllLeaderMomentum() {
        Map<String, LeaderMomentum> results = new HashMap<>();

        for (String leader : LEADER_SYMBOLS) {
            results.put(leader, analyzeBullishMomentum(leader, LOOKBACK_BARS));
        }

        return results;
    }

    /**
     * Data class to hold leader momentum analysis results
     */
    public static class LeaderMomentum {
        public String symbol;
        public boolean isConfirming = false;
        public int consecutiveGreenBars = 0;
        public boolean hasHigherLows = false;
        public double percentGreen = 0.0;
        public int strength = 0;
        public String reason = "";

        public LeaderMomentum(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public String toString() {
            return String.format("%s: confirming=%s, consecutive=%d, higherLows=%s, strength=%d, reason='%s'",
                    symbol, isConfirming, consecutiveGreenBars, hasHigherLows, strength, reason);
        }
    }
}