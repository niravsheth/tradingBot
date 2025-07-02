package com.tradingBot.service;


import com.tradingBot.entity.Trade;
import com.tradingBot.repository.TradeRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Getter
@Setter
@Slf4j
@RequiredArgsConstructor
public class SafetyService {

    private final TradeRepository tradeRepository;
    private final TelegramService telegramService;

    @Value("${safety.trading-enabled:false}")
    private boolean tradingEnabled;

    @Value("${safety.paper-mode:true}")
    private boolean paperMode;

    @Value("${safety.max-daily-loss:50}")
    private BigDecimal maxDailyLoss;

    @Value("${safety.max-position-value:100}")
    private BigDecimal maxPositionValue;

    @Value("${safety.max-open-positions:3}")
    private int maxOpenPositions;

    private volatile boolean emergencyStopActive = false;

    /**
     * Master switch - check if trading is enabled
     */
    public boolean isTradingEnabled() {
        return tradingEnabled && !emergencyStopActive;
    }

    /**
     * Check if paper mode is enabled
     */
    public boolean isPaperMode() {
        return paperMode;
    }

    /**
     * Check all safety conditions before allowing a trade
     */
    public boolean canTrade() {
        // Master switch check
        if (!tradingEnabled) {
            log.info("Trading disabled via master switch");
            return false;
        }

        // Emergency stop check
        if (emergencyStopActive) {
            log.error("Emergency stop is active - no trading allowed");
            return false;
        }

        // Check daily loss limit
        BigDecimal todayLoss = calculateTodayPnL();
        if (todayLoss.compareTo(maxDailyLoss.negate()) < 0) {
            String msg = String.format(
                    "🚨 Daily loss limit hit: $%.2f (limit: $%.2f)",
                    todayLoss.abs(), maxDailyLoss);
            log.error(msg);
            telegramService.sendMessage(msg);
            return false;
        }

        // Check open positions limit
        long openPositions = tradeRepository.countByStatus("OPEN");
        if (openPositions >= maxOpenPositions) {
            log.warn("Maximum open positions reached: {}/{}",
                    openPositions, maxOpenPositions);
            return false;
        }

        return true;
    }

    /**
     * Validate position size against limits
     */
    public boolean validatePositionSize(BigDecimal positionValue) {
        if (positionValue.compareTo(maxPositionValue) > 0) {
            log.error("Position size ${} exceeds max ${}",
                    positionValue, maxPositionValue);
            return false;
        }
        return true;
    }

    /**
     * EMERGENCY STOP - Kill switch for the bot
     */
    public void activateEmergencyStop(String reason) {
        emergencyStopActive = true;
        log.error("🚨🚨🚨 EMERGENCY STOP ACTIVATED: {} 🚨🚨🚨", reason);

        // Send detailed notification with open positions
        telegramService.sendEmergencyStopNotification(reason);

        // Log all open positions
        List<Trade> openTrades = tradeRepository.findByStatusAndSymbol("OPEN", "QQQ");
        log.error("Open positions at emergency stop: {}", openTrades.size());
        for (Trade trade : openTrades) {
            log.error("Open: {} {} @ ${} x {}",
                    trade.getOptionSymbol(), trade.getType(),
                    trade.getEntryPrice(), trade.getQuantity());
        }
    }

    /**
     * Deactivate emergency stop (requires manual intervention)
     */
    public void deactivateEmergencyStop() {
        emergencyStopActive = false;
        log.info("Emergency stop deactivated");
        telegramService.sendMessage("✅ Emergency stop deactivated. Bot can resume trading.");
    }

    /**
     * Calculate today's P&L
     */
    private BigDecimal calculateTodayPnL() {
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0);
        BigDecimal pnl = tradeRepository.calculateProfitSince(startOfDay);
        return pnl != null ? pnl : BigDecimal.ZERO;
    }

    /**
     * Get current safety status
     */
    public SafetyStatus getStatus() {
        SafetyStatus status = new SafetyStatus();
        status.setTradingEnabled(tradingEnabled);
        status.setPaperMode(paperMode);
        status.setEmergencyStopActive(emergencyStopActive);
        status.setTodayPnL(calculateTodayPnL());
        status.setOpenPositions(tradeRepository.countByStatus("OPEN"));
        status.setMaxDailyLoss(maxDailyLoss);
        status.setMaxPositionValue(maxPositionValue);
        status.setMaxOpenPositions(maxOpenPositions);
        return status;
    }

    public boolean isEmergencyStopActive() {
        return emergencyStopActive;
    }

    public BigDecimal getMaxDailyLoss() {
        return maxDailyLoss;
    }

    public BigDecimal getMaxPositionValue() {
        return maxPositionValue;
    }

    public int getMaxOpenPositions() {
        return maxOpenPositions;
    }

    // Safety status DTO
    public static class SafetyStatus {
        private boolean tradingEnabled;
        private boolean paperMode;
        private boolean emergencyStopActive;
        private BigDecimal todayPnL;
        private long openPositions;
        private BigDecimal maxDailyLoss;
        private BigDecimal maxPositionValue;
        private int maxOpenPositions;

        private int DailyLossLimit;

        // Getters and setters
        public boolean isTradingEnabled() { return tradingEnabled; }
        public void setTradingEnabled(boolean tradingEnabled) { this.tradingEnabled = tradingEnabled; }

        public boolean isPaperMode() { return paperMode; }
        public void setPaperMode(boolean paperMode) { this.paperMode = paperMode; }

        public boolean isEmergencyStopActive() { return emergencyStopActive; }
        public void setEmergencyStopActive(boolean emergencyStopActive) { this.emergencyStopActive = emergencyStopActive; }

        public BigDecimal getTodayPnL() { return todayPnL; }
        public void setTodayPnL(BigDecimal todayPnL) { this.todayPnL = todayPnL; }

        public long getOpenPositions() { return openPositions; }
        public void setOpenPositions(long openPositions) { this.openPositions = openPositions; }

        public BigDecimal getMaxDailyLoss() { return maxDailyLoss; }
        public void setMaxDailyLoss(BigDecimal maxDailyLoss) { this.maxDailyLoss = maxDailyLoss; }

        public BigDecimal getMaxPositionValue() { return maxPositionValue; }
        public void setMaxPositionValue(BigDecimal maxPositionValue) { this.maxPositionValue = maxPositionValue; }

        public int getMaxOpenPositions() { return maxOpenPositions; }
        public void setMaxOpenPositions(int maxOpenPositions) { this.maxOpenPositions = maxOpenPositions; }
    }
}