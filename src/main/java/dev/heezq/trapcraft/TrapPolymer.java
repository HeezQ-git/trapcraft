package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import net.minecraft.block.BlockState;

import java.util.HashSet;
import java.util.Set;
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
    /**
     * The pool every block that isn't a solid cube draws from.
     *
     * BIOME_TRANSPARENT_BLOCK rather than TRANSPARENT_BLOCK, and one pool
     * rather than two, which is not a preference:
     *
     * <ul>
     *   <li>TRANSPARENT_BLOCK is 37 states on this pack and a directional
     *       block eats four, so the casino alone does not fit in it.
     *   <li>The two pools OVERLAP. Polymer lists spruce leaves in both, and
     *       taking a state out of one does not take it out of the other, so
     *       drawing from both hands the same carrier to two different blocks.
     *       Nothing fails: {@code requestBlock} returns a state, no warning is
     *       logged, and the generated resource pack simply keeps whichever
     *       model was registered last. The blackjack table renders as a
     *       gravity bong and the scratch counter as a mixing station, and the
     *       only place it is visible is in the game.
     *   <li>BIOME_TRANSPARENT_BLOCK is 78 states of the same thing -- leaves,
     *       so full cube, full collision, no face culling -- and the biome
     *       tint only lands on faces carrying a tintindex, which none of the
     *       177 generated models has.
     * </ul>
     *
     * One more property of leaves, learned the hard way: shader packs wave
     * them. Every leaf state is foliage to a shader, so anything carried here
     * sways in the wind -- fine for a bong, absurd for a slot machine. The
     * whole casino floor therefore closed its shells and moved to FULL_BLOCK
     * (note block states, which nothing waves); this pool is only for models
     * that genuinely cannot seal the cube.
     *
     * So: 21 carriers (bong 12, gravity bong 5, mailbox 4) out of one
     * 78-state pool, and the 37-state pool left alone entirely. Anything that
     * needs a see-through carrier uses this constant rather than naming a
     * pool of its own.
     */
    public static final BlockModelType NON_SOLID = BlockModelType.BIOME_TRANSPARENT_BLOCK;

    /**
     * The pool for small props that must hold still.
     *
     * Every carrier in BOTH transparent pools is a leaf -- TRANSPARENT_BLOCK is
     * azalea, flowering azalea, birch and spruce; BIOME_TRANSPARENT_BLOCK is
     * oak, spruce, jungle, acacia, dark oak and mangrove. There is no
     * see-through pool that is not foliage, so "use the other transparent
     * pool" is not an escape from the swaying: it is the same problem with a
     * different tree. A bong swaying reads as the room breathing; a row of
     * medicine bottles swaying on a shelf reads as broken.
     *
     * Trapdoors are the way out. Bottom-half trapdoor states are non-opaque so
     * they cull nothing, they render on cutout exactly like leaves do -- which
     * is what the glassware models are built for -- and no shader pack waves
     * or lights a trapdoor, because a trapdoor is neither a plant nor a lamp.
     * The cost is a small pool: 13 states, against 78 for the leaves. Spend it
     * on things that are visibly wrong when they move, and leave the rest of
     * the trapdoor family (four sides and a top half, 13-16 apiece) for the
     * next one.
     */
    public static final BlockModelType INERT = BlockModelType.BOTTOM_TRAPDOOR;

    /**
     * Every carrier handed out, so the overlap above cannot come back silently.
     * A shared state is invisible from the server side -- both blocks work
     * perfectly and one of them just draws as the other.
     */
    private static final Set<BlockState> HANDED_OUT = new HashSet<>();

    private TrapPolymer() {
    }

    public static BlockState requestOrFallback(BlockModelType type, PolymerBlockModel model,
                                               Supplier<BlockState> fallback, String what) {
        BlockState state = PolymerBlockResourceUtils.requestBlock(type, model);
        if (state != null) {
            if (!HANDED_OUT.add(state)) {
                // Two blocks now share a carrier and the resource pack keeps
                // the last model written, so one of them is drawing as the
                // other. ERROR rather than WARN: it renders wrong for every
                // player and there is no other symptom.
                TrapCraft.LOGGER.error(
                        "Polymer handed out {} twice -- '{}' now shares a carrier with an "
                                + "earlier block and one of them will render as the other. "
                                + "Pools {} overlap; draw them all from TrapPolymer.NON_SOLID.",
                        state, what, type);
            }
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

    /**
     * How many carriers the mod ended up holding, logged after registration.
     *
     * Positive evidence rather than the absence of a warning: the last time
     * this went wrong, every block registered "successfully" and eleven of
     * them quietly shared a carrier with somebody else. A count that does not
     * match {@link #NON_SOLID}'s spend is the first thing to look at.
     */
    public static void logCarriers() {
        TrapCraft.LOGGER.info("TrapCraft holds {} distinct Polymer carriers "
                + "({} left in {})", HANDED_OUT.size(),
                PolymerBlockResourceUtils.getBlocksLeft(NON_SOLID), NON_SOLID);
    }
}
