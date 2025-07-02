package com.tradingBot.engine;

import com.tradingBot.entity.MarketData;
import com.tradingBot.model.MarketInternals;
import com.tradingBot.model.MarketConditions;
import com.tradingBot.repository.MarketDataRepository;
import com.tradingBot.service.TradierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
@RequiredArgsConstructor
public class TrendTrackingEngine {

    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;

    private final AtomicReference<String> currentTrend = new AtomicReference<>("NEUTRAL");
    private final ConcurrentHashMap<String, String> symbolTrends = new ConcurrentHashMap<>();

    public void analyzeTrend(String symbol) {
        try {
            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 30);
            if (recentData.size() < 10) {
                symbolTrends.put(symbol, "NEUTRAL");
                return;
            }

            // Calculate trend using price action
            BigDecimal firstPrice = recentData.get(0).getPrice();
            BigDecimal lastPrice = recentData.get(recentData.size() - 1).getPrice();
            BigDecimal priceChange = lastPrice.subtract(firstPrice)
                    .divide(firstPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            // Moving average trend
            BigDecimal sum = BigDecimal.ZERO;
            for (MarketData data : recentData) {
                sum = sum.add(data.getPrice());
            }
            BigDecimal avgPrice = sum.divide(BigDecimal.valueOf(recentData.size()), 4, RoundingMode.HALF_UP);

            String trend;
            if (lastPrice.compareTo(avgPrice) > 0 && priceChange.compareTo(BigDecimal.valueOf(0.2)) > 0) {
                trend = "BULLISH";
            } else if (lastPrice.compareTo(avgPrice) < 0 && priceChange.compareTo(BigDecimal.valueOf(-0.2)) < 0) {
                trend = "BEARISH";
            } else {
                trend = "NEUTRAL";
            }

            symbolTrends.put(symbol, trend);
            log.debug("Trend for {}: {} (change: {}%)", symbol, trend, priceChange);

        } catch (Exception e) {
            log.error("Error analyzing trend for {}: {}", symbol, e.getMessage());
            symbolTrends.put(symbol, "NEUTRAL");
        }
    }

    public void analyzeMarketTrend() {
        try {
            // Analyze SPY as market proxy
            analyzeTrend("SPY");
            String spyTrend = symbolTrends.getOrDefault("SPY", "NEUTRAL");
            currentTrend.set(spyTrend);

            log.info("Market trend updated: {}", spyTrend);
        } catch (Exception e) {
            log.error("Error analyzing market trend: {}", e.getMessage());
            currentTrend.set("NEUTRAL");
        }
    }

    public String getMarketTrend() {
        return currentTrend.get();
    }

    public String getSymbolTrend(String symbol) {
        return symbolTrends.getOrDefault(symbol, "NEUTRAL");
    }

    public boolean isTrendFavorable(String symbol, String signalType) {
        String trend = getSymbolTrend(symbol);
        String marketTrend = getMarketTrend();

        // Check if individual symbol trend favors signal
        boolean symbolFavorable = ("BUY".equals(signalType) && "BULLISH".equals(trend)) ||
                ("SELL".equals(signalType) && "BEARISH".equals(trend));

        // Check if market trend doesn't oppose
        boolean marketNotOpposing = !"NEUTRAL".equals(marketTrend) &&
                (("BUY".equals(signalType) && !"BEARISH".equals(marketTrend)) ||
                        ("SELL".equals(signalType) && !"BULLISH".equals(marketTrend)));

        return symbolFavorable || (!"NEUTRAL".equals(trend) && marketNotOpposing);
    }
}