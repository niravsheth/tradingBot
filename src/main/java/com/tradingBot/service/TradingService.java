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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    private final ExecutionPipeline executionPipeline;
    private final PreTradeRiskEngine preTradeRiskEngine;

    private final SafetyService safetyService;
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
        log.info("[INST][{}] === INSTITUTIONAL EXECUTION START ===", executionId);

        // Get pending signals - NO tracking status anymore
        List<Signal> signals = signalRepository
                .findByStatusAndExpirationTimeAfter("PENDING", LocalDateTime.now())
                .stream()
                .filter(s -> !s.getExecuted())
                .sorted((s1, s2) -> {
                    // Priority: Unusual flow first, then by confidence
                    if (s1.getStrategy().contains("UNUSUAL_FLOW") &&
                            !s2.getStrategy().contains("UNUSUAL_FLOW")) {
                        return -1;
                    }
                    if (!s1.getStrategy().contains("UNUSUAL_FLOW") &&
                            s2.getStrategy().contains("UNUSUAL_FLOW")) {
                        return 1;
                    }
                    return Double.compare(s2.getConfidence(), s1.getConfidence());
                })
                .collect(Collectors.toList());

        log.info("[INST][{}] Found {} signals ready for immediate execution",
                executionId, signals.size());

        if (!signals.isEmpty()) {
            // Check if market is suitable ONCE
            if (!preTradeRiskEngine.isMarketSuitable()) {
                log.warn("[INST][{}] Market conditions unsuitable - blocking all executions",
                        executionId);

                // Mark all signals as blocked
                signals.forEach(signal -> {
                    signal.setStatus("MARKET_UNSUITABLE");
                    signal.setExecuted(true);
                    signalRepository.save(signal);
                });
                return;
            }

            // Execute high priority signals immediately
            List<Signal> urgentSignals = signals.stream()
                    .filter(s -> s.getStrategy().contains("UNUSUAL_FLOW") ||
                            s.getConfidence() >= 0.85)
                    .limit(3) // Max 3 simultaneous executions
                    .collect(Collectors.toList());

            if (!urgentSignals.isEmpty()) {
                log.info("[INST][{}] Executing {} URGENT signals immediately",
                        executionId, urgentSignals.size());

                // Execute in parallel for speed
                List<CompletableFuture<Trade>> futures = urgentSignals.stream()
                        .map(signal -> executionPipeline.executeImmediately(signal))
                        .collect(Collectors.toList());

                // Wait for all to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .join();
            }

            // Execute remaining signals sequentially
            List<Signal> remainingSignals = signals.stream()
                    .filter(s -> !urgentSignals.contains(s))
                    .collect(Collectors.toList());

            for (Signal signal : remainingSignals) {
                if (!safetyService.canTrade()) {
                    log.warn("[INST][{}] Safety limit reached - stopping execution",
                            executionId);
                    break;
                }

                executionPipeline.executeImmediately(signal).join();
            }
        }

        log.info("[INST][{}] === INSTITUTIONAL EXECUTION COMPLETE ===", executionId);
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

        log.info("[{}] Found {} broker positions, {} DB trades",
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
                log.warn("[{}] No DB record for broker position: {}", checkId, optionSymbol);
                checkBrokerOnlyPosition(brokerPos, checkId);
            }
        }
    }

    private void checkBrokerOnlyPosition(BrokerPosition brokerPos, String checkId) {
        log.info("[{}] Checking broker position without DB metadata: {}",
                checkId, brokerPos.getSymbol());

        try {
            // Get fresh quote
            QuoteResponse quoteResponse = tradierService.getQuote(brokerPos.getSymbol());
            if (quoteResponse == null || quoteResponse.getQuote() == null) {
                log.error("[{}] Failed to get quote for {}", checkId, brokerPos.getSymbol());
                return;
            }

            Quote quote = quoteResponse.getQuote();
            BigDecimal currentBid = quote.getBid();
            BigDecimal currentPrice = currentBid != null && currentBid.compareTo(BigDecimal.ZERO) > 0 ?
                    currentBid : quote.getLast();

            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[{}] Invalid price for {}", checkId, brokerPos.getSymbol());
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

            log.info("[{}] {} - Entry: ${}, Current: ${}, P&L: ${} ({}%)",
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
                log.warn("[{}] Invalid price for {} - Bid: ${}, Last: ${}",
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

            log.info("[{}] {} - Entry: ${} → Bid: ${} / Mid: ${} / Ask: ${} | P&L: ${} ({}%)",
                    checkId, optionSymbol, entryPrice, currentBid, currentMid, currentAsk,
                    pnl, pnlPercent);

            // CHECK STOPS AND TARGETS
            boolean shouldClose = false;
            String closeReason = "";

            // Stop Loss Check - Use BID price
            if (dbTrade.getStopLoss() != null && currentBid.compareTo(dbTrade.getStopLoss()) <= 0) {
                shouldClose = true;
                closeReason = "STOP_LOSS_HIT";
                log.warn("[{}] 🛑 STOP LOSS HIT - Bid ${} <= Stop ${}",
                        checkId, currentBid, dbTrade.getStopLoss());
            }

            // Target Check - Use ASK price (conservative for targets)
            else if (dbTrade.getTarget() != null && currentAsk.compareTo(dbTrade.getTarget()) >= 0) {
                shouldClose = true;
                closeReason = "TARGET_REACHED";
                log.info("[{}] 🎯 TARGET HIT - Ask ${} >= Target ${}",
                        checkId, currentAsk, dbTrade.getTarget());
            }

            // Time-based exits
            else if (LocalTime.now().isAfter(LocalTime.of(15, 50))) {
                shouldClose = true;
                closeReason = "EOD_EXIT";
                log.warn("[{}] ⏰ End of day exit - 3:50 PM", checkId);
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
            log.error("[{}] Error checking position {}: {}",
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
            log.info("[{}] EXECUTING CLOSE for {} - Reason: {}",
                    checkId, trade.getOptionSymbol(), reason);

            // Create market order to close
            OrderRequest closeOrder = new OrderRequest();
            closeOrder.setupForOption(brokerPos.getSymbol());
            closeOrder.setSymbol(brokerPos.getSymbol());
            closeOrder.setQuantity(Math.abs(brokerPos.getQuantity()));
            closeOrder.setSide("sell_to_close");
            closeOrder.setType("market");
            closeOrder.setDuration("day");

            log.info("[{}] Placing MARKET close order for {} contracts",
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
                log.error("[{}] Failed to place close order", checkId);
                telegramService.sendMessage("❌ Failed to close position: " + trade.getOptionSymbol());
            }

        } catch (Exception e) {
            log.error("[{}] Error executing close: {}", checkId, e.getMessage());
            telegramService.sendMessage("❌ Error closing position: " + e.getMessage());
        }
    }
}