package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.model.Quote;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BarAggregationService {

    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final List<String> TRACKED_SYMBOLS = Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA");

    // Accumulator for 1-minute bars
    private final Map<String, BarAccumulator> accumulators = new ConcurrentHashMap<>();

    private final Map<String, Long> previousCumulativeVolumes = new ConcurrentHashMap<>();


    @Data
    private static class BarAccumulator {
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private LocalDateTime startTime;
        private int tickCount = 0;
        private BigDecimal vwapNumerator = BigDecimal.ZERO;
        private BigDecimal vwapDenominator = BigDecimal.ZERO;
        private BigDecimal lastValidPrice;
        private Long lastCumulativeVolume = 0L;
        private Long barStartCumulativeVolume = null;



        public void addTick(BigDecimal price, Long cumulativeVolume) {
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                log.debug("Skipping invalid tick price: {}", price);
                return;
            }

            // Initialize or update OHLC
            if (open == null) {
                open = price;
                high = price;
                low = price;
                startTime = LocalDateTime.now();
                // Set bar start volume on first tick
                if (cumulativeVolume != null && cumulativeVolume > 0) {
                    barStartCumulativeVolume = cumulativeVolume;
                    lastCumulativeVolume = cumulativeVolume; // FIXED: Initialize to prevent massive first increment
                }
            } else {
                high = high.max(price);
                low = low.min(price);
            }

            close = price;
            lastValidPrice = price;
            tickCount++;

            // Calculate incremental volume for this tick
            if (cumulativeVolume != null && cumulativeVolume > 0 && lastCumulativeVolume != null) {
                Long incrementalVolume = cumulativeVolume - lastCumulativeVolume;
                if (incrementalVolume > 0) {
                    BigDecimal volumeBD = BigDecimal.valueOf(incrementalVolume);
                    vwapNumerator = vwapNumerator.add(price.multiply(volumeBD));
                    vwapDenominator = vwapDenominator.add(volumeBD);
                }
                lastCumulativeVolume = cumulativeVolume;
            }
        }


        public BigDecimal getVWAP() {
            if (vwapDenominator.compareTo(BigDecimal.ZERO) > 0) {
                return vwapNumerator.divide(vwapDenominator, 4, RoundingMode.HALF_UP);
            }
            return close != null ? close : lastValidPrice;
        }

        public boolean isValid() {
            return open != null && high != null && low != null && close != null && tickCount > 0;
        }

        public Long getFinalCumulativeVolume() {
            return lastCumulativeVolume;
        }

        public Long getCurrentIncrementalVolume() {
            if (barStartCumulativeVolume == null || lastCumulativeVolume == null) {
                return null;
            }
            return Math.max(0L, lastCumulativeVolume - barStartCumulativeVolume);
        }
    }

    /**
     * Accumulate tick data every 10 seconds during market hours
     */
    @Scheduled(fixedDelay = 10000)
    public void accumulateTicks() {
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(16, 0))) {
            return;
        }

        for (String symbol : TRACKED_SYMBOLS) {
            try {
                QuoteResponse quoteResponse = tradierService.getQuote(symbol);
                if (quoteResponse != null && quoteResponse.getQuote() != null) {
                    Quote quote = quoteResponse.getQuote();

                    BigDecimal price = quote.getLast();
                    Long cumulativeVolume = quote.getVolume();

                    if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                        BarAccumulator acc = accumulators.computeIfAbsent(symbol, k -> new BarAccumulator());
                        acc.addTick(price, cumulativeVolume);

                        log.debug("[BAR-ACCUMULATE] {} @ {} V={} (samples: {})",
                                symbol, price, cumulativeVolume, acc.tickCount);
                    }
                }
            } catch (Exception e) {
                log.error("[BAR-ACCUMULATE] Error getting quote for {}: {}", symbol, e.getMessage());
            }
        }
    }


    /**
     * Finalize bars every minute
     */
    @Scheduled(cron = "0 * 9-16 * * MON-FRI")
    @Transactional
    public void finalizeBars() {

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime barTime = now.truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);

        log.info("[BAR-FINALIZE] Finalizing bars for minute: {}", barTime);

        for (String symbol : TRACKED_SYMBOLS) {
            BarAccumulator acc = accumulators.remove(symbol);

            if (acc == null || !acc.isValid()) {
                createFallbackBar(symbol, barTime);
            } else {
                saveBar(symbol, acc, barTime);
            }
        }
    }

    /**
     * FIXED: Save a bar to the database with proper validation and volume tracking initialization
     */
    private void saveBar(String symbol, BarAccumulator acc, LocalDateTime timestamp) {
        try {
            MarketData bar = new MarketData();
            bar.setSymbol(symbol);
            bar.setTimestamp(timestamp);
            bar.setOpen(acc.open);
            bar.setHigh(acc.high);
            bar.setLow(acc.low);
            bar.setClose(acc.close);

            Long currentCumulativeVolume = acc.getFinalCumulativeVolume();

            // FIXED: Initialize volume tracking at market open if not present
            if (!previousCumulativeVolumes.containsKey(symbol)) {
                log.info("[BAR-SAVE] Initializing volume tracking for {} at {}", symbol, currentCumulativeVolume);
                previousCumulativeVolumes.put(symbol, 0L);
            }

            Long previousCumulativeVolume = previousCumulativeVolumes.get(symbol);
            Long incrementalVolume = Math.max(0L, currentCumulativeVolume - previousCumulativeVolume);

            // FIXED: Sanity check - if incremental volume is unreasonably large, use accumulator's calculation
            if (incrementalVolume > currentCumulativeVolume / 2) {
                Long accumulatorVolume = acc.getCurrentIncrementalVolume();
                if (accumulatorVolume != null && accumulatorVolume > 0 && accumulatorVolume < incrementalVolume) {
                    log.warn("[BAR-SAVE] Incremental volume too large ({} vs cumulative {}), using accumulator: {}",
                            incrementalVolume, currentCumulativeVolume, accumulatorVolume);
                    incrementalVolume = accumulatorVolume;
                }
            }

            previousCumulativeVolumes.put(symbol, currentCumulativeVolume);

            bar.setVolume(currentCumulativeVolume);
            bar.setIncrementalVolume(incrementalVolume);
            bar.setPrice(acc.close);

            if (!validateOHLC(bar)) {
                log.error("[BAR-SAVE] Invalid OHLC data for {}: O={}, H={}, L={}, C={}",
                        symbol, bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose());
                return;
            }

            bar.setVwap(acc.getVWAP());
            bar.setTickCount(acc.tickCount);
            bar.setIsFallback(false);
            bar.setDataSource("REAL-TIME");

            marketDataRepository.save(bar);

            log.info("[BAR-SAVE] Saved {} bar: O={}, H={}, L={}, C={}, VWAP={}, V={}, IncrV={}, ticks={}",
                    symbol,
                    bar.getOpen().setScale(2, RoundingMode.HALF_UP),
                    bar.getHigh().setScale(2, RoundingMode.HALF_UP),
                    bar.getLow().setScale(2, RoundingMode.HALF_UP),
                    bar.getClose().setScale(2, RoundingMode.HALF_UP),
                    bar.getVwap().setScale(4, RoundingMode.HALF_UP),
                    bar.getVolume(),
                    bar.getIncrementalVolume(),
                    bar.getTickCount());

        } catch (Exception e) {
            log.error("[BAR-SAVE] Error saving bar for {}: {}", symbol, e.getMessage(), e);
        }
    }

    /**
     * Create fallback bar when accumulator data is missing
     */
    private void createFallbackBar(String symbol, LocalDateTime timestamp) {
        try {
            QuoteResponse quoteResponse = tradierService.getQuote(symbol);
            if (quoteResponse == null || quoteResponse.getQuote() == null) {
                log.error("[FALLBACK] Cannot create fallback bar - no quote for {}", symbol);
                return;
            }

            Quote quote = quoteResponse.getQuote();
            BigDecimal price = quote.getLast();
            Long volume = quote.getVolume();

            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("[FALLBACK] Invalid price for fallback bar: {}", price);
                return;
            }

            MarketData bar = new MarketData();
            bar.setSymbol(symbol);
            bar.setTimestamp(timestamp);
            bar.setOpen(price);
            bar.setHigh(price.multiply(new BigDecimal("1.0001")));
            bar.setLow(price.multiply(new BigDecimal("0.9999")));
            BigDecimal closeVariation = Math.random() > 0.5 ? new BigDecimal("1.0002") : new BigDecimal("0.9998");
            bar.setClose(price.multiply(closeVariation).setScale(2, RoundingMode.HALF_UP));
            Long fallbackVolume = volume != null && volume > 0 ? volume : 10000L;
            bar.setVolume(fallbackVolume);
            bar.setIncrementalVolume(fallbackVolume / 60);
            bar.setPrice(price);
            bar.setVwap(price);
            bar.setTickCount(1);
            bar.setIsFallback(true);
            bar.setDataSource("FALLBACK");

            marketDataRepository.save(bar);

            log.warn("[FALLBACK] Created fallback bar for {} @ {}", symbol, price);

        } catch (Exception e) {
            log.error("[FALLBACK] Failed to create fallback bar for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Validate OHLC relationships
     */
    private boolean validateOHLC(MarketData bar) {
        if (bar.getOpen() == null || bar.getHigh() == null ||
                bar.getLow() == null || bar.getClose() == null) {
            return false;
        }

        if (bar.getHigh().compareTo(bar.getOpen()) < 0 ||
                bar.getHigh().compareTo(bar.getLow()) < 0 ||
                bar.getHigh().compareTo(bar.getClose()) < 0) {
            return false;
        }

        if (bar.getLow().compareTo(bar.getOpen()) > 0 ||
                bar.getLow().compareTo(bar.getHigh()) > 0 ||
                bar.getLow().compareTo(bar.getClose()) > 0) {
            return false;
        }

        return true;
    }

    /**
     * Get recent bars with NULL filtering and fallback creation
     */
    public List<MarketData> getRecentBars(String symbol, int count) {
        log.info("[BAR-QUERY][{}] Querying bars for {}, startTime will be: {}",
                 count, symbol, LocalDateTime.now().minusMinutes(count * 3));
        String queryId = UUID.randomUUID().toString().substring(0, 8);

        try {
            List<MarketData> allBars = marketDataRepository.findRecentData(symbol, count * 3);

            List<MarketData> validBars = allBars.stream()
                    .filter(bar -> bar.getOpen() != null &&
                            bar.getHigh() != null &&
                            bar.getLow() != null &&
                            bar.getClose() != null &&
                            bar.getVolume() != null &&
                            bar.getVolume() > 0 &&
                            bar.getOpen().compareTo(bar.getClose()) != 0)  // ADD THIS: exclude doji bars
                    .sorted(Comparator.comparing(MarketData::getTimestamp))
                    .collect(Collectors.toList());

            int invalidCount = allBars.size() - validBars.size();
            if (invalidCount > 0) {
                log.warn("[BAR-QUERY][{}] FILTERED OUT {} bad bars with NULL fields for {}",
                        queryId, invalidCount, symbol);
            }

            if (validBars.size() < count) {
                log.warn("[BAR-QUERY][{}] Only {} valid bars found, need {}. Creating emergency bars.",
                        queryId, validBars.size(), count);
                return createEmergencyBars(symbol, count, queryId);
            }
            if (!validBars.isEmpty()) {
                MarketData oldest = validBars.get(0);
                MarketData newest = validBars.get(validBars.size() - 1);
                log.info("[BAR-QUERY][{}] Found {} valid bars, range: {} to {}",
                        queryId, validBars.size(), oldest.getTimestamp(), newest.getTimestamp());
                log.info("[BAR-QUERY][{}] Sample newest bar: O={}, C={}, isFallback={}",
                        queryId, newest.getOpen(), newest.getClose(), newest.getIsFallback());
            }
            return validBars.subList(Math.max(0, validBars.size() - count), validBars.size());

        } catch (Exception e) {
            log.error("[BAR-QUERY][{}] Error getting bars for {}: {}", queryId, symbol, e.getMessage());
            return createEmergencyBars(symbol, count, queryId);
        }
    }

    /**
     * Create emergency bars when database has insufficient data
     */
    private List<MarketData> createEmergencyBars(String symbol, int count, String queryId) {
        log.warn("[EMERGENCY] Creating {} fallback bars for {} from current quote", count, symbol);

        List<MarketData> emergencyBars = new ArrayList<>();

        try {
            QuoteResponse quoteResponse = tradierService.getQuote(symbol);
            if (quoteResponse == null || quoteResponse.getQuote() == null) {
                log.error("[EMERGENCY] Cannot create emergency bars - no quote for {}", symbol);
                return emergencyBars;
            }

            Quote quote = quoteResponse.getQuote();
            BigDecimal basePrice = quote.getLast();
            Long baseVolume = quote.getVolume();

            if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("[EMERGENCY] Invalid base price for emergency bars: {}", basePrice);
                return emergencyBars;
            }

            LocalDateTime now = LocalDateTime.now();

            for (int i = count - 1; i >= 0; i--) {
                MarketData bar = new MarketData();
                bar.setSymbol(symbol);
                bar.setTimestamp(now.minusMinutes(i));

                double variation = 0.9995 + Math.random() * 0.001;
                BigDecimal price = basePrice.multiply(BigDecimal.valueOf(variation))
                        .setScale(2, RoundingMode.HALF_UP);

                bar.setOpen(price);
                bar.setHigh(price.multiply(new BigDecimal("1.0002")));
                bar.setLow(price.multiply(new BigDecimal("0.9998")));
                bar.setClose(price);
                Long estimatedVolume = baseVolume != null && baseVolume > 0 ? baseVolume / 60 : 10000L;
                bar.setVolume(estimatedVolume);
                bar.setIncrementalVolume(estimatedVolume);
                bar.setPrice(price);
                bar.setVwap(price);
                bar.setTickCount(10);
                bar.setIsFallback(true);
                bar.setDataSource("EMERGENCY");

                emergencyBars.add(bar);
            }

            log.info("[EMERGENCY] Created {} fallback bars for {} @ {}", count, symbol, basePrice);

        } catch (Exception e) {
            log.error("[EMERGENCY] Failed to create emergency bars: {}", e.getMessage());
        }

        return emergencyBars;
    }

    /**
     * Get bars for a specific time range
     */
    public List<MarketData> getBarsForTimeRange(String symbol, LocalDateTime start, LocalDateTime end) {
        try {
            List<MarketData> bars = marketDataRepository.findBySymbolAndTimestampBetween(symbol, start, end);

            return bars.stream()
                    .filter(bar -> bar.getOpen() != null &&
                            bar.getHigh() != null &&
                            bar.getLow() != null &&
                            bar.getClose() != null)
                    .sorted(Comparator.comparing(MarketData::getTimestamp))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error getting bars for {} between {} and {}: {}",
                    symbol, start, end, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * FIXED: Clear accumulators and reset volume tracking at market close
     */
    @Scheduled(cron = "0 5 16 * * MON-FRI")
    public void clearAccumulators() {
        log.info("[BAR-CLEANUP] Clearing {} accumulators at market close", accumulators.size());
        accumulators.clear();

        // FIXED: Reset volume tracking for next trading day
        log.info("[BAR-CLEANUP] Resetting volume tracking for {} symbols", previousCumulativeVolumes.size());
        previousCumulativeVolumes.clear();
    }

    /**
     * Get current incremental volume from active accumulator
     */
    public Long getCurrentIncrementalVolume(String symbol) {
        BarAccumulator acc = accumulators.get(symbol);
        if (acc == null) {
            return null;
        }
        return acc.getCurrentIncrementalVolume();
    }

    /**
     * Check if accumulator exists and has recent data
     */
    public boolean hasActiveAccumulator(String symbol) {
        BarAccumulator acc = accumulators.get(symbol);
        if (acc == null || acc.startTime == null) {
            return false;
        }
        return Duration.between(acc.startTime, LocalDateTime.now()).getSeconds() < 30;
    }
}