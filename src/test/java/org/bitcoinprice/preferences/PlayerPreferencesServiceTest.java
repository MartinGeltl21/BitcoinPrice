package org.bitcoinprice.preferences;

import org.bitcoinprice.model.PriceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPreferencesServiceTest {
    @TempDir Path temporary;
    private final UUID player = UUID.randomUUID();
    private final MutableClock clock = new MutableClock();

    private PlayerPreferencesService service() {
        return new PlayerPreferencesService(temporary.resolve("players.json"), Duration.ofSeconds(300), Logger.getAnonymousLogger(), clock);
    }

    private PriceSnapshot quote(String eur, String usd) {
        clock.now = clock.now.plusSeconds(1);
        return new PriceSnapshot(new BigDecimal(eur), new BigDecimal(usd), null, null, clock.now, clock.now);
    }

    @Test void personalLanguageSurvivesOtherPreferenceChangesAndRestartIndependentlyOfLocale() {
        UUID other = UUID.randomUUID();
        Preferences selected;
        try (PlayerPreferencesService service = service()) {
            service.setLanguage(player, "en-US");
            service.setLocale(player, "de-DE");
            service.setNotifications(player, false);
            service.setCurrency(player, "GBP");
            service.setDisplay(player, DisplayMode.CHAT);
            service.setActionbar(player, ActionbarMode.INTERVAL, 5);
            selected = service.get(player);
            assertEquals("en", selected.language());
            assertEquals("de-DE", selected.locale());
            assertEquals("DEFAULT", service.get(other).language());
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals(selected, service.get(player));
            assertEquals(Preferences.DEFAULTS, service.get(other));
            service.setLanguage(player, "DEFAULT");
            assertEquals("DEFAULT", service.get(player).language());
            assertEquals("de-DE", service.get(player).locale());
            assertEquals(ActionbarMode.INTERVAL, service.get(player).actionbarMode());
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals("DEFAULT", service.get(player).language());
            assertEquals("de-DE", service.get(player).locale());
        }
    }

    @Test void invalidLanguageCannotPartiallyChangePreferences() {
        try (PlayerPreferencesService service = service()) {
            service.setLanguage(player, "en");
            Preferences original = service.get(player);
            for (String invalid : List.of("fr", "US", "en_US", "")) {
                assertThrows(IllegalArgumentException.class, () -> service.setLanguage(player, invalid));
                assertEquals(original, service.get(player));
            }
        }
    }

    @Test void automaticLanguageAndCompactContentSurviveAllSettersAndRestart() {
        UUID other = UUID.randomUUID();
        Preferences selected;
        try (PlayerPreferencesService service = service()) {
            service.setLanguage(player, "auto");
            service.setActionbar(player, ActionbarMode.INTERVAL, 5);
            service.setActionbarContent(player, ActionbarContent.PRICE);
            service.setCurrency(player, "JPY");
            service.setLocale(player, "en-US");
            service.setNotifications(player, false);
            service.setDisplay(player, DisplayMode.CHAT);
            service.setActionbar(player, ActionbarMode.INTERVAL, 30);
            service.setLanguage(player, "de");
            service.setLanguage(player, "AUTO");
            selected = service.get(player);
            assertEquals(ActionbarContent.PRICE, selected.actionbarContent());
            assertEquals("AUTO", selected.language());
            assertEquals(ActionbarMode.INTERVAL, selected.actionbarMode());
            assertEquals(30, selected.actionbarIntervalMinutes());
            assertEquals("en-US", selected.locale());
            assertEquals(ActionbarContent.FULL, service.get(other).actionbarContent());
            assertThrows(NullPointerException.class, () -> service.setActionbarContent(player, null));
            assertEquals(selected, service.get(player));
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals(selected, service.get(player));
            assertEquals(Preferences.DEFAULTS, service.get(other));
            service.setActionbarContent(player, ActionbarContent.CHANGE);
            assertEquals(ActionbarMode.INTERVAL, service.get(player).actionbarMode());
            assertEquals(30, service.get(player).actionbarIntervalMinutes());
            assertFalse(service.get(player).notifications());
        }
    }

    @Test void settingsAndPortfolioSurviveCloseAndReopenWithoutReset() throws Exception {
        PortfolioBalance balance;
        try (PlayerPreferencesService service = service()) {
            assertEquals(Preferences.DEFAULTS, service.get(player));
            service.setNotifications(player, false);
            service.setCurrency(player, "usd");
            service.setDisplay(player, DisplayMode.ACTIONBAR);
            service.setLocale(player, "en-us");
            service.startPortfolio(player);
            balance = service.buy(player, new BigDecimal("123.45"), new BigDecimal("50000"));
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals(new Preferences(false, "USD", DisplayMode.ACTIONBAR, "en-US"), service.get(player));
            assertEquals(balance, service.getPortfolio(player).orElseThrow());
            assertEquals(balance, service.startPortfolio(player));
        }
        assertTrue(Files.readString(temporary.resolve("players.json")).contains(player.toString()));
    }

    @Test void personalActionbarModesAreIsolatedAndSurviveRestart() {
        UUID other = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Preferences selected;
        try (PlayerPreferencesService service = service()) {
            service.setNotifications(player, false);
            service.setCurrency(player, "GBP");
            service.setLocale(player, "en-GB");
            service.setActionbar(player, ActionbarMode.INTERVAL, 5);
            selected = new Preferences(false, "GBP", DisplayMode.ACTIONBAR, "en-GB", ActionbarMode.INTERVAL, 5);
            assertEquals(selected, service.get(player));
            assertEquals(Preferences.DEFAULTS, service.get(other));
            service.setActionbar(other, ActionbarMode.CONTINUOUS, 0);
            assertEquals(selected, service.get(player));
            assertEquals(Preferences.DEFAULTS, service.get(stranger));
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals(selected, service.get(player));
            assertEquals(new Preferences(true, "DEFAULT", DisplayMode.ACTIONBAR, "DEFAULT"), service.get(other));
            assertEquals(Preferences.DEFAULTS, service.get(stranger));
        }
    }

    @Test void everyOtherPreferenceSetterPreservesPersonalActionbarSettings() {
        try (PlayerPreferencesService service = service()) {
            service.setActionbar(player, ActionbarMode.INTERVAL, 30);
            service.setNotifications(player, false);
            assertEquals(new Preferences(false, "DEFAULT", DisplayMode.ACTIONBAR, "DEFAULT", ActionbarMode.INTERVAL, 30), service.get(player));
            service.setCurrency(player, "JPY");
            assertEquals(new Preferences(false, "JPY", DisplayMode.ACTIONBAR, "DEFAULT", ActionbarMode.INTERVAL, 30), service.get(player));
            service.setLocale(player, "ja-JP");
            assertEquals(new Preferences(false, "JPY", DisplayMode.ACTIONBAR, "ja-JP", ActionbarMode.INTERVAL, 30), service.get(player));
            service.setDisplay(player, DisplayMode.CHAT);
            assertEquals(new Preferences(false, "JPY", DisplayMode.CHAT, "ja-JP", ActionbarMode.INTERVAL, 30), service.get(player));
            service.setDisplay(player, DisplayMode.OFF);
            assertEquals(ActionbarMode.INTERVAL, service.get(player).actionbarMode());
            assertEquals(30, service.get(player).actionbarIntervalMinutes());
            service.setDisplay(player, DisplayMode.ACTIONBAR);
            assertEquals(new Preferences(false, "JPY", DisplayMode.ACTIONBAR, "ja-JP", ActionbarMode.INTERVAL, 30), service.get(player));
        }
    }

    @Test void invalidActionbarSelectionsDoNotMutateSettingsOrSelectDisplay() {
        try (PlayerPreferencesService service = service()) {
            service.setCurrency(player, "CHF");
            Preferences initial = service.get(player);
            for (int minutes : List.of(-1, 2, 15, 59, 61, Integer.MAX_VALUE)) {
                assertThrows(IllegalArgumentException.class, () -> service.setActionbar(player, ActionbarMode.INTERVAL, minutes));
                assertEquals(initial, service.get(player));
            }
            assertThrows(NullPointerException.class, () -> service.setActionbar(player, null, 1));
            assertEquals(initial, service.get(player));
            for (int minutes : List.of(0, 1, 5, 10, 30, 60)) {
                service.setActionbar(player, ActionbarMode.INTERVAL, minutes);
                assertEquals(minutes, service.get(player).actionbarIntervalMinutes());
            }
        }
    }

    @Test void legacyVersionOnePreferencesMigrateWithoutLosingExistingSettings() throws Exception {
        Path file = temporary.resolve("players.json");
        org.json.JSONObject legacyPreferences = new org.json.JSONObject()
                .put("notifications", false).put("currency", "USD").put("display", "ACTIONBAR").put("locale", "en-US");
        org.json.JSONObject legacyPlayer = new org.json.JSONObject().put("preferences", legacyPreferences)
                .put("alerts", new org.json.JSONArray()).put("portfolio", org.json.JSONObject.NULL);
        Files.writeString(file, new org.json.JSONObject().put("version", 1)
                .put("players", new org.json.JSONObject().put(player.toString(), legacyPlayer)).toString());
        try (PlayerPreferencesService service = service()) {
            assertEquals(new Preferences(false, "USD", DisplayMode.ACTIONBAR, "en-US", ActionbarMode.CONTINUOUS, 0), service.get(player));
            assertEquals(ActionbarContent.FULL, service.get(player).actionbarContent());
            assertEquals("DEFAULT", service.get(player).language());
            service.setActionbar(player, ActionbarMode.INTERVAL, 1);
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals(new Preferences(false, "USD", DisplayMode.ACTIONBAR, "en-US", ActionbarMode.INTERVAL, 1), service.get(player));
        }
        assertEquals(1, new org.json.JSONObject(Files.readString(file)).getInt("version"));
        try (var files = Files.list(temporary)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().contains(".invalid-")).count());
        }
    }

    @Test void persistedActionbarIntervalRejectsCoercionAndPreservesMalformedSource() throws Exception {
        for (Object invalid : List.of(new BigDecimal("1.5"), "1", new BigDecimal("4294967297"), true, org.json.JSONObject.NULL)) {
            Path file = temporary.resolve(UUID.randomUUID() + ".json");
            org.json.JSONObject preferences = new org.json.JSONObject().put("notifications", false)
                    .put("currency", "GBP").put("display", "ACTIONBAR").put("locale", "en-GB")
                    .put("actionbarMode", "INTERVAL").put("actionbarIntervalMinutes", invalid);
            org.json.JSONObject storedPlayer = new org.json.JSONObject().put("preferences", preferences)
                    .put("alerts", new org.json.JSONArray()).put("portfolio", org.json.JSONObject.NULL);
            String source = new org.json.JSONObject().put("version", 1)
                    .put("players", new org.json.JSONObject().put(player.toString(), storedPlayer)).toString();
            Files.writeString(file, source);
            try (PlayerPreferencesService service = new PlayerPreferencesService(file, Duration.ofSeconds(300), Logger.getAnonymousLogger(), clock)) {
                assertEquals(Preferences.DEFAULTS, service.get(player), "Malformed interval must not silently select an integer cadence");
            }
            assertEquals(source, Files.readString(file));
            try (var files = Files.list(temporary)) {
                List<Path> backups = files.filter(path -> path.getFileName().toString().startsWith(file.getFileName() + ".invalid-")).toList();
                assertEquals(1, backups.size());
                assertEquals(source, Files.readString(backups.getFirst()));
            }
        }
    }

    @Test void firstQuoteAboveThresholdDoesNotTriggerThenRealCrossingDoes() {
        try (PlayerPreferencesService service = service()) {
            PriceAlert alert = service.addAlert(player, AlertDirection.ABOVE, new BigDecimal("100"), "EUR");
            assertTrue(service.checkAlerts(player, quote("110", "200")).isEmpty());
            assertTrue(service.checkAlerts(player, quote("120", "200")).isEmpty());
            assertTrue(service.checkAlerts(player, quote("90", "200")).isEmpty());
            assertEquals(List.of(alert), service.checkAlerts(player, quote("100", "200")));
            assertTrue(service.checkAlerts(player, quote("101", "200")).isEmpty());
        }
    }

    @Test void hysteresisAndCooldownPreventRepeatedAlerts() {
        try (PlayerPreferencesService service = service()) {
            service.addAlert(player, AlertDirection.ABOVE, new BigDecimal("100"), "EUR");
            service.checkAlerts(player, quote("99", "200"));
            assertEquals(1, service.checkAlerts(player, quote("101", "200")).size());
            clock.now = clock.now.plusSeconds(301);
            service.checkAlerts(player, quote("99.99", "200"));
            assertTrue(service.checkAlerts(player, quote("101", "200")).isEmpty(), "Tiny wobble does not rearm");
            service.checkAlerts(player, quote("99.9", "200"));
            assertEquals(1, service.checkAlerts(player, quote("100", "200")).size());
            service.checkAlerts(player, quote("90", "200"));
            assertTrue(service.checkAlerts(player, quote("101", "200")).isEmpty(), "Cooldown blocks crossing");
            clock.now = clock.now.plusSeconds(301);
            assertTrue(service.checkAlerts(player, quote("102", "200")).isEmpty(), "No late event without new crossing");
            service.checkAlerts(player, quote("99", "200"));
            assertEquals(1, service.checkAlerts(player, quote("101", "200")).size());
        }
    }

    @Test void belowAlertsUseSelectedCurrencyAndIgnoreDuplicateSnapshots() {
        try (PlayerPreferencesService service = service()) {
            PriceAlert alert = service.addAlert(player, AlertDirection.BELOW, new BigDecimal("200"), "USD");
            service.checkAlerts(player, quote("100", "210"));
            PriceSnapshot crossing = quote("120", "199");
            assertEquals(List.of(alert), service.checkAlerts(player, crossing));
            assertTrue(service.checkAlerts(player, crossing).isEmpty());
            assertTrue(service.checkAlerts(player, new PriceSnapshot(new BigDecimal("100"), new BigDecimal("250"),
                    null, null, clock.now.minusSeconds(1), clock.now.minusSeconds(1))).isEmpty());
        }
    }

    @Test void alertCooldownAndArmingSurviveRestart() {
        PriceAlert alert;
        try (PlayerPreferencesService service = service()) {
            alert = service.addAlert(player, AlertDirection.ABOVE, new BigDecimal("100"), "EUR");
            service.checkAlerts(player, quote("90", "200"));
            assertEquals(1, service.checkAlerts(player, quote("110", "200")).size());
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals(List.of(alert), service.listAlerts(player));
            service.checkAlerts(player, quote("90", "200"));
            assertTrue(service.checkAlerts(player, quote("110", "200")).isEmpty());
            clock.now = clock.now.plusSeconds(301);
            service.checkAlerts(player, quote("90", "200"));
            assertEquals(List.of(alert), service.checkAlerts(player, quote("110", "200")));
        }
    }

    @Test void firstBaselineSurvivesRestart() {
        try (PlayerPreferencesService service = service()) {
            service.addAlert(player, AlertDirection.ABOVE, new BigDecimal("100"), "EUR");
            service.checkAlerts(player, quote("90", "200"));
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals(1, service.checkAlerts(player, quote("110", "200")).size());
        }
    }

    @Test void alertsAreBoundedImmutableAndOwnedByPlayer() {
        try (PlayerPreferencesService service = service()) {
            PriceAlert first = service.addAlert(player, AlertDirection.ABOVE, BigDecimal.TEN, "EUR");
            assertThrows(UnsupportedOperationException.class, () -> service.listAlerts(player).clear());
            UUID stranger = UUID.randomUUID();
            assertFalse(service.removeAlert(stranger, first.id().toString()));
            for (int i = 1; i < 10; i++) service.addAlert(player, AlertDirection.BELOW, BigDecimal.TEN, "EUR");
            assertThrows(IllegalArgumentException.class, () -> service.addAlert(player, AlertDirection.ABOVE, BigDecimal.ONE, "EUR"));
            assertFalse(service.removeAlert(player, ""));
            assertTrue(service.removeAlert(player, first.id().toString().substring(0, 8)));
            assertEquals(9, service.listAlerts(player).size());
        }
    }

    @Test void ambiguousShortAlertIdDoesNotRemoveEither() throws Exception {
        UUID a = UUID.fromString("abcd1234-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("abcd1234-0000-0000-0000-000000000002");
        try (PlayerPreferencesService service = service()) {
            service.addAlert(player, AlertDirection.ABOVE, BigDecimal.TEN, "EUR");
            service.addAlert(player, AlertDirection.BELOW, BigDecimal.TEN, "USD");
        }
        org.json.JSONObject json = new org.json.JSONObject(Files.readString(temporary.resolve("players.json")));
        org.json.JSONArray alerts = json.getJSONObject("players").getJSONObject(player.toString()).getJSONArray("alerts");
        alerts.getJSONObject(0).put("id", a.toString());
        alerts.getJSONObject(1).put("id", b.toString());
        Files.writeString(temporary.resolve("players.json"), json.toString());
        try (PlayerPreferencesService service = service()) {
            assertFalse(service.removeAlert(player, "abcd1234"));
            assertEquals(2, service.listAlerts(player).size());
            assertTrue(service.removeAlert(player, a.toString()));
        }
    }

    @Test void portfolioRequiresExplicitStartAndCannotBeResetOrShared() {
        try (PlayerPreferencesService service = service()) {
            assertTrue(service.getPortfolio(player).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> service.buy(player, BigDecimal.TEN, BigDecimal.TEN));
            assertThrows(IllegalArgumentException.class, () -> service.sell(player, BigDecimal.ONE, BigDecimal.TEN));
            assertEquals(new BigDecimal("10000.00"), service.startPortfolio(player).cashEur());
            PortfolioBalance bought = service.buy(player, new BigDecimal("1000"), new BigDecimal("50000"));
            assertEquals(new BigDecimal("9000.00"), bought.cashEur());
            assertEquals(new BigDecimal("0.02000000"), bought.bitcoin());
            assertEquals(bought, service.startPortfolio(player));
            assertTrue(service.getPortfolio(UUID.randomUUID()).isEmpty());
            PortfolioBalance sold = service.sell(player, new BigDecimal("0.01"), new BigDecimal("60000"));
            assertEquals(new BigDecimal("9600.00"), sold.cashEur());
            assertEquals(new BigDecimal("0.01000000"), sold.bitcoin());
        }
    }

    @Test void invalidTradesDoNotChangeBalances() {
        try (PlayerPreferencesService service = service()) {
            PortfolioBalance initial = service.startPortfolio(player);
            for (String amount : List.of("0", "-1", "10000.01", "0.001", "1e100", "1e-100")) {
                assertThrows(IllegalArgumentException.class, () -> service.buy(player, new BigDecimal(amount), new BigDecimal("50000")));
                assertEquals(initial, service.getPortfolio(player).orElseThrow());
            }
            assertThrows(IllegalArgumentException.class, () -> service.buy(player, BigDecimal.ONE, BigDecimal.ZERO));
            assertThrows(IllegalArgumentException.class, () -> service.sell(player, new BigDecimal("0.01"), new BigDecimal("50000")));
            assertThrows(IllegalArgumentException.class, () -> service.buy(player, new BigDecimal("0.01"), new BigDecimal("10000000")));
            assertEquals(initial, service.getPortfolio(player).orElseThrow());
        }
    }

    @Test void tradeRoundingCannotCreateMoneyOnRoundTrip() {
        try (PlayerPreferencesService service = service()) {
            service.startPortfolio(player);
            BigDecimal price = new BigDecimal("75321.456789");
            PortfolioBalance bought = service.buy(player, new BigDecimal("1.00"), price);
            assertEquals(new BigDecimal("0.00001327"), bought.bitcoin());
            PortfolioBalance sold = service.sell(player, bought.bitcoin(), price);
            assertTrue(sold.cashEur().compareTo(new BigDecimal("10000.00")) <= 0);
            assertEquals(0, sold.bitcoin().signum());
        }
    }

    @Test void invalidPreferencesAndAlertsAreRejectedWithoutChangingSettings() {
        try (PlayerPreferencesService service = service()) {
            assertThrows(IllegalArgumentException.class, () -> service.setCurrency(player, "XYZ"));
            assertThrows(IllegalArgumentException.class, () -> service.setLocale(player, "de_DE"));
            assertThrows(IllegalArgumentException.class, () -> service.addAlert(player, AlertDirection.ABOVE, BigDecimal.ZERO, "EUR"));
            assertThrows(IllegalArgumentException.class, () -> service.addAlert(player, AlertDirection.ABOVE, BigDecimal.TEN, "BOTH"));
            assertEquals(Preferences.DEFAULTS, service.get(player));
            assertFalse(service.hasAlerts(player));
        }
    }

    @Test void corruptSourceIsPreservedBeforeWritingReplacement() throws Exception {
        String corrupted = "{\"version\":1,\"players\":{\"invalid-uuid\":{}}}";
        Files.writeString(temporary.resolve("players.json"), corrupted);
        try (PlayerPreferencesService service = service()) {
            assertEquals(Preferences.DEFAULTS, service.get(player));
            service.setCurrency(player, "EUR");
        }
        try (var files = Files.list(temporary)) {
            List<Path> backups = files.filter(path -> path.getFileName().toString().contains(".invalid-")).toList();
            assertEquals(1, backups.size());
            assertEquals(corrupted, Files.readString(backups.getFirst()));
        }
        try (PlayerPreferencesService service = service()) {
            assertEquals("EUR", service.get(player).currency());
        }
    }

    @Test void unknownVersionIsNotSilentlyLoadedOrLost() throws Exception {
        String source = "{\"version\":999,\"players\":{}}";
        Files.writeString(temporary.resolve("players.json"), source);
        try (PlayerPreferencesService ignored = service()) { }
        assertEquals(source, Files.readString(temporary.resolve("players.json")));
        try (var files = Files.list(temporary)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().contains(".invalid-")).count());
        }
    }

    @Test void burstWritesFlushLastStateAndCloseIsIdempotent() {
        PlayerPreferencesService service = service();
        for (int i = 0; i < 100; i++) service.setNotifications(player, i % 2 == 0);
        service.setCurrency(player, "USD");
        service.close();
        service.close();
        assertThrows(IllegalStateException.class, () -> service.setCurrency(player, "EUR"));
        try (PlayerPreferencesService restored = service()) {
            assertFalse(restored.get(player).notifications());
            assertEquals("USD", restored.get(player).currency());
        }
    }

    @Test void additionalCurrencyPreferencesAndAlertBaselinesSurviveRestart() {
        List<String> additional = List.of("GBP", "CHF", "CAD", "AUD", "JPY", "CNY", "INR");
        List<UUID> owners = additional.stream().map(ignored -> UUID.randomUUID()).toList();
        try (PlayerPreferencesService service = service()) {
            for (int i = 0; i < additional.size(); i++) {
                String currency = additional.get(i);
                UUID owner = owners.get(i);
                service.setCurrency(owner, currency.toLowerCase(java.util.Locale.ROOT));
                service.addAlert(owner, AlertDirection.ABOVE, new BigDecimal("100"), currency.toLowerCase(java.util.Locale.ROOT));
                clock.now = clock.now.plusSeconds(1);
                PriceSnapshot baseline = new PriceSnapshot(Map.of(currency, new BigDecimal("90")), Map.of(), clock.now, clock.now);
                assertTrue(service.checkAlerts(owner, baseline).isEmpty());
            }
        }
        try (PlayerPreferencesService service = service()) {
            for (int i = 0; i < additional.size(); i++) {
                String currency = additional.get(i);
                UUID owner = owners.get(i);
                assertEquals(currency, service.get(owner).currency());
                assertEquals(currency, service.listAlerts(owner).getFirst().currency());
                clock.now = clock.now.plusSeconds(1);
                PriceSnapshot crossing = new PriceSnapshot(Map.of(currency, new BigDecimal("101")), Map.of(), clock.now, clock.now);
                assertEquals(service.listAlerts(owner), service.checkAlerts(owner, crossing));
            }
        }
    }

    @Test void missingCurrencySampleSkipsAlertWithoutEstablishingOrReplacingBaseline() {
        try (PlayerPreferencesService service = service()) {
            PriceAlert gbp = service.addAlert(player, AlertDirection.ABOVE, new BigDecimal("100"), "GBP");
            PriceAlert eur = service.addAlert(player, AlertDirection.ABOVE, new BigDecimal("100"), "EUR");
            assertTrue(service.checkAlerts(player, quote("90", "200")).isEmpty());
            clock.now = clock.now.plusSeconds(1);
            PriceSnapshot firstGbp = new PriceSnapshot(Map.of("GBP", new BigDecimal("110")), Map.of(), clock.now, clock.now);
            assertTrue(service.checkAlerts(player, firstGbp).isEmpty(), "Missing currency must not create an invented baseline");
            assertEquals(List.of(eur), service.checkAlerts(player, quote("110", "200")));
            clock.now = clock.now.plusSeconds(1);
            PriceSnapshot gbpSafe = new PriceSnapshot(Map.of("GBP", new BigDecimal("90")), Map.of(), clock.now, clock.now);
            service.checkAlerts(player, gbpSafe);
            assertTrue(service.checkAlerts(player, quote("110", "200")).isEmpty());
            clock.now = clock.now.plusSeconds(1);
            PriceSnapshot gbpCrossing = new PriceSnapshot(Map.of("GBP", new BigDecimal("110")), Map.of(), clock.now, clock.now);
            assertEquals(List.of(gbp), service.checkAlerts(player, gbpCrossing), "Missing samples must preserve prior real baseline");
        }
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
