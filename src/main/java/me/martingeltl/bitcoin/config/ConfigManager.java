package me.martingeltl.bitcoin.config;

import me.martingeltl.bitcoin.BitcoinPrice;
import org.bukkit.ChatColor;

/**
 * Verwaltet die Konfiguration des Plugins
 */
public class ConfigManager {

    private final BitcoinPrice plugin;
    
    // Konfigurationseinstellungen
    private int priceInterval;
    private String priceCurrency;
    private String apiUrl;
    private int apiTimeout;
    private String messagePrefix;
    private String priceColor;
    private String apiErrorMessage;
    
    public ConfigManager(BitcoinPrice plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    /**
     * Lädt die Konfiguration aus der config.yml
     */
    public void loadConfig() {
        this.priceInterval = plugin.getConfig().getInt("price-interval", 10);
        this.priceCurrency = plugin.getConfig().getString("price-currency", "EUR");
        this.apiUrl = plugin.getConfig().getString("api.url", "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur,usd");
        this.apiTimeout = plugin.getConfig().getInt("api.timeout", 5000);
        this.messagePrefix = ChatColor.translateAlternateColorCodes('&', 
                plugin.getConfig().getString("messages.prefix", "&6[BitcoinPrice] &r"));
        this.priceColor = ChatColor.translateAlternateColorCodes('&', 
                plugin.getConfig().getString("messages.price-color", "&6"));
        this.apiErrorMessage = ChatColor.translateAlternateColorCodes('&', 
                plugin.getConfig().getString("messages.api-error", "&cFehler beim Abrufen des Bitcoin-Preises. Bitte versuche es später erneut."));
    }
    
    /**
     * Speichert die Konfiguration in die config.yml
     */
    public void saveConfig() {
        plugin.getConfig().set("price-interval", priceInterval);
        plugin.getConfig().set("price-currency", priceCurrency);
        plugin.saveConfig();
        loadConfig();
    }
    
    /**
     * Setzt das Intervall für die automatische Preisanzeige
     * @param minutes Minuten (1, 5, 10, 30, 60)
     * @return true, wenn das Intervall gültig ist, sonst false
     */
    public boolean setPriceInterval(int minutes) {
        if (minutes == 1 || minutes == 5 || minutes == 10 || minutes == 30 || minutes == 60) {
            this.priceInterval = minutes;
            saveConfig();
            return true;
        }
        return false;
    }
    
    /**
     * Setzt die Währung für die Preisanzeige
     * @param currency Währung (EUR, USD, BOTH)
     * @return true, wenn die Währung gültig ist, sonst false
     */
    public boolean setPriceCurrency(String currency) {
        if (currency.equals("EUR") || currency.equals("USD") || currency.equals("BOTH")) {
            this.priceCurrency = currency;
            saveConfig();
            return true;
        }
        return false;
    }
    
    // Getter-Methoden
    
    public int getPriceInterval() {
        return priceInterval;
    }
    
    public String getPriceCurrency() {
        return priceCurrency;
    }
    
    public String getApiUrl() {
        return apiUrl;
    }
    
    public int getApiTimeout() {
        return apiTimeout;
    }
    
    public String getMessagePrefix() {
        return messagePrefix;
    }
    
    public String getPriceColor() {
        return priceColor;
    }
    
    public String getApiErrorMessage() {
        return apiErrorMessage;
    }
} 