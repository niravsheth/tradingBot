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

    // AI LEARNING COMPONENTS - PERSISTENT
    private final AILearningEngine aiLearning = new AILearningEngine();
    private final CorrelationDetector correlationDetector = new CorrelationDetector();
    private final SignalAttributeLearning attributeLearning = new SignalAttributeLearning();
    private final LeaderDirectionValidator leaderValidator = new LeaderDirectionValidator();
    private final GreeksValidator greeksValidator = new GreeksValidator();

    @PostConstruct
    public void initializeAI() {
        try {
            aiLearning.loadAIState();
            attributeLearning.loadLearnedPatterns();
            log.info("AI Learning Engine initialized with persistent state");
        } catch (Exception e) {
            log.error("Error initializing AI Learning Engine: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdownAI() {
        try {
            aiLearning.saveAIState();
            log.info("AI Learning Engine state saved on shutdown");
        } catch (Exception e) {
            log.error("Error saving AI state on shutdown: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 300000) // Every 5 minutes
    public void saveAIStatePeriodically() {
        try {
            aiLearning.saveAIState();
        } catch (Exception e) {
            log.debug("Error in periodic AI state save: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "America/New_York") // 4:30 PM ET daily
    public void sendAILearningSummary() {
        try {
            attributeLearning.sendDailySummary();
        } catch (Exception e) {
            log.error("Error sending AI learning summary: {}", e.getMessage());
        }
    }

    public List<Signal> analyzeOptions(String symbol, String marketTrend) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        log.info("Trading ANALYSIS START ========== {}", analysisId);
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
            log.info("[{}] Retrieved {} options", analysisId, options.size());

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

// PRIORITY 1: AI-POWERED LEADER-LAG STRATEGIES (HIGHEST PRIORITY)
            List<Signal> leaderLagSignals = analyzeAILeaderLagStrategies(aiStrikes, ta, marketTrend, analysisId);
            rawSignals.addAll(leaderLagSignals);

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

            // Save signals for IMMEDIATE execution
            saveSignalsAfterConfirmation(orchestratedSignals, ta, marketTrend, analysisId);

            log.info("[{}] ========== ANALYSIS COMPLETE - {} SIGNALS ==========",
                    analysisId, orchestratedSignals.size());

            return orchestratedSignals;

        } catch (Exception e) {
            log.error("[{}] ERROR in analyzeOptions: {}", analysisId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    private List<Option> selectStrikesForAILeaderLag(List<Option> options, TechnicalAnalysis ta, String analysisId) {
        // More permissive filtering for AI - allows slightly ITM options
        BigDecimal currentPrice = ta.getCurrentPrice();

        return options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    BigDecimal distance = strike.subtract(currentPrice).abs();
                    return distance.compareTo(BigDecimal.valueOf(3.0)) <= 0; // Within $3 of current
                })
                .collect(Collectors.toList());
    }
    public List<Signal> analyzeOptions(String symbol) {
        return analyzeOptions(symbol, "NEUTRAL");
    }

    private boolean hasDataSufficiency(String symbol, String analysisId) {
        try {
            // Check market data availability (minimum 2 hours of data for 0DTE)
            LocalDateTime twoHoursAgo = LocalDateTime.now(ET_ZONE).minusHours(2);
            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 120); // 2 hours in minutes

            if (recentData.size() < 120) {
                log.warn("[DATA-CHECK][{}] Insufficient market data: {} minutes (need 120)", analysisId, recentData.size());
                return false;
            }

            // Check for data gaps in critical trading hours
            LocalDateTime marketOpen = LocalDateTime.now(ET_ZONE).with(LocalTime.of(9, 30));
            LocalDateTime now = LocalDateTime.now(ET_ZONE);

            if (now.isAfter(marketOpen.plusHours(2))) {
                long expectedMinutes = Duration.between(marketOpen, now).toMinutes();
                long actualDataPoints = recentData.stream()
                        .filter(data -> data.getTimestamp().isAfter(marketOpen))
                        .count();

                double dataCompleteness = (double) actualDataPoints / expectedMinutes;

                if (dataCompleteness < 0.80) { // Need at least 80% data completeness
                    log.warn("[DATA-CHECK][{}] Data completeness {}% < 80% threshold", analysisId, (int)(dataCompleteness * 100));
                    return false;
                }
            }

            // Check leader stock data availability for AI
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
            return false; // Fail safe
        }
    }

    // ================================================================================================
    // AI-POWERED LEADER-LAG ANALYSIS - ENHANCED FOR INDIVIDUAL STOCKS
    // ================================================================================================

//    private List<Signal> analyzeAILeaderLagStrategies(List<Option> options, TechnicalAnalysis ta,
//                                                      String marketTrend, String analysisId) {
//        List<Signal> leaderLagSignals = new ArrayList<>();
//        LocalTime now = LocalTime.now(ET_ZONE);
//        marketTrend = ta.getTrend();
//        try {
//            // Build comprehensive market context for AI with leader stocks
//            MarketContext context = buildEnhancedMarketContext(ta, marketTrend, now, analysisId);
//
//            if (context == null) {
//                log.warn("[AI-LEADER-LAG][{}] Could not build market context - insufficient leader data", analysisId);
//                return leaderLagSignals;
//            }
//
//            // Use AI to determine optimal strategy
//            AIDecision aiDecision = aiLearning.decideStrategy(context, analysisId);
//
//            if (aiDecision.getStrategy() == LeaderLagStrategy.NONE) {
//                log.info("[AI-LEADER-LAG][{}] AI decided to skip leader-lag: {}", analysisId, aiDecision.getReasoning());
//                return leaderLagSignals;
//            }
//
//            // Get leader correlation metrics
//            LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetrics(analysisId);
//
//            if (metrics == null || metrics.getStrongestCorrelation() < 0.4) {
//                log.warn("[AI-LEADER-LAG][{}] Insufficient leader correlation ({}%) - AI overridden",
//                        analysisId, metrics != null ? (int)(metrics.getStrongestCorrelation() * 100) : 0);
//                return leaderLagSignals;
//            }
//
//            log.info("[AI-LEADER-LAG][{}] 🤖 AI SELECTED: {} (Confidence: {}%)",
//                    analysisId, aiDecision.getStrategy(), (int)(aiDecision.getConfidence() * 100));
//            log.info("[AI-LEADER-LAG][{}] 🧠 AI Reasoning: {}", analysisId, aiDecision.getReasoning());
//            log.info("[AI-LEADER-LAG][{}] 📊 Leader Analysis: {} ({}% corr), QQQ momentum: {}",
//                    analysisId, metrics.getStrongestLeader(),
//                    (int)(metrics.getStrongestCorrelation() * 100),
//                    String.format("%.2f", context.qqqMomentum));
//
//            // Generate signals based on AI decision
//            for (Option option : options) {
//                if (!quickSignalValidation(option, options, ta, marketTrend, analysisId)) {
//                    continue;
//                }
//
//                AILeaderLagSignal aiSignal = generateEnhancedAILeaderLagSignal(option, aiDecision, ta, marketTrend, metrics, context);
//
//                if (aiSignal != null && aiSignal.getConfidence() >= getAIThreshold(aiDecision.getStrategy(), now)) {
//                    Signal signal = createAILeaderLagSignal(option, options, ta, aiSignal, marketTrend, analysisId);
//                    if (signal != null) {
//                        leaderLagSignals.add(signal);
//
//                        log.info("[AI-LEADER-LAG][{}] ✅ {} Signal Generated - Confidence: {}%",
//                                analysisId, aiDecision.getStrategy(), (int)(aiSignal.getConfidence() * 100));
//                    }
//                }
//            }
//
//            return leaderLagSignals;
//
//        } catch (Exception e) {
//            log.error("[AI-LEADER-LAG][{}] Error in AI leader-lag analysis: {}", analysisId, e.getMessage());
//            return leaderLagSignals;
//        }
//    }

//    private MarketContext buildEnhancedMarketContext(TechnicalAnalysis ta, String marketTrend, LocalTime now, String analysisId) {
//        try {
//            // Get leader stock analysis
//            Map<String, TechnicalAnalysis> leaderAnalysis = new HashMap<>();
//            Map<String, Double> leaderMomentum = new HashMap<>();
//
//            for (String leaderSymbol : LEADER_STOCKS) {
//                TechnicalAnalysis leaderTA = technicalAnalysisService.analyze(leaderSymbol);
//                if (leaderTA != null) {
//                    leaderAnalysis.put(leaderSymbol, leaderTA);
//                    leaderMomentum.put(leaderSymbol, leaderTA.getMomentumStrength());
//                } else {
//                    log.warn("[AI-CONTEXT][{}] Missing TA for leader stock: {}", analysisId, leaderSymbol);
//                }
//            }
//
//            // Ensure we have at least 2 leaders with data
//            if (leaderMomentum.size() < 2) {
//                log.warn("[AI-CONTEXT][{}] Insufficient leader data: {} of {} leaders",
//                        analysisId, leaderMomentum.size(), LEADER_STOCKS.size());
//                return null;
//            }
//
//            // Find strongest leader
//            String strongestLeader = leaderMomentum.entrySet().stream()
//                    .max(Map.Entry.comparingByValue(Comparator.comparing(Math::abs)))
//                    .map(Map.Entry::getKey)
//                    .orElse("AAPL");
//
//            double strongestLeaderMomentum = leaderMomentum.get(strongestLeader);
//
//            // Calculate leader-QQQ divergence
//            double leaderQQQDivergence = Math.abs(strongestLeaderMomentum - ta.getMomentumStrength());
//
//            return new MarketContext(
//                    strongestLeaderMomentum,
//                    ta.getMomentumStrength(),
//                    correlationDetector.getLeaderQQQCorrelation(strongestLeader),
//                    getVixLevel(),
//                    marketTrend,
//                    ta.getMarketRegime().toString(),
//                    now.getHour() * 60 + now.getMinute(),
//                    LocalDate.now().getDayOfWeek().getValue(),
//                    ta.getVolumeRatio(),
//                    leaderQQQDivergence,
//                    ta.getRsi(),
//                    ta.getCurrentPrice(),
//                    strongestLeader
//            );
//
//        } catch (Exception e) {
//            log.error("[AI-CONTEXT][{}] Error building market context: {}", analysisId, e.getMessage());
//            return null;
//        }
//    }

//    private AILeaderLagSignal generateEnhancedAILeaderLagSignal(Option option, AIDecision aiDecision,
//                                                                TechnicalAnalysis ta, String marketTrend,
//                                                                LeaderCorrelationMetrics metrics, MarketContext context) {
//
//        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
//        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
//
//        if (aiDecision.getStrategy() == LeaderLagStrategy.STANDARD) {
//            // STANDARD: Leader moves against QQQ, expect QQQ to follow
//            String leader = context.strongestLeader;
//            double leaderMomentum = context.spyMomentum; // This is actually strongest leader momentum now
//            double qqqMomentum = context.qqqMomentum;
//
//            // Leader bullish, QQQ bearish/neutral -> take QQQ CALL
//            if (isCall && leaderMomentum > 0.3 && qqqMomentum < 0.1 &&
//                    (leaderMomentum - qqqMomentum) > 0.2) {
//                return createStandardAISignal(option, context, metrics, "CALL", aiDecision, leader);
//            }
//            // Leader bearish, QQQ bullish/neutral -> take QQQ PUT
//            else if (isPut && leaderMomentum < -0.3 && qqqMomentum > -0.1 &&
//                    (qqqMomentum - leaderMomentum) > 0.2) {
//                return createStandardAISignal(option, context, metrics, "PUT", aiDecision, leader);
//            }
//        }
//        else if (aiDecision.getStrategy() == LeaderLagStrategy.REVERSE) {
//            // REVERSE: Leader previously drove QQQ, now fading -> bet on QQQ's true direction
//            double leaderMomentum = context.spyMomentum; // Actually strongest leader
//            double qqqMomentum = context.qqqMomentum;
//
//            // Leader fading from strong bullish, QQQ continuing up -> QQQ CALL
//            if (isCall && leaderMomentum > 0.1 && leaderMomentum < 0.4 && qqqMomentum > 0.3) {
//                return createReverseAISignal(option, context, metrics, "CALL", aiDecision);
//            }
//            // Leader fading from strong bearish, QQQ continuing down -> QQQ PUT
//            else if (isPut && leaderMomentum < -0.1 && leaderMomentum > -0.4 && qqqMomentum < -0.3) {
//                return createReverseAISignal(option, context, metrics, "PUT", aiDecision);
//            }
//        }
//
//        return null;
//    }

//    private AILeaderLagSignal createStandardAISignal(Option option, MarketContext context,
//                                                     LeaderCorrelationMetrics metrics, String type,
//                                                     AIDecision aiDecision, String leader) {
//        double confidence = aiDecision.getConfidence();
//
//        // AI confidence boosts for standard strategy
//        double leaderMomentum = context.spyMomentum; // Actually leader momentum
//        double divergence = Math.abs(leaderMomentum - context.qqqMomentum);
//
//        if (divergence > 0.4) confidence = Math.min(0.95, confidence * 1.15); // Strong divergence
//        if (Math.abs(leaderMomentum) > 0.5) confidence = Math.min(0.95, confidence * 1.10); // Strong leader move
//        if (metrics.getStrongestCorrelation() > 0.7) confidence = Math.min(0.95, confidence * 1.08); // High correlation
//        if (context.volumeRatio > 1.5) confidence = Math.min(0.95, confidence * 1.05); // Volume confirmation
//
//        String strategy = type.equals("CALL") ? "0DTE_AI_LEADER_LAG_CALL" : "0DTE_AI_LEADER_LAG_PUT";
//        String reason = String.format("🤖 AI Standard: %s momentum %.2f vs QQQ %.2f, correlation %.0f%%, divergence %.2f",
//                leader, leaderMomentum, context.qqqMomentum, metrics.getStrongestCorrelation() * 100, divergence);
//
//        return new AILeaderLagSignal(LeaderLagStrategy.STANDARD, "BUY", strategy, confidence,
//                metrics.getStrongestCorrelation(), Math.abs(leaderMomentum), reason, aiDecision.getMarketRegime(), leader);
//    }

//    private AILeaderLagSignal createReverseAISignal(Option option, MarketContext context,
//                                                    LeaderCorrelationMetrics metrics, String type, AIDecision aiDecision) {
//        double confidence = aiDecision.getConfidence();
//
//        // AI confidence boosts for reverse strategy
//        double leaderFade = 0.5 - Math.abs(context.spyMomentum); // How much leader has faded
//        double qqqStrength = Math.abs(context.qqqMomentum);
//
//        if (leaderFade > 0.3) confidence = Math.min(0.95, confidence * 1.12); // Leader fading
//        if (qqqStrength > 0.4) confidence = Math.min(0.95, confidence * 1.15); // QQQ strong momentum
//        if (context.timeOfDay > 930) confidence = Math.min(0.95, confidence * 1.10); // Power hour boost
//        if (metrics.getStrongestCorrelation() > 0.6) confidence = Math.min(0.95, confidence * 1.05);
//
//        String strategy = type.equals("CALL") ? "0DTE_AI_REVERSE_LEADER_LAG_CALL" : "0DTE_AI_REVERSE_LEADER_LAG_PUT";
//        String reason = String.format("🤖 AI Reverse: %s fading %.2f, QQQ continuing %.2f, correlation %.0f%%",
//                context.strongestLeader, context.spyMomentum, context.qqqMomentum, metrics.getStrongestCorrelation() * 100);
//
//        return new AILeaderLagSignal(LeaderLagStrategy.REVERSE, "BUY", strategy, confidence,
//                metrics.getStrongestCorrelation(), qqqStrength, reason, aiDecision.getMarketRegime(), context.strongestLeader);
//    }

    private Signal createAILeaderLagSignal(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                           AILeaderLagSignal aiSignal, String marketTrend, String analysisId) {

        Signal signal = createSignal(option, allOptions, ta, aiSignal.getSignalType(),
                aiSignal.getStrategy(), aiSignal.getConfidence(), marketTrend);

        if (signal == null) return null;

        // Enhanced AI metadata
        signal.getMetadata().put("aiStrategy", aiSignal.getLeaderLagStrategy().toString());
        signal.getMetadata().put("aiCorrelation", String.valueOf(aiSignal.getCorrelation()));
        signal.getMetadata().put("aiSignalStrength", String.valueOf(aiSignal.getSignalStrength()));
        signal.getMetadata().put("aiMarketRegime", aiSignal.getMarketRegime());
        signal.getMetadata().put("leaderStock", aiSignal.getLeaderStock());
        signal.getMetadata().put("priority", "HIGHEST"); // AI gets highest priority
        signal.getMetadata().put("signalSource", "AI_LEARNING_ENGINE");

        // Store option Greeks for later validation - FIXED: Use proper conversion
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

        // AI-optimized risk management
        signal.setTargetPrice(calculateAITarget(signal.getEntryPrice(), aiSignal, ta));
        signal.setStopLoss(calculateAIStop(signal.getEntryPrice(), aiSignal, ta));

        signal.setReason(aiSignal.getReason());

        return signal;
    }

    private BigDecimal calculateAITarget(BigDecimal entryPrice, AILeaderLagSignal aiSignal, TechnicalAnalysis ta) {
        double baseMultiplier = aiSignal.getLeaderLagStrategy() == LeaderLagStrategy.STANDARD ? 2.5 : 2.0;

        // AI strength adjustment
        double strengthAdjustment = 1.0 + (aiSignal.getSignalStrength() - 0.5) * 0.6;
        double finalMultiplier = baseMultiplier * strengthAdjustment;

        // Time decay for 0DTE
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(15, 0))) {
            finalMultiplier *= 0.75; // Reduce targets in final hour
        }

        // High confidence boost
        if (aiSignal.getConfidence() > 0.9) {
            finalMultiplier *= 1.1;
        }

        return entryPrice.multiply(BigDecimal.valueOf(finalMultiplier)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAIStop(BigDecimal entryPrice, AILeaderLagSignal aiSignal, TechnicalAnalysis ta) {
        double baseStopPercentage = aiSignal.getLeaderLagStrategy() == LeaderLagStrategy.STANDARD ? 0.30 : 0.25;

        // Tighter stops for high-confidence AI signals
        if (aiSignal.getConfidence() > 0.9) {
            baseStopPercentage *= 0.80;
        }

        // Power hour adjustment
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(POWER_HOUR_START)) {
            baseStopPercentage *= 0.85; // Tighter stops in power hour
        }

        return entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(baseStopPercentage)))
                .setScale(2, RoundingMode.HALF_UP);
    }

//    private double getAIThreshold(LeaderLagStrategy strategy, LocalTime now) {
//        boolean isPowerHour = now.isAfter(POWER_HOUR_START);
//
//        switch (strategy) {
//            case STANDARD:
//                return isPowerHour ? 0.80 : 0.70; // Lower threshold - AI is smart
//            case REVERSE:
//                return isPowerHour ? 0.75 : 0.80; // Reverse easier in power hour
//            default:
//                return 0.85;
//        }
//    }

    private List<Signal> applyLateSessionFiltering(List<Signal> signals, String analysisId) {
        List<Signal> filtered = signals.stream()
                .filter(s -> {
                    String priority = s.getMetadata().get("priority");
                    String source = s.getMetadata().get("signalSource");

                    // AI signals: need 75%+ confidence (lower bar for AI)
                    if ("HIGHEST".equals(priority) && "AI_LEARNING_ENGINE".equals(source)) {
                        return s.getConfidence() >= 0.75;
                    }

                    // Advanced signals: need 95% confidence
                    if (s.getStrategy().contains("TRENDLINE_BREAK") ||
                            s.getStrategy().contains("GAP_FILL") ||
                            s.getStrategy().contains("HIGH_BREACH") ||
                            s.getStrategy().contains("LOW_BREACH")) {
                        return s.getConfidence() >= 0.95;
                    }

                    // Traditional signals: need 90% confidence
                    return s.getConfidence() >= 0.90;
                })
                .collect(Collectors.toList());

        log.info("[LATE-SESSION][{}] Late session filter: {} signals -> {} (AI-priority-aware filtering)",
                analysisId, signals.size(), filtered.size());

        return filtered;
    }

    // ================================================================================================
    // TRADITIONAL STRATEGY ANALYSIS (SIMPLIFIED - NO UNUSUAL FLOW OR VOLUME SPIKE)
    // ================================================================================================

    private void analyzeOptionWithStrategies(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend,
                                             String analysisId, List<Option> allOptions) {

        LocalTime now = LocalTime.now(ET_ZONE);

        // Opening Range Breakout (after 9:45 AM)
        if (now.isAfter(LocalTime.of(9, 45))) {
            analyzeOpeningRangeBreakout(option, allOptions, ta, signals, marketTrend, analysisId);
        }

        // VWAP-based strategies
        if (ta.getVwap() != null && ta.getVwap().compareTo(BigDecimal.ZERO) > 0) {

            // 1. Strong breakouts with volume (HIGHEST PRIORITY)
            if (ta.isVwapBreakout() && ta.getVolumeRatio() > 1.5) {
                analyzeVWAPBreakout(option, allOptions, ta, signals, marketTrend, analysisId);
            }

            // 2. Extended reversions (far from VWAP)
            else if (ta.isExtendedFromVwap() && (ta.getRsi() > 70 || ta.getRsi() < 30)) {
                analyzeVWAPDeviationReversion(option, allOptions, ta, signals, marketTrend, analysisId);
            }

            // 3. Support/Resistance plays (clear levels)
            else if (ta.isVwapAsSupport() || ta.isVwapAsResistance()) {
                analyzeVWAPSupportResistance(option, allOptions, ta, signals, marketTrend, analysisId);
            }

            // 4. VWAP Reclaim (gradual reclaim after test)
            else if (ta.getVolumeRatio() > 0.8 && !ta.isExtendedFromVwap()) {
                analyzeVWAPReclaim(option, allOptions, ta, signals, marketTrend, analysisId);
            }

            // 5. Directional bounce (very close to VWAP with clear direction)
            else if (Math.abs(ta.getPriceToVwapRatio() - 1.0) < 0.002) {
                analyzeDirectionalVWAPBounce(option, allOptions, ta, signals, marketTrend, analysisId);
            }

            // 6. Generic bounce (close to VWAP, less specific)
            else if (Math.abs(ta.getPriceToVwapRatio() - 1.0) < 0.005) {
                analyzeVWAPBounce(option, allOptions, ta, signals, marketTrend, analysisId);
            }
        }

        // Enhanced pattern detection for existing VWAP strategies
        VWAPPattern currentPattern = detectMultiCandleVWAPPattern(
                marketDataRepository.findRecentData(ta.getSymbol(), 10), ta);

        // Boost confidence for pattern-confirmed signals
        if (currentPattern != VWAPPattern.NONE) {
            log.info("[{}] Multi-candle pattern detected: {}", analysisId, currentPattern);

            // Apply pattern-specific confidence boosts to existing signals
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
                return 1.15; // 15% boost
            case FAILED_BREAKDOWN:
                return 1.20; // 20% boost - strong pattern
            case STAIR_STEP:
                return 1.10; // 10% boost
            default:
                return 1.0;
        }
    }

    // ================================================================================================
    // EXECUTION WITH ENHANCED VALIDATION (LEADER DIRECTION + GREEKS)
    // ================================================================================================

    private void executeSignalImmediately(Signal signal, SHAPExplainerService.SHAPExplanation explanation, String analysisId) {
        try {
            log.info("[EXECUTION][{}] Attempting BUY_TO_OPEN execution: {}", analysisId, signal.getOptionSymbol());

            // 1. GET QUOTE FIRST
            QuoteResponse quote = tradierService.getQuote(signal.getOptionSymbol());
            if (quote == null || quote.getQuote() == null) {
                log.error("[EXECUTION][{}] ❌ Cannot get quote - saving as QUOTE_FAILED", analysisId);
                saveFailedTrade(signal, "QUOTE_FAILED", "Unable to get option quote", analysisId);
                saveFailedSignal(signal, "QUOTE_FAILED", "Unable to get option quote");
                return;
            }

            BigDecimal currentPrice = quote.getQuote().getLast();
            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                // Try mid price if last is not available
                currentPrice = quote.getQuote().getMidPrice();
                if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    log.error("[EXECUTION][{}] ❌ Invalid option price - saving as PRICE_INVALID", analysisId);
                    saveFailedTrade(signal, "PRICE_INVALID", "Option price is null or zero", analysisId);
                    saveFailedSignal(signal, "PRICE_INVALID", "Option price is null or zero");
                    return;
                }
            }

            // FIXED: Update Greeks from fresh quote with proper null checks and type handling
            Quote freshQuote = quote.getQuote();
            if (freshQuote.getGreeks() != null) {
                OptionGreeks greeks = freshQuote.getGreeks();

                // Convert BigDecimal Greeks to Double for Signal entity
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

            // 2. PRICE LEVEL RISK ASSESSMENT
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

                // Send notification for blocked signals
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

            // 3. LEADER DIRECTION VALIDATION (Skip for AI Leader-Lag strategies)
            if (!signal.getStrategy().contains("AI_LEADER_LAG")) {
                LeaderDirectionValidator.ValidationResult leaderValidation =
                        leaderValidator.validateLeaderDirection(signal, analysisId);

                if (!leaderValidation.shouldProceed) {
                    // Check if it's just confidence reduction or hard block
                    if (leaderValidation.confidenceAdjustment < 0.8) {
                        log.warn("[EXECUTION][{}] ❌ Leader opposition - HARD BLOCK: {}",
                                analysisId, leaderValidation.reason);

                        saveFailedTrade(signal, "LEADER_BLOCKED", leaderValidation.reason, analysisId);
                        saveFailedSignal(signal, "LEADER_BLOCKED", leaderValidation.reason);

                        telegramService.sendMessage(String.format(
                                "🚫 SIGNAL BLOCKED - LEADER OPPOSITION\n" +
                                        "Option: %s\n" +
                                        "Reason: %s\n" +
                                        "Leaders: %s\n" +
                                        "Status: Saved for analysis",
                                signal.getOptionSymbol(),
                                leaderValidation.reason,
                                leaderValidation.details
                        ));
                        return;
                    } else {
                        // Just reduce confidence
                        signal.setConfidence(signal.getConfidence() * leaderValidation.confidenceAdjustment);
                        log.info("[EXECUTION][{}] ⚠️ Leader caution - confidence reduced to {}%",
                                analysisId, (int)(signal.getConfidence() * 100));
                    }
                }
            }

            // 4. GREEKS VALIDATION
            GreeksValidator.GreeksValidationResult greeksValidation =
                    greeksValidator.validateGreeks(signal, signal.getConfidence(), analysisId);

            if (!greeksValidation.isValid) {
                log.warn("[EXECUTION][{}] ❌ Greeks validation failed: {}",
                        analysisId, greeksValidation.reason);

                saveFailedTrade(signal, "GREEKS_BLOCKED", greeksValidation.reason, analysisId);
                saveFailedSignal(signal, "GREEKS_BLOCKED", greeksValidation.reason);

                telegramService.sendMessage(String.format(
                        "🚫 SIGNAL BLOCKED - GREEKS RISK\n" +
                                "Option: %s\n" +
                                "Reason: %s\n" +
                                "Delta: %.3f, Gamma: %.3f, Theta: %.3f\n" +
                                "Status: Saved for analysis",
                        signal.getOptionSymbol(),
                        greeksValidation.reason,
                        signal.getDelta() != null ? signal.getDelta() : 0.0,
                        signal.getGamma() != null ? signal.getGamma() : 0.0,
                        signal.getTheta() != null ? signal.getTheta() : 0.0
                ));
                return;
            }

            // 5. PROCEED WITH EXECUTION IF ALL CHECKS PASS
            log.info("[EXECUTION][{}] ✅ All validations PASSED - proceeding with execution", analysisId);

            boolean orderSuccessful = attemptSignalExecution(signal, analysisId);

            if (orderSuccessful) {
                log.info("[EXECUTION][{}] ✅ Tradier order CONFIRMED - saving as OPEN", analysisId);

                Trade trade = createSuccessfulTrade(signal, currentPrice, analysisId);
                enrichTradeWithBayesianData(trade, signal);
                trade = tradeRepository.save(trade);

                // Update signal
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

                // Send success notification
                sendSuccessNotification(trade, signal, explanation, analysisId);

                // Update AI learning asynchronously
                updateAIFromTradeAsync(trade, signal);

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
    @Async
    public void updateAIFromTradeAsync(Trade trade, Signal signal) {
        try {
            if (signal.getStrategy().contains("AI_LEADER_LAG")) {
                aiLearning.updateFromTrade(trade, signal);
                log.info("[AI-UPDATE] Updated AI learning from trade: {} P&L: {}%",
                        trade.getOptionSymbol(), calculateTradePerformance(trade));
            }
        } catch (Exception e) {
            log.error("[AI-UPDATE] Error updating AI from trade: {}", e.getMessage());
        }
    }

    // ================================================================================================
    // LEADER DIRECTION VALIDATOR
    // ================================================================================================

    private class LeaderDirectionValidator {

        @Getter @Setter
        class ValidationResult {
            boolean shouldProceed;
            double confidenceAdjustment;
            String reason;
            String details;

            ValidationResult(boolean proceed, double adjustment, String reason, String details) {
                this.shouldProceed = proceed;
                this.confidenceAdjustment = adjustment;
                this.reason = reason;
                this.details = details;
            }
        }

        @Getter @Setter
        class LeaderMomentum {
            String symbol;
            double momentum5min;
            double momentum10min;
            double momentum15min;
            boolean isOpposing;
            double weight;

            LeaderMomentum(String symbol, double weight) {
                this.symbol = symbol;
                this.weight = weight;
            }
        }

        public ValidationResult validateLeaderDirection(Signal signal, String analysisId) {
            try {
                LocalTime now = LocalTime.now(ET_ZONE);

                // Skip validation in first 30 minutes (too noisy)
                if (now.isBefore(LocalTime.of(10, 0))) {
                    return new ValidationResult(true, 1.0, "Early session - skipping leader check", "");
                }

                // Skip for very high confidence signals
                if (signal.getConfidence() >= 0.85) {
                    return new ValidationResult(true, 1.0, "High confidence signal - leader check bypassed", "");
                }

                // Skip for reversion plays
                if (signal.getStrategy().contains("REVERSION")) {
                    return new ValidationResult(true, 1.0, "Reversion play - leaders may be extended", "");
                }

                // Check if QQQ volume is 2x+ normal (QQQ leading)
                TechnicalAnalysis qqqTA = technicalAnalysisService.analyze("QQQ");
                if (qqqTA != null && qqqTA.getVolumeRatio() > 2.0) {
                    return new ValidationResult(true, 1.0, "QQQ leading with high volume", "");
                }

                boolean isCallSignal = signal.getStrategy().contains("CALL");
                Map<String, LeaderMomentum> leaderAnalysis = analyzeLeaderMomentum(isCallSignal, now);

                // Count opposing leaders
                long opposingCount = leaderAnalysis.values().stream()
                        .filter(l -> l.isOpposing)
                        .count();

                // Calculate weighted opposition score
                double oppositionScore = leaderAnalysis.values().stream()
                        .filter(l -> l.isOpposing)
                        .mapToDouble(l -> l.weight)
                        .sum();

                // Build details string
                String details = leaderAnalysis.entrySet().stream()
                        .map(e -> String.format("%s: 5m=%.2f%%, 10m=%.2f%%, 15m=%.2f%%",
                                e.getKey(),
                                e.getValue().momentum5min * 100,
                                e.getValue().momentum10min * 100,
                                e.getValue().momentum15min * 100))
                        .collect(Collectors.joining(", "));

                // Decision logic
                if (opposingCount >= 2 || oppositionScore >= 0.7) {
                    // Hard block
                    return new ValidationResult(false, 0.0,
                            String.format("%d/3 leaders opposing direction (weighted: %.0f%%)",
                                    opposingCount, oppositionScore * 100),
                            details);
                } else if (opposingCount == 1 && oppositionScore >= 0.4) {
                    // Reduce confidence by 20%
                    return new ValidationResult(true, 0.8,
                            "1 leader opposing - confidence reduced",
                            details);
                }

                return new ValidationResult(true, 1.0, "Leaders aligned", details);

            } catch (Exception e) {
                log.error("[LEADER-VALIDATOR][{}] Error validating leader direction: {}",
                        analysisId, e.getMessage());
                // Fail open - don't block on error
                return new ValidationResult(true, 1.0, "Validation error - proceeding", "");
            }
        }

        private Map<String, LeaderMomentum> analyzeLeaderMomentum(boolean isCallSignal, LocalTime now) {
            Map<String, LeaderMomentum> results = new HashMap<>();

            for (String leader : LEADER_STOCKS) {
                LeaderMomentum momentum = new LeaderMomentum(leader, LEADER_WEIGHTS.get(leader));

                // Get recent data
                List<MarketData> data5min = marketDataRepository.findRecentData(leader, 5);
                List<MarketData> data10min = marketDataRepository.findRecentData(leader, 10);
                List<MarketData> data15min = marketDataRepository.findRecentData(leader, 15);

                // Calculate momentum for each timeframe
                momentum.momentum5min = calculateMomentum(data5min);
                momentum.momentum10min = calculateMomentum(data10min);
                momentum.momentum15min = calculateMomentum(data15min);

                // Adjust thresholds based on time of day
                double threshold5min = now.isAfter(POWER_HOUR_START) ? 0.0010 : 0.0015;  // 0.10% or 0.15%
                double threshold10min = now.isAfter(POWER_HOUR_START) ? 0.0020 : 0.0025; // 0.20% or 0.25%
                double threshold15min = now.isAfter(POWER_HOUR_START) ? 0.0030 : 0.0035; // 0.30% or 0.35%

                // Check if opposing with weighted importance
                boolean opposing5min = isCallSignal ?
                        momentum.momentum5min < -threshold5min : momentum.momentum5min > threshold5min;
                boolean opposing10min = isCallSignal ?
                        momentum.momentum10min < -threshold10min : momentum.momentum10min > threshold10min;
                boolean opposing15min = isCallSignal ?
                        momentum.momentum15min < -threshold15min : momentum.momentum15min > threshold15min;

                // 5-min has highest weight for 0DTE
                momentum.isOpposing = (opposing5min && opposing10min) ||
                        (opposing5min && opposing15min) ||
                        (opposing5min && Math.abs(momentum.momentum5min) > threshold5min * 2);

                results.put(leader, momentum);
            }

            return results;
        }

        private double calculateMomentum(List<MarketData> data) {
            if (data.isEmpty()) return 0.0;

            MarketData firstData = data.get(data.size() - 1); // Oldest
            MarketData lastData = data.get(0); // Most recent

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

        @Getter @Setter
        class GreeksValidationResult {
            boolean isValid;
            String reason;

            GreeksValidationResult(boolean valid, String reason) {
                this.isValid = valid;
                this.reason = reason;
            }
        }

        public GreeksValidationResult validateGreeks(Signal signal, double confidence, String analysisId) {
            try {
                LocalTime now = LocalTime.now(ET_ZONE);
                long minutesToClose = Duration.between(now, LocalTime.of(16, 0)).toMinutes();

                Double delta = signal.getDelta();
                Double gamma = signal.getGamma();
                Double theta = signal.getTheta();

                // Check if Greeks are available
                if (delta == null || gamma == null || theta == null) {
                    log.warn("[GREEKS-VALIDATOR][{}] Greeks not available - using defaults", analysisId);
                    // Don't block if Greeks unavailable
                    return new GreeksValidationResult(true, "Greeks unavailable - proceeding with caution");
                }

                // Get option price for theta ratio calculation
                BigDecimal optionPrice = signal.getEntryPrice();
                if (optionPrice == null || optionPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    return new GreeksValidationResult(true, "Unable to calculate theta ratio");
                }

                // Strategy-specific adjustments
                boolean isBreakout = signal.getStrategy().contains("BREAKOUT") ||
                        signal.getStrategy().contains("ORB");
                boolean isReversion = signal.getStrategy().contains("REVERSION");

                // CONFIDENCE-BASED MULTIPLIERS
                double deltaMultiplier = 1.0;
                double thetaMultiplier = 1.0;
                double gammaMultiplier = 1.0;

                if (confidence >= 0.90) {
                    deltaMultiplier = 1.4;   // 40% more lenient
                    thetaMultiplier = 1.3;   // 30% more lenient
                    gammaMultiplier = 1.3;
                    log.info("[GREEKS-VALIDATOR][{}] HIGH CONFIDENCE ({}%) - Relaxed Greeks thresholds",
                            analysisId, (int)(confidence * 100));
                } else if (confidence >= 0.80) {
                    deltaMultiplier = 1.2;   // 20% more lenient
                    thetaMultiplier = 1.15;  // 15% more lenient
                    gammaMultiplier = 1.15;
                    log.info("[GREEKS-VALIDATOR][{}] ELEVATED CONFIDENCE ({}%) - Moderately relaxed Greeks",
                            analysisId, (int)(confidence * 100));
                }

                // DELTA VALIDATION with confidence adjustment
                double minDelta, maxDelta;
                if (minutesToClose < 120) { // Final 2 hours
                    minDelta = 0.30;
                    maxDelta = 0.45 * deltaMultiplier;
                } else if (now.isBefore(LocalTime.of(11, 0))) { // Morning
                    minDelta = isBreakout ? 0.20 : 0.25;
                    maxDelta = 0.50 * deltaMultiplier;
                } else { // Midday
                    minDelta = 0.25;
                    maxDelta = 0.45 * deltaMultiplier;
                }

                // Strategy adjustments
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

                // GAMMA VALIDATION with confidence adjustment
                double maxGamma = minutesToClose < 60 ? 0.10 * gammaMultiplier : // Final hour
                        minutesToClose < 120 ? 0.12 * gammaMultiplier : // Final 2 hours
                                0.15 * gammaMultiplier; // Earlier

                double gammaAbs = Math.abs(gamma);
                if (gammaAbs > maxGamma) {
                    // Extra strict for ultra-high gamma (even with confidence)
                    if (gammaAbs > 0.20 * gammaMultiplier) {
                        return new GreeksValidationResult(false,
                                String.format("Gamma too high (%.3f) - position unstable", gammaAbs));
                    }
                    // Warning level - only block if close to expiry
                    if (minutesToClose < 120) {
                        return new GreeksValidationResult(false,
                                String.format("High gamma (%.3f) near expiry - whipsaw risk", gammaAbs));
                    }
                }

                // GAMMA/DELTA RATIO with confidence adjustment
                if (deltaAbs > 0.001) { // Avoid division by very small numbers
                    double gammaDeltaRatio = gammaAbs / deltaAbs;
                    double maxGammaDeltaRatio = 0.5 * gammaMultiplier;
                    if (gammaDeltaRatio > maxGammaDeltaRatio) {
                        return new GreeksValidationResult(false,
                                String.format("Gamma/Delta ratio too high (%.2f) - unstable position", gammaDeltaRatio));
                    }
                }

                // THETA VALIDATION with confidence adjustment
                double thetaAbs = Math.abs(theta);
                double optionPriceDouble = optionPrice.doubleValue();

                if (optionPriceDouble > 0.001) { // Avoid division by very small numbers
                    double thetaRatio = thetaAbs / optionPriceDouble;
                    double maxThetaRatio = minutesToClose < 60 ? 0.30 * thetaMultiplier : // 30% in final hour
                            minutesToClose < 120 ? 0.40 * thetaMultiplier : // 40% in final 2 hours
                                    0.50 * thetaMultiplier; // 50% earlier

                    if (thetaRatio > maxThetaRatio) {
                        return new GreeksValidationResult(false,
                                String.format("Theta decay too high (%.0f%% of premium) - melting ice cube",
                                        thetaRatio * 100));
                    }
                }

                // All checks passed
                log.info("[GREEKS-VALIDATOR][{}] Greeks validated - Delta: {:.3f}, Gamma: {:.3f}, Theta: {:.3f}, Confidence: {}%",
                        analysisId, deltaAbs, gammaAbs, thetaAbs, (int)(confidence * 100));

                return new GreeksValidationResult(true, "Greeks within acceptable ranges");

            } catch (Exception e) {
                log.error("[GREEKS-VALIDATOR][{}] Error validating Greeks: {}", analysisId, e.getMessage());
                // Fail open - don't block on error
                return new GreeksValidationResult(true, "Validation error - proceeding");
            }
        }
    }

    // ================================================================================================
    // MODIFIED TREND FILTERING (ALLOWS AI COUNTER-TREND SIGNALS)
    // ================================================================================================

    private boolean isSignalAlignedWithTrend(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                             String marketTrend, String analysisId) {

        // STRICT RULE 1: Block ALL signals in NEUTRAL trend
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

    private boolean isAILeaderLagSignal(Signal signal) {
        return signal != null && signal.getStrategy() != null &&
                signal.getStrategy().contains("AI_LEADER_LAG");
    }

    private boolean isSignalAlignedWithTrendOrAI(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                                 String marketTrend, String analysisId, Signal signal) {

        // AI Leader-Lag signals are allowed to be counter-trend
        if (isAILeaderLagSignal(signal)) {
            log.info("[{}] ✅ AI LEADER-LAG signal allowed (counter-trend permitted): {} {}",
                    analysisId, option.getType(), option.getStrikePrice());
            return true;
        }

        // All other signals must follow trend
        return isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId);
    }

    // ================================================================================================
    // TRADITIONAL STRATEGY IMPLEMENTATIONS (SIMPLIFIED)
    // ================================================================================================

    private void analyzeOpeningRangeBreakout(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend, String analysisId) {
        LocalTime now = LocalTime.now(ET_ZONE);

        // PRIMARY: 30-minute ORB (10:00-10:30 AM optimal window)
        if (now.isAfter(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(10, 30))) {
            analyze30MinuteORB(option, allOptions, ta, signals, marketTrend, analysisId);
        }
    }

    private void analyze30MinuteORB(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                    List<Signal> signals, String marketTrend, String analysisId) {
        LocalTime now = LocalTime.now(ET_ZONE);

        // Check data sufficiency for ORB
        if (!hasORBDataSufficiency(ta.getSymbol(), analysisId)) {
            return;
        }

        OpeningRange range30Min = calculate30MinuteRange(ta);
        if (range30Min == null || !isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) return;

        BigDecimal currentPrice = ta.getCurrentPrice();

        // Enhanced false breakout protection
        if (!validateEnhancedBreakout(currentPrice, range30Min, ta, now)) return;

        // Volume validation
        VolumeRequirement volReq = calculateBreakoutCandleVolume(ta);
        if (!volReq.isMet()) return;

        // Gap analysis
        GapAnalysis gapAnalysis = analyzeGapConditions(ta);

        String strategy = "CALL".equalsIgnoreCase(option.getType()) ? "0DTE_30MIN_ORB_CALL" : "0DTE_30MIN_ORB_PUT";
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());

        if ((isCall && currentPrice.compareTo(range30Min.getHigh()) > 0) ||
                (!isCall && currentPrice.compareTo(range30Min.getLow()) < 0)) {

            double confidence = calculateEnhancedORBConfidence(ta, volReq, gapAnalysis, isCall, now);
            double threshold = calculateDynamicThreshold(now, volReq);

            if (confidence >= threshold) {
                Signal signal = createAdvancedORBSignal(option, allOptions, ta, "BUY", strategy, confidence, marketTrend, range30Min, volReq);
                signals.add(signal);

                log.info("[{}] ✅ {} - Confidence: {}%, Volume: {}, Gap: {}%",
                        analysisId, strategy, (int)(confidence * 100), volReq.getTier(),
                        gapAnalysis.getGapPercentage() * 100);
            }
        }
    }

    private boolean hasORBDataSufficiency(String symbol, String analysisId) {
        try {
            LocalDateTime marketOpen = LocalDateTime.now(ET_ZONE).with(LocalTime.of(9, 30));
            LocalDateTime orbEnd = marketOpen.plusMinutes(30);
            LocalDateTime now = LocalDateTime.now(ET_ZONE);

            // If we're before ORB completion, check if we have data up to now
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

    private void analyzeVWAPBreakout(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                     List<Signal> signals, String marketTrend, String analysisId) {
        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwapUpper = ta.getVwapUpperBand();
        BigDecimal vwapLower = ta.getVwapLowerBand();

        if (ta.isHasRsiDivergence()) {
            log.info("[{}] ⚠️ RSI divergence detected - reducing breakout confidence", analysisId);
        }

        if (currentPrice.compareTo(vwapUpper) > 0 && "CALL".equalsIgnoreCase(option.getType())) {
            BigDecimal breakoutDistance = currentPrice.subtract(vwapUpper)
                    .divide(vwapUpper, 4, RoundingMode.HALF_UP);

            if (breakoutDistance.compareTo(BigDecimal.valueOf(0.002)) >= 0) {
                // USE AI-LEARNED THRESHOLD
                double requiredVolumeRatio = attributeLearning.getOptimalThreshold(
                        "0DTE_VWAP_BREAKOUT_CALL", "VOLUME_THRESHOLD");
                boolean volumeRequirementMet = ta.getVolumeRatio() >= requiredVolumeRatio;

                if (volumeRequirementMet) {
                    double confidence = calculateBreakoutConfidence(ta, true);

                    if (ta.isHasRsiDivergence()) {
                        confidence *= 0.7;
                    }

                    if (confidence >= 0.60) {
                        Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_BREAKOUT_CALL", confidence, marketTrend);
                        signal.setReason(String.format(
                                "VWAP breakout %.2f%% above upper band with %.1fx volume (AI threshold: %.2f)",
                                breakoutDistance.multiply(BigDecimal.valueOf(100)).doubleValue(),
                                ta.getVolumeRatio(), requiredVolumeRatio));
                        signals.add(signal);

                        log.info("[{}] ✅ VWAP Breakout CALL - Confidence: {}% (AI Volume Threshold: {})",
                                analysisId, (int)(confidence * 100), requiredVolumeRatio);
                    }
                }
            }
        }

        if (currentPrice.compareTo(vwapLower) < 0 && "PUT".equalsIgnoreCase(option.getType())) {
            BigDecimal breakoutDistance = vwapLower.subtract(currentPrice)
                    .divide(vwapLower, 4, RoundingMode.HALF_UP);

            if (breakoutDistance.compareTo(BigDecimal.valueOf(0.002)) >= 0) {
                // USE AI-LEARNED THRESHOLD
                double requiredVolumeRatio = attributeLearning.getOptimalThreshold(
                        "0DTE_VWAP_BREAKOUT_PUT", "VOLUME_THRESHOLD");
                boolean volumeRequirementMet = ta.getVolumeRatio() >= requiredVolumeRatio;

                if (volumeRequirementMet) {
                    double confidence = calculateBreakoutConfidence(ta, false);

                    if (ta.isHasRsiDivergence()) {
                        confidence *= 0.7;
                    }

                    if (confidence >= 0.60) {
                        Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_BREAKOUT_PUT", confidence, marketTrend);
                        signal.setReason(String.format(
                                "VWAP breakout %.2f%% below lower band with %.1fx volume (AI threshold: %.2f)",
                                breakoutDistance.multiply(BigDecimal.valueOf(100)).doubleValue(),
                                ta.getVolumeRatio(), requiredVolumeRatio));
                        signals.add(signal);

                        log.info("[{}] ✅ VWAP Breakout PUT - Confidence: {}% (AI Volume Threshold: {})",
                                analysisId, (int)(confidence * 100), requiredVolumeRatio);
                    }
                }
            }
        }
    }

    private void analyzeVWAPDeviationReversion(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                               List<Signal> signals, String marketTrend, String analysisId) {
        if (!ta.isExtendedFromVwap() || ta.getVwapStandardDeviation() == null ||
                ta.getVwapStandardDeviation().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
            return;
        }

        double priceRatio = ta.getPriceToVwapRatio();

        if (priceRatio > 1.0 && "PUT".equalsIgnoreCase(option.getType()) && ta.getRsi() > 70) {
            double confidence = calculateReversionConfidence(ta, false);

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_REVERSION_PUT", confidence, marketTrend);
                signal.setReason(String.format(
                        "Extended %.2f%% above VWAP with RSI %.1f - mean reversion setup",
                        (priceRatio - 1) * 100, ta.getRsi()));
                signals.add(signal);

                log.info("[{}] ✅ VWAP Reversion PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }

        if (priceRatio < 1.0 && "CALL".equalsIgnoreCase(option.getType()) && ta.getRsi() < 30) {
            double confidence = calculateReversionConfidence(ta, true);

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, allOptions, ta, "BUY", "0DTE_VWAP_REVERSION_CALL", confidence, marketTrend);
                signal.setReason(String.format(
                        "Extended %.2f%% below VWAP with RSI %.1f - mean reversion setup",
                        (1 - priceRatio) * 100, ta.getRsi()));
                signals.add(signal);

                log.info("[{}] ✅ VWAP Reversion CALL - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }
    }

    private void analyzeVWAPSupportResistance(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                              List<Signal> signals, String marketTrend, String analysisId) {
        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
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
                    signal.setReason(String.format("VWAP support at $%.2f held with volume confirmation", vwap.doubleValue()));
                    signals.add(signal);

                    log.info("[{}] ✅ VWAP Support CALL - Confidence: {}%",
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
                    signal.setReason(String.format("VWAP resistance at $%.2f rejected with volume confirmation", vwap.doubleValue()));
                    signals.add(signal);

                    log.info("[{}] ✅ VWAP Resistance PUT - Confidence: {}%",
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

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        boolean nowAboveVWAP = currentPrice.compareTo(vwap) > 0;
        boolean nowBelowVWAP = currentPrice.compareTo(vwap) < 0;

        // Get recent data for pattern confirmation
        List<MarketData> recentData = marketDataRepository.findRecentData(ta.getSymbol(), 5);
        if (recentData.size() < 3) return;

        boolean recentlyTestedFromBelow = checkRecentVWAPTest(recentData, vwap, false);
        boolean recentlyTestedFromAbove = checkRecentVWAPTest(recentData, vwap, true);

        double penetration = Math.abs(currentPrice.subtract(vwap)
                .divide(vwap, 4, RoundingMode.HALF_UP).doubleValue());

        if (penetration < 0.0005 || penetration > 0.003) return;

        // VWAP RECLAIM LONG (CALL)
        if ("CALL".equalsIgnoreCase(option.getType()) && nowAboveVWAP && recentlyTestedFromBelow) {
            if ("UP".equals(marketTrend) && ta.getVolumeRatio() > 1.0) {
                double confidence = calculateVWAPReclaimConfidence(ta, penetration, true);

                if (confidence >= 0.75) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY",
                            "0DTE_VWAP_RECLAIM_CALL", confidence, marketTrend);

                    signal.setReason(String.format(
                            "VWAP reclaim after test - penetration: %.2f%%, volume: %.1fx",
                            penetration * 100, ta.getVolumeRatio()));

                    signals.add(signal);
                    log.info("[{}] ✅ VWAP RECLAIM CALL - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            }
        }

        // VWAP FAILED RECLAIM (PUT)
        if ("PUT".equalsIgnoreCase(option.getType()) && nowBelowVWAP && recentlyTestedFromAbove) {
            if ("DOWN".equals(marketTrend) && ta.getVolumeRatio() > 1.0) {
                double confidence = calculateVWAPReclaimConfidence(ta, penetration, false);

                if (confidence >= 0.75) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY",
                            "0DTE_VWAP_FAILED_RECLAIM_PUT", confidence, marketTrend);

                    signal.setReason(String.format(
                            "VWAP failed reclaim - rejection: %.2f%%, volume: %.1fx",
                            penetration * 100, ta.getVolumeRatio()));

                    signals.add(signal);
                    log.info("[{}] ✅ VWAP FAILED RECLAIM PUT - Confidence: {}%",
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

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        // Check if we're close to VWAP (within 0.5%)
        if (Math.abs(ta.getPriceToVwapRatio() - 1.0) > 0.005) {
            return;
        }

        boolean volumeRequirementMet = ta.getVolumeRatio() >= MIN_VOLUME_RATIO_STANDARD;
        if (!volumeRequirementMet) {
            return;
        }

        boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
        boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

        // CALL signals when price is above VWAP and trending up
        if (isCallOption && currentPrice.compareTo(vwap) >= 0 && "UP".equals(marketTrend)) {
            double confidence = calculateBounceConfidence(ta, true);

            if (confidence >= 0.70) {
                Signal signal = createSignal(option, allOptions, ta, "BUY",
                        "0DTE_VWAP_BOUNCE_CALL", confidence, marketTrend);
                signal.setReason("VWAP support bounce with volume confirmation");
                signals.add(signal);

                log.info("[{}] ✅ VWAP Bounce CALL - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }

        // PUT signals when price is below VWAP and trending down
        if (isPutOption && currentPrice.compareTo(vwap) <= 0 && "DOWN".equals(marketTrend)) {
            double confidence = calculateBounceConfidence(ta, false);

            if (confidence >= 0.70) {
                Signal signal = createSignal(option, allOptions, ta, "BUY",
                        "0DTE_VWAP_BOUNCE_PUT", confidence, marketTrend);
                signal.setReason("VWAP resistance rejection with volume confirmation");
                signals.add(signal);

                log.info("[{}] ✅ VWAP Bounce PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }
    }

    private void analyzeDirectionalVWAPBounce(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                              List<Signal> signals, String marketTrend, String analysisId) {

        if (!isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, analysisId)) {
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        // Must be very close to VWAP (within 0.2%)
        double vwapDistance = Math.abs(currentPrice.subtract(vwap)
                .divide(vwap, 4, RoundingMode.HALF_UP).doubleValue());

        if (vwapDistance > 0.002) {
            return;
        }

        boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
        boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

        // VWAP BOUNCE LONG (CALL)
        if (isCallOption && "UP".equals(marketTrend)) {
            if (ta.getVolumeRatio() > 1.0) {
                double confidence = calculateDirectionalBounceConfidence(ta, vwapDistance, true);

                if (confidence >= 0.75) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY",
                            "0DTE_VWAP_BOUNCE_CALL", confidence, marketTrend);

                    signal.setReason(String.format(
                            "VWAP bounce setup - Distance: %.2f%%, Volume: %.1fx, RSI: %.1f",
                            vwapDistance * 100, ta.getVolumeRatio(), ta.getRsi()));

                    signals.add(signal);
                    log.info("[{}] ✅ VWAP BOUNCE CALL - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            }
        }

        // VWAP BOUNCE SHORT (PUT)
        if (isPutOption && "DOWN".equals(marketTrend)) {
            if (ta.getVolumeRatio() > 1.0) {
                double confidence = calculateDirectionalBounceConfidence(ta, vwapDistance, false);

                if (confidence >= 0.75) {
                    Signal signal = createSignal(option, allOptions, ta, "BUY",
                            "0DTE_VWAP_BOUNCE_PUT", confidence, marketTrend);

                    signal.setReason(String.format(
                            "VWAP rejection setup - Distance: %.2f%%, Volume: %.1fx, RSI: %.1f",
                            vwapDistance * 100, ta.getVolumeRatio(), ta.getRsi()));

                    signals.add(signal);
                    log.info("[{}] ✅ VWAP BOUNCE PUT - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            }
        }
    }

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

        // Distance from VWAP boost (closer = higher confidence)
        if (vwapDistance <= 0.001) confidence += 0.15;      // Within 0.1%
        else if (vwapDistance <= 0.002) confidence += 0.10; // Within 0.2%

        // Volume confirmation
        if (ta.getVolumeRatio() >= 2.0) confidence += 0.10;
        else if (ta.getVolumeRatio() >= 1.5) confidence += 0.07;
        else if (ta.getVolumeRatio() >= 1.2) confidence += 0.03;

        // RSI confirmation (not oversold/overbought)
        if (ta.getRsi() > 40 && ta.getRsi() < 60) {
            confidence += 0.05;
        }

        // Time of day adjustment
        LocalTime now = LocalTime.now(ET_ZONE);
        if (isOptimalVWAPTime(now)) {
            confidence += 0.05;
        }

        return Math.min(confidence, 0.95);
    }

    private boolean isOptimalVWAPTime(LocalTime now) {
        // Morning momentum window (9:50-11:30 AM)
        if (now.isAfter(LocalTime.of(9, 50)) && now.isBefore(LocalTime.of(11, 30))) {
            return true;
        }

        // Afternoon momentum window (1:00-3:00 PM)
        if (now.isAfter(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(15, 0))) {
            return true;
        }

        return false;
    }

    // ================================================================================================
    // HELPER AND UTILITY METHODS
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

        // Calculate day's range
        QuoteResponse qqqQuote = tradierService.getQuote("QQQ");
        if (qqqQuote != null && qqqQuote.getQuote() != null) {
            Quote quote = qqqQuote.getQuote();
            if (quote.getHigh() != null && quote.getLow() != null) {
                dayRange = quote.getHigh().subtract(quote.getLow());
            }
        }

        // Calculate price movement percentage
        BigDecimal priceMove = BigDecimal.ZERO;
        if (ta.getPreviousClose() != null && ta.getPreviousClose().compareTo(BigDecimal.ZERO) > 0) {
            priceMove = currentPrice.subtract(ta.getPreviousClose())
                    .abs()
                    .divide(ta.getPreviousClose(), 4, RoundingMode.HALF_UP);
        }

        // Determine strike range based on volatility
        int strikesFromATM;
        if (priceMove.compareTo(BigDecimal.valueOf(0.005)) > 0 ||
                dayRange.compareTo(currentPrice.multiply(BigDecimal.valueOf(0.01))) > 0) {
            strikesFromATM = 4; // High volatility - wider strikes
            log.info("[{}] High volatility detected - selecting up to {} strikes from ATM",
                    analysisId, strikesFromATM);
        } else {
            strikesFromATM = 2; // Normal volatility - tighter strikes
            log.info("[{}] Normal volatility - selecting up to {} strikes from ATM",
                    analysisId, strikesFromATM);
        }

        // Filter options by strike distance
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

        // Log selected strikes
        selected.forEach(opt -> {
            BigDecimal distance = opt.getStrikePrice().subtract(currentPrice);
            log.info("[{}] Selected: {} ${} (${} from current, Vol: {}, OI: {})",
                    analysisId, opt.getType(), opt.getStrikePrice(),
                    distance.compareTo(BigDecimal.ZERO) > 0 ? "+" + distance : distance,
                    opt.getVolume(), opt.getOpenInterest());
        });

        return selected;
    }

    private void saveSignalsAfterConfirmation(List<Signal> signals, TechnicalAnalysis ta,
                                              String marketTrend, String analysisId) {
        for (Signal signal : signals) {
            // FINAL EXPIRATION CHECK
            LocalDate today = LocalDate.now(ET_ZONE);
            LocalDate signalExpiration = extractExpirationFromOptionSymbol(signal.getOptionSymbol());

            if (!today.equals(signalExpiration)) {
                log.warn("[{}] ❌ Signal REJECTED in final check: {} - Not 0DTE",
                        analysisId, signal.getOptionSymbol());
                continue;
            }

            // SHAP EXPLANATION & VALIDATION
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

            // CAPTURE ATTRIBUTES FOR AI LEARNING
            attributeLearning.captureSignalGeneration(signal, ta, analysisId);

            // Execute signal immediately
            executeSignalImmediately(signal, explanation, analysisId);

            // START VERIFICATION PROCESS
            if (signal.getId() != null) {
                attributeLearning.verifyAndLearn(signal.getId().toString());
            }
        }

        // CAPTURE MISSED OPPORTUNITIES
        if (signals.isEmpty() && !ta.getMarketRegime().equals(MarketRegime.CHOPPY)) {
            attributeLearning.captureMissedOpportunity(ta, marketTrend, analysisId);
        }
    }

    private LocalDate extractExpirationFromOptionSymbol(String optionSymbol) {
        try {
            if (optionSymbol == null || optionSymbol.length() < 10) {
                return LocalDate.now(ET_ZONE);
            }

            String datePart;
            if (optionSymbol.contains("_")) {
                String afterUnderscore = optionSymbol.split("_")[1];
                datePart = afterUnderscore.substring(0, 6); // MMDDYY

                int month = Integer.parseInt(datePart.substring(0, 2));
                int day = Integer.parseInt(datePart.substring(2, 4));
                int year = 2000 + Integer.parseInt(datePart.substring(4, 6));

                return LocalDate.of(year, month, day);
            } else {
                datePart = optionSymbol.substring(3, 9); // YYMMDD

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

        // For non-AI signals, check trend alignment
        if (!strategy.contains("AI_LEADER_LAG") &&
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

            // Store Greeks from option with proper null checks
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

            // DYNAMIC TARGET AND STOP CALCULATION
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
        boolean isAISignal = strategy.contains("AI_LEADER_LAG");

        // AI signals can be counter-trend, traditional signals must be aligned
        boolean isAligned = isAISignal ||
                ((isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend)));

        BigDecimal targetMultiplier;

        if (isAligned) {
            // Bigger targets for aligned positions
            if (strategy.contains("AI_LEADER_LAG")) {
                targetMultiplier = BigDecimal.valueOf(2.5); // AI gets aggressive targets
            } else if (strategy.contains("30MIN_ORB")) {
                targetMultiplier = BigDecimal.valueOf(2.2);
            } else if (strategy.contains("VWAP_BREAKOUT")) {
                targetMultiplier = BigDecimal.valueOf(2.0);
            } else if (strategy.contains("VWAP_REVERSION")) {
                targetMultiplier = BigDecimal.valueOf(1.7);
            } else {
                targetMultiplier = BigDecimal.valueOf(1.8); // Default
            }
        } else {
            // Smaller targets for counter-trend (shouldn't happen for traditional signals)
            targetMultiplier = BigDecimal.valueOf(1.4);
        }

        // Time decay for 0DTE
        LocalTime now = LocalTime.now(ET_ZONE);
        long minutesToClose = Duration.between(now, LocalTime.of(16, 0)).toMinutes();

        if (minutesToClose < 120) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(0.7));
        } else if (minutesToClose < 180) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(0.85));
        }

        // Volatility adjustment
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
        boolean isAISignal = strategy.contains("AI_LEADER_LAG");

        // AI signals can be counter-trend
        boolean isAligned = isAISignal ||
                ((isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend)));

        BigDecimal baseStopPercentage;

        if (isAligned) {
            if (strategy.contains("AI_LEADER_LAG")) {
                baseStopPercentage = BigDecimal.valueOf(0.30); // AI gets optimized stops
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
            baseStopPercentage = BigDecimal.valueOf(0.25); // Tight stop for counter-trend
        }

        // Volatility adjustment
        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            baseStopPercentage = baseStopPercentage.multiply(BigDecimal.valueOf(1.2));
        }

        // Time adjustment
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

        // Extract option details
        Option option = getOptionFromSignal(signal, null); // You'll need to pass options list
        if (option != null) {
            trade.setStrikePrice(option.getStrikePrice());
            trade.setSide(option.getType()); // CALL or PUT
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

        // =====================================
        // PRICING INFORMATION
        // =====================================
        trade.setEntryPrice(fillPrice);
        trade.setCurrentPrice(fillPrice);
        trade.setFillPrice(fillPrice);
        trade.setExecutionPrice(fillPrice);
        trade.setTargetPrice(signal.getTargetPrice());
        trade.setOriginalTarget(signal.getTargetPrice());
        trade.setStopLoss(signal.getStopLoss());
        trade.setOriginalStop(signal.getStopLoss());

        // Get bid/ask data from fresh quote
        try {
            QuoteResponse quote = tradierService.getQuote(signal.getOptionSymbol());
            if (quote != null && quote.getQuote() != null) {
                Quote q = quote.getQuote();
                trade.setBidAtEntry(q.getBid());
                trade.setAskAtEntry(q.getAsk());
                if (q.getBid() != null && q.getAsk() != null) {
                    BigDecimal spread = q.getAsk().subtract(q.getBid());
                    trade.setSpreadAtEntry(spread);
                    // Calculate spread percentage
                    if (fillPrice.compareTo(BigDecimal.ZERO) > 0) {
                        double spreadPercent = spread.divide(fillPrice, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100)).doubleValue();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not get bid/ask data for trade creation: {}", e.getMessage());
        }

        // Initialize profit tracking
        trade.setRealizedPnl(BigDecimal.ZERO);
        trade.setUnrealizedPnl(BigDecimal.ZERO);
        trade.setMaxProfitReached(BigDecimal.ZERO);
        trade.setMaxDrawdown(BigDecimal.ZERO);
        trade.setHighestPrice(fillPrice);
        trade.setLowestPrice(fillPrice);

        // =====================================
        // TIMESTAMPS
        // =====================================
        LocalDateTime now = LocalDateTime.now();
        ZonedDateTime zonedNow = ZonedDateTime.now();

        trade.setCreatedAt(zonedNow);
        trade.setEntryTime(now);
        trade.setSignalGeneratedAt(signal.getCreatedAt());
        trade.setExecutionAttemptedAt(now);
        trade.setOrderFilledAt(now);
        trade.setLastSyncTime(now);

        // =====================================
        // EXECUTION AND ORDER TRACKING
        // =====================================
        String orderId = signal.getMetadata().get("orderId");
        trade.setOrderId(orderId);
        trade.setOrderStatus("FILLED");
        trade.setOrderType("MARKET");
        trade.setOrderSide("buy_to_open");
        trade.setOrderDuration("DAY");
        trade.setRetryCount(0);

        // =====================================
        // MARKET CONTEXT FIELDS
        // =====================================
        trade.setMarketTrend(signal.getMarketTrend());
        trade.setMarketRegime(signal.getMarketRegime());
        trade.setTimeSlot(determineTimeSlot(LocalTime.now()));

        // Get underlying price (QQQ current price)
        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(signal.getSymbol());
            if (ta != null) {
                trade.setUnderlyingPriceAtEntry(ta.getCurrentPrice());
                trade.setMarketTrendStrength(ta.getStrength());
            }
        } catch (Exception e) {
            log.debug("Could not get underlying price for trade: {}", e.getMessage());
        }

        // Market indicators
        trade.setVixAtEntry(getVixLevel());
        trade.setMarketBreadthAtEntry(calculateMarketBreadth());

        // =====================================
        // EXPIRATION AND TIME DECAY
        // =====================================
        LocalDate expirationDate = extractExpirationFromOptionSymbol(signal.getOptionSymbol());
        trade.setExpirationDate(expirationDate.atTime(16, 0).atZone(ET_ZONE));

        // Calculate time to expiry
        LocalDateTime expiryTime = expirationDate.atTime(16, 0);
        long minutesToExpiry = Duration.between(now, expiryTime).toMinutes();
        trade.setMinutesToExpiry((int) minutesToExpiry);
        trade.setDaysToExpiry(signal.getDaysToExpiry());

        // Time decay factor (0DTE has high decay)
        double timeDecayFactor = minutesToExpiry > 0 ? 1.0 / minutesToExpiry : 1.0;
        trade.setTimeDecayFactor(timeDecayFactor);

        // =====================================
        // OPTION GREEKS
        // =====================================
        trade.setImpliedVolatility(signal.getImpliedVolatility());
        trade.setDelta(signal.getDelta());
        trade.setGamma(signal.getGamma());
        trade.setTheta(signal.getTheta());
        trade.setVega(signal.getVega());
        trade.setRho(signal.getRho());

        // =====================================
        // TECHNICAL ANALYSIS DATA
        // =====================================
        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(signal.getSymbol());
            if (ta != null) {
                trade.setVolumeRatio(ta.getVolumeRatio());
                trade.setMomentumStrength(ta.getMomentumStrength());
                trade.setRsiAtEntry(ta.getRsi());

                // VWAP deviation
                if (ta.getVwap() != null && ta.getCurrentPrice() != null) {
                    double vwapDeviation = ta.getCurrentPrice().subtract(ta.getVwap())
                            .divide(ta.getVwap(), 4, RoundingMode.HALF_UP).doubleValue();
                    trade.setVwapDeviation(vwapDeviation);
                }

                // ATR ratio
                if (ta.getAverageTrueRange() != null && ta.getCurrentPrice() != null) {
                    double atrRatio = ta.getAverageTrueRange()
                            .divide(ta.getCurrentPrice(), 4, RoundingMode.HALF_UP).doubleValue();
                    trade.setAtrRatio(atrRatio);
                }

                // MACD signal
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

        // =====================================
        // RISK MANAGEMENT
        // =====================================
        BigDecimal positionValue = fillPrice.multiply(BigDecimal.valueOf(100)); // Options are 100 shares
        trade.setMaxRisk(positionValue);
        trade.setMaxProfitPotential(signal.getTargetPrice().multiply(BigDecimal.valueOf(100)));

        // Calculate risk/reward ratio
        if (signal.getStopLoss() != null && signal.getStopLoss().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal risk = fillPrice.subtract(signal.getStopLoss());
            BigDecimal reward = signal.getTargetPrice().subtract(fillPrice);
            if (risk.compareTo(BigDecimal.ZERO) > 0) {
                double riskRewardRatio = reward.divide(risk, 4, RoundingMode.HALF_UP).doubleValue();
                trade.setRiskRewardRatio(riskRewardRatio);
            }
        }

        // =====================================
        // BAYESIAN AND ML DATA
        // =====================================
        trade.setSignalConfidence(signal.getConfidence());
        trade.setOriginalConfidence(signal.getConfidence());

        // Expected value based on confidence and risk/reward
        if (trade.getRiskRewardRatio() != null) {
            double expectedValue = (signal.getConfidence() * trade.getRiskRewardRatio()) -
                    ((1 - signal.getConfidence()) * 1.0);
            trade.setExpectedValue(expectedValue);
        }

        // =====================================
        // TRADE STATUS AND MANAGEMENT
        // =====================================
        trade.setStatus("OPEN");
        trade.setAutoClosed(false);
        trade.setManualIntervention(false);
        trade.setTrailingActivated(false);
        trade.setStopAdjustmentsCount(0);
        trade.setTargetAdjustmentsCount(0);

        // =====================================
        // LINKING AND TRACKING
        // =====================================
        trade.setSignalId(signal.getId());
        trade.setStrategyInstanceId(analysisId);
        trade.setExecutionSessionId(UUID.randomUUID().toString().substring(0, 8));

        // =====================================
        // LEARNING AND ADAPTATION
        // =====================================
        String learningPhase = signal.getMetadata().get("learningPhase");
        trade.setLearningPhase(learningPhase != null ? learningPhase : "MARKET_ONLY");
        trade.setModelVersion("1.0");
        trade.setFeatureSetVersion("2024.1");

        // =====================================
        // POSITION MANAGEMENT
        // =====================================
        // Assume 1% of portfolio per trade (adjust based on your risk management)
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

    // Helper method to count populated fields for logging
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

    // Helper method to calculate current portfolio heat
    private double calculateCurrentPortfolioHeat() {
        try {
            // Count open trades
            long openTrades = tradeRepository.findAll().stream()
                    .filter(trade -> "OPEN".equals(trade.getStatus()))
                    .count();

            // Simple calculation - adjust based on your risk management
            return Math.min(openTrades * 1.0, 10.0); // Max 10% portfolio heat

        } catch (Exception e) {
            return 1.0; // Default to 1% if calculation fails
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
            if (signal.getStrategy().contains("AI_LEADER_LAG")) {
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
    // ORB SPECIFIC METHODS
    // ================================================================================================

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
    // VWAP SPECIFIC METHODS
    // ================================================================================================

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

        // Null checks
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

    // ================================================================================================
    // UTILITY AND HELPER METHODS
    // ================================================================================================

    private int getAdjustedMinVolume(LocalTime now) {
        if (now.isBefore(LocalTime.of(10, 30))) {
            return minVolume / 2;
        } else if (now.isAfter(LocalTime.of(15, 0))) {
            return minVolume / 2;
        }
        return minVolume;
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

        log.info("[{}] Base filtering - Volume: {}, IV: {:.3f}",
                analysisId, adjustedMinVolume, adjustedMinIV);

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
//        if ("DOWN".equals(marketTrend) && "CALL".equalsIgnoreCase(option.getType())) {
//            log.warn("BLOCKED: CALL option on DOWN trend");
//            return false;
//        }
        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean aligned = (isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend));

        // Allow AI signals to be counter-trend (will be handled in createSignal)
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

    // ================================================================================================
    // AI LEARNING ENGINE - PERSISTENT VERSION
    // ================================================================================================

    public class AILearningEngine {
        private final Map<String, List<TradeOutcome>> performanceHistory = new ConcurrentHashMap<>();
        private final Map<String, Double> qTable = new ConcurrentHashMap<>();
        private double explorationRate = 0.15;
        private final double learningRate = 0.2;
        private int totalTrades = 0;
        private int successfulTrades = 0;

        public void loadAIState() {
            try {
                // Load Q-table entries
                List<AIState> qEntries = aiStateRepository.findByStrategyTypeOrderByLastUpdatedDesc("Q_TABLE");
                for (AIState entry : qEntries) {
                    if (entry.getStateKey() != null && entry.getActionKey() != null && entry.getQValue() != null) {
                        String key = entry.getStateKey() + "_" + entry.getActionKey();
                        qTable.put(key, entry.getQValue());
                    }
                }

                // Load exploration rate and stats
                List<AIState> configEntries = aiStateRepository.findByStrategyTypeOrderByLastUpdatedDesc("CONFIG");
                if (!configEntries.isEmpty()) {
                    AIState config = configEntries.get(0);
                    if (config.getExplorationRate() != null) {
                        explorationRate = config.getExplorationRate();
                    }
                    if (config.getTotalTrades() != null) {
                        totalTrades = config.getTotalTrades();
                    }
                    if (config.getSuccessfulTrades() != null) {
                        successfulTrades = config.getSuccessfulTrades();
                    }
                }

                // Load performance history from recent successful trades
                loadPerformanceHistoryFromTrades();

                log.info("[AI-LOAD] Loaded AI state - Q-table: {} entries, Exploration: {:.3f}, Trades: {}/{}",
                        qTable.size(), explorationRate, successfulTrades, totalTrades);

            } catch (Exception e) {
                log.error("[AI-LOAD] Error loading AI state: {}", e.getMessage());
                // Initialize with defaults
                explorationRate = 0.15;
                totalTrades = 0;
                successfulTrades = 0;
            }
        }

        public void saveAIState() {
            try {
                // Save Q-table entries
                for (Map.Entry<String, Double> entry : qTable.entrySet()) {
                    String[] parts = entry.getKey().split("_");
                    if (parts.length >= 2) {
                        String stateKey = parts[0];
                        String actionKey = parts[1];

                        Optional<AIState> existing = aiStateRepository.findByStateAndAction(stateKey, actionKey);
                        AIState aiState = existing.orElse(new AIState());

                        aiState.setStateKey(stateKey);
                        aiState.setActionKey(actionKey);
                        aiState.setQValue(entry.getValue());
                        aiState.setStrategyType("Q_TABLE");
                        aiState.setLastUpdated(LocalDateTime.now());

                        aiStateRepository.save(aiState);
                    }
                }

                // Save configuration
                AIState config = new AIState();
                config.setStrategyType("CONFIG");
                config.setExplorationRate(explorationRate);
                config.setTotalTrades(totalTrades);
                config.setSuccessfulTrades(successfulTrades);
                config.setLastUpdated(LocalDateTime.now());
                config.setLearningPhase(determineLearningPhase());

                aiStateRepository.save(config);

                log.debug("[AI-SAVE] Saved AI state - Q-table: {} entries, Config updated", qTable.size());

            } catch (Exception e) {
                log.error("[AI-SAVE] Error saving AI state: {}", e.getMessage());
            }
        }

        private void loadPerformanceHistoryFromTrades() {
            try {
                LocalDateTime since = LocalDateTime.now().minusDays(30); // Last 30 days
                List<Trade> recentAITrades = tradeRepository.findAll().stream()
                        .filter(trade -> trade.getStrategy() != null && trade.getStrategy().contains("AI_LEADER_LAG"))
                        .filter(trade -> trade.getEntryTime() != null && trade.getEntryTime().isAfter(since))
                        .filter(trade -> "CLOSED".equals(trade.getStatus()) || "FAILED".equals(trade.getStatus()))
                        .collect(Collectors.toList());

                for (Trade trade : recentAITrades) {
                    LeaderLagStrategy strategy = trade.getStrategy().contains("REVERSE") ?
                            LeaderLagStrategy.REVERSE : LeaderLagStrategy.STANDARD;

                    double pnlPercent = calculateTradePerformance(trade);
                    boolean isWinner = pnlPercent > 0;

                    // Reconstruct simplified features
                    MarketFeatures features = new MarketFeatures(0, 0, 0, 18,
                            trade.getEntryTime().getHour() * 60, 1, 1.0, 0);

                    TradeOutcome outcome = new TradeOutcome(strategy, features, pnlPercent, isWinner, trade.getEntryTime());
                    performanceHistory.computeIfAbsent(strategy.toString(), k -> new ArrayList<>()).add(outcome);
                }

                log.info("[AI-LOAD] Loaded {} recent AI trades into performance history", recentAITrades.size());

            } catch (Exception e) {
                log.error("[AI-LOAD] Error loading performance history: {}", e.getMessage());
            }
        }

        private String determineLearningPhase() {
            if (totalTrades < 50) return "EXPLORATION";
            if (totalTrades < 200) return "LEARNING";
            return "EXPLOITATION";
        }

        public AIDecision decideStrategy(MarketContext context, String analysisId) {
            MarketFeatures features = extractFeatures(context);
            String regime = classifyRegime(features);

            double standardScore = getPerformanceScore("STANDARD", features);
            double reverseScore = getPerformanceScore("REVERSE", features);

            String stateKey = encodeState(features);
            double qStandard = qTable.getOrDefault(stateKey + "_STANDARD", 0.5);
            double qReverse = qTable.getOrDefault(stateKey + "_REVERSE", 0.5);

            double combinedStandard = (standardScore * 0.4) + (qStandard * 0.6);
            double combinedReverse = (reverseScore * 0.4) + (qReverse * 0.6);

            LeaderLagStrategy selectedStrategy;
            double confidence;

            if (Math.random() < explorationRate) {
                selectedStrategy = Math.random() < 0.5 ? LeaderLagStrategy.STANDARD : LeaderLagStrategy.REVERSE;
                confidence = 0.6;
                log.info("[AI-EXPLORE][{}] Exploring with {} (ε={})", analysisId, selectedStrategy, explorationRate);
            } else {
                if (combinedStandard > combinedReverse) {
                    selectedStrategy = LeaderLagStrategy.STANDARD;
                    confidence = Math.min(0.95, 0.5 + (combinedStandard - combinedReverse));
                } else {
                    selectedStrategy = LeaderLagStrategy.REVERSE;
                    confidence = Math.min(0.95, 0.5 + (combinedReverse - combinedStandard));
                }
            }

            if (confidence < 0.4) {
                selectedStrategy = LeaderLagStrategy.NONE;
            }

            String reasoning = generateReasoning(features, regime, standardScore, reverseScore,
                    qStandard, qReverse, selectedStrategy, context);

            return new AIDecision(selectedStrategy, confidence, reasoning, regime, features);
        }

        public void updateFromTrade(Trade trade, Signal signal) {
            try {
                if (!signal.getStrategy().contains("AI_LEADER_LAG")) return;

                totalTrades++;
                MarketFeatures features = reconstructFeatures(signal);
                LeaderLagStrategy strategy = signal.getStrategy().contains("REVERSE") ?
                        LeaderLagStrategy.REVERSE : LeaderLagStrategy.STANDARD;

                double pnlPercent = calculatePnLPercent(trade);
                boolean isWinner = pnlPercent > 0;

                if (isWinner) successfulTrades++;

                // Update Q-table
                String stateKey = encodeState(features);
                String actionKey = stateKey + "_" + strategy.toString();
                double currentQ = qTable.getOrDefault(actionKey, 0.5);
                double reward = Math.max(-1.0, Math.min(1.0, pnlPercent / 50.0));
                double newQ = currentQ + learningRate * (reward - currentQ);
                qTable.put(actionKey, newQ);

                // Update performance history
                TradeOutcome outcome = new TradeOutcome(strategy, features, pnlPercent, isWinner, trade.getEntryTime());
                performanceHistory.computeIfAbsent(strategy.toString(), k -> new ArrayList<>()).add(outcome);

                // Decay exploration
                explorationRate = Math.max(0.05, explorationRate * 0.995);

                log.info("[AI-LEARN] Updated from trade: {} P&L: {}%, Reward: {:.3f}, New Q: {:.3f}",
                        trade.getOptionSymbol(), String.format("%.2f", pnlPercent), reward, newQ);

            } catch (Exception e) {
                log.error("[AI-LEARN] Error updating from trade: {}", e.getMessage());
            }
        }

//        private MarketFeatures extractFeatures(MarketContext context) {
//            return new MarketFeatures(
//                    context.spyMomentum, // This is actually strongest leader momentum
//                    context.qqqMomentum,
//                    context.correlation,
//                    context.vixLevel,
//                    context.timeOfDay,
//                    context.dayOfWeek,
//                    context.volumeRatio,
//                    context.momentumDivergence
//            );
//        }

        private String classifyRegime(MarketFeatures features) {
            if (features.momentumDivergence > 0.4) return "MOMENTUM_DIVERGENCE";
            if (features.vixLevel > 25) return "HIGH_VOLATILITY";
            if (features.timeOfDay > 930) return "POWER_HOUR";
            if (features.spyMomentum > 0.3) return "TRENDING_UP";
            if (features.spyMomentum < -0.3) return "TRENDING_DOWN";
            return "NEUTRAL";
        }

        private double getPerformanceScore(String strategy, MarketFeatures features) {
            List<TradeOutcome> outcomes = performanceHistory.get(strategy);
            if (outcomes == null || outcomes.isEmpty()) return 0.5;

            List<TradeOutcome> similar = outcomes.stream()
                    .filter(o -> isSimilarCondition(o.features, features))
                    .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
                    .limit(20)
                    .collect(Collectors.toList());

            if (similar.isEmpty()) return 0.5;

            double avgPnL = similar.stream().mapToDouble(o -> o.pnlPercent).average().orElse(0);
            double winRate = similar.stream().mapToDouble(o -> o.isWinner ? 1.0 : 0.0).average().orElse(0);

            return Math.max(0.1, Math.min(0.9, 0.5 + (avgPnL / 100.0) + (winRate - 0.5)));
        }

        private boolean isSimilarCondition(MarketFeatures a, MarketFeatures b) {
            return Math.abs(a.spyMomentum - b.spyMomentum) < 0.3 &&
                    Math.abs(a.momentumDivergence - b.momentumDivergence) < 0.2 &&
                    Math.abs(a.correlation - b.correlation) < 0.3 &&
                    Math.abs(a.timeOfDay - b.timeOfDay) < 120;
        }

        private String encodeState(MarketFeatures features) {
            int momentumBin = Math.max(0, Math.min(3, (int)((features.spyMomentum + 1) * 2)));
            int divergenceBin = Math.max(0, Math.min(2, (int)(features.momentumDivergence * 3)));
            int timeBin = features.timeOfDay / 240;
            int correlationBin = Math.max(0, Math.min(1, (int)(features.correlation * 2)));

            return String.format("%d_%d_%d_%d", momentumBin, divergenceBin, timeBin, correlationBin);
        }

        private String generateReasoning(MarketFeatures features, String regime, double standardScore,
                                         double reverseScore, double qStandard, double qReverse,
                                         LeaderLagStrategy selected, MarketContext context) {
            StringBuilder reasoning = new StringBuilder();

            reasoning.append(String.format("Regime: %s. ", regime));
            reasoning.append(String.format("Leader %s momentum: %.2f vs QQQ: %.2f. ",
                    context.strongestLeader, features.spyMomentum, features.qqqMomentum));
            reasoning.append(String.format("Performance: Std=%.2f, Rev=%.2f. ", standardScore, reverseScore));
            reasoning.append(String.format("Q-Values: Std=%.2f, Rev=%.2f. ", qStandard, qReverse));

            if ("MOMENTUM_DIVERGENCE".equals(regime)) {
                reasoning.append("High leader-QQQ divergence favors strategy selection. ");
            } else if ("TRENDING_UP".equals(regime) || "TRENDING_DOWN".equals(regime)) {
                reasoning.append("Strong trend detected in analysis. ");
            }

            reasoning.append(String.format("Selected: %s", selected));

            return reasoning.toString();
        }

        private MarketFeatures reconstructFeatures(Signal signal) {
            // Simplified reconstruction from signal metadata
            String leaderStock = signal.getMetadata().getOrDefault("leaderStock", "AAPL");
            return new MarketFeatures(0, 0, 0, 18, LocalTime.now().getHour() * 60, 1, 1.0, 0);
        }

        private double calculatePnLPercent(Trade trade) {
            if (trade.getExitPrice() != null && trade.getEntryPrice() != null &&
                    trade.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
                return trade.getExitPrice().subtract(trade.getEntryPrice())
                        .divide(trade.getEntryPrice(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
            }
            return 0.0;
        }
    }

    // ================================================================================================
    // CORRELATION DETECTOR - LEADER STOCK ANALYSIS
    // ================================================================================================

    private class CorrelationDetector {
        private final Map<String, List<PricePoint>> priceHistory = new ConcurrentHashMap<>();
        private LeaderCorrelationMetrics lastMetrics;

        public LeaderCorrelationMetrics getLeaderMetrics(String analysisId) {
            try {
                // Get QQQ prices
                List<PricePoint> qqqPrices = getRecentPrices("QQQ", 15, analysisId);
                if (qqqPrices.size() < 10) {
                    log.warn("[CORRELATION][{}] Insufficient QQQ data: {} points", analysisId, qqqPrices.size());
                    return lastMetrics;
                }

                String strongestLeader = null;
                double strongestCorrelation = 0.0;
                Map<String, Double> leaderCorrelations = new HashMap<>();

                // Analyze each leader stock
                for (String leaderSymbol : LEADER_STOCKS) {
                    List<PricePoint> leaderPrices = getRecentPrices(leaderSymbol, 15, analysisId);

                    if (leaderPrices.size() >= 10) {
                        double correlation = calculateCorrelation(leaderPrices, qqqPrices);
                        leaderCorrelations.put(leaderSymbol, correlation);

                        if (correlation > strongestCorrelation) {
                            strongestCorrelation = correlation;
                            strongestLeader = leaderSymbol;
                        }
                    } else {
                        log.warn("[CORRELATION][{}] Insufficient {} data: {} points",
                                analysisId, leaderSymbol, leaderPrices.size());
                    }
                }

                if (strongestLeader == null) {
                    log.warn("[CORRELATION][{}] No valid leader correlations found", analysisId);
                    return lastMetrics;
                }

                lastMetrics = new LeaderCorrelationMetrics(strongestLeader, strongestCorrelation, leaderCorrelations);

                log.info("[CORRELATION][{}] Leader analysis - Strongest: {} ({}%), All: {}",
                        analysisId, strongestLeader, (int)(strongestCorrelation * 100),
                        leaderCorrelations.entrySet().stream()
                                .map(e -> e.getKey() + ":" + (int)(e.getValue() * 100) + "%")
                                .collect(Collectors.joining(", ")));

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
                    return calculateCorrelation(leaderPrices, qqqPrices);
                }
                return 0.5;

            } catch (Exception e) {
                log.debug("Error calculating correlation for {}: {}", leaderSymbol, e.getMessage());
                return 0.5;
            }
        }

        private List<PricePoint> getRecentPrices(String symbol, int periods, String analysisId) {
            List<PricePoint> prices = new ArrayList<>();
            try {
                // Get actual market data
                List<MarketData> marketData = marketDataRepository.findRecentData(symbol, periods);

                if (marketData.size() < periods / 2) {
                    log.warn("[PRICE-DATA][{}] Limited {} data: {} of {} requested",
                            analysisId, symbol, marketData.size(), periods);
                }

                for (MarketData data : marketData) {
                    if (data.getPrice() != null && data.getTimestamp() != null) {
                        prices.add(new PricePoint(data.getPrice().doubleValue(), data.getTimestamp()));
                    }
                }

                // Sort by timestamp (most recent first)
                prices.sort((a, b) -> b.timestamp.compareTo(a.timestamp));

            } catch (Exception e) {
                log.debug("[PRICE-DATA][{}] Error getting prices for {}: {}", analysisId, symbol, e.getMessage());
            }
            return prices;
        }

        private double calculateCorrelation(List<PricePoint> prices1, List<PricePoint> prices2) {
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
    // SUPPORTING CLASSES AND ENUMS
    // ================================================================================================

    private enum LeaderLagStrategy { STANDARD, REVERSE, NONE }
    private enum VWAPPattern { NONE, COMPRESSION, FAILED_BREAKDOWN, STAIR_STEP }

//    @Getter @Setter
//    private static class MarketContext {
//        final double spyMomentum, qqqMomentum, correlation, vixLevel;
//        final String marketTrend, volatilityRegime, strongestLeader;
//        final int timeOfDay, dayOfWeek;
//        final double volumeRatio, momentumDivergence, rsi;
//        final BigDecimal currentPrice;
//
//        public MarketContext(double spyMomentum, double qqqMomentum, double correlation, double vixLevel,
//                             String marketTrend, String volatilityRegime, int timeOfDay, int dayOfWeek,
//                             double volumeRatio, double momentumDivergence, double rsi, BigDecimal currentPrice,
//                             String strongestLeader) {
//            this.spyMomentum = spyMomentum; this.qqqMomentum = qqqMomentum; this.correlation = correlation;
//            this.vixLevel = vixLevel; this.marketTrend = marketTrend; this.volatilityRegime = volatilityRegime;
//            this.timeOfDay = timeOfDay; this.dayOfWeek = dayOfWeek; this.volumeRatio = volumeRatio;
//            this.momentumDivergence = momentumDivergence; this.rsi = rsi; this.currentPrice = currentPrice;
//            this.strongestLeader = strongestLeader;
//        }
//    }

    @Getter @Setter
    private static class AIDecision {
        final LeaderLagStrategy strategy;
        final double confidence;
        final String reasoning, marketRegime;
        final MarketFeatures features;

        public AIDecision(LeaderLagStrategy strategy, double confidence, String reasoning,
                          String marketRegime, MarketFeatures features) {
            this.strategy = strategy; this.confidence = confidence; this.reasoning = reasoning;
            this.marketRegime = marketRegime; this.features = features;
        }
    }

    @Getter @Setter
    private static class AILeaderLagSignal {
        final LeaderLagStrategy leaderLagStrategy;
        final String signalType, strategy, reason, marketRegime, leaderStock;
        final double confidence, correlation, signalStrength;

        public AILeaderLagSignal(LeaderLagStrategy leaderLagStrategy, String signalType, String strategy,
                                 double confidence, double correlation, double signalStrength,
                                 String reason, String marketRegime, String leaderStock) {
            this.leaderLagStrategy = leaderLagStrategy; this.signalType = signalType; this.strategy = strategy;
            this.confidence = confidence; this.correlation = correlation; this.signalStrength = signalStrength;
            this.reason = reason; this.marketRegime = marketRegime; this.leaderStock = leaderStock;
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
    private static class MarketFeatures {
        final double spyMomentum, qqqMomentum, correlation, vixLevel, volumeRatio, momentumDivergence;
        final int timeOfDay, dayOfWeek;

        public MarketFeatures(double spyMomentum, double qqqMomentum, double correlation, double vixLevel,
                              int timeOfDay, int dayOfWeek, double volumeRatio, double momentumDivergence) {
            this.spyMomentum = spyMomentum; this.qqqMomentum = qqqMomentum; this.correlation = correlation;
            this.vixLevel = vixLevel; this.timeOfDay = timeOfDay; this.dayOfWeek = dayOfWeek;
            this.volumeRatio = volumeRatio; this.momentumDivergence = momentumDivergence;
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
            this.strategy = strategy; this.features = features; this.pnlPercent = pnlPercent;
            this.isWinner = isWinner; this.timestamp = timestamp;
        }
    }

    @Getter @Setter
    private static class PricePoint {
        final double price;
        final LocalDateTime timestamp;

        public PricePoint(double price, LocalDateTime timestamp) {
            this.price = price; this.timestamp = timestamp;
        }
    }

    @Getter @Setter
    private static class VolumeRequirement {
        private final String tier;
        private final boolean met;
        private final double multiplier;
        private final double maxConfidence;

        public VolumeRequirement(String tier, boolean met, double multiplier, double maxConfidence) {
            this.tier = tier; this.met = met; this.multiplier = multiplier; this.maxConfidence = maxConfidence;
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
            this.high = high; this.low = low;
        }

        public BigDecimal getHigh() { return high; }
        public BigDecimal getLow() { return low; }
    }

    // PUBLIC METHODS FOR AI LEARNING INTEGRATION
    public void updateAIFromTrade(Trade trade, Signal signal) {
        if (signal.getStrategy().contains("AI_LEADER_LAG")) {
            aiLearning.updateFromTrade(trade, signal);
            log.info("[AI-UPDATE] Updated AI learning from trade: {} P&L: {}%",
                    trade.getOptionSymbol(), calculateTradePerformance(trade));
        }
    }

    // ================================================================================================
    // META AI LEARNING - SIGNAL ATTRIBUTE OPTIMIZATION
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

                // Capture ALL technical attributes
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

                // Capture POC data if available
                Optional<POCData> pocData = pocDataRepository.findLatestPOCByTimeframe(ta.getSymbol(), "30m");
                if (!pocData.isEmpty()) {
                    POCData latest = pocData.get();
                    verification.attributes.pocLevel = latest.getPocPrice().doubleValue();
                    verification.attributes.pocDistance = Math.abs(ta.getCurrentPrice().subtract(latest.getPocPrice())
                            .divide(latest.getPocPrice(), 4, RoundingMode.HALF_UP).doubleValue());
                }

                // Store for verification
                pendingVerifications.put(signal.getId().toString(), verification);

                log.debug("[AI-CAPTURE][{}] Captured attributes for signal {}", analysisId, signal.getId());

            } catch (Exception e) {
                log.error("[AI-CAPTURE][{}] Error capturing signal attributes: {}", analysisId, e.getMessage());
            }
        }

        public void captureMissedOpportunity(TechnicalAnalysis ta, String marketTrend, String analysisId) {
            try {
                // Track when we DIDN'T generate a signal but market moved favorably
                String missedKey = "MISSED_" + UUID.randomUUID().toString().substring(0, 8);

                SignalVerification missed = new SignalVerification();
                missed.signalId = null; // No signal was generated
                missed.strategy = "NO_SIGNAL";
                missed.generatedAt = LocalDateTime.now();
                missed.confidence = 0.0;

                // Capture attributes when we didn't signal
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
                Thread.sleep(300000); // Wait 5 minutes for position to develop

                SignalVerification verification = pendingVerifications.get(verificationId);
                if (verification == null) return;

                boolean wasSuccessful = false;
                double actualPnL = 0.0;

                if (verification.signalId != null) {
                    // Check trade outcome
                    Optional<Trade> trade = tradeRepository.findBySignalId(verification.signalId);
                    if (trade.isPresent()) {
                        Trade t = trade.get();
                        wasSuccessful = t.getExitPrice() != null &&
                                t.getExitPrice().compareTo(t.getEntryPrice()) > 0;
                        actualPnL = calculateTradePerformance(t);
                    }
                } else {
                    // Check if market moved favorably for missed opportunity
                    QuoteResponse quote = tradierService.getQuote("QQQ");
                    if (quote != null && quote.getQuote() != null) {
                        double priceChange = quote.getQuote().getLast()
                                .subtract(BigDecimal.valueOf(verification.attributes.priceLevel))
                                .doubleValue();
                        wasSuccessful = Math.abs(priceChange) > 0.50; // Missed $0.50+ move
                    }
                }

                // Update pattern learning
                updatePatternLearning(verification, wasSuccessful, actualPnL);

                // Adjust thresholds if pattern is significant
                adjustDynamicThresholds(verification, wasSuccessful);

                // Persist learning
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

            // Store exact attributes if this is a high-performing pattern
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
                    // Lower threshold if successful with lower volume
                    dynamicThresholds.put(key, current * 0.95);
                } else if (!success && verification.attributes.volumeRatio > current) {
                    // Raise threshold if failed despite high volume
                    dynamicThresholds.put(key, current * 1.05);
                }
            }

            // Adjust RSI thresholds
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

                // Store detailed attributes as JSON in a metadata field
                learning.setMetadata(serializeAttributes(verification.attributes));
                learning.setPerformanceMetric(pnl);

                aiStateRepository.save(learning);

            } catch (Exception e) {
                log.error("[AI-PERSIST] Error persisting learning: {}", e.getMessage());
            }
        }

        private String encodeAttributePattern(TechnicalAttributes attrs) {
            // Create a pattern key based on attribute ranges
            StringBuilder key = new StringBuilder();

            // Encode key attributes into ranges
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
            // Simple JSON-like serialization
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

                // Find top performing patterns
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

                // Threshold adjustments
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
    // SUPPORTING CLASSES FOR AI LEARNING
    // ================================================================================================

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


    // ================================================================================================
// FIXED AI-POWERED LEADER-LAG ANALYSIS - PROPER TREND FOLLOWING & CONFIDENCE
// ================================================================================================

    private List<Signal> analyzeAILeaderLagStrategies(List<Option> options, TechnicalAnalysis ta,
                                                      String marketTrend, String analysisId) {
        List<Signal> leaderLagSignals = new ArrayList<>();
        LocalTime now = LocalTime.now(ET_ZONE);

        // FIXED: Use actual market trend from TA
        String actualMarketTrend = ta.getTrend();

        try {
            // Build comprehensive market context for AI with leader stocks
            MarketContext context = buildEnhancedMarketContext(ta, actualMarketTrend, now, analysisId);

            if (context == null) {
                log.warn("[AI-LEADER-LAG][{}] Could not build market context - insufficient leader data", analysisId);
                return leaderLagSignals;
            }

            // Use AI to determine optimal strategy
            AIDecision aiDecision = aiLearning.decideStrategy(context, analysisId);

            if (aiDecision.getStrategy() == LeaderLagStrategy.NONE) {
                log.info("[AI-LEADER-LAG][{}] AI decided to skip leader-lag: {}", analysisId, aiDecision.getReasoning());
                return leaderLagSignals;
            }

            // Get leader correlation metrics
            LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetrics(analysisId);

            if (metrics == null || metrics.getStrongestCorrelation() < 0.4) {
                log.warn("[AI-LEADER-LAG][{}] Insufficient leader correlation ({}%) - AI overridden",
                        analysisId, metrics != null ? (int)(metrics.getStrongestCorrelation() * 100) : 0);
                return leaderLagSignals;
            }

            log.info("[AI-LEADER-LAG][{}] 🤖 AI SELECTED: {} (Confidence: {}%)",
                    analysisId, aiDecision.getStrategy(), (int)(aiDecision.getConfidence() * 100));
            log.info("[AI-LEADER-LAG][{}] 🧠 AI Reasoning: {}", analysisId, aiDecision.getReasoning());
            log.info("[AI-LEADER-LAG][{}] 📊 Leader Analysis: {} ({}% corr), QQQ momentum: {}",
                    analysisId, metrics.getStrongestLeader(),
                    (int)(metrics.getStrongestCorrelation() * 100),
                    String.format("%.2f", context.qqqMomentum));

            // Generate signals based on AI decision with PROPER TREND FOLLOWING
            for (Option option : options) {
                if (!quickSignalValidation(option, options, ta, actualMarketTrend, analysisId)) {
                    continue;
                }

                AILeaderLagSignal aiSignal = generateEnhancedAILeaderLagSignal(
                        option, aiDecision, ta, actualMarketTrend, metrics, context);

                if (aiSignal != null && aiSignal.getConfidence() >= getAIThreshold(aiDecision.getStrategy(), now)) {
                    Signal signal = createAILeaderLagSignal(option, options, ta, aiSignal, actualMarketTrend, analysisId);
                    if (signal != null) {
                        leaderLagSignals.add(signal);

                        log.info("[AI-LEADER-LAG][{}] ✅ {} Signal Generated - Confidence: {}%",
                                analysisId, aiDecision.getStrategy(), (int)(aiSignal.getConfidence() * 100));
                    }
                }
            }

            return leaderLagSignals;

        } catch (Exception e) {
            log.error("[AI-LEADER-LAG][{}] Error in AI leader-lag analysis: {}", analysisId, e.getMessage());
            return leaderLagSignals;
        }
    }

    private MarketContext buildEnhancedMarketContext(TechnicalAnalysis ta, String marketTrend,
                                                     LocalTime now, String analysisId) {
        try {
            // Get leader stock analysis
            Map<String, TechnicalAnalysis> leaderAnalysis = new HashMap<>();
            Map<String, Double> leaderMomentum = new HashMap<>();

            for (String leaderSymbol : LEADER_STOCKS) {
                TechnicalAnalysis leaderTA = technicalAnalysisService.analyze(leaderSymbol);
                if (leaderTA != null) {
                    leaderAnalysis.put(leaderSymbol, leaderTA);
                    leaderMomentum.put(leaderSymbol, leaderTA.getMomentumStrength());
                } else {
                    log.warn("[AI-CONTEXT][{}] Missing TA for leader stock: {}", analysisId, leaderSymbol);
                }
            }

            // Ensure we have at least 2 leaders with data
            if (leaderMomentum.size() < 2) {
                log.warn("[AI-CONTEXT][{}] Insufficient leader data: {} of {} leaders",
                        analysisId, leaderMomentum.size(), LEADER_STOCKS.size());
                return null;
            }

            // FIXED: Find strongest leader by CORRELATION (same as getLeaderMetrics())
            List<PricePoint> qqqPrices = correlationDetector.getRecentPrices("QQQ", 15, analysisId);
            if (qqqPrices.size() < 10) {
                log.warn("[AI-CONTEXT][{}] Insufficient QQQ data for correlation", analysisId);
                return null;
            }

            String strongestLeader = null;
            double strongestCorrelation = 0.0;

            // Find leader with highest correlation to QQQ
            for (String leaderSymbol : LEADER_STOCKS) {
                if (leaderMomentum.containsKey(leaderSymbol)) {
                    List<PricePoint> leaderPrices = correlationDetector.getRecentPrices(leaderSymbol, 15, analysisId);
                    if (leaderPrices.size() >= 10) {
                        double correlation = correlationDetector.calculateCorrelation(leaderPrices, qqqPrices);
                        if (correlation > strongestCorrelation) {
                            strongestCorrelation = correlation;
                            strongestLeader = leaderSymbol;
                        }
                    }
                }
            }

            if (strongestLeader == null) {
                log.warn("[AI-CONTEXT][{}] No valid leader correlations found", analysisId);
                return null;
            }

            // FIXED: Use the same strongest leader's momentum consistently
            double strongestLeaderMomentum = leaderMomentum.get(strongestLeader);
            double qqqMomentum = ta.getMomentumStrength();

            // Calculate leader-QQQ divergence
            double leaderQQQDivergence = Math.abs(strongestLeaderMomentum - qqqMomentum);

            log.info("[AI-CONTEXT][{}] Using leader: {} (corr: {}%, momentum: {})",
                    analysisId, strongestLeader,
                    String.format("%.1f", strongestCorrelation * 100),
                    String.format("%.3f", strongestLeaderMomentum));

            // FIXED: Create context with properly named parameters and consistent leader
            return new MarketContext(
                    strongestLeaderMomentum,    // Use the correlation-based strongest leader's momentum
                    qqqMomentum,               // QQQ momentum
                    strongestCorrelation,      // Use the actual correlation we calculated
                    getVixLevel(),
                    marketTrend,
                    ta.getMarketRegime().toString(),
                    now.getHour() * 60 + now.getMinute(),
                    LocalDate.now().getDayOfWeek().getValue(),
                    ta.getVolumeRatio(),
                    leaderQQQDivergence,
                    ta.getRsi(),
                    ta.getCurrentPrice(),
                    strongestLeader             // The correlation-based strongest leader
            );

        } catch (Exception e) {
            log.error("[AI-CONTEXT][{}] Error building market context: {}", analysisId, e.getMessage());
            return null;
        }
    }

    // FIXED: Enhanced AI signal generation with PROPER trend following logic
    private AILeaderLagSignal generateEnhancedAILeaderLagSignal(Option option, AIDecision aiDecision,
                                                                TechnicalAnalysis ta, String marketTrend,
                                                                LeaderCorrelationMetrics metrics, MarketContext context) {

        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean isPut = "PUT".equalsIgnoreCase(option.getType());

        // FIXED: Use proper variable names for clarity
        String strongestLeader = context.strongestLeader;
        double leaderMomentum = context.leaderMomentum;  // FIXED: Clear naming
        double qqqMomentum = context.qqqMomentum;

        if (aiDecision.getStrategy() == LeaderLagStrategy.STANDARD) {
            // STANDARD STRATEGY: Leader diverges from QQQ, expect QQQ to follow leader

            // BULLISH SETUP: Leader bullish + QQQ lagging + CALL option + UP/NEUTRAL trend
            if (isCall && leaderMomentum > 0.1 && qqqMomentum < 0.15 &&
                    (leaderMomentum - qqqMomentum) > 0.05 &&
                    ("UP".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createStandardAISignal(option, context, metrics, "CALL", aiDecision, strongestLeader,
                        leaderMomentum, qqqMomentum, marketTrend);
            }

            // BEARISH SETUP: Leader bearish + QQQ lagging + PUT option + DOWN/NEUTRAL trend
            else if (isPut && leaderMomentum < -0.1 && qqqMomentum > -0.15 &&
                    (qqqMomentum - leaderMomentum) > 0.05 &&
                    ("DOWN".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createStandardAISignal(option, context, metrics, "PUT", aiDecision, strongestLeader,
                        leaderMomentum, qqqMomentum, marketTrend);
            }
        }
        else if (aiDecision.getStrategy() == LeaderLagStrategy.REVERSE) {
            // REVERSE STRATEGY: Leader momentum fading, QQQ continuing independently

            // BULLISH SETUP: Leader fading from bullish + QQQ continuing up + CALL + UP/NEUTRAL trend
            if (isCall && leaderMomentum > -0.05 && leaderMomentum < 0.3 &&
                    qqqMomentum > -0.05 && ("UP".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createReverseAISignal(option, context, metrics, "CALL", aiDecision,
                        leaderMomentum, qqqMomentum, marketTrend);
            }

            // BEARISH SETUP: Leader fading from bearish + QQQ continuing down + PUT + DOWN/NEUTRAL trend
            else if (isPut && leaderMomentum < 0.05 && leaderMomentum > -0.3 &&
                    qqqMomentum < 0.05 && ("DOWN".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createReverseAISignal(option, context, metrics, "PUT", aiDecision,
                        leaderMomentum, qqqMomentum, marketTrend);
            }
        }

        return null;
    }
    // FIXED: Standard AI signal with improved confidence calculation
    private AILeaderLagSignal createStandardAISignal(Option option, MarketContext context,
                                                     LeaderCorrelationMetrics metrics, String type,
                                                     AIDecision aiDecision, String leader,
                                                     double leaderMomentum, double qqqMomentum,
                                                     String marketTrend) {

        // Start with AI base confidence
        double confidence = aiDecision.getConfidence();

        // FIXED: Enhanced confidence calculation with proper trend following
        double divergence = Math.abs(leaderMomentum - qqqMomentum);
        double leaderStrength = Math.abs(leaderMomentum);
        double correlationStrength = metrics.getStrongestCorrelation();

        // Divergence boost (more divergence = higher confidence)
        if (divergence > 0.4) {
            confidence = Math.min(0.95, confidence * 1.20); // Strong divergence
        } else if (divergence > 0.25) {
            confidence = Math.min(0.95, confidence * 1.15); // Moderate divergence
        } else if (divergence > 0.15) {
            confidence = Math.min(0.95, confidence * 1.10); // Weak divergence
        }

        // Leader strength boost
        if (leaderStrength > 0.5) {
            confidence = Math.min(0.95, confidence * 1.15); // Very strong leader move
        } else if (leaderStrength > 0.3) {
            confidence = Math.min(0.95, confidence * 1.10); // Strong leader move
        } else if (leaderStrength > 0.2) {
            confidence = Math.min(0.95, confidence * 1.05); // Moderate leader move
        }

        // Correlation boost (higher correlation = more reliable)
        if (correlationStrength > 0.8) {
            confidence = Math.min(0.95, confidence * 1.12); // Very high correlation
        } else if (correlationStrength > 0.7) {
            confidence = Math.min(0.95, confidence * 1.08); // High correlation
        } else if (correlationStrength > 0.6) {
            confidence = Math.min(0.95, confidence * 1.05); // Good correlation
        }

        // Volume confirmation boost
        if (context.volumeRatio > 2.0) {
            confidence = Math.min(0.95, confidence * 1.10); // Exceptional volume
        } else if (context.volumeRatio > 1.5) {
            confidence = Math.min(0.95, confidence * 1.05); // High volume
        }

        // Time-of-day adjustments
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(9, 45)) && now.isBefore(LocalTime.of(10, 30))) {
            confidence = Math.min(0.95, confidence * 1.08); // Morning momentum window
        } else if (now.isAfter(LocalTime.of(15, 0))) {
            confidence = Math.min(0.95, confidence * 1.12); // Power hour boost
        }

        // Trend alignment boost
        boolean isAligned = (type.equals("CALL") && "UP".equals(marketTrend)) ||
                (type.equals("PUT") && "DOWN".equals(marketTrend));
        if (isAligned) {
            confidence = Math.min(0.95, confidence * 1.08); // Trend-following bonus
        }

        // Market regime adjustments
        if ("HIGH_VOLATILITY".equals(context.volatilityRegime)) {
            confidence = Math.min(0.95, confidence * 1.05); // Volatility helps breakouts
        }

        String strategy = type.equals("CALL") ? "0DTE_AI_LEADER_LAG_CALL" : "0DTE_AI_LEADER_LAG_PUT";
        String reason = String.format(
                "🤖 AI Standard: %s momentum %.2f%% vs QQQ %.2f%% (divergence %.2f%%), correlation %.0f%%, trend %s",
                leader, leaderMomentum * 100, qqqMomentum * 100, divergence * 100,
                correlationStrength * 100, marketTrend);

        return new AILeaderLagSignal(LeaderLagStrategy.STANDARD, "BUY", strategy, confidence,
                correlationStrength, leaderStrength, reason, aiDecision.getMarketRegime(), leader);
    }

    // FIXED: Reverse AI signal with improved confidence calculation
    private AILeaderLagSignal createReverseAISignal(Option option, MarketContext context,
                                                    LeaderCorrelationMetrics metrics, String type,
                                                    AIDecision aiDecision, double leaderMomentum,
                                                    double qqqMomentum, String marketTrend) {

        // Start with AI base confidence
        double confidence = aiDecision.getConfidence();

        // FIXED: Enhanced confidence calculation for reverse strategy
        double leaderFadeStrength = 0.5 - Math.abs(leaderMomentum); // How much leader has faded
        double qqqStrength = Math.abs(qqqMomentum);
        double correlationStrength = metrics.getStrongestCorrelation();

        // Leader fading boost (more fading = higher confidence)
        if (leaderFadeStrength > 0.4) {
            confidence = Math.min(0.95, confidence * 1.18); // Strong fading
        } else if (leaderFadeStrength > 0.3) {
            confidence = Math.min(0.95, confidence * 1.12); // Moderate fading
        } else if (leaderFadeStrength > 0.2) {
            confidence = Math.min(0.95, confidence * 1.08); // Weak fading
        }

        // QQQ independence boost (QQQ continuing despite leader fade)
        if (qqqStrength > 0.4) {
            confidence = Math.min(0.95, confidence * 1.15); // Strong QQQ momentum
        } else if (qqqStrength > 0.3) {
            confidence = Math.min(0.95, confidence * 1.10); // Good QQQ momentum
        } else if (qqqStrength > 0.2) {
            confidence = Math.min(0.95, confidence * 1.05); // Moderate QQQ momentum
        }

        // Time-based boost (reverse strategies work better later in day)
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(15, 0))) {
            confidence = Math.min(0.95, confidence * 1.15); // Power hour independence
        } else if (now.isAfter(LocalTime.of(13, 0))) {
            confidence = Math.min(0.95, confidence * 1.10); // Afternoon momentum
        }

        // Correlation boost (even in reverse, correlation helps predict QQQ behavior)
        if (correlationStrength > 0.6) {
            confidence = Math.min(0.95, confidence * 1.08);
        }

        // Volume confirmation
        if (context.volumeRatio > 1.5) {
            confidence = Math.min(0.95, confidence * 1.08);
        }

        // Trend alignment boost (reverse still benefits from trend following)
        boolean isAligned = (type.equals("CALL") && "UP".equals(marketTrend)) ||
                (type.equals("PUT") && "DOWN".equals(marketTrend));
        if (isAligned) {
            confidence = Math.min(0.95, confidence * 1.10); // Higher boost for reverse trend-following
        }

        String strategy = type.equals("CALL") ? "0DTE_AI_REVERSE_LEADER_LAG_CALL" : "0DTE_AI_REVERSE_LEADER_LAG_PUT";
        String reason = String.format(
                "🤖 AI Reverse: %s fading %.2f%% (from strong), QQQ continuing %.2f%%, correlation %.0f%%, trend %s",
                context.strongestLeader, leaderMomentum * 100, qqqMomentum * 100,
                correlationStrength * 100, marketTrend);

        return new AILeaderLagSignal(LeaderLagStrategy.REVERSE, "BUY", strategy, confidence,
                correlationStrength, qqqStrength, reason, aiDecision.getMarketRegime(), context.strongestLeader);
    }

    // FIXED: Updated MarketContext class with clearer field names
    @Getter @Setter
    private static class MarketContext {
        final double leaderMomentum;      // FIXED: Clear naming - strongest leader momentum
        final double qqqMomentum;         // FIXED: Clear naming - QQQ momentum
        final double correlation, vixLevel;
        final String marketTrend, volatilityRegime, strongestLeader;
        final int timeOfDay, dayOfWeek;
        final double volumeRatio, momentumDivergence, rsi;
        final BigDecimal currentPrice;

        public MarketContext(double leaderMomentum, double qqqMomentum, double correlation, double vixLevel,
                             String marketTrend, String volatilityRegime, int timeOfDay, int dayOfWeek,
                             double volumeRatio, double momentumDivergence, double rsi, BigDecimal currentPrice,
                             String strongestLeader) {
            this.leaderMomentum = leaderMomentum;     // FIXED: Use proper field name
            this.qqqMomentum = qqqMomentum;
            this.correlation = correlation;
            this.vixLevel = vixLevel;
            this.marketTrend = marketTrend;
            this.volatilityRegime = volatilityRegime;
            this.timeOfDay = timeOfDay;
            this.dayOfWeek = dayOfWeek;
            this.volumeRatio = volumeRatio;
            this.momentumDivergence = momentumDivergence;
            this.rsi = rsi;
            this.currentPrice = currentPrice;
            this.strongestLeader = strongestLeader;
        }
    }

    // FIXED: Updated AI threshold logic with trend consideration
    private double getAIThreshold(LeaderLagStrategy strategy, LocalTime now) {
        boolean isPowerHour = now.isAfter(POWER_HOUR_START);
        boolean isMorning = now.isBefore(LocalTime.of(11, 0));

        switch (strategy) {
            case STANDARD:
                if (isPowerHour) return 0.75;      // Lower threshold in power hour
                if (isMorning) return 0.70;       // Lower threshold in morning
                return 0.75;                      // Standard midday threshold

            case REVERSE:
                if (isPowerHour) return 0.70;     // Reverse easier in power hour
                if (isMorning) return 0.80;       // Higher threshold in morning (less reliable)
                return 0.75;                      // Standard midday threshold

            default:
                return 0.85;
        }
    }

    // FIXED: Update the feature extraction to use proper field names
    private MarketFeatures extractFeatures(MarketContext context) {
        return new MarketFeatures(
                context.leaderMomentum,    // FIXED: Use proper field name
                context.qqqMomentum,       // FIXED: Use proper field name
                context.correlation,
                context.vixLevel,
                context.timeOfDay,
                context.dayOfWeek,
                context.volumeRatio,
                context.momentumDivergence
        );
    }
}