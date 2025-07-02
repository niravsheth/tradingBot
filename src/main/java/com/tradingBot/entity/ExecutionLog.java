package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "execution_logs")
@Data
public class ExecutionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long signalId;
    private String status;
    private String details;
    private LocalDateTime executionTime;
}