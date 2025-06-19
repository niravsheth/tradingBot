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
    private final OrchestratorMonitoringService monitoringService;
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

    // Position limits
    private static final int MAX_CONCURRENT_POSITIONS = 2;
    private static final int MAX_SIGNALS_PER_HOUR = 4;

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

        monitoringService.logOrchestratorDecision(analysisId, rawSignals, finalSignals, reason);

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

            // Check both the extracted type and the full strategy name
            int priority = -1;

            // First check full strategy name
            if (priorityStrategies.contains(signal.getStrategy())) {
                priority = priorityStrategies.indexOf(signal.getStrategy());
            }
            // Then check extracted type
            else if (priorityStrategies.contains(strategyType)) {
                priority = priorityStrategies.indexOf(strategyType);
            }
            // Special handling for 0DTE strategies
            else if (signal.getStrategy().startsWith("0DTE_")) {
                // Check if any part of the strategy matches
                for (int i = 0; i < priorityStrategies.size(); i++) {
                    if (signal.getStrategy().contains(priorityStrategies.get(i)) ||
                            priorityStrategies.get(i).contains(strategyType)) {
                        priority = i;
                        break;
                    }
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

        // Take top priority strategies
        List<Signal> filtered = new ArrayList<>();

        // For HIGH_VOLATILITY regime, be more permissive
        int maxPriorityLevels = (regime == MarketRegime.HIGH_VOLATILITY) ? 5 : 3;

        for (int i = 0; i < Math.min(maxPriorityLevels, priorityStrategies.size()); i++) {
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

        // Remove expired entries from recent signals
        recentSignals.entrySet().removeIf(entry ->
                entry.getValue().isBefore(now.minusMinutes(SIGNAL_COOLDOWN_MINUTES)));

        for (Signal signal : signals) {
            String key = signal.getSymbol() + "_" + extractStrategyType(signal.getStrategy());

            if (!recentSignals.containsKey(key)) {
                limited.add(signal);
                recentSignals.put(key, now);

                if (limited.size() >= MAX_CONCURRENT_POSITIONS) {
                    break;
                }
            } else {
                log.debug("[ORCHESTRATOR][{}] Skipping {} - cooldown active",
                        analysisId, key);
            }
        }

        return limited;
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

        // Apply quality thresholds based on time of day
        LocalTime now = LocalTime.now(ET_ZONE);
        double minConfidence = getMinimumConfidenceThreshold(now);

        // Lower threshold for HIGH_VOLATILITY regime
        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            minConfidence = Math.min(minConfidence, 0.60);
        }

        double finalMinConfidence = minConfidence;
        List<Signal> qualified = signals.stream()
                .filter(s -> s.getConfidence() >= finalMinConfidence)
                .limit(MAX_CONCURRENT_POSITIONS)
                .collect(Collectors.toList());

        // Log decision
        if (qualified.isEmpty() && !signals.isEmpty()) {
            log.info("[ORCHESTRATOR][{}] No signals met minimum confidence threshold of {}",
                    analysisId, String.format("%.2f", minConfidence));
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

    private double getMinimumConfidenceThreshold(LocalTime now) {
        // Higher thresholds during non-prime hours
        if (now.isAfter(LocalTime.of(9, 45)) && now.isBefore(LocalTime.of(10, 15))) {
            return 0.65; // Prime morning window
        } else if (now.isAfter(LocalTime.of(14, 0)) && now.isBefore(LocalTime.of(14, 30))) {
            return 0.65; // Afternoon momentum window
        } else if (now.isAfter(LocalTime.of(15, 0))) {
            return 0.75; // Higher threshold near close
        } else {
            return 0.70; // Standard threshold
        }
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