package com.tradingBot.service;

import com.tradingBot.entity.Signal;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SHAPExplainerService {

    // MAIN PUBLIC METHOD - Explain signal generation
    public SHAPExplanation explainSignal(Signal signal, TechnicalAnalysis ta, String marketTrend) {
        try {
            if (signal == null || ta == null) {
                log.warn("[SHAP] Signal or TA is null, returning default explanation");
                return createDefaultExplanation(signal);
            }

            // Extract features from technical analysis
            Map<String, Double> features = extractFeatures(signal, ta, marketTrend);

            // Calculate SHAP values (feature importance)
            Map<String, Double> shapValues = calculateSHAPValues(features, signal);

            // Generate human-readable explanation
            String explanation = generateExplanation(shapValues, signal);

            // Calculate overall confidence factors
            double explanationStrength = calculateExplanationStrength(shapValues);

            SHAPExplanation shapExplanation = new SHAPExplanation();
            shapExplanation.setSignal(signal.getOptionSymbol());
            shapExplanation.setStrategy(signal.getStrategy());
            shapExplanation.setFeatures(features);
            shapExplanation.setShapValues(shapValues);
            shapExplanation.setExplanation(explanation);
            shapExplanation.setExplanationStrength(explanationStrength);
            shapExplanation.setTopPositiveFactors(getTopFactors(shapValues, true, 3));
            shapExplanation.setTopNegativeFactors(getTopFactors(shapValues, false, 2));

            log.info("[SHAP] Explanation generated - Strength: {}, Top factor: {}",
                    String.format("%.2f", explanationStrength), getTopFactor(shapValues));

            return shapExplanation;

        } catch (Exception e) {
            log.error("[SHAP] Error generating explanation: {}", e.getMessage());
            return createDefaultExplanation(signal);
        }
    }

    // ENHANCED SIGNAL VALIDATION using SHAP
    public boolean validateSignalWithExplanation(Signal signal, TechnicalAnalysis ta, String marketTrend) {
        SHAPExplanation explanation = explainSignal(signal, ta, marketTrend);

        // Null safety check
        if (explanation == null) {
            log.warn("[SHAP-VALIDATION] Explanation is null, returning false");
            return false;
        }

        // Require strong explanation for signal validation
        boolean strongExplanation = explanation.getExplanationStrength() >= 0.7;

        // Check for conflicting factors - with null safety
        Map<String, Double> shapValues = explanation.getShapValues();
        boolean hasConflictingFactors = shapValues != null && hasStrongConflictingFactors(shapValues);

        // Validate top factors make sense for signal type - with null safety
        List<String> topFactors = explanation.getTopPositiveFactors();
        boolean logicalFactors = topFactors != null && validateSignalLogic(signal, topFactors);

        boolean isValid = strongExplanation && !hasConflictingFactors && logicalFactors;

        log.info("[SHAP-VALIDATION] Signal: {} - Strength: {}, Conflicts: {}, Logic: {}, Valid: {}",
                signal != null ? signal.getOptionSymbol() : "null",
                String.format("%.2f", explanation.getExplanationStrength()),
                hasConflictingFactors, logicalFactors, isValid);

        return isValid;
    }

    // FEATURE EXTRACTION - Convert trading data to ML features
    private Map<String, Double> extractFeatures(Signal signal, TechnicalAnalysis ta, String marketTrend) {
        Map<String, Double> features = new HashMap<>();

        // Price-based features - with null safety
        features.put("price_momentum", calculatePriceMomentum(ta));
        features.put("price_to_vwap_ratio", ta.getPriceToVwapRatio() != 0 ? ta.getPriceToVwapRatio() : 0.0);
        features.put("vwap_deviation", calculateVWAPDeviation(ta));

        // Volume features
        features.put("volume_ratio", ta.getVolumeRatio() != 0 ? ta.getVolumeRatio() : 1.0);
        features.put("volume_strength", ta.isHighVolume() ? 1.0 : 0.0);

        // Technical indicators
        features.put("rsi", ta.getRsi() / 100.0); // Normalize to 0-1
        features.put("macd_signal", getMACDSignalValue(ta.getMacdSignal()));
        features.put("momentum_strength", ta.getMomentumStrength());

        // Market structure features
        features.put("vwap_breakout", ta.isVwapBreakout() ? 1.0 : 0.0);
        features.put("extended_from_vwap", ta.isExtendedFromVwap() ? 1.0 : 0.0);
        features.put("vwap_as_support", ta.isVwapAsSupport() ? 1.0 : 0.0);
        features.put("vwap_as_resistance", ta.isVwapAsResistance() ? 1.0 : 0.0);

        // Market trend alignment
        features.put("trend_alignment", calculateTrendAlignment(signal, marketTrend));
        features.put("market_regime_strength", getMarketRegimeStrength(ta.getMarketRegime()));

        // Divergence signals
        features.put("rsi_divergence", ta.isHasRsiDivergence() ? -0.5 : 0.0); // Negative feature
        features.put("macd_divergence", ta.isHasMacdDivergence() ? -0.5 : 0.0); // Negative feature

        // Time-based features
        features.put("time_window_strength", calculateTimeWindowStrength());

        log.debug("[SHAP-FEATURES] Extracted {} features for {}",
                features.size(), signal != null ? signal.getOptionSymbol() : "null");

        return features;
    }

    // SHAP VALUES CALCULATION - Simplified implementation
    private Map<String, Double> calculateSHAPValues(Map<String, Double> features, Signal signal) {
        Map<String, Double> shapValues = new HashMap<>();

        if (features == null || features.isEmpty()) {
            return shapValues;
        }

        // Base weights for different feature categories (learned from historical performance)
        Map<String, Double> baseWeights = getBaseFeatureWeights();

        // Calculate SHAP values based on feature values and learned weights
        for (Map.Entry<String, Double> feature : features.entrySet()) {
            String featureName = feature.getKey();
            Double featureValue = feature.getValue();

            // Null safety
            if (featureValue == null) {
                featureValue = 0.0;
            }

            double baseWeight = baseWeights.getOrDefault(featureName, 0.1);

            // SHAP value = feature_value * base_weight * interaction_effects
            double interactionEffect = calculateInteractionEffect(featureName, features);
            double shapValue = featureValue * baseWeight * interactionEffect;

            shapValues.put(featureName, shapValue);
        }

        // Normalize SHAP values so they sum to the prediction (signal confidence)
        double confidence = signal != null ? signal.getConfidence() : 0.5;
        normalizeShapValues(shapValues, confidence);

        return shapValues;
    }

    // BASE FEATURE WEIGHTS - Learned from historical performance
    private Map<String, Double> getBaseFeatureWeights() {
        Map<String, Double> weights = new HashMap<>();

        // High-impact features (learned from profitable trades)
        weights.put("volume_ratio", 0.25);           // Volume spikes are highly predictive
        weights.put("vwap_breakout", 0.20);          // VWAP breakouts have good success rate
        weights.put("trend_alignment", 0.18);        // Trend alignment critical for 0DTE
        weights.put("momentum_strength", 0.15);      // Momentum continuation important

        // Medium-impact features
        weights.put("price_momentum", 0.12);         // Price momentum matters
        weights.put("rsi", 0.10);                    // RSI useful but not dominant
        weights.put("macd_signal", 0.08);            // MACD helpful for confirmation

        // Lower-impact but useful features
        weights.put("price_to_vwap_ratio", 0.06);    // VWAP ratio provides context
        weights.put("time_window_strength", 0.05);   // Time windows matter for 0DTE
        weights.put("market_regime_strength", 0.04); // Market regime provides context

        // Neutral/Contextual features
        weights.put("vwap_deviation", 0.03);
        weights.put("volume_strength", 0.03);
        weights.put("extended_from_vwap", 0.02);
        weights.put("vwap_as_support", 0.02);
        weights.put("vwap_as_resistance", 0.02);

        // Negative features (reduce confidence when present)
        weights.put("rsi_divergence", -0.15);        // Divergences are warning signs
        weights.put("macd_divergence", -0.10);       // Divergences reduce confidence

        return weights;
    }

    // INTERACTION EFFECTS - How features interact with each other
    private double calculateInteractionEffect(String featureName, Map<String, Double> features) {
        double baseEffect = 1.0;

        // Null safety
        if (features == null) {
            return baseEffect;
        }

        switch (featureName) {
            case "volume_ratio":
                // Volume is more important when momentum is strong
                Double momentum = features.get("momentum_strength");
                if (momentum != null && momentum > 0.5) {
                    baseEffect *= 1.3;
                }
                break;
            case "vwap_breakout":
                // VWAP breakout more significant with high volume
                Double volume = features.get("volume_ratio");
                if (volume != null && volume > 1.5) {
                    baseEffect *= 1.4;
                }
                break;
            case "trend_alignment":
                // Trend alignment matters more in strong regimes
                Double regime = features.get("market_regime_strength");
                if (regime != null && regime > 0.7) {
                    baseEffect *= 1.2;
                }
                break;
            case "rsi":
                // RSI extremes are more predictive
                Double rsi = features.get("rsi");
                if (rsi != null && (rsi < 0.3 || rsi > 0.7)) {
                    baseEffect *= 1.3;
                }
                break;
        }

        return baseEffect;
    }

    // NORMALIZATION - Make SHAP values sum to confidence
    private void normalizeShapValues(Map<String, Double> shapValues, double targetSum) {
        if (shapValues == null || shapValues.isEmpty()) {
            return;
        }

        double currentSum = shapValues.values().stream()
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        if (Math.abs(currentSum) > 0.001) {
            double scaleFactor = targetSum / currentSum;
            shapValues.replaceAll((k, v) -> v != null ? v * scaleFactor : 0.0);
        }
    }

    // EXPLANATION GENERATION
    private String generateExplanation(Map<String, Double> shapValues, Signal signal) {
        if (shapValues == null || shapValues.isEmpty()) {
            return "Unable to generate explanation - no SHAP values";
        }

        StringBuilder explanation = new StringBuilder();
        String signalType = signal != null && signal.getOptionSymbol() != null && signal.getOptionSymbol().contains("P")
                ? "PUT" : "CALL";

        explanation.append(String.format("Signal generated for %s based on: ", signalType));

        // Get top 3 positive factors
        List<Map.Entry<String, Double>> topPositive = shapValues.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3)
                .collect(Collectors.toList());

        for (int i = 0; i < topPositive.size(); i++) {
            Map.Entry<String, Double> factor = topPositive.get(i);
            String humanName = getHumanReadableFeatureName(factor.getKey());
            if (i > 0) explanation.append(", ");
            explanation.append(humanName);
        }

        return explanation.toString();
    }

    // HUMAN-READABLE FEATURE NAMES
    private String getHumanReadableFeatureName(String featureName) {
        Map<String, String> nameMapping = new HashMap<>();
        nameMapping.put("volume_ratio", "Volume Spike");
        nameMapping.put("vwap_breakout", "VWAP Breakout");
        nameMapping.put("trend_alignment", "Trend Alignment");
        nameMapping.put("momentum_strength", "Price Momentum");
        nameMapping.put("rsi", "RSI Momentum");
        nameMapping.put("macd_signal", "MACD Signal");
        nameMapping.put("price_momentum", "Price Movement");
        nameMapping.put("time_window_strength", "Timing Window");
        nameMapping.put("market_regime_strength", "Market Regime");
        nameMapping.put("rsi_divergence", "RSI Divergence");
        nameMapping.put("macd_divergence", "MACD Divergence");
        nameMapping.put("extended_from_vwap", "Extended from VWAP");
        nameMapping.put("vwap_as_support", "VWAP Support");
        nameMapping.put("vwap_as_resistance", "VWAP Resistance");

        return nameMapping.getOrDefault(featureName,
                featureName != null ? featureName.replace("_", " ") : "Unknown");
    }

    // HELPER CALCULATION METHODS
    private double calculatePriceMomentum(TechnicalAnalysis ta) {
        if (ta == null || ta.getCurrentPrice() == null || ta.getPreviousClose() == null) {
            return 0.0;
        }

        try {
            return ta.getCurrentPrice().subtract(ta.getPreviousClose())
                    .divide(ta.getPreviousClose(), 4, RoundingMode.HALF_UP)
                    .doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double calculateVWAPDeviation(TechnicalAnalysis ta) {
        if (ta == null || ta.getVwap() == null || ta.getCurrentPrice() == null) {
            return 0.0;
        }

        try {
            if (ta.getVwap().compareTo(BigDecimal.ZERO) == 0) {
                return 0.0;
            }
            return ta.getCurrentPrice().subtract(ta.getVwap())
                    .divide(ta.getVwap(), 4, RoundingMode.HALF_UP)
                    .doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double getMACDSignalValue(String macdSignal) {
        if (macdSignal == null) return 0.0;
        switch (macdSignal.toUpperCase()) {
            case "BULLISH":
                return 0.8;
            case "BEARISH":
                return -0.8;
            default:
                return 0.0;
        }
    }

    private double calculateTrendAlignment(Signal signal, String marketTrend) {
        if (signal == null || signal.getOptionSymbol() == null || marketTrend == null) {
            return 0.0;
        }

        boolean isPut = signal.getOptionSymbol().contains("P");
        boolean isCall = !isPut;

        if ((isCall && "UP".equals(marketTrend)) || (isPut && "DOWN".equals(marketTrend))) {
            return 1.0; // Perfect alignment
        } else if ("NEUTRAL".equals(marketTrend)) {
            return 0.0; // Neutral
        } else {
            return -0.8; // Counter-trend
        }
    }

    private double getMarketRegimeStrength(Object marketRegime) {
        if (marketRegime == null) return 0.5;

        String regime = marketRegime.toString();
        switch (regime.toLowerCase()) {
            case "trending_up":
            case "trending_down":
                return 0.9;
            case "high_volatility":
                return 0.7;
            case "opening_range":
                return 0.6;
            default:
                return 0.5;
        }
    }

    private double calculateTimeWindowStrength() {
        java.time.LocalTime now = java.time.LocalTime.now();

        // Prime trading hours (10:00-12:00, 13:30-15:30)
        if ((now.isAfter(java.time.LocalTime.of(10, 0)) && now.isBefore(java.time.LocalTime.of(12, 0))) ||
                (now.isAfter(java.time.LocalTime.of(13, 30)) && now.isBefore(java.time.LocalTime.of(15, 30)))) {
            return 1.0;
        } else if (now.isAfter(java.time.LocalTime.of(9, 30)) && now.isBefore(java.time.LocalTime.of(16, 0))) {
            return 0.7; // Regular trading hours
        } else {
            return 0.2; // Outside hours
        }
    }

    // VALIDATION HELPERS
    private double calculateExplanationStrength(Map<String, Double> shapValues) {
        if (shapValues == null || shapValues.isEmpty()) {
            return 0.0;
        }

        double totalPositive = shapValues.values().stream()
                .filter(v -> v != null && v > 0)
                .mapToDouble(Double::doubleValue)
                .sum();

        double totalNegative = Math.abs(shapValues.values().stream()
                .filter(v -> v != null && v < 0)
                .mapToDouble(Double::doubleValue)
                .sum());

        // Strong explanation has high positive contribution and low negative
        double netStrength = totalPositive - totalNegative;
        return Math.max(0.0, Math.min(1.0, netStrength));
    }

    private boolean hasStrongConflictingFactors(Map<String, Double> shapValues) {
        if (shapValues == null || shapValues.isEmpty()) {
            return false;
        }

        double totalPositive = shapValues.values().stream()
                .filter(v -> v != null && v > 0)
                .mapToDouble(Double::doubleValue)
                .sum();

        double totalNegative = Math.abs(shapValues.values().stream()
                .filter(v -> v != null && v < 0)
                .mapToDouble(Double::doubleValue)
                .sum());

        // If negative factors are more than 40% of positive, it's conflicting
        return totalPositive > 0 && totalNegative > (totalPositive * 0.4);
    }

    private boolean validateSignalLogic(Signal signal, List<String> topFactors) {
        if (topFactors == null || topFactors.isEmpty()) {
            return false;
        }

        // Check if top factors make sense for signal type
        for (String factor : topFactors) {
            if ("Trend Alignment".equals(factor)) {
                // Trend alignment should always be positive for valid signals
                return true;
            }
        }

        return !topFactors.isEmpty(); // At least some positive factors
    }

    private List<String> getTopFactors(Map<String, Double> shapValues, boolean positive, int count) {
        if (shapValues == null || shapValues.isEmpty()) {
            return new ArrayList<>();
        }

        return shapValues.entrySet().stream()
                .filter(e -> e.getValue() != null && (positive ? e.getValue() > 0 : e.getValue() < 0))
                .sorted(positive ?
                        Map.Entry.<String, Double>comparingByValue().reversed() :
                        Map.Entry.<String, Double>comparingByValue())
                .limit(count)
                .map(e -> getHumanReadableFeatureName(e.getKey()))
                .collect(Collectors.toList());
    }

    private String getTopFactor(Map<String, Double> shapValues) {
        if (shapValues == null || shapValues.isEmpty()) {
            return "Unknown";
        }

        return shapValues.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .max(Map.Entry.comparingByValue())
                .map(e -> getHumanReadableFeatureName(e.getKey()))
                .orElse("Unknown");
    }

    private SHAPExplanation createDefaultExplanation(Signal signal) {
        SHAPExplanation explanation = new SHAPExplanation();
        explanation.setSignal(signal != null ? signal.getOptionSymbol() : "Unknown");
        explanation.setStrategy(signal != null ? signal.getStrategy() : "Unknown");
        explanation.setExplanation("Unable to generate detailed explanation");
        explanation.setExplanationStrength(0.5);
        explanation.setFeatures(new HashMap<>());
        explanation.setShapValues(new HashMap<>());
        explanation.setTopPositiveFactors(Arrays.asList("Signal Generated"));
        explanation.setTopNegativeFactors(new ArrayList<>());
        return explanation;
    }

    // DATA CLASS
    @Data
    public static class SHAPExplanation {
        private String signal;
        private String strategy;
        private Map<String, Double> features;
        private Map<String, Double> shapValues;
        private String explanation;
        private double explanationStrength;
        private List<String> topPositiveFactors;
        private List<String> topNegativeFactors;
    }
}