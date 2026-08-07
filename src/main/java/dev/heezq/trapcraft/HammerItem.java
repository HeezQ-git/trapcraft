package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Breaks a 3x3 plane facing you: the block you mined plus its eight
 * neighbours, on the plane perpendicular to whichever way you're looking.
 *
 * Nothing to do with the rest of the mod -- it just rides the same jar and
 * deploy pipeline rather than being a second mod to build and ship.
 */
public class HammerItem extends Item implements PolymerItem {
    /**
     * Re-entrancy guard, and the single most important line in this class.
     *
     * tryBreakBlock() runs the full player-break path, which calls postMine()
     * on the held item -- this item. Without the flag, each of the eight
     * neighbours would expand into its own 3x3 and recurse until the server
     * died. Static is fine: block breaking is server-thread only.
     */
    private static boolean breaking = false;

    private final Identifier model;

    public HammerItem(Settings settings, Identifier model) {
        super(settings);
        this.model = model;
    }

    @Override
    public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity user) {
        boolean result = super.postMine(stack, world, state, pos, user);

        if (breaking
                || !(world instanceof ServerWorld server)
                || !(user instanceof ServerPlayerEntity player)
                // Sneak for a single block -- you need that for careful work,
                // and it's the convention every other 3x3 tool uses.
                || player.isSneaking()) {
            return result;
        }

        Direction facing = facing(player);
        Direction[] plane = planeAxes(facing);
        int broken = 0;

        breaking = true;
        try {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) {
                        continue; // the block you actually hit, already gone
                    }
                    BlockPos target = pos.offset(plane[0], a).offset(plane[1], b);
                    if (canBreak(stack, server, target)) {
                        // Break as the player: correct drops, XP, enchantments
                        // and tool damage, all without reimplementing them.
                        if (player.interactionManager.tryBreakBlock(target)) {
                            broken++;
                        }
                    }
                    if (stack.isEmpty()) {
                        break; // hammer broke mid-swing
                    }
                }
            }
        } finally {
            breaking = false;
        }

        if (broken > 0) {
            effects(server, pos);
        }
        return result;
    }

    /** The 3x3 sits perpendicular to the axis you're most looking along. */
    private static Direction facing(ServerPlayerEntity player) {
        Vec3d look = player.getRotationVector();
        return Direction.getFacing(look.x, look.y, look.z);
    }

    private static Direction[] planeAxes(Direction facing) {
        return switch (facing.getAxis()) {
            case Y -> new Direction[]{Direction.EAST, Direction.NORTH};
            case X -> new Direction[]{Direction.UP, Direction.NORTH};
            case Z -> new Direction[]{Direction.UP, Direction.EAST};
        };
    }

    private static boolean canBreak(ItemStack stack, ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        // Negative hardness is unbreakable: bedrock, barriers, portal frames.
        if (state.getHardness(world, pos) < 0) {
            return false;
        }
        // Blocks that need a tool only go if this hammer is actually the right
        // one; blocks that don't (dirt, sand, gravel) always go. Without the
        // second half a "pickaxe" would refuse to clear the dirt around an ore.
        return !state.isToolRequired() || stack.isSuitableFor(state);
    }

    private static void effects(ServerWorld world, BlockPos pos) {
        world.spawnParticles(ParticleTypes.CRIT,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                14, 0.7, 0.7, 0.7, 0.06);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                6, 0.5, 0.5, 0.5, 0.02);
        // Deliberately not a stone-break sound -- a heavy, low anvil clunk so a
        // hammer swing is audible as different from ordinary mining.
        world.playSound(null, pos, SoundEvents.BLOCK_ANVIL_LAND,
                SoundCategory.BLOCKS, 0.35F, 0.7F);
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.DIAMOND_PICKAXE;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }
}
