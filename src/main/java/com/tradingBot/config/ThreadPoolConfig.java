package com.tradingBot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "tradingbot.thread-pools")
@Data
public class ThreadPoolConfig {

    private PoolSettings tradingEngine = new PoolSettings();
    private PoolSettings flowConfirmation = new PoolSettings();
    private PoolSettings positionMonitor = new PoolSettings();

    @Data
    public static class PoolSettings {
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 50;
        private String threadPrefix = "Worker-";
    }
}