package com.tradingBot.service;

import com.tradingBot.analytics.MarketMicrostructureAnalyzer;
import com.tradingBot.entity.MarketMicrostructureData;
import com.tradingBot.entity.Signal;
import com.tradingBot.entity.Trade;
import com.tradingBot.engine.TrendTrackingEngine;
import com.tradingBot.execution.ExecutionAlgorithmSelector;
import com.tradingBot.execution.ExecutionAlgorithmSelector.*;
import com.tradingBot.ml.SignalMLScorer;
import com.tradingBot.model.*;
import com.tradingBot.repository.SignalRepository;
import com.tradingBot.repository.TradeRepository;
import com.tradingBot.risk.PositionSizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalExecutionService {

    private final SignalRepository signalRepository;
    private final TradeRepository tradeRepository;
    private final TradierService tradierService;
    private final TrendTrackingEngine trendEngine;
    private final TelegramService telegramService;
    private final SignalMLScorer mlScorer;
    private final PositionSizer positionSizer;
    private final MarketMicrostructureAnalyzer microAnalyzer;
    private final ExecutionAlgorithmSelector executionSelector;

    @Value("${tradingbot.execution.ml-threshold:0.65}")
    private double mlThreshold;

    @Value("${tradingbot.execution.account-value:100000}")
    private BigDecimal defaultAccountValue;

    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);

    @Transactional
    public void executeSignal(Signal signal) {
        try {
            log.info("Starting execution process for signal {}: {}",
                    signal.getId(), signal.getOptionSymbol());

            // Step 1: Validate signal
            if (!validateSignal(signal)) {
                return;
            }

            // Step 2: ML Scoring
            double mlScore = mlScorer.scoreSignal(signal);
            log.info("ML Score for {}: {}", signal.getOptionSymbol(), mlScore);

            if (mlScore < mlThreshold) {
                log.info("Signal {} rejected - ML score {} below threshold {}",
                        signal.getId(), mlScore, mlThreshold);
                signal.setStatus("REJECTED");
                signal.setReason(String.format("ML score %.3f below threshold", mlScore));
                signalRepository.save(signal);
                sendRejectionNotification(signal, mlScore);
                return;
            }

            // Step 3: Microstructure Check
            MarketMicrostructureData microData = microAnalyzer.analyzeMicrostructure(signal.getSymbol());
            if (microData.getToxicityScore() != null && microData.getToxicityScore() > 0.8) {
                log.warn("Market microstructure toxic for {}, delaying execution", signal.getSymbol());
                scheduleDelayedExecution(signal, 60000); // Retry in 1 minute
                return;
            }

            // Step 4: Trend Validation
            if (!trendEngine.isTrendFavorable(signal.getSymbol(), signal.getSignalType())) {
                log.info("Signal {} skipped - trend not favorable", signal.getId());
                signal.setStatus("SKIPPED");
                signal.setReason("Trend not favorable");
                signalRepository.save(signal);
                return;
            }

            // Step 5: Get Current Quote
            QuoteResponse optionQuote = tradierService.getQuote(signal.getOptionSymbol());
            if (optionQuote == null || optionQuote.getQuote() == null) {
                log.error("Failed to get quote for {}", signal.getOptionSymbol());
                signal.setStatus("FAILED");
                signal.setReason("No quote available");
                signalRepository.save(signal);
                return;
            }

            Quote quote = optionQuote.getQuote();
            BigDecimal currentMid = quote.getBid().add(quote.getAsk())
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

            // Step 6: Dynamic Position Sizing
            BigDecimal accountValue = getAccountValue();
            int optimalSize = positionSizer.calculateOptimalPositionSize(signal, accountValue);

            if (optimalSize == 0) {
                log.warn("Position size calculated as 0 for signal {}", signal.getId());
                signal.setStatus("REJECTED");
                signal.setReason("Position size too small");
                signalRepository.save(signal);
                return;
            }

            // Step 7: Create Execution Plan
            ExecutionPlan executionPlan = executionSelector.createExecutionPlan(signal, optimalSize);

            // Step 8: Execute Trade
            executeWithPlan(signal, executionPlan, quote);

        } catch (Exception e) {
            log.error("Error executing signal {}: {}", signal.getId(), e.getMessage(), e);
            signal.setStatus("ERROR");
            signal.setReason("Execution error: " + e.getMessage());
            signalRepository.save(signal);
            sendErrorNotification(signal, e.getMessage());
        }
    }

    private void executeWithPlan(Signal signal, ExecutionPlan plan, Quote currentQuote) {
        log.info("Executing {} with {} algorithm, {} slices",
                signal.getOptionSymbol(), plan.getAlgorithm(), plan.getSlices().size());

        int totalExecuted = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        String firstOrderId = null;

        for (int i = 0; i < plan.getSlices().size(); i++) {
            OrderSlice slice = plan.getSlices().get(i);

            if (slice.getDelayMillis() > 0 && i > 0) {
                // Schedule delayed execution for subsequent slices
                final int sliceIndex = i;
                scheduledExecutor.schedule(() ->
                                executeSlice(signal, slice, plan, currentQuote),
                        slice.getDelayMillis(), TimeUnit.MILLISECONDS
                );
            } else {
                // Execute first slice immediately
                OrderResponse response = executeSlice(signal, slice, plan, currentQuote);
                if (response != null && response.getId() != null) {
                    if (firstOrderId == null) {
                        firstOrderId = response.getId();
                    }
                    totalExecuted += slice.getQuantity();
                    totalCost = totalCost.add(
                            response.getAvgFillPrice() != null ?
                                    response.getAvgFillPrice() : currentQuote.getLast()
                    ).multiply(BigDecimal.valueOf(slice.getQuantity()));
                }
            }
        }

        // Create trade record for executed portion
        if (totalExecuted > 0 && firstOrderId != null) {
            BigDecimal avgPrice = totalCost.divide(
                    BigDecimal.valueOf(totalExecuted), 2, RoundingMode.HALF_UP
            );

            Trade trade = createTradeFromSignal(signal, firstOrderId, avgPrice, totalExecuted);
            tradeRepository.save(trade);

            // Update signal
            signal.setExecuted(true);
            signal.setStatus("EXECUTED");
            signal.setTradeId(trade.getId());
            signal.setExecutionStartTime(LocalDateTime.now());
            signalRepository.save(signal);

            // Send notification
            sendExecutionNotification(signal, trade, plan);

            log.info("Signal {} executed successfully - Trade ID: {}", signal.getId(), trade.getId());
        }
    }

    private OrderResponse executeSlice(Signal signal, OrderSlice slice,
                                       ExecutionPlan plan, Quote currentQuote) {
        try {
            OrderRequest order = new OrderRequest();
            order.setupForOption(signal.getOptionSymbol());
            order.setSide("buy_to_open");
            order.setQuantity(slice.getQuantity());

            // Set order type based on algorithm
            switch (plan.getAlgorithm()) {
                case MARKET:
                    order.setType("market");
                    break;

                case LIMIT_SWEEP:
                case ADAPTIVE_LIMIT:
                    order.setType("limit");
                    BigDecimal limitPrice = calculateSmartLimitPrice(
                            currentQuote, plan.getUrgencyScore()
                    );
                    order.setPrice(limitPrice);
                    break;

                case ICEBERG:
                    order.setType("limit");
                    // Start at mid, work up if needed
                    BigDecimal icebergPrice = currentQuote.getBid().add(currentQuote.getAsk())
                            .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    order.setPrice(icebergPrice);
                    break;

                case SNIPER:
                    order.setType("limit");
                    // Aggressive but not market
                    BigDecimal sniperPrice = currentQuote.getAsk().subtract(
                            currentQuote.getAsk().subtract(currentQuote.getBid())
                                    .multiply(BigDecimal.valueOf(0.1))
                    );
                    order.setPrice(sniperPrice);
                    break;
            }

            order.setDuration("day");

            log.info("Placing {} order for {} contracts at {}",
                    order.getType(), slice.getQuantity(),
                    order.getPrice() != null ? order.getPrice() : "market");

            return tradierService.placeOrder(order);

        } catch (Exception e) {
            log.error("Error executing slice: {}", e.getMessage());
            return null;
        }
    }

    private BigDecimal calculateSmartLimitPrice(Quote quote, double urgencyScore) {
        BigDecimal bid = quote.getBid();
        BigDecimal ask = quote.getAsk();
        BigDecimal spread = ask.subtract(bid);

        // Start at a price based on urgency
        // High urgency = closer to ask, Low urgency = closer to bid
        BigDecimal targetPrice = bid.add(spread.multiply(BigDecimal.valueOf(urgencyScore)));

        // Round to nearest penny
        return targetPrice.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal getAccountValue() {
        // In production, this would query your broker API
        // For now, return configured default
        return defaultAccountValue;
    }

    private void scheduleDelayedExecution(Signal signal, long delayMillis) {
        signal.setStatus("DELAYED");
        signal.setReason("Market conditions unfavorable");
        signalRepository.save(signal);

        scheduledExecutor.schedule(() -> executeSignal(signal), delayMillis, TimeUnit.MILLISECONDS);
    }

    private boolean validateSignal(Signal signal) {
        // Check if expired
        if (signal.getExpirationTime() != null &&
                LocalDateTime.now().isAfter(signal.getExpirationTime())) {
            log.info("Signal {} expired", signal.getId());
            signal.setStatus("EXPIRED");
            signal.setReason("Signal expired");
            signalRepository.save(signal);
            return false;
        }

        // Check if already executed
        if (Boolean.TRUE.equals(signal.getExecuted())) {
            log.warn("Signal {} already executed", signal.getId());
            return false;
        }

        // Check status
        if (!"PENDING".equals(signal.getStatus())) {
            log.info("Signal {} status is not PENDING: {}", signal.getId(), signal.getStatus());
            return false;
        }

        return true;
    }

    private Trade createTradeFromSignal(Signal signal, String orderId,
                                        BigDecimal entryPrice, int quantity) {
        Trade trade = new Trade();
        trade.setSymbol(signal.getSymbol());
        trade.setOptionSymbol(signal.getOptionSymbol());
        trade.setSignalId(signal.getId());
        trade.setOrderId(orderId);
        trade.setAction("BUY");
        trade.setQuantity(quantity);
        trade.setEntryPrice(entryPrice);
        trade.setTargetPrice(signal.getTargetPrice());
        trade.setStopLoss(signal.getStopLoss());
        trade.setStrategy(signal.getStrategy());
        trade.setStatus("OPEN");
        trade.setEntryTime(LocalDateTime.now());
        trade.setCurrentPrice(entryPrice);

        // Parse option type from symbol
        String optionSymbol = signal.getOptionSymbol();
        trade.setType(optionSymbol.contains("C") ? "CALL" : "PUT");

        return trade;
    }

    private void sendExecutionNotification(Signal signal, Trade trade, ExecutionPlan plan) {
        String message = String.format(
                "✅ <b>SIGNAL EXECUTED</b>\n" +
                        "Option: %s\n" +
                        "ML Score: %.3f\n" +
                        "Algorithm: %s\n" +
                        "Quantity: %d contracts\n" +
                        "Entry: $%.2f\n" +
                        "Target: $%.2f (+%.0f%%)\n" +
                        "Stop: $%.2f (-%.0f%%)\n" +
                        "Strategy: %s\n" +
                        "Trend: %s\n" +
                        "Urgency: %.2f",
                trade.getOptionSymbol(),
                mlScorer.scoreSignal(signal),
                plan.getAlgorithm(),
                trade.getQuantity(),
                trade.getEntryPrice(),
                trade.getTargetPrice(),
                ((trade.getTargetPrice().doubleValue() / trade.getEntryPrice().doubleValue() - 1) * 100),
                trade.getStopLoss(),
                ((1 - trade.getStopLoss().doubleValue() / trade.getEntryPrice().doubleValue()) * 100),
                trade.getStrategy(),
                trendEngine.getSymbolTrend(signal.getSymbol()),
                plan.getUrgencyScore()
        );

        telegramService.sendMessage(message);
    }

    private void sendRejectionNotification(Signal signal, double mlScore) {
        String message = String.format(
                "❌ <b>SIGNAL REJECTED</b>\n" +
                        "Option: %s\n" +
                        "ML Score: %.3f (threshold: %.2f)\n" +
                        "Strategy: %s\n" +
                        "Reason: ML score below threshold",
                signal.getOptionSymbol(),
                mlScore,
                mlThreshold,
                signal.getStrategy()
        );

        telegramService.sendMessage(message);
    }

    private void sendErrorNotification(Signal signal, String error) {
        String message = String.format(
                "⚠️ <b>EXECUTION ERROR</b>\n" +
                        "Option: %s\n" +
                        "Error: %s\n" +
                        "Signal ID: %d",
                signal.getOptionSymbol(),
                error,
                signal.getId()
        );

        telegramService.sendMessage(message);
    }
}