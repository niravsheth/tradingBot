package com.tradingBot.repository;

import com.tradingBot.entity.RiskMetricsData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface RiskMetricsDataRepository extends JpaRepository<RiskMetricsData, Long> {
    Optional<RiskMetricsData> findTopByOrderByCalculatedAtDesc();
}