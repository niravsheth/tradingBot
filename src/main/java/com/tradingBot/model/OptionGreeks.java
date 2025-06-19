package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OptionGreeks {
    private Double delta;
    private Double gamma;
    private Double theta;
    private Double vega;
    private Double rho;

    @JsonProperty("mid_iv")
    private Double midIv;

    @JsonProperty("smv_vol")
    private Double smvVol;

    @JsonProperty("updated_at")
    private String updatedAt;
}