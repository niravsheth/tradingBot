package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Option {
    private String symbol;
    private String underlying;

    @JsonProperty("option_type")
    private String type;

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

    @JsonProperty("implied_volatility")
    private Double impliedVolatility;

    private OptionGreeks greeks;

    // Helper method to get type in uppercase
    public String getType() {
        return type != null ? type.toUpperCase() : null;
    }

    // Delegate methods for Greeks
    public Double getDelta() {
        return greeks != null ? greeks.getDelta() : null;
    }

    public Double getGamma() {
        return greeks != null ? greeks.getGamma() : null;
    }

    public Double getTheta() {
        return greeks != null ? greeks.getTheta() : null;
    }

    public Double getVega() {
        return greeks != null ? greeks.getVega() : null;
    }
}
