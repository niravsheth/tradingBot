package com.tradingBot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BrokerPosition {
    private String symbol;
    private int quantity;
    private BigDecimal avgCost;
    private BigDecimal currentPrice;
    private BigDecimal marketValue;
    private BigDecimal pnl;
    private BigDecimal pnlPercent;
    private String side; // "long" or "short"

    public boolean isLong() {
        return quantity > 0;
    }

    public boolean isShort() {
        return quantity < 0;
    }

    public int getAbsoluteQuantity() {
        return Math.abs(quantity);
    }
}
