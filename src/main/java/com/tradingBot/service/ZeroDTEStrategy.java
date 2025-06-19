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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZeroDTEStrategy {

    private final TradierService tradierService;
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
    private static final double MIN_VOLUME_RATIO_BREAKOUT = 1.2;  // Reduced from 1.5
    private static final double MIN_VOLUME_RATIO_STANDARD = 0.8;  // Reduced from 1.0
    private static final double MAX_RSI_DIVERGENCE = 65.0;
    private static final double MIN_RSI_DIVERGENCE = 35.0;

    // Time windows
    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 45);
    private static final LocalTime MORNING_SESSION_END = LocalTime.of(11, 30);
    // Enhanced strategy parameters
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
    private static final double SMART_MONEY_THRESHOLD = 50000; // $50k premium
    private static final LocalTime LUNCH_START = LocalTime.of(11, 30);
    private static final LocalTime LUNCH_END = LocalTime.of(13, 00);
    private static final LocalTime POWER_HOUR_START = LocalTime.of(15, 00);
    private static final LocalTime CLOSING_CUTOFF = LocalTime.of(15, 30);

    public List<Signal> analyzeOptions(String symbol) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[v63][{}] ========== STARTING 0DTE ANALYSIS FOR {} ==========", analysisId, symbol);

        List<Signal> rawSignals = new ArrayList<>();

        try {
            // Time check - avoid unsuitable times
            LocalTime now = LocalTime.now(ET_ZONE);
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

            // Analyze each option against strategies
            for (Option option : filteredOptions) {
                analyzeOptionWithStrategies(option, ta, rawSignals, analysisId);
            }

            // Use orchestrator to process signals intelligently
            List<Signal> orchestratedSignals = signalOrchestrator.orchestrateSignals(
                    rawSignals, ta, analysisId);

            // Save the orchestrated signals
            saveSignals(orchestratedSignals, ta, analysisId);

            log.info("[v64][{}] ========== ANALYSIS COMPLETE - {} SIGNALS (from {} raw) ==========",
                    analysisId, orchestratedSignals.size(), rawSignals.size());

            return orchestratedSignals;

        } catch (Exception e) {
            log.error("[v63][{}] ERROR in analyzeOptions: {}", analysisId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // NEW METHOD: Time-based volume adjustment
    private int getAdjustedMinVolume(LocalTime now) {
        if (now.isBefore(LocalTime.of(10, 30))) {
            return minVolume / 2;  // 25 instead of 50 early
        } else if (now.isAfter(LocalTime.of(15, 0))) {
            return minVolume / 2;  // Lower near close
        }
        return minVolume;
    }

    private MarketConditions getMarketConditions() {
        // This would fetch VIX, market breadth, etc.
        // For now, return a placeholder
        MarketConditions conditions = new MarketConditions();
        conditions.setVix(getVixLevel());
        conditions.setMarketBreadth(calculateMarketBreadth());
        conditions.setInternals(getMarketInternals());
        return conditions;
    }

    private double getVixLevel() {
        // Fetch VIX from your data provider
        // Placeholder: return normal VIX level
        return 18.0;
    }

    private double calculateMarketBreadth() {
        // Calculate advance/decline ratio or similar
        // Placeholder: return neutral breadth
        return 0.5;
    }

    private MarketInternals getMarketInternals() {
        // Get TICK, ADD, etc.
        MarketInternals internals = new MarketInternals();
        // Populate with real data
        return internals;
    }

    private double estimateMaxConfidence(TechnicalAnalysis ta) {
        double maxConfidence = 0.5; // Base

        // Volume spike scenarios
        if (ta.getVolumeRatio() > 2.0) {
            maxConfidence = Math.max(maxConfidence, 0.75);
        }

        // Breakout scenarios
        if (ta.isVwapBreakout() && ta.getVolumeRatio() > 1.5) {
            maxConfidence = Math.max(maxConfidence, 0.78);
        }

        // Reversion scenarios
        if (ta.isExtendedFromVwap() && (ta.getRsi() > 70 || ta.getRsi() < 30)) {
            maxConfidence = Math.max(maxConfidence, 0.80);
        }

        // Support/Resistance with volume
        if ((ta.isVwapAsSupport() || ta.isVwapAsResistance()) && ta.getVolumeRatio() > 1.0) {
            maxConfidence = Math.max(maxConfidence, 0.70);
        }

        return maxConfidence;
    }

    private boolean isGoodTradingTime(LocalTime now) {
        // Prime trading windows only
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

        // Only valid in first 30 minutes
        if (!now.isAfter(LocalTime.of(9, 30)) || !now.isBefore(LocalTime.of(10, 00))) {
            return false;
        }

        // Check for gap and volume
        BigDecimal gap = calculatePremarketGap(ta);
        boolean hasGap = gap.abs().compareTo(BigDecimal.valueOf(0.005)) > 0; // 0.5% gap
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
        // Calculate gap from previous close
        if (ta.getPreviousClose() != null && ta.getPreviousClose().compareTo(BigDecimal.ZERO) > 0) {
            return ta.getCurrentPrice().subtract(ta.getPreviousClose())
                    .divide(ta.getPreviousClose(), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private boolean detectUnusualOptionsFlow(Option option, String analysisId) {
        // Check for unusual volume patterns
        if (option.getVolume() > UNUSUAL_VOLUME_MIN &&
                option.getOpenInterest() > 0 &&
                option.getVolume() > option.getOpenInterest() * 2) {

            // Calculate premium
            BigDecimal premium = option.getMidPrice()
                    .multiply(BigDecimal.valueOf(option.getVolume()))
                    .multiply(BigDecimal.valueOf(100)); // Contract multiplier

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
        // Boost confidence during prime windows
        if ((now.isAfter(PRIME_WINDOW_1_START) && now.isBefore(PRIME_WINDOW_1_END))) {
            return 1.15; // 15% boost for morning momentum
        } else if ((now.isAfter(PRIME_WINDOW_2_START) && now.isBefore(PRIME_WINDOW_2_END))) {
            return 1.10; // 10% boost for late morning
        } else if ((now.isAfter(PRIME_WINDOW_3_START) && now.isBefore(PRIME_WINDOW_3_END))) {
            return 1.20; // 20% boost for afternoon move
        } else if ((now.isAfter(FINAL_WINDOW_START) && now.isBefore(FINAL_WINDOW_END))) {
            return 1.05; // 5% boost for close positioning
        }
        return 0.8; // 20% penalty outside prime windows
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

        // Add time-based adjustment
        LocalTime now = LocalTime.now(ET_ZONE);

        // Use time-based volume adjustment
        int adjustedMinVolume = getAdjustedMinVolume(now);
        double adjustedMinIV = minIV;
        double maxMoneyness = 0.03; // 3% default - will be overridden later

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

        // Apply dynamic parameters if available
        if (params != null) {
            adjustedMinVolume = (int)(adjustedMinVolume * params.getMinVolumeMultiplier());
            adjustedMinIV = adjustedMinIV * params.getMinIVMultiplier();
        }

        final int finalMinVolume = adjustedMinVolume;
        final double finalMinIV = adjustedMinIV;

        log.info("[v63][{}] Base filtering - Volume: {}, IV: {:.3f}",
                analysisId, finalMinVolume, finalMinIV);

        // First pass: basic filtering
        List<Option> basicFiltered = options.stream()
                .filter(option -> option.isValid())
                .filter(option -> option.getVolume() >= finalMinVolume)
                .filter(option -> {
                    Double iv = option.getImpliedVolatility();
                    return iv != null && iv >= finalMinIV;
                })
                .filter(option -> option.getSpreadPercentage() <= maxSpreadPercentage)
                .collect(Collectors.toList());

        // Add debug logging
        log.info("[v63][{}] Volume filtering: {} options -> {} passed (min vol: {})",
                analysisId, options.size(), basicFiltered.size(), finalMinVolume);

        // Add detailed logging for failed options
        if (log.isDebugEnabled()) {
            options.stream()
                    .filter(option -> option.getVolume() < finalMinVolume)
                    .limit(5)  // Show first 5 failures
                    .forEach(option ->
                            log.debug("[{}] Option {} failed volume check: {} < {}",
                                    analysisId, option.getSymbol(), option.getVolume(), finalMinVolume)
                    );
        }

        // Sort by distance from current price (ATM first)
        BigDecimal currentPrice = ta.getCurrentPrice();
        basicFiltered.sort((o1, o2) -> {
            BigDecimal diff1 = o1.getStrikePrice().subtract(currentPrice).abs();
            BigDecimal diff2 = o2.getStrikePrice().subtract(currentPrice).abs();
            return diff1.compareTo(diff2);
        });

        return basicFiltered;
    }

    private List<Option> filterByConfidenceAdjustedStrikes(List<Option> options,
                                                           TechnicalAnalysis ta,
                                                           double expectedConfidence,
                                                           String analysisId) {
        BigDecimal currentPrice = ta.getCurrentPrice();
        double maxMoneyness;
        int maxStrikes;

        // Determine moneyness threshold based on expected confidence
        if (expectedConfidence >= 0.75) {
            maxMoneyness = 0.005; // 0.5% - ATM only
            maxStrikes = 4; // Only 4 closest strikes
            log.info("[v63][{}] High confidence expected - Using tight strikes (0.5% max)", analysisId);
        } else if (expectedConfidence >= 0.65) {
            maxMoneyness = 0.01; // 1% OTM max
            maxStrikes = 6;
        } else {
            maxMoneyness = 0.02; // 2% OTM max
            maxStrikes = 8;
        }

        // Filter by moneyness and limit strikes
        List<Option> filtered = options.stream()
                .filter(option -> {
                    BigDecimal moneyness = option.getStrikePrice()
                            .subtract(currentPrice).abs()
                            .divide(currentPrice, 4, RoundingMode.HALF_UP);
                    return moneyness.compareTo(BigDecimal.valueOf(maxMoneyness)) <= 0;
                })
                .limit(maxStrikes)
                .collect(Collectors.toList());

        log.info("[v63][{}] Strike filtering: {} options within {}% of spot price ${}",
                analysisId, filtered.size(),
                String.format("%.1f", maxMoneyness * 100),
                currentPrice);

        return filtered;
    }

    private void analyzeOpeningDriveStrategy(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String analysisId) {
        if (!detectOpeningDrive(ta, analysisId)) {
            return;
        }

        // Check for unusual flow
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);

        BigDecimal gap = calculatePremarketGap(ta);
        boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
        boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

        // Trade in direction of gap with momentum
        if (gap.compareTo(BigDecimal.ZERO) > 0 && isCallOption &&
                "BULLISH".equals(ta.getTrend())) {

            double confidence = 0.70; // High base confidence

            // Boost for volume or unusual flow
            if (ta.getVolumeRatio() > 2.0 || hasUnusualFlow) {
                confidence += 0.15;
            }

            // Boost for momentum alignment
            if (ta.getMomentumStrength() > 0.5) {
                confidence += 0.10;
            }

            if (confidence >= 0.70) {
                Signal signal = createSignal(option, ta, "BUY", "0DTE_OPENING_DRIVE_CALL", confidence);
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

        // Negative gap
        if (gap.compareTo(BigDecimal.ZERO) < 0 && isPutOption &&
                "BEARISH".equals(ta.getTrend())) {

            double confidence = 0.70;

            if (ta.getVolumeRatio() > 2.0 || hasUnusualFlow) {
                confidence += 0.15;
            }

            if (ta.getMomentumStrength() < -0.5) {
                confidence += 0.10;
            }

            if (confidence >= 0.70) {
                Signal signal = createSignal(option, ta, "BUY", "0DTE_OPENING_DRIVE_PUT", confidence);
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

    private void analyzeOptionWithStrategies(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String analysisId) {

        // Add this FIRST to capture unusual flow as signals
        analyzeUnusualFlow(option, ta, signals, analysisId);

        MarketRegime regime = ta.getMarketRegime();
        LocalTime now = LocalTime.now(ET_ZONE);

        // Get time window multiplier
        double timeMultiplier = getTimeWindowMultiplier(now);

        // Check moneyness early
        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal moneyness = option.getStrikePrice()
                .subtract(currentPrice).abs()
                .divide(currentPrice, 4, RoundingMode.HALF_UP);

        log.debug("[v63][{}] Analyzing {} strike ${} ({}% from spot)",
                analysisId, option.getType(), option.getStrikePrice(),
                String.format("%.2f", moneyness.multiply(BigDecimal.valueOf(100)).doubleValue()));

        // Analyze with various strategies
        analyzeOpeningDriveStrategy(option, ta, signals, analysisId);
        analyzeOpeningRangeBreakout(option, ta, signals, analysisId);
        analyzeVWAPBreakout(option, ta, signals, analysisId);
        analyzeVWAPDeviationReversion(option, ta, signals, analysisId);
        analyzeVolumeSpikeStrategy(option, ta, signals, analysisId);
        analyzeVWAPSupportResistance(option, ta, signals, analysisId);
        analyzeVWAPBounce(option, ta, signals, analysisId);
    }

    // Updated Strategy: Opening Range Breakout
    private void analyzeOpeningRangeBreakout(Option option, TechnicalAnalysis ta,
                                             List<Signal> signals, String analysisId) {
        if (ta.getOpeningRangeHigh() == null || ta.getOpeningRangeLow() == null) {
            return;
        }

        // Check for unusual flow
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal orHigh = ta.getOpeningRangeHigh();
        BigDecimal orLow = ta.getOpeningRangeLow();

        log.info("[v63][{}] ORB Analysis - Current: ${}, OR High: ${}, OR Low: ${}",
                analysisId, currentPrice, orHigh, orLow);

        // Breakout above opening range high
        if (currentPrice.compareTo(orHigh) > 0 && "CALL".equalsIgnoreCase(option.getType())) {
            boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT;

            if (volumeRequirementMet && !ta.isHasRsiDivergence()) {
                double confidence = calculateORBConfidence(ta, true);

                // Boost confidence for unusual flow
                if (hasUnusualFlow) {
                    confidence = Math.min(confidence * 1.15, 0.95);
                }

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, ta, "BUY", "0DTE_ORB_CALL", confidence);
                    signal.setReason(String.format(
                            "Opening range breakout above $%.2f with %.1fx volume%s",
                            orHigh, ta.getVolumeRatio(),
                            hasUnusualFlow ? " (Unusual Flow)" : ""));
                    signals.add(signal);

                    log.info("[v63][{}] ✅ ORB CALL signal - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            } else if (!volumeRequirementMet) {
                log.debug("[{}] Skipping ORB CALL - Volume ratio {:.2f} < required {:.1f}",
                        analysisId, ta.getVolumeRatio(), MIN_VOLUME_RATIO_BREAKOUT);
            }
        }

        // Breakdown below opening range low
        if (currentPrice.compareTo(orLow) < 0 && "PUT".equalsIgnoreCase(option.getType())) {
            boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT;

            if (volumeRequirementMet && !ta.isHasRsiDivergence()) {
                double confidence = calculateORBConfidence(ta, false);

                // Boost confidence for unusual flow
                if (hasUnusualFlow) {
                    confidence = Math.min(confidence * 1.15, 0.95);
                }

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, ta, "BUY", "0DTE_ORB_PUT", confidence);
                    signal.setReason(String.format(
                            "Opening range breakdown below $%.2f with %.1fx volume%s",
                            orLow, ta.getVolumeRatio(),
                            hasUnusualFlow ? " (Unusual Flow)" : ""));
                    signals.add(signal);

                    log.info("[v63][{}] ✅ ORB PUT signal - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            } else if (!volumeRequirementMet) {
                log.debug("[{}] Skipping ORB PUT - Volume ratio {:.2f} < required {:.1f}",
                        analysisId, ta.getVolumeRatio(), MIN_VOLUME_RATIO_BREAKOUT);
            }
        }
    }

    // Updated VWAP Breakout with divergence check
    private void analyzeVWAPBreakout(Option option, TechnicalAnalysis ta,
                                     List<Signal> signals, String analysisId) {
        // Check for unusual flow
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwapUpper = ta.getVwapUpperBand();
        BigDecimal vwapLower = ta.getVwapLowerBand();

        // Check for divergence
        if (ta.isHasRsiDivergence()) {
            log.info("[v63][{}] ⚠️ RSI divergence detected - reducing breakout confidence", analysisId);
        }

        // Breakout above upper band
        if (currentPrice.compareTo(vwapUpper) > 0 && "CALL".equalsIgnoreCase(option.getType())) {
            // Calculate breakout quality
            BigDecimal breakoutDistance = currentPrice.subtract(vwapUpper)
                    .divide(vwapUpper, 4, RoundingMode.HALF_UP);

            if (breakoutDistance.compareTo(BigDecimal.valueOf(0.002)) >= 0) { // 0.2% minimum
                boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT;

                if (volumeRequirementMet) {
                    double confidence = calculateBreakoutConfidence(ta, true);

                    // Reduce confidence for divergence
                    if (ta.isHasRsiDivergence()) {
                        confidence *= 0.7;
                    }

                    // Boost confidence for unusual flow
                    if (hasUnusualFlow) {
                        confidence = Math.min(confidence * 1.15, 0.95);
                    }

                    if (confidence >= 0.60) {
                        Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_BREAKOUT_CALL", confidence);
                        signal.setReason(String.format(
                                "VWAP breakout %.2f%% above upper band with %.1fx volume%s",
                                breakoutDistance.multiply(BigDecimal.valueOf(100)).doubleValue(),
                                ta.getVolumeRatio(),
                                hasUnusualFlow ? " (Unusual Flow)" : ""));
                        signals.add(signal);

                        log.info("[v63][{}] ✅ VWAP Breakout CALL - Confidence: {}%",
                                analysisId, (int)(confidence * 100));
                    }
                } else {
                    log.debug("[{}] Skipping VWAP Breakout CALL - Volume ratio {:.2f} < required {:.1f}",
                            analysisId, ta.getVolumeRatio(), MIN_VOLUME_RATIO_BREAKOUT);
                }
            }
        }

        // Breakout below lower band
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
                        Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_BREAKOUT_PUT", confidence);
                        signal.setReason(String.format(
                                "VWAP breakout %.2f%% below lower band with %.1fx volume%s",
                                breakoutDistance.multiply(BigDecimal.valueOf(100)).doubleValue(),
                                ta.getVolumeRatio(),
                                hasUnusualFlow ? " (Unusual Flow)" : ""));
                        signals.add(signal);

                        log.info("[v63][{}] ✅ VWAP Breakout PUT - Confidence: {}%",
                                analysisId, (int)(confidence * 100));
                    }
                } else {
                    log.debug("[{}] Skipping VWAP Breakout PUT - Volume ratio {:.2f} < required {:.1f}",
                            analysisId, ta.getVolumeRatio(), MIN_VOLUME_RATIO_BREAKOUT);
                }
            }
        }
    }

    // Updated VWAP Deviation Reversion (Mean Reversion)
    private void analyzeVWAPDeviationReversion(Option option, TechnicalAnalysis ta,
                                               List<Signal> signals, String analysisId) {
        // Add null check for VWAP standard deviation
        if (!ta.isExtendedFromVwap() || ta.getVwapStandardDeviation() == null ||
                ta.getVwapStandardDeviation().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        // Check for unusual flow
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double priceRatio = ta.getPriceToVwapRatio();

        log.info("[v63][{}] Deviation Reversion - Price/VWAP: {}, Extended: {}",
                analysisId,
                String.format("%.3f", priceRatio),
                ta.isExtendedFromVwap());

        // PUT opportunity - price extended above VWAP
        if (priceRatio > 1.0 && "PUT".equalsIgnoreCase(option.getType()) && ta.getRsi() > 70) {
            double confidence = calculateReversionConfidence(ta, false);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_REVERSION_PUT", confidence);
                signal.setReason(String.format(
                        "Extended %.2f%% above VWAP with RSI %.1f - mean reversion setup%s",
                        (priceRatio - 1) * 100, ta.getRsi(),
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ VWAP Reversion PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }

        // CALL opportunity - price extended below VWAP
        if (priceRatio < 1.0 && "CALL".equalsIgnoreCase(option.getType()) && ta.getRsi() < 30) {
            double confidence = calculateReversionConfidence(ta, true);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_REVERSION_CALL", confidence);
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

    // Updated Volume Spike Momentum
    private void analyzeVolumeSpikeStrategy(Option option, TechnicalAnalysis ta,
                                            List<Signal> signals, String analysisId) {
        // Check for unusual flow
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);

        // Relax volume requirement if unusual flow detected
        double requiredVolumeRatio = hasUnusualFlow ? 1.5 : 2.0;

        if (ta.getVolumeRatio() < requiredVolumeRatio && !hasUnusualFlow) {
            log.debug("[{}] Skipping Volume Spike - Volume ratio {:.2f} < required {:.1f}",
                    analysisId, ta.getVolumeRatio(), requiredVolumeRatio);
            return;
        }

        log.info("[v63][{}] Volume Spike Analysis - Ratio: {}x, Trend: {}",
                analysisId,
                String.format("%.1f", ta.getVolumeRatio()),
                ta.getTrend());

        // Bullish volume spike
        if ("CALL".equalsIgnoreCase(option.getType()) &&
                "BULLISH".equals(ta.getTrend()) &&
                ta.getMomentumStrength() > 0.3) {

            double confidence = calculateVolumeSpikeConfidence(ta, true);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VOLUME_SPIKE_CALL", confidence);
                signal.setReason(String.format(
                        "Volume spike %.1fx with bullish momentum - continuation play%s",
                        ta.getVolumeRatio(),
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ Volume Spike CALL - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }

        // Bearish volume spike
        if ("PUT".equalsIgnoreCase(option.getType()) &&
                "BEARISH".equals(ta.getTrend()) &&
                ta.getMomentumStrength() < -0.3) {

            double confidence = calculateVolumeSpikeConfidence(ta, false);

            if (hasUnusualFlow) {
                confidence = Math.min(confidence * 1.15, 0.95);
            }

            if (confidence >= 0.65) {
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VOLUME_SPIKE_PUT", confidence);
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

    // Updated VWAP Support/Resistance
    private void analyzeVWAPSupportResistance(Option option, TechnicalAnalysis ta,
                                              List<Signal> signals, String analysisId) {
        // Check for unusual flow
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        if (ta.isVwapAsSupport() && "CALL".equalsIgnoreCase(option.getType()) &&
                currentPrice.compareTo(vwap) > 0) {

            // Relax volume requirement for support/resistance
            boolean volumeRequirementMet = hasUnusualFlow || ta.getVolumeRatio() >= MIN_VOLUME_RATIO_STANDARD;

            if (volumeRequirementMet) {
                double confidence = calculateSupportResistanceConfidence(ta, true);

                if (hasUnusualFlow) {
                    confidence = Math.min(confidence * 1.15, 0.95);
                }

                if (confidence >= 0.65) {
                    Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_SUPPORT_CALL", confidence);
                    signal.setReason(String.format(
                            "VWAP support at $%.2f held with %d touches%s",
                            vwap, 3,
                            hasUnusualFlow ? " (Unusual Flow)" : ""));
                    signals.add(signal);

                    log.info("[v63][{}] ✅ VWAP Support CALL - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            } else {
                log.debug("[{}] Skipping VWAP Support - Volume ratio {:.2f} < required {:.1f}",
                        analysisId, ta.getVolumeRatio(), MIN_VOLUME_RATIO_STANDARD);
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
                    Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_RESISTANCE_PUT", confidence);
                    signal.setReason(String.format(
                            "VWAP resistance at $%.2f rejected with %d touches%s",
                            vwap, 3,
                            hasUnusualFlow ? " (Unusual Flow)" : ""));
                    signals.add(signal);

                    log.info("[v63][{}] ✅ VWAP Resistance PUT - Confidence: {}%",
                            analysisId, (int)(confidence * 100));
                }
            } else {
                log.debug("[{}] Skipping VWAP Resistance - Volume ratio {:.2f} < required {:.1f}",
                        analysisId, ta.getVolumeRatio(), MIN_VOLUME_RATIO_STANDARD);
            }
        }
    }

    private void analyzeVWAPBounce(Option option, TechnicalAnalysis ta,
                                   List<Signal> signals, String analysisId) {
        // Check for unusual flow
        boolean hasUnusualFlow = detectUnusualOptionsFlow(option, analysisId);

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        if (Math.abs(ta.getPriceToVwapRatio() - 1.0) > 0.005) {
            return; // Not close enough to VWAP
        }

        // Relax volume requirement for bounces if unusual flow detected
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_BOUNCE_CALL", confidence);
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
                Signal signal = createSignal(option, ta, "BUY", "0DTE_VWAP_BOUNCE_PUT", confidence);
                signal.setReason(String.format("VWAP rejection with neutral RSI and volume confirmation%s",
                        hasUnusualFlow ? " (Unusual Flow)" : ""));
                signals.add(signal);

                log.info("[v63][{}] ✅ VWAP Bounce PUT - Confidence: {}%",
                        analysisId, (int)(confidence * 100));
            }
        }
    }

    // Apply time decay to all signals
    private List<Signal> applyTimeDecayAndSort(List<Signal> signals, String analysisId) {
        LocalTime now = LocalTime.now(ET_ZONE);
        double timeDecayFactor = calculateGlobalTimeDecay(now);

        log.info("[v63][{}] Applying time decay factor: {}",
                analysisId,
                String.format("%.2f", timeDecayFactor));

        return signals.stream()
                .map(signal -> {
                    double adjustedConfidence = signal.getConfidence() * timeDecayFactor;
                    signal.setConfidence(adjustedConfidence);
                    return signal;
                })
                .filter(signal -> signal.getConfidence() >= 0.50)
                .sorted(Comparator.comparing(Signal::getConfidence).reversed())
                .collect(Collectors.toList());
    }

    private double calculateGlobalTimeDecay(LocalTime now) {
        if (now.isBefore(LocalTime.of(11, 0))) {
            return 1.0; // Full confidence before 11 AM
        } else if (now.isBefore(LocalTime.of(13, 0))) {
            return 0.9; // 90% between 11 AM - 1 PM
        } else if (now.isBefore(LocalTime.of(14, 0))) {
            return 0.8; // 80% between 1 PM - 2 PM
        } else if (now.isBefore(LocalTime.of(15, 0))) {
            return 0.7; // 70% between 2 PM - 3 PM
        } else {
            return 0.5; // 50% after 3 PM
        }
    }

    private List<Signal> selectTopSignals(List<Signal> signals, TechnicalAnalysis ta,
                                          String analysisId) {
        List<Signal> selected = new ArrayList<>();

        // First priority: Signals with unusual flow
        signals.stream()
                .filter(s -> s.getReason() != null && s.getReason().contains("flow"))
                .sorted(Comparator.comparing(Signal::getConfidence).reversed())
                .limit(2)
                .forEach(selected::add);

        // Second priority: Opening drive signals
        if (selected.size() < 3) {
            signals.stream()
                    .filter(s -> s.getStrategy().contains("OPENING_DRIVE"))
                    .filter(s -> !selected.contains(s))
                    .findFirst()
                    .ifPresent(selected::add);
        }

        // Third priority: Time window aligned signals
        LocalTime now = LocalTime.now(ET_ZONE);
        if (getTimeWindowMultiplier(now) > 1.0 && selected.size() < 3) {
            signals.stream()
                    .filter(s -> !selected.contains(s))
                    .filter(s -> s.getConfidence() > 0.70)
                    .sorted(Comparator.comparing(Signal::getConfidence).reversed())
                    .limit(3 - selected.size())
                    .forEach(selected::add);
        }

        // Fill remaining with highest confidence
        if (selected.size() < 3) {
            signals.stream()
                    .filter(s -> !selected.contains(s))
                    .sorted(Comparator.comparing(Signal::getConfidence).reversed())
                    .limit(3 - selected.size())
                    .forEach(selected::add);
        }

        return selected;
    }

    private List<String> getPriorityStrategies(MarketRegime regime) {
        switch (regime) {
            case TRENDING_UP:
            case TRENDING_DOWN:
                return List.of("BREAKOUT", "ORB", "SPIKE");
            case CHOPPY:
                return List.of("REVERSION", "BOUNCE", "SUPPORT");
            case HIGH_VOLATILITY:
                return List.of("REVERSION", "SPIKE", "BREAKOUT");
            case LOW_VOLATILITY:
                return List.of("BREAKOUT", "ORB", "SUPPORT");
            default:
                return List.of("BREAKOUT", "REVERSION", "SPIKE");
        }
    }

    private void saveSignals(List<Signal> signals, TechnicalAnalysis ta, String analysisId) {
        LocalDateTime expirationTime = LocalDateTime.now().plusMinutes(signalExpirationMinutes);

        for (Signal signal : signals) {
            signal.setExpirationTime(expirationTime);
            signal.setStatus("PENDING");
            signal.setEntryAssumptionPrice(ta.getCurrentPrice());

            // Set appropriate exit times based on strategy
            if (signal.getStrategy().contains("SCALP")) {
                // Don't override expiration for scalps, they need immediate execution
                signal.setExpirationTime(LocalDateTime.now().plusMinutes(10)); // Give more time
            }

            signalRepository.save(signal);

            log.info("[v63][{}] SAVED {} - {} {}, Confidence: {}%, Target: ${}, Stop: ${}",
                    analysisId, signal.getStrategy(), signal.getSignalType(),
                    signal.getOptionSymbol(), (int)(signal.getConfidence() * 100),
                    signal.getTargetPrice(), signal.getStopLoss());
        }
    }

    // Updated confidence calculations

    private double calculateBreakoutConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.5;

        // Volume confirmation (relaxed)
        if (ta.getVolumeRatio() >= 2.0) {
            confidence += 0.30;
        } else if (ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT) {
            confidence += 0.20;
        } else if (ta.getVolumeRatio() >= 1.0) {
            confidence += 0.10; // Still give some credit for average volume
        }

        // Momentum alignment
        if (bullish && ta.getRsi() > 55 && ta.getRsi() < 70) {
            confidence += 0.15;
        } else if (!bullish && ta.getRsi() < 45 && ta.getRsi() > 30) {
            confidence += 0.15;
        }

        // Trend alignment
        if ((bullish && "BULLISH".equals(ta.getVwapTrend())) ||
                (!bullish && "BEARISH".equals(ta.getVwapTrend()))) {
            confidence += 0.10;
        }

        // Market regime bonus
        if (ta.getMarketRegime() == MarketRegime.TRENDING_UP && bullish ||
                ta.getMarketRegime() == MarketRegime.TRENDING_DOWN && !bullish) {
            confidence += 0.10;
        }

        // Apply time window multiplier
        LocalTime now = LocalTime.now(ET_ZONE);
        double timeMultiplier = getTimeWindowMultiplier(now);
        confidence *= timeMultiplier;

        return Math.min(confidence, 0.95);
    }


    private double calculateORBConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.6; // Good base for ORB

        // Volume confirmation (relaxed)
        if (ta.getVolumeRatio() >= MIN_VOLUME_RATIO_BREAKOUT) {
            confidence += 0.20;
        } else if (ta.getVolumeRatio() >= 1.0) {
            confidence += 0.10;
        }

        // No divergence
        if (!ta.isHasRsiDivergence()) {
            confidence += 0.10;
        }

        // Trend continuation
        if (ta.getStrength() > 0.5) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.90);
    }

    private double calculateReversionConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.6;

        // Extreme RSI
        if ((bullish && ta.getRsi() < 25) || (!bullish && ta.getRsi() > 75)) {
            confidence += 0.20;
        }

        // Extended from VWAP
        if (ta.isExtendedFromVwap()) {
            confidence += 0.15;
        }

        // High volatility favors reversion
        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.95);
    }

    private double calculateVolumeSpikeConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.5;

        // Volume surge level (relaxed thresholds)
        if (ta.getVolumeRatio() > 3.0) {
            confidence += 0.30;
        } else if (ta.getVolumeRatio() > 2.0) {
            confidence += 0.20;
        } else if (ta.getVolumeRatio() > 1.5) {
            confidence += 0.10; // New tier for moderate volume
        }

        // Momentum confirmation
        if (Math.abs(ta.getMomentumStrength()) > 0.5) {
            confidence += 0.20;
        }

        // Fresh move (not extended)
        if (!ta.isExtendedFromVwap()) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.90);
    }

    private double calculateSupportResistanceConfidence(TechnicalAnalysis ta, boolean support) {
        double confidence = 0.6;

        // Volume at level (relaxed)
        if (ta.getVolumeRatio() > 1.2) {
            confidence += 0.20;
        } else if (ta.getVolumeRatio() > 0.8) {
            confidence += 0.10;
        }

        // RSI confirmation
        if ((support && ta.getRsi() > 40 && ta.getRsi() < 60) ||
                (!support && ta.getRsi() > 40 && ta.getRsi() < 60)) {
            confidence += 0.15;
        }

        // Multiple touches (assumed from ta.isVwapAsSupport/Resistance)
        confidence += 0.10;

        return Math.min(confidence, 0.95);
    }

    private double calculateBounceConfidence(TechnicalAnalysis ta, boolean bullish) {
        double confidence = 0.5;

        // Volume surge at VWAP (relaxed)
        if (ta.getVolumeRatio() > 1.3) {
            confidence += 0.25;
        } else if (ta.getVolumeRatio() > 1.0) {
            confidence += 0.15;
        } else if (ta.getVolumeRatio() > 0.8) {
            confidence += 0.10;
        }

        // Neutral RSI (best for bounces)
        if (ta.getRsi() > 45 && ta.getRsi() < 55) {
            confidence += 0.20;
        }

        // Not in strong trend
        if (ta.getStrength() < 0.5) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.85);
    }

    private Signal createSignal(Option option, TechnicalAnalysis ta, String signalType,
                                String strategy, double confidence) {
        if (option == null || ta == null || signalType == null || strategy == null) {
            log.error("Cannot create signal with null parameters");
            return null;
        }

        // Validate confidence range
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
            signal.setMarketRegime(ta.getMarketRegime()); // Add market regime

            // Rest of method with better null safety...
            BigDecimal atr = ta.getAverageTrueRange();
            if (atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) {
                atr = ta.getCurrentPrice().multiply(BigDecimal.valueOf(0.005)); // 0.5% fallback
            }

            BigDecimal optionPrice = option.getMidPrice();
            if (optionPrice == null || optionPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Invalid option price for {}: {}", option.getSymbol(), optionPrice);
                return null;
            }

            signal.setEntryPrice(optionPrice);

            // Calculate targets with validation
            BigDecimal targetPrice = calculateTargetPrice(optionPrice, atr, strategy, ta);
            BigDecimal stopLoss = calculateStopLoss(optionPrice, atr, strategy, ta);

            signal.setTargetPrice(targetPrice);
            signal.setStopLoss(stopLoss);

            return signal;

        } catch (Exception e) {
            log.error("Error creating signal: {}", e.getMessage(), e);
            return null;
        }
    }

    private BigDecimal calculateTargetPrice(BigDecimal optionPrice, BigDecimal atr,
                                            String strategy, TechnicalAnalysis ta) {
        // Implementation with null safety and validation
        BigDecimal baseMultiplier = BigDecimal.valueOf(0.5);

        if (strategy.contains("BREAKOUT")) {
            baseMultiplier = BigDecimal.valueOf(0.7);
        } else if (strategy.contains("REVERSION")) {
            baseMultiplier = BigDecimal.valueOf(0.4);
        } else if (strategy.contains("SPIKE")) {
            baseMultiplier = BigDecimal.valueOf(0.3);
        }

        BigDecimal targetMove = atr.multiply(baseMultiplier);
        return optionPrice.add(targetMove);
    }

    private BigDecimal calculateStopLoss(BigDecimal optionPrice, BigDecimal atr,
                                         String strategy, TechnicalAnalysis ta) {
        BigDecimal stopMultiplier = BigDecimal.valueOf(0.3);
        BigDecimal stopMove = atr.multiply(stopMultiplier);
        return optionPrice.subtract(stopMove).max(BigDecimal.valueOf(0.01));
    }

    private void analyzeUnusualFlow(Option option, TechnicalAnalysis ta,
                                    List<Signal> signals, String analysisId) {
        if (!detectUnusualOptionsFlow(option, analysisId)) {
            return;
        }

        // Create signal for unusual flow
        double confidence = 0.85; // High base confidence for unusual flow

        // Boost for extreme volume
        BigDecimal premium = option.getMidPrice()
                .multiply(BigDecimal.valueOf(option.getVolume()))
                .multiply(BigDecimal.valueOf(100));

        if (premium.compareTo(BigDecimal.valueOf(100000)) > 0) { // $100k+
            confidence = 0.90;
        }


        Signal signal = createSignal(option, ta, "BUY", "UNUSUAL_FLOW", confidence);
        signal.setSignalType(option.getType());  // Direction in signalType
        signal.setReason(String.format(
                "🎯 Unusual flow detected - %s %s Vol: %d Premium: $%.0f (%.1fx OI)",
                option.getType(), option.getStrikePrice(),
                option.getVolume(), premium.doubleValue(),
                (double)option.getVolume() / option.getOpenInterest()
        ));

        signals.add(signal);
        log.info("[v63][{}] ✅ Created {} signal - Confidence: {}%",
                analysisId, "UNUSUAL_FLOW", (int)(confidence * 100));
    }
}