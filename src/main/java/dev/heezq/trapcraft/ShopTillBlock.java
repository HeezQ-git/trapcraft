package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.component.DataComponentTypes;
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
 * The register, and therefore the shop.
 *
 * Put one down and you have a business. Every market shelf within
 * {@link TrapShops#REACH} joins it, stock is any container under the till or
 * under one of those shelves, every sale in the building lands in this one
 * cash box, and the price policy is set here for all of it.
 *
 * The first version had no such thing -- each shelf was its own shop with its
 * own barrel and its own till, so a supermarket was twelve corner shops in a
 * row and twelve things to keep stocked. This is the block that makes a
 * building a business.
 */
public class ShopTillBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public ShopTillBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/shop_till")),
                () -> Blocks.SMITHING_TABLE.getDefaultState(), "shop_till");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.SMITHING_TABLE.getDefaultState();
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         LivingEntity placer, ItemStack stack) {
        if (world instanceof ServerWorld ground && placer instanceof ServerPlayerEntity owner) {
            TrapShops.open(ground, pos, owner);
            // A named till is a named shop the moment it lands, the way a
            // named shulker is a named shulker. Saves anybody discovering the
            // rename button before they have a shop worth naming.
            Text named = stack.get(DataComponentTypes.CUSTOM_NAME);
            TrapShops.Shop shop = named == null ? null : TrapShops.shopAt(ground, pos);
            if (shop != null) {
                TrapShops.rename(shop, named.getString());
            }
        }
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;
        TrapShops.Shop shop = TrapShops.shopAt(ground, pos);
        if (shop == null) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_BARREL_OPEN,
                SoundCategory.BLOCKS, 0.7F, 1.1F);

        // Somebody else's shop is a shop window: what it sells and what it
        // charges, and nothing they can change.
        if (!shop.owner().equals(who.getUuid())) {
            who.sendMessage(Text.literal(shop.name()).formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal("   " + shop.ownerName() + "'s, "
                            + TrapShops.shelvesOf(shop).size() + " shelves, prices "
                            + shop.markupName().toLowerCase(java.util.Locale.ROOT))
                            .formatted(Formatting.GRAY)), false);
            return ActionResult.SUCCESS;
        }

        TrapShops.Shop mine = shop;
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new ShopScreen(syncId, inventory, mine),
                Text.literal(shop.name()).formatted(Formatting.GOLD)));
        return ActionResult.SUCCESS;
    }

    /** Taken down: the shop closes and the register spills. */
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapShops.closeShop(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
