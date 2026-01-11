package com.tradingBot.service;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DailyLevelService - Tracks key daily price levels for 0DTE target calculation
 *
 * Levels tracked:
 * - Previous Day: High, Low, Close
 * - Today: Open, High (running), Low (running)
 * - Gap: Today Open - Previous Close
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DailyLevelService {

    private final TradierService tradierService;

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final String PRIMARY_SYMBOL = "QQQ";

    // Daily levels storage per symbol
    private final Map<String, DailyLevels> dailyLevelsMap = new ConcurrentHashMap<>();

    // Track if levels have been initialized today
    private LocalDate lastInitDate = null;

    /**
     * Container for all daily price levels
     */
    @Data
    public static class DailyLevels {
        private String symbol;
        private LocalDate tradingDate;

        // Previous day levels (fetched from API at market open)
        private BigDecimal previousDayHigh;
        private BigDecimal previousDayLow;
        private BigDecimal previousDayClose;

        // Today's levels (tracked throughout the day)
        private BigDecimal todayOpen;
        private BigDecimal todayHigh;
        private BigDecimal todayLow;

        // Gap calculation
        private BigDecimal gap;           // Today Open - Previous Close
        private BigDecimal gapPercent;    // Gap as percentage
        private boolean isGapUp;
        private boolean isGapDown;
        private boolean hasUnfilledGap;

        // Metadata
        private LocalDateTime lastUpdated;
        private boolean isInitialized;

        public DailyLevels(String symbol) {
            this.symbol = symbol;
            this.isInitialized = false;
        }

        /**
         * Check if gap has been filled
         */
        public void updateGapStatus(BigDecimal currentPrice) {
            if (gap == null || previousDayClose == null) {
                return;
            }

            if (isGapUp) {
                // Gap up filled when price drops to previous close
                hasUnfilledGap = currentPrice.compareTo(previousDayClose) > 0;
            } else if (isGapDown) {
                // Gap down filled when price rises to previous close
                hasUnfilledGap = currentPrice.compareTo(previousDayClose) < 0;
            } else {
                hasUnfilledGap = false;
            }
        }
    }

    /**
     * Initialize daily levels at market open (9:30 AM ET)
     * Fetches previous day data from Tradier API
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "America/New_York")
    public void initializeDailyLevels() {
        log.info("[DAILY-LEVELS] ========================================");
        log.info("[DAILY-LEVELS] Initializing daily levels at market open");
        log.info("[DAILY-LEVELS] ========================================");

        LocalDate today = LocalDate.now(ET_ZONE);

        // Prevent re-initialization on same day
        if (today.equals(lastInitDate)) {
            log.warn("[DAILY-LEVELS] Already initialized for today ({}), skipping", today);
            return;
        }

        // Initialize QQQ levels
        initializeSymbolLevels(PRIMARY_SYMBOL, today);

        lastInitDate = today;
    }

    /**
     * Initialize levels for a specific symbol
     */
    private void initializeSymbolLevels(String symbol, LocalDate today) {
        log.info("[DAILY-LEVELS][{}] Fetching previous day data from Tradier API", symbol);

        DailyLevels levels = new DailyLevels(symbol);
        levels.setTradingDate(today);

        try {
            // Fetch previous trading day's data
            LocalDate previousTradingDay = getPreviousTradingDay(today);
            log.info("[DAILY-LEVELS][{}] Previous trading day: {}", symbol, previousTradingDay);

            // Call Tradier historical API
            TradierService.HistoricalBar prevDayBar = tradierService.getHistoricalDaily(symbol, previousTradingDay);

            if (prevDayBar != null) {
                levels.setPreviousDayHigh(prevDayBar.getHigh());
                levels.setPreviousDayLow(prevDayBar.getLow());
                levels.setPreviousDayClose(prevDayBar.getClose());

                log.info("[DAILY-LEVELS][{}] ✓ Previous day data loaded successfully", symbol);
                log.info("[DAILY-LEVELS][{}]   Previous Day High:  ${}", symbol, prevDayBar.getHigh());
                log.info("[DAILY-LEVELS][{}]   Previous Day Low:   ${}", symbol, prevDayBar.getLow());
                log.info("[DAILY-LEVELS][{}]   Previous Day Close: ${}", symbol, prevDayBar.getClose());
            } else {
                log.error("[DAILY-LEVELS][{}] ✗ Failed to fetch previous day data from API", symbol);
                // Try to use fallback from current quote
                setFallbackPreviousLevels(symbol, levels);
            }

            levels.setLastUpdated(LocalDateTime.now(ET_ZONE));
            dailyLevelsMap.put(symbol, levels);

        } catch (Exception e) {
            log.error("[DAILY-LEVELS][{}] ✗ Error initializing daily levels: {}", symbol, e.getMessage(), e);
            setFallbackPreviousLevels(symbol, levels);
            dailyLevelsMap.put(symbol, levels);
        }
    }

    /**
     * Set fallback previous day levels from current quote if API fails
     */
    private void setFallbackPreviousLevels(String symbol, DailyLevels levels) {
        log.warn("[DAILY-LEVELS][{}] Using fallback - estimating previous day levels from current quote", symbol);

        try {
            var quoteResponse = tradierService.getQuote(symbol);
            if (quoteResponse != null && quoteResponse.getQuote() != null) {
                BigDecimal currentPrice = quoteResponse.getQuote().getLast();
                if (currentPrice != null) {
                    // Estimate previous close as current price (not ideal but better than nothing)
                    levels.setPreviousDayClose(currentPrice);
                    // Estimate high/low with small buffer
                    levels.setPreviousDayHigh(currentPrice.multiply(new BigDecimal("1.005")));
                    levels.setPreviousDayLow(currentPrice.multiply(new BigDecimal("0.995")));

                    log.warn("[DAILY-LEVELS][{}] Fallback levels set - High: ${}, Low: ${}, Close: ${}",
                            symbol, levels.getPreviousDayHigh(), levels.getPreviousDayLow(), levels.getPreviousDayClose());
                }
            }
        } catch (Exception e) {
            log.error("[DAILY-LEVELS][{}] Failed to set fallback levels: {}", symbol, e.getMessage());
        }
    }

    /**
     * Capture today's open from first bar after market open (9:31 AM)
     */
    @Scheduled(cron = "0 31 9 * * MON-FRI", zone = "America/New_York")
    public void captureTodayOpen() {
        log.info("[DAILY-LEVELS] Capturing today's open price");

        for (String symbol : dailyLevelsMap.keySet()) {
            try {
                var quoteResponse = tradierService.getQuote(symbol);
                if (quoteResponse != null && quoteResponse.getQuote() != null) {
                    BigDecimal openPrice = quoteResponse.getQuote().getLast();
                    DailyLevels levels = dailyLevelsMap.get(symbol);

                    if (levels != null && openPrice != null) {
                        levels.setTodayOpen(openPrice);
                        levels.setTodayHigh(openPrice);
                        levels.setTodayLow(openPrice);

                        // Calculate gap
                        calculateGap(levels);

                        levels.isInitialized=true;
                        levels.setLastUpdated(LocalDateTime.now(ET_ZONE));

                        log.info("[DAILY-LEVELS][{}] ✓ Today's open captured: ${}", symbol, openPrice);
                        log.info("[DAILY-LEVELS][{}]   Today Open: ${}", symbol, levels.getTodayOpen());
                        log.info("[DAILY-LEVELS][{}]   Today High: ${}", symbol, levels.getTodayHigh());
                        log.info("[DAILY-LEVELS][{}]   Today Low:  ${}", symbol, levels.getTodayLow());

                        // Log gap analysis
                        logGapAnalysis(symbol, levels);
                    }
                }
            } catch (Exception e) {
                log.error("[DAILY-LEVELS][{}] Error capturing today's open: {}", symbol, e.getMessage());
            }
        }
    }

    /**
     * Calculate gap between today's open and previous close
     */
    private void calculateGap(DailyLevels levels) {
        if (levels.getTodayOpen() == null || levels.getPreviousDayClose() == null) {
            log.warn("[DAILY-LEVELS][{}] Cannot calculate gap - missing data", levels.getSymbol());
            return;
        }

        BigDecimal gap = levels.getTodayOpen().subtract(levels.getPreviousDayClose());
        BigDecimal gapPercent = gap.divide(levels.getPreviousDayClose(), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        levels.setGap(gap);
        levels.setGapPercent(gapPercent);
        levels.setGapUp(gap.compareTo(BigDecimal.ZERO) > 0);
        levels.setGapDown(gap.compareTo(BigDecimal.ZERO) < 0);

        // Consider gap significant if > 0.1%
        boolean significantGap = gapPercent.abs().compareTo(new BigDecimal("0.1")) > 0;
        levels.setHasUnfilledGap(significantGap);
    }

    /**
     * Log detailed gap analysis
     */
    private void logGapAnalysis(String symbol, DailyLevels levels) {
        if (levels.getGap() == null) {
            return;
        }

        log.info("[DAILY-LEVELS][{}] ========== GAP ANALYSIS ==========", symbol);

        if (levels.isGapUp()) {
            log.info("[DAILY-LEVELS][{}] ⬆️ OVERNIGHT GAP UP DETECTED", symbol);
            log.info("[DAILY-LEVELS][{}]   Gap Size: +${} (+{}%)", symbol,
                    levels.getGap().setScale(2, RoundingMode.HALF_UP),
                    levels.getGapPercent().setScale(2, RoundingMode.HALF_UP));
            log.info("[DAILY-LEVELS][{}]   Gap Fill Target: ${} (Previous Day Close)", symbol, levels.getPreviousDayClose());
            log.info("[DAILY-LEVELS][{}]   First Support: ${} (Previous Day High)", symbol, levels.getPreviousDayHigh());
        } else if (levels.isGapDown()) {
            log.info("[DAILY-LEVELS][{}] ⬇️ OVERNIGHT GAP DOWN DETECTED", symbol);
            log.info("[DAILY-LEVELS][{}]   Gap Size: ${} ({}%)", symbol,
                    levels.getGap().setScale(2, RoundingMode.HALF_UP),
                    levels.getGapPercent().setScale(2, RoundingMode.HALF_UP));
            log.info("[DAILY-LEVELS][{}]   Gap Fill Target: ${} (Previous Day Close)", symbol, levels.getPreviousDayClose());
            log.info("[DAILY-LEVELS][{}]   First Resistance: ${} (Previous Day Low)", symbol, levels.getPreviousDayLow());
        } else {
            log.info("[DAILY-LEVELS][{}] ↔️ NO SIGNIFICANT OVERNIGHT GAP", symbol);
            log.info("[DAILY-LEVELS][{}]   Opened near previous close: ${}", symbol, levels.getPreviousDayClose());
        }

        log.info("[DAILY-LEVELS][{}] ===================================", symbol);
    }

    /**
     * Update today's high/low throughout the trading day
     */
    @Scheduled(fixedDelay = 30000) // Every 30 seconds
    public void updateTodayHighLow() {
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isBefore(LocalTime.of(9, 31)) || now.isAfter(LocalTime.of(16, 0))) {
            return;
        }

        for (String symbol : dailyLevelsMap.keySet()) {
            try {
                var quoteResponse = tradierService.getQuote(symbol);
                if (quoteResponse != null && quoteResponse.getQuote() != null) {
                    BigDecimal currentPrice = quoteResponse.getQuote().getLast();
                    DailyLevels levels = dailyLevelsMap.get(symbol);

                    if (levels != null && currentPrice != null && levels.isInitialized()) {
                        boolean updated = false;

                        // Update today's high
                        if (levels.getTodayHigh() == null || currentPrice.compareTo(levels.getTodayHigh()) > 0) {
                            BigDecimal oldHigh = levels.getTodayHigh();
                            levels.setTodayHigh(currentPrice);
                            log.info("[DAILY-LEVELS][{}] 📈 NEW SESSION HIGH: ${} (previous: ${})",
                                    symbol, currentPrice, oldHigh);
                            updated = true;
                        }

                        // Update today's low
                        if (levels.getTodayLow() == null || currentPrice.compareTo(levels.getTodayLow()) < 0) {
                            BigDecimal oldLow = levels.getTodayLow();
                            levels.setTodayLow(currentPrice);
                            log.info("[DAILY-LEVELS][{}] 📉 NEW SESSION LOW: ${} (previous: ${})",
                                    symbol, currentPrice, oldLow);
                            updated = true;
                        }

                        // Update gap fill status
                        levels.updateGapStatus(currentPrice);

                        if (updated) {
                            levels.setLastUpdated(LocalDateTime.now(ET_ZONE));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[DAILY-LEVELS][{}] Error updating high/low: {}", symbol, e.getMessage());
            }
        }
    }

    /**
     * Get daily levels for a symbol
     */
    public DailyLevels getDailyLevels(String symbol) {
        DailyLevels levels = dailyLevelsMap.get(symbol);

        if (levels == null) {
            log.warn("[DAILY-LEVELS][{}] No daily levels found - initializing now", symbol);
            initializeSymbolLevels(symbol, LocalDate.now(ET_ZONE));
            levels = dailyLevelsMap.get(symbol);
        }

        if (levels != null && !levels.isInitialized()) {
            log.warn("[DAILY-LEVELS][{}] Daily levels not fully initialized (missing today's open)", symbol);
        }

        return levels;
    }

    /**
     * Get daily levels for QQQ (primary symbol)
     */
    public DailyLevels getQQQDailyLevels() {
        return getDailyLevels(PRIMARY_SYMBOL);
    }

    /**
     * Check if daily levels are ready for trading
     */
    public boolean areLevelsReady(String symbol) {
        DailyLevels levels = dailyLevelsMap.get(symbol);
        return levels != null && levels.isInitialized() &&
                levels.getPreviousDayHigh() != null &&
                levels.getPreviousDayLow() != null &&
                levels.getPreviousDayClose() != null &&
                levels.getTodayOpen() != null;
    }

    /**
     * Get previous trading day (skip weekends)
     */
    private LocalDate getPreviousTradingDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);

        // Skip weekends
        while (previous.getDayOfWeek().getValue() > 5) {
            previous = previous.minusDays(1);
        }

        // Note: This doesn't account for market holidays
        // For production, you'd want to check against a holiday calendar
        return previous;
    }

    /**
     * Clear daily levels at market close
     */
    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "America/New_York")
    public void clearDailyLevels() {
        log.info("[DAILY-LEVELS] Clearing daily levels at market close");
        log.info("[DAILY-LEVELS] Final levels summary:");

        for (Map.Entry<String, DailyLevels> entry : dailyLevelsMap.entrySet()) {
            DailyLevels levels = entry.getValue();
            log.info("[DAILY-LEVELS][{}] Session Summary - Open: ${}, High: ${}, Low: ${}, Gap: ${}",
                    entry.getKey(),
                    levels.getTodayOpen(),
                    levels.getTodayHigh(),
                    levels.getTodayLow(),
                    levels.getGap());
        }

        dailyLevelsMap.clear();
        lastInitDate = null;
    }

    /**
     * Force re-initialization (useful for testing or recovery)
     */
    public void forceReinitialize() {
        log.info("[DAILY-LEVELS] Force re-initializing daily levels");
        lastInitDate = null;
        dailyLevelsMap.clear();
        initializeDailyLevels();
    }

    /**
     * Log current state of all daily levels
     */
    public void logCurrentLevels() {
        log.info("[DAILY-LEVELS] ========== CURRENT DAILY LEVELS ==========");

        for (Map.Entry<String, DailyLevels> entry : dailyLevelsMap.entrySet()) {
            String symbol = entry.getKey();
            DailyLevels levels = entry.getValue();

            log.info("[DAILY-LEVELS][{}] Previous Day - High: ${}, Low: ${}, Close: ${}",
                    symbol, levels.getPreviousDayHigh(), levels.getPreviousDayLow(), levels.getPreviousDayClose());
            log.info("[DAILY-LEVELS][{}] Today        - Open: ${}, High: ${}, Low: ${}",
                    symbol, levels.getTodayOpen(), levels.getTodayHigh(), levels.getTodayLow());
            log.info("[DAILY-LEVELS][{}] Gap          - Size: ${} ({}%), Unfilled: {}",
                    symbol, levels.getGap(), levels.getGapPercent(), levels.isHasUnfilledGap());
        }

        log.info("[DAILY-LEVELS] ============================================");
    }
}