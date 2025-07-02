package com.tradingBot.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.tradingBot.entity.*;

@Repository
public interface PendingFlowRepository extends JpaRepository<PendingFlow, Long> {
    List<PendingFlow> findByStatus(String status);

    @Query("SELECT p FROM PendingFlow p WHERE p.status = 'PENDING' " +
            "AND p.detectedAt > :cutoffTime")
    List<PendingFlow> findActivePendingFlows(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Modifying  // Added this annotation for update query
    @Query("UPDATE PendingFlow p SET p.status = 'EXPIRED' " +
            "WHERE p.status = 'PENDING' AND p.detectedAt < :expiryTime")
    void expireOldFlows(@Param("expiryTime") LocalDateTime expiryTime);

    Optional<PendingFlow> findByOptionSymbolAndStatus(String optionSymbol, String status);

    List<PendingFlow> findByDetectedAtAfterAndStatus(LocalDateTime after, String status);
}