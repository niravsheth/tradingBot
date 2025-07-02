package com.tradingBot.service;

import com.tradingBot.entity.*;
import com.tradingBot.ml.SignalMLScorer;
import com.tradingBot.model.*;
import com.tradingBot.repository.*;
import com.tradingBot.service.PositionSyncService.BrokerPosition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradingService {
    private final TradierService tradierService;
    private final TelegramService telegramService;
    private final TradeRepository tradeRepository;
    private final SignalMLScorer signalMLScorer;

    private final SignalRepository signalRepository;
    private final SafetyService safetyService;
    private final MarketDataRepository marketDataRepository;
    private final CapitalAllocationService capitalAllocationService;
    private final PositionSyncService positionSyncService;

    @Value("${trading.max-position-size}")
    private BigDecimal maxPositionSize;

    @Value("${trading.risk-percentage}")
    private Double riskPercentage;

    @Value("${safety.paper-mode:true}")
    private boolean paperMode;

    @Transactional
    public void executeSignals() {
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[v62][{}] === SIGNAL EXECUTION START ===", executionId);

        // Get fresh signals prioritized by confidence and age
        List<Signal> signals = signalRepository
                .findFreshUnexecutedSignalsPrioritized(LocalDateTime.now());

        log.info("[v62][{}] Found {} fresh unexecuted signals", executionId, signals.size());

        if (!signals.isEmpty()) {
            // Log signal details
            for (Signal signal : signals) {
                long secondsToExpiration = Duration.between(
                        LocalDateTime.now(), signal.getExpirationTime()).getSeconds();
                log.info("[v62][{}] Pending: {} - Confidence: {}%, Expires in {}s",
                        executionId, signal.getOptionSymbol(),
                        (int)(signal.getConfidence() * 100), secondsToExpiration);
            }
        }

        // Execute high priority signals first
        List<Signal> highPrioritySignals = signals.stream()
                .filter(s -> s.getConfidence() >= 0.85 ||
                        s.getStrategy().contains("UNUSUAL_FLOW"))
                .collect(Collectors.toList());

        if (!highPrioritySignals.isEmpty()) {
            log.info("[v62][{}] Executing {} HIGH PRIORITY signals first",
                    executionId, highPrioritySignals.size());
        }

        // Process high priority first, then others
        for (Signal signal : highPrioritySignals) {
            executeSignal(signal, executionId);
        }

        // Then process remaining signals
        for (Signal signal : signals) {
            if (!highPrioritySignals.contains(signal)) {
                executeSignal(signal, executionId);
            }
        }
    }

    private void executeSignal(Signal signal, String executionId) {
        log.info("[v62][{}] Executing signal for {} with confidence {}%",
                executionId, signal.getOptionSymbol(), (int)(signal.getConfidence() * 100));
        if (signalMLScorer != null) {
            double mlScore = signalMLScorer.scoreSignal(signal);
            log.info("[v62][{}] ML Score: {} (original confidence: {}%)",
                    executionId, String.format("%.3f", mlScore),
                    (int)(signal.getConfidence() * 100));

            // Adjust confidence based on ML score
            double adjustedConfidence = (signal.getConfidence() * 0.7) + (mlScore * 0.3);
            signal.setConfidence(adjustedConfidence);

            // Reject if ML score is too low
            if (mlScore < 0.5) {
                log.warn("[v62][{}] ML Score {} too low - rejecting signal",
                        executionId, String.format("%.3f", mlScore));
                signal.setStatus("ML_REJECTED");
                signal.setExecuted(true);
                signal.setExecutionNotes(String.format("ML score %.3f below threshold", mlScore));
                signalRepository.save(signal);
                return;
            }
        }
        // Check signal age
        LocalDateTime now = LocalDateTime.now();

        // Check if we're in late session
        LocalTime currentTime = now.toLocalTime();
        if (currentTime.isAfter(LocalTime.of(15, 30)) && signal.getConfidence() < 0.90) {
            log.warn("[v62][{}] LATE SESSION - Signal confidence {}% < 90% required - SKIPPING",
                    executionId, (int)(signal.getConfidence() * 100));
            signal.setStatus("BLOCKED_LATE_SESSION");
            signal.setExecuted(true);
            signal.setExecutionNotes("Late session - requires 90%+ confidence");
            signalRepository.save(signal);
            return;
        }

        long secondsToExpiration = Duration.between(now, signal.getExpirationTime()).getSeconds();

        // Hard expiration check
        if (secondsToExpiration <= 0) {
            log.warn("[v62][{}] Signal #{} EXPIRED - {} seconds ago",
                    executionId, signal.getId(), -secondsToExpiration);
            signal.setStatus("EXPIRED");
            signal.setExecuted(true);
            signal.setExecutionNotes("Expired before execution");
            signalRepository.save(signal);
            return;
        }

        // URGENT execution if near expiration
        if (secondsToExpiration < 30) {
            log.warn("[v62][{}] URGENT - Signal expires in {} seconds!",
                    executionId, secondsToExpiration);
        }

        // Final trend check - NEVER trade in neutral
        if ("NEUTRAL".equals(signal.getMarketTrend())) {
            log.error("[v62][{}] Signal has NEUTRAL trend - BLOCKING EXECUTION", executionId);
            signal.setStatus("BLOCKED_NEUTRAL");
            signal.setExecuted(true);
            signal.setExecutionNotes("Neutral market - no trades allowed");
            signalRepository.save(signal);
            return;
        }

        // Get fresh quote
        QuoteResponse quoteResponse = tradierService.getQuote(signal.getOptionSymbol());
        if (quoteResponse == null || quoteResponse.getQuote() == null) {
            log.error("[v62][{}] Failed to get quote for {}", executionId, signal.getOptionSymbol());
            signal.setStatus("FAILED");
            signal.setExecuted(true);
            signal.setExecutionNotes("Failed to get quote");
            signalRepository.save(signal);
            return;
        }

        Quote quote = quoteResponse.getQuote();
        BigDecimal currentPrice = quote.getLast();

        // Check if option price moved too much (15% max for 0DTE)
        if (signal.getOriginalOptionPrice() != null &&
                signal.getOriginalOptionPrice().compareTo(BigDecimal.ZERO) > 0) {

            BigDecimal priceChange = currentPrice.subtract(signal.getOriginalOptionPrice())
                    .divide(signal.getOriginalOptionPrice(), 4, RoundingMode.HALF_UP).abs();

            if (priceChange.compareTo(BigDecimal.valueOf(0.15)) > 0) {
                log.warn("[v62][{}] Option price moved {}% - too much movement",
                        executionId, priceChange.multiply(BigDecimal.valueOf(100)));
                signal.setStatus("PRICE_MOVED");
                signal.setExecuted(true);
                signal.setExecutionNotes(String.format("Price moved %.1f%%",
                        priceChange.multiply(BigDecimal.valueOf(100)).doubleValue()));
                signalRepository.save(signal);
                return;
            }
        }

        // Validate underlying hasn't moved too much
        if (!validateSignalStillValid(signal, executionId)) {
            signal.setStatus("INVALIDATED");
            signal.setExecuted(true);
            signal.setExecutionNotes("Market conditions changed");
            signalRepository.save(signal);
            return;
        }

        // All checks passed - proceed with execution
        log.info("[v62][{}] All checks passed - proceeding with execution", executionId);

        // Calculate position size
        BigDecimal allocatedCapital = capitalAllocationService
                .calculatePositionSize(signal.getConfidence(), currentPrice);

        if (allocatedCapital.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[v62][{}] No capital allocated", executionId);
            return;
        }

        int quantity = capitalAllocationService.calculateContracts(allocatedCapital, currentPrice);
        if (quantity == 0) {
            log.warn("[v62][{}] Cannot afford even 1 contract at ${}", executionId, currentPrice);
            return;
        }

        // Execute trade
        if (paperMode) {
            executePaperTrade(signal, currentPrice, quantity, executionId);
        } else {
            executeRealTrade(signal, currentPrice, quantity, executionId);
        }
    }

    private boolean validateSignalStillValid(Signal signal, String executionId) {
        // Special handling for scalp trades - they need immediate execution
        if (signal.getStrategy() != null && signal.getStrategy().contains("SCALP")) {
            log.info("[v62][{}] Scalp trade - skipping price validation for immediate execution", executionId);
            return true;
        }

        // Re-check current price vs signal assumptions for the UNDERLYING symbol (QQQ), not the option
        QuoteResponse quote = tradierService.getQuote(signal.getSymbol()); // Fetch QQQ price, not optionSymbol
        if (quote == null || quote.getQuote() == null) {
            log.warn("[v62][{}] Failed to fetch current price for underlying symbol {}", executionId, signal.getSymbol());
            return false;
        }
        BigDecimal currentPrice = quote.getQuote().getLast();
        BigDecimal originalPrice = signal.getEntryAssumptionPrice();

        log.info("[v62][{}] Validating signal - Original Price: ${}, Current Price: ${}",
                executionId, originalPrice, currentPrice);

        if (originalPrice == null || originalPrice.compareTo(BigDecimal.ZERO) == 0) {
            // If we don't have a valid original price, can't validate accurately
            log.warn("[v62][{}] Original price not set or zero, skipping price validation", executionId);
            return true;
        }

        // Check if price moved too much (>2% for example)
        BigDecimal priceChange = currentPrice.subtract(originalPrice).abs();
        BigDecimal priceChangePercent = BigDecimal.ZERO;
        if (originalPrice.compareTo(BigDecimal.ZERO) > 0) {
            priceChangePercent = priceChange.divide(originalPrice, 4, RoundingMode.HALF_UP);
        }
        log.info("[v62][{}] Price change: ${} ({}%)",
                executionId, priceChange, priceChangePercent.multiply(BigDecimal.valueOf(100)));

        if (priceChangePercent.compareTo(BigDecimal.valueOf(0.02)) > 0) {
            log.warn("[v62][{}] Price moved {}% since signal generation - invalidating",
                    executionId, priceChangePercent.multiply(BigDecimal.valueOf(100)));
            return false;
        }
        return true;
    }

    private void executePaperTrade(Signal signal, BigDecimal price, int quantity, String executionId) {
        log.info("[v62][{}] Creating paper trade record", executionId);

        Trade trade = new Trade();
        trade.setSymbol(signal.getSymbol());
        trade.setOptionSymbol("PAPER_" + signal.getOptionSymbol());
        trade.setType(signal.getOptionSymbol().contains("C") ? "CALL" : "PUT");
        trade.setAction(signal.getSignalType());
        trade.setQuantity(quantity);
        trade.setEntryPrice(price);
        trade.setStatus("OPEN");
        trade.setEntryTime(LocalDateTime.now());
        trade.setStrategy(signal.getStrategy());

        // FIXED: Copy exit targets from signal
        trade.setTargetPrice(signal.getTargetPrice());
        trade.setStopLoss(signal.getStopLoss());
        trade.setSignalId(signal.getId()); // Track signal ID

        tradeRepository.save(trade);
        log.info("[v62][{}] Paper trade saved - ID: {}", executionId, trade.getId());

        signal.setExecuted(true);
        signal.setStatus("EXECUTED");
        signal.setTradeId(trade.getId()); // Link signal to trade
        signalRepository.save(signal);

        BigDecimal positionValue = price.multiply(BigDecimal.valueOf(quantity * 100));

        String message = String.format(
                "📝 <b>PAPER TRADE OPENED</b> [%s]\n" +
                        "Option: %s\n" +
                        "Action: %s\n" +
                        "Strategy: %s\n" +
                        "Confidence: %d%%\n" +
                        "Contracts: %d\n" +
                        "Price: $%.2f\n" +
                        "Position Value: $%.2f\n" +
                        "Target: $%.2f | Stop: $%.2f",
                executionId,
                signal.getOptionSymbol(),
                signal.getSignalType(),
                signal.getStrategy(),
                (int)(signal.getConfidence() * 100),
                quantity,
                price,
                positionValue,
                signal.getTargetPrice(),
                signal.getStopLoss());

        telegramService.sendMessage(message);
        log.info("[v62][{}] Paper trade execution complete", executionId);
    }

    private void executeRealTrade(Signal signal, BigDecimal currentPrice, int quantity, String executionId) {
        log.info("[v62][{}] Creating real order for {} contracts of {}",
                executionId, quantity, signal.getOptionSymbol());
        OrderRequest orderRequest = new OrderRequest();
        // CRITICAL: For options, use the setupForOption method
        orderRequest.setupForOption(signal.getOptionSymbol());
        // Explicitly set symbol as a fallback to ensure it's not null
        try {
            // Assuming OrderRequest has a setSymbol method; adjust if different
            orderRequest.setSymbol(signal.getOptionSymbol());
            log.debug("[v62][{}] Explicitly set symbol to {} on OrderRequest", executionId, signal.getOptionSymbol());
        } catch (Exception e) {
            log.warn("[v62][{}] Failed to explicitly set symbol on OrderRequest: {}", executionId, e.getMessage());
        }
        orderRequest.setQuantity(quantity);
        // FIXED: Use proper option side values
        // For opening positions:
        // - BUY signal -> buy_to_open
        // - SELL signal -> sell_to_open
        String side = "buy_to_open";
        orderRequest.setSide(side);
        orderRequest.setType("market");
        orderRequest.setDuration("day");
        log.info("[v62][{}] Placing order via Tradier API - Symbol: {}, Side: {}, Qty: {}",
                executionId, signal.getOptionSymbol(), orderRequest.getSide(), quantity);
        OrderResponse orderResponse = tradierService.placeOrder(orderRequest);
        if (orderResponse != null && (orderResponse.getStatus().equals("ok") || orderResponse.getStatus().equals("filled"))) {
            log.info("[v62][{}] Order FILLED - Order ID: {}", executionId, orderResponse.getId());
            Trade trade = new Trade();
            trade.setSymbol(signal.getSymbol());
            trade.setOptionSymbol(signal.getOptionSymbol());
            trade.setType(signal.getOptionSymbol().contains("C") ? "CALL" : "PUT");
            trade.setAction(signal.getSignalType());
            trade.setQuantity(quantity);
            trade.setEntryPrice(orderResponse.getAvgFillPrice() != null ?
                    orderResponse.getAvgFillPrice() : currentPrice);
            trade.setStatus("OPEN");
            trade.setEntryTime(LocalDateTime.now());
            trade.setStrategy(signal.getStrategy());

            // FIXED: Copy exit targets from signal
            trade.setTargetPrice(signal.getTargetPrice());
            trade.setStopLoss(signal.getStopLoss());
            trade.setSignalId(signal.getId()); // Track signal ID
            trade.setOrderId(orderResponse.getId()); // Track order ID

            tradeRepository.save(trade);
            log.info("[v62][{}] Trade record saved - Trade ID: {}", executionId, trade.getId());

            signal.setExecuted(true);
            signal.setStatus("EXECUTED");
            signal.setTradeId(trade.getId()); // Link signal to trade
            signalRepository.save(signal);

            // REFRESH POSITIONS AFTER TRADE
            positionSyncService.refreshPositions();

            telegramService.notifyTrade(trade);
            log.info("[v62][{}] Real trade execution complete", executionId);
        } else {
            log.error("[v62][{}] Order failed or not filled - Status: {}",
                    executionId, orderResponse != null ? orderResponse.getStatus() : "null");
            // Order failed
            signal.setStatus("FAILED");
            signal.setExecuted(true); // Don't retry
            signal.setReason(signal.getReason() + " - Order failed: " +
                    (orderResponse != null ? orderResponse.getStatus() : "null response"));
            signalRepository.save(signal);
        }
    }

    @Transactional
    public void checkOpenPositions() {
        String checkId = UUID.randomUUID().toString().substring(0, 8);
        log.debug("[v62][{}] Checking open positions", checkId);

        // ALWAYS sync with broker first
        positionSyncService.refreshPositions();

        // Get REAL positions from broker
        Map<String, BrokerPosition> brokerPositions =
                positionSyncService.getCurrentPositions(false);

        // Get DB trades for metadata (stops, targets, strategy)
        List<Trade> dbTrades = tradeRepository.findByStatusAndSymbol("OPEN", "QQQ");

        log.info("[v62][{}] Found {} broker positions, {} DB trades",
                checkId, brokerPositions.size(), dbTrades.size());

        // Process each broker position
        for (Map.Entry<String, BrokerPosition> entry : brokerPositions.entrySet()) {
            String optionSymbol = entry.getKey();
            BrokerPosition brokerPos = entry.getValue();

            // Skip if not an option position
            if (!optionSymbol.contains("C") && !optionSymbol.contains("P")) {
                continue;
            }

            // Find matching DB trade
            Trade dbTrade = dbTrades.stream()
                    .filter(t -> t.getOptionSymbol().equals(optionSymbol) ||
                            t.getOptionSymbol().equals("PAPER_" + optionSymbol))
                    .findFirst()
                    .orElse(null);

            if (dbTrade != null) {
                // Use DB metadata with broker prices
                checkPositionWithRealPrice(brokerPos, dbTrade, checkId);
            } else {
                // No DB record - create one or use conservative stops
                log.warn("[v62][{}] No DB record for broker position: {}", checkId, optionSymbol);
                checkBrokerOnlyPosition(brokerPos, checkId);
            }
        }

        // Check paper trades separately
        for (Trade trade : dbTrades) {
            if (trade.getOptionSymbol().startsWith("PAPER_")) {
                checkPaperPosition(trade, checkId);
            }
        }
    }

    private void checkBrokerOnlyPosition(BrokerPosition brokerPos, String checkId) {
        log.info("[v62][{}] Checking broker position without DB metadata: {}",
                checkId, brokerPos.getSymbol());

        try {
            // Get fresh quote
            QuoteResponse quoteResponse = tradierService.getQuote(brokerPos.getSymbol());
            if (quoteResponse == null || quoteResponse.getQuote() == null) {
                log.error("[v62][{}] Failed to get quote for {}", checkId, brokerPos.getSymbol());
                return;
            }

            Quote quote = quoteResponse.getQuote();
            BigDecimal currentBid = quote.getBid();
            BigDecimal currentPrice = currentBid != null && currentBid.compareTo(BigDecimal.ZERO) > 0 ?
                    currentBid : quote.getLast();

            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[v62][{}] Invalid price for {}", checkId, brokerPos.getSymbol());
                return;
            }

            // Update broker position with current price
            brokerPos.setCurrentPrice(currentPrice);

            // Recalculate P&L
            BigDecimal pnl = currentPrice.subtract(brokerPos.getAvgCost())
                    .multiply(BigDecimal.valueOf(brokerPos.getAbsoluteQuantity() * 100));
            BigDecimal pnlPercent = pnl.divide(
                    brokerPos.getAvgCost().multiply(BigDecimal.valueOf(brokerPos.getAbsoluteQuantity() * 100)),
                    4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

            log.info("[v62][{}] {} - Entry: ${}, Current: ${}, P&L: ${} ({}%)",
                    checkId, brokerPos.getSymbol(), brokerPos.getAvgCost(),
                    currentPrice, pnl, pnlPercent);

            // Conservative exit rules without metadata
            boolean shouldClose = false;
            String reason = "";

            // Exit if down 40% (conservative for untracked positions)
            if (pnlPercent.compareTo(BigDecimal.valueOf(-40)) <= 0) {
                shouldClose = true;
                reason = "40% loss limit (untracked)";
            }
            // Exit if up 60%
            else if (pnlPercent.compareTo(BigDecimal.valueOf(60)) >= 0) {
                shouldClose = true;
                reason = "60% profit target (untracked)";
            }
            // End of day check
            else if (LocalTime.now().isAfter(LocalTime.of(15, 45))) {
                shouldClose = true;
                reason = "End of day exit (untracked)";
            }

            if (shouldClose) {
                // Create minimal trade record for closing
                Trade trade = new Trade();
                trade.setSymbol("QQQ");
                trade.setOptionSymbol(brokerPos.getSymbol());
                trade.setType(brokerPos.getSymbol().contains("C") ? "CALL" : "PUT");
                trade.setAction("BUY");
                trade.setQuantity(brokerPos.getAbsoluteQuantity());
                trade.setEntryPrice(brokerPos.getAvgCost());
                trade.setStatus("OPEN");
                trade.setStrategy("UNTRACKED_POSITION");

                executePositionClose(trade, brokerPos, currentPrice, reason, checkId);
            }

        } catch (Exception e) {
            log.error("[v62][{}] Error checking broker-only position: {}",
                    checkId, e.getMessage());
        }
    }

    private void checkPositionWithRealPrice(BrokerPosition brokerPos,
                                            Trade dbTrade, String checkId) {
        try {
            // GET FRESH QUOTE FROM TRADIER
            String optionSymbol = brokerPos.getSymbol();
            QuoteResponse quoteResponse = tradierService.getQuote(optionSymbol);

            if (quoteResponse == null || quoteResponse.getQuote() == null) {
                log.error("[v62][{}] Failed to get quote for {}", checkId, optionSymbol);
                return;
            }

            Quote quote = quoteResponse.getQuote();
            BigDecimal currentBid = quote.getBid();
            BigDecimal currentAsk = quote.getAsk();
            BigDecimal currentMid = currentBid.add(currentAsk).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            BigDecimal currentLast = quote.getLast();

            // Use the most conservative price for stop loss checks
            BigDecimal currentPrice = currentBid; // Use BID for stop loss (worst case)

            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                currentPrice = currentLast;
            }

            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[v62][{}] Invalid price for {} - Bid: ${}, Last: ${}",
                        checkId, optionSymbol, currentBid, currentLast);
                return;
            }

            // Update current price in DB
            dbTrade.setCurrentPrice(currentPrice);

            // Calculate P&L using broker's position data
            BigDecimal entryPrice = brokerPos.getAvgCost(); // Use broker's average cost
            BigDecimal pnl = currentPrice.subtract(entryPrice)
                    .multiply(BigDecimal.valueOf(Math.abs(brokerPos.getQuantity()) * 100));

            BigDecimal pnlPercent = currentPrice.subtract(entryPrice)
                    .divide(entryPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            log.info("[v62][{}] {} - Entry: ${} → Bid: ${} / Mid: ${} / Ask: ${} | P&L: ${} ({}%)",
                    checkId, optionSymbol, entryPrice, currentBid, currentMid, currentAsk,
                    pnl, pnlPercent);

            // CHECK STOPS AND TARGETS
            boolean shouldClose = false;
            String closeReason = "";

            // Stop Loss Check - Use BID price
            if (dbTrade.getStopLoss() != null && currentBid.compareTo(dbTrade.getStopLoss()) <= 0) {
                shouldClose = true;
                closeReason = "STOP_LOSS_HIT";
                log.warn("[v62][{}] 🛑 STOP LOSS HIT - Bid ${} <= Stop ${}",
                        checkId, currentBid, dbTrade.getStopLoss());
            }

            // Target Check - Use ASK price (conservative for targets)
            else if (dbTrade.getTarget() != null && currentAsk.compareTo(dbTrade.getTarget()) >= 0) {
                shouldClose = true;
                closeReason = "TARGET_REACHED";
                log.info("[v62][{}] 🎯 TARGET HIT - Ask ${} >= Target ${}",
                        checkId, currentAsk, dbTrade.getTarget());
            }

            // Time-based exits
            else if (LocalTime.now().isAfter(LocalTime.of(15, 50))) {
                shouldClose = true;
                closeReason = "EOD_EXIT";
                log.warn("[v62][{}] ⏰ End of day exit - 3:50 PM", checkId);
            }

            // Execute close if needed
            if (shouldClose) {
                executePositionClose(dbTrade, brokerPos, currentMid, closeReason, checkId);
            } else {
                // Update trailing stops if profitable
                if (pnlPercent.compareTo(BigDecimal.valueOf(5)) > 0) {
                    updateTrailingStopWithRealPrice(dbTrade, currentBid, entryPrice, pnlPercent);
                }

                // Save updated prices
                dbTrade.setCurrentPrice(currentPrice);
                dbTrade.setProfit(pnl);
                tradeRepository.save(dbTrade);
            }

        } catch (Exception e) {
            log.error("[v62][{}] Error checking position {}: {}",
                    checkId, brokerPos.getSymbol(), e.getMessage());
        }
    }

    private void updateTrailingStopWithRealPrice(Trade trade, BigDecimal currentBid,
                                                 BigDecimal entryPrice, BigDecimal profitPercent) {
        BigDecimal currentStop = trade.getStopLoss();
        BigDecimal newStop = null;
        String level = "";

        // CONSERVATIVE TRAILING STOPS FOR 0DTE
        if (profitPercent.compareTo(BigDecimal.valueOf(50)) >= 0) {
            // 50%+ profit: Stop at entry + 30%
            newStop = entryPrice.multiply(BigDecimal.valueOf(1.30));
            level = "50%+ profit → 30% locked";
        } else if (profitPercent.compareTo(BigDecimal.valueOf(30)) >= 0) {
            // 30%+ profit: Stop at entry + 15%
            newStop = entryPrice.multiply(BigDecimal.valueOf(1.15));
            level = "30%+ profit → 15% locked";
        } else if (profitPercent.compareTo(BigDecimal.valueOf(20)) >= 0) {
            // 20%+ profit: Stop at entry + 8%
            newStop = entryPrice.multiply(BigDecimal.valueOf(1.08));
            level = "20%+ profit → 8% locked";
        } else if (profitPercent.compareTo(BigDecimal.valueOf(10)) >= 0) {
            // 10%+ profit: Stop at breakeven
            newStop = entryPrice.multiply(BigDecimal.valueOf(1.01));
            level = "10%+ profit → breakeven";
        }

        // Only update if new stop is higher and reasonable
        if (newStop != null &&
                (currentStop == null || newStop.compareTo(currentStop) > 0) &&
                newStop.compareTo(currentBid.multiply(BigDecimal.valueOf(0.80))) < 0) { // Stop must be < 80% of current bid

            trade.setStopLoss(newStop);
            trade.setTrailingActivated(true);
            trade.setLastAdjustmentTime(LocalDateTime.now());

            log.info("[TRAILING] {} - Updated stop to ${} ({})",
                    trade.getOptionSymbol(), newStop, level);
        }
    }

    private void executePositionClose(Trade trade, BrokerPosition brokerPos,
                                      BigDecimal exitPrice, String reason, String checkId) {
        try {
            log.info("[v62][{}] EXECUTING CLOSE for {} - Reason: {}",
                    checkId, trade.getOptionSymbol(), reason);

            // Create market order to close
            OrderRequest closeOrder = new OrderRequest();
            closeOrder.setupForOption(brokerPos.getSymbol());
            closeOrder.setSymbol(brokerPos.getSymbol());
            closeOrder.setQuantity(Math.abs(brokerPos.getQuantity()));
            closeOrder.setSide("sell_to_close");
            closeOrder.setType("market");
            closeOrder.setDuration("day");

            log.info("[v62][{}] Placing MARKET close order for {} contracts",
                    checkId, closeOrder.getQuantity());

            OrderResponse response = tradierService.placeOrder(closeOrder);

            if (response != null && response.getOrder() != null) {
                // Update trade record
                trade.setExitPrice(exitPrice);
                trade.setExitTime(LocalDateTime.now());
                trade.setStatus("CLOSED");
                trade.setCloseReason(reason);
                trade.setExitReason(reason);

                // Calculate final P&L
                BigDecimal finalPnl = exitPrice.subtract(brokerPos.getAvgCost())
                        .multiply(BigDecimal.valueOf(Math.abs(brokerPos.getQuantity()) * 100));
                trade.setRealizedPnl(finalPnl);
                trade.setProfit(finalPnl);

                tradeRepository.save(trade);

                // Refresh positions
                positionSyncService.refreshPositions();

                // Send alert
                String emoji = finalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "✅" : "❌";
//                telegramService.sendMessage(String.format(
//                        "%s Position Closed: %s\n" +
//                                "Reason: %s\n" +
//                                "Entry: $%.2f → Exit: $%.2f\n" +
//                                "P&L: $%.2f (%.1f%%)\n" +
//                                "Order ID: %s",
//                        emoji, trade.getOptionSymbol(),
//                        reason,
//                        brokerPos.getAvgCost(), exitPrice,
//                        finalPnl,
//                        finalPnl.divide(brokerPos.getAvgCost()
//                                        .multiply(BigDecimal.valueOf(Math.abs(brokerPos.getQuantity()) * 100)),
//                                2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)),
//                        response.getOrder().getId()
//                ));

            } else {
                log.error("[v62][{}] Failed to place close order", checkId);
                telegramService.sendMessage("❌ Failed to close position: " + trade.getOptionSymbol());
            }

        } catch (Exception e) {
            log.error("[v62][{}] Error executing close: {}", checkId, e.getMessage());
            telegramService.sendMessage("❌ Error closing position: " + e.getMessage());
        }
    }

    private void checkPaperPosition(Trade trade, String checkId) {
        log.debug("[v62][{}] Checking paper position: {}", checkId, trade.getOptionSymbol());

        String realOptionSymbol = trade.getOptionSymbol().replace("PAPER_", "");

        // Get quote for paper position
        QuoteResponse quoteResponse = null;
        try {
            quoteResponse = tradierService.getQuote(realOptionSymbol);
        } catch (Exception e) {
            log.error("[v62][{}] Error fetching quote for {}: {}",
                    checkId, realOptionSymbol, e.getMessage());
            return;
        }

        if (quoteResponse == null || quoteResponse.getQuote() == null) {
            log.warn("[v62][{}] Failed to get quote for paper position {}", checkId, realOptionSymbol);
            return;
        }

        Quote quote = quoteResponse.getQuote();
        BigDecimal currentPrice = quote.getLast();

        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[v62][{}] Invalid current price for {}: {}",
                    checkId, realOptionSymbol, currentPrice);
            return;
        }

        BigDecimal pnl = currentPrice.subtract(trade.getEntryPrice())
                .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));

        log.info("[v62][{}] Paper position {} - Entry: ${}, Current: ${}, P&L: ${}",
                checkId, trade.getOptionSymbol(), trade.getEntryPrice(), currentPrice, pnl);

        // Check exit conditions
        boolean shouldClose = false;
        String reason = "";

        try {
            if (trade.getStopLoss() != null &&
                    currentPrice.compareTo(trade.getStopLoss()) <= 0) {
                shouldClose = true;
                reason = "Stop loss hit";
            } else if (trade.getTargetPrice() != null &&
                    currentPrice.compareTo(trade.getTargetPrice()) >= 0) {
                shouldClose = true;
                reason = "Target reached";
            } else if (LocalDateTime.now().getHour() >= 15 &&
                    LocalDateTime.now().getMinute() >= 30) {
                shouldClose = true;
                reason = "End of day exit";
            }
        } catch (Exception e) {
            log.error("[v62][{}] Error checking exit conditions for {}: {}",
                    checkId, trade.getOptionSymbol(), e.getMessage());
            return;
        }

        if (shouldClose) {
            closePaperPosition(trade, currentPrice, reason, checkId);
        }
    }

    private void closePaperPosition(Trade trade, BigDecimal exitPrice, String reason, String checkId) {
        log.info("[v62][{}] Closing paper position", checkId);

        trade.setExitPrice(exitPrice);
        trade.setExitTime(LocalDateTime.now());
        trade.setStatus("CLOSED");
        trade.setCloseReason(reason);

        BigDecimal multiplier = BigDecimal.valueOf(100);
        if (trade.getAction().equals("BUY")) {
            trade.setProfit(exitPrice.subtract(trade.getEntryPrice())
                    .multiply(BigDecimal.valueOf(trade.getQuantity()))
                    .multiply(multiplier));
        }

        trade.setRealizedPnl(trade.getProfit());

        tradeRepository.save(trade);

        String message = String.format(
                "📝 <b>PAPER TRADE CLOSED</b> [%s]\n" +
                        "Option: %s\n" +
                        "Entry: $%.2f → Exit: $%.2f\n" +
                        "Contracts: %d\n" +
                        "P&L: <b>$%.2f</b> (%s)\n" +
                        "Reason: %s",
                checkId,
                trade.getOptionSymbol().replace("PAPER_", ""),
                trade.getEntryPrice(),
                exitPrice,
                trade.getQuantity(),
                trade.getProfit(),
                trade.getProfit().compareTo(BigDecimal.ZERO) > 0 ? "✅ PROFIT" : "❌ LOSS",
                reason);

        telegramService.sendMessage(message);

        log.info("[v62][{}] Paper position closed - P&L: ${}", checkId, trade.getProfit());
    }

    @PostConstruct
    public void checkAndManageOpenPositions() {
        log.info("[RECOVERY] Initial position sync on startup");
        // Use the position sync service for initial sync
        positionSyncService.syncPositionsWithDatabase();
    }

    public void closePosition(Trade trade, BigDecimal exitPrice, String reason) {
        if (trade == null || !trade.getStatus().equals("OPEN")) {
            log.warn("Cannot close position - invalid trade or not open");
            return;
        }

        try {
            log.info("[CLOSE] Closing position {} at ${} - Reason: {}",
                    trade.getOptionSymbol(), exitPrice, reason);

            // Place closing order
            OrderRequest orderRequest = new OrderRequest();
            orderRequest.setupForOption(trade.getOptionSymbol());
            orderRequest.setSymbol(trade.getOptionSymbol());
            orderRequest.setQuantity(trade.getQuantity());
            orderRequest.setSide("sell_to_close"); // Closing a long position
            orderRequest.setType("market");
            orderRequest.setDuration("day");

            log.info("[CLOSE] Placing close order for {} contracts of {}",
                    trade.getQuantity(), trade.getOptionSymbol());

            OrderResponse orderResponse = tradierService.placeOrder(orderRequest);

            if (orderResponse != null && orderResponse.getOrder() != null) {
                // Update trade record
                trade.setExitPrice(exitPrice);
                trade.setExitTime(LocalDateTime.now());
                trade.setStatus("CLOSED");
                trade.setExitReason(reason);

                // Calculate final P/L
                BigDecimal qty = BigDecimal.valueOf(trade.getQuantity() * 100);
                BigDecimal entryValue = trade.getEntryPrice().multiply(qty);
                BigDecimal exitValue = exitPrice.multiply(qty);
                BigDecimal profitLoss = exitValue.subtract(entryValue);

                trade.setRealizedPnl(profitLoss);
                tradeRepository.save(trade);

                // Update daily P/L
                // safetyService.updateDailyPnL(profitLoss);

                // Log result
                BigDecimal profitPercent = profitLoss.divide(entryValue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

                String emoji = profitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "✅" : "❌";
                log.info("[CLOSE] {} Position closed - P/L: ${} ({}%)",
                        emoji, profitLoss, profitPercent);

                // Send notification
                telegramService.sendMessage(String.format(
                        "%s Position Closed: %s\n" +
                                "Exit: $%.2f (Entry: $%.2f)\n" +
                                "P/L: $%.2f (%.1f%%)\n" +
                                "Reason: %s",
                        emoji, trade.getOptionSymbol(),
                        exitPrice, trade.getEntryPrice(),
                        profitLoss, profitPercent,
                        reason
                ));

            } else {
                log.error("[CLOSE] Failed to place close order for {}", trade.getOptionSymbol());
                telegramService.sendMessage("⚠️ Failed to close position: " + trade.getOptionSymbol());
            }

        } catch (Exception e) {
            log.error("[CLOSE] Error closing position {}: {}", trade.getOptionSymbol(), e.getMessage(), e);
            telegramService.sendMessage("❌ Error closing position: " + trade.getOptionSymbol());
        }
    }
}