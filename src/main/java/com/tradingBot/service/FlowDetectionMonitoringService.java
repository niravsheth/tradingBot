package com.tradingBot.service;

import com.tradingBot.analytics.AdvancedUnusualFlowDetector;
import com.tradingBot.model.Option;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class FlowDetectionMonitoringService {

    private final TelegramService telegramService;

    // Daily tracking
    private final Map<String, FlowDayStats> dailyStats = new ConcurrentHashMap<>();
    private final List<FlowEvent> todaysFlows = Collections.synchronizedList(new ArrayList<>());

    @Data
    public static class FlowDayStats {
        private int totalFlowsAnalyzed = 0;
        private int confirmedDirectional = 0;
        private int filteredAsHedging = 0;
        private int smartMoneyFlows = 0;
        private BigDecimal totalPremiumAnalyzed = BigDecimal.ZERO;
        private BigDecimal directionalPremium = BigDecimal.ZERO;
        private BigDecimal hedgingPremium = BigDecimal.ZERO;
        private Map<String, Integer> hedgingReasons = new HashMap<>();
        private List<String> largestFlows = new ArrayList<>();
    }

    @Data
    public static class FlowEvent {
        private LocalDateTime timestamp;
        private String optionSymbol;
        private String type; // CALL/PUT
        private BigDecimal strike;
        private int volume;
        private BigDecimal premium;
        private boolean wasFiltered;
        private String filterReason;
        private double hedgingProbability;
        private String flowType;
        private double aggressiveness;
        private boolean isSmartMoney;
    }

    public void recordFlowAnalysis(Option option, AdvancedUnusualFlowDetector.FlowAnalysis analysis) {
        String today = LocalDateTime.now().toLocalDate().toString();
        FlowDayStats stats = dailyStats.computeIfAbsent(today, k -> new FlowDayStats());

        BigDecimal premium = option.getMidPrice()
                .multiply(BigDecimal.valueOf(option.getVolume()))
                .multiply(BigDecimal.valueOf(100));

        // Update daily stats
        stats.totalFlowsAnalyzed++;
        stats.totalPremiumAnalyzed = stats.totalPremiumAnalyzed.add(premium);

        if (analysis.isUnusualFlow()) {
            stats.confirmedDirectional++;
            stats.directionalPremium = stats.directionalPremium.add(premium);

            if (analysis.isSmartMoney()) {
                stats.smartMoneyFlows++;
            }
        } else {
            stats.filteredAsHedging++;
            stats.hedgingPremium = stats.hedgingPremium.add(premium);

            // Track filter reasons
            stats.hedgingReasons.merge(analysis.getReasoning(), 1, Integer::sum);
        }

        // Track largest flows
        if (premium.compareTo(BigDecimal.valueOf(500000)) > 0) {
            String flowDesc = String.format("%s %s Vol:%d $%,.0f %s",
                    option.getType(), option.getStrikePrice(), option.getVolume(),
                    premium.doubleValue(), analysis.isUnusualFlow() ? "CONFIRMED" : "FILTERED");
            stats.largestFlows.add(flowDesc);

            // Keep only top 10
            if (stats.largestFlows.size() > 10) {
                stats.largestFlows.remove(0);
            }
        }

        // Record individual flow event
        FlowEvent event = new FlowEvent();
        event.setTimestamp(LocalDateTime.now());
        event.setOptionSymbol(option.getSymbol());
        event.setType(option.getType());
        event.setStrike(option.getStrikePrice());
        event.setVolume(option.getVolume());
        event.setPremium(premium);
        event.setWasFiltered(!analysis.isUnusualFlow());
        event.setFilterReason(analysis.getReasoning());
        event.setHedgingProbability(analysis.getHedgingProbability());
        event.setFlowType(analysis.getFlowType());
        event.setAggressiveness(analysis.getAggressiveness());
        event.setSmartMoney(analysis.isSmartMoney());

        todaysFlows.add(event);

        // Clean up old events (keep only today's)
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0);
        todaysFlows.removeIf(e -> e.getTimestamp().isBefore(startOfDay));
    }

    @Scheduled(cron = "0 */30 9-16 * * MON-FRI") // Every 30 minutes during trading hours
    public void generateFlowSummary() {
        String today = LocalDateTime.now().toLocalDate().toString();
        FlowDayStats stats = dailyStats.get(today);

        if (stats == null || stats.totalFlowsAnalyzed == 0) {
            return;
        }

        double hedgingRatio = (double) stats.filteredAsHedging / stats.totalFlowsAnalyzed;
        double directionalRatio = (double) stats.confirmedDirectional / stats.totalFlowsAnalyzed;

        log.info("=== FLOW DETECTION SUMMARY ===");
        log.info("Total Flows Analyzed: {}", stats.totalFlowsAnalyzed);
        log.info("Confirmed Directional: {} ({:.1f}%)",
                stats.confirmedDirectional, directionalRatio * 100);
        log.info("Filtered as Hedging: {} ({:.1f}%)",
                stats.filteredAsHedging, hedgingRatio * 100);
        log.info("Smart Money Flows: {}", stats.smartMoneyFlows);
        log.info("Total Premium: ${:,.0f}", stats.totalPremiumAnalyzed.doubleValue());
        log.info("Directional Premium: ${:,.0f}", stats.directionalPremium.doubleValue());
        log.info("Hedging Premium: ${:,.0f}", stats.hedgingPremium.doubleValue());

        // Alert if too much is being filtered
        if (hedgingRatio > 0.8 && stats.totalFlowsAnalyzed > 10) {
            log.warn("⚠️ HIGH HEDGING RATIO: {:.1f}% - May be filtering too aggressively",
                    hedgingRatio * 100);

            telegramService.sendMessage(String.format(
                    "⚠️ <b>HIGH HEDGING FILTER RATE</b>\n" +
                            "Filtering %.1f%% of flows as hedging\n" +
                            "Confirmed directional: %d\n" +
                            "Filtered: %d\n" +
                            "May need to adjust thresholds",
                    hedgingRatio * 100, stats.confirmedDirectional, stats.filteredAsHedging
            ));
        }

        // Alert if very few directional flows
        if (directionalRatio < 0.1 && stats.totalFlowsAnalyzed > 20) {
            log.warn("⚠️ LOW DIRECTIONAL RATIO: {:.1f}% - May be filtering too strictly",
                    directionalRatio * 100);
        }
    }

    @Scheduled(cron = "0 0 16 * * MON-FRI") // End of trading day
    public void generateDailyFlowReport() {
        String today = LocalDateTime.now().toLocalDate().toString();
        FlowDayStats stats = dailyStats.get(today);

        if (stats == null || stats.totalFlowsAnalyzed == 0) {
            log.info("No unusual flow activity detected today");
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("📊 <b>DAILY FLOW DETECTION REPORT</b>\n\n");

        report.append(String.format("📈 <b>Summary</b>\n"));
        report.append(String.format("• Total Flows: %d\n", stats.totalFlowsAnalyzed));
        report.append(String.format("• Directional: %d (%.1f%%)\n",
                stats.confirmedDirectional,
                (double)stats.confirmedDirectional / stats.totalFlowsAnalyzed * 100));
        report.append(String.format("• Hedging: %d (%.1f%%)\n",
                stats.filteredAsHedging,
                (double)stats.filteredAsHedging / stats.totalFlowsAnalyzed * 100));
        report.append(String.format("• Smart Money: %d\n\n", stats.smartMoneyFlows));

        report.append(String.format("💰 <b>Premium Analysis</b>\n"));
        report.append(String.format("• Total: $%,.0f\n", stats.totalPremiumAnalyzed.doubleValue()));
        report.append(String.format("• Directional: $%,.0f\n", stats.directionalPremium.doubleValue()));
        report.append(String.format("• Hedging: $%,.0f\n\n", stats.hedgingPremium.doubleValue()));

        // Top filter reasons
        if (!stats.hedgingReasons.isEmpty()) {
            report.append("<b>🔍 Top Filter Reasons</b>\n");
            stats.hedgingReasons.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .forEach(entry -> report.append(String.format("• %s: %d\n",
                            entry.getKey(), entry.getValue())));
            report.append("\n");
        }

        // Largest flows
        if (!stats.largestFlows.isEmpty()) {
            report.append("<b>🎯 Largest Flows</b>\n");
            stats.largestFlows.stream()
                    .limit(5)
                    .forEach(flow -> report.append(String.format("• %s\n", flow)));
        }

        telegramService.sendMessage(report.toString());

        // Performance analysis
        analyzeFilterEffectiveness();
    }

    private void analyzeFilterEffectiveness() {
        // Analyze time-based patterns
        Map<String, List<FlowEvent>> hourlyFlows = todaysFlows.stream()
                .collect(Collectors.groupingBy(event ->
                        String.valueOf(event.getTimestamp().getHour())));

        log.info("=== HOURLY FLOW PATTERNS ===");
        hourlyFlows.forEach((hour, flows) -> {
            long directional = flows.stream().filter(f -> !f.isWasFiltered()).count();
            long hedging = flows.stream().filter(FlowEvent::isWasFiltered).count();

            log.info("Hour {}: {} total, {} directional, {} hedging",
                    hour, flows.size(), directional, hedging);
        });

        // Analyze flow type effectiveness
        Map<String, List<FlowEvent>> typeFlows = todaysFlows.stream()
                .filter(event -> !event.isWasFiltered())
                .collect(Collectors.groupingBy(FlowEvent::getFlowType));

        log.info("=== CONFIRMED FLOW TYPES ===");
        typeFlows.forEach((type, flows) -> {
            double avgAggressiveness = flows.stream()
                    .mapToDouble(FlowEvent::getAggressiveness)
                    .average()
                    .orElse(0.0);

            log.info("{}: {} flows, avg aggressiveness: {:.1f}%",
                    type, flows.size(), avgAggressiveness * 100);
        });
    }

    public Map<String, Object> getFlowStatistics() {
        String today = LocalDateTime.now().toLocalDate().toString();
        FlowDayStats stats = dailyStats.get(today);

        Map<String, Object> result = new HashMap<>();

        if (stats != null) {
            result.put("totalFlows", stats.totalFlowsAnalyzed);
            result.put("directionalFlows", stats.confirmedDirectional);
            result.put("hedgingFlows", stats.filteredAsHedging);
            result.put("smartMoneyFlows", stats.smartMoneyFlows);
            result.put("totalPremium", stats.totalPremiumAnalyzed);
            result.put("directionalPremium", stats.directionalPremium);
            result.put("hedgingPremium", stats.hedgingPremium);
            result.put("hedgingRatio", stats.totalFlowsAnalyzed > 0 ?
                    (double)stats.filteredAsHedging / stats.totalFlowsAnalyzed : 0.0);
            result.put("filterReasons", stats.hedgingReasons);
            result.put("largestFlows", stats.largestFlows);
        } else {
            result.put("message", "No flow data available for today");
        }

        return result;
    }

    @Scheduled(cron = "0 5 0 * * *") // Clean up at 12:05 AM
    public void cleanupOldData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);

        // Remove old daily stats
        dailyStats.entrySet().removeIf(entry -> {
            try {
                return LocalDateTime.parse(entry.getKey() + "T00:00:00").isBefore(cutoff);
            } catch (Exception e) {
                return true; // Remove invalid entries
            }
        });

        // Clear today's flows (they'll be rebuilt tomorrow)
        todaysFlows.clear();

        log.info("Cleaned up old flow detection data");
    }
}