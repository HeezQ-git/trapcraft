package dev.heezq.trapcraft;

import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * What container is actually at a position.
 *
 * <h2>The bug this exists to stop</h2>
 *
 * {@code world.getBlockEntity(pos)} on a double chest returns ONE HALF of it:
 * a 27-slot {@code ChestBlockEntity}, never the 54-slot pair. Every reader in
 * this mod was doing that, so a crew hand filling a double chest declared it
 * full at half capacity and started throwing the harvest on the floor, and a
 * shop stocked out of one stopped seeing anything past slot 27.
 *
 * Nothing about that presents as a chest problem. It reads as "the hand is
 * broken" or "my shop won't sell the thing I definitely stocked", which is a
 * long way from the chest being the wrong size.
 *
 * <h2>Why the hopper's own resolver</h2>
 *
 * Because "what container is at this block" already has a right answer in
 * vanilla and a hopper computes it every tick. It joins double chests, honours
 * {@code InventoryProvider} blocks like the composter, and finds a chest
 * minecart parked on the square. Reimplementing two of those three and
 * forgetting the rest is how this class would become the next bug.
 */
public final class TrapBoxes {

    private TrapBoxes() {
    }

    /**
     * The whole container at this position, both halves of a double chest
     * included, or null if there isn't one.
     *
     * Costs an entity lookup, because a chest minecart is a container too.
     * Cheap enough for a shop screen or a crew pass; do NOT call it on every
     * square of a wide scan -- find the candidate with
     * {@code getBlockEntity} first and resolve the winner through here once.
     */
    public static Inventory at(World world, BlockPos pos) {
        return HopperBlockEntity.getInventoryAt(world, pos);
    }
}
