package com.tradingBot.ml;

import com.tradingBot.entity.MarketData;
import com.tradingBot.repository.MarketDataRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class MLTrendPredictor {

    private final MarketDataRepository marketDataRepository;

    @Data
    public static class MLPrediction {
        private String predictedTrend;
        private double confidence;
        private Map<String, Double> probabilities = new HashMap<>();
        private int horizon; // Prediction horizon in minutes
        private String modelType;
    }

    /**
     * Predict trend using ensemble of models
     */
    public CompletableFuture<MLPrediction> predictTrend(String symbol, int horizonMinutes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Prepare features
                double[][] features = prepareFeatures(symbol);

                // Run predictions in parallel
                CompletableFuture<MLPrediction> lstmFuture =
                        CompletableFuture.supplyAsync(() -> runLSTMPrediction(features, horizonMinutes));

                CompletableFuture<MLPrediction> hmmFuture =
                        CompletableFuture.supplyAsync(() -> runHMMPrediction(features, horizonMinutes));

                // Combine predictions
                MLPrediction lstm = lstmFuture.join();
                MLPrediction hmm = hmmFuture.join();

                return ensemblePredictions(lstm, hmm);

            } catch (Exception e) {
                log.error("ML prediction failed: {}", e.getMessage());
                return createDefaultPrediction();
            }
        });
    }

    private double[][] prepareFeatures(String symbol) {
        List<MarketData> data = marketDataRepository.findRecentData(symbol, 60);

        // Feature engineering
        int numFeatures = 10; // price, volume, returns, volatility, etc.
        int sequenceLength = Math.min(data.size(), 30);

        double[][] features = new double[sequenceLength][numFeatures];

        for (int i = 0; i < sequenceLength; i++) {
            MarketData md = data.get(i);

            // Basic features
            features[i][0] = md.getPrice().doubleValue();
            features[i][1] = md.getVolume() != null ? md.getVolume() : 0;

            // Price features
            if (i > 0) {
                features[i][2] = calculateReturn(data.get(i-1).getPrice(), md.getPrice());
            }

            // Volume features
            if (i > 5) {
                features[i][3] = calculateVolumeRatio(data, i);
            }

            // Microstructure features
            if (md.getBid() != null && md.getAsk() != null) {
                features[i][4] = md.getAsk().subtract(md.getBid()).doubleValue(); // Spread
                features[i][5] = (md.getBidSize() - md.getAskSize()) /
                        (double)(md.getBidSize() + md.getAskSize()); // Order imbalance
            }

            // Technical indicators
            if (i >= 14) {
                features[i][6] = calculateRSI(data, i, 14);
            }

            // Volatility
            if (i >= 20) {
                features[i][7] = calculateRealizedVol(data, i, 20);
            }

            // Time features
            features[i][8] = md.getTimestamp().getHour() + md.getTimestamp().getMinute() / 60.0;
            features[i][9] = md.getTimestamp().getDayOfWeek().getValue();
        }

        return normalizeFeatures(features);
    }

    /**
     * LSTM-based prediction (mock implementation - replace with actual model)
     */
    private MLPrediction runLSTMPrediction(double[][] features, int horizon) {
        MLPrediction prediction = new MLPrediction();
        prediction.setModelType("LSTM");
        prediction.setHorizon(horizon);

        // In production, this would call your trained LSTM model
        // For now, simulate based on recent momentum
        double momentum = calculateMomentumFromFeatures(features);

        if (momentum > 0.5) {
            prediction.setPredictedTrend("UP");
            prediction.setConfidence(0.7 + momentum * 0.2);
            prediction.getProbabilities().put("UP", 0.6 + momentum * 0.3);
            prediction.getProbabilities().put("NEUTRAL", 0.3 - momentum * 0.2);
            prediction.getProbabilities().put("DOWN", 0.1 - momentum * 0.1);
        } else if (momentum < -0.5) {
            prediction.setPredictedTrend("DOWN");
            prediction.setConfidence(0.7 + Math.abs(momentum) * 0.2);
            prediction.getProbabilities().put("DOWN", 0.6 + Math.abs(momentum) * 0.3);
            prediction.getProbabilities().put("NEUTRAL", 0.3 - Math.abs(momentum) * 0.2);
            prediction.getProbabilities().put("UP", 0.1 - Math.abs(momentum) * 0.1);
        } else {
            prediction.setPredictedTrend("NEUTRAL");
            prediction.setConfidence(0.6);
            prediction.getProbabilities().put("NEUTRAL", 0.5);
            prediction.getProbabilities().put("UP", 0.25);
            prediction.getProbabilities().put("DOWN", 0.25);
        }

        return prediction;
    }

    /**
     * HMM-based prediction (mock implementation - replace with actual model)
     */
    private MLPrediction runHMMPrediction(double[][] features, int horizon) {
        MLPrediction prediction = new MLPrediction();
        prediction.setModelType("HMM");
        prediction.setHorizon(horizon);

        // In production, this would use a trained HMM
        // For now, analyze state transitions
        int[] states = detectMarketStates(features);
        int currentState = states[states.length - 1];

        // Simple state transition probabilities
        switch (currentState) {
            case 0: // Bullish state
                prediction.setPredictedTrend("UP");
                prediction.setConfidence(0.75);
                prediction.getProbabilities().put("UP", 0.7);
                prediction.getProbabilities().put("NEUTRAL", 0.2);
                prediction.getProbabilities().put("DOWN", 0.1);
                break;

            case 1: // Neutral state
                prediction.setPredictedTrend("NEUTRAL");
                prediction.setConfidence(0.65);
                prediction.getProbabilities().put("NEUTRAL", 0.6);
                prediction.getProbabilities().put("UP", 0.2);
                prediction.getProbabilities().put("DOWN", 0.2);
                break;

            case 2: // Bearish state
                prediction.setPredictedTrend("DOWN");
                prediction.setConfidence(0.75);
                prediction.getProbabilities().put("DOWN", 0.7);
                prediction.getProbabilities().put("NEUTRAL", 0.2);
                prediction.getProbabilities().put("UP", 0.1);
                break;
        }

        return prediction;
    }

    /**
     * Ensemble multiple predictions
     */
    private MLPrediction ensemblePredictions(MLPrediction... predictions) {
        MLPrediction ensemble = new MLPrediction();
        ensemble.setModelType("ENSEMBLE");

        Map<String, Double> weightedProbs = new HashMap<>();
        weightedProbs.put("UP", 0.0);
        weightedProbs.put("NEUTRAL", 0.0);
        weightedProbs.put("DOWN", 0.0);

        double totalWeight = 0.0;

        for (MLPrediction pred : predictions) {
            double weight = pred.getConfidence();
            totalWeight += weight;

            for (Map.Entry<String, Double> entry : pred.getProbabilities().entrySet()) {
                weightedProbs.merge(entry.getKey(),
                        entry.getValue() * weight, Double::sum);
            }
        }

        // Normalize
        for (Map.Entry<String, Double> entry : weightedProbs.entrySet()) {
            entry.setValue(entry.getValue() / totalWeight);
        }

        ensemble.setProbabilities(weightedProbs);

        // Determine predicted trend
        String predictedTrend = weightedProbs.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("NEUTRAL");

        ensemble.setPredictedTrend(predictedTrend);
        ensemble.setConfidence(weightedProbs.get(predictedTrend));

        return ensemble;
    }

    // Helper methods
    private double calculateReturn(BigDecimal prev, BigDecimal curr) {
        if (prev.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return curr.subtract(prev).divide(prev, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private double calculateVolumeRatio(List<MarketData> data, int index) {
        long currentVol = data.get(index).getVolume() != null ? data.get(index).getVolume() : 0;
        long avgVol = (long) data.subList(Math.max(0, index - 5), index).stream()
                .mapToLong(d -> d.getVolume() != null ? d.getVolume() : 0)
                .average()
                .orElse(1);

        return avgVol > 0 ? (double) currentVol / avgVol : 1.0;
    }

    private double calculateRSI(List<MarketData> data, int index, int period) {
        // Simplified RSI calculation
        double gains = 0, losses = 0;

        for (int i = Math.max(1, index - period + 1); i <= index; i++) {
            double change = calculateReturn(data.get(i-1).getPrice(), data.get(i).getPrice());
            if (change > 0) {
                gains += change;
            } else {
                losses += Math.abs(change);
            }
        }

        double avgGain = gains / period;
        double avgLoss = losses / period;

        if (avgLoss == 0) return 100;

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double calculateRealizedVol(List<MarketData> data, int index, int period) {
        List<Double> returns = new ArrayList<>();

        for (int i = Math.max(1, index - period + 1); i <= index; i++) {
            returns.add(calculateReturn(data.get(i-1).getPrice(), data.get(i).getPrice()));
        }

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2))
                .average()
                .orElse(0);

        return Math.sqrt(variance * 252 * 390); // Annualized
    }

    private double[][] normalizeFeatures(double[][] features) {
        int numFeatures = features[0].length;

        for (int j = 0; j < numFeatures; j++) {
            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;

            // Find min and max
            for (int i = 0; i < features.length; i++) {
                min = Math.min(min, features[i][j]);
                max = Math.max(max, features[i][j]);
            }

            // Normalize
            double range = max - min;
            if (range > 0) {
                for (int i = 0; i < features.length; i++) {
                    features[i][j] = (features[i][j] - min) / range;
                }
            }
        }

        return features;
    }

    private double calculateMomentumFromFeatures(double[][] features) {
        // Simple momentum from normalized returns
        double momentum = 0.0;
        int returnFeatureIndex = 2;

        for (int i = Math.max(0, features.length - 5); i < features.length; i++) {
            momentum += features[i][returnFeatureIndex];
        }

        return momentum / 5.0;
    }

    private int[] detectMarketStates(double[][] features) {
        // Simple state detection based on returns and volatility
        int[] states = new int[features.length];

        for (int i = 0; i < features.length; i++) {
            double ret = features[i][2]; // Return feature
            double vol = features[i][7]; // Volatility feature

            if (ret > 0.6 && vol < 0.5) {
                states[i] = 0; // Bullish
            } else if (ret < 0.4 && vol < 0.5) {
                states[i] = 2; // Bearish
            } else {
                states[i] = 1; // Neutral/Choppy
            }
        }

        return states;
    }

    private MLPrediction createDefaultPrediction() {
        MLPrediction prediction = new MLPrediction();
        prediction.setPredictedTrend("NEUTRAL");
        prediction.setConfidence(0.5);
        prediction.getProbabilities().put("NEUTRAL", 0.5);
        prediction.getProbabilities().put("UP", 0.25);
        prediction.getProbabilities().put("DOWN", 0.25);
        return prediction;
    }
}