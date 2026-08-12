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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
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
 * Step one of the long line: pods in, latex out.
 *
 * The bulk step, and the coca line's leaf press is the model for it -- no
 * window to miss, nothing to time, just a machine that takes a lot of one thing
 * and slowly gives you a little of another. Every chain wants one of these, or
 * every stage is a decision and the whole thing becomes a chore.
 *
 * What makes it heavier than the press is arithmetic: {@link #PODS_PER_BATCH}
 * pods for one latex, against five leaves for one paste, off a plant that takes
 * longer to ripen and insists on daylight. That is where "harder than cocaine"
 * mostly lives -- not in any single step being fiddly, but in the field behind
 * it having to be about twice the size.
 */
public class ScoringTableBlock extends Block implements PolymerTexturedBlock {
    public static final MapCodec<ScoringTableBlock> CODEC = createCodec(ScoringTableBlock::new);

    public static final BooleanProperty LOADED = BooleanProperty.of("loaded");
    public static final int DONE = 4;
    public static final IntProperty PROGRESS = IntProperty.of("progress", 0, DONE);

    /** Pods one batch of latex takes. */
    public static final int PODS_PER_BATCH = 6;
    /** 35s a step, 140s the batch. Half again on the leaf press. */
    private static final int STEP_TICKS = 700;

    private final BlockState emptyState;
    private final BlockState[] workingStates = new BlockState[DONE + 1];

    public ScoringTableBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LOADED, false).with(PROGRESS, 0));

        this.emptyState = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/scoring_table_empty")),
                () -> Blocks.OAK_PLANKS.getDefaultState(), "scoring_table_empty");
        for (int step = 0; step <= DONE; step++) {
            String name = "scoring_table_" + step;
            this.workingStates[step] = TrapPolymer.requestOrFallback(
                    BlockModelType.FULL_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/" + name)),
                    () -> Blocks.OAK_PLANKS.getDefaultState(), name);
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
        if (!stack.isOf(TrapContent.poppyPod)) {
            return ActionResult.PASS;
        }
        if (stack.getCount() < PODS_PER_BATCH) {
            if (!world.isClient) {
                player.sendMessage(Text.literal("Needs " + PODS_PER_BATCH + " pods")
                        .formatted(Formatting.RED), true);
            }
            return ActionResult.SUCCESS;
        }
        if (!world.isClient) {
            stack.decrementUnlessCreative(PODS_PER_BATCH, player);
            start(state, world, pos);
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
            return ActionResult.PASS;   // still weeping
        }
        if (!world.isClient) {
            ItemStack out = new ItemStack(TrapContent.rawOpium, 1);
            if (!player.giveItemStack(out)) {
                player.dropItem(out, false);
            }
            world.setBlockState(pos, state.with(LOADED, false).with(PROGRESS, 0));
            world.playSound(null, pos, SoundEvents.BLOCK_HONEY_BLOCK_BREAK,
                    SoundCategory.BLOCKS, 0.9F, 0.8F);
        }
        return ActionResult.SUCCESS;
    }

    /**
     * Load a batch out of a stack. Same door a player goes through.
     *
     * Split out for the same reason the press's is: two ways to start a batch
     * is two places for the pod count to drift apart.
     */
    public static boolean load(BlockState state, World world, BlockPos pos, ItemStack pods) {
        if (state.get(LOADED) || !pods.isOf(TrapContent.poppyPod)
                || pods.getCount() < PODS_PER_BATCH) {
            return false;
        }
        pods.decrement(PODS_PER_BATCH);
        start(state, world, pos);
        return true;
    }

    private static void start(BlockState state, World world, BlockPos pos) {
        world.setBlockState(pos, state.with(LOADED, true).with(PROGRESS, 0));
        world.scheduleBlockTick(pos, state.getBlock(), STEP_TICKS);
        world.playSound(null, pos, SoundEvents.ITEM_AXE_SCRAPE, SoundCategory.BLOCKS, 0.7F, 1.4F);
    }

    /** The latex, if it has run. Empty otherwise. */
    public static ItemStack take(BlockState state, World world, BlockPos pos) {
        if (!state.get(LOADED) || state.get(PROGRESS) < DONE) {
            return ItemStack.EMPTY;
        }
        world.setBlockState(pos, state.with(LOADED, false).with(PROGRESS, 0));
        world.playSound(null, pos, SoundEvents.BLOCK_HONEY_BLOCK_BREAK,
                SoundCategory.BLOCKS, 0.9F, 0.8F);
        return new ItemStack(TrapContent.rawOpium, 1);
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (!state.get(LOADED) || state.get(PROGRESS) >= DONE) {
            return;
        }
        world.setBlockState(pos, state.with(PROGRESS, state.get(PROGRESS) + 1));
        world.scheduleBlockTick(pos, this, STEP_TICKS);
        // Beading up on the cuts. FALLING_HONEY is the one vanilla particle
        // that drips slowly instead of falling, which is exactly the read.
        world.spawnParticles(ParticleTypes.FALLING_HONEY,
                pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 4, 0.3, 0.05, 0.3, 0.0);
    }

    /** Timber and blades, so it breaks as wood. See RefinerBlock for why. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return state.get(LOADED) ? workingStates[state.get(PROGRESS)] : emptyState;
    }
}
