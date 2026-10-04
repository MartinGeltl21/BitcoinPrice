package me.martingeltl.bitcoin.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** A validated immutable provider sample. A missing provider timestamp stays unknown. */
public record PriceSnapshot(BigDecimal eur, BigDecimal usd, BigDecimal eurChange24h,
                            BigDecimal usdChange24h, Instant providerUpdatedAt, Instant fetchedAt) {
    public PriceSnapshot {
        requirePositive(eur);
        requirePositive(usd);
        requireBounded(eurChange24h);
        requireBounded(usdChange24h);
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }

    private static void requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("1e15")) > 0) {
            throw new IllegalArgumentException("Bitcoin prices must be positive finite numbers.");
        }
        requireBounded(value);
    }

    private static void requireBounded(BigDecimal value) {
        if (value != null && (value.precision() > 40 || Math.abs((long) value.scale()) > 18)) {
            throw new IllegalArgumentException("Bitcoin numeric value exceeds precision bounds.");
        }
    }

    public BigDecimal price(String currency) {
        return switch (currency.toUpperCase(Locale.ROOT)) {
            case "EUR" -> eur;
            case "USD" -> usd;
            default -> throw new IllegalArgumentException("Expected EUR or USD.");
        };
    }

    public BigDecimal change24h(String currency) {
        return switch (currency.toUpperCase(Locale.ROOT)) {
            case "EUR" -> eurChange24h;
            case "USD" -> usdChange24h;
            default -> throw new IllegalArgumentException("Expected EUR or USD.");
        };
    }
}
