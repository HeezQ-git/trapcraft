package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
 * The counter townspeople queue at.
 *
 * A shelf is where somebody stands to be served. It belongs to the nearest
 * {@link ShopTillBlock} within {@link TrapShops#REACH}, and everything a shop
 * HAS -- its name, its prices, its cash register -- lives on that till.
 *
 * That is the whole difference from the first version, which made every shelf
 * its own little business with its own barrel and its own till. Twelve of
 * those is not a supermarket, it is twelve corner shops in a row and twelve
 * things to keep stocked. Twelve of these is one building.
 *
 * <h2>Two sides of one counter</h2>
 *
 * The owner clicking a shelf gets the back office, same as the till gives
 * them. Anybody else gets the shop window and can buy off it at exactly what a
 * townsperson pays, duty and all -- which is what makes a kiosk somewhere your
 * friends shop rather than a machine for turning villagers into emeralds.
 */
public class MarketShelfBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public MarketShelfBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/market_shelf")),
                () -> Blocks.OAK_PLANKS.getDefaultState(), "market_shelf");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         LivingEntity placer, ItemStack stack) {
        if (world instanceof ServerWorld ground && placer instanceof ServerPlayerEntity owner) {
            TrapShops.claim(ground, pos, owner);
        }
    }

    /**
     * A shelf is a counter, not a business.
     *
     * Everything a shop has -- its name, its prices, its money -- lives on the
     * till it belongs to. Clicking a shelf tells you whose shop you are stood
     * in and points at the register.
     */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;
        TrapShops.Shelf shelf = TrapShops.at(ground, pos);
        if (shelf == null) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_BARREL_OPEN,
                SoundCategory.BLOCKS, 0.6F, 1.2F);
        TrapShops.Shop shop = TrapShops.ownerOf(shelf);
        if (shop == null) {
            who.sendMessage(Text.literal("A shelf with no shop. ")
                    .formatted(Formatting.YELLOW)
                    .append(Text.literal("Put a shop till within " + TrapShops.REACH
                            + " blocks of it.").formatted(Formatting.GRAY)), false);
            return ActionResult.SUCCESS;
        }
        TrapShops.Shop theirs = shop;
        // The owner gets the back office wherever they click; everybody else
        // gets the shop window. One counter, two sides of it.
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                shop.owner().equals(who.getUuid())
                        ? (syncId, inventory, ignored) ->
                                new ShopScreen(syncId, inventory, theirs)
                        : (syncId, inventory, ignored) ->
                                new ShelfScreenHandler(syncId, inventory, theirs),
                Text.literal(shop.name()).formatted(Formatting.GOLD)));
        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapShops.release(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
