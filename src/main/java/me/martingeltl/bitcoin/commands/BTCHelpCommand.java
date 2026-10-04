package me.martingeltl.bitcoin.commands;
import me.martingeltl.bitcoin.BitcoinPrice;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
/** Alias using the same validation, formatting and cache as /btc. */
public final class BTCHelpCommand implements CommandExecutor {
    private final BitcoinPrice plugin;
    public BTCHelpCommand(BitcoinPrice plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 0) { plugin.getMessages().error(sender, "Dieser Alias hat keine Argumente. /btc help"); return true; }
        plugin.getBtcCommand().showHelp(sender);
        return true;
    }
}