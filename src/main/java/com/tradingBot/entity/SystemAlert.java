package com.tradingBot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "system_alerts")
@Data
public class SystemAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String type;
    private String severity;
    private String message;
    private Boolean resolved = false;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}