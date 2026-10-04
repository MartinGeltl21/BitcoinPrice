package org.bitcoinprice.config;

import org.bitcoinprice.BitcoinPrice;
import org.bitcoinprice.model.CurrencyCatalog;
import org.bukkit.configuration.file.FileConfiguration;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Validates both startup config and command mutations. Existing keys remain compatible. */
public final class ConfigManager {
    private final BitcoinPrice plugin;
    private int priceInterval;
    private String priceCurrency;
    private ApiSettings apiSettings;
    private boolean broadcastsEnabled;
    private Locale locale;
    private int monitorSeconds;
    private int actionbarSeconds;
    private int historyHours;
    private int alertCooldownSeconds;
    private String messagePrefix;
    private String priceColor;
    private String apiErrorMessage;

    public ConfigManager(BitcoinPrice plugin) { this.plugin = plugin; loadConfig(); }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        priceInterval = config.getInt("price-interval", 10);
        Object rawInterval = config.get("price-interval");
        if ((rawInterval != null && (!(rawInterval instanceof Number number) || number.doubleValue() != number.intValue()))
                || !validInterval(priceInterval)) { warn("price-interval", "10"); priceInterval = 10; }
        priceCurrency = config.getString("price-currency", "EUR").toUpperCase(Locale.ROOT);
        if (!CurrencyCatalog.isSelection(priceCurrency, false)) { warn("price-currency", "EUR"); priceCurrency = "EUR"; }
        broadcastsEnabled = config.getBoolean("broadcasts-enabled", true);
        Object rawBroadcasts = config.get("broadcasts-enabled");
        if (rawBroadcasts != null && !(rawBroadcasts instanceof Boolean)) { warn("broadcasts-enabled", "true"); broadcastsEnabled = true; }
        String localeTag = config.getString("locale", "de-DE");
        try {
            locale = new Locale.Builder().setLanguageTag(localeTag).build();
            if (locale.getLanguage().isBlank()) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { warn("locale", "de-DE"); locale = Locale.GERMANY; }
        monitorSeconds = bounded("monitor-seconds", 60, 30, 3600);
        actionbarSeconds = bounded("actionbar-seconds", 5, 1, 60);
        historyHours = bounded("history-hours", 168, 1, 168);
        alertCooldownSeconds = bounded("alert-cooldown-seconds", 300, 1, 86400);
        int timeout = bounded("api.timeout", 10000, 100, 30000);
        int cache = bounded("api.cache-seconds", 60, 10, 3600);
        int stale = bounded("api.max-stale-seconds", 300, cache, 86400);
        int providerAge = bounded("api.max-provider-age-seconds", 900, 30, 86400);
        int refreshCooldown = bounded("api.refresh-cooldown-seconds", 30, 10, 3600);
        String url = config.getString("api.url", ApiSettings.DEFAULT_URL);
        try {
            URI uri = URI.create(url);
            boolean loopback = Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme()) && loopback)) throw new IllegalArgumentException();
            new ApiSettings(url, timeout, cache, stale, providerAge, refreshCooldown, "");
        } catch (RuntimeException invalid) { warn("api.url", "secure default URL"); url = ApiSettings.DEFAULT_URL; }
        String key = config.getString("api.demo-api-key", "");
        String environmentKey = System.getenv("COINGECKO_DEMO_API_KEY");
        if (environmentKey != null && !environmentKey.isBlank()) key = environmentKey;
        if (key.contains("\r") || key.contains("\n")) { warn("api.demo-api-key", "empty key"); key = ""; }
        apiSettings = new ApiSettings(url, timeout, cache, stale, providerAge, refreshCooldown, key);
        messagePrefix = colors(config.getString("messages.prefix", "&6[BitcoinPrice] &r"));
        priceColor = colors(config.getString("messages.price-color", "&6"));
        apiErrorMessage = colors(config.getString("messages.api-error", "&cBitcoin-Preis momentan nicht verfügbar. Bitte später erneut versuchen."));
    }

    private int bounded(String key, int fallback, int min, int max) {
        Object raw = plugin.getConfig().get(key);
        if (raw != null && (!(raw instanceof Number number) || number.doubleValue() != number.intValue())) {
            int safe = Math.clamp(fallback, min, max); warn(key, Integer.toString(safe)); return safe;
        }
        int requested = plugin.getConfig().getInt(key, fallback);
        int safe = Math.clamp(requested, min, max);
        if (safe != requested) warn(key, Integer.toString(safe));
        return safe;
    }

    private void warn(String key, String replacement) {
        plugin.getLogger().warning("Invalid config value for " + key + "; using " + replacement + ".");
    }

    // Adventure's formatter decodes the configured legacy colors at the display boundary.
    private static String colors(String value) { return value; }
    private static boolean validInterval(int minutes) { return Set.of(1, 5, 10, 30, 60).contains(minutes); }

    public void saveConfig() {
        plugin.getConfig().set("price-interval", priceInterval);
        plugin.getConfig().set("price-currency", priceCurrency);
        plugin.getConfig().set("broadcasts-enabled", broadcastsEnabled);
        plugin.saveConfig();
    }

    public boolean setPriceInterval(int minutes) {
        if (!validInterval(minutes)) return false;
        priceInterval = minutes; saveConfig(); return true;
    }
    public boolean setPriceCurrency(String currency) {
        if (currency == null) return false;
        String normalized = currency.toUpperCase(Locale.ROOT);
        if (!CurrencyCatalog.isSelection(normalized, false)) return false;
        priceCurrency = normalized; saveConfig(); return true;
    }
    public void setBroadcastsEnabled(boolean enabled) { broadcastsEnabled = enabled; saveConfig(); }
    public int getPriceInterval() { return priceInterval; }
    public String getPriceCurrency() { return priceCurrency; }
    public ApiSettings getApiSettings() { return apiSettings; }
    public String getApiUrl() { return apiSettings.url(); }
    public int getApiTimeout() { return apiSettings.timeoutMillis(); }
    public boolean isBroadcastsEnabled() { return broadcastsEnabled; }
    public Locale getLocale() { return locale; }
    public int getMonitorSeconds() { return monitorSeconds; }
    public int getActionbarSeconds() { return actionbarSeconds; }
    public int getHistoryHours() { return historyHours; }
    public int getAlertCooldownSeconds() { return alertCooldownSeconds; }
    public String getMessagePrefix() { return messagePrefix; }
    public String getPriceColor() { return priceColor; }
    public String getApiErrorMessage() { return apiErrorMessage; }

    /** Additional message keys can be supplied by administrators without a code change. */
    public String getTemplate(String key) {
        String fallback = switch (key) {
            case "price" -> "&6BTC: {price} {currency} &7| 24h {change} | {age} | Quelle {provider_age} {status}";
            case "actionbar" -> "&6BTC {price} {currency} &7| 24h {change} | {age} {status}";
            case "alert" -> "&eBTC-Alarm: {price} {currency} | {change} | {provider_age}";
            case "board" -> "&6Bitcoin\n&f{price} {currency}\n&724h {change}\n{age} | Quelle {provider_age} {status}";
            default -> "";
        };
        return colors(plugin.getConfig().getString("messages." + key, fallback));
    }
}
