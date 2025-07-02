package com.tradingBot.analytics;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.MarketRegime;
import com.tradingBot.repository.MarketDataRepository;
import com.tradingBot.service.TradierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class MarketRegimeDetector {

    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;

    public MarketRegime detectCurrentRegime(String symbol) {
        LocalTime now = LocalTime.now();

        // Time-based regimes
        if (now.isBefore(LocalTime.of(10, 0))) {
            return MarketRegime.OPENING_RANGE;
        }
        if (now.isAfter(LocalTime.of(15, 30))) {
            return MarketRegime.CLOSING_RANGE;
        }

        // Get market data
        List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 60);

        if (recentData.size() < 20) {
            return MarketRegime.LOW_VOLATILITY; // Default for insufficient data
        }

        // Calculate volatility
        double realizedVol = calculateRealizedVolatility(recentData);

        // Calculate trend strength
        double trendStrength = calculateTrendStrength(recentData);

        // Determine regime
        if (realizedVol > 0.02) { // 2% hourly vol = high volatility
            return MarketRegime.HIGH_VOLATILITY;
        } else if (realizedVol < 0.005) { // 0.5% hourly vol = low volatility
            return MarketRegime.LOW_VOLATILITY;
        } else if (trendStrength > 0.7) {
            return recentData.get(recentData.size() - 1).getPrice()
                    .compareTo(recentData.get(0).getPrice()) > 0 ?
                    MarketRegime.TRENDING_UP : MarketRegime.TRENDING_DOWN;
        } else {
            return MarketRegime.CHOPPY;
        }
    }

    private double calculateRealizedVolatility(List<MarketData> data) {
        if (data.size() < 2) return 0.0;

        BigDecimal sumSquaredReturns = BigDecimal.ZERO;

        for (int i = 1; i < data.size(); i++) {
            BigDecimal prevPrice = data.get(i - 1).getPrice();
            BigDecimal currPrice = data.get(i).getPrice();

            if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal logReturn = BigDecimal.valueOf(
                        Math.log(currPrice.doubleValue() / prevPrice.doubleValue())
                );
                sumSquaredReturns = sumSquaredReturns.add(logReturn.multiply(logReturn));
            }
        }

        BigDecimal variance = sumSquaredReturns.divide(
                BigDecimal.valueOf(data.size() - 1), 8, RoundingMode.HALF_UP
        );

        return Math.sqrt(variance.doubleValue()) * Math.sqrt(252 * 6.5); // Annualized
    }

    private double calculateTrendStrength(List<MarketData> data) {
        if (data.size() < 3) return 0.0;

        // Linear regression R-squared
        double n = data.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;

        for (int i = 0; i < data.size(); i++) {
            double x = i;
            double y = data.get(i).getPrice().doubleValue();

            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }

        double correlation = (n * sumXY - sumX * sumY) /
                Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

        return Math.abs(correlation); // R-squared approximation
    }
}