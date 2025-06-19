package com.tradingBot.model;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuoteResponse {
    private QuotesWrapper quotes;

    // Convenience method to get the quote directly
    public Quote getQuote() {
        return quotes != null && quotes.getQuote() != null ? quotes.getQuote() : null;
    }
}
