package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * The tick that drives the casino floor.
 *
 * Screen handlers get no tick of their own, so anything that animates while a
 * player watches it needs somebody else to drive it. One shared list rather
 * than one per machine: the slot machine and the roulette table want exactly
 * the same thing, and a second copy of this is a second place to forget to
 * remove a finished game from.
 */
public final class TrapTables {

    /** Anything mid-animation. Returns false when it has nothing left to draw. */
    public interface Playing {
        boolean tick();
    }

    private static final List<Playing> RUNNING = new ArrayList<>();

    private TrapTables() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!RUNNING.isEmpty()) {
                RUNNING.removeIf(game -> !game.tick());
            }
        });
    }

    /** Start ticking a game that has just been set going. */
    public static void watch(Playing game) {
        if (!RUNNING.contains(game)) {
            RUNNING.add(game);
        }
    }
}
