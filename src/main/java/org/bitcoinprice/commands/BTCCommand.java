package org.bitcoinprice.commands;

import org.bitcoinprice.BitcoinPrice;
import org.bitcoinprice.model.PriceQuote;
import org.bitcoinprice.model.PriceSnapshot;
import org.bitcoinprice.model.CurrencyCatalog;
import org.bitcoinprice.preferences.AlertDirection;
import org.bitcoinprice.preferences.ActionbarMode;
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
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!allowed(sender)) return true;
        try {
            if (args.length == 0) { showPrice(sender, null); return true; }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "price" -> { exact(args, 1); showPrice(sender, null); }
                case "help" -> {
                    if (args.length > 2) throw usage("Verwendung: /btc help [Seite] oder /btchelp [Seite]");
                    showHelp(sender, args.length == 1 ? "1" : args[1]);
                }
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
                        if (quote.stale()) {
                            messages().send(sender, "Kein aktueller Kurs verfügbar; der letzte gespeicherte Kurs ist veraltet. Keine Aktualisierung gesendet.");
                            return;
                        }
                        plugin.getScheduler().broadcastQuote(quote);
                        messages().send(sender, "Kurs aktualisiert; globale Chatnachrichten beachten die gespeicherten Einstellungen.");
                    });
                }
                case "on", "off" -> notifications(sender, args, sub.equals("on"));
                case "settings" -> { exact(args, 1); settings(sender); }
                case "status" -> { exact(args, 1); status(sender); }
                case "locale" -> {
                    exact(args, 2); Player player = player(sender);
                    String locale = args[1].equalsIgnoreCase("DEFAULT") ? "DEFAULT" : args[1];
                    plugin.getPreferences().setLocale(player.getUniqueId(), locale);
                    messages().send(sender, "Sprache/Zahlenformat: " + locale);
                }
                case "display" -> {
                    Player player = player(sender);
                    display(sender, player.getUniqueId(), player, "Deine Anzeige", args, 1);
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
        if (args.length == 1) { messages().send(sender, "Chat-Intervall / Actionbar-DEFAULT: " + plugin.getConfigManager().getPriceInterval() + " Minuten"); return; }
        exact(args, 2); admin(sender);
        int value;
        try { value = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { throw usage("/btc interval 1|5|10|30|60"); }
        if (!plugin.getConfigManager().setPriceInterval(value)) throw usage("Erlaubte Intervalle: 1, 5, 10, 30, 60 Minuten.");
        plugin.getScheduler().updateSchedulerInterval(value);
        messages().send(sender, "Chat-Intervall / Actionbar-DEFAULT: " + value + " Minuten. Eigene Actionbar-Intervalle bleiben erhalten.");
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
            boolean changed = plugin.getPreferences().get(player.getUniqueId()).notifications() != on;
            plugin.getPreferences().setNotifications(player.getUniqueId(), on);
            if (changed) plugin.getScheduler().resetActionbar(player);
            messages().send(sender, "Deine Kursnachrichten: " + (on ? "an" : "aus") + ". Preisalarme verwaltest du separat mit /btc alert."
                    + inactiveHint(player.getUniqueId(), "/btc"));
        }
    }
    private void settings(CommandSender sender) {
        showSettings(sender, player(sender).getUniqueId(), "Deine Einstellungen");
    }
    private void status(CommandSender sender) {
        var status = plugin.getApiService().status();
        String cache = status.quote().map(quote -> "Abruf vor " + MessageFormatter.age(quote.snapshot().fetchedAt())
                + " | Kursstand vor " + MessageFormatter.age(quote.snapshot().providerUpdatedAt())
                + " | " + (quote.stale() ? "veraltet" : "aktuell")).orElse("kein verwendbarer Kurs");
        messages().send(sender, "Kurscache: " + cache);
        messages().send(sender, "Kursabfrage: " + (status.requestInFlight() ? "läuft" : "ruht")
                + " | API-Wartezeit: " + status.retryAfterSeconds() + " Sekunden. Status löst keine Abfrage aus.");
        if (sender instanceof Player player) showSettings(sender, player.getUniqueId(), "Deine Einstellungen");
    }
    private void showSettings(CommandSender sender, UUID id, String label) {
        Preferences preferences = plugin.getPreferences().get(id);
        String currency = preferences.currency().equals("DEFAULT") ? "DEFAULT → " + plugin.getConfigManager().getPriceCurrency() : preferences.currency();
        String locale = preferences.locale().equals("DEFAULT") ? "DEFAULT → " + plugin.getConfigManager().getLocale().toLanguageTag() : preferences.locale();
        messages().send(sender, label + ": Kursnachrichten: " + (preferences.notifications() ? "an" : "aus")
                + " | Anzeige: " + preferences.display().name().toLowerCase(Locale.ROOT)
                + " | Währung: " + currency + " | Sprache: " + locale);
        messages().send(sender, "Chat: " + plugin.getConfigManager().getPriceInterval() + " Minuten | Actionbar: "
                + actionbarDescription(preferences) + " | Automatische Anzeige: " + (effectiveDisplay(preferences) ? "aktiv" : "inaktiv"));
    }
    private boolean effectiveDisplay(Preferences settings) {
        return settings.notifications() && settings.display() != DisplayMode.OFF
                && (settings.display() != DisplayMode.CHAT || plugin.getConfigManager().isBroadcastsEnabled());
    }
    private String inactiveHint(UUID id, String commandPrefix) {
        Preferences settings = plugin.getPreferences().get(id);
        if (!settings.notifications()) return " Regelmäßige Anzeige bleibt aus; einschalten mit " + commandPrefix + " on.";
        if (settings.display() == DisplayMode.OFF) return " Anzeige bleibt aus; wähle " + commandPrefix + " display chat oder actionbar.";
        if (settings.display() == DisplayMode.CHAT && !plugin.getConfigManager().isBroadcastsEnabled())
            return " Globale Chatnachrichten sind ausgeschaltet; ein OP kann /btc on all verwenden.";
        return "";
    }
    private String actionbarDescription(Preferences settings) {
        if (settings.actionbarMode() == ActionbarMode.CONTINUOUS) return "dauerhaft";
        int minutes = settings.actionbarIntervalMinutes();
        return "Intervall " + (minutes == 0 ? "DEFAULT → " + plugin.getConfigManager().getPriceInterval() : minutes) + " Minuten";
    }
    private void display(CommandSender sender, UUID id, Player online, String label, String[] args, int offset) {
        String prefix = offset == 1 ? "/btc" : "/btc player " + args[1];
        String syntax = prefix + " display chat|off oder actionbar [continuous|interval] [1|5|10|30|60|DEFAULT]";
        if (args.length <= offset) throw usage(syntax);
        DisplayMode selected;
        try { selected = DisplayMode.valueOf(args[offset].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw usage(syntax); }
        Preferences old = plugin.getPreferences().get(id);
        if (selected != DisplayMode.ACTIONBAR || args.length == offset + 1) {
            exact(args, offset + 1);
            plugin.getPreferences().setDisplay(id, selected);
        } else {
            if (args.length > offset + 3) throw usage(syntax);
            ActionbarMode mode;
            try { mode = ActionbarMode.valueOf(args[offset + 1].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { throw usage(syntax); }
            int minutes = old.actionbarIntervalMinutes();
            if (args.length == offset + 3) {
                if (mode != ActionbarMode.INTERVAL) throw usage("Minuten sind nur für actionbar interval erlaubt.");
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
                + (selected == DisplayMode.ACTIONBAR ? " (" + actionbarDescription(current) + ")" : "") + inactiveHint(id, prefix));
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
        else if (action.equals("currency") || action.equals("locale")) exact(args, 4);
        else if (action.equals("display")) { if (args.length < 4 || args.length > 6) throw usage("/btc player <Name|UUID> display chat|off|actionbar [continuous|interval] [Minuten|DEFAULT]"); }
        else throw usage("/btc player <Name|UUID> on|off|settings|currency <Währung|DEFAULT>");
        Target target = resolveTarget(sender, args[1]);
        switch (action) {
            case "on", "off" -> {
                boolean enabled = action.equals("on");
                boolean changed = plugin.getPreferences().get(target.id()).notifications() != enabled;
                plugin.getPreferences().setNotifications(target.id(), enabled);
                if (changed && target.online() != null) plugin.getScheduler().resetActionbar(target.online());
                messages().send(sender, target.label() + ": Kursnachrichten " + (enabled ? "an" : "aus") + ". Preisalarme bleiben separat verwaltbar."
                        + inactiveHint(target.id(), "/btc player " + args[1]));
            }
            case "currency" -> {
                String selected = args[3].toUpperCase(Locale.ROOT);
                plugin.getPreferences().setCurrency(target.id(), selected);
                messages().send(sender, target.label() + ": Währung " + selected);
            }
            case "settings" -> showSettings(sender, target.id(), target.label());
            case "display" -> display(sender, target.id(), target.online(), target.label() + ": Anzeige", args, 3);
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
        messages().error(sender, "Hilfe-Seite muss eine ganze Zahl von 1 bis " + helpPageCount(sender) + " sein. /btc help [Seite] oder /btchelp [Seite]");
    }
    public void showHelp(CommandSender sender, int page) {
        if (!allowed(sender)) return;
        List<String> entries = helpEntries(isAdmin(sender));
        int pages = helpPageCount(sender);
        if (page < 1 || page > pages) { invalidHelpPage(sender); return; }
        int first = (page - 1) * HELP_PAGE_SIZE;
        String section = first >= helpEntries(false).size() ? "OP/Admin-Verwaltung" : "Spielerbefehle und Hinweise";
        messages().send(sender, "&6BitcoinPrice-Hilfe " + page + "/" + pages + " — " + section);
        for (String entry : entries.subList(first, Math.min(first + HELP_PAGE_SIZE, entries.size()))) messages().send(sender, entry);
        Component footer = messages().component(plugin.getConfigManager().getMessagePrefix()).append(Component.text("Seiten: ", NamedTextColor.GRAY));
        if (page > 1) footer = footer.append(helpLink("« Zurück (/btc help " + (page - 1) + ")", page - 1));
        if (page > 1 && page < pages) footer = footer.append(Component.text(" | ", NamedTextColor.GRAY));
        if (page < pages) footer = footer.append(helpLink("Weiter » (/btc help " + (page + 1) + ")", page + 1));
        sender.sendMessage(footer);
    }
    private static Component helpLink(String label, int page) {
        return Component.text(label, NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/btc help " + page))
                .hoverEvent(HoverEvent.showText(Component.text("Hilfeseite " + page + " öffnen")));
    }
    /** Canonical complete help; exactly eight normal entries per page keep topics together. */
    public static List<String> helpEntries(boolean administrator) {
        List<String> entries = new ArrayList<>(List.of(
                "/btc — aktuellen Bitcoin-Kurs anzeigen",
                "/btc price — aktuellen Bitcoin-Kurs anzeigen",
                "/btceur — Bitcoin-Kurs in EUR anzeigen",
                "/btcusd — Bitcoin-Kurs in USD anzeigen",
                "/btc help [Seite] — diese Hilfe; ohne Seite: 1",
                "/btchelp [Seite] — identische Hilfe; ohne Seite: 1",
                "/btc interval — Chat-Intervall und Actionbar-DEFAULT ansehen",
                "/btc currency — deine wirksame Währung ansehen (Konsole: global)",
                "/btc currency <Währung|BOTH|DEFAULT> — persönliche Währung setzen",
                "/btc on — deine ausgewählten Kursnachrichten einschalten",
                "/btc off — deine Kursnachrichten ausschalten; Alarme bleiben separat",
                "/btc settings — gespeicherte und wirksame Einstellungen ansehen",
                "/btc status — Kurscache, API-Wartezeit und wirksame Anzeige prüfen; keine API-Abfrage",
                "/btc display chat — Kurse im Chat anzeigen",
                "/btc display actionbar — Kurse über der Schnellzugriffsleiste anzeigen",
                "/btc display actionbar continuous — dauerhaft sichtbar; jede Sekunde aus dem Cache",
                "/btc display actionbar interval [1|5|10|30|60|DEFAULT] — kurz im persönlichen Minutenintervall",
                "/btc display off — regelmäßige Kursanzeige ausschalten",
                "/btc locale <Sprachcode|DEFAULT> — Zahlenformat wählen, z. B. de-DE oder en-US",
                "/btc alert above <Betrag> <Währung> — Alarm beim Kreuzen nach oben speichern",
                "/btc alert below <Betrag> <Währung> — Alarm beim Kreuzen nach unten speichern",
                "/btc alert list — deine Alarme samt ID ansehen",
                "/btc alert remove <ID> — eigenen Alarm löschen (eindeutige Kurz-ID möglich)",
                "/btc sats <Betrag> <Währung> — Gegenwert in ganzen Satoshi anzeigen",
                "/btc history [1h|6h|24h|7d] — gespeicherte Kurse; Standard: 24h",
                "/btc board list — Namen der Kurstafeln ansehen",
                "Alarme sind explizit: /btc off und globales Chat-Aus deaktivieren sie nicht.",
                "/btc portfolio — dein virtuelles Guthaben und BTC ansehen",
                "/btc portfolio start — Lernportfolio mit 10.000 virtuellen EUR eröffnen; kein Reset",
                "/btc portfolio buy <EUR-Betrag> — virtuelle BTC zum aktuellen EUR-Kurs kaufen",
                "/btc portfolio sell <BTC-Betrag> — virtuelle BTC zum aktuellen EUR-Kurs verkaufen",
                "Währungen: " + String.join(", ", CurrencyCatalog.codes()) + ". Beträge mit Dezimalpunkt, z. B. 10.50.",
                "BOTH zeigt EUR und USD gemeinsam; Alarme und Satoshi-Umrechnung benötigen eine einzelne Währung.",
                "DEFAULT übernimmt die globale Auswahl; bei /btc locale das globale Zahlenformat.",
                "Portfolio: ausschließlich Spielgeld, freiwilliges Lernspiel; keine echten Käufe. Handel benötigt aktuellen Kurs."));
        if (administrator) {
            entries.addAll(List.of(
                    "/btc interval <1|5|10|30|60> — Chat-Intervall und Actionbar-DEFAULT in Minuten ändern (OP/Admin)",
                    "/btc global currency <Währung|BOTH> — globale Währung ändern (OP/Admin)",
                    "/btc refresh — Kurs mit Abfragebegrenzung aktualisieren und an berechtigte Chat-Empfänger senden (OP/Admin)",
                    "/btc on all — globale Chatnachrichten einschalten (OP/Admin)",
                    "/btc off all — globale Chatnachrichten ausschalten; persönliche Actionbar/Alarme bleiben separat (OP/Admin)",
                    "/btc board create [Name] — Tafel an deiner Position erstellen; Standard: spawn (OP/Admin)",
                    "/btc board remove <Name> — Tafel entfernen (OP/Admin)",
                    "/btc player <Name|UUID> settings — Spielereinstellungen ansehen (OP/Admin)",
                    "/btc player <Name|UUID> on — individuelle Kursnachrichten einschalten (OP/Admin)",
                    "/btc player <Name|UUID> off — individuelle Kursnachrichten ausschalten (OP/Admin)",
                    "/btc player <Name|UUID> currency <Währung|BOTH|DEFAULT> — individuelle Währung ändern (OP/Admin)",
                    "/btc player <Name|UUID> display <chat|actionbar|off> — individuelle Anzeige ändern (OP/Admin)",
                    "/btc player <Name|UUID> display actionbar continuous — dauerhaft für einen Spieler (OP/Admin)",
                    "/btc player <Name|UUID> display actionbar interval [1|5|10|30|60|DEFAULT] — persönliches Minutenintervall (OP/Admin)",
                    "/btc player <Name|UUID> locale <Sprachcode|DEFAULT> — individuelles Zahlenformat ändern (OP/Admin)",
                    "Spielerziele: exakte bekannte Namen oder vollständige UUID; offline speicherbar, keine externe Profilabfrage."));
        }
        return List.copyOf(entries);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("bitcoinprice.use")) return List.of();
        List<String> values = new ArrayList<>(); boolean admin = isAdmin(sender);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            values.addAll(List.of("price", "help", "interval", "currency", "on", "off", "settings", "status", "locale", "display", "alert", "sats", "history", "portfolio", "board"));
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
            if (sub.equals("display") && args[1].equalsIgnoreCase("actionbar")) values.addAll(List.of("continuous", "interval"));
            if (sub.equals("board") && admin && args[1].equalsIgnoreCase("remove")) values.addAll(plugin.getBoards().names());
            if (sub.equals("alert") && args[1].equalsIgnoreCase("remove") && sender instanceof Player player)
                plugin.getPreferences().listAlerts(player.getUniqueId()).forEach(alert -> values.add(alert.id().toString().substring(0, 8)));
        } else if (args.length == 4) {
            if (sub.equals("display") && args[1].equalsIgnoreCase("actionbar") && args[2].equalsIgnoreCase("interval"))
                values.addAll(List.of("1", "5", "10", "30", "60", "DEFAULT"));
            if (sub.equals("alert") && (args[1].equalsIgnoreCase("above") || args[1].equalsIgnoreCase("below"))) values.addAll(CurrencyCatalog.codes());
            if (sub.equals("player") && admin) switch (args[2].toLowerCase(Locale.ROOT)) {
                case "currency" -> values.addAll(CurrencyCatalog.selectionCodes(true));
                case "display" -> values.addAll(List.of("chat", "actionbar", "off"));
                case "locale" -> values.addAll(List.of("de-DE", "en-US", "DEFAULT"));
                default -> { }
            }
        } else if (args.length == 5 && sub.equals("player") && admin && args[2].equalsIgnoreCase("display") && args[3].equalsIgnoreCase("actionbar")) {
            values.addAll(List.of("continuous", "interval"));
        } else if (args.length == 6 && sub.equals("player") && admin && args[2].equalsIgnoreCase("display")
                && args[3].equalsIgnoreCase("actionbar") && args[4].equalsIgnoreCase("interval")) {
            values.addAll(List.of("1", "5", "10", "30", "60", "DEFAULT"));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().distinct().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
