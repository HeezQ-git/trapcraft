package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A block that is placed facing somewhere, and everything that takes.
 *
 * A till whose drawer faces the wall, a slot machine you can only play from
 * behind, a row of bars all pointing north because that is the only way they
 * come -- that is what an unturnable block looks like once somebody is
 * actually building with it.
 *
 * Polymer turns a carrier the same way a vanilla blockstate does: the model is
 * drawn once facing north and the other three sides are that one model spun.
 * The catch is that each angle is its own carrier state, so a directional
 * block costs four from the pool instead of one. Read the pool note in
 * {@link TrapPolymer} before adding another.
 *
 * Not vanilla's {@link net.minecraft.block.HorizontalFacingBlock}: that one
 * gives you rotate and mirror but makes {@code getCodec} abstract, which is
 * eighteen codecs to write for nothing, and it leaves the property and the
 * placement state to you anyway.
 */
public abstract class TurnableBlock extends Block {
    /** Which way the front points. Every model here is built facing north. */
    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    protected TurnableBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    /**
     * Degrees to turn a north-built model so its front points `facing`.
     *
     * The same table vanilla writes into a furnace blockstate, and for the
     * same reason.
     */
    public static int spin(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    /** One carrier per angle for a single model. Four states out of the pool. */
    public static Map<Direction, BlockState> carriers(BlockModelType type, String model,
                                                      Supplier<BlockState> fallback) {
        Map<Direction, BlockState> byFacing = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            byFacing.put(facing, TrapPolymer.requestOrFallback(type,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/" + model),
                            0, spin(facing)),
                    fallback, model + " facing " + facing.asString()));
        }
        return byFacing;
    }

    /**
     * A box built facing north, turned to match a model facing `facing`.
     *
     * Only the blocks with a hand-built outline shape need this -- rotate the
     * model without rotating the shape and you get a bong you can see poking
     * out one side and hit on the other.
     */
    protected static VoxelShape turnBox(Direction facing, double x0, double y0, double z0,
                                        double x1, double y1, double z1) {
        // Same spin() the model is given, so the outline cannot drift from
        // what is drawn. The arithmetic is in TrapMath because that is the one
        // class `gradlew test` can reach without a game on the classpath.
        double[] box = TrapMath.turn(spin(facing), x0, y0, z0, x1, y1, z1);
        return createCuboidShape(box[0], box[1], box[2], box[3], box[4], box[5]);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * Opposite, like a furnace: the face you decorated points at the person
     * who placed it, not away from them.
     *
     * Subclasses with their own placement rules override this and chain, so
     * they keep the facing they were placed with.
     */
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(FACING,
                context.getHorizontalPlayerFacing().getOpposite());
    }

    /** So /clone, structure blocks and the debug stick turn it honestly. */
    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }
}
