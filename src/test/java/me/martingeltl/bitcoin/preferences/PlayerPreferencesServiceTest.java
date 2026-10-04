package me.martingeltl.bitcoin.preferences;

import me.martingeltl.bitcoin.model.PriceSnapshot;
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
            assertThrows(IllegalArgumentException.class, () -> service.setCurrency(player, "GBP"));
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

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
