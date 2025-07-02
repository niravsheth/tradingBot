package com.tradingBot.service;

import com.tradingBot.config.TradingConfig;
import com.tradingBot.entity.Trade;
import com.tradingBot.model.*;
import com.tradingBot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class PositionMonitoringService {

    private final TradeRepository tradeRepository;
    private final TradierService tradierService;
    private final TelegramService telegramService;
    private final TradingConfig tradingConfig;

    @Transactional
    public void monitorPositions() {
        try {
            List<Trade> openTrades = tradeRepository.findByStatus("OPEN");

            for (Trade trade : openTrades) {
                monitorTrade(trade);
            }

        } catch (Exception e) {
            log.error("Error monitoring positions: {}", e.getMessage());
        }
    }

    private void monitorTrade(Trade trade) {
        try {
            // Get current price
            QuoteResponse quote = tradierService.getQuote(trade.getOptionSymbol());
            if (quote == null || quote.getQuote() == null) {
                log.warn("Failed to get quote for {}", trade.getOptionSymbol());
                return;
            }

            BigDecimal currentPrice = quote.getQuote().getLast();
            trade.setCurrentPrice(currentPrice);

            // Calculate P&L
            BigDecimal pnl = currentPrice.subtract(trade.getEntryPrice())
                    .divide(trade.getEntryPrice(), 4, RoundingMode.HALF_UP);

            // Check stop loss
            if (currentPrice.compareTo(trade.getStopLoss()) <= 0) {
                closeTrade(trade, currentPrice, "STOP_LOSS_HIT");
                return;
            }

            // Check target
            if (currentPrice.compareTo(trade.getTargetPrice()) >= 0) {
                closeTrade(trade, currentPrice, "TARGET_REACHED");
                return;
            }

            // Handle trailing stop
            handleTrailingStop(trade, currentPrice, pnl);

            // Update highest price
            if (trade.getHighestPrice() == null ||
                    currentPrice.compareTo(trade.getHighestPrice()) > 0) {
                trade.setHighestPrice(currentPrice);
            }

            trade.setUpdatedAt(LocalDateTime.now());
            tradeRepository.save(trade);

        } catch (Exception e) {
            log.error("Error monitoring trade {}: {}", trade.getId(), e.getMessage());
        }
    }

    private void handleTrailingStop(Trade trade, BigDecimal currentPrice, BigDecimal pnl) {
        BigDecimal activationLevel = tradingConfig.getRisk().getTrailingStopActivation();
        BigDecimal trailingDistance = tradingConfig.getRisk().getTrailingStopDistance();

        // Check if trailing stop should be activated
        if (!Boolean.TRUE.equals(trade.getTrailingActivated()) &&
                pnl.compareTo(activationLevel) >= 0) {

            // Activate trailing stop
            trade.setTrailingActivated(true);
            BigDecimal newStop = currentPrice.multiply(
                    BigDecimal.ONE.subtract(trailingDistance));

            // Only update if new stop is higher than current stop
            if (newStop.compareTo(trade.getStopLoss()) > 0) {
                trade.setOriginalStop(trade.getStopLoss());
                trade.setStopLoss(newStop);
                trade.setLastAdjustmentTime(LocalDateTime.now());

                sendTrailingStopAlert(trade, newStop, "ACTIVATED");
                log.info("Trailing stop activated for {} at ${}",
                        trade.getOptionSymbol(), newStop);
            }
        }

        // Adjust trailing stop if active
        else if (Boolean.TRUE.equals(trade.getTrailingActivated())) {
            BigDecimal newStop = currentPrice.multiply(
                    BigDecimal.ONE.subtract(trailingDistance));

            // Only raise stop, never lower it
            if (newStop.compareTo(trade.getStopLoss()) > 0) {
                trade.setStopLoss(newStop);
                trade.setLastAdjustmentTime(LocalDateTime.now());

                log.debug("Trailing stop adjusted for {} to ${}",
                        trade.getOptionSymbol(), newStop);
            }
        }
    }

    private void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        try {
            // Create close order
            OrderRequest closeOrder = new OrderRequest();
            closeOrder.setupForOption(trade.getOptionSymbol());
            closeOrder.setSide("sell_to_close");
            closeOrder.setQuantity(trade.getQuantity());
            closeOrder.setType("market");
            closeOrder.setDuration("day");

            // Execute close order
            OrderResponse response = tradierService.placeOrder(closeOrder);

            if (response != null && response.getId() != null) {
                // Update trade
                trade.setStatus("CLOSED");
                trade.setExitPrice(exitPrice);
                trade.setExitTime(LocalDateTime.now());
                trade.setCloseReason(reason);
                trade.setExitReason(reason);

                // Calculate realized P&L
                BigDecimal totalEntry = trade.getEntryPrice()
                        .multiply(BigDecimal.valueOf(trade.getQuantity()));
                BigDecimal totalExit = exitPrice
                        .multiply(BigDecimal.valueOf(trade.getQuantity()));
                BigDecimal realizedPnl = totalExit.subtract(totalEntry);

                trade.setRealizedPnl(realizedPnl);
                trade.setProfit(realizedPnl);

                tradeRepository.save(trade);

                // Send notification
                sendCloseNotification(trade, reason);

                log.info("Trade {} closed - Reason: {}, P&L: ${}",
                        trade.getId(), reason, realizedPnl);
            }

        } catch (Exception e) {
            log.error("Error closing trade {}: {}", trade.getId(), e.getMessage());
        }
    }

    private void sendTrailingStopAlert(Trade trade, BigDecimal newStop, String action) {
        String message = String.format(
                "🎯 <b>TRAILING STOP %s</b>\n" +
                        "Option: %s\n" +
                        "Entry: $%.2f\n" +
                        "Current Stop: $%.2f\n" +
                        "New Stop: $%.2f\n" +
                        "Protected Profit: %.1f%%",
                action,
                trade.getOptionSymbol(),
                trade.getEntryPrice(),
                trade.getOriginalStop() != null ? trade.getOriginalStop() : trade.getStopLoss(),
                newStop,
                (newStop.divide(trade.getEntryPrice(), 4, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE)).multiply(BigDecimal.valueOf(100)).doubleValue()
        );

        telegramService.sendMessage(message);
    }

    private void sendCloseNotification(Trade trade, String reason) {
        BigDecimal pnlPercent = trade.getRealizedPnl()
                .divide(trade.getEntryPrice().multiply(BigDecimal.valueOf(trade.getQuantity())),
                        4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        String emoji = trade.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0 ? "✅" : "❌";

        String message = String.format(
                "%s <b>POSITION CLOSED</b>\n" +
                        "Option: %s\n" +
                        "Quantity: %d contracts\n" +
                        "Entry: $%.2f\n" +
                        "Exit: $%.2f\n" +
                        "P&L: $%.2f (%.1f%%)\n" +
                        "Reason: %s\n" +
                        "Duration: %d minutes",
                emoji,
                trade.getOptionSymbol(),
                trade.getQuantity(),
                trade.getEntryPrice(),
                trade.getExitPrice(),
                trade.getRealizedPnl(),
                pnlPercent,
                reason.replace("_", " "),
                java.time.Duration.between(trade.getEntryTime(), trade.getExitTime()).toMinutes()
        );

        telegramService.sendMessage(message);
    }
}