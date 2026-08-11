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
import net.minecraft.util.BlockRotation;
import net.minecraft.util.BlockMirror;
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
 * A machine that takes your emeralds and occasionally gives some back.
 *
 * Two blocks tall, like a door or a bed: a cabinet you stand at rather than a
 * cube on the floor. The halves are one block with a HALF property, so placing
 * puts the top on and breaking either end takes both -- the alternative is
 * players left holding half a slot machine.
 *
 * What it pays is in {@link TrapMath#slotPayout}: 85% back over time, and
 * about three spins in four pay nothing.
 */
public class SlotMachineBlock extends Block implements PolymerBlock, PolymerTexturedBlock {

    private static int spin(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }


    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    /** Which way the player stands. The model is drawn facing north. */
    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    private final java.util.Map<Direction, BlockState> lower =
            new java.util.EnumMap<>(Direction.class);
    private final java.util.Map<Direction, BlockState> upper =
            new java.util.EnumMap<>(Direction.class);

    public SlotMachineBlock(Settings settings) {
        super(settings);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            lower.put(facing, TrapPolymer.requestOrFallback(
                // TRANSPARENT_BLOCK, not FULL_BLOCK. The carrier is what the
                // client believes about this block, and believing a table with
                // legs is a solid cube makes it cull the faces of whatever is
                // underneath -- so you stand on a floor above a cave and see
                // straight through into it. Any model that doesn't fill the
                // cube has to say so.
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/slot_machine_lower"),
                                    0, spin(facing)),
                () -> Blocks.RED_TERRACOTTA.getDefaultState(), "slot_machine_lower facing " + facing.asString()));
            upper.put(facing, TrapPolymer.requestOrFallback(
                // TRANSPARENT_BLOCK, not FULL_BLOCK. The carrier is what the
                // client believes about this block, and believing a table with
                // legs is a solid cube makes it cull the faces of whatever is
                // underneath -- so you stand on a floor above a cave and see
                // straight through into it. Any model that doesn't fill the
                // cube has to say so.
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/slot_machine_upper"),
                                    0, spin(facing)),
                () -> Blocks.RED_TERRACOTTA.getDefaultState(), "slot_machine_upper facing " + facing.asString()));
        }
        setDefaultState(getDefaultState().with(HALF, DoubleBlockHalf.LOWER)
                .with(FACING, Direction.NORTH));
    }

    /** Start ticking a machine that has just been pulled. */
    public static void watch(SlotScreenHandler machine) {
        TrapTables.watch(machine);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(HALF, FACING);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return (state.get(HALF) == DoubleBlockHalf.UPPER ? upper : lower)
                .get(state.get(FACING));
    }

    /** Break as metal: it's a machine full of levers and coin. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.IRON_BLOCK.getDefaultState();
    }

    // --- standing it up -------------------------------------------------------

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockPos pos = context.getBlockPos();
        if (pos.getY() >= context.getWorld().getTopYInclusive()
                || !context.getWorld().getBlockState(pos.up()).isReplaceable()) {
            return null;   // no headroom: refuse rather than place a half machine
        }
        // Faces whoever put it down, like a furnace. onPlaced builds the upper
        // half from THIS state, so it inherits the facing -- both halves turn
        // together or the cabinet gets its top on backwards.
        return getDefaultState().with(FACING,
                context.getHorizontalPlayerFacing().getOpposite());
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

    /**
     * Take the other half with it.
     *
     * Uses flag 35 (NOTIFY_ALL | SKIP_DROPS) so the partner vanishes without
     * dropping a second machine -- the loot table is on the lower half only.
     */
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
        // If the other half goes by any route -- pistons, worldedit, a mod --
        // this one goes too rather than being left standing.
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
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(),
                SoundCategory.BLOCKS, 0.7F, 1.5F);
        TrapHouse.House house = TrapHouse.at(world, pos);
        gambler.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new SlotScreenHandler(syncId, inventory, house),
                TrapHouse.sign(Text.literal("Lucky Streak").formatted(Formatting.GOLD, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
