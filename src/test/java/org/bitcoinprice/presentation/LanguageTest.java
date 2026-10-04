package org.bitcoinprice.presentation;

import org.bitcoinprice.commands.BTCCommand;
import org.bitcoinprice.config.ApiSettings;
import org.bitcoinprice.model.PriceQuote;
import org.bitcoinprice.model.PriceSnapshot;
import org.bitcoinprice.preferences.AlertDirection;
import org.bitcoinprice.preferences.PriceAlert;
import org.bitcoinprice.preferences.ActionbarContent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LanguageTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final ApiSettings SETTINGS = new ApiSettings("https://example.com/price", 1000, 60, 600, 300, 60, "");

    private static PriceSnapshot snapshot(Instant provider) {
        return new PriceSnapshot(Map.of("EUR", new BigDecimal("80000.5"), "USD", new BigDecimal("90000")),
                Map.of("EUR", new BigDecimal("2.5")), provider, NOW);
    }

    private static String render(String template, Language language, Locale locale, PriceQuote quote, PriceAlert alert) {
        return MessageFormatter.render(language.text("template." + template), quote, "EUR", locale, language, "&6", SETTINGS, NOW, alert);
    }

    @Test void languageChangesActionbarWordsIndependentlyOfNumberFormatting() {
        PriceQuote quote = new PriceQuote(snapshot(NOW), false);
        String english = render("actionbar", Language.ENGLISH, Locale.GERMANY, quote, null);
        assertTrue(english.contains("current"));
        assertTrue(english.contains("80.000,50"));
        assertTrue(english.contains("+2,50%"));
        assertFalse(english.contains("aktuell"));
        String german = render("actionbar", Language.GERMAN, Locale.US, quote, null);
        assertTrue(german.contains("aktuell"));
        assertTrue(german.contains("80,000.50"));
    }

    @Test void staleUnknownAndMissingDataStayExplicitInEnglish() {
        assertTrue(render("actionbar", Language.ENGLISH, Locale.US, new PriceQuote(snapshot(NOW), true), null).contains("stale"));
        assertTrue(render("actionbar", Language.ENGLISH, Locale.US, new PriceQuote(snapshot(NOW.minusSeconds(301)), false), null).contains("stale"));
        String unknown = render("price", Language.ENGLISH, Locale.US, new PriceQuote(snapshot(null), false), null);
        assertTrue(unknown.contains("Price timestamp unknown"));
        assertTrue(unknown.contains("Price age: unknown"));
        assertEquals("unavailable", MessageFormatter.selectedPrice(snapshot(NOW), "JPY", Locale.US, Language.ENGLISH));
        String missing = MessageFormatter.render("{price} | {change}", new PriceQuote(snapshot(NOW), false), "GBP", Locale.US,
                Language.ENGLISH, "", SETTINGS, NOW, null);
        assertEquals("unavailable&r | unknown", missing);
    }

    @Test void chatBoardAndAlertsTranslateTemplateLabelsAndDynamicDirections() {
        PriceQuote quote = new PriceQuote(snapshot(NOW), false);
        String chat = render("price", Language.ENGLISH, Locale.US, quote, null);
        assertTrue(chat.contains("Fetched:"));
        assertTrue(chat.contains("Price age:"));
        assertTrue(render("board", Language.ENGLISH, Locale.US, quote, null).contains("current | Price age:"));
        for (AlertDirection direction : AlertDirection.values()) {
            PriceAlert alert = new PriceAlert(UUID.randomUUID(), direction, new BigDecimal("85000"), "EUR");
            String text = render("alert", Language.ENGLISH, Locale.US, quote, alert);
            assertTrue(text.contains("Price alert:"));
            assertTrue(text.contains(direction == AlertDirection.ABOVE ? "above 85,000.00" : "below 85,000.00"));
        }
    }

    @Test void shippedTemplatesSwitchLanguageWhileCustomOverridesArePreserved() {
        for (String key : new String[]{"price", "actionbar", "alert", "board", "api-error"}) {
            String english = Language.ENGLISH.text("template." + key);
            assertEquals(english, Language.ENGLISH.template(key, Language.GERMAN.text("template." + key), null));
            assertEquals(english, Language.ENGLISH.template(key, null, null));
            assertEquals("Custom {price}", Language.ENGLISH.template(key, "Custom {price}", null));
            assertEquals("English override", Language.ENGLISH.template(key, "Custom {price}", "English override"));
            assertEquals("", Language.ENGLISH.template(key, null, ""));
        }
    }

    @Test void validationErrorsAndHelpUseExplicitTextLanguage() {
        var error = assertThrows(MessageException.class, () -> BTCCommand.positiveAmount("1,50"));
        assertTrue(error.text(Language.ENGLISH).contains("decimal point"));
        assertTrue(error.text(Language.GERMAN).contains("Dezimalpunkt"));
        var player = BTCCommand.helpEntries(false, Language.ENGLISH);
        var admin = BTCCommand.helpEntries(true, Language.ENGLISH);
        assertTrue(player.stream().anyMatch(entry -> entry.contains("/btc language <de|en|AUTO|DEFAULT>")));
        assertTrue(player.stream().noneMatch(entry -> entry.contains("/btc global language")));
        assertTrue(admin.stream().anyMatch(entry -> entry.contains("/btc global language <de|en|AUTO>")));
        assertTrue(admin.stream().anyMatch(entry -> entry.contains("/btc player <Name|UUID> language")));
        assertEquals(BTCCommand.helpEntries(false).size(), player.size());
        assertEquals(BTCCommand.helpEntries(true).size(), admin.size());
    }

    @Test void supportedLanguagesNormalizeAndUnsupportedSelectionsAreRejected() {
        assertEquals(Language.ENGLISH, Language.parse("en-US"));
        assertEquals(Language.GERMAN, Language.parse("DE"));
        assertEquals("en", Language.selection("en-GB"));
        assertEquals("DEFAULT", Language.selection("default"));
        assertEquals("AUTO", Language.selection("auto"));
        for (String invalid : new String[]{"fr", "US", "", "en_US", "DEFAULT"})
            assertThrows(MessageException.class, () -> Language.parse(invalid), invalid);
    }

    @Test void automaticLanguageFollowsClientChangesWithoutOverridingAnExplicitSelection() {
        assertEquals(Language.GERMAN, Language.resolve("DEFAULT", Locale.US, Language.GERMAN, false));
        assertEquals(Language.ENGLISH, Language.resolve("DEFAULT", Locale.US, Language.GERMAN, true));
        assertEquals(Language.GERMAN, Language.resolve("de", Locale.US, Language.ENGLISH, true));
        assertEquals(Language.ENGLISH, Language.resolve("en", Locale.GERMANY, Language.GERMAN, true));
        assertEquals(Language.ENGLISH, Language.resolve("AUTO", Locale.UK, Language.GERMAN, false));
        assertEquals(Language.GERMAN, Language.resolve("AUTO", Locale.GERMANY, Language.ENGLISH, false));
        assertEquals(Language.ENGLISH, Language.resolve("AUTO", Locale.FRANCE, Language.ENGLISH, false));
        assertEquals(Language.GERMAN, Language.resolve("AUTO", null, Language.GERMAN, true));
    }

    @Test void actionbarContentChangesOnlyDisplayedDetailsAndKeepsStaleDataExplicit() {
        PriceQuote fresh = new PriceQuote(snapshot(NOW), false);
        String price = render(ActionbarContent.PRICE.template(), Language.ENGLISH, Locale.US, fresh, null);
        String change = render(ActionbarContent.CHANGE.template(), Language.ENGLISH, Locale.US, fresh, null);
        String full = render(ActionbarContent.FULL.template(), Language.ENGLISH, Locale.US, fresh, null);
        assertTrue(price.contains("80,000.50"));
        assertFalse(price.contains("24h"));
        assertFalse(price.contains("current"));
        assertTrue(change.contains("24h: +2.50%"));
        assertFalse(change.contains("current"));
        assertTrue(full.contains("24h: +2.50%"));
        assertTrue(full.contains("current"));
        for (ActionbarContent content : ActionbarContent.values()) {
            assertTrue(render(content.template(), Language.ENGLISH, Locale.US,
                    new PriceQuote(snapshot(NOW), true), null).contains("stale"));
            assertTrue(render(content.template(), Language.GERMAN, Locale.GERMANY,
                    new PriceQuote(snapshot(NOW.minusSeconds(301)), false), null).contains("veraltet"));
            assertTrue(render(content.template(), Language.ENGLISH, Locale.US,
                    new PriceQuote(snapshot(null), false), null).contains("Price timestamp unknown"));
        }
        assertEquals(ActionbarContent.PRICE, ActionbarContent.parse("price"));
        assertThrows(MessageException.class, () -> ActionbarContent.parse("unknown"));
    }

    @Test void catalogsHaveMatchingKeysAndPlaceholdersWithoutUsingTheHostLocale() {
        assertEquals(Language.GERMAN.keys(), Language.ENGLISH.keys());
        Pattern placeholder = Pattern.compile("\\{[^}]+}");
        for (String key : Language.GERMAN.keys()) {
            Object[] args = {"$0\\", "{2}", "arg2", "arg3", "arg4", "arg5", "arg6", "arg7"};
            String german = Language.GERMAN.text(key, args);
            String english = Language.ENGLISH.text(key, args);
            assertEquals(placeholder.matcher(german).results().map(result -> result.group()).sorted().toList(),
                    placeholder.matcher(english).results().map(result -> result.group()).sorted().toList(), key);
        }
        assertEquals("Your language: {1}", Language.ENGLISH.text("language.changed", "{1}"));
    }
}
