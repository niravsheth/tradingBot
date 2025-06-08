package com.tradingBot.service;

import com.tradingBot.QQQTradingBotApplication;
import com.tradingBot.entity.Trade;
import com.tradingBot.entity.MarketData;
import com.tradingBot.entity.Signal;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.model.Quote;
import com.tradingBot.repository.TradeRepository;
import com.tradingBot.repository.MarketDataRepository;
import com.tradingBot.service.SafetyService.SafetyStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
    private final CapitalAllocationService capitalAllocationService;

    @Value("${trading.symbol}")
    private String tradingSymbol;

    // Run every minute during market hours
    @Scheduled(cron = "0 * 10-22 * * MON-SUN")
    public void tradingLoop() {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        LocalTime now = LocalTime.now();

        log.info("[v62][{}] ===== TRADING LOOP START - {} =====", requestId, now);

        // Only trade between 10:00 AM and 3:30 PM ET
//        if (now.isBefore(LocalTime.of(10, 0)) || now.isAfter(LocalTime.of(15, 30))) {
//            log.info("[v62][{}] Outside trading hours - skipping", requestId);
//            return;
//        }

        try {
            // Step 1: Check if trading is enabled
            log.info("[v62][{}] Step 1: Checking if trading is enabled", requestId);
            if (!safetyService.isTradingEnabled()) {
                log.warn("[v62][{}] Trading is DISABLED via master switch", requestId);
                return;
            }
            log.info("[v62][{}] ✓ Trading is ENABLED", requestId);

            // Step 2: Check safety conditions
            log.info("[v62][{}] Step 2: Checking safety conditions", requestId);
            if (!safetyService.canTrade()) {
                log.warn("[v62][{}] Trading BLOCKED by safety checks", requestId);
                SafetyStatus status = safetyService.getStatus();
                log.warn("[v62][{}] Safety details - Today P&L: ${}, Open positions: {}/{}",
                        requestId, status.getTodayPnL(), status.getOpenPositions(), status.getMaxOpenPositions());
                return;
            }
            log.info("[v62][{}] ✓ Safety checks PASSED", requestId);

            // Step 3: Get available capital
            log.info("[v62][{}] Step 3: Calculating available capital", requestId);
            BigDecimal availableCapital = capitalAllocationService.getAvailableCapital();
            log.info("[v62][{}] Available capital: ${}", requestId, availableCapital);

            // Step 4: Log safety status every 15 minutes
            if (now.getMinute() % 15 == 0) {
                SafetyService.SafetyStatus status = safetyService.getStatus();
                log.info("[v62][{}] === PERIODIC STATUS CHECK ===", requestId);
                log.info("[v62][{}] Trading Enabled: {}", requestId, status.isTradingEnabled());
                log.info("[v62][{}] Paper Mode: {}", requestId, status.isPaperMode());
                log.info("[v62][{}] Today P&L: ${}", requestId, status.getTodayPnL());
                log.info("[v62][{}] Open Positions: {}/{}", requestId, status.getOpenPositions(), status.getMaxOpenPositions());
                log.info("[v62][{}] Available Capital: ${}", requestId, availableCapital);
                log.info("[v62][{}] ==============================", requestId);
            }

            // Step 5: Get current QQQ quote
            log.info("[v62][{}] Step 5: Fetching {} quote", requestId, tradingSymbol);
            QuoteResponse quoteResponse = tradierService.getQuote(tradingSymbol);
            if (quoteResponse != null && quoteResponse.getQuote() != null) {
                Quote quote = quoteResponse.getQuote();
                log.info("[v62][{}] {} Quote - Price: ${}, Bid: ${}, Ask: ${}, Volume: {}",
                        requestId, tradingSymbol, quote.getLast(), quote.getBid(), quote.getAsk(), quote.getVolume());
            } else {
                log.error("[v62][{}] Failed to get {} quote", requestId, tradingSymbol);
            }

            // Step 6: Analyze options for signals
            log.info("[v62][{}] Step 6: Starting option analysis for {}", requestId, tradingSymbol);
            log.info("[v62][{}] Calling strategy.analyzeOptions({})", requestId, tradingSymbol);
            List<Signal> signals = strategy.analyzeOptions(tradingSymbol);
            log.info("[v62][{}] Analysis complete - Generated {} signals", requestId, signals.size());

            if (!signals.isEmpty()) {
                log.info("[v62][{}] === SIGNALS GENERATED ===", requestId);
                for (Signal signal : signals) {
                    log.info("[v62][{}] Signal: {} {} - Strategy: {}, Confidence: {}%, Target: ${}, Stop: ${}",
                            requestId, signal.getSignalType(), signal.getOptionSymbol(),
                            signal.getStrategy(), (int)(signal.getConfidence() * 100),
                            signal.getTargetPrice(), signal.getStopLoss());
                }
                log.info("[v62][{}] ========================", requestId);
            }

            // Step 7: Execute pending signals
            log.info("[v62][{}] Step 7: Executing pending signals", requestId);
            tradingService.executeSignals();

            // Step 8: Monitor open positions
            log.info("[v62][{}] Step 8: Monitoring open positions", requestId);
            List<Trade> openTrades = tradeRepository.findByStatusAndSymbol("OPEN", tradingSymbol);
            if (!openTrades.isEmpty()) {
                log.info("[v62][{}] Monitoring {} open positions", requestId, openTrades.size());
                for (Trade trade : openTrades) {
                    log.debug("[v62][{}] Open position: {} - Entry: ${}, Qty: {}",
                            requestId, trade.getOptionSymbol(), trade.getEntryPrice(), trade.getQuantity());
                }
            }
            tradingService.checkOpenPositions();

            log.info("[v62][{}] ===== TRADING LOOP COMPLETE =====", requestId);

        } catch (Exception e) {
            log.error("[v62][{}] ERROR in trading loop: {}", requestId, e.getMessage(), e);
            telegramService.sendMessage(String.format("⚠️ Trading bot error [%s]: %s", requestId, e.getMessage()));

            // If critical error, activate emergency stop
            if (e.getMessage() != null && e.getMessage().contains("critical")) {
                log.error("[v62][{}] CRITICAL ERROR - Activating emergency stop", requestId);
                safetyService.activateEmergencyStop("Critical error: " + e.getMessage());
            }
        }
    }

    // Collect market data every minute during market hours
    @Scheduled(cron = "0 * 9-16 * * MON-FRI")
    public void collectMarketData() {
        LocalTime now = LocalTime.now();

        // Collect data from 9:30 AM to 4:00 PM ET
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

    // Daily summary at 4:00 PM ET
    @Scheduled(cron = "0 0 16 * * MON-FRI")
    public void dailySummary() {
        log.info("[v62] Generating daily summary");

        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0);
        List<Trade> todaysTrades = tradeRepository.findClosedTradesAfter(startOfDay);
        BigDecimal totalProfit = tradeRepository.calculateProfitSince(startOfDay);

        if (totalProfit == null) totalProfit = BigDecimal.ZERO;

        // Calculate statistics
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

        log.info("[v62] Daily Summary - Trades: {}, Winners: {}, Losers: {}, Total P&L: ${}, Avg Win: ${}",
                todaysTrades.size(), winners, losers, totalProfit, avgWin);

        telegramService.sendDailySummary(todaysTrades, totalProfit);

        // Log version info in daily summary
        log.info("[v62] Bot Version: {} - Date: {}", QQQTradingBotApplication.VERSION, LocalDate.now());
    }

    // Market open notification
    @Scheduled(cron = "0 30 9 * * MON-FRI")
    public void marketOpenNotification() {
        telegramService.sendMessage("🔔 Market is open! Bot will start trading at 10:00 AM ET to avoid morning reports.");
    }

    // Trading start notification
    @Scheduled(cron = "0 0 10 * * MON-SUN")
    public void tradingStartNotification() {
        telegramService.sendMessage("🚀 Trading session started! 0DTE QQQ bot is now actively scanning for opportunities.");
    }
}