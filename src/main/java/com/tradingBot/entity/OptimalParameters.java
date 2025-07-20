package com.tradingBot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;

@Data
public class OptimalParameters {
    private Map<String, Double> stopLossByStrategy = new HashMap<>();
    private Map<String, BigDecimal> profitTargetByStrategy = new HashMap<>();
    private Double marketBreadthThreshold;
    private Map<String, Double> minVolumeRatios = new HashMap<>();
    private Double minConfidence;
    private List<TimeWindow> optimalTradingWindows = new ArrayList<>();
    private Double neutralZoneThreshold;

    public void setStopLoss(String strategy, double stopPercentage) {
        stopLossByStrategy.put(strategy, stopPercentage);
    }

    public void setProfitTarget(String strategy, BigDecimal targetPercentage) {
        profitTargetByStrategy.put(strategy, targetPercentage);
    }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();

        sb.append("STOP LOSSES:\n");
        stopLossByStrategy.forEach((strategy, stop) ->
                sb.append(String.format("• %s: %.0f%%\n", strategy, stop * 100)));

        sb.append("\nPROFIT TARGETS:\n");
        profitTargetByStrategy.forEach((strategy, target) ->
                sb.append(String.format("• %s: %.0f%%\n", strategy, target.multiply(BigDecimal.valueOf(100)))));

        if (marketBreadthThreshold != null) {
            sb.append(String.format("\nMARKET BREADTH: %.3f\n", marketBreadthThreshold));
        }

        if (minConfidence != null) {
            sb.append(String.format("MIN CONFIDENCE: %.0f%%\n", minConfidence * 100));
        }

        return sb.toString();
    }
}

