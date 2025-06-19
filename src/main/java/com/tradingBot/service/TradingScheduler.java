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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
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
    
    private final CapitalAllocationService capitalAllocationService;
    private static final Map<String, CachedQuote> componentQuoteCache = new ConcurrentHashMap<>();
    private static final long COMPONENT_CACHE_TTL = 30000; // 30 seconds
    private volatile BigDecimal cachedVix = null;
    private volatile LocalDateTime vixCacheTime = null;

    @Value("${trading.symbol}")
    private String tradingSymbol;

    @Scheduled(cron = "0 * 9-16 * * MON-FRI")
    public void analyzeMarketAndGenerateSignals() {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        LocalTime now = LocalTime.now();
        log.info("[v62][{}] ===== MARKET ANALYSIS START - {} =====", requestId, now);

        try {
            // YOUR EXISTING SAFETY CHECKS HERE...

            log.info("[v62][{}] Step 6: Starting option analysis for {}", requestId, tradingSymbol);

            // ADD TREND CHECK
            String currentTrend = getQQQTrend();
            log.info("[v62][{}] Current market trend: {}", requestId, currentTrend);

            List<Signal> signals = strategy.analyzeOptions(tradingSymbol);
            log.info("[v62][{}] Analysis complete - Generated {} signals", requestId, signals.size());

            if (!signals.isEmpty()) {
                // Filter signals based on trend
                List<Signal> trendAlignedSignals = new ArrayList<>();

                for (Signal signal : signals) {
                    boolean isPut = signal.getOptionSymbol().contains("P");
                    boolean shouldTake = false;

                    if (isPut && "DOWN".equals(currentTrend)) {
                        shouldTake = true;
                        log.info("[v62][{}] PUT aligned with DOWN trend - TAKING signal", requestId);
                    } else if (!isPut && "UP".equals(currentTrend)) {
                        shouldTake = true;
                        log.info("[v62][{}] CALL aligned with UP trend - TAKING signal", requestId);
                    } else if ("NEUTRAL".equals(currentTrend) && signal.getConfidence() >= 0.7) {
                        shouldTake = true;
                        log.info("[v62][{}] NEUTRAL trend but high confidence - TAKING signal", requestId);
                    } else {
                        log.info("[v62][{}] Signal {} NOT aligned with {} trend - SKIPPING",
                                requestId, signal.getOptionSymbol(), currentTrend);
                    }

                    if (shouldTake) {
                        trendAlignedSignals.add(signal);
                    }
                }

                // Log filtered signals
                if (!trendAlignedSignals.isEmpty()) {
                    log.info("[v62][{}] === TREND-ALIGNED SIGNALS ({} of {}) ===",
                            requestId, trendAlignedSignals.size(), signals.size());
                    for (Signal signal : trendAlignedSignals) {
                        log.info("[v62][{}] Signal: {} {} - Strategy: {}, Confidence: {}%, Target: ${}, Stop: ${}",
                                requestId, signal.getSignalType(), signal.getOptionSymbol(),
                                signal.getStrategy(), (int)(signal.getConfidence() * 100),
                                signal.getTargetPrice(), signal.getStopLoss());
                    }
                    log.info("[v62][{}] ========================", requestId);

                    // Update the signals list to only include trend-aligned ones
                    signals.clear();
                    signals.addAll(trendAlignedSignals);
                } else {
                    log.info("[v62][{}] No trend-aligned signals after filtering", requestId);
                    signals.clear();
                }
            }

            log.info("[v62][{}] ===== MARKET ANALYSIS COMPLETE =====", requestId);
        } catch (Exception e) {
            // YOUR EXISTING ERROR HANDLING
        }
    }

    @Scheduled(cron = "0,15,30,45 * 9-15 * * MON-FRI")
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

    @Scheduled(cron = "0 0 16 * * MON-FRI")
    public void dailySummary() {
        log.info("[v62] Generating daily summary");
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0);
        List<Trade> todaysTrades = tradeRepository.findClosedTradesAfter(startOfDay);
        BigDecimal totalProfit = tradeRepository.calculateProfitSince(startOfDay);
        if (totalProfit == null) totalProfit = BigDecimal.ZERO;
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
        log.info("[v62] Bot Version: {} - Date: {}", QQQTradingBotApplication.VERSION, LocalDate.now());
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

            // Calculate current P&L before closing
            BigDecimal currentPrice = trade.getCurrentPrice();
            if (currentPrice == null) {
                currentPrice = tradierService.getOptionPrice(trade.getOptionSymbol());
            }

            BigDecimal pnl = currentPrice.subtract(trade.getEntryPrice())
                    .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));

            // Create closing order - using your existing OrderRequest pattern
            OrderRequest closeRequest = new OrderRequest();
            closeRequest.setSymbol(trade.getOptionSymbol());
            closeRequest.setQuantity(trade.getQuantity());
            closeRequest.setSide("sell_to_close");  // For long positions
            closeRequest.setType("MARKET");
            closeRequest.setDuration("DAY");

            // Place the order
            OrderResponse response = tradierService.placeOrder(closeRequest);

            if (response != null) {
                log.info("[CLOSE] ✅ Successfully closed {} - P&L: ${}",
                        trade.getOptionSymbol(), pnl);

                // Update trade record
                trade.setStatus("CLOSED");
                trade.setExitTime(LocalDateTime.now());
                trade.setExitPrice(currentPrice);
                trade.setProfit(pnl);

                tradeRepository.save(trade);

                // Send notification
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

    @Scheduled(cron = "*/30 * 9-16 * * MON-FRI")
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

        for (Trade trade : openTrades) {
            monitorPosition(trade);
        }
    }

    private void monitorPosition(Trade trade) {
        try {
            BigDecimal currentPrice = tradierService.getOptionPrice(trade.getOptionSymbol());

            if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("[MONITOR] Could not get price for {}", trade.getOptionSymbol());
                return;
            }

            // Update current price
            trade.setCurrentPrice(currentPrice);

            // Calculate current P&L
            BigDecimal pnl = currentPrice.subtract(trade.getEntryPrice())
                    .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));

            BigDecimal pnlPercent = pnl.divide(
                    trade.getEntryPrice().multiply(BigDecimal.valueOf(trade.getQuantity() * 100)),
                    2, BigDecimal.ROUND_HALF_UP
            ).multiply(BigDecimal.valueOf(100));

            // Get market trend
            String trend = getQQQTrend();
            boolean isPut = trade.getOptionSymbol().contains("P");

            // ⚡ CHECK TARGETS/STOPS FIRST - BEFORE ANY ADJUSTMENTS
            // Check profit target FIRST
            if (trade.getTarget() != null && currentPrice.compareTo(trade.getTarget()) >= 0) {
                log.info("[MONITOR] 🎯 TARGET HIT for {} - Current: ${} >= Target: ${}",
                        trade.getOptionSymbol(), currentPrice, trade.getTarget());
                closePosition(trade, "TARGET");
                return; // EXIT IMMEDIATELY
            }

            // Check stop loss
            if (trade.getStopLoss() != null && currentPrice.compareTo(trade.getStopLoss()) <= 0) {
                log.warn("[MONITOR] 🛑 STOP LOSS HIT for {} - Current: ${} <= Stop: ${}",
                        trade.getOptionSymbol(), currentPrice, trade.getStopLoss());
                closePosition(trade, "STOP_LOSS");
                return; // EXIT IMMEDIATELY
            }

            // ⚡ NOW APPLY DYNAMIC ADJUSTMENTS (only if position still open)
            adjustForVolatility(trade, currentPrice);
            applyPositionSpecificStrategy(trade, currentPrice);

            // Determine position alignment
            String alignment = "";
            String recommendation = "";

            if (isPut) {
                if ("DOWN".equals(trend)) {
                    alignment = "✅ ALIGNED";
                    recommendation = "HOLD/ADD";
                } else if ("UP".equals(trend)) {
                    alignment = "❌ AGAINST";
                    recommendation = "EXIT/REDUCE";
                } else {
                    alignment = "⚠️ NEUTRAL";
                    recommendation = "MONITOR";
                }
            } else { // Call
                if ("UP".equals(trend)) {
                    alignment = "✅ ALIGNED";
                    recommendation = "HOLD/ADD";
                } else if ("DOWN".equals(trend)) {
                    alignment = "❌ AGAINST";
                    recommendation = "EXIT/REDUCE";
                } else {
                    alignment = "⚠️ NEUTRAL";
                    recommendation = "MONITOR";
                }
            }

            // Enhanced logging with trend info
            String emoji = pnl.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
            String positionType = isPut ? "PUT" : "CALL";

            // Calculate distances to stops/targets
            BigDecimal stopDistance = trade.getStopLoss() != null ?
                    currentPrice.subtract(trade.getStopLoss()) : BigDecimal.ZERO;
            BigDecimal stopPercent = trade.getStopLoss() != null ?
                    stopDistance.divide(currentPrice, 2, BigDecimal.ROUND_HALF_UP).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO;

            BigDecimal targetDistance = trade.getTarget() != null ?
                    trade.getTarget().subtract(currentPrice) : BigDecimal.ZERO;
            BigDecimal targetPercent = trade.getTarget() != null ?
                    targetDistance.divide(currentPrice, 2, BigDecimal.ROUND_HALF_UP).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO;

            // Main monitoring log with trend analysis
            log.info("[MONITOR] {} {} {} | Trend: {} {} | Entry: ${} → Current: ${} | P&L: ${} ({}%) | Stop: ${} (-{}%) | Target: ${} (+{}%) | Action: {}",
                    emoji,
                    trade.getOptionSymbol(),
                    positionType,
                    trend,
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

            // Additional warning if position is against trend and losing
            if ("❌ AGAINST".equals(alignment) && pnl.compareTo(BigDecimal.ZERO) < 0) {
                log.warn("[MONITOR] ⚠️ WARNING: {} position AGAINST {} trend and LOSING ${} - Consider EXIT",
                        positionType, trend, pnl.abs());
            }

            // Opportunity alert if aligned and profitable
            if ("✅ ALIGNED".equals(alignment) && pnlPercent.compareTo(BigDecimal.valueOf(5)) > 0) {
                log.info("[MONITOR] 💚 OPPORTUNITY: {} position ALIGNED with {} trend and UP {}% - Consider ADDING",
                        positionType, trend, pnlPercent);
            }

            // Save updated values
            tradeRepository.save(trade);

        } catch (Exception e) {
            log.error("[MONITOR] Error monitoring position {}: {}",
                    trade.getOptionSymbol(), e.getMessage());
        }
    }

    private String generateExecutionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void adjustForVolatility(Trade trade, BigDecimal currentPrice) {
        try {
            // Get QQQ quote for volatility calculation
            QuoteResponse qqqResponse = tradierService.getQuote("QQQ");
            if (qqqResponse == null || qqqResponse.getQuote() == null) return;

            Quote qqqQuote = qqqResponse.getQuote();
            if (qqqQuote.getHigh() == null || qqqQuote.getLow() == null || qqqQuote.getLast() == null) return;

            // Calculate intraday volatility
            BigDecimal dayRange = qqqQuote.getHigh().subtract(qqqQuote.getLow());
            BigDecimal volatility = dayRange.divide(qqqQuote.getLast(), 4, BigDecimal.ROUND_HALF_UP);

            // High volatility = wider stops
            if (volatility.compareTo(BigDecimal.valueOf(0.01)) > 0) { // >1% range
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

    private void applyPositionSpecificStrategy(Trade trade, BigDecimal currentPrice) {
        try {
            boolean isPut = trade.getOptionSymbol().contains("P");
            String trend = getQQQTrend(); // We'll implement this

            if (isPut) {
                // Put option management
                if ("UP".equals(trend)) {
                    // Tighter stops in uptrending market for puts
                    BigDecimal tightStop = currentPrice.multiply(BigDecimal.valueOf(0.98)); // 2% stop
                    if (trade.getStopLoss() == null || tightStop.compareTo(trade.getStopLoss()) > 0) {
                        log.info("[MONITOR] 📉 PUT in UPTREND - Tightening stop to ${}", tightStop);
                        trade.setStopLoss(tightStop);
                    }
                } else if ("DOWN".equals(trend)) {
                    // Extend targets in downtrend for puts
                    if (trade.getTarget() != null) {
                        BigDecimal extendedTarget = trade.getTarget().multiply(BigDecimal.valueOf(1.2));
                        log.info("[MONITOR] 📉 PUT in DOWNTREND - Extending target to ${}", extendedTarget);
                        trade.setTarget(extendedTarget);
                    }
                }
            } else {
                // Call option management
                if ("UP".equals(trend)) {
                    // Extend targets in uptrend for calls
                    if (trade.getTarget() != null) {
                        BigDecimal extendedTarget = trade.getTarget().multiply(BigDecimal.valueOf(1.2));
                        log.info("[MONITOR] 📈 CALL in UPTREND - Extending target to ${}", extendedTarget);
                        trade.setTarget(extendedTarget);
                    }
                } else if ("DOWN".equals(trend)) {
                    // Tighter stops in downtrend for calls
                    BigDecimal tightStop = currentPrice.multiply(BigDecimal.valueOf(0.98));
                    if (trade.getStopLoss() == null || tightStop.compareTo(trade.getStopLoss()) > 0) {
                        log.info("[MONITOR] 📈 CALL in DOWNTREND - Tightening stop to ${}", tightStop);
                        trade.setStopLoss(tightStop);
                    }
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

    private String getQQQTrend() {
        try {
            QuoteResponse response = tradierService.getQuote("QQQ");
            if (response == null || response.getQuote() == null) {
                return "NEUTRAL";
            }

            Quote quote = response.getQuote();
            BigDecimal currentPrice = quote.getLast();
            BigDecimal previousClose = quote.getPreviousClose();

            if (currentPrice == null || previousClose == null) {
                return "NEUTRAL";
            }

            // Initialize trend score
            double trendScore = 0.0;

            // Get market internals for context
            MarketInternals internals = getMarketInternals();
            double volatilityAdjustment = getVolatilityAdjustment();

            // Get recent market data for better trend analysis
            List<MarketData> recentData = marketDataRepository.findRecentData("QQQ", 20); // Last 20 minutes

            int upMoves = 0;
            int downMoves = 0;

            // Method 1: Check price momentum over last few data points
            if (recentData.size() >= 5) {
                for (int i = 1; i < Math.min(recentData.size(), 10); i++) {
                    BigDecimal prevPrice = recentData.get(i-1).getPrice();
                    BigDecimal currPrice = recentData.get(i).getPrice();

                    if (currPrice.compareTo(prevPrice) > 0) {
                        upMoves++;
                    } else if (currPrice.compareTo(prevPrice) < 0) {
                        downMoves++;
                    }
                }

                // Strong trend detection
                if (upMoves > downMoves * 2) {
                    log.info("[TREND] Strong UP trend detected - Up moves: {}, Down moves: {}", upMoves, downMoves);
                    trendScore += 3.0; // Strong signal
                } else if (downMoves > upMoves * 2) {
                    log.info("[TREND] Strong DOWN trend detected - Down moves: {}, Up moves: {}", downMoves, upMoves);
                    trendScore -= 3.0; // Strong signal
                }
            }

            // Method 2: Multi-timeframe analysis with heavier weighting on recent
            BigDecimal price5MinAgo = getPriceMinutesAgo(recentData, 5);
            BigDecimal price10MinAgo = getPriceMinutesAgo(recentData, 10);
            BigDecimal price15MinAgo = getPriceMinutesAgo(recentData, 15);

            // Weight recent moves more heavily
            if (price5MinAgo != null) {
                if (currentPrice.compareTo(price5MinAgo) > 0) {
                    trendScore += 1.5; // Heavier weight for recent
                } else if (currentPrice.compareTo(price5MinAgo) < 0) {
                    trendScore -= 1.5;
                }
            }

            if (price10MinAgo != null) {
                if (currentPrice.compareTo(price10MinAgo) > 0) {
                    trendScore += 1.0;
                } else if (currentPrice.compareTo(price10MinAgo) < 0) {
                    trendScore -= 1.0;
                }
            }

            if (price15MinAgo != null) {
                if (currentPrice.compareTo(price15MinAgo) > 0) {
                    trendScore += 0.5; // Less weight for older
                } else if (currentPrice.compareTo(price15MinAgo) < 0) {
                    trendScore -= 0.5;
                }
            }

            // Method 3: Day change
            BigDecimal dayChange = currentPrice.subtract(previousClose)
                    .divide(previousClose, 4, BigDecimal.ROUND_HALF_UP);

            if (dayChange.compareTo(BigDecimal.valueOf(0.002)) > 0) { // >0.2%
                trendScore += 1.0;
            } else if (dayChange.compareTo(BigDecimal.valueOf(-0.002)) < 0) { // <-0.2%
                trendScore -= 1.0;
            }

            // NEW: Add VWAP bias
            try {
                // Get or calculate VWAP
                BigDecimal vwap = calculateCurrentVWAP(); // You'll need to implement this
                if (vwap != null && currentPrice.compareTo(vwap) > 0) {
                    trendScore += 0.5; // Slight bullish bias above VWAP
                } else if (vwap != null && currentPrice.compareTo(vwap) < 0) {
                    trendScore -= 0.5; // Slight bearish bias below VWAP
                }
            } catch (Exception e) {
                log.debug("VWAP calculation skipped: {}", e.getMessage());
            }

            // NEW: Consider volume-weighted moves
            try {
                double volumeRatio = calculateVolumeRatio(recentData);
                if (volumeRatio > 1.5) {
                    // Volume confirms direction
                    if (trendScore > 0) {
                        trendScore += 1.0; // Volume confirms uptrend
                    } else if (trendScore < 0) {
                        trendScore -= 1.0; // Volume confirms downtrend
                    }
                }
            } catch (Exception e) {
                log.debug("Volume ratio calculation skipped: {}", e.getMessage());
            }

            // NEW: Component stock analysis (Magnificent 7 weighting) - WITH CACHING
            double componentScore = analyzeQQQComponentsCached();
            trendScore += componentScore;

            // NEW: Market internals influence
            if (internals != null) {
                trendScore += internals.getTrendBias();
            }

            // Log detailed debug info
            log.info("[TREND] QQQ Trend Analysis - Current: ${}, 5min ago: ${}, 10min ago: ${}, Base Score: {}",
                    currentPrice, price5MinAgo, price10MinAgo, trendScore);

            log.info("[TREND] Debug - Up moves: {}, Down moves: {}, " +
                            "5min: {}, 10min: {}, 15min: {}, Day: {}, " +
                            "Component Score: {}, VIX adj: {}, Internals: {}, Final Score: {}",
                    upMoves, downMoves,
                    (price5MinAgo != null ? (currentPrice.compareTo(price5MinAgo) > 0 ? "+1.5" : "-1.5") : "0"),
                    (price10MinAgo != null ? (currentPrice.compareTo(price10MinAgo) > 0 ? "+1" : "-1") : "0"),
                    (price15MinAgo != null ? (currentPrice.compareTo(price15MinAgo) > 0 ? "+0.5" : "-0.5") : "0"),
                    dayChange.compareTo(BigDecimal.valueOf(0.002)) > 0 ? "+1" :
                            (dayChange.compareTo(BigDecimal.valueOf(-0.002)) < 0 ? "-1" : "0"),
                    String.format("%.2f", componentScore),
                    String.format("%.2f", volatilityAdjustment),
                    String.format("%.2f", internals != null ? internals.getTrendBias() : 0.0),
                    String.format("%.2f", trendScore));

            // Determine final trend with DYNAMIC thresholds based on volatility
            String trend;
            double upThreshold = 3.0 * volatilityAdjustment;    // Higher threshold in high volatility
            double downThreshold = -3.0 * volatilityAdjustment;

            if (trendScore >= upThreshold) {
                trend = "UP";
            } else if (trendScore <= downThreshold) {
                trend = "DOWN";
            } else {
                trend = "NEUTRAL";
            }

            log.info("[TREND] Final QQQ Trend: {} (Score: {}, Thresholds: ±{})",
                    trend, String.format("%.2f", trendScore), String.format("%.1f", upThreshold));

            return trend;

        } catch (Exception e) {
            log.error("Error determining trend: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    private double analyzeQQQComponentsCached() {
        double componentScore = 0.0;

        try {
            // Define components and their weights
            Map<String, Double> componentWeights = new HashMap<>();
            componentWeights.put("NVDA", 2.0);  // NVIDIA - high weight
            componentWeights.put("MSFT", 2.0);  // Microsoft - high weight
            componentWeights.put("AAPL", 1.0);  // Apple - normal weight
            componentWeights.put("AMZN", 1.0);  // Amazon - normal weight
            componentWeights.put("TSLA", 0.8);  // Tesla - lower weight (volatile)
            componentWeights.put("AVGO", 0.8);  // Broadcom - lower weight

            // Check if we need to refresh cache
            boolean needsRefresh = false;
            for (String symbol : componentWeights.keySet()) {
                CachedQuote cached = componentQuoteCache.get(symbol);
                if (cached == null || cached.isExpired()) {
                    needsRefresh = true;
                    break;
                }
            }

            // Refresh cache if needed with BATCH call
            if (needsRefresh) {
                String symbols = String.join(",", componentWeights.keySet());
                log.debug("[TREND] Refreshing component quotes cache for: {}", symbols);

                // BATCH API CALL - much more efficient
                Map<String, Quote> freshQuotes = tradierService.getMultipleQuotes(symbols);

                // Update cache
                for (Map.Entry<String, Quote> entry : freshQuotes.entrySet()) {
                    componentQuoteCache.put(entry.getKey(), new CachedQuote(entry.getValue()));
                }
            }

            // Calculate weighted trend from cached data
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

                        // Score based on magnitude of move
                        double symbolScore = 0.0;
                        if (dayChange.compareTo(BigDecimal.valueOf(0.01)) > 0) { // >1%
                            symbolScore = 1.0;
                        } else if (dayChange.compareTo(BigDecimal.valueOf(0.005)) > 0) { // >0.5%
                            symbolScore = 0.5;
                        } else if (dayChange.compareTo(BigDecimal.valueOf(-0.005)) < 0) { // <-0.5%
                            symbolScore = -0.5;
                        } else if (dayChange.compareTo(BigDecimal.valueOf(-0.01)) < 0) { // <-1%
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

            // Normalize by total weight
            if (totalWeight > 0) {
                componentScore = weightedTrend / totalWeight * 2.0; // Scale to ±2 range
                log.info("[TREND] Component analysis score: {} (NVDA/MSFT weighted, cached)",
                        String.format("%.2f", componentScore));
            }

        } catch (Exception e) {
            log.error("Error analyzing QQQ components: {}", e.getMessage());
        }

        return componentScore;
    }

    // NEW: Get volatility adjustment based on VIX
    private double getVolatilityAdjustment() {
        try {
            // Cache VIX for 5 minutes
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

                // Adjust thresholds based on VIX
                if (vix < 15) {
                    return 0.8;  // Lower thresholds in low volatility
                } else if (vix > 25) {
                    return 1.3;  // Higher thresholds in high volatility
                } else if (vix > 30) {
                    return 1.5;  // Much higher thresholds in extreme volatility
                }
            }
        } catch (Exception e) {
            log.debug("VIX fetch failed, using default adjustment: {}", e.getMessage());
        }

        return 1.0; // Default multiplier
    }

    // NEW: Get market internals (simplified version)
    private MarketInternals getMarketInternals() {
        try {
            MarketInternals internals = new MarketInternals();

            // Get key market indicators (you'd need to add these symbols to your data feed)
            // For now, using proxies

            // TICK proxy: Up vs Down volume in QQQ
            QuoteResponse qqqResponse = tradierService.getQuote("QQQ");
            if (qqqResponse != null && qqqResponse.getQuote() != null) {
                Quote qqqQuote = qqqResponse.getQuote();

                // Simple breadth calculation
                if (qqqQuote.getBidSize() != null && qqqQuote.getAskSize() != null) {
                    double breadth = (double) qqqQuote.getBidSize() /
                            (qqqQuote.getBidSize() + qqqQuote.getAskSize());

                    if (breadth > 0.6) {
                        internals.setTrendBias(0.5); // Bullish breadth
                    } else if (breadth < 0.4) {
                        internals.setTrendBias(-0.5); // Bearish breadth
                    } else {
                        internals.setTrendBias(0.0); // Neutral
                    }
                }
            }

            return internals;
        } catch (Exception e) {
            log.debug("Market internals fetch failed: {}", e.getMessage());
            return null;
        }
    }

    // Simple market internals class
    private static class MarketInternals {
        private double trendBias = 0.0;

        public double getTrendBias() {
            return trendBias;
        }

        public void setTrendBias(double bias) {
            this.trendBias = bias;
        }
    }

    // Helper method to calculate current VWAP
    private BigDecimal calculateCurrentVWAP() {
        // Simple implementation - get from latest technical analysis
        // You might want to cache this or calculate it more efficiently
        try {
            List<MarketData> todaysData = marketDataRepository
                    .findBySymbolAndTimestampAfterOrderByTimestampAsc("QQQ",
                            LocalDateTime.now().withHour(9).withMinute(30));

            if (todaysData.isEmpty()) return null;

            BigDecimal cumulativePriceVolume = BigDecimal.ZERO;
            BigDecimal cumulativeVolume = BigDecimal.ZERO;

            for (MarketData data : todaysData) {
                BigDecimal typicalPrice = data.getPrice(); // Simplified
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

    // Helper method to calculate volume ratio
    private double calculateVolumeRatio(List<MarketData> recentData) {
        if (recentData.size() < 10) return 1.0;

        // Get last 5 minutes volume
        long recentVolume = recentData.stream()
                .skip(Math.max(0, recentData.size() - 5))
                .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                .sum();

        // Get previous 5 minutes volume
        long previousVolume = recentData.stream()
                .skip(Math.max(0, recentData.size() - 10))
                .limit(5)
                .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                .sum();

        return previousVolume > 0 ? (double) recentVolume / previousVolume : 1.0;
    }

    // Helper method to analyze major QQQ components
    private double analyzeQQQComponents() {
        double componentScore = 0.0;

        try {
            // Define components and their weights
            // Higher weight for market leaders
            Map<String, Double> componentWeights = new HashMap<>();
            componentWeights.put("NVDA", 2.0);  // NVIDIA - high weight
            componentWeights.put("MSFT", 2.0);  // Microsoft - high weight
            componentWeights.put("AAPL", 1.0);  // Apple - normal weight
            componentWeights.put("AMZN", 1.0);  // Amazon - normal weight
            componentWeights.put("TSLA", 0.8);  // Tesla - lower weight (volatile)
            componentWeights.put("AVGO", 0.8);  // Broadcom - lower weight

            // Get quotes for all components in one call
            String symbols = String.join(",", componentWeights.keySet());
            Map<String, Quote> componentQuotes = getMultipleQuotes(symbols);

            double totalWeight = 0.0;
            double weightedTrend = 0.0;

            for (Map.Entry<String, Double> entry : componentWeights.entrySet()) {
                String symbol = entry.getKey();
                Double weight = entry.getValue();

                Quote componentQuote = componentQuotes.get(symbol);
                if (componentQuote != null && componentQuote.getLast() != null &&
                        componentQuote.getPreviousClose() != null) {

                    BigDecimal dayChange = componentQuote.getLast()
                            .subtract(componentQuote.getPreviousClose())
                            .divide(componentQuote.getPreviousClose(), 4, BigDecimal.ROUND_HALF_UP);

                    // Score based on magnitude of move
                    double symbolScore = 0.0;
                    if (dayChange.compareTo(BigDecimal.valueOf(0.01)) > 0) { // >1%
                        symbolScore = 1.0;
                    } else if (dayChange.compareTo(BigDecimal.valueOf(0.005)) > 0) { // >0.5%
                        symbolScore = 0.5;
                    } else if (dayChange.compareTo(BigDecimal.valueOf(-0.005)) < 0) { // <-0.5%
                        symbolScore = -0.5;
                    } else if (dayChange.compareTo(BigDecimal.valueOf(-0.01)) < 0) { // <-1%
                        symbolScore = -1.0;
                    }

                    weightedTrend += symbolScore * weight;
                    totalWeight += weight;

                    if (Math.abs(symbolScore) > 0) {
                        log.debug("[TREND] {} change: {}% (score: {}, weight: {})",
                                symbol, dayChange.multiply(BigDecimal.valueOf(100)),
                                symbolScore, weight);
                    }
                }
            }

            // Normalize by total weight
            if (totalWeight > 0) {
                componentScore = weightedTrend / totalWeight * 2.0; // Scale to ±2 range
                log.info("[TREND] Component analysis score: {} (NVDA/MSFT heavy weighted)",
                        String.format("%.2f", componentScore));
            }

        } catch (Exception e) {
            log.error("Error analyzing QQQ components: {}", e.getMessage());
        }

        return componentScore;
    }

    // Helper method to get multiple quotes efficiently
    private Map<String, Quote> getMultipleQuotes(String symbols) {
        Map<String, Quote> quotes = new HashMap<>();
        try {
            // This would need to be implemented in TradierService
            // For now, fall back to individual calls
            for (String symbol : symbols.split(",")) {
                QuoteResponse response = tradierService.getQuote(symbol);
                if (response != null && response.getQuote() != null) {
                    quotes.put(symbol, response.getQuote());
                }
            }
        } catch (Exception e) {
            log.error("Error fetching component quotes: {}", e.getMessage());
        }
        return quotes;
    }

    // Helper method
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

    // Placeholder method to get average volume (to be implemented based on your data)
    private long getAverageVolumeForQQQ() {
        // This should fetch historical average volume from your data repository or Tradier API
        // Placeholder value for now
        return 50000000L; // Example average volume for QQQ
    }


}
