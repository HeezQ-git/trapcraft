package dev.heezq.trapcraft;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Where somebody may be put down without it looking like the mod did
 * something to them.
 *
 * Every feature here calls a body in -- dealers, hands, tenants, punters,
 * shoppers, buyers, a raid -- and each had grown its own idea of a safe
 * square, from three lines of air checks down to none at all. Several dropped
 * a villager at a fixed offset and hoped. All three failures read as the mod's
 * fault rather than the world's: a hand suffocating in the wall you set the
 * patch against, a dealer stood in the air over the ravine you were mining,
 * a buyer on the surface of a lava lake because the heightmap happily calls
 * lava the top of the world.
 *
 * One answer, three questions: room for the body, room for the head, and
 * something under the feet that will still be there in a second and is not
 * going to burn them.
 *
 * Air is deliberately NOT the test. Half these spawns are indoors, where the
 * floor is a slab or a carpet and the doorway has a torch in it, and "is it
 * air" answers the wrong question in both directions -- it refuses a perfectly
 * good doorstep and accepts standing on nothing at all.
 */
public final class TrapSpawn {

    /**
     * How far the search looks by default.
     *
     * Four blocks: far enough to step out of the wall somebody built against,
     * near enough that the villager still turns up where the player is looking
     * rather than through the door behind them.
     */
    public static final int SEARCH = 4;

    private TrapSpawn() {
    }

    /** Room to stand at {@code pos}, with a floor under it. */
    public static boolean safe(World world, BlockPos pos) {
        return safe(world, pos, 2);
    }

    /**
     * The same, for something taller than a villager.
     *
     * A raid squad includes a ravager, and half-burying one would be worse
     * than skipping it.
     */
    public static boolean safe(World world, BlockPos pos, int height) {
        if (!floor(world, pos.down())) {
            return false;
        }
        for (int up = 0; up < height; up++) {
            if (!clear(world, pos.up(up))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The nearest square to {@code pos} somebody can be stood on, or null.
     *
     * Null rather than a fallback, on purpose. A caller that puts them down
     * anyway has learnt nothing, and "nobody came tonight" is a thing the
     * player can look at and fix; a villager quietly cooking in a lava lake is
     * not. Callers that really must produce a body handle the null themselves.
     */
    public static BlockPos near(World world, BlockPos pos, int radius) {
        return near(world, pos, radius, 2);
    }

    /**
     * The same, for something that does not fit in a villager's doorway.
     *
     * A golem is two and three quarter blocks tall. Stand one in a slot with
     * two blocks of headroom and its EYES are inside the ceiling, which is
     * suffocation damage every tick from a spot the search called safe.
     */
    public static BlockPos near(World world, BlockPos pos, int radius, int height) {
        return BlockPos.findClosest(pos, radius, radius, spot -> safe(world, spot, height))
                .map(BlockPos::toImmutable)
                .orElse(null);
    }

    /** {@link #near} at the usual radius. */
    public static BlockPos near(World world, BlockPos pos) {
        return near(world, pos, SEARCH);
    }

    /** Nothing to walk into, and no fluid to drown or burn in. */
    private static boolean clear(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty()
                && state.getFluidState().isEmpty()
                && !state.isIn(BlockTags.FIRE);
    }

    /**
     * Solid enough to stand on, and cool enough to stand on.
     *
     * Collision shape rather than isSolidBlock: a hand's patch is usually
     * farmland and a shop's doorstep is often a slab or a stair, and none of
     * those are full cubes -- isSolidBlock would have quietly refused to put
     * a farmhand on their own field. Lava and water fail it for free, both
     * having no collision at all, so the only burners left worth naming are
     * the two that genuinely are solid.
     */
    private static boolean floor(World world, BlockPos pos) {
        // ponytail: two named burners, not a general "does this block hurt"
        // query -- there is no such query, only PathNodeType, which needs a
        // mob to ask on behalf of. Add cactus and sweet berries here if anyone
        // ever reports a villager stood in one.
        BlockState state = world.getBlockState(pos);
        return !state.getCollisionShape(world, pos).isEmpty()
                && !state.isOf(Blocks.MAGMA_BLOCK)
                && !state.isIn(BlockTags.CAMPFIRES);
    }
}
