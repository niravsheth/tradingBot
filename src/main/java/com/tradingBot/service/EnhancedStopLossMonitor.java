package com.tradingBot.service;

import com.tradingBot.entity.Trade;
import com.tradingBot.model.OptionGreeks;
import com.tradingBot.model.Option;
import com.tradingBot.model.Quote;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.TradeRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnhancedStopLossMonitor {

    private final TradierService tradierService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final AdvancedSignalDetector advancedSignalDetector;
    private final TradeRepository tradeRepository;
    private final TelegramService telegramService;
    private final StopLossConfiguration config;

    // Performance monitoring
    private final LongAdder totalEvaluations = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();
    private final AtomicLong maxLatencyNanos = new AtomicLong(0);

    // Cache for real-time monitoring
    private final Map<String, StopLossState> stopLossCache = new ConcurrentHashMap<>();
    private final Map<String, VolatilitySpike> volatilitySpikeCache = new ConcurrentHashMap<>();
    private final Map<String, MarketDataQuality> dataQualityCache = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreakerState> circuitBreakerStates = new ConcurrentHashMap<>();

    @Data
    @Component
    @ConfigurationProperties(prefix = "trading.stoploss")
    public static class StopLossConfiguration {
        private double atrMultiplier = 1.0;
        private double minimumStopPercentage = 0.50;
        private double maxVolatilitySpike = 2.0;
        private double spreadThreshold = 0.15;
        private int volatilityCacheMinutes = 5;
        private long latencyThresholdNanos = 1_000_000; // 1ms
        private int maxCircuitBreakerFailures = 5;
        private long circuitBreakerTimeoutMs = 60_000; // 1 minute
        private boolean enablePerformanceLogging = true;
        private boolean enableDetailedDecisionLogging = true;
    }

    @Data
    public static class StopLossState {
        private BigDecimal originalStop;
        private BigDecimal currentStop;
        private BigDecimal atrBasedStop;
        private BigDecimal greeksBasedStop;
        private BigDecimal microstructureStop;
        private BigDecimal trendlineStop;
        private BigDecimal gapSpecificStop;

        private boolean trailingActivated;
        private boolean volatilityAdjusted;
        private boolean greeksAdjusted;
        private boolean microstructureTriggered;

        private LocalDateTime lastUpdate;
        private String adjustmentReason;
        private double confidenceScore;
        private Map<String, Object> calculationMetrics = new HashMap<>();
    }

    @Data
    public static class VolatilitySpike {
        private BigDecimal currentVolatility;
        private BigDecimal averageVolatility;
        private double spikeMultiplier;
        private boolean isSpike;
        private LocalDateTime detectedAt;
        private String severity; // LOW, MEDIUM, HIGH, EXTREME
    }

    @Data
    public static class MarketDataQuality {
        private String symbol;
        private LocalDateTime lastUpdate;
        private double latencyMicroseconds;
        private double completenessPercentage;
        private double priceAccuracyScore;
        private boolean feedHealthy;
        private int sequenceGaps;
        private long timestampJitter;
        private double overallQualityScore;
    }

    @Data
    public static class CircuitBreakerState {
        private String symbol;
        private int failureCount;
        private int successCount;
        private LocalDateTime lastFailureTime;
        private LocalDateTime nextAttemptTime;
        private boolean isOpen;
        private String lastErrorType;
    }

    @Data
    public static class StopLossDecision {
        private String decisionId;
        private boolean shouldExit;
        private String exitReason;
        private BigDecimal recommendedExitPrice;
        private String triggerType; // ATR, GREEKS, MICROSTRUCTURE, TRENDLINE, GAP, TREND_REVERSAL
        private double confidence;
        private LocalDateTime timestamp;
        private Map<String, Object> decisionFactors = new HashMap<>();
        private List<String> decisionPath = new ArrayList<>();
        private Map<String, Double> riskMetrics = new HashMap<>();
    }

    public StopLossDecision evaluateStopLoss(Trade trade) {
        String tradeId = trade.getId().toString();
        long startTime = System.nanoTime();

        // Initialize decision with unique ID for audit trail
        StopLossDecision decision = new StopLossDecision();
        decision.setDecisionId(UUID.randomUUID().toString());
        decision.setTimestamp(LocalDateTime.now());
        decision.setShouldExit(false);
        decision.setConfidence(0.0);

        // Setup MDC for structured logging
        org.slf4j.MDC.put("tradeId", tradeId);
        org.slf4j.MDC.put("symbol", trade.getSymbol());
        org.slf4j.MDC.put("optionSymbol", trade.getOptionSymbol());
        org.slf4j.MDC.put("decisionId", decision.getDecisionId());

        try {
            log.info("Stop-loss evaluation initiated",
                    kv("tradeType", trade.getType()),
                    kv("entryPrice", trade.getEntryPrice()),
                    kv("currentStopLoss", trade.getStopLoss()),
                    kv("evaluationStartTime", startTime)
            );

            // Check circuit breaker
            CircuitBreakerState circuitBreaker = getCircuitBreakerState(trade.getSymbol());
            if (circuitBreaker.isOpen() &&
                    LocalDateTime.now().isBefore(circuitBreaker.getNextAttemptTime())) {

                log.warn("Circuit breaker is open, skipping evaluation",
                        kv("failureCount", circuitBreaker.getFailureCount()),
                        kv("nextAttemptTime", circuitBreaker.getNextAttemptTime()),
                        kv("lastErrorType", circuitBreaker.getLastErrorType())
                );
                return decision;
            }

            // Get and validate market data
            QuoteResponse quoteResponse = getMarketDataWithQualityCheck(trade.getOptionSymbol());
            if (quoteResponse == null || quoteResponse.getQuote() == null) {
                handleMarketDataError(trade.getSymbol(), "Quote unavailable");
                return decision;
            }

            Quote quote = quoteResponse.getQuote();
            MarketDataQuality dataQuality = assessMarketDataQuality(trade.getSymbol(), quote);

            if (dataQuality.getOverallQualityScore() < 0.7) {
                log.warn("Market data quality degraded",
                        kv("qualityScore", dataQuality.getOverallQualityScore()),
                        kv("latencyMicros", dataQuality.getLatencyMicroseconds()),
                        kv("completeness", dataQuality.getCompletenessPercentage())
                );
            }

            BigDecimal currentBid = quote.getBid();
            BigDecimal currentAsk = quote.getAsk();
            BigDecimal currentMid = currentBid.add(currentAsk)
                    .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);

            // Get or create stop-loss state
            StopLossState state = stopLossCache.computeIfAbsent(tradeId,
                    k -> initializeStopLossState(trade));

            // Record calculation start time for each method
            Map<String, Long> calculationTimes = new HashMap<>();

            // Update all stop-loss calculations with detailed logging
            calculationTimes.put("ATR", measureCalculationTime(() ->
                    updateATRBasedStop(trade, state, quote, dataQuality)));

            calculationTimes.put("Greeks", measureCalculationTime(() ->
                    updateGreeksBasedStop(trade, state, quote)));

            calculationTimes.put("Microstructure", measureCalculationTime(() ->
                    updateMicrostructureStop(trade, state, quote)));

            calculationTimes.put("Trendline", measureCalculationTime(() ->
                    updateTrendlineStop(trade, state)));

            calculationTimes.put("Gap", measureCalculationTime(() ->
                    updateGapSpecificStop(trade, state)));

            calculationTimes.put("Volatility", measureCalculationTime(() ->
                    updateVolatilityAdjustments(trade, state)));

            // Log calculation performance
            logCalculationPerformance(trade.getSymbol(), calculationTimes);

            // Determine final stop-loss level with decision transparency
            BigDecimal finalStopLevel = calculateFinalStopLevel(state, decision);
            state.setCurrentStop(finalStopLevel);
            state.setConfidenceScore(calculateOverallConfidence(state));

            // Log stop-loss state details
            logStopLossStateDetails(trade, state, currentMid);

            // Check for exit conditions with detailed reasoning
            StopLossDecision exitDecision = checkExitConditions(trade, state, quote, decision);
            if (exitDecision.isShouldExit()) {
                logExitDecision(exitDecision, trade, state);
                recordCircuitBreakerSuccess(trade.getSymbol());
                return exitDecision;
            }

            // Check trend reversal exits
            StopLossDecision trendReversalDecision = checkTrendReversalExit(trade, state, decision);
            if (trendReversalDecision.isShouldExit()) {
                logExitDecision(trendReversalDecision, trade, state);
                recordCircuitBreakerSuccess(trade.getSymbol());
                return trendReversalDecision;
            }

            // Update trailing stops if profitable
            updateTrailingStops(trade, state, currentMid);

            // Update cache and log final status
            state.setLastUpdate(LocalDateTime.now());
            stopLossCache.put(tradeId, state);

            recordCircuitBreakerSuccess(trade.getSymbol());

            long totalTime = System.nanoTime() - startTime;
            logEvaluationCompletion(trade, state, decision, totalTime);

        } catch (Exception e) {
            handleEvaluationError(trade, decision, e, startTime);
            recordCircuitBreakerFailure(trade.getSymbol(), e);
        } finally {
            org.slf4j.MDC.clear();
        }

        return decision;
    }

    private QuoteResponse getMarketDataWithQualityCheck(String symbol) {
        long dataStartTime = System.nanoTime();
        try {
            QuoteResponse response = tradierService.getQuote(symbol);
            long dataLatency = System.nanoTime() - dataStartTime;

            log.debug("Market data retrieved",
                    kv("symbol", symbol),
                    kv("dataLatencyMicros", dataLatency / 1000.0),
                    kv("dataAvailable", response != null && response.getQuote() != null)
            );

            return response;
        } catch (Exception e) {
            log.error("Failed to retrieve market data",
                    kv("symbol", symbol),
                    kv("error", e.getMessage()),
                    kv("dataLatencyMicros", (System.nanoTime() - dataStartTime) / 1000.0)
            );
            throw e;
        }
    }

    private MarketDataQuality assessMarketDataQuality(String symbol, Quote quote) {
        MarketDataQuality quality = new MarketDataQuality();
        quality.setSymbol(symbol);
        quality.setLastUpdate(LocalDateTime.now());

        // Assess various quality metrics
        quality.setFeedHealthy(quote.getBid() != null && quote.getAsk() != null);
        quality.setCompletenessPercentage(calculateCompletenessScore(quote));
        quality.setPriceAccuracyScore(validatePriceAccuracy(quote));
        quality.setLatencyMicroseconds(calculateFeedLatency(symbol));
        quality.setSequenceGaps(detectSequenceGaps(symbol));
        quality.setTimestampJitter(calculateTimestampJitter(symbol));

        // Calculate overall quality score
        double overallScore = (quality.getCompletenessPercentage() * 0.3 +
                quality.getPriceAccuracyScore() * 0.3 +
                (quality.isFeedHealthy() ? 1.0 : 0.0) * 0.2 +
                Math.max(0, 1.0 - quality.getLatencyMicroseconds() / 1000.0) * 0.2);

        quality.setOverallQualityScore(Math.max(0.0, Math.min(1.0, overallScore)));

        dataQualityCache.put(symbol, quality);

        log.debug("Market data quality assessed",
                kv("symbol", symbol),
                kv("overallScore", quality.getOverallQualityScore()),
                kv("feedHealthy", quality.isFeedHealthy()),
                kv("completeness", quality.getCompletenessPercentage()),
                kv("latencyMicros", quality.getLatencyMicroseconds())
        );

        return quality;
    }

    private StopLossState initializeStopLossState(Trade trade) {
        StopLossState state = new StopLossState();
        state.setOriginalStop(trade.getStopLoss());
        state.setCurrentStop(trade.getStopLoss());
        state.setTrailingActivated(false);
        state.setVolatilityAdjusted(false);
        state.setGreeksAdjusted(false);
        state.setMicrostructureTriggered(false);
        state.setLastUpdate(LocalDateTime.now());
        state.setAdjustmentReason("Initial stop-loss set");
        state.setConfidenceScore(0.5); // Neutral confidence initially

        log.info("Stop-loss state initialized",
                kv("tradeId", trade.getId()),
                kv("initialStop", state.getOriginalStop()),
                kv("initializationTime", LocalDateTime.now())
        );

        return state;
    }

    private void updateATRBasedStop(Trade trade, StopLossState state, Quote quote, MarketDataQuality dataQuality) {
        try {
            log.debug("Starting ATR-based stop calculation", kv("symbol", trade.getSymbol()));

            TechnicalAnalysis ta = technicalAnalysisService.analyze(trade.getSymbol());
            if (ta == null || ta.getAverageTrueRange() == null) {
                log.warn("Technical analysis unavailable for ATR calculation",
                        kv("symbol", trade.getSymbol()),
                        kv("taAvailable", ta != null),
                        kv("atrAvailable", ta != null && ta.getAverageTrueRange() != null)
                );
                return;
            }

            BigDecimal atr = ta.getAverageTrueRange();
            BigDecimal entryPrice = trade.getEntryPrice();

            // Adjust ATR multiplier based on market conditions and data quality
            double adjustedMultiplier = config.getAtrMultiplier();
            if (dataQuality.getOverallQualityScore() < 0.8) {
                adjustedMultiplier *= 1.2; // Wider stops for poor data quality
            }

            // ATR-based stop calculation
            BigDecimal atrStop = entryPrice.subtract(
                    atr.multiply(BigDecimal.valueOf(adjustedMultiplier)));

            // Ensure stop is reasonable (not below minimum percentage)
            BigDecimal minimumStop = entryPrice.multiply(
                    BigDecimal.valueOf(config.getMinimumStopPercentage()));
            BigDecimal finalAtrStop = atrStop.max(minimumStop);

            state.setAtrBasedStop(finalAtrStop);

            // Store calculation metrics
            Map<String, Object> atrMetrics = new HashMap<>();
            atrMetrics.put("atrValue", atr);
            atrMetrics.put("multiplier", adjustedMultiplier);
            atrMetrics.put("rawAtrStop", atrStop);
            atrMetrics.put("minimumStop", minimumStop);
            atrMetrics.put("appliedMinimum", !finalAtrStop.equals(atrStop));
            atrMetrics.put("dataQualityScore", dataQuality.getOverallQualityScore());

            state.getCalculationMetrics().put("ATR", atrMetrics);

            log.info("ATR stop calculation completed",
                    kv("symbol", trade.getSymbol()),
                    kv("entryPrice", entryPrice),
                    kv("atrValue", atr),
                    kv("multiplier", adjustedMultiplier),
                    kv("atrStop", finalAtrStop),
                    kv("stopDistance", entryPrice.subtract(finalAtrStop)),
                    kv("stopDistancePercent", entryPrice.subtract(finalAtrStop)
                            .divide(entryPrice, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))),
                    kv("minimumApplied", !finalAtrStop.equals(atrStop)),
                    kv("dataQualityAdjustment", dataQuality.getOverallQualityScore() < 0.8)
            );

        } catch (Exception e) {
            log.error("Error in ATR-based stop calculation",
                    kv("symbol", trade.getSymbol()),
                    kv("error", e.getMessage()),
                    kv("errorType", e.getClass().getSimpleName())
            );
            state.getCalculationMetrics().put("ATR_ERROR", e.getMessage());
        }
    }

    private void updateGreeksBasedStop(Trade trade, StopLossState state, Quote quote) {
        try {
            log.debug("Starting Greeks-based stop calculation", kv("optionSymbol", trade.getOptionSymbol()));

            LocalDateTime today = LocalDateTime.now();
            com.tradingBot.model.OptionChainResponse chain = tradierService.getOptionChain(
                    trade.getSymbol(), today.toLocalDate());

            if (chain == null || !chain.hasOptions()) {
                log.warn("Option chain unavailable for Greeks calculation",
                        kv("symbol", trade.getSymbol()),
                        kv("chainAvailable", chain != null),
                        kv("hasOptions", chain != null && chain.hasOptions())
                );
                return;
            }

            Optional<Option> optionOpt = chain.getOptionsList().stream()
                    .filter(opt -> opt.getSymbol().equals(trade.getOptionSymbol()))
                    .findFirst();

            if (!optionOpt.isPresent() || optionOpt.get().getGreeks() == null) {
                log.warn("Greeks data unavailable",
                        kv("optionSymbol", trade.getOptionSymbol()),
                        kv("optionFound", optionOpt.isPresent()),
                        kv("greeksAvailable", optionOpt.isPresent() && optionOpt.get().getGreeks() != null)
                );
                return;
            }

            Option option = optionOpt.get();
            OptionGreeks greeks = option.getGreeks();
            BigDecimal entryPrice = trade.getEntryPrice();

            // Calculate Greeks-based risk metrics
            double deltaValue = Math.abs(greeks.getDelta().doubleValue());
            double gammaValue = Math.abs(greeks.getGamma().doubleValue());
            double thetaValue = greeks.getTheta().doubleValue();
            double vegaValue = Math.abs(greeks.getVega().doubleValue());

            // Delta-adjusted stop calculation
            BigDecimal deltaAdjustment = BigDecimal.valueOf(deltaValue * 2.0);
            BigDecimal greeksStop = entryPrice.multiply(BigDecimal.ONE.subtract(deltaAdjustment));

            // Time decay adjustment
            double hoursToExpiry = getHoursToExpiry();
            boolean thetaAdjustmentApplied = false;
            if (hoursToExpiry < 2.0) {
                greeksStop = greeksStop.multiply(BigDecimal.valueOf(0.85));
                state.setGreeksAdjusted(true);
                state.setAdjustmentReason("Greeks adjusted for theta decay");
                thetaAdjustmentApplied = true;
            }

            // Gamma risk adjustment
            boolean gammaAdjustmentApplied = false;
            if (gammaValue > 0.05) {
                greeksStop = greeksStop.multiply(BigDecimal.valueOf(0.90));
                gammaAdjustmentApplied = true;
            }

            state.setGreeksBasedStop(greeksStop);

            // Store detailed Greeks metrics
            Map<String, Object> greeksMetrics = new HashMap<>();
            greeksMetrics.put("delta", deltaValue);
            greeksMetrics.put("gamma", gammaValue);
            greeksMetrics.put("theta", thetaValue);
            greeksMetrics.put("vega", vegaValue);
            greeksMetrics.put("hoursToExpiry", hoursToExpiry);
            greeksMetrics.put("thetaAdjustmentApplied", thetaAdjustmentApplied);
            greeksMetrics.put("gammaAdjustmentApplied", gammaAdjustmentApplied);
            greeksMetrics.put("deltaRiskLevel", assessDeltaRisk(deltaValue));
            greeksMetrics.put("gammaRiskLevel", assessGammaRisk(gammaValue));

            state.getCalculationMetrics().put("GREEKS", greeksMetrics);

            log.info("Greeks stop calculation completed",
                    kv("optionSymbol", trade.getOptionSymbol()),
                    kv("delta", deltaValue),
                    kv("gamma", gammaValue),
                    kv("theta", thetaValue),
                    kv("vega", vegaValue),
                    kv("hoursToExpiry", hoursToExpiry),
                    kv("greeksStop", greeksStop),
                    kv("thetaAdjusted", thetaAdjustmentApplied),
                    kv("gammaAdjusted", gammaAdjustmentApplied),
                    kv("deltaRisk", assessDeltaRisk(deltaValue)),
                    kv("gammaRisk", assessGammaRisk(gammaValue))
            );

        } catch (Exception e) {
            log.error("Error in Greeks-based stop calculation",
                    kv("optionSymbol", trade.getOptionSymbol()),
                    kv("error", e.getMessage()),
                    kv("errorType", e.getClass().getSimpleName())
            );
            state.getCalculationMetrics().put("GREEKS_ERROR", e.getMessage());
        }
    }

    private void updateMicrostructureStop(Trade trade, StopLossState state, Quote quote) {
        try {
            log.debug("Starting microstructure stop calculation", kv("symbol", trade.getSymbol()));

            BigDecimal bid = quote.getBid();
            BigDecimal ask = quote.getAsk();

            if (bid == null || ask == null || bid.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Invalid bid/ask data for microstructure analysis",
                        kv("symbol", trade.getSymbol()),
                        kv("bid", bid),
                        kv("ask", ask)
                );
                return;
            }

            // Calculate market microstructure metrics
            BigDecimal spread = ask.subtract(bid);
            BigDecimal midPrice = bid.add(ask).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            BigDecimal spreadPercentage = spread.divide(midPrice, 4, RoundingMode.HALF_UP);

            boolean wideSpreadDetected = spreadPercentage.compareTo(
                    BigDecimal.valueOf(config.getSpreadThreshold())) > 0;

            BigDecimal microstructureStop;
            String microstructureReason;

            if (wideSpreadDetected) {
                // Wide spread - tighten stops due to liquidity concerns
                BigDecimal entryPrice = trade.getEntryPrice();
                microstructureStop = entryPrice.multiply(BigDecimal.valueOf(0.80));
                state.setMicrostructureTriggered(true);
                microstructureReason = "Wide spread detected - liquidity concerns";

                log.warn("Wide spread detected, tightening microstructure stop",
                        kv("symbol", trade.getSymbol()),
                        kv("spreadPercentage", spreadPercentage.multiply(BigDecimal.valueOf(100))),
                        kv("threshold", config.getSpreadThreshold() * 100),
                        kv("tightenedStop", microstructureStop)
                );
            } else {
                // Normal microstructure - use bid-based stop for long positions
                microstructureStop = bid.multiply(BigDecimal.valueOf(0.95));
                microstructureReason = "Normal spread - bid-based stop";
            }

            state.setMicrostructureStop(microstructureStop);

            // Store microstructure metrics
            Map<String, Object> microMetrics = new HashMap<>();
            microMetrics.put("bid", bid);
            microMetrics.put("ask", ask);
            microMetrics.put("spread", spread);
            microMetrics.put("spreadPercentage", spreadPercentage.doubleValue());
            microMetrics.put("spreadThreshold", config.getSpreadThreshold());
            microMetrics.put("wideSpreadDetected", wideSpreadDetected);
            microMetrics.put("liquidityRisk", assessLiquidityRisk(spreadPercentage.doubleValue()));
            microMetrics.put("reason", microstructureReason);

            state.getCalculationMetrics().put("MICROSTRUCTURE", microMetrics);

            log.info("Microstructure stop calculation completed",
                    kv("symbol", trade.getSymbol()),
                    kv("bid", bid),
                    kv("ask", ask),
                    kv("spread", spread),
                    kv("spreadPercent", spreadPercentage.multiply(BigDecimal.valueOf(100))),
                    kv("microstructureStop", microstructureStop),
                    kv("wideSpread", wideSpreadDetected),
                    kv("liquidityRisk", assessLiquidityRisk(spreadPercentage.doubleValue())),
                    kv("reason", microstructureReason)
            );

        } catch (Exception e) {
            log.error("Error in microstructure stop calculation",
                    kv("symbol", trade.getSymbol()),
                    kv("error", e.getMessage()),
                    kv("errorType", e.getClass().getSimpleName())
            );
            state.getCalculationMetrics().put("MICROSTRUCTURE_ERROR", e.getMessage());
        }
    }

    private void updateTrendlineStop(Trade trade, StopLossState state) {
        try {
            log.debug("Starting trendline stop calculation", kv("symbol", trade.getSymbol()));

            TechnicalAnalysis ta = technicalAnalysisService.analyze(trade.getSymbol());
            if (ta == null) {
                log.warn("Technical analysis unavailable for trendline calculation",
                        kv("symbol", trade.getSymbol())
                );
                return;
            }

            AdvancedSignalDetector.AdvancedSignalResult signals =
                    advancedSignalDetector.detectAdvancedSignals(trade.getSymbol(), ta.getTrend());

            boolean isCallPosition = "CALL".equals(trade.getType());
            boolean trendlineBreakDetected = false;
            String breakDirection = "";
            int breakSignalCount = 0;

            // Analyze trendline break signals
            for (com.tradingBot.entity.Signal signal : signals.getTrendlineBreakSignals()) {
                if (signal.getStrategy().contains("TRENDLINE_BREAK")) {
                    breakSignalCount++;
                    boolean isBearishBreak = signal.getStrategy().contains("PUT");

                    // Check if trendline break is against our position
                    if ((isCallPosition && isBearishBreak) || (!isCallPosition && !isBearishBreak)) {
                        BigDecimal trendlineStop = trade.getEntryPrice().multiply(BigDecimal.valueOf(0.85));
                        state.setTrendlineStop(trendlineStop);
                        state.setAdjustmentReason("Trendline break against position");
                        trendlineBreakDetected = true;
                        breakDirection = isBearishBreak ? "BEARISH" : "BULLISH";

                        log.warn("Trendline break against position detected",
                                kv("symbol", trade.getSymbol()),
                                kv("positionType", trade.getType()),
                                kv("breakDirection", breakDirection),
                                kv("signalStrategy", signal.getStrategy()),
                                kv("trendlineStop", trendlineStop)
                        );
                        break;
                    }
                }
            }

            // Store trendline analysis metrics
            Map<String, Object> trendlineMetrics = new HashMap<>();
            trendlineMetrics.put("trendDirection", ta.getTrend());
            trendlineMetrics.put("breakSignalCount", breakSignalCount);
            trendlineMetrics.put("trendlineBreakDetected", trendlineBreakDetected);
            trendlineMetrics.put("breakDirection", breakDirection);
            trendlineMetrics.put("positionType", trade.getType());
            trendlineMetrics.put("signalStrength", calculateTrendSignalStrength(signals));

            state.getCalculationMetrics().put("TRENDLINE", trendlineMetrics);

            log.info("Trendline stop calculation completed",
                    kv("symbol", trade.getSymbol()),
                    kv("trend", ta.getTrend()),
                    kv("breakSignals", breakSignalCount),
                    kv("breakDetected", trendlineBreakDetected),
                    kv("breakDirection", breakDirection),
                    kv("positionType", trade.getType()),
                    kv("signalStrength", calculateTrendSignalStrength(signals))
            );

        } catch (Exception e) {
            log.error("Error in trendline stop calculation",
                    kv("symbol", trade.getSymbol()),
                    kv("error", e.getMessage()),
                    kv("errorType", e.getClass().getSimpleName())
            );
            state.getCalculationMetrics().put("TRENDLINE_ERROR", e.getMessage());
        }
    }

    private void updateGapSpecificStop(Trade trade, StopLossState state) {
        try {
            log.debug("Starting gap-specific stop calculation", kv("symbol", trade.getSymbol()));

            TechnicalAnalysis ta = technicalAnalysisService.analyze(trade.getSymbol());
            if (ta == null) {
                log.warn("Technical analysis unavailable for gap calculation",
                        kv("symbol", trade.getSymbol())
                );
                return;
            }

            AdvancedSignalDetector.AdvancedSignalResult signals =
                    advancedSignalDetector.detectAdvancedSignals(trade.getSymbol(), ta.getTrend());

            boolean gapDetected = false;
            String gapType = "";
            int gapSignalCount = 0;

            // Check for gap-fill signals
            for (com.tradingBot.entity.Signal signal : signals.getGapFillSignals()) {
                if (signal.getStrategy().contains("GAP_FILL")) {
                    gapSignalCount++;
                    gapDetected = true;
                    gapType = extractGapType(signal.getStrategy());

                    // Gap-specific stops are tighter (0.5× ATR)
                    BigDecimal atr = ta.getAverageTrueRange();
                    if (atr != null) {
                        BigDecimal gapStop = trade.getEntryPrice().subtract(
                                atr.multiply(BigDecimal.valueOf(0.5)));
                        state.setGapSpecificStop(gapStop);
                        state.setAdjustmentReason("Gap-specific stop (0.5x ATR)");

                        log.info("Gap-specific stop applied",
                                kv("symbol", trade.getSymbol()),
                                kv("gapType", gapType),
                                kv("atr", atr),
                                kv("gapStop", gapStop),
                                kv("signalStrategy", signal.getStrategy())
                        );
                    }
                    break;
                }
            }

            // Store gap analysis metrics
            Map<String, Object> gapMetrics = new HashMap<>();
            gapMetrics.put("gapDetected", gapDetected);
            gapMetrics.put("gapType", gapType);
            gapMetrics.put("gapSignalCount", gapSignalCount);
            gapMetrics.put("atrAvailable", ta.getAverageTrueRange() != null);
            if (ta.getAverageTrueRange() != null) {
                gapMetrics.put("atrValue", ta.getAverageTrueRange());
            }

            state.getCalculationMetrics().put("GAP", gapMetrics);

            log.info("Gap-specific stop calculation completed",
                    kv("symbol", trade.getSymbol()),
                    kv("gapDetected", gapDetected),
                    kv("gapType", gapType),
                    kv("gapSignals", gapSignalCount),
                    kv("atrAvailable", ta.getAverageTrueRange() != null)
            );

        } catch (Exception e) {
            log.error("Error in gap-specific stop calculation",
                    kv("symbol", trade.getSymbol()),
                    kv("error", e.getMessage()),
                    kv("errorType", e.getClass().getSimpleName())
            );
            state.getCalculationMetrics().put("GAP_ERROR", e.getMessage());
        }
    }

    private void updateVolatilityAdjustments(Trade trade, StopLossState state) {
        try {
            log.debug("Starting volatility adjustment calculation", kv("symbol", trade.getSymbol()));

            String symbol = trade.getSymbol();
            VolatilitySpike spike = detectVolatilitySpike(symbol);

            boolean adjustmentApplied = false;
            String adjustmentReason = "";

            if (spike.isSpike()) {
                // Widen stops during volatility spikes
                BigDecimal currentStop = state.getCurrentStop();
                if (currentStop != null) {
                    double adjustmentFactor = calculateVolatilityAdjustmentFactor(spike.getSpikeMultiplier());
                    BigDecimal adjustedStop = currentStop.multiply(BigDecimal.valueOf(adjustmentFactor));
                    state.setCurrentStop(adjustedStop);
                    state.setVolatilityAdjusted(true);
                    adjustmentReason = String.format(
                            "Volatility spike %.1fx detected - stop widened by %.1f%%",
                            spike.getSpikeMultiplier(), (1 - adjustmentFactor) * 100);
                    state.setAdjustmentReason(adjustmentReason);
                    adjustmentApplied = true;

                    log.warn("Volatility spike adjustment applied",
                            kv("symbol", trade.getSymbol()),
                            kv("spikeMultiplier", spike.getSpikeMultiplier()),
                            kv("spikeSeverity", spike.getSeverity()),
                            kv("adjustmentFactor", adjustmentFactor),
                            kv("originalStop", currentStop),
                            kv("adjustedStop", adjustedStop)
                    );
                }
            }

            // Store volatility metrics
            Map<String, Object> volMetrics = new HashMap<>();
            volMetrics.put("currentVolatility", spike.getCurrentVolatility());
            volMetrics.put("averageVolatility", spike.getAverageVolatility());
            volMetrics.put("spikeMultiplier", spike.getSpikeMultiplier());
            volMetrics.put("isSpike", spike.isSpike());
            volMetrics.put("spikeSeverity", spike.getSeverity());
            volMetrics.put("adjustmentApplied", adjustmentApplied);
            volMetrics.put("adjustmentReason", adjustmentReason);

            state.getCalculationMetrics().put("VOLATILITY", volMetrics);

            log.info("Volatility adjustment calculation completed",
                    kv("symbol", trade.getSymbol()),
                    kv("currentVol", spike.getCurrentVolatility()),
                    kv("averageVol", spike.getAverageVolatility()),
                    kv("spikeMultiplier", spike.getSpikeMultiplier()),
                    kv("isSpike", spike.isSpike()),
                    kv("severity", spike.getSeverity()),
                    kv("adjustmentApplied", adjustmentApplied)
            );

        } catch (Exception e) {
            log.error("Error in volatility adjustment calculation",
                    kv("symbol", trade.getSymbol()),
                    kv("error", e.getMessage()),
                    kv("errorType", e.getClass().getSimpleName())
            );
            state.getCalculationMetrics().put("VOLATILITY_ERROR", e.getMessage());
        }
    }

    private VolatilitySpike detectVolatilitySpike(String symbol) {
        VolatilitySpike spike = volatilitySpikeCache.get(symbol);

        if (spike != null && spike.getDetectedAt().isAfter(
                LocalDateTime.now().minusMinutes(config.getVolatilityCacheMinutes()))) {
            return spike; // Use cached spike if recent
        }

        spike = new VolatilitySpike();
        spike.setDetectedAt(LocalDateTime.now());

        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta != null && ta.getCurrentVolatility() != null && ta.getAverageVolatility() != null) {
                spike.setCurrentVolatility(ta.getCurrentVolatility());
                spike.setAverageVolatility(ta.getAverageVolatility());

                double ratio = ta.getCurrentVolatility().divide(ta.getAverageVolatility(),
                        4, RoundingMode.HALF_UP).doubleValue();
                spike.setSpikeMultiplier(ratio);
                spike.setSpike(ratio > config.getMaxVolatilitySpike());
                spike.setSeverity(categorizeSeverity(ratio));
            } else {
                spike.setSpike(false);
                spike.setSpikeMultiplier(1.0);
                spike.setSeverity("NORMAL");
            }

            volatilitySpikeCache.put(symbol, spike);

            log.debug("Volatility spike detection completed",
                    kv("symbol", symbol),
                    kv("spikeMultiplier", spike.getSpikeMultiplier()),
                    kv("isSpike", spike.isSpike()),
                    kv("severity", spike.getSeverity())
            );

        } catch (Exception e) {
            log.error("Error detecting volatility spike",
                    kv("symbol", symbol),
                    kv("error", e.getMessage())
            );
            spike.setSpike(false);
            spike.setSeverity("ERROR");
        }

        return spike;
    }

    private BigDecimal calculateFinalStopLevel(StopLossState state, StopLossDecision decision) {
        List<BigDecimal> stops = Arrays.asList(
                state.getAtrBasedStop(),
                state.getGreeksBasedStop(),
                state.getMicrostructureStop(),
                state.getTrendlineStop(),
                state.getGapSpecificStop()
        );

        // Filter out null values and log available stops
        List<BigDecimal> validStops = stops.stream()
                .filter(Objects::nonNull)
                .sorted(BigDecimal::compareTo)
                .collect(java.util.stream.Collectors.toList());

        if (validStops.isEmpty()) {
            log.warn("No valid stop-loss levels calculated, using original stop");
            return state.getOriginalStop();
        }

        // Use the highest (most conservative) stop level
        BigDecimal finalStop = Collections.max(validStops);

        // Record decision factors
        decision.getDecisionFactors().put("availableStops", validStops.size());
        decision.getDecisionFactors().put("finalStopLevel", finalStop);
        decision.getDecisionFactors().put("originalStop", state.getOriginalStop());
        decision.getDecisionFactors().put("stopSource", determineStopSource(state, finalStop));

        log.info("Final stop level calculated",
                kv("availableStops", validStops.size()),
                kv("finalStop", finalStop),
                kv("originalStop", state.getOriginalStop()),
                kv("stopSource", determineStopSource(state, finalStop)),
                kv("allStops", validStops)
        );

        return finalStop;
    }

    private StopLossDecision checkExitConditions(Trade trade, StopLossState state, Quote quote, StopLossDecision decision) {
        decision.getDecisionPath().add("Checking exit conditions");

        BigDecimal currentBid = quote.getBid();
        BigDecimal finalStop = state.getCurrentStop();

        if (currentBid != null && finalStop != null && currentBid.compareTo(finalStop) <= 0) {
            decision.setShouldExit(true);
            decision.setExitReason("Stop-loss triggered");
            decision.setRecommendedExitPrice(currentBid);
            decision.setConfidence(0.95);

            // Determine trigger type based on which stop was hit
            String triggerType = determineTriggerType(state, finalStop);
            decision.setTriggerType(triggerType);

            // Calculate risk metrics
            calculateRiskMetrics(trade, state, decision);

            // Record detailed decision factors
            decision.getDecisionFactors().put("currentBid", currentBid);
            decision.getDecisionFactors().put("stopLevel", finalStop);
            decision.getDecisionFactors().put("stopBreached", true);
            decision.getDecisionFactors().put("breachAmount", finalStop.subtract(currentBid));
            decision.getDecisionFactors().put("breachPercent",
                    finalStop.subtract(currentBid).divide(finalStop, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)));

            decision.getDecisionPath().add("Stop-loss breach detected: " + triggerType);
            decision.getDecisionPath().add("Exit recommended with high confidence");

            log.warn("Stop-loss triggered",
                    kv("symbol", trade.getSymbol()),
                    kv("triggerType", triggerType),
                    kv("currentBid", currentBid),
                    kv("stopLevel", finalStop),
                    kv("breachAmount", finalStop.subtract(currentBid)),
                    kv("exitReason", decision.getExitReason())
            );
        } else {
            decision.getDecisionPath().add("No stop-loss breach detected");
            decision.getDecisionFactors().put("stopBreached", false);
            if (currentBid != null && finalStop != null) {
                decision.getDecisionFactors().put("distanceToStop", currentBid.subtract(finalStop));
                decision.getDecisionFactors().put("distanceToStopPercent",
                        currentBid.subtract(finalStop).divide(currentBid, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100)));
            }
        }

        return decision;
    }

    private StopLossDecision checkTrendReversalExit(Trade trade, StopLossState state, StopLossDecision decision) {
        decision.getDecisionPath().add("Checking trend reversal conditions");

        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(trade.getSymbol());
            if (ta == null) {
                decision.getDecisionPath().add("Technical analysis unavailable for trend check");
                return decision;
            }

            String currentTrend = ta.getTrend();
            boolean isCallPosition = "CALL".equals(trade.getType());
            boolean trendReversalDetected = false;
            String reversalType = "";

            // Check if trend has reversed against our position
            if (isCallPosition && "DOWN".equals(currentTrend)) {
                // Call position but trend turned bearish
                trendReversalDetected = true;
                reversalType = "BEARISH_AGAINST_CALL";
                decision.setShouldExit(true);
                decision.setExitReason("Trend reversal - bearish trend against CALL position");
                decision.setTriggerType("TREND_REVERSAL");
                decision.setConfidence(0.80);
                decision.getDecisionPath().add("Bearish trend reversal against CALL position");
            }
            else if (!isCallPosition && "UP".equals(currentTrend)) {
                // Put position but trend turned bullish
                trendReversalDetected = true;
                reversalType = "BULLISH_AGAINST_PUT";
                decision.setShouldExit(true);
                decision.setExitReason("Trend reversal - bullish trend against PUT position");
                decision.setTriggerType("TREND_REVERSAL");
                decision.setConfidence(0.80);
                decision.getDecisionPath().add("Bullish trend reversal against PUT position");
            } else {
                decision.getDecisionPath().add("No trend reversal against position detected");
            }

            // Set exit price if reversal detected
            if (trendReversalDetected) {
                QuoteResponse quote = tradierService.getQuote(trade.getOptionSymbol());
                if (quote != null && quote.getQuote() != null) {
                    decision.setRecommendedExitPrice(quote.getQuote().getBid());
                }

                // Calculate trend reversal risk metrics
                decision.getRiskMetrics().put("trendStrength", calculateTrendStrength(ta));
                decision.getRiskMetrics().put("reversalConfidence", 0.80);
                decision.getRiskMetrics().put("trendConsistency", calculateTrendConsistency(ta));
            }

            // Record trend analysis in decision factors
            decision.getDecisionFactors().put("currentTrend", currentTrend);
            decision.getDecisionFactors().put("positionType", trade.getType());
            decision.getDecisionFactors().put("trendReversalDetected", trendReversalDetected);
            decision.getDecisionFactors().put("reversalType", reversalType);
            decision.getDecisionFactors().put("trendStrength", calculateTrendStrength(ta));

            log.info("Trend reversal analysis completed",
                    kv("symbol", trade.getSymbol()),
                    kv("currentTrend", currentTrend),
                    kv("positionType", trade.getType()),
                    kv("reversalDetected", trendReversalDetected),
                    kv("reversalType", reversalType),
                    kv("shouldExit", decision.isShouldExit())
            );

        } catch (Exception e) {
            log.error("Error checking trend reversal exit",
                    kv("symbol", trade.getSymbol()),
                    kv("error", e.getMessage())
            );
            decision.getDecisionPath().add("Error in trend reversal analysis: " + e.getMessage());
        }

        return decision;
    }

    private void updateTrailingStops(Trade trade, StopLossState state, BigDecimal currentPrice) {
        try {
            BigDecimal entryPrice = trade.getEntryPrice();
            BigDecimal currentPnL = currentPrice.subtract(entryPrice);
            BigDecimal pnlPercentage = currentPnL.divide(entryPrice, 4, RoundingMode.HALF_UP);

            boolean trailingUpdated = false;
            String trailingReason = "";
            BigDecimal newTrailingStop = null;

            // Implement trailing stops for profitable positions
            if (pnlPercentage.compareTo(BigDecimal.valueOf(0.20)) > 0) { // 20%+ profit

                if (pnlPercentage.compareTo(BigDecimal.valueOf(0.50)) > 0) { // 50%+ profit
                    newTrailingStop = entryPrice.multiply(BigDecimal.valueOf(1.30)); // Lock in 30%
                    trailingReason = "50%+ profit - trailing at 30% gain";
                } else if (pnlPercentage.compareTo(BigDecimal.valueOf(0.30)) > 0) { // 30%+ profit
                    newTrailingStop = entryPrice.multiply(BigDecimal.valueOf(1.15)); // Lock in 15%
                    trailingReason = "30%+ profit - trailing at 15% gain";
                } else { // 20%+ profit
                    newTrailingStop = entryPrice.multiply(BigDecimal.valueOf(1.05)); // Lock in 5%
                    trailingReason = "20%+ profit - trailing at 5% gain";
                }

                // Only update if trailing stop is higher than current stop
                if (newTrailingStop != null &&
                        (state.getCurrentStop() == null || newTrailingStop.compareTo(state.getCurrentStop()) > 0)) {

                    BigDecimal previousStop = state.getCurrentStop();
                    state.setCurrentStop(newTrailingStop);
                    state.setTrailingActivated(true);
                    state.setAdjustmentReason(trailingReason);
                    // MISSING: Update database
                    trade.setStopLoss(newTrailingStop);
                    tradeRepository.save(trade);
                    trailingUpdated = true;

                    log.info("Trailing stop updated",
                            kv("symbol", trade.getSymbol()),
                            kv("entryPrice", entryPrice),
                            kv("currentPrice", currentPrice),
                            kv("pnlPercent", pnlPercentage.multiply(BigDecimal.valueOf(100))),
                            kv("previousStop", previousStop),
                            kv("newTrailingStop", newTrailingStop),
                            kv("reason", trailingReason)
                    );
                }
            }

            // Store trailing stop metrics
            Map<String, Object> trailingMetrics = new HashMap<>();
            trailingMetrics.put("currentPnL", currentPnL);
            trailingMetrics.put("pnlPercentage", pnlPercentage.doubleValue());
            trailingMetrics.put("trailingEligible", pnlPercentage.compareTo(BigDecimal.valueOf(0.20)) > 0);
            trailingMetrics.put("trailingUpdated", trailingUpdated);
            trailingMetrics.put("trailingReason", trailingReason);
            trailingMetrics.put("newTrailingStop", newTrailingStop);

            state.getCalculationMetrics().put("TRAILING", trailingMetrics);

            log.debug("Trailing stop analysis completed",
                    kv("symbol", trade.getSymbol()),
                    kv("pnlPercent", pnlPercentage.multiply(BigDecimal.valueOf(100))),
                    kv("trailingEligible", pnlPercentage.compareTo(BigDecimal.valueOf(0.20)) > 0),
                    kv("trailingUpdated", trailingUpdated)
            );

        } catch (Exception e) {
            log.error("Error updating trailing stops",
                    kv("symbol", trade.getSymbol()),
                    kv("error", e.getMessage())
            );
        }
    }

    // Helper methods for detailed logging and analysis
    private long measureCalculationTime(Runnable calculation) {
        long start = System.nanoTime();
        calculation.run();
        return System.nanoTime() - start;
    }

    private void logCalculationPerformance(String symbol, Map<String, Long> calculationTimes) {
        if (!config.isEnablePerformanceLogging()) return;

        long totalTime = calculationTimes.values().stream().mapToLong(Long::longValue).sum();

        log.info("Stop-loss calculation performance",
                kv("symbol", symbol),
                kv("totalTimeNanos", totalTime),
                kv("totalTimeMicros", totalTime / 1000.0),
                kv("atrTimeNanos", calculationTimes.get("ATR")),
                kv("greeksTimeNanos", calculationTimes.get("Greeks")),
                kv("microstructureTimeNanos", calculationTimes.get("Microstructure")),
                kv("trendlineTimeNanos", calculationTimes.get("Trendline")),
                kv("gapTimeNanos", calculationTimes.get("Gap")),
                kv("volatilityTimeNanos", calculationTimes.get("Volatility"))
        );

        // Update performance statistics
        totalEvaluations.increment();
        totalLatencyNanos.add(totalTime);
        maxLatencyNanos.updateAndGet(current -> Math.max(current, totalTime));

        // Alert on performance degradation
        if (totalTime > config.getLatencyThresholdNanos()) {
            log.warn("Performance threshold exceeded",
                    kv("symbol", symbol),
                    kv("latencyNanos", totalTime),
                    kv("thresholdNanos", config.getLatencyThresholdNanos()),
                    kv("exceedancePercent", ((double) totalTime / config.getLatencyThresholdNanos() - 1) * 100)
            );
        }
    }

    private void logStopLossStateDetails(Trade trade, StopLossState state, BigDecimal currentPrice) {
        if (!config.isEnableDetailedDecisionLogging()) return;

        BigDecimal entryPrice = trade.getEntryPrice();
        BigDecimal pnl = currentPrice.subtract(entryPrice);
        BigDecimal pnlPercent = pnl.divide(entryPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        log.info("Stop-loss state details",
                kv("symbol", trade.getSymbol()),
                kv("entryPrice", entryPrice),
                kv("currentPrice", currentPrice),
                kv("pnl", pnl),
                kv("pnlPercent", pnlPercent),
                kv("originalStop", state.getOriginalStop()),
                kv("currentStop", state.getCurrentStop()),
                kv("atrStop", state.getAtrBasedStop()),
                kv("greeksStop", state.getGreeksBasedStop()),
                kv("microstructureStop", state.getMicrostructureStop()),
                kv("trendlineStop", state.getTrendlineStop()),
                kv("gapStop", state.getGapSpecificStop()),
                kv("trailingActivated", state.isTrailingActivated()),
                kv("volatilityAdjusted", state.isVolatilityAdjusted()),
                kv("greeksAdjusted", state.isGreeksAdjusted()),
                kv("microstructureTriggered", state.isMicrostructureTriggered()),
                kv("confidenceScore", state.getConfidenceScore()),
                kv("adjustmentReason", state.getAdjustmentReason())
        );
    }

    private void logExitDecision(StopLossDecision decision, Trade trade, StopLossState state) {
        log.warn("Exit decision made",
                kv("decisionId", decision.getDecisionId()),
                kv("symbol", trade.getSymbol()),
                kv("shouldExit", decision.isShouldExit()),
                kv("exitReason", decision.getExitReason()),
                kv("triggerType", decision.getTriggerType()),
                kv("confidence", decision.getConfidence()),
                kv("recommendedPrice", decision.getRecommendedExitPrice()),
                kv("decisionFactors", decision.getDecisionFactors()),
                kv("riskMetrics", decision.getRiskMetrics()),
                kv("decisionPath", decision.getDecisionPath())
        );

        // Send alert notification
        String alertMessage = String.format(
                "🚨 STOP-LOSS TRIGGERED\n" +
                        "Symbol: %s\n" +
                        "Type: %s\n" +
                        "Reason: %s\n" +
                        "Price: $%s\n" +
                        "Confidence: %.2f",
                trade.getSymbol(),
                decision.getTriggerType(),
                decision.getExitReason(),
                decision.getRecommendedExitPrice(),
                decision.getConfidence()
        );

        try {
            telegramService.sendMessage(alertMessage);
        } catch (Exception e) {
            log.error("Failed to send exit alert", kv("error", e.getMessage()));
        }
    }

    private void logEvaluationCompletion(Trade trade, StopLossState state, StopLossDecision decision, long totalTime) {
        log.info("Stop-loss evaluation completed",
                kv("symbol", trade.getSymbol()),
                kv("totalTimeNanos", totalTime),
                kv("totalTimeMicros", totalTime / 1000.0),
                kv("shouldExit", decision.isShouldExit()),
                kv("finalStop", state.getCurrentStop()),
                kv("confidenceScore", state.getConfidenceScore()),
                kv("adjustmentReason", state.getAdjustmentReason()),
                kv("calculationMetrics", state.getCalculationMetrics().keySet()),
                kv("decisionFactorCount", decision.getDecisionFactors().size()),
                kv("decisionPathSteps", decision.getDecisionPath().size())
        );
    }

    private void handleEvaluationError(Trade trade, StopLossDecision decision, Exception error, long startTime) {
        long errorTime = System.nanoTime() - startTime;

        log.error("Stop-loss evaluation failed",
                kv("symbol", trade.getSymbol()),
                kv("error", error.getMessage()),
                kv("errorType", error.getClass().getSimpleName()),
                kv("evaluationTimeMicros", errorTime / 1000.0),
                kv("stackTrace", Arrays.toString(error.getStackTrace()).substring(0,
                        Math.min(500, Arrays.toString(error.getStackTrace()).length())))
        );

        decision.getDecisionFactors().put("error", error.getMessage());
        decision.getDecisionFactors().put("errorType", error.getClass().getSimpleName());
        decision.getDecisionPath().add("Error occurred: " + error.getMessage());
    }

    // Circuit breaker methods
    private CircuitBreakerState getCircuitBreakerState(String symbol) {
        return circuitBreakerStates.computeIfAbsent(symbol, k -> {
            CircuitBreakerState state = new CircuitBreakerState();
            state.setSymbol(symbol);
            state.setFailureCount(0);
            state.setSuccessCount(0);
            state.setOpen(false);
            return state;
        });
    }

    private void recordCircuitBreakerSuccess(String symbol) {
        CircuitBreakerState state = getCircuitBreakerState(symbol);
        state.setSuccessCount(state.getSuccessCount() + 1);

        // Reset failure count on success
        if (state.getFailureCount() > 0) {
            state.setFailureCount(0);
            state.setOpen(false);

            log.info("Circuit breaker reset after success",
                    kv("symbol", symbol),
                    kv("successCount", state.getSuccessCount())
            );
        }
    }

    private void recordCircuitBreakerFailure(String symbol, Exception error) {
        CircuitBreakerState state = getCircuitBreakerState(symbol);
        state.setFailureCount(state.getFailureCount() + 1);
        state.setLastFailureTime(LocalDateTime.now());
        state.setLastErrorType(error.getClass().getSimpleName());

        if (state.getFailureCount() >= config.getMaxCircuitBreakerFailures()) {
            state.setOpen(true);
            state.setNextAttemptTime(LocalDateTime.now().plusSeconds(
                    config.getCircuitBreakerTimeoutMs() / 1000));

            log.error("Circuit breaker opened",
                    kv("symbol", symbol),
                    kv("failureCount", state.getFailureCount()),
                    kv("nextAttemptTime", state.getNextAttemptTime()),
                    kv("lastError", error.getMessage())
            );
        }
    }

    // Additional helper methods for comprehensive analysis
    private double calculateCompletenessScore(Quote quote) {
        int availableFields = 0;
        int totalFields = 6; // bid, ask, last, volume, etc.

        if (quote.getBid() != null) availableFields++;
        if (quote.getAsk() != null) availableFields++;
        if (quote.getLast() != null) availableFields++;
        if (quote.getVolume() != null) availableFields++;
        if (quote.getHigh() != null) availableFields++;
        if (quote.getLow() != null) availableFields++;

        return (double) availableFields / totalFields;
    }

    private double validatePriceAccuracy(Quote quote) {
        // Simple price validation - more sophisticated validation would
        // compare against multiple sources
        if (quote.getBid() != null && quote.getAsk() != null && quote.getLast() != null) {
            if (quote.getBid().compareTo(quote.getAsk()) >= 0) return 0.0; // Invalid spread
            if (quote.getLast().compareTo(quote.getBid()) < 0 ||
                    quote.getLast().compareTo(quote.getAsk()) > 0) return 0.7; // Last outside spread
            return 1.0; // Valid prices
        }
        return 0.5; // Partial data
    }

    private double calculateFeedLatency(String symbol) {
        // In a real implementation, this would measure actual feed latency
        return Math.random() * 1000; // Simulated latency in microseconds
    }

    private int detectSequenceGaps(String symbol) {
        // In a real implementation, this would track sequence numbers
        return 0; // No gaps detected
    }

    private long calculateTimestampJitter(String symbol) {
        // In a real implementation, this would measure timestamp consistency
        return 0; // No jitter detected
    }

    private String assessDeltaRisk(double delta) {
        if (delta > 0.8) return "HIGH";
        if (delta > 0.5) return "MEDIUM";
        return "LOW";
    }

    private String assessGammaRisk(double gamma) {
        if (gamma > 0.05) return "HIGH";
        if (gamma > 0.02) return "MEDIUM";
        return "LOW";
    }

    private String assessLiquidityRisk(double spreadPercentage) {
        if (spreadPercentage > 0.20) return "HIGH";
        if (spreadPercentage > 0.10) return "MEDIUM";
        return "LOW";
    }

    private double calculateTrendSignalStrength(AdvancedSignalDetector.AdvancedSignalResult signals) {
        // Calculate signal strength based on number and quality of signals
        int signalCount = signals.getTrendlineBreakSignals().size() +
                signals.getGapFillSignals().size();
        return Math.min(1.0, signalCount * 0.2);
    }

    private String extractGapType(String strategy) {
        if (strategy.contains("UP")) return "GAP_UP";
        if (strategy.contains("DOWN")) return "GAP_DOWN";
        return "UNKNOWN_GAP";
    }

    private double calculateVolatilityAdjustmentFactor(double spikeMultiplier) {
        // More aggressive spikes require wider stops
        if (spikeMultiplier > 4.0) return 0.70; // Widen by 30%
        if (spikeMultiplier > 3.0) return 0.80; // Widen by 20%
        if (spikeMultiplier > 2.0) return 0.85; // Widen by 15%
        return 0.90; // Widen by 10%
    }

    private String categorizeSeverity(double ratio) {
        if (ratio > 4.0) return "EXTREME";
        if (ratio > 3.0) return "HIGH";
        if (ratio > 2.0) return "MEDIUM";
        return "LOW";
    }

    private double calculateOverallConfidence(StopLossState state) {
        // Calculate confidence based on available data and consistency
        int availableCalculations = 0;
        int totalCalculations = 5; // ATR, Greeks, Microstructure, Trendline, Gap

        if (state.getAtrBasedStop() != null) availableCalculations++;
        if (state.getGreeksBasedStop() != null) availableCalculations++;
        if (state.getMicrostructureStop() != null) availableCalculations++;
        if (state.getTrendlineStop() != null) availableCalculations++;
        if (state.getGapSpecificStop() != null) availableCalculations++;

        double baseConfidence = (double) availableCalculations / totalCalculations;

        // Adjust for consistency between methods
        // This is a simplified implementation
        return Math.max(0.1, Math.min(1.0, baseConfidence));
    }

    private String determineStopSource(StopLossState state, BigDecimal finalStop) {
        if (finalStop.equals(state.getAtrBasedStop())) return "ATR";
        if (finalStop.equals(state.getGreeksBasedStop())) return "GREEKS";
        if (finalStop.equals(state.getMicrostructureStop())) return "MICROSTRUCTURE";
        if (finalStop.equals(state.getTrendlineStop())) return "TRENDLINE";
        if (finalStop.equals(state.getGapSpecificStop())) return "GAP";
        return "ORIGINAL";
    }

    private String determineTriggerType(StopLossState state, BigDecimal finalStop) {
        if (state.isMicrostructureTriggered()) return "MICROSTRUCTURE";
        if (state.isGreeksAdjusted()) return "GREEKS";
        if (finalStop.equals(state.getTrendlineStop())) return "TRENDLINE";
        if (finalStop.equals(state.getGapSpecificStop())) return "GAP";
        return "ATR";
    }

    private void calculateRiskMetrics(Trade trade, StopLossState state, StopLossDecision decision) {
        BigDecimal entryPrice = trade.getEntryPrice();
        BigDecimal currentStop = state.getCurrentStop();

        if (entryPrice != null && currentStop != null) {
            BigDecimal riskAmount = entryPrice.subtract(currentStop);
            double riskPercent = riskAmount.divide(entryPrice, 4, RoundingMode.HALF_UP).doubleValue();

            decision.getRiskMetrics().put("riskAmount", riskAmount.doubleValue());
            decision.getRiskMetrics().put("riskPercent", riskPercent * 100);
            decision.getRiskMetrics().put("confidenceScore", state.getConfidenceScore());
            decision.getRiskMetrics().put("adjustmentCount", (double)countAdjustments(state));
        }
    }

    private int countAdjustments(StopLossState state) {
        int count = 0;
        if (state.isTrailingActivated()) count++;
        if (state.isVolatilityAdjusted()) count++;
        if (state.isGreeksAdjusted()) count++;
        if (state.isMicrostructureTriggered()) count++;
        return count;
    }

    private double calculateTrendStrength(TechnicalAnalysis ta) {
        // Simplified trend strength calculation
        // In practice, this would analyze multiple indicators
        return 0.75; // Placeholder
    }

    private double calculateTrendConsistency(TechnicalAnalysis ta) {
        // Simplified trend consistency calculation
        return 0.80; // Placeholder
    }

    private void handleMarketDataError(String symbol, String error) {
        log.error("Market data error",
                kv("symbol", symbol),
                kv("error", error),
                kv("timestamp", LocalDateTime.now())
        );
    }

    private double getHoursToExpiry() {
        LocalTime now = LocalTime.now();
        LocalTime marketClose = LocalTime.of(16, 0);

        if (now.isAfter(marketClose)) {
            return 0.0; // Already expired
        }

        return java.time.Duration.between(now, marketClose).toMinutes() / 60.0;
    }

    // Public methods for external access and manual adjustments
    public StopLossState getStopLossState(String tradeId) {
        return stopLossCache.get(tradeId);
    }

    public void adjustStopLoss(String tradeId, BigDecimal newStop, String reason) {
        StopLossState state = stopLossCache.get(tradeId);
        if (state != null) {
            BigDecimal previousStop = state.getCurrentStop();
            state.setCurrentStop(newStop);
            state.setAdjustmentReason("Manual adjustment: " + reason);
            state.setLastUpdate(LocalDateTime.now());

            log.warn("Manual stop-loss adjustment",
                    kv("tradeId", tradeId),
                    kv("previousStop", previousStop),
                    kv("newStop", newStop),
                    kv("reason", reason),
                    kv("adjustmentTime", LocalDateTime.now())
            );
        }
    }

    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        long totalEvals = totalEvaluations.sum();
        long totalLatency = totalLatencyNanos.sum();

        metrics.put("totalEvaluations", totalEvals);
        metrics.put("averageLatencyNanos", totalEvals > 0 ? totalLatency / totalEvals : 0);
        metrics.put("averageLatencyMicros", totalEvals > 0 ? (totalLatency / totalEvals) / 1000.0 : 0);
        metrics.put("maxLatencyNanos", maxLatencyNanos.get());
        metrics.put("maxLatencyMicros", maxLatencyNanos.get() / 1000.0);
        metrics.put("activePositions", stopLossCache.size());
        metrics.put("circuitBreakerStates", circuitBreakerStates.size());

        return metrics;
    }

    public void clearCache() {
        stopLossCache.clear();
        volatilitySpikeCache.clear();
        dataQualityCache.clear();
        log.info("Stop-loss monitor cache cleared");
    }
}