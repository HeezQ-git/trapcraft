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
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * Water pipe. The middle rung of the three ways to smoke:
 * joint (x1.0, portable) -> bong (x1.5, needs setup) -> tlok (x2.2, a ritual).
 *
 * Water is one-time; the load is per hit. Costs an extra tolerance level on top
 * of the usual, so the stronger hit genuinely burns through your ceiling faster
 * rather than being strictly better than a joint.
 */
public class BongBlock extends TurnableBlock implements PolymerTexturedBlock {
    public static final MapCodec<BongBlock> CODEC = createCodec(BongBlock::new);

    public static final BooleanProperty WATER = BooleanProperty.of("water");
    public static final BooleanProperty LOADED = BooleanProperty.of("loaded");
    /** What's in the bowl. Blockstate, so a restart doesn't eat your load. */
    public static final net.minecraft.state.property.EnumProperty<Strain> STRAIN =
            net.minecraft.state.property.EnumProperty.of("strain", Strain.class);
    public static final net.minecraft.state.property.IntProperty GRADE =
            net.minecraft.state.property.IntProperty.of("grade", 0, 3);

    public static final float POTENCY = 1.5F;
    public static final int EXTRA_TOLERANCE = 1;

    private final Map<Direction, BlockState> dryCarriers;
    private final Map<Direction, BlockState> wetCarriers;
    private final Map<Direction, BlockState> loadedCarriers;

    public BongBlock(Settings settings) {
        super(settings);
        this.setDefaultState(getDefaultState()
                .with(WATER, false).with(LOADED, false)
                .with(STRAIN, Strain.KUSH).with(GRADE, Quality.MIDS.index()));

        this.dryCarriers = request("bong_dry");
        this.wetCarriers = request("bong_wet");
        this.loadedCarriers = request("bong_loaded");
    }

    private static Map<Direction, BlockState> request(String name) {
        return carriers(TrapPolymer.NON_SOLID, name,
                () -> Blocks.GLASS.getDefaultState());
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
                                         ShapeContext context) {
        return SHAPES.get(state.get(FACING));
    }

    /**
     * Beaker foot, tube, and the downstem poking out to one side.
     *
     * The downstem is why this block gets a shape per angle rather than one
     * shape: turn the model without turning the outline and you get a bong you
     * can see sticking out to the right and have to click on the left.
     */
    private static final Map<Direction, VoxelShape> SHAPES = shapes();

    private static Map<Direction, VoxelShape> shapes() {
        Map<Direction, VoxelShape> byFacing = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            byFacing.put(facing, VoxelShapes.union(
                    turnBox(facing, 4, 0, 4, 12, 3, 12),
                    turnBox(facing, 5.5, 3, 5.5, 10.5, 13.5, 10.5),
                    turnBox(facing, 11, 5, 6.5, 15, 9.5, 9.5)));
        }
        return byFacing;
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(WATER, LOADED, STRAIN, GRADE);
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
                                         PlayerEntity player, Hand hand, BlockHitResult hit) {
        // Fill: water bucket, once.
        if (!state.get(WATER) && stack.isOf(Items.WATER_BUCKET)) {
            if (!world.isClient) {
                if (!player.isCreative()) {
                    player.setStackInHand(hand, new ItemStack(Items.BUCKET));
                }
                world.setBlockState(pos, state.with(WATER, true));
                world.playSound(null, pos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0F, 1.0F);
            }
            return ActionResult.SUCCESS;
        }

        // Load: a cured bud of any strain.
        Strain strain = TrapContent.strainOfDriedBud(stack.getItem());
        if (strain != null && !state.get(LOADED)) {
            if (!state.get(WATER)) {
                if (!world.isClient) {
                    player.sendMessage(Text.literal("Needs water").formatted(Formatting.RED), true);
                }
                return ActionResult.SUCCESS;
            }
            if (!world.isClient) {
                stack.decrementUnlessCreative(1, player);
                world.setBlockState(pos, state.with(LOADED, true)
                        .with(STRAIN, strain)
                        .with(GRADE, TrapComponents.get(stack).index()));
                world.playSound(null, pos, SoundEvents.BLOCK_GRASS_PLACE, SoundCategory.BLOCKS, 0.8F, 1.3F);
            }
            return ActionResult.SUCCESS;
        }

        return smoke(state, world, pos, player);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        return smoke(state, world, pos, player);
    }

    private ActionResult smoke(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (!state.get(LOADED)) {
            return ActionResult.PASS;
        }
        if (world instanceof ServerWorld server) {
            TrapNet.play(player, TrapNet.BONG_HIT);
            TrapContent.hit(server, player, state.get(STRAIN),
                    Quality.byIndex(state.get(GRADE)), POTENCY, EXTRA_TOLERANCE);
            world.setBlockState(pos, state.with(LOADED, false));
            server.spawnParticles(ParticleTypes.BUBBLE_POP,
                    pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 12, 0.15, 0.1, 0.15, 0.05);
            // Smoke off the mouthpiece AND off the player. Cosy smoke lingers
            // for a couple of seconds, which is what the two-and-a-bit second
            // gesture needs -- the old burst was gone before the arms moved.
            server.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 10, 0.12, 0.15, 0.12, 0.01);
            TrapSmoke.exhale(server, player, 1.1F);
            // Layered: the splash is the water moving, the low bubble under
            // it is the draw. Either alone is thin.
            world.playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH,
                    SoundCategory.BLOCKS, 0.5F, 1.6F);
            world.playSound(null, pos, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT,
                    SoundCategory.BLOCKS, 0.7F, 0.7F);
        }
        return ActionResult.SUCCESS;
    }

    /** Break as glassware, so the sound and particles match the model. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.GLASS.getDefaultState();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        if (state.get(LOADED)) {
            return loadedCarriers.get(state.get(FACING));
        }
        return (state.get(WATER) ? wetCarriers : dryCarriers).get(state.get(FACING));
    }
}
