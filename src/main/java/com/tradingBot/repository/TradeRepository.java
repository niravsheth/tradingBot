package com.tradingBot.repository;

import com.tradingBot.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findByStatusAndSymbol(String status, String symbol);

    @Query("SELECT t FROM Trade t WHERE t.entryTime >= :startDate AND t.status = 'CLOSED'")
    List<Trade> findClosedTradesAfter(LocalDateTime startDate);

    @Query("SELECT SUM(t.profit) FROM Trade t WHERE t.status = 'CLOSED' AND t.entryTime >= :startDate")
    BigDecimal calculateProfitSince(LocalDateTime startDate);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = :status")
    long countByStatus(String status);

    List<Trade> findByStatus(String status);

    /**
     * Find trades by option symbol and entry time after
     */
    @Query("SELECT t FROM Trade t WHERE t.optionSymbol LIKE %:optionSymbol% AND t.entryTime > :entryTime")
    List<Trade> findByOptionSymbolAndEntryTimeAfter(@Param("optionSymbol") String optionSymbol,
                                                    @Param("entryTime") LocalDateTime entryTime);

    List<Trade> findByOptionSymbolAndStatus(String optionSymbol, String status);


}