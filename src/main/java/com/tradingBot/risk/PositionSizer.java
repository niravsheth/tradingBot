package com.tradingBot.risk;

import com.tradingBot.entity.Signal;
import com.tradingBot.config.TradingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@Slf4j
@RequiredArgsConstructor
public class PositionSizer {

    private final TradingConfig tradingConfig;

    public int calculateOptimalPositionSize(Signal signal, BigDecimal accountValue) {
        // Kelly Criterion based sizing
        double confidence = signal.getConfidence();
        double expectedReturn = 0.30; // 30% expected return
        double winRate = 0.55; // 55% win rate

        // Kelly formula: f = (p*b - q) / b
        double kelly = (winRate * expectedReturn - (1 - winRate)) / expectedReturn;
        kelly *= 0.25; // Use 1/4 Kelly for safety

        // Calculate position value
        BigDecimal positionValue = accountValue.multiply(BigDecimal.valueOf(kelly));

        // Apply risk limits
        BigDecimal maxPositionValue = tradingConfig.getMaxPositionValue(); // Fixed - removed .getTrading()
        positionValue = positionValue.min(maxPositionValue);

        // Convert to contracts
        BigDecimal optionPrice = signal.getOriginalOptionPrice();
        if (optionPrice == null || optionPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid option price for signal {}", signal.getId());
            return tradingConfig.getDefaultPositionSize(); // Fixed - removed .getTrading()
        }

        int contracts = positionValue.divide(
                optionPrice.multiply(BigDecimal.valueOf(100)),
                0,
                RoundingMode.DOWN
        ).intValue();

        // Apply limits
        int maxContracts = tradingConfig.getDefaultPositionSize(); // Fixed - removed .getTrading()
        int minContracts = 1;

        int finalSize = Math.max(minContracts, Math.min(contracts, maxContracts));

        log.info("Position sizing for {}: Kelly={}, Contracts={}, Final={}",
                signal.getOptionSymbol(), kelly, contracts, finalSize);

        return finalSize;
    }
}