package com.tradingBot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.tradingBot.model.*;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TradierService {

    private OkHttpClient client;
    private ObjectMapper objectMapper;

    private static final Map<String, Long> apiCallTimes = new ConcurrentHashMap<>();

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
            //log.info("Updated cache for {}: Last=${}", symbol, qqqQuote.getQuote().getLast());
        }
    }

    // Method to get quote, checking cache first
    public QuoteResponse getQuote(String symbol) {
        long startTime = System.currentTimeMillis();
        CachedQuote cached = quoteCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            //log.debug("Using cached quote for {}: Last=${}", symbol, cached.getQuote().getQuote().getLast());
            return cached.getQuote();
        }
        // Fetch new quote if not in cache or expired
        QuoteResponse quoteResponse = fetchQuote(symbol);
        if (quoteResponse != null) {
            quoteCache.put(symbol, new CachedQuote(quoteResponse, LocalDateTime.now()));
        }
        long duration = System.currentTimeMillis() - startTime;
        apiCallTimes.put("quote_" + System.currentTimeMillis(), duration);

        if (duration > 1000) { // More than 1 second
            log.warn("[PERF] Slow quote fetch for {}: {}ms", symbol, duration);
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
            //log.debug("Fetched option chain for {} with expiration {}", symbol, expirationStr);
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
            //log.debug("Fetched {} expirations for {}", expirations.size(), symbol);
            return expirations;
        } catch (Exception e) {
            log.error("Error fetching expirations for {}: {}", symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    // Method to place an order via Tradier API, accepting a single OrderRequest parameter
    public OrderResponse placeOrder(OrderRequest orderRequest) {
        String trackingId = UUID.randomUUID().toString().substring(0, 8);

        try {
            log.info("[ORDER][{}] Attempting to place order with OrderRequest: {}", trackingId, orderRequest);

            // Validate mandatory fields with null checks
            String optionSymbol = orderRequest.getSymbol();
            if (optionSymbol == null || optionSymbol.isEmpty()) {
                log.error("[ORDER][{}] Symbol is null or empty in OrderRequest", trackingId);
                return null;
            }

            String underlyingSymbol = extractUnderlyingSymbol(optionSymbol);
            if (underlyingSymbol == null || underlyingSymbol.isEmpty()) {
                log.error("[ORDER][{}] Could not extract underlying symbol from option symbol: {}", trackingId, optionSymbol);
                return null;
            }
            log.debug("[ORDER][{}] Extracted underlying symbol: {} from option symbol: {}", trackingId, underlyingSymbol, optionSymbol);

            String side = orderRequest.getSide();
            if (side == null || side.isEmpty()) {
                log.error("[ORDER][{}] Side is null or empty in OrderRequest", trackingId);
                return null;
            }

            if (!side.equals("buy_to_open") && !side.equals("buy_to_close") &&
                    !side.equals("sell_to_open") && !side.equals("sell_to_close")) {
                log.error("[ORDER][{}] Invalid side value: {}. Valid values are buy_to_open, buy_to_close, sell_to_open, sell_to_close", trackingId, side);
                return null;
            }

            int quantity = orderRequest.getQuantity();
            if (quantity <= 0) {
                log.error("[ORDER][{}] Invalid quantity value: {}", trackingId, quantity);
                return null;
            }

            String type = orderRequest.getType();
            if (type == null || type.isEmpty()) {
                log.warn("[ORDER][{}] Type is null or empty in OrderRequest, defaulting to 'market'", trackingId);
                type = "market";
            }

            String duration = orderRequest.getDuration();
            if (duration == null || duration.isEmpty()) {
                log.warn("[ORDER][{}] Duration is null or empty in OrderRequest, defaulting to 'day'", trackingId);
                duration = "day";
            }

            FormBody.Builder formBuilder = new FormBody.Builder()
                    .add("class", "option")
                    .add("symbol", underlyingSymbol)
                    .add("option_symbol", optionSymbol)
                    .add("side", side)
                    .add("quantity", String.valueOf(quantity))
                    .add("type", type)
                    .add("duration", duration);

            try {
                BigDecimal limitPrice = orderRequest.getPrice();
                if (limitPrice != null && limitPrice.compareTo(BigDecimal.ZERO) > 0) {
                    formBuilder.add("price", limitPrice.toString());
                    log.debug("[ORDER][{}] Added limit price: ${}", trackingId, limitPrice);
                }
            } catch (Exception e) {
                log.warn("[ORDER][{}] Failed to retrieve limit price from OrderRequest: {}", trackingId, e.getMessage());
            }

            RequestBody requestBody = formBuilder.build();
            Request request = new Request.Builder()
                    .url(baseUrl + "/accounts/" + accountId + "/ordersss")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                   .post(requestBody)
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("[ORDER][{}] Failed to place order. Status: {}, Body: {}", trackingId, response.code(), responseBody);
                return null;
            }

            OrderResponse orderResponse = objectMapper.readValue(responseBody, OrderResponse.class);

            if (orderResponse == null) {
                log.error("[ORDER][{}] Null order response from Tradier", trackingId);
                return null;
            }

            if ("error".equalsIgnoreCase(orderResponse.getStatus())) {
                log.error("[ORDER][{}] Order rejected by Tradier: Status = {}", trackingId, orderResponse.getStatus());
                return null;
            }

            if (orderResponse.getOrder() == null) {
                log.error("[ORDER][{}] No order object in response", trackingId);
                return null;
            }

            String orderId = orderResponse.getId();
            if (orderId == null || orderId.trim().isEmpty()) {
                log.error("[ORDER][{}] No valid order ID in response", trackingId);
                return null;
            }

            if ("market".equalsIgnoreCase(type)) {
                Thread.sleep(1000);
                String orderStatus = getOrderStatus(orderId);
                if (!isOrderStatusValid(orderStatus)) {
                    log.error("[ORDER][{}] Market order not properly accepted. Status: {}", trackingId, orderStatus);
                    return null;
                }
            }

            log.info("[ORDER][{}] ✅ Successfully placed order for {}: Side={}, Quantity={}, Order ID={}",
                    trackingId, optionSymbol, side, quantity, orderId);
            return orderResponse;
        }
        catch (Exception e) {
            log.error("[ORDER][{}] ❌ Error placing order for {}: {}", trackingId,
                    orderRequest != null ? orderRequest.getSymbol() : "unknown", e.getMessage(), e);
            return null;
        }
    }

//    private String getOrderStatus(String orderId) {
//        try {
//            Request request = new Request.Builder()
//                    .url(baseUrl + "/accounts/" + accountId + "/orders/" + orderId)
//                    .addHeader("Authorization", "Bearer " + apiKey)
//                    .addHeader("Accept", "application/json")
//                    .build();
//
//            Response response = client.newCall(request).execute();
//            String responseBody = response.body().string();
//
//            if (response.isSuccessful()) {
//                JsonNode rootNode = objectMapper.readTree(responseBody);
//                JsonNode orderNode = rootNode.path("order");
//
//                return orderNode.path("status").asText("unknown");
//            }
//
//        } catch (Exception e) {
//            log.error("Error getting order status for {}: {}", orderId, e.getMessage());
//        }
//        return "unknown";
//    }

    private boolean isOrderStatusValid(String status) {
        if (status == null) return false;
        String lowerStatus = status.toLowerCase();
        return Arrays.asList("pending", "submitted", "accepted", "open", "filled", "partially_filled")
                .contains(lowerStatus);
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
            return LocalDateTime.now().isAfter(timestamp.plusSeconds(2));
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

            //log.info("Fetched {} quotes in batch for: {}", quotes.size(), symbols);

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

    public PositionsResponse getPositions() {
        try {
            String url = baseUrl + "/accounts/" + accountId + "/positions";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Accept", "application/json");

            Request request = new Request.Builder()
                    .url(baseUrl + "/accounts/" + accountId + "/positions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();


            if (response.isSuccessful()) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.body().string());
                JsonNode positionsNode = root.path("positions").path("position");

                PositionsResponse positionsResponse = new PositionsResponse();
                List<Position> positions = new ArrayList<>();

                if (positionsNode.isArray()) {
                    for (JsonNode posNode : positionsNode) {
                        Position position = parsePosition(posNode);
                        positions.add(position);
                    }
                } else if (!positionsNode.isMissingNode()) {
                    Position position = parsePosition(positionsNode);
                    positions.add(position);
                }

                positionsResponse.setPositions(positions);
                return positionsResponse;
            }

        } catch (Exception e) {
            log.error("Error fetching positions: {}", e.getMessage());
        }

        return null;
    }

    private Position parsePosition(JsonNode node) {
        Position position = new Position();
        position.setSymbol(node.path("symbol").asText());
        position.setQuantity(node.path("quantity").asInt());
        position.setCostBasis(new BigDecimal(node.path("cost_basis").asText("0")));
        position.setMarketValue(new BigDecimal(node.path("market_value").asText("0")));
        position.setUnrealizedPl(new BigDecimal(node.path("unrealized_pl").asText("0")));
        position.setUnrealizedPlPercent(new BigDecimal(node.path("unrealized_pl_percent").asText("0")));
        return position;
    }

    public OrderStatusResponse getOrderStatusDetailed(String orderId) {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/accounts/" + accountId + "/orders/" + orderId)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (response.isSuccessful()) {
                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode orderNode = rootNode.path("order");

                if (!orderNode.isMissingNode()) {
                    OrderStatusResponse orderStatus = new OrderStatusResponse();
                    orderStatus.setOrderId(orderId);
                    orderStatus.setStatus(orderNode.path("status").asText("unknown"));
                    orderStatus.setSymbol(orderNode.path("symbol").asText());
                    orderStatus.setSide(orderNode.path("side").asText());
                    orderStatus.setType(orderNode.path("type").asText());

                    // Parse original order details
                    if (orderNode.has("price") && !orderNode.path("price").isNull()) {
                        orderStatus.setOriginalPrice(new BigDecimal(orderNode.path("price").asText()));
                    }
                    orderStatus.setOriginalQuantity(orderNode.path("quantity").asInt());

                    // Parse fill information from legs (for options)
                    JsonNode legNode = orderNode.path("leg");
                    if (legNode.isArray() && legNode.size() > 0) {
                        JsonNode firstLeg = legNode.get(0);

                        // Check for executions/fills
                        JsonNode executionsNode = firstLeg.path("executions");
                        if (executionsNode.isArray() && executionsNode.size() > 0) {
                            JsonNode firstExecution = executionsNode.get(0);

                            double execPrice = firstExecution.path("price").asDouble();
                            int execQuantity = firstExecution.path("quantity").asInt();

                            orderStatus.setFillPrice(BigDecimal.valueOf(execPrice));
                            orderStatus.setFillQuantity(execQuantity);

                            // Parse execution timestamp
                            String execTimestamp = firstExecution.path("timestamp").asText();
                            if (!execTimestamp.isEmpty()) {
                                try {
                                    // Adjust timestamp parsing based on Tradier's format
                                    orderStatus.setFillTime(LocalDateTime.parse(execTimestamp.substring(0, 19)));
                                } catch (Exception e) {
                                    log.debug("Could not parse execution timestamp: {}", execTimestamp);
                                }
                            }
                        }
                    }

                    log.debug("Parsed order status for {}: Status={}, FillPrice={}",
                            orderId, orderStatus.getStatus(), orderStatus.getFillPrice());

                    return orderStatus;
                }
            } else {
                log.warn("Failed to get order status for {}: HTTP {}", orderId, response.code());
            }

        } catch (Exception e) {
            log.error("Error getting detailed order status for {}: {}", orderId, e.getMessage());
        }

        // Return basic status response if detailed parsing fails
        return new OrderStatusResponse("unknown", orderId);
    }

    /**
     * Get positions as a list for easier processing
     */
    public List<Position> getPositionsList() {
        try {
            PositionsResponse positionsResponse = getPositions();
            if (positionsResponse != null && positionsResponse.getPositions() != null) {
                return positionsResponse.getPositions();
            }
        } catch (Exception e) {
            log.error("Error getting positions list: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Check if a specific position exists
     */
    public Position getPositionBySymbol(String symbol) {
        try {
            List<Position> positions = getPositionsList();
            return positions.stream()
                    .filter(pos -> symbol.equals(pos.getSymbol()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.error("Error getting position for symbol {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Enhanced order placement with better error handling and tracking
     */
    public OrderResponse placeOrderWithTracking(OrderRequest orderRequest, String trackingId) {
        try {
            log.info("[ORDER][{}] Placing order: Symbol={}, Side={}, Quantity={}, Type={}",
                    trackingId, orderRequest.getSymbol(), orderRequest.getSide(),
                    orderRequest.getQuantity(), orderRequest.getType());

            OrderResponse response = placeOrder(orderRequest);

            if (response != null && response.getOrder() != null) {
                String orderId = response.getId();
                log.info("[ORDER][{}] Order placed successfully: OrderID={}", trackingId, orderId);

                // For market orders, wait a moment and check status
                if ("market".equalsIgnoreCase(orderRequest.getType())) {
                    try {
                        Thread.sleep(500); // Wait 0.5 seconds
                        OrderStatusResponse status = getOrderStatusDetailed(orderId);
                        log.info("[ORDER][{}] Market order status check: Status={}, Symbol={}",
                                trackingId, status.getStatus(), status.getSymbol());
                    } catch (Exception e) {
                        log.warn("[ORDER][{}] Could not check immediate order status: {}", trackingId, e.getMessage());
                    }
                }

                return response;
            } else {
                log.error("[ORDER][{}] Order placement failed: Response was null or invalid", trackingId);
                return null;
            }

        } catch (Exception e) {
            log.error("[ORDER][{}] Exception during order placement: {}", trackingId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Cancel an order by ID
     */
    public boolean cancelOrder(String orderId) {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/accounts/" + accountId + "/orders/" + orderId)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .delete()
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (response.isSuccessful()) {
                log.info("Successfully cancelled order: {}", orderId);
                return true;
            } else {
                log.warn("Failed to cancel order {}: HTTP {}, Body: {}", orderId, response.code(), responseBody);
                return false;
            }

        } catch (Exception e) {
            log.error("Error cancelling order {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    /**
     * Get all open orders
     */
    public List<OrderStatusResponse> getOpenOrders() {
        List<OrderStatusResponse> openOrders = new ArrayList<>();

        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/accounts/" + accountId + "/orders")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (response.isSuccessful()) {
                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode ordersNode = rootNode.path("orders").path("order");

                if (ordersNode.isArray()) {
                    for (JsonNode orderNode : ordersNode) {
                        String status = orderNode.path("status").asText();
                        if ("open".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status)) {
                            OrderStatusResponse orderStatus = new OrderStatusResponse();
                            orderStatus.setOrderId(orderNode.path("id").asText());
                            orderStatus.setStatus(status);
                            orderStatus.setSymbol(orderNode.path("symbol").asText());
                            orderStatus.setSide(orderNode.path("side").asText());
                            orderStatus.setType(orderNode.path("type").asText());
                            orderStatus.setOriginalQuantity(orderNode.path("quantity").asInt());

                            openOrders.add(orderStatus);
                        }
                    }
                }

                log.debug("Found {} open orders", openOrders.size());

            } else {
                log.warn("Failed to get orders: HTTP {}", response.code());
            }

        } catch (Exception e) {
            log.error("Error getting open orders: {}", e.getMessage());
        }

        return openOrders;
    }

    // Update the existing getOrderStatus method to be backward compatible
    public String getOrderStatus(String orderId) {
        try {
            OrderStatusResponse detailedStatus = getOrderStatusDetailed(orderId);
            return detailedStatus != null ? detailedStatus.getStatus() : "unknown";
        } catch (Exception e) {
            log.error("Error getting order status for {}: {}", orderId, e.getMessage());
            return "unknown";
        }
    }



    /**
     * Container for historical daily bar data
     */
    @Data
    public static class HistoricalBar {
        private LocalDate date;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private Long volume;
    }

    /**
     * Fetch historical daily bar for a specific date from Tradier API
     * Used by DailyLevelService to get previous day's high/low/close
     *
     * @param symbol Stock symbol (e.g., "QQQ")
     * @param date   Date to fetch data for
     * @return HistoricalBar with OHLCV data, or null if not available
     */
    public HistoricalBar getHistoricalDaily(String symbol, LocalDate date) {
        String trackingId = UUID.randomUUID().toString().substring(0, 8);

        log.info("[HISTORICAL][{}] Fetching daily bar for {} on {}", trackingId, symbol, date);

        try {
            // Tradier history endpoint: /markets/history
            // Parameters: symbol, interval=daily, start=date, end=date
            String url = String.format("%s/markets/history?symbol=%s&interval=daily&start=%s&end=%s",
                    baseUrl, symbol, date.toString(), date.toString());

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            log.debug("[HISTORICAL][{}] API URL: {}", trackingId, url);

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("[HISTORICAL][{}] ✗ API request failed. Status: {}, Body: {}",
                        trackingId, response.code(), responseBody);
                return null;
            }

            log.debug("[HISTORICAL][{}] API response: {}", trackingId, responseBody);

            // Parse JSON response
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode historyNode = rootNode.path("history");

            if (historyNode.isMissingNode() || historyNode.isNull()) {
                log.warn("[HISTORICAL][{}] ✗ No history data in response for {} on {}",
                        trackingId, symbol, date);
                return null;
            }

            JsonNode dayNode = historyNode.path("day");

            // Handle both single object and array response
            JsonNode barNode;
            if (dayNode.isArray() && dayNode.size() > 0) {
                barNode = dayNode.get(0);
            } else if (dayNode.isObject()) {
                barNode = dayNode;
            } else {
                log.warn("[HISTORICAL][{}] ✗ No day data found for {} on {}",
                        trackingId, symbol, date);
                return null;
            }

            // Extract OHLCV data
            HistoricalBar bar = new HistoricalBar();
            bar.setDate(date);
            bar.setOpen(extractBigDecimal(barNode, "open"));
            bar.setHigh(extractBigDecimal(barNode, "high"));
            bar.setLow(extractBigDecimal(barNode, "low"));
            bar.setClose(extractBigDecimal(barNode, "close"));
            bar.setVolume(barNode.has("volume") ? barNode.get("volume").asLong() : 0L);

            // Validate data
            if (bar.getOpen() == null || bar.getHigh() == null ||
                    bar.getLow() == null || bar.getClose() == null) {
                log.error("[HISTORICAL][{}] ✗ Incomplete bar data for {} on {}: O={}, H={}, L={}, C={}",
                        trackingId, symbol, date, bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose());
                return null;
            }

            log.info("[HISTORICAL][{}] ✓ Successfully fetched daily bar for {} on {}", trackingId, symbol, date);
            log.info("[HISTORICAL][{}]   Open:   ${}", trackingId, bar.getOpen());
            log.info("[HISTORICAL][{}]   High:   ${}", trackingId, bar.getHigh());
            log.info("[HISTORICAL][{}]   Low:    ${}", trackingId, bar.getLow());
            log.info("[HISTORICAL][{}]   Close:  ${}", trackingId, bar.getClose());
            log.info("[HISTORICAL][{}]   Volume: {}", trackingId, bar.getVolume());

            return bar;

        } catch (Exception e) {
            log.error("[HISTORICAL][{}] ✗ Error fetching historical data for {} on {}: {}",
                    trackingId, symbol, date, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Helper method to extract BigDecimal from JSON node
     */
    private BigDecimal extractBigDecimal(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName)) {
            return null;
        }
        JsonNode valueNode = node.get(fieldName);
        if (valueNode.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(valueNode.asText()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("Failed to parse {} as BigDecimal: {}", fieldName, valueNode.asText());
            return null;
        }
    }

    /**
     * Fetch multiple days of historical data
     * Useful for backtesting or calculating multi-day levels
     *
     * @param symbol Stock symbol
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return List of HistoricalBar objects
     */
    public List<HistoricalBar> getHistoricalDailyRange(String symbol, LocalDate startDate, LocalDate endDate) {
        String trackingId = UUID.randomUUID().toString().substring(0, 8);
        List<HistoricalBar> bars = new ArrayList<>();

        log.info("[HISTORICAL][{}] Fetching daily bars for {} from {} to {}",
                trackingId, symbol, startDate, endDate);

        try {
            String url = String.format("%s/markets/history?symbol=%s&interval=daily&start=%s&end=%s",
                    baseUrl, symbol, startDate.toString(), endDate.toString());

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("[HISTORICAL][{}] ✗ API request failed. Status: {}", trackingId, response.code());
                return bars;
            }

            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode dayNode = rootNode.path("history").path("day");

            if (dayNode.isArray()) {
                for (JsonNode barNode : dayNode) {
                    HistoricalBar bar = new HistoricalBar();
                    String dateStr = barNode.has("date") ? barNode.get("date").asText() : null;
                    if (dateStr != null) {
                        bar.setDate(LocalDate.parse(dateStr));
                    }
                    bar.setOpen(extractBigDecimal(barNode, "open"));
                    bar.setHigh(extractBigDecimal(barNode, "high"));
                    bar.setLow(extractBigDecimal(barNode, "low"));
                    bar.setClose(extractBigDecimal(barNode, "close"));
                    bar.setVolume(barNode.has("volume") ? barNode.get("volume").asLong() : 0L);

                    if (bar.getOpen() != null && bar.getClose() != null) {
                        bars.add(bar);
                    }
                }
            }

            log.info("[HISTORICAL][{}] ✓ Fetched {} daily bars for {}", trackingId, bars.size(), symbol);

        } catch (Exception e) {
            log.error("[HISTORICAL][{}] ✗ Error fetching historical range: {}", trackingId, e.getMessage());
        }

        return bars;
    }

}
