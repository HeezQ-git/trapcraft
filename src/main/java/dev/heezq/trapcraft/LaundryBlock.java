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

    /** Emeralds in the drum. */
    public static final int MAX_LOAD = 8;
    /** Under this and it will not start: you do not launder pocket change. */
    public static final int MIN_LOAD = 2;
    /** How long a wash takes, whatever is in it. */
    public static final int WASH_TICKS = 20 * 30;

    public static final IntProperty LOAD = IntProperty.of("load", 0, MAX_LOAD);
    public static final BooleanProperty DONE = BooleanProperty.of("done");

    private final BlockState[] idle = new BlockState[MAX_LOAD + 1];
    private final BlockState[] finished = new BlockState[MAX_LOAD + 1];

    public LaundryBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LOAD, 0).with(DONE, false));
        for (int load = 0; load <= MAX_LOAD; load++) {
            // Three models between nine loads: empty, turning, and a drum with
            // clean money sat in it. A texture per emerald would be eight
            // pictures of the same drum.
            String running = load == 0 ? "laundry_empty" : "laundry_running";
            idle[load] = TrapPolymer.requestOrFallback(BlockModelType.FULL_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/" + running)),
                    () -> Blocks.CAULDRON.getDefaultState(), running);
            finished[load] = TrapPolymer.requestOrFallback(BlockModelType.FULL_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/laundry_done")),
                    () -> Blocks.CAULDRON.getDefaultState(), "laundry_done");
        }
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
        int load = state.get(LOAD);
        return state.get(DONE) ? finished[load] : idle[load];
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
            load(ground, pos, state, who, held);
            return ActionResult.SUCCESS;
        }

        int load = state.get(LOAD);
        who.sendMessage(load == 0
                ? Text.literal("Empty. Put dirty money in it.").formatted(Formatting.GRAY)
                : Text.literal(load + " in the drum, going round.")
                        .formatted(Formatting.GRAY), true);
        return ActionResult.SUCCESS;
    }

    /** Tip some in and set it turning. */
    private void load(ServerWorld world, BlockPos pos, BlockState state,
                      ServerPlayerEntity who, ItemStack held) {
        int room = MAX_LOAD - state.get(LOAD);
        if (room <= 0) {
            who.sendMessage(Text.literal("The drum's full.").formatted(Formatting.GRAY), true);
            return;
        }
        int going = Math.min(room, held.getCount());
        held.decrement(going);
        int load = state.get(LOAD) + going;
        world.setBlockState(pos, state.with(LOAD, load));

        if (load >= MIN_LOAD) {
            world.scheduleBlockTick(pos, this, WASH_TICKS);
            world.playSound(null, pos, SoundEvents.BLOCK_WATER_AMBIENT,
                    SoundCategory.BLOCKS, 0.7F, 1.0F);
        }
        who.sendMessage(load < MIN_LOAD
                ? Text.literal(load + " in. It wants at least " + MIN_LOAD
                        + " before it'll run.").formatted(Formatting.GRAY)
                : Text.literal(load + " in the drum. ").formatted(Formatting.GRAY)
                        .append(Text.literal("Half a minute.")
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
        int cut = Math.max(1, Math.round(load * TrapLaw.WASH_CUT));
        int clean = Math.max(0, load - cut);
        world.setBlockState(pos, state.with(LOAD, 0).with(DONE, false));

        if (clean > 0) {
            // Minted here and nowhere else. Dirty money was never in the world
            // -- the market has never counted it and no shop would take it --
            // so this is the moment those emeralds actually exist.
            TrapMarket.minted(clean);
            who.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, clean));
        }
        TrapLaw.washed(who, load, cut);

        world.playSound(null, pos, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE,
                SoundCategory.BLOCKS, 0.8F, 1.4F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5,
                pos.getY() + 1.1, pos.getZ() + 0.5, 14, 0.35, 0.3, 0.35, 0.02);
        who.sendMessage(Text.literal("Out of the drum. ").formatted(Formatting.GREEN)
                .append(Text.literal(clean + "e clean").formatted(Formatting.WHITE))
                .append(Text.literal(", " + cut + " gone in the wash.")
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
        if (load > 0) {
            Block.dropStack(world, pos, new ItemStack(TrapContent.dirtyEmerald, load));
        }
        super.onStateReplaced(state, world, pos, moved);
    }
}
