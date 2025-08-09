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
public interface SupportResistanceLevelsRepository extends JpaRepository<SupportResistanceLevels, Long> {

    @Query("SELECT s FROM SupportResistanceLevels s WHERE s.symbol = :symbol AND s.isActive = true AND s.price BETWEEN :minPrice AND :maxPrice ORDER BY s.strength DESC")
    List<SupportResistanceLevels> findActiveLevelsNearPrice(@Param("symbol") String symbol,
                                                            @Param("minPrice") BigDecimal minPrice,
                                                            @Param("maxPrice") BigDecimal maxPrice);

    @Query("SELECT s FROM SupportResistanceLevels s WHERE s.symbol = :symbol AND s.isActive = true ORDER BY s.strength DESC")
    List<SupportResistanceLevels> findAllActiveLevels(@Param("symbol") String symbol);
}