package com.tradingBot.service;

import com.tradingBot.entity.Trade;
import com.tradingBot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CapitalAllocationService {

    private final TradeRepository tradeRepository;

    @Value("${trading.total-capital}")
    private BigDecimal totalCapital;

    @Value("${trading.max-position-percentage}")
    private Double maxPositionPercentage;

    @Value("${trading.min-position-size}")
    private BigDecimal minPositionSize;

    @Value("${trading.confidence-allocation.high}")
    private Double highConfidenceAllocation;

    @Value("${trading.confidence-allocation.medium}")
    private Double mediumConfidenceAllocation;

    @Value("${trading.confidence-allocation.low}")
    private Double lowConfidenceAllocation;

    /**
     * Calculate available capital considering open positions and today's P&L
     */
    public BigDecimal getAvailableCapital() {
        log.debug("[v62] Calculating available capital");

        // Start with total capital
        BigDecimal available = totalCapital;

        // Subtract today's losses (or add gains)
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0);
        BigDecimal todayPnL = tradeRepository.calculateProfitSince(startOfDay);
        if (todayPnL != null) {
            available = available.add(todayPnL);
            log.debug("[v62] Adjusted for today's P&L: ${} (available: ${})", todayPnL, available);
        }

        // Subtract capital in open positions
        List<Trade> openTrades = tradeRepository.findByStatus("OPEN");
        BigDecimal capitalInUse = BigDecimal.ZERO;

        for (Trade trade : openTrades) {
            BigDecimal positionValue = trade.getEntryPrice()
                    .multiply(BigDecimal.valueOf(trade.getQuantity() * 100));
            capitalInUse = capitalInUse.add(positionValue);
        }

        available = available.subtract(capitalInUse);
        log.info("[v62] Available capital: ${} (Total: ${}, In use: ${}, Today P&L: ${})",
                available, totalCapital, capitalInUse, todayPnL != null ? todayPnL : 0);

        return available.max(BigDecimal.ZERO);
    }

    /**
     * Calculate position size based on confidence level
     */
    public BigDecimal calculatePositionSize(Double confidence, BigDecimal optionPrice) {
        log.debug("[v62] Calculating position size for confidence: {}", confidence);

        BigDecimal availableCapital = getAvailableCapital();
        if (availableCapital.compareTo(minPositionSize) < 0) {
            log.warn("[v62] Insufficient capital: ${} < minimum ${}", availableCapital, minPositionSize);
            return BigDecimal.ZERO;
        }

        // Determine allocation percentage based on confidence
        double allocationPercentage;
        String confidenceLevel;

        if (confidence >= 0.8) {
            allocationPercentage = highConfidenceAllocation;
            confidenceLevel = "HIGH";
        } else if (confidence >= 0.6) {
            allocationPercentage = mediumConfidenceAllocation;
            confidenceLevel = "MEDIUM";
        } else {
            allocationPercentage = lowConfidenceAllocation;
            confidenceLevel = "LOW";
        }

        // Calculate position size
        BigDecimal allocatedCapital = availableCapital
                .multiply(BigDecimal.valueOf(allocationPercentage));

        // Apply max position limit
        BigDecimal maxPosition = totalCapital
                .multiply(BigDecimal.valueOf(maxPositionPercentage / 100));
        allocatedCapital = allocatedCapital.min(maxPosition);

        // Ensure minimum position size
        if (allocatedCapital.compareTo(minPositionSize) < 0) {
            log.info("[v62] Allocated capital ${} below minimum, using minimum ${}",
                    allocatedCapital, minPositionSize);
            allocatedCapital = minPositionSize;
        }

        log.info("[v62] Position sizing - Confidence: {} ({}), Allocation: {}%, Amount: ${}",
                confidence, confidenceLevel, (int)(allocationPercentage * 100), allocatedCapital);

        return allocatedCapital;
    }

    /**
     * Calculate number of contracts based on allocated capital
     */
    public int calculateContracts(BigDecimal allocatedCapital, BigDecimal optionPrice) {
        if (optionPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("[v62] Invalid option price: ${}", optionPrice);
            return 0;
        }

        // Each option contract represents 100 shares
        BigDecimal contractValue = optionPrice.multiply(BigDecimal.valueOf(100));
        int contracts = allocatedCapital.divide(contractValue, 0, RoundingMode.DOWN).intValue();

        // Ensure at least 1 contract if we have enough for it
        if (contracts == 0 && allocatedCapital.compareTo(contractValue) >= 0) {
            contracts = 1;
        }

        log.info("[v62] Calculated contracts: {} (Capital: ${}, Option price: ${}, Contract value: ${})",
                contracts, allocatedCapital, optionPrice, contractValue);

        return Math.min(contracts, 20); // Cap at 20 contracts for safety
    }
}
