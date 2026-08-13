package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.Map;

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
 * The owner clicking a shelf opens the shelf itself and stocks it, like any
 * chest -- see {@link MarketShelfBlockEntity}. Anybody else gets the shop
 * window and can buy off it at exactly what a townsperson pays, duty and all,
 * which is what makes a kiosk somewhere your friends shop rather than a
 * machine for turning villagers into emeralds. The back office -- prices,
 * takings, staff -- is at the till, where the money is.
 */
public class MarketShelfBlock extends TurnableBlock
        implements PolymerBlock, PolymerTexturedBlock, BlockEntityProvider {
    private final Map<Direction, BlockState> carriers;

    public MarketShelfBlock(Settings settings) {
        super(settings);
        this.carriers = carriers(
                BlockModelType.FULL_BLOCK, "market_shelf",
                () -> Blocks.OAK_PLANKS.getDefaultState());
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
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new MarketShelfBlockEntity(pos, state);
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
     * till it belongs to. What a shelf has is what is ON it: the owner opens
     * it and stocks it, everybody else buys off it.
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
            who.sendMessage(Text.literal("Półka bez sklepu. ")
                    .formatted(Formatting.YELLOW)
                    .append(Text.literal("Postaw kasę sklepową w promieniu " + TrapShops.REACH
                            + " bloków od niej.").formatted(Formatting.GRAY)), false);
        }
        // The owner opens the shelf and fills it, like any chest; everybody
        // else gets the shop window. One counter, two sides of it.
        //
        // A shelf whose till has gone opens for anybody, deliberately: there
        // is no shop for it to be the window of, and stock nobody can reach
        // without breaking the block is stock the mod has eaten.
        if (shop == null || shop.owner().equals(who.getUuid())) {
            if (world.getBlockEntity(pos) instanceof MarketShelfBlockEntity stock) {
                who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                        (syncId, inventory, ignored) ->
                                GenericContainerScreenHandler.createGeneric9x3(
                                        syncId, inventory, stock),
                        Text.literal(shop == null ? "Półka" : shop.name())
                                .formatted(Formatting.GOLD)));
            }
            return ActionResult.SUCCESS;
        }
        TrapShops.Shop theirs = shop;
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new ShelfScreenHandler(syncId, inventory, theirs),
                Text.literal(shop.name()).formatted(Formatting.GOLD)));
        return ActionResult.SUCCESS;
    }

    /** Taken down with stock on it: the stock lands on the floor, like a chest. */
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        ItemScatterer.onStateReplaced(state, world, pos);
        TrapShops.release(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
