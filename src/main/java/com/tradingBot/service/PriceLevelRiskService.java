package com.tradingBot.service;

import com.tradingBot.entity.*;
import com.tradingBot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PriceLevelRiskService {

    private final POCDataRepository pocDataRepository;
    private final SupportResistanceLevelsRepository supportResistanceLevelsRepository;
    private final VWAPDataRepository vwapDataRepository;
    private final MarketDataRepository marketDataRepository;
    private final TechnicalAnalysisService technicalAnalysisService;

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final double POC_PROXIMITY_THRESHOLD = 0.002; // 0.2%
    private static final double RESISTANCE_PROXIMITY_THRESHOLD = 0.001; // 0.1%
    private static final double BREAKOUT_VOLUME_THRESHOLD = 1.5; // 1.5x volume required

    public PriceLevelRisk assessPriceLevelRisk(String symbol, BigDecimal currentPrice, String analysisId) {
        try {
            LocalDateTime sessionStart = LocalDateTime.now(ET_ZONE).with(LocalTime.of(9, 30));

            // Check POC proximity
            POCProximity pocProximity = checkPOCProximity(symbol, currentPrice, sessionStart);

            // Check resistance/support proximity
            ResistanceProximity resistanceProximity = checkResistanceProximity(symbol, currentPrice);

            // Overall risk assessment
            boolean isHighRisk = pocProximity.isNearPOC() || resistanceProximity.isNearLevel();
            boolean hasVolumeConfirmation = checkVolumeBreakout(symbol);

            String recommendation = determineRecommendation(pocProximity, resistanceProximity, hasVolumeConfirmation);

            log.info("[PRICE-LEVEL][{}] Risk Assessment - POC: {}, Resistance: {}, Volume: {}, Recommendation: {}",
                    analysisId,
                    pocProximity.isNearPOC() ? String.format("%.2f%% away", pocProximity.getDistancePercent() * 100) : "CLEAR",
                    resistanceProximity.isNearLevel() ? String.format("%.2f%% away", resistanceProximity.getDistancePercent() * 100) : "CLEAR",
                    hasVolumeConfirmation ? "CONFIRMED" : "LACKING",
                    recommendation);

            return new PriceLevelRisk(isHighRisk, hasVolumeConfirmation, recommendation, pocProximity, resistanceProximity);

        } catch (Exception e) {
            log.error("[PRICE-LEVEL][{}] Error assessing price level risk: {}", analysisId, e.getMessage());
            return new PriceLevelRisk(false, true, "PROCEED", null, null); // Default to allow execution
        }
    }

    private POCProximity checkPOCProximity(String symbol, BigDecimal currentPrice, LocalDateTime sessionStart) {
        Optional<POCData> pocData = pocDataRepository.findLatestPOCForSession(symbol, sessionStart);

        if (!pocData.isPresent()) {
            return new POCProximity(false, 0.0, null);
        }

        BigDecimal pocPrice = pocData.get().getPocPrice();
        double distancePercent = Math.abs(currentPrice.subtract(pocPrice)
                .divide(pocPrice, 4, RoundingMode.HALF_UP).doubleValue());

        boolean isNear = distancePercent <= POC_PROXIMITY_THRESHOLD;

        return new POCProximity(isNear, distancePercent, pocPrice);
    }

    private ResistanceProximity checkResistanceProximity(String symbol, BigDecimal currentPrice) {
        BigDecimal priceRange = currentPrice.multiply(BigDecimal.valueOf(RESISTANCE_PROXIMITY_THRESHOLD));
        BigDecimal minPrice = currentPrice.subtract(priceRange);
        BigDecimal maxPrice = currentPrice.add(priceRange);

        List<SupportResistanceLevels> nearbyLevels = supportResistanceLevelsRepository
                .findActiveLevelsNearPrice(symbol, minPrice, maxPrice);

        if (nearbyLevels.isEmpty()) {
            return new ResistanceProximity(false, 0.0, null, null);
        }

        // Find closest level
        SupportResistanceLevels closestLevel = nearbyLevels.stream()
                .min((a, b) -> {
                    BigDecimal distA = currentPrice.subtract(a.getPrice()).abs();
                    BigDecimal distB = currentPrice.subtract(b.getPrice()).abs();
                    return distA.compareTo(distB);
                })
                .orElse(null);

        if (closestLevel == null) {
            return new ResistanceProximity(false, 0.0, null, null);
        }

        double distancePercent = Math.abs(currentPrice.subtract(closestLevel.getPrice())
                .divide(closestLevel.getPrice(), 4, RoundingMode.HALF_UP).doubleValue());

        return new ResistanceProximity(true, distancePercent, closestLevel.getPrice(), closestLevel.getLevelType());
    }

    private boolean checkVolumeBreakout(String symbol) {
        try {
            TechnicalAnalysis ta = technicalAnalysisService.analyze(symbol);
            return ta != null && ta.getVolumeRatio() >= BREAKOUT_VOLUME_THRESHOLD;
        } catch (Exception e) {
            log.warn("Error checking volume breakout: {}", e.getMessage());
            return false;
        }
    }

    private String determineRecommendation(POCProximity poc, ResistanceProximity resistance, boolean hasVolume) {
        boolean isNearCriticalLevel = (poc != null && poc.isNearPOC()) ||
                (resistance != null && resistance.isNearLevel());

        if (!isNearCriticalLevel) {
            return "PROCEED"; // Clear of all levels
        }

        if (hasVolume) {
            return "PROCEED_WITH_VOLUME"; // Volume confirms breakout
        }

        return "WAIT_OR_CANCEL"; // Near level without volume confirmation
    }

    // Data classes
    public static class PriceLevelRisk {
        private final boolean isHighRisk;
        private final boolean hasVolumeConfirmation;
        private final String recommendation;
        private final POCProximity pocProximity;
        private final ResistanceProximity resistanceProximity;

        public PriceLevelRisk(boolean isHighRisk, boolean hasVolumeConfirmation, String recommendation,
                              POCProximity pocProximity, ResistanceProximity resistanceProximity) {
            this.isHighRisk = isHighRisk;
            this.hasVolumeConfirmation = hasVolumeConfirmation;
            this.recommendation = recommendation;
            this.pocProximity = pocProximity;
            this.resistanceProximity = resistanceProximity;
        }

        public boolean shouldProceed() {
            return "PROCEED".equals(recommendation) || "PROCEED_WITH_VOLUME".equals(recommendation);
        }

        // Getters
        public boolean isHighRisk() { return isHighRisk; }
        public boolean hasVolumeConfirmation() { return hasVolumeConfirmation; }
        public String getRecommendation() { return recommendation; }
        public POCProximity getPocProximity() { return pocProximity; }
        public ResistanceProximity getResistanceProximity() { return resistanceProximity; }
    }

    public static class POCProximity {
        private final boolean nearPOC;
        private final double distancePercent;
        private final BigDecimal pocPrice;

        public POCProximity(boolean nearPOC, double distancePercent, BigDecimal pocPrice) {
            this.nearPOC = nearPOC;
            this.distancePercent = distancePercent;
            this.pocPrice = pocPrice;
        }

        public boolean isNearPOC() { return nearPOC; }
        public double getDistancePercent() { return distancePercent; }
        public BigDecimal getPocPrice() { return pocPrice; }
    }

    public static class ResistanceProximity {
        private final boolean nearLevel;
        private final double distancePercent;
        private final BigDecimal levelPrice;
        private final String levelType;

        public ResistanceProximity(boolean nearLevel, double distancePercent, BigDecimal levelPrice, String levelType) {
            this.nearLevel = nearLevel;
            this.distancePercent = distancePercent;
            this.levelPrice = levelPrice;
            this.levelType = levelType;
        }

        public boolean isNearLevel() { return nearLevel; }
        public double getDistancePercent() { return distancePercent; }
        public BigDecimal getLevelPrice() { return levelPrice; }
        public String getLevelType() { return levelType; }
    }
}