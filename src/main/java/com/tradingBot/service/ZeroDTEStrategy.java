package com.tradingBot.service;

import com.tradingBot.analytics.AdvancedUnusualFlowDetector;
import com.tradingBot.entity.*;
import com.tradingBot.model.*;
import com.tradingBot.repository.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZeroDTEStrategy {

    private final TradierService tradierService;
    private final TelegramService telegramService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final SignalRepository signalRepository;
    private final MarketDataRepository marketDataRepository;
    private final TradeRepository tradeRepository;
    private final SignalOrchestrator signalOrchestrator;
    private final PreTradeRiskEngine preTradeRiskEngine;
    private final IntegratedBayesianMLSystem integratedBayesianMLSystem;
    private final AdvancedSignalDetector advancedSignalDetector;
    private final SHAPExplainerService shapExplainerService;
    private final PriceLevelRiskService priceLevelRiskService;

    // AI Persistence Repositories
    private final AIStateRepository aiStateRepository;
    private final VolumeProfileRepository volumeProfileRepository;
    private final POCDataRepository pocDataRepository;
    private final SupportResistanceLevelsRepository supportResistanceLevelsRepository;
    private final VWAPDataRepository vwapDataRepository;

    @Value("${trading.min-volume:50}")
    private int minVolume;

    @Value("${trading.min-iv:0.15}")
    private double minIV;

    // Timezone
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    // Leader stocks for AI analysis
    private static final List<String> LEADER_STOCKS = Arrays.asList("AAPL", "MSFT", "NVDA");

    // Leader weights for direction validation
    private static final Map<String, Double> LEADER_WEIGHTS = new HashMap<String, Double>() {{
        put("NVDA", 0.5);  // Strongest correlation to QQQ
        put("MSFT", 0.3);
        put("AAPL", 0.2);
    }};

    // Updated strategy thresholds
    private static final double MIN_VOLUME_RATIO_BREAKOUT = 1.2;
    private static final double MIN_VOLUME_RATIO_STANDARD = 0.8;
    private static final LocalTime PRIME_WINDOW_1_START = LocalTime.of(9, 45);
    private static final LocalTime PRIME_WINDOW_1_END = LocalTime.of(10, 30);
    private static final LocalTime PRIME_WINDOW_2_START = LocalTime.of(10, 30);
    private static final LocalTime PRIME_WINDOW_2_END = LocalTime.of(12, 00);
    private static final LocalTime PRIME_WINDOW_3_START = LocalTime.of(12, 00);
    private static final LocalTime PRIME_WINDOW_3_END = LocalTime.of(15, 00);
    private static final LocalTime FINAL_WINDOW_START = LocalTime.of(15, 00);
    private static final LocalTime FINAL_WINDOW_END = LocalTime.of(15, 55);
    private static final LocalTime POWER_HOUR_START = LocalTime.of(15, 30);


    private final CorrelationDetector correlationDetector = new CorrelationDetector();
    private final LeaderDirectionValidator leaderValidator = new LeaderDirectionValidator();
    private final GreeksValidator greeksValidator = new GreeksValidator();

    // NEW: Enhanced AI Components with Momentum Velocity Tracking
    private final MomentumVelocityTracker momentumTracker = new MomentumVelocityTracker();
    private final MicroMomentumClassifier microMomentumClassifier = new MicroMomentumClassifier();
    private final RSIMomentumContextMatrix contextMatrix = new RSIMomentumContextMatrix();
    private final PreExecutionValidator preExecutionValidator = new PreExecutionValidator();


    // NEW: Scheduled momentum tracking updates every minute
    @Scheduled(fixedDelay = 60000) // Every 1 minute
    public void updateMomentumTracking() {
        try {
            momentumTracker.updateMomentumHistory();
        } catch (Exception e) {
            log.debug("Error updating momentum tracking: {}", e.getMessage());
        }
    }

    public List<Signal> analyzeOptions(String symbol, String marketTrend) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        //log.info("Trading ANALYSIS START ========== {}", analysisId);
        log.info("[{}] Market trend: {}", analysisId, marketTrend);

        List<Signal> rawSignals = new ArrayList<>();

        try {
            // PRE-TRADE MARKET CHECKS
            if (!preTradeRiskEngine.isMarketSuitable()) {
                log.warn("[{}] Market unsuitable for trading - aborting analysis", analysisId);
                return Collections.emptyList();
            }

            // Time check
            LocalTime now = LocalTime.now(ET_ZONE);
            boolean isLateSession = now.isAfter(LocalTime.of(15, 30));
            if (isLateSession) {
                log.warn("[{}] LATE SESSION MODE - Only 90%+ confidence signals allowed", analysisId);
            }

            // Block analysis in neutral market
            if ("NEUTRAL".equals(marketTrend)) {
                log.warn("[{}] MARKET IS NEUTRAL - NO SIGNALS WILL BE GENERATED", analysisId);
                return Collections.emptyList();
            }

            if (!isGoodTradingTime(now)) {
                log.warn("[{}] Not a good time for 0DTE trading: {}", analysisId, now);
                return Collections.emptyList();
            }

            // Data sufficiency check
            if (!hasDataSufficiency(symbol, analysisId)) {
                log.warn("[{}] Insufficient data for analysis - aborting", analysisId);
                return Collections.emptyList();
            }

            // Get technical analysis
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta == null) {
                log.error("[{}] Technical analysis failed", analysisId);
                return Collections.emptyList();
            }
            ta.setTrend(marketTrend);

            logTechnicalAnalysis(ta, analysisId);

            // Check market conditions
            MarketConditions marketConditions = getMarketConditions();
            if (!signalOrchestrator.shouldAnalyze(symbol, marketConditions)) {
                log.info("[{}] Orchestrator vetoed analysis - unfavorable conditions", analysisId);
                return Collections.emptyList();
            }

            // Check for today's expiration
            LocalDate today = LocalDate.now(ET_ZONE);
            List<LocalDate> expirations = tradierService.getExpirations(symbol);

            if (!expirations.contains(today)) {
                log.warn("[{}] No 0DTE options available today", analysisId);
                return Collections.emptyList();
            }

            // Get option chain
            OptionChainResponse chainResponse = tradierService.getOptionChain(symbol, today);

            if (chainResponse == null || !chainResponse.hasOptions()) {
                log.error("[{}] No option chain data available", analysisId);
                return Collections.emptyList();
            }

            List<Option> options = chainResponse.getOptionsList();
            //log.info("[{}] Retrieved {} options", analysisId, options.size());

            // Apply filtering based on market regime
            List<Option> filteredOptions = filterOptionsByMarketRegime(options, ta, analysisId);

            // PRE-TRADE STRIKE QUALIFICATION
            List<Option> qualifiedOptions = filteredOptions.stream()
                    .filter(option -> preTradeRiskEngine.isStrikeSuitable(option))
                    .collect(Collectors.toList());

            log.info("[{}] {} options passed pre-trade qualification (from {} filtered)",
                    analysisId, qualifiedOptions.size(), filteredOptions.size());

            // Apply dynamic strike selection based on volatility
            List<Option> selectedStrikes = selectStrikesBasedOnVolatility(qualifiedOptions, ta, analysisId);
            List<Option> aiStrikes = selectStrikesForAILeaderLag(qualifiedOptions, ta, analysisId);

            log.info("[{}] Analyzing {} selected strikes, {} AI strikes", analysisId, selectedStrikes.size(), aiStrikes.size());

            // PRIORITY 1: ENHANCED AI-POWERED LEADER-LAG STRATEGIES (HIGHEST PRIORITY)
            List<Signal> leaderLagSignals = analyzeEnhancedAILeaderLagStrategies(aiStrikes, ta, marketTrend, analysisId);
            rawSignals.addAll(leaderLagSignals);

            // NEW: Add enhanced debugging
            logEnhancedLeaderLagDebug(leaderLagSignals, analysisId);

            // PRIORITY 2: ADVANCED SIGNALS (with data sufficiency checks)
            AdvancedSignalDetector.AdvancedSignalResult advancedResult =
                    advancedSignalDetector.detectAdvancedSignals(symbol, marketTrend);

            if (advancedResult != null) {
                rawSignals.addAll(advancedResult.getTrendlineBreakSignals());
                rawSignals.addAll(advancedResult.getGapFillSignals());
                rawSignals.addAll(advancedResult.getHighLowBreachSignals());
            }

            log.info("[{}] Generated {} AI leader-lag + {} advanced signals",
                    analysisId, leaderLagSignals.size(),
                    advancedResult != null ? (advancedResult.getTrendlineBreakSignals().size() +
                            advancedResult.getGapFillSignals().size() +
                            advancedResult.getHighLowBreachSignals().size()) : 0);

            // PRIORITY 3: TRADITIONAL STRATEGIES (Only if no AI signals)
            if (leaderLagSignals.isEmpty()) {
                for (Option option : selectedStrikes) {
                    if (!quickSignalValidation(option, selectedStrikes, ta, marketTrend, analysisId)) {
                        continue;
                    }
                    analyzeOptionWithStrategies(option, ta, rawSignals, marketTrend, analysisId, selectedStrikes);
                }
            }

            log.info("[{}] Generated {} total raw signals", analysisId, rawSignals.size());

            // Apply Bayesian filtering to ALL signals
            List<Signal> processedSignals = new ArrayList<>();
            for (Signal signal : rawSignals) {
                IntegratedBayesianMLSystem.IntegratedAnalysisResult result =
                        integratedBayesianMLSystem.analyzeAndExecuteSignal(signal);

                if (result.getExecutionDecision().isShouldExecute()) {
                    signal.setConfidence(result.getBayesianAnalysis().getAdjustedConfidence());

                    // Store Bayesian analysis data in signal metadata
                    signal.getMetadata().put("bayesianConfidence", String.valueOf(result.getBayesianAnalysis().getAdjustedConfidence()));
                    signal.getMetadata().put("learningPhase", result.getLearningState().getLearningPhase());
                    signal.getMetadata().put("thresholdMethod", result.getDynamicThreshold().getOptimizationMethod());
                    signal.getMetadata().put("priorProbability", String.valueOf(result.getBayesianAnalysis().getPriorProbability()));
                    signal.getMetadata().put("likelihood", String.valueOf(result.getBayesianAnalysis().getLikelihood()));
                    signal.getMetadata().put("posteriorProbability", String.valueOf(result.getBayesianAnalysis().getPosteriorProbability()));

                    processedSignals.add(signal);

                    log.info("[BAYESIAN][{}] ✅ Signal APPROVED: {} - Will attempt execution",
                            analysisId, signal.getOptionSymbol());
                } else {
                    saveBayesianRejectedSignal(signal, result.getExecutionDecision(), analysisId);
                }
            }

            log.info("[{}] {} signals passed Bayesian filtering (from {} total)",
                    analysisId, processedSignals.size(), rawSignals.size());

            // Use orchestrator to process signals intelligently
            List<Signal> orchestratedSignals = signalOrchestrator.orchestrateSignals(
                    processedSignals, ta, analysisId);

            // Late session filter with AI priority
            if (isLateSession && !orchestratedSignals.isEmpty()) {
                orchestratedSignals = applyLateSessionFiltering(orchestratedSignals, analysisId);
            }

            // NEW: Enhanced signal execution with pre-execution validation
            saveAndExecuteSignalsWithValidation(orchestratedSignals, ta, marketTrend, analysisId);

            log.info("[{}] ========== ANALYSIS COMPLETE - {} SIGNALS ==========",
                    analysisId, orchestratedSignals.size());

            return orchestratedSignals;

        } catch (Exception e) {
            log.error("[{}] ERROR in analyzeOptions: {}", analysisId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // ================================================================================================
    // NEW: ENHANCED AI-POWERED LEADER-LAG ANALYSIS WITH MOMENTUM VELOCITY TRACKING
    // ================================================================================================

    private List<Signal> analyzeEnhancedAILeaderLagStrategies(List<Option> options, TechnicalAnalysis ta,
                                                              String marketTrend, String analysisId) {
        List<Signal> leaderLagSignals = new ArrayList<>();
        LocalTime now = LocalTime.now(ET_ZONE);
        String actualMarketTrend = ta.getTrend();

        try {
            // NEW: Update momentum tracking first
            momentumTracker.updateCurrentMomentum(analysisId);

            // Build enhanced market context with velocity data
            EnhancedMarketContext context = buildEnhancedMarketContextWithVelocity(ta, actualMarketTrend, now, analysisId);

            if (context == null) {
                log.warn("[ENHANCED-AI][{}] Could not build enhanced market context", analysisId);
                return leaderLagSignals;
            }

            // NEW: Classify micro-momentum patterns
            Map<String, MicroMomentumPattern> leaderPatterns = classifyLeaderMicroMomentumPatterns(context, analysisId);

            // NEW: Get RSI-momentum context for each leader
            Map<String, RSIMomentumContext> rsiContexts = getRSIMomentumContexts(context, analysisId);

            // Use enhanced AI to determine optimal strategy
            EnhancedAIDecision aiDecision = makeEnhancedAIDecision(context, leaderPatterns, rsiContexts, analysisId);

            if (aiDecision.getStrategy() == LeaderLagStrategy.NONE) {
                log.info("[ENHANCED-AI][{}] AI decided to skip: {}", analysisId, aiDecision.getReasoning());
                return leaderLagSignals;
            }

            // Get leader correlation metrics with time decay
            LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);

            if (metrics == null || !isCorrelationValid(metrics, analysisId)) {
                log.warn("[ENHANCED-AI][{}] Invalid correlation metrics", analysisId);
                return leaderLagSignals;
            }

            log.info("[ENHANCED-AI][{}] 🤖 AI SELECTED: {} (Confidence: {}%)",
                    analysisId, aiDecision.getStrategy(), (int)(aiDecision.getConfidence() * 100));
            log.info("[ENHANCED-AI][{}] 🧠 Pattern: {}, Context: {}",
                    analysisId, aiDecision.getDominantPattern(), aiDecision.getRsiContext());

            // Generate signals based on enhanced AI decision
            for (Option option : options) {
                if (!quickSignalValidation(option, options, ta, actualMarketTrend, analysisId)) {
                    continue;
                }

                EnhancedAILeaderLagSignal aiSignal = generateEnhancedAISignalWithVelocity(
                        option, aiDecision, ta, actualMarketTrend, metrics, context, analysisId);

                if (aiSignal != null && aiSignal.getConfidence() >= getEnhancedAIThreshold(aiDecision.getStrategy(), now, context)) {
                    // NEW: Store signal for pre-execution validation
                    Signal signal = createEnhancedAILeaderLagSignal(option, options, ta, aiSignal, actualMarketTrend, analysisId);
                    if (signal != null) {
                        // Mark for pre-execution validation
                        signal.getMetadata().put("requiresPreValidation", "true");
                        signal.getMetadata().put("validationContext", serializeValidationContext(context, aiDecision));

                        leaderLagSignals.add(signal);

                        log.info("[ENHANCED-AI][{}] ✅ {} Signal Generated - Confidence: {}%, Pattern: {}",
                                analysisId, aiDecision.getStrategy(), (int)(aiSignal.getConfidence() * 100),
                                aiDecision.getDominantPattern());
                    }
                }
            }

            return leaderLagSignals;

        } catch (Exception e) {
            log.error("[ENHANCED-AI][{}] Error in enhanced AI analysis: {}", analysisId, e.getMessage());
            return leaderLagSignals;
        }
    }

    // ================================================================================================
    // NEW: MOMENTUM VELOCITY TRACKER
    // ================================================================================================

    private class MomentumVelocityTracker {
        private final Map<String, List<MomentumReading>> momentumHistory = new ConcurrentHashMap<>();
        private final int MAX_HISTORY_SIZE = 10;

        public void initialize() {
            // Initialize momentum tracking for leader stocks
            for (String symbol : LEADER_STOCKS) {
                momentumHistory.put(symbol, new ArrayList<>());
            }
            momentumHistory.put("QQQ", new ArrayList<>());
        }

        public void cleanup() {
            momentumHistory.clear();
        }

        public void updateMomentumHistory() {
            try {
                for (String symbol : Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA")) {
                    TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
                    if (ta != null) {
                        addMomentumReading(symbol, ta.getMomentumStrength());
                    }
                }
            } catch (Exception e) {
                log.debug("Error updating momentum history: {}", e.getMessage());
            }
        }

        public void updateCurrentMomentum(String analysisId) {
            try {
                for (String symbol : Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA")) {
                    TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
                    if (ta != null) {
                        addMomentumReading(symbol, ta.getMomentumStrength());
                        log.debug("[MOMENTUM][{}] Updated {} momentum: {}", analysisId, symbol, ta.getMomentumStrength());
                    }
                }
            } catch (Exception e) {
                log.debug("[MOMENTUM][{}] Error updating current momentum: {}", analysisId, e.getMessage());
            }
        }

        private void addMomentumReading(String symbol, double momentum) {
            List<MomentumReading> history = momentumHistory.computeIfAbsent(symbol, k -> new ArrayList<>());

            MomentumReading reading = new MomentumReading(momentum, LocalDateTime.now());
            history.add(0, reading); // Add to front

            // Keep only recent readings
            if (history.size() > MAX_HISTORY_SIZE) {
                history.subList(MAX_HISTORY_SIZE, history.size()).clear();
            }
        }

        public MomentumVelocity calculateVelocity(String symbol) {
            List<MomentumReading> history = momentumHistory.get(symbol);
            if (history == null || history.size() < 3) {
                return new MomentumVelocity(0.0, 0.0, VelocityState.INSUFFICIENT_DATA);
            }

            double currentMomentum = history.get(0).momentum;
            double momentum3MinAgo = getMomentumAtTime(history, 3);
            double momentum5MinAgo = getMomentumAtTime(history, 5);

            // Calculate acceleration (momentum change over time)
            double acceleration3Min = momentum3MinAgo != 0 ? (currentMomentum - momentum3MinAgo) / 3.0 : 0;
            double acceleration5Min = momentum5MinAgo != 0 ? (currentMomentum - momentum5MinAgo) / 5.0 : 0;

            // Average acceleration for stability
            double avgAcceleration = (acceleration3Min + acceleration5Min) / 2.0;

            VelocityState state = classifyVelocityState(currentMomentum, avgAcceleration);

            return new MomentumVelocity(currentMomentum, avgAcceleration, state);
        }

        private double getMomentumAtTime(List<MomentumReading> history, int minutesAgo) {
            LocalDateTime targetTime = LocalDateTime.now().minusMinutes(minutesAgo);

            return history.stream()
                    .filter(r -> r.timestamp.isBefore(targetTime.plusMinutes(1)) &&
                            r.timestamp.isAfter(targetTime.minusMinutes(1)))
                    .mapToDouble(r -> r.momentum)
                    .findFirst()
                    .orElse(history.size() > minutesAgo ? history.get(minutesAgo).momentum : 0.0);
        }

        private VelocityState classifyVelocityState(double momentum, double acceleration) {
            if (momentum > 0.15 && acceleration > 0.005) return VelocityState.STRONG_BULLISH;
            if (momentum > 0.05 && acceleration > 0.002) return VelocityState.ACCELERATING_BULLISH;
            if (momentum > 0.005 && acceleration < -0.002) return VelocityState.WEAKENING_BULLISH;
            if (momentum < -0.15 && acceleration < -0.005) return VelocityState.STRONG_BEARISH;
            if (momentum < -0.05 && acceleration < -0.002) return VelocityState.ACCELERATING_BEARISH;
            if (momentum < -0.005 && acceleration > 0.002) return VelocityState.WEAKENING_BEARISH;
            if (Math.abs(momentum) < 0.02 && Math.abs(acceleration) < 0.001) return VelocityState.COILING;
            if (Math.abs(acceleration) > 0.003) return VelocityState.PIVOTING;
            return VelocityState.NOISE;
        }

        public List<MomentumReading> getMomentumHistory(String symbol) {
            return momentumHistory.getOrDefault(symbol, new ArrayList<>());
        }
    }

    // ================================================================================================
    // NEW: MICRO-MOMENTUM PATTERN CLASSIFIER
    // ================================================================================================

    private class MicroMomentumClassifier {

        public MicroMomentumPattern classifyPattern(String symbol, List<MomentumReading> history) {
            if (history.size() < 5) return MicroMomentumPattern.INSUFFICIENT_DATA;

            List<Double> recent5 = history.stream().limit(5).mapToDouble(r -> r.momentum).boxed().collect(Collectors.toList());

            if (isAccumulation(recent5)) return MicroMomentumPattern.ACCUMULATION;
            if (isDistribution(recent5)) return MicroMomentumPattern.DISTRIBUTION;
            if (isCoiling(recent5)) return MicroMomentumPattern.COILING;
            if (isPivoting(recent5)) return MicroMomentumPattern.PIVOT;

            return MicroMomentumPattern.NOISE;
        }

        private boolean isAccumulation(List<Double> momentum) {
            // Series of positive micro-momentums with higher lows
            boolean allPositive = momentum.stream().allMatch(m -> m > 0);
            if (!allPositive) return false;

            // Check for higher lows pattern
            int higherLows = 0;
            for (int i = 1; i < momentum.size(); i++) {
                if (momentum.get(i) > momentum.get(i-1)) higherLows++;
            }

            return higherLows >= 3;
        }

        private boolean isDistribution(List<Double> momentum) {
            // Series of negative micro-momentums with lower highs
            boolean allNegative = momentum.stream().allMatch(m -> m < 0);
            if (!allNegative) return false;

            // Check for lower highs pattern
            int lowerHighs = 0;
            for (int i = 1; i < momentum.size(); i++) {
                if (momentum.get(i) < momentum.get(i-1)) lowerHighs++;
            }

            return lowerHighs >= 3;
        }

        private boolean isCoiling(List<Double> momentum) {
            // Decreasing momentum range (compression before breakout)
            double range1 = Math.abs(momentum.get(0) - momentum.get(1));
            double range2 = Math.abs(momentum.get(1) - momentum.get(2));
            double range3 = Math.abs(momentum.get(2) - momentum.get(3));
            double range4 = Math.abs(momentum.get(3) - momentum.get(4));

            return range1 < range2 && range2 < range3 && range3 < range4 && range1 < 0.005;
        }

        private boolean isPivoting(List<Double> momentum) {
            // Sign change from negative to positive (or vice versa)
            boolean hadNegative = false;
            boolean hadPositive = false;

            for (Double m : momentum) {
                if (m > 0.002) hadPositive = true;
                if (m < -0.002) hadNegative = true;
            }

            return hadNegative && hadPositive;
        }
    }

    // ================================================================================================
    // NEW: RSI-MOMENTUM CONTEXT MATRIX
    // ================================================================================================

    private class RSIMomentumContextMatrix {

        public RSIMomentumContext evaluateContext(double rsi, MomentumVelocity velocity) {
            RSIZone rsiZone = classifyRSIZone(rsi);
            MomentumState momentumState = classifyMomentumState(velocity);

            ContextInterpretation interpretation = getInterpretation(rsiZone, momentumState, velocity.state);
            SignalAction signalAction = getSignalAction(interpretation);

            return new RSIMomentumContext(rsiZone, momentumState, velocity.state, interpretation, signalAction,
                    calculateContextConfidence(rsiZone, momentumState, velocity.state));
        }

        private RSIZone classifyRSIZone(double rsi) {
            if (rsi >= 65) return RSIZone.OVERBOUGHT;  // LOWERED from 70
            if (rsi >= 55) return RSIZone.UPPER_NEUTRAL;  // LOWERED from 60
            if (rsi >= 35) return RSIZone.NEUTRAL;  // LOWERED from 40
            if (rsi >= 25) return RSIZone.LOWER_NEUTRAL;  // LOWERED from 30
            return RSIZone.OVERSOLD;
        }

        private MomentumState classifyMomentumState(MomentumVelocity velocity) {
            if (velocity.momentum > 0.05) return MomentumState.POSITIVE;
            if (velocity.momentum > 0.005) return MomentumState.WEAK_POSITIVE;
            if (velocity.momentum < -0.05) return MomentumState.NEGATIVE;
            if (velocity.momentum < -0.005) return MomentumState.WEAK_NEGATIVE;
            return MomentumState.NEAR_ZERO;
        }

        private ContextInterpretation getInterpretation(RSIZone rsiZone, MomentumState momentumState, VelocityState velocityState) {
            // RELAXED: Lower thresholds for clearer signals

            // High RSI scenarios
            if (rsiZone == RSIZone.OVERBOUGHT) {
                if (momentumState == MomentumState.POSITIVE && velocityState == VelocityState.ACCELERATING_BULLISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                if (momentumState == MomentumState.POSITIVE && velocityState == VelocityState.WEAKENING_BULLISH) {
                    return ContextInterpretation.EXHAUSTION;
                }
                if (momentumState == MomentumState.NEAR_ZERO && velocityState == VelocityState.COILING) {
                    return ContextInterpretation.CONSOLIDATION;
                }
            }

            // Low RSI scenarios - RELAXED THRESHOLDS
            if (rsiZone == RSIZone.OVERSOLD) {
                if (momentumState == MomentumState.NEGATIVE && velocityState == VelocityState.ACCELERATING_BEARISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                if (momentumState == MomentumState.NEGATIVE && velocityState == VelocityState.WEAKENING_BEARISH) {
                    return ContextInterpretation.BOUNCE_SETUP;
                }
                // NEW: More scenarios for OVERSOLD
                if (momentumState == MomentumState.WEAK_NEGATIVE) {
                    return ContextInterpretation.BOUNCE_SETUP;
                }
            }

            // NEW: Lower neutral RSI zones
            if (rsiZone == RSIZone.LOWER_NEUTRAL) {
                if (velocityState == VelocityState.STRONG_BEARISH || velocityState == VelocityState.ACCELERATING_BEARISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                if (velocityState == VelocityState.WEAKENING_BEARISH) {
                    return ContextInterpretation.BOUNCE_SETUP;
                }
            }

            // Neutral RSI with strong momentum - RELAXED
            if (rsiZone == RSIZone.NEUTRAL || rsiZone == RSIZone.LOWER_NEUTRAL || rsiZone == RSIZone.UPPER_NEUTRAL) {
                if (velocityState == VelocityState.STRONG_BULLISH || velocityState == VelocityState.STRONG_BEARISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                if (velocityState == VelocityState.ACCELERATING_BULLISH || velocityState == VelocityState.ACCELERATING_BEARISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                // NEW: Allow PIVOT patterns
                if (velocityState == VelocityState.PIVOTING) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
            }

            return ContextInterpretation.UNCLEAR;
        }
        private SignalAction getSignalAction(ContextInterpretation interpretation) {
            switch (interpretation) {
                case TREND_CONTINUATION: return SignalAction.FOLLOW;
                case EXHAUSTION: return SignalAction.REVERSE;
                case BOUNCE_SETUP: return SignalAction.REVERSE;
                case CONSOLIDATION: return SignalAction.WAIT;
                case UNCLEAR: return SignalAction.FOLLOW; // CHANGED: Default to FOLLOW instead of SKIP
                default: return SignalAction.FOLLOW;
            }
        }

        private double calculateContextConfidence(RSIZone rsiZone, MomentumState momentumState, VelocityState velocityState) {
            double confidence = 0.5;

            // Clear velocity states get higher confidence
            if (velocityState == VelocityState.STRONG_BULLISH || velocityState == VelocityState.STRONG_BEARISH) {
                confidence += 0.25;
            } else if (velocityState == VelocityState.ACCELERATING_BULLISH || velocityState == VelocityState.ACCELERATING_BEARISH) {
                confidence += 0.20;
            }

            // Extreme RSI with momentum gets boost
            if ((rsiZone == RSIZone.OVERBOUGHT || rsiZone == RSIZone.OVERSOLD) &&
                    (momentumState == MomentumState.POSITIVE || momentumState == MomentumState.NEGATIVE)) {
                confidence += 0.15;
            }

            // Coiling pattern gets boost (compression before breakout)
            if (velocityState == VelocityState.COILING) {
                confidence += 0.10;
            }

            return Math.min(0.95, confidence);
        }
    }

    // ================================================================================================
    // NEW: PRE-EXECUTION VALIDATOR
    // ================================================================================================

    private class PreExecutionValidator {

        public static class ValidationResult {
            private final boolean valid;
            private final double confidenceMultiplier;
            private final String reason;

            public ValidationResult(boolean valid, double confidenceMultiplier, String reason) {
                this.valid = valid;
                this.confidenceMultiplier = confidenceMultiplier;
                this.reason = reason;
            }

            public boolean isValid() {
                return valid;
            }

            public double getConfidenceMultiplier() {
                return confidenceMultiplier;
            }

            public String getReason() {
                return reason;
            }
        }
        public ValidationResult validateBeforeExecution(Signal signal, String analysisId) {
            try {
                if (!"true".equals(signal.getMetadata().get("requiresPreValidation"))) {
                    return new ValidationResult(true, 1.0, "No pre-validation required");
                }

                log.info("[PRE-VALIDATION][{}] Starting 30-60 second validation for {}", analysisId, signal.getOptionSymbol());

                // Wait 30-60 seconds and re-check
                Thread.sleep(45000); // 45 seconds

                // Re-analyze momentum and correlation
                momentumTracker.updateCurrentMomentum(analysisId);

                // Check if leader monitoring is required
                if ("true".equals(signal.getMetadata().get("requiresLeaderMonitoring"))) {
                    ValidationResult leaderMonitorResult = monitorSpecificLeader(signal, analysisId);
                    if (!leaderMonitorResult.isValid()) {
                        return leaderMonitorResult; // Failed leader monitoring
                    }
                }

                // Get current leader state
                String leaderStock = signal.getMetadata().get("leaderStock");
                if (leaderStock == null) {
                    return new ValidationResult(false, 0.0, "No leader stock specified");
                }

                MomentumVelocity currentVelocity = momentumTracker.calculateVelocity(leaderStock);
                MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

                // Validation Rule 1: Check if momentum direction changed
                String originalDirection = signal.getMetadata().get("momentumDirection");
                String currentDirection = currentVelocity.momentum > 0 ? "BULLISH" : "BEARISH";

                if (!currentDirection.equals(originalDirection)) {
                    log.warn("[PRE-VALIDATION][{}] ❌ ABORT: Momentum direction changed {} -> {}",
                            analysisId, originalDirection, currentDirection);
                    return new ValidationResult(false, 0.0, "Momentum direction changed");
                }

                // Validation Rule 2: Check if momentum magnitude increased in signal direction
                double originalMagnitude = Double.parseDouble(signal.getMetadata().getOrDefault("momentumMagnitude", "0"));
                double currentMagnitude = Math.abs(currentVelocity.momentum);

                if (currentMagnitude >= originalMagnitude * 1.1) {
                    log.info("[PRE-VALIDATION][{}] ✅ EXECUTE: Momentum increased {} -> {}",
                            analysisId, String.format("%.3f",originalMagnitude), String.format("%.3f",currentMagnitude));
                    return new ValidationResult(true, 1.15, "Momentum increased in signal direction");
                }

                // Validation Rule 3: Check if correlation is still valid
                LeaderCorrelationMetrics currentCorrelation = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);
                if (currentCorrelation == null || currentCorrelation.getStrongestCorrelation() < 0.4) {
                    log.warn("[PRE-VALIDATION][{}] ❌ ABORT: Correlation broke ({}%)",
                            analysisId, currentCorrelation != null ? (int)(currentCorrelation.getStrongestCorrelation() * 100) : 0);
                    return new ValidationResult(false, 0.0, "Correlation breakdown");
                }

                // Validation Rule 4: Check if leader and QQQ are moving opposite
                boolean leaderBullish = currentVelocity.momentum > 0.01;
                boolean qqqBullish = qqqVelocity.momentum > 0.01;
                boolean leaderBearish = currentVelocity.momentum < -0.01;
                boolean qqqBearish = qqqVelocity.momentum < -0.01;

                if ((leaderBullish && qqqBearish) || (leaderBearish && qqqBullish)) {
                    log.warn("[PRE-VALIDATION][{}] ❌ ABORT: Leader-QQQ moving opposite: Leader={}, QQQ={}",
                            analysisId, String.format("%.3f",currentVelocity.momentum), String.format(".3f",qqqVelocity.momentum));
                    return new ValidationResult(false, 0.0, "Leader-QQQ divergence");
                }

                // Default: Allow execution with standard confidence
                log.info("[PRE-VALIDATION][{}] ✅ PROCEED: All validations passed", analysisId);
                return new ValidationResult(true, 1.0, "Validations passed");

            } catch (Exception e) {
                log.error("[PRE-VALIDATION][{}] Error in validation: {}", analysisId, e.getMessage());
                return new ValidationResult(false, 0.0, "Validation error: " + e.getMessage());
            }
        }

        private ValidationResult monitorSpecificLeader(Signal signal, String analysisId) {
            try {
                String opposingLeader = signal.getMetadata().get("opposingLeader");
                String isCallSignalStr = signal.getMetadata().get("isCallSignal");
                String monitoringDurationStr = signal.getMetadata().get("monitoringDuration");

                if (opposingLeader == null || isCallSignalStr == null || monitoringDurationStr == null) {
                    log.warn("[LEADER-MONITORING][{}] Missing monitoring metadata - skipping", analysisId);
                    return new ValidationResult(true, 1.0, "Monitoring metadata missing");
                }

                boolean isCallSignal = Boolean.parseBoolean(isCallSignalStr);
                int monitoringDuration = Integer.parseInt(monitoringDurationStr);

                log.info("[LEADER-MONITORING][{}] Monitoring {} for {} seconds...",
                        analysisId, opposingLeader, monitoringDuration);

                // Wait for the monitoring duration
                Thread.sleep(monitoringDuration * 1000);

                // Re-analyze the specific opposing leader
                List<MarketData> leaderData5min = marketDataRepository.findRecentData(opposingLeader, 5);
                double currentMomentum = calculateMomentum(leaderData5min);

                // Check if leader is still opposing
                boolean stillOpposing;
                double threshold = LocalTime.now(ET_ZONE).isAfter(LocalTime.of(15, 30)) ? 0.0010 : 0.0015;

                if (isCallSignal) {
                    // CALL signal - negative momentum opposes
                    stillOpposing = currentMomentum < -threshold;
                } else {
                    // PUT signal - positive momentum opposes
                    stillOpposing = currentMomentum > threshold;
                }

                if (stillOpposing) {
                    log.warn("[LEADER-MONITORING][{}] ❌ ABORT: {} still opposing after {} seconds (momentum: {})",
                            analysisId, opposingLeader, monitoringDuration,String.format("%.3f",currentMomentum));
                    return new ValidationResult(false, 0.0,
                            String.format("%s still opposing after monitoring", opposingLeader));
                }

                log.info("[LEADER-MONITORING][{}] ✅ PROCEED: {} turned favorable (momentum: {})",
                        analysisId, opposingLeader, String.format("%.3f",currentMomentum));
                return new ValidationResult(true, 1.05, "Leader monitoring passed - turned favorable");

            } catch (Exception e) {
                log.error("[LEADER-MONITORING][{}] Error in leader monitoring: {}", analysisId, e.getMessage());
                return new ValidationResult(false, 0.0, "Leader monitoring error: " + e.getMessage());
            }
        }

        private double calculateMomentum(List<MarketData> data) {
            if (data.isEmpty()) return 0.0;

            MarketData firstData = data.get(data.size() - 1);
            MarketData lastData = data.get(0);

            if (firstData.getPrice() == null || lastData.getPrice() == null ||
                    firstData.getPrice().compareTo(BigDecimal.ZERO) == 0) {
                return 0.0;
            }

            return lastData.getPrice().subtract(firstData.getPrice())
                    .divide(firstData.getPrice(), 6, RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }

    // ================================================================================================
    // NEW: ENHANCED SIGNAL EXECUTION WITH PRE-VALIDATION
    // ================================================================================================

    private void saveAndExecuteSignalsWithValidation(List<Signal> signals, TechnicalAnalysis ta,
                                                     String marketTrend, String analysisId) {
        for (Signal signal : signals) {
            // Final expiration check
            LocalDate today = LocalDate.now(ET_ZONE);
            LocalDate signalExpiration = extractExpirationFromOptionSymbol(signal.getOptionSymbol());

            if (!today.equals(signalExpiration)) {
                log.warn("[{}] ❌ Signal REJECTED in final check: {} - Not 0DTE",
                        analysisId, signal.getOptionSymbol());
                continue;
            }

            // SHAP explanation & validation
            SHAPExplainerService.SHAPExplanation explanation =
                    shapExplainerService.explainSignal(signal, ta, marketTrend);

            boolean shapValid = shapExplainerService.validateSignalWithExplanation(signal, ta, marketTrend);
            if (!shapValid) {
                log.warn("[SHAP-FILTER][{}] ❌ Signal REJECTED: {} - Weak explanation ({}%)",
                        analysisId, signal.getOptionSymbol(),
                        (int)(explanation.getExplanationStrength() * 100));
                continue;
            }

            // Set signal properties
            signal.setCreatedAt(LocalDateTime.now());
            signal.setEntryAssumptionPrice(ta.getCurrentPrice());
            signal.setMarketTrend(marketTrend);
            signal.setOriginalOptionPrice(signal.getEntryPrice());
            LocalDateTime expirationTime = LocalDateTime.now().plusSeconds(30);
            signal.setExpirationTime(expirationTime);

            log.info("[{}] SIGNAL PREPARED FOR EXECUTION: {} - {} {}, Confidence: {}%",
                    analysisId, signal.getStrategy(), signal.getSignalType(),
                    signal.getOptionSymbol(), (int)(signal.getConfidence() * 100));


            // NEW: Execute with pre-validation for AI signals
            if ("true".equals(signal.getMetadata().get("requiresPreValidation"))) {
                executeSignalWithPreValidation(signal, explanation, analysisId);
            } else {
                executeSignalImmediately(signal, explanation, analysisId);
            }
            
        }
    }

    private void executeSignalWithPreValidation(Signal signal, SHAPExplainerService.SHAPExplanation explanation, String analysisId) {
        try {
            // Run pre-execution validation
            PreExecutionValidator.ValidationResult validation = preExecutionValidator.validateBeforeExecution(signal, analysisId);

            if (!validation.isValid()) {
                log.warn("[PRE-VALIDATION][{}] ❌ Signal ABORTED: {} - Reason: {}",
                        analysisId, signal.getOptionSymbol(), validation.getReason());

                saveFailedTrade(signal, "PRE_VALIDATION_FAILED", validation.getReason(), analysisId);
                saveFailedSignal(signal, "PRE_VALIDATION_FAILED", validation.getReason());

                telegramService.sendMessage(String.format(
                        "🚫 AI SIGNAL ABORTED - PRE-VALIDATION\n" +
                                "Option: %s\n" +
                                "Reason: %s\n" +
                                "Status: Saved for analysis",
                        signal.getOptionSymbol(),
                        validation.getReason()
                ));
                return;
            }

            // Adjust confidence based on validation result
            signal.setConfidence(signal.getConfidence() * validation.getConfidenceMultiplier());
            signal.getMetadata().put("preValidationPassed", "true");
            signal.getMetadata().put("validationConfidence", String.valueOf(validation.getConfidenceMultiplier()));

            log.info("[PRE-VALIDATION][{}] ✅ Validation PASSED - Adjusted confidence: {}%",
                    analysisId, (int)(signal.getConfidence() * 100));

            // Proceed with normal execution
            executeSignalImmediately(signal, explanation, analysisId);

        } catch (Exception e) {
            log.error("[PRE-VALIDATION][{}] ❌ Exception during pre-validation: {}", analysisId, e.getMessage());
            saveFailedTrade(signal, "PRE_VALIDATION_ERROR", e.getMessage(), analysisId);
            saveFailedSignal(signal, "PRE_VALIDATION_ERROR", e.getMessage());
        }
    }

    // ================================================================================================
    // NEW: ENHANCED HELPER METHODS
    // ================================================================================================

    private EnhancedMarketContext buildEnhancedMarketContextWithVelocity(TechnicalAnalysis ta, String marketTrend,
                                                                         LocalTime now, String analysisId) {
        try {
            // Get velocity data for all leader stocks
            Map<String, MomentumVelocity> leaderVelocities = new HashMap<>();
            for (String leader : LEADER_STOCKS) {
                leaderVelocities.put(leader, momentumTracker.calculateVelocity(leader));
            }

            MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

            // Find strongest leader by correlation (not just momentum)
            LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);
            if (metrics == null) return null;

            String strongestLeader = metrics.getStrongestLeader();
            MomentumVelocity strongestLeaderVelocity = leaderVelocities.get(strongestLeader);

            if (strongestLeaderVelocity == null) return null;

            return new EnhancedMarketContext(
                    strongestLeader,
                    strongestLeaderVelocity,
                    qqqVelocity,
                    leaderVelocities,
                    metrics.getStrongestCorrelation(),
                    getVixLevel(),
                    marketTrend,
                    ta.getMarketRegime().toString(),
                    now.getHour() * 60 + now.getMinute(),
                    LocalDate.now().getDayOfWeek().getValue(),
                    ta.getVolumeRatio(),
                    ta.getRsi(),
                    ta.getCurrentPrice()
            );

        } catch (Exception e) {
            log.error("[ENHANCED-CONTEXT][{}] Error building enhanced context: {}", analysisId, e.getMessage());
            return null;
        }
    }

    private Map<String, MicroMomentumPattern> classifyLeaderMicroMomentumPatterns(EnhancedMarketContext context, String analysisId) {
        Map<String, MicroMomentumPattern> patterns = new HashMap<>();

        for (String leader : LEADER_STOCKS) {
            List<MomentumReading> history = momentumTracker.getMomentumHistory(leader);
            MicroMomentumPattern pattern = microMomentumClassifier.classifyPattern(leader, history);
            patterns.put(leader, pattern);

            log.debug("[MICRO-PATTERN][{}] {}: {}", analysisId, leader, pattern);
        }

        return patterns;
    }

    private Map<String, RSIMomentumContext> getRSIMomentumContexts(EnhancedMarketContext context, String analysisId) {
        Map<String, RSIMomentumContext> contexts = new HashMap<>();

        try {
            for (String leader : LEADER_STOCKS) {
                TechnicalAnalysis leaderTA = technicalAnalysisService.analyze(leader);
                if (leaderTA != null) {
                    MomentumVelocity velocity = context.leaderVelocities.get(leader);
                    RSIMomentumContext rsiContext = contextMatrix.evaluateContext(leaderTA.getRsi(), velocity);
                    contexts.put(leader, rsiContext);

                    log.debug("[RSI-CONTEXT][{}] {}: RSI={}, Action={}",
                            analysisId, leader, leaderTA.getRsi(), rsiContext.signalAction);
                }
            }
        } catch (Exception e) {
            log.debug("[RSI-CONTEXT][{}] Error getting RSI contexts: {}", analysisId, e.getMessage());
        }

        return contexts;
    }

    private EnhancedAIDecision makeEnhancedAIDecision(EnhancedMarketContext context,
                                                      Map<String, MicroMomentumPattern> leaderPatterns,
                                                      Map<String, RSIMomentumContext> rsiContexts,
                                                      String analysisId) {

        // Analyze the strongest leader
        String strongestLeader = context.strongestLeader;
        MicroMomentumPattern dominantPattern = leaderPatterns.get(strongestLeader);
        RSIMomentumContext dominantContext = rsiContexts.get(strongestLeader);

        if (dominantPattern == null || dominantContext == null) {
            return new EnhancedAIDecision(LeaderLagStrategy.NONE, 0.0, "Insufficient pattern/context data",
                    dominantPattern, dominantContext);
        }

        // Decision logic based on enhanced analysis
        LeaderLagStrategy strategy = LeaderLagStrategy.NONE;
        double confidence = 0.5;
        String reasoning = "";

        // Pattern-based decisions
        if (dominantPattern == MicroMomentumPattern.ACCUMULATION && dominantContext.signalAction == SignalAction.FOLLOW) {
            strategy = LeaderLagStrategy.STANDARD;
            confidence = 0.75;
            reasoning = "Accumulation pattern with follow signal";
        }
        else if (dominantPattern == MicroMomentumPattern.DISTRIBUTION && dominantContext.signalAction == SignalAction.FOLLOW) {
            strategy = LeaderLagStrategy.STANDARD;
            confidence = 0.75;
            reasoning = "Distribution pattern with follow signal";
        }
        else if (dominantPattern == MicroMomentumPattern.COILING && dominantContext.signalAction == SignalAction.WAIT) {
            strategy = LeaderLagStrategy.NONE;
            confidence = 0.0;
            reasoning = "Coiling pattern - wait for breakout";
        }
        else if (dominantContext.signalAction == SignalAction.REVERSE) {
            strategy = LeaderLagStrategy.REVERSE;
            confidence = 0.70;
            reasoning = "RSI-momentum context suggests reversal";
        }
        else if (dominantContext.signalAction == SignalAction.FOLLOW && dominantContext.contextConfidence > 0.7) {
            strategy = LeaderLagStrategy.STANDARD;
            confidence = dominantContext.contextConfidence;
            reasoning = "High-confidence follow signal";
        }

        // Boost confidence for velocity confirmation
        if (context.strongestLeaderVelocity.state == VelocityState.STRONG_BULLISH ||
                context.strongestLeaderVelocity.state == VelocityState.STRONG_BEARISH) {
            confidence *= 1.15;
            reasoning += " + strong velocity";
        }

        return new EnhancedAIDecision(strategy, Math.min(0.95, confidence), reasoning, dominantPattern, dominantContext);
    }

    private boolean isCorrelationValid(LeaderCorrelationMetrics metrics, String analysisId) {
        if (metrics.getStrongestCorrelation() < 0.4) {
            log.warn("[CORRELATION][{}] Correlation too low: {}%", analysisId, (int)(metrics.getStrongestCorrelation() * 100));
            return false;
        }

        // Check time decay - correlation should be recent
        LocalDateTime now = LocalDateTime.now();
        // Add time decay logic here if needed

        return true;
    }

    private EnhancedAILeaderLagSignal generateEnhancedAISignalWithVelocity(Option option, EnhancedAIDecision aiDecision,
                                                                           TechnicalAnalysis ta, String marketTrend,
                                                                           LeaderCorrelationMetrics metrics,
                                                                           EnhancedMarketContext context, String analysisId) {

        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean isPut = "PUT".equalsIgnoreCase(option.getType());

        String strongestLeader = context.strongestLeader;
        MomentumVelocity leaderVelocity = context.strongestLeaderVelocity;
        MomentumVelocity qqqVelocity = context.qqqVelocity;

        if (aiDecision.getStrategy() == LeaderLagStrategy.STANDARD) {
            // Enhanced standard strategy with velocity confirmation

            if (isCall && leaderVelocity.momentum > 0.01 && qqqVelocity.momentum < 0.05 &&
                    (leaderVelocity.state == VelocityState.ACCELERATING_BULLISH || leaderVelocity.state == VelocityState.STRONG_BULLISH) &&
                    ("UP".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createEnhancedStandardAISignal(option, context, metrics, "CALL", aiDecision, analysisId);
            }

            if (isPut && leaderVelocity.momentum < -0.01 && qqqVelocity.momentum > -0.05 &&
                    (leaderVelocity.state == VelocityState.ACCELERATING_BEARISH || leaderVelocity.state == VelocityState.STRONG_BEARISH) &&
                    ("DOWN".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createEnhancedStandardAISignal(option, context, metrics, "PUT", aiDecision, analysisId);
            }
        }
        else if (aiDecision.getStrategy() == LeaderLagStrategy.REVERSE) {
            // Enhanced reverse strategy with weakening detection

            if (isCall && leaderVelocity.state == VelocityState.WEAKENING_BULLISH &&
                    qqqVelocity.momentum > 0.01 && ("UP".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createEnhancedReverseAISignal(option, context, metrics, "CALL", aiDecision, analysisId);
            }

            if (isPut && leaderVelocity.state == VelocityState.WEAKENING_BEARISH &&
                    qqqVelocity.momentum < -0.01 && ("DOWN".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createEnhancedReverseAISignal(option, context, metrics, "PUT", aiDecision, analysisId);
            }
        }

        return null;
    }

    private EnhancedAILeaderLagSignal createEnhancedStandardAISignal(Option option, EnhancedMarketContext context,
                                                                     LeaderCorrelationMetrics metrics, String type,
                                                                     EnhancedAIDecision aiDecision, String analysisId) {

        double confidence = aiDecision.getConfidence();
        MomentumVelocity leaderVel = context.strongestLeaderVelocity;
        MomentumVelocity qqqVel = context.qqqVelocity;

        // Enhanced confidence calculation with velocity
        double momentumDivergence = Math.abs(leaderVel.momentum - qqqVel.momentum);
        double velocityStrength = Math.abs(leaderVel.acceleration);

        // Velocity-based confidence boosts
        if (leaderVel.state == VelocityState.STRONG_BULLISH || leaderVel.state == VelocityState.STRONG_BEARISH) {
            confidence *= 1.20; // Strong velocity
        } else if (leaderVel.state == VelocityState.ACCELERATING_BULLISH || leaderVel.state == VelocityState.ACCELERATING_BEARISH) {
            confidence *= 1.15; // Accelerating
        }

        // Divergence boost
        if (momentumDivergence > 0.05) confidence *= 1.10;

        // Correlation boost
        if (metrics.getStrongestCorrelation() > 0.7) confidence *= 1.08;

        // Volume boost
        if (context.volumeRatio > 1.5) confidence *= 1.05;

        String strategy = type.equals("CALL") ? "0DTE_ENHANCED_AI_LEADER_LAG_CALL" : "0DTE_ENHANCED_AI_LEADER_LAG_PUT";
        String reason = String.format(
                "🚀 Enhanced AI Standard: %s velocity=%s, momentum=%.3f vs QQQ=%.3f, acceleration=%.4f, pattern=%s",
                context.strongestLeader, leaderVel.state, leaderVel.momentum, qqqVel.momentum,
                leaderVel.acceleration, aiDecision.getDominantPattern());

        return new EnhancedAILeaderLagSignal(LeaderLagStrategy.STANDARD, "BUY", strategy,
                Math.min(0.95, confidence), metrics.getStrongestCorrelation(),
                Math.abs(leaderVel.momentum), reason, aiDecision.getRsiContext().toString(),
                context.strongestLeader, leaderVel, aiDecision.getDominantPattern());
    }

    private EnhancedAILeaderLagSignal createEnhancedReverseAISignal(Option option, EnhancedMarketContext context,
                                                                    LeaderCorrelationMetrics metrics, String type,
                                                                    EnhancedAIDecision aiDecision, String analysisId) {

        double confidence = aiDecision.getConfidence();
        MomentumVelocity leaderVel = context.strongestLeaderVelocity;
        MomentumVelocity qqqVel = context.qqqVelocity;

        // Enhanced confidence for weakening patterns
        if (leaderVel.state == VelocityState.WEAKENING_BULLISH || leaderVel.state == VelocityState.WEAKENING_BEARISH) {
            confidence *= 1.18;
        }

        // QQQ independence boost
        if (Math.abs(qqqVel.momentum) > 0.03) confidence *= 1.12;

        // Time-based boost for reverse strategies
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(15, 0))) confidence *= 1.10;

        String strategy = type.equals("CALL") ? "0DTE_ENHANCED_AI_REVERSE_LEADER_LAG_CALL" : "0DTE_ENHANCED_AI_REVERSE_LEADER_LAG_PUT";
        String reason = String.format(
                "🔄 Enhanced AI Reverse: %s weakening velocity=%s, momentum=%.3f->%.3f, QQQ continuing=%.3f, pattern=%s",
                context.strongestLeader, leaderVel.state, leaderVel.momentum, leaderVel.acceleration,
                qqqVel.momentum, aiDecision.getDominantPattern());

        return new EnhancedAILeaderLagSignal(LeaderLagStrategy.REVERSE, "BUY", strategy,
                Math.min(0.95, confidence), metrics.getStrongestCorrelation(),
                Math.abs(qqqVel.momentum), reason, aiDecision.getRsiContext().toString(),
                context.strongestLeader, leaderVel, aiDecision.getDominantPattern());
    }

    private Signal createEnhancedAILeaderLagSignal(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                                   EnhancedAILeaderLagSignal aiSignal, String marketTrend, String analysisId) {

        Signal signal = createSignal(option, allOptions, ta, aiSignal.getSignalType(),
                aiSignal.getStrategy(), aiSignal.getConfidence(), marketTrend);

        if (signal == null) return null;

        // Enhanced AI metadata with velocity data
        signal.getMetadata().put("aiStrategy", aiSignal.getLeaderLagStrategy().toString());
        signal.getMetadata().put("aiCorrelation", String.valueOf(aiSignal.getCorrelation()));
        signal.getMetadata().put("aiSignalStrength", String.valueOf(aiSignal.getSignalStrength()));
        signal.getMetadata().put("aiMarketRegime", aiSignal.getMarketRegime());
        signal.getMetadata().put("leaderStock", aiSignal.getLeaderStock());
        signal.getMetadata().put("priority", "HIGHEST");
        signal.getMetadata().put("signalSource", "ENHANCED_AI_LEARNING_ENGINE");

        // NEW: Velocity and pattern metadata
        signal.getMetadata().put("leaderVelocity", aiSignal.getLeaderVelocity().state.toString());
        signal.getMetadata().put("momentumAcceleration", String.valueOf(aiSignal.getLeaderVelocity().acceleration));
        signal.getMetadata().put("microPattern", aiSignal.getMicroPattern().toString());
        signal.getMetadata().put("momentumDirection", aiSignal.getLeaderVelocity().momentum > 0 ? "BULLISH" : "BEARISH");
        signal.getMetadata().put("momentumMagnitude", String.valueOf(Math.abs(aiSignal.getLeaderVelocity().momentum)));

        // Store option Greeks
        if (option.getDelta() != null) signal.setDelta(option.getDelta());
        if (option.getGamma() != null) signal.setGamma(option.getGamma());
        if (option.getTheta() != null) signal.setTheta(option.getTheta());
        if (option.getVega() != null) signal.setVega(option.getVega());

        // Enhanced risk management with velocity consideration
        signal.setTargetPrice(calculateEnhancedAITarget(signal.getEntryPrice(), aiSignal, ta));
        signal.setStopLoss(calculateEnhancedAIStop(signal.getEntryPrice(), aiSignal, ta));

        signal.setReason(aiSignal.getReason());

        return signal;
    }

    private BigDecimal calculateEnhancedAITarget(BigDecimal entryPrice, EnhancedAILeaderLagSignal aiSignal, TechnicalAnalysis ta) {
        double baseMultiplier = aiSignal.getLeaderLagStrategy() == LeaderLagStrategy.STANDARD ? 2.8 : 2.3;

        // Velocity-based adjustment
        VelocityState velocityState = aiSignal.getLeaderVelocity().state;
        if (velocityState == VelocityState.STRONG_BULLISH || velocityState == VelocityState.STRONG_BEARISH) {
            baseMultiplier *= 1.20; // Strong velocity gets bigger targets
        } else if (velocityState == VelocityState.ACCELERATING_BULLISH || velocityState == VelocityState.ACCELERATING_BEARISH) {
            baseMultiplier *= 1.15; // Accelerating momentum
        }

        // Pattern-based adjustment
        if (aiSignal.getMicroPattern() == MicroMomentumPattern.ACCUMULATION ||
                aiSignal.getMicroPattern() == MicroMomentumPattern.DISTRIBUTION) {
            baseMultiplier *= 1.10; // Clear patterns get boost
        }

        // Time decay for 0DTE
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(15, 0))) {
            baseMultiplier *= 0.80; // Reduce targets in final hour
        }

        return entryPrice.multiply(BigDecimal.valueOf(baseMultiplier)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateEnhancedAIStop(BigDecimal entryPrice, EnhancedAILeaderLagSignal aiSignal, TechnicalAnalysis ta) {
        double baseStopPercentage = aiSignal.getLeaderLagStrategy() == LeaderLagStrategy.STANDARD ? 0.25 : 0.20;

        // Tighter stops for high-velocity signals (they should move quickly)
        VelocityState velocityState = aiSignal.getLeaderVelocity().state;
        if (velocityState == VelocityState.STRONG_BULLISH || velocityState == VelocityState.STRONG_BEARISH) {
            baseStopPercentage *= 0.85;
        }

        // Confidence-based adjustment
        if (aiSignal.getConfidence() > 0.9) {
            baseStopPercentage *= 0.90;
        }

        return entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(baseStopPercentage)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private double getEnhancedAIThreshold(LeaderLagStrategy strategy, LocalTime now, EnhancedMarketContext context) {
        boolean isPowerHour = now.isAfter(POWER_HOUR_START);

        // Base thresholds
        double threshold = switch (strategy) {
            case STANDARD -> isPowerHour ? 0.70 : 0.72;
            case REVERSE -> isPowerHour ? 0.65 : 0.75;
            default -> 0.85;
        };

        // Velocity-based threshold reduction
        if (context.strongestLeaderVelocity.state == VelocityState.STRONG_BULLISH ||
                context.strongestLeaderVelocity.state == VelocityState.STRONG_BEARISH) {
            threshold -= 0.05; // Lower threshold for strong velocity
        }

        return threshold;
    }

    private String serializeValidationContext(EnhancedMarketContext context, EnhancedAIDecision decision) {
        return String.format("{\"leader\":\"%s\",\"velocity\":\"%s\",\"pattern\":\"%s\",\"momentum\":%.3f}",
                context.strongestLeader, context.strongestLeaderVelocity.state,
                decision.getDominantPattern(), context.strongestLeaderVelocity.momentum);
    }

    // ================================================================================================
    // NEW: ENHANCED CORRELATION DETECTOR WITH TIME DECAY
    // ================================================================================================

    private class CorrelationDetector {
        private final Map<String, List<PricePoint>> priceHistory = new ConcurrentHashMap<>();
        private LeaderCorrelationMetrics lastMetrics;

        public LeaderCorrelationMetrics getLeaderMetrics(String analysisId) {
            return getLeaderMetricsWithTimeDecay(analysisId);
        }

        public LeaderCorrelationMetrics getLeaderMetricsWithTimeDecay(String analysisId) {
            try {
                List<PricePoint> qqqPrices = getRecentPrices("QQQ", 15, analysisId);
                if (qqqPrices.size() < 10) {
                    log.warn("[CORRELATION][{}] Insufficient QQQ data: {} points", analysisId, qqqPrices.size());
                    return lastMetrics;
                }

                String strongestLeader = null;
                double strongestCorrelation = 0.0;
                Map<String, Double> leaderCorrelations = new HashMap<>();

                for (String leaderSymbol : LEADER_STOCKS) {
                    List<PricePoint> leaderPrices = getRecentPrices(leaderSymbol, 15, analysisId);

                    if (leaderPrices.size() >= 10) {
                        double correlation = calculateCorrelationWithTimeDecay(leaderPrices, qqqPrices);
                        leaderCorrelations.put(leaderSymbol, correlation);

                        if (correlation > strongestCorrelation) {
                            strongestCorrelation = correlation;
                            strongestLeader = leaderSymbol;
                        }
                    }
                }

                if (strongestLeader == null) {
                    log.warn("[CORRELATION][{}] No valid leader correlations found", analysisId);
                    return lastMetrics;
                }

                lastMetrics = new LeaderCorrelationMetrics(strongestLeader, strongestCorrelation, leaderCorrelations);
                return lastMetrics;

            } catch (Exception e) {
                log.error("[CORRELATION][{}] Error calculating leader metrics: {}", analysisId, e.getMessage());
                return lastMetrics;
            }
        }

        public double getLeaderQQQCorrelation(String leaderSymbol) {
            try {
                List<PricePoint> leaderPrices = getRecentPrices(leaderSymbol, 15, "correlation-check");
                List<PricePoint> qqqPrices = getRecentPrices("QQQ", 15, "correlation-check");

                if (leaderPrices.size() >= 5 && qqqPrices.size() >= 5) {
                    return calculateCorrelationWithTimeDecay(leaderPrices, qqqPrices);
                }
                return 0.5;

            } catch (Exception e) {
                log.debug("Error calculating correlation for {}: {}", leaderSymbol, e.getMessage());
                return 0.5;
            }
        }

        private double calculateCorrelationWithTimeDecay(List<PricePoint> prices1, List<PricePoint> prices2) {
            double baseCorrelation = calculateCorrelation(prices1, prices2);

            // Apply time decay - more recent data has higher weight
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime mostRecentPrice = prices1.get(0).timestamp;

            long minutesAge = Duration.between(mostRecentPrice, now).toMinutes();
            double timeDecayFactor = getTimeDecayFactor(minutesAge);

            return baseCorrelation * timeDecayFactor;
        }

        private double getTimeDecayFactor(long minutesAge) {
            if (minutesAge <= 2) return 1.0;      // 100% confidence
            if (minutesAge <= 5) return 0.85;     // 85% confidence
            if (minutesAge <= 10) return 0.60;    // 60% confidence
            return 0.30;                          // 30% confidence (mostly expired)
        }

        private List<PricePoint> getRecentPrices(String symbol, int periods, String analysisId) {
            List<PricePoint> prices = new ArrayList<>();
            try {
                List<MarketData> marketData = marketDataRepository.findRecentData(symbol, periods);

                for (MarketData data : marketData) {
                    if (data.getPrice() != null && data.getTimestamp() != null) {
                        prices.add(new PricePoint(data.getPrice().doubleValue(), data.getTimestamp()));
                    }
                }

                prices.sort((a, b) -> b.timestamp.compareTo(a.timestamp));

            } catch (Exception e) {
                log.debug("[PRICE-DATA][{}] Error getting prices for {}: {}", analysisId, symbol, e.getMessage());
            }
            return prices;
        }

        public double calculateCorrelation(List<PricePoint> prices1, List<PricePoint> prices2) {
            int n = Math.min(prices1.size(), prices2.size());
            if (n < 3) return 0.5;

            double sum1 = prices1.stream().limit(n).mapToDouble(p -> p.price).sum();
            double sum2 = prices2.stream().limit(n).mapToDouble(p -> p.price).sum();
            double sum1Sq = prices1.stream().limit(n).mapToDouble(p -> p.price * p.price).sum();
            double sum2Sq = prices2.stream().limit(n).mapToDouble(p -> p.price * p.price).sum();
            double sum1Sum2 = 0;

            for (int i = 0; i < n; i++) {
                sum1Sum2 += prices1.get(i).price * prices2.get(i).price;
            }

            double numerator = n * sum1Sum2 - sum1 * sum2;
            double denominator = Math.sqrt((n * sum1Sq - sum1 * sum1) * (n * sum2Sq - sum2 * sum2));

            return denominator != 0 ? Math.abs(numerator / denominator) : 0.5;
        }
    }

    // ================================================================================================
    // NEW: ENHANCED DATA STRUCTURES
    // ================================================================================================

    @Getter @Setter
    private static class MomentumReading {
        final double momentum;
        final LocalDateTime timestamp;

        public MomentumReading(double momentum, LocalDateTime timestamp) {
            this.momentum = momentum;
            this.timestamp = timestamp;
        }
    }

    @Getter @Setter
    private static class MomentumVelocity {
        final double momentum;
        final double acceleration;
        final VelocityState state;

        public MomentumVelocity(double momentum, double acceleration, VelocityState state) {
            this.momentum = momentum;
            this.acceleration = acceleration;
            this.state = state;
        }
    }

    private enum VelocityState {
        STRONG_BULLISH, ACCELERATING_BULLISH, WEAKENING_BULLISH,
        STRONG_BEARISH, ACCELERATING_BEARISH, WEAKENING_BEARISH,
        COILING, PIVOTING, NOISE, INSUFFICIENT_DATA
    }

    private enum MicroMomentumPattern {
        ACCUMULATION, DISTRIBUTION, COILING, PIVOT, NOISE, INSUFFICIENT_DATA
    }

    private enum RSIZone {
        OVERBOUGHT, UPPER_NEUTRAL, NEUTRAL, LOWER_NEUTRAL, OVERSOLD
    }

    private enum MomentumState {
        POSITIVE, WEAK_POSITIVE, NEAR_ZERO, WEAK_NEGATIVE, NEGATIVE
    }

    private enum ContextInterpretation {
        TREND_CONTINUATION, EXHAUSTION, CONSOLIDATION, BOUNCE_SETUP, UNCLEAR
    }

    private enum SignalAction {
        FOLLOW, REVERSE, WAIT, SKIP
    }

    @Getter @Setter
    private static class RSIMomentumContext {
        final RSIZone rsiZone;
        final MomentumState momentumState;
        final VelocityState velocityState;
        final ContextInterpretation interpretation;
        final SignalAction signalAction;
        final double contextConfidence;

        public RSIMomentumContext(RSIZone rsiZone, MomentumState momentumState, VelocityState velocityState,
                                  ContextInterpretation interpretation, SignalAction signalAction, double contextConfidence) {
            this.rsiZone = rsiZone;
            this.momentumState = momentumState;
            this.velocityState = velocityState;
            this.interpretation = interpretation;
            this.signalAction = signalAction;
            this.contextConfidence = contextConfidence;
        }
    }

    @Getter @Setter
    private static class EnhancedMarketContext {
        final String strongestLeader;
        final MomentumVelocity strongestLeaderVelocity;
        final MomentumVelocity qqqVelocity;
        final Map<String, MomentumVelocity> leaderVelocities;
        final double correlation, vixLevel, volumeRatio, rsi;
        final String marketTrend, volatilityRegime;
        final int timeOfDay, dayOfWeek;
        final BigDecimal currentPrice;

        public EnhancedMarketContext(String strongestLeader, MomentumVelocity strongestLeaderVelocity,
                                     MomentumVelocity qqqVelocity, Map<String, MomentumVelocity> leaderVelocities,
                                     double correlation, double vixLevel, String marketTrend, String volatilityRegime,
                                     int timeOfDay, int dayOfWeek, double volumeRatio, double rsi, BigDecimal currentPrice) {
            this.strongestLeader = strongestLeader;
            this.strongestLeaderVelocity = strongestLeaderVelocity;
            this.qqqVelocity = qqqVelocity;
            this.leaderVelocities = leaderVelocities;
            this.correlation = correlation;
            this.vixLevel = vixLevel;
            this.marketTrend = marketTrend;
            this.volatilityRegime = volatilityRegime;
            this.timeOfDay = timeOfDay;
            this.dayOfWeek = dayOfWeek;
            this.volumeRatio = volumeRatio;
            this.rsi = rsi;
            this.currentPrice = currentPrice;
        }
    }

    @Getter @Setter
    private static class EnhancedAIDecision {
        final LeaderLagStrategy strategy;
        final double confidence;
        final String reasoning;
        final MicroMomentumPattern dominantPattern;
        final RSIMomentumContext rsiContext;

        public EnhancedAIDecision(LeaderLagStrategy strategy, double confidence, String reasoning,
                                  MicroMomentumPattern dominantPattern, RSIMomentumContext rsiContext) {
            this.strategy = strategy;
            this.confidence = confidence;
            this.reasoning = reasoning;
            this.dominantPattern = dominantPattern;
            this.rsiContext = rsiContext;
        }
    }

    @Getter @Setter
    private static class EnhancedAILeaderLagSignal {
        final LeaderLagStrategy leaderLagStrategy;
        final String signalType, strategy, reason, marketRegime, leaderStock;
        final double confidence, correlation, signalStrength;
        final MomentumVelocity leaderVelocity;
        final MicroMomentumPattern microPattern;

        public EnhancedAILeaderLagSignal(LeaderLagStrategy leaderLagStrategy, String signalType, String strategy,
                                         double confidence, double correlation, double signalStrength,
                                         String reason, String marketRegime, String leaderStock,
                                         MomentumVelocity leaderVelocity, MicroMomentumPattern microPattern) {
            this.leaderLagStrategy = leaderLagStrategy;
            this.signalType = signalType;
            this.strategy = strategy;
            this.confidence = confidence;
            this.correlation = correlation;
            this.signalStrength = signalStrength;
            this.reason = reason;
            this.marketRegime = marketRegime;
            this.leaderStock = leaderStock;
            this.leaderVelocity = leaderVelocity;
            this.microPattern = microPattern;
        }
    }

    private static class ValidationResult {
        private final boolean valid;
        private final double confidenceMultiplier;
        private final String reason;

        public ValidationResult(boolean valid, double confidenceMultiplier, String reason) {
            this.valid = valid;
            this.confidenceMultiplier = confidenceMultiplier;
            this.reason = reason;
        }

        public boolean isValid() {
            return valid;
        }

        public double getConfidenceMultiplier() {
            return confidenceMultiplier;
        }

        public String getReason() {
            return reason;
        }
    }

    // ================================================================================================
    // KEEP ALL EXISTING METHODS UNCHANGED (Traditional strategies, etc.)
    // ================================================================================================

    private List<Option> selectStrikesForAILeaderLag(List<Option> options, TechnicalAnalysis ta, String analysisId) {
        BigDecimal currentPrice = ta.getCurrentPrice();

        return options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    BigDecimal distance = strike.subtract(currentPrice).abs();
                    return distance.compareTo(BigDecimal.valueOf(3.0)) <= 0;
                })
                .collect(Collectors.toList());
    }

    public List<Signal> analyzeOptions(String symbol) {
        return analyzeOptions(symbol, "NEUTRAL");
    }

    private boolean hasDataSufficiency(String symbol, String analysisId) {
        try {
            LocalDateTime twoHoursAgo = LocalDateTime.now(ET_ZONE).minusHours(2);
            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 120);

            if (recentData.size() < 120) {
                log.warn("[DATA-CHECK][{}] Insufficient market data: {} minutes (need 120)", analysisId, recentData.size());
                return false;
            }

            LocalDateTime marketOpen = LocalDateTime.now(ET_ZONE).with(LocalTime.of(9, 30));
            LocalDateTime now = LocalDateTime.now(ET_ZONE);

            if (now.isAfter(marketOpen.plusHours(2))) {
                long expectedMinutes = Duration.between(marketOpen, now).toMinutes();
                long actualDataPoints = recentData.stream()
                        .filter(data -> data.getTimestamp().isAfter(marketOpen))
                        .count();

                double dataCompleteness = (double) actualDataPoints / expectedMinutes;

                if (dataCompleteness < 0.80) {
                    log.warn("[DATA-CHECK][{}] Data completeness {}% < 80% threshold", analysisId, (int)(dataCompleteness * 100));
                    return false;
                }
            }

            for (String leaderSymbol : LEADER_STOCKS) {
                List<MarketData> leaderData = marketDataRepository.findRecentData(leaderSymbol, 30);
                if (leaderData.size() < 30) {
                    log.warn("[DATA-CHECK][{}] Insufficient {} data: {} minutes (need 30)", analysisId, leaderSymbol, leaderData.size());
                    return false;
                }
            }

            log.info("[DATA-CHECK][{}] Data sufficiency ✅ - {} minutes of data available", analysisId, recentData.size());
            return true;

        } catch (Exception e) {
            log.error("[DATA-CHECK][{}] Error checking data sufficiency: {}", analysisId, e.getMessage());
            return false;
        }
    }

    private List<Signal> applyLateSessionFiltering(List<Signal> signals, String analysisId) {
        List<Signal> filtered = signals.stream()
                .filter(s -> {
                    String priority = s.getMetadata().get("priority");
                    String source = s.getMetadata().get("signalSource");

                    if ("HIGHEST".equals(priority) && ("AI_LEARNING_ENGINE".equals(source) || "ENHANCED_AI_LEARNING_ENGINE".equals(source))) {
                        return s.getConfidence() >= 0.75;
                    }

                    if (s.getStrategy().contains("TRENDLINE_BREAK") ||
                            s.getStrategy().contains("GAP_FILL") ||
                            s.getStrategy().contains("HIGH_BREACH") ||
                            s.getStrategy().contains("LOW_BREACH")) {
                        return s.getConfidence() >= 0.95;
                    }

                    return s.getConfidence() >= 0.90;
                })
                .collect(Collectors.toList());

        log.info("[LATE-SESSION][{}] Late session filter: {} signals -> {} (AI-priority-aware filtering)",
                analysisId, signals.size(), filtered.size());

        return filtered;
    }

    private void analyzeOptionWithStrategies(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend,
                                             String analysisId, List<Option> allOptions) {

        LocalTime now = LocalTime.now(ET_ZONE);

        if (now.isAfter(LocalTime.of(9, 45))) {
            analyzeOpeningRangeBreakout(option, allOptions, ta, signals, marketTrend, analysisId);
        }

        if (ta.getVwap() != null && ta.getVwap().compareTo(BigDecimal.ZERO) > 0) {
//            if (ta.isVwapBreakout() && ta.getVolumeRatio() > 1.5) {
//                analyzeVWAPBreakout(option, allOptions, ta, signals, marketTrend, analysisId);
//            }
            if (ta.isExtendedFromVwap() && (ta.getRsi() > 70 || ta.getRsi() < 30)) {
                analyzeVWAPDeviationReversion(option, allOptions, ta, signals, marketTrend, analysisId);
            }
            else if (ta.isVwapAsSupport() || ta.isVwapAsResistance()) {
                analyzeVWAPSupportResistance(option, allOptions, ta, signals, marketTrend, analysisId);
            }
            else if (ta.getVolumeRatio() > 0.8 && !ta.isExtendedFromVwap()) {
                analyzeVWAPReclaim(option, allOptions, ta, signals, marketTrend, analysisId);
            }
            else if (Math.abs(ta.getPriceToVwapRatio() - 1.0) < 0.002) {
                analyzeDirectionalVWAPBounce(option, allOptions, ta, signals, marketTrend, analysisId);
            }
            else if (Math.abs(ta.getPriceToVwapRatio() - 1.0) < 0.005) {
                analyzeVWAPBounce(option, allOptions, ta, signals, marketTrend, analysisId);
            }
        }

        VWAPPattern currentPattern = detectMultiCandleVWAPPattern(
                marketDataRepository.findRecentData(ta.getSymbol(), 10), ta);

        if (currentPattern != VWAPPattern.NONE) {
            log.info("[{}] Multi-candle pattern detected: {}", analysisId, currentPattern);

            signals.stream()
                    .filter(s -> s.getStrategy().contains("VWAP"))
                    .forEach(signal -> {
                        double boost = getPatternConfidenceBoost(currentPattern);
                        signal.setConfidence(Math.min(0.95, signal.getConfidence() * boost));
                        signal.setReason(signal.getReason() + " [Pattern: " + currentPattern + "]");
                    });
        }
    }

    private double getPatternConfidenceBoost(VWAPPattern pattern) {
        switch (pattern) {
            case COMPRESSION:
                return 1.15;
            case FAILED_BREAKDOWN:
                return 1.20;
            case STAIR_STEP:
                return 1.10;
            default:
                return 1.0;
        }
    }

    private void executeSignalImmediately(Signal signal, SHAPExplainerService.SHAPExplanation explanation, String analysisId) {
        try {
            log.info("[EXECUTION][{}] Attempting BUY_TO_OPEN execution: {}", analysisId, signal.getOptionSymbol());

            QuoteResponse quote = tradierService.getQuote(signal.getOptionSymbol());
            if (quote == null || quote.getQuote() == null) {
                log.error("[EXECUTION][{}] ❌ Cannot get quote - saving as QUOTE_FAILED", analysisId);
                saveFailedTrade(signal, "QUOTE_FAILED", "Unable to get option quote", analysisId);
                saveFailedSignal(signal, "QUOTE_FAILED", "Unable to get option quote");
                return;
            }

            BigDecimal currentPrice = quote.getQuote().getLast();
            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                currentPrice = quote.getQuote().getMidPrice();
                if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    log.error("[EXECUTION][{}] ❌ Invalid option price - saving as PRICE_INVALID", analysisId);
                    saveFailedTrade(signal, "PRICE_INVALID", "Option price is null or zero", analysisId);
                    saveFailedSignal(signal, "PRICE_INVALID", "Option price is null or zero");
                    return;
                }
            }

            Quote freshQuote = quote.getQuote();
            if (freshQuote.getGreeks() != null) {
                OptionGreeks greeks = freshQuote.getGreeks();

                if (greeks.getDelta() != null) {
                    signal.setDelta(greeks.getDelta().doubleValue());
                }
                if (greeks.getGamma() != null) {
                    signal.setGamma(greeks.getGamma().doubleValue());
                }
                if (greeks.getTheta() != null) {
                    signal.setTheta(greeks.getTheta());
                }
                if (greeks.getVega() != null) {
                    signal.setVega(greeks.getVega());
                }
                if (greeks.getRho() != null) {
                    signal.setRho(greeks.getRho());
                }
            }

            PriceLevelRiskService.PriceLevelRisk priceLevelRisk =
                    priceLevelRiskService.assessPriceLevelRisk(signal.getSymbol(),
                            signal.getEntryAssumptionPrice(), analysisId);

            if (!priceLevelRisk.shouldProceed()) {
                log.warn("[EXECUTION][{}] ❌ Price level risk - saving as LEVEL_BLOCKED: {}",
                        analysisId, priceLevelRisk.getRecommendation());

                saveFailedTrade(signal, "LEVEL_BLOCKED",
                        "Near POC/resistance without volume confirmation: " + priceLevelRisk.getRecommendation(), analysisId);
                saveFailedSignal(signal, "LEVEL_BLOCKED",
                        "Near POC/resistance without volume confirmation");

                telegramService.sendMessage(String.format(
                        "🚫 SIGNAL BLOCKED - PRICE LEVEL RISK\n" +
                                "Option: %s\n" +
                                "Reason: %s\n" +
                                "Risk: %s\n" +
                                "Status: Saved for analysis",
                        signal.getOptionSymbol(),
                        priceLevelRisk.getRecommendation(),
                        priceLevelRisk.isHighRisk() ? "HIGH" : "MODERATE"
                ));
                return;
            }

            if (!signal.getStrategy().contains("AI_LEADER_LAG") && !signal.getStrategy().contains("ENHANCED_AI")) {
                LeaderDirectionValidator.ValidationResult leaderValidation =
                        leaderValidator.validateLeaderDirection(signal, analysisId);

                if (!leaderValidation.isShouldProceed()) {
                    if (leaderValidation.getConfidenceAdjustment() < 0.8) {
                        log.warn("[EXECUTION][{}] ❌ Leader opposition - HARD BLOCK: {}",
                                analysisId, leaderValidation.getReason());

                        saveFailedTrade(signal, "LEADER_BLOCKED", leaderValidation.getReason(), analysisId);
                        saveFailedSignal(signal, "LEADER_BLOCKED", leaderValidation.getReason());

                        telegramService.sendMessage(String.format(
                                "🚫 SIGNAL BLOCKED - LEADER OPPOSITION\n" +
                                        "Option: %s\n" +
                                        "Reason: %s\n" +
                                        "Leaders: %s\n" +
                                        "Status: Saved for analysis",
                                signal.getOptionSymbol(),
                                leaderValidation.getReason(),
                                leaderValidation.getDetails()
                        ));
                        return;
                    } else {
                        signal.setConfidence(signal.getConfidence() * leaderValidation.getConfidenceAdjustment());
                        log.info("[EXECUTION][{}] ⚠️ Leader caution - confidence reduced to {}%",
                                analysisId, (int)(signal.getConfidence() * 100));
                    }
                }
            }

            GreeksValidator.GreeksValidationResult greeksValidation =
                    greeksValidator.validateGreeks(signal, signal.getConfidence(), analysisId);

            if (!greeksValidation.isValid()) {
                log.warn("[EXECUTION][{}] ❌ Greeks validation failed: {}",
                        analysisId, greeksValidation.getReason());

                saveFailedTrade(signal, "GREEKS_BLOCKED", greeksValidation.getReason(), analysisId);
                saveFailedSignal(signal, "GREEKS_BLOCKED", greeksValidation.getReason());

                telegramService.sendMessage(String.format(
                        "🚫 SIGNAL BLOCKED - GREEKS RISK\n" +
                                "Option: %s\n" +
                                "Reason: %s\n" +
                                "Delta: %.3f, Gamma: %.3f, Theta: %.3f\n" +
                                "Status: Saved for analysis",
                        signal.getOptionSymbol(),
                        greeksValidation.getReason(),
                        signal.getDelta() != null ? signal.getDelta() : 0.0,
                        signal.getGamma() != null ? signal.getGamma() : 0.0,
                        signal.getTheta() != null ? signal.getTheta() : 0.0
                ));
                return;
            }

            log.info("[EXECUTION][{}] ✅ All validations PASSED - proceeding with execution", analysisId);

            boolean orderSuccessful = attemptSignalExecution(signal, analysisId);

            if (orderSuccessful) {
                log.info("[EXECUTION][{}] ✅ Tradier order CONFIRMED - saving as OPEN", analysisId);

                Trade trade = createSuccessfulTrade(signal, currentPrice, analysisId);
                enrichTradeWithBayesianData(trade, signal);
                trade = tradeRepository.save(trade);

                signal.setEntryPrice(currentPrice);
                signal.setStatus("EXECUTED");
                signal.getMetadata().put("tradeId", trade.getId().toString());
                signal.getMetadata().put("executedAt", LocalDateTime.now().toString());
                signal.getMetadata().put("priceLevelClear", "true");
                signal.getMetadata().put("leaderValidation", "passed");
                signal.getMetadata().put("greeksValidation", "passed");
                signalRepository.save(signal);

                log.info("[EXECUTION][{}] ✅ SUCCESS SAVED - Trade ID: {} (OPEN), Signal (EXECUTED)",
                        analysisId, trade.getId());

                sendSuccessNotification(trade, signal, explanation, analysisId);

            } else {
                log.warn("[EXECUTION][{}] ❌ Tradier order FAILED - saving as ORDER_FAILED", analysisId);
                saveFailedTrade(signal, "ORDER_FAILED", "Tradier order rejected", analysisId);
                saveFailedSignal(signal, "ORDER_FAILED", "Tradier order rejected");

                telegramService.sendMessage(String.format(
                        "❌ EXECUTION FAILED\n" +
                                "Option: %s\n" +
                                "Reason: Tradier order rejected\n" +
                                "Status: ORDER_FAILED (saved for analysis)",
                        signal.getOptionSymbol()
                ));
            }

        } catch (Exception e) {
            log.error("[EXECUTION][{}] ❌ Exception during execution: {}", analysisId, e.getMessage());
            saveFailedTrade(signal, "EXECUTION_ERROR", e.getMessage(), analysisId);
            saveFailedSignal(signal, "EXECUTION_ERROR", e.getMessage());
        }
    }


    // ================================================================================================
    // LEADER DIRECTION VALIDATOR
    // ================================================================================================

    private class LeaderDirectionValidator {

        static class ValidationResult {
            private final boolean shouldProceed;
            private final double confidenceAdjustment;
            private final String reason;
            private final String details;

            ValidationResult(boolean proceed, double adjustment, String reason, String details) {
                this.shouldProceed = proceed;
                this.confidenceAdjustment = adjustment;
                this.reason = reason;
                this.details = details;
            }

            public boolean isShouldProceed() {
                return shouldProceed;
            }

            public double getConfidenceAdjustment() {
                return confidenceAdjustment;
            }

            public String getReason() {
                return reason;
            }

            public String getDetails() {
                return details;
            }
        }

        static class LeaderMomentum {
            private final String symbol;
            private final double weight;
            private double momentum5min;
            private double momentum10min;
            private double momentum15min;
            private boolean isOpposing;

            LeaderMomentum(String symbol, double weight) {
                this.symbol = symbol;
                this.weight = weight;
            }

            public String getSymbol() { return symbol; }
            public double getWeight() { return weight; }
            public double getMomentum5min() { return momentum5min; }
            public void setMomentum5min(double momentum5min) { this.momentum5min = momentum5min; }
            public double getMomentum10min() { return momentum10min; }
            public void setMomentum10min(double momentum10min) { this.momentum10min = momentum10min; }
            public double getMomentum15min() { return momentum15min; }
            public void setMomentum15min(double momentum15min) { this.momentum15min = momentum15min; }
            public boolean isOpposing() { return isOpposing; }
            public void setOpposing(boolean opposing) { isOpposing = opposing; }
        }

        public ValidationResult validateLeaderDirection(Signal signal, String analysisId) {
            try {
                LocalTime now = LocalTime.now(ET_ZONE);

                if (now.isBefore(LocalTime.of(10, 0))) {
                    return new ValidationResult(true, 1.0, "Early session - skipping leader check", "");
                }

                Double confidence = signal.getConfidence();
                if (confidence != null && confidence >= 0.85) {
                    return new ValidationResult(true, 1.0, "High confidence signal - leader check bypassed", "");
                }

                if (signal.getStrategy().contains("REVERSION")) {
                    return new ValidationResult(true, 1.0, "Reversion play - leaders may be extended", "");
                }

                TechnicalAnalysis qqqTA = technicalAnalysisService.analyze("QQQ");
                if (qqqTA != null && qqqTA.getVolumeRatio() > 2.0) {
                    return new ValidationResult(true, 1.0, "QQQ leading with high volume", "");
                }

                boolean isCallSignal;
                if (signal.getStrategy().startsWith("TEMP_")) {
                    String optionType = signal.getMetadata().get("optionType");
                    isCallSignal = "CALL".equalsIgnoreCase(optionType);
                    log.info("[LEADER-VALIDATOR][{}] Temp VWAP validation - Option type: {}", analysisId, optionType);
                } else {
                    isCallSignal = signal.getStrategy().contains("CALL");
                }

                Map<String, LeaderMomentum> leaderAnalysis = analyzeLeaderMomentum(isCallSignal, now);

                long opposingCount = leaderAnalysis.values().stream()
                        .filter(l -> l.isOpposing())
                        .count();

                double oppositionScore = leaderAnalysis.values().stream()
                        .filter(l -> l.isOpposing())
                        .mapToDouble(l -> l.getWeight())
                        .sum();

                String details = leaderAnalysis.entrySet().stream()
                        .map(e -> String.format("%s: 5m=%.2f%%, 10m=%.2f%%, 15m=%.2f%% (opposing: %s)",
                                e.getKey(),
                                e.getValue().getMomentum5min() * 100,
                                e.getValue().getMomentum10min() * 100,
                                e.getValue().getMomentum15min() * 100,
                                e.getValue().isOpposing()))
                        .collect(Collectors.joining(", "));

                log.info("[LEADER-VALIDATOR][{}] {} Signal Analysis: {} leaders opposing (weighted: {})",
                        analysisId, isCallSignal ? "CALL" : "PUT", opposingCount,String.format("%.0f", oppositionScore * 100));
                //log.info("[LEADER-VALIDATOR][{}] Leader Details: {}", analysisId, details);

                boolean isVWAPValidation = signal.getStrategy().startsWith("TEMP_VWAP");

                if (isVWAPValidation) {
                    if (opposingCount >= 1 || oppositionScore >= 0.3) {
                        return new ValidationResult(false, 0.0,
                                String.format("VWAP BLOCKED: %d/3 leaders opposing (weighted: %.0f%%)",
                                        opposingCount, oppositionScore * 100),
                                details);
                    }
                } else {
                    // HARD BLOCKS first
                    if (opposingCount >= 2 || oppositionScore >= 0.7) {
                        return new ValidationResult(false, 0.0,
                                String.format("%d/3 leaders opposing direction (weighted: %.0f%%)",
                                        opposingCount, oppositionScore * 100),
                                details);
                    }

                    // NEW: UNIVERSAL MONITORING LOGIC - Any leader opposing
                    if (opposingCount >= 1) {
                        // Find the opposing leader(s)
                        List<String> opposingLeaders = leaderAnalysis.entrySet().stream()
                                .filter(e -> e.getValue().isOpposing())
                                .map(Map.Entry::getKey)
                                .collect(Collectors.toList());

                        // For multiple opposing leaders, pick the one with highest weight
                        String primaryOpposingLeader = opposingLeaders.stream()
                                .max(Comparator.comparing(leader -> LEADER_WEIGHTS.get(leader)))
                                .orElse(opposingLeaders.get(0));

                        // Set monitoring metadata for ALL signals
                        signal.getMetadata().put("requiresLeaderMonitoring", "true");
                        signal.getMetadata().put("opposingLeader", primaryOpposingLeader);
                        signal.getMetadata().put("isCallSignal", String.valueOf(isCallSignal));

                        // Determine monitoring duration
                        int monitoringSeconds = now.isAfter(LocalTime.of(15, 30)) ? 90 : 120;
                        signal.getMetadata().put("monitoringDuration", String.valueOf(monitoringSeconds));

                        log.warn("[LEADER-MONITORING][{}] {} leader(s) opposing - MONITORING {} for {} seconds",
                                analysisId, opposingCount, primaryOpposingLeader, monitoringSeconds);

                        return new ValidationResult(true, 0.9, // Slight confidence reduction
                                String.format("%d leader(s) opposing - monitoring %s", opposingCount, primaryOpposingLeader),
                                details);
                    }
                }

                log.info("[LEADER-VALIDATOR][{}] ✅ Leaders ALIGNED for {} signal", analysisId, isCallSignal ? "CALL" : "PUT");
                return new ValidationResult(true, 1.0, "Leaders aligned", details);

            } catch (Exception e) {
                log.error("[LEADER-VALIDATOR][{}] Error validating leader direction: {}",
                        analysisId, e.getMessage());
                return new ValidationResult(true, 1.0, "Validation error - proceeding", "");
            }
        }

        private Map<String, LeaderMomentum> analyzeLeaderMomentum(boolean isCallSignal, LocalTime now) {
            Map<String, LeaderMomentum> results = new HashMap<>();

            // FIXED: Consistent lower thresholds throughout the day to catch directional misalignment
            double threshold5min = 0.0008;   // 0.08% (was 0.10%/0.15%)
            double threshold10min = 0.0012;  // 0.12% (was 0.20%/0.25%)
            double threshold15min = 0.0018;  // 0.18% (was 0.30%/0.35%)

            for (String leader : LEADER_STOCKS) {
                LeaderMomentum momentum = new LeaderMomentum(leader, LEADER_WEIGHTS.get(leader));

                List<MarketData> data5min = marketDataRepository.findRecentData(leader, 5);
                List<MarketData> data10min = marketDataRepository.findRecentData(leader, 10);
                List<MarketData> data15min = marketDataRepository.findRecentData(leader, 15);

                momentum.setMomentum5min(calculateMomentum(data5min));
                momentum.setMomentum10min(calculateMomentum(data10min));
                momentum.setMomentum15min(calculateMomentum(data15min));

                boolean opposing5min, opposing10min, opposing15min;

                if (isCallSignal) {
                    // CALL signal - negative momentum opposes
                    opposing5min = momentum.getMomentum5min() < -threshold5min;
                    opposing10min = momentum.getMomentum10min() < -threshold10min;
                    opposing15min = momentum.getMomentum15min() < -threshold15min;
                } else {
                    // PUT signal - positive momentum opposes
                    opposing5min = momentum.getMomentum5min() > threshold5min;
                    opposing10min = momentum.getMomentum10min() > threshold10min;
                    opposing15min = momentum.getMomentum15min() > threshold15min;
                }

                // Leader opposes if ANY timeframe shows opposing momentum
                boolean finalOpposing = opposing5min || opposing10min || opposing15min;
                momentum.setOpposing(finalOpposing);

                results.put(leader, momentum);

                log.info("[LEADER-MOMENTUM][{}] {}: 5m={}% (opp:{}), 10m={}% (opp:{}), 15m={}% (opp:{}) → {} signal → FINAL opposing={}",
                        leader, leader,
                        String.format("%.3f", momentum.getMomentum5min() * 100), opposing5min,
                        String.format("%.3f", momentum.getMomentum10min() * 100), opposing10min,
                        String.format("%.3f", momentum.getMomentum15min() * 100), opposing15min,
                        isCallSignal ? "CALL" : "PUT",
                        finalOpposing);
            }

            return results;
        }
        private double calculateMomentum(List<MarketData> data) {
            if (data.isEmpty()) return 0.0;

            MarketData firstData = data.get(data.size() - 1);
            MarketData lastData = data.get(0);

            if (firstData.getPrice() == null || lastData.getPrice() == null ||
                    firstData.getPrice().compareTo(BigDecimal.ZERO) == 0) {
                return 0.0;
            }

            return lastData.getPrice().subtract(firstData.getPrice())
                    .divide(firstData.getPrice(), 6, RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }

    // ================================================================================================
    // GREEKS VALIDATOR
    // ================================================================================================

    private class GreeksValidator {

        static class GreeksValidationResult {
            private final boolean valid;
            private final String reason;

            GreeksValidationResult(boolean valid, String reason) {
                this.valid = valid;
                this.reason = reason;
            }

            public boolean isValid() {
                return valid;
            }

            public String getReason() {
                return reason;
            }
        }

        public GreeksValidationResult validateGreeks(Signal signal, double confidence, String analysisId) {
            try {
                LocalTime now = LocalTime.now(ET_ZONE);
                long minutesToClose = Duration.between(now, LocalTime.of(16, 0)).toMinutes();

                Double delta = signal.getDelta();
                Double gamma = signal.getGamma();
                Double theta = signal.getTheta();

                if (delta == null || gamma == null || theta == null) {
                    log.warn("[GREEKS-VALIDATOR][{}] Greeks not available - using defaults", analysisId);
                    return new GreeksValidationResult(true, "Greeks unavailable - proceeding with caution");
                }

                BigDecimal optionPrice = signal.getEntryPrice();
                if (optionPrice == null || optionPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    return new GreeksValidationResult(true, "Unable to calculate theta ratio");
                }

                boolean isBreakout = signal.getStrategy().contains("BREAKOUT") ||
                        signal.getStrategy().contains("ORB");
                boolean isReversion = signal.getStrategy().contains("REVERSION");
                boolean isBounce = signal.getStrategy().contains("BOUNCE");
                boolean isHighConfidence = confidence >= 0.80;
                boolean isVeryHighConfidence = confidence >= 0.85;

                // ENHANCED: More aggressive multipliers for high-confidence signals
                double deltaMultiplier = 1.0;
                double thetaMultiplier = 1.0;
                double gammaMultiplier = 1.0;

                if (isVeryHighConfidence) { // 85%+ confidence
                    deltaMultiplier = 1.6;   // 60% more lenient
                    thetaMultiplier = 1.8;   // 80% more lenient for theta (key fix)
                    gammaMultiplier = 1.4;
                    log.info("[GREEKS-VALIDATOR][{}] VERY HIGH CONFIDENCE ({}%) - Significantly relaxed Greeks thresholds",
                            analysisId, (int)(confidence * 100));
                } else if (isHighConfidence) { // 80%+ confidence
                    deltaMultiplier = 1.4;
                    thetaMultiplier = 1.5;   // 50% more lenient for theta
                    gammaMultiplier = 1.3;
                    log.info("[GREEKS-VALIDATOR][{}] HIGH CONFIDENCE ({}%) - Relaxed Greeks thresholds",
                            analysisId, (int)(confidence * 100));
                } else if (confidence >= 0.75) {
                    deltaMultiplier = 1.2;
                    thetaMultiplier = 1.3;
                    gammaMultiplier = 1.15;
                    log.info("[GREEKS-VALIDATOR][{}] ELEVATED CONFIDENCE ({}%) - Moderately relaxed Greeks",
                            analysisId, (int)(confidence * 100));
                }

                // ENHANCED: Early day theta tolerance
                if (now.isBefore(LocalTime.of(11, 0))) {
                    thetaMultiplier *= 1.3; // 30% more theta tolerance in morning
                    log.info("[GREEKS-VALIDATOR][{}] MORNING SESSION - Additional theta tolerance", analysisId);
                }

                // ENHANCED: Strategy-specific adjustments
                if (isBounce && isHighConfidence) {
                    thetaMultiplier *= 1.2; // Bounce strategies with trend alignment get theta boost
                    log.info("[GREEKS-VALIDATOR][{}] HIGH-CONFIDENCE BOUNCE - Theta tolerance increased", analysisId);
                }

                double minDelta, maxDelta;
                if (minutesToClose < 120) {
                    minDelta = 0.30;
                    maxDelta = 0.45 * deltaMultiplier;
                } else if (now.isBefore(LocalTime.of(11, 0))) {
                    minDelta = isBreakout ? 0.20 : 0.25;
                    maxDelta = 0.50 * deltaMultiplier;
                } else {
                    minDelta = 0.25;
                    maxDelta = 0.45 * deltaMultiplier;
                }

                if (isBreakout) minDelta -= 0.05;
                if (isReversion) {
                    minDelta = 0.35;
                    maxDelta = 0.45 * deltaMultiplier;
                }

                double deltaAbs = Math.abs(delta);
                if (deltaAbs < minDelta) {
                    return new GreeksValidationResult(false,
                            String.format("Delta too low (%.3f < %.3f) - lottery ticket",
                                    deltaAbs, minDelta));
                }

                if (deltaAbs > maxDelta) {
                    return new GreeksValidationResult(false,
                            String.format("Delta too high (%.3f > %.3f) - poor risk/reward",
                                    deltaAbs, maxDelta));
                }

                double maxGamma = minutesToClose < 60 ? 0.10 * gammaMultiplier :
                        minutesToClose < 120 ? 0.12 * gammaMultiplier :
                                0.15 * gammaMultiplier;

                double gammaAbs = Math.abs(gamma);
                if (gammaAbs > maxGamma) {
                    if (gammaAbs > 0.20 * gammaMultiplier) {
                        return new GreeksValidationResult(false,
                                String.format("Gamma too high (%.3f) - position unstable", gammaAbs));
                    }
                    if (minutesToClose < 120) {
                        return new GreeksValidationResult(false,
                                String.format("High gamma (%.3f) near expiry - whipsaw risk", gammaAbs));
                    }
                }

                if (deltaAbs > 0.001) {
                    double gammaDeltaRatio = gammaAbs / deltaAbs;
                    double maxGammaDeltaRatio = 0.5 * gammaMultiplier;
                    if (gammaDeltaRatio > maxGammaDeltaRatio) {
                        return new GreeksValidationResult(false,
                                String.format("Gamma/Delta ratio too high (%.2f) - unstable position", gammaDeltaRatio));
                    }
                }

                // ENHANCED: Much more lenient theta validation
                double thetaAbs = Math.abs(theta);
                double optionPriceDouble = optionPrice.doubleValue();

                if (optionPriceDouble > 0.001) {
                    double thetaRatio = thetaAbs / optionPriceDouble;

                    // ENHANCED: New theta thresholds with confidence and time adjustments
                    double maxThetaRatio;
                    if (minutesToClose < 60) {
                        maxThetaRatio = 0.40 * thetaMultiplier; // Was 0.30, now 0.40 base
                    } else if (minutesToClose < 120) {
                        maxThetaRatio = 0.55 * thetaMultiplier; // Was 0.40, now 0.55 base
                    } else {
                        maxThetaRatio = 0.75 * thetaMultiplier; // Was 0.50, now 0.75 base
                    }

                    // SPECIAL: Very high confidence signals get extreme theta tolerance
                    if (isVeryHighConfidence && now.isBefore(LocalTime.of(12, 0))) {
                        maxThetaRatio = Math.min(0.90, maxThetaRatio); // Up to 90% theta allowed for very high confidence morning signals
                        log.info("[GREEKS-VALIDATOR][{}] VERY HIGH CONFIDENCE MORNING - Extreme theta tolerance: {}%",
                                analysisId, (int)(maxThetaRatio * 100));
                    }

                    if (thetaRatio > maxThetaRatio) {
                        // ENHANCED: Only block if extremely high theta AND low confidence
                        if (thetaRatio > 0.85 && confidence < 0.75) {
                            return new GreeksValidationResult(false,
                                    String.format("Extreme theta decay (%.0f%% of premium) with low confidence - melting ice cube",
                                            thetaRatio * 100));
                        }

                        // For high-confidence signals, just warn but allow
                        log.warn("[GREEKS-VALIDATOR][{}] High theta decay (%.0f%%) but HIGH CONFIDENCE ({}%) - ALLOWING",
                                analysisId, (int)(thetaRatio * 100), (int)(confidence * 100));
                    }
                }

                log.info("[GREEKS-VALIDATOR][{}] Greeks validated - Delta: {:.3f}, Gamma: {:.3f}, Theta: {:.3f}, Confidence: {}%",
                        analysisId, deltaAbs, gammaAbs, thetaAbs, (int)(confidence * 100));

                return new GreeksValidationResult(true, "Greeks within acceptable ranges");

            } catch (Exception e) {
                log.error("[GREEKS-VALIDATOR][{}] Error validating Greeks: {}", analysisId, e.getMessage());
                return new GreeksValidationResult(true, "Validation error - proceeding");
            }
        }
    }

    // ================================================================================================
    // TRADITIONAL STRATEGY IMPLEMENTATIONS
    // ================================================================================================

    private void analyzeOpeningRangeBreakout(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend, String analysisId) {
        LocalTime now = LocalTime.now(ET_ZONE);

        if (now.isAfter(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(10, 30))) {
            analyze30MinuteORB(option, allOptions, ta, signals, marketTrend, analysisId);
        }
    }

    private void analyze30MinuteORB(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                    List<Signal> signals, String marketTrend, String analysisId) {
        if (!hasORBDataSufficiency(ta.getSymbol(), analysisId)) {
            return;
        }

        OpeningRange range30Min = calculate30MinuteRange(ta);
        if (range30Min == null || !isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) return;

        BigDecimal currentPrice = ta.getCurrentPrice();

        if (!validateEnhancedBreakout(currentPrice, range30Min, ta, LocalTime.now(ET_ZONE))) return;

        VolumeRequirement volReq = calculateBreakoutCandleVolume(ta);
        if (!volReq.isMet()) return;

        GapAnalysis gapAnalysis = analyzeGapConditions(ta);

        String strategy = "CALL".equalsIgnoreCase(option.getType()) ? "0DTE_30MIN_ORB_CALL" : "0DTE_30MIN_ORB_PUT";
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());

        if ((isCall && currentPrice.compareTo(range30Min.getHigh()) > 0) ||
                (!isCall && currentPrice.compareTo(range30Min.getLow()) < 0)) {

            double confidence = calculateEnhancedORBConfidence(ta, volReq, gapAnalysis, isCall, LocalTime.now(ET_ZONE));
            double threshold = calculateDynamicThreshold(LocalTime.now(ET_ZONE), volReq);

            if (confidence >= threshold) {
                Signal signal = createAdvancedORBSignal(option, allOptions, ta, "BUY", strategy, confidence, marketTrend, range30Min, volReq);
                signals.add(signal);

                log.info("[{}] ✅ {} - Confidence: {}%, Volume: {}, Gap: {}%",
                        analysisId, strategy, (int)(confidence * 100), volReq.getTier(),
                        gapAnalysis.getGapPercentage() * 100);
            }
        }
    }

//    private void analyzeVWAPBreakout(Option option, List<Option> allOptions, TechnicalAnalysis ta,
//                                     List<Signal> signals, String marketTrend, String analysisId) {
//        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
//            return;
//        }
//
//        Signal tempSignal = new Signal();
//        tempSignal.setStrategy("TEMP_VWAP_CHECK");
//        tempSignal.getMetadata().put("optionType", option.getType());
//
//        LeaderDirectionValidator.ValidationResult leaderValidation =
//                leaderValidator.validateLeaderDirection(tempSignal, analysisId);
//
//        if (!leaderValidation.isShouldProceed()) {
//            log.warn("[{}] ❌ VWAP BREAKOUT BLOCKED: {} - {}",
//                    analysisId, "QQQ", leaderValidation.getReason());
//            return;
//        }
//
//        BigDecimal currentPrice = ta.getCurrentPrice();
//        BigDecimal vwapUpper = ta.getVwapUpperBand();
//        BigDecimal vwapLower = ta.getVwapLowerBand();
//
//        if (ta.isHasRsiDivergence()) {
//            log.info("[{}] ⚠️ RSI divergence detected - reducing breakout confidence", analysisId);
//        }
//
//        if (currentPrice.compareTo(vwapUpper) > 0 && "CALL".equalsIgnoreCase(option.getType())) {
//            BigDecimal breakoutDistance = currentPrice.subtract(vwapUpper)
//                    .divide(vwapUpper, 4, RoundingMode.HALF_UP);
//
//            if (breakoutDistance.compareTo(BigDecimal.valueOf(0.002)) >= 0) {
//                boolean volumeRequirementMet = ta.getVolumeRatio() >= requiredVolumeRatio;
//
//                if (volumeRequirementMet) {
//                    double confidence = calculateBreakoutConfidence(ta, true);
//
//                    if (ta.isHasRsiDivergence()) {
//                        confidence *= 0.7;
//                    }
//
//                    if (confidence >= 0.60) {
//                        Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_BREAKOUT_CALL", confidence, marketTrend);
//                        signal.setReason(String.format(
//                                "VWAP breakout %.2f%% above upper band with %.1fx volume (AI threshold: %.2f)",
//                                breakoutDistance.multiply(BigDecimal.valueOf(100)).doubleValue(),
//                                ta.getVolumeRatio(), requiredVolumeRatio));
//                        signals.add(signal);
//
//                        log.info("[{}] ✅ VWAP Breakout CALL - Confidence: {}% (AI Volume Threshold: {})",
//                                analysisId, (int)(confidence * 100), requiredVolumeRatio);
//                    }
//                }
//            }
//        }
//
//        if (currentPrice.compareTo(vwapLower) < 0 && "PUT".equalsIgnoreCase(option.getType())) {
//            BigDecimal breakoutDistance = vwapLower.subtract(currentPrice)
//                    .divide(vwapLower, 4, RoundingMode.HALF_UP);
//
//            if (breakoutDistance.compareTo(BigDecimal.valueOf(0.002)) >= 0) {
//                double requiredVolumeRatio = attributeLearning.getOptimalThreshold(
//                        "0DTE_VWAP_BREAKOUT_PUT", "VOLUME_THRESHOLD");
//                boolean volumeRequirementMet = ta.getVolumeRatio() >= requiredVolumeRatio;
//
//                if (volumeRequirementMet) {
//                    double confidence = calculateBreakoutConfidence(ta, false);
//
//                    if (ta.isHasRsiDivergence()) {
//                        confidence *= 0.7;
//                    }
//
//                    if (confidence >= 0.60) {
//                        Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_BREAKOUT_PUT", confidence, marketTrend);
//                        signal.setReason(String.format(
//                                "VWAP breakout %.2f%% below lower band with %.1fx volume (AI threshold: %.2f)",
//                                breakoutDistance.multiply(BigDecimal.valueOf(100)).doubleValue(),
//                                ta.getVolumeRatio(), requiredVolumeRatio));
//                        signals.add(signal);
//
//                        log.info("[{}] ✅ VWAP Breakout PUT - Confidence: {}% (AI Volume Threshold: {})",
//                                analysisId, (int)(confidence * 100), requiredVolumeRatio);
//                    }
//                }
//            }
//        }
//    }
//starting the change
private void analyzeVWAPDeviationReversion(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                           List<Signal> signals, String marketTrend, String analysisId) {
    if (!ta.isExtendedFromVwap() || ta.getVwapStandardDeviation() == null ||
            ta.getVwapStandardDeviation().compareTo(BigDecimal.ZERO) == 0) {
        return;
    }
    if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
        return;
    }

    // NEW: Pre-validate leader direction
    Signal tempSignal = new Signal();
    tempSignal.setStrategy("TEMP_VWAP_REVERSION_CHECK");
    tempSignal.getMetadata().put("optionType", option.getType());

    LeaderDirectionValidator.ValidationResult leaderValidation =
            leaderValidator.validateLeaderDirection(tempSignal, analysisId);

    if (!leaderValidation.isShouldProceed()) {
        log.warn("[{}] ❌ VWAP REVERSION BLOCKED: {} - {}",
                analysisId, option.getType(), leaderValidation.getReason());
        return;
    }

    double priceRatio = ta.getPriceToVwapRatio();

    if (priceRatio > 1.0 && "PUT".equalsIgnoreCase(option.getType()) && ta.getRsi() > 70) {
        double confidence = calculateReversionConfidence(ta, false);

        if (confidence >= 0.65) {
            Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_REVERSION_PUT", confidence, marketTrend);
            signal.setReason(String.format(
                    "Extended %.2f%% above VWAP with RSI %.1f - mean reversion setup (leaders aligned)",
                    (priceRatio - 1) * 100, ta.getRsi()));
            signals.add(signal);

            log.info("[{}] ✅ VWAP Reversion PUT - Confidence: {}% (Leader validation passed)",
                    analysisId, (int)(confidence * 100));
        }
    }

    if (priceRatio < 1.0 && "CALL".equalsIgnoreCase(option.getType()) && ta.getRsi() < 30) {
        double confidence = calculateReversionConfidence(ta, true);

        if (confidence >= 0.65) {
            Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_REVERSION_CALL", confidence, marketTrend);
            signal.setReason(String.format(
                    "Extended %.2f%% below VWAP with RSI %.1f - mean reversion setup (leaders aligned)",
                    (1 - priceRatio) * 100, ta.getRsi()));
            signals.add(signal);

            log.info("[{}] ✅ VWAP Reversion CALL - Confidence: {}% (Leader validation passed)",
                    analysisId, (int)(confidence * 100));
        }
    }
}

    private void analyzeVWAPSupportResistance(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                              List<Signal> signals, String marketTrend, String analysisId) {
        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
            return;
        }

        // NEW: Pre-validate leader direction
        Signal tempSignal = new Signal();
        tempSignal.setStrategy("TEMP_VWAP_SUPPORT_RESISTANCE_CHECK");
        tempSignal.getMetadata().put("optionType", option.getType());

        LeaderDirectionValidator.ValidationResult leaderValidation =
                leaderValidator.validateLeaderDirection(tempSignal, analysisId);

        if (!leaderValidation.isShouldProceed()) {
            log.warn("[{}] ❌ VWAP SUPPORT/RESISTANCE BLOCKED: {} - {}",
                    analysisId, option.getType(), leaderValidation.getReason());
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        if (ta.isVwapAsSupport() && "CALL".equalsIgnoreCase(option.getType()) &&
                currentPrice.compareTo(vwap) > 0) {

            if (ta.getVolumeRatio() >= MIN_VOLUME_RATIO_STANDARD) {
                double confidence = calculateSupportResistanceConfidence(ta, true);

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_SUPPORT_CALL", confidence, marketTrend);
                    signal.setReason(String.format("VWAP support at $%.2f held with volume confirmation (leaders aligned)", vwap.doubleValue()));
                    signals.add(signal);

                    log.info("[{}] ✅ VWAP Support CALL - Confidence: {}% (Leader validation passed)",
                            analysisId, (int)(confidence * 100));
                }
            }
        }

        if (ta.isVwapAsResistance() && "PUT".equalsIgnoreCase(option.getType()) &&
                currentPrice.compareTo(vwap) < 0) {

            if (ta.getVolumeRatio() >= MIN_VOLUME_RATIO_STANDARD) {
                double confidence = calculateSupportResistanceConfidence(ta, false);

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_RESISTANCE_PUT", confidence, marketTrend);
                    signal.setReason(String.format("VWAP resistance at $%.2f rejected with volume confirmation (leaders aligned)", vwap.doubleValue()));
                    signals.add(signal);

                    log.info("[{}] ✅ VWAP Resistance PUT - Confidence: {}% (Leader validation passed)",
                            analysisId, (int)(confidence * 100));
                }
            }
        }
    }

    private void analyzeVWAPReclaim(Option option, List<Option> allOptions,
                                    TechnicalAnalysis ta, List<Signal> signals,
                                    String marketTrend, String analysisId) {

        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
            return;
        }

        // NEW: Pre-validate leader direction
        Signal tempSignal = new Signal();
        tempSignal.setStrategy("TEMP_VWAP_RECLAIM_CHECK");
        tempSignal.getMetadata().put("optionType", option.getType());

        LeaderDirectionValidator.ValidationResult leaderValidation =
                leaderValidator.validateLeaderDirection(tempSignal, analysisId);

        if (!leaderValidation.isShouldProceed()) {
            log.warn("[{}] ❌ VWAP RECLAIM BLOCKED: {} - {}",
                    analysisId, option.getType(), leaderValidation.getReason());
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        boolean nowAboveVWAP = currentPrice.compareTo(vwap) > 0;
        boolean nowBelowVWAP = currentPrice.compareTo(vwap) < 0;

        List<MarketData> recentData = marketDataRepository.findRecentData(ta.getSymbol(), 5);
        if (recentData.size() < 3) return;

        boolean recentlyTestedFromBelow = checkRecentVWAPTest(recentData, vwap, false);
        boolean recentlyTestedFromAbove = checkRecentVWAPTest(recentData, vwap, true);

        double penetration = Math.abs(currentPrice.subtract(vwap)
                .divide(vwap, 4, RoundingMode.HALF_UP).doubleValue());

        if (penetration < 0.0005 || penetration > 0.003) return;

        if ("CALL".equalsIgnoreCase(option.getType()) && nowAboveVWAP && recentlyTestedFromBelow) {
            if ("UP".equals(marketTrend) && ta.getVolumeRatio() > 1.0) {
                double confidence = calculateVWAPReclaimConfidence(ta, penetration, true);

                if (confidence >= 0.75) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY",
                            "0DTE_VWAP_RECLAIM_CALL", confidence, marketTrend);

                    signal.setReason(String.format(
                            "VWAP reclaim after test - penetration: %.2f%%, volume: %.1fx (leaders aligned)",
                            penetration * 100, ta.getVolumeRatio()));

                    signals.add(signal);
                    log.info("[{}] ✅ VWAP RECLAIM CALL - Confidence: {}% (Leader validation passed)",
                            analysisId, (int)(confidence * 100));
                }
            }
        }

        if ("PUT".equalsIgnoreCase(option.getType()) && nowBelowVWAP && recentlyTestedFromAbove) {
            if ("DOWN".equals(marketTrend) && ta.getVolumeRatio() > 1.0) {
                double confidence = calculateVWAPReclaimConfidence(ta, penetration, false);

                if (confidence >= 0.75) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY",
                            "0DTE_VWAP_FAILED_RECLAIM_PUT", confidence, marketTrend);

                    signal.setReason(String.format(
                            "VWAP failed reclaim - rejection: %.2f%%, volume: %.1fx (leaders aligned)",
                            penetration * 100, ta.getVolumeRatio()));

                    signals.add(signal);
                    log.info("[{}] ✅ VWAP FAILED RECLAIM PUT - Confidence: {}% (Leader validation passed)",
                            analysisId, (int)(confidence * 100));
                }
            }
        }
    }

    private void analyzeVWAPBounce(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                   List<Signal> signals, String marketTrend, String analysisId) {

        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
            return;
        }

        // NEW: Pre-validate leader direction
        Signal tempSignal = new Signal();
        tempSignal.setStrategy("TEMP_VWAP_BOUNCE_CHECK");
        tempSignal.getMetadata().put("optionType", option.getType());

        LeaderDirectionValidator.ValidationResult leaderValidation =
                leaderValidator.validateLeaderDirection(tempSignal, analysisId);

        if (!leaderValidation.isShouldProceed()) {
            log.warn("[{}] ❌ VWAP BOUNCE BLOCKED: {} - {}",
                    analysisId, option.getType(), leaderValidation.getReason());
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        if (Math.abs(ta.getPriceToVwapRatio() - 1.0) > 0.005) {
            return;
        }

        boolean volumeRequirementMet = ta.getVolumeRatio() >= MIN_VOLUME_RATIO_STANDARD;
        if (!volumeRequirementMet) {
            return;
        }

        boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
        boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

        if (isCallOption && currentPrice.compareTo(vwap) >= 0 && "UP".equals(marketTrend)) {
            double confidence = calculateBounceConfidence(ta, true);

            if (confidence >= 0.70) {
                Signal signal = createSignal(option, allOptions, ta, "BUY",
                        "0DTE_VWAP_BOUNCE_CALL", confidence, marketTrend);
                signal.setReason("VWAP support bounce with volume confirmation (leaders aligned)");
                signals.add(signal);

                log.info("[{}] ✅ VWAP Bounce CALL - Confidence: {}% (Leader validation passed)",
                        analysisId, (int)(confidence * 100));
            }
        }

        if (isPutOption && currentPrice.compareTo(vwap) <= 0 && "DOWN".equals(marketTrend)) {
            double confidence = calculateBounceConfidence(ta, false);

            if (confidence >= 0.70) {
                Signal signal = createSignal(option, allOptions, ta, "BUY",
                        "0DTE_VWAP_BOUNCE_PUT", confidence, marketTrend);
                signal.setReason("VWAP resistance rejection with volume confirmation (leaders aligned)");
                signals.add(signal);

                log.info("[{}] ✅ VWAP Bounce PUT - Confidence: {}% (Leader validation passed)",
                        analysisId, (int)(confidence * 100));
            }
        }
    }

    private void analyzeDirectionalVWAPBounce(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                              List<Signal> signals, String marketTrend, String analysisId) {

        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
            return;
        }

        // NEW: Pre-validate leader direction
        Signal tempSignal = new Signal();
        tempSignal.setStrategy("TEMP_VWAP_DIRECTIONAL_BOUNCE_CHECK");
        tempSignal.getMetadata().put("optionType", option.getType());

        LeaderDirectionValidator.ValidationResult leaderValidation =
                leaderValidator.validateLeaderDirection(tempSignal, analysisId);

        if (!leaderValidation.isShouldProceed()) {
            log.warn("[{}] ❌ VWAP DIRECTIONAL BOUNCE BLOCKED: {} - {}",
                    analysisId, option.getType(), leaderValidation.getReason());
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        double vwapDistance = Math.abs(currentPrice.subtract(vwap)
                .divide(vwap, 4, RoundingMode.HALF_UP).doubleValue());

        if (vwapDistance > 0.002) {
            return;
        }

        boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
        boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

        if (isCallOption && "UP".equals(marketTrend)) {
            if (ta.getVolumeRatio() > 1.0) {
                double confidence = calculateDirectionalBounceConfidence(ta, vwapDistance, true);

                if (confidence >= 0.75) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY",
                            "0DTE_VWAP_BOUNCE_CALL", confidence, marketTrend);

                    signal.setReason(String.format(
                            "VWAP bounce setup - Distance: %.2f%%, Volume: %.1fx, RSI: %.1f (leaders aligned)",
                            vwapDistance * 100, ta.getVolumeRatio(), ta.getRsi()));

                    signals.add(signal);
                    log.info("[{}] ✅ VWAP BOUNCE CALL - Confidence: {}% (Leader validation passed)",
                            analysisId, (int)(confidence * 100));
                }
            }
        }

        if (isPutOption && "DOWN".equals(marketTrend)) {
            if (ta.getVolumeRatio() > 1.0) {
                double confidence = calculateDirectionalBounceConfidence(ta, vwapDistance, false);

                if (confidence >= 0.75) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY",
                            "0DTE_VWAP_BOUNCE_PUT", confidence, marketTrend);

                    signal.setReason(String.format(
                            "VWAP rejection setup - Distance: %.2f%%, Volume: %.1fx, RSI: %.1f (leaders aligned)",
                            vwapDistance * 100, ta.getVolumeRatio(), ta.getRsi()));

                    signals.add(signal);
                    log.info("[{}] ✅ VWAP BOUNCE PUT - Confidence: {}% (Leader validation passed)",
                            analysisId, (int)(confidence * 100));
                }
            }
        }
    }
//ending the change




    // ================================================================================================
    // CONFIDENCE CALCULATION METHODS
    // ================================================================================================

    private double calculateBreakoutConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.5;

        if (ta.getVolumeRatio() >= 2.0) {
            confidence += 0.30;
        } else if (ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT) {
            confidence += 0.20;
        } else if (ta.getVolumeRatio() >= 1.0) {
            confidence += 0.10;
        }

        if (bullish && ta.getRsi() > 55 && ta.getRsi() < 70) {
            confidence += 0.15;
        } else if (!bullish && ta.getRsi() < 45 && ta.getRsi() > 30) {
            confidence += 0.15;
        }

        if ((bullish && "BULLISH".equals(ta.getVwapTrend())) ||
                (!bullish && "BEARISH".equals(ta.getVwapTrend()))) {
            confidence += 0.10;
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        double timeMultiplier = getTimeWindowMultiplier(now);
        confidence *= timeMultiplier;

        return Math.min(confidence, 0.95);
    }

    private double calculateReversionConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.6;

        if ((bullish && ta.getRsi() < 25) || (!bullish && ta.getRsi() > 75)) {
            confidence += 0.20;
        }

        if (ta.isExtendedFromVwap()) {
            confidence += 0.15;
        }

        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.95);
    }

    private double calculateSupportResistanceConfidence(TechnicalAnalysis ta, boolean support) {
        double confidence = 0.6;

        if (ta.getVolumeRatio() > 1.2) {
            confidence += 0.20;
        } else if (ta.getVolumeRatio() > 0.8) {
            confidence += 0.10;
        }

        if ((support && ta.getRsi() > 40 && ta.getRsi() < 60) ||
                (!support && ta.getRsi() > 40 && ta.getRsi() < 60)) {
            confidence += 0.15;
        }

        confidence += 0.10;

        return Math.min(confidence, 0.95);
    }

    private double calculateBounceConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.5;

        if (ta.getVolumeRatio() > 1.3) {
            confidence += 0.25;
        } else if (ta.getVolumeRatio() > 1.0) {
            confidence += 0.15;
        } else if (ta.getVolumeRatio() > 0.8) {
            confidence += 0.10;
        }

        if (ta.getRsi() > 45 && ta.getRsi() < 55) {
            confidence += 0.20;
        }

        if (ta.getStrength() < 0.5) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.85);
    }

    private double calculateDirectionalBounceConfidence(TechnicalAnalysis ta, double vwapDistance, boolean bullish) {
        double confidence = 0.70;

        if (vwapDistance <= 0.001) confidence += 0.15;
        else if (vwapDistance <= 0.002) confidence += 0.10;

        if (ta.getVolumeRatio() >= 2.0) confidence += 0.10;
        else if (ta.getVolumeRatio() >= 1.5) confidence += 0.07;
        else if (ta.getVolumeRatio() >= 1.2) confidence += 0.03;

        if (ta.getRsi() > 40 && ta.getRsi() < 60) {
            confidence += 0.05;
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        if (isOptimalVWAPTime(now)) {
            confidence += 0.05;
        }

        return Math.min(confidence, 0.95);
    }

    // ================================================================================================
    // HELPER METHODS FOR TRADITIONAL STRATEGIES
    // ================================================================================================

    private boolean hasORBDataSufficiency(String symbol, String analysisId) {
        try {
            LocalDateTime marketOpen = LocalDateTime.now(ET_ZONE).with(LocalTime.of(9, 30));
            LocalDateTime orbEnd = marketOpen.plusMinutes(30);
            LocalDateTime now = LocalDateTime.now(ET_ZONE);

            LocalDateTime checkUntil = now.isBefore(orbEnd) ? now : orbEnd;

            List<MarketData> orbData = marketDataRepository.findRecentData(symbol, 100)
                    .stream()
                    .filter(data -> data.getTimestamp().isAfter(marketOpen) &&
                            data.getTimestamp().isBefore(checkUntil))
                    .collect(Collectors.toList());

            long expectedMinutes = Duration.between(marketOpen, checkUntil).toMinutes();
            double completeness = expectedMinutes > 0 ? (double) orbData.size() / expectedMinutes : 0;

            if (completeness < 0.80) {
                log.warn("[ORB-DATA][{}] Insufficient ORB data: {}% completeness", analysisId, (int)(completeness * 100));
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("[ORB-DATA][{}] Error checking ORB data sufficiency: {}", analysisId, e.getMessage());
            return false;
        }
    }

    private boolean checkRecentVWAPTest(List<MarketData> data, BigDecimal vwap, boolean testFromAbove) {
        if (data.size() < 3) return false;

        for (int i = data.size() - 3; i < data.size() - 1; i++) {
            MarketData bar = data.get(i);
            if (bar.getPrice() == null) continue;

            if (testFromAbove) {
                if (bar.getPrice().compareTo(vwap) > 0) return true;
            } else {
                if (bar.getPrice().compareTo(vwap) < 0) return true;
            }
        }
        return false;
    }

    private double calculateVWAPReclaimConfidence(TechnicalAnalysis ta, double penetration, boolean isCall) {
        double confidence = 0.70;

        if (penetration >= 0.001 && penetration <= 0.002) {
            confidence += 0.10;
        } else if (penetration <= 0.001) {
            confidence += 0.05;
        }

        if (ta.getVolumeRatio() > 1.5) confidence += 0.10;
        else if (ta.getVolumeRatio() > 1.2) confidence += 0.05;

        if (ta.getRsi() > 35 && ta.getRsi() < 65) confidence += 0.05;

        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(10, 25)) && now.isBefore(LocalTime.of(10, 45))) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.95);
    }

    private boolean isOptimalVWAPTime(LocalTime now) {
        if (now.isAfter(LocalTime.of(9, 50)) && now.isBefore(LocalTime.of(11, 30))) {
            return true;
        }

        if (now.isAfter(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(15, 0))) {
            return true;
        }

        return false;
    }

    private VWAPPattern detectMultiCandleVWAPPattern(List<MarketData> data, TechnicalAnalysis ta) {
        if (data.size() < 5) return VWAPPattern.NONE;

        BigDecimal vwap = ta.getVwap();

        if (detectVWAPCompression(data, vwap)) {
            return VWAPPattern.COMPRESSION;
        } else if (detectFailedBreakdownRecovery(data, vwap)) {
            return VWAPPattern.FAILED_BREAKDOWN;
        } else if (detectStairStepPattern(data, vwap)) {
            return VWAPPattern.STAIR_STEP;
        }

        return VWAPPattern.NONE;
    }

    private boolean detectVWAPCompression(List<MarketData> data, BigDecimal vwap) {
        double maxDeviation = 0;

        for (int i = data.size() - 5; i < data.size(); i++) {
            if (data.get(i).getPrice() == null || vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            double deviation = Math.abs(
                    data.get(i).getPrice().subtract(vwap)
                            .divide(vwap, 4, RoundingMode.HALF_UP).doubleValue()
            );
            maxDeviation = Math.max(maxDeviation, deviation);
        }

        return maxDeviation < 0.0015;
    }

    private boolean detectFailedBreakdownRecovery(List<MarketData> data, BigDecimal vwap) {
        if (data.size() < 4) return false;

        MarketData bar1 = data.get(data.size() - 4);
        MarketData bar2 = data.get(data.size() - 3);
        MarketData bar3 = data.get(data.size() - 2);
        MarketData current = data.get(data.size() - 1);

        if (bar1.getPrice() == null || bar2.getLow() == null || bar3.getLow() == null ||
                current.getPrice() == null || current.getLow() == null || vwap == null) {
            return false;
        }

        boolean wasAbove = bar1.getPrice().compareTo(vwap) > 0;
        boolean brokeBelow = bar2.getLow().compareTo(vwap) < 0 &&
                bar3.getLow().compareTo(vwap) < 0;
        boolean recovered = current.getPrice().compareTo(vwap) > 0 &&
                current.getLow().compareTo(vwap) >= 0;

        return wasAbove && brokeBelow && recovered;
    }

    private boolean detectStairStepPattern(List<MarketData> data, BigDecimal vwap) {
        if (data.size() < 4) return false;

        boolean allAboveVWAP = true;
        boolean higherLows = true;

        for (int i = data.size() - 4; i < data.size(); i++) {
            if (data.get(i).getLow() == null || vwap == null) {
                return false;
            }

            if (data.get(i).getLow().compareTo(vwap) < 0) {
                allAboveVWAP = false;
                break;
            }

            if (i > data.size() - 4) {
                if (data.get(i-1).getLow() == null ||
                        data.get(i).getLow().compareTo(data.get(i-1).getLow()) <= 0) {
                    higherLows = false;
                }
            }
        }

        return allAboveVWAP && higherLows;
    }

    private Signal createAdvancedORBSignal(Option option, List<Option> allOptions, TechnicalAnalysis ta, String signalType,
                                           String strategy, double confidence, String marketTrend, OpeningRange range, VolumeRequirement volReq) {
        Signal signal = createSignal(option, allOptions, ta, signalType, strategy, confidence, marketTrend);
        if (signal == null) return null;

        BigDecimal entryPrice = signal.getEntryPrice();
        boolean isHighVol = ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY;

        double targetMultiplier = "EXCEPTIONAL".equals(volReq.getTier()) ? 2.5 : isHighVol ? 2.2 : 1.8;
        signal.setTargetPrice(entryPrice.multiply(BigDecimal.valueOf(targetMultiplier)));

        double stopPercentage = "EXCEPTIONAL".equals(volReq.getTier()) ? 0.25 : isHighVol ? 0.35 : 0.30;
        signal.setStopLoss(entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(stopPercentage))));

        return signal;
    }

    private VolumeRequirement calculateBreakoutCandleVolume(TechnicalAnalysis ta) {
        double volumeRatio = ta.getVolumeRatio();

        if (volumeRatio >= 2.0) return new VolumeRequirement("EXCEPTIONAL", true, 1.0, 0.95);
        if (volumeRatio >= 1.5) return new VolumeRequirement("STRONG", true, 0.8, 0.85);
        if (volumeRatio >= 1.2) return new VolumeRequirement("ADEQUATE", true, 0.6, 0.75);
        return new VolumeRequirement("WEAK", false, 0.0, 0.0);
    }

    private boolean validateEnhancedBreakout(BigDecimal currentPrice, OpeningRange range, TechnicalAnalysis ta, LocalTime now) {
        BigDecimal rangeSize = range.getHigh().subtract(range.getLow());
        BigDecimal minRangeSize = currentPrice.multiply(BigDecimal.valueOf(0.005));
        if (rangeSize.compareTo(minRangeSize) < 0) return false;

        BigDecimal minPenetration = currentPrice.multiply(BigDecimal.valueOf(0.001));
        BigDecimal actualPenetration = currentPrice.compareTo(range.getHigh()) > 0 ?
                currentPrice.subtract(range.getHigh()) : range.getLow().subtract(currentPrice);
        if (actualPenetration.compareTo(minPenetration) < 0) return false;

        if (now.isAfter(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(10, 3))) {
            return ta.getMomentumStrength() > 0.4;
        }

        return true;
    }

    private GapAnalysis analyzeGapConditions(TechnicalAnalysis ta) {
        if (ta.getPreviousClose() == null) return new GapAnalysis(0.0, false, 0.0, "NONE");

        BigDecimal gap = ta.getCurrentPrice().subtract(ta.getPreviousClose())
                .divide(ta.getPreviousClose(), 4, RoundingMode.HALF_UP);
        double gapPercentage = gap.doubleValue();

        boolean isSignificant = Math.abs(gapPercentage) > 0.015;
        boolean isModerate = Math.abs(gapPercentage) > 0.005;

        double confidenceBoost = 0.0;
        if (isSignificant) confidenceBoost = 0.20;
        else if (isModerate) confidenceBoost = 0.10;

        String direction = gapPercentage > 0 ? "UP" : gapPercentage < 0 ? "DOWN" : "FLAT";

        return new GapAnalysis(gapPercentage, isSignificant, confidenceBoost, direction);
    }

    private double calculateEnhancedORBConfidence(TechnicalAnalysis ta, VolumeRequirement volReq,
                                                  GapAnalysis gapAnalysis, boolean bullish, LocalTime now) {
        double confidence = 0.75;

        confidence = Math.min(confidence + 0.15, volReq.getMaxConfidence());
        confidence += gapAnalysis.getConfidenceBoost();
        confidence *= getTimeBasedMultiplier(now);

        if ((bullish && ta.getMomentumStrength() > 0.4) || (!bullish && ta.getMomentumStrength() < -0.4)) {
            confidence += 0.10;
        }

        if (ta.getRsi() > 45 && ta.getRsi() < 65) confidence += 0.05;

        return Math.min(confidence, 0.95);
    }

    private double getTimeBasedMultiplier(LocalTime now) {
        if (now.isBefore(LocalTime.of(10, 15))) return 1.15;
        if (now.isBefore(LocalTime.of(10, 30))) return 1.10;
        if (now.isAfter(LocalTime.of(15, 0))) return 0.85;
        return 1.0;
    }

    private double calculateDynamicThreshold(LocalTime now, VolumeRequirement volReq) {
        double baseThreshold = 0.80;

        if (now.isAfter(LocalTime.of(15, 0))) {
            baseThreshold += 0.10;
        }

        switch (volReq.getTier()) {
            case "EXCEPTIONAL": return baseThreshold - 0.05;
            case "STRONG": return baseThreshold;
            case "ADEQUATE": return baseThreshold + 0.05;
            default: return 0.95;
        }
    }

    private OpeningRange calculate30MinuteRange(TechnicalAnalysis ta) {
        try {
            List<MarketData> recentData = marketDataRepository.findRecentData(ta.getSymbol(), 100);

            if (recentData.isEmpty()) {
                return null;
            }

            LocalDate today = LocalDate.now(ET_ZONE);
            LocalDateTime rangeStart = today.atTime(9, 30);
            LocalDateTime rangeEnd = today.atTime(10, 0);

            List<MarketData> rangeData = recentData.stream()
                    .filter(data -> data.getTimestamp() != null)
                    .filter(data -> {
                        LocalDateTime timestamp = data.getTimestamp();
                        return !timestamp.isBefore(rangeStart) && !timestamp.isAfter(rangeEnd);
                    })
                    .collect(Collectors.toList());

            if (rangeData.isEmpty()) {
                return null;
            }

            BigDecimal high = rangeData.stream()
                    .map(MarketData::getHigh)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);

            BigDecimal low = rangeData.stream()
                    .map(MarketData::getLow)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(null);

            if (high != null && low != null) {
                return new OpeningRange(high, low);
            }

            return null;

        } catch (Exception e) {
            log.error("Error calculating 30-minute opening range: {}", e.getMessage(), e);
            return null;
        }
    }

    // ================================================================================================
    // UTILITY AND HELPER METHODS
    // ================================================================================================

    private Option getOptionFromSignal(Signal signal, List<Option> options) {
        return options.stream()
                .filter(opt -> opt.getSymbol().equals(signal.getOptionSymbol()))
                .findFirst()
                .orElse(null);
    }

    private List<Option> selectStrikesBasedOnVolatility(List<Option> options, TechnicalAnalysis ta, String analysisId) {
        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal dayRange = BigDecimal.ZERO;

        QuoteResponse qqqQuote = tradierService.getQuote("QQQ");
        if (qqqQuote != null && qqqQuote.getQuote() != null) {
            Quote quote = qqqQuote.getQuote();
            if (quote.getHigh() != null && quote.getLow() != null) {
                dayRange = quote.getHigh().subtract(quote.getLow());
            }
        }

        BigDecimal priceMove = BigDecimal.ZERO;
        if (ta.getPreviousClose() != null && ta.getPreviousClose().compareTo(BigDecimal.ZERO) > 0) {
            priceMove = currentPrice.subtract(ta.getPreviousClose())
                    .abs()
                    .divide(ta.getPreviousClose(), 4, RoundingMode.HALF_UP);
        }

        int strikesFromATM;
        if (priceMove.compareTo(BigDecimal.valueOf(0.005)) > 0 ||
                dayRange.compareTo(currentPrice.multiply(BigDecimal.valueOf(0.01))) > 0) {
            strikesFromATM = 4;
            log.info("[{}] High volatility detected - selecting up to {} strikes from ATM",
                    analysisId, strikesFromATM);
        } else {
            strikesFromATM = 2;
            log.info("[{}] Normal volatility - selecting up to {} strikes from ATM",
                    analysisId, strikesFromATM);
        }

        List<Option> selected = options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    boolean isCall = "CALL".equalsIgnoreCase(option.getType());
                    boolean isPut = "PUT".equalsIgnoreCase(option.getType());

                    if (isCall) {
                        return strike.compareTo(currentPrice) >= 0 &&
                                strike.compareTo(currentPrice.add(BigDecimal.valueOf(strikesFromATM))) <= 0;
                    } else if (isPut) {
                        return strike.compareTo(currentPrice.subtract(BigDecimal.valueOf(strikesFromATM))) >= 0 &&
                                strike.compareTo(currentPrice) <= 0;
                    }
                    return false;
                })
                .sorted((o1, o2) -> {
                    BigDecimal dist1 = o1.getStrikePrice().subtract(currentPrice).abs();
                    BigDecimal dist2 = o2.getStrikePrice().subtract(currentPrice).abs();
                    return dist1.compareTo(dist2);
                })
                .collect(Collectors.toList());

        selected.forEach(opt -> {
            BigDecimal distance = opt.getStrikePrice().subtract(currentPrice);
            log.info("[{}] Selected: {} ${} (${} from current, Vol: {}, OI: {})",
                    analysisId, opt.getType(), opt.getStrikePrice(),
                    distance.compareTo(BigDecimal.ZERO) > 0 ? "+" + distance : distance,
                    opt.getVolume(), opt.getOpenInterest());
        });

        return selected;
    }

    private MarketConditions getMarketConditions() {
        MarketConditions conditions = new MarketConditions();
        conditions.setVix(getVixLevel());
        conditions.setMarketBreadth(calculateMarketBreadth());
        conditions.setInternals(getMarketInternals());
        return conditions;
    }

    private double getVixLevel() {
        return 18.0;
    }

    private double calculateMarketBreadth() {
        return 0.5;
    }

    private MarketInternals getMarketInternals() {
        return new MarketInternals();
    }

    private boolean isGoodTradingTime(LocalTime now) {
        return (now.isAfter(PRIME_WINDOW_1_START) && now.isBefore(PRIME_WINDOW_1_END)) ||
                (now.isAfter(PRIME_WINDOW_2_START) && now.isBefore(PRIME_WINDOW_2_END)) ||
                (now.isAfter(PRIME_WINDOW_3_START) && now.isBefore(PRIME_WINDOW_3_END)) ||
                (now.isAfter(FINAL_WINDOW_START) && now.isBefore(FINAL_WINDOW_END));
    }

    private double getTimeWindowMultiplier(LocalTime now) {
        if ((now.isAfter(PRIME_WINDOW_1_START) && now.isBefore(PRIME_WINDOW_1_END))) {
            return 1.15;
        } else if ((now.isAfter(PRIME_WINDOW_2_START) && now.isBefore(PRIME_WINDOW_2_END))) {
            return 1.10;
        } else if ((now.isAfter(PRIME_WINDOW_3_START) && now.isBefore(PRIME_WINDOW_3_END))) {
            return 1.20;
        } else if ((now.isAfter(FINAL_WINDOW_START) && now.isBefore(FINAL_WINDOW_END))) {
            return 1.05;
        }
        return 0.8;
    }

    private void logTechnicalAnalysis(TechnicalAnalysis ta, String analysisId) {
        log.info("[{}] TA Results - Price: ${}, VWAP: ${}, Regime: {}",
                analysisId, ta.getCurrentPrice(), ta.getVwap(), ta.getMarketRegime());
        log.info("[{}] Volume - Current: {}, Ratio: {}, High: {}",
                analysisId, ta.getCurrentVolume(),
                String.format("%.2f", ta.getVolumeRatio()),
                ta.isHighVolume());
        log.info("[{}] Momentum - RSI: {}, MACD: {}, Divergence: {}",
                analysisId,
                String.format("%.1f", ta.getRsi()),
                ta.getMacdSignal(),
                ta.isHasRsiDivergence() || ta.isHasMacdDivergence());
    }

    private List<Option> filterOptionsByMarketRegime(List<Option> options, TechnicalAnalysis ta, String analysisId) {
        MarketRegime regime = ta.getMarketRegime();
        DynamicParameters params = ta.getDynamicParameters();

        LocalTime now = LocalTime.now(ET_ZONE);
        int adjustedMinVolume = getAdjustedMinVolume(now);
        double adjustedMinIV = minIV;

        switch (regime) {
            case HIGH_VOLATILITY:
                adjustedMinVolume = (int)(adjustedMinVolume * 0.7);
                adjustedMinIV = minIV * 1.2;
                break;
            case LOW_VOLATILITY:
                adjustedMinVolume = (int)(adjustedMinVolume * 1.3);
                adjustedMinIV = minIV * 0.8;
                break;
            case OPENING_RANGE:
                adjustedMinVolume = (int)(adjustedMinVolume * 0.5);
                break;
            default:
                break;
        }

        if (params != null) {
            adjustedMinVolume = (int)(adjustedMinVolume * params.getMinVolumeMultiplier());
            adjustedMinIV = adjustedMinIV * params.getMinIVMultiplier();
        }

        log.info("[{}] Base filtering - Volume: {}, IV: {}",
                analysisId, adjustedMinVolume,String.format("%.3f",adjustedMinIV));

        List<Option> basicFiltered = options.stream()
                .filter(Option::isValid)
                .collect(Collectors.toList());

        log.info("[{}] After basic filtering: {} options", analysisId, basicFiltered.size());

        return filterByConfidenceAdjustedStrikes(basicFiltered, ta, 0.0, analysisId);
    }

    private List<Option> filterByConfidenceAdjustedStrikes(List<Option> options,
                                                           TechnicalAnalysis ta,
                                                           double expectedConfidence,
                                                           String analysisId) {
        BigDecimal currentPrice = ta.getCurrentPrice();

        List<Option> atmOptions = options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    if (strike == null) return false;

                    double distanceFromPrice = Math.abs(strike.doubleValue() - currentPrice.doubleValue());
                    return distanceFromPrice <= 0.50;
                })
                .filter(option -> option.getVolume() != null && option.getVolume() >= 100)
                .filter(option -> option.getOpenInterest() != null && option.getOpenInterest() >= 500)
                .sorted((o1, o2) -> {
                    double dist1 = Math.abs(o1.getStrikePrice().doubleValue() - currentPrice.doubleValue());
                    double dist2 = Math.abs(o2.getStrikePrice().doubleValue() - currentPrice.doubleValue());
                    return Double.compare(dist1, dist2);
                })
                .collect(Collectors.toList());

        log.info("[ATM-FILTER][{}] ATM filtering result: {} options selected (from {} total)",
                analysisId, atmOptions.size(), options.size());

        if (atmOptions.isEmpty()) {
            log.warn("[ATM-FILTER][{}] No ATM options found - expanding to $1.00 range", analysisId);

            return options.stream()
                    .filter(option -> {
                        BigDecimal strike = option.getStrikePrice();
                        return strike != null &&
                                Math.abs(strike.doubleValue() - currentPrice.doubleValue()) <= 1.00;
                    })
                    .collect(Collectors.toList());
        }

        return atmOptions;
    }

    private boolean quickSignalValidation(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                          String marketTrend, String analysisId) {
        if ("NEUTRAL".equals(marketTrend)) {
            return false;
        }

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean aligned = (isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend));

        if (!aligned) {
            return false;
        }

        Integer volume = option.getVolume();
        Integer openInterest = option.getOpenInterest();
        if ((volume == null || volume < 10) && (openInterest == null || openInterest < 50)) {
            return false;
        }

        if (option.getSpreadPercentage() > 15.0) {
            return false;
        }

        return true;
    }

    private boolean isSignalAlignedWithTrend(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                             String marketTrend, String analysisId) {

        if ("NEUTRAL".equals(marketTrend)) {
            log.warn("[{}] ❌ BLOCKED: {} {} - Market is NEUTRAL (NO TRADES ALLOWED)",
                    analysisId, option.getType(), option.getStrikePrice());
            return false;
        }

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean aligned = (isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend));

        log.info("[{}] Trend Check: {} option vs {} market trend = {}",
                analysisId, option.getType(), marketTrend, aligned ? "ALIGNED" : "MISALIGNED");

        return aligned;
    }

    private int getAdjustedMinVolume(LocalTime now) {
        if (now.isBefore(LocalTime.of(10, 30))) {
            return minVolume / 2;
        } else if (now.isAfter(LocalTime.of(15, 0))) {
            return minVolume / 2;
        }
        return minVolume;
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

    private boolean isOrderSuccessful(OrderResponse response) {
        return response != null &&
                (response.getStatus() == null || !"error".equals(response.getStatus()));
    }

    public String getOrderId(OrderResponse response) {
        return response.getOrder() != null ? response.getId() : "unknown";
    }

    private BigDecimal getCurrentPrice(String optionSymbol) {
        try {
            QuoteResponse quote = tradierService.getQuote(optionSymbol);
            return quote != null && quote.getQuote() != null ?
                    quote.getQuote().getLast() : BigDecimal.ZERO;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private double calculateTradePerformance(Trade trade) {
        if (trade.getExitPrice() != null && trade.getEntryPrice() != null &&
                trade.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
            return trade.getExitPrice().subtract(trade.getEntryPrice())
                    .divide(trade.getEntryPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        return 0.0;
    }

    private LocalDate extractExpirationFromOptionSymbol(String optionSymbol) {
        try {
            if (optionSymbol == null || optionSymbol.length() < 10) {
                return LocalDate.now(ET_ZONE);
            }

            String datePart;
            if (optionSymbol.contains("_")) {
                String afterUnderscore = optionSymbol.split("_")[1];
                datePart = afterUnderscore.substring(0, 6);

                int month = Integer.parseInt(datePart.substring(0, 2));
                int day = Integer.parseInt(datePart.substring(2, 4));
                int year = 2000 + Integer.parseInt(datePart.substring(4, 6));

                return LocalDate.of(year, month, day);
            } else {
                datePart = optionSymbol.substring(3, 9);

                int year = 2000 + Integer.parseInt(datePart.substring(0, 2));
                int month = Integer.parseInt(datePart.substring(2, 4));
                int day = Integer.parseInt(datePart.substring(4, 6));

                return LocalDate.of(year, month, day);
            }

        } catch (Exception e) {
            log.warn("Could not extract expiration from option symbol: {} - Error: {}",
                    optionSymbol, e.getMessage());
            return LocalDate.now(ET_ZONE);
        }
    }

    // ================================================================================================
    // SIGNAL CREATION AND TRADE MANAGEMENT
    // ================================================================================================

    private Signal createSignal(Option option, List<Option> allOptions, TechnicalAnalysis ta, String signalType,
                                String strategy, double confidence, String marketTrend) {
        if (option == null || ta == null || signalType == null || strategy == null) {
            log.error("Cannot create signal with null parameters");
            return null;
        }

        if (!strategy.contains("AI_LEADER_LAG") && !strategy.contains("ENHANCED_AI") &&
                !isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, "signal-creation")) {
            log.info("[BLOCKED] {} {} not aligned with {} trend",
                    option.getType(), option.getStrikePrice(), marketTrend);
            return null;
        }

        confidence = Math.max(0.0, Math.min(1.0, confidence));

        try {
            Signal signal = new Signal();
            signal.setSymbol(ta.getSymbol());
            signal.setOptionSymbol(option.getSymbol());
            signal.setSignalType(signalType);
            signal.setStrategy(strategy);
            signal.setConfidence(confidence);
            signal.setTimestamp(LocalDateTime.now());
            signal.setExecuted(false);
            signal.setEntryAssumptionPrice(ta.getCurrentPrice());
            signal.setMarketRegime(ta.getMarketRegime());
            signal.setMarketTrend(marketTrend);

            if (option.getDelta() != null) {
                signal.setDelta(option.getDelta());
            }
            if (option.getGamma() != null) {
                signal.setGamma(option.getGamma());
            }
            if (option.getTheta() != null) {
                signal.setTheta(option.getTheta());
            }
            if (option.getVega() != null) {
                signal.setVega(option.getVega());
            }
            if (option.getImpliedVolatility() != null) {
                signal.setImpliedVolatility(option.getImpliedVolatility());
            }

            BigDecimal atr = ta.getAverageTrueRange();
            if (atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) {
                atr = ta.getCurrentPrice().multiply(BigDecimal.valueOf(0.005));
            }

            BigDecimal optionPrice = option.getMidPrice();
            if (optionPrice == null || optionPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Invalid option price for {}: {}", option.getSymbol(), optionPrice);
                return null;
            }

            signal.setEntryPrice(optionPrice);

            BigDecimal targetPrice = calculateDynamicTargetPrice(optionPrice, atr, strategy, ta, marketTrend, option);
            BigDecimal stopLoss = calculateDynamicStopLoss(optionPrice, atr, strategy, ta, marketTrend, option);

            signal.setTargetPrice(targetPrice);
            signal.setStopLoss(stopLoss);

            return signal;

        } catch (Exception e) {
            log.error("Error creating signal: {}", e.getMessage(), e);
            return null;
        }
    }

    private BigDecimal calculateDynamicTargetPrice(BigDecimal entryPrice, BigDecimal atr, String strategy,
                                                   TechnicalAnalysis ta, String marketTrend, Option option) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean isAISignal = strategy.contains("AI_LEADER_LAG") || strategy.contains("ENHANCED_AI");

        boolean isAligned = isAISignal ||
                ((isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend)));

        BigDecimal targetMultiplier;

        if (isAligned) {
            if (strategy.contains("ENHANCED_AI")) {
                targetMultiplier = BigDecimal.valueOf(3.0);
            } else if (strategy.contains("AI_LEADER_LAG")) {
                targetMultiplier = BigDecimal.valueOf(2.5);
            } else if (strategy.contains("30MIN_ORB")) {
                targetMultiplier = BigDecimal.valueOf(2.2);
            } else if (strategy.contains("VWAP_BREAKOUT")) {
                targetMultiplier = BigDecimal.valueOf(2.0);
            } else if (strategy.contains("VWAP_REVERSION")) {
                targetMultiplier = BigDecimal.valueOf(1.7);
            } else {
                targetMultiplier = BigDecimal.valueOf(1.8);
            }
        } else {
            targetMultiplier = BigDecimal.valueOf(1.4);
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        long minutesToClose = Duration.between(now, LocalTime.of(16, 0)).toMinutes();

        if (minutesToClose < 120) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(0.7));
        } else if (minutesToClose < 180) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(0.85));
        }

        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(1.15));
        }

        BigDecimal targetPrice = entryPrice.multiply(targetMultiplier);
        return targetPrice.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateDynamicStopLoss(BigDecimal entryPrice, BigDecimal atr, String strategy,
                                                TechnicalAnalysis ta, String marketTrend, Option option) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean isAISignal = strategy.contains("AI_LEADER_LAG") || strategy.contains("ENHANCED_AI");

        boolean isAligned = isAISignal ||
                ((isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend)));

        BigDecimal baseStopPercentage;

        if (isAligned) {
            if (strategy.contains("ENHANCED_AI")) {
                baseStopPercentage = BigDecimal.valueOf(0.20);
            } else if (strategy.contains("AI_LEADER_LAG")) {
                baseStopPercentage = BigDecimal.valueOf(0.30);
            } else if (strategy.contains("30MIN_ORB")) {
                baseStopPercentage = BigDecimal.valueOf(0.45);
            } else if (strategy.contains("VWAP_BREAKOUT")) {
                baseStopPercentage = BigDecimal.valueOf(0.35);
            } else if (strategy.contains("VWAP_REVERSION")) {
                baseStopPercentage = BigDecimal.valueOf(0.50);
            } else if (strategy.contains("VWAP_RECLAIM")) {
                baseStopPercentage = BigDecimal.valueOf(0.30);
            } else {
                baseStopPercentage = BigDecimal.valueOf(0.40);
            }
        } else {
            baseStopPercentage = BigDecimal.valueOf(0.25);
        }

        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            baseStopPercentage = baseStopPercentage.multiply(BigDecimal.valueOf(1.2));
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(14, 30))) {
            baseStopPercentage = baseStopPercentage.multiply(BigDecimal.valueOf(0.8));
        }

        BigDecimal stopLoss = entryPrice.multiply(BigDecimal.ONE.subtract(baseStopPercentage));

        if (stopLoss.compareTo(BigDecimal.valueOf(0.05)) < 0) {
            stopLoss = BigDecimal.valueOf(0.05);
        }

        return stopLoss.setScale(2, RoundingMode.HALF_UP);
    }

    private Trade createSuccessfulTrade(Signal signal, BigDecimal fillPrice, String analysisId) {
        Trade trade = new Trade();

        trade.setSymbol(signal.getSymbol());
        trade.setOptionSymbol(signal.getOptionSymbol());
        trade.setStrategy(signal.getStrategy());
        trade.setQuantity(1);
        trade.setActualQuantity(1);

        Option option = getOptionFromSignal(signal, null);
        if (option != null) {
            trade.setStrikePrice(option.getStrikePrice());
            trade.setSide(option.getType());
            trade.setType(option.getType());
            trade.setVolume(option.getVolume());
        }
        trade.setEntryPrice(fillPrice);
        trade.setEntryTime(LocalDateTime.now());
        trade.setCreatedAt(ZonedDateTime.now());
        trade.setStatus("OPEN");
        trade.setCurrentPrice(fillPrice);
        trade.setTargetPrice(signal.getTargetPrice());
        trade.setStopLoss(signal.getStopLoss());

        trade.setSignalId(signal.getId());
        trade.setOrderStatus("FILLED");
        trade.setOrderType("MARKET");
        trade.setOrderSide("buy_to_open");

        trade.setMarketTrend(signal.getMarketTrend());
        trade.setTimeSlot(determineTimeSlot(LocalTime.now()));
        trade.setExpirationDate(signal.getExpirationDate());
        trade.setDaysToExpiry(signal.getDaysToExpiry());

        trade.setImpliedVolatility(signal.getImpliedVolatility());
        trade.setDelta(signal.getDelta());
        trade.setGamma(signal.getGamma());
        trade.setTheta(signal.getTheta());
        trade.setVega(signal.getVega());

        trade.setAction("BUY");

        trade.setEntryPrice(fillPrice);
        trade.setCurrentPrice(fillPrice);
        trade.setFillPrice(fillPrice);
        trade.setExecutionPrice(fillPrice);
        trade.setTargetPrice(signal.getTargetPrice());
        trade.setOriginalTarget(signal.getTargetPrice());
        trade.setStopLoss(signal.getStopLoss());
        trade.setOriginalStop(signal.getStopLoss());

        try {
            QuoteResponse quote = tradierService.getQuote(signal.getOptionSymbol());
            if (quote != null && quote.getQuote() != null) {
                Quote q = quote.getQuote();
                trade.setBidAtEntry(q.getBid());
                trade.setAskAtEntry(q.getAsk());
                if (q.getBid() != null && q.getAsk() != null) {
                    BigDecimal spread = q.getAsk().subtract(q.getBid());
                    trade.setSpreadAtEntry(spread);
                    if (fillPrice.compareTo(BigDecimal.ZERO) > 0) {
                        double spreadPercent = spread.divide(fillPrice, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100)).doubleValue();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not get bid/ask data for trade creation: {}", e.getMessage());
        }

        trade.setRealizedPnl(BigDecimal.ZERO);
        trade.setUnrealizedPnl(BigDecimal.ZERO);
        trade.setMaxProfitReached(BigDecimal.ZERO);
        trade.setMaxDrawdown(BigDecimal.ZERO);
        trade.setHighestPrice(fillPrice);
        trade.setLowestPrice(fillPrice);

        LocalDateTime now = LocalDateTime.now();
        ZonedDateTime zonedNow = ZonedDateTime.now();

        trade.setCreatedAt(zonedNow);
        trade.setEntryTime(now);
        trade.setSignalGeneratedAt(signal.getCreatedAt());
        trade.setExecutionAttemptedAt(now);
        trade.setOrderFilledAt(now);
        trade.setLastSyncTime(now);

        String orderId = signal.getMetadata().get("orderId");
        trade.setOrderId(orderId);
        trade.setOrderStatus("FILLED");
        trade.setOrderType("MARKET");
        trade.setOrderSide("buy_to_open");
        trade.setOrderDuration("DAY");
        trade.setRetryCount(0);

        trade.setMarketTrend(signal.getMarketTrend());
        trade.setMarketRegime(signal.getMarketRegime());
        trade.setTimeSlot(determineTimeSlot(LocalTime.now()));

        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(signal.getSymbol());
            if (ta != null) {
                trade.setUnderlyingPriceAtEntry(ta.getCurrentPrice());
                trade.setMarketTrendStrength(ta.getStrength());
            }
        } catch (Exception e) {
            log.debug("Could not get underlying price for trade: {}", e.getMessage());
        }

        trade.setVixAtEntry(getVixLevel());
        trade.setMarketBreadthAtEntry(calculateMarketBreadth());

        LocalDate expirationDate = extractExpirationFromOptionSymbol(signal.getOptionSymbol());
        trade.setExpirationDate(expirationDate.atTime(16, 0).atZone(ET_ZONE));

        LocalDateTime expiryTime = expirationDate.atTime(16, 0);
        long minutesToExpiry = Duration.between(now, expiryTime).toMinutes();
        trade.setMinutesToExpiry((int) minutesToExpiry);
        trade.setDaysToExpiry(signal.getDaysToExpiry());

        double timeDecayFactor = minutesToExpiry > 0 ? 1.0 / minutesToExpiry : 1.0;
        trade.setTimeDecayFactor(timeDecayFactor);

        trade.setImpliedVolatility(signal.getImpliedVolatility());
        trade.setDelta(signal.getDelta());
        trade.setGamma(signal.getGamma());
        trade.setTheta(signal.getTheta());
        trade.setVega(signal.getVega());
        trade.setRho(signal.getRho());

        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(signal.getSymbol());
            if (ta != null) {
                trade.setVolumeRatio(ta.getVolumeRatio());
                trade.setMomentumStrength(ta.getMomentumStrength());
                trade.setRsiAtEntry(ta.getRsi());

                if (ta.getVwap() != null && ta.getCurrentPrice() != null) {
                    double vwapDeviation = ta.getCurrentPrice().subtract(ta.getVwap())
                            .divide(ta.getVwap(), 4, RoundingMode.HALF_UP).doubleValue();
                    trade.setVwapDeviation(vwapDeviation);
                }

                if (ta.getAverageTrueRange() != null && ta.getCurrentPrice() != null) {
                    double atrRatio = ta.getAverageTrueRange()
                            .divide(ta.getCurrentPrice(), 4, RoundingMode.HALF_UP).doubleValue();
                    trade.setAtrRatio(atrRatio);
                }

                if (ta.getMacdSignal() != null) {
                    try {
                        trade.setMacdAtEntry(Double.parseDouble(ta.getMacdSignal()));
                    } catch (NumberFormatException e) {
                        // MACD signal might be text like "BUY" or "SELL"
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not populate technical analysis data: {}", e.getMessage());
        }

        BigDecimal positionValue = fillPrice.multiply(BigDecimal.valueOf(100));
        trade.setMaxRisk(positionValue);
        trade.setMaxProfitPotential(signal.getTargetPrice().multiply(BigDecimal.valueOf(100)));

        if (signal.getStopLoss() != null && signal.getStopLoss().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal risk = fillPrice.subtract(signal.getStopLoss());
            BigDecimal reward = signal.getTargetPrice().subtract(fillPrice);
            if (risk.compareTo(BigDecimal.ZERO) > 0) {
                double riskRewardRatio = reward.divide(risk, 4, RoundingMode.HALF_UP).doubleValue();
                trade.setRiskRewardRatio(riskRewardRatio);
            }
        }

        trade.setSignalConfidence(signal.getConfidence());
        trade.setOriginalConfidence(signal.getConfidence());

        if (trade.getRiskRewardRatio() != null) {
            double expectedValue = (signal.getConfidence() * trade.getRiskRewardRatio()) -
                    ((1 - signal.getConfidence()) * 1.0);
            trade.setExpectedValue(expectedValue);
        }

        trade.setStatus("OPEN");
        trade.setAutoClosed(false);
        trade.setManualIntervention(false);
        trade.setTrailingActivated(false);
        trade.setStopAdjustmentsCount(0);
        trade.setTargetAdjustmentsCount(0);

        trade.setSignalId(signal.getId());
        trade.setStrategyInstanceId(analysisId);
        trade.setExecutionSessionId(UUID.randomUUID().toString().substring(0, 8));

        String learningPhase = signal.getMetadata().get("learningPhase");
        trade.setLearningPhase(learningPhase != null ? learningPhase : "MARKET_ONLY");
        trade.setModelVersion("1.0");
        trade.setFeatureSetVersion("2024.1");

        trade.setPositionSizePercentage(1.0);
        trade.setPortfolioHeat(calculateCurrentPortfolioHeat());

        log.info("[TRADE-CREATE][{}] Created comprehensive trade: {} - Status: OPEN with {} populated fields",
                analysisId, trade.getOptionSymbol(), countPopulatedFields(trade));

        trade.setMaxProfitPotential(signal.getTargetPrice().multiply(BigDecimal.valueOf(100)));

        trade.setSignalGeneratedAt(signal.getCreatedAt());
        trade.setExecutionAttemptedAt(LocalDateTime.now());
        trade.setOrderFilledAt(LocalDateTime.now());

        log.info("[TRADE-CREATE][{}] Created SUCCESSFUL trade: {} - Status: OPEN",
                analysisId, trade.getOptionSymbol());

        return trade;
    }

    private int countPopulatedFields(Trade trade) {
        int count = 0;
        java.lang.reflect.Field[] fields = Trade.class.getDeclaredFields();

        for (java.lang.reflect.Field field : fields) {
            field.setAccessible(true);
            try {
                Object value = field.get(trade);
                if (value != null) {
                    if (value instanceof String && !((String) value).isEmpty()) count++;
                    else if (!(value instanceof String)) count++;
                }
            } catch (IllegalAccessException e) {
                // Ignore
            }
        }
        return count;
    }

    private double calculateCurrentPortfolioHeat() {
        try {
            long openTrades = tradeRepository.findAll().stream()
                    .filter(trade -> "OPEN".equals(trade.getStatus()))
                    .count();

            return Math.min(openTrades * 1.0, 10.0);

        } catch (Exception e) {
            return 1.0;
        }
    }

    private void enrichTradeWithBayesianData(Trade trade, Signal signal) {
        String bayesianConfidence = signal.getMetadata().get("bayesianConfidence");
        if (bayesianConfidence != null) {
            try {
                trade.setBayesianProbability(Double.valueOf(bayesianConfidence));
                trade.setAdjustedConfidence(Double.valueOf(bayesianConfidence));
            } catch (NumberFormatException e) {
                log.warn("Invalid bayesian confidence format: {}", bayesianConfidence);
            }
        }

        trade.setSignalConfidence(signal.getConfidence());
        trade.setOriginalConfidence(signal.getConfidence());

        String learningPhase = signal.getMetadata().get("learningPhase");
        if (learningPhase != null) {
            trade.setLearningPhase(learningPhase);
        }

        String thresholdMethod = signal.getMetadata().get("thresholdMethod");
        if (thresholdMethod != null) {
            trade.setThresholdOptimizationMethod(thresholdMethod);
        }

        trade.setBayesianAnalysisTimestamp(ZonedDateTime.now());
    }

    private void saveFailedTrade(Signal signal, String failureReason, String errorMessage, String analysisId) {
        Trade trade = new Trade();

        trade.setSymbol(signal.getSymbol());
        trade.setOptionSymbol(signal.getOptionSymbol());
        trade.setStrategy(signal.getStrategy());
        trade.setQuantity(1);
        trade.setEntryPrice(BigDecimal.ZERO);
        trade.setEntryTime(LocalDateTime.now());
        trade.setStatus("FAILED");
        trade.setExitReason(failureReason);
        trade.setSignalId(signal.getId());
        trade.setFailureReason(failureReason);
        trade.setErrorMessage(errorMessage);

        tradeRepository.save(trade);

        log.warn("[TRADE-CREATE][{}] Created FAILED trade: {} - Status: FAILED, Reason: {}",
                analysisId, trade.getOptionSymbol(), failureReason);
    }

    private void saveFailedSignal(Signal signal, String failureReason, String errorMessage) {
        signal.setStatus("FAILED");
        signal.setFailureReason(failureReason);
        signal.setErrorMessage(errorMessage);
        signal.getMetadata().put("failedAt", LocalDateTime.now().toString());

        signalRepository.save(signal);
    }

    private void saveBayesianRejectedSignal(Signal signal, IntegratedBayesianMLSystem.ExecutionDecision decision, String analysisId) {
        signal.setStatus("BAYESIAN_REJECTED");
        signal.setFailureReason(decision.getDecision());
        signal.setErrorMessage(decision.getReasoning());
        signal.getMetadata().put("rejectedAt", LocalDateTime.now().toString());
        signal.getMetadata().put("confidenceGap", String.valueOf(decision.getConfidenceGap()));

        signalRepository.save(signal);

        Trade rejectedTrade = new Trade();
        rejectedTrade.setSymbol(signal.getSymbol());
        rejectedTrade.setOptionSymbol(signal.getOptionSymbol());
        rejectedTrade.setStrategy(signal.getStrategy());
        rejectedTrade.setQuantity(1);
        rejectedTrade.setEntryPrice(BigDecimal.ZERO);
        rejectedTrade.setEntryTime(LocalDateTime.now());
        rejectedTrade.setStatus("BAYESIAN_REJECTED");
        rejectedTrade.setExitReason(decision.getDecision());
        rejectedTrade.setSignalId(signal.getId());
        rejectedTrade.setFailureReason("BAYESIAN_FILTER");
        rejectedTrade.setErrorMessage(decision.getReasoning());

        tradeRepository.save(rejectedTrade);

        log.warn("[BAYESIAN-REJECT][{}] Saved rejected signal and trade: {} - Reason: {}",
                analysisId, signal.getOptionSymbol(), decision.getReasoning());
    }

    private boolean attemptSignalExecution(Signal signal, String analysisId) {
        try {
            signal.setStatus("ATTEMPTING");

            OrderRequest orderRequest = new OrderRequest();
            orderRequest.setSymbol(signal.getOptionSymbol());
            orderRequest.setSide("buy_to_open");
            orderRequest.setQuantity(1);
            orderRequest.setType("market");
            orderRequest.setDuration("day");

            log.info("[EXECUTION][{}] Placing BUY_TO_OPEN order for: {}", analysisId, signal.getOptionSymbol());

            OrderResponse orderResponse = tradierService.placeOrder(orderRequest);

            if (orderResponse != null && isOrderSuccessful(orderResponse)) {
                String orderId = getOrderId(orderResponse);

                log.info("[EXECUTION][{}] ✅ BUY_TO_OPEN order placed: {} (Order ID: {})",
                        analysisId, signal.getOptionSymbol(), orderId);

                signal.setFillPrice(getCurrentPrice(signal.getOptionSymbol()));
                signal.setActualQuantity(1);
                signal.getMetadata().put("orderId", orderId);

                return true;

            } else {
                log.warn("[EXECUTION][{}] ❌ BUY_TO_OPEN Order REJECTED: {} - Response: {}",
                        analysisId, signal.getOptionSymbol(), orderResponse);
                return false;
            }

        } catch (Exception e) {
            log.error("[EXECUTION][{}] ❌ BUY_TO_OPEN Order placement exception: {} - {}",
                    analysisId, signal.getOptionSymbol(), e.getMessage(), e);
            return false;
        }
    }

    private void sendSuccessNotification(Trade trade, Signal signal, SHAPExplainerService.SHAPExplanation explanation, String analysisId) {
        if (signal.getConfidence() >= 0.70) {
            String leaderInfo = "";
            if (signal.getStrategy().contains("AI_LEADER_LAG") || signal.getStrategy().contains("ENHANCED_AI")) {
                String leader = signal.getMetadata().get("leaderStock");
                leaderInfo = leader != null ? "\nLeader: " + leader : "";
            }

            telegramService.sendMessage(String.format(
                    "✅ EXECUTION SUCCESS\n" +
                            "Strategy: %s\n" +
                            "Option: %s\n" +
                            "Entry: $%.2f\n" +
                            "Confidence: %d%% (SHAP: %d%%)\n" +
                            "Target: $%.2f | Stop: $%.2f\n" +
                            "Trade ID: %s%s\n\n" +
                            "🧠 AI Explanation:\n" +
                            "Top Factors: %s\n" +
                            "✅ Position LIVE - Monitoring active",
                    signal.getStrategy(), signal.getOptionSymbol(),
                    signal.getEntryPrice().doubleValue(),
                    (int)(signal.getConfidence() * 100),
                    (int)(explanation.getExplanationStrength() * 100),
                    signal.getTargetPrice().doubleValue(),
                    signal.getStopLoss().doubleValue(),
                    trade.getId(), leaderInfo,
                    String.join(", ", explanation.getTopPositiveFactors())
            ));
        }
    }



    // ================================================================================================
    // SIGNAL ATTRIBUTE LEARNING - ENHANCED
    // ================================================================================================

    public class SignalAttributeLearning {
        private final Map<String, AttributePattern> successfulPatterns = new ConcurrentHashMap<>();
        private final Map<String, Double> dynamicThresholds = new ConcurrentHashMap<>();
        private final Map<String, SignalVerification> pendingVerifications = new ConcurrentHashMap<>();

        public void captureSignalGeneration(Signal signal, TechnicalAnalysis ta, String analysisId) {
            try {
                SignalVerification verification = new SignalVerification();
                verification.signalId = signal.getId();
                verification.strategy = signal.getStrategy();
                verification.generatedAt = LocalDateTime.now();
                verification.confidence = signal.getConfidence();

                verification.attributes = new TechnicalAttributes();
                verification.attributes.vwapDistance = ta.getPriceToVwapRatio();
                verification.attributes.vwapTrend = ta.getVwapTrend();
                verification.attributes.rsi = ta.getRsi();
                verification.attributes.macdSignal = ta.getMacdSignal() != null ? ta.getMacdSignal() : "NEUTRAL";
                verification.attributes.volumeRatio = ta.getVolumeRatio();
                verification.attributes.momentumStrength = ta.getMomentumStrength();
                verification.attributes.marketRegime = ta.getMarketRegime().toString();
                verification.attributes.extendedFromVwap = ta.isExtendedFromVwap();
                verification.attributes.hasRsiDivergence = ta.isHasRsiDivergence();
                verification.attributes.hasMacdDivergence = ta.isHasMacdDivergence();
                verification.attributes.priceLevel = ta.getCurrentPrice().doubleValue();
                verification.attributes.atr = ta.getAverageTrueRange() != null ? ta.getAverageTrueRange().doubleValue() : 0;
                verification.attributes.strength = ta.getStrength();
                verification.attributes.timeOfDay = LocalTime.now(ET_ZONE).toSecondOfDay();

                Optional<POCData> pocData = pocDataRepository.findLatestPOCByTimeframe(ta.getSymbol(), "30m");
                if (!pocData.isEmpty()) {
                    POCData latest = pocData.get();
                    verification.attributes.pocLevel = latest.getPocPrice().doubleValue();
                    verification.attributes.pocDistance = Math.abs(ta.getCurrentPrice().subtract(latest.getPocPrice())
                            .divide(latest.getPocPrice(), 4, RoundingMode.HALF_UP).doubleValue());
                }

                pendingVerifications.put(signal.getId().toString(), verification);

                log.debug("[AI-CAPTURE][{}] Captured attributes for signal {}", analysisId, signal.getId());

            } catch (Exception e) {
                log.error("[AI-CAPTURE][{}] Error capturing signal attributes: {}", analysisId, e.getMessage());
            }
        }

        public void captureMissedOpportunity(TechnicalAnalysis ta, String marketTrend, String analysisId) {
            try {
                String missedKey = "MISSED_" + UUID.randomUUID().toString().substring(0, 8);

                SignalVerification missed = new SignalVerification();
                missed.signalId = null;
                missed.strategy = "NO_SIGNAL";
                missed.generatedAt = LocalDateTime.now();
                missed.confidence = 0.0;

                missed.attributes = new TechnicalAttributes();
                missed.attributes.vwapDistance = ta.getPriceToVwapRatio();
                missed.attributes.rsi = ta.getRsi();
                missed.attributes.volumeRatio = ta.getVolumeRatio();
                missed.attributes.momentumStrength = ta.getMomentumStrength();
                missed.attributes.marketRegime = ta.getMarketRegime().toString();
                missed.attributes.timeOfDay = LocalTime.now(ET_ZONE).toSecondOfDay();

                pendingVerifications.put(missedKey, missed);

            } catch (Exception e) {
                log.debug("[AI-MISSED] Error capturing missed opportunity: {}", e.getMessage());
            }
        }

        @Async
        public void verifyAndLearn(String verificationId) {
            try {
                Thread.sleep(300000);

                SignalVerification verification = pendingVerifications.get(verificationId);
                if (verification == null) return;

                boolean wasSuccessful = false;
                double actualPnL = 0.0;

                if (verification.signalId != null) {
                    Optional<Trade> trade = tradeRepository.findBySignalId(verification.signalId);
                    if (trade.isPresent()) {
                        Trade t = trade.get();
                        wasSuccessful = t.getExitPrice() != null &&
                                t.getExitPrice().compareTo(t.getEntryPrice()) > 0;
                        actualPnL = calculateTradePerformance(t);
                    }
                } else {
                    QuoteResponse quote = tradierService.getQuote("QQQ");
                    if (quote != null && quote.getQuote() != null) {
                        double priceChange = quote.getQuote().getLast()
                                .subtract(BigDecimal.valueOf(verification.attributes.priceLevel))
                                .doubleValue();
                        wasSuccessful = Math.abs(priceChange) > 0.50;
                    }
                }

                updatePatternLearning(verification, wasSuccessful, actualPnL);
                adjustDynamicThresholds(verification, wasSuccessful);
                persistLearning(verification, wasSuccessful, actualPnL);

                pendingVerifications.remove(verificationId);

            } catch (Exception e) {
                log.error("[AI-VERIFY] Error in verification: {}", e.getMessage());
            }
        }

        private void updatePatternLearning(SignalVerification verification, boolean success, double pnl) {
            String patternKey = encodeAttributePattern(verification.attributes);

            AttributePattern pattern = successfulPatterns.computeIfAbsent(patternKey,
                    k -> new AttributePattern());

            pattern.occurrences++;
            if (success) pattern.successes++;
            pattern.totalPnL += pnl;
            pattern.lastSeen = LocalDateTime.now();
            pattern.successRate = (double) pattern.successes / pattern.occurrences;

            if (pattern.successRate > 0.75 && pattern.occurrences >= 5) {
                pattern.idealAttributes = verification.attributes;

                log.info("[AI-PATTERN] High-performing pattern discovered: {} ({}% win rate, {} trades)",
                        patternKey, (int)(pattern.successRate * 100), pattern.occurrences);
            }
        }

        private void adjustDynamicThresholds(SignalVerification verification, boolean success) {
            if (verification.attributes.volumeRatio > 0) {
                String key = verification.strategy + "_VOLUME_THRESHOLD";
                Double current = dynamicThresholds.getOrDefault(key, 1.2);

                if (success && verification.attributes.volumeRatio < current) {
                    dynamicThresholds.put(key, current * 0.95);
                } else if (!success && verification.attributes.volumeRatio > current) {
                    dynamicThresholds.put(key, current * 1.05);
                }
            }

            if ("VWAP_REVERSION".contains(verification.strategy)) {
                String rsiKey = verification.strategy + "_RSI_THRESHOLD";
                Double currentRsi = dynamicThresholds.getOrDefault(rsiKey, 70.0);

                if (success) {
                    dynamicThresholds.put(rsiKey,
                            currentRsi * 0.9 + verification.attributes.rsi * 0.1);
                }
            }
        }

        private void persistLearning(SignalVerification verification, boolean success, double pnl) {
            try {
                String patternKey = encodeAttributePattern(verification.attributes);

                AIState learning = new AIState();
                learning.setStrategyType("ATTRIBUTE_LEARNING");
                learning.setStateKey(patternKey);
                learning.setActionKey(verification.strategy);
                learning.setQValue(success ? 1.0 : 0.0);
                learning.setLastUpdated(LocalDateTime.now());
                learning.setLearningPhase("PATTERN_DISCOVERY");

                learning.setMetadata(serializeAttributes(verification.attributes));
                learning.setPerformanceMetric(pnl);

                aiStateRepository.save(learning);

            } catch (Exception e) {
                log.error("[AI-PERSIST] Error persisting learning: {}", e.getMessage());
            }
        }

        private String encodeAttributePattern(TechnicalAttributes attrs) {
            StringBuilder key = new StringBuilder();

            key.append(attrs.marketRegime).append("_");
            key.append(getRangeKey("RSI", attrs.rsi, 10)).append("_");
            key.append(getRangeKey("VOL", attrs.volumeRatio, 0.5)).append("_");
            key.append(getRangeKey("VWAP", attrs.vwapDistance, 0.005)).append("_");
            key.append(getTimeSlot(attrs.timeOfDay));

            return key.toString();
        }

        private String getRangeKey(String prefix, double value, double binSize) {
            int bin = (int)(value / binSize);
            return prefix + bin;
        }

        private String getTimeSlot(int secondsOfDay) {
            int hour = secondsOfDay / 3600;
            if (hour < 10) return "OPEN";
            if (hour < 12) return "MORNING";
            if (hour < 14) return "MIDDAY";
            if (hour < 15) return "AFTERNOON";
            return "CLOSE";
        }

        private String serializeAttributes(TechnicalAttributes attrs) {
            return String.format(
                    "{\"rsi\":%.2f,\"vwap\":%.4f,\"vol\":%.2f,\"momentum\":%.2f,\"poc\":%.2f}",
                    attrs.rsi, attrs.vwapDistance, attrs.volumeRatio,
                    attrs.momentumStrength, attrs.pocDistance
            );
        }

        public double getOptimalThreshold(String strategy, String thresholdType) {
            String key = strategy + "_" + thresholdType;
            return dynamicThresholds.getOrDefault(key, getDefaultThreshold(thresholdType));
        }

        private double getDefaultThreshold(String type) {
            switch(type) {
                case "VOLUME_THRESHOLD": return 1.2;
                case "RSI_THRESHOLD": return 70.0;
                case "VWAP_DISTANCE": return 0.005;
                default: return 1.0;
            }
        }

        public void loadLearnedPatterns() {
            try {
                List<AIState> patterns = aiStateRepository.findByStrategyTypeOrderByLastUpdatedDesc("ATTRIBUTE_LEARNING");

                for (AIState state : patterns) {
                    if (state.getQValue() != null && state.getQValue() > 0.7) {
                        AttributePattern pattern = new AttributePattern();
                        pattern.successRate = state.getQValue();
                        pattern.lastSeen = state.getLastUpdated();
                        successfulPatterns.put(state.getStateKey(), pattern);
                    }
                }

                log.info("[AI-LOAD] Loaded {} learned patterns", successfulPatterns.size());

            } catch (Exception e) {
                log.error("[AI-LOAD] Error loading patterns: {}", e.getMessage());
            }
        }

        @Async
        public void sendDailySummary() {
            try {
                StringBuilder summary = new StringBuilder();
                summary.append("🤖 AI Learning Summary\n\n");

                List<Map.Entry<String, AttributePattern>> topPatterns = successfulPatterns.entrySet().stream()
                        .filter(e -> e.getValue().occurrences >= 3)
                        .sorted((a, b) -> Double.compare(b.getValue().successRate, a.getValue().successRate))
                        .limit(3)
                        .collect(Collectors.toList());

                if (!topPatterns.isEmpty()) {
                    summary.append("Top Patterns:\n");
                    for (Map.Entry<String, AttributePattern> entry : topPatterns) {
                        summary.append(String.format("• %s: %.0f%% win rate (%d trades)\n",
                                entry.getKey().substring(0, Math.min(20, entry.getKey().length())),
                                entry.getValue().successRate * 100,
                                entry.getValue().occurrences));
                    }
                }

                summary.append("\nThreshold Adjustments:\n");
                dynamicThresholds.forEach((key, value) -> {
                    if (key.contains("VOLUME")) {
                        summary.append(String.format("• Volume: %.2f\n", value));
                    } else if (key.contains("RSI")) {
                        summary.append(String.format("• RSI: %.1f\n", value));
                    }
                });

                if (summary.length() > 50) {
                    telegramService.sendMessage(summary.toString());
                }

            } catch (Exception e) {
                log.error("[AI-SUMMARY] Error sending summary: {}", e.getMessage());
            }
        }
    }

    // ================================================================================================
    // SUPPORTING ENUMS AND CLASSES
    // ================================================================================================

    private enum LeaderLagStrategy { STANDARD, REVERSE, NONE }
    private enum VWAPPattern { NONE, COMPRESSION, FAILED_BREAKDOWN, STAIR_STEP }

    @Getter @Setter
    private static class MarketContext {
        final double leaderMomentum, qqqMomentum, correlation, vixLevel;
        final String marketTrend, volatilityRegime, strongestLeader;
        final int timeOfDay, dayOfWeek;
        final double volumeRatio, rsi;
        final BigDecimal currentPrice;

        public MarketContext(double leaderMomentum, double qqqMomentum, double correlation, double vixLevel,
                             String marketTrend, String volatilityRegime, int timeOfDay, int dayOfWeek,
                             double volumeRatio, double momentumDivergence, double rsi, BigDecimal currentPrice,
                             String strongestLeader) {
            this.leaderMomentum = leaderMomentum;
            this.qqqMomentum = qqqMomentum;
            this.correlation = correlation;
            this.vixLevel = vixLevel;
            this.marketTrend = marketTrend;
            this.volatilityRegime = volatilityRegime;
            this.timeOfDay = timeOfDay;
            this.dayOfWeek = dayOfWeek;
            this.volumeRatio = volumeRatio;
            this.rsi = rsi;
            this.currentPrice = currentPrice;
            this.strongestLeader = strongestLeader;
        }
    }

    @Getter @Setter
    private static class AIDecision {
        final LeaderLagStrategy strategy;
        final double confidence;
        final String reasoning, marketRegime;
        final MarketFeatures features;

        public AIDecision(LeaderLagStrategy strategy, double confidence, String reasoning,
                          String marketRegime, MarketFeatures features) {
            this.strategy = strategy;
            this.confidence = confidence;
            this.reasoning = reasoning;
            this.marketRegime = marketRegime;
            this.features = features;
        }
    }

    @Getter @Setter
    private static class MarketFeatures {
        final double spyMomentum, qqqMomentum, correlation, vixLevel, volumeRatio, momentumDivergence;
        final int timeOfDay, dayOfWeek;

        public MarketFeatures(double spyMomentum, double qqqMomentum, double correlation, double vixLevel,
                              int timeOfDay, int dayOfWeek, double volumeRatio, double momentumDivergence) {
            this.spyMomentum = spyMomentum;
            this.qqqMomentum = qqqMomentum;
            this.correlation = correlation;
            this.vixLevel = vixLevel;
            this.timeOfDay = timeOfDay;
            this.dayOfWeek = dayOfWeek;
            this.volumeRatio = volumeRatio;
            this.momentumDivergence = momentumDivergence;
        }
    }

    @Getter @Setter
    private static class TradeOutcome {
        final LeaderLagStrategy strategy;
        final MarketFeatures features;
        final double pnlPercent;
        final boolean isWinner;
        final LocalDateTime timestamp;

        public TradeOutcome(LeaderLagStrategy strategy, MarketFeatures features, double pnlPercent,
                            boolean isWinner, LocalDateTime timestamp) {
            this.strategy = strategy;
            this.features = features;
            this.pnlPercent = pnlPercent;
            this.isWinner = isWinner;
            this.timestamp = timestamp;
        }
    }

    @Getter @Setter
    private static class PricePoint {
        final double price;
        final LocalDateTime timestamp;

        public PricePoint(double price, LocalDateTime timestamp) {
            this.price = price;
            this.timestamp = timestamp;
        }
    }

    @Getter @Setter
    private static class LeaderCorrelationMetrics {
        final String strongestLeader;
        final double strongestCorrelation;
        final Map<String, Double> allCorrelations;

        public LeaderCorrelationMetrics(String strongestLeader, double strongestCorrelation, Map<String, Double> allCorrelations) {
            this.strongestLeader = strongestLeader;
            this.strongestCorrelation = strongestCorrelation;
            this.allCorrelations = allCorrelations;
        }
    }

    @Getter @Setter
    private static class VolumeRequirement {
        private final String tier;
        private final boolean met;
        private final double multiplier;
        private final double maxConfidence;

        public VolumeRequirement(String tier, boolean met, double multiplier, double maxConfidence) {
            this.tier = tier;
            this.met = met;
            this.multiplier = multiplier;
            this.maxConfidence = maxConfidence;
        }
    }

    @Getter @Setter
    private static class GapAnalysis {
        private final double gapPercentage;
        private final boolean isSignificant;
        private final double confidenceBoost;
        private final String direction;

        public GapAnalysis(double gapPercentage, boolean isSignificant, double confidenceBoost, String direction) {
            this.gapPercentage = gapPercentage;
            this.isSignificant = isSignificant;
            this.confidenceBoost = confidenceBoost;
            this.direction = direction;
        }
    }

    private static class OpeningRange {
        private final BigDecimal high, low;

        public OpeningRange(BigDecimal high, BigDecimal low) {
            this.high = high;
            this.low = low;
        }

        public BigDecimal getHigh() { return high; }
        public BigDecimal getLow() { return low; }
    }

    private static class SignalVerification {
        Long signalId;
        String strategy;
        LocalDateTime generatedAt;
        double confidence;
        TechnicalAttributes attributes;
        boolean verified = false;
        double actualOutcome = 0.0;
    }

    private class TechnicalAttributes {
        double vwapDistance;
        String vwapTrend;
        double rsi;
        String macdSignal;
        double volumeRatio;
        double momentumStrength;
        String marketRegime;
        boolean extendedFromVwap;
        boolean hasRsiDivergence;
        boolean hasMacdDivergence;
        double priceLevel;
        double atr;
        double strength;
        int timeOfDay;
        double pocLevel;
        double pocDistance;
    }

    private class AttributePattern {
        int occurrences = 0;
        int successes = 0;
        double successRate = 0.0;
        double totalPnL = 0.0;
        LocalDateTime lastSeen;
        TechnicalAttributes idealAttributes;
    }

    private void logEnhancedLeaderLagDebug(List<Signal> leaderLagSignals, String analysisId) {
        if (leaderLagSignals.isEmpty()) {
            //log.warn("[ENHANCED-AI-DEBUG][{}] 🔍 NO AI LEADER LAG SIGNALS GENERATED - Debugging:", analysisId);

            // Debug correlation
            try {
                LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);
                if (metrics == null) {
                    log.warn("[ENHANCED-AI-DEBUG][{}] ❌ CORRELATION METRICS: NULL", analysisId);
                } else {
                    log.info("[ENHANCED-AI-DEBUG][{}] 📊 CORRELATION: Strongest={} ({}%), All={}",
                            analysisId, metrics.getStrongestLeader(),
                            (int)(metrics.getStrongestCorrelation() * 100),
                            metrics.getAllCorrelations().entrySet().stream()
                                    .map(e -> e.getKey() + "=" + (int)(e.getValue() * 100) + "%")
                                    .collect(Collectors.joining(", ")));
                }
            } catch (Exception e) {
                log.error("[ENHANCED-AI-DEBUG][{}] ❌ Error getting correlation: {}", analysisId, e.getMessage());
            }

            // Debug momentum - FIXED FORMATTING
            try {
                for (String leader : LEADER_STOCKS) {
                    MomentumVelocity velocity = momentumTracker.calculateVelocity(leader);
                    log.info("[ENHANCED-AI-DEBUG][{}] 🎯 {} MOMENTUM: {:.3f}, State: {}, Acceleration: {:.4f}",
                            analysisId, leader, velocity.momentum, velocity.state, velocity.acceleration);
                }

                MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");
                log.info("[ENHANCED-AI-DEBUG][{}] 🎯 QQQ MOMENTUM: {}, State: {}, Acceleration: {}",
                        analysisId, String.format("%.3f",qqqVelocity.momentum), qqqVelocity.state, String.format("%.4f",qqqVelocity.acceleration));

            } catch (Exception e) {
                log.error("[ENHANCED-AI-DEBUG][{}] ❌ Error getting momentum: {}", analysisId, e.getMessage());
            }

            // NEW: Debug why AI decision failed
            try {
                EnhancedMarketContext context = buildEnhancedMarketContextWithVelocity(
                        technicalAnalysisService.analyze("QQQ"), "DOWN", LocalTime.now(ET_ZONE), analysisId);

                if (context != null) {
                    Map<String, MicroMomentumPattern> leaderPatterns = classifyLeaderMicroMomentumPatterns(context, analysisId);
                    Map<String, RSIMomentumContext> rsiContexts = getRSIMomentumContexts(context, analysisId);

                    log.info("[ENHANCED-AI-DEBUG][{}] 🧠 AI DECISION DEBUG:", analysisId);
                    log.info("[ENHANCED-AI-DEBUG][{}] - Strongest Leader: {}", analysisId, context.strongestLeader);
                    log.info("[ENHANCED-AI-DEBUG][{}] - Correlation: {}%", analysisId, String.format("%.1f",context.correlation * 100));

                    leaderPatterns.forEach((leader, pattern) ->
                            log.info("[ENHANCED-AI-DEBUG][{}] - {} Pattern: {}", analysisId, leader, pattern));

                    rsiContexts.forEach((leader, rsiContext) ->
                            log.info("[ENHANCED-AI-DEBUG][{}] - {} RSI Context: {} → Action: {}",
                                    analysisId, leader, rsiContext.interpretation, rsiContext.signalAction));
                }
            } catch (Exception e) {
                log.error("[ENHANCED-AI-DEBUG][{}] ❌ Error in AI decision debug: {}", analysisId, e.getMessage());
            }

        } else {
            log.info("[ENHANCED-AI-DEBUG][{}] ✅ Generated {} AI leader lag signals", analysisId, leaderLagSignals.size());

            for (Signal signal : leaderLagSignals) {
                String leaderStock = signal.getMetadata().get("leaderStock");
                String leaderVelocity = signal.getMetadata().get("leaderVelocity");
                String microPattern = signal.getMetadata().get("microPattern");

                log.info("[ENHANCED-AI-DEBUG][{}] 🚀 AI Signal: {} - Leader: {}, Velocity: {}, Pattern: {}, Confidence: {}%",
                        analysisId, signal.getStrategy(), leaderStock, leaderVelocity, microPattern,
                        (int)(signal.getConfidence() * 100));
            }
        }
    }
}