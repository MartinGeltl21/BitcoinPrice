package org.bitcoinprice.commands;

import org.bitcoinprice.BitcoinPrice;
import org.bitcoinprice.model.PriceQuote;
import org.bitcoinprice.model.PriceSnapshot;
import org.bitcoinprice.model.CurrencyCatalog;
import org.bitcoinprice.preferences.AlertDirection;
import org.bitcoinprice.preferences.ActionbarMode;
import org.bitcoinprice.preferences.ActionbarContent;
import org.bitcoinprice.preferences.DisplayMode;
import org.bitcoinprice.preferences.PortfolioBalance;
import org.bitcoinprice.preferences.Preferences;
import org.bitcoinprice.presentation.MessageFormatter;
import org.bitcoinprice.presentation.Language;
import org.bitcoinprice.presentation.MessageException;
import org.bitcoinprice.presentation.ServiceDiagnostics;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Main command. Syntax is validated before HTTP requests or mutation. */
public final class BTCCommand implements CommandExecutor, TabCompleter {
    private final BitcoinPrice plugin;
    private final Set<UUID> pendingPlayers = new java.util.HashSet<>();
    private boolean pendingConsole;
    private static final int HELP_PAGE_SIZE = 8;
    private static final Map<String, Duration> PERIODS = Map.of("1h", Duration.ofHours(1), "6h", Duration.ofHours(6), "24h", Duration.ofHours(24), "7d", Duration.ofDays(7));
    public BTCCommand(BitcoinPrice plugin) { this.plugin = plugin; }
    private MessageFormatter messages() { return plugin.getMessages(); }
    private String text(CommandSender sender, String key, Object... args) { return messages().text(sender, key, args); }
    private static MessageException failure(String key, Object... args) { return new MessageException(key, args); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!allowed(sender)) return true;
        try {
            if (args.length == 0) { showPrice(sender, null); return true; }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "price" -> { exact(args, 1); showPrice(sender, null); }
                case "help" -> {
                    if (args.length > 2) throw failure("error.help.usage");
                    showHelp(sender, args.length == 1 ? "1" : args[1]);
                }
                case "interval" -> interval(sender, args);
                case "currency" -> currency(sender, args);
                case "player" -> targetSettings(sender, args);
                case "global" -> {
                    exact(args, 3); admin(sender);
                    if (args[1].equalsIgnoreCase("currency")) setGlobalCurrency(sender, args[2]);
                    else if (args[1].equalsIgnoreCase("language")) {
                        plugin.getConfigManager().setLanguage(args[2]);
                        plugin.getApiService().cachedQuote().ifPresentOrElse(plugin.getBoards()::update, plugin.getBoards()::showUnavailable);
                        messages().send(sender, text(sender, plugin.getConfigManager().isLanguageAutoDetect() ? "language.global.auto" : "language.global", plugin.getConfigManager().getLanguage().code()));
                    } else throw usage("/btc global currency <CODE|BOTH> | language de|en|AUTO");
                }
                case "refresh" -> {
                    exact(args, 1); admin(sender);
                    request(sender, true, quote -> {
                        if (quote.stale()) {
                            messages().send(sender, text(sender, "refresh.stale"));
                            return;
                        }
                        plugin.getScheduler().broadcastQuote(quote);
                        messages().send(sender, text(sender, "refresh.done"));
                    });
                }
                case "on", "off" -> notifications(sender, args, sub.equals("on"));
                case "settings" -> { exact(args, 1); settings(sender); }
                case "status" -> { exact(args, 1); status(sender); }
                case "locale" -> {
                    exact(args, 2); Player player = player(sender);
                    String locale = args[1].equalsIgnoreCase("DEFAULT") ? "DEFAULT" : args[1];
                    plugin.getPreferences().setLocale(player.getUniqueId(), locale);
                    messages().send(sender, text(sender, "locale.changed", locale));
                }
                case "language" -> {
                    exact(args, 2); Player player = player(sender);
                    plugin.getPreferences().setLanguage(player.getUniqueId(), args[1]);
                    plugin.getScheduler().resetActionbar(player);
                    messages().send(sender, text(sender, "language.changed", languageDescription(sender, player.getUniqueId())));
                }
                case "display" -> {
                    Player player = player(sender);
                    display(sender, player.getUniqueId(), player, text(sender, "label.display"), args, 1);
                }
                case "alert" -> alert(sender, args);
                case "sats" -> sats(sender, args);
                case "board" -> board(sender, args);
                case "history" -> history(sender, args);
                case "portfolio" -> portfolio(sender, args);
                default -> throw failure("error.command");
            }
        } catch (IllegalArgumentException ex) { messages().error(sender, ex); }
        return true;
    }
    private static IllegalArgumentException usage(String text) { return failure("usage", text); }
    private boolean allowed(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("bitcoinprice.use")) return true;
        messages().error(sender, text(sender, "error.permission")); return false;
    }
    private static void exact(String[] args, int size) { if (args.length != size) throw failure("error.arguments"); }
    private static Player player(CommandSender sender) {
        if (!(sender instanceof Player player)) throw failure("error.player");
        return player;
    }
    private static boolean isAdmin(CommandSender sender) { return sender.isOp() || sender.hasPermission("bitcoinprice.admin"); }
    private static void admin(CommandSender sender) { if (!isAdmin(sender)) throw failure("error.admin"); }
    private static String coinCurrency(String raw) {
        String currency = raw.toUpperCase(Locale.ROOT);
        if (!CurrencyCatalog.isSupported(currency)) throw failure("error.currency", String.join(", ", CurrencyCatalog.codes()));
        return currency;
    }
    public static BigDecimal positiveAmount(String raw) {
        if (raw.length() > 32 || !raw.matches("[0-9]+(?:\\.[0-9]+)?")) throw failure("error.amount.format");
        BigDecimal amount = new BigDecimal(raw);
        if (amount.signum() <= 0 || amount.scale() > 8 || amount.compareTo(new BigDecimal("1000000000000")) > 0)
            throw failure("error.amount.bounds");
        return amount;
    }
    public void showPrice(CommandSender sender, String currencyOverride) {
        if (!allowed(sender)) return;
        request(sender, false, quote -> sender.sendMessage(messages().quote("price", quote,
                currencyOverride == null ? messages().currency(sender) : currencyOverride, messages().locale(sender), messages().language(sender))));
    }
    /** Bound callback demand per user; all UI and balance changes stay on the main thread. */
    private void request(CommandSender sender, boolean force, Consumer<PriceQuote> success) {
        UUID id = sender instanceof Player player ? player.getUniqueId() : null;
        if (id == null ? pendingConsole : !pendingPlayers.add(id)) {
            messages().send(sender, text(sender, "request.pending")); return;
        }
        if (id == null) pendingConsole = true;
        CompletableFuture<PriceQuote> future = force ? plugin.getApiService().forceRefresh() : plugin.getApiService().fetchBitcoinPrice();
        future.whenComplete((quote, error) -> plugin.runSync(() -> {
            if (id == null) pendingConsole = false; else pendingPlayers.remove(id);
            if (sender instanceof Player player && !player.isOnline()) return;
            if (error != null) { messages().apiError(sender); return; }
            PriceQuote currentQuote = new PriceQuote(quote.snapshot(), quote.stale()
                    || !MessageFormatter.isFresh(quote.snapshot(), plugin.getConfigManager().getApiSettings(), Instant.now()));
            try { success.accept(currentQuote); }
            catch (IllegalArgumentException ex) { messages().error(sender, ex); }
        }));
    }
    private void interval(CommandSender sender, String[] args) {
        if (args.length == 1) { messages().send(sender, text(sender, "interval.current", plugin.getConfigManager().getPriceInterval())); return; }
        exact(args, 2); admin(sender);
        int value;
        try { value = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { throw usage("/btc interval 1|5|10|30|60"); }
        if (!plugin.getConfigManager().setPriceInterval(value)) throw failure("error.interval");
        plugin.getScheduler().updateSchedulerInterval(value);
        messages().send(sender, text(sender, "interval.changed", value));
    }
    private void currency(CommandSender sender, String[] args) {
        if (args.length == 1) { messages().send(sender, text(sender, "currency.current", messages().currency(sender))); return; }
        exact(args, 2);
        if (!(sender instanceof Player player)) { admin(sender); setGlobalCurrency(sender, args[1]); return; }
        String value = args[1].toUpperCase(Locale.ROOT);
        plugin.getPreferences().setCurrency(player.getUniqueId(), value);
        messages().send(sender, text(sender, "currency.changed", value));
    }
    private void setGlobalCurrency(CommandSender sender, String raw) {
        String value = raw.toUpperCase(Locale.ROOT);
        if (!plugin.getConfigManager().setPriceCurrency(value)) throw failure("error.currency", String.join(", ", CurrencyCatalog.selectionCodes(false)));
        messages().send(sender, text(sender, "currency.global", value));
    }
    private void notifications(CommandSender sender, String[] args, boolean on) {
        if (args.length == 2 && args[1].equalsIgnoreCase("all")) {
            admin(sender); plugin.getConfigManager().setBroadcastsEnabled(on);
            messages().send(sender, text(sender, "notifications.global", text(sender, on ? "on" : "off")));
        } else {
            exact(args, 1); Player player = player(sender);
            boolean changed = plugin.getPreferences().get(player.getUniqueId()).notifications() != on;
            plugin.getPreferences().setNotifications(player.getUniqueId(), on);
            if (changed) plugin.getScheduler().resetActionbar(player);
            messages().send(sender, text(sender, "notifications.changed", text(sender, on ? "on" : "off"))
                    + inactiveHint(sender, player.getUniqueId(), "/btc"));
        }
    }
    private void settings(CommandSender sender) {
        showSettings(sender, player(sender).getUniqueId(), text(sender, "label.settings"));
    }
    private void status(CommandSender sender) {
        var status = plugin.getApiService().status();
        String cache = status.quote().map(quote -> text(sender, "status.cache.details",
                MessageFormatter.age(quote.snapshot().fetchedAt(), messages().language(sender)),
                MessageFormatter.age(quote.snapshot().providerUpdatedAt(), messages().language(sender)),
                text(sender, quote.stale() ? "status.stale" : "status.fresh"))).orElse(text(sender, "status.cache.empty"));
        messages().send(sender, text(sender, "status.cache", cache));
        messages().send(sender, text(sender, "status.request", text(sender, status.requestInFlight() ? "running" : "idle"), status.retryAfterSeconds()));
        for (String line : ServiceDiagnostics.lines(status, messages().language(sender))) messages().send(sender, line);
        if (sender instanceof Player player) showSettings(sender, player.getUniqueId(), text(sender, "label.settings"));
    }
    private void showSettings(CommandSender sender, UUID id, String label) {
        Preferences preferences = plugin.getPreferences().get(id);
        String currency = preferences.currency().equals("DEFAULT") ? "DEFAULT → " + plugin.getConfigManager().getPriceCurrency() : preferences.currency();
        String locale = preferences.locale().equals("DEFAULT") ? "DEFAULT → " + plugin.getConfigManager().getLocale().toLanguageTag() : preferences.locale();
        String language = languageDescription(sender, id);
        messages().send(sender, text(sender, "settings.main", label, text(sender, preferences.notifications() ? "on" : "off"),
                preferences.display().name().toLowerCase(Locale.ROOT), currency, language, locale));
        messages().send(sender, text(sender, "settings.display", plugin.getConfigManager().getPriceInterval(),
                actionbarDescription(sender, preferences), text(sender, effectiveDisplay(preferences) ? "active" : "inactive")));
    }
    private String languageDescription(CommandSender sender, UUID id) {
        String selected = plugin.getPreferences().get(id).language();
        if (!selected.equals("AUTO") && !selected.equals("DEFAULT")) return selected;
        Player target = sender instanceof Player self && self.getUniqueId().equals(id) ? self : plugin.getServer().getPlayer(id);
        boolean automatic = selected.equals("AUTO") || plugin.getConfigManager().isLanguageAutoDetect();
        if (automatic && target == null) return text(sender, "language.auto.offline", selected);
        Language effective = Language.resolve(selected, target == null ? null : target.locale(), plugin.getConfigManager().getLanguage(),
                plugin.getConfigManager().isLanguageAutoDetect());
        return selected + " → " + effective.code();
    }
    private boolean effectiveDisplay(Preferences settings) {
        return settings.notifications() && settings.display() != DisplayMode.OFF
                && (settings.display() != DisplayMode.CHAT || plugin.getConfigManager().isBroadcastsEnabled());
    }
    private String inactiveHint(CommandSender sender, UUID id, String commandPrefix) {
        Preferences settings = plugin.getPreferences().get(id);
        if (!settings.notifications()) return text(sender, "hint.notifications", commandPrefix);
        if (settings.display() == DisplayMode.OFF) return text(sender, "hint.display", commandPrefix);
        if (settings.display() == DisplayMode.CHAT && !plugin.getConfigManager().isBroadcastsEnabled())
            return text(sender, "hint.global");
        return "";
    }
    private String actionbarDescription(CommandSender sender, Preferences settings) {
        int minutes = settings.actionbarIntervalMinutes();
        String timing = settings.actionbarMode() == ActionbarMode.CONTINUOUS ? text(sender, "actionbar.continuous")
                : text(sender, "actionbar.interval", minutes == 0 ? "DEFAULT → " + plugin.getConfigManager().getPriceInterval() : minutes);
        return timing + ", " + text(sender, "actionbar.content." + settings.actionbarContent().name().toLowerCase(Locale.ROOT));
    }
    private void display(CommandSender sender, UUID id, Player online, String label, String[] args, int offset) {
        String prefix = offset == 1 ? "/btc" : "/btc player " + args[1];
        String syntax = text(sender, "display.syntax", prefix);
        if (args.length <= offset) throw usage(syntax);
        DisplayMode selected;
        try { selected = DisplayMode.valueOf(args[offset].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw usage(syntax); }
        Preferences old = plugin.getPreferences().get(id);
        if (selected != DisplayMode.ACTIONBAR || args.length == offset + 1) {
            exact(args, offset + 1);
            plugin.getPreferences().setDisplay(id, selected);
        } else if (args[offset + 1].equalsIgnoreCase("content")) {
            exact(args, offset + 3);
            plugin.getPreferences().setActionbarContent(id, ActionbarContent.parse(args[offset + 2]));
        } else {
            if (args.length > offset + 3) throw usage(syntax);
            ActionbarMode mode;
            try { mode = ActionbarMode.valueOf(args[offset + 1].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { throw usage(syntax); }
            int minutes = old.actionbarIntervalMinutes();
            if (args.length == offset + 3) {
                if (mode != ActionbarMode.INTERVAL) throw failure("error.actionbar.minutes");
                String raw = args[offset + 2];
                try { minutes = raw.equalsIgnoreCase("DEFAULT") ? 0 : Integer.parseInt(raw); }
                catch (NumberFormatException ex) { throw usage(syntax); }
                if (!(raw.equalsIgnoreCase("DEFAULT") || Set.of(1, 5, 10, 30, 60).contains(minutes))) throw usage(syntax);
            }
            plugin.getPreferences().setActionbar(id, mode, minutes);
        }
        Preferences current = plugin.getPreferences().get(id);
        if (online != null && !old.equals(current)) plugin.getScheduler().resetActionbar(online);
        messages().send(sender, label + ": " + selected.name().toLowerCase(Locale.ROOT)
                + (selected == DisplayMode.ACTIONBAR ? " (" + actionbarDescription(sender, current) + ")" : "") + inactiveHint(sender, id, prefix));
    }
    private record Target(UUID id, String label, Player online) { }
    /** Resolve from live/local records only. Never use getOfflinePlayer(String), which can query Mojang synchronously. */
    private Target resolveTarget(CommandSender sender, String raw) {
        if (raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            UUID id = UUID.fromString(raw);
            Player online = plugin.getServer().getPlayer(id);
            if (online == null && sender instanceof Player self && self.getUniqueId().equals(id)) online = self;
            return new Target(id, online == null ? id.toString() : online.getName(), online);
        }
        if (!raw.matches("[a-zA-Z0-9_]{1,16}")) throw failure("error.target.format");
        Player online = plugin.getServer().getPlayerExact(raw);
        if (online == null && sender instanceof Player self && self.getName().equalsIgnoreCase(raw)) online = self;
        if (online != null) return new Target(online.getUniqueId(), online.getName(), online);
        OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(raw);
        if (cached != null && cached.getName() != null && cached.getName().equalsIgnoreCase(raw) && cached.hasPlayedBefore())
            return new Target(cached.getUniqueId(), cached.getName(), cached.getPlayer());
        for (OfflinePlayer known : plugin.getServer().getOfflinePlayers()) {
            if (known.getName() != null && known.getName().equalsIgnoreCase(raw))
                return new Target(known.getUniqueId(), known.getName(), known.getPlayer());
        }
        throw failure("error.target.unknown");
    }
    private void targetSettings(CommandSender sender, String[] args) {
        admin(sender);
        if (args.length < 3) throw failure("usage.player");
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("on") || action.equals("off") || action.equals("settings")) exact(args, 3);
        else if (action.equals("currency") || action.equals("locale") || action.equals("language")) exact(args, 4);
        else if (action.equals("display")) { if (args.length < 4 || args.length > 6) throw failure("usage.player.display"); }
        else throw failure("usage.player");
        Target target = resolveTarget(sender, args[1]);
        switch (action) {
            case "on", "off" -> {
                boolean enabled = action.equals("on");
                boolean changed = plugin.getPreferences().get(target.id()).notifications() != enabled;
                plugin.getPreferences().setNotifications(target.id(), enabled);
                if (changed && target.online() != null) plugin.getScheduler().resetActionbar(target.online());
                messages().send(sender, text(sender, "target.notifications", target.label(), text(sender, enabled ? "on" : "off"))
                        + inactiveHint(sender, target.id(), "/btc player " + args[1]));
            }
            case "currency" -> {
                String selected = args[3].toUpperCase(Locale.ROOT);
                plugin.getPreferences().setCurrency(target.id(), selected);
                messages().send(sender, text(sender, "target.currency", target.label(), selected));
            }
            case "settings" -> showSettings(sender, target.id(), target.label());
            case "display" -> display(sender, target.id(), target.online(), text(sender, "target.display", target.label()), args, 3);
            case "locale" -> {
                String selected = args[3].equalsIgnoreCase("DEFAULT") ? "DEFAULT" : args[3];
                plugin.getPreferences().setLocale(target.id(), selected);
                messages().send(sender, text(sender, "target.locale", target.label(), selected));
            }
            case "language" -> {
                plugin.getPreferences().setLanguage(target.id(), args[3]);
                if (target.online() != null) plugin.getScheduler().resetActionbar(target.online());
                messages().send(sender, text(sender, "language.target", target.label(), languageDescription(sender, target.id())));
            }
            default -> throw new IllegalStateException("Validated action was lost");
        }
    }
    private void alert(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            var alerts = plugin.getPreferences().listAlerts(player.getUniqueId());
            if (alerts.isEmpty()) messages().send(sender, text(sender, "alert.none"));
            alerts.forEach(alert -> messages().send(sender, alert.id().toString().substring(0, 8) + " | " + text(sender, alert.direction() == AlertDirection.ABOVE ? "above" : "below")
                    + " " + MessageFormatter.money(alert.threshold(), messages().locale(sender), alert.currency()) + " " + alert.currency()));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            if (!plugin.getPreferences().removeAlert(player.getUniqueId(), args[2])) throw failure("error.alert.missing");
            messages().send(sender, text(sender, "alert.removed"));
        } else {
            exact(args, 4);
            AlertDirection direction;
            try { direction = AlertDirection.valueOf(args[1].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { throw failure("usage.alert"); }
            var alert = plugin.getPreferences().addAlert(player.getUniqueId(), direction, positiveAmount(args[2]), coinCurrency(args[3]));
            messages().send(sender, text(sender, "alert.saved", alert.id().toString().substring(0, 8)));
        }
    }
    public static BigDecimal toSats(BigDecimal fiatAmount, BigDecimal price) {
        return fiatAmount.multiply(BigDecimal.valueOf(100_000_000L)).divide(price, 0, RoundingMode.DOWN);
    }
    private void sats(CommandSender sender, String[] args) {
        exact(args, 3); BigDecimal amount = positiveAmount(args[1]); String currency = coinCurrency(args[2]);
        request(sender, false, quote -> messages().send(sender, MessageFormatter.money(amount, messages().locale(sender), currency) + " " + currency
                + " ≈ " + MessageFormatter.number(toSats(amount, quote.snapshot().price(currency)), messages().locale(sender), 0)
                + " sats" + (quote.stale() ? text(sender, "sats.stale") : "") + ". 1 BTC = " + MessageFormatter.number(new BigDecimal("100000000"), messages().locale(sender), 0) + " sats."));
    }
    private void board(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            messages().send(sender, text(sender, "board.list", String.join(", ", plugin.getBoards().names()))); return;
        }
        admin(sender);
        if ((args.length == 2 || args.length == 3) && args[1].equalsIgnoreCase("create")) {
            plugin.getBoards().create(player(sender), args.length == 3 ? args[2] : "spawn");
            messages().send(sender, text(sender, "board.created"));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            if (!plugin.getBoards().remove(args[2])) throw failure("error.board.missing");
            messages().send(sender, text(sender, "board.removed"));
        } else throw failure("usage.board");
    }
    private void history(CommandSender sender, String[] args) {
        if (args.length > 2) throw usage("/btc history [1h|6h|24h|7d]");
        Duration period = PERIODS.get(args.length == 1 ? "24h" : args[1].toLowerCase(Locale.ROOT));
        if (period == null) throw usage("/btc history [1h|6h|24h|7d]");
        String selected = messages().currency(sender);
        List<PriceSnapshot> samples = plugin.getHistory().list(period);
        if (samples.isEmpty()) { messages().send(sender, text(sender, "history.empty")); return; }
        List<String> currencies = selected.equals("BOTH") ? List.of("EUR", "USD") : List.of(selected);
        for (String currency : currencies) {
            List<PriceSnapshot> available = samples.stream().filter(sample -> sample.supports(currency)).toList();
            if (available.isEmpty()) { messages().send(sender, text(sender, "history.currency.empty", currency)); continue; }
            BigDecimal low = available.stream().map(s -> s.price(currency)).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal high = available.stream().map(s -> s.price(currency)).max(BigDecimal::compareTo).orElseThrow();
            messages().send(sender, text(sender, "history.summary", currency, period.toHours(), available.size(), MessageFormatter.money(low, messages().locale(sender), currency), MessageFormatter.money(high, messages().locale(sender), currency)));
            messages().send(sender, MessageFormatter.chart(available, currency, period, Instant.now(), messages().language(sender)));
        }
        messages().send(sender, text(sender, "history.legend"));
    }
    private void portfolio(CommandSender sender, String[] args) {
        Player player = player(sender); UUID id = player.getUniqueId();
        if (args.length == 2 && args[1].equalsIgnoreCase("start")) {
            showBalance(sender, plugin.getPreferences().startPortfolio(id)); return;
        }
        if (args.length == 1) {
            showBalance(sender, plugin.getPreferences().getPortfolio(id).orElseThrow(() -> failure("portfolio.intro"))); return;
        }
        exact(args, 3); String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("buy") && !action.equals("sell")) throw failure("usage.portfolio");
        BigDecimal amount = positiveAmount(args[2]);
        if (plugin.getPreferences().getPortfolio(id).isEmpty()) throw failure("error.portfolio.start");
        request(sender, false, quote -> {
            if (quote.stale()) throw failure("error.portfolio.stale");
            PortfolioBalance balance = action.equals("buy") ? plugin.getPreferences().buy(id, amount, quote.snapshot().eur())
                    : plugin.getPreferences().sell(id, amount, quote.snapshot().eur());
            showBalance(sender, balance);
        });
    }
    private void showBalance(CommandSender sender, PortfolioBalance balance) {
        messages().send(sender, text(sender, "portfolio.balance", MessageFormatter.number(balance.cashEur(), messages().locale(sender), 2), MessageFormatter.number(balance.bitcoin(), messages().locale(sender), 8)));
    }
    public void showHelp(CommandSender sender) {
        showHelp(sender, 1);
    }
    public void showHelp(CommandSender sender, String rawPage) {
        if (!allowed(sender)) return;
        if (!rawPage.matches("[1-9][0-9]{0,8}")) { invalidHelpPage(sender); return; }
        showHelp(sender, Integer.parseInt(rawPage));
    }
    public int helpPageCount(CommandSender sender) {
        return (helpEntries(isAdmin(sender)).size() + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE;
    }
    private void invalidHelpPage(CommandSender sender) {
        messages().error(sender, text(sender, "help.invalid", helpPageCount(sender)));
    }
    public void showHelp(CommandSender sender, int page) {
        if (!allowed(sender)) return;
        List<String> entries = helpEntries(isAdmin(sender), messages().language(sender));
        int pages = helpPageCount(sender);
        if (page < 1 || page > pages) { invalidHelpPage(sender); return; }
        int first = (page - 1) * HELP_PAGE_SIZE;
        String section = first >= helpEntries(false).size() ? text(sender, "help.admin") : text(sender, "help.player");
        messages().send(sender, text(sender, "help.title", page, pages, section));
        for (String entry : entries.subList(first, Math.min(first + HELP_PAGE_SIZE, entries.size()))) messages().send(sender, entry);
        Component footer = messages().component(plugin.getConfigManager().getMessagePrefix()).append(Component.text(text(sender, "help.pages"), NamedTextColor.GRAY));
        if (page > 1) footer = footer.append(helpLink(sender, text(sender, "help.previous", page - 1), page - 1));
        if (page > 1 && page < pages) footer = footer.append(Component.text(" | ", NamedTextColor.GRAY));
        if (page < pages) footer = footer.append(helpLink(sender, text(sender, "help.next", page + 1), page + 1));
        sender.sendMessage(footer);
    }
    private Component helpLink(CommandSender sender, String label, int page) {
        return Component.text(label, NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/btc help " + page))
                .hoverEvent(HoverEvent.showText(Component.text(text(sender, "help.hover", page))));
    }
    /** Canonical complete help, displayed in pages of eight entries. */
    public static List<String> helpEntries(boolean administrator) { return helpEntries(administrator, Language.GERMAN); }
    public static List<String> helpEntries(boolean administrator, Language language) {
        List<String> entries = new ArrayList<>();
        for (int index = 1; index <= 37; index++)
            entries.add(language.text("help.player." + index, String.join(", ", CurrencyCatalog.codes())));
        if (administrator) for (int index = 1; index <= 19; index++)
            entries.add(language.text("help.admin." + index));
        return List.copyOf(entries);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("bitcoinprice.use")) return List.of();
        List<String> values = new ArrayList<>(); boolean admin = isAdmin(sender);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            values.addAll(List.of("price", "help", "interval", "currency", "on", "off", "settings", "status", "locale", "language", "display", "alert", "sats", "history", "portfolio", "board"));
            if (admin) values.addAll(List.of("global", "refresh", "player"));
        } else if (args.length == 2) {
            switch (sub) {
                case "help" -> { for (int page = 1; page <= helpPageCount(sender); page++) values.add(Integer.toString(page)); }
                case "currency" -> values.addAll(sender instanceof Player ? CurrencyCatalog.selectionCodes(true) : admin ? CurrencyCatalog.selectionCodes(false) : List.of());
                case "player" -> {
                    if (admin) {
                        plugin.getServer().getOnlinePlayers().forEach(online -> values.add(online.getName()));
                        if (sender instanceof Player self) values.add(self.getName());
                        for (OfflinePlayer known : plugin.getServer().getOfflinePlayers()) if (known.getName() != null) values.add(known.getName());
                    }
                }
                case "interval" -> { if (admin) values.addAll(List.of("1", "5", "10", "30", "60")); }
                case "global" -> { if (admin) values.addAll(List.of("currency", "language")); }
                case "on", "off" -> { if (admin) values.add("all"); }
                case "locale" -> values.addAll(List.of("de-DE", "en-US", "DEFAULT"));
                case "language" -> values.addAll(List.of("de", "en", "AUTO", "DEFAULT"));
                case "display" -> values.addAll(List.of("chat", "actionbar", "off"));
                case "alert" -> values.addAll(List.of("above", "below", "list", "remove"));
                case "history" -> values.addAll(List.of("1h", "6h", "24h", "7d"));
                case "portfolio" -> values.addAll(List.of("start", "buy", "sell"));
                case "board" -> { values.add("list"); if (admin) values.addAll(List.of("create", "remove")); }
                default -> { }
            }
        } else if (args.length == 3) {
            if (sub.equals("global") && admin && args[1].equalsIgnoreCase("currency")) values.addAll(CurrencyCatalog.selectionCodes(false));
            if (sub.equals("global") && admin && args[1].equalsIgnoreCase("language")) values.addAll(List.of("de", "en", "AUTO"));
            if (sub.equals("player") && admin) values.addAll(List.of("on", "off", "currency", "settings", "display", "locale", "language"));
            if (sub.equals("sats")) values.addAll(CurrencyCatalog.codes());
            if (sub.equals("display") && args[1].equalsIgnoreCase("actionbar")) values.addAll(List.of("continuous", "interval", "content"));
            if (sub.equals("board") && admin && args[1].equalsIgnoreCase("remove")) values.addAll(plugin.getBoards().names());
            if (sub.equals("alert") && args[1].equalsIgnoreCase("remove") && sender instanceof Player player)
                plugin.getPreferences().listAlerts(player.getUniqueId()).forEach(alert -> values.add(alert.id().toString().substring(0, 8)));
        } else if (args.length == 4) {
            if (sub.equals("display") && args[1].equalsIgnoreCase("actionbar") && args[2].equalsIgnoreCase("interval"))
                values.addAll(List.of("1", "5", "10", "30", "60", "DEFAULT"));
            if (sub.equals("display") && args[1].equalsIgnoreCase("actionbar") && args[2].equalsIgnoreCase("content"))
                values.addAll(List.of("price", "change", "full"));
            if (sub.equals("alert") && (args[1].equalsIgnoreCase("above") || args[1].equalsIgnoreCase("below"))) values.addAll(CurrencyCatalog.codes());
            if (sub.equals("player") && admin) switch (args[2].toLowerCase(Locale.ROOT)) {
                case "currency" -> values.addAll(CurrencyCatalog.selectionCodes(true));
                case "display" -> values.addAll(List.of("chat", "actionbar", "off"));
                case "locale" -> values.addAll(List.of("de-DE", "en-US", "DEFAULT"));
                case "language" -> values.addAll(List.of("de", "en", "AUTO", "DEFAULT"));
                default -> { }
            }
        } else if (args.length == 5 && sub.equals("player") && admin && args[2].equalsIgnoreCase("display") && args[3].equalsIgnoreCase("actionbar")) {
            values.addAll(List.of("continuous", "interval", "content"));
        } else if (args.length == 6 && sub.equals("player") && admin && args[2].equalsIgnoreCase("display")
                && args[3].equalsIgnoreCase("actionbar") && args[4].equalsIgnoreCase("interval")) {
            values.addAll(List.of("1", "5", "10", "30", "60", "DEFAULT"));
        } else if (args.length == 6 && sub.equals("player") && admin && args[2].equalsIgnoreCase("display")
                && args[3].equalsIgnoreCase("actionbar") && args[4].equalsIgnoreCase("content")) {
            values.addAll(List.of("price", "change", "full"));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().distinct().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
