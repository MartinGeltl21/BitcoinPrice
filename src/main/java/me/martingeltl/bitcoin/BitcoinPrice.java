package me.martingeltl.bitcoin;

import me.martingeltl.bitcoin.api.CoinGeckoService;
import me.martingeltl.bitcoin.commands.BTCCommand;
import me.martingeltl.bitcoin.commands.BTCEURCommand;
import me.martingeltl.bitcoin.commands.BTCHelpCommand;
import me.martingeltl.bitcoin.commands.BTCUSDCommand;
import me.martingeltl.bitcoin.config.ConfigManager;
import me.martingeltl.bitcoin.scheduler.PriceScheduler;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * BitcoinPrice - Ein Plugin zum Abrufen des aktuellen Bitcoin-Preises
 * @author Martin Geltl
 */
public final class BitcoinPrice extends JavaPlugin {

    private ConfigManager configManager;
    private CoinGeckoService apiService;
    private PriceScheduler scheduler;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        
        // Initialisiere Manager und Services
        this.configManager = new ConfigManager(this);
        this.apiService = new CoinGeckoService(this);
        this.scheduler = new PriceScheduler(this);
        
        // Registriere Commands
        getCommand("btc").setExecutor(new BTCCommand(this));
        getCommand("btchelp").setExecutor(new BTCHelpCommand(this));
        getCommand("btceur").setExecutor(new BTCEURCommand(this));
        getCommand("btcusd").setExecutor(new BTCUSDCommand(this));
        
        // Starte Scheduler
        scheduler.startScheduler();
        
        getLogger().info("BitcoinPrice Plugin wurde erfolgreich aktiviert!");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (scheduler != null) {
            scheduler.stopScheduler();
        }
        
        getLogger().info("BitcoinPrice Plugin wurde deaktiviert!");
    }
    
    /**
     * Gibt den ConfigManager zurück
     * @return ConfigManager
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    /**
     * Gibt den CoinGeckoService zurück
     * @return CoinGeckoService
     */
    public CoinGeckoService getApiService() {
        return apiService;
    }
    
    /**
     * Gibt den PriceScheduler zurück
     * @return PriceScheduler
     */
    public PriceScheduler getScheduler() {
        return scheduler;
    }
} 