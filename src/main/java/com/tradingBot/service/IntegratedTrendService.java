package com.tradingBot.service;

import com.tradingBot.ml.MLTrendPredictor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class IntegratedTrendService {

    private final StatisticalTrendDetector statisticalDetector;
    private final AdvancedTrendDetector advancedDetector;
    private final MLTrendPredictor mlPredictor;

    @Data
    public static class IntegratedTrendAnalysis {
        private String finalTrend;
        private double confidence;
        private String statisticalTrend;
        private String advancedTrend;
        private String mlPrediction;
        private double zScore;
        private String marketRegime;
        private String reasoning;
    }

    public IntegratedTrendAnalysis getComprehensiveTrend(String symbol) {
        IntegratedTrendAnalysis result = new IntegratedTrendAnalysis();

        try {
            // Run all analyses in parallel
            CompletableFuture<StatisticalTrendDetector.TrendAnalysis> statFuture =
                    CompletableFuture.supplyAsync(() -> statisticalDetector.analyzeTrend(symbol));

            CompletableFuture<String> advancedFuture =
                    CompletableFuture.supplyAsync(() -> advancedDetector.detectTrend(symbol));

            CompletableFuture<MLTrendPredictor.MLPrediction> mlFuture =
                    mlPredictor.predictTrend(symbol, 5);

            // Wait for all results (with timeout)
            CompletableFuture.allOf(statFuture, advancedFuture, mlFuture)
                    .get(2, TimeUnit.SECONDS);

            // Get results
            StatisticalTrendDetector.TrendAnalysis statistical = statFuture.join();
            String advanced = advancedFuture.join();
            MLTrendPredictor.MLPrediction ml = mlFuture.join();

            // Store individual results
            result.setStatisticalTrend(statistical.getTrend());
            result.setAdvancedTrend(advanced);
            result.setMlPrediction(ml.getPredictedTrend());
            result.setZScore(statistical.getZScore());
            result.setMarketRegime(statistical.getRegime());

            // Combine trends with weighted voting
            String finalTrend = determineFinalTrend(statistical, advanced, ml);
            double finalConfidence = calculateFinalConfidence(statistical, advanced, ml);

            result.setFinalTrend(finalTrend);
            result.setConfidence(finalConfidence);
            result.setReasoning(generateReasoning(result));

            log.info("[INTEGRATED] {} - Final: {} ({}%), Stat: {}, Adv: {}, ML: {}",
                    symbol, finalTrend, (int)(finalConfidence * 100),
                    statistical.getTrend(), advanced, ml.getPredictedTrend());

            return result;

        } catch (Exception e) {
            log.error("Integrated trend analysis failed: {}", e.getMessage());
            return createDefaultAnalysis();
        }
    }

    private String determineFinalTrend(StatisticalTrendDetector.TrendAnalysis statistical,
                                       String advanced,
                                       MLTrendPredictor.MLPrediction ml) {
        // Weighted voting
        Map<String, Double> votes = new HashMap<>();

        // Statistical (40% weight) - most reliable for 0DTE
        votes.merge(statistical.getTrend(), 0.4 * statistical.getConfidence(), Double::sum);

        // Advanced (35% weight)
        double advConfidence = advanced.equals("NEUTRAL") ? 0.5 : 0.7;
        votes.merge(advanced, 0.35 * advConfidence, Double::sum);

        // ML (25% weight) - experimental
        votes.merge(ml.getPredictedTrend(), 0.25 * ml.getConfidence(), Double::sum);

        // Special case: If z-score is extreme, give it more weight
        if (Math.abs(statistical.getZScore()) > 2.5) {
            votes.merge(statistical.getTrend(), 0.2, Double::sum);
        }

        // Find winner
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("NEUTRAL");
    }

    private double calculateFinalConfidence(StatisticalTrendDetector.TrendAnalysis statistical,
                                            String advanced,
                                            MLTrendPredictor.MLPrediction ml) {
        // Agreement bonus
        int agreementCount = 0;
        if (statistical.getTrend().equals(advanced)) agreementCount++;
        if (statistical.getTrend().equals(ml.getPredictedTrend())) agreementCount++;
        if (advanced.equals(ml.getPredictedTrend())) agreementCount++;

        double agreementBonus = agreementCount * 0.1;

        // Weighted average confidence
        double weightedConfidence =
                0.4 * statistical.getConfidence() +
                        0.35 * 0.7 + // Advanced doesn't provide confidence
                        0.25 * ml.getConfidence();

        return Math.min(0.95, weightedConfidence + agreementBonus);
    }

    private String generateReasoning(IntegratedTrendAnalysis analysis) {
        StringBuilder reason = new StringBuilder();

        reason.append(String.format("Consensus: %s (%.0f%%), ",
                analysis.getFinalTrend(), analysis.getConfidence() * 100));

        reason.append(String.format("Z-score: %.2f, ", analysis.getZScore()));
        reason.append(String.format("Regime: %s", analysis.getMarketRegime()));

        // Note disagreements
        if (!analysis.getStatisticalTrend().equals(analysis.getFinalTrend())) {
            reason.append(", Statistical disagrees");
        }
        if (!analysis.getMlPrediction().equals(analysis.getFinalTrend())) {
            reason.append(", ML disagrees");
        }

        return reason.toString();
    }

    private IntegratedTrendAnalysis createDefaultAnalysis() {
        IntegratedTrendAnalysis analysis = new IntegratedTrendAnalysis();
        analysis.setFinalTrend("NEUTRAL");
        analysis.setConfidence(0.5);
        analysis.setStatisticalTrend("NEUTRAL");
        analysis.setAdvancedTrend("NEUTRAL");
        analysis.setMlPrediction("NEUTRAL");
        analysis.setReasoning("Default analysis due to error");
        return analysis;
    }
}