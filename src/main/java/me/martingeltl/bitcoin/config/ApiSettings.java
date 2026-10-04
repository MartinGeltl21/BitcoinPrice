package me.martingeltl.bitcoin.config;

import java.net.URI;
import java.util.Locale;

public record ApiSettings(String url, int timeoutMillis, long cacheSeconds, long maxStaleSeconds,
                          long maxProviderAgeSeconds, long refreshCooldownSeconds, String demoApiKey) {
    public static final String DEFAULT_URL = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur,usd&precision=full&include_24hr_change=true&include_last_updated_at=true";

    public ApiSettings {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("API URL must be an HTTP(S) URL without credentials or fragment.");
        }
        if (timeoutMillis < 100 || timeoutMillis > 30000 || cacheSeconds < 1 || cacheSeconds > 3600
                || maxStaleSeconds < cacheSeconds || maxStaleSeconds > 86400
                || maxProviderAgeSeconds < 1 || maxProviderAgeSeconds > 86400
                || refreshCooldownSeconds < 1 || refreshCooldownSeconds > 3600) {
            throw new IllegalArgumentException("Invalid API timings.");
        }
        url = addFlag(url, "include_24hr_change");
        url = addFlag(url, "include_last_updated_at");
        demoApiKey = demoApiKey == null ? "" : demoApiKey.trim();
        if (demoApiKey.contains("\r") || demoApiKey.contains("\n")) throw new IllegalArgumentException("Invalid API key.");
    }

    private static String addFlag(String url, String flag) {
        if (url.matches(".*[?&]" + flag + "=[^&]*.*")) return url.replaceAll("([?&]" + flag + "=)[^&]*", "$1true");
        return url + (url.contains("?") ? "&" : "?") + flag + "=true";
    }

    @Override public String toString() {
        return "ApiSettings[timeoutMillis=" + timeoutMillis + ", cacheSeconds=" + cacheSeconds + "]";
    }
}
