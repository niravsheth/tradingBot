package com.tradingBot.config;

import com.tradingBot.entity.MarketData;
import com.tradingBot.repository.MarketDataRepository;
import com.tradingBot.service.TradierService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class DataQualityConfiguration {

    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final List<String> TRACKED_SYMBOLS = List.of("QQQ", "AAPL", "MSFT", "NVDA");

    @PostConstruct
    public void initializeDataQuality() {
        log.info("[DATA-QUALITY] Initializing data quality checks...");

        // Check and fix any NULL data on startup
        cleanupNullData();

        // Verify data completeness
        verifyDataCompleteness();

        log.info("[DATA-QUALITY] Data quality initialization complete");
    }

    /**
     * Clean up NULL data in the database
     */
    @Transactional
    public void cleanupNullData() {
        try {
            log.info("[DATA-QUALITY] Starting NULL data cleanup...");

            for (String symbol : TRACKED_SYMBOLS) {
                // Get records with NULL OHLC data
                List<MarketData> nullRecords = marketDataRepository.findRecentData(symbol, 1000)
                        .stream()
                        .filter(bar -> !bar.isComplete())
                        .toList();

                if (!nullRecords.isEmpty()) {
                    log.warn("[DATA-QUALITY] Found {} incomplete records for {}",
                            nullRecords.size(), symbol);

                    // Fix or delete incomplete records
                    for (MarketData bar : nullRecords) {
                        if (canFixBar(bar)) {
                            fixBar(bar);
                            marketDataRepository.save(bar);
                            log.debug("[DATA-QUALITY] Fixed bar for {} at {}",
                                    symbol, bar.getTimestamp());
                        } else {
                            marketDataRepository.delete(bar);
                            log.debug("[DATA-QUALITY] Deleted unfixable bar for {} at {}",
                                    symbol, bar.getTimestamp());
                        }
                    }
                }
            }

            log.info("[DATA-QUALITY] NULL data cleanup complete");

        } catch (Exception e) {
            log.error("[DATA-QUALITY] Error during NULL data cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if a bar can be fixed
     */
    private boolean canFixBar(MarketData bar) {
        // Bar can be fixed if it has at least price or close
        return bar.getPrice() != null || bar.getClose() != null;
    }

    /**
     * Fix a bar with missing data
     */
    private void fixBar(MarketData bar) {
        // Use close or price as base
        BigDecimal basePrice = bar.getClose() != null ? bar.getClose() : bar.getPrice();

        if (basePrice == null) {
            return; // Cannot fix
        }

        // Fix OHLC if missing
        if (bar.getOpen() == null) {
            bar.setOpen(basePrice);
        }
        if (bar.getHigh() == null) {
            bar.setHigh(basePrice.multiply(new BigDecimal("1.0001")));
        }
        if (bar.getLow() == null) {
            bar.setLow(basePrice.multiply(new BigDecimal("0.9999")));
        }
        if (bar.getClose() == null) {
            bar.setClose(basePrice);
        }
        if (bar.getPrice() == null) {
            bar.setPrice(bar.getClose());
        }

        // Fix volume if missing
        if (bar.getVolume() == null || bar.getVolume().compareTo(0L) <= 0) {
            bar.setVolume(Long.valueOf(10000));
        }

        // Mark as fallback
        bar.setIsFallback(true);
        bar.setDataSource("FIXED");
    }

    /**
     * Verify data completeness for recent periods
     */
    private void verifyDataCompleteness() {
        LocalDateTime now = LocalDateTime.now(ET_ZONE);
        LocalTime marketTime = now.toLocalTime();

        // Only check during market hours
        if (marketTime.isBefore(LocalTime.of(9, 30)) || marketTime.isAfter(LocalTime.of(16, 0))) {
            return;
        }

        LocalDateTime checkFrom = now.minusHours(1);

        for (String symbol : TRACKED_SYMBOLS) {
            List<MarketData> recentData = marketDataRepository
                    .findBySymbolAndTimestampBetween(symbol, checkFrom, now);

            long completeRecords = recentData.stream()
                    .filter(MarketData::isComplete)
                    .count();

            double completenessRatio = recentData.isEmpty() ? 0 :
                    (double) completeRecords / recentData.size();

            if (completenessRatio < 0.8) {
                log.warn("[DATA-QUALITY] Low data completeness for {}: {}% ({}/{})",
                        symbol,
                        (int)(completenessRatio * 100),
                        completeRecords,
                        recentData.size());

                // Attempt to fill gaps
                fillDataGaps(symbol, checkFrom, now);
            }
        }
    }

    /**
     * Fill data gaps with current quotes
     */
    @Transactional
    public void fillDataGaps(String symbol, LocalDateTime from, LocalDateTime to) {
        try {
            // Get current quote
            var quote = tradierService.getQuote(symbol);
            if (quote == null || quote.getQuote() == null || quote.getQuote().getLast() == null) {
                log.warn("[DATA-QUALITY] Cannot fill gaps for {} - no quote available", symbol);
                return;
            }

            BigDecimal currentPrice = quote.getQuote().getLast();
            BigDecimal volume = quote.getQuote().getVolume() != null ?
                    BigDecimal.valueOf(quote.getQuote().getVolume()) : BigDecimal.valueOf(10000);

            // Find missing minutes
            LocalDateTime current = from.withSecond(0).withNano(0);
            int gapsFilled = 0;

            while (current.isBefore(to)) {
                // Check if we have data for this minute
                List<MarketData> existing = marketDataRepository
                        .findBySymbolAndTimestampBetween(symbol, current, current.plusMinutes(1));

                if (existing.isEmpty()) {
                    // Create synthetic bar
                    MarketData bar = new MarketData();
                    bar.setSymbol(symbol);
                    bar.setTimestamp(current);
                    bar.setOpen(currentPrice);
                    bar.setHigh(currentPrice.multiply(new BigDecimal("1.0001")));
                    bar.setLow(currentPrice.multiply(new BigDecimal("0.9999")));
                    bar.setClose(currentPrice);
                    bar.setPrice(currentPrice);
                    bar.setVolume(volume.divide(BigDecimal.valueOf(60), RoundingMode.HALF_UP).longValue());
                    bar.setTickCount(1);
                    bar.setIsFallback(true);
                    bar.setDataSource("GAP_FILL");

                    marketDataRepository.save(bar);
                    gapsFilled++;
                }

                current = current.plusMinutes(1);
            }

            if (gapsFilled > 0) {
                log.info("[DATA-QUALITY] Filled {} data gaps for {}", gapsFilled, symbol);
            }

        } catch (Exception e) {
            log.error("[DATA-QUALITY] Error filling gaps for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Periodic data quality check (every 5 minutes during market hours)
     */
    @Scheduled(cron = "0 */5 9-16 * * MON-FRI")
    public void periodicDataQualityCheck() {
        LocalTime now = LocalTime.now(ET_ZONE);

        // Skip if market is closed
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(16, 0))) {
            return;
        }

        log.debug("[DATA-QUALITY] Running periodic data quality check");

        // Check for recent NULL data
        for (String symbol : TRACKED_SYMBOLS) {
            List<MarketData> recentBars = marketDataRepository.findRecentData(symbol, 10);

            long nullCount = recentBars.stream()
                    .filter(bar -> !bar.isComplete())
                    .count();

            if (nullCount > 0) {
                log.warn("[DATA-QUALITY] Found {} incomplete bars for {} in last 10 minutes",
                        nullCount, symbol);

                // Trigger cleanup
                cleanupNullData();
            }
        }
    }

    /**
     * Daily data quality report
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI")
    public void dailyDataQualityReport() {
        log.info("[DATA-QUALITY] ===== Daily Data Quality Report =====");

        LocalDateTime today = LocalDateTime.now(ET_ZONE).withHour(9).withMinute(30);
        LocalDateTime now = LocalDateTime.now(ET_ZONE);

        for (String symbol : TRACKED_SYMBOLS) {
            List<MarketData> todayData = marketDataRepository
                    .findBySymbolAndTimestampBetween(symbol, today, now);

            long totalRecords = todayData.size();
            long completeRecords = todayData.stream().filter(MarketData::isComplete).count();
            long fallbackRecords = todayData.stream()
                    .filter(bar -> Boolean.TRUE.equals(bar.getIsFallback()))
                    .count();

            double completenessRate = totalRecords > 0 ?
                    (double) completeRecords / totalRecords * 100 : 0;
            double fallbackRate = totalRecords > 0 ?
                    (double) fallbackRecords / totalRecords * 100 : 0;

            log.info("[DATA-QUALITY] {} - Records: {}, Complete: {}%, Fallback: {}%",
                    symbol, totalRecords,
                    String.format("%.1f", completenessRate),
                    String.format("%.1f", fallbackRate));

            if (completenessRate < 95) {
                log.warn("[DATA-QUALITY] {} has low completeness rate: {}%",
                        symbol, String.format("%.1f", completenessRate));
            }

            if (fallbackRate > 10) {
                log.warn("[DATA-QUALITY] {} has high fallback rate: {}%",
                        symbol, String.format("%.1f", fallbackRate));
            }
        }

        log.info("[DATA-QUALITY] ===== End of Report =====");
    }
}