package com.tradingBot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

@Service
@Slf4j
@RequiredArgsConstructor
public class MonitoringService {

    private final SafetyService safetyService;
    private final TelegramService telegramService;

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
        // This would check for positions that should have been closed
        // Implementation depends on your specific requirements
    }
}