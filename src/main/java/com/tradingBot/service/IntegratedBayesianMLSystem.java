package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import com.tradingBot.entity.Trade;
import com.tradingBot.repository.TradeRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class IntegratedBayesianMLSystem {

    private final TradeRepository tradeRepository;

    // Cache for performance optimization
    private final Map<String, ThresholdCache> thresholdCache = new ConcurrentHashMap<>();
    private final Map<String, LearningState> learningStateCache = new ConcurrentHashMap<>();

    // Configuration constants - ADJUSTED FOR BETTER PERFORMANCE
    private static final int MIN_HISTORICAL_TRADES = 10;
    private static final double DEFAULT_THRESHOLD = 0.70; // REDUCED from 0.75
    private static final double MIN_CONFIDENCE = 0.0;
    private static final double MAX_CONFIDENCE = 1.0;
    private static final int CACHE_EXPIRY_MINUTES = 30;

    /**
     * Main entry point for Bayesian analysis and execution decision
     */
    public IntegratedAnalysisResult analyzeAndExecuteSignal(Signal signal) {
        try {
            if (signal == null) {
                log.warn("[BAYESIAN] Null signal provided");
                return createDefaultResult("NULL_SIGNAL");
            }

            log.info("[BAYESIAN] Analyzing signal: {} (Confidence: {}%)",
                    signal.getOptionSymbol(),
                    signal.getConfidence() != null ? (int)(signal.getConfidence() * 100) : "NULL");

            // Perform Bayesian analysis
            BayesianAnalysis bayesianAnalysis = performBayesianAnalysis(signal);

            // Get learning state
            LearningState learningState = getLearningState(signal.getStrategy());

            // Calculate dynamic threshold
            DynamicThreshold dynamicThreshold = calculateDynamicThreshold(signal.getStrategy(), learningState);

            // Make execution decision
            ExecutionDecision executionDecision = makeExecutionDecision(signal, bayesianAnalysis, dynamicThreshold);

            // Update learning state (invalidate cache for next calculation)
            updateLearningState(signal.getStrategy(), bayesianAnalysis, executionDecision);

            return IntegratedAnalysisResult.builder()
                    .bayesianAnalysis(bayesianAnalysis)
                    .learningState(learningState)
                    .dynamicThreshold(dynamicThreshold)
                    .executionDecision(executionDecision)
                    .timestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("[BAYESIAN] Error in integrated analysis: {}", e.getMessage(), e);
            return createDefaultResult("ERROR: " + e.getMessage());
        }
    }

    /**
     * Null-safe Bayesian analysis using trade history
     */
    public BayesianAnalysis performBayesianAnalysis(Signal signal) {
        try {
            if (signal == null || signal.getConfidence() == null) {
                log.warn("[BAYESIAN] Invalid signal for analysis, using default");
                return createDefaultBayesianAnalysis();
            }

            // Validate signal confidence
            double signalConfidence = validateConfidence(signal.getConfidence());

            // Get historical trade data for this strategy
            List<HistoricalData> historicalData = getHistoricalDataFromTrades(signal.getStrategy());

            if (historicalData.size() < MIN_HISTORICAL_TRADES) {
                log.warn("[BAYESIAN] Insufficient historical data ({} trades) for strategy: {}, using priors",
                        historicalData.size(), signal.getStrategy());
                return createPriorBayesianAnalysis(signal, signalConfidence);
            }

            // Calculate Bayesian components with null-safety
            double priorProbability = calculatePriorProbability(historicalData);
            double likelihood = calculateLikelihood(signal, historicalData);
            double evidence = calculateEvidence(historicalData);

            // Prevent division by zero
            if (evidence <= 0.0) {
                log.warn("[BAYESIAN] Invalid evidence ({}), using fallback", evidence);
                evidence = 0.01; // Small non-zero value
            }

            double posteriorProbability = (likelihood * priorProbability) / evidence;

            // Ensure posterior is within valid range
            posteriorProbability = Math.max(MIN_CONFIDENCE, Math.min(MAX_CONFIDENCE, posteriorProbability));

            // Calculate adjusted confidence
            double adjustedConfidence = calculateAdjustedConfidence(signalConfidence, posteriorProbability);

            BayesianAnalysis analysis = BayesianAnalysis.builder()
                    .priorProbability(priorProbability)
                    .likelihood(likelihood)
                    .evidence(evidence)
                    .posteriorProbability(posteriorProbability)
                    .adjustedConfidence(adjustedConfidence)
                    .confidenceBoost(adjustedConfidence - signalConfidence)
                    .dataQuality(calculateDataQuality(historicalData))
                    .build();

            log.debug("[BAYESIAN] Analysis complete - Prior: {}, Likelihood: {}, Posterior: {}, Adjusted: {}",
                    String.format("%.3f",priorProbability),String.format("%.3f",likelihood), String.format("%.3f",posteriorProbability), String.format("%.3f",adjustedConfidence));

            return analysis;

        } catch (Exception e) {
            log.error("[BAYESIAN] Error in Bayesian analysis: {}", e.getMessage(), e);
            return createDefaultBayesianAnalysis();
        }
    }

    /**
     * Extract historical performance data from completed trades - FIXED DATETIME ISSUE
     */
    private List<HistoricalData> getHistoricalDataFromTrades(String strategy) {
        try {
            if (strategy == null || strategy.trim().isEmpty()) {
                log.warn("[BAYESIAN] Invalid strategy provided");
                return new ArrayList<>();
            }

            // FIX: Use ZonedDateTime instead of LocalDateTime to match Trade entity
            ZonedDateTime cutoff = ZonedDateTime.now().minus(90, ChronoUnit.DAYS);

            log.debug("[BAYESIAN] Querying trades for strategy: {} after: {}", strategy, cutoff);

            List<Trade> completedTrades = tradeRepository.findByStrategyAndStatusInAndCreatedAtAfter(
                    strategy,
                    Arrays.asList("CLOSED", "EXPIRED", "FAILED", "STOPPED"),
                    cutoff  // Now this matches the expected ZonedDateTime type
            );

            if (completedTrades == null) {
                log.warn("[BAYESIAN] Repository returned null for strategy: {}", strategy);
                return new ArrayList<>();
            }

            log.info("[BAYESIAN] Retrieved {} completed trades for strategy: {}",
                    completedTrades.size(), strategy);

            // Convert trades to historical data with comprehensive validation
            List<HistoricalData> historicalData = completedTrades.stream()
                    .filter(Objects::nonNull)
                    .filter(trade -> trade.getSignalConfidence() != null)
                    .filter(trade -> trade.getEntryPrice() != null)
                    .filter(trade -> trade.getEntryPrice().compareTo(BigDecimal.ZERO) > 0)
                    .filter(trade -> !trade.getSignalConfidence().isNaN())
                    .filter(trade -> !trade.getSignalConfidence().isInfinite())
                    .filter(trade -> trade.getSignalConfidence() >= MIN_CONFIDENCE && trade.getSignalConfidence() <= MAX_CONFIDENCE)
                    .map(this::convertTradeToHistoricalData)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.debug("[BAYESIAN] Retrieved {} valid historical data points for strategy {} (from {} trades)",
                    historicalData.size(), strategy, completedTrades.size());

            return historicalData;

        } catch (Exception e) {
            log.error("[BAYESIAN] Error retrieving historical data for {}: {}", strategy, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Convert Trade entity to HistoricalData with null-safety - FIXED DATETIME CONVERSION
     */
    private HistoricalData convertTradeToHistoricalData(Trade trade) {
        try {
            if (trade == null || trade.getSignalConfidence() == null || trade.getEntryPrice() == null) {
                return null;
            }

            // Calculate profitability
            double profitability = calculateTradeProfitability(trade);

            // Determine outcome
            String outcome = determineTradeOutcome(trade, profitability);

            // FIX: Handle ZonedDateTime to LocalDateTime conversion properly
            LocalDateTime timestamp = LocalDateTime.now(); // Default fallback

            if (trade.getCreatedAt() != null) {
                // Convert ZonedDateTime to LocalDateTime
                timestamp = trade.getCreatedAt().toLocalDateTime();
            } else if (trade.getEntryTime() != null) {
                // Fallback to entryTime if available
                timestamp = trade.getEntryTime();
            }

            return HistoricalData.builder()
                    .strategy(trade.getStrategy())
                    .confidence(trade.getSignalConfidence())
                    .profitability(profitability)
                    .outcome(outcome)
                    .timestamp(timestamp)
                    .tradeId(trade.getId() != null ? trade.getId().toString() : "unknown")
                    .build();

        } catch (Exception e) {
            log.error("[BAYESIAN] Error converting trade to historical data: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Calculate trade profitability with null-safety
     */
    private double calculateTradeProfitability(Trade trade) {
        try {
            BigDecimal entryPrice = trade.getEntryPrice();
            BigDecimal exitPrice = null;

            // Try different exit price sources
            if (trade.getExitPrice() != null && trade.getExitPrice().compareTo(BigDecimal.ZERO) > 0) {
                exitPrice = trade.getExitPrice();
            } else if (trade.getCurrentPrice() != null && trade.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                exitPrice = trade.getCurrentPrice();
            } else {
                // For failed/stopped trades, assume significant loss
                return "FAILED".equals(trade.getStatus()) || "STOPPED".equals(trade.getStatus()) ? -0.50 : 0.0;
            }

            if (entryPrice != null && exitPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal profit = exitPrice.subtract(entryPrice);
                return profit.divide(entryPrice, 4, RoundingMode.HALF_UP).doubleValue();
            }

            return 0.0;

        } catch (Exception e) {
            log.error("[BAYESIAN] Error calculating profitability: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Determine trade outcome based on status and profitability
     */
    private String determineTradeOutcome(Trade trade, double profitability) {
        if (trade.getStatus() == null) {
            return "UNKNOWN";
        }

        switch (trade.getStatus()) {
            case "CLOSED":
                return profitability > 0.05 ? "PROFITABLE" : profitability < -0.05 ? "LOSS" : "BREAKEVEN";
            case "EXPIRED":
                return profitability > 0.0 ? "EXPIRED_PROFIT" : "EXPIRED_LOSS";
            case "FAILED":
            case "STOPPED":
                return "STOPPED_LOSS";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Get learning state for a strategy with caching
     */
    public LearningState getLearningState(String strategy) {
        try {
            // Check cache first
            LearningState cached = learningStateCache.get(strategy);
            if (cached != null && cached.getLastUpdate().isAfter(LocalDateTime.now().minusMinutes(CACHE_EXPIRY_MINUTES))) {
                return cached;
            }

            // Calculate new learning state
            List<HistoricalData> historicalData = getHistoricalDataFromTrades(strategy);

            String learningPhase = determineLearningPhase(historicalData.size());
            double performanceScore = calculatePerformanceScore(historicalData);
            ConfidenceDistribution distribution = analyzeConfidenceDistribution(historicalData);

            LearningState state = LearningState.builder()
                    .strategy(strategy)
                    .signalCount(historicalData.size())
                    .learningPhase(learningPhase)
                    .performanceScore(performanceScore)
                    .confidenceDistribution(distribution)
                    .lastUpdate(LocalDateTime.now())
                    .build();

            // Cache the result
            learningStateCache.put(strategy, state);

            log.debug("[LEARNING] State for {}: Phase={}, Trades={}, Performance={}",
                    strategy, learningPhase, historicalData.size(), String.format("%.3f",performanceScore));

            return state;

        } catch (Exception e) {
            log.error("[LEARNING] Error getting learning state for {}: {}", strategy, e.getMessage());
            return createDefaultLearningState(strategy);
        }
    }

    /**
     * Calculate dynamic threshold with null-safety
     */
    public DynamicThreshold calculateDynamicThreshold(String strategy, LearningState learningState) {
        try {
            // Check cache first
            String cacheKey = strategy + "_" + learningState.getSignalCount();
            ThresholdCache cached = thresholdCache.get(cacheKey);
            if (cached != null && cached.getTimestamp().isAfter(LocalDateTime.now().minusMinutes(CACHE_EXPIRY_MINUTES))) {
                return cached.getThreshold();
            }

            List<HistoricalData> historicalData = getHistoricalDataFromTrades(strategy);
            double baseThreshold = calculateLearnedThreshold(historicalData);

            // Adjust threshold based on learning phase
            double adjustedThreshold = adjustThresholdByLearningPhase(baseThreshold, learningState);

            // Apply performance-based adjustments
            double finalThreshold = adjustThresholdByPerformance(adjustedThreshold, learningState.getPerformanceScore());

            // Ensure threshold is within valid range
            finalThreshold = Math.max(0.5, Math.min(0.95, finalThreshold));

            DynamicThreshold threshold = DynamicThreshold.builder()
                    .baseThreshold(baseThreshold)
                    .adjustedThreshold(finalThreshold)
                    .strategy(strategy)
                    .optimizationMethod(determineOptimizationMethod(historicalData.size()))
                    .confidence(calculateThresholdConfidence(historicalData))
                    .lastCalculated(LocalDateTime.now())
                    .build();

            // Cache the result
            thresholdCache.put(cacheKey, new ThresholdCache(threshold, LocalDateTime.now()));

            log.debug("[THRESHOLD] Dynamic threshold for {}: {} (base: {}, method: {})",
                    strategy, String.format("%.3f",finalThreshold),String.format("%.3f",baseThreshold), threshold.getOptimizationMethod());

            return threshold;

        } catch (Exception e) {
            log.error("[THRESHOLD] Error calculating dynamic threshold for {}: {}", strategy, e.getMessage());
            return createDefaultThreshold(strategy);
        }
    }

    /**
     * Null-safe threshold calculation with ROC optimization
     */
    private double calculateLearnedThreshold(List<HistoricalData> historicalData) {
        try {
            if (historicalData == null || historicalData.isEmpty()) {
                log.warn("[LEARNING] No historical data available, using default threshold: {}", DEFAULT_THRESHOLD);
                return DEFAULT_THRESHOLD;
            }

            // Filter out invalid data points
            List<HistoricalData> validData = historicalData.stream()
                    .filter(Objects::nonNull)
                    .filter(data -> data.getConfidence() != null)
                    .filter(data -> data.getProfitability() != null)
                    .filter(data -> data.getOutcome() != null)
                    .filter(data -> !data.getConfidence().isNaN())
                    .filter(data -> !data.getConfidence().isInfinite())
                    .filter(data -> data.getConfidence() >= MIN_CONFIDENCE && data.getConfidence() <= MAX_CONFIDENCE)
                    .collect(Collectors.toList());

            if (validData.isEmpty()) {
                log.warn("[LEARNING] No valid data with complete information, using default threshold: {}", DEFAULT_THRESHOLD);
                return DEFAULT_THRESHOLD;
            }

            if (validData.size() < MIN_HISTORICAL_TRADES) {
                log.warn("[LEARNING] Insufficient valid data ({}), using median confidence", validData.size());
                return calculateMedianConfidence(validData);
            }

            // Sort by confidence using null-safe comparator
            List<HistoricalData> sortedByConfidence = validData.stream()
                    .sorted(Comparator.comparing(
                            HistoricalData::getConfidence,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ))
                    .collect(Collectors.toList());

            // Calculate optimal threshold using ROC analysis
            double optimalThreshold = calculateOptimalThresholdROC(sortedByConfidence);

            log.info("[LEARNING] Calculated learned threshold: {} from {} valid data points (filtered from {})",
                    String.format("%.3f",optimalThreshold), validData.size(), historicalData.size());

            return optimalThreshold;

        } catch (Exception e) {
            log.error("[LEARNING] Error calculating learned threshold: {}", e.getMessage(), e);
            return DEFAULT_THRESHOLD;
        }
    }

    /**
     * ROC-based threshold optimization
     */
    private double calculateOptimalThresholdROC(List<HistoricalData> data) {
        if (data.size() < MIN_HISTORICAL_TRADES) {
            return calculateMedianConfidence(data);
        }

        double bestThreshold = DEFAULT_THRESHOLD;
        double bestF1Score = 0.0;

        // Test thresholds from 0.5 to 0.95 in 0.05 increments
        for (double threshold = 0.50; threshold <= 0.95; threshold += 0.05) {
            ThresholdMetrics metrics = calculateThresholdMetrics(data, threshold);

            if (metrics.getF1Score() > bestF1Score) {
                bestF1Score = metrics.getF1Score();
                bestThreshold = threshold;
            }
        }

        log.debug("[LEARNING] Best threshold: {} with F1-score: {}", String.format("%.3f",bestThreshold), String.format("%.3f",bestF1Score));
        return bestThreshold;
    }

    /**
     * Calculate threshold metrics with null-safety
     */
    private ThresholdMetrics calculateThresholdMetrics(List<HistoricalData> data, double threshold) {
        int truePositives = 0;
        int falsePositives = 0;
        int trueNegatives = 0;
        int falseNegatives = 0;

        for (HistoricalData point : data) {
            // Skip data points with null values
            if (point.getConfidence() == null || point.getProfitability() == null) {
                continue;
            }

            boolean predictedPositive = point.getConfidence() >= threshold;
            boolean actualPositive = point.getProfitability() > 0.0;

            if (predictedPositive && actualPositive) {
                truePositives++;
            } else if (predictedPositive && !actualPositive) {
                falsePositives++;
            } else if (!predictedPositive && !actualPositive) {
                trueNegatives++;
            } else {
                falseNegatives++;
            }
        }

        return new ThresholdMetrics(truePositives, falsePositives, trueNegatives, falseNegatives);
    }

    /**
     * Calculate prior probability with null-safety
     */
    private double calculatePriorProbability(List<HistoricalData> data) {
        try {
            if (data.isEmpty()) {
                return 0.5; // Neutral prior
            }

            long successfulTrades = data.stream()
                    .filter(point -> point.getProfitability() != null)
                    .filter(point -> point.getProfitability() > 0.0)
                    .count();

            // Apply Laplace smoothing to avoid extreme priors
            return (successfulTrades + 1.0) / (data.size() + 2.0);

        } catch (Exception e) {
            log.error("[BAYESIAN] Error calculating prior probability: {}", e.getMessage());
            return 0.5;
        }
    }

    /**
     * Calculate likelihood with confidence-based weighting
     */
    private double calculateLikelihood(Signal signal, List<HistoricalData> historicalData) {
        try {
            if (signal.getConfidence() == null || historicalData.isEmpty()) {
                return 0.5;
            }

            double signalConfidence = validateConfidence(signal.getConfidence());

            // Find similar confidence data points (within 0.1 range)
            List<HistoricalData> similarData = historicalData.stream()
                    .filter(h -> h.getConfidence() != null)
                    .filter(h -> Math.abs(h.getConfidence() - signalConfidence) <= 0.1)
                    .collect(Collectors.toList());

            if (similarData.isEmpty()) {
                // Fallback to overall success rate
                return calculatePriorProbability(historicalData);
            }

            long successfulSimilar = similarData.stream()
                    .filter(h -> h.getProfitability() != null)
                    .filter(h -> h.getProfitability() > 0.0)
                    .count();

            // Apply Laplace smoothing
            return (successfulSimilar + 1.0) / (similarData.size() + 2.0);

        } catch (Exception e) {
            log.error("[BAYESIAN] Error calculating likelihood: {}", e.getMessage());
            return 0.5;
        }
    }

    /**
     * Calculate evidence (marginal probability)
     */
    private double calculateEvidence(List<HistoricalData> data) {
        try {
            if (data.isEmpty()) {
                return 1.0;
            }

            // Evidence is the average confidence weighted by outcomes
            double weightedSum = 0.0;
            double totalWeight = 0.0;

            for (HistoricalData point : data) {
                if (point.getConfidence() != null && point.getProfitability() != null) {
                    double weight = Math.abs(point.getProfitability()) + 0.1; // Avoid zero weights
                    weightedSum += point.getConfidence() * weight;
                    totalWeight += weight;
                }
            }

            return totalWeight > 0 ? weightedSum / totalWeight : 1.0;

        } catch (Exception e) {
            log.error("[BAYESIAN] Error calculating evidence: {}", e.getMessage());
            return 1.0;
        }
    }

    /**
     * Calculate adjusted confidence combining original and Bayesian analysis
     */
    private double calculateAdjustedConfidence(double originalConfidence, double posteriorProbability) {
        // Weighted combination of original confidence and Bayesian posterior
        double weight = 0.7; // 70% weight to Bayesian analysis
        return weight * posteriorProbability + (1 - weight) * originalConfidence;
    }

    /**
     * Make execution decision based on all analysis
     */
    private ExecutionDecision makeExecutionDecision(Signal signal, BayesianAnalysis analysis, DynamicThreshold threshold) {
        try {
            boolean shouldExecute = analysis.getAdjustedConfidence() >= threshold.getAdjustedThreshold();

            String decision = shouldExecute ? "EXECUTE" : "REJECT";
            String reasoning = String.format("Adjusted confidence %.3f %s threshold %.3f",
                    analysis.getAdjustedConfidence(),
                    shouldExecute ? ">=" : "<",
                    threshold.getAdjustedThreshold());

            double confidenceGap = analysis.getAdjustedConfidence() - threshold.getAdjustedThreshold();

            return ExecutionDecision.builder()
                    .shouldExecute(shouldExecute)
                    .decision(decision)
                    .reasoning(reasoning)
                    .confidenceGap(confidenceGap)
                    .threshold(threshold.getAdjustedThreshold())
                    .signalConfidence(analysis.getAdjustedConfidence())
                    .timestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("[EXECUTION] Error making execution decision: {}", e.getMessage());
            return ExecutionDecision.builder()
                    .shouldExecute(false)
                    .decision("ERROR")
                    .reasoning("Error in decision making: " + e.getMessage())
                    .confidenceGap(-1.0)
                    .threshold(DEFAULT_THRESHOLD)
                    .signalConfidence(0.0)
                    .timestamp(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * Update learning state after decision (invalidate caches)
     */
    private void updateLearningState(String strategy, BayesianAnalysis analysis, ExecutionDecision decision) {
        // Invalidate cache to force recalculation with latest data
        learningStateCache.remove(strategy);
        thresholdCache.entrySet().removeIf(entry -> entry.getKey().startsWith(strategy + "_"));
    }

    // ================================================================================================
    // UTILITY METHODS
    // ================================================================================================

    private double validateConfidence(Double confidence) {
        if (confidence == null || confidence.isNaN() || confidence.isInfinite()) {
            return DEFAULT_THRESHOLD;
        }
        return Math.max(MIN_CONFIDENCE, Math.min(MAX_CONFIDENCE, confidence));
    }

    private double calculateMedianConfidence(List<HistoricalData> data) {
        List<Double> confidences = data.stream()
                .map(HistoricalData::getConfidence)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());

        if (confidences.isEmpty()) {
            return DEFAULT_THRESHOLD;
        }

        int size = confidences.size();
        if (size % 2 == 0) {
            return (confidences.get(size/2 - 1) + confidences.get(size/2)) / 2.0;
        } else {
            return confidences.get(size/2);
        }
    }

    private double calculatePerformanceScore(List<HistoricalData> data) {
        try {
            if (data == null || data.isEmpty()) {
                return 0.0;
            }

            OptionalDouble avgProfitability = data.stream()
                    .filter(Objects::nonNull)
                    .filter(point -> point.getProfitability() != null)
                    .filter(point -> !point.getProfitability().isNaN())
                    .mapToDouble(HistoricalData::getProfitability)
                    .average();

            return avgProfitability.orElse(0.0);

        } catch (Exception e) {
            log.error("[LEARNING] Error calculating performance score: {}", e.getMessage());
            return 0.0;
        }
    }

    private ConfidenceDistribution analyzeConfidenceDistribution(List<HistoricalData> data) {
        try {
            List<Double> validConfidences = data.stream()
                    .filter(Objects::nonNull)
                    .map(HistoricalData::getConfidence)
                    .filter(Objects::nonNull)
                    .filter(c -> !c.isNaN() && !c.isInfinite())
                    .collect(Collectors.toList());

            if (validConfidences.isEmpty()) {
                return new ConfidenceDistribution(DEFAULT_THRESHOLD, 0.1, 0, 0);
            }

            DoubleSummaryStatistics stats = validConfidences.stream()
                    .mapToDouble(Double::doubleValue)
                    .summaryStatistics();

            double mean = stats.getAverage();
            double stdDev = calculateStandardDeviation(validConfidences, mean);

            long profitableCount = data.stream()
                    .filter(Objects::nonNull)
                    .filter(point -> point.getProfitability() != null)
                    .filter(point -> !point.getProfitability().isNaN())
                    .filter(point -> point.getProfitability() > 0.0)
                    .count();

            long unprofitableCount = data.size() - profitableCount;

            return new ConfidenceDistribution(mean, stdDev, profitableCount, unprofitableCount);

        } catch (Exception e) {
            log.error("[LEARNING] Error analyzing confidence distribution: {}", e.getMessage());
            return new ConfidenceDistribution(DEFAULT_THRESHOLD, 0.1, 0, 0);
        }
    }

    private double calculateStandardDeviation(List<Double> values, double mean) {
        if (values.size() <= 1) {
            return 0.1;
        }

        double sumSquaredDiffs = values.stream()
                .filter(Objects::nonNull)
                .mapToDouble(val -> Math.pow(val - mean, 2))
                .sum();

        return Math.sqrt(sumSquaredDiffs / (values.size() - 1));
    }

    private double calculateDataQuality(List<HistoricalData> data) {
        if (data.isEmpty()) {
            return 0.0;
        }

        // Data quality based on completeness and recency
        long completeData = data.stream()
                .filter(d -> d != null && d.getConfidence() != null &&
                        d.getProfitability() != null && d.getOutcome() != null)
                .count();

        double completeness = (double) completeData / data.size();

        // Recency factor (higher weight for recent data)
        LocalDateTime cutoff = LocalDateTime.now().minus(30, ChronoUnit.DAYS);
        long recentData = data.stream()
                .filter(d -> d.getTimestamp() != null && d.getTimestamp().isAfter(cutoff))
                .count();

        double recency = data.size() > 0 ? (double) recentData / data.size() : 0.0;

        return 0.7 * completeness + 0.3 * recency;
    }

    private String determineLearningPhase(int dataCount) {
        if (dataCount < 10) {
            return "BOOTSTRAPPING";
        } else if (dataCount < 50) {
            return "LEARNING";
        } else if (dataCount < 200) {
            return "OPTIMIZING";
        } else {
            return "MATURE";
        }
    }

    private String determineOptimizationMethod(int dataCount) {
        if (dataCount < MIN_HISTORICAL_TRADES) {
            return "PRIOR_BASED";
        } else if (dataCount < 50) {
            return "SIMPLE_AVERAGE";
        } else {
            return "ROC_OPTIMIZED";
        }
    }

    // ADJUSTED THRESHOLDS - MORE LENIENT FOR NEW STRATEGIES
    private double adjustThresholdByLearningPhase(double baseThreshold, LearningState learningState) {
        switch (learningState.getLearningPhase()) {
            case "BOOTSTRAPPING":
                // REDUCED: More lenient for new strategies - was +0.1, now +0.05
                return Math.min(baseThreshold + 0.05, 0.80); // Cap at 80% instead of 90%
            case "LEARNING":
                // REDUCED: Slightly more lenient - was +0.05, now +0.02
                return Math.min(baseThreshold + 0.02, 0.82); // Cap at 82% instead of 85%
            case "OPTIMIZING":
                return baseThreshold; // Use as-is
            case "MATURE":
                return Math.max(baseThreshold - 0.05, 0.6); // More aggressive
            default:
                return baseThreshold;
        }
    }

    private double adjustThresholdByPerformance(double threshold, double performanceScore) {
        if (performanceScore > 0.1) { // Good performance
            return Math.max(threshold - 0.05, 0.5);
        } else if (performanceScore < -0.1) { // Poor performance
            return Math.min(threshold + 0.1, 0.95);
        }
        return threshold;
    }

    private double calculateThresholdConfidence(List<HistoricalData> data) {
        if (data.size() < MIN_HISTORICAL_TRADES) {
            return 0.3; // Low confidence
        } else if (data.size() < 50) {
            return 0.6; // Medium confidence
        } else {
            return 0.9; // High confidence
        }
    }

    // ================================================================================================
    // DEFAULT CREATION METHODS
    // ================================================================================================

    private BayesianAnalysis createDefaultBayesianAnalysis() {
        return BayesianAnalysis.builder()
                .priorProbability(0.5)
                .likelihood(0.5)
                .evidence(1.0)
                .posteriorProbability(0.5)
                .adjustedConfidence(DEFAULT_THRESHOLD)
                .confidenceBoost(0.0)
                .dataQuality(0.0)
                .build();
    }

    private BayesianAnalysis createPriorBayesianAnalysis(Signal signal, double signalConfidence) {
        return BayesianAnalysis.builder()
                .priorProbability(0.5)
                .likelihood(signalConfidence)
                .evidence(1.0)
                .posteriorProbability(signalConfidence * 0.5)
                .adjustedConfidence(signalConfidence)
                .confidenceBoost(0.0)
                .dataQuality(0.1)
                .build();
    }

    private LearningState createDefaultLearningState(String strategy) {
        return LearningState.builder()
                .strategy(strategy)
                .signalCount(0)
                .learningPhase("BOOTSTRAPPING")
                .performanceScore(0.0)
                .confidenceDistribution(new ConfidenceDistribution(DEFAULT_THRESHOLD, 0.1, 0, 0))
                .lastUpdate(LocalDateTime.now())
                .build();
    }

    private DynamicThreshold createDefaultThreshold(String strategy) {
        return DynamicThreshold.builder()
                .baseThreshold(DEFAULT_THRESHOLD)
                .adjustedThreshold(DEFAULT_THRESHOLD)
                .strategy(strategy)
                .optimizationMethod("DEFAULT")
                .confidence(0.3)
                .lastCalculated(LocalDateTime.now())
                .build();
    }

    private IntegratedAnalysisResult createDefaultResult(String reason) {
        return IntegratedAnalysisResult.builder()
                .bayesianAnalysis(createDefaultBayesianAnalysis())
                .learningState(createDefaultLearningState("UNKNOWN"))
                .dynamicThreshold(createDefaultThreshold("UNKNOWN"))
                .executionDecision(ExecutionDecision.builder()
                        .shouldExecute(false)
                        .decision("REJECT")
                        .reasoning(reason)
                        .confidenceGap(-1.0)
                        .threshold(DEFAULT_THRESHOLD)
                        .signalConfidence(0.0)
                        .timestamp(LocalDateTime.now())
                        .build())
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ================================================================================================
    // DATA CLASSES
    // ================================================================================================

    @Data
    @Builder
    public static class BayesianAnalysis {
        private double priorProbability;
        private double likelihood;
        private double evidence;
        private double posteriorProbability;
        private double adjustedConfidence;
        private double confidenceBoost;
        private double dataQuality;
    }

    @Data
    @Builder
    public static class LearningState {
        private String strategy;
        private int signalCount;
        private String learningPhase;
        private double performanceScore;
        private ConfidenceDistribution confidenceDistribution;
        private LocalDateTime lastUpdate;
    }

    @Data
    @Builder
    public static class DynamicThreshold {
        private double baseThreshold;
        private double adjustedThreshold;
        private String strategy;
        private String optimizationMethod;
        private double confidence;
        private LocalDateTime lastCalculated;
    }

    @Data
    @Builder
    public static class ExecutionDecision {
        private boolean shouldExecute;
        private String decision;
        private String reasoning;
        private double confidenceGap;
        private double threshold;
        private double signalConfidence;
        private LocalDateTime timestamp;
    }

    @Data
    @Builder
    public static class IntegratedAnalysisResult {
        private BayesianAnalysis bayesianAnalysis;
        private LearningState learningState;
        private DynamicThreshold dynamicThreshold;
        private ExecutionDecision executionDecision;
        private LocalDateTime timestamp;
    }

    @Data
    public static class ThresholdMetrics {
        private final int truePositives;
        private final int falsePositives;
        private final int trueNegatives;
        private final int falseNegatives;

        public ThresholdMetrics(int tp, int fp, int tn, int fn) {
            this.truePositives = tp;
            this.falsePositives = fp;
            this.trueNegatives = tn;
            this.falseNegatives = fn;
        }

        public double getPrecision() {
            return truePositives + falsePositives > 0 ?
                    (double) truePositives / (truePositives + falsePositives) : 0.0;
        }

        public double getRecall() {
            return truePositives + falseNegatives > 0 ?
                    (double) truePositives / (truePositives + falseNegatives) : 0.0;
        }

        public double getF1Score() {
            double precision = getPrecision();
            double recall = getRecall();
            return precision + recall > 0 ? 2 * (precision * recall) / (precision + recall) : 0.0;
        }
    }

    @Data
    public static class ConfidenceDistribution {
        private final double mean;
        private final double standardDeviation;
        private final long profitableCount;
        private final long unprofitableCount;

        public ConfidenceDistribution(double mean, double stdDev, long profitable, long unprofitable) {
            this.mean = mean;
            this.standardDeviation = stdDev;
            this.profitableCount = profitable;
            this.unprofitableCount = unprofitable;
        }

        public double getSuccessRate() {
            long total = profitableCount + unprofitableCount;
            return total > 0 ? (double) profitableCount / total : 0.0;
        }
    }

    @Data
    private static class ThresholdCache {
        private final DynamicThreshold threshold;
        private final LocalDateTime timestamp;

        public ThresholdCache(DynamicThreshold threshold, LocalDateTime timestamp) {
            this.threshold = threshold;
            this.timestamp = timestamp;
        }
    }

    /**
     * Historical data extracted from completed trades
     */
    @Data
    @Builder
    public static class HistoricalData {
        private String strategy;
        private Double confidence;
        private Double profitability;
        private String outcome;
        private LocalDateTime timestamp;
        private String tradeId;
    }

    /**
     * Process trade closure and provide ML feedback for continuous learning
     * This method should be called whenever a trade is closed/completed
     */
    public MLFeedbackResult closeTradeWithMLFeedback(Trade closedTrade, String closureReason) {
        try {
            if (closedTrade == null) {
                log.warn("[ML_FEEDBACK] Null trade provided for feedback");
                return createDefaultFeedbackResult("NULL_TRADE");
            }

            log.info("[ML_FEEDBACK] Processing feedback for trade: {} (Strategy: {}, Status: {})",
                    closedTrade.getId(), closedTrade.getStrategy(), closedTrade.getStatus());

            // Convert trade to historical data for analysis
            HistoricalData historicalData = convertTradeToHistoricalData(closedTrade);

            if (historicalData == null) {
                log.warn("[ML_FEEDBACK] Could not convert trade to historical data");
                return createDefaultFeedbackResult("CONVERSION_FAILED");
            }

            // Analyze trade outcome
            TradeOutcomeAnalysis outcomeAnalysis = analyzeTradeOutcome(closedTrade, historicalData, closureReason);

            // Update learning metrics
            LearningMetricsUpdate metricsUpdate = updateLearningMetrics(closedTrade.getStrategy(), historicalData, outcomeAnalysis);

            // Adjust confidence calibration
            ConfidenceCalibration calibration = updateConfidenceCalibration(closedTrade.getStrategy(), historicalData);

            // Update threshold recommendations
            ThresholdRecommendation thresholdRec = updateThresholdRecommendation(closedTrade.getStrategy(), outcomeAnalysis);

            // Invalidate relevant caches to force recalculation
            invalidateStrategyCache(closedTrade.getStrategy());

            // Log learning insights
            logLearningInsights(closedTrade, outcomeAnalysis, metricsUpdate);

            MLFeedbackResult result = MLFeedbackResult.builder()
                    .tradeId(closedTrade.getId().toString())
                    .strategy(closedTrade.getStrategy())
                    .outcomeAnalysis(outcomeAnalysis)
                    .metricsUpdate(metricsUpdate)
                    .confidenceCalibration(calibration)
                    .thresholdRecommendation(thresholdRec)
                    .feedbackProcessed(true)
                    .processingTimestamp(LocalDateTime.now())
                    .build();

            log.info("[ML_FEEDBACK] Feedback processed successfully for trade: {}", closedTrade.getId());
            return result;

        } catch (Exception e) {
            log.error("[ML_FEEDBACK] Error processing ML feedback for trade {}: {}",
                    closedTrade != null ? closedTrade.getId() : "null", e.getMessage(), e);
            return createDefaultFeedbackResult("ERROR: " + e.getMessage());
        }
    }

    /**
     * Analyze the outcome of a closed trade
     */
    private TradeOutcomeAnalysis analyzeTradeOutcome(Trade trade, HistoricalData historicalData, String closureReason) {
        try {
            double profitability = historicalData.getProfitability();
            String outcome = historicalData.getOutcome();
            Double originalConfidence = trade.getSignalConfidence();

            // Calculate outcome metrics
            boolean wasSuccessful = profitability > 0.0;
            boolean metExpectations = assessExpectationsMet(originalConfidence, profitability);
            double confidenceAccuracy = calculateConfidenceAccuracy(originalConfidence, profitability);

            // Determine key learnings
            List<String> keyLearnings = extractKeyLearnings(trade, profitability, closureReason);

            // Calculate attribution factors
            OutcomeAttribution attribution = calculateOutcomeAttribution(trade, profitability, closureReason);

            return TradeOutcomeAnalysis.builder()
                    .tradeId(trade.getId().toString())
                    .profitability(profitability)
                    .outcome(outcome)
                    .wasSuccessful(wasSuccessful)
                    .metExpectations(metExpectations)
                    .confidenceAccuracy(confidenceAccuracy)
                    .originalConfidence(originalConfidence != null ? originalConfidence : 0.0)
                    .closureReason(closureReason)
                    .keyLearnings(keyLearnings)
                    .attribution(attribution)
                    .analysisTimestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("[ML_FEEDBACK] Error analyzing trade outcome: {}", e.getMessage());
            return createDefaultOutcomeAnalysis(trade, closureReason);
        }
    }

    /**
     * Update learning metrics based on trade outcome
     */
    private LearningMetricsUpdate updateLearningMetrics(String strategy, HistoricalData historicalData, TradeOutcomeAnalysis outcome) {
        try {
            // Get current learning state
            LearningState currentState = getLearningState(strategy);

            // Calculate metric deltas
            double performanceDelta = calculatePerformanceDelta(currentState, historicalData.getProfitability());
            double confidenceCalibrationDelta = calculateCalibrationDelta(currentState, outcome.getConfidenceAccuracy());

            // Update success rates
            SuccessRateUpdate successRateUpdate = updateSuccessRates(strategy, outcome.wasSuccessful);

            // Calculate learning velocity
            double learningVelocity = calculateLearningVelocity(strategy, outcome);

            return LearningMetricsUpdate.builder()
                    .strategy(strategy)
                    .performanceDelta(performanceDelta)
                    .confidenceCalibrationDelta(confidenceCalibrationDelta)
                    .successRateUpdate(successRateUpdate)
                    .learningVelocity(learningVelocity)
                    .previousSignalCount(currentState.getSignalCount())
                    .newSignalCount(currentState.getSignalCount() + 1)
                    .updateTimestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("[ML_FEEDBACK] Error updating learning metrics: {}", e.getMessage());
            return createDefaultMetricsUpdate(strategy);
        }
    }

    /**
     * Update confidence calibration based on actual outcomes
     */
    private ConfidenceCalibration updateConfidenceCalibration(String strategy, HistoricalData historicalData) {
        try {
            // Get recent historical data for calibration analysis
            List<HistoricalData> recentData = getHistoricalDataFromTrades(strategy);
            recentData.add(historicalData); // Include current trade

            // Analyze confidence vs actual performance correlation
            ConfidencePerformanceCorrelation correlation = analyzeConfidencePerformanceCorrelation(recentData);

            // Calculate calibration adjustments
            Map<String, Double> calibrationAdjustments = calculateCalibrationAdjustments(recentData);

            // Determine if recalibration is needed
            boolean needsRecalibration = assessNeedForRecalibration(correlation, calibrationAdjustments);

            return ConfidenceCalibration.builder()
                    .strategy(strategy)
                    .correlation(correlation)
                    .calibrationAdjustments(calibrationAdjustments)
                    .needsRecalibration(needsRecalibration)
                    .lastCalibrationUpdate(LocalDateTime.now())
                    .dataPointsUsed(recentData.size())
                    .build();

        } catch (Exception e) {
            log.error("[ML_FEEDBACK] Error updating confidence calibration: {}", e.getMessage());
            return createDefaultCalibration(strategy);
        }
    }

    /**
     * Update threshold recommendations based on recent performance
     */
    private ThresholdRecommendation updateThresholdRecommendation(String strategy, TradeOutcomeAnalysis outcome) {
        try {
            // Get current threshold
            LearningState learningState = getLearningState(strategy);
            DynamicThreshold currentThreshold = calculateDynamicThreshold(strategy, learningState);

            // Analyze if current threshold performed well
            boolean thresholdPerformedWell = assessThresholdPerformance(outcome, currentThreshold);

            // Calculate recommended adjustment
            double recommendedAdjustment = calculateThresholdAdjustment(outcome, currentThreshold);

            // Determine confidence in recommendation
            double recommendationConfidence = calculateRecommendationConfidence(learningState, outcome);

            return ThresholdRecommendation.builder()
                    .strategy(strategy)
                    .currentThreshold(currentThreshold.getAdjustedThreshold())
                    .recommendedAdjustment(recommendedAdjustment)
                    .newRecommendedThreshold(currentThreshold.getAdjustedThreshold() + recommendedAdjustment)
                    .thresholdPerformedWell(thresholdPerformedWell)
                    .recommendationConfidence(recommendationConfidence)
                    .basedOnTradeId(outcome.getTradeId())
                    .recommendationTimestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("[ML_FEEDBACK] Error updating threshold recommendation: {}", e.getMessage());
            return createDefaultThresholdRecommendation(strategy);
        }
    }

    /**
     * Invalidate caches for a specific strategy
     */
    private void invalidateStrategyCache(String strategy) {
        try {
            // Remove learning state cache
            learningStateCache.remove(strategy);

            // Remove threshold caches for this strategy
            thresholdCache.entrySet().removeIf(entry -> entry.getKey().startsWith(strategy + "_"));

            log.debug("[ML_FEEDBACK] Invalidated caches for strategy: {}", strategy);

        } catch (Exception e) {
            log.error("[ML_FEEDBACK] Error invalidating caches for strategy {}: {}", strategy, e.getMessage());
        }
    }

    /**
     * Log key learning insights from the trade outcome
     */
    private void logLearningInsights(Trade trade, TradeOutcomeAnalysis outcome, LearningMetricsUpdate metricsUpdate) {
        try {
            log.info("[ML_INSIGHTS] Trade {} completed - Strategy: {}, Profitability: {}, " +
                            "Original Confidence: {}, Met Expectations: {}, Performance Delta: {}",
                    trade.getId(),
                    trade.getStrategy(),
                    String.format("%.3f",outcome.getProfitability()),
                    String.format("%.3f",outcome.getOriginalConfidence()),
                    outcome.isMetExpectations(),
                    String.format("%.3f",metricsUpdate.getPerformanceDelta()));

            // Log key learnings
            if (!outcome.getKeyLearnings().isEmpty()) {
                log.info("[ML_INSIGHTS] Key learnings from trade {}: {}",
                        trade.getId(), String.join(", ", outcome.getKeyLearnings()));
            }

            // Log significant performance changes
            if (Math.abs(metricsUpdate.getPerformanceDelta()) > 0.05) {
                log.warn("[ML_INSIGHTS] Significant performance change detected for {}: {:.3f}",
                        trade.getStrategy(), metricsUpdate.getPerformanceDelta());
            }

        } catch (Exception e) {
            log.error("[ML_FEEDBACK] Error logging learning insights: {}", e.getMessage());
        }
    }

    // ================================================================================================
    // HELPER METHODS FOR ML FEEDBACK
    // ================================================================================================

    private boolean assessExpectationsMet(Double originalConfidence, double profitability) {
        if (originalConfidence == null) return false;

        // High confidence should lead to positive results
        if (originalConfidence > 0.8) {
            return profitability > 0.05; // At least 5% profit expected
        } else if (originalConfidence > 0.6) {
            return profitability > 0.0; // At least breakeven expected
        } else {
            return profitability > -0.05; // Limited loss acceptable for low confidence
        }
    }

    private double calculateConfidenceAccuracy(Double originalConfidence, double profitability) {
        if (originalConfidence == null) return 0.0;

        // Simple accuracy metric: how well confidence predicted actual outcome
        double expectedProfit = (originalConfidence - 0.5) * 0.2; // Scale confidence to expected profit range
        double accuracy = 1.0 - Math.abs(expectedProfit - profitability) / 0.2;
        return Math.max(0.0, Math.min(1.0, accuracy));
    }

    private List<String> extractKeyLearnings(Trade trade, double profitability, String closureReason) {
        List<String> learnings = new ArrayList<>();

        try {
            Double confidence = trade.getSignalConfidence();
            if (confidence != null) {
                if (confidence > 0.8 && profitability < 0.0) {
                    learnings.add("High confidence signal resulted in loss - review signal quality");
                }
                if (confidence < 0.6 && profitability > 0.1) {
                    learnings.add("Low confidence signal was surprisingly profitable - may be undervaluing signals");
                }
            }

            if ("STOPPED".equals(closureReason) && profitability < -0.1) {
                learnings.add("Stop loss effective in limiting losses");
            }

            if ("EXPIRED".equals(closureReason)) {
                learnings.add("Trade expired - consider adjusting time horizons");
            }

        } catch (Exception e) {
            log.error("[ML_FEEDBACK] Error extracting learnings: {}", e.getMessage());
        }

        return learnings;
    }

    private OutcomeAttribution calculateOutcomeAttribution(Trade trade, double profitability, String closureReason) {
        // Simplified attribution - in reality this would be more sophisticated
        return OutcomeAttribution.builder()
                .signalQuality(Math.abs(profitability) * 0.4) // 40% attributed to signal
                .marketConditions(Math.abs(profitability) * 0.3) // 30% to market
                .executionTiming(Math.abs(profitability) * 0.2) // 20% to timing
                .riskManagement(Math.abs(profitability) * 0.1) // 10% to risk mgmt
                .build();
    }

    private double calculatePerformanceDelta(LearningState currentState, double newProfitability) {
        double currentPerformance = currentState.getPerformanceScore();
        // Simple moving average update
        double weight = 1.0 / Math.max(currentState.getSignalCount(), 1);
        return weight * (newProfitability - currentPerformance);
    }

    private double calculateCalibrationDelta(LearningState currentState, double confidenceAccuracy) {
        // This would be more sophisticated in practice
        return confidenceAccuracy - 0.5; // Baseline accuracy
    }

    private SuccessRateUpdate updateSuccessRates(String strategy, boolean wasSuccessful) {
        // This would typically update persistent storage
        return SuccessRateUpdate.builder()
                .strategy(strategy)
                .wasSuccessful(wasSuccessful)
                .impactOnRate(wasSuccessful ? 0.01 : -0.01) // Simplified
                .build();
    }

    private double calculateLearningVelocity(String strategy, TradeOutcomeAnalysis outcome) {
        // Measure how quickly the system is learning from new data
        return Math.abs(outcome.getConfidenceAccuracy() - 0.5) * 2.0;
    }

    // Placeholder implementations for more complex methods
    private ConfidencePerformanceCorrelation analyzeConfidencePerformanceCorrelation(List<HistoricalData> data) {
        // Simplified implementation
        return ConfidencePerformanceCorrelation.builder()
                .correlationCoefficient(0.5)
                .sampleSize(data.size())
                .build();
    }

    private Map<String, Double> calculateCalibrationAdjustments(List<HistoricalData> data) {
        Map<String, Double> adjustments = new HashMap<>();
        adjustments.put("low_confidence", 0.0);
        adjustments.put("medium_confidence", 0.0);
        adjustments.put("high_confidence", 0.0);
        return adjustments;
    }

    private boolean assessNeedForRecalibration(ConfidencePerformanceCorrelation correlation, Map<String, Double> adjustments) {
        return Math.abs(correlation.getCorrelationCoefficient()) < 0.3;
    }

    private boolean assessThresholdPerformance(TradeOutcomeAnalysis outcome, DynamicThreshold threshold) {
        return outcome.isMetExpectations();
    }

    private double calculateThresholdAdjustment(TradeOutcomeAnalysis outcome, DynamicThreshold threshold) {
        if (!outcome.isMetExpectations()) {
            return 0.02; // Increase threshold slightly
        } else if (outcome.getConfidenceAccuracy() > 0.8) {
            return -0.01; // Decrease threshold slightly for very accurate predictions
        }
        return 0.0;
    }

    private double calculateRecommendationConfidence(LearningState learningState, TradeOutcomeAnalysis outcome) {
        // Higher confidence in recommendations when we have more data
        return Math.min(0.9, learningState.getSignalCount() / 100.0);
    }

    // ================================================================================================
    // DEFAULT CREATION METHODS FOR ML FEEDBACK
    // ================================================================================================

    private MLFeedbackResult createDefaultFeedbackResult(String reason) {
        return MLFeedbackResult.builder()
                .tradeId("unknown")
                .strategy("unknown")
                .outcomeAnalysis(createDefaultOutcomeAnalysis(null, reason))
                .metricsUpdate(createDefaultMetricsUpdate("unknown"))
                .confidenceCalibration(createDefaultCalibration("unknown"))
                .thresholdRecommendation(createDefaultThresholdRecommendation("unknown"))
                .feedbackProcessed(false)
                .processingTimestamp(LocalDateTime.now())
                .build();
    }

    private TradeOutcomeAnalysis createDefaultOutcomeAnalysis(Trade trade, String reason) {
        return TradeOutcomeAnalysis.builder()
                .tradeId(trade != null ? trade.getId().toString() : "unknown")
                .profitability(0.0)
                .outcome("UNKNOWN")
                .wasSuccessful(false)
                .metExpectations(false)
                .confidenceAccuracy(0.0)
                .originalConfidence(0.0)
                .closureReason(reason)
                .keyLearnings(Arrays.asList("Default analysis - " + reason))
                .attribution(OutcomeAttribution.builder()
                        .signalQuality(0.0).marketConditions(0.0)
                        .executionTiming(0.0).riskManagement(0.0).build())
                .analysisTimestamp(LocalDateTime.now())
                .build();
    }

    private LearningMetricsUpdate createDefaultMetricsUpdate(String strategy) {
        return LearningMetricsUpdate.builder()
                .strategy(strategy)
                .performanceDelta(0.0)
                .confidenceCalibrationDelta(0.0)
                .successRateUpdate(SuccessRateUpdate.builder()
                        .strategy(strategy).wasSuccessful(false).impactOnRate(0.0).build())
                .learningVelocity(0.0)
                .previousSignalCount(0)
                .newSignalCount(0)
                .updateTimestamp(LocalDateTime.now())
                .build();
    }

    private ConfidenceCalibration createDefaultCalibration(String strategy) {
        return ConfidenceCalibration.builder()
                .strategy(strategy)
                .correlation(ConfidencePerformanceCorrelation.builder()
                        .correlationCoefficient(0.0).sampleSize(0).build())
                .calibrationAdjustments(new HashMap<>())
                .needsRecalibration(false)
                .lastCalibrationUpdate(LocalDateTime.now())
                .dataPointsUsed(0)
                .build();
    }

    private ThresholdRecommendation createDefaultThresholdRecommendation(String strategy) {
        return ThresholdRecommendation.builder()
                .strategy(strategy)
                .currentThreshold(DEFAULT_THRESHOLD)
                .recommendedAdjustment(0.0)
                .newRecommendedThreshold(DEFAULT_THRESHOLD)
                .thresholdPerformedWell(true)
                .recommendationConfidence(0.0)
                .basedOnTradeId("unknown")
                .recommendationTimestamp(LocalDateTime.now())
                .build();
    }

    // ================================================================================================
    // ML FEEDBACK DATA CLASSES
    // ================================================================================================

    @Data
    @Builder
    public static class MLFeedbackResult {
        private String tradeId;
        private String strategy;
        private TradeOutcomeAnalysis outcomeAnalysis;
        private LearningMetricsUpdate metricsUpdate;
        private ConfidenceCalibration confidenceCalibration;
        private ThresholdRecommendation thresholdRecommendation;
        private boolean feedbackProcessed;
        private LocalDateTime processingTimestamp;
    }

    @Data
    @Builder
    public static class TradeOutcomeAnalysis {
        private String tradeId;
        private double profitability;
        private String outcome;
        private boolean wasSuccessful;
        private boolean metExpectations;
        private double confidenceAccuracy;
        private double originalConfidence;
        private String closureReason;
        private List<String> keyLearnings;
        private OutcomeAttribution attribution;
        private LocalDateTime analysisTimestamp;
    }

    @Data
    @Builder
    public static class OutcomeAttribution {
        private double signalQuality;      // 0.0 to 1.0 - how much signal contributed to outcome
        private double marketConditions;   // 0.0 to 1.0 - how much market conditions contributed
        private double executionTiming;    // 0.0 to 1.0 - how much timing contributed
        private double riskManagement;     // 0.0 to 1.0 - how much risk management contributed

        public double getTotalAttribution() {
            return signalQuality + marketConditions + executionTiming + riskManagement;
        }
    }

    @Data
    @Builder
    public static class LearningMetricsUpdate {
        private String strategy;
        private double performanceDelta;           // Change in performance score
        private double confidenceCalibrationDelta; // Change in confidence calibration
        private SuccessRateUpdate successRateUpdate;
        private double learningVelocity;           // How quickly the system is learning
        private int previousSignalCount;
        private int newSignalCount;
        private LocalDateTime updateTimestamp;
    }

    @Data
    @Builder
    public static class SuccessRateUpdate {
        private String strategy;
        private boolean wasSuccessful;
        private double impactOnRate;               // How this trade impacts overall success rate
        private LocalDateTime updateTimestamp;
    }

    @Data
    @Builder
    public static class ConfidenceCalibration {
        private String strategy;
        private ConfidencePerformanceCorrelation correlation;
        private Map<String, Double> calibrationAdjustments;    // Adjustments for different confidence ranges
        private boolean needsRecalibration;
        private LocalDateTime lastCalibrationUpdate;
        private int dataPointsUsed;
    }

    @Data
    @Builder
    public static class ConfidencePerformanceCorrelation {
        private double correlationCoefficient;     // -1.0 to 1.0 - how well confidence predicts performance
        private int sampleSize;
        private double pValue;                     // Statistical significance (optional)
        private String correlationStrength;       // "WEAK", "MODERATE", "STRONG"

        public String getCorrelationStrength() {
            double abs = Math.abs(correlationCoefficient);
            if (abs < 0.3) return "WEAK";
            else if (abs < 0.7) return "MODERATE";
            else return "STRONG";
        }
    }

    @Data
    @Builder
    public static class ThresholdRecommendation {
        private String strategy;
        private double currentThreshold;
        private double recommendedAdjustment;      // +/- adjustment to current threshold
        private double newRecommendedThreshold;
        private boolean thresholdPerformedWell;
        private double recommendationConfidence;   // 0.0 to 1.0 - confidence in the recommendation
        private String basedOnTradeId;            // Trade that triggered this recommendation
        private LocalDateTime recommendationTimestamp;

        public String getRecommendationDescription() {
            if (Math.abs(recommendedAdjustment) < 0.01) {
                return "No adjustment needed";
            } else if (recommendedAdjustment > 0) {
                return String.format("Increase threshold by %.3f (more conservative)", recommendedAdjustment);
            } else {
                return String.format("Decrease threshold by %.3f (more aggressive)", Math.abs(recommendedAdjustment));
            }
        }
    }
}