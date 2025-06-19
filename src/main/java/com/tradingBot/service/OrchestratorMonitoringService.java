package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import com.tradingBot.repository.SignalRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrchestratorMonitoringService {
    // Make these thread-safe with proper concurrent collections
    private final ConcurrentHashMap<String, OrchestratorDecision> recentDecisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> strategyConflictCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> strategySuccessRate = new ConcurrentHashMap<>();


    // Add size limit to prevent memory leaks
    private static final int MAX_DECISIONS_CACHE = 1000;

    private final SignalRepository signalRepository;



    public void logOrchestratorDecision(String analysisId,
                                        List<Signal> rawSignals,
                                        List<Signal> finalSignals,
                                        String reason) {
        try {
            // Prevent memory leaks by limiting cache size
            if (recentDecisions.size() > MAX_DECISIONS_CACHE) {
                cleanupOldDecisions();
            }

            OrchestratorDecision decision = new OrchestratorDecision();
            decision.setAnalysisId(analysisId);
            decision.setTimestamp(LocalDateTime.now());
            decision.setRawSignalCount(rawSignals != null ? rawSignals.size() : 0);
            decision.setFinalSignalCount(finalSignals != null ? finalSignals.size() : 0);
            decision.setFilteredCount(decision.getRawSignalCount() - decision.getFinalSignalCount());
            decision.setReason(reason != null ? reason : "No reason provided");
            decision.setRawStrategies(extractStrategies(rawSignals != null ? rawSignals : new ArrayList<>()));
            decision.setFinalStrategies(extractStrategies(finalSignals != null ? finalSignals : new ArrayList<>()));

            recentDecisions.put(analysisId, decision);

            // Log with null safety
            log.info("[MONITOR] Orchestrator Decision: {} raw -> {} final signals",
                    decision.getRawSignalCount(), decision.getFinalSignalCount());

            if (rawSignals != null && finalSignals != null) {
                log.info("[MONITOR] Filtered strategies: {}",
                        getFilteredStrategies(rawSignals, finalSignals));
            }

            // Track conflicts
            if (rawSignals != null && hasConflictingSignals(rawSignals)) {
                String conflictKey = LocalDateTime.now().toLocalDate().toString();
                strategyConflictCount.merge(conflictKey, 1, Integer::sum);
            }
        } catch (Exception e) {
            log.error("[MONITOR] Error logging orchestrator decision: {}", e.getMessage(), e);
        }
    }

    private void cleanupOldDecisions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        recentDecisions.entrySet().removeIf(entry ->
                entry.getValue().getTimestamp().isBefore(cutoff));
    }

    @Scheduled(fixedDelay = 300000) // Every 5 minutes
    public void reportOrchestratorMetrics() {
        OrchestratorMetrics metrics = calculateMetrics();

        log.info("=== ORCHESTRATOR METRICS ===");
        log.info("Signal Reduction Rate: {}%",
                String.format("%.1f", metrics.getSignalReductionRate() * 100));
        log.info("Conflict Rate: {}%",
                String.format("%.1f", metrics.getConflictRate() * 100));
        log.info("Top Filtered Strategies: {}", metrics.getTopFilteredStrategies());
        log.info("Average Confidence Improvement: {}%",
                String.format("%.1f", metrics.getConfidenceImprovement() * 100));
        log.info("===========================");

        // Alert on anomalies
        if (metrics.getSignalReductionRate() > 0.9) {
            log.warn("⚠️ HIGH FILTER RATE: Orchestrator filtering {}% of signals",
                    (int)(metrics.getSignalReductionRate() * 100));
        }

        if (metrics.getConflictRate() > 0.3) {
            log.warn("⚠️ HIGH CONFLICT RATE: {}% of analyses have conflicting signals",
                    (int)(metrics.getConflictRate() * 100));
        }
    }

    public Map<String, Object> getOrchestratorDashboard() {
        Map<String, Object> dashboard = new HashMap<>();

        // Recent decisions
        List<OrchestratorDecision> recent = recentDecisions.values().stream()
                .sorted(Comparator.comparing(OrchestratorDecision::getTimestamp).reversed())
                .limit(20)
                .collect(Collectors.toList());

        dashboard.put("recentDecisions", recent);
        dashboard.put("metrics", calculateMetrics());
        dashboard.put("strategyPerformance", getStrategyPerformance());
        dashboard.put("timeBasedAnalysis", getTimeBasedAnalysis());

        return dashboard;
    }

    private OrchestratorMetrics calculateMetrics() {
        OrchestratorMetrics metrics = new OrchestratorMetrics();

        if (recentDecisions.isEmpty()) {
            return metrics;
        }

        // Calculate signal reduction rate
        int totalRaw = recentDecisions.values().stream()
                .mapToInt(OrchestratorDecision::getRawSignalCount)
                .sum();
        int totalFinal = recentDecisions.values().stream()
                .mapToInt(OrchestratorDecision::getFinalSignalCount)
                .sum();

        if (totalRaw > 0) {
            metrics.setSignalReductionRate(1.0 - (double)totalFinal / totalRaw);
        }

        // Calculate conflict rate
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        long recentAnalyses = recentDecisions.values().stream()
                .filter(d -> d.getTimestamp().isAfter(cutoff))
                .count();

        if (recentAnalyses > 0) {
            String today = LocalDateTime.now().toLocalDate().toString();
            int conflicts = strategyConflictCount.getOrDefault(today, 0);
            metrics.setConflictRate((double)conflicts / recentAnalyses);
        }

        // Find most filtered strategies
        Map<String, Integer> filteredCount = new HashMap<>();
        recentDecisions.values().forEach(decision -> {
            Set<String> filtered = new HashSet<>(decision.getRawStrategies());
            filtered.removeAll(decision.getFinalStrategies());
            filtered.forEach(strategy ->
                    filteredCount.merge(strategy, 1, Integer::sum));
        });

        metrics.setTopFilteredStrategies(
                filteredCount.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(3)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList())
        );

        return metrics;
    }

    private Map<String, Double> getStrategyPerformance() {
        // Query last 30 days of executed signals
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        List<Signal> executedSignals = signalRepository.findExecutedSignalsAfter(cutoff);

        Map<String, List<Signal>> byStrategy = executedSignals.stream()
                .collect(Collectors.groupingBy(s -> extractStrategyType(s.getStrategy())));

        Map<String, Double> performance = new HashMap<>();

        byStrategy.forEach((strategy, signals) -> {
            long profitable = signals.stream()
                    .filter(s -> s.getRealizedPnl() != null &&
                            s.getRealizedPnl().doubleValue() > 0)
                    .count();

            double winRate = signals.isEmpty() ? 0 : (double)profitable / signals.size();
            performance.put(strategy, winRate);

            // Update success rate cache
            strategySuccessRate.put(strategy, winRate);
        });

        return performance;
    }

    private Map<String, Object> getTimeBasedAnalysis() {
        Map<String, Object> analysis = new HashMap<>();

        // Group decisions by hour
        Map<Integer, List<OrchestratorDecision>> byHour = recentDecisions.values().stream()
                .collect(Collectors.groupingBy(d -> d.getTimestamp().getHour()));

        // Calculate metrics by hour
        Map<Integer, Double> reductionByHour = new HashMap<>();
        byHour.forEach((hour, decisions) -> {
            double avgReduction = decisions.stream()
                    .mapToDouble(d -> (double)d.getFilteredCount() /
                            Math.max(1, d.getRawSignalCount()))
                    .average()
                    .orElse(0.0);
            reductionByHour.put(hour, avgReduction);
        });

        analysis.put("reductionByHour", reductionByHour);
        analysis.put("peakHours", findPeakSignalHours(byHour));

        return analysis;
    }

    private List<Integer> findPeakSignalHours(Map<Integer, List<OrchestratorDecision>> byHour) {
        return byHour.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Integer, List<OrchestratorDecision>> e) ->
                                e.getValue().stream().mapToInt(OrchestratorDecision::getFinalSignalCount).sum())
                        .reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private Set<String> extractStrategies(List<Signal> signals) {
        return signals.stream()
                .map(s -> extractStrategyType(s.getStrategy()))
                .collect(Collectors.toSet());
    }

    private String extractStrategyType(String strategy) {
        // Same logic as in orchestrator
        if (strategy.contains("OPENING_DRIVE")) return "OPENING_DRIVE";
        if (strategy.contains("ORB")) return "ORB";
        if (strategy.contains("BREAKOUT")) return "VWAP_BREAKOUT";
        if (strategy.contains("REVERSION")) return "VWAP_REVERSION";
        if (strategy.contains("SUPPORT")) return "VWAP_SUPPORT";
        if (strategy.contains("RESISTANCE")) return "VWAP_RESISTANCE";
        if (strategy.contains("SPIKE")) return "VOLUME_SPIKE";
        if (strategy.contains("BOUNCE")) return "VWAP_BOUNCE";
        return "UNKNOWN";
    }

    private Set<String> getFilteredStrategies(List<Signal> raw, List<Signal> final_) {
        Set<String> rawStrats = extractStrategies(raw);
        Set<String> finalStrats = extractStrategies(final_);
        rawStrats.removeAll(finalStrats);
        return rawStrats;
    }

    private boolean hasConflictingSignals(List<Signal> signals) {
        Set<String> directions = signals.stream()
                .map(s -> s.getOptionSymbol().contains("C") ? "CALL" : "PUT")
                .collect(Collectors.toSet());
        return directions.size() > 1;
    }
}

// Supporting classes
@Data
class OrchestratorDecision {
    private String analysisId;
    private LocalDateTime timestamp;
    private int rawSignalCount;
    private int finalSignalCount;
    private int filteredCount;
    private String reason;
    private Set<String> rawStrategies;
    private Set<String> finalStrategies;
}

@Data
class OrchestratorMetrics {
    private double signalReductionRate;
    private double conflictRate;
    private double confidenceImprovement;
    private List<String> topFilteredStrategies;
    private Map<String, Double> strategyWinRates;
}