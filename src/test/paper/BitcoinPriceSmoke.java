package org.bitcoinprice.smoke;

import com.sun.net.httpserver.HttpServer;
import org.bitcoinprice.BitcoinPrice;
import org.bitcoinprice.model.PriceSnapshot;
import org.bitcoinprice.preferences.DisplayMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only plugin: exercises the real Paper APIs, without requiring a connected client. */
public final class BitcoinPriceSmoke extends JavaPlugin {
    private static final UUID OWNER = UUID.fromString("ddc57a9b-759f-4251-92be-ac1707413661");
    private static final UUID OFFLINE_TARGET = UUID.fromString("b3a3ee0f-cd0e-40f5-9c55-c92ad57a5044");
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> received = new ArrayList<>();
    private final List<Component> receivedComponents = new ArrayList<>();
    private HttpServer http;
    private BitcoinPrice plugin;
    private Player player;
    private boolean admin;
    private boolean op;
    private int passed;
    private long sampleSequence;

    @Override public void onEnable() {
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 28761), 0);
            http.createContext("/price", exchange -> {
                requests.incrementAndGet();
                String body = "{\"bitcoin\":{\"eur\":80000,\"usd\":90000,\"gbp\":70000,\"chf\":75000,\"cad\":120000,\"aud\":130000,"
                        + "\"jpy\":14000000,\"cny\":600000,\"inr\":7400000,\"eur_24h_change\":2.5,\"usd_24h_change\":2.1,"
                        + "\"gbp_24h_change\":3.5,\"jpy_24h_change\":1.0,\"last_updated_at\":" + Instant.now().getEpochSecond() + "}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            http.start();
            plugin = (BitcoinPrice) Bukkit.getPluginManager().getPlugin("BitcoinPrice");
            Bukkit.getScheduler().runTaskLater(this, () -> guarded(this::initial), 20);
        } catch (Exception error) { fail(error); }
    }

    private void initial() throws Exception {
        player = (Player) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getUniqueId" -> OWNER;
                case "getName" -> "BitcoinSmoke";
                case "hasPermission" -> "bitcoinprice.use".equals(args[0]) && !op || admin;
                case "isOp" -> op || admin;
                case "isOnline", "isValid" -> true;
                case "getLocation" -> new Location(Bukkit.getWorlds().getFirst(), 0.5, 80, 0.5);
                case "getWorld" -> Bukkit.getWorlds().getFirst();
                case "getServer" -> Bukkit.getServer();
                case "locale" -> java.util.Locale.GERMANY;
                case "getLocale" -> "de_de";
                case "sendMessage", "sendActionBar" -> {
                    if (args != null) for (Object value : args) {
                        if (value instanceof Component component) {
                            received.add(PlainTextComponentSerializer.plainText().serialize(component));
                            receivedComponents.add(component);
                        }
                        else if (value instanceof String text) received.add(text);
                    }
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "BitcoinSmokePlayer";
                default -> {
                    if (method.getReturnType() == boolean.class) yield false;
                    if (method.getReturnType() == int.class) yield 0;
                    if (method.getReturnType() == long.class) yield 0L;
                    if (method.getReturnType() == double.class) yield 0.0;
                    if (method.getReturnType() == float.class) yield 0.0f;
                    yield null;
                }
            };
        });
        check(plugin != null && plugin.isEnabled(), "plugin enabled on Paper 26.2");
        // The proxy is not a real connected player, so the fixture holds its own chunk ticket.
        var playerChunk = Bukkit.getWorlds().getFirst().getChunkAt(0, 0);
        playerChunk.addPluginChunkTicket(this);
        playerChunk.getEntities();
        if (Files.exists(getDataFolder().toPath().resolve("restart.marker"))) {
            Bukkit.getWorlds().getFirst().getChunkAt(0, 0).getEntities(); // Test explicitly loads the board's chunk and entities.
            check(!plugin.getPreferences().get(OWNER).notifications(), "notification off restored after restart");
            check(plugin.getPreferences().get(OWNER).currency().equals("USD"), "personal currency restored");
            check(!plugin.getPreferences().get(OFFLINE_TARGET).notifications()
                            && plugin.getPreferences().get(OFFLINE_TARGET).currency().equals("GBP"),
                    "admin-selected offline UUID notification/currency settings restored");
            check(plugin.getPreferences().get(OWNER).display() == DisplayMode.ACTIONBAR, "display restored");
            check(plugin.getPreferences().getPortfolio(OWNER).orElseThrow().bitcoin().compareTo(new BigDecimal("0.001")) == 0,
                    "portfolio restored without resetting balance");
            check(!plugin.getConfigManager().isBroadcastsEnabled(), "global notifications off restored");
            check(plugin.getBoards().names().contains("smoke"), "board definition restored");
            check(!plugin.getHistory().list(Duration.ofDays(1)).isEmpty(), "history restored");
            Bukkit.getScheduler().runTaskLater(this, () -> guarded(this::verifyRestoredBoard), 40);
            return;
        }
        verifyHelp(false);
        String originalConfig = plugin.getConfig().saveToString();
        plugin.getConfig().set("price-interval", 1.5);
        plugin.getConfig().set("price-currency", "eur");
        plugin.getConfig().set("api.timeout", 0);
        plugin.getConfigManager().loadConfig();
        check(plugin.getConfigManager().getPriceInterval() == 10 && plugin.getConfigManager().getPriceCurrency().equals("EUR")
                        && plugin.getConfigManager().getApiTimeout() > 0,
                "invalid file configuration safely normalized");
        plugin.getConfig().loadFromString(originalConfig);
        plugin.getConfigManager().loadConfig();
        Bukkit.getWorlds().getFirst().getChunkAt(0, 0).getEntities(); // A real player's location would already be loaded.
        int interval = plugin.getConfigManager().getPriceInterval();
        command(player, "interval", "1");
        command(player, "global", "currency", "USD");
        command(player, "refresh");
        command(player, "off", "all");
        command(player, "board", "create", "forbidden");
        command(player, "player", OFFLINE_TARGET.toString(), "off");
        command(player, "player", OFFLINE_TARGET.toString(), "currency", "GBP");
        check(plugin.getConfigManager().getPriceInterval() == interval && plugin.getConfigManager().getPriceCurrency().equals("EUR"),
                "non-admin cannot change global settings");
        check(requests.get() == 0 && plugin.getConfigManager().isBroadcastsEnabled() && plugin.getBoards().names().isEmpty(),
                "non-admin cannot refresh, disable all, or create boards");
        check(plugin.getPreferences().get(OFFLINE_TARGET).notifications()
                        && plugin.getPreferences().get(OFFLINE_TARGET).currency().equals("DEFAULT"),
                "non-admin cannot change another player's notifications or currency");
        check(!plugin.getBtcCommand().onTabComplete(player, plugin.getCommand("btc"), "btc", new String[]{""}).contains("player"),
                "non-admin completion hides target-player administration");
        command(player, "unknown-subcommand");
        check(requests.get() == 0, "unknown commands never request HTTP");
        command(player, "currency", "USD");
        check(plugin.getPreferences().get(OWNER).currency().equals("USD") && plugin.getConfigManager().getPriceCurrency().equals("EUR"),
                "personal currency leaves global currency unchanged");
        command(player, "display", "actionbar");
        command(player, "locale", "en-US");
        command(player, "off");
        check(!plugin.getPreferences().get(OWNER).notifications(), "personal off applied");
        plugin.getApiService().fetchBitcoinPrice().whenComplete((quote, error) -> plugin.runSync(() -> {
            if (error != null) { fail(error); return; }
            guarded(() -> {
                check(!quote.stale(), "fresh provider timestamp accepted");
                check(quote.snapshot().supports("GBP") && quote.snapshot().price("GBP").compareTo(new BigDecimal("70000")) == 0,
                        "extended provider response includes actual GBP price");
                Bukkit.getScheduler().runTaskLater(this, () -> guarded(this::withQuote), 2);
            });
        }));
    }

    private void withQuote() throws Exception {
        check(requests.get() == 1 && !plugin.getHistory().list(Duration.ofHours(1)).isEmpty(), "shared quote recorded in history");
        command(player);
        plugin.getCommand("btceur").execute(player, "btceur", new String[0]);
        plugin.getCommand("btcusd").execute(player, "btcusd", new String[0]);
        command(player, "sats", "10", "EUR");
        command(player, "history", "24h");
        command(player, "settings");
        check(requests.get() == 1, "price aliases, sats and settings share cache");
        check(received.stream().anyMatch(message -> message.contains("12,500") || message.contains("12.500") || message.contains("12500")),
                "10 EUR converts to 12500 satoshis");
        check(plugin.getBtcCommand().onTabComplete(player, plugin.getCommand("btc"), "btc", new String[]{""}).contains("sats"),
                "command completion includes new functions");
        command(player, "alert", "above", "85000", "EUR");
        check(plugin.getPreferences().listAlerts(OWNER).size() == 1, "price alert command adds owned alert");
        var prefs = plugin.getPreferences();
        prefs.checkAlerts(OWNER, snapshot("80000"));
        check(prefs.checkAlerts(OWNER, snapshot("86000")).size() == 1, "alert triggers on threshold crossing");
        check(prefs.checkAlerts(OWNER, snapshot("86000")).isEmpty(), "alert does not repeat above threshold");
        command(player, "portfolio", "start");
        command(player, "portfolio", "buy", "80");
        check(prefs.getPortfolio(OWNER).orElseThrow().bitcoin().compareTo(new BigDecimal("0.001")) == 0,
                "virtual portfolio buys using the shared EUR quote");
        op = true; // Explicitly exercise OP while bitcoinprice.admin permission remains false.
        check(!player.hasPermission("bitcoinprice.admin") && !player.hasPermission("bitcoinprice.use") && player.isOp(),
                "fixture distinguishes OP from both admin and use permissions");
        verifyHelp(true);
        command(player, "player", "BitcoinSmoke", "on");
        check(prefs.get(OWNER).notifications(), "OP can enable individual player using exact name");
        command(player, "player", OWNER.toString(), "off");
        check(!prefs.get(OWNER).notifications(), "OP can disable individual player using UUID");
        command(player, "player", OWNER.toString(), "currency", "GBP");
        check(prefs.get(OWNER).currency().equals("GBP") && plugin.getConfigManager().getPriceCurrency().equals("EUR"),
                "OP selects individual GBP without changing global currency");
        command(player);
        check(received.stream().anyMatch(message -> message.contains("70,000.00 GBP") && message.contains("+3.50%")),
                "GBP display uses GBP quote and GBP daily change rather than EUR fallback");
        command(player, "history", "24h");
        check(received.stream().anyMatch(message -> message.contains("Verlauf GBP") && message.contains("70,000.00")),
                "history renders stored GBP samples");
        command(player, "sats", "7", "GBP");
        check(received.stream().anyMatch(message -> message.contains("10,000 sats")), "GBP satoshi conversion uses GBP quote");
        command(player, "currency", "JPY");
        command(player);
        check(received.stream().anyMatch(message -> message.contains("14,000,000 JPY") && !message.contains("14,000,000.00")),
                "JPY uses ISO zero decimal precision");
        command(player, "currency", "USD"); // Existing owner restart expectations stay unchanged.
        command(player, "player", OFFLINE_TARGET.toString(), "off");
        command(player, "player", OFFLINE_TARGET.toString(), "currency", "GBP");
        command(player, "player", OFFLINE_TARGET.toString(), "settings");
        check(!prefs.get(OFFLINE_TARGET).notifications() && prefs.get(OFFLINE_TARGET).currency().equals("GBP"),
                "OP persists individual offline UUID preferences without profile lookup");
        command(player, "player", "UnbekanntSmoke", "off");
        check(received.getLast().contains("unbekannt") && requests.get() == 1,
                "unknown exact name fails locally without profile or price request");
        check(plugin.getBtcCommand().onTabComplete(player, plugin.getCommand("btc"), "btc", new String[]{"player", ""}).contains("BitcoinSmoke")
                        && plugin.getBtcCommand().onTabComplete(player, plugin.getCommand("btc"), "btc", new String[]{"player", OWNER.toString(), "currency", ""}).contains("GBP"),
                "OP completion includes exact player names and extended currency choices");
        op = false;
        admin = true;
        command(player, "board", "create", "smoke");
        check(plugin.getBoards().names().contains("smoke"), "admin creates named board");
        Bukkit.getScheduler().runTaskLater(this, () -> guarded(this::withBoard), 2);
    }

    private void withBoard() throws Exception {
        var displays = Bukkit.getWorlds().getFirst().getEntitiesByClass(TextDisplay.class);
        check(displays.size() == 1, "board creates exactly one real TextDisplay (count=" + displays.size() + ")");
        plugin.getBoards().update(plugin.getApiService().cachedQuote().orElseThrow());
        check(PlainTextComponentSerializer.plainText().serialize(displays.iterator().next().text()).contains("80"),
                "TextDisplay renders actual cached course");
        command(player, "off", "all");
        check(!plugin.getConfigManager().isBroadcastsEnabled(), "admin global off persisted in config");
        int count = requests.get();
        plugin.getScheduler().broadcastBitcoinPrice();
        check(requests.get() == count, "disabled broadcasts do not fetch");
        Files.createDirectories(getDataFolder().toPath());
        Files.writeString(getDataFolder().toPath().resolve("restart.marker"), "restart ready");
        finish();
    }

    private void verifyRestoredBoard() {
        check(Bukkit.getWorlds().getFirst().getEntitiesByClass(TextDisplay.class).size() == 1,
                "restart retains board without duplicate entity");
        check(plugin.getBoards().remove("smoke"), "owned board removal succeeds");
        check(Bukkit.getWorlds().getFirst().getEntitiesByClass(TextDisplay.class).isEmpty(), "owned TextDisplay removed");
        admin = true;
        command(player, "board", "create", "smoke");
        check(plugin.getBoards().names().contains("smoke") && Bukkit.getWorlds().getFirst().getEntitiesByClass(TextDisplay.class).size() == 1,
                "removed board name reusable without duplicate entity");
        plugin.getBoards().remove("smoke");
        finish();
    }

    private PriceSnapshot snapshot(String eur) {
        Instant sampleTime = Instant.now().plusNanos(++sampleSequence);
        return new PriceSnapshot(new BigDecimal(eur), new BigDecimal("90000"), null, null, sampleTime, sampleTime);
    }
    private record HelpOutput(List<String> text, List<Component> components) { }
    private HelpOutput helpOutput(boolean alias, String... args) {
        int textStart = received.size(), componentStart = receivedComponents.size();
        if (alias) plugin.getCommand("btchelp").execute(player, "btchelp", args);
        else {
            String[] delegated = new String[args.length + 1]; delegated[0] = "help";
            System.arraycopy(args, 0, delegated, 1, args.length);
            command(player, delegated);
        }
        return new HelpOutput(List.copyOf(received.subList(textStart, received.size())),
                List.copyOf(receivedComponents.subList(componentStart, receivedComponents.size())));
    }
    private static boolean hasHelpClick(Component component, String command) {
        if (net.kyori.adventure.text.event.ClickEvent.runCommand(command).equals(component.clickEvent())) return true;
        return component.children().stream().anyMatch(child -> hasHelpClick(child, command));
    }
    private void verifyHelp(boolean administrator) {
        int beforeHttp = requests.get();
        int pages = plugin.getBtcCommand().helpPageCount(player);
        List<String> all = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            HelpOutput main = helpOutput(false, Integer.toString(page));
            HelpOutput alias = helpOutput(true, Integer.toString(page));
            check(main.equals(alias) && main.text().size() <= 10 && main.text().getFirst().contains("Hilfe " + page + "/" + pages),
                    (administrator ? "OP" : "normal") + " help aliases produce identical bounded page " + page);
            all.addAll(main.text());
            int nextPage = page + 1;
            if (page < pages) check(main.components().stream().anyMatch(component -> hasHelpClick(component, "/btc help " + nextPage)),
                    "help next-page footer has executable Adventure navigation");
        }
        check(helpOutput(false).equals(helpOutput(false, "1")) && helpOutput(true).equals(helpOutput(false, "1")),
                "both default help commands open exactly page one");
        check(plugin.getCommand("btchelp").tabComplete(player, "btchelp", new String[]{""}).equals(
                        plugin.getBtcCommand().onTabComplete(player, plugin.getCommand("btc"), "btc", new String[]{"help", ""})),
                "both help commands complete identical valid page numbers");
        check(all.stream().anyMatch(text -> text.contains("/btc interval —"))
                        && all.stream().anyMatch(text -> text.contains("/btc currency —"))
                        && all.stream().anyMatch(text -> text.contains("/btc portfolio —"))
                        && all.stream().anyMatch(text -> text.contains("/btc portfolio start"))
                        && all.stream().anyMatch(text -> text.contains("/btc portfolio buy"))
                        && all.stream().anyMatch(text -> text.contains("/btc portfolio sell")),
                "complete help includes normal read-only interval/currency and all portfolio commands");
        check(all.stream().anyMatch(text -> text.contains("BOTH zeigt EUR und USD"))
                        && all.stream().anyMatch(text -> text.contains("DEFAULT übernimmt"))
                        && all.stream().anyMatch(text -> text.contains("Spielgeld"))
                        && all.stream().anyMatch(text -> text.contains("Währungen:") && text.contains("GBP") && text.contains("JPY")),
                "help separately explains extended currencies, BOTH, DEFAULT and virtual money");
        if (administrator) {
            check(all.stream().anyMatch(text -> text.contains("/btc global currency"))
                            && all.stream().anyMatch(text -> text.contains("/btc player <Name|UUID> currency"))
                            && all.stream().anyMatch(text -> text.contains("/btc board create"))
                            && all.stream().anyMatch(text -> text.contains("/btc interval <1|5|10|30|60>")),
                    "OP help includes complete administrative commands even when use/admin permission denied");
        } else {
            check(all.stream().noneMatch(text -> text.contains("OP/Admin") || text.contains("/btc player ")
                            || text.contains("/btc global ") || text.contains("/btc refresh")
                            || text.contains("/btc interval <") || text.contains("/btc board create")),
                    "normal help hides administrative commands");
        }
        for (String bad : List.of("0", "-1", Integer.toString(pages + 1), "1.5", "abc", "2147483648")) {
            HelpOutput main = helpOutput(false, bad), alias = helpOutput(true, bad);
            check(main.equals(alias) && main.text().size() == 1 && main.text().getFirst().contains("Hilfe-Seite"),
                    "both help commands reject invalid page " + bad + " consistently");
        }
        check(helpOutput(false, "1", "extra").equals(helpOutput(true, "1", "extra")),
                "both help commands reject extra arguments consistently");
        check(requests.get() == beforeHttp, "help pages, aliases and invalid arguments never request HTTP");
    }
    private void command(CommandSender sender, String... args) {
        plugin.getBtcCommand().onCommand(sender, plugin.getCommand("btc"), "btc", args);
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message + " | Messages: " + received);
        getLogger().info("SMOKE PASS " + (++passed) + ": " + message);
    }
    private void guarded(CheckedRunnable runnable) {
        try { runnable.run(); } catch (Throwable error) { fail(error); }
    }
    private void finish() {
        getLogger().info("SMOKE COMPLETE: " + passed + " checks passed");
        Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 5);
    }
    private void fail(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE, "SMOKE FAILED", error);
        Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 5);
    }
    @Override public void onDisable() { if (http != null) http.stop(0); }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
}
