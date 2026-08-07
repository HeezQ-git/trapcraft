package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
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
import net.minecraft.util.math.random.Random;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * Wet buds in, dried buds out.
 *
 * ponytail: state lives in blockstate properties, not a BlockEntity -- three
 * enum/int properties fit, so there is no NBT to serialise and no tick handler
 * to register. If a rack ever needs to hold a stack rather than a single
 * strain marker, that's when it earns a BlockEntity.
 */
public class DryingRackBlock extends Block implements PolymerTexturedBlock {
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

    private final BlockState emptyState;
    /** Indexed by dryness 0..MAX_DRYNESS. */
    private final Map<Strain, BlockState[]> occupiedStates = new EnumMap<>(Strain.class);

    public DryingRackBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(OCCUPIED, false)
                .with(STRAIN, Strain.KUSH)
                .with(DRYNESS, 0)
                .with(QUALITY, Quality.MIDS.index()));

        this.emptyState = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/drying_rack_empty")),
                () -> Blocks.BARREL.getDefaultState(),
                "drying_rack_empty");

        // A state per (strain, dryness) so the block shows how far along the
        // cure is. FULL_BLOCK's pool is in four figures, so 12 is nothing.
        for (Strain strain : Strain.values()) {
            BlockState[] byDryness = new BlockState[MAX_DRYNESS + 1];
            for (int dryness = 0; dryness <= MAX_DRYNESS; dryness++) {
                String name = "drying_rack_" + strain.id() + "_" + dryness;
                byDryness[dryness] = TrapPolymer.requestOrFallback(
                        BlockModelType.FULL_BLOCK,
                        PolymerBlockModel.of(Identifier.of("trapcraft:block/" + name)),
                        () -> Blocks.BARREL.getDefaultState(),
                        name);
            }
            this.occupiedStates.put(strain, byDryness);
        }
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
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
        int dryness = state.get(DRYNESS);
        if (dryness == 0) {
            return ActionResult.PASS; // still soaking, nothing to take
        }
        if (!world.isClient) {
            Strain strain = state.get(STRAIN);
            Quality grade = Quality.byIndex(state.get(QUALITY) - gradePenalty(dryness));
            // Rushing costs yield as well as grade, so patience pays twice.
            int count = dryness >= READY_DRYNESS ? 2 : 1;
            ItemStack out = TrapComponents.apply(
                    new ItemStack(TrapContent.driedBud(strain), count), grade);
            if (!player.giveItemStack(out)) {
                player.dropItem(out, false);
            }
            world.setBlockState(pos, state.with(OCCUPIED, false).with(DRYNESS, 0));
            world.playSound(null, pos, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 0.8F, 1.2F);
        }
        return ActionResult.SUCCESS;
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
            return emptyState;
        }
        return occupiedStates.get(state.get(STRAIN))[state.get(DRYNESS)];
    }
}
