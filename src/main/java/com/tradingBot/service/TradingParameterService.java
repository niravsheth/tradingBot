package com.tradingBot.service;

import com.tradingBot.entity.OptimalParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.Properties;

// Create new service: src/main/java/com/tradingBot/service/TradingParameterService.java
@Service
@Slf4j
public class TradingParameterService {

    @Value("${trading.parameters.file:trading-params.properties}")
    private String parameterFile;

    public void saveOptimalParameters(OptimalParameters params) {
        Properties props = new Properties();

        // Save all parameters
        if (params.getMarketBreadthThreshold() != null) {
            props.setProperty("market.breadth.threshold",
                    String.valueOf(params.getMarketBreadthThreshold()));
        }

        params.getStopLossByStrategy().forEach((strategy, stop) ->
                props.setProperty("stop." + strategy, String.valueOf(stop)));

        // Write to file
        try (FileOutputStream out = new FileOutputStream(parameterFile)) {
            props.store(out, "Optimal parameters for " + LocalDate.now().plusDays(1));
            log.info("Saved optimal parameters to {}", parameterFile);
        } catch (Exception e) {
            log.error("Failed to save parameters: {}", e.getMessage());
        }
    }

    @PostConstruct
    public void loadOptimalParameters() {
        File file = new File(parameterFile);
        if (!file.exists()) return;

        try (FileInputStream in = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(in);

            // Apply parameters to your configuration
            // You'll need to inject and update your services
            log.info("Loaded optimal parameters from previous day");
        } catch (Exception e) {
            log.error("Failed to load parameters: {}", e.getMessage());
        }
    }
}