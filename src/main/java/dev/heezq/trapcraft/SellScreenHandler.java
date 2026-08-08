package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The counter at the back of the stall, where you sell the rest of it.
 *
 * The shelves deal in lots of listed goods at a market price. This takes
 * anything: a chest of odds and ends goes in, one click turns the lot into
 * emeralds, and whatever it won't buy comes straight back with a reason.
 *
 * Forty-five real slots, because "in batch" is the whole point -- shift-click
 * a stack in, fill it up, sell once.
 */
public class SellScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    /** Everything above the footer takes items. */
    private static final int TILL = SIZE - 9;

    private static final int INFO_SLOT = TILL;
    private static final int TOTAL_SLOT = TILL + 3;
    private static final int SELL_SLOT = TILL + 5;
    private static final int PURSE_SLOT = TILL + 8;

    private final SimpleInventory counter = new SimpleInventory(TILL);
    private final SimpleInventory footer = new SimpleInventory(9);
    private final ServerPlayerEntity seller;

    public SellScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.seller = (ServerPlayerEntity) playerInventory.player;

        for (int index = 0; index < TILL; index++) {
            this.addSlot(new Slot(counter, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18));
        }
        for (int index = 0; index < 9; index++) {
            this.addSlot(new ReadOnlySlot(footer, index, 8 + index * 18, 18 + 5 * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 103 + row * 18 + (ROWS - 4) * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 161 + (ROWS - 4) * 18));
        }

        // SimpleInventory notifies listeners, not the handler -- see the note
        // in MixerScreenHandler. Without this the total never moves.
        counter.addListener(sender -> paint());
        paint();
    }

    // --- the counter ----------------------------------------------------------

    /** What's on the counter right now, and what it's worth. */
    private int quote() {
        int total = 0;
        for (int slot = 0; slot < counter.size(); slot++) {
            ItemStack stack = counter.getStack(slot);
            total += TrapScrap.priceOf(seller.getServer(), stack);
        }
        return total;
    }

    private int refused() {
        int count = 0;
        for (int slot = 0; slot < counter.size(); slot++) {
            ItemStack stack = counter.getStack(slot);
            if (!stack.isEmpty() && TrapScrap.priceOf(seller.getServer(), stack) <= 0) {
                count++;
            }
        }
        return count;
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < footer.size(); index++) {
            footer.setStack(index, filler.copy());
        }

        int worth = quote();
        int wontBuy = refused();

        ItemStack info = new ItemStack(Items.BOOK);
        info.set(DataComponentTypes.CUSTOM_NAME,
                plain("The Counter").formatted(Formatting.GOLD, Formatting.BOLD));
        info.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Put anything in. Shift-click works.", Formatting.GRAY),
                line("Then hit SELL.", Formatting.GRAY),
                Text.empty(),
                line("Listed goods fetch the market price.", Formatting.DARK_GRAY),
                line("Everything else is valued on the spot,", Formatting.DARK_GRAY),
                line("at " + Math.round(TrapMath.SCRAP_RATE * 100) + "% -- it's a counter, not a shop.",
                        Formatting.DARK_GRAY),
                Text.empty(),
                line("What it won't take comes back to you.", Formatting.WHITE))));
        footer.setStack(INFO_SLOT - TILL, info);

        ItemStack total = new ItemStack(worth > 0 ? Items.EMERALD : Items.GRAY_DYE);
        total.setCount(Math.max(1, Math.min(64, worth)));
        total.set(DataComponentTypes.CUSTOM_NAME,
                plain("On the counter: ").formatted(Formatting.GRAY)
                        .append(plain(worth + "e").formatted(Formatting.GREEN, Formatting.BOLD)));
        List<Text> breakdown = new ArrayList<>();
        for (Map.Entry<String, int[]> row : tally().entrySet()) {
            if (breakdown.size() >= 8) {
                breakdown.add(line("  ...and more", Formatting.DARK_GRAY));
                break;
            }
            breakdown.add(line("  " + row.getValue()[0] + "x " + row.getKey() + "   ",
                    Formatting.DARK_GRAY)
                    .append(plain(row.getValue()[1] + "e").formatted(Formatting.GREEN)));
        }
        if (breakdown.isEmpty()) {
            breakdown.add(line("Nothing on it yet.", Formatting.DARK_GRAY));
        }
        if (wontBuy > 0) {
            breakdown.add(Text.empty());
            breakdown.add(line(wontBuy + " thing(s) it won't take.", Formatting.RED));
        }
        total.set(DataComponentTypes.LORE, new LoreComponent(breakdown));
        footer.setStack(TOTAL_SLOT - TILL, total);

        ItemStack sell = new ItemStack(worth > 0 ? Items.HOPPER : Items.GRAY_DYE);
        sell.set(DataComponentTypes.CUSTOM_NAME,
                plain(worth > 0 ? "SELL THE LOT" : "Nothing to sell")
                        .formatted(worth > 0 ? Formatting.GOLD : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        sell.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(worth > 0 ? "Click for " + worth + "e." : "Put something on the counter.",
                        worth > 0 ? Formatting.GRAY : Formatting.DARK_GRAY))));
        footer.setStack(SELL_SLOT - TILL, sell);

        ItemStack purse = new ItemStack(Items.GOLD_NUGGET);
        purse.set(DataComponentTypes.CUSTOM_NAME,
                plain("Purse: ").formatted(Formatting.GRAY)
                        .append(plain(TrapMarket.wealthOf(seller) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        footer.setStack(PURSE_SLOT - TILL, purse);

        sendContentUpdates();
    }

    /** Item name to {count, worth}, merged across slots so the list reads short. */
    private Map<String, int[]> tally() {
        Map<String, int[]> rows = new LinkedHashMap<>();
        for (int slot = 0; slot < counter.size(); slot++) {
            ItemStack stack = counter.getStack(slot);
            int worth = TrapScrap.priceOf(seller.getServer(), stack);
            if (stack.isEmpty() || worth <= 0) {
                continue;
            }
            int[] row = rows.computeIfAbsent(stack.getName().getString(), key -> new int[2]);
            row[0] += stack.getCount();
            row[1] += worth;
        }
        return rows;
    }

    // --- selling --------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index == SELL_SLOT) {
            sell();
            return;
        }
        super.onSlotClick(index, button, type, clicker);
    }

    private void sell() {
        int paid = 0;
        int sold = 0;
        Map<String, int[]> receipt = tally();
        List<String> handedBack = new ArrayList<>();

        for (int slot = 0; slot < counter.size(); slot++) {
            ItemStack stack = counter.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int worth = TrapScrap.priceOf(seller.getServer(), stack);
            if (worth <= 0) {
                String why = TrapScrap.refusal(stack);
                handedBack.add(stack.getName().getString()
                        + (why == null ? " -- nobody's buying that" : " -- " + why));
                seller.getInventory().offerOrDrop(stack.copy());
                counter.setStack(slot, ItemStack.EMPTY);
                continue;
            }
            paid += worth;
            sold += stack.getCount();
            // Order flow: dumping a hundred of something on the counter should
            // move its price the same way dumping it on the shelf does.
            ShopStock.Entry listed = ShopStock.matching(stack);
            if (listed != null) {
                TrapMarket.traded(listed, Math.max(1, stack.getCount() / listed.count()), false);
            }
            counter.setStack(slot, ItemStack.EMPTY);
        }

        if (paid <= 0 && handedBack.isEmpty()) {
            deny();
            paint();
            return;
        }
        if (paid > 0) {
            TrapMarket.pay(seller, paid);
            if (paid >= 500) {
                TrapAwards.grant(seller, "liquidation");
            }
            till();
            MutableText line = plain("Sold ").formatted(Formatting.GRAY)
                    .append(plain(sold + " item" + (sold == 1 ? "" : "s"))
                            .formatted(Formatting.WHITE))
                    .append(plain(" for ").formatted(Formatting.GRAY))
                    .append(plain(paid + "e").formatted(Formatting.GREEN, Formatting.BOLD));
            int named = 0;
            for (Map.Entry<String, int[]> row : receipt.entrySet()) {
                if (named++ >= 6) {
                    line.append(plain("\n  ...and " + (receipt.size() - 6) + " more")
                            .formatted(Formatting.DARK_GRAY));
                    break;
                }
                line.append(plain("\n  " + row.getValue()[0] + "x " + row.getKey() + "   ")
                                .formatted(Formatting.DARK_GRAY))
                        .append(plain(row.getValue()[1] + "e").formatted(Formatting.GREEN));
            }
            seller.sendMessage(line, false);
        }

        if (!handedBack.isEmpty()) {
            deny();
            MutableText line = plain("Handed back:").formatted(Formatting.GRAY);
            for (String refused : handedBack) {
                line.append(plain("\n  " + refused).formatted(Formatting.RED));
            }
            seller.sendMessage(line, false);
        }
        paint();
    }

    /**
     * Anything left on the counter goes back in your bag.
     *
     * Closing a menu must never cost you a chestful of goods, and a player who
     * loses one stack that way never uses the counter again.
     */
    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        for (int slot = 0; slot < counter.size(); slot++) {
            ItemStack stack = counter.getStack(slot);
            if (!stack.isEmpty()) {
                closer.getInventory().offerOrDrop(stack.copy());
                counter.setStack(slot, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Shift-clicking from your bag fills the counter, not the other way into
     * the footer.
     */
    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
        Slot from = this.slots.get(index);
        if (!from.hasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = from.getStack();
        ItemStack before = stack.copy();

        if (index < SIZE) {
            // Off the counter and back into the bag. The footer gives nothing.
            if (index >= TILL || !this.insertItem(stack, SIZE, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.insertItem(stack, 0, TILL, false)) {
            return ItemStack.EMPTY;
        }
        from.markDirty();
        paint();
        return before;
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == seller;
    }

    // --- trimmings ------------------------------------------------------------

    private void till() {
        seller.getWorld().playSound(null, seller.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.6F, 1.6F);
        seller.getWorld().playSound(null, seller.getBlockPos(),
                SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 0.4F, 1.2F);
    }

    private void deny() {
        seller.getWorld().playSound(null, seller.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting colour) {
        return plain(text).formatted(colour);
    }

    private static class ReadOnlySlot extends Slot {
        ReadOnlySlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return false;
        }

        @Override
        public boolean canTakeItems(PlayerEntity taker) {
            return false;
        }
    }
}
