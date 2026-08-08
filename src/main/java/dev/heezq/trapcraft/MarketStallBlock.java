package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * A stall you build, so the market is a place rather than a menu.
 *
 * Deliberately not a command. A shop you can open from the bottom of a ravine
 * makes hauling loot home pointless, and a trading post somebody actually
 * built at spawn is a thing the server shares.
 */
public class MarketStallBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public MarketStallBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                eu.pb4.polymer.blocks.api.PolymerBlockModel.of(
                        net.minecraft.util.Identifier.of("trapcraft:block/market_stall")),
                () -> Blocks.OAK_PLANKS.getDefaultState(), "market_stall");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    /** Break as timber: it's a market stall, not a machine. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity shopper)) {
            return ActionResult.SUCCESS;
        }

        world.playSound(null, pos, SoundEvents.BLOCK_BARREL_OPEN,
                SoundCategory.BLOCKS, 0.7F, 1.1F);
        shopper.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new ShopScreenHandler(syncId, inventory),
                Text.literal("The Market").formatted(Formatting.DARK_GREEN)));
        return ActionResult.SUCCESS;
    }
}
