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

/**
 * The counter the floor actually runs on.
 *
 * Wired to a casino the same way a machine is -- right-click it holding the
 * card -- and then stocked by hand out of whatever you grew. Everything the
 * punters are handed at the door comes out of here, and a dry one empties the
 * room inside a few minutes.
 *
 * A table on legs, so TRANSPARENT_BLOCK; check_models.py enforces that.
 */
public class BarBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public BarBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/casino_bar")),
                () -> Blocks.DARK_OAK_PLANKS.getDefaultState(), "casino_bar");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
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
        keeper.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new BarScreenHandler(syncId, inventory, house),
                TrapHouse.sign(Text.literal("The Bar")
                        .formatted(Formatting.GOLD, Formatting.BOLD), house)));
        return ActionResult.SUCCESS;
    }
}
