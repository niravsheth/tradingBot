package com.tradingBot.service;

import com.tradingBot.analytics.AdvancedUnusualFlowDetector;
import com.tradingBot.entity.*;
import com.tradingBot.model.*;
import com.tradingBot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZeroDTEStrategy {

    private final TradierService tradierService;
    private final TelegramService telegramService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final SignalRepository signalRepository;
    private final SignalOrchestrator signalOrchestrator;
    private final AdvancedUnusualFlowDetector flowDetector;
    private final PreTradeRiskEngine preTradeRiskEngine;  // Add this field

    @Value("${trading.min-volume:50}")
    private int minVolume;

    @Value("${trading.min-iv:0.15}")
    private double minIV;

    @Value("${trading.max-spread-percentage:10.0}")
    private double maxSpreadPercentage;


    // Timezone
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    // Updated strategy thresholds - REDUCED FOR 0DTE
    private static final double MIN_VOLUME_RATIO_BREAKOUT = 1.2;
    private static final double MIN_VOLUME_RATIO_STANDARD = 0.8;
    private static final LocalTime PRIME_WINDOW_1_START = LocalTime.of(9, 45);
    private static final LocalTime PRIME_WINDOW_1_END = LocalTime.of(10, 30);
    private static final LocalTime PRIME_WINDOW_2_START = LocalTime.of(10, 30);
    private static final LocalTime PRIME_WINDOW_2_END = LocalTime.of(12, 00);
    private static final LocalTime PRIME_WINDOW_3_START = LocalTime.of(13, 00);
    private static final LocalTime PRIME_WINDOW_3_END = LocalTime.of(15, 00);
    private static final LocalTime FINAL_WINDOW_START = LocalTime.of(15, 00);
    private static final LocalTime FINAL_WINDOW_END = LocalTime.of(15, 55);


    public List<Signal> analyzeOptions(String symbol, String marketTrend) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        log.info("Trading ANALYSIS START ==========", analysisId);
        log.info("Market trend: {}", analysisId, marketTrend);

        List<Signal> rawSignals = new ArrayList<>();

        try {
            // PRE-TRADE MARKET CHECKS
            if (!preTradeRiskEngine.isMarketSuitable()) {
                log.warn("Market unsuitable for trading - aborting analysis", analysisId);
                return Collections.emptyList();
            }

            // Time check
            LocalTime now = LocalTime.now(ET_ZONE);
            boolean isLateSession = now.isAfter(LocalTime.of(15, 30));
            if (isLateSession) {
                log.warn("[INST][{}] LATE SESSION MODE - Only 90%+ confidence signals allowed", analysisId);
            }

            // Block analysis in neutral market
            if ("NEUTRAL".equals(marketTrend)) {
                log.warn("[INST][{}] MARKET IS NEUTRAL - NO SIGNALS WILL BE GENERATED", analysisId);
                return Collections.emptyList();
            }

            if (!isGoodTradingTime(now)) {
                log.warn("[INST][{}] Not a good time for 0DTE trading: {}", analysisId, now);
                return Collections.emptyList();
            }

            // Get technical analysis
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta == null) {
                log.error("[INST][{}] Technical analysis failed", analysisId);
                return Collections.emptyList();
            }

            logTechnicalAnalysis(ta, analysisId);

            // Check market conditions
            MarketConditions marketConditions = getMarketConditions();
            if (!signalOrchestrator.shouldAnalyze(symbol, marketConditions)) {
                log.info("[INST][{}] Orchestrator vetoed analysis - unfavorable conditions", analysisId);
                return Collections.emptyList();
            }

            // Check for today's expiration
            LocalDate today = LocalDate.now(ET_ZONE);
            List<LocalDate> expirations = tradierService.getExpirations(symbol);

            if (!expirations.contains(today)) {
                log.warn("[INST][{}] No 0DTE options available today", analysisId);
                return Collections.emptyList();
            }

            // Get option chain
            OptionChainResponse chainResponse = tradierService.getOptionChain(symbol, today);

            if (chainResponse == null || !chainResponse.hasOptions()) {
                log.error("[INST][{}] No option chain data available", analysisId);
                return Collections.emptyList();
            }

            List<Option> options = chainResponse.getOptionsList();
            log.info("[INST][{}] Retrieved {} options", analysisId, options.size());

            // Apply filtering based on market regime
            List<Option> filteredOptions = filterOptionsByMarketRegime(options, ta, analysisId);

            // PRE-TRADE STRIKE QUALIFICATION
            List<Option> qualifiedOptions = filteredOptions.stream()
                    .filter(option -> preTradeRiskEngine.isStrikeSuitable(option))
                    .collect(Collectors.toList());

            log.info("[INST][{}] {} options passed pre-trade qualification (from {} filtered)",
                    analysisId, qualifiedOptions.size(), filteredOptions.size());

            // Apply dynamic strike selection based on volatility
            List<Option> selectedStrikes = selectStrikesBasedOnVolatility(qualifiedOptions, ta, analysisId);

            log.info("[INST][{}] Analyzing {} selected strikes", analysisId, selectedStrikes.size());

            // Log flow analysis summary first
            logFlowAnalysisSummary(selectedStrikes, analysisId);

            // Analyze each option against strategies
            for (Option option : selectedStrikes) {
                // Quick pre-validation
                if (!quickSignalValidation(option, selectedStrikes, ta, marketTrend, analysisId)) {
                    continue;
                }

                analyzeOptionWithStrategies(option, ta, rawSignals, marketTrend, analysisId, selectedStrikes);
            }

            log.info("[INST][{}] Generated {} raw signals", analysisId, rawSignals.size());

            // Validate and adjust flow signals
            List<Signal> validatedSignals = rawSignals.stream()
                    .filter(signal -> isFlowSignalValid(signal, getOptionFromSignal(signal, selectedStrikes), selectedStrikes, analysisId))
                    .map(signal -> {
                        Option option = getOptionFromSignal(signal, selectedStrikes);
                        if (option != null) {
                            double adjustedConfidence = adjustFlowConfidence(signal, option, selectedStrikes);
                            signal.setConfidence(adjustedConfidence);
                        }
                        return signal;
                    })
                    .collect(Collectors.toList());

            // Use orchestrator to process signals intelligently
            List<Signal> orchestratedSignals = signalOrchestrator.orchestrateSignals(
                    validatedSignals, ta, analysisId);

            // Late session filter
            if (isLateSession && !orchestratedSignals.isEmpty()) {
                List<Signal> highConfidenceOnly = orchestratedSignals.stream()
                        .filter(s -> s.getConfidence() >= 0.90)
                        .collect(Collectors.toList());

                log.info("[INST][{}] Late session filter: {} signals -> {} (90%+ only)",
                        analysisId, orchestratedSignals.size(), highConfidenceOnly.size());

                orchestratedSignals = highConfidenceOnly;
            }

            // Save signals for IMMEDIATE execution
            saveSignalsImmediate(orchestratedSignals, ta, marketTrend, analysisId);

            log.info("[INST][{}] ========== ANALYSIS COMPLETE - {} SIGNALS ==========",
                    analysisId, orchestratedSignals.size());

            return orchestratedSignals;

        } catch (Exception e) {
            log.error("[INST][{}] ERROR in analyzeOptions: {}", analysisId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }


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
        if (priceMove.compareTo(BigDecimal.valueOf(0.005)) > 0 || // >0.5% move
                dayRange.compareTo(currentPrice.multiply(BigDecimal.valueOf(0.01))) > 0) { // >1% range
            strikesFromATM = 4; // High volatility - wider strikes
            log.info("[INST][{}] High volatility detected - selecting up to {} strikes from ATM",
                    analysisId, strikesFromATM);
        } else {
            strikesFromATM = 2; // Normal volatility - tighter strikes
            log.info("[INST][{}] Normal volatility - selecting up to {} strikes from ATM",
                    analysisId, strikesFromATM);
        }

        // Filter options by strike distance
        List<Option> selected = options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    BigDecimal distance = strike.subtract(currentPrice).abs();

                    boolean isCall = "CALL".equalsIgnoreCase(option.getType());
                    boolean isPut = "PUT".equalsIgnoreCase(option.getType());

                    if (isCall) {
                        // For calls: ATM to X strikes above
                        return strike.compareTo(currentPrice) >= 0 &&
                                strike.compareTo(currentPrice.add(BigDecimal.valueOf(strikesFromATM))) <= 0;
                    } else if (isPut) {
                        // For puts: ATM to X strikes below
                        return strike.compareTo(currentPrice.subtract(BigDecimal.valueOf(strikesFromATM))) >= 0 &&
                                strike.compareTo(currentPrice) <= 0;
                    }
                    return false;
                })
                .sorted((o1, o2) -> {
                    // Sort by distance from current price (closest first)
                    BigDecimal dist1 = o1.getStrikePrice().subtract(currentPrice).abs();
                    BigDecimal dist2 = o2.getStrikePrice().subtract(currentPrice).abs();
                    return dist1.compareTo(dist2);
                })
                .collect(Collectors.toList());

        // Log selected strikes
        selected.forEach(opt -> {
            BigDecimal distance = opt.getStrikePrice().subtract(currentPrice);
            log.info("[INST][{}] Selected: {} ${} (${} from current, Vol: {}, OI: {})",
                    analysisId, opt.getType(), opt.getStrikePrice(),
                    distance.compareTo(BigDecimal.ZERO) > 0 ? "+" + distance : distance,
                    opt.getVolume(), opt.getOpenInterest());
        });

        return selected;
    }


    private void saveSignalsImmediate(List<Signal> signals, TechnicalAnalysis ta,
                                      String marketTrend, String analysisId) {
        for (Signal signal : signals) {
            // ALL signals go directly to PENDING for immediate execution
            signal.setStatus("PENDING");
            signal.setCreatedAt(LocalDateTime.now());
            signal.setEntryAssumptionPrice(ta.getCurrentPrice());
            signal.setMarketTrend(marketTrend);
            signal.setOriginalOptionPrice(signal.getEntryPrice());

            // Very short expiration - 30 seconds
            LocalDateTime expirationTime = LocalDateTime.now().plusSeconds(30);
            signal.setExpirationTime(expirationTime);

            signalRepository.save(signal);

            log.info("[INST][{}] IMMEDIATE {} - {} {}, Confidence: {}%, Executing in <500ms",
                    analysisId, signal.getStrategy(), signal.getSignalType(),
                    signal.getOptionSymbol(), (int)(signal.getConfidence() * 100));

            // Alert for all signals
            if (signal.getConfidence() >= 0.70) {
                String urgency = signal.getStrategy().contains("UNUSUAL_FLOW") ? "URGENT" : "IMMEDIATE";
                telegramService.sendMessage(String.format(
                        "⚡ %s EXECUTION SIGNAL\n" +
                                "Strategy: %s\n" +
                                "Option: %s\n" +
                                "Confidence: %d%%\n" +
                                "Entry: $%.2f\n" +
                                "Target: $%.2f (+%.0f%%)\n" +
                                "Stop: $%.2f (-%.0f%%)\n" +
                                "Executing NOW...",
                        urgency,
                        signal.getStrategy(),
                        signal.getOptionSymbol(),
                        (int)(signal.getConfidence() * 100),
                        signal.getEntryPrice(),
                        signal.getTargetPrice(),
                        ((signal.getTargetPrice().doubleValue() / signal.getEntryPrice().doubleValue() - 1) * 100),
                        signal.getStopLoss(),
                        ((1 - signal.getStopLoss().doubleValue() / signal.getEntryPrice().doubleValue()) * 100)
                ));
            }
        }
    }

    private boolean isFlowSignalValid(Signal signal, Option option, List<Option> allOptions, String analysisId) {
        if (!signal.getStrategy().contains("UNUSUAL_FLOW")) {
            return true; // Not a flow signal, use regular validation
        }

        // Re-validate the flow in case market conditions changed
        AdvancedUnusualFlowDetector.FlowAnalysis analysis =
                flowDetector.analyzeFlow(option, signal.getSymbol(), allOptions, analysisId);

        if (!analysis.isUnusualFlow()) {
            log.warn("[v63][{}] Flow signal invalidated - {} Reason: {}",
                    analysisId, signal.getOptionSymbol(), analysis.getReasoning());
            return false;
        }

        // Additional validation for flow signals
        if (analysis.getHedgingProbability() > 0.4) {
            log.warn("[v63][{}] Flow signal rejected - High hedging probability: {}%",
                    analysisId, (int)(analysis.getHedgingProbability() * 100));
            return false;
        }

        return true;
    }


    private double adjustFlowConfidence(Signal signal, Option option, List<Option> allOptions) {
        if (!signal.getStrategy().contains("UNUSUAL_FLOW")) {
            return signal.getConfidence(); // Not a flow signal
        }

        AdvancedUnusualFlowDetector.FlowAnalysis analysis =
                flowDetector.analyzeFlow(option, signal.getSymbol(), allOptions, "confidence-adj");

        double baseConfidence = signal.getConfidence();

        // Boost for smart money
        if (analysis.isSmartMoney()) {
            baseConfidence = Math.min(baseConfidence * 1.2, 0.95);
        }

        // Boost for high aggressiveness
        if (analysis.getAggressiveness() > 0.8) {
            baseConfidence = Math.min(baseConfidence * 1.1, 0.93);
        }

        // Reduce for any hedging probability
        if (analysis.getHedgingProbability() > 0.1) {
            baseConfidence *= (1 - analysis.getHedgingProbability() * 0.5);
        }

        return baseConfidence;
    }

    private void logFlowAnalysisSummary(List<Option> options, String analysisId) {
        log.info("[v63][{}] === FLOW ANALYSIS SUMMARY ===", analysisId);

        int totalFlows = 0;
        int filteredAsHedging = 0;
        int confirmedDirectional = 0;
        int smartMoneyFlows = 0;

        for (Option option : options) {
            if (option.getVolume() > 1000) { // Only analyze significant volume
                AdvancedUnusualFlowDetector.FlowAnalysis analysis =
                        flowDetector.analyzeFlow(option, "QQQ", options, analysisId);

                totalFlows++;

                if (analysis.getHedgingProbability() > 0.3) {
                    filteredAsHedging++;
                }

                if (analysis.isUnusualFlow()) {
                    confirmedDirectional++;
                    if (analysis.isSmartMoney()) {
                        smartMoneyFlows++;
                    }
                }
            }
        }

        log.info("[v63][{}] Flow Summary - Total: {}, Hedging: {}, Directional: {}, Smart Money: {}",
                analysisId, totalFlows, filteredAsHedging, confirmedDirectional, smartMoneyFlows);
    }

    public List<Signal> analyzeOptions(String symbol) {
        return analyzeOptions(symbol, "NEUTRAL");
    }

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
        MarketInternals internals = new MarketInternals();
        return internals;
    }

    private boolean isGoodTradingTime(LocalTime now) {
        if ((now.isAfter(PRIME_WINDOW_1_START) && now.isBefore(PRIME_WINDOW_1_END)) ||
                (now.isAfter(PRIME_WINDOW_2_START) && now.isBefore(PRIME_WINDOW_2_END)) ||
                (now.isAfter(PRIME_WINDOW_3_START) && now.isBefore(PRIME_WINDOW_3_END)) ||
                (now.isAfter(FINAL_WINDOW_START) && now.isBefore(FINAL_WINDOW_END))) {
            return true;
        }

        log.info("[v63] Outside prime trading windows - skipping analysis");
        return false;
    }

    private boolean detectOpeningDrive(TechnicalAnalysis ta, String analysisId) {
        LocalTime now = LocalTime.now(ET_ZONE);

//        if (!now.isAfter(LocalTime.of(9, 30)) || !now.isBefore(LocalTime.of(10, 00))) {
//            return false;
//        }

        BigDecimal gap = calculatePremarketGap(ta);
        boolean hasGap = gap.abs().compareTo(BigDecimal.valueOf(0.005)) > 0;
        boolean hasVolume = ta.getVolumeRatio() > 1.5;

        if (hasGap && hasVolume) {
            log.info("[v63][{}] Opening Drive detected - Gap: {}%, Volume: {}x",
                    analysisId,
                    String.format("%.2f", gap.multiply(BigDecimal.valueOf(100)).doubleValue()),
                    String.format("%.1f", ta.getVolumeRatio()));
            return true;
        }

        return false;
    }

    private BigDecimal calculatePremarketGap(TechnicalAnalysis ta) {
        if (ta.getPreviousClose() != null && ta.getPreviousClose().compareTo(BigDecimal.ZERO) > 0) {
            return ta.getCurrentPrice().subtract(ta.getPreviousClose())
                    .divide(ta.getPreviousClose(), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private boolean detectUnusualOptionsFlow(Option option, List<Option> allOptions, String analysisId) {
        try {
            AdvancedUnusualFlowDetector.FlowAnalysis analysis =
                    flowDetector.analyzeFlow(option, "QQQ", allOptions, analysisId);

            if (analysis.isUnusualFlow()) {
                log.info("[v63][{}] ✅ CONFIRMED UNUSUAL FLOW - {} {} Premium: ${} Type: {} Confidence: {}%",
                        analysisId, option.getType(), option.getStrikePrice(),
                        option.getMidPrice().multiply(BigDecimal.valueOf(option.getVolume() * 100)),
                        analysis.getFlowType(), (int)(analysis.getConfidence() * 100));

                // Alert for smart money flows
                if (analysis.isSmartMoney()) {
                    telegramService.sendMessage(String.format(
                            "🎯 <b>SMART MONEY FLOW DETECTED</b>\n" +
                                    "Option: %s %s\n" +
                                    "Volume: %,d contracts\n" +
                                    "Premium: $%,.0f\n" +
                                    "Type: %s\n" +
                                    "Confidence: %.0f%%\n" +
                                    "Aggressiveness: %.0f%%",
                            option.getType(), option.getStrikePrice(),
                            option.getVolume(),
                            option.getMidPrice().multiply(BigDecimal.valueOf(option.getVolume() * 100)).doubleValue(),
                            analysis.getFlowType(),
                            analysis.getConfidence() * 100,
                            analysis.getAggressiveness() * 100
                    ));
                }

                return true;
            } else {
                // Log why it was filtered out
                log.debug("[v63][{}] ❌ Flow filtered out - {} {} Reason: {}",
                        analysisId, option.getType(), option.getStrikePrice(), analysis.getReasoning());

                // Alert for large flows that were filtered (might be interesting)
                BigDecimal premium = option.getMidPrice()
                        .multiply(BigDecimal.valueOf(option.getVolume()))
                        .multiply(BigDecimal.valueOf(100));

                if (premium.compareTo(BigDecimal.valueOf(2000000)) > 0) { // >$2M flows that were filtered
                    log.warn("[v63][{}] 🚫 LARGE FLOW FILTERED - {} {} Premium: ${} Reason: {}",
                            analysisId, option.getType(), option.getStrikePrice(),
                            premium, analysis.getReasoning());
                }

                return false;
            }
        } catch (Exception e) {
            log.error("[v63][{}] Error in enhanced flow detection: {}", analysisId, e.getMessage());
            return false; // Fail safe - don't trade on errors
        }
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
        log.info("[v63][{}] TA Results - Price: ${}, VWAP: ${}, Regime: {}",
                analysisId, ta.getCurrentPrice(), ta.getVwap(), ta.getMarketRegime());
        log.info("[v63][{}] Volume - Current: {}, Ratio: {}, High: {}",
                analysisId, ta.getCurrentVolume(),
                String.format("%.2f", ta.getVolumeRatio()),
                ta.isHighVolume());
        log.info("[v63][{}] Momentum - RSI: {}, MACD: {}, Divergence: {}",
                analysisId,
                String.format("%.1f", ta.getRsi()),
                ta.getMacdSignal(),
                ta.isHasRsiDivergence() || ta.isHasMacdDivergence());
        log.info("[v63][{}] VWAP - Upper: ${}, Lower: ${}, Extended: {}",
                analysisId, ta.getVwapUpperBand(), ta.getVwapLowerBand(), ta.isExtendedFromVwap());

        if (ta.getOpeningRangeHigh() != null) {
            log.info("[v63][{}] Opening Range - High: ${}, Low: ${}",
                    analysisId, ta.getOpeningRangeHigh(), ta.getOpeningRangeLow());
        }
    }

    private List<Option> filterOptionsByMarketRegime(List<Option> options, TechnicalAnalysis ta,
                                                     String analysisId) {
        MarketRegime regime = ta.getMarketRegime();
        DynamicParameters params = ta.getDynamicParameters();

        LocalTime now = LocalTime.now(ET_ZONE);

        int adjustedMinVolume = getAdjustedMinVolume(now);
        double adjustedMinIV = minIV;

        // Remove the percentage-based filtering - we'll use strike-based instead

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

        final int finalMinVolume = adjustedMinVolume;
        final double finalMinIV = adjustedMinIV;

        log.info("[v63][{}] Base filtering - Volume: {}, IV: {:.3f}",
                analysisId, finalMinVolume, finalMinIV);

        List<Option> basicFiltered = options.stream()
                .filter(option -> option.isValid())
                .filter(option -> option.getVolume() >= finalMinVolume)
                .filter(option -> {
                    Double iv = option.getImpliedVolatility();
                    return iv != null && iv >= finalMinIV;
                })
                .filter(option -> option.getSpreadPercentage() <= maxSpreadPercentage)
                .collect(Collectors.toList());

        log.info("[v63][{}] After basic filtering: {} options", analysisId, basicFiltered.size());

        // Now apply strike filtering - this is the key change
        return filterByConfidenceAdjustedStrikes(basicFiltered, ta, 0.0, analysisId);
    }

    private List<Option> filterByConfidenceAdjustedStrikes(List<Option> options,
                                                           TechnicalAnalysis ta,
                                                           double expectedConfidence,
                                                           String analysisId) {
        BigDecimal currentPrice = ta.getCurrentPrice();

        log.info("[v63][{}] Current QQQ price: ${}", analysisId, currentPrice);

        // For 0DTE, we want ATM and 1 strike OTM only
        List<Option> filtered = options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    BigDecimal distance = currentPrice.subtract(strike).abs();

                    boolean isCall = "CALL".equalsIgnoreCase(option.getType());
                    boolean isPut = "PUT".equalsIgnoreCase(option.getType());

                    if (isCall) {
                        // For calls: ATM or 1-2 strikes above current price
                        // If QQQ = 553.30, accept 554, 555
                        return strike.compareTo(currentPrice) >= 0 &&
                                strike.compareTo(currentPrice.add(BigDecimal.valueOf(2))) <= 0;
                    } else if (isPut) {
                        // For puts: ATM or 1-2 strikes below current price
                        // If QQQ = 553.30, accept 553, 552
                        return strike.compareTo(currentPrice.subtract(BigDecimal.valueOf(2))) >= 0 &&
                                strike.compareTo(currentPrice) <= 0;
                    }

                    return false;
                })
                .sorted((o1, o2) -> {
                    // Sort by distance from current price (closest first)
                    BigDecimal dist1 = o1.getStrikePrice().subtract(currentPrice).abs();
                    BigDecimal dist2 = o2.getStrikePrice().subtract(currentPrice).abs();
                    return dist1.compareTo(dist2);
                })
                .collect(Collectors.toList());

        // Log selected strikes
        log.info("[v63][{}] Strike filtering for price ${}: Found {} options",
                analysisId, currentPrice, filtered.size());

        filtered.forEach(opt -> {
            BigDecimal distance = opt.getStrikePrice().subtract(currentPrice);
            log.info("[v63][{}] Selected: {} ${} (${} from current)",
                    analysisId, opt.getType(), opt.getStrikePrice(),
                    distance.compareTo(BigDecimal.ZERO) > 0 ? "+" + distance : distance);
        });

        return filtered;
    }


    private void analyzeOptionWithStrategies(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend,
                                             String analysisId, List<Option> allOptions){
        // When blocking due to neutral market
//        if ("NEUTRAL".equals(marketTrend)) {
//            // Check if this would have been a signal
//            if (wouldGenerateSignal(option, ta)) {
//                analysisService.recordBlockedSignal(
//                        option.getSymbol(),
//                        "POTENTIAL_SIGNAL",
//                        0.75, // estimated confidence
//                        "MARKET_NEUTRAL",
//                        option.getMidPrice(),
//                        getCurrentMarketBreadth(),
//                        marketTrend
//                );
//            }
//            return;
//        }

        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, allOptions, analysisId);
        if (hasUnusualFlow) {
            analyzeUnusualFlow(option, allOptions,ta, signals, marketTrend, analysisId);
        }

        // Continue checking other strategies regardless of unusual flow
        LocalTime now = LocalTime.now(ET_ZONE);

        if (now.isBefore(LocalTime.of(10, 0))) {
            analyzeOpeningDriveStrategy(option,allOptions, ta, signals, marketTrend, analysisId);
        }

        if (now.isAfter(LocalTime.of(9, 45))) {
            analyzeOpeningRangeBreakout(option,allOptions, ta, signals, marketTrend, analysisId);
        }

        if (ta.getVolumeRatio() > 1.5) {
            analyzeVolumeSpikeStrategy(option,allOptions, ta, signals, marketTrend, analysisId);
        }

        if (ta.isVwapBreakout() && ta.getVolumeRatio() > 1.5) {
            analyzeVWAPBreakout(option,allOptions, ta, signals, marketTrend, analysisId);
        } else if (ta.isExtendedFromVwap() && (ta.getRsi() > 70 || ta.getRsi() < 30)) {
            analyzeVWAPDeviationReversion(option,allOptions, ta, signals, marketTrend, analysisId);
        } else if (ta.isVwapAsSupport() || ta.isVwapAsResistance()) {
            analyzeVWAPSupportResistance(option,allOptions, ta, signals, marketTrend, analysisId);
        } else if (Math.abs(ta.getPriceToVwapRatio() - 1.0) < 0.005) {
            analyzeVWAPBounce(option,allOptions, ta, signals, marketTrend, analysisId);
        }
    }

    private void analyzeOpeningDriveStrategy(Option option,List<Option> allOptions, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend, String analysisId) {
        if (!detectOpeningDrive(ta, analysisId)) {
            return;
        }
        if (!isSignalAlignedWithTrend(option,allOptions, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        boolean hasUnusualFlow = detectUnusualOptionsFlow(option,allOptions, analysisId);
        BigDecimal gap = calculatePremarketGap(ta);
        boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
        boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

        if (gap.compareTo(BigDecimal.ZERO) > 0 && isCallOption && "UP".equals(marketTrend)) {
            double confidence = 0.70;

            if (ta.getVolumeRatio() > 2.0 || hasUnusualFlow) {
                confidence += 0.15;
            }

            if (ta.getMomentumStrength() > 0.5) {
                confidence += 0.10;
            }

            if (confidence >= 0.70) {
                Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_OPENING_DRIVE_CALL", confidence, marketTrend);
                signal.setReason(String.format(
                        "Opening drive +%.2f%% gap with %.1fx volume - momentum continuation%s",
                        gap.multiply(BigDecimal.valueOf(100)).doubleValue(),
                        ta.getVolumeRatio(),
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ OPENING DRIVE CALL - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }

        if (gap.compareTo(BigDecimal.ZERO) < 0 && isPutOption && "DOWN".equals(marketTrend)) {
            double confidence = 0.70;

            if (ta.getVolumeRatio() > 2.0 || hasUnusualFlow) {
                confidence += 0.15;
            }

            if (ta.getMomentumStrength() < -0.5) {
                confidence += 0.10;
            }

            if (confidence >= 0.70) {
                Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_OPENING_DRIVE_PUT", confidence, marketTrend);
                signal.setReason(String.format(
                        "Opening drive %.2f%% gap down with %.1fx volume - momentum continuation%s",
                        gap.multiply(BigDecimal.valueOf(100)).doubleValue(),
                        ta.getVolumeRatio(),
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ OPENING DRIVE PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }
    }

    private void analyzeOpeningRangeBreakout(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend, String analysisId) {
        if (ta.getOpeningRangeHigh() == null || ta.getOpeningRangeLow() == null) {
            return;
        }
        if (!isSignalAlignedWithTrend(option,allOptions, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        boolean hasUnusualFlow = detectUnusualOptionsFlow(option,allOptions, analysisId);
        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal orHigh = ta.getOpeningRangeHigh();
        BigDecimal orLow = ta.getOpeningRangeLow();

        log.info("[v63][{}] ORB Analysis - Current: ${}, OR High: ${}, OR Low: ${}",
                analysisId, currentPrice, orHigh, orLow);

        if (currentPrice.compareTo(orHigh) > 0 && "CALL".equalsIgnoreCase(option.getType())) {
            boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT;

            if (volumeRequirementMet && !ta.isHasRsiDivergence()) {
                double confidence = calculateORBConfidence(ta, true);

                if (hasUnusualFlow) {
                    confidence = Math.min(confidence * 1.15, 0.95);
                }

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_ORB_CALL", confidence, marketTrend);
                    signal.setReason(String.format(
                            "Opening range breakout above $%.2f with %.1fx volume%s",
                            orHigh, ta.getVolumeRatio(),
                            hasUnusualFlow ? " (Unusual Flow)" : ""));
                    signals.add(signal);

                    log.info("[v63][{}] ✅ ORB CALL signal - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            }
        }

        if (currentPrice.compareTo(orLow) < 0 && "PUT".equalsIgnoreCase(option.getType())) {
            boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT;

            if (volumeRequirementMet && !ta.isHasRsiDivergence()) {
                double confidence = calculateORBConfidence(ta, false);

                if (hasUnusualFlow) {
                    confidence = Math.min(confidence * 1.15, 0.95);
                }

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_ORB_PUT", confidence, marketTrend);
                    signal.setReason(String.format(
                            "Opening range breakdown below $%.2f with %.1fx volume%s",
                            orLow, ta.getVolumeRatio(),
                            hasUnusualFlow ? " (Unusual Flow)" : ""));
                    signals.add(signal);

                    log.info("[v63][{}] ✅ ORB PUT signal - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            }
        }
    }

    private void analyzeVWAPBreakout(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                     List<Signal> signals, String marketTrend, String analysisId) {
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option,allOptions, analysisId);
        if (!isSignalAlignedWithTrend(option,allOptions, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwapUpper = ta.getVwapUpperBand();
        BigDecimal vwapLower = ta.getVwapLowerBand();

        if (ta.isHasRsiDivergence()) {
            log.info("[v63][{}] ⚠️ RSI divergence detected - reducing breakout confidence", analysisId);
        }

        if (currentPrice.compareTo(vwapUpper) > 0 && "CALL".equalsIgnoreCase(option.getType())) {
            BigDecimal breakoutDistance = currentPrice.subtract(vwapUpper)
                    .divide(vwapUpper, 4, RoundingMode.HALF_UP);

            if (breakoutDistance.compareTo(BigDecimal.valueOf(0.002)) >= 0) {
                boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT;

                if (volumeRequirementMet) {
                    double confidence = calculateBreakoutConfidence(ta, true);

                    if (ta.isHasRsiDivergence()) {
                        confidence *= 0.7;
                    }

                    if (hasUnusualFlow) {
                        confidence = Math.min(confidence * 1.15, 0.95);
                    }

                    if (confidence >= 0.60) {
                        Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VWAP_BREAKOUT_CALL", confidence, marketTrend);
                        signal.setReason(String.format(
                                "VWAP breakout %.2f%% above upper band with %.1fx volume%s",
                                breakoutDistance.multiply(BigDecimal.valueOf(100)).doubleValue(),
                                ta.getVolumeRatio(),
                                hasUnusualFlow ? " (Unusual Flow)" : ""));
                        signals.add(signal);

                        log.info("[v63][{}] ✅ VWAP Breakout CALL - Confidence: {}%",
                                analysisId, (int)(confidence * 100));
                    }
                }
            }
        }

        if (currentPrice.compareTo(vwapLower) < 0 && "PUT".equalsIgnoreCase(option.getType())) {
            BigDecimal breakoutDistance = vwapLower.subtract(currentPrice)
                    .divide(vwapLower, 4, RoundingMode.HALF_UP);

            if (breakoutDistance.compareTo(BigDecimal.valueOf(0.002)) >= 0) {
                boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT;

                if (volumeRequirementMet) {
                    double confidence = calculateBreakoutConfidence(ta, false);

                    if (ta.isHasRsiDivergence()) {
                        confidence *= 0.7;
                    }

                    if (hasUnusualFlow) {
                        confidence = Math.min(confidence * 1.15, 0.95);
                    }

                    if (confidence >= 0.60) {
                        Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VWAP_BREAKOUT_PUT", confidence, marketTrend);
                        signal.setReason(String.format(
                                "VWAP breakout %.2f%% below lower band with %.1fx volume%s",
                                breakoutDistance.multiply(BigDecimal.valueOf(100)).doubleValue(),
                                ta.getVolumeRatio(),
                                hasUnusualFlow ? " (Unusual Flow)" : ""));
                        signals.add(signal);

                        log.info("[v63][{}] ✅ VWAP Breakout PUT - Confidence: {}%",
                                analysisId, (int)(confidence * 100));
                    }
                }
            }
        }
    }

    private void analyzeVWAPDeviationReversion(Option option,List<Option> allOptions, TechnicalAnalysis ta,
                                               List<Signal> signals, String marketTrend, String analysisId) {
        if (!ta.isExtendedFromVwap() || ta.getVwapStandardDeviation() == null ||
                ta.getVwapStandardDeviation().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        if (!isSignalAlignedWithTrend(option,allOptions, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        boolean hasUnusualFlow = detectUnusualOptionsFlow(option,allOptions,analysisId);

        double priceRatio = ta.getPriceToVwapRatio();

        log.info("[v63][{}] Deviation Reversion - Price/VWAP: {}, Extended: {}",
                analysisId,
                String.format("%.3f", priceRatio),
                ta.isExtendedFromVwap());

        if (priceRatio > 1.0 && "PUT".equalsIgnoreCase(option.getType()) && ta.getRsi() > 70) {
            double confidence = calculateReversionConfidence(ta, false);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VWAP_REVERSION_PUT", confidence, marketTrend);
                signal.setReason(String.format(
                        "Extended %.2f%% above VWAP with RSI %.1f - mean reversion setup%s",
                        (priceRatio - 1) * 100, ta.getRsi(),
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ VWAP Reversion PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }

        if (priceRatio < 1.0 && "CALL".equalsIgnoreCase(option.getType()) && ta.getRsi() < 30) {
            double confidence = calculateReversionConfidence(ta, true);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VWAP_REVERSION_CALL", confidence, marketTrend);
                signal.setReason(String.format(
                        "Extended %.2f%% below VWAP with RSI %.1f - mean reversion setup%s",
                        (1 - priceRatio) * 100, ta.getRsi(),
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ VWAP Reversion CALL - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }
    }

    private void analyzeVolumeSpikeStrategy(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                            List<Signal> signals, String marketTrend, String analysisId) {
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, allOptions,analysisId);
        if (!isSignalAlignedWithTrend(option,allOptions, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        double requiredVolumeRatio = hasUnusualFlow ? 1.5 : 2.0;

        if (ta.getVolumeRatio() < requiredVolumeRatio && !hasUnusualFlow) {
            log.debug("[{}] Skipping Volume Spike - Volume ratio {:.2f} < required {:.1f}",
                    analysisId, ta.getVolumeRatio(), requiredVolumeRatio);
            return;
        }

        log.info("[v63][{}] Volume Spike Analysis - Ratio: {}x, Trend: {}",
                analysisId,
                String.format("%.1f", ta.getVolumeRatio()),
                marketTrend);

        if ("CALL".equalsIgnoreCase(option.getType()) &&
                "UP".equals(marketTrend) &&
                ta.getMomentumStrength() > 0.3) {

            double confidence = calculateVolumeSpikeConfidence(ta, true);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VOLUME_SPIKE_CALL", confidence, marketTrend);
                signal.setReason(String.format(
                        "Volume spike %.1fx with bullish momentum - continuation play%s",
                        ta.getVolumeRatio(),
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ Volume Spike CALL - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }

        if ("PUT".equalsIgnoreCase(option.getType()) &&
                "DOWN".equals(marketTrend) &&
                ta.getMomentumStrength() < -0.3) {

            double confidence = calculateVolumeSpikeConfidence(ta, false);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VOLUME_SPIKE_PUT", confidence, marketTrend);
                signal.setReason(String.format(
                        "Volume spike %.1fx with bearish momentum - continuation play%s",
                        ta.getVolumeRatio(),
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ Volume Spike PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }
    }

    private void analyzeVWAPSupportResistance(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                              List<Signal> signals, String marketTrend, String analysisId) {
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option,allOptions, analysisId);
        if (!isSignalAlignedWithTrend(option,allOptions, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        if (ta.isVwapAsSupport() && "CALL".equalsIgnoreCase(option.getType()) &&
                currentPrice.compareTo(vwap) > 0) {

            boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_STANDARD;

            if (volumeRequirementMet) {
                double confidence = calculateSupportResistanceConfidence(ta, true);

                if (hasUnusualFlow) {
                    confidence = Math.min(confidence * 1.15, 0.95);
                }

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VWAP_SUPPORT_CALL", confidence, marketTrend);
                    signal.setReason(String.format(
                            "VWAP support at $%.2f held with %d touches%s",
                            vwap, 3,
                            hasUnusualFlow ? " (Unusual Flow)" : ""));
                    signals.add(signal);

                    log.info("[v63][{}] ✅ VWAP Support CALL - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            }
        }

        if (ta.isVwapAsResistance() && "PUT".equalsIgnoreCase(option.getType()) &&
                currentPrice.compareTo(vwap) < 0) {

            boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_STANDARD;

            if (volumeRequirementMet) {
                double confidence = calculateSupportResistanceConfidence(ta, false);

                if (hasUnusualFlow) {
                    confidence = Math.min(confidence * 1.15, 0.95);
                }

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VWAP_RESISTANCE_PUT", confidence, marketTrend);
                    signal.setReason(String.format(
                            "VWAP resistance at $%.2f rejected with %d touches%s",
                            vwap, 3,
                            hasUnusualFlow ? " (Unusual Flow)" : ""));
                    signals.add(signal);

                    log.info("[v63][{}] ✅ VWAP Resistance PUT - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            }
        }
    }

    private void analyzeVWAPBounce(Option option,List<Option> allOptions, TechnicalAnalysis ta,
                                   List<Signal> signals, String marketTrend, String analysisId) {
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option,allOptions,analysisId);
        if (!isSignalAlignedWithTrend(option,allOptions,ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }
        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        if (Math.abs(ta.getPriceToVwapRatio() - 1.0) > 0.005) {
            return;
        }

        boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_STANDARD;

        if (!volumeRequirementMet) {
            log.debug("[{}] Skipping VWAP Bounce - Volume ratio {:.2f} < required {:.1f}",
                    analysisId, ta.getVolumeRatio(), MIN_VOLUME_RATIO_STANDARD);
            return;
        }

        if ("CALL".equalsIgnoreCase(option.getType()) && ta.getRsi() > 45 && ta.getRsi() < 55) {
            double confidence = calculateBounceConfidence(ta, true);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.60) {
                Signal signal = createSignal(option, allOptions,ta, "BUY", "0DTE_VWAP_BOUNCE_CALL", confidence, marketTrend);
                signal.setReason(String.format("VWAP bounce with neutral RSI and volume confirmation%s",
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ VWAP Bounce CALL - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }

        if ("PUT".equalsIgnoreCase(option.getType()) && ta.getRsi() > 45 && ta.getRsi() < 55) {
            double confidence = calculateBounceConfidence(ta, false);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.60) {
                Signal signal = createSignal(option,allOptions, ta, "BUY", "0DTE_VWAP_BOUNCE_PUT", confidence, marketTrend);
                signal.setReason(String.format("VWAP rejection with neutral RSI and volume confirmation%s",
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ VWAP Bounce PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }
    }


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

        if (ta.getMarketRegime() == MarketRegime.TRENDING_UP && bullish ||
                ta.getMarketRegime() == MarketRegime.TRENDING_DOWN && !bullish) {
            confidence += 0.10;
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        double timeMultiplier = getTimeWindowMultiplier(now);
        confidence *= timeMultiplier;

        return Math.min(confidence, 0.95);
    }

    private double calculateORBConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.6;

        if (ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT) {
            confidence += 0.20;
        } else if (ta.getVolumeRatio() >= 1.0) {
            confidence += 0.10;
        }

        if (!ta.isHasRsiDivergence()) {
            confidence += 0.10;
        }

        if (ta.getStrength() > 0.5) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.90);
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

    private double calculateVolumeSpikeConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.5;

        if (ta.getVolumeRatio() > 3.0) {
            confidence += 0.30;
        } else if (ta.getVolumeRatio() > 2.0) {
            confidence += 0.20;
        } else if (ta.getVolumeRatio() > 1.5) {
            confidence += 0.10;
        }

        if (Math.abs(ta.getMomentumStrength()) > 0.5) {
            confidence += 0.20;
        }

        if (!ta.isExtendedFromVwap()) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.90);
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


    private Signal createSignal(Option option,List<Option> allOptions, TechnicalAnalysis ta, String signalType,
                                String strategy, double confidence, String marketTrend) {
        if (option == null || ta == null || signalType == null || strategy == null) {
            log.error("Cannot create signal with null parameters");
            return null;
        }
        if (!isSignalAlignedWithTrend(option, allOptions,ta, marketTrend, "signal-creation")) {
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
            signal.setMarketTrend(marketTrend); // Store market trend

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

    private BigDecimal calculateDynamicStopLoss(BigDecimal entryPrice, BigDecimal atr, String strategy,
                                                TechnicalAnalysis ta, String marketTrend, Option option) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isAligned = (isPut && "DOWN".equals(marketTrend)) || (!isPut && "UP".equals(marketTrend));

        // Base stop loss percentages
        BigDecimal baseStopPercentage;

        if (isAligned) {
            // WIDER stops for trend-aligned positions
            switch (strategy) {
                case "0DTE_UNUSUAL_FLOW_CALL":
                case "0DTE_UNUSUAL_FLOW_PUT":
                    baseStopPercentage = BigDecimal.valueOf(0.40); // 40% stop for high conviction
                    break;
                case "0DTE_OPENING_DRIVE_CALL":
                case "0DTE_OPENING_DRIVE_PUT":
                    baseStopPercentage = BigDecimal.valueOf(0.45); // 45% for opening drive
                    break;
                case "0DTE_VWAP_BREAKOUT_CALL":
                case "0DTE_VWAP_BREAKOUT_PUT":
                    baseStopPercentage = BigDecimal.valueOf(0.35); // 35% for breakouts
                    break;
                case "0DTE_VWAP_REVERSION_CALL":
                case "0DTE_VWAP_REVERSION_PUT":
                    baseStopPercentage = BigDecimal.valueOf(0.50); // 50% for reversions (need room)
                    break;
                default:
                    baseStopPercentage = BigDecimal.valueOf(0.40); // 40% default
            }
        } else {
            // TIGHTER stops for counter-trend positions
            baseStopPercentage = BigDecimal.valueOf(0.25); // 25% tight stop
        }

        // Adjust for volatility
        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            baseStopPercentage = baseStopPercentage.multiply(BigDecimal.valueOf(1.2)); // 20% wider in high vol
        }

        // Adjust for time of day
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(14, 30))) {
            baseStopPercentage = baseStopPercentage.multiply(BigDecimal.valueOf(0.8)); // Tighter near close
        }

        BigDecimal stopLoss = entryPrice.multiply(BigDecimal.ONE.subtract(baseStopPercentage));

        // Minimum stop of $0.05
        if (stopLoss.compareTo(BigDecimal.valueOf(0.05)) < 0) {
            stopLoss = BigDecimal.valueOf(0.05);
        }

        log.info("[STOP] {} position {} trend - Stop: {}% (${} -> ${})",
                option.getType(),
                isAligned ? "WITH" : "AGAINST",
                baseStopPercentage.multiply(BigDecimal.valueOf(100)),
                entryPrice, stopLoss);

        return stopLoss.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateDynamicTargetPrice(BigDecimal entryPrice, BigDecimal atr, String strategy,
                                                   TechnicalAnalysis ta, String marketTrend, Option option) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isAligned = (isPut && "DOWN".equals(marketTrend)) || (!isPut && "UP".equals(marketTrend));

        // Base target multipliers
        BigDecimal targetMultiplier;

        if (isAligned) {
            // BIGGER targets for trend-aligned positions
            switch (strategy) {
                case "0DTE_UNUSUAL_FLOW_CALL":
                case "0DTE_UNUSUAL_FLOW_PUT":
                    targetMultiplier = BigDecimal.valueOf(2.5); // 150% gain for unusual flow
                    break;
                case "0DTE_OPENING_DRIVE_CALL":
                case "0DTE_OPENING_DRIVE_PUT":
                    targetMultiplier = BigDecimal.valueOf(2.2); // 120% gain for opening drive
                    break;
                case "0DTE_VWAP_BREAKOUT_CALL":
                case "0DTE_VWAP_BREAKOUT_PUT":
                    targetMultiplier = BigDecimal.valueOf(2.0); // 100% gain for breakouts
                    break;
                case "0DTE_VOLUME_SPIKE_CALL":
                case "0DTE_VOLUME_SPIKE_PUT":
                    targetMultiplier = BigDecimal.valueOf(2.0); // 100% for volume spikes
                    break;
                case "0DTE_VWAP_REVERSION_CALL":
                case "0DTE_VWAP_REVERSION_PUT":
                    targetMultiplier = BigDecimal.valueOf(1.7); // 70% for reversions
                    break;
                default:
                    targetMultiplier = BigDecimal.valueOf(1.8); // 80% default
            }

            // Extra boost for strong trends
            if ("UP".equals(marketTrend) && ta.getMomentumStrength() > 0.7) {
                targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(1.2));
            } else if ("DOWN".equals(marketTrend) && ta.getMomentumStrength() < -0.7) {
                targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(1.2));
            }
        } else {
            // SMALLER targets for counter-trend positions
            targetMultiplier = BigDecimal.valueOf(1.4); // 40% gain max
        }

        // Adjust for time decay
        LocalTime now = LocalTime.now(ET_ZONE);
        long minutesToClose = Duration.between(now, LocalTime.of(16, 0)).toMinutes();

        if (minutesToClose < 120) { // Less than 2 hours
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(0.7)); // Reduce by 30%
        } else if (minutesToClose < 180) { // Less than 3 hours
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(0.85)); // Reduce by 15%
        }

        // Adjust for volatility
        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(1.15)); // 15% higher in vol
        }

        BigDecimal targetPrice = entryPrice.multiply(targetMultiplier);

        log.info("[TARGET] {} position {} trend - Target: {}% (${} -> ${})",
                option.getType(),
                isAligned ? "WITH" : "AGAINST",
                targetMultiplier.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)),
                entryPrice, targetPrice);

        return targetPrice.setScale(2, RoundingMode.HALF_UP);
    }

    private void analyzeUnusualFlow(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                    List<Signal> signals, String marketTrend, String analysisId) {
        if (!detectUnusualOptionsFlow(option,allOptions,analysisId)) {
            return;
        }
        if (!isSignalAlignedWithTrend(option, allOptions,ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        double confidence = 0.85;

        BigDecimal premium = option.getMidPrice()
                .multiply(BigDecimal.valueOf(option.getVolume()))
                .multiply(BigDecimal.valueOf(100));

        if (premium.compareTo(BigDecimal.valueOf(100000)) > 0) {
            confidence = 0.90;
        }

        String strategyName = "PUT".equalsIgnoreCase(option.getType()) ?
                "0DTE_UNUSUAL_FLOW_PUT" : "0DTE_UNUSUAL_FLOW_CALL";

        Signal signal = createSignal(option, allOptions,ta, "BUY", strategyName, confidence, marketTrend);
        signal.setReason(String.format(
                "🎯 Unusual flow detected - %s %s Vol: %d Premium: $%.0f (%.1fx OI)",
                option.getType(), option.getStrikePrice(),
                option.getVolume(), premium.doubleValue(),
                (double)option.getVolume() / option.getOpenInterest()
        ));

        signals.add(signal);
        log.info("[v63][{}] ✅ Created {} signal - Confidence: {}%",
                analysisId, strategyName, (int)(confidence * 100));
    }

    private boolean isSignalAlignedWithTrend(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                             String marketTrend, String analysisId) {
        log.info("[{}] Market trend: {}, TA trend: {}, Price: ${}, VWAP: ${}",
                analysisId, marketTrend, ta.getTrend(), ta.getCurrentPrice(), ta.getVwap());

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());

        // STRICT RULE 1: Block ALL signals in NEUTRAL trend - NO EXCEPTIONS
        if ("NEUTRAL".equals(marketTrend)) {
            log.warn("[{}] ❌ BLOCKED: {} {} - Market is NEUTRAL (NO TRADES ALLOWED)",
                    analysisId, option.getType(), option.getStrikePrice());
            return false;
        }

        // STRICT RULE 2: Enforce trend alignment for ALL signals including unusual flow
        boolean aligned = (isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend));

        if (detectUnusualOptionsFlow(option, allOptions,analysisId)) {
            BigDecimal premium = option.getMidPrice()
                    .multiply(BigDecimal.valueOf(option.getVolume()))
                    .multiply(BigDecimal.valueOf(100));

            if (!aligned) {
                log.warn("[{}] ⚠️ UNUSUAL FLOW BLOCKED: {} {} with ${} premium - AGAINST {} trend",
                        analysisId, option.getType(), option.getStrikePrice(), premium, marketTrend);

                // Alert for massive blocked flows
                if (premium.compareTo(BigDecimal.valueOf(1000000)) > 0) {
                    telegramService.sendMessage(String.format(
                            "🚫 MASSIVE FLOW BLOCKED\n" +
                                    "Type: %s %s\n" +
                                    "Premium: $%,.0f\n" +
                                    "Reason: Against %s trend - STAYING DISCIPLINED!",
                            option.getType(), option.getStrikePrice(),
                            premium.doubleValue(), marketTrend
                    ));
                }
                return false;
            }
        }

        log.info(" Trend alignment check - {} option, {} trend: {}",
                analysisId, option.getType(), marketTrend, aligned ? "PASS" : "FAIL");

        return aligned;
    }

    private boolean quickSignalValidation(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                          String marketTrend, String analysisId) {
        // Ultra-fast pre-checks before creating signal

        // 1. Neutral market - instant reject
        if ("NEUTRAL".equals(marketTrend)) {
            return false;
        }

        // 2. Option type alignment
        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean aligned = (isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend));

        if (!aligned && !detectUnusualOptionsFlow(option, allOptions,analysisId)) {
            return false; // Not aligned and not unusual flow
        }

        // 3. Minimum liquidity check
        if (option.getVolume() < 50 && option.getOpenInterest() < 100) {
            return false; // Too illiquid
        }

        // 4. Spread check
        if (option.getSpreadPercentage() > 15.0) {
            return false; // Spread too wide
        }

        return true;
    }


}