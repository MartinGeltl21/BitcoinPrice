package org.bitcoinprice.api;

import org.bitcoinprice.model.PriceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class PriceHistoryTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final Logger LOG = Logger.getLogger("history-test");

    @Test void migratesLegacyHistoryAndPersistsNewCurrencySamples() throws Exception {
        Path file = directory.resolve("history.json");
        String legacy = "{\"version\":1,\"samples\":[{\"eur\":90000,\"usd\":100000,\"eurChange24h\":null,\"usdChange24h\":null,"
                + "\"providerUpdatedAt\":\"2026-10-04T11:59:00Z\",\"fetchedAt\":\"2026-10-04T11:59:00Z\"}]}";
        Files.writeString(file, legacy);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (PriceHistory history = new PriceHistory(file, Duration.ofDays(7), LOG, clock)) {
            assertFalse(history.list(Duration.ofHours(1)).getFirst().supports("GBP"));
            history.append(new PriceSnapshot(java.util.Map.of("EUR", new BigDecimal("90000"), "USD", new BigDecimal("100000"), "GBP", new BigDecimal("75000")),
                    java.util.Map.of("GBP", new BigDecimal("-2.5")), NOW, NOW));
        }
        assertEquals(2, new org.json.JSONObject(Files.readString(file)).getInt("version"));
        try (PriceHistory restored = new PriceHistory(file, Duration.ofDays(7), LOG, clock)) {
            var samples = restored.list(Duration.ofHours(1));
            assertEquals(2, samples.size());
            assertEquals(new BigDecimal("75000"), samples.getLast().price("GBP"));
            assertEquals(new BigDecimal("-2.5"), samples.getLast().change24h("GBP"));
            assertFalse(samples.getFirst().supports("GBP"));
        }
    }

    @Test void restoresDeduplicatedHistoryAndPrunesRetention() throws Exception {
        Path file = directory.resolve("history.json");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (PriceHistory history = new PriceHistory(file, Duration.ofDays(7), LOG, clock)) {
            history.append(sample(NOW.minus(Duration.ofDays(8)), NOW.minus(Duration.ofDays(8)), "1"));
            history.append(sample(NOW.minusSeconds(7200), NOW.minusSeconds(7200), "2"));
            history.append(sample(NOW.minusSeconds(60), NOW.minusSeconds(60), "3"));
            history.append(sample(NOW.minusSeconds(60), NOW, "3"));
            assertEquals(2, history.list(Duration.ofDays(7)).size());
            assertEquals(1, history.list(Duration.ofHours(1)).size());
            assertThrows(UnsupportedOperationException.class, () -> history.list(Duration.ofDays(7)).clear());
        }
        try (PriceHistory restored = new PriceHistory(file, Duration.ofDays(7), LOG, clock)) {
            assertEquals(2, restored.list(Duration.ofDays(7)).size());
            assertEquals(new BigDecimal("3"), restored.list(Duration.ofHours(1)).getFirst().eur());
        }
    }

    @Test void preservesMalformedSourceInsteadOfReplacingIt() throws Exception {
        Path file = directory.resolve("history.json"); String invalid = "{broken";
        Files.writeString(file, invalid);
        try (PriceHistory history = new PriceHistory(file, Duration.ofDays(7), LOG, Clock.fixed(NOW, ZoneOffset.UTC))) {
            history.append(sample(NOW, NOW, "3")); assertEquals(1, history.list(Duration.ofHours(1)).size());
        }
        assertEquals(invalid, Files.readString(file));
    }

    @Test void burstWritesFlushLatestSnapshotAndPreserveUnknownTimestamp() throws Exception {
        Path file = directory.resolve("history.json");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (PriceHistory history = new PriceHistory(file, Duration.ofDays(7), LOG, clock)) {
            for (int i = 100; i >= 0; i--) history.append(sample(null, NOW.minusSeconds(i), Integer.toString(101 - i)));
            assertThrows(IllegalArgumentException.class, () -> history.append(sample(null, NOW.plusSeconds(61), "2")));
        }
        try (PriceHistory restored = new PriceHistory(file, Duration.ofDays(7), LOG, clock)) {
            var result = restored.list(Duration.ofHours(1));
            assertEquals(101, result.size()); assertNull(result.getLast().providerUpdatedAt());
            assertEquals(new BigDecimal("101"), result.getLast().eur());
        }
    }

    private static PriceSnapshot sample(Instant provider, Instant fetched, String price) {
        return new PriceSnapshot(new BigDecimal(price), new BigDecimal(price), null, null, provider, fetched);
    }
}
