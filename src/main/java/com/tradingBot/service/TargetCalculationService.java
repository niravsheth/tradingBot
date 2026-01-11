package com.tradingBot.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TargetCalculationService - Calculates proper level-based targets for 0DTE signals
 *
 * Target Priority for PUT signals (price above VWAP):
 * 1. VWAP (if distance >= $0.50)
 * 2. Today's Open
 * 3. Today's Low
 * 4. Gap fill levels (if gap up exists) - MORNING ONLY
 *
 * Target Priority for CALL signals (price below VWAP):
 * 1. VWAP (if distance >= $0.50)
 * 2. Today's Open
 * 3. Today's High
 * 4. Gap fill levels (if gap down exists) - MORNING ONLY
 *
 * For FLIPPED signals (breakout/breakdown):
 * - CALL breakout: Today's High or Previous Day High
 * - PUT breakdown: Today's Low or Previous Day Low
 *
 * Time-based rules:
 * - Morning (9:30-11:00): Gap fill levels allowed
 * - Midday (11:00-13:00): Today High/Low as max target
 * - Afternoon (13:00+): Today High/Low as max target
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TargetCalculationService {

    private final DailyLevelService dailyLevelService;

    private static final BigDecimal MIN_TARGET_DISTANCE = new BigDecimal("0.50");
    private static final LocalTime MORNING_END = LocalTime.of(11, 0);
    private static final LocalTime MIDDAY_END = LocalTime.of(13, 0);

    /**
     * Container for target calculation result
     */
    @Data
    public static class TargetResult {
        private BigDecimal targetPrice;
        private String targetLevel;      // Name of the level used as target
        private BigDecimal distance;     // Distance from entry to target
        private String reason;           // Detailed explanation
        private boolean isValid;         // Whether a valid target was found
        private List<String> levelsPassed; // Levels that were too close

        public TargetResult() {
            this.levelsPassed = new ArrayList<>();
        }
    }

    /**
     * Determine current trading window
     */
    private String getTradingWindow() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(MORNING_END)) {
            return "MORNING";
        } else if (now.isBefore(MIDDAY_END)) {
            return "MIDDAY";
        } else {
            return "AFTERNOON";
        }
    }

    /**
     * Check if gap fill targets are allowed based on time
     */
    private boolean isGapFillAllowed() {
        return getTradingWindow().equals("MORNING");
    }

    /**
     * Calculate target for mean reversion signals (non-flipped)
     *
     * @param entryPrice Current entry price
     * @param signalType "PUT" or "CALL"
     * @param vwap Current VWAP value
     * @param analysisId Tracking ID for logging
     * @return TargetResult with calculated target and explanation
     */
    public TargetResult calculateMeanReversionTarget(BigDecimal entryPrice, String signalType,
                                                     BigDecimal vwap, String analysisId) {
        log.info("[TARGET-CALC][{}] ========================================", analysisId);
        log.info("[TARGET-CALC][{}] Calculating MEAN REVERSION target", analysisId);
        log.info("[TARGET-CALC][{}] Signal Type: {}, Entry: ${}, VWAP: ${}",
                analysisId, signalType, entryPrice, vwap);
        log.info("[TARGET-CALC][{}] Trading Window: {}, Gap Fill Allowed: {}",
                analysisId, getTradingWindow(), isGapFillAllowed());

        TargetResult result = new TargetResult();
        DailyLevelService.DailyLevels levels = dailyLevelService.getQQQDailyLevels();

        if (levels == null) {
            log.error("[TARGET-CALC][{}] ✗ Daily levels not available", analysisId);
            result.setValid(false);
            result.setReason("Daily levels not initialized");
            return result;
        }

        logAvailableLevels(levels, vwap, analysisId);

        if (signalType.equals("PUT")) {
            return calculatePutTarget(entryPrice, vwap, levels, analysisId);
        } else {
            return calculateCallTarget(entryPrice, vwap, levels, analysisId);
        }
    }

    /**
     * Calculate target for PUT signal (expecting price to go DOWN)
     * Find first valid support level below entry
     */
    private TargetResult calculatePutTarget(BigDecimal entryPrice, BigDecimal vwap,
                                            DailyLevelService.DailyLevels levels, String analysisId) {
        log.info("[TARGET-CALC][{}] Calculating PUT target (looking for support BELOW entry)", analysisId);

        TargetResult result = new TargetResult();
        List<LevelCandidate> candidates = new ArrayList<>();

        // Priority 1: VWAP - Check first if it meets minimum distance
        if (vwap != null) {
            BigDecimal vwapDistance = entryPrice.subtract(vwap).abs();
            if (vwap.compareTo(entryPrice) < 0 && vwapDistance.compareTo(MIN_TARGET_DISTANCE) >= 0) {
                candidates.add(new LevelCandidate(vwap, "VWAP", 1));
                log.info("[TARGET-CALC][{}] VWAP at ${} is valid target (distance: ${})",
                        analysisId, vwap.setScale(2, RoundingMode.HALF_UP), vwapDistance.setScale(2, RoundingMode.HALF_UP));
            } else if (vwap.compareTo(entryPrice) < 0) {
                log.info("[TARGET-CALC][{}] VWAP at ${} too close (distance: ${} < $0.50), checking next levels",
                        analysisId, vwap.setScale(2, RoundingMode.HALF_UP), vwapDistance.setScale(2, RoundingMode.HALF_UP));
                result.getLevelsPassed().add("VWAP (too close: $" + vwapDistance.setScale(2, RoundingMode.HALF_UP) + ")");
            }
        }

        // Priority 2: Today's Open (if VWAP was skipped or not valid)
        if (levels.getTodayOpen() != null) {
            candidates.add(new LevelCandidate(levels.getTodayOpen(), "Today Open", 2));
        }

        // Priority 3: Today's Low
        if (levels.getTodayLow() != null) {
            candidates.add(new LevelCandidate(levels.getTodayLow(), "Today Low", 3));
        }

        // Priority 4: Gap fill levels - ONLY in morning
        if (isGapFillAllowed() && levels.isGapUp() && levels.isHasUnfilledGap()) {
            log.info("[TARGET-CALC][{}] ⬆️ Gap UP detected (MORNING) - adding gap fill levels", analysisId);

            if (levels.getPreviousDayHigh() != null) {
                candidates.add(new LevelCandidate(levels.getPreviousDayHigh(),
                        "Previous Day High (Gap Support)", 4));
            }
            if (levels.getPreviousDayClose() != null) {
                candidates.add(new LevelCandidate(levels.getPreviousDayClose(),
                        "Previous Day Close (Gap Fill)", 5));
            }
        } else if (levels.isGapUp() && levels.isHasUnfilledGap()) {
            log.info("[TARGET-CALC][{}] ⬆️ Gap UP detected but {} window - skipping gap fill targets",
                    analysisId, getTradingWindow());
        }

        // Priority 5: Previous Day Low (if not a gap situation)
        if (!levels.isGapUp() && levels.getPreviousDayLow() != null) {
            candidates.add(new LevelCandidate(levels.getPreviousDayLow(), "Previous Day Low", 6));
        }

        // Find first valid level BELOW entry with minimum distance
        return findFirstValidLevel(entryPrice, candidates, "BELOW", analysisId, result);
    }

    /**
     * Calculate target for CALL signal (expecting price to go UP)
     * Find first valid resistance level above entry
     */
    private TargetResult calculateCallTarget(BigDecimal entryPrice, BigDecimal vwap,
                                             DailyLevelService.DailyLevels levels, String analysisId) {
        log.info("[TARGET-CALC][{}] Calculating CALL target (looking for resistance ABOVE entry)", analysisId);

        TargetResult result = new TargetResult();
        List<LevelCandidate> candidates = new ArrayList<>();

        // Priority 1: VWAP - Check first if it meets minimum distance
        if (vwap != null) {
            BigDecimal vwapDistance = vwap.subtract(entryPrice).abs();
            if (vwap.compareTo(entryPrice) > 0 && vwapDistance.compareTo(MIN_TARGET_DISTANCE) >= 0) {
                candidates.add(new LevelCandidate(vwap, "VWAP", 1));
                log.info("[TARGET-CALC][{}] VWAP at ${} is valid target (distance: ${})",
                        analysisId, vwap.setScale(2, RoundingMode.HALF_UP), vwapDistance.setScale(2, RoundingMode.HALF_UP));
            } else if (vwap.compareTo(entryPrice) > 0) {
                log.info("[TARGET-CALC][{}] VWAP at ${} too close (distance: ${} < $0.50), checking next levels",
                        analysisId, vwap.setScale(2, RoundingMode.HALF_UP), vwapDistance.setScale(2, RoundingMode.HALF_UP));
                result.getLevelsPassed().add("VWAP (too close: $" + vwapDistance.setScale(2, RoundingMode.HALF_UP) + ")");
            }
        }

        // Priority 2: Today's Open (if above entry)
        if (levels.getTodayOpen() != null) {
            candidates.add(new LevelCandidate(levels.getTodayOpen(), "Today Open", 2));
        }

        // Priority 3: Today's High
        if (levels.getTodayHigh() != null) {
            candidates.add(new LevelCandidate(levels.getTodayHigh(), "Today High", 3));
        }

        // Priority 4: Gap fill levels - ONLY in morning
        if (isGapFillAllowed() && levels.isGapDown() && levels.isHasUnfilledGap()) {
            log.info("[TARGET-CALC][{}] ⬇️ Gap DOWN detected (MORNING) - adding gap fill levels", analysisId);

            if (levels.getPreviousDayLow() != null) {
                candidates.add(new LevelCandidate(levels.getPreviousDayLow(),
                        "Previous Day Low (Gap Resistance)", 4));
            }
            if (levels.getPreviousDayClose() != null) {
                candidates.add(new LevelCandidate(levels.getPreviousDayClose(),
                        "Previous Day Close (Gap Fill)", 5));
            }
        } else if (levels.isGapDown() && levels.isHasUnfilledGap()) {
            log.info("[TARGET-CALC][{}] ⬇️ Gap DOWN detected but {} window - skipping gap fill targets",
                    analysisId, getTradingWindow());
        }

        // Priority 5: Previous Day High (if not a gap situation)
        if (!levels.isGapDown() && levels.getPreviousDayHigh() != null) {
            candidates.add(new LevelCandidate(levels.getPreviousDayHigh(), "Previous Day High", 6));
        }

        // Find first valid level ABOVE entry with minimum distance
        return findFirstValidLevel(entryPrice, candidates, "ABOVE", analysisId, result);
    }

    /**
     * Calculate target for FLIPPED signals (breakout/breakdown)
     *
     * @param entryPrice Current entry price
     * @param signalType "PUT" (breakdown) or "CALL" (breakout) - AFTER flip
     * @param vwap Current VWAP value
     * @param analysisId Tracking ID for logging
     * @return TargetResult with calculated target
     */
    public TargetResult calculateBreakoutTarget(BigDecimal entryPrice, String signalType,
                                                BigDecimal vwap, String analysisId) {
        log.info("[TARGET-CALC][{}] ========================================", analysisId);
        log.info("[TARGET-CALC][{}] Calculating BREAKOUT/BREAKDOWN target", analysisId);
        log.info("[TARGET-CALC][{}] Signal Type: {} (flipped), Entry: ${}", analysisId, signalType, entryPrice);
        log.info("[TARGET-CALC][{}] Trading Window: {}", analysisId, getTradingWindow());

        TargetResult result = new TargetResult();
        DailyLevelService.DailyLevels levels = dailyLevelService.getQQQDailyLevels();

        if (levels == null) {
            log.error("[TARGET-CALC][{}] ✗ Daily levels not available", analysisId);
            result.setValid(false);
            result.setReason("Daily levels not initialized");
            return result;
        }

        logAvailableLevels(levels, vwap, analysisId);

        List<LevelCandidate> candidates = new ArrayList<>();

        if (signalType.equals("CALL")) {
            // CALL breakout - looking for resistance ABOVE entry
            log.info("[TARGET-CALC][{}] 🚀 BREAKOUT CALL - targeting resistance above", analysisId);

            // Priority 1: Today's High
            if (levels.getTodayHigh() != null) {
                candidates.add(new LevelCandidate(levels.getTodayHigh(), "Today High", 1));
            }

            // Priority 2: Previous Day High (only in morning for gap fill scenarios)
            if (isGapFillAllowed() && levels.getPreviousDayHigh() != null) {
                candidates.add(new LevelCandidate(levels.getPreviousDayHigh(), "Previous Day High", 2));
            }

            // Priority 3: Measured move from VWAP
            if (vwap != null) {
                BigDecimal extension = entryPrice.subtract(vwap).abs();
                BigDecimal measuredMove = entryPrice.add(extension);
                candidates.add(new LevelCandidate(measuredMove, "Measured Move (VWAP Extension)", 3));
            }

            return findFirstValidLevel(entryPrice, candidates, "ABOVE", analysisId, result);

        } else {
            // PUT breakdown - looking for support BELOW entry
            log.info("[TARGET-CALC][{}] 💥 BREAKDOWN PUT - targeting support below", analysisId);

            // Priority 1: Today's Low
            if (levels.getTodayLow() != null) {
                candidates.add(new LevelCandidate(levels.getTodayLow(), "Today Low", 1));
            }

            // Priority 2: Previous Day Low (only in morning for gap fill scenarios)
            if (isGapFillAllowed() && levels.getPreviousDayLow() != null) {
                candidates.add(new LevelCandidate(levels.getPreviousDayLow(), "Previous Day Low", 2));
            }

            // Priority 3: Measured move from VWAP
            if (vwap != null) {
                BigDecimal extension = entryPrice.subtract(vwap).abs();
                BigDecimal measuredMove = entryPrice.subtract(extension);
                candidates.add(new LevelCandidate(measuredMove, "Measured Move (VWAP Extension)", 3));
            }

            return findFirstValidLevel(entryPrice, candidates, "BELOW", analysisId, result);
        }
    }

    /**
     * Find first valid level in the correct direction with minimum distance
     */
    private TargetResult findFirstValidLevel(BigDecimal entryPrice, List<LevelCandidate> candidates,
                                             String direction, String analysisId, TargetResult result) {

        // Sort by priority
        candidates.sort(Comparator.comparingInt(c -> c.priority));

        log.info("[TARGET-CALC][{}] Evaluating {} candidate levels ({} direction):",
                analysisId, candidates.size(), direction);

        for (LevelCandidate candidate : candidates) {
            BigDecimal levelPrice = candidate.price;
            String levelName = candidate.name;

            // Check if level is in correct direction
            boolean validDirection;
            BigDecimal distance;

            if (direction.equals("BELOW")) {
                validDirection = levelPrice.compareTo(entryPrice) < 0;
                distance = entryPrice.subtract(levelPrice);
            } else { // ABOVE
                validDirection = levelPrice.compareTo(entryPrice) > 0;
                distance = levelPrice.subtract(entryPrice);
            }

            // Check minimum distance requirement
            boolean meetsMinDistance = distance.compareTo(MIN_TARGET_DISTANCE) >= 0;

            log.info("[TARGET-CALC][{}]   {} ${} - Distance: ${}, Direction: {}, Min Distance: {}",
                    analysisId, levelName, levelPrice.setScale(2, RoundingMode.HALF_UP),
                    distance.setScale(2, RoundingMode.HALF_UP),
                    validDirection ? "✓ Valid" : "✗ Wrong side",
                    meetsMinDistance ? "✓ OK" : "✗ Too close");

            if (!validDirection) {
                result.getLevelsPassed().add(levelName + " (wrong direction)");
                continue;
            }

            if (!meetsMinDistance) {
                result.getLevelsPassed().add(levelName + " (too close: $" + distance.setScale(2, RoundingMode.HALF_UP) + ")");
                continue;
            }

            // Found valid level with minimum distance
            result.setTargetPrice(levelPrice);
            result.setTargetLevel(levelName);
            result.setDistance(distance);
            result.setValid(true);
            result.setReason(String.format("Target: %s at $%s (distance: $%s)",
                    levelName,
                    levelPrice.setScale(2, RoundingMode.HALF_UP),
                    distance.setScale(2, RoundingMode.HALF_UP)));

            log.info("[TARGET-CALC][{}] ✓ TARGET SELECTED: {} at ${}",
                    analysisId, levelName, levelPrice.setScale(2, RoundingMode.HALF_UP));
            log.info("[TARGET-CALC][{}]   Entry: ${}, Target: ${}, Distance: ${}",
                    analysisId, entryPrice, levelPrice.setScale(2, RoundingMode.HALF_UP),
                    distance.setScale(2, RoundingMode.HALF_UP));
            log.info("[TARGET-CALC][{}] ========================================", analysisId);

            return result;
        }

        // No valid level found
        log.warn("[TARGET-CALC][{}] ✗ NO VALID TARGET LEVEL FOUND", analysisId);
        log.warn("[TARGET-CALC][{}]   Levels passed: {}", analysisId, result.getLevelsPassed());
        log.info("[TARGET-CALC][{}] ========================================", analysisId);

        result.setValid(false);
        result.setReason("No valid target level found with minimum distance ($" + MIN_TARGET_DISTANCE + ")");

        return result;
    }

    /**
     * Log all available levels for debugging
     */
    private void logAvailableLevels(DailyLevelService.DailyLevels levels, BigDecimal vwap, String analysisId) {
        log.info("[TARGET-CALC][{}] Available Levels:", analysisId);
        log.info("[TARGET-CALC][{}]   VWAP:              ${}", analysisId,
                vwap != null ? vwap.setScale(2, RoundingMode.HALF_UP) : "N/A");
        log.info("[TARGET-CALC][{}]   Today Open:        ${}", analysisId,
                levels.getTodayOpen() != null ? levels.getTodayOpen().setScale(2, RoundingMode.HALF_UP) : "N/A");
        log.info("[TARGET-CALC][{}]   Today High:        ${}", analysisId,
                levels.getTodayHigh() != null ? levels.getTodayHigh().setScale(2, RoundingMode.HALF_UP) : "N/A");
        log.info("[TARGET-CALC][{}]   Today Low:         ${}", analysisId,
                levels.getTodayLow() != null ? levels.getTodayLow().setScale(2, RoundingMode.HALF_UP) : "N/A");
        log.info("[TARGET-CALC][{}]   Prev Day High:     ${}", analysisId,
                levels.getPreviousDayHigh() != null ? levels.getPreviousDayHigh().setScale(2, RoundingMode.HALF_UP) : "N/A");
        log.info("[TARGET-CALC][{}]   Prev Day Low:      ${}", analysisId,
                levels.getPreviousDayLow() != null ? levels.getPreviousDayLow().setScale(2, RoundingMode.HALF_UP) : "N/A");
        log.info("[TARGET-CALC][{}]   Prev Day Close:    ${}", analysisId,
                levels.getPreviousDayClose() != null ? levels.getPreviousDayClose().setScale(2, RoundingMode.HALF_UP) : "N/A");
        log.info("[TARGET-CALC][{}]   Gap:               ${} ({})", analysisId,
                levels.getGap() != null ? levels.getGap().setScale(2, RoundingMode.HALF_UP) : "N/A",
                levels.isGapUp() ? "GAP UP" : levels.isGapDown() ? "GAP DOWN" : "NO GAP");
        log.info("[TARGET-CALC][{}]   Unfilled Gap:      {}", analysisId, levels.isHasUnfilledGap());
    }

    /**
     * Helper class for level candidates
     */
    private static class LevelCandidate {
        BigDecimal price;
        String name;
        int priority;

        LevelCandidate(BigDecimal price, String name, int priority) {
            this.price = price;
            this.name = name;
            this.priority = priority;
        }
    }
}