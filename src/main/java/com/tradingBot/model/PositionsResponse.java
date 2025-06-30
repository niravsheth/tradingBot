package com.tradingBot.model;

import lombok.Data;

import java.util.List;

@Data
public class PositionsResponse {
    private List<Position> positions;
}