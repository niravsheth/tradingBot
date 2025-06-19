package com.tradingBot.controller;

import com.tradingBot.service.ZeroDTEStrategy;
import com.tradingBot.service.TradingService;
import com.tradingBot.entity.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
@Slf4j
public class TradingController {

    private final ZeroDTEStrategy strategy;
    private final TradingService tradingService;

    @PostMapping("/analyze/{symbol}")
    public ResponseEntity<Map<String, Object>> analyzeSymbol(@PathVariable String symbol) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[v62][{}] Manual analysis requested for {}", requestId, symbol);

        Map<String, Object> response = new HashMap<>();
        response.put("requestId", requestId);
        response.put("symbol", symbol);
        response.put("timestamp", LocalDateTime.now());

        try {
            // Run analysis
            List<Signal> signals = strategy.analyzeOptions(symbol);

            response.put("status", "success");
            response.put("signalsGenerated", signals.size());

            List<Map<String, Object>> signalDetails = new ArrayList<>();
            for (Signal signal : signals) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("optionSymbol", signal.getOptionSymbol());
                detail.put("strategy", signal.getStrategy());
                detail.put("confidence", signal.getConfidence());
                detail.put("action", signal.getSignalType());
                detail.put("target", signal.getTargetPrice());
                detail.put("stopLoss", signal.getStopLoss());
                signalDetails.add(detail);
            }
            response.put("signals", signalDetails);

            // Execute signals if any
            if (!signals.isEmpty()) {
                tradingService.executeSignals();
                response.put("executionTriggered", true);
            }

        } catch (Exception e) {
            log.error("[v62][{}] Error analyzing {}: {}", requestId, symbol, e.getMessage());
            response.put("status", "error");
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }
}