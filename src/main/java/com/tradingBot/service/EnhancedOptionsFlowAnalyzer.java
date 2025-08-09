package com.tradingBot.service;

import com.tradingBot.model.Option;
import com.tradingBot.model.OptionGreeks;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnhancedOptionsFlowAnalyzer {

    private final TradierService tradierService;
    private final TelegramService telegramService;

    // Cache for order book depth
    private final Map<String, OrderBookSnapshot> orderBookCache = new ConcurrentHashMap<>();
    private final Map<String, List<FlowEvent>> recentFlows = new ConcurrentHashMap<>();

    // Spoofing detection
    private final Map<String, List<OrderActivity>> orderActivityHistory = new ConcurrentHashMap<>();

    @Data
    public static class FlowAnalysis {
        private String optionSymbol;
        private String flowType; // SWEEP, BLOCK, SPLIT, REGULAR
        private BigDecimal premium;
        private int contracts;
        private BigDecimal delta;
        private BigDecimal gamma;
        private Double vega;
        private BigDecimal impliedVolatility;
        private boolean isSweep;
        private boolean isBlock;
        private boolean isSplit;
        private boolean likelySpoof;
        private double aggressiveness; // 0-1 scale
        private double smartMoneyScore; // 0-1 scale
        private String executionType; // AT_ASK, AT_BID, MIDPOINT
        private List<String> alerts;
        private LocalDateTime timestamp;
    }

    public FlowAnalysis analyzeOptionsFlow(Option option, List<Option> chainOptions) {
        FlowAnalysis analysis = new FlowAnalysis();
        analysis.setOptionSymbol(option.getSymbol());
        analysis.setTimestamp(LocalDateTime.now());
        analysis.setAlerts(new ArrayList<>());

        try {
            // Basic flow metrics
            analysis.setContracts(option.getVolume());
            analysis.setPremium(calculatePremium(option));

            // Greeks analysis
            if (option.getGreeks() != null) {
                OptionGreeks greeks = option.getGreeks();
                analysis.setDelta(greeks.getDelta());
                analysis.setGamma(greeks.getGamma());
                analysis.setVega(greeks.getVega());
                analysis.setImpliedVolatility(BigDecimal.valueOf(option.getImpliedVolatility()));
            }

            // Detect flow type
            detectFlowType(option, analysis);

            // Spoofing detection
            analysis.setLikelySpoof(detectSpoofing(option));

            // Execution analysis
            analyzeExecution(option, analysis);

            // Smart money scoring
            analysis.setSmartMoneyScore(calculateSmartMoneyScore(option, analysis, chainOptions));

            // Aggressiveness scoring
            analysis.setAggressiveness(calculateAggressiveness(option, analysis));

            // Generate alerts
            generateFlowAlerts(analysis);

            // Store flow event
            storeFlowEvent(analysis);

        } catch (Exception e) {
            log.error("Error analyzing options flow: {}", e.getMessage());
        }

        return analysis;
    }

    private void detectFlowType(Option option, FlowAnalysis analysis) {
        int volume = option.getVolume();
        int openInterest = option.getOpenInterest();
        BigDecimal bid = option.getBid();
        BigDecimal ask = option.getAsk();
        BigDecimal last = option.getLast();

        // Sweep detection - rapid execution across multiple exchanges
        if (isSweepOrder(option)) {
            analysis.setFlowType("SWEEP");
            analysis.setSweep(true);
            analysis.getAlerts().add("SWEEP detected - aggressive buying");
        }
        // Block detection - large single order
        else if (volume >= 500 && volume > openInterest * 0.25) {
            analysis.setFlowType("BLOCK");
            analysis.setBlock(true);
            analysis.getAlerts().add("BLOCK trade - institutional size");
        }
        // Split detection - order split across multiple fills
        else if (isSplitOrder(option)) {
            analysis.setFlowType("SPLIT");
            analysis.setSplit(true);
        }
        // Regular flow
        else {
            analysis.setFlowType("REGULAR");
        }
    }

    private boolean isSweepOrder(Option option) {
        // Check recent order activity for sweep patterns
        String symbol = option.getSymbol();
        List<OrderActivity> recentActivity = orderActivityHistory.get(symbol);

        if (recentActivity == null || recentActivity.size() < 3) {
            return false;
        }

        // Look for rapid sequential fills at or above ask
        long rapidFills = recentActivity.stream()
                .filter(a -> a.getTimestamp().isAfter(LocalDateTime.now().minusSeconds(5)))
                .filter(a -> a.getPrice().compareTo(option.getAsk()) >= 0)
                .count();

        return rapidFills >= 3;
    }

    private boolean isSplitOrder(Option option) {
        // Detect if order was split across multiple fills
        int volume = option.getVolume();

        // Common split sizes
        int[] commonSplits = {100, 200, 500, 1000};
        for (int split : commonSplits) {
            if (volume % split == 0 && volume / split >= 2) {
                return true;
            }
        }

        return false;
    }

    private boolean detectSpoofing(Option option) {
        String symbol = option.getSymbol();
        List<OrderActivity> history = orderActivityHistory.get(symbol);

        if (history == null || history.size() < 10) {
            return false;
        }

        // Look for pattern: large orders placed and quickly cancelled
        long cancelledOrders = history.stream()
                .filter(a -> a.getTimestamp().isAfter(LocalDateTime.now().minusMinutes(5)))
                .filter(OrderActivity::isCancelled)
                .count();

        long totalOrders = history.stream()
                .filter(a -> a.getTimestamp().isAfter(LocalDateTime.now().minusMinutes(5)))
                .count();

        // If >50% of recent orders were cancelled, possible spoofing
        return totalOrders > 0 && (double) cancelledOrders / totalOrders > 0.5;
    }

    private void analyzeExecution(Option option, FlowAnalysis analysis) {
        BigDecimal bid = option.getBid();
        BigDecimal ask = option.getAsk();
        BigDecimal last = option.getLast();
        BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        BigDecimal askDistance = last.subtract(ask).abs();
        BigDecimal bidDistance = last.subtract(bid).abs();
        BigDecimal midDistance = last.subtract(mid).abs();

        if (askDistance.compareTo(bidDistance) < 0 && askDistance.compareTo(midDistance) < 0) {
            analysis.setExecutionType("AT_ASK");
            analysis.getAlerts().add("Executed at ASK - aggressive buying");
        } else if (bidDistance.compareTo(askDistance) < 0 && bidDistance.compareTo(midDistance) < 0) {
            analysis.setExecutionType("AT_BID");
            analysis.getAlerts().add("Executed at BID - passive/selling");
        } else {
            analysis.setExecutionType("MIDPOINT");
        }
    }

    private double calculateSmartMoneyScore(Option option, FlowAnalysis analysis, List<Option> chain) {
        double score = 0.0;

        // Factor 1: Size relative to open interest (25%)
        if (option.getOpenInterest() > 0) {
            double sizeRatio = (double) option.getVolume() / option.getOpenInterest();
            if (sizeRatio > 0.5) score += 0.25;
            else if (sizeRatio > 0.25) score += 0.15;
        }

        // Factor 2: Premium size (25%)
        if (analysis.getPremium().compareTo(BigDecimal.valueOf(100000)) > 0) {
            score += 0.25;
        } else if (analysis.getPremium().compareTo(BigDecimal.valueOf(50000)) > 0) {
            score += 0.15;
        }

        // Factor 3: Execution quality (20%)
        if ("AT_ASK".equals(analysis.getExecutionType()) && !analysis.isLikelySpoof()) {
            score += 0.20;
        } else if ("MIDPOINT".equals(analysis.getExecutionType())) {
            score += 0.10;
        }

        // Factor 4: Greek positioning (20%)
        if (analysis.getDelta() != null && analysis.getGamma() != null) {
            // High delta with high gamma = aggressive directional bet
            if (analysis.getDelta().abs().compareTo(BigDecimal.valueOf(0.7)) > 0 &&
                    analysis.getGamma().compareTo(BigDecimal.valueOf(0.05)) > 0) {
                score += 0.20;
            }
        }

        // Factor 5: Unusual activity (10%)
        if (isUnusualActivity(option, chain)) {
            score += 0.10;
        }

        return Math.min(score, 1.0);
    }

    private double calculateAggressiveness(Option option, FlowAnalysis analysis) {
        double aggressiveness = 0.0;

        // Sweep orders are most aggressive
        if (analysis.isSweep()) {
            aggressiveness = 0.9;
        }
        // At ask execution
        else if ("AT_ASK".equals(analysis.getExecutionType())) {
            aggressiveness = 0.7;
        }
        // Large blocks
        else if (analysis.isBlock()) {
            aggressiveness = 0.6;
        }
        // Midpoint execution
        else if ("MIDPOINT".equals(analysis.getExecutionType())) {
            aggressiveness = 0.4;
        }
        // At bid execution
        else {
            aggressiveness = 0.2;
        }

        // Adjust for size
        if (option.getVolume() > 1000) {
            aggressiveness = Math.min(aggressiveness * 1.2, 1.0);
        }

        return aggressiveness;
    }

    private boolean isUnusualActivity(Option option, List<Option> chain) {
        // Compare to average volume in the chain
        double avgVolume = chain.stream()
                .mapToInt(Option::getVolume)
                .average()
                .orElse(0);

        return option.getVolume() > avgVolume * 3;
    }

    private BigDecimal calculatePremium(Option option) {
        return option.getMidPrice()
                .multiply(BigDecimal.valueOf(option.getVolume()))
                .multiply(BigDecimal.valueOf(100));
    }

    private void generateFlowAlerts(FlowAnalysis analysis) {
        // High smart money score
        if (analysis.getSmartMoneyScore() > 0.8) {
            analysis.getAlerts().add("🎯 HIGH SMART MONEY SCORE");
        }

        // Large premium
        if (analysis.getPremium().compareTo(BigDecimal.valueOf(500000)) > 0) {
            analysis.getAlerts().add("💰 WHALE ALERT - Premium > $500K");
        }

        // High gamma exposure
        if (analysis.getGamma() != null &&
                analysis.getGamma().multiply(BigDecimal.valueOf(analysis.getContracts() * 100))
                        .compareTo(BigDecimal.valueOf(1000)) > 0) {
            analysis.getAlerts().add("🔥 HIGH GAMMA EXPOSURE");
        }

        // Aggressive sweep
        if (analysis.isSweep() && analysis.getAggressiveness() > 0.8) {
            analysis.getAlerts().add("⚡ AGGRESSIVE SWEEP DETECTED");
        }
    }

    private void storeFlowEvent(FlowAnalysis analysis) {
        FlowEvent event = new FlowEvent();
        event.setTimestamp(analysis.getTimestamp());
        event.setSymbol(analysis.getOptionSymbol());
        event.setFlowType(analysis.getFlowType());
        event.setPremium(analysis.getPremium());
        event.setSmartMoneyScore(analysis.getSmartMoneyScore());

        recentFlows.computeIfAbsent(analysis.getOptionSymbol(), k -> new ArrayList<>())
                .add(event);

        // Keep only last 100 flows per symbol
        List<FlowEvent> flows = recentFlows.get(analysis.getOptionSymbol());
        if (flows.size() > 100) {
            flows.remove(0);
        }
    }

    // Real-time gamma and vega estimates
    public GammaVegaExposure calculateRealTimeExposure(String underlying) {
        GammaVegaExposure exposure = new GammaVegaExposure();

        try {
            // Get all options for the underlying
            List<Option> options = tradierService.getOptionChain(underlying, LocalDateTime.now().toLocalDate())
                    .getOptionsList();

            BigDecimal totalGamma = BigDecimal.ZERO;
            BigDecimal totalVega = BigDecimal.ZERO;
            BigDecimal netDelta = BigDecimal.ZERO;

            for (Option option : options) {
                if (option.getGreeks() != null && option.getOpenInterest() > 0) {
                    OptionGreeks greeks = option.getGreeks();
                    int multiplier = option.getOpenInterest() * 100;

                    // Net dealer gamma (negative for sold options)
                    BigDecimal optionGamma = greeks.getGamma()
                            .multiply(BigDecimal.valueOf(multiplier))
                            .negate(); // Dealer is short

                    totalGamma = totalGamma.add(optionGamma);

                    // Vega exposure
                    BigDecimal optionVega = BigDecimal.valueOf(greeks.getVega())
                            .multiply(BigDecimal.valueOf(multiplier))
                            .negate();

                    totalVega = totalVega.add(optionVega);

                    // Delta exposure
                    BigDecimal optionDelta = greeks.getDelta()
                            .multiply(BigDecimal.valueOf(multiplier))
                            .negate();

                    netDelta = netDelta.add(optionDelta);
                }
            }

            exposure.setTotalGamma(totalGamma);
            exposure.setTotalVega(totalVega);
            exposure.setNetDelta(netDelta);
            exposure.setTimestamp(LocalDateTime.now());

            // Calculate key levels
            exposure.setGammaFlipLevel(calculateGammaFlipLevel(options));
            exposure.setMaxPainLevel(calculateMaxPain(options));

        } catch (Exception e) {
            log.error("Error calculating gamma/vega exposure: {}", e.getMessage());
        }

        return exposure;
    }

    private BigDecimal calculateGammaFlipLevel(List<Option> options) {
        // Find strike where gamma exposure flips from negative to positive
        Map<BigDecimal, BigDecimal> strikeGamma = new TreeMap<>();

        for (Option option : options) {
            if (option.getGreeks() != null && option.getOpenInterest() > 0) {
                BigDecimal strike = option.getStrikePrice();
                BigDecimal gamma = option.getGreeks().getGamma()
                        .multiply(BigDecimal.valueOf(option.getOpenInterest() * 100))
                        .negate();

                strikeGamma.merge(strike, gamma, BigDecimal::add);
            }
        }

        // Find flip point
        BigDecimal previousStrike = null;
        BigDecimal previousGamma = null;

        for (Map.Entry<BigDecimal, BigDecimal> entry : strikeGamma.entrySet()) {
            if (previousGamma != null &&
                    previousGamma.compareTo(BigDecimal.ZERO) < 0 &&
                    entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                // Found flip point
                return entry.getKey();
            }
            previousStrike = entry.getKey();
            previousGamma = entry.getValue();
        }

        return null;
    }

    private BigDecimal calculateMaxPain(List<Option> options) {
        // Calculate max pain - strike where most options expire worthless
        Map<BigDecimal, BigDecimal> strikePain = new HashMap<>();

        Set<BigDecimal> strikes = options.stream()
                .map(Option::getStrikePrice)
                .collect(Collectors.toSet());

        for (BigDecimal strike : strikes) {
            BigDecimal totalPain = BigDecimal.ZERO;

            for (Option option : options) {
                if (option.getOpenInterest() > 0) {
                    BigDecimal optionStrike = option.getStrikePrice();
                    BigDecimal oi = BigDecimal.valueOf(option.getOpenInterest() * 100);

                    if ("CALL".equals(option.getType())) {
                        // Calls: pain = max(0, strike - optionStrike) * OI
                        BigDecimal intrinsic = strike.subtract(optionStrike).max(BigDecimal.ZERO);
                        totalPain = totalPain.add(intrinsic.multiply(oi));
                    } else {
                        // Puts: pain = max(0, optionStrike - strike) * OI
                        BigDecimal intrinsic = optionStrike.subtract(strike).max(BigDecimal.ZERO);
                        totalPain = totalPain.add(intrinsic.multiply(oi));
                    }
                }
            }

            strikePain.put(strike, totalPain);
        }

        // Find minimum pain level
        return strikePain.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // Data classes
    @Data
    private static class OrderBookSnapshot {
        private LocalDateTime timestamp;
        private Map<BigDecimal, Integer> bidLevels;
        private Map<BigDecimal, Integer> askLevels;
        private BigDecimal topBid;
        private BigDecimal topAsk;
        private int bidDepth;
        private int askDepth;
    }

    @Data
    private static class FlowEvent {
        private LocalDateTime timestamp;
        private String symbol;
        private String flowType;
        private BigDecimal premium;
        private double smartMoneyScore;
    }

    @Data
    private static class OrderActivity {
        private LocalDateTime timestamp;
        private BigDecimal price;
        private int size;
        private boolean cancelled;
        private String side; // BID, ASK
    }

    @Data
    public static class GammaVegaExposure {
        private BigDecimal totalGamma;
        private BigDecimal totalVega;
        private BigDecimal netDelta;
        private BigDecimal gammaFlipLevel;
        private BigDecimal maxPainLevel;
        private LocalDateTime timestamp;
    }
}