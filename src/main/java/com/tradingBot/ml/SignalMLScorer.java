package com.tradingBot.ml;

import com.tradingBot.entity.Signal;
import com.tradingBot.analytics.MarketMicrostructureAnalyzer;
import com.tradingBot.analytics.MarketRegimeDetector;
import com.tradingBot.entity.MarketMicrostructureData;
import com.tradingBot.model.MarketRegime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class SignalMLScorer {

    private final MarketMicrostructureAnalyzer microAnalyzer;
    private final FeatureExtractor featureExtractor;
    private final MarketRegimeDetector regimeDetector;

    // Feature weights learned from "backtesting"
    private static final Map<String, Double> FEATURE_WEIGHTS = new HashMap<>() {{
        put("microstructure_quality", 0.25);
        put("regime_alignment", 0.20);
        put("time_of_day", 0.15);
        put("volatility_regime", 0.20);
        put("momentum_confirmation", 0.20);
    }};

    public double scoreSignal(Signal signal) {
        try {
            // Extract all features
            FeatureExtractor.MLFeatures features = featureExtractor.extractFeatures(signal);

            // Calculate component scores
            double microstructureScore = scoreMicrostructure(signal, features);
            double regimeScore = scoreRegimeAlignment(signal, features);
            double timeScore = scoreTimeOfDay(features);
            double volatilityScore = scoreVolatilityRegime(signal, features);
            double momentumScore = scoreMomentum(features);

            // Weighted combination
            double totalScore =
                    microstructureScore * FEATURE_WEIGHTS.get("microstructure_quality") +
                            regimeScore * FEATURE_WEIGHTS.get("regime_alignment") +
                            timeScore * FEATURE_WEIGHTS.get("time_of_day") +
                            volatilityScore * FEATURE_WEIGHTS.get("volatility_regime") +
                            momentumScore * FEATURE_WEIGHTS.get("momentum_confirmation");

            // Apply strategy-specific adjustments
            totalScore = applyStrategyAdjustments(signal, totalScore, features);

            // Apply confidence penalty for extreme scores
            if (totalScore > 0.9) {
                totalScore = 0.9 + (totalScore - 0.9) * 0.5; // Diminishing returns
            }

            log.debug("ML Score for {}: {:.3f} (Micro:{:.2f}, Regime:{:.2f}, Time:{:.2f}, Vol:{:.2f}, Mom:{:.2f})",
                    signal.getOptionSymbol(), totalScore, microstructureScore, regimeScore,
                    timeScore, volatilityScore, momentumScore);

            return Math.max(0.0, Math.min(1.0, totalScore));

        } catch (Exception e) {
            log.error("Error scoring signal {}: {}", signal.getId(), e.getMessage());
            return 0.5; // Neutral score on error
        }
    }

    private double scoreMicrostructure(Signal signal, FeatureExtractor.MLFeatures features) {
        double score = 0.5;

        // Spread quality
        if (features.getSpreadBps() < 5) {
            score += 0.3;
        } else if (features.getSpreadBps() < 10) {
            score += 0.2;
        } else if (features.getSpreadBps() < 20) {
            score += 0.1;
        } else {
            score -= 0.2;
        }

        // Order imbalance - slight directional bias is good
        double imbalanceAbs = Math.abs(features.getOrderImbalance());
        if (imbalanceAbs > 0.2 && imbalanceAbs < 0.6) {
            score += 0.2;
        } else if (imbalanceAbs > 0.8) {
            score -= 0.1; // Too extreme might mean manipulation
        }

        // Toxicity check
        if (features.getMicrostructureToxicity() < 0.3) {
            score += 0.2;
        } else if (features.getMicrostructureToxicity() > 0.7) {
            score -= 0.3;
        }

        // Liquidity
        if (features.getLiquidityScore() > 0.7) {
            score += 0.1;
        } else if (features.getLiquidityScore() < 0.3) {
            score -= 0.2;
        }

        return Math.max(0, Math.min(1, score));
    }

    private double scoreRegimeAlignment(Signal signal, FeatureExtractor.MLFeatures features) {
        MarketRegime regime = MarketRegime.valueOf(features.getMarketRegime());
        String strategy = signal.getStrategy();

        // Strategy-regime alignment matrix
        double score = 0.5;

        switch (regime) {
            case TRENDING_UP:
                if ("UNUSUAL_FLOW_CONFIRMED".equals(strategy) && "CALL".equals(getOptionType(signal))) {
                    score = 0.9;
                } else if ("MOMENTUM".equals(strategy)) {
                    score = 0.85;
                } else if ("MEAN_REVERSION".equals(strategy)) {
                    score = 0.3;
                }
                break;

            case TRENDING_DOWN:
                if ("UNUSUAL_FLOW_CONFIRMED".equals(strategy) && "PUT".equals(getOptionType(signal))) {
                    score = 0.9;
                } else if ("MEAN_REVERSION".equals(strategy)) {
                    score = 0.3;
                }
                break;

            case CHOPPY:
                if ("MEAN_REVERSION".equals(strategy)) {
                    score = 0.8;
                } else if ("MOMENTUM".equals(strategy)) {
                    score = 0.3;
                }
                break;

            case HIGH_VOLATILITY:
                if ("UNUSUAL_FLOW_CONFIRMED".equals(strategy)) {
                    score = 0.7; // Flow might be hedging
                } else {
                    score = 0.4; // Generally harder to trade
                }
                break;

            case OPENING_RANGE:
            case CLOSING_RANGE:
                // Special handling for these periods
                score = 0.6; // Neutral, let other factors decide
                break;
        }

        return score;
    }

    private double scoreTimeOfDay(FeatureExtractor.MLFeatures features) {
        int minutesIntoSession = features.getMinutesIntoSession();

        // Best times to trade (empirically determined)
        if (minutesIntoSession >= 45 && minutesIntoSession <= 120) {
            return 0.9; // 10:15 AM - 11:30 AM best period
        } else if (minutesIntoSession >= 180 && minutesIntoSession <= 300) {
            return 0.8; // 12:30 PM - 2:30 PM good period
        } else if (minutesIntoSession < 30) {
            return 0.3; // First 30 min too volatile
        } else if (minutesIntoSession > 360) {
            return 0.5; // Last 30 min depends on other factors
        } else {
            return 0.7; // Other times neutral-good
        }
    }

    private double scoreVolatilityRegime(Signal signal, FeatureExtractor.MLFeatures features) {
        double vol = features.getVolatility();
        double score = 0.5;

        // Optimal volatility range for options trading
        if (vol >= 0.15 && vol <= 0.35) {
            score = 0.9; // Sweet spot
        } else if (vol < 0.10) {
            score = 0.3; // Too low - options won't move
        } else if (vol > 0.50) {
            score = 0.4; // Too high - expensive and risky
        } else {
            score = 0.7; // Acceptable
        }

        // Adjust for IV rank
        if (features.getIvRank() > 0.8 && "BUY".equals(signal.getSignalType())) {
            score *= 0.8; // Buying expensive options
        } else if (features.getIvRank() < 0.2 && "BUY".equals(signal.getSignalType())) {
            score *= 1.1; // Buying cheap options
        }

        return Math.min(1.0, score);
    }

    private double scoreMomentum(FeatureExtractor.MLFeatures features) {
        double score = 0.5;

        // Check if momentum aligns
        boolean shortTermPositive = features.getMomentum5min() > 0;
        boolean mediumTermPositive = features.getMomentum30min() > 0;

        if (shortTermPositive && mediumTermPositive) {
            score += 0.3; // Both align
        } else if (shortTermPositive || mediumTermPositive) {
            score += 0.1; // One aligns
        } else {
            score -= 0.2; // Neither align
        }

        // Check momentum strength
        double momentumStrength = Math.abs(features.getMomentum5min());
        if (momentumStrength > 0.005 && momentumStrength < 0.02) {
            score += 0.2; // Good momentum, not extreme
        } else if (momentumStrength > 0.03) {
            score -= 0.1; // Too extreme, might reverse
        }

        return Math.max(0, Math.min(1, score));
    }

    private double applyStrategyAdjustments(Signal signal, double baseScore,
                                            FeatureExtractor.MLFeatures features) {
        String strategy = signal.getStrategy();

        // Flow confirmations get boost if everything else looks good
        if ("UNUSUAL_FLOW_CONFIRMED".equals(strategy) && baseScore > 0.7) {
            baseScore *= 1.1;
        }

        // Penalize if price is at extremes
        if (features.getPricePosition() > 0.9 || features.getPricePosition() < 0.1) {
            baseScore *= 0.9;
        }

        // Time decay penalty for near-expiration
        if (features.getTimeDecayFactor() > 0.8) {
            baseScore *= 0.85;
        }

        return baseScore;
    }

    private String getOptionType(Signal signal) {
        return signal.getOptionSymbol().contains("C") ? "CALL" : "PUT";
    }
}