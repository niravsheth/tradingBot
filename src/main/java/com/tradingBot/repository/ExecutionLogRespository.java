package com.tradingBot.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, Long> {

    List<ExecutionLog> findBySignalId(Long signalId);

    List<ExecutionLog> findByExecutionTimeBetween(LocalDateTime start, LocalDateTime end);

    List<ExecutionLog> findByStatusAndExecutionTimeAfter(String status, LocalDateTime after);

    @Query("SELECT COUNT(e) FROM ExecutionLog e WHERE e.status = :status " +
            "AND e.executionTime >= :startTime")
    int countByStatusSince(@Param("status") String status,
                           @Param("startTime") LocalDateTime startTime);
}
