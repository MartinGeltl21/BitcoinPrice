package org.bitcoinprice.preferences;

import org.bitcoinprice.model.CurrencyCatalog;
import org.bitcoinprice.presentation.MessageException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

public record PriceAlert(java.util.UUID id, AlertDirection direction, BigDecimal threshold, String currency) {
    public PriceAlert {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(direction, "direction");
        PlayerPreferencesService.requirePositive(threshold, "threshold");
        currency = Objects.requireNonNull(currency, "currency").toUpperCase(Locale.ROOT);
        if (!CurrencyCatalog.isSupported(currency)) {
            throw new MessageException("error.currency", String.join(", ", CurrencyCatalog.codes()));
        }
    }
}
