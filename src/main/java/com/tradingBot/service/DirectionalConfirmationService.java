package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DirectionalConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(DirectionalConfirmationService.class);

    private final BarAggregationService barAggregationService;

    // Configuration
    private static final List<String> LEADERS = Arrays.asList("AAPL", "MSFT", "NVDA");
    private static final int CANDLE_LOOKBACK = 5;  // Last 5 bars for direction
    private static final int MIN_DIRECTIONAL_CANDLES = 3;  // Need 3+ bars in same direction
    private static final int MIN_LEADERS_CONFIRMING = 2;  // Need 2/3 leaders minimum
    private static final int STRONG_DIRECTIONAL_CANDLES = 4; // 4/5 bars = strong momentum for flip

    // Distance thresholds for flip logic
    private static final double MAX_DISTANCE_FOR_FLIP = 0.10; // Only flip if within 0.10% of VWAP

    public DirectionalConfirmationService(BarAggregationService barAggregationService) {
        this.barAggregationService = barAggregationService;
    }

    /**
     * Result object for directional validation with flip capability
     */
    public static class DirectionalResult {
        private final boolean isValid;
        private final boolean shouldFlip;
        private final String flipDirection;
        private final String qqqDirection;
        private final int greenCandles;
        private final int redCandles;
        private final int leadersConfirmingFlip;
        private final String rejectionReason;

        public DirectionalResult(boolean isValid, boolean shouldFlip, String flipDirection,
                                 String qqqDirection, int greenCandles, int redCandles,
                                 int leadersConfirmingFlip, String rejectionReason) {
            this.isValid = isValid;
            this.shouldFlip = shouldFlip;
            this.flipDirection = flipDirection;
            this.qqqDirection = qqqDirection;
            this.greenCandles = greenCandles;
            this.redCandles = redCandles;
            this.leadersConfirmingFlip = leadersConfirmingFlip;
            this.rejectionReason = rejectionReason;
        }

        public boolean isValid() { return isValid; }
        public boolean shouldFlip() { return shouldFlip; }
        public String getFlipDirection() { return flipDirection; }
        public String getQqqDirection() { return qqqDirection; }
        public int getGreenCandles() { return greenCandles; }
        public int getRedCandles() { return redCandles; }
        public int getLeadersConfirmingFlip() { return leadersConfirmingFlip; }
        public String getRejectionReason() { return rejectionReason; }
    }

    /**
     * Enhanced directional validation with flip signal capability
     * Includes distance check to prevent flipping when price is extended from VWAP
     *
     * BUG FIX: Added momentum continuation check when strongMomentum=false but candles still conflict
     *
     * @param signalAction Original signal type (CALL/PUT)
     * @param analysisId Analysis ID for logging
     * @param distanceFromVWAP Distance from VWAP as percentage (e.g., 0.15 = 0.15%)
     * @return DirectionalResult with validation outcome and flip information
     */
    public DirectionalResult validateSignalDirectionWithFlip(String signalAction, String analysisId, double distanceFromVWAP) {
        log.info("[DIRECTIONAL-FILTER][{}] Validating {} signal direction (Distance from VWAP: {}%)",
                analysisId, signalAction, String.format("%.4f", distanceFromVWAP));

        // Step 1: Analyze QQQ candle direction with detailed counts
        CandleAnalysis qqqAnalysis = analyzeCandleDirectionDetailed("QQQ", analysisId);

        // If we can't determine direction (insufficient data), allow signal through
        if (qqqAnalysis == null) {
            log.warn("[DIRECTIONAL-FILTER][{}] ⚠ Cannot determine QQQ direction - allowing signal", analysisId);
            return new DirectionalResult(true, false, null, null, 0, 0, 0, null);
        }

        String qqqDirection = qqqAnalysis.direction;
        int greenCandles = qqqAnalysis.greenCandles;
        int redCandles = qqqAnalysis.redCandles;

        // Check if signal direction matches QQQ direction
        boolean qqqConfirms = false;
        if (signalAction.equals("CALL") && qqqDirection.equals("BULLISH")) {
            qqqConfirms = true;
        } else if (signalAction.equals("PUT") && qqqDirection.equals("BEARISH")) {
            qqqConfirms = true;
        } else if (qqqDirection.equals("NEUTRAL")) {
            log.info("[DIRECTIONAL-FILTER][{}] ✗ NEUTRAL candles - no clear direction, skipping trade", analysisId);
            return new DirectionalResult(false, false, null, qqqDirection, greenCandles, redCandles, 0,
                    "NEUTRAL candles - no clear direction");
        }

        if (qqqConfirms) {
            // Original direction confirmed, check leaders
            int leadersConfirming = countLeadersConfirmingDirection(signalAction, analysisId);

            if (leadersConfirming < MIN_LEADERS_CONFIRMING) {
                log.info("[DIRECTIONAL-FILTER][{}] ✗ Insufficient leader confirmation - {}/3 leaders confirming (need {})",
                        analysisId, leadersConfirming, MIN_LEADERS_CONFIRMING);
                return new DirectionalResult(false, false, null, qqqDirection, greenCandles, redCandles, 0,
                        "Insufficient leader confirmation");
            }

            log.info("[DIRECTIONAL-FILTER][{}] ✓ Direction CONFIRMED - QQQ: {}, Leaders: {}/3",
                    analysisId, qqqDirection, leadersConfirming);
            return new DirectionalResult(true, false, null, qqqDirection, greenCandles, redCandles, leadersConfirming, null);
        }

        // Direction conflicts - check if we should flip for breakout/breakdown trade
        boolean strongMomentum = (greenCandles >= STRONG_DIRECTIONAL_CANDLES || redCandles >= STRONG_DIRECTIONAL_CANDLES);

        if (strongMomentum) {
            String potentialFlipDirection = redCandles >= STRONG_DIRECTIONAL_CANDLES ? "PUT" : "CALL";

            log.info("[DIRECTIONAL-FILTER][{}] ⚡ Strong opposing momentum detected - {}/5 {} candles",
                    analysisId,
                    potentialFlipDirection.equals("PUT") ? redCandles : greenCandles,
                    potentialFlipDirection.equals("PUT") ? "red" : "green");

            // CRITICAL: Check distance from VWAP before allowing flip
            // If price is extended from VWAP, this is exhaustion (mean reversion), not breakout
            if (distanceFromVWAP > MAX_DISTANCE_FOR_FLIP) {
                log.info("[DIRECTIONAL-FILTER][{}] ✗ FLIP BLOCKED - Price extended from VWAP ({}% > {}%)",
                        analysisId, String.format("%.4f", distanceFromVWAP), MAX_DISTANCE_FOR_FLIP);
                log.info("[DIRECTIONAL-FILTER][{}] → This is EXHAUSTION, not breakout. Keeping original {} signal for mean reversion.",
                        analysisId, signalAction);

                // Check if leaders confirm the ORIGINAL direction for mean reversion
                int leadersConfirmingOriginal = countLeadersConfirmingDirection(signalAction, analysisId);

                if (leadersConfirmingOriginal >= MIN_LEADERS_CONFIRMING) {
                    log.info("[DIRECTIONAL-FILTER][{}] ✓ Mean reversion APPROVED - {}/3 leaders confirm {} (exhaustion setup)",
                            analysisId, leadersConfirmingOriginal, signalAction);
                    return new DirectionalResult(true, false, null, qqqDirection, greenCandles, redCandles,
                            leadersConfirmingOriginal, null);
                } else {
                    log.info("[DIRECTIONAL-FILTER][{}] ✗ Mean reversion rejected - only {}/3 leaders confirm {}",
                            analysisId, leadersConfirmingOriginal, signalAction);
                    return new DirectionalResult(false, false, null, qqqDirection, greenCandles, redCandles,
                            leadersConfirmingOriginal, "Extended from VWAP but insufficient leader confirmation for mean reversion");
                }
            }

            // Price is near VWAP - this is a valid breakout/breakdown setup
            log.info("[DIRECTIONAL-FILTER][{}] ✓ Price near VWAP ({}% <= {}%) - checking for breakout/breakdown",
                    analysisId, String.format("%.4f", distanceFromVWAP), MAX_DISTANCE_FOR_FLIP);

            // Check if leaders confirm the FLIP direction
            int leadersConfirmingFlip = countLeadersConfirmingDirection(potentialFlipDirection, analysisId);

            if (leadersConfirmingFlip >= MIN_LEADERS_CONFIRMING) {
                log.info("[DIRECTIONAL-FILTER][{}] ✓ FLIP SIGNAL APPROVED - {} → {} | Leaders: {}/3 confirming",
                        analysisId, signalAction, potentialFlipDirection, leadersConfirmingFlip);
                return new DirectionalResult(false, true, potentialFlipDirection, qqqDirection, greenCandles, redCandles,
                        leadersConfirmingFlip, null);
            } else {
                log.info("[DIRECTIONAL-FILTER][{}] ✗ Flip rejected - only {}/3 leaders confirm {} direction",
                        analysisId, leadersConfirmingFlip, potentialFlipDirection);
                return new DirectionalResult(false, false, potentialFlipDirection, qqqDirection, greenCandles, redCandles,
                        leadersConfirmingFlip, "Insufficient leader confirmation for flip");
            }
        }

        // ============================================================
        // BUG FIX: Moderate momentum (3 candles) - check for momentum continuation
        // Previously this just rejected without checking leaders for opposite direction
        // ============================================================
        boolean moderateMomentum = (greenCandles >= MIN_DIRECTIONAL_CANDLES || redCandles >= MIN_DIRECTIONAL_CANDLES);

        if (moderateMomentum) {
            String potentialContinuationDirection = redCandles >= MIN_DIRECTIONAL_CANDLES ? "PUT" : "CALL";

            log.info("[DIRECTIONAL-FILTER][{}] 📊 Moderate opposing momentum detected - {}/5 {} candles",
                    analysisId,
                    potentialContinuationDirection.equals("PUT") ? redCandles : greenCandles,
                    potentialContinuationDirection.equals("PUT") ? "red" : "green");

            // Check if leaders confirm the CONTINUATION direction (opposite of original signal)
            int leadersConfirmingContinuation = countLeadersConfirmingDirection(potentialContinuationDirection, analysisId);

            log.info("[DIRECTIONAL-FILTER][{}] Checking leaders for {} continuation: {}/3 confirming",
                    analysisId, potentialContinuationDirection, leadersConfirmingContinuation);

            if (leadersConfirmingContinuation >= MIN_LEADERS_CONFIRMING) {
                log.info("[DIRECTIONAL-FILTER][{}] ✓ MOMENTUM CONTINUATION APPROVED - {} → {} | Leaders: {}/3 confirming",
                        analysisId, signalAction, potentialContinuationDirection, leadersConfirmingContinuation);
                // Return as flip with shouldFlip=false but provide leadersConfirmingFlip for ZeroDTEStrategy to use
                return new DirectionalResult(false, false, potentialContinuationDirection, qqqDirection, greenCandles, redCandles,
                        leadersConfirmingContinuation, "QQQ direction conflicts with signal");
            } else {
                log.info("[DIRECTIONAL-FILTER][{}] ✗ Momentum continuation rejected - only {}/3 leaders confirm {}",
                        analysisId, leadersConfirmingContinuation, potentialContinuationDirection);
                return new DirectionalResult(false, false, potentialContinuationDirection, qqqDirection, greenCandles, redCandles,
                        leadersConfirmingContinuation, "Insufficient leader confirmation for momentum continuation");
            }
        }

        // No momentum pattern detected, just reject
        log.info("[DIRECTIONAL-FILTER][{}] ✗ QQQ candles conflict - Signal: {}, QQQ Direction: {}",
                analysisId, signalAction, qqqDirection);
        return new DirectionalResult(false, false, null, qqqDirection, greenCandles, redCandles, 0,
                "QQQ direction conflicts with signal");
    }

    /**
     * Original method - kept for backward compatibility
     * Calls the new method with default distance (assumes not extended)
     */
    public boolean validateSignalDirection(String signalAction, String analysisId) {
        DirectionalResult result = validateSignalDirectionWithFlip(signalAction, analysisId, 0.0);
        return result.isValid();
    }

    /**
     * Internal class to hold candle analysis details
     */
    private static class CandleAnalysis {
        final String direction;
        final int greenCandles;
        final int redCandles;
        final BigDecimal netChange;

        CandleAnalysis(String direction, int greenCandles, int redCandles, BigDecimal netChange) {
            this.direction = direction;
            this.greenCandles = greenCandles;
            this.redCandles = redCandles;
            this.netChange = netChange;
        }
    }

    /**
     * Analyzes last N candles with detailed counts
     */
    private CandleAnalysis analyzeCandleDirectionDetailed(String symbol, String analysisId) {
        List<MarketData> bars = barAggregationService.getRecentBars(symbol, CANDLE_LOOKBACK);

        if (bars == null || bars.size() < CANDLE_LOOKBACK) {
            log.warn("[CANDLE-DIRECTION][{}] Insufficient bars for {} (need {}, have {})",
                    analysisId, symbol, CANDLE_LOOKBACK, bars != null ? bars.size() : 0);
            return null;
        }

        // Filter out invalid bars
        List<MarketData> validBars = bars.stream()
                .filter(bar -> bar != null &&
                        bar.getOpen() != null &&
                        bar.getClose() != null &&
                        bar.getOpen().compareTo(BigDecimal.ZERO) > 0 &&
                        bar.getClose().compareTo(BigDecimal.ZERO) > 0 &&
                        (bar.getIsFallback() == null || !bar.getIsFallback()) &&
                        !"FALLBACK".equals(bar.getDataSource()) &&
                        !"EMERGENCY".equals(bar.getDataSource()))
                .collect(Collectors.toList());

        if (validBars.size() < MIN_DIRECTIONAL_CANDLES) {
            log.warn("[CANDLE-DIRECTION][{}] Insufficient VALID bars for {} (need {}, have {} valid out of {} total)",
                    analysisId, symbol, MIN_DIRECTIONAL_CANDLES, validBars.size(), bars.size());
            return null;
        }

        int greenCandles = 0;
        int redCandles = 0;
        BigDecimal netChange = BigDecimal.ZERO;

        for (MarketData bar : validBars) {
            BigDecimal open = bar.getOpen();
            BigDecimal close = bar.getClose();

            if (close.compareTo(open) > 0) {
                greenCandles++;
            } else if (close.compareTo(open) < 0) {
                redCandles++;
            }
            netChange = netChange.add(close.subtract(open));
        }

        log.info("[CANDLE-DIRECTION][{}] {}: {}/{} green, {}/{} red, Net: ${}",
                analysisId, symbol, greenCandles, validBars.size(), redCandles, validBars.size(),
                netChange.setScale(2, RoundingMode.HALF_UP));

        String direction;

        // Strong directional bias: 4+ out of 5 bars in same direction
        if (greenCandles >= 4) {
            log.info("[CANDLE-DIRECTION][{}] {} = BULLISH (strong: {} green bars)",
                    analysisId, symbol, greenCandles);
            direction = "BULLISH";
        } else if (redCandles >= 4) {
            log.info("[CANDLE-DIRECTION][{}] {} = BEARISH (strong: {} red bars)",
                    analysisId, symbol, redCandles);
            direction = "BEARISH";
        }
        // Moderate directional bias
        else if (greenCandles >= MIN_DIRECTIONAL_CANDLES && netChange.compareTo(BigDecimal.ZERO) > 0) {
            log.info("[CANDLE-DIRECTION][{}] {} = BULLISH (moderate: {} green bars, net positive)",
                    analysisId, symbol, greenCandles);
            direction = "BULLISH";
        } else if (redCandles >= MIN_DIRECTIONAL_CANDLES && netChange.compareTo(BigDecimal.ZERO) < 0) {
            log.info("[CANDLE-DIRECTION][{}] {} = BEARISH (moderate: {} red bars, net negative)",
                    analysisId, symbol, redCandles);
            direction = "BEARISH";
        } else {
            log.info("[CANDLE-DIRECTION][{}] {} = NEUTRAL (no clear direction)", analysisId, symbol);
            direction = "NEUTRAL";
        }

        return new CandleAnalysis(direction, greenCandles, redCandles, netChange);
    }

    /**
     * Original method for backward compatibility
     */
    private String analyzeCandleDirection(String symbol, String analysisId) {
        CandleAnalysis analysis = analyzeCandleDirectionDetailed(symbol, analysisId);
        return analysis != null ? analysis.direction : null;
    }

    /**
     * Checks if leaders are moving in the same direction as the signal
     * Returns number of leaders confirming (0-3)
     */
    private int countLeadersConfirmingDirection(String signalDirection, String analysisId) {
        int confirming = 0;

        for (String leader : LEADERS) {
            String leaderDirection = analyzeCandleDirection(leader, analysisId);

            if (leaderDirection == null) {
                log.info("[LEADER-DIRECTION][{}] {}: INSUFFICIENT DATA - skipped",
                        analysisId, leader);
                continue;
            }

            boolean matches = false;
            if (signalDirection.equals("CALL") && leaderDirection.equals("BULLISH")) {
                matches = true;
            } else if (signalDirection.equals("PUT") && leaderDirection.equals("BEARISH")) {
                matches = true;
            } else if (leaderDirection.equals("NEUTRAL")) {
                log.info("[LEADER-DIRECTION][{}] {}: {} - NEUTRAL (not counted)",
                        analysisId, leader, leaderDirection);
                continue;
            }

            log.info("[LEADER-DIRECTION][{}] {}: {} - {}",
                    analysisId, leader, leaderDirection, matches ? "✓ CONFIRMS" : "✗ CONFLICTS");

            if (matches) confirming++;
        }

        return confirming;
    }

    /**
     * Get detailed directional report for logging/debugging
     */
    public String getDirectionalReport(String signalAction, String analysisId) {
        StringBuilder report = new StringBuilder();
        report.append("\n========== DIRECTIONAL ANALYSIS ==========\n");
        report.append("Signal: ").append(signalAction).append("\n");

        String qqqDir = analyzeCandleDirection("QQQ", analysisId);
        report.append("QQQ Direction: ").append(qqqDir != null ? qqqDir : "UNKNOWN").append("\n");

        report.append("Leaders:\n");
        for (String leader : LEADERS) {
            String dir = analyzeCandleDirection(leader, analysisId);
            if (dir == null) {
                report.append("  ").append(leader).append(": INSUFFICIENT DATA\n");
                continue;
            }
            boolean confirms = (signalAction.equals("CALL") && dir.equals("BULLISH")) ||
                    (signalAction.equals("PUT") && dir.equals("BEARISH"));
            report.append("  ").append(leader).append(": ").append(dir)
                    .append(confirms ? " ✓" : " ✗").append("\n");
        }

        report.append("==========================================\n");
        return report.toString();
    }
}