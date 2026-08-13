package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.Map;

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
public class ClimbBlock extends TurnableBlock implements PolymerBlock, PolymerTexturedBlock {
    private final Map<Direction, BlockState> carriers;

    public ClimbBlock(Settings settings) {
        super(settings);
        // A full cube, so FULL_BLOCK is honest here and costs nothing from the
        // thin see-through pool. check_models.py verifies the claim.
        this.carriers = carriers(BlockModelType.FULL_BLOCK, "climb",
                () -> Blocks.IRON_BLOCK.getDefaultState());
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
                TrapHouse.sign(Text.literal("Wspinaczka").formatted(Formatting.GOLD, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
