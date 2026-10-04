package org.bitcoinprice.api;

import org.bitcoinprice.config.ApiSettings;
import org.bitcoinprice.model.PriceQuote;
import org.bitcoinprice.model.PriceSnapshot;
import org.bitcoinprice.model.CurrencyCatalog;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** One asynchronous, bounded provider request shared by all callers. */
public final class CoinGeckoService implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final ApiSettings settings;
    private final Consumer<PriceSnapshot> onFreshSnapshot;
    private final Logger logger;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BitcoinPrice-api"); thread.setDaemon(true); return thread;
    });
    private PriceSnapshot cached;
    private CompletableFuture<PriceQuote> inFlight;
    private HttpURLConnection activeConnection;
    private Instant nextAttempt = Instant.MIN;
    private Instant lastForced;
    private int failures;
    private boolean cachedDegraded;
    private boolean closed;
    private FailureReason lastFailure = FailureReason.NONE;
    private Integer lastFailureHttpStatus;
    private Instant lastFailureAt;

    public CoinGeckoService(ApiSettings settings, Consumer<PriceSnapshot> callback, Logger logger) {
        this(settings, callback, logger, Clock.systemUTC());
    }

    public CoinGeckoService(ApiSettings settings, Consumer<PriceSnapshot> callback, Logger logger, Clock clock) {
        this.settings = settings; this.onFreshSnapshot = callback; this.logger = logger; this.clock = clock;
    }

    public CompletableFuture<PriceQuote> fetchBitcoinPrice() { return request(false); }
    public CompletableFuture<PriceQuote> forceRefresh() { return request(true); }

    /** Public diagnostics contain cached data only, without provider URLs or credentials. */
    public enum FailureReason { NONE, RATE_LIMIT, TIMEOUT, NETWORK, HTTP_ERROR, INVALID_RESPONSE, OUTDATED_PROVIDER_DATA, RESPONSE_TOO_LARGE }
    public enum CacheState { EMPTY, FRESH, CACHE_EXPIRED, PROVIDER_TIMESTAMP_MISSING, PROVIDER_DATA_OUTDATED, PROVIDER_FAILURE, EXPIRED, CLOSED }
    public record ServiceStatus(Optional<PriceQuote> quote, boolean requestInFlight, long retryAfterSeconds,
                                CacheState cacheState, FailureReason lastFailure, Integer httpStatus,
                                Instant failureAt, int consecutiveFailures, long refreshCooldownSeconds) { }

    private static final class ProviderException extends IOException {
        private final FailureReason reason;
        ProviderException(FailureReason reason) { super(reason.name()); this.reason = reason; }
    }

    /** Inspect the cache and retry state without starting a request or changing its cooldown. */
    public synchronized ServiceStatus status() {
        Instant now = clock.instant();
        Optional<PriceQuote> quote = cachedQuote();
        long retrySeconds = closed ? 0 : secondsUntil(now, nextAttempt);
        long refreshSeconds = closed || lastForced == null ? 0 : secondsUntil(now, lastForced.plusSeconds(settings.refreshCooldownSeconds()));
        return new ServiceStatus(quote, !closed && inFlight != null && !inFlight.isDone(), retrySeconds,
                cacheState(now, quote), lastFailure, lastFailureHttpStatus, lastFailureAt, failures, refreshSeconds);
    }

    private static long secondsUntil(Instant now, Instant until) {
        if (!now.isBefore(until)) return 0;
        Duration remaining = Duration.between(now, until);
        return remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
    }

    private CacheState cacheState(Instant now, Optional<PriceQuote> quote) {
        if (closed) return CacheState.CLOSED;
        if (cached == null) return CacheState.EMPTY;
        if (quote.isEmpty()) return CacheState.EXPIRED;
        if (cached.providerUpdatedAt() == null) return CacheState.PROVIDER_TIMESTAMP_MISSING;
        if (Duration.between(cached.providerUpdatedAt(), now).getSeconds() > settings.maxProviderAgeSeconds())
            return CacheState.PROVIDER_DATA_OUTDATED;
        if (cachedDegraded) return CacheState.PROVIDER_FAILURE;
        return quote.get().stale() ? CacheState.CACHE_EXPIRED : CacheState.FRESH;
    }

    private synchronized CompletableFuture<PriceQuote> request(boolean force) {
        if (closed) return CompletableFuture.failedFuture(new IOException("Price service is closed."));
        Instant now = clock.instant();
        Optional<PriceQuote> quote = cachedQuote();
        if (!force && quote.isPresent() && !cachedDegraded
                && now.isBefore(quote.get().snapshot().fetchedAt().plusSeconds(settings.cacheSeconds()))) {
            return CompletableFuture.completedFuture(quote.get());
        }
        if (force && lastForced != null && now.isBefore(lastForced.plusSeconds(settings.refreshCooldownSeconds()))) {
            return CompletableFuture.failedFuture(new IOException("Refresh cooldown is active."));
        }
        if (now.isBefore(nextAttempt)) {
            if (!force && quote.isPresent()) return CompletableFuture.completedFuture(quote.get());
            return CompletableFuture.failedFuture(new IOException("Provider retry cooldown is active."));
        }
        if (force) lastForced = now;
        if (inFlight != null) return mirror(inFlight);
        CompletableFuture<PriceQuote> future = new CompletableFuture<>();
        inFlight = future;
        executor.execute(() -> fetch(future));
        return mirror(future);
    }

    private CompletableFuture<PriceQuote> mirror(CompletableFuture<PriceQuote> future) {
        // One command's cancellation must not cancel another command's shared request.
        return future.thenApply(quote -> quote);
    }

    public synchronized Optional<PriceQuote> cachedQuote() {
        if (cached == null || closed) return Optional.empty();
        Instant now = clock.instant();
        long age = Math.max(0, Duration.between(cached.fetchedAt(), now).getSeconds());
        if (age > settings.maxStaleSeconds()) return Optional.empty();
        boolean providerStale = cached.providerUpdatedAt() == null
                || Duration.between(cached.providerUpdatedAt(), now).getSeconds() > settings.maxProviderAgeSeconds();
        return Optional.of(new PriceQuote(cached, cachedDegraded || age >= settings.cacheSeconds() || providerStale));
    }

    private void fetch(CompletableFuture<PriceQuote> future) {
        HttpURLConnection connection = null;
        long retryAfter = 0;
        Integer responseStatus = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.timeoutMillis());
        try {
            connection = (HttpURLConnection) URI.create(settings.url()).toURL().openConnection();
            synchronized (this) {
                if (closed) throw new IOException("Price service is closed.");
                activeConnection = connection;
            }
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(settings.timeoutMillis());
            connection.setReadTimeout(settings.timeoutMillis());
            connection.setRequestProperty("User-Agent", "BitcoinPrice-Minecraft-Plugin/2.0");
            connection.setRequestProperty("Accept", "application/json");
            if (!settings.demoApiKey().isBlank()) connection.setRequestProperty("x-cg-demo-api-key", settings.demoApiKey());
            connection.connect();
            connection.setReadTimeout(remainingMillis(deadline));
            int status = connection.getResponseCode();
            responseStatus = status;
            if (status != 200) {
                if (status == 429 || status == 503) retryAfter = retryAfterSeconds(connection.getHeaderField("Retry-After"));
                throw new ProviderException(status == 429 ? FailureReason.RATE_LIMIT : FailureReason.HTTP_ERROR);
            }
            if (connection.getContentLengthLong() > MAX_BODY_BYTES) throw new ProviderException(FailureReason.RESPONSE_TOO_LARGE);
            byte[] body;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    // Shrinking timeout prevents a slow byte stream keeping a request alive indefinitely.
                    connection.setReadTimeout(remainingMillis(deadline));
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (output.size() + count > MAX_BODY_BYTES) throw new ProviderException(FailureReason.RESPONSE_TOO_LARGE);
                    output.write(buffer, 0, count);
                }
                body = output.toByteArray();
            }
            PriceSnapshot snapshot = parseSnapshot(new String(body, StandardCharsets.UTF_8), settings, clock.instant());
            PriceQuote quote;
            synchronized (this) {
                if (closed) throw new IOException("Price service is closed.");
                cached = snapshot; cachedDegraded = false; failures = 0; nextAttempt = Instant.MIN;
                lastFailure = FailureReason.NONE; lastFailureHttpStatus = null; lastFailureAt = null;
                quote = cachedQuote().orElseThrow();
            }
            try { onFreshSnapshot.accept(snapshot); }
            catch (RuntimeException callbackError) { logger.warning("BitcoinPrice sample callback failed; valid price retained."); }
            synchronized (this) { if (inFlight == future) inFlight = null; }
            future.complete(quote);
        } catch (Exception error) {
            Optional<PriceQuote> fallback;
            synchronized (this) {
                if (!closed) {
                    if (failures < Integer.MAX_VALUE) failures++;
                    cachedDegraded = true;
                    lastFailure = error instanceof ProviderException provider ? provider.reason
                            : error instanceof SocketTimeoutException ? FailureReason.TIMEOUT
                            : error instanceof IOException ? FailureReason.NETWORK : FailureReason.INVALID_RESPONSE;
                    lastFailureHttpStatus = responseStatus;
                    lastFailureAt = clock.instant();
                    nextAttempt = lastFailureAt.plusSeconds(Math.max(retryAfter, Math.min(300, 5L << (Math.min(failures, 6) - 1))));
                }
                fallback = cachedQuote();
                if (!closed) logger.warning("BitcoinPrice provider request failed; retry cooldown activated.");
            }
            synchronized (this) { if (inFlight == future) inFlight = null; }
            if (fallback.isPresent()) future.complete(new PriceQuote(fallback.get().snapshot(), true));
            else future.completeExceptionally(new IOException("Bitcoin price is temporarily unavailable."));
        } finally {
            if (connection != null) connection.disconnect();
            synchronized (this) {
                if (activeConnection == connection) activeConnection = null;
                if (inFlight == future) inFlight = null;
            }
        }
    }

    private long retryAfterSeconds(String header) {
        if (header == null) return 0;
        try { return Math.max(1, Math.min(3600, Long.parseLong(header.trim()))); }
        catch (NumberFormatException ignored) {
            try {
                long seconds = Duration.between(clock.instant(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
                return Math.max(1, Math.min(3600, seconds));
            } catch (RuntimeException invalid) { return 60; }
        }
    }

    private static int remainingMillis(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("Price provider request timed out.");
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    static PriceSnapshot parseSnapshot(String body, ApiSettings settings, Instant fetchedAt) throws IOException {
        try {
            JSONObject bitcoin = new JSONObject(body).getJSONObject("bitcoin");
            Map<String, BigDecimal> prices = new LinkedHashMap<>(), changes = new LinkedHashMap<>();
            for (String code : CurrencyCatalog.codes()) {
                String field = code.toLowerCase(java.util.Locale.ROOT);
                boolean required = code.equals("EUR") || code.equals("USD");
                BigDecimal price = decimal(bitcoin, field, !required);
                if (price == null) continue;
                prices.put(code, price);
                BigDecimal change = decimal(bitcoin, field + "_24h_change", true);
                if (change != null) changes.put(code, change);
            }
            Instant provider = null;
            if (bitcoin.has("last_updated_at") && !bitcoin.isNull("last_updated_at")) {
                Object raw = bitcoin.get("last_updated_at");
                if (!(raw instanceof Number)) throw new IllegalArgumentException("Invalid timestamp.");
                provider = Instant.ofEpochSecond(new BigDecimal(raw.toString()).longValueExact());
                if (provider.isAfter(fetchedAt.plusSeconds(60))) throw new IllegalArgumentException("Provider timestamp is in the future.");
                if (provider.isBefore(fetchedAt.minusSeconds(settings.maxProviderAgeSeconds())))
                    throw new ProviderException(FailureReason.OUTDATED_PROVIDER_DATA);
            }
            return new PriceSnapshot(prices, changes, provider, fetchedAt);
        } catch (RuntimeException invalid) { throw new ProviderException(FailureReason.INVALID_RESPONSE); }
    }

    private static BigDecimal decimal(JSONObject object, String field, boolean optional) {
        if (optional && (!object.has(field) || object.isNull(field))) return null;
        Object value = object.get(field);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected a numeric price.");
        BigDecimal decimal = new BigDecimal(value.toString());
        if (decimal.precision() > 100 || Math.abs((long) decimal.scale()) > 100) throw new IllegalArgumentException("Numeric value exceeds bounds.");
        return decimal;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (activeConnection != null) activeConnection.disconnect();
        if (inFlight != null) inFlight.completeExceptionally(new IOException("Price service is closed."));
        executor.shutdownNow();
    }
}
