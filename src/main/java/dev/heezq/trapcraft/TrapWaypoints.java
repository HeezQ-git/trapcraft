package dev.heezq.trapcraft;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Handing a place to somebody's minimap.
 *
 * Xaero's Minimap reads waypoints out of CHAT. A message containing
 * `xaero-waypoint:` reaches its share handler and the player gets a clickable
 * prompt to add it. That is the whole protocol -- there is no packet, no API
 * and no way for a server to detect who has the mod installed.
 *
 * Which is why this is used sparingly and only where a coordinate is genuinely
 * the point. Anyone without Xaero's sees a line of raw text, and a mod that
 * sprays those into chat is worse than one that doesn't bother.
 *
 * The exact field order was worked out by testing three candidates against a
 * real client, because the jar only tells you the prefix and the separator:
 *
 *   xaero-waypoint:name:initial:x:y:z:colour:disabled:type:Internal-dimension
 */
public final class TrapWaypoints {

    /** Xaero's colour wheel, 0..15. */
    public static final int GOLD = 4;
    public static final int RED = 12;
    public static final int GREEN = 10;

    private TrapWaypoints() {
    }

    /**
     * Offer a waypoint. The player clicks to accept it.
     *
     * @param label what to call it. Colons are stripped -- they're the
     *              separator, and a name containing one silently truncates
     *              everything after it.
     */
    public static void offer(ServerPlayerEntity player, String label, BlockPos where, int colour) {
        String clean = label.replace(':', ' ').trim();
        String initial = clean.isEmpty() ? "?" : clean.substring(0, 1);
        String dimension = player.getWorld().getRegistryKey().getValue().getPath();

        player.sendMessage(Text.literal("xaero-waypoint:" + clean + ":" + initial + ":"
                + where.getX() + ":" + where.getY() + ":" + where.getZ() + ":"
                + colour + ":false:0:Internal-" + dimension), false);
    }
}
