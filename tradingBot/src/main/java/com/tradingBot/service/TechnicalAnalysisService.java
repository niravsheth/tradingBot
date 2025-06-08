package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class TechnicalAnalysisService {
    private final MarketDataRepository marketDataRepository;

    public TechnicalAnalysis analyze(String symbol, BigDecimal currentPrice) {
        TechnicalAnalysis ta = new TechnicalAnalysis();

        // Get historical data
        List<MarketData> data5min = marketDataRepository
                .findBySymbolAndTimestampAfterOrderByTimestampAsc(symbol,
                        LocalDateTime.now().minusMinutes(60));
        List<MarketData> dataDaily = marketDataRepository
                .findBySymbolAndTimestampAfterOrderByTimestampAsc(symbol,
                        LocalDateTime.now().minusDays(10));

        // Calculate VWAP
        BigDecimal vwap = calculateVWAP(data5min);
        ta.setVwap(vwap);
        ta.setCurrentPrice(currentPrice);

        // Check VWAP bounce conditions
        ta.setNearVWAP(isNearVWAP(currentPrice, vwap));
        ta.setVWAPBounce(checkVWAPBounce(data5min, vwap));
        ta.setBullishVWAPBounce(checkBullishVWAPBounce(data5min, vwap));
        ta.setBearishVWAPBounce(checkBearishVWAPBounce(data5min, vwap));

        // Detect trendline patterns
        ta.setTrendlineBreakdown(detectTrendlinePattern(data5min));

        // Identify key levels
        ta.setKeyLevels(identifyKeyLevels(dataDaily));
        ta.setKeyLevelSignal(checkKeyLevelInteraction(currentPrice, ta.getKeyLevels()));

        // Detect chart patterns
        ta.setChartPattern(detectChartPatterns(data5min));

        // Basic momentum
        ta.setBullishMomentum(calculateMomentum(data5min) > 0.002);
        ta.setBearishMomentum(calculateMomentum(data5min) < -0.002);

        return ta;
    }

    private BigDecimal calculateVWAP(List<MarketData> data) {
        if (data.isEmpty()) return BigDecimal.ZERO;

        BigDecimal sumPriceVolume = BigDecimal.ZERO;
        BigDecimal sumVolume = BigDecimal.ZERO;

        for (MarketData md : data) {
            BigDecimal typicalPrice = md.getHigh().add(md.getLow()).add(md.getPrice())
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            sumPriceVolume = sumPriceVolume.add(
                    typicalPrice.multiply(BigDecimal.valueOf(md.getVolume())));
            sumVolume = sumVolume.add(BigDecimal.valueOf(md.getVolume()));
        }

        if (sumVolume.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return sumPriceVolume.divide(sumVolume, 2, RoundingMode.HALF_UP);
    }

    private boolean isNearVWAP(BigDecimal price, BigDecimal vwap) {
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return false;
        BigDecimal diff = price.subtract(vwap).abs();
        BigDecimal threshold = vwap.multiply(BigDecimal.valueOf(0.002)); // 0.2%
        return diff.compareTo(threshold) <= 0;
    }

    private boolean checkVWAPBounce(List<MarketData> data, BigDecimal vwap) {
        if (data.size() < 3) return false;

        MarketData current = data.get(data.size() - 1);
        MarketData prev1 = data.get(data.size() - 2);
        MarketData prev2 = data.get(data.size() - 3);

        // Check if price touched VWAP and bounced
        boolean touchedVWAP = prev1.getLow().compareTo(vwap) <= 0 &&
                prev1.getHigh().compareTo(vwap) >= 0;
        boolean bouncing = current.getPrice().compareTo(prev1.getPrice()) > 0;

        return touchedVWAP && bouncing;
    }

    private boolean checkBullishVWAPBounce(List<MarketData> data, BigDecimal vwap) {
        if (!checkVWAPBounce(data, vwap)) return false;

        MarketData current = data.get(data.size() - 1);
        boolean aboveVWAP = current.getPrice().compareTo(vwap) > 0;
        boolean increasingVolume = current.getVolume() > data.get(data.size() - 2).getVolume();

        return aboveVWAP && increasingVolume;
    }

    private boolean checkBearishVWAPBounce(List<MarketData> data, BigDecimal vwap) {
        if (data.size() < 3) return false;

        MarketData current = data.get(data.size() - 1);
        MarketData prev1 = data.get(data.size() - 2);

        // Check if price touched VWAP from below and got rejected
        boolean touchedVWAP = prev1.getHigh().compareTo(vwap) >= 0 &&
                prev1.getLow().compareTo(vwap) <= 0;
        boolean rejected = current.getPrice().compareTo(vwap) < 0 &&
                current.getPrice().compareTo(prev1.getPrice()) < 0;
        boolean increasingVolume = current.getVolume() > prev1.getVolume();

        return touchedVWAP && rejected && increasingVolume;
    }

    private String detectTrendlinePattern(List<MarketData> data) {
        if (data.size() < 10) return null;

        // Find recent highs and lows
        List<Integer> highIndices = new ArrayList<>();
        List<Integer> lowIndices = new ArrayList<>();

        for (int i = 2; i < data.size() - 2; i++) {
            BigDecimal price = data.get(i).getHigh();
            if (price.compareTo(data.get(i-1).getHigh()) > 0 &&
                    price.compareTo(data.get(i-2).getHigh()) > 0 &&
                    price.compareTo(data.get(i+1).getHigh()) > 0 &&
                    price.compareTo(data.get(i+2).getHigh()) > 0) {
                highIndices.add(i);
            }

            price = data.get(i).getLow();
            if (price.compareTo(data.get(i-1).getLow()) < 0 &&
                    price.compareTo(data.get(i-2).getLow()) < 0 &&
                    price.compareTo(data.get(i+1).getLow()) < 0 &&
                    price.compareTo(data.get(i+2).getLow()) < 0) {
                lowIndices.add(i);
            }
        }

        // Check for trendline breakouts
        if (highIndices.size() >= 2) {
            BigDecimal slope = calculateTrendlineSlope(data, highIndices);
            BigDecimal currentPrice = data.get(data.size() - 1).getPrice();
            BigDecimal trendlineValue = extrapolateTrendline(data, highIndices, slope);

            if (currentPrice.compareTo(trendlineValue) > 0) {
                return "BULLISH_BREAKOUT";
            }
        }

        if (lowIndices.size() >= 2) {
            BigDecimal slope = calculateTrendlineSlope(data, lowIndices);
            BigDecimal currentPrice = data.get(data.size() - 1).getPrice();
            BigDecimal trendlineValue = extrapolateTrendline(data, lowIndices, slope);

            if (currentPrice.compareTo(trendlineValue) < 0) {
                return "BEARISH_BREAKDOWN";
            }
        }

        return null;
    }

    private Map<String, KeyLevel> identifyKeyLevels(List<MarketData> data) {
        Map<String, KeyLevel> levels = new HashMap<>();

        if (data.isEmpty()) return levels;

        // Yesterday's high/low
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1).withHour(16).withMinute(0);
        List<MarketData> yesterdayData = data.stream()
                .filter(d -> d.getTimestamp().toLocalDate().equals(yesterday.toLocalDate()))
                .toList();

        if (!yesterdayData.isEmpty()) {
            BigDecimal yHigh = yesterdayData.stream()
                    .map(MarketData::getHigh)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            BigDecimal yLow = yesterdayData.stream()
                    .map(MarketData::getLow)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            levels.put("YESTERDAY_HIGH", new KeyLevel("YESTERDAY_HIGH", yHigh, "resistance"));
            levels.put("YESTERDAY_LOW", new KeyLevel("YESTERDAY_LOW", yLow, "support"));
        }

        // 3-day high/low
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        List<MarketData> threeDayData = data.stream()
                .filter(d -> d.getTimestamp().isAfter(threeDaysAgo))
                .toList();

        if (!threeDayData.isEmpty()) {
            BigDecimal high3d = threeDayData.stream()
                    .map(MarketData::getHigh)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            BigDecimal low3d = threeDayData.stream()
                    .map(MarketData::getLow)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            levels.put("3DAY_HIGH", new KeyLevel("3DAY_HIGH", high3d, "resistance"));
            levels.put("3DAY_LOW", new KeyLevel("3DAY_LOW", low3d, "support"));
        }

        // Weekly high/low
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<MarketData> weekData = data.stream()
                .filter(d -> d.getTimestamp().isAfter(weekAgo))
                .toList();

        if (!weekData.isEmpty()) {
            BigDecimal highWeek = weekData.stream()
                    .map(MarketData::getHigh)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            BigDecimal lowWeek = weekData.stream()
                    .map(MarketData::getLow)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            levels.put("WEEKLY_HIGH", new KeyLevel("WEEKLY_HIGH", highWeek, "resistance"));
            levels.put("WEEKLY_LOW", new KeyLevel("WEEKLY_LOW", lowWeek, "support"));
        }

        return levels;
    }

    private KeyLevelSignal checkKeyLevelInteraction(BigDecimal currentPrice, Map<String, KeyLevel> levels) {
        for (Map.Entry<String, KeyLevel> entry : levels.entrySet()) {
            KeyLevel level = entry.getValue();
            BigDecimal threshold = level.getPrice().multiply(BigDecimal.valueOf(0.001)); // 0.1%

            if (level.getType().equals("support")) {
                BigDecimal diff = currentPrice.subtract(level.getPrice()).abs();
                if (diff.compareTo(threshold) <= 0 && currentPrice.compareTo(level.getPrice()) >= 0) {
                    return new KeyLevelSignal("SUPPORT_BOUNCE", entry.getKey(), level.getPrice());
                }
            } else if (level.getType().equals("resistance")) {
                BigDecimal diff = level.getPrice().subtract(currentPrice).abs();
                if (diff.compareTo(threshold) <= 0 && currentPrice.compareTo(level.getPrice()) <= 0) {
                    return new KeyLevelSignal("RESISTANCE_REJECTION", entry.getKey(), level.getPrice());
                }
            }
        }
        return null;
    }

    private ChartPattern detectChartPatterns(List<MarketData> data) {
        if (data.size() < 20) return null;

        // Find recent peaks and troughs
        List<Peak> peaks = findPeaks(data);
        List<Trough> troughs = findTroughs(data);

        // Check for double top
        if (peaks.size() >= 2) {
            Peak peak1 = peaks.get(peaks.size() - 2);
            Peak peak2 = peaks.get(peaks.size() - 1);

            BigDecimal priceDiff = peak1.getPrice().subtract(peak2.getPrice()).abs();
            BigDecimal threshold = peak1.getPrice().multiply(BigDecimal.valueOf(0.002)); // 0.2%

            if (priceDiff.compareTo(threshold) <= 0 &&
                    peak2.getIndex() - peak1.getIndex() >= 5) {
                // Confirm neckline break
                BigDecimal neckline = findNecklineBetweenPeaks(data, peak1, peak2);
                BigDecimal currentPrice = data.get(data.size() - 1).getPrice();

                if (currentPrice.compareTo(neckline) < 0) {
                    return new ChartPattern("DOUBLE_TOP", neckline, peak1.getPrice());
                }
            }
        }

        // Check for double bottom
        if (troughs.size() >= 2) {
            Trough trough1 = troughs.get(troughs.size() - 2);
            Trough trough2 = troughs.get(troughs.size() - 1);

            BigDecimal priceDiff = trough1.getPrice().subtract(trough2.getPrice()).abs();
            BigDecimal threshold = trough1.getPrice().multiply(BigDecimal.valueOf(0.002)); // 0.2%

            if (priceDiff.compareTo(threshold) <= 0 &&
                    trough2.getIndex() - trough1.getIndex() >= 5) {
                // Confirm neckline break
                BigDecimal neckline = findNecklineBetweenTroughs(data, trough1, trough2);
                BigDecimal currentPrice = data.get(data.size() - 1).getPrice();

                if (currentPrice.compareTo(neckline) > 0) {
                    return new ChartPattern("DOUBLE_BOTTOM", neckline, trough1.getPrice());
                }
            }
        }

        return null;
    }

    // Helper methods
    private BigDecimal calculateTrendlineSlope(List<MarketData> data, List<Integer> indices) {
        if (indices.size() < 2) return BigDecimal.ZERO;

        int idx1 = indices.get(indices.size() - 2);
        int idx2 = indices.get(indices.size() - 1);

        BigDecimal price1 = data.get(idx1).getHigh();
        BigDecimal price2 = data.get(idx2).getHigh();

        return price2.subtract(price1).divide(BigDecimal.valueOf(idx2 - idx1), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal extrapolateTrendline(List<MarketData> data, List<Integer> indices, BigDecimal slope) {
        int lastIdx = indices.get(indices.size() - 1);
        BigDecimal lastPrice = data.get(lastIdx).getHigh();
        int currentIdx = data.size() - 1;

        return lastPrice.add(slope.multiply(BigDecimal.valueOf(currentIdx - lastIdx)));
    }

    private List<Peak> findPeaks(List<MarketData> data) {
        List<Peak> peaks = new ArrayList<>();

        for (int i = 2; i < data.size() - 2; i++) {
            BigDecimal price = data.get(i).getHigh();
            if (price.compareTo(data.get(i-1).getHigh()) > 0 &&
                    price.compareTo(data.get(i-2).getHigh()) > 0 &&
                    price.compareTo(data.get(i+1).getHigh()) > 0 &&
                    price.compareTo(data.get(i+2).getHigh()) > 0) {
                peaks.add(new Peak(i, price));
            }
        }

        return peaks;
    }

    private List<Trough> findTroughs(List<MarketData> data) {
        List<Trough> troughs = new ArrayList<>();

        for (int i = 2; i < data.size() - 2; i++) {
            BigDecimal price = data.get(i).getLow();
            if (price.compareTo(data.get(i-1).getLow()) < 0 &&
                    price.compareTo(data.get(i-2).getLow()) < 0 &&
                    price.compareTo(data.get(i+1).getLow()) < 0 &&
                    price.compareTo(data.get(i+2).getLow()) < 0) {
                troughs.add(new Trough(i, price));
            }
        }

        return troughs;
    }

    private BigDecimal findNecklineBetweenPeaks(List<MarketData> data, Peak peak1, Peak peak2) {
        BigDecimal lowestPrice = data.get(peak1.getIndex()).getLow();

        for (int i = peak1.getIndex() + 1; i < peak2.getIndex(); i++) {
            if (data.get(i).getLow().compareTo(lowestPrice) < 0) {
                lowestPrice = data.get(i).getLow();
            }
        }

        return lowestPrice;
    }

    private BigDecimal findNecklineBetweenTroughs(List<MarketData> data, Trough trough1, Trough trough2) {
        BigDecimal highestPrice = data.get(trough1.getIndex()).getHigh();

        for (int i = trough1.getIndex() + 1; i < trough2.getIndex(); i++) {
            if (data.get(i).getHigh().compareTo(highestPrice) > 0) {
                highestPrice = data.get(i).getHigh();
            }
        }

        return highestPrice;
    }

    private double calculateMomentum(List<MarketData> data) {
        if (data.size() < 10) return 0.0;

        BigDecimal firstPrice = data.get(0).getPrice();
        BigDecimal lastPrice = data.get(data.size() - 1).getPrice();

        return lastPrice.subtract(firstPrice)
                .divide(firstPrice, 4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}

class TechnicalAnalysis {
    private BigDecimal vwap;
    private BigDecimal currentPrice;
    private boolean nearVWAP;
    private boolean vwapBounce;
    private boolean bullishVWAPBounce;
    private boolean bearishVWAPBounce;
    private String trendlineBreakdown;
    private Map<String, KeyLevel> keyLevels;
    private KeyLevelSignal keyLevelSignal;
    private ChartPattern chartPattern;
    private boolean bullishMomentum;
    private boolean bearishMomentum;

    // Getters and setters
    public BigDecimal getVwap() { return vwap; }
    public void setVwap(BigDecimal vwap) { this.vwap = vwap; }

    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

    public boolean isNearVWAP() { return nearVWAP; }
    public void setNearVWAP(boolean nearVWAP) { this.nearVWAP = nearVWAP; }

    public boolean isVWAPBounce() { return vwapBounce; }
    public void setVWAPBounce(boolean vwapBounce) { this.vwapBounce = vwapBounce; }

    public boolean isBullishVWAPBounce() { return bullishVWAPBounce; }
    public void setBullishVWAPBounce(boolean bullishVWAPBounce) { this.bullishVWAPBounce = bullishVWAPBounce; }

    public boolean isBearishVWAPBounce() { return bearishVWAPBounce; }
    public void setBearishVWAPBounce(boolean bearishVWAPBounce) { this.bearishVWAPBounce = bearishVWAPBounce; }

    public String getTrendlineBreakdown() { return trendlineBreakdown; }
    public void setTrendlineBreakdown(String trendlineBreakdown) { this.trendlineBreakdown = trendlineBreakdown; }

    public Map<String, KeyLevel> getKeyLevels() { return keyLevels; }
    public void setKeyLevels(Map<String, KeyLevel> keyLevels) { this.keyLevels = keyLevels; }

    public KeyLevelSignal getKeyLevelSignal() { return keyLevelSignal; }
    public void setKeyLevelSignal(KeyLevelSignal keyLevelSignal) { this.keyLevelSignal = keyLevelSignal; }

    public ChartPattern getChartPattern() { return chartPattern; }
    public void setChartPattern(ChartPattern chartPattern) { this.chartPattern = chartPattern; }

    public boolean isBullishMomentum() { return bullishMomentum; }
    public void setBullishMomentum(boolean bullishMomentum) { this.bullishMomentum = bullishMomentum; }

    public boolean isBearishMomentum() { return bearishMomentum; }
    public void setBearishMomentum(boolean bearishMomentum) { this.bearishMomentum = bearishMomentum; }
}

class KeyLevel {
    private String name;
    private BigDecimal price;
    private String type; // support or resistance

    public KeyLevel(String name, BigDecimal price, String type) {
        this.name = name;
        this.price = price;
        this.type = type;
    }

    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public String getType() { return type; }
}

class KeyLevelSignal {
    private String type;
    private String level;
    private BigDecimal price;

    public KeyLevelSignal(String type, String level, BigDecimal price) {
        this.type = type;
        this.level = level;
        this.price = price;
    }

    public String getType() { return type; }
    public String getLevel() { return level; }
    public BigDecimal getPrice() { return price; }
}

class ChartPattern {
    private String type;
    private BigDecimal neckline;
    private BigDecimal patternHeight;

    public ChartPattern(String type, BigDecimal neckline, BigDecimal patternHeight) {
        this.type = type;
        this.neckline = neckline;
        this.patternHeight = patternHeight;
    }

    public String getType() { return type; }
    public BigDecimal getNeckline() { return neckline; }
    public BigDecimal getPatternHeight() { return patternHeight; }
}

class Peak {
    private int index;
    private BigDecimal price;

    public Peak(int index, BigDecimal price) {
        this.index = index;
        this.price = price;
    }

    public int getIndex() { return index; }
    public BigDecimal getPrice() { return price; }
}

class Trough {
    private int index;
    private BigDecimal price;

    public Trough(int index, BigDecimal price) {
        this.index = index;
        this.price = price;
    }

    public int getIndex() { return index; }
    public BigDecimal getPrice() { return price; }
}

