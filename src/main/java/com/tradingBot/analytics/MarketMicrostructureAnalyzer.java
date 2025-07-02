package com.tradingBot.analytics;

import com.tradingBot.service.TradierService;
import com.tradingBot.entity.MarketMicrostructureData;
import com.tradingBot.repository.MarketMicrostructureDataRepository;
import com.tradingBot.model.QuoteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class MarketMicrostructureAnalyzer {

    private final TradierService tradierService;
    private final MarketMicrostructureDataRepository microstructureRepo;

    private static final BigDecimal TOXIC_SPREAD_THRESHOLD = new BigDecimal("0.002"); // 20 bps
    private static final double TOXIC_IMBALANCE_THRESHOLD = 0.7;

    public MarketMicrostructureData analyzeMicrostructure(String symbol) {
        MarketMicrostructureData data = new MarketMicrostructureData();
        data.setSymbol(symbol);
        data.setTimestamp(LocalDateTime.now());

        QuoteResponse quote = tradierService.getQuote(symbol);
        if (quote != null && quote.getQuote() != null) {
            BigDecimal bid = quote.getQuote().getBid();
            BigDecimal ask = quote.getQuote().getAsk();

            // Calculate spread
            BigDecimal spread = ask.subtract(bid);
            data.setBidAskSpread(spread);

            // Calculate spread percentage
            BigDecimal midPrice = bid.add(ask).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            BigDecimal spreadPct = spread.divide(midPrice, 6, RoundingMode.HALF_UP);

            // Calculate order imbalance
            Integer bidSize = quote.getQuote().getBidSize();
            Integer askSize = quote.getQuote().getAskSize();
            if (bidSize != null && askSize != null && (bidSize + askSize) > 0) {
                double imbalance = (double)(bidSize - askSize) / (bidSize + askSize);
                data.setOrderImbalance(BigDecimal.valueOf(imbalance));
            }

            // Calculate toxicity score
            double toxicity = calculateToxicityScore(spreadPct, data.getOrderImbalance());
            data.setToxicityScore(toxicity);

            // Calculate liquidity score
            double liquidity = calculateLiquidityScore(bidSize, askSize, spreadPct);
            data.setLiquidityScore(liquidity);
        }

        return microstructureRepo.save(data);
    }

    public double calculateToxicityScore(String symbol) {
        // Get recent microstructure data
        List<MarketMicrostructureData> recentData = microstructureRepo
                .findBySymbolAndTimestampAfterOrderByTimestampDesc(
                        symbol, LocalDateTime.now().minusMinutes(5)
                );

        if (recentData.isEmpty()) {
            return analyzeMicrostructure(symbol).getToxicityScore();
        }

        // Average toxicity over recent samples
        double avgToxicity = recentData.stream()
                .mapToDouble(d -> d.getToxicityScore() != null ? d.getToxicityScore() : 0.5)
                .average()
                .orElse(0.5);

        return avgToxicity;
    }

    private double calculateToxicityScore(BigDecimal spreadPct, BigDecimal orderImbalance) {
        double toxicity = 0.0;

        // Wide spread indicates toxicity
        if (spreadPct.compareTo(TOXIC_SPREAD_THRESHOLD) > 0) {
            toxicity += 0.3 * spreadPct.divide(TOXIC_SPREAD_THRESHOLD, 2, RoundingMode.HALF_UP).doubleValue();
        }

        // Order imbalance indicates informed trading
        if (orderImbalance != null) {
            double imbalanceAbs = Math.abs(orderImbalance.doubleValue());
            if (imbalanceAbs > TOXIC_IMBALANCE_THRESHOLD) {
                toxicity += 0.4 * (imbalanceAbs / TOXIC_IMBALANCE_THRESHOLD);
            }
        }

        // Add temporal component - rapid changes indicate toxicity
        // This would need historical data to implement properly
        toxicity += 0.3 * estimateTemporalToxicity();

        return Math.min(1.0, toxicity);
    }

    private double calculateLiquidityScore(Integer bidSize, Integer askSize, BigDecimal spreadPct) {
        double score = 1.0;

        // Penalize wide spreads
        if (spreadPct != null) {
            score *= Math.max(0, 1 - spreadPct.doubleValue() * 100); // Convert to basis points
        }

        // Reward depth
        if (bidSize != null && askSize != null) {
            int totalSize = bidSize + askSize;
            double depthScore = Math.min(1.0, totalSize / 1000.0); // Normalize to 1000 shares
            score *= (0.5 + 0.5 * depthScore);
        }

        return Math.max(0, Math.min(1.0, score));
    }

    private double estimateTemporalToxicity() {
        // Placeholder - in production, analyze rapid price/quote changes
        return 0.1;
    }

    public boolean isMarketToxic(String symbol) {
        double toxicity = calculateToxicityScore(symbol);
        return toxicity > 0.7;
    }

    public boolean hasSufficientLiquidity(String symbol, int orderSize) {
        MarketMicrostructureData data = analyzeMicrostructure(symbol);

        if (data.getLiquidityScore() == null || data.getLiquidityScore() < 0.3) {
            return false;
        }

        // Check if order size is too large relative to quote sizes
        QuoteResponse quote = tradierService.getQuote(symbol);
        if (quote != null && quote.getQuote() != null) {
            Integer bidSize = quote.getQuote().getBidSize();
            Integer askSize = quote.getQuote().getAskSize();

            if (bidSize != null && askSize != null) {
                int avgQuoteSize = (bidSize + askSize) / 2;
                return orderSize <= avgQuoteSize * 0.2; // Don't take more than 20% of displayed liquidity
            }
        }

        return false;
    }
}