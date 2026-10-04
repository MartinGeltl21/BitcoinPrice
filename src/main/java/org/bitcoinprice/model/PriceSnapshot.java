package org.bitcoinprice.model;

import org.bitcoinprice.presentation.MessageException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;

/** A validated immutable provider sample. A missing provider timestamp stays unknown. */
public record PriceSnapshot(Map<String, BigDecimal> prices, Map<String, BigDecimal> changes24h,
                            Instant providerUpdatedAt, Instant fetchedAt) {
    public PriceSnapshot {
        prices = normalized(prices, true);
        changes24h = normalized(changes24h, false);
        if (prices.isEmpty()) throw new IllegalArgumentException("At least one currency quote is required.");
        if (!prices.keySet().containsAll(changes24h.keySet()))
            throw new IllegalArgumentException("A change value requires a quote for its currency.");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }

    /** Compatibility for existing EUR/USD samples and version-one history. */
    public PriceSnapshot(BigDecimal eur, BigDecimal usd, BigDecimal eurChange24h, BigDecimal usdChange24h,
                         Instant providerUpdatedAt, Instant fetchedAt) {
        this(Map.of("EUR", eur, "USD", usd), changes(eurChange24h, usdChange24h), providerUpdatedAt, fetchedAt);
    }
    private static Map<String, BigDecimal> changes(BigDecimal eur, BigDecimal usd) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (eur != null) values.put("EUR", eur);
        if (usd != null) values.put("USD", usd);
        return values;
    }
    private static Map<String, BigDecimal> normalized(Map<String, BigDecimal> input, boolean price) {
        Objects.requireNonNull(input);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        input.forEach((code, value) -> {
            if (!CurrencyCatalog.isSupported(code)) throw new IllegalArgumentException("Unsupported currency.");
            String key = code.toUpperCase(Locale.ROOT);
            Objects.requireNonNull(value, "quote");
            if (price) requirePositive(value); else requireBounded(value);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate currency.");
        });
        return Map.copyOf(result);
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
        BigDecimal value = prices.get(currency.toUpperCase(Locale.ROOT));
        if (value == null) throw new MessageException("error.price.currency", currency);
        return value;
    }

    public BigDecimal change24h(String currency) {
        if (!supports(currency)) throw new MessageException("error.price.currency", currency);
        return changes24h.get(currency.toUpperCase(Locale.ROOT));
    }
    public boolean supports(String currency) { return currency != null && prices.containsKey(currency.toUpperCase(Locale.ROOT)); }
    public BigDecimal eur() { return price("EUR"); }
    public BigDecimal usd() { return price("USD"); }
    public BigDecimal eurChange24h() { return change24h("EUR"); }
    public BigDecimal usdChange24h() { return change24h("USD"); }
}
