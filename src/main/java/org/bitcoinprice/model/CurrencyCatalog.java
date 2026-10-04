package org.bitcoinprice.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Supported fiat quotes, shared by validation, provider requests and command completion. */
public final class CurrencyCatalog {
    private static final List<String> CODES = List.of("EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY", "CNY", "INR");
    private CurrencyCatalog() { }
    public static List<String> codes() { return CODES; }
    public static boolean isSupported(String code) {
        return code != null && CODES.contains(code.toUpperCase(Locale.ROOT));
    }
    public static boolean isSelection(String code, boolean allowDefault) {
        return isSupported(code) || "BOTH".equalsIgnoreCase(code) || allowDefault && "DEFAULT".equalsIgnoreCase(code);
    }
    public static List<String> selectionCodes(boolean allowDefault) {
        List<String> selections = new ArrayList<>(CODES);
        selections.add("BOTH");
        if (allowDefault) selections.add("DEFAULT");
        return List.copyOf(selections);
    }
    public static String providerCodes() { return String.join(",", CODES).toLowerCase(Locale.ROOT); }
}
