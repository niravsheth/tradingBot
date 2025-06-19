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

}