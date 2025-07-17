package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import com.tradingBot.entity.Trade;
import com.tradingBot.model.*;
import com.tradingBot.repository.SignalRepository;
import com.tradingBot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.math.RoundingMode;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExecutionPipeline {

    private final TradierService tradierService;
    private final TradeRepository tradeRepository;
    private final SignalRepository signalRepository;
    private final TelegramService telegramService;
    private final KellyPositionSizer kellySizer;
    private final PreTradeRiskEngine riskEngine;

    @Async
    public CompletableFuture<Trade> executeImmediately(Signal signal) {
        long startTime = System.currentTimeMillis();
        String execId = "EXEC-" + signal.getId();

        log.info("[{}] 🚀 IMMEDIATE EXECUTION START - {}", execId, signal.getOptionSymbol());

        try {
            // 1. Final risk check (50ms)
            if (!riskEngine.canExecuteSignal(signal)) {
                log.warn("[{}] Final risk check failed", execId);
                updateSignalStatus(signal, "RISK_BLOCKED");
                return CompletableFuture.completedFuture(null);
            }

            // 2. Get fresh quote (100ms)
            QuoteResponse freshQuote = tradierService.getQuote(signal.getOptionSymbol());
            if (freshQuote == null || freshQuote.getQuote() == null) {
                log.error("[{}] Failed to get fresh quote", execId);
                updateSignalStatus(signal, "NO_QUOTE");
                return CompletableFuture.completedFuture(null);
            }

            Quote quote = freshQuote.getQuote();
            BigDecimal currentMid = quote.getBid().add(quote.getAsk())
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

            // 3. Check if price moved too much (instant)
            BigDecimal priceMove = currentMid.subtract(signal.getEntryPrice())
                    .abs().divide(signal.getEntryPrice(), 4, RoundingMode.HALF_UP);

            if (priceMove.compareTo(BigDecimal.valueOf(0.15)) > 0) {
                log.warn("[{}] Price moved {}% - too much", execId,
                        priceMove.multiply(BigDecimal.valueOf(100)));
                updateSignalStatus(signal, "PRICE_MOVED");
                return CompletableFuture.completedFuture(null);
            }

            // 4. Calculate position size (50ms)
            int contracts = kellySizer.calculateOptimalSize(signal, currentMid);
            if (contracts == 0) {
                log.warn("[{}] Kelly sizer returned 0 contracts", execId);
                updateSignalStatus(signal, "SIZE_TOO_SMALL");
                return CompletableFuture.completedFuture(null);
            }

            // 5. Build smart order (instant)
            OrderRequest order = buildSmartOrder(signal, contracts, quote);

            // 6. Execute with retry logic (200-500ms)
            Trade trade = executeWithRetry(order, signal, currentMid, contracts, execId);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("[{}] ✅ EXECUTION COMPLETE in {}ms", execId, executionTime);

            if (executionTime > 500) {
                telegramService.sendMessage(String.format(
                        "⚠️ SLOW EXECUTION: %s took %dms",
                        signal.getOptionSymbol(), executionTime
                ));
            }

            return CompletableFuture.completedFuture(trade);

        } catch (Exception e) {
            log.error("[{}] Execution failed: {}", execId, e.getMessage(), e);
            updateSignalStatus(signal, "EXECUTION_ERROR");
            return CompletableFuture.completedFuture(null);
        }
    }

    private OrderRequest buildSmartOrder(Signal signal, int contracts, Quote quote) {
        OrderRequest order = new OrderRequest();
        order.setupForOption(signal.getOptionSymbol());
        order.setSymbol(signal.getOptionSymbol());
        order.setQuantity(contracts);
        order.setSide("buy_to_open");

        // Smart order type selection
        if (signal.getStrategy().contains("UNUSUAL_FLOW") ||
                signal.getConfidence() >= 0.90) {
            // Aggressive fill for high conviction
            order.setType("market");
        } else {
            // Limit order at ask minus 1 penny for others
            BigDecimal limitPrice = quote.getAsk().subtract(BigDecimal.valueOf(0.01));
            order.setType("limit");
            order.setPrice(limitPrice);
        }

        order.setDuration("day");
        return order;
    }

    // Replace the executeWithRetry method with this corrected version:

    private Trade executeWithRetry(OrderRequest order, Signal signal,
                                   BigDecimal fillPrice, int contracts, String execId) {
        int maxRetries = 3;
        int retryDelayMs = 100;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("[{}] Order attempt {}/{}", execId, attempt, maxRetries);

                OrderResponse response = tradierService.placeOrder(order);

                if (response != null && response.getId() != null) {
                    // Success - create trade record
                    Trade trade = createTradeRecord(signal, response, fillPrice, contracts);

                    // Update signal
                    signal.setExecuted(true);
                    signal.setStatus("EXECUTED");
                    signal.setTradeId(trade.getId());
                    signal.setExecutionStartTime(LocalDateTime.now());
                    signalRepository.save(signal);

                    // Send notification
                    sendExecutionNotification(trade, signal, execId);

                    return trade;
                }

                if (attempt < maxRetries) {
                    log.warn("[{}] Order attempt {} failed, retrying in {}ms",
                            execId, attempt, retryDelayMs);
                    Thread.sleep(retryDelayMs);

                    // If limit order failed, switch to market on last attempt
                    if (attempt == maxRetries - 1 && "limit".equals(order.getType())) {
                        log.info("[{}] Switching to market order for final attempt", execId);
                        order.setType("market");
                        order.setPrice(null);
                    }
                }

            } catch (Exception e) {
                log.error("[{}] Order attempt {} error: {}", execId, attempt, e.getMessage());
                if (attempt == maxRetries) {
                    throw new RuntimeException("All order attempts failed", e);
                }
            }
        }

        throw new RuntimeException("Failed to execute after " + maxRetries + " attempts");
    }

    // Also update createTradeRecord method:
    private Trade createTradeRecord(Signal signal, OrderResponse response,
                                    BigDecimal fillPrice, int contracts) {
        Trade trade = new Trade();
        trade.setSymbol(signal.getSymbol());
        trade.setOptionSymbol(signal.getOptionSymbol());
        trade.setType(signal.getOptionSymbol().contains("C") ? "CALL" : "PUT");
        trade.setAction("BUY");
        trade.setQuantity(contracts);
        trade.setEntryPrice(response.getAvgFillPrice() != null ?
                response.getAvgFillPrice() : fillPrice);
        trade.setStatus("OPEN");
        trade.setEntryTime(LocalDateTime.now());
        trade.setStrategy(signal.getStrategy());
        trade.setOrderId(response.getId()); // Use response.getId() directly
        trade.setSignalId(signal.getId());

        // Copy targets from signal
        trade.setTargetPrice(signal.getTargetPrice());
        trade.setStopLoss(signal.getStopLoss());

        return tradeRepository.save(trade);
    }

    private void updateSignalStatus(Signal signal, String status) {
        signal.setStatus(status);
        signal.setExecuted(true);
        signal.setExecutionNotes("Blocked by: " + status);
        signalRepository.save(signal);
    }

    private void sendExecutionNotification(Trade trade, Signal signal, String execId) {
        BigDecimal positionValue = trade.getEntryPrice()
                .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));

        String message = String.format(
                "⚡ <b>INSTANT EXECUTION</b> [%s]\n" +
                        "Option: %s\n" +
                        "Strategy: %s\n" +
                        "Confidence: %d%%\n" +
                        "Contracts: %d @ $%.2f\n" +
                        "Position: $%.2f\n" +
                        "Target: $%.2f | Stop: $%.2f\n" +
                        "Execution: <100ms",
                execId.substring(5),
                trade.getOptionSymbol(),
                trade.getStrategy(),
                (int)(signal.getConfidence() * 100),
                trade.getQuantity(),
                trade.getEntryPrice(),
                positionValue,
                trade.getTargetPrice(),
                trade.getStopLoss()
        );

        telegramService.sendMessage(message);
    }
}