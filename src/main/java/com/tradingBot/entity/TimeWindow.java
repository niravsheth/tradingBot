package com.tradingBot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalTime;

@Data
@AllArgsConstructor
public class TimeWindow {
    private LocalTime start;
    private LocalTime end;
    private double winRate;
    private int tradeCount;
}
