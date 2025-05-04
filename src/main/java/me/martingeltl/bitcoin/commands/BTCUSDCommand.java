package me.martingeltl.bitcoin.commands;

import me.martingeltl.bitcoin.BitcoinPrice;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Handler für den Befehl /btcusd
 */
public class BTCUSDCommand implements CommandExecutor {

    private final BitcoinPrice plugin;
    
    public BTCUSDCommand(BitcoinPrice plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                "Rufe aktuellen Bitcoin-Preis in US-Dollar ab...");
        
        plugin.getApiService().fetchBitcoinPrice().thenAccept(response -> {
            String priceUSD = plugin.getApiService().extractPrice(response, "usd");
            
            if (priceUSD != null && !priceUSD.equals("N/A")) {
                String messagePrefix = plugin.getConfigManager().getMessagePrefix();
                String priceColor = plugin.getConfigManager().getPriceColor();
                String message = messagePrefix + "Bitcoin-Preis: " + priceColor + priceUSD + " $";
                
                // Sende die Nachricht synchron zur Hauptthread
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(message);
                });
            } else {
                // Fehlermeldung synchron zur Hauptthread senden
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                            plugin.getConfigManager().getApiErrorMessage());
                });
            }
        }).exceptionally(e -> {
            // Fehlermeldung synchron zur Hauptthread senden
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        plugin.getConfigManager().getApiErrorMessage());
                plugin.getLogger().warning("Fehler beim Abrufen des Bitcoin-Preises: " + e.getMessage());
            });
            return null;
        });
        
        return true;
    }
} 