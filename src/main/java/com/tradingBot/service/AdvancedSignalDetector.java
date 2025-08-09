package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.entity.Signal;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdvancedSignalDetector {

    private final TradierService tradierService;
    private final MarketDataRepository marketDataRepository;
    private final TechnicalAnalysisService technicalAnalysisService;

    @Data
    public static class TrendlineBreak {
        private BigDecimal trendlineValue;
        private String direction; // UP, DOWN
        private double strength; // 0-1
        private int touchPoints;
        private LocalDateTime breakTime;
        private boolean confirmed;
    }

    @Data
    public static class GapAnalysis {
        private BigDecimal gapSize;
        private BigDecimal gapPercentage;
        private String direction; // UP, DOWN
        private BigDecimal fillLevel;
        private boolean hasFilledGap;
        private boolean isSignificantGap;
        private String gapType; // BREAKAWAY, RUNAWAY, EXHAUSTION, COMMON
    }

    @Data
    public static class HighLowBreach {
        private BigDecimal yesterdayHigh;
        private BigDecimal yesterdayLow;
        private BigDecimal currentPrice;
        private boolean breachedHigh;
        private boolean breachedLow;
        private double volumeConfirmation;
        private LocalDateTime breachTime;
    }

    @Data
    public static class AdvancedSignalResult {
        private List<Signal> trendlineBreakSignals;
        private List<Signal> gapFillSignals;
        private List<Signal> highLowBreachSignals;
        private Map<String, Object> explainableFeatures;
        private double overallConfidence;
    }

    public AdvancedSignalResult detectAdvancedSignals(String symbol, String marketTrend) {
        AdvancedSignalResult result = new AdvancedSignalResult();
        result.setTrendlineBreakSignals(new ArrayList<>());
        result.setGapFillSignals(new ArrayList<>());
        result.setHighLowBreachSignals(new ArrayList<>());
        result.setExplainableFeatures(new HashMap<>());

        try {
            // Get market data for analysis
            LocalDateTime sessionStart = LocalDateTime.now().withHour(9).withMinute(30);
            List<MarketData> sessionData = marketDataRepository
                    .findBySymbolAndTimestampAfterOrderByTimestampAsc(symbol, sessionStart);

            if (sessionData.isEmpty()) {
                log.warn("No session data available for advanced signal detection");
                return result;
            }

            // Detect trendline breaks
            List<Signal> trendlineSignals = detectTrendlineBreaks(symbol, sessionData, marketTrend);
            result.setTrendlineBreakSignals(trendlineSignals);

            // Detect gap fills
            List<Signal> gapSignals = detectGapFillSignals(symbol, sessionData, marketTrend);
            result.setGapFillSignals(gapSignals);

            // Detect high/low breaches
            List<Signal> highLowSignals = detectHighLowBreaches(symbol, sessionData, marketTrend);
            result.setHighLowBreachSignals(highLowSignals);

            // Calculate explainable features
            result.setExplainableFeatures(calculateExplainableFeatures(sessionData, symbol));

            // Calculate overall confidence
            int totalSignals = trendlineSignals.size() + gapSignals.size() + highLowSignals.size();
            double avgConfidence = getAllSignals(result).stream()
                    .mapToDouble(Signal::getConfidence)
                    .average()
                    .orElse(0.0);
            result.setOverallConfidence(avgConfidence);

            log.info("[ADVANCED] {} - Trendline: {}, Gap: {}, HighLow: {}, Overall Confidence: {}%",
                    symbol, trendlineSignals.size(), gapSignals.size(), highLowSignals.size(),
                    (int)(avgConfidence * 100));

        } catch (Exception e) {
            log.error("Error in advanced signal detection: {}", e.getMessage());
        }

        return result;
    }

    private List<Signal> detectTrendlineBreaks(String symbol, List<MarketData> data, String marketTrend) {
        List<Signal> signals = new ArrayList<>();

        try {
            // Find trendlines (support and resistance)
            List<TrendlineBreak> trendlineBreaks = findTrendlineBreaks(data);

            for (TrendlineBreak breakEvent : trendlineBreaks) {
                if (breakEvent.isConfirmed() && breakEvent.getStrength() > 0.7) {

                    // Get current options for the symbol
                    List<com.tradingBot.model.Option> options = getATMOptions(symbol);

                    for (com.tradingBot.model.Option option : options) {
                        boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
                        boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

                        // Bullish trendline break (resistance break)
                        if ("UP".equals(breakEvent.getDirection()) && isCallOption && "UP".equals(marketTrend)) {
                            Signal signal = createTrendlineBreakSignal(option, breakEvent, "BUY",
                                    "0DTE_TRENDLINE_BREAK_CALL", marketTrend);
                            signals.add(signal);

                            log.info("[TRENDLINE] ✅ Resistance break at ${} - {} CALL signal generated",
                                    breakEvent.getTrendlineValue(), option.getStrikePrice());
                        }

                        // Bearish trendline break (support break)
                        if ("DOWN".equals(breakEvent.getDirection()) && isPutOption && "DOWN".equals(marketTrend)) {
                            Signal signal = createTrendlineBreakSignal(option, breakEvent, "BUY",
                                    "0DTE_TRENDLINE_BREAK_PUT", marketTrend);
                            signals.add(signal);

                            log.info("[TRENDLINE] ✅ Support break at ${} - {} PUT signal generated",
                                    breakEvent.getTrendlineValue(), option.getStrikePrice());
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error detecting trendline breaks: {}", e.getMessage());
        }

        return signals;
    }

    private List<Signal> detectGapFillSignals(String symbol, List<MarketData> sessionData, String marketTrend) {
        List<Signal> signals = new ArrayList<>();

        try {
            GapAnalysis gapAnalysis = analyzeGaps(symbol, sessionData);

            if (gapAnalysis.isSignificantGap() && !gapAnalysis.isHasFilledGap()) {
                // Gap fill opportunity detected
                List<com.tradingBot.model.Option> options = getATMOptions(symbol);

                for (com.tradingBot.model.Option option : options) {
                    boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
                    boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

                    // Gap up - expect fill (bearish)
                    if ("UP".equals(gapAnalysis.getDirection()) && isPutOption) {
                        double confidence = calculateGapFillConfidence(gapAnalysis, false);
                        if (confidence >= 0.65) {
                            Signal signal = createGapFillSignal(option, gapAnalysis, "BUY",
                                    "0DTE_GAP_FILL_PUT", confidence, marketTrend);
                            signals.add(signal);

                            log.info("[GAP] ✅ Gap up fill signal - {} PUT, Gap: {}%, Fill target: ${}",
                                    option.getStrikePrice(),
                                    gapAnalysis.getGapPercentage().multiply(BigDecimal.valueOf(100)),
                                    gapAnalysis.getFillLevel());
                        }
                    }

                    // Gap down - expect fill (bullish)
                    if ("DOWN".equals(gapAnalysis.getDirection()) && isCallOption) {
                        double confidence = calculateGapFillConfidence(gapAnalysis, true);
                        if (confidence >= 0.65) {
                            Signal signal = createGapFillSignal(option, gapAnalysis, "BUY",
                                    "0DTE_GAP_FILL_CALL", confidence, marketTrend);
                            signals.add(signal);

                            log.info("[GAP] ✅ Gap down fill signal - {} CALL, Gap: {}%, Fill target: ${}",
                                    option.getStrikePrice(),
                                    gapAnalysis.getGapPercentage().multiply(BigDecimal.valueOf(100)),
                                    gapAnalysis.getFillLevel());
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error detecting gap fill signals: {}", e.getMessage());
        }

        return signals;
    }

    private List<Signal> detectHighLowBreaches(String symbol, List<MarketData> sessionData, String marketTrend) {
        List<Signal> signals = new ArrayList<>();

        try {
            HighLowBreach breach = analyzeHighLowBreaches(symbol, sessionData);

            if (breach != null && breach.getVolumeConfirmation() > 1.2) {
                List<com.tradingBot.model.Option> options = getATMOptions(symbol);

                for (com.tradingBot.model.Option option : options) {
                    boolean isCallOption = "CALL".equalsIgnoreCase(option.getType());
                    boolean isPutOption = "PUT".equalsIgnoreCase(option.getType());

                    // Yesterday's high breach (bullish continuation)
                    if (breach.isBreachedHigh() && isCallOption && "UP".equals(marketTrend)) {
                        double confidence = calculateHighLowBreachConfidence(breach, true);
                        if (confidence >= 0.70) {
                            Signal signal = createHighLowBreachSignal(option, breach, "BUY",
                                    "0DTE_HIGH_BREACH_CALL", confidence, marketTrend);
                            signals.add(signal);

                            log.info("[HIGH_BREACH] ✅ Yesterday's high ${} breached - {} CALL signal",
                                    breach.getYesterdayHigh(), option.getStrikePrice());
                        }
                    }

                    // Yesterday's low breach (bearish continuation)
                    if (breach.isBreachedLow() && isPutOption && "DOWN".equals(marketTrend)) {
                        double confidence = calculateHighLowBreachConfidence(breach, false);
                        if (confidence >= 0.70) {
                            Signal signal = createHighLowBreachSignal(option, breach, "BUY",
                                    "0DTE_LOW_BREACH_PUT", confidence, marketTrend);
                            signals.add(signal);

                            log.info("[LOW_BREACH] ✅ Yesterday's low ${} breached - {} PUT signal",
                                    breach.getYesterdayLow(), option.getStrikePrice());
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error detecting high/low breaches: {}", e.getMessage());
        }

        return signals;
    }

    // Implementation methods

    private List<TrendlineBreak> findTrendlineBreaks(List<MarketData> data) {
        List<TrendlineBreak> breaks = new ArrayList<>();

        if (data.size() < 20) return breaks;

        // Find significant highs and lows for trendline construction
        List<MarketData> pivotHighs = findPivotHighs(data, 5);
        List<MarketData> pivotLows = findPivotLows(data, 5);

        // Construct resistance trendlines from highs
        if (pivotHighs.size() >= 2) {
            TrendlineBreak resistanceBreak = checkTrendlineBreak(pivotHighs, data, "RESISTANCE");
            if (resistanceBreak != null) breaks.add(resistanceBreak);
        }

        // Construct support trendlines from lows
        if (pivotLows.size() >= 2) {
            TrendlineBreak supportBreak = checkTrendlineBreak(pivotLows, data, "SUPPORT");
            if (supportBreak != null) breaks.add(supportBreak);
        }

        return breaks;
    }

    private List<MarketData> findPivotHighs(List<MarketData> data, int lookback) {
        List<MarketData> pivots = new ArrayList<>();

        for (int i = lookback; i < data.size() - lookback; i++) {
            MarketData current = data.get(i);
            BigDecimal currentHigh = current.getHigh() != null ? current.getHigh() : current.getPrice();

            boolean isPivot = true;
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j != i) {
                    MarketData other = data.get(j);
                    BigDecimal otherHigh = other.getHigh() != null ? other.getHigh() : other.getPrice();
                    if (otherHigh.compareTo(currentHigh) > 0) {
                        isPivot = false;
                        break;
                    }
                }
            }

            if (isPivot) {
                pivots.add(current);
            }
        }

        return pivots;
    }

    private List<MarketData> findPivotLows(List<MarketData> data, int lookback) {
        List<MarketData> pivots = new ArrayList<>();

        for (int i = lookback; i < data.size() - lookback; i++) {
            MarketData current = data.get(i);
            BigDecimal currentLow = current.getLow() != null ? current.getLow() : current.getPrice();

            boolean isPivot = true;
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j != i) {
                    MarketData other = data.get(j);
                    BigDecimal otherLow = other.getLow() != null ? other.getLow() : other.getPrice();
                    if (otherLow.compareTo(currentLow) < 0) {
                        isPivot = false;
                        break;
                    }
                }
            }

            if (isPivot) {
                pivots.add(current);
            }
        }

        return pivots;
    }

    private TrendlineBreak checkTrendlineBreak(List<MarketData> pivots, List<MarketData> allData, String type) {
        if (pivots.size() < 2) return null;

        // Use last two pivots to create trendline
        MarketData pivot1 = pivots.get(pivots.size() - 2);
        MarketData pivot2 = pivots.get(pivots.size() - 1);

        BigDecimal price1 = "RESISTANCE".equals(type) ?
                (pivot1.getHigh() != null ? pivot1.getHigh() : pivot1.getPrice()) :
                (pivot1.getLow() != null ? pivot1.getLow() : pivot1.getPrice());
        BigDecimal price2 = "RESISTANCE".equals(type) ?
                (pivot2.getHigh() != null ? pivot2.getHigh() : pivot2.getPrice()) :
                (pivot2.getLow() != null ? pivot2.getLow() : pivot2.getPrice());

        // Calculate trendline slope and current value
        long timeDiff = java.time.Duration.between(pivot1.getTimestamp(), pivot2.getTimestamp()).toMinutes();
        if (timeDiff <= 0) return null;

        BigDecimal priceSlope = price2.subtract(price1).divide(BigDecimal.valueOf(timeDiff), 6, RoundingMode.HALF_UP);
        MarketData currentData = allData.get(allData.size() - 1);
        long currentTimeDiff = java.time.Duration.between(pivot2.getTimestamp(), currentData.getTimestamp()).toMinutes();
        BigDecimal currentTrendlineValue = price2.add(priceSlope.multiply(BigDecimal.valueOf(currentTimeDiff)));

        // Check for break
        BigDecimal currentPrice = currentData.getPrice();
        boolean hasBreak = false;
        String direction = "";

        if ("RESISTANCE".equals(type) && currentPrice.compareTo(currentTrendlineValue) > 0) {
            hasBreak = true;
            direction = "UP";
        } else if ("SUPPORT".equals(type) && currentPrice.compareTo(currentTrendlineValue) < 0) {
            hasBreak = true;
            direction = "DOWN";
        }

        if (hasBreak) {
            TrendlineBreak breakEvent = new TrendlineBreak();
            breakEvent.setTrendlineValue(currentTrendlineValue);
            breakEvent.setDirection(direction);
            breakEvent.setStrength(calculateTrendlineStrength(pivots, currentTrendlineValue));
            breakEvent.setTouchPoints(pivots.size());
            breakEvent.setBreakTime(currentData.getTimestamp());
            breakEvent.setConfirmed(true);
            return breakEvent;
        }

        return null;
    }

    private double calculateTrendlineStrength(List<MarketData> pivots, BigDecimal trendlineValue) {
        // More pivots = stronger trendline
        double strength = Math.min(pivots.size() / 5.0, 1.0);
        return strength;
    }

    private GapAnalysis analyzeGaps(String symbol, List<MarketData> sessionData) {
        GapAnalysis analysis = new GapAnalysis();

        try {
            // Get yesterday's close
            QuoteResponse quote = tradierService.getQuote(symbol);
            if (quote == null || quote.getQuote().getPreviousClose() == null) {
                return analysis;
            }

            BigDecimal yesterdayClose = quote.getQuote().getPreviousClose();
            BigDecimal todayOpen = sessionData.get(0).getPrice();

            // Calculate gap
            BigDecimal gapSize = todayOpen.subtract(yesterdayClose);
            BigDecimal gapPercentage = gapSize.divide(yesterdayClose, 4, RoundingMode.HALF_UP);

            analysis.setGapSize(gapSize);
            analysis.setGapPercentage(gapPercentage);
            analysis.setDirection(gapSize.compareTo(BigDecimal.ZERO) > 0 ? "UP" : "DOWN");
            analysis.setFillLevel(yesterdayClose);

            // Check if gap is significant (>0.5%)
            analysis.setSignificantGap(gapPercentage.abs().compareTo(BigDecimal.valueOf(0.005)) > 0);

            // Check if gap has been filled
            BigDecimal currentPrice = sessionData.get(sessionData.size() - 1).getPrice();
            if ("UP".equals(analysis.getDirection())) {
                analysis.setHasFilledGap(currentPrice.compareTo(yesterdayClose) <= 0);
            } else {
                analysis.setHasFilledGap(currentPrice.compareTo(yesterdayClose) >= 0);
            }

            // Classify gap type (simplified)
            if (gapPercentage.abs().compareTo(BigDecimal.valueOf(0.02)) > 0) {
                analysis.setGapType("BREAKAWAY"); // Large gap
            } else {
                analysis.setGapType("COMMON"); // Small gap
            }

        } catch (Exception e) {
            log.error("Error analyzing gaps: {}", e.getMessage());
        }

        return analysis;
    }

    private HighLowBreach analyzeHighLowBreaches(String symbol, List<MarketData> sessionData) {
        HighLowBreach breach = new HighLowBreach();

        try {
            // Get yesterday's high/low (simplified - would need historical data)
            // For now, use current quote data
            QuoteResponse quote = tradierService.getQuote(symbol);
            if (quote == null) return null;

            // Simplified: use 52-week high/low as proxy
            BigDecimal yesterdayHigh = quote.getQuote().getWeek52High();
            BigDecimal yesterdayLow = quote.getQuote().getWeek52Low();
            BigDecimal currentPrice = quote.getQuote().getLast();

            if (yesterdayHigh == null || yesterdayLow == null) return null;

            breach.setYesterdayHigh(yesterdayHigh);
            breach.setYesterdayLow(yesterdayLow);
            breach.setCurrentPrice(currentPrice);

            // Check breaches
            breach.setBreachedHigh(currentPrice.compareTo(yesterdayHigh) > 0);
            breach.setBreachedLow(currentPrice.compareTo(yesterdayLow) < 0);

            // Volume confirmation
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            breach.setVolumeConfirmation(ta != null ? ta.getVolumeRatio() : 1.0);

            breach.setBreachTime(LocalDateTime.now());

        } catch (Exception e) {
            log.error("Error analyzing high/low breaches: {}", e.getMessage());
            return null;
        }

        return breach;
    }

    // Signal creation methods

    private Signal createTrendlineBreakSignal(com.tradingBot.model.Option option, TrendlineBreak breakEvent,
                                              String signalType, String strategy, String marketTrend) {
        Signal signal = new Signal();
        signal.setSymbol("QQQ");
        signal.setOptionSymbol(option.getSymbol());
        signal.setSignalType(signalType);
        signal.setStrategy(strategy);
        signal.setConfidence(0.75 + (breakEvent.getStrength() * 0.15)); // 75-90% confidence
        signal.setTimestamp(LocalDateTime.now());
        signal.setExecuted(false);
        signal.setEntryPrice(option.getMidPrice());
        signal.setMarketTrend(marketTrend);

        // Dynamic targets based on trendline break strength
        BigDecimal entryPrice = option.getMidPrice();
        signal.setTargetPrice(entryPrice.multiply(BigDecimal.valueOf(1.6 + breakEvent.getStrength() * 0.4)));
        signal.setStopLoss(entryPrice.multiply(BigDecimal.valueOf(0.65)));

        signal.setReason(String.format("Trendline break %s at $%.2f with %d touch points",
                breakEvent.getDirection(), breakEvent.getTrendlineValue(), breakEvent.getTouchPoints()));

        return signal;
    }

    private Signal createGapFillSignal(com.tradingBot.model.Option option, GapAnalysis gapAnalysis,
                                       String signalType, String strategy, double confidence, String marketTrend) {
        Signal signal = new Signal();
        signal.setSymbol("QQQ");
        signal.setOptionSymbol(option.getSymbol());
        signal.setSignalType(signalType);
        signal.setStrategy(strategy);
        signal.setConfidence(confidence);
        signal.setTimestamp(LocalDateTime.now());
        signal.setExecuted(false);
        signal.setEntryPrice(option.getMidPrice());
        signal.setMarketTrend(marketTrend);

        // Targets based on gap size
        BigDecimal entryPrice = option.getMidPrice();
        double gapMagnitude = gapAnalysis.getGapPercentage().abs().doubleValue();
        signal.setTargetPrice(entryPrice.multiply(BigDecimal.valueOf(1.4 + gapMagnitude * 20)));
        signal.setStopLoss(entryPrice.multiply(BigDecimal.valueOf(0.70)));

        signal.setReason(String.format("Gap fill opportunity - %s gap of %.2f%% targeting $%.2f",
                gapAnalysis.getDirection(),
                gapAnalysis.getGapPercentage().multiply(BigDecimal.valueOf(100)),
                gapAnalysis.getFillLevel()));

        return signal;
    }

    private Signal createHighLowBreachSignal(com.tradingBot.model.Option option, HighLowBreach breach,
                                             String signalType, String strategy, double confidence, String marketTrend) {
        Signal signal = new Signal();
        signal.setSymbol("QQQ");
        signal.setOptionSymbol(option.getSymbol());
        signal.setSignalType(signalType);
        signal.setStrategy(strategy);
        signal.setConfidence(confidence);
        signal.setTimestamp(LocalDateTime.now());
        signal.setExecuted(false);
        signal.setEntryPrice(option.getMidPrice());
        signal.setMarketTrend(marketTrend);

        // Targets based on volume confirmation
        BigDecimal entryPrice = option.getMidPrice();
        double volumeBoost = Math.min(breach.getVolumeConfirmation() / 2.0, 0.3);
        signal.setTargetPrice(entryPrice.multiply(BigDecimal.valueOf(1.5 + volumeBoost)));
        signal.setStopLoss(entryPrice.multiply(BigDecimal.valueOf(0.70)));

        signal.setReason(String.format("High/Low breach with %.1fx volume confirmation",
                breach.getVolumeConfirmation()));

        return signal;
    }

    // Helper methods

    private double calculateGapFillConfidence(GapAnalysis gapAnalysis, boolean bullish) {
        double baseConfidence = 0.65;

        // Larger gaps more likely to fill
        double gapMagnitude = gapAnalysis.getGapPercentage().abs().doubleValue();
        if (gapMagnitude > 0.02) baseConfidence += 0.15; // >2% gap
        else if (gapMagnitude > 0.01) baseConfidence += 0.10; // >1% gap

        // Time of day factor
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(11, 0))) {
            baseConfidence += 0.10; // Morning gaps more likely to fill
        }

        return Math.min(baseConfidence, 0.90);
    }

    private double calculateHighLowBreachConfidence(HighLowBreach breach, boolean bullish) {
        double baseConfidence = 0.70;

        // Volume confirmation
        if (breach.getVolumeConfirmation() > 2.0) baseConfidence += 0.15;
        else if (breach.getVolumeConfirmation() > 1.5) baseConfidence += 0.10;

        return Math.min(baseConfidence, 0.90);
    }

    private List<com.tradingBot.model.Option> getATMOptions(String symbol) {
        try {
            LocalDate today = LocalDate.now();
            com.tradingBot.model.OptionChainResponse chain = tradierService.getOptionChain(symbol, today);

            if (chain == null || !chain.hasOptions()) {
                return new ArrayList<>();
            }

            QuoteResponse quote = tradierService.getQuote(symbol);
            if (quote == null) return new ArrayList<>();

            BigDecimal currentPrice = quote.getQuote().getLast();

            // Get ATM options (within $2 of current price)
            return chain.getOptionsList().stream()
                    .filter(option -> option.getStrikePrice().subtract(currentPrice).abs()
                            .compareTo(BigDecimal.valueOf(2.0)) <= 0)
                    .filter(option -> option.getVolume() >= 50)
                    .limit(4) // Limit to 4 closest strikes
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error getting ATM options: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> calculateExplainableFeatures(List<MarketData> sessionData, String symbol) {
        Map<String, Object> features = new HashMap<>();

        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta != null) {
                features.put("volumeRatio", ta.getVolumeRatio());
                features.put("momentumStrength", ta.getMomentumStrength());
                features.put("vwapDeviation", Math.abs(ta.getPriceToVwapRatio() - 1.0));
                features.put("rsi", ta.getRsi());
                features.put("marketRegime", ta.getMarketRegime().toString());
                features.put("priceVolatility", ta.getCurrentVolatility());
            }

            features.put("sessionDataPoints", sessionData.size());
            features.put("timeOfDay", LocalTime.now().toString());

        } catch (Exception e) {
            log.error("Error calculating explainable features: {}", e.getMessage());
        }

        return features;
    }

    private List<Signal> getAllSignals(AdvancedSignalResult result) {
        List<Signal> allSignals = new ArrayList<>();
        allSignals.addAll(result.getTrendlineBreakSignals());
        allSignals.addAll(result.getGapFillSignals());
        allSignals.addAll(result.getHighLowBreachSignals());
        return allSignals;
    }
}