package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.entity.Signal;
import com.tradingBot.model.Quote;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MultiTimeframeAnalysisService {

    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;
    private final TechnicalAnalysisService technicalAnalysisService;

    // MAIN PUBLIC METHOD - Multi-timeframe validation
    public MultiTimeframeResult analyzeMultipleTimeframes(String symbol) {
        try {
            log.info("[MTF] Starting multi-timeframe analysis for {}", symbol);

            // Get data for different timeframes
            TimeframeData m1Data = analyze1MinuteTimeframe(symbol);
            TimeframeData m5Data = analyze5MinuteTimeframe(symbol);
            TimeframeData m15Data = analyze15MinuteTimeframe(symbol);

            // Calculate confluence
            String overallTrend = calculateTrendConfluence(m1Data, m5Data, m15Data);
            double confluenceScore = calculateConfluenceScore(m1Data, m5Data, m15Data);

            MultiTimeframeResult result = new MultiTimeframeResult();
            result.setM1Data(m1Data);
            result.setM5Data(m5Data);
            result.setM15Data(m15Data);
            result.setOverallTrend(overallTrend);
            result.setConfluenceScore(confluenceScore);

            log.info("[MTF] Analysis complete - Overall trend: {}, Confluence: {:.2f}",
                    overallTrend, confluenceScore);

            return result;

        } catch (Exception e) {
            log.error("[MTF] Multi-timeframe analysis failed: {}", e.getMessage());
            return createNeutralResult();
        }
    }

    // SIGNAL VALIDATION across timeframes
    public boolean validateSignalAcrossTimeframes(Signal signal, String symbol) {
        try {
            MultiTimeframeResult mtfResult = analyzeMultipleTimeframes(symbol);

            boolean isPut = signal.getOptionSymbol().contains("P");
            boolean isCall = !isPut;

            String overallTrend = mtfResult.getOverallTrend();
            double confluence = mtfResult.getConfluenceScore();

            // Check trend alignment
            boolean trendAligned = (isCall && "UP".equals(overallTrend)) ||
                    (isPut && "DOWN".equals(overallTrend));

            // Require higher confluence for execution
            boolean strongConfluence = confluence >= 0.7; // 70% confluence required

            boolean isValid = trendAligned && strongConfluence;

            log.info("[MTF-VALIDATION] Signal: {} - Trend: {}, Confluence: {:.2f}, Valid: {}",
                    signal.getOptionSymbol(), overallTrend, confluence, isValid);

            return isValid;

        } catch (Exception e) {
            log.error("[MTF-VALIDATION] Error validating signal: {}", e.getMessage());
            return false; // Fail safe
        }
    }

    // 1-MINUTE TIMEFRAME ANALYSIS (current implementation)
    private TimeframeData analyze1MinuteTimeframe(String symbol) {
        List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 20);

        if (recentData.isEmpty()) {
            return createNeutralTimeframeData("1M");
        }

        String trend = calculateTrend(recentData);
        double momentum = calculateMomentum(recentData);
        double volumeStrength = calculateVolumeStrength(recentData);

        TimeframeData data = new TimeframeData();
        data.setTimeframe("1M");
        data.setTrend(trend);
        data.setMomentum(momentum);
        data.setVolumeStrength(volumeStrength);
        data.setDataPoints(recentData.size());

        log.debug("[MTF-1M] Trend: {}, Momentum: {:.2f}, Volume: {:.2f}",
                trend, momentum, volumeStrength);

        return data;
    }

    // 5-MINUTE TIMEFRAME ANALYSIS
    private TimeframeData analyze5MinuteTimeframe(String symbol) {
        List<MarketData> rawData = marketDataRepository.findRecentData(symbol, 100); // Last 100 minutes

        if (rawData.size() < 10) {
            return createNeutralTimeframeData("5M");
        }

        // Aggregate to 5-minute candles
        List<AggregatedCandle> fiveMinCandles = aggregateToCandles(rawData, 5);

        if (fiveMinCandles.size() < 4) {
            return createNeutralTimeframeData("5M");
        }

        String trend = calculateCandleTrend(fiveMinCandles);
        double momentum = calculateCandleMomentum(fiveMinCandles);
        double volumeStrength = calculateCandleVolumeStrength(fiveMinCandles);

        TimeframeData data = new TimeframeData();
        data.setTimeframe("5M");
        data.setTrend(trend);
        data.setMomentum(momentum);
        data.setVolumeStrength(volumeStrength);
        data.setDataPoints(fiveMinCandles.size());

        log.debug("[MTF-5M] Trend: {}, Momentum: {:.2f}, Volume: {:.2f}",
                trend, momentum, volumeStrength);

        return data;
    }

    // 15-MINUTE TIMEFRAME ANALYSIS
    private TimeframeData analyze15MinuteTimeframe(String symbol) {
        List<MarketData> rawData = marketDataRepository.findRecentData(symbol, 300); // Last 300 minutes (5 hours)

        if (rawData.size() < 30) {
            return createNeutralTimeframeData("15M");
        }

        // Aggregate to 15-minute candles
        List<AggregatedCandle> fifteenMinCandles = aggregateToCandles(rawData, 15);

        if (fifteenMinCandles.size() < 4) {
            return createNeutralTimeframeData("15M");
        }

        String trend = calculateCandleTrend(fifteenMinCandles);
        double momentum = calculateCandleMomentum(fifteenMinCandles);
        double volumeStrength = calculateCandleVolumeStrength(fifteenMinCandles);

        TimeframeData data = new TimeframeData();
        data.setTimeframe("15M");
        data.setTrend(trend);
        data.setMomentum(momentum);
        data.setVolumeStrength(volumeStrength);
        data.setDataPoints(fifteenMinCandles.size());

        log.debug("[MTF-15M] Trend: {}, Momentum: {:.2f}, Volume: {:.2f}",
                trend, momentum, volumeStrength);

        return data;
    }

    // CANDLE AGGREGATION - Convert minute data to multi-minute candles
    private List<AggregatedCandle> aggregateToCandles(List<MarketData> data, int intervalMinutes) {
        if (data.isEmpty()) return new ArrayList<>();

        List<AggregatedCandle> candles = new ArrayList<>();

        // Sort by timestamp
        List<MarketData> sortedData = data.stream()
                .sorted(Comparator.comparing(MarketData::getTimestamp))
                .collect(Collectors.toList());

        // Group by time intervals
        LocalDateTime currentCandleStart = null;
        List<MarketData> currentGroup = new ArrayList<>();

        for (MarketData md : sortedData) {
            LocalDateTime dataTime = md.getTimestamp();
            LocalDateTime intervalStart = dataTime.withSecond(0).withNano(0)
                    .withMinute((dataTime.getMinute() / intervalMinutes) * intervalMinutes);

            if (currentCandleStart == null || !currentCandleStart.equals(intervalStart)) {
                // Process previous group
                if (!currentGroup.isEmpty()) {
                    candles.add(createCandleFromGroup(currentGroup, currentCandleStart));
                }

                // Start new group
                currentCandleStart = intervalStart;
                currentGroup = new ArrayList<>();
            }

            currentGroup.add(md);
        }

        // Process last group
        if (!currentGroup.isEmpty()) {
            candles.add(createCandleFromGroup(currentGroup, currentCandleStart));
        }

        log.debug("[MTF-AGG] Aggregated {} data points into {} {}-min candles",
                data.size(), candles.size(), intervalMinutes);

        return candles;
    }

    // CREATE CANDLE from grouped data
    private AggregatedCandle createCandleFromGroup(List<MarketData> group, LocalDateTime candleStart) {
        if (group.isEmpty()) return null;

        // Sort by timestamp to get proper OHLC
        group.sort(Comparator.comparing(MarketData::getTimestamp));

        BigDecimal open = group.get(0).getPrice();
        BigDecimal close = group.get(group.size() - 1).getPrice();
        BigDecimal high = group.stream()
                .map(MarketData::getPrice)
                .max(BigDecimal::compareTo)
                .orElse(open);
        BigDecimal low = group.stream()
                .map(MarketData::getPrice)
                .min(BigDecimal::compareTo)
                .orElse(open);

        long totalVolume = group.stream()
                .filter(md -> md.getVolume() != null)
                .mapToLong(MarketData::getVolume)
                .sum();

        AggregatedCandle candle = new AggregatedCandle();
        candle.setTimestamp(candleStart);
        candle.setOpen(open);
        candle.setHigh(high);
        candle.setLow(low);
        candle.setClose(close);
        candle.setVolume(totalVolume);
        candle.setDataPoints(group.size());

        return candle;
    }

    // TREND CONFLUENCE CALCULATION
    private String calculateTrendConfluence(TimeframeData m1, TimeframeData m5, TimeframeData m15) {
        List<String> trends = Arrays.asList(m1.getTrend(), m5.getTrend(), m15.getTrend());

        long upCount = trends.stream().filter("UP"::equals).count();
        long downCount = trends.stream().filter("DOWN"::equals).count();
        long neutralCount = trends.stream().filter("NEUTRAL"::equals).count();

        if (upCount >= 2) return "UP";
        if (downCount >= 2) return "DOWN";
        return "NEUTRAL";
    }

    // CONFLUENCE SCORE (0.0 to 1.0)
    private double calculateConfluenceScore(TimeframeData m1, TimeframeData m5, TimeframeData m15) {
        // Weight different timeframes
        double m1Weight = 0.4;  // 40% - most recent
        double m5Weight = 0.35; // 35% - medium term
        double m15Weight = 0.25; // 25% - longer term

        // Calculate alignment score for each timeframe
        double m1Score = getTrendScore(m1.getTrend()) * m1Weight;
        double m5Score = getTrendScore(m5.getTrend()) * m5Weight;
        double m15Score = getTrendScore(m15.getTrend()) * m15Weight;

        double totalScore = Math.abs(m1Score + m5Score + m15Score);

        // Also consider momentum alignment
        double momentumAlignment = calculateMomentumAlignment(m1, m5, m15);

        // Final confluence score
        return Math.min(totalScore * momentumAlignment, 1.0);
    }

    // Helper methods for calculations
    private double getTrendScore(String trend) {
        switch (trend) {
            case "UP": return 1.0;
            case "DOWN": return -1.0;
            default: return 0.0;
        }
    }

    private double calculateMomentumAlignment(TimeframeData m1, TimeframeData m5, TimeframeData m15) {
        double[] momentums = {m1.getMomentum(), m5.getMomentum(), m15.getMomentum()};

        // Check if all momentums have same sign (all positive or all negative)
        boolean allPositive = Arrays.stream(momentums).allMatch(m -> m > 0);
        boolean allNegative = Arrays.stream(momentums).allMatch(m -> m < 0);

        if (allPositive || allNegative) return 1.2; // Boost for momentum alignment

        // Partial alignment
        long positiveCount = Arrays.stream(momentums).filter(m -> m > 0).count();
        return positiveCount >= 2 ? 1.0 : 0.8;
    }

    // SIMPLE TREND CALCULATION for minute data
    private String calculateTrend(List<MarketData> data) {
        if (data.size() < 3) return "NEUTRAL";

        data.sort(Comparator.comparing(MarketData::getTimestamp));

        BigDecimal first = data.get(0).getPrice();
        BigDecimal last = data.get(data.size() - 1).getPrice();

        BigDecimal change = last.subtract(first).divide(first, 4, RoundingMode.HALF_UP);

        if (change.compareTo(BigDecimal.valueOf(0.002)) > 0) return "UP";
        if (change.compareTo(BigDecimal.valueOf(-0.002)) < 0) return "DOWN";
        return "NEUTRAL";
    }

    // CANDLE-BASED TREND CALCULATION
    private String calculateCandleTrend(List<AggregatedCandle> candles) {
        if (candles.size() < 3) return "NEUTRAL";

        // Look at last 3 candles for trend
        List<AggregatedCandle> recent = candles.subList(Math.max(0, candles.size() - 3), candles.size());

        int upCandles = 0;
        int downCandles = 0;

        for (AggregatedCandle candle : recent) {
            if (candle.getClose().compareTo(candle.getOpen()) > 0) {
                upCandles++;
            } else if (candle.getClose().compareTo(candle.getOpen()) < 0) {
                downCandles++;
            }
        }

        if (upCandles > downCandles) return "UP";
        if (downCandles > upCandles) return "DOWN";
        return "NEUTRAL";
    }

    // MOMENTUM CALCULATIONS
    private double calculateMomentum(List<MarketData> data) {
        if (data.size() < 2) return 0.0;

        data.sort(Comparator.comparing(MarketData::getTimestamp));

        BigDecimal recent = data.get(data.size() - 1).getPrice();
        BigDecimal earlier = data.get(0).getPrice();

        return recent.subtract(earlier).divide(earlier, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private double calculateCandleMomentum(List<AggregatedCandle> candles) {
        if (candles.size() < 2) return 0.0;

        AggregatedCandle latest = candles.get(candles.size() - 1);
        AggregatedCandle earlier = candles.get(0);

        return latest.getClose().subtract(earlier.getOpen())
                .divide(earlier.getOpen(), 4, RoundingMode.HALF_UP).doubleValue();
    }

    // VOLUME STRENGTH CALCULATIONS
    private double calculateVolumeStrength(List<MarketData> data) {
        if (data.isEmpty()) return 0.0;

        double avgVolume = data.stream()
                .filter(md -> md.getVolume() != null)
                .mapToLong(MarketData::getVolume)
                .average()
                .orElse(1000000);

        // Compare recent volume to average
        Long recentVolume = data.get(data.size() - 1).getVolume();
        if (recentVolume == null) return 0.5;

        return Math.min(recentVolume / avgVolume, 3.0); // Cap at 3x
    }

    private double calculateCandleVolumeStrength(List<AggregatedCandle> candles) {
        if (candles.isEmpty()) return 0.0;

        double avgVolume = candles.stream()
                .mapToLong(AggregatedCandle::getVolume)
                .average()
                .orElse(1000000);

        long recentVolume = candles.get(candles.size() - 1).getVolume();

        return Math.min(recentVolume / avgVolume, 3.0); // Cap at 3x
    }

    // HELPER METHODS
    private TimeframeData createNeutralTimeframeData(String timeframe) {
        TimeframeData data = new TimeframeData();
        data.setTimeframe(timeframe);
        data.setTrend("NEUTRAL");
        data.setMomentum(0.0);
        data.setVolumeStrength(0.5);
        data.setDataPoints(0);
        return data;
    }

    private MultiTimeframeResult createNeutralResult() {
        MultiTimeframeResult result = new MultiTimeframeResult();
        result.setM1Data(createNeutralTimeframeData("1M"));
        result.setM5Data(createNeutralTimeframeData("5M"));
        result.setM15Data(createNeutralTimeframeData("15M"));
        result.setOverallTrend("NEUTRAL");
        result.setConfluenceScore(0.5);
        return result;
    }

    // DATA CLASSES
    @Data
    public static class MultiTimeframeResult {
        private TimeframeData m1Data;
        private TimeframeData m5Data;
        private TimeframeData m15Data;
        private String overallTrend;
        private double confluenceScore;
    }

    @Data
    public static class TimeframeData {
        private String timeframe;
        private String trend;
        private double momentum;
        private double volumeStrength;
        private int dataPoints;
    }

    @Data
    private static class AggregatedCandle {
        private LocalDateTime timestamp;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private long volume;
        private int dataPoints;
    }
}