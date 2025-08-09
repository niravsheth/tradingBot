package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import com.tradingBot.entity.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class PositionService {

    private final TradierService tradierService;
    private final PositionSyncService positionSyncService;

    public void openPosition(Trade trade, Signal signal) {
        try {
            log.info("[POSITION] Opening position for {} - Qty: {}",
                    trade.getOptionSymbol(), trade.getQuantity());

            trade.setStatus("OPEN");
            trade.setCreatedAt(ZonedDateTime.now());

        } catch (Exception e) {
            log.error("[POSITION] Error opening position: {}", e.getMessage());
            throw new RuntimeException("Failed to open position", e);
        }
    }

    public BigDecimal closePosition(Trade trade, BigDecimal exitPrice) {
        try {
            log.info("[POSITION] Closing position {} at ${}",
                    trade.getOptionSymbol(), exitPrice);

            BigDecimal entryPrice = trade.getEntryPrice();
            int quantity = trade.getQuantity() != null ? trade.getQuantity() : 1;

            BigDecimal pnl = exitPrice.subtract(entryPrice)
                    .multiply(BigDecimal.valueOf(quantity * 100));

            trade.setExitPrice(exitPrice);
            trade.setRealizedPnl(pnl);
            trade.setClosedAt(ZonedDateTime.now());
            trade.setStatus("CLOSED");

            positionSyncService.refreshPositions();

            return pnl;

        } catch (Exception e) {
            log.error("[POSITION] Error closing position: {}", e.getMessage());
            throw new RuntimeException("Failed to close position", e);
        }
    }
}