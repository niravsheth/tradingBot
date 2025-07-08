package com.tradingBot.ml;

import com.tradingBot.analytics.MarketMicrostructureAnalyzer;
import com.tradingBot.entity.Signal;
import com.tradingBot.entity.MarketData;
import com.tradingBot.analytics.MarketRegimeDetector;
import com.tradingBot.repository.MarketDataRepository;
import com.tradingBot.service.TradierService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FeatureExtractor {

    private final MarketDataRepository marketDataRepository;
    private final MarketMicrostructureAnalyzer microAnalyzer;
    private final MarketRegimeDetector regimeDetector;
    private final TradierService tradierService;

    @Data
    public static class MLFeatures {
        private double spreadBps;
        private double orderImbalance;
        private String marketRegime;
        private double ivRank;
        private int minutesIntoSession;
        private double volumeRatio;
        private double pricePosition;
        private double momentum5min;
        private double momentum30min;
        private double volatility;
        private double microstructureToxicity;
        private double liquidityScore;
        private double timeDecayFactor;
        private double moneyness;
        private double volumeProfile;
    }

    public MLFeatures extractFeatures(Signal signal) {
        MLFeatures features = new MLFeatures();

        // Time features
        LocalDateTime now = LocalDateTime.now();
        LocalTime marketOpen = LocalTime.of(9, 30);
        features.minutesIntoSession = (int) java.time.Duration.between(
                marketOpen, now.toLocalTime()
        ).toMinutes();

        // Market microstructure features
        var microData = microAnalyzer.analyzeMicrostructure(signal.getSymbol());
        features.spreadBps = microData.getBidAskSpread() != null ?
                microData.getBidAskSpread().multiply(BigDecimal.valueOf(10000)).doubleValue() : 50;
        features.orderImbalance = microData.getOrderImbalance() != null ?
                microData.getOrderImbalance().doubleValue() : 0;
        features.microstructureToxicity = microData.getToxicityScore() != null ?
                microData.getToxicityScore() : 0.5;
        features.liquidityScore = microData.getLiquidityScore() != null ?
                microData.getLiquidityScore() : 0.5;

        // Market regime
        features.marketRegime = regimeDetector.detectCurrentRegime(signal.getSymbol()).toString();

        // Price momentum features
        calculateMomentumFeatures(signal.getSymbol(), features);

        // Volatility features
        features.volatility = calculateRealizedVolatility(signal.getSymbol());

        // Options-specific features
        extractOptionsFeatures(signal, features);

        // Volume analysis
        calculateVolumeFeatures(signal.getSymbol(), features);

        return features;
    }

    private void calculateMomentumFeatures(String symbol, MLFeatures features) {
        List<MarketData> data5min = marketDataRepository.findRecentData(symbol, 5);
        List<MarketData> data30min = marketDataRepository.findRecentData(symbol, 30);

        if (!data5min.isEmpty() && data5min.size() > 1) {
            BigDecimal firstPrice = data5min.get(0).getPrice();
            BigDecimal lastPrice = data5min.get(data5min.size() - 1).getPrice();
            features.momentum5min = lastPrice.subtract(firstPrice)
                    .divide(firstPrice, 4, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        if (!data30min.isEmpty() && data30min.size() > 1) {
            BigDecimal firstPrice = data30min.get(0).getPrice();
            BigDecimal lastPrice = data30min.get(data30min.size() - 1).getPrice();
            features.momentum30min = lastPrice.subtract(firstPrice)
                    .divide(firstPrice, 4, RoundingMode.HALF_UP)
                    .doubleValue();

            // Price position in range
            BigDecimal high = data30min.stream()
                    .map(MarketData::getHigh)
                    .filter(h -> h != null)
                    .max(BigDecimal::compareTo)
                    .orElse(lastPrice);
            BigDecimal low = data30min.stream()
                    .map(MarketData::getLow)
                    .filter(l -> l != null)
                    .min(BigDecimal::compareTo)
                    .orElse(lastPrice);

            if (high.compareTo(low) > 0) {
                features.pricePosition = lastPrice.subtract(low)
                        .divide(high.subtract(low), 4, RoundingMode.HALF_UP)
                        .doubleValue();
            } else {
                features.pricePosition = 0.5;
            }
        }
    }

    private double calculateRealizedVolatility(String symbol) {
        List<MarketData> data = marketDataRepository.findRecentData(symbol, 60);

        if (data.size() < 10) return 0.25; // Default 25% annualized

        double sumSquaredReturns = 0;
        int count = 0;

        for (int i = 1; i < data.size(); i++) {
            BigDecimal prevPrice = data.get(i - 1).getPrice();
            BigDecimal currPrice = data.get(i).getPrice();

            if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                double logReturn = Math.log(currPrice.doubleValue() / prevPrice.doubleValue());
                sumSquaredReturns += logReturn * logReturn;
                count++;
            }
        }

        if (count == 0) return 0.25;

        double variance = sumSquaredReturns / count;
        return Math.sqrt(variance * 252 * 390); // Annualized (390 minutes per day)
    }

    private void extractOptionsFeatures(Signal signal, MLFeatures features) {
        // Time decay factor
        if (signal.getExpirationTime() != null) {
            long minutesToExpiry = java.time.Duration.between(
                    LocalDateTime.now(), signal.getExpirationTime()
            ).toMinutes();
            features.timeDecayFactor = Math.exp(-minutesToExpiry / 390.0); // Exponential decay
        }

        // Moneyness (if we have strike price info)
        // This would need option chain data to implement properly
        features.moneyness = 1.0; // ATM placeholder

        // IV rank - simplified
        features.ivRank = 0.5; // Placeholder - need historical IV data
    }

    private void calculateVolumeFeatures(String symbol, MLFeatures features) {
        List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 30);

        if (recentData.isEmpty()) {
            features.volumeRatio = 1.0;
            features.volumeProfile = 0.5;
            return;
        }

        // Current volume vs average
        MarketData latest = recentData.get(recentData.size() - 1);
        if (latest.getVolume() != null) {
            double avgVolume = recentData.stream()
                    .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                    .average()
                    .orElse(1.0);

            features.volumeRatio = latest.getVolume() / Math.max(1, avgVolume);
        } else {
            features.volumeRatio = 1.0;
        }

        // Volume profile - where is volume concentrated
        long totalVolume = recentData.stream()
                .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                .sum();

        long firstHalfVolume = recentData.stream()
                .limit(recentData.size() / 2)
                .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                .sum();

        features.volumeProfile = totalVolume > 0 ?
                (double) firstHalfVolume / totalVolume : 0.5;
    }
}