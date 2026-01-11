package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderWrapper {
    private String id;
    private String status;
    private String symbol;

    @JsonProperty("option_symbol")
    private String optionSymbol;

    private Integer quantity;
    private String side;
    private String type;

    @JsonProperty("avg_fill_price")
    private BigDecimal avgFillPrice;

    @JsonProperty("exec_quantity")
    private Integer execQuantity;

    @JsonProperty("remaining_quantity")
    private Integer remainingQuantity;
}