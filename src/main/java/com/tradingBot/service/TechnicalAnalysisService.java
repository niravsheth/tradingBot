package com.tradingBot.service;

import com.tradingBot.entity.MarketData;
import com.tradingBot.service.TechnicalAnalysis;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.model.Quote;
import com.tradingBot.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TechnicalAnalysisService {

    private final MarketDataRepository marketDataRepository;
    private final TradierService tradierService;
    private final BarAggregationService barAggregationService;

    private static final int DEFAULT_PERIOD = 14; // For RSI, ATR
    private static final int BOLLINGER_PERIOD = 20;
    private static final double BOLLINGER_STD_DEV = 2.0;
    private static final int MIN_BARS_REQUIRED = 20;

    /**
     * Main analysis method with comprehensive NULL handling
     */
    public TechnicalAnalysis analyze(String symbol) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[TA][{}] Starting technical analysis for {}", analysisId, symbol);

        try {
            // Get market data with fallback mechanisms
            List<MarketData> marketData = getValidMarketData(symbol, analysisId);

            if (marketData == null || marketData.isEmpty()) {
                log.error("[TA][{}] No valid market data available for {}", analysisId, symbol);
                return createFallbackAnalysis(symbol, analysisId);
            }

            // Create technical analysis object
            TechnicalAnalysis ta = new TechnicalAnalysis();
            ta.setSymbol(symbol);
            ta.setTimestamp(LocalDateTime.now());

            // Get current price from most recent bar or quote
            BigDecimal currentPrice = getCurrentPrice(marketData, symbol);
            if (currentPrice == null) {
                log.error("[TA][{}] Cannot determine current price for {}", analysisId, symbol);
                return createFallbackAnalysis(symbol, analysisId);
            }
            ta.setCurrentPrice(currentPrice);

            // Calculate indicators with NULL safety
            calculateVWAP(ta, marketData, analysisId);
            calculateRSI(ta, marketData, analysisId);
            calculateMovingAverages(ta, marketData, analysisId);
            calculateBollingerBands(ta, marketData, analysisId);
            calculateATR(ta, marketData, analysisId);
            calculateVolatility(ta, marketData, analysisId);
            calculateVolumeMetrics(ta, marketData, analysisId);
            calculateMomentum(ta, marketData, analysisId);
            determineTrend(ta, marketData, analysisId);

            // Validate the analysis
            if (!validateAnalysis(ta, analysisId)) {
                log.warn("[TA][{}] Analysis validation failed, using fallback", analysisId);
                return enhanceWithFallbackData(ta, symbol, analysisId);
            }

            log.info("[TA][{}] Analysis complete - Price: ${}, VWAP: ${}, RSI: {}",
                    analysisId, ta.getCurrentPrice(), ta.getVwap(), ta.getRsi());

            return ta;

        } catch (Exception e) {
            log.error("[TA][{}] Error analyzing {}: {}", analysisId, symbol, e.getMessage(), e);
            return createFallbackAnalysis(symbol, analysisId);
        }
    }

    /**
     * Get valid market data with filtering and fallback
     */
    private List<MarketData> getValidMarketData(String symbol, String analysisId) {
        try {
            // Try to get data from BarAggregationService first
            List<MarketData> bars = barAggregationService.getRecentBars(symbol, 50);

            // Filter out invalid bars
            List<MarketData> validBars = bars.stream()
                    .filter(this::isValidBar)
                    .sorted(Comparator.comparing(MarketData::getTimestamp))
                    .collect(Collectors.toList());

            int invalidCount = bars.size() - validBars.size();
            if (invalidCount > 0) {
                log.warn("[TA][{}] Filtered out {} bars with NULL fields for {}",
                        analysisId, invalidCount, symbol);
            }

            if (validBars.size() < MIN_BARS_REQUIRED) {
                log.warn("[TA][{}] Only {} valid bars found (need {}), fetching from repository",
                        analysisId, validBars.size(), MIN_BARS_REQUIRED);

                // Try to get more data from repository
                List<MarketData> repoData = marketDataRepository.findRecentData(symbol, 100);
                validBars = repoData.stream()
                        .filter(this::isValidBar)
                        .sorted(Comparator.comparing(MarketData::getTimestamp))
                        .limit(50)
                        .collect(Collectors.toList());
            }

            return validBars;

        } catch (Exception e) {
            log.error("[TA][{}] Error getting market data: {}", analysisId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Validate that a bar has all required fields
     */
    private boolean isValidBar(MarketData bar) {
        return bar != null &&
                bar.getOpen() != null &&
                bar.getHigh() != null &&
                bar.getLow() != null &&
                bar.getClose() != null &&
                bar.getVolume() != null &&
                bar.getTimestamp() != null &&
                bar.getClose().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Get current price from bars or quote service
     */
    private BigDecimal getCurrentPrice(List<MarketData> marketData, String symbol) {
        // Try to get from most recent bar
        if (!marketData.isEmpty()) {
            MarketData lastBar = marketData.get(marketData.size() - 1);
            if (lastBar.getClose() != null && lastBar.getClose().compareTo(BigDecimal.ZERO) > 0) {
                return lastBar.getClose();
            }
        }

        // Fallback to quote service
        try {
            QuoteResponse quoteResponse = tradierService.getQuote(symbol);
            if (quoteResponse != null && quoteResponse.getQuote() != null) {
                Quote quote = quoteResponse.getQuote();
                if (quote.getLast() != null) {
                    return quote.getLast();
                }
            }
        } catch (Exception e) {
            log.error("Error getting quote for {}: {}", symbol, e.getMessage());
        }

        return null;
    }

    /**
     * Calculate VWAP with NULL safety
     */
    private void calculateVWAP(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            BigDecimal totalPriceVolume = BigDecimal.ZERO;
            BigDecimal totalVolume = BigDecimal.ZERO;

            for (MarketData bar : marketData) {
                if (bar.getClose() != null && bar.getVolume() != null && bar.getVolume() > 0) {

                    // Use typical price (H+L+C)/3 or just close if OHLC incomplete
                    BigDecimal typicalPrice;
                    if (bar.getHigh() != null && bar.getLow() != null) {
                        typicalPrice = bar.getHigh().add(bar.getLow()).add(bar.getClose())
                                .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
                    } else {
                        typicalPrice = bar.getClose();
                    }

                    // Convert Long volume to BigDecimal for calculation
                    BigDecimal volumeBD = BigDecimal.valueOf(bar.getVolume());
                    totalPriceVolume = totalPriceVolume.add(typicalPrice.multiply(volumeBD));
                    totalVolume = totalVolume.add(volumeBD);
                }
            }

            if (totalVolume.compareTo(BigDecimal.ZERO) > 0) {
                ta.setVwap(totalPriceVolume.divide(totalVolume, 2, RoundingMode.HALF_UP));
                log.debug("[TA][{}] VWAP calculated: {}", analysisId, ta.getVwap());
            } else {
                // Use current price as VWAP fallback
                ta.setVwap(ta.getCurrentPrice());
                log.warn("[TA][{}] No volume data for VWAP, using current price", analysisId);
            }

        } catch (Exception e) {
            log.error("[TA][{}] Error calculating VWAP: {}", analysisId, e.getMessage());
            ta.setVwap(ta.getCurrentPrice());
        }
    }

    /**
     * Calculate RSI with NULL safety - sets primitive double
     */
    private void calculateRSI(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            if (marketData.size() < DEFAULT_PERIOD + 1) {
                ta.setRsi(50.0); // Neutral RSI as fallback
                log.warn("[TA][{}] Insufficient data for RSI, using neutral value", analysisId);
                return;
            }

            List<BigDecimal> gains = new ArrayList<>();
            List<BigDecimal> losses = new ArrayList<>();

            for (int i = 1; i < marketData.size(); i++) {
                BigDecimal prevClose = marketData.get(i - 1).getClose();
                BigDecimal currClose = marketData.get(i).getClose();

                if (prevClose != null && currClose != null) {
                    BigDecimal change = currClose.subtract(prevClose);
                    if (change.compareTo(BigDecimal.ZERO) > 0) {
                        gains.add(change);
                        losses.add(BigDecimal.ZERO);
                    } else {
                        gains.add(BigDecimal.ZERO);
                        losses.add(change.abs());
                    }
                }
            }

            if (gains.size() >= DEFAULT_PERIOD) {
                // Calculate average gain and loss
                BigDecimal avgGain = gains.subList(gains.size() - DEFAULT_PERIOD, gains.size())
                        .stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(DEFAULT_PERIOD), 4, RoundingMode.HALF_UP);

                BigDecimal avgLoss = losses.subList(losses.size() - DEFAULT_PERIOD, losses.size())
                        .stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(DEFAULT_PERIOD), 4, RoundingMode.HALF_UP);

                double rsi;
                if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
                    rsi = 100.0;
                } else {
                    BigDecimal rs = avgGain.divide(avgLoss, 4, RoundingMode.HALF_UP);
                    rsi = 100.0 - (100.0 / (1.0 + rs.doubleValue()));
                }

                ta.setRsi(Math.round(rsi * 100.0) / 100.0);
                log.debug("[TA][{}] RSI calculated: {}", analysisId, ta.getRsi());
            } else {
                ta.setRsi(50.0);
                log.warn("[TA][{}] Insufficient valid data for RSI", analysisId);
            }

        } catch (Exception e) {
            log.error("[TA][{}] Error calculating RSI: {}", analysisId, e.getMessage());
            ta.setRsi(50.0);
        }
    }

    /**
     * Calculate volume metrics with NULL safety
     */
    private void calculateVolumeMetrics(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            if (marketData.isEmpty()) {
                log.error("[TA][{}] No data for volume metrics", analysisId);
                ta.setVolumeRatio(1.0);
                ta.setAverageVolume(BigDecimal.valueOf(100000));
                ta.setCurrentVolume(100000L);
                return;
            }

            String symbol = ta.getSymbol();
            Long currentIncrementalVolume = null;
            boolean usingStaleData = false;

            // STEP 1: Try to get real-time volume from active accumulator
            if (barAggregationService.hasActiveAccumulator(symbol)) {
                currentIncrementalVolume = barAggregationService.getCurrentIncrementalVolume(symbol);
                if (currentIncrementalVolume != null && currentIncrementalVolume > 0) {
                    log.debug("[TA][{}] Using real-time incremental volume from accumulator: {}",
                            analysisId, currentIncrementalVolume);
                }
            }

            // STEP 2: Fallback to last finalized bar if accumulator unavailable
            if (currentIncrementalVolume == null || currentIncrementalVolume <= 0) {
                MarketData currentBar = marketData.get(marketData.size() - 1);
                currentIncrementalVolume = currentBar.getIncrementalVolume();

                if (currentIncrementalVolume == null || currentIncrementalVolume <= 0) {
                    // Try to calculate from cumulative volumes
                    if (marketData.size() >= 2 && currentBar.getVolume() != null) {
                        MarketData previousBar = marketData.get(marketData.size() - 2);
                        if (previousBar.getVolume() != null) {
                            currentIncrementalVolume = Math.max(0L, currentBar.getVolume() - previousBar.getVolume());
                            log.debug("[TA][{}] Calculated incremental volume from cumulative: {}",
                                    analysisId, currentIncrementalVolume);
                        }
                    }
                }

                if (currentIncrementalVolume == null || currentIncrementalVolume <= 0) {
                    log.error("[TA][{}] Cannot determine current incremental volume for {}", analysisId, symbol);
                    ta.setVolumeRatio(0.1); // Set very low to fail volume checks
                    ta.setAverageVolume(BigDecimal.valueOf(100000));
                    ta.setCurrentVolume(0L);
                    return;
                }

                usingStaleData = true;
            }

            // STEP 3: Calculate average from last 3 finalized bars
            int barsForAverage = Math.min(3, marketData.size() - 1);
            if (barsForAverage < 1) {
                log.warn("[TA][{}] Insufficient bars for average calculation", analysisId);
                ta.setVolumeRatio(1.0);
                ta.setCurrentVolume(currentIncrementalVolume);
                ta.setAverageVolume(BigDecimal.valueOf(currentIncrementalVolume));
                return;
            }

            List<Long> validIncrementalVolumes = new ArrayList<>();
            for (int i = marketData.size() - 1 - barsForAverage; i < marketData.size() - 1; i++) {
                if (i < 0) continue;
                MarketData bar = marketData.get(i);
                Long incVol = bar.getIncrementalVolume();

                if (incVol != null && incVol > 0) {
                    validIncrementalVolumes.add(incVol);
                } else if (i < marketData.size() - 2) {
                    // Try to calculate from cumulative
                    MarketData nextBar = marketData.get(i + 1);
                    if (bar.getVolume() != null && nextBar.getVolume() != null) {
                        Long calculated = Math.max(0L, nextBar.getVolume() - bar.getVolume());
                        if (calculated > 0) {
                            validIncrementalVolumes.add(calculated);
                        }
                    }
                }
            }

            if (validIncrementalVolumes.isEmpty()) {
                log.error("[TA][{}] No valid historical volume data for average calculation", analysisId);
                ta.setVolumeRatio(0.1); // Set very low to fail volume checks
                ta.setAverageVolume(BigDecimal.valueOf(100000));
                ta.setCurrentVolume(currentIncrementalVolume);
                return;
            }

            // Calculate simple average of last 3 bars
            double averageIncrementalVolume = validIncrementalVolumes.stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(currentIncrementalVolume.doubleValue());

            if (averageIncrementalVolume <= 0) {
                log.error("[TA][{}] Invalid average volume: {}", analysisId, averageIncrementalVolume);
                ta.setVolumeRatio(0.1);
                ta.setCurrentVolume(currentIncrementalVolume);
                ta.setAverageVolume(BigDecimal.valueOf(100000));
                return;
            }

            // STEP 4: Calculate ratio
            double volumeRatio = currentIncrementalVolume / averageIncrementalVolume;

            // Validate ratio is reasonable (cap at 10x for safety)
            if (volumeRatio > 10.0) {
                log.warn("[TA][{}] Volume ratio capped at 10x: {} (current: {}, average: {})",
                        analysisId, volumeRatio, currentIncrementalVolume, averageIncrementalVolume);
                volumeRatio = 10.0;
            }

            ta.setVolumeRatio(volumeRatio);
            ta.setCurrentVolume(currentIncrementalVolume);
            ta.setAverageVolume(BigDecimal.valueOf((long) averageIncrementalVolume));

            // Log warning if using stale data
            if (usingStaleData) {
                log.warn("[TA][{}] Using stale volume data from finalized bar - accumulator unavailable. Current: {}, Average: {}, Ratio: {}x",
                        analysisId, currentIncrementalVolume, (long) averageIncrementalVolume,
                        String.format("%.2f", volumeRatio));
            } else {
                log.debug("[TA][{}] Volume metrics - Current: {}, Average: {}, Ratio: {}x",
                        analysisId, currentIncrementalVolume, (long) averageIncrementalVolume,
                        String.format("%.2f", volumeRatio));
            }

        } catch (Exception e) {
            log.error("[TA][{}] Error calculating volume metrics: {}", analysisId, e.getMessage(), e);
            ta.setVolumeRatio(0.1); // Set very low to fail volume checks
            ta.setAverageVolume(BigDecimal.valueOf(100000));
            ta.setCurrentVolume(0L);
        }
    }    /**
     * Calculate moving averages with NULL safety
     */
    private void calculateMovingAverages(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            // Calculate 20-period SMA
            if (marketData.size() >= 20) {
                BigDecimal sum = marketData.subList(marketData.size() - 20, marketData.size())
                        .stream()
                        .map(MarketData::getClose)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                long validCount = marketData.subList(marketData.size() - 20, marketData.size())
                        .stream()
                        .map(MarketData::getClose)
                        .filter(Objects::nonNull)
                        .count();

                if (validCount > 0) {
                    ta.setSma20(sum.divide(BigDecimal.valueOf(validCount), 2, RoundingMode.HALF_UP));
                }
            }

            // Calculate 50-period SMA
            if (marketData.size() >= 50) {
                BigDecimal sum = marketData.stream()
                        .map(MarketData::getClose)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                long validCount = marketData.stream()
                        .map(MarketData::getClose)
                        .filter(Objects::nonNull)
                        .count();

                if (validCount > 0) {
                    ta.setSma50(sum.divide(BigDecimal.valueOf(validCount), 2, RoundingMode.HALF_UP));
                }
            }

            // Use current price as fallback for missing SMAs
            if (ta.getSma20() == null) {
                ta.setSma20(ta.getCurrentPrice());
            }
            if (ta.getSma50() == null) {
                ta.setSma50(ta.getCurrentPrice());
            }

        } catch (Exception e) {
            log.error("[TA][{}] Error calculating moving averages: {}", analysisId, e.getMessage());
            ta.setSma20(ta.getCurrentPrice());
            ta.setSma50(ta.getCurrentPrice());
        }
    }

    /**
     * Calculate Bollinger Bands with NULL safety
     */
    private void calculateBollingerBands(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            if (marketData.size() < BOLLINGER_PERIOD) {
                // Use current price +/- 0.5% as fallback
                BigDecimal offset = ta.getCurrentPrice().multiply(new BigDecimal("0.005"));
                ta.setBollingerUpper(ta.getCurrentPrice().add(offset));
                ta.setBollingerMiddle(ta.getCurrentPrice());
                ta.setBollingerLower(ta.getCurrentPrice().subtract(offset));
                return;
            }

            List<BigDecimal> recentPrices = marketData
                    .subList(marketData.size() - BOLLINGER_PERIOD, marketData.size())
                    .stream()
                    .map(MarketData::getClose)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (recentPrices.size() < BOLLINGER_PERIOD / 2) {
                // Not enough valid data
                BigDecimal offset = ta.getCurrentPrice().multiply(new BigDecimal("0.005"));
                ta.setBollingerUpper(ta.getCurrentPrice().add(offset));
                ta.setBollingerMiddle(ta.getCurrentPrice());
                ta.setBollingerLower(ta.getCurrentPrice().subtract(offset));
                return;
            }

            // Calculate SMA for middle band
            BigDecimal sma = recentPrices.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(recentPrices.size()), 4, RoundingMode.HALF_UP);

            // Calculate standard deviation
            double variance = recentPrices.stream()
                    .mapToDouble(price -> Math.pow(price.subtract(sma).doubleValue(), 2))
                    .average()
                    .orElse(0.0);

            BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance));
            BigDecimal bandWidth = stdDev.multiply(BigDecimal.valueOf(BOLLINGER_STD_DEV));

            ta.setBollingerMiddle(sma);
            ta.setBollingerUpper(sma.add(bandWidth));
            ta.setBollingerLower(sma.subtract(bandWidth));

        } catch (Exception e) {
            log.error("[TA][{}] Error calculating Bollinger Bands: {}", analysisId, e.getMessage());
            BigDecimal offset = ta.getCurrentPrice().multiply(new BigDecimal("0.005"));
            ta.setBollingerUpper(ta.getCurrentPrice().add(offset));
            ta.setBollingerMiddle(ta.getCurrentPrice());
            ta.setBollingerLower(ta.getCurrentPrice().subtract(offset));
        }
    }

    /**
     * Calculate ATR with NULL safety - NOTE: Sets averageTrueRange, not atr
     */
    private void calculateATR(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            if (marketData.size() < DEFAULT_PERIOD + 1) {
                ta.setAverageTrueRange(ta.getCurrentPrice().multiply(new BigDecimal("0.01"))); // 1% as fallback
                return;
            }

            List<BigDecimal> trueRanges = new ArrayList<>();

            for (int i = 1; i < marketData.size(); i++) {
                MarketData current = marketData.get(i);
                MarketData previous = marketData.get(i - 1);

                if (current.getHigh() != null && current.getLow() != null &&
                        current.getClose() != null && previous.getClose() != null) {

                    BigDecimal highLow = current.getHigh().subtract(current.getLow());
                    BigDecimal highPrevClose = current.getHigh().subtract(previous.getClose()).abs();
                    BigDecimal lowPrevClose = current.getLow().subtract(previous.getClose()).abs();

                    BigDecimal trueRange = highLow.max(highPrevClose).max(lowPrevClose);
                    trueRanges.add(trueRange);
                }
            }

            if (trueRanges.size() >= DEFAULT_PERIOD) {
                BigDecimal atr = trueRanges.subList(trueRanges.size() - DEFAULT_PERIOD, trueRanges.size())
                        .stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(DEFAULT_PERIOD), 4, RoundingMode.HALF_UP);

                ta.setAverageTrueRange(atr); // Use correct method name
            } else {
                ta.setAverageTrueRange(ta.getCurrentPrice().multiply(new BigDecimal("0.01")));
            }

        } catch (Exception e) {
            log.error("[TA][{}] Error calculating ATR: {}", analysisId, e.getMessage());
            ta.setAverageTrueRange(ta.getCurrentPrice().multiply(new BigDecimal("0.01")));
        }
    }

    /**
     * Calculate volatility with NULL safety - Sets both currentVolatility and volatilityPercentile
     */
    private void calculateVolatility(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            if (marketData.size() < 20) {
                BigDecimal defaultVol = ta.getCurrentPrice().multiply(new BigDecimal("0.02"));
                ta.setCurrentVolatility(defaultVol);
                ta.setAverageVolatility(defaultVol);
                ta.setVolatilityPercentile(50.0); // Mid-range percentile
                return;
            }

            List<Double> returns = new ArrayList<>();
            for (int i = 1; i < marketData.size(); i++) {
                BigDecimal prevClose = marketData.get(i - 1).getClose();
                BigDecimal currClose = marketData.get(i).getClose();

                if (prevClose != null && currClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
                    double dailyReturn = currClose.subtract(prevClose)
                            .divide(prevClose, 6, RoundingMode.HALF_UP)
                            .doubleValue();
                    returns.add(dailyReturn);
                }
            }

            if (returns.size() >= 10) {
                double avgReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double variance = returns.stream()
                        .mapToDouble(r -> Math.pow(r - avgReturn, 2))
                        .average()
                        .orElse(0.0);

                double stdDev = Math.sqrt(variance);

                // Set current volatility as price * standard deviation
                BigDecimal currentVol = ta.getCurrentPrice().multiply(BigDecimal.valueOf(stdDev));
                ta.setCurrentVolatility(currentVol);

                // Calculate average volatility (use same as current for now)
                ta.setAverageVolatility(currentVol);

                // Calculate volatility percentile (simplified - assumes normal distribution)
                // Map stdDev to percentile (0.01 = 20th percentile, 0.02 = 50th, 0.03 = 80th)
                double percentile = Math.min(100, Math.max(0, stdDev * 2500));
                ta.setVolatilityPercentile(percentile);

            } else {
                BigDecimal defaultVol = ta.getCurrentPrice().multiply(new BigDecimal("0.02"));
                ta.setCurrentVolatility(defaultVol);
                ta.setAverageVolatility(defaultVol);
                ta.setVolatilityPercentile(50.0);
            }

        } catch (Exception e) {
            log.error("[TA][{}] Error calculating volatility: {}", analysisId, e.getMessage());
            BigDecimal defaultVol = ta.getCurrentPrice().multiply(new BigDecimal("0.02"));
            ta.setCurrentVolatility(defaultVol);
            ta.setAverageVolatility(defaultVol);
            ta.setVolatilityPercentile(50.0);
        }
    }

    /**
     * Calculate momentum with NULL safety - sets primitive double
     */
    private void calculateMomentum(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            if (marketData.size() < 10) {
                ta.setMomentumStrength(0.5); // Neutral momentum
                return;
            }

            // Calculate rate of change over last 10 periods
            BigDecimal oldPrice = marketData.get(marketData.size() - 10).getClose();
            BigDecimal currentPrice = marketData.get(marketData.size() - 1).getClose();

            if (oldPrice != null && currentPrice != null && oldPrice.compareTo(BigDecimal.ZERO) > 0) {
                double roc = currentPrice.subtract(oldPrice)
                        .divide(oldPrice, 4, RoundingMode.HALF_UP)
                        .doubleValue();

                // Normalize to 0-1 range (assuming +/-5% is extreme)
                double momentum = Math.max(0, Math.min(1, (roc + 0.05) / 0.10));
                ta.setMomentumStrength(momentum);
            } else {
                ta.setMomentumStrength(0.5);
            }

        } catch (Exception e) {
            log.error("[TA][{}] Error calculating momentum: {}", analysisId, e.getMessage());
            ta.setMomentumStrength(0.5);
        }
    }

    /**
     * Determine trend with NULL safety
     */
    private void determineTrend(TechnicalAnalysis ta, List<MarketData> marketData, String analysisId) {
        try {
            // Default trend
            ta.setTrend("NEUTRAL");
            ta.setStrength(0.5);

            if (marketData.size() < 20) {
                return;
            }

            BigDecimal currentPrice = ta.getCurrentPrice();
            int upCount = 0;
            int downCount = 0;

            // Count higher highs and higher lows
            for (int i = marketData.size() - 10; i < marketData.size() - 1; i++) {
                MarketData current = marketData.get(i);
                MarketData next = marketData.get(i + 1);

                if (current.getHigh() != null && next.getHigh() != null &&
                        current.getLow() != null && next.getLow() != null) {

                    if (next.getHigh().compareTo(current.getHigh()) > 0 &&
                            next.getLow().compareTo(current.getLow()) > 0) {
                        upCount++;
                    } else if (next.getHigh().compareTo(current.getHigh()) < 0 &&
                            next.getLow().compareTo(current.getLow()) < 0) {
                        downCount++;
                    }
                }
            }

            // Determine trend based on price action and moving averages
            if (ta.getSma20() != null && ta.getSma50() != null) {
                boolean aboveSMA20 = currentPrice.compareTo(ta.getSma20()) > 0;
                boolean aboveSMA50 = currentPrice.compareTo(ta.getSma50()) > 0;
                boolean sma20AboveSMA50 = ta.getSma20().compareTo(ta.getSma50()) > 0;

                if (aboveSMA20 && aboveSMA50 && sma20AboveSMA50 && upCount > downCount) {
                    ta.setTrend("UP");
                    ta.setStrength(Math.min(1.0, 0.5 + (upCount - downCount) * 0.1));
                } else if (!aboveSMA20 && !aboveSMA50 && !sma20AboveSMA50 && downCount > upCount) {
                    ta.setTrend("DOWN");
                    ta.setStrength(Math.min(1.0, 0.5 + (downCount - upCount) * 0.1));
                } else {
                    ta.setTrend("NEUTRAL");
                    ta.setStrength(0.5);
                }
            }

        } catch (Exception e) {
            log.error("[TA][{}] Error determining trend: {}", analysisId, e.getMessage());
            ta.setTrend("NEUTRAL");
            ta.setStrength(0.5);
        }
    }

    /**
     * Validate the technical analysis has minimum required fields
     * Note: Since RSI and volumeRatio are primitives, they always have values (can't be null)
     */
    private boolean validateAnalysis(TechnicalAnalysis ta, String analysisId) {
        boolean valid = ta.getCurrentPrice() != null &&
                ta.getVwap() != null;

        // RSI and volumeRatio are primitives, so check for default/invalid values
        if (ta.getRsi() <= 0 || ta.getRsi() > 100) {
            log.warn("[TA][{}] Invalid RSI value: {}", analysisId, ta.getRsi());
            valid = false;
        }

        if (ta.getVolumeRatio() <= 0) {
            log.warn("[TA][{}] Invalid volume ratio: {}", analysisId, ta.getVolumeRatio());
            valid = false;
        }

        if (!valid) {
            log.warn("[TA][{}] Analysis validation failed", analysisId);
        }

        return valid;
    }

    /**
     * Create a basic fallback analysis when data is unavailable
     */
    private TechnicalAnalysis createFallbackAnalysis(String symbol, String analysisId) {
        log.warn("[TA][{}] Creating fallback analysis for {}", analysisId, symbol);

        TechnicalAnalysis ta = new TechnicalAnalysis();
        ta.setSymbol(symbol);
        ta.setTimestamp(LocalDateTime.now());

        try {
            // Try to get current price from quote
            QuoteResponse quoteResponse = tradierService.getQuote(symbol);
            if (quoteResponse != null && quoteResponse.getQuote() != null) {
                Quote quote = quoteResponse.getQuote();
                BigDecimal price = quote.getLast();

                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    ta.setCurrentPrice(price);
                    ta.setVwap(price);
                    ta.setSma20(price);
                    ta.setSma50(price);
                    ta.setBollingerMiddle(price);
                    ta.setBollingerUpper(price.multiply(new BigDecimal("1.005")));
                    ta.setBollingerLower(price.multiply(new BigDecimal("0.995")));
                    ta.setAverageTrueRange(price.multiply(new BigDecimal("0.01")));
                    ta.setAverageVolume(BigDecimal.valueOf(100000));
                    ta.setCurrentVolatility(price.multiply(new BigDecimal("0.02")));
                    ta.setAverageVolatility(price.multiply(new BigDecimal("0.02")));
                }
            }
        } catch (Exception e) {
            log.error("[TA][{}] Error creating fallback analysis: {}", analysisId, e.getMessage());
        }

        // Set neutral/default values for primitives
        ta.setRsi(50.0);
        ta.setVolatilityPercentile(50.0);
        ta.setVolumeRatio(1.0);
        ta.setMomentumStrength(0.5);
        ta.setTrend("NEUTRAL");
        ta.setStrength(0.5);
        ta.setCurrentVolume(10000L);

        return ta;
    }

    /**
     * Enhance partial analysis with fallback data
     */
    private TechnicalAnalysis enhanceWithFallbackData(TechnicalAnalysis ta, String symbol, String analysisId) {
        // Fill in any missing fields with reasonable defaults
        if (ta.getVwap() == null) {
            ta.setVwap(ta.getCurrentPrice());
        }

        // For primitive doubles, check if they have invalid/default values
        if (ta.getRsi() <= 0 || ta.getRsi() > 100) {
            ta.setRsi(50.0);
        }

        if (ta.getVolumeRatio() <= 0) {
            ta.setVolumeRatio(1.0);
        }

        if (ta.getVolatilityPercentile() <= 0) {
            ta.setVolatilityPercentile(50.0);
        }

        if (ta.getMomentumStrength() < 0 || ta.getMomentumStrength() > 1) {
            ta.setMomentumStrength(0.5);
        }

        if (ta.getTrend() == null) {
            ta.setTrend("NEUTRAL");
        }

        if (ta.getStrength() < 0 || ta.getStrength() > 1) {
            ta.setStrength(0.5);
        }

        if (ta.getSma20() == null) {
            ta.setSma20(ta.getCurrentPrice());
        }

        if (ta.getSma50() == null) {
            ta.setSma50(ta.getCurrentPrice());
        }

        if (ta.getBollingerMiddle() == null) {
            ta.setBollingerMiddle(ta.getCurrentPrice());
            ta.setBollingerUpper(ta.getCurrentPrice().multiply(new BigDecimal("1.005")));
            ta.setBollingerLower(ta.getCurrentPrice().multiply(new BigDecimal("0.995")));
        }

        if (ta.getAverageTrueRange() == null) {
            ta.setAverageTrueRange(ta.getCurrentPrice().multiply(new BigDecimal("0.01")));
        }

        if (ta.getAverageVolume() == null) {
            ta.setAverageVolume(BigDecimal.valueOf(100000));
        }

        if (ta.getCurrentVolatility() == null) {
            ta.setCurrentVolatility(ta.getCurrentPrice().multiply(new BigDecimal("0.02")));
        }

        if (ta.getAverageVolatility() == null) {
            ta.setAverageVolatility(ta.getCurrentPrice().multiply(new BigDecimal("0.02")));
        }

        if (ta.getCurrentVolume() <= 0) {
            ta.setCurrentVolume(10000L);
        }

        log.info("[TA][{}] Enhanced analysis with fallback data", analysisId);
        return ta;
    }
}