// 15. Test Controller (only in test/dev profiles)
package com.tradingBot.controller;

import com.tradingBot.service.*;
import com.tradingBot.model.*;
import com.tradingBot.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/test")
@Profile({"test", "dev", "paper"})
@Slf4j
public class HealthController {

    @Autowired
    private TradierService tradierService;

    @Autowired
    private TelegramService telegramService;

    @Autowired
    private SafetyService safetyService;

    @GetMapping("/quote/{symbol}")
    public Map<String, Object> testQuote(@PathVariable String symbol) {
        Map<String, Object> result = new HashMap<>();

        try {
            QuoteResponse response = tradierService.getQuote(symbol);
            if (response != null && response.getQuote() != null) {
                Quote quote = response.getQuote();
                result.put("success", true);
                result.put("symbol", quote.getSymbol());
                result.put("last", quote.getLast());
                result.put("bid", quote.getBid());
                result.put("ask", quote.getAsk());
                result.put("volume", quote.getVolume());
                result.put("timestamp", LocalDateTime.now());
            } else {
                result.put("success", false);
                result.put("error", "No quote data");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
    @PostMapping("/telegram/test")
    public Map<String, String> testTelegram(@RequestParam(required = false) String message) {
        Map<String, String> result = new HashMap<>();

        try {
            String testMsg = message != null ? message :
                    "🧪 Test Message\nTime: " + LocalDateTime.now() +
                            "\nSafety Status: " + (safetyService.isTradingEnabled() ? "Trading Enabled" : "Trading Disabled") +
                            "\nMode: " + (safetyService.isPaperMode() ? "Paper" : "Real");

            telegramService.sendMessage(testMsg);
            result.put("status", "sent");
            result.put("message", testMsg);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }

        return result;
    }

    @PostMapping("/simulate-signal")
    public Map<String, Object> simulateSignal() {
        Map<String, Object> result = new HashMap<>();

        Signal testSignal = new Signal();
        testSignal.setSymbol("QQQ");
        testSignal.setOptionSymbol("QQQ240603C520");
        testSignal.setSignalType("BUY");
        testSignal.setStrategy("TEST_SIGNAL");
        testSignal.setTargetPrice(new BigDecimal("2.00"));
        testSignal.setStopLoss(new BigDecimal("1.00"));
        testSignal.setConfidence(0.75);
        testSignal.setReason("Manual test signal");
        testSignal.setTimestamp(LocalDateTime.now());

        telegramService.notifySignal(testSignal);

        result.put("signal", testSignal);
        result.put("sent", true);

        return result;
    }
}
