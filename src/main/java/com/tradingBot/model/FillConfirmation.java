package com.tradingBot.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class FillConfirmation {
    private boolean filled;
    private BigDecimal fillPrice;
    private int filledQuantity;
    private LocalDateTime fillTime;

    public FillConfirmation(boolean filled, BigDecimal fillPrice, int filledQuantity, LocalDateTime fillTime) {
        this.filled = filled;
        this.fillPrice = fillPrice;
        this.filledQuantity = filledQuantity;
        this.fillTime = fillTime;
    }

    // Getters
    public boolean isFilled() { return filled; }
    public BigDecimal getFillPrice() { return fillPrice; }
    public int getFilledQuantity() { return filledQuantity; }
    public LocalDateTime getFillTime() { return fillTime; }
}
