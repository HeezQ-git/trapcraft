package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;
import net.minecraft.util.BlockRotation;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.math.Direction;

/**
 * A strongbox with three locks, which is where The Climb lives.
 *
 * A single squat block on purpose: the casino floor already has two tall
 * cabinets and a low table, and a heavy armoured box you crouch over is a
 * fourth silhouette. It also suits the game -- doors and locks rather than
 * reels and wheels.
 *
 * The game is in {@link ClimbScreenHandler}; this is the furniture.
 */
public class ClimbBlock extends Block implements PolymerBlock, PolymerTexturedBlock {

    /** Which way the player stands. The model is drawn facing north. */
    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    /**
     * Degrees to turn the model so its front points `facing`.
     *
     * The same table vanilla writes into a furnace blockstate, and for the
     * same reason: the model is drawn once facing north and the other three
     * sides are that one model spun. Each angle is its own carrier, so this
     * costs four from the Polymer pool instead of one -- see BarBlock.
     */
    private static int spin(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // Opposite, like a furnace: the face you decorated points at whoever
        // put it down, not away from them.
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
    private final java.util.Map<Direction, BlockState> carriers =
            new java.util.EnumMap<>(Direction.class);

    public ClimbBlock(Settings settings) {
        super(settings);
        // A full cube, so FULL_BLOCK is honest here and costs nothing from the
        // thin TRANSPARENT_BLOCK pool. check_models.py verifies the claim.
        for (Direction facing : Direction.Type.HORIZONTAL) {
            carriers.put(facing, TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/climb"),
                                0, spin(facing)),
                () -> Blocks.IRON_BLOCK.getDefaultState(), "climb facing " + facing.asString()));
        }
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

    /** Break as metal: it's a safe. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.IRON_BLOCK.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity gambler)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_IRON_DOOR_OPEN,
                SoundCategory.BLOCKS, 0.6F, 1.4F);
        TrapHouse.House house = TrapHouse.at(world, pos);
        gambler.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new ClimbScreenHandler(syncId, inventory, house),
                TrapHouse.sign(Text.literal("The Climb").formatted(Formatting.GOLD, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
