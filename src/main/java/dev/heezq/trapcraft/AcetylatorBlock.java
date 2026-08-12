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
import net.minecraft.item.Items;
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
 * Step three, and the one that can beat you: base plus acid becomes product.
 *
 * The refiner is the coca line's skill step and this is deliberately its
 * meaner cousin. Both grade on when you pull, both read {@link Purity} off the
 * stage you pulled at -- and where the refiner's worst case is a bad grade, this
 * one's is an empty machine. Leave it past {@link #RUINED} and the batch is
 * gone: the base, the acid, the twelve pods behind them and the twenty minutes.
 *
 * That single difference is what makes the long line feel long. The coca chain
 * punishes inattention with less money; this one punishes it with nothing at
 * all, so the last stage is the one you stand next to.
 *
 * The grace at peak is {@link #PEAK_GRACE} steps against the refiner's five --
 * enough to walk to a chest, not enough to go and do something else.
 */
public class AcetylatorBlock extends Block implements PolymerTexturedBlock {
    public static final MapCodec<AcetylatorBlock> CODEC = createCodec(AcetylatorBlock::new);

    public static final BooleanProperty RUNNING = BooleanProperty.of("running");
    /** Pull here for Pure. Every step below it is a grade off. */
    public static final int PEAK = 4;
    /** One step past peak and there is nothing in there any more. */
    public static final int RUINED = 5;
    public static final IntProperty PROGRESS = IntProperty.of("progress", 0, RUINED);

    /** 45s a step, so peak lands at three minutes. */
    private static final int STEP_TICKS = 900;
    /** Steps of slack at peak before it goes over. Refiner gets five. */
    public static final int PEAK_GRACE = 2;

    /** What passes for acetic anhydride round here. */
    public static final net.minecraft.item.Item ACID = Items.FERMENTED_SPIDER_EYE;

    private final BlockState idleState;
    private final BlockState[] runningStates = new BlockState[RUINED + 1];

    public AcetylatorBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(RUNNING, false).with(PROGRESS, 0));

        this.idleState = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/acetylator_idle")),
                () -> Blocks.BREWING_STAND.getDefaultState(), "acetylator_idle");
        for (int step = 0; step <= RUINED; step++) {
            String name = "acetylator_" + step;
            this.runningStates[step] = TrapPolymer.requestOrFallback(
                    BlockModelType.FULL_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/" + name)),
                    () -> Blocks.BREWING_STAND.getDefaultState(), name);
        }
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(RUNNING, PROGRESS);
    }

    /**
     * Purity from timing, with a hole at the end.
     *
     * Null means the batch is gone. Returning a grade for a ruined run would
     * have been the easy shape and the wrong one -- the whole reason this
     * block is scarier than the refiner is that its bottom outcome is not on
     * the scale at all.
     */
    public static Purity purityFor(int progress) {
        if (progress >= RUINED || progress <= 0) {
            return null;
        }
        return Purity.byIndex(Purity.PURE.index() - (PEAK - progress));
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
                                         PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (state.get(RUNNING)) {
            return collect(state, world, pos, player);
        }
        if (!stack.isOf(TrapContent.morphineBase)) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            int slot = player.getInventory().getSlotWithStack(new ItemStack(ACID));
            if (slot < 0 && !player.isCreative()) {
                player.sendMessage(Text.literal("Needs a fermented spider eye")
                        .formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            if (slot >= 0) {
                player.getInventory().getStack(slot).decrement(1);
            }
            stack.decrementUnlessCreative(1, player);
            start(state, world, pos);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        return state.get(RUNNING) ? collect(state, world, pos, player) : ActionResult.PASS;
    }

    private ActionResult collect(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        int progress = state.get(PROGRESS);
        if (progress == 0) {
            return ActionResult.PASS;   // nothing has come out of solution yet
        }
        if (!world.isClient) {
            Purity purity = purityFor(progress);
            if (purity == null) {
                clear(world, pos, state);
                player.sendMessage(Text.literal("Cooked to tar. The batch is gone.")
                        .formatted(Formatting.RED), false);
                return ActionResult.SUCCESS;
            }
            ItemStack out = TrapComponents.applyPurity(
                    new ItemStack(TrapContent.heroin, progress >= PEAK ? 2 : 1), purity);
            if (!player.giveItemStack(out)) {
                player.dropItem(out, false);
            }
            if (purity == Purity.PURE
                    && player instanceof net.minecraft.server.network.ServerPlayerEntity who) {
                // No vanilla criterion can see "you were stood here at the right
                // moment", which is the only interesting thing about this block.
                TrapAwards.grant(who, "pure_dope");
            }
            world.setBlockState(pos, state.with(RUNNING, false).with(PROGRESS, 0));
            world.playSound(null, pos, SoundEvents.BLOCK_BREWING_STAND_BREW,
                    SoundCategory.BLOCKS, 0.9F, 0.8F);
        }
        return ActionResult.SUCCESS;
    }

    /** Start a run from a base stack and an acid out of the same chest. */
    public static boolean load(BlockState state, World world, BlockPos pos,
                               ItemStack base, net.minecraft.inventory.Inventory box) {
        if (state.get(RUNNING) || !base.isOf(TrapContent.morphineBase)) {
            return false;
        }
        int acid = -1;
        for (int slot = 0; slot < box.size(); slot++) {
            if (box.getStack(slot).isOf(ACID)) {
                acid = slot;
                break;
            }
        }
        if (acid < 0) {
            return false;
        }
        box.getStack(acid).decrement(1);
        base.decrement(1);
        box.markDirty();
        start(state, world, pos);
        return true;
    }

    private static void start(BlockState state, World world, BlockPos pos) {
        world.setBlockState(pos, state.with(RUNNING, true).with(PROGRESS, 0));
        world.scheduleBlockTick(pos, state.getBlock(), STEP_TICKS);
        world.playSound(null, pos, SoundEvents.BLOCK_BREWING_STAND_BREW,
                SoundCategory.BLOCKS, 0.9F, 1.3F);
    }

    /**
     * Pull the run. Empty if nothing has formed, or if it went over.
     *
     * Same shape as the refiner's, so anything that learns to work one machine
     * can work the other -- but a caller that never checks for empty will
     * quietly lose batches here, because here empty is a real outcome.
     */
    public static ItemStack take(BlockState state, World world, BlockPos pos) {
        int progress = state.get(PROGRESS);
        if (!state.get(RUNNING) || progress == 0) {
            return ItemStack.EMPTY;
        }
        Purity purity = purityFor(progress);
        if (purity == null) {
            clear(world, pos, state);
            return ItemStack.EMPTY;
        }
        ItemStack out = TrapComponents.applyPurity(
                new ItemStack(TrapContent.heroin, progress >= PEAK ? 2 : 1), purity);
        world.setBlockState(pos, state.with(RUNNING, false).with(PROGRESS, 0));
        world.playSound(null, pos, SoundEvents.BLOCK_BREWING_STAND_BREW,
                SoundCategory.BLOCKS, 0.9F, 0.8F);
        return out;
    }

    private static void clear(World world, BlockPos pos, BlockState state) {
        world.setBlockState(pos, state.with(RUNNING, false).with(PROGRESS, 0));
        world.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1.0F, 0.6F);
        if (world instanceof ServerWorld server) {
            server.spawnParticles(ParticleTypes.LARGE_SMOKE,
                    pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 20, 0.3, 0.1, 0.3, 0.03);
        }
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (!state.get(RUNNING) || state.get(PROGRESS) >= RUINED) {
            return;
        }
        int next = state.get(PROGRESS) + 1;
        world.setBlockState(pos, state.with(PROGRESS, next));
        if (next < RUINED) {
            world.scheduleBlockTick(pos, this, next == PEAK ? STEP_TICKS * PEAK_GRACE : STEP_TICKS);
        }

        if (next == PEAK) {
            // The one moment worth hearing from across the base. Everything
            // else about this block is quiet on purpose so this reads.
            world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                    SoundCategory.BLOCKS, 0.8F, 1.8F);
            world.spawnParticles(ParticleTypes.END_ROD,
                    pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 12, 0.25, 0.1, 0.25, 0.02);
        } else if (next >= RUINED) {
            world.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH,
                    SoundCategory.BLOCKS, 1.0F, 0.5F);
            world.spawnParticles(ParticleTypes.LARGE_SMOKE,
                    pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 16, 0.3, 0.1, 0.3, 0.03);
        } else {
            world.spawnParticles(ParticleTypes.WHITE_SMOKE,
                    pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 6, 0.22, 0.0, 0.22, 0.015);
        }
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return state.get(RUNNING) ? runningStates[state.get(PROGRESS)] : idleState;
    }

    /** Glass and iron over a stone bench -- see RefinerBlock for why this matters. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.IRON_BLOCK.getDefaultState();
    }
}
