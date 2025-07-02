package com.tradingBot.engine;

import com.tradingBot.config.ThreadPoolConfig;
import com.tradingBot.entity.Signal;
import com.tradingBot.repository.SignalRepository;
import com.tradingBot.service.SignalExecutionService;
import com.tradingBot.service.PositionMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class TradingEngine {

    private final SignalRepository signalRepository;
    private final SignalExecutionService signalExecutionService;
    private final TrendTrackingEngine trendEngine;
    private final FlowConfirmationEngine flowEngine;
    private final PositionMonitoringService positionMonitoringService;
    private final ThreadPoolConfig threadPoolConfig;

    @Value("${tradingbot.monitoring.position-check-interval:30000}")
    private long positionCheckInterval;

    @Value("${tradingbot.monitoring.trend-update-interval:60000}")
    private long trendUpdateInterval;

    @Value("${tradingbot.trading.timezone:America/New_York}")
    private String marketTimezone;

    private ThreadPoolExecutor executorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        log.info("Initializing Trading Engine...");

        ThreadPoolConfig.PoolSettings settings = threadPoolConfig.getTradingEngine();

        executorService = new ThreadPoolExecutor(
                settings.getCoreSize(),
                settings.getMaxSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(settings.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r);
                    t.setName(settings.getThreadPrefix() + t.getId());
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        start();
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info("Starting Trading Engine components...");

            // Start flow confirmation engine
            flowEngine.start();

            // Start continuous trend analysis thread
            executorService.submit(this::runTrendAnalysis);

            // Start signal execution thread
            executorService.submit(this::runSignalExecution);

            // Start position monitoring thread
            executorService.submit(this::runPositionMonitoring);

            log.info("Trading Engine started with {} threads",
                    executorService.getCorePoolSize());
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("Stopping Trading Engine...");

            flowEngine.stop();

            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }

            log.info("Trading Engine stopped");
        }
    }

    private void runTrendAnalysis() {
        log.info("Starting trend analysis thread");

        while (isRunning.get()) {
            try {
                // Analyze market trend
                trendEngine.analyzeMarketTrend();

                // Analyze trends for symbols with pending signals
                List<Signal> pendingSignals = signalRepository.findByExecutedFalseAndStatus("PENDING");
                for (Signal signal : pendingSignals) {
                    trendEngine.analyzeTrend(signal.getSymbol());
                }

                Thread.sleep(trendUpdateInterval);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in trend analysis: {}", e.getMessage());
            }
        }

        log.info("Trend analysis thread stopped");
    }

    private void runSignalExecution() {
        log.info("Starting signal execution thread");

        while (isRunning.get()) {
            try {
                // Check if market is open
                if (!isMarketOpen()) {
                    Thread.sleep(60000); // Wait 1 minute
                    continue;
                }

                // Get pending signals
                List<Signal> pendingSignals = signalRepository
                        .findFreshUnexecutedSignalsPrioritized(LocalDateTime.now());

                if (!pendingSignals.isEmpty()) {
                    log.info("Processing {} pending signals", pendingSignals.size());

                    for (Signal signal : pendingSignals) {
                        // Submit to thread pool to avoid blocking
                        executorService.submit(() -> signalExecutionService.executeSignal(signal));

                        // Small delay between executions
                        Thread.sleep(2000);
                    }
                }

                Thread.sleep(30000); // Check every 30 seconds

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in signal execution: {}", e.getMessage());
            }
        }

        log.info("Signal execution thread stopped");
    }

    private void runPositionMonitoring() {
        log.info("Starting position monitoring thread");

        while (isRunning.get()) {
            try {
                // Only monitor during market hours
                if (!isMarketOpen()) {
                    Thread.sleep(60000); // Wait 1 minute
                    continue;
                }

                // Monitor all open positions
                positionMonitoringService.monitorPositions();

                Thread.sleep(positionCheckInterval);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in position monitoring: {}", e.getMessage());
            }
        }

        log.info("Position monitoring thread stopped");
    }

    @Scheduled(cron = "0 */5 * * * *") // Every 5 minutes
    public void monitorHealth() {
        if (isRunning.get()) {
            ThreadPoolExecutor pool = executorService;
            log.info("Trading Engine Health: Running | Market: {} | Trend: {} | " +
                            "Active Threads: {}/{} | Queue: {}/{}",
                    isMarketOpen() ? "OPEN" : "CLOSED",
                    trendEngine.getMarketTrend(),
                    pool.getActiveCount(),
                    pool.getPoolSize(),
                    pool.getQueue().size(),
                    pool.getQueue().remainingCapacity()
            );
        }
    }

    private boolean isMarketOpen() {
        ZoneId zone = ZoneId.of(marketTimezone);
        LocalTime now = LocalTime.now(zone);
        LocalTime marketOpen = LocalTime.of(9, 30);
        LocalTime marketClose = LocalTime.of(16, 0);

        return now.isAfter(marketOpen) && now.isBefore(marketClose) &&
                !isWeekend() && !isHoliday();
    }

    private boolean isWeekend() {
        int dayOfWeek = LocalDateTime.now().getDayOfWeek().getValue();
        return dayOfWeek == 6 || dayOfWeek == 7;
    }

    private boolean isHoliday() {
        // Add holiday check logic
        return false;
    }
}