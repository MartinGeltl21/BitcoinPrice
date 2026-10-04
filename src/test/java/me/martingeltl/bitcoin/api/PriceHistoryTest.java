package me.martingeltl.bitcoin.api;

import me.martingeltl.bitcoin.model.PriceSnapshot;
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
