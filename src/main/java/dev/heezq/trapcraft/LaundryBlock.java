package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * A drum you put dirty money in.
 *
 * The black market pays in {@link TrapContent#dirtyEmerald}, which is not
 * money -- no shop takes it, no wage comes out of it, and the market does not
 * know it exists. It becomes money here, minus a cut, after a wash.
 *
 * That is a much better mechanic than the command it replaces. "/wash 400"
 * was a number moving in a ledger; this is a machine in a back room with your
 * takings going round in it, and the whole reason the drug half of this mod is
 * dangerous is now something you can point at.
 *
 * <h2>Why the load lives in the blockstate</h2>
 *
 * Nothing else has to remember anything. The drum knows how much is in it and
 * whether it has finished, which is nine loads by two states -- eighteen
 * carriers out of a FULL_BLOCK pool in four figures. No block entity, no side
 * ledger, no save file, and a machine broken mid-cycle spills exactly what was
 * in it.
 */
public class LaundryBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    public static final MapCodec<LaundryBlock> CODEC = createCodec(LaundryBlock::new);

    /** Emeralds in the drum. Two stacks of dirty money. */
    public static final int MAX_LOAD = 128;
    /** Under this and it will not start: you do not launder pocket change. */
    public static final int MIN_LOAD = 2;
    /**
     * How long each emerald takes to come out clean.
     *
     * Per emerald rather than per load, so a drum is a THROUGHPUT and not a
     * free multiplier: eight used to take half a minute whatever you did, so
     * a bigger drum would simply have been more money for the same wait.
     * Three seconds each keeps that half-minute for a small load and makes a
     * full one an eight-minute job you walk away from.
     */
    public static final int WASH_TICKS_EACH = 60;

    public static final IntProperty LOAD = IntProperty.of("load", 0, MAX_LOAD);
    public static final BooleanProperty DONE = BooleanProperty.of("done");

    /**
     * Three carriers for two hundred and fifty-eight states, and it matters.
     *
     * The first version asked Polymer for a carrier per LOAD VALUE, which was
     * eighteen states for three pictures -- wasteful at a capacity of eight
     * and, at a capacity of a hundred and twenty-eight, would have taken a
     * quarter of the whole FULL_BLOCK pool to draw the same three drums.
     * Nothing requires the mapping from server state to carrier to be one to
     * one; it is a function, and this one has three answers.
     */
    private final BlockState emptyDrum;
    private final BlockState running;
    private final BlockState finished;

    public LaundryBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LOAD, 0).with(DONE, false));
        this.emptyDrum = TrapPolymer.requestOrFallback(BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/laundry_empty")),
                () -> Blocks.CAULDRON.getDefaultState(), "laundry_empty");
        this.running = TrapPolymer.requestOrFallback(BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/laundry_running")),
                () -> Blocks.CAULDRON.getDefaultState(), "laundry_running");
        this.finished = TrapPolymer.requestOrFallback(BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/laundry_done")),
                () -> Blocks.CAULDRON.getDefaultState(), "laundry_done");
    }

    /** Ticks a load of this size takes, and never less than a moment. */
    public static int washTicks(int load) {
        return Math.max(20, load * WASH_TICKS_EACH);
    }

    /** "8 minutes", "45s" -- for the drum, the book and the page. */
    public static String washLabel(int load) {
        int seconds = washTicks(load) / 20;
        return seconds < 90 ? seconds + "s"
                : seconds % 60 == 0 ? seconds / 60 + " minutes"
                : String.format("%.1f minutes", seconds / 60.0f);
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LOAD, DONE);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        if (state.get(DONE)) {
            return finished;
        }
        return state.get(LOAD) == 0 ? emptyDrum : running;
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.CAULDRON.getDefaultState();
    }

    // --- using it -------------------------------------------------------------

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;

        if (state.get(DONE)) {
            collect(ground, pos, state, who);
            return ActionResult.SUCCESS;
        }

        ItemStack held = player.getMainHandStack();
        if (held.isOf(TrapContent.dirtyEmerald)) {
            load(ground, pos, state, who, held, 1);
            return ActionResult.SUCCESS;
        }
        // A block is nine, and takes nine's worth of room. Without this you
        // would have to uncraft a week's takings back into loose emeralds on
        // the drum's doorstep, which is busywork wearing a mechanic's hat.
        if (held.isOf(TrapContent.dirtyEmeraldBlockItem)) {
            load(ground, pos, state, who, held, 9);
            return ActionResult.SUCCESS;
        }

        int load = state.get(LOAD);
        who.sendMessage(load == 0
                ? Text.literal("Empty. Put dirty money in it -- up to " + MAX_LOAD + ".")
                        .formatted(Formatting.GRAY)
                : Text.literal(load + " in the drum, going round.")
                        .formatted(Formatting.GRAY), true);
        return ActionResult.SUCCESS;
    }

    /** Tip some in and set it turning. */
    private void load(ServerWorld world, BlockPos pos, BlockState state,
                      ServerPlayerEntity who, ItemStack held, int each) {
        int room = MAX_LOAD - state.get(LOAD);
        if (room < each) {
            who.sendMessage(Text.literal(room <= 0 ? "The drum's full."
                            : "Only room for " + room + " more. Break a block up.")
                    .formatted(Formatting.GRAY), true);
            return;
        }
        // Whole items only: half a block down the drum would have to round
        // somewhere, and every rounding in this mod is money appearing or
        // vanishing.
        int lots = Math.min(room / each, held.getCount());
        held.decrement(lots);
        int going = lots * each;
        int load = state.get(LOAD) + going;
        world.setBlockState(pos, state.with(LOAD, load));

        if (load >= MIN_LOAD) {
            // Rescheduled on every top-up, from the new total. Tipping more in
            // starts the clock again -- which is worth saying out loud, and is
            // simpler than a part-washed load that somebody has to reason
            // about. Load it all, then leave it.
            world.scheduleBlockTick(pos, this, washTicks(load));
            world.playSound(null, pos, SoundEvents.BLOCK_WATER_AMBIENT,
                    SoundCategory.BLOCKS, 0.7F, 1.0F);
        }
        who.sendMessage(load < MIN_LOAD
                ? Text.literal(load + " in. It wants at least " + MIN_LOAD
                        + " before it'll run.").formatted(Formatting.GRAY)
                : Text.literal(load + " in the drum. ").formatted(Formatting.GRAY)
                        .append(Text.literal(washLabel(load))
                                .formatted(Formatting.WHITE))
                        .append(Text.literal(load < MAX_LOAD
                                        ? "   adding more restarts it" : "   full")
                                .formatted(Formatting.DARK_GRAY)), true);
    }

    /**
     * Take the clean money out.
     *
     * The cut is the whole point and it is taken here rather than at the door,
     * so what you get back is the number you see. Where it goes depends on
     * whether there is anybody to take it: a city gets the duty, and without
     * one it is simply gone -- which is the honest version of what happens to
     * money nobody can account for.
     */
    private void collect(ServerWorld world, BlockPos pos, BlockState state,
                         ServerPlayerEntity who) {
        int load = state.get(LOAD);
        // Anything from nothing to a fifth. A flat rate is a fee somebody
        // budgets for; a roll is a risk they take, and the whole point of
        // laundering is that you do not know what you will get back.
        int cut = Math.round(load * TrapLaw.WASH_CUT * world.getRandom().nextFloat());
        int clean = Math.max(0, load - cut);
        world.setBlockState(pos, state.with(LOAD, 0).with(DONE, false));
        if (clean > 0) {
            // Minted here and nowhere else. Dirty money was never in the world
            // -- the market has never counted it and no shop would take it --
            // so this is the moment those emeralds actually exist.
            TrapMarket.minted(clean);
            int left = clean;
            while (left > 0) {
                int lot = Math.min(left, Items.EMERALD.getMaxCount());
                who.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, lot));
                left -= lot;
            }
        }
        TrapLaw.washed(who, load, cut);

        world.playSound(null, pos, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE,
                SoundCategory.BLOCKS, 0.8F, 1.4F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5,
                pos.getY() + 1.1, pos.getZ() + 0.5, 14, 0.35, 0.3, 0.35, 0.02);
        who.sendMessage(Text.literal("Out of the drum. ").formatted(Formatting.GREEN)
                .append(Text.literal(clean + "e clean").formatted(Formatting.WHITE))
                .append(Text.literal(cut == 0 ? ", and nothing lost this time."
                                : ", " + cut + " gone in the wash.")
                        .formatted(Formatting.DARK_GRAY)), false);
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos,
                                 Random random) {
        if (state.get(DONE) || state.get(LOAD) < MIN_LOAD) {
            return;
        }
        world.setBlockState(pos, state.with(DONE, true));
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(),
                SoundCategory.BLOCKS, 0.6F, 1.6F);
        world.spawnParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.1,
                pos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.01);
    }

    /** Broken mid-cycle: whatever was in it falls out, still dirty. */
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        int load = state.get(LOAD);
        // In stacks, because a drum now holds two of them and one ItemStack of
        // a hundred and twenty-eight is over the item's own limit.
        while (load > 0) {
            int lot = Math.min(load, TrapContent.dirtyEmerald.getMaxCount());
            Block.dropStack(world, pos, new ItemStack(TrapContent.dirtyEmerald, lot));
            load -= lot;
        }
        super.onStateReplaced(state, world, pos, moved);
    }
}
