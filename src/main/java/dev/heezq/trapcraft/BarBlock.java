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
 * The counter the floor actually runs on.
 *
 * Wired to a casino the same way a machine is -- right-click it holding the
 * card -- and then stocked by hand out of whatever you grew. Everything the
 * punters are handed at the door comes out of here, and a dry one empties the
 * room inside a few minutes.
 *
 * A table on legs, so a see-through carrier; check_models.py enforces that.
 *
 * It has a FRONT -- a panelled counter face with a foot rail, and a back bar
 * of bottles standing behind it -- so it has to be placed facing somewhere.
 * Polymer can rotate a carrier the same way a vanilla blockstate does, but
 * each angle is its own carrier state, so a directional block costs four from
 * the pool instead of one. Worth it here: an unturnable counter means every
 * bar on the server faces north and half of them have their bottles in the
 * wall.
 */
public class BarBlock extends TurnableBlock implements PolymerBlock, PolymerTexturedBlock {
    private final Map<Direction, BlockState> carriers;

    public BarBlock(Settings settings) {
        super(settings);
        // FULL_BLOCK (note block states), not the leaf pool: shader packs
        // wave every leaf state as foliage, and the one block the owner works
        // at should not ripple while they do. The counter runs wall to wall
        // and the back bar boards reach the seams, so the shell is closed and
        // the solid carrier is honest; check_models.py measures the coverage
        // and fails the deploy rather than trusting this comment.
        this.carriers = carriers(BlockModelType.FULL_BLOCK, "casino_bar",
                () -> Blocks.DARK_OAK_PLANKS.getDefaultState());
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
            keeper.sendMessage(Text.literal("Nie podłączony do niczego. Kliknij PPM, "
                    + "trzymając licencję kasyna.").formatted(Formatting.GRAY), false);
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
                TrapHouse.sign(Text.literal("Bar")
                        .formatted(Formatting.GOLD, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
