package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Step one of the coca line: leaves in, paste out.
 *
 * Same blockstate-machine shape as {@link DryingRackBlock} -- no BlockEntity,
 * because a boolean and a counter fit in the state. Unlike the drying rack
 * there's no window to miss here; pressing is the boring reliable step, and
 * the actual decision lives in the refiner.
 */
public class LeafPressBlock extends Block implements PolymerTexturedBlock {
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

    private final BlockState emptyState;
    private final BlockState[] workingStates = new BlockState[DONE + 1];

    public LeafPressBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LOADED, false).with(PROGRESS, 0));

        this.emptyState = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/leaf_press_empty")),
                () -> Blocks.SMOOTH_STONE.getDefaultState(), "leaf_press_empty");
        for (int step = 0; step <= DONE; step++) {
            String name = "leaf_press_" + step;
            this.workingStates[step] = TrapPolymer.requestOrFallback(
                    BlockModelType.FULL_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/" + name)),
                    () -> Blocks.SMOOTH_STONE.getDefaultState(), name);
        }
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
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
                player.sendMessage(Text.literal("Needs " + LEAVES_PER_BATCH + " leaves")
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
        world.setBlockState(pos, state.with(LOADED, true).with(PROGRESS, 0));
        world.scheduleBlockTick(pos, state.getBlock(), STEP_TICKS);
        world.playSound(null, pos, SoundEvents.BLOCK_BAMBOO_BREAK, SoundCategory.BLOCKS, 0.8F, 0.7F);
        return true;
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
        return state.get(LOADED) ? workingStates[state.get(PROGRESS)] : emptyState;
    }
}
