package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
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
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.Map;

/**
 * The Drop: a tall board of pegs with nine slots along the bottom.
 *
 * Two blocks tall, because a plinko board you look down at makes no sense --
 * the whole appeal is watching something fall, and it has to be taller than
 * you for that to read. Same double-block handling as the slot machine: the
 * halves are one block with a HALF property, so placing puts the top on and
 * breaking either end takes both.
 *
 * The game is in {@link PlinkoScreenHandler}; this is the cabinet.
 */
public class PlinkoBlock extends TurnableBlock implements PolymerBlock, PolymerTexturedBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    private final Map<Direction, BlockState> lowerCarriers;
    private final Map<Direction, BlockState> upperCarriers;

    public PlinkoBlock(Settings settings) {
        super(settings);
        // FULL_BLOCK (note block states), not the leaf pool: shader packs
        // wave every leaf state as foliage, and a board you watch a ball fall
        // down cannot also be swaying. The shallow wall-board became a
        // full-depth cabinet so the shell is closed and the solid carrier is
        // honest; check_models.py measures the coverage and fails the deploy
        // rather than trusting this comment.
        //
        // Eight states, four per half. The cabinet is all face: the pegs and
        // the tray hang off the front, so it has to point at the room.
        this.lowerCarriers = carriers(BlockModelType.FULL_BLOCK, "plinko_lower",
                () -> Blocks.BLUE_TERRACOTTA.getDefaultState());
        this.upperCarriers = carriers(BlockModelType.FULL_BLOCK, "plinko_upper",
                () -> Blocks.BLUE_TERRACOTTA.getDefaultState());
        setDefaultState(getDefaultState().with(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(HALF);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        Map<Direction, BlockState> half =
                state.get(HALF) == DoubleBlockHalf.UPPER ? upperCarriers : lowerCarriers;
        return half.get(state.get(FACING));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    // --- standing it up -------------------------------------------------------

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockPos pos = context.getBlockPos();
        if (pos.getY() >= context.getWorld().getTopYInclusive()
                || !context.getWorld().getBlockState(pos.up()).isReplaceable()) {
            return null;   // no headroom: refuse rather than place half a board
        }
        return super.getPlacementState(context);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         net.minecraft.entity.LivingEntity placer,
                         net.minecraft.item.ItemStack stack) {
        world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), 3);
    }

    @Override
    protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        if (state.get(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = world.getBlockState(pos.down());
            return below.isOf(this) && below.get(HALF) == DoubleBlockHalf.LOWER;
        }
        return super.canPlaceAt(state, world, pos);
    }

    /** Take the other half with it. Flag 35 skips drops so it drops once. */
    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient()) {
            BlockPos other = state.get(HALF) == DoubleBlockHalf.LOWER ? pos.up() : pos.down();
            BlockState partner = world.getBlockState(other);
            if (partner.isOf(this) && partner.get(HALF) != state.get(HALF)) {
                world.setBlockState(other, Blocks.AIR.getDefaultState(), 35);
                world.syncWorldEvent(player, 2001, other, Block.getRawIdFromState(partner));
            }
        }
        return super.onBreak(world, pos, state, player);
    }

    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, WorldView world,
                                                   net.minecraft.world.tick.ScheduledTickView ticks,
                                                   BlockPos pos, Direction direction,
                                                   BlockPos neighborPos, BlockState neighborState,
                                                   net.minecraft.util.math.random.Random random) {
        boolean towardsPartner = state.get(HALF) == DoubleBlockHalf.LOWER
                ? direction == Direction.UP : direction == Direction.DOWN;
        if (towardsPartner && (!neighborState.isOf(this)
                || neighborState.get(HALF) == state.get(HALF))) {
            return Blocks.AIR.getDefaultState();
        }
        return state;
    }

    // --- playing --------------------------------------------------------------

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity gambler)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(),
                SoundCategory.BLOCKS, 0.7F, 1.6F);
        TrapHouse.House house = TrapHouse.at(world, pos);
        gambler.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new PlinkoScreenHandler(syncId, inventory, house),
                TrapHouse.sign(Text.literal("Plinko").formatted(Formatting.AQUA, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
