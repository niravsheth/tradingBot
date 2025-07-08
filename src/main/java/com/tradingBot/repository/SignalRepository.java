package com.tradingBot.repository;

import com.tradingBot.entity.Signal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SignalRepository extends JpaRepository<Signal, Long> {
    List<Signal> findByExecutedFalseAndTimestampAfter(LocalDateTime timestamp);
    List<Signal> findBySymbolAndTimestampAfter(String symbol, LocalDateTime timestamp);

    // NEW methods for signal lifecycle management
    @Query("SELECT s FROM Signal s WHERE s.executed = false AND s.status = 'PENDING' " +
            "AND s.timestamp >= :timestamp AND s.expirationTime > :now")
    List<Signal> findFreshUnexecutedSignals(@Param("timestamp") LocalDateTime timestamp,
                                            @Param("now") LocalDateTime now);

    List<Signal> findByExecutedFalseAndStatus(String status);

    @Query("SELECT s FROM Signal s WHERE s.status = 'PENDING' AND s.expirationTime < :now")
    List<Signal> findExpiredPendingSignals(@Param("now") LocalDateTime now);


    /**
     * Find signals that have been executed after a certain timestamp
     */
    @Query("SELECT s FROM Signal s WHERE s.executed = true AND s.timestamp > :timestamp")
    List<Signal> findByExecutedTrueAndTimestampAfter(@Param("timestamp") LocalDateTime timestamp);

    /**
     * Find executed signals with status 'EXECUTED' after timestamp
     */
    @Query("SELECT s FROM Signal s WHERE s.status = 'EXECUTED' AND s.timestamp > :timestamp")
    List<Signal> findExecutedSignalsAfter(@Param("timestamp") LocalDateTime timestamp);


    List<Signal> findByExecutedFalse();

    List<Signal> findByExecutedFalseAndExpirationTimeAfter(LocalDateTime currentTime);

    @Query("SELECT s FROM Signal s WHERE s.optionSymbol = :optionSymbol " +
            "AND s.createdAt > :timeLimit AND s.executed = false")
    List<Signal> findRecentSignalsForOption(@Param("optionSymbol") String optionSymbol,
                                            @Param("timeLimit") LocalDateTime timeLimit);

    List<Signal> findByStrategyAndCreatedAtAfter(String strategy, LocalDateTime after);

    @Query("SELECT COUNT(s) FROM Signal s WHERE s.executed = true AND s.status = 'EXECUTED' " +
            "AND s.createdAt >= :startTime")
    int countExecutedSignalsSince(@Param("startTime") LocalDateTime startTime);


    // Find signals by status
    List<Signal> findByStatus(String status);

    // Find signals by status with expiration check
    List<Signal> findByStatusAndExpirationTimeAfter(String status, LocalDateTime currentTime);

    // Find fresh unexecuted signals (your existing method)
    @Query("SELECT s FROM Signal s WHERE s.executed = false " +
            "AND s.status = 'PENDING' " +
            "AND s.expirationTime > :currentTime " +
            "ORDER BY s.confidence DESC, s.timestamp DESC")
    List<Signal> findFreshUnexecutedSignalsPrioritized(@Param("currentTime") LocalDateTime currentTime);

    // Find tracking signals that haven't expired
    @Query("SELECT s FROM Signal s WHERE s.status = 'TRACKING' " +
            "AND s.expirationTime > :currentTime")
    List<Signal> findActiveTrackingSignals(@Param("currentTime") LocalDateTime currentTime);

    // Find pending signals ready for execution
    @Query("SELECT s FROM Signal s WHERE s.status = 'PENDING' " +
            "AND s.executed = false " +
            "AND s.expirationTime > :currentTime " +
            "ORDER BY s.confidence DESC")
    List<Signal> findPendingSignalsForExecution(@Param("currentTime") LocalDateTime currentTime);

    // Find signals by status and symbol
    List<Signal> findByStatusAndSymbol(String status, String symbol);

    // Count signals by status created after a certain time
    long countByStatusAndTimestampAfter(String status, LocalDateTime timestamp);

    // Find expired signals
    @Query("SELECT s FROM Signal s WHERE s.status IN ('PENDING', 'TRACKING') " +
            "AND s.executed = false " +
            "AND s.expirationTime <= :currentTime")
    List<Signal> findExpiredSignals(@Param("currentTime") LocalDateTime currentTime);

}