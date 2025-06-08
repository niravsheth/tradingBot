package com.tradingBot.service;

import com.tradingBot.entity.*;
import com.tradingBot.repository.*;
import com.tradingBot.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZeroDTEStrategy {
    private final TradierService tradierService;
    private final SignalRepository signalRepository;
    private final MarketDataRepository marketDataRepository;
    private final TechnicalAnalysisService technicalAnalysisService;

    @Value("${trading.min-volume}")
    private Integer minVolume;

    @Value("${trading.min-iv}")
    private Double minIV;

    @Value("${trading.max-spread-percentage}")
    private Double maxSpreadPercentage;

    public List<Signal> analyzeOptions(String symbol) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[v62][{}] === OPTION ANALYSIS START for {} ===", analysisId, symbol);

        List<Signal> signals = new ArrayList<>();

        // Step 1: Get current quote
        log.info("[v62][{}] Step 1: Getting current quote for {}", analysisId, symbol);
        QuoteResponse quoteResponse = tradierService.getQuote(symbol);
        if (quoteResponse == null || quoteResponse.getQuote() == null) {
            log.error("[v62][{}] Failed to get quote for {}", analysisId, symbol);
            return signals;
        }

        Quote quote = quoteResponse.getQuote();
        BigDecimal currentPrice = quote.getLast();
        log.info("[v62][{}] {} current price: ${}", analysisId, symbol, currentPrice);

        // Step 2: Get today's expiration options
        log.info("[v62][{}] Step 2: Getting 0DTE options", analysisId);
        LocalDate today = LocalDate.now();
        OptionChainResponse chain = tradierService.getOptionChain(symbol, today);

        if (chain == null || chain.getOptionsList() == null || chain.getOptionsList().isEmpty()) {
            log.warn("[v62][{}] No 0DTE options available for {} on {}", analysisId, symbol, today);
            return signals;
        }

        log.info("[v62][{}] Found {} 0DTE options to analyze", analysisId, chain.getOptionsList().size());

        // Step 3: Get technical analysis
        log.info("[v62][{}] Step 3: Running technical analysis", analysisId);
        TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol, currentPrice);
        logTechnicalAnalysis(analysisId, ta);

        // Step 4: Analyze each option
        log.info("[v62][{}] Step 4: Analyzing individual options", analysisId);
        int optionsAnalyzed = 0;
        int optionsFiltered = 0;

        for (Option option : chain.getOptionsList()) {
            optionsAnalyzed++;

            // Basic filtering
            if (option.getVolume() < minVolume) {
                log.trace("[v62][{}] Option {} filtered - Low volume: {}",
                        analysisId, option.getSymbol(), option.getVolume());
                optionsFiltered++;
                continue;
            }

            if (option.getImpliedVolatility() < minIV) {
                log.trace("[v62][{}] Option {} filtered - Low IV: {}",
                        analysisId, option.getSymbol(), option.getImpliedVolatility());
                optionsFiltered++;
                continue;
            }

            Signal signal = analyzeOption(option, currentPrice, ta, analysisId);
            if (signal != null) {
                signals.add(signal);
                signalRepository.save(signal);
                log.info("[v62][{}] ✓ Signal generated for {} - Strategy: {}, Confidence: {}%",
                        analysisId, signal.getOptionSymbol(), signal.getStrategy(),
                        (int)(signal.getConfidence() * 100));
            }
        }

        log.info("[v62][{}] Options analyzed: {}, Filtered: {}, Signals generated: {}",
                analysisId, optionsAnalyzed, optionsFiltered, signals.size());

        log.info("[v62][{}] === OPTION ANALYSIS COMPLETE ===", analysisId);

        return signals;
    }

    private void logTechnicalAnalysis(String analysisId, TechnicalAnalysis ta) {
        log.info("[v62][{}] Technical Analysis Results:", analysisId);
        log.info("[v62][{}]   VWAP: ${}", analysisId, ta.getVwap());
        log.info("[v62][{}]   Near VWAP: {}", analysisId, ta.isNearVWAP());
        log.info("[v62][{}]   VWAP Bounce: {}", analysisId, ta.isVWAPBounce());
        log.info("[v62][{}]   Bullish VWAP: {}", analysisId, ta.isBullishVWAPBounce());
        log.info("[v62][{}]   Bearish VWAP: {}", analysisId, ta.isBearishVWAPBounce());
        log.info("[v62][{}]   Trendline: {}", analysisId, ta.getTrendlineBreakdown());

        if (ta.getKeyLevelSignal() != null) {
            log.info("[v62][{}]   Key Level: {} at ${}",
                    analysisId, ta.getKeyLevelSignal().getType(), ta.getKeyLevelSignal().getPrice());
        }

        if (ta.getChartPattern() != null) {
            log.info("[v62][{}]   Chart Pattern: {}", analysisId, ta.getChartPattern().getType());
        }

        log.info("[v62][{}]   Bullish Momentum: {}", analysisId, ta.isBullishMomentum());
        log.info("[v62][{}]   Bearish Momentum: {}", analysisId, ta.isBearishMomentum());
    }

    private Signal analyzeOption(Option option, BigDecimal stockPrice, TechnicalAnalysis ta, String analysisId) {
        log.trace("[v62][{}] Analyzing option: {} (Type: {}, Strike: ${}, Vol: {}, IV: {})",
                analysisId, option.getSymbol(), option.getType(), option.getStrikePrice(),
                option.getVolume(), option.getImpliedVolatility());

        // Calculate spread percentage
        BigDecimal spread = option.getAsk().subtract(option.getBid());
        BigDecimal midPrice = option.getBid().add(option.getAsk()).divide(BigDecimal.valueOf(2));
        double spreadPercentage = spread.divide(midPrice, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        if (spreadPercentage > maxSpreadPercentage) {
            log.trace("[v62][{}] Option {} filtered - High spread: {}%",
                    analysisId, option.getSymbol(), spreadPercentage);
            return null;
        }

        Signal signal = new Signal();
        signal.setSymbol(option.getUnderlying());
        signal.setOptionSymbol(option.getSymbol());
        signal.setTimestamp(LocalDateTime.now());
        signal.setExecuted(false);

        // Check strategies in order of priority

        // 1. VWAP Bounce Strategy (Highest confidence)
        if (ta.isNearVWAP() && ta.isVWAPBounce()) {
            if (option.getType().equals("CALL") && ta.isBullishVWAPBounce()) {
                log.debug("[v62][{}] VWAP Bounce CALL signal for {}", analysisId, option.getSymbol());
                signal.setSignalType("BUY");
                signal.setStrategy("0DTE_VWAP_BOUNCE_CALL");
                signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(1.8)));
                signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.4)));
                signal.setConfidence(0.85);
                signal.setReason("Bullish VWAP bounce with volume confirmation");
                return signal;
            } else if (option.getType().equals("PUT") && ta.isBearishVWAPBounce()) {
                log.debug("[v62][{}] VWAP Bounce PUT signal for {}", analysisId, option.getSymbol());
                signal.setSignalType("BUY");
                signal.setStrategy("0DTE_VWAP_BOUNCE_PUT");
                signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(1.8)));
                signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.4)));
                signal.setConfidence(0.85);
                signal.setReason("Bearish VWAP rejection with volume confirmation");
                return signal;
            }
        }

        // 2. Chart Pattern Strategy
        if (ta.getChartPattern() != null) {
            ChartPattern pattern = ta.getChartPattern();
            if (pattern.getType().equals("DOUBLE_BOTTOM") && option.getType().equals("CALL")) {
                log.debug("[v62][{}] Double Bottom CALL signal for {}", analysisId, option.getSymbol());
                signal.setSignalType("BUY");
                signal.setStrategy("0DTE_DOUBLE_BOTTOM");
                signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(2.2)));
                signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.4)));
                signal.setConfidence(0.88);
                signal.setReason("Double bottom reversal pattern confirmed");
                return signal;
            } else if (pattern.getType().equals("DOUBLE_TOP") && option.getType().equals("PUT")) {
                log.debug("[v62][{}] Double Top PUT signal for {}", analysisId, option.getSymbol());
                signal.setSignalType("BUY");
                signal.setStrategy("0DTE_DOUBLE_TOP");
                signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(2.2)));
                signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.4)));
                signal.setConfidence(0.88);
                signal.setReason("Double top reversal pattern confirmed");
                return signal;
            }
        }

        // 3. Trendline Breakout Strategy
        if (ta.getTrendlineBreakdown() != null) {
            if (ta.getTrendlineBreakdown().equals("BULLISH_BREAKOUT") && option.getType().equals("CALL")) {
                log.debug("[v62][{}] Trendline Breakout CALL signal for {}", analysisId, option.getSymbol());
                signal.setSignalType("BUY");
                signal.setStrategy("0DTE_TRENDLINE_BREAKOUT");
                signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(2.0)));
                signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.5)));
                signal.setConfidence(0.80);
                signal.setReason("Bullish trendline breakout with momentum");
                return signal;
            } else if (ta.getTrendlineBreakdown().equals("BEARISH_BREAKDOWN") && option.getType().equals("PUT")) {
                log.debug("[v62][{}] Trendline Breakdown PUT signal for {}", analysisId, option.getSymbol());
                signal.setSignalType("BUY");
                signal.setStrategy("0DTE_TRENDLINE_BREAKDOWN");
                signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(2.0)));
                signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.5)));
                signal.setConfidence(0.80);
                signal.setReason("Bearish trendline breakdown confirmed");
                return signal;
            }
        }

        // 4. Key Level Strategy
        if (ta.getKeyLevelSignal() != null) {
            KeyLevelSignal kls = ta.getKeyLevelSignal();
            if (kls.getType().equals("SUPPORT_BOUNCE") && option.getType().equals("CALL")) {
                log.debug("[v62][{}] Support Bounce CALL signal for {}", analysisId, option.getSymbol());
                signal.setSignalType("BUY");
                signal.setStrategy("0DTE_SUPPORT_BOUNCE");
                signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(1.6)));
                signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.5)));
                signal.setConfidence(0.75);
                signal.setReason(String.format("Bounce off %s support at $%.2f",
                        kls.getLevel(), kls.getPrice()));
                return signal;
            } else if (kls.getType().equals("RESISTANCE_REJECTION") && option.getType().equals("PUT")) {
                log.debug("[v62][{}] Resistance Rejection PUT signal for {}", analysisId, option.getSymbol());
                signal.setSignalType("BUY");
                signal.setStrategy("0DTE_RESISTANCE_REJECTION");
                signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(1.6)));
                signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.5)));
                signal.setConfidence(0.75);
                signal.setReason(String.format("Rejection at %s resistance at $%.2f",
                        kls.getLevel(), kls.getPrice()));
                return signal;
            }
        }

        // 5. Momentum Strategy (Fallback)
        boolean bullish = ta.isBullishMomentum();
        boolean bearish = ta.isBearishMomentum();

        if (option.getType().equals("CALL") && bullish &&
                option.getDelta() > 0.25 && option.getDelta() < 0.40) {
            log.debug("[v62][{}] Momentum CALL signal for {}", analysisId, option.getSymbol());
            signal.setSignalType("BUY");
            signal.setStrategy("0DTE_CALL_MOMENTUM");
            signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(1.5)));
            signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.5)));
            signal.setConfidence(calculateConfidence(option, true));
            signal.setReason("Bullish momentum with optimal delta");
            return signal;
        }

        if (option.getType().equals("PUT") && bearish &&
                option.getDelta() < -0.25 && option.getDelta() > -0.40) {
            log.debug("[v62][{}] Momentum PUT signal for {}", analysisId, option.getSymbol());
            signal.setSignalType("BUY");
            signal.setStrategy("0DTE_PUT_MOMENTUM");
            signal.setTargetPrice(midPrice.multiply(BigDecimal.valueOf(1.5)));
            signal.setStopLoss(midPrice.multiply(BigDecimal.valueOf(0.5)));
            signal.setConfidence(calculateConfidence(option, false));
            signal.setReason("Bearish momentum with optimal delta");
            return signal;
        }

        return null;
    }

    private double calculateConfidence(Option option, boolean bullish) {
        double confidence = 0.5;

        // Volume factor
        if (option.getVolume() > minVolume * 2) confidence += 0.1;
        if (option.getVolume() > minVolume * 5) confidence += 0.1;

        // IV factor
        if (option.getImpliedVolatility() > minIV * 1.5) confidence += 0.1;

        // Greeks alignment
        if (bullish && option.getGamma() > 0.01) confidence += 0.1;
        if (!bullish && option.getGamma() > 0.01) confidence += 0.1;

        double finalConfidence = Math.min(confidence, 0.9);
        log.trace("[v62] Calculated confidence: {} for option {}", finalConfidence, option.getSymbol());

        return finalConfidence;
    }
}