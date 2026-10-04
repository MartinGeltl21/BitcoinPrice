package org.bitcoinprice.preferences;

import org.bitcoinprice.model.CurrencyCatalog;
import org.bitcoinprice.presentation.Language;
import org.bitcoinprice.presentation.MessageException;
import java.util.Locale;
import java.util.Objects;

public record Preferences(boolean notifications, String currency, DisplayMode display, String locale,
                          ActionbarMode actionbarMode, int actionbarIntervalMinutes, String language, ActionbarContent actionbarContent) {
    public static final Preferences DEFAULTS = new Preferences(true, "DEFAULT", DisplayMode.CHAT, "DEFAULT");

    public Preferences(boolean notifications, String currency, DisplayMode display, String locale) {
        this(notifications, currency, display, locale, ActionbarMode.CONTINUOUS, 0);
    }

    public Preferences(boolean notifications, String currency, DisplayMode display, String locale,
                       ActionbarMode actionbarMode, int actionbarIntervalMinutes) {
        this(notifications, currency, display, locale, actionbarMode, actionbarIntervalMinutes, "DEFAULT");
    }

    public Preferences(boolean notifications, String currency, DisplayMode display, String locale,
                       ActionbarMode actionbarMode, int actionbarIntervalMinutes, String language) {
        this(notifications, currency, display, locale, actionbarMode, actionbarIntervalMinutes, language, ActionbarContent.FULL);
    }

    public Preferences {
        currency = Objects.requireNonNull(currency, "currency").toUpperCase(Locale.ROOT);
        if (!CurrencyCatalog.isSelection(currency, true)) {
            throw new MessageException("error.currency", String.join(", ", CurrencyCatalog.selectionCodes(true)));
        }
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(actionbarMode, "actionbarMode");
        Objects.requireNonNull(actionbarContent, "actionbarContent");
        if (actionbarIntervalMinutes != 0 && actionbarIntervalMinutes != 1 && actionbarIntervalMinutes != 5
                && actionbarIntervalMinutes != 10 && actionbarIntervalMinutes != 30 && actionbarIntervalMinutes != 60) {
            throw new MessageException("error.actionbar.interval");
        }
        if (Objects.requireNonNull(locale, "locale").equalsIgnoreCase("DEFAULT")) {
            locale = "DEFAULT";
        } else {
            try {
                Locale parsed = new Locale.Builder().setLanguageTag(locale).build();
                if (parsed.getLanguage().isEmpty()) throw new MessageException("error.locale");
                locale = parsed.toLanguageTag();
            } catch (java.util.IllformedLocaleException ex) {
                throw new MessageException("error.locale");
            }
        }
        language = Language.selection(language);
    }
}
