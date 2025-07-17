// New file: src/main/java/com/tradingBot/service/TradingAnalysisService.java
package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.entity.Trade;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.MarketDataRepository;
import com.tradingBot.repository.SignalRepository;
import com.tradingBot.repository.TradeRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradingAnalysisService {

    private final TradeRepository tradeRepository;
    private final SignalRepository signalRepository;
    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;

    // Track blocked signals in memory (or create a simple table)
    private final List<BlockedSignalInfo> todaysBlockedSignals = new CopyOnWriteArrayList<>();

    @Data
    @AllArgsConstructor
    public static class BlockedSignalInfo {
        private String optionSymbol;
        private String strategy;
        private double confidence;
        private String blockReason;
        private BigDecimal entryPrice;
        private LocalDateTime blockedAt;
        private double marketBreadth;
        private String marketTrend;
    }

    // Call this when a signal is blocked
    public void recordBlockedSignal(String optionSymbol, String strategy,
                                    double confidence, String blockReason,
                                    BigDecimal entryPrice, double marketBreadth,
                                    String marketTrend) {
        todaysBlockedSignals.add(new BlockedSignalInfo(
                optionSymbol, strategy, confidence, blockReason,
                entryPrice, LocalDateTime.now(), marketBreadth, marketTrend
        ));
    }

    // Analyze what would have happened to blocked signals
    public String analyzeBlockedSignals() {
        StringBuilder analysis = new StringBuilder();

        Map<String, List<BlockedSignalInfo>> byReason = todaysBlockedSignals.stream()
                .collect(Collectors.groupingBy(BlockedSignalInfo::getBlockReason));

        BigDecimal totalMissedProfit = BigDecimal.ZERO;
        int profitableBlocked = 0;

        for (Map.Entry<String, List<BlockedSignalInfo>> entry : byReason.entrySet()) {
            String reason = entry.getKey();
            List<BlockedSignalInfo> signals = entry.getValue();

            analysis.append(String.format("\n📊 %s: %d signals blocked\n", reason, signals.size()));

            for (BlockedSignalInfo blocked : signals) {
                // Get current price to see what would have happened
                try {
                    QuoteResponse quote = tradierService.getQuote(blocked.getOptionSymbol());
                    if (quote != null && quote.getQuote() != null) {
                        BigDecimal currentPrice = quote.getQuote().getLast();
                        BigDecimal potentialPnL = currentPrice.subtract(blocked.getEntryPrice())
                                .multiply(BigDecimal.valueOf(100)); // 1 contract

                        if (potentialPnL.compareTo(BigDecimal.ZERO) > 0) {
                            profitableBlocked++;
                            totalMissedProfit = totalMissedProfit.add(potentialPnL);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error analyzing blocked signal: {}", e.getMessage());
                }
            }
        }

        analysis.append(String.format("\n💸 Total missed profit: $%.2f from %d profitable signals\n",
                totalMissedProfit, profitableBlocked));

        // Add recommendations based on blocked signals
        if (byReason.containsKey("MARKET_BREADTH_LOW")) {
            int breadthBlocked = byReason.get("MARKET_BREADTH_LOW").size();
            if (breadthBlocked > 5) {
                analysis.append("\n🔧 Consider lowering market breadth threshold - blocked ")
                        .append(breadthBlocked).append(" signals today\n");
            }
        }

        return analysis.toString();
    }

    // Analyze failed trades
    public String analyzeFailedTrades(List<Trade> trades) {
        StringBuilder analysis = new StringBuilder();

        List<Trade> losers = trades.stream()
                .filter(t -> t.getProfit() != null && t.getProfit().compareTo(BigDecimal.ZERO) < 0)
                .collect(Collectors.toList());

        if (losers.isEmpty()) {
            return "";
        }

        analysis.append("\n❌ FAILED TRADES ANALYSIS:\n");

        // Group by exit reason
        Map<String, List<Trade>> byExitReason = losers.stream()
                .filter(t -> t.getExitReason() != null)
                .collect(Collectors.groupingBy(Trade::getExitReason));

        for (Map.Entry<String, List<Trade>> entry : byExitReason.entrySet()) {
            String reason = entry.getKey();
            List<Trade> reasonTrades = entry.getValue();
            BigDecimal totalLoss = reasonTrades.stream()
                    .map(Trade::getProfit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            analysis.append(String.format("  • %s: %d trades ($%.2f loss)\n",
                    reason, reasonTrades.size(), totalLoss));

            // Check for premature stops
            if (reason.equals("STOP_LOSS")) {
                int prematureStops = 0;
                for (Trade trade : reasonTrades) {
                    // Simple check: did price go back up after stop?
                    if (checkIfStopWasPremature(trade)) {
                        prematureStops++;
                    }
                }
                if (prematureStops > 0) {
                    analysis.append(String.format("    ⚠️ %d stops were premature\n", prematureStops));
                }
            }
        }

        return analysis.toString();
    }

    private boolean checkIfStopWasPremature(Trade trade) {
        // Check if price recovered above entry after stop hit
        try {
            List<MarketData> priceData = marketDataRepository
                    .findBySymbolAndTimestampBetween(
                            trade.getOptionSymbol(),
                            trade.getExitTime(),
                            LocalDateTime.now()
                    );

            return priceData.stream()
                    .anyMatch(data -> data.getPrice().compareTo(trade.getEntryPrice()) > 0);
        } catch (Exception e) {
            return false;
        }
    }

    // Clear for next day
    @Scheduled(cron = "0 0 0 * * *") // Midnight
    public void clearDailyData() {
        todaysBlockedSignals.clear();
    }
}