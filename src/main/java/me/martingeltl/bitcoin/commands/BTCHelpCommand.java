package me.martingeltl.bitcoin.commands;

import me.martingeltl.bitcoin.BitcoinPrice;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Handler für den Befehl /btchelp
 */
public class BTCHelpCommand implements CommandExecutor {

    private final BitcoinPrice plugin;
    
    public BTCHelpCommand(BitcoinPrice plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sendHelpMessage(sender);
        return true;
    }
    
    /**
     * Sendet die Hilfsnachricht
     * @param sender Der CommandSender
     */
    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== BitcoinPrice Plugin - Hilfe ===");
        sender.sendMessage(ChatColor.YELLOW + "Befehle:");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc" + 
                ChatColor.GRAY + " - Zeigt das Hauptmenü an");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc help" + 
                ChatColor.GRAY + " - Zeigt diese Hilfe an");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc interval <1|5|10|30|60>" + 
                ChatColor.GRAY + " - Ändert das Update-Intervall");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc currency <EUR|USD|BOTH>" + 
                ChatColor.GRAY + " - Ändert die Währung");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btc refresh" + 
                ChatColor.GRAY + " - Aktualisiert den Bitcoin-Preis sofort");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btceur" + 
                ChatColor.GRAY + " - Zeigt den Bitcoin-Preis in Euro an");
        sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.WHITE + "/btcusd" + 
                ChatColor.GRAY + " - Zeigt den Bitcoin-Preis in US-Dollar an");
        sender.sendMessage(ChatColor.GOLD + "=================================");
    }
} 