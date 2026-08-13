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
 * The furniture for {@link BlackjackScreenHandler}.
 *
 * A table on four legs, which costs from the thin see-through pool and
 * has to: a legged model on a solid carrier makes the client cull whatever is
 * under and beside it. check_models.py measures the coverage and fails the
 * deploy rather than trusting this comment.
 *
 * Four states rather than one, because a dealer's side is only a dealer's
 * side if you can point it at the dealer.
 */
public class BlackjackBlock extends TurnableBlock implements PolymerBlock, PolymerTexturedBlock {
    private final Map<Direction, BlockState> carriers;

    public BlackjackBlock(Settings settings) {
        super(settings);
        // A see-through carrier (TrapPolymer.NON_SOLID), not FULL_BLOCK. It is a table on four legs, and a
        // carrier that claims to be a solid cube makes the client cull the
        // faces of whatever is under and beside it -- so a table on a floor
        // above a cave shows you the cave. check_models.py measures the
        // coverage and fails the deploy rather than trusting this comment.
        this.carriers = carriers(TrapPolymer.NON_SOLID, "blackjack",
                () -> Blocks.GREEN_TERRACOTTA.getDefaultState());
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
