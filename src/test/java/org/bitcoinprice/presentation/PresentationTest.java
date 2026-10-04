package org.bitcoinprice.presentation;

import org.bitcoinprice.commands.BTCCommand;
import org.bitcoinprice.config.ApiSettings;
import org.bitcoinprice.model.PriceSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PresentationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static PriceSnapshot sample(String price, Instant fetched, Instant provider) {
        return new PriceSnapshot(new BigDecimal(price), new BigDecimal(price).multiply(new BigDecimal("1.1")), null, null, provider, fetched);
    }
    private static ApiSettings timings() { return new ApiSettings("https://example.com/price", 1000, 60, 600, 300, 60, ""); }

    @Test void satoshiConversionNeverOverstatesPurchasableWholeSatoshis() {
        assertEquals(new BigDecimal("12500"), BTCCommand.toSats(new BigDecimal("10"), new BigDecimal("80000")));
        assertEquals(new BigDecimal("33333333"), BTCCommand.toSats(BigDecimal.ONE, new BigDecimal("3")));
        assertEquals(BigDecimal.ZERO, BTCCommand.toSats(new BigDecimal("0.00000001"), new BigDecimal("80000")));
    }
    @Test void humanAmountsRejectAmbiguousOrAbusiveInputBeforeRequestingPrices() {
        for (String input : List.of("-1", "0", "NaN", "1e100", "1,50", "1.000000001", "1000000000001", "9".repeat(100)))
            assertThrows(IllegalArgumentException.class, () -> BTCCommand.positiveAmount(input), input);
        assertEquals(new BigDecimal("10.50"), BTCCommand.positiveAmount("10.50"));
    }
    @Test void numberFormattingUsesPlayerLocaleRegardlessOfHostLocale() {
        assertEquals("80.000,50", MessageFormatter.number(new BigDecimal("80000.5"), Locale.GERMANY, 2));
        assertEquals("80,000.50", MessageFormatter.number(new BigDecimal("80000.5"), Locale.US, 2));
    }
    @Test void fiatFormattingHonorsIsoCurrencyPrecisionAndLocale() {
        assertEquals("14,000,000", MessageFormatter.money(new BigDecimal("14000000.2"), Locale.US, "JPY"));
        assertEquals("70.000,50", MessageFormatter.money(new BigDecimal("70000.5"), Locale.GERMANY, "GBP"));
        assertEquals("70,000.50", MessageFormatter.money(new BigDecimal("70000.5"), Locale.US, "GBP"));
        assertThrows(IllegalArgumentException.class, () -> MessageFormatter.money(BigDecimal.ONE, Locale.US, "BTC"));
    }
    @Test void optionalCurrencyUsesOwnPriceAndNeverFallsBackToEur() {
        PriceSnapshot snapshot = new PriceSnapshot(Map.of("EUR", new BigDecimal("80000"), "USD", new BigDecimal("90000"), "GBP", new BigDecimal("70000")),
                Map.of("GBP", new BigDecimal("3.5")), NOW, NOW);
        assertEquals("70,000.00", MessageFormatter.selectedPrice(snapshot, "GBP", Locale.US));
        assertEquals("nicht verfügbar", MessageFormatter.selectedPrice(snapshot, "JPY", Locale.US));
        assertEquals("nicht verfügbar", MessageFormatter.selectedPrice(sample("80000", NOW, NOW), "GBP", Locale.US));
    }
    @Test void extendedHistoryIgnoresLegacySamplesWithoutSelectedCurrency() {
        PriceSnapshot legacy = sample("999999", NOW.minusSeconds(3500), NOW);
        PriceSnapshot gbp = new PriceSnapshot(Map.of("EUR", new BigDecimal("80000"), "USD", new BigDecimal("90000"), "GBP", new BigDecimal("70000")), Map.of(), NOW, NOW);
        String chart = MessageFormatter.chart(List.of(legacy, gbp), "GBP", Duration.ofHours(1), NOW);
        assertEquals("[" + " ".repeat(31) + "-]", chart);
    }
    @Test void missingProviderTimestampCannotEnableAlertsOrTrading() {
        assertFalse(MessageFormatter.isFresh(sample("80000", NOW, null), timings(), NOW));
    }
    @Test void freshnessIsRecheckedAtActualExecutionTime() {
        PriceSnapshot price = sample("80000", NOW, NOW.minusSeconds(290));
        assertTrue(MessageFormatter.isFresh(price, timings(), NOW));
        assertFalse(MessageFormatter.isFresh(price, timings(), NOW.plusSeconds(11)));
        assertFalse(MessageFormatter.isFresh(sample("80000", NOW, NOW), timings(), NOW.plusSeconds(61)));
        assertFalse(MessageFormatter.isFresh(sample("80000", NOW, NOW.plusSeconds(61)), timings(), NOW));
    }
    @Test void chartPreservesEmptyTimeBinsAndIgnoresOutOfWindowData() {
        String chart = MessageFormatter.chart(List.of(sample("10", NOW.minusSeconds(3599), NOW), sample("20", NOW, NOW),
                sample("999", NOW.minusSeconds(3601), NOW), sample("999", NOW.plusSeconds(1), NOW)), "EUR", Duration.ofHours(1), NOW);
        assertEquals(34, chart.length());
        assertEquals('_', chart.charAt(1));
        assertEquals('#', chart.charAt(32));
        assertEquals(" ".repeat(30), chart.substring(2, 32));
    }
    @Test void chartShowsFlatPricesWithoutDivisionByZero() {
        String chart = MessageFormatter.chart(List.of(sample("10", NOW, NOW)), "EUR", Duration.ofHours(1), NOW);
        assertTrue(chart.endsWith("-]"));
        assertEquals("[" + " ".repeat(32) + "]", MessageFormatter.chart(List.of(sample("10", NOW.minusSeconds(3601), NOW)), "EUR", Duration.ofHours(1), NOW));
    }
}
