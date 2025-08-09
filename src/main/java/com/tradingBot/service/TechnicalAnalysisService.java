package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.*;
import com.tradingBot.service.*;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TechnicalAnalysisService {

    private final TradierService tradierService;
    private final MarketDataRepository marketDataRepository;

    // Constants for technical analysis calculations
    private static final int VWAP_PERIOD = 20;
    private static final int RSI_PERIOD = 14;
    private static final int VOLATILITY_PERIOD = 20;
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final double STD_DEV_MULTIPLIER = 2.0;
    private static final int LOOKBACK_CANDLES = 5;
    private static final int SUPPORT_RESISTANCE_LOOKBACK = 50;

    // Add these fields for VWAP optimization
    private final Map<String, VWAPState> vwapStateCache = new ConcurrentHashMap<>();
    private static final long VWAP_CACHE_TTL = 60000; // 1 minute

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

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

    public TechnicalAnalysis analyze(String symbol) {
        log.info("[TA] Starting technical analysis for {}", symbol);

        // Fetch market data since market open
        LocalDateTime marketOpen = LocalDateTime.now().withHour(9).withMinute(30).withSecond(0).withNano(0);
        List<MarketData> sessionData = marketDataRepository
                .findBySymbolAndTimestampAfterOrderByTimestampAsc(symbol, marketOpen);

        if (sessionData == null || sessionData.isEmpty()) {
            log.error("[TA] No market data available for {}", symbol);
            return null;
        }

        //log.info("[TA] Found {} data points for analysis", sessionData.size());

        // Get current quote
        QuoteResponse quote = tradierService.getQuote(symbol);
        if (quote == null || quote.getQuote() == null) {
            log.error("[TA] Failed to fetch current quote for {}", symbol);
            return null;
        }
        BigDecimal currentPrice = quote.getQuote().getLast();
        long currentVolume = quote.getQuote().getVolume() != null ? quote.getQuote().getVolume() : 0L;

        TechnicalAnalysis ta = new TechnicalAnalysis();
        ta.setSymbol(symbol);
        ta.setTimestamp(LocalDateTime.now());
        ta.setCurrentPrice(currentPrice);
        ta.setCurrentVolume(currentVolume);

        // Calculate VWAP and bands
        calculateVWAPIndicators(sessionData, ta);

        // Calculate volume metrics
        calculateVolumeMetrics(sessionData, currentVolume, ta);

        // Calculate volatility metrics
        calculateVolatilityMetrics(sessionData, ta);

        // Calculate momentum indicators
        calculateMomentumIndicators(sessionData, ta);

        // Calculate support and resistance levels
        calculateSupportResistance(sessionData, ta);

        // Analyze trends - UPDATED METHOD
        analyzeTrends(sessionData, ta);

        // Check for reversals
        checkReversals(ta);

        // Set dynamic parameters based on market conditions
        setDynamicParameters(ta);

        // Detect market regime
        detectMarketRegime(ta);

        // Calculate opening range if within first hour
        calculateOpeningRange(sessionData, ta);

        // Check for divergences
        checkDivergences(sessionData, ta);

        // Calculate VWAP extensions
        calculateVwapExtensions(ta);

        log.info("[TA] Analysis complete - VWAP: ${}, Price: ${}, RSI: {}, Volume Ratio: {}, Regime: {}",
                ta.getVwap(), ta.getCurrentPrice(),
                String.format("%.1f", ta.getRsi()),
                String.format("%.2f", ta.getVolumeRatio()),
                ta.getMarketRegime());

        // Get previous close from market data
        BigDecimal previousClose = getPreviousCloseFromTradier(symbol);
        ta.setPreviousClose(previousClose);

        // DISABLE DIVERGENCE - it's broken and blocking all signals
        ta.setHasRsiDivergence(false);

        return ta;
    }

    // Helper method to get previous close from Tradier
    private BigDecimal getPreviousCloseFromTradier(String symbol) {
        try {
            QuoteResponse quote = tradierService.getQuote(symbol);
            if (quote != null && quote.getQuote().getPreviousClose() != null) {
                return quote.getQuote().getPreviousClose();
            }
        } catch (Exception e) {
            log.error("Failed to get previous close for {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    private void calculateVWAPIndicators(List<MarketData> data, TechnicalAnalysis ta) {
        if (data.isEmpty()) {
            ta.setVwap(BigDecimal.ZERO);
            ta.setVwapUpperBand(BigDecimal.ZERO);
            ta.setVwapLowerBand(BigDecimal.ZERO);
            return;
        }

        String symbol = ta.getSymbol();
        LocalDateTime marketOpen = LocalDateTime.now().withHour(9).withMinute(30).withSecond(0).withNano(0);

        // Get or create VWAP state
        VWAPState state = vwapStateCache.computeIfAbsent(symbol, k -> new VWAPState());

        // Check if we need to reset (new session)
        if (state.isNewSession(marketOpen)) {
            state = new VWAPState();
            state.setSessionStart(marketOpen);
            vwapStateCache.put(symbol, state);
        }

        // Incremental VWAP calculation
        if (!state.isStale() && state.getDataPointCount() > 0) {
            // Incremental update - only process new data points
            int startIndex = Math.max(0, state.getDataPointCount());

            for (int i = startIndex; i < data.size(); i++) {
                MarketData md = data.get(i);
                updateVWAPIncremental(state, md);
            }
        } else {
            // Full recalculation (only on startup or after gap)
            state = new VWAPState();
            state.setSessionStart(marketOpen);

            for (MarketData md : data) {
                updateVWAPIncremental(state, md);
            }

            vwapStateCache.put(symbol, state);
        }

        // Set calculated values
        ta.setVwap(state.getVwap());

        // Calculate bands using Welford's algorithm for variance
        BigDecimal variance = calculateIncrementalVariance(state);
        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        ta.setVwapUpperBand(state.getVwap().add(stdDev.multiply(BigDecimal.valueOf(STD_DEV_MULTIPLIER))));
        ta.setVwapLowerBand(state.getVwap().subtract(stdDev.multiply(BigDecimal.valueOf(STD_DEV_MULTIPLIER))));
        ta.setVwapStandardDeviation(stdDev);

        // Check for breakout
        BigDecimal currentPrice = ta.getCurrentPrice();
        ta.setVwapBreakout(currentPrice.compareTo(ta.getVwapUpperBand()) > 0 ||
                currentPrice.compareTo(ta.getVwapLowerBand()) < 0);

        // VWAP trend using exponential smoothing
        ta.setVwapTrend(calculateVWAPTrend(state, ta.getCurrentPrice()));

        // Support/Resistance with proper statistical methods
        checkVWAPSupportResistanceOptimized(data.subList(Math.max(0, data.size() - 20), data.size()),
                state.getVwap(), ta);
    }

    private void updateVWAPIncremental(VWAPState state, MarketData md) {
        BigDecimal high = md.getHigh() != null ? md.getHigh() : md.getPrice();
        BigDecimal low = md.getLow() != null ? md.getLow() : md.getPrice();
        BigDecimal close = md.getPrice();
        BigDecimal typicalPrice = high.add(low).add(close).divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
        long volume = md.getVolume() != null ? md.getVolume() : 0;

        if (volume > 0) {
            // Update cumulative values
            state.setCumulativePriceVolume(
                    state.getCumulativePriceVolume().add(typicalPrice.multiply(BigDecimal.valueOf(volume)))
            );
            state.setCumulativeVolume(
                    state.getCumulativeVolume().add(BigDecimal.valueOf(volume))
            );

            // Update VWAP
            if (state.getCumulativeVolume().compareTo(BigDecimal.ZERO) > 0) {
                state.setVwap(
                        state.getCumulativePriceVolume().divide(state.getCumulativeVolume(), 6, RoundingMode.HALF_UP)
                );
            }

            // Update variance using Welford's online algorithm
            BigDecimal deviation = typicalPrice.subtract(state.getVwap());
            state.setSumSquaredDeviations(
                    state.getSumSquaredDeviations().add(
                            deviation.multiply(deviation).multiply(BigDecimal.valueOf(volume))
                    )
            );

            state.setDataPointCount(state.getDataPointCount() + 1);
            state.setLastUpdate(LocalDateTime.now());
        }
    }

    private BigDecimal calculateIncrementalVariance(VWAPState state) {
        if (state.getCumulativeVolume().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return state.getSumSquaredDeviations().divide(state.getCumulativeVolume(), 10, RoundingMode.HALF_UP);
    }

    private String calculateVWAPTrend(VWAPState state, BigDecimal currentPrice) {
        // Use EMA of VWAP to determine trend
        String key = state.getVwap().toString() + "_trend";
        BigDecimal emaVwap = getEMAValue(key, state.getVwap(), 0.1);

        if (state.getVwap().compareTo(emaVwap) > 0 && currentPrice.compareTo(state.getVwap()) > 0) {
            return "BULLISH";
        } else if (state.getVwap().compareTo(emaVwap) < 0 && currentPrice.compareTo(state.getVwap()) < 0) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    private final Map<String, BigDecimal> emaCache = new ConcurrentHashMap<>();

    private BigDecimal getEMAValue(String key, BigDecimal newValue, double alpha) {
        BigDecimal previousEMA = emaCache.get(key);
        if (previousEMA == null) {
            emaCache.put(key, newValue);
            return newValue;
        }

        BigDecimal ema = newValue.multiply(BigDecimal.valueOf(alpha))
                .add(previousEMA.multiply(BigDecimal.valueOf(1 - alpha)));
        emaCache.put(key, ema);
        return ema;
    }

    private void checkVWAPSupportResistanceOptimized(List<MarketData> recentData,
                                                     BigDecimal vwap, TechnicalAnalysis ta) {
        if (recentData.size() < 3) {
            ta.setVwapAsSupport(false);
            ta.setVwapAsResistance(false);
            return;
        }

        int supportTouches = 0;
        int resistanceTouches = 0;
        int successfulBounces = 0;

        // More sensitive threshold - 0.1% instead of 0.15%
        BigDecimal vwapThreshold = vwap.multiply(BigDecimal.valueOf(0.001));

        for (int i = 1; i < recentData.size() - 1; i++) {
            MarketData current = recentData.get(i);
            MarketData previous = recentData.get(i - 1);
            MarketData next = recentData.get(i + 1);

            BigDecimal currentLow = current.getLow() != null ? current.getLow() : current.getPrice();
            BigDecimal currentHigh = current.getHigh() != null ? current.getHigh() : current.getPrice();
            BigDecimal currentPrice = current.getPrice();

            // Check for support touch (price dipped to VWAP and bounced)
            if (currentLow.subtract(vwap).abs().compareTo(vwapThreshold) <= 0) {
                // Price touched VWAP from above
                if (previous.getPrice().compareTo(vwap) > 0) {
                    // Check if it bounced (next price is higher)
                    if (next.getPrice().compareTo(currentPrice) > 0 &&
                            next.getPrice().compareTo(vwap) > 0) {
                        supportTouches++;
                        successfulBounces++;
                        log.debug("Support bounce detected at index {} - Low: {}, VWAP: {}, Next: {}",
                                i, currentLow, vwap, next.getPrice());
                    }
                }
            }

            // Check for resistance touch (price rose to VWAP and rejected)
            if (vwap.subtract(currentHigh).abs().compareTo(vwapThreshold) <= 0) {
                // Price touched VWAP from below
                if (previous.getPrice().compareTo(vwap) < 0) {
                    // Check if it was rejected (next price is lower)
                    if (next.getPrice().compareTo(currentPrice) < 0 &&
                            next.getPrice().compareTo(vwap) < 0) {
                        resistanceTouches++;
                        log.debug("Resistance rejection detected at index {} - High: {}, VWAP: {}, Next: {}",
                                i, currentHigh, vwap, next.getPrice());
                    }
                }
            }
        }

        // Also check the most recent candle for real-time detection
        if (recentData.size() >= 2) {
            MarketData latest = recentData.get(recentData.size() - 1);
            MarketData previousCandle = recentData.get(recentData.size() - 2);

            // Real-time support check
            if (latest.getPrice().compareTo(vwap) > 0 &&
                    previousCandle.getLow() != null &&
                    previousCandle.getLow().subtract(vwap).abs().compareTo(vwapThreshold) <= 0) {
                // Just bounced off VWAP
                supportTouches++;
                log.info("REAL-TIME: Potential support bounce - Current: {}, VWAP: {}",
                        latest.getPrice(), vwap);
            }

            // Real-time resistance check
            if (latest.getPrice().compareTo(vwap) < 0 &&
                    previousCandle.getHigh() != null &&
                    vwap.subtract(previousCandle.getHigh()).abs().compareTo(vwapThreshold) <= 0) {
                // Just rejected from VWAP
                resistanceTouches++;
                log.info("REAL-TIME: Potential resistance rejection - Current: {}, VWAP: {}",
                        latest.getPrice(), vwap);
            }
        }

        // Lower threshold for 0DTE - only need 1 clean touch/bounce
        ta.setVwapAsSupport(supportTouches >= 1 && successfulBounces >= 1);
        ta.setVwapAsResistance(resistanceTouches >= 1);

        if (ta.isVwapAsSupport()) {
            log.info("VWAP acting as SUPPORT - {} touches, {} bounces", supportTouches, successfulBounces);
        }
        if (ta.isVwapAsResistance()) {
            log.info("VWAP acting as RESISTANCE - {} touches", resistanceTouches);
        }
    }

    private BigDecimal calculateVWAPAtIndex(List<MarketData> data, int endIndex) {
        BigDecimal cumulativePriceVolume = BigDecimal.ZERO;
        BigDecimal cumulativeVolume = BigDecimal.ZERO;

        for (int i = 0; i <= endIndex && i < data.size(); i++) {
            MarketData md = data.get(i);
            BigDecimal high = md.getHigh() != null ? md.getHigh() : md.getPrice();
            BigDecimal low = md.getLow() != null ? md.getLow() : md.getPrice();
            BigDecimal close = md.getPrice();
            BigDecimal typicalPrice = high.add(low).add(close).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            long volume = md.getVolume() != null ? md.getVolume() : 0;

            cumulativePriceVolume = cumulativePriceVolume.add(typicalPrice.multiply(BigDecimal.valueOf(volume)));
            cumulativeVolume = cumulativeVolume.add(BigDecimal.valueOf(volume));
        }

        return cumulativeVolume.compareTo(BigDecimal.ZERO) > 0 ?
                cumulativePriceVolume.divide(cumulativeVolume, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private void checkVWAPSupportResistance(List<MarketData> data, BigDecimal vwap, TechnicalAnalysis ta) {
        if (data.size() < LOOKBACK_CANDLES) {
            ta.setVwapAsSupport(false);
            ta.setVwapAsResistance(false);
            return;
        }

        int bounces = 0;
        boolean aboveVwap = false;
        boolean belowVwap = false;

        for (int i = data.size() - LOOKBACK_CANDLES; i < data.size(); i++) {
            MarketData md = data.get(i);
            BigDecimal low = md.getLow() != null ? md.getLow() : md.getPrice();
            BigDecimal high = md.getHigh() != null ? md.getHigh() : md.getPrice();

            // Check if price touched VWAP and bounced
            BigDecimal vwapThreshold = vwap.multiply(BigDecimal.valueOf(0.002)); // 0.2% threshold

            if (low.subtract(vwap).abs().compareTo(vwapThreshold) <= 0) {
                bounces++;
                if (md.getPrice().compareTo(vwap) > 0) {
                    aboveVwap = true;
                }
            }

            if (high.subtract(vwap).abs().compareTo(vwapThreshold) <= 0) {
                bounces++;
                if (md.getPrice().compareTo(vwap) < 0) {
                    belowVwap = true;
                }
            }
        }

        ta.setVwapAsSupport(bounces >= 2 && aboveVwap);
        ta.setVwapAsResistance(bounces >= 2 && belowVwap);
    }

    private void calculateVolumeMetrics(List<MarketData> data, long currentVolume, TechnicalAnalysis ta) {
        if (data.size() < VOLUME_AVG_PERIOD) {
            ta.setAverageVolume(currentVolume);
            ta.setVolumeRatio(1.0);
            ta.setHighVolume(false);
            ta.setVolumeTrend("STABLE");
            return;
        }

        // Calculate average volume
        List<MarketData> recentData = data.subList(Math.max(0, data.size() - VOLUME_AVG_PERIOD), data.size());
        long totalVolume = recentData.stream()
                .mapToLong(md -> md.getVolume() != null ? md.getVolume() : 0)
                .sum();
        long avgVolume = totalVolume / recentData.size();

        ta.setAverageVolume(avgVolume);
        ta.setVolumeRatio(avgVolume > 0 ? (double) currentVolume / avgVolume : 1.0);
        ta.setHighVolume(ta.getVolumeRatio() > 1.5);

        // Determine volume trend
        if (data.size() >= 10) {
            long oldAvgVolume = data.subList(Math.max(0, data.size() - 10), data.size() - 5)
                    .stream()
                    .mapToLong(md -> md.getVolume() != null ? md.getVolume() : 0)
                    .sum() / 5;

            long recentAvgVolume = data.subList(data.size() - 5, data.size())
                    .stream()
                    .mapToLong(md -> md.getVolume() != null ? md.getVolume() : 0)
                    .sum() / 5;

            if (recentAvgVolume > oldAvgVolume * 1.2) {
                ta.setVolumeTrend("INCREASING");
            } else if (recentAvgVolume < oldAvgVolume * 0.8) {
                ta.setVolumeTrend("DECREASING");
            } else {
                ta.setVolumeTrend("STABLE");
            }
        } else {
            ta.setVolumeTrend("STABLE");
        }
    }

    private void calculateVolatilityMetrics(List<MarketData> data, TechnicalAnalysis ta) {
        if (data.size() < 2) {
            ta.setCurrentVolatility(BigDecimal.ZERO);
            ta.setAverageVolatility(BigDecimal.ZERO);
            ta.setAverageTrueRange(BigDecimal.ONE);
            ta.setVolatilityPercentile(50.0);
            ta.setVolatilitySpike(false);
            return;
        }

        // Calculate returns for volatility
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 1; i < data.size(); i++) {
            BigDecimal prevPrice = data.get(i - 1).getPrice();
            BigDecimal currPrice = data.get(i).getPrice();
            if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ret = currPrice.subtract(prevPrice).divide(prevPrice, 6, RoundingMode.HALF_UP);
                returns.add(ret);
            }
        }

        // Calculate current volatility (standard deviation of returns)
        if (!returns.isEmpty()) {
            BigDecimal mean = returns.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(returns.size()), 6, RoundingMode.HALF_UP);

            BigDecimal sumSquaredDiff = returns.stream()
                    .map(r -> r.subtract(mean).pow(2))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal variance = sumSquaredDiff.divide(BigDecimal.valueOf(returns.size()), 10, RoundingMode.HALF_UP);
            BigDecimal volatility = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
            ta.setCurrentVolatility(volatility);

            // Calculate average volatility over longer period
            if (data.size() >= VOLATILITY_PERIOD) {
                List<BigDecimal> longReturns = new ArrayList<>();
                for (int i = data.size() - VOLATILITY_PERIOD; i < data.size(); i++) {
                    if (i > 0) {
                        BigDecimal prevPrice = data.get(i - 1).getPrice();
                        BigDecimal currPrice = data.get(i).getPrice();
                        if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal ret = currPrice.subtract(prevPrice).divide(prevPrice, 6, RoundingMode.HALF_UP);
                            longReturns.add(ret.abs());
                        }
                    }
                }

                BigDecimal avgVolatility = longReturns.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(longReturns.size()), 6, RoundingMode.HALF_UP);
                ta.setAverageVolatility(avgVolatility);

                // Volatility spike detection
                ta.setVolatilitySpike(volatility.compareTo(avgVolatility.multiply(BigDecimal.valueOf(1.5))) > 0);
            } else {
                ta.setAverageVolatility(volatility);
                ta.setVolatilitySpike(false);
            }
        } else {
            ta.setCurrentVolatility(BigDecimal.ZERO);
            ta.setAverageVolatility(BigDecimal.ZERO);
            ta.setVolatilitySpike(false);
        }

        // Calculate ATR for dynamic stops/targets
        calculateATR(data, ta);

        // Set volatility percentile (simplified - would need historical data for accurate percentile)
        ta.setVolatilityPercentile(ta.isVolatilitySpike() ? 80.0 : 50.0);
    }

    private void calculateATR(List<MarketData> data, TechnicalAnalysis ta) {
        if (data.size() < 2) {
            ta.setAverageTrueRange(ta.getCurrentPrice().multiply(BigDecimal.valueOf(0.01))); // 1% default
            return;
        }

        List<BigDecimal> trueRanges = new ArrayList<>();
        for (int i = 1; i < Math.min(data.size(), 14); i++) {
            MarketData current = data.get(data.size() - i);
            MarketData previous = data.get(data.size() - i - 1);

            BigDecimal high = current.getHigh() != null ? current.getHigh() : current.getPrice();
            BigDecimal low = current.getLow() != null ? current.getLow() : current.getPrice();
            BigDecimal prevClose = previous.getPrice();

            BigDecimal tr1 = high.subtract(low);
            BigDecimal tr2 = high.subtract(prevClose).abs();
            BigDecimal tr3 = low.subtract(prevClose).abs();

            BigDecimal trueRange = tr1.max(tr2).max(tr3);
            trueRanges.add(trueRange);
        }

        if (!trueRanges.isEmpty()) {
            BigDecimal atr = trueRanges.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(trueRanges.size()), 4, RoundingMode.HALF_UP);
            ta.setAverageTrueRange(atr);
        } else {
            ta.setAverageTrueRange(ta.getCurrentPrice().multiply(BigDecimal.valueOf(0.01)));
        }
    }

    private void calculateMomentumIndicators(List<MarketData> data, TechnicalAnalysis ta) {
        // Calculate RSI
        double rsi = calculateRSI(data, RSI_PERIOD);
        ta.setRsi(rsi);

        // Simple MACD signal (would need more sophisticated calculation in production)
        if (data.size() >= 26) {
            BigDecimal ema12 = calculateEMA(data, 12);
            BigDecimal ema26 = calculateEMA(data, 26);
            BigDecimal macd = ema12.subtract(ema26);

            if (macd.compareTo(BigDecimal.ZERO) > 0) {
                ta.setMacdSignal("BULLISH");
                ta.setMomentumStrength(Math.min(macd.doubleValue() / ta.getCurrentPrice().doubleValue() * 100, 1.0));
            } else if (macd.compareTo(BigDecimal.ZERO) < 0) {
                ta.setMacdSignal("BEARISH");
                ta.setMomentumStrength(Math.max(macd.doubleValue() / ta.getCurrentPrice().doubleValue() * 100, -1.0));
            } else {
                ta.setMacdSignal("NEUTRAL");
                ta.setMomentumStrength(0.0);
            }
        } else {
            ta.setMacdSignal("NEUTRAL");
            ta.setMomentumStrength(0.0);
        }
    }

    private double calculateRSI(List<MarketData> data, int period) {
        if (data.size() < period + 1) {
            return 50.0; // Neutral RSI
        }

        List<BigDecimal> gains = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();

        for (int i = data.size() - period; i < data.size(); i++) {
            if (i > 0) {
                BigDecimal change = data.get(i).getPrice().subtract(data.get(i - 1).getPrice());
                if (change.compareTo(BigDecimal.ZERO) > 0) {
                    gains.add(change);
                    losses.add(BigDecimal.ZERO);
                } else {
                    gains.add(BigDecimal.ZERO);
                    losses.add(change.abs());
                }
            }
        }

        BigDecimal avgGain = gains.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
        BigDecimal avgLoss = losses.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return 100.0;
        }

        BigDecimal rs = avgGain.divide(avgLoss, 6, RoundingMode.HALF_UP);
        double rsi = 100.0 - (100.0 / (1.0 + rs.doubleValue()));

        return rsi;
    }

    private BigDecimal calculateEMA(List<MarketData> data, int period) {
        if (data.size() < period) {
            return data.get(data.size() - 1).getPrice();
        }

        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        BigDecimal ema = data.get(data.size() - period).getPrice();

        for (int i = data.size() - period + 1; i < data.size(); i++) {
            BigDecimal price = data.get(i).getPrice();
            ema = price.subtract(ema).multiply(multiplier).add(ema);
        }

        return ema;
    }

    private void calculateSupportResistance(List<MarketData> data, TechnicalAnalysis ta) {
        if (data.size() < SUPPORT_RESISTANCE_LOOKBACK) {
            ta.setSupportLevels(new ArrayList<>());
            ta.setResistanceLevels(new ArrayList<>());
            return;
        }

        List<BigDecimal> highs = new ArrayList<>();
        List<BigDecimal> lows = new ArrayList<>();

        // Look for local highs and lows
        for (int i = 2; i < data.size() - 2; i++) {
            MarketData current = data.get(i);
            MarketData prev1 = data.get(i - 1);
            MarketData prev2 = data.get(i - 2);
            MarketData next1 = data.get(i + 1);
            MarketData next2 = data.get(i + 2);

            BigDecimal currentHigh = current.getHigh() != null ? current.getHigh() : current.getPrice();
            BigDecimal currentLow = current.getLow() != null ? current.getLow() : current.getPrice();

            // Check for local high
            if (isLocalHigh(currentHigh, prev1, prev2, next1, next2)) {
                highs.add(currentHigh);
            }

            // Check for local low
            if (isLocalLow(currentLow, prev1, prev2, next1, next2)) {
                lows.add(currentLow);
            }
        }

        // Sort and take significant levels
        List<BigDecimal> resistanceLevels = highs.stream()
                .sorted(Comparator.reverseOrder())
                .distinct()
                .limit(3)
                .collect(Collectors.toList());

        List<BigDecimal> supportLevels = lows.stream()
                .sorted()
                .distinct()
                .limit(3)
                .collect(Collectors.toList());

        ta.setResistanceLevels(resistanceLevels);
        ta.setSupportLevels(supportLevels);
    }

    private boolean isLocalHigh(BigDecimal current, MarketData prev1, MarketData prev2,
                                MarketData next1, MarketData next2) {
        BigDecimal p1High = prev1.getHigh() != null ? prev1.getHigh() : prev1.getPrice();
        BigDecimal p2High = prev2.getHigh() != null ? prev2.getHigh() : prev2.getPrice();
        BigDecimal n1High = next1.getHigh() != null ? next1.getHigh() : next1.getPrice();
        BigDecimal n2High = next2.getHigh() != null ? next2.getHigh() : next2.getPrice();

        return current.compareTo(p1High) > 0 && current.compareTo(p2High) > 0 &&
                current.compareTo(n1High) > 0 && current.compareTo(n2High) > 0;
    }

    private boolean isLocalLow(BigDecimal current, MarketData prev1, MarketData prev2,
                               MarketData next1, MarketData next2) {
        BigDecimal p1Low = prev1.getLow() != null ? prev1.getLow() : prev1.getPrice();
        BigDecimal p2Low = prev2.getLow() != null ? prev2.getLow() : prev2.getPrice();
        BigDecimal n1Low = next1.getLow() != null ? next1.getLow() : next1.getPrice();
        BigDecimal n2Low = next2.getLow() != null ? next2.getLow() : next2.getPrice();

        return current.compareTo(p1Low) < 0 && current.compareTo(p2Low) < 0 &&
                current.compareTo(n1Low) < 0 && current.compareTo(n2Low) < 0;
    }
    @Autowired
    private UnifiedTrendDetector unifiedTrendDetector;
    private void analyzeTrends(List<MarketData> data, TechnicalAnalysis ta) {
        String trend = unifiedTrendDetector.detectTrend("QQQ");
        ta.setTrend(trend);
        log.info("[TA] Trend: {}", ta.getTrend());
    }

    private void checkReversals(TechnicalAnalysis ta) {
        // Check for potential bearish reversal
        boolean bearishReversal = false;
        if (ta.getRsi() > 70 && "BEARISH".equals(ta.getMacdSignal())) {
            bearishReversal = true;
        } else if (ta.getCurrentPrice().compareTo(ta.getVwapUpperBand()) > 0 &&
                ta.isHighVolume() && ta.getRsi() > 65) {
            bearishReversal = true;
        }
        ta.setPotentialBearishReversal(bearishReversal);

        // Check for potential bullish reversal
        boolean bullishReversal = false;
        if (ta.getRsi() < 30 && "BULLISH".equals(ta.getMacdSignal())) {
            bullishReversal = true;
        } else if (ta.getCurrentPrice().compareTo(ta.getVwapLowerBand()) < 0 &&
                ta.isHighVolume() && ta.getRsi() < 35) {
            bullishReversal = true;
        }
        ta.setPotentialBullishReversal(bullishReversal);
    }

    private void setDynamicParameters(TechnicalAnalysis ta) {
        DynamicParameters params = new DynamicParameters();

        // Adjust based on volatility
        if (ta.isVolatilitySpike()) {
            params.setMinVolumeMultiplier(0.7); // Lower volume requirement during volatility
            params.setMinIVMultiplier(1.2);     // Higher IV requirement
            params.setTargetMultiplier(1.5);    // Wider targets
            params.setStopMultiplier(1.3);      // Wider stops
        } else if (ta.getVolatilityPercentile() < 30) {
            params.setMinVolumeMultiplier(1.3); // Higher volume requirement in low volatility
            params.setMinIVMultiplier(0.8);     // Lower IV requirement
            params.setTargetMultiplier(0.8);    // Tighter targets
            params.setStopMultiplier(0.9);      // Tighter stops
        }

        // Adjust based on volume
        if (ta.isHighVolume()) {
            params.setMinVolumeMultiplier(params.getMinVolumeMultiplier() * 0.8);
        }

        ta.setDynamicParameters(params);
    }

    private void detectMarketRegime(TechnicalAnalysis ta) {
        LocalTime now = LocalTime.now(ET_ZONE);

        // Time-based regimes
        if (now.isBefore(LocalTime.of(10, 0))) {
            ta.setMarketRegime(MarketRegime.OPENING_RANGE);
            return;
        }
        if (now.isAfter(LocalTime.of(15, 30))) {
            ta.setMarketRegime(MarketRegime.CLOSING_RANGE);
            return;
        }

        // Volatility-based regimes
        if (ta.isVolatilitySpike()) {
            ta.setMarketRegime(MarketRegime.HIGH_VOLATILITY);
            return;
        }

        if (ta.getVolatilityPercentile() < 30) {
            ta.setMarketRegime(MarketRegime.LOW_VOLATILITY);
            return;
        }

        // Trend-based regimes - UPDATED TO USE NEW NAMING
        double trendStrength = ta.getStrength();
        String trend = ta.getTrend();

        if (trendStrength > 0.7) {
            if ("UP".equals(trend)) {
                ta.setMarketRegime(MarketRegime.TRENDING_UP);
            } else if ("DOWN".equals(trend)) {
                ta.setMarketRegime(MarketRegime.TRENDING_DOWN);
            }
        } else {
            ta.setMarketRegime(MarketRegime.CHOPPY);
        }
    }

    private void calculateOpeningRange(List<MarketData> data, TechnicalAnalysis ta) {
        LocalTime marketOpen = LocalTime.of(9, 30);
        LocalTime rangeEnd = LocalTime.of(9, 45); // 15-minute opening range
        LocalTime now = LocalTime.now(ET_ZONE);

        if (data.isEmpty()) {
            ta.setOpeningRangeHigh(null);
            ta.setOpeningRangeLow(null);
            return;
        }

        BigDecimal orHigh = null;
        BigDecimal orLow = null;

        if (now.isBefore(rangeEnd)) {
            // Still in opening range, use data so far
            for (MarketData md : data) {
                LocalDateTime timestamp = md.getTimestamp();
                if (timestamp.toLocalTime().isAfter(marketOpen)) {
                    BigDecimal high = md.getHigh() != null ? md.getHigh() : md.getPrice();
                    BigDecimal low = md.getLow() != null ? md.getLow() : md.getPrice();

                    if (orHigh == null || high.compareTo(orHigh) > 0) {
                        orHigh = high;
                    }
                    if (orLow == null || low.compareTo(orLow) < 0) {
                        orLow = low;
                    }
                }
            }
        } else {
            // After opening range, calculate from first 15 minutes
            for (MarketData md : data) {
                LocalDateTime timestamp = md.getTimestamp();
                LocalTime time = timestamp.toLocalTime();

                if (time.isAfter(marketOpen) && time.isBefore(rangeEnd)) {
                    BigDecimal high = md.getHigh() != null ? md.getHigh() : md.getPrice();
                    BigDecimal low = md.getLow() != null ? md.getLow() : md.getPrice();

                    if (orHigh == null || high.compareTo(orHigh) > 0) {
                        orHigh = high;
                    }
                    if (orLow == null || low.compareTo(orLow) < 0) {
                        orLow = low;
                    }
                }
            }
        }

        ta.setOpeningRangeHigh(orHigh);
        ta.setOpeningRangeLow(orLow);
    }

    private void checkDivergences(List<MarketData> data, TechnicalAnalysis ta) {
        // DISABLED - always set to false
        ta.setHasRsiDivergence(false);
        ta.setHasMacdDivergence(false);
    }

    private void calculateVwapExtensions(TechnicalAnalysis ta) {
        if (ta.getVwap() == null || ta.getVwap().compareTo(BigDecimal.ZERO) == 0) {
            ta.setPriceToVwapRatio(1.0);
            ta.setExtendedFromVwap(false);
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        // Calculate price to VWAP ratio
        double ratio = currentPrice.divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();
        ta.setPriceToVwapRatio(ratio);

        // Calculate standard deviation if not already set
        if (ta.getVwapUpperBand() != null && ta.getVwapLowerBand() != null) {
            BigDecimal upperDiff = ta.getVwapUpperBand().subtract(vwap);
            BigDecimal stdDev = upperDiff.divide(BigDecimal.valueOf(STD_DEV_MULTIPLIER), 4, RoundingMode.HALF_UP);
            ta.setVwapStandardDeviation(stdDev);

            // Check if price is extended (beyond 2.5 standard deviations)
            BigDecimal extension = currentPrice.subtract(vwap).abs();
            BigDecimal maxExtension = stdDev.multiply(BigDecimal.valueOf(2.5));
            ta.setExtendedFromVwap(extension.compareTo(maxExtension) > 0);
        } else {
            ta.setVwapStandardDeviation(BigDecimal.ZERO);
            ta.setExtendedFromVwap(false);
        }
    }
}