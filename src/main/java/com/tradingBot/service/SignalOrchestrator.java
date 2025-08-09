package com.tradingBot.service;

import com.tradingBot.entity.*;
import com.tradingBot.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalOrchestrator {

    private static final int MAX_CONCURRENT_POSITIONS = 2;
    private static final int MAX_SIGNALS_PER_HOUR = 4;
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    // FIXED: Updated strategy priorities to include 0DTE strategies properly
    private static final Map<MarketRegime, List<String>> REGIME_STRATEGY_PRIORITY = Map.of(
            MarketRegime.TRENDING_UP, List.of("0DTE_UNUSUAL_FLOW_CALL", "0DTE_UNUSUAL_FLOW_PUT", "OPENING_DRIVE", "0DTE_OPENING_DRIVE", "ORB", "0DTE_ORB", "VWAP_BREAKOUT", "0DTE_VWAP_BREAKOUT", "VOLUME_SPIKE", "0DTE_VOLUME_SPIKE"),
            MarketRegime.TRENDING_DOWN, List.of("0DTE_UNUSUAL_FLOW_PUT", "0DTE_UNUSUAL_FLOW_CALL", "OPENING_DRIVE", "0DTE_OPENING_DRIVE", "ORB", "0DTE_ORB", "VWAP_BREAKOUT", "0DTE_VWAP_BREAKOUT", "VOLUME_SPIKE", "0DTE_VOLUME_SPIKE"),
            MarketRegime.CHOPPY, List.of("0DTE_UNUSUAL_FLOW_PUT", "0DTE_UNUSUAL_FLOW_CALL", "VWAP_REVERSION", "0DTE_VWAP_REVERSION", "VWAP_SUPPORT", "0DTE_VWAP_SUPPORT", "VWAP_RESISTANCE", "0DTE_VWAP_RESISTANCE"),
            MarketRegime.HIGH_VOLATILITY, List.of("0DTE_UNUSUAL_FLOW_PUT", "0DTE_UNUSUAL_FLOW_CALL", "VWAP_REVERSION", "0DTE_VWAP_REVERSION", "VOLUME_SPIKE", "0DTE_VOLUME_SPIKE", "VWAP_BOUNCE", "0DTE_VWAP_BOUNCE", "ORB", "0DTE_ORB"), // Added ORB strategies
            MarketRegime.LOW_VOLATILITY, List.of("0DTE_UNUSUAL_FLOW_CALL", "0DTE_UNUSUAL_FLOW_PUT", "VWAP_BREAKOUT", "0DTE_VWAP_BREAKOUT", "ORB", "0DTE_ORB"),
            MarketRegime.OPENING_RANGE, List.of("0DTE_UNUSUAL_FLOW_PUT", "0DTE_UNUSUAL_FLOW_CALL", "OPENING_DRIVE", "0DTE_OPENING_DRIVE", "ORB", "0DTE_ORB", "VOLUME_SPIKE", "0DTE_VOLUME_SPIKE"),
            MarketRegime.CLOSING_RANGE, List.of("VWAP_REVERSION", "0DTE_VWAP_REVERSION")
    );

    // Track recent signals to prevent spam
    private final Map<String, LocalDateTime> recentSignals = new ConcurrentHashMap<>();
    private static final int SIGNAL_COOLDOWN_MINUTES = 5;

    private int getCooldownMinutes(Signal signal) {
        String strategy = signal.getStrategy();
        LocalTime now = LocalTime.now(ET_ZONE);

        // NO COOLDOWN for critical time-sensitive signals
        if (strategy.contains("GAP_FILL") || strategy.contains("GAP_BREAK")) {
            log.info("[COOLDOWN] Gap signal {} - NO COOLDOWN (time critical)", signal.getOptionSymbol());
            return 0; // No cooldown for gap signals
        }

        // VERY SHORT cooldown for unusual flow (30 seconds to 1 minute)
        if (strategy.contains("UNUSUAL_FLOW")) {
            if (signal.getConfidence() >= 0.85) {
                log.info("[COOLDOWN] High confidence unusual flow {} - NO COOLDOWN", signal.getOptionSymbol());
                return 0; // No cooldown for high confidence unusual flow
            } else {
                return 1; // 1 minute for lower confidence unusual flow
            }
        }

        // SHORT cooldown for opening range breakouts in first hour
        if (strategy.contains("ORB") || strategy.contains("OPENING")) {
            if (now.isBefore(LocalTime.of(10, 30))) {
                return 1; // 1 minute in first hour
            } else {
                return 3; // 3 minutes after first hour
            }
        }

        // MEDIUM cooldown for VWAP strategies
        if (strategy.contains("VWAP")) {
            if (strategy.contains("BREAKOUT")) {
                return 2; // 2 minutes for VWAP breakouts (more time-sensitive)
            } else {
                return 3; // 3 minutes for other VWAP signals
            }
        }

        // LONG cooldown for general volume/momentum signals
        if (strategy.contains("VOLUME") || strategy.contains("MOMENTUM")) {
            return 5; // 5 minutes for general signals
        }

        // DEFAULT cooldown based on confidence
        return signal.getConfidence() >= 0.75 ? 2 : 4; // High confidence = 2 min, normal = 4 min
    }


    public List<Signal> orchestrateSignals(List<Signal> rawSignals, TechnicalAnalysis ta, String analysisId) {
        log.info("[ORCHESTRATOR][{}] Processing {} raw signals", analysisId, rawSignals.size());

        if (rawSignals.isEmpty()) {
            return rawSignals;
        }

        // Step 1: Filter by market regime appropriateness
        List<Signal> regimeFiltered = filterByMarketRegime(rawSignals, ta.getMarketRegime(), analysisId);
        if (regimeFiltered.size() != rawSignals.size()) {
            log.info("[ORCHESTRATOR][{}] Regime filtering: {} -> {} signals",
                    analysisId, rawSignals.size(), regimeFiltered.size());
        }

        // Step 2: Resolve conflicts
        List<Signal> conflictResolved = resolveConflicts(regimeFiltered, analysisId);

        // Step 3: Apply position and rate limits
        List<Signal> rateLimited = applyRateLimits(conflictResolved, analysisId);

        // Step 4: Boost or penalize based on confluence
        List<Signal> confluenceAdjusted = adjustForConfluence(rateLimited, ta, analysisId);

        // Step 5: Final selection based on quality
        List<Signal> finalSignals = selectTopSignals(confluenceAdjusted, ta, analysisId);

        String reason = String.format("Regime: %s, Conflicts: %d, Rate limited: %d",
                ta.getMarketRegime(),
                regimeFiltered.size() - conflictResolved.size(),
                conflictResolved.size() - rateLimited.size());

        log.info("[ORCHESTRATOR][{}] Final signals: {} (reduced from {})",
                analysisId, finalSignals.size(), rawSignals.size());

        return finalSignals;
    }

    private List<Signal> filterByMarketRegime(List<Signal> signals, MarketRegime regime, String analysisId) {
        List<String> priorityStrategies = REGIME_STRATEGY_PRIORITY.getOrDefault(regime, List.of());

        if (priorityStrategies.isEmpty()) {
            log.warn("[ORCHESTRATOR][{}] No priority strategies for regime {}", analysisId, regime);
            return signals;
        }

        // Group signals by priority
        Map<Integer, List<Signal>> priorityGroups = new HashMap<>();

        for (Signal signal : signals) {
            String strategyType = extractStrategyType(signal.getStrategy());
            int priority = -1;

            // First check full strategy name
            if (priorityStrategies.contains(signal.getStrategy())) {
                priority = priorityStrategies.indexOf(signal.getStrategy());
            }
            // Then check extracted type
            else if (priorityStrategies.contains(strategyType)) {
                priority = priorityStrategies.indexOf(strategyType);
            }
            // Enhanced 0DTE strategy matching
            else if (signal.getStrategy().startsWith("0DTE_")) {
                String baseStrategy = signal.getStrategy().substring(5); // Remove "0DTE_" prefix

                for (int i = 0; i < priorityStrategies.size(); i++) {
                    String priorityStrategy = priorityStrategies.get(i);

                    // Check multiple matching patterns for better 0DTE compatibility
                    if (signal.getStrategy().contains(priorityStrategy) ||
                            priorityStrategy.contains(strategyType) ||
                            priorityStrategy.contains(baseStrategy) ||
                            baseStrategy.contains(priorityStrategy.replace("0DTE_", ""))) {
                        priority = i;
                        break;
                    }
                }

                // If still no match, give 0DTE strategies medium priority (better than rejection)
                if (priority < 0) {
                    priority = Math.max(0, priorityStrategies.size() / 2);
                    log.debug("[ORCHESTRATOR][{}] 0DTE strategy {} assigned medium priority {}",
                            analysisId, signal.getStrategy(), priority);
                }
            }

            if (priority >= 0) {
                priorityGroups.computeIfAbsent(priority, k -> new ArrayList<>()).add(signal);
                log.debug("[ORCHESTRATOR][{}] Signal {} assigned priority {}",
                        analysisId, signal.getStrategy(), priority);
            } else {
                // Non-priority strategies get lower priority
                priorityGroups.computeIfAbsent(999, k -> new ArrayList<>()).add(signal);
                log.debug("[ORCHESTRATOR][{}] Signal {} not in priority list for regime {}",
                        analysisId, signal.getStrategy(), regime);
            }
        }

        // Take top priority strategies - be more permissive for high volatility
        List<Signal> filtered = new ArrayList<>();
        int maxPriorityLevels = (regime == MarketRegime.HIGH_VOLATILITY) ? 6 : 4; // Increased limits

        for (int i = 0; i < Math.min(maxPriorityLevels, priorityStrategies.size() + 2); i++) {
            if (priorityGroups.containsKey(i)) {
                filtered.addAll(priorityGroups.get(i));
            }
        }

        // If no signals passed, take the best available
        if (filtered.isEmpty() && !signals.isEmpty()) {
            log.warn("[ORCHESTRATOR][{}] No signals matched regime priority, taking highest confidence", analysisId);
            filtered.add(signals.stream()
                    .max(Comparator.comparing(Signal::getConfidence))
                    .orElse(signals.get(0)));
        }

        log.info("[ORCHESTRATOR][{}] Regime filter: {} -> {} signals for {} regime",
                analysisId, signals.size(), filtered.size(), regime);

        return filtered;
    }

    private List<Signal> resolveConflicts(List<Signal> signals, String analysisId) {
        // Group by option type (CALL/PUT)
        Map<String, List<Signal>> byDirection = signals.stream()
                .collect(Collectors.groupingBy(s ->
                        s.getOptionSymbol().contains("C") ? "CALL" : "PUT"));

        List<Signal> resolved = new ArrayList<>();

        // If we have both CALL and PUT signals, choose the stronger direction
        if (byDirection.size() > 1) {
            double callConfidence = byDirection.getOrDefault("CALL", List.of()).stream()
                    .mapToDouble(Signal::getConfidence)
                    .max()
                    .orElse(0.0);

            double putConfidence = byDirection.getOrDefault("PUT", List.of()).stream()
                    .mapToDouble(Signal::getConfidence)
                    .max()
                    .orElse(0.0);

            if (Math.abs(callConfidence - putConfidence) > 0.1) {
                // Clear directional bias
                String strongerDirection = callConfidence > putConfidence ? "CALL" : "PUT";
                resolved.addAll(byDirection.get(strongerDirection));

                log.info("[ORCHESTRATOR][{}] Conflict resolved: {} signals (chose {})",
                        analysisId, resolved.size(), strongerDirection);
            } else {
                // No clear bias - take highest confidence signal regardless of direction
                Signal bestSignal = signals.stream()
                        .max(Comparator.comparing(Signal::getConfidence))
                        .orElse(null);
                if (bestSignal != null) {
                    resolved.add(bestSignal);
                    log.info("[ORCHESTRATOR][{}] No clear directional bias - took highest confidence signal",
                            analysisId);
                }
            }
        } else {
            // No conflict
            resolved.addAll(signals);
        }

        return resolved;
    }

    private List<Signal> applyRateLimits(List<Signal> signals, String analysisId) {
        LocalDateTime now = LocalDateTime.now();
        List<Signal> limited = new ArrayList<>();

        // Clean up expired entries first
        recentSignals.entrySet().removeIf(entry ->
                entry.getValue().isBefore(now.minusMinutes(10))); // Keep 10 min history

        for (Signal signal : signals) {
            // STRATEGY-SPECIFIC KEY - This is the key fix!
            String key = signal.getSymbol() + "_" + signal.getStrategy(); // Use full strategy, not extracted type

            // CHECK FOR BYPASS FIRST
            if (shouldBypassCooldown(signal)) {
                limited.add(signal);
                // Still update tracking to prevent spam
                recentSignals.put(key, now);
                log.warn("[RATE-LIMITS][{}] ⚡ {} BYPASSED cooldown", analysisId, signal.getOptionSymbol());

                // Don't count bypassed signals against position limits
                continue;
            }

            // GET DYNAMIC COOLDOWN
            int cooldownMinutes = getCooldownMinutes(signal);

            // NO COOLDOWN = immediate execution
            if (cooldownMinutes == 0) {
                limited.add(signal);
                recentSignals.put(key, now);
                log.info("[RATE-LIMITS][{}] ✅ {} immediate execution (0 min cooldown)",
                        analysisId, signal.getOptionSymbol());
                continue;
            }

            // CHECK DYNAMIC COOLDOWN
            LocalDateTime lastSignalTime = recentSignals.get(key);
            if (lastSignalTime == null ||
                    lastSignalTime.isBefore(now.minusMinutes(cooldownMinutes))) {

                limited.add(signal);
                recentSignals.put(key, now);

                log.debug("[RATE-LIMITS][{}] ✅ {} passed {}-min cooldown (strategy: {})",
                        analysisId, signal.getOptionSymbol(), cooldownMinutes, signal.getStrategy());

                // Check position limits
                if (limited.size() >= MAX_CONCURRENT_POSITIONS) {
                    log.info("[RATE-LIMITS][{}] Position limit reached ({}), stopping",
                            analysisId, MAX_CONCURRENT_POSITIONS);
                    break;
                }
            } else {
                long remainingMinutes = cooldownMinutes -
                        java.time.Duration.between(lastSignalTime, now).toMinutes();
                log.debug("[RATE-LIMITS][{}] ❌ Skipping {} - {}-min cooldown active ({} min remaining)",
                        analysisId, signal.getOptionSymbol(), cooldownMinutes, remainingMinutes);
            }
        }

        if (limited.size() != signals.size()) {
            log.info("[RATE-LIMITS][{}] Dynamic cooldown filter: {} -> {} signals",
                    analysisId, signals.size(), limited.size());
        }

        return limited;
    }

    private boolean shouldBypassCooldown(Signal signal) {
        String strategy = signal.getStrategy();
        LocalTime now = LocalTime.now(ET_ZONE);

        // CRITICAL SIGNALS that always bypass cooldown
        if (strategy.contains("GAP_FILL") || strategy.contains("GAP_BREAK")) {
            log.warn("[COOLDOWN-BYPASS] Gap signal {} bypassing cooldown - CRITICAL TIME SENSITIVE",
                    signal.getOptionSymbol());
            return true;
        }

        // High confidence unusual flow
        if (strategy.contains("UNUSUAL_FLOW") && signal.getConfidence() >= 0.85) {
            log.warn("[COOLDOWN-BYPASS] High confidence unusual flow {} bypassing cooldown",
                    signal.getOptionSymbol());
            return true;
        }

        // Opening range breakouts in first 30 minutes (most critical window)
        if (strategy.contains("ORB") && now.isBefore(LocalTime.of(10, 0))) {
            log.warn("[COOLDOWN-BYPASS] Early ORB signal {} bypassing cooldown - first 30 min",
                    signal.getOptionSymbol());
            return true;
        }

        return false;
    }

    private List<Signal> adjustForConfluence(List<Signal> signals, TechnicalAnalysis ta,
                                             String analysisId) {
        // Check for confluence factors
        boolean hasVolumeConfirmation = ta.getVolumeRatio() > 1.5;
        boolean hasMomentumAlignment = Math.abs(ta.getMomentumStrength()) > 0.5;
        boolean hasCleanTrend = !ta.isHasRsiDivergence() && !ta.isHasMacdDivergence();

        for (Signal signal : signals) {
            double originalConfidence = signal.getConfidence();
            double adjustedConfidence = originalConfidence;

            // Count confluence factors
            int confluenceCount = 0;
            if (hasVolumeConfirmation) confluenceCount++;
            if (hasMomentumAlignment) confluenceCount++;
            if (hasCleanTrend) confluenceCount++;

            // Special boost for unusual flow signals
            if (signal.getStrategy().contains("UNUSUAL_FLOW")) {
                adjustedConfidence *= 1.10; // 10% boost for unusual flow
            }

            // Adjust confidence based on confluence
            if (confluenceCount >= 3) {
                adjustedConfidence *= 1.15; // 15% boost for strong confluence
            } else if (confluenceCount >= 2) {
                adjustedConfidence *= 1.08; // 8% boost for moderate confluence
            } else if (confluenceCount <= 1) {
                adjustedConfidence *= 0.9; // 10% penalty for weak confluence
            }

            // Cap at 0.95
            adjustedConfidence = Math.min(adjustedConfidence, 0.95);

            if (adjustedConfidence != originalConfidence) {
                signal.setConfidence(adjustedConfidence);
                log.info("[ORCHESTRATOR][{}] Confluence adjustment: {} -> {} (factors: {})",
                        analysisId,
                        String.format("%.2f", originalConfidence),
                        String.format("%.2f", adjustedConfidence),
                        confluenceCount);
            }
        }

        return signals;
    }

    private List<Signal> selectTopSignals(List<Signal> signals, TechnicalAnalysis ta,
                                          String analysisId) {
        if (signals.isEmpty()) {
            return signals;
        }

        // Sort by confidence
        signals.sort(Comparator.comparing(Signal::getConfidence).reversed());

        // Apply quality thresholds based on time of day and strategy
        LocalTime now = LocalTime.now(ET_ZONE);
        String referenceStrategy = signals.get(0).getStrategy(); // Use first signal's strategy
        double minConfidence = getMinimumConfidenceThreshold(now, referenceStrategy);

        // Lower threshold for HIGH_VOLATILITY regime - be more aggressive
        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            minConfidence = Math.min(minConfidence, 0.55); // More aggressive than before
        }

        // Even more aggressive near 0DTE expiration
        if (isNear0DTEExpiration()) {
            minConfidence -= 0.10; // 10% lower in final hour
            log.info("[ORCHESTRATOR][{}] Near 0DTE expiration - lowering threshold to {:.2f}",
                    analysisId, minConfidence);
        }

        double finalMinConfidence = minConfidence;
        List<Signal> qualified = signals.stream()
                .filter(s -> s.getConfidence() >= finalMinConfidence)
                .limit(MAX_CONCURRENT_POSITIONS)
                .collect(Collectors.toList());

        // Log decision
        if (qualified.isEmpty() && !signals.isEmpty()) {
            log.info("[ORCHESTRATOR][{}] No signals met minimum confidence threshold of {:.2f}",
                    analysisId, minConfidence);
            log.info("[ORCHESTRATOR][{}] Best signal had confidence: {:.2f}",
                    analysisId, signals.get(0).getConfidence());
        } else {
            for (Signal signal : qualified) {
                log.info("[ORCHESTRATOR][{}] ✅ SELECTED: {} - Confidence: {}%, Strategy: {}",
                        analysisId,
                        signal.getOptionSymbol(),
                        (int)(signal.getConfidence() * 100),
                        signal.getStrategy());
            }
        }

        return qualified;
    }

    private double getMinimumConfidenceThreshold(LocalTime now, String strategy) {
        // Significantly lower base thresholds for 0DTE
        double baseThreshold;
        if (now.isAfter(LocalTime.of(9, 45)) && now.isBefore(LocalTime.of(10, 15))) {
            baseThreshold = 0.55; // Reduced from 0.65 to 0.55
        } else if (now.isAfter(LocalTime.of(14, 0)) && now.isBefore(LocalTime.of(14, 30))) {
            baseThreshold = 0.55; // Reduced from 0.65 to 0.55
        } else if (now.isAfter(LocalTime.of(15, 0))) {
            baseThreshold = 0.65; // Reduced from 0.75 to 0.65
        } else {
            baseThreshold = 0.60; // Reduced from 0.70 to 0.60
        }

        // Additional 10% reduction for 0DTE strategies (was 5%)
        if (strategy != null && strategy.startsWith("0DTE_")) {
            baseThreshold -= 0.10; // Increased from 0.05 to 0.10
            log.debug("[ORCHESTRATOR] Lowered threshold by 10% for 0DTE strategy: {}", strategy);
        }

        return Math.max(0.45, baseThreshold); // Minimum floor of 45%
    }

    private boolean isNear0DTEExpiration() {
        LocalTime now = LocalTime.now(ET_ZONE);
        return now.isAfter(LocalTime.of(15, 0)); // Last hour before 4:00 PM expiration
    }

    private String extractStrategyType(String strategy) {
        // Extract core strategy type from full strategy name
        if (strategy.contains("OPENING_DRIVE")) return "OPENING_DRIVE";
        if (strategy.contains("ORB")) return "ORB";
        if (strategy.contains("BREAKOUT")) return "VWAP_BREAKOUT";
        if (strategy.contains("REVERSION")) return "VWAP_REVERSION";
        if (strategy.contains("SUPPORT")) return "VWAP_SUPPORT";
        if (strategy.contains("RESISTANCE")) return "VWAP_RESISTANCE";
        if (strategy.contains("SPIKE")) return "VOLUME_SPIKE";
        if (strategy.contains("BOUNCE")) return "VWAP_BOUNCE";
        if (strategy.contains("UNUSUAL_FLOW")) return "UNUSUAL_FLOW";
        return "UNKNOWN";
    }

    // Additional method to check if we should analyze at all
    public boolean shouldAnalyze(String symbol, MarketConditions conditions) {
        // Skip analysis during unfavorable conditions
        if (conditions.getVix() > 30) {
            log.info("[ORCHESTRATOR] VIX too high ({}), skipping analysis", conditions.getVix());
            return false;
        }

        if (conditions.getMarketBreadth() < 0.2) {
            log.info("[ORCHESTRATOR] Poor market breadth ({}), skipping analysis",
                    conditions.getMarketBreadth());
            return false;
        }

        // Check if we've had too many recent losses
        int recentLosses = getRecentLossCount(symbol, 60); // Last 60 minutes
        if (recentLosses >= 3) {
            log.warn("[ORCHESTRATOR] Too many recent losses ({}), entering cooldown", recentLosses);
            return false;
        }

        return true;
    }

    private int getRecentLossCount(String symbol, int minutes) {
        // This would query your trade history
        // Placeholder implementation
        return 0;
    }
}