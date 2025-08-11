package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OptionGreeks {
    // Keep BigDecimal for delta and gamma for precision in calculations
    private BigDecimal delta;
    private BigDecimal gamma;

    // Use Double for the rest as they're typically used in analysis, not precision calculations
    private Double theta;
    private Double vega;
    private Double rho;

    @JsonProperty("mid_iv")
    private Double midIv;

    @JsonProperty("smv_vol")
    private Double smvVol;

    @JsonProperty("updated_at")
    private String updatedAt;

    // Convenience methods for type conversion
    public Double getDeltaAsDouble() {
        return delta != null ? delta.doubleValue() : null;
    }

    public Double getGammaAsDouble() {
        return gamma != null ? gamma.doubleValue() : null;
    }

    // Null-safe getters
    public BigDecimal getDelta() {
        return delta;
    }

    public BigDecimal getGamma() {
        return gamma;
    }

    public Double getTheta() {
        return theta;
    }

    public Double getVega() {
        return vega;
    }

    public Double getRho() {
        return rho;
    }

    public Double getMidIv() {
        return midIv;
    }

    public Double getSmvVol() {
        return smvVol;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    // Validation method
    public boolean isValid() {
        return delta != null || gamma != null || theta != null || vega != null;
    }

    // Check if all primary Greeks are available
    public boolean hasAllPrimaryGreeks() {
        return delta != null && gamma != null && theta != null && vega != null;
    }

    // Check if delta is in reasonable range for options
    public boolean isDeltaReasonable() {
        if (delta == null) return false;
        double deltaValue = Math.abs(delta.doubleValue());
        return deltaValue >= 0.0 && deltaValue <= 1.0;
    }

    // Check if gamma is reasonable (typically 0-0.5 for most options)
    public boolean isGammaReasonable() {
        if (gamma == null) return false;
        double gammaValue = Math.abs(gamma.doubleValue());
        return gammaValue >= 0.0 && gammaValue <= 0.5;
    }
}