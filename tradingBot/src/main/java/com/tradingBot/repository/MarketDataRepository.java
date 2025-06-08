package com.tradingBot.repository;

import com.tradingBot.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, Long> {
    MarketData findTopBySymbolOrderByTimestampDesc(String symbol);
    List<MarketData> findBySymbolAndTimestampAfterOrderByTimestampAsc(String symbol, LocalDateTime timestamp);
}