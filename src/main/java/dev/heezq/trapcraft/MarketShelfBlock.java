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
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
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
 * A shelf sells what is in the container directly beneath it -- same rule as
 * the market stall, for the same reasons -- but it sells to the CITY rather
 * than to players. Villagers walk out of the housing, come to the building,
 * take a lot off it and pay {@link TrapShops#RETAIL} of the market price,
 * which is about double what the counter would give for the same crate.
 *
 * A supermarket is a row of these. There is deliberately no concept of a shop
 * building anywhere in the code: a shelf over a barrel already looks like a
 * shop counter, and twelve of them in a room already looks like a supermarket,
 * so the thing that would have needed defining defines itself.
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
     * Owner takes the money; anybody else reads the sign.
     *
     * No screen. Everything a shelf has to say is three numbers, and this mod
     * already asks people to click through enough chests -- the interesting
     * part of a shop is the queue outside it, not another grid.
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

        if (!shelf.owner().equals(who.getUuid())) {
            who.sendMessage(Text.literal(shelf.ownerName() + "'s shelf. ")
                    .formatted(Formatting.WHITE)
                    .append(Text.literal(shelf.sold() + " sold to the town so far.")
                            .formatted(Formatting.GRAY)), false);
            return ActionResult.SUCCESS;
        }

        int takings = TrapShops.collect(who, shelf);
        Inventory box = TrapShops.stockOf(ground, shelf);
        int lines = 0;
        if (box != null) {
            for (int slot = 0; slot < box.size(); slot++) {
                if (!box.getStack(slot).isEmpty()) {
                    lines++;
                }
            }
        }
        who.sendMessage(Text.literal("Your shelf. ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(takings > 0
                        ? Text.literal("+" + takings + "e").formatted(Formatting.GREEN)
                        : Text.literal("Till empty.").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("   " + shelf.sold() + " sold, "
                        + TrapHomes.population() + " townspeople about")
                        .formatted(Formatting.GRAY))
                .append(Text.literal(box == null
                                ? "\n  Nothing underneath it. Put a chest or barrel there."
                                : lines == 0 ? "\n  Empty. Stock it and they'll come."
                                : "").formatted(Formatting.RED)), false);
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
