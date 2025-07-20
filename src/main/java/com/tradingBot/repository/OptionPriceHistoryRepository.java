package com.tradingBot.repository;

import com.tradingBot.entity.OptionPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

// Add repository
public interface OptionPriceHistoryRepository extends JpaRepository<OptionPriceHistory, Long> {
    List<OptionPriceHistory> findByOptionSymbolAndTimestampBetween(
            String symbol, LocalDateTime start, LocalDateTime end);
}