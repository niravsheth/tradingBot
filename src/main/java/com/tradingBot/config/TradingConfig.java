package com.tradingBot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;
import java.math.BigDecimal;

@Configuration
@ConfigurationProperties(prefix = "tradingbot.trading")
@Data
public class TradingConfig {

    private String marketOpen = "09:30";
    private String marketClose = "16:00";
    private String timezone = "America/New_York";

    private int defaultPositionSize = 10;
    private int maxPositions = 20;
    private BigDecimal maxPositionValue = new BigDecimal("10000");

    private RiskSettings risk = new RiskSettings();

    @Data
    public static class RiskSettings {
        private BigDecimal maxDailyLoss = new BigDecimal("1000");
        private BigDecimal maxPositionLoss = new BigDecimal("500");
        private BigDecimal trailingStopActivation = new BigDecimal("0.15");
        private BigDecimal trailingStopDistance = new BigDecimal("0.10");
    }
}