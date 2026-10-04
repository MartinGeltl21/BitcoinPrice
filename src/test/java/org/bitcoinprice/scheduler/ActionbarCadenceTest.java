package org.bitcoinprice.scheduler;

import org.bitcoinprice.preferences.ActionbarMode;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ActionbarCadenceTest {
    private static long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }

    @Test void intervalRemainsQuietBetweenDeadlinesAndSkipsMissedBursts() {
        var cadence = new ActionbarCadence(); var player = UUID.randomUUID();
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(0)));
        for (int second = 1; second < 60; second++)
            assertFalse(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(second)));
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(60)));
        assertFalse(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(60)));
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(600)));
        assertFalse(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(601)));
    }

    @Test void simultaneousPlayersHaveIndependentModesAndIntervals() {
        var cadence = new ActionbarCadence(); var continuous = UUID.randomUUID();
        var shortInterval = UUID.randomUUID(); var longInterval = UUID.randomUUID();
        assertTrue(cadence.shouldShow(shortInterval, ActionbarMode.INTERVAL, 1, 0));
        assertTrue(cadence.shouldShow(longInterval, ActionbarMode.INTERVAL, 5, 0));
        for (int second = 0; second <= 60; second++)
            assertTrue(cadence.shouldShow(continuous, ActionbarMode.CONTINUOUS, 1, seconds(second)));
        assertTrue(cadence.shouldShow(shortInterval, ActionbarMode.INTERVAL, 1, seconds(60)));
        assertFalse(cadence.shouldShow(longInterval, ActionbarMode.INTERVAL, 5, seconds(60)));
        assertTrue(cadence.shouldShow(longInterval, ActionbarMode.INTERVAL, 5, seconds(300)));
    }

    @Test void changedDefaultUsesElapsedTimeWithoutResettingOtherPlayers() {
        var cadence = new ActionbarCadence(); var player = UUID.randomUUID();
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 10, 0));
        assertFalse(cadence.shouldShow(player, ActionbarMode.INTERVAL, 5, seconds(299)));
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 5, seconds(300)));
        assertFalse(cadence.shouldShow(player, ActionbarMode.INTERVAL, 10, seconds(600)));
    }

    @Test void modeChangeAndRejoinShowOnceImmediately() {
        var cadence = new ActionbarCadence(); var player = UUID.randomUUID();
        assertTrue(cadence.shouldShow(player, ActionbarMode.CONTINUOUS, 1, 0));
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(1)));
        cadence.retain(Set.of());
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(2)));
        cadence.remove(player);
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(3)));
        cadence.clear();
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, seconds(4)));
    }

    @Test void invalidIntervalDoesNotAdvanceState() {
        var cadence = new ActionbarCadence(); var player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> cadence.shouldShow(player, ActionbarMode.INTERVAL, 0, 0));
        assertTrue(cadence.shouldShow(player, ActionbarMode.INTERVAL, 1, 0));
    }
}
