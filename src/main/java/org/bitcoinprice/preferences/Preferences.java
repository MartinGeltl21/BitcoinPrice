package org.bitcoinprice.preferences;

import org.bitcoinprice.model.CurrencyCatalog;
import java.util.Locale;
import java.util.Objects;

public record Preferences(boolean notifications, String currency, DisplayMode display, String locale,
                          ActionbarMode actionbarMode, int actionbarIntervalMinutes) {
    public static final Preferences DEFAULTS = new Preferences(true, "DEFAULT", DisplayMode.CHAT, "DEFAULT");

    public Preferences(boolean notifications, String currency, DisplayMode display, String locale) {
        this(notifications, currency, display, locale, ActionbarMode.CONTINUOUS, 0);
    }

    public Preferences {
        currency = Objects.requireNonNull(currency, "currency").toUpperCase(Locale.ROOT);
        if (!CurrencyCatalog.isSelection(currency, true)) {
            throw new IllegalArgumentException("Currency must be " + String.join(", ", CurrencyCatalog.selectionCodes(true)));
        }
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(actionbarMode, "actionbarMode");
        if (actionbarIntervalMinutes != 0 && actionbarIntervalMinutes != 1 && actionbarIntervalMinutes != 5
                && actionbarIntervalMinutes != 10 && actionbarIntervalMinutes != 30 && actionbarIntervalMinutes != 60) {
            throw new IllegalArgumentException("Actionbar interval must be DEFAULT, 1, 5, 10, 30 or 60 minutes");
        }
        if (Objects.requireNonNull(locale, "locale").equalsIgnoreCase("DEFAULT")) {
            locale = "DEFAULT";
        } else {
            try {
                Locale parsed = new Locale.Builder().setLanguageTag(locale).build();
                if (parsed.getLanguage().isEmpty()) throw new IllegalArgumentException("Locale requires a language");
                locale = parsed.toLanguageTag();
            } catch (java.util.IllformedLocaleException ex) {
                throw new IllegalArgumentException("Locale must be a BCP47 language tag, e.g. de-DE", ex);
            }
        }
    }
}
