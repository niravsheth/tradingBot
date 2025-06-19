package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderRequest {
    @JsonProperty("class")
    private String orderClass = "equity";

    private String symbol;
    private String side;
    private Integer quantity;
    private String type;
    private String duration;
    private BigDecimal price;

    @JsonProperty("stop")
    private BigDecimal stopPrice;

    @JsonProperty("option_symbol")
    private String optionSymbol;

    // Helper method to ensure proper setup for options
    public void setupForOption(String optionSymbol) {
        this.optionSymbol = optionSymbol;
        this.symbol = null;  // Clear symbol as we're using option_symbol
        this.orderClass = "option";  // Set class to option
    }
}