package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class AdvancedTrendDetector {

    private final TradierService tradierService;
    private final MarketDataRepository marketDataRepository;

    // Exponential moving average for smoothing
    private final Map<String, Double> emaValues = new ConcurrentHashMap<>();
    private static final double EMA_ALPHA = 0.3; // Smoothing factor

    public String detectTrend(String symbol) {
        try {
            // 1. Get market internals
            MarketInternals internals = getMarketInternals();

            // 2. Volume-confirmed price action
            VolumeProfile volumeProfile = analyzeVolumeProfile(symbol);

            // 3. Accumulation/Distribution
            double adLine = calculateAccumulationDistribution(symbol);

            // 4. Smart money flow
            double smartMoneyFlow = calculateSmartMoneyFlow(symbol);

            // 5. Smoothed trend score
            double rawScore = 0.0;

            // Market internals (25% weight)
            if (internals.getAdvanceDeclineRatio() > 2.0) rawScore += 2.5;
            else if (internals.getAdvanceDeclineRatio() < 0.5) rawScore -= 2.5;

            if (internals.getUpVolume() > internals.getDownVolume() * 1.5) rawScore += 2.0;
            else if (internals.getDownVolume() > internals.getUpVolume() * 1.5) rawScore -= 2.0;

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

            return newTrend;

        } catch (Exception e) {
            log.error("Advanced trend detection failed: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    private MarketInternals getMarketInternals() {
        MarketInternals internals = new MarketInternals();

        try {
            // Get TICK
            QuoteResponse tickResponse = tradierService.getQuote("$TICK");
            if (tickResponse != null && tickResponse.getQuote() != null) {
                internals.setTick(tickResponse.getQuote().getLast().intValue());
            }

            // Get advance/decline
            QuoteResponse addResponse = tradierService.getQuote("$ADD");
            if (addResponse != null && addResponse.getQuote() != null) {
                internals.setAdvanceDecline(addResponse.getQuote().getLast().intValue());
            }

            // Get up/down volume
            QuoteResponse voldResponse = tradierService.getQuote("$VOLD");
            QuoteResponse voluResponse = tradierService.getQuote("$VOLU");

            if (voldResponse != null && voldResponse.getQuote() != null) {
                internals.setDownVolume(voldResponse.getQuote().getLast().longValue());
            }
            if (voluResponse != null && voluResponse.getQuote() != null) {
                internals.setUpVolume(voluResponse.getQuote().getLast().longValue());
            }

        } catch (Exception e) {
            log.debug("Market internals fetch failed: {}", e.getMessage());
        }

        return internals;
    }

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