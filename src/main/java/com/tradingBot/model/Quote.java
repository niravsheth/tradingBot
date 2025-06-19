package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Quote {
    private String symbol;
    private BigDecimal last;
    private BigDecimal bid;
    private BigDecimal ask;

    @JsonProperty("bidsize")
    private Integer bidSize;

    @JsonProperty("asksize")
    private Integer askSize;

    private Long volume;
    private BigDecimal high;
    private BigDecimal low;

    @JsonProperty("prevclose")
    private BigDecimal previousClose;

    private BigDecimal open;
    private BigDecimal close;
    private BigDecimal change;

    @JsonProperty("change_percentage")
    private BigDecimal changePercentage;

    @JsonProperty("average_volume")
    private Long averageVolume;

    @JsonProperty("week_52_high")
    private BigDecimal week52High;

    @JsonProperty("week_52_low")
    private BigDecimal week52Low;
}
