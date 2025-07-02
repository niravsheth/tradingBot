package com.tradingBot.analytics;

import com.tradingBot.model.*;
import com.tradingBot.service.TradierService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class AdvancedUnusualFlowDetector {

    private final TradierService tradierService;

    // Enhanced thresholds
    private static final double MIN_SMART_MONEY_PREMIUM = 100000; // $100K minimum
    private static final double MIN_VOLUME_TO_OI_RATIO = 3.0; // Volume must be 3x open interest
    private static final int MIN_ABSOLUTE_VOLUME = 1000; // Minimum 1000 contracts
    private static final double MAX_HEDGING_PROBABILITY = 0.3; // Max 30% probability of being hedging

    // Strike distance thresholds (from ATM)
    private static final double MAX_HEDGING_STRIKE_DISTANCE = 0.05; // 5% from ATM for hedging
    private static final double MIN_DIRECTIONAL_STRIKE_DISTANCE = 0.01; // 1% minimum for directional

    @Data
    public static class FlowAnalysis {
        private boolean isUnusualFlow;
        private double hedgingProbability;
        private String flowType; // DIRECTIONAL, HEDGING, SPREAD, PROTECTIVE
        private double aggressiveness; // 0-1 scale
        private String reasoning;
        private double confidence;
        private boolean isSmartMoney;
    }

    public FlowAnalysis analyzeFlow(Option option, String underlyingSymbol,
                                    List<Option> allOptions, String analysisId) {
        FlowAnalysis analysis = new FlowAnalysis();

        try {
            // 1. Basic volume and premium checks
            if (!passesBasicThresholds(option)) {
                analysis.setUnusualFlow(false);
                analysis.setReasoning("Failed basic volume/premium thresholds");
                return analysis;
            }

            // 2. Get underlying quote for context
            QuoteResponse underlyingQuote = tradierService.getQuote(underlyingSymbol);
            if (underlyingQuote == null || underlyingQuote.getQuote() == null) {
                analysis.setUnusualFlow(false);
                analysis.setReasoning("Cannot get underlying quote for analysis");
                return analysis;
            }

            BigDecimal underlyingPrice = underlyingQuote.getQuote().getLast();

            // 3. Calculate hedging probability
            double hedgingProb = calculateHedgingProbability(option, underlyingPrice,
                    allOptions, analysisId);
            analysis.setHedgingProbability(hedgingProb);

            // 4. Analyze flow characteristics
            analysis.setFlowType(classifyFlowType(option, underlyingPrice, allOptions));
            analysis.setAggressiveness(calculateAggressiveness(option));

            // 5. Apply hedging filters
            if (hedgingProb > MAX_HEDGING_PROBABILITY) {
                analysis.setUnusualFlow(false);
                analysis.setReasoning(String.format(
                        "High hedging probability: %.1f%% - Likely %s",
                        hedgingProb * 100, analysis.getFlowType()));
                return analysis;
            }

            // 6. Check for institutional hedging patterns
            if (isInstitutionalHedging(option, underlyingPrice, allOptions)) {
                analysis.setUnusualFlow(false);
                analysis.setReasoning("Institutional hedging pattern detected");
                return analysis;
            }

            // 7. Time-based hedging filters
            if (isTimeBasedHedging()) {
                analysis.setUnusualFlow(false);
                analysis.setReasoning("Time-based hedging window (pre-close/earnings)");
                return analysis;
            }

            // 8. Market regime hedging check
            if (isMarketRegimeHedging(underlyingQuote.getQuote())) {
                analysis.setUnusualFlow(false);
                analysis.setReasoning("Market stress hedging detected");
                return analysis;
            }

            // 9. Check for spread hedging
            if (isSpreadHedging(option, allOptions)) {
                analysis.setUnusualFlow(false);
                analysis.setReasoning("Part of spread hedging strategy");
                return analysis;
            }

            // 10. Final confidence calculation
            double confidence = calculateDirectionalConfidence(option, underlyingPrice,
                    analysis.getAggressiveness());
            analysis.setConfidence(confidence);

            // 11. Determine if this is smart money
            analysis.setSmartMoney(isSmartMoneyFlow(option, confidence, analysis.getAggressiveness()));

            // 12. Final decision
            boolean isUnusual = confidence > 0.7 && analysis.getAggressiveness() > 0.6;
            analysis.setUnusualFlow(isUnusual);

            if (isUnusual) {
                analysis.setReasoning(String.format(
                        "Directional flow: %.0f%% confidence, %.0f%% aggressive, %s",
                        confidence * 100, analysis.getAggressiveness() * 100,
                        analysis.isSmartMoney() ? "Smart Money" : "Retail"));
            }

            log.info("[FLOW][{}] {} {} - Hedging: {:.1f}%, Type: {}, Aggressive: {:.1f}%, Confidence: {:.1f}%",
                    analysisId, option.getType(), option.getStrikePrice(),
                    hedgingProb * 100, analysis.getFlowType(),
                    analysis.getAggressiveness() * 100, confidence * 100);

            return analysis;

        } catch (Exception e) {
            log.error("[FLOW][{}] Error analyzing flow: {}", analysisId, e.getMessage());
            analysis.setUnusualFlow(false);
            analysis.setReasoning("Analysis error: " + e.getMessage());
            return analysis;
        }
    }

    private boolean passesBasicThresholds(Option option) {
        BigDecimal premium = option.getMidPrice()
                .multiply(BigDecimal.valueOf(option.getVolume()))
                .multiply(BigDecimal.valueOf(100));

        return option.getVolume() >= MIN_ABSOLUTE_VOLUME &&
                premium.compareTo(BigDecimal.valueOf(MIN_SMART_MONEY_PREMIUM)) >= 0 &&
                option.getOpenInterest() > 0 &&
                option.getVolume() >= option.getOpenInterest() * MIN_VOLUME_TO_OI_RATIO;
    }

    private double calculateHedgingProbability(Option option, BigDecimal underlyingPrice,
                                               List<Option> allOptions, String analysisId) {
        double hedgingScore = 0.0;

        // 1. Strike distance from ATM
        BigDecimal strikeDistance = option.getStrikePrice().subtract(underlyingPrice)
                .abs().divide(underlyingPrice, 4, RoundingMode.HALF_UP);

        if (strikeDistance.compareTo(BigDecimal.valueOf(MAX_HEDGING_STRIKE_DISTANCE)) <= 0) {
            hedgingScore += 0.3; // ATM/near-ATM options often used for hedging
        }

        // 2. Put/Call ratio analysis
        if ("PUT".equalsIgnoreCase(option.getType())) {
            // OTM puts are often portfolio protection
            if (option.getStrikePrice().compareTo(underlyingPrice) < 0) {
                hedgingScore += 0.4;
            }
            // ATM puts during market stress
            if (strikeDistance.compareTo(BigDecimal.valueOf(0.02)) <= 0) {
                hedgingScore += 0.2;
            }
        }

        // 3. Time to expiration (0DTE puts often hedging)
        LocalTime now = LocalTime.now();
        if (now.isAfter(LocalTime.of(14, 0)) && "PUT".equalsIgnoreCase(option.getType())) {
            hedgingScore += 0.3; // End-of-day put protection
        }

        // 4. Volume pattern analysis
        double volToOIRatio = (double) option.getVolume() / option.getOpenInterest();
        if (volToOIRatio > 10) {
            hedgingScore -= 0.2; // Very high volume suggests directional bet
        } else if (volToOIRatio < 5) {
            hedgingScore += 0.2; // Lower volume might be systematic hedging
        }

        // 5. Bid/Ask pressure
        if (option.getBid() != null && option.getAsk() != null) {
            BigDecimal spread = option.getAsk().subtract(option.getBid());
            BigDecimal midPrice = option.getMidPrice();
            double spreadPercentage = spread.divide(midPrice, 4, RoundingMode.HALF_UP).doubleValue();

            if (spreadPercentage < 0.05) {
                hedgingScore -= 0.1; // Tight spreads suggest aggressive buying
            }
        }

        return Math.max(0.0, Math.min(1.0, hedgingScore));
    }

    private String classifyFlowType(Option option, BigDecimal underlyingPrice, List<Option> allOptions) {
        BigDecimal strikeDistance = option.getStrikePrice().subtract(underlyingPrice)
                .abs().divide(underlyingPrice, 4, RoundingMode.HALF_UP);

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isOTM = (isPut && option.getStrikePrice().compareTo(underlyingPrice) < 0) ||
                (!isPut && option.getStrikePrice().compareTo(underlyingPrice) > 0);

        // Check for protective patterns
        if (isPut && isOTM && strikeDistance.compareTo(BigDecimal.valueOf(0.1)) <= 0) {
            return "PROTECTIVE"; // Protective puts
        }

        // Check for spread activity
        if (hasSpreadActivity(option, allOptions)) {
            return "SPREAD";
        }

        // Check strike characteristics
        if (strikeDistance.compareTo(BigDecimal.valueOf(0.02)) <= 0) {
            return "HEDGING"; // Near ATM, likely hedging
        }

        return "DIRECTIONAL";
    }

    private boolean hasSpreadActivity(Option option, List<Option> allOptions) {
        // Look for corresponding legs that might indicate spread activity
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        String oppositeType = isCall ? "PUT" : "CALL";

        // Check for straddle/strangle activity (same expiration, different strikes)
        long oppositeTypeVolume = allOptions.stream()
                .filter(opt -> oppositeType.equalsIgnoreCase(opt.getType()))
                .filter(opt -> opt.getVolume() != null && opt.getVolume() > 500)
                .mapToLong(opt -> opt.getVolume())
                .sum();

        // If significant opposite type volume, might be spread
        return oppositeTypeVolume > option.getVolume() * 0.5;
    }

    private double calculateAggressiveness(Option option) {
        double aggressiveness = 0.5; // Base score

        // 1. Volume to Open Interest ratio
        double volToOI = (double) option.getVolume() / Math.max(option.getOpenInterest(), 1);
        if (volToOI > 10) {
            aggressiveness += 0.3;
        } else if (volToOI > 5) {
            aggressiveness += 0.2;
        }

        // 2. Premium size
        BigDecimal premium = option.getMidPrice()
                .multiply(BigDecimal.valueOf(option.getVolume()))
                .multiply(BigDecimal.valueOf(100));

        if (premium.compareTo(BigDecimal.valueOf(1000000)) > 0) { // >$1M
            aggressiveness += 0.2;
        } else if (premium.compareTo(BigDecimal.valueOf(500000)) > 0) { // >$500K
            aggressiveness += 0.1;
        }

        // 3. Time concentration (if volume came in quickly)
        // This would require tick data, so we'll estimate based on volume
        if (option.getVolume() > 5000) {
            aggressiveness += 0.1; // High volume suggests concentrated buying
        }

        return Math.max(0.0, Math.min(1.0, aggressiveness));
    }

    private boolean isInstitutionalHedging(Option option, BigDecimal underlyingPrice,
                                           List<Option> allOptions) {
        // Pattern 1: Large ATM put buying (portfolio protection)
        if ("PUT".equalsIgnoreCase(option.getType())) {
            BigDecimal strikeDistance = option.getStrikePrice().subtract(underlyingPrice)
                    .abs().divide(underlyingPrice, 4, RoundingMode.HALF_UP);

            if (strikeDistance.compareTo(BigDecimal.valueOf(0.05)) <= 0 && // Within 5% of ATM
                    option.getVolume() > 2000) { // Large size
                return true;
            }
        }

        // Pattern 2: Systematic delta hedging (large call buying with puts)
        if ("CALL".equalsIgnoreCase(option.getType()) && option.getVolume() > 5000) {
            // Check for corresponding put volume
            long putVolume = allOptions.stream()
                    .filter(opt -> "PUT".equalsIgnoreCase(opt.getType()))
                    .filter(opt -> opt.getVolume() != null && opt.getVolume() > 1000)
                    .mapToLong(opt -> opt.getVolume())
                    .sum();

            if (putVolume > option.getVolume() * 0.7) { // Significant put volume
                return true;
            }
        }

        // Pattern 3: End-of-day portfolio adjustments
        LocalTime now = LocalTime.now();
        if (now.isAfter(LocalTime.of(15, 30)) && option.getVolume() > 3000) {
            return true; // Large end-of-day flows often hedging
        }

        return false;
    }

    private boolean isTimeBasedHedging() {
        LocalTime now = LocalTime.now();

        // Pre-market close hedging (3:45 PM - 4:00 PM)
        if (now.isAfter(LocalTime.of(15, 45))) {
            return true;
        }

        // Check for earnings season (this would need external data)
        // For now, assume certain days of week have more hedging
        int dayOfWeek = LocalDate.now().getDayOfWeek().getValue();
        if (dayOfWeek == 5) { // Friday - weekly hedging
            return now.isAfter(LocalTime.of(15, 0));
        }

        return false;
    }

    private boolean isMarketRegimeHedging(Quote underlyingQuote) {
        // 1. High volatility day
        if (underlyingQuote.getHigh() != null && underlyingQuote.getLow() != null) {
            BigDecimal dayRange = underlyingQuote.getHigh().subtract(underlyingQuote.getLow());
            BigDecimal rangePercent = dayRange.divide(underlyingQuote.getLast(), 4, RoundingMode.HALF_UP);

            if (rangePercent.compareTo(BigDecimal.valueOf(0.02)) > 0) { // >2% daily range
                return true; // High volatility encourages hedging
            }
        }

        // 2. Large gap from previous close
        if (underlyingQuote.getPreviousClose() != null) {
            BigDecimal gap = underlyingQuote.getLast().subtract(underlyingQuote.getPreviousClose())
                    .abs().divide(underlyingQuote.getPreviousClose(), 4, RoundingMode.HALF_UP);

            if (gap.compareTo(BigDecimal.valueOf(0.015)) > 0) { // >1.5% gap
                return true; // Large gaps trigger hedging
            }
        }

        return false;
    }

    private boolean isSpreadHedging(Option option, List<Option> allOptions) {
        // Look for iron condor, butterfly, or other spread patterns
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());

        // Check for multiple strikes with similar volume
        long similarVolumeCount = allOptions.stream()
                .filter(opt -> opt.getType().equals(option.getType()))
                .filter(opt -> !opt.getStrikePrice().equals(option.getStrikePrice()))
                .filter(opt -> opt.getVolume() != null &&
                        Math.abs(opt.getVolume() - option.getVolume()) < option.getVolume() * 0.3)
                .count();

        return similarVolumeCount >= 2; // Multiple similar volume strikes suggest spreads
    }

    private double calculateDirectionalConfidence(Option option, BigDecimal underlyingPrice,
                                                  double aggressiveness) {
        double confidence = 0.5;

        // 1. Strike selection confidence
        BigDecimal strikeDistance = option.getStrikePrice().subtract(underlyingPrice)
                .abs().divide(underlyingPrice, 4, RoundingMode.HALF_UP);

        if (strikeDistance.compareTo(BigDecimal.valueOf(0.02)) > 0 &&
                strikeDistance.compareTo(BigDecimal.valueOf(0.1)) <= 0) {
            confidence += 0.2; // OTM but reasonable strikes suggest directional bet
        }

        // 2. Volume characteristics
        double volToOI = (double) option.getVolume() / Math.max(option.getOpenInterest(), 1);
        if (volToOI > 5) {
            confidence += 0.2;
        }

        // 3. Premium size (large premiums suggest conviction)
        BigDecimal premium = option.getMidPrice()
                .multiply(BigDecimal.valueOf(option.getVolume()))
                .multiply(BigDecimal.valueOf(100));

        if (premium.compareTo(BigDecimal.valueOf(500000)) > 0) {
            confidence += 0.2;
        }

        // 4. Aggressiveness boost
        confidence += aggressiveness * 0.3;

        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private boolean isSmartMoneyFlow(Option option, double confidence, double aggressiveness) {
        BigDecimal premium = option.getMidPrice()
                .multiply(BigDecimal.valueOf(option.getVolume()))
                .multiply(BigDecimal.valueOf(100));

        return premium.compareTo(BigDecimal.valueOf(1000000)) > 0 && // >$1M premium
                confidence > 0.8 &&
                aggressiveness > 0.7;
    }
}