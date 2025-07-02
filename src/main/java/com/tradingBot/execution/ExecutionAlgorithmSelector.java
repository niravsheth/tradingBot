package com.tradingBot.execution;

import com.tradingBot.analytics.MarketMicrostructureAnalyzer;
import com.tradingBot.entity.Signal;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class ExecutionAlgorithmSelector {

    private final MarketMicrostructureAnalyzer microAnalyzer;

    public enum ExecutionAlgorithm {
        MARKET,           // Immediate execution
        LIMIT_SWEEP,      // Work the order with limits
        ICEBERG,          // Hide size
        ADAPTIVE_LIMIT,   // Dynamic limit pricing
        SNIPER            // Aggressive but selective
    }

    @Data
    public static class ExecutionPlan {
        private ExecutionAlgorithm algorithm;
        private List<OrderSlice> slices = new ArrayList<>();
        private BigDecimal maxSpread;
        private int minFillSize;
        private double urgencyScore;
        private boolean useDiscretion;
    }

    @Data
    public static class OrderSlice {
        private int quantity;
        private BigDecimal limitPrice;
        private long delayMillis;
        private boolean isHidden;
    }

    public ExecutionPlan createExecutionPlan(Signal signal, int totalQuantity) {
        ExecutionPlan plan = new ExecutionPlan();

        // Assess market conditions
        boolean isToxic = microAnalyzer.isMarketToxic(signal.getSymbol());
        boolean hasLiquidity = microAnalyzer.hasSufficientLiquidity(signal.getSymbol(), totalQuantity);

        // Calculate urgency
        plan.urgencyScore = calculateUrgency(signal);

        // Select algorithm
        if (plan.urgencyScore > 0.8 && hasLiquidity) {
            plan.algorithm = ExecutionAlgorithm.MARKET;
        } else if (isToxic) {
            plan.algorithm = ExecutionAlgorithm.SNIPER;
        } else if (totalQuantity > 50) {
            plan.algorithm = ExecutionAlgorithm.ICEBERG;
        } else if (plan.urgencyScore < 0.3) {
            plan.algorithm = ExecutionAlgorithm.ADAPTIVE_LIMIT;
        } else {
            plan.algorithm = ExecutionAlgorithm.LIMIT_SWEEP;
        }

        // Create order slices
        createOrderSlices(plan, signal, totalQuantity);

        // Set execution parameters
        plan.maxSpread = calculateMaxSpread(signal);
        plan.minFillSize = Math.max(1, totalQuantity / 10);
        plan.useDiscretion = !isToxic && plan.urgencyScore < 0.7;

        log.info("Execution plan for {}: {} algorithm, {} slices, urgency: {}",
                signal.getOptionSymbol(), plan.algorithm, plan.slices.size(), plan.urgencyScore);

        return plan;
    }

    private double calculateUrgency(Signal signal) {
        double urgency = 0.5;

        // Time to expiration
        if (signal.getExpirationTime() != null) {
            long minutesToExpiry = java.time.Duration.between(
                    LocalDateTime.now(), signal.getExpirationTime()
            ).toMinutes();

            if (minutesToExpiry < 5) urgency += 0.4;
            else if (minutesToExpiry < 15) urgency += 0.2;
        }

        // Confidence level
        if (signal.getConfidence() > 0.85) urgency += 0.2;
        else if (signal.getConfidence() > 0.75) urgency += 0.1;

        // Momentum-based strategies need faster execution
        if ("BREAKOUT".equals(signal.getStrategy()) ||
                "MOMENTUM".equals(signal.getStrategy())) {
            urgency += 0.2;
        }

        return Math.min(1.0, urgency);
    }

    private void createOrderSlices(ExecutionPlan plan, Signal signal, int totalQuantity) {
        switch (plan.algorithm) {
            case MARKET:
                // Single market order
                OrderSlice slice = new OrderSlice();
                slice.quantity = totalQuantity;
                slice.delayMillis = 0;
                slice.isHidden = false;
                plan.slices.add(slice);
                break;

            case ICEBERG:
                // Split into smaller visible chunks
                int visibleSize = Math.min(10, totalQuantity / 5);
                int remaining = totalQuantity;
                long delay = 0;

                while (remaining > 0) {
                    OrderSlice iceSlice = new OrderSlice();
                    iceSlice.quantity = Math.min(visibleSize, remaining);
                    iceSlice.delayMillis = delay;
                    iceSlice.isHidden = remaining > visibleSize;
                    plan.slices.add(iceSlice);

                    remaining -= iceSlice.quantity;
                    delay += 2000; // 2 second intervals
                }
                break;

            case LIMIT_SWEEP:
                // Aggressive limit orders at multiple levels
                int sweepSize = totalQuantity / 3;
                for (int i = 0; i < 3; i++) {
                    OrderSlice sweepSlice = new OrderSlice();
                    sweepSlice.quantity = i < 2 ? sweepSize : totalQuantity - 2 * sweepSize;
                    sweepSlice.delayMillis = i * 1000; // 1 second intervals
                    sweepSlice.isHidden = false;
                    plan.slices.add(sweepSlice);
                }
                break;

            default:
                // Adaptive or sniper - single smart order
                OrderSlice smartSlice = new OrderSlice();
                smartSlice.quantity = totalQuantity;
                smartSlice.delayMillis = 0;
                smartSlice.isHidden = false;
                plan.slices.add(smartSlice);
        }
    }

    private BigDecimal calculateMaxSpread(Signal signal) {
        // Base spread tolerance
        BigDecimal baseSpread = new BigDecimal("0.02"); // 2 cents

        // Adjust based on option price
        if (signal.getOriginalOptionPrice() != null) {
            BigDecimal priceBasedSpread = signal.getOriginalOptionPrice()
                    .multiply(new BigDecimal("0.01")); // 1% of price
            baseSpread = baseSpread.max(priceBasedSpread);
        }

        return baseSpread;
    }
}