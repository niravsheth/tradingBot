package com.tradingBot.entity;


import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "option_price_history")
@Data
public class OptionPriceHistory {
    @ Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String optionSymbol;
    private LocalDateTime timestamp;
    private BigDecimal bid;
    private BigDecimal ask;
    private BigDecimal last;
    private BigDecimal midPrice;
    private Long volume;
    private Integer openInterest;
}

