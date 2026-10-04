package me.martingeltl.bitcoin.scheduler;

import me.martingeltl.bitcoin.BitcoinPrice;
import me.martingeltl.bitcoin.model.PriceQuote;
import me.martingeltl.bitcoin.model.PriceSnapshot;
import me.martingeltl.bitcoin.preferences.DisplayMode;
import me.martingeltl.bitcoin.preferences.Preferences;
import me.martingeltl.bitcoin.presentation.MessageFormatter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Independent chat schedule, demand-driven monitoring and cached UI refresh. Main thread only. */
public final class PriceScheduler {
    private final BitcoinPrice plugin;
    private BukkitTask chatTask, monitorTask, uiTask;
    private boolean polling, chatRequested, running;
    private long generation;
    public PriceScheduler(BitcoinPrice plugin) { this.plugin = plugin; }
    public void startScheduler() {
        stopScheduler(); running = true;
        long chatTicks = plugin.getConfigManager().getPriceInterval() * 60L * 20L;
        chatTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::broadcastBitcoinPrice, chatTicks, chatTicks);
        monitorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> { if (monitorDemand()) poll(false); }, 20L,
                plugin.getConfigManager().getMonitorSeconds() * 20L);
        uiTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshUI, 20L, plugin.getConfigManager().getActionbarSeconds() * 20L);
    }
    public void stopScheduler() {
        generation++; running = false; polling = false; chatRequested = false;
        if (chatTask != null) chatTask.cancel();
        if (monitorTask != null) monitorTask.cancel();
        if (uiTask != null) uiTask.cancel();
        chatTask = monitorTask = uiTask = null;
    }
    public void updateSchedulerInterval(int ignored) { startScheduler(); }
    public void broadcastBitcoinPrice() {
        if (running && chatDemand()) poll(true);
    }
    private boolean receivesChat(Player player) {
        Preferences settings = plugin.getPreferences().get(player.getUniqueId());
        return player.isOnline() && player.hasPermission("bitcoinprice.use") && settings.notifications() && settings.display() == DisplayMode.CHAT;
    }
    private boolean chatDemand() {
        return plugin.getConfigManager().isBroadcastsEnabled() && plugin.getServer().getOnlinePlayers().stream().anyMatch(this::receivesChat);
    }
    private boolean monitorDemand() {
        if (plugin.getBoards().hasActiveBoards()) return true;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.hasPermission("bitcoinprice.use")) continue;
            Preferences settings = plugin.getPreferences().get(player.getUniqueId());
            if (settings.notifications() && settings.display() == DisplayMode.ACTIONBAR || plugin.getPreferences().hasAlerts(player.getUniqueId())) return true;
        }
        return false;
    }
    private void poll(boolean chat) {
        chatRequested |= chat;
        if (polling) return;
        polling = true;
        long requestGeneration = generation;
        plugin.getApiService().fetchBitcoinPrice().whenComplete((quote, error) -> plugin.runSync(() -> {
            if (!running || requestGeneration != generation) return;
            polling = false;
            boolean broadcast = chatRequested; chatRequested = false;
            if (error != null) return; // Service logs rate-limited failures; do not flood players.
            if (broadcast && chatDemand()) {
                broadcastQuote(quote);
            }
            refreshUI();
        }));
    }
    /** A manual admin refresh shares its single fetched quote with eligible chat recipients. */
    public void broadcastQuote(PriceQuote quote) {
        if (!running || !plugin.getConfigManager().isBroadcastsEnabled()) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (receivesChat(player)) player.sendMessage(plugin.getMessages().quote("price", quote,
                    plugin.getMessages().currency(player), plugin.getMessages().locale(player)));
        }
    }
    private void refreshUI() {
        if (!running) return;
        plugin.getApiService().cachedQuote().ifPresentOrElse(quote -> {
            plugin.getBoards().update(quote);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                Preferences settings = plugin.getPreferences().get(player.getUniqueId());
                if (player.isOnline() && player.hasPermission("bitcoinprice.use") && settings.notifications() && settings.display() == DisplayMode.ACTIONBAR)
                    player.sendActionBar(plugin.getMessages().quote("actionbar", quote,
                            plugin.getMessages().currency(player), plugin.getMessages().locale(player)));
            }
        }, () -> plugin.getBoards().showUnavailable());
    }
    /** Called only for fresh service snapshots, including manually requested prices. */
    public void handleFreshSnapshot(PriceSnapshot snapshot) {
        if (!running) return;
        java.time.Instant now = java.time.Instant.now();
        boolean verifiedFresh = MessageFormatter.isFresh(snapshot, plugin.getConfigManager().getApiSettings(), now);
        PriceQuote quote = new PriceQuote(snapshot, !verifiedFresh);
        plugin.getBoards().update(quote);
        if (!verifiedFresh) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || !player.hasPermission("bitcoinprice.use")) continue;
            for (var alert : plugin.getPreferences().checkAlerts(player.getUniqueId(), snapshot)) {
                player.sendMessage(plugin.getMessages().alert(quote, alert, plugin.getMessages().locale(player)));
            }
        }
    }
}
