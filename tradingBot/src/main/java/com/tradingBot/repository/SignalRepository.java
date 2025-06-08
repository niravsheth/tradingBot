package com.tradingBot.repository;

import com.tradingBot.entity.Signal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SignalRepository extends JpaRepository<Signal, Long> {
    List<Signal> findByExecutedFalseAndTimestampAfter(LocalDateTime timestamp);
    List<Signal> findBySymbolAndTimestampAfter(String symbol, LocalDateTime timestamp);
}