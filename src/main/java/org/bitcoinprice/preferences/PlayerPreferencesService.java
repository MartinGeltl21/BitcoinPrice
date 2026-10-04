package org.bitcoinprice.preferences;

import org.bitcoinprice.model.PriceSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Thread-safe preferences and virtual portfolio with serialized, atomic persistence. */
public final class PlayerPreferencesService implements AutoCloseable {
    private static final BigDecimal MAX_VALUE = new BigDecimal("1000000000000000");
    private static final BigDecimal HYSTERESIS = new BigDecimal("0.001");
    private static final int MAX_ALERTS = 10;
    private final Path file;
    private final Duration cooldown;
    private final Logger logger;
    private final Clock clock;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final ExecutorService writer;
    private String pending;
    private String failedPayload;
    private boolean writeScheduled;
    private boolean persistenceBlocked;
    private boolean closed;

    public PlayerPreferencesService(Path file, Duration alertCooldown, Logger logger) {
        this(file, alertCooldown, logger, Clock.systemUTC());
    }

    public PlayerPreferencesService(Path file, Duration alertCooldown, Logger logger, Clock clock) {
        this.file = Objects.requireNonNull(file).toAbsolutePath();
        this.cooldown = Objects.requireNonNull(alertCooldown);
        if (alertCooldown.isNegative()) throw new IllegalArgumentException("Negative alert cooldown");
        this.logger = Objects.requireNonNull(logger);
        this.clock = Objects.requireNonNull(clock);
        load();
        writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BitcoinPrice-preferences");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized Preferences get(UUID player) {
        Objects.requireNonNull(player);
        PlayerData data = players.get(player);
        return data == null ? Preferences.DEFAULTS : data.preferences;
    }

    public synchronized void setNotifications(UUID player, boolean enabled) {
        Preferences old = get(player);
        set(player, new Preferences(enabled, old.currency(), old.display(), old.locale()));
    }

    public synchronized void setCurrency(UUID player, String currency) {
        Preferences old = get(player);
        set(player, new Preferences(old.notifications(), currency, old.display(), old.locale()));
    }

    public synchronized void setDisplay(UUID player, DisplayMode display) {
        Preferences old = get(player);
        set(player, new Preferences(old.notifications(), old.currency(), display, old.locale()));
    }

    public synchronized void setLocale(UUID player, String locale) {
        Preferences old = get(player);
        set(player, new Preferences(old.notifications(), old.currency(), old.display(), locale));
    }

    private void set(UUID player, Preferences preferences) {
        PlayerData data = mutable(player);
        data.preferences = preferences;
        persist();
    }

    public synchronized List<PriceAlert> listAlerts(UUID player) {
        Objects.requireNonNull(player);
        PlayerData data = players.get(player);
        return data == null ? List.of() : data.alerts.values().stream().map(state -> state.alert).toList();
    }

    public synchronized boolean hasAlerts(UUID player) { return !listAlerts(player).isEmpty(); }

    public synchronized PriceAlert addAlert(UUID player, AlertDirection direction, BigDecimal threshold, String currency) {
        PriceAlert alert = new PriceAlert(UUID.randomUUID(), direction, threshold, currency);
        PlayerData data = mutable(player);
        if (data.alerts.size() >= MAX_ALERTS) throw new IllegalArgumentException("Maximum of 10 alerts per player");
        data.alerts.put(alert.id(), new AlertState(alert));
        persist();
        return alert;
    }

    public synchronized boolean removeAlert(UUID player, String id) {
        requireOpen();
        Objects.requireNonNull(player);
        if (id == null || !id.matches("(?i)[a-f0-9-]{4,36}")) return false;
        PlayerData data = players.get(player);
        if (data == null) return false;
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        List<UUID> matches = data.alerts.keySet().stream().filter(key -> key.toString().startsWith(normalized)).toList();
        if (matches.size() != 1) return false;
        data.alerts.remove(matches.getFirst());
        persist();
        return true;
    }

    public synchronized List<PriceAlert> checkAlerts(UUID player, PriceSnapshot snapshot) {
        requireOpen();
        Objects.requireNonNull(player);
        Objects.requireNonNull(snapshot);
        PlayerData data = players.get(player);
        if (data == null || data.alerts.isEmpty()) return List.of();
        List<PriceAlert> triggered = new ArrayList<>();
        Instant now = clock.instant();
        boolean changed = false;
        for (AlertState state : data.alerts.values()) {
            if (!snapshot.supports(state.alert.currency())) continue;
            if (state.sampleAt != null && !snapshot.fetchedAt().isAfter(state.sampleAt)) continue;
            BigDecimal price = snapshot.price(state.alert.currency());
            requirePositive(price, "price");
            BigDecimal threshold = state.alert.threshold();
            boolean above = state.alert.direction() == AlertDirection.ABOVE;
            if (!state.armed) {
                BigDecimal rearmAt = threshold.multiply(above ? BigDecimal.ONE.subtract(HYSTERESIS) : BigDecimal.ONE.add(HYSTERESIS));
                if (above ? price.compareTo(rearmAt) <= 0 : price.compareTo(rearmAt) >= 0) state.armed = true;
            }
            boolean crossed = state.previous != null && (above
                    ? state.previous.compareTo(threshold) < 0 && price.compareTo(threshold) >= 0
                    : state.previous.compareTo(threshold) > 0 && price.compareTo(threshold) <= 0);
            boolean cooled = state.lastTriggered == null || !now.isBefore(state.lastTriggered.plus(cooldown));
            if (crossed && state.armed && cooled) {
                triggered.add(state.alert);
                state.armed = false;
                state.lastTriggered = now;
            }
            state.previous = price;
            state.sampleAt = snapshot.fetchedAt();
            changed = true;
        }
        if (changed) persist();
        return List.copyOf(triggered);
    }

    public synchronized Optional<PortfolioBalance> getPortfolio(UUID player) {
        Objects.requireNonNull(player);
        PlayerData data = players.get(player);
        return Optional.ofNullable(data == null ? null : data.portfolio);
    }

    public synchronized PortfolioBalance startPortfolio(UUID player) {
        PlayerData data = mutable(player);
        if (data.portfolio == null) {
            data.portfolio = new PortfolioBalance(new BigDecimal("10000.00"), new BigDecimal("0.00000000"));
            persist();
        }
        return data.portfolio;
    }

    public synchronized PortfolioBalance buy(UUID player, BigDecimal eurAmount, BigDecimal eurBtcPrice) {
        requireAmount(eurAmount, 2, "EUR amount");
        requirePositive(eurBtcPrice, "price");
        PlayerData data = tradingPlayer(player);
        PortfolioBalance old = data.portfolio;
        if (old.cashEur().compareTo(eurAmount) < 0) throw new IllegalArgumentException("Insufficient virtual EUR balance");
        BigDecimal coins = eurAmount.divide(eurBtcPrice, 8, RoundingMode.DOWN);
        if (coins.signum() == 0) throw new IllegalArgumentException("Trade must purchase at least one satoshi");
        data.portfolio = new PortfolioBalance(old.cashEur().subtract(eurAmount).setScale(2), old.bitcoin().add(coins).setScale(8));
        persist();
        return data.portfolio;
    }

    public synchronized PortfolioBalance sell(UUID player, BigDecimal btcAmount, BigDecimal eurBtcPrice) {
        requireAmount(btcAmount, 8, "BTC amount");
        requirePositive(eurBtcPrice, "price");
        PlayerData data = tradingPlayer(player);
        PortfolioBalance old = data.portfolio;
        if (old.bitcoin().compareTo(btcAmount) < 0) throw new IllegalArgumentException("Insufficient virtual BTC balance");
        BigDecimal proceeds = btcAmount.multiply(eurBtcPrice).setScale(2, RoundingMode.DOWN);
        if (proceeds.signum() == 0) throw new IllegalArgumentException("Trade must yield at least one cent");
        data.portfolio = new PortfolioBalance(old.cashEur().add(proceeds).setScale(2), old.bitcoin().subtract(btcAmount).setScale(8));
        persist();
        return data.portfolio;
    }

    private PlayerData tradingPlayer(UUID player) {
        requireOpen();
        Objects.requireNonNull(player);
        PlayerData data = players.get(player);
        if (data == null || data.portfolio == null) throw new IllegalArgumentException("Start your virtual portfolio first");
        return data;
    }

    static void requirePositive(BigDecimal amount, String name) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_VALUE) > 0
                || amount.precision() > 40 || amount.scale() > 18 || amount.scale() < -18) {
            throw new IllegalArgumentException(name + " must be a positive, bounded decimal number");
        }
    }

    private static void requireAmount(BigDecimal amount, int decimals, String name) {
        requirePositive(amount, name);
        if (amount.stripTrailingZeros().scale() > decimals) throw new IllegalArgumentException(name + " supports at most " + decimals + " decimals");
    }

    private PlayerData mutable(UUID player) {
        requireOpen();
        return players.computeIfAbsent(Objects.requireNonNull(player), ignored -> new PlayerData());
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Preferences service is closed");
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("Preferences file exceeds 16 MiB");
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            if (root.getInt("version") != 1) throw new IllegalArgumentException("Unsupported preferences version");
            JSONObject storedPlayers = root.getJSONObject("players");
            Map<UUID, PlayerData> restored = new HashMap<>();
            for (String key : storedPlayers.keySet()) {
                UUID player = parseUuid(key);
                JSONObject stored = storedPlayers.getJSONObject(key);
                PlayerData data = new PlayerData();
                JSONObject prefs = stored.getJSONObject("preferences");
                data.preferences = new Preferences(prefs.getBoolean("notifications"), prefs.getString("currency"),
                        DisplayMode.valueOf(prefs.getString("display")), prefs.getString("locale"));
                JSONArray alerts = stored.getJSONArray("alerts");
                if (alerts.length() > MAX_ALERTS) throw new IllegalArgumentException("Too many stored alerts");
                for (int i = 0; i < alerts.length(); i++) {
                    JSONObject storedAlert = alerts.getJSONObject(i);
                    PriceAlert alert = new PriceAlert(parseUuid(storedAlert.getString("id")),
                            AlertDirection.valueOf(storedAlert.getString("direction")),
                            new BigDecimal(storedAlert.getString("threshold")), storedAlert.getString("currency"));
                    AlertState state = new AlertState(alert);
                    state.armed = storedAlert.getBoolean("armed");
                    if (!storedAlert.isNull("previous")) {
                        state.previous = new BigDecimal(storedAlert.getString("previous"));
                        requirePositive(state.previous, "Previous price");
                    }
                    state.sampleAt = instant(storedAlert, "sampleAt");
                    state.lastTriggered = instant(storedAlert, "lastTriggered");
                    if ((state.previous == null) != (state.sampleAt == null)) throw new IllegalArgumentException("Incomplete alert state");
                    Instant latestPlausible = clock.instant().plus(Duration.ofMinutes(5));
                    if (state.sampleAt != null && state.sampleAt.isAfter(latestPlausible)
                            || state.lastTriggered != null && (state.sampleAt == null || state.lastTriggered.isAfter(latestPlausible))) {
                        throw new IllegalArgumentException("Implausible stored alert timestamp");
                    }
                    if (data.alerts.put(alert.id(), state) != null) throw new IllegalArgumentException("Duplicate alert id");
                }
                if (!stored.isNull("portfolio")) {
                    JSONObject portfolio = stored.getJSONObject("portfolio");
                    BigDecimal cash = new BigDecimal(portfolio.getString("cashEur"));
                    BigDecimal coins = new BigDecimal(portfolio.getString("bitcoin"));
                    if (cash.precision() > 40 || coins.precision() > 40 || cash.scale() < -18 || coins.scale() < -18) {
                        throw new IllegalArgumentException("Invalid stored balance");
                    }
                    data.portfolio = new PortfolioBalance(cash, coins);
                }
                if (restored.put(player, data) != null) throw new IllegalArgumentException("Duplicate player id");
            }
            players.putAll(restored);
        } catch (Exception ex) {
            Path backup = file.resolveSibling(file.getFileName() + ".invalid-" + UUID.randomUUID());
            try {
                Files.copy(file, backup);
                logger.warning("Invalid BitcoinPrice preferences preserved at " + backup + "; using defaults: " + ex.getClass().getSimpleName());
            } catch (IOException backupFailure) {
                persistenceBlocked = true;
                logger.log(Level.SEVERE, "Cannot preserve invalid preferences; original file will not be overwritten", backupFailure);
            }
        }
    }

    private static UUID parseUuid(String text) {
        UUID uuid = UUID.fromString(text);
        if (!uuid.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("Noncanonical UUID");
        return uuid;
    }

    private static Instant instant(JSONObject object, String key) {
        return object.isNull(key) ? null : Instant.parse(object.getString(key));
    }

    private void persist() {
        if (persistenceBlocked) return;
        JSONObject storedPlayers = new JSONObject();
        players.forEach((id, data) -> {
            Preferences prefs = data.preferences;
            JSONObject stored = new JSONObject().put("preferences", new JSONObject()
                    .put("notifications", prefs.notifications()).put("currency", prefs.currency())
                    .put("display", prefs.display().name()).put("locale", prefs.locale()));
            JSONArray alerts = new JSONArray();
            data.alerts.values().forEach(state -> alerts.put(new JSONObject()
                    .put("id", state.alert.id().toString()).put("direction", state.alert.direction().name())
                    .put("threshold", state.alert.threshold().toPlainString()).put("currency", state.alert.currency())
                    .put("armed", state.armed).put("previous", state.previous == null ? JSONObject.NULL : state.previous.toPlainString())
                    .put("sampleAt", state.sampleAt == null ? JSONObject.NULL : state.sampleAt.toString())
                    .put("lastTriggered", state.lastTriggered == null ? JSONObject.NULL : state.lastTriggered.toString())));
            stored.put("alerts", alerts);
            stored.put("portfolio", data.portfolio == null ? JSONObject.NULL : new JSONObject()
                    .put("cashEur", data.portfolio.cashEur().toPlainString()).put("bitcoin", data.portfolio.bitcoin().toPlainString()));
            storedPlayers.put(id.toString(), stored);
        });
        pending = new JSONObject().put("version", 1).put("players", storedPlayers).toString(2);
        if (!writeScheduled) {
            writeScheduled = true;
            writer.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            String payload;
            synchronized (this) {
                payload = pending;
                pending = null;
                if (payload == null) {
                    writeScheduled = false;
                    return;
                }
            }
            try {
                atomicWrite(payload);
                synchronized (this) { failedPayload = null; }
            } catch (IOException ex) {
                synchronized (this) { failedPayload = payload; }
                logger.log(Level.SEVERE, "Cannot save BitcoinPrice player preferences", ex);
            }
        }
    }

    private void atomicWrite(String payload) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            writer.shutdown();
        }
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Preferences writer did not finish within 30 seconds");
            }
            String retry;
            synchronized (this) { retry = failedPayload; }
            if (retry != null) {
                atomicWrite(retry);
                synchronized (this) { failedPayload = null; }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while saving preferences", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not flush player preferences", ex);
        }
    }

    private static final class PlayerData {
        Preferences preferences = Preferences.DEFAULTS;
        final Map<UUID, AlertState> alerts = new LinkedHashMap<>();
        PortfolioBalance portfolio;
    }

    private static final class AlertState {
        final PriceAlert alert;
        boolean armed = true;
        BigDecimal previous;
        Instant sampleAt;
        Instant lastTriggered;
        AlertState(PriceAlert alert) { this.alert = alert; }
    }
}
