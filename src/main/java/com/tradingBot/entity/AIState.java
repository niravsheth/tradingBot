package com.tradingBot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_state")
@Data
public class AIState {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exploration_rate")
    private Double explorationRate;

    @Column(name = "total_trades")
    private Integer totalTrades;

    @Column(name = "successful_trades")
    private Integer successfulTrades;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    @Column(name = "learning_phase")
    private String learningPhase;

    @Column(name = "state_key")
    private String stateKey;

    @Column(name = "action_key")
    private String actionKey;

    @Column(name = "q_value")
    private Double qValue;

    @Column(name = "strategy_type")
    private String strategyType; // STANDARD, REVERSE

    @Column(name="metadata")
    private String metadata;

    @Column(name="performanceMetric")
    private Double performanceMetric;
}