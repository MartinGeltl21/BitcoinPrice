package org.bitcoinprice.presentation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit text language, independent of the locale used to format numbers. */
public enum Language {
    GERMAN("de"), ENGLISH("en");

    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)}");
    private final String code;
    private final Properties messages;

    Language(String code) {
        this.code = code;
        messages = new Properties();
        String resource = "/languages/" + code + ".properties";
        try (var input = Objects.requireNonNull(Language.class.getResourceAsStream(resource), resource);
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            messages.load(reader);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load " + resource, error);
        }
    }

    public String code() { return code; }
    public Set<String> keys() { return messages.stringPropertyNames(); }

    public String text(String key, Object... arguments) {
        String template = Objects.requireNonNull(messages.getProperty(key), "Missing translation: " + key);
        return ARGUMENT.matcher(template).replaceAll(match -> {
            int index = Integer.parseInt(match.group(1));
            if (index >= arguments.length) throw new IllegalArgumentException("Missing argument for " + key);
            return java.util.regex.Matcher.quoteReplacement(String.valueOf(arguments[index]));
        });
    }

    /** Accept language codes and regional tags; unsupported languages must not silently fall back. */
    public static Language parse(String raw) {
        try {
            String code = new Locale.Builder().setLanguageTag(raw).build().getLanguage();
            return switch (code) {
                case "de" -> GERMAN;
                case "en" -> ENGLISH;
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException | java.util.IllformedLocaleException invalid) {
            throw new MessageException("error.language");
        }
    }

    public static String selection(String raw) {
        if (Objects.requireNonNull(raw).equalsIgnoreCase("DEFAULT")) return "DEFAULT";
        return raw.equalsIgnoreCase("AUTO") ? "AUTO" : parse(raw).code();
    }

    /** Explicit choices win; automatic detection supports de/en and uses the server fallback otherwise. */
    public static Language resolve(String selected, Locale clientLocale, Language fallback, boolean automaticDefault) {
        if (!selected.equals("AUTO") && !selected.equals("DEFAULT")) return parse(selected);
        if (selected.equals("AUTO") || automaticDefault) {
            if (clientLocale != null) return switch (clientLocale.getLanguage()) {
                case "de" -> GERMAN;
                case "en" -> ENGLISH;
                default -> fallback;
            };
        }
        return fallback;
    }

    /** A legacy custom template remains verbatim; shipped defaults follow the selected language. */
    public String template(String key, String custom, String localizedCustom) {
        if (localizedCustom != null) return localizedCustom;
        if (custom != null && !custom.equals(GERMAN.text("template." + key))
                && !custom.equals(ENGLISH.text("template." + key))) return custom;
        return text("template." + key);
    }
}
