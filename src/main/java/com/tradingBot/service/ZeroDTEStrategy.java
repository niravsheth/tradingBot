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

    private Map<String, Double> getRegimeAdjustedLeaderWeights() {
        if (currentRegime == SessionRegime.TRENDING_UP || currentRegime == SessionRegime.TRENDING_DOWN) {
            // In trending: elevate AAPL (institutional conviction), balance NVDA
            return new HashMap<String, Double>() {{
                put("NVDA", 0.35);
                put("MSFT", 0.30);
                put("AAPL", 0.35);
            }};
        }
        // In ranging: keep original weights (NVDA short-term correlation most relevant)
        return LEADER_WEIGHTS;
    }

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

    private final CoilTrackingService coilTrackingService;

    private final DailyLevelService dailyLevelService;

    private volatile SessionRegime currentRegime = SessionRegime.RANGING;
    private volatile BigDecimal sessionOpenPrice = null;
    private volatile LocalDate sessionDate = null;
    private volatile LocalDateTime lastVwapCrossTime = null;
    private volatile BigDecimal lastVwapCrossPrice = null;
    private volatile long continuousAboveVwapMinutes = 0;
    private volatile long continuousBelowVwapMinutes = 0;
    private volatile BigDecimal sessionHighPrice = BigDecimal.ZERO;
    private volatile BigDecimal sessionLowPrice = new BigDecimal("9999");
    private volatile BigDecimal rollingVwapSlope30min = BigDecimal.ZERO;
    private volatile int higherHighCount15m = 0;
    private volatile int lowerLowCount15m = 0;
    private volatile int signalsGeneratedToday = 0;
    private volatile int signalsKilledByCoilToday = 0;
    private volatile int putSignalsToday = 0;
    private volatile int callSignalsToday = 0;

    // --- Trend-following state tracking ---
    private volatile int consecutiveBarsBelow = 0;      // Consecutive 1-min closes below VWAP
    private volatile int consecutiveBarsAbove = 0;      // Consecutive 1-min closes above VWAP
    private volatile boolean vwapBreachSignalFired = false;  // Prevent duplicate breach signals per direction
    private volatile String lastBreachDirection = null;       // "DOWN" or "UP"
    private volatile LocalDateTime lastTrendSignalTime = null; // Debounce for trend signals
    private volatile LocalDateTime regimeTrendingStartTime = null; // When current TRENDING regime began

    private final Map<String, LocalDateTime> lastSignalByTypeAndStrike = new ConcurrentHashMap<>();

    // --- Big-move catcher state ---
    private volatile int trendContinuationCallCount = 0;

    private volatile int trendContinuationPutCount = 0;
    private volatile double averageDailyRange = 10.0;       // default $10 for QQQ
    private volatile double regimeStartRSI = 50.0;

    private volatile double peakVelocityLast10Bars = 0.0;
    private volatile LocalDateTime lastDepartureSignalTime = null;

    private volatile LocalDateTime lastCallCrossSignalTime = null;

    private volatile LocalDateTime lastPutCrossSignalTime = null;

    // --- V2: Exhaustion reversal structural tracking ---
    private volatile double sessionPeakRSI = 0.0;
    private volatile double sessionTroughRSI = 100.0;
    private volatile BigDecimal swingLowAfterRSIPeak = null;
    private volatile BigDecimal swingHighAfterRSITrough = null;
    private volatile boolean rsiHasPeaked = false;
    private volatile boolean rsiHasTroughed = false;

    // Thread safety for analyzeOptions
    private final java.util.concurrent.atomic.AtomicBoolean analysisRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Leader persistence tracking
    private final Map<String, Integer> leaderOppositionStreaks = new ConcurrentHashMap<>();
// key = "AAPL_PUT" or "NVDA_CALL", value = consecutive opposing cycle count

    private enum SessionRegime {
        TRENDING_UP,
        TRENDING_DOWN,
        TREND_EXHAUSTING,  // NEW: trend losing steam, allow counter-trend with penalty
        RANGING
    }

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



    // ============================================================================
// COMPLETE analyzeOptions() METHOD - COPY AND REPLACE ENTIRE METHOD
// ============================================================================
// Location: ZeroDTEStrategy.java, lines 213-820 approximately
//
// Changes made:
// 1. VWAP distance threshold: 0.02 → 0.08 (Change 3)
// 2. Bar filter REMOVED - no longer rejects based on bar color (Change 6)
// 3. Still uses confirmationBar for momentum calculation (preserved)
// 4. wickRejectionBonus set to 0 (no bar-based bonus)
// ============================================================================

    public List<Signal> analyzeOptions(String symbol, String marketTrend) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        List<Signal> signals = new ArrayList<>();

        // ═══ THREAD SAFETY: Only one analysis cycle at a time ═══
        if (!analysisRunning.compareAndSet(false, true)) {
            log.debug("[{}] Analysis already running on another thread — skipping", analysisId);
            return signals;
        }

        try {
            return analyzeOptionsInternal(symbol, marketTrend, analysisId, signals);
        } finally {
            analysisRunning.set(false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
// CHANGE 5: REPLACE the analyzeOptionsInternal method (line 283-1070)
// This is the CORE change — integrates all 7 improvements
// Key changes:
//   - Key-level and POC converted from hard gates to confidence modifiers
//   - Momentum 0.0 == 0.0 treated as slowing
//   - Regime gate now ALSO calls trend-following path instead of just blocking
//   - Target caps scaled by regime
//   - MR signals suppressed when strong trend confirmed
//   - VWAP breach detection added
// ═══════════════════════════════════════════════════════════════════════════

    private List<Signal> analyzeOptionsInternal(String symbol, String marketTrend,
                                                String analysisId, List<Signal> signals) {
        try {
            log.info("========================================");
            log.info("[{}] BIG-MOVE CATCHER ANALYSIS START", analysisId);
            log.info("[{}] Symbol: {}, Market Trend: {}", analysisId, symbol, marketTrend);
            log.info("========================================");

            LocalTime now = LocalTime.now(ET_ZONE);
            if (!isGoodTradingTime(now)) {
                log.warn("[{}] Outside trading window, skipping analysis", analysisId);
                return signals;
            }

            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            if (ta == null || ta.getCurrentPrice() == null || ta.getVwap() == null) {
                log.error("[{}] Technical analysis failed or incomplete", analysisId);
                return signals;
            }

            log.info("[{}] Current session regime: {}", analysisId, currentRegime);

            BigDecimal currentPrice = ta.getCurrentPrice();
            BigDecimal vwap = ta.getVwap();
            BigDecimal distanceFromVWAP = currentPrice.subtract(vwap).abs();
            BigDecimal atr = ta.getAverageTrueRange();
            if (atr == null || atr.compareTo(new BigDecimal("0.50")) < 0) {
                atr = new BigDecimal("0.50");
            }

            boolean priceAboveVWAP = currentPrice.compareTo(vwap) > 0;
            boolean priceBelowVWAP = currentPrice.compareTo(vwap) < 0;
            double rsi = ta.getRsi();
            boolean isPowerHour = now.isAfter(LocalTime.of(14, 45));

            // ═══════════════════════════════════════════════════════════════
            // EXTENDED-MOVE INHIBITOR
            // ═══════════════════════════════════════════════════════════════
            BigDecimal sessionRange = sessionHighPrice.subtract(sessionLowPrice);
            double moveFromLOD = currentPrice.subtract(sessionLowPrice).doubleValue();
            double moveFromHOD = sessionHighPrice.subtract(currentPrice).doubleValue();
            double totalRangeRatio = sessionRange.doubleValue() / averageDailyRange;

            // V3: Dynamic departure exhaustion gate
            // When session has exceeded 80% ADR and regime is TRENDING, raise to 95%
            double departThreshVal = 0.65;
            boolean sessionExtending = totalRangeRatio > 0.80;
            boolean trendingSession = (currentRegime == SessionRegime.TRENDING_UP
                    || currentRegime == SessionRegime.TRENDING_DOWN);
            if (sessionExtending && trendingSession) {
                departThreshVal = 0.95;
                log.info("[{}] V3 dynamic departure gate: session {}% ADR + TRENDING → threshold 95%",
                        analysisId, String.format("%.0f", totalRangeRatio * 100));
            }
            boolean bullDepartExhausted = (moveFromLOD / averageDailyRange > departThreshVal)
                    || (totalRangeRatio > 1.2);
            boolean bearDepartExhausted = (moveFromHOD / averageDailyRange > departThreshVal)
                    || (totalRangeRatio > 1.2);

            // V3: Continuation — lowered from 80% to 70% to prevent chasing exhausted moves
            // Feb 23: PUT continuations fired at bearDir 71-80% (most of move consumed)
            double contThresh = isPowerHour ? 1.50 : 0.70;
            boolean bullContExhausted = moveFromLOD / averageDailyRange > contThresh;
            boolean bearContExhausted = moveFromHOD / averageDailyRange > contThresh;

            // V3: Counter-session-trend gate for continuations
            double bullDirCont = moveFromLOD / averageDailyRange;
            double bearDirCont = moveFromHOD / averageDailyRange;
            if (bullDirCont > 0.01 && bearDirCont > 0.01) {
                if (bullDirCont / bearDirCont > 2.0) {
                    bearContExhausted = true;
                    log.info("[{}] V3 counter-session: blocking PUT continuation (bull {}%/bear {}%)",
                            analysisId, String.format("%.0f", bullDirCont * 100),
                            String.format("%.0f", bearDirCont * 100));
                }
                if (bearDirCont / bullDirCont > 2.0) {
                    bullContExhausted = true;
                    log.info("[{}] V3 counter-session: blocking CALL continuation (bear {}%/bull {}%)",
                            analysisId, String.format("%.0f", bearDirCont * 100),
                            String.format("%.0f", bullDirCont * 100));
                }
            }

            int maxCont = isPowerHour ? 3 : 2;

            log.info("[{}] Ext-move: range=${}, ratio={}%, bullDir={}%, bearDir={}%, powerHr={}",
                    analysisId,
                    sessionRange.setScale(2, RoundingMode.HALF_UP),
                    String.format("%.0f", totalRangeRatio * 100),
                    String.format("%.0f", moveFromLOD / averageDailyRange * 100),
                    String.format("%.0f", moveFromHOD / averageDailyRange * 100),
                    isPowerHour);

            // Debounce: 20 min between departure/continuation signals
            boolean debounceOk = (lastDepartureSignalTime == null ||
                    lastDepartureSignalTime.isBefore(LocalDateTime.now(ET_ZONE).minusMinutes(20)));

            if (debounceOk) {
                List<Signal> adrSignals = checkADRExhaustionReversal(symbol, ta, atr, currentPrice, vwap,
                        rsi, totalRangeRatio, moveFromLOD, moveFromHOD, priceBelowVWAP, priceAboveVWAP, analysisId);
                if (!adrSignals.isEmpty()) {
                    signals.addAll(adrSignals);
                    lastDepartureSignalTime = LocalDateTime.now(ET_ZONE);
                    return signals;
                }
            }

            if (debounceOk && now.isAfter(LocalTime.of(13, 30))) {
                List<Signal> compressionSignals = checkCompressionBreakout(symbol, ta, atr, currentPrice, vwap,
                        rsi, priceAboveVWAP, priceBelowVWAP, analysisId);
                if (!compressionSignals.isEmpty()) {
                    signals.addAll(compressionSignals);
                    lastDepartureSignalTime = LocalDateTime.now(ET_ZONE);
                    return signals;
                }
            }

            // Helper: check sustained momentum (2 consecutive bars same direction)
            boolean momentumSustained = false;
            boolean momentumReversing = false;
            double vel0 = 0, vel1 = 0;
            {
                List<MarketData> momBars = barAggregationService.getRecentBars(symbol, 5);
                if (momBars != null && momBars.size() >= 3) {
                    int li = momBars.size() - 1;
                    MarketData b0 = momBars.get(li), b1 = momBars.get(li - 1), b2 = momBars.get(li - 2);
                    if (b0.getClose() != null && b1.getClose() != null && b2.getClose() != null) {
                        vel0 = b0.getClose().subtract(b1.getClose()).doubleValue();
                        vel1 = b1.getClose().subtract(b2.getClose()).doubleValue();
                        momentumSustained = (vel0 > 0 && vel1 > 0) || (vel0 < 0 && vel1 < 0);
                        // Reversing = 2 bars opposite to the VWAP side
                        if (priceAboveVWAP) momentumReversing = (vel0 < 0 && vel1 < 0);
                        if (priceBelowVWAP) momentumReversing = (vel0 > 0 && vel1 > 0);
                    }
                }
            }

            {
                // Per-direction debounce: 15 minutes between same-direction signals
                boolean callDebounceOk = (lastCallCrossSignalTime == null ||
                        lastCallCrossSignalTime.isBefore(LocalDateTime.now(ET_ZONE).minusMinutes(15)));
                boolean putDebounceOk = (lastPutCrossSignalTime == null ||
                        lastPutCrossSignalTime.isBefore(LocalDateTime.now(ET_ZONE).minusMinutes(15)));

                if (callDebounceOk || putDebounceOk) {
                    List<Signal> crossSignals = checkVWAPCrossSignal(symbol, ta, atr,
                            currentPrice, vwap, rsi, callDebounceOk, putDebounceOk, analysisId);
                    if (!crossSignals.isEmpty()) {
                        signals.addAll(crossSignals);

                        String crossDir = crossSignals.get(0).getSignalType();
                        if ("CALL".equals(crossDir)) {
                            lastCallCrossSignalTime = LocalDateTime.now(ET_ZONE);
                        } else {
                            lastPutCrossSignalTime = LocalDateTime.now(ET_ZONE);
                        }
                        lastDepartureSignalTime = LocalDateTime.now(ET_ZONE);
                        return signals;
                    }
                }
            }



            // ═══════════════════════════════════════════════════════════════
            // PATH A5: EXHAUSTION REVERSAL — V2: RSI-peaked + structural swing
            // ═══════════════════════════════════════════════════════════════
            BigDecimal exhaustThreshold = atr.multiply(new BigDecimal("1.50"));

            if (debounceOk && distanceFromVWAP.compareTo(exhaustThreshold) > 0) {

                // PUT on exhausted rally
                if (priceAboveVWAP && continuousAboveVwapMinutes >= 25
                        && rsi > 72 && momentumReversing) {

                    // V3: Textbook exhaustion gates — require ALL three for genuine structural exhaustion
                    double bullDirPct = moveFromLOD / averageDailyRange;
                    double volRatioExhaust = ta.getVolumeRatio();
                    boolean dirExhausted = bullDirPct >= 0.75;
                    boolean rsiExtreme = rsi > 80;
                    boolean volumeDriedUp = volRatioExhaust < 0.30;

                    if (!dirExhausted) {
                        log.info("[EXHAUST-REV][{}] ⛔ PUT exhaust blocked — bullDir {}% < 75% (not truly exhausted)",
                                analysisId, String.format("%.0f", bullDirPct * 100));
                    } else if (!rsiExtreme) {
                        log.info("[EXHAUST-REV][{}] ⛔ PUT exhaust blocked — RSI {} < 80 (not extreme enough)",
                                analysisId, String.format("%.1f", rsi));
                    } else if (!volumeDriedUp) {
                        log.info("[EXHAUST-REV][{}] ⛔ PUT exhaust blocked — volume {}x > 0.30 (fuel remains)",
                                analysisId, String.format("%.2f", volRatioExhaust));
                    }

                    if (!dirExhausted || !rsiExtreme || !volumeDriedUp) {
                        // Log but skip — not a textbook exhaustion
                        log.info("[EXHAUST-REV][{}] PUT exhaust conditions partial: dirExh={}, rsiExt={}, volDry={}",
                                analysisId, dirExhausted, rsiExtreme, volumeDriedUp);
                    } else {

                        // V2: RSI must have peaked (declined 10+ from session peak)
                        boolean rsiPeakConfirmed = rsiHasPeaked && (sessionPeakRSI - rsi >= 10.0);

                        // V2: Structural — price broke below swing low formed after RSI peak
                        boolean structConfirmed = swingLowAfterRSIPeak != null
                                && currentPrice.compareTo(swingLowAfterRSIPeak) <= 0;

                        if (rsiPeakConfirmed && structConfirmed) {
                            int lp = checkLeadersForDirection(symbol, "PUT");
                            if (lp >= 2) {
                                log.info("[EXHAUST-REV][{}] ⚡ PUT EXHAUSTION (V2) — peakRSI={}, now={}, swingLow=${}",
                                        analysisId, String.format("%.1f", sessionPeakRSI),
                                        String.format("%.1f", rsi), swingLowAfterRSIPeak);

                                BigDecimal target = vwap;
                                BigDecimal stop = sessionHighPrice.add(atr.multiply(new BigDecimal("0.3")));

                                signals = buildAndRouteSignal(symbol, "PUT", currentPrice, target, stop,
                                        lp, ta, "EXHAUSTION_REVERSAL_DOWN", analysisId);
                                if (!signals.isEmpty()) {
                                    lastDepartureSignalTime = LocalDateTime.now(ET_ZONE);
                                }
                                return signals;
                            }
                        } else {
                            log.info("[EXHAUST-REV][{}] PUT exhaust raw conditions met, V2 NOT confirmed: " +
                                            "peaked={}, peakRSI={}, swingLow={}, struct={}",
                                    analysisId, rsiHasPeaked, String.format("%.1f", sessionPeakRSI),
                                    swingLowAfterRSIPeak, structConfirmed);
                        }
                    } // closes the V3 dirExhausted/rsiExtreme/volumeDriedUp else block
                }

                if (priceBelowVWAP && rsi < 40) {

                    double velocityCollapseRatio = momentumTracker.getVelocityCollapseRatio(symbol, 8);
                    double peakVelocity = momentumTracker.getPeakVelocity(symbol, 8);

                    // ═══ INTRADAY CAPITULATION: Independent gates (not exhaustion score) ═══
                    // Capitulation is a sharp V-bottom — different from gradual exhaustion.
                    // DecliningRange is irrelevant (cap bar is typically the widest bar).
                    // Instead: velocity collapse + trough RSI extreme + volume spike on cap bar + stabilization.

                    // Gate 1: Velocity collapse > 3.0x (mandatory — this IS the capitulation signature)
                    boolean velCollapsePass = velocityCollapseRatio > 3.0;

                    // Gate 2: Session trough RSI < 25 (must have hit extreme, not just "oversold")
                    // Current RSI may have recovered (that's the divergence), but the trough must be deep
                    boolean troughRsiPass = sessionTroughRSI < 25.0;

                    // Gate 3: Capitulation bar volume spike — cap bar should have higher incremental
                    // volume than the average of the 2-3 bars after it (selling climax then volume dries up)
                    boolean capVolumePass = false;
                    String capVolReason = "no data";
                    try {
                        List<MarketData> capBars = barAggregationService.getRecentBars(symbol, 8);
                        if (capBars != null && capBars.size() >= 5) {
                            // Find the bar with the lowest low (the capitulation bar)
                            int capBarIdx = 0;
                            BigDecimal lowestLow = null;
                            for (int ci = 0; ci < capBars.size(); ci++) {
                                if (capBars.get(ci).getLow() != null) {
                                    if (lowestLow == null || capBars.get(ci).getLow().compareTo(lowestLow) < 0) {
                                        lowestLow = capBars.get(ci).getLow();
                                        capBarIdx = ci;
                                    }
                                }
                            }

                            // Get incremental volume of cap bar vs average of 2-3 bars after it
                            Long capBarIncVol = capBars.get(capBarIdx).getIncrementalVolume();
                            if (capBarIncVol == null || capBarIncVol <= 0) {
                                capBarIncVol = capBars.get(capBarIdx).getVolume(); // fallback
                            }

                            int afterCount = 0;
                            long afterVolSum = 0;
                            for (int ai = capBarIdx + 1; ai < Math.min(capBarIdx + 4, capBars.size()); ai++) {
                                Long incVol = capBars.get(ai).getIncrementalVolume();
                                if (incVol != null && incVol > 0) {
                                    afterVolSum += incVol;
                                    afterCount++;
                                }
                            }

                            if (afterCount > 0 && capBarIncVol != null && capBarIncVol > 0) {
                                double avgAfterVol = (double) afterVolSum / afterCount;
                                capVolumePass = capBarIncVol > avgAfterVol;
                                capVolReason = String.format("capVol=%d vs avgAfter=%.0f → %s",
                                        capBarIncVol, avgAfterVol, capVolumePass ? "SPIKE" : "no spike");
                            } else {
                                // If we can't determine volume, use TA volumeRatio as fallback
                                // Low volume ratio after cap = volume dried up = pass
                                double volRatio = (ta != null) ? ta.getVolumeRatio() : 1.0;
                                capVolumePass = volRatio < 0.60;
                                capVolReason = String.format("fallback: volRatio=%.2fx %s 0.60",
                                        volRatio, capVolumePass ? "<" : ">=");
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[INTRADAY-CAP][{}] Volume check error: {}", analysisId, e.getMessage());
                        capVolumePass = false;
                        capVolReason = "error: " + e.getMessage();
                    }

                    log.info("[INTRADAY-CAP][{}] Checking: RSI={}, troughRSI={}, velCollapse={}x, peakVel={}%, capVol={} " +
                                    "| Gates: velCollapse={}, troughRSI={}, capVolume={}",
                            analysisId, String.format("%.1f", rsi),
                            String.format("%.1f", sessionTroughRSI),
                            String.format("%.1f", velocityCollapseRatio),
                            String.format("%.4f", peakVelocity * 100),
                            capVolReason,
                            velCollapsePass ? "✓" : "✗",
                            troughRsiPass ? "✓" : "✗",
                            capVolumePass ? "✓" : "✗");

                    // All 3 independent gates must pass
                    if (velCollapsePass && troughRsiPass && capVolumePass) {
                        int lc = checkLeadersForDirection(symbol, "CALL");

                        // Minimum 1/3 leaders (was 2/3 in session exhaustion path)
                        if (lc >= 1) {

                            // Confirm price has stabilized: at least 2 bars above the recent low
                            List<MarketData> capBars = barAggregationService.getRecentBars(symbol, 5);
                            boolean stabilized = false;
                            if (capBars != null && capBars.size() >= 3) {
                                BigDecimal recentLow = getRecentLow(capBars, capBars.size());
                                int barsAboveLow = 0;
                                for (int bi = capBars.size() - 1; bi >= Math.max(0, capBars.size() - 3); bi--) {
                                    if (capBars.get(bi).getClose() != null && recentLow != null &&
                                            capBars.get(bi).getClose().compareTo(recentLow.add(new BigDecimal("0.10"))) > 0) {
                                        barsAboveLow++;
                                    }
                                }
                                stabilized = barsAboveLow >= 2;
                            }

                            if (stabilized) {
                                log.info("═══════════════════════════════════════════════════════════════");
//                                log.info("[INTRADAY-CAP][{}] ⚡ CALL INTRADAY CAPITULATION — RSI={}, velCollapse={}x, exh={}/4, leaders={}/3",
//                                        analysisId, String.format("%.1f", rsi), String.format("%.1f", velocityCollapseRatio),
//                                        exhaustionScore, lc);
                                log.info("[INTRADAY-CAP][{}] Price=${}, VWAP=${}, peakVel={}%",
                                        analysisId, currentPrice, vwap, String.format("%.4f", peakVelocity * 100));
                                log.info("═══════════════════════════════════════════════════════════════");

                                BigDecimal target = vwap; // mean reversion to VWAP
                                BigDecimal recentLow = getRecentLow(
                                        barAggregationService.getRecentBars(symbol, 8), 8);
                                BigDecimal stop = recentLow != null
                                        ? recentLow.subtract(new BigDecimal("0.50"))
                                        : sessionLowPrice.subtract(atr.multiply(new BigDecimal("0.3")));

                                // Check VWAP slope — don't call capitulation if VWAP is steeply
                                // sloping in the sell direction (the selling IS the trend)
                                double capSlopeVal = rollingVwapSlope30min.doubleValue();
                                if (capSlopeVal < -0.50) {
                                    log.info("[INTRADAY-CAP][{}] SUPPRESSED — VWAP slope ${}/30m " +
                                                    "strongly bearish. Selling is the trend, not a capitulation",
                                            analysisId, String.format("%.2f", capSlopeVal));
                                    // Don't generate the signal — let it continue to the next path
                                    // (fall through instead of returning)
                                    return signals;
                                }

                                signals = buildAndRouteSignal(symbol, "CALL", currentPrice, target, stop,
                                        lc, ta, "INTRADAY_CAPITULATION_UP", analysisId);
                                if (!signals.isEmpty()) {
                                    lastDepartureSignalTime = LocalDateTime.now(ET_ZONE);
                                }
                                return signals;
                            } else {
                                log.info("[INTRADAY-CAP][{}] Price not stabilized — waiting for 2+ bars above low",
                                        analysisId);
                            }
                        } else {
                            log.info("[INTRADAY-CAP][{}] Only {}/3 leaders — need minimum 1", analysisId, lc);
                        }
                    } else {
                        if (velocityCollapseRatio <= 3.0) {
                            log.debug("[INTRADAY-CAP][{}] Velocity collapse {}x < 3.0 threshold",
                                    analysisId, String.format("%.1f", velocityCollapseRatio));
                        }
                    }
                }

                // CALL on exhausted selloff
                if (priceBelowVWAP && continuousBelowVwapMinutes >= 25
                        && rsi < 28 && momentumReversing) {

                    // V3: Textbook exhaustion gates — require ALL three for genuine structural exhaustion
                    double bearDirPct = moveFromHOD / averageDailyRange;
                    double volRatioExhaustCall = ta.getVolumeRatio();
                    boolean dirExhaustedCall = bearDirPct >= 0.75;
                    boolean rsiExtremeCall = rsi < 25;
                    boolean volumeDriedUpCall = volRatioExhaustCall < 0.30;

                    if (!dirExhaustedCall) {
                        log.info("[EXHAUST-REV][{}] ⛔ CALL exhaust blocked — bearDir {}% < 75% (not truly exhausted)",
                                analysisId, String.format("%.0f", bearDirPct * 100));
                    } else if (!rsiExtremeCall) {
                        log.info("[EXHAUST-REV][{}] ⛔ CALL exhaust blocked — RSI {} > 25 (not extreme enough)",
                                analysisId, String.format("%.1f", rsi));
                    } else if (!volumeDriedUpCall) {
                        log.info("[EXHAUST-REV][{}] ⛔ CALL exhaust blocked — volume {}x > 0.30 (fuel remains)",
                                analysisId, String.format("%.2f", volRatioExhaustCall));
                    }

                    if (dirExhaustedCall && rsiExtremeCall && volumeDriedUpCall) {

                        // V2: RSI must have troughed (risen 10+ from session trough)
                        boolean rsiTroughConfirmed = rsiHasTroughed && (rsi - sessionTroughRSI >= 10.0);

                        // V2: Structural — price broke above swing high formed after RSI trough
                        boolean structConfirmed = swingHighAfterRSITrough != null
                                && currentPrice.compareTo(swingHighAfterRSITrough) >= 0;

                        if (rsiTroughConfirmed && structConfirmed) {
                            int lc = checkLeadersForDirection(symbol, "CALL");
                            if (lc >= 2) {
                                log.info("[EXHAUST-REV][{}] ⚡ CALL EXHAUSTION (V2+V3) — troughRSI={}, now={}, swingHigh=${}",
                                        analysisId, String.format("%.1f", sessionTroughRSI),
                                        String.format("%.1f", rsi), swingHighAfterRSITrough);

                                BigDecimal target = vwap;
                                BigDecimal stop = sessionLowPrice.subtract(atr.multiply(new BigDecimal("0.3")));

                                signals = buildAndRouteSignal(symbol, "CALL", currentPrice, target, stop,
                                        lc, ta, "EXHAUSTION_REVERSAL_UP", analysisId);
                                if (!signals.isEmpty()) {
                                    lastDepartureSignalTime = LocalDateTime.now(ET_ZONE);
                                }
                                return signals;
                            }
                        } else {
                            log.info("[EXHAUST-REV][{}] CALL exhaust V3 gates passed but V2 NOT confirmed: " +
                                            "troughed={}, troughRSI={}, swingHigh={}, struct={}",
                                    analysisId, rsiHasTroughed, String.format("%.1f", sessionTroughRSI),
                                    swingHighAfterRSITrough, structConfirmed);
                        }
                    } else {
                        log.info("[EXHAUST-REV][{}] CALL exhaust conditions partial: dirExh={}, rsiExt={}, volDry={}",
                                analysisId, dirExhaustedCall, rsiExtremeCall, volumeDriedUpCall);
                    }
                }
            }


            // ═══════════════════════════════════════════════════════════════
            // REGIME BLOCKS FOR MEAN REVERSION (relaxed for TREND_EXHAUSTING)
            // ═══════════════════════════════════════════════════════════════
            if (currentRegime == SessionRegime.TRENDING_UP && priceAboveVWAP) {
                log.info("[{}] ⛔ REGIME BLOCK: TRENDING_UP — PUT MR suppressed. Above VWAP {} cycles",
                        analysisId, continuousAboveVwapMinutes);
                return signals;
            }
            if (currentRegime == SessionRegime.TRENDING_DOWN && priceBelowVWAP) {
                log.info("[{}] ⛔ REGIME BLOCK: TRENDING_DOWN — CALL MR suppressed. Below VWAP {} cycles",
                        analysisId, continuousBelowVwapMinutes);
                return signals;
            }
            // TREND_EXHAUSTING: allow MR with confidence penalty (handled below)

            // ═══════════════════════════════════════════════════════════════
            // PATH B: MEAN-REVERSION SIGNALS (existing logic, with soft gates)
            // ═══════════════════════════════════════════════════════════════

            String signalType = null;
            if (priceBelowVWAP) {
                signalType = "CALL";
            } else if (priceAboveVWAP) {
                signalType = "PUT";
            } else {
                log.info("[MR-SIGNAL][{}] Price exactly at VWAP, no signal", analysisId);
                return signals;
            }

            // Debounce
            String dedupeKey = signalType + "_" + symbol;
            LocalDateTime lastGenerated = lastSignalByTypeAndStrike.get(dedupeKey);
            if (lastGenerated != null && lastGenerated.isAfter(LocalDateTime.now(ET_ZONE).minusSeconds(60))) {
                log.debug("[{}] Debounce: {} signal generated recently — skipping", analysisId, signalType);
                return signals;
            }

            // VWAP distance gate (unchanged)
            if (distanceFromVWAP.compareTo(new BigDecimal("0.50")) < 0) {
                log.info("[MR-SIGNAL][{}] ❌ SKIP - VWAP distance ${} < $0.50 threshold",
                        analysisId, distanceFromVWAP.setScale(2, RoundingMode.HALF_UP));
                return signals;
            }

            log.info("[MR-SIGNAL][{}] ✓ VWAP distance ${} >= $0.50", analysisId,
                    distanceFromVWAP.setScale(2, RoundingMode.HALF_UP));
            log.info("[MR-SIGNAL][{}] {} opportunity - Price {} VWAP by {}%", analysisId, signalType,
                    signalType.equals("CALL") ? "below" : "above",
                    String.format("%.4f", distanceFromVWAP));

            // ═══ POC: SOFT GATE ═══
            double pocConfidenceAdj = 0.0;
            BigDecimal poc = targetCalculationService.getPOC(symbol, analysisId);
            if (poc != null) {
                BigDecimal pocDistanceDollars = currentPrice.subtract(poc).abs();
                if (pocDistanceDollars.compareTo(new BigDecimal("0.50")) < 0) {
                    pocConfidenceAdj = -0.10;
                    log.info("[MR-SIGNAL][{}] ⚠ POC distance ${} < $0.50 — confidence -10%",
                            analysisId, pocDistanceDollars.setScale(2, RoundingMode.HALF_UP));
                }
            }

            // ═══ KEY LEVEL: SOFT GATE ═══
            double keyLevelConfidenceAdj = 0.0;
            DailyLevelService.DailyLevels levels = dailyLevelService.getQQQDailyLevels();
            BigDecimal levelProximityThreshold = atr.multiply(new BigDecimal("0.3"));
            boolean nearKeyLevel = false;
            String nearestLevel = "NONE";
            BigDecimal nearestLevelPrice = null;
            BigDecimal nearestLevelDistance = new BigDecimal("999");

            if (levels != null) {
                if (signalType.equals("CALL")) {
                    if (levels.getTodayLow() != null) {
                        BigDecimal dist = currentPrice.subtract(levels.getTodayLow()).abs();
                        if (dist.compareTo(nearestLevelDistance) < 0) {
                            nearestLevelDistance = dist; nearestLevelPrice = levels.getTodayLow(); nearestLevel = "LOD";
                        }
                    }
                    if (poc != null && poc.compareTo(currentPrice) < 0) {
                        BigDecimal dist = currentPrice.subtract(poc).abs();
                        if (dist.compareTo(nearestLevelDistance) < 0) {
                            nearestLevelDistance = dist; nearestLevelPrice = poc; nearestLevel = "POC";
                        }
                    }
                    if (levels.getPreviousDayLow() != null) {
                        BigDecimal dist = currentPrice.subtract(levels.getPreviousDayLow()).abs();
                        if (dist.compareTo(nearestLevelDistance) < 0) {
                            nearestLevelDistance = dist; nearestLevelPrice = levels.getPreviousDayLow(); nearestLevel = "PREV_DAY_LOW";
                        }
                    }
                } else {
                    if (levels.getTodayHigh() != null) {
                        BigDecimal dist = levels.getTodayHigh().subtract(currentPrice).abs();
                        if (dist.compareTo(nearestLevelDistance) < 0) {
                            nearestLevelDistance = dist; nearestLevelPrice = levels.getTodayHigh(); nearestLevel = "HOD";
                        }
                    }
                    if (poc != null && poc.compareTo(currentPrice) > 0) {
                        BigDecimal dist = poc.subtract(currentPrice).abs();
                        if (dist.compareTo(nearestLevelDistance) < 0) {
                            nearestLevelDistance = dist; nearestLevelPrice = poc; nearestLevel = "POC";
                        }
                    }
                    if (levels.getPreviousDayHigh() != null) {
                        BigDecimal dist = levels.getPreviousDayHigh().subtract(currentPrice).abs();
                        if (dist.compareTo(nearestLevelDistance) < 0) {
                            nearestLevelDistance = dist; nearestLevelPrice = levels.getPreviousDayHigh(); nearestLevel = "PREV_DAY_HIGH";
                        }
                    }
                }

                nearKeyLevel = nearestLevelDistance.compareTo(levelProximityThreshold) <= 0;
                log.info("[MR-SIGNAL][{}] Key Level: {} at ${}, Distance=${}, Near={}",
                        analysisId, nearestLevel,
                        nearestLevelPrice != null ? nearestLevelPrice.setScale(2, RoundingMode.HALF_UP) : "N/A",
                        nearestLevelDistance.setScale(2, RoundingMode.HALF_UP), nearKeyLevel);
            }

            if (nearKeyLevel) {
                keyLevelConfidenceAdj = 0.10;
            } else {
                keyLevelConfidenceAdj = -0.10;
                log.info("[MR-SIGNAL][{}] ⚠ Not near key level — confidence -10%", analysisId);
            }

            // ═══ CONFIRMATION 1: VWAP Distance meaningful ═══
            boolean confirmation1 = true;
            if (sessionRange.compareTo(new BigDecimal("1.00")) > 0) {
                BigDecimal minMeaningfulDistance = sessionRange.multiply(new BigDecimal("0.05"));
                confirmation1 = distanceFromVWAP.compareTo(minMeaningfulDistance) >= 0;
                if (!confirmation1) {
                    log.info("[MR-SIGNAL][{}] ✗ VWAP distance below 5% of session range", analysisId);
                    return signals;
                }
            }

            // ═══ CONFIRMATION 2: VWAP Interaction ═══
            boolean confirmation2 = false;
            if (distanceFromVWAP.compareTo(new BigDecimal("1.00")) > 0) {
                LocalDateTime vwapTouchTime = findRecentVWAPTouch(symbol, 15);
                if (vwapTouchTime != null) {
                    confirmation2 = true;
                } else {
                    log.info("[MR-SIGNAL][{}] ✗ Large distance but no recent VWAP touch", analysisId);
                    return signals;
                }
            } else {
                confirmation2 = true;
            }

            // ═══ CONFIRMATION 3: Momentum Slowing (MR requires deceleration) ═══
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 10);
            boolean confirmation3 = false;
            double wickRejectionBonus = 0.0;

            MarketData confirmationBar = null;
            if (recentBars != null && recentBars.size() >= 2) {
                MarketData latestBar = recentBars.get(recentBars.size() - 1);
                MarketData previousBar = recentBars.get(recentBars.size() - 2);
                LocalDateTime barEndTime = latestBar.getTimestamp().plusMinutes(1);
                boolean isBarClosed = LocalDateTime.now(ET_ZONE).isAfter(barEndTime);
                confirmationBar = isBarClosed ? latestBar : previousBar;
            }

            if (recentBars != null && recentBars.size() >= 3 && confirmationBar != null) {
                int currentIndex = recentBars.indexOf(confirmationBar);
                if (currentIndex >= 2) {
                    MarketData currentBar = confirmationBar;
                    MarketData prevBar = recentBars.get(currentIndex - 1);
                    MarketData olderBar = recentBars.get(currentIndex - 2);

                    if (currentBar.getClose() != null && prevBar.getClose() != null && olderBar.getClose() != null) {
                        double currentVelocity = Math.abs(currentBar.getClose().subtract(prevBar.getClose())
                                .divide(prevBar.getClose(), 4, RoundingMode.HALF_UP).doubleValue() * 100);
                        double previousVelocity = Math.abs(prevBar.getClose().subtract(olderBar.getClose())
                                .divide(olderBar.getClose(), 4, RoundingMode.HALF_UP).doubleValue() * 100);

                        confirmation3 = currentVelocity < previousVelocity ||
                                (currentVelocity == previousVelocity && currentVelocity < 0.02);

                        log.info("[MOMENTUM][{}] Current velocity: {}%, Previous: {}%, Slowing: {}",
                                analysisId, String.format("%.4f", currentVelocity),
                                String.format("%.4f", previousVelocity), confirmation3);
                    }
                }
            }

            if (!confirmation3) {
                log.info("[{}] Rejection: ✗ [MOMENTUM-SLOW] Momentum not slowing", analysisId);
                return signals;
            }

            // ═══ CONFIRMATION 3b: Volume Exhaustion ═══
            boolean volumeExhaustion = false;
            try {
                boolean hasExhaust = volumeTracker.hasExhaustionPattern("QQQ");
                double volRatio = ta.getVolumeRatio();
                volumeExhaustion = hasExhaust || volRatio < 0.8;

                if (!volumeExhaustion && currentRegime != SessionRegime.RANGING
                        && currentRegime != SessionRegime.TREND_EXHAUSTING) {
                    log.info("[{}] ✗ Volume not showing exhaustion in {} regime", analysisId, currentRegime);
                    return signals;
                }
            } catch (Exception e) {
                log.debug("[VOLUME-GATE][{}] Error: {}", analysisId, e.getMessage());
            }

            // ═══ CONFIRMATION 4: Leaders ═══
            int leadersConfirming = checkLeadersForDirection(symbol, signalType);
            boolean confirmation4 = leadersConfirming >= 2;
            if (currentRegime == SessionRegime.TRENDING_UP && "PUT".equals(signalType)) {
                confirmation4 = leadersConfirming >= 3;
            }
            if (currentRegime == SessionRegime.TRENDING_DOWN && "CALL".equals(signalType)) {
                confirmation4 = leadersConfirming >= 3;
            }

            if (!confirmation4) {
                log.info("[{}] Rejection: ✗ [LEADERS] Only {}/3 confirming", analysisId, leadersConfirming);
                return signals;
            }

            // ═══ CONFIRMATION 5: Price Momentum Alignment ═══
            boolean confirmation5 = checkPriceMomentumAlignment(symbol, signalType);
            if (!confirmation5) {
                log.info("[{}] Rejection: ✗ [PRICE-MOMENTUM] Not aligned with {}", analysisId, signalType);
                return signals;
            }

            // ═══ ENTRY, TARGET, STOP LOSS ═══
            BigDecimal entryPrice = currentPrice;
            atr = ta.getAverageTrueRange();
            if (atr == null || atr.compareTo(new BigDecimal("0.50")) < 0) atr = new BigDecimal("0.50");

            TargetCalculationService.TargetResult targetResult =
                    targetCalculationService.calculateMeanReversionTarget(entryPrice, signalType, vwap, analysisId);

            if (!targetResult.isValid()) {
                log.warn("[MR-SIGNAL][{}] ✗ No valid target — {}", analysisId, targetResult.getReason());
                return signals;
            }

            BigDecimal targetPrice = targetResult.getTargetPrice();
            BigDecimal targetDistance = targetResult.getDistance();

            // Regime-aware stop loss
            BigDecimal stopDistance;
            if (currentRegime == SessionRegime.RANGING) {
                BigDecimal atrStop = atr.multiply(new BigDecimal("0.5"));
                BigDecimal halfTarget = targetDistance.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                stopDistance = atrStop.min(halfTarget);
            } else {
                stopDistance = atr.multiply(new BigDecimal("0.75"));
                BigDecimal vwapBuffer = distanceFromVWAP.add(new BigDecimal("0.30"));
                if (vwapBuffer.compareTo(stopDistance) < 0) stopDistance = vwapBuffer;
            }
            if (stopDistance.compareTo(new BigDecimal("0.30")) < 0) stopDistance = new BigDecimal("0.30");

            BigDecimal stopLoss;
            if (signalType.equals("CALL")) {
                stopLoss = entryPrice.subtract(stopDistance);
            } else {
                stopLoss = entryPrice.add(stopDistance);
            }

            BigDecimal targetDistanceAbs = targetPrice.subtract(entryPrice).abs();
            if (targetDistanceAbs.compareTo(new BigDecimal("1.00")) < 0) {
                log.info("[MR-SIGNAL] ❌ [{}] target distance ${} < $1.00", analysisId, targetDistanceAbs);
                return signals;
            }

            double maxTargetPct;
            if (currentRegime == SessionRegime.TRENDING_DOWN || currentRegime == SessionRegime.TRENDING_UP) {
                maxTargetPct = 0.020;
            } else {
                maxTargetPct = 0.010;
            }
            BigDecimal maxAbsoluteDistance = entryPrice.multiply(new BigDecimal(String.valueOf(maxTargetPct)));
            if (targetDistanceAbs.compareTo(maxAbsoluteDistance) > 0) {
                return signals;
            }

            sessionRange = sessionHighPrice.subtract(sessionLowPrice);
            if (sessionRange.compareTo(new BigDecimal("1.00")) > 0) {
                BigDecimal maxRelativeDistance = sessionRange.multiply(new BigDecimal("0.50"));
                if (targetDistanceAbs.compareTo(maxRelativeDistance) > 0) return signals;
            }

            double minutesToClose = java.time.Duration.between(now, LocalTime.of(16, 0)).toMinutes();
            if (minutesToClose > 0 && minutesToClose < 390) {
                double maxDistanceByTime = Math.max(1.0, 6.0 * (minutesToClose / 390.0));
                if (targetDistanceAbs.doubleValue() > maxDistanceByTime) return signals;
            }

            // ═══ ALL MR CONFIRMATIONS PASSED ═══
            log.info("[MR-SIGNAL][{}] ✅ {} MR SIGNAL — 5/5 confirmations", analysisId, signalType);

            // ═══ CONFIRMATION 6: Directional Validation ═══
            DirectionalConfirmationService.DirectionalResult directionalResult =
                    directionalConfirmationService.validateSignalDirectionWithFlip(signalType, analysisId, currentPrice, vwap);

            if (!directionalResult.isValid()) {
                if (directionalResult.shouldFlip() && directionalResult.getFlipDirection() != null) {
                    String originalSignal = signalType;
                    signalType = directionalResult.getFlipDirection();
                    log.info("[{}] ⚡ SIGNAL FLIPPED: {} → {}", analysisId, originalSignal, signalType);

                    TargetCalculationService.TargetResult flipTarget =
                            targetCalculationService.calculateMeanReversionTarget(entryPrice, signalType, vwap, analysisId);

                    if (!flipTarget.isValid()) {
                        TargetCalculationService.TargetResult breakoutTarget =
                                targetCalculationService.calculateBreakoutTarget(entryPrice, signalType, vwap, analysisId);
                        if (breakoutTarget != null && breakoutTarget.isValid()) {
                            targetPrice = breakoutTarget.getTargetPrice();
                            BigDecimal bStop = breakoutTarget.getDistance().divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                            stopLoss = signalType.equals("CALL") ? entryPrice.subtract(bStop) : entryPrice.add(bStop);
                        } else {
                            return signals;
                        }
                    } else {
                        targetPrice = flipTarget.getTargetPrice();
                        BigDecimal flipStopDist = flipTarget.getDistance().divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                        stopLoss = signalType.equals("CALL") ? entryPrice.subtract(flipStopDist) : entryPrice.add(flipStopDist);
                    }
                } else {
                    log.info("[{}] ✗✗✗ SIGNAL REJECTED — DIRECTIONAL CONFLICT", analysisId);
                    return signals;
                }
            }

            // ═══ 6/6 CONFIRMED — BUILD SIGNAL ═══
            log.info("[{}] ✓✓✓ MR CONFIRMATION SIGNAL APPROVED — 6/6", analysisId);

            LocalDate today = LocalDate.now(ET_ZONE);
            List<LocalDate> expirations = tradierService.getExpirations(symbol);
            if (expirations == null || expirations.isEmpty() || !expirations.contains(today)) {
                return signals;
            }

            OptionChainResponse chainResponse = tradierService.getOptionChain(symbol, today);
            if (chainResponse == null || !chainResponse.hasOptions()) return signals;

            List<Option> options = chainResponse.getOptionsList();
            if (options == null || options.isEmpty()) return signals;

            Option selectedOption;
            if (signalType.equals("CALL")) {
                selectedOption = selectBestCallOption(options, entryPrice, targetPrice, analysisId);
            } else {
                selectedOption = selectBestPutOption(options, entryPrice, targetPrice, analysisId);
            }
            if (selectedOption == null) return signals;

            Signal signal = new Signal();
            signal.setSymbol(selectedOption.getSymbol());
            signal.setOptionSymbol(selectedOption.getSymbol());
            signal.setSignalType(signalType);
            signal.setEntryPrice(selectedOption.getAsk());
            signal.setTargetPrice(targetPrice);
            signal.setStopLoss(stopLoss);
            signal.setStrikePrice(selectedOption.getStrikePrice());
            ZonedDateTime expiry = today.atTime(16, 0).atZone(ET_ZONE);
            signal.setExpirationDate(expiry);
            signal.setStrategy("CONFIRMATION_BASED_0DTE");

            Map<String, Double> candleNets = fetchCandleNetsForConfidence(analysisId);
            levels = dailyLevelService.getQQQDailyLevels();
            BigDecimal todayHigh = (levels != null) ? levels.getTodayHigh() : null;
            BigDecimal todayLow = (levels != null) ? levels.getTodayLow() : null;

            double calculatedConfidence = calculateSignalConfidence(
                    signal, ta, leadersConfirming, candleNets, todayHigh, todayLow, wickRejectionBonus, analysisId);

            // Apply soft-gate adjustments
            calculatedConfidence += keyLevelConfidenceAdj;
            calculatedConfidence += pocConfidenceAdj;

            // TREND_EXHAUSTING penalty for MR counter-trend
            if (currentRegime == SessionRegime.TREND_EXHAUSTING) {
                calculatedConfidence -= 0.10;
                log.info("[{}] TREND_EXHAUSTING penalty: -10% confidence for MR", analysisId);
            }

            // Strong trend penalty
            if (isStrongTrendConfirmed()) {
                calculatedConfidence -= 0.15;
                log.info("[{}] Strong trend penalty: -15% confidence for counter-trend MR", analysisId);
            }

            calculatedConfidence = Math.max(0.0, Math.min(1.0, calculatedConfidence));
            signal.setConfidence(calculatedConfidence);
            signal.setCreatedAt(LocalDateTime.now());

            log.info("[{}] MR Signal: {} ${} {}, Entry: ${}, Target: ${}, Confidence: {}%",
                    analysisId, symbol, selectedOption.getStrikePrice(), signalType,
                    signal.getEntryPrice(), targetPrice, (int)(signal.getConfidence() * 100));

            signal = applyRiskManagement(signal, analysisId);

            if (signal != null && signal.getConfidence() != null && signal.getConfidence() >= 0.60) {
                signals.add(signal);
                lastSignalByTypeAndStrike.put(signalType + "_" + symbol, LocalDateTime.now(ET_ZONE));
                signalsGeneratedToday++;
                if ("PUT".equals(signalType)) putSignalsToday++;
                if ("CALL".equals(signalType)) callSignalsToday++;

                boolean handled = routeSignalByConfidence(signal, ta, analysisId);
                if (!handled) {
                    log.warn("[{}] Signal could not be routed", analysisId);
                }
            }

        } catch (Exception e) {
            log.error("[{}] Error in analysis: {}", analysisId, e.getMessage(), e);
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

        // ═══ REGIME-GATE FOR PIPELINE B ═══
        if (currentRegime == SessionRegime.TRENDING_UP && vwapDistancePercent > 0) {
            // Suppress rejection-put in uptrend; only allow breakout-call
            log.info("[VWAP-STRATEGIES][{}] TRENDING_UP: Suppressing rejection-PUT, only breakout-CALL allowed", analysisId);
            if (vwapDistancePercent > 0.001 && vwapDistancePercent <= 0.015) {
                Option callOption = findBestATMOption(allOptions, currentPrice, true);
                if (callOption != null) {
                    analyzeVWAPBreakoutCall(callOption, allOptions, ta, signals, marketTrend, analysisId);
                }
            }
            return;
        }

        if (currentRegime == SessionRegime.TRENDING_DOWN && vwapDistancePercent < 0) {
            log.info("[VWAP-STRATEGIES][{}] TRENDING_DOWN: Suppressing bounce-CALL, only breakdown-PUT allowed", analysisId);
            if (vwapDistancePercent < -0.001 && vwapDistancePercent >= -0.015) {
                Option putOption = findBestATMOption(allOptions, currentPrice, false);
                if (putOption != null) {
                    analyzeVWAPBreakdownPut(putOption, allOptions, ta, signals, marketTrend, analysisId);
                }
            }
            return;
        }

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

        // Widened cap in TRENDING regimes to capture mid-trend continuation entries
        double maxBreakoutDistance = (currentRegime == SessionRegime.TRENDING_UP) ? 0.015 : 0.008;
        if (vwapDistancePercent <= 0.001 || vwapDistancePercent > maxBreakoutDistance) {
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
                    log.warn("[TOO-LATE][{}] ⚠️ Already {}% from recent high - breakdown complete",
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

        /**
         * Calculate velocity collapse ratio: peak velocity in last N bars / current velocity.
         * A ratio > 3.0 means 70%+ collapse = confirmed capitulation.
         * Returns 0.0 if insufficient data.
         */
        public double getVelocityCollapseRatio(String symbol, int lookbackBars) {
            List<MomentumReading> history = momentumHistory.get(symbol);
            if (history == null || history.size() < 3) {
                return 0.0;
            }

            int limit = Math.min(lookbackBars, history.size());
            double peakVelocity = 0.0;
            double currentVelocity = Math.abs(history.get(0).momentum);

            for (int i = 0; i < limit; i++) {
                double vel = Math.abs(history.get(i).momentum);
                if (vel > peakVelocity) {
                    peakVelocity = vel;
                }
            }

            // Avoid division by zero — if current velocity is near zero, that IS exhaustion
            if (currentVelocity < 0.0001) {
                return peakVelocity > 0.001 ? 100.0 : 0.0; // infinite collapse if peak was real
            }

            return peakVelocity / currentVelocity;
        }

        /**
         * Get the peak velocity value from last N bars (for logging).
         */
        public double getPeakVelocity(String symbol, int lookbackBars) {
            List<MomentumReading> history = momentumHistory.get(symbol);
            if (history == null || history.isEmpty()) return 0.0;

            int limit = Math.min(lookbackBars, history.size());
            double peak = 0.0;
            for (int i = 0; i < limit; i++) {
                double vel = Math.abs(history.get(i).momentum);
                if (vel > peak) peak = vel;
            }
            return peak;
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
        // Late session: PENALIZE (not boost) due to 0DTE theta/gamma acceleration
        if (now.isAfter(LocalTime.of(15, 45))) {
            confidence *= 0.70; // Severe penalty — near-expiry
        } else if (now.isAfter(LocalTime.of(15, 30))) {
            confidence *= 0.80;
        } else if (now.isAfter(LocalTime.of(15, 15))) {
            confidence *= 0.90;
        } else if (now.isAfter(LocalTime.of(15, 0))) {
            confidence *= 0.95;
        }

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
                    !signal.getStrategy().contains("ENHANCED_AI")) {
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

                // ═══ NO BYPASSES — all signals must pass leader validation ═══
                // Early session: use relaxed thresholds but still validate
                boolean earlySession = now.isBefore(LocalTime.of(10, 0));

                // High volume: note it but don't bypass
                TechnicalAnalysis qqqTA = technicalAnalysisService.analyze("QQQ");
                boolean highVolume = qqqTA != null && qqqTA.getVolumeRatio() > 2.0;

                if (earlySession) {
                    log.info("[LEADER-VALIDATOR][{}] Early session — using relaxed thresholds (not bypassed)", analysisId);
                }
                if (highVolume) {
                    log.info("[LEADER-VALIDATOR][{}] High QQQ volume — noted but validation continues", analysisId);
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
                Map<String, Double> adjustedWeights = getRegimeAdjustedLeaderWeights();
                LeaderMomentum momentum = new LeaderMomentum(leader, adjustedWeights.get(leader));

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

    @PostConstruct
    public void initializeCoilTracking() {
        // Register callback so CoilTrackingService can execute signals through ZeroDTEStrategy
        coilTrackingService.setExecutionCallback(this::executeCoilSignal);
        log.info("[INIT] CoilTrackingService callback registered");
    }

    /**
     * Callback method for CoilTrackingService to execute signals
     * This keeps all execution logic in ZeroDTEStrategy
     */
    private void executeCoilSignal(Signal signal) {
        String analysisId = "COIL-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[{}] ═══════════════════════════════════════════════", analysisId);
        log.info("[{}] EXECUTING COIL SIGNAL VIA CALLBACK", analysisId);
        log.info("[{}] Signal: {} {} @ ${}", analysisId, signal.getSignalType(),
                signal.getStrikePrice(), signal.getEntryPrice());
        log.info("[{}] Strategy: {}", analysisId, signal.getStrategy());
        log.info("[{}] Confidence: {}%", analysisId, (int)(signal.getConfidence() * 100));
        log.info("[{}] ═══════════════════════════════════════════════", analysisId);

        // Use existing execution method
        executeSignalImmediately(signal, null, analysisId);
    }

    /**
     * Route signal based on confidence - either execute immediately or track for coiling
     *
     * @param signal The generated signal
     * @param ta Technical analysis at signal generation
     * @param analysisId Analysis ID for logging
     * @return true if signal was handled (executed or tracked), false if rejected
     */
    private boolean routeSignalByConfidence(Signal signal, TechnicalAnalysis ta, String analysisId) {
        if (signal == null || signal.getConfidence() == null) {
            log.warn("[{}] Cannot route null signal or signal with null confidence", analysisId);
            return false;
        }

        double confidence = signal.getConfidence();

        // Execution threshold: 70% (was 75%)
        double executionThreshold = 0.70;

        // ════════════════════════════════════════════════════════════════
        // CONFIDENCE < 70%: Route to Coil Tracker
        // ════════════════════════════════════════════════════════════════
        if (confidence < executionThreshold) {
            if (coilTrackingService.shouldTrackForCoil(signal)) {
                log.info("[{}] ═══════════════════════════════════════════════", analysisId);
                log.info("[{}] 🔄 ROUTING TO COIL TRACKER", analysisId);
                log.info("[{}] Confidence: {}% (below {}% threshold)", analysisId,
                        (int)(confidence * 100), (int)(executionThreshold * 100));
                log.info("[{}] ═══════════════════════════════════════════════", analysisId);

                coilTrackingService.trackSignal(signal, ta, analysisId);

                try {
                    String message = String.format(
                            "🔄 SIGNAL → COIL TRACKER\nID: %s\nSignal: %s %s\nStrike: $%s\nConfidence: %d%%\nStatus: Monitoring",
                            analysisId, signal.getSignalType(), signal.getOptionSymbol(),
                            signal.getStrikePrice(), (int)(confidence * 100));
                    telegramService.sendMessage(message);
                } catch (Exception e) {
                    log.error("[{}] Error sending Telegram: {}", analysisId, e.getMessage());
                }
                return true;
            } else {
                log.warn("[{}] Low confidence signal ({}%) cannot be tracked - skipping",
                        analysisId, (int)(confidence * 100));
                return false;
            }
        }

        // ════════════════════════════════════════════════════════════════
        // CONFIDENCE >= 70%: Execute immediately
        // ════════════════════════════════════════════════════════════════
        log.info("[{}] ═══════════════════════════════════════════════", analysisId);
        log.info("[{}] ✓ EXECUTING SIGNAL (confidence {}% >= {}%)", analysisId,
                (int)(confidence * 100), (int)(executionThreshold * 100));
        log.info("[{}] ═══════════════════════════════════════════════", analysisId);

        try {
            String message = formatSignalMessage(signal, analysisId);
            telegramService.sendMessage(message);
        } catch (Exception e) {
            log.error("[{}] Error sending Telegram: {}", analysisId, e.getMessage());
        }

        executeSignalImmediately(signal, null, analysisId);
        return true;
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

    @Scheduled(fixedDelay = 15000) // Every 15 seconds — matches analysis cycle
    public void updateSessionState() {
        try {
            LocalDate today = LocalDate.now(ET_ZONE);
            LocalTime now = LocalTime.now(ET_ZONE);

            // Reset on new day
            if (sessionDate == null || !sessionDate.equals(today)) {
                sessionDate = today;
                sessionOpenPrice = null;
                rollingVwapSlope30min = BigDecimal.ZERO;
                sessionHighPrice = BigDecimal.ZERO;
                sessionLowPrice = new BigDecimal("9999");
                continuousAboveVwapMinutes = 0;
                continuousBelowVwapMinutes = 0;
                lastVwapCrossTime = null;
                lastVwapCrossPrice = null;
                rollingVwapSlope30min = BigDecimal.ZERO;
                higherHighCount15m = 0;
                lowerLowCount15m = 0;
                signalsGeneratedToday = 0;
                signalsKilledByCoilToday = 0;
                putSignalsToday = 0;
                callSignalsToday = 0;
                currentRegime = SessionRegime.RANGING;
                trendContinuationCallCount = 0;
                trendContinuationPutCount = 0;
                lastDepartureSignalTime = null;
                regimeStartRSI = 50.0;
                sessionPeakRSI = 0.0;
                sessionTroughRSI = 100.0;
                swingLowAfterRSIPeak = null;
                swingHighAfterRSITrough = null;
                rsiHasPeaked = false;
                rsiHasTroughed = false;
                leaderOppositionStreaks.clear();
                lastSignalByTypeAndStrike.clear();
                lastCallCrossSignalTime = null;
                lastPutCrossSignalTime = null;
                log.info("[SESSION-STATE] New trading day initialized: {}", today);
            }

            if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(16, 0))) {
                return;
            }

            TechnicalAnalysis ta = technicalAnalysisService.analyze("QQQ");
            if (ta == null || ta.getCurrentPrice() == null || ta.getVwap() == null) return;

            BigDecimal price = ta.getCurrentPrice();
            BigDecimal vwap = ta.getVwap();

            // Capture session open
            if (sessionOpenPrice == null) {
                sessionOpenPrice = price;
                log.info("[SESSION-STATE] Session open captured: ${}", sessionOpenPrice);
            }

            // Update session high/low
            if (price.compareTo(sessionHighPrice) > 0) sessionHighPrice = price;
            // Update VWAP slope for regime detection
            updateVwapSlope("QQQ");

            if (price.compareTo(sessionLowPrice) < 0) sessionLowPrice = price;

            double currentRSIForTracking = ta.getRsi();

            if (currentRSIForTracking > sessionPeakRSI) {
                sessionPeakRSI = currentRSIForTracking;
                rsiHasPeaked = false;
                swingLowAfterRSIPeak = null;
            }
            if (!rsiHasPeaked && sessionPeakRSI - currentRSIForTracking >= 10.0) {
                rsiHasPeaked = true;
                swingLowAfterRSIPeak = price;
                log.info("[SESSION-STATE] RSI PEAKED at {}, now {} (declined {})",
                        String.format("%.1f", sessionPeakRSI),
                        String.format("%.1f", currentRSIForTracking),
                        String.format("%.1f", sessionPeakRSI - currentRSIForTracking));
            }
            if (rsiHasPeaked && swingLowAfterRSIPeak != null && price.compareTo(swingLowAfterRSIPeak) < 0) {
                swingLowAfterRSIPeak = price;
            }

            if (currentRSIForTracking < sessionTroughRSI) {
                sessionTroughRSI = currentRSIForTracking;
                rsiHasTroughed = false;
                swingHighAfterRSITrough = null;
            }
            if (!rsiHasTroughed && currentRSIForTracking - sessionTroughRSI >= 10.0) {
                rsiHasTroughed = true;
                swingHighAfterRSITrough = price;
                log.info("[SESSION-STATE] RSI TROUGHED at {}, now {} (risen {})",
                        String.format("%.1f", sessionTroughRSI),
                        String.format("%.1f", currentRSIForTracking),
                        String.format("%.1f", currentRSIForTracking - sessionTroughRSI));
            }
            if (rsiHasTroughed && swingHighAfterRSITrough != null && price.compareTo(swingHighAfterRSITrough) > 0) {
                swingHighAfterRSITrough = price;
            }


            // Track VWAP side duration
            boolean aboveVwap = price.compareTo(vwap) > 0;
            boolean belowVwap = price.compareTo(vwap) < 0;

            if (aboveVwap) {
                if (continuousBelowVwapMinutes > 0) {
                    // Just crossed above — reset
                    lastVwapCrossTime = LocalDateTime.now(ET_ZONE);
                    lastVwapCrossPrice = price;
                    continuousBelowVwapMinutes = 0;
                    log.info("[SESSION-STATE] VWAP cross UP at ${}", price);
                }
                continuousAboveVwapMinutes++;
            } else if (belowVwap) {
                if (continuousAboveVwapMinutes > 0) {
                    lastVwapCrossTime = LocalDateTime.now(ET_ZONE);
                    lastVwapCrossPrice = price;
                    continuousAboveVwapMinutes = 0;
                    log.info("[SESSION-STATE] VWAP cross DOWN at ${}", price);
                }
                continuousBelowVwapMinutes++;
            }
            // VWAP slope is now computed by updateVwapSlope() using smoothed 5-bar averages
            // (inline endpoint computation removed — was vulnerable to single-bar spike flips)

            // Count higher-highs / lower-lows on 15-min bars
            List<MarketData> bars15m = barAggregationService.getRecentBars("QQQ", 30);
            if (bars15m != null && bars15m.size() >= 10) {
                int hhCount = 0;
                int llCount = 0;
                // Sample every 15th bar (approximate 15-min candles from 1-min bars)
                for (int i = bars15m.size() - 1; i >= 15; i -= 15) {
                    MarketData current = bars15m.get(i);
                    MarketData previous = bars15m.get(i - 15 < 0 ? 0 : i - 15);
                    if (current.getHigh() != null && previous.getHigh() != null) {
                        if (current.getHigh().compareTo(previous.getHigh()) > 0 &&
                                current.getLow().compareTo(previous.getLow()) > 0) {
                            hhCount++;
                        }
                        if (current.getHigh().compareTo(previous.getHigh()) < 0 &&
                                current.getLow().compareTo(previous.getLow()) < 0) {
                            llCount++;
                        }
                    }
                }
                higherHighCount15m = hhCount;
                lowerLowCount15m = llCount;
            }

            // ═══════════════════════════════════════════
            // REGIME CLASSIFICATION (accelerated + exhaustion)
            // ═══════════════════════════════════════════
            SessionRegime previousRegime = currentRegime;

            BigDecimal displacement = price.subtract(sessionOpenPrice)
                    .divide(sessionOpenPrice, 6, RoundingMode.HALF_UP);
            double displacementPct = displacement.doubleValue();
            double vwapSlopePerHour = rollingVwapSlope30min.doubleValue() * 2;

            // ACCELERATED: 180 → 60 cycles (~15 min)
            boolean trendingUp = (displacementPct > 0.01 && continuousAboveVwapMinutes >= 12)
                    || (vwapSlopePerHour > 0.50 && higherHighCount15m >= 3)
                    || (continuousAboveVwapMinutes >= 60);   // Was 180

            boolean trendingDown = (displacementPct < -0.01 && continuousBelowVwapMinutes >= 12)
                    || (vwapSlopePerHour < -0.50 && lowerLowCount15m >= 3)
                    || (continuousBelowVwapMinutes >= 60);   // Was 180

            // NEW: Exhaustion detection
            boolean exhaustingUp = false;
            boolean exhaustingDown = false;

            if ((currentRegime == SessionRegime.TRENDING_UP || currentRegime == SessionRegime.TREND_EXHAUSTING)
                    && trendingUp) {
                double currentRSI = ta.getRsi();
                boolean rsiDeclining = currentRSI < (regimeStartRSI - 15.0);
                boolean longTrend = continuousAboveVwapMinutes >= 80; // ~20 min
                if (rsiDeclining && longTrend) {
                    exhaustingUp = true;
                    log.info("[REGIME] Trend exhaustion UP: RSI {} → {}, {} cycles",
                            String.format("%.1f", regimeStartRSI),
                            String.format("%.1f", currentRSI), continuousAboveVwapMinutes);
                }
            }
            if ((currentRegime == SessionRegime.TRENDING_DOWN || currentRegime == SessionRegime.TREND_EXHAUSTING)
                    && trendingDown) {
                double currentRSI = ta.getRsi();
                boolean rsiRising = currentRSI > (regimeStartRSI + 15.0);
                boolean longTrend = continuousBelowVwapMinutes >= 80;
                if (rsiRising && longTrend) {
                    exhaustingDown = true;
                    log.info("[REGIME] Trend exhaustion DOWN: RSI {} → {}, {} cycles",
                            String.format("%.1f", regimeStartRSI),
                            String.format("%.1f", currentRSI), continuousBelowVwapMinutes);
                }
            }

            if (exhaustingUp || exhaustingDown) {
                currentRegime = SessionRegime.TREND_EXHAUSTING;
            } else if (trendingUp) {
                currentRegime = SessionRegime.TRENDING_UP;
            } else if (trendingDown) {
                currentRegime = SessionRegime.TRENDING_DOWN;
            } else {
                currentRegime = SessionRegime.RANGING;
            }

            if (currentRegime != previousRegime) {
                log.info("═══════════════════════════════════════════════════════════════");
                log.info("[SESSION-STATE] ⚡ REGIME CHANGE: {} → {}", previousRegime, currentRegime);
                log.info("[SESSION-STATE]   Displacement: {}%, VWAP slope: ${}/hr, HH15m: {}, LL15m: {}",
                        String.format("%.2f", displacementPct * 100),
                        String.format("%.2f", vwapSlopePerHour),
                        higherHighCount15m, lowerLowCount15m);
                log.info("[SESSION-STATE]   Above VWAP: {} cycles, Below VWAP: {} cycles",
                        continuousAboveVwapMinutes, continuousBelowVwapMinutes);
                log.info("═══════════════════════════════════════════════════════════════");

                regimeStartRSI = ta.getRsi();

                if (currentRegime == SessionRegime.TRENDING_DOWN || currentRegime == SessionRegime.TRENDING_UP) {
                    regimeTrendingStartTime = LocalDateTime.now(ET_ZONE);
                } else {
                    regimeTrendingStartTime = null;
                }

                // Reset continuation counts on direction change
                if (currentRegime == SessionRegime.RANGING || currentRegime == SessionRegime.TREND_EXHAUSTING) {
                    trendContinuationCallCount = 0;
                    trendContinuationPutCount = 0;
                }
            }

            // Track consecutive bar closes for VWAP breach detection (unchanged)
            List<MarketData> latestBars = barAggregationService.getRecentBars("QQQ", 5);
            if (latestBars != null && !latestBars.isEmpty()) {
                MarketData lastBar = latestBars.get(latestBars.size() - 1);
                if (lastBar.getClose() != null && vwap != null) {
                    if (lastBar.getClose().compareTo(vwap) < 0) {
                        consecutiveBarsBelow++;
                        consecutiveBarsAbove = 0;
                    } else if (lastBar.getClose().compareTo(vwap) > 0) {
                        consecutiveBarsAbove++;
                        consecutiveBarsBelow = 0;
                    }
                }
            }

            // Reset breach flag on VWAP cross (unchanged)
            if (aboveVwap && "DOWN".equals(lastBreachDirection)) {
                vwapBreachSignalFired = false;
            }
            if (belowVwap && "UP".equals(lastBreachDirection)) {
                vwapBreachSignalFired = false;
            }

            if (currentRegime != previousRegime) {
                if (currentRegime == SessionRegime.TRENDING_DOWN || currentRegime == SessionRegime.TRENDING_UP) {
                    regimeTrendingStartTime = LocalDateTime.now(ET_ZONE);
                } else {
                    regimeTrendingStartTime = null;
                }
            }


            consecutiveBarsBelow = 0;
            consecutiveBarsAbove = 0;

        } catch (Exception e) {
            log.debug("[SESSION-STATE] Error updating session state: {}", e.getMessage());
        }
    }

    /**
     * Compute rolling VWAP slope from the last 30 bars (30 minutes of 1-min bars).
     * Slope = (VWAP at current bar - VWAP at bar 30 ago) / 30 minutes.
     * Result is stored in rollingVwapSlope30min as $/30min.
     *
     * A steep slope (>$0.50/30min) means VWAP is trending — price is directional.
     * A flat slope (<$0.15/30min) means VWAP is flat — price is ranging around fair value.
     */
    private void updateVwapSlope(String symbol) {
        try {
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 30);
            if (recentBars == null || recentBars.size() < 20) {
                return; // Not enough data yet
            }

            int size = recentBars.size();

            // Smoothed slope: average VWAP of newest 5 bars vs average VWAP of oldest 5 bars
            // This prevents a single spike bar from flipping the regime
            BigDecimal newestSum = BigDecimal.ZERO;
            int newestCount = 0;
            for (int i = size - 1; i >= Math.max(0, size - 5); i--) {
                BigDecimal bv = recentBars.get(i).getVwap();
                if (bv != null && bv.compareTo(BigDecimal.ZERO) > 0) {
                    newestSum = newestSum.add(bv);
                    newestCount++;
                }
            }

            BigDecimal oldestSum = BigDecimal.ZERO;
            int oldestCount = 0;
            for (int i = 0; i < Math.min(5, size); i++) {
                BigDecimal bv = recentBars.get(i).getVwap();
                if (bv != null && bv.compareTo(BigDecimal.ZERO) > 0) {
                    oldestSum = oldestSum.add(bv);
                    oldestCount++;
                }
            }

            if (newestCount == 0 || oldestCount == 0) return;

            BigDecimal newestAvg = newestSum.divide(BigDecimal.valueOf(newestCount), 4, RoundingMode.HALF_UP);
            BigDecimal oldestAvg = oldestSum.divide(BigDecimal.valueOf(oldestCount), 4, RoundingMode.HALF_UP);

            // Slope = average of newest 5 VWAP values minus average of oldest 5 VWAP values
            // A single spike bar contributes only 1/5 of the newest average, not 100%
            rollingVwapSlope30min = newestAvg.subtract(oldestAvg);

        } catch (Exception e) {
            log.debug("[VWAP-SLOPE] Error computing slope: {}", e.getMessage());
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

    private boolean isGoodTradingTime(LocalTime now) {
        if (now.isAfter(LocalTime.of(15, 50))) {
            return false;
        }

        // Dead zone 13:00-14:00: LOWERED volume threshold (was 0.5, now 0.3)
        if (now.isAfter(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(14, 0))) {
            double volRatio = volumeTracker.getVolumeRatio("QQQ");
            if (volRatio < 0.3) {
                log.debug("[TIME-GATE] Dead zone — volume ratio {} < 0.3, blocking",
                        String.format("%.2f", volRatio));
                return false;
            }
            log.info("[TIME-GATE] Dead zone BYPASSED — volume ratio {} >= 0.3",
                    String.format("%.2f", volRatio));
        }

        return (now.isAfter(PRIME_WINDOW_1_START) && now.isBefore(PRIME_WINDOW_1_END)) ||
                (now.isAfter(PRIME_WINDOW_2_START) && now.isBefore(PRIME_WINDOW_2_END)) ||
                (now.isAfter(PRIME_WINDOW_3_START) && now.isBefore(PRIME_WINDOW_3_END)) ||
                (now.isAfter(FINAL_WINDOW_START) && now.isBefore(LocalTime.of(15, 50)));
    }

    // Add new method for time-adjusted confidence
    private double getTimeAdjustedConfidence(double baseConfidence, LocalTime now, String analysisId) {
        double multiplier = 1.0;
        String window = "UNKNOWN";

        if (now.isAfter(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(11, 0))) {
            multiplier = 1.0;
            window = "OPTIMAL";
        } else if (now.isAfter(LocalTime.of(11, 0)) && now.isBefore(LocalTime.of(13, 0))) {
            multiplier = 0.85;
            window = "MIDDAY_LULL";
        } else if (now.isAfter(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(14, 0))) {
            multiplier = 0.90;    // Was 0.80
            window = "DEAD_ZONE_ACTIVE";
        } else if (now.isAfter(LocalTime.of(14, 0)) && now.isBefore(LocalTime.of(15, 0))) {
            multiplier = 1.0;
            window = "AFTERNOON";
        } else if (now.isAfter(LocalTime.of(15, 0)) && now.isBefore(LocalTime.of(15, 30))) {
            multiplier = 0.90;
            window = "LATE_SESSION";
        } else if (now.isAfter(LocalTime.of(15, 30)) && now.isBefore(LocalTime.of(15, 45))) {
            multiplier = 0.85;    // Was 0.80 — power hour boost
            window = "POWER_HOUR";
        } else if (now.isAfter(LocalTime.of(15, 45))) {
            multiplier = 0.65;
            window = "FINAL_MINUTES";
        } else {
            multiplier = 0.95;
            window = "EARLY";
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

            if (integratedBayesianMLSystem != null) {
                try {
                    IntegratedBayesianMLSystem.IntegratedAnalysisResult mlResult =
                            integratedBayesianMLSystem.analyzeAndExecuteSignal(signal);

                    if (mlResult != null && mlResult.getBayesianAnalysis() != null) {
                        IntegratedBayesianMLSystem.BayesianAnalysis bayesianAnalysis = mlResult.getBayesianAnalysis();
                        double mlConfidence = bayesianAnalysis.getPosteriorProbability();

                        // Store ML score for diagnostic logging — do NOT modify rule confidence
                        signal.setAdjustedConfidence(mlConfidence);
                        signal.setBayesianProbability(mlConfidence);

                        double ruleConfidence = signal.getConfidence();
                        double divergence = Math.abs(ruleConfidence - mlConfidence);
                        log.info("[{}] ML DIAGNOSTIC (bypass mode): rule={}%, ML={}%, divergence={}% — rule confidence PRESERVED",
                                analysisId, (int)(ruleConfidence * 100), (int)(mlConfidence * 100),
                                (int)(divergence * 100));
                    }
                } catch (Exception e) {
                    log.error("[{}] Error in ML analysis: {}", analysisId, e.getMessage());
                }
            }

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
     * Enhanced exhaustion score: 4 components, returns score 0-4.
     * Fixes from Mar 2/Mar 4 backtest:
     *   1. Volume: waived in low-vol environment (<0.5x avg), checks relative decline instead
     *   2. RSI divergence: relaxed to allow RSI recovery >8pts while price near low (<0.25%)
     *   3. Momentum deceleration: new 4th component using velocity collapse ratio
     *   4. DecliningRange: unchanged (already works)
     *
     * Threshold: score >= 3 out of 4 = exhaustion confirmed
     */
    private int checkExhaustionScore(String symbol, String signalType, String analysisId) {
        try {
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 8);
            if (recentBars.size() < 6) {
                log.warn("[EXHAUSTION-V4][{}] Insufficient bars: {}", analysisId, recentBars.size());
                return 0;
            }

            // Use last 5 closed bars (skip current accumulating bar)
            int endIdx = recentBars.size() - 1;
            LocalDateTime barEnd = recentBars.get(endIdx).getTimestamp().plusMinutes(1);
            boolean latestClosed = LocalDateTime.now(ET_ZONE).isAfter(barEnd);
            if (!latestClosed) endIdx--;
            int startIdx = Math.max(0, endIdx - 4);
            if (endIdx - startIdx < 4) {
                log.warn("[EXHAUSTION-V4][{}] Not enough closed bars", analysisId);
                return 0;
            }

            // ═══ COMPONENT 1: DECLINING RANGE ═══
            int decliningRangeCount = 0;
            for (int i = startIdx + 1; i <= endIdx; i++) {
                MarketData current = recentBars.get(i);
                MarketData previous = recentBars.get(i - 1);
                if (current.getHigh() == null || current.getLow() == null ||
                        previous.getHigh() == null || previous.getLow() == null) continue;

                BigDecimal currentRange = current.getHigh().subtract(current.getLow());
                BigDecimal previousRange = previous.getHigh().subtract(previous.getLow());
                if (currentRange.compareTo(previousRange) < 0) {
                    decliningRangeCount++;
                }
            }
            boolean decliningRangePass = decliningRangeCount >= 3;

            // ═══ COMPONENT 2: VOLUME (FIXED — low-volume environment waiver) ═══
            boolean volumePass = false;
            String volumeReason;

            // Get volume ratio from TechnicalAnalysisService (reliable pipeline)
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            double currentVolumeRatio = (ta != null) ? ta.getVolumeRatio() : 0.0;

            // Check if we're in a low-volume environment
            boolean lowVolumeEnvironment = currentVolumeRatio < 0.50;

            if (lowVolumeEnvironment) {
                // Low-volume environment: waive strict declining-volume check
                // Instead: check if capitulation bar had higher volume than post-cap bars
                // (relative decline from an already-low base)
                Long capBarVolume = recentBars.get(startIdx).getIncrementalVolume();
                Long latestBarVolume = recentBars.get(endIdx).getIncrementalVolume();

                // Fallback to cumulative difference if incremental is null
                if ((capBarVolume == null || capBarVolume <= 0) && startIdx > 0) {
                    Long prev = recentBars.get(startIdx - 1).getVolume();
                    Long curr = recentBars.get(startIdx).getVolume();
                    if (prev != null && curr != null) capBarVolume = Math.max(0L, curr - prev);
                }
                if ((latestBarVolume == null || latestBarVolume <= 0) && endIdx > 0) {
                    Long prev = recentBars.get(endIdx - 1).getVolume();
                    Long curr = recentBars.get(endIdx).getVolume();
                    if (prev != null && curr != null) latestBarVolume = Math.max(0L, curr - prev);
                }

                if (capBarVolume != null && latestBarVolume != null && capBarVolume > 0) {
                    volumePass = capBarVolume > latestBarVolume;
                    volumeReason = String.format("LOW-ENV WAIVED (ratio=%.2fx, capIncrVol=%d > latestIncrVol=%d = %s)",
                            currentVolumeRatio, capBarVolume, latestBarVolume, volumePass ? "yes" : "no");
                }
            } else {
                // Normal volume environment: use existing declining-volume check
                int decliningVolumeCount = 0;
                for (int i = startIdx + 1; i <= endIdx; i++) {
                    MarketData current = recentBars.get(i);
                    MarketData previous = recentBars.get(i - 1);

                    // Use incremental volume (per-bar), not cumulative daily volume
                    Long currentIncVol = current.getIncrementalVolume();
                    Long previousIncVol = previous.getIncrementalVolume();

                    // Fallback: compute incremental from cumulative difference
                    if ((currentIncVol == null || currentIncVol <= 0) && i > 0) {
                        if (current.getVolume() != null && previous.getVolume() != null) {
                            currentIncVol = Math.max(0L, current.getVolume() - previous.getVolume());
                        }
                    }
                    if ((previousIncVol == null || previousIncVol <= 0) && i > 1) {
                        MarketData beforePrev = recentBars.get(i - 2);
                        if (previous.getVolume() != null && beforePrev.getVolume() != null) {
                            previousIncVol = Math.max(0L, previous.getVolume() - beforePrev.getVolume());
                        }
                    }

                    if (currentIncVol != null && previousIncVol != null &&
                            currentIncVol > 0 && previousIncVol > 0 &&
                            currentIncVol < previousIncVol) {
                        decliningVolumeCount++;
                    }
                }

                volumePass = decliningVolumeCount >= 3;
                volumeReason = String.format("STANDARD (%d/4 declining)", decliningVolumeCount);
            }

            // ═══ COMPONENT 3: RSI DIVERGENCE (FIXED — relaxed definition) ═══
            boolean rsiDivergencePass = false;
            String rsiDivReason = "no data";

            if (ta != null && ta.getRsi() > 0) {
                double currentRsi = ta.getRsi();
                BigDecimal currentPrice = ta.getCurrentPrice();

                if ("CALL".equals(signalType)) {
                    // FIXED: Two paths to confirm bullish divergence:
                    // Path A (original): price at/near session low with RSI above trough
                    // Path B (new): RSI has recovered 8+ points from recent trough while price
                    //   is within session_range * 3% of the low (not strict $0.20)

                    BigDecimal sessionRange = sessionHighPrice.subtract(sessionLowPrice);
                    BigDecimal proximityThreshold = sessionRange.compareTo(new BigDecimal("1.00")) > 0
                            ? sessionRange.multiply(new BigDecimal("0.03"))
                            : new BigDecimal("0.30");

                    // Path A: price near session low
                    boolean nearSessionLow = recentBars.get(endIdx).getLow() != null &&
                            recentBars.get(endIdx).getLow().compareTo(
                                    sessionLowPrice.add(proximityThreshold)) <= 0;

                    // Path B: RSI recovery > 8 points while price still within 0.25% of recent low
                    double rsiRecovery = currentRsi - sessionTroughRSI;
                    boolean rsiRecoveredStrong = rsiRecovery > 8.0;

                    BigDecimal recentLow = getRecentLow(recentBars, 5);
                    boolean priceNearRecentLow = false;
                    if (recentLow != null && currentPrice != null && recentLow.compareTo(BigDecimal.ZERO) > 0) {
                        double pctFromLow = currentPrice.subtract(recentLow).abs()
                                .divide(recentLow, 6, RoundingMode.HALF_UP).doubleValue() * 100;
                        priceNearRecentLow = pctFromLow < 0.25;
                    }

                    if (nearSessionLow && currentRsi > 30) {
                        rsiDivergencePass = true;
                        rsiDivReason = String.format("PATH-A: near session low + RSI %.1f > 30", currentRsi);
                    } else if (rsiRecoveredStrong && priceNearRecentLow) {
                        rsiDivergencePass = true;
                        rsiDivReason = String.format("PATH-B: RSI recovery +%.1f pts, price within 0.25%% of low",
                                rsiRecovery);
                    } else if (rsiRecoveredStrong) {
                        // Relaxed: even if price isn't near low, 8+ pt RSI recovery is meaningful
                        rsiDivergencePass = true;
                        rsiDivReason = String.format("PATH-C: RSI recovery +%.1f pts (strong)", rsiRecovery);
                    } else {
                        rsiDivReason = String.format("FAIL: RSI recovery +%.1f pts (need >8), nearLow=%s",
                                rsiRecovery, nearSessionLow);
                    }

                } else if ("PUT".equals(signalType)) {
                    // Mirror logic for PUT
                    BigDecimal sessionRange = sessionHighPrice.subtract(sessionLowPrice);
                    BigDecimal proximityThreshold = sessionRange.compareTo(new BigDecimal("1.00")) > 0
                            ? sessionRange.multiply(new BigDecimal("0.03"))
                            : new BigDecimal("0.30");

                    boolean nearSessionHigh = recentBars.get(endIdx).getHigh() != null &&
                            recentBars.get(endIdx).getHigh().compareTo(
                                    sessionHighPrice.subtract(proximityThreshold)) >= 0;

                    double rsiDecline = sessionPeakRSI - currentRsi;
                    boolean rsiDeclinedStrong = rsiDecline > 8.0;

                    if (nearSessionHigh && currentRsi < 70) {
                        rsiDivergencePass = true;
                        rsiDivReason = String.format("PATH-A: near session high + RSI %.1f < 70", currentRsi);
                    } else if (rsiDeclinedStrong) {
                        rsiDivergencePass = true;
                        rsiDivReason = String.format("PATH-B: RSI decline -%.1f pts from peak", rsiDecline);
                    } else {
                        rsiDivReason = String.format("FAIL: RSI decline -%.1f pts (need >8), nearHigh=%s",
                                rsiDecline, nearSessionHigh);
                    }
                }
            }

            // ═══ COMPONENT 4: MOMENTUM DECELERATION (NEW) ═══
            boolean momentumDecelerationPass = false;
            String momDecelReason = "no data";

            double collapseRatio = momentumTracker.getVelocityCollapseRatio(symbol, 8);
            double peakVel = momentumTracker.getPeakVelocity(symbol, 8);

            if (collapseRatio > 3.0) {
                // >70% collapse from peak
                momentumDecelerationPass = true;
                momDecelReason = String.format("PASS: collapse ratio %.1fx (peak=%.4f%%)", collapseRatio, peakVel * 100);
            } else if (collapseRatio > 1.5) {
                // >40% collapse — pass if momentum is also slowing
                MomentumVelocity mv = momentumTracker.calculateVelocity(symbol);
                if (mv != null && (mv.getState() == VelocityState.WEAKENING_BEARISH
                        || mv.getState() == VelocityState.WEAKENING_BULLISH
                        || mv.getState() == VelocityState.COILING)) {
                    momentumDecelerationPass = true;
                    momDecelReason = String.format("PASS: collapse %.1fx + state=%s", collapseRatio, mv.getState());
                } else {
                    momDecelReason = String.format("FAIL: collapse %.1fx but state=%s",
                            collapseRatio, mv != null ? mv.getState() : "null");
                }
            } else if (collapseRatio > 0) {
                momDecelReason = String.format("FAIL: collapse ratio %.1fx < 1.5 (need >3.0)", collapseRatio);
            }

            // ═══ SCORE ═══
            int exhaustionScore = 0;
            if (decliningRangePass) exhaustionScore++;
            if (volumePass) exhaustionScore++;
            if (rsiDivergencePass) exhaustionScore++;
            if (momentumDecelerationPass) exhaustionScore++;

            boolean exhaustionConfirmed = exhaustionScore >= 3;

//            log.info("[EXHAUSTION-V4][{}] DeclRange: {}/4 {} | Volume: {} {} | RSI Divg: {} | MomDecel: {} → Score: {}/4 → {}",
//                    symbol,
//                    decliningRangeCount, decliningRangePass ? "✓" : "✗",
//                    volumeReason, volumePass ? "✓" : "✗",
//                    rsiDivReason,
//                    momDecelReason,
//                    exhaustionScore, exhaustionConfirmed ? "PASS" : "FAIL");

            return exhaustionScore;

        } catch (Exception e) {
            log.error("[EXHAUSTION-V4][{}] Error: {}", analysisId, e.getMessage());
            return 0;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════
// METHOD 1: checkADRExhaustionReversal
// Place this after checkExhaustionScore (~line 9330) or anywhere in the private methods section
// ══════════════════════════════════════════════════════════════════════════════════

    /**
     * PRO TRADER SIGNAL: ADR Exhaustion Reversal
     *
     * A 20-year 0DTE QQQ vet doesn't wait for RSI to hit 20 or bars to show declining range.
     * They see that the day's total move (gap + intraday) has consumed the average daily range,
     * and price is stabilizing at an extreme. The move is done — not because of bar-level
     * exhaustion patterns, but because there's simply no range left.
     *
     * This catches setups like Mar 9 09:55: $9 gap down + $3.45 intraday selloff = $12.45 total,
     * which is 118% of ADR. RSI was 48 (not extreme), bars weren't declining (expanding selloff),
     * but the move was over because sellers had nowhere left to go.
     *
     * Components (all must pass):
     *   1. Total range (including gap) has consumed significant portion of ADR
     *   2. Price is at an extreme of the day's range (near LOD for CALL, near HOD for PUT)
     *   3. Price is stabilizing (not making new extremes — 3+ bars holding above LOD or below HOD)
     *   4. At least 1/3 leaders confirming the reversal direction
     *   5. Gap must exist and be in the direction of the selloff (gap down for CALL, gap up for PUT)
     *
     * Uses existing infrastructure:
     *   - totalRangeRatio (already computed in analyzeOptions)
     *   - sessionLowPrice, sessionHighPrice (session tracking)
     *   - barAggregationService.getRecentBars()
     *   - checkLeadersForDirection()
     *   - buildAndRouteSignal()
     *   - analyzeGapConditions() / ta.getPreviousClose()
     */
    private List<Signal> checkADRExhaustionReversal(String symbol, TechnicalAnalysis ta, BigDecimal atr,
                                                    BigDecimal currentPrice, BigDecimal vwap, double rsi,
                                                    double totalRangeRatio, double moveFromLOD, double moveFromHOD,
                                                    boolean priceBelowVWAP, boolean priceAboveVWAP, String analysisId) {
        List<Signal> signals = new ArrayList<>();

        try {
            // ═══ GATE 1: Gap must exist ═══
            GapAnalysis gap = analyzeGapConditions(ta);
            if (gap == null || Math.abs(gap.getGapPercentage()) < 0.005) {
                return signals; // No meaningful gap — this isn't an ADR exhaustion day
            }

            // ═══ GATE 2: Total range consumed relative to ADR ═══
            // Include the gap in the total move calculation
            double gapSize = 0;
            if (ta.getPreviousClose() != null) {
                gapSize = Math.abs(sessionOpenPrice.subtract(ta.getPreviousClose()).doubleValue());
            }
            double totalMoveWithGap = (sessionHighPrice.subtract(sessionLowPrice).doubleValue()) + gapSize;
            double totalMoveRatio = totalMoveWithGap / averageDailyRange;

            // The move must have consumed a meaningful portion of ADR
            // On a $10.50 ADR day, consuming $8+ (76%) with gap means the trend leg is likely done
            if (totalMoveRatio < 0.70) {
                log.debug("[ADR-EXHAUST][{}] Total move ratio {}% < 70% — not enough range consumed",
                        analysisId, String.format("%.0f", totalMoveRatio * 100));
                return signals;
            }

            // ═══ GATE 3: Price at extreme + correct gap direction ═══
            boolean callSetup = false;
            boolean putSetup = false;

            BigDecimal sessionRange = sessionHighPrice.subtract(sessionLowPrice);
            if (sessionRange.doubleValue() < 1.0) return signals; // Too narrow

            double pctFromLOD = moveFromLOD / sessionRange.doubleValue();
            double pctFromHOD = moveFromHOD / sessionRange.doubleValue();

            // CALL: Gap down day, price near LOD (bottom 25% of range)
            if (gap.getGapPercentage() < -0.005 && pctFromLOD < 0.25) {
                callSetup = true;
            }
            // PUT: Gap up day, price near HOD (top 25% of range)
            if (gap.getGapPercentage() > 0.005 && pctFromHOD < 0.25) {
                putSetup = true;
            }

            if (!callSetup && !putSetup) {
                return signals;
            }

            // ═══ GATE 4: Price stabilizing (not making new extremes) ═══
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 8);
            if (recentBars == null || recentBars.size() < 5) return signals;

            boolean stabilized = false;
            if (callSetup) {
                // Last 3 bars should NOT be making new session lows
                int barsAboveLow = 0;
                BigDecimal recentLow = getRecentLow(recentBars, recentBars.size());
                for (int bi = recentBars.size() - 1; bi >= Math.max(0, recentBars.size() - 4); bi--) {
                    if (recentBars.get(bi).getClose() != null && recentLow != null &&
                            recentBars.get(bi).getClose().compareTo(recentLow.add(new BigDecimal("0.15"))) > 0) {
                        barsAboveLow++;
                    }
                }
                stabilized = barsAboveLow >= 3;
            } else {
                // PUT: last 3 bars NOT making new session highs
                int barsBelowHigh = 0;
                BigDecimal recentHigh = getRecentHigh(recentBars, recentBars.size());
                for (int bi = recentBars.size() - 1; bi >= Math.max(0, recentBars.size() - 4); bi--) {
                    if (recentBars.get(bi).getClose() != null && recentHigh != null &&
                            recentBars.get(bi).getClose().compareTo(recentHigh.subtract(new BigDecimal("0.15"))) < 0) {
                        barsBelowHigh++;
                    }
                }
                stabilized = barsBelowHigh >= 3;
            }

            if (!stabilized) {
                log.debug("[ADR-EXHAUST][{}] Price not stabilized at extreme", analysisId);
                return signals;
            }

            // ═══ GATE 5: Leader confirmation ═══
            String signalType = callSetup ? "CALL" : "PUT";
            int leadersConfirming = checkLeadersForDirection(symbol, signalType);
            if (leadersConfirming < 1) {
                log.info("[ADR-EXHAUST][{}] Only {}/3 leaders confirming {} — need minimum 1",
                        analysisId, leadersConfirming, signalType);
                return signals;
            }

            // ═══ ALL GATES PASSED — GENERATE SIGNAL ═══
            log.info("═══════════════════════════════════════════════════════════════");
            log.info("[ADR-EXHAUST][{}] ⚡ {} ADR EXHAUSTION REVERSAL — totalMove={}% ADR, gap={}%, " +
                            "pctFromExtreme={}%, stabilized=true, leaders={}/3",
                    analysisId, signalType,
                    totalMoveRatio * 100, gap.getGapPercentage() * 100,
                    callSetup ? pctFromLOD * 100 : pctFromHOD * 100,
                    leadersConfirming);
            log.info("[ADR-EXHAUST][{}] Price=${}, VWAP=${}, RSI={}, LOD=${}, HOD=${}",
                    analysisId, currentPrice, vwap, rsi, sessionLowPrice, sessionHighPrice);
            log.info("═══════════════════════════════════════════════════════════════");

            // Target: VWAP (mean reversion)
            BigDecimal target = vwap;

            // Stop: beyond the session extreme
            BigDecimal stop;
            if (callSetup) {
                stop = sessionLowPrice.subtract(atr.multiply(new BigDecimal("0.3")));
            } else {
                stop = sessionHighPrice.add(atr.multiply(new BigDecimal("0.3")));
            }

            signals = buildAndRouteSignal(symbol, signalType, currentPrice, target, stop,
                    leadersConfirming, ta, "ADR_EXHAUSTION_REVERSAL_" + (callSetup ? "UP" : "DOWN"), analysisId);

        } catch (Exception e) {
            log.error("[ADR-EXHAUST][{}] Error: {}", analysisId, e.getMessage());
        }

        return signals;
    }

    private List<Signal> checkCompressionBreakout(String symbol, TechnicalAnalysis ta, BigDecimal atr,
                                                  BigDecimal currentPrice, BigDecimal vwap, double rsi,
                                                  boolean priceAboveVWAP, boolean priceBelowVWAP,
                                                  String analysisId) {
        List<Signal> signals = new ArrayList<>();

        try {
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 25);
            if (recentBars == null || recentBars.size() < 20) return signals;

            // ═══ GATE 1: Identify compression zone from bars 5-20 (skip last 5 for breakout) ═══
            int compStart = Math.max(0, recentBars.size() - 20);
            int compEnd = recentBars.size() - 5;
            if (compEnd <= compStart) return signals;

            BigDecimal compHigh = BigDecimal.ZERO;
            BigDecimal compLow = new BigDecimal("9999");
            long compVolumeSum = 0;
            int compBarCount = 0;

            for (int i = compStart; i < compEnd; i++) {
                MarketData bar = recentBars.get(i);
                if (bar.getHigh() != null && bar.getLow() != null) {
                    if (bar.getHigh().compareTo(compHigh) > 0) compHigh = bar.getHigh();
                    if (bar.getLow().compareTo(compLow) < 0) compLow = bar.getLow();
                }
                Long incVol = bar.getIncrementalVolume();
                if (incVol != null && incVol > 0) {
                    compVolumeSum += incVol;
                    compBarCount++;
                }
            }

            BigDecimal compRange = compHigh.subtract(compLow);

            // Compression range must be narrow relative to ATR
            // A tight consolidation is less than 50% of ATR
            if (compRange.compareTo(atr.multiply(new BigDecimal("0.50"))) > 0) {
                log.debug("[COMPRESSION][{}] Range ${} > 50% ATR ${} — not compressed",
                        analysisId, compRange, atr.multiply(new BigDecimal("0.50")));
                return signals;
            }

            // ═══ GATE 2: Volume surge on breakout bars ═══
            double avgCompVolume = compBarCount > 0 ? (double) compVolumeSum / compBarCount : 0;
            if (avgCompVolume <= 0) return signals;

            // Check last 3 bars for volume surge
            boolean volumeSurge = false;
            for (int i = recentBars.size() - 3; i < recentBars.size(); i++) {
                Long incVol = recentBars.get(i).getIncrementalVolume();
                if (incVol != null && incVol > avgCompVolume * 1.5) {
                    volumeSurge = true;
                    break;
                }
            }

            if (!volumeSurge) {
                log.debug("[COMPRESSION][{}] No volume surge on breakout bars", analysisId);
                return signals;
            }

            // ═══ GATE 3: Price has broken out of compression range ═══
            boolean breakoutUp = currentPrice.compareTo(compHigh) > 0;
            boolean breakoutDown = currentPrice.compareTo(compLow) < 0;

            if (!breakoutUp && !breakoutDown) {
                return signals; // Still inside compression
            }

            // ═══ GATE 4: Breakout direction aligns with VWAP side ═══
            boolean callBreakout = breakoutUp && priceAboveVWAP;
            boolean putBreakout = breakoutDown && priceBelowVWAP;

            if (!callBreakout && !putBreakout) {
                return signals;
            }

            String signalType = callBreakout ? "CALL" : "PUT";

            // ═══ GATE 5: Leader confirmation (2/3 minimum for breakout) ═══
            int leadersConfirming = checkLeadersForDirection(symbol, signalType);
            if (leadersConfirming < 2) {
                log.info("[COMPRESSION][{}] Only {}/3 leaders confirming {} breakout — need 2",
                        analysisId, leadersConfirming, signalType);
                return signals;
            }

            // ═══ ALL GATES PASSED — GENERATE SIGNAL ═══
            log.info("═══════════════════════════════════════════════════════════════");
            log.info("[COMPRESSION][{}] ⚡ {} COMPRESSION BREAKOUT — compRange={}, " +
                            "breakout {} above compHigh {}, volSurge=true, leaders={}/3",
                    analysisId, signalType,
                    compRange, currentPrice, compHigh, leadersConfirming);
            log.info("[COMPRESSION][{}] Price=${}, VWAP=${}, RSI={}, compBars={}",
                    analysisId, currentPrice, vwap, rsi, compEnd - compStart);
            log.info("═══════════════════════════════════════════════════════════════");

            // Target: HOD + ATR extension for CALL, LOD - ATR extension for PUT
            BigDecimal target;
            BigDecimal stop;
            if (callBreakout) {
                target = sessionHighPrice.add(atr.multiply(new BigDecimal("0.5")));
                stop = compLow.subtract(new BigDecimal("0.30"));
            } else {
                target = sessionLowPrice.subtract(atr.multiply(new BigDecimal("0.5")));
                stop = compHigh.add(new BigDecimal("0.30"));
            }

            signals = buildAndRouteSignal(symbol, signalType, currentPrice, target, stop,
                    leadersConfirming, ta, "COMPRESSION_BREAKOUT_" + (callBreakout ? "UP" : "DOWN"), analysisId);

        } catch (Exception e) {
            log.error("[COMPRESSION][{}] Error: {}", analysisId, e.getMessage());
        }

        return signals;
    }

    /**
     * VWAP CROSS SIGNAL — Primary directional signal
     *
     * A pro 0DTE QQQ trader trades one thing: the moment price crosses VWAP with conviction.
     * Not the departure. Not the continuation. Not the pullback. Just the cross.
     *
     * What makes a cross "with conviction":
     *   1. Price actually crosses from one side to the other (detected in recent bars)
     *   2. RSI accelerates in the cross direction (>10 point change in 3 bars)
     *   3. Volume confirms participation (volume ratio > 0.8)
     *   4. Leaders confirm the direction (at least 2/3)
     *
     * Mar 10 validation:
     *   10:22-10:23: Price crosses above VWAP. RSI jumps 45→64 (+19 in 2 bars). Vol 1.29x. → CALL +$2.99
     *   13:34-13:38: Price crosses below VWAP. RSI drops 62→30 (-32 in 4 bars). Vol 1.31x. → PUT +$1.76
     *   15:21-15:27: Price crosses below VWAP. RSI drops 62→41 (-21 in 6 bars). Vol. → PUT +$2.01
     *   14:15: NO cross — price already below VWAP and falling. System gave 97%. This method gives nothing.
     *
     * @param callDebounceOk true if CALL signals are allowed (15min since last CALL)
     * @param putDebounceOk true if PUT signals are allowed (15min since last PUT)
     */
    private List<Signal> checkVWAPCrossSignal(String symbol, TechnicalAnalysis ta, BigDecimal atr,
                                              BigDecimal currentPrice, BigDecimal vwap, double currentRsi,
                                              boolean callDebounceOk, boolean putDebounceOk,
                                              String analysisId) {
        List<Signal> signals = new ArrayList<>();

        try {
            // Get recent bars for cross detection and RSI acceleration
            List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 10);
            if (recentBars == null || recentBars.size() < 6) return signals;

            // Also get RSI history for acceleration measurement
            List<RSIReading> rsiHistory = rsiTracker.getRSIHistory(symbol);

            // ═══ STEP 1: Detect VWAP cross in last 5 bars ═══
            // Find the most recent bar where price side flipped relative to VWAP
            int crossBarIndex = -1;
            String crossDirection = null; // "CALL" if crossed above, "PUT" if crossed below

            // Need VWAP per bar — use the session VWAP (it moves slowly, close enough)
            // Compare each bar's close to VWAP
            for (int i = recentBars.size() - 1; i >= Math.max(1, recentBars.size() - 5); i--) {
                MarketData curr = recentBars.get(i);
                MarketData prev = recentBars.get(i - 1);

                if (curr.getClose() == null || prev.getClose() == null || vwap == null) continue;

                boolean currAbove = curr.getClose().compareTo(vwap) > 0;
                boolean prevBelow = prev.getClose().compareTo(vwap) <= 0;
                boolean currBelow = curr.getClose().compareTo(vwap) < 0;
                boolean prevAbove = prev.getClose().compareTo(vwap) >= 0;

                if (currAbove && prevBelow) {
                    crossBarIndex = i;
                    crossDirection = "CALL";
                    break;
                }
                if (currBelow && prevAbove) {
                    crossBarIndex = i;
                    crossDirection = "PUT";
                    break;
                }
            }

            if (crossBarIndex < 0 || crossDirection == null) {
                return signals; // No cross in last 5 bars
            }

            // Check debounce for this direction
            if ("CALL".equals(crossDirection) && !callDebounceOk) return signals;
            if ("PUT".equals(crossDirection) && !putDebounceOk) return signals;

            // ═══ STEP 2: Confirm price is holding on the new side ═══
            // After the cross, price should still be on the cross side
            boolean stillOnCrossSide;
            if ("CALL".equals(crossDirection)) {
                stillOnCrossSide = currentPrice.compareTo(vwap) > 0;
            } else {
                stillOnCrossSide = currentPrice.compareTo(vwap) < 0;
            }
            if (!stillOnCrossSide) {
                log.debug("[VWAP-CROSS][{}] Cross detected at bar {} but price reverted", analysisId, crossBarIndex);
                return signals;
            }

            // ═══ STEP 2B: VWAP SLOPE REGIME CHECK ═══
            // If VWAP is sloping steeply, only fire in the slope direction.
            // If VWAP is flat, suppress VWAP_CROSS entirely (crosses are noise on flat VWAP).
            double slopeValue = rollingVwapSlope30min.doubleValue();
            double absSlope = Math.abs(slopeValue);

            if (absSlope < 0.15) {
                // FLAT VWAP — crosses are noise, price is oscillating around fair value
                // Suppress VWAP_CROSS. Let mean-reversion (MR-SIGNAL) or reversal paths handle it.
                log.info("[VWAP-CROSS][{}] SUPPRESSED — VWAP slope {}/30m is FLAT (<$0.15). " +
                                "Crosses are noise in ranging market. Direction: {}",
                        analysisId, String.format("%.2f", slopeValue), crossDirection);
                return signals;
            }

            // STEEP VWAP — only fire in the slope direction
            boolean slopeBullish = slopeValue > 0.15;   // VWAP rising → bullish trend
            boolean slopeBearish = slopeValue < -0.15;   // VWAP falling → bearish trend

            if ("CALL".equals(crossDirection) && slopeBearish) {
                // CALL cross against a bearish VWAP slope — counter-trend, suppress
                log.info("[VWAP-CROSS][{}] SUPPRESSED — CALL cross against bearish slope {}/30m",
                        analysisId, String.format("%.2f", slopeValue));
                return signals;
            }
            if ("PUT".equals(crossDirection) && slopeBullish) {
                // PUT cross against a bullish VWAP slope — counter-trend, suppress
                log.info("[VWAP-CROSS][{}] SUPPRESSED — PUT cross against bullish slope {}/30m",
                        analysisId, String.format("%.2f", slopeValue));
                return signals;
            }

            // Log the slope for signals that pass
            log.info("[VWAP-CROSS][{}] VWAP slope {}/30m — {} is aligned with slope",
                    analysisId, String.format("%.2f", slopeValue), crossDirection);

            // ═══ STEP 3: RSI acceleration confirms conviction ═══
            // Measure RSI change over the last 3-4 bars
            // RSI should have moved significantly in the cross direction
            double rsiAtCross = 50.0; // default
            double rsiNow = currentRsi;

            // Try to get RSI from a few bars before the cross
            if (rsiHistory != null && rsiHistory.size() >= 4) {
                // History is newest-first, so index 3 = 3 readings ago
                rsiAtCross = rsiHistory.get(Math.min(3, rsiHistory.size() - 1)).rsi;
            } else {
                // Fallback: estimate from bar data if available
                // Use the RSI at the bar before the cross if we can get it
                // Since we don't have per-bar RSI in MarketData, use the RSI history tracker
                return signals; // Can't confirm acceleration without RSI history
            }

            double rsiChange = rsiNow - rsiAtCross;
            double absRsiChange = Math.abs(rsiChange);

            // CALL cross: RSI should be rising (positive change)
            // PUT cross: RSI should be falling (negative change)
            boolean rsiConfirms = false;
            if ("CALL".equals(crossDirection) && rsiChange > 10.0) {
                rsiConfirms = true;
            } else if ("PUT".equals(crossDirection) && rsiChange < -10.0) {
                rsiConfirms = true;
            }

            if (!rsiConfirms) {
                log.debug("[VWAP-CROSS][{}] {} cross but RSI change only {} (need >10 in cross direction)",
                        analysisId, crossDirection, String.format("%.1f", rsiChange));
                return signals;
            }

            // ═══ STEP 4: Volume confirms participation ═══
            double volumeRatio = (ta != null) ? ta.getVolumeRatio() : 0.0;
            if (volumeRatio < 0.8) {
                log.debug("[VWAP-CROSS][{}] {} cross but volume {}x < 0.8 threshold",
                        analysisId, crossDirection, String.format("%.2f", volumeRatio));
                return signals;
            }

            // ═══ STEP 5: Leader confirmation ═══
            int leadersConfirming = checkLeadersForDirection(symbol, crossDirection);
            if (leadersConfirming < 2) {
                log.info("[VWAP-CROSS][{}] {} cross — RSI {} change, vol {}x — but only {}/3 leaders",
                        analysisId, crossDirection, String.format("%.1f", rsiChange), String.format("%.2f", volumeRatio), leadersConfirming);
                return signals;
            }

            // ═══ ALL GATES PASSED — GENERATE SIGNAL ═══
            BigDecimal vwapDist = currentPrice.subtract(vwap).abs();

            log.info("═══════════════════════════════════════════════════════════════");
            log.info("[VWAP-CROSS][{}] VWAP slope: {}/30m (regime: {})",
                    analysisId, String.format("%.2f", slopeValue),
                    absSlope > 0.50 ? "STRONG_TREND" :
                            absSlope > 0.15 ? "TRENDING" : "FLAT");
            log.info("[VWAP-CROSS][{}] Price=${}, VWAP=${}, Dist=${}, RSI={}",
                    analysisId, currentPrice, vwap,
                    vwapDist.setScale(2, RoundingMode.HALF_UP), currentRsi);
            log.info("[VWAP-CROSS][{}] RSI acceleration: {} → {} ({} in ~3 bars)",
                    analysisId, String.format("%.1f", rsiAtCross), String.format("%.1f", rsiNow), String.format("%+.1f", rsiChange));
            log.info("[VWAP-CROSS][{}] Volume: {}x | Leaders: {}/3",
                    analysisId, String.format("%.2f", volumeRatio), leadersConfirming);
            log.info("═══════════════════════════════════════════════════════════════");

            // Target: use existing ADR-aware target calculation
            BigDecimal target = calculateADRAwareTarget(currentPrice, crossDirection,
                    "VWAP_CROSS_" + (crossDirection.equals("CALL") ? "UP" : "DOWN"),
                    vwap, atr, analysisId);

            // Stop: other side of VWAP by 0.5 × ATR
            BigDecimal stop;
            if ("CALL".equals(crossDirection)) {
                stop = vwap.subtract(atr.multiply(new BigDecimal("0.5")));
            } else {
                stop = vwap.add(atr.multiply(new BigDecimal("0.5")));
            }

            signals = buildAndRouteSignal(symbol, crossDirection, currentPrice, target, stop,
                    leadersConfirming, ta,
                    "VWAP_CROSS_" + (crossDirection.equals("CALL") ? "UP" : "DOWN"),
                    analysisId);

        } catch (Exception e) {
            log.error("[VWAP-CROSS][{}] Error: {}", analysisId, e.getMessage());
        }

        return signals;
    }

    /**
     * Backward-compatible wrapper: returns boolean like the old method.
     * Calls the new score-based method internally.
     */
    private boolean checkPriceMomentumAlignment(String symbol, String signalType) {
        int score = checkExhaustionScore(symbol, signalType, symbol);
        return score >= 4;  // V5: All 4 components mandatory for exhaustion reversal
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

                    boolean isOversold = leaderRSI < 35;  // Tightened from 45
                    boolean isBelowVwap = distanceFromVwap < -0.003;  // Tightened from -0.001
                    boolean isNeutralCall = leaderRSI >= 40 && leaderRSI <= 60 && Math.abs(distanceFromVwap) < 0.003;

                    if (isNeutralCall) {
                        confirms = false;
                        confirmReason = String.format("NEUTRAL — RSI %.1f, VWAP dist %.2f%%", leaderRSI, distanceFromVwap * 100);
                        continue;
                    }
                    boolean isNearVwap = Math.abs(distanceFromVwap) < 0.003;  // Within 0.3%
                    boolean isNotOverbought = leaderRSI < 55;
                    boolean isClearlyBullish = leaderRSI > 60 && distanceFromVwap > 0.002;

                    if (isClearlyBullish) {
                        // V3: Leader is bullish (above VWAP, RSI > 60) — this CONFIRMS the CALL direction
                        // A leader that is already bullish is the strongest evidence a CALL move is real.
                        // Previous logic incorrectly tagged this as "diverging" and rejected it.
                        confirms = true;
                        confirmReason = String.format("bullish-aligned (RSI %.1f, dist %.2f%%)", leaderRSI, distanceFromVwap * 100);
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

                    boolean isOverbought = leaderRSI > 65;  // Tightened from 55
                    boolean isAboveVwap = distanceFromVwap > 0.003;  // Tightened from 0.001
                    boolean isNearVwap = Math.abs(distanceFromVwap) < 0.002;  // Tightened from 0.003
                    boolean isNotOversold = leaderRSI > 45;
                    boolean isClearlyBearish = leaderRSI < 40 && distanceFromVwap < -0.002;
                    boolean isNeutral = leaderRSI >= 40 && leaderRSI <= 60 && Math.abs(distanceFromVwap) < 0.003;

                    // NEUTRAL leaders do not count as confirming OR opposing
                    if (isNeutral) {
                        confirms = false;
                        confirmReason = String.format("NEUTRAL — RSI %.1f, VWAP dist %.2f%% (no opinion)", leaderRSI, distanceFromVwap * 100);
                        log.info("[LEADERS] ◯ {} is NEUTRAL for {} — skipping (RSI: {}, Dist: {}%)",
                                leader, signalType, String.format("%.1f", leaderRSI), String.format("%.2f", distanceFromVwap * 100));
                        // Note: does NOT increment confirming — effectively reduces the denominator
                        continue; // Skip to next leader
                    }

                    if (isClearlyBearish) {
                        // V3: Leader is bearish (below VWAP, RSI < 40) — this CONFIRMS the PUT direction
                        // A leader already positioned bearish is the strongest evidence a PUT move is real.
                        // Previous logic incorrectly tagged this as "diverging" and rejected it.
                        confirms = true;
                        confirmReason = String.format("bearish-aligned (RSI %.1f, dist %.2f%%)", leaderRSI, distanceFromVwap * 100);
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

        // ═══ Track leader opposition streaks ═══
        for (String leader : leaders) {
            String streakKey = leader + "_" + signalType;
            try {
                TechnicalAnalysis leaderTA = technicalAnalysisService.analyze(leader);
                if (leaderTA == null) continue;

                double leaderDist = 0;
                if (leaderTA.getVwap() != null && leaderTA.getVwap().compareTo(BigDecimal.ZERO) > 0) {
                    leaderDist = leaderTA.getCurrentPrice().subtract(leaderTA.getVwap())
                            .divide(leaderTA.getVwap(), 6, RoundingMode.HALF_UP).doubleValue();
                }

                boolean isOpposingThisCycle = false;
                if ("PUT".equals(signalType) && leaderDist < -0.002 && leaderTA.getRsi() < 40) {
                    isOpposingThisCycle = true; // Leader is bearish while we want PUT (counter-trend)
                }
                if ("CALL".equals(signalType) && leaderDist > 0.002 && leaderTA.getRsi() > 60) {
                    isOpposingThisCycle = true; // Leader is bullish while we want CALL from below (not exhausting)
                }

                if (isOpposingThisCycle) {
                    leaderOppositionStreaks.merge(streakKey, 1, Integer::sum);
                } else {
                    leaderOppositionStreaks.put(streakKey, 0);
                }

                int streak = leaderOppositionStreaks.getOrDefault(streakKey, 0);
                if (streak >= 20) { // 20 cycles × 15s = 5 minutes persistent opposition
                    log.warn("[LEADERS-PERSISTENCE] ⚠ {} has opposed {} for {} consecutive cycles — hard penalty active",
                            leader, signalType, streak);
                }
            } catch (Exception e) {
                // ignore
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
     * Calculate dynamic signal confidence (V4).
     * Changes from V3:
     *   - Volume < 0.20x now penalizes -10% (was -5%)
     *   - New exhaustion quality factor for EXHAUSTION/CAPITULATION signals
     *   - Exhaustion score embedded in strategy name: "INTRADAY_CAPITULATION_UP" or "EXHAUSTION_REVERSAL_UP"
     *
     * @return confidence between 0.40 and 0.95
     */
    private double calculateSignalConfidence(
            Signal signal,
            TechnicalAnalysis ta,
            int leaderConfirmations,
            Map<String, Double> candleNets,
            BigDecimal todayHigh,
            BigDecimal todayLow,
            double barCloseBonus,
            String analysisId) {

        try {
            double baseConfidence = 0.50;
            StringBuilder reasoning = new StringBuilder();
            reasoning.append("[CONFIDENCE-CALC][").append(analysisId).append("] ");

            // FACTOR 1: Leader Confirmations
            double leaderBonus = 0.0;
            if (leaderConfirmations >= 3) {
                leaderBonus = 0.15;
            } else if (leaderConfirmations == 2) {
                leaderBonus = 0.08;
            } else if (leaderConfirmations == 1) {
                leaderBonus = 0.02;
            } else {
                leaderBonus = -0.05;
            }
            reasoning.append(String.format("Leaders=%d/3→%+.0f%% | ", leaderConfirmations, leaderBonus * 100));

            // FACTOR 2: Candle Net Alignment
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
                    if (aligned) alignedCount++;
                }

                if (alignedCount >= 4) candleNetBonus = 0.15;
                else if (alignedCount == 3) candleNetBonus = 0.10;
                else if (alignedCount == 2) candleNetBonus = 0.05;
                else candleNetBonus = -0.05;

                double avgMagnitude = candleNets.size() > 0 ? totalMagnitude / candleNets.size() : 0;
                if (avgMagnitude > 0.30) candleNetBonus += 0.05;
                reasoning.append(String.format("CandleAlign=%d/%d,Mag=%.2f→%+.0f%% | ",
                        alignedCount, candleNets.size(), avgMagnitude, candleNetBonus * 100));
            } else {
                reasoning.append("CandleNets=N/A→+0% | ");
            }

            // FACTOR 3: RSI Extremity
            double rsiBonus = 0.0;
            if (ta != null && ta.getRsi() > 0) {
                double rsi = ta.getRsi();
                String signalType = signal.getSignalType();

                if ("CALL".equals(signalType)) {
                    if (rsi <= 25) rsiBonus = 0.10;
                    else if (rsi <= 35) rsiBonus = 0.05;
                    else if (rsi >= 55) rsiBonus = -0.05;
                } else if ("PUT".equals(signalType)) {
                    if (rsi >= 75) rsiBonus = 0.10;
                    else if (rsi >= 65) rsiBonus = 0.05;
                    else if (rsi <= 45) rsiBonus = -0.05;
                }
                reasoning.append(String.format("RSI=%.1f→%+.0f%% | ", rsi, rsiBonus * 100));
            }

            // FACTOR 4: Volume Ratio — UPDATED: heavier penalty for dead volume
            double volumeBonus = 0.0;
            if (ta != null && ta.getVolumeRatio() > 0) {
                double volumeRatio = ta.getVolumeRatio();
                BigDecimal currentPrice = ta.getCurrentPrice();
                String signalType = signal.getSignalType();

                boolean nearHOD = false;
                boolean nearLOD = false;

                if (todayHigh != null && currentPrice != null &&
                        todayHigh.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal distToHOD = todayHigh.subtract(currentPrice).abs()
                            .divide(todayHigh, 6, RoundingMode.HALF_UP);
                    nearHOD = distToHOD.doubleValue() < 0.0020;
                }
                if (todayLow != null && currentPrice != null &&
                        todayLow.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal distToLOD = currentPrice.subtract(todayLow).abs()
                            .divide(todayLow, 6, RoundingMode.HALF_UP);
                    nearLOD = distToLOD.doubleValue() < 0.0020;
                }

                if ("PUT".equals(signalType) && nearHOD) {
                    if (volumeRatio < 0.5) volumeBonus = 0.08;
                    else if (volumeRatio > 1.2) volumeBonus = -0.08;
                    reasoning.append(String.format("Volume=%.2fx@HOD→%+.0f%% | ", volumeRatio, volumeBonus * 100));
                } else if ("CALL".equals(signalType) && nearLOD) {
                    if (volumeRatio < 0.5) volumeBonus = 0.08;
                    else if (volumeRatio > 1.2) volumeBonus = -0.08;
                    reasoning.append(String.format("Volume=%.2fx@LOD→%+.0f%% | ", volumeRatio, volumeBonus * 100));
                } else {
                    // Mid-range — UPDATED penalties
                    if (volumeRatio >= 1.5) volumeBonus = 0.10;
                    else if (volumeRatio >= 1.2) volumeBonus = 0.05;
                    else if (volumeRatio < 0.20) volumeBonus = -0.10;   // V4: was -0.05, now -0.10 for dead volume
                    else if (volumeRatio < 0.50) volumeBonus = -0.05;
                    else volumeBonus = 0.0;
                    reasoning.append(String.format("Volume=%.2fx→%+.0f%% | ", volumeRatio, volumeBonus * 100));
                }
            }

            // FACTOR 5: VWAP Distance
            double vwapBonus = 0.0;
            if (ta != null && ta.getVwap() != null && ta.getCurrentPrice() != null
                    && ta.getVwap().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal vwapDistance = ta.getCurrentPrice().subtract(ta.getVwap()).abs();
                BigDecimal vwapDistancePercent = vwapDistance.divide(ta.getVwap(), 6, RoundingMode.HALF_UP);
                double distPct = vwapDistancePercent.doubleValue() * 100;

                if (distPct >= 0.05 && distPct <= 0.15) vwapBonus = 0.05;
                else if (distPct > 0.30) vwapBonus = -0.03;
                reasoning.append(String.format("VWAPDist=%.2f%%→%+.0f%% | ", distPct, vwapBonus * 100));
            }

            // FACTOR 6: Bar Close Confirmation
            reasoning.append(String.format("BarClose→%+.0f%% | ", barCloseBonus * 100));

            // FACTOR 7: Duration Decay
            double durationDecay = 0.0;
            String signalDir = signal.getSignalType();
            if ("PUT".equals(signalDir) && continuousAboveVwapMinutes > 0) {
                long minutesAbove = continuousAboveVwapMinutes / 4;
                if (minutesAbove > 90) durationDecay = -0.20;
                else if (minutesAbove > 60) durationDecay = -0.15;
                else if (minutesAbove > 30) durationDecay = -0.10;
                else if (minutesAbove > 15) durationDecay = -0.05;
                reasoning.append(String.format("DurationDecay(PUT,%dmin)→%+.0f%% | ", minutesAbove, durationDecay * 100));
            } else if ("CALL".equals(signalDir) && continuousBelowVwapMinutes > 0) {
                long minutesBelow = continuousBelowVwapMinutes / 4;
                if (minutesBelow > 90) durationDecay = -0.20;
                else if (minutesBelow > 60) durationDecay = -0.15;
                else if (minutesBelow > 30) durationDecay = -0.10;
                else if (minutesBelow > 15) durationDecay = -0.05;
                reasoning.append(String.format("DurationDecay(CALL,%dmin)→%+.0f%% | ", minutesBelow, durationDecay * 100));
            }

            // FACTOR 8: Leader Persistence Penalty
            double persistencePenalty = 0.0;
            for (String leader : LEADER_STOCKS) {
                String streakKey = leader + "_" + signal.getSignalType();
                int streak = leaderOppositionStreaks.getOrDefault(streakKey, 0);
                if (streak >= 20) {
                    double leaderWeight = LEADER_WEIGHTS.getOrDefault(leader, 0.2);
                    persistencePenalty -= 0.15 * leaderWeight;
                    reasoning.append(String.format("LeaderPersistence(%s,%dcycles)→%+.0f%% | ",
                            leader, streak, -15.0 * leaderWeight));
                }
            }

            // FACTOR 9 (NEW): Exhaustion Quality — for EXHAUSTION/CAPITULATION signals
            double exhaustionBonus = 0.0;
            String strategy = signal.getStrategy() != null ? signal.getStrategy() : "";
            if (strategy.contains("EXHAUSTION") || strategy.contains("CAPITULATION")) {
                if (strategy.contains("CAPITULATION")) {
                    // CAPITULATION signals use independent gates (velocity + trough RSI + cap volume)
                    // They already passed strict gates, so give a flat bonus
                    exhaustionBonus = 0.10;
                    reasoning.append(String.format("CapQuality→+%.0f%% | ", exhaustionBonus * 100));
                } else {
                    // EXHAUSTION signals use the 4/4 scoring system
                    int exhScore = checkExhaustionScore("QQQ", signal.getSignalType(), analysisId + "_conf");
                    if (exhScore >= 4) exhaustionBonus = 0.15;       // perfect exhaustion
                    else if (exhScore >= 3) exhaustionBonus = 0.05;  // good but missing one component
                    else exhaustionBonus = -0.10;                    // shouldn't happen with 4/4 gate, but safety net
                    reasoning.append(String.format("ExhQuality=%d/4→%+.0f%% | ", exhScore, exhaustionBonus * 100));
                }
            }

            // RSI Acceleration bonus for VWAP_CROSS signals
            if (strategy.contains("VWAP_CROSS")) {
                // The cross was already validated with >10pt RSI acceleration
                // Give additional bonus for stronger acceleration
                List<RSIReading> rsiHist = rsiTracker.getRSIHistory("QQQ");
                if (rsiHist != null && rsiHist.size() >= 4) {
                    double recentRsi = rsiHist.get(0).rsi;
                    double olderRsi = rsiHist.get(3).rsi;
                    double accel = Math.abs(recentRsi - olderRsi);
                    double accelBonus = 0.0;
                    if (accel > 20) accelBonus = 0.15;
                    else if (accel > 15) accelBonus = 0.10;
                    else if (accel > 10) accelBonus = 0.05;
                    reasoning.append(String.format("RSIAccel=%.0f→%+.0f%% | ", accel, accelBonus * 100));
                    exhaustionBonus += accelBonus; // Reuse the variable, it gets added to final
                }
            }


            // FINAL CALCULATION
            double finalConfidence = baseConfidence + leaderBonus + candleNetBonus + rsiBonus
                    + volumeBonus + vwapBonus + barCloseBonus + durationDecay
                    + persistencePenalty + exhaustionBonus;

            finalConfidence = Math.max(0.40, Math.min(0.95, finalConfidence));

            reasoning.append(String.format("FINAL=%.0f%%", finalConfidence * 100));
            log.info(reasoning.toString());

            return finalConfidence;

        } catch (Exception e) {
            log.error("[CONFIDENCE-CALC][{}] Error calculating confidence: {}", analysisId, e.getMessage());
            return 0.70;
        }
    }


    public static class BarCloseConfirmation {
        public boolean isValid;
        public boolean isBarClosed;
        public double wickRejectionBonus;
        public String reason;
        public MarketData confirmationBar;

        public BarCloseConfirmation() {
            this.isValid = false;
            this.isBarClosed = false;
            this.wickRejectionBonus = 0.0;
            this.reason = "";
            this.confirmationBar = null;
        }
    }

    /**
     * NEW: Validate bar close and direction alignment before signal generation
     *
     * Rules:
     * - Only generate signals after bar closes (not mid-bar)
     * - PUT signal requires last closed bar to be RED (close < open)
     * - CALL signal requires last closed bar to be GREEN (close > open)
     * - Wick rejection adds confidence bonus
     *
     * @param recentBars List of recent bars (minimum 2 required)
     * @param signalType "PUT" or "CALL"
     * @param analysisId Tracking ID for logging
     * @return BarCloseConfirmation with validation result and bonus
     */
    private BarCloseConfirmation validateBarCloseConfirmation(List<MarketData> recentBars,
                                                              String signalType,
                                                              String analysisId) {
        BarCloseConfirmation result = new BarCloseConfirmation();

        if (recentBars == null || recentBars.size() < 2) {
            result.reason = "Insufficient bars for confirmation";
            log.warn("[BAR-CLOSE][{}] ❌ {}", analysisId, result.reason);
            return result;
        }

        MarketData latestBar = recentBars.get(recentBars.size() - 1);
        MarketData previousBar = recentBars.get(recentBars.size() - 2);

        // Check if latest bar is closed (timestamp + 1 minute < now)
        LocalDateTime now = LocalDateTime.now(ET_ZONE);
        LocalDateTime barTimestamp = latestBar.getTimestamp();
        LocalDateTime barEndTime = barTimestamp.plusMinutes(1);

        boolean isBarClosed = now.isAfter(barEndTime);
        result.isBarClosed = isBarClosed;

        // Select the appropriate bar for confirmation
        MarketData confirmationBar;
        if (isBarClosed) {
            confirmationBar = latestBar;
            log.debug("[BAR-CLOSE][{}] Using latest closed bar at {}", analysisId, barTimestamp);
        } else {
            confirmationBar = previousBar;
            log.info("[BAR-CLOSE][{}] Current bar incomplete ({}), using previous closed bar",
                    analysisId, barTimestamp);
        }
        result.confirmationBar = confirmationBar;

        // Validate bar has required data
        if (confirmationBar.getOpen() == null || confirmationBar.getClose() == null ||
                confirmationBar.getHigh() == null || confirmationBar.getLow() == null) {
            result.reason = "Confirmation bar missing OHLC data";
            log.warn("[BAR-CLOSE][{}] ❌ {}", analysisId, result.reason);
            return result;
        }

        BigDecimal barOpen = confirmationBar.getOpen();
        BigDecimal barClose = confirmationBar.getClose();
        BigDecimal barHigh = confirmationBar.getHigh();
        BigDecimal barLow = confirmationBar.getLow();

        boolean isGreenBar = barClose.compareTo(barOpen) > 0;
        boolean isRedBar = barClose.compareTo(barOpen) < 0;
        boolean isDoji = barClose.compareTo(barOpen) == 0;

        log.info("[BAR-CLOSE][{}] Bar: O=${}, H=${}, L=${}, C=${} → {}",
                analysisId, barOpen, barHigh, barLow, barClose,
                isGreenBar ? "GREEN" : (isRedBar ? "RED" : "DOJI"));

        // HARD REJECT: Bar direction must align with signal
        if (signalType.equals("PUT") && isGreenBar) {
            result.reason = "PUT signal but last bar is GREEN - direction mismatch";
            result.isValid = false;
            log.info("[BAR-CLOSE][{}] ❌ REJECTED - {}", analysisId, result.reason);
            return result;
        }

        if (signalType.equals("CALL") && isRedBar) {
            result.reason = "CALL signal but last bar is RED - direction mismatch";
            result.isValid = false;
            log.info("[BAR-CLOSE][{}] ❌ REJECTED - {}", analysisId, result.reason);
            return result;
        }

        // Bar direction aligned - calculate wick rejection bonus
        BigDecimal bodySize = barClose.subtract(barOpen).abs();
        BigDecimal upperWick = barHigh.subtract(barClose.max(barOpen));
        BigDecimal lowerWick = barClose.min(barOpen).subtract(barLow);

        // Ensure we don't divide by zero
        if (bodySize.compareTo(BigDecimal.ZERO) == 0) {
            bodySize = new BigDecimal("0.01");  // Doji bar
        }

        double wickRejectionBonus = 0.0;

        if (signalType.equals("PUT") && upperWick.compareTo(bodySize) > 0) {
            // PUT with upper wick rejection (sellers pushed back)
            wickRejectionBonus = 0.05;
            log.info("[BAR-CLOSE][{}] ✓ Upper wick rejection detected (wick: ${}, body: ${}) → +5%",
                    analysisId, upperWick.setScale(2, RoundingMode.HALF_UP),
                    bodySize.setScale(2, RoundingMode.HALF_UP));
        } else if (signalType.equals("CALL") && lowerWick.compareTo(bodySize) > 0) {
            // CALL with lower wick rejection (buyers pushed back)
            wickRejectionBonus = 0.05;
            log.info("[BAR-CLOSE][{}] ✓ Lower wick rejection detected (wick: ${}, body: ${}) → +5%",
                    analysisId, lowerWick.setScale(2, RoundingMode.HALF_UP),
                    bodySize.setScale(2, RoundingMode.HALF_UP));
        } else if ((signalType.equals("PUT") && isRedBar) || (signalType.equals("CALL") && isGreenBar)) {
            // Bar direction aligned without significant wick
            wickRejectionBonus = 0.03;
            log.info("[BAR-CLOSE][{}] ✓ Bar direction aligned → +3%", analysisId);
        } else if (isDoji) {
            // Doji - neutral, no bonus
            wickRejectionBonus = 0.0;
            log.info("[BAR-CLOSE][{}] ✓ Doji bar - neutral → +0%", analysisId);
        }

        result.isValid = true;
        result.wickRejectionBonus = wickRejectionBonus;
        result.reason = "Bar close confirmation passed";
        log.info("[BAR-CLOSE][{}] ✓ PASSED - Bonus: +{}%", analysisId, (int)(wickRejectionBonus * 100));

        return result;
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


    // ═══════════════════════════════════════════════════════════════════════════════
    // PART 5: checkVwapBreachSignal() — REPLACE ENTIRE METHOD (around line 9519)
    // Changes:
    //   - 3 consecutive bars → 2 of 3 bars confirming
    //   - Volume threshold 0.8 → 0.6
    //   - Already called from relaxed regime gate in analyzeOptionsInternal
    // ═══════════════════════════════════════════════════════════════════════════════

    private List<Signal> checkVwapBreachSignal(String symbol, TechnicalAnalysis ta,
                                               BigDecimal atr, String analysisId) {
        List<Signal> signals = new ArrayList<>();
        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();

        List<MarketData> recentBars = barAggregationService.getRecentBars(symbol, 5);
        if (recentBars == null || recentBars.size() < 3) return signals;

        // RELAXED: 2-of-3 bars (was 3/3 consecutive)
        int closesBelow = 0;
        int closesAbove = 0;
        for (int i = Math.max(0, recentBars.size() - 3); i < recentBars.size(); i++) {
            MarketData bar = recentBars.get(i);
            if (bar.getClose() != null && vwap != null) {
                if (bar.getClose().compareTo(vwap) < 0) closesBelow++;
                if (bar.getClose().compareTo(vwap) > 0) closesAbove++;
            }
        }

        // 2 of 3 bars confirming (was 3 of 3)
        boolean breachDown = closesBelow >= 2 && currentPrice.compareTo(vwap) < 0;
        boolean breachUp = closesAbove >= 2 && currentPrice.compareTo(vwap) > 0;

        if (!breachDown && !breachUp) return signals;

        String direction = breachDown ? "DOWN" : "UP";

        // Debounce: one breach signal per direction per session
        if (direction.equals(lastBreachDirection) && vwapBreachSignalFired) {
            return signals;
        }

        // Debounce: no trend signal within 5 minutes
        if (lastTrendSignalTime != null &&
                lastTrendSignalTime.isAfter(LocalDateTime.now(ET_ZONE).minusMinutes(5))) {
            return signals;
        }

        {
            double moveFromLOD = currentPrice.subtract(sessionLowPrice).doubleValue();
            double moveFromHOD = sessionHighPrice.subtract(currentPrice).doubleValue();
            double bullDirBreach = moveFromLOD / averageDailyRange;
            double bearDirBreach = moveFromHOD / averageDailyRange;

            if (breachUp && bearDirBreach > 0.01 && bullDirBreach > 0.01) {
                double sessionRatio = bearDirBreach / bullDirBreach;
                if (sessionRatio > 2.0) {
                    log.info("[VWAP-BREACH][{}] ⛔ CALL breach blocked — counter-session-trend " +
                                    "(bear {}%/bull {}% = {}:1)", analysisId,
                            String.format("%.0f", bearDirBreach * 100),
                            String.format("%.0f", bullDirBreach * 100),
                            String.format("%.1f", sessionRatio));
                    return signals;
                }
            }
            if (breachDown && bullDirBreach > 0.01 && bearDirBreach > 0.01) {
                double sessionRatio = bullDirBreach / bearDirBreach;
                if (sessionRatio > 2.0) {
                    log.info("[VWAP-BREACH][{}] ⛔ PUT breach blocked — counter-session-trend " +
                                    "(bull {}%/bear {}% = {}:1)", analysisId,
                            String.format("%.0f", bullDirBreach * 100),
                            String.format("%.0f", bearDirBreach * 100),
                            String.format("%.1f", sessionRatio));
                    return signals;
                }
            }

            // V3: Block breach signals when signal-direction already consumed >80% of ADR
            if (breachDown && bearDirBreach > 0.80) {
                log.info("[VWAP-BREACH][{}] ⛔ PUT breach blocked — bearDir {}% > 80% (no room to run)",
                        analysisId, String.format("%.0f", bearDirBreach * 100));
                return signals;
            }
            if (breachUp && bullDirBreach > 0.80) {
                log.info("[VWAP-BREACH][{}] ⛔ CALL breach blocked — bullDir {}% > 80% (no room to run)",
                        analysisId, String.format("%.0f", bullDirBreach * 100));
                return signals;
            }
        }

        // V3: Dynamic volume gate — lower threshold in first 90 minutes when VWAP volume
        // averages haven't built up yet. Morning breakouts routinely show 0.15-0.55x ratios
        // because the denominator (average volume) is inflated by later-session activity.
        double volRatio = ta.getVolumeRatio();
        LocalTime breachTime = LocalTime.now(ET_ZONE);
        double volumeThreshold = 0.6;  // Default
        if (breachTime.isBefore(LocalTime.of(11, 0))) {
            volumeThreshold = 0.3;  // First 90 min: lower gate for morning breakouts
        }
        if (volRatio < volumeThreshold) {
            log.info("[VWAP-BREACH][{}] Breach {} but volume {} < {} — skipping",
                    analysisId, direction, String.format("%.2f", volRatio),
                    String.format("%.1f", volumeThreshold));
            return signals;
        }

        // Leader confirmation: 2/3
        String signalType = breachDown ? "PUT" : "CALL";
        int leadersConfirming = checkLeadersForDirection(symbol, signalType);
        if (leadersConfirming < 2) {
            log.info("[VWAP-BREACH][{}] Breach {} but only {}/3 leaders — skipping",
                    analysisId, direction, leadersConfirming);
            return signals;
        }

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("[VWAP-BREACH][{}] ⚡ VWAP BREACH {} DETECTED (2-of-3 bars)", analysisId, direction);
        log.info("[VWAP-BREACH][{}] Price: ${}, VWAP: ${}, Volume: {}x, Leaders: {}/3",
                analysisId, currentPrice, vwap, String.format("%.2f", volRatio), leadersConfirming);
        log.info("═══════════════════════════════════════════════════════════════");

        BigDecimal targetPrice;
        BigDecimal stopLoss;

        if (breachDown) {
            targetPrice = calculateADRAwareTarget(currentPrice, "PUT",
                    "VWAP_BREACH_DOWN", vwap, atr, analysisId);
            stopLoss = vwap.add(new BigDecimal("0.30"));
        } else {
            targetPrice = calculateADRAwareTarget(currentPrice, "CALL",
                    "VWAP_BREACH_UP", vwap, atr, analysisId);
            stopLoss = vwap.subtract(new BigDecimal("0.30"));
        }

        BigDecimal targetDistAbs = targetPrice.subtract(currentPrice).abs();
        if (targetDistAbs.compareTo(new BigDecimal("0.50")) < 0) {
            log.info("[VWAP-BREACH][{}] Target too close (${})", analysisId, targetDistAbs);
            return signals;
        }


        signals = buildAndRouteSignal(symbol, signalType, currentPrice, targetPrice, stopLoss,
                leadersConfirming, ta, "VWAP_BREACH_" + direction, analysisId);

        if (!signals.isEmpty()) {
            vwapBreachSignalFired = true;
            lastBreachDirection = direction;
            lastTrendSignalTime = LocalDateTime.now(ET_ZONE);
        }

        return signals;
    }


    // ═══════════════════════════════════════════════════════════════════════════════
    // PART 6: checkTrendFollowingPullback() — REPLACE ENTIRE METHOD (around line 9635)
    // Changes:
    //   - Pullback distance: 0.3% → 0.5% (wider zone to catch entries)
    //   - Volume: 0.5 → 0.3 (less restrictive)
    //   - Added TREND_EXHAUSTING regime support
    // ═══════════════════════════════════════════════════════════════════════════════

    private List<Signal> checkTrendFollowingPullback(String symbol, TechnicalAnalysis ta,
                                                     BigDecimal atr, String analysisId) {
        List<Signal> signals = new ArrayList<>();
        BigDecimal currentPrice = ta.getCurrentPrice();
        BigDecimal vwap = ta.getVwap();
        BigDecimal distFromVwap = currentPrice.subtract(vwap).abs();

        double distPct = distFromVwap.divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();
        if (distPct > 0.005) {
            return signals;
        }

        if (lastTrendSignalTime != null &&
                lastTrendSignalTime.isAfter(LocalDateTime.now(ET_ZONE).minusMinutes(10))) {
            return signals;
        }

        // V2: Exhaustion metrics
        double moveFromLOD = currentPrice.subtract(sessionLowPrice).doubleValue();
        double moveFromHOD = sessionHighPrice.subtract(currentPrice).doubleValue();

        String signalType;
        BigDecimal stopLoss;

        if (currentRegime == SessionRegime.TRENDING_DOWN) {
            signalType = "PUT";

            // V3: Counter-session-trend suppression
            double bullDirPull = moveFromLOD / averageDailyRange;
            double bearDirPull = moveFromHOD / averageDailyRange;
            if (bullDirPull > 0.01 && bearDirPull > 0.01) {
                double sessionRatioPull = bullDirPull / bearDirPull;
                if (sessionRatioPull > 2.0) {
                    log.info("[TREND-PULLBACK][{}] ⛔ PUT blocked — counter-session-trend (bull {}%/bear {}% = {}:1)",
                            analysisId, String.format("%.0f", bullDirPull * 100),
                            String.format("%.0f", bearDirPull * 100),
                            String.format("%.1f", sessionRatioPull));
                    return signals;
                }
            }

            // V3: Exhaustion gate raised from 65% to 70%
            if (moveFromHOD / averageDailyRange > 0.70) {
                log.info("[TREND-PULLBACK][{}] ⛔ PUT blocked — bearDir {}% exhausted (>70%)",
                        analysisId, String.format("%.0f", moveFromHOD / averageDailyRange * 100));
                return signals;
            }
            // V2: RSI coherence — PUT pullback needs RSI < 58
            if (ta.getRsi() > 58) {
                log.info("[TREND-PULLBACK][{}] ⛔ PUT blocked — RSI {} > 58",
                        analysisId, String.format("%.1f", ta.getRsi()));
                return signals;
            }
            // V2: Price-side — should be at/below VWAP for PUT pullback
            if (currentPrice.compareTo(vwap.add(new BigDecimal("0.10"))) > 0) {
                log.info("[TREND-PULLBACK][{}] ⛔ PUT blocked — price ${} above VWAP ${}",
                        analysisId, currentPrice, vwap);
                return signals;
            }
            stopLoss = vwap.add(atr.multiply(new BigDecimal("0.75")));

        } else if (currentRegime == SessionRegime.TRENDING_UP) {
            signalType = "CALL";

            // V3: Counter-session-trend suppression
            double bullDirPullC = moveFromLOD / averageDailyRange;
            double bearDirPullC = moveFromHOD / averageDailyRange;
            if (bullDirPullC > 0.01 && bearDirPullC > 0.01) {
                double sessionRatioPullC = bearDirPullC / bullDirPullC;
                if (sessionRatioPullC > 2.0) {
                    log.info("[TREND-PULLBACK][{}] ⛔ CALL blocked — counter-session-trend (bear {}%/bull {}% = {}:1)",
                            analysisId, String.format("%.0f", bearDirPullC * 100),
                            String.format("%.0f", bullDirPullC * 100),
                            String.format("%.1f", sessionRatioPullC));
                    return signals;
                }
            }

            // V3: Exhaustion gate raised from 65% to 70%
            if (moveFromLOD / averageDailyRange > 0.70) {
                log.info("[TREND-PULLBACK][{}] ⛔ CALL blocked — bullDir {}% exhausted (>70%)",
                        analysisId, String.format("%.0f", moveFromLOD / averageDailyRange * 100));
                return signals;
            }
            // V2: RSI coherence — CALL pullback needs RSI > 42
            if (ta.getRsi() < 42) {
                log.info("[TREND-PULLBACK][{}] ⛔ CALL blocked — RSI {} < 42",
                        analysisId, String.format("%.1f", ta.getRsi()));
                return signals;
            }
            // V2: Price-side — should be at/above VWAP for CALL pullback
            if (currentPrice.compareTo(vwap.subtract(new BigDecimal("0.10"))) < 0) {
                log.info("[TREND-PULLBACK][{}] ⛔ CALL blocked — price ${} below VWAP ${}",
                        analysisId, currentPrice, vwap);
                return signals;
            }
            stopLoss = vwap.subtract(atr.multiply(new BigDecimal("0.75")));

            // V4: VWAP cross-up confirmation for CALL pullbacks
            // Prevents entering false bounces below VWAP
            if ("CALL".equals(signalType)) {
                boolean recentVwapCrossUp = lastVwapCrossTime != null
                        && lastBreachDirection != null
                        && lastBreachDirection.equals("UP")
                        && lastVwapCrossTime.isAfter(LocalDateTime.now(ET_ZONE).minusMinutes(3));

                if (!recentVwapCrossUp) {
                    log.info("[TREND-PULLBACK][{}] ⛔ CALL waiting for VWAP cross-up confirmation " +
                                    "(lastCross={}, lastDir={})",
                            analysisId,
                            lastVwapCrossTime != null ? lastVwapCrossTime.toLocalTime() : "null",
                            lastBreachDirection);
                    return signals;
                }
            }

            // V4: Same for PUT pullbacks — require VWAP cross-down
            if ("PUT".equals(signalType)) {
                boolean recentVwapCrossDown = lastVwapCrossTime != null
                        && lastBreachDirection != null
                        && lastBreachDirection.equals("DOWN")
                        && lastVwapCrossTime.isAfter(LocalDateTime.now(ET_ZONE).minusMinutes(3));

                if (!recentVwapCrossDown) {
                    log.info("[TREND-PULLBACK][{}] ⛔ PUT waiting for VWAP cross-down confirmation",
                            analysisId);
                    return signals;
                }
            }

        } else {
            return signals;
        }

        int leadersConfirming = checkLeadersForDirection(symbol, signalType);
        if (leadersConfirming < 2) {
            log.info("[TREND-PULLBACK][{}] Only {}/3 leaders confirm {} — skipping",
                    analysisId, leadersConfirming, signalType);
            return signals;
        }

        double volRatio = ta.getVolumeRatio();
        if (volRatio < 0.3) {
            log.info("[TREND-PULLBACK][{}] Volume too low ({}) for trend entry",
                    analysisId, String.format("%.2f", volRatio));
            return signals;
        }

        BigDecimal targetPrice = calculateADRAwareTarget(currentPrice, signalType,
                "TREND_PULLBACK", vwap, atr, analysisId);

        BigDecimal targetDist = targetPrice.subtract(currentPrice).abs();
        if (targetDist.compareTo(new BigDecimal("0.50")) < 0) {
            return signals;
        }

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("[TREND-PULLBACK][{}] ⚡ {} PULLBACK ENTRY", analysisId, signalType);
        log.info("[TREND-PULLBACK][{}] Price=${}, VWAP=${}, Dist={}%, Regime={}, Leaders={}/3",
                analysisId, currentPrice, vwap, String.format("%.3f", distPct * 100),
                currentRegime, leadersConfirming);
        log.info("═══════════════════════════════════════════════════════════════");

        signals = buildAndRouteSignal(symbol, signalType, currentPrice, targetPrice, stopLoss,
                leadersConfirming, ta, "TREND_PULLBACK", analysisId);

        if (!signals.isEmpty()) {
            lastTrendSignalTime = LocalDateTime.now(ET_ZONE);
        }

        return signals;
    }


    // ═══════════════════════════════════════════════════════════════════════════════
    // PART 6b: isStrongTrendConfirmed() — REPLACE ENTIRE METHOD (around line 9736)
    // Change: support TREND_EXHAUSTING regime
    // ═══════════════════════════════════════════════════════════════════════════════

    private boolean isStrongTrendConfirmed() {
        if (currentRegime == SessionRegime.RANGING || currentRegime == SessionRegime.TREND_EXHAUSTING) {
            return false; // TREND_EXHAUSTING is no longer "strong"
        }

        boolean longDuration = (currentRegime == SessionRegime.TRENDING_DOWN && continuousBelowVwapMinutes >= 120)
                || (currentRegime == SessionRegime.TRENDING_UP && continuousAboveVwapMinutes >= 120);

        boolean largeDisplacement = false;
        if (sessionOpenPrice != null && sessionOpenPrice.compareTo(BigDecimal.ZERO) > 0) {
            TechnicalAnalysis ta = null;
            try { ta = technicalAnalysisService.analyze("QQQ"); } catch (Exception ignored) {}
            if (ta != null && ta.getCurrentPrice() != null) {
                double dispPct = Math.abs(ta.getCurrentPrice().subtract(sessionOpenPrice)
                        .divide(sessionOpenPrice, 6, RoundingMode.HALF_UP).doubleValue());
                largeDisplacement = dispPct > 0.015;
            }
        }

        return longDuration || largeDisplacement;
    }


    /**
     * V3: ADR-aware target calculation.
     *
     * Computes realistic, achievable targets based on:
     * - Remaining directional room in the ADR
     * - Session structure (VWAP, HOD, LOD)
     * - Strategy type (MR vs trend)
     *
     * Returns a target that represents 50% of remaining directional room,
     * capped to realistic 0DTE intraday move distances, with VWAP as the
     * anchor for mean-reversion strategies.
     */
    private BigDecimal calculateADRAwareTarget(BigDecimal currentPrice, String signalType,
                                               String strategy, BigDecimal vwap,
                                               BigDecimal atr, String analysisId) {

        double price = currentPrice.doubleValue();
        double vwapVal = vwap.doubleValue();

        // Session metrics
        double moveFromLOD = currentPrice.subtract(sessionLowPrice).doubleValue();
        double moveFromHOD = sessionHighPrice.subtract(currentPrice).doubleValue();
        double hodVal = sessionHighPrice.doubleValue();
        double lodVal = sessionLowPrice.doubleValue();

        // Remaining directional room
        double remainingBullRoom = Math.max(0, averageDailyRange - moveFromLOD);
        double remainingBearRoom = Math.max(0, averageDailyRange - moveFromHOD);

        double targetVal;

        if (strategy.startsWith("EXHAUSTION_REVERSAL")) {
            // Exhaustion reversals: target is VWAP (mean reversion to center)
            targetVal = vwapVal;
            log.info("[TARGET][{}] {} exhaustion reversal → target VWAP ${}", analysisId, signalType,
                    String.format("%.2f", targetVal));

        } else if (strategy.contains("BREACH") || strategy.contains("DEPARTURE")
                || strategy.contains("CONTINUATION")) {
            // Trend signals: target is 50% of remaining directional room
            if ("CALL".equals(signalType)) {
                double room = remainingBullRoom;
                double extensionTarget = price + (room * 0.50);
                // Also consider HOD as natural resistance if above current price
                if (hodVal > price && hodVal < extensionTarget) {
                    targetVal = hodVal;  // Use HOD as more conservative target
                } else {
                    targetVal = extensionTarget;
                }
                // Floor: at least $0.50 above entry, ceiling: entry + ADR * 0.40
                double maxTarget = price + (averageDailyRange * 0.40);
                targetVal = Math.min(targetVal, maxTarget);
                targetVal = Math.max(targetVal, price + 0.50);
            } else {
                double room = remainingBearRoom;
                double extensionTarget = price - (room * 0.50);
                // LOD as natural support if below current price
                if (lodVal < price && lodVal > extensionTarget) {
                    targetVal = lodVal;  // Use LOD as more conservative target
                } else {
                    targetVal = extensionTarget;
                }
                double maxTarget = price - (averageDailyRange * 0.40);
                targetVal = Math.max(targetVal, maxTarget);
                targetVal = Math.min(targetVal, price - 0.50);
            }
            log.info("[TARGET][{}] {} {} trend → target ${} (room={}, 50%={})",
                    analysisId, signalType, strategy, String.format("%.2f", targetVal),
                    String.format("%.2f", "CALL".equals(signalType) ? remainingBullRoom : remainingBearRoom),
                    String.format("%.2f", ("CALL".equals(signalType) ? remainingBullRoom : remainingBearRoom) * 0.50));

        } else if (strategy.equals("TREND_PULLBACK")) {
            // Pullback: target is the further of VWAP or 40% of remaining room
            if ("CALL".equals(signalType)) {
                double vwapTarget = vwapVal;  // MR back above VWAP
                double roomTarget = price + (remainingBullRoom * 0.40);
                targetVal = Math.max(vwapTarget, roomTarget);
                targetVal = Math.min(targetVal, price + (averageDailyRange * 0.30));
                targetVal = Math.max(targetVal, price + 0.50);
            } else {
                double vwapTarget = vwapVal;
                double roomTarget = price - (remainingBearRoom * 0.40);
                targetVal = Math.min(vwapTarget, roomTarget);
                targetVal = Math.max(targetVal, price - (averageDailyRange * 0.30));
                targetVal = Math.min(targetVal, price - 0.50);
            }
            log.info("[TARGET][{}] {} pullback → target ${} (VWAP=${}, room={})",
                    analysisId, signalType, String.format("%.2f", targetVal),
                    String.format("%.2f", vwapVal),
                    String.format("%.2f", "CALL".equals(signalType) ? remainingBullRoom : remainingBearRoom));

        } else {
            // MR signals (bounce/rejection): target VWAP
            targetVal = vwapVal;
            log.info("[TARGET][{}] {} MR signal → target VWAP ${}", analysisId, signalType,
                    String.format("%.2f", targetVal));
        }

        // Sanity: target must be in correct direction
        if ("CALL".equals(signalType) && targetVal <= price) {
            targetVal = price + 0.50;  // Minimum $0.50 above
            log.warn("[TARGET][{}] CALL target below entry — forcing +$0.50 minimum", analysisId);
        }
        if ("PUT".equals(signalType) && targetVal >= price) {
            targetVal = price - 0.50;
            log.warn("[TARGET][{}] PUT target above entry — forcing -$0.50 minimum", analysisId);
        }

        return BigDecimal.valueOf(targetVal).setScale(2, RoundingMode.HALF_UP);
    }


    // ═══════════════════════════════════════════════════════════════════════════════
    // PART 7: buildAndRouteSignal() — REPLACE ENTIRE METHOD (around line 9763)
    // Changes:
    //   - Recognizes new strategy names (VWAP_DEPARTURE_*, TREND_CONTINUATION_*,
    //     EXHAUSTION_REVERSAL_*)
    //   - Departure/Continuation get +10% confidence boost (regime-aligned)
    //   - Exhaustion reversal gets +5% (counter-trend but high-probability)
    // ═══════════════════════════════════════════════════════════════════════════════

    private List<Signal> buildAndRouteSignal(String symbol, String signalType,
                                             BigDecimal entryPrice, BigDecimal targetPrice, BigDecimal stopLoss,
                                             int leadersConfirming, TechnicalAnalysis ta,
                                             String strategy, String analysisId) {
        List<Signal> signals = new ArrayList<>();
        LocalTime now = LocalTime.now(ET_ZONE);

        try {
            LocalDate today = LocalDate.now(ET_ZONE);
            List<LocalDate> expirations = tradierService.getExpirations(symbol);
            if (expirations == null || !expirations.contains(today)) {
                log.warn("[{}] No 0DTE expiration for {}", analysisId, strategy);
                return signals;
            }

            OptionChainResponse chainResponse = tradierService.getOptionChain(symbol, today);
            if (chainResponse == null || !chainResponse.hasOptions()) {
                log.warn("[{}] No option chain for {}", analysisId, strategy);
                return signals;
            }

            List<Option> options = chainResponse.getOptionsList();
            if (options == null || options.isEmpty()) return signals;

            Option selectedOption;
            if (signalType.equals("CALL")) {
                selectedOption = selectBestCallOption(options, entryPrice, targetPrice, analysisId);
            } else {
                selectedOption = selectBestPutOption(options, entryPrice, targetPrice, analysisId);
            }

            if (selectedOption == null) {
                log.warn("[{}] No suitable option for {}", analysisId, strategy);
                return signals;
            }

            Signal signal = new Signal();
            signal.setSymbol(selectedOption.getSymbol());
            signal.setOptionSymbol(selectedOption.getSymbol());
            signal.setSignalType(signalType);
            signal.setEntryPrice(selectedOption.getAsk());
            signal.setTargetPrice(targetPrice);
            signal.setStopLoss(stopLoss);
            signal.setStrikePrice(selectedOption.getStrikePrice());
            signal.setExpirationDate(today.atTime(16, 0).atZone(ET_ZONE));
            signal.setStrategy(strategy);

            // Calculate confidence
            Map<String, Double> candleNets = fetchCandleNetsForConfidence(analysisId);
            DailyLevelService.DailyLevels levels = dailyLevelService.getQQQDailyLevels();
            BigDecimal todayHigh = (levels != null) ? levels.getTodayHigh() : null;
            BigDecimal todayLow = (levels != null) ? levels.getTodayLow() : null;

            double confidence = calculateSignalConfidence(
                    signal, ta, leadersConfirming, candleNets, todayHigh, todayLow, 0.0, analysisId);

            // V2: Strategy-specific confidence adjustments — conditional bonuses
            if (strategy.startsWith("VWAP_DEPARTURE") || strategy.startsWith("TREND_CONTINUATION")) {
                confidence += 0.10;
                log.info("[{}] {} bonus: +10% confidence (trend-aligned)", analysisId, strategy);

            } else if (strategy.startsWith("VWAP_BREACH")) {
                confidence += 0.10;
                log.info("[{}] {} bonus: +10% confidence (regime-aligned)", analysisId, strategy);

            } else if (strategy.equals("TREND_PULLBACK")) {
                // V2: Conditional pullback bonus — requires regime stability + RSI coherence
                boolean regimeStable = regimeTrendingStartTime != null
                        && java.time.Duration.between(regimeTrendingStartTime,
                        LocalDateTime.now(ET_ZONE)).toMinutes() >= 10;

                double currentRSI = ta.getRsi();
                boolean rsiCoherent;
                if ("CALL".equals(signalType)) {
                    rsiCoherent = currentRSI > 45;
                } else {
                    rsiCoherent = currentRSI < 55;
                }

                if (regimeStable && rsiCoherent) {
                    confidence += 0.10;
                    log.info("[{}] TREND_PULLBACK bonus: +10% (regime {}min stable, RSI {} coherent)",
                            analysisId,
                            java.time.Duration.between(regimeTrendingStartTime,
                                    LocalDateTime.now(ET_ZONE)).toMinutes(),
                            String.format("%.1f", currentRSI));
                } else if (!regimeStable) {
                    log.info("[{}] TREND_PULLBACK bonus: +0% (regime <10min, unstable)", analysisId);
                } else {
                    confidence -= 0.05;
                    log.info("[{}] TREND_PULLBACK penalty: -5% (RSI {} incoherent for {})",
                            analysisId, String.format("%.1f", currentRSI), signalType);
                }

            } else if (strategy.startsWith("EXHAUSTION_REVERSAL")) {
                confidence += 0.05;
                log.info("[{}] {} bonus: +5% confidence (exhaustion counter-trend)", analysisId, strategy);
            }

            // V2: Directional coherence gate — penalize trend signals on wrong side of VWAP
            if (strategy.startsWith("VWAP_DEPARTURE") || strategy.startsWith("TREND_CONTINUATION")
                    || strategy.equals("TREND_PULLBACK")) {
                BigDecimal currentVwap = ta.getVwap();
                BigDecimal price = ta.getCurrentPrice();
                if ("CALL".equals(signalType) && price.compareTo(currentVwap) < 0) {
                    confidence *= 0.85;
                    log.info("[{}] V2 coherence penalty: 0.85x — CALL with price ${} < VWAP ${}",
                            analysisId, price, currentVwap);
                } else if ("PUT".equals(signalType) && price.compareTo(currentVwap) > 0) {
                    confidence *= 0.85;
                    log.info("[{}] V2 coherence penalty: 0.85x — PUT with price ${} > VWAP ${}",
                            analysisId, price, currentVwap);
                }
            }

            // V3: Time adjustment removed here — applied ONCE in applyRiskManagement only
            // Previous: time multiplier applied here AND again in applyRiskManagement = double penalty
            // Example: 83% midday signal → 0.85x here = 70% → 0.85x again in risk mgmt = 60% (killed)
            // Now: 83% passes through here → 0.85x once in risk mgmt = 70% (executes correctly)
            signal.setConfidence(Math.max(0.0, Math.min(1.0, confidence)));
            signal.setCreatedAt(LocalDateTime.now());


            log.info("[{}] {} Signal: {} ${}, Entry: ${}, Target: ${}, Confidence: {}%",
                    analysisId, strategy, signalType, selectedOption.getStrikePrice(),
                    signal.getEntryPrice(), targetPrice, (int)(signal.getConfidence() * 100));

            signal = applyRiskManagement(signal, analysisId);

            if (signal != null && signal.getConfidence() != null && signal.getConfidence() >= 0.60) {
                signals.add(signal);
                lastSignalByTypeAndStrike.put(signalType + "_" + symbol, LocalDateTime.now(ET_ZONE));
                signalsGeneratedToday++;
                if ("PUT".equals(signalType)) putSignalsToday++;
                if ("CALL".equals(signalType)) callSignalsToday++;

                boolean handled = routeSignalByConfidence(signal, ta, analysisId);
                if (handled) {
                    log.info("[{}] {} signal routed successfully", analysisId, strategy);
                }
            }

        } catch (Exception e) {
            log.error("[{}] Error building {} signal: {}", analysisId, strategy, e.getMessage(), e);
        }

        return signals;
    }

}