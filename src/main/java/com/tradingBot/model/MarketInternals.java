package com.tradingBot.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MarketInternals {
    private int tick; // NYSE TICK
    private int add; // Advance-Decline Difference
    private double putCallRatio;
    private BigDecimal vold; // Down Volume
    private BigDecimal volu; // Up Volume
    private int newHighs;
    private int newLows;

    public boolean isBullishInternals() {
        return tick > 500 && add > 1000 && putCallRatio < 0.8;
    }

    public boolean isBearishInternals() {
        return tick < -500 && add < -1000 && putCallRatio > 1.2;
    }

    public double getVolumeRatio() {
        if (vold == null || vold.compareTo(BigDecimal.ZERO) == 0) {
            return 1.0;
        }
        return volu.divide(vold, 2, BigDecimal.ROUND_HALF_UP).doubleValue();
    }
}
