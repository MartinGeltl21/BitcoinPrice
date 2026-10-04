package org.bitcoinprice;

import org.bitcoinprice.api.CoinGeckoService;
import org.bitcoinprice.api.PriceHistory;
import org.bitcoinprice.boards.BoardManager;
import org.bitcoinprice.commands.BTCCommand;
import org.bitcoinprice.commands.BTCEURCommand;
import org.bitcoinprice.commands.BTCHelpCommand;
import org.bitcoinprice.commands.BTCUSDCommand;
import org.bitcoinprice.config.ConfigManager;
import org.bitcoinprice.preferences.PlayerPreferencesService;
import org.bitcoinprice.presentation.MessageFormatter;
import org.bitcoinprice.scheduler.PriceScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;

/** Lightweight Bitcoin information and optional personal displays for Paper. */
public final class BitcoinPrice extends JavaPlugin {
    private ConfigManager configManager;
    private CoinGeckoService apiService;
    private PriceScheduler scheduler;
    private PlayerPreferencesService preferences;
    private PriceHistory history;
    private BoardManager boards;
    private MessageFormatter messages;
    private BTCCommand btcCommand;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        stopping = false;
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        history = new PriceHistory(getDataFolder().toPath().resolve("history.json"),
                Duration.ofHours(configManager.getHistoryHours()), getLogger());
        preferences = new PlayerPreferencesService(getDataFolder().toPath().resolve("players.json"),
                Duration.ofSeconds(configManager.getAlertCooldownSeconds()), getLogger());
        messages = new MessageFormatter(this);
        boards = new BoardManager(this);
        scheduler = new PriceScheduler(this);
        apiService = new CoinGeckoService(configManager.getApiSettings(), snapshot -> {
            history.append(snapshot);
            runSync(() -> scheduler.handleFreshSnapshot(snapshot));
        }, getLogger());
        btcCommand = new BTCCommand(this);
        Objects.requireNonNull(getCommand("btc")).setExecutor(btcCommand);
        Objects.requireNonNull(getCommand("btc")).setTabCompleter(btcCommand);
        BTCHelpCommand helpCommand = new BTCHelpCommand(this);
        Objects.requireNonNull(getCommand("btchelp")).setExecutor(helpCommand);
        Objects.requireNonNull(getCommand("btchelp")).setTabCompleter(helpCommand);
        Objects.requireNonNull(getCommand("btceur")).setExecutor(new BTCEURCommand(this));
        Objects.requireNonNull(getCommand("btcusd")).setExecutor(new BTCUSDCommand(this));
        if (((Object) boards) instanceof Listener listener) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
        boards.load();
        scheduler.startScheduler();
        getLogger().info("BitcoinPrice " + getPluginMeta().getVersion() + " wurde erfolgreich aktiviert!");
    }

    /** Schedule a UI callback only while this plugin is still accepting work. */
    public void runSync(Runnable action) {
        if (stopping || !isEnabled()) return;
        Runnable guarded = () -> {
            if (!stopping && isEnabled()) action.run();
        };
        if (Bukkit.isPrimaryThread()) {
            guarded.run();
        } else {
            try {
                getServer().getScheduler().runTask(this, guarded);
            } catch (IllegalPluginAccessException ignored) {
                // Plugin was disabled between checking and scheduling.
            }
        }
    }

    @Override
    public void onDisable() {
        stopping = true;
        if (scheduler != null) scheduler.stopScheduler();
        closeService(apiService, "API");
        closeService(boards, "Kurstafeln");
        closeService(preferences, "Spielereinstellungen");
        closeService(history, "Kursgeschichte");
        getLogger().info("BitcoinPrice Plugin wurde deaktiviert!");
    }

    private void closeService(AutoCloseable service, String name) {
        if (service == null) return;
        try {
            service.close();
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, name + " konnte nicht sauber geschlossen werden.", error);
        }
    }

    public ConfigManager getConfigManager() { return configManager; }
    public CoinGeckoService getApiService() { return apiService; }
    public PriceScheduler getScheduler() { return scheduler; }
    public PlayerPreferencesService getPreferences() { return preferences; }
    public PriceHistory getHistory() { return history; }
    public BoardManager getBoards() { return boards; }
    public MessageFormatter getMessages() { return messages; }
    public BTCCommand getBtcCommand() { return btcCommand; }
}
