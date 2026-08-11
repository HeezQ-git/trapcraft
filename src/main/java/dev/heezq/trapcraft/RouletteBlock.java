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
 * The other table in the casino.
 *
 * One block, not two: you stand AT a roulette table rather than in front of
 * it, and a waist-high table next to the slot machine's cabinet is what makes
 * a room of them read as a casino floor instead of a row of vending machines.
 *
 * The game is in {@link RouletteScreenHandler}; this is the furniture.
 */
public class RouletteBlock extends Block implements PolymerBlock, PolymerTexturedBlock {

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

    public RouletteBlock(Settings settings) {
        super(settings);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            carriers.put(facing, TrapPolymer.requestOrFallback(
                // TRANSPARENT_BLOCK, not FULL_BLOCK. The carrier is what the
                // client believes about this block, and believing a table with
                // legs is a solid cube makes it cull the faces of whatever is
                // underneath -- so you stand on a floor above a cave and see
                // straight through into it. Any model that doesn't fill the
                // cube has to say so.
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/roulette"),
                                0, spin(facing)),
                () -> Blocks.GREEN_TERRACOTTA.getDefaultState(), "roulette facing " + facing.asString()));
        }
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

    /** Break as wood: it's a table, whatever the felt says. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity gambler)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(),
                SoundCategory.BLOCKS, 0.7F, 1.2F);
        TrapHouse.House house = TrapHouse.at(world, pos);
        gambler.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new RouletteScreenHandler(syncId, inventory, house),
                TrapHouse.sign(Text.literal("Roulette").formatted(Formatting.GREEN, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
