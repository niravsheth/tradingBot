package com.tradingBot.repository;

import com.tradingBot.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, Long> {

    List<MarketData> findBySymbolAndTimestampAfterOrderByTimestampAsc(String symbol, LocalDateTime timestamp);

    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
            "AND m.timestamp >= :cutoffTime ORDER BY m.timestamp DESC")
    List<MarketData> findRecentData(@Param("symbol") String symbol,
                                    @Param("cutoffTime") LocalDateTime cutoffTime);

    default List<MarketData> findRecentData(String symbol, int minutes) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(minutes);
        return findRecentData(symbol, cutoffTime);
    }

    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
            "AND m.timestamp BETWEEN :startTime AND :endTime " +
            "ORDER BY m.timestamp ASC")
    List<MarketData> findBySymbolAndTimestampBetween(@Param("symbol") String symbol,
                                                     @Param("startTime") LocalDateTime startTime,
                                                     @Param("endTime") LocalDateTime endTime);


}