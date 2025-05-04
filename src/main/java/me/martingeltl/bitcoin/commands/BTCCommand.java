package me.martingeltl.bitcoin.commands;

import me.martingeltl.bitcoin.BitcoinPrice;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Handler für den Hauptbefehl /btc
 */
public class BTCCommand implements CommandExecutor {

    private final BitcoinPrice plugin;
    // Set zur Speicherung von Spielern, die keine BTC-Preis Nachrichten erhalten möchten
    private static final Set<UUID> disabledPlayers = new HashSet<>();
    // Flag, ob Broadcasts für alle deaktiviert sind
    private static boolean broadcastsDisabled = false;
    
    public BTCCommand(BitcoinPrice plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("help")) {
                sendHelpMessage(sender);
                return true;
            } else if (args[0].equalsIgnoreCase("interval")) {
                if (args.length > 1) {
                    try {
                        int interval = Integer.parseInt(args[1]);
                        return handleIntervalChange(sender, interval);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                                ChatColor.RED + "Bitte gib ein gültiges Intervall an.");
                        return false;
                    }
                } else {
                    sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                            "Aktuelles Intervall: " + plugin.getConfigManager().getPriceInterval() + " Minute(n)");
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("currency")) {
                if (args.length > 1) {
                    return handleCurrencyChange(sender, args[1].toUpperCase());
                } else {
                    sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                            "Aktuelle Währung: " + plugin.getConfigManager().getPriceCurrency());
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("refresh")) {
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        "Aktualisiere Bitcoin-Preis...");
                plugin.getScheduler().broadcastBitcoinPrice();
                return true;
            } else if (args[0].equalsIgnoreCase("on")) {
                return handleEnableNotifications(sender, args);
            } else if (args[0].equalsIgnoreCase("off")) {
                return handleDisableNotifications(sender, args);
            }
        }
        
        // Statt Hauptmenü anzeigen, den aktuellen Bitcoin-Preis abrufen
        fetchCurrentPrice(sender);
        return true;
    }
    
    /**
     * Aktiviert Bitcoin-Preis Benachrichtigungen
     * @param sender Der CommandSender
     * @param args Die Befehlsargumente
     * @return true, wenn der Befehl erfolgreich war
     */
    private boolean handleEnableNotifications(CommandSender sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("all")) {
            if (sender.hasPermission("bitcoinprice.admin")) {
                broadcastsDisabled = false;
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        ChatColor.GREEN + "Bitcoin-Preis Benachrichtigungen wurden für alle Spieler aktiviert.");
                return true;
            } else {
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        ChatColor.RED + "Du hast keine Berechtigung, diesen Befehl auszuführen.");
                return false;
            }
        } else {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                disabledPlayers.remove(player.getUniqueId());
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        ChatColor.GREEN + "Bitcoin-Preis Benachrichtigungen wurden für dich aktiviert.");
                return true;
            } else {
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        ChatColor.RED + "Dieser Befehl kann nur von Spielern ausgeführt werden.");
                return false;
            }
        }
    }
    
    /**
     * Deaktiviert Bitcoin-Preis Benachrichtigungen
     * @param sender Der CommandSender
     * @param args Die Befehlsargumente
     * @return true, wenn der Befehl erfolgreich war
     */
    private boolean handleDisableNotifications(CommandSender sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("all")) {
            if (sender.hasPermission("bitcoinprice.admin")) {
                broadcastsDisabled = true;
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        ChatColor.GREEN + "Bitcoin-Preis Benachrichtigungen wurden für alle Spieler deaktiviert.");
                return true;
            } else {
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        ChatColor.RED + "Du hast keine Berechtigung, diesen Befehl auszuführen.");
                return false;
            }
        } else {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                disabledPlayers.add(player.getUniqueId());
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        ChatColor.GREEN + "Bitcoin-Preis Benachrichtigungen wurden für dich deaktiviert.");
                return true;
            } else {
                sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                        ChatColor.RED + "Dieser Befehl kann nur von Spielern ausgeführt werden.");
                return false;
            }
        }
    }
    
    /**
     * Ruft den aktuellen Bitcoin-Preis ab basierend auf der eingestellten Währung
     * @param sender Der CommandSender
     */
    private void fetchCurrentPrice(CommandSender sender) {
        String currency = plugin.getConfigManager().getPriceCurrency();
        
        sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                "Rufe aktuellen Bitcoin-Preis ab...");
        
        plugin.getApiService().fetchBitcoinPrice().thenAccept(response -> {
            String message = null;
            String messagePrefix = plugin.getConfigManager().getMessagePrefix();
            String priceColor = plugin.getConfigManager().getPriceColor();
            
            try {
                if (currency.equals("EUR")) {
                    String priceEUR = plugin.getApiService().extractPrice(response, "eur");
                    message = messagePrefix + "Bitcoin-Preis: " + priceColor + priceEUR + " €";
                } else if (currency.equals("USD")) {
                    String priceUSD = plugin.getApiService().extractPrice(response, "usd");
                    message = messagePrefix + "Bitcoin-Preis: " + priceColor + priceUSD + " $";
                } else if (currency.equals("BOTH")) {
                    String priceEUR = plugin.getApiService().extractPrice(response, "eur");
                    String priceUSD = plugin.getApiService().extractPrice(response, "usd");
                    message = messagePrefix + "Bitcoin-Preis: " + priceColor + priceEUR + " € / " + priceUSD + " $";
                }
                
                final String finalMessage = message;
                if (finalMessage != null) {
                    // Sende die Nachricht synchron zur Hauptthread
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(finalMessage);
                    });
                }
            } catch (Exception e) {
                // Fehlermeldung synchron zur Hauptthread senden
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                            plugin.getConfigManager().getApiErrorMessage());
                    plugin.getLogger().warning("Fehler beim Erstellen der Bitcoin-Preis Nachricht: " + e.getMessage());
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
    }
    
    /**
     * Sendet die Hilfsnachricht
     * @param sender Der CommandSender
     */
    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== BitcoinPrice Plugin ===");
        sender.sendMessage(ChatColor.YELLOW + "Aktuelles Intervall: " + 
                ChatColor.WHITE + plugin.getConfigManager().getPriceInterval() + " Minute(n)");
        sender.sendMessage(ChatColor.YELLOW + "Aktuelle Währung: " + 
                ChatColor.WHITE + plugin.getConfigManager().getPriceCurrency());
        sender.sendMessage(ChatColor.YELLOW + "Befehle:");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc" + 
                ChatColor.GRAY + " - Zeigt den aktuellen Bitcoin-Preis an");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc help" + 
                ChatColor.GRAY + " - Zeigt diese Hilfe an");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc interval <1|5|10|30|60>" + 
                ChatColor.GRAY + " - Ändert das Update-Intervall");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc currency <EUR|USD|BOTH>" + 
                ChatColor.GRAY + " - Ändert die Währung");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc refresh" + 
                ChatColor.GRAY + " - Aktualisiert den Bitcoin-Preis sofort");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc on" + 
                ChatColor.GRAY + " - Aktiviert Bitcoin-Preis Benachrichtigungen für dich");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc off" + 
                ChatColor.GRAY + " - Deaktiviert Bitcoin-Preis Benachrichtigungen für dich");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc on all" + 
                ChatColor.GRAY + " - Aktiviert Bitcoin-Preis Benachrichtigungen für alle Spieler");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc off all" + 
                ChatColor.GRAY + " - Deaktiviert Bitcoin-Preis Benachrichtigungen für alle Spieler");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btceur" + 
                ChatColor.GRAY + " - Zeigt den Bitcoin-Preis in Euro an");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btcusd" + 
                ChatColor.GRAY + " - Zeigt den Bitcoin-Preis in US-Dollar an");
    }
    
    /**
     * Verarbeitet die Änderung des Intervalls
     * @param sender Der CommandSender
     * @param interval Das neue Intervall in Minuten
     * @return true, wenn das Intervall gültig ist, sonst false
     */
    private boolean handleIntervalChange(CommandSender sender, int interval) {
        if (plugin.getConfigManager().setPriceInterval(interval)) {
            plugin.getScheduler().updateSchedulerInterval(interval);
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                    ChatColor.GREEN + "Intervall wurde auf " + interval + " Minute(n) geändert.");
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                    ChatColor.RED + "Ungültiges Intervall. Erlaubte Werte: 1, 5, 10, 30, 60");
            return false;
        }
    }
    
    /**
     * Verarbeitet die Änderung der Währung
     * @param sender Der CommandSender
     * @param currency Die neue Währung (EUR, USD oder BOTH)
     * @return true, wenn die Währung gültig ist, sonst false
     */
    private boolean handleCurrencyChange(CommandSender sender, String currency) {
        if (plugin.getConfigManager().setPriceCurrency(currency)) {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                    ChatColor.GREEN + "Währung wurde auf " + currency + " geändert.");
            return true;
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessagePrefix() + 
                    ChatColor.RED + "Ungültige Währung. Erlaubte Werte: EUR, USD, BOTH");
            return false;
        }
    }
    
    /**
     * Prüft, ob ein Spieler Bitcoin-Preis Benachrichtigungen erhalten möchte
     * @param player Der Spieler
     * @return true, wenn der Spieler Benachrichtigungen erhalten möchte, sonst false
     */
    public static boolean shouldReceiveMessages(Player player) {
        return !disabledPlayers.contains(player.getUniqueId()) && !broadcastsDisabled;
    }
} 