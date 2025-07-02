package com.tradingBot.risk;

import com.tradingBot.entity.Trade;
import com.tradingBot.entity.RiskMetricsData;
import com.tradingBot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class PortfolioRiskCalculator {

    private final TradeRepository tradeRepository;
    private static final int VAR_LOOKBACK_DAYS = 30;
    private static final BigDecimal DAILY_RISK_FREE_RATE = new BigDecimal("0.0001"); // ~3.65% annual

    public RiskMetricsData calculatePortfolioRisk(List<Trade> openTrades) {
        RiskMetricsData metrics = new RiskMetricsData();
        metrics.setCalculatedAt(LocalDateTime.now());

        if (openTrades.isEmpty()) {
            metrics.setPortfolioVar(BigDecimal.ZERO);
            metrics.setSharpeRatio(0.0);
            metrics.setCorrelationScore(0.0);
            return metrics;
        }

        // Calculate portfolio value
        BigDecimal portfolioValue = calculatePortfolioValue(openTrades);

        // Calculate VaR
        BigDecimal portfolioVar = calculateVaR(openTrades, 0.95);
        metrics.setPortfolioVar(portfolioVar);

        // Calculate Sharpe ratio
        double sharpe = calculateSharpeRatio(openTrades);
        metrics.setSharpeRatio(sharpe);

        // Calculate correlation risk
        double correlationScore = calculateCorrelationRisk(openTrades);
        metrics.setCorrelationScore(correlationScore);

        // Calculate optimal position size for next trade
        BigDecimal optimalSize = calculateOptimalNextPosition(portfolioValue, portfolioVar);
        metrics.setOptimalPositionSize(optimalSize);

        return metrics;
    }

    private BigDecimal calculatePortfolioValue(List<Trade> trades) {
        return trades.stream()
                .map(t -> t.getCurrentPrice() != null ?
                        t.getCurrentPrice().multiply(BigDecimal.valueOf(t.getQuantity() * 100)) :
                        t.getEntryPrice().multiply(BigDecimal.valueOf(t.getQuantity() * 100)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateVaR(List<Trade> trades, double confidence) {
        // Historical VaR calculation
        List<BigDecimal> historicalReturns = getHistoricalPortfolioReturns(trades);

        if (historicalReturns.size() < 10) {
            // Not enough data, use parametric VaR
            return calculateParametricVaR(trades, confidence);
        }

        // Sort returns
        Collections.sort(historicalReturns);

        // Find percentile
        int index = (int) ((1 - confidence) * historicalReturns.size());
        BigDecimal var = historicalReturns.get(index).abs();

        // Scale to current portfolio
        BigDecimal portfolioValue = calculatePortfolioValue(trades);
        return var.multiply(portfolioValue);
    }

    private BigDecimal calculateParametricVaR(List<Trade> trades, double confidence) {
        // Calculate portfolio volatility
        double portfolioVolatility = calculatePortfolioVolatility(trades);

        // Z-score for confidence level
        double zScore = confidence == 0.95 ? 1.645 : confidence == 0.99 ? 2.326 : 1.96;

        BigDecimal portfolioValue = calculatePortfolioValue(trades);
        BigDecimal var = portfolioValue.multiply(BigDecimal.valueOf(portfolioVolatility * zScore));

        return var;
    }

    private double calculatePortfolioVolatility(List<Trade> trades) {
        // Simplified: assume each position has 30% annualized volatility
        // In production, calculate actual correlations and individual volatilities
        double avgVolatility = 0.30;
        double correlation = 0.5; // Assume moderate correlation

        int n = trades.size();
        if (n == 1) return avgVolatility;

        // Portfolio volatility with correlation
        double variance = avgVolatility * avgVolatility * (1 + (n - 1) * correlation) / n;
        return Math.sqrt(variance) / Math.sqrt(252); // Daily volatility
    }

    private List<BigDecimal> getHistoricalPortfolioReturns(List<Trade> openTrades) {
        LocalDateTime lookbackDate = LocalDateTime.now().minusDays(VAR_LOOKBACK_DAYS);

        // Get historical trades
        List<Trade> historicalTrades = tradeRepository.findByEntryTimeBetween(
                lookbackDate, LocalDateTime.now()
        );

        // Calculate daily returns
        Map<LocalDateTime, BigDecimal> dailyPnL = new TreeMap<>();

        for (Trade trade : historicalTrades) {
            if (trade.getRealizedPnl() != null) {
                LocalDateTime date = trade.getExitTime() != null ?
                        trade.getExitTime().toLocalDate().atStartOfDay() :
                        trade.getEntryTime().toLocalDate().atStartOfDay();

                dailyPnL.merge(date, trade.getRealizedPnl(), BigDecimal::add);
            }
        }

        // Convert to returns
        List<BigDecimal> returns = new ArrayList<>();
        BigDecimal previousValue = calculatePortfolioValue(openTrades);

        for (BigDecimal pnl : dailyPnL.values()) {
            if (previousValue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dailyReturn = pnl.divide(previousValue, 6, RoundingMode.HALF_UP);
                returns.add(dailyReturn);
            }
        }

        return returns;
    }

    private double calculateSharpeRatio(List<Trade> trades) {
        List<BigDecimal> returns = getHistoricalPortfolioReturns(trades);

        if (returns.size() < 5) return 0.0;

        // Calculate average return
        BigDecimal avgReturn = returns.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 6, RoundingMode.HALF_UP);

        // Calculate standard deviation
        BigDecimal variance = BigDecimal.ZERO;
        for (BigDecimal ret : returns) {
            BigDecimal diff = ret.subtract(avgReturn);
            variance = variance.add(diff.multiply(diff));
        }
        variance = variance.divide(BigDecimal.valueOf(returns.size() - 1), 6, RoundingMode.HALF_UP);
        double stdDev = Math.sqrt(variance.doubleValue());

        if (stdDev == 0) return 0.0;

        // Sharpe ratio = (Return - Risk Free Rate) / StdDev
        double excessReturn = avgReturn.subtract(DAILY_RISK_FREE_RATE).doubleValue();
        double sharpe = excessReturn / stdDev;

        // Annualize
        return sharpe * Math.sqrt(252);
    }

    private double calculateCorrelationRisk(List<Trade> trades) {
        // Check if all trades are in same underlying
        Set<String> symbols = trades.stream()
                .map(Trade::getSymbol)
                .collect(Collectors.toSet());

        if (symbols.size() == 1) {
            // All in same symbol - high correlation risk
            return 1.0;
        }

        // Simple correlation estimate based on symbol diversity
        double diversificationRatio = (double) symbols.size() / trades.size();
        return 1.0 - diversificationRatio;
    }

    private BigDecimal calculateOptimalNextPosition(BigDecimal portfolioValue, BigDecimal currentVaR) {
        // Kelly-based position sizing with risk constraint
        BigDecimal maxRisk = portfolioValue.multiply(new BigDecimal("0.02")); // 2% max risk
        BigDecimal remainingRisk = maxRisk.subtract(currentVaR);

        if (remainingRisk.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO; // No room for more risk
        }

        // Assume new position will have 30% volatility
        BigDecimal positionSize = remainingRisk.divide(new BigDecimal("0.30"), 2, RoundingMode.HALF_UP);

        // Cap at 10% of portfolio
        BigDecimal maxPosition = portfolioValue.multiply(new BigDecimal("0.10"));
        return positionSize.min(maxPosition);
    }
}