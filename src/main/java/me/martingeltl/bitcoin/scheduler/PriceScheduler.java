package me.martingeltl.bitcoin.scheduler;

import me.martingeltl.bitcoin.BitcoinPrice;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.json.JSONObject;

/**
 * Scheduler für die regelmäßige Anzeige des Bitcoin-Preises
 */
public class PriceScheduler {

    private final BitcoinPrice plugin;
    private BukkitTask schedulerTask;
    
    public PriceScheduler(BitcoinPrice plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Startet den Scheduler für die regelmäßige Anzeige des Bitcoin-Preises
     */
    public void startScheduler() {
        stopScheduler(); // Stoppt den Scheduler, falls er bereits läuft
        
        int intervalInMinutes = plugin.getConfigManager().getPriceInterval();
        long intervalInTicks = intervalInMinutes * 60 * 20; // Umrechnung von Minuten in Ticks (20 Ticks = 1 Sekunde)
        
        schedulerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::broadcastBitcoinPrice, 20 * 5, intervalInTicks);
        plugin.getLogger().info("Bitcoin-Preis Scheduler gestartet. Intervall: " + intervalInMinutes + " Minute(n)");
    }
    
    /**
     * Stoppt den Scheduler
     */
    public void stopScheduler() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
    }
    
    /**
     * Sendet den aktuellen Bitcoin-Preis an alle Spieler
     */
    public void broadcastBitcoinPrice() {
        plugin.getApiService().fetchBitcoinPrice().thenAccept(response -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                String priceCurrency = plugin.getConfigManager().getPriceCurrency();
                String message = createBitcoinPriceMessage(response, priceCurrency);
                
                if (message != null) {
                    Bukkit.getServer().getOnlinePlayers().forEach(player -> {
                        // Prüfen, ob der Spieler die Nachrichten erhalten möchte
                        if (me.martingeltl.bitcoin.commands.BTCCommand.shouldReceiveMessages(player)) {
                            player.sendMessage(message);
                        }
                    });
                    plugin.getLogger().info("Bitcoin-Preis wurde an alle berechtigten Spieler gesendet.");
                }
            });
        }).exceptionally(e -> {
            plugin.getLogger().warning("Fehler beim Abrufen des Bitcoin-Preises: " + e.getMessage());
            return null;
        });
    }
    
    /**
     * Erstellt die Nachricht mit dem Bitcoin-Preis basierend auf der Währungseinstellung
     * @param response Die JSON-Antwort von der CoinGecko API
     * @param currency Die Währung (EUR, USD oder BOTH)
     * @return Die formatierte Nachricht
     */
    private String createBitcoinPriceMessage(JSONObject response, String currency) {
        String messagePrefix = plugin.getConfigManager().getMessagePrefix();
        String priceColor = plugin.getConfigManager().getPriceColor();
        
        try {
            if (currency.equals("EUR")) {
                String priceEUR = plugin.getApiService().extractPrice(response, "eur");
                return messagePrefix + "Bitcoin-Preis: " + priceColor + priceEUR + " €";
            } else if (currency.equals("USD")) {
                String priceUSD = plugin.getApiService().extractPrice(response, "usd");
                return messagePrefix + "Bitcoin-Preis: " + priceColor + priceUSD + " $";
            } else if (currency.equals("BOTH")) {
                String priceEUR = plugin.getApiService().extractPrice(response, "eur");
                String priceUSD = plugin.getApiService().extractPrice(response, "usd");
                return messagePrefix + "Bitcoin-Preis: " + priceColor + priceEUR + " € / " + priceUSD + " $";
            }
            
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("Fehler beim Erstellen der Bitcoin-Preis Nachricht: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Aktualisiert den Scheduler mit einem neuen Intervall
     * @param intervalInMinutes Das neue Intervall in Minuten
     */
    public void updateSchedulerInterval(int intervalInMinutes) {
        stopScheduler();
        startScheduler();
        
        plugin.getLogger().info("Bitcoin-Preis Scheduler aktualisiert. Neues Intervall: " + intervalInMinutes + " Minute(n)");
    }
} 