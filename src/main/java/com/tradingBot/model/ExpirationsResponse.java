package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExpirationsResponse {
    private ExpirationsWrapper expirations;

    public List<String> getExpirationDates() {
        return expirations != null && expirations.getDate() != null ?
                expirations.getDate() : new ArrayList<>();
    }

    public List<LocalDate> getExpirations() {
        return getExpirationDates().stream()
                .map(LocalDate::parse)
                .collect(Collectors.toList());
    }
}
