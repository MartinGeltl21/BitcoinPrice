package me.martingeltl.bitcoin.preferences;

import java.util.Locale;
import java.util.Objects;

public record Preferences(boolean notifications, String currency, DisplayMode display, String locale) {
    public static final Preferences DEFAULTS = new Preferences(true, "DEFAULT", DisplayMode.CHAT, "DEFAULT");

    public Preferences {
        currency = Objects.requireNonNull(currency, "currency").toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("EUR", "USD", "BOTH", "DEFAULT").contains(currency)) {
            throw new IllegalArgumentException("Currency must be EUR, USD, BOTH or DEFAULT");
        }
        Objects.requireNonNull(display, "display");
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
