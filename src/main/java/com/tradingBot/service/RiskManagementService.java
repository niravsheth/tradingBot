package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RiskManagementService {

    private final SafetyService safetyService;
    private final PreTradeRiskEngine preTradeRiskEngine;

    public boolean isTradeAllowed(Signal signal) {
        try {
            if (!safetyService.canTrade()) {
                log.warn("[RISK] Trade blocked by safety service");
                return false;
            }

            if (!preTradeRiskEngine.canExecuteSignal(signal)) {
                log.warn("[RISK] Trade blocked by pre-trade risk engine");
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("[RISK] Error in risk check: {}", e.getMessage());
            return false;
        }
    }

    public boolean checkPortfolioExposure(String symbol, String strategy) {
        try {
            // Basic portfolio exposure check
            return safetyService.canTrade();
        } catch (Exception e) {
            log.error("[RISK] Error checking portfolio exposure: {}", e.getMessage());
            return false;
        }
    }
}