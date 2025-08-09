
package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "volume_profile")
@Data
public class VolumeProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "price_level", precision = 10, scale = 2)
    private BigDecimal priceLevel;

    @Column(name = "volume")
    private Long volume;

    @Column(name = "session_date")
    private LocalDateTime sessionDate;

    @Column(name = "timeframe")
    private String timeframe; // 1MIN, 5MIN, 15MIN, 30MIN, 1HR

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}