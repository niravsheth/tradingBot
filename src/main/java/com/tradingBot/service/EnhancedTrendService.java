package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.Quote;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.model.MarketRegime;
import com.tradingBot.analytics.MarketRegimeDetector;
import com.tradingBot.ml.MLTrendPredictor;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnhancedTrendService {

    private final TradierService tradierService;
    private final MarketDataRepository marketDataRepository;
    private final MarketRegimeDetector regimeDetector;

    // Make ML predictor optional
    @Autowired(required = false)
    private MLTrendPredictor mlPredictor;

    // Configuration
    @Value("${trading.trend-detection.enhanced.ml.enabled:true}")
    private boolean mlEnabled;

    @Value("${trading.trend-detection.enhanced.ml.timeout-ms:500}")
    private int mlTimeoutMs;

    // Cache for trend with short TTL
    private final AtomicReference<CachedTrend> trendCache = new AtomicReference<>();
    private static final int CACHE_TTL_SECONDS = 10; // Shorter cache for more responsiveness

    // No need for separate executor since MLTrendPredictor already handles async

    @Data
    private static class CachedTrend {
        private String trend;
        private double confidence;
        private LocalDateTime timestamp;
        private String reasoning;
        private EnhancedTrendResult fullResult;

        boolean isExpired() {
            return LocalDateTime.now().isAfter(timestamp.plusSeconds(CACHE_TTL_SECONDS));
        }
    }

    @Data
    public static class EnhancedTrendResult {
        private String finalTrend; // UP, DOWN, NEUTRAL
        private double finalConfidence;
        private double baseScore;
        private String baseTrend;
        private double baseConfidence;

        // Component scores
        private double momentumScore;
        private double volumeScore;
        private double microstructureScore;
        private double regimeScore;
        private double mlValidationScore;

        // Additional signals from IntegratedTrendService
        private double zScore;
        private double bollingerPosition;
        private boolean volumeSurge;
        private String marketRegime;
        private Map<String, Double> mlProbabilities;

        private String reasoning;
        private long calculationTimeMs;
    }

    /**
     * Fast trend detection with async ML validation
     */
    public String getTrend(String symbol) {
        // Check cache first
        CachedTrend cached = trendCache.get();
        if (cached != null && !cached.isExpired()) {
            log.debug("[TREND] Using cached trend: {} ({}%)",
                    cached.getTrend(), (int)(cached.getConfidence() * 100));
            return cached.getTrend();
        }

        // Calculate enhanced trend
        long startTime = System.currentTimeMillis();
        EnhancedTrendResult result = calculateEnhancedTrend(symbol);

        // Cache it
        CachedTrend newCache = new CachedTrend();
        newCache.setTrend(result.getFinalTrend());
        newCache.setConfidence(result.getFinalConfidence());
        newCache.setTimestamp(LocalDateTime.now());
        newCache.setReasoning(result.getReasoning());
        newCache.setFullResult(result);
        trendCache.set(newCache);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[TREND] {} - {} (Confidence: {}%, Time: {}ms)",
                symbol, result.getFinalTrend(),
                (int)(result.getFinalConfidence() * 100), duration);

        return result.getFinalTrend();
    }

    /**
     * Enhanced trend calculation with all factors
     */
    private EnhancedTrendResult calculateEnhancedTrend(String symbol) {
        EnhancedTrendResult result = new EnhancedTrendResult();
        long startTime = System.currentTimeMillis();

        try {
            // Phase 1: Fast base calculation (similar to SimplifiedTrendService)
            calculateBaseTrend(symbol, result);

            // Phase 2: Additional factors from IntegratedTrendService
            calculateStatisticalFactors(symbol, result);
            calculateVolumeAnalysis(symbol, result);
            calculateMicrostructure(symbol, result);
            detectMarketRegime(symbol, result);

            // Phase 3: ML validation (optional)
            performMLValidation(result, symbol);

            // Phase 4: Combine all signals
            combineTrendSignals(result);

            result.setCalculationTimeMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            log.error("Error in enhanced trend calculation: {}", e.getMessage());
            return createNeutralResult("Calculation error");
        }
    }

    /**
     * Phase 1: Fast base trend (momentum-based)
     */
    private void calculateBaseTrend(String symbol, EnhancedTrendResult result) {
        double baseScore = 0.0;

        try {
            QuoteResponse response = tradierService.getQuote(symbol);
            if (response == null || response.getQuote() == null) {
                result.setBaseTrend("NEUTRAL");
                result.setBaseConfidence(0.5);
                return;
            }

            Quote quote = response.getQuote();
            BigDecimal currentPrice = quote.getLast();

            // Recent momentum (3-min, 5-min, 10-min)
            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 10);

            if (recentData.size() >= 3) {
                // 3-minute momentum (highest weight)
                BigDecimal price3Min = recentData.get(recentData.size() - 3).getPrice();
                BigDecimal change3Min = calculatePercentChange(currentPrice, price3Min);

                if (change3Min.compareTo(BigDecimal.valueOf(0.0008)) > 0) {
                    baseScore += 4.0;
                    result.setMomentumScore(result.getMomentumScore() + 0.4);
                } else if (change3Min.compareTo(BigDecimal.valueOf(-0.0008)) < 0) {
                    baseScore -= 4.0;
                    result.setMomentumScore(result.getMomentumScore() - 0.4);
                }

                // 5-minute momentum
                if (recentData.size() >= 5) {
                    BigDecimal price5Min = recentData.get(recentData.size() - 5).getPrice();
                    BigDecimal change5Min = calculatePercentChange(currentPrice, price5Min);

                    if (change5Min.compareTo(BigDecimal.valueOf(0.0015)) > 0) {
                        baseScore += 2.0;
                        result.setMomentumScore(result.getMomentumScore() + 0.2);
                    } else if (change5Min.compareTo(BigDecimal.valueOf(-0.0015)) < 0) {
                        baseScore -= 2.0;
                        result.setMomentumScore(result.getMomentumScore() - 0.2);
                    }
                }
            }

            // Day's trend
            if (quote.getPreviousClose() != null) {
                BigDecimal dayChange = calculatePercentChange(currentPrice, quote.getPreviousClose());

                if (dayChange.compareTo(BigDecimal.valueOf(0.0025)) > 0) {
                    baseScore += 1.5;
                } else if (dayChange.compareTo(BigDecimal.valueOf(-0.0025)) < 0) {
                    baseScore -= 1.5;
                }
            }

            // Price position in range
            if (quote.getHigh() != null && quote.getLow() != null) {
                BigDecimal position = calculatePricePosition(currentPrice, quote.getHigh(), quote.getLow());

                if (position.compareTo(BigDecimal.valueOf(0.75)) > 0) {
                    baseScore += 2.0;
                } else if (position.compareTo(BigDecimal.valueOf(0.25)) < 0) {
                    baseScore -= 2.0;
                }
            }

            result.setBaseScore(baseScore);

            // Determine base trend
            if (baseScore >= 4.0) {
                result.setBaseTrend("UP");
                result.setBaseConfidence(Math.min(0.8, 0.5 + baseScore / 20));
            } else if (baseScore <= -4.0) {
                result.setBaseTrend("DOWN");
                result.setBaseConfidence(Math.min(0.8, 0.5 + Math.abs(baseScore) / 20));
            } else {
                result.setBaseTrend("NEUTRAL");
                result.setBaseConfidence(0.5);
            }

        } catch (Exception e) {
            log.error("Error in base trend calculation: {}", e.getMessage());
            result.setBaseTrend("NEUTRAL");
            result.setBaseConfidence(0.5);
        }
    }

    /**
     * Phase 2a: Statistical factors (z-score, Bollinger)
     */
    private void calculateStatisticalFactors(String symbol, EnhancedTrendResult result) {
        try {
            List<MarketData> data = marketDataRepository.findRecentData(symbol, 30);
            if (data.size() < 10) return;

            // Calculate returns
            List<BigDecimal> returns = new ArrayList<>();
            for (int i = 1; i < data.size(); i++) {
                BigDecimal ret = calculatePercentChange(
                        data.get(i).getPrice(),
                        data.get(i-1).getPrice()
                );
                returns.add(ret);
            }

            // Calculate mean and std dev
            BigDecimal mean = calculateMean(returns);
            BigDecimal stdDev = calculateStdDev(returns, mean);

            if (stdDev.compareTo(BigDecimal.ZERO) > 0) {
                // Z-score of latest return
                BigDecimal latestReturn = returns.get(returns.size() - 1);
                BigDecimal zScore = latestReturn.subtract(mean)
                        .divide(stdDev, 4, RoundingMode.HALF_UP);
                result.setZScore(zScore.doubleValue());

                // Extreme z-scores boost confidence
                if (Math.abs(zScore.doubleValue()) > 2.5) {
                    if (zScore.doubleValue() > 0 && "UP".equals(result.getBaseTrend())) {
                        result.setBaseConfidence(result.getBaseConfidence() * 1.2);
                    } else if (zScore.doubleValue() < 0 && "DOWN".equals(result.getBaseTrend())) {
                        result.setBaseConfidence(result.getBaseConfidence() * 1.2);
                    }
                }
            }

            // Bollinger Band position
            BigDecimal currentPrice = data.get(data.size() - 1).getPrice();
            BigDecimal sma20 = calculateSMA(data, 20);
            BigDecimal bbStdDev = calculateBBStdDev(data, 20);

            if (bbStdDev.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal upperBand = sma20.add(bbStdDev.multiply(BigDecimal.valueOf(2)));
                BigDecimal lowerBand = sma20.subtract(bbStdDev.multiply(BigDecimal.valueOf(2)));
                BigDecimal bbPosition = currentPrice.subtract(lowerBand)
                        .divide(upperBand.subtract(lowerBand), 4, RoundingMode.HALF_UP);
                result.setBollingerPosition(bbPosition.doubleValue());
            }

        } catch (Exception e) {
            log.error("Error in statistical factors: {}", e.getMessage());
        }
    }

    /**
     * Phase 2b: Volume analysis
     */
    private void calculateVolumeAnalysis(String symbol, EnhancedTrendResult result) {
        try {
            List<MarketData> data = marketDataRepository.findRecentData(symbol, 20);
            if (data.size() < 5) return;

            // Recent vs average volume
            long recentVolume = data.stream()
                    .skip(Math.max(0, data.size() - 5))
                    .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                    .sum();

            long avgVolume = data.stream()
                    .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                    .sum() / data.size();

            if (avgVolume > 0) {
                double volumeRatio = (double) recentVolume / (avgVolume * 5);
                result.setVolumeScore(volumeRatio);
                result.setVolumeSurge(volumeRatio > 1.5);

                // Volume confirms trend
                if (volumeRatio > 1.5) {
                    if ("UP".equals(result.getBaseTrend())) {
                        result.setBaseScore(result.getBaseScore() + 2.0);
                    } else if ("DOWN".equals(result.getBaseTrend())) {
                        result.setBaseScore(result.getBaseScore() - 2.0);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error in volume analysis: {}", e.getMessage());
        }
    }

    /**
     * Phase 2c: Microstructure (bid/ask imbalance)
     */
    private void calculateMicrostructure(String symbol, EnhancedTrendResult result) {
        try {
            QuoteResponse response = tradierService.getQuote(symbol);
            if (response == null || response.getQuote() == null) return;

            Quote quote = response.getQuote();
            if (quote.getBidSize() != null && quote.getAskSize() != null) {
                int totalSize = quote.getBidSize() + quote.getAskSize();
                if (totalSize > 0) {
                    double bidRatio = (double) quote.getBidSize() / totalSize;
                    result.setMicrostructureScore(bidRatio - 0.5); // -0.5 to 0.5 range

                    // Strong imbalance confirms trend
                    if (bidRatio > 0.65 && "UP".equals(result.getBaseTrend())) {
                        result.setBaseConfidence(result.getBaseConfidence() * 1.1);
                    } else if (bidRatio < 0.35 && "DOWN".equals(result.getBaseTrend())) {
                        result.setBaseConfidence(result.getBaseConfidence() * 1.1);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error in microstructure analysis: {}", e.getMessage());
        }
    }

    /**
     * Phase 2d: Market regime detection
     */
    private void detectMarketRegime(String symbol, EnhancedTrendResult result) {
        try {
            MarketRegime regime = regimeDetector.detectCurrentRegime(symbol);
            result.setMarketRegime(regime.toString());

            // Regime-based adjustments
            switch (regime) {
                case TRENDING_UP:
                    if ("UP".equals(result.getBaseTrend())) {
                        result.setRegimeScore(0.2);
                        result.setBaseConfidence(result.getBaseConfidence() * 1.15);
                    }
                    break;

                case TRENDING_DOWN:
                    if ("DOWN".equals(result.getBaseTrend())) {
                        result.setRegimeScore(-0.2);
                        result.setBaseConfidence(result.getBaseConfidence() * 1.15);
                    }
                    break;

                case HIGH_VOLATILITY:
                    // Reduce confidence in high volatility
                    result.setBaseConfidence(result.getBaseConfidence() * 0.85);
                    break;

                case CHOPPY:
                    // Much lower confidence in choppy markets
                    result.setBaseConfidence(result.getBaseConfidence() * 0.7);
                    if (Math.abs(result.getBaseScore()) < 6.0) {
                        result.setBaseTrend("NEUTRAL");
                    }
                    break;
            }

        } catch (Exception e) {
            log.error("Error in regime detection: {}", e.getMessage());
            result.setMarketRegime("UNKNOWN");
        }
    }

    /**
     * Phase 3: ML validation (optional and robust)
     */
    private void performMLValidation(EnhancedTrendResult result, String symbol) {
        // Check if ML is enabled and available
        if (!mlEnabled) {
            log.debug("[TREND] ML validation disabled by configuration");
            result.setMlValidationScore(0.5);
            return;
        }

        if (mlPredictor == null) {
            log.debug("[TREND] ML predictor not available");
            result.setMlValidationScore(0.5);
            return;
        }

        try {
            // Get ML prediction with timeout
            CompletableFuture<MLTrendPredictor.MLPrediction> mlFuture =
                    mlPredictor.predictTrend(symbol, 5);

            MLTrendPredictor.MLPrediction mlPrediction =
                    mlFuture.get(mlTimeoutMs, TimeUnit.MILLISECONDS);

            applyMLValidation(result, mlPrediction);
            log.debug("[TREND] ML validation successful: {} ({}%)",
                    mlPrediction.getPredictedTrend(),
                    (int)(mlPrediction.getConfidence() * 100));

        } catch (TimeoutException e) {
            log.debug("[TREND] ML prediction timed out after {}ms", mlTimeoutMs);
            result.setMlValidationScore(0.5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[TREND] ML prediction interrupted");
            result.setMlValidationScore(0.5);
        } catch (ExecutionException e) {
            log.warn("[TREND] ML prediction failed: {}", e.getCause().getMessage());
            result.setMlValidationScore(0.5);
        } catch (Exception e) {
            log.error("[TREND] Unexpected ML error: {}", e.getMessage(), e);
            result.setMlValidationScore(0.5);
        }
    }

    /**
     * Apply ML validation results
     */
    private void applyMLValidation(EnhancedTrendResult result, MLTrendPredictor.MLPrediction mlPrediction) {
        if (mlPrediction == null) {
            result.setMlValidationScore(0.5);
            return;
        }

        if (mlPrediction.getProbabilities() != null) {
            result.setMlProbabilities(mlPrediction.getProbabilities());
        }

        // ML agreement boosts confidence
        if (mlPrediction.getPredictedTrend() != null &&
                mlPrediction.getPredictedTrend().equals(result.getBaseTrend())) {
            result.setMlValidationScore(mlPrediction.getConfidence());
            result.setBaseConfidence(result.getBaseConfidence() * (1 + mlPrediction.getConfidence() * 0.2));
        } else {
            // ML disagrees - reduce confidence unless ML is very confident
            if (mlPrediction.getConfidence() > 0.8) {
                // Strong ML disagreement might flip the trend
                result.setMlValidationScore(-mlPrediction.getConfidence());
                result.setBaseConfidence(result.getBaseConfidence() * 0.7);
            } else {
                // Weak ML disagreement just reduces confidence
                result.setMlValidationScore(0.5);
                result.setBaseConfidence(result.getBaseConfidence() * 0.9);
            }
        }
    }

    /**
     * Phase 4: Combine all signals
     */
    private void combineTrendSignals(EnhancedTrendResult result) {
        // Start with base trend
        String finalTrend = result.getBaseTrend();
        double finalConfidence = result.getBaseConfidence();

        // Apply all modifiers
        double totalScore = result.getBaseScore() +
                result.getMomentumScore() * 2.0 +
                result.getVolumeScore() * 1.5 +
                result.getMicrostructureScore() * 3.0 +
                result.getRegimeScore() * 5.0 +
                result.getMlValidationScore() * 2.0;

        // Re-evaluate trend with all factors
        if (totalScore >= 5.0) {
            finalTrend = "UP";
            finalConfidence = Math.min(0.95, 0.6 + totalScore / 30);
        } else if (totalScore <= -5.0) {
            finalTrend = "DOWN";
            finalConfidence = Math.min(0.95, 0.6 + Math.abs(totalScore) / 30);
        } else {
            finalTrend = "NEUTRAL";
            finalConfidence = Math.max(0.4, 0.6 - Math.abs(totalScore) / 20);
        }

        // Apply regime overrides
        if ("CHOPPY".equals(result.getMarketRegime()) && Math.abs(totalScore) < 8.0) {
            finalTrend = "NEUTRAL";
            finalConfidence = Math.min(finalConfidence, 0.6);
        }

        result.setFinalTrend(finalTrend);
        result.setFinalConfidence(finalConfidence);
        result.setReasoning(generateReasoning(result));
    }

    private String generateReasoning(EnhancedTrendResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("%s trend", result.getFinalTrend()));

        if (Math.abs(result.getZScore()) > 2.0) {
            sb.append(String.format(" (z=%.1f)", result.getZScore()));
        }

        if (result.isVolumeSurge()) {
            sb.append(" with volume surge");
        }

        if (result.getMlValidationScore() > 0.7) {
            sb.append(" ML confirmed");
        } else if (result.getMlValidationScore() < -0.7) {
            sb.append(" ML disagrees");
        }

        sb.append(String.format(" [%s regime]", result.getMarketRegime()));

        return sb.toString();
    }

    // Helper methods
    private BigDecimal calculatePercentChange(BigDecimal current, BigDecimal previous) {
        if (previous.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return current.subtract(previous).divide(previous, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePricePosition(BigDecimal price, BigDecimal high, BigDecimal low) {
        BigDecimal range = high.subtract(low);
        if (range.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.valueOf(0.5);
        return price.subtract(low).divide(range, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMean(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStdDev(List<BigDecimal> values, BigDecimal mean) {
        if (values.size() < 2) return BigDecimal.ZERO;

        BigDecimal sumSquaredDiff = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = sumSquaredDiff.divide(
                BigDecimal.valueOf(values.size() - 1), 10, RoundingMode.HALF_UP);

        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private BigDecimal calculateSMA(List<MarketData> data, int period) {
        int actualPeriod = Math.min(period, data.size());
        BigDecimal sum = data.stream()
                .skip(Math.max(0, data.size() - actualPeriod))
                .map(MarketData::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(actualPeriod), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateBBStdDev(List<MarketData> data, int period) {
        BigDecimal sma = calculateSMA(data, period);
        int actualPeriod = Math.min(period, data.size());

        BigDecimal sumSquaredDiff = data.stream()
                .skip(Math.max(0, data.size() - actualPeriod))
                .map(d -> d.getPrice().subtract(sma).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = sumSquaredDiff.divide(
                BigDecimal.valueOf(actualPeriod), 10, RoundingMode.HALF_UP);

        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private EnhancedTrendResult createNeutralResult(String reason) {
        EnhancedTrendResult result = new EnhancedTrendResult();
        result.setFinalTrend("NEUTRAL");
        result.setFinalConfidence(0.5);
        result.setBaseTrend("NEUTRAL");
        result.setBaseConfidence(0.5);
        result.setReasoning(reason);
        return result;
    }

    /**
     * Get detailed trend analysis
     */
    public EnhancedTrendResult getTrendDetailed(String symbol) {
        CachedTrend cached = trendCache.get();
        if (cached != null && !cached.isExpired() && cached.getFullResult() != null) {
            return cached.getFullResult();
        }
        return calculateEnhancedTrend(symbol);
    }

}