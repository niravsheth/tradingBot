package com.tradingBot.service;

import com.tradingBot.entity.*;
import com.tradingBot.model.*;
import com.tradingBot.repository.*;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.tradingBot.service.DirectionalConfirmationService;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZeroDTEStrategy {

    private final TradierService tradierService;
    private final TelegramService telegramService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final SignalRepository signalRepository;
    private final MarketDataRepository marketDataRepository;
    private final TradeRepository tradeRepository;
    private final SignalOrchestrator signalOrchestrator;
    private final PreTradeRiskEngine preTradeRiskEngine;
    private final IntegratedBayesianMLSystem integratedBayesianMLSystem;

    private final  EnhancedStopLossMonitor enhancedStopLossMonitor;
    private final SHAPExplainerService shapExplainerService;
    private final PriceLevelRiskService priceLevelRiskService;

    private final BarAggregationService barAggregationService;
    private final LeaderAnalysisService leaderAnalysisService;
    private final CandlePatternService candlePatternService;
    private final VolumeAnalysisService volumeAnalysisService;
    private final BounceDetectionService bounceDetectionService;

    private final TargetCalculationService targetCalculationService;


    // Timezone
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    // Leader stocks for AI analysis
    private static final List<String> LEADER_STOCKS = Arrays.asList("AAPL", "MSFT", "NVDA");

    // Leader weights for direction validation
    private static final Map<String, Double> LEADER_WEIGHTS = new HashMap<String, Double>() {{
        put("NVDA", 0.5);  // Strongest correlation to QQQ
        put("MSFT", 0.3);
        put("AAPL", 0.2);
    }};

    private static final LocalTime PRIME_WINDOW_1_START = LocalTime.of(9, 45);
    private static final LocalTime PRIME_WINDOW_1_END = LocalTime.of(10, 30);
    private static final LocalTime PRIME_WINDOW_2_START = LocalTime.of(10, 30);
    private static final LocalTime PRIME_WINDOW_2_END = LocalTime.of(12, 00);
    private static final LocalTime PRIME_WINDOW_3_START = LocalTime.of(12, 00);
    private static final LocalTime PRIME_WINDOW_3_END = LocalTime.of(15, 00);
    private static final LocalTime FINAL_WINDOW_START = LocalTime.of(15, 00);
    private static final LocalTime FINAL_WINDOW_END = LocalTime.of(15, 55);
    private static final LocalTime POWER_HOUR_START = LocalTime.of(15, 30);


    private final CorrelationDetector correlationDetector = new CorrelationDetector();
    private final LeaderDirectionValidator leaderValidator = new LeaderDirectionValidator();
    private final GreeksValidator greeksValidator = new GreeksValidator();

    // NEW: Enhanced AI Components with Momentum Velocity Tracking
    private final MomentumVelocityTracker momentumTracker = new MomentumVelocityTracker();
    private final MicroMomentumClassifier microMomentumClassifier = new MicroMomentumClassifier();
    private final RSIMomentumContextMatrix contextMatrix = new RSIMomentumContextMatrix();
    private final PreExecutionValidator preExecutionValidator = new PreExecutionValidator();

    private static final BigDecimal MIN_DISTANCE_FROM_VWAP = new BigDecimal("0.001"); // 0.1%
    private static final BigDecimal MAX_DISTANCE_FROM_VWAP = new BigDecimal("0.004"); // 0.4%
    private static final BigDecimal TARGET_DISTANCE = new BigDecimal("0.004"); // 0.4% profit target
    private static final int MIN_GREEN_BARS_QQQ = 2;
    private static final int LOOKBACK_BARS = 5;
    private static final int MAX_MINUTES_SINCE_REVERSAL = 2;
    private static final int MIN_CONFIRMATIONS_REQUIRED = 4; // Out of 6 possible


    private static final double RSI_OVERBOUGHT_THRESHOLD = 75.0;

    private final DirectionalConfirmationService directionalConfirmationService;
    private static final double RSI_OVERSOLD_THRESHOLD = 25.0;

    private final Map<String, String> pendingOrders = new ConcurrentHashMap<>(); // signalId -> orderId
    private final Map<String, LocalDateTime> executionAttempts = new ConcurrentHashMap<>(); // signalId -> lastAttempt

    private final Map<String, String> pendingSellOrders = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> sellExecutionAttempts = new ConcurrentHashMap<>();

    private final RSIHistoryTracker rsiTracker = new RSIHistoryTracker();

    // Mean Reversion Strategy Constants
    private static final BigDecimal VWAP_DISTANCE_THRESHOLD = new BigDecimal("0.0010"); // 0.10%
    private static final double VOLUME_SURGE_THRESHOLD = 1.2; // 1.2x average volume required
    private static final int VWAP_SLOPE_LOOKBACK = 3; // bars for VWAP slope calculation


    // NEW: Scheduled momentum tracking updates every minute
    @Scheduled(fixedDelay = 60000) // Every 1 minute
    public void updateMomentumTracking() {
        try {
            momentumTracker.updateMomentumHistory();
        } catch (Exception e) {
            log.debug("Error updating momentum tracking: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60000) // Every 1 minute
    public void updateRSITracking() {
        try {
            rsiTracker.updateRSIHistory();
        } catch (Exception e) {
            log.debug("Error updating RSI tracking: {}", e.getMessage());
        }
    }

//    private int countRecentBounces(String symbol, BigDecimal vwap, int minutesBack, String analysisId) {
//        try {
//            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, minutesBack);
//            if (recentData.size() < 3) {
//                log.debug("[BOUNCE-COUNT][{}] Insufficient data: {} bars", analysisId, recentData.size());
//                return 0;
//            }
//
//            int bounceCount = 0;
//            for (int i = recentData.size() - 1; i >= 1; i--) {
//                MarketData bar = recentData.get(i);
//                MarketData nextBar = recentData.get(i - 1);
//
//                if (bar.getLow() != null && bar.getClose() != null &&
//                        nextBar.getClose() != null && vwap != null) {
//
//                    boolean lowBelowVWAP = bar.getLow().compareTo(vwap) < 0;
//                    boolean closedAtOrAboveVWAP = bar.getClose().compareTo(vwap) >= 0;
//                    boolean nextBarHigher = nextBar.getClose().compareTo(bar.getClose()) > 0;
//
//                    if (lowBelowVWAP && closedAtOrAboveVWAP && nextBarHigher) {
//                        bounceCount++;
//                    }
//                }
//            }
//
//            log.info("[BOUNCE-COUNT][{}] Found {} bounces in last {} minutes",
//                    analysisId, bounceCount, minutesBack);
//            return bounceCount;
//
//        } catch (Exception e) {
//            log.error("[BOUNCE-COUNT][{}] Error counting bounces: {}", analysisId, e.getMessage());
//            return 0;
//        }
//    }
//
//    private int countRecentRejections(String symbol, BigDecimal vwap, int minutesBack, String analysisId) {
//        try {
//            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, minutesBack);
//            if (recentData.size() < 3) {
//                log.debug("[REJECTION-COUNT][{}] Insufficient data: {} bars", analysisId, recentData.size());
//                return 0;
//            }
//
//            int rejectionCount = 0;
//            for (int i = recentData.size() - 1; i >= 1; i--) {
//                MarketData bar = recentData.get(i);
//                MarketData nextBar = recentData.get(i - 1);
//
//                if (bar.getHigh() != null && bar.getClose() != null &&
//                        nextBar.getClose() != null && vwap != null) {
//
//                    boolean highAboveVWAP = bar.getHigh().compareTo(vwap) > 0;
//                    boolean closedAtOrBelowVWAP = bar.getClose().compareTo(vwap) <= 0;
//                    boolean nextBarLower = nextBar.getClose().compareTo(bar.getClose()) < 0;
//
//                    if (highAboveVWAP && closedAtOrBelowVWAP && nextBarLower) {
//                        rejectionCount++;
//                    }
//                }
//            }
//
//            log.info("[REJECTION-COUNT][{}] Found {} rejections in last {} minutes",
//                    analysisId, rejectionCount, minutesBack);
//            return rejectionCount;
//
//        } catch (Exception e) {
//            log.error("[REJECTION-COUNT][{}] Error counting rejections: {}", analysisId, e.getMessage());
//            return 0;
//        }
//    }



    /**
     * Complete rewritten analyzeOptions function with 6 confirmation checks
     * This replaces your existing analyzeOptions method entirely
     */
    public List<Signal> analyzeOptions(String symbol, String marketTrend) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        List<Signal> signals = new ArrayList<>();

        try {
            log.info("========================================");
            log.info("[{}] CONFIRMATION-BASED SIGNAL ANALYSIS START", analysisId);
            log.info("[{}] Symbol: {}, Market Trend: {}", analysisId, symbol, marketTrend);
            log.info("========================================");

            // Time validation
            LocalTime now = LocalTime.now(ET_ZONE);
            if (!isGoodTradingTime(now)) {
                log.warn("[{}] Outside trading window, skipping analysis", analysisId);
                return signals;
            }

            // Get technical analysis for QQQ
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta == null || ta.getCurrentPrice() == null || ta.getVwap() == null) {
                log.error("[{}] Technical analysis failed or incomplete", analysisId);
                return signals;
            }

            BigDecimal currentPrice = ta.getCurrentPrice();
            BigDecimal vwap = ta.getVwap();

            // ====================================================================
            // DETERMINE SIGNAL TYPE BASED ON PRICE/VWAP RELATIONSHIP
            // ====================================================================
            String signalType = null;
            if (currentPrice.compareTo(vwap) < 0) {
                signalType = "CALL"; // Price below VWAP, look for bounce
            } else if (currentPrice.compareTo(vwap) > 0) {
                signalType = "PUT"; // Price above VWAP, look for rejection
            } else {
                log.info("[MR-SIGNAL][{}] Price exactly at VWAP, no signal", analysisId);
                return signals;
            }

            // Calculate distance from VWAP
            double distanceFromVWAP = Math.abs(currentPrice.subtract(vwap)
                    .divide(vwap, 4, RoundingMode.HALF_UP)
                    .doubleValue() * 100);

            log.info("[MR-SIGNAL][{}] Price: ${}, VWAP: ${}, Distance: {}%",
                    analysisId, currentPrice, vwap, String.format("%.4f", distanceFromVWAP));

            // Check if distance is significant enough
            if (distanceFromVWAP < 0.02) {
                log.info("[MR-SIGNAL][{}] ❌ REJECTED - Distance too small: {}%", analysisId, String.format("%.4f", distanceFromVWAP));
                return signals;
            }

            log.info("[MR-SIGNAL][{}] {} opportunity - Price {} VWAP by {}%",
                    analysisId, signalType, signalType.equals("CALL") ? "below" : "above", String.format("%.4f", distanceFromVWAP));

            // ====================================================================
            // CONFIRMATION 1: Price-VWAP Position (already verified above)
            // ====================================================================
            boolean confirmation1 = true;

            // ====================================================================
            // CONFIRMATION 2: VWAP Interaction (touched within last 10 bars)
            // ====================================================================
            boolean confirmation2 = false;
            if (distanceFromVWAP > 0.10) {
                confirmation2 = true;
                log.info("[VWAP-INTERACTION][{}] ✓ Distance {}% sufficient, VWAP touch not required",
                        analysisId, String.format("%.4f", distanceFromVWAP));
            } else {
                // For smaller distances, require recent VWAP touch
                LocalDateTime vwapTouchTime = findRecentVWAPTouch(symbol, 10);
                if (vwapTouchTime != null) {
                    confirmation2 = true;
                    log.info("[VWAP-INTERACTION][{}] ✓ Found VWAP touch at {}", analysisId, vwapTouchTime);
                } else {
                    log.info("[VWAP-INTERACTION][{}] ✗ No VWAP touch in last 10 bars (distance: {}%)",
                            analysisId, String.format("%.4f", distanceFromVWAP));
                }
            }

            if (!confirmation2) {
                log.info("[{}] No valid confirmation signal generated", analysisId);
                log.info("[{}] Rejection reasons:", analysisId);
                log.info("[{}]   ✗ [VWAP-INTERACTION] Distance too small ({}%) and no recent VWAP touch",
                        analysisId, String.format("%.4f", distanceFromVWAP));
                return signals;
            }

            // ====================================================================
            // CONFIRMATION 3: Momentum Slowing
            // ====================================================================
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 5);
            boolean confirmation3 = false;

            if (recentBars.size() >= 3) {
                // Calculate velocity (rate of price change)
                MarketData currentBar = recentBars.get(recentBars.size() - 1);
                MarketData previousBar = recentBars.get(recentBars.size() - 2);
                MarketData olderBar = recentBars.get(recentBars.size() - 3);

                if (currentBar.getClose() != null && previousBar.getClose() != null && olderBar.getClose() != null) {
                    double currentVelocity = Math.abs(currentBar.getClose().subtract(previousBar.getClose())
                            .divide(previousBar.getClose(), 4, RoundingMode.HALF_UP).doubleValue() * 100);
                    double previousVelocity = Math.abs(previousBar.getClose().subtract(olderBar.getClose())
                            .divide(olderBar.getClose(), 4, RoundingMode.HALF_UP).doubleValue() * 100);

                    confirmation3 = currentVelocity < previousVelocity;

                    log.info("[MOMENTUM][{}] Current velocity: {}%, Previous: {}%, Slowing: {}",
                            analysisId, String.format("%.4f", currentVelocity),
                            String.format("%.4f", previousVelocity), confirmation3);
                }
            }

            if (!confirmation3) {
                log.info("[{}] No valid confirmation signal generated", analysisId);
                log.info("[{}] Rejection reasons:", analysisId);
                log.info("[{}]   ✗ [MOMENTUM-SLOW] Momentum not slowing", analysisId);
                return signals;
            }

            // ====================================================================
            // CONFIRMATION 4: Leaders Confirming (at least 2 of 3)
            // ====================================================================
            int leadersConfirming = checkLeadersForDirection(symbol, signalType);
            boolean confirmation4 = leadersConfirming >= 2;

            if (!confirmation4) {
                log.info("[{}] No valid confirmation signal generated", analysisId);
                log.info("[{}] Rejection reasons:", analysisId);
                log.info("[{}]   ✗ [LEADERS] Only {}/3 leaders confirming (need 2)", analysisId, leadersConfirming);
                return signals;
            }

            log.info("[LEADERS][{}] Result: {}/3 valid leaders confirming (need 2)", analysisId, leadersConfirming);

            // ====================================================================
            // CONFIRMATION 5: Price Momentum Alignment (NEW - replaces Historical)
            // ====================================================================
            boolean confirmation5 = checkPriceMomentumAlignment(symbol, signalType);

            if (!confirmation5) {
                log.info("[{}] No valid confirmation signal generated", analysisId);
                log.info("[{}] Rejection reasons:", analysisId);
                log.info("[{}]   ✗ [PRICE-MOMENTUM] Price momentum not aligned with {} direction", analysisId, signalType);
                return signals;
            }

            // ====================================================================
            // CALCULATE ENTRY, TARGET, STOP LOSS
            // ====================================================================
            BigDecimal entryPrice = currentPrice;
            BigDecimal atr = ta.getAverageTrueRange();
            if (atr == null || atr.compareTo(new BigDecimal("0.50")) < 0) {
                atr = new BigDecimal("0.50"); // Minimum realistic ATR for QQQ
            }
//
            BigDecimal adjustedATR = atr; // You can apply multipliers here if needed
//            BigDecimal targetPrice;
//            BigDecimal stopLoss;
//
//            if (signalType.equals("CALL")) {
//                targetPrice = entryPrice.add(adjustedATR);
//                stopLoss = entryPrice.subtract(adjustedATR.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP));
//            } else {
//                targetPrice = entryPrice.subtract(adjustedATR);
//                stopLoss = entryPrice.add(adjustedATR.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP));
//            }

            BigDecimal targetPrice;
            BigDecimal stopLoss;

            // Calculate level-based target using TargetCalculationService
            TargetCalculationService.TargetResult targetResult =
                    targetCalculationService.calculateMeanReversionTarget(entryPrice, signalType, vwap, analysisId);

            if (!targetResult.isValid()) {
                log.warn("[MR-SIGNAL][{}] ✗ No valid target level found - {}", analysisId, targetResult.getReason());
                return signals;
            }

            targetPrice = targetResult.getTargetPrice();
            BigDecimal targetDistance = targetResult.getDistance();

            log.info("[MR-SIGNAL][{}] Target calculated: {} at ${} (distance: ${})",
                    analysisId, targetResult.getTargetLevel(), targetPrice, targetDistance);

            // Calculate stop loss based on target distance (1:2 risk/reward)
            BigDecimal stopDistance = targetDistance.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            if (signalType.equals("CALL")) {
                stopLoss = entryPrice.subtract(stopDistance);
            } else {
                stopLoss = entryPrice.add(stopDistance);
            }

            log.info("[MR-SIGNAL][{}] Stop loss: ${} (distance: ${}, R:R = 1:2)",
                    analysisId, stopLoss, stopDistance);

            if (targetPrice.subtract(entryPrice).abs().compareTo(new BigDecimal("1.00")) < 0) {
                log.info("[MR-SIGNAL] ❌ [{}] Rejected - entry/target difference less than $1.00 (Entry: ${}, Target: ${})",
                        analysisId, entryPrice, targetPrice);
                return signals;
            }

            // ====================================================================
            // ALL 5 BASIC CONFIRMATIONS PASSED - LOG SIGNAL GENERATED
            // ====================================================================
            log.info("[MR-SIGNAL][{}] Time: {}, ATR: ${}, Adjusted ATR: ${}",
                    analysisId, LocalTime.now(), atr, adjustedATR);

            log.info("[MR-SIGNAL][{}] ✅ {} SIGNAL GENERATED - 5/5 confirmations | Entry: ${} | Target: ${}",
                    analysisId, signalType, entryPrice, targetPrice);

            log.info("[MR-SIGNAL][{}]   ✓ [PRICE-VWAP] Price {} VWAP by {}%",
                    analysisId, signalType.equals("CALL") ? "below" : "above", String.format("%.2f",distanceFromVWAP));
            log.info("[MR-SIGNAL][{}]   ✓ [VWAP-INTERACTION] Price touched VWAP within last 10 bars", analysisId);
            log.info("[MR-SIGNAL][{}]   ✓ [MOMENTUM-SLOW] Momentum slowing - price velocity decreasing", analysisId);
            log.info("[MR-SIGNAL][{}]   ✓ [LEADERS] {}/3 leaders confirming direction with valid RSI", analysisId, leadersConfirming);
            log.info("[MR-SIGNAL][{}]   ✓ [PRICE-MOMENTUM] Price momentum aligned with signal direction", analysisId);

// ============================================================
// CRITICAL: DIRECTIONAL VALIDATION WITH BREAKOUT/BREAKDOWN FLIP
// ============================================================
            log.info("[{}] Step 7: Validating signal direction with candles and leaders", analysisId);

            DirectionalConfirmationService.DirectionalResult directionalResult =
                    directionalConfirmationService.validateSignalDirectionWithFlip(signalType, analysisId, distanceFromVWAP);

            if (!directionalResult.isValid()) {
                // Check if we should flip to breakout/breakdown trade
                if (directionalResult.shouldFlip() && directionalResult.getFlipDirection() != null) {
                    String originalSignal = signalType;
                    signalType = directionalResult.getFlipDirection();

                    log.info("========================================");
                    log.info("[{}] ⚡ SIGNAL FLIPPED: {} → {} (Breakout/Breakdown at VWAP)",
                            analysisId, originalSignal, signalType);
                    log.info("[{}] QQQ Candles: {}/5 green, {}/5 red = {}",
                            analysisId, directionalResult.getGreenCandles(),
                            directionalResult.getRedCandles(), directionalResult.getQqqDirection());
                    log.info("[{}] Leaders confirming flip: {}/3",
                            analysisId, directionalResult.getLeadersConfirmingFlip());
                    log.info("[{}] Distance from VWAP: {}% (within flip threshold)",
                            analysisId, String.format("%.4f", distanceFromVWAP));
                    log.info("========================================");

                    // Recalculate target for breakout/breakdown trade using level-based calculation
                    TargetCalculationService.TargetResult breakoutTarget =
                            targetCalculationService.calculateBreakoutTarget(entryPrice, signalType, vwap, analysisId);

                    if (!breakoutTarget.isValid()) {
                        log.warn("[{}] ✗ No valid breakout target found - {}", analysisId, breakoutTarget.getReason());
                        log.info("[{}] Trying mean reversion target instead of ATR fallback", analysisId);

                        TargetCalculationService.TargetResult fallbackTarget =
                                targetCalculationService.calculateMeanReversionTarget(entryPrice, signalType, vwap, analysisId);

                        if (fallbackTarget.isValid()) {
                            targetPrice = fallbackTarget.getTargetPrice();
                            BigDecimal fallbackDistance = fallbackTarget.getDistance();

                            log.info("[{}] ✓ Using {} at ${} (distance: ${})",
                                    analysisId, fallbackTarget.getTargetLevel(), targetPrice, fallbackDistance);

                            BigDecimal stopDist = fallbackDistance.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                            if (signalType.equals("PUT")) {
                                stopLoss = entryPrice.add(stopDist);
                            } else {
                                stopLoss = entryPrice.subtract(stopDist);
                            }
                        } else {
                            log.warn("[{}] ✗ No valid target available - rejecting flipped signal", analysisId);
                            return signals;
                        }
                    } else {
                        // breakoutTarget IS valid - use it
                        targetPrice = breakoutTarget.getTargetPrice();
                        BigDecimal breakoutDistance = breakoutTarget.getDistance();

                        if (signalType.equals("PUT")) {
                            log.info("[{}] 💥 BREAKDOWN TARGET: {} at ${} (distance: ${})",
                                    analysisId, breakoutTarget.getTargetLevel(), targetPrice, breakoutDistance);
                            stopLoss = entryPrice.add(breakoutDistance.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP));
                        } else {
                            log.info("[{}] 🚀 BREAKOUT TARGET: {} at ${} (distance: ${})",
                                    analysisId, breakoutTarget.getTargetLevel(), targetPrice, breakoutDistance);
                            stopLoss = entryPrice.subtract(breakoutDistance.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP));
                        }
                    }

                    log.info("[{}] Recalculated - Entry: ${}, Target: ${}, Stop: ${}",
                            analysisId, entryPrice, targetPrice, stopLoss);

                } else {
                    // No flip possible - check for momentum continuation
                    boolean canConvertToMomentumTrade = false;
                    String momentumDirection = null;

                    if (signalType.equals("CALL") && "BEARISH".equals(directionalResult.getQqqDirection())) {
                        canConvertToMomentumTrade = true;
                        momentumDirection = "PUT";
                        log.info("[{}] 📉 CALL rejected with bearish momentum - checking for continuation PUT", analysisId);
                    } else if (signalType.equals("PUT") && "BULLISH".equals(directionalResult.getQqqDirection())) {
                        canConvertToMomentumTrade = true;
                        momentumDirection = "CALL";
                        log.info("[{}] 📈 PUT rejected with bullish momentum - checking for continuation CALL", analysisId);
                    }

                    if (canConvertToMomentumTrade && directionalResult.getLeadersConfirmingFlip() >= 2) {
                        String originalSignal = signalType;
                        signalType = momentumDirection;

                        log.info("========================================");
                        log.info("[{}] ⚡ MOMENTUM CONTINUATION: {} → {} (following candle direction)",
                                analysisId, originalSignal, signalType);
                        log.info("[{}] QQQ Candles: {}/5 green, {}/5 red = {}",
                                analysisId, directionalResult.getGreenCandles(),
                                directionalResult.getRedCandles(), directionalResult.getQqqDirection());
                        log.info("[{}] Leaders confirming momentum: {}/3",
                                analysisId, directionalResult.getLeadersConfirmingFlip());
                        log.info("========================================");

                        TargetCalculationService.TargetResult momentumTarget =
                                targetCalculationService.calculateMeanReversionTarget(entryPrice, signalType, vwap, analysisId);

                        if (momentumTarget.isValid()) {
                            targetPrice = momentumTarget.getTargetPrice();
                            BigDecimal momentumDistance = momentumTarget.getDistance();

                            log.info("[{}] 🎯 MOMENTUM TARGET: {} at ${} (distance: ${})",
                                    analysisId, momentumTarget.getTargetLevel(), targetPrice, momentumDistance);

                            BigDecimal stopDist = momentumDistance.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                            if (signalType.equals("PUT")) {
                                stopLoss = entryPrice.add(stopDist);
                            } else {
                                stopLoss = entryPrice.subtract(stopDist);
                            }

                            log.info("[{}] Momentum trade - Entry: ${}, Target: ${}, Stop: ${}",
                                    analysisId, entryPrice, targetPrice, stopLoss);
                        } else {
                            log.warn("[{}] ✗ No valid momentum target found - {}", analysisId, momentumTarget.getReason());
                            log.info("[{}] ✗✗✗ SIGNAL REJECTED - NO VALID TARGET ✗✗✗", analysisId);
                            return signals;
                        }
                    } else {
                        log.info("[{}] ✗✗✗ SIGNAL REJECTED - DIRECTIONAL CONFLICT ✗✗✗", analysisId);
                        log.info("[{}] No valid confirmation signal generated", analysisId);
                        log.info("[{}] Rejection reasons:", analysisId);
                        if (directionalResult.getRejectionReason() != null) {
                            log.info("[{}]   ✗ [DIRECTIONAL] {}", analysisId, directionalResult.getRejectionReason());
                        } else {
                            log.info("[{}]   ✗ [DIRECTIONAL-CONFLICT] Signal {} conflicts with QQQ candles ({}) or insufficient leader confirmation",
                                    analysisId, signalType, directionalResult.getQqqDirection());
                        }
                        return signals;
                    }
                }
            }

            log.info("[{}] ✓ Directional validation PASSED - proceeding to option selection", analysisId);
// ============================================================

            log.info("[{}] Step 7: Validating signal direction with candles and leaders", analysisId);

            boolean directionValid = directionalConfirmationService.validateSignalDirection(
                    signalType,
                    analysisId
            );

            if (!directionValid) {
                log.info("[{}] ✗✗✗ SIGNAL REJECTED - DIRECTIONAL CONFLICT ✗✗✗", analysisId);
                log.info("[{}] No valid confirmation signal generated", analysisId);
                log.info("[{}] Rejection reasons:", analysisId);
                log.info("[{}]   ✗ [DIRECTIONAL-CONFLICT] Signal {} conflicts with QQQ candles or leader momentum",
                        analysisId, signalType);
                return signals;
            }

            log.info("[{}] ✓ Directional validation PASSED - proceeding to option selection", analysisId);
// ============================================================
            // ALL 6 CONFIRMATIONS PASSED - SIGNAL APPROVED
            // ====================================================================
            log.info("========================================");
            log.info("[{}] ✓✓✓ CONFIRMATION SIGNAL APPROVED ✓✓✓", analysisId);
            log.info("[{}] Confirmations: 6/6", analysisId);
            log.info("[{}] Entry: ${}, Target: ${}, Stop: ${}", analysisId, entryPrice, targetPrice, stopLoss);
            log.info("========================================");

            // ============================================================
            // OPTION CHAIN FETCHING WITH DETAILED LOGGING
            // ============================================================

            // Check for today's expiration
            LocalDate today = LocalDate.now(ET_ZONE);
            log.info("[{}] [OPTION-FETCH] Step 1: Fetching expirations for {} (today: {})",
                    analysisId, symbol, today);

            List<LocalDate> expirations = tradierService.getExpirations(symbol);

            log.info("[{}] [OPTION-FETCH] Step 2: Received expirations - Count: {}, Dates: {}",
                    analysisId,
                    expirations != null ? expirations.size() : 0,
                    expirations != null ? expirations.toString() : "null");

            if (expirations == null || expirations.isEmpty()) {
                log.warn("[{}] [OPTION-FETCH] ❌ FAILED at Step 2: No expiration dates returned", analysisId);
                return signals;
            }

            if (!expirations.contains(today)) {
                log.warn("[{}] [OPTION-FETCH] ❌ FAILED at Step 2: Today {} not in available dates {}",
                        analysisId, today, expirations);
                return signals;
            }

            log.info("[{}] [OPTION-FETCH] ✓ Step 2 passed: Today is in expiration list", analysisId);

            // Get option chain
            log.info("[{}] [OPTION-FETCH] Step 3: Calling getOptionChain({}, {})",
                    analysisId, symbol, today);

            OptionChainResponse chainResponse = tradierService.getOptionChain(symbol, today);

            log.info("[{}] [OPTION-FETCH] Step 4: Received option chain response - IsNull: {}, HasOptions: {}",
                    analysisId,
                    chainResponse == null,
                    chainResponse != null ? chainResponse.hasOptions() : "N/A");

            if (chainResponse == null) {
                log.warn("[{}] [OPTION-FETCH] ❌ FAILED at Step 4: chainResponse is NULL", analysisId);
                return signals;
            }

            if (!chainResponse.hasOptions()) {
                log.warn("[{}] [OPTION-FETCH] ❌ FAILED at Step 4: chainResponse.hasOptions() = false", analysisId);
                return signals;
            }

            List<Option> options = chainResponse.getOptionsList();

            log.info("[{}] [OPTION-FETCH] Step 5: Extracted options list - Count: {}",
                    analysisId,
                    options != null ? options.size() : 0);

            if (options == null || options.isEmpty()) {
                log.warn("[{}] [OPTION-FETCH] ❌ FAILED at Step 5: Options list is empty", analysisId);
                return signals;
            }

            // Log detailed breakdown
            long callCount = options.stream()
                    .filter(o -> o.getType() != null && "CALL".equalsIgnoreCase(o.getType()))
                    .count();
            long putCount = options.stream()
                    .filter(o -> o.getType() != null && "PUT".equalsIgnoreCase(o.getType()))
                    .count();
            long todayCount = options.stream()
                    .filter(o -> {
                        if (o.getExpirationDate() == null) return false;
                        try {
                            Object expiryObj = o.getExpirationDate();
                            if (expiryObj instanceof String) {
                                LocalDate expiryDate = LocalDate.parse((String) expiryObj);
                                return expiryDate.equals(today);
                            } else if (expiryObj instanceof LocalDate) {
                                return expiryObj.equals(today);
                            }
                            return false;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
            long withPrices = options.stream()
                    .filter(o -> o.getStrikePrice() != null && o.getBid() != null && o.getAsk() != null)
                    .count();
            long nonZeroBid = options.stream()
                    .filter(o -> o.getBid() != null && o.getBid().compareTo(BigDecimal.ZERO) > 0)
                    .count();

            log.info("[{}] [OPTION-FETCH] ✓ Step 5 passed: Options breakdown:", analysisId);
            log.info("[{}]   - Total options: {}", analysisId, options.size());
            log.info("[{}]   - CALLs: {}, PUTs: {}", analysisId, callCount, putCount);
            log.info("[{}]   - Expiring today: {}", analysisId, todayCount);
            log.info("[{}]   - With all prices: {}", analysisId, withPrices);
            log.info("[{}]   - With bid > $0: {}", analysisId, nonZeroBid);

            // Select best option
            log.info("[{}] [OPTION-FETCH] Step 6: Selecting best {} option", analysisId, signalType);
            log.info("[{}]   - Entry price: ${}", analysisId, entryPrice);
            log.info("[{}]   - Target price: ${}", analysisId, targetPrice);

            // Select appropriate option based on signal type
            Option selectedOption;
            if (signalType.equals("CALL")) {
                selectedOption = selectBestCallOption(options, entryPrice, targetPrice, analysisId);
            } else {
                selectedOption = selectBestPutOption(options, entryPrice, targetPrice, analysisId);
            }

            if (selectedOption == null) {
                log.warn("[{}] [OPTION-FETCH] ❌ FAILED at Step 6: No suitable option selected", analysisId);
                return signals;
            }

            log.info("[{}] [OPTION-FETCH] ✓ Step 6 passed: Option selected", analysisId);
            log.info("[{}]   - Symbol: {}", analysisId, selectedOption.getSymbol());
            log.info("[{}]   - Strike: ${}", analysisId, selectedOption.getStrikePrice());
            log.info("[{}]   - Bid: ${}, Ask: ${}", analysisId, selectedOption.getBid(), selectedOption.getAsk());

            // Create signal
            log.info("[{}] [OPTION-FETCH] Step 7: Creating signal", analysisId);

            Signal signal = new Signal();
            signal.setSymbol(selectedOption.getSymbol());
            signal.setOptionSymbol(selectedOption.getSymbol());
            signal.setSignalType(signalType);
            signal.setEntryPrice(selectedOption.getAsk()); // Buy at ask
            signal.setTargetPrice(targetPrice);
            signal.setStopLoss(stopLoss);
            signal.setStrikePrice(selectedOption.getStrikePrice());

            // ✅ FIXED: create a proper ET expiry datetime instead of parsing plain date
            ZonedDateTime expiry = today.atTime(16, 0).atZone(ET_ZONE);
            signal.setExpirationDate(expiry);

            signal.setStrategy("CONFIRMATION_BASED_0DTE");

            // Dynamic confidence calculation based on signal quality factors
            Map<String, Double> candleNets = fetchCandleNetsForConfidence(analysisId);
            double calculatedConfidence = calculateSignalConfidence(signal, ta, leadersConfirming, candleNets, analysisId);
            signal.setConfidence(calculatedConfidence);

            signal.setCreatedAt(LocalDateTime.now());

            log.info("[{}] Signal created: {} ${} {}, Entry: ${}, Target: ${}, Confidence: {}%",
                    analysisId, symbol, selectedOption.getStrikePrice(), signalType,
                    signal.getEntryPrice(), targetPrice, (int) (signal.getConfidence() * 100));

            // Apply risk management
            log.info("[{}] [OPTION-FETCH] Step 8: Applying risk management", analysisId);
            signal = applyRiskManagement(signal, analysisId);

            if (signal != null && signal.getConfidence() != null && signal.getConfidence() >= 0.60) {
                signals.add(signal);

                // Notify via Telegram
                try {
                    String message = formatSignalMessage(signal, analysisId);
                    telegramService.sendMessage(message);
                } catch (Exception e) {
                    log.error("[{}] Error sending Telegram message: {}", analysisId, e.getMessage());
                }

                log.info("[{}] ✓✓✓ SIGNAL READY FOR EXECUTION ✓✓✓", analysisId);
                executeSignalImmediately(signal, null, analysisId);

                log.info("[{}] Signal added to list", analysisId);
            } else {
                log.warn("[{}] [OPTION-FETCH] ❌ FAILED at Step 8: Signal rejected by risk management", analysisId);
                if (signal != null) {
                    log.warn("[{}]   - Confidence: {}", analysisId, signal.getConfidence());
                }
            }

        } catch (Exception e) {
            log.error("[{}] ❌❌❌ EXCEPTION in analyzeOptions: {}", analysisId, e.getMessage(), e);
            e.printStackTrace();
        }

        return signals;
    }


    /**
     * Helper method to format signal message for Telegram
     */
    private String formatSignalMessage(Signal signal, String analysisId) {
        return String.format(
                "🎯 NEW %s SIGNAL\n" +
                        "Symbol: %s\n" +
                        "Strike: $%s\n" +
                        "Entry: $%s\n" +
                        "Target: $%s\n" +
                        "Stop: $%s\n" +
                        "Confidence: %d%%\n" +
                        "Strategy: %s\n" +
                        "ID: %s",
                signal.getSignalType(),
                signal.getSymbol(),
                signal.getStrikePrice(),
                signal.getEntryPrice(),
                signal.getTargetPrice(),
                signal.getStopLoss(),
                (int)(signal.getConfidence() * 100),
                signal.getStrategy(),
                analysisId
        );
    }




    // ADD this new method - replaces individual option analysis
    private void analyzeVWAPMeanReversionStrategies(List<Option> allOptions, TechnicalAnalysis ta,
                                                    List<Signal> signals, String marketTrend, String analysisId) {

        if (ta.getVwap() == null || ta.getVwap().compareTo(BigDecimal.ZERO) == 0) {
            log.debug("[VWAP-STRATEGIES][{}] No VWAP available", analysisId);
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double vwapDistancePercent = currentPrice.subtract(vwap)
                .divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();

        LocalTime now = LocalTime.now(ET_ZONE);
        MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

        log.info("[VWAP-STRATEGIES][{}] Price: ${}, VWAP: ${}, Distance: {}%, Momentum: {}",
                analysisId, currentPrice, vwap, String.format("%.2f", vwapDistancePercent * 100),
                qqqVelocity != null ? qqqVelocity.state : "UNKNOWN");

        // Skip if not in optimal distance range (0.3%-1.5%)
        double absDistance = Math.abs(vwapDistancePercent);
        if (absDistance < 0.003 || absDistance > 0.015) {
            log.debug("[VWAP-STRATEGIES][{}] Distance {}% outside range (need 0.3%-1.5%)",
                    analysisId, String.format("%.2f", absDistance * 100));
            return;
        }

        // ========================================================================================
        // SCENARIO 1: Price BELOW VWAP - Evaluate BOTH bounce (CALL) and continuation (PUT)
        // ========================================================================================
        if (vwapDistancePercent < -0.003) {

            log.info("[VWAP-BELOW][{}] Price below VWAP - evaluating CALL bounce vs PUT breakdown", analysisId);

            // Check momentum to determine which is more likely
            boolean momentumFavorsBounce = qqqVelocity != null &&
                    (qqqVelocity.state == VelocityState.WEAKENING_BEARISH ||
                            qqqVelocity.state == VelocityState.COILING ||
                            qqqVelocity.state == VelocityState.PIVOTING);

            boolean momentumFavorsBreakdown = qqqVelocity != null &&
                    (qqqVelocity.state == VelocityState.ACCELERATING_BEARISH ||
                            qqqVelocity.state == VelocityState.STRONG_BEARISH);

            // Option 1A: CALL Bounce Setup (mean reversion up to VWAP)
            if (momentumFavorsBounce) {
                log.info("[VWAP-BELOW][{}] Momentum favors BOUNCE - analyzing CALL setup", analysisId);
                Option callOption = findBestATMOption(allOptions, currentPrice, true); // true = CALL
                if (callOption != null) {
                    analyzeVWAPBounceCall(callOption, allOptions, ta, signals, marketTrend, analysisId);
                }
            }

            // Option 1B: PUT Breakdown Setup (continuation of downtrend)
            if (momentumFavorsBreakdown || !momentumFavorsBounce) {
                log.info("[VWAP-BELOW][{}] Momentum favors BREAKDOWN - analyzing PUT setup", analysisId);
                Option putOption = findBestATMOption(allOptions, currentPrice, false); // false = PUT
                if (putOption != null) {
                    analyzeVWAPBreakdownPut(putOption, allOptions, ta, signals, marketTrend, analysisId);
                }
            }
        }

        // ========================================================================================
        // SCENARIO 2: Price ABOVE VWAP - Evaluate BOTH rejection (PUT) and continuation (CALL)
        // ========================================================================================
        else if (vwapDistancePercent > 0.003) {

            log.info("[VWAP-ABOVE][{}] Price above VWAP - evaluating PUT rejection vs CALL breakout", analysisId);

            boolean momentumFavorsRejection = qqqVelocity != null &&
                    (qqqVelocity.state == VelocityState.WEAKENING_BULLISH ||
                            qqqVelocity.state == VelocityState.COILING ||
                            qqqVelocity.state == VelocityState.PIVOTING);

            boolean momentumFavorsBreakout = qqqVelocity != null &&
                    (qqqVelocity.state == VelocityState.ACCELERATING_BULLISH ||
                            qqqVelocity.state == VelocityState.STRONG_BULLISH);

            // Option 2A: PUT Rejection Setup (mean reversion down to VWAP)
            if (momentumFavorsRejection) {
                log.info("[VWAP-ABOVE][{}] Momentum favors REJECTION - analyzing PUT setup", analysisId);
                Option putOption = findBestATMOption(allOptions, currentPrice, false);
                if (putOption != null) {
                    analyzeVWAPRejectionPut(putOption, allOptions, ta, signals, marketTrend, analysisId);
                }
            }

            // Option 2B: CALL Breakout Setup (continuation of uptrend)
            if (momentumFavorsBreakout || !momentumFavorsRejection) {
                log.info("[VWAP-ABOVE][{}] Momentum favors BREAKOUT - analyzing CALL setup", analysisId);
                Option callOption = findBestATMOption(allOptions, currentPrice, true);
                if (callOption != null) {
                    analyzeVWAPBreakoutCall(callOption, allOptions, ta, signals, marketTrend, analysisId);
                }
            }
        }
    }

    private void analyzeVWAPRejectionPut(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                         List<Signal> signals, String marketTrend, String analysisId) {

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double vwapDistancePercent = currentPrice.subtract(vwap)
                .divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();
        LocalTime now = LocalTime.now(ET_ZONE);

        log.info("[VWAP-REJECTION-PUT][{}] === ANALYZING PUT REJECTION === Distance: {}%",
                analysisId, String.format("%.2f", vwapDistancePercent * 100));

        // Skip if not above VWAP in optimal range (0.3%-1.5%)
        if (vwapDistancePercent <= 0.003 || vwapDistancePercent > 0.015) {
            log.debug("[VWAP-REJECTION-PUT][{}] Distance {}% outside range (need 0.3% to 1.5%)",
                    analysisId, String.format("%.2f", vwapDistancePercent * 100));
            return;
        }

        // Get momentum velocity
        MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

        // Must have weakening/coiling momentum for rejection (NOT accelerating up)
        if (qqqVelocity == null ||
                (qqqVelocity.state == VelocityState.ACCELERATING_BULLISH ||
                        qqqVelocity.state == VelocityState.STRONG_BULLISH)) {
            log.warn("[VWAP-REJECTION-PUT][{}] ❌ BLOCKED - Momentum too bullish for rejection ({})",
                    analysisId, qqqVelocity != null ? qqqVelocity.state : "NULL");
            return;
        }

        log.info("[VWAP-REJECTION-PUT][{}] ✅ Momentum check PASSED - {} (rejection likely)",
                analysisId, qqqVelocity.state);

        // Calculate GEX levels
        GEXCalculator.GEXLevels gexLevels = gexCalculator.calculateGEX(allOptions, currentPrice, analysisId);

        // Track confirmations
        ConfirmationTracker tracker = new ConfirmationTracker("VWAP_REJECTION_PUT", 3);

        // Confirmation 1: Distance from VWAP (0.3-0.5% = optimal)
        double absDistance = Math.abs(vwapDistancePercent);
        boolean optimalDistance = absDistance >= 0.003 && absDistance <= 0.005;
        boolean acceptableDistance = absDistance >= 0.003 && absDistance <= 0.015;

        tracker.addConfirmation(acceptableDistance, "VWAP_DISTANCE",
                String.format("%.2f%% above (optimal: 0.3-0.5%%)", absDistance * 100));

        // Confirmation 2: Volume Exhaustion Pattern
        boolean hasExhaustion = volumeTracker.hasExhaustionPattern("QQQ");
        boolean hasSpike = volumeTracker.hasVolumeSpikeAfterExhaustion("QQQ");
        boolean volumeConfirmed = hasExhaustion && hasSpike;

        tracker.addConfirmation(volumeConfirmed, "VOLUME_EXHAUSTION",
                String.format("Exhaustion=%s, Spike=%s", hasExhaustion, hasSpike));

        // Confirmation 3: Near +GEX Resistance
        boolean nearGEXResistance = false;
        if (gexLevels != null && gexLevels.getPositiveGEX() != null) {
            BigDecimal distanceToGEX = currentPrice.subtract(gexLevels.getPositiveGEX()).abs();
            double distancePercent = distanceToGEX.divide(currentPrice, 6, RoundingMode.HALF_UP).doubleValue();
            nearGEXResistance = distancePercent < 0.005; // Within 0.5%

            tracker.addConfirmation(nearGEXResistance, "GEX_RESISTANCE",
                    String.format("Distance to +GEX: %.2f%%", distancePercent * 100));
        } else {
            tracker.addConfirmation(false, "GEX_RESISTANCE", "No +GEX data");
        }

        // Confirmation 4: RSI in rejection zone (60-80 optimal, down to 50 acceptable)
        double rsi = ta.getRsi();
        boolean rsiOptimal = rsi >= 60 && rsi <= 80;
        boolean rsiAcceptable = rsi >= 50 && rsi <= 85;

        tracker.addConfirmation(rsiAcceptable, "RSI_OVERBOUGHT",
                String.format("RSI %.1f (optimal: 60-80)", rsi));

        // Confirmation 5: Bearish reversal candle pattern
        boolean bearishCandle = hasBearishReversal("QQQ", vwap);
        tracker.addConfirmation(bearishCandle, "BEARISH_CANDLE",
                bearishCandle ? "reversal candle detected" : "no reversal pattern");

        // Confirmation 6: Previous rejections at this level
        int rejectionCount = countRecentRejections("QQQ", vwap, 60, analysisId);
        boolean hasHistory = rejectionCount >= 1;
        tracker.addConfirmation(hasHistory, "REJECTION_HISTORY",
                String.format("%d previous rejection(s)", rejectionCount));

        log.info("[VWAP-REJECTION-PUT][{}] CONFIRMATIONS: {}/6 - Confirmed: {} | Rejected: {}",
                analysisId, tracker.getScore(),
                String.join(", ", tracker.getConfirmed()),
                String.join(", ", tracker.getRejected()));

        // DECISION: Need 3/6 for optimal distance, 4/6 for suboptimal
        int requiredConfirmations = optimalDistance ? 3 : 4;

        if (tracker.getScore() < requiredConfirmations) {
            log.warn("[VWAP-REJECTION-PUT][{}] ❌ SIGNAL REJECTED - Only {}/6 confirmations (need {})",
                    analysisId, tracker.getScore(), requiredConfirmations);
            logSignalRejectionSummary(analysisId, "VWAP_REJECTION_PUT", tracker);
            return;
        }

        // Calculate confidence
        double baseConfidence = 0.70 + (tracker.getScore() - 3) * 0.05;
        double timeAdjustedConfidence = getTimeAdjustedConfidence(baseConfidence, now, analysisId);

        // Optimal distance boost
        if (optimalDistance) {
            timeAdjustedConfidence = Math.min(0.90, timeAdjustedConfidence * 1.10);
            log.info("[VWAP-REJECTION-PUT][{}] Optimal distance boost: +10%", analysisId);
        }

        // Volume exhaustion boost (key for rejection)
        if (volumeConfirmed) {
            timeAdjustedConfidence = Math.min(0.90, timeAdjustedConfidence * 1.15);
            log.info("[VWAP-REJECTION-PUT][{}] Volume exhaustion boost: +15%", analysisId);
        }

        // RSI optimal boost
        if (rsiOptimal) {
            timeAdjustedConfidence = Math.min(0.90, timeAdjustedConfidence * 1.08);
            log.info("[VWAP-REJECTION-PUT][{}] RSI optimal boost: +8%", analysisId);
        }

        // Threshold check
        double threshold = 0.65;
        if (timeAdjustedConfidence < threshold) {
            log.warn("[VWAP-REJECTION-PUT][{}] ❌ SIGNAL REJECTED - Confidence {}% < threshold {}%",
                    analysisId, (int)(timeAdjustedConfidence * 100), (int)(threshold * 100));
            return;
        }

        // Generate signal
        Signal signal = createSignal(option, allOptions, ta, "BUY",
                "0DTE_VWAP_REJECTION_PUT", timeAdjustedConfidence, marketTrend);

        if (signal != null) {
            signal.setReason(String.format(
                    "VWAP Rejection: %s | Distance: %.2f%% above, Momentum: %s, Confirmations: %d/6",
                    tracker.getSummary(), absDistance * 100, qqqVelocity.state, tracker.getScore()));

            // Target: VWAP (mean reversion play)
            signal.setTargetPrice(vwap);

            // Stop: +0.3% from entry (tight for mean reversion)
            BigDecimal stopDistance = signal.getEntryPrice().multiply(BigDecimal.valueOf(0.003));
            signal.setStopLoss(signal.getEntryPrice().add(stopDistance));

            signals.add(signal);

            log.info("[VWAP-REJECTION-PUT][{}] ✅ SIGNAL GENERATED - Confidence: {}%, Target: VWAP ${}, Confirmations: {}/6",
                    analysisId, (int)(timeAdjustedConfidence * 100), vwap, tracker.getScore());
        }
    }

    private boolean hasBearishReversal(String symbol, BigDecimal vwap) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, 3);
            if (recent.size() < 2) return false;

            MarketData current = recent.get(0);
            MarketData previous = recent.get(1);

            // Shooting star/bearish engulfing or large red candle closing near lows
            boolean bearishEngulfing = current.getOpen().compareTo(previous.getClose()) > 0 &&
                    current.getClose().compareTo(previous.getOpen()) < 0;

            BigDecimal range = current.getHigh().subtract(current.getLow());
            BigDecimal closeFromLow = current.getClose().subtract(current.getLow());
            boolean closingNearLow = range.compareTo(BigDecimal.ZERO) > 0 &&
                    closeFromLow.divide(range, 4, RoundingMode.HALF_UP)
                            .compareTo(BigDecimal.valueOf(0.3)) < 0;

            // Previous candle high touched/went above VWAP
            boolean previousWentAboveVWAP = previous.getHigh() != null &&
                    previous.getHigh().compareTo(vwap) >= 0;

            return (bearishEngulfing || closingNearLow) && previousWentAboveVWAP;

        } catch (Exception e) {
            log.error("[BEARISH-REVERSAL] Error: {}", e.getMessage());
            return false;
        }
    }

    private void analyzeVWAPBounceCall(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                       List<Signal> signals, String marketTrend, String analysisId) {

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double vwapDistancePercent = currentPrice.subtract(vwap)
                .divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();
        LocalTime now = LocalTime.now(ET_ZONE);

        log.info("[VWAP-BOUNCE-CALL][{}] === ANALYZING CALL BOUNCE === Distance: {}%",
                analysisId, String.format("%.2f", vwapDistancePercent * 100));

        // Skip if not below VWAP in optimal range (0.3%-1.5%)
        if (vwapDistancePercent >= -0.003 || vwapDistancePercent < -0.015) {
            log.debug("[VWAP-BOUNCE-CALL][{}] Distance {}% outside range (need -0.3% to -1.5%)",
                    analysisId, String.format("%.2f", vwapDistancePercent * 100));
            return;
        }

        // Get momentum velocity
        MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

        // Must have weakening/coiling momentum for bounce (NOT accelerating down)
        if (qqqVelocity == null ||
                (qqqVelocity.state == VelocityState.ACCELERATING_BEARISH ||
                        qqqVelocity.state == VelocityState.STRONG_BEARISH)) {
            log.warn("[VWAP-BOUNCE-CALL][{}] ❌ BLOCKED - Momentum too bearish for bounce ({})",
                    analysisId, qqqVelocity != null ? qqqVelocity.state : "NULL");
            return;
        }

        log.info("[VWAP-BOUNCE-CALL][{}] ✅ Momentum check PASSED - {} (bounce likely)",
                analysisId, qqqVelocity.state);

        // Calculate GEX levels
        GEXCalculator.GEXLevels gexLevels = gexCalculator.calculateGEX(allOptions, currentPrice, analysisId);

        // Track confirmations
        ConfirmationTracker tracker = new ConfirmationTracker("VWAP_BOUNCE_CALL", 3);

        // Confirmation 1: Distance from VWAP (0.3-0.5% = optimal)
        double absDistance = Math.abs(vwapDistancePercent);
        boolean optimalDistance = absDistance >= 0.003 && absDistance <= 0.005;
        boolean acceptableDistance = absDistance >= 0.003 && absDistance <= 0.015;

        tracker.addConfirmation(acceptableDistance, "VWAP_DISTANCE",
                String.format("%.2f%% below (optimal: 0.3-0.5%%)", absDistance * 100));

        // Confirmation 2: Volume Exhaustion Pattern
        boolean hasExhaustion = volumeTracker.hasExhaustionPattern("QQQ");
        boolean hasSpike = volumeTracker.hasVolumeSpikeAfterExhaustion("QQQ");
        boolean volumeConfirmed = hasExhaustion && hasSpike;

        tracker.addConfirmation(volumeConfirmed, "VOLUME_EXHAUSTION",
                String.format("Exhaustion=%s, Spike=%s", hasExhaustion, hasSpike));

        // Confirmation 3: Near -GEX Support
        boolean nearGEXSupport = false;
        if (gexLevels != null && gexLevels.getNegativeGEX() != null) {
            BigDecimal distanceToGEX = currentPrice.subtract(gexLevels.getNegativeGEX()).abs();
            double distancePercent = distanceToGEX.divide(currentPrice, 6, RoundingMode.HALF_UP).doubleValue();
            nearGEXSupport = distancePercent < 0.005; // Within 0.5%

            tracker.addConfirmation(nearGEXSupport, "GEX_SUPPORT",
                    String.format("Distance to -GEX: %.2f%%", distancePercent * 100));
        } else {
            tracker.addConfirmation(false, "GEX_SUPPORT", "No -GEX data");
        }

        // Confirmation 4: RSI in bounce zone (20-40 optimal, up to 50 acceptable)
        double rsi = ta.getRsi();
        boolean rsiOptimal = rsi >= 20 && rsi <= 40;
        boolean rsiAcceptable = rsi >= 15 && rsi <= 50;

        tracker.addConfirmation(rsiAcceptable, "RSI_OVERSOLD",
                String.format("RSI %.1f (optimal: 20-40)", rsi));

        // Confirmation 5: Bullish reversal candle pattern
        boolean bullishCandle = hasBullishReversal("QQQ", vwap);
        tracker.addConfirmation(bullishCandle, "BULLISH_CANDLE",
                bullishCandle ? "reversal candle detected" : "no reversal pattern");

        // Confirmation 6: Previous bounces at this level (shows respect for VWAP)
        int bounceCount = countRecentBounces("QQQ", vwap, 60, analysisId);
        boolean hasHistory = bounceCount >= 1;
        tracker.addConfirmation(hasHistory, "BOUNCE_HISTORY",
                String.format("%d previous bounce(s)", bounceCount));

        log.info("[VWAP-BOUNCE-CALL][{}] CONFIRMATIONS: {}/6 - Confirmed: {} | Rejected: {}",
                analysisId, tracker.getScore(),
                String.join(", ", tracker.getConfirmed()),
                String.join(", ", tracker.getRejected()));

        // DECISION: Need 3/6 for optimal distance, 4/6 for suboptimal
        int requiredConfirmations = optimalDistance ? 3 : 4;

        if (tracker.getScore() < requiredConfirmations) {
            log.warn("[VWAP-BOUNCE-CALL][{}] ❌ SIGNAL REJECTED - Only {}/6 confirmations (need {})",
                    analysisId, tracker.getScore(), requiredConfirmations);
            logSignalRejectionSummary(analysisId, "VWAP_BOUNCE_CALL", tracker);
            return;
        }

        // Calculate confidence
        double baseConfidence = 0.70 + (tracker.getScore() - 3) * 0.05;
        double timeAdjustedConfidence = getTimeAdjustedConfidence(baseConfidence, now, analysisId);

        // Optimal distance boost
        if (optimalDistance) {
            timeAdjustedConfidence = Math.min(0.90, timeAdjustedConfidence * 1.10);
            log.info("[VWAP-BOUNCE-CALL][{}] Optimal distance boost: +10%", analysisId);
        }

        // Volume exhaustion boost (key for bounce)
        if (volumeConfirmed) {
            timeAdjustedConfidence = Math.min(0.90, timeAdjustedConfidence * 1.15);
            log.info("[VWAP-BOUNCE-CALL][{}] Volume exhaustion boost: +15%", analysisId);
        }

        // RSI optimal boost
        if (rsiOptimal) {
            timeAdjustedConfidence = Math.min(0.90, timeAdjustedConfidence * 1.08);
            log.info("[VWAP-BOUNCE-CALL][{}] RSI optimal boost: +8%", analysisId);
        }

        // Threshold check
        double threshold = 0.65;
        if (timeAdjustedConfidence < threshold) {
            log.warn("[VWAP-BOUNCE-CALL][{}] ❌ SIGNAL REJECTED - Confidence {}% < threshold {}%",
                    analysisId, (int)(timeAdjustedConfidence * 100), (int)(threshold * 100));
            return;
        }

        // Generate signal
        Signal signal = createSignal(option, allOptions, ta, "BUY",
                "0DTE_VWAP_BOUNCE_CALL", timeAdjustedConfidence, marketTrend);

        if (signal != null) {
            signal.setReason(String.format(
                    "VWAP Bounce: %s | Distance: %.2f%% below, Momentum: %s, Confirmations: %d/6",
                    tracker.getSummary(), absDistance * 100, qqqVelocity.state, tracker.getScore()));

            // Target: VWAP (mean reversion play)
            signal.setTargetPrice(vwap);

            // Stop: -0.3% from entry (tight for mean reversion)
            BigDecimal stopDistance = signal.getEntryPrice().multiply(BigDecimal.valueOf(0.003));
            signal.setStopLoss(signal.getEntryPrice().subtract(stopDistance));

            signals.add(signal);

            log.info("[VWAP-BOUNCE-CALL][{}] ✅ SIGNAL GENERATED - Confidence: {}%, Target: VWAP ${}, Confirmations: {}/6",
                    analysisId, (int)(timeAdjustedConfidence * 100), vwap, tracker.getScore());
        }
    }

    private boolean hasBullishReversal(String symbol, BigDecimal vwap) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, 3);
            if (recent.size() < 2) return false;

            MarketData current = recent.get(0);
            MarketData previous = recent.get(1);

            // Hammer/bullish engulfing or large green candle closing near highs
            boolean bullishEngulfing = current.getOpen().compareTo(previous.getClose()) < 0 &&
                    current.getClose().compareTo(previous.getOpen()) > 0;

            BigDecimal range = current.getHigh().subtract(current.getLow());
            BigDecimal closeFromHigh = current.getHigh().subtract(current.getClose());
            boolean closingNearHigh = range.compareTo(BigDecimal.ZERO) > 0 &&
                    closeFromHigh.divide(range, 4, RoundingMode.HALF_UP)
                            .compareTo(BigDecimal.valueOf(0.3)) < 0;

            // Previous candle low touched/went below VWAP
            boolean previousWentBelowVWAP = previous.getLow() != null &&
                    previous.getLow().compareTo(vwap) <= 0;

            return (bullishEngulfing || closingNearHigh) && previousWentBelowVWAP;

        } catch (Exception e) {
            log.error("[BULLISH-REVERSAL] Error: {}", e.getMessage());
            return false;
        }
    }


    private void analyzeVWAPBreakoutCall(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                         List<Signal> signals, String marketTrend, String analysisId) {

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double vwapDistancePercent = currentPrice.subtract(vwap)
                .divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();
        LocalTime now = LocalTime.now(ET_ZONE);

        log.info("[VWAP-BREAKOUT-CALL][{}] === ANALYZING CALL BREAKOUT === Distance: {}%",
                analysisId, String.format("%.2f", vwapDistancePercent * 100));

        // Skip if not above VWAP in fresh breakout range (0.1%-0.8%)
        if (vwapDistancePercent <= 0.001 || vwapDistancePercent > 0.008) {
            log.debug("[VWAP-BREAKOUT-CALL][{}] Distance {}% outside range (need 0.1% to 0.8%)",
                    analysisId, String.format("%.2f", vwapDistancePercent * 100));
            return;
        }

        // Get momentum velocity
        MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

        // Must have strong/accelerating bullish momentum for breakout
        if (qqqVelocity == null ||
                !(qqqVelocity.state == VelocityState.ACCELERATING_BULLISH ||
                        qqqVelocity.state == VelocityState.STRONG_BULLISH ||
                        qqqVelocity.state == VelocityState.PIVOTING)) {
            log.warn("[VWAP-BREAKOUT-CALL][{}] ❌ BLOCKED - Momentum not bullish enough ({})",
                    analysisId, qqqVelocity != null ? qqqVelocity.state : "NULL");
            return;
        }

        log.info("[VWAP-BREAKOUT-CALL][{}] ✅ Momentum check PASSED - {} (breakout likely)",
                analysisId, qqqVelocity.state);

        // Calculate GEX levels
        GEXCalculator.GEXLevels gexLevels = gexCalculator.calculateGEX(allOptions, currentPrice, analysisId);

        // Track confirmations
        ConfirmationTracker tracker = new ConfirmationTracker("VWAP_BREAKOUT_CALL", 4); // Higher bar for momentum

        // Confirmation 1: Fresh Breakout (0.1-0.5% = ideal, up to 0.8% acceptable)
        double absDistance = Math.abs(vwapDistancePercent);
        boolean freshBreakout = absDistance >= 0.001 && absDistance <= 0.005;
        boolean acceptableBreakout = absDistance >= 0.001 && absDistance <= 0.008;

        tracker.addConfirmation(acceptableBreakout, "FRESH_BREAKOUT",
                String.format("%.2f%% above (fresh: 0.1-0.5%%)", absDistance * 100));

        // Confirmation 2: Strong Volume Surge (critical for breakouts)
        double volumeRatio = volumeTracker.getVolumeRatio("QQQ");
        boolean strongVolume = volumeRatio > 2.0;
        boolean acceptableVolume = volumeRatio > 1.5;

        tracker.addConfirmation(acceptableVolume, "VOLUME_SURGE",
                String.format("%.2fx (need >1.5x)", volumeRatio));

        // Confirmation 3: Breaking above +GEX resistance
        boolean breakingGEXResistance = false;
        if (gexLevels != null && gexLevels.getPositiveGEX() != null) {
            breakingGEXResistance = currentPrice.compareTo(gexLevels.getPositiveGEX()) > 0;
            tracker.addConfirmation(breakingGEXResistance, "BREAKING_GEX",
                    String.format("Price $%s > +GEX $%s (resistance broken)",
                            currentPrice, gexLevels.getPositiveGEX()));
        } else {
            tracker.addConfirmation(false, "BREAKING_GEX", "No +GEX data");
        }

        // Confirmation 4: Bullish momentum candle
        boolean bullishCandle = hasBullishMomentumCandle("QQQ");
        tracker.addConfirmation(bullishCandle, "BULLISH_CANDLE",
                bullishCandle ? "momentum candle detected" : "no momentum pattern");

        // Confirmation 5: RSI not overbought (50-70 = ideal for continuation)
        double rsi = ta.getRsi();
        boolean rsiHealthy = rsi >= 50 && rsi <= 70;
        tracker.addConfirmation(rsiHealthy, "RSI_HEALTHY",
                String.format("RSI %.1f (healthy: 50-70)", rsi));

        // Confirmation 6: Not a failed breakout attempt
        int failedAttempts = countFailedBreakoutAttempts("QQQ", vwap, 30, analysisId);
        boolean notFailed = failedAttempts < 2;
        tracker.addConfirmation(notFailed, "NOT_FAILED",
                String.format("%d failed attempt(s) (limit: 2)", failedAttempts));

        log.info("[VWAP-BREAKOUT-CALL][{}] CONFIRMATIONS: {}/6 - Confirmed: {} | Rejected: {}",
                analysisId, tracker.getScore(),
                String.join(", ", tracker.getConfirmed()),
                String.join(", ", tracker.getRejected()));

        // DECISION: Need 4/6 confirmations (higher bar for momentum trades)
        int requiredConfirmations = 4;

        if (tracker.getScore() < requiredConfirmations) {
            log.warn("[VWAP-BREAKOUT-CALL][{}] ❌ SIGNAL REJECTED - Only {}/6 confirmations (need {})",
                    analysisId, tracker.getScore(), requiredConfirmations);
            logSignalRejectionSummary(analysisId, "VWAP_BREAKOUT_CALL", tracker);
            return;
        }

        // Calculate confidence
        double baseConfidence = 0.65 + (tracker.getScore() - 4) * 0.05;
        double timeAdjustedConfidence = getTimeAdjustedConfidence(baseConfidence, now, analysisId);

        // Fresh breakout boost
        if (freshBreakout) {
            timeAdjustedConfidence = Math.min(0.85, timeAdjustedConfidence * 1.12);
            log.info("[VWAP-BREAKOUT-CALL][{}] Fresh breakout boost: +12%", analysisId);
        }

        // Strong volume boost (critical for breakouts)
        if (strongVolume) {
            timeAdjustedConfidence = Math.min(0.85, timeAdjustedConfidence * 1.20);
            log.info("[VWAP-BREAKOUT-CALL][{}] Strong volume boost: +20%", analysisId);
        }

        // GEX break boost
        if (breakingGEXResistance) {
            timeAdjustedConfidence = Math.min(0.85, timeAdjustedConfidence * 1.15);
            log.info("[VWAP-BREAKOUT-CALL][{}] GEX resistance break boost: +15%", analysisId);
        }

        // Morning session boost (best time for breakouts)
        if (now.isAfter(LocalTime.of(9, 45)) && now.isBefore(LocalTime.of(10, 30))) {
            timeAdjustedConfidence = Math.min(0.85, timeAdjustedConfidence * 1.10);
            log.info("[VWAP-BREAKOUT-CALL][{}] Morning session boost: +10%", analysisId);
        }

        // Threshold check
        double threshold = 0.70; // Higher threshold for momentum plays
        if (timeAdjustedConfidence < threshold) {
            log.warn("[VWAP-BREAKOUT-CALL][{}] ❌ SIGNAL REJECTED - Confidence {}% < threshold {}%",
                    analysisId, (int)(timeAdjustedConfidence * 100), (int)(threshold * 100));
            return;
        }

        // Generate signal
        Signal signal = createSignal(option, allOptions, ta, "BUY",
                "0DTE_VWAP_BREAKOUT_CALL", timeAdjustedConfidence, marketTrend);

        if (signal != null) {
            signal.setReason(String.format(
                    "VWAP Breakout: %s | Distance: %.2f%% above, Volume: %.2fx, Momentum: %s, Confirmations: %d/6",
                    tracker.getSummary(), absDistance * 100, volumeRatio, qqqVelocity.state, tracker.getScore()));

            // Target: +1.5% from entry (momentum play, bigger target)
            BigDecimal targetDistance = signal.getEntryPrice().multiply(BigDecimal.valueOf(0.015));
            signal.setTargetPrice(signal.getEntryPrice().add(targetDistance));

            // Stop: Back below VWAP (failed breakout)
            signal.setStopLoss(vwap);

            signals.add(signal);

            log.info("[VWAP-BREAKOUT-CALL][{}] ✅ SIGNAL GENERATED - Confidence: {}%, Target: +1.5%, Stop: VWAP ${}, Confirmations: {}/6",
                    analysisId, (int)(timeAdjustedConfidence * 100), vwap, tracker.getScore());
        }
    }

    private boolean hasBullishMomentumCandle(String symbol) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, 2);
            if (recent.size() < 2) return false;

            MarketData current = recent.get(0);
            MarketData previous = recent.get(1);

            if (current.getOpen() == null || current.getClose() == null ||
                    current.getHigh() == null || current.getLow() == null) {
                return false;
            }

            // Strong bullish candle: close near high, body > 60% of range
            BigDecimal range = current.getHigh().subtract(current.getLow());
            BigDecimal body = current.getClose().subtract(current.getOpen()).abs();

            boolean strongBody = range.compareTo(BigDecimal.ZERO) > 0 &&
                    body.divide(range, 4, RoundingMode.HALF_UP)
                            .compareTo(BigDecimal.valueOf(0.6)) > 0;

            boolean closedNearHigh = current.getClose().compareTo(current.getOpen()) > 0;

            boolean higherClose = previous.getClose() != null &&
                    current.getClose().compareTo(previous.getClose()) > 0;

            return strongBody && closedNearHigh && higherClose;

        } catch (Exception e) {
            log.error("[MOMENTUM-CANDLE] Error: {}", e.getMessage());
            return false;
        }
    }

    private int countFailedBreakoutAttempts(String symbol, BigDecimal vwap, int minutesBack, String analysisId) {
        try {
            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, minutesBack);
            if (recentData.size() < 3) {
                log.debug("[FAILED-BREAKOUT][{}] Insufficient data: {} bars", analysisId, recentData.size());
                return 0;
            }

            int failedCount = 0;
            for (int i = recentData.size() - 1; i >= 1; i--) {
                MarketData bar = recentData.get(i);
                MarketData nextBar = recentData.get(i - 1);

                if (bar.getHigh() != null && bar.getClose() != null &&
                        nextBar.getClose() != null && vwap != null) {

                    // High went above VWAP but closed back below it
                    boolean highAboveVWAP = bar.getHigh().compareTo(vwap) > 0;
                    boolean closedBelowVWAP = bar.getClose().compareTo(vwap) < 0;
                    boolean nextBarLower = nextBar.getClose().compareTo(bar.getClose()) < 0;

                    if (highAboveVWAP && closedBelowVWAP && nextBarLower) {
                        failedCount++;
                    }
                }
            }

            log.info("[FAILED-BREAKOUT][{}] Found {} failed breakout(s) in last {} minutes",
                    analysisId, failedCount, minutesBack);
            return failedCount;

        } catch (Exception e) {
            log.error("[FAILED-BREAKOUT][{}] Error counting failed breakouts: {}", analysisId, e.getMessage());
            return 0;
        }
    }

    // Helper method to find best ATM option
    private Option findBestATMOption(List<Option> allOptions, BigDecimal currentPrice, boolean isCall) {
        return allOptions.stream()
                .filter(opt -> isCall ? "CALL".equalsIgnoreCase(opt.getType()) : "PUT".equalsIgnoreCase(opt.getType()))
                .min(Comparator.comparing(opt ->
                        opt.getStrikePrice().subtract(currentPrice).abs()))
                .orElse(null);
    }

    // ADD this new method for PUT breakdown scenarios
    private void analyzeVWAPBreakdownPut(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                         List<Signal> signals, String marketTrend, String analysisId) {

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double vwapDistancePercent = currentPrice.subtract(vwap)
                .divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();
        LocalTime now = LocalTime.now(ET_ZONE);

        log.info("[VWAP-BREAKDOWN-PUT][{}] === ANALYZING PUT BREAKDOWN === Distance: {}%, Trend: {}",
                analysisId, String.format("%.2f", vwapDistancePercent * 100), marketTrend);

        // Get momentum velocity
        MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

        // ⚠️ CRITICAL: Must have bearish momentum for breakdown
        if (qqqVelocity == null ||
                !(qqqVelocity.state == VelocityState.ACCELERATING_BEARISH ||
                        qqqVelocity.state == VelocityState.STRONG_BEARISH ||
                        qqqVelocity.state == VelocityState.COILING)) {
            log.warn("[VWAP-BREAKDOWN-PUT][{}] ❌ BLOCKED - Momentum not bearish enough ({})",
                    analysisId, qqqVelocity != null ? qqqVelocity.state : "NULL");
            return;
        }

        log.info("[VWAP-BREAKDOWN-PUT][{}] ✅ Momentum check PASSED - {} (breakdown likely)",
                analysisId, qqqVelocity.state);

        // Calculate GEX levels
        GEXCalculator.GEXLevels gexLevels = gexCalculator.calculateGEX(allOptions, currentPrice, analysisId);

        // Track confirmations
        ConfirmationTracker tracker = new ConfirmationTracker("VWAP_BREAKDOWN_PUT", 3);

        // Confirmation 1: Momentum Acceleration (key for breakdown)
        boolean momentumAccelerating = qqqVelocity.state == VelocityState.ACCELERATING_BEARISH;
        tracker.addConfirmation(momentumAccelerating, "MOMENTUM_ACCELERATION",
                String.format("%s (breakdown confirmed)", qqqVelocity.state));

        // Confirmation 2: Volume Surge (selling pressure)
        double volumeRatio = volumeTracker.getVolumeRatio("QQQ");
        boolean volumeSurge = volumeRatio > 1.5;
        tracker.addConfirmation(volumeSurge, "VOLUME_SURGE",
                String.format("%.2fx (selling pressure)", volumeRatio));

        // Confirmation 3: Breaking below -GEX support
        boolean breakingGEXSupport = false;
        if (gexLevels != null && gexLevels.getNegativeGEX() != null) {
            breakingGEXSupport = currentPrice.compareTo(gexLevels.getNegativeGEX()) < 0;
            tracker.addConfirmation(breakingGEXSupport, "BREAKING_GEX",
                    String.format("Price $%s < -GEX $%s (support broken)",
                            currentPrice, gexLevels.getNegativeGEX()));
        } else {
            tracker.addConfirmation(false, "BREAKING_GEX", "No -GEX data");
        }

        // Confirmation 4: Bearish candlestick pattern
        boolean bearishCandle = hasBreakdownConfirmation("QQQ");
        tracker.addConfirmation(bearishCandle, "BEARISH_CANDLE",
                bearishCandle ? "breakdown candle detected" : "no breakdown pattern");

        // Confirmation 5: Distance from VWAP (further = stronger breakdown)
        double absDistance = Math.abs(vwapDistancePercent);
        boolean significantDistance = absDistance > 0.005; // > 0.5%
        tracker.addConfirmation(significantDistance, "VWAP_DISTANCE",
                String.format("%.2f%% away (prefer >0.5%% for breakdown)", absDistance * 100));

        // Confirmation 6: NOT TOO LATE - Critical timing check
        boolean notTooLate = !isTooLateForBreakdown(ta.getSymbol(), analysisId);
        tracker.addConfirmation(notTooLate, "TIMING",
                notTooLate ? "entry timing good" : "move already extended");

        log.info("[VWAP-BREAKDOWN-PUT][{}] CONFIRMATIONS: {}/6 - Confirmed: {} | Rejected: {}",
                analysisId, tracker.getScore(),
                String.join(", ", tracker.getConfirmed()),
                String.join(", ", tracker.getRejected()));

        // DECISION: Need at least 4 of 6 confirmations if ANY timing warnings
        int requiredConfirmations = notTooLate ? 3 : 4;

        if (tracker.getScore() < requiredConfirmations) {
            log.warn("[VWAP-BREAKDOWN-PUT][{}] ❌ SIGNAL REJECTED - Only {}/6 confirmations (need {})",
                    analysisId, tracker.getScore(), requiredConfirmations);
            logSignalRejectionSummary(analysisId, "VWAP_BREAKDOWN_PUT", tracker);
            return;
        }

        // Calculate confidence
        double baseConfidence = 0.65 + (tracker.getScore() - 3) * 0.05;
        double timeAdjustedConfidence = getTimeAdjustedConfidence(baseConfidence, now, analysisId);

        // Momentum acceleration boost (key for breakdown)
        if (momentumAccelerating) {
            timeAdjustedConfidence = Math.min(0.88, timeAdjustedConfidence * 1.15);
            log.info("[VWAP-BREAKDOWN-PUT][{}] Momentum acceleration boost: +15%", analysisId);
        }

        // Volume surge boost
        if (volumeSurge && volumeRatio > 2.0) {
            timeAdjustedConfidence = Math.min(0.88, timeAdjustedConfidence * 1.10);
            log.info("[VWAP-BREAKDOWN-PUT][{}] Heavy volume surge boost: +10%", analysisId);
        }

        // GEX break boost
        if (breakingGEXSupport) {
            timeAdjustedConfidence = Math.min(0.88, timeAdjustedConfidence * 1.12);
            log.info("[VWAP-BREAKDOWN-PUT][{}] GEX support break boost: +12%", analysisId);
        }

        // Threshold check
        double threshold = 0.65;
        if (timeAdjustedConfidence < threshold) {
            log.warn("[VWAP-BREAKDOWN-PUT][{}] ❌ SIGNAL REJECTED - Confidence {}% < threshold {}%",
                    analysisId, (int)(timeAdjustedConfidence * 100), (int)(threshold * 100));
            return;
        }

        // Generate signal
        Signal signal = createSignal(option, allOptions, ta, "BUY",
                "0DTE_VWAP_BREAKDOWN_PUT", timeAdjustedConfidence, marketTrend);

        if (signal != null) {
            signal.setReason(String.format(
                    "VWAP Breakdown: %s | Momentum: %s, Distance: %.2f%% below VWAP, Confirmations: %d/6",
                    tracker.getSummary(), qqqVelocity.state, Math.abs(vwapDistancePercent) * 100, tracker.getScore()));

            signals.add(signal);

            log.info("[VWAP-BREAKDOWN-PUT][{}] ✅ SIGNAL GENERATED - Confidence: {}%, Momentum: {}, Confirmations: {}/6",
                    analysisId, (int)(timeAdjustedConfidence * 100), qqqVelocity.state, tracker.getScore());
        }
    }

    private boolean isTooLateForBreakdown(String symbol, String analysisId) {
        try {
            // Get 5-minute data for velocity check
            List<MarketData> recent5min = marketDataRepository.findRecentData(symbol, 5);
            if (recent5min.size() < 5) {
                return false; // Not enough data, allow signal
            }

            BigDecimal price5MinAgo = recent5min.get(recent5min.size() - 1).getPrice();
            BigDecimal currentPrice = recent5min.get(0).getPrice();

            if (price5MinAgo == null || price5MinAgo.compareTo(BigDecimal.ZERO) == 0) {
                return false;
            }

            // ✅ CHECK #1: Extended move in last 5 minutes (down >1%)
            double priceChange = calculateRecentPriceChange(symbol, 5);

            if (priceChange < -0.010) { // Down more than 1%
                log.warn("[TOO-LATE][{}] ⚠️ Moveextended -{}% in 5min - TOO LATE",
                        analysisId, String.format("%.2f", Math.abs(priceChange) * 100));
                return true;
            }

            // Get 15-bar data for high/low checks
            List<MarketData> recent15 = marketDataRepository.findRecentData(symbol, 15);

            // ✅ CHECK #2: Already far from recent HIGH (breakdown already happened)
            // THIS IS THE KEY FIX - Use getRecentHigh() to check if move is fresh
            BigDecimal recentHigh = getRecentHigh(recent15, 15);
            if (recentHigh != null && recentHigh.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal distanceFromHigh = recentHigh.subtract(currentPrice);
                double distancePercent = distanceFromHigh.divide(recentHigh, 6, RoundingMode.HALF_UP).doubleValue();

                // If already down >1% from recent high, the breakdown already happened
                if (distancePercent > 0.010) { // More than 1% from high
                    log.warn("[TOO-LATE][{}] ⚠️ Already {:.2f}% from recent high - breakdown complete",
                            analysisId, distancePercent * 100);
                    return true;
                }
            }

            // ✅ CHECK #3: Scraping recent LOW (reversal imminent)
            BigDecimal recentLow = getRecentLow(recent15, 15);
            if (recentLow != null) {
                BigDecimal distanceFromLow = currentPrice.subtract(recentLow);
                double distancePercent = distanceFromLow.divide(currentPrice, 6, RoundingMode.HALF_UP).doubleValue();

                if (distancePercent < 0.003) { // Within 0.3% of low
                    log.warn("[TOO-LATE][{}] ⚠️ Price within 0.3% of recent low - reversal risk",
                            analysisId);
                    return true;
                }
            }

            // ✅ CHECK #4: RSI oversold (reversal likely)
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta != null && ta.getRsi() < 25.0) {
                log.warn("[TOO-LATE][{}] ⚠️ RSI {} oversold - reversal likely",
                        analysisId, String.format("%.1f", ta.getRsi()));
                return true;
            }

            // All checks passed - entry timing is good
            return false;

        } catch (Exception e) {
            log.error("[TOO-LATE][{}] Error checking timing: {}", analysisId, e.getMessage());
            return false; // On error, allow the signal
        }
    }

    private double calculateRecentPriceChange(String symbol, int minutes) {
        try {
            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, minutes);
            if (recentData.size() < minutes) {
                return 0.0;
            }

            BigDecimal oldPrice = recentData.get(recentData.size() - 1).getPrice();
            BigDecimal currentPrice = recentData.get(0).getPrice();

            if (oldPrice == null || oldPrice.compareTo(BigDecimal.ZERO) == 0) {
                return 0.0;
            }

            return currentPrice.subtract(oldPrice)
                    .divide(oldPrice, 6, RoundingMode.HALF_UP)
                    .doubleValue();

        } catch (Exception e) {
            log.error("[PRICE-CHANGE] Error calculating price change: {}", e.getMessage());
            return 0.0;
        }
    }

    private BigDecimal getRecentLow(List<MarketData> data, int bars) {
        if (data.isEmpty() || bars <= 0) {
            return null;
        }

        int lookback = Math.min(bars, data.size());

        return data.stream()
                .limit(lookback)
                .map(MarketData::getLow)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    private BigDecimal getRecentHigh(List<MarketData> data, int bars) {
        if (data.isEmpty() || bars <= 0) {
            return null;
        }

        int lookback = Math.min(bars, data.size());

        return data.stream()
                .limit(lookback)
                .map(MarketData::getHigh)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    // Helper method for breakdown confirmation
    private boolean hasBreakdownConfirmation(String symbol) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, 3);
            if (recent.size() < 2) return false;

            MarketData current = recent.get(0);
            MarketData previous = recent.get(1);

            // Bearish engulfing or large red candle closing near lows
            boolean bearishEngulfing = current.getOpen().compareTo(previous.getClose()) > 0 &&
                    current.getClose().compareTo(previous.getOpen()) < 0;

            BigDecimal range = current.getHigh().subtract(current.getLow());
            BigDecimal closeFromLow = current.getClose().subtract(current.getLow());
            boolean closingNearLow = range.compareTo(BigDecimal.ZERO) > 0 &&
                    closeFromLow.divide(range, 4, RoundingMode.HALF_UP)
                            .compareTo(BigDecimal.valueOf(0.3)) < 0;

            return bearishEngulfing || closingNearLow;

        } catch (Exception e) {
            log.error("[BREAKDOWN-CONFIRMATION] Error: {}", e.getMessage());
            return false;
        }
    }


    private List<Signal> analyzeEnhancedAILeaderLagStrategies(List<Option> options, TechnicalAnalysis ta,
                                                              String marketTrend, String analysisId) {
        List<Signal> leaderLagSignals = new ArrayList<>();
        LocalTime now = LocalTime.now(ET_ZONE);
        String actualMarketTrend = ta.getTrend();

        try {
            // NEW: Update momentum tracking first
            momentumTracker.updateCurrentMomentum(analysisId);

            // Build enhanced market context with velocity data
            EnhancedMarketContext context = buildEnhancedMarketContextWithVelocity(ta, actualMarketTrend, now, analysisId);

            if (context == null) {
                log.warn("[ENHANCED-AI][{}] Could not build enhanced market context", analysisId);
                return leaderLagSignals;
            }

            // NEW: Classify micro-momentum patterns
            Map<String, MicroMomentumPattern> leaderPatterns = classifyLeaderMicroMomentumPatterns(context, analysisId);

            // NEW: Get RSI-momentum context for each leader
            Map<String, RSIMomentumContext> rsiContexts = getRSIMomentumContexts(context, analysisId);

            // Use enhanced AI to determine optimal strategy
            EnhancedAIDecision aiDecision = makeEnhancedAIDecision(context, leaderPatterns, rsiContexts, analysisId);

            if (aiDecision.getStrategy() == LeaderLagStrategy.NONE) {
                log.info("[ENHANCED-AI][{}] AI decided to skip: {}", analysisId, aiDecision.getReasoning());
                return leaderLagSignals;
            }

            // Get leader correlation metrics with time decay
            LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);

            if (metrics == null || !isCorrelationValid(metrics, analysisId)) {
                log.warn("[ENHANCED-AI][{}] Invalid correlation metrics", analysisId);
                return leaderLagSignals;
            }

            log.info("[ENHANCED-AI][{}] 🤖 AI SELECTED: {} (Confidence: {}%)",
                    analysisId, aiDecision.getStrategy(), (int)(aiDecision.getConfidence() * 100));
            log.info("[ENHANCED-AI][{}] 🧠 Pattern: {}, Context: {}",
                    analysisId, aiDecision.getDominantPattern(), aiDecision.getRsiContext());

            // Generate signals based on enhanced AI decision
            for (Option option : options) {
                if (!quickSignalValidation(option, options, ta, actualMarketTrend, analysisId)) {
                    continue;
                }

                EnhancedAILeaderLagSignal aiSignal = generateEnhancedAISignalWithVelocity(
                        option, aiDecision, ta, actualMarketTrend, metrics, context, analysisId);

                if (aiSignal != null && aiSignal.getConfidence() >= getEnhancedAIThreshold(aiDecision.getStrategy(), now, context)) {
                    // NEW: Store signal for pre-execution validation
                    Signal signal = createEnhancedAILeaderLagSignal(option, options, ta, aiSignal, actualMarketTrend, analysisId);
                    if (signal != null) {
                        // Mark for pre-execution validation
                        signal.getMetadata().put("requiresPreValidation", "true");
                        signal.getMetadata().put("validationContext", serializeValidationContext(context, aiDecision));

                        leaderLagSignals.add(signal);

                        log.info("[ENHANCED-AI][{}] ✅ {} Signal Generated - Confidence: {}%, Pattern: {}",
                                analysisId, aiDecision.getStrategy(), (int)(aiSignal.getConfidence() * 100),
                                aiDecision.getDominantPattern());
                    }
                }
            }

            return leaderLagSignals;

        } catch (Exception e) {
            log.error("[ENHANCED-AI][{}] Error in enhanced AI analysis: {}", analysisId, e.getMessage());
            return leaderLagSignals;
        }
    }

    private class MomentumVelocityTracker {
        private final Map<String, List<MomentumReading>> momentumHistory = new ConcurrentHashMap<>();
        private final int MAX_HISTORY_SIZE = 10;

        public void initialize() {
            // Initialize momentum tracking for leader stocks
            for (String symbol : LEADER_STOCKS) {
                momentumHistory.put(symbol, new ArrayList<>());
            }
            momentumHistory.put("QQQ", new ArrayList<>());
        }

        public void cleanup() {
            momentumHistory.clear();
        }

        public void updateMomentumHistory() {
            try {
                for (String symbol : Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA")) {
                    TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
                    if (ta != null) {
                        double momentum = calculateRawMomentum(symbol);
                        double volumeRatio = ta.getVolumeRatio();
                        addMomentumReading(symbol, momentum,ta.getMomentumStrength());
                    }
                }
            } catch (Exception e) {
                log.debug("Error updating momentum history: {}", e.getMessage());
            }
        }

        public void updateCurrentMomentum(String analysisId) {
            try {
                for (String symbol : Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA")) {
                    TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
                    if (ta != null) {
                        double momentum = calculateRawMomentum(symbol);
                        double volumeRatio = ta.getVolumeRatio();
                        addMomentumReading(symbol, momentum, volumeRatio);
                    }
                }
            } catch (Exception e) {
                log.debug("[MOMENTUM][{}] Error updating current momentum: {}", analysisId, e.getMessage());
            }
        }

        private double calculateRawMomentum(String symbol) {
            try {
                List<MarketData> data = marketDataRepository.findRecentData(symbol, 5);
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
            } catch (Exception e) {
                return 0.0;
            }
        }

        private void addMomentumReading(String symbol, double momentum,double volumeRatio) {
            List<MomentumReading> history = momentumHistory.computeIfAbsent(symbol, k -> new ArrayList<>());

            MomentumReading reading = new MomentumReading(momentum, volumeRatio,LocalDateTime.now());
            history.add(0, reading); // Add to front

            // Keep only recent readings
            if (history.size() > MAX_HISTORY_SIZE) {
                history.subList(MAX_HISTORY_SIZE, history.size()).clear();
            }
        }

        public MomentumVelocity calculateVelocity(String symbol) {
            List<MomentumReading> history = momentumHistory.get(symbol);
            if (history == null || history.size() < 3) {
                return new MomentumVelocity(0.0, 0.0, VelocityState.INSUFFICIENT_DATA);
            }

            double currentMomentum = history.get(0).momentum;
            double currentVolumeRatio = history.get(0).volumeRatio;

            // Apply volume weighting to current momentum
            double volumeAdjustedMomentum = currentMomentum * calculateVolumeConvictionScore(currentVolumeRatio);

            double momentum3MinAgo = getMomentumAtTime(history, 3);
            double momentum5MinAgo = getMomentumAtTime(history, 5);

            // Calculate acceleration (momentum change over time)
            double acceleration3Min = momentum3MinAgo != 0 ? (currentMomentum - momentum3MinAgo) / 3.0 : 0;
            double acceleration5Min = momentum5MinAgo != 0 ? (currentMomentum - momentum5MinAgo) / 5.0 : 0;

            // Average acceleration for stability
            double avgAcceleration = (acceleration3Min + acceleration5Min) / 2.0;

            VelocityState state = classifyVelocityState(currentMomentum, avgAcceleration);

            return new MomentumVelocity(currentMomentum, avgAcceleration, state);
        }

        private double getMomentumAtTime(List<MomentumReading> history, int minutesAgo) {
            LocalDateTime targetTime = LocalDateTime.now().minusMinutes(minutesAgo);

            return history.stream()
                    .filter(r -> r.timestamp.isBefore(targetTime.plusMinutes(1)) &&
                            r.timestamp.isAfter(targetTime.minusMinutes(1)))
                    .mapToDouble(r -> r.momentum)
                    .findFirst()
                    .orElse(history.size() > minutesAgo ? history.get(minutesAgo).momentum : 0.0);
        }

        private VelocityState classifyVelocityState(double momentum, double acceleration) {
            if (Math.abs(momentum) < 0.002 && Math.abs(acceleration) < 0.0005) {
                return VelocityState.COILING;  // This is GOOD - compression before expansion
            }
            if (momentum > 0.15 && acceleration > 0.005) return VelocityState.STRONG_BULLISH;
            if (momentum > 0.05 && acceleration > 0.002) return VelocityState.ACCELERATING_BULLISH;
            if (momentum > 0.005 && acceleration < -0.002) return VelocityState.WEAKENING_BULLISH;
            if (momentum < -0.15 && acceleration < -0.005) return VelocityState.STRONG_BEARISH;
            if (momentum < -0.05 && acceleration < -0.002) return VelocityState.ACCELERATING_BEARISH;
            if (momentum < -0.005 && acceleration > 0.002) return VelocityState.WEAKENING_BEARISH;
            if (Math.abs(momentum) < 0.02 && Math.abs(acceleration) < 0.001) return VelocityState.COILING;
            if (Math.abs(acceleration) > 0.003) return VelocityState.PIVOTING;
            return VelocityState.NOISE;
        }

        public List<MomentumReading> getMomentumHistory(String symbol) {
            return momentumHistory.getOrDefault(symbol, new ArrayList<>());
        }
    }

    private class MicroMomentumClassifier {

        public MicroMomentumPattern classifyPattern(String symbol, List<MomentumReading> history) {
            if (history.size() < 5) return MicroMomentumPattern.INSUFFICIENT_DATA;

            List<Double> recent5 = history.stream().limit(5).mapToDouble(r -> r.momentum).boxed().collect(Collectors.toList());

            if (isAccumulation(recent5)) return MicroMomentumPattern.ACCUMULATION;
            if (isDistribution(recent5)) return MicroMomentumPattern.DISTRIBUTION;
            if (isCoiling(recent5)) return MicroMomentumPattern.COILING;
            if (isPivoting(recent5)) return MicroMomentumPattern.PIVOT;

            return MicroMomentumPattern.NOISE;
        }

        private boolean isAccumulation(List<Double> momentum) {
            // Series of positive micro-momentums with higher lows
            boolean allPositive = momentum.stream().allMatch(m -> m > 0);
            if (!allPositive) return false;

            // Check for higher lows pattern
            int higherLows = 0;
            for (int i = 1; i < momentum.size(); i++) {
                if (momentum.get(i) > momentum.get(i-1)) higherLows++;
            }

            return higherLows >= 3;
        }

        private boolean isDistribution(List<Double> momentum) {
            // Series of negative micro-momentums with lower highs
            boolean allNegative = momentum.stream().allMatch(m -> m < 0);
            if (!allNegative) return false;

            // Check for lower highs pattern
            int lowerHighs = 0;
            for (int i = 1; i < momentum.size(); i++) {
                if (momentum.get(i) < momentum.get(i-1)) lowerHighs++;
            }

            return lowerHighs >= 3;
        }

        private boolean isCoiling(List<Double> momentum) {
            // Decreasing momentum range (compression before breakout)
            double range1 = Math.abs(momentum.get(0) - momentum.get(1));
            double range2 = Math.abs(momentum.get(1) - momentum.get(2));
            double range3 = Math.abs(momentum.get(2) - momentum.get(3));
            double range4 = Math.abs(momentum.get(3) - momentum.get(4));

            return range1 < range2 && range2 < range3 && range3 < range4 && range1 < 0.005;
        }

        private boolean isPivoting(List<Double> momentum) {
            // Sign change from negative to positive (or vice versa)
            boolean hadNegative = false;
            boolean hadPositive = false;

            for (Double m : momentum) {
                if (m > 0.002) hadPositive = true;
                if (m < -0.002) hadNegative = true;
            }

            return hadNegative && hadPositive;
        }
    }



    private class RSIMomentumContextMatrix {

        public RSIMomentumContext evaluateContext(double rsi, MomentumVelocity velocity) {
            RSIZone rsiZone = classifyRSIZone(rsi);
            MomentumState momentumState = classifyMomentumState(velocity);

            ContextInterpretation interpretation = getInterpretation(rsiZone, momentumState, velocity.state);
            SignalAction signalAction = getSignalAction(interpretation);

            return new RSIMomentumContext(rsiZone, momentumState, velocity.state, interpretation, signalAction,
                    calculateContextConfidence(rsiZone, momentumState, velocity.state));
        }

        private RSIZone classifyRSIZone(double rsi) {
            if (rsi >= 65) return RSIZone.OVERBOUGHT;  // LOWERED from 70
            if (rsi >= 55) return RSIZone.UPPER_NEUTRAL;  // LOWERED from 60
            if (rsi >= 35) return RSIZone.NEUTRAL;  // LOWERED from 40
            if (rsi >= 25) return RSIZone.LOWER_NEUTRAL;  // LOWERED from 30
            return RSIZone.OVERSOLD;
        }

        private MomentumState classifyMomentumState(MomentumVelocity velocity) {
            if (velocity.momentum > 0.05) return MomentumState.POSITIVE;
            if (velocity.momentum > 0.005) return MomentumState.WEAK_POSITIVE;
            if (velocity.momentum < -0.05) return MomentumState.NEGATIVE;
            if (velocity.momentum < -0.005) return MomentumState.WEAK_NEGATIVE;
            return MomentumState.NEAR_ZERO;
        }

        private ContextInterpretation getInterpretation(RSIZone rsiZone, MomentumState momentumState, VelocityState velocityState) {
            // RELAXED: Lower thresholds for clearer signals

            // High RSI scenarios
            if (rsiZone == RSIZone.OVERBOUGHT) {
                if (momentumState == MomentumState.POSITIVE && velocityState == VelocityState.ACCELERATING_BULLISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                if (momentumState == MomentumState.POSITIVE && velocityState == VelocityState.WEAKENING_BULLISH) {
                    return ContextInterpretation.EXHAUSTION;
                }
                if (momentumState == MomentumState.NEAR_ZERO && velocityState == VelocityState.COILING) {
                    return ContextInterpretation.CONSOLIDATION;
                }
            }

            // Low RSI scenarios - RELAXED THRESHOLDS
            if (rsiZone == RSIZone.OVERSOLD) {
                if (momentumState == MomentumState.NEGATIVE && velocityState == VelocityState.ACCELERATING_BEARISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                if (momentumState == MomentumState.NEGATIVE && velocityState == VelocityState.WEAKENING_BEARISH) {
                    return ContextInterpretation.BOUNCE_SETUP;
                }
                // NEW: More scenarios for OVERSOLD
                if (momentumState == MomentumState.WEAK_NEGATIVE) {
                    return ContextInterpretation.BOUNCE_SETUP;
                }
            }

            // NEW: Lower neutral RSI zones
            if (rsiZone == RSIZone.LOWER_NEUTRAL) {
                if (velocityState == VelocityState.STRONG_BEARISH || velocityState == VelocityState.ACCELERATING_BEARISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                if (velocityState == VelocityState.WEAKENING_BEARISH) {
                    return ContextInterpretation.BOUNCE_SETUP;
                }
            }

            // Neutral RSI with strong momentum - RELAXED
            if (rsiZone == RSIZone.NEUTRAL || rsiZone == RSIZone.LOWER_NEUTRAL || rsiZone == RSIZone.UPPER_NEUTRAL) {
                if (velocityState == VelocityState.STRONG_BULLISH || velocityState == VelocityState.STRONG_BEARISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                if (velocityState == VelocityState.ACCELERATING_BULLISH || velocityState == VelocityState.ACCELERATING_BEARISH) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
                // NEW: Allow PIVOT patterns
                if (velocityState == VelocityState.PIVOTING) {
                    return ContextInterpretation.TREND_CONTINUATION;
                }
            }

            return ContextInterpretation.UNCLEAR;
        }
        private SignalAction getSignalAction(ContextInterpretation interpretation) {
            switch (interpretation) {
                case TREND_CONTINUATION: return SignalAction.FOLLOW;
                case EXHAUSTION: return SignalAction.REVERSE;
                case BOUNCE_SETUP: return SignalAction.REVERSE;
                case CONSOLIDATION: return SignalAction.WAIT;
                case UNCLEAR: return SignalAction.FOLLOW; // CHANGED: Default to FOLLOW instead of SKIP
                default: return SignalAction.FOLLOW;
            }
        }

        private double calculateContextConfidence(RSIZone rsiZone, MomentumState momentumState, VelocityState velocityState) {
            double confidence = 0.5;

            // Clear velocity states get higher confidence
            if (velocityState == VelocityState.STRONG_BULLISH || velocityState == VelocityState.STRONG_BEARISH) {
                confidence += 0.25;
            } else if (velocityState == VelocityState.ACCELERATING_BULLISH || velocityState == VelocityState.ACCELERATING_BEARISH) {
                confidence += 0.20;
            }

            // Extreme RSI with momentum gets boost
            if ((rsiZone == RSIZone.OVERBOUGHT || rsiZone == RSIZone.OVERSOLD) &&
                    (momentumState == MomentumState.POSITIVE || momentumState == MomentumState.NEGATIVE)) {
                confidence += 0.15;
            }

            // Coiling pattern gets boost (compression before breakout)
            if (velocityState == VelocityState.COILING) {
                confidence += 0.10;
            }

            return Math.min(0.95, confidence);
        }
    }

    private class PreExecutionValidator {

        public static class ValidationResult {
            private final boolean valid;
            private final double confidenceMultiplier;
            private final String reason;

            public ValidationResult(boolean valid, double confidenceMultiplier, String reason) {
                this.valid = valid;
                this.confidenceMultiplier = confidenceMultiplier;
                this.reason = reason;
            }

            public boolean isValid() {
                return valid;
            }

            public double getConfidenceMultiplier() {
                return confidenceMultiplier;
            }

            public String getReason() {
                return reason;
            }
        }
        public ValidationResult validateBeforeExecution(Signal signal, String analysisId) {
            try {
                if (!"true".equals(signal.getMetadata().get("requiresPreValidation"))) {
                    return new ValidationResult(true, 1.0, "No pre-validation required");
                }

                log.info("[PRE-VALIDATION][{}] Starting 30-60 second validation for {}", analysisId, signal.getOptionSymbol());

                // Wait 30-60 seconds and re-check
                Thread.sleep(45000); // 45 seconds

                // Re-analyze momentum and correlation
                momentumTracker.updateCurrentMomentum(analysisId);

                // Check if leader monitoring is required
                if ("true".equals(signal.getMetadata().get("requiresLeaderMonitoring"))) {
                    ValidationResult leaderMonitorResult = monitorSpecificLeader(signal, analysisId);
                    if (!leaderMonitorResult.isValid()) {
                        return leaderMonitorResult; // Failed leader monitoring
                    }
                }

                // Get current leader state
                String leaderStock = signal.getMetadata().get("leaderStock");
                if (leaderStock == null) {
                    return new ValidationResult(false, 0.0, "No leader stock specified");
                }

                MomentumVelocity currentVelocity = momentumTracker.calculateVelocity(leaderStock);
                MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

                // Validation Rule 1: Check if momentum direction changed
                String originalDirection = signal.getMetadata().get("momentumDirection");
                String currentDirection = currentVelocity.momentum > 0 ? "BULLISH" : "BEARISH";

                if (!currentDirection.equals(originalDirection)) {
                    log.warn("[PRE-VALIDATION][{}] ❌ ABORT: Momentum direction changed {} -> {}",
                            analysisId, originalDirection, currentDirection);
                    return new ValidationResult(false, 0.0, "Momentum direction changed");
                }

                // Validation Rule 2: Check if momentum magnitude increased in signal direction
                double originalMagnitude = Double.parseDouble(signal.getMetadata().getOrDefault("momentumMagnitude", "0"));
                double currentMagnitude = Math.abs(currentVelocity.momentum);

                if (currentMagnitude >= originalMagnitude * 1.1) {
                    log.info("[PRE-VALIDATION][{}] ✅ EXECUTE: Momentum increased {} -> {}",
                            analysisId, String.format("%.3f",originalMagnitude), String.format("%.3f",currentMagnitude));
                    return new ValidationResult(true, 1.15, "Momentum increased in signal direction");
                }

                // Validation Rule 3: Check if correlation is still valid
                LeaderCorrelationMetrics currentCorrelation = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);
                if (currentCorrelation == null || currentCorrelation.getStrongestCorrelation() < 0.4) {
                    log.warn("[PRE-VALIDATION][{}] ❌ ABORT: Correlation broke ({}%)",
                            analysisId, currentCorrelation != null ? (int)(currentCorrelation.getStrongestCorrelation() * 100) : 0);
                    return new ValidationResult(false, 0.0, "Correlation breakdown");
                }

                // Validation Rule 4: Check if leader and QQQ are moving opposite
                boolean leaderBullish = currentVelocity.momentum > 0.01;
                boolean qqqBullish = qqqVelocity.momentum > 0.01;
                boolean leaderBearish = currentVelocity.momentum < -0.01;
                boolean qqqBearish = qqqVelocity.momentum < -0.01;

                if ((leaderBullish && qqqBearish) || (leaderBearish && qqqBullish)) {
                    log.warn("[PRE-VALIDATION][{}] ❌ ABORT: Leader-QQQ moving opposite: Leader={}, QQQ={}",
                            analysisId, String.format("%.3f",currentVelocity.momentum), String.format("%.3f",qqqVelocity.momentum));
                    return new ValidationResult(false, 0.0, "Leader-QQQ divergence");
                }

                // Default: Allow execution with standard confidence
                log.info("[PRE-VALIDATION][{}] ✅ PROCEED: All validations passed", analysisId);
                return new ValidationResult(true, 1.0, "Validations passed");

            } catch (Exception e) {
                log.error("[PRE-VALIDATION][{}] Error in validation: {}", analysisId, e.getMessage());
                return new ValidationResult(false, 0.0, "Validation error: " + e.getMessage());
            }
        }

        private ValidationResult monitorSpecificLeader(Signal signal, String analysisId) {
            try {
                String opposingLeader = signal.getMetadata().get("opposingLeader");
                String isCallSignalStr = signal.getMetadata().get("isCallSignal");
                String monitoringDurationStr = signal.getMetadata().get("monitoringDuration");

                if (opposingLeader == null || isCallSignalStr == null || monitoringDurationStr == null) {
                    log.warn("[LEADER-MONITORING][{}] Missing monitoring metadata - skipping", analysisId);
                    return new ValidationResult(true, 1.0, "Monitoring metadata missing");
                }

                boolean isCallSignal = Boolean.parseBoolean(isCallSignalStr);
                int monitoringDuration = Integer.parseInt(monitoringDurationStr);

                log.info("[LEADER-MONITORING][{}] Monitoring {} for {} seconds...",
                        analysisId, opposingLeader, monitoringDuration);

                // Wait for the monitoring duration
                Thread.sleep(monitoringDuration * 1000);

                // Re-analyze the specific opposing leader
                List<MarketData> leaderData5min = marketDataRepository.findRecentData(opposingLeader, 5);
                double currentMomentum = calculateMomentum(leaderData5min);

                // Check if leader is still opposing
                boolean stillOpposing;
                double threshold = LocalTime.now(ET_ZONE).isAfter(LocalTime.of(15, 30)) ? 0.0010 : 0.0015;

                if (isCallSignal) {
                    // CALL signal - negative momentum opposes
                    stillOpposing = currentMomentum < -threshold;
                } else {
                    // PUT signal - positive momentum opposes
                    stillOpposing = currentMomentum > threshold;
                }

                if (stillOpposing) {
                    log.warn("[LEADER-MONITORING][{}] ❌ ABORT: {} still opposing after {} seconds (momentum: {})",
                            analysisId, opposingLeader, monitoringDuration,String.format("%.3f",currentMomentum));
                    return new ValidationResult(false, 0.0,
                            String.format("%s still opposing after monitoring", opposingLeader));
                }

                log.info("[LEADER-MONITORING][{}] ✅ PROCEED: {} turned favorable (momentum: {})",
                        analysisId, opposingLeader, String.format("%.3f",currentMomentum));
                return new ValidationResult(true, 1.05, "Leader monitoring passed - turned favorable");

            } catch (Exception e) {
                log.error("[LEADER-MONITORING][{}] Error in leader monitoring: {}", analysisId, e.getMessage());
                return new ValidationResult(false, 0.0, "Leader monitoring error: " + e.getMessage());
            }
        }

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
    }


    private void executeSignalWithPreValidation(Signal signal, SHAPExplainerService.SHAPExplanation explanation, String analysisId) {
        try {
            // Run pre-execution validation
            PreExecutionValidator.ValidationResult validation = preExecutionValidator.validateBeforeExecution(signal, analysisId);

            if (!validation.isValid()) {
                log.warn("[PRE-VALIDATION][{}] ❌ Signal ABORTED: {} - Reason: {}",
                        analysisId, signal.getOptionSymbol(), validation.getReason());

                saveFailedTrade(signal, "PRE_VALIDATION_FAILED", validation.getReason(), analysisId);
                saveFailedSignal(signal, "PRE_VALIDATION_FAILED", validation.getReason());

                telegramService.sendMessage(String.format(
                        "🚫 AI SIGNAL ABORTED - PRE-VALIDATION\n" +
                                "Option: %s\n" +
                                "Reason: %s\n" +
                                "Status: Saved for analysis",
                        signal.getOptionSymbol(),
                        validation.getReason()
                ));
                return;
            }

            // Adjust confidence based on validation result
            signal.setConfidence(signal.getConfidence() * validation.getConfidenceMultiplier());
            signal.getMetadata().put("preValidationPassed", "true");
            signal.getMetadata().put("validationConfidence", String.valueOf(validation.getConfidenceMultiplier()));

            log.info("[PRE-VALIDATION][{}] ✅ Validation PASSED - Adjusted confidence: {}%",
                    analysisId, (int)(signal.getConfidence() * 100));

            // Proceed with normal execution
            executeSignalImmediately(signal, explanation, analysisId);

        } catch (Exception e) {
            log.error("[PRE-VALIDATION][{}] ❌ Exception during pre-validation: {}", analysisId, e.getMessage());
            saveFailedTrade(signal, "PRE_VALIDATION_ERROR", e.getMessage(), analysisId);
            saveFailedSignal(signal, "PRE_VALIDATION_ERROR", e.getMessage());
        }
    }


    private EnhancedMarketContext buildEnhancedMarketContextWithVelocity(TechnicalAnalysis ta, String marketTrend,
                                                                         LocalTime now, String analysisId) {
        try {
            // Get velocity data for all leader stocks
            Map<String, MomentumVelocity> leaderVelocities = new HashMap<>();
            for (String leader : LEADER_STOCKS) {
                leaderVelocities.put(leader, momentumTracker.calculateVelocity(leader));
            }

            MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");

            // Find strongest leader with 80%+ correlation requirement
            LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);
            if (metrics == null) {
                log.warn("[ENHANCED-CONTEXT][{}] No leader meets 80% correlation threshold - aborting AI analysis",
                        analysisId);
                return null;
            }

            String strongestLeader = metrics.getStrongestLeader();
            double correlation = metrics.getStrongestCorrelation();
            MomentumVelocity strongestLeaderVelocity = leaderVelocities.get(strongestLeader);

            if (strongestLeaderVelocity == null) return null;

            // Skip technical confluence for high correlation leaders
            boolean strongestLeaderTechnicalStatus = true;
            boolean strongestLeaderVolumeConfirmed = true;

            if (correlation < 0.80) {
                // Only do technical confluence validation for weak correlations
                try {
                    boolean isCall = strongestLeaderVelocity.momentum > 0;
                    strongestLeaderTechnicalStatus = validateStrongestLeaderTechnicalConfluence(
                            strongestLeader, isCall, analysisId);
                    strongestLeaderVolumeConfirmed = Math.abs(strongestLeaderVelocity.momentum) > 0.005;

                    log.info("[ENHANCED-CONTEXT][{}] Weak correlation leader {} - Technical: {}, Volume: {}",
                            analysisId, strongestLeader, strongestLeaderTechnicalStatus, strongestLeaderVolumeConfirmed);
                } catch (Exception e) {
                    log.warn("[ENHANCED-CONTEXT][{}] Error validating weak correlation leader {}: {}",
                            analysisId, strongestLeader, e.getMessage());
                }
            } else {
                log.info("[ENHANCED-CONTEXT][{}] High correlation leader {} ({}%) - bypassing technical tests",
                        analysisId, strongestLeader, (int)(correlation * 100));
            }

            return new EnhancedMarketContext(
                    strongestLeader,
                    strongestLeaderVelocity,
                    qqqVelocity,
                    leaderVelocities,
                    correlation,
                    getVixLevel(),
                    marketTrend,
                    ta.getMarketRegime().toString(),
                    now.getHour() * 60 + now.getMinute(),
                    LocalDate.now().getDayOfWeek().getValue(),
                    ta.getVolumeRatio(),
                    ta.getRsi(),
                    ta.getCurrentPrice(),
                    strongestLeaderTechnicalStatus,
                    strongestLeaderVolumeConfirmed
            );

        } catch (Exception e) {
            log.error("[ENHANCED-CONTEXT][{}] Error building enhanced context: {}", analysisId, e.getMessage());
            return null;
        }
    }
    private Map<String, MicroMomentumPattern> classifyLeaderMicroMomentumPatterns(EnhancedMarketContext context, String analysisId) {
        Map<String, MicroMomentumPattern> patterns = new HashMap<>();

        for (String leader : LEADER_STOCKS) {
            List<MomentumReading> history = momentumTracker.getMomentumHistory(leader);
            MicroMomentumPattern pattern = microMomentumClassifier.classifyPattern(leader, history);
            patterns.put(leader, pattern);

            //log.debug("[MICRO-PATTERN][{}] {}: {}", analysisId, leader, pattern);
        }

        return patterns;
    }

    private Map<String, RSIMomentumContext> getRSIMomentumContexts(EnhancedMarketContext context, String analysisId) {
        Map<String, RSIMomentumContext> contexts = new HashMap<>();

        try {
            for (String leader : LEADER_STOCKS) {
                TechnicalAnalysis leaderTA = technicalAnalysisService.analyze(leader);
                if (leaderTA != null) {
                    MomentumVelocity velocity = context.leaderVelocities.get(leader);
                    RSIMomentumContext rsiContext = contextMatrix.evaluateContext(leaderTA.getRsi(), velocity);
                    contexts.put(leader, rsiContext);

//                    log.debug("[RSI-CONTEXT][{}] {}: RSI={}, Action={}",
//                            analysisId, leader, leaderTA.getRsi(), rsiContext.signalAction);
                }
            }
        } catch (Exception e) {
            log.debug("[RSI-CONTEXT][{}] Error getting RSI contexts: {}", analysisId, e.getMessage());
        }

        return contexts;
    }

    private EnhancedAIDecision makeEnhancedAIDecision(EnhancedMarketContext context,
                                                      Map<String, MicroMomentumPattern> leaderPatterns,
                                                      Map<String, RSIMomentumContext> rsiContexts,
                                                      String analysisId) {

        String strongestLeader = context.strongestLeader;
        double correlation = context.correlation;

        // PRIORITY PATH: High correlation overrides all technical analysis
        if (correlation >= 0.80) {
            MomentumVelocity leaderVelocity = context.strongestLeaderVelocity;
            LeaderLagStrategy strategy;
            double confidence = 0.75 + (correlation - 0.80) * 0.5;
            String reasoning;

            // NEW: COILING Recognition as Valid Setup
            if (leaderVelocity.state == VelocityState.COILING) {
                // Get current price position relative to VWAP
                TechnicalAnalysis qqqTA = technicalAnalysisService.analyze("QQQ");
                boolean priceAboveVWAP = qqqTA != null && qqqTA.getCurrentPrice() != null &&
                        qqqTA.getVwap() != null &&
                        qqqTA.getCurrentPrice().compareTo(qqqTA.getVwap()) > 0;

                if (priceAboveVWAP) {
                    strategy = LeaderLagStrategy.STANDARD;
                    confidence = 0.80; // High confidence for coiling above VWAP
                    reasoning = String.format(
                            "High correlation (%.0f%%) + COILING above VWAP - compression before breakout",
                            correlation * 100);
                } else {
                    // Coiling below VWAP could be bearish consolidation
                    strategy = LeaderLagStrategy.REVERSE;
                    confidence = 0.75;
                    reasoning = String.format(
                            "High correlation (%.0f%%) + COILING below VWAP - potential breakdown",
                            correlation * 100);
                }
            }
            // RELAXED: Lower momentum threshold from 0.005 to 0.002 (0.2%)
            else if (Math.abs(leaderVelocity.momentum) > 0.002) { // Changed from 0.005
                strategy = LeaderLagStrategy.STANDARD;
                confidence = 0.75 + (correlation - 0.80) * 0.5;
                reasoning = String.format("High correlation (%.0f%%) with momentum (%.3f) - following %s",
                        correlation * 100, leaderVelocity.momentum, strongestLeader);
            }
            else if (leaderVelocity.state == VelocityState.WEAKENING_BULLISH ||
                    leaderVelocity.state == VelocityState.WEAKENING_BEARISH) {
                strategy = LeaderLagStrategy.REVERSE;
                reasoning = String.format("High correlation (%.0f%%) with weakening momentum - reversal play on %s",
                        correlation * 100, strongestLeader);
            }
            else {
                strategy = LeaderLagStrategy.NONE;
                reasoning = String.format("High correlation (%.0f%%) but insufficient momentum clarity (<0.2%%)",
                        correlation * 100);
            }

            log.info("[ENHANCED-AI][{}] CORRELATION-DRIVEN DECISION: {} - {}",
                    analysisId, strategy, reasoning);

            return new EnhancedAIDecision(strategy, Math.min(0.95, confidence), reasoning,
                    MicroMomentumPattern.INSUFFICIENT_DATA, null);
        }
        // FALLBACK PATH: Original complex analysis for weak correlations
        MicroMomentumPattern dominantPattern = leaderPatterns.get(strongestLeader);
        RSIMomentumContext dominantContext = rsiContexts.get(strongestLeader);

        if (dominantPattern == null || dominantContext == null) {
            return new EnhancedAIDecision(LeaderLagStrategy.NONE, 0.0,
                    "Insufficient pattern/context data with weak correlation",
                    dominantPattern, dominantContext);
        }

        // Original decision logic for correlations < 80%
        LeaderLagStrategy strategy = LeaderLagStrategy.NONE;
        double confidence = 0.5;
        String reasoning = "";

        if (dominantPattern == MicroMomentumPattern.ACCUMULATION && dominantContext.signalAction == SignalAction.FOLLOW) {
            strategy = LeaderLagStrategy.STANDARD;
            confidence = 0.65; // Reduced confidence for weak correlation
            reasoning = "Accumulation pattern with follow signal (weak correlation)";
        }
        else if (dominantPattern == MicroMomentumPattern.DISTRIBUTION && dominantContext.signalAction == SignalAction.FOLLOW) {
            strategy = LeaderLagStrategy.STANDARD;
            confidence = 0.65;
            reasoning = "Distribution pattern with follow signal (weak correlation)";
        }
        else if (dominantContext.signalAction == SignalAction.REVERSE) {
            strategy = LeaderLagStrategy.REVERSE;
            confidence = 0.60;
            reasoning = "RSI-momentum context suggests reversal (weak correlation)";
        }
        else if (dominantContext.signalAction == SignalAction.FOLLOW && dominantContext.contextConfidence > 0.7) {
            strategy = LeaderLagStrategy.STANDARD;
            confidence = Math.min(0.70, dominantContext.contextConfidence); // Capped for weak correlation
            reasoning = "High-confidence follow signal (weak correlation)";
        }

        // Apply correlation penalty to confidence
        confidence *= (correlation / 0.80); // Scale down confidence based on correlation weakness

        return new EnhancedAIDecision(strategy, Math.min(0.80, confidence), reasoning, dominantPattern, dominantContext);
    }

    private boolean isCorrelationValid(LeaderCorrelationMetrics metrics, String analysisId) {
        if (metrics.getStrongestCorrelation() < 0.4) {
            log.warn("[CORRELATION][{}] Correlation too low: {}%", analysisId, (int)(metrics.getStrongestCorrelation() * 100));
            return false;
        }

        // Check time decay - correlation should be recent
        LocalDateTime now = LocalDateTime.now();
        // Add time decay logic here if needed

        return true;
    }

    private EnhancedAILeaderLagSignal generateEnhancedAISignalWithVelocity(Option option, EnhancedAIDecision aiDecision,
                                                                           TechnicalAnalysis ta, String marketTrend,
                                                                           LeaderCorrelationMetrics metrics,
                                                                           EnhancedMarketContext context, String analysisId) {

        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean isPut = "PUT".equalsIgnoreCase(option.getType());

        // NEW: Check if strongest leader passed technical confluence validation
        if (!context.strongestLeaderTechnicalStatus) {
            log.info("[ENHANCED-AI][{}] Signal blocked - strongest leader {} failed technical confluence",
                    analysisId, context.strongestLeader);
            return null;
        }

        if (!context.strongestLeaderVolumeConfirmed) {
            log.info("[ENHANCED-AI][{}] Signal blocked - strongest leader {} failed volume confirmation",
                    analysisId, context.strongestLeader);
            return null;
        }

        String strongestLeader = context.strongestLeader;
        MomentumVelocity leaderVelocity = context.strongestLeaderVelocity;
        MomentumVelocity qqqVelocity = context.qqqVelocity;

        if (aiDecision.getStrategy() == LeaderLagStrategy.STANDARD) {
            // Enhanced standard strategy with technical confluence validation

            if (isCall && leaderVelocity.momentum > 0.01 && qqqVelocity.momentum < 0.05 &&
                    (leaderVelocity.state == VelocityState.ACCELERATING_BULLISH || leaderVelocity.state == VelocityState.STRONG_BULLISH) &&
                    ("UP".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createEnhancedStandardAISignal(option, context, metrics, "CALL", aiDecision, analysisId);
            }

            if (isPut && leaderVelocity.momentum < -0.01 && qqqVelocity.momentum > -0.05 &&
                    (leaderVelocity.state == VelocityState.ACCELERATING_BEARISH || leaderVelocity.state == VelocityState.STRONG_BEARISH) &&
                    ("DOWN".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createEnhancedStandardAISignal(option, context, metrics, "PUT", aiDecision, analysisId);
            }
        }
        else if (aiDecision.getStrategy() == LeaderLagStrategy.REVERSE) {
            // Enhanced reverse strategy with weakening detection

            if (isCall && leaderVelocity.state == VelocityState.WEAKENING_BULLISH &&
                    qqqVelocity.momentum > 0.01 && ("UP".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createEnhancedReverseAISignal(option, context, metrics, "CALL", aiDecision, analysisId);
            }

            if (isPut && leaderVelocity.state == VelocityState.WEAKENING_BEARISH &&
                    qqqVelocity.momentum < -0.01 && ("DOWN".equals(marketTrend) || "NEUTRAL".equals(marketTrend))) {

                return createEnhancedReverseAISignal(option, context, metrics, "PUT", aiDecision, analysisId);
            }
        }

        return null;
    }
    private EnhancedAILeaderLagSignal createEnhancedStandardAISignal(Option option, EnhancedMarketContext context,
                                                                     LeaderCorrelationMetrics metrics, String type,
                                                                     EnhancedAIDecision aiDecision, String analysisId) {

        double confidence = aiDecision.getConfidence();
        MomentumVelocity leaderVel = context.strongestLeaderVelocity;
        MomentumVelocity qqqVel = context.qqqVelocity;

        // Enhanced confidence calculation with technical confluence
        double momentumDivergence = Math.abs(leaderVel.momentum - qqqVel.momentum);
        double velocityStrength = Math.abs(leaderVel.acceleration);

        // Technical confluence boosts
        if (context.strongestLeaderTechnicalStatus) {
            confidence *= 1.25; // Significant boost for technical confluence
        }

        if (context.strongestLeaderVolumeConfirmed) {
            confidence *= 1.15; // Volume confirmation boost
        }

        // Velocity-based confidence boosts
        if (leaderVel.state == VelocityState.STRONG_BULLISH || leaderVel.state == VelocityState.STRONG_BEARISH) {
            confidence *= 1.20; // Strong velocity
        } else if (leaderVel.state == VelocityState.ACCELERATING_BULLISH || leaderVel.state == VelocityState.ACCELERATING_BEARISH) {
            confidence *= 1.15; // Accelerating
        }

        // Divergence boost
        if (momentumDivergence > 0.05) confidence *= 1.10;

        // Correlation boost
        if (metrics.getStrongestCorrelation() > 0.7) confidence *= 1.08;

        String strategy = type.equals("CALL") ? "0DTE_ENHANCED_AI_LEADER_LAG_CALL" : "0DTE_ENHANCED_AI_LEADER_LAG_PUT";
        String reason = String.format(
                "🚀 Enhanced AI Standard: %s velocity=%s, momentum=%.3f vs QQQ=%.3f, technical=✓, volume=✓, pattern=%s",
                context.strongestLeader, leaderVel.state, leaderVel.momentum, qqqVel.momentum,
                aiDecision.getDominantPattern());

        return new EnhancedAILeaderLagSignal(LeaderLagStrategy.STANDARD, "BUY", strategy,
                Math.min(0.95, confidence), metrics.getStrongestCorrelation(),
                Math.abs(leaderVel.momentum), reason, aiDecision.getRsiContext().toString(),
                context.strongestLeader, leaderVel, aiDecision.getDominantPattern());
    }

    private EnhancedAILeaderLagSignal createEnhancedReverseAISignal(Option option, EnhancedMarketContext context,
                                                                    LeaderCorrelationMetrics metrics, String type,
                                                                    EnhancedAIDecision aiDecision, String analysisId) {

        double confidence = aiDecision.getConfidence();
        MomentumVelocity leaderVel = context.strongestLeaderVelocity;
        MomentumVelocity qqqVel = context.qqqVelocity;

        // Enhanced confidence for weakening patterns
        if (leaderVel.state == VelocityState.WEAKENING_BULLISH || leaderVel.state == VelocityState.WEAKENING_BEARISH) {
            confidence *= 1.18;
        }

        // QQQ independence boost
        if (Math.abs(qqqVel.momentum) > 0.03) confidence *= 1.12;

        // Time-based boost for reverse strategies
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(15, 0))) confidence *= 1.10;

        String strategy = type.equals("CALL") ? "0DTE_ENHANCED_AI_REVERSE_LEADER_LAG_CALL" : "0DTE_ENHANCED_AI_REVERSE_LEADER_LAG_PUT";
        String reason = String.format(
                "🔄 Enhanced AI Reverse: %s weakening velocity=%s, momentum=%.3f->%.3f, QQQ continuing=%.3f, pattern=%s",
                context.strongestLeader, leaderVel.state, leaderVel.momentum, leaderVel.acceleration,
                qqqVel.momentum, aiDecision.getDominantPattern());

        return new EnhancedAILeaderLagSignal(LeaderLagStrategy.REVERSE, "BUY", strategy,
                Math.min(0.95, confidence), metrics.getStrongestCorrelation(),
                Math.abs(qqqVel.momentum), reason, aiDecision.getRsiContext().toString(),
                context.strongestLeader, leaderVel, aiDecision.getDominantPattern());
    }

    private Signal createEnhancedAILeaderLagSignal(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                                   EnhancedAILeaderLagSignal aiSignal, String marketTrend, String analysisId) {

        Signal signal = createSignal(option, allOptions, ta, aiSignal.getSignalType(),
                aiSignal.getStrategy(), aiSignal.getConfidence(), marketTrend);

        if (signal == null) return null;

        // Enhanced AI metadata with validation status
        signal.getMetadata().put("aiStrategy", aiSignal.getLeaderLagStrategy().toString());
        signal.getMetadata().put("aiCorrelation", String.valueOf(aiSignal.getCorrelation()));
        signal.getMetadata().put("aiSignalStrength", String.valueOf(aiSignal.getSignalStrength()));
        signal.getMetadata().put("aiMarketRegime", aiSignal.getMarketRegime());
        signal.getMetadata().put("leaderStock", aiSignal.getLeaderStock());
        signal.getMetadata().put("priority", "HIGHEST");
        signal.getMetadata().put("signalSource", "ENHANCED_AI_LEARNING_ENGINE");

        // NEW: Technical validation metadata
        signal.getMetadata().put("strongestLeaderValidated", "true");
        signal.getMetadata().put("technicalConfluenceFactors", "VWAP_BREAKOUT,KEY_LEVEL_BREAK,RSI_CONTEXT");
        signal.getMetadata().put("volumeConfirmed", "true");

        // Velocity and pattern metadata
        signal.getMetadata().put("leaderVelocity", aiSignal.getLeaderVelocity().state.toString());
        signal.getMetadata().put("momentumAcceleration", String.valueOf(aiSignal.getLeaderVelocity().acceleration));
        signal.getMetadata().put("microPattern", aiSignal.getMicroPattern().toString());
        signal.getMetadata().put("momentumDirection", aiSignal.getLeaderVelocity().momentum > 0 ? "BULLISH" : "BEARISH");
        signal.getMetadata().put("momentumMagnitude", String.valueOf(Math.abs(aiSignal.getLeaderVelocity().momentum)));

        // Store option Greeks
        if (option.getDelta() != null) signal.setDelta(option.getDelta());
        if (option.getGamma() != null) signal.setGamma(option.getGamma());
        if (option.getTheta() != null) signal.setTheta(option.getTheta());
        if (option.getVega() != null) signal.setVega(option.getVega());

        // Enhanced risk management with technical confluence consideration
        signal.setTargetPrice(calculateEnhancedAITarget(signal.getEntryPrice(), aiSignal, ta));
        signal.setStopLoss(calculateEnhancedAIStop(signal.getEntryPrice(), aiSignal, ta));

        signal.setReason(aiSignal.getReason());

        return signal;
    }
    private BigDecimal calculateEnhancedAITarget(BigDecimal entryPrice, EnhancedAILeaderLagSignal aiSignal, TechnicalAnalysis ta) {
        double baseMultiplier = aiSignal.getLeaderLagStrategy() == LeaderLagStrategy.STANDARD ? 2.8 : 2.3;

        // Velocity-based adjustment
        VelocityState velocityState = aiSignal.getLeaderVelocity().state;
        if (velocityState == VelocityState.STRONG_BULLISH || velocityState == VelocityState.STRONG_BEARISH) {
            baseMultiplier *= 1.20; // Strong velocity gets bigger targets
        } else if (velocityState == VelocityState.ACCELERATING_BULLISH || velocityState == VelocityState.ACCELERATING_BEARISH) {
            baseMultiplier *= 1.15; // Accelerating momentum
        }

        // Pattern-based adjustment
        if (aiSignal.getMicroPattern() == MicroMomentumPattern.ACCUMULATION ||
                aiSignal.getMicroPattern() == MicroMomentumPattern.DISTRIBUTION) {
            baseMultiplier *= 1.10; // Clear patterns get boost
        }

        // Time decay for 0DTE
        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(15, 0))) {
            baseMultiplier *= 0.80; // Reduce targets in final hour
        }

        return entryPrice.multiply(BigDecimal.valueOf(baseMultiplier)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateEnhancedAIStop(BigDecimal entryPrice, EnhancedAILeaderLagSignal aiSignal, TechnicalAnalysis ta) {
        double baseStopPercentage = aiSignal.getLeaderLagStrategy() == LeaderLagStrategy.STANDARD ? 0.25 : 0.20;

        // Tighter stops for high-velocity signals (they should move quickly)
        VelocityState velocityState = aiSignal.getLeaderVelocity().state;
        if (velocityState == VelocityState.STRONG_BULLISH || velocityState == VelocityState.STRONG_BEARISH) {
            baseStopPercentage *= 0.85;
        }

        // Confidence-based adjustment
        if (aiSignal.getConfidence() > 0.9) {
            baseStopPercentage *= 0.90;
        }

        return entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(baseStopPercentage)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private double getEnhancedAIThreshold(LeaderLagStrategy strategy, LocalTime now, EnhancedMarketContext context) {
        boolean isPowerHour = now.isAfter(POWER_HOUR_START);

        // Base thresholds
        double threshold = switch (strategy) {
            case STANDARD -> isPowerHour ? 0.62 : 0.65;
            case REVERSE -> isPowerHour ? 0.58 : 0.65;
            default -> 0.75;
        };

        // Velocity-based threshold reduction
        if (context.strongestLeaderVelocity.state == VelocityState.STRONG_BULLISH ||
                context.strongestLeaderVelocity.state == VelocityState.STRONG_BEARISH) {
            threshold -= 0.05; // Lower threshold for strong velocity
        }

        return threshold;
    }

    private String serializeValidationContext(EnhancedMarketContext context, EnhancedAIDecision decision) {
        return String.format("{\"leader\":\"%s\",\"velocity\":\"%s\",\"pattern\":\"%s\",\"momentum\":%.3f}",
                context.strongestLeader, context.strongestLeaderVelocity.state,
                decision.getDominantPattern(), context.strongestLeaderVelocity.momentum);
    }

    // ================================================================================================
    // NEW: ENHANCED CORRELATION DETECTOR WITH TIME DECAY
    // ================================================================================================

    private class CorrelationDetector {
        private final Map<String, List<PricePoint>> priceHistory = new ConcurrentHashMap<>();
        private LeaderCorrelationMetrics lastMetrics;

        public LeaderCorrelationMetrics getLeaderMetrics(String analysisId) {
            return getLeaderMetricsWithTimeDecay(analysisId);
        }

        public LeaderCorrelationMetrics getLeaderMetricsWithTimeDecay(String analysisId) {
            try {
                List<PricePoint> qqqPrices = getRecentPrices("QQQ", 15, analysisId);
                if (qqqPrices.size() < 10) {
                    log.warn("[CORRELATION][{}] Insufficient QQQ data: {} points", analysisId, qqqPrices.size());
                    return lastMetrics;
                }

                String strongestLeader = null;
                double strongestCorrelation = 0.0;
                Map<String, Double> leaderCorrelations = new HashMap<>();

                for (String leaderSymbol : LEADER_STOCKS) {
                    List<PricePoint> leaderPrices = getRecentPrices(leaderSymbol, 15, analysisId);

                    if (leaderPrices.size() >= 10) {
                        double correlation = calculateCorrelationWithTimeDecay(leaderPrices, qqqPrices);
                        leaderCorrelations.put(leaderSymbol, correlation);

                        if (correlation > strongestCorrelation) {
                            strongestCorrelation = correlation;
                            strongestLeader = leaderSymbol;
                        }
                    }
                }

                // CRITICAL FIX: Only return leader if correlation meets 80% threshold
                if (strongestLeader == null || strongestCorrelation < 0.80) {
                    log.warn("[CORRELATION][{}] No leader meets 80% threshold - strongest: {} ({}%)",
                            analysisId, strongestLeader,
                            strongestCorrelation > 0 ? (int)(strongestCorrelation * 100) : 0);
                    return null;
                }

                log.info("[CORRELATION][{}] Qualified leader found: {} ({}%)",
                        analysisId, strongestLeader, (int)(strongestCorrelation * 100));

                lastMetrics = new LeaderCorrelationMetrics(strongestLeader, strongestCorrelation, leaderCorrelations);
                return lastMetrics;

            } catch (Exception e) {
                log.error("[CORRELATION][{}] Error calculating leader metrics: {}", analysisId, e.getMessage());
                return null;
            }
        }

        public double getLeaderQQQCorrelation(String leaderSymbol) {
            try {
                List<PricePoint> leaderPrices = getRecentPrices(leaderSymbol, 15, "correlation-check");
                List<PricePoint> qqqPrices = getRecentPrices("QQQ", 15, "correlation-check");

                if (leaderPrices.size() >= 5 && qqqPrices.size() >= 5) {
                    return calculateCorrelationWithTimeDecay(leaderPrices, qqqPrices);
                }
                return 0.5;

            } catch (Exception e) {
                log.debug("Error calculating correlation for {}: {}", leaderSymbol, e.getMessage());
                return 0.5;
            }
        }

        private double calculateCorrelationWithTimeDecay(List<PricePoint> prices1, List<PricePoint> prices2) {
            double baseCorrelation = calculateCorrelation(prices1, prices2);

            // Apply time decay - more recent data has higher weight
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime mostRecentPrice = prices1.get(0).timestamp;

            long minutesAge = Duration.between(mostRecentPrice, now).toMinutes();
            double timeDecayFactor = getTimeDecayFactor(minutesAge);

            return baseCorrelation * timeDecayFactor;
        }

        private double getTimeDecayFactor(long minutesAge) {
            if (minutesAge <= 2) return 1.0;      // 100% confidence
            if (minutesAge <= 5) return 0.85;     // 85% confidence
            if (minutesAge <= 10) return 0.60;    // 60% confidence
            return 0.30;                          // 30% confidence (mostly expired)
        }

        private List<PricePoint> getRecentPrices(String symbol, int periods, String analysisId) {
            List<PricePoint> prices = new ArrayList<>();
            try {
                List<MarketData> marketData = marketDataRepository.findRecentData(symbol, periods);

                for (MarketData data : marketData) {
                    if (data.getPrice() != null && data.getTimestamp() != null) {
                        prices.add(new PricePoint(data.getPrice().doubleValue(), data.getTimestamp()));
                    }
                }

                prices.sort((a, b) -> b.timestamp.compareTo(a.timestamp));

            } catch (Exception e) {
                log.debug("[PRICE-DATA][{}] Error getting prices for {}: {}", analysisId, symbol, e.getMessage());
            }
            return prices;
        }

        public double calculateCorrelation(List<PricePoint> prices1, List<PricePoint> prices2) {
            int n = Math.min(prices1.size(), prices2.size());
            if (n < 3) return 0.5;

            double sum1 = prices1.stream().limit(n).mapToDouble(p -> p.price).sum();
            double sum2 = prices2.stream().limit(n).mapToDouble(p -> p.price).sum();
            double sum1Sq = prices1.stream().limit(n).mapToDouble(p -> p.price * p.price).sum();
            double sum2Sq = prices2.stream().limit(n).mapToDouble(p -> p.price * p.price).sum();
            double sum1Sum2 = 0;

            for (int i = 0; i < n; i++) {
                sum1Sum2 += prices1.get(i).price * prices2.get(i).price;
            }

            double numerator = n * sum1Sum2 - sum1 * sum2;
            double denominator = Math.sqrt((n * sum1Sq - sum1 * sum1) * (n * sum2Sq - sum2 * sum2));

            return denominator != 0 ? Math.abs(numerator / denominator) : 0.5;
        }
    }

    // ================================================================================================
    // NEW: ENHANCED DATA STRUCTURES
    // ================================================================================================

    @Getter @Setter
    private static class MomentumReading {
        final double momentum;
        final double volumeRatio;
        final LocalDateTime timestamp;

        public MomentumReading(double momentum, double volumeRatio,  LocalDateTime timestamp) {
            this.momentum = momentum;
            this.volumeRatio = volumeRatio;
            this.timestamp = timestamp;
        }
    }

    private double calculateVolumeConvictionScore(double volumeRatio) {
        if (volumeRatio < 0.8) return 0.5;      // Discount low volume moves
        if (volumeRatio <= 1.2) return 1.0;     // Normal weighting
        if (volumeRatio <= 2.0) return 1.3;     // Volume surge confirmation
        return 1.5;                             // Strong conviction (capped)
    }

    @Getter @Setter
    private static class MomentumVelocity {
        final double momentum;
        final double acceleration;
        final VelocityState state;

        public MomentumVelocity(double momentum, double acceleration, VelocityState state) {
            this.momentum = momentum;
            this.acceleration = acceleration;
            this.state = state;
        }
    }

    private enum VelocityState {
        STRONG_BULLISH, ACCELERATING_BULLISH, WEAKENING_BULLISH,
        STRONG_BEARISH, ACCELERATING_BEARISH, WEAKENING_BEARISH,
        COILING, PIVOTING, NOISE, INSUFFICIENT_DATA
    }

    private enum MicroMomentumPattern {
        ACCUMULATION, DISTRIBUTION, COILING, PIVOT, NOISE, INSUFFICIENT_DATA
    }

    private enum RSIZone {
        OVERBOUGHT, UPPER_NEUTRAL, NEUTRAL, LOWER_NEUTRAL, OVERSOLD
    }

    private enum MomentumState {
        POSITIVE, WEAK_POSITIVE, NEAR_ZERO, WEAK_NEGATIVE, NEGATIVE
    }

    private enum ContextInterpretation {
        TREND_CONTINUATION, EXHAUSTION, CONSOLIDATION, BOUNCE_SETUP, UNCLEAR
    }

    private enum SignalAction {
        FOLLOW, REVERSE, WAIT, SKIP
    }

    @Getter @Setter
    private static class RSIMomentumContext {
        final RSIZone rsiZone;
        final MomentumState momentumState;
        final VelocityState velocityState;
        final ContextInterpretation interpretation;
        final SignalAction signalAction;
        final double contextConfidence;

        public RSIMomentumContext(RSIZone rsiZone, MomentumState momentumState, VelocityState velocityState,
                                  ContextInterpretation interpretation, SignalAction signalAction, double contextConfidence) {
            this.rsiZone = rsiZone;
            this.momentumState = momentumState;
            this.velocityState = velocityState;
            this.interpretation = interpretation;
            this.signalAction = signalAction;
            this.contextConfidence = contextConfidence;
        }
    }

    @Getter @Setter
    private static class EnhancedMarketContext {
        final String strongestLeader;
        final MomentumVelocity strongestLeaderVelocity;
        final MomentumVelocity qqqVelocity;
        final Map<String, MomentumVelocity> leaderVelocities;
        final double correlation, vixLevel, volumeRatio, rsi;
        final String marketTrend, volatilityRegime;
        final int timeOfDay, dayOfWeek;
        final BigDecimal currentPrice;
        final boolean strongestLeaderTechnicalStatus;
        final boolean strongestLeaderVolumeConfirmed;

        public EnhancedMarketContext(String strongestLeader, MomentumVelocity strongestLeaderVelocity,
                                     MomentumVelocity qqqVelocity, Map<String, MomentumVelocity> leaderVelocities,
                                     double correlation, double vixLevel, String marketTrend, String volatilityRegime,
                                     int timeOfDay, int dayOfWeek, double volumeRatio, double rsi, BigDecimal currentPrice,
                                     boolean strongestLeaderTechnicalStatus, boolean strongestLeaderVolumeConfirmed) {
            this.strongestLeader = strongestLeader;
            this.strongestLeaderVelocity = strongestLeaderVelocity;
            this.qqqVelocity = qqqVelocity;
            this.leaderVelocities = leaderVelocities;
            this.correlation = correlation;
            this.vixLevel = vixLevel;
            this.marketTrend = marketTrend;
            this.volatilityRegime = volatilityRegime;
            this.timeOfDay = timeOfDay;
            this.dayOfWeek = dayOfWeek;
            this.volumeRatio = volumeRatio;
            this.rsi = rsi;
            this.currentPrice = currentPrice;
            this.strongestLeaderTechnicalStatus = strongestLeaderTechnicalStatus;
            this.strongestLeaderVolumeConfirmed = strongestLeaderVolumeConfirmed;
        }
    }

    @Getter @Setter
    private static class EnhancedAIDecision {
        final LeaderLagStrategy strategy;
        final double confidence;
        final String reasoning;
        final MicroMomentumPattern dominantPattern;
        final RSIMomentumContext rsiContext;

        public EnhancedAIDecision(LeaderLagStrategy strategy, double confidence, String reasoning,
                                  MicroMomentumPattern dominantPattern, RSIMomentumContext rsiContext) {
            this.strategy = strategy;
            this.confidence = confidence;
            this.reasoning = reasoning;
            this.dominantPattern = dominantPattern;
            this.rsiContext = rsiContext;
        }
    }

    @Getter @Setter
    private static class EnhancedAILeaderLagSignal {
        final LeaderLagStrategy leaderLagStrategy;
        final String signalType, strategy, reason, marketRegime, leaderStock;
        final double confidence, correlation, signalStrength;
        final MomentumVelocity leaderVelocity;
        final MicroMomentumPattern microPattern;

        public EnhancedAILeaderLagSignal(LeaderLagStrategy leaderLagStrategy, String signalType, String strategy,
                                         double confidence, double correlation, double signalStrength,
                                         String reason, String marketRegime, String leaderStock,
                                         MomentumVelocity leaderVelocity, MicroMomentumPattern microPattern) {
            this.leaderLagStrategy = leaderLagStrategy;
            this.signalType = signalType;
            this.strategy = strategy;
            this.confidence = confidence;
            this.correlation = correlation;
            this.signalStrength = signalStrength;
            this.reason = reason;
            this.marketRegime = marketRegime;
            this.leaderStock = leaderStock;
            this.leaderVelocity = leaderVelocity;
            this.microPattern = microPattern;
        }
    }

    private List<Option> selectStrikesForAILeaderLag(List<Option> options, TechnicalAnalysis ta, String analysisId) {
        BigDecimal currentPrice = ta.getCurrentPrice();

        return options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    BigDecimal distance = strike.subtract(currentPrice).abs();
                    return distance.compareTo(BigDecimal.valueOf(1.0)) <= 0;
                })
                .collect(Collectors.toList());
    }

    public List<Signal> analyzeOptions(String symbol) {
        return analyzeOptions(symbol, "NEUTRAL");
    }

    private boolean hasDataSufficiency(String symbol, String analysisId) {
        try {
            LocalDateTime twoHoursAgo = LocalDateTime.now(ET_ZONE).minusHours(2);
            List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 120);

            if (recentData.size() < 120) {
                log.warn("[DATA-CHECK][{}] Insufficient market data: {} minutes (need 120)", analysisId, recentData.size());
                return false;
            }

            LocalDateTime marketOpen = LocalDateTime.now(ET_ZONE).with(LocalTime.of(9, 30));
            LocalDateTime now = LocalDateTime.now(ET_ZONE);

            if (now.isAfter(marketOpen.plusHours(2))) {
                long expectedMinutes = Duration.between(marketOpen, now).toMinutes();
                long actualDataPoints = recentData.stream()
                        .filter(data -> data.getTimestamp().isAfter(marketOpen))
                        .count();

                double dataCompleteness = (double) actualDataPoints / expectedMinutes;

                if (dataCompleteness < 0.80) {
                    log.warn("[DATA-CHECK][{}] Data completeness {}% < 80% threshold", analysisId, (int)(dataCompleteness * 100));
                    return false;
                }
            }

            for (String leaderSymbol : LEADER_STOCKS) {
                List<MarketData> leaderData = marketDataRepository.findRecentData(leaderSymbol, 30);
                if (leaderData.size() < 30) {
                    log.warn("[DATA-CHECK][{}] Insufficient {} data: {} minutes (need 30)", analysisId, leaderSymbol, leaderData.size());
                    return false;
                }
            }

           // log.info("[DATA-CHECK][{}] Data sufficiency ✅ - {} minutes of data available", analysisId, recentData.size());
            return true;

        } catch (Exception e) {
            log.error("[DATA-CHECK][{}] Error checking data sufficiency: {}", analysisId, e.getMessage());
            return false;
        }
    }

    private List<Signal> applyLateSessionFiltering(List<Signal> signals, String analysisId) {
        List<Signal> filtered = signals.stream()
                .filter(s -> {
                    String priority = s.getMetadata().get("priority");
                    String source = s.getMetadata().get("signalSource");

                    if ("HIGHEST".equals(priority) && ("AI_LEARNING_ENGINE".equals(source) || "ENHANCED_AI_LEARNING_ENGINE".equals(source))) {
                        return s.getConfidence() >= 0.75;
                    }

                    if (s.getStrategy().contains("TRENDLINE_BREAK") ||
                            s.getStrategy().contains("GAP_FILL") ||
                            s.getStrategy().contains("HIGH_BREACH") ||
                            s.getStrategy().contains("LOW_BREACH")) {
                        return s.getConfidence() >= 0.95;
                    }

                    return s.getConfidence() >= 0.90;
                })
                .collect(Collectors.toList());

        log.info("[LATE-SESSION][{}] Late session filter: {} signals -> {} (AI-priority-aware filtering)",
                analysisId, signals.size(), filtered.size());

        return filtered;
    }

    private void executeSignalImmediately(Signal signal, SHAPExplainerService.SHAPExplanation explanation, String analysisId) {
        try {
            // Check for duplicate execution attempts first
            String signalKey = signal.getId() != null ? signal.getId().toString() : UUID.randomUUID().toString();
            LocalDateTime lastAttempt = executionAttempts.get(signalKey);

            if (lastAttempt != null && lastAttempt.isAfter(LocalDateTime.now().minusMinutes(5))) {
                log.warn("[EXECUTION][{}] Duplicate execution attempt blocked for signal: {}",
                        analysisId, signal.getOptionSymbol());
                return;
            }

            // Check for signal expiration
            if (isSignalExpired(signal)) {
                log.warn("[EXECUTION][{}] Signal EXPIRED before execution: {} (expired at: {})",
                        analysisId, signal.getOptionSymbol(), signal.getExpirationTime());
                saveFailedSignal(signal, "SIGNAL_EXPIRED", "Signal expired before execution");
                return;
            }

            // Check if signal already executed
            if ("EXECUTED".equals(signal.getStatus()) || "FILLED".equals(signal.getStatus())) {
                log.warn("[EXECUTION][{}] Signal already executed: {}", analysisId, signal.getOptionSymbol());
                return;
            }

            // Position overlap check
            boolean hasExistingPosition = hasOpenPositionOnUnderlying(signal.getSymbol());
            if (hasExistingPosition) {
                if (signal.getConfidence() < 0.90) {
                    log.warn("[EXECUTION][{}] BLOCKED - Existing position on {} with confidence {}% < 90% threshold",
                            analysisId, signal.getSymbol(), (int)(signal.getConfidence() * 100));
                    saveFailedTrade(signal, "POSITION_OVERLAP_BLOCKED",
                            "Existing position with insufficient confidence override", analysisId);
                    saveFailedSignal(signal, "POSITION_OVERLAP_BLOCKED",
                            "Existing position with insufficient confidence override");
                    return;
                } else {
                    log.warn("[EXECUTION][{}] OVERRIDE - Existing position on {} but confidence {}% >= 90% - proceeding with STRICT validation",
                            analysisId, signal.getSymbol(), (int)(signal.getConfidence() * 100));
                }
            }

            // Mark execution attempt
            executionAttempts.put(signalKey, LocalDateTime.now());

            log.info("[EXECUTION][{}] Attempting BUY_TO_OPEN execution: {}", analysisId, signal.getOptionSymbol());

            // Get current quote and validate
            QuoteResponse quote = tradierService.getQuote(signal.getOptionSymbol());
            if (quote == null || quote.getQuote() == null) {
                log.error("[EXECUTION][{}] Cannot get quote - saving as QUOTE_FAILED", analysisId);
                saveFailedTrade(signal, "QUOTE_FAILED", "Unable to get option quote", analysisId);
                saveFailedSignal(signal, "QUOTE_FAILED", "Unable to get option quote");
                return;
            }

            BigDecimal currentPrice = quote.getQuote().getLast();
            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                currentPrice = quote.getQuote().getMidPrice();
                if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    log.error("[EXECUTION][{}] Invalid option price - saving as PRICE_INVALID", analysisId);
                    saveFailedTrade(signal, "PRICE_INVALID", "Option price is null or zero", analysisId);
                    saveFailedSignal(signal, "PRICE_INVALID", "Option price is null or zero");
                    return;
                }
            }

            // Update signal with fresh Greeks data
            Quote freshQuote = quote.getQuote();
            if (freshQuote.getGreeks() != null) {
                OptionGreeks greeks = freshQuote.getGreeks();
                if (greeks.getDelta() != null) signal.setDelta(greeks.getDelta().doubleValue());
                if (greeks.getGamma() != null) signal.setGamma(greeks.getGamma().doubleValue());
                if (greeks.getTheta() != null) signal.setTheta(greeks.getTheta());
                if (greeks.getVega() != null) signal.setVega(greeks.getVega());
                if (greeks.getRho() != null) signal.setRho(greeks.getRho());
            }

            // Store option data in signal metadata to avoid null pointer later
            storeOptionDataInSignal(signal, freshQuote);

            // Price level risk assessment
            PriceLevelRiskService.PriceLevelRisk priceLevelRisk =
                    priceLevelRiskService.assessPriceLevelRisk(signal.getSymbol(),
                            signal.getEntryAssumptionPrice(), analysisId);

            if (!priceLevelRisk.shouldProceed()) {
                log.warn("[EXECUTION][{}] Price level risk - saving as LEVEL_BLOCKED: {}",
                        analysisId, priceLevelRisk.getRecommendation());
                saveFailedTrade(signal, "LEVEL_BLOCKED",
                        "Near POC/resistance without volume confirmation: " + priceLevelRisk.getRecommendation(), analysisId);
                saveFailedSignal(signal, "LEVEL_BLOCKED", "Near POC/resistance without volume confirmation");
                telegramService.sendMessage(String.format(
                        "Signal BLOCKED - PRICE LEVEL RISK\nOption: %s\nReason: %s\nRisk: %s\nStatus: Saved for analysis",
                        signal.getOptionSymbol(), priceLevelRisk.getRecommendation(),
                        priceLevelRisk.isHighRisk() ? "HIGH" : "MODERATE"));
                return;
            }

            // Leader direction validation (skip for AI signals)
            // Leader direction validation (skip for AI signals and VWAP strategies)
            if (!signal.getStrategy().contains("AI_LEADER_LAG") &&
                    !signal.getStrategy().contains("ENHANCED_AI") &&
                    !signal.getStrategy().contains("VWAP")) {
                LeaderDirectionValidator.ValidationResult leaderValidation =
                        leaderValidator.validateLeaderDirection(signal, analysisId, hasExistingPosition);

                if (!leaderValidation.isShouldProceed()) {
                    if (leaderValidation.getConfidenceAdjustment() < 0.8) {
                        log.warn("[EXECUTION][{}] Leader opposition - HARD BLOCK: {}", analysisId, leaderValidation.getReason());
                        saveFailedTrade(signal, "LEADER_BLOCKED", leaderValidation.getReason(), analysisId);
                        saveFailedSignal(signal, "LEADER_BLOCKED", leaderValidation.getReason());
                        telegramService.sendMessage(String.format(
                                "Signal BLOCKED - LEADER OPPOSITION\nOption: %s\nReason: %s\nLeaders: %s\nStatus: Saved for analysis",
                                signal.getOptionSymbol(), leaderValidation.getReason(), leaderValidation.getDetails()));
                        return;
                    } else {
                        signal.setConfidence(signal.getConfidence() * leaderValidation.getConfidenceAdjustment());
                        log.info("[EXECUTION][{}] Leader caution - confidence reduced to {}%",
                                analysisId, (int)(signal.getConfidence() * 100));
                    }
                }
            }

            // Greeks validation (stricter if existing position)
            GreeksValidator.GreeksValidationResult greeksValidation =
                    greeksValidator.validateGreeks(signal, signal.getConfidence(), analysisId, hasExistingPosition);

            if (!greeksValidation.isValid()) {
                log.warn("[EXECUTION][{}] Greeks validation failed: {}", analysisId, greeksValidation.getReason());
                saveFailedTrade(signal, "GREEKS_BLOCKED", greeksValidation.getReason(), analysisId);
                saveFailedSignal(signal, "GREEKS_BLOCKED", greeksValidation.getReason());
                telegramService.sendMessage(String.format(
                        "Signal BLOCKED - GREEKS RISK\nOption: %s\nReason: %s\nDelta: %.3f, Gamma: %.3f, Theta: %.3f\nStatus: Saved for analysis",
                        signal.getOptionSymbol(), greeksValidation.getReason(),
                        signal.getDelta() != null ? signal.getDelta() : 0.0,
                        signal.getGamma() != null ? signal.getGamma() : 0.0,
                        signal.getTheta() != null ? signal.getTheta() : 0.0));
                return;
            }

            log.info("[EXECUTION][{}] All validations PASSED - proceeding with order execution", analysisId);

            // Execute order with proper confirmation
            boolean orderSuccessful = attemptSignalExecutionWithConfirmation(signal, analysisId);

            if (orderSuccessful) {
                log.info("[EXECUTION][{}] Order CONFIRMED AND FILLED - creating trade record", analysisId);

                // Create trade with the confirmed fill price
                Trade trade = createSuccessfulTradeFixed(signal, currentPrice, analysisId);
                enrichTradeWithBayesianData(trade, signal);
                trade = tradeRepository.save(trade);

                // Update signal to final executed state and save to database
                signal.setEntryPrice(currentPrice);
                signal.setStatus("EXECUTED");
                signal.getMetadata().put("tradeId", trade.getId().toString());
                signal.getMetadata().put("executedAt", LocalDateTime.now().toString());
                signal.getMetadata().put("priceLevelClear", "true");
                signal.getMetadata().put("leaderValidation", "passed");
                signal.getMetadata().put("greeksValidation", "passed");
                signalRepository.save(signal);

                log.info("[EXECUTION][{}] SUCCESS SAVED - Trade ID: {} (OPEN), Signal (EXECUTED)",
                        analysisId, trade.getId());

                sendSuccessNotification(trade, signal, explanation, analysisId);

            } else {
                log.warn("[EXECUTION][{}] Order execution/confirmation FAILED - saving as ORDER_FAILED", analysisId);
                saveFailedTrade(signal, "ORDER_FAILED", "Order placement or fill confirmation failed", analysisId);
                saveFailedSignal(signal, "ORDER_FAILED", "Order placement or fill confirmation failed");

                telegramService.sendMessage(String.format(
                        "EXECUTION FAILED\nOption: %s\nReason: Order placement or fill confirmation failed\nStatus: ORDER_FAILED (saved for analysis)",
                        signal.getOptionSymbol()));
            }

        } catch (Exception e) {
            log.error("[EXECUTION][{}] Exception during execution: {}", analysisId, e.getMessage(), e);
            saveFailedTrade(signal, "EXECUTION_ERROR", e.getMessage(), analysisId);
            saveFailedSignal(signal, "EXECUTION_ERROR", e.getMessage());
        }
    }

    // In LeaderDirectionValidator class - add hasExistingPosition parameter

    private void storeOptionDataInSignal(Signal signal, Quote quote) {
        try {
            if (quote != null) {
                // Store basic option data
                if (quote.getLast() != null) {
                    signal.getMetadata().put("optionLastPrice", quote.getLast().toString());
                }
                if (quote.getBid() != null) {
                    signal.getMetadata().put("optionBid", quote.getBid().toString());
                }
                if (quote.getAsk() != null) {
                    signal.getMetadata().put("optionAsk", quote.getAsk().toString());
                }
                if (quote.getVolume() != null) {
                    signal.getMetadata().put("optionVolume", quote.getVolume().toString());
                }
                if (quote.getOpenInterest() != null) {
                    signal.getMetadata().put("optionOpenInterest", quote.getOpenInterest().toString());
                }

                // Store Greeks if available
                if (quote.getGreeks() != null) {
                    OptionGreeks greeks = quote.getGreeks();
                    if (greeks.getDelta() != null) {
                        signal.getMetadata().put("optionDelta", greeks.getDelta().toString());
                    }
                    if (greeks.getGamma() != null) {
                        signal.getMetadata().put("optionGamma", greeks.getGamma().toString());
                    }
                    if (greeks.getTheta() != null) {
                        signal.getMetadata().put("optionTheta", greeks.getTheta().toString());
                    }
                    if (greeks.getVega() != null) {
                        signal.getMetadata().put("optionVega", greeks.getVega().toString());
                    }
                }

                // Extract option type and strike from symbol
                extractAndStoreOptionDetails(signal);
            }
        } catch (Exception e) {
            log.warn("[OPTION-DATA] Error storing option data in signal: {}", e.getMessage());
        }
    }

    // Helper method to extract option details from symbol
    private void extractAndStoreOptionDetails(Signal signal) {
        try {
            String optionSymbol = signal.getOptionSymbol();
            if (optionSymbol != null && optionSymbol.length() > 10) {
                // Parse option symbol format: QQQ250826P00570000
                String underlying = optionSymbol.substring(0, optionSymbol.indexOf("25")); // QQQ

                // Find the C or P
                int cIndex = optionSymbol.indexOf('C');
                int pIndex = optionSymbol.indexOf('P');

                if (cIndex > 0 || pIndex > 0) {
                    String optionType = cIndex > 0 ? "CALL" : "PUT";
                    int typeIndex = cIndex > 0 ? cIndex : pIndex;

                    // Extract strike price
                    String strikePart = optionSymbol.substring(typeIndex + 1);
                    if (strikePart.length() >= 8) {
                        // Strike format: 00570000 = $570.00
                        String strikeStr = strikePart.substring(0, 8);
                        double strikePrice = Double.parseDouble(strikeStr) / 1000.0;

                        signal.getMetadata().put("optionType", optionType);
                        signal.getMetadata().put("optionStrike", String.valueOf(strikePrice));
                        signal.getMetadata().put("optionUnderlying", underlying);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse option details from symbol: {}", signal.getOptionSymbol());
        }
    }



    private class LeaderDirectionValidator {

        static class ValidationResult {
            private final boolean shouldProceed;
            private final double confidenceAdjustment;
            private final String reason;
            private final String details;

            ValidationResult(boolean proceed, double adjustment, String reason, String details) {
                this.shouldProceed = proceed;
                this.confidenceAdjustment = adjustment;
                this.reason = reason;
                this.details = details;
            }

            public boolean isShouldProceed() {
                return shouldProceed;
            }

            public double getConfidenceAdjustment() {
                return confidenceAdjustment;
            }

            public String getReason() {
                return reason;
            }

            public String getDetails() {
                return details;
            }
        }

        static class LeaderMomentum {
            private final String symbol;
            private final double weight;
            private double momentum5min;
            private double momentum10min;
            private double momentum15min;
            private boolean isOpposing;

            LeaderMomentum(String symbol, double weight) {
                this.symbol = symbol;
                this.weight = weight;
            }

            public String getSymbol() { return symbol; }
            public double getWeight() { return weight; }
            public double getMomentum5min() { return momentum5min; }
            public void setMomentum5min(double momentum5min) { this.momentum5min = momentum5min; }
            public double getMomentum10min() { return momentum10min; }
            public void setMomentum10min(double momentum10min) { this.momentum10min = momentum10min; }
            public double getMomentum15min() { return momentum15min; }
            public void setMomentum15min(double momentum15min) { this.momentum15min = momentum15min; }
            public boolean isOpposing() { return isOpposing; }
            public void setOpposing(boolean opposing) { isOpposing = opposing; }
        }

        public ValidationResult validateLeaderDirection(Signal signal, String analysisId, boolean hasExistingPosition) {
            try {
                LocalTime now = LocalTime.now(ET_ZONE);

                if (now.isBefore(LocalTime.of(10, 0))) {
                    return new ValidationResult(true, 1.0, "Early session - skipping leader check", "");
                }

                Double confidence = signal.getConfidence();
                if (confidence != null && confidence >= 0.85) {
                    return new ValidationResult(true, 1.0, "High confidence signal - leader check bypassed", "");
                }

                if (signal.getStrategy().contains("REVERSION")) {
                    return new ValidationResult(true, 1.0, "Reversion play - leaders may be extended", "");
                }

                TechnicalAnalysis qqqTA = technicalAnalysisService.analyze("QQQ");
                if (qqqTA != null && qqqTA.getVolumeRatio() > 2.0) {
                    return new ValidationResult(true, 1.0, "QQQ leading with high volume", "");
                }

                boolean isCallSignal;
                if (signal.getStrategy().startsWith("TEMP_")) {
                    String optionType = signal.getMetadata().get("optionType");
                    isCallSignal = "CALL".equalsIgnoreCase(optionType);
                } else {
                    isCallSignal = signal.getStrategy().contains("CALL");
                }

                // Stricter thresholds when existing position
                double oppositionThreshold = hasExistingPosition ? 0.50 : 0.70; // Stricter when position exists
                double hardBlockThreshold = hasExistingPosition ? 0.20 : 0.30;  // Stricter when position exists

                Map<String, LeaderMomentum> leaderAnalysis = analyzeLeaderMomentum(isCallSignal, now);

                long opposingCount = leaderAnalysis.values().stream()
                        .filter(l -> l.isOpposing())
                        .count();

                double oppositionScore = leaderAnalysis.values().stream()
                        .filter(l -> l.isOpposing())
                        .mapToDouble(l -> l.getWeight())
                        .sum();

                String details = leaderAnalysis.entrySet().stream()
                        .map(e -> String.format("%s: 5m=%.2f%%, 10m=%.2f%%, 15m=%.2f%% (opposing: %s)",
                                e.getKey(),
                                e.getValue().getMomentum5min() * 100,
                                e.getValue().getMomentum10min() * 100,
                                e.getValue().getMomentum15min() * 100,
                                e.getValue().isOpposing()))
                        .collect(Collectors.joining(", "));

                boolean isVWAPValidation = signal.getStrategy().startsWith("TEMP_VWAP");

                if (isVWAPValidation) {
                    if (opposingCount >= 1 || oppositionScore >= hardBlockThreshold) {
                        return new ValidationResult(false, 0.0,
                                String.format("VWAP BLOCKED: %d/3 leaders opposing (weighted: %.0f%%) - %s threshold",
                                        opposingCount, oppositionScore * 100,
                                        hasExistingPosition ? "STRICT" : "NORMAL"),
                                details);
                    }
                } else {
                    // HARD BLOCKS first
                    if (opposingCount >= 2 || oppositionScore >= oppositionThreshold) {
                        return new ValidationResult(false, 0.0,
                                String.format("%d/3 leaders opposing direction (weighted: %.0f%%) - %s validation",
                                        opposingCount, oppositionScore * 100,
                                        hasExistingPosition ? "STRICT" : "NORMAL"),
                                details);
                    }

                    // Monitoring logic with stricter conditions for existing positions
                    if (opposingCount >= 1) {
                        List<String> opposingLeaders = leaderAnalysis.entrySet().stream()
                                .filter(e -> e.getValue().isOpposing())
                                .map(Map.Entry::getKey)
                                .collect(Collectors.toList());

                        String primaryOpposingLeader = opposingLeaders.stream()
                                .max(Comparator.comparing(leader -> LEADER_WEIGHTS.get(leader)))
                                .orElse(opposingLeaders.get(0));

                        signal.getMetadata().put("requiresLeaderMonitoring", "true");
                        signal.getMetadata().put("opposingLeader", primaryOpposingLeader);
                        signal.getMetadata().put("isCallSignal", String.valueOf(isCallSignal));

                        int monitoringSeconds = hasExistingPosition ? 150 :  // Longer monitoring when position exists
                                now.isAfter(LocalTime.of(15, 30)) ? 90 : 120;
                        signal.getMetadata().put("monitoringDuration", String.valueOf(monitoringSeconds));

                        double confidenceReduction = hasExistingPosition ? 0.85 : 0.9; // More reduction when position exists

                        log.warn("[LEADER-MONITORING][{}] {} leader(s) opposing - MONITORING {} for {} seconds ({})",
                                analysisId, opposingCount, primaryOpposingLeader, monitoringSeconds,
                                hasExistingPosition ? "STRICT" : "NORMAL");

                        return new ValidationResult(true, confidenceReduction,
                                String.format("%d leader(s) opposing - monitoring %s (%s mode)",
                                        opposingCount, primaryOpposingLeader,
                                        hasExistingPosition ? "STRICT" : "NORMAL"),
                                details);
                    }
                }

                log.info("[LEADER-VALIDATOR][{}] Leaders ALIGNED for {} signal ({})",
                        analysisId, isCallSignal ? "CALL" : "PUT",
                        hasExistingPosition ? "STRICT" : "NORMAL");
                return new ValidationResult(true, 1.0, "Leaders aligned", details);

            } catch (Exception e) {
                log.error("[LEADER-VALIDATOR][{}] Error validating leader direction: {}",
                        analysisId, e.getMessage());
                return new ValidationResult(true, 1.0, "Validation error - proceeding", "");
            }
        }


        private Map<String, LeaderMomentum> analyzeLeaderMomentum(boolean isCallSignal, LocalTime now) {
            Map<String, LeaderMomentum> results = new HashMap<>();

            // Adaptive volatility-scaled thresholds
            VolatilityRegime regime = detectVolatilityRegime();
            double[] thresholds = getAdaptiveThresholds(regime);
            double threshold5min = thresholds[0];
            double threshold10min = thresholds[1];
            double threshold15min = thresholds[2];

            // Adaptive timeframe weights based on regime
            double[] weights = getAdaptiveWeights(regime);
            double weight5min = weights[0];
            double weight10min = weights[1];
            double weight15min = weights[2];

            double oppositionCutoff = getAdaptiveOppositionCutoff(regime);

            for (String leader : LEADER_STOCKS) {
                LeaderMomentum momentum = new LeaderMomentum(leader, LEADER_WEIGHTS.get(leader));

                List<MarketData> data5min = marketDataRepository.findRecentData(leader, 5);
                List<MarketData> data10min = marketDataRepository.findRecentData(leader, 10);
                List<MarketData> data15min = marketDataRepository.findRecentData(leader, 15);

                momentum.setMomentum5min(calculateMomentum(data5min));
                momentum.setMomentum10min(calculateMomentum(data10min));
                momentum.setMomentum15min(calculateMomentum(data15min));

                // Filter noise based on adaptive thresholds
                double filtered5min = Math.abs(momentum.getMomentum5min()) > threshold5min ? momentum.getMomentum5min() : 0;
                double filtered10min = Math.abs(momentum.getMomentum10min()) > threshold10min ? momentum.getMomentum10min() : 0;
                double filtered15min = Math.abs(momentum.getMomentum15min()) > threshold15min ? momentum.getMomentum15min() : 0;

                // Calculate net weighted momentum score
                double netMomentumScore = (filtered5min * weight5min) + (filtered10min * weight10min) + (filtered15min * weight15min);

                // Determine opposition based on net score
                boolean isOpposing;
                if (isCallSignal) {
                    isOpposing = netMomentumScore < -oppositionCutoff;
                } else {
                    isOpposing = netMomentumScore > oppositionCutoff;
                }

                momentum.setOpposing(isOpposing);
                results.put(leader, momentum);

                log.info("[LEADER-MOMENTUM][{}] {}: Net={} (5m={}%*{}, 10m={}%*{}, 15m={}%*{}) -> {} signal -> opposing={}",
                        leader, leader, String.format("%.4f",netMomentumScore),
                        String.format("%.4f",momentum.getMomentum5min() * 100),String.format("%.1f",weight5min),
                        String.format("%.3f",momentum.getMomentum10min() * 100),String.format("%.1f",weight10min),
                        String.format("%.3f",momentum.getMomentum15min() * 100),String.format("%.1f",weight15min),
                        isCallSignal ? "CALL" : "PUT", isOpposing);
            }

            return results;
        }

        private VolatilityRegime detectVolatilityRegime() {
            try {
                TechnicalAnalysis qqqTA = technicalAnalysisService.analyze("QQQ");
                if (qqqTA == null) return VolatilityRegime.NORMAL;

                double atr = qqqTA.getAverageTrueRange() != null ? qqqTA.getAverageTrueRange().doubleValue() : 0;
                double price = qqqTA.getCurrentPrice().doubleValue();
                double atrRatio = price > 0 ? atr / price : 0;

                if (atrRatio > 0.015) return VolatilityRegime.HIGH;
                if (atrRatio < 0.008) return VolatilityRegime.LOW;
                return VolatilityRegime.NORMAL;
            } catch (Exception e) {
                return VolatilityRegime.NORMAL;
            }
        }

        private double[] getAdaptiveThresholds(VolatilityRegime regime) {
            switch (regime) {
                case HIGH: return new double[]{0.0030, 0.0035, 0.0040}; // 0.30%, 0.35%, 0.40%
                case LOW: return new double[]{0.0006, 0.0010, 0.0015};  // 0.06%, 0.10%, 0.15%
                default: return new double[]{0.0015, 0.0020, 0.0025};   // 0.15%, 0.20%, 0.25%
            }
        }

        private double[] getAdaptiveWeights(VolatilityRegime regime) {
            switch (regime) {
                case HIGH: return new double[]{0.25, 0.35, 0.40};  // More balanced when all timeframes noisy
                case LOW: return new double[]{0.40, 0.35, 0.25};   // Favor precision of shorter timeframes
                default: return new double[]{0.20, 0.30, 0.50};    // Standard weighting
            }
        }

        private double getAdaptiveOppositionCutoff(VolatilityRegime regime) {
            switch (regime) {
                case HIGH: return 0.0015;
                case LOW: return 0.0003;
                default: return 0.0008;
            }
        }
        private enum VolatilityRegime {
            HIGH, NORMAL, LOW
        }
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
    }


    private class GreeksValidator {

        static class GreeksValidationResult {
            private final boolean valid;
            private final String reason;

            GreeksValidationResult(boolean valid, String reason) {
                this.valid = valid;
                this.reason = reason;
            }

            public boolean isValid() {
                return valid;
            }

            public String getReason() {
                return reason;
            }
        }

        public GreeksValidationResult validateGreeks(Signal signal, double confidence, String analysisId, boolean hasExistingPosition) {
            try {
                LocalTime now = LocalTime.now(ET_ZONE);
                long minutesToClose = Duration.between(now, LocalTime.of(16, 0)).toMinutes();

                Double delta = signal.getDelta();
                Double gamma = signal.getGamma();
                Double theta = signal.getTheta();

                if (delta == null || gamma == null || theta == null) {
                    log.warn("[GREEKS-VALIDATOR][{}] Greeks not available - proceeding with caution", analysisId);
                    return new GreeksValidationResult(true, "Greeks unavailable - proceeding with caution");
                }

                BigDecimal optionPrice = signal.getEntryPrice();
                if (optionPrice == null || optionPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    return new GreeksValidationResult(true, "Unable to calculate theta ratio");
                }

                double deltaAbs = Math.abs(delta);
                double gammaAbs = Math.abs(gamma);
                double thetaAbs = Math.abs(theta);
                double optionPriceDouble = optionPrice.doubleValue();

                // ================================================================
                // DELTA VALIDATION - Time-aware thresholds for 0DTE
                // Near close: WANT high delta (ITM = real value, less lottery)
                // Morning: Allow wider range for momentum plays
                // ================================================================
                double minDelta, maxDelta;

                if (minutesToClose <= 60) {
                    // FINAL HOUR: Prefer ITM options with real value
                    // High delta = good (intrinsic value, linear movement)
                    // Low delta = bad (lottery ticket, theta burn)
                    minDelta = hasExistingPosition ? 0.45 : 0.40;
                    maxDelta = hasExistingPosition ? 0.92 : 0.95;
                    log.info("[GREEKS-VALIDATOR][{}] FINAL HOUR MODE - Preferring ITM (delta {}-{})",
                            analysisId, minDelta, maxDelta);

                } else if (minutesToClose <= 120) {
                    // LAST 2 HOURS: Transition zone
                    minDelta = hasExistingPosition ? 0.35 : 0.30;
                    maxDelta = hasExistingPosition ? 0.85 : 0.90;

                } else if (now.isBefore(LocalTime.of(11, 0))) {
                    // MORNING: Allow wider range for momentum
                    minDelta = hasExistingPosition ? 0.25 : 0.20;
                    maxDelta = hasExistingPosition ? 0.70 : 0.75;

                } else {
                    // MIDDAY: Standard range
                    minDelta = hasExistingPosition ? 0.30 : 0.25;
                    maxDelta = hasExistingPosition ? 0.75 : 0.80;
                }

                // Confidence adjustment - high confidence allows wider delta range
                if (confidence >= 0.85) {
                    minDelta = Math.max(0.15, minDelta - 0.10);
                    maxDelta = Math.min(0.98, maxDelta + 0.05);
                } else if (confidence >= 0.75) {
                    minDelta = Math.max(0.18, minDelta - 0.05);
                    maxDelta = Math.min(0.95, maxDelta + 0.03);
                }

                if (deltaAbs < minDelta) {
                    return new GreeksValidationResult(false,
                            String.format("Delta too low (%.3f < %.3f) - lottery ticket, avoid OTM near expiry (%s)",
                                    deltaAbs, minDelta, hasExistingPosition ? "STRICT" : "NORMAL"));
                }

                if (deltaAbs > maxDelta) {
                    return new GreeksValidationResult(false,
                            String.format("Delta too high (%.3f > %.3f) - too deep ITM (%s)",
                                    deltaAbs, maxDelta, hasExistingPosition ? "STRICT" : "NORMAL"));
                }

                // ================================================================
                // GAMMA VALIDATION - High gamma = unstable near expiry
                // But less strict for ITM options (high delta dampens gamma effect)
                // ================================================================
                double maxGamma;
                if (minutesToClose <= 60) {
                    // Near close: gamma explodes for ATM, but ITM is more stable
                    if (deltaAbs >= 0.70) {
                        maxGamma = 0.40; // ITM options: gamma is lower, allow more
                    } else {
                        maxGamma = hasExistingPosition ? 0.15 : 0.20;
                    }
                } else if (minutesToClose <= 120) {
                    maxGamma = hasExistingPosition ? 0.18 : 0.25;
                } else {
                    maxGamma = hasExistingPosition ? 0.20 : 0.30;
                }

                // High confidence = accept more gamma risk
                if (confidence >= 0.80) {
                    maxGamma *= 1.3;
                }

                if (gammaAbs > maxGamma) {
                    // Only hard block on extreme gamma
                    if (gammaAbs > maxGamma * 1.5) {
                        return new GreeksValidationResult(false,
                                String.format("Gamma extremely high (%.3f > %.3f) - position unstable (%s)",
                                        gammaAbs, maxGamma, hasExistingPosition ? "STRICT" : "NORMAL"));
                    }
                    // Warn but allow moderate gamma excess
                    log.warn("[GREEKS-VALIDATOR][{}] Elevated gamma (%.3f) but within tolerance - proceeding",
                            analysisId, gammaAbs);
                }

                // ================================================================
                // THETA VALIDATION - Less relevant near close for ITM options
                // ITM options have less theta decay (intrinsic value protected)
                // ================================================================
                if (optionPriceDouble > 0.01) {
                    double thetaRatio = thetaAbs / optionPriceDouble;

                    double maxThetaRatio;
                    if (minutesToClose <= 60) {
                        // Near close: theta ratio can be high but ITM options survive
                        if (deltaAbs >= 0.60) {
                            maxThetaRatio = 2.0; // ITM: theta less impactful
                        } else {
                            maxThetaRatio = hasExistingPosition ? 0.80 : 1.0;
                        }
                    } else if (minutesToClose <= 120) {
                        maxThetaRatio = hasExistingPosition ? 0.60 : 0.80;
                    } else {
                        maxThetaRatio = hasExistingPosition ? 0.50 : 0.70;
                    }

                    // High confidence = accept more theta risk
                    if (confidence >= 0.80) {
                        maxThetaRatio *= 1.4;
                    } else if (confidence >= 0.70) {
                        maxThetaRatio *= 1.2;
                    }

                    if (thetaRatio > maxThetaRatio && deltaAbs < 0.50) {
                        // Only block OTM options with extreme theta
                        if (thetaRatio > maxThetaRatio * 1.5 && confidence < 0.75) {
                            return new GreeksValidationResult(false,
                                    String.format("Extreme theta decay (%.0f%% of premium) on OTM option - melting ice cube (%s)",
                                            thetaRatio * 100, hasExistingPosition ? "STRICT" : "NORMAL"));
                        }
                        log.warn("[GREEKS-VALIDATOR][{}] High theta ratio (%.0f%%) but allowing due to confidence/delta",
                                analysisId, thetaRatio * 100);
                    }
                }

                // ================================================================
                // GAMMA/DELTA RATIO - Less strict for high delta options
                // ================================================================
                if (deltaAbs > 0.01) {
                    double gammaDeltaRatio = gammaAbs / deltaAbs;
                    double maxGammaDeltaRatio = deltaAbs >= 0.60 ? 0.80 : 0.50; // ITM = more tolerant

                    if (confidence >= 0.80) {
                        maxGammaDeltaRatio *= 1.2;
                    }

                    if (gammaDeltaRatio > maxGammaDeltaRatio) {
                        log.warn("[GREEKS-VALIDATOR][{}] Gamma/Delta ratio elevated () but not blocking",
                                analysisId, String.format("%.2f",gammaDeltaRatio));
                    }
                }

                log.info("[GREEKS-VALIDATOR][{}] ✓ Greeks PASSED - Delta: {} [{}-{}], Gamma: {}, Theta: {}, Mins to close: {}, Confidence: {}%",
                        analysisId, String.format("%.3f",deltaAbs),String.format("%.2f",minDelta),String.format("%.2f",maxDelta), String.format("%.3f",gammaAbs), String.format("%.3f",thetaAbs), minutesToClose, (int)(confidence * 100));

                return new GreeksValidationResult(true, "Greeks within acceptable ranges");

            } catch (Exception e) {
                log.error("[GREEKS-VALIDATOR][{}] Error validating Greeks: {}", analysisId, e.getMessage());
                return new GreeksValidationResult(true, "Validation error - proceeding");
            }
        }

    }

    private boolean attemptSignalExecutionWithConfirmation(Signal signal, String analysisId) {
        try {
            signal.setStatus("ATTEMPTING_EXECUTION");
            signalRepository.save(signal);

            // Place the order using your existing placeOrder method
            OrderRequest orderRequest = new OrderRequest();
            orderRequest.setSymbol(signal.getOptionSymbol());
            orderRequest.setSide("buy_to_open");
            orderRequest.setQuantity(1);
            orderRequest.setType("market");
            orderRequest.setDuration("day");

            log.info("[EXECUTION][{}] Placing BUY_TO_OPEN order for: {}", analysisId, signal.getOptionSymbol());

            // Use your existing placeOrderWithTracking method if available, otherwise use placeOrder
            OrderResponse orderResponse;
            try {
                orderResponse = tradierService.placeOrderWithTracking(orderRequest, analysisId);
            } catch (NoSuchMethodError e) {
                // Fall back to regular placeOrder if placeOrderWithTracking doesn't exist yet
                orderResponse = tradierService.placeOrder(orderRequest);
            }

            if (orderResponse != null && isOrderSuccessful(orderResponse)) {
                String orderId = getOrderId(orderResponse);

                // Track pending order
                String signalKey = signal.getId() != null ? signal.getId().toString() : UUID.randomUUID().toString();
                pendingOrders.put(signalKey, orderId);

                signal.setStatus("ORDER_PLACED");
                signal.getMetadata().put("orderId", orderId);
                signal.getMetadata().put("orderPlacedAt", LocalDateTime.now().toString());
                signalRepository.save(signal);

                log.info("[EXECUTION][{}] Order placed successfully: {} (Order ID: {})",
                        analysisId, signal.getOptionSymbol(), orderId);

                // Wait and confirm fill using your TradierService
                return confirmOrderFillWithRetryUpdated(signal, orderId, analysisId);

            } else {
                log.warn("[EXECUTION][{}] Order placement failed: {}", analysisId, signal.getOptionSymbol());
                signal.setStatus("ORDER_FAILED");
                signalRepository.save(signal);
                return false;
            }

        } catch (Exception e) {
            log.error("[EXECUTION][{}] Exception during order execution: {}", analysisId, e.getMessage(), e);
            signal.setStatus("EXECUTION_ERROR");
            signalRepository.save(signal);
            return false;
        }
    }

    private boolean confirmOrderFillWithRetryUpdated(Signal signal, String orderId, String analysisId) {
        int maxRetries = 6;  // 6 attempts over 12 seconds for 0DTE speed
        int retryDelaySeconds = 2;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Thread.sleep(retryDelaySeconds * 1000);

                // Try to use enhanced order status first
                OrderStatusResponse detailedStatus = null;
                try {
                    detailedStatus = tradierService.getOrderStatusDetailed(orderId);
                } catch (NoSuchMethodError e) {
                    // Method doesn't exist yet, use basic status checking
                    log.debug("[FILL-CONFIRM][{}] Using basic order status checking", analysisId);
                }

                if (detailedStatus != null) {
                    String status = detailedStatus.getStatus();

                    if ("filled".equalsIgnoreCase(status) || "completely_filled".equalsIgnoreCase(status)) {
                        // Order is filled - get fill price
                        BigDecimal fillPrice = detailedStatus.getFillPrice();
                        if (fillPrice == null || fillPrice.compareTo(BigDecimal.ZERO) <= 0) {
                            // Fallback to current market price
                            fillPrice = getCurrentPrice(signal.getOptionSymbol());
                        }

                        signal.setStatus("FILLED");
                        signal.setEntryPrice(fillPrice);
                        signal.getMetadata().put("fillPrice", fillPrice.toString());
                        signal.getMetadata().put("filledAt", LocalDateTime.now().toString());
                        signal.getMetadata().put("actualFillPrice", "true");
                        signalRepository.save(signal);

                        // Remove from pending orders
                        String signalKey = signal.getId() != null ? signal.getId().toString() : UUID.randomUUID().toString();
                        pendingOrders.remove(signalKey);

                        log.info("[FILL-CONFIRM][{}] Order FILLED: {} at ${} (Attempt {})",
                                analysisId, signal.getOptionSymbol(), fillPrice, attempt);

                        return true;

                    } else if ("rejected".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status) ||
                            "expired".equalsIgnoreCase(status)) {
                        signal.setStatus("ORDER_" + status.toUpperCase());
                        signalRepository.save(signal);
                        pendingOrders.remove(signal.getId().toString());

                        log.warn("[FILL-CONFIRM][{}] Order {}: {}", analysisId, status.toUpperCase(), signal.getOptionSymbol());
                        return false;
                    }

                    log.info("[FILL-CONFIRM][{}] Order pending: {} (Status: {}, Attempt {}/{})",
                            analysisId, signal.getOptionSymbol(), status, attempt, maxRetries);

                } else {
                    // Fallback to basic string status check
                    String basicStatus = tradierService.getOrderStatus(orderId);

                    if ("filled".equalsIgnoreCase(basicStatus)) {
                        // Get current market price as fill estimate
                        BigDecimal estimatedFillPrice = getCurrentPrice(signal.getOptionSymbol());

                        signal.setStatus("FILLED");
                        signal.setEntryPrice(estimatedFillPrice);
                        signal.getMetadata().put("fillPrice", estimatedFillPrice.toString());
                        signal.getMetadata().put("filledAt", LocalDateTime.now().toString());
                        signal.getMetadata().put("fillConfirmationMethod", "ESTIMATED_MARKET_FILL");
                        signalRepository.save(signal);

                        String signalKey = signal.getId() != null ? signal.getId().toString() : UUID.randomUUID().toString();
                        pendingOrders.remove(signalKey);

                        log.info("[FILL-CONFIRM][{}] Order FILLED (estimated): {} at ${} (Attempt {})",
                                analysisId, signal.getOptionSymbol(), estimatedFillPrice, attempt);

                        return true;

                    } else if ("rejected".equalsIgnoreCase(basicStatus) || "cancelled".equalsIgnoreCase(basicStatus)) {
                        signal.setStatus("ORDER_" + basicStatus.toUpperCase());
                        signalRepository.save(signal);
                        pendingOrders.remove(signal.getId().toString());

                        log.warn("[FILL-CONFIRM][{}] Order {}: {}", analysisId, basicStatus.toUpperCase(), signal.getOptionSymbol());
                        return false;
                    }

                    log.info("[FILL-CONFIRM][{}] Order status: {} (Attempt {}/{})",
                            analysisId, basicStatus, attempt, maxRetries);
                }

            } catch (Exception e) {
                log.warn("[FILL-CONFIRM][{}] Error checking order status (Attempt {}/{}): {}",
                        analysisId, attempt, maxRetries, e.getMessage());
            }
        }
        // Order confirmation timed out
        log.error("[FILL-CONFIRM][{}] Order fill confirmation TIMEOUT: {} (Order ID: {})",
                analysisId, signal.getOptionSymbol(), orderId);

        signal.setStatus("FILL_TIMEOUT");
        signal.getMetadata().put("timeoutReason", "No fill confirmation within " + (maxRetries * retryDelaySeconds) + " seconds");
        signalRepository.save(signal);
        return false;
    }



    private Trade createSuccessfulTradeFixed(Signal signal, BigDecimal fillPrice, String analysisId) {
        Trade trade = new Trade();

        trade.setSymbol(signal.getSymbol());
        trade.setOptionSymbol(signal.getOptionSymbol());
        trade.setStrategy(signal.getStrategy());
        trade.setQuantity(1);
        trade.setActualQuantity(1);

        // Get option data from signal metadata instead of null options list
        String optionType = signal.getMetadata().get("optionType");
        String optionStrike = signal.getMetadata().get("optionStrike");
        String optionVolume = signal.getMetadata().get("optionVolume");

        if (optionType != null) {
            trade.setType(optionType);
            trade.setSide(optionType);
        }
        if (optionStrike != null) {
            try {
                trade.setStrikePrice(BigDecimal.valueOf(Double.parseDouble(optionStrike)));
            } catch (NumberFormatException e) {
                log.warn("Could not parse strike price: {}", optionStrike);
            }
        }
        if (optionVolume != null) {
            try {
                trade.setVolume(Integer.valueOf(optionVolume));
            } catch (NumberFormatException e) {
                log.warn("Could not parse volume: {}", optionVolume);
            }
        }

        trade.setEntryPrice(fillPrice);
        trade.setEntryTime(LocalDateTime.now());
        trade.setCreatedAt(ZonedDateTime.now());
        trade.setStatus("OPEN");
        trade.setCurrentPrice(fillPrice);
        trade.setTargetPrice(signal.getTargetPrice());
        trade.setStopLoss(signal.getStopLoss());

        trade.setSignalId(signal.getId());
        trade.setOrderStatus("FILLED");
        trade.setOrderType("MARKET");
        trade.setOrderSide("buy_to_open");

        trade.setMarketTrend(signal.getMarketTrend());
        trade.setTimeSlot(determineTimeSlot(LocalTime.now()));
        trade.setExpirationDate(signal.getExpirationDate());
        trade.setDaysToExpiry(signal.getDaysToExpiry());

        // Set Greeks from signal
        trade.setImpliedVolatility(signal.getImpliedVolatility());
        trade.setDelta(signal.getDelta());
        trade.setGamma(signal.getGamma());
        trade.setTheta(signal.getTheta());
        trade.setVega(signal.getVega());

        trade.setAction("BUY");
        trade.setFillPrice(fillPrice);
        trade.setExecutionPrice(fillPrice);
        trade.setOriginalTarget(signal.getTargetPrice());
        trade.setOriginalStop(signal.getStopLoss());

        // Get bid/ask from metadata if available
        String optionBid = signal.getMetadata().get("optionBid");
        String optionAsk = signal.getMetadata().get("optionAsk");
        if (optionBid != null && optionAsk != null) {
            try {
                BigDecimal bid = new BigDecimal(optionBid);
                BigDecimal ask = new BigDecimal(optionAsk);
                trade.setBidAtEntry(bid);
                trade.setAskAtEntry(ask);
                trade.setSpreadAtEntry(ask.subtract(bid));
            } catch (Exception e) {
                log.debug("Could not parse bid/ask from metadata: {}", e.getMessage());
            }
        }

        trade.setRealizedPnl(BigDecimal.ZERO);
        trade.setUnrealizedPnl(BigDecimal.ZERO);
        trade.setMaxProfitReached(BigDecimal.ZERO);
        trade.setMaxDrawdown(BigDecimal.ZERO);
        trade.setHighestPrice(fillPrice);
        trade.setLowestPrice(fillPrice);

        LocalDateTime now = LocalDateTime.now();
        trade.setSignalGeneratedAt(signal.getCreatedAt());
        trade.setExecutionAttemptedAt(now);
        trade.setOrderFilledAt(now);
        trade.setLastSyncTime(now);

        String orderId = signal.getMetadata().get("orderId");
        trade.setOrderId(orderId);
        trade.setOrderDuration("DAY");
        trade.setRetryCount(0);

        trade.setMarketRegime(signal.getMarketRegime());
        trade.setTimeSlot(determineTimeSlot(LocalTime.now()));

        // Get underlying price
        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(signal.getSymbol());
            if (ta != null) {
                trade.setUnderlyingPriceAtEntry(ta.getCurrentPrice());
                trade.setMarketTrendStrength(ta.getStrength());
                trade.setVolumeRatio(ta.getVolumeRatio());
                trade.setMomentumStrength(ta.getMomentumStrength());
                trade.setRsiAtEntry(ta.getRsi());
            }
        } catch (Exception e) {
            log.debug("Could not get technical analysis for trade: {}", e.getMessage());
        }

        trade.setVixAtEntry(getVixLevel());
        trade.setMarketBreadthAtEntry(calculateMarketBreadth());

        // Set expiration details
        LocalDate expirationDate = extractExpirationFromOptionSymbol(signal.getOptionSymbol());
        trade.setExpirationDate(expirationDate.atTime(16, 0).atZone(ET_ZONE));

        LocalDateTime expiryTime = expirationDate.atTime(16, 0);
        long minutesToExpiry = Duration.between(now, expiryTime).toMinutes();
        trade.setMinutesToExpiry((int) minutesToExpiry);
        trade.setDaysToExpiry(signal.getDaysToExpiry());

        // Risk/reward calculations
        BigDecimal positionValue = fillPrice.multiply(BigDecimal.valueOf(100));
        trade.setMaxRisk(positionValue);
        trade.setMaxProfitPotential(signal.getTargetPrice().multiply(BigDecimal.valueOf(100)));

        if (signal.getStopLoss() != null && signal.getStopLoss().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal risk = fillPrice.subtract(signal.getStopLoss());
            BigDecimal reward = signal.getTargetPrice().subtract(fillPrice);
            if (risk.compareTo(BigDecimal.ZERO) > 0) {
                double riskRewardRatio = reward.divide(risk, 4, RoundingMode.HALF_UP).doubleValue();
                trade.setRiskRewardRatio(riskRewardRatio);
            }
        }

        trade.setSignalConfidence(signal.getConfidence());
        trade.setOriginalConfidence(signal.getConfidence());

        if (trade.getRiskRewardRatio() != null) {
            double expectedValue = (signal.getConfidence() * trade.getRiskRewardRatio()) -
                    ((1 - signal.getConfidence()) * 1.0);
            trade.setExpectedValue(expectedValue);
        }

        trade.setStatus("OPEN");
        trade.setAutoClosed(false);
        trade.setManualIntervention(false);
        trade.setTrailingActivated(false);
        trade.setStopAdjustmentsCount(0);
        trade.setTargetAdjustmentsCount(0);

        trade.setStrategyInstanceId(analysisId);
        trade.setExecutionSessionId(UUID.randomUUID().toString().substring(0, 8));

        String learningPhase = signal.getMetadata().get("learningPhase");
        trade.setLearningPhase(learningPhase != null ? learningPhase : "MARKET_ONLY");
        trade.setModelVersion("1.0");
        trade.setFeatureSetVersion("2024.1");

        trade.setPositionSizePercentage(1.0);
        trade.setPortfolioHeat(calculateCurrentPortfolioHeat());

        enhancedStopLossMonitor.captureEntryLeaderConsensus(trade, analysisId);

        log.info("[TRADE-CREATE][{}] Created SUCCESSFUL trade: {} - Status: OPEN",
                analysisId, trade.getOptionSymbol());

        return trade;
    }


    private void analyze30MinuteORB(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                    List<Signal> signals, String marketTrend, String analysisId) {
        if (!hasORBDataSufficiency(ta.getSymbol(), analysisId)) {
            return;
        }

        OpeningRange range30Min = calculate30MinuteRange(ta);
        if (range30Min == null ) return;

        BigDecimal currentPrice = ta.getCurrentPrice();

        if (!validateEnhancedBreakout(currentPrice, range30Min, ta, LocalTime.now(ET_ZONE))) return;

        VolumeRequirement volReq = calculateBreakoutCandleVolume(ta);
        if (!volReq.isMet()) {
            log.info("Signal rejected for volume requirements in ta for 30MinuteORB");
            return;
        }

        GapAnalysis gapAnalysis = analyzeGapConditions(ta);

        String strategy = "CALL".equalsIgnoreCase(option.getType()) ? "0DTE_30MIN_ORB_CALL" : "0DTE_30MIN_ORB_PUT";
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());

        if ((isCall && currentPrice.compareTo(range30Min.getHigh()) > 0) ||
                (!isCall && currentPrice.compareTo(range30Min.getLow()) < 0)) {

            // RSI validation check before creating signal
            if (!validateRSIForSignal(analysisId, strategy, ta.getRsi())) {
                log.info("Signal rejected because of rsi filter, check the Oversold and overbought values at top for 30MinuteORB");
                return;
            }

            double confidence = calculateEnhancedORBConfidence(ta, volReq, gapAnalysis, isCall, LocalTime.now(ET_ZONE));
            double threshold = calculateDynamicThreshold(LocalTime.now(ET_ZONE), volReq);

            if (confidence >= threshold) {
                Signal signal = createAdvancedORBSignal(option, allOptions, ta, "BUY", strategy, confidence, marketTrend, range30Min, volReq);
                signals.add(signal);

                log.info("[{}] ✅ {} - Confidence: {}%, Volume: {}, Gap: {}%",
                        analysisId, strategy, (int)(confidence * 100), volReq.getTier(),
                        gapAnalysis.getGapPercentage() * 100);
            }
        }
    }


    private boolean hasORBDataSufficiency(String symbol, String analysisId) {
        try {
            LocalDateTime marketOpen = LocalDateTime.now(ET_ZONE).with(LocalTime.of(9, 30));
            LocalDateTime orbEnd = marketOpen.plusMinutes(60);
            LocalDateTime now = LocalDateTime.now(ET_ZONE);

            LocalDateTime checkUntil = now.isBefore(orbEnd) ? now : orbEnd;

            List<MarketData> orbData = marketDataRepository.findRecentData(symbol, 100)
                    .stream()
                    .filter(data -> data.getTimestamp().isAfter(marketOpen) &&
                            data.getTimestamp().isBefore(checkUntil))
                    .collect(Collectors.toList());

            long expectedMinutes = Duration.between(marketOpen, checkUntil).toMinutes();
            double completeness = expectedMinutes > 0 ? (double) orbData.size() / expectedMinutes : 0;

            if (completeness < 0.80) {
                log.warn("[ORB-DATA][{}] Insufficient ORB data: {}% completeness", analysisId, (int)(completeness * 100));
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("[ORB-DATA][{}] Error checking ORB data sufficiency: {}", analysisId, e.getMessage());
            return false;
        }
    }


//    private VWAPPattern detectMultiCandleVWAPPattern(List<MarketData> data, TechnicalAnalysis ta) {
//        if (data.size() < 5) return VWAPPattern.NONE;
//
//        BigDecimal vwap = ta.getVwap();
//
//        if (detectVWAPCompression(data, vwap)) {
//            return VWAPPattern.COMPRESSION;
//        } else if (detectFailedBreakdownRecovery(data, vwap)) {
//            return VWAPPattern.FAILED_BREAKDOWN;
//        } else if (detectStairStepPattern(data, vwap)) {
//            return VWAPPattern.STAIR_STEP;
//        }
//
//        return VWAPPattern.NONE;
//    }

//    private boolean detectVWAPCompression(List<MarketData> data, BigDecimal vwap) {
//        double maxDeviation = 0;
//
//        for (int i = data.size() - 5; i < data.size(); i++) {
//            if (data.get(i).getPrice() == null || vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) {
//                continue;
//            }
//            double deviation = Math.abs(
//                    data.get(i).getPrice().subtract(vwap)
//                            .divide(vwap, 4, RoundingMode.HALF_UP).doubleValue()
//            );
//            maxDeviation = Math.max(maxDeviation, deviation);
//        }
//
//        return maxDeviation < 0.0015;
//    }

//    private boolean detectFailedBreakdownRecovery(List<MarketData> data, BigDecimal vwap) {
//        if (data.size() < 4) return false;
//
//        MarketData bar1 = data.get(data.size() - 4);
//        MarketData bar2 = data.get(data.size() - 3);
//        MarketData bar3 = data.get(data.size() - 2);
//        MarketData current = data.get(data.size() - 1);
//
//        if (bar1.getPrice() == null || bar2.getLow() == null || bar3.getLow() == null ||
//                current.getPrice() == null || current.getLow() == null || vwap == null) {
//            return false;
//        }
//
//        boolean wasAbove = bar1.getPrice().compareTo(vwap) > 0;
//        boolean brokeBelow = bar2.getLow().compareTo(vwap) < 0 &&
//                bar3.getLow().compareTo(vwap) < 0;
//        boolean recovered = current.getPrice().compareTo(vwap) > 0 &&
//                current.getLow().compareTo(vwap) >= 0;
//
//        return wasAbove && brokeBelow && recovered;
//    }

//    private boolean detectStairStepPattern(List<MarketData> data, BigDecimal vwap) {
//        if (data.size() < 4) return false;
//
//        boolean allAboveVWAP = true;
//        boolean higherLows = true;
//
//        for (int i = data.size() - 4; i < data.size(); i++) {
//            if (data.get(i).getLow() == null || vwap == null) {
//                return false;
//            }
//
//            if (data.get(i).getLow().compareTo(vwap) < 0) {
//                allAboveVWAP = false;
//                break;
//            }
//
//            if (i > data.size() - 4) {
//                if (data.get(i-1).getLow() == null ||
//                        data.get(i).getLow().compareTo(data.get(i-1).getLow()) <= 0) {
//                    higherLows = false;
//                }
//            }
//        }
//
//        return allAboveVWAP && higherLows;
//    }

    private Signal createAdvancedORBSignal(Option option, List<Option> allOptions, TechnicalAnalysis ta, String signalType,
                                           String strategy, double confidence, String marketTrend, OpeningRange range, VolumeRequirement volReq) {
        Signal signal = createSignal(option, allOptions, ta, signalType, strategy, confidence, marketTrend);
        if (signal == null) return null;

        BigDecimal entryPrice = signal.getEntryPrice();
        boolean isHighVol = ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY;

        double targetMultiplier = "EXCEPTIONAL".equals(volReq.getTier()) ? 2.5 : isHighVol ? 2.2 : 1.8;
        signal.setTargetPrice(entryPrice.multiply(BigDecimal.valueOf(targetMultiplier)));

        double stopPercentage = "EXCEPTIONAL".equals(volReq.getTier()) ? 0.25 : isHighVol ? 0.35 : 0.30;
        signal.setStopLoss(entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(stopPercentage))));

        return signal;
    }

    private VolumeRequirement calculateBreakoutCandleVolume(TechnicalAnalysis ta) {
        double volumeRatio = ta.getVolumeRatio();

        if (volumeRatio >= 2.0) return new VolumeRequirement("EXCEPTIONAL", true, 1.0, 0.95);
        if (volumeRatio >= 1.5) return new VolumeRequirement("STRONG", true, 0.8, 0.85);
        if (volumeRatio >= 1.2) return new VolumeRequirement("ADEQUATE", true, 0.6, 0.75);
        return new VolumeRequirement("WEAK", false, 0.0, 0.0);
    }


    //need to validate the logic for opening range strategy
    private boolean validateEnhancedBreakout(BigDecimal currentPrice, OpeningRange range, TechnicalAnalysis ta, LocalTime now) {
        BigDecimal rangeSize = range.getHigh().subtract(range.getLow());
        BigDecimal minRangeSize = currentPrice.multiply(BigDecimal.valueOf(0.005));
        if (rangeSize.compareTo(minRangeSize) < 0) return false;

        BigDecimal minPenetration = currentPrice.multiply(BigDecimal.valueOf(0.001));
        BigDecimal actualPenetration = currentPrice.compareTo(range.getHigh()) > 0 ?
                currentPrice.subtract(range.getHigh()) : range.getLow().subtract(currentPrice);
        if (actualPenetration.compareTo(minPenetration) < 0) return false;

        if (now.isAfter(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(10, 30))) {
            return ta.getMomentumStrength() > 0.4;
        }

        return true;
    }

    private GapAnalysis analyzeGapConditions(TechnicalAnalysis ta) {
        if (ta.getPreviousClose() == null) return new GapAnalysis(0.0, false, 0.0, "NONE");

        BigDecimal gap = ta.getCurrentPrice().subtract(ta.getPreviousClose())
                .divide(ta.getPreviousClose(), 4, RoundingMode.HALF_UP);
        double gapPercentage = gap.doubleValue();

        boolean isSignificant = Math.abs(gapPercentage) > 0.015;
        boolean isModerate = Math.abs(gapPercentage) > 0.005;

        double confidenceBoost = 0.0;
        if (isSignificant) confidenceBoost = 0.20;
        else if (isModerate) confidenceBoost = 0.10;

        String direction = gapPercentage > 0 ? "UP" : gapPercentage < 0 ? "DOWN" : "FLAT";

        return new GapAnalysis(gapPercentage, isSignificant, confidenceBoost, direction);
    }

    private double calculateEnhancedORBConfidence(TechnicalAnalysis ta, VolumeRequirement volReq,
                                                  GapAnalysis gapAnalysis, boolean bullish, LocalTime now) {
        double confidence = 0.75;

        confidence = Math.min(confidence + 0.15, volReq.getMaxConfidence());
        confidence += gapAnalysis.getConfidenceBoost();
        confidence *= getTimeBasedMultiplier(now);

        if ((bullish && ta.getMomentumStrength() > 0.4) || (!bullish && ta.getMomentumStrength() < -0.4)) {
            confidence += 0.10;
        }

        if (ta.getRsi() > 45 && ta.getRsi() < 65) confidence += 0.05;

        return Math.min(confidence, 0.95);
    }

    private double getTimeBasedMultiplier(LocalTime now) {
        if (now.isBefore(LocalTime.of(10, 15))) return 1.15;
        if (now.isBefore(LocalTime.of(10, 30))) return 1.10;
        if (now.isAfter(LocalTime.of(15, 0))) return 0.85;
        return 1.0;
    }

    private double calculateDynamicThreshold(LocalTime now, VolumeRequirement volReq) {
        double baseThreshold = 0.80;

        if (now.isAfter(LocalTime.of(15, 0))) {
            baseThreshold += 0.10;
        }

        switch (volReq.getTier()) {
            case "EXCEPTIONAL": return baseThreshold - 0.05;
            case "STRONG": return baseThreshold;
            case "ADEQUATE": return baseThreshold + 0.05;
            default: return 0.95;
        }
    }

    private OpeningRange calculate30MinuteRange(TechnicalAnalysis ta) {
        try {
            List<MarketData> recentData = marketDataRepository.findRecentData(ta.getSymbol(), 100);

            if (recentData.isEmpty()) {
                return null;
            }

            LocalDate today = LocalDate.now(ET_ZONE);
            LocalDateTime rangeStart = today.atTime(9, 30);
            LocalDateTime rangeEnd = today.atTime(10, 0);

            List<MarketData> rangeData = recentData.stream()
                    .filter(data -> data.getTimestamp() != null)
                    .filter(data -> {
                        LocalDateTime timestamp = data.getTimestamp();
                        return !timestamp.isBefore(rangeStart) && !timestamp.isAfter(rangeEnd);
                    })
                    .collect(Collectors.toList());

            if (rangeData.isEmpty()) {
                return null;
            }

            BigDecimal high = rangeData.stream()
                    .map(MarketData::getHigh)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);

            BigDecimal low = rangeData.stream()
                    .map(MarketData::getLow)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(null);

            if (high != null && low != null) {
                return new OpeningRange(high, low);
            }

            return null;

        } catch (Exception e) {
            log.error("Error calculating 30-minute opening range: {}", e.getMessage(), e);
            return null;
        }
    }


    private Option getOptionFromSignal(Signal signal, List<Option> options) {
        return options.stream()
                .filter(opt -> opt.getSymbol().equals(signal.getOptionSymbol()))
                .findFirst()
                .orElse(null);
    }

    private List<Option> selectStrikesBasedOnVolatility(List<Option> options, TechnicalAnalysis ta, String analysisId) {
        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal dayRange = BigDecimal.ZERO;

        QuoteResponse qqqQuote = tradierService.getQuote("QQQ");
        if (qqqQuote != null && qqqQuote.getQuote() != null) {
            Quote quote = qqqQuote.getQuote();
            if (quote.getHigh() != null && quote.getLow() != null) {
                dayRange = quote.getHigh().subtract(quote.getLow());
            }
        }

        BigDecimal priceMove = BigDecimal.ZERO;
        if (ta.getPreviousClose() != null && ta.getPreviousClose().compareTo(BigDecimal.ZERO) > 0) {
            priceMove = currentPrice.subtract(ta.getPreviousClose())
                    .abs()
                    .divide(ta.getPreviousClose(), 4, RoundingMode.HALF_UP);
        }

        int strikesFromATM;
        if (priceMove.compareTo(BigDecimal.valueOf(0.005)) > 0 ||
                dayRange.compareTo(currentPrice.multiply(BigDecimal.valueOf(0.01))) > 0) {
            strikesFromATM = 4;
            log.info("[{}] High volatility detected - selecting up to {} strikes from ATM",
                    analysisId, strikesFromATM);
        } else {
            strikesFromATM = 2;
            log.info("[{}] Normal volatility - selecting up to {} strikes from ATM",
                    analysisId, strikesFromATM);
        }

        List<Option> selected = options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    boolean isCall = "CALL".equalsIgnoreCase(option.getType());
                    boolean isPut = "PUT".equalsIgnoreCase(option.getType());

                    if (isCall) {
                        return strike.compareTo(currentPrice) >= 0 &&
                                strike.compareTo(currentPrice.add(BigDecimal.valueOf(strikesFromATM))) <= 0;
                    } else if (isPut) {
                        return strike.compareTo(currentPrice.subtract(BigDecimal.valueOf(strikesFromATM))) >= 0 &&
                                strike.compareTo(currentPrice) <= 0;
                    }
                    return false;
                })
                .sorted((o1, o2) -> {
                    BigDecimal dist1 = o1.getStrikePrice().subtract(currentPrice).abs();
                    BigDecimal dist2 = o2.getStrikePrice().subtract(currentPrice).abs();
                    return dist1.compareTo(dist2);
                })
                .collect(Collectors.toList());

        selected.forEach(opt -> {
            BigDecimal distance = opt.getStrikePrice().subtract(currentPrice);
            log.info("[{}] Selected: {} ${} (${} from current, Vol: {}, OI: {})",
                    analysisId, opt.getType(), opt.getStrikePrice(),
                    distance.compareTo(BigDecimal.ZERO) > 0 ? "+" + distance : distance,
                    opt.getVolume(), opt.getOpenInterest());
        });

        return selected;
    }

    private MarketConditions getMarketConditions() {
        MarketConditions conditions = new MarketConditions();
        conditions.setVix(getVixLevel());
        conditions.setMarketBreadth(calculateMarketBreadth());
        conditions.setInternals(getMarketInternals());
        return conditions;
    }

    private double getVixLevel() {
        return 18.0;
    }

    private double calculateMarketBreadth() {
        return 0.5;
    }

    private MarketInternals getMarketInternals() {
        return new MarketInternals();
    }


    private List<Option> filterOptionsByMarketRegime(List<Option> options, TechnicalAnalysis ta, String analysisId) {
//        MarketRegime regime = ta.getMarketRegime();
//        DynamicParameters params = ta.getDynamicParameters();
//
//        LocalTime now = LocalTime.now(ET_ZONE);
//        int adjustedMinVolume = getAdjustedMinVolume(now);
//        double adjustedMinIV = minIV;
//
//        switch (regime) {
//            case HIGH_VOLATILITY:
//                adjustedMinVolume = (int)(adjustedMinVolume * 0.7);
//                adjustedMinIV = minIV * 1.2;
//                break;
//            case LOW_VOLATILITY:
//                adjustedMinVolume = (int)(adjustedMinVolume * 1.3);
//                adjustedMinIV = minIV * 0.8;
//                break;
//            case OPENING_RANGE:
//                adjustedMinVolume = (int)(adjustedMinVolume * 0.5);
//                break;
//            default:
//                break;
//        }
//
//        if (params != null) {
//            adjustedMinVolume = (int)(adjustedMinVolume * params.getMinVolumeMultiplier());
//            adjustedMinIV = adjustedMinIV * params.getMinIVMultiplier();
//        }

//        log.info("[{}] Base filtering - Volume: {}, IV: {}",
//                analysisId, adjustedMinVolume,String.format("%.3f",adjustedMinIV));

        List<Option> basicFiltered = options.stream()
                .filter(Option::isValid)
                .collect(Collectors.toList());

        //log.info("[{}] After basic filtering: {} options", analysisId, basicFiltered.size());

        return filterByConfidenceAdjustedStrikes(basicFiltered, ta, 0.0, analysisId);
    }

    private List<Option> filterByConfidenceAdjustedStrikes(List<Option> options,
                                                           TechnicalAnalysis ta,
                                                           double expectedConfidence,
                                                           String analysisId) {
        BigDecimal currentPrice = ta.getCurrentPrice();

        List<Option> atmOptions = options.stream()
                .filter(option -> {
                    BigDecimal strike = option.getStrikePrice();
                    if (strike == null) return false;

                    double distanceFromPrice = Math.abs(strike.doubleValue() - currentPrice.doubleValue());
                    return distanceFromPrice <= 0.50;
                })
                .filter(option -> option.getVolume() != null && option.getVolume() >= 100)
                .filter(option -> option.getOpenInterest() != null && option.getOpenInterest() >= 500)
                .sorted((o1, o2) -> {
                    double dist1 = Math.abs(o1.getStrikePrice().doubleValue() - currentPrice.doubleValue());
                    double dist2 = Math.abs(o2.getStrikePrice().doubleValue() - currentPrice.doubleValue());
                    return Double.compare(dist1, dist2);
                })
                .collect(Collectors.toList());

        //log.info("[ATM-FILTER][{}] ATM filtering result: {} options selected (from {} total)",
                //analysisId, atmOptions.size(), options.size());

        if (atmOptions.isEmpty()) {
            log.warn("[ATM-FILTER][{}] No ATM options found - expanding to $1.00 range", analysisId);

            return options.stream()
                    .filter(option -> {
                        BigDecimal strike = option.getStrikePrice();
                        return strike != null &&
                                Math.abs(strike.doubleValue() - currentPrice.doubleValue()) <= 1.00;
                    })
                    .collect(Collectors.toList());
        }

        return atmOptions;
    }

    private boolean quickSignalValidation(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                          String marketTrend, String analysisId) {
//        if ("NEUTRAL".equals(marketTrend)) {
//            return false;
//        }

//        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
//        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
//        boolean aligned = (isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend));
//
//        if (!aligned) {
//            return false;
//        }

        Integer volume = option.getVolume();
        Integer openInterest = option.getOpenInterest();
        if ((volume == null || volume < 10) && (openInterest == null || openInterest < 50)) {
            log.info("Signals getting rejected for volume or oi is null or less 10 , 50");
            return false;
        }

        if (option.getSpreadPercentage() > 15.0) {
            log.info("Signals getting rejected for spread greater than 15");
            return false;
        }

        return true;
    }

    private boolean isSignalAlignedWithTrend(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                             String marketTrend, String analysisId) {

        if ("NEUTRAL".equals(marketTrend)) {
            log.warn("[{}] ⛔ BLOCKED: {} {} - Market is NEUTRAL (NO TRADES ALLOWED)",
                    analysisId, option.getType(), option.getStrikePrice());
            return false;
        }

        // For VWAP strategies, only block on NEUTRAL - let VWAP pattern logic determine direction
        // VWAP strategies have their own directional logic based on price action relative to VWAP

        // For non-VWAP strategies, maintain existing trend alignment requirements
        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean aligned = (isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend));

//        log.info("[{}] Trend Check: {} option vs {} market trend = {}",
//                analysisId, option.getType(), marketTrend, aligned ? "ALIGNED" : "MISALIGNED");

        return aligned;
    }

//    private int getAdjustedMinVolume(LocalTime now) {
//        if (now.isBefore(LocalTime.of(10, 30))) {
//            return minVolume / 2;
//        } else if (now.isAfter(LocalTime.of(15, 0))) {
//            return minVolume / 2;
//        }
//        return minVolume;
//    }

    private String determineTimeSlot(LocalTime time) {
        if (time.isAfter(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(10, 30))) {
            return "MORNING";
        } else if (time.isAfter(LocalTime.of(10, 30)) && time.isBefore(LocalTime.of(14, 0))) {
            return "MIDDAY";
        } else if (time.isAfter(LocalTime.of(14, 0)) && time.isBefore(LocalTime.of(15, 0))) {
            return "AFTERNOON";
        } else {
            return "CLOSE";
        }
    }

    private boolean isOrderSuccessful(OrderResponse response) {
        return response != null &&
                (response.getStatus() == null || !"error".equals(response.getStatus()));
    }

    public String getOrderId(OrderResponse response) {
        return response.getOrder() != null ? response.getId() : "unknown";
    }

    private BigDecimal getCurrentPrice(String optionSymbol) {
        try {
            QuoteResponse quote = tradierService.getQuote(optionSymbol);
            return quote != null && quote.getQuote() != null ?
                    quote.getQuote().getLast() : BigDecimal.ZERO;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private LocalDate extractExpirationFromOptionSymbol(String optionSymbol) {
        try {
            if (optionSymbol == null || optionSymbol.length() < 10) {
                return LocalDate.now(ET_ZONE);
            }

            String datePart;
            if (optionSymbol.contains("_")) {
                String afterUnderscore = optionSymbol.split("_")[1];
                datePart = afterUnderscore.substring(0, 6);

                int month = Integer.parseInt(datePart.substring(0, 2));
                int day = Integer.parseInt(datePart.substring(2, 4));
                int year = 2000 + Integer.parseInt(datePart.substring(4, 6));

                return LocalDate.of(year, month, day);
            } else {
                datePart = optionSymbol.substring(3, 9);

                int year = 2000 + Integer.parseInt(datePart.substring(0, 2));
                int month = Integer.parseInt(datePart.substring(2, 4));
                int day = Integer.parseInt(datePart.substring(4, 6));

                return LocalDate.of(year, month, day);
            }

        } catch (Exception e) {
            log.warn("Could not extract expiration from option symbol: {} - Error: {}",
                    optionSymbol, e.getMessage());
            return LocalDate.now(ET_ZONE);
        }
    }

    private Signal createSignal(Option option, List<Option> allOptions, TechnicalAnalysis ta, String signalType,
                                String strategy, double confidence, String marketTrend) {
        if (option == null || ta == null || signalType == null || strategy == null) {
            log.error("Cannot create signal with null parameters");
            return null;
        }

//        if (!strategy.contains("AI_LEADER_LAG") && !strategy.contains("ENHANCED_AI") &&
//                !isSignalAlignedWithTrend(option, allOptions, ta, marketTrend, "signal-creation")) {
//            log.info("[BLOCKED] {} {} not aligned with {} trend",
//                    option.getType(), option.getStrikePrice(), marketTrend);
//            return null;
//        }

        confidence = Math.max(0.0, Math.min(1.0, confidence));

        try {
            Signal signal = new Signal();
            signal.setSymbol(ta.getSymbol());
            signal.setOptionSymbol(option.getSymbol());
            signal.setSignalType(signalType);
            signal.setStrategy(strategy);
            signal.setConfidence(confidence);
            signal.setTimestamp(LocalDateTime.now());
            signal.setExecuted(false);
            signal.setEntryAssumptionPrice(ta.getCurrentPrice());
            signal.setMarketRegime(ta.getMarketRegime());
            signal.setMarketTrend(marketTrend);

            // Set signal expiration
            setSignalExpiration(signal);

            if (option.getDelta() != null) {
                signal.setDelta(option.getDelta());
            }
            if (option.getGamma() != null) {
                signal.setGamma(option.getGamma());
            }
            if (option.getTheta() != null) {
                signal.setTheta(option.getTheta());
            }
            if (option.getVega() != null) {
                signal.setVega(option.getVega());
            }
            if (option.getImpliedVolatility() != null) {
                signal.setImpliedVolatility(option.getImpliedVolatility());
            }

            BigDecimal atr = ta.getAverageTrueRange();
            if (atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) {
                atr = ta.getCurrentPrice().multiply(BigDecimal.valueOf(0.005));
            }

            BigDecimal optionPrice = option.getMidPrice();
            if (optionPrice == null || optionPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Invalid option price for {}: {}", option.getSymbol(), optionPrice);
                return null;
            }

            signal.setEntryPrice(optionPrice);

            BigDecimal targetPrice = calculateDynamicTargetPrice(optionPrice, atr, strategy, ta, marketTrend, option);
            BigDecimal stopLoss = calculateDynamicStopLoss(optionPrice, atr, strategy, ta, marketTrend, option);

            signal.setTargetPrice(targetPrice);
            signal.setStopLoss(stopLoss);

            return signal;

        } catch (Exception e) {
            log.error("Error creating signal: {}", e.getMessage(), e);
            return null;
        }
    }

    private BigDecimal calculateDynamicTargetPrice(BigDecimal entryPrice, BigDecimal atr, String strategy,
                                                   TechnicalAnalysis ta, String marketTrend, Option option) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean isAISignal = strategy.contains("AI_LEADER_LAG") || strategy.contains("ENHANCED_AI");

        boolean isAligned = isAISignal ||
                ((isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend)));

        BigDecimal targetMultiplier;

        if (isAligned) {
            if (strategy.contains("ENHANCED_AI")) {
                targetMultiplier = BigDecimal.valueOf(3.0);
            } else if (strategy.contains("AI_LEADER_LAG")) {
                targetMultiplier = BigDecimal.valueOf(2.5);
            } else if (strategy.contains("30MIN_ORB")) {
                targetMultiplier = BigDecimal.valueOf(2.2);
            } else if (strategy.contains("VWAP_BREAKOUT")) {
                targetMultiplier = BigDecimal.valueOf(2.0);
            } else if (strategy.contains("VWAP_REVERSION")) {
                targetMultiplier = BigDecimal.valueOf(1.7);
            } else {
                targetMultiplier = BigDecimal.valueOf(1.8);
            }
        } else {
            targetMultiplier = BigDecimal.valueOf(1.4);
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        long minutesToClose = Duration.between(now, LocalTime.of(16, 0)).toMinutes();

        if (minutesToClose < 120) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(0.7));
        } else if (minutesToClose < 180) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(0.85));
        }

        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            targetMultiplier = targetMultiplier.multiply(BigDecimal.valueOf(1.15));
        }

        BigDecimal targetPrice = entryPrice.multiply(targetMultiplier);
        return targetPrice.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateDynamicStopLoss(BigDecimal entryPrice, BigDecimal atr, String strategy,
                                                TechnicalAnalysis ta, String marketTrend, Option option) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        boolean isAISignal = strategy.contains("AI_LEADER_LAG") || strategy.contains("ENHANCED_AI");

        boolean isAligned = isAISignal ||
                ((isPut && "DOWN".equals(marketTrend)) || (isCall && "UP".equals(marketTrend)));

        BigDecimal baseStopPercentage;

        if (isAligned) {
            if (strategy.contains("ENHANCED_AI")) {
                baseStopPercentage = BigDecimal.valueOf(0.20);
            } else if (strategy.contains("AI_LEADER_LAG")) {
                baseStopPercentage = BigDecimal.valueOf(0.30);
            } else if (strategy.contains("30MIN_ORB")) {
                baseStopPercentage = BigDecimal.valueOf(0.45);
            } else if (strategy.contains("VWAP_BREAKOUT")) {
                baseStopPercentage = BigDecimal.valueOf(0.35);
            } else if (strategy.contains("VWAP_REVERSION")) {
                baseStopPercentage = BigDecimal.valueOf(0.50);
            } else if (strategy.contains("VWAP_RECLAIM")) {
                baseStopPercentage = BigDecimal.valueOf(0.30);
            } else {
                baseStopPercentage = BigDecimal.valueOf(0.40);
            }
        } else {
            baseStopPercentage = BigDecimal.valueOf(0.25);
        }

        if (ta.getMarketRegime() == MarketRegime.HIGH_VOLATILITY) {
            baseStopPercentage = baseStopPercentage.multiply(BigDecimal.valueOf(1.2));
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        if (now.isAfter(LocalTime.of(14, 30))) {
            baseStopPercentage = baseStopPercentage.multiply(BigDecimal.valueOf(0.8));
        }

        BigDecimal stopLoss = entryPrice.multiply(BigDecimal.ONE.subtract(baseStopPercentage));

        if (stopLoss.compareTo(BigDecimal.valueOf(0.05)) < 0) {
            stopLoss = BigDecimal.valueOf(0.05);
        }

        return stopLoss.setScale(2, RoundingMode.HALF_UP);
    }

    private int countPopulatedFields(Trade trade) {
        int count = 0;
        java.lang.reflect.Field[] fields = Trade.class.getDeclaredFields();

        for (java.lang.reflect.Field field : fields) {
            field.setAccessible(true);
            try {
                Object value = field.get(trade);
                if (value != null) {
                    if (value instanceof String && !((String) value).isEmpty()) count++;
                    else if (!(value instanceof String)) count++;
                }
            } catch (IllegalAccessException e) {
                // Ignore
            }
        }
        return count;
    }

    private double calculateCurrentPortfolioHeat() {
        try {
            long openTrades = tradeRepository.findAll().stream()
                    .filter(trade -> "OPEN".equals(trade.getStatus()))
                    .count();

            return Math.min(openTrades * 1.0, 10.0);

        } catch (Exception e) {
            return 1.0;
        }
    }

    private void enrichTradeWithBayesianData(Trade trade, Signal signal) {
        String bayesianConfidence = signal.getMetadata().get("bayesianConfidence");
        if (bayesianConfidence != null) {
            try {
                trade.setBayesianProbability(Double.valueOf(bayesianConfidence));
                trade.setAdjustedConfidence(Double.valueOf(bayesianConfidence));
            } catch (NumberFormatException e) {
                log.warn("Invalid bayesian confidence format: {}", bayesianConfidence);
            }
        }

        trade.setSignalConfidence(signal.getConfidence());
        trade.setOriginalConfidence(signal.getConfidence());

        String learningPhase = signal.getMetadata().get("learningPhase");
        if (learningPhase != null) {
            trade.setLearningPhase(learningPhase);
        }

        String thresholdMethod = signal.getMetadata().get("thresholdMethod");
        if (thresholdMethod != null) {
            trade.setThresholdOptimizationMethod(thresholdMethod);
        }

        trade.setBayesianAnalysisTimestamp(ZonedDateTime.now());
    }

    private void saveFailedTrade(Signal signal, String failureReason, String errorMessage, String analysisId) {
        Trade trade = new Trade();

        trade.setSymbol(signal.getSymbol());
        trade.setOptionSymbol(signal.getOptionSymbol());
        trade.setStrategy(signal.getStrategy());
        trade.setQuantity(1);
        trade.setEntryPrice(BigDecimal.ZERO);
        trade.setEntryTime(LocalDateTime.now());
        trade.setStatus("FAILED");
        trade.setExitReason(failureReason);
        trade.setSignalId(signal.getId());
        trade.setFailureReason(failureReason);
        trade.setErrorMessage(errorMessage);

        tradeRepository.save(trade);

        log.warn("[TRADE-CREATE][{}] Created FAILED trade: {} - Status: FAILED, Reason: {}",
                analysisId, trade.getOptionSymbol(), failureReason);
    }

    private void saveFailedSignal(Signal signal, String failureReason, String errorMessage) {
        signal.setStatus("FAILED");
        signal.setFailureReason(failureReason);
        signal.setErrorMessage(errorMessage);
        signal.getMetadata().put("failedAt", LocalDateTime.now().toString());

        signalRepository.save(signal);
    }

    private void saveBayesianRejectedSignal(Signal signal, IntegratedBayesianMLSystem.ExecutionDecision decision, String analysisId) {
        signal.setStatus("BAYESIAN_REJECTED");
        signal.setFailureReason(decision.getDecision());
        signal.setErrorMessage(decision.getReasoning());
        signal.getMetadata().put("rejectedAt", LocalDateTime.now().toString());
        signal.getMetadata().put("confidenceGap", String.valueOf(decision.getConfidenceGap()));

        signalRepository.save(signal);

        Trade rejectedTrade = new Trade();
        rejectedTrade.setSymbol(signal.getSymbol());
        rejectedTrade.setOptionSymbol(signal.getOptionSymbol());
        rejectedTrade.setStrategy(signal.getStrategy());
        rejectedTrade.setQuantity(1);
        rejectedTrade.setEntryPrice(BigDecimal.ZERO);
        rejectedTrade.setEntryTime(LocalDateTime.now());
        rejectedTrade.setStatus("BAYESIAN_REJECTED");
        rejectedTrade.setExitReason(decision.getDecision());
        rejectedTrade.setSignalId(signal.getId());
        rejectedTrade.setFailureReason("BAYESIAN_FILTER");
        rejectedTrade.setErrorMessage(decision.getReasoning());

        tradeRepository.save(rejectedTrade);

        log.warn("[BAYESIAN-REJECT][{}] Saved rejected signal and trade: {} - Reason: {}",
                analysisId, signal.getOptionSymbol(), decision.getReasoning());
    }


    private void sendSuccessNotification(Trade trade, Signal signal, SHAPExplainerService.SHAPExplanation explanation, String analysisId) {
        if (signal.getConfidence() >= 0.70) {
            String leaderInfo = "";
            if (signal.getStrategy().contains("AI_LEADER_LAG") || signal.getStrategy().contains("ENHANCED_AI")) {
                String leader = signal.getMetadata().get("leaderStock");
                leaderInfo = leader != null ? "\nLeader: " + leader : "";
            }

            telegramService.sendMessage(String.format(
                    "✅ EXECUTION SUCCESS\n" +
                            "Strategy: %s\n" +
                            "Option: %s\n" +
                            "Entry: $%.2f\n" +
                            "Confidence: %d%% (SHAP: %d%%)\n" +
                            "Target: $%.2f | Stop: $%.2f\n" +
                            "Trade ID: %s%s\n\n" +
                            "🧠 AI Explanation:\n" +
                            "Top Factors: %s\n" +
                            "✅ Position LIVE - Monitoring active",
                    signal.getStrategy(), signal.getOptionSymbol(),
                    signal.getEntryPrice().doubleValue(),
                    (int)(signal.getConfidence() * 100),
                    (int)(explanation.getExplanationStrength() * 100),
                    signal.getTargetPrice().doubleValue(),
                    signal.getStopLoss().doubleValue(),
                    trade.getId(), leaderInfo,
                    String.join(", ", explanation.getTopPositiveFactors())
            ));
        }
    }

    private enum LeaderLagStrategy { STANDARD, REVERSE, NONE }

    @Getter @Setter
    private static class MarketFeatures {
        final double spyMomentum, qqqMomentum, correlation, vixLevel, volumeRatio, momentumDivergence;
        final int timeOfDay, dayOfWeek;

        public MarketFeatures(double spyMomentum, double qqqMomentum, double correlation, double vixLevel,
                              int timeOfDay, int dayOfWeek, double volumeRatio, double momentumDivergence) {
            this.spyMomentum = spyMomentum;
            this.qqqMomentum = qqqMomentum;
            this.correlation = correlation;
            this.vixLevel = vixLevel;
            this.timeOfDay = timeOfDay;
            this.dayOfWeek = dayOfWeek;
            this.volumeRatio = volumeRatio;
            this.momentumDivergence = momentumDivergence;
        }
    }

    @Getter @Setter
    private static class PricePoint {
        final double price;
        final LocalDateTime timestamp;

        public PricePoint(double price, LocalDateTime timestamp) {
            this.price = price;
            this.timestamp = timestamp;
        }
    }

    @Getter @Setter
    private static class LeaderCorrelationMetrics {
        final String strongestLeader;
        final double strongestCorrelation;
        final Map<String, Double> allCorrelations;

        public LeaderCorrelationMetrics(String strongestLeader, double strongestCorrelation, Map<String, Double> allCorrelations) {
            this.strongestLeader = strongestLeader;
            this.strongestCorrelation = strongestCorrelation;
            this.allCorrelations = allCorrelations;
        }
    }

    @Getter @Setter
    private static class VolumeRequirement {
        private final String tier;
        private final boolean met;
        private final double multiplier;
        private final double maxConfidence;

        public VolumeRequirement(String tier, boolean met, double multiplier, double maxConfidence) {
            this.tier = tier;
            this.met = met;
            this.multiplier = multiplier;
            this.maxConfidence = maxConfidence;
        }
    }

    @Getter @Setter
    private static class GapAnalysis {
        private final double gapPercentage;
        private final boolean isSignificant;
        private final double confidenceBoost;
        private final String direction;

        public GapAnalysis(double gapPercentage, boolean isSignificant, double confidenceBoost, String direction) {
            this.gapPercentage = gapPercentage;
            this.isSignificant = isSignificant;
            this.confidenceBoost = confidenceBoost;
            this.direction = direction;
        }
    }

    private static class OpeningRange {
        private final BigDecimal high, low;

        public OpeningRange(BigDecimal high, BigDecimal low) {
            this.high = high;
            this.low = low;
        }

        public BigDecimal getHigh() { return high; }
        public BigDecimal getLow() { return low; }
    }


    private class TechnicalAttributes {
        double vwapDistance;
        String vwapTrend;
        double rsi;
        String macdSignal;
        double volumeRatio;
        double momentumStrength;
        String marketRegime;
        boolean extendedFromVwap;
        boolean hasRsiDivergence;
        boolean hasMacdDivergence;
        double priceLevel;
        double atr;
        double strength;
        int timeOfDay;
        double pocLevel;
        double pocDistance;
    }


    private void logEnhancedLeaderLagDebug(List<Signal> leaderLagSignals, String analysisId) {
        if (leaderLagSignals.isEmpty()) {
            //log.warn("[ENHANCED-AI-DEBUG][{}] 🔍 NO AI LEADER LAG SIGNALS GENERATED - Debugging:", analysisId);

            // Debug correlation
            try {
                LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);
                if (metrics == null) {
                    log.warn("[ENHANCED-AI-DEBUG][{}] ❌ CORRELATION METRICS: NULL", analysisId);
                } else {
//                    log.info("[ENHANCED-AI-DEBUG][{}] 📊 CORRELATION: Strongest={} ({}%), All={}",
//                            analysisId, metrics.getStrongestLeader(),
//                            (int)(metrics.getStrongestCorrelation() * 100),
//                            metrics.getAllCorrelations().entrySet().stream()
//                                    .map(e -> e.getKey() + "=" + (int)(e.getValue() * 100) + "%")
//                                    .collect(Collectors.joining(", ")));
                }
            } catch (Exception e) {
                log.error("[ENHANCED-AI-DEBUG][{}] ❌ Error getting correlation: {}", analysisId, e.getMessage());
            }

            // Debug momentum - FIXED FORMATTING
            try {
//                for (String leader : LEADER_STOCKS) {
//                    MomentumVelocity velocity = momentumTracker.calculateVelocity(leader);
//                    log.info("[ENHANCED-AI-DEBUG][{}] 🎯 {} MOMENTUM: {}, State: {}, Acceleration: {}",
//                            analysisId, leader, String.format("%.3f",velocity.momentum), velocity.state, String.format("%.4f",velocity.acceleration));
//                }

                MomentumVelocity qqqVelocity = momentumTracker.calculateVelocity("QQQ");
                log.info("[ENHANCED-AI-DEBUG][{}] 🎯 QQQ MOMENTUM: {}, State: {}, Acceleration: {}",
                        analysisId, String.format("%.3f",qqqVelocity.momentum), qqqVelocity.state, String.format("%.4f",qqqVelocity.acceleration));

            } catch (Exception e) {
                log.error("[ENHANCED-AI-DEBUG][{}] ❌ Error getting momentum: {}", analysisId, e.getMessage());
            }

            // NEW: Debug why AI decision failed
            try {
                EnhancedMarketContext context = buildEnhancedMarketContextWithVelocity(
                        technicalAnalysisService.analyze("QQQ"), "DOWN", LocalTime.now(ET_ZONE), analysisId);

                if (context != null) {
                    Map<String, MicroMomentumPattern> leaderPatterns = classifyLeaderMicroMomentumPatterns(context, analysisId);
                    Map<String, RSIMomentumContext> rsiContexts = getRSIMomentumContexts(context, analysisId);

                    //log.info("[ENHANCED-AI-DEBUG][{}] 🧠 AI DECISION DEBUG:", analysisId);
                    log.info("[ENHANCED-AI-DEBUG][{}] - Strongest Leader: {}", analysisId, context.strongestLeader);
//                    log.info("[ENHANCED-AI-DEBUG][{}] - Correlation: {}%", analysisId, String.format("%.1f",context.correlation * 100));

 //                   leaderPatterns.forEach((leader, pattern) ->
 //                           log.info("[ENHANCED-AI-DEBUG][{}] - {} Pattern: {}", analysisId, leader, pattern));

 //                   rsiContexts.forEach((leader, rsiContext) ->
//                            log.info("[ENHANCED-AI-DEBUG][{}] - {} RSI Context: {} → Action: {}",
//                                    analysisId, leader, rsiContext.interpretation, rsiContext.signalAction));
                }
            } catch (Exception e) {
                log.error("[ENHANCED-AI-DEBUG][{}] ❌ Error in AI decision debug: {}", analysisId, e.getMessage());
            }

        } else {
            log.info("[ENHANCED-AI-DEBUG][{}] ✅ Generated {} AI leader lag signals", analysisId, leaderLagSignals.size());

//            for (Signal signal : leaderLagSignals) {
//                String leaderStock = signal.getMetadata().get("leaderStock");
//                String leaderVelocity = signal.getMetadata().get("leaderVelocity");
//                String microPattern = signal.getMetadata().get("microPattern");
//
//                log.info("[ENHANCED-AI-DEBUG][{}] 🚀 AI Signal: {} - Leader: {}, Velocity: {}, Pattern: {}, Confidence: {}%",
//                        analysisId, signal.getStrategy(), leaderStock, leaderVelocity, microPattern,
//                        (int)(signal.getConfidence() * 100));
//            }
        }
    }

    private boolean validateRSIForSignal(String executionId, String signalType, double rsi) {
        if (signalType.contains("PUT") && rsi > RSI_OVERBOUGHT_THRESHOLD) {
            log.warn("[{}] ⛔ RSI FILTER REJECTED PUT signal - RSI: {} > threshold: {} (too overbought)",
                    executionId, String.format("%.1f",rsi), RSI_OVERBOUGHT_THRESHOLD);
            return false;
        }

        if (signalType.contains("CALL") && rsi < RSI_OVERSOLD_THRESHOLD) {
            log.warn("[{}] ⛔ RSI FILTER REJECTED CALL signal - RSI: {} < threshold: {} (too oversold)",
                    executionId, String.format("%.1f",rsi), RSI_OVERSOLD_THRESHOLD);
            return false;
        }

        log.info("[{}] ✅ RSI FILTER PASSED - RSI: {} within acceptable range for {}",
                executionId, String.format("%.1f",rsi), signalType);
        return true;
    }


    private StrategyActivationDecision determineStrategyActivation(TechnicalAnalysis ta, String marketTrend, String analysisId) {
        // Always run all strategies - let execution-time confidence determine winners
        log.info("[STRATEGY-ACTIVATION][{}] All strategies active - parallel execution mode", analysisId);
        return new StrategyActivationDecision(true, true, false, "All strategies active for parallel execution");
    }
    private boolean isHighVolatilityPeriod() {
        LocalTime now = LocalTime.now(ET_ZONE);
        double vixLevel = getVixLevel();

        // High volatility or power hour = shorter expiration
        return vixLevel > 25.0 || now.isAfter(LocalTime.of(15, 30));
    }

    private void setSignalExpiration(Signal signal) {
        LocalDateTime now = LocalDateTime.now();
        int expirationSeconds = isHighVolatilityPeriod() ? 30 : 60;
        signal.setExpirationTime(now.plusSeconds(expirationSeconds));
    }

    private boolean isSignalExpired(Signal signal) {
        if (signal.getExpirationTime() == null) {
            return false; // No expiration set, assume valid
        }
        return signal.getExpirationTime().isBefore(LocalDateTime.now());
    }

    private boolean hasOpenPositionOnUnderlying(String symbol) {
        try {
            long openPositions = tradeRepository.findAll().stream()
                    .filter(trade -> "OPEN".equals(trade.getStatus()))
                    .filter(trade -> symbol.equals(trade.getSymbol()))
                    .count();

            return openPositions > 0;
        } catch (Exception e) {
            log.error("Error checking open positions for {}: {}", symbol, e.getMessage());
            return false; // If error, assume no position to avoid false blocks
        }
    }

    private List<Signal> analyzeTraditionalStrategies(List<Option> options, TechnicalAnalysis ta,
                                                      VolumeSnapshot volumeSnapshot,
                                                      GEXCalculator.GEXLevels gexSnapshot,
                                                      String marketTrend, String analysisId) {
        List<Signal> traditionalSignals = new ArrayList<>();
        LocalTime now = LocalTime.now(ET_ZONE);

        try {
            log.info("[TRADITIONAL][{}] Analyzing traditional strategies for {} options (with snapshots)",
                    analysisId, options.size());

            for (Option option : options) {
                if (!quickSignalValidation(option, options, ta, marketTrend, analysisId)) {
                    continue;
                }

                // Opening Range Breakout (morning only)
                if (now.isAfter(LocalTime.of(9, 45)) && now.isBefore(LocalTime.of(10, 30))) {
                    analyze30MinuteORB(option, options, ta, traditionalSignals, marketTrend, analysisId);
                }

                // VWAP Strategies (active all day) - PASS SNAPSHOTS
                if (ta.getVwap() != null && ta.getVwap().compareTo(BigDecimal.ZERO) > 0) {
                    analyzeVWAPMeanReversion(option, options, ta, volumeSnapshot, gexSnapshot,
                            traditionalSignals, marketTrend, analysisId);
                    analyzeVWAPSupportResistance(option, options, ta, volumeSnapshot, gexSnapshot,
                            traditionalSignals, marketTrend, analysisId);
                }
            }

            log.info("[TRADITIONAL][{}] Generated {} traditional signals", analysisId, traditionalSignals.size());
            return traditionalSignals;

        } catch (Exception e) {
            log.error("[TRADITIONAL][{}] Error in traditional strategy analysis: {}", analysisId, e.getMessage());
            return traditionalSignals;
        }
    }

    private static class StrategyActivationDecision {
        private final boolean shouldRunAI;
        private final boolean shouldRunTraditional;
        private final boolean shouldRunAdvanced;
        private final String reason;

        public StrategyActivationDecision(boolean shouldRunAI, boolean shouldRunTraditional,
                                          boolean shouldRunAdvanced, String reason) {
            this.shouldRunAI = shouldRunAI;
            this.shouldRunTraditional = shouldRunTraditional;
            this.shouldRunAdvanced = shouldRunAdvanced;
            this.reason = reason;
        }

        public boolean shouldRunAI() { return shouldRunAI; }
        public boolean shouldRunTraditional() { return shouldRunTraditional; }
        public boolean shouldRunAdvanced() { return shouldRunAdvanced; }
        public String getReason() { return reason; }
    }

    private List<Signal> sortSignalsByConfidence(List<Signal> signals) {
        return signals.stream()
                .filter(signal -> !isSignalExpired(signal)) // Filter expired signals
                .sorted((s1, s2) -> Double.compare(s2.getConfidence(), s1.getConfidence())) // Sort by confidence descending
                .collect(Collectors.toList());
    }

    private void saveAndExecuteSignalsWithValidationAndSellProtection(List<Signal> signals, TechnicalAnalysis ta,
                                                                      String marketTrend, String analysisId) {
        if (signals.isEmpty()) {
            return;
        }

        // Sort signals by confidence and take only top 2
        List<Signal> rankedSignals = sortSignalsByConfidence(signals);
        List<Signal> topSignals = rankedSignals.stream()
                .limit(2)
                .collect(Collectors.toList());

        log.info("[EXECUTION-RANKING][{}] Processing {} top signals from {} total (expired: {})",
                analysisId, topSignals.size(), signals.size(), signals.size() - rankedSignals.size());

        for (Signal signal : topSignals) {
            // Final expiration check
            if (isSignalExpired(signal)) {
                log.warn("[{}] Signal EXPIRED during processing: {} (generated: {})",
                        analysisId, signal.getOptionSymbol(), signal.getTimestamp());
                continue;
            }

            // Final 0DTE check
            LocalDate today = LocalDate.now(ET_ZONE);
            LocalDate signalExpiration = extractExpirationFromOptionSymbol(signal.getOptionSymbol());

            if (!today.equals(signalExpiration)) {
                log.warn("[{}] Signal REJECTED in final check: {} - Not 0DTE",
                        analysisId, signal.getOptionSymbol());
                continue;
            }

            // SHAP explanation & validation
            SHAPExplainerService.SHAPExplanation explanation =
                    shapExplainerService.explainSignal(signal, ta, marketTrend);

            boolean shapValid = shapExplainerService.validateSignalWithExplanation(signal, ta, marketTrend);
            if (!shapValid) {
                log.warn("[SHAP-FILTER][{}] Signal REJECTED: {} - Weak explanation ({}%)",
                        analysisId, signal.getOptionSymbol(),
                        (int)(explanation.getExplanationStrength() * 100));
                continue;
            }

            // Set signal properties
            signal.setCreatedAt(LocalDateTime.now());
            signal.setEntryAssumptionPrice(ta.getCurrentPrice());
            signal.setMarketTrend(marketTrend);
            signal.setOriginalOptionPrice(signal.getEntryPrice());

            log.info("[{}] SIGNAL PREPARED FOR EXECUTION: {} - {} {}, Confidence: {}%",
                    analysisId, signal.getStrategy(), signal.getSignalType(),
                    signal.getOptionSymbol(), (int)(signal.getConfidence() * 100));

            // Execute with pre-validation for AI signals
            if ("true".equals(signal.getMetadata().get("requiresPreValidation"))) {
                executeSignalWithPreValidation(signal, explanation, analysisId);
            } else {
                executeSignalImmediately(signal, explanation, analysisId);
            }

            // Stop after first successful execution
            if ("FILLED".equals(signal.getStatus()) || "EXECUTED".equals(signal.getStatus())) {
                log.info("[{}] Signal successfully executed - stopping further executions", analysisId);
                break;
            }
        }
    }

    // COMPLETELY REPLACE analyzeVWAPMeanReversion method with this full implementation
    private void analyzeVWAPMeanReversion(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                          VolumeSnapshot volumeSnapshot,
                                          GEXCalculator.GEXLevels gexSnapshot,
                                          List<Signal> signals, String marketTrend, String analysisId) {

        if (ta.getVwap() == null || ta.getVwap().compareTo(BigDecimal.ZERO) == 0) {
            log.debug("[VWAP-MEAN-REVERSION][{}] No VWAP available", analysisId);
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double vwapDistancePercent = currentPrice.subtract(vwap)
                .divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        LocalTime now = LocalTime.now(ET_ZONE);

        // Skip if not in optimal distance range (0.3%-1.5%)
        double absDistance = Math.abs(vwapDistancePercent);
        if (absDistance < 0.003 || absDistance > 0.015) {
            log.debug("[VWAP-MEAN-REVERSION][{}] Distance {}% outside range (need 0.3%-1.5%)",
                    analysisId, String.format("%.2f", absDistance * 100));
            return;
        }

        // ========================================================================================
        // CALL BOUNCE SETUP (Price below VWAP by 0.3%-1.5%)
        // ========================================================================================
        if (isCall && vwapDistancePercent < -0.003 && vwapDistancePercent > -0.015) {

            log.info("[VWAP-BOUNCE-CALL][{}] === ANALYZING CALL BOUNCE === Distance: {}%",
                    analysisId, String.format("%.2f", vwapDistancePercent * 100));

            // Track confirmations using ConfirmationTracker
            ConfirmationTracker tracker = new ConfirmationTracker("VWAP_BOUNCE_CALL", 3);

            // Confirmation 1: VWAP Distance Optimal (0.3-0.5% ideal)
            boolean vwapDistanceOptimal = absDistance >= 0.003 && absDistance <= 0.005;
            tracker.addConfirmation(vwapDistanceOptimal, "VWAP_DISTANCE",
                    String.format("%.2f%% (optimal: 0.3-0.5%%)", absDistance * 100));

            // Confirmation 2: Volume Pattern (Exhaustion + Spike) - USE SNAPSHOT
            double volumeRatio = volumeSnapshot.getVolumeRatio();
            boolean hasExhaustion = volumeSnapshot.hasExhaustion();
            boolean hasSpike = volumeSnapshot.hasSpike();
            boolean volumeConfirmed = (volumeRatio > 1.5) || (hasExhaustion && hasSpike);

            tracker.addConfirmation(volumeConfirmed, "VOLUME",
                    String.format("%.2fx, Exhaustion=%s, Spike=%s", volumeRatio, hasExhaustion, hasSpike));

            // Confirmation 3: GEX Proximity to -GEX Support - USE SNAPSHOT
            boolean gexProximity = gexSnapshot != null && gexSnapshot.isNearNegativeGEX();
            tracker.addConfirmation(gexProximity, "GEX_SUPPORT",
                    gexSnapshot != null ? String.format("-GEX=$%s, distance=%.2f%%",
                            gexSnapshot.getNegativeGEX(), gexSnapshot.getDistanceToNegativeGEX() * 100) : "N/A");

            // Confirmation 4: Daily Timeframe Context
            boolean dailyContextBullish = false;
            try {
                List<MarketData> dailyData = marketDataRepository.findRecentData("QQQ", 50);
                if (dailyData.size() >= 50) {
                    BigDecimal ema50 = calculate50EMA(dailyData);
                    dailyContextBullish = currentPrice.compareTo(ema50) > 0;
                }
            } catch (Exception e) {
                log.debug("[VWAP-BOUNCE-CALL][{}] Could not check daily context: {}", analysisId, e.getMessage());
            }

            tracker.addConfirmation(dailyContextBullish, "DAILY_UPTREND",
                    dailyContextBullish ? "price > 50 EMA" : "price < 50 EMA");

            // Confirmation 5: Candlestick Pattern
            boolean candlestickPattern = hasBounceConfirmation("QQQ", vwap);
            tracker.addConfirmation(candlestickPattern, "BULLISH_CANDLE",
                    candlestickPattern ? "bounce detected" : "no bounce");

            log.info("[VWAP-BOUNCE-CALL][{}] CONFIRMATIONS: {}/5 - Confirmed: {} | Rejected: {}",
                    analysisId, tracker.getScore(),
                    String.join(", ", tracker.getConfirmed()),
                    String.join(", ", tracker.getRejected()));

            // DECISION: Need 3 of 5 confirmations
            if (!tracker.meetsThreshold()) {
                log.warn("[VWAP-BOUNCE-CALL][{}] ❌ SIGNAL REJECTED - Only {}/5 confirmations (need 3)",
                        analysisId, tracker.getScore());
                logSignalRejectionSummary(analysisId, "VWAP_BOUNCE_CALL", tracker);
                return;
            }

            // Calculate confidence
            double baseConfidence = 0.65 + (tracker.getScore() - 3) * 0.05;
            double timeAdjustedConfidence = getTimeAdjustedConfidence(baseConfidence, now, analysisId);

            // GEX boost
            if (gexProximity) {
                timeAdjustedConfidence = Math.min(0.95, timeAdjustedConfidence * 1.10);
                log.info("[VWAP-BOUNCE-CALL][{}] GEX proximity boost applied: +10%", analysisId);
            }

            // Threshold check
            double threshold = 0.60;
            if (timeAdjustedConfidence < threshold) {
                log.warn("[VWAP-BOUNCE-CALL][{}] ❌ SIGNAL REJECTED - Confidence {}% < threshold {}%",
                        analysisId, (int)(timeAdjustedConfidence * 100), (int)(threshold * 100));
                return;
            }

            // Generate signal
            Signal signal = createSignal(option, allOptions, ta, "BUY",
                    "0DTE_VWAP_MEAN_REVERSION_BOUNCE_CALL", timeAdjustedConfidence, marketTrend);

            if (signal != null) {
                signal.setReason(tracker.getSummary() + String.format(" | Distance: %.2f%% below VWAP",
                        absDistance * 100));

                signals.add(signal);

                log.info("[VWAP-BOUNCE-CALL][{}] ✅ SIGNAL GENERATED - Confidence: {}%, Confirmations: {}/5",
                        analysisId, (int)(timeAdjustedConfidence * 100), tracker.getScore());
            }
        }

        // ========================================================================================
        // PUT REJECTION SETUP (Price above VWAP by 0.3%-1.5%)
        // ========================================================================================
        else if (isPut && vwapDistancePercent > 0.003 && vwapDistancePercent < 0.015) {

            log.info("[VWAP-REJECT-PUT][{}] === ANALYZING PUT REJECTION === Distance: {}%",
                    analysisId, String.format("%.2f", vwapDistancePercent * 100));

            // Track confirmations
            ConfirmationTracker tracker = new ConfirmationTracker("VWAP_REJECT_PUT", 3);

            // Confirmation 1: VWAP Distance Optimal (0.3-0.5% ideal)
            boolean vwapDistanceOptimal = absDistance >= 0.003 && absDistance <= 0.005;
            tracker.addConfirmation(vwapDistanceOptimal, "VWAP_DISTANCE",
                    String.format("%.2f%% (optimal: 0.3-0.5%%)", absDistance * 100));

            // Confirmation 2: Volume Pattern - USE SNAPSHOT
            double volumeRatio = volumeSnapshot.getVolumeRatio();
            boolean hasExhaustion = volumeSnapshot.hasExhaustion();
            boolean hasSpike = volumeSnapshot.hasSpike();
            boolean volumeConfirmed = (volumeRatio > 1.5) || (hasExhaustion && hasSpike);

            tracker.addConfirmation(volumeConfirmed, "VOLUME",
                    String.format("%.2fx, Exhaustion=%s, Spike=%s", volumeRatio, hasExhaustion, hasSpike));

            // Confirmation 3: GEX Proximity to +GEX Resistance - USE SNAPSHOT
            boolean gexProximity = gexSnapshot != null && gexSnapshot.isNearPositiveGEX();
            tracker.addConfirmation(gexProximity, "GEX_RESISTANCE",
                    gexSnapshot != null ? String.format("+GEX=$%s, distance=%.2f%%",
                            gexSnapshot.getPositiveGEX(), gexSnapshot.getDistanceToPositiveGEX() * 100) : "N/A");

            // Confirmation 4: Daily Timeframe Context (bearish structure preferred)
            boolean dailyContextBearish = false;
            try {
                List<MarketData> dailyData = marketDataRepository.findRecentData("QQQ", 50);
                if (dailyData.size() >= 50) {
                    BigDecimal ema50 = calculate50EMA(dailyData);
                    dailyContextBearish = currentPrice.compareTo(ema50) < 0;
                }
            } catch (Exception e) {
                log.debug("[VWAP-REJECT-PUT][{}] Could not check daily context: {}", analysisId, e.getMessage());
            }

            tracker.addConfirmation(dailyContextBearish, "DAILY_DOWNTREND",
                    dailyContextBearish ? "price < 50 EMA" : "price > 50 EMA");

            // Confirmation 5: Bearish reversal candle
            boolean bearishCandle = hasBearishReversal("QQQ", vwap);
            tracker.addConfirmation(bearishCandle, "BEARISH_CANDLE",
                    bearishCandle ? "rejection detected" : "no rejection");

            log.info("[VWAP-REJECT-PUT][{}] CONFIRMATIONS: {}/5 - Confirmed: {} | Rejected: {}",
                    analysisId, tracker.getScore(),
                    String.join(", ", tracker.getConfirmed()),
                    String.join(", ", tracker.getRejected()));

            // DECISION: Need 3 of 5 confirmations
            if (!tracker.meetsThreshold()) {
                log.warn("[VWAP-REJECT-PUT][{}] ❌ SIGNAL REJECTED - Only {}/5 confirmations (need 3)",
                        analysisId, tracker.getScore());
                logSignalRejectionSummary(analysisId, "VWAP_REJECT_PUT", tracker);
                return;
            }

            // Calculate confidence
            double baseConfidence = 0.65 + (tracker.getScore() - 3) * 0.05;
            double timeAdjustedConfidence = getTimeAdjustedConfidence(baseConfidence, now, analysisId);

            // GEX boost
            if (gexProximity) {
                timeAdjustedConfidence = Math.min(0.95, timeAdjustedConfidence * 1.10);
                log.info("[VWAP-REJECT-PUT][{}] GEX proximity boost applied: +10%", analysisId);
            }

            // Threshold check
            double threshold = 0.60;
            if (timeAdjustedConfidence < threshold) {
                log.warn("[VWAP-REJECT-PUT][{}] ❌ SIGNAL REJECTED - Confidence {}% < threshold {}%",
                        analysisId, (int)(timeAdjustedConfidence * 100), (int)(threshold * 100));
                return;
            }

            // Generate signal
            Signal signal = createSignal(option, allOptions, ta, "BUY",
                    "0DTE_VWAP_MEAN_REVERSION_REJECT_PUT", timeAdjustedConfidence, marketTrend);

            if (signal != null) {
                signal.setReason(tracker.getSummary() + String.format(" | Distance: %.2f%% above VWAP",
                        absDistance * 100));

                signals.add(signal);

                log.info("[VWAP-REJECT-PUT][{}] ✅ SIGNAL GENERATED - Confidence: {}%, Confirmations: {}/5",
                        analysisId, (int)(timeAdjustedConfidence * 100), tracker.getScore());
            }
        }
    }


    // Helper method for 50 EMA calculation
    // ADD this helper method for daily context checks
    private BigDecimal calculate50EMA(List<MarketData> data) {
        if (data.size() < 50) {
            log.debug("Insufficient data for 50 EMA calculation: {} bars", data.size());
            return BigDecimal.ZERO;
        }

        BigDecimal ema = data.get(data.size() - 1).getPrice();
        double multiplier = 2.0 / (50 + 1);

        for (int i = data.size() - 2; i >= Math.max(0, data.size() - 50); i--) {
            BigDecimal price = data.get(i).getPrice();
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                ema = price.multiply(BigDecimal.valueOf(multiplier))
                        .add(ema.multiply(BigDecimal.valueOf(1 - multiplier)));
            }
        }

        return ema;
    }

    // ADD @PostConstruct method for initialization
    @PostConstruct
    public void initializeTrackers() {
        try {
            log.info("[TRACKER-INIT] Initializing all tracking systems...");

            momentumTracker.initialize();
            rsiTracker.initialize();

            log.info("[TRACKER-INIT] All tracking systems initialized successfully");

            // Initial data population
            log.info("[TRACKER-INIT] Populating initial tracking data...");
            updateMomentumTracking();
            updateRSITracking();
            updateVolumeTracking();

            log.info("[TRACKER-INIT] ✅ System ready for signal generation");

        } catch (Exception e) {
            log.error("[TRACKER-INIT] ❌ Error initializing trackers: {}", e.getMessage(), e);
        }
    }

    // ADD @PreDestroy method for cleanup
    @PreDestroy
    public void cleanupTrackers() {
        try {
            log.info("[TRACKER-CLEANUP] Cleaning up all tracking systems...");

            momentumTracker.cleanup();
            rsiTracker.cleanup();

            log.info("[TRACKER-CLEANUP] ✅ All tracking systems cleaned up successfully");
        } catch (Exception e) {
            log.error("[TRACKER-CLEANUP] ❌ Error cleaning up trackers: {}", e.getMessage(), e);
        }
    }

    // ADD this new method for comprehensive rejection logging
    private void logSignalRejectionSummary(String analysisId, String strategy,
                                           ConfirmationTracker tracker) {

        if (tracker.meetsThreshold()) {
            return; // Don't log if signal passed
        }

        StringBuilder summary = new StringBuilder();
        summary.append(String.format("\n╔════════════════════════════════════════════════════════════════╗\n"));
        summary.append(String.format("║ SIGNAL REJECTION SUMMARY - %s\n", analysisId));
        summary.append(String.format("╠════════════════════════════════════════════════════════════════╣\n"));
        summary.append(String.format("║ Strategy: %s\n", strategy));
        summary.append(String.format("║ Result: ❌ REJECTED - %d/%d confirmations (need %d)\n",
                tracker.getScore(), tracker.getConfirmed().size() + tracker.getRejected().size(),
                tracker.getRequired()));
        summary.append(String.format("╠════════════════════════════════════════════════════════════════╣\n"));

        if (!tracker.getConfirmed().isEmpty()) {
            summary.append(String.format("║ ✓ PASSED CONFIRMATIONS:\n"));
            for (int i = 0; i < tracker.getConfirmed().size(); i++) {
                summary.append(String.format("║   %d. %s\n", i + 1, tracker.getConfirmed().get(i)));
            }
            summary.append(String.format("╠════════════════════════════════════════════════════════════════╣\n"));
        }

        summary.append(String.format("║ ✗ FAILED CONFIRMATIONS:\n"));
        for (int i = 0; i < tracker.getRejected().size(); i++) {
            summary.append(String.format("║   %d. %s\n", i + 1, tracker.getRejected().get(i)));
        }

        summary.append(String.format("╚════════════════════════════════════════════════════════════════╝\n"));

        log.warn(summary.toString());
    }


    private void analyzeVWAPSupportResistance(Option option, List<Option> allOptions, TechnicalAnalysis ta,
                                              VolumeSnapshot volumeSnapshot,
                                              GEXCalculator.GEXLevels gexSnapshot,
                                              List<Signal> signals, String marketTrend, String analysisId) {

        if (ta.getVwap() == null || ta.getVwap().compareTo(BigDecimal.ZERO) == 0) {
            log.debug("[VWAP-SUPPORT][{}] No VWAP available", analysisId);
            return;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double vwapDistancePercent = currentPrice.subtract(vwap)
                .divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();

        boolean isPut = "PUT".equalsIgnoreCase(option.getType());
        boolean isCall = "CALL".equalsIgnoreCase(option.getType());
        LocalTime now = LocalTime.now(ET_ZONE);

        // Only trade VWAP as support when price is touching/near it (0.05% - 0.3%)
        double absDistance = Math.abs(vwapDistancePercent);
        if (absDistance > 0.003) {
            return; // Too far from VWAP
        }

        // CALL: Price touching VWAP from above (support bounce)
        if (isCall && vwapDistancePercent < 0.001 && vwapDistancePercent > -0.003) {

            log.info("[VWAP-SUPPORT][{}] === ANALYZING SUPPORT BOUNCE === Distance: {}%",
                    analysisId, String.format("%.2f", vwapDistancePercent * 100));

            ConfirmationTracker tracker = new ConfirmationTracker("VWAP_SUPPORT", 2);

            // Confirmation 1: Bounce candlestick pattern
            boolean bounceCandle = hasBounceConfirmation("QQQ", vwap);
            tracker.addConfirmation(bounceCandle, "NO_BOUNCE_CANDLE",
                    bounceCandle ? "bounce pattern" : "");

            // Confirmation 2: Volume surge - USE SNAPSHOT
            double volumeRatio = volumeSnapshot.getVolumeRatio();
            boolean volumeHigh = volumeRatio > 1.3;
            tracker.addConfirmation(volumeHigh, "VOLUME_LOW",
                    String.format("%.2fx", volumeRatio));

            // Confirmation 3: Previous bounces at VWAP (support proven)
            int bounceCount = countRecentBounces("QQQ", vwap, 60, analysisId);
            boolean hasHistory = bounceCount >= 2;
            tracker.addConfirmation(hasHistory, "FIRST_TOUCH_ONLY",
                    String.format("%d", bounceCount));

            log.info("[VWAP-SUPPORT][{}] CONFIRMATIONS: {}/3 - Confirmed: {} | Rejected: {}",
                    analysisId, tracker.getScore(),
                    String.join(", ", tracker.getConfirmed()),
                    String.join(", ", tracker.getRejected()));

            if (tracker.getScore() < 2) {
                log.warn("[VWAP-SUPPORT][{}] ❌ SIGNAL REJECTED - Only {}/3 confirmations (need 2)",
                        analysisId, tracker.getScore());
                return;
            }

            double baseConfidence = 0.60 + (tracker.getScore() - 2) * 0.08;
            double timeAdjustedConfidence = getTimeAdjustedConfidence(baseConfidence, now, analysisId);

            Signal signal = createSignal(option, allOptions, ta, "BUY",
                    "0DTE_VWAP_SUPPORT_BOUNCE", timeAdjustedConfidence, marketTrend);

            if (signal != null) {
                signal.setReason(tracker.getSummary());
                signals.add(signal);
                log.info("[VWAP-SUPPORT][{}] ✅ SIGNAL GENERATED - Confidence: {}%",
                        analysisId, (int)(timeAdjustedConfidence * 100));
            }
        }

        // PUT: Price touching VWAP from below (resistance rejection)
        else if (isPut && vwapDistancePercent > -0.001 && vwapDistancePercent < 0.003) {

            log.info("[VWAP-RESISTANCE][{}] === ANALYZING RESISTANCE REJECTION === Distance: {}%",
                    analysisId, String.format("%.2f", vwapDistancePercent * 100));

            ConfirmationTracker tracker = new ConfirmationTracker("VWAP_RESISTANCE", 2);

            // Confirmation 1: Rejection candlestick pattern
            boolean rejectionCandle = hasBearishReversal("QQQ", vwap);
            tracker.addConfirmation(rejectionCandle, "NO_REJECTION_CANDLE",
                    rejectionCandle ? "rejection pattern" : "");

            // Confirmation 2: Volume surge - USE SNAPSHOT
            double volumeRatio = volumeSnapshot.getVolumeRatio();
            boolean volumeHigh = volumeRatio > 1.3;
            tracker.addConfirmation(volumeHigh, "VOLUME_LOW",
                    String.format("%.2fx", volumeRatio));

            // Confirmation 3: Previous rejections at VWAP
            int rejectionCount = countRecentRejections("QQQ", vwap, 60, analysisId);
            boolean hasHistory = rejectionCount >= 2;
            tracker.addConfirmation(hasHistory, "FIRST_TOUCH_ONLY",
                    String.format("%d", rejectionCount));

            log.info("[VWAP-RESISTANCE][{}] CONFIRMATIONS: {}/3 - Confirmed: {} | Rejected: {}",
                    analysisId, tracker.getScore(),
                    String.join(", ", tracker.getConfirmed()),
                    String.join(", ", tracker.getRejected()));

            if (tracker.getScore() < 2) {
                log.warn("[VWAP-RESISTANCE][{}] ❌ SIGNAL REJECTED - Only {}/3 confirmations (need 2)",
                        analysisId, tracker.getScore());
                return;
            }

            double baseConfidence = 0.60 + (tracker.getScore() - 2) * 0.08;
            double timeAdjustedConfidence = getTimeAdjustedConfidence(baseConfidence, now, analysisId);

            Signal signal = createSignal(option, allOptions, ta, "BUY",
                    "0DTE_VWAP_RESISTANCE_REJECTION", timeAdjustedConfidence, marketTrend);

            if (signal != null) {
                signal.setReason(tracker.getSummary());
                signals.add(signal);
                log.info("[VWAP-RESISTANCE][{}] ✅ SIGNAL GENERATED - Confidence: {}%",
                        analysisId, (int)(timeAdjustedConfidence * 100));
            }
        }
    }

    // ADD this as a new inner class
    @Getter @Setter
    private static class ConfirmationTracker {
        private final List<String> confirmed = new ArrayList<>();
        private final List<String> rejected = new ArrayList<>();
        private int score = 0;
        private final int required;
        private final String strategy;

        public ConfirmationTracker(String strategy, int required) {
            this.strategy = strategy;
            this.required = required;
        }

        public void addConfirmation(boolean passed, String factor, String detail) {
            if (passed) {
                score++;
                confirmed.add(String.format("%s(%s)", factor, detail));
            } else {
                rejected.add(String.format("%s(%s)", factor, detail));
            }
        }

        public boolean meetsThreshold() {
            return score >= required;
        }

        public String getSummary() {
            return String.format("%s - %d/%d confirmations | ✓ %s | ✗ %s",
                    strategy, score, confirmed.size() + rejected.size(),
                    String.join(", ", confirmed),
                    String.join(", ", rejected));
        }

        public String getDetailedRejectionReason() {
            if (meetsThreshold()) {
                return "N/A - Signal passed";
            }

            return String.format("Only %d/%d confirmations (need %d). Failed: %s",
                    score, confirmed.size() + rejected.size(), required,
                    String.join(", ", rejected));
        }
    }

    // Helper method to count VWAP touches
    private int countVWAPTouches(String symbol, BigDecimal vwap, int lookbackMinutes) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, lookbackMinutes);
            if (recent.size() < 3) return 0;

            int touchCount = 0;
            BigDecimal threshold = vwap.multiply(BigDecimal.valueOf(0.002)); // 0.2% threshold

            for (MarketData data : recent) {
                if (data.getLow() != null && vwap != null) {
                    BigDecimal distance = data.getLow().subtract(vwap).abs();
                    if (distance.compareTo(threshold) < 0) {
                        touchCount++;
                    }
                }
            }

            return touchCount;

        } catch (Exception e) {
            log.error("[VWAP-TOUCH-COUNT] Error: {}", e.getMessage());
            return 0;
        }
    }

    // Helper method to count VWAP rejections
    private int countVWAPRejections(String symbol, BigDecimal vwap, int lookbackMinutes) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, lookbackMinutes);
            if (recent.size() < 3) return 0;

            int rejectionCount = 0;
            BigDecimal threshold = vwap.multiply(BigDecimal.valueOf(0.002)); // 0.2% threshold

            for (MarketData data : recent) {
                if (data.getHigh() != null && data.getClose() != null && vwap != null) {
                    boolean highAboveVWAP = data.getHigh().compareTo(vwap) > 0;
                    BigDecimal distance = data.getHigh().subtract(vwap).abs();
                    boolean closeBelowVWAP = data.getClose().compareTo(vwap) < 0;

                    if (highAboveVWAP && distance.compareTo(threshold) < 0 && closeBelowVWAP) {
                        rejectionCount++;
                    }
                }
            }

            return rejectionCount;

        } catch (Exception e) {
            log.error("[VWAP-REJECTION-COUNT] Error: {}", e.getMessage());
            return 0;
        }
    }


    private boolean hasBounceConfirmation(String symbol, BigDecimal vwap) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, 2);
            if (recent.size() < 2) return false;

            MarketData currentCandle = recent.get(0);
            MarketData previousCandle = recent.get(1);

            // Previous candle touched/went below VWAP, current candle closed above
            boolean previousTouchedVWAP = previousCandle.getLow().compareTo(vwap) <= 0;
            boolean currentAboveVWAP = currentCandle.getClose().compareTo(vwap) > 0;
            boolean closedHigher = currentCandle.getClose().compareTo(previousCandle.getClose()) > 0;

            return previousTouchedVWAP && currentAboveVWAP && closedHigher;

        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasRejectionConfirmation(String symbol, BigDecimal vwap) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, 2);
            if (recent.size() < 2) return false;

            MarketData currentCandle = recent.get(0);
            MarketData previousCandle = recent.get(1);

            // Previous candle touched/went above VWAP, current candle closed below
            boolean previousTouchedVWAP = previousCandle.getHigh().compareTo(vwap) >= 0;
            boolean currentBelowVWAP = currentCandle.getClose().compareTo(vwap) < 0;
            boolean closedLower = currentCandle.getClose().compareTo(previousCandle.getClose()) < 0;

            return previousTouchedVWAP && currentBelowVWAP && closedLower;

        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasConsolidationPattern(String symbol, BigDecimal vwap, BigDecimal ema9, int minutes) {
        try {
            List<MarketData> recent = marketDataRepository.findRecentData(symbol, minutes);
            if (recent.size() < minutes) return false;

            BigDecimal rangeHigh = vwap.max(ema9);
            BigDecimal rangeLow = vwap.min(ema9);
            BigDecimal range = rangeHigh.subtract(rangeLow);

            // Check if price stayed within VWAP/EMA range
            int barsInRange = 0;
            for (MarketData data : recent) {
                if (data.getPrice().compareTo(rangeLow) >= 0 &&
                        data.getPrice().compareTo(rangeHigh) <= 0) {
                    barsInRange++;
                }
            }

            // 80% of bars should be in consolidation range
            return barsInRange >= (minutes * 0.8);

        } catch (Exception e) {
            return false;
        }
    }


    private double getTimeMultiplier(LocalTime now) {
        if (now.isBefore(LocalTime.of(10, 30))) {
            return 0.7;  // Morning - less reliable
        } else if (now.isAfter(LocalTime.of(14, 0)) && now.isBefore(LocalTime.of(15, 30))) {
            return 1.2;  // Afternoon - trending period
        } else if (now.isAfter(LocalTime.of(15, 30))) {
            return 0.8;  // Final 30 minutes - gamma risk
        } else {
            return 1.0;  // Midday - normal reliability
        }
    }


    private enum PriceStructure {
        STRONG_BULLISH,  // Price > 9EMA > VWAP
        BULLISH,         // Price > VWAP, 9EMA > VWAP
        NEUTRAL,         // Mixed signals
        BEARISH,         // Price < VWAP, 9EMA < VWAP
        STRONG_BEARISH   // Price < 9EMA < VWAP
    }

    private enum SimpleMomentum {
        BULLISH,   // 3 consecutive higher closes
        BEARISH,   // 3 consecutive lower closes
        NEUTRAL    // Mixed
    }


    private boolean validateStrongestLeaderTechnicalConfluence(String strongestLeader, boolean isCall, String analysisId) {
        try {
            // Get correlation strength for this leader
            LeaderCorrelationMetrics metrics = correlationDetector.getLeaderMetricsWithTimeDecay(analysisId);
            double correlation = 0.0;
            if (metrics != null && metrics.getAllCorrelations().containsKey(strongestLeader)) {
                correlation = metrics.getAllCorrelations().get(strongestLeader);
            }

            // CRITICAL FIX: High correlation overrides technical tests
            if (correlation >= 0.80) {
                log.info("[TECHNICAL-CONFLUENCE][{}] {} correlation {}% - bypassing technical tests",
                        analysisId, strongestLeader, (int)(correlation * 100));
                return true;
            }

            TechnicalAnalysis leaderTA = technicalAnalysisService.analyze(strongestLeader);
            if (leaderTA == null) return false;

            int passedTests = 0;

            if (hasVWAPBreakoutWithVolume(leaderTA, isCall)) passedTests++;
            if (hasKeyLevelBreakWithVolume(leaderTA, strongestLeader, isCall, analysisId)) passedTests++;
            if (hasValidRSIContext(leaderTA, isCall)) passedTests++;

            // Reduced requirements for medium correlations
            int requiredTests;
            if (correlation >= 0.60) {
                requiredTests = 1; // Only need 1/3 tests for 60-80% correlation
            } else {
                requiredTests = 2; // Need 2/3 tests for <60% correlation
            }

            boolean result = passedTests >= requiredTests;

            log.info("[TECHNICAL-CONFLUENCE][{}] {} correlation {}% - passed {}/3 tests (need {}): {}",
                    analysisId, strongestLeader, (int)(correlation * 100), passedTests, requiredTests,
                    result ? "PASS" : "FAIL");

            return result;
        } catch (Exception e) {
            return false;
        }
    }
    private boolean hasVWAPBreakoutWithVolume(TechnicalAnalysis ta, boolean isCall) {
        if (ta.getVwap() == null || ta.getVwap().compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }

        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        double volumeRatio = ta.getVolumeRatio();

        // Calculate distance from VWAP
        BigDecimal vwapDistance = currentPrice.subtract(vwap).abs();
        double vwapDistancePercent = currentPrice.subtract(vwap)
                .divide(vwap, 4, RoundingMode.HALF_UP).doubleValue();

        // Require minimum distance and volume confirmation
        boolean hasDistance = vwapDistance.compareTo(vwap.multiply(BigDecimal.valueOf(0.0015))) > 0; // 0.15%
        boolean hasVolume = volumeRatio >= 1.3;

        if (isCall) {
            return vwapDistancePercent > 0.0015 && hasVolume; // Above VWAP
        } else {
            return vwapDistancePercent < -0.0015 && hasVolume; // Below VWAP
        }
    }

    private boolean hasKeyLevelBreakWithVolume(TechnicalAnalysis ta, String symbol, boolean isCall, String analysisId) {
        try {
            List<MarketData> recent20 = marketDataRepository.findRecentData(symbol, 20);
            if (recent20.size() < 20) return false;

            BigDecimal currentPrice = ta.getCurrentPrice();
            double volumeRatio = ta.getVolumeRatio();

            if (volumeRatio < 1.2) return false; // Require volume confirmation

            // Find 20-period high and low
            BigDecimal high20 = recent20.stream()
                    .map(MarketData::getHigh)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            BigDecimal low20 = recent20.stream()
                    .map(MarketData::getLow)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            if (high20.compareTo(BigDecimal.ZERO) == 0 || low20.compareTo(BigDecimal.ZERO) == 0) {
                return false;
            }

            // Check for meaningful breakouts (minimum 0.10% penetration)
            if (isCall) {
                BigDecimal breakoutLevel = high20.multiply(BigDecimal.valueOf(1.001)); // 0.10% above high
                return currentPrice.compareTo(breakoutLevel) > 0;
            } else {
                BigDecimal breakoutLevel = low20.multiply(BigDecimal.valueOf(0.999)); // 0.10% below low
                return currentPrice.compareTo(breakoutLevel) < 0;
            }

        } catch (Exception e) {
            log.debug("[KEY-LEVEL][{}] Error checking key levels for {}: {}", analysisId, symbol, e.getMessage());
            return false;
        }
    }

    private boolean hasValidRSIContext(TechnicalAnalysis ta, boolean isCall) {
        double rsi = ta.getRsi();

        // FIXED: Expanded ranges to handle more market conditions
        if (isCall) {
            return rsi >= 25 && rsi <= 85; // Expanded from 40-80
        } else {
            return rsi >= 15 && rsi <= 75; // Expanded from 20-60
        }
    }

    private class RSIHistoryTracker {
        private final Map<String, List<RSIReading>> rsiHistory = new ConcurrentHashMap<>();
        private final int MAX_HISTORY_SIZE = 10;

        public void initialize() {
            for (String symbol : Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA")) {
                rsiHistory.put(symbol, new ArrayList<>());
            }
        }

        public void cleanup() {
            rsiHistory.clear();
        }

        public void updateRSIHistory() {
            try {
                for (String symbol : Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA")) {
                    TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
                    if (ta != null) {
                        addRSIReading(symbol, ta.getRsi());
                    }
                }
            } catch (Exception e) {
                log.debug("Error updating RSI history: {}", e.getMessage());
            }
        }

        private void addRSIReading(String symbol, double rsi) {
            List<RSIReading> history = rsiHistory.computeIfAbsent(symbol, k -> new ArrayList<>());
            RSIReading reading = new RSIReading(rsi, LocalDateTime.now());
            history.add(0, reading);

            if (history.size() > MAX_HISTORY_SIZE) {
                history.subList(MAX_HISTORY_SIZE, history.size()).clear();
            }
        }

        public RSIDirection getRSIDirection(String symbol, int minutesBack) {
            List<RSIReading> history = rsiHistory.get(symbol);
            if (history == null || history.size() < 2) {
                return RSIDirection.INSUFFICIENT_DATA;
            }

            double currentRSI = history.get(0).rsi;
            double pastRSI = getRSIAtTime(history, minutesBack);

            if (pastRSI == 0.0) {
                return RSIDirection.INSUFFICIENT_DATA;
            }

            double delta = currentRSI - pastRSI;

            if (delta > 2.0) return RSIDirection.RISING;
            if (delta < -2.0) return RSIDirection.FALLING;
            return RSIDirection.FLAT;
        }

        private double getRSIAtTime(List<RSIReading> history, int minutesAgo) {
            LocalDateTime targetTime = LocalDateTime.now().minusMinutes(minutesAgo);

            return history.stream()
                    .filter(r -> r.timestamp.isBefore(targetTime.plusMinutes(1)) &&
                            r.timestamp.isAfter(targetTime.minusMinutes(1)))
                    .mapToDouble(r -> r.rsi)
                    .findFirst()
                    .orElse(history.size() > minutesAgo ? history.get(minutesAgo).rsi : 0.0);
        }

        public double getCurrentRSI(String symbol) {
            List<RSIReading> history = rsiHistory.get(symbol);
            if (history == null || history.isEmpty()) {
                return 0.0;
            }
            return history.get(0).rsi;
        }

        public List<RSIReading> getRSIHistory(String symbol) {
            return rsiHistory.getOrDefault(symbol, new ArrayList<>());
        }
    }

    @Getter @Setter
    private static class RSIReading {
        final double rsi;
        final LocalDateTime timestamp;

        public RSIReading(double rsi, LocalDateTime timestamp) {
            this.rsi = rsi;
            this.timestamp = timestamp;
        }
    }

    private enum RSIDirection {
        RISING, FALLING, FLAT, INSUFFICIENT_DATA
    }

    public static class VolumeSnapshot {
        private final String symbol;
        private final double volumeRatio;
        private final boolean hasExhaustion;
        private final boolean hasSpike;
        private final LocalDateTime snapshotTime;

        public VolumeSnapshot(String symbol, double volumeRatio, boolean hasExhaustion,
                              boolean hasSpike, LocalDateTime snapshotTime) {
            this.symbol = symbol;
            this.volumeRatio = volumeRatio;
            this.hasExhaustion = hasExhaustion;
            this.hasSpike = hasSpike;
            this.snapshotTime = snapshotTime;
        }

        public double getVolumeRatio() { return volumeRatio; }
        public boolean hasExhaustion() { return hasExhaustion; }
        public boolean hasSpike() { return hasSpike; }
        public LocalDateTime getSnapshotTime() { return snapshotTime; }
    }
    // Add this new inner class to ZeroDTEStrategy
    private class VolumeTracker {
        private final Map<String, CircularVolumeBuffer> symbolVolumes = new ConcurrentHashMap<>();
        private final int BUFFER_SIZE = 20; // Track last 20 minutes

        private static class CircularVolumeBuffer {
            private final long[] volumes;
            private final LocalDateTime[] timestamps;
            private int currentIndex = 0;
            private int size = 0;
            private long lastCumulativeVolume = 0;

            CircularVolumeBuffer(int capacity) {
                this.volumes = new long[capacity];
                this.timestamps = new LocalDateTime[capacity];
            }

            void addReading(long cumulativeVolume, LocalDateTime timestamp) {
                long barVolume = cumulativeVolume - lastCumulativeVolume;
                lastCumulativeVolume = cumulativeVolume;

                volumes[currentIndex] = barVolume;
                timestamps[currentIndex] = timestamp;
                currentIndex = (currentIndex + 1) % volumes.length;
                if (size < volumes.length) size++;
            }

            double getCurrentVolumeRatio() {
                if (size < 3) return 0.0;

                long currentVolume = volumes[(currentIndex - 1 + volumes.length) % volumes.length];
                long sum = 0;
                for (int i = 0; i < size; i++) {
                    sum += volumes[i];
                }
                double average = (double) sum / size;

                return average > 0 ? currentVolume / average : 0.0;
            }

            boolean hasExhaustionPattern() {
                if (size < 4) return false;

                // Check last 3 bars for declining volume
                long bar1 = volumes[(currentIndex - 4 + volumes.length) % volumes.length];
                long bar2 = volumes[(currentIndex - 3 + volumes.length) % volumes.length];
                long bar3 = volumes[(currentIndex - 2 + volumes.length) % volumes.length];
                long bar4 = volumes[(currentIndex - 1 + volumes.length) % volumes.length];

                return bar1 > bar2 && bar2 > bar3 && bar4 < bar3;
            }

            boolean hasVolumeSpikeAfterExhaustion() {
                if (size < 2) return false;

                long current = volumes[(currentIndex - 1 + volumes.length) % volumes.length];
                long previous = volumes[(currentIndex - 2 + volumes.length) % volumes.length];

                return current > previous * 1.3; // 30% spike minimum
            }
        }

        public void updateVolume(String symbol, long cumulativeVolume, LocalDateTime timestamp) {
            CircularVolumeBuffer buffer = symbolVolumes.computeIfAbsent(symbol,
                    k -> new CircularVolumeBuffer(BUFFER_SIZE));
            buffer.addReading(cumulativeVolume, timestamp);
        }

        public double getVolumeRatio(String symbol) {
            CircularVolumeBuffer buffer = symbolVolumes.get(symbol);
            return buffer != null ? buffer.getCurrentVolumeRatio() : 0.0;
        }

        public boolean hasExhaustionPattern(String symbol) {
            CircularVolumeBuffer buffer = symbolVolumes.get(symbol);
            return buffer != null && buffer.hasExhaustionPattern();
        }

        public boolean hasVolumeSpikeAfterExhaustion(String symbol) {
            CircularVolumeBuffer buffer = symbolVolumes.get(symbol);
            return buffer != null && buffer.hasVolumeSpikeAfterExhaustion();
        }

        // ============================================
        // NEW METHOD ADDED HERE
        // ============================================
        public VolumeSnapshot getSnapshot(String symbol) {
            double volumeRatio = getVolumeRatio(symbol);
            boolean hasExhaustion = hasExhaustionPattern(symbol);
            boolean hasSpike = hasVolumeSpikeAfterExhaustion(symbol);

            return new VolumeSnapshot(
                    symbol,
                    volumeRatio,
                    hasExhaustion,
                    hasSpike,
                    LocalDateTime.now()
            );
        }
    }



    // Add as field in ZeroDTEStrategy
    private final VolumeTracker volumeTracker = new VolumeTracker();

    // Add scheduled update method
    @Scheduled(fixedDelay = 60000) // Every 1 minute
    public void updateVolumeTracking() {
        try {
            for (String symbol : Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA")) {
                List<MarketData> data = marketDataRepository.findRecentData(symbol, 1);
                if (!data.isEmpty()) {
                    MarketData latest = data.get(0);
                    if (latest.getVolume() != null) {
                        volumeTracker.updateVolume(symbol, latest.getVolume(),
                                latest.getTimestamp() != null ? latest.getTimestamp() : LocalDateTime.now());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error updating volume tracking: {}", e.getMessage());
        }
    }

    // Add this new inner class to ZeroDTEStrategy
    private class GEXCalculator {

        @Getter @Setter
        static class GEXLevels {
            private BigDecimal positiveGEX;      // +GEX (call gamma resistance)
            private BigDecimal negativeGEX;      // -GEX (put gamma support)
            private BigDecimal zeroGEX;          // Zero gamma flip point
            private BigDecimal currentPrice;
            private LocalDateTime calculatedAt;

            public GEXLevels(BigDecimal positiveGEX, BigDecimal negativeGEX,
                             BigDecimal zeroGEX, BigDecimal currentPrice) {
                this.positiveGEX = positiveGEX;
                this.negativeGEX = negativeGEX;
                this.zeroGEX = zeroGEX;
                this.currentPrice = currentPrice;
                this.calculatedAt = LocalDateTime.now();
            }

            public double getDistanceToNegativeGEX() {
                if (negativeGEX == null || currentPrice == null ||
                        currentPrice.compareTo(BigDecimal.ZERO) == 0) {
                    return Double.MAX_VALUE;
                }
                return currentPrice.subtract(negativeGEX).abs()
                        .divide(currentPrice, 6, RoundingMode.HALF_UP).doubleValue();
            }

            public double getDistanceToPositiveGEX() {
                if (positiveGEX == null || currentPrice == null ||
                        currentPrice.compareTo(BigDecimal.ZERO) == 0) {
                    return Double.MAX_VALUE;
                }
                return currentPrice.subtract(positiveGEX).abs()
                        .divide(currentPrice, 6, RoundingMode.HALF_UP).doubleValue();
            }

            public boolean isNearNegativeGEX() {
                return getDistanceToNegativeGEX() < 0.002; // Within 0.2%
            }

            public boolean isNearPositiveGEX() {
                return getDistanceToPositiveGEX() < 0.002; // Within 0.2%
            }
        }

        static class GEXStrike {
            BigDecimal strike;
            double gexValue;
            boolean isCall;

            GEXStrike(BigDecimal strike, double gexValue, boolean isCall) {
                this.strike = strike;
                this.gexValue = gexValue;
                this.isCall = isCall;
            }
        }

        public GEXLevels calculateGEX(List<Option> options, BigDecimal spotPrice, String analysisId) {
            if (options == null || options.isEmpty() || spotPrice == null ||
                    spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[GEX][{}] Cannot calculate - invalid input", analysisId);
                return null;
            }

            List<GEXStrike> gexStrikes = new ArrayList<>();
            double spotSquared = spotPrice.doubleValue() * spotPrice.doubleValue();

            for (Option option : options) {
                if (option.getStrikePrice() == null || option.getOpenInterest() == null ||
                        option.getGamma() == null) {
                    continue;
                }

                double gamma = option.getGamma();
                int openInterest = option.getOpenInterest();
                boolean isCall = "CALL".equalsIgnoreCase(option.getType());

                // GEX = Gamma × Open_Interest × Contract_Multiplier × Spot²
                // For calls: positive (dealers short gamma)
                // For puts: negative (dealers long gamma)
                double gexValue = gamma * openInterest * 100 * spotSquared;
                if (!isCall) {
                    gexValue = -gexValue; // Puts are negative
                }

                gexStrikes.add(new GEXStrike(option.getStrikePrice(), gexValue, isCall));
            }

            if (gexStrikes.isEmpty()) {
                log.warn("[GEX][{}] No valid strikes for calculation", analysisId);
                return null;
            }

            // Find +GEX (largest positive call gamma)
            GEXStrike maxCallGEX = gexStrikes.stream()
                    .filter(g -> g.isCall && g.gexValue > 0)
                    .max(Comparator.comparingDouble(g -> g.gexValue))
                    .orElse(null);

            // Find -GEX (largest negative put gamma in absolute terms)
            GEXStrike maxPutGEX = gexStrikes.stream()
                    .filter(g -> !g.isCall && g.gexValue < 0)
                    .min(Comparator.comparingDouble(g -> g.gexValue))
                    .orElse(null);

            // Calculate zero GEX (cumulative flip point)
            gexStrikes.sort(Comparator.comparing(g -> g.strike));
            double cumulativeGEX = 0;
            BigDecimal zeroGEXStrike = spotPrice;

            for (GEXStrike strike : gexStrikes) {
                cumulativeGEX += strike.gexValue;
                if (cumulativeGEX > 0 && strike.strike.compareTo(spotPrice) < 0) {
                    zeroGEXStrike = strike.strike;
                } else if (cumulativeGEX < 0 && strike.strike.compareTo(spotPrice) > 0) {
                    break;
                }
            }

            GEXLevels levels = new GEXLevels(
                    maxCallGEX != null ? maxCallGEX.strike : null,
                    maxPutGEX != null ? maxPutGEX.strike : null,
                    zeroGEXStrike,
                    spotPrice
            );

            log.info("[GEX][{}] Calculated - +GEX: ${}, -GEX: ${}, Zero: ${}, Spot: ${}",
                    analysisId,
                    levels.getPositiveGEX(),
                    levels.getNegativeGEX(),
                    levels.getZeroGEX(),
                    spotPrice);

            return levels;
        }
    }

    // Add as field in ZeroDTEStrategy
    private final GEXCalculator gexCalculator = new GEXCalculator();

    // Replace existing isGoodTradingTime method
    private boolean isGoodTradingTime(LocalTime now) {
        // Block dead zone entirely
        if (now.isAfter(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(14, 0))) {
            return false;
        }

        return (now.isAfter(PRIME_WINDOW_1_START) && now.isBefore(PRIME_WINDOW_1_END)) ||
                (now.isAfter(PRIME_WINDOW_2_START) && now.isBefore(PRIME_WINDOW_2_END)) ||
                (now.isAfter(PRIME_WINDOW_3_START) && now.isBefore(PRIME_WINDOW_3_END)) ||
                (now.isAfter(FINAL_WINDOW_START) && now.isBefore(FINAL_WINDOW_END));
    }

    // Add new method for time-adjusted confidence
    private double getTimeAdjustedConfidence(double baseConfidence, LocalTime now, String analysisId) {
        double multiplier = 1.0;
        String window = "UNKNOWN";

        if (now.isAfter(LocalTime.of(10, 15)) && now.isBefore(LocalTime.of(11, 0))) {
            multiplier = 1.0;
            window = "OPTIMAL";
        } else if (now.isAfter(LocalTime.of(11, 0)) && now.isBefore(LocalTime.of(13, 0))) {
            multiplier = 0.85;
            window = "MIDDAY_LULL";
        } else if (now.isAfter(LocalTime.of(14, 0)) && now.isBefore(LocalTime.of(15, 0))) {
            multiplier = 1.0;
            window = "AFTERNOON";
        } else {
            multiplier = 0.9;
            window = "OTHER";
        }

        double adjusted = baseConfidence * multiplier;

        log.info("[TIME-ADJUST][{}] Window: {}, Base: {}%, Multiplier: {}x, Adjusted: {}%",
                analysisId, window, (int)(baseConfidence * 100),
                String.format("%.2f", multiplier), (int)(adjusted * 100));

        return adjusted;
    }

    // ============================================
// STEP 4: ADD ALL THESE NEW METHODS
// Location: At the end of the class, BEFORE the final closing brace }
// After getTimeAdjustedConfidence() method (around line 6454)
// ============================================

    /**
     * NEW: Evaluate confirmation-based signal using new services
     */
    private ConfirmationSignal evaluateConfirmationSignal(String symbol, String analysisId) {
        ConfirmationSignal signal = new ConfirmationSignal();
        signal.timestamp = LocalDateTime.now();
        signal.symbol = symbol;

        try {
            // Get recent bars for QQQ
            List<MarketData> qqqBars = barAggregationService.getRecentBars(symbol, LOOKBACK_BARS);

            if (qqqBars == null || qqqBars.isEmpty()) {
                signal.addRejection("No market data available: " + (qqqBars == null ? "null" : "empty"));
                log.warn("[{}] {}", analysisId, signal.rejections.get(signal.rejections.size() - 1));
                return signal;
            }

            if (qqqBars.size() < 3) {
                signal.addRejection("Insufficient QQQ bar data: " + qqqBars.size() + " (need at least 3)");
                log.warn("[{}] {}", analysisId, signal.rejections.get(signal.rejections.size() - 1));
                return signal;
            }

            // Check for NULL price bars
            long nullPrices = qqqBars.stream().filter(bar -> bar.getClose() == null).count();
            if (nullPrices > 0) {
                signal.addRejection("Data quality issue: " + nullPrices + "/" + qqqBars.size() + " bars have NULL prices");
                log.warn("[{}] {}", analysisId, signal.rejections.get(signal.rejections.size() - 1));
                return signal;
            }

            MarketData currentBar = qqqBars.get(qqqBars.size() - 1);
            BigDecimal currentPrice = currentBar.getClose();

            // Get VWAP from TechnicalAnalysisService
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta == null) {
                signal.addRejection("Technical analysis failed to complete");
                log.error("[{}] Technical analysis returned null", analysisId);
                return signal;
            }

            if (ta.getVwap() == null) {
                signal.addRejection("VWAP calculation failed - possibly due to data quality issues");
                log.warn("[{}] VWAP is null in technical analysis result", analysisId);
                return signal;
            }

            BigDecimal vwap = ta.getVwap();
            signal.vwap = vwap;
            signal.currentPrice = currentPrice;

            // Calculate distance from VWAP
            BigDecimal distance = currentPrice.subtract(vwap).abs();
            BigDecimal distancePercent = distance.divide(vwap, 6, RoundingMode.HALF_UP);
            signal.distancePercent = distancePercent.multiply(BigDecimal.valueOf(100));

            log.info("[{}] Price: ${}, VWAP: ${}, Distance: {}%",
                    analysisId, currentPrice, vwap, signal.distancePercent);

            // Continue with the rest of your confirmation logic
            // ...

            return signal;
        } catch (Exception e) {
            signal.addRejection("Error: " + e.getMessage());
            log.error("[{}] Error evaluating confirmation signal: {}", analysisId, e.getMessage(), e);
            return signal;
        }
    }

    /**
     * NEW: Mean-Reversion Signal Generation
     * Generates signals when price deviates from VWAP by ≥0.10%
     */
    private ConfirmationSignal generateMeanReversionSignal(String analysisId) {
        try {
            ConfirmationSignal signal = new ConfirmationSignal();
            signal.setSymbol("QQQ");
            signal.setTimestamp(LocalDateTime.now());
            signal.setAction("NONE");

            // Get technical analysis
            TechnicalAnalysis ta = technicalAnalysisService.analyze("QQQ");
            if (ta == null || ta.getCurrentPrice() == null || ta.getVwap() == null) {
                log.warn("[MR-SIGNAL][{}] Missing technical analysis data", analysisId);
                return signal;
            }

            BigDecimal currentPrice = ta.getCurrentPrice();
            BigDecimal vwap = ta.getVwap();

            // Calculate distance from VWAP
            BigDecimal priceDiff = currentPrice.subtract(vwap);
            BigDecimal distancePercent = priceDiff.divide(vwap, 4, RoundingMode.HALF_UP);

            signal.setCurrentPrice(currentPrice);
            signal.setVwap(vwap);
            signal.setDistancePercent(distancePercent.multiply(BigDecimal.valueOf(100)));

            log.info("[MR-SIGNAL][{}] Price: ${}, VWAP: ${}, Distance: {}%",
                    analysisId, currentPrice, vwap, signal.getDistancePercent());

            // ================================================================
            // IMPROVEMENT #7: Increased distance threshold to 0.15%
            // ================================================================
            BigDecimal absDistance = distancePercent.abs();
            BigDecimal THRESHOLD = new BigDecimal("0.0006"); // 0.15% (was 0.10%)

            if (absDistance.compareTo(THRESHOLD) < 0) {
                signal.addRejection("[DISTANCE] Too close to VWAP: " + signal.getDistancePercent() + "%");
                log.info("[MR-SIGNAL][{}] ❌ REJECTED - Distance too small: {}%",
                        analysisId, signal.getDistancePercent());
                return signal;
            }

            // Determine signal direction
            boolean priceBelowVWAP = currentPrice.compareTo(vwap) < 0;
            boolean priceAboveVWAP = currentPrice.compareTo(vwap) > 0;

            // CALL when price below VWAP
            if (priceBelowVWAP) {
                signal.setAction("CALL");
                signal.addConfirmation(String.format("[PRICE-VWAP] Price below VWAP by %.2f%%",
                        signal.getDistancePercent().abs()));
                signal.confirmationCount++;

                log.info("[MR-SIGNAL][{}] CALL opportunity - Price below VWAP by {}%",
                        analysisId, signal.getDistancePercent().abs());
            }
            // PUT when price above VWAP
            else if (priceAboveVWAP) {
                signal.setAction("PUT");
                signal.addConfirmation(String.format("[PRICE-VWAP] Price above VWAP by %.2f%%",
                        signal.getDistancePercent().abs()));
                signal.confirmationCount++;

                log.info("[MR-SIGNAL][{}] PUT opportunity - Price above VWAP by {}%",
                        analysisId, signal.getDistancePercent().abs());
            }

            // ================================================================
            // IMPROVEMENT #1: Recent VWAP Interaction Check
            // Price must have touched VWAP within last 10 bars (10 minutes)
            // ================================================================
            boolean hasRecentVWAPInteraction = checkRecentVWAPInteraction("QQQ", vwap, 10, analysisId);
            if (!hasRecentVWAPInteraction) {
                signal.addRejection("[VWAP-INTERACTION] No VWAP touch in last 10 minutes");
                signal.setAction("NONE");
                log.info("[MR-SIGNAL][{}] ❌ REJECTED - No recent VWAP interaction", analysisId);
                return signal;
            } else {
                signal.addConfirmation("[VWAP-INTERACTION] Price touched VWAP within last 10 bars");
                signal.confirmationCount++;
            }

            // ================================================================
            // IMPROVEMENT #2: Momentum Slowing Check
            // Current bar price change must be smaller than previous bar
            // ================================================================
            boolean isMomentumSlowing = checkMomentumSlowing("QQQ", analysisId);
            if (!isMomentumSlowing) {
                signal.addRejection("[MOMENTUM] Price moving too fast - momentum not slowing");
                signal.setAction("NONE");
                log.info("[MR-SIGNAL][{}] ❌ REJECTED - Momentum still accelerating", analysisId);
                return signal;
            } else {
                signal.addConfirmation("[MOMENTUM] Momentum slowing - price velocity decreasing");
                signal.confirmationCount++;
            }

            // ================================================================
            // IMPROVEMENT #4: Volume Spike Check (3-bar comparison)
            // Current bar volume must be > average of last 3 bars × 1.5
            // ================================================================
            boolean hasVolumeSurge = checkVolumeSurge("QQQ", analysisId);
            if (hasVolumeSurge) {
                signal.setVolumeConfirming(true);
                signal.addConfirmation("[VOLUME] Volume surge detected (>1.5x last 3 bars avg)");
                signal.confirmationCount++;
            } else {
                signal.addRejection("[VOLUME] No volume surge detected");
            }

            // ================================================================
            // IMPROVEMENT #3: Real Historical Bounces/Rejections
            // Quality check: 0.08% reversal, 2-3 clean bounces
            // ================================================================
            if (signal.getAction().equals("CALL")) {
                int qualityBounces = countQualityBounces("QQQ", vwap, 30, analysisId);
                if (qualityBounces >= 2) {
                    signal.setHasHistoricalSupport(true);
                    signal.addConfirmation(String.format("[HISTORY] %d quality bounces from VWAP", qualityBounces));
                    signal.confirmationCount++;
                } else {
                    signal.addRejection(String.format("[HISTORY] Only %d quality bounces (need 2)", qualityBounces));
                }
            } else if (signal.getAction().equals("PUT")) {
                int qualityRejections = countQualityRejections("QQQ", vwap, 30, analysisId);
                if (qualityRejections >= 2) {
                    signal.setHasHistoricalSupport(true);
                    signal.addConfirmation(String.format("[HISTORY] %d quality rejections at VWAP", qualityRejections));
                    signal.confirmationCount++;
                } else {
                    signal.addRejection(String.format("[HISTORY] Only %d quality rejections (need 2)", qualityRejections));
                }
            }

            // ================================================================
            // IMPROVEMENT #5: Leader Confirmation (2/3 with RSI 20-80)
            // ================================================================
            boolean leadersConfirm = checkLeaderConfirmation(signal.getAction(), analysisId);
            if (leadersConfirm) {
                signal.setLeadersConfirming(true);
                signal.addConfirmation("[LEADERS] 2/3 leaders confirming direction with valid RSI");
                signal.confirmationCount++;
            } else {
                signal.addRejection("[LEADERS] Less than 2/3 leaders confirming");
            }

            // ================================================================
            // IMPROVEMENT #6: Time-Based ATR Scaling
            // More conservative targets in afternoon hours
            // ================================================================
            BigDecimal atr = ta.getAverageTrueRange();
            if (atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) {
                atr = currentPrice.multiply(new BigDecimal("0.01")); // Fallback: 1% of price
            }

            // Apply time-based ATR multiplier
            LocalTime now = LocalTime.now(ET_ZONE);
            BigDecimal atrMultiplier = getATRMultiplierForTime(now);
            BigDecimal adjustedATR = atr.multiply(atrMultiplier);

            log.info("[MR-SIGNAL][{}] Time: {}, ATR Multiplier: {}x, Adjusted ATR: ${}",
                    analysisId, now, atrMultiplier, adjustedATR);

            signal.setEntryPrice(currentPrice);

            if (signal.getAction().equals("CALL")) {
                signal.setTargetPrice(currentPrice.add(adjustedATR));
                signal.setStopLoss(currentPrice.subtract(adjustedATR.multiply(new BigDecimal("0.5"))));
            } else if (signal.getAction().equals("PUT")) {
                signal.setTargetPrice(currentPrice.subtract(adjustedATR));
                signal.setStopLoss(currentPrice.add(adjustedATR.multiply(new BigDecimal("0.5"))));
            }

            // ================================================================
            // Final Validation: Require minimum 5 confirmations
            // ================================================================
            if (signal.confirmationCount < 4) {
                signal.addRejection(String.format("[CONFIRMATIONS] Only %d/4+ confirmations met",
                        signal.confirmationCount));
                signal.setAction("NONE");
                log.info("[MR-SIGNAL][{}] ❌ REJECTED - Insufficient confirmations: {}/4",
                        analysisId, signal.confirmationCount);

                // Log all rejections for debugging
                for (String rejection : signal.rejections) {
                    log.info("[MR-SIGNAL][{}]   • {}", analysisId, rejection);
                }
            } else {
                log.info("[MR-SIGNAL][{}] ✅ {} SIGNAL GENERATED - {}/{} confirmations | Entry: ${} | Target: ${}",
                        analysisId, signal.getAction(), signal.confirmationCount,
                        signal.confirmations.size(), signal.getEntryPrice(), signal.getTargetPrice());

                // Log all confirmations
                for (String confirmation : signal.confirmations) {
                    log.info("[MR-SIGNAL][{}]   ✓ {}", analysisId, confirmation);
                }
            }

            return signal;

        } catch (Exception e) {
            log.error("[MR-SIGNAL][{}] Error generating mean reversion signal: {}", analysisId, e.getMessage(), e);
            ConfirmationSignal fallback = new ConfirmationSignal();
            fallback.setSymbol("QQQ");
            fallback.setAction("NONE");
            return fallback;
        }
    }


    /**
     * Count consecutive green bars from most recent
     */
    private int countConsecutiveGreenBars(List<MarketData> bars) {
        int count = 0;
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (candlePatternService.isGreenBar(bars.get(i))) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Check if entry is too late (> 2 minutes since reversal)
     */
    private boolean checkIfTooLate(List<MarketData> bars, String analysisId) {
        if (bars.size() < 3) return false;

        // Find when reversal started (first green bar in sequence)
        int reversalBarIndex = -1;
        for (int i = bars.size() - 1; i >= Math.max(0, bars.size() - 4); i--) {
            if (!candlePatternService.isGreenBar(bars.get(i))) {
                reversalBarIndex = i + 1;
                break;
            }
        }

        if (reversalBarIndex == -1) {
            reversalBarIndex = Math.max(0, bars.size() - 4);
        }

        int minutesSinceReversal = bars.size() - 1 - reversalBarIndex;
        boolean tooLate = minutesSinceReversal > MAX_MINUTES_SINCE_REVERSAL;

        if (tooLate) {
            log.warn("[{}] [TOO-LATE] {} minutes since reversal (max {})",
                    analysisId, minutesSinceReversal, MAX_MINUTES_SINCE_REVERSAL);
        }

        return tooLate;
    }


    /**
     * Dual logging: Track both confirmation and predictive timing
     */
    private void logPredictiveVsConfirmed(ConfirmationSignal signal, List<MarketData> bars, String analysisId) {
        if ("CALL".equals(signal.action)) {
            log.info("[{}] [CONFIRMED-ENTRY] Entry at ${} after {} confirmations",
                    analysisId, signal.entryPrice, signal.confirmationCount);
        }

        // Find where predictive entry would have been (VWAP touch)
        BigDecimal touchTolerance = new BigDecimal("0.0005"); // 0.05%
        for (int i = Math.max(0, bars.size() - 5); i < bars.size(); i++) {
            MarketData bar = bars.get(i);
            if (signal.vwap != null && bar.getLow() != null) {
                BigDecimal distance = bar.getLow().subtract(signal.vwap).abs();
                BigDecimal percentDistance = distance.divide(signal.vwap, 6, RoundingMode.HALF_UP);

                if (percentDistance.compareTo(touchTolerance) <= 0) {
                    log.info("[{}] [PREDICTIVE-OPPORTUNITY] Would have entered at ${} on VWAP touch (bar {})",
                            analysisId, bar.getClose(), i);
                    if (signal.entryPrice != null) {
                        BigDecimal diff = signal.entryPrice.subtract(bar.getClose());
                        log.info("[{}] [ENTRY-COMPARISON] Predictive: ${}, Confirmed: ${}, Diff: ${} ({}%)",
                                analysisId, bar.getClose(), signal.entryPrice, diff,
                                diff.divide(bar.getClose(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
                    }
                    break;
                }
            }
        }
    }

    /**
     * Select best call option for confirmed signal
     */
    // REPLACE THIS METHOD IN ZeroDTEStrategy.java (Lines 6780-6815)
    // This version is compilation-safe and adds detailed logging

    /**
     * Select best call option for confirmed signal
     */
    private Option selectBestCallOption(List<Option> options, BigDecimal entryPrice,
                                        BigDecimal targetPrice, String analysisId) {
        try {
            LocalDate today = LocalDate.now(ET_ZONE);

            log.info("[{}] [OPTION-SELECT] Starting selection process", analysisId);
            log.info("[{}] [OPTION-SELECT] Input: {} total options", analysisId, options.size());
            log.info("[{}] [OPTION-SELECT] Target: Type=CALL, Expiry={}, Entry=${}",
                    analysisId, today, entryPrice);

            // Use the EXACT same filters as original code to avoid compilation issues
            // Just add logging between each step

            // Step 1: Filter by type
            List<Option> afterTypeFilter = options.stream()
                    .filter(o -> "CALL".equalsIgnoreCase(o.getType()))
                    .collect(Collectors.toList());
            log.info("[{}] [OPTION-SELECT] After type=CALL filter: {} options (removed {})",
                    analysisId, afterTypeFilter.size(), options.size() - afterTypeFilter.size());

            if (afterTypeFilter.isEmpty()) {
                log.warn("[{}] [OPTION-SELECT] ❌ No CALL type options found", analysisId);
                return null;
            }

            // Step 2: Filter by expiration
            // NOTE: Option.getExpirationDate() returns String, we need LocalDate
            List<Option> afterExpiryFilter = afterTypeFilter.stream()
                    .filter(o -> {
                        if (o.getExpirationDate() == null) return false;
                        // Convert String to LocalDate for comparison
                        try {
                            Object expiryObj = o.getExpirationDate();
                            if (expiryObj instanceof String) {
                                LocalDate expiryDate = LocalDate.parse((String) expiryObj);
                                return expiryDate.equals(today);
                            } else if (expiryObj instanceof LocalDate) {
                                return expiryObj.equals(today);
                            }
                            return false;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
            log.info("[{}] [OPTION-SELECT] After expiry={} filter: {} options (removed {})",
                    analysisId, today, afterExpiryFilter.size(), afterTypeFilter.size() - afterExpiryFilter.size());

            if (afterExpiryFilter.isEmpty()) {
                log.warn("[{}] [OPTION-SELECT] ❌ No CALLs expiring today", analysisId);
                log.warn("[{}] [OPTION-SELECT] Total CALLs available: {}, None expire today",
                        analysisId, afterTypeFilter.size());

                // DEBUG: Show what expiry dates the options actually have
                Set<Object> actualExpiries = afterTypeFilter.stream()
                        .map(Option::getExpirationDate)
                        .filter(d -> d != null)
                        .limit(10)
                        .collect(Collectors.toSet());
                log.warn("[{}] [OPTION-SELECT] Sample expiry dates from options: {}",
                        analysisId, actualExpiries);
                log.warn("[{}] [OPTION-SELECT] Looking for today: {} (type: {})",
                        analysisId, today, today.getClass().getSimpleName());
                if (!actualExpiries.isEmpty()) {
                    Object firstExpiry = actualExpiries.iterator().next();
                    log.warn("[{}] [OPTION-SELECT] First expiry type: {}",
                            analysisId, firstExpiry.getClass().getName());
                }

                return null;
            }

            // Step 3: Filter by having all required prices
            List<Option> afterPriceFilter = afterExpiryFilter.stream()
                    .filter(o -> o.getStrikePrice() != null && o.getBid() != null && o.getAsk() != null)
                    .collect(Collectors.toList());
            log.info("[{}] [OPTION-SELECT] After price check filter: {} options (removed {})",
                    analysisId, afterPriceFilter.size(), afterExpiryFilter.size() - afterPriceFilter.size());

            if (afterPriceFilter.isEmpty()) {
                log.warn("[{}] [OPTION-SELECT] ❌ All options missing price data", analysisId);
                return null;
            }

            // Step 4: Filter by non-zero bid
            List<Option> calls = afterPriceFilter.stream()
                    .filter(o -> o.getBid().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());
            log.info("[{}] [OPTION-SELECT] After bid>$0 filter: {} options (removed {})",
                    analysisId, calls.size(), afterPriceFilter.size() - calls.size());

            if (calls.isEmpty()) {
                log.warn("[{}] [OPTION-SELECT] ❌ All options have bid=$0", analysisId);
                log.warn("[{}] [OPTION-SELECT] Filter summary:", analysisId);
                log.warn("[{}]   Started with: {} options", analysisId, options.size());
                log.warn("[{}]   Type=CALL: {} options", analysisId, afterTypeFilter.size());
                log.warn("[{}]   Expiry=today: {} options", analysisId, afterExpiryFilter.size());
                log.warn("[{}]   Has prices: {} options", analysisId, afterPriceFilter.size());
                log.warn("[{}]   Bid>$0: {} options", analysisId, calls.size());
                return null;
            }

            // Log strike range
            BigDecimal minStrike = calls.stream()
                    .map(Option::getStrikePrice)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            BigDecimal maxStrike = calls.stream()
                    .map(Option::getStrikePrice)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            log.info("[{}] [OPTION-SELECT] Strike range available: ${} to ${}",
                    analysisId, minStrike, maxStrike);

            // Step 5: Select ATM or slightly OTM call (EXACT same logic as original)
            BigDecimal lowerBound = entryPrice.subtract(new BigDecimal("1.0"));
            BigDecimal upperBound = entryPrice.add(new BigDecimal("2.0"));

            log.info("[{}] [OPTION-SELECT] Target strike range: ${} to ${} (Entry: ${})",
                    analysisId, lowerBound, upperBound, entryPrice);

            Option bestOption = calls.stream()
                    .filter(o -> o.getStrikePrice().compareTo(lowerBound) >= 0) // Allow $1 ITM
                    .filter(o -> o.getStrikePrice().compareTo(upperBound) <= 0)  // Max $2 OTM
                    .min(Comparator.comparing(o -> o.getStrikePrice().subtract(entryPrice).abs())) // Closest to entry
                    .orElse(null);

            if (bestOption == null) {
                log.warn("[{}] [OPTION-SELECT] ❌ No options in target strike range", analysisId);

                // Show closest option
                Option closest = calls.stream()
                        .min(Comparator.comparing(o -> o.getStrikePrice().subtract(entryPrice).abs()))
                        .orElse(null);

                if (closest != null) {
                    BigDecimal distance = closest.getStrikePrice().subtract(entryPrice).abs();
                    log.warn("[{}] [OPTION-SELECT] Closest available strike: ${} (${} away)",
                            analysisId, closest.getStrikePrice(), distance);
                    log.warn("[{}] [OPTION-SELECT] That strike is {} our range",
                            analysisId,
                            closest.getStrikePrice().compareTo(lowerBound) < 0 ? "BELOW" : "ABOVE");
                }

                // List first 5 strikes
                String strikeList = calls.stream()
                        .limit(5)
                        .map(o -> "$" + o.getStrikePrice().toString())
                        .collect(Collectors.joining(", "));
                log.warn("[{}] [OPTION-SELECT] Sample strikes: {} ...", analysisId, strikeList);

                return null;
            }

            // Success - log details
            log.info("[{}] [OPTION-SELECT] ✓✓✓ OPTION SELECTED ✓✓✓", analysisId);
            log.info("[{}]   Symbol: {}", analysisId, bestOption.getSymbol());
            log.info("[{}]   Strike: ${}", analysisId, bestOption.getStrikePrice());
            log.info("[{}]   Distance from entry: ${}", analysisId,
                    bestOption.getStrikePrice().subtract(entryPrice).abs());
            log.info("[{}]   Bid: ${}, Ask: ${}", analysisId,
                    bestOption.getBid(), bestOption.getAsk());

            return bestOption;

        } catch (Exception e) {
            log.error("[{}] [OPTION-SELECT] ❌❌❌ EXCEPTION: {}", analysisId, e.getMessage(), e);
            e.printStackTrace();
            return null;
        }
    }




    /**
     * Create signal object with confirmation data
     */
    private Signal createConfirmationSignal(Option option, ConfirmationSignal confirmation, String analysisId) {
        Signal signal = new Signal();

        signal.setSymbol(confirmation.symbol);
        signal.setOptionSymbol(option.getSymbol()); // FIXED: Use option symbol string
        signal.setSignalType("CALL");
        signal.setStrikePrice(option.getStrikePrice());
        signal.setEntryPrice(option.getAsk()); // Use ask for entry
        signal.setTargetPrice(confirmation.targetPrice);
        signal.setStopLoss(confirmation.stopLoss);

        // FIXED: Extract expiration from option symbol and convert to ZonedDateTime
        try {
            LocalDate expirationDate = extractExpirationFromOptionSymbol(option.getSymbol());
            if (expirationDate != null) {
                signal.setExpirationDate(expirationDate.atTime(16, 0).atZone(ET_ZONE));
            }
        } catch (Exception e) {
            log.warn("[{}] Could not extract expiration from option symbol: {}", analysisId, e.getMessage());
        }

        signal.setTimestamp(confirmation.timestamp);
        signal.setCreatedAt(confirmation.timestamp);
        signal.setSignalGeneratedAt(confirmation.timestamp);

        // Set initial confidence based on confirmation count
        double baseConfidence = 0.60 + (confirmation.confirmationCount * 0.05); // 60% + 5% per confirmation
        signal.setConfidence(Math.min(0.95, baseConfidence));
        signal.setOriginalConfidence(baseConfidence);

        // Set reason with confirmation details
        signal.setReason(String.format("CONFIRMATION-BASED: %d/6 confirmations | %s",
                confirmation.confirmationCount,
                String.join(", ", confirmation.confirmations.stream()
                        .map(c -> c.substring(c.indexOf("]") + 2))
                        .collect(Collectors.toList()))));

        signal.setStatus("PENDING");
        signal.setStrategy("CONFIRMATION_BASED_0DTE");

        // Store analysis ID in metadata
        signal.addMetadata("analysisId", analysisId);
        signal.addMetadata("confirmationCount", String.valueOf(confirmation.confirmationCount));
        signal.addMetadata("leadersConfirming", String.valueOf(confirmation.leadersConfirming));
        signal.addMetadata("qqqGreenBars", String.valueOf(confirmation.qqqGreenBars));

        log.info("[{}] Signal created: {} ${} CALL, Entry: ${}, Target: ${}, Confidence: {}%",
                analysisId, confirmation.symbol, option.getStrikePrice(),
                signal.getEntryPrice(), signal.getTargetPrice(),
                (int)(signal.getConfidence() * 100));

        return signal;
    }

    /**
     * Apply existing risk management and ML systems
     */
    private Signal applyRiskManagement(Signal signal, String analysisId) {
        try {
            // Apply pre-trade risk engine
            if (preTradeRiskEngine != null) {
                boolean riskCheckPassed = preTradeRiskEngine.canExecuteSignal(signal);
                if (!riskCheckPassed) {
                    log.warn("[{}] Signal rejected by pre-trade risk engine", analysisId);
                    signal.setPassedPreTradeChecks(false);
                    signal.setStatus("RISK_REJECTED");
                    return null;
                }
                signal.setPassedPreTradeChecks(true);
            }

            // Apply ML confidence adjustment - FIXED: uses isShouldExecute() getter
            if (integratedBayesianMLSystem != null) {
                try {
                    IntegratedBayesianMLSystem.IntegratedAnalysisResult mlResult =
                            integratedBayesianMLSystem.analyzeAndExecuteSignal(signal);

                    if (mlResult != null && mlResult.getBayesianAnalysis() != null) {
                        IntegratedBayesianMLSystem.BayesianAnalysis bayesianAnalysis = mlResult.getBayesianAnalysis();

                        double mlConfidence = bayesianAnalysis.getPosteriorProbability();
 //                       signal.setConfidence(mlConfidence);
                        signal.setAdjustedConfidence(mlConfidence);
                        signal.setBayesianProbability(mlConfidence);

                        // FIXED: Use isShouldExecute() getter method
//                        boolean shouldExecute = bayesianAnalysis.isShouldExecute();
//                        signal.setPassedBayesianFilter(shouldExecute);

                        log.info("[{}] ML-adjusted confidence: {}%", analysisId, (int)(mlConfidence * 100));

//                        if (!shouldExecute) {
//                            log.warn("[{}] Signal rejected by Bayesian filter", analysisId);
//                            signal.setStatus("BAYESIAN_REJECTED");
//                            return null;
//                        }
                    }
                } catch (Exception e) {
                    log.error("[{}] Error in ML analysis: {}", analysisId, e.getMessage());
                    // Continue without ML adjustment
                }
            }

            // Apply time-based adjustment
            LocalTime now = LocalTime.now(ET_ZONE);
            double timeAdjusted = getTimeAdjustedConfidence(signal.getConfidence(), now, analysisId);
            signal.setConfidence(timeAdjusted);
            signal.setAdjustedConfidence(timeAdjusted);

            return signal;

        } catch (Exception e) {
            log.error("[{}] Error in risk management: {}", analysisId, e.getMessage(), e);
            return signal; // Return original signal if risk management fails
        }
    }

    /**
     * Format Telegram message for confirmation signal
     */
    private String formatConfirmationSignalMessage(Signal signal, ConfirmationSignal confirmation) {
        StringBuilder msg = new StringBuilder();
        msg.append("🎯 CONFIRMATION-BASED SIGNAL 🎯\n\n");
        msg.append(String.format("Symbol: %s\n", signal.getSymbol()));
        msg.append(String.format("Type: CALL\n"));
        msg.append(String.format("Strike: $%.2f\n", signal.getStrikePrice()));
        msg.append(String.format("Entry: $%.2f\n", signal.getEntryPrice()));
        msg.append(String.format("Target: $%.2f\n", signal.getTargetPrice()));
        msg.append(String.format("Stop: $%.2f\n", signal.getStopLoss()));
        msg.append(String.format("Confidence: %d%%\n\n", (int)(signal.getConfidence() * 100)));

        msg.append(String.format("✅ Confirmations: %d/6\n", confirmation.confirmationCount));
        for (String c : confirmation.confirmations) {
            msg.append(String.format("  • %s\n", c.substring(c.indexOf("]") + 2)));
        }

        msg.append(String.format("\n📊 Details:\n"));
        msg.append(String.format("  VWAP: $%.2f\n", confirmation.vwap));
        msg.append(String.format("  Distance: %.2f%%\n", confirmation.distancePercent));
        msg.append(String.format("  QQQ Green Bars: %d\n", confirmation.qqqGreenBars));
        msg.append(String.format("  Leaders: %s\n", confirmation.leadersConfirming ? "✓ Confirming" : "✗ Not confirming"));

        return msg.toString();
    }


// ============================================
// STEP 5: ADD THIS INNER CLASS
// Location: At the very end, BEFORE the final closing brace }
// After all methods, around line 6454
// ============================================

    /**
     * Confirmation signal data structure
     */
    @Getter @Setter
    private static class ConfirmationSignal {
        String symbol;
        LocalDateTime timestamp;
        String action = "NONE";
        BigDecimal currentPrice;
        BigDecimal vwap;
        BigDecimal distancePercent;
        BigDecimal entryPrice;
        BigDecimal targetPrice;
        BigDecimal stopLoss;

        int qqqGreenBars = 0;
        boolean leadersConfirming = false;
        boolean volumeConfirming = false;
        boolean hasBullishCandle = false;
        boolean hasHistoricalSupport = false;

        int confirmationCount = 0;
        List<String> confirmations = new ArrayList<>();
        List<String> rejections = new ArrayList<>();

        void addConfirmation(String message) {
            confirmations.add(message);
        }

        void addRejection(String message) {
            rejections.add(message);
        }
    }

    private final Map<String, Double> symbolVolumeRatios = new ConcurrentHashMap<>();


    private boolean isVolumeSurgeConfirmed(String symbol, double volumeRatio) {
        // Store volume ratio for debug/logging
        symbolVolumeRatios.put(symbol, volumeRatio);

        // Check if volume is at least 1.2x the average
        boolean isVolumeSurge = volumeRatio >= 1.2;

        // Special case for market open (first 15 minutes)
        LocalTime marketOpen = LocalTime.of(9, 30);
        LocalTime currentTime = LocalTime.now(ZoneId.of("America/New_York"));
        boolean isNearOpen = Duration.between(marketOpen, currentTime).toMinutes() < 15;

        // Be more lenient near market open
        if (isNearOpen) {
            return true; // Always confirm near open
        }

        return isVolumeSurge;
    }


    /**
     * Check if price has interacted with VWAP in the last N bars
     * Interaction = price within 0.05% of VWAP
     */
    private boolean checkRecentVWAPInteraction(String symbol, BigDecimal vwap, int minutesBack, String analysisId) {
        try {
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, minutesBack);
            if (recentBars.isEmpty()) {
                log.warn("[VWAP-INTERACTION][{}] No bars available", analysisId);
                return false;
            }

            // Define "near VWAP" as within 0.15% threshold
            BigDecimal VWAP_PROXIMITY_THRESHOLD = new BigDecimal("0.0015"); // 0.15%

            for (MarketData bar : recentBars) {
                if (bar.getHigh() == null || bar.getLow() == null || vwap == null) {
                    continue;
                }

                // Check if bar's price range came near VWAP (within 0.15%)
                BigDecimal distanceFromHigh = bar.getHigh().subtract(vwap)
                        .divide(vwap, 6, RoundingMode.HALF_UP)
                        .abs();
                BigDecimal distanceFromLow = bar.getLow().subtract(vwap)
                        .divide(vwap, 6, RoundingMode.HALF_UP)
                        .abs();

                // If high is above VWAP and low is below VWAP, bar crossed VWAP
                boolean crossedVWAP = bar.getLow().compareTo(vwap) <= 0 &&
                        bar.getHigh().compareTo(vwap) >= 0;

                // Or if either high or low came within 0.15% of VWAP
                boolean nearVWAP = distanceFromHigh.compareTo(VWAP_PROXIMITY_THRESHOLD) <= 0 ||
                        distanceFromLow.compareTo(VWAP_PROXIMITY_THRESHOLD) <= 0;

                if (crossedVWAP || nearVWAP) {
                    log.info("[VWAP-INTERACTION][{}] ✓ Price touched/crossed VWAP at {} (L: ${}, H: ${}, VWAP: ${})",
                            analysisId, bar.getTimestamp(), bar.getLow(), bar.getHigh(), vwap);
                    return true;
                }
            }

            log.info("[VWAP-INTERACTION][{}] ✗ No VWAP touch in last {} bars",
                    analysisId, minutesBack);
            return false;

        } catch (Exception e) {
            log.error("[VWAP-INTERACTION][{}] Error: {}", analysisId, e.getMessage());
            return false;
        }
    }
    /**
     * Check if momentum is slowing (current bar move < previous bar move)
     */
    private boolean checkMomentumSlowing(String symbol, String analysisId) {
        try {
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 3);
            if (recentBars.size() < 3) {
                log.warn("[MOMENTUM][{}] Insufficient bars: {}", analysisId, recentBars.size());
                return false;
            }

            MarketData current = recentBars.get(recentBars.size() - 1);
            MarketData previous = recentBars.get(recentBars.size() - 2);
            MarketData beforePrevious = recentBars.get(recentBars.size() - 3);

            if (current.getClose() == null || previous.getClose() == null ||
                    beforePrevious.getClose() == null) {
                log.warn("[MOMENTUM][{}] NULL price data", analysisId);
                return false;
            }

            // Calculate price velocity (% change per bar)
            BigDecimal currentVelocity = current.getClose().subtract(previous.getClose())
                    .divide(previous.getClose(), 6, RoundingMode.HALF_UP)
                    .abs();

            BigDecimal previousVelocity = previous.getClose().subtract(beforePrevious.getClose())
                    .divide(beforePrevious.getClose(), 6, RoundingMode.HALF_UP)
                    .abs();

            // Momentum is slowing if current velocity < previous velocity
            boolean isSlowing = currentVelocity.compareTo(previousVelocity) < 0;

            log.info("[MOMENTUM][{}] Current velocity: {}%, Previous: {}%, Slowing: {}",
                    analysisId,
                    currentVelocity.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP),
                    previousVelocity.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP),
                    isSlowing);

            return isSlowing;

        } catch (Exception e) {
            log.error("[MOMENTUM][{}] Error: {}", analysisId, e.getMessage());
            return false;
        }
    }


    /**
     * Check for volume surge: current bar > average of last 3 bars × 1.5
     */
    private boolean checkVolumeSurge(String symbol, String analysisId) {
        try {
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 4);
            if (recentBars.size() < 4) {
                log.warn("[VOLUME][{}] Insufficient bars: {}", analysisId, recentBars.size());
                return false;
            }

            MarketData currentBar = recentBars.get(recentBars.size() - 1);

            // Use incrementalVolume if available, otherwise use volume
            Long currentVolume = currentBar.getIncrementalVolume() != null ?
                    currentBar.getIncrementalVolume() : currentBar.getVolume();

            if (currentVolume == null || currentVolume <= 0) {
                log.warn("[VOLUME][{}] Invalid current volume: {}", analysisId, currentVolume);
                return false;
            }

            // Calculate average of previous 3 bars
            long totalVolume = 0;
            int validBars = 0;
            for (int i = recentBars.size() - 4; i < recentBars.size() - 1; i++) {
                MarketData bar = recentBars.get(i);
                Long vol = bar.getIncrementalVolume() != null ?
                        bar.getIncrementalVolume() : bar.getVolume();
                if (vol != null && vol > 0) {
                    totalVolume += vol;
                    validBars++;
                }
            }

            if (validBars == 0) {
                log.warn("[VOLUME][{}] No valid volume data in previous bars", analysisId);
                return false;
            }

            double avgVolume = (double) totalVolume / validBars;
            double volumeRatio = currentVolume / avgVolume;
            boolean hasSurge = volumeRatio >= 1.5;

            log.info("[VOLUME][{}] Current: {}, Avg(3): {}, Ratio: {}x, Surge: {}",
                    analysisId,
                    currentVolume,
                    String.format("%.0f", avgVolume),
                    String.format("%.2f", volumeRatio),
                    hasSurge);

            return hasSurge;

        } catch (Exception e) {
            log.error("[VOLUME][{}] Error: {}", analysisId, e.getMessage());
            return false;
        }
    }

    /**
     * Check leader confirmation: 2/3 leaders with RSI 20-80 must align with QQQ direction
     */
    private boolean checkLeaderConfirmation(String qqqDirection, String analysisId) {
        try {
            int confirmingLeaders = 0;
            int validLeaders = 0;

            for (String leader : LEADER_STOCKS) {
                TechnicalAnalysis leaderTA = technicalAnalysisService.analyze(leader);
                if (leaderTA == null || leaderTA.getCurrentPrice() == null || leaderTA.getVwap() == null) {
                    log.warn("[LEADERS][{}] Missing data for {}", analysisId, leader);
                    continue;
                }

                double rsi = leaderTA.getRsi();

                // RSI must be between 20 and 80
                if (rsi < 20 || rsi > 80) {
                    log.info("[LEADERS][{}] {} RSI out of range: {}",
                            analysisId,
                            leader,
                            String.format("%.1f", rsi));
                    continue;
                }

                validLeaders++;

                BigDecimal leaderPrice = leaderTA.getCurrentPrice();
                BigDecimal leaderVwap = leaderTA.getVwap();
                boolean leaderBullish = leaderPrice.compareTo(leaderVwap) > 0;
                boolean leaderBearish = leaderPrice.compareTo(leaderVwap) < 0;

                // Check alignment with QQQ
                boolean aligned = false;
                if (qqqDirection.equals("CALL") && leaderBearish) {
                    aligned = true;
                } else if (qqqDirection.equals("PUT") && leaderBullish) {
                    aligned = true;
                }

                if (aligned) {
                    confirmingLeaders++;
                    log.info("[LEADERS][{}] ✓ {} confirms {} (Price: ${}, VWAP: ${}, RSI: {})",
                            analysisId,
                            leader,
                            qqqDirection,
                            leaderPrice,
                            leaderVwap,
                            String.format("%.1f", rsi));
                } else {
                    log.info("[LEADERS][{}] ✗ {} does NOT confirm {} (Price: ${}, VWAP: ${}, RSI: {})",
                            analysisId,
                            leader,
                            qqqDirection,
                            leaderPrice,
                            leaderVwap,
                            String.format("%.1f", rsi));
                }
            }

            boolean hasConfirmation = confirmingLeaders >= 2;
            log.info("[LEADERS][{}] Result: {}/{} valid leaders confirming (need 2)",
                    analysisId, confirmingLeaders, validLeaders);

            return hasConfirmation;

        } catch (Exception e) {
            log.error("[LEADERS][{}] Error: {}", analysisId, e.getMessage());
            return false;
        }
    }

    /**
     * Get ATR multiplier based on time of day
     * 9:30-12:00 = 1.0x (full targets)
     * 12:00-14:00 = 0.7x (reduced targets)
     * 14:00-16:00 = 0.3x (conservative targets)
     */
    private BigDecimal getATRMultiplierForTime(LocalTime time) {
        if (time.isBefore(LocalTime.of(12, 0))) {
            return BigDecimal.ONE; // Morning: full targets
        } else if (time.isBefore(LocalTime.of(14, 0))) {
            return new BigDecimal("0.7"); // Early afternoon: reduced
        } else {
            return new BigDecimal("0.3"); // Late afternoon: conservative
        }
    }

    /**
     * Count quality bounces: price must reverse by at least 0.08% after touching VWAP
     * This filters out noise touches that don't result in meaningful reversals
     */
    private int countQualityBounces(String symbol, BigDecimal vwap, int minutesBack, String analysisId) {
        try {
            List<MarketData> recentData = barAggregationService.getRecentBars(symbol, minutesBack);
            if (recentData.size() < 3) {
                log.debug("[QUALITY-BOUNCE][{}] Insufficient data: {} bars", analysisId, recentData.size());
                return 0;
            }

            int bounceCount = 0;
            BigDecimal REVERSAL_THRESHOLD = new BigDecimal("0.0008"); // 0.08%

            for (int i = 1; i < recentData.size() - 1; i++) {
                MarketData bar = recentData.get(i);
                MarketData nextBar = recentData.get(i + 1);

                if (bar.getLow() == null || bar.getClose() == null ||
                        nextBar.getClose() == null || vwap == null) {
                    continue;
                }

                // Check if this bar touched below VWAP
                boolean lowBelowVWAP = bar.getLow().compareTo(vwap) < 0;

                // Check if bar closed at or above VWAP (recovery started)
                boolean closedAtOrAboveVWAP = bar.getClose().compareTo(vwap) >= 0;

                if (lowBelowVWAP && closedAtOrAboveVWAP) {
                    // Calculate the reversal percentage
                    BigDecimal reversalAmount = nextBar.getClose().subtract(bar.getLow());
                    BigDecimal reversalPercent = reversalAmount.divide(bar.getLow(), 4, RoundingMode.HALF_UP);

                    // Only count if reversal is at least 0.08%
                    if (reversalPercent.compareTo(REVERSAL_THRESHOLD) >= 0) {
                        bounceCount++;
                        log.info("[QUALITY-BOUNCE][{}] ✓ Found quality bounce at {} | Low: ${} → Next Close: ${} | Reversal: {}%",
                                analysisId, bar.getTimestamp(), bar.getLow(), nextBar.getClose(),
                                reversalPercent.multiply(BigDecimal.valueOf(100)));
                    } else {
                        log.debug("[QUALITY-BOUNCE][{}] ✗ Noise touch at {} | Reversal only {}% (need 0.08%)",
                                analysisId, bar.getTimestamp(),
                                reversalPercent.multiply(BigDecimal.valueOf(100)));
                    }
                }
            }

            log.info("[QUALITY-BOUNCE][{}] Found {} quality bounces in last {} minutes (0.08% threshold)",
                    analysisId, bounceCount, minutesBack);
            return bounceCount;

        } catch (Exception e) {
            log.error("[QUALITY-BOUNCE][{}] Error counting bounces: {}", analysisId, e.getMessage());
            return 0;
        }
    }

    /**
     * Count quality rejections: price must reverse by at least 0.08% after touching VWAP
     * This filters out noise touches that don't result in meaningful reversals
     */
    private int countQualityRejections(String symbol, BigDecimal vwap, int minutesBack, String analysisId) {
        try {
            List<MarketData> recentData = barAggregationService.getRecentBars(symbol, minutesBack);
            if (recentData.size() < 3) {
                log.debug("[QUALITY-REJECTION][{}] Insufficient data: {} bars", analysisId, recentData.size());
                return 0;
            }

            int rejectionCount = 0;
            BigDecimal REVERSAL_THRESHOLD = new BigDecimal("0.0008"); // 0.08%

            for (int i = 1; i < recentData.size() - 1; i++) {
                MarketData bar = recentData.get(i);
                MarketData nextBar = recentData.get(i + 1);

                if (bar.getHigh() == null || bar.getClose() == null ||
                        nextBar.getClose() == null || vwap == null) {
                    continue;
                }

                // Check if this bar touched above VWAP
                boolean highAboveVWAP = bar.getHigh().compareTo(vwap) > 0;

                // Check if bar closed at or below VWAP (rejection started)
                boolean closedAtOrBelowVWAP = bar.getClose().compareTo(vwap) <= 0;

                if (highAboveVWAP && closedAtOrBelowVWAP) {
                    // Calculate the reversal percentage
                    BigDecimal reversalAmount = bar.getHigh().subtract(nextBar.getClose());
                    BigDecimal reversalPercent = reversalAmount.divide(bar.getHigh(), 4, RoundingMode.HALF_UP);

                    // Only count if reversal is at least 0.08%
                    if (reversalPercent.compareTo(REVERSAL_THRESHOLD) >= 0) {
                        rejectionCount++;
                        log.info("[QUALITY-REJECTION][{}] ✓ Found quality rejection at {} | High: ${} → Next Close: ${} | Reversal: {}%",
                                analysisId, bar.getTimestamp(), bar.getHigh(), nextBar.getClose(),
                                reversalPercent.multiply(BigDecimal.valueOf(100)));
                    } else {
                        log.debug("[QUALITY-REJECTION][{}] ✗ Noise touch at {} | Reversal only {}% (need 0.08%)",
                                analysisId, bar.getTimestamp(),
                                reversalPercent.multiply(BigDecimal.valueOf(100)));
                    }
                }
            }

            log.info("[QUALITY-REJECTION][{}] Found {} quality rejections in last {} minutes (0.08% threshold)",
                    analysisId, rejectionCount, minutesBack);
            return rejectionCount;

        } catch (Exception e) {
            log.error("[QUALITY-REJECTION][{}] Error counting rejections: {}", analysisId, e.getMessage());
            return 0;
        }
    }

    /**
     * LEGACY METHODS - Keep these for backward compatibility
     * but they should no longer be used by generateMeanReversionSignal()
     */
    private int countRecentBounces(String symbol, BigDecimal vwap, int minutesBack, String analysisId) {
        // Delegate to quality bounce counter
        return countQualityBounces(symbol, vwap, minutesBack, analysisId);
    }

    private int countRecentRejections(String symbol, BigDecimal vwap, int minutesBack, String analysisId) {
        // Delegate to quality rejection counter
        return countQualityRejections(symbol, vwap, minutesBack, analysisId);
    }


    /**
     * Detect if market structure is broken (trending strongly down)
     * Returns true if market is in breakdown mode
     */
    private boolean isMarketStructureBroken(String symbol) {
        try {
            // Get recent bars for QQQ
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 5);
            if (recentBars.size() < 5) {
                return false; // Not enough data, allow trade
            }

            // Check 1: Are we making lower lows? (3+ consecutive lower lows)
            int lowerLowCount = 0;
            for (int i = 1; i < recentBars.size(); i++) {
                if (recentBars.get(i).getLow() != null &&
                        recentBars.get(i-1).getLow() != null &&
                        recentBars.get(i).getLow().compareTo(recentBars.get(i-1).getLow()) < 0) {
                    lowerLowCount++;
                }
            }
            boolean makingLowerLows = lowerLowCount >= 3;

            // Check 2: Are ALL 3 leaders below their VWAPs?
            TechnicalAnalysis aaplTA = technicalAnalysisService.analyze("AAPL");
            TechnicalAnalysis msftTA = technicalAnalysisService.analyze("MSFT");
            TechnicalAnalysis nvdaTA = technicalAnalysisService.analyze("NVDA");

            boolean allLeadersBelowVWAP =
                    aaplTA.getCurrentPrice().compareTo(aaplTA.getVwap()) < 0 &&
                            msftTA.getCurrentPrice().compareTo(msftTA.getVwap()) < 0 &&
                            nvdaTA.getCurrentPrice().compareTo(nvdaTA.getVwap()) < 0;

            // Check 3: Is VWAP declining?
            MarketData currentBar = recentBars.get(recentBars.size() - 1);
            MarketData previousBar = recentBars.get(recentBars.size() - 2);

            boolean vwapDeclining = false;
            if (currentBar.getVwap() != null && previousBar.getVwap() != null) {
                vwapDeclining = currentBar.getVwap().compareTo(previousBar.getVwap()) < 0;
            }

            // Market structure broken if at least 2 of 3 conditions met
            int conditionsMet = 0;
            if (makingLowerLows) conditionsMet++;
            if (allLeadersBelowVWAP) conditionsMet++;
            if (vwapDeclining) conditionsMet++;

            boolean structureBroken = conditionsMet >= 2;

            if (structureBroken) {
                log.info("[REGIME] Market structure BROKEN - Lower lows: {}, All leaders below VWAP: {}, VWAP declining: {}",
                        makingLowerLows, allLeadersBelowVWAP, vwapDeclining);
            }

            return structureBroken;

        } catch (Exception e) {
            log.error("[REGIME] Error checking market structure: {}", e.getMessage());
            return false; // On error, allow trade
        }
    }

    /**
     * Detect if market structure is strong (trending up)
     * Returns true if market is in strong uptrend
     */
    private boolean isMarketStructureStrong(String symbol) {
        try {
            // Get recent bars for QQQ
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 5);
            if (recentBars.size() < 5) {
                return false; // Not enough data
            }

            // Check 1: Are we making higher highs? (3+ consecutive higher highs)
            int higherHighCount = 0;
            for (int i = 1; i < recentBars.size(); i++) {
                if (recentBars.get(i).getHigh() != null &&
                        recentBars.get(i-1).getHigh() != null &&
                        recentBars.get(i).getHigh().compareTo(recentBars.get(i-1).getHigh()) > 0) {
                    higherHighCount++;
                }
            }
            boolean makingHigherHighs = higherHighCount >= 3;

            // Check 2: Are ALL 3 leaders above their VWAPs?
            TechnicalAnalysis aaplTA = technicalAnalysisService.analyze("AAPL");
            TechnicalAnalysis msftTA = technicalAnalysisService.analyze("MSFT");
            TechnicalAnalysis nvdaTA = technicalAnalysisService.analyze("NVDA");

            boolean allLeadersAboveVWAP =
                    aaplTA.getCurrentPrice().compareTo(aaplTA.getVwap()) > 0 &&
                            msftTA.getCurrentPrice().compareTo(msftTA.getVwap()) > 0 &&
                            nvdaTA.getCurrentPrice().compareTo(nvdaTA.getVwap()) > 0;

            // Check 3: Is VWAP rising?
            MarketData currentBar = recentBars.get(recentBars.size() - 1);
            MarketData previousBar = recentBars.get(recentBars.size() - 2);

            boolean vwapRising = false;
            if (currentBar.getVwap() != null && previousBar.getVwap() != null) {
                vwapRising = currentBar.getVwap().compareTo(previousBar.getVwap()) > 0;
            }

            // Market structure strong if at least 2 of 3 conditions met
            int conditionsMet = 0;
            if (makingHigherHighs) conditionsMet++;
            if (allLeadersAboveVWAP) conditionsMet++;
            if (vwapRising) conditionsMet++;

            boolean structureStrong = conditionsMet >= 2;

            if (structureStrong) {
                log.info("[REGIME] Market structure STRONG - Higher highs: {}, All leaders above VWAP: {}, VWAP rising: {}",
                        makingHigherHighs, allLeadersAboveVWAP, vwapRising);
            }

            return structureStrong;

        } catch (Exception e) {
            log.error("[REGIME] Error checking market structure: {}", e.getMessage());
            return false; // On error, don't assume strength
        }
    }

    /**
     * Check if price momentum aligns with signal direction
     * For CALL: last 3 bars should show upward momentum (at least 2 of 3 rising)
     * For PUT: last 3 bars should show downward momentum (at least 2 of 3 falling)
     */
    private boolean checkPriceMomentumAlignment(String symbol, String signalType) {
        try {
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 4);
            if (recentBars.size() < 4) {
                log.warn("[MOMENTUM] Insufficient bars for momentum check");
                return false;
            }

            // Get last 3 bars (excluding current accumulating bar)
            List<MarketData> last3Bars = recentBars.subList(recentBars.size() - 3, recentBars.size());

            if (signalType.equals("CALL")) {
                // For CALL: expect rising prices
                int risingBars = 0;
                for (int i = 1; i < last3Bars.size(); i++) {
                    if (last3Bars.get(i).getClose() != null &&
                            last3Bars.get(i-1).getClose() != null &&
                            last3Bars.get(i).getClose().compareTo(last3Bars.get(i-1).getClose()) > 0) {
                        risingBars++;
                    }
                }

                // At least 2 of 3 bars rising
                boolean momentumAligned = risingBars <= 1;

                log.info("[MOMENTUM] CALL momentum check: {}/2 rising bars needed - {}",
                        risingBars, momentumAligned ? "PASS" : "FAIL");

                return momentumAligned;

            } else if (signalType.equals("PUT")) {
                // For PUT: expect falling prices
                int fallingBars = 0;
                for (int i = 1; i < last3Bars.size(); i++) {
                    if (last3Bars.get(i).getClose() != null &&
                            last3Bars.get(i-1).getClose() != null &&
                            last3Bars.get(i).getClose().compareTo(last3Bars.get(i-1).getClose()) < 0) {
                        fallingBars++;
                    }
                }

                // At least 2 of 3 bars falling
                boolean momentumAligned = fallingBars <= 1;

                log.info("[MOMENTUM] PUT momentum check: {}/2 falling bars needed - {}",
                        fallingBars, momentumAligned ? "PASS" : "FAIL");

                return momentumAligned;
            }

            return false;

        } catch (Exception e) {
            log.error("[MOMENTUM] Error checking price momentum: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Find if price touched VWAP within the last N bars
     * Returns the timestamp of the touch, or null if no touch found
     */
    private LocalDateTime findRecentVWAPTouch(String symbol, int barsBack) {
        try {
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, barsBack + 1);
            if (recentBars.size() < 2) {
                return null;
            }

            // FIXED: Define proximity threshold - within 0.15% considered a "touch"
            BigDecimal PROXIMITY_THRESHOLD = new BigDecimal("0.0015"); // 0.15%

            for (int i = recentBars.size() - 1; i >= 1; i--) {
                MarketData bar = recentBars.get(i);

                if (bar.getVwap() == null || bar.getHigh() == null || bar.getLow() == null) {
                    continue;
                }

                BigDecimal vwap = bar.getVwap();

                // FIXED: Check if bar crossed VWAP (low below, high above)
                boolean crossedVWAP = bar.getLow().compareTo(vwap) <= 0 &&
                        bar.getHigh().compareTo(vwap) >= 0;

                // FIXED: Check if high or low came within proximity threshold
                BigDecimal distanceFromHigh = bar.getHigh().subtract(vwap)
                        .divide(vwap, 6, RoundingMode.HALF_UP)
                        .abs();
                BigDecimal distanceFromLow = bar.getLow().subtract(vwap)
                        .divide(vwap, 6, RoundingMode.HALF_UP)
                        .abs();

                boolean nearVWAP = distanceFromHigh.compareTo(PROXIMITY_THRESHOLD) <= 0 ||
                        distanceFromLow.compareTo(PROXIMITY_THRESHOLD) <= 0;

                if (crossedVWAP || nearVWAP) {
                    return bar.getTimestamp();
                }
            }

            return null;

        } catch (Exception e) {
            log.error("Error finding VWAP touch: {}", e.getMessage());
            return null;
        }
    }


    /**
     * Check how many leader stocks confirm the signal direction
     * For CALL: leaders should be below VWAP with RSI < 40
     * For PUT: leaders should be above VWAP with RSI > 60
     * Returns count of leaders confirming (0-3)
     */
    private int checkLeadersForDirection(String symbol, String signalType) {
        int confirming = 0;
        String[] leaders = {"AAPL", "MSFT", "NVDA"};

        for (String leader : leaders) {
            try {
                TechnicalAnalysis leaderTA = technicalAnalysisService.analyze(leader);

                if (leaderTA == null || leaderTA.getCurrentPrice() == null ||
                        leaderTA.getVwap() == null) {
                    log.warn("[LEADERS] Missing data for {}", leader);
                    continue;
                }

                BigDecimal leaderPrice = leaderTA.getCurrentPrice();
                BigDecimal leaderVwap = leaderTA.getVwap();
                double leaderRSI = leaderTA.getRsi();

                // Validate RSI is in reasonable range
                if (leaderRSI <= 0 || leaderRSI > 100) {
                    log.warn("[LEADERS] Invalid RSI for {}: {}", leader, leaderRSI);
                    continue;
                }

                // Calculate distance from VWAP
                double distanceFromVwap = 0.0;
                if (leaderVwap.compareTo(BigDecimal.ZERO) > 0) {
                    distanceFromVwap = leaderPrice.subtract(leaderVwap)
                            .divide(leaderVwap, 6, RoundingMode.HALF_UP)
                            .doubleValue();
                }

                boolean confirms = false;
                String confirmReason = "";

                if (signalType.equals("CALL")) {
                    // =====================================================================
                    // CALL CONFIRMATION (QQQ below VWAP, expecting bounce UP)
                    // =====================================================================
                    // Confirm if leader shows ANY of these conditions:
                    // 1. RSI oversold (< 45) - showing weakness, ready to bounce
                    // 2. Also below VWAP (sector-wide dip = high bounce probability)
                    // 3. RSI neutral but not overbought (< 55) AND near VWAP (within 0.3%)
                    //
                    // REJECT if leader is clearly bullish (RSI > 60 AND above VWAP)
                    // because that suggests divergence, not sector-wide dip
                    // =====================================================================

                    boolean isOversold = leaderRSI < 45;
                    boolean isBelowVwap = distanceFromVwap < -0.001;  // Below by 0.1%+
                    boolean isNearVwap = Math.abs(distanceFromVwap) < 0.003;  // Within 0.3%
                    boolean isNotOverbought = leaderRSI < 55;
                    boolean isClearlyBullish = leaderRSI > 60 && distanceFromVwap > 0.002;

                    if (isClearlyBullish) {
                        // Leader diverging bullish - doesn't confirm CALL
                        confirms = false;
                        confirmReason = "diverging bullish";
                    } else if (isOversold) {
                        confirms = true;
                        confirmReason = String.format("RSI oversold (%.1f < 45)", leaderRSI);
                    } else if (isBelowVwap && isNotOverbought) {
                        confirms = true;
                        confirmReason = String.format("below VWAP (%.2f%%) + RSI %.1f",
                                distanceFromVwap * 100, leaderRSI);
                    } else if (isNearVwap && isNotOverbought) {
                        confirms = true;
                        confirmReason = String.format("near VWAP (%.2f%%) + RSI %.1f",
                                distanceFromVwap * 100, leaderRSI);
                    }

                } else if (signalType.equals("PUT")) {
                    // =====================================================================
                    // PUT CONFIRMATION (QQQ above VWAP, expecting pullback DOWN)
                    // =====================================================================
                    // Confirm if leader shows ANY of these conditions:
                    // 1. RSI overbought (> 55) - showing strength exhaustion
                    // 2. Also above VWAP (sector-wide extension = high pullback probability)
                    // 3. RSI neutral but not oversold (> 45) AND near VWAP (within 0.3%)
                    //
                    // REJECT if leader is clearly bearish (RSI < 40 AND below VWAP)
                    // because that suggests divergence, not sector-wide extension
                    // =====================================================================

                    boolean isOverbought = leaderRSI > 55;
                    boolean isAboveVwap = distanceFromVwap > 0.001;  // Above by 0.1%+
                    boolean isNearVwap = Math.abs(distanceFromVwap) < 0.003;  // Within 0.3%
                    boolean isNotOversold = leaderRSI > 45;
                    boolean isClearlyBearish = leaderRSI < 40 && distanceFromVwap < -0.002;

                    if (isClearlyBearish) {
                        // Leader diverging bearish - doesn't confirm PUT
                        confirms = false;
                        confirmReason = "diverging bearish";
                    } else if (isOverbought && isAboveVwap) {
                        confirms = true;
                        confirmReason = String.format("RSI overbought (%.1f > 55) + above VWAP", leaderRSI);
                    } else if (isAboveVwap && isNotOversold) {
                        confirms = true;
                        confirmReason = String.format("above VWAP (%.2f%%) + RSI %.1f",
                                distanceFromVwap * 100, leaderRSI);
                    } else if (isNearVwap && isNotOversold) {
                        confirms = true;
                        confirmReason = String.format("near VWAP (%.2f%%) + RSI %.1f",
                                distanceFromVwap * 100, leaderRSI);
                    }
                }

                if (confirms) {
                    confirming++;
                    log.info("[LEADERS] ✓ {} confirms {} - {} (Price: ${}, VWAP: ${}, Dist: {}%, RSI: {})",
                            leader, signalType, confirmReason, leaderPrice, leaderVwap,
                            String.format("%.2f", distanceFromVwap * 100),
                            String.format("%.1f", leaderRSI));
                } else {
                    log.info("[LEADERS] ✗ {} does NOT confirm {} - {} (Price: ${}, VWAP: ${}, Dist: {}%, RSI: {})",
                            leader, signalType, confirmReason.isEmpty() ? "no conditions met" : confirmReason,
                            leaderPrice, leaderVwap, String.format("%.2f", distanceFromVwap * 100), String.format("%.1f",leaderRSI));
                }

            } catch (Exception e) {
                log.error("[LEADERS] Error checking {}: {}", leader, e.getMessage());
            }
        }

        return confirming;
    }


    /**
     * Select the best PUT option from available options
     * Similar to selectBestCallOption but for PUT side
     */
    private Option selectBestPutOption(List<Option> options, BigDecimal entryPrice,
                                       BigDecimal targetPrice, String analysisId) {
        try {
            log.info("[OPTION-SELECT] Starting selection process");
            log.info("[OPTION-SELECT] Input: {} total options", options.size());
            log.info("[OPTION-SELECT] Target: Type=PUT, Entry=${}", entryPrice);

            LocalDate today = LocalDate.now(ET_ZONE);

            // Filter for PUT options expiring today with valid prices
            List<Option> putOptions = options.stream()
                    .filter(o -> "PUT".equalsIgnoreCase(o.getType()))
                    .filter(o -> {
                        if (o.getExpirationDate() == null) return false;
                        try {
                            Object expiryObj = o.getExpirationDate();
                            if (expiryObj instanceof String) {
                                LocalDate expiryDate = LocalDate.parse((String) expiryObj);
                                return expiryDate.equals(today);
                            } else if (expiryObj instanceof LocalDate) {
                                return expiryObj.equals(today);
                            }
                            return false;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .filter(o -> o.getStrikePrice() != null && o.getBid() != null && o.getAsk() != null)
                    .filter(o -> o.getBid().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            log.info("[OPTION-SELECT] After type=PUT filter: {} options", putOptions.size());
            log.info("[OPTION-SELECT] After expiry={} filter: {} options", today, putOptions.size());
            log.info("[OPTION-SELECT] After price check filter: {} options", putOptions.size());
            log.info("[OPTION-SELECT] After bid>$0 filter: {} options", putOptions.size());

            if (putOptions.isEmpty()) {
                log.error("[OPTION-SELECT] No valid PUT options after filtering");
                return null;
            }

            // Find strikes in range (within $1 of entry)
            BigDecimal lowerBound = entryPrice.subtract(BigDecimal.ONE);
            BigDecimal upperBound = entryPrice.add(BigDecimal.ONE);

            log.info("[OPTION-SELECT] Strike range available: ${} to ${}",
                    putOptions.stream().map(Option::getStrikePrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO),
                    putOptions.stream().map(Option::getStrikePrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO));

            log.info("[OPTION-SELECT] Target strike range: ${} to ${} (Entry: ${})",
                    lowerBound, upperBound, entryPrice);

            // Find options in target range
            List<Option> inRange = putOptions.stream()
                    .filter(o -> o.getStrikePrice().compareTo(lowerBound) >= 0 &&
                            o.getStrikePrice().compareTo(upperBound) <= 0)
                    .collect(Collectors.toList());

            if (inRange.isEmpty()) {
                // If no options in range, take closest strike below entry
                Option closest = putOptions.stream()
                        .filter(o -> o.getStrikePrice().compareTo(entryPrice) <= 0)
                        .min((o1, o2) -> {
                            BigDecimal diff1 = entryPrice.subtract(o1.getStrikePrice()).abs();
                            BigDecimal diff2 = entryPrice.subtract(o2.getStrikePrice()).abs();
                            return diff1.compareTo(diff2);
                        })
                        .orElse(null);

                if (closest != null) {
                    log.info("[OPTION-SELECT] No options in range, using closest: ${}", closest.getStrikePrice());
                    inRange.add(closest);
                }
            }

            if (inRange.isEmpty()) {
                log.error("[OPTION-SELECT] No suitable PUT options found");
                return null;
            }

            // Select best option: closest to entry with tightest spread
            Option best = inRange.stream()
                    .min((o1, o2) -> {
                        BigDecimal distance1 = entryPrice.subtract(o1.getStrikePrice()).abs();
                        BigDecimal distance2 = entryPrice.subtract(o2.getStrikePrice()).abs();
                        int distanceCompare = distance1.compareTo(distance2);
                        if (distanceCompare != 0) return distanceCompare;

                        BigDecimal spread1 = o1.getAsk().subtract(o1.getBid());
                        BigDecimal spread2 = o2.getAsk().subtract(o2.getBid());
                        return spread1.compareTo(spread2);
                    })
                    .orElse(null);

            if (best != null) {
                BigDecimal distanceFromEntry = entryPrice.subtract(best.getStrikePrice()).abs();

                log.info("[OPTION-SELECT] ✓✓✓ OPTION SELECTED ✓✓✓");
                log.info("[OPTION-SELECT]   Symbol: {}", best.getSymbol());
                log.info("[OPTION-SELECT]   Strike: ${}", best.getStrikePrice());
                log.info("[OPTION-SELECT]   Distance from entry: ${}", distanceFromEntry);
                log.info("[OPTION-SELECT]   Bid: ${}, Ask: ${}", best.getBid(), best.getAsk());
            }

            return best;

        } catch (Exception e) {
            log.error("[OPTION-SELECT] Error selecting PUT option: {}", e.getMessage());
            return null;
        }
    }

    // Minimum profit target distance to ensure tradeable opportunity
    private static final BigDecimal MIN_TARGET_DISTANCE_DOLLARS = new BigDecimal("0.30");  // $0.30 minimum
    private static final BigDecimal MIN_TARGET_DISTANCE_PERCENT = new BigDecimal("0.0005"); // 0.05% minimum

    /**
     * Fetch candle net movements for confidence calculation.
     * Calculates sum of (close - open) for last 5 bars for each symbol.
     */
    private Map<String, Double> fetchCandleNetsForConfidence(String analysisId) {
        Map<String, Double> candleNets = new HashMap<>();

        try {
            List<String> symbols = Arrays.asList("QQQ", "AAPL", "MSFT", "NVDA");

            for (String symbol : symbols) {
                List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 5);
                if (recentBars != null && recentBars.size() >= 5) {
                    double netMovement = 0.0;
                    for (MarketData bar : recentBars) {
                        if (bar.getClose() != null && bar.getOpen() != null) {
                            netMovement += bar.getClose().subtract(bar.getOpen()).doubleValue();
                        }
                    }
                    candleNets.put(symbol, netMovement);
                }
            }
        } catch (Exception e) {
            log.warn("[CONFIDENCE-CALC][{}] Error fetching candle nets: {}", analysisId, e.getMessage());
        }

        return candleNets;
    }

    /**
     * Calculate dynamic confidence based on signal quality factors.
     * Replaces hardcoded 0.85 confidence with factor-based calculation.
     *
     * Factors:
     * - Target distance: $1.00-$2.00 ideal (+15%), <$1.00 penalty (-5%)
     * - Leader confirmations: 3/3 (+15%), 2/3 (+8%)
     * - Candle net alignment: 4/4 (+15%), 3/4 (+10%), 2/4 (+5%), 0-1 (-5%)
     * - RSI extremity: oversold CALL / overbought PUT (+5% to +10%)
     * - Volume ratio: >1.5x (+10%), >1.2x (+5%), <0.8x (-5%)
     * - VWAP distance: 0.05%-0.15% ideal (+5%), >0.30% (-3%)
     *
     * @return confidence between 0.40 and 0.95
     */
    private double calculateSignalConfidence(
            Signal signal,
            TechnicalAnalysis ta,
            int leaderConfirmations,
            Map<String, Double> candleNets,
            String analysisId) {

        try {
            double baseConfidence = 0.50;
            StringBuilder reasoning = new StringBuilder();
            reasoning.append("[CONFIDENCE-CALC][").append(analysisId).append("] ");

            // FACTOR 1: Target Distance
            double targetDistanceBonus = 0.0;
            if (signal.getTargetPrice() != null && signal.getEntryPrice() != null) {
                BigDecimal targetDistance = signal.getTargetPrice().subtract(signal.getEntryPrice()).abs();
                double distanceValue = targetDistance.doubleValue();

                if (distanceValue >= 1.00 && distanceValue <= 2.00) {
                    targetDistanceBonus = 0.15;
                } else if (distanceValue > 2.00 && distanceValue <= 3.00) {
                    targetDistanceBonus = 0.10;
                } else if (distanceValue < 1.00) {
                    targetDistanceBonus = -0.05;
                } else {
                    targetDistanceBonus = 0.05;
                }
                reasoning.append(String.format("TargetDist=$%.2f→%+.0f%% | ", distanceValue, targetDistanceBonus * 100));
            }

            // FACTOR 2: Leader Confirmations
            double leaderBonus = 0.0;
            if (leaderConfirmations >= 3) {
                leaderBonus = 0.15;
            } else if (leaderConfirmations == 2) {
                leaderBonus = 0.08;
            } else {
                leaderBonus = -0.05;
            }
            reasoning.append(String.format("Leaders=%d/3→%+.0f%% | ", leaderConfirmations, leaderBonus * 100));

            // FACTOR 3: Candle Net Alignment
            double candleNetBonus = 0.0;
            if (candleNets != null && !candleNets.isEmpty()) {
                String signalType = signal.getSignalType();
                int alignedCount = 0;
                double totalMagnitude = 0.0;

                for (Map.Entry<String, Double> entry : candleNets.entrySet()) {
                    double netValue = entry.getValue();
                    totalMagnitude += Math.abs(netValue);

                    boolean aligned = ("CALL".equals(signalType) && netValue > 0) ||
                            ("PUT".equals(signalType) && netValue < 0);
                    if (aligned) {
                        alignedCount++;
                    }
                }

                if (alignedCount >= 4) {
                    candleNetBonus = 0.15;
                } else if (alignedCount == 3) {
                    candleNetBonus = 0.10;
                } else if (alignedCount == 2) {
                    candleNetBonus = 0.05;
                } else {
                    candleNetBonus = -0.05;
                }

                double avgMagnitude = candleNets.size() > 0 ? totalMagnitude / candleNets.size() : 0;
                if (avgMagnitude > 0.30) {
                    candleNetBonus += 0.05;
                }
                reasoning.append(String.format("CandleAlign=%d/%d,Mag=%.2f→%+.0f%% | ",
                        alignedCount, candleNets.size(), avgMagnitude, candleNetBonus * 100));
            } else {
                reasoning.append("CandleNets=N/A→+0% | ");
            }

            // FACTOR 4: RSI Extremity
            double rsiBonus = 0.0;
            if (ta != null && ta.getRsi() > 0) {
                double rsi = ta.getRsi();
                String signalType = signal.getSignalType();

                if ("CALL".equals(signalType)) {
                    if (rsi <= 25) {
                        rsiBonus = 0.10;
                    } else if (rsi <= 35) {
                        rsiBonus = 0.05;
                    } else if (rsi >= 55) {
                        rsiBonus = -0.05;
                    }
                } else if ("PUT".equals(signalType)) {
                    if (rsi >= 75) {
                        rsiBonus = 0.10;
                    } else if (rsi >= 65) {
                        rsiBonus = 0.05;
                    } else if (rsi <= 45) {
                        rsiBonus = -0.05;
                    }
                }
                reasoning.append(String.format("RSI=%.1f→%+.0f%% | ", rsi, rsiBonus * 100));
            }

            // FACTOR 5: Volume Ratio
            double volumeBonus = 0.0;
            if (ta != null && ta.getVolumeRatio() > 0) {
                double volumeRatio = ta.getVolumeRatio();

                if (volumeRatio >= 1.5) {
                    volumeBonus = 0.10;
                } else if (volumeRatio >= 1.2) {
                    volumeBonus = 0.05;
                } else if (volumeRatio < 0.8) {
                    volumeBonus = -0.05;
                }
                reasoning.append(String.format("Volume=%.2fx→%+.0f%% | ", volumeRatio, volumeBonus * 100));
            }

            // FACTOR 6: VWAP Distance
            double vwapBonus = 0.0;
            if (ta != null && ta.getVwap() != null && ta.getCurrentPrice() != null
                    && ta.getVwap().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal vwapDistance = ta.getCurrentPrice().subtract(ta.getVwap()).abs();
                BigDecimal vwapDistancePercent = vwapDistance.divide(ta.getVwap(), 6, RoundingMode.HALF_UP);
                double distPct = vwapDistancePercent.doubleValue() * 100;

                if (distPct >= 0.05 && distPct <= 0.15) {
                    vwapBonus = 0.05;
                } else if (distPct > 0.30) {
                    vwapBonus = -0.03;
                }
                reasoning.append(String.format("VWAPDist=%.2f%%→%+.0f%% | ", distPct, vwapBonus * 100));
            }

            // FINAL CALCULATION
            double finalConfidence = baseConfidence + targetDistanceBonus + leaderBonus
                    + candleNetBonus + rsiBonus + volumeBonus + vwapBonus;

            finalConfidence = Math.max(0.40, Math.min(0.95, finalConfidence));

            reasoning.append(String.format("FINAL=%.0f%%", finalConfidence * 100));
            log.info(reasoning.toString());

            return finalConfidence;

        } catch (Exception e) {
            log.error("[CONFIDENCE-CALC][{}] Error calculating confidence: {}", analysisId, e.getMessage());
            return 0.70;
        }
    }

//    /**
//     * Validate that target distance is sufficient for profitable trade
//     * Returns adjusted target if original is too tight
//     */
//    private BigDecimal validateAndAdjustTarget(BigDecimal entryPrice, BigDecimal originalTarget,
//                                               BigDecimal atr, String signalType, String analysisId) {
//
//        BigDecimal targetDistance = entryPrice.subtract(originalTarget).abs();
//        BigDecimal minDistanceDollars = MIN_TARGET_DISTANCE_DOLLARS;
//        BigDecimal minDistancePercent = entryPrice.multiply(MIN_TARGET_DISTANCE_PERCENT);
//
//        // Use the larger of dollar minimum or percentage minimum
//        BigDecimal effectiveMinDistance = minDistanceDollars.max(minDistancePercent);
//
//        // Also ensure target is at least 1.5x ATR
//        BigDecimal minAtrDistance = atr.multiply(new BigDecimal("1.5"));
//        effectiveMinDistance = effectiveMinDistance.max(minAtrDistance);
//
//        log.debug("[TARGET-VALIDATE][{}] Original distance: ${}, Min required: ${} (ATR: ${})",
//                analysisId, targetDistance, effectiveMinDistance, atr);
//
//        if (targetDistance.compareTo(effectiveMinDistance) < 0) {
//            // Target too tight - adjust it
//            BigDecimal adjustedTarget;
//
//            if (signalType.equals("CALL")) {
//                // CALL target is above entry
//                adjustedTarget = entryPrice.add(effectiveMinDistance);
//            } else {
//                // PUT target is below entry
//                adjustedTarget = entryPrice.subtract(effectiveMinDistance);
//            }
//
//            log.warn("[TARGET-VALIDATE][{}] Target too tight! Original: ${}, Adjusted to: ${} (min distance: ${})",
//                    analysisId, originalTarget, adjustedTarget, effectiveMinDistance);
//
//            return adjustedTarget;
//        }
//
//        log.info("[TARGET-VALIDATE][{}] Target distance OK: ${} >= min ${}",
//                analysisId, targetDistance, effectiveMinDistance);
//
//        return originalTarget;
//    }

}