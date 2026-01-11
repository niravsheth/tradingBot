package com.tradingBot.repository;

import com.tradingBot.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MarketDataRepository - COMPLETE WITH ALL METHODS
 */
@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, Long> {

    // ============================================
    // EXISTING METHODS YOUR CODE USES
    // ============================================

    /**
     * Find all market data for a symbol
     */
    List<MarketData> findBySymbol(String symbol);

    /**
     * Find most recent market data for a symbol
     */
    MarketData findTopBySymbolOrderByTimestampDesc(String symbol);

    /**
     * Find market data within time range
     */
    List<MarketData> findBySymbolAndTimestampBetween(
            String symbol,
            LocalDateTime startTime,
            LocalDateTime endTime
    );

    /**
     * Find recent data for last N minutes - USED BY YOUR EXISTING CODE
     * This is what findRecentData does
     */
    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
            "AND m.timestamp >= :startTime " +
            "ORDER BY m.timestamp ASC")
    List<MarketData> findRecentDataQuery(
            @Param("symbol") String symbol,
            @Param("startTime") LocalDateTime startTime
    );

    /**
     * findRecentData - Default implementation
     * Used extensively in your ZeroDTEStrategy
     */
    default List<MarketData> findRecentData(String symbol, int minutes) {
        LocalDateTime startTime = LocalDateTime.now().minusMinutes(minutes);
        return findRecentDataQuery(symbol, startTime);
    }

    // ============================================
    // NEW METHODS FOR NEW SERVICES
    // ============================================

    /**
     * Find market data after a specific timestamp
     * Used by: BarAggregationService, BounceDetectionService, VolumeAnalysisService
     */
    List<MarketData> findBySymbolAndTimestampAfterOrderByTimestampAsc(
            String symbol,
            LocalDateTime timestamp
    );

    /**
     * Convenience method - same as findRecentData but with LocalDateTime
     */
    default List<MarketData> findBySymbolAndTimestampAfter(String symbol, LocalDateTime timestamp) {
        return findBySymbolAndTimestampAfterOrderByTimestampAsc(symbol, timestamp);
    }

    /**
     * Delete old market data before a timestamp
     * Used by: BarAggregationService for cleanup
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM MarketData m WHERE m.symbol = :symbol AND m.timestamp < :timestamp")
    int deleteBySymbolAndTimestampBefore(
            @Param("symbol") String symbol,
            @Param("timestamp") LocalDateTime timestamp
    );

    /**
     * Count bars for a symbol
     */
    long countBySymbol(String symbol);

    /**
     * Find bars within a specific time window
     */
    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
            "AND m.timestamp >= :startTime AND m.timestamp <= :endTime " +
            "ORDER BY m.timestamp ASC")
    List<MarketData> findBarsInWindow(
            @Param("symbol") String symbol,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Get latest bar for each tracked symbol
     */
    @Query("SELECT m FROM MarketData m WHERE m.timestamp = " +
            "(SELECT MAX(m2.timestamp) FROM MarketData m2 WHERE m2.symbol = m.symbol) " +
            "AND m.symbol IN :symbols")
    List<MarketData> findLatestForSymbols(@Param("symbols") List<String> symbols);

    /**
     * Check if we have recent data for a symbol
     */
    default boolean hasRecentData(String symbol, int minutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        MarketData latest = findTopBySymbolOrderByTimestampDesc(symbol);
        return latest != null && latest.getTimestamp().isAfter(cutoff);
    }

    /**
     * Get bars with specific volume criteria
     */
    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol " +
            "AND m.timestamp >= :startTime " +
            "AND m.volume > :minVolume " +
            "ORDER BY m.timestamp ASC")
    List<MarketData> findHighVolumeBars(
            @Param("symbol") String symbol,
            @Param("startTime") LocalDateTime startTime,
            @Param("minVolume") long minVolume
    );

    /**
     * Cleanup all data older than timestamp across all symbols
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM MarketData m WHERE m.timestamp < :timestamp")
    int deleteAllBeforeTimestamp(@Param("timestamp") LocalDateTime timestamp);
}