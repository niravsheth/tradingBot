//package com.tradingBot;
//
//import com.tradingBot.entity.*;
//import com.tradingBot.service.*;
//import com.tradingBot.repository.*;
//import jakarta.transaction.Transactional;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//
//import java.math.BigDecimal;
//import java.math.RoundingMode;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.ZoneOffset;
//import java.time.format.DateTimeFormatter;
//import java.util.*;
//import java.util.stream.Collectors;
//
//@Component
//@Slf4j
//@Transactional
//public class AITrainingApplication {
//
//    // ================================================================================================
//    // DEPENDENCIES (using your existing repositories and services)
//    // ================================================================================================
//
//    @Autowired
//    private TradierService tradierService;
//
//    @Autowired
//    private MarketDataRepository marketDataRepository;
//
//    @Autowired
//    private SignalRepository signalRepository;
//
//    @Autowired
//    private TradeRepository tradeRepository;
//
//    @Autowired
//    private ZeroDTEStrategy zeroDTEStrategy;
//
//    @Value("${training.batch-size:500}")
//    private int batchSize;
//
//    // Training progress tracking
//    private volatile boolean isTraining = false;
//    private volatile String trainingStatus = "READY";
//    private volatile int trainingProgress = 0;
//
//    // ================================================================================================
//    // MAIN TRAINING METHOD - Call this to train your AI
//    // ================================================================================================
//
//    public void startAITraining() {
//        if (isTraining) {
//            log.warn("⚠️ Training already in progress...");
//            return;
//        }
//
//        isTraining = true;
//        trainingStatus = "STARTING";
//        trainingProgress = 0;
//
//        try {
//            log.info("🤖 Starting AI training with 6 months of Tradier data...");
//
//            LocalDate endDate = LocalDate.now();
//            LocalDate startDate = endDate.minusMonths(6);
//
//            // Step 1: Scrape and store market data (20%)
//            trainingStatus = "SCRAPING_MARKET_DATA";
//            scrapeMarketData(startDate, endDate);
//            trainingProgress = 20;
//
//            // Step 2: Generate simulated trades (40%)
//            trainingStatus = "GENERATING_TRADES";
//            generateSimulatedTrades(startDate, endDate);
//            trainingProgress = 60;
//
//            // Step 3: Train AI learning system (80%)
//            trainingStatus = "TRAINING_AI";
//            trainAILearningSystem();
//            trainingProgress = 80;
//
//            // Step 4: Initialize AI with training data (100%)
//            trainingStatus = "INITIALIZING_AI";
//            initializeAIWithTrainingData();
//            trainingProgress = 100;
//
//            trainingStatus = "COMPLETED";
//            log.info("✅ AI training completed successfully!");
//
//        } catch (Exception e) {
//            trainingStatus = "FAILED";
//            log.error("❌ AI training failed: {}", e.getMessage(), e);
//            throw new RuntimeException("AI training failed", e);
//        } finally {
//            isTraining = false;
//        }
//    }
//
//    // ================================================================================================
//    // STEP 1: SCRAPE MARKET DATA
//    // ================================================================================================
//
//    private void scrapeMarketData(LocalDate startDate, LocalDate endDate) {
//        log.info("📊 Scraping market data from {} to {}", startDate, endDate);
//
//        String[] symbols = {"SPY", "QQQ", "VIX"};
//
//        for (String symbol : symbols) {
//            try {
//                log.info("Fetching {} data...", symbol);
//
//                // Get historical data from Tradier (1-minute bars)
//                List<MarketData> existingData = marketDataRepository.findBySymbolAndTimestampBetween(
//                        symbol, startDate.atStartOfDay(), endDate.atTime(23, 59));
//
//                if (existingData.size() > 1000) {
//                    log.info("✅ {} already has {} data points, skipping...", symbol, existingData.size());
//                    continue;
//                }
//
//                // Fetch from Tradier API
//                HistoricalDataResponse response = tradierService.getHistoricalData(
//                        symbol, startDate, endDate, "minute");
//
//                if (response != null && response.getHistory() != null) {
//                    List<MarketData> marketDataBatch = new ArrayList<>();
//
//                    for (HistoricalBar bar : response.getHistory().getBars()) {
//                        MarketData data = new MarketData();
//                        data.setSymbol(symbol);
//                        data.setTimestamp(convertToLocalDateTime(bar.getTime()));
//                        data.setOpen(bar.getOpen());
//                        data.setHigh(bar.getHigh());
//                        data.setLow(bar.getLow());
//                        data.setPrice(bar.getClose());
//                        data.setVolume(bar.getVolume());
//                        data.setVwap(calculateSimpleVWAP(bar));
//
//                        marketDataBatch.add(data);
//
//                        if (marketDataBatch.size() >= batchSize) {
//                            marketDataRepository.saveAll(marketDataBatch);
//                            marketDataBatch.clear();
//                            log.debug("Saved {} {} bars", batchSize, symbol);
//                        }
//                    }
//
//                    // Save remaining
//                    if (!marketDataBatch.isEmpty()) {
//                        marketDataRepository.saveAll(marketDataBatch);
//                    }
//
//                    log.info("✅ Saved {} {} market data points",
//                            response.getHistory().getBars().size(), symbol);
//                }
//
//                // Rate limiting
//                Thread.sleep(2000);
//
//            } catch (Exception e) {
//                log.error("Error scraping {} data: {}", symbol, e.getMessage());
//            }
//        }
//    }
//
//    // ================================================================================================
//    // STEP 2: GENERATE SIMULATED TRADES
//    // ================================================================================================
//
//    private void generateSimulatedTrades(LocalDate startDate, LocalDate endDate) {
//        log.info("🎯 Generating simulated trades...");
//
//        // Get SPY and QQQ data
//        List<MarketData> spyData = marketDataRepository.findBySymbolAndTimestampBetween(
//                "SPY", startDate.atStartOfDay(), endDate.atTime(23, 59));
//        List<MarketData> qqqData = marketDataRepository.findBySymbolAndTimestampBetween(
//                "QQQ", startDate.atStartOfDay(), endDate.atTime(23, 59));
//
//        if (spyData.size() < 100 || qqqData.size() < 100) {
//            log.warn("⚠️ Insufficient market data for simulation");
//            return;
//        }
//
//        // Group by date
//        Map<LocalDate, List<MarketData>> spyByDate = spyData.stream()
//                .collect(Collectors.groupingBy(d -> d.getTimestamp().toLocalDate()));
//        Map<LocalDate, List<MarketData>> qqqByDate = qqqData.stream()
//                .collect(Collectors.groupingBy(d -> d.getTimestamp().toLocalDate()));
//
//        List<Trade> simulatedTrades = new ArrayList<>();
//        List<Signal> simulatedSignals = new ArrayList<>();
//        Random random = new Random(42); // Deterministic seed
//
//        int totalDays = spyByDate.keySet().size();
//        int processedDays = 0;
//
//        for (LocalDate date : spyByDate.keySet()) {
//            if (!qqqByDate.containsKey(date)) continue;
//
//            List<MarketData> daySpyData = spyByDate.get(date);
//            List<MarketData> dayQqqData = qqqByDate.get(date);
//
//            if (daySpyData.size() < 30 || dayQqqData.size() < 30) continue;
//
//            // Generate 3-8 trades per day
//            int tradesPerDay = 3 + random.nextInt(6);
//
//            for (int i = 0; i < tradesPerDay; i++) {
//                TradeSimulationResult result = simulateRealisticTrade(
//                        daySpyData, dayQqqData, date, random);
//
//                if (result != null) {
//                    simulatedTrades.add(result.trade);
//                    simulatedSignals.add(result.signal);
//                }
//            }
//
//            // Batch save every 100 trades
//            if (simulatedTrades.size() >= 100) {
//                signalRepository.saveAll(simulatedSignals);
//                tradeRepository.saveAll(simulatedTrades);
//
//                log.info("Saved {} simulated trades", simulatedTrades.size());
//                simulatedTrades.clear();
//                simulatedSignals.clear();
//            }
//
//            processedDays++;
//            if (processedDays % 10 == 0) {
//                log.info("Processed {}/{} days", processedDays, totalDays);
//            }
//        }
//
//        // Save remaining
//        if (!simulatedTrades.isEmpty()) {
//            signalRepository.saveAll(simulatedSignals);
//            tradeRepository.saveAll(simulatedTrades);
//        }
//
//        log.info("✅ Generated {} simulated trades",
//                tradeRepository.countByStatus("SIMULATED"));
//    }
//
//    // ================================================================================================
//    // TRADE SIMULATION LOGIC
//    // ================================================================================================
//
//    private TradeSimulationResult simulateRealisticTrade(List<MarketData> spyData,
//                                                         List<MarketData> qqqData,
//                                                         LocalDate date, Random random) {
//        try {
//            // Pick entry time (9:45 AM to 3:00 PM)
//            int entryIndex = 15 + random.nextInt(Math.min(spyData.size() - 60, 200));
//
//            MarketData spyEntry = spyData.get(entryIndex);
//            MarketData qqqEntry = qqqData.get(entryIndex);
//
//            // Calculate market conditions
//            double spyMomentum = calculateMomentum(spyData, entryIndex, 10);
//            double qqqMomentum = calculateMomentum(qqqData, entryIndex, 10);
//            double correlation = calculateCorrelation(spyData, qqqData, entryIndex, 15);
//            double divergence = Math.abs(spyMomentum - qqqMomentum);
//
//            // Determine strategy based on AI logic
//            String strategy;
//            String signalType = "BUY";
//            boolean isCall;
//
//            if (divergence > 0.3 && correlation > 0.6) {
//                // Reverse leader-lag
//                if (spyMomentum > qqqMomentum + 0.15) {
//                    strategy = "0DTE_AI_REVERSE_LEADER_LAG_PUT";
//                    isCall = false;
//                } else if (qqqMomentum > spyMomentum + 0.15) {
//                    strategy = "0DTE_AI_REVERSE_LEADER_LAG_CALL";
//                    isCall = true;
//                } else {
//                    return null; // Skip unclear setups
//                }
//            } else if (correlation > 0.75) {
//                // Standard leader-lag
//                if (spyMomentum > 0.2) {
//                    strategy = "0DTE_AI_LEADER_LAG_CALL";
//                    isCall = true;
//                } else if (spyMomentum < -0.2) {
//                    strategy = "0DTE_AI_LEADER_LAG_PUT";
//                    isCall = false;
//                } else {
//                    return null; // Skip weak momentum
//                }
//            } else {
//                return null; // Skip low correlation periods
//            }
//
//            // Calculate confidence
//            double confidence = calculateAIConfidence(spyMomentum, qqqMomentum, correlation, divergence);
//            if (confidence < 0.60) return null; // Skip low confidence
//
//            // Simulate option pricing
//            BigDecimal underlyingPrice = qqqEntry.getPrice();
//            BigDecimal optionPrice = simulateOptionPrice(underlyingPrice, isCall, random);
//
//            // Simulate holding period (30 minutes to 3 hours)
//            int holdingMinutes = 30 + random.nextInt(150);
//            int exitIndex = Math.min(entryIndex + holdingMinutes, qqqData.size() - 1);
//
//            // Calculate exit
//            MarketData qqqExit = qqqData.get(exitIndex);
//            BigDecimal underlyingMove = qqqExit.getPrice().subtract(qqqEntry.getPrice());
//            BigDecimal movePercent = underlyingMove.divide(qqqEntry.getPrice(), 6, RoundingMode.HALF_UP);
//
//            // Option pricing simulation
//            double leverage = 8.0 + random.nextGaussian() * 3.0; // 5-15x leverage
//            leverage = Math.max(3.0, Math.min(20.0, leverage));
//
//            double directionMultiplier = isCall ? 1.0 : -1.0;
//            BigDecimal optionMove = movePercent.multiply(BigDecimal.valueOf(leverage * directionMultiplier));
//
//            BigDecimal exitPrice = optionPrice.multiply(BigDecimal.ONE.add(optionMove));
//            exitPrice = exitPrice.max(BigDecimal.valueOf(0.01)); // Minimum $0.01
//
//            BigDecimal pnl = exitPrice.subtract(optionPrice);
//            BigDecimal pnlPercent = pnl.divide(optionPrice, 4, RoundingMode.HALF_UP)
//                    .multiply(BigDecimal.valueOf(100));
//
//            // Determine exit reason
//            String exitReason = determineExitReason(pnlPercent, holdingMinutes, exitIndex == qqqData.size() - 1);
//
//            // Create signal
//            Signal signal = new Signal();
//            signal.setSymbol("QQQ");
//            signal.setOptionSymbol(generateOptionSymbol(date, underlyingPrice, isCall));
//            signal.setSignalType(signalType);
//            signal.setStrategy(strategy);
//            signal.setConfidence(confidence);
//            signal.setTimestamp(spyEntry.getTimestamp());
//            signal.setEntryPrice(optionPrice);
//            signal.setTargetPrice(optionPrice.multiply(BigDecimal.valueOf(2.0)));
//            signal.setStopLoss(optionPrice.multiply(BigDecimal.valueOf(0.70)));
//            signal.setStatus("SIMULATED");
//            signal.setMarketTrend(determineMarketTrend(qqqMomentum));
//            signal.setCreatedAt(spyEntry.getTimestamp());
//
//            // Add AI metadata
//            signal.getMetadata().put("aiStrategy", strategy.contains("REVERSE") ? "REVERSE" : "STANDARD");
//            signal.getMetadata().put("aiCorrelation", String.valueOf(correlation));
//            signal.getMetadata().put("spyMomentum", String.valueOf(spyMomentum));
//            signal.getMetadata().put("qqqMomentum", String.valueOf(qqqMomentum));
//            signal.getMetadata().put("divergence", String.valueOf(divergence));
//            signal.getMetadata().put("priority", "HIGHEST");
//            signal.getMetadata().put("signalSource", "AI_LEARNING_ENGINE");
//
//            // Create trade
//            Trade trade = new Trade();
//            trade.setSymbol("QQQ");
//            trade.setOptionSymbol(signal.getOptionSymbol());
//            trade.setStrategy(strategy);
//            trade.setQuantity(1);
//            trade.setEntryPrice(optionPrice);
//            trade.setExitPrice(exitPrice);
//            trade.setEntryTime(spyEntry.getTimestamp());
//            trade.setExitTime(qqqExit.getTimestamp());
//            trade.setStatus("SIMULATED");
//            trade.setCurrentPrice(exitPrice);
//            trade.setPnl(pnl);
//            trade.setPnlPercent(pnlPercent);
//            trade.setExitReason(exitReason);
//            trade.setMarketTrend(signal.getMarketTrend());
//            trade.setSignalConfidence(confidence);
//
//            // AI enrichment
//            trade.setBayesianProbability(confidence);
//            trade.setAdjustedConfidence(confidence);
//            trade.setSignalConfidence(confidence);
//            trade.setLearningPhase("TRAINING");
//            trade.setThresholdOptimizationMethod("SIMULATED");
//
//            return new TradeSimulationResult(trade, signal);
//
//        } catch (Exception e) {
//            log.debug("Error simulating trade: {}", e.getMessage());
//            return null;
//        }
//    }
//
//    // ================================================================================================
//    // STEP 3: TRAIN AI LEARNING SYSTEM
//    // ================================================================================================
//
//    private void trainAILearningSystem() {
//        log.info("🧠 Training AI learning system...");
//
//        // Get all simulated trades
//        List<Trade> simulatedTrades = tradeRepository.findByStatus("SIMULATED");
//        List<Signal> simulatedSignals = signalRepository.findByStatus("SIMULATED");
//
//        log.info("Training with {} trades and {} signals",
//                simulatedTrades.size(), simulatedSignals.size());
//
//        if (simulatedTrades.isEmpty()) {
//            log.warn("No simulated trades found for training");
//            return;
//        }
//
//        // Create signal-trade mapping
//        Map<String, Trade> tradeBySignalId = simulatedTrades.stream()
//                .filter(t -> t.getSignalId() != null)
//                .collect(Collectors.toMap(
//                        t -> t.getSignalId().toString(),
//                        t -> t,
//                        (existing, replacement) -> existing));
//
//        // Train AI with each signal-trade pair
//        int trainedCount = 0;
//        for (Signal signal : simulatedSignals) {
//            if (signal.getStrategy().contains("AI_LEADER_LAG")) {
//                Trade correspondingTrade = tradeBySignalId.get(signal.getId().toString());
//                if (correspondingTrade != null) {
//                    // This simulates the AI learning from historical performance
//                    zeroDTEStrategy.updateAIFromTrade(correspondingTrade, signal);
//                    trainedCount++;
//                }
//            }
//        }
//
//        log.info("✅ AI trained with {} signal-trade pairs", trainedCount);
//    }
//
//    // ================================================================================================
//    // STEP 4: INITIALIZE AI WITH TRAINING DATA
//    // ================================================================================================
//
//    private void initializeAIWithTrainingData() {
//        log.info("🚀 Initializing AI with training data...");
//
//        // Calculate performance metrics
//        List<Trade> aiTrades = tradeRepository.findByStatusAndStrategyContaining("SIMULATED", "AI_LEADER_LAG");
//
//        Map<String, List<Trade>> tradesByStrategy = aiTrades.stream()
//                .collect(Collectors.groupingBy(Trade::getStrategy));
//
//        for (Map.Entry<String, List<Trade>> entry : tradesByStrategy.entrySet()) {
//            String strategy = entry.getKey();
//            List<Trade> trades = entry.getValue();
//
//            double avgPnL = trades.stream()
//                    .mapToDouble(t -> t.getPnlPercent().doubleValue())
//                    .average().orElse(0.0);
//
//            long winCount = trades.stream()
//                    .filter(t -> t.getPnlPercent().compareTo(BigDecimal.ZERO) > 0)
//                    .count();
//
//            double winRate = trades.size() > 0 ? (double) winCount / trades.size() : 0.0;
//
//            log.info("📊 {} Performance: Avg P&L: {:.2f}%, Win Rate: {:.1f}%, Total Trades: {}",
//                    strategy, avgPnL, winRate * 100, trades.size());
//        }
//
//        log.info("✅ AI initialization complete!");
//    }
//
//    // ================================================================================================
//    // HELPER METHODS
//    // ================================================================================================
//
//    private double calculateMomentum(List<MarketData> data, int index, int lookback) {
//        if (index < lookback) return 0.0;
//
//        BigDecimal current = data.get(index).getPrice();
//        BigDecimal previous = data.get(index - lookback).getPrice();
//
//        return current.subtract(previous).divide(previous, 6, RoundingMode.HALF_UP).doubleValue();
//    }
//
//    private double calculateCorrelation(List<MarketData> spyData, List<MarketData> qqqData, int index, int window) {
//        if (index < window) return 0.5;
//
//        // Simplified correlation calculation
//        double[] spyReturns = new double[window];
//        double[] qqqReturns = new double[window];
//
//        for (int i = 0; i < window; i++) {
//            int idx = index - window + i + 1;
//            spyReturns[i] = spyData.get(idx).getPrice().subtract(spyData.get(idx-1).getPrice())
//                    .divide(spyData.get(idx-1).getPrice(), 6, RoundingMode.HALF_UP).doubleValue();
//            qqqReturns[i] = qqqData.get(idx).getPrice().subtract(qqqData.get(idx-1).getPrice())
//                    .divide(qqqData.get(idx-1).getPrice(), 6, RoundingMode.HALF_UP).doubleValue();
//        }
//
//        return calculatePearsonCorrelation(spyReturns, qqqReturns);
//    }
//
//    private double calculatePearsonCorrelation(double[] x, double[] y) {
//        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
//        int n = x.length;
//
//        for (int i = 0; i < n; i++) {
//            sumX += x[i];
//            sumY += y[i];
//            sumXY += x[i] * y[i];
//            sumX2 += x[i] * x[i];
//            sumY2 += y[i] * y[i];
//        }
//
//        double numerator = n * sumXY - sumX * sumY;
//        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
//
//        return denominator != 0 ? Math.abs(numerator / denominator) : 0.5;
//    }
//
//    private double calculateAIConfidence(double spyMomentum, double qqqMomentum, double correlation, double divergence) {
//        double confidence = 0.60; // Base
//
//        if (correlation > 0.8) confidence += 0.15;
//        else if (correlation > 0.7) confidence += 0.10;
//
//        if (Math.abs(spyMomentum) > 0.4) confidence += 0.10;
//        if (divergence > 0.3) confidence += 0.10;
//
//        return Math.min(0.95, confidence);
//    }
//
//    private BigDecimal simulateOptionPrice(BigDecimal underlyingPrice, boolean isCall, Random random) {
//        // Simplified option pricing
//        double basePrice = 1.0 + random.nextGaussian() * 0.5;
//        basePrice = Math.max(0.10, Math.min(5.0, basePrice));
//
//        return BigDecimal.valueOf(basePrice).setScale(2, RoundingMode.HALF_UP);
//    }
//
//    private String determineExitReason(BigDecimal pnlPercent, int holdingMinutes, boolean isEndOfDay) {
//        if (isEndOfDay) return "END_OF_DAY";
//        if (pnlPercent.compareTo(BigDecimal.valueOf(100)) > 0) return "TAKE_PROFIT";
//        if (pnlPercent.compareTo(BigDecimal.valueOf(-30)) < 0) return "STOP_LOSS";
//        if (holdingMinutes > 120) return "TIME_DECAY";
//        return "MANUAL_CLOSE";
//    }
//
//    private String determineMarketTrend(double qqqMomentum) {
//        if (qqqMomentum > 0.2) return "UP";
//        if (qqqMomentum < -0.2) return "DOWN";
//        return "NEUTRAL";
//    }
//
//    private String generateOptionSymbol(LocalDate date, BigDecimal price, boolean isCall) {
//        String dateStr = date.format(DateTimeFormatter.ofPattern("yyMMdd"));
//        String type = isCall ? "C" : "P";
//        String strike = String.format("%08d", price.multiply(BigDecimal.valueOf(1000)).intValue());
//        return "QQQ" + dateStr + type + strike;
//    }
//
//    private BigDecimal calculateSimpleVWAP(HistoricalBar bar) {
//        return bar.getHigh().add(bar.getLow()).add(bar.getClose())
//                .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
//    }
//
//    private LocalDateTime convertToLocalDateTime(Object timestamp) {
//        // Convert various timestamp formats to LocalDateTime
//        if (timestamp instanceof Long) {
//            return LocalDateTime.ofEpochSecond((Long) timestamp, 0, ZoneOffset.UTC);
//        } else if (timestamp instanceof String) {
//            return LocalDateTime.parse((String) timestamp);
//        }
//        return LocalDateTime.now();
//    }
//
//    // ================================================================================================
//    // STATUS AND CONTROL METHODS
//    // ================================================================================================
//
//    public boolean isTraining() {
//        return isTraining;
//    }
//
//    public String getTrainingStatus() {
//        return trainingStatus;
//    }
//
//    public int getTrainingProgress() {
//        return trainingProgress;
//    }
//
//    public Map<String, Object> getTrainingStats() {
//        Map<String, Object> stats = new HashMap<>();
//
//        try {
//            long marketDataCount = marketDataRepository.count();
//            long simulatedTradeCount = tradeRepository.countByStatus("SIMULATED");
//            long simulatedSignalCount = signalRepository.countByStatus("SIMULATED");
//
//            stats.put("marketDataPoints", marketDataCount);
//            stats.put("simulatedTrades", simulatedTradeCount);
//            stats.put("simulatedSignals", simulatedSignalCount);
//            stats.put("trainingStatus", trainingStatus);
//            stats.put("trainingProgress", trainingProgress);
//            stats.put("isTraining", isTraining);
//
//            boolean isReady = marketDataCount > 1000 && simulatedTradeCount > 50;
//            stats.put("aiReady", isReady);
//
//        } catch (Exception e) {
//            stats.put("error", e.getMessage());
//        }
//
//        return stats;
//    }
//
//    // ================================================================================================
//    // SUPPORTING CLASSES
//    // ================================================================================================
//
//    private static class TradeSimulationResult {
//        final Trade trade;
//        final Signal signal;
//
//        TradeSimulationResult(Trade trade, Signal signal) {
//            this.trade = trade;
//            this.signal = signal;
//
//            // Link them
//            if (signal.getId() != null) {
//                trade.setSignalId(signal.getId());
//            }
//        }
//    }
//}