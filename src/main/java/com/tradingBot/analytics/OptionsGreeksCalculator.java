package com.tradingBot.analytics;

import com.tradingBot.model.Option;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
@Slf4j
public class OptionsGreeksCalculator {

    private static final double DAYS_IN_YEAR = 365.25;

    public GreeksResult calculateGreeks(Option option, BigDecimal underlyingPrice) {
        GreeksResult result = new GreeksResult();

        double S = underlyingPrice.doubleValue();
        double K = option.getStrikePrice().doubleValue();
        double T = calculateTimeToExpiry(option.getExpirationDate());
        double r = 0.05; // You should get this from market data
        double sigma = option.getImpliedVolatility() != null ?
                option.getImpliedVolatility() : estimateImpliedVolatility(option, S);

        // Calculate all Greeks
        result.delta = calculateDelta(S, K, T, r, sigma, option.getType());
        result.gamma = calculateGamma(S, K, T, r, sigma);
        result.theta = calculateTheta(S, K, T, r, sigma, option.getType());
        result.vega = calculateVega(S, K, T, r, sigma);
        result.rho = calculateRho(S, K, T, r, sigma, option.getType());

        return result;
    }

    private double calculateDelta(double S, double K, double T, double r, double sigma, String type) {
        if (T <= 0) return "CALL".equals(type) ? (S >= K ? 1.0 : 0.0) : (S <= K ? -1.0 : 0.0);

        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));

        if ("CALL".equals(type)) {
            return normalCDF(d1);
        } else {
            return normalCDF(d1) - 1;
        }
    }

    private double calculateGamma(double S, double K, double T, double r, double sigma) {
        if (T <= 0) return 0.0;

        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
        return normalPDF(d1) / (S * sigma * Math.sqrt(T));
    }

    private double calculateTheta(double S, double K, double T, double r, double sigma, String type) {
        if (T <= 0) return 0.0;

        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
        double d2 = d1 - sigma * Math.sqrt(T);

        double term1 = -S * normalPDF(d1) * sigma / (2 * Math.sqrt(T));

        if ("CALL".equals(type)) {
            double term2 = -r * K * Math.exp(-r * T) * normalCDF(d2);
            return (term1 + term2) / DAYS_IN_YEAR;
        } else {
            double term2 = r * K * Math.exp(-r * T) * normalCDF(-d2);
            return (term1 + term2) / DAYS_IN_YEAR;
        }
    }

    private double calculateVega(double S, double K, double T, double r, double sigma) {
        if (T <= 0) return 0.0;

        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
        return S * normalPDF(d1) * Math.sqrt(T) / 100; // Divide by 100 for 1% vol change
    }

    private double calculateRho(double S, double K, double T, double r, double sigma, String type) {
        if (T <= 0) return 0.0;

        double d2 = (Math.log(S / K) + (r - 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));

        if ("CALL".equals(type)) {
            return K * T * Math.exp(-r * T) * normalCDF(d2) / 100;
        } else {
            return -K * T * Math.exp(-r * T) * normalCDF(-d2) / 100;
        }
    }

    private double normalCDF(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    private double normalPDF(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    private double erf(double x) {
        // Approximation of error function
        double a1 =  0.254829592;
        double a2 = -0.284496736;
        double a3 =  1.421413741;
        double a4 = -1.453152027;
        double a5 =  1.061405429;
        double p  =  0.3275911;

        int sign = x >= 0 ? 1 : -1;
        x = Math.abs(x);

        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);

        return sign * y;
    }

    private double calculateTimeToExpiry(String expirationDate) {
        LocalDate expiry = LocalDate.parse(expirationDate);
        LocalDate today = LocalDate.now();
        long daysToExpiry = ChronoUnit.DAYS.between(today, expiry);
        return Math.max(0, daysToExpiry / DAYS_IN_YEAR);
    }

    private double estimateImpliedVolatility(Option option, double S) {
        // Newton-Raphson method for IV estimation
        double marketPrice = option.getMidPrice().doubleValue();
        double K = option.getStrikePrice().doubleValue();
        double T = calculateTimeToExpiry(option.getExpirationDate());
        double r = 0.05;

        double sigma = 0.25; // Initial guess
        double tolerance = 0.0001;
        int maxIterations = 100;

        for (int i = 0; i < maxIterations; i++) {
            double price = blackScholesPrice(S, K, T, r, sigma, option.getType());
            double vega = calculateVega(S, K, T, r, sigma) * 100;

            if (Math.abs(vega) < 0.0001) break;

            double diff = price - marketPrice;
            if (Math.abs(diff) < tolerance) break;

            sigma = sigma - diff / vega;
            sigma = Math.max(0.01, Math.min(sigma, 3.0)); // Keep IV between 1% and 300%
        }

        return sigma;
    }

    private double blackScholesPrice(double S, double K, double T, double r, double sigma, String type) {
        if (T <= 0) return Math.max(0, "CALL".equals(type) ? S - K : K - S);

        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
        double d2 = d1 - sigma * Math.sqrt(T);

        if ("CALL".equals(type)) {
            return S * normalCDF(d1) - K * Math.exp(-r * T) * normalCDF(d2);
        } else {
            return K * Math.exp(-r * T) * normalCDF(-d2) - S * normalCDF(-d1);
        }
    }

    public static class GreeksResult {
        public double delta;
        public double gamma;
        public double theta;
        public double vega;
        public double rho;
    }
}