package dev.heezq.trapcraft;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which machines a player has beaten.
 *
 * Only exists to answer one question -- have you won on all four? -- which no
 * single machine can answer about itself. Kept in memory rather than saved: the
 * advancement it feeds is permanent once earned, so the worst a restart can do
 * is ask somebody to win on a machine they have already beaten, and the set is
 * four entries per player.
 */
public final class TrapCasino {
    private static final Map<UUID, Set<String>> BEATEN = new HashMap<>();
    private static final int GAMES = 4;

    private TrapCasino() {
    }

    /** Called when a machine pays a player more than they put in. */
    public static void won(ServerPlayerEntity player, String machine) {
        if (player == null) {
            return;
        }
        Set<String> beaten = BEATEN.computeIfAbsent(player.getUuid(), key -> new HashSet<>());
        if (beaten.add(machine) && beaten.size() >= GAMES) {
            TrapAwards.grant(player, "whole_floor");
        }
    }
}
