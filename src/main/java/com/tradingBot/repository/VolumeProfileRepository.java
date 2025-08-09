
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
public interface VolumeProfileRepository extends JpaRepository<VolumeProfile, Long> {

    @Query("SELECT v FROM VolumeProfile v WHERE v.symbol = :symbol AND v.sessionDate >= :sessionStart AND v.timeframe = :timeframe ORDER BY v.volume DESC")
    List<VolumeProfile> findPOCForSession(@Param("symbol") String symbol,
                                          @Param("sessionStart") LocalDateTime sessionStart,
                                          @Param("timeframe") String timeframe);

    @Query("SELECT v FROM VolumeProfile v WHERE v.symbol = :symbol AND v.sessionDate >= :since ORDER BY v.createdAt DESC")
    List<VolumeProfile> findRecentVolumeProfile(@Param("symbol") String symbol, @Param("since") LocalDateTime since);
}