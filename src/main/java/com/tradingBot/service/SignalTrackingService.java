package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import com.tradingBot.entity.MarketData;
import com.tradingBot.model.Quote;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.SignalRepository;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalTrackingService {

    private final SignalRepository signalRepository;
    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;
    private final TelegramService telegramService;

    // Track signals awaiting confirmation
    private final Map<Long, TrackingState> trackingStates = new ConcurrentHashMap<>();

    @Data
    private static class TrackingState {
        private Signal signal;
        private LocalDateTime startTime;
        private BigDecimal entryPriceAtSignal;
        private BigDecimal optionPriceAtSignal;
        private BigDecimal highSinceSignal;
        private BigDecimal lowSinceSignal;
        private boolean hasVolumeSurge = false;
        private boolean hasPulledBack = false;
        private int confirmationCandles = 0;
        private String confirmationType; // "BREAKOUT", "PULLBACK", "VOLUME"
    }

    @Scheduled(fixedDelay = 15000)
    public void checkTrackedSignals() {
        // Use the simple method
        List<Signal> trackingSignals = signalRepository.findByStatus("TRACKING");

        // Filter out expired ones in code if needed
        trackingSignals = trackingSignals.stream()
                .filter(s -> s.getExpirationTime().isAfter(LocalDateTime.now()))
                .collect(Collectors.toList());

        if (trackingSignals.isEmpty()) {
            return;
        }

        log.info("[TRACKER] Monitoring {} signals for confirmation", trackingSignals.size());

        for (Signal signal : trackingSignals) {
            TrackingState state = trackingStates.computeIfAbsent(signal.getId(),
                    k -> initializeTrackingState(signal));

            checkSignalConfirmation(signal, state);
        }
    }

    private TrackingState initializeTrackingState(Signal signal) {
        TrackingState state = new TrackingState();
        state.setSignal(signal);
        state.setStartTime(LocalDateTime.now());
        state.setEntryPriceAtSignal(signal.getEntryAssumptionPrice());
        state.setOptionPriceAtSignal(signal.getEntryPrice());
        state.setHighSinceSignal(signal.getEntryAssumptionPrice());
        state.setLowSinceSignal(signal.getEntryAssumptionPrice());

        log.info("[TRACKER] Started tracking {} - Strategy: {}, Entry: ${}",
                signal.getOptionSymbol(), signal.getStrategy(), signal.getEntryPrice());

        return state;
    }

    private void checkSignalConfirmation(Signal signal, TrackingState state) {
        try {
            // Get current prices
            QuoteResponse underlyingQuote = tradierService.getQuote(signal.getSymbol());
            QuoteResponse optionQuote = tradierService.getQuote(signal.getOptionSymbol());

            if (underlyingQuote == null || optionQuote == null) {
                log.warn("[TRACKER] Failed to get quotes for {}", signal.getOptionSymbol());
                return;
            }

            BigDecimal currentUnderlyingPrice = underlyingQuote.getQuote().getLast();
            BigDecimal currentOptionPrice = optionQuote.getQuote().getLast();

            // Update high/low tracking
            if (currentUnderlyingPrice.compareTo(state.getHighSinceSignal()) > 0) {
                state.setHighSinceSignal(currentUnderlyingPrice);
            }
            if (currentUnderlyingPrice.compareTo(state.getLowSinceSignal()) < 0) {
                state.setLowSinceSignal(currentUnderlyingPrice);
            }

            // Check timeout (5 minutes max tracking)
            if (Duration.between(state.getStartTime(), LocalDateTime.now()).toMinutes() > 5) {
                log.info("[TRACKER] Signal {} timed out after 5 minutes", signal.getId());
                expireSignal(signal, state, "Confirmation timeout");
                return;
            }

            // Check if move went against us (stop would have hit)
            if (isStopHit(signal, currentOptionPrice)) {
                log.info("[TRACKER] Signal {} would have hit stop - cancelling", signal.getId());
                expireSignal(signal, state, "Stop level reached during tracking");
                return;
            }

            // Strategy-specific confirmation
            boolean confirmed = false;

            if (signal.getStrategy().contains("BREAKOUT")) {
                confirmed = checkBreakoutConfirmation(signal, state, currentUnderlyingPrice);
            } else if (signal.getStrategy().contains("REVERSION")) {
                confirmed = checkReversionConfirmation(signal, state, currentUnderlyingPrice);
            } else if (signal.getStrategy().contains("UNUSUAL_FLOW")) {
                confirmed = checkFlowConfirmation(signal, state, optionQuote.getQuote());
            } else {
                // Default confirmation - volume and price holding
                confirmed = checkDefaultConfirmation(signal, state, currentUnderlyingPrice);
            }

            if (confirmed) {
                confirmSignal(signal, state, currentOptionPrice);
            }

        } catch (Exception e) {
            log.error("[TRACKER] Error checking signal {}: {}", signal.getId(), e.getMessage());
        }
    }

    private boolean checkBreakoutConfirmation(Signal signal, TrackingState state,
                                              BigDecimal currentPrice) {
        boolean isCall = signal.getOptionSymbol().contains("C");

        // For breakouts, we want:
        // 1. Price to hold above/below breakout level for 2+ minutes
        // 2. Volume confirmation
        // 3. No immediate reversal

        List<MarketData> recentData = marketDataRepository
                .findRecentData(signal.getSymbol(), 3);

        if (recentData.size() < 3) return false;

        // Count how many candles held the breakout
        int holdingCandles = 0;
        BigDecimal breakoutLevel = state.getEntryPriceAtSignal();

        for (MarketData data : recentData) {
            if (isCall && data.getPrice().compareTo(breakoutLevel) > 0) {
                holdingCandles++;
            } else if (!isCall && data.getPrice().compareTo(breakoutLevel) < 0) {
                holdingCandles++;
            }
        }

        // Check volume surge
        boolean volumeSurge = checkVolumeSurge(signal.getSymbol());

        boolean confirmed = holdingCandles >= 2 && volumeSurge;

        if (confirmed) {
            state.setConfirmationType("BREAKOUT_HOLD");
            log.info("[TRACKER] {} BREAKOUT confirmed - {} candles held with volume",
                    signal.getOptionSymbol(), holdingCandles);
        }

        return confirmed;
    }

    private boolean checkReversionConfirmation(Signal signal, TrackingState state,
                                               BigDecimal currentPrice) {
        boolean isCall = signal.getOptionSymbol().contains("C");

        // For reversions, we want:
        // 1. Initial move in wrong direction (exhaustion)
        // 2. Then reversal with volume

        BigDecimal entryPrice = state.getEntryPriceAtSignal();

        if (isCall) {
            // For call reversion, price should go down first, then up
            if (!state.isHasPulledBack()) {
                // Check if we've had the pullback
                BigDecimal pullbackLevel = entryPrice.multiply(BigDecimal.valueOf(0.998));
                if (state.getLowSinceSignal().compareTo(pullbackLevel) <= 0) {
                    state.setHasPulledBack(true);
                    log.info("[TRACKER] Reversion pullback complete at ${}",
                            state.getLowSinceSignal());
                }
                return false;
            }

            // Now check for reversal
            if (currentPrice.compareTo(entryPrice) > 0 && checkVolumeSurge(signal.getSymbol())) {
                state.setConfirmationType("REVERSION_BOUNCE");
                return true;
            }
        } else {
            // For put reversion, opposite
            if (!state.isHasPulledBack()) {
                BigDecimal pullbackLevel = entryPrice.multiply(BigDecimal.valueOf(1.002));
                if (state.getHighSinceSignal().compareTo(pullbackLevel) >= 0) {
                    state.setHasPulledBack(true);
                    log.info("[TRACKER] Reversion pullback complete at ${}",
                            state.getHighSinceSignal());
                }
                return false;
            }

            if (currentPrice.compareTo(entryPrice) < 0 && checkVolumeSurge(signal.getSymbol())) {
                state.setConfirmationType("REVERSION_BOUNCE");
                return true;
            }
        }

        return false;
    }

    private boolean checkFlowConfirmation(Signal signal, TrackingState state, Quote optionQuote) {
        // For unusual flow, confirm with:
        // 1. Continued volume in same option
        // 2. Price movement in expected direction
        // 3. No immediate reversal

        // Check if volume continues
        Long currentVolume = optionQuote.getVolume();
        Long avgVolume = optionQuote.getAverageVolume();

        boolean continuedVolume = currentVolume != null && avgVolume != null &&
                currentVolume > avgVolume * 3;

        // Check price direction
        boolean priceConfirms = false;
        BigDecimal priceChange = optionQuote.getLast()
                .subtract(state.getOptionPriceAtSignal())
                .divide(state.getOptionPriceAtSignal(), 4, RoundingMode.HALF_UP);

        if (priceChange.compareTo(BigDecimal.valueOf(0.05)) > 0) {
            priceConfirms = true;
        }

        boolean confirmed = continuedVolume && priceConfirms;

        if (confirmed) {
            state.setConfirmationType("FLOW_CONTINUATION");
            log.info("[TRACKER] {} FLOW confirmed - Volume: {} ({}x avg), Price: +{}%",
                    signal.getOptionSymbol(), currentVolume,
                    currentVolume / avgVolume,
                    priceChange.multiply(BigDecimal.valueOf(100)));
        }

        return confirmed;
    }

    private boolean checkDefaultConfirmation(Signal signal, TrackingState state,
                                             BigDecimal currentPrice) {
        // Default: Wait for pullback and volume
        boolean isCall = signal.getOptionSymbol().contains("C");
        BigDecimal entryPrice = state.getEntryPriceAtSignal();

        // Step 1: Wait for minor pullback (shows resistance/support)
        if (!state.isHasPulledBack()) {
            BigDecimal pullbackTarget = isCall ?
                    entryPrice.multiply(BigDecimal.valueOf(0.999)) :
                    entryPrice.multiply(BigDecimal.valueOf(1.001));

            boolean pulledBack = isCall ?
                    state.getLowSinceSignal().compareTo(pullbackTarget) <= 0 :
                    state.getHighSinceSignal().compareTo(pullbackTarget) >= 0;

            if (pulledBack) {
                state.setHasPulledBack(true);
                log.info("[TRACKER] Pullback detected for {}", signal.getOptionSymbol());
            }
            return false;
        }

        // Step 2: Confirm bounce with volume
        boolean rightDirection = isCall ?
                currentPrice.compareTo(entryPrice) > 0 :
                currentPrice.compareTo(entryPrice) < 0;

        boolean hasVolume = checkVolumeSurge(signal.getSymbol());

        if (rightDirection && hasVolume) {
            state.setConfirmationType("PULLBACK_BOUNCE");
            return true;
        }

        return false;
    }

    private boolean checkVolumeSurge(String symbol) {
        List<MarketData> recentData = marketDataRepository.findRecentData(symbol, 3);
        if (recentData.size() < 2) return false;

        long currentVolume = recentData.get(recentData.size() - 1).getVolume();
        long avgVolume = recentData.stream()
                .mapToLong(d -> d.getVolume())
                .sum() / recentData.size();

        return currentVolume > avgVolume * 1.5;
    }

    private boolean isStopHit(Signal signal, BigDecimal currentPrice) {
        return signal.getStopLoss() != null &&
                currentPrice.compareTo(signal.getStopLoss()) <= 0;
    }

    private void confirmSignal(Signal signal, TrackingState state, BigDecimal currentOptionPrice) {
        // Update signal with confirmed entry price
        signal.setEntryPrice(currentOptionPrice);
        signal.setStatus("PENDING");
        signal.setReason(signal.getReason() + " [CONFIRMED: " + state.getConfirmationType() + "]");
        signalRepository.save(signal);

        // Remove from tracking
        trackingStates.remove(signal.getId());

        // Send alert
        telegramService.sendMessage(String.format(
                "✅ <b>SIGNAL CONFIRMED</b>\n" +
                        "Option: %s\n" +
                        "Strategy: %s\n" +
                        "Confirmation: %s\n" +
                        "Original Price: $%.2f → Current: $%.2f\n" +
                        "Ready for execution!",
                signal.getOptionSymbol(),
                signal.getStrategy(),
                state.getConfirmationType(),
                state.getOptionPriceAtSignal(),
                currentOptionPrice
        ));

        log.info("[TRACKER] ✅ Signal {} CONFIRMED and ready for execution", signal.getId());
    }

    private void expireSignal(Signal signal, TrackingState state, String reason) {
        signal.setStatus("EXPIRED");
        signal.setReason(signal.getReason() + " [TRACKING FAILED: " + reason + "]");
        signal.setExecuted(true);
        signalRepository.save(signal);

        trackingStates.remove(signal.getId());

        log.info("[TRACKER] Signal {} expired: {}", signal.getId(), reason);
    }

//    // In SignalTrackingService or TradingScheduler
//    @Scheduled(cron = "0 0 * * * *") // Every hour
//    public void cleanupExpiredSignals() {
//        List<Signal> expiredSignals = signalRepository
//                .findByStatusInAndExpirationTimeLessThanEqual(
//                        Arrays.asList("PENDING", "TRACKING"),
//                        LocalDateTime.now()
//                );
//
//        for (Signal signal : expiredSignals) {
//            signal.setStatus("EXPIRED");
//            signal.setExecuted(true);
//            signal.setExecutionNotes("Expired without execution");
//        }
//
//        if (!expiredSignals.isEmpty()) {
//            signalRepository.saveAll(expiredSignals);
//            log.info("Cleaned up {} expired signals", expiredSignals.size());
//        }
//    }
}