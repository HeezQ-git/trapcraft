package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

/**
 * Somewhere to put the money.
 *
 * Twenty-five stacks of emeralds is what an elytra costs, and carrying that
 * around is most of an inventory. The wallet holds any amount in a single
 * slot, and -- the part that matters -- the money in it is still spendable:
 * {@link TrapMarket#wealthOf} counts it and {@link TrapMarket#collect} draws
 * from it once loose emeralds and blocks run out. So you can bank everything
 * and still walk up to a shelf and buy something.
 *
 * The balance rides on the stack as a component, which means it survives
 * chests, hoppers and death drops, and two wallets never merge because the
 * item doesn't stack.
 */
public class WalletItem extends Item implements PolymerItem {
    /** Nine emeralds to the block, as everywhere else. */
    public static final int PER_BLOCK = 9;

    private final Identifier model;

    public WalletItem(Settings settings, Identifier model) {
        super(settings);
        this.model = model;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.LEATHER;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    // --- the balance ----------------------------------------------------------

    public static int balanceOf(ItemStack stack) {
        Integer held = stack.get(TrapComponents.balance);
        return held == null ? 0 : held;
    }

    /**
     * Set the balance and restate it on the item.
     *
     * The component itself is stripped on the way to the client -- that is the
     * whole point of registering it with Polymer -- so a wallet that only
     * carried the number would look empty in your hand. The lore is written
     * server-side from the same number, so what you read is never stale.
     */
    public static void setBalance(ItemStack stack, int amount) {
        int held = Math.max(0, amount);
        stack.set(TrapComponents.balance, held);
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(held + "e").formatted(Formatting.GREEN, Formatting.BOLD)
                        .styled(style -> style.withItalic(false)),
                Text.literal(held == 0
                                ? "Pusty. Kliknij PPM, żeby go napełnić."
                                : held / PER_BLOCK + " bloków i " + held % PER_BLOCK + " luzem")
                        .formatted(Formatting.DARK_GRAY)
                        .styled(style -> style.withItalic(false)))));
    }

    /**
     * Sweep every emerald the player is carrying into this wallet.
     *
     * @return what went in
     */
    public static int depositAll(ServerPlayerEntity player, ItemStack wallet) {
        int found = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.EMERALD)) {
                found += stack.getCount();
                inventory.setStack(slot, ItemStack.EMPTY);
            } else if (stack.isOf(Items.EMERALD_BLOCK)) {
                found += stack.getCount() * PER_BLOCK;
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
        if (found > 0) {
            setBalance(wallet, balanceOf(wallet) + found);
        }
        return found;
    }

    /**
     * Take money out, blocks first where it would otherwise be absurd.
     *
     * Not {@link TrapMarket#pay}: moving your own money between a pocket and a
     * wallet neither creates nor destroys emeralds, and reporting it to the
     * economy would make the index lurch every time somebody tidied up.
     *
     * @return what came out, which may be less than asked for
     */
    public static int withdraw(ServerPlayerEntity player, ItemStack wallet, int amount) {
        int taken = Math.min(Math.max(0, amount), balanceOf(wallet));
        if (taken <= 0) {
            return 0;
        }
        setBalance(wallet, balanceOf(wallet) - taken);
        TrapMarket.handOver(player, taken);
        return taken;
    }

    /**
     * Stamp a wallet that arrived without a balance.
     *
     * A crafted, /given or looted wallet has no component and so would sit in
     * your hand as a blank pouch with no number on it until the first time you
     * opened it. Cheaper to check a component than to thread a stamp through
     * every route an item can enter the world by.
     */
    @Override
    public void inventoryTick(ItemStack stack, net.minecraft.server.world.ServerWorld world,
                              net.minecraft.entity.Entity holder,
                              net.minecraft.entity.EquipmentSlot slot) {
        if (stack.get(TrapComponents.balance) == null) {
            setBalance(stack, 0);
        }
    }

    // --- opening it -----------------------------------------------------------

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack held = user.getStackInHand(hand);
        if (world.isClient() || !(user instanceof ServerPlayerEntity owner)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, owner.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(),
                SoundCategory.PLAYERS, 0.7F, 1.4F);
        owner.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new WalletScreenHandler(syncId, inventory, held),
                Text.literal("Portfel").formatted(Formatting.GREEN, Formatting.BOLD)));
        return ActionResult.SUCCESS;
    }
}
