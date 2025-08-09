package com.tradingBot.service;

import com.tradingBot.entity.*;
import com.tradingBot.ml.SignalMLScorer;
import com.tradingBot.model.*;
import com.tradingBot.repository.*;
import com.tradingBot.service.PositionSyncService.BrokerPosition;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
@AllArgsConstructor
public class TradingService {
    private final TradierService tradierService;
    private final TelegramService telegramService;
    private final TradeRepository tradeRepository;

    private final SignalMLScorer signalMLScorer;
    private final SignalRepository signalRepository;

    private final ExecutionPipeline executionPipeline;
    private final PreTradeRiskEngine preTradeRiskEngine;

    private final SignalConfirmationService signalConfirmationService;

    private final SafetyService safetyService;
    private final PositionSyncService positionSyncService;
    private final EnhancedStopLossMonitor enhancedStopLossMonitor;

    private final IntegratedBayesianMLSystem integratedBayesianMLSystem;


    @Transactional
    public void executeSignals() {
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[INST][{}] === INSTITUTIONAL EXECUTION START ===", executionId);

        // Get pending signals
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

        log.info("[INST][{}] Found {} signals for confirmation and execution",
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

            // CONFIRM SIGNALS FIRST
            List<Signal> confirmedSignals = new ArrayList<>();

            for (Signal signal : signals) {
                try {
                    // SIGNAL CONFIRMATION STEP
                    SignalConfirmationService.ConfirmationResult confirmation =
                            signalConfirmationService.confirmSignal(signal);

                    if (confirmation.isConfirmed()) {
                        // Update confidence based on confirmation
                        signal.setConfidence(confirmation.getFinalConfidence());
                        confirmedSignals.add(signal);

                        log.info("[INST][{}] ✅ Signal CONFIRMED: {} - Confidence: {}% -> {}%",
                                executionId, signal.getOptionSymbol(),
                                (int)(signal.getConfidence() * 100),
                                (int)(confirmation.getFinalConfidence() * 100));
                    } else {
                        // Mark as rejected
                        signal.setStatus("CONFIRMATION_REJECTED");
                        signal.setExecuted(true);
                        signalRepository.save(signal);

                        log.warn("[INST][{}] ❌ Signal REJECTED: {} - Reasons: {}",
                                executionId, signal.getOptionSymbol(),
                                String.join(", ", confirmation.getRejectionReasons()));
                    }

                } catch (Exception e) {
                    log.error("[INST][{}] Error confirming signal {}: {}",
                            executionId, signal.getOptionSymbol(), e.getMessage());

                    // Mark as error
                    signal.setStatus("CONFIRMATION_ERROR");
                    signal.setExecuted(true);
                    signalRepository.save(signal);
                }
            }

            log.info("[INST][{}] Confirmation complete: {} confirmed out of {} total",
                    executionId, confirmedSignals.size(), signals.size());

            // Execute high priority confirmed signals immediately
            List<Signal> urgentSignals = confirmedSignals.stream()
                    .filter(s -> s.getStrategy().contains("UNUSUAL_FLOW") ||
                            s.getConfidence() >= 0.85)
                    .limit(3) // Max 3 simultaneous executions
                    .collect(Collectors.toList());

            if (!urgentSignals.isEmpty()) {
                log.info("[INST][{}] Executing {} URGENT confirmed signals immediately",
                        executionId, urgentSignals.size());

                // Execute in parallel for speed
                List<CompletableFuture<Trade>> futures = urgentSignals.stream()
                        .map(signal -> executionPipeline.executeImmediately(signal))
                        .collect(Collectors.toList());

                // Wait for all to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .join();
            }

            // Execute remaining confirmed signals sequentially
            List<Signal> remainingSignals = confirmedSignals.stream()
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
        log.debug("[ENHANCED][{}] Checking open positions with order validation", checkId);

        positionSyncService.refreshPositions();

        Map<String, BrokerPosition> brokerPositions = positionSyncService.getCurrentPositions(false);

        List<Trade> dbTrades = tradeRepository.findByStatusAndSymbol("OPEN", "QQQ").stream()
                .filter(this::hasValidOrderExecution)
                .collect(Collectors.toList());

        log.info("[{}] Found {} broker positions, {} valid DB trades for monitoring",
                checkId, brokerPositions.size(), dbTrades.size());

        for (Map.Entry<String, BrokerPosition> entry : brokerPositions.entrySet()) {
            String optionSymbol = entry.getKey();
            BrokerPosition brokerPos = entry.getValue();

            if (!optionSymbol.contains("C") && !optionSymbol.contains("P")) {
                continue;
            }

            Trade dbTrade = dbTrades.stream()
                    .filter(t -> matchesOptionSymbol(t, optionSymbol))
                    .filter(this::hasValidOrderExecution)
                    .findFirst()
                    .orElse(null);

            if (dbTrade != null) {
                checkPositionWithEnhancedStopLoss(brokerPos, dbTrade, checkId);
            } else {
                checkBrokerOnlyPosition(brokerPos, checkId);
            }
        }
    }



    // Update the monitoring validation to be more strict
    private boolean hasValidOrderExecution(Trade trade) {
        // Must have OPEN status
        if (!"OPEN".equals(trade.getStatus())) {
            return false;
        }

        // Must have valid order ID
        if (trade.getOrderId() == null || trade.getOrderId().trim().isEmpty() || "unknown".equals(trade.getOrderId())) {
            log.warn("Trade {} missing valid order ID - skipping monitoring", trade.getId());
            return false;
        }

        // Must have valid entry price
        if (trade.getEntryPrice() == null || trade.getEntryPrice().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Trade {} has invalid entry price - skipping monitoring", trade.getId());
            return false;
        }

        // Must be recent (within 24 hours)
        if (trade.getEntryTime() == null || trade.getEntryTime().isBefore(LocalDateTime.now().minusHours(24))) {
            log.warn("Trade {} is too old - skipping monitoring", trade.getId());
            return false;
        }

        return true;
    }

    // ADD this new method to TradingService.java
    private boolean matchesOptionSymbol(Trade trade, String brokerSymbol) {
        String tradeSymbol = trade.getOptionSymbol();
        if (tradeSymbol == null) return false;

        if (tradeSymbol.startsWith("PAPER_")) {
            tradeSymbol = tradeSymbol.substring(6);
        }

        return tradeSymbol.equals(brokerSymbol);
    }

    private void checkPositionWithEnhancedStopLoss(BrokerPosition brokerPos, Trade dbTrade, String checkId) {
        try {
            log.debug("[ENHANCED][{}] Checking {} with advanced stop-loss monitoring",
                    checkId, dbTrade.getOptionSymbol());

            // STEP 1: Get fresh market data
            String optionSymbol = brokerPos.getSymbol();
            QuoteResponse quoteResponse = tradierService.getQuote(optionSymbol);

            if (quoteResponse == null || quoteResponse.getQuote() == null) {
                log.error("[ENHANCED][{}] Failed to get quote for {}", checkId, optionSymbol);
                return;
            }

            Quote quote = quoteResponse.getQuote();
            BigDecimal currentBid = quote.getBid();
            BigDecimal currentAsk = quote.getAsk();
            BigDecimal currentMid = currentBid.add(currentAsk).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

            if (currentBid == null || currentBid.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[{}] Invalid bid price for {}", checkId, optionSymbol);
                return;
            }

            // STEP 2: Enhanced stop-loss evaluation
            EnhancedStopLossMonitor.StopLossDecision stopDecision =
                    enhancedStopLossMonitor.evaluateStopLoss(dbTrade);

            // STEP 3: Update position with current prices
            dbTrade.setCurrentPrice(currentMid);
            BigDecimal entryPrice = brokerPos.getAvgCost();
            BigDecimal pnl = currentMid.subtract(entryPrice)
                    .multiply(BigDecimal.valueOf(Math.abs(brokerPos.getQuantity()) * 100));
            BigDecimal pnlPercent = currentMid.subtract(entryPrice)
                    .divide(entryPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            log.info("[ENHANCED][{}] {} - Entry: ${}, Bid: ${}/Ask: ${}, P&L: ${} ({}%)",
                    checkId, optionSymbol, entryPrice, currentBid, currentAsk, pnl, pnlPercent);

            // STEP 4: Check for enhanced stop-loss exit
            if (stopDecision.isShouldExit()) {
                executeEnhancedStopLossExit(dbTrade, brokerPos, stopDecision, checkId);
                return;
            }

            // STEP 5: Log enhanced stop-loss status
            EnhancedStopLossMonitor.StopLossState stopState =
                    enhancedStopLossMonitor.getStopLossState(dbTrade.getId().toString());

            if (stopState != null) {
                log.info("[ENHANCED][{}] Stop Status - Original: ${}, Current: ${}, Reason: {}",
                        checkId, stopState.getOriginalStop(), stopState.getCurrentStop(),
                        stopState.getAdjustmentReason());

                // Update trade record with enhanced stop
                if (stopState.getCurrentStop() != null &&
                        !stopState.getCurrentStop().equals(dbTrade.getStopLoss())) {
                    dbTrade.setStopLoss(stopState.getCurrentStop());
                    log.info("[ENHANCED][{}] Updated DB stop-loss to ${}", checkId, stopState.getCurrentStop());
                }
            }

            // STEP 6: Check for traditional exits (targets, time-based)
            boolean shouldClose = false;
            String closeReason = "";

            // Target Check
            if (dbTrade.getTarget() != null && currentAsk.compareTo(dbTrade.getTarget()) >= 0) {
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

            // STEP 7: Execute traditional exit if needed
            if (shouldClose) {
                executePositionClose(dbTrade, brokerPos, currentMid, closeReason, checkId);
            } else {
                // Save updated data
                dbTrade.setCurrentPrice(currentMid);
                dbTrade.setProfit(pnl);
                tradeRepository.save(dbTrade);
            }

        } catch (Exception e) {
            log.error("[ENHANCED][{}] Error in enhanced position check: {}", checkId, e.getMessage());
        }
    }

    private void executeEnhancedStopLossExit(Trade trade, BrokerPosition brokerPos,
                                             EnhancedStopLossMonitor.StopLossDecision decision, String checkId) {
        try {
            log.warn("[ENHANCED][{}] 🛑 ENHANCED STOP-LOSS TRIGGERED - {} (Type: {}, Confidence: {}%)",
                    checkId, trade.getOptionSymbol(), decision.getTriggerType(),
                    (int)(decision.getConfidence() * 100));

            // Create market order to close
            OrderRequest closeOrder = new OrderRequest();
            closeOrder.setupForOption(brokerPos.getSymbol());
            closeOrder.setSymbol(brokerPos.getSymbol());
            closeOrder.setQuantity(Math.abs(brokerPos.getQuantity()));
            closeOrder.setSide("sell_to_close");
            closeOrder.setType("market");
            closeOrder.setDuration("day");

            log.info("[ENHANCED][{}] Placing ENHANCED STOP market close order for {} contracts",
                    checkId, closeOrder.getQuantity());

            OrderResponse response = tradierService.placeOrder(closeOrder);

            if (response != null && response.getOrder() != null) {
                // Update trade record
                BigDecimal exitPrice = decision.getRecommendedExitPrice() != null ?
                        decision.getRecommendedExitPrice() : brokerPos.getAvgCost();

                trade.setExitPrice(exitPrice);
                trade.setExitTime(LocalDateTime.now());
                trade.setStatus("CLOSED");
                trade.setCloseReason("ENHANCED_STOP_LOSS_" + decision.getTriggerType());
                trade.setExitReason(decision.getExitReason());

                // Calculate final P&L
                BigDecimal finalPnl = exitPrice.subtract(brokerPos.getAvgCost())
                        .multiply(BigDecimal.valueOf(Math.abs(brokerPos.getQuantity()) * 100));
                trade.setRealizedPnl(finalPnl);
                trade.setProfit(finalPnl);

                tradeRepository.save(trade);

                updateAILearning(trade);

                // Refresh positions
                positionSyncService.refreshPositions();

                // Enhanced alert with details
                String emoji = finalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "✅" : "❌";
                String alertMessage = String.format(
                        "%s ENHANCED STOP-LOSS EXIT\n" +
                                "Option: %s\n" +
                                "Trigger: %s (%.0f%% confidence)\n" +
                                "Reason: %s\n" +
                                "Entry: $%.2f → Exit: $%.2f\n" +
                                "P&L: $%.2f (%.1f%%)\n" +
                                "Order ID: %s\n" +
                                "🔬 Advanced monitoring system",
                        emoji, trade.getOptionSymbol(),
                        decision.getTriggerType(), decision.getConfidence() * 100,
                        decision.getExitReason(),
                        brokerPos.getAvgCost(), exitPrice,
                        finalPnl,
                        finalPnl.divide(brokerPos.getAvgCost()
                                        .multiply(BigDecimal.valueOf(Math.abs(brokerPos.getQuantity()) * 100)),
                                2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                );

                telegramService.sendMessage(alertMessage);

                log.info("[ENHANCED][{}] ✅ Enhanced stop-loss exit completed - P&L: ${}",
                        checkId, finalPnl);

            } else {
                log.error("[ENHANCED][{}] Failed to place enhanced stop-loss order", checkId);
                telegramService.sendMessage("❌ FAILED to execute enhanced stop-loss: " + trade.getOptionSymbol());
            }

        } catch (Exception e) {
            log.error("[ENHANCED][{}] Error executing enhanced stop-loss: {}", checkId, e.getMessage());
            telegramService.sendMessage("❌ Enhanced stop-loss execution error: " + e.getMessage());
        }
    }

    private final ZeroDTEStrategy zeroDTEStrategy;
    public void updateAILearning(Trade trade) {
        try {
            if (trade.getSignalId() != null) {
                Signal originalSignal = signalRepository.findById(trade.getSignalId()).orElse(null);

                if (originalSignal != null && originalSignal.getStrategy().contains("AI_LEADER_LAG")) {
                    // 🤖 UPDATE AI LEARNING
                    zeroDTEStrategy.updateAIFromTrade(trade, originalSignal);

                    double pnlPercent = calculatePnLPercent(trade);
                    log.info("🤖 [AI-LEARNING] Updated from trade: {} P&L: {}%",
                            trade.getOptionSymbol(), String.format("%.2f", pnlPercent));
                }
            }
        } catch (Exception e) {
            log.error("Error updating AI learning: {}", e.getMessage());
        }
    }

    private double calculatePnLPercent(Trade trade) {
        if (trade.getExitPrice() != null && trade.getEntryPrice() != null) {
            return trade.getExitPrice().subtract(trade.getEntryPrice())
                    .divide(trade.getEntryPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        return 0.0;
    }

    // ADD THIS NEW METHOD FOR MANUAL STOP-LOSS ADJUSTMENTS:
    @Transactional
    public void adjustStopLoss(Long tradeId, BigDecimal newStop, String reason) {
        try {
            Trade trade = tradeRepository.findById(tradeId).orElse(null);
            if (trade == null) {
                log.error("Trade not found for stop-loss adjustment: {}", tradeId);
                return;
            }

            // Update database
            trade.setStopLoss(newStop);
            trade.setLastAdjustmentTime(LocalDateTime.now());
            tradeRepository.save(trade);

            // Update enhanced monitoring
            enhancedStopLossMonitor.adjustStopLoss(tradeId.toString(), newStop, reason);

            log.info("[MANUAL STOP] Trade {} - Stop adjusted to ${} (Reason: {})",
                    tradeId, newStop, reason);

            telegramService.sendMessage(String.format(
                    "🔧 Manual Stop-Loss Adjustment\n" +
                            "Trade: %s\n" +
                            "New Stop: $%.2f\n" +
                            "Reason: %s",
                    trade.getOptionSymbol(), newStop, reason));

        } catch (Exception e) {
            log.error("Error adjusting stop-loss: {}", e.getMessage());
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

                updateAILearning(trade);

                integratedBayesianMLSystem.closeTradeWithMLFeedback(trade, reason);
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