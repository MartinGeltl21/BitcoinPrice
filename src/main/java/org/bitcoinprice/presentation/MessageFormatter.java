package org.bitcoinprice.presentation;

import org.bitcoinprice.BitcoinPrice;
import org.bitcoinprice.config.ApiSettings;
import org.bitcoinprice.model.PriceQuote;
import org.bitcoinprice.model.PriceSnapshot;
import org.bitcoinprice.model.CurrencyCatalog;
import org.bitcoinprice.preferences.PriceAlert;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Currency;

/** Shared Adventure formatting for commands, chat, actionbars and displays. */
public final class MessageFormatter {
    private final BitcoinPrice plugin;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    public MessageFormatter(BitcoinPrice plugin) { this.plugin = plugin; }
    public Component component(String text) { return LEGACY.deserialize(text.replace('§', '&')); }
    public void send(CommandSender sender, String text) {
        sender.sendMessage(component(plugin.getConfigManager().getMessagePrefix() + text));
    }
    public void error(CommandSender sender, String text) { send(sender, "&c" + text); }
    public void error(CommandSender sender, IllegalArgumentException error) {
        error(sender, error instanceof MessageException translated ? translated.text(language(sender)) : error.getMessage());
    }
    public String text(CommandSender sender, String key, Object... arguments) { return language(sender).text(key, arguments); }
    public Language language(CommandSender sender) {
        if (sender instanceof Player player) {
            String selected = plugin.getPreferences().get(player.getUniqueId()).language();
            return Language.resolve(selected, player.locale(), plugin.getConfigManager().getLanguage(),
                    plugin.getConfigManager().isLanguageAutoDetect());
        }
        return plugin.getConfigManager().getLanguage();
    }
    public void apiError(CommandSender sender) { send(sender, plugin.getConfigManager().getTemplate("api-error", language(sender))); }
    public Locale locale(CommandSender sender) {
        if (sender instanceof Player player) {
            String language = plugin.getPreferences().get(player.getUniqueId()).locale();
            if (!language.equals("DEFAULT")) return Locale.forLanguageTag(language);
        }
        return plugin.getConfigManager().getLocale();
    }
    public String currency(CommandSender sender) {
        if (sender instanceof Player player) {
            String currency = plugin.getPreferences().get(player.getUniqueId()).currency();
            if (!currency.equals("DEFAULT")) return currency;
        }
        return plugin.getConfigManager().getPriceCurrency();
    }
    public static String number(BigDecimal value, Locale locale, int decimals) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        return format.format(value);
    }
    public static String money(BigDecimal value, Locale locale, String currency) {
        if (!CurrencyCatalog.isSupported(currency)) throw new MessageException("error.currency", String.join(", ", CurrencyCatalog.codes()));
        return number(value, locale, Math.max(0, Currency.getInstance(currency.toUpperCase(Locale.ROOT)).getDefaultFractionDigits()));
    }
    /** A missing optional provider currency remains explicit, never falls back to another currency's price. */
    public static String selectedPrice(PriceSnapshot snapshot, String currency, Locale locale) {
        return selectedPrice(snapshot, currency, locale, Language.GERMAN);
    }
    public static String selectedPrice(PriceSnapshot snapshot, String currency, Locale locale, Language language) {
        return snapshot.supports(currency) ? money(snapshot.price(currency), locale, currency) : language.text("unavailable");
    }
    public static String age(Instant timestamp) {
        return age(timestamp, Language.GERMAN);
    }
    public static String age(Instant timestamp, Language language) {
        if (timestamp == null) return language.text("unknown");
        long seconds = Math.max(0, Duration.between(timestamp, Instant.now()).getSeconds());
        return seconds < 60 ? seconds + "s" : seconds < 3600 ? seconds / 60 + "min" : seconds / 3600 + "h";
    }
    public static boolean isFresh(PriceSnapshot snapshot, ApiSettings settings, Instant now) {
        return snapshot.providerUpdatedAt() != null
                && !snapshot.providerUpdatedAt().isAfter(now.plusSeconds(60))
                && !snapshot.providerUpdatedAt().isBefore(now.minusSeconds(settings.maxProviderAgeSeconds()))
                && !snapshot.fetchedAt().isBefore(now.minusSeconds(settings.cacheSeconds()));
    }
    public Component quote(String template, PriceQuote quote, String currency, Locale locale) {
        return quote(template, quote, currency, locale, plugin.getConfigManager().getLanguage());
    }
    public Component quote(String template, PriceQuote quote, String currency, Locale locale, Language language) {
        return format(template, quote, currency, locale, language, null);
    }
    public Component alert(PriceQuote quote, PriceAlert alert, Locale locale) {
        return alert(quote, alert, locale, plugin.getConfigManager().getLanguage());
    }
    public Component alert(PriceQuote quote, PriceAlert alert, Locale locale, Language language) {
        return format("alert", quote, alert.currency(), locale, language, alert);
    }
    public Component actionbar(Player player, PriceQuote quote) {
        return quote(plugin.getPreferences().get(player.getUniqueId()).actionbarContent().template(), quote,
                currency(player), locale(player), language(player));
    }
    private Component format(String template, PriceQuote quote, String currency, Locale locale, Language language, PriceAlert alert) {
        String text = render(plugin.getConfigManager().getTemplate(template, language), quote, currency, locale, language,
                plugin.getConfigManager().getPriceColor(), plugin.getConfigManager().getApiSettings(), Instant.now(), alert);
        return component((template.equals("board") || template.startsWith("actionbar") ? "" : plugin.getConfigManager().getMessagePrefix()) + text);
    }
    /** Renders the same quote for chat, actionbar, boards and alerts using an explicit text language. */
    public static String render(String template, PriceQuote quote, String currency, Locale locale, Language language,
                                String color, ApiSettings settings, Instant now, PriceAlert alert) {
        PriceSnapshot snapshot = quote.snapshot();
        currency = currency.toUpperCase(Locale.ROOT);
        if (!CurrencyCatalog.isSelection(currency, false)) throw new MessageException("error.currency", String.join(", ", CurrencyCatalog.selectionCodes(false)));
        String eur = color + selectedPrice(snapshot, "EUR", locale, language) + "&r";
        String usd = color + selectedPrice(snapshot, "USD", locale, language) + "&r";
        String price = currency.equals("BOTH") ? eur + " EUR / " + usd + " USD" : color + selectedPrice(snapshot, currency, locale, language) + "&r";
        String change = currency.equals("BOTH")
                ? "EUR " + change(snapshot.supports("EUR") ? snapshot.change24h("EUR") : null, locale, language)
                    + " / USD " + change(snapshot.supports("USD") ? snapshot.change24h("USD") : null, locale, language)
                : change(snapshot.supports(currency) ? snapshot.change24h(currency) : null, locale, language);
        boolean fresh = !quote.stale() && isFresh(snapshot, settings, now);
        String status = snapshot.providerUpdatedAt() == null ? "&e" + language.text("status.unknown")
                : fresh ? "&a" + language.text("status.fresh") : "&c" + language.text("status.stale");
        String text = template
                .replace("{eur}", eur).replace("{usd}", usd).replace("{price}", price)
                .replace("{currency}", currency.equals("BOTH") ? "" : currency).replace("{change}", change)
                .replace("{age}", age(snapshot.fetchedAt(), language)).replace("{provider_age}", age(snapshot.providerUpdatedAt(), language))
                .replace("{status}", status).replace("{warning}", fresh ? "" : " &7| " + status)
                .replace("{threshold}", alert == null ? "" : money(alert.threshold(), locale, alert.currency()))
                .replace("{direction}", alert == null ? "" : language.text(alert.direction().name().equals("ABOVE") ? "above" : "below"))
                .replace("{id}", alert == null ? "" : alert.id().toString().substring(0, 8));
        for (String code : CurrencyCatalog.codes())
            text = text.replace("{" + code.toLowerCase(Locale.ROOT) + "}", color + selectedPrice(snapshot, code, locale, language) + "&r");
        return text;
    }
    private static String change(BigDecimal value, Locale locale, Language language) {
        return value == null ? language.text("unknown") : (value.signum() >= 0 ? "+" : "") + number(value, locale, 2) + "%";
    }
    /** A bounded chart with uniformly spaced bins; gaps stay visible rather than inventing prices. */
    public static String chart(List<PriceSnapshot> samples, String currency, Duration period, Instant now) {
        return chart(samples, currency, period, now, Language.GERMAN);
    }
    public static String chart(List<PriceSnapshot> samples, String currency, Duration period, Instant now, Language language) {
        if (samples.isEmpty()) return language.text("history.empty.chart");
        int width = 32;
        BigDecimal[] bins = new BigDecimal[width];
        long total = period.toMillis();
        Instant start = now.minus(period);
        BigDecimal min = null, max = null;
        for (PriceSnapshot sample : samples) {
            if (!sample.supports(currency)) continue;
            long offset = Duration.between(start, sample.fetchedAt()).toMillis();
            if (offset < 0 || offset > total) continue;
            int index = (int) Math.min(width - 1, offset * width / total);
            BigDecimal price = sample.price(currency);
            bins[index] = price;
            min = min == null ? price : min.min(price);
            max = max == null ? price : max.max(price);
        }
        String levels = "_.,:;ox#";
        StringBuilder result = new StringBuilder(width);
        for (BigDecimal value : bins) {
            if (value == null) result.append(' ');
            else if (min.compareTo(max) == 0) result.append('-');
            else result.append(levels.charAt(value.subtract(min).multiply(BigDecimal.valueOf(7))
                    .divide(max.subtract(min), 0, java.math.RoundingMode.HALF_UP).intValue()));
        }
        return "[" + result + "]";
    }
}
