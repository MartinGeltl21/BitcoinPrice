package org.bitcoinprice.commands;

import org.bitcoinprice.BitcoinPrice;
import org.bitcoinprice.model.PriceQuote;
import org.bitcoinprice.model.PriceSnapshot;
import org.bitcoinprice.model.CurrencyCatalog;
import org.bitcoinprice.preferences.AlertDirection;
import org.bitcoinprice.preferences.DisplayMode;
import org.bitcoinprice.preferences.PortfolioBalance;
import org.bitcoinprice.preferences.Preferences;
import org.bitcoinprice.presentation.MessageFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
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
    private static final Map<String, Duration> PERIODS = Map.of("1h", Duration.ofHours(1), "6h", Duration.ofHours(6), "24h", Duration.ofHours(24), "7d", Duration.ofDays(7));
    public BTCCommand(BitcoinPrice plugin) { this.plugin = plugin; }
    private MessageFormatter messages() { return plugin.getMessages(); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!allowed(sender)) return true;
        try {
            if (args.length == 0) { showPrice(sender, null); return true; }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "price" -> { exact(args, 1); showPrice(sender, null); }
                case "help" -> { exact(args, 1); showHelp(sender); }
                case "interval" -> interval(sender, args);
                case "currency" -> currency(sender, args);
                case "player" -> targetSettings(sender, args);
                case "global" -> {
                    exact(args, 3); admin(sender);
                    if (!args[1].equalsIgnoreCase("currency")) throw usage("/btc global currency <Währung|BOTH>");
                    setGlobalCurrency(sender, args[2]);
                }
                case "refresh" -> {
                    exact(args, 1); admin(sender);
                    request(sender, true, quote -> {
                        plugin.getScheduler().broadcastQuote(quote);
                        messages().send(sender, "Kurs aktualisiert; globale Chatnachrichten beachten die gespeicherten Einstellungen.");
                    });
                }
                case "on", "off" -> notifications(sender, args, sub.equals("on"));
                case "settings" -> { exact(args, 1); settings(sender); }
                case "locale" -> {
                    exact(args, 2); Player player = player(sender);
                    String locale = args[1].equalsIgnoreCase("DEFAULT") ? "DEFAULT" : args[1];
                    plugin.getPreferences().setLocale(player.getUniqueId(), locale);
                    messages().send(sender, "Sprache/Zahlenformat: " + locale);
                }
                case "display" -> {
                    exact(args, 2); Player player = player(sender);
                    DisplayMode display;
                    try { display = DisplayMode.valueOf(args[1].toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException ex) { throw usage("/btc display chat|actionbar|off"); }
                    plugin.getPreferences().setDisplay(player.getUniqueId(), display);
                    if (display != DisplayMode.ACTIONBAR) player.sendActionBar(net.kyori.adventure.text.Component.empty());
                    messages().send(sender, "Anzeige: " + display.name().toLowerCase(Locale.ROOT));
                }
                case "alert" -> alert(sender, args);
                case "sats" -> sats(sender, args);
                case "board" -> board(sender, args);
                case "history" -> history(sender, args);
                case "portfolio" -> portfolio(sender, args);
                default -> throw usage("Unbekannter Befehl. /btc help zeigt alle Befehle.");
            }
        } catch (IllegalArgumentException ex) { messages().error(sender, ex.getMessage()); }
        return true;
    }
    private static IllegalArgumentException usage(String text) { return new IllegalArgumentException(text); }
    private boolean allowed(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("bitcoinprice.use")) return true;
        messages().error(sender, "Dafür fehlt dir bitcoinprice.use."); return false;
    }
    private static void exact(String[] args, int size) { if (args.length != size) throw usage("Ungültige Argumente. Hilfe: /btc help"); }
    private static Player player(CommandSender sender) {
        if (!(sender instanceof Player player)) throw usage("Dieser Befehl benötigt einen Spieler.");
        return player;
    }
    private static boolean isAdmin(CommandSender sender) { return sender.isOp() || sender.hasPermission("bitcoinprice.admin"); }
    private static void admin(CommandSender sender) { if (!isAdmin(sender)) throw usage("Dieser Befehl benötigt OP oder bitcoinprice.admin."); }
    private static String coinCurrency(String raw) {
        String currency = raw.toUpperCase(Locale.ROOT);
        if (!CurrencyCatalog.isSupported(currency)) throw usage("Erlaubte Währungen: " + String.join(", ", CurrencyCatalog.codes()) + ".");
        return currency;
    }
    public static BigDecimal positiveAmount(String raw) {
        if (raw.length() > 32 || !raw.matches("[0-9]+(?:\\.[0-9]+)?")) throw usage("Bitte eine positive Zahl mit Dezimalpunkt angeben (z. B. 10.50).");
        BigDecimal amount = new BigDecimal(raw);
        if (amount.signum() <= 0 || amount.scale() > 8 || amount.compareTo(new BigDecimal("1000000000000")) > 0)
            throw usage("Betrag muss positiv, höchstens 1 Billion und auf 8 Nachkommastellen begrenzt sein.");
        return amount;
    }
    public void showPrice(CommandSender sender, String currencyOverride) {
        if (!allowed(sender)) return;
        request(sender, false, quote -> sender.sendMessage(messages().quote("price", quote,
                currencyOverride == null ? messages().currency(sender) : currencyOverride, messages().locale(sender))));
    }
    /** Bound callback demand per user; all UI and balance changes stay on the main thread. */
    private void request(CommandSender sender, boolean force, Consumer<PriceQuote> success) {
        UUID id = sender instanceof Player player ? player.getUniqueId() : null;
        if (id == null ? pendingConsole : !pendingPlayers.add(id)) {
            messages().send(sender, "Eine Kursabfrage läuft bereits."); return;
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
            catch (IllegalArgumentException ex) { messages().error(sender, ex.getMessage()); }
        }));
    }
    private void interval(CommandSender sender, String[] args) {
        if (args.length == 1) { messages().send(sender, "Chat-Intervall: " + plugin.getConfigManager().getPriceInterval() + " Minuten"); return; }
        exact(args, 2); admin(sender);
        int value;
        try { value = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { throw usage("/btc interval 1|5|10|30|60"); }
        if (!plugin.getConfigManager().setPriceInterval(value)) throw usage("Erlaubte Intervalle: 1, 5, 10, 30, 60 Minuten.");
        plugin.getScheduler().updateSchedulerInterval(value);
        messages().send(sender, "Chat-Intervall: " + value + " Minuten");
    }
    private void currency(CommandSender sender, String[] args) {
        if (args.length == 1) { messages().send(sender, "Währung: " + messages().currency(sender)); return; }
        exact(args, 2);
        if (!(sender instanceof Player player)) { admin(sender); setGlobalCurrency(sender, args[1]); return; }
        String value = args[1].toUpperCase(Locale.ROOT);
        plugin.getPreferences().setCurrency(player.getUniqueId(), value);
        messages().send(sender, "Deine Währung: " + value);
    }
    private void setGlobalCurrency(CommandSender sender, String raw) {
        String value = raw.toUpperCase(Locale.ROOT);
        if (!plugin.getConfigManager().setPriceCurrency(value)) throw usage("Erlaubte globale Währungen: " + String.join(", ", CurrencyCatalog.selectionCodes(false)) + ".");
        messages().send(sender, "Globale Währung: " + value);
    }
    private void notifications(CommandSender sender, String[] args, boolean on) {
        if (args.length == 2 && args[1].equalsIgnoreCase("all")) {
            admin(sender); plugin.getConfigManager().setBroadcastsEnabled(on);
            messages().send(sender, "Globale Chatnachrichten: " + (on ? "an" : "aus"));
        } else {
            exact(args, 1); Player player = player(sender);
            plugin.getPreferences().setNotifications(player.getUniqueId(), on);
            if (!on) player.sendActionBar(net.kyori.adventure.text.Component.empty());
            messages().send(sender, "Deine Kursnachrichten: " + (on ? "an" : "aus") + ". Preisalarme verwaltest du separat mit /btc alert.");
        }
    }
    private void settings(CommandSender sender) {
        showSettings(sender, player(sender).getUniqueId(), "Deine Einstellungen");
    }
    private void showSettings(CommandSender sender, UUID id, String label) {
        Preferences preferences = plugin.getPreferences().get(id);
        messages().send(sender, label + ": Kursnachrichten: " + preferences.notifications() + " | Anzeige: " + preferences.display()
                + " | Währung: " + preferences.currency() + " | Sprache: " + preferences.locale());
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
        if (!raw.matches("[a-zA-Z0-9_]{1,16}")) throw usage("Bitte einen exakten Spielernamen oder eine vollständige UUID angeben.");
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
        throw usage("Spielername ist auf diesem Server unbekannt. Bitte eine vollständige UUID verwenden; es erfolgt keine externe Profilabfrage.");
    }
    private void targetSettings(CommandSender sender, String[] args) {
        admin(sender);
        if (args.length < 3) throw usage("/btc player <Name|UUID> on|off|settings|currency <Währung|DEFAULT>");
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("on") || action.equals("off") || action.equals("settings")) exact(args, 3);
        else if (action.equals("currency") || action.equals("display") || action.equals("locale")) exact(args, 4);
        else throw usage("/btc player <Name|UUID> on|off|settings|currency <Währung|DEFAULT>");
        Target target = resolveTarget(sender, args[1]);
        switch (action) {
            case "on", "off" -> {
                boolean enabled = action.equals("on");
                plugin.getPreferences().setNotifications(target.id(), enabled);
                if (!enabled && target.online() != null && target.online().isOnline()) target.online().sendActionBar(net.kyori.adventure.text.Component.empty());
                messages().send(sender, target.label() + ": Kursnachrichten " + (enabled ? "an" : "aus") + ". Preisalarme bleiben separat verwaltbar.");
            }
            case "currency" -> {
                String selected = args[3].toUpperCase(Locale.ROOT);
                plugin.getPreferences().setCurrency(target.id(), selected);
                messages().send(sender, target.label() + ": Währung " + selected);
            }
            case "settings" -> showSettings(sender, target.id(), target.label());
            case "display" -> {
                DisplayMode mode;
                try { mode = DisplayMode.valueOf(args[3].toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ex) { throw usage("/btc player <Name|UUID> display chat|actionbar|off"); }
                plugin.getPreferences().setDisplay(target.id(), mode);
                if (mode != DisplayMode.ACTIONBAR && target.online() != null && target.online().isOnline()) target.online().sendActionBar(net.kyori.adventure.text.Component.empty());
                messages().send(sender, target.label() + ": Anzeige " + mode.name().toLowerCase(Locale.ROOT));
            }
            case "locale" -> {
                String selected = args[3].equalsIgnoreCase("DEFAULT") ? "DEFAULT" : args[3];
                plugin.getPreferences().setLocale(target.id(), selected);
                messages().send(sender, target.label() + ": Sprache/Zahlenformat " + selected);
            }
            default -> throw new IllegalStateException("Validated action was lost");
        }
    }
    private void alert(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            var alerts = plugin.getPreferences().listAlerts(player.getUniqueId());
            if (alerts.isEmpty()) messages().send(sender, "Keine Preisalarme. /btc alert above|below <Betrag> <Währung>");
            alerts.forEach(alert -> messages().send(sender, alert.id().toString().substring(0, 8) + " | " + alert.direction()
                    + " " + MessageFormatter.money(alert.threshold(), messages().locale(sender), alert.currency()) + " " + alert.currency()));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            if (!plugin.getPreferences().removeAlert(player.getUniqueId(), args[2])) throw usage("Alarm nicht gefunden oder ID nicht eindeutig.");
            messages().send(sender, "Preisalarm entfernt.");
        } else {
            exact(args, 4);
            AlertDirection direction;
            try { direction = AlertDirection.valueOf(args[1].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { throw usage("/btc alert above|below <Betrag> <Währung>"); }
            var alert = plugin.getPreferences().addAlert(player.getUniqueId(), direction, positiveAmount(args[2]), coinCurrency(args[3]));
            messages().send(sender, "Alarm " + alert.id().toString().substring(0, 8) + " gespeichert. Meldet die nächste Schwellenüberschreitung, sobald ein Ausgangskurs bekannt ist.");
        }
    }
    public static BigDecimal toSats(BigDecimal fiatAmount, BigDecimal price) {
        return fiatAmount.multiply(BigDecimal.valueOf(100_000_000L)).divide(price, 0, RoundingMode.DOWN);
    }
    private void sats(CommandSender sender, String[] args) {
        exact(args, 3); BigDecimal amount = positiveAmount(args[1]); String currency = coinCurrency(args[2]);
        request(sender, false, quote -> messages().send(sender, MessageFormatter.money(amount, messages().locale(sender), currency) + " " + currency
                + " ≈ " + MessageFormatter.number(toSats(amount, quote.snapshot().price(currency)), messages().locale(sender), 0)
                + " sats" + (quote.stale() ? " &c(veralteter Kurs)" : "") + ". 1 BTC = 100.000.000 sats."));
    }
    private void board(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            messages().send(sender, "Kurstafeln: " + String.join(", ", plugin.getBoards().names())); return;
        }
        admin(sender);
        if ((args.length == 2 || args.length == 3) && args[1].equalsIgnoreCase("create")) {
            plugin.getBoards().create(player(sender), args.length == 3 ? args[2] : "spawn");
            messages().send(sender, "Kurstafel an deiner Position erstellt.");
        } else if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            if (!plugin.getBoards().remove(args[2])) throw usage("Kurstafel nicht gefunden.");
            messages().send(sender, "Kurstafel entfernt (entladene Chunks werden beim Laden bereinigt).");
        } else throw usage("/btc board create [Name] | remove <Name> | list");
    }
    private void history(CommandSender sender, String[] args) {
        if (args.length > 2) throw usage("/btc history [1h|6h|24h|7d]");
        Duration period = PERIODS.get(args.length == 1 ? "24h" : args[1].toLowerCase(Locale.ROOT));
        if (period == null) throw usage("/btc history [1h|6h|24h|7d]");
        String selected = messages().currency(sender);
        List<PriceSnapshot> samples = plugin.getHistory().list(period);
        if (samples.isEmpty()) { messages().send(sender, "Für diesen Zeitraum sind noch keine Kursdaten gespeichert."); return; }
        List<String> currencies = selected.equals("BOTH") ? List.of("EUR", "USD") : List.of(selected);
        for (String currency : currencies) {
            List<PriceSnapshot> available = samples.stream().filter(sample -> sample.supports(currency)).toList();
            if (available.isEmpty()) { messages().send(sender, "Für " + currency + " sind in diesem Zeitraum noch keine Kursdaten gespeichert."); continue; }
            BigDecimal low = available.stream().map(s -> s.price(currency)).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal high = available.stream().map(s -> s.price(currency)).max(BigDecimal::compareTo).orElseThrow();
            messages().send(sender, "Verlauf " + currency + " (" + period.toHours() + "h, " + available.size() + " Messungen): "
                    + MessageFormatter.money(low, messages().locale(sender), currency) + "–" + MessageFormatter.money(high, messages().locale(sender), currency));
            messages().send(sender, MessageFormatter.chart(available, currency, period, Instant.now()));
        }
        messages().send(sender, "Links: früher; rechts: jetzt. Zeichen steigen von _ bis #; Leerzeichen = keine Messung.");
    }
    private void portfolio(CommandSender sender, String[] args) {
        Player player = player(sender); UUID id = player.getUniqueId();
        if (args.length == 2 && args[1].equalsIgnoreCase("start")) {
            showBalance(sender, plugin.getPreferences().startPortfolio(id)); return;
        }
        if (args.length == 1) {
            showBalance(sender, plugin.getPreferences().getPortfolio(id).orElseThrow(() -> usage("Virtuelles Lernportfolio: /btc portfolio start (10.000 virtuelle EUR; kein echtes Geld)."))); return;
        }
        exact(args, 3); String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("buy") && !action.equals("sell")) throw usage("/btc portfolio start | buy <EUR-Betrag> | sell <BTC-Betrag>");
        BigDecimal amount = positiveAmount(args[2]);
        if (plugin.getPreferences().getPortfolio(id).isEmpty()) throw usage("Zuerst /btc portfolio start. Es handelt sich ausschließlich um virtuelles Geld.");
        request(sender, false, quote -> {
            if (quote.stale()) throw usage("Handel benötigt einen aktuellen Kurs. Bitte später erneut versuchen.");
            PortfolioBalance balance = action.equals("buy") ? plugin.getPreferences().buy(id, amount, quote.snapshot().eur())
                    : plugin.getPreferences().sell(id, amount, quote.snapshot().eur());
            showBalance(sender, balance);
        });
    }
    private void showBalance(CommandSender sender, PortfolioBalance balance) {
        messages().send(sender, "Virtuelles Lernportfolio (kein echtes Geld): " + MessageFormatter.number(balance.cashEur(), messages().locale(sender), 2)
                + " EUR | " + MessageFormatter.number(balance.bitcoin(), messages().locale(sender), 8) + " BTC");
    }
    public void showHelp(CommandSender sender) {
        if (!allowed(sender)) return;
        messages().send(sender, "&6BitcoinPrice – Befehle");
        for (String line : List.of("/btc [price] | /btceur | /btcusd – aktueller Kurs", "/btc currency <Währung|BOTH|DEFAULT> – persönliche Währung",
                "/btc on|off – persönliche Kursnachrichten", "/btc display chat|actionbar|off – Anzeige", "/btc locale de-DE|en-US|DEFAULT | settings",
                "/btc alert above|below <Betrag> <Währung>", "/btc alert list | remove <ID> – explizite Preisalarme", "/btc sats <Betrag> <Währung> – Umrechnung in Satoshi",
                "/btc history [1h|6h|24h|7d] – gespeicherte Messungen", "/btc board list – Kurstafeln", "/btc portfolio start | buy <EUR> | sell <BTC>",
                "Portfolio: freiwilliges Lernspiel mit virtuellem Geld; keine echten Käufe.")) messages().send(sender, line);
        messages().send(sender, "Währungen: " + String.join(", ", CurrencyCatalog.codes()) + ". BOTH = EUR und USD; DEFAULT = globale Auswahl.");
        if (isAdmin(sender)) {
            messages().send(sender, "OP/Admin: /btc interval 1|5|10|30|60 | global currency <Währung|BOTH> | refresh | on|off all");
            messages().send(sender, "Admin: /btc board create [Name] | remove <Name>");
            messages().send(sender, "OP/Admin: /btc player <Name|UUID> on|off|settings | currency <Währung|BOTH|DEFAULT>");
            messages().send(sender, "OP/Admin: /btc player <Name|UUID> display chat|actionbar|off | locale <de-DE|en-US|DEFAULT>");
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("bitcoinprice.use")) return List.of();
        List<String> values = new ArrayList<>(); boolean admin = isAdmin(sender);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            values.addAll(List.of("price", "help", "currency", "on", "off", "settings", "locale", "display", "alert", "sats", "history", "portfolio", "board"));
            if (admin) values.addAll(List.of("interval", "global", "refresh", "player"));
        } else if (args.length == 2) {
            switch (sub) {
                case "currency" -> values.addAll(sender instanceof Player ? CurrencyCatalog.selectionCodes(true) : admin ? CurrencyCatalog.selectionCodes(false) : List.of());
                case "player" -> {
                    if (admin) {
                        plugin.getServer().getOnlinePlayers().forEach(online -> values.add(online.getName()));
                        if (sender instanceof Player self) values.add(self.getName());
                        for (OfflinePlayer known : plugin.getServer().getOfflinePlayers()) if (known.getName() != null) values.add(known.getName());
                    }
                }
                case "interval" -> { if (admin) values.addAll(List.of("1", "5", "10", "30", "60")); }
                case "global" -> { if (admin) values.add("currency"); }
                case "on", "off" -> { if (admin) values.add("all"); }
                case "locale" -> values.addAll(List.of("de-DE", "en-US", "DEFAULT"));
                case "display" -> values.addAll(List.of("chat", "actionbar", "off"));
                case "alert" -> values.addAll(List.of("above", "below", "list", "remove"));
                case "history" -> values.addAll(List.of("1h", "6h", "24h", "7d"));
                case "portfolio" -> values.addAll(List.of("start", "buy", "sell"));
                case "board" -> { values.add("list"); if (admin) values.addAll(List.of("create", "remove")); }
                default -> { }
            }
        } else if (args.length == 3) {
            if (sub.equals("global") && admin && args[1].equalsIgnoreCase("currency")) values.addAll(CurrencyCatalog.selectionCodes(false));
            if (sub.equals("player") && admin) values.addAll(List.of("on", "off", "currency", "settings", "display", "locale"));
            if (sub.equals("sats")) values.addAll(CurrencyCatalog.codes());
            if (sub.equals("board") && admin && args[1].equalsIgnoreCase("remove")) values.addAll(plugin.getBoards().names());
            if (sub.equals("alert") && args[1].equalsIgnoreCase("remove") && sender instanceof Player player)
                plugin.getPreferences().listAlerts(player.getUniqueId()).forEach(alert -> values.add(alert.id().toString().substring(0, 8)));
        } else if (args.length == 4) {
            if (sub.equals("alert") && (args[1].equalsIgnoreCase("above") || args[1].equalsIgnoreCase("below"))) values.addAll(CurrencyCatalog.codes());
            if (sub.equals("player") && admin) switch (args[2].toLowerCase(Locale.ROOT)) {
                case "currency" -> values.addAll(CurrencyCatalog.selectionCodes(true));
                case "display" -> values.addAll(List.of("chat", "actionbar", "off"));
                case "locale" -> values.addAll(List.of("de-DE", "en-US", "DEFAULT"));
                default -> { }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().distinct().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
