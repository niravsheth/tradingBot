package com.tradingBot.engine;

import com.tradingBot.entity.Signal;
import com.tradingBot.entity.PendingFlow;
import com.tradingBot.model.*;
import com.tradingBot.repository.*;
import com.tradingBot.service.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flow Confirmation Engine
 * Monitors unusual flow alerts and confirms them before generating signals
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FlowConfirmationEngine {

    private final TradierService tradierService;
    private final SignalRepository signalRepository;
    private final PendingFlowRepository pendingFlowRepository;
    private final TelegramService telegramService;
    private final TrendTrackingEngine trendEngine;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Map<String, FlowTracker> activeFlows = new ConcurrentHashMap<>();

    private ExecutorService executorService;

    // Confirmation parameters
    private static final int SWEEP_WINDOW_MINUTES = 15;
    private static final int BLOCK_WINDOW_MINUTES = 30;
    private static final int STANDARD_WINDOW_MINUTES = 20;

    private static final double SWEEP_REQUIRED_MOVE = 0.3;
    private static final double BLOCK_REQUIRED_MOVE = 0.5;
    private static final double STANDARD_REQUIRED_MOVE = 0.4;

    /**
     * Start flow confirmation engine
     */
    public void start() {
        if (isRunning.get()) {
            log.warn("Flow Confirmation Engine is already running");
            return;
        }

        log.info("Starting Flow Confirmation Engine...");
        isRunning.set(true);

        executorService = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("FlowConfirm-" + t.getId());
            return t;
        });

        // Load any pending flows from database
        loadPendingFlows();

        log.info("Flow Confirmation Engine started");
    }

    /**
     * Stop flow confirmation engine
     */
    public void stop() {
        log.info("Stopping Flow Confirmation Engine...");
        isRunning.set(false);

        if (executorService != null) {
            executorService.shutdown();
        }

        activeFlows.clear();
        log.info("Flow Confirmation Engine stopped");
    }

    /**
     * Load pending flows from database
     */
    private void loadPendingFlows() {
        try {
            List<PendingFlow> pendingFlows = pendingFlowRepository.findByStatus("PENDING");
            log.info("Loading {} pending flows from database", pendingFlows.size());

            for (PendingFlow flow : pendingFlows) {
                FlowTracker tracker = new FlowTracker();
                tracker.pendingFlow = flow;
                tracker.detectedAt = flow.getDetectedAt();
                tracker.expiresAt = flow.getDetectedAt().plusMinutes(flow.getConfirmationWindow());
                activeFlows.put(flow.getOptionSymbol(), tracker);
            }

        } catch (Exception e) {
            log.error("Error loading pending flows: {}", e.getMessage());
        }
    }

    /**
     * Create new flow alert
     */
    public void createFlowAlert(String symbol, String optionSymbol,
                                BigDecimal flowSize, String flowType,
                                BigDecimal optionPrice, BigDecimal underlyingPrice) {

        log.info("[FLOW-ALERT] Creating flow alert for {} - {} contracts",
                optionSymbol, flowSize);

        // Check if we're already tracking this option
        if (activeFlows.containsKey(optionSymbol)) {
            log.info("[FLOW-ALERT] Already tracking {}, updating volume", optionSymbol);
            FlowTracker existing = activeFlows.get(optionSymbol);
            existing.additionalVolume = existing.additionalVolume.add(flowSize);
            return;
        }

        // Create pending flow entity
        PendingFlow pendingFlow = new PendingFlow();
        pendingFlow.setSymbol(symbol);
        pendingFlow.setOptionSymbol(optionSymbol);
        pendingFlow.setFlowSize(flowSize);
        pendingFlow.setFlowType(flowType);
        pendingFlow.setDetectedAt(LocalDateTime.now());
        pendingFlow.setDetectionPrice(optionPrice);
        pendingFlow.setUnderlyingPrice(underlyingPrice);
        pendingFlow.setStatus("PENDING");

        // Set confirmation parameters based on flow type
        if ("SWEEP".equals(flowType)) {
            pendingFlow.setConfirmationWindow(SWEEP_WINDOW_MINUTES);
            pendingFlow.setRequiredMove(BigDecimal.valueOf(SWEEP_REQUIRED_MOVE));
        } else if (flowSize.compareTo(BigDecimal.valueOf(1000)) > 0) {
            pendingFlow.setConfirmationWindow(BLOCK_WINDOW_MINUTES);
            pendingFlow.setRequiredMove(BigDecimal.valueOf(BLOCK_REQUIRED_MOVE));
        } else {
            pendingFlow.setConfirmationWindow(STANDARD_WINDOW_MINUTES);
            pendingFlow.setRequiredMove(BigDecimal.valueOf(STANDARD_REQUIRED_MOVE));
        }

        // Save to database
        pendingFlowRepository.save(pendingFlow);

        // Create tracker
        FlowTracker tracker = new FlowTracker();
        tracker.pendingFlow = pendingFlow;
        tracker.detectedAt = LocalDateTime.now();
        tracker.expiresAt = LocalDateTime.now().plusMinutes(pendingFlow.getConfirmationWindow());
        tracker.initialPrice = optionPrice;
        tracker.lowPrice = optionPrice;
        tracker.highPrice = optionPrice;

        activeFlows.put(optionSymbol, tracker);

        // Send alert
        sendFlowDetectionAlert(pendingFlow);
    }

    /**
     * Check pending flows for confirmation (runs every minute)
     */
    @Scheduled(fixedDelay = 60000)
    public void checkPendingFlows() {
        if (!isRunning.get() || activeFlows.isEmpty()) {
            return;
        }

        log.debug("[FLOW-CHECK] Checking {} pending flows", activeFlows.size());

        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, FlowTracker> entry : activeFlows.entrySet()) {
            String optionSymbol = entry.getKey();
            FlowTracker tracker = entry.getValue();

            // Check in separate thread
            executorService.submit(() -> checkFlow(optionSymbol, tracker, toRemove));
        }

        // Remove expired/confirmed flows
        try {
            Thread.sleep(5000); // Wait for checks to complete
            for (String symbol : toRemove) {
                activeFlows.remove(symbol);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check individual flow for confirmation
     */
    private void checkFlow(String optionSymbol, FlowTracker tracker, List<String> toRemove) {
        PendingFlow flow = tracker.pendingFlow;

        try {
            // Check if expired
            if (LocalDateTime.now().isAfter(tracker.expiresAt)) {
                log.info("[FLOW-CHECK] {} expired without confirmation", optionSymbol);
                flow.setStatus("EXPIRED");
                flow.setConfirmationReason("Monitoring window expired");
                pendingFlowRepository.save(flow);
                toRemove.add(optionSymbol);
                return;
            }

            // Get current prices
            QuoteResponse optionQuote = tradierService.getQuote(optionSymbol);
            QuoteResponse underlyingQuote = tradierService.getQuote(flow.getSymbol());

            if (optionQuote == null || underlyingQuote == null ||
                    optionQuote.getQuote() == null || underlyingQuote.getQuote() == null) {
                log.warn("[FLOW-CHECK] Failed to get quotes for {}", optionSymbol);
                return;
            }

            BigDecimal currentOptionPrice = optionQuote.getQuote().getLast();
            BigDecimal currentUnderlyingPrice = underlyingQuote.getQuote().getLast();

            // Update price tracking
            tracker.updatePrices(currentOptionPrice);

            // Check for confirmation
            ConfirmationResult result = evaluateConfirmation(
                    flow, tracker, currentOptionPrice, currentUnderlyingPrice);

            if (result.isConfirmed) {
                log.info("[FLOW-CHECK] ✅ {} CONFIRMED - {}", optionSymbol, result.reason);
                handleConfirmedFlow(flow, tracker, currentOptionPrice, currentUnderlyingPrice);
                toRemove.add(optionSymbol);

            } else if (result.isInvalidated) {
                log.info("[FLOW-CHECK] ❌ {} INVALIDATED - {}", optionSymbol, result.reason);
                flow.setStatus("INVALIDATED");
                flow.setConfirmationReason(result.reason);
                pendingFlowRepository.save(flow);
                toRemove.add(optionSymbol);

            } else {
                // Still monitoring
                long minutesElapsed = java.time.Duration.between(
                        tracker.detectedAt, LocalDateTime.now()).toMinutes();
                log.debug("[FLOW-CHECK] {} monitoring {}/{} min - {}",
                        optionSymbol, minutesElapsed, flow.getConfirmationWindow(), result.reason);
            }

        } catch (Exception e) {
            log.error("[FLOW-CHECK] Error checking {}: {}", optionSymbol, e.getMessage());
        }
    }

    /**
     * Evaluate confirmation criteria
     */
    private ConfirmationResult evaluateConfirmation(PendingFlow flow, FlowTracker tracker,
                                                    BigDecimal currentOptionPrice,
                                                    BigDecimal currentUnderlyingPrice) {
        ConfirmationResult result = new ConfirmationResult();

        // Calculate underlying move
        BigDecimal underlyingMove = currentUnderlyingPrice
                .subtract(flow.getUnderlyingPrice())
                .divide(flow.getUnderlyingPrice(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // Calculate option recovery from low
        BigDecimal optionRecovery = currentOptionPrice
                .subtract(tracker.lowPrice)
                .divide(tracker.lowPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // Check direction
        boolean isCall = flow.getOptionSymbol().contains("C");
        boolean rightDirection = (isCall && underlyingMove.compareTo(BigDecimal.ZERO) > 0) ||
                (!isCall && underlyingMove.compareTo(BigDecimal.ZERO) < 0);

        // Check if underlying moved enough
        boolean sufficientMove = underlyingMove.abs()
                .compareTo(flow.getRequiredMove()) >= 0;

        // Check if option recovered from reversal
        boolean optionRecovered = currentOptionPrice
                .compareTo(flow.getDetectionPrice().multiply(BigDecimal.valueOf(0.95))) > 0;

        // Check if reversal pattern completed (for large flows)
        boolean reversalComplete = true;
        if (flow.getFlowSize().compareTo(BigDecimal.valueOf(1000)) > 0) {
            reversalComplete = tracker.hasCompletedReversal(currentOptionPrice);
        }

        // Evaluate confirmation
        if (rightDirection && sufficientMove && optionRecovered && reversalComplete) {
            result.isConfirmed = true;
            result.reason = String.format(
                    "Underlying moved %.2f%% in expected direction, option recovered %.1f%% from low",
                    underlyingMove, optionRecovery);

        } else if (!rightDirection && underlyingMove.abs().compareTo(BigDecimal.valueOf(0.5)) > 0) {
            result.isInvalidated = true;
            result.reason = "Underlying moved opposite to flow direction";

        } else if (currentOptionPrice.compareTo(
                flow.getDetectionPrice().multiply(BigDecimal.valueOf(0.70))) < 0) {
            result.isInvalidated = true;
            result.reason = "Option price fell too much (-30%)";

        } else {
            // Still waiting
            List<String> waiting = new ArrayList<>();
            if (!rightDirection) waiting.add("wrong direction");
            if (!sufficientMove) waiting.add(String.format("need %.1f%% move",
                    flow.getRequiredMove().subtract(underlyingMove.abs())));
            if (!reversalComplete) waiting.add("reversal incomplete");

            result.reason = "Waiting: " + String.join(", ", waiting);
        }

        return result;
    }

    /**
     * Handle confirmed flow
     */
    private void handleConfirmedFlow(PendingFlow flow, FlowTracker tracker,
                                     BigDecimal currentOptionPrice,
                                     BigDecimal currentUnderlyingPrice) {
        // Update flow status
        flow.setStatus("CONFIRMED");
        flow.setConfirmedAt(LocalDateTime.now());
        flow.setConfirmationPrice(currentOptionPrice);
        flow.setConfirmationReason("Price action confirmed flow direction");
        pendingFlowRepository.save(flow);

        // Create trading signal
        Signal signal = new Signal();
        signal.setSymbol(flow.getSymbol());
        signal.setOptionSymbol(flow.getOptionSymbol());
        signal.setSignalType("BUY");
        signal.setStrategy("UNUSUAL_FLOW_CONFIRMED");
        signal.setOriginalOptionPrice(currentOptionPrice);
        signal.setEntryAssumptionPrice(currentUnderlyingPrice);

        // Calculate confidence based on confirmation strength
        BigDecimal maxDrawdown = flow.getDetectionPrice()
                .subtract(tracker.lowPrice)
                .divide(flow.getDetectionPrice(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // Set confidence and exits based on drawdown
        if (maxDrawdown.compareTo(BigDecimal.valueOf(10)) < 0) {
            // Minimal reversal - very strong signal
            signal.setConfidence(0.92);
            signal.setStopLoss(currentOptionPrice.multiply(BigDecimal.valueOf(0.80)));
            signal.setTargetPrice(currentOptionPrice.multiply(BigDecimal.valueOf(1.70)));

        } else if (maxDrawdown.compareTo(BigDecimal.valueOf(20)) < 0) {
            // Moderate reversal
            signal.setConfidence(0.87);
            signal.setStopLoss(currentOptionPrice.multiply(BigDecimal.valueOf(0.75)));
            signal.setTargetPrice(currentOptionPrice.multiply(BigDecimal.valueOf(1.55)));

        } else {
            // Large reversal completed
            signal.setConfidence(0.82);
            signal.setStopLoss(currentOptionPrice.multiply(BigDecimal.valueOf(0.70)));
            signal.setTargetPrice(currentOptionPrice.multiply(BigDecimal.valueOf(1.45)));
        }

        // Set notes
        signal.setNotes(String.format(
                "Flow confirmed after %.0f min. Original: %s contracts @ $%.2f. Max drawdown: %.1f%%",
                java.time.Duration.between(flow.getDetectedAt(), LocalDateTime.now()).toMinutes(),
                flow.getFlowSize(),
                flow.getDetectionPrice(),
                maxDrawdown
        ));

        signal.setReason(String.format(
                "Unusual %s flow of %s contracts confirmed by price action",
                flow.getFlowType(), flow.getFlowSize()
        ));

        // Short expiration since we already waited
        signal.setCreatedAt(LocalDateTime.now());
        signal.setExpirationTime(LocalDateTime.now().plusMinutes(10));
        signal.setPriority(95); // High priority
        signal.setGeneratedBy("FLOW_CONFIRMATION");

        signalRepository.save(signal);

        // Send confirmation alert
        sendConfirmationAlert(flow, signal, tracker);

        log.info("[FLOW-CONFIRM] Signal created for {} with {}% confidence",
                signal.getOptionSymbol(), (int)(signal.getConfidence() * 100));
    }

    /**
     * Send flow detection alert
     */
    private void sendFlowDetectionAlert(PendingFlow flow) {
        String message = String.format(
                "🔍 <b>UNUSUAL FLOW DETECTED</b>\n" +
                        "Option: %s\n" +
                        "Type: %s flow\n" +
                        "Size: %s contracts\n" +
                        "Price: $%.2f\n" +
                        "Underlying: %s @ $%.2f\n" +
                        "Action: Monitoring for %d minutes\n" +
                        "Required Move: %.1f%%\n" +
                        "Status: Waiting for confirmation...",
                flow.getOptionSymbol(),
                flow.getFlowType(),
                flow.getFlowSize(),
                flow.getDetectionPrice(),
                flow.getSymbol(),
                flow.getUnderlyingPrice(),
                flow.getConfirmationWindow(),
                flow.getRequiredMove()
        );

        telegramService.sendMessage(message);
    }

    /**
     * Send confirmation alert
     */
    private void sendConfirmationAlert(PendingFlow flow, Signal signal, FlowTracker tracker) {
        long monitoringMinutes = java.time.Duration.between(
                flow.getDetectedAt(), LocalDateTime.now()).toMinutes();

        BigDecimal maxDrawdown = flow.getDetectionPrice()
                .subtract(tracker.lowPrice)
                .divide(flow.getDetectionPrice(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        String message = String.format(
                "✅ <b>FLOW CONFIRMED - SIGNAL GENERATED</b>\n" +
                        "Option: %s\n" +
                        "Original Flow: %s contracts @ $%.2f\n" +
                        "Monitoring: %d minutes\n" +
                        "Max Drawdown: %.1f%% (Low: $%.2f)\n" +
                        "Entry Price: $%.2f\n" +
                        "Stop Loss: $%.2f (-%.0f%%)\n" +
                        "Target: $%.2f (+%.0f%%)\n" +
                        "Confidence: %d%%\n" +
                        "Additional Volume: %s contracts",
                signal.getOptionSymbol(),
                flow.getFlowSize(),
                flow.getDetectionPrice(),
                monitoringMinutes,
                maxDrawdown,
                tracker.lowPrice,
                signal.getOriginalOptionPrice(),
                signal.getStopLoss(),
                (1 - signal.getStopLoss().divide(signal.getOriginalOptionPrice(),
                        4, RoundingMode.HALF_UP).doubleValue()) * 100,
                signal.getTargetPrice(),
                (signal.getTargetPrice().divide(signal.getOriginalOptionPrice(),
                        4, RoundingMode.HALF_UP).doubleValue() - 1) * 100,
                (int)(signal.getConfidence() * 100),
                tracker.additionalVolume
        );

        telegramService.sendMessage(message);
    }

    /**
     * Get active flow summary
     */
    public String getActiveFlowSummary() {
        if (activeFlows.isEmpty()) {
            return "No active flow alerts";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("📊 <b>ACTIVE FLOW ALERTS</b>\n\n");

        for (Map.Entry<String, FlowTracker> entry : activeFlows.entrySet()) {
            FlowTracker tracker = entry.getValue();
            PendingFlow flow = tracker.pendingFlow;

            long elapsed = java.time.Duration.between(
                    tracker.detectedAt, LocalDateTime.now()).toMinutes();
            long remaining = flow.getConfirmationWindow() - elapsed;

            summary.append(String.format(
                    "%s - %s contracts\n" +
                            "⏱ %d/%d min | Expires in %d min\n" +
                            "📉 Low: $%.2f | Current: tracking...\n\n",
                    flow.getOptionSymbol(),
                    flow.getFlowSize(),
                    elapsed,
                    flow.getConfirmationWindow(),
                    remaining,
                    tracker.lowPrice
            ));
        }

        return summary.toString();
    }

    /**
     * Flow tracker
     */
    @Data
    private static class FlowTracker {
        PendingFlow pendingFlow;
        LocalDateTime detectedAt;
        LocalDateTime expiresAt;
        BigDecimal initialPrice;
        BigDecimal lowPrice;
        BigDecimal highPrice;
        BigDecimal additionalVolume = BigDecimal.ZERO;
        boolean hasReversed = false;

        void updatePrices(BigDecimal currentPrice) {
            if (currentPrice.compareTo(lowPrice) < 0) {
                lowPrice = currentPrice;
                hasReversed = true;
            }
            if (currentPrice.compareTo(highPrice) > 0) {
                highPrice = currentPrice;
            }
        }

        boolean hasCompletedReversal(BigDecimal currentPrice) {
            if (!hasReversed) {
                return true;
            }

            // Consider reversal complete if recovered 50% from low
            BigDecimal recovery = currentPrice.subtract(lowPrice);
            BigDecimal totalDrop = initialPrice.subtract(lowPrice);

            if (totalDrop.compareTo(BigDecimal.ZERO) <= 0) {
                return true;
            }

            BigDecimal recoveryPercent = recovery.divide(totalDrop, 2, RoundingMode.HALF_UP);
            return recoveryPercent.compareTo(BigDecimal.valueOf(0.50)) >= 0;
        }
    }

    /**
     * Confirmation result
     */
    @Data
    private static class ConfirmationResult {
        boolean isConfirmed = false;
        boolean isInvalidated = false;
        String reason;
    }
}