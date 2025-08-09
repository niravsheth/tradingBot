package com.tradingBot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "poc_data")
@Data
public class POCData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "poc_price", precision = 10, scale = 2)
    private BigDecimal pocPrice;

    @Column(name = "poc_volume")
    private Long pocVolume;

    @Column(name = "session_date")
    private LocalDateTime sessionDate;

    @Column(name = "timeframe")
    private String timeframe;

    @Column(name = "value_area_high", precision = 10, scale = 2)
    private BigDecimal valueAreaHigh;

    @Column(name = "value_area_low", precision = 10, scale = 2)
    private BigDecimal valueAreaLow;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
