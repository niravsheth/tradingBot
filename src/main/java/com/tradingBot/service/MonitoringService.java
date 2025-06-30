package com.tradingBot.service;

import com.tradingBot.entity.Trade;
import com.tradingBot.model.Quote;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MonitoringService {

    private final SafetyService safetyService;
    private final TelegramService telegramService;
    private final TradeRepository tradeRepository;
    private final TradierService tradierService;
    private final TradingService tradingService;

    @Value("${trading.trailing-stop.enabled:true}")
    private boolean trailingStopEnabled;

    @Value("${trading.trailing-stop.trigger-profit:0.30}")
    private double trailingStopTrigger; // Start trailing after 30% profit

    @Value("${trading.trailing-stop.distance:0.20}")
    private double trailingStopDistance; // Trail by 20% from high

    // Monitor positions every 30 seconds during market hours
    @Scheduled(cron = "*/30 * 9-16 * * MON-FRI")
    public void monitorPositions() {
        if (!safetyService.isTradingEnabled()) {
            return;
        }

        List<Trade> openTrades = tradeRepository.findByStatus("OPEN");

        if (openTrades.isEmpty()) {
            return;
        }

        log.debug("[MONITOR] Checking {} open positions", openTrades.size());

        for (Trade trade : openTrades) {
            try {
                QuoteResponse response = tradierService.getQuote(trade.getOptionSymbol());
                if (response == null || response.getQuote() == null) {
                    log.warn("[MONITOR] No quote for {}", trade.getOptionSymbol());
                    continue;
                }

                Quote optionQuote = response.getQuote();
                BigDecimal currentPrice = optionQuote.getLast();

                if (currentPrice == null) {
                    continue;
                }

                // Calculate P/L for logging only
                BigDecimal qty = BigDecimal.valueOf(trade.getQuantity() * 100);
                BigDecimal currentValue = currentPrice.multiply(qty);
                BigDecimal entryValue = trade.getEntryPrice().multiply(qty);
                BigDecimal profitLoss = currentValue.subtract(entryValue);
                BigDecimal profitPercent = profitLoss.divide(entryValue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

                // Update trailing stop
                updateStopLoss(trade, optionQuote);

                // Check stop loss
                if (currentPrice.compareTo(trade.getStopLoss()) <= 0) {
                    log.warn("[MONITOR] Stop loss hit for {} at ${} (stop: ${})",
                            trade.getOptionSymbol(), currentPrice, trade.getStopLoss());

                    // Close position through trading service
                    tradingService.closePosition(trade, currentPrice, "STOP_LOSS");
                    continue;
                }

                // Check target
                if (currentPrice.compareTo(trade.getTargetPrice()) >= 0) {
                    log.info("[MONITOR] Target reached for {} at ${} (target: ${})",
                            trade.getOptionSymbol(), currentPrice, trade.getTargetPrice());

                    // Close position through trading service
                    tradingService.closePosition(trade, currentPrice, "TARGET_HIT");
                    continue;
                }

                // Log position status
                log.debug("[MONITOR] {} - Price: ${}, P/L: ${} ({}%), Stop: ${}, Target: ${}",
                        trade.getOptionSymbol(), currentPrice, profitLoss, profitPercent,
                        trade.getStopLoss(), trade.getTargetPrice());

            } catch (Exception e) {
                log.error("[MONITOR] Error monitoring {}: {}",
                        trade.getOptionSymbol(), e.getMessage());
            }
        }
    }

    // Monitor system health every 30 minutes
    @Scheduled(fixedRate = 1800000)
    public void monitorSystemHealth() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        long maxMemory = heapUsage.getMax() / 1024 / 1024;
        long usedMemory = heapUsage.getUsed() / 1024 / 1024;
        double memoryPercent = (double) usedMemory / maxMemory * 100;

        if (memoryPercent > 80) {
            log.warn("High memory usage: {}%", String.format("%.1f", memoryPercent));
            telegramService.sendMessage(String.format(
                    "⚠️ High memory usage: %.1f%% (%d MB / %d MB)",
                    memoryPercent, usedMemory, maxMemory));
        }

        // Log current status
        SafetyService.SafetyStatus status = safetyService.getStatus();
        log.info("System Status - Trading: {}, Paper: {}, P&L: ${}, Open: {}",
                status.isTradingEnabled(),
                status.isPaperMode(),
                status.getTodayPnL(),
                status.getOpenPositions());
    }

    // Check for stuck positions every hour
    @Scheduled(fixedRate = 3600000)
    public void checkStuckPositions() {
        List<Trade> openTrades = tradeRepository.findByStatus("OPEN");
        LocalDateTime cutoff = LocalDateTime.now().minusHours(4);

        for (Trade trade : openTrades) {
            if (trade.getEntryTime().isBefore(cutoff)) {
                log.warn("[MONITOR] Position {} open for over 4 hours", trade.getOptionSymbol());
                telegramService.sendMessage(String.format(
                        "⚠️ Stuck position: %s open since %s",
                        trade.getOptionSymbol(),
                        trade.getEntryTime()
                ));
            }
        }
    }

    private void updateStopLoss(Trade trade, Quote optionQuote) {
        if (!trailingStopEnabled || !trade.getStatus().equals("OPEN")) {
            return;
        }

        BigDecimal currentPrice = optionQuote.getLast();
        BigDecimal entryPrice = trade.getEntryPrice();
        BigDecimal currentStop = trade.getStopLoss();

        // Calculate profit percentage
        BigDecimal profitPercent = currentPrice.subtract(entryPrice)
                .divide(entryPrice, 4, RoundingMode.HALF_UP);

        // Start trailing at 5% profit instead of 30%
        if (profitPercent.compareTo(BigDecimal.valueOf(0.05)) > 0) {
            // Trail at 15% below current price
            BigDecimal newStop = currentPrice.multiply(
                    BigDecimal.ONE.subtract(BigDecimal.valueOf(0.15))
            );

            // Only update if new stop is higher
            if (newStop.compareTo(currentStop) > 0) {
                trade.setStopLoss(newStop);
                trade.setUpdatedAt(LocalDateTime.now());
                tradeRepository.save(trade);

                log.info("[TRAILING] Updated stop for {} from ${} to ${} ({}% profit)",
                        trade.getOptionSymbol(), currentStop, newStop,
                        profitPercent.multiply(BigDecimal.valueOf(100)));

                // Remove the target update section - keep original target

                // Send notification
                telegramService.sendMessage(String.format(
                        "📈 Trailing stop updated for %s\nNew stop: $%.2f (was $%.2f)\nProfit: %.1f%%",
                        trade.getOptionSymbol(), newStop, currentStop,
                        profitPercent.multiply(BigDecimal.valueOf(100))
                ));
            }
        }
    }
}