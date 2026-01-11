package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.entity.Trade;
import com.tradingBot.model.*;
import com.tradingBot.repository.TradeRepository;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnhancedStopLossMonitor {

    private final TradierService tradierService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final TradeRepository tradeRepository;
    private final MarketDataRepository marketDataRepository;
    private final TelegramService telegramService;
    private final StopLossConfiguration config;

    // Performance monitoring
    private final LongAdder totalEvaluations = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();
    private final AtomicLong maxLatencyNanos = new AtomicLong(0);

    // Leader consensus cache
    private final Map<String, LeaderConsensusState> leaderConsensusCache = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreakerState> circuitBreakerStates = new ConcurrentHashMap<>();

    // Leader stocks and weights
    private static final List<String> LEADER_STOCKS = Arrays.asList("AAPL", "MSFT", "NVDA");
    private static final Map<String, Double> LEADER_WEIGHTS = new HashMap<String, Double>() {{
        put("NVDA", 0.5);  // Strongest correlation to QQQ
        put("MSFT", 0.3);
        put("AAPL", 0.2);
    }};

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    @Data
    @Component
    @ConfigurationProperties(prefix = "trading.stoploss")
    public static class StopLossConfiguration {
        private double leaderReversalThreshold = 0.003; // 0.3% threshold for meaningful reversal
        private double dovishWeakeningThreshold = 0.5; // 50% momentum reduction
        private int cautionPersistenceMinutes = 5; // Minimum caution time
        private int exitPersistenceMinutes = 8; // Minimum exit persistence
        private int primaryLeaderExitMinutes = 10; // NVDA persistence requirement
        private double thetaProtectionThreshold = 0.60; // 60% decay protection
        private long latencyThresholdNanos = 1_000_000; // 1ms
        private int maxCircuitBreakerFailures = 5;
        private long circuitBreakerTimeoutMs = 60_000; // 1 minute
        private boolean enablePerformanceLogging = true;
        private boolean enableDetailedDecisionLogging = true;
    }

    @Data
    public static class LeaderConsensusState {
        private String tradeId;
        private LocalDateTime entryTime;
        private Map<String, Double> entryLeaderMomentum = new HashMap<>(); // MSFT: 0.06, NVDA: 0.08, etc.
        private Map<String, String> entryLeaderDirection = new HashMap<>(); // MSFT: BULLISH, etc.
        private boolean isCallPosition;
        private String consensusDirection; // BULLISH or BEARISH
        private double weightedConsensusScore;
        private LocalDateTime lastUpdate;
        private Map<String, LocalDateTime> leaderReversalTimes = new HashMap<>();
        private boolean cautionTriggered = false;
        private String cautionReason;
    }

    @Data
    public static class CircuitBreakerState {
        private String symbol;
        private int failureCount;
        private int successCount;
        private LocalDateTime lastFailureTime;
        private LocalDateTime nextAttemptTime;
        private boolean isOpen;
        private String lastErrorType;
    }

    @Data
    public static class StopLossDecision {
        private String decisionId;
        private boolean shouldExit;
        private String exitReason;
        private BigDecimal recommendedExitPrice;
        private String triggerType; // LEADER_CONSENSUS_BREAKDOWN, THETA_PROTECTION
        private double confidence;
        private LocalDateTime timestamp;
        private Map<String, Object> decisionFactors = new HashMap<>();
        private List<String> decisionPath = new ArrayList<>();
        private Map<String, Double> riskMetrics = new HashMap<>();
    }

    @Data
    private static class LeaderConsensusBreakdown {
        private final boolean shouldExit;
        private final boolean cautionTriggered;
        private final String reason;
        private final String breakdownType;
        private final double confidence;
        private final List<String> reversedLeaders;
        private final List<String> dovishLeaders;
        private final double currentConsensusScore;
        private final long persistenceMinutes;
    }

    @Transactional
    public StopLossDecision evaluateStopLoss(Trade trade) {
        String tradeId = trade.getId().toString();
        long startTime = System.nanoTime();

        StopLossDecision decision = new StopLossDecision();
        decision.setDecisionId(UUID.randomUUID().toString());
        decision.setTimestamp(LocalDateTime.now());
        decision.setShouldExit(false);
        decision.setConfidence(0.0);

        if (!isMarketOpen()) {
            return createSkipDecision("Market closed");
        }

        try {
            log.info("Leader consensus evaluation initiated for trade {}", tradeId);

            // Get or create leader consensus state
            LeaderConsensusState consensusState = leaderConsensusCache.computeIfAbsent(tradeId,
                    k -> initializeLeaderConsensusState(trade));

            // Update current leader positions
            updateCurrentLeaderPositions(consensusState);

            // Check for leader consensus breakdown
            LeaderConsensusBreakdown breakdown = checkLeaderConsensusBreakdown(consensusState, trade);

            if (breakdown.isShouldExit()) {
                decision.setShouldExit(true);
                decision.setExitReason(breakdown.getReason());
                decision.setTriggerType("LEADER_CONSENSUS_BREAKDOWN");
                decision.setConfidence(breakdown.getConfidence());
                decision.setRecommendedExitPrice(getCurrentOptionPrice(trade.getOptionSymbol()));

                // Store breakdown details
                decision.getDecisionFactors().put("breakdownType", breakdown.getBreakdownType());
                decision.getDecisionFactors().put("reversedLeaders", breakdown.getReversedLeaders());
                decision.getDecisionFactors().put("consensusScore", breakdown.getCurrentConsensusScore());
                decision.getDecisionFactors().put("persistenceMinutes", breakdown.getPersistenceMinutes());

                log.warn("Leader consensus breakdown detected - Exit recommended: {}", breakdown.getReason());
            } else if (breakdown.isCautionTriggered()) {
                consensusState.setCautionTriggered(true);
                consensusState.setCautionReason(breakdown.getReason());
                log.warn("Leader consensus caution triggered: {}", breakdown.getReason());
            }

            consensusState.setLastUpdate(LocalDateTime.now());
            leaderConsensusCache.put(tradeId, consensusState);

            long totalTime = System.nanoTime() - startTime;
            log.info("Leader consensus evaluation completed in {}μs - shouldExit: {}",
                    totalTime / 1000, decision.isShouldExit());

            return decision;

        } catch (Exception e) {
            log.error("Error in leader consensus evaluation for trade {}: {}", tradeId, e.getMessage());
            return createFailureDecision("Leader consensus evaluation error: " + e.getMessage());
        }
    }

    // Initialize leader consensus state at trade creation
    private LeaderConsensusState initializeLeaderConsensusState(Trade trade) {
        LeaderConsensusState state = new LeaderConsensusState();
        state.setTradeId(trade.getId().toString());
        state.setEntryTime(trade.getEntryTime() != null ? trade.getEntryTime() : LocalDateTime.now());
        state.setCallPosition("CALL".equalsIgnoreCase(trade.getType()));
        state.setLastUpdate(LocalDateTime.now());

        try {
            // Capture leader momentum at entry time
            for (String leader : LEADER_STOCKS) {
                List<MarketData> leaderData = marketDataRepository.findRecentData(leader, 5);
                double momentum = calculateMomentum(leaderData);

                state.getEntryLeaderMomentum().put(leader, momentum);
                state.getEntryLeaderDirection().put(leader, momentum > 0 ? "BULLISH" : "BEARISH");
            }

            // Determine consensus direction
            long bullishCount = state.getEntryLeaderDirection().values().stream()
                    .mapToLong(dir -> "BULLISH".equals(dir) ? 1 : 0).sum();

            state.setConsensusDirection(bullishCount >= 2 ? "BULLISH" : "BEARISH");
            state.setWeightedConsensusScore(calculateWeightedConsensus(state.getEntryLeaderMomentum()));

            log.info("Leader consensus initialized for trade {} - Direction: {}, Score: {}, Leaders: {}",
                    trade.getId(), state.getConsensusDirection(),
                    String.format("%.3f", state.getWeightedConsensusScore()),
                    state.getEntryLeaderMomentum());

        } catch (Exception e) {
            log.error("Error initializing leader consensus for trade {}: {}", trade.getId(), e.getMessage());
        }

        return state;
    }

    // Update current leader positions for monitoring
    private void updateCurrentLeaderPositions(LeaderConsensusState state) {
        LocalDateTime now = LocalDateTime.now();

        for (String leader : LEADER_STOCKS) {
            try {
                List<MarketData> currentData = marketDataRepository.findRecentData(leader, 5);
                double currentMomentum = calculateMomentum(currentData);

                String currentDirection = currentMomentum > 0.002 ? "BULLISH" :
                        currentMomentum < -0.002 ? "BEARISH" : "NEUTRAL";
                String entryDirection = state.getEntryLeaderDirection().get(leader);

                // Track when leader first reversed direction
                if (!currentDirection.equals(entryDirection) && !currentDirection.equals("NEUTRAL")) {
                    if (!state.getLeaderReversalTimes().containsKey(leader)) {
                        state.getLeaderReversalTimes().put(leader, now);
                        log.info("Leader {} reversed from {} to {} at {}",
                                leader, entryDirection, currentDirection, now);
                    }
                } else if (currentDirection.equals(entryDirection)) {
                    // Leader returned to original direction - reset reversal time
                    state.getLeaderReversalTimes().remove(leader);
                }

            } catch (Exception e) {
                log.debug("Error updating {} position: {}", leader, e.getMessage());
            }
        }
    }

    // Check for leader consensus breakdown
    private LeaderConsensusBreakdown checkLeaderConsensusBreakdown(LeaderConsensusState state, Trade trade) {
        LocalDateTime now = LocalDateTime.now();
        boolean isCallPosition = state.isCallPosition();

        List<String> reversedLeaders = new ArrayList<>();
        List<String> dovishLeaders = new ArrayList<>();
        double currentWeightedScore = 0.0;
        long maxPersistenceMinutes = 0;

        // Check each leader's current status vs entry
        for (String leader : LEADER_STOCKS) {
            try {
                List<MarketData> currentData = marketDataRepository.findRecentData(leader, 5);
                double currentMomentum = calculateMomentum(currentData);
                double entryMomentum = state.getEntryLeaderMomentum().getOrDefault(leader, 0.0);
                String entryDirection = state.getEntryLeaderDirection().get(leader);

                // Check for full reversal (changed direction significantly)
                if (isFullReversal(entryMomentum, currentMomentum, isCallPosition)) {
                    reversedLeaders.add(leader);

                    // Check persistence
                    LocalDateTime reversalTime = state.getLeaderReversalTimes().get(leader);
                    if (reversalTime != null) {
                        long persistenceMinutes = Duration.between(reversalTime, now).toMinutes();
                        maxPersistenceMinutes = Math.max(maxPersistenceMinutes, persistenceMinutes);
                    }
                }
                // Check for dovish behavior (weakening momentum in same direction)
                else if (isDovishWeakening(entryMomentum, currentMomentum, isCallPosition)) {
                    dovishLeaders.add(leader);
                }

                // Calculate weighted contribution
                double weight = LEADER_WEIGHTS.getOrDefault(leader, 0.33);
                currentWeightedScore += currentMomentum * weight;

            } catch (Exception e) {
                log.debug("Error checking {} consensus breakdown: {}", leader, e.getMessage());
            }
        }

        // Determine if consensus has broken down
        boolean shouldExit = false;
        String reason = "";
        String breakdownType = "";
        double confidence = 0.0;

        // Critical breakdown: Primary leader reversed with persistence
        if (reversedLeaders.contains("NVDA") && maxPersistenceMinutes >= config.getPrimaryLeaderExitMinutes()) {
            shouldExit = true;
            reason = String.format("NVDA reversed and persisting for %d minutes", maxPersistenceMinutes);
            breakdownType = "PRIMARY_LEADER_REVERSAL";
            confidence = 0.90;
        }
        // Major breakdown: 2+ leaders reversed with persistence
        else if (reversedLeaders.size() >= 2 && maxPersistenceMinutes >= config.getExitPersistenceMinutes()) {
            shouldExit = true;
            reason = String.format("%d leaders reversed (%s) persisting %d+ minutes",
                    reversedLeaders.size(), String.join(",", reversedLeaders), maxPersistenceMinutes);
            breakdownType = "MULTIPLE_LEADER_REVERSAL";
            confidence = 0.85;
        }
        // Consensus breakdown: 1 reversed + others dovish with good persistence
        else if (reversedLeaders.size() >= 1 && dovishLeaders.size() >= 1 && maxPersistenceMinutes >= (config.getExitPersistenceMinutes() + 4)) {
            shouldExit = true;
            reason = String.format("Leader %s reversed, others dovish (%s), persisting %d+ minutes",
                    String.join(",", reversedLeaders), String.join(",", dovishLeaders), maxPersistenceMinutes);
            breakdownType = "CONSENSUS_BREAKDOWN";
            confidence = 0.80;
        }

        // Caution triggers (no exit yet)
        boolean cautionTriggered = false;
        if (!shouldExit && (reversedLeaders.size() >= 1 || dovishLeaders.size() >= 2)) {
            cautionTriggered = true;
            if (!state.isCautionTriggered()) {
                reason = String.format("Caution: %d reversed, %d dovish - monitoring",
                        reversedLeaders.size(), dovishLeaders.size());
            }
        }

        return new LeaderConsensusBreakdown(shouldExit, cautionTriggered, reason, breakdownType,
                confidence, reversedLeaders, dovishLeaders, currentWeightedScore, maxPersistenceMinutes);
    }

    // Helper method to check if leader fully reversed direction
    private boolean isFullReversal(double entryMomentum, double currentMomentum, boolean isCallPosition) {
        double threshold = config.getLeaderReversalThreshold();

        if (isCallPosition) {
            // For CALL position, reversal is when bullish leader turns bearish
            return entryMomentum > threshold && currentMomentum < -threshold;
        } else {
            // For PUT position, reversal is when bearish leader turns bullish
            return entryMomentum < -threshold && currentMomentum > threshold;
        }
    }

    // Helper method to check if leader is showing dovish weakening
    private boolean isDovishWeakening(double entryMomentum, double currentMomentum, boolean isCallPosition) {
        double weakeningThreshold = config.getDovishWeakeningThreshold();

        if (isCallPosition && entryMomentum > 0.002) {
            // Bullish leader weakening significantly but not fully reversing
            return currentMomentum > 0 && currentMomentum < (entryMomentum * weakeningThreshold);
        } else if (!isCallPosition && entryMomentum < -0.002) {
            // Bearish leader weakening significantly but not fully reversing
            return currentMomentum < 0 && currentMomentum > (entryMomentum * weakeningThreshold);
        }

        return false;
    }

    // Calculate momentum from market data
    private double calculateMomentum(List<MarketData> data) {
        if (data.isEmpty()) return 0.0;

        MarketData firstData = data.get(data.size() - 1);
        MarketData lastData = data.get(0);

        if (firstData.getPrice() == null || lastData.getPrice() == null ||
                firstData.getPrice().compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }

        return lastData.getPrice().subtract(firstData.getPrice())
                .divide(firstData.getPrice(), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // Calculate weighted consensus score
    private double calculateWeightedConsensus(Map<String, Double> leaderMomentum) {
        double weightedSum = 0.0;
        for (Map.Entry<String, Double> entry : leaderMomentum.entrySet()) {
            double weight = LEADER_WEIGHTS.getOrDefault(entry.getKey(), 0.33);
            weightedSum += entry.getValue() * weight;
        }
        return weightedSum;
    }

    // Basic theta protection check
    private boolean isOptionValueTooDecayed(Trade trade) {
        LocalTime now = LocalTime.now(ET_ZONE);
        if (!now.isAfter(LocalTime.of(15, 0))) {
            return false; // Only apply in final hour
        }

        try {
            BigDecimal currentPrice = getCurrentOptionPrice(trade.getOptionSymbol());
            BigDecimal entryPrice = trade.getEntryPrice();

            if (currentPrice != null && entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
                double decayPercent = entryPrice.subtract(currentPrice)
                        .divide(entryPrice, 4, RoundingMode.HALF_UP).doubleValue();

                // Exit if option lost 60%+ value in final hour (pure theta decay protection)
                return decayPercent > config.getThetaProtectionThreshold();
            }
        } catch (Exception e) {
            log.debug("Error checking theta decay: {}", e.getMessage());
        }

        return false;
    }

    // Helper method to get current option price
    private BigDecimal getCurrentOptionPrice(String optionSymbol) {
        try {
            QuoteResponse quote = tradierService.getQuote(optionSymbol);
            if (quote != null && quote.getQuote() != null) {
                BigDecimal last = quote.getQuote().getLast();
                if (last != null && last.compareTo(BigDecimal.ZERO) > 0) {
                    return last;
                }
                // Fallback to mid price
                BigDecimal bid = quote.getQuote().getBid();
                BigDecimal ask = quote.getQuote().getAsk();
                if (bid != null && ask != null) {
                    return bid.add(ask).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception e) {
            log.debug("Error getting option price for {}: {}", optionSymbol, e.getMessage());
        }
        return null;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (config == null) {
            throw new IllegalStateException("StopLossConfiguration not loaded");
        }

        log.info("Leader consensus monitor initialized with config: " +
                        "leaderReversalThreshold={}, exitPersistenceMinutes={}, primaryLeaderExitMinutes={}",
                config.getLeaderReversalThreshold(),
                config.getExitPersistenceMinutes(),
                config.getPrimaryLeaderExitMinutes());
    }

    @Scheduled(fixedDelay = 30000) // Every 30 seconds
    public void monitorAllOpenTrades() {
        if (!isMarketOpen()) {
            return; // Skip monitoring when market is closed
        }

        String traceId = UUID.randomUUID().toString().substring(0, 8);

        try {
            // STEP 1: Monitor regular OPEN trades
            //List<Trade> openTrades = tradeRepository.findByStatus("OPEN");

            List<Trade> openTrades = tradeRepository.findAll().stream()
                    .filter(trade -> "OPEN".equals(trade.getStatus()))
                    .collect(Collectors.toList());

            List<Trade> monitorableTrades = openTrades.stream()
                    .filter(trade -> "OPEN".equals(trade.getStatus()))
                    .filter(trade -> !isTradeBeingProcessed(trade))
                    .collect(Collectors.toList());

            if (!monitorableTrades.isEmpty()) {
                log.debug("[MONITOR][{}] Monitoring {} OPEN trades", traceId, monitorableTrades.size());

                for (Trade trade : monitorableTrades) {
                    try {
                        Trade currentTrade = tradeRepository.findById(trade.getId()).orElse(null);
                        if (currentTrade == null || !"OPEN".equals(currentTrade.getStatus())) {
                            log.debug("[MONITOR][{}] Skipping trade {} - Status changed to {}",
                                    traceId, trade.getId(), currentTrade != null ? currentTrade.getStatus() : "NOT_FOUND");
                            continue;
                        }

                        StopLossDecision decision = evaluateStopLoss(currentTrade);
                        if (decision.isShouldExit()) {
                            log.warn("[MONITOR][{}] Leader consensus breakdown triggered for trade {}: {}",
                                    traceId, trade.getId(), decision.getExitReason());
                            executeStopLossExit(currentTrade, decision);
                        }
                    } catch (Exception e) {
                        log.error("[MONITOR][{}] Error evaluating trade {}: {}",
                                traceId, trade.getId(), e.getMessage());
                    }
                }
            } else {
                log.debug("[MONITOR][{}] No OPEN trades to monitor", traceId);
            }

            // STEP 2: Check for FAILED trades with ORDER_FAILED reason and convert to MOCK
            List<Trade> failedTrades = tradeRepository.findByStatusAndFailureReason("FAILED", "ORDER_FAILED");

            if (!failedTrades.isEmpty()) {
                log.info("[MONITOR][{}] Found {} FAILED trades with ORDER_FAILED - Converting to MOCK trades",
                        traceId, failedTrades.size());

                for (Trade failedTrade : failedTrades) {
                    try {
                        convertFailedTradeToMock(failedTrade, traceId);
                    } catch (Exception e) {
                        log.error("[MONITOR][{}] Error converting FAILED trade {} to MOCK: {}",
                                traceId, failedTrade.getId(), e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.error("[MONITOR][{}] Error in monitoring cycle: {}", traceId, e.getMessage());
        }
    }


    @Transactional
    private void convertFailedTradeToMock(Trade failedTrade, String traceId) {
        try {
            // ============================================
            // CRITICAL FIX: Only convert trades from TODAY
            // ============================================
            LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
            LocalDate tradeDate = failedTrade.getEntryTime().toLocalDate();

            if (!tradeDate.equals(today)) {
                log.debug("[MOCK-CONVERT][{}] Skipping trade {} - Not from today (trade date: {}, today: {})",
                        traceId, failedTrade.getId(), tradeDate, today);

                // Mark as expired/old - don't retry
                failedTrade.setStatus("FAILED");
                failedTrade.setFailureReason("ORDER_FAILED_EXPIRED");
                failedTrade.setErrorMessage("Trade from " + tradeDate + " - too old to convert to MOCK");
                tradeRepository.saveAndFlush(failedTrade);
                return;
            }

            log.info("[MOCK-CONVERT][{}] Converting FAILED trade {} to MOCK trade (Option: {})",
                    traceId, failedTrade.getId(), failedTrade.getOptionSymbol());

            // Step 1: Get current option price as mock entry
            BigDecimal currentOptionPrice = getCurrentOptionPrice(failedTrade.getOptionSymbol());

            if (currentOptionPrice == null || currentOptionPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("[MOCK-CONVERT][{}] Cannot convert trade {} - Invalid option price: {} (Option: {})",
                        traceId, failedTrade.getId(), currentOptionPrice, failedTrade.getOptionSymbol());

                // Mark as failed - can't get price (option may have expired or been delisted)
                failedTrade.setStatus("FAILED");
                failedTrade.setFailureReason("ORDER_FAILED_NO_PRICE");
                failedTrade.setErrorMessage("Cannot get current price for " + failedTrade.getOptionSymbol());
                tradeRepository.saveAndFlush(failedTrade);
                return;
            }

            // Step 2: Update trade to MOCK OPEN status
            failedTrade.setStatus("OPEN");
            failedTrade.setOrderType("MOCK");
            failedTrade.setEntryPrice(currentOptionPrice);
            failedTrade.setEntryTime(LocalDateTime.now());
            failedTrade.setFailureReason(null); // Clear failure reason since we're now tracking it
            failedTrade.setErrorMessage("Converted from ORDER_FAILED to MOCK trade");
            failedTrade.setLastAdjustmentTime(LocalDateTime.now());

            // Step 3: Calculate stop-loss and take-profit (same as real trades)
            BigDecimal stopLossPrice = currentOptionPrice.multiply(BigDecimal.valueOf(0.50)); // 50% loss
            BigDecimal takeProfitPrice = currentOptionPrice.multiply(BigDecimal.valueOf(1.50)); // 50% gain

            failedTrade.setStopLoss(stopLossPrice);
            failedTrade.setProfit(takeProfitPrice);

            // Step 4: Save the converted trade
            Trade savedTrade = tradeRepository.saveAndFlush(failedTrade);

            log.info("[MOCK-CONVERT][{}] Successfully converted trade {} to MOCK - Entry: ${}, Stop: ${}, Target: ${}",
                    traceId, savedTrade.getId(), currentOptionPrice, stopLossPrice, takeProfitPrice);

            // Step 5: Send notification
            telegramService.sendMessage(String.format(
                    "🎭 MOCK TRADE ACTIVATED\n" +
                            "Trade ID: %s\n" +
                            "Symbol: %s\n" +
                            "Option: %s\n" +
                            "Type: MOCK (Order Failed)\n" +
                            "Entry: $%.2f\n" +
                            "Stop Loss: $%.2f\n" +
                            "Take Profit: $%.2f\n" +
                            "Strategy: %s",
                    savedTrade.getId(),
                    savedTrade.getSymbol(),
                    savedTrade.getOptionSymbol(),
                    currentOptionPrice,
                    stopLossPrice,
                    takeProfitPrice,
                    savedTrade.getStrategy()
            ));

        } catch (Exception e) {
            log.error("[MOCK-CONVERT][{}] Failed to convert trade {} to MOCK: {}",
                    traceId, failedTrade.getId(), e.getMessage(), e);
        }
    }



    private void executeMockExit(Trade trade, StopLossDecision decision, String tradeId) {
        try {
            log.info("[MOCK-EXIT][{}] Executing MOCK exit - Skipping broker order", tradeId);

            BigDecimal exitPrice = decision.getRecommendedExitPrice();

            trade.setStatus("CLOSED");
            trade.setExitPrice(exitPrice);
            trade.setExitTime(LocalDateTime.now());
            trade.setCloseReason("LEADER_CONSENSUS_" + decision.getTriggerType());
            trade.setExitReason(decision.getExitReason());

            // Calculate P&L
            if (exitPrice != null && trade.getEntryPrice() != null) {
                BigDecimal pnl = exitPrice.subtract(trade.getEntryPrice())
                        .multiply(BigDecimal.valueOf(Math.abs(trade.getQuantity()) * 100));
                trade.setRealizedPnl(pnl);
                trade.setProfit(pnl);
            }

            tradeRepository.saveAndFlush(trade);

            log.info("[MOCK-EXIT][{}] Successfully closed MOCK trade - P&L: ${}",
                    tradeId, trade.getRealizedPnl());

            telegramService.sendMessage(String.format(
                    "🎭 MOCK EXIT - LEADER CONSENSUS\n" +
                            "Trade ID: %s\n" +
                            "Symbol: %s\n" +
                            "Trigger: %s\n" +
                            "Reason: %s\n" +
                            "Entry: $%.2f\n" +
                            "Exit: $%.2f\n" +
                            "P&L: $%.2f\n" +
                            "Type: MOCK",
                    trade.getId(),
                    trade.getSymbol(),
                    decision.getTriggerType(),
                    decision.getExitReason(),
                    trade.getEntryPrice(),
                    exitPrice,
                    trade.getRealizedPnl()
            ));

        } catch (Exception e) {
            log.error("[MOCK-EXIT][{}] Error executing MOCK exit: {}", tradeId, e.getMessage());
            releaseTradeLoopOnError(trade.getId());
        }
    }

    private void executeLiveExit(Trade trade, StopLossDecision decision, String tradeId) {
        try {
            log.info("[LIVE-EXIT][{}] Executing LIVE exit - Placing broker order", tradeId);

            OrderRequest closeRequest = new OrderRequest();
            closeRequest.setSymbol(trade.getOptionSymbol());
            closeRequest.setQuantity(Math.abs(trade.getQuantity()));
            closeRequest.setSide("sell_to_close");
            closeRequest.setType("market");
            closeRequest.setDuration("day");

            log.info("[LIVE-EXIT][{}] Placing market close order for {} contracts",
                    tradeId, closeRequest.getQuantity());

            OrderResponse response = tradierService.placeOrder(closeRequest);

            if (response != null && response.getOrder() != null) {
                BigDecimal exitPrice = decision.getRecommendedExitPrice();

                trade.setStatus("CLOSED");
                trade.setExitPrice(exitPrice);
                trade.setExitTime(LocalDateTime.now());
                trade.setCloseReason("LEADER_CONSENSUS_" + decision.getTriggerType());
                trade.setExitReason(decision.getExitReason());

                // Calculate P&L
                if (exitPrice != null && trade.getEntryPrice() != null) {
                    BigDecimal pnl = exitPrice.subtract(trade.getEntryPrice())
                            .multiply(BigDecimal.valueOf(Math.abs(trade.getQuantity()) * 100));
                    trade.setRealizedPnl(pnl);
                    trade.setProfit(pnl);
                }

                tradeRepository.saveAndFlush(trade);

                log.info("[LIVE-EXIT][{}] Successfully closed LIVE trade", tradeId);

                telegramService.sendMessage(String.format(
                        "⚡ LIVE EXIT - LEADER CONSENSUS\n" +
                                "Trade ID: %s\n" +
                                "Symbol: %s\n" +
                                "Trigger: %s\n" +
                                "Reason: %s\n" +
                                "Entry: $%.2f\n" +
                                "Exit: $%.2f\n" +
                                "P&L: $%.2f\n" +
                                "Order ID: %s",
                        trade.getId(),
                        trade.getSymbol(),
                        decision.getTriggerType(),
                        decision.getExitReason(),
                        trade.getEntryPrice(),
                        exitPrice,
                        trade.getRealizedPnl(),
                        response.getId()
                ));

            } else {
                log.error("[LIVE-EXIT][{}] Failed to place order", tradeId);
                releaseTradeLoopOnError(trade.getId());
                telegramService.sendMessage("FAILED to execute exit: " + trade.getOptionSymbol());
            }

        } catch (Exception e) {
            log.error("[LIVE-EXIT][{}] Error executing LIVE exit: {}", tradeId, e.getMessage());
            releaseTradeLoopOnError(trade.getId());
        }
    }

    private void releaseTradeLoopOnError(Long tradeId) {
        try {
            Trade errorTrade = tradeRepository.findById(tradeId).orElse(null);
            if (errorTrade != null && "CLOSING".equals(errorTrade.getStatus())) {
                errorTrade.setStatus("OPEN");
                tradeRepository.saveAndFlush(errorTrade);
                log.info("[EXIT] Released lock for trade {} after error", tradeId);
            }
        } catch (Exception releaseError) {
            log.error("[EXIT] Error releasing trade lock for {}: {}", tradeId, releaseError.getMessage());
        }
    }


    private void executeStopLossExit(Trade trade, StopLossDecision decision) {
        String tradeId = trade.getId().toString();

        try {
            log.warn("[EXIT][{}] Attempting leader consensus exit - Type: {}", tradeId, trade.getOrderType());

            // STEP 1: Acquire trade lock
            Trade freshTrade = tradeRepository.findById(trade.getId())
                    .orElseThrow(() -> new RuntimeException("Trade not found: " + trade.getId()));

            if (!"OPEN".equals(freshTrade.getStatus())) {
                log.warn("[EXIT][{}] Cannot close - Status is {} (already being processed)",
                        tradeId, freshTrade.getStatus());
                return;
            }

            // Atomic status change: OPEN -> CLOSING
            freshTrade.setStatus("CLOSING");
            freshTrade.setLastAdjustmentTime(LocalDateTime.now());
            tradeRepository.saveAndFlush(freshTrade);

            // Verify status change
            Trade verifyTrade = tradeRepository.findById(trade.getId()).orElse(null);
            if (verifyTrade == null || !"CLOSING".equals(verifyTrade.getStatus())) {
                log.error("[EXIT][{}] Failed to acquire lock - Status verification failed", tradeId);
                return;
            }

            log.info("[EXIT][{}] Successfully acquired trade for exit", tradeId);

            // STEP 2: Check if MOCK or LIVE
            boolean isMockTrade = "MOCK".equals(freshTrade.getOrderType());

            if (isMockTrade) {
                // MOCK EXIT - Skip broker call
                executeMockExit(freshTrade, decision, tradeId);
            } else {
                // LIVE EXIT - Execute broker order
                executeLiveExit(freshTrade, decision, tradeId);
            }

        } catch (Exception e) {
            log.error("[EXIT][{}] Error executing exit: {}", tradeId, e.getMessage());
            releaseTradeLoopOnError(trade.getId());
            telegramService.sendMessage("Exit execution error: " + e.getMessage());
        }
    }



    private boolean isTradeBeingProcessed(Trade trade) {
        // Check if trade was recently updated by another system
        if (trade.getLastAdjustmentTime() != null &&
                trade.getLastAdjustmentTime().isAfter(LocalDateTime.now().minusMinutes(2))) {
            return true;
        }

        // Add any other logic to detect if trade is being processed
        return false;
    }

//    private void executeStopLossExit(Trade trade, StopLossDecision decision) {
//        String tradeId = trade.getId().toString();
//
//        try {
//            log.warn("ATTEMPTING LEADER CONSENSUS EXIT for trade {}: {}",
//                    trade.getId(), decision.getExitReason());
//
//            // STEP 1: ATOMIC STATUS LOCKING - Acquire trade for closing
//            Trade freshTrade = tradeRepository.findById(trade.getId())
//                    .orElseThrow(() -> new RuntimeException("Trade not found: " + trade.getId()));
//
//            if (!"OPEN".equals(freshTrade.getStatus())) {
//                log.warn("Cannot close trade {} - Status is {} (already being processed)",
//                        trade.getId(), freshTrade.getStatus());
//                return;
//            }
//
//            // Atomic status change: OPEN -> CLOSING
//            freshTrade.setStatus("CLOSING");
//            freshTrade.setLastAdjustmentTime(LocalDateTime.now());
//            Trade savedTrade = tradeRepository.saveAndFlush(freshTrade);
//
//            // Verify status change
//            Trade verifyTrade = tradeRepository.findById(trade.getId()).orElse(null);
//            if (verifyTrade == null || !"CLOSING".equals(verifyTrade.getStatus())) {
//                log.error("Failed to acquire trade {} for closing - Status verification failed", trade.getId());
//                return;
//            }
//
//            log.info("[LEADER-CONSENSUS-EXIT] Successfully acquired trade {} for leader consensus exit", trade.getId());
//
//            // STEP 2: CREATE AND PLACE SELL ORDER
//            OrderRequest closeRequest = new OrderRequest();
//            closeRequest.setSymbol(trade.getOptionSymbol());
//            closeRequest.setQuantity(Math.abs(trade.getQuantity()));
//            closeRequest.setSide("sell_to_close");
//            closeRequest.setType("market");
//            closeRequest.setDuration("day");
//
//            log.info("[LEADER-CONSENSUS-EXIT] Placing LEADER CONSENSUS market close order for {} contracts",
//                    closeRequest.getQuantity());
//
//            OrderResponse response = tradierService.placeOrder(closeRequest);
//
//            if (response != null && response.getOrder() != null) {
//                // STEP 3: UPDATE TRADE TO FINAL CLOSED STATUS
//                BigDecimal exitPrice = decision.getRecommendedExitPrice();
//
//                freshTrade.setStatus("CLOSED");
//                freshTrade.setExitPrice(exitPrice);
//                freshTrade.setExitTime(LocalDateTime.now());
//                freshTrade.setCloseReason("LEADER_CONSENSUS_" + decision.getTriggerType());
//                freshTrade.setExitReason(decision.getExitReason());
//
//                // Calculate final P&L
//                if (exitPrice != null && trade.getEntryPrice() != null) {
//                    BigDecimal finalPnl = exitPrice.subtract(trade.getEntryPrice())
//                            .multiply(BigDecimal.valueOf(Math.abs(trade.getQuantity()) * 100));
//                    freshTrade.setRealizedPnl(finalPnl);
//                    freshTrade.setProfit(finalPnl);
//                }
//
//                tradeRepository.saveAndFlush(freshTrade);
//
//                log.info("[LEADER-CONSENSUS-EXIT] Successfully closed trade {} with leader consensus", trade.getId());
//
//                // Send notification
//                telegramService.sendMessage(String.format(
//                        "LEADER CONSENSUS EXIT TRIGGERED\nTrade: %s\nSymbol: %s\nTrigger: %s\nReason: %s\nExit Price: $%.2f\nOrder ID: %s",
//                        trade.getId(), trade.getSymbol(), decision.getTriggerType(),
//                        decision.getExitReason(),
//                        decision.getRecommendedExitPrice() != null ? decision.getRecommendedExitPrice().doubleValue() : 0.0,
//                        response.getId()
//                ));
//
//            } else {
//                log.error("[LEADER-CONSENSUS-EXIT] Failed to place leader consensus order for trade {}", trade.getId());
//
//                // Release lock on failure
//                freshTrade.setStatus("OPEN");
//                freshTrade.setLastAdjustmentTime(LocalDateTime.now());
//                tradeRepository.saveAndFlush(freshTrade);
//
//                telegramService.sendMessage("FAILED to execute leader consensus exit: " + trade.getOptionSymbol());
//            }
//
//        } catch (Exception e) {
//            log.error("[LEADER-CONSENSUS-EXIT] Error executing leader consensus exit for trade {}: {}",
//                    trade.getId(), e.getMessage());
//
//            // Release lock on error
//            try {
//                Trade errorTrade = tradeRepository.findById(trade.getId()).orElse(null);
//                if (errorTrade != null && "CLOSING".equals(errorTrade.getStatus())) {
//                    errorTrade.setStatus("OPEN");
//                    tradeRepository.saveAndFlush(errorTrade);
//                }
//            } catch (Exception releaseError) {
//                log.error("Error releasing trade lock: {}", releaseError.getMessage());
//            }
//
//            telegramService.sendMessage("Leader consensus exit execution error: " + e.getMessage());
//        }
//    }

    // Helper methods
    private boolean isMarketOpen() {
        LocalTime now = LocalTime.now();
        return now.isAfter(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(16, 0));
    }

    private StopLossDecision createFailureDecision(String reason) {
        StopLossDecision decision = new StopLossDecision();
        decision.setDecisionId(UUID.randomUUID().toString());
        decision.setTimestamp(LocalDateTime.now());
        decision.setShouldExit(false);
        decision.setExitReason(reason);
        decision.setConfidence(0.0);
        decision.getDecisionPath().add("Failed: " + reason);
        return decision;
    }

    private StopLossDecision createSkipDecision(String reason) {
        StopLossDecision decision = new StopLossDecision();
        decision.setDecisionId(UUID.randomUUID().toString());
        decision.setTimestamp(LocalDateTime.now());
        decision.setShouldExit(false);
        decision.setExitReason(reason);
        decision.setConfidence(0.0);
        decision.getDecisionPath().add("Skipped: " + reason);
        return decision;
    }

    // Public method to capture entry consensus (call this from ZeroDTEStrategy)
    public void captureEntryLeaderConsensus(Trade trade, String analysisId) {
        try {
            // Initialize the leader consensus state when trade is created
            LeaderConsensusState consensusState = initializeLeaderConsensusState(trade);
            leaderConsensusCache.put(trade.getId().toString(), consensusState);

            log.info("[ENTRY-CONSENSUS][{}] Captured leader consensus for trade {} - Direction: {}, Score: {}",
                    analysisId, trade.getId(), consensusState.getConsensusDirection(),
                    String.format("%.3f", consensusState.getWeightedConsensusScore()));

        } catch (Exception e) {
            log.error("[ENTRY-CONSENSUS][{}] Error capturing entry consensus for trade {}: {}",
                    analysisId, trade.getId(), e.getMessage());
        }
    }

    public Map<String, Object> getMonitoringStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("activeMonitoring", leaderConsensusCache.size());
        status.put("totalEvaluations", totalEvaluations.sum());
        status.put("lastEvaluationTime", LocalDateTime.now());
        status.put("marketOpen", isMarketOpen());
        status.put("circuitBreakerStates", circuitBreakerStates.values().stream()
                .map(cb -> cb.getSymbol() + ":" + (cb.isOpen() ? "OPEN" : "CLOSED"))
                .collect(Collectors.toList()));

        try {
            List<Trade> openTrades = tradeRepository.findByStatus("OPEN");
            status.put("openTradesCount", openTrades.size());
        } catch (Exception e) {
            status.put("openTradesCount", "ERROR: " + e.getMessage());
        }

        log.info("Leader consensus monitor status: {}", status);
        return status;
    }

    public void testStopLossMonitoring(Long tradeId) {
        Trade trade = tradeRepository.findById(tradeId).orElse(null);
        if (trade == null) {
            log.error("Trade {} not found", tradeId);
            return;
        }

        log.info("Testing leader consensus monitoring for trade {}", tradeId);
        StopLossDecision decision = evaluateStopLoss(trade);
        log.info("Decision: shouldExit={}, reason={}, confidence={}",
                decision.isShouldExit(), decision.getExitReason(), decision.getConfidence());
    }

    public void clearCache() {
        leaderConsensusCache.clear();
        log.info("Leader consensus monitor cache cleared");
    }

    @Transactional
    public void executeLeaderConsensusExit(Trade trade, StopLossDecision decision) {
        if (trade == null || decision == null) {
            log.error("[LEADER-CONSENSUS-EXIT] Cannot execute - null trade or decision");
            return;
        }

        try {
            log.info("[LEADER-CONSENSUS-EXIT] Initiating leader consensus exit for trade {}, Type: {}",
                    trade.getId(), trade.getOrderType());

            // STEP 1: ACQUIRE TRADE LOCK
            Trade freshTrade = tradeRepository.findById(trade.getId())
                    .orElseThrow(() -> new RuntimeException("Trade not found: " + trade.getId()));

            if (!"OPEN".equals(freshTrade.getStatus())) {
                log.warn("Cannot close trade {} - Status is {} (already being processed)",
                        trade.getId(), freshTrade.getStatus());
                return;
            }

            // Atomic status change: OPEN -> CLOSING
            freshTrade.setStatus("CLOSING");
            freshTrade.setLastAdjustmentTime(LocalDateTime.now());
            Trade savedTrade = tradeRepository.saveAndFlush(freshTrade);

            // Verify status change
            Trade verifyTrade = tradeRepository.findById(trade.getId()).orElse(null);
            if (verifyTrade == null || !"CLOSING".equals(verifyTrade.getStatus())) {
                log.error("Failed to acquire trade {} for closing - Status verification failed", trade.getId());
                return;
            }

            log.info("[LEADER-CONSENSUS-EXIT] Successfully acquired trade {} for leader consensus exit", trade.getId());

            // STEP 2: CHECK IF MOCK OR LIVE
            boolean isMockTrade = "MOCK".equals(freshTrade.getOrderType());

            if (isMockTrade) {
                // MOCK EXIT - Skip broker call, just update record
                log.info("[LEADER-CONSENSUS-EXIT] MOCK trade detected - Skipping broker order");

                BigDecimal exitPrice = decision.getRecommendedExitPrice();

                freshTrade.setStatus("CLOSED");
                freshTrade.setExitPrice(exitPrice);
                freshTrade.setExitTime(LocalDateTime.now());
                freshTrade.setCloseReason("LEADER_CONSENSUS_" + decision.getTriggerType());
                freshTrade.setExitReason(decision.getExitReason());

                // Calculate final P&L
                if (exitPrice != null && trade.getEntryPrice() != null) {
                    BigDecimal finalPnl = exitPrice.subtract(trade.getEntryPrice())
                            .multiply(BigDecimal.valueOf(Math.abs(trade.getQuantity()) * 100));
                    freshTrade.setRealizedPnl(finalPnl);
                    freshTrade.setProfit(finalPnl);
                }

                tradeRepository.saveAndFlush(freshTrade);

                log.info("[LEADER-CONSENSUS-EXIT] Successfully closed MOCK trade {} with P&L: ${}",
                        trade.getId(), freshTrade.getRealizedPnl());

                // Send MOCK exit notification
                telegramService.sendMessage(String.format(
                        "🎯 MOCK EXIT - LEADER CONSENSUS\nTrade: %s\nSymbol: %s\nTrigger: %s\nReason: %s\nEntry: $%.2f\nExit: $%.2f\nP&L: $%.2f\nType: MOCK",
                        trade.getId(),
                        trade.getSymbol(),
                        decision.getTriggerType(),
                        decision.getExitReason(),
                        trade.getEntryPrice(),
                        exitPrice,
                        freshTrade.getRealizedPnl()
                ));

            } else {
                // LIVE EXIT - Execute broker order
                log.info("[LEADER-CONSENSUS-EXIT] LIVE trade - Placing broker order");

                OrderRequest closeRequest = new OrderRequest();
                closeRequest.setSymbol(trade.getOptionSymbol());
                closeRequest.setQuantity(Math.abs(trade.getQuantity()));
                closeRequest.setSide("sell_to_close");
                closeRequest.setType("market");
                closeRequest.setDuration("day");

                log.info("[LEADER-CONSENSUS-EXIT] Placing LEADER CONSENSUS market close order for {} contracts",
                        closeRequest.getQuantity());

                OrderResponse response = tradierService.placeOrder(closeRequest);

                if (response != null && response.getOrder() != null) {
                    // STEP 3: UPDATE TRADE TO FINAL CLOSED STATUS
                    BigDecimal exitPrice = decision.getRecommendedExitPrice();

                    freshTrade.setStatus("CLOSED");
                    freshTrade.setExitPrice(exitPrice);
                    freshTrade.setExitTime(LocalDateTime.now());
                    freshTrade.setCloseReason("LEADER_CONSENSUS_" + decision.getTriggerType());
                    freshTrade.setExitReason(decision.getExitReason());

                    // Calculate final P&L
                    if (exitPrice != null && trade.getEntryPrice() != null) {
                        BigDecimal finalPnl = exitPrice.subtract(trade.getEntryPrice())
                                .multiply(BigDecimal.valueOf(Math.abs(trade.getQuantity()) * 100));
                        freshTrade.setRealizedPnl(finalPnl);
                        freshTrade.setProfit(finalPnl);
                    }

                    tradeRepository.saveAndFlush(freshTrade);

                    log.info("[LEADER-CONSENSUS-EXIT] Successfully closed LIVE trade {} with leader consensus", trade.getId());

                    // Send LIVE notification
                    telegramService.sendMessage(String.format(
                            "⚡ LIVE EXIT - LEADER CONSENSUS\nTrade: %s\nSymbol: %s\nTrigger: %s\nReason: %s\nEntry: $%.2f\nExit: $%.2f\nP&L: $%.2f\nOrder ID: %s",
                            trade.getId(),
                            trade.getSymbol(),
                            decision.getTriggerType(),
                            decision.getExitReason(),
                            trade.getEntryPrice(),
                            exitPrice,
                            freshTrade.getRealizedPnl(),
                            response.getId()
                    ));

                } else {
                    log.error("[LEADER-CONSENSUS-EXIT] Failed to place leader consensus order for trade {}", trade.getId());

                    // Release lock on failure
                    freshTrade.setStatus("OPEN");
                    freshTrade.setLastAdjustmentTime(LocalDateTime.now());
                    tradeRepository.saveAndFlush(freshTrade);

                    telegramService.sendMessage("FAILED to execute leader consensus exit: " + trade.getOptionSymbol());
                }
            }

        } catch (Exception e) {
            log.error("[LEADER-CONSENSUS-EXIT] Error executing leader consensus exit for trade {}: {}",
                    trade.getId(), e.getMessage());

            // Release lock on error
            try {
                Trade errorTrade = tradeRepository.findById(trade.getId()).orElse(null);
                if (errorTrade != null && "CLOSING".equals(errorTrade.getStatus())) {
                    errorTrade.setStatus("OPEN");
                    tradeRepository.saveAndFlush(errorTrade);
                }
            } catch (Exception releaseError) {
                log.error("Error releasing trade lock: {}", releaseError.getMessage());
            }

            telegramService.sendMessage("Leader consensus exit execution error: " + e.getMessage());
        }
    }
}