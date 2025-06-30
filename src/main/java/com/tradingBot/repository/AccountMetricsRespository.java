package com.tradingBot.repository;

import org.springframework.stereotype.Repository;

@Repository
public class AccountMetricsRespository extends JpaRepository<AccountMetrics, Long>  {

    Optional<AccountMetrics> findTopByOrderByTimestampDesc();

    List<AccountMetrics> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT a FROM AccountMetrics a WHERE DATE(a.timestamp) = CURRENT_DATE " +
            "ORDER BY a.timestamp DESC LIMIT 1")
    Optional<AccountMetrics> findTodayMetrics();
}
