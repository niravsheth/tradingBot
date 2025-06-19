package com.tradingBot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.tradingBot.entity.Trade;
import com.tradingBot.model.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TradierService {

    private OkHttpClient client;
    private ObjectMapper objectMapper;

    @Value("${tradier.api.key}")
    private String apiKey;
    @Value("${tradier.api.base-url}")
    private String baseUrl;
    @Value("${tradier.api.account-id}")
    private String accountId;

    // Cache for quotes with expiration tracking
    private final Map<String, CachedQuote> quoteCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.client = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    // Scheduled task to update cache for QQQ every 5 seconds during trading hours
    @Scheduled(cron = "0/5 * 9-16 * * MON-FRI")
    public void updateQuoteCache() {
        String symbol = "QQQ";
        QuoteResponse qqqQuote = fetchQuote(symbol);
        if (qqqQuote != null) {
            quoteCache.put(symbol, new CachedQuote(qqqQuote, LocalDateTime.now()));
            log.info("Updated cache for {}: Last=${}", symbol, qqqQuote.getQuote().getLast());
        }
    }

    // Method to get quote, checking cache first
    public QuoteResponse getQuote(String symbol) {
        CachedQuote cached = quoteCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            log.debug("Using cached quote for {}: Last=${}", symbol, cached.getQuote().getQuote().getLast());
            return cached.getQuote();
        }
        // Fetch new quote if not in cache or expired
        QuoteResponse quoteResponse = fetchQuote(symbol);
        if (quoteResponse != null) {
            quoteCache.put(symbol, new CachedQuote(quoteResponse, LocalDateTime.now()));
        }
        return quoteResponse;
    }

    // Private method to perform actual API call for quotes
    private QuoteResponse fetchQuote(String symbol) {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/markets/quotes?symbols=" + symbol + "&greeks=true")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();
            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                log.error("Failed to fetch quote. Status: {}, Body: {}", response.code(), responseBody);
                return null;
            }
            QuoteResponse quoteResponse = objectMapper.readValue(responseBody, QuoteResponse.class);
            log.debug("Fetched quote for {}: ${}", symbol, quoteResponse.getQuote().getLast());
            return quoteResponse;
        } catch (Exception e) {
            log.error("Error fetching quote for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // Method to get option chain for a specific expiration date
    public OptionChainResponse getOptionChain(String symbol, LocalDate expiration) {
        try {
            String expirationStr = expiration.toString(); // Format: YYYY-MM-DD
            Request request = new Request.Builder()
                    .url(baseUrl + "/markets/options/chains?symbol=" + symbol + "&expiration=" + expirationStr + "&greeks=true")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();
            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                log.error("Failed to fetch option chain. Status: {}, Body: {}", response.code(), responseBody);
                return null;
            }
            OptionChainResponse chainResponse = objectMapper.readValue(responseBody, OptionChainResponse.class);
            log.debug("Fetched option chain for {} with expiration {}", symbol, expirationStr);
            return chainResponse;
        } catch (Exception e) {
            log.error("Error fetching option chain for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // Method to get available expirations for a symbol, without using ExpirationResponse
    public List<LocalDate> getExpirations(String symbol) {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/markets/options/expirations?symbol=" + symbol + "&includeAllRoots=true")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();
            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                log.error("Failed to fetch expirations. Status: {}, Body: {}", response.code(), responseBody);
                return new ArrayList<>();
            }
            // Parse JSON response directly
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode expirationsNode = rootNode.path("expirations").path("date");
            List<LocalDate> expirations = new ArrayList<>();
            if (expirationsNode.isArray()) {
                for (JsonNode dateNode : expirationsNode) {
                    String dateStr = dateNode.asText();
                    try {
                        LocalDate date = LocalDate.parse(dateStr);
                        expirations.add(date);
                    } catch (Exception e) {
                        log.warn("Failed to parse expiration date: {}", dateStr);
                    }
                }
            }
            log.debug("Fetched {} expirations for {}", expirations.size(), symbol);
            return expirations;
        } catch (Exception e) {
            log.error("Error fetching expirations for {}: {}", symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    // Method to place an order via Tradier API, accepting a single OrderRequest parameter
    public OrderResponse placeOrder(OrderRequest orderRequest) {
        try {
            // Log the entire OrderRequest object for debugging
            log.info("Attempting to place order with OrderRequest: {}", orderRequest);

            // Validate mandatory fields with null checks
            String optionSymbol = orderRequest.getSymbol();
            if (optionSymbol == null || optionSymbol.isEmpty()) {
                log.error("Symbol is null or empty in OrderRequest");
                return null;
            }

            // Extract underlying symbol from option symbol (e.g., QQQ from QQQ250611P00531000)
            String underlyingSymbol = extractUnderlyingSymbol(optionSymbol);
            if (underlyingSymbol == null || underlyingSymbol.isEmpty()) {
                log.error("Could not extract underlying symbol from option symbol: {}", optionSymbol);
                return null;
            }
            log.debug("Extracted underlying symbol: {} from option symbol: {}", underlyingSymbol, optionSymbol);

            String side = orderRequest.getSide();
            if (side == null || side.isEmpty()) {
                log.error("Side is null or empty in OrderRequest");
                return null;
            }

            // Validate side parameter for option orders
            if (!side.equals("buy_to_open") && !side.equals("buy_to_close") &&
                    !side.equals("sell_to_open") && !side.equals("sell_to_close")) {
                log.error("Invalid side value: {}. Valid values are buy_to_open, buy_to_close, sell_to_open, sell_to_close", side);
                return null;
            }

            int quantity = orderRequest.getQuantity();
            if (quantity <= 0) {
                log.error("Invalid quantity value: {}", quantity);
                return null;
            }

            String type = orderRequest.getType();
            if (type == null || type.isEmpty()) {
                log.warn("Type is null or empty in OrderRequest, defaulting to 'market'");
                type = "market";
            }

            String duration = orderRequest.getDuration();
            if (duration == null || duration.isEmpty()) {
                log.warn("Duration is null or empty in OrderRequest, defaulting to 'day'");
                duration = "day";
            }

            // Build the order payload from OrderRequest with null-safe checks
            FormBody.Builder formBuilder = new FormBody.Builder()
                    .add("class", "option")
                    .add("symbol", underlyingSymbol) // Underlying symbol (e.g., QQQ)
                    .add("option_symbol", optionSymbol) // Full OCC option symbol (e.g., QQQ250611P00531000)
                    .add("side", side)
                    .add("quantity", String.valueOf(quantity))
                    .add("type", type)
                    .add("duration", duration);

            // Add limit price if available and applicable
            try {
                BigDecimal limitPrice = null;
                // Check for common getter names for limit price
                try {
                    limitPrice = orderRequest.getPrice();
                } catch (Exception e) {
                    log.debug("No getPrice() method found or error retrieving limit price: {}", e.getMessage());
                }
                if (limitPrice != null && limitPrice.compareTo(BigDecimal.ZERO) > 0) {
                    formBuilder.add("price", limitPrice.toString());
                    log.debug("Added limit price: ${}", limitPrice);
                } else {
                    log.debug("Limit price not set or invalid, skipping price field");
                }
            } catch (Exception e) {
                log.warn("Failed to retrieve limit price from OrderRequest: {}", e.getMessage());
            }

            // Skip stop price since getStop() is not available as per user feedback
            log.debug("Stop price retrieval skipped as getStop() is not available");

            RequestBody requestBody = formBuilder.build();
            Request request = new Request.Builder()
                    .url(baseUrl + "/accounts/" + accountId + "/orders")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .post(requestBody)
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                log.error("Failed to place order. Status: {}, Body: {}", response.code(), responseBody);
                return null;
            }

            OrderResponse orderResponse = objectMapper.readValue(responseBody, OrderResponse.class);
            log.info("Successfully placed order for {}: Side={}, Quantity={}, Type={}",
                    optionSymbol, side, quantity, type);
            return orderResponse;
        } catch (Exception e) {
            log.error("Error placing order for {}: {}", orderRequest != null ? orderRequest.getSymbol() : "unknown", e.getMessage(), e);
            return null;
        }
    }

    // Helper method to extract underlying symbol from option symbol
    private String extractUnderlyingSymbol(String optionSymbol) {
        if (optionSymbol == null || optionSymbol.length() < 6) {
            return null;
        }
        // Option symbols are typically in the format SYMBOLYYMMDD[CP]STRIKE (e.g., QQQ250611P00531000)
        // Extract the part before the date (first 6 characters after symbol might be date)
        // We'll look for the first sequence of digits that looks like a date (YYMMDD)
        int dateStartIndex = -1;
        for (int i = 0; i < optionSymbol.length() - 5; i++) {
            if (Character.isDigit(optionSymbol.charAt(i)) &&
                    Character.isDigit(optionSymbol.charAt(i + 1)) &&
                    Character.isDigit(optionSymbol.charAt(i + 2)) &&
                    Character.isDigit(optionSymbol.charAt(i + 3)) &&
                    Character.isDigit(optionSymbol.charAt(i + 4)) &&
                    Character.isDigit(optionSymbol.charAt(i + 5))) {
                dateStartIndex = i;
                break;
            }
        }
        if (dateStartIndex > 0) {
            return optionSymbol.substring(0, dateStartIndex);
        }
        // Fallback: If no date pattern is found, return the first 3 characters (common for symbols like QQQ)
        if (optionSymbol.length() >= 3) {
            return optionSymbol.substring(0, 3);
        }
        return null;
    }

    // Inner class to store cached quote with timestamp
    private static class CachedQuote {
        private final QuoteResponse quote;
        private final LocalDateTime timestamp;

        public CachedQuote(QuoteResponse quote, LocalDateTime timestamp) {
            this.quote = quote;
            this.timestamp = timestamp;
        }

        public QuoteResponse getQuote() {
            return quote;
        }

        public boolean isExpired() {
            // Cache expires after 10 seconds
            return LocalDateTime.now().isAfter(timestamp.plusSeconds(10));
        }
    }
    // Add this method to get positions from Tradier
    public String getAccountPositions() {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/accounts/" + accountId + "/positions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("Failed to fetch positions. Status: {}, Body: {}", response.code(), responseBody);
                return null;
            }

            log.info("Positions from Tradier: {}", responseBody);
            return responseBody;
        } catch (Exception e) {
            log.error("Error fetching positions: {}", e.getMessage());
            return null;
        }
    }

    public BigDecimal getOptionPrice(String optionSymbol) {
        try {
            String optionUrl = baseUrl + "/markets/quotes?symbols=" + optionSymbol;

            Request request = new Request.Builder()
                    .url(optionUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (response.isSuccessful()) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode quote = root.path("quotes").path("quote");

                // Log what we got
                log.debug("Option quote for {}: {}", optionSymbol, quote);

                // Use 'last' price, fallback to 'bid' if not available
                if (quote.has("last") && !quote.get("last").isNull()) {
                    BigDecimal price = new BigDecimal(quote.get("last").asText());
                    log.info("Got price for {}: ${}", optionSymbol, price);
                    return price;
                } else if (quote.has("bid") && !quote.get("bid").isNull()) {
                    BigDecimal price = new BigDecimal(quote.get("bid").asText());
                    log.info("Got bid price for {}: ${}", optionSymbol, price);
                    return price;
                } else {
                    log.warn("No last or bid price for {}", optionSymbol);
                }
            } else {
                log.error("Failed to fetch option price. Status: {}, Body: {}",
                        response.code(), responseBody);
            }
        } catch (Exception e) {
            log.error("Error fetching option price for {}: {}", optionSymbol, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    public Map<String, Quote> getMultipleQuotes(String symbols) {
        Map<String, Quote> quotes = new HashMap<>();
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/markets/quotes?symbols=" + symbols + "&greeks=false")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("Failed to fetch multiple quotes. Status: {}, Body: {}",
                        response.code(), responseBody);
                return quotes;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode quotesNode = root.path("quotes").path("quote");

            if (quotesNode.isArray()) {
                // Multiple quotes returned
                for (JsonNode quoteNode : quotesNode) {
                    try {
                        Quote quote = objectMapper.treeToValue(quoteNode, Quote.class);
                        String symbol = quoteNode.path("symbol").asText();
                        quotes.put(symbol, quote);
                        log.debug("Parsed quote for {}: ${}", symbol, quote.getLast());
                    } catch (Exception e) {
                        log.error("Error parsing quote node: {}", e.getMessage());
                    }
                }
            } else if (!quotesNode.isMissingNode()) {
                // Single quote returned
                try {
                    Quote quote = objectMapper.treeToValue(quotesNode, Quote.class);
                    String symbol = quotesNode.path("symbol").asText();
                    quotes.put(symbol, quote);
                    log.debug("Parsed single quote for {}: ${}", symbol, quote.getLast());
                } catch (Exception e) {
                    log.error("Error parsing single quote: {}", e.getMessage());
                }
            }

            log.info("Fetched {} quotes in batch for: {}", quotes.size(), symbols);

        } catch (Exception e) {
            log.error("Error fetching multiple quotes for {}: {}", symbols, e.getMessage());

            // Fallback to individual calls if batch fails
            try {
                log.info("Falling back to individual quote calls");
                for (String symbol : symbols.split(",")) {
                    symbol = symbol.trim();
                    QuoteResponse response = getQuote(symbol);
                    if (response != null && response.getQuote() != null) {
                        quotes.put(symbol, response.getQuote());
                    }
                }
            } catch (Exception fallbackError) {
                log.error("Fallback quote fetching also failed: {}", fallbackError.getMessage());
            }
        }

        return quotes;
    }
}
