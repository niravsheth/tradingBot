package com.tradingBot.service;

import com.tradingBot.QQQTradingBotApplication;
import com.tradingBot.entity.Trade;
import com.tradingBot.entity.MarketData;
import com.tradingBot.entity.Signal;
import com.tradingBot.model.OrderRequest;
import com.tradingBot.model.OrderResponse;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.model.Quote;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class TradingScheduler {

    private final ZeroDTEStrategy strategy;
    private final TradingService tradingService;
    private final TelegramService telegramService;
    private final TradeRepository tradeRepository;
    private final TradierService tradierService;
    private final MarketDataRepository marketDataRepository;
    private final SafetyService safetyService;
    private final PositionSyncService positionSyncService;

    private final CapitalAllocationService capitalAllocationService;

    private final EnhancedTrendService enhancedTrendService;

    private static final Map<String, CachedQuote> componentQuoteCache = new ConcurrentHashMap<>();
    private static final long COMPONENT_CACHE_TTL = 30000; // 30 seconds
    private volatile BigDecimal cachedVix = null;
    private volatile LocalDateTime vixCacheTime = null;

    @Value("${trading.symbol}")
    private String tradingSymbol;

    @Scheduled(cron = "*/15 * 9-16 * * MON-FRI")
    public void analyzeMarketAndGenerateSignals() {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        LocalTime now = LocalTime.now();
        log.info("[v62][{}] ===== MARKET ANALYSIS START - {} =====", requestId, now);

        try {
            // Safety checks
            if (!safetyService.isTradingEnabled()) {
                log.warn("[v62][{}] Trading is disabled - skipping market analysis", requestId);
                return;
            }

            SafetyStatus status = safetyService.getStatus();
            log.info("[v62][{}] Safety check - Daily P&L: ${}, Open positions: {}/{}",
                    requestId, status.getTodayPnL(), status.getOpenPositions(), status.getMaxOpenPositions());

            if (!safetyService.canTrade()) {
                log.warn("[v62][{}] Cannot open new trades - limits reached", requestId);
                // Check if it's due to daily loss limit (assuming negative P&L threshold)
                if (status.getTodayPnL() != null && status.getTodayPnL().compareTo(BigDecimal.valueOf(-500)) <= 0) {
                    log.error("[v62][{}] DAILY LOSS LIMIT REACHED: ${}", requestId, status.getTodayPnL());
                    telegramService.sendMessage(String.format("🛑 Daily loss limit reached: $%s - Trading halted",
                            status.getTodayPnL()));
                }
                return;
            }

            log.info("[v62][{}] Step 6: Starting option analysis for {}", requestId, tradingSymbol);

            // GET MARKET TREND
            String currentTrend = getQQQTrend();
            log.info("[v62][{}] Current market trend: {}", requestId, currentTrend);

            // PASS TREND TO STRATEGY
            List<Signal> signals = strategy.analyzeOptions(tradingSymbol, currentTrend);
            log.info("[v62][{}] Analysis complete - Generated {} signals", requestId, signals.size());

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
                SafetyStatus status = safetyService.getStatus();
                log.warn("[v62][{}] Safety details - Today P&L: ${}, Open positions: {}/{}",
                        executionId, status.getTodayPnL(), status.getOpenPositions(), status.getMaxOpenPositions());
                return;
            }

            log.info("[v62][{}] Executing pending signals", executionId);
            tradingService.executeSignals();

            log.info("[v62][{}] Monitoring open positions", executionId);
            List<Trade> openTrades = tradeRepository.findByStatusAndSymbol("OPEN", tradingSymbol);
            if (!openTrades.isEmpty()) {
                log.info("[v62][{}] Monitoring {} open positions", executionId, openTrades.size());
                for (Trade trade : openTrades) {
                    log.debug("[v62][{}] Open position: {} - Entry: ${}, Qty: {}",
                            executionId, trade.getOptionSymbol(), trade.getEntryPrice(), trade.getQuantity());
                }
                tradingService.checkOpenPositions();
            }

            log.info("[v62][{}] ===== SIGNAL EXECUTION COMPLETE =====", executionId);
        } catch (Exception e) {
            log.error("[v62][{}] ERROR in signal execution: {}", executionId, e.getMessage(), e);
            telegramService.sendMessage(String.format("⚠️ Execution error [%s]: %s", executionId, e.getMessage()));
        }
    }

    @Scheduled(cron = "0 * 9-16 * * MON-FRI")
    public void collectMarketData() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(16, 0))) {
            return;
        }

        try {
            QuoteResponse quoteResponse = tradierService.getQuote("QQQ");
            if (quoteResponse != null && quoteResponse.getQuote() != null) {
                Quote quote = quoteResponse.getQuote();
                MarketData marketData = new MarketData();
                marketData.setSymbol("QQQ");
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
            }
        } catch (Exception e) {
            log.error("Error collecting market data: {}", e.getMessage());
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

    @Scheduled(cron = "0 0 10 * * MON-FRI")
    public void tradingStartNotification() {
        telegramService.sendMessage("🚀 Trading session started! 0DTE QQQ bot is now actively scanning for opportunities.");
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

    @Scheduled(cron = "*/10 * 9-16 * * MON-FRI")
    public void monitorPositions() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(16, 0))) {
            return;
        }

        String monitorId = generateExecutionId();
        log.info("[MONITOR][{}] Checking open positions", monitorId);

        List<Trade> openTrades = tradeRepository.findByStatus("OPEN");

        if (openTrades.isEmpty()) {
            return;
        }

        log.info("[MONITOR][{}] Monitoring {} open positions", monitorId, openTrades.size());

        String currentTrend = getQQQTrend();

        for (Trade trade : openTrades) {
            monitorPosition(trade, currentTrend);
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

    // UPDATED METHOD WITH DYNAMIC ADJUSTMENTS
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

    private static class CachedQuote {
        Quote quote;
        LocalDateTime timestamp;

        CachedQuote(Quote quote) {
            this.quote = quote;
            this.timestamp = LocalDateTime.now();
        }

        boolean isExpired() {
            return LocalDateTime.now().isAfter(timestamp.plusSeconds(30));
        }
    }

//    public String getQQQTrend() {
//        try {
//            QuoteResponse response = tradierService.getQuote("QQQ");
//            if (response == null || response.getQuote() == null) {
//                return "NEUTRAL";
//            }
//
//            Quote quote = response.getQuote();
//            BigDecimal currentPrice = quote.getLast();
//
//            // Simple but effective trend detection
//            double trendScore = 0.0;
//
//            // 1. Immediate price action (most weight)
//            List<MarketData> recentData = marketDataRepository.findRecentData("QQQ", 10);
//            if (recentData.size() >= 3) {
//                BigDecimal price3MinAgo = recentData.get(recentData.size() - 3).getPrice();
//                BigDecimal priceChange = currentPrice.subtract(price3MinAgo)
//                        .divide(price3MinAgo, 4, RoundingMode.HALF_UP);
//
//                // Much more sensitive - 0.1% = 10 basis points
//                if (priceChange.compareTo(BigDecimal.valueOf(0.001)) > 0) {
//                    trendScore += 3.0; // Strong weight for recent move
//                } else if (priceChange.compareTo(BigDecimal.valueOf(-0.001)) < 0) {
//                    trendScore -= 3.0;
//                }
//            }
//
//            // 2. Day's trend (less weight)
//            BigDecimal dayChange = currentPrice.subtract(quote.getPreviousClose())
//                    .divide(quote.getPreviousClose(), 4, RoundingMode.HALF_UP);
//
//            if (dayChange.compareTo(BigDecimal.valueOf(0.002)) > 0) {
//                trendScore += 1.0;
//            } else if (dayChange.compareTo(BigDecimal.valueOf(-0.002)) < 0) {
//                trendScore -= 1.0;
//            }
//
//            // 3. Position in day's range
//            if (quote.getHigh() != null && quote.getLow() != null) {
//                BigDecimal range = quote.getHigh().subtract(quote.getLow());
//                if (range.compareTo(BigDecimal.ZERO) > 0) {
//                    BigDecimal position = currentPrice.subtract(quote.getLow())
//                            .divide(range, 2, RoundingMode.HALF_UP);
//
//                    if (position.compareTo(BigDecimal.valueOf(0.7)) > 0) {
//                        trendScore += 1.5;
//                    } else if (position.compareTo(BigDecimal.valueOf(0.3)) < 0) {
//                        trendScore -= 1.5;
//                    }
//                }
//            }
//
//            // Simple thresholds
//            String trend;
//            if (trendScore >= 2.0) {
//                trend = "UP";
//            } else if (trendScore <= -2.0) {
//                trend = "DOWN";
//            } else {
//                trend = "NEUTRAL";
//            }
//
//            log.info("[TREND] QQQ Trend: {} (Score: {})", trend, String.format("%.1f", trendScore));
//            return trend;
//
//        } catch (Exception e) {
//            log.error("Error determining trend: {}", e.getMessage());
//            return "NEUTRAL";
//        }
//    }
    private final AdvancedTrendDetector advancedTrendDetector;
    // Add to TradingScheduler class fields
    private final IntegratedTrendService integratedTrendService;

    public String getQQQTrend() {
        try {
//            // Use enhanced trend service with ML validation
//            String trend = enhancedTrendService.getTrend("QQQ");
//
//            // Get detailed analysis for logging and alerts
//            EnhancedTrendService.EnhancedTrendResult detailed =
//                    enhancedTrendService.getTrendDetailed("QQQ");
//
//            // Enhanced logging with all factors
//            log.info("[TREND] QQQ - {} ({}%) | Base: {} | Z-Score: {:.2f} | Volume: {:.1fx | ML: {} | Regime: {} | Time: {}ms",
//                    trend,
//                    (int)(detailed.getFinalConfidence() * 100),
//                    detailed.getBaseTrend(),
//                    detailed.getZScore(),
//                    detailed.getVolumeScore(),
//                    detailed.getMlValidationScore() > 0 ? "Confirmed" : "Divergent",
//                    detailed.getMarketRegime(),
//                    detailed.getCalculationTimeMs()
//            );
//
//            // Alert conditions
//            handleTrendAlerts(detailed);

            return advancedTrendDetector.detectTrend("QQQ");

        } catch (Exception e) {
            log.error("Error getting enhanced trend, defaulting to NEUTRAL: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    private void handleTrendAlerts(EnhancedTrendService.EnhancedTrendResult detailed) {
        try {
            // Alert for extreme z-scores (3-sigma events)
            if (Math.abs(detailed.getZScore()) > 3.0 && detailed.getFinalConfidence() > 0.8) {
                telegramService.sendMessage(String.format(
                        "🎯 EXTREME TREND SIGNAL (3σ Event)\n" +
                                "Direction: %s\n" +
                                "Z-Score: %.2f\n" +
                                "Confidence: %d%%\n" +
                                "ML: %s\n" +
                                "Regime: %s\n" +
                                "Analysis: %s",
                        detailed.getFinalTrend(),
                        detailed.getZScore(),
                        (int)(detailed.getFinalConfidence() * 100),
                        detailed.getMlValidationScore() > 0.5 ? "Confirmed ✅" : "Caution ⚠️",
                        detailed.getMarketRegime(),
                        detailed.getReasoning()
                ));
            }

            // Alert for strong ML confirmation
            if (detailed.getMlValidationScore() > 0.8 && detailed.getFinalConfidence() > 0.75) {
                log.info("[TREND] 🤖 Strong ML confirmation for {} trend", detailed.getFinalTrend());
            }

            // Alert for regime changes
            if ("HIGH_VOLATILITY".equals(detailed.getMarketRegime()) ||
                    "CHOPPY".equals(detailed.getMarketRegime())) {
                log.warn("[TREND] ⚠️ Difficult market regime: {}", detailed.getMarketRegime());
            }

        } catch (Exception e) {
            log.error("Error in trend alerts: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60000) // Every minute
    public void logDetailedTrendAnalysis() {
        if (!safetyService.isTradingEnabled()) {
            return;
        }

        try {
            EnhancedTrendService.EnhancedTrendResult detailed =
                    enhancedTrendService.getTrendDetailed("QQQ");

            log.debug("[TREND-DETAIL] Components - Momentum: {:.2f}, Volume: {:.2f}, " +
                            "Microstructure: {:.2f}, Regime: {:.2f}, ML: {:.2f}",
                    detailed.getMomentumScore(),
                    detailed.getVolumeScore(),
                    detailed.getMicrostructureScore(),
                    detailed.getRegimeScore(),
                    detailed.getMlValidationScore()
            );

            if (detailed.getMlProbabilities() != null) {
                log.debug("[TREND-DETAIL] ML Probabilities - UP: {}%, NEUTRAL: {}%, DOWN: {}%",
                        (int)(detailed.getMlProbabilities().getOrDefault("UP", 0.0) * 100),
                        (int)(detailed.getMlProbabilities().getOrDefault("NEUTRAL", 0.0) * 100),
                        (int)(detailed.getMlProbabilities().getOrDefault("DOWN", 0.0) * 100)
                );
            }

        } catch (Exception e) {
            log.error("Error in detailed trend logging: {}", e.getMessage());
        }
    }

    private double analyzeQQQComponentsCached() {
        double componentScore = 0.0;

        try {
            Map<String, Double> componentWeights = new HashMap<>();
            componentWeights.put("NVDA", 2.0);
            componentWeights.put("MSFT", 2.0);
            componentWeights.put("AAPL", 1.0);
            componentWeights.put("AMZN", 1.0);
            componentWeights.put("TSLA", 0.8);
            componentWeights.put("AVGO", 0.8);

            boolean needsRefresh = false;
            for (String symbol : componentWeights.keySet()) {
                CachedQuote cached = componentQuoteCache.get(symbol);
                if (cached == null || cached.isExpired()) {
                    needsRefresh = true;
                    break;
                }
            }

            if (needsRefresh) {
                String symbols = String.join(",", componentWeights.keySet());
                log.debug("[TREND] Refreshing component quotes cache for: {}", symbols);

                Map<String, Quote> freshQuotes = tradierService.getMultipleQuotes(symbols);

                for (Map.Entry<String, Quote> entry : freshQuotes.entrySet()) {
                    componentQuoteCache.put(entry.getKey(), new CachedQuote(entry.getValue()));
                }
            }

            double totalWeight = 0.0;
            double weightedTrend = 0.0;

            for (Map.Entry<String, Double> entry : componentWeights.entrySet()) {
                String symbol = entry.getKey();
                Double weight = entry.getValue();

                CachedQuote cached = componentQuoteCache.get(symbol);
                if (cached != null && cached.quote != null) {
                    Quote componentQuote = cached.quote;

                    if (componentQuote.getLast() != null && componentQuote.getPreviousClose() != null) {
                        BigDecimal dayChange = componentQuote.getLast()
                                .subtract(componentQuote.getPreviousClose())
                                .divide(componentQuote.getPreviousClose(), 4, BigDecimal.ROUND_HALF_UP);

                        double symbolScore = 0.0;
                        if (dayChange.compareTo(BigDecimal.valueOf(0.01)) > 0) {
                            symbolScore = 1.0;
                        } else if (dayChange.compareTo(BigDecimal.valueOf(0.005)) > 0) {
                            symbolScore = 0.5;
                        } else if (dayChange.compareTo(BigDecimal.valueOf(-0.005)) < 0) {
                            symbolScore = -0.5;
                        } else if (dayChange.compareTo(BigDecimal.valueOf(-0.01)) < 0) {
                            symbolScore = -1.0;
                        }

                        weightedTrend += symbolScore * weight;
                        totalWeight += weight;

                        if (Math.abs(symbolScore) > 0) {
                            log.debug("[TREND] {} change: {}% (score: {}, weight: {})",
                                    symbol, String.format("%.2f", dayChange.multiply(BigDecimal.valueOf(100)).doubleValue()),
                                    symbolScore, weight);
                        }
                    }
                }
            }

            if (totalWeight > 0) {
                componentScore = weightedTrend / totalWeight * 2.0;
                log.info("[TREND] Component analysis score: {} (NVDA/MSFT weighted, cached)",
                        String.format("%.2f", componentScore));
            }

        } catch (Exception e) {
            log.error("Error analyzing QQQ components: {}", e.getMessage());
        }

        return componentScore;
    }

    private double getVolatilityAdjustment() {
        try {
            if (cachedVix == null || vixCacheTime == null ||
                    LocalDateTime.now().isAfter(vixCacheTime.plusMinutes(5))) {

                QuoteResponse vixResponse = tradierService.getQuote("VIX");
                if (vixResponse != null && vixResponse.getQuote() != null) {
                    cachedVix = vixResponse.getQuote().getLast();
                    vixCacheTime = LocalDateTime.now();
                }
            }

            if (cachedVix != null) {
                double vix = cachedVix.doubleValue();

                if (vix < 15) {
                    return 0.8;
                } else if (vix > 25) {
                    return 1.3;
                } else if (vix > 30) {
                    return 1.5;
                }
            }
        } catch (Exception e) {
            log.debug("VIX fetch failed, using default adjustment: {}", e.getMessage());
        }

        return 1.0;
    }

    private MarketInternals getMarketInternals() {
        try {
            MarketInternals internals = new MarketInternals();

            QuoteResponse qqqResponse = tradierService.getQuote("QQQ");
            if (qqqResponse != null && qqqResponse.getQuote() != null) {
                Quote qqqQuote = qqqResponse.getQuote();

                if (qqqQuote.getBidSize() != null && qqqQuote.getAskSize() != null) {
                    double breadth = (double) qqqQuote.getBidSize() /
                            (qqqQuote.getBidSize() + qqqQuote.getAskSize());

                    if (breadth > 0.6) {
                        internals.setTrendBias(0.5);
                    } else if (breadth < 0.4) {
                        internals.setTrendBias(-0.5);
                    } else {
                        internals.setTrendBias(0.0);
                    }
                }
            }

            return internals;
        } catch (Exception e) {
            log.debug("Market internals fetch failed: {}", e.getMessage());
            return null;
        }
    }

    private static class MarketInternals {
        private double trendBias = 0.0;

        public double getTrendBias() {
            return trendBias;
        }

        public void setTrendBias(double bias) {
            this.trendBias = bias;
        }
    }

    private BigDecimal calculateCurrentVWAP() {
        try {
            List<MarketData> todaysData = marketDataRepository
                    .findBySymbolAndTimestampAfterOrderByTimestampAsc("QQQ",
                            LocalDateTime.now().withHour(9).withMinute(30));

            if (todaysData.isEmpty()) return null;

            BigDecimal cumulativePriceVolume = BigDecimal.ZERO;
            BigDecimal cumulativeVolume = BigDecimal.ZERO;

            for (MarketData data : todaysData) {
                BigDecimal typicalPrice = data.getPrice();
                long volume = data.getVolume() != null ? data.getVolume() : 0;

                cumulativePriceVolume = cumulativePriceVolume
                        .add(typicalPrice.multiply(BigDecimal.valueOf(volume)));
                cumulativeVolume = cumulativeVolume.add(BigDecimal.valueOf(volume));
            }

            return cumulativeVolume.compareTo(BigDecimal.ZERO) > 0 ?
                    cumulativePriceVolume.divide(cumulativeVolume, 2, BigDecimal.ROUND_HALF_UP) : null;

        } catch (Exception e) {
            log.debug("VWAP calculation error: {}", e.getMessage());
            return null;
        }
    }

    private double calculateVolumeRatio(List<MarketData> recentData) {
        if (recentData.size() < 10) return 1.0;

        long recentVolume = recentData.stream()
                .skip(Math.max(0, recentData.size() - 5))
                .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                .sum();

        long previousVolume = recentData.stream()
                .skip(Math.max(0, recentData.size() - 10))
                .limit(5)
                .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                .sum();

        return previousVolume > 0 ? (double) recentVolume / previousVolume : 1.0;
    }

    private BigDecimal getPriceMinutesAgo(List<MarketData> recentData, int minutes) {
        if (recentData == null || recentData.isEmpty()) return null;

        LocalDateTime targetTime = LocalDateTime.now().minusMinutes(minutes);

        for (MarketData data : recentData) {
            if (data.getTimestamp().isBefore(targetTime) ||
                    data.getTimestamp().isEqual(targetTime)) {
                return data.getPrice();
            }
        }

        return null;
    }

    private long getAverageVolumeForQQQ() {
        return 50000000L;
    }



//    @Scheduled(cron = "0 */5 * * * *") // Every 5 minutes
//    public void cleanupExpiredSignals() {
//        List<Signal> expiredSignals = signalRepository.findExpiredPendingSignals(LocalDateTime.now());
//
//        if (!expiredSignals.isEmpty()) {
//            log.info("[CLEANUP] Marking {} expired signals", expiredSignals.size());
//
//            for (Signal signal : expiredSignals) {
//                signal.setStatus("EXPIRED");
//                signal.setExecuted(true);
//                signal.setExecutionNotes("Auto-expired");
//            }
//
//            signalRepository.saveAll(expiredSignals);
//        }
//    }
}