package com.tradingBot.model;

import lombok.Data;

@Data
public class DynamicParameters {
    private double minVolumeMultiplier = 1.0;  // Adjust min volume requirement
    private double minIVMultiplier = 1.0;      // Adjust min IV requirement
    private double targetMultiplier = 1.0;     // Adjust profit target based on volatility
    private double stopMultiplier = 1.0;       // Adjust stop loss based on volatility

    // Constructor with default values
    public DynamicParameters() {
        this.minVolumeMultiplier = 1.0;
        this.minIVMultiplier = 1.0;
        this.targetMultiplier = 1.0;
        this.stopMultiplier = 1.0;
    }

    // Constructor with custom values
    public DynamicParameters(double minVolume, double minIV, double target, double stop) {
        this.minVolumeMultiplier = minVolume;
        this.minIVMultiplier = minIV;
        this.targetMultiplier = target;
        this.stopMultiplier = stop;
    }
}