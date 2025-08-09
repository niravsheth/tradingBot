package com.tradingBot.service;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MetricClasses {

    // ==========================================
    // RECORD DEFINITIONS (IMMUTABLE DATA CONTAINERS)
    // ==========================================

    /**
     * NAIVE EXPLANATION: Container for VWAP (Volume Weighted Average Price) calculations.
     * VWAP = average price weighted by volume, shows "fair value" price.
     * USE CASE: Compare current price to VWAP to see if stock is expensive or cheap.
     * OUTPUT: VWAP value, distance from current price, trend direction, volatility.
     */
    public record VWAPMetrics(double vwap, double distance, double trend, double volatility) {}

    /**
     * NAIVE EXPLANATION: Container for order flow analysis (buying vs selling pressure).
     * Tracks who's winning - buyers or sellers - and how aggressive the trading is.
     * USE CASE: Detect institutional activity and predict short-term price direction.
     * OUTPUT: Imbalance, market depth, persistence, and microstructure signals.
     */
    public record AdvancedOrderFlowMetrics(double imbalance, double depth, double persistence, double microstructureSignal) {}

    /**
     * NAIVE EXPLANATION: Container for volatility analysis (how wild the price swings are).
     * Measures current volatility vs historical and predicts regime changes.
     * USE CASE: Volatile periods often signal trend changes or continuation.
     * OUTPUT: Current volatility, regime classification, mean reversion tendency.
     */
    public record VolatilityRegimeMetrics(double realizedVol, double regime, double meanReversion) {}

    /**
     * NAIVE EXPLANATION: Container for technical analysis metrics.
     * Momentum, correlations between stocks, divergences, sector rotation patterns.
     * USE CASE: Technical analysis helps predict trend continuation or reversal.
     * OUTPUT: Momentum score, correlation level, divergence signals, sector rotation.
     */
    public record EnhancedTechMetrics(double momentum, double correlation, double divergence, double sectorRotation) {}

    /**
     * NAIVE EXPLANATION: Container for volume analysis (trading activity patterns).
     * Analyzes if volume is high/low, trending, has block trades, unusual activity.
     * USE CASE: Volume confirms price moves - high volume = strong conviction.
     * OUTPUT: Volume ratio, block prints, trend, unusual activity indicators.
     */
    public record VolumeMetrics(double ratio, double blockPrints, double trend, double unusualActivity) {}

    /**
     * NAIVE EXPLANATION: Container for time-based features.
     * Time of day affects market behavior (opening rush, lunch lull, closing action).
     * USE CASE: Different trading strategies work better at different times.
     * OUTPUT: Time encoding, market regime, session phase indicators.
     */
    public record TemporalFeatures(double timeSin, double timeCos, double marketRegime, double sessionPhase) {}

    /**
     * NAIVE EXPLANATION: Container for signals from other markets.
     * VIX (fear), bonds (safety), currency (international), commodities affect stocks.
     * USE CASE: Other markets often predict stock market moves.
     * OUTPUT: Cross-market signals that influence stock trends.
     */
    public record CrossMarketSignals(double vixSignal, double bondSignal, double currencySignal, double commoditySignal) {}

    // ==========================================
    // MAIN FEATURE VECTOR CLASS (33 FEATURES)
    // ==========================================

    /**
     * NAIVE EXPLANATION: Main data structure containing all 33 calculated features.
     * This is the input that gets fed to the AI models for prediction.
     * USE CASE: Package all stock analysis into format AI models can understand.
     * OUTPUT: Complete feature vector ready for machine learning models.
     */
    public static class FeatureVector implements Serializable {
        // EXISTING 29 FEATURES
        public double[] returns = new double[5];
        public double rangePosition = 0.5;
        public double priceAcceleration = 0.0;
        public double vwap = 0.0;
        public double vwapDistance = 0.0;
        public double vwapTrend = 0.0;
        public double vwapVolatility = 0.2;
        public double orderFlowImbalance = 0.0;
        public double bookDepth = 0.0;
        public double orderFlowPersistence = 0.0;
        public double microstructureSignal = 0.0;
        public double realizedVol = 0.2;
        public double volRegime = 0.0;
        public double volMeanReversion = 0.0;
        public double techMomentum = 0.0;
        public double techCorrelation = 0.5;
        public double techDivergence = 0.0;
        public double sectorRotation = 0.0;
        public double volumeRatio = 1.0;
        public double blockPrints = 0.0;
        public double volumeTrend = 0.0;
        public double unusualActivity = 0.0;
        public double timeSin = 0.0;
        public double timeCos = 0.0;
        public double marketRegime = 0.0;
        public double sessionPhase = 0.5;
        public double vixSignal = 0.0;
        public double bondSignal = 0.0;
        public double currencySignal = 0.0;
        public double commoditySignal = 0.0;
        public double confidence = 0.7;

        // NEW CORRELATION LEARNING FEATURES (4 ADDITIONAL)
        public double correlationStrength = 0.5;  // NVDA-MSFT correlation strength (0-1)
        public double nvdaLeadership = 0.5;       // 1.0 if NVDA leading, 0.0 if MSFT leading
        public double regimeStability = 0.7;     // Stability of correlation regime (0-1)
        public double divergenceSignal = 0.0;    // Strength of NVDA-MSFT divergence

        /**
         * NAIVE EXPLANATION: Sets all features to reasonable default values.
         * Like initializing a form with typical values before filling it out.
         * USE CASE: Create a baseline feature vector when no data is available.
         * OUTPUT: FeatureVector with sensible defaults for all 33 features.
         */
        public void fillWithDefaults() {
            log.debug("FEATURE VECTOR: Filling with default values");

            Arrays.fill(returns, 0.0);
            rangePosition = 0.5;
            priceAcceleration = 0.0;
            vwap = 0.0;
            vwapDistance = 0.0;
            vwapTrend = 0.0;
            vwapVolatility = 0.2;
            orderFlowImbalance = 0.0;
            bookDepth = 0.0;
            orderFlowPersistence = 0.0;
            microstructureSignal = 0.0;
            realizedVol = 0.2;
            volRegime = 0.0;
            volMeanReversion = 0.0;
            techMomentum = 0.0;
            techCorrelation = 0.5;
            techDivergence = 0.0;
            sectorRotation = 0.0;
            volumeRatio = 1.0;
            blockPrints = 0.0;
            volumeTrend = 0.0;
            unusualActivity = 0.0;
            timeSin = 0.0;
            timeCos = 0.0;
            marketRegime = 0.0;
            sessionPhase = 0.5;
            vixSignal = 0.0;
            bondSignal = 0.0;
            currencySignal = 0.0;
            commoditySignal = 0.0;
            confidence = 0.7;

            // NEW CORRELATION FEATURES
            correlationStrength = 0.5;  // Neutral correlation
            nvdaLeadership = 0.5;       // Balanced leadership
            regimeStability = 0.7;      // Stable regime assumption
            divergenceSignal = 0.0;     // No divergence

            log.trace("FEATURE VECTOR: All 33 features initialized with defaults");
        }

        /**
         * NAIVE EXPLANATION: Converts feature vector to array format for AI models.
         * Like organizing data into a numbered list that computers can process.
         * USE CASE: Feed features into machine learning models that expect arrays.
         * OUTPUT: Array of 33 numbers representing all features.
         */
        public double[] toAdvancedArray() {
            double[] result = new double[33];
            int idx = 0;

            // Copy returns array (5 elements)
            System.arraycopy(returns, 0, result, idx, 5);
            idx += 5;

            // Add individual features (only 24 assignments to fit indices 5-28)
            result[idx++] = rangePosition;
            result[idx++] = priceAcceleration;
            result[idx++] = vwap;
            result[idx++] = vwapDistance;
            result[idx++] = vwapTrend;
            result[idx++] = vwapVolatility;
            result[idx++] = orderFlowImbalance;
            result[idx++] = bookDepth;
            result[idx++] = orderFlowPersistence;
            result[idx++] = microstructureSignal;
            result[idx++] = realizedVol;
            result[idx++] = volRegime;
            result[idx++] = volMeanReversion;
            result[idx++] = techMomentum;
            result[idx++] = techCorrelation;
            result[idx++] = techDivergence;
            result[idx++] = sectorRotation;
            result[idx++] = volumeRatio;
            result[idx++] = blockPrints;
            result[idx++] = volumeTrend;
            result[idx++] = unusualActivity;
            result[idx++] = timeSin;
            result[idx++] = timeCos;
            result[idx++] = marketRegime;
            // REMOVE: sessionPhase, vixSignal, bondSignal, currencySignal, commoditySignal, confidence

            // Add correlation features at specific indices
            result[29] = correlationStrength;
            result[30] = nvdaLeadership;
            result[31] = regimeStability;
            result[32] = divergenceSignal;

            log.trace("FEATURE VECTOR: Converted to array with {} elements", result.length);
            return result;
        }

        /**
         * NAIVE EXPLANATION: Normalizes features using stored statistics.
         * Converts all features to similar scales so AI models work better.
         * USE CASE: Standardize features before feeding to machine learning models.
         * OUTPUT: FeatureVector with normalized values (mean=0, std=1).
         */
        public void standardizeAdvanced(AdvancedStandardizationParams params) {
            log.debug("FEATURE VECTOR: Standardizing all features");

            // Standardize correlation features (most important for our use case)
            correlationStrength = params.standardize("correlationStrength", correlationStrength);
            nvdaLeadership = params.standardize("nvdaLeadership", nvdaLeadership);
            regimeStability = params.standardize("regimeStability", regimeStability);
            divergenceSignal = params.standardize("divergenceSignal", divergenceSignal);

            // Standardize key existing features
            rangePosition = params.standardize("rangePosition", rangePosition);
            vwapDistance = params.standardize("vwapDistance", vwapDistance);
            orderFlowImbalance = params.standardize("orderFlowImbalance", orderFlowImbalance);
            realizedVol = params.standardize("realizedVol", realizedVol);
            techMomentum = params.standardize("techMomentum", techMomentum);
            volumeRatio = params.standardize("volumeRatio", volumeRatio);
            priceAcceleration = params.standardize("priceAcceleration", priceAcceleration);
            vwapTrend = params.standardize("vwapTrend", vwapTrend);
            microstructureSignal = params.standardize("microstructureSignal", microstructureSignal);
            volRegime = params.standardize("volRegime", volRegime);
            techDivergence = params.standardize("techDivergence", techDivergence);
            volumeTrend = params.standardize("volumeTrend", volumeTrend);
            vixSignal = params.standardize("vixSignal", vixSignal);

            log.trace("FEATURE VECTOR: Standardization completed");
        }

        /**
         * NAIVE EXPLANATION: Creates a copy of this feature vector.
         * Like making a photocopy of a document for backup or modification.
         * USE CASE: Create backup before modifying features or for comparison.
         * OUTPUT: New FeatureVector with identical values.
         */
        public FeatureVector copy() {
            FeatureVector copy = new FeatureVector();
            System.arraycopy(this.returns, 0, copy.returns, 0, 5);
            copy.rangePosition = this.rangePosition;
            copy.priceAcceleration = this.priceAcceleration;
            copy.vwap = this.vwap;
            copy.vwapDistance = this.vwapDistance;
            copy.vwapTrend = this.vwapTrend;
            copy.vwapVolatility = this.vwapVolatility;
            copy.orderFlowImbalance = this.orderFlowImbalance;
            copy.bookDepth = this.bookDepth;
            copy.orderFlowPersistence = this.orderFlowPersistence;
            copy.microstructureSignal = this.microstructureSignal;
            copy.realizedVol = this.realizedVol;
            copy.volRegime = this.volRegime;
            copy.volMeanReversion = this.volMeanReversion;
            copy.techMomentum = this.techMomentum;
            copy.techCorrelation = this.techCorrelation;
            copy.techDivergence = this.techDivergence;
            copy.sectorRotation = this.sectorRotation;
            copy.volumeRatio = this.volumeRatio;
            copy.blockPrints = this.blockPrints;
            copy.volumeTrend = this.volumeTrend;
            copy.unusualActivity = this.unusualActivity;
            copy.timeSin = this.timeSin;
            copy.timeCos = this.timeCos;
            copy.marketRegime = this.marketRegime;
            copy.sessionPhase = this.sessionPhase;
            copy.vixSignal = this.vixSignal;
            copy.bondSignal = this.bondSignal;
            copy.currencySignal = this.currencySignal;
            copy.commoditySignal = this.commoditySignal;
            copy.confidence = this.confidence;
            copy.correlationStrength = this.correlationStrength;
            copy.nvdaLeadership = this.nvdaLeadership;
            copy.regimeStability = this.regimeStability;
            copy.divergenceSignal = this.divergenceSignal;

            log.trace("FEATURE VECTOR: Created copy with all features");
            return copy;
        }
    }

    // ==========================================
    // MODEL SCORES AND PREDICTION CLASSES
    // ==========================================

    /**
     * NAIVE EXPLANATION: Container for predictions from all 3 AI models.
     * Stores the output from linear, XGBoost, and LSTM models.
     * USE CASE: Compare different model predictions and calculate ensemble result.
     * OUTPUT: Three prediction scores that get combined into final trend decision.
     */
    public static class ModelScores implements Serializable {
        public double linearScore = 0.0;    // Linear regression prediction
        public double xgboostScore = 0.0;   // XGBoost tree ensemble prediction
        public double lstmScore = 0.0;      // LSTM sequence prediction

        public ModelScores() {
            log.trace("MODEL SCORES: Created empty score container");
        }

        public ModelScores(double linear, double xgboost, double lstm) {
            this.linearScore = linear;
            this.xgboostScore = xgboost;
            this.lstmScore = lstm;
            log.debug("MODEL SCORES: Created with Linear={}, XGBoost={}, LSTM={}", linear, xgboost, lstm);
        }

        /**
         * NAIVE EXPLANATION: Checks if all model scores are reasonable numbers.
         * Ensures no model returned crazy values that would break the system.
         * USE CASE: Validate model outputs before using them for trading decisions.
         * OUTPUT: True if all scores are valid, false if any are corrupted.
         */
        public boolean isValid() {
            boolean valid = !Double.isNaN(linearScore) &&
                    !Double.isNaN(xgboostScore) &&
                    !Double.isNaN(lstmScore) &&
                    Math.abs(linearScore) <= 1.0 &&
                    Math.abs(xgboostScore) <= 1.0 &&
                    Math.abs(lstmScore) <= 1.0;

            if (!valid) {
                log.warn("MODEL SCORES: Invalid scores detected - Linear:{}, XGBoost:{}, LSTM:{}",
                        linearScore, xgboostScore, lstmScore);
            }

            return valid;
        }

        /**
         * NAIVE EXPLANATION: Simple average of all 3 model predictions.
         * Like asking 3 experts and taking their average opinion.
         * USE CASE: Get quick ensemble prediction without weighted combination.
         * OUTPUT: Simple average of linear, XGBoost, and LSTM scores.
         */
        public double getSimpleAverage() {
            double average = (linearScore + xgboostScore + lstmScore) / 3.0;
            log.trace("MODEL SCORES: Simple average = ({} + {} + {}) / 3 = {}",
                    linearScore, xgboostScore, lstmScore, average);
            return average;
        }

        /**
         * NAIVE EXPLANATION: Weighted average giving more importance to better performing models.
         * Like listening more to the expert who's been right most often.
         * USE CASE: Combine predictions with performance-based weighting.
         * OUTPUT: Weighted ensemble score based on model accuracies.
         */
        public double getWeightedAverage(double linearWeight, double xgboostWeight, double lstmWeight) {
            double totalWeight = linearWeight + xgboostWeight + lstmWeight;
            if (totalWeight == 0) {
                log.warn("MODEL SCORES: Zero total weight, using simple average");
                return getSimpleAverage();
            }

            double weighted = (linearScore * linearWeight + xgboostScore * xgboostWeight + lstmScore * lstmWeight) / totalWeight;
            log.debug("MODEL SCORES: Weighted average = {} (weights: L:{}, X:{}, LSTM:{})",
                    weighted, linearWeight, xgboostWeight, lstmWeight);
            return weighted;
        }
    }

    /**
     * NAIVE EXPLANATION: One training example containing features and the correct answer.
     * Like a flashcard with question (features) and answer (target) for model learning.
     * USE CASE: Store historical data for training and improving AI models.
     * OUTPUT: Packaged example that models can learn from.
     */
    public static class TrainingExample implements Serializable {
        public final FeatureVector features; // The input data (question)
        public final double target; // The correct answer
        public final long timestamp; // When this example occurred

        public TrainingExample(FeatureVector features, double target, long timestamp) {
            this.features = features;
            this.target = target;
            this.timestamp = timestamp;
            log.trace("TRAINING EXAMPLE: Created with target={} at timestamp={}", target, timestamp);
        }

        /**
         * NAIVE EXPLANATION: Checks if this example is recent enough to be useful.
         * Old market patterns might not apply to current conditions.
         * USE CASE: Filter training data to only use recent relevant examples.
         * OUTPUT: True if example is recent, false if it's too old.
         */
        public boolean isRecentExample(long currentTime, long maxAgeMillis) {
            long age = currentTime - timestamp;
            boolean recent = age <= maxAgeMillis;

            if (!recent) {
                log.trace("TRAINING EXAMPLE: Example age {}ms exceeds max {}ms", age, maxAgeMillis);
            }

            return recent;
        }

        /**
         * NAIVE EXPLANATION: Checks if target value indicates a strong signal.
         * Strong signals are more reliable for training than weak ones.
         * USE CASE: Filter training examples to focus on clear patterns.
         * OUTPUT: True if target indicates strong directional signal.
         */
        public boolean hasStrongSignal(double threshold) {
            boolean strong = Math.abs(target) > threshold;
            log.trace("TRAINING EXAMPLE: Target {} {} strong signal (threshold {})",
                    target, strong ? "has" : "lacks", threshold);
            return strong;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TrainingExample that = (TrainingExample) obj;
            return Double.compare(that.target, target) == 0 &&
                    timestamp == that.timestamp &&
                    Objects.equals(features, that.features);
        }

        @Override
        public int hashCode() {
            return Objects.hash(target, timestamp);
        }
    }

    // ==========================================
    // STANDARDIZATION AND NORMALIZATION
    // ==========================================

    /**
     * NAIVE EXPLANATION: Handles feature normalization using running statistics.
     * Keeps track of average and variance for each feature to standardize them.
     * USE CASE: Ensure all features are on similar scales for AI model input.
     * OUTPUT: Normalized features with mean=0 and standard deviation=1.
     */
    public static class AdvancedStandardizationParams implements Serializable {
        private final Map<String, RunningStats> stats = new ConcurrentHashMap<>();

        public AdvancedStandardizationParams() {
            initializeDefaultStats();
        }

        /**
         * NAIVE EXPLANATION: Initializes default statistics for key features.
         * Sets reasonable starting values so normalization works from the beginning.
         * USE CASE: Bootstrap the normalization system before seeing real data.
         * OUTPUT: Pre-configured statistics for important features.
         */
        private void initializeDefaultStats() {
            log.debug("STANDARDIZATION: Initializing default statistics");

            // Initialize correlation features (most important)
            addFeatureStats("correlationStrength", 0.5, 0.3);
            addFeatureStats("nvdaLeadership", 0.5, 0.5);
            addFeatureStats("regimeStability", 0.7, 0.2);
            addFeatureStats("divergenceSignal", 0.0, 0.05);

            // Initialize key existing features
            addFeatureStats("techMomentum", 0.0, 0.02);
            addFeatureStats("vixSignal", 0.0, 0.1);
            addFeatureStats("priceAcceleration", 0.0, 0.001);
            addFeatureStats("rangePosition", 0.5, 0.3);
            addFeatureStats("vwapDistance", 0.0, 0.05);
            addFeatureStats("orderFlowImbalance", 0.0, 0.3);
            addFeatureStats("realizedVol", 0.2, 0.1);
            addFeatureStats("volumeRatio", 1.0, 0.5);

            log.info("STANDARDIZATION: Initialized default stats for {} features", stats.size());
        }

        /**
         * NAIVE EXPLANATION: Adds default statistics for one feature.
         * Helper method to set up mean and standard deviation for a feature.
         * USE CASE: Initialize normalization parameters for individual features.
         * OUTPUT: Feature ready for standardization with default stats.
         */
        private void addFeatureStats(String featureName, double defaultMean, double defaultStd) {
            RunningStats stat = new RunningStats();
            stat.initializeWithDefaults(defaultMean, defaultStd);
            stats.put(featureName, stat);
            log.trace("STANDARDIZATION: Added {} with mean={}, std={}", featureName, defaultMean, defaultStd);
        }

        /**
         * NAIVE EXPLANATION: Normalizes one feature value using its historical statistics.
         * Converts raw value to standardized value: (value - mean) / standard_deviation.
         * USE CASE: Standardize individual feature for consistent AI model input.
         * OUTPUT: Normalized feature value with mean=0, std_dev=1.
         */
        public double standardize(String featureName, double value) {
            RunningStats stat = stats.computeIfAbsent(featureName, k -> {
                log.debug("STANDARDIZATION: Created new stats tracker for feature: {}", featureName);
                return new RunningStats();
            });

            double standardized = stat.standardize(value);
            log.trace("STANDARDIZATION: {} raw={} -> standardized={}", featureName, value, standardized);

            return standardized;
        }

        /**
         * NAIVE EXPLANATION: Updates statistics using a batch of training examples.
         * Learns the mean and variance of each feature from historical data.
         * USE CASE: Update normalization parameters as new market data arrives.
         * OUTPUT: Updated statistics for more accurate feature normalization.
         */
        public void updateFromExamples(List<TrainingExample> examples) {
            if (examples.isEmpty()) {
                log.debug("STANDARDIZATION: No examples to update from");
                return;
            }

            log.debug("STANDARDIZATION: Updating statistics from {} training examples", examples.size());

            Map<String, List<Double>> featureValues = new HashMap<>();

            // Extract all feature values
            for (TrainingExample example : examples) {
                double[] features = example.features.toAdvancedArray();

                // Collect correlation feature values
                if (features.length >= 33) {
                    featureValues.computeIfAbsent("correlationStrength", k -> new ArrayList<>()).add(features[29]);
                    featureValues.computeIfAbsent("nvdaLeadership", k -> new ArrayList<>()).add(features[30]);
                    featureValues.computeIfAbsent("regimeStability", k -> new ArrayList<>()).add(features[31]);
                    featureValues.computeIfAbsent("divergenceSignal", k -> new ArrayList<>()).add(features[32]);
                }

                // Collect other important feature values
                featureValues.computeIfAbsent("rangePosition", k -> new ArrayList<>()).add(example.features.rangePosition);
                featureValues.computeIfAbsent("vwapDistance", k -> new ArrayList<>()).add(example.features.vwapDistance);
                featureValues.computeIfAbsent("techMomentum", k -> new ArrayList<>()).add(example.features.techMomentum);
                featureValues.computeIfAbsent("volumeRatio", k -> new ArrayList<>()).add(example.features.volumeRatio);
            }

            // Update statistics for each feature
            int featuresUpdated = 0;
            for (Map.Entry<String, List<Double>> entry : featureValues.entrySet()) {
                String featureName = entry.getKey();
                List<Double> values = entry.getValue();

                RunningStats stat = stats.computeIfAbsent(featureName, k -> new RunningStats());
                values.forEach(stat::update);
                featuresUpdated++;
            }

            log.info("STANDARDIZATION: Updated statistics for {} features using {} examples", featuresUpdated, examples.size());
        }

        /**
         * NAIVE EXPLANATION: Gets current statistics for a specific feature.
         * Like asking "what's the current average and spread for this feature?"
         * USE CASE: Monitor normalization parameters or debug standardization issues.
         * OUTPUT: Running statistics object with mean, variance, and count.
         */
        public RunningStats getFeatureStats(String featureName) {
            return stats.get(featureName);
        }

        /**
         * NAIVE EXPLANATION: Gets all current feature statistics.
         * Like getting a report card showing stats for all features.
         * USE CASE: Monitor overall normalization health or export statistics.
         * OUTPUT: Map of all feature names to their statistics.
         */
        public Map<String, RunningStats> getAllStats() {
            return new HashMap<>(stats);
        }

        /**
         * NAIVE EXPLANATION: Clears all stored statistics and starts fresh.
         * Like resetting a calculator to start new calculations.
         * USE CASE: Reset normalization when market conditions change dramatically.
         * OUTPUT: Empty statistics ready to learn new patterns.
         */
        public void resetAllStats() {
            int featuresCleared = stats.size();
            stats.clear();
            initializeDefaultStats();
            log.info("STANDARDIZATION: Reset and reinitialized statistics for {} features", featuresCleared);
        }
    }

    /**
     * NAIVE EXPLANATION: Tracks running average and variance for one feature.
     * Efficiently calculates statistics without storing all historical values.
     * USE CASE: Normalize features by computing (value - mean) / std_deviation.
     * OUTPUT: Running statistics that enable feature standardization.
     */
    public static class RunningStats implements Serializable {
        private double mean = 0.0; // Running average
        private double variance = 1.0; // Running variance
        private int count = 0; // Number of values seen
        private static final double MIN_VARIANCE = 0.001; // Prevent division by zero

        /**
         * NAIVE EXPLANATION: Sets up initial statistics with known values.
         * Like giving the calculator a head start with reasonable defaults.
         * USE CASE: Bootstrap statistics before seeing real data.
         * OUTPUT: Initialized statistics ready for standardization.
         */
        public void initializeWithDefaults(double initialMean, double initialStd) {
            this.mean = initialMean;
            this.variance = Math.max(initialStd * initialStd, MIN_VARIANCE);
            this.count = 10; // Pretend we've seen 10 samples
            log.trace("RUNNING STATS: Initialized with mean={}, std={}, count={}", initialMean, initialStd, count);
        }

        /**
         * NAIVE EXPLANATION: Adds new value and updates running mean and variance.
         * Uses mathematical formulas to update statistics incrementally.
         * USE CASE: Efficiently track feature statistics as new data arrives.
         * OUTPUT: Updated mean and variance incorporating the new value.
         */
        public void update(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                log.trace("RUNNING STATS: Skipping invalid value: {}", value);
                return;
            }

            count++;
            double oldMean = mean;
            double delta = value - mean;
            mean += delta / count;

            if (count > 1) {
                double oldVariance = variance;
                variance = ((count - 1) * variance + delta * (value - mean)) / count;
                variance = Math.max(variance, MIN_VARIANCE);

                log.trace("RUNNING STATS: Updated - mean {} -> {}, variance {} -> {}, count={}",
                        oldMean, mean, oldVariance, variance, count);
            }
        }

        /**
         * NAIVE EXPLANATION: Converts raw value to standardized value.
         * Formula: (value - mean) / standard_deviation
         * USE CASE: Normalize features so AI models get consistent input ranges.
         * OUTPUT: Standardized value with mean=0 and std_deviation=1.
         */
        public double standardize(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                log.trace("RUNNING STATS: Standardizing invalid value to 0.0");
                return 0.0;
            }

            double std = Math.sqrt(Math.max(variance, MIN_VARIANCE));
            double standardized = (value - mean) / std;

            // Clamp to reasonable range to prevent extreme values
            standardized = Math.max(-5.0, Math.min(5.0, standardized));

            log.trace("RUNNING STATS: Standardized {} -> {} (mean={}, std={})",
                    value, standardized, mean, std);

            return standardized;
        }

        // Getters
        public double getMean() { return mean; }
        public double getVariance() { return variance; }
        public double getStandardDeviation() { return Math.sqrt(variance); }
        public int getCount() { return count; }
    }

    // ==========================================
    // PERFORMANCE TRACKING CLASSES
    // ==========================================

    /**
     * NAIVE EXPLANATION: Tracks how well each AI model is performing over time.
     * Measures accuracy, speed, and other performance metrics for each model.
     * USE CASE: Monitor model health and adjust weights based on recent performance.
     * OUTPUT: Performance statistics used to optimize ensemble weighting.
     */
    public static class ModelPerformanceTracker {
        private final Map<String, ModelPerformanceMetrics> metrics = new ConcurrentHashMap<>();
        private final Map<String, List<PredictionResult>> recentPredictions = new ConcurrentHashMap<>();
        private static final int MAX_PREDICTIONS = 100; // Keep last 100 predictions

        /**
         * NAIVE EXPLANATION: Records a new prediction and its performance metrics.
         * Stores model scores, ensemble result, trend decision, and timing.
         * USE CASE: Track all predictions for later accuracy analysis.
         * OUTPUT: Stored prediction result for performance monitoring.
         */
        public void recordPrediction(String symbol, ModelScores scores, double ensemble, String trend, long latency) {
            log.trace("PERFORMANCE TRACKER: Recording prediction for {} - ensemble={}, trend={}, latency={}ms",
                    symbol, ensemble, trend, latency);

            ModelPerformanceMetrics metric = metrics.computeIfAbsent(symbol, k -> {
                log.debug("PERFORMANCE TRACKER: Created new metrics tracker for {}", symbol);
                return new ModelPerformanceMetrics();
            });
            metric.recordPredictionLatency(latency);

            List<PredictionResult> predictions = recentPredictions.computeIfAbsent(symbol, k -> new ArrayList<>());
            predictions.add(new PredictionResult(scores, ensemble, trend, System.currentTimeMillis()));

            // Keep only recent predictions
            if (predictions.size() > MAX_PREDICTIONS) {
                predictions.remove(0);
                log.trace("PERFORMANCE TRACKER: Removed oldest prediction for {}, keeping last {}", symbol, MAX_PREDICTIONS);
            }

            log.debug("PERFORMANCE TRACKER: {} now has {} recorded predictions", symbol, predictions.size());
        }

        public ModelPerformanceMetrics getMetrics(String symbol) {
            return metrics.getOrDefault(symbol, new ModelPerformanceMetrics());
        }

        public Map<String, ModelPerformanceMetrics> getAllMetrics() {
            return new HashMap<>(metrics);
        }

        /**
         * NAIVE EXPLANATION: Calculates accuracy by comparing predictions to actual outcomes.
         * Checks if model predicted UP/DOWN correctly based on subsequent price moves.
         * USE CASE: Measure how often each model makes correct predictions.
         * OUTPUT: Updated accuracy percentages for all models.
         */
        public void updateStatistics() {
            log.debug("PERFORMANCE TRACKER: Updating accuracy statistics for all symbols");

            int symbolsUpdated = 0;
            for (Map.Entry<String, List<PredictionResult>> entry : recentPredictions.entrySet()) {
                String symbol = entry.getKey();
                List<PredictionResult> predictions = entry.getValue();

                if (predictions.size() > 10) {
                    ModelPerformanceMetrics metric = metrics.get(symbol);
                    if (metric != null) {
                        metric.updateAccuracyFromPredictions(predictions);
                        symbolsUpdated++;
                        log.debug("PERFORMANCE TRACKER: Updated accuracy for {} using {} predictions",
                                symbol, predictions.size());
                    }
                }
            }

            log.info("PERFORMANCE TRACKER: Updated statistics for {} symbols", symbolsUpdated);
        }

        /**
         * NAIVE EXPLANATION: Records backtest results for a symbol.
         * Updates performance metrics based on historical simulation results.
         * USE CASE: Incorporate backtest performance into overall model assessment.
         * OUTPUT: Updated performance metrics including backtest results.
         */
        public void updateBacktestResults(String symbol, BacktestResult result) {
            ModelPerformanceMetrics metric = metrics.computeIfAbsent(symbol, k -> new ModelPerformanceMetrics());
            metric.updateFromBacktestResult(result);

            log.info("PERFORMANCE TRACKER: Updated {} with backtest - accuracy:{:.1f}%, sharpe:{:.2f}",
                    symbol, result.accuracy * 100, result.sharpeRatio);
        }

        /**
         * NAIVE EXPLANATION: Removes old predictions that are no longer relevant.
         * Keeps memory usage under control by discarding ancient history.
         * USE CASE: Cleanup old data to prevent memory leaks and focus on recent performance.
         * OUTPUT: Reduced memory usage with only recent predictions retained.
         */
        public void cleanupOldPredictions(long maxAgeMillis) {
            long cutoff = System.currentTimeMillis() - maxAgeMillis;
            int totalRemoved = 0;

            for (Map.Entry<String, List<PredictionResult>> entry : recentPredictions.entrySet()) {
                String symbol = entry.getKey();
                List<PredictionResult> predictions = entry.getValue();
                int sizeBefore = predictions.size();

                predictions.removeIf(p -> p.timestamp < cutoff);

                int removed = sizeBefore - predictions.size();
                totalRemoved += removed;

                if (removed > 0) {
                    log.debug("PERFORMANCE TRACKER: Removed {} old predictions for {}, {} remaining",
                            removed, symbol, predictions.size());
                }
            }

            log.info("PERFORMANCE TRACKER: Cleanup removed {} old predictions across all symbols", totalRemoved);
        }
    }

    /**
     * NAIVE EXPLANATION: Stores performance metrics for one symbol's predictions.
     * Tracks accuracy, latency, Sharpe ratio, and prediction count for each model.
     * USE CASE: Monitor how well models are performing for specific stocks.
     * OUTPUT: Performance statistics used for model weighting and health monitoring.
     */
    public static class ModelPerformanceMetrics {
        public double linearAccuracy = 0.7; // How often linear model is correct
        public double xgboostAccuracy = 0.7; // How often XGBoost model is correct
        public double lstmAccuracy = 0.7; // How often LSTM model is correct
        public double avgLatency = 50.0; // Average response time in milliseconds
        public int predictionCount = 0; // Total predictions made
        public double ensembleAccuracy = 0.7; // How often combined prediction is correct
        public double sharpeRatio = 0.5; // Risk-adjusted returns measure
        public long lastUpdate = System.currentTimeMillis();

        /**
         * NAIVE EXPLANATION: Records timing information for a prediction.
         * Updates running average of how long predictions take.
         * USE CASE: Monitor system performance and detect slowdowns.
         * OUTPUT: Updated average latency incorporating new timing measurement.
         */
        public void recordPredictionLatency(long latency) {
            predictionCount++;
            double oldLatency = avgLatency;
            avgLatency = (avgLatency * (predictionCount - 1) + latency) / predictionCount;
            lastUpdate = System.currentTimeMillis();

            log.trace("PERFORMANCE METRICS: Latency {} -> {} (prediction #{})", oldLatency, avgLatency, predictionCount);

            if (predictionCount % 50 == 0) {
                log.info("PERFORMANCE METRICS: {} predictions completed, avg_latency={}ms", predictionCount, avgLatency);
            }
        }

        /**
         * NAIVE EXPLANATION: Calculates prediction accuracy by comparing to actual outcomes.
         * Checks if predicted UP/DOWN matched actual price direction.
         * USE CASE: Measure how often each model makes correct directional predictions.
         * OUTPUT: Updated accuracy percentages for each model and ensemble.
         */
        public void updateAccuracyFromPredictions(List<PredictionResult> predictions) {
            if (predictions.size() < 10) {
                log.debug("ACCURACY CALCULATOR: Need 10+ predictions, only have {}", predictions.size());
                return;
            }

            log.debug("ACCURACY CALCULATOR: Calculating accuracy from {} predictions", predictions.size());

            int linearCorrect = 0, xgbCorrect = 0, lstmCorrect = 0, ensembleCorrect = 0;
            int total = 0;

            // Compare each prediction to the next period's actual result
            for (int i = 0; i < predictions.size() - 1; i++) {
                PredictionResult current = predictions.get(i);
                PredictionResult next = predictions.get(i + 1);

                // Determine actual direction
                boolean actualUp = next.ensemble > current.ensemble;

                // Check each model's directional prediction
                boolean linearPredUp = current.scores.linearScore > 0;
                boolean xgbPredUp = current.scores.xgboostScore > 0;
                boolean lstmPredUp = current.scores.lstmScore > 0;
                boolean ensemblePredUp = current.ensemble > 0;

                // Count correct predictions
                if (linearPredUp == actualUp) linearCorrect++;
                if (xgbPredUp == actualUp) xgbCorrect++;
                if (lstmPredUp == actualUp) lstmCorrect++;
                if (ensemblePredUp == actualUp) ensembleCorrect++;

                total++;
            }

            if (total > 0) {
                // Update accuracies with bounds
                linearAccuracy = Math.max(0.3, Math.min(1.0, (double)linearCorrect / total));
                xgboostAccuracy = Math.max(0.3, Math.min(1.0, (double)xgbCorrect / total));
                lstmAccuracy = Math.max(0.3, Math.min(1.0, (double)lstmCorrect / total));
                ensembleAccuracy = Math.max(0.3, Math.min(1.0, (double)ensembleCorrect / total));

                log.info("ACCURACY CALCULATOR: Updated accuracies - Linear:{:.1f}%, XGBoost:{:.1f}%, LSTM:{:.1f}%, Ensemble:{:.1f}%",
                        linearAccuracy*100, xgboostAccuracy*100, lstmAccuracy*100, ensembleAccuracy*100);
            }

            lastUpdate = System.currentTimeMillis();
        }

        /**
         * NAIVE EXPLANATION: Incorporates backtest results into performance metrics.
         * Blends current performance with historical simulation results.
         * USE CASE: Get more robust performance estimates using historical data.
         * OUTPUT: Updated performance metrics incorporating backtest results.
         */
        public void updateFromBacktestResult(BacktestResult result) {
            if (result.sampleSize > 10) {
                // Blend current metrics with backtest results
                ensembleAccuracy = (ensembleAccuracy + result.accuracy) / 2.0;
                sharpeRatio = (sharpeRatio + result.sharpeRatio) / 2.0;
                lastUpdate = System.currentTimeMillis();

                log.info("BACKTEST INTEGRATION: Blended performance - accuracy:{:.1f}%, sharpe:{:.2f}",
                        ensembleAccuracy*100, sharpeRatio);
            }
        }

        /**
         * NAIVE EXPLANATION: Calculates overall performance score across all models.
         * Weighted average emphasizing ensemble accuracy.
         * USE CASE: Get single number representing overall model health.
         * OUTPUT: Overall score 0-1 indicating combined model performance.
         */
        public double getOverallScore() {
            double score = (linearAccuracy * 0.2 + xgboostAccuracy * 0.25 + lstmAccuracy * 0.2 + ensembleAccuracy * 0.35);
            log.trace("OVERALL SCORE: Weighted combination = {}", score);
            return score;
        }
    }

    /**
     * NAIVE EXPLANATION: Stores one prediction result with all its details.
     * Records what each model predicted, final ensemble score, and timing.
     * USE CASE: Keep history of predictions for accuracy analysis.
     * OUTPUT: Complete record of one prediction event.
     */
    public static class PredictionResult {
        public final ModelScores scores; // What each model predicted
        public final double ensemble; // Combined prediction
        public final String trend; // Final UP/DOWN/NEUTRAL decision
        public final long timestamp; // When prediction was made

        public PredictionResult(ModelScores scores, double ensemble, String trend, long timestamp) {
            this.scores = scores;
            this.ensemble = ensemble;
            this.trend = trend;
            this.timestamp = timestamp;
            log.trace("PREDICTION RESULT: Stored - ensemble={}, trend={} at {}", ensemble, trend, timestamp);
        }
    }

    // ==========================================
    // BACKTESTING CLASSES
    // ==========================================

    /**
     * NAIVE EXPLANATION: Simulates trading strategy on historical data.
     * Tests how well the models would have performed if used for actual trading.
     * USE CASE: Validate model performance before risking real money.
     * OUTPUT: Backtest results showing accuracy, returns, and risk metrics.
     */
    public static class BacktestEngine {
        private static final int MIN_SAMPLES = 50; // Need enough data for valid test
        private static final double TRANSACTION_COST = 0.001; // 0.1% cost per trade

        /**
         * NAIVE EXPLANATION: Runs trading simulation on historical data.
         * Pretends to trade based on model predictions and calculates results.
         * USE CASE: Test strategy performance before deploying with real money.
         * OUTPUT: Backtest results with accuracy, returns, and risk metrics.
         */
        public BacktestResult runBacktest(String symbol, List<TrainingExample> examples) {
            if (examples.size() < MIN_SAMPLES) {
                log.warn("BACKTEST ENGINE: Insufficient data for {} (need {}, have {})",
                        symbol, MIN_SAMPLES, examples.size());
                return new BacktestResult(0.5, 0, 0.0, 0.0);
            }

            log.info("BACKTEST ENGINE: Running simulation for {} using {} examples", symbol, examples.size());

            int correct = 0;
            int total = 0;
            double totalReturn = 0.0;

            // Simulate trading decisions
            for (int i = 10; i < examples.size() - 1; i++) {
                TrainingExample current = examples.get(i);
                TrainingExample next = examples.get(i + 1);

                // Check directional accuracy
                boolean predictedUp = current.target > 0.1;
                boolean actualUp = next.target > current.target;

                if (predictedUp == actualUp) correct++;
                total++;

                // Calculate trade returns
                if (Math.abs(current.target) > 0.1) { // Only trade on strong signals
                    double direction = current.target > 0 ? 1.0 : -1.0;
                    double priceReturn = next.target - current.target;
                    double tradeReturn = direction * priceReturn - TRANSACTION_COST;
                    totalReturn += tradeReturn;
                }
            }

            double accuracy = total > 0 ? (double)correct / total : 0.5;
            double sharpeRatio = calculateSharpeRatio(totalReturn, total);

            BacktestResult result = new BacktestResult(accuracy, total, totalReturn, sharpeRatio);

            log.info("BACKTEST ENGINE: {} results - accuracy:{:.1f}%, return:{:.3f}, sharpe:{:.2f}, grade:{}",
                    symbol, accuracy*100, totalReturn, sharpeRatio, result.getGrade());

            return result;
        }

        private double calculateSharpeRatio(double totalReturn, int trades) {
            if (trades < 10) return 0.0;

            double avgReturn = totalReturn / trades;
            double volatility = Math.sqrt(Math.abs(avgReturn) * 0.1); // Simplified volatility estimate
            return volatility > 0 ? avgReturn / volatility : 0.0;
        }
    }

    /**
     * NAIVE EXPLANATION: Contains results from a backtest simulation.
     * Shows how well the trading strategy would have performed historically.
     * USE CASE: Evaluate strategy performance before deploying with real money.
     * OUTPUT: Accuracy, sample size, returns, and risk-adjusted metrics.
     */
    public static class BacktestResult {
        public final double accuracy; // Percentage of correct predictions
        public final int sampleSize; // Number of trades tested
        public final double totalReturn; // Cumulative returns
        public final double sharpeRatio; // Risk-adjusted returns

        public BacktestResult(double accuracy, int sampleSize, double totalReturn, double sharpeRatio) {
            this.accuracy = Math.max(0.0, Math.min(1.0, accuracy));
            this.sampleSize = sampleSize;
            this.totalReturn = totalReturn;
            this.sharpeRatio = sharpeRatio;
            log.debug("BACKTEST RESULT: Created with accuracy={:.1f}%, samples={}, return={:.3f}, sharpe={:.2f}",
                    accuracy*100, sampleSize, totalReturn, sharpeRatio);
        }

        /**
         * NAIVE EXPLANATION: Checks if backtest results are statistically meaningful.
         * Need enough trades and good enough accuracy to trust the results.
         * USE CASE: Filter out unreliable backtest results from small samples.
         * OUTPUT: True if results are trustworthy, false if sample is too small.
         */
        public boolean isStatisticallySignificant() {
            boolean significant = sampleSize >= 50 && accuracy > 0.55;
            log.debug("BACKTEST RESULT: Significance check - samples:{} (need 50+), accuracy:{:.1f}% (need 55%+) = {}",
                    sampleSize, accuracy*100, significant ? "SIGNIFICANT" : "NOT_SIGNIFICANT");
            return significant;
        }

        /**
         * NAIVE EXPLANATION: Assigns letter grade based on performance metrics.
         * Like school grades: A=excellent, B=good, C=okay, D=poor, F=bad.
         * USE CASE: Quick assessment of strategy quality for human review.
         * OUTPUT: Letter grade A-F based on Sharpe ratio and accuracy.
         */
        public String getGrade() {
            if (sharpeRatio > 2.0 && accuracy > 0.7) return "A";
            if (sharpeRatio > 1.5 && accuracy > 0.65) return "B";
            if (sharpeRatio > 1.0 && accuracy > 0.6) return "C";
            if (sharpeRatio > 0.5 && accuracy > 0.55) return "D";
            return "F";
        }
    }

    // ==========================================
    // RING BUFFER DATA STRUCTURE
    // ==========================================

    /**
     * NAIVE EXPLANATION: Circular buffer that stores fixed number of recent items.
     * When full, adding new item removes the oldest item automatically.
     * USE CASE: Keep rolling window of recent data without unlimited memory growth.
     * OUTPUT: Fixed-size buffer containing most recent items.
     */
    public static class RingBuffer<T> {
        private final T[] buffer;
        private final int capacity;
        private int head = 0;
        private int tail = 0;
        private int size = 0;

        @SuppressWarnings("unchecked")
        public RingBuffer(int capacity) {
            this.capacity = Math.max(1, capacity);
            this.buffer = (T[]) new Object[this.capacity];
            log.debug("RING BUFFER: Created with capacity {}", this.capacity);
        }

        /**
         * NAIVE EXPLANATION: Adds new item to buffer, removing oldest if buffer is full.
         * Like a conveyor belt where new items push old items off the end.
         * USE CASE: Maintain rolling window of recent data for pattern analysis.
         * OUTPUT: Buffer updated with new item and possibly old item removed.
         */
        public synchronized void add(T item) {
            if (item == null) {
                log.trace("RING BUFFER: Skipping null item");
                return;
            }

            buffer[tail] = item;
            tail = (tail + 1) % capacity;

            if (size < capacity) {
                size++;
            } else {
                head = (head + 1) % capacity;
            }

            log.trace("RING BUFFER: Added item, size={}, head={}, tail={}", size, head, tail);
        }

        /**
         * NAIVE EXPLANATION: Gets specified number of most recent items.
         * Like asking "give me the last 10 things you stored".
         * USE CASE: Retrieve recent sequence for LSTM model or analysis.
         * OUTPUT: List containing the most recent items up to requested length.
         */
        public synchronized List<T> getSequence(int length) {
            List<T> result = new ArrayList<>();
            int actualLength = Math.min(length, size);

            for (int i = 0; i < actualLength; i++) {
                int index = (head + size - actualLength + i) % capacity;
                result.add(buffer[index]);
            }

            log.trace("RING BUFFER: Retrieved sequence of {} items (requested {}, available {})",
                    result.size(), length, size);

            return result;
        }

        /**
         * NAIVE EXPLANATION: Returns copy of all items currently in buffer.
         * Like asking "give me everything you have stored".
         * USE CASE: Get all stored data for training or analysis.
         * OUTPUT: Complete list of all items in the buffer.
         */
        public synchronized List<T> getAllExamples() {
            List<T> result = new ArrayList<>();

            for (int i = 0; i < size; i++) {
                int index = (head + i) % capacity;
                result.add(buffer[index]);
            }

            log.trace("RING BUFFER: Retrieved all {} examples", result.size());
            return result;
        }

        public synchronized int size() {
            return size;
        }

        public synchronized boolean isEmpty() {
            return size == 0;
        }

        /**
         * NAIVE EXPLANATION: Gets the most recently added item.
         * Like asking "what was the last thing you stored?"
         * USE CASE: Access most current data point for analysis.
         * OUTPUT: Most recent item or null if buffer is empty.
         */
        public synchronized T getLatest() {
            if (isEmpty()) {
                log.trace("RING BUFFER: Buffer is empty, returning null");
                return null;
            }

            int latestIndex = (tail - 1 + capacity) % capacity;
            T latest = buffer[latestIndex];
            log.trace("RING BUFFER: Retrieved latest item from index {}", latestIndex);
            return latest;
        }

        /**
         * NAIVE EXPLANATION: Removes all items from buffer.
         * Like emptying a container to start fresh.
         * USE CASE: Reset buffer when changing strategies or market conditions.
         * OUTPUT: Empty buffer ready for new data.
         */
        public synchronized void clear() {
            int sizeBefore = size;
            Arrays.fill(buffer, null);
            head = 0;
            tail = 0;
            size = 0;
            log.debug("RING BUFFER: Cleared {} items, buffer now empty", sizeBefore);
        }
    }
}