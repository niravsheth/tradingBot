package com.tradingBot.service;

import com.tradingBot.entity.*;
import com.tradingBot.model.*;
import com.tradingBot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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

        // Check if trading is enabled
        if (!safetyService.isTradingEnabled()) {
            log.warn("[v62][{}] Trading is disabled - cannot execute signals", executionId);
            return;
        }

        // Check safety conditions
        if (!safetyService.canTrade()) {
            log.warn("[v62][{}] Trading blocked by safety checks", executionId);
            return;
        }

        // Get unexecuted signals from last 5 minutes
        List<Signal> signals = signalRepository
                .findByExecutedFalseAndTimestampAfter(LocalDateTime.now().minusMinutes(5));

        log.info("[v62][{}] Found {} unexecuted signals to process", executionId, signals.size());

        if (signals.isEmpty()) {
            log.debug("[v62][{}] No signals to execute", executionId);
            return;
        }

        // Check available capital before processing signals
        BigDecimal availableCapital = capitalAllocationService.getAvailableCapital();
        log.info("[v62][{}] Available capital for trading: ${}", executionId, availableCapital);

        if (availableCapital.compareTo(new BigDecimal("100")) < 0) {
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

        // Double-check safety
        if (!safetyService.canTrade()) {
            log.warn("[v62][{}] Safety check failed during execution", executionId);
            return;
        }

        // Step 1: Get current quote for the option
        log.debug("[v62][{}] Step 1: Getting quote for {}", executionId, signal.getOptionSymbol());
        QuoteResponse quoteResponse = tradierService.getQuote(signal.getOptionSymbol());
        if (quoteResponse == null || quoteResponse.getQuote() == null) {
            log.error("[v62][{}] Failed to get quote for {}", executionId, signal.getOptionSymbol());
            return;
        }

        Quote quote = quoteResponse.getQuote();
        BigDecimal currentPrice = quote.getLast();
        log.info("[v62][{}] Option {} - Price: ${}, Bid: ${}, Ask: ${}",
                executionId, signal.getOptionSymbol(), currentPrice, quote.getBid(), quote.getAsk());

        // Step 2: Calculate position size based on confidence
        log.debug("[v62][{}] Step 2: Calculating position size based on confidence", executionId);
        BigDecimal allocatedCapital = capitalAllocationService
                .calculatePositionSize(signal.getConfidence(), currentPrice);

        if (allocatedCapital.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[v62][{}] No capital allocated for signal", executionId);
            return;
        }

        // Step 3: Calculate number of contracts
        log.debug("[v62][{}] Step 3: Calculating number of contracts", executionId);
        int quantity = capitalAllocationService.calculateContracts(allocatedCapital, currentPrice);

        if (quantity == 0) {
            log.warn("[v62][{}] Cannot afford even 1 contract at ${}", executionId, currentPrice);
            return;
        }

        // Step 4: Validate position size with safety service
        BigDecimal positionValue = currentPrice.multiply(BigDecimal.valueOf(quantity * 100));
        log.info("[v62][{}] Position details - Contracts: {}, Value: ${}, Allocated: ${}",
                executionId, quantity, positionValue, allocatedCapital);

        if (!safetyService.validatePositionSize(positionValue)) {
            log.error("[v62][{}] Position size ${} exceeds safety limits", executionId, positionValue);
            return;
        }

        // Step 5: Execute trade (paper or real)
        if (paperMode) {
            log.info("[v62][{}] Executing PAPER trade", executionId);
            executePaperTrade(signal, currentPrice, quantity, executionId);
        } else {
            log.info("[v62][{}] Executing REAL trade", executionId);
            executeRealTrade(signal, currentPrice, quantity, executionId);
        }
    }

    private void executePaperTrade(Signal signal, BigDecimal price, int quantity, String executionId) {
        log.info("[v62][{}] Creating paper trade record", executionId);

        // Create paper trade record
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

        tradeRepository.save(trade);
        log.info("[v62][{}] Paper trade saved - ID: {}", executionId, trade.getId());

        // Mark signal as executed
        signal.setExecuted(true);
        signalRepository.save(signal);

        // Calculate position value
        BigDecimal positionValue = price.multiply(BigDecimal.valueOf(quantity * 100));

        // Notify
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
        orderRequest.setSymbol(signal.getOptionSymbol());
        orderRequest.setQuantity(quantity);
        orderRequest.setSide(signal.getSignalType().equals("BUY") ? "buy" : "sell");
        orderRequest.setType("market");
        orderRequest.setDuration("day");

        // Place order
        log.info("[v62][{}] Placing order via Tradier API", executionId);
        OrderResponse orderResponse = tradierService.placeOrder(orderRequest);

        if (orderResponse != null && orderResponse.getStatus().equals("filled")) {
            log.info("[v62][{}] Order FILLED - Order ID: {}", executionId, orderResponse.getId());

            // Create trade record
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

            tradeRepository.save(trade);
            log.info("[v62][{}] Trade record saved - Trade ID: {}", executionId, trade.getId());

            // Mark signal as executed
            signal.setExecuted(true);
            signalRepository.save(signal);

            // Notify via Telegram
            telegramService.notifyTrade(trade);

            log.info("[v62][{}] Real trade execution complete", executionId);
        } else {
            log.error("[v62][{}] Order failed or not filled - Status: {}",
                    executionId, orderResponse != null ? orderResponse.getStatus() : "null");
        }
    }

    @Transactional
    public void checkOpenPositions() {
        String checkId = UUID.randomUUID().toString().substring(0, 8);
        log.debug("[v62][{}] Checking open positions", checkId);

        List<Trade> openTrades = tradeRepository.findByStatusAndSymbol("OPEN", "QQQ");

        if (openTrades.isEmpty()) {
            log.debug("[v62][{}] No open positions to check", checkId);
            return;
        }

        log.info("[v62][{}] Checking {} open positions", checkId, openTrades.size());

        for (Trade trade : openTrades) {
            try {
                checkPosition(trade, checkId);
            } catch (Exception e) {
                log.error("[v62][{}] Error checking position {}: {}",
                        checkId, trade.getOptionSymbol(), e.getMessage());
            }
        }
    }

    private void checkPosition(Trade trade, String checkId) {
        log.debug("[v62][{}] Checking position: {}", checkId, trade.getOptionSymbol());

        QuoteResponse quoteResponse = tradierService.getQuote(trade.getOptionSymbol());
        if (quoteResponse == null || quoteResponse.getQuote() == null) {
            log.warn("[v62][{}] Failed to get quote for position {}", checkId, trade.getOptionSymbol());
            return;
        }

        Quote quote = quoteResponse.getQuote();
        BigDecimal currentPrice = quote.getLast();

        // Calculate P&L
        BigDecimal pnl = currentPrice.subtract(trade.getEntryPrice())
                .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));

        log.debug("[v62][{}] Position {} - Entry: ${}, Current: ${}, P&L: ${}",
                checkId, trade.getOptionSymbol(), trade.getEntryPrice(), currentPrice, pnl);

        Signal originalSignal = signalRepository
                .findBySymbolAndTimestampAfter(trade.getOptionSymbol(),
                        trade.getEntryTime().minusMinutes(10))
                .stream()
                .findFirst()
                .orElse(null);

        if (originalSignal == null) {
            log.warn("[v62][{}] No original signal found for trade", checkId);
            return;
        }

        // Check exit conditions
        boolean shouldClose = false;
        String reason = "";

        // Check stop loss
        if (currentPrice.compareTo(originalSignal.getStopLoss()) <= 0) {
            shouldClose = true;
            reason = "Stop loss hit";
            log.info("[v62][{}] Stop loss triggered for {} at ${}",
                    checkId, trade.getOptionSymbol(), currentPrice);
        }
        // Check target
        else if (currentPrice.compareTo(originalSignal.getTargetPrice()) >= 0) {
            shouldClose = true;
            reason = "Target reached";
            log.info("[v62][{}] Target reached for {} at ${}",
                    checkId, trade.getOptionSymbol(), currentPrice);
        }
        // Check time-based exit
        else if (LocalDateTime.now().getHour() >= 15 &&
                LocalDateTime.now().getMinute() >= 30) {
            shouldClose = true;
            reason = "End of day exit";
            log.info("[v62][{}] End of day exit for {}", checkId, trade.getOptionSymbol());
        }

        if (shouldClose) {
            closePosition(trade, currentPrice, reason, checkId);
        }
    }

    private void closePosition(Trade trade, BigDecimal exitPrice, String reason, String checkId) {
        log.info("[v62][{}] Closing position {} - Reason: {}",
                checkId, trade.getOptionSymbol(), reason);

        // For paper trades
        if (trade.getOptionSymbol().startsWith("PAPER_")) {
            closePaperPosition(trade, exitPrice, reason, checkId);
            return;
        }

        // For real trades
        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setSymbol(trade.getOptionSymbol());
        orderRequest.setQuantity(trade.getQuantity());
        orderRequest.setSide(trade.getAction().equals("BUY") ? "sell" : "buy");
        orderRequest.setType("market");
        orderRequest.setDuration("day");

        OrderResponse orderResponse = tradierService.placeOrder(orderRequest);

        if (orderResponse != null && orderResponse.getStatus().equals("filled")) {
            // Update trade record
            trade.setExitPrice(exitPrice);
            trade.setExitTime(LocalDateTime.now());
            trade.setStatus("CLOSED");

            // Calculate profit
            BigDecimal multiplier = BigDecimal.valueOf(100);
            if (trade.getAction().equals("BUY")) {
                trade.setProfit(exitPrice.subtract(trade.getEntryPrice())
                        .multiply(BigDecimal.valueOf(trade.getQuantity()))
                        .multiply(multiplier));
            } else {
                trade.setProfit(trade.getEntryPrice().subtract(exitPrice)
                        .multiply(BigDecimal.valueOf(trade.getQuantity()))
                        .multiply(multiplier));
            }

            tradeRepository.save(trade);

            // Notify
            telegramService.notifyTrade(trade);

            log.info("[v62][{}] Position closed - P&L: ${}", checkId, trade.getProfit());
        }
    }

    private void closePaperPosition(Trade trade, BigDecimal exitPrice, String reason, String checkId) {
        log.info("[v62][{}] Closing paper position", checkId);

        // Update trade record
        trade.setExitPrice(exitPrice);
        trade.setExitTime(LocalDateTime.now());
        trade.setStatus("CLOSED");

        // Calculate profit
        BigDecimal multiplier = BigDecimal.valueOf(100);
        if (trade.getAction().equals("BUY")) {
            trade.setProfit(exitPrice.subtract(trade.getEntryPrice())
                    .multiply(BigDecimal.valueOf(trade.getQuantity()))
                    .multiply(multiplier));
        }

        tradeRepository.save(trade);

        // Notify
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
}