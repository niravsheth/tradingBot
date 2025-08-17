package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import com.tradingBot.model.Quote;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalConfirmationService {

    private final TradierService tradierService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final TelegramService telegramService;

    // Manual override storage
    private final Map<String, ManualOverride> activeOverrides = new ConcurrentHashMap<>();

    @Value("${confirmation.min-atr-multiplier:1.0}")
    private double minAtrMultiplier;

    @Value("${confirmation.max-iv-rank:90}")
    private int maxIvRank;

    @Value("${confirmation.min-volume-ratio:1.0}")
    private double minVolumeRatio;

    @Value("${confirmation.constituent-correlation-threshold:0.6}")
    private double constituentCorrelationThreshold;

    @Data
    public static class ConfirmationResult {
        private boolean confirmed;
        private double finalConfidence;
        private List<String> confirmationFactors;
        private List<String> rejectionReasons;
        private Map<String, Double> componentScores;
        private boolean manualOverride;
        private String overrideReason;
        private LocalDateTime timestamp;

        public ConfirmationResult() {
            this.confirmationFactors = new ArrayList<>();
            this.rejectionReasons = new ArrayList<>();
            this.componentScores = new HashMap<>();
            this.timestamp = LocalDateTime.now();
        }
    }

    public ConfirmationResult confirmSignal(Signal signal) {
        ConfirmationResult result = new ConfirmationResult();

        try {
            // Check for manual override first
            ManualOverride override = checkManualOverride(signal);
            if (override != null) {
                result.setManualOverride(true);
                result.setOverrideReason(override.getReason());

                if (override.getAction() == OverrideAction.FORCE_ACCEPT) {
                    result.setConfirmed(true);
                    result.setFinalConfidence(override.getConfidenceAdjustment());
                    log.info("Manual override ACCEPTED signal: {}", signal.getOptionSymbol());
                    return result;
                } else if (override.getAction() == OverrideAction.FORCE_REJECT) {
                    result.setConfirmed(false);
                    result.getRejectionReasons().add("Manual override: " + override.getReason());
                    log.info("Manual override REJECTED signal: {}", signal.getOptionSymbol());
                    return result;
                }
            }

            // Automated confirmations
            double atrScore = confirmWithATR(signal, result);
            double volumeScore = confirmWithVolume(signal, result);
            double pocScore = confirmWithPOC(signal, result);
            double constituentScore = confirmWithConstituents(signal, result);
            double ivScore = confirmWithIV(signal, result);
            double flowScore = confirmWithOptionsFlow(signal, result);
            double momentumScore = confirmWithMomentum(signal, result);

            // Store component scores
            result.getComponentScores().put("ATR", atrScore);
            result.getComponentScores().put("Volume", volumeScore);
            result.getComponentScores().put("POC", pocScore);
            result.getComponentScores().put("Constituents", constituentScore);
            result.getComponentScores().put("IV", ivScore);
            result.getComponentScores().put("Flow", flowScore);
            result.getComponentScores().put("Momentum", momentumScore);

            // Calculate weighted final score
            double finalScore = calculateWeightedScore(result.getComponentScores(), signal);

            // Apply event-driven blocks
            if (shouldBlockForEvents(signal, result)) {
                result.setConfirmed(false);
                result.getRejectionReasons().add("Blocked due to upcoming event");
                return result;
            }

            // Automated IV override
            if (isIVTooHigh(signal)) {
                result.setConfirmed(false);
                result.getRejectionReasons().add("IV rank > " + maxIvRank + "% - too expensive");
                return result;
            }

            // Time-based adjustments
            double timeAdjustment = getTimeBasedAdjustment();
            finalScore *= timeAdjustment;

            // Final confirmation threshold
            double threshold = getConfirmationThreshold(signal);
            result.setConfirmed(finalScore >= threshold);
            result.setFinalConfidence(finalScore);

            if (!result.isConfirmed()) {
                result.getRejectionReasons().add(String.format(
                        "Final score %.2f below threshold %.2f", finalScore, threshold));
            }

            // Log detailed results
            logConfirmationDetails(signal, result);

        } catch (Exception e) {
            log.error("Error in signal confirmation: {}", e.getMessage());
            result.setConfirmed(false);
            result.getRejectionReasons().add("Confirmation error: " + e.getMessage());
        }

        return result;
    }

    private double confirmWithATR(Signal signal, ConfirmationResult result) {
        try {
            BigDecimal currentATR = calculateCurrentATR(signal.getSymbol());
            BigDecimal historicalATR = getHistoricalATR(signal.getSymbol(), 20);

            if (currentATR == null || historicalATR == null) {
                result.getRejectionReasons().add("Unable to calculate ATR");
                return 0.0;
            }

            double atrRatio = currentATR.divide(historicalATR, 2, RoundingMode.HALF_UP).doubleValue();

            if (atrRatio >= minAtrMultiplier) {
                result.getConfirmationFactors().add(String.format("ATR ratio %.2fx (good volatility)", atrRatio));
                return Math.min(atrRatio / 2.0, 1.0); // Cap at 1.0
            } else {
                result.getRejectionReasons().add(String.format("ATR ratio %.2fx below minimum", atrRatio));
                return atrRatio / 2.0;
            }
        } catch (Exception e) {
            log.error("ATR confirmation error: {}", e.getMessage());
            return 0.5; // Neutral on error
        }
    }

    private double confirmWithVolume(Signal signal, ConfirmationResult result) {
        try {
            Quote quote = tradierService.getQuote(signal.getSymbol()).getQuote();
            if (quote == null || quote.getVolume() == null || quote.getAverageVolume() == null) {
                return 0.5;
            }

            double volumeRatio = quote.getVolume().doubleValue() / quote.getAverageVolume().doubleValue();

            if (volumeRatio >= minVolumeRatio) {
                result.getConfirmationFactors().add(String.format("Volume %.1fx average", volumeRatio));
                return Math.min(volumeRatio / 3.0, 1.0);
            } else {
                result.getRejectionReasons().add(String.format("Low volume %.1fx", volumeRatio));
                return volumeRatio / 3.0;
            }
        } catch (Exception e) {
            log.error("Volume confirmation error: {}", e.getMessage());
            return 0.5;
        }
    }

    private double confirmWithPOC(Signal signal, ConfirmationResult result) {
        try {
            // Point of Control analysis - where most volume traded
            BigDecimal poc = calculatePOC(signal.getSymbol());
            Quote quote = tradierService.getQuote(signal.getSymbol()).getQuote();

            if (poc == null || quote == null) {
                return 0.5;
            }

            BigDecimal currentPrice = quote.getLast();
            BigDecimal pocDistance = currentPrice.subtract(poc).abs()
                    .divide(currentPrice, 4, RoundingMode.HALF_UP);

            // Closer to POC = more stable
            if (pocDistance.compareTo(BigDecimal.valueOf(0.005)) < 0) {
                result.getConfirmationFactors().add("Price near POC (stable)");
                return 0.8;
            } else if (pocDistance.compareTo(BigDecimal.valueOf(0.01)) < 0) {
                result.getConfirmationFactors().add("Price within 1% of POC");
                return 0.6;
            } else {
                result.getRejectionReasons().add(String.format("Price %.1f%% from POC",
                        pocDistance.multiply(BigDecimal.valueOf(100))));
                return 0.4;
            }
        } catch (Exception e) {
            log.error("POC confirmation error: {}", e.getMessage());
            return 0.5;
        }
    }

    private double confirmWithConstituents(Signal signal, ConfirmationResult result) {
        try {
            String[] constituents = {"MSFT", "NVDA", "AAPL", "TSLA"};
            Map<String, Quote> quotes = tradierService.getMultipleQuotes(String.join(",", constituents));

            int alignedCount = 0;
            int totalCount = 0;

            boolean isCallSignal = signal.getOptionSymbol().contains("C");

            for (Map.Entry<String, Quote> entry : quotes.entrySet()) {
                Quote quote = entry.getValue();
                if (quote != null && quote.getLast() != null && quote.getPreviousClose() != null) {
                    totalCount++;

                    BigDecimal change = quote.getLast().subtract(quote.getPreviousClose());
                    boolean isPositive = change.compareTo(BigDecimal.ZERO) > 0;

                    if ((isCallSignal && isPositive) || (!isCallSignal && !isPositive)) {
                        alignedCount++;
                    }
                }
            }

            double alignmentRatio = totalCount > 0 ? (double) alignedCount / totalCount : 0.0;

            if (alignmentRatio >= constituentCorrelationThreshold) {
                result.getConfirmationFactors().add(String.format(
                        "%.0f%% constituent alignment", alignmentRatio * 100));
                return alignmentRatio;
            } else {
                result.getRejectionReasons().add(String.format(
                        "Low constituent alignment %.0f%%", alignmentRatio * 100));
                return alignmentRatio;
            }
        } catch (Exception e) {
            log.error("Constituent confirmation error: {}", e.getMessage());
            return 0.5;
        }
    }

    private double confirmWithIV(Signal signal, ConfirmationResult result) {
        try {
            // Get current IV rank
            int ivRank = calculateIVRank(signal.getOptionSymbol());

            if (ivRank > maxIvRank) {
                result.getRejectionReasons().add(String.format("IV rank %d%% too high", ivRank));
                return 0.2;
            } else if (ivRank > 70) {
                result.getConfirmationFactors().add(String.format("Elevated IV %d%% (caution)", ivRank));
                return 0.6;
            } else {
                result.getConfirmationFactors().add(String.format("Normal IV %d%%", ivRank));
                return 0.9;
            }
        } catch (Exception e) {
            log.error("IV confirmation error: {}", e.getMessage());
            return 0.5;
        }
    }

    private double confirmWithMomentum(Signal signal, ConfirmationResult result) {
        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(signal.getSymbol());

            if (ta == null) {
                result.getRejectionReasons().add("Technical analysis failed");
                return 0.5;
            }

            boolean isCallSignal = signal.getOptionSymbol().contains("C");
            double momentum = ta.getMomentumStrength();

            if ((isCallSignal && momentum > 0.5) || (!isCallSignal && momentum < -0.5)) {
                result.getConfirmationFactors().add(String.format(
                        "Strong momentum %.2f aligned with signal", momentum));
                return 0.9;
            } else if ((isCallSignal && momentum > 0) || (!isCallSignal && momentum < 0)) {
                result.getConfirmationFactors().add("Momentum aligned");
                return 0.7;
            } else {
                result.getRejectionReasons().add("Momentum not aligned");
                return 0.3;
            }
        } catch (Exception e) {
            log.error("Momentum confirmation error: {}", e.getMessage());
            return 0.5;
        }
    }



    private boolean shouldBlockForEvents(Signal signal, ConfirmationResult result) {
        // Check for upcoming events
        LocalDateTime now = LocalDateTime.now();

        // Fed announcements typically at 2 PM ET
        if (now.toLocalTime().isAfter(LocalTime.of(13, 45)) &&
                now.toLocalTime().isBefore(LocalTime.of(14, 15))) {
            if (isFedDay()) {
                result.getRejectionReasons().add("Blocked: Fed announcement window");
                return true;
            }
        }

        // Earnings for major QQQ components
        if (hasUpcomingEarnings(signal.getSymbol())) {
            result.getRejectionReasons().add("Blocked: Earnings announcement pending");
            return true;
        }

        // Major economic data releases (8:30 AM ET)
        if (now.toLocalTime().isAfter(LocalTime.of(8, 15)) &&
                now.toLocalTime().isBefore(LocalTime.of(8, 45))) {
            if (hasMajorEconomicRelease()) {
                result.getRejectionReasons().add("Blocked: Economic data release");
                return true;
            }
        }

        return false;
    }

    private double getTimeBasedAdjustment() {
        LocalTime now = LocalTime.now();

        // Reduce confidence near market close
        if (now.isAfter(LocalTime.of(15, 30))) {
            return 0.7; // 30% reduction
        } else if (now.isAfter(LocalTime.of(15, 0))) {
            return 0.85; // 15% reduction
        }

        // Reduce confidence in first 30 minutes
        if (now.isBefore(LocalTime.of(10, 0))) {
            return 0.9; // 10% reduction
        }

        return 1.0; // No adjustment
    }

    private double getConfirmationThreshold(Signal signal) {
        // Higher threshold for riskier strategies
        if (signal.getStrategy().contains("REVERSION")) {
            return 0.75;
        } else if (signal.getStrategy().contains("UNUSUAL_FLOW")) {
            return 0.65;
        } else {
            return 0.70;
        }
    }

    // Manual override methods
    public void addManualOverride(String optionSymbol, OverrideAction action,
                                  String reason, double confidenceAdjustment) {
        ManualOverride override = new ManualOverride();
        override.setOptionSymbol(optionSymbol);
        override.setAction(action);
        override.setReason(reason);
        override.setConfidenceAdjustment(confidenceAdjustment);
        override.setCreatedAt(LocalDateTime.now());
        override.setExpiresAt(LocalDateTime.now().plusMinutes(30)); // 30 min expiry

        activeOverrides.put(optionSymbol, override);

        log.info("Manual override added for {}: {} - {}", optionSymbol, action, reason);
        telegramService.sendMessage(String.format(
                "🔧 Manual Override Set\nOption: %s\nAction: %s\nReason: %s\nExpires: 30 min",
                optionSymbol, action, reason
        ));
    }

    private ManualOverride checkManualOverride(Signal signal) {
        ManualOverride override = activeOverrides.get(signal.getOptionSymbol());

        if (override != null) {
            if (override.getExpiresAt().isBefore(LocalDateTime.now())) {
                activeOverrides.remove(signal.getOptionSymbol());
                return null;
            }
            return override;
        }

        return null;
    }

    // Helper methods (simplified implementations)
    private BigDecimal calculateCurrentATR(String symbol) {
        // In production, calculate from recent price data
        Quote quote = tradierService.getQuote(symbol).getQuote();
        if (quote != null && quote.getHigh() != null && quote.getLow() != null) {
            return quote.getHigh().subtract(quote.getLow());
        }
        return null;
    }

    private BigDecimal getHistoricalATR(String symbol, int days) {
        // In production, get from historical data
        return calculateCurrentATR(symbol); // Simplified
    }

    private BigDecimal calculatePOC(String symbol) {
        // In production, calculate from volume profile
        Quote quote = tradierService.getQuote(symbol).getQuote();
        if (quote != null) {
            // Simplified: use VWAP as proxy for POC
            return quote.getLast(); // Would use actual VWAP
        }
        return null;
    }

    private int calculateIVRank(String optionSymbol) {
        // In production, calculate from historical IV data
        return 50; // Placeholder
    }

    private boolean isIVTooHigh(Signal signal) {
        return calculateIVRank(signal.getOptionSymbol()) > maxIvRank;
    }

    private boolean isFedDay() {
        // In production, check Fed calendar
        return false;
    }

    private boolean hasUpcomingEarnings(String symbol) {
        // In production, check earnings calendar
        return false;
    }

    private boolean hasMajorEconomicRelease() {
        // In production, check economic calendar
        return false;
    }

    private void logConfirmationDetails(Signal signal, ConfirmationResult result) {
        StringBuilder details = new StringBuilder();
        details.append(String.format("\n=== Signal Confirmation for %s ===\n", signal.getOptionSymbol()));
        details.append(String.format("Strategy: %s\n", signal.getStrategy()));
        details.append(String.format("Final Score: %.2f\n", result.getFinalConfidence()));
        details.append(String.format("Confirmed: %s\n", result.isConfirmed()));

        details.append("\nComponent Scores:\n");
        result.getComponentScores().forEach((k, v) ->
                details.append(String.format("  %s: %.2f\n", k, v)));

        if (!result.getConfirmationFactors().isEmpty()) {
            details.append("\nConfirmation Factors:\n");
            result.getConfirmationFactors().forEach(f ->
                    details.append(String.format("  ✓ %s\n", f)));
        }

        if (!result.getRejectionReasons().isEmpty()) {
            details.append("\nRejection Reasons:\n");
            result.getRejectionReasons().forEach(r ->
                    details.append(String.format("  ✗ %s\n", r)));
        }

        log.info(details.toString());
    }

    // Data classes
    @Data
    private static class ManualOverride {
        private String optionSymbol;
        private OverrideAction action;
        private String reason;
        private double confidenceAdjustment;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
    }

    public enum OverrideAction {
        FORCE_ACCEPT,
        FORCE_REJECT,
        ADJUST_CONFIDENCE
    }

    // Placeholder for TechnicalAnalysis
    private static class TechnicalAnalysiss {
        public double getMomentumStrength() { return 0.0; }
    }



    // REPLACE THE confirmWithOptionsFlow METHOD WITH THIS:
    private double confirmWithOptionsFlow(Signal signal, ConfirmationResult result) {
        try {
            // Get option details
            // This is a simplified approach - in production you'd get the actual option
            String optionSymbol = signal.getOptionSymbol();

            // Use the enhanced flow analyzer to check current flow conditions
            // For now, return neutral score since we need the actual option object
            // This should be enhanced to get the option from the signal

            result.getConfirmationFactors().add("Options flow analysis neutral");
            return 0.6; // Neutral if no special flow conditions

        } catch (Exception e) {
            log.error("Options flow confirmation error: {}", e.getMessage());
            return 0.5;
        }
    }

    // ADD THIS HELPER METHOD FOR BETTER INTEGRATION:
    private double confirmWithTechnicalAlignment(Signal signal, ConfirmationResult result) {
        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(signal.getSymbol());

            if (ta == null) {
                result.getRejectionReasons().add("Technical analysis unavailable");
                return 0.3;
            }

            boolean isCallSignal = signal.getOptionSymbol().contains("C");
            String trend = ta.getTrend();

            // Check trend alignment
            boolean aligned = (isCallSignal && "UP".equals(trend)) ||
                    (!isCallSignal && "DOWN".equals(trend));

            if (aligned) {
                result.getConfirmationFactors().add(String.format(
                        "Signal aligned with %s trend", trend));
                return 0.8;
            } else if ("NEUTRAL".equals(trend)) {
                result.getConfirmationFactors().add("Neutral trend - proceed with caution");
                return 0.6;
            } else {
                result.getRejectionReasons().add(String.format(
                        "Signal against %s trend", trend));
                return 0.2;
            }
        } catch (Exception e) {
            log.error("Technical alignment confirmation error: {}", e.getMessage());
            return 0.5;
        }
    }

    // UPDATE THE calculateWeightedScore METHOD TO INCLUDE TECHNICAL ALIGNMENT:
    private double calculateWeightedScore(Map<String, Double> scores, Signal signal) {
        // Add technical alignment score
        ConfirmationResult tempResult = new ConfirmationResult();
        double techAlignmentScore = confirmWithTechnicalAlignment(signal, tempResult);
        scores.put("TechnicalAlignment", techAlignmentScore);

        // Dynamic weights based on strategy
        Map<String, Double> weights = getStrategyWeights(signal.getStrategy());

        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            String component = entry.getKey();
            Double score = entry.getValue();
            Double weight = weights.getOrDefault(component, 1.0);

            weightedSum += score * weight;
            totalWeight += weight;
        }

        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    // UPDATE THE getStrategyWeights METHOD TO INCLUDE TECHNICAL ALIGNMENT:
    private Map<String, Double> getStrategyWeights(String strategy) {
        Map<String, Double> weights = new HashMap<>();

        if (strategy.contains("UNUSUAL_FLOW")) {
            weights.put("Flow", 3.0);
            weights.put("Volume", 2.0);
            weights.put("IV", 1.5);
            weights.put("Constituents", 1.0);
            weights.put("ATR", 1.0);
            weights.put("POC", 0.5);
            weights.put("Momentum", 1.5);
            weights.put("TechnicalAlignment", 2.5); // High weight for flow strategies
        } else if (strategy.contains("BREAKOUT")) {
            weights.put("Momentum", 3.0);
            weights.put("Volume", 2.5);
            weights.put("ATR", 2.0);
            weights.put("Constituents", 1.5);
            weights.put("Flow", 1.0);
            weights.put("IV", 1.0);
            weights.put("POC", 0.5);
            weights.put("TechnicalAlignment", 3.0); // Very high weight for breakouts
        } else {
            // Default weights
            weights.put("ATR", 1.0);
            weights.put("Volume", 1.0);
            weights.put("POC", 1.0);
            weights.put("Constituents", 1.0);
            weights.put("IV", 1.0);
            weights.put("Flow", 1.0);
            weights.put("Momentum", 1.0);
            weights.put("TechnicalAlignment", 2.0); // Important for all strategies
        }

        return weights;
    }
}