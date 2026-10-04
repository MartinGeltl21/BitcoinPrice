package org.bitcoinprice.scheduler;

import org.bitcoinprice.BitcoinPrice;
import org.bitcoinprice.model.PriceQuote;
import org.bitcoinprice.model.PriceSnapshot;
import org.bitcoinprice.preferences.DisplayMode;
import org.bitcoinprice.preferences.Preferences;
import org.bitcoinprice.presentation.MessageFormatter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Independent chat schedule, demand-driven monitoring and cached UI refresh. Main thread only. */
public final class PriceScheduler {
    private final BitcoinPrice plugin;
    private BukkitTask chatTask, monitorTask, uiTask;
    private boolean polling, chatRequested, running;
    private long generation;
    private final ActionbarCadence actionbars = new ActionbarCadence();
    private final Set<UUID> actionbarOwners = new HashSet<>();
    public PriceScheduler(BitcoinPrice plugin) { this.plugin = plugin; }
    public void startScheduler() {
        stopScheduler(); running = true;
        startChatTimer();
        monitorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> { if (monitorDemand()) poll(false); }, 20L,
                plugin.getConfigManager().getMonitorSeconds() * 20L);
        uiTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshUI, 20L, 20L);
    }
    public void stopScheduler() {
        generation++; running = false; polling = false; chatRequested = false;
        if (chatTask != null) chatTask.cancel();
        if (monitorTask != null) monitorTask.cancel();
        if (uiTask != null) uiTask.cancel();
        chatTask = monitorTask = uiTask = null;
        for (Player player : plugin.getServer().getOnlinePlayers()) clearActionbar(player);
        actionbarOwners.clear();
        actionbars.clear();
    }
    private void startChatTimer() {
        long chatTicks = plugin.getConfigManager().getPriceInterval() * 60L * 20L;
        chatTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::broadcastBitcoinPrice, chatTicks, chatTicks);
    }
    public void updateSchedulerInterval(int ignored) {
        if (!running) return;
        if (chatTask != null) chatTask.cancel();
        startChatTimer(); // Preserve API callbacks and each player's independent actionbar cadence.
    }
    public void resetActionbar(Player player) {
        clearActionbar(player);
        actionbars.remove(player.getUniqueId());
    }
    private void clearActionbar(Player player) {
        if (actionbarOwners.remove(player.getUniqueId()) && player.isOnline()) player.sendActionBar(Component.empty());
    }
    public void broadcastBitcoinPrice() {
        if (running && chatDemand()) poll(true);
    }
    private boolean receivesChat(Player player) {
        Preferences settings = plugin.getPreferences().get(player.getUniqueId());
        return player.isOnline() && (player.isOp() || player.hasPermission("bitcoinprice.use")) && settings.notifications() && settings.display() == DisplayMode.CHAT;
    }
    private boolean chatDemand() {
        return plugin.getConfigManager().isBroadcastsEnabled() && plugin.getServer().getOnlinePlayers().stream().anyMatch(this::receivesChat);
    }
    private boolean monitorDemand() {
        if (plugin.getBoards().hasActiveBoards()) return true;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOp() && !player.hasPermission("bitcoinprice.use")) continue;
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
            // UI timing is independent: a completed price request must not show an interval actionbar early.
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
        PriceQuote quote = plugin.getApiService().cachedQuote().orElse(null);
        if (quote == null) plugin.getBoards().showUnavailable(); else plugin.getBoards().update(quote);
        Set<UUID> active = new HashSet<>();
        long nowNanos = System.nanoTime();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            active.add(player.getUniqueId());
            refreshActionbar(player, quote, nowNanos);
        }
        actionbars.retain(active);
        actionbarOwners.retainAll(active);
    }
    private void refreshActionbar(Player player, PriceQuote quote, long nowNanos) {
        Preferences settings = plugin.getPreferences().get(player.getUniqueId());
        if (!player.isOnline() || !(player.isOp() || player.hasPermission("bitcoinprice.use"))
                || !settings.notifications() || settings.display() != DisplayMode.ACTIONBAR) {
            resetActionbar(player);
            return;
        }
        if (quote == null) { clearActionbar(player); return; }
        int minutes = settings.actionbarIntervalMinutes() == 0
                ? plugin.getConfigManager().getPriceInterval() : settings.actionbarIntervalMinutes();
        if (actionbars.shouldShow(player.getUniqueId(), settings.actionbarMode(), minutes, nowNanos)) {
            player.sendActionBar(plugin.getMessages().quote("actionbar", quote,
                    plugin.getMessages().currency(player), plugin.getMessages().locale(player)));
            actionbarOwners.add(player.getUniqueId());
        }
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
            if (!player.isOnline() || !player.isOp() && !player.hasPermission("bitcoinprice.use")) continue;
            for (var alert : plugin.getPreferences().checkAlerts(player.getUniqueId(), snapshot)) {
                player.sendMessage(plugin.getMessages().alert(quote, alert, plugin.getMessages().locale(player)));
            }
        }
    }
}
