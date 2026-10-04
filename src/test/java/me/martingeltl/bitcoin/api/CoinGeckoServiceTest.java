package me.martingeltl.bitcoin.api;

import com.sun.net.httpserver.HttpServer;
import me.martingeltl.bitcoin.config.ApiSettings;
import me.martingeltl.bitcoin.model.PriceQuote;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class CoinGeckoServiceTest {
    private static final Logger LOG = Logger.getLogger("api-test");
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Test void rejectsIncompleteInvalidAndOutdatedNumbers() throws Exception {
        ApiSettings settings = settings("http://localhost", 500);
        for (String invalid : new String[]{"{}", "{\"bitcoin\":{\"eur\":1}}", body("0", "2", NOW),
                body("-1", "2", NOW), body("\"NaN\"", "2", NOW), body("1e500", "2", NOW),
                body("1", "2", NOW.minusSeconds(601)), body("1", "2", NOW.plusSeconds(61)),
                "{\"bitcoin\":{\"eur\":\"1\",\"usd\":2}}"}) {
            assertThrows(IOException.class, () -> CoinGeckoService.parseSnapshot(invalid, settings, NOW), invalid);
        }
        var sample = CoinGeckoService.parseSnapshot(body("90000.12345678", "100000", NOW), settings, NOW);
        assertEquals("90000.12345678", sample.eur().toPlainString());
        assertEquals(NOW, sample.fetchedAt());
        var unknown = CoinGeckoService.parseSnapshot("{\"bitcoin\":{\"eur\":1,\"usd\":2}}", settings, NOW);
        assertNull(unknown.providerUpdatedAt());
        assertNull(unknown.eurChange24h());
    }

    @Test void sharesConcurrentRequestAndCachesWithoutCallerCancellationLeak() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] bytes = body("90000", "100000", NOW).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (CoinGeckoService service = new CoinGeckoService(settings(url(server), 2000), sample -> {}, LOG, Clock.fixed(NOW, ZoneOffset.UTC))) {
            CompletableFuture<PriceQuote> first = service.fetchBitcoinPrice();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture<PriceQuote> second = service.fetchBitcoinPrice();
            first.cancel(true); release.countDown();
            assertFalse(second.get(3, TimeUnit.SECONDS).stale());
            for (int i = 0; i < 10; i++) assertFalse(service.fetchBitcoinPrice().get(1, TimeUnit.SECONDS).stale());
            assertEquals(1, requests.get());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void retryAfterAndForcedCooldownCannotBeBypassedAndFallbackExpires() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger requests = new AtomicInteger(), status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body("90000", "100000", clock.instant()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "120");
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (CoinGeckoService service = new CoinGeckoService(settings(url(server), 1000), sample -> {}, LOG, clock)) {
            assertFalse(service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS).stale());
            assertFalse(service.forceRefresh().get(2, TimeUnit.SECONDS).stale());
            assertThrows(ExecutionException.class, () -> service.forceRefresh().get(1, TimeUnit.SECONDS));
            status.set(429); clock.advance(60);
            assertTrue(service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS).stale());
            assertTrue(service.cachedQuote().orElseThrow().stale());
            int calls = requests.get();
            clock.advance(60);
            assertTrue(service.fetchBitcoinPrice().get(1, TimeUnit.SECONDS).stale());
            assertThrows(ExecutionException.class, () -> service.forceRefresh().get(1, TimeUnit.SECONDS));
            assertEquals(calls, requests.get());
            status.set(200); clock.advance(61);
            assertFalse(service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS).stale());
            clock.advance(901); assertTrue(service.cachedQuote().isEmpty());
        } finally { server.stop(0); }
    }

    @Test void unknownTimestampIsStaleButDoesNotCauseCacheRequestStorm() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); byte[] bytes = "{\"bitcoin\":{\"eur\":1,\"usd\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (CoinGeckoService service = new CoinGeckoService(settings(url(server), 1000), sample -> { throw new IllegalStateException(); }, LOG, Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertTrue(service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS).stale());
            assertTrue(service.fetchBitcoinPrice().get(1, TimeUnit.SECONDS).stale());
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    @Test void timeoutAndShutdownCompleteWaitingCallers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        // Leave enough time for a loaded CI runner to accept the socket, while the handler
        // remains blocked much longer than the request deadline.
        CoinGeckoService service = new CoinGeckoService(settings(url(server), 2000), sample -> {}, LOG);
        try {
            var pending = service.fetchBitcoinPrice();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            service.close();
            assertThrows(ExecutionException.class, () -> service.fetchBitcoinPrice().get(1, TimeUnit.SECONDS));
        } finally { service.close(); release.countDown(); server.stop(0); }

        CountDownLatch secondEntered = new CountDownLatch(1), secondRelease = new CountDownLatch(1);
        HttpServer secondServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secondServer.createContext("/", exchange -> {
            secondEntered.countDown();
            try { secondRelease.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        secondServer.start();
        CoinGeckoService second = new CoinGeckoService(settings(url(secondServer), 10000), sample -> {}, LOG);
        try {
            var pending = second.fetchBitcoinPrice(); assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            second.close(); assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
        } finally { second.close(); secondRelease.countDown(); secondServer.stop(0); }
    }

    @Test void enforcesResponseBodyLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = new byte[65537]; exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.start();
        try (CoinGeckoService service = new CoinGeckoService(settings(url(server), 1000), sample -> fail(), LOG)) {
            assertThrows(ExecutionException.class, () -> service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS));
        } finally { server.stop(0); }
    }

    private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    private static ApiSettings settings(String url, int timeout) { return new ApiSettings(url, timeout, 60, 900, 600, 60, ""); }
    private static String body(String eur, String usd, Instant updated) {
        return "{\"bitcoin\":{\"eur\":" + eur + ",\"usd\":" + usd + ",\"eur_24h_change\":-2.3,\"usd_24h_change\":null,\"last_updated_at\":" + updated.getEpochSecond() + "}}";
    }
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> time;
        MutableClock(Instant now) { time = new AtomicReference<>(now); }
        void advance(long seconds) { time.updateAndGet(now -> now.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time.get(); }
    }
}
