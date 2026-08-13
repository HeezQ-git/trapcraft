package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step one of the coca line: leaves in, paste out.
 *
 * Same blockstate-machine shape as {@link DryingRackBlock} -- no BlockEntity,
 * because a boolean and a counter fit in the state. Unlike the drying rack
 * there's no window to miss here; pressing is the boring reliable step, and
 * the actual decision lives in the refiner.
 */
public class LeafPressBlock extends TurnableBlock implements PolymerTexturedBlock {
    public static final MapCodec<LeafPressBlock> CODEC = createCodec(LeafPressBlock::new);

    public static final BooleanProperty LOADED = BooleanProperty.of("loaded");
    public static final int DONE = 3;
    public static final IntProperty PROGRESS = IntProperty.of("progress", 0, DONE);

    /** Leaves consumed per batch. */
    /**
     * Leaves one batch of paste takes.
     *
     * Raised from three when the coca line turned out to be earning about five
     * times what weed does per plant-minute. A bush drops two to four leaves,
     * so a batch is now two bushes rather than one -- which is also the honest
     * fiction, since the whole point of the refined line is that it needs a
     * FIELD behind it rather than a window box.
     */
    public static final int LEAVES_PER_BATCH = 5;
    private static final int STEP_TICKS = 600;   // 30s a step, 90s total

    private final Map<Direction, BlockState> emptyCarriers;
    private final List<Map<Direction, BlockState>> workingCarriers = new ArrayList<>();

    public LeafPressBlock(Settings settings) {
        super(settings);
        this.setDefaultState(getDefaultState()
                .with(LOADED, false).with(PROGRESS, 0));

        // Four carriers per model rather than one: a press with a screw handle
        // on the front is worth turning to face the room.
        this.emptyCarriers = carriers(BlockModelType.FULL_BLOCK, "leaf_press_empty",
                () -> Blocks.SMOOTH_STONE.getDefaultState());
        for (int step = 0; step <= DONE; step++) {
            this.workingCarriers.add(carriers(BlockModelType.FULL_BLOCK,
                    "leaf_press_" + step, () -> Blocks.SMOOTH_STONE.getDefaultState()));
        }
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(LOADED, PROGRESS);
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
                                         PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (state.get(LOADED)) {
            return collect(state, world, pos, player);
        }
        if (!stack.isOf(TrapContent.cocaLeaves)) {
            return ActionResult.PASS;
        }
        if (stack.getCount() < LEAVES_PER_BATCH) {
            if (!world.isClient) {
                player.sendMessage(Text.literal("Potrzeba " + LEAVES_PER_BATCH + " liści")
                        .formatted(Formatting.RED), true);
            }
            return ActionResult.SUCCESS;
        }
        if (!world.isClient) {
            stack.decrementUnlessCreative(LEAVES_PER_BATCH, player);
            world.setBlockState(pos, state.with(LOADED, true).with(PROGRESS, 0));
            world.scheduleBlockTick(pos, this, STEP_TICKS);
            world.playSound(null, pos, SoundEvents.BLOCK_BAMBOO_BREAK, SoundCategory.BLOCKS, 0.8F, 0.7F);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        return state.get(LOADED) ? collect(state, world, pos, player) : ActionResult.PASS;
    }

    private ActionResult collect(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (state.get(PROGRESS) < DONE) {
            return ActionResult.PASS;   // still pressing
        }
        if (!world.isClient) {
            ItemStack out = new ItemStack(TrapContent.cocaPaste, 1);
            if (!player.giveItemStack(out)) {
                player.dropItem(out, false);
            }
            world.setBlockState(pos, state.with(LOADED, false).with(PROGRESS, 0));
            world.playSound(null, pos, SoundEvents.BLOCK_WET_GRASS_BREAK, SoundCategory.BLOCKS, 0.9F, 0.8F);
        }
        return ActionResult.SUCCESS;
    }

    /**
     * Load a batch out of a stack. False if it is the wrong thing or short.
     *
     * Split out for the crew, exactly like the drying rack's. A hand pressing
     * leaves has to go through the same door a player does, or the two would
     * drift and one of them would quietly start producing paste from four
     * leaves.
     */
    public static boolean load(BlockState state, World world, BlockPos pos, ItemStack leaves) {
        if (state.get(LOADED) || !leaves.isOf(TrapContent.cocaLeaves)
                || leaves.getCount() < LEAVES_PER_BATCH) {
            return false;
        }
        leaves.decrement(LEAVES_PER_BATCH);
        start(state, world, pos);
        return true;
    }

    /**
     * A batch out of a whole chest, across as many stacks as it takes.
     *
     * A hand decides there is work here by counting the leaves in the WHOLE
     * box, and used to load the press out of a single stack. Five at a time
     * off a stack of sixty-four leaves four behind, so a box filled with full
     * stacks ends up as a row of fours -- every one of them enough to keep
     * saying "ready" and none of them enough to press. The hand then walked
     * to the press once a pass, forever, and did nothing when it got there.
     *
     * Counted first and taken second, so a box that turns out to be short
     * loses nothing: half a batch out of the chest and no batch in the press
     * would be leaves destroyed by looking at them.
     */
    public static boolean load(BlockState state, World world, BlockPos pos,
                               net.minecraft.inventory.Inventory box) {
        if (state.get(LOADED) || !canLoad(box)) {
            return false;
        }
        int owed = LEAVES_PER_BATCH;
        for (int slot = 0; slot < box.size() && owed > 0; slot++) {
            ItemStack stack = box.getStack(slot);
            if (!stack.isOf(TrapContent.cocaLeaves)) {
                continue;
            }
            int taken = Math.min(owed, stack.getCount());
            stack.decrement(taken);
            owed -= taken;
        }
        box.markDirty();
        start(state, world, pos);
        return true;
    }

    /**
     * Has this box got a batch in it, counting every stack?
     *
     * Public, and asked by the crew board rather than answered again over
     * there. Two copies of "is there enough" is what broke this: the board
     * counted the whole box, the loader took from one stack, and a barrel of
     * four-leaf stacks satisfied one and not the other -- so the hand was
     * told to go to work and then had no work to do, every pass, forever.
     * One question, one answer, and they cannot drift apart again.
     */
    public static boolean canLoad(net.minecraft.inventory.Inventory box) {
        if (box == null) {
            return false;
        }
        int found = 0;
        for (int slot = 0; slot < box.size() && found < LEAVES_PER_BATCH; slot++) {
            if (box.getStack(slot).isOf(TrapContent.cocaLeaves)) {
                found += box.getStack(slot).getCount();
            }
        }
        return found >= LEAVES_PER_BATCH;
    }

    private static void start(BlockState state, World world, BlockPos pos) {
        world.setBlockState(pos, state.with(LOADED, true).with(PROGRESS, 0));
        world.scheduleBlockTick(pos, state.getBlock(), STEP_TICKS);
        world.playSound(null, pos, SoundEvents.BLOCK_BAMBOO_BREAK, SoundCategory.BLOCKS, 0.8F, 0.7F);
    }

    /** The paste, if it is pressed. Empty otherwise. */
    public static ItemStack take(BlockState state, World world, BlockPos pos) {
        if (!state.get(LOADED) || state.get(PROGRESS) < DONE) {
            return ItemStack.EMPTY;
        }
        world.setBlockState(pos, state.with(LOADED, false).with(PROGRESS, 0));
        world.playSound(null, pos, SoundEvents.BLOCK_WET_GRASS_BREAK, SoundCategory.BLOCKS, 0.9F, 0.8F);
        return new ItemStack(TrapContent.cocaPaste, 1);
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (!state.get(LOADED) || state.get(PROGRESS) >= DONE) {
            return;
        }
        world.setBlockState(pos, state.with(PROGRESS, state.get(PROGRESS) + 1));
        world.scheduleBlockTick(pos, this, STEP_TICKS);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.SPLASH,
                pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 6, 0.3, 0.0, 0.3, 0.01);
    }

    /**
     * Break as oak, because the press reads as a timber frame with iron in it.
     * See RefinerBlock#getPolymerBreakEventBlockState for why this is needed
     * at all -- the carrier's sounds are an accident of blockstate supply.
     */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return (state.get(LOADED)
                ? workingCarriers.get(state.get(PROGRESS))
                : emptyCarriers).get(state.get(FACING));
    }
}
