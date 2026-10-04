package me.martingeltl.bitcoin.preferences;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

public record PriceAlert(java.util.UUID id, AlertDirection direction, BigDecimal threshold, String currency) {
    public PriceAlert {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(direction, "direction");
        PlayerPreferencesService.requirePositive(threshold, "threshold");
        currency = Objects.requireNonNull(currency, "currency").toUpperCase(Locale.ROOT);
        if (!currency.equals("EUR") && !currency.equals("USD")) {
            throw new IllegalArgumentException("Alert currency must be EUR or USD");
        }
    }
}
