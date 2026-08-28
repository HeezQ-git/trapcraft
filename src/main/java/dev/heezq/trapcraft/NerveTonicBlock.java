package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Bottles stood on a surface, up to four to a block, like candles.
 *
 * Candles are the model for the whole interaction and not just the look: a
 * plain right-click with another bottle adds one to the cluster, sneaking
 * places a fresh one in the next space along, and breaking the block hands
 * back however many were standing there.
 *
 * Carried on trapdoor states rather than leaves. See {@link TrapPolymer#INERT}
 * -- the short version is that a shelf of medicine swaying in the wind is the
 * bug this block was rebuilt to fix.
 */
public class NerveTonicBlock extends Block implements PolymerTexturedBlock {
    public static final int MAX = 4;
    public static final IntProperty BOTTLES = IntProperty.of("bottles", 1, MAX);

    /**
     * Where each bottle stands, as an offset from the middle of the block.
     *
     * gen_assets.py holds the same table under BOTTLE_SPOTS and builds the
     * models from it; this copy exists so the outline can be derived rather
     * than typed. Change one, change the other -- a cluster you can see in one
     * place and click in another is the same complaint the bong's outline was.
     */
    private static final double[][][] SPOTS = {
            {{0, 0}},
            {{-2.5, -1.5}, {2.5, 1.5}},
            {{-3, -1.5}, {2, -3}, {1, 3}},
            {{-3, -3}, {3, -2.5}, {-2.5, 3}, {3, 3}},
    };

    /** Half a bottle across the diagonal, which is the worst a spin can do. */
    private static final double REACH = 2.2 * Math.sqrt(2);
    /** 15 tall in the model, shrunk by the same 0.55 the generator uses. */
    private static final double TALL = 15 * 0.55;

    private static final VoxelShape[] SHAPES = shapes();

    private static VoxelShape[] shapes() {
        VoxelShape[] byCount = new VoxelShape[MAX];
        for (int i = 0; i < MAX; i++) {
            double x0 = 16, z0 = 16, x1 = 0, z1 = 0;
            for (double[] spot : SPOTS[i]) {
                x0 = Math.min(x0, 8 + spot[0] - REACH);
                x1 = Math.max(x1, 8 + spot[0] + REACH);
                z0 = Math.min(z0, 8 + spot[1] - REACH);
                z1 = Math.max(z1, 8 + spot[1] + REACH);
            }
            byCount[i] = createCuboidShape(x0, 0, z0, x1, TALL, z1);
        }
        return byCount;
    }

    private final BlockState[] carriers = new BlockState[MAX];

    public NerveTonicBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(BOTTLES, 1));
        for (int n = 1; n <= MAX; n++) {
            carriers[n - 1] = TrapPolymer.requestOrFallback(TrapPolymer.INERT,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/nerve_tonic_" + n)),
                    () -> Blocks.GLASS.getDefaultState(), "nerve_tonic_" + n);
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(BOTTLES);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
                                         ShapeContext context) {
        return SHAPES[state.get(BOTTLES) - 1];
    }

    /**
     * Another bottle on a cluster joins it instead of landing next to it.
     *
     * Straight off CandleBlock, sneak clause included: holding sneak is how
     * you say "beside, not on top of", and without it a shelf can never have
     * two separate singles touching.
     */
    @Override
    protected boolean canReplace(BlockState state, ItemPlacementContext context) {
        return !context.shouldCancelInteraction()
                && context.getStack().isOf(TrapContent.nerveTonic)
                && state.get(BOTTLES) < MAX
                || super.canReplace(state, context);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState existing = context.getWorld().getBlockState(context.getBlockPos());
        if (existing.isOf(this)) {
            // Not cycle(): that wraps four back round to one, and vanilla only
            // gets away with it because canReplace above already refused.
            return existing.with(BOTTLES, Math.min(existing.get(BOTTLES) + 1, MAX));
        }
        return getDefaultState();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers[state.get(BOTTLES) - 1];
    }

    /** Break as glassware, so the sound and shards match what is drawn. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.GLASS.getDefaultState();
    }
}
