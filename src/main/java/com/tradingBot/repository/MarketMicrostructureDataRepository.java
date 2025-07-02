package com.tradingBot.repository;

import com.tradingBot.entity.MarketMicrostructureData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketMicrostructureDataRepository extends JpaRepository<MarketMicrostructureData, Long> {
    List<MarketMicrostructureData> findBySymbolAndTimestampAfterOrderByTimestampDesc(
            String symbol, LocalDateTime after);
}