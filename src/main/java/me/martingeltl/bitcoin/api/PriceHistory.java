package me.martingeltl.bitcoin.api;

import me.martingeltl.bitcoin.model.PriceSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Bounded chronological history; writes are coalesced off the server thread. */
public final class PriceHistory implements AutoCloseable {
    private static final int MAX_RECORDS = 10080;
    private static final long MAX_FILE_BYTES = 4 * 1024 * 1024;
    private final Path file;
    private final Duration retention;
    private final Logger logger;
    private final Clock clock;
    private final List<PriceSnapshot> records = new ArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BitcoinPrice-history"); thread.setDaemon(true); return thread;
    });
    private boolean readOnly;
    private boolean closed;
    private boolean queued;
    private long revision;
    private long persistedRevision;

    public PriceHistory(Path file, Duration retention, Logger logger) {
        this(file, retention, logger, Clock.systemUTC());
    }

    public PriceHistory(Path file, Duration retention, Logger logger, Clock clock) {
        this.file = file.toAbsolutePath(); this.logger = logger; this.clock = clock;
        if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("History retention must be positive.");
        this.retention = retention.compareTo(Duration.ofDays(7)) > 0 ? Duration.ofDays(7) : retention;
        restore();
    }

    private void restore() {
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("History exceeds size limit.");
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            if (root.getInt("version") != 1) throw new IOException("Unsupported history version.");
            JSONArray array = root.getJSONArray("samples");
            if (array.length() > MAX_RECORDS) throw new IOException("Too many history records.");
            List<PriceSnapshot> restored = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject sample = array.getJSONObject(i);
                Instant fetchedAt = Instant.parse(sample.getString("fetchedAt"));
                Instant providerAt = sample.isNull("providerUpdatedAt") ? null : Instant.parse(sample.getString("providerUpdatedAt"));
                if (fetchedAt.isAfter(clock.instant().plusSeconds(60)) || (providerAt != null && providerAt.isAfter(fetchedAt.plusSeconds(60)))) {
                    throw new IOException("Future history timestamp.");
                }
                restored.add(new PriceSnapshot(sample.getBigDecimal("eur"), sample.getBigDecimal("usd"),
                        optionalDecimal(sample, "eurChange24h"), optionalDecimal(sample, "usdChange24h"), providerAt, fetchedAt));
            }
            restored.sort(Comparator.comparing(PriceSnapshot::fetchedAt));
            for (PriceSnapshot sample : restored) if (records.isEmpty() || !sameSample(records.getLast(), sample)) records.add(sample);
            prune();
        } catch (IOException | RuntimeException invalid) {
            readOnly = true;
            logger.warning("BitcoinPrice history could not be restored; original file preserved. Repair or rename history file before enabling persistence.");
        }
    }

    private static BigDecimal optionalDecimal(JSONObject object, String key) {
        return object.isNull(key) ? null : object.getBigDecimal(key);
    }

    public synchronized void append(PriceSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (closed) return;
        if (snapshot.fetchedAt().isAfter(clock.instant().plusSeconds(60))) throw new IllegalArgumentException("Future sample.");
        prune();
        if (!records.isEmpty()) {
            PriceSnapshot last = records.getLast();
            if (snapshot.fetchedAt().isBefore(last.fetchedAt()) || sameSample(last, snapshot)) return;
        }
        records.add(snapshot); prune(); revision++;
        if (!readOnly && !queued) { queued = true; writer.execute(this::persistLoop); }
    }

    private static boolean sameSample(PriceSnapshot a, PriceSnapshot b) {
        Instant aTime = a.providerUpdatedAt() == null ? a.fetchedAt() : a.providerUpdatedAt();
        Instant bTime = b.providerUpdatedAt() == null ? b.fetchedAt() : b.providerUpdatedAt();
        return aTime.equals(bTime) && a.eur().compareTo(b.eur()) == 0 && a.usd().compareTo(b.usd()) == 0;
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(retention);
        records.removeIf(sample -> sample.fetchedAt().isBefore(cutoff));
        if (records.size() > MAX_RECORDS) records.subList(0, records.size() - MAX_RECORDS).clear();
    }

    public synchronized List<PriceSnapshot> list(Duration duration) {
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("History range must be positive.");
        prune();
        Instant cutoff = clock.instant().minus(duration.compareTo(retention) > 0 ? retention : duration);
        return records.stream().filter(sample -> !sample.fetchedAt().isBefore(cutoff)).toList();
    }

    private void persistLoop() {
        while (true) {
            List<PriceSnapshot> snapshot;
            long savedRevision;
            synchronized (this) { snapshot = List.copyOf(records); savedRevision = revision; }
            try {
                persist(snapshot);
                synchronized (this) { persistedRevision = savedRevision; }
            }
            catch (IOException failure) { logger.warning("BitcoinPrice history save failed; existing file retained."); }
            synchronized (this) {
                if (savedRevision == revision) { queued = false; return; }
            }
        }
    }

    private void persist(List<PriceSnapshot> snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        JSONArray samples = new JSONArray();
        for (PriceSnapshot sample : snapshot) {
            samples.put(new JSONObject().put("eur", sample.eur()).put("usd", sample.usd())
                    .put("eurChange24h", sample.eurChange24h() == null ? JSONObject.NULL : sample.eurChange24h())
                    .put("usdChange24h", sample.usdChange24h() == null ? JSONObject.NULL : sample.usdChange24h())
                    .put("providerUpdatedAt", sample.providerUpdatedAt() == null ? JSONObject.NULL : sample.providerUpdatedAt().toString())
                    .put("fetchedAt", sample.fetchedAt().toString()));
        }
        Path temporary = Files.createTempFile(file.getParent(), "history-", ".tmp");
        try {
            byte[] payload = new JSONObject().put("version", 1).put("samples", samples).toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            // Retry an earlier failed save, and serialize the final payload behind any pending write.
            if (!readOnly && revision > persistedRevision) writer.execute(this::persistLoop);
            writer.shutdown();
        }
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) logger.warning("BitcoinPrice history flush is still pending.");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
