package com.tradingBot.service;

import com.tradingBot.entity.*;
import com.tradingBot.model.*;
import com.tradingBot.service.*;
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

    @Value("${trading.min-volume:50}")
    private int minVolume;

    @Value("${trading.min-iv:0.15}")
    private double minIV;

    @Value("${trading.max-spread-percentage:10.0}")
    private double maxSpreadPercentage;

    @Value("${trading.signal-expiration-minutes:5}")
    private int signalExpirationMinutes;

    // Timezone
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    // Updated strategy thresholds - REDUCED FOR 0DTE
    private static final double MIN_VOLUME_RATIO_BREAKOUT = 1.2;
    private static final double MIN_VOLUME_RATIO_STANDARD = 0.8;
    private static final double MAX_RSI_DIVERGENCE = 65.0;
    private static final double MIN_RSI_DIVERGENCE = 35.0;

    // Time windows
    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 45);
    private static final LocalTime MORNING_SESSION_END = LocalTime.of(11, 30);
    private static final LocalTime PRIME_WINDOW_1_START = LocalTime.of(9, 45);
    private static final LocalTime PRIME_WINDOW_1_END = LocalTime.of(10, 15);
    private static final LocalTime PRIME_WINDOW_2_START = LocalTime.of(10, 30);
    private static final LocalTime PRIME_WINDOW_2_END = LocalTime.of(12, 00);
    private static final LocalTime PRIME_WINDOW_3_START = LocalTime.of(13, 00);
    private static final LocalTime PRIME_WINDOW_3_END = LocalTime.of(14, 30);
    private static final LocalTime FINAL_WINDOW_START = LocalTime.of(15, 00);
    private static final LocalTime FINAL_WINDOW_END = LocalTime.of(15, 55);

    // Flow detection thresholds
    private static final double UNUSUAL_VOLUME_RATIO = 5.0;
    private static final int UNUSUAL_VOLUME_MIN = 5000;
    private static final double SMART_MONEY_THRESHOLD = 50000;

    // UPDATED METHOD SIGNATURE TO ACCEPT MARKET TREND
    public List<Signal> analyzeOptions(String symbol, String marketTrend) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[v63][{}] ========== STARTING 0DTE ANALYSIS FOR {} ==========", analysisId, symbol);
        log.info("[v63][{}] Market trend: {}", analysisId, marketTrend);

        List<Signal> rawSignals = new ArrayList<>();

        try {
            // Time check - avoid unsuitable times
            LocalTime now = LocalTime.now(ET_ZONE);
            // STRICT TIME CHECK - After 3:30 PM, only high confidence trades
            boolean isLateSession = now.isAfter(LocalTime.of(15, 30));
            if (isLateSession) {
                log.warn("[v63][{}] LATE SESSION MODE - Only 90%+ confidence signals allowed", analysisId);
            }

            // Block analysis in neutral market
            if ("NEUTRAL".equals(marketTrend)) {
                log.warn("[v63][{}] MARKET IS NEUTRAL - NO SIGNALS WILL BE GENERATED", analysisId);
                return rawSignals;
            }
            if (!isGoodTradingTime(now)) {
                log.warn("[v63][{}] Not a good time for 0DTE trading: {}", analysisId, now);
                return rawSignals;
            }

            // Get technical analysis
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta == null) {
                log.error("[v63][{}] Technical analysis failed", analysisId);
                return rawSignals;
            }

            logTechnicalAnalysis(ta, analysisId);

            MarketConditions marketConditions = getMarketConditions();
            if (!signalOrchestrator.shouldAnalyze(symbol, marketConditions)) {
                log.info("[v64][{}] Orchestrator vetoed analysis - unfavorable conditions", analysisId);
                return rawSignals;
            }

            // Check for today's expiration
            LocalDate today = LocalDate.now(ET_ZONE);
            List<LocalDate> expirations = tradierService.getExpirations(symbol);

            if (!expirations.contains(today)) {
                log.warn("[v63][{}] No 0DTE options available today", analysisId);
                return rawSignals;
            }

            // Get option chain
            OptionChainResponse chainResponse = tradierService.getOptionChain(symbol, today);

            if (chainResponse == null || !chainResponse.hasOptions()) {
                log.error("[v63][{}] No option chain data available", analysisId);
                return rawSignals;
            }

            List<Option> options = chainResponse.getOptionsList();
            log.info("[v63][{}] Retrieved {} options", analysisId, options.size());

            // Apply filtering based on market regime
            List<Option> filteredOptions = filterOptionsByMarketRegime(options, ta, analysisId);

            // Add confidence pre-filtering based on market conditions
            double expectedMaxConfidence = estimateMaxConfidence(ta);
            filteredOptions = filterByConfidenceAdjustedStrikes(filteredOptions, ta,
                    expectedMaxConfidence, analysisId);

            log.info("[v63][{}] Analyzing {} filtered options after confidence adjustment",
                    analysisId, filteredOptions.size());

            // Analyze each option against strategies - PASS MARKET TREND
            for (Option option : filteredOptions) {
                analyzeOptionWithStrategies(option, ta, rawSignals, marketTrend, analysisId);
            }

            // Use orchestrator to process signals intelligently
            List<Signal> orchestratedSignals = signalOrchestrator.orchestrateSignals(
                    rawSignals, ta, analysisId);

            // After getting orchestrated signals, filter by time
            if (isLateSession && !orchestratedSignals.isEmpty()) {
                List<Signal> highConfidenceOnly = orchestratedSignals.stream()
                        .filter(s -> s.getConfidence() >= 0.90)
                        .collect(Collectors.toList());

                log.info("[v63][{}] Late session filter: {} signals -> {} (90%+ only)",
                        analysisId, orchestratedSignals.size(), highConfidenceOnly.size());

                orchestratedSignals = highConfidenceOnly;
            }

            // Save signals with proper expiration
            saveSignals(orchestratedSignals, ta, marketTrend, analysisId);

            log.info("[v64][{}] ========== ANALYSIS COMPLETE - {} SIGNALS (from {} raw) ==========",
                    analysisId, orchestratedSignals.size(), rawSignals.size());

            return orchestratedSignals;

        } catch (Exception e) {
            log.error("[v63][{}] ERROR in analyzeOptions: {}", analysisId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // OVERLOADED METHOD FOR BACKWARD COMPATIBILITY
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

    private double estimateMaxConfidence(TechnicalAnalysis ta) {
        double maxConfidence = 0.5;

        if (ta.getVolumeRatio() > 2.0) {
            maxConfidence = Math.max(maxConfidence, 0.75);
        }

        if (ta.isVwapBreakout() && ta.getVolumeRatio() > 1.5) {
            maxConfidence = Math.max(maxConfidence, 0.78);
        }

        if (ta.isExtendedFromVwap() && (ta.getRsi() > 70 || ta.getRsi() < 30)) {
            maxConfidence = Math.max(maxConfidence, 0.80);
        }

        if ((ta.isVwapAsSupport() || ta.isVwapAsResistance()) && ta.getVolumeRatio() > 1.0) {
            maxConfidence = Math.max(maxConfidence, 0.70);
        }

        return maxConfidence;
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

        if (!now.isAfter(LocalTime.of(9, 30)) || !now.isBefore(LocalTime.of(10, 00))) {
            return false;
        }

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

    private boolean detectUnusualOptionsFlow(Option option, String analysisId) {
        if (option.getVolume() > UNUSUAL_VOLUME_MIN &&
                option.getOpenInterest() > 0 &&
                option.getVolume() > option.getOpenInterest() * 2) {

            BigDecimal premium = option.getMidPrice()
                    .multiply(BigDecimal.valueOf(option.getVolume()))
                    .multiply(BigDecimal.valueOf(100));

            if (premium.compareTo(BigDecimal.valueOf(SMART_MONEY_THRESHOLD)) > 0) {
                log.info("[v63][{}] 🎯 UNUSUAL FLOW DETECTED - {} {} Vol: {} Premium: ${}",
                        analysisId, option.getType(), option.getStrikePrice(),
                        option.getVolume(), premium);
                return true;
            }
        }
        return false;
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

        // GET ACTUAL STRIKE INTERVALS
        Set<BigDecimal> strikes = options.stream()
                .map(Option::getStrikePrice)
                .collect(Collectors.toCollection(TreeSet::new));

        // Find the ATM strike
        BigDecimal atmStrike = findATMStrike(strikes, currentPrice);

        // Determine strike interval (usually $1 for QQQ)
        BigDecimal strikeInterval = determineStrikeInterval(strikes);

        log.info("[v63][{}] Current price: ${}, ATM strike: ${}, Interval: ${}",
                analysisId, currentPrice, atmStrike, strikeInterval);

        // FILTER TO ONLY ATM AND 1 STRIKE OTM
        List<Option> filtered = options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    BigDecimal strikesAway = strike.subtract(atmStrike)
                            .divide(strikeInterval, 0, RoundingMode.HALF_UP).abs();

                    // For 0DTE, we want ONLY:
                    // - ATM (0 strikes away)
                    // - 1 strike OTM
                    // NO ITM OPTIONS for 0DTE

                    boolean isCall = "CALL".equalsIgnoreCase(option.getType());
                    boolean isPut = "PUT".equalsIgnoreCase(option.getType());

                    if (isCall) {
                        // For calls: ATM or 1 strike above
                        boolean isATM = strike.equals(atmStrike);
                        boolean is1OTM = strike.equals(atmStrike.add(strikeInterval));
                        return isATM || is1OTM;
                    } else if (isPut) {
                        // For puts: ATM or 1 strike below
                        boolean isATM = strike.equals(atmStrike);
                        boolean is1OTM = strike.equals(atmStrike.subtract(strikeInterval));
                        return isATM || is1OTM;
                    }

                    return false;
                })
                .collect(Collectors.toList());

        log.info("[v63][{}] Strike filtering: {} options -> {} (ATM + 1 OTM only)",
                analysisId, options.size(), filtered.size());

        // Log selected strikes
        filtered.forEach(opt -> {
            log.info("[v63][{}] Selected: {} {} - {} strikes from ATM",
                    analysisId, opt.getType(), opt.getStrikePrice(),
                    opt.getStrikePrice().subtract(atmStrike).divide(strikeInterval, 0, RoundingMode.HALF_UP).abs());
        });

        return filtered;
    }

    private BigDecimal findATMStrike(Set<BigDecimal> strikes, BigDecimal currentPrice) {
        // Find the closest strike to current price
        return strikes.stream()
                .min(Comparator.comparing(strike -> strike.subtract(currentPrice).abs()))
                .orElse(currentPrice.setScale(0, RoundingMode.HALF_UP));
    }

    private BigDecimal determineStrikeInterval(Set<BigDecimal> strikes) {
        if (strikes.size() < 2) {
            return BigDecimal.ONE; // Default $1
        }

        List<BigDecimal> strikeList = new ArrayList<>(strikes);
        Collections.sort(strikeList);

        // Get the most common interval
        Map<BigDecimal, Integer> intervalCounts = new HashMap<>();

        for (int i = 1; i < Math.min(strikeList.size(), 10); i++) {
            BigDecimal interval = strikeList.get(i).subtract(strikeList.get(i-1));
            intervalCounts.merge(interval, 1, Integer::sum);
        }

        return intervalCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(BigDecimal.ONE);
    }

    // UPDATED METHOD SIGNATURE
    private void analyzeOptionWithStrategies(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend, String analysisId) {

        if (detectUnusualOptionsFlow(option, analysisId)) {
            analyzeUnusualFlow(option, ta, signals, marketTrend, analysisId);
            return;
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isBefore(LocalTime.of(10, 0))) {
            analyzeOpeningDriveStrategy(option, ta, signals, marketTrend, analysisId);
            return;
        }

        if (now.isAfter(LocalTime.of(9, 45))) {
            analyzeOpeningRangeBreakout(option, ta, signals, marketTrend, analysisId);
        }

        if (ta.getVolumeRatio() > 1.5) {
            analyzeVolumeSpikeStrategy(option, ta, signals, marketTrend, analysisId);
        }

        if (ta.isVwapBreakout() && ta.getVolumeRatio() > 1.5) {
            analyzeVWAPBreakout(option, ta, signals, marketTrend, analysisId);
        } else if (ta.isExtendedFromVwap() && (ta.getRsi() > 70 || ta.getRsi() < 30)) {
            analyzeVWAPDeviationReversion(option, ta, signals, marketTrend, analysisId);
        } else if (ta.isVwapAsSupport() || ta.isVwapAsResistance()) {
            analyzeVWAPSupportResistance(option, ta, signals, marketTrend, analysisId);
        } else if (Math.abs(ta.getPriceToVwapRatio() - 1.0) < 0.005) {
            analyzeVWAPBounce(option, ta, signals, marketTrend, analysisId);
        }
    }

    // UPDATE ALL STRATEGY METHODS TO ACCEPT MARKET TREND
    private void analyzeOpeningDriveStrategy(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend, String analysisId) {
        if (!detectOpeningDrive(ta, analysisId)) {
            return;
        }
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_OPENING_DRIVE_CALL", confidence, marketTrend);
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_OPENING_DRIVE_PUT", confidence, marketTrend);
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

    private void analyzeOpeningRangeBreakout(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String marketTrend, String analysisId) {
        if (ta.getOpeningRangeHigh() == null || ta.getOpeningRangeLow() == null) {
            return;
        }
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);
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
                    Signal signal = createSignal(option, ta, "BUY", "0DTE_ORB_CALL", confidence, marketTrend);
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
                    Signal signal = createSignal(option, ta, "BUY", "0DTE_ORB_PUT", confidence, marketTrend);
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

    private void analyzeVWAPBreakout(Option option, TechnicalAnalysis ta,
                                     List<Signal> signals, String marketTrend, String analysisId) {
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, analysisId)) {
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
                        Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_BREAKOUT_CALL", confidence, marketTrend);
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
                        Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_BREAKOUT_PUT", confidence, marketTrend);
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

    private void analyzeVWAPDeviationReversion(Option option, TechnicalAnalysis ta,
                                               List<Signal> signals, String marketTrend, String analysisId) {
        if (!ta.isExtendedFromVwap() || ta.getVwapStandardDeviation() == null ||
                ta.getVwapStandardDeviation().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, analysisId)) {
            log.debug("[{}] Strategy blocked due to trend misalignment", analysisId);
            return;
        }

        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);
        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_REVERSION_PUT", confidence, marketTrend);
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_REVERSION_CALL", confidence, marketTrend);
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

    private void analyzeVolumeSpikeStrategy(Option option, TechnicalAnalysis ta,
                                            List<Signal> signals, String marketTrend, String analysisId) {
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, analysisId)) {
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VOLUME_SPIKE_CALL", confidence, marketTrend);
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VOLUME_SPIKE_PUT", confidence, marketTrend);
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

    private void analyzeVWAPSupportResistance(Option option, TechnicalAnalysis ta,
                                              List<Signal> signals, String marketTrend, String analysisId) {
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, analysisId)) {
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
                    Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_SUPPORT_CALL", confidence, marketTrend);
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
                    Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_RESISTANCE_PUT", confidence, marketTrend);
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

    private void analyzeVWAPBounce(Option option, TechnicalAnalysis ta,
                                   List<Signal> signals, String marketTrend, String analysisId) {
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, analysisId)) {
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_BOUNCE_CALL", confidence, marketTrend);
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_BOUNCE_PUT", confidence, marketTrend);
                signal.setReason(String.format("VWAP rejection with neutral RSI and volume confirmation%s",
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ VWAP Bounce PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }
    }

    private void saveSignals(List<Signal> signals, TechnicalAnalysis ta, String marketTrend, String analysisId) {
        for (Signal signal : signals) {
            // Dynamic expiration based on strategy and market conditions
            int expirationMinutes = calculateDynamicExpiration(signal, ta);
            LocalDateTime expirationTime = LocalDateTime.now().plusMinutes(expirationMinutes);

            signal.setExpirationTime(expirationTime);
            signal.setStatus("PENDING");
            signal.setCreatedAt(LocalDateTime.now()); // Track exact creation time
            signal.setEntryAssumptionPrice(ta.getCurrentPrice());
            signal.setMarketTrend(marketTrend);

            // Store current option price for validation
            signal.setOriginalOptionPrice(signal.getEntryPrice());

            signalRepository.save(signal);

            log.info("[v63][{}] SAVED {} - {} {}, Confidence: {}%, Expires in {} min at {}",
                    analysisId, signal.getStrategy(), signal.getSignalType(),
                    signal.getOptionSymbol(), (int)(signal.getConfidence() * 100),
                    expirationMinutes, expirationTime.toLocalTime());

            // Alert for high-priority signals
            if (signal.getConfidence() >= 0.85 || signal.getStrategy().contains("UNUSUAL_FLOW")) {
                telegramService.sendMessage(String.format(
                        "🚨 HIGH PRIORITY SIGNAL\n" +
                                "Strategy: %s\n" +
                                "Option: %s\n" +
                                "Confidence: %d%%\n" +
                                "Entry: $%.2f\n" +
                                "Expires: %d minutes",
                        signal.getStrategy(),
                        signal.getOptionSymbol(),
                        (int)(signal.getConfidence() * 100),
                        signal.getEntryPrice(),
                        expirationMinutes
                ));
            }
        }
    }

    private int calculateDynamicExpiration(Signal signal, TechnicalAnalysis ta) {
        String strategy = signal.getStrategy();
        LocalTime now = LocalTime.now(ET_ZONE);

        // Unusual flow - very short expiration
        if (strategy.contains("UNUSUAL_FLOW")) {
            return 2; // 2 minutes - execute ASAP
        }
        // Near market close - VERY short window
        if (now.isAfter(LocalTime.of(15, 30))) {
            return 1; // 1 minute only!
        }

        // High confidence signals - quick execution
        if (signal.getConfidence() >= 0.85) {
            return 3; // 3 minutes for high confidence
        }

        // Scalp trades - medium expiration
        if (strategy.contains("SCALP") || strategy.contains("BOUNCE")) {
            return 4; // 4 minutes
        }

        // Breakout trades - slightly longer
        if (strategy.contains("BREAKOUT") || strategy.contains("OPENING_DRIVE")) {
            return 5; // 5 minutes
        }


        // High volatility - faster execution needed
        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            return 3; // 3 minutes
        }

        // Default - still quick
        return 4; // 4 minutes default
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

    // UPDATED createSignal METHOD WITH MARKET TREND
    private Signal createSignal(Option option, TechnicalAnalysis ta, String signalType,
                                String strategy, double confidence, String marketTrend) {
        if (option == null || ta == null || signalType == null || strategy == null) {
            log.error("Cannot create signal with null parameters");
            return null;
        }
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, "signal-creation")) {
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

    // NEW DYNAMIC STOP LOSS CALCULATION
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

    // NEW DYNAMIC TARGET CALCULATION
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

    private void analyzeUnusualFlow(Option option, TechnicalAnalysis ta,
                                    List<Signal> signals, String marketTrend, String analysisId) {
        if (!detectUnusualOptionsFlow(option, analysisId)) {
            return;
        }
        if (!isSignalAlignedWithTrend(option, ta, marketTrend, analysisId)) {
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

        Signal signal = createSignal(option, ta, "BUY", strategyName, confidence, marketTrend);
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

    // UPDATED TREND ALIGNMENT CHECK
    private boolean isSignalAlignedWithTrend(Option option, TechnicalAnalysis ta,
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

        if (detectUnusualOptionsFlow(option, analysisId)) {
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

    private boolean quickSignalValidation(Option option, TechnicalAnalysis ta,
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

        if (!aligned && !detectUnusualOptionsFlow(option, analysisId)) {
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