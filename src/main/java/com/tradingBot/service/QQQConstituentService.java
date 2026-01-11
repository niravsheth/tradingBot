package com.tradingBot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QQQ Constituent Service for tracking QQQ ETF composition and weights
 * Critical for 0DTE QQQ options as constituent moves drive ETF performance
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QQQConstituentService {

    private final MarketDataService marketDataService;

    // Cache for constituent data
    private final Map<String, Double> constituentWeights = new ConcurrentHashMap<>();
    private final Map<String, Double> constituentMomentum = new ConcurrentHashMap<>();

    /**
     * QQQ Top Holdings with approximate weights (updated periodically in production)
     */
    private static final Map<String, Double> QQQ_TOP_HOLDINGS = Map.of(
            "AAPL", 0.081,   // Apple Inc. ~8.1%
            "MSFT", 0.074,   // Microsoft Corp. ~7.4%
            "NVDA", 0.064,   // NVIDIA Corp. ~6.4%
            "AMZN", 0.051,   // Amazon.com Inc. ~5.1%
            "GOOGL", 0.042,  // Alphabet Inc. Class A ~4.2%
            "META", 0.041,   // Meta Platforms Inc. ~4.1%
            "TSLA", 0.032,   // Tesla Inc. ~3.2%
            "GOOG", 0.031,   // Alphabet Inc. Class C ~3.1%
            "AVGO", 0.025,   // Broadcom Inc. ~2.5%
            "COST", 0.022    // Costco Wholesale Corp. ~2.2%
    );

    /**
     * Get constituent weight in QQQ
     */
    public double getConstituentWeight(String symbol) {
        return constituentWeights.computeIfAbsent(symbol, k ->
                QQQ_TOP_HOLDINGS.getOrDefault(symbol, 0.005) // Default 0.5% for smaller holdings
        );
    }

    /**
     * Get all top QQQ constituents with their weights
     */
    public Map<String, Double> getTopConstituents() {
        return new HashMap<>(QQQ_TOP_HOLDINGS);
    }

    /**
     * Calculate QQQ constituent alignment score
     * Critical for 0DTE as major tech stocks drive QQQ moves
     */
    public double calculateConstituentAlignment(double qqqMomentum) {
        double totalWeight = 0.0;
        double alignmentScore = 0.0;

        for (Map.Entry<String, Double> entry : QQQ_TOP_HOLDINGS.entrySet()) {
            String symbol = entry.getKey();
            double weight = entry.getValue();

            // Get constituent momentum (cached for performance)
            double constituentMomentum = getConstituentMomentum(symbol);

            // Calculate directional alignment
            double directionAlignment = qqqMomentum * constituentMomentum > 0 ? 1.0 : 0.0;

            // Weight by importance in QQQ
            alignmentScore += weight * directionAlignment;
            totalWeight += weight;
        }

        double finalAlignment = totalWeight > 0 ? alignmentScore / totalWeight : 0.5;

//        log.debug("QQQ Constituent Alignment: {} (Total Weight: {})",
//                String.format("%.3f", finalAlignment),
//                String.format("%.3f", totalWeight));

        return finalAlignment;
    }

    /**
     * Get cached constituent momentum
     */
    private double getConstituentMomentum(String symbol) {
        return constituentMomentum.computeIfAbsent(symbol, k ->
                marketDataService.getRealTimeMomentum(symbol)
        );
    }

    /**
     * Calculate weighted momentum of QQQ constituents
     * Useful for predicting QQQ direction based on constituent moves
     */
    public double calculateWeightedMomentum() {
        double weightedMomentum = 0.0;
        double totalWeight = 0.0;

        for (Map.Entry<String, Double> entry : QQQ_TOP_HOLDINGS.entrySet()) {
            String symbol = entry.getKey();
            double weight = entry.getValue();
            double momentum = getConstituentMomentum(symbol);

            weightedMomentum += weight * momentum;
            totalWeight += weight;
        }

        return totalWeight > 0 ? weightedMomentum / totalWeight : 0.0;
    }

    /**
     * Get constituent performance summary
     */
    public Map<String, Object> getConstituentSummary() {
        Map<String, Object> summary = new HashMap<>();

        // Calculate stats
        double avgMomentum = 0.0;
        int bullishCount = 0;
        int bearishCount = 0;

        for (String symbol : QQQ_TOP_HOLDINGS.keySet()) {
            double momentum = getConstituentMomentum(symbol);
            avgMomentum += momentum;

            if (momentum > 0.1) {
                bullishCount++;
            } else if (momentum < -0.1) {
                bearishCount++;
            }
        }

        avgMomentum /= QQQ_TOP_HOLDINGS.size();

        summary.put("averageMomentum", avgMomentum);
        summary.put("bullishCount", bullishCount);
        summary.put("bearishCount", bearishCount);
        summary.put("neutralCount", QQQ_TOP_HOLDINGS.size() - bullishCount - bearishCount);
        summary.put("weightedMomentum", calculateWeightedMomentum());
        summary.put("totalConstituentsTracked", QQQ_TOP_HOLDINGS.size());

        return summary;
    }

    /**
     * Get individual constituent details
     */
    public Map<String, Object> getConstituentDetails(String symbol) {
        Map<String, Object> details = new HashMap<>();

        if (!QQQ_TOP_HOLDINGS.containsKey(symbol)) {
            details.put("error", "Symbol not in QQQ top holdings");
            return details;
        }

        details.put("symbol", symbol);
        details.put("weight", getConstituentWeight(symbol));
        details.put("weightPercent", getConstituentWeight(symbol) * 100);
        details.put("momentum", getConstituentMomentum(symbol));
        details.put("ranking", getRanking(symbol));

        return details;
    }

    /**
     * Get ranking of constituent by weight
     */
    private int getRanking(String symbol) {
        List<Map.Entry<String, Double>> sorted = QQQ_TOP_HOLDINGS.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getKey().equals(symbol)) {
                return i + 1; // 1-based ranking
            }
        }
        return -1; // Not found
    }

    /**
     * Clear caches for fresh data
     */
    public void clearCaches() {
        constituentWeights.clear();
        constituentMomentum.clear();
        log.info("QQQ constituent caches cleared");
    }

    /**
     * Update constituent weight (for dynamic rebalancing)
     * In production: Called when ETF rebalances
     */
    public void updateConstituentWeight(String symbol, double newWeight) {
        constituentWeights.put(symbol, newWeight);
        log.info("Updated constituent weight: {} -> {}%", symbol, String.format("%.3f", newWeight * 100));
    }
}