package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
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
 *
 * <h2>One block, two shops</h2>
 *
 * Your own stall opens the open market -- the counter, full stock, full price,
 * always there. Somebody ELSE'S opens their table, which sells whatever they
 * put in the chest underneath at {@link TrapMath#STALL_RATE} of the same
 * prices.
 *
 * That is the whole point of not splitting these into two blocks. The market
 * is the backstop that means you are never actually stuck, and every stall in
 * town is a cheaper way to get the same thing off somebody who had it spare.
 * A city ends up with a market square in it because walking to one is worth
 * doing, not because anybody agreed to build one.
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

    /** Whoever puts it down owns it. */
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         net.minecraft.entity.LivingEntity placer, ItemStack stack) {
        if (world instanceof ServerWorld server && placer instanceof ServerPlayerEntity owner) {
            TrapStalls.claim(server, pos, owner);
        }
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity shopper)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_BARREL_OPEN,
                SoundCategory.BLOCKS, 0.7F, 1.1F);

        TrapStalls.Stall stall = TrapStalls.at((ServerWorld) world, pos);
        // An unclaimed stall is one placed before any of this existed, or by a
        // command block. It stays the open market rather than becoming
        // nobody's shop, which is what it has always been.
        if (stall == null || stall.owner().equals(shopper.getUuid())) {
            if (stall != null) {
                int takings = TrapStalls.collect(shopper, stall);
                if (takings > 0) {
                    shopper.sendMessage(Text.literal("Till: ").formatted(Formatting.DARK_GRAY)
                            .append(Text.literal("+" + takings + "e").formatted(Formatting.GREEN)),
                            false);
                }
            }
            shopper.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, ignored) -> new ShopScreenHandler(syncId, inventory),
                    Text.literal("The Market").formatted(Formatting.DARK_GREEN)));
            return ActionResult.SUCCESS;
        }

        shopper.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new StallScreenHandler(syncId, inventory, stall),
                Text.literal(stall.ownerName() + "'s Stall").formatted(Formatting.GOLD)));
        return ActionResult.SUCCESS;
    }

    /** Taken down: the register forgets it and the till spills on the floor. */
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapStalls.release(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
