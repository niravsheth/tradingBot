package com.tradingBot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingBot.entity.Trade;
import com.tradingBot.model.Position;
import com.tradingBot.model.PositionsResponse;
import com.tradingBot.model.Quote;
import com.tradingBot.model.QuoteResponse;
import com.tradingBot.repository.TradeRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PositionSyncService {

    private final TradierService tradierService;
    private final TradeRepository tradeRepository;
    private final TelegramService telegramService;

    // Cache for API positions with TTL
    private final Map<String, CachedPosition> positionCache = new ConcurrentHashMap<>();
    private static final int CACHE_TTL_SECONDS = 30;

    // Position state
    @Data
    public static class BrokerPosition {
        private String symbol;
        private int quantity;
        private BigDecimal avgCost;
        private BigDecimal currentPrice;
        private BigDecimal marketValue;
        private BigDecimal pnl;
        private BigDecimal pnlPercent;
        private LocalDateTime lastUpdated;
        private String type; // "long" or "short"

        public int getAbsoluteQuantity() {
            return Math.abs(quantity);
        }
    }

    @Data
    private static class CachedPosition {
        private BrokerPosition position;
        private LocalDateTime cachedAt;

        boolean isExpired() {
            return LocalDateTime.now().minusSeconds(CACHE_TTL_SECONDS).isAfter(cachedAt);
        }
    }

    /**
     * Get current positions from Tradier API with caching
     */
    public Map<String, BrokerPosition> getCurrentPositions(boolean forceRefresh) {
        // Check cache first
        if (!forceRefresh && !positionCache.isEmpty()) {
            // Remove expired entries
            positionCache.entrySet().removeIf(entry -> entry.getValue().isExpired());

            // If we still have valid cached data, return it
            if (!positionCache.isEmpty()) {
                return positionCache.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().getPosition()
                        ));
            }
        }

        // Fetch fresh data from API
        Map<String, BrokerPosition> positions = fetchPositionsFromAPI();

        // Update cache
        LocalDateTime now = LocalDateTime.now();
        positions.forEach((symbol, position) -> {
            CachedPosition cached = new CachedPosition();
            cached.setPosition(position);
            cached.setCachedAt(now);
            positionCache.put(symbol, cached);
        });

        return positions;
    }

    /**
     * Fetch positions from Tradier API
     */
    private Map<String, BrokerPosition> fetchPositionsFromAPI() {
        Map<String, BrokerPosition> positions = new HashMap<>();

        try {
            // Try the direct API method first
            String positionsJson = tradierService.getAccountPositions();
            if (positionsJson != null) {
                positions = parsePositionsFromJson(positionsJson);
            } else {
                // Fallback to the typed response method
                PositionsResponse response = tradierService.getPositions();
                if (response != null && response.getPositions() != null) {
                    positions = convertPositionsResponse(response);
                }
            }

            // Update current prices
            if (!positions.isEmpty()) {
                updateCurrentPrices(positions);
            }

            log.info("[SYNC] Retrieved {} option positions from broker", positions.size());

        } catch (Exception e) {
            log.error("[SYNC] Error fetching positions from API: {}", e.getMessage(), e);
        }

        return positions;
    }

    private Map<String, BrokerPosition> parsePositionsFromJson(String positionsJson) {
        Map<String, BrokerPosition> positions = new HashMap<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(positionsJson);
            JsonNode positionsNode = root.path("positions").path("position");

            // Handle single position (object) or multiple positions (array)
            List<JsonNode> positionsList = new ArrayList<>();
            if (positionsNode.isArray()) {
                positionsNode.forEach(positionsList::add);
            } else if (!positionsNode.isMissingNode()) {
                positionsList.add(positionsNode);
            }

            for (JsonNode pos : positionsList) {
                String symbol = pos.path("symbol").asText();

                // Skip non-option positions
                if (!symbol.contains("C") && !symbol.contains("P")) {
                    continue;
                }

                BrokerPosition position = new BrokerPosition();
                position.setSymbol(symbol);
                position.setQuantity(pos.path("quantity").asInt());

                BigDecimal costBasis = new BigDecimal(pos.path("cost_basis").asDouble());
                position.setAvgCost(costBasis.divide(
                        new BigDecimal(Math.abs(position.getQuantity() * 100)),
                        2,
                        RoundingMode.HALF_UP
                ));

                position.setType(position.getQuantity() > 0 ? "long" : "short");
                position.setLastUpdated(LocalDateTime.now());

                positions.put(symbol, position);
            }
        } catch (Exception e) {
            log.error("[SYNC] Error parsing positions JSON: {}", e.getMessage());
        }

        return positions;
    }

    private Map<String, BrokerPosition> convertPositionsResponse(PositionsResponse response) {
        Map<String, BrokerPosition> positions = new HashMap<>();

        for (Position pos : response.getPositions()) {
            // Only process option positions
            if (pos.getSymbol().contains("C") || pos.getSymbol().contains("P")) {
                BrokerPosition brokerPos = new BrokerPosition();
                brokerPos.setSymbol(pos.getSymbol());
                brokerPos.setQuantity(pos.getQuantity());
                brokerPos.setAvgCost(pos.getCostBasis().divide(
                        BigDecimal.valueOf(Math.abs(pos.getQuantity()) * 100),
                        2,
                        RoundingMode.HALF_UP
                ));
                brokerPos.setMarketValue(pos.getMarketValue());
                brokerPos.setPnl(pos.getUnrealizedPl());
                brokerPos.setPnlPercent(pos.getUnrealizedPlPercent());
                brokerPos.setType(pos.getQuantity() > 0 ? "long" : "short");
                brokerPos.setLastUpdated(LocalDateTime.now());

                positions.put(pos.getSymbol(), brokerPos);
            }
        }

        return positions;
    }

    private void updateCurrentPrices(Map<String, BrokerPosition> positions) {
        try {
            // Batch quote request
            String symbols = String.join(",", positions.keySet());
            Map<String, Quote> quotes = tradierService.getMultipleQuotes(symbols);

            for (Map.Entry<String, BrokerPosition> entry : positions.entrySet()) {
                Quote quote = quotes.get(entry.getKey());
                if (quote != null && quote.getBid() != null) {
                    BrokerPosition pos = entry.getValue();
                    // Use bid for conservative P&L calculation
                    pos.setCurrentPrice(quote.getBid());

                    // Recalculate P&L with fresh price
                    BigDecimal pnl = pos.getCurrentPrice().subtract(pos.getAvgCost())
                            .multiply(BigDecimal.valueOf(Math.abs(pos.getQuantity()) * 100));
                    pos.setPnl(pnl);

                    BigDecimal pnlPercent = pnl.divide(
                            pos.getAvgCost().multiply(BigDecimal.valueOf(Math.abs(pos.getQuantity()) * 100)),
                            4, RoundingMode.HALF_UP);
                    pos.setPnlPercent(pnlPercent);
                }
            }
        } catch (Exception e) {
            log.error("[SYNC] Error updating current prices: {}", e.getMessage());
        }
    }

    /**
     * Check if a specific position exists at broker
     */
    public boolean hasPosition(String optionSymbol) {
        Map<String, BrokerPosition> positions = getCurrentPositions(false);
        return positions.containsKey(optionSymbol);
    }

    /**
     * Get specific position details
     */
    public BrokerPosition getPosition(String optionSymbol) {
        Map<String, BrokerPosition> positions = getCurrentPositions(false);
        return positions.get(optionSymbol);
    }

    /**
     * Force refresh all positions (called after trade execution)
     */
    public void refreshPositions() {
        log.info("[SYNC] Force refreshing positions");
        getCurrentPositions(true);
    }

    /**
     * Scheduled sync - runs every 5 minutes
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 10000) // 5 minutes
    @Transactional
    public void syncPositionsWithDatabase() {
        log.info("[SYNC] Starting position sync with Tradier API");

        try {
            // Get positions from API
            Map<String, BrokerPosition> brokerPositions = getCurrentPositions(true);

            // Get open positions from database
            List<Trade> dbTrades = tradeRepository.findByStatus("OPEN");
            Map<String, Trade> dbPositionMap = dbTrades.stream()
                    .collect(Collectors.toMap(
                            trade -> trade.getOptionSymbol().replace("PAPER_", ""),
                            trade -> trade,
                            (t1, t2) -> t1 // Handle duplicates by keeping first
                    ));

            // 1. Check for positions at broker but not in DB
            for (Map.Entry<String, BrokerPosition> entry : brokerPositions.entrySet()) {
                String symbol = entry.getKey();
                BrokerPosition brokerPos = entry.getValue();

                if (!dbPositionMap.containsKey(symbol)) {
                    log.warn("[SYNC] Found untracked position at broker: {}", symbol);
                    createTradeFromBrokerPosition(brokerPos);
                }
            }

            // 2. Check for positions in DB but not at broker
            for (Map.Entry<String, Trade> entry : dbPositionMap.entrySet()) {
                String symbol = entry.getKey();
                Trade trade = entry.getValue();

                // Skip paper trades
                if (trade.getOptionSymbol().startsWith("PAPER_")) {
                    continue;
                }

                if (!brokerPositions.containsKey(symbol)) {
                    log.warn("[SYNC] Position {} in DB but not at broker - marking as CLOSED", symbol);
                    trade.setStatus("CLOSED");
                    trade.setExitTime(LocalDateTime.now());
                    trade.setCloseReason("Position not found at broker");
                    tradeRepository.save(trade);

                    telegramService.sendMessage(String.format(
                            "⚠️ Position %s was closed outside the bot", symbol
                    ));
                }
            }

            // 3. Update P&L for existing positions
            for (Map.Entry<String, Trade> entry : dbPositionMap.entrySet()) {
                String symbol = entry.getKey();
                Trade trade = entry.getValue();
                BrokerPosition brokerPos = brokerPositions.get(symbol);

                if (brokerPos != null) {
                    // Update current P&L
                    trade.setUnrealizedPnl(brokerPos.getPnl());
                    trade.setLastSyncTime(LocalDateTime.now());
                    tradeRepository.save(trade);
                }
            }

            log.info("[SYNC] Position sync complete. {} positions at broker, {} in database",
                    brokerPositions.size(), dbPositionMap.size());

        } catch (Exception e) {
            log.error("[SYNC] Error during position sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Create a trade record from broker position
     */
    private Trade createTradeFromBrokerPosition(BrokerPosition brokerPos) {
        Trade trade = new Trade();
        trade.setSymbol("QQQ");
        trade.setOptionSymbol(brokerPos.getSymbol());
        trade.setType(brokerPos.getSymbol().contains("C") ? "CALL" : "PUT");
        trade.setAction("BUY");
        trade.setQuantity(Math.abs(brokerPos.getQuantity()));
        trade.setEntryPrice(brokerPos.getAvgCost());
        trade.setStatus("OPEN");
        trade.setEntryTime(LocalDateTime.now());
        trade.setStrategy("EXTERNAL_POSITION");
        trade.setUnrealizedPnl(brokerPos.getPnl());
        trade.setLastSyncTime(LocalDateTime.now());

        // Set conservative exit targets
        trade.setTargetPrice(brokerPos.getAvgCost().multiply(new BigDecimal("1.30"))); // 30% target
        trade.setStopLoss(brokerPos.getAvgCost().multiply(new BigDecimal("0.70"))); // 30% stop

        tradeRepository.save(trade);

        telegramService.sendMessage(String.format(
                "📍 External position detected and tracked:\n" +
                        "Option: %s\n" +
                        "Quantity: %d\n" +
                        "Avg Cost: $%.2f\n" +
                        "Current P&L: $%.2f (%.1f%%)",
                brokerPos.getSymbol(),
                brokerPos.getQuantity(),
                brokerPos.getAvgCost(),
                brokerPos.getPnl(),
                brokerPos.getPnlPercent().multiply(new BigDecimal(100)).doubleValue()
        ));

        return trade;
    }
}