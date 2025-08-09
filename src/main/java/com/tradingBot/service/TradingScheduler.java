package com.tradingBot.service;

import com.tradingBot.QQQTradingBotApplication;
import com.tradingBot.entity.Trade;
import com.tradingBot.entity.MarketData;
import com.tradingBot.entity.Signal;
import com.tradingBot.model.OrderRequest;
import com.tradingBot.model.OrderResponse;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.model.Quote;
import com.tradingBot.repository.SignalRepository;
import com.tradingBot.repository.TradeRepository;
import com.tradingBot.repository.MarketDataRepository;
import com.tradingBot.service.SafetyService.SafetyStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class TradingScheduler {

    private final ZeroDTEStrategy strategy;
    private final TradingService tradingService;
    private final TelegramService telegramService;
    private final TradeRepository tradeRepository;
    private final SignalRepository signalRepository;
    private final TradierService tradierService;
    private final MarketDataRepository marketDataRepository;
    private final SafetyService safetyService;
    private final PositionSyncService positionSyncService;

    private final UnifiedTrendDetector unifiedTrendDetector;

    private volatile BigDecimal cachedVix = null;
    private volatile LocalDateTime vixCacheTime = null;

    private final ZeroDTEStrategy zeroDTEStrategy;
    private final MarketTrendMonitor marketTrendMonitor; // ADD THIS

    @Scheduled(fixedRate = 60000) // Every minute
    public void executeScheduledAnalysis() {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);

        try {
            // Get trend from detector
            String marketTrend = unifiedTrendDetector.detectTrend("QQQ");

            // ENHANCED: Log trend with more detail from monitor
            MarketTrendMonitor.TrendState trendState = marketTrendMonitor.getCurrentTrendState("QQQ");
            if (trendState != null) {
                log.info("[{}] Market Trend: {} (Changes today: {}, Duration: {} min)",
                        analysisId, marketTrend, trendState.getChangeCount(),
                        trendState.getLastChangeTime() != null ?
                                Duration.between(trendState.getLastChangeTime(), LocalDateTime.now()).toMinutes() : 0);
            } else {
                log.info("[{}] Market Trend: {} (No trend history yet)", analysisId, marketTrend);
            }

            // Continue with your existing analysis
            List<Signal> signals = zeroDTEStrategy.analyzeOptions("QQQ", marketTrend);

            // ENHANCED: Add trend stability check for VWAP signals
            if (!signals.isEmpty() && trendState != null) {
                boolean isStableTrend = isTrendStableForVWAP(trendState);
                if (!isStableTrend) {
                    log.warn("[{}] ⚠️ Trend instability detected - {} changes in short time",
                            analysisId, trendState.getChangeCount());

                    // Optionally reduce signal confidence for unstable trends
                    signals.forEach(signal -> {
                        if (signal.getStrategy().contains("VWAP")) {
                            double originalConfidence = signal.getConfidence();
                            signal.setConfidence(originalConfidence * 0.9); // 10% reduction
                            log.info("[{}] Reduced VWAP signal confidence: {}% -> {}% due to trend instability",
                                    analysisId, (int)(originalConfidence * 100), (int)(signal.getConfidence() * 100));
                        }
                    });
                }
            }

        } catch (Exception e) {
            log.error("[{}] Error in scheduled analysis: {}", analysisId, e.getMessage());
        }
    }

    // Helper to check trend stability for VWAP signals
    private boolean isTrendStableForVWAP(MarketTrendMonitor.TrendState trendState) {
        if (trendState.getLastChangeTime() == null) return true;

        long minutesSinceChange = Duration.between(trendState.getLastChangeTime(), LocalDateTime.now()).toMinutes();
        int changeCount = trendState.getChangeCount();

        // Consider trend stable if:
        // - Less than 4 changes today, OR
        // - No change in last 15 minutes
        return changeCount < 4 || minutesSinceChange > 15;
    }


    @Value("${trading.symbol}")
    private String tradingSymbol;

    @Scheduled(cron = "*/15 * 9-16 * * MON-FRI")
    public void analyzeMarketAndGenerateSignals() {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        LocalTime now = LocalTime.now();
        log.info("[{}] ===== MARKET ANALYSIS START - {} =====", requestId, now);

        try {
            // Safety checks
            if (!safetyService.isTradingEnabled()) {
                log.warn("[{}] Trading is disabled - skipping market analysis", requestId);
                return;
            }

            SafetyStatus status = safetyService.getStatus();
            log.info("[{}] Safety check - Daily P&L: ${}, Open positions: {}/{}",
                    requestId, status.getTodayPnL(), status.getOpenPositions(), status.getMaxOpenPositions());

            if (!safetyService.canTrade()) {
                log.warn("[{}] Cannot open new trades - limits reached", requestId);
                // Check if it's due to daily loss limit (assuming negative P&L threshold)
                if (status.getTodayPnL() != null && status.getTodayPnL().compareTo(BigDecimal.valueOf(-500)) <= 0) {
                    log.error("[{}] DAILY LOSS LIMIT REACHED, trading will be haulted: ${}", requestId, status.getTodayPnL());
                }
                return;
            }

            log.info("[{}] Step 6: Starting option analysis for {}", requestId, tradingSymbol);

            // GET MARKET TREND
            String currentTrend = getQQQTrend();
            log.info("[{}] Current market trend: {}", requestId, currentTrend);

            // PASS TREND TO STRATEGY
            List<Signal> signals = strategy.analyzeOptions(tradingSymbol, currentTrend);
            log.info("[{}] Analysis complete - Generated {} signals", requestId, signals.size());

            if (!signals.isEmpty()) {
                log.info("[v62][{}] === GENERATED SIGNALS ===", requestId);
                for (Signal signal : signals) {
                    log.info("[v62][{}] Signal: {} {} - Strategy: {}, Confidence: {}%, Target: ${}, Stop: ${}, Trend: {}",
                            requestId, signal.getSignalType(), signal.getOptionSymbol(),
                            signal.getStrategy(), (int)(signal.getConfidence() * 100),
                            signal.getTargetPrice(), signal.getStopLoss(), currentTrend);
                }
                log.info("[v62][{}] ========================", requestId);
            }

            log.info("[v62][{}] ===== MARKET ANALYSIS COMPLETE =====", requestId);
        } catch (Exception e) {
            log.error("[v62][{}] ERROR in market analysis: {}", requestId, e.getMessage(), e);
            telegramService.sendMessage(String.format("⚠️ Market analysis error [%s]: %s", requestId, e.getMessage()));
        }
    }

    // Add this debug method to TradingScheduler.java
    @Scheduled(cron = "*/5 * 9-16 * * MON-FRI")
    public void executePendingSignals() {
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        LocalTime now = LocalTime.now();
        log.info("[v62][{}] ===== SIGNAL EXECUTION START - {} =====", executionId, now);

        if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(16, 00))) {
            log.info("[v62][{}] Outside trading hours - skipping execution", executionId);
            return;
        }

        try {
            if (!safetyService.isTradingEnabled()) {
                log.warn("[v62][{}] Trading is DISABLED via master switch - skipping execution", executionId);
                return;
            }

            if (!safetyService.canTrade()) {
                log.warn("[v62][{}] Trading BLOCKED by safety checks - skipping execution", executionId);
                return;
            }

            log.info("[v62][{}] Executing pending signals - SHOULD PLACE BUY_TO_OPEN ORDERS", executionId);
            // Remove this line if it exists and is calling wrong method:
            // tradingService.executeSignals();

            log.info("[v62][{}] Monitoring open positions - SHOULD PLACE SELL_TO_CLOSE ORDERS", executionId);
            List<Trade> openTrades = tradeRepository.findByStatusAndSymbol("OPEN", tradingSymbol);
            if (!openTrades.isEmpty()) {
                log.info("[v62][{}] Monitoring {} open positions", executionId, openTrades.size());
                for (Trade trade : openTrades) {
                    log.debug("[v62][{}] Open position: {} - Entry: ${}, Qty: {}",
                            executionId, trade.getOptionSymbol(), trade.getEntryPrice(), trade.getQuantity());
                }
                // Only call this for existing trades, not new signals:
                tradingService.checkOpenPositions();
            }

            log.info("[v62][{}] ===== SIGNAL EXECUTION COMPLETE =====", executionId);
        } catch (Exception e) {
            log.error("[v62][{}] ERROR in signal execution: {}", executionId, e.getMessage(), e);
        }
    }

    @Scheduled(fixedRate = 1000)
    public void collectMarketData() {
        LocalTime now = LocalTime.now();
        DayOfWeek day = LocalDate.now().getDayOfWeek();

        // Skip weekends
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return;
        }
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(16, 0))) {
            return;
        }

        // Collect data for both symbols
        String[] symbols = {"QQQ", "SPY","AAPL","MSFT","NVDA"};

        for (String symbol : symbols) {
            try {
                QuoteResponse quoteResponse = tradierService.getQuote(symbol);
                if (quoteResponse != null && quoteResponse.getQuote() != null) {
                    Quote quote = quoteResponse.getQuote();
                    MarketData marketData = new MarketData();
                    marketData.setSymbol(symbol); // ✅ Now handles both
                    marketData.setPrice(quote.getLast());
                    marketData.setBid(quote.getBid());
                    marketData.setAsk(quote.getAsk());
                    marketData.setBidSize(quote.getBidSize());
                    marketData.setAskSize(quote.getAskSize());
                    marketData.setVolume(quote.getVolume());
                    marketData.setHigh(quote.getHigh());
                    marketData.setLow(quote.getLow());
                    marketData.setPreviousClose(quote.getPreviousClose());
                    marketData.setTimestamp(LocalDateTime.now());
                    marketDataRepository.save(marketData);

                    //log.debug("Collected market data for {}: ${}", symbol, quote.getLast());
                }
            } catch (Exception e) {
                log.error("Error collecting market data for {}: {}", symbol, e.getMessage());
            }
        }
    }

    @Autowired
    private TradingAnalysisService analysisService;

    @Scheduled(cron = "0 0 16 * * MON-FRI")
    public void dailySummary() {
        log.info("[v62] Generating enhanced daily summary");

        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0);
        List<Trade> todaysTrades = tradeRepository.findClosedTradesAfter(startOfDay);
        BigDecimal totalProfit = tradeRepository.calculateProfitSince(startOfDay);
        if (totalProfit == null) totalProfit = BigDecimal.ZERO;

        // Original stats
        long winners = todaysTrades.stream()
                .filter(t -> t.getProfit() != null && t.getProfit().compareTo(BigDecimal.ZERO) > 0)
                .count();
        long losers = todaysTrades.size() - winners;

        BigDecimal avgWin = BigDecimal.ZERO;
        if (winners > 0) {
            avgWin = todaysTrades.stream()
                    .filter(t -> t.getProfit() != null && t.getProfit().compareTo(BigDecimal.ZERO) > 0)
                    .map(Trade::getProfit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(winners), 2, BigDecimal.ROUND_HALF_UP);
        }

        BigDecimal avgLoss = BigDecimal.ZERO;
        if (losers > 0) {
            avgLoss = todaysTrades.stream()
                    .filter(t -> t.getProfit() != null && t.getProfit().compareTo(BigDecimal.ZERO) < 0)
                    .map(Trade::getProfit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(losers), 2, BigDecimal.ROUND_HALF_UP);
        }

        // Build enhanced summary
        StringBuilder summary = new StringBuilder();
        summary.append("📊 <b>DAILY TRADING SUMMARY</b>\n");
        summary.append("═══════════════════════\n\n");

        summary.append(String.format("📈 Trades: %d | Win Rate: %.1f%%\n",
                todaysTrades.size(), winners > 0 ? (winners * 100.0 / todaysTrades.size()) : 0));
        summary.append(String.format("✅ Winners: %d | ❌ Losers: %d\n", winners, losers));
        summary.append(String.format("💰 Total P&L: $%.2f\n", totalProfit));
        summary.append(String.format("📊 Avg Win: $%.2f | Avg Loss: $%.2f\n", avgWin, avgLoss));

        // Add blocked signals analysis
        String blockedAnalysis = analysisService.analyzeBlockedSignals();
        if (!blockedAnalysis.isEmpty()) {
            summary.append("\n🚫 <b>BLOCKED SIGNALS ANALYSIS</b>\n");
            summary.append("════════════════════════\n");
            summary.append(blockedAnalysis);
        }

        // Add failed trades analysis
        String failedAnalysis = analysisService.analyzeFailedTrades(todaysTrades);
        if (!failedAnalysis.isEmpty()) {
            summary.append("\n" + failedAnalysis);
        }

        // Add optimization suggestions
        summary.append("\n🔧 <b>OPTIMIZATION SUGGESTIONS</b>\n");
        summary.append("══════════════════════════\n");
        summary.append(generateOptimizationSuggestions(todaysTrades));

        // Bot info
        summary.append(String.format("\n🤖 Bot Version: %s | Date: %s",
                QQQTradingBotApplication.VERSION, LocalDate.now()));

        // Send the enhanced summary
        telegramService.sendMessage(summary.toString());

        log.info("[v62] Enhanced daily summary sent");
    }
//    private final ZeroDTEStrategy.SignalAttributeLearning attributeLearning = new ZeroDTEStrategy.SignalAttributeLearning();
//    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "America/New_York") // 4:30 PM ET daily
//    public void sendAILearningSummary() {
//
//        try {
//            attributeLearning.sendDailySummary();
//        } catch (Exception e) {
//            log.error("Error sending AI learning summary: {}", e.getMessage());
//        }
//    }

    private String generateOptimizationSuggestions(List<Trade> trades) {
        StringBuilder suggestions = new StringBuilder();

        // Analyze stop losses
        long stoppedOut = trades.stream()
                .filter(t -> "STOP_LOSS".equals(t.getExitReason()))
                .count();

        if (stoppedOut > trades.size() * 0.3) {
            suggestions.append("• High stop-out rate (")
                    .append(stoppedOut * 100 / trades.size())
                    .append("%) - Consider wider stops\n");
        }

        // Check if we're missing morning opportunities
        LocalTime avgEntryTime = trades.stream()
                .map(t -> t.getEntryTime().toLocalTime())
                .reduce(LocalTime.of(0,0), (a, b) -> a.plusSeconds(b.toSecondOfDay()))
                .withSecond(trades.size() > 0 ? trades.size() : 1);

        if (avgEntryTime.isAfter(LocalTime.of(11, 0))) {
            suggestions.append("• Missing morning opportunities - Check breadth thresholds\n");
        }

        // Add more based on your needs
        if (suggestions.length() == 0) {
            suggestions.append("• No specific optimizations identified\n");
        }

        return suggestions.toString();
    }

    @Scheduled(cron = "0 30 9 * * MON-FRI")
    public void marketOpenNotification() {
        telegramService.sendMessage("🔔 Market is open! Bot will start trading at 10:00 AM ET to avoid morning reports.");
    }

    private void closePosition(Trade trade, String reason) {
        try {
            log.info("[CLOSE] Starting position close for {} - Reason: {}",
                    trade.getOptionSymbol(), reason);

            BigDecimal currentPrice = trade.getCurrentPrice();
            if (currentPrice == null) {
                currentPrice = tradierService.getOptionPrice(trade.getOptionSymbol());
            }

            BigDecimal pnl = currentPrice.subtract(trade.getEntryPrice())
                    .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));

            OrderRequest closeRequest = new OrderRequest();
            closeRequest.setSymbol(trade.getOptionSymbol());
            closeRequest.setQuantity(trade.getQuantity());
            closeRequest.setSide("sell_to_close");
            closeRequest.setType("MARKET");
            closeRequest.setDuration("DAY");

            OrderResponse response = tradierService.placeOrder(closeRequest);

            if (response != null) {
                log.info("[CLOSE] ✅ Successfully closed {} - P&L: ${}",
                        trade.getOptionSymbol(), pnl);

                trade.setStatus("CLOSED");
                trade.setExitTime(LocalDateTime.now());
                trade.setExitPrice(currentPrice);
                trade.setProfit(pnl);

                tradeRepository.save(trade);

                tradingService.updateAILearning(trade);

                String emoji = pnl.compareTo(BigDecimal.ZERO) > 0 ? "💰" : "💸";
                telegramService.sendMessage(String.format(
                        "%s Position Closed: %s\nReason: %s\nP&L: $%.2f",
                        emoji, trade.getOptionSymbol(), reason, pnl
                ));
            } else {
                log.error("[CLOSE] Failed to close position {}", trade.getOptionSymbol());
            }

        } catch (Exception e) {
            log.error("[CLOSE] Error closing position {}: {}",
                    trade.getOptionSymbol(), e.getMessage());
        }
    }

    // Update monitoring to only track OPEN trades in TradingScheduler.java
    @Scheduled(cron = "*/10 * 9-16 * * MON-FRI")
    public void monitorPositions() {
        String monitorId = generateExecutionId();
        log.info("[MONITOR][{}] Checking OPEN positions only", monitorId);

        // ONLY monitor OPEN trades - ignore FAILED, REJECTED, etc.
        List<Trade> openTrades = tradeRepository.findByStatusAndSymbol("OPEN", tradingSymbol).stream()
                .filter(trade -> {
                    boolean hasOrderId = trade.getOrderId() != null && !trade.getOrderId().trim().isEmpty();
                    boolean hasEntryPrice = trade.getEntryPrice() != null &&
                            trade.getEntryPrice().compareTo(BigDecimal.ZERO) > 0;
                    boolean isRecent = trade.getEntryTime() != null &&
                            trade.getEntryTime().isAfter(LocalDateTime.now().minusHours(24));

                    return hasOrderId && hasEntryPrice && isRecent;
                })
                .collect(Collectors.toList());

        log.info("[MONITOR][{}] Found {} OPEN positions to monitor", monitorId, openTrades.size());

        // Also log rejected/failed counts for visibility
        long failedCount = tradeRepository.countByStatus("FAILED");
        long rejectedCount = tradeRepository.countByStatus("BAYESIAN_REJECTED");

        log.info("[MONITOR][{}] Database status - OPEN: {}, FAILED: {}, REJECTED: {}",
                monitorId, openTrades.size(), failedCount, rejectedCount);

        if (!openTrades.isEmpty()) {
            String currentTrend = getQQQTrend();
            for (Trade trade : openTrades) {
                monitorPosition(trade, currentTrend);
            }
        }
    }

    // Add this cleanup method to TradingScheduler.java
    @Scheduled(cron = "0 */30 * * * *") // Every 30 minutes
    public void cleanupInvalidTrades() {
        try {
            // Find trades that are OPEN but have no valid order ID (older than 1 hour)
            List<Trade> invalidTrades = tradeRepository.findByStatus("OPEN").stream()
                    .filter(trade -> trade.getOrderId() == null || trade.getOrderId().trim().isEmpty() || "unknown".equals(trade.getOrderId()))
                    .filter(trade -> trade.getEntryTime() != null && trade.getEntryTime().isBefore(LocalDateTime.now().minusHours(1)))
                    .collect(Collectors.toList());

            if (!invalidTrades.isEmpty()) {
                log.warn("[CLEANUP] Found {} invalid OPEN trades without order IDs - marking as FAILED", invalidTrades.size());

                for (Trade trade : invalidTrades) {
                    trade.setStatus("FAILED");
                    trade.setFailureReason("NO_ORDER_ID");
                    trade.setExitReason("Cleanup - no valid order ID");
                    trade.setExitTime(LocalDateTime.now());
                    tradeRepository.save(trade);

                    log.warn("[CLEANUP] Marked Trade ID {} as FAILED - No valid order ID", trade.getId());
                }
            }

            // Cleanup very old PENDING signals (older than 2 hours)
            List<Signal> oldSignals = signalRepository.findByStatus("PENDING").stream()
                    .filter(signal -> signal.getCreatedAt() != null && signal.getCreatedAt().isBefore(LocalDateTime.now().minusHours(2)))
                    .collect(Collectors.toList());

            if (!oldSignals.isEmpty()) {
                log.info("[CLEANUP] Found {} old PENDING signals - marking as EXPIRED", oldSignals.size());

                for (Signal signal : oldSignals) {
                    signal.setStatus("EXPIRED");
                    signal.setExecuted(true);
                    signalRepository.save(signal);
                }
            }

        } catch (Exception e) {
            log.error("[CLEANUP] Error during cleanup: {}", e.getMessage());
        }
    }

    private void monitorPosition(Trade trade, String marketTrend) {
        try {
            BigDecimal currentPrice = tradierService.getOptionPrice(trade.getOptionSymbol());

            if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("[MONITOR] Could not get price for {}", trade.getOptionSymbol());
                return;
            }

            trade.setCurrentPrice(currentPrice);

            BigDecimal pnl = currentPrice.subtract(trade.getEntryPrice())
                    .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));

            BigDecimal pnlPercent = pnl.divide(
                    trade.getEntryPrice().multiply(BigDecimal.valueOf(trade.getQuantity() * 100)),
                    2, RoundingMode.HALF_UP
            ).multiply(BigDecimal.valueOf(100));

            // CHECK TARGETS/STOPS FIRST
            if (trade.getTarget() != null && currentPrice.compareTo(trade.getTarget()) >= 0) {
                log.info("[MONITOR] 🎯 TARGET HIT for {} - Current: ${} >= Target: ${}",
                        trade.getOptionSymbol(), currentPrice, trade.getTarget());
                closePosition(trade, "TARGET");
                return;
            }

            if (trade.getStopLoss() != null && currentPrice.compareTo(trade.getStopLoss()) <= 0) {
                log.warn("[MONITOR] 🛑 STOP LOSS HIT for {} - Current: ${} <= Stop: ${}",
                        trade.getOptionSymbol(), currentPrice, trade.getStopLoss());
                closePosition(trade, "STOP_LOSS");
                return;
            }

            // DYNAMIC ADJUSTMENTS
            updateTrailingStopInline(trade, currentPrice);
            adjustForVolatility(trade, currentPrice);
            applyPositionSpecificStrategy(trade, currentPrice, marketTrend);

            // Position alignment
            boolean isPut = trade.getOptionSymbol().contains("P");
            String alignment = "";
            String recommendation = "";

            if (isPut) {
                if ("DOWN".equals(marketTrend)) {
                    alignment = "✅ ALIGNED";
                    recommendation = "HOLD/ADD";
                } else if ("UP".equals(marketTrend)) {
                    alignment = "❌ AGAINST";
                    recommendation = "EXIT/REDUCE";
                } else {
                    alignment = "⚠️ NEUTRAL";
                    recommendation = "MONITOR";
                }
            } else {
                if ("UP".equals(marketTrend)) {
                    alignment = "✅ ALIGNED";
                    recommendation = "HOLD/ADD";
                } else if ("DOWN".equals(marketTrend)) {
                    alignment = "❌ AGAINST";
                    recommendation = "EXIT/REDUCE";
                } else {
                    alignment = "⚠️ NEUTRAL";
                    recommendation = "MONITOR";
                }
            }

            String emoji = pnl.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
            String positionType = isPut ? "PUT" : "CALL";

            BigDecimal stopDistance = trade.getStopLoss() != null ?
                    currentPrice.subtract(trade.getStopLoss()) : BigDecimal.ZERO;
            BigDecimal stopPercent = trade.getStopLoss() != null ?
                    stopDistance.divide(currentPrice, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO;

            BigDecimal targetDistance = trade.getTarget() != null ?
                    trade.getTarget().subtract(currentPrice) : BigDecimal.ZERO;
            BigDecimal targetPercent = trade.getTarget() != null ?
                    targetDistance.divide(currentPrice, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO;

            log.info("[MONITOR] {} {} {} | Trend: {} {} | Entry: ${} → Current: ${} | P&L: ${} ({}%) | Stop: ${} (-{}%) | Target: ${} (+{}%) | Action: {}",
                    emoji,
                    trade.getOptionSymbol(),
                    positionType,
                    marketTrend,
                    alignment,
                    trade.getEntryPrice(),
                    currentPrice,
                    pnl,
                    pnlPercent,
                    trade.getStopLoss(),
                    stopPercent.abs(),
                    trade.getTarget(),
                    targetPercent,
                    recommendation
            );

            if ("❌ AGAINST".equals(alignment) && pnl.compareTo(BigDecimal.ZERO) < 0) {
                log.warn("[MONITOR] ⚠️ WARNING: {} position AGAINST {} trend and LOSING ${} - Consider EXIT",
                        positionType, marketTrend, pnl.abs());
            }

            if ("✅ ALIGNED".equals(alignment) && pnlPercent.compareTo(BigDecimal.valueOf(5)) > 0) {
                log.info("[MONITOR] 💚 OPPORTUNITY: {} position ALIGNED with {} trend and UP {}% - Consider ADDING",
                        positionType, marketTrend, pnlPercent);
            }

            tradeRepository.save(trade);

        } catch (Exception e) {
            log.error("[MONITOR] Error monitoring position {}: {}",
                    trade.getOptionSymbol(), e.getMessage());
        }
    }

    private void updateTrailingStopInline(Trade trade, BigDecimal currentPrice) {
        try {
            BigDecimal entryPrice = trade.getEntryPrice();
            BigDecimal currentStop = trade.getStopLoss();

            BigDecimal profitPercent = currentPrice.subtract(entryPrice)
                    .divide(entryPrice, 4, RoundingMode.HALF_UP);

            if (profitPercent.compareTo(BigDecimal.valueOf(0.05)) > 0) {
                BigDecimal newStop = currentPrice.multiply(BigDecimal.valueOf(0.85));

                if (newStop.compareTo(currentStop) > 0) {
                    trade.setStopLoss(newStop);

                    log.info("[TRAILING] Updated stop for {} from ${} to ${} ({}% profit)",
                            trade.getOptionSymbol(), currentStop, newStop,
                            profitPercent.multiply(BigDecimal.valueOf(100)));

                    telegramService.sendMessage(String.format(
                            "📈 Trailing stop updated for %s\nNew stop: $%.2f (was $%.2f)\nProfit: %.1f%%",
                            trade.getOptionSymbol(), newStop, currentStop,
                            profitPercent.multiply(BigDecimal.valueOf(100)).doubleValue()
                    ));
                }
            }
        } catch (Exception e) {
            log.error("[TRAILING] Error updating trailing stop: {}", e.getMessage());
        }
    }

    private String generateExecutionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void adjustForVolatility(Trade trade, BigDecimal currentPrice) {
        try {
            QuoteResponse qqqResponse = tradierService.getQuote("QQQ");
            if (qqqResponse == null || qqqResponse.getQuote() == null) return;

            Quote qqqQuote = qqqResponse.getQuote();
            if (qqqQuote.getHigh() == null || qqqQuote.getLow() == null || qqqQuote.getLast() == null) return;

            BigDecimal dayRange = qqqQuote.getHigh().subtract(qqqQuote.getLow());
            BigDecimal volatility = dayRange.divide(qqqQuote.getLast(), 4, BigDecimal.ROUND_HALF_UP);

            if (volatility.compareTo(BigDecimal.valueOf(0.01)) > 0) {
                BigDecimal volatilityMultiplier = BigDecimal.ONE.subtract(volatility.multiply(BigDecimal.valueOf(2)));
                BigDecimal widerStop = currentPrice.multiply(volatilityMultiplier);

                if (trade.getStopLoss() != null && widerStop.compareTo(trade.getStopLoss()) < 0) {
                    log.info("[MONITOR] 🌊 VOLATILITY ADJUSTMENT - Volatility: {}%, Wider stop: ${} (was ${})",
                            volatility.multiply(BigDecimal.valueOf(100)), widerStop, trade.getStopLoss());
                    trade.setStopLoss(widerStop);
                }
            }
        } catch (Exception e) {
            log.error("Error adjusting for volatility: {}", e.getMessage());
        }
    }

    private void applyPositionSpecificStrategy(Trade trade, BigDecimal currentPrice, String marketTrend) {
        try {
            boolean isPut = trade.getOptionSymbol().contains("P");
            boolean isAligned = (isPut && "DOWN".equals(marketTrend)) || (!isPut && "UP".equals(marketTrend));

            // Calculate current profit percentage
            BigDecimal profitPercent = currentPrice.subtract(trade.getEntryPrice())
                    .divide(trade.getEntryPrice(), 4, RoundingMode.HALF_UP);

            if (isAligned) {
                // POSITION ALIGNED WITH TREND - BE AGGRESSIVE

                // If profitable, extend targets
                if (profitPercent.compareTo(BigDecimal.valueOf(0.20)) > 0) { // 20% profit
                    BigDecimal newTarget = trade.getTarget().multiply(BigDecimal.valueOf(1.5));
                    if (newTarget.compareTo(trade.getTarget()) > 0) {
                        log.info("[MONITOR] 🚀 TREND ALIGNED & PROFITABLE - Extending target from ${} to ${}",
                                trade.getTarget(), newTarget);
                        trade.setTarget(newTarget);

                        // Also trail stop closer
                        BigDecimal newStop = currentPrice.multiply(BigDecimal.valueOf(0.80)); // 20% stop
                        if (newStop.compareTo(trade.getStopLoss()) > 0) {
                            trade.setStopLoss(newStop);
                            log.info("[MONITOR] 📊 Tightening stop to ${} to lock in profits", newStop);
                        }
                    }
                }

                // If slightly profitable, just trail stop
                else if (profitPercent.compareTo(BigDecimal.valueOf(0.10)) > 0) { // 10% profit
                    BigDecimal newStop = currentPrice.multiply(BigDecimal.valueOf(0.85)); // 15% stop
                    if (newStop.compareTo(trade.getStopLoss()) > 0) {
                        trade.setStopLoss(newStop);
                        log.info("[MONITOR] 📈 Trailing stop to ${} (trend aligned)", newStop);
                    }
                }

            } else {
                // POSITION AGAINST TREND - BE DEFENSIVE

                // Tighten stops aggressively
                BigDecimal tightStop = currentPrice.multiply(BigDecimal.valueOf(0.95)); // 5% stop
                if (trade.getStopLoss() == null || tightStop.compareTo(trade.getStopLoss()) > 0) {
                    log.warn("[MONITOR] ⚠️ AGAINST TREND - Tightening stop to ${}", tightStop);
                    trade.setStopLoss(tightStop);
                }

                // Lower targets to take profits quickly
                if (profitPercent.compareTo(BigDecimal.valueOf(0.10)) > 0) { // Any profit
                    BigDecimal quickTarget = currentPrice.multiply(BigDecimal.valueOf(1.15)); // 15% target
                    if (quickTarget.compareTo(trade.getTarget()) < 0) {
                        log.info("[MONITOR] 💰 AGAINST TREND - Lowering target to ${} to secure profit", quickTarget);
                        trade.setTarget(quickTarget);
                    }
                }
            }

            // TIME-BASED ADJUSTMENTS
            LocalTime now = LocalTime.now();
            if (now.isAfter(LocalTime.of(15, 0))) { // Last hour
                // Tighten both stops and targets
                BigDecimal eodStop = currentPrice.multiply(BigDecimal.valueOf(0.90)); // 10% stop
                BigDecimal eodTarget = currentPrice.multiply(BigDecimal.valueOf(1.20)); // 20% target

                if (eodStop.compareTo(trade.getStopLoss()) > 0) {
                    trade.setStopLoss(eodStop);
                    log.info("[MONITOR] ⏰ END OF DAY - Tightening stop to ${}", eodStop);
                }

                if (eodTarget.compareTo(trade.getTarget()) < 0) {
                    trade.setTarget(eodTarget);
                    log.info("[MONITOR] ⏰ END OF DAY - Lowering target to ${}", eodTarget);
                }
            }

        } catch (Exception e) {
            log.error("Error applying position-specific strategy: {}", e.getMessage());
        }
    }


    public String getQQQTrend() {
        try {
            return unifiedTrendDetector.detectTrend("QQQ");
        } catch (Exception e) {
            log.error("Error getting unified trend, defaulting to NEUTRAL: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

//    @Scheduled(fixedDelay = 60000) // Every minute
//    public void logDetailedTrendAnalysis() {
//        if (!safetyService.isTradingEnabled()) {
//            return;
//        }
//        try {
//            UnifiedTrendDetector.EnhancedTrendResult detailed =
//                    unifiedTrendDetector.getTrendDetailed("QQQ");
//            log.debug("[TREND-DETAIL] Components - Momentum: {}, Volume: {}, " +
//                            "Microstructure: {}, Regime: {}, ML: {}",
//                    String.format("%.2f",detailed.getMomentumScore()),
//                    String.format("%.2f",detailed.getVolumeScore()),
//                    String.format("%.2f",detailed.getMicrostructureScore()),
//                    String.format("%.2f",detailed.getRegimeScore()),
//                    String.format("%.2f",detailed.getMlValidationScore())
//            );
//            if (detailed.getMlProbabilities() != null) {
//                log.debug("[TREND-DETAIL] ML Probabilities - UP: {}%, NEUTRAL: {}%, DOWN: {}%",
//                        (int)(detailed.getMlProbabilities().getOrDefault("UP", 0.0) * 100),
//                        (int)(detailed.getMlProbabilities().getOrDefault("NEUTRAL", 0.0) * 100),
//                        (int)(detailed.getMlProbabilities().getOrDefault("DOWN", 0.0) * 100)
//                );
//            }
//        } catch (Exception e) {
//            log.error("Error in detailed trend logging: {}", e.getMessage());
//        }
//    }
}