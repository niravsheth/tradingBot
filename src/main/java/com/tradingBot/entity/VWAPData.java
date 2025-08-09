package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "vwap_data")
@Data
public class VWAPData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "vwap_price", precision = 10, scale = 2)
    private BigDecimal vwapPrice;

    @Column(name = "upper_band", precision = 10, scale = 2)
    private BigDecimal upperBand;

    @Column(name = "lower_band", precision = 10, scale = 2)
    private BigDecimal lowerBand;

    @Column(name = "std_dev", precision = 10, scale = 4)
    private BigDecimal stdDev;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    @Column(name = "session_date")
    private LocalDateTime sessionDate;
}
