package com.tradingBot.repository;

import com.tradingBot.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, Long> {
    MarketData findTopBySymbolOrderByTimestampDesc(String symbol);
    List<MarketData> findBySymbolAndTimestampAfterOrderByTimestampAsc(String symbol, LocalDateTime timestamp);

    /**
     * Find recent market data for a symbol within the last N minutes
     */
    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
            "AND m.timestamp >= :cutoffTime ORDER BY m.timestamp DESC")
    List<MarketData> findRecentData(@Param("symbol") String symbol,
                                    @Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Convenience method that calculates the cutoff time
     */
    default List<MarketData> findRecentData(String symbol, int minutes) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(minutes);
        return findRecentData(symbol, cutoffTime);
    }

    /**
     * Find market data between two timestamps
     */
    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
            "AND m.timestamp BETWEEN :startTime AND :endTime " +
            "ORDER BY m.timestamp ASC")
    List<MarketData> findBySymbolAndTimestampBetween(@Param("symbol") String symbol,
                                                     @Param("startTime") LocalDateTime startTime,
                                                     @Param("endTime") LocalDateTime endTime);

    /**
     * Get the latest market data entry for a symbol
     */
    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
            "ORDER BY m.timestamp DESC LIMIT 1")
    MarketData findLatestBySymbol(@Param("symbol") String symbol);

}