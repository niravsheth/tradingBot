package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.Quote;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class UnifiedTrendDetector {

    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;

    // TREND THRESHOLDS - MADE ULTRA SENSITIVE
    private static final double ULTRA_SENSITIVE_THRESHOLD = 0.0005; // 0.05%
    private static final double QQQ_DIRECT_THRESHOLD = 0.0008; // 0.08%
    private static final double NVDA_WEIGHT = 0.60;
    private static final double MSFT_WEIGHT = 0.40;
    private static final double HIGH_CORRELATION_THRESHOLD = 0.75;
    private static final double LOW_CORRELATION_THRESHOLD = 0.25;

    // NEW: Enhanced thresholds for improved logic
    private static final double CORRELATION_THRESHOLD = 0.3;
    private static final double HIGH_CORRELATION_THRESHOLD_NEW = 0.6;
    private static final double STRONG_SIGNAL_THRESHOLD = 0.005; // 0.5%
    private static final double LEADER_WEIGHT = 0.7; // Weight for market leader
    private static final double OTHERS_WEIGHT = 0.3; // Weight for others

    // REACTIVE TREND STORAGE
    private final Map<String, ReactiveSignals> lastReactiveSignals = new ConcurrentHashMap<>();

    /**
     * MAIN ENTRY POINT: Now uses REACTIVE trend detection for faster, more accurate signals
     */
    public String detectTrend(String symbol) {
        try {
            // Try ultra-sensitive reactive trend first
            String reactiveTrend = detectReactiveTrendSimplified(symbol);
            if (!"NEUTRAL".equals(reactiveTrend)) {
                //log.info("MAIN DETECTOR: Using reactive trend: {}", reactiveTrend);
                return reactiveTrend;
            }

            //log.info("MAIN DETECTOR: Reactive neutral, staying neutral");
            return "NEUTRAL";

        } catch (Exception e) {
            log.error("MAIN DETECTOR: Error: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    /**
     * IMPROVED REACTIVE TREND: Enhanced logic with leadership weighting and correlation awareness
     * Fixes the issue where NVDA positive momentum gets overwhelmed by MSFT negative momentum
     */
    public String detectReactiveTrendSimplified(String symbol) {
        long startTime = System.nanoTime();

        try {
            // Step 1: Get direct QQQ price movement first
            String qqqDirect = getDirectQQQTrend(symbol);
            if (!"NEUTRAL".equals(qqqDirect)) {
                //log.info("REACTIVE SIMPLE: Direct QQQ trend detected: {}", qqqDirect);
                return qqqDirect;
            }

            // Step 2: Get NVDA + MSFT signals with enhanced analysis
            ReactiveSignals signals = getSimpleTechSignals();
            if (signals == null) {
                return "NEUTRAL";
            }

            // Step 3: Apply IMPROVED signal combination logic
            String trend = determineEnhancedTrend(signals);

            long latency = (System.nanoTime() - startTime) / 1_000_000;
            //log.info("REACTIVE SIMPLE: {} (latency:{}ms)", trend, latency);
            return trend;

        } catch (Exception e) {
            log.error("REACTIVE SIMPLE: Failed: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    /**
     * NEW: Enhanced trend determination logic that properly handles market leadership
     */
    private String determineEnhancedTrend(ReactiveSignals signals) {

        //log.info("REACTIVE SIMPLE: NVDA:{}%, MSFT:{}%, Combined:{}, Correlation:{}",
 //               signals.nvdaMomentum * 100, signals.msftMomentum * 100, signals.combinedStrength, signals.correlation);

        // Check if we have strong individual signals that override combination
        if (Math.abs(signals.nvdaMomentum) > STRONG_SIGNAL_THRESHOLD ||
                Math.abs(signals.msftMomentum) > STRONG_SIGNAL_THRESHOLD) {

            return handleStrongSignalScenario(signals);
        }

        // Determine market leader and apply appropriate weighting
        MarketLeader leader = identifyMarketLeader(signals);

        // Apply correlation-aware signal combination
        double finalSignal = applyCorrelationAwareLogic(signals, leader);

        // Make final decision with ultra-sensitive thresholds
        if (finalSignal > ULTRA_SENSITIVE_THRESHOLD) {
            //log.info("REACTIVE SIMPLE: UP signal (final:{} > {})", finalSignal, ULTRA_SENSITIVE_THRESHOLD);
            return "UP";
        } else if (finalSignal < -ULTRA_SENSITIVE_THRESHOLD) {
            //log.info("REACTIVE SIMPLE: DOWN signal (final:{} < {})", finalSignal, -ULTRA_SENSITIVE_THRESHOLD);
            return "DOWN";
        } else {
            //log.info("REACTIVE SIMPLE: NEUTRAL (final:{})", finalSignal);
            return "NEUTRAL";
        }
    }

    /**
     * Handle scenarios where one stock has a strong signal
     */
    private String handleStrongSignalScenario(ReactiveSignals signals) {
        boolean nvdaStrong = Math.abs(signals.nvdaMomentum) > STRONG_SIGNAL_THRESHOLD;
        boolean msftStrong = Math.abs(signals.msftMomentum) > STRONG_SIGNAL_THRESHOLD;

        if (nvdaStrong && !msftStrong) {
            // NVDA has strong signal, MSFT weak - follow NVDA (market leader)
            String direction = signals.nvdaMomentum > 0 ? "UP" : "DOWN";
            //log.info("STRONG SIGNAL: NVDA leading with {}% move, direction: {}",
             //       signals.nvdaMomentum * 100, direction);
            return direction;
        } else if (msftStrong && !nvdaStrong) {
            // MSFT has strong signal, NVDA weak - but weight by correlation
            if (signals.correlation > CORRELATION_THRESHOLD) {
                String direction = signals.msftMomentum > 0 ? "UP" : "DOWN";
//                log.info("STRONG SIGNAL: MSFT strong with correlation {}, direction: {}",
//                        signals.correlation, direction);
                return direction;
            } else {
                // Low correlation - MSFT strong signal gets reduced weight
                double reducedSignal = signals.msftMomentum * 0.5; // Reduce impact
//                log.info("STRONG SIGNAL: MSFT strong but low correlation, reducing weight: {}%",
//                        reducedSignal * 100);
                return reducedSignal > ULTRA_SENSITIVE_THRESHOLD ? "UP" :
                        reducedSignal < -ULTRA_SENSITIVE_THRESHOLD ? "DOWN" : "NEUTRAL";
            }
        } else if (nvdaStrong && msftStrong) {
            // Both strong - use original combined logic but with higher confidence
            //log.info("STRONG SIGNAL: Both strong, using combined: {}", signals.combinedStrength);
            return signals.combinedStrength > 0 ? "UP" : signals.combinedStrength < 0 ? "DOWN" : "NEUTRAL";
        }

        // Fallback to normal logic
        return signals.combinedStrength > ULTRA_SENSITIVE_THRESHOLD ? "UP" :
                signals.combinedStrength < -ULTRA_SENSITIVE_THRESHOLD ? "DOWN" : "NEUTRAL";
    }

    /**
     * Identify which stock is acting as market leader based on correlation and momentum
     */
    private MarketLeader identifyMarketLeader(ReactiveSignals signals) {
        // Calculate leadership scores
        double nvdaLeadershipScore = Math.abs(signals.nvdaMomentum) * 2.0; // NVDA gets 2x multiplier for current market
        double msftLeadershipScore = Math.abs(signals.msftMomentum) * 1.0;

        // Factor in correlation - higher correlation with QQQ means stronger leadership
        if (signals.correlation > 0.4) { // Assuming this represents market correlation
            nvdaLeadershipScore *= 1.2; // Boost for high correlation
            msftLeadershipScore *= 1.1;
        }

        if (nvdaLeadershipScore > msftLeadershipScore && Math.abs(signals.nvdaMomentum) > 0.001) {
            //log.debug("LEADER ANALYSIS: NVDA vs msft leading (score: {} vs {})",
         //           String.format("%.3f",nvdaLeadershipScore), String.format("%.3f",msftLeadershipScore));
            return new MarketLeader("NVDA", signals.nvdaMomentum, nvdaLeadershipScore);
        } else if (Math.abs(signals.msftMomentum) > 0.001) {
//            log.debug("LEADER ANALYSIS: MSFT vs nvidia leading (score: {} vs {})",
//                    String.format("%.3f",msftLeadershipScore), String.format("%.3f",nvdaLeadershipScore));
            return new MarketLeader("MSFT", signals.msftMomentum, msftLeadershipScore);
        } else {
            return new MarketLeader("NONE", 0.0, 0.0);
        }
    }

    /**
     * Apply correlation-aware logic to determine final signal
     */
    private double applyCorrelationAwareLogic(ReactiveSignals signals, MarketLeader leader) {

        if (signals.correlation < CORRELATION_THRESHOLD) {
            // Low correlation: Use leader-weighted approach
            //log.debug("LOW CORRELATION: Using leader-weighted approach");

            if (leader.symbol.equals("NVDA")) {
                // NVDA leading in low correlation environment
                double leaderWeighted = (signals.nvdaMomentum * LEADER_WEIGHT) +
                        (signals.msftMomentum * OTHERS_WEIGHT);
//                log.debug("NVDA LEADER: weighted signal {} (NVDA: {}%, MSFT: {}%)",
//                        String.format("%.4f",leaderWeighted), String.format("%.1f",signals.nvdaMomentum * 100), String.format("%.1f",signals.msftMomentum * 100));
                return leaderWeighted;
            } else if (leader.symbol.equals("MSFT")) {
                // MSFT leading but with less weight than NVDA would get
                double leaderWeighted = (signals.msftMomentum * 0.6) +
                        (signals.nvdaMomentum * 0.4);
                //log.debug("MSFT LEADER: weighted signal {}", String.format("%.4f",leaderWeighted));
                return leaderWeighted;
            } else {
                // No clear leader - use dampened average
                double dampened = signals.combinedStrength * 0.7;
                //log.debug("NO LEADER: dampened signal {}", String.format("%.4f",dampened));
                return dampened;
            }

        } else if (signals.correlation > HIGH_CORRELATION_THRESHOLD_NEW) {
            // High correlation: Use traditional weighted average
            //log.debug("HIGH CORRELATION: Using traditional weighted average");
            return signals.combinedStrength;

        } else {
            // Medium correlation: Blend approaches
            //log.debug("MEDIUM CORRELATION: Blending approaches");
            double traditional = signals.combinedStrength;

            // Calculate leader-weighted
            double leaderWeighted;
            if (leader.symbol.equals("NVDA")) {
                leaderWeighted = (signals.nvdaMomentum * LEADER_WEIGHT) +
                        (signals.msftMomentum * OTHERS_WEIGHT);
            } else {
                leaderWeighted = (signals.msftMomentum * 0.6) +
                        (signals.nvdaMomentum * 0.4);
            }

            // Blend based on correlation strength
            double corrWeight = (signals.correlation - CORRELATION_THRESHOLD) /
                    (HIGH_CORRELATION_THRESHOLD_NEW - CORRELATION_THRESHOLD);

            double blended = (traditional * corrWeight) + (leaderWeighted * (1.0 - corrWeight));
            log.debug("BLENDED: traditional {}, leader {}, final {}",
                    String.format("%.4f",traditional), String.format("%.4f",leaderWeighted),String.format("%.4f",blended));
            return blended;
        }
    }

    /**
     * Get direct QQQ price trend if available - MOST IMPORTANT CHECK
     */
    private String getDirectQQQTrend(String symbol) {
        try {
            // If we're analyzing QQQ itself, get its direct price movement
            QuoteResponse qqqResponse = tradierService.getQuote("QQQ");
            if (qqqResponse == null || qqqResponse.getQuote() == null) {
                log.debug("DIRECT QQQ: No quote available");
                return "NEUTRAL";
            }

            Quote qqq = qqqResponse.getQuote();
            double qqqChange = getPercentChange(qqq);

//            log.info("DIRECT QQQ: Price change {}% (current:{}, previous:{})",
//                    qqqChange * 100, qqq.getLast(), qqq.getPreviousClose());

            // Ultra sensitive QQQ thresholds - should catch your -0.10% move
            if (qqqChange > QQQ_DIRECT_THRESHOLD) {
                //log.info("DIRECT QQQ: UP trend ({}% > {}%)", qqqChange * 100, QQQ_DIRECT_THRESHOLD * 100);
                return "UP";
            } else if (qqqChange < -QQQ_DIRECT_THRESHOLD) {
                //log.info("DIRECT QQQ: DOWN trend ({}% < -{}%)", qqqChange * 100, QQQ_DIRECT_THRESHOLD * 100);
                return "DOWN";
            }

            //log.debug("DIRECT QQQ: Within neutral range ({}%)", qqqChange * 100);
            return "NEUTRAL";

        } catch (Exception e) {
            log.debug("DIRECT QQQ: Failed to get QQQ quote: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    /**
     * Get simple NVDA and MSFT momentum signals with better error handling
     * ENHANCED: Now calculates better correlation and leadership metrics
     */
    private ReactiveSignals getSimpleTechSignals() {
        try {
           // log.debug("REACTIVE SIGNALS: Fetching NVDA and MSFT quotes");

            // Get quotes with timeout handling
            Quote nvdaQuote = getQuoteWithTimeout("NVDA");
            Quote msftQuote = getQuoteWithTimeout("MSFT");

            if (nvdaQuote == null || msftQuote == null) {
               // log.warn("REACTIVE SIGNALS: Missing quotes - NVDA:{}, MSFT:{}", nvdaQuote != null, msftQuote != null);
                return getLastKnownTechSignals();
            }

            // Calculate momentum (% change)
            double nvdaMomentum = getPercentChange(nvdaQuote);
            double msftMomentum = getPercentChange(msftQuote);

            // Calculate enhanced correlation
            double correlation = calculateEnhancedCorrelation(nvdaMomentum, msftMomentum);

            // Calculate combined strength using original weights initially
            double combinedStrength = (nvdaMomentum * NVDA_WEIGHT) + (msftMomentum * MSFT_WEIGHT);

            // Calculate divergence
            double divergence = Math.abs(nvdaMomentum - msftMomentum);

//            log.debug("REACTIVE SIGNALS: NVDA:{}%, MSFT:{}%, correlation:{}, combined:{}, divergence:{}",
//                    nvdaMomentum * 100, msftMomentum * 100, correlation, combinedStrength, divergence);

            ReactiveSignals signals = new ReactiveSignals(nvdaMomentum, msftMomentum, correlation, combinedStrength, divergence);

            // Cache for future use
            lastReactiveSignals.put("CACHE", signals);

            return signals;

        } catch (Exception e) {
            log.error("REACTIVE SIGNALS: Failed to get tech signals: {}", e.getClass().getSimpleName());
            return getLastKnownTechSignals();
        }
    }

    /**
     * ENHANCED: Better correlation calculation that considers market context
     */
    private double calculateEnhancedCorrelation(double nvdaMomentum, double msftMomentum) {
        // Handle flat market case
        if (Math.abs(nvdaMomentum) < 0.001 && Math.abs(msftMomentum) < 0.001) {
            return 0.0; // Both flat = no correlation data
        }

        // Same direction = positive correlation
        if ((nvdaMomentum > 0 && msftMomentum > 0) || (nvdaMomentum < 0 && msftMomentum < 0)) {
            // Calculate strength of agreement
            double totalMomentum = Math.abs(nvdaMomentum) + Math.abs(msftMomentum);
            double difference = Math.abs(nvdaMomentum - msftMomentum);
            double agreement = 1.0 - (difference / (totalMomentum + 0.001));
            return Math.max(0.0, agreement * 0.8); // Scale to 0-0.8 range
        } else {
            // Opposite directions = negative/low correlation
            return 0.0; // In your logs, correlation shows as 0.0 for divergent moves
        }
    }

    /**
     * Get a quote with timeout handling
     */
    private Quote getQuoteWithTimeout(String symbol) {
        try {
            QuoteResponse response = tradierService.getQuote(symbol);
            return response != null ? response.getQuote() : null;
        } catch (Exception e) {
            log.warn("QUOTE TIMEOUT: Failed to get {} quote: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Get last known tech signals as fallback
     */
    private ReactiveSignals getLastKnownTechSignals() {
        ReactiveSignals cached = lastReactiveSignals.get("CACHE");
        if (cached != null) {
            log.debug("REACTIVE SIGNALS: Using cached signals from previous call");
            return cached;
        }

        log.warn("REACTIVE SIGNALS: No cached signals available, returning neutral");
        return new ReactiveSignals(0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * Calculate percent change from a quote
     */
    private double getPercentChange(Quote quote) {
        if (quote.getLast() != null && quote.getPreviousClose() != null) {
            return quote.getLast().subtract(quote.getPreviousClose())
                    .divide(quote.getPreviousClose(), 4, RoundingMode.HALF_UP).doubleValue();
        }
        return 0.0;
    }

    /**
     * DEBUG METHOD: Test reactive trend detection directly
     */
    public Map<String, Object> debugReactiveTrend(String symbol) {
        Map<String, Object> debug = new HashMap<>();

        try {
            // Test direct QQQ movement
            QuoteResponse qqqResponse = tradierService.getQuote("QQQ");
            if (qqqResponse != null && qqqResponse.getQuote() != null) {
                Quote qqq = qqqResponse.getQuote();
                double qqqChange = getPercentChange(qqq);

                debug.put("qqq_current_price", qqq.getLast());
                debug.put("qqq_previous_close", qqq.getPreviousClose());
                debug.put("qqq_change_percent", qqqChange * 100);
                debug.put("qqq_change_raw", qqqChange);
                debug.put("qqq_threshold_percent", QQQ_DIRECT_THRESHOLD * 100);
                debug.put("qqq_should_be_down", qqqChange < -QQQ_DIRECT_THRESHOLD);
                debug.put("qqq_should_be_up", qqqChange > QQQ_DIRECT_THRESHOLD);

                // Calculate actual price difference
                if (qqq.getLast() != null && qqq.getPreviousClose() != null) {
                    BigDecimal priceDiff = qqq.getLast().subtract(qqq.getPreviousClose());
                    debug.put("qqq_price_difference", priceDiff);
                }
            }

            // Test NVDA/MSFT signals
            ReactiveSignals signals = getSimpleTechSignals();
            if (signals != null) {
                debug.put("nvda_momentum", signals.nvdaMomentum);
                debug.put("nvda_momentum_percent", signals.nvdaMomentum * 100);
                debug.put("msft_momentum", signals.msftMomentum);
                debug.put("msft_momentum_percent", signals.msftMomentum * 100);
                debug.put("combined_strength", signals.combinedStrength);
                debug.put("correlation", signals.correlation);
                debug.put("divergence", signals.divergence);
                debug.put("combined_threshold_percent", ULTRA_SENSITIVE_THRESHOLD * 100);
                debug.put("combined_should_be_down", signals.combinedStrength < -ULTRA_SENSITIVE_THRESHOLD);
                debug.put("combined_should_be_up", signals.combinedStrength > ULTRA_SENSITIVE_THRESHOLD);

                // Add enhanced analysis
                MarketLeader leader = identifyMarketLeader(signals);
                debug.put("market_leader", leader.symbol);
                debug.put("leader_momentum", leader.momentum);
                debug.put("leader_score", leader.score);

                double enhancedSignal = applyCorrelationAwareLogic(signals, leader);
                debug.put("enhanced_signal", enhancedSignal);
                debug.put("enhanced_direction", enhancedSignal > ULTRA_SENSITIVE_THRESHOLD ? "UP" :
                        enhancedSignal < -ULTRA_SENSITIVE_THRESHOLD ? "DOWN" : "NEUTRAL");
            }

            // Test the actual methods
            debug.put("direct_qqq_trend", getDirectQQQTrend(symbol));
            debug.put("simplified_reactive_trend", detectReactiveTrendSimplified(symbol));
            debug.put("main_detect_trend", detectTrend(symbol));
            debug.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            debug.put("error", e.getMessage());
            debug.put("error_class", e.getClass().getSimpleName());
        }

        return debug;
    }

    // Helper class for market leader identification
    private static class MarketLeader {
        final String symbol;
        final double momentum;
        final double score;

        MarketLeader(String symbol, double momentum, double score) {
            this.symbol = symbol;
            this.momentum = momentum;
            this.score = score;
        }
    }

    // REACTIVE SIGNALS DATA CLASS - ALREADY EXISTS IN ORIGINAL
    @Data
    public static class ReactiveSignals {
        public final double nvdaMomentum;
        public final double msftMomentum;
        public final double correlation;
        public final double combinedStrength;
        public final double divergence;

        public ReactiveSignals(double nvdaMomentum, double msftMomentum, double correlation,
                               double combinedStrength, double divergence) {
            this.nvdaMomentum = nvdaMomentum;
            this.msftMomentum = msftMomentum;
            this.correlation = correlation;
            this.combinedStrength = combinedStrength;
            this.divergence = divergence;
        }
    }

    @PostConstruct
    public void init() {
        log.info("TREND DETECTOR: Enhanced reactive trend detection ready");
    }

    @PreDestroy
    public void cleanup() {
        log.info("TREND DETECTOR: Cleanup complete");
    }
}