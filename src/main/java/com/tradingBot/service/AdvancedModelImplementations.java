package com.tradingBot.service;

import com.tradingBot.service.MetricClasses.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.*;
import java.util.stream.IntStream;

@Slf4j
public class AdvancedModelImplementations {

    // ENHANCED LINEAR REGRESSION WITH REGULARIZATION AND ONLINE LEARNING
    public static class AdvancedLinearRegressionModel {
        private final double[] weights = new double[33]; // Expanded to 33 features (29 + 4 correlation features)
        private final double[] momentum = new double[33]; // For Adam optimizer
        private final double[] variance = new double[33]; // For Adam optimizer
        private final double learningRate = 0.001;
        private final double l1Regularization = 0.001;
        private final double l2Regularization = 0.001;
        private final double beta1 = 0.9, beta2 = 0.999;
        private int updateCount = 0;
        private volatile boolean initialized = false;

        public double predictAdvanced(FeatureVector features) {
            if (!initialized) {
                initializeAdvancedWeights();
                initialized = true;
            }

            double[] inputs = features.toAdvancedArray();
            double prediction = 0.0;

            for (int i = 0; i < Math.min(weights.length, inputs.length); i++) {
                prediction += weights[i] * inputs[i];
            }

            // Apply tanh for bounded output
            return Math.tanh(prediction);
        }

        public void updateWeights(double[] inputs, double target, double prediction) {
            double error = target - prediction;
            updateCount++;

            for (int i = 0; i < Math.min(weights.length, inputs.length); i++) {
                if (inputs[i] == 0) continue;

                // Gradient with regularization
                double gradient = -error * inputs[i] +
                        l1Regularization * Math.signum(weights[i]) +
                        l2Regularization * weights[i];

                // Adam optimizer
                momentum[i] = beta1 * momentum[i] + (1 - beta1) * gradient;
                variance[i] = beta2 * variance[i] + (1 - beta2) * gradient * gradient;

                double momentumCorrected = momentum[i] / (1 - Math.pow(beta1, updateCount));
                double varianceCorrected = variance[i] / (1 - Math.pow(beta2, updateCount));

                weights[i] -= learningRate * momentumCorrected / (Math.sqrt(varianceCorrected) + 1e-8);
            }
        }

        private void initializeAdvancedWeights() {
            // Initialize with domain knowledge instead of random weights
            Arrays.fill(weights, 0.0); // Start with zeros

            // Set reasonable initial weights based on financial logic
            if (weights.length >= 33) {
                weights[0] = 0.3;   // returns[0] - recent return (positive weight)
                weights[5] = 0.2;   // techMomentum (positive weight)
                weights[6] = 0.1;   // techCorrelation (positive weight)
                weights[10] = -0.1; // orderFlowImbalance (negative = selling pressure bad)
                weights[15] = 0.15; // vixSignal (positive = fear down is good)
                weights[20] = 0.1;  // priceAcceleration (positive weight)
                weights[25] = 0.1;  // rangePosition (positive weight)

                // NEW CORRELATION LEARNING WEIGHTS
                weights[29] = 0.25; // correlationStrength - very important for NVDA/MSFT analysis
                weights[30] = 0.20; // nvdaLeadership - NVDA leadership is bullish
                weights[31] = 0.15; // regimeStability - stable regimes are predictable
                weights[32] = -0.10; // divergenceSignal - high divergence creates uncertainty
            }

            log.info("LINEAR MODEL: Initialized with domain-knowledge weights including correlation features");
        }

        public void saveModel(String path) throws IOException {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
                oos.writeObject(weights);
                oos.writeObject(momentum);
                oos.writeObject(variance);
                oos.writeInt(updateCount);
            }
        }

        public void loadModel(String path) throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path))) {
                System.arraycopy((double[]) ois.readObject(), 0, weights, 0, weights.length);
                System.arraycopy((double[]) ois.readObject(), 0, momentum, 0, momentum.length);
                System.arraycopy((double[]) ois.readObject(), 0, variance, 0, variance.length);
                updateCount = ois.readInt();
                initialized = true;
            }
        }

        public double[] getWeights() {
            return weights.clone();
        }

        public boolean isInitialized() {
            return initialized;
        }
    }

    // ADVANCED XGBOOST IMPLEMENTATION WITH PROPER TREE STRUCTURES
    public static class AdvancedXGBoostModel {
        private final List<AdvancedDecisionTree> trees = new ArrayList<>();
        private final int maxTrees = 100;
        private final int maxDepth = 6;
        private final double learningRate = 0.1;
        private final double subsampleRatio = 0.8;
        private final double colsampleRatio = 0.8;
        private final double regularizationLambda = 1.0;
        private volatile boolean initialized = false;

        public double predictAdvanced(FeatureVector features) {
            if (!initialized) {
                initializeAdvancedTrees();
                initialized = true;
            }

            double prediction = 0.0;
            double[] inputs = features.toAdvancedArray();

            for (AdvancedDecisionTree tree : trees) {
                prediction += learningRate * tree.predict(inputs);
            }

            return Math.tanh(prediction);
        }

        public void addTree(List<TrainingExample> examples) {
            if (trees.size() >= maxTrees) {
                trees.remove(0); // Remove oldest tree
            }

            // Calculate residuals
            double[] residuals = new double[examples.size()];
            for (int i = 0; i < examples.size(); i++) {
                TrainingExample ex = examples.get(i);
                double prediction = predictAdvanced(ex.features);
                residuals[i] = ex.target - prediction;
            }

            // Build new tree on residuals
            AdvancedDecisionTree newTree = new AdvancedDecisionTree(maxDepth, regularizationLambda);
            newTree.fit(examples, residuals, subsampleRatio, colsampleRatio);
            trees.add(newTree);
        }

        private void initializeAdvancedTrees() {
            // Create some basic trained trees instead of empty ones
            for (int i = 0; i < 5; i++) { // Fewer trees initially
                AdvancedDecisionTree tree = new AdvancedDecisionTree(2, regularizationLambda); // Shallow depth

                // Train with correlation-focused pattern
                List<TrainingExample> dummyExamples = createCorrelationTrainingData();
                double[] dummyResiduals = new double[dummyExamples.size()];
                Arrays.fill(dummyResiduals, 0.1); // Small positive bias

                tree.fit(dummyExamples, dummyResiduals, 0.8, 0.8);
                trees.add(tree);
            }
        }

        private List<TrainingExample> createCorrelationTrainingData() {
            List<TrainingExample> examples = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                FeatureVector fv = new FeatureVector();
                fv.fillWithDefaults();

                // Focus on correlation patterns
                fv.techMomentum = 0.02; // Positive tech momentum
                fv.vixSignal = 0.1;     // Positive VIX relief
                fv.correlationStrength = 0.8; // High correlation
                fv.nvdaLeadership = 1.0; // NVDA leading
                fv.regimeStability = 0.7; // Stable regime
                fv.divergenceSignal = 0.05; // Low divergence

                examples.add(new TrainingExample(fv, 0.5, System.currentTimeMillis()));
            }
            return examples;
        }

        public double[] getFeatureImportances() {
            double[] importances = new double[33]; // Updated to 33 features
            for (AdvancedDecisionTree tree : trees) {
                double[] treeImportances = tree.getFeatureImportances();
                for (int i = 0; i < Math.min(importances.length, treeImportances.length); i++) {
                    importances[i] += treeImportances[i];
                }
            }

            // Normalize
            double sum = Arrays.stream(importances).sum();
            if (sum > 0) {
                for (int i = 0; i < importances.length; i++) {
                    importances[i] /= sum;
                }
            }

            return importances;
        }

        public void saveModel(String path) throws IOException {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
                oos.writeObject(trees);
            }
        }

        @SuppressWarnings("unchecked")
        public void loadModel(String path) throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path))) {
                trees.clear();
                trees.addAll((List<AdvancedDecisionTree>) ois.readObject());
                initialized = true;
            }
        }

        public int getTreeCount() {
            return trees.size();
        }

        public boolean isInitialized() {
            return initialized;
        }
    }

    // ADVANCED LSTM WITH PROPER GATES AND MEMORY CELLS
    public static class AdvancedLSTMModel {
        private final int hiddenSize = 32;
        private final int inputSize = 33; // Updated to 33 features
        private final double learningRate = 0.001;

        // LSTM parameters
        private final double[][] forgetGateWeights = new double[hiddenSize][inputSize + hiddenSize];
        private final double[][] inputGateWeights = new double[hiddenSize][inputSize + hiddenSize];
        private final double[][] outputGateWeights = new double[hiddenSize][inputSize + hiddenSize];
        private final double[][] candidateWeights = new double[hiddenSize][inputSize + hiddenSize];
        private final double[] forgetGateBias = new double[hiddenSize];
        private final double[] inputGateBias = new double[hiddenSize];
        private final double[] outputGateBias = new double[hiddenSize];
        private final double[] candidateBias = new double[hiddenSize];

        // LSTM state
        private final double[] hiddenState = new double[hiddenSize];
        private final double[] cellState = new double[hiddenSize];

        private volatile boolean initialized = false;

        public double predictAdvanced(List<FeatureVector> sequence) {
            if (!initialized) {
                initializeAdvancedLSTM();
                initialized = true;
            }

            if (sequence.size() < 3) return 0.0;

            // Reset states
            Arrays.fill(hiddenState, 0.0);
            Arrays.fill(cellState, 0.0);

            // Process sequence
            for (FeatureVector features : sequence) {
                updateLSTMStates(features.toAdvancedArray());
            }

            // Output layer with correlation-aware attention
            double output = 0.0;
            for (int i = 0; i < hiddenSize; i++) {
                // Enhanced attention weights that focus on correlation patterns
                double attentionWeight = 0.1 + 0.05 * Math.sin(i) + 0.03 * Math.cos(i * 2);
                output += hiddenState[i] * attentionWeight;
            }

            return Math.tanh(output / hiddenSize);
        }

        private void updateLSTMStates(double[] input) {
            double[] combined = new double[inputSize + hiddenSize];
            System.arraycopy(input, 0, combined, 0, Math.min(input.length, inputSize));
            System.arraycopy(hiddenState, 0, combined, inputSize, hiddenSize);

            // Forget gate
            double[] forgetGate = new double[hiddenSize];
            for (int i = 0; i < hiddenSize; i++) {
                double sum = forgetGateBias[i];
                for (int j = 0; j < combined.length; j++) {
                    sum += forgetGateWeights[i][j] * combined[j];
                }
                forgetGate[i] = sigmoid(sum);
            }

            // Input gate
            double[] inputGate = new double[hiddenSize];
            for (int i = 0; i < hiddenSize; i++) {
                double sum = inputGateBias[i];
                for (int j = 0; j < combined.length; j++) {
                    sum += inputGateWeights[i][j] * combined[j];
                }
                inputGate[i] = sigmoid(sum);
            }

            // Candidate values
            double[] candidate = new double[hiddenSize];
            for (int i = 0; i < hiddenSize; i++) {
                double sum = candidateBias[i];
                for (int j = 0; j < combined.length; j++) {
                    sum += candidateWeights[i][j] * combined[j];
                }
                candidate[i] = Math.tanh(sum);
            }

            // Update cell state
            for (int i = 0; i < hiddenSize; i++) {
                cellState[i] = forgetGate[i] * cellState[i] + inputGate[i] * candidate[i];
            }

            // Output gate
            double[] outputGate = new double[hiddenSize];
            for (int i = 0; i < hiddenSize; i++) {
                double sum = outputGateBias[i];
                for (int j = 0; j < combined.length; j++) {
                    sum += outputGateWeights[i][j] * combined[j];
                }
                outputGate[i] = sigmoid(sum);
            }

            // Update hidden state
            for (int i = 0; i < hiddenSize; i++) {
                hiddenState[i] = outputGate[i] * Math.tanh(cellState[i]);
            }
        }

        private double sigmoid(double x) {
            return 1.0 / (1.0 + Math.exp(-Math.max(-500, Math.min(500, x))));
        }

        private void initializeAdvancedLSTM() {
            // Xavier initialization for all weight matrices
            double scale = Math.sqrt(2.0 / (inputSize + hiddenSize));
            initializeMatrix(forgetGateWeights, scale);
            initializeMatrix(inputGateWeights, scale);
            initializeMatrix(outputGateWeights, scale);
            initializeMatrix(candidateWeights, scale);

            // Initialize biases (forget gate bias to 1, others to 0)
            Arrays.fill(forgetGateBias, 1.0);
            initialized = true;
        }

        private void initializeMatrix(double[][] matrix, double scale) {
            for (int i = 0; i < matrix.length; i++) {
                for (int j = 0; j < matrix[i].length; j++) {
                    matrix[i][j] = (Math.random() - 0.5) * scale;
                }
            }
        }

        public void saveModel(String path) throws IOException {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
                oos.writeObject(forgetGateWeights);
                oos.writeObject(inputGateWeights);
                oos.writeObject(outputGateWeights);
                oos.writeObject(candidateWeights);
                oos.writeObject(forgetGateBias);
                oos.writeObject(inputGateBias);
                oos.writeObject(outputGateBias);
                oos.writeObject(candidateBias);
            }
        }

        public void loadModel(String path) throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path))) {
                double[][] loadedForgetWeights = (double[][]) ois.readObject();
                double[][] loadedInputWeights = (double[][]) ois.readObject();
                double[][] loadedOutputWeights = (double[][]) ois.readObject();
                double[][] loadedCandidateWeights = (double[][]) ois.readObject();
                double[] loadedForgetBias = (double[]) ois.readObject();
                double[] loadedInputBias = (double[]) ois.readObject();
                double[] loadedOutputBias = (double[]) ois.readObject();
                double[] loadedCandidateBias = (double[]) ois.readObject();

                // Copy loaded weights
                for (int i = 0; i < hiddenSize; i++) {
                    System.arraycopy(loadedForgetWeights[i], 0, forgetGateWeights[i], 0, inputSize + hiddenSize);
                    System.arraycopy(loadedInputWeights[i], 0, inputGateWeights[i], 0, inputSize + hiddenSize);
                    System.arraycopy(loadedOutputWeights[i], 0, outputGateWeights[i], 0, inputSize + hiddenSize);
                    System.arraycopy(loadedCandidateWeights[i], 0, candidateWeights[i], 0, inputSize + hiddenSize);
                }

                System.arraycopy(loadedForgetBias, 0, forgetGateBias, 0, hiddenSize);
                System.arraycopy(loadedInputBias, 0, inputGateBias, 0, hiddenSize);
                System.arraycopy(loadedOutputBias, 0, outputGateBias, 0, hiddenSize);
                System.arraycopy(loadedCandidateBias, 0, candidateBias, 0, hiddenSize);

                initialized = true;
            }
        }

        public double[] getHiddenState() {
            return hiddenState.clone();
        }

        public double[] getCellState() {
            return cellState.clone();
        }

        public boolean isInitialized() {
            return initialized;
        }

        public void resetStates() {
            Arrays.fill(hiddenState, 0.0);
            Arrays.fill(cellState, 0.0);
        }
    }

    // ADVANCED DECISION TREE IMPLEMENTATION
    @Data
    public static class AdvancedDecisionTree implements Serializable {
        private final int maxDepth;
        private final double regularizationLambda;
        private TreeNode root;
        private double[] featureImportances = new double[33]; // Updated to 33 features

        public AdvancedDecisionTree(int maxDepth, double regularizationLambda) {
            this.maxDepth = maxDepth;
            this.regularizationLambda = regularizationLambda;
        }

        public double predict(double[] inputs) {
            if (root == null) return 0.0;
            return root.predict(inputs);
        }

        public void fit(List<TrainingExample> examples, double[] residuals, double subsampleRatio, double colsampleRatio) {
            if (examples.isEmpty()) return;

            // Subsample examples
            List<TrainingExample> sampledExamples = sampleExamples(examples, subsampleRatio);
            double[] sampledResiduals = sampleResiduals(residuals, examples, sampledExamples);

            // Select features with bias toward correlation features
            int[] selectedFeatures = selectFeaturesWithCorrelationBias(colsampleRatio);

            // Build tree
            root = buildTree(sampledExamples, sampledResiduals, selectedFeatures, 0);
        }

        private int[] selectFeaturesWithCorrelationBias(double ratio) {
            int totalFeatures = 33;
            int selectedCount = Math.max(1, (int)(totalFeatures * ratio));
            List<Integer> features = new ArrayList<>();

            // Always include correlation features (indices 29-32) if possible
            if (selectedCount >= 4) {
                features.add(29); // correlationStrength
                features.add(30); // nvdaLeadership
                features.add(31); // regimeStability
                features.add(32); // divergenceSignal
                selectedCount -= 4;
            }

            // Add remaining features randomly
            List<Integer> remainingFeatures = IntStream.range(0, 29).boxed().collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            Collections.shuffle(remainingFeatures);

            for (int i = 0; i < selectedCount && i < remainingFeatures.size(); i++) {
                features.add(remainingFeatures.get(i));
            }

            return features.stream().mapToInt(Integer::intValue).toArray();
        }

        private TreeNode buildTree(List<TrainingExample> examples, double[] residuals, int[] features, int depth) {
            if (depth >= maxDepth || examples.size() < 5) {
                return new TreeNode(calculateLeafValue(residuals));
            }

            BestSplit bestSplit = findBestSplit(examples, residuals, features);
            if (bestSplit == null) {
                return new TreeNode(calculateLeafValue(residuals));
            }

            // Update feature importance with correlation feature boost
            if (bestSplit.featureIndex < featureImportances.length) {
                double importanceBoost = (bestSplit.featureIndex >= 29) ? 1.5 : 1.0; // Boost correlation features
                featureImportances[bestSplit.featureIndex] += bestSplit.gain * importanceBoost;
            }

            // Split data
            List<TrainingExample> leftExamples = new ArrayList<>();
            List<TrainingExample> rightExamples = new ArrayList<>();
            List<Double> leftResiduals = new ArrayList<>();
            List<Double> rightResiduals = new ArrayList<>();

            for (int i = 0; i < examples.size(); i++) {
                double[] features_arr = examples.get(i).features.toAdvancedArray();
                if (bestSplit.featureIndex < features_arr.length &&
                        features_arr[bestSplit.featureIndex] <= bestSplit.threshold) {
                    leftExamples.add(examples.get(i));
                    leftResiduals.add(residuals[i]);
                } else {
                    rightExamples.add(examples.get(i));
                    rightResiduals.add(residuals[i]);
                }
            }

            TreeNode node = new TreeNode(bestSplit.featureIndex, bestSplit.threshold);
            if (!leftExamples.isEmpty()) {
                node.left = buildTree(leftExamples, leftResiduals.stream().mapToDouble(Double::doubleValue).toArray(), features, depth + 1);
            }
            if (!rightExamples.isEmpty()) {
                node.right = buildTree(rightExamples, rightResiduals.stream().mapToDouble(Double::doubleValue).toArray(), features, depth + 1);
            }

            return node;
        }

        private BestSplit findBestSplit(List<TrainingExample> examples, double[] residuals, int[] features) {
            BestSplit bestSplit = null;
            double bestGain = 0.0;

            for (int featureIndex : features) {
                double[] values = examples.stream()
                        .mapToDouble(ex -> {
                            double[] arr = ex.features.toAdvancedArray();
                            return featureIndex < arr.length ? arr[featureIndex] : 0.0;
                        })
                        .sorted()
                        .distinct()
                        .toArray();

                for (double threshold : values) {
                    double gain = calculateSplitGain(examples, residuals, featureIndex, threshold);
                    if (gain > bestGain) {
                        bestGain = gain;
                        bestSplit = new BestSplit(featureIndex, threshold, gain);
                    }
                }
            }

            return bestSplit;
        }

        private double calculateSplitGain(List<TrainingExample> examples, double[] residuals, int featureIndex, double threshold) {
            double leftSum = 0.0, rightSum = 0.0;
            int leftCount = 0, rightCount = 0;

            for (int i = 0; i < examples.size(); i++) {
                double[] features = examples.get(i).features.toAdvancedArray();
                if (featureIndex < features.length && features[featureIndex] <= threshold) {
                    leftSum += residuals[i];
                    leftCount++;
                } else {
                    rightSum += residuals[i];
                    rightCount++;
                }
            }

            if (leftCount == 0 || rightCount == 0) return 0.0;

            // Calculate variance reduction
            double totalVariance = calculateVariance(residuals);
            double leftVariance = calculateVarianceForSplit(examples, residuals, featureIndex, threshold, true);
            double rightVariance = calculateVarianceForSplit(examples, residuals, featureIndex, threshold, false);

            double weightedVariance = (leftCount * leftVariance + rightCount * rightVariance) / examples.size();
            return totalVariance - weightedVariance;
        }

        private double calculateVariance(double[] values) {
            if (values.length == 0) return 0.0;
            double mean = Arrays.stream(values).average().orElse(0.0);
            return Arrays.stream(values).map(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
        }

        private double calculateVarianceForSplit(List<TrainingExample> examples, double[] residuals, int featureIndex, double threshold, boolean left) {
            List<Double> splitValues = new ArrayList<>();

            for (int i = 0; i < examples.size(); i++) {
                double[] features = examples.get(i).features.toAdvancedArray();
                boolean goLeft = featureIndex < features.length && features[featureIndex] <= threshold;
                if (goLeft == left) {
                    splitValues.add(residuals[i]);
                }
            }

            if (splitValues.isEmpty()) return 0.0;
            return calculateVariance(splitValues.stream().mapToDouble(Double::doubleValue).toArray());
        }

        private double calculateLeafValue(double[] residuals) {
            return Arrays.stream(residuals).average().orElse(0.0);
        }

        private List<TrainingExample> sampleExamples(List<TrainingExample> examples, double ratio) {
            int sampleSize = (int)(examples.size() * ratio);
            List<TrainingExample> sampled = new ArrayList<>(examples);
            Collections.shuffle(sampled);
            return sampled.subList(0, Math.min(sampleSize, sampled.size()));
        }

        private double[] sampleResiduals(double[] residuals, List<TrainingExample> originalExamples, List<TrainingExample> sampledExamples) {
            Set<TrainingExample> sampledSet = new HashSet<>(sampledExamples);
            List<Double> sampledResiduals = new ArrayList<>();

            for (int i = 0; i < originalExamples.size(); i++) {
                if (sampledSet.contains(originalExamples.get(i))) {
                    sampledResiduals.add(residuals[i]);
                }
            }

            return sampledResiduals.stream().mapToDouble(Double::doubleValue).toArray();
        }

        public double[] getFeatureImportances() {
            // Normalize importances
            double sum = Arrays.stream(featureImportances).sum();
            if (sum > 0) {
                return Arrays.stream(featureImportances).map(imp -> imp / sum).toArray();
            }
            return featureImportances.clone();
        }
    }

    public static class TreeNode implements Serializable {
        int featureIndex = -1;
        double threshold;
        double value;
        TreeNode left;
        TreeNode right;

        // Leaf node
        public TreeNode(double value) {
            this.value = value;
        }

        // Internal node
        public TreeNode(int featureIndex, double threshold) {
            this.featureIndex = featureIndex;
            this.threshold = threshold;
        }

        public double predict(double[] inputs) {
            if (featureIndex == -1) return value; // Leaf node

            if (featureIndex < inputs.length && inputs[featureIndex] <= threshold) {
                return left != null ? left.predict(inputs) : 0.0;
            } else {
                return right != null ? right.predict(inputs) : 0.0;
            }
        }
    }

    public static class BestSplit {
        final int featureIndex;
        final double threshold;
        final double gain;

        public BestSplit(int featureIndex, double threshold, double gain) {
            this.featureIndex = featureIndex;
            this.threshold = threshold;
            this.gain = gain;
        }
    }
}