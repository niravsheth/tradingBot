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
public interface VWAPDataRepository extends JpaRepository<VWAPData, Long> {

    @Query("SELECT v FROM VWAPData v WHERE v.symbol = :symbol AND v.sessionDate >= :sessionStart ORDER BY v.timestamp DESC LIMIT 1")
    Optional<VWAPData> findLatestVWAPForSession(@Param("symbol") String symbol, @Param("sessionStart") LocalDateTime sessionStart);

    @Query("SELECT v FROM VWAPData v WHERE v.symbol = :symbol AND v.timestamp >= :since ORDER BY v.timestamp DESC")
    List<VWAPData> findRecentVWAP(@Param("symbol") String symbol, @Param("since") LocalDateTime since);
}
