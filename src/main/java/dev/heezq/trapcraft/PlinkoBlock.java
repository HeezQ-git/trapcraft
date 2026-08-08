package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import xyz.nucleoid.packettweaker.PacketContext;

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
public class PlinkoBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    private final BlockState lowerCarrier;
    private final BlockState upperCarrier;

    public PlinkoBlock(Settings settings) {
        super(settings);
        // TRANSPARENT_BLOCK: the board is a frame with gaps in it, and a
        // carrier that claims to be a solid cube makes the client cull the
        // faces of whatever is behind and below -- the roulette table shipped
        // exactly that bug and showed the caves under the floor.
        this.lowerCarrier = TrapPolymer.requestOrFallback(
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/plinko_lower")),
                () -> Blocks.BLUE_TERRACOTTA.getDefaultState(), "plinko_lower");
        this.upperCarrier = TrapPolymer.requestOrFallback(
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/plinko_upper")),
                () -> Blocks.BLUE_TERRACOTTA.getDefaultState(), "plinko_upper");
        setDefaultState(getDefaultState().with(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(HALF);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return state.get(HALF) == DoubleBlockHalf.UPPER ? upperCarrier : lowerCarrier;
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
        return getDefaultState();
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
        gambler.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new PlinkoScreenHandler(syncId, inventory),
                Text.literal("The Drop").formatted(Formatting.AQUA, Formatting.BOLD)));
        return ActionResult.SUCCESS;
    }
}
