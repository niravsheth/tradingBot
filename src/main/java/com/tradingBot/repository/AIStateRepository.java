
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
public interface AIStateRepository extends JpaRepository<AIState, Long> {

    @Query("SELECT a FROM AIState a WHERE a.stateKey = :stateKey AND a.actionKey = :actionKey")
    Optional<AIState> findByStateAndAction(@Param("stateKey") String stateKey, @Param("actionKey") String actionKey);

    @Query("SELECT a FROM AIState a WHERE a.strategyType = :strategyType ORDER BY a.lastUpdated DESC")
    List<AIState> findByStrategyTypeOrderByLastUpdatedDesc(@Param("strategyType") String strategyType);

    @Query("SELECT a FROM AIState a WHERE a.lastUpdated >= :since")
    List<AIState> findRecentStates(@Param("since") LocalDateTime since);

    Optional<AIState> findByStateKeyAndActionKey(String stateKey, String actionKey);
}
