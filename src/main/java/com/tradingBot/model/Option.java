package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Option {
    private String symbol;
    private String underlying;

    @JsonProperty("option_type")
    private String type; // "call" or "put" from API

    // Override getter to normalize type to uppercase
    public String getType() {
        return type != null ? type.toUpperCase() : null;
    }

    // Keep the setter as is
    public void setType(String type) {
        this.type = type;
    }

    @JsonProperty("strike")
    private BigDecimal strikePrice;

    @JsonProperty("expiration_date")
    private String expirationDate;

    private BigDecimal bid;
    private BigDecimal ask;
    private BigDecimal last;
    private Integer volume;

    @JsonProperty("open_interest")
    private Integer openInterest;

    // IV might come in different field names
    @JsonProperty("implied_volatility")
    private Double impliedVolatility;

    @JsonProperty("mid_iv")
    private Double midIv;

    @JsonProperty("ask_iv")
    private Double askIv;

    @JsonProperty("bid_iv")
    private Double bidIv;

    private OptionGreeks greeks;

    // Calculate mid price
    public BigDecimal getMidPrice() {
        if (bid != null && ask != null) {
            return bid.add(ask).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        return last != null ? last : BigDecimal.ZERO;
    }

    // Get IV from any available source
    public Double getImpliedVolatility() {
        if (impliedVolatility != null) return impliedVolatility;
        if (midIv != null) return midIv;
        if (greeks != null && greeks.getMidIv() != null) return greeks.getMidIv();
        if (askIv != null && bidIv != null) return (askIv + bidIv) / 2.0;
        if (askIv != null) return askIv;
        if (bidIv != null) return bidIv;
        return null;
    }

    // Set IV (for cases where we calculate or estimate it)
    public void setImpliedVolatility(Double iv) {
        this.impliedVolatility = iv;
    }

    // Helper method to check if option data is valid
    public boolean isValid() {
        return bid != null && ask != null && volume != null &&
                bid.compareTo(BigDecimal.ZERO) > 0 &&
                ask.compareTo(BigDecimal.ZERO) > 0;
    }

    // Calculate spread percentage
    public double getSpreadPercentage() {
        if (bid == null || ask == null || bid.compareTo(BigDecimal.ZERO) == 0) {
            return 100.0; // Return high value if invalid
        }
        BigDecimal spread = ask.subtract(bid);
        BigDecimal midPrice = getMidPrice();
        if (midPrice.compareTo(BigDecimal.ZERO) == 0) {
            return 100.0;
        }
        return spread.divide(midPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

}