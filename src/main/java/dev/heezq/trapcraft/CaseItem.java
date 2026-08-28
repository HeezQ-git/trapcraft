package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
 * A box you can't open, and the key that changes that.
 *
 * Right-click the case: if the matching key is anywhere in your inventory,
 * both are consumed and the reel starts. If it isn't, you are told which key
 * it wants, because a box that just does nothing when clicked is a bug report.
 *
 * <h2>Why both are eaten before the reel spins</h2>
 *
 * The prize is decided in the screen handler's constructor and paid on close,
 * so a player who closes the window mid-spin still gets it (see
 * {@link CaseScreenHandler}). If the case and key came off the stack at the
 * END instead, closing early would be a free open -- and closing early is
 * exactly what somebody does once they have seen the animation twice.
 */
public class CaseItem extends Item implements PolymerItem {

    private final CaseOdds.Tier tier;
    private final Identifier model;

    public CaseItem(CaseOdds.Tier tier, Settings settings, Identifier model) {
        super(settings);
        this.tier = tier;
        this.model = model;
    }

    public CaseOdds.Tier tier() {
        return tier;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        // A shulker box would be the obvious base and is the wrong one: it is
        // placeable, so the client predicts placing a real shulker where you
        // clicked and the block flashes before the server says no. Same trap
        // as TrapContent.BASE, and the same fix -- ride on something inert.
        return Items.PAPER;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    /**
     * Say what it is for, once.
     *
     * Stamped rather than appended at tooltip time because these go to a
     * vanilla client: whatever is not on the stack when it leaves the server
     * does not exist. Same arrangement as the wallet's balance, and the same
     * one-component check to keep it off the hot path.
     */
    @Override
    public void inventoryTick(ItemStack stack, ServerWorld world, Entity holder,
                              EquipmentSlot slot) {
        if (stack.get(DataComponentTypes.LORE) == null) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore()));
        }
    }

    List<Text> lore() {
        return List.of(
                TrapCases.plain("Potrzebny: ").formatted(Formatting.GRAY)
                        .append(TrapCases.name(tier.keyKey()).formatted(Formatting.YELLOW)),
                TrapCases.plain("PPM, żeby otworzyć").formatted(Formatting.DARK_GRAY));
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (world.isClient() || !(user instanceof ServerPlayerEntity owner)) {
            return ActionResult.SUCCESS;
        }
        ItemStack held = owner.getStackInHand(hand);
        int keySlot = findKey(owner);
        if (keySlot < 0) {
            owner.playSoundToPlayer(SoundEvents.BLOCK_CHEST_LOCKED, SoundCategory.PLAYERS,
                    0.8F, 1.0F);
            owner.sendMessage(TrapCases.plain("Zamknięta. Potrzebujesz: ")
                    .formatted(Formatting.GRAY)
                    .append(TrapCases.name(tier.keyKey())
                            .formatted(Formatting.GOLD, Formatting.BOLD)), false);
            return ActionResult.SUCCESS;
        }

        owner.getInventory().getStack(keySlot).decrement(1);
        held.decrement(1);
        owner.getWorld().playSound(null, owner.getBlockPos(), SoundEvents.BLOCK_CHEST_OPEN,
                SoundCategory.PLAYERS, 0.8F, 1.2F);
        owner.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new CaseScreenHandler(syncId, inventory, tier),
                Text.translatable(tier.caseKey()).formatted(Formatting.GOLD, Formatting.BOLD)));
        return ActionResult.SUCCESS;
    }

    /** Where the matching key is, or -1. */
    private int findKey(ServerPlayerEntity owner) {
        Item wanted = TrapContent.KEYS.get(tier);
        var inventory = owner.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(wanted)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * The other half. Does nothing but say what it opens.
     *
     * Its own class only so the lore differs -- everything else about a key is
     * a plain item, and giving it behaviour it doesn't need would be inventing
     * a second way for a key to be wrong.
     */
    public static class Key extends CaseItem {

        public Key(CaseOdds.Tier tier, Settings settings, Identifier model) {
            super(tier, settings, model);
        }

        @Override
        List<Text> lore() {
            return List.of(
                    TrapCases.plain("Otwiera: ").formatted(Formatting.GRAY)
                            .append(TrapCases.name(tier().caseKey())
                                    .formatted(Formatting.YELLOW)),
                    TrapCases.plain("Zużywa się przy otwarciu")
                            .formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (!world.isClient() && user instanceof ServerPlayerEntity owner) {
                owner.sendMessage(TrapCases.plain("Klucz otwiera ")
                        .formatted(Formatting.GRAY)
                        .append(TrapCases.name(tier().caseKey())
                                .formatted(Formatting.GOLD))
                        .append(TrapCases.plain(". Kliknij skrzynkę, nie klucz.")
                                .formatted(Formatting.GRAY)), true);
            }
            return ActionResult.SUCCESS;
        }
    }
}
