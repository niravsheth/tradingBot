package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Entity
@Table(name = "market_data", indexes = {
        @Index(name = "idx_symbol_timestamp", columnList = "symbol,timestamp DESC"),
        @Index(name = "idx_timestamp", columnList = "timestamp DESC"),
        @Index(name = "idx_symbol", columnList = "symbol")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String symbol;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    // OHLC data - using BigDecimal for precision
    @Column(name = "open", precision = 10, scale = 2)
    private BigDecimal open;

    @Column(name = "high", precision = 10, scale = 2)
    private BigDecimal high;

    @Column(name = "low", precision = 10, scale = 2)
    private BigDecimal low;

    @Column(name = "close", precision = 10, scale = 2)
    private BigDecimal close;

    // Price field for backward compatibility
    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    // Volume data - Using Long as it comes from API
    @Column(name = "volume")
    private Long volume;

    @Column(name = "tick_count")
    private Integer tickCount;

    // Technical indicators
    @Column(precision = 10, scale = 2)
    private BigDecimal vwap;

    @Column(precision = 10)
    private Double rsi; // RSI is typically a double value

    @Column(name = "sma_20", precision = 10, scale = 2)
    private BigDecimal sma20;

    @Column(name = "sma_50", precision = 10, scale = 2)
    private BigDecimal sma50;

    @Column(precision = 10)
    private Double volatility; // Volatility as percentage, typically double

    @Column(name = "volume_ratio", precision = 10)
    private Double volumeRatio; // Ratio is typically double

    // Market breadth indicators
    @Column(name = "market_breadth", precision = 5)
    private Double marketBreadth; // Percentage, typically double

    @Column(name = "advances")
    private Integer advances;

    @Column(name = "declines")
    private Integer declines;

    // Data quality fields
    @Column(name = "is_aggregated")
    private Boolean isAggregated = false;

    @Column(name = "is_fallback")
    private Boolean isFallback = false;

    @Column(name = "data_source", length = 50)
    private String dataSource;

    // Audit fields
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();

        // Ensure price field is set
        if (price == null && close != null) {
            price = close;
        }

        // Set default values for quality fields
        if (isAggregated == null) {
            isAggregated = false;
        }
        if (isFallback == null) {
            isFallback = false;
        }
        if (dataSource == null) {
            dataSource = "TRADIER";
        }

        // Set default volume if null
        if (volume == null || volume <= 0) {
            volume = 10000L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();

        // Ensure price field is set
        if (price == null && close != null) {
            price = close;
        }
    }

    /**
     * Validate OHLC relationships
     */
    public boolean isValidOHLC() {
        if (open == null || high == null || low == null || close == null) {
            return false;
        }

        // High should be >= all other prices
        if (high.compareTo(open) < 0 || high.compareTo(low) < 0 || high.compareTo(close) < 0) {
            return false;
        }

        // Low should be <= all other prices
        if (low.compareTo(open) > 0 || low.compareTo(high) > 0 || low.compareTo(close) > 0) {
            return false;
        }

        return true;
    }

    /**
     * Check if this is a complete bar with all required fields
     */
    public boolean isComplete() {
        return symbol != null &&
                timestamp != null &&
                open != null &&
                high != null &&
                low != null &&
                close != null &&
                volume != null &&
                volume > 0;
    }

    /**
     * Get typical price (H+L+C)/3
     */
    public BigDecimal getTypicalPrice() {
        if (high != null && low != null && close != null) {
            return high.add(low).add(close).divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        }
        return close != null ? close : price;
    }

    /**
     * Get true range for ATR calculation
     */
    public BigDecimal getTrueRange(BigDecimal previousClose) {
        if (high == null || low == null || close == null) {
            return null;
        }

        BigDecimal highLow = high.subtract(low);

        if (previousClose == null) {
            return highLow;
        }

        BigDecimal highPrevClose = high.subtract(previousClose).abs();
        BigDecimal lowPrevClose = low.subtract(previousClose).abs();

        return highLow.max(highPrevClose).max(lowPrevClose);
    }

    /**
     * Check if this is a green (bullish) bar
     */
    public boolean isGreenBar() {
        return close != null && open != null && close.compareTo(open) > 0;
    }

    /**
     * Check if this is a red (bearish) bar
     */
    public boolean isRedBar() {
        return close != null && open != null && close.compareTo(open) < 0;
    }

    /**
     * Get bar range (high - low)
     */
    public BigDecimal getRange() {
        if (high != null && low != null) {
            return high.subtract(low);
        }
        return BigDecimal.ZERO;
    }

    /**
     * Get bar body (|close - open|)
     */
    public BigDecimal getBody() {
        if (close != null && open != null) {
            return close.subtract(open).abs();
        }
        return BigDecimal.ZERO;
    }

    /**
     * Get volume as BigDecimal for calculations
     */
    public BigDecimal getVolumeAsBigDecimal() {
        return volume != null ? BigDecimal.valueOf(volume) : BigDecimal.ZERO;
    }

    /**
     * Set volume from BigDecimal
     */
    public void setVolumeFromBigDecimal(BigDecimal volumeBD) {
        if (volumeBD != null) {
            this.volume = volumeBD.longValue();
        }
    }
    @Column(name = "incremental_volume")
    private Long incrementalVolume;

    public Long getIncrementalVolume() {
        return incrementalVolume;
    }

    public void setIncrementalVolume(Long incrementalVolume) {
        this.incrementalVolume = incrementalVolume;
    }

}