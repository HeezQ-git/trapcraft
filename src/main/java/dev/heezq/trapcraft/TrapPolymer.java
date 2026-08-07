package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import net.minecraft.block.BlockState;

import java.util.function.Supplier;

/**
 * Polymer hands out unused vanilla blockstates from a finite pool, and returns
 * null when a pool runs dry -- which on a 136-mod pack with two other Polymer
 * mods is a real possibility, not a theoretical one.
 *
 * getPolymerBlockState() must NEVER return null: Polymer's collision mixin
 * dereferences it while the block's shape cache is built during registration,
 * so a null takes the whole server down at boot rather than degrading.
 */
public final class TrapPolymer {
    private TrapPolymer() {
    }

    public static BlockState requestOrFallback(BlockModelType type, PolymerBlockModel model,
                                               Supplier<BlockState> fallback, String what) {
        BlockState state = PolymerBlockResourceUtils.requestBlock(type, model);
        if (state != null) {
            return state;
        }
        BlockState degraded = fallback.get();
        TrapCraft.LOGGER.warn(
                "Polymer {} pool is empty ({} left) -- '{}' falls back to {}. "
                        + "It will work but show a vanilla texture.",
                type, PolymerBlockResourceUtils.getBlocksLeft(type), what, degraded.getBlock());
        return degraded;
    }

    /**
     * Logged once at startup so pool pressure is visible before it bites. On
     * this pack PLANT_BLOCK is already down to single digits thanks to the
     * other two Polymer mods, so which pool a block draws from matters.
     */
    public static void logPools() {
        for (BlockModelType type : BlockModelType.values()) {
            TrapCraft.LOGGER.info("Polymer pool {} = {}",
                    type, PolymerBlockResourceUtils.getBlocksLeft(type));
        }
    }
}
