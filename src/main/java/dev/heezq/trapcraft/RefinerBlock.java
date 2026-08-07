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
 * Step two: paste plus blaze powder becomes finished product.
 *
 * The reagent is blaze powder purely because it's Minecraft-fantasy -- the
 * chain here is a game mechanic, deliberately not a model of anything real.
 *
 * This block carries the skill in the coca line. Purity is decided entirely by
 * WHEN you pull it, on the same shape as the drying rack's curing window:
 * early is weak, one stage is perfect, past that it burns.
 */
public class RefinerBlock extends Block implements PolymerTexturedBlock {
    public static final MapCodec<RefinerBlock> CODEC = createCodec(RefinerBlock::new);

    public static final BooleanProperty RUNNING = BooleanProperty.of("running");
    public static final int PEAK = 3;
    public static final int BURNT = 4;
    public static final IntProperty PROGRESS = IntProperty.of("progress", 0, BURNT);

    private static final int STEP_TICKS = 500;

    private final BlockState idleState;
    private final BlockState[] runningStates = new BlockState[BURNT + 1];

    public RefinerBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(RUNNING, false).with(PROGRESS, 0));

        this.idleState = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/refiner_idle")),
                () -> Blocks.FURNACE.getDefaultState(), "refiner_idle");
        for (int step = 0; step <= BURNT; step++) {
            String name = "refiner_" + step;
            this.runningStates[step] = TrapPolymer.requestOrFallback(
                    BlockModelType.FULL_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/" + name)),
                    () -> Blocks.FURNACE.getDefaultState(), name);
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
     * Purity from timing. Pull at PEAK for the best result; anything else is a
     * grade off per step, and burning it drops you to the bottom.
     */
    public static Purity purityFor(int progress) {
        if (progress >= BURNT) {
            return Purity.CUT;
        }
        return Purity.byIndex(Purity.PURE.index() - (PEAK - progress));
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
                                         PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (state.get(RUNNING)) {
            return collect(state, world, pos, player);
        }
        if (!stack.isOf(TrapContent.cocaPaste)) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            // Reagent comes out of the inventory, not the hand, so you don't
            // have to juggle two stacks to use the machine.
            int slot = player.getInventory().getSlotWithStack(new ItemStack(Items.BLAZE_POWDER));
            if (slot < 0 && !player.isCreative()) {
                player.sendMessage(Text.literal("Needs blaze powder")
                        .formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            if (slot >= 0) {
                player.getInventory().getStack(slot).decrement(1);
            }
            stack.decrementUnlessCreative(1, player);
            world.setBlockState(pos, state.with(RUNNING, true).with(PROGRESS, 0));
            world.scheduleBlockTick(pos, this, STEP_TICKS);
            world.playSound(null, pos, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 0.9F, 1.2F);
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
            return ActionResult.PASS;   // nothing formed yet
        }
        if (!world.isClient) {
            Purity purity = purityFor(progress);
            ItemStack out = TrapComponents.applyPurity(
                    new ItemStack(TrapContent.cocaPowder, progress >= PEAK ? 2 : 1), purity);
            if (!player.giveItemStack(out)) {
                player.dropItem(out, false);
            }
            world.setBlockState(pos, state.with(RUNNING, false).with(PROGRESS, 0));
            world.playSound(null, pos, SoundEvents.BLOCK_BREWING_STAND_BREW,
                    SoundCategory.BLOCKS, 0.9F, 1.0F);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (!state.get(RUNNING) || state.get(PROGRESS) >= BURNT) {
            return;
        }
        int next = state.get(PROGRESS) + 1;
        world.setBlockState(pos, state.with(PROGRESS, next));
        if (next < BURNT) {
            // Long grace at peak: you should have to forget about it, not lose
            // a batch for stepping away. Same rule as the drying rack.
            world.scheduleBlockTick(pos, this, next == PEAK ? STEP_TICKS * 5 : STEP_TICKS);
        }
        world.spawnParticles(next >= BURNT ? ParticleTypes.LARGE_SMOKE : ParticleTypes.WHITE_SMOKE,
                pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 8, 0.25, 0.0, 0.25, 0.02);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return state.get(RUNNING) ? runningStates[state.get(PROGRESS)] : idleState;
    }

    /**
     * What the client thinks it is breaking.
     *
     * Break sounds, hit sounds and break particles are all predicted client
     * side from this state, and it defaults to the Polymer carrier -- an
     * arbitrary vanilla block chosen for having spare blockstates, not for
     * sounding like anything. That is why a copper retort broke with a muffled
     * thud and threw the wrong coloured dust.
     *
     * The server's own BlockSoundGroup only covers the place sound, so the two
     * have to be picked together or the block places and breaks as different
     * materials.
     */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.COPPER_BLOCK.getDefaultState();
    }
}
