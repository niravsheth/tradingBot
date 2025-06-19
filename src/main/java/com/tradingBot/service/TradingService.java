package com.tradingBot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingBot.entity.*;
import com.tradingBot.model.*;
import com.tradingBot.repository.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradingService {
    private final TradierService tradierService;
    private final TelegramService telegramService;
    private final TradeRepository tradeRepository;
    private final SignalRepository signalRepository;
    private final SafetyService safetyService;
    private final MarketDataRepository marketDataRepository;
    private final CapitalAllocationService capitalAllocationService;
    private final PositionSyncService positionSyncService; // NEW DEPENDENCY

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

        if (!safetyService.isTradingEnabled()) {
            log.warn("[v62][{}] Trading is disabled - cannot execute signals", executionId);
            return;
        }

        if (!safetyService.canTrade()) {
            log.warn("[v62][{}] Trading blocked by safety checks", executionId);
            return;
        }

        // Only get fresh signals that haven't expired
        List<Signal> signals = signalRepository
                .findFreshUnexecutedSignals(LocalDateTime.now().minusMinutes(5), LocalDateTime.now());

        log.info("[v62][{}] Found {} fresh unexecuted signals to process", executionId, signals.size());

        if (signals.isEmpty()) {
            log.debug("[v62][{}] No signals to execute", executionId);
            return;
        }

        BigDecimal availableCapital = capitalAllocationService.getAvailableCapital();
        log.info("[v62][{}] Available capital for trading: ${}", executionId, availableCapital);

        if (availableCapital.compareTo(new BigDecimal("0")) < 0) {
            log.warn("[v62][{}] Insufficient capital (${}) - skipping signal execution",
                    executionId, availableCapital);
            return;
        }

        for (Signal signal : signals) {
            try {
                log.info("[v62][{}] Processing signal #{} for {} (Confidence: {}%)",
                        executionId, signal.getId(), signal.getOptionSymbol(),
                        (int)(signal.getConfidence() * 100));

                executeSignal(signal, executionId);

            } catch (Exception e) {
                log.error("[v62][{}] Error executing signal #{}: {}",
                        executionId, signal.getId(), e.getMessage(), e);
                telegramService.sendMessage(String.format(
                        "⚠️ Error executing signal [%s]: %s", executionId, e.getMessage()));
            }
        }

        log.info("[v62][{}] === SIGNAL EXECUTION COMPLETE ===", executionId);
    }

    private void executeSignal(Signal signal, String executionId) {
        log.info("[v62][{}] Executing signal for {} with confidence {}%",
                executionId, signal.getOptionSymbol(), (int)(signal.getConfidence() * 100));

        // First check if signal is still valid
        boolean isScalp = signal.getStrategy() != null && signal.getStrategy().contains("SCALP");

        // For scalp trades, extend expiration slightly for execution
        if (isScalp && LocalDateTime.now().isAfter(signal.getExpirationTime().minusMinutes(2))) {
            log.info("[v62][{}] Extending scalp trade expiration for execution", executionId);
            signal.setExpirationTime(LocalDateTime.now().plusMinutes(5));
        }

        if (LocalDateTime.now().isAfter(signal.getExpirationTime())) {
            log.warn("[v62][{}] Signal #{} has expired - skipping execution", executionId, signal.getId());
            signal.setStatus("EXPIRED");
            signal.setExecuted(true); // Mark as processed
            signalRepository.save(signal);
            return;
        }

        if (!safetyService.canTrade()) {
            log.warn("[v62][{}] Safety check failed during execution", executionId);
            return;
        }

        // Re-validate market conditions
        if (!validateSignalStillValid(signal, executionId)) {
            log.warn("[v62][{}] Signal #{} no longer valid - market conditions changed", executionId, signal.getId());
            signal.setStatus("INVALIDATED");
            signal.setExecuted(true);
            signalRepository.save(signal);
            return;
        }

        log.debug("[v62][{}] Step 1: Getting quote for {}", executionId, signal.getOptionSymbol());
        QuoteResponse quoteResponse = tradierService.getQuote(signal.getOptionSymbol());
        if (quoteResponse == null || quoteResponse.getQuote() == null) {
            log.error("[v62][{}] Failed to get quote for {}", executionId, signal.getOptionSymbol());
            signal.setStatus("FAILED");
            signal.setExecuted(true);
            signal.setReason(signal.getReason() + " - Failed to get quote");
            signalRepository.save(signal);
            return;
        }

        Quote quote = quoteResponse.getQuote();
        BigDecimal currentPrice = quote.getLast();
        log.info("[v62][{}] Option {} - Price: ${}, Bid: ${}, Ask: ${}",
                executionId, signal.getOptionSymbol(), currentPrice, quote.getBid(), quote.getAsk());

        log.debug("[v62][{}] Step 2: Calculating position size based on confidence", executionId);
        BigDecimal allocatedCapital = capitalAllocationService
                .calculatePositionSize(signal.getConfidence(), currentPrice);

        if (allocatedCapital.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[v62][{}] No capital allocated for signal", executionId);
            return;
        }

        log.debug("[v62][{}] Step 3: Calculating number of contracts", executionId);
        int quantity = capitalAllocationService.calculateContracts(allocatedCapital, currentPrice);

        if (quantity == 0) {
            log.warn("[v62][{}] Cannot afford even 1 contract at ${}", executionId, currentPrice);
            return;
        }

        BigDecimal positionValue = currentPrice.multiply(BigDecimal.valueOf(quantity * 100));
        log.info("[v62][{}] Position details - Contracts: {}, Value: ${}, Allocated: ${}",
                executionId, quantity, positionValue, allocatedCapital);

        if (!safetyService.validatePositionSize(positionValue)) {
            log.error("[v62][{}] Position size ${} exceeds safety limits", executionId, positionValue);
            return;
        }

        if (paperMode) {
            log.info("[v62][{}] Executing PAPER trade", executionId);
            executePaperTrade(signal, currentPrice, quantity, executionId);
        } else {
            log.info("[v62][{}] Executing REAL trade", executionId);
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
        String side;
        if (signal.getSignalType().equals("BUY")) {
            side = "buy_to_open";
        } else {
            side = "sell_to_open";
        }
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

        // Get positions from BROKER API as source of truth
        Map<String, PositionSyncService.BrokerPosition> brokerPositions =
                positionSyncService.getCurrentPositions(false);

        // Also check DB for paper trades and strategy metadata
        List<Trade> dbTrades = tradeRepository.findByStatusAndSymbol("OPEN", "QQQ");

        log.info("[v62][{}] Found {} broker positions, {} DB trades",
                checkId, brokerPositions.size(), dbTrades.size());

        // Check real positions from broker
        for (PositionSyncService.BrokerPosition brokerPos : brokerPositions.values()) {
            // Find corresponding DB trade for strategy metadata
            Trade dbTrade = dbTrades.stream()
                    .filter(t -> t.getOptionSymbol().equals(brokerPos.getSymbol()))
                    .findFirst()
                    .orElse(null);

            if (dbTrade != null) {
                // Use DB trade metadata with broker position data
                checkBrokerPosition(brokerPos, dbTrade, checkId);
            } else {
                // No metadata, use broker position only
                checkBrokerPositionWithoutMetadata(brokerPos, checkId);
            }
        }

        // Check paper trades (not at broker)
        for (Trade trade : dbTrades) {
            if (trade.getOptionSymbol().startsWith("PAPER_")) {
                checkPaperPosition(trade, checkId);
            }
        }
    }

    private void checkBrokerPosition(PositionSyncService.BrokerPosition brokerPos,
                                     Trade dbTrade, String checkId) {
        log.info("[v62][{}] Checking broker position: {} with DB metadata",
                checkId, brokerPos.getSymbol());

        BigDecimal currentPrice = brokerPos.getCurrentPrice();
        BigDecimal pnl = brokerPos.getPnl();

        log.info("[v62][{}] Position {} - Entry: ${}, Current: ${}, P&L: ${} ({}%)",
                checkId, brokerPos.getSymbol(),
                brokerPos.getAvgCost(), currentPrice, pnl,
                brokerPos.getPnlPercent().multiply(new BigDecimal(100)).intValue());

        // Check exit conditions using DB metadata
        boolean shouldClose = false;
        String reason = "";

        try {
            // Use targets from DB trade
            if (dbTrade.getStopLoss() != null &&
                    currentPrice.compareTo(dbTrade.getStopLoss()) <= 0) {
                shouldClose = true;
                reason = "Stop loss hit";
            } else if (dbTrade.getTargetPrice() != null &&
                    currentPrice.compareTo(dbTrade.getTargetPrice()) >= 0) {
                shouldClose = true;
                reason = "Target reached";
            } else if (LocalDateTime.now().getHour() >= 15 &&
                    LocalDateTime.now().getMinute() >= 30) {
                shouldClose = true;
                reason = "End of day exit";
            }
            // Check if option is near expiration (for 0DTE)
            else if (LocalDateTime.now().getHour() >= 15 &&
                    LocalDateTime.now().getMinute() >= 50) {
                shouldClose = true;
                reason = "Near expiration exit";
            }
        } catch (Exception e) {
            log.error("[v62][{}] Error checking exit conditions for {}: {}",
                    checkId, brokerPos.getSymbol(), e.getMessage());
            return;
        }

        if (shouldClose) {
            closeBrokerPosition(dbTrade, brokerPos, currentPrice, reason, checkId);
        }
    }

    private void checkBrokerPositionWithoutMetadata(PositionSyncService.BrokerPosition brokerPos,
                                                    String checkId) {
        log.info("[v62][{}] Checking broker position without metadata: {}",
                checkId, brokerPos.getSymbol());

        BigDecimal currentPrice = brokerPos.getCurrentPrice();
        BigDecimal pnl = brokerPos.getPnl();
        BigDecimal pnlPercent = brokerPos.getPnlPercent();

        // Conservative exit rules without metadata
        boolean shouldClose = false;
        String reason = "";

        // Exit if down 30%
        if (pnlPercent.compareTo(new BigDecimal("-0.30")) <= 0) {
            shouldClose = true;
            reason = "30% loss limit";
        }
        // Exit if up 50%
        else if (pnlPercent.compareTo(new BigDecimal("0.50")) >= 0) {
            shouldClose = true;
            reason = "50% profit target";
        }
        // End of day
        else if (LocalDateTime.now().getHour() >= 15 &&
                LocalDateTime.now().getMinute() >= 30) {
            shouldClose = true;
            reason = "End of day exit";
        }

        if (shouldClose) {
            // Create minimal trade record for closing
            Trade trade = new Trade();
            trade.setSymbol("QQQ");
            trade.setOptionSymbol(brokerPos.getSymbol());
            trade.setType(brokerPos.getSymbol().contains("C") ? "CALL" : "PUT");
            trade.setAction("BUY");
            trade.setQuantity(Math.abs(brokerPos.getQuantity()));
            trade.setEntryPrice(brokerPos.getAvgCost());
            trade.setStatus("OPEN");
            trade.setStrategy("UNTRACKED_POSITION");

            closeBrokerPosition(trade, brokerPos, currentPrice, reason, checkId);
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

    private void closeBrokerPosition(Trade trade, PositionSyncService.BrokerPosition brokerPos,
                                     BigDecimal exitPrice, String reason, String checkId) {
        log.info("[v62][{}] Closing broker position {} - Reason: {}",
                checkId, brokerPos.getSymbol(), reason);

        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setupForOption(brokerPos.getSymbol());
        try {
            orderRequest.setSymbol(brokerPos.getSymbol());
        } catch (Exception e) {
            log.warn("[v62][{}] Failed to set symbol: {}", checkId, e.getMessage());
        }

        orderRequest.setQuantity(Math.abs(brokerPos.getQuantity()));
        orderRequest.setSide("sell_to_close"); // Assuming long positions
        orderRequest.setType("market");
        orderRequest.setDuration("day");

        log.info("[v62][{}] Closing position - Symbol: {}, Side: {}, Qty: {}",
                checkId, brokerPos.getSymbol(), orderRequest.getSide(), orderRequest.getQuantity());

        OrderResponse orderResponse = tradierService.placeOrder(orderRequest);
        if (orderResponse != null && orderResponse.getStatus().equals("filled")) {
            // Update trade record
            trade.setExitPrice(exitPrice);
            trade.setExitTime(LocalDateTime.now());
            trade.setStatus("CLOSED");
            trade.setCloseReason(reason);
            trade.setRealizedPnl(brokerPos.getPnl());
            trade.setProfit(brokerPos.getPnl());
            tradeRepository.save(trade);

            // Refresh positions after closing
            positionSyncService.refreshPositions();

            telegramService.notifyTrade(trade);
            log.info("[v62][{}] Position closed - P&L: ${}", checkId, trade.getProfit());
        } else {
            log.error("[v62][{}] Failed to close position - Status: {}",
                    checkId, orderResponse != null ? orderResponse.getStatus() : "null");
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
}