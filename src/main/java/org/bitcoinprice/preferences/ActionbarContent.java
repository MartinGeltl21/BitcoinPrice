package org.bitcoinprice.preferences;

import org.bitcoinprice.presentation.MessageException;
import java.util.Locale;

/** Independent of the continuous/interval cadence and the selected number format. */
public enum ActionbarContent {
    PRICE("actionbar-price"), CHANGE("actionbar-change"), FULL("actionbar");

    private final String template;
    ActionbarContent(String template) { this.template = template; }
    public String template() { return template; }

    public static ActionbarContent parse(String raw) {
        try { return valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new MessageException("error.actionbar.content"); }
    }
}
