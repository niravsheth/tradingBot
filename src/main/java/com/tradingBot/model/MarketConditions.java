
package com.tradingBot.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MarketConditions {
    private double vix;
    private double marketBreadth; // Advance/Decline ratio
    private MarketInternals internals;
    private BigDecimal spyVolume;
    private BigDecimal spyPrice;
    private String marketTrend; // UP, DOWN, SIDEWAYS
    private LocalDateTime timestamp;

    public boolean isHighVolatility() {
        return vix > 25;
    }

    public boolean isTrendingMarket() {
        return marketBreadth > 0.65 || marketBreadth < 0.35;
    }

    public boolean isHealthyMarket() {
        return vix < 20 && marketBreadth > 0.4 && marketBreadth < 0.6;
    }
}

