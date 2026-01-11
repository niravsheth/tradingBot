package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VolumeAnalysisService - Volume Pattern Detection
 *
 * Purpose: Detect volume exhaustion, spikes, and follow-through
 * Replaces: Broken volume ratio that used corrupted tick volume
 *
 * Key Patterns:
 * - Volume exhaustion: declining volume into a low
 * - Volume spike: reversal bar has volume spike
 * - Follow-through: sustained volume on subsequent bars
 */
@Service
public class VolumeAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(VolumeAnalysisService.class);

    // Thresholds
    private static final BigDecimal VOLUME_SPIKE_MULTIPLIER = new BigDecimal("1.5"); // 50% above average
    private static final BigDecimal VOLUME_DECLINE_RATIO = new BigDecimal("0.85"); // 15% decline
    private static final int AVERAGE_LOOKBACK = 10; // bars for average volume

    /**
     * Check if volume is declining (exhaustion pattern)
     *
     * Volume declining over 3 bars suggests selling pressure exhausting
     * Bullish sign when price approaching support
     */
    public boolean isVolumeExhaustion(List<MarketData> bars) {
        if (bars == null || bars.size() < 3) {
            logger.warn("[VOLUME] Insufficient bars for exhaustion check: {}", bars == null ? 0 : bars.size());
            return false;
        }

        // Check last 3 bars for declining volume
        int decliningCount = 0;
        for (int i = bars.size() - 2; i >= Math.max(0, bars.size() - 3); i--) {
            long currentVolume = bars.get(i + 1).getVolume();
            long previousVolume = bars.get(i).getVolume();

            if (currentVolume < previousVolume) {
                decliningCount++;
                logger.debug("[VOLUME] Bar {} volume declining: {} < {}",
                        i + 1, currentVolume, previousVolume);
            }
        }

        boolean hasExhaustion = decliningCount >= 2; // 2 of 3 bars declining

        if (hasExhaustion) {
            logger.info("[VOLUME] ✓ Volume exhaustion detected: {} of last 3 bars declining", decliningCount);
        } else {
            logger.debug("[VOLUME] No exhaustion: only {} of last 3 bars declining", decliningCount);
        }

        return hasExhaustion;
    }

    /**
     * Check if bar has volume spike
     *
     * Volume spike on reversal bar suggests strong buying interest
     * Compare to average volume over lookback period
     */
    public boolean isVolumeSpike(MarketData bar, List<MarketData> history) {
        if (bar == null || bar.getVolume() == 0) {
            logger.warn("[VOLUME] Invalid bar for spike check");
            return false;
        }

        if (history == null || history.isEmpty()) {
            logger.warn("[VOLUME] No history for spike comparison");
            return false;
        }

        // Calculate average volume
        long avgVolume = calculateAverageVolume(history);
        if (avgVolume == 0) {
            logger.warn("[VOLUME] Average volume is zero");
            return false;
        }

        // Check if current volume is spike
        long currentVolume = bar.getVolume();
        BigDecimal volumeRatio = BigDecimal.valueOf(currentVolume)
                .divide(BigDecimal.valueOf(avgVolume), 2, RoundingMode.HALF_UP);

        boolean isSpike = volumeRatio.compareTo(VOLUME_SPIKE_MULTIPLIER) >= 0;

        if (isSpike) {
            logger.info("[VOLUME] ✓ Volume spike detected: {} ({}x average of {})",
                    currentVolume, volumeRatio, avgVolume);
        } else {
            logger.debug("[VOLUME] No volume spike: {} ({}x average)", currentVolume, volumeRatio);
        }

        return isSpike;
    }

    /**
     * Check if volume has follow-through after reversal
     *
     * After initial reversal, volume should stay elevated (not just one spike)
     * Confirms move is real, not a fake-out
     */
    public boolean hasFollowThrough(List<MarketData> bars) {
        if (bars == null || bars.size() < 3) {
            logger.warn("[VOLUME] Insufficient bars for follow-through check: {}", bars == null ? 0 : bars.size());
            return false;
        }

        // Get last 3 bars
        List<MarketData> recentBars = bars.subList(Math.max(0, bars.size() - 3), bars.size());

        // Calculate average volume before these 3 bars
        List<MarketData> historyBars = bars.subList(0, Math.max(1, bars.size() - 3));
        long avgHistoricalVolume = calculateAverageVolume(historyBars);

        if (avgHistoricalVolume == 0) {
            logger.warn("[VOLUME] Cannot calculate historical average");
            return false;
        }

        // Check if at least 2 of last 3 bars have elevated volume
        int elevatedCount = 0;
        for (MarketData bar : recentBars) {
            if (bar.getVolume() > avgHistoricalVolume) {
                elevatedCount++;
            }
        }

        boolean hasFollowThrough = elevatedCount >= 2;

        if (hasFollowThrough) {
            logger.info("[VOLUME] ✓ Volume follow-through detected: {}/3 recent bars above average", elevatedCount);
        } else {
            logger.debug("[VOLUME] No follow-through: only {}/3 bars above average", elevatedCount);
        }

        return hasFollowThrough;
    }

    /**
     * Comprehensive volume analysis for a reversal setup
     *
     * Ideal pattern:
     * 1. Volume declining into the low (exhaustion)
     * 2. Volume spike on reversal bar
     * 3. Volume follow-through on next bars
     */
    public VolumeAnalysis analyzeVolumePattern(List<MarketData> bars) {
        VolumeAnalysis analysis = new VolumeAnalysis();

        if (bars == null || bars.size() < 5) {
            analysis.reason = "Insufficient bars for volume analysis";
            return analysis;
        }

        // Check each component
        analysis.hasExhaustion = isVolumeExhaustion(bars);

        // Check spike on most recent bar (reversal bar)
        MarketData currentBar = bars.get(bars.size() - 1);
        List<MarketData> history = bars.subList(0, bars.size() - 1);
        analysis.hasSpike = isVolumeSpike(currentBar, history);

        // Check follow-through (needs at least 2 bars after reversal)
        if (bars.size() >= 3) {
            analysis.hasFollowThrough = hasFollowThrough(bars);
        }

        // Calculate confirmation count
        int confirmations = 0;
        if (analysis.hasExhaustion) confirmations++;
        if (analysis.hasSpike) confirmations++;
        if (analysis.hasFollowThrough) confirmations++;

        analysis.confirmationCount = confirmations;
        analysis.isConfirming = confirmations >= 2; // Need at least 2 of 3

        // Build reason string
        StringBuilder reason = new StringBuilder();
        reason.append(String.format("Volume: %d/3 confirmations - ", confirmations));
        if (analysis.hasExhaustion) reason.append("exhaustion, ");
        if (analysis.hasSpike) reason.append("spike, ");
        if (analysis.hasFollowThrough) reason.append("follow-through, ");
        if (reason.length() > 0 && reason.charAt(reason.length() - 2) == ',') {
            reason.delete(reason.length() - 2, reason.length()); // Remove trailing comma
        }
        analysis.reason = reason.toString();

        logger.info("[VOLUME-ANALYSIS] {}", analysis.reason);

        return analysis;
    }

    /**
     * Calculate average volume over bars
     */
    private long calculateAverageVolume(List<MarketData> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }

        // Use up to AVERAGE_LOOKBACK bars
        int barsToUse = Math.min(bars.size(), AVERAGE_LOOKBACK);
        List<MarketData> relevantBars = bars.subList(Math.max(0, bars.size() - barsToUse), bars.size());

        long totalVolume = 0;
        int count = 0;

        for (MarketData bar : relevantBars) {
            if (bar.getVolume() > 0) { // Skip zero volume bars
                totalVolume += bar.getVolume();
                count++;
            }
        }

        return count > 0 ? totalVolume / count : 0;
    }

    /**
     * Get volume statistics for logging
     */
    public String getVolumeStats(List<MarketData> bars) {
        if (bars == null || bars.isEmpty()) {
            return "No bars";
        }

        long avgVolume = calculateAverageVolume(bars);
        long currentVolume = bars.get(bars.size() - 1).getVolume();
        BigDecimal ratio = avgVolume > 0
                ? BigDecimal.valueOf(currentVolume).divide(BigDecimal.valueOf(avgVolume), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return String.format("Current: %d, Avg: %d, Ratio: %sx", currentVolume, avgVolume, ratio);
    }

    /**
     * Data class for volume analysis results
     */
    public static class VolumeAnalysis {
        public boolean hasExhaustion = false;
        public boolean hasSpike = false;
        public boolean hasFollowThrough = false;
        public int confirmationCount = 0;
        public boolean isConfirming = false;
        public String reason = "";

        @Override
        public String toString() {
            return String.format("VolumeAnalysis[confirming=%s, exhaustion=%s, spike=%s, followThrough=%s, count=%d, reason='%s']",
                    isConfirming, hasExhaustion, hasSpike, hasFollowThrough, confirmationCount, reason);
        }
    }
}