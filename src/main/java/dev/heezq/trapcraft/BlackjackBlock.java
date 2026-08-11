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
 * The furniture for {@link BlackjackScreenHandler}.
 *
 * A table on four legs, which costs one state from the thin
 * TRANSPARENT_BLOCK pool and has to: a legged model on a solid carrier makes
 * the client cull whatever is under and beside it. check_models.py measures
 * the coverage and fails the deploy rather than trusting this comment.
 */
public class BlackjackBlock extends Block implements PolymerBlock, PolymerTexturedBlock {

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

    public BlackjackBlock(Settings settings) {
        super(settings);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            carriers.put(facing, TrapPolymer.requestOrFallback(
                // TRANSPARENT_BLOCK, not FULL_BLOCK. It is a table on four
                // legs now, and a carrier that claims to be a solid cube makes
                // the client cull the faces of whatever is under and beside
                // it -- so a table on a floor above a cave shows you the cave.
                // check_models.py measures the coverage and fails the deploy
                // rather than trusting this comment.
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/blackjack"),
                                0, spin(facing)),
                () -> Blocks.GREEN_TERRACOTTA.getDefaultState(), "blackjack facing " + facing.asString()));
        }
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

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
        world.playSound(null, pos, SoundEvents.ITEM_BOOK_PAGE_TURN,
                SoundCategory.BLOCKS, 0.7F, 1.3F);
        TrapHouse.House house = TrapHouse.at(world, pos);
        gambler.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new BlackjackScreenHandler(syncId, inventory, house),
                TrapHouse.sign(Text.literal("Blackjack").formatted(Formatting.GREEN, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
