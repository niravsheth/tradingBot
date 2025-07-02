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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
    private final Map<String, List<FlowEvent>> flowHistory = new ConcurrentHashMap<>();

    private ExecutorService executorService;

    // STRICTER confirmation parameters
    private static final int MIN_FLOW_EVENTS = 3;  // Need at least 3 flow events
    private static final int FLOW_WINDOW_MINUTES = 5;  // Within 5 minutes
    private static final BigDecimal MIN_TOTAL_VOLUME = new BigDecimal("5000");  // Min 5000 contracts total
    private static final double MIN_FLOW_RATIO = 0.70;  // 70% of flow must be in same direction

    // Price movement requirements
    private static final double MIN_UNDERLYING_MOVE = 0.15;  // 0.15% move in underlying
    private static final double MIN_OPTION_MOVE = 5.0;  // 5% move in option price
    private static final int PRICE_CONFIRMATION_MINUTES = 3;  // Wait 3 minutes for price confirmation

    public void start() {
        if (isRunning.get()) {
            log.warn("Flow Confirmation Engine is already running");
            return;
        }

        log.info("Starting Flow Confirmation Engine with STRICT confirmation rules...");
        isRunning.set(true);

        executorService = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("FlowConfirm-" + t.getId());
            return t;
        });

        loadPendingFlows();
        log.info("Flow Confirmation Engine started");
    }

    public void stop() {
        log.info("Stopping Flow Confirmation Engine...");
        isRunning.set(false);

        if (executorService != null) {
            executorService.shutdown();
        }

        activeFlows.clear();
        flowHistory.clear();
        log.info("Flow Confirmation Engine stopped");
    }

    /**
     * Record new flow event (called by your flow detection logic)
     */
    public void recordFlowEvent(String symbol, String optionSymbol,
                                BigDecimal volume, String type,
                                BigDecimal optionPrice, BigDecimal underlyingPrice,
                                boolean isCall) {

        log.info("[FLOW-EVENT] {} - {} contracts @ ${} ({})",
                optionSymbol, volume, optionPrice, type);

        // Create flow event
        FlowEvent event = new FlowEvent();
        event.timestamp = LocalDateTime.now();
        event.volume = volume;
        event.type = type;
        event.optionPrice = optionPrice;
        event.underlyingPrice = underlyingPrice;
        event.isCall = isCall;

        // Add to history
        flowHistory.computeIfAbsent(optionSymbol, k -> new ArrayList<>()).add(event);

        // Clean old events
        cleanOldFlowEvents(optionSymbol);

        // Check if we should start tracking this option
        checkForFlowPattern(symbol, optionSymbol);
    }

    /**
     * Check if flow pattern warrants tracking
     */
    private void checkForFlowPattern(String symbol, String optionSymbol) {
        List<FlowEvent> events = flowHistory.getOrDefault(optionSymbol, new ArrayList<>());

        // Remove old events
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(FLOW_WINDOW_MINUTES);
        events = events.stream()
                .filter(e -> e.timestamp.isAfter(cutoff))
                .collect(Collectors.toList());

        if (events.size() < MIN_FLOW_EVENTS) {
            log.debug("[FLOW-PATTERN] {} - Only {} events, need {}",
                    optionSymbol, events.size(), MIN_FLOW_EVENTS);
            return;
        }

        // Calculate total volume
        BigDecimal totalVolume = events.stream()
                .map(e -> e.volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalVolume.compareTo(MIN_TOTAL_VOLUME) < 0) {
            log.debug("[FLOW-PATTERN] {} - Volume {} < minimum {}",
                    optionSymbol, totalVolume, MIN_TOTAL_VOLUME);
            return;
        }

        // Check flow consistency (are they mostly buys or mostly sells?)
        long buyCount = events.stream()
                .filter(e -> "BUY".equals(e.type) || "SWEEP".equals(e.type))
                .count();
        double buyRatio = (double) buyCount / events.size();

        if (buyRatio < MIN_FLOW_RATIO && buyRatio > (1 - MIN_FLOW_RATIO)) {
            log.info("[FLOW-PATTERN] {} - Mixed flow detected ({}% buys), skipping",
                    optionSymbol, (int)(buyRatio * 100));
            return;
        }

        // Determine flow direction
        boolean isBullishFlow = buyRatio >= MIN_FLOW_RATIO;

        // Verify flow matches option type
        boolean isCall = events.get(0).isCall;
        if ((isBullishFlow && !isCall) || (!isBullishFlow && isCall)) {
            log.warn("[FLOW-PATTERN] {} - Flow direction mismatch! {} flow on {} option",
                    optionSymbol, isBullishFlow ? "Bullish" : "Bearish", isCall ? "CALL" : "PUT");
            return;
        }

        // Pattern detected! Start tracking
        if (!activeFlows.containsKey(optionSymbol)) {
            startTrackingFlow(symbol, optionSymbol, events, totalVolume, isBullishFlow);
        }
    }

    /**
     * Start tracking confirmed flow pattern
     */
    private void startTrackingFlow(String symbol, String optionSymbol,
                                   List<FlowEvent> events, BigDecimal totalVolume,
                                   boolean isBullishFlow) {

        FlowEvent latestEvent = events.get(events.size() - 1);

        // Create pending flow
        PendingFlow pendingFlow = new PendingFlow();
        pendingFlow.setSymbol(symbol);
        pendingFlow.setOptionSymbol(optionSymbol);
        pendingFlow.setFlowSize(totalVolume);
        pendingFlow.setFlowType(isBullishFlow ? "BULLISH_FLOW" : "BEARISH_FLOW");
        pendingFlow.setDetectedAt(LocalDateTime.now());
        pendingFlow.setDetectionPrice(latestEvent.optionPrice);
        pendingFlow.setUnderlyingPrice(latestEvent.underlyingPrice);
        pendingFlow.setStatus("TRACKING");
        pendingFlow.setConfirmationWindow(PRICE_CONFIRMATION_MINUTES);
        pendingFlow.setRequiredMove(BigDecimal.valueOf(MIN_UNDERLYING_MOVE));

        pendingFlowRepository.save(pendingFlow);

        // Create tracker
        FlowTracker tracker = new FlowTracker();
        tracker.pendingFlow = pendingFlow;
        tracker.flowEvents = new ArrayList<>(events);
        tracker.detectedAt = LocalDateTime.now();
        tracker.expiresAt = LocalDateTime.now().plusMinutes(10);  // 10 minute total window
        tracker.initialOptionPrice = latestEvent.optionPrice;
        tracker.initialUnderlyingPrice = latestEvent.underlyingPrice;
        tracker.isBullishFlow = isBullishFlow;
        tracker.confirmationStartTime = LocalDateTime.now();

        activeFlows.put(optionSymbol, tracker);

        sendFlowPatternAlert(pendingFlow, events.size());
    }

    /**
     * Monitor tracked flows for price confirmation
     */
    @Scheduled(fixedDelay = 30000)  // Check every 30 seconds
    public void checkTrackedFlows() {
        if (!isRunning.get() || activeFlows.isEmpty()) {
            return;
        }

        log.debug("[FLOW-CHECK] Monitoring {} active flow patterns", activeFlows.size());

        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, FlowTracker> entry : activeFlows.entrySet()) {
            String optionSymbol = entry.getKey();
            FlowTracker tracker = entry.getValue();

            executorService.submit(() -> checkFlowConfirmation(optionSymbol, tracker, toRemove));
        }

        // Clean up
        try {
            Thread.sleep(5000);
            for (String symbol : toRemove) {
                activeFlows.remove(symbol);
                flowHistory.remove(symbol);  // Clean history too
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check if flow is confirmed by price action
     */
    private void checkFlowConfirmation(String optionSymbol, FlowTracker tracker, List<String> toRemove) {
        PendingFlow flow = tracker.pendingFlow;

        try {
            // Check expiration
            if (LocalDateTime.now().isAfter(tracker.expiresAt)) {
                log.info("[FLOW-CHECK] {} expired without sufficient confirmation", optionSymbol);
                flow.setStatus("EXPIRED");
                flow.setConfirmationReason("No sustained price movement");
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

            // Check for additional flow
            checkForAdditionalFlow(optionSymbol, tracker);

            // Evaluate confirmation
            ConfirmationResult result = evaluateStrictConfirmation(
                    tracker, currentOptionPrice, currentUnderlyingPrice);

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
                log.debug("[FLOW-CHECK] {} still tracking - {}", optionSymbol, result.reason);
            }

        } catch (Exception e) {
            log.error("[FLOW-CHECK] Error checking {}: {}", optionSymbol, e.getMessage());
        }
    }

    /**
     * Check for additional flow in same direction
     */
    private void checkForAdditionalFlow(String optionSymbol, FlowTracker tracker) {
        List<FlowEvent> recentEvents = flowHistory.getOrDefault(optionSymbol, new ArrayList<>());

        // Count events since we started tracking
        long newEventCount = recentEvents.stream()
                .filter(e -> e.timestamp.isAfter(tracker.confirmationStartTime))
                .count();

        tracker.additionalFlowCount = (int) newEventCount;

        if (newEventCount > 0) {
            BigDecimal additionalVolume = recentEvents.stream()
                    .filter(e -> e.timestamp.isAfter(tracker.confirmationStartTime))
                    .map(e -> e.volume)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            tracker.additionalVolume = additionalVolume;
            log.info("[FLOW-CHECK] {} - {} additional flow events, {} contracts",
                    optionSymbol, newEventCount, additionalVolume);
        }
    }

    /**
     * Strict confirmation evaluation
     */
    private ConfirmationResult evaluateStrictConfirmation(FlowTracker tracker,
                                                          BigDecimal currentOptionPrice,
                                                          BigDecimal currentUnderlyingPrice) {
        ConfirmationResult result = new ConfirmationResult();

        // Calculate price movements
        BigDecimal underlyingMove = currentUnderlyingPrice
                .subtract(tracker.initialUnderlyingPrice)
                .divide(tracker.initialUnderlyingPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal optionMove = currentOptionPrice
                .subtract(tracker.initialOptionPrice)
                .divide(tracker.initialOptionPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // Check if moves are in expected direction
        boolean correctDirection = tracker.isBullishFlow ?
                (underlyingMove.doubleValue() > 0 && optionMove.doubleValue() > 0) :
                (underlyingMove.doubleValue() < 0 && optionMove.doubleValue() < 0);

        // Time since detection
        long minutesSinceDetection = java.time.Duration.between(
                tracker.detectedAt, LocalDateTime.now()).toMinutes();

        // STRICT CONFIRMATION CRITERIA
        boolean sufficientUnderlyingMove = Math.abs(underlyingMove.doubleValue()) >= MIN_UNDERLYING_MOVE;
        boolean sufficientOptionMove = Math.abs(optionMove.doubleValue()) >= MIN_OPTION_MOVE;
        boolean hasAdditionalFlow = tracker.additionalFlowCount > 0;
        boolean sufficientTime = minutesSinceDetection >= PRICE_CONFIRMATION_MINUTES;

        // Need ALL conditions for confirmation
        if (correctDirection && sufficientUnderlyingMove && sufficientOptionMove &&
                hasAdditionalFlow && sufficientTime) {

            result.isConfirmed = true;
            result.reason = String.format(
                    "Sustained flow confirmed: Underlying %.2f%%, Option %.2f%%, %d additional flows",
                    underlyingMove, optionMove, tracker.additionalFlowCount);

        } else if (!correctDirection && Math.abs(underlyingMove.doubleValue()) > 0.2) {
            result.isInvalidated = true;
            result.reason = "Price moved opposite to flow direction";

        } else if (minutesSinceDetection > 5 && !hasAdditionalFlow) {
            result.isInvalidated = true;
            result.reason = "No follow-through flow detected";

        } else if (minutesSinceDetection > 7 && !sufficientOptionMove) {
            result.isInvalidated = true;
            result.reason = "Insufficient option price movement after 7 minutes";

        } else {
            // Still waiting
            List<String> waiting = new ArrayList<>();
            if (!correctDirection) waiting.add("waiting for direction");
            if (!sufficientUnderlyingMove) waiting.add(String.format("need %.2f%% underlying move",
                    MIN_UNDERLYING_MOVE - Math.abs(underlyingMove.doubleValue())));
            if (!sufficientOptionMove) waiting.add(String.format("need %.0f%% option move",
                    MIN_OPTION_MOVE - Math.abs(optionMove.doubleValue())));
            if (!hasAdditionalFlow) waiting.add("waiting for additional flow");
            if (!sufficientTime) waiting.add(String.format("wait %d more minutes",
                    PRICE_CONFIRMATION_MINUTES - minutesSinceDetection));

            result.reason = "Tracking: " + String.join(", ", waiting);
        }

        return result;
    }

    /**
     * Handle confirmed flow with conservative signal generation
     */
    private void handleConfirmedFlow(PendingFlow flow, FlowTracker tracker,
                                     BigDecimal currentOptionPrice,
                                     BigDecimal currentUnderlyingPrice) {
        // Update flow status
        flow.setStatus("CONFIRMED");
        flow.setConfirmedAt(LocalDateTime.now());
        flow.setConfirmationPrice(currentOptionPrice);
        flow.setConfirmationReason("Sustained flow with price confirmation");
        pendingFlowRepository.save(flow);

        // Create trading signal with CONSERVATIVE parameters
        Signal signal = new Signal();
        signal.setSymbol(flow.getSymbol());
        signal.setOptionSymbol(flow.getOptionSymbol());
        signal.setSignalType("BUY");
        signal.setStrategy("UNUSUAL_FLOW_CONFIRMED");
        signal.setOriginalOptionPrice(currentOptionPrice);
        signal.setEntryAssumptionPrice(currentUnderlyingPrice);

        // Conservative confidence based on confirmation strength
        double confidence = 0.70;  // Base confidence
        if (tracker.additionalFlowCount >= 3) confidence += 0.10;
        if (tracker.additionalVolume.compareTo(new BigDecimal("10000")) > 0) confidence += 0.05;

        signal.setConfidence(Math.min(confidence, 0.85));  // Cap at 85%

        // TIGHTER stops and CONSERVATIVE targets
        signal.setStopLoss(currentOptionPrice.multiply(BigDecimal.valueOf(0.85)));  // 15% stop
        signal.setTargetPrice(currentOptionPrice.multiply(BigDecimal.valueOf(1.30)));  // 30% target

        // Detailed notes
        signal.setNotes(String.format(
                "Confirmed after %d flow events, %.0f total contracts. Additional flow: %d events, %s contracts",
                tracker.flowEvents.size(),
                flow.getFlowSize(),
                tracker.additionalFlowCount,
                tracker.additionalVolume
        ));

        signal.setReason(String.format(
                "Sustained %s unusual flow pattern confirmed by price action",
                tracker.isBullishFlow ? "bullish" : "bearish"
        ));

        // Very short expiration - enter quickly or skip
        signal.setCreatedAt(LocalDateTime.now());
        signal.setExpirationTime(LocalDateTime.now().plusMinutes(5));
        signal.setStatus("PENDING");
        signal.setExecuted(false);

        signalRepository.save(signal);

        sendConfirmationAlert(flow, signal, tracker);

        log.info("[FLOW-CONFIRM] Conservative signal created for {} with {}% confidence",
                signal.getOptionSymbol(), (int)(signal.getConfidence() * 100));
    }

    /**
     * Send flow pattern detection alert
     */
    private void sendFlowPatternAlert(PendingFlow flow, int eventCount) {
        String message = String.format(
                "🔍 <b>FLOW PATTERN DETECTED</b>\n" +
                        "Option: %s\n" +
                        "Pattern: %s\n" +
                        "Events: %d in %d minutes\n" +
                        "Total Volume: %s contracts\n" +
                        "Initial Price: $%.2f\n" +
                        "Underlying: %s @ $%.2f\n" +
                        "Status: Monitoring for price confirmation...\n" +
                        "⚠️ Waiting for sustained movement and additional flow",
                flow.getOptionSymbol(),
                flow.getFlowType(),
                eventCount,
                FLOW_WINDOW_MINUTES,
                flow.getFlowSize(),
                flow.getDetectionPrice(),
                flow.getSymbol(),
                flow.getUnderlyingPrice()
        );

        telegramService.sendMessage(message);
    }

    /**
     * Send confirmation alert
     */
    private void sendConfirmationAlert(PendingFlow flow, Signal signal, FlowTracker tracker) {
        long monitoringMinutes = java.time.Duration.between(
                flow.getDetectedAt(), LocalDateTime.now()).toMinutes();

        String message = String.format(
                "✅ <b>FLOW CONFIRMED - SIGNAL GENERATED</b>\n" +
                        "Option: %s\n" +
                        "Initial Flow: %d events, %s contracts\n" +
                        "Additional Flow: %d events, %s contracts\n" +
                        "Monitoring Time: %d minutes\n" +
                        "Entry: $%.2f\n" +
                        "Stop: $%.2f (-15%%)\n" +
                        "Target: $%.2f (+30%%)\n" +
                        "Confidence: %d%%\n" +
                        "⚠️ Conservative position sizing recommended",
                signal.getOptionSymbol(),
                tracker.flowEvents.size(),
                flow.getFlowSize(),
                tracker.additionalFlowCount,
                tracker.additionalVolume,
                monitoringMinutes,
                signal.getOriginalOptionPrice(),
                signal.getStopLoss(),
                signal.getTargetPrice(),
                (int)(signal.getConfidence() * 100)
        );

        telegramService.sendMessage(message);
    }

    /**
     * Clean old flow events
     */
    private void cleanOldFlowEvents(String optionSymbol) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);  // Keep 30 minutes of history

        List<FlowEvent> events = flowHistory.get(optionSymbol);
        if (events != null) {
            events.removeIf(e -> e.timestamp.isBefore(cutoff));
            if (events.isEmpty()) {
                flowHistory.remove(optionSymbol);
            }
        }
    }

    /**
     * Flow event data
     */
    @Data
    private static class FlowEvent {
        LocalDateTime timestamp;
        BigDecimal volume;
        String type;  // BUY, SELL, SWEEP
        BigDecimal optionPrice;
        BigDecimal underlyingPrice;
        boolean isCall;
    }

    /**
     * Enhanced flow tracker
     */
    @Data
    private static class FlowTracker {
        PendingFlow pendingFlow;
        List<FlowEvent> flowEvents;
        LocalDateTime detectedAt;
        LocalDateTime expiresAt;
        LocalDateTime confirmationStartTime;
        BigDecimal initialOptionPrice;
        BigDecimal initialUnderlyingPrice;
        boolean isBullishFlow;
        int additionalFlowCount = 0;
        BigDecimal additionalVolume = BigDecimal.ZERO;
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

    /**
     * Get active flow summary
     */
    public String getActiveFlowSummary() {
        if (activeFlows.isEmpty()) {
            return "No active flow patterns being tracked";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("📊 <b>ACTIVE FLOW TRACKING</b>\n\n");

        for (Map.Entry<String, FlowTracker> entry : activeFlows.entrySet()) {
            FlowTracker tracker = entry.getValue();
            PendingFlow flow = tracker.pendingFlow;

            long elapsed = java.time.Duration.between(
                    tracker.detectedAt, LocalDateTime.now()).toMinutes();

            summary.append(String.format(
                    "%s - %s flow\n" +
                            "Events: %d initial + %d additional\n" +
                            "Volume: %s + %s contracts\n" +
                            "Tracking: %d minutes\n\n",
                    flow.getOptionSymbol(),
                    tracker.isBullishFlow ? "BULLISH" : "BEARISH",
                    tracker.flowEvents.size(),
                    tracker.additionalFlowCount,
                    flow.getFlowSize(),
                    tracker.additionalVolume,
                    elapsed
            ));
        }

        return summary.toString();
    }

    /**
     * Load pending flows from database on startup
     */
    private void loadPendingFlows() {
        try {
            // Load flows that are still in TRACKING status
            List<PendingFlow> pendingFlows = pendingFlowRepository.findByStatus("TRACKING");
            log.info("Loading {} pending flows from database", pendingFlows.size());

            for (PendingFlow flow : pendingFlows) {
                // Check if flow is still valid (not too old)
                if (flow.getDetectedAt().isAfter(LocalDateTime.now().minusMinutes(30))) {
                    // Recreate tracker
                    FlowTracker tracker = new FlowTracker();
                    tracker.pendingFlow = flow;
                    tracker.detectedAt = flow.getDetectedAt();
                    tracker.expiresAt = flow.getDetectedAt().plusMinutes(10);
                    tracker.initialOptionPrice = flow.getDetectionPrice();
                    tracker.initialUnderlyingPrice = flow.getUnderlyingPrice();
                    tracker.confirmationStartTime = flow.getDetectedAt();
                    tracker.isBullishFlow = "BULLISH_FLOW".equals(flow.getFlowType());
                    tracker.flowEvents = new ArrayList<>(); // We lost the detailed events, but can continue tracking

                    activeFlows.put(flow.getOptionSymbol(), tracker);

                    log.info("Resumed tracking for {} detected at {}",
                            flow.getOptionSymbol(), flow.getDetectedAt());
                } else {
                    // Expire old flows
                    flow.setStatus("EXPIRED");
                    flow.setConfirmationReason("System restart - flow too old");
                    pendingFlowRepository.save(flow);

                    log.info("Expired old flow {} from {}",
                            flow.getOptionSymbol(), flow.getDetectedAt());
                }
            }

        } catch (Exception e) {
            log.error("Error loading pending flows: {}", e.getMessage());
        }
    }
}