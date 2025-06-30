package com.tradingBot.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface SystemAlertRepository extends JpaRepository<SystemAlert, Long> {

    List<SystemAlert> findByResolvedFalseOrderByCreatedAtDesc();

    List<SystemAlert> findBySeverityAndResolvedFalse(String severity);

    List<SystemAlert> findByTypeAndCreatedAtAfter(String type, LocalDateTime after);

    @Query("UPDATE SystemAlert a SET a.resolved = true, a.resolvedAt = :now " +
            "WHERE a.id = :alertId")
    void resolveAlert(@Param("alertId") Long alertId, @Param("now") LocalDateTime now);
}