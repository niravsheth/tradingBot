package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.MarketRegime;
import com.tradingBot.analytics.MarketRegimeDetector;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class StatisticalTrendDetector {

    private final MarketDataRepository marketDataRepository;
    private final MarketRegimeDetector regimeDetector;
    private final TradierService tradierService;

    // Rolling statistics cache
    private final Map<String, RollingStatistics> statsCache = new ConcurrentHashMap<>();

    @Data
    private static class RollingStatistics {
        private List<BigDecimal> prices = new ArrayList<>();
        private List<BigDecimal> returns = new ArrayList<>();
        private BigDecimal mean;
        private BigDecimal stdDev;
        private BigDecimal currentZScore;
        private LocalDateTime lastUpdate;

        // Bollinger Bands
        private BigDecimal upperBand;
        private BigDecimal lowerBand;
        private BigDecimal bandWidth;

        // Trend strength metrics
        private double trendStrength;
        private double momentum;
        private int consecutiveDirection;
    }

    @Data
    public static class TrendAnalysis {
        private String trend; // UP, DOWN, NEUTRAL
        private double confidence;
        private double zScore;
        private double normalizedMomentum;
        private String regime;
        private Map<String, Double> signals = new HashMap<>();
        private String reasoning;
    }

    public TrendAnalysis analyzeTrend(String symbol) {
        try {
            // Get current market regime
            MarketRegime regime = regimeDetector.detectCurrentRegime(symbol);

            // Get rolling statistics
            RollingStatistics stats = updateRollingStatistics(symbol);

            // Calculate multi-timeframe z-scores
            Map<String, Double> zScores = calculateMultiTimeframeZScores(symbol);

            // Get volume-weighted trend
            double volumeWeightedTrend = calculateVolumeWeightedTrend(symbol);

            // Calculate regime-adjusted thresholds
            double[] thresholds = getRegimeAdjustedThresholds(regime);

            // Combine signals
            TrendAnalysis analysis = new TrendAnalysis();
            analysis.setRegime(regime.toString());
            analysis.setZScore(stats.getCurrentZScore().doubleValue());

            // Multi-factor trend determination
            double trendScore = 0.0;

            // 1. Z-Score Signal (30% weight)
            double shortTermZ = zScores.getOrDefault("5min", 0.0);
            double mediumTermZ = zScores.getOrDefault("15min", 0.0);

            if (shortTermZ > thresholds[0] && mediumTermZ > 0) {
                trendScore += 3.0;
                analysis.getSignals().put("zScore", shortTermZ);
            } else if (shortTermZ < -thresholds[0] && mediumTermZ < 0) {
                trendScore -= 3.0;
                analysis.getSignals().put("zScore", shortTermZ);
            }

            // 2. Bollinger Band Position (20% weight)
            BigDecimal bbPosition = calculateBollingerBandPosition(stats);
            if (bbPosition != null) {
                double bbSignal = bbPosition.doubleValue();
                if (bbSignal > 0.8) {
                    trendScore += 2.0;
                } else if (bbSignal < 0.2) {
                    trendScore -= 2.0;
                }
                analysis.getSignals().put("bollingerBand", bbSignal);
            }

            // 3. Volume-Weighted Trend (25% weight)
            trendScore += volumeWeightedTrend * 2.5;
            analysis.getSignals().put("volumeTrend", volumeWeightedTrend);

            // 4. Momentum Persistence (25% weight)
            double momentumPersistence = calculateMomentumPersistence(stats);
            trendScore += momentumPersistence * 2.5;
            analysis.getSignals().put("momentum", momentumPersistence);

            // Apply regime-specific adjustments
            trendScore = applyRegimeAdjustments(trendScore, regime, stats);

            // Determine trend with dynamic thresholds
            String trend;
            double confidence;

            if (trendScore >= thresholds[1]) {
                trend = "UP";
                confidence = Math.min(0.95, 0.5 + (trendScore / 20.0));
            } else if (trendScore <= -thresholds[1]) {
                trend = "DOWN";
                confidence = Math.min(0.95, 0.5 + (Math.abs(trendScore) / 20.0));
            } else {
                trend = "NEUTRAL";
                confidence = 0.5 - (Math.abs(trendScore) / 10.0);
            }

            analysis.setTrend(trend);
            analysis.setConfidence(confidence);
            analysis.setNormalizedMomentum(stats.getMomentum());
            analysis.setReasoning(generateReasoning(analysis, stats));

            log.info("[TREND] {} - {} (Confidence: {}%, Score: {:.2f}, Z-Score: {:.2f})",
                    symbol, trend, (int)(confidence * 100), trendScore, shortTermZ);

            return analysis;

        } catch (Exception e) {
            log.error("Error in statistical trend analysis: {}", e.getMessage());
            return createNeutralAnalysis();
        }
    }

    private RollingStatistics updateRollingStatistics(String symbol) {
        RollingStatistics stats = statsCache.computeIfAbsent(symbol, k -> new RollingStatistics());

        // Get recent data
        List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 30);

        if (recentData.size() < 10) {
            return stats;
        }

        // Update price list
        stats.setPrices(recentData.stream()
                .map(MarketData::getPrice)
                .collect(Collectors.toList()));

        // Calculate returns
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 1; i < stats.getPrices().size(); i++) {
            BigDecimal prevPrice = stats.getPrices().get(i - 1);
            BigDecimal currPrice = stats.getPrices().get(i);

            if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ret = currPrice.subtract(prevPrice)
                        .divide(prevPrice, 6, RoundingMode.HALF_UP);
                returns.add(ret);
            }
        }
        stats.setReturns(returns);

        // Calculate statistics
        if (!returns.isEmpty()) {
            // Mean
            BigDecimal sum = returns.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            stats.setMean(sum.divide(BigDecimal.valueOf(returns.size()), 6, RoundingMode.HALF_UP));

            // Standard deviation
            BigDecimal variance = BigDecimal.ZERO;
            for (BigDecimal ret : returns) {
                BigDecimal diff = ret.subtract(stats.getMean());
                variance = variance.add(diff.multiply(diff));
            }

            if (returns.size() > 1) {
                variance = variance.divide(BigDecimal.valueOf(returns.size() - 1), 10, RoundingMode.HALF_UP);
                stats.setStdDev(BigDecimal.valueOf(Math.sqrt(variance.doubleValue())));
            } else {
                stats.setStdDev(BigDecimal.valueOf(0.001)); // Default small value
            }

            // Current Z-Score
            if (stats.getStdDev().compareTo(BigDecimal.ZERO) > 0 && !returns.isEmpty()) {
                BigDecimal lastReturn = returns.get(returns.size() - 1);
                stats.setCurrentZScore(
                        lastReturn.subtract(stats.getMean())
                                .divide(stats.getStdDev(), 4, RoundingMode.HALF_UP)
                );
            }

            // Bollinger Bands (2 std dev)
            BigDecimal currentPrice = stats.getPrices().get(stats.getPrices().size() - 1);
            BigDecimal sma = calculateSMA(stats.getPrices(), 20);
            BigDecimal bbStdDev = calculateStdDev(stats.getPrices(), 20);

            stats.setUpperBand(sma.add(bbStdDev.multiply(BigDecimal.valueOf(2))));
            stats.setLowerBand(sma.subtract(bbStdDev.multiply(BigDecimal.valueOf(2))));
            stats.setBandWidth(stats.getUpperBand().subtract(stats.getLowerBand()));

            // Momentum
            calculateMomentumMetrics(stats);
        }

        stats.setLastUpdate(LocalDateTime.now());
        return stats;
    }

    private Map<String, Double> calculateMultiTimeframeZScores(String symbol) {
        Map<String, Double> zScores = new HashMap<>();

        // 5-minute z-score
        List<MarketData> data5min = marketDataRepository.findRecentData(symbol, 5);
        zScores.put("5min", calculateZScore(data5min));

        // 15-minute z-score
        List<MarketData> data15min = marketDataRepository.findRecentData(symbol, 15);
        zScores.put("15min", calculateZScore(data15min));

        // 30-minute z-score
        List<MarketData> data30min = marketDataRepository.findRecentData(symbol, 30);
        zScores.put("30min", calculateZScore(data30min));

        return zScores;
    }

    private double calculateZScore(List<MarketData> data) {
        if (data.size() < 3) return 0.0;

        // Calculate returns
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < data.size(); i++) {
            BigDecimal prev = data.get(i - 1).getPrice();
            BigDecimal curr = data.get(i).getPrice();

            if (prev.compareTo(BigDecimal.ZERO) > 0) {
                double ret = curr.subtract(prev)
                        .divide(prev, 6, RoundingMode.HALF_UP)
                        .doubleValue();
                returns.add(ret);
            }
        }

        if (returns.isEmpty()) return 0.0;

        // Calculate mean and std dev
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        if (stdDev == 0) return 0.0;

        // Return z-score of most recent return
        return (returns.get(returns.size() - 1) - mean) / stdDev;
    }

    private BigDecimal calculateBollingerBandPosition(RollingStatistics stats) {
        if (stats.getUpperBand() == null || stats.getLowerBand() == null) {
            return null;
        }

        BigDecimal currentPrice = stats.getPrices().get(stats.getPrices().size() - 1);
        BigDecimal range = stats.getUpperBand().subtract(stats.getLowerBand());

        if (range.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(0.5);
        }

        // Position between 0 (lower band) and 1 (upper band)
        return currentPrice.subtract(stats.getLowerBand())
                .divide(range, 4, RoundingMode.HALF_UP);
    }

    private double[] getRegimeAdjustedThresholds(MarketRegime regime) {
        // [z-score threshold, trend score threshold]
        switch (regime) {
            case HIGH_VOLATILITY:
                return new double[]{1.0, 3.0}; // Wider thresholds
            case LOW_VOLATILITY:
                return new double[]{2.5, 5.0}; // Tighter thresholds
            case TRENDING_UP:
            case TRENDING_DOWN:
                return new double[]{1.5, 2.5}; // Easier to confirm trend
            case CHOPPY:
                return new double[]{2.0, 6.0}; // Harder to confirm trend
            default:
                return new double[]{2.0, 4.0}; // Default
        }
    }

    // Additional helper methods...
    private double calculateVolumeWeightedTrend(String symbol) {
        List<MarketData> data = marketDataRepository.findRecentData(symbol, 15);
        if (data.size() < 2) return 0.0;

        double vwTrend = 0.0;
        long totalVolume = 0;

        for (int i = 1; i < data.size(); i++) {
            MarketData current = data.get(i);
            MarketData previous = data.get(i - 1);

            if (current.getVolume() != null && current.getVolume() > 0) {
                double priceChange = current.getPrice().subtract(previous.getPrice())
                        .divide(previous.getPrice(), 6, RoundingMode.HALF_UP)
                        .doubleValue();

                vwTrend += priceChange * current.getVolume();
                totalVolume += current.getVolume();
            }
        }

        return totalVolume > 0 ? vwTrend / totalVolume * 1000 : 0.0;
    }

    private void calculateMomentumMetrics(RollingStatistics stats) {
        if (stats.getReturns().size() < 3) return;

        // Count consecutive positive/negative returns
        int consecutive = 0;
        BigDecimal lastReturn = stats.getReturns().get(stats.getReturns().size() - 1);

        for (int i = stats.getReturns().size() - 1; i >= 0; i--) {
            BigDecimal ret = stats.getReturns().get(i);
            if (ret.signum() == lastReturn.signum()) {
                consecutive++;
            } else {
                break;
            }
        }

        stats.setConsecutiveDirection(lastReturn.signum() * consecutive);

        // Calculate momentum strength
        double recentMomentum = stats.getReturns().stream()
                .skip(Math.max(0, stats.getReturns().size() - 5))
                .mapToDouble(BigDecimal::doubleValue)
                .sum();

        stats.setMomentum(recentMomentum);
        stats.setTrendStrength(Math.tanh(recentMomentum * 100)); // Normalize to [-1, 1]
    }

    private double calculateMomentumPersistence(RollingStatistics stats) {
        int consecutive = Math.abs(stats.getConsecutiveDirection());
        double momentum = stats.getMomentum();

        // Reward persistent momentum
        if (consecutive >= 3) {
            return momentum * (1 + consecutive * 0.1);
        }

        return momentum * 0.5; // Penalize choppy movement
    }

    private BigDecimal calculateSMA(List<BigDecimal> prices, int period) {
        if (prices.size() < period) {
            period = prices.size();
        }

        BigDecimal sum = prices.stream()
                .skip(Math.max(0, prices.size() - period))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStdDev(List<BigDecimal> prices, int period) {
        if (prices.size() < period) {
            period = prices.size();
        }

        List<BigDecimal> subset = prices.subList(Math.max(0, prices.size() - period), prices.size());
        BigDecimal mean = calculateSMA(prices, period);

        BigDecimal variance = subset.stream()
                .map(p -> p.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period - 1), 10, RoundingMode.HALF_UP);

        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private double applyRegimeAdjustments(double trendScore, MarketRegime regime, RollingStatistics stats) {
        switch (regime) {
            case TRENDING_UP:
                // Boost bullish signals in uptrend
                if (trendScore > 0) {
                    trendScore *= 1.3;
                } else {
                    trendScore *= 0.7; // Reduce bearish signals
                }
                break;

            case TRENDING_DOWN:
                // Boost bearish signals in downtrend
                if (trendScore < 0) {
                    trendScore *= 1.3;
                } else {
                    trendScore *= 0.7; // Reduce bullish signals
                }
                break;

            case HIGH_VOLATILITY:
                // Require stronger signals in volatile markets
                trendScore *= 0.8;
                break;

            case CHOPPY:
                // Much harder to confirm trends in choppy markets
                trendScore *= 0.5;
                break;

            case OPENING_RANGE:
                // Give more weight to momentum in opening
                if (Math.abs(stats.getMomentum()) > 0.001) {
                    trendScore *= 1.2;
                }
                break;

            case CLOSING_RANGE:
                // Be more conservative near close
                trendScore *= 0.7;
                break;
        }

        return trendScore;
    }

    private String generateReasoning(TrendAnalysis analysis, RollingStatistics stats) {
        StringBuilder reason = new StringBuilder();

        reason.append(String.format("%s trend in %s regime",
                analysis.getTrend(), analysis.getRegime()));

        if (analysis.getZScore() != 0) {
            reason.append(String.format(", Z-score: %.2f", analysis.getZScore()));
        }

        if (stats.getConsecutiveDirection() != 0) {
            reason.append(String.format(", %d consecutive %s moves",
                    Math.abs(stats.getConsecutiveDirection()),
                    stats.getConsecutiveDirection() > 0 ? "up" : "down"));
        }

        return reason.toString();
    }

    private TrendAnalysis createNeutralAnalysis() {
        TrendAnalysis analysis = new TrendAnalysis();
        analysis.setTrend("NEUTRAL");
        analysis.setConfidence(0.5);
        analysis.setZScore(0.0);
        analysis.setReasoning("Insufficient data or error in analysis");
        return analysis;
    }
}