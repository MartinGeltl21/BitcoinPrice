package org.bitcoinprice.commands;
import org.bitcoinprice.BitcoinPrice;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import java.util.List;
/** Help alias delegates all validation, pagination and completion to /btc help. */
public final class BTCHelpCommand implements CommandExecutor, TabCompleter {
    private final BitcoinPrice plugin;
    public BTCHelpCommand(BitcoinPrice plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return plugin.getBtcCommand().onCommand(sender, command, "btc", helpArgs(args));
    }
    private static String[] helpArgs(String[] args) {
        String[] delegated = new String[args.length + 1];
        delegated[0] = "help";
        System.arraycopy(args, 0, delegated, 1, args.length);
        return delegated;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return plugin.getBtcCommand().onTabComplete(sender, command, "btc", helpArgs(args));
    }
}
