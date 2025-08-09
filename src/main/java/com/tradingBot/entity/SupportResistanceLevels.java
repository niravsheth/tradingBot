package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "support_resistance_levels")
@Data
public class SupportResistanceLevels {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "level_type")
    private String levelType; // SUPPORT, RESISTANCE, POC

    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "strength")
    private Double strength; // 0.0 to 1.0

    @Column(name = "confirmed_touches")
    private Integer confirmedTouches;

    @Column(name = "last_touch")
    private LocalDateTime lastTouch;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "is_active")
    private Boolean isActive;
}

