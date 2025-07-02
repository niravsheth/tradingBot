package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
@RequiredArgsConstructor
public class AdvancedTrendDetector {

    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;

    private final AtomicReference<String> currentTrend = new AtomicReference<>("NEUTRAL");
    private final ConcurrentHashMap<String, String> symbolTrends = new ConcurrentHashMap<>();

    public String detectTrend(String symbol) {
        try {
            // 1. Get market internals (with fallback if not available)
            MarketInternals internals = getMarketInternals();

            // 2. Volume-confirmed price action
            VolumeProfile volumeProfile = analyzeVolumeProfile(symbol);

            // 3. Accumulation/Distribution
            double adLine = calculateAccumulationDistribution(symbol);

            // 4. Smart money flow
            double smartMoneyFlow = calculateSmartMoneyFlow(symbol);

            // 5. Smoothed trend score
            double rawScore = 0.0;

            // Market internals (25% weight) - only if available
            if (internals.hasValidData()) {
                if (internals.getAdvanceDeclineRatio() > 2.0) rawScore += 2.5;
                else if (internals.getAdvanceDeclineRatio() < 0.5) rawScore -= 2.5;

                if (internals.getUpVolume() > internals.getDownVolume() * 1.5) rawScore += 2.0;
                else if (internals.getDownVolume() > internals.getUpVolume() * 1.5) rawScore -= 2.0;
            } else {
                // If no internals, give more weight to other factors
                rawScore *= 1.33; // Compensate for missing 25% weight
            }

            // Volume profile (25% weight)
            if (volumeProfile.isBullishVolume()) rawScore += 2.5;
            else if (volumeProfile.isBearishVolume()) rawScore -= 2.5;

            // A/D Line (25% weight)
            if (adLine > 0.5) rawScore += 2.5;
            else if (adLine < -0.5) rawScore -= 2.5;

            // Smart money (25% weight)
            if (smartMoneyFlow > 0.3) rawScore += 2.5;
            else if (smartMoneyFlow < -0.3) rawScore -= 2.5;

            // Apply EMA smoothing to reduce noise
            String key = symbol + "_trend";
            Double previousEma = emaValues.get(key);
            double smoothedScore;

            if (previousEma == null) {
                smoothedScore = rawScore;
            } else {
                smoothedScore = EMA_ALPHA * rawScore + (1 - EMA_ALPHA) * previousEma;
            }

            emaValues.put(key, smoothedScore);

            // Determine trend with hysteresis
            String previousTrend = getPreviousTrend(symbol);
            String newTrend;

            // Use different thresholds to prevent flip-flopping
            if ("UP".equals(previousTrend)) {
                newTrend = smoothedScore < -3.5 ? "DOWN" : (smoothedScore < -1.0 ? "NEUTRAL" : "UP");
            } else if ("DOWN".equals(previousTrend)) {
                newTrend = smoothedScore > 3.5 ? "UP" : (smoothedScore > 1.0 ? "NEUTRAL" : "DOWN");
            } else {
                newTrend = smoothedScore > 3.0 ? "UP" : (smoothedScore < -3.0 ? "DOWN" : "NEUTRAL");
            }

            log.info("[TREND] {} - Raw: {:.1f}, Smoothed: {:.1f}, Trend: {} -> {}",
                    symbol, rawScore, smoothedScore, previousTrend, newTrend);

            // Store the trend
            symbolTrends.put(symbol, newTrend);
            previousTrends.put(symbol, newTrend);

            return newTrend;

        } catch (Exception e) {
            log.error("Advanced trend detection failed: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    private MarketInternals getMarketInternals() {
        MarketInternals internals = new MarketInternals();

        try {
            // Try to get TICK - but handle if not available
            try {
                QuoteResponse tickResponse = tradierService.getQuote("$TICK");
                if (tickResponse != null && tickResponse.getQuote() != null && tickResponse.getQuote().getLast() != null) {
                    internals.setTick(tickResponse.getQuote().getLast().intValue());
                    internals.setHasData(true);
                }
            } catch (Exception e) {
                log.debug("TICK data not available: {}", e.getMessage());
            }

            // Try to get advance/decline - but handle if not available
            try {
                QuoteResponse addResponse = tradierService.getQuote("$ADD");
                if (addResponse != null && addResponse.getQuote() != null && addResponse.getQuote().getLast() != null) {
                    internals.setAdvanceDecline(addResponse.getQuote().getLast().intValue());
                    internals.setHasData(true);
                }
            } catch (Exception e) {
                log.debug("ADD data not available: {}", e.getMessage());
            }

            // Try to get up/down volume - but handle if not available
            try {
                QuoteResponse voldResponse = tradierService.getQuote("$VOLD");
                if (voldResponse != null && voldResponse.getQuote() != null && voldResponse.getQuote().getLast() != null) {
                    internals.setDownVolume(voldResponse.getQuote().getLast().longValue());
                    internals.setHasData(true);
                }
            } catch (Exception e) {
                log.debug("VOLD data not available: {}", e.getMessage());
            }

            try {
                QuoteResponse voluResponse = tradierService.getQuote("$VOLU");
                if (voluResponse != null && voluResponse.getQuote() != null && voluResponse.getQuote().getLast() != null) {
                    internals.setUpVolume(voluResponse.getQuote().getLast().longValue());
                    internals.setHasData(true);
                }
            } catch (Exception e) {
                log.debug("VOLU data not available: {}", e.getMessage());
            }

            // If we couldn't get market internals, try to infer from SPY/QQQ breadth
            if (!internals.hasValidData()) {
                log.debug("Market internals not available from standard symbols, using fallback method");
                internals = inferMarketInternalsFromETFs();
            }

        } catch (Exception e) {
            log.debug("Market internals fetch failed: {}", e.getMessage());
        }

        return internals;
    }

    private MarketInternals inferMarketInternalsFromETFs() {
        MarketInternals internals = new MarketInternals();

        try {
            // Use SPY and QQQ data as proxy for market internals
            QuoteResponse spyResponse = tradierService.getQuote("SPY");
            QuoteResponse qqqResponse = tradierService.getQuote("QQQ");

            if (spyResponse != null && spyResponse.getQuote() != null &&
                    qqqResponse != null && qqqResponse.getQuote() != null) {

                // Infer advance/decline from price changes
                BigDecimal spyChange = calculatePercentChange(spyResponse.getQuote());
                BigDecimal qqqChange = calculatePercentChange(qqqResponse.getQuote());

                if (spyChange != null && qqqChange != null) {
                    // If both positive, market is advancing
                    if (spyChange.compareTo(BigDecimal.ZERO) > 0 && qqqChange.compareTo(BigDecimal.ZERO) > 0) {
                        internals.setAdvanceDecline(500); // Simulated positive A/D
                    } else if (spyChange.compareTo(BigDecimal.ZERO) < 0 && qqqChange.compareTo(BigDecimal.ZERO) < 0) {
                        internals.setAdvanceDecline(-500); // Simulated negative A/D
                    } else {
                        internals.setAdvanceDecline(0); // Mixed signals
                    }

                    // Infer up/down volume from actual volume
                    if (spyResponse.getQuote().getVolume() != null) {
                        long volume = spyResponse.getQuote().getVolume();
                        if (spyChange.compareTo(BigDecimal.ZERO) > 0) {
                            internals.setUpVolume((long)(volume * 0.6));
                            internals.setDownVolume((long)(volume * 0.4));
                        } else {
                            internals.setUpVolume((long)(volume * 0.4));
                            internals.setDownVolume((long)(volume * 0.6));
                        }
                    }

                    internals.setHasData(true);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to infer market internals from ETFs: {}", e.getMessage());
        }

        return internals;
    }

    private BigDecimal calculatePercentChange(com.tradingBot.model.Quote quote) {
        if (quote.getLast() != null && quote.getPreviousClose() != null &&
                quote.getPreviousClose().compareTo(BigDecimal.ZERO) > 0) {
            return quote.getLast().subtract(quote.getPreviousClose())
                    .divide(quote.getPreviousClose(), 4, java.math.RoundingMode.HALF_UP);
        }
        return null;
    }

    // Exponential moving average for smoothing
    private final Map<String, Double> emaValues = new ConcurrentHashMap<>();
    private static final double EMA_ALPHA = 0.3; // Smoothing factor

    // Add these fields for VWAP optimization
    private final Map<String, VWAPState> vwapStateCache = new ConcurrentHashMap<>();
    private static final long VWAP_CACHE_TTL = 60000; // 1 minute

    @Data
    private static class VWAPState {
        private BigDecimal cumulativePriceVolume = BigDecimal.ZERO;
        private BigDecimal cumulativeVolume = BigDecimal.ZERO;
        private BigDecimal vwap = BigDecimal.ZERO;
        private BigDecimal sumSquaredDeviations = BigDecimal.ZERO;
        private int dataPointCount = 0;
        private LocalDateTime lastUpdate;
        private LocalDateTime sessionStart;

        public boolean isStale() {
            return lastUpdate == null ||
                    LocalDateTime.now().isAfter(lastUpdate.plusSeconds(60));
        }

        public boolean isNewSession(LocalDateTime marketOpen) {
            return sessionStart == null ||
                    !sessionStart.toLocalDate().equals(marketOpen.toLocalDate());
        }
    }

    // Rest of the existing methods remain the same...

    private VolumeProfile analyzeVolumeProfile(String symbol) {
        List<MarketData> data = marketDataRepository.findRecentData(symbol, 20);

        long buyVolume = 0;
        long sellVolume = 0;

        for (int i = 1; i < data.size(); i++) {
            MarketData current = data.get(i);
            MarketData previous = data.get(i - 1);

            if (current.getVolume() != null && current.getVolume() > 0) {
                // Volume classification: if price went up, it's buy volume
                if (current.getPrice().compareTo(previous.getPrice()) > 0) {
                    buyVolume += current.getVolume();
                } else if (current.getPrice().compareTo(previous.getPrice()) < 0) {
                    sellVolume += current.getVolume();
                } else {
                    // Price unchanged - check bid/ask pressure
                    if (current.getBidSize() != null && current.getAskSize() != null) {
                        if (current.getBidSize() > current.getAskSize()) {
                            buyVolume += current.getVolume() / 2;
                        } else {
                            sellVolume += current.getVolume() / 2;
                        }
                    }
                }
            }
        }

        return new VolumeProfile(buyVolume, sellVolume);
    }

    private double calculateAccumulationDistribution(String symbol) {
        List<MarketData> data = marketDataRepository.findRecentData(symbol, 20);

        double adLine = 0.0;

        for (MarketData md : data) {
            if (md.getHigh() != null && md.getLow() != null && md.getPrice() != null && md.getVolume() != null) {
                BigDecimal high = md.getHigh();
                BigDecimal low = md.getLow();
                BigDecimal close = md.getPrice();

                if (!high.equals(low)) {
                    // Money Flow Multiplier
                    double mfm = ((close.subtract(low).doubleValue()) - (high.subtract(close).doubleValue()))
                            / (high.subtract(low).doubleValue());

                    // Money Flow Volume
                    double mfv = mfm * md.getVolume();

                    adLine += mfv;
                }
            }
        }

        // Normalize
        return Math.tanh(adLine / 1000000); // tanh for -1 to 1 range
    }

    private double calculateSmartMoneyFlow(String symbol) {
        List<MarketData> data = marketDataRepository.findRecentData(symbol, 30);

        double smartFlow = 0.0;

        // Look for large volume spikes with price continuation
        for (int i = 2; i < data.size(); i++) {
            MarketData current = data.get(i);
            MarketData prev1 = data.get(i - 1);
            MarketData prev2 = data.get(i - 2);

            if (current.getVolume() != null && prev1.getVolume() != null) {
                double volRatio = (double) current.getVolume() / prev1.getVolume();

                // High volume spike
                if (volRatio > 2.0) {
                    BigDecimal priceMove = current.getPrice().subtract(prev2.getPrice());

                    // Check if price continued in same direction
                    if (i + 1 < data.size()) {
                        MarketData next = data.get(i + 1);
                        BigDecimal continuation = next.getPrice().subtract(current.getPrice());

                        if (priceMove.signum() == continuation.signum()) {
                            // Smart money detected
                            smartFlow += priceMove.doubleValue() * volRatio;
                        }
                    }
                }
            }
        }

        return Math.tanh(smartFlow * 100); // Normalize to -1 to 1
    }

    @Data
    private static class VolumeProfile {
        private final long buyVolume;
        private final long sellVolume;

        public boolean isBullishVolume() {
            return buyVolume > sellVolume * 1.2;
        }

        public boolean isBearishVolume() {
            return sellVolume > buyVolume * 1.2;
        }
    }

    @Data
    private static class MarketInternals {
        private int tick = 0;
        private int advanceDecline = 0;
        private long upVolume = 0;
        private long downVolume = 0;
        private boolean hasData = false;

        public boolean hasValidData() {
            return hasData;
        }

        public double getAdvanceDeclineRatio() {
            // Calculate advancing vs declining stocks ratio
            if (advanceDecline > 1000) return 3.0;
            if (advanceDecline > 500) return 2.0;
            if (advanceDecline < -1000) return 0.33;
            if (advanceDecline < -500) return 0.5;
            return 1.0;
        }
    }

    private final Map<String, String> previousTrends = new ConcurrentHashMap<>();

    private String getPreviousTrend(String symbol) {
        return previousTrends.getOrDefault(symbol, "NEUTRAL");
    }
}