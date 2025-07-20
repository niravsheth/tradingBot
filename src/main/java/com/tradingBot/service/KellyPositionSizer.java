package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import com.tradingBot.entity.Trade;
import com.tradingBot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class KellyPositionSizer {

    private final TradeRepository tradeRepository;
    @Value("${trading.kelly.fraction:0.25}")
    private double kellyFraction; // Use 1/4 Kelly for safety

    @Value("${trading.kelly.min-trades:20}")
    private int minTradesForKelly;

    @Value("${trading.kelly.max-position-percentage:5}")
    private double maxPositionPercentage;

    @Value("${trading.kelly.min-contracts:1}")
    private int minContracts;

    @Value("${trading.kelly.max-contracts:20}")
    private int maxContracts;

    @Value("${trading.total-capital}")
    private BigDecimal totalCapital;

    public BigDecimal getAvailableCapital() {
        log.debug("[v62] Calculating available capital");

        // Start with total capital
        BigDecimal available = totalCapital;

        // Subtract today's losses (or add gains)
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0);
        BigDecimal todayPnL = tradeRepository.calculateProfitSince(startOfDay);
        if (todayPnL != null) {
            available = available.add(todayPnL);
            log.debug("[v62] Adjusted for today's P&L: ${} (available: ${})", todayPnL, available);
        }

        // Subtract capital in open positions
        List<Trade> openTrades = tradeRepository.findByStatus("OPEN");
        BigDecimal capitalInUse = BigDecimal.ZERO;

        for (Trade trade : openTrades) {
            BigDecimal positionValue = trade.getEntryPrice()
                    .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));
            capitalInUse = capitalInUse.add(positionValue);
        }

        available = available.subtract(capitalInUse);
        log.info("[v62] Available capital: ${} (Total: ${}, In use: ${}, Today P&L: ${})",
                available, totalCapital, capitalInUse, todayPnL != null ? todayPnL : 0);

        return available.max(BigDecimal.ZERO);
    }
    public int calculateOptimalSize(Signal signal, BigDecimal currentOptionPrice) {
        String sizeId = "SIZE-" + signal.getId();

        // Get available capital
        BigDecimal availableCapital = getAvailableCapital();
        if (availableCapital.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[{}] No available capital", sizeId);
            return 0;
        }

        // Calculate Kelly-based position size
        double kellyPercentage = calculateKellyPercentage(signal.getStrategy());

        // Apply confidence adjustment
        double confidenceMultiplier = signal.getConfidence();
        double adjustedPercentage = kellyPercentage * confidenceMultiplier;

        // Apply maximum position constraint
        adjustedPercentage = Math.min(adjustedPercentage, maxPositionPercentage / 100.0);

        // Calculate dollar allocation
        BigDecimal allocation = availableCapital.multiply(BigDecimal.valueOf(adjustedPercentage));

        // Calculate contracts
        BigDecimal contractValue = currentOptionPrice.multiply(BigDecimal.valueOf(100));
        int contracts = allocation.divide(contractValue, 0, RoundingMode.DOWN).intValue();

        // Apply min/max constraints
        contracts = Math.max(contracts, minContracts);
        contracts = Math.min(contracts, maxContracts);

        // Final capital check
        BigDecimal requiredCapital = contractValue.multiply(BigDecimal.valueOf(contracts));
        if (requiredCapital.compareTo(availableCapital) > 0) {
            contracts = availableCapital.divide(contractValue, 0, RoundingMode.DOWN).intValue();
        }

        log.info("[{}] Kelly sizing - Available: ${}, Kelly%: {:.2f}%, Adjusted%: {:.2f}%, Contracts: {}",
                sizeId, availableCapital, kellyPercentage * 100, adjustedPercentage * 100, contracts);

        return contracts;
    }

    private double calculateKellyPercentage(String strategy) {
        // Get historical performance for this strategy
        LocalDateTime lookback = LocalDateTime.now().minusDays(30);
        List<Trade> historicalTrades = tradeRepository
                .findByStrategyAndCreatedAtAfter(strategy, lookback);

        if (historicalTrades.size() < minTradesForKelly) {
            // Not enough history - use conservative default
            log.info("Insufficient history for Kelly ({} trades) - using default allocation",
                    historicalTrades.size());
            return 0.02; // 2% default
        }

        // Calculate win rate and average win/loss
        List<Trade> winners = historicalTrades.stream()
                .filter(t -> t.getRealizedPnl() != null &&
                        t.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        List<Trade> losers = historicalTrades.stream()
                .filter(t -> t.getRealizedPnl() != null &&
                        t.getRealizedPnl().compareTo(BigDecimal.ZERO) <= 0)
                .collect(Collectors.toList());

        if (winners.isEmpty() || losers.isEmpty()) {
            return 0.02; // Default if no wins or losses
        }

        double winRate = (double) winners.size() / historicalTrades.size();
        double lossRate = 1.0 - winRate;

        // Calculate average win and loss percentages
        double avgWinPercent = winners.stream()
                .mapToDouble(t -> calculateReturnPercentage(t))
                .average()
                .orElse(0.5);

        double avgLossPercent = Math.abs(losers.stream()
                .mapToDouble(t -> calculateReturnPercentage(t))
                .average()
                .orElse(-0.3));

        // Kelly formula: f = (p*b - q) / b
        // where p = win rate, q = loss rate, b = win/loss ratio
        double b = avgWinPercent / avgLossPercent;
        double kellyFull = (winRate * b - lossRate) / b;

        // Apply Kelly fraction (1/4 Kelly for safety)
        double kellyAdjusted = kellyFull * kellyFraction;

        // Ensure positive and reasonable
        kellyAdjusted = Math.max(0.01, Math.min(kellyAdjusted, 0.10)); // 1% to 10%

        log.info("Kelly calculation - Strategy: {}, WinRate: {:.1f}%, AvgWin: {:.1f}%, AvgLoss: {:.1f}%, Kelly: {:.2f}%",
                strategy, winRate * 100, avgWinPercent * 100, avgLossPercent * 100, kellyAdjusted * 100);

        return kellyAdjusted;
    }

    private double calculateReturnPercentage(Trade trade) {
        if (trade.getEntryPrice() == null || trade.getExitPrice() == null) {
            return 0.0;
        }

        return trade.getExitPrice()
                .subtract(trade.getEntryPrice())
                .divide(trade.getEntryPrice(), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // Strategy-specific overrides for known good strategies
    public double getStrategyMultiplier(String strategy) {
        Map<String, Double> strategyMultipliers = Map.of(
                "0DTE_UNUSUAL_FLOW_CALL", 1.5,
                "0DTE_UNUSUAL_FLOW_PUT", 1.5,
                "0DTE_OPENING_DRIVE_CALL", 1.2,
                "0DTE_OPENING_DRIVE_PUT", 1.2,
                "0DTE_VWAP_BREAKOUT_CALL", 1.0,
                "0DTE_VWAP_BREAKOUT_PUT", 1.0,
                "0DTE_VOLUME_SPIKE_CALL", 1.1,
                "0DTE_VOLUME_SPIKE_PUT", 1.1
        );

        return strategyMultipliers.getOrDefault(strategy, 1.0);
    }
}