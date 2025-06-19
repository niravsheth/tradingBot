package com.tradingBot.model;

public enum MarketRegime {
    TRENDING_UP,
    TRENDING_DOWN,
    CHOPPY,
    LOW_VOLATILITY,
    HIGH_VOLATILITY,
    OPENING_RANGE,  // First 30 minutes
    CLOSING_RANGE   // Last 30 minutes
}