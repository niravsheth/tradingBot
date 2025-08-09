package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import com.tradingBot.entity.Trade;
import com.tradingBot.model.*;
import com.tradingBot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class PreTradeRiskEngine {

    private final TradierService tradierService;
    private final TradeRepository tradeRepository;
    private final SafetyService safetyService;

    @Value("${trading.pre-trade.max-vix:35}")
    private double maxVix;

    @Value("${trading.pre-trade.min-market-breadth:0.3}")
    private double minMarketBreadth;

    @Value("${trading.pre-trade.max-daily-trades:20}")
    private int maxDailyTrades;

    @Value("${trading.pre-trade.max-position-risk:1000}")
    private BigDecimal maxPositionRisk;

    public boolean isMarketSuitable() {
        String checkId = "MKT-" + System.currentTimeMillis();
        log.info("[PRE-TRADE][{}] Running market suitability checks", checkId);

        // 1. Check VIX level
        try {
            QuoteResponse vixQuote = tradierService.getQuote("VIX");
            if (vixQuote != null && vixQuote.getQuote() != null) {
                double vixLevel = vixQuote.getQuote().getLast().doubleValue();
                if (vixLevel > maxVix) {
                    log.warn("[PRE-TRADE][{}] VIX too high: {} > {}", checkId, vixLevel, maxVix);
                    return false;
                }
                //log.info("[PRE-TRADE][{}] VIX check passed: {}", checkId, vixLevel);
            }
        } catch (Exception e) {
            log.error("[PRE-TRADE][{}] VIX check failed: {}", checkId, e.getMessage());
        }

        // 2. Check market breadth (using QQQ volume as proxy)
        try {
            QuoteResponse qqqQuote = tradierService.getQuote("QQQ");
            if (qqqQuote != null && qqqQuote.getQuote() != null) {
                Quote quote = qqqQuote.getQuote();
                if (quote.getBidSize() != null && quote.getAskSize() != null) {
                    double breadth = (double) quote.getBidSize() /
                            (quote.getBidSize() + quote.getAskSize());
                    if (breadth < minMarketBreadth) {
                        log.warn("[PRE-TRADE][{}] Market breadth too low: {} < {}",
                                checkId, breadth, minMarketBreadth);
                        return false;
                    }
                    //log.info("[PRE-TRADE][{}] Market breadth check passed: {}", checkId, breadth);
                }
            }
        } catch (Exception e) {
            log.error("[PRE-TRADE][{}] Market breadth check failed: {}", checkId, e.getMessage());
        }

        // 3. Check daily trade count
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0);
        int todaysTrades = tradeRepository.countExecutedSignalsSince(startOfDay);
        if (todaysTrades >= maxDailyTrades) {
            log.warn("[PRE-TRADE][{}] Daily trade limit reached: {} >= {}",
                    checkId, todaysTrades, maxDailyTrades);
            return false;
        }

        // 4. Check if within trading hours
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 35)) || now.isAfter(LocalTime.of(15, 55))) {
            log.warn("[PRE-TRADE][{}] Outside optimal trading hours", checkId);
            return false;
        }

        // 5. Check safety service
        if (!safetyService.canTrade()) {
            log.warn("[PRE-TRADE][{}] Safety service blocking trades", checkId);
            return false;
        }

        log.info("[PRE-TRADE][{}] All market checks PASSED ✅", checkId);
        return true;
    }

    public boolean isStrikeSuitable(Option option) {
        if (option == null) return false;

        String checkId = option.getSymbol() + "-" + System.currentTimeMillis();
        log.debug("[PRE-TRADE][{}] Checking strike suitability", checkId);

        // 1. Volume check
        if (option.getVolume() < 50) {
            log.debug("[PRE-TRADE][{}] Volume too low: {} < 50", checkId, option.getVolume());
            return false;
        }

        // 2. Open Interest check
        if (option.getOpenInterest() < 1000) {
            log.debug("[PRE-TRADE][{}] OI too low: {} < 1000", checkId, option.getOpenInterest());
            return false;
        }

        // 3. Spread check
        double maxSpread = option.getMidPrice().compareTo(BigDecimal.ONE) > 0 ? 5.0 : 10.0;
        if (option.getSpreadPercentage() > maxSpread) {
            log.debug("[PRE-TRADE][{}] Spread too wide: {}% > {}%",
                    checkId, option.getSpreadPercentage(), maxSpread);
            return false;
        }

        // 4. Minimum premium check
        if (option.getMidPrice().compareTo(BigDecimal.valueOf(0.10)) < 0) {
            log.debug("[PRE-TRADE][{}] Premium too low: ${} < $0.10",
                    checkId, option.getMidPrice());
            return false;
        }

        // 5. Max premium check (avoid extremely expensive options)
        if (option.getMidPrice().compareTo(BigDecimal.valueOf(10.0)) > 0) {
            log.debug("[PRE-TRADE][{}] Premium too high: ${} > $10.00",
                    checkId, option.getMidPrice());
            return false;
        }

        return true;
    }

    public boolean canExecuteSignal(Signal signal) {
        String checkId = signal.getId() + "-FINAL";

        // Final pre-execution checks
        if (!isMarketSuitable()) {
            log.warn("[PRE-TRADE][{}] Market no longer suitable", checkId);
            return false;
        }

        // Check position risk
        BigDecimal estimatedRisk = calculatePositionRisk(signal);
        if (estimatedRisk.compareTo(maxPositionRisk) > 0) {
            log.warn("[PRE-TRADE][{}] Position risk too high: ${} > ${}",
                    checkId, estimatedRisk, maxPositionRisk);
            return false;
        }

        // Check for existing position in same symbol
        List<Trade> openTrades = tradeRepository.findByStatusAndSymbol("OPEN", signal.getSymbol());
        if (!openTrades.isEmpty()) {
            log.warn("[PRE-TRADE][{}] Already have open position in {}",
                    checkId, signal.getSymbol());
            return false;
        }

        return true;
    }

    private BigDecimal calculatePositionRisk(Signal signal) {
        // Conservative risk calculation
        BigDecimal optionPrice = signal.getEntryPrice();
        BigDecimal stopLoss = signal.getStopLoss();
        BigDecimal riskPerContract = optionPrice.subtract(stopLoss)
                .multiply(BigDecimal.valueOf(100));

        // Assume 5 contracts for now (will be properly sized later)
        return riskPerContract.multiply(BigDecimal.valueOf(5));
    }
}