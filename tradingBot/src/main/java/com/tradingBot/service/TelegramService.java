package com.tradingBot.service;

import com.tradingBot.repository.TradeRepository;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.tradingBot.entity.Signal;
import com.tradingBot.entity.Trade;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j
public class TelegramService {
    private final OkHttpClient client;
    private final TradeRepository tradeRepository;

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.chat-id}")
    private String chatId;

    @Value("${safety.paper-mode:true}")
    private boolean paperMode;

    public TelegramService(TradeRepository tradeRepository) {
        this.client = new OkHttpClient();
        this.tradeRepository = tradeRepository;
    }

    public void sendMessage(String message) {
        try {
            // Add paper mode prefix if enabled
            if (paperMode) {
                message = "📝 [PAPER MODE] " + message;
            }

            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);

            RequestBody body = new FormBody.Builder()
                    .add("chat_id", chatId)
                    .add("text", message)
                    .add("parse_mode", "HTML")
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                log.error("Failed to send Telegram message: {}", response.body().string());
            }
        } catch (Exception e) {
            log.error("Error sending Telegram message: {}", e.getMessage());
        }
    }

    public void sendEmergencyStopNotification(String reason) {
        StringBuilder message = new StringBuilder();
        message.append("🚨🚨🚨 <b>EMERGENCY STOP ACTIVATED</b> 🚨🚨🚨\n\n");
        message.append("Reason: ").append(reason).append("\n");
        message.append("Time: ").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

        // Get all open positions
        List<Trade> openTrades = tradeRepository.findByStatusAndSymbol("OPEN", "QQQ");

        if (!openTrades.isEmpty()) {
            message.append("<b>⚠️ OPEN POSITIONS:</b>\n");
            BigDecimal totalRisk = BigDecimal.ZERO;

            for (Trade trade : openTrades) {
                message.append(String.format("\n• %s %s\n",
                        trade.getOptionSymbol(), trade.getType()));
                message.append(String.format("  Entry: $%.2f × %d\n",
                        trade.getEntryPrice(), trade.getQuantity()));
                message.append(String.format("  Entry Time: %s\n",
                        trade.getEntryTime().format(DateTimeFormatter.ofPattern("HH:mm"))));

                BigDecimal positionValue = trade.getEntryPrice()
                        .multiply(BigDecimal.valueOf(trade.getQuantity()))
                        .multiply(BigDecimal.valueOf(100)); // Options multiplier
                totalRisk = totalRisk.add(positionValue);

                message.append(String.format("  Position Value: $%.2f\n", positionValue));
            }

            message.append(String.format("\n<b>Total at Risk: $%.2f</b>\n", totalRisk));
            message.append("\n⚠️ <b>MANUALLY CLOSE ALL POSITIONS NOW!</b>");
        } else {
            message.append("✅ No open positions\n");
        }

        message.append("\n<b>Bot Status: STOPPED</b>");
        message.append("\n\nTo restart: Check system and fix issue first!");

        sendMessage(message.toString());
    }

    public void notifySignal(Signal signal) {
        StringBuilder message = new StringBuilder();
        message.append("<b>🚨 NEW 0DTE SIGNAL</b>\n\n");
        message.append(String.format("Symbol: <code>%s</code>\n", signal.getOptionSymbol()));
        message.append(String.format("Action: <b>%s</b>\n", signal.getSignalType()));
        message.append(String.format("Strategy: %s\n", formatStrategy(signal.getStrategy())));
        message.append(String.format("Target: $%.2f\n", signal.getTargetPrice()));
        message.append(String.format("Stop Loss: $%.2f\n", signal.getStopLoss()));
        message.append(String.format("Confidence: %.0f%%\n", signal.getConfidence() * 100));
        message.append(String.format("Reason: %s\n", signal.getReason()));

        // Add strategy-specific emojis
        if (signal.getStrategy().contains("VWAP")) {
            message.insert(0, "📊 ");
        } else if (signal.getStrategy().contains("DOUBLE")) {
            message.insert(0, "🎯 ");
        } else if (signal.getStrategy().contains("TRENDLINE")) {
            message.insert(0, "📈 ");
        } else if (signal.getStrategy().contains("SUPPORT") || signal.getStrategy().contains("RESISTANCE")) {
            message.insert(0, "🔑 ");
        }

        sendMessage(message.toString());
    }

    private String formatStrategy(String strategy) {
        return strategy.replace("0DTE_", "")
                .replace("_", " ")
                .toLowerCase()
                .replaceFirst("^.", String.valueOf(Character.toUpperCase(strategy.charAt(0))));
    }

    public void notifyTrade(Trade trade) {
        StringBuilder message = new StringBuilder();

        if (trade.getStatus().equals("OPEN")) {
            message.append("<b>📈 TRADE OPENED</b>\n\n");
            message.append(String.format("Option: <code>%s</code>\n", trade.getOptionSymbol()));
            message.append(String.format("Type: %s\n", trade.getType()));
            message.append(String.format("Strike: $%.2f\n", trade.getStrikePrice()));
            message.append(String.format("Quantity: %d\n", trade.getQuantity()));
            message.append(String.format("Entry Price: $%.2f\n", trade.getEntryPrice()));
            message.append(String.format("Strategy: %s\n", trade.getStrategy()));
        } else if (trade.getStatus().equals("CLOSED")) {
            message.append("<b>📊 TRADE CLOSED</b>\n\n");
            message.append(String.format("Option: <code>%s</code>\n", trade.getOptionSymbol()));
            message.append(String.format("Entry: $%.2f → Exit: $%.2f\n",
                    trade.getEntryPrice(), trade.getExitPrice()));
            message.append(String.format("Profit/Loss: <b>$%.2f</b>\n", trade.getProfit()));

            if (trade.getProfit().doubleValue() > 0) {
                message.append("Result: ✅ PROFIT");
            } else {
                message.append("Result: ❌ LOSS");
            }
        }

        sendMessage(message.toString());
    }

    public void sendDailySummary(List<Trade> trades, BigDecimal totalProfit) {
        StringBuilder message = new StringBuilder();
        message.append("<b>📊 DAILY SUMMARY</b>\n\n");
        message.append(String.format("Total Trades: %d\n", trades.size()));
        message.append(String.format("Total P/L: <b>$%.2f</b>\n", totalProfit));

        long winners = trades.stream()
                .filter(t -> t.getProfit().doubleValue() > 0)
                .count();
        long losers = trades.size() - winners;

        message.append(String.format("Winners: %d | Losers: %d\n", winners, losers));
        message.append(String.format("Win Rate: %.1f%%\n",
                (double) winners / trades.size() * 100));

        sendMessage(message.toString());
    }
}
