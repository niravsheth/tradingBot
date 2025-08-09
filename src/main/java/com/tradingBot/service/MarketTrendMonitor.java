package com.tradingBot.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Market Trend Change Monitor
 * Tracks trend changes and sends immediate Telegram notifications
 * Critical for VWAP signal generation monitoring
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketTrendMonitor {

    private final UnifiedTrendDetector unifiedTrendDetector;
    private final TelegramService telegramService;

    // EDT timezone for market operations
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Track trend state for multiple symbols
    private final ConcurrentHashMap<String, TrendState> trendStates = new ConcurrentHashMap<>();

    @Data
    public static class TrendState {
        private String currentTrend;
        private String previousTrend;
        private LocalDateTime lastChangeTime;
        private LocalDateTime lastNotificationTime;
        private int changeCount;
        private boolean isFirstDetection;

        public TrendState() {
            this.isFirstDetection = true;
            this.changeCount = 0;
        }
    }

    /**
     * Monitor trend changes every 30 seconds during market hours
     * More frequent than analysis to catch rapid changes
     */
    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void monitorTrendChanges() {
        LocalDateTime now = LocalDateTime.now(ET_ZONE);

        // Only monitor during market hours (9:30 AM - 4:00 PM ET)
        if (!isMarketHours(now)) {
            return;
        }

        // Monitor primary symbols
        monitorSymbolTrend("QQQ", now);
        monitorSymbolTrend("SPY", now); // Optional: also monitor SPY
    }

    /**
     * Monitor trend for a specific symbol
     */
    private void monitorSymbolTrend(String symbol, LocalDateTime now) {
        try {
            // Get current trend from your existing detector
            String currentTrend = unifiedTrendDetector.detectTrend(symbol);

            // Get or create trend state for this symbol
            TrendState state = trendStates.computeIfAbsent(symbol, k -> new TrendState());

            // Update trend state
            state.setPreviousTrend(state.getCurrentTrend());
            state.setCurrentTrend(currentTrend);

            // Check if this is the first detection of the day
            if (state.isFirstDetection()) {
                handleFirstTrendDetection(symbol, currentTrend, state, now);
                state.setFirstDetection(false);
                return;
            }

            // Check for trend change (ignore NEUTRAL trends)
            if (hasTrendChanged(state) && !isNeutralTrend(currentTrend)) {
                handleTrendChange(symbol, state, now);
            }

            // Log current state for debugging (every 5 minutes to avoid spam)
            if (now.getMinute() % 5 == 0 && now.getSecond() < 30) {
                log.info("[TREND-MONITOR] {} - Current: {}, Previous: {}, Changes today: {}",
                        symbol, currentTrend, state.getPreviousTrend(), state.getChangeCount());
            }

        } catch (Exception e) {
            log.error("[TREND-MONITOR] Error monitoring trend for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Handle first trend detection of the day (market open)
     */
    private void handleFirstTrendDetection(String symbol, String currentTrend, TrendState state, LocalDateTime now) {
        state.setLastChangeTime(now);
        state.setLastNotificationTime(now);

        // Only notify if not neutral
        if (!isNeutralTrend(currentTrend)) {
            String message = String.format(
                    "📊 <b>MARKET OPEN TREND</b>\n" +
                            "Symbol: %s\n" +
                            "Direction: %s %s\n" +
                            "Time: %s ET\n" +
                            "Status: First detection of the day\n\n" +
                            "🎯 VWAP signals will be %s",
                    symbol,
                    getTrendEmoji(currentTrend), currentTrend,
                    now.format(TIME_FORMATTER),
                    getSignalExpectation(currentTrend)
            );

            //telegramService.sendMessage(message);

            log.info("[TREND-MONITOR] 🚨 FIRST TREND: {} - {} at {}",
                    symbol, currentTrend, now.format(TIME_FORMATTER));
        } else {
            log.info("[TREND-MONITOR] Market opened with NEUTRAL trend for {} - no notification sent", symbol);
        }
    }

    /**
     * Handle trend change detection
     */
    private void handleTrendChange(String symbol, TrendState state, LocalDateTime now) {
        String currentTrend = state.getCurrentTrend();
        String previousTrend = state.getPreviousTrend();

        // Calculate time since last change
        long minutesSinceLastChange = 0;
        if (state.getLastChangeTime() != null) {
            minutesSinceLastChange = java.time.Duration.between(state.getLastChangeTime(), now).toMinutes();
        }

        // Update state
        state.setLastChangeTime(now);
        state.setLastNotificationTime(now);
        state.setChangeCount(state.getChangeCount() + 1);

        // Create comprehensive notification
        String message = String.format(
                "🔄 <b>TREND CHANGE ALERT</b>\n" +
                        "Symbol: %s\n" +
                        "Previous: %s %s\n" +
                        "Current: %s %s\n" +
                        "Time: %s ET\n" +
                        "Duration: %d minutes\n" +
                        "Changes today: %d\n\n" +
                        "📈 Impact: %s\n" +
                        "🎯 Next signals: %s",
                symbol,
                getTrendEmoji(previousTrend), previousTrend,
                getTrendEmoji(currentTrend), currentTrend,
                now.format(TIME_FORMATTER),
                minutesSinceLastChange,
                state.getChangeCount(),
                getTrendChangeImpact(previousTrend, currentTrend),
                getSignalExpectation(currentTrend)
        );

        //telegramService.sendMessage(message);

        log.warn("[TREND-MONITOR] 🚨 TREND CHANGE: {} - {} -> {} after {} minutes (Change #{})",
                symbol, previousTrend, currentTrend, minutesSinceLastChange, state.getChangeCount());

        // Alert for rapid trend changes (potential whipsaw)
        if (minutesSinceLastChange < 15 && state.getChangeCount() > 3) {
            String whipsawAlert = String.format(
                    "⚠️ <b>WHIPSAW WARNING</b>\n" +
                            "Symbol: %s\n" +
                            "Rapid changes: %d in short time\n" +
                            "Consider reducing position sizes\n" +
                            "Market may be choppy",
                    symbol, state.getChangeCount()
            );

            telegramService.sendMessage(whipsawAlert);
            log.warn("[TREND-MONITOR] 🌪️ WHIPSAW WARNING: {} - {} rapid changes", symbol, state.getChangeCount());
        }
    }

    /**
     * Check if trend has meaningfully changed
     */
    private boolean hasTrendChanged(TrendState state) {
        String current = state.getCurrentTrend();
        String previous = state.getPreviousTrend();

        if (previous == null || current == null) {
            return false;
        }

        // Only care about changes between UP and DOWN
        // Ignore changes involving NEUTRAL
        boolean isSignificantChange =
                ("UP".equals(previous) && "DOWN".equals(current)) ||
                        ("DOWN".equals(previous) && "UP".equals(current)) ||
                        (isNeutralTrend(previous) && !isNeutralTrend(current)) ||
                        (!isNeutralTrend(previous) && isNeutralTrend(current));

        return !current.equals(previous) && isSignificantChange;
    }

    /**
     * Check if trend is neutral
     */
    private boolean isNeutralTrend(String trend) {
        return "NEUTRAL".equals(trend) || trend == null;
    }

    /**
     * Get emoji for trend direction
     */
    private String getTrendEmoji(String trend) {
        if (trend == null) return "❓";

        switch (trend) {
            case "UP": return "🟢📈";
            case "DOWN": return "🔴📉";
            case "NEUTRAL": return "⚪⚖️";
            default: return "❓";
        }
    }

    /**
     * Get expected signal types for trend
     */
    private String getSignalExpectation(String trend) {
        if (trend == null) return "Unknown";

        switch (trend) {
            case "UP": return "CALL signals (VWAP bounces, breakouts)";
            case "DOWN": return "PUT signals (VWAP rejections, breakdowns)";
            case "NEUTRAL": return "NO SIGNALS (blocked)";
            default: return "Unknown";
        }
    }

    /**
     * Get trend change impact description
     */
    private String getTrendChangeImpact(String from, String to) {
        if (from == null || to == null) return "Unknown impact";

        if ("UP".equals(from) && "DOWN".equals(to)) {
            return "Bullish to Bearish - Switch to PUT strategies";
        } else if ("DOWN".equals(from) && "UP".equals(to)) {
            return "Bearish to Bullish - Switch to CALL strategies";
        } else if (isNeutralTrend(from) && !isNeutralTrend(to)) {
            return "Neutral to Directional - Signals now ENABLED";
        } else if (!isNeutralTrend(from) && isNeutralTrend(to)) {
            return "Directional to Neutral - Signals now BLOCKED";
        } else {
            return "Trend shift detected";
        }
    }

    /**
     * Check if current time is during market hours
     */
    private boolean isMarketHours(LocalDateTime now) {
        int hour = now.getHour();
        int minute = now.getMinute();

        // Market hours: 9:30 AM - 4:00 PM ET
        if (hour < 9 || hour > 16) {
            return false;
        }

        if (hour == 9 && minute < 30) {
            return false; // Before 9:30 AM
        }

        if (hour == 16 && minute > 0) {
            return false; // After 4:00 PM
        }

        return true;
    }

    /**
     * Get current trend state for a symbol (for external access)
     */
    public TrendState getCurrentTrendState(String symbol) {
        return trendStates.get(symbol);
    }

    /**
     * Get trend change summary for the day
     */
    public String getDailyTrendSummary(String symbol) {
        TrendState state = trendStates.get(symbol);
        if (state == null) {
            return "No trend data available for " + symbol;
        }

        return String.format(
                "📊 Daily Trend Summary for %s:\n" +
                        "Current: %s\n" +
                        "Changes today: %d\n" +
                        "Last change: %s\n" +
                        "Current duration: %d minutes",
                symbol,
                state.getCurrentTrend(),
                state.getChangeCount(),
                state.getLastChangeTime() != null ? state.getLastChangeTime().format(TIME_FORMATTER) : "N/A",
                state.getLastChangeTime() != null ?
                        java.time.Duration.between(state.getLastChangeTime(), LocalDateTime.now(ET_ZONE)).toMinutes() : 0
        );
    }

    /**
     * Reset trend states (called at market open)
     */
    @Scheduled(cron = "0 25 9 * * MON-FRI", zone = "America/New_York") // 9:25 AM ET weekdays
    public void resetDailyTrendStates() {
        log.info("[TREND-MONITOR] Resetting trend states for new trading day");
        trendStates.clear();

//        telegramService.sendMessage(
//                "🌅 <b>MARKET OPENING SOON</b>\n" +
//                        "Trend monitoring ACTIVATED\n" +
//                        "Will notify on first trend detection\n" +
//                        "Ready to track VWAP signal conditions!"
//        );
    }

    /**
     * End of day summary
     */
    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "America/New_York") // 4:05 PM ET weekdays
    public void sendEndOfDaySummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("📊 <b>END OF DAY TREND SUMMARY</b>\n\n");

        for (String symbol : trendStates.keySet()) {
            summary.append(getDailyTrendSummary(symbol)).append("\n\n");
        }

        //telegramService.sendMessage(summary.toString());
        log.info("[TREND-MONITOR] End of day summary sent");
    }
}