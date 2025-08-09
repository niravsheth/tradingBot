package com.tradingBot.repository;

import com.tradingBot.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
@Repository
public interface POCDataRepository extends JpaRepository<POCData, Long> {

    @Query("SELECT p FROM POCData p WHERE p.symbol = :symbol AND p.sessionDate >= :sessionStart ORDER BY p.createdAt DESC LIMIT 1")
    Optional<POCData> findLatestPOCForSession(@Param("symbol") String symbol, @Param("sessionStart") LocalDateTime sessionStart);

    @Query("SELECT p FROM POCData p WHERE p.symbol = :symbol AND p.timeframe = :timeframe ORDER BY p.createdAt DESC LIMIT 1")
    Optional<POCData> findLatestPOCByTimeframe(@Param("symbol") String symbol, @Param("timeframe") String timeframe);
}