package com.tradingBot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingBot.model.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@Slf4j
public class TradierService {
    private OkHttpClient client;
    private ObjectMapper objectMapper;

    @Value("${tradier.api.key}")
    private String apiKey;

    @Value("${tradier.api.base-url}")
    private String baseUrl;

    @Value("${tradier.api.account-id:}")
    private String accountId;

    @PostConstruct
    public void init() {
        this.client = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    public TradierService() {
        this.client = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public QuoteResponse getQuote(String symbol) {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/markets/quotes?symbols=" + symbol + "&greeks=true")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("Failed to fetch quote. Status: {}, Body: {}",
                        response.code(), responseBody);
                return null;
            }

            QuoteResponse quoteResponse = objectMapper.readValue(responseBody, QuoteResponse.class);
            log.debug("Fetched quote for {}: ${}", symbol,
                    quoteResponse.getQuote() != null ? quoteResponse.getQuote().getLast() : "null");
            return quoteResponse;
        } catch (Exception e) {
            log.error("Error fetching quote for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    public OptionChainResponse getOptionChain(String symbol, LocalDate expiration) {
        try {
            Request request = new Request.Builder()
                    .url(String.format("%s/markets/options/chains?symbol=%s&expiration=%s&greeks=true",
                            baseUrl, symbol, expiration))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("Failed to fetch option chain. Status: {}, Body: {}",
                        response.code(), responseBody);
                return null;
            }

            OptionChainResponse chainResponse = objectMapper.readValue(responseBody, OptionChainResponse.class);
            log.debug("Fetched {} options for {} expiring {}",
                    chainResponse.getOptionsList().size(), symbol, expiration);
            return chainResponse;
        } catch (Exception e) {
            log.error("Error fetching option chain: {}", e.getMessage());
            return null;
        }
    }

    public List<LocalDate> getExpirations(String symbol) {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/markets/options/expirations?symbol=" + symbol)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("Failed to fetch expirations. Status: {}, Body: {}",
                        response.code(), responseBody);
                return new ArrayList<>();
            }

            ExpirationsResponse expResponse = objectMapper.readValue(responseBody, ExpirationsResponse.class);
            return expResponse.getExpirations();
        } catch (Exception e) {
            log.error("Error fetching expirations: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public OrderResponse placeOrder(OrderRequest orderRequest) {
        try {
            // For options, use option_symbol instead of symbol
            if (orderRequest.getSymbol() != null && orderRequest.getSymbol().length() > 10) {
                orderRequest.setOptionSymbol(orderRequest.getSymbol());
                orderRequest.setSymbol(null);
                orderRequest.setOrderClass("option");
            }

            String jsonBody = objectMapper.writeValueAsString(orderRequest);
            log.debug("Placing order: {}", jsonBody);

            RequestBody body = RequestBody.create(
                    jsonBody,
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(baseUrl + "/accounts/" + accountId + "/orders")
                    .post(body)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("Failed to place order. Status: {}, Body: {}",
                        response.code(), responseBody);
                return null;
            }

            OrderResponse orderResponse = objectMapper.readValue(responseBody, OrderResponse.class);
            log.info("Order placed successfully: {}", orderResponse.getId());
            return orderResponse;
        } catch (Exception e) {
            log.error("Error placing order: {}", e.getMessage(), e);
            return null;
        }
    }
}