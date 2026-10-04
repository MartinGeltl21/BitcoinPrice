package org.bitcoinprice.scheduler;

import org.bitcoinprice.preferences.ActionbarMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Monotonic, per-player display timing. Price requests never advance this clock. */
public final class ActionbarCadence {
    private final Map<UUID, State> players = new HashMap<>();

    public boolean shouldShow(UUID player, ActionbarMode mode, int minutes, long nowNanos) {
        if (minutes < 1 || minutes > 60) throw new IllegalArgumentException("Invalid actionbar interval");
        State old = players.get(player);
        if (mode == ActionbarMode.CONTINUOUS) {
            players.put(player, new State(mode, nowNanos));
            return true;
        }
        if (old == null || old.mode() != mode || nowNanos - old.lastShown() >= TimeUnit.MINUTES.toNanos(minutes)) {
            players.put(player, new State(mode, nowNanos));
            return true;
        }
        return false;
    }

    public void remove(UUID player) { players.remove(player); }
    public void retain(Set<UUID> active) { players.keySet().retainAll(active); }
    public void clear() { players.clear(); }
    private record State(ActionbarMode mode, long lastShown) { }
}
