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
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Wet buds in, dried buds out.
 *
 * ponytail: state lives in blockstate properties, not a BlockEntity -- three
 * enum/int properties fit, so there is no NBT to serialise and no tick handler
 * to register. If a rack ever needs to hold a stack rather than a single
 * strain marker, that's when it earns a BlockEntity.
 */
public class DryingRackBlock extends TurnableBlock implements PolymerTexturedBlock {
    public static final MapCodec<DryingRackBlock> CODEC = createCodec(DryingRackBlock::new);

    public static final BooleanProperty OCCUPIED = BooleanProperty.of("occupied");
    public static final EnumProperty<Strain> STRAIN = EnumProperty.of("strain", Strain.class);
    /** Peak cure. Pull it here and the grade survives intact. */
    public static final int READY_DRYNESS = 3;
    /** One stage past peak: still usable, but a grade worse. */
    public static final int MAX_DRYNESS = 4;
    public static final IntProperty DRYNESS = IntProperty.of("dryness", 0, MAX_DRYNESS);
    /**
     * Grade of what's hanging, so drying doesn't launder Fire into Swill.
     * Doesn't affect the model, so it costs no extra Polymer states.
     */
    public static final IntProperty QUALITY = IntProperty.of("quality", 0, 3);

    private static final int DRY_TICKS = 1200; // 60s per stage, 4 min total

    private final Map<Direction, BlockState> emptyCarriers;
    /** Indexed by dryness 0..MAX_DRYNESS, then by which way the rack faces. */
    private final Map<Strain, List<Map<Direction, BlockState>>> occupiedCarriers =
            new EnumMap<>(Strain.class);

    public DryingRackBlock(Settings settings) {
        super(settings);
        this.setDefaultState(getDefaultState()
                .with(OCCUPIED, false)
                .with(STRAIN, Strain.KUSH)
                .with(DRYNESS, 0)
                .with(QUALITY, Quality.MIDS.index()));

        // The bare rack is the same from every side, so one carrier does for
        // all four angles -- the buds are what hang on the front.
        BlockState bare = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/drying_rack_empty")),
                () -> Blocks.BARREL.getDefaultState(),
                "drying_rack_empty");
        Map<Direction, BlockState> everyWay = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            everyWay.put(facing, bare);
        }
        this.emptyCarriers = everyWay;

        // A state per (strain, dryness, facing) so the block shows how far
        // along the cure is and which way it was hung. FULL_BLOCK's pool is in
        // four figures, so 120 is affordable -- which is the only reason this
        // one is allowed to be directional at all.
        for (Strain strain : Strain.values()) {
            List<Map<Direction, BlockState>> byDryness = new ArrayList<>();
            for (int dryness = 0; dryness <= MAX_DRYNESS; dryness++) {
                byDryness.add(carriers(BlockModelType.FULL_BLOCK,
                        "drying_rack_" + strain.id() + "_" + dryness,
                        () -> Blocks.BARREL.getDefaultState()));
            }
            this.occupiedCarriers.put(strain, byDryness);
        }
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(OCCUPIED, STRAIN, DRYNESS, QUALITY);
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
                                         PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (state.get(OCCUPIED)) {
            return collect(state, world, pos, player);
        }

        Strain strain = TrapContent.strainOfRawBud(stack.getItem());
        if (strain == null) {
            return ActionResult.PASS;
        }

        if (!world.isClient) {
            stack.decrementUnlessCreative(1, player);
            world.setBlockState(pos, state.with(OCCUPIED, true).with(STRAIN, strain).with(DRYNESS, 0)
                    .with(QUALITY, TrapComponents.get(stack).index()));
            world.scheduleBlockTick(pos, this, DRY_TICKS);
            world.playSound(null, pos, SoundEvents.BLOCK_GRASS_PLACE, SoundCategory.BLOCKS, 0.8F, 1.0F);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        return state.get(OCCUPIED) ? collect(state, world, pos, player) : ActionResult.PASS;
    }

    /**
     * The curing window. Pulling early or leaving it too long both cost grade,
     * so the rack is a decision rather than a timer you wait out.
     *
     * <ul>
     *   <li>stage 0 -- soaking wet, refuses to be collected at all
     *   <li>stages 1-2 -- collectable, but one grade lost per stage short
     *   <li>stage 3 -- peak, full grade
     *   <li>stage 4 -- overdried, one grade lost
     * </ul>
     */
    private static int gradePenalty(int dryness) {
        if (dryness >= MAX_DRYNESS) {
            return 1;                       // left hanging too long
        }
        return READY_DRYNESS - dryness;     // 0 at peak, 1-2 if rushed
    }

    private ActionResult collect(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (world.isClient) {
            return state.get(DRYNESS) == 0 ? ActionResult.PASS : ActionResult.SUCCESS;
        }
        ItemStack out = take(state, world, pos);
        if (out.isEmpty()) {
            return ActionResult.PASS; // still soaking, nothing to take
        }
        if (!player.giveItemStack(out)) {
            player.dropItem(out, false);
        }
        return ActionResult.SUCCESS;
    }

    /**
     * Pull whatever is hanging here. Empty if there is nothing worth pulling.
     *
     * Split out of {@link #collect} so a hired hand can work a rack without
     * having to pretend to be a player. The grade arithmetic lives here and
     * nowhere else, which is the point -- a crew that cured buds by its own
     * rules would quietly be a way round the curing window.
     */
    public static ItemStack take(BlockState state, World world, BlockPos pos) {
        int dryness = state.get(DRYNESS);
        if (!state.get(OCCUPIED) || dryness == 0) {
            return ItemStack.EMPTY;
        }
        Strain strain = state.get(STRAIN);
        Quality grade = Quality.byIndex(state.get(QUALITY) - gradePenalty(dryness));
        // Rushing costs yield as well as grade, so patience pays twice.
        int count = dryness >= READY_DRYNESS ? 2 : 1;
        ItemStack out = TrapComponents.apply(
                new ItemStack(TrapContent.driedBud(strain), count), grade);
        world.setBlockState(pos, state.with(OCCUPIED, false).with(DRYNESS, 0));
        world.playSound(null, pos, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 0.8F, 1.2F);
        return out;
    }

    /** Hang one fresh bud out of a stack. False if the rack is busy or it isn't one. */
    public static boolean hang(BlockState state, World world, BlockPos pos, ItemStack fresh) {
        Strain strain = TrapContent.strainOfRawBud(fresh.getItem());
        if (strain == null || state.get(OCCUPIED)) {
            return false;
        }
        world.setBlockState(pos, state.with(OCCUPIED, true).with(STRAIN, strain).with(DRYNESS, 0)
                .with(QUALITY, TrapComponents.get(fresh).index()));
        world.scheduleBlockTick(pos, state.getBlock(), DRY_TICKS);
        fresh.decrement(1);
        world.playSound(null, pos, SoundEvents.BLOCK_GRASS_PLACE, SoundCategory.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (!state.get(OCCUPIED) || state.get(DRYNESS) >= MAX_DRYNESS) {
            return;
        }
        int next = state.get(DRYNESS) + 1;
        world.setBlockState(pos, state.with(DRYNESS, next));
        if (next == READY_DRYNESS) {
            // One clear cue at the moment it peaks. The texture already
            // shows it, but only if you happen to look -- this reaches you
            // from the next room, which is where you usually are.
            world.playSound(null, pos, SoundEvents.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES,
                    SoundCategory.BLOCKS, 0.55F, 1.6F);
            world.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5,
                    8, 0.3, 0.2, 0.3, 0.0);
        }
        if (next < MAX_DRYNESS) {
            // Peak gets a long grace period before it spoils -- you should have
            // to forget about it, not lose it for stepping away for a minute.
            world.scheduleBlockTick(pos, this, next == READY_DRYNESS ? DRY_TICKS * 5 : DRY_TICKS);
        }
    }

    /** Break as a wooden cabinet, so the sound and particles match the model. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        if (!state.get(OCCUPIED)) {
            return emptyCarriers.get(state.get(FACING));
        }
        return occupiedCarriers.get(state.get(STRAIN))
                .get(state.get(DRYNESS)).get(state.get(FACING));
    }
}
