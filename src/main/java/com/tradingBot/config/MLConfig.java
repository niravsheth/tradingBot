package com.tradingBot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "tradingbot.ml")
@Data
public class MLConfig {
    private double minScore = 0.65;
    private int featureWindowMinutes = 30;
    private boolean enabled = true;
}