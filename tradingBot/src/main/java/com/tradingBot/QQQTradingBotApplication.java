package com.tradingBot;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@Slf4j
public class QQQTradingBotApplication {

    public static final String VERSION = "62.0.0";
    public static final String RELEASE_DATE = "2025-06-07";

    @PostConstruct
    public void init() {
        // Set default timezone to ET
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        log.info("Application timezone set to: {}", TimeZone.getDefault().getID());

        // Log version and startup warning
        log.info("===========================================");
        log.info("QQQ 0DTE Trading Bot v{} starting...", VERSION);
        log.info("Release Date: {}", RELEASE_DATE);
        log.warn("===========================================");
        log.warn("⚠️  0DTE OPTIONS TRADING BOT v{} ⚠️", VERSION);
        log.warn("This bot trades extremely risky instruments");
        log.warn("You can lose 100% of position value quickly");
        log.warn("Only trade money you can afford to lose!");
        log.warn("===========================================");
    }

    public static void main(String[] args) {
        SpringApplication.run(QQQTradingBotApplication.class, args);
    }
}