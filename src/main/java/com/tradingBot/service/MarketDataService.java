package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.MarketDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Market Data Service for real-time market data retrieval
 * In production: Connect to Bloomberg, Polygon, Alpha Vantage, etc.
 */
@Service
@Slf4j
public class MarketDataService {
    private final TradierService tradierService;
    private final MarketDataRepository marketDataRepository;
    private final Map<String, Double> momentumCache = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> atrCache = new ConcurrentHashMap<>();
    private final Random random = new Random(); // For demo simulation

    public MarketDataService(TradierService tradierService, MarketDataRepository marketDataRepository) {
        this.tradierService = tradierService;
        this.marketDataRepository = marketDataRepository;
    }

    /**
     * Get real-time momentum indicator for a symbol
     * In production: Calculate RSI, MACD, or custom momentum from live price data
     */
    public double getRealTimeMomentum(String symbol) {
        return momentumCache.computeIfAbsent(symbol, k -> {
            // Simulate realistic momentum values between -1.0 and 1.0
            double momentum = random.nextGaussian() * 0.4; // Normal distribution
            log.debug("Generated momentum for {}: {}", symbol, String.format("%.3f",momentum));
            return momentum;
        });
    }

    /**
     * Get historical Average True Range for volatility analysis
     * In production: Calculate from historical OHLC data
     */
    public BigDecimal getHistoricalATR(String symbol, int periods) {
        try {
            List<MarketData> data = marketDataRepository.findRecentData(symbol, periods);

            if (data.size() < 2) {
                QuoteResponse quote = tradierService.getQuote(symbol);
                if (quote != null && quote.getQuote() != null) {
                    return quote.getQuote().getLast().multiply(BigDecimal.valueOf(0.01));
                }
                return BigDecimal.ONE;
            }

            BigDecimal totalRange = BigDecimal.ZERO;
            int count = 0;

            for (int i = 1; i < data.size(); i++) {
                MarketData current = data.get(i);
                MarketData previous = data.get(i-1);

                if (current.getHigh() != null && current.getLow() != null) {
                    BigDecimal range = current.getHigh().subtract(current.getLow());
                    totalRange = totalRange.add(range);
                    count++;
                }
            }

            return count > 0 ? totalRange.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP) : BigDecimal.ONE;

        } catch (Exception e) {
            log.error("Error calculating historical ATR: {}", e.getMessage());
            return BigDecimal.ONE;
        }
    }

    /**
     * Get ETF constituent weight
     * In production: Connect to ETF provider APIs for real-time weights
     */
    public double getConstituentWeight(String symbol) {
        // QQQ top holdings with approximate weights (as of typical composition)
        Map<String, Double> qqqWeights = Map.of(
                "AAPL", 0.081,  // ~8.1%
                "MSFT", 0.074,  // ~7.4%
                "NVDA", 0.064,  // ~6.4%
                "AMZN", 0.051,  // ~5.1%
                "GOOGL", 0.042, // ~4.2%
                "META", 0.041,  // ~4.1%
                "TSLA", 0.032,  // ~3.2%
                "GOOG", 0.031   // ~3.1%
        );

        double weight = qqqWeights.getOrDefault(symbol, 0.01); // Default 1% for others
        log.debug("Constituent weight for {}: {}%", symbol, String.format("%.3f",weight * 100));
        return weight;
    }

    /**
     * Get current implied volatility rank
     * In production: Calculate from options chain data
     */
    public double getImpliedVolatilityRank(String symbol) {
        // Simulate IV rank between 0-100
        double ivRank = 20 + random.nextDouble() * 60; // 20-80 range is typical
        log.debug("IV Rank for {}: {}", symbol, String.format("%.1f",ivRank));
        return ivRank;
    }

    /**
     * Clear caches for fresh data
     */
    public void clearCaches() {
        momentumCache.clear();
        atrCache.clear();
        log.info("Market data caches cleared");
    }
}