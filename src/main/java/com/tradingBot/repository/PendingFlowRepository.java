package com.tradingBot.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
@Repostiory
public class PendingFlowRepositoryextends JpaRepository<PendingFlow, Long>  {
    List<PendingFlow> findByStatus(String status);

    @Query("SELECT p FROM PendingFlow p WHERE p.status = 'PENDING' " +
            "AND p.detectedAt > :cutoffTime")
    List<PendingFlow> findActivePendingFlows(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("UPDATE PendingFlow p SET p.status = 'EXPIRED' " +
            "WHERE p.status = 'PENDING' AND p.detectedAt < :expiryTime")
    void expireOldFlows(@Param("expiryTime") LocalDateTime expiryTime);

    Optional<PendingFlow> findByOptionSymbolAndStatus(String optionSymbol, String status);

    List<PendingFlow> findByDetectedAtAfterAndStatus(LocalDateTime after, String status);
}
