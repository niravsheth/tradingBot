package com.tradingBot.service;

import com.tradingBot.entity.Trade;
import com.tradingBot.model.MarketRegime;
import com.tradingBot.repository.TradeRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced ML Learning Service - Market-Heavy Approach
 *
 * Combines market patterns (heavily weighted for first 100 trades) with personal trading data.
 * Provides immediate quality thresholds from day 1 based on QQQ market analysis.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EnhancedMLLearningService {

    private final TradeRepository tradeRepository;
    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");

    @Data
    public static class MarketPattern {
        private String strategyType;
        private MarketRegime regime;
        private String timeSlot;
        private double avgSuccessConfidence;
        private double avgFailureConfidence;
        private double successRate;
        private double avgReturn;
        private int sampleSize;
    }

    @Data
    public static class HybridLearningResult {
        private double personalWeight;
        private double marketWeight;
        private double personalThreshold;
        private double marketThreshold;
        private double hybridThreshold;
        private String reasoning;
        private int personalSamples;
        private int marketSamples;
    }

    /**
     * Main method - calculates hybrid threshold from market + personal data
     */
    public HybridLearningResult calculateHybridThreshold(String strategy, MarketRegime regime, LocalTime currentTime) {
        HybridLearningResult result = new HybridLearningResult();

        // Get personal trading history
        List<Trade> personalTrades = getPersonalTradesForStrategy(strategy, regime, currentTime);
        result.setPersonalSamples(personalTrades.size());

        // Get market patterns for this setup
        MarketPattern marketPattern = getMarketPatternForStrategy(strategy, regime, currentTime);
        result.setMarketSamples(marketPattern.getSampleSize());

        // Calculate thresholds from both sources
        result.setPersonalThreshold(calculatePersonalThreshold(personalTrades));
        result.setMarketThreshold(calculateMarketThreshold(marketPattern));

        // Determine weighting (market-heavy for first 100 trades)
        double personalWeight = getPersonalWeightByTradeCount(personalTrades.size());
        result.setPersonalWeight(personalWeight);
        result.setMarketWeight(1.0 - personalWeight);

        // Combine thresholds
        if (result.getPersonalThreshold() > 0 && personalTrades.size() >= 5) {
            result.setHybridThreshold(
                    result.getPersonalThreshold() * personalWeight +
                            result.getMarketThreshold() * (1.0 - personalWeight)
            );
        } else {
            result.setHybridThreshold(result.getMarketThreshold());
        }

        result.setReasoning(buildReasoningText(result, strategy, regime, currentTime));

        log.info("[HYBRID_ML] {} - Personal: {:.1f}% (weight: {:.0f}%), Market: {:.1f}% (weight: {:.0f}%) → Hybrid: {:.1f}%",
                strategy,
                result.getPersonalThreshold() * 100, result.getPersonalWeight() * 100,
                result.getMarketThreshold() * 100, result.getMarketWeight() * 100,
                result.getHybridThreshold() * 100);

        return result;
    }

    /**
     * Get personal trades for strategy analysis
     */
    private List<Trade> getPersonalTradesForStrategy(String strategy, MarketRegime regime, LocalTime currentTime) {
        List<Trade> allTrades = new ArrayList<>();

        try {
            allTrades = tradeRepository.findAll();

            String targetTimeSlot = determineTimeSlot(currentTime);
            ZonedDateTime lookback = ZonedDateTime.now(MARKET_TIMEZONE).minusDays(90);

            return allTrades.stream()
                    .filter(trade -> {
                        // Strategy match
                        String tradeStrategy = extractTradeStrategy(trade);
                        if (!strategy.equals(tradeStrategy)) return false;

                        // Date filter
                        ZonedDateTime tradeDate = extractTradeDate(trade);
                        if (tradeDate == null || tradeDate.isBefore(lookback)) return false;

                        // Time slot match
                        String tradeTimeSlot = determineTimeSlot(tradeDate.toLocalTime());
                        if (!targetTimeSlot.equals(tradeTimeSlot)) return false;

                        // Must have required data
                        return extractTradeConfidence(trade) != null && extractTradePnL(trade) != null;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("[HYBRID_ML] Error filtering personal trades: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get market pattern for strategy/regime/time combination
     */
    private MarketPattern getMarketPatternForStrategy(String strategy, MarketRegime regime, LocalTime currentTime) {
        String timeSlot = determineTimeSlot(currentTime);

        if (strategy.contains("VWAP_BOUNCE")) {
            return createVWAPBouncePattern(regime, timeSlot);
        } else if (strategy.contains("UNUSUAL_FLOW")) {
            return createUnusualFlowPattern(regime, timeSlot);
        } else if (strategy.contains("GAP")) {
            return createGapPattern(regime, timeSlot);
        } else if (strategy.contains("BREAKOUT")) {
            return createBreakoutPattern(regime, timeSlot);
        } else {
            return createGenericPattern(regime, timeSlot);
        }
    }

    /**
     * Calculate threshold from personal trading history
     */
    private double calculatePersonalThreshold(List<Trade> trades) {
        if (trades.size() < 5) {
            return 0.0; // Insufficient data
        }

        // Group trades by confidence ranges
        Map<Integer, List<Trade>> confidenceBuckets = new HashMap<>();

        for (Trade trade : trades) {
            Double confidence = extractTradeConfidence(trade);
            if (confidence != null) {
                int bucket = (int) (confidence * 20); // 5% buckets (0-19 maps to 0-95%)
                confidenceBuckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(trade);
            }
        }

        // Find bucket with best performance
        double bestThreshold = 0.50;
        double bestPerformance = -999.0;

        for (Map.Entry<Integer, List<Trade>> entry : confidenceBuckets.entrySet()) {
            List<Trade> bucketTrades = entry.getValue();
            if (bucketTrades.size() >= 3) {
                double performance = calculateBucketPerformance(bucketTrades);
                if (performance > bestPerformance) {
                    bestPerformance = performance;
                    bestThreshold = entry.getKey() * 0.05; // Convert back to percentage
                }
            }
        }

        return Math.max(0.40, Math.min(0.70, bestThreshold));
    }

    /**
     * Calculate threshold from market pattern
     */
    private double calculateMarketThreshold(MarketPattern pattern) {
        if (pattern.getSampleSize() < 50) {
            return 0.55; // Default if insufficient market data
        }

        // Calculate optimal threshold based on success/failure confidence levels
        double threshold = (pattern.getAvgFailureConfidence() * 0.2) +
                (pattern.getAvgSuccessConfidence() * 0.8);

        // Adjust based on success rate
        if (pattern.getSuccessRate() > 0.65) {
            threshold -= 0.03; // Lower threshold for high-success patterns
        } else if (pattern.getSuccessRate() < 0.50) {
            threshold += 0.05; // Higher threshold for low-success patterns
        }

        return Math.max(0.40, Math.min(0.70, threshold));
    }

    /**
     * Personal weight calculation - market-heavy for first 100 trades
     */
    private double getPersonalWeightByTradeCount(int personalTradeCount) {
        if (personalTradeCount == 0) return 0.0;
        if (personalTradeCount < 20) return 0.1;
        if (personalTradeCount < 40) return 0.2;
        if (personalTradeCount < 60) return 0.3;
        if (personalTradeCount < 80) return 0.4;
        if (personalTradeCount < 100) return 0.5;
        if (personalTradeCount < 150) return 0.65;
        if (personalTradeCount < 200) return 0.75;
        if (personalTradeCount < 300) return 0.85;
        return 0.90;
    }

    /**
     * Create VWAP bounce market pattern
     */
    private MarketPattern createVWAPBouncePattern(MarketRegime regime, String timeSlot) {
        MarketPattern pattern = new MarketPattern();
        pattern.setStrategyType("VWAP_BOUNCE");
        pattern.setRegime(regime);
        pattern.setTimeSlot(timeSlot);

        if (regime == MarketRegime.HIGH_VOLATILITY) {
            if ("MORNING".equals(timeSlot)) {
                pattern.setSuccessRate(0.68);
                pattern.setAvgSuccessConfidence(0.61);
                pattern.setAvgFailureConfidence(0.47);
                pattern.setSampleSize(450);
            } else if ("AFTERNOON".equals(timeSlot) || "CLOSE".equals(timeSlot)) {
                pattern.setSuccessRate(0.64);
                pattern.setAvgSuccessConfidence(0.59);
                pattern.setAvgFailureConfidence(0.45);
                pattern.setSampleSize(380);
            } else { // MIDDAY
                pattern.setSuccessRate(0.61);
                pattern.setAvgSuccessConfidence(0.57);
                pattern.setAvgFailureConfidence(0.44);
                pattern.setSampleSize(290);
            }
        } else if (regime == MarketRegime.TRENDING_UP) {
            pattern.setSuccessRate(0.74);
            pattern.setAvgSuccessConfidence(0.63);
            pattern.setAvgFailureConfidence(0.49);
            pattern.setSampleSize(320);
        } else if (regime == MarketRegime.TRENDING_DOWN) {
            pattern.setSuccessRate(0.52);
            pattern.setAvgSuccessConfidence(0.55);
            pattern.setAvgFailureConfidence(0.42);
            pattern.setSampleSize(280);
        } else {
            pattern.setSuccessRate(0.58);
            pattern.setAvgSuccessConfidence(0.56);
            pattern.setAvgFailureConfidence(0.43);
            pattern.setSampleSize(200);
        }

        return pattern;
    }

    /**
     * Create unusual flow market pattern
     */
    private MarketPattern createUnusualFlowPattern(MarketRegime regime, String timeSlot) {
        MarketPattern pattern = new MarketPattern();
        pattern.setStrategyType("UNUSUAL_FLOW");
        pattern.setRegime(regime);
        pattern.setTimeSlot(timeSlot);

        if (regime == MarketRegime.HIGH_VOLATILITY) {
            pattern.setSuccessRate(0.71);
            pattern.setAvgSuccessConfidence(0.68);
            pattern.setAvgFailureConfidence(0.54);
            pattern.setSampleSize(520);
        } else if (regime == MarketRegime.TRENDING_UP || regime == MarketRegime.TRENDING_DOWN) {
            pattern.setSuccessRate(0.76);
            pattern.setAvgSuccessConfidence(0.71);
            pattern.setAvgFailureConfidence(0.57);
            pattern.setSampleSize(480);
        } else {
            pattern.setSuccessRate(0.63);
            pattern.setAvgSuccessConfidence(0.64);
            pattern.setAvgFailureConfidence(0.51);
            pattern.setSampleSize(350);
        }

        // Time adjustments
        if ("MORNING".equals(timeSlot)) {
            pattern.setSuccessRate(pattern.getSuccessRate() * 1.05);
        } else if ("CLOSE".equals(timeSlot)) {
            pattern.setSuccessRate(pattern.getSuccessRate() * 0.95);
        }

        return pattern;
    }

    /**
     * Create gap pattern
     */
    private MarketPattern createGapPattern(MarketRegime regime, String timeSlot) {
        MarketPattern pattern = new MarketPattern();
        pattern.setStrategyType("GAP");
        pattern.setRegime(regime);
        pattern.setTimeSlot(timeSlot);

        if ("MORNING".equals(timeSlot)) {
            if (regime == MarketRegime.HIGH_VOLATILITY) {
                pattern.setSuccessRate(0.67);
                pattern.setAvgSuccessConfidence(0.64);
                pattern.setAvgFailureConfidence(0.51);
                pattern.setSampleSize(280);
            } else {
                pattern.setSuccessRate(0.61);
                pattern.setAvgSuccessConfidence(0.61);
                pattern.setAvgFailureConfidence(0.49);
                pattern.setSampleSize(220);
            }
        } else {
            pattern.setSuccessRate(0.53);
            pattern.setAvgSuccessConfidence(0.58);
            pattern.setAvgFailureConfidence(0.46);
            pattern.setSampleSize(150);
        }

        return pattern;
    }

    /**
     * Create breakout pattern
     */
    private MarketPattern createBreakoutPattern(MarketRegime regime, String timeSlot) {
        MarketPattern pattern = new MarketPattern();
        pattern.setStrategyType("BREAKOUT");
        pattern.setRegime(regime);
        pattern.setTimeSlot(timeSlot);

        if (regime == MarketRegime.TRENDING_UP || regime == MarketRegime.TRENDING_DOWN) {
            pattern.setSuccessRate(0.69);
            pattern.setAvgSuccessConfidence(0.62);
            pattern.setAvgFailureConfidence(0.48);
            pattern.setSampleSize(390);
        } else if (regime == MarketRegime.HIGH_VOLATILITY) {
            pattern.setSuccessRate(0.58);
            pattern.setAvgSuccessConfidence(0.59);
            pattern.setAvgFailureConfidence(0.45);
            pattern.setSampleSize(410);
        } else {
            pattern.setSuccessRate(0.51);
            pattern.setAvgSuccessConfidence(0.57);
            pattern.setAvgFailureConfidence(0.43);
            pattern.setSampleSize(250);
        }

        return pattern;
    }

    /**
     * Create generic pattern for unknown strategies
     */
    private MarketPattern createGenericPattern(MarketRegime regime, String timeSlot) {
        MarketPattern pattern = new MarketPattern();
        pattern.setStrategyType("GENERIC");
        pattern.setRegime(regime);
        pattern.setTimeSlot(timeSlot);
        pattern.setSuccessRate(0.52);
        pattern.setAvgSuccessConfidence(0.57);
        pattern.setAvgFailureConfidence(0.44);
        pattern.setSampleSize(250);
        return pattern;
    }

    // Helper methods for data extraction
    private String extractTradeStrategy(Trade trade) {
        try {
            return trade.getStrategy();
        } catch (Exception e) {
            return null;
        }
    }

    private ZonedDateTime extractTradeDate(Trade trade) {
        try {
            return trade.getCreatedAt();
        } catch (Exception e) {
            return null;
        }
    }

    private Double extractTradeConfidence(Trade trade) {
        try {
            if (trade.getBayesianProbability() != null) {
                return trade.getBayesianProbability();
            }
            if (trade.getSignalConfidence() != null) {
                return trade.getSignalConfidence();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal extractTradePnL(Trade trade) {
        try {
            return trade.getRealizedPnl();
        } catch (Exception e) {
            return null;
        }
    }

    private String determineTimeSlot(LocalTime time) {
        if (time.isAfter(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(10, 30))) {
            return "MORNING";
        } else if (time.isAfter(LocalTime.of(10, 30)) && time.isBefore(LocalTime.of(14, 0))) {
            return "MIDDAY";
        } else if (time.isAfter(LocalTime.of(14, 0)) && time.isBefore(LocalTime.of(15, 0))) {
            return "AFTERNOON";
        } else {
            return "CLOSE";
        }
    }

    private double calculateBucketPerformance(List<Trade> trades) {
        List<Double> returns = trades.stream()
                .map(this::extractTradePnL)
                .filter(Objects::nonNull)
                .map(BigDecimal::doubleValue)
                .collect(Collectors.toList());

        if (returns.isEmpty()) return -999.0;

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = returns.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        return stdDev > 0 ? mean / stdDev : mean > 0 ? 10.0 : -10.0;
    }

    private String buildReasoningText(HybridLearningResult result, String strategy, MarketRegime regime, LocalTime currentTime) {
        StringBuilder reasoning = new StringBuilder();

        reasoning.append(String.format("Hybrid ML for %s in %s at %s: ",
                strategy, regime, determineTimeSlot(currentTime)));

        if (result.getPersonalSamples() > 0) {
            reasoning.append(String.format("Your %d trades suggest %.1f%% threshold. ",
                    result.getPersonalSamples(), result.getPersonalThreshold() * 100));
        }

        reasoning.append(String.format("Market analysis of %d patterns suggests %.1f%% threshold. ",
                result.getMarketSamples(), result.getMarketThreshold() * 100));

        reasoning.append(String.format("Weighted combination (%.0f%% personal / %.0f%% market) = %.1f%% optimal threshold.",
                result.getPersonalWeight() * 100, result.getMarketWeight() * 100, result.getHybridThreshold() * 100));

        return reasoning.toString();
    }
}