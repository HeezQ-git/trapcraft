package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
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
 * Step two: paste plus blaze powder becomes finished product.
 *
 * The reagent is blaze powder purely because it's Minecraft-fantasy -- the
 * chain here is a game mechanic, deliberately not a model of anything real.
 *
 * This block carries the skill in the coca line. Purity is decided entirely by
 * WHEN you pull it, on the same shape as the drying rack's curing window:
 * early is weak, one stage is perfect, past that it burns.
 */
public class RefinerBlock extends TurnableBlock implements PolymerTexturedBlock {
    public static final MapCodec<RefinerBlock> CODEC = createCodec(RefinerBlock::new);

    public static final BooleanProperty RUNNING = BooleanProperty.of("running");
    public static final int PEAK = 3;
    public static final int BURNT = 4;
    public static final IntProperty PROGRESS = IntProperty.of("progress", 0, BURNT);

    private static final int STEP_TICKS = 500;

    private final Map<Direction, BlockState> idleCarriers;
    private final List<Map<Direction, BlockState>> runningCarriers = new ArrayList<>();

    public RefinerBlock(Settings settings) {
        super(settings);
        this.setDefaultState(getDefaultState()
                .with(RUNNING, false).with(PROGRESS, 0));

        // Four carriers per model rather than one: a machine with a door
        // and a dial on the front is worth turning to face the room.
        this.idleCarriers = carriers(BlockModelType.FULL_BLOCK, "refiner_idle",
                () -> Blocks.FURNACE.getDefaultState());
        for (int step = 0; step <= BURNT; step++) {
            this.runningCarriers.add(carriers(BlockModelType.FULL_BLOCK,
                    "refiner_" + step, () -> Blocks.FURNACE.getDefaultState()));
        }
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
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

    /** Start a run from a paste stack and a reagent out of the same chest. */
    public static boolean load(BlockState state, World world, BlockPos pos,
                               ItemStack paste, net.minecraft.inventory.Inventory box) {
        if (state.get(RUNNING) || !paste.isOf(TrapContent.cocaPaste)) {
            return false;
        }
        int reagent = -1;
        for (int slot = 0; slot < box.size(); slot++) {
            if (box.getStack(slot).isOf(Items.BLAZE_POWDER)) {
                reagent = slot;
                break;
            }
        }
        if (reagent < 0) {
            return false;
        }
        box.getStack(reagent).decrement(1);
        paste.decrement(1);
        box.markDirty();
        world.setBlockState(pos, state.with(RUNNING, true).with(PROGRESS, 0));
        world.scheduleBlockTick(pos, state.getBlock(), STEP_TICKS);
        world.playSound(null, pos, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 0.9F, 1.2F);
        return true;
    }

    /**
     * Pull the run. Empty if nothing has formed yet.
     *
     * The crew only ever calls this at {@link #PEAK} -- see the note on the
     * refining job. Timing the pull is the whole skill of the coca line, and
     * a hand that could do it is the most expensive thing you can teach one.
     */
    public static ItemStack take(BlockState state, World world, BlockPos pos) {
        int progress = state.get(PROGRESS);
        if (!state.get(RUNNING) || progress == 0) {
            return ItemStack.EMPTY;
        }
        Purity purity = purityFor(progress);
        ItemStack out = TrapComponents.applyPurity(
                new ItemStack(TrapContent.cocaPowder, progress >= PEAK ? 2 : 1), purity);
        world.setBlockState(pos, state.with(RUNNING, false).with(PROGRESS, 0));
        world.playSound(null, pos, SoundEvents.BLOCK_BREWING_STAND_BREW,
                SoundCategory.BLOCKS, 0.9F, 1.0F);
        return out;
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
        return (state.get(RUNNING)
                ? runningCarriers.get(state.get(PROGRESS))
                : idleCarriers).get(state.get(FACING));
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
