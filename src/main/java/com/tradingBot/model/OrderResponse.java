package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderResponse {
    private OrderWrapper order;

    public String getId() {
        return order != null ? order.getId() : null;
    }

    public String getStatus() {
        return order != null ? order.getStatus() : null;
    }

    public String getSymbol() {
        return order != null && order.getOptionSymbol() != null ?
                order.getOptionSymbol() : (order != null ? order.getSymbol() : null);
    }

    public Integer getQuantity() {
        return order != null ? order.getQuantity() : null;
    }

    public String getSide() {
        return order != null ? order.getSide() : null;
    }

    public String getType() {
        return order != null ? order.getType() : null;
    }

    public BigDecimal getAvgFillPrice() {
        return order != null ? order.getAvgFillPrice() : null;
    }
}