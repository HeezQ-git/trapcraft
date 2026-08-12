package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * The counter the floor actually runs on.
 *
 * Wired to a casino the same way a machine is -- right-click it holding the
 * card -- and then stocked by hand out of whatever you grew. Everything the
 * punters are handed at the door comes out of here, and a dry one empties the
 * room inside a few minutes.
 *
 * A table on legs, so TRANSPARENT_BLOCK; check_models.py enforces that.
 *
 * It has a FRONT -- a panelled counter face with a foot rail, and a back bar
 * of bottles standing behind it -- so it has to be placed facing somewhere.
 * Polymer can rotate a carrier the same way a vanilla blockstate does, but
 * each angle is its own carrier state, so a directional block costs four from
 * the pool instead of one. Worth it here: an unturnable counter means every
 * bar on the server faces north and half of them have their bottles in the
 * wall.
 */
public class BarBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    /** Which way the customer stands. The model is built facing north. */
    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    private final Map<Direction, BlockState> carriers = new EnumMap<>(Direction.class);

    public BarBlock(Settings settings) {
        super(settings);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            carriers.put(facing, TrapPolymer.requestOrFallback(
                    BlockModelType.TRANSPARENT_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/casino_bar"),
                            0, spin(facing)),
                    () -> Blocks.DARK_OAK_PLANKS.getDefaultState(),
                    "casino_bar facing " + facing.asString()));
        }
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    /**
     * Degrees to turn the model so its front points `facing`.
     *
     * The same table vanilla writes into a furnace blockstate, and for the
     * same reason: the model is drawn once, facing north, and the other three
     * sides are that one model spun.
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
        // Opposite, like a furnace: the face you decorated points at the
        // person who placed it, not away from them.
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

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.DARK_OAK_PLANKS.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity keeper)) {
            return ActionResult.SUCCESS;
        }
        TrapHouse.House house = TrapHouse.at(world, pos);
        if (house == null) {
            keeper.sendMessage(Text.literal("Not wired to anything. Right-click it "
                    + "holding a casino card.").formatted(Formatting.GRAY), false);
            world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.BLOCKS, 0.6F, 0.6F);
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_BARREL_OPEN,
                SoundCategory.BLOCKS, 0.7F, 1.1F);
        // This counter's own shelf. Four bars on one casino is four times the
        // room behind them, not four doors onto the same eighteen stacks.
        String wire = TrapHouse.wireAt(world, pos);
        keeper.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) ->
                        new BarScreenHandler(syncId, inventory, house, wire),
                TrapHouse.sign(Text.literal("The Bar")
                        .formatted(Formatting.GOLD, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
