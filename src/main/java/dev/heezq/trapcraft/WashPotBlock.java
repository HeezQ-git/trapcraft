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
import net.minecraft.state.property.Properties;
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
 * Step two of the long line: latex and lime, cooked down to base.
 *
 * The pot's whole character is that it does not carry its own fire. It only
 * advances while something underneath it is burning, which turns one machine
 * into a small BUILD -- a pot on a campfire, or a row of them over a lava
 * channel -- and makes the second stage of the chain a thing you have to
 * construct rather than a thing you have to click.
 *
 * That also gives the line a failure mode the coca chain does not have and
 * cannot get: nothing spoils, but a fire that burns out is a batch that simply
 * stops, and you find it hours later exactly where you left it. Losing the
 * afternoon is a better punishment than losing the goods, because it is one you
 * can see coming and design around.
 *
 * Heat is read off the block below via {@link Properties#LIT} rather than a
 * list of block ids, so a lit campfire, a lit furnace and whatever this pack's
 * other 136 mods call a forge all count without anybody maintaining a list.
 */
public class WashPotBlock extends Block implements PolymerTexturedBlock {
    public static final MapCodec<WashPotBlock> CODEC = createCodec(WashPotBlock::new);

    public static final BooleanProperty LOADED = BooleanProperty.of("loaded");
    public static final int DONE = 4;
    public static final IntProperty PROGRESS = IntProperty.of("progress", 0, DONE);

    /** Latex per batch. */
    public static final int OPIUM_PER_BATCH = 2;
    /** Slaked lime, which this game spells "bone meal". */
    public static final int LIME_PER_BATCH = 3;
    /** 40s a step, 160s the batch -- but only while it is over a fire. */
    private static final int STEP_TICKS = 800;
    /** How often a stalled pot looks again for its fire. */
    private static final int COLD_RETRY_TICKS = 100;

    private final BlockState emptyState;
    private final BlockState[] workingStates = new BlockState[DONE + 1];

    public WashPotBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LOADED, false).with(PROGRESS, 0));

        this.emptyState = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/wash_pot_empty")),
                () -> Blocks.CAULDRON.getDefaultState(), "wash_pot_empty");
        for (int step = 0; step <= DONE; step++) {
            String name = "wash_pot_" + step;
            this.workingStates[step] = TrapPolymer.requestOrFallback(
                    BlockModelType.FULL_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/" + name)),
                    () -> Blocks.CAULDRON.getDefaultState(), name);
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

    /**
     * Is there a live fire under this?
     *
     * Public because the guide quotes the rule and the wash pot is not the only
     * thing that ought to be able to ask it if the mod ever grows a second
     * fired machine.
     */
    public static boolean heated(World world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.down());
        if (below.contains(Properties.LIT) && below.get(Properties.LIT)) {
            return true;
        }
        // Lava has no LIT property and is the obvious thing somebody will try.
        return below.isOf(Blocks.LAVA) || below.isOf(Blocks.MAGMA_BLOCK)
                || !below.getFluidState().isEmpty()
                && below.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
                                         PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (state.get(LOADED)) {
            return collect(state, world, pos, player);
        }
        if (!stack.isOf(TrapContent.rawOpium)) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            if (stack.getCount() < OPIUM_PER_BATCH) {
                player.sendMessage(Text.literal("Needs " + OPIUM_PER_BATCH + " opium")
                        .formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            // Lime comes out of the inventory, not the hand -- same rule as the
            // refiner's blaze powder, so no machine in the mod asks you to
            // juggle two stacks.
            if (!player.isCreative() && countLime(player) < LIME_PER_BATCH) {
                player.sendMessage(Text.literal("Needs " + LIME_PER_BATCH + " bone meal for lime")
                        .formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            if (!heated(world, pos)) {
                // Said out loud rather than left as a silent stall: a machine
                // that takes your goods and then does nothing is a bug report.
                player.sendMessage(Text.literal("Nothing burning under it")
                        .formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            takeLime(player);
            stack.decrementUnlessCreative(OPIUM_PER_BATCH, player);
            start(state, world, pos);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        return state.get(LOADED) ? collect(state, world, pos, player) : ActionResult.PASS;
    }

    private static int countLime(PlayerEntity player) {
        int found = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(Items.BONE_MEAL)) {
                found += inventory.getStack(slot).getCount();
            }
        }
        return found;
    }

    private static void takeLime(PlayerEntity player) {
        if (player.isCreative()) {
            return;
        }
        int owed = LIME_PER_BATCH;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size() && owed > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.BONE_MEAL)) {
                int taken = Math.min(owed, stack.getCount());
                stack.decrement(taken);
                owed -= taken;
            }
        }
    }

    private ActionResult collect(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (state.get(PROGRESS) < DONE) {
            return ActionResult.PASS;   // still cooking
        }
        if (!world.isClient) {
            ItemStack out = new ItemStack(TrapContent.morphineBase, 1);
            if (!player.giveItemStack(out)) {
                player.dropItem(out, false);
            }
            world.setBlockState(pos, state.with(LOADED, false).with(PROGRESS, 0));
            world.playSound(null, pos, SoundEvents.BLOCK_BREWING_STAND_BREW,
                    SoundCategory.BLOCKS, 0.8F, 0.7F);
        }
        return ActionResult.SUCCESS;
    }

    /** Start a batch from a stack and a chest's worth of lime. For the crew. */
    public static boolean load(BlockState state, World world, BlockPos pos,
                               ItemStack opium, net.minecraft.inventory.Inventory box) {
        if (state.get(LOADED) || !opium.isOf(TrapContent.rawOpium)
                || opium.getCount() < OPIUM_PER_BATCH || !heated(world, pos)) {
            return false;
        }
        int owed = LIME_PER_BATCH;
        for (int slot = 0; slot < box.size() && owed > 0; slot++) {
            if (box.getStack(slot).isOf(Items.BONE_MEAL)) {
                owed -= Math.min(owed, box.getStack(slot).getCount());
            }
        }
        if (owed > 0) {
            return false;   // counted before anything is taken; see LeafPressBlock
        }
        owed = LIME_PER_BATCH;
        for (int slot = 0; slot < box.size() && owed > 0; slot++) {
            ItemStack stack = box.getStack(slot);
            if (stack.isOf(Items.BONE_MEAL)) {
                int taken = Math.min(owed, stack.getCount());
                stack.decrement(taken);
                owed -= taken;
            }
        }
        box.markDirty();
        opium.decrement(OPIUM_PER_BATCH);
        start(state, world, pos);
        return true;
    }

    private static void start(BlockState state, World world, BlockPos pos) {
        world.setBlockState(pos, state.with(LOADED, true).with(PROGRESS, 0));
        world.scheduleBlockTick(pos, state.getBlock(), STEP_TICKS);
        world.playSound(null, pos, SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.BLOCKS, 0.6F, 1.5F);
    }

    /** The base, if it has cooked down. Empty otherwise. */
    public static ItemStack take(BlockState state, World world, BlockPos pos) {
        if (!state.get(LOADED) || state.get(PROGRESS) < DONE) {
            return ItemStack.EMPTY;
        }
        world.setBlockState(pos, state.with(LOADED, false).with(PROGRESS, 0));
        world.playSound(null, pos, SoundEvents.BLOCK_BREWING_STAND_BREW,
                SoundCategory.BLOCKS, 0.8F, 0.7F);
        return new ItemStack(TrapContent.morphineBase, 1);
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (!state.get(LOADED) || state.get(PROGRESS) >= DONE) {
            return;
        }
        if (!heated(world, pos)) {
            // Cold: nothing spoils, nothing advances, and it keeps looking.
            // A wisp of smoke off a stalled pot is the only tell it needs --
            // the missing fire underneath is the other one, and that one is
            // hard to miss.
            world.spawnParticles(ParticleTypes.SMOKE,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 2, 0.2, 0.0, 0.2, 0.0);
            world.scheduleBlockTick(pos, this, COLD_RETRY_TICKS);
            return;
        }
        world.setBlockState(pos, state.with(PROGRESS, state.get(PROGRESS) + 1));
        world.scheduleBlockTick(pos, this, STEP_TICKS);
        world.spawnParticles(ParticleTypes.BUBBLE_POP,
                pos.getX() + 0.5, pos.getY() + 0.95, pos.getZ() + 0.5, 6, 0.25, 0.0, 0.25, 0.01);
        world.playSound(null, pos, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT,
                SoundCategory.BLOCKS, 0.25F, 0.8F);
    }

    /** A copper pot on brick. Break it as copper. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.COPPER_BLOCK.getDefaultState();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return state.get(LOADED) ? workingStates[state.get(PROGRESS)] : emptyState;
    }
}
