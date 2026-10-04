package org.bitcoinprice.api;

import com.sun.net.httpserver.HttpServer;
import org.bitcoinprice.config.ApiSettings;
import org.bitcoinprice.model.PriceQuote;
import org.bitcoinprice.presentation.Language;
import org.bitcoinprice.presentation.ServiceDiagnostics;
import org.bitcoinprice.api.CoinGeckoService.CacheState;
import org.bitcoinprice.api.CoinGeckoService.FailureReason;
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

    @Test void parsesAdditionalCurrenciesWithoutSubstitutingMissingQuotes() throws Exception {
        String response = body("90000", "100000", NOW).replace("\"usd\":100000", "\"usd\":100000,\"gbp\":75000,\"gbp_24h_change\":-1.5,\"jpy\":15000000,\"jpy_24h_change\":null");
        var snapshot = CoinGeckoService.parseSnapshot(response, settings("http://localhost", 500), NOW);
        assertEquals(new java.math.BigDecimal("75000"), snapshot.price("GBP"));
        assertEquals(new java.math.BigDecimal("-1.5"), snapshot.change24h("GBP"));
        assertNull(snapshot.change24h("JPY"));
        assertFalse(snapshot.supports("CHF"));
        assertThrows(IllegalArgumentException.class, () -> snapshot.price("CHF"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.prices().put("CHF", java.math.BigDecimal.ONE));
        assertThrows(IOException.class, () -> CoinGeckoService.parseSnapshot(response.replace("\"gbp\":75000", "\"gbp\":0"), settings("http://localhost", 500), NOW));
    }

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
            var pendingStatus = service.status();
            assertTrue(pendingStatus.requestInFlight());
            assertTrue(pendingStatus.quote().isEmpty());
            assertEquals(0, pendingStatus.retryAfterSeconds());
            assertEquals(1, requests.get());
            assertEquals(CacheState.EMPTY, pendingStatus.cacheState());
            assertEquals(FailureReason.NONE, pendingStatus.lastFailure());
            CompletableFuture<PriceQuote> second = service.fetchBitcoinPrice();
            first.cancel(true); release.countDown();
            assertFalse(second.get(3, TimeUnit.SECONDS).stale());
            for (int i = 0; i < 10; i++) assertFalse(service.fetchBitcoinPrice().get(1, TimeUnit.SECONDS).stale());
            assertEquals(1, requests.get());
            assertEquals(CacheState.FRESH, service.status().cacheState());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void statusIsReadOnlyAndTracksCacheRetryAndExpiry() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger requests = new AtomicInteger(), responseCode = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body("90000", "100000", clock.instant()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "120");
            exchange.sendResponseHeaders(responseCode.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (CoinGeckoService service = new CoinGeckoService(settings(url(server), 1000), sample -> {}, LOG, clock)) {
            for (int i = 0; i < 5; i++) {
                var status = service.status();
                assertTrue(status.quote().isEmpty());
                assertFalse(status.requestInFlight());
                assertEquals(0, status.retryAfterSeconds());
            }
            assertEquals(0, requests.get(), "Status must not initialize the cache through HTTP");
            service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS);
            var fresh = service.status();
            assertFalse(fresh.quote().orElseThrow().stale());
            assertFalse(fresh.requestInFlight());
            assertEquals(0, fresh.retryAfterSeconds());
            assertEquals(1, requests.get());

            clock.advance(60); responseCode.set(429);
            assertTrue(service.status().quote().orElseThrow().stale());
            assertEquals(CacheState.CACHE_EXPIRED, service.status().cacheState());
            assertEquals(1, requests.get(), "Inspecting an aged cache must not refresh it");
            assertTrue(service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS).stale());
            var degraded = service.status();
            assertTrue(degraded.quote().orElseThrow().stale());
            assertFalse(degraded.requestInFlight());
            assertEquals(120, degraded.retryAfterSeconds());
            assertEquals(CacheState.PROVIDER_FAILURE, degraded.cacheState());
            assertEquals(FailureReason.RATE_LIMIT, degraded.lastFailure());
            assertEquals(429, degraded.httpStatus());
            assertEquals(1, degraded.consecutiveFailures());
            assertEquals(clock.instant(), degraded.failureAt());
            var diagnostics = ServiceDiagnostics.lines(degraded, Language.ENGLISH);
            assertTrue(diagnostics.stream().anyMatch(line -> line.contains("rate limit") && line.contains("HTTP 429")));
            assertTrue(diagnostics.stream().anyMatch(line -> line.contains("120 seconds")));
            assertEquals(2, requests.get(), "Rendering diagnostics is read-only");
            clock.advance(30);
            assertEquals(90, service.status().retryAfterSeconds());
            clock.advance(91);
            assertEquals(0, service.status().retryAfterSeconds());
            assertEquals(2, requests.get(), "Status must not retry automatically after the cooldown");
            clock.advance(901);
            assertTrue(service.status().quote().isEmpty());
            assertEquals(CacheState.EXPIRED, service.status().cacheState());
            assertEquals(2, requests.get(), "Expired cache inspection must remain read-only");
        } finally { server.stop(0); }
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
            assertEquals(CacheState.PROVIDER_TIMESTAMP_MISSING, service.status().cacheState());
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
            assertEquals(FailureReason.TIMEOUT, service.status().lastFailure());
            assertTrue(ServiceDiagnostics.lines(service.status(), Language.GERMAN).stream().anyMatch(line -> line.contains("Zeitüberschreitung")));
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
            assertEquals(FailureReason.RESPONSE_TOO_LARGE, service.status().lastFailure());
        } finally { server.stop(0); }
    }

    @Test void diagnosesHttpInvalidAndOutdatedResponsesWithoutExposingRequestDetails() throws Exception {
        AtomicInteger status = new AtomicInteger();
        AtomicReference<String> response = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "17");
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        record Case(int code, String body, FailureReason reason) { }
        try {
            for (Case example : new Case[]{
                    new Case(429, "limited", FailureReason.RATE_LIMIT),
                    new Case(503, "unavailable", FailureReason.HTTP_ERROR),
                    new Case(401, "unauthorized", FailureReason.HTTP_ERROR),
                    new Case(200, "{}", FailureReason.INVALID_RESPONSE),
                    new Case(200, body("1", "2", NOW.minusSeconds(601)), FailureReason.OUTDATED_PROVIDER_DATA),
                    new Case(200, body("1", "2", NOW.plusSeconds(61)), FailureReason.INVALID_RESPONSE)}) {
                status.set(example.code()); response.set(example.body());
                ApiSettings settings = new ApiSettings(url(server), 1000, 60, 900, 600, 60, "test-demo-key");
                try (CoinGeckoService service = new CoinGeckoService(settings, ignored -> fail(), LOG, Clock.fixed(NOW, ZoneOffset.UTC))) {
                    assertThrows(ExecutionException.class, () -> service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS));
                    var result = service.status();
                    assertEquals(example.reason(), result.lastFailure());
                    assertEquals(example.code(), result.httpStatus());
                    assertEquals(CacheState.EMPTY, result.cacheState());
                    assertEquals(1, result.consecutiveFailures());
                    assertEquals(example.code() == 429 || example.code() == 503 ? 17 : 5, result.retryAfterSeconds());
                    int count = requests.get();
                    for (Language language : Language.values()) {
                        String text = String.join("\n", ServiceDiagnostics.lines(result, language));
                        assertTrue(text.contains("HTTP " + example.code()));
                        assertFalse(text.contains("test-demo-key"));
                        assertFalse(text.contains(url(server)));
                    }
                    assertFalse(result.toString().contains("test-demo-key"));
                    assertEquals(count, requests.get());
                }
            }
        } finally { server.stop(0); }
    }

    @Test void recoveryClearsFailureAndRefreshCooldownDoesNotInventAnApiError() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger code = new AtomicInteger(429), requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body("90000", "100000", clock.instant()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try (CoinGeckoService service = new CoinGeckoService(settings(url(server), 1000), ignored -> {}, LOG, clock)) {
            assertThrows(ExecutionException.class, () -> service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS));
            assertEquals(FailureReason.RATE_LIMIT, service.status().lastFailure());
            clock.advance(6); code.set(200);
            assertFalse(service.forceRefresh().get(2, TimeUnit.SECONDS).stale());
            var recovered = service.status();
            assertEquals(CacheState.FRESH, recovered.cacheState());
            assertEquals(FailureReason.NONE, recovered.lastFailure());
            assertNull(recovered.failureAt());
            assertNull(recovered.httpStatus());
            assertEquals(0, recovered.consecutiveFailures());
            assertEquals(60, recovered.refreshCooldownSeconds());
            assertThrows(ExecutionException.class, () -> service.forceRefresh().get(1, TimeUnit.SECONDS));
            assertEquals(FailureReason.NONE, service.status().lastFailure());
            assertEquals(2, requests.get());
            assertTrue(ServiceDiagnostics.lines(service.status(), Language.ENGLISH).stream().anyMatch(line -> line.contains("Manual refresh") && line.contains("60 seconds")));
        } finally { server.stop(0); }
    }

    @Test void cacheAgeAndConnectionFailuresHaveSeparateDiagnoses() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body("90000", "100000", NOW.minusSeconds(599)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        String endpoint = url(server);
        try (CoinGeckoService service = new CoinGeckoService(settings(endpoint, 1000), ignored -> {}, LOG, clock)) {
            assertFalse(service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS).stale());
            clock.advance(2);
            assertEquals(CacheState.PROVIDER_DATA_OUTDATED, service.status().cacheState());
            assertEquals(1, requests.get());
            clock.advance(900);
            assertEquals(CacheState.EXPIRED, service.status().cacheState());
        } finally { server.stop(0); }
        try (CoinGeckoService service = new CoinGeckoService(settings(endpoint, 1000), ignored -> fail(), LOG)) {
            assertThrows(ExecutionException.class, () -> service.fetchBitcoinPrice().get(2, TimeUnit.SECONDS));
            assertEquals(FailureReason.NETWORK, service.status().lastFailure());
            assertNull(service.status().httpStatus());
            service.close();
            assertEquals(CacheState.CLOSED, service.status().cacheState());
        }
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
