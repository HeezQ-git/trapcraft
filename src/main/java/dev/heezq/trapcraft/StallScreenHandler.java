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
import java.util.List;
import java.util.Map;

/**
 * Somebody else's shop.
 *
 * Deliberately not the market screen with a different header. The market is a
 * catalogue you page through by category; a stall is a handful of things one
 * person happens to have spare, and it should read like a table with stuff on
 * it rather than a department store.
 *
 * Every price on it is derived from the market's, so there is nothing to
 * haggle over and nothing to keep up to date -- see {@link TrapMath#STALL_RATE}.
 */
public class StallScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int FOOTER = SIZE - 9;
    private static final int SIGN_SLOT = FOOTER + 4;
    private static final int PURSE_SLOT = FOOTER + 8;
    /** The top five rows, which is more than anybody will ever fill. */
    private static final int SHELF = FOOTER;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity shopper;
    private final TrapStalls.Stall stall;
    private final List<ShopStock.Entry> shown = new ArrayList<>();

    public StallScreenHandler(int syncId, PlayerInventory playerInventory,
                              TrapStalls.Stall stall) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.shopper = (ServerPlayerEntity) playerInventory.player;
        this.stall = stall;

        for (int index = 0; index < SIZE; index++) {
            this.addSlot(new ReadOnlySlot(display, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18));
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
        paint();
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        shown.clear();
        Map<ShopStock.Entry, Integer> listing =
                TrapStalls.listing(shopper.getWorld(), stall);
        for (Map.Entry<ShopStock.Entry, Integer> row : listing.entrySet()) {
            if (shown.size() >= SHELF) {
                break;
            }
            display.setStack(shown.size(), tag(row.getKey(), row.getValue()));
            shown.add(row.getKey());
        }
        if (shown.isEmpty()) {
            display.setStack(SHELF / 2, empty());
        }

        display.setStack(SIGN_SLOT, sign(listing.size()));
        display.setStack(PURSE_SLOT, purse());
        sendContentUpdates();
    }

    private ItemStack tag(ShopStock.Entry entry, int held) {
        int market = TrapMarket.buyPrice(shopper.getServer(), entry);
        int price = TrapMath.stallPrice(market);
        boolean can = TrapMarket.wealthOf(shopper) >= price;

        ItemStack tag = entry.stack();
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(entry.count() + "x " + entry.label())
                        .formatted(can ? Formatting.WHITE : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line(price + "e", Formatting.GREEN)
                .append(plain("   market " + market + "e").formatted(Formatting.DARK_GRAY)));
        lore.add(line("You save " + (market - price) + "e a lot.", Formatting.AQUA));
        lore.add(Text.empty());
        lore.add(line(held / entry.count() + " lots on the table", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line(can ? "Click to buy one." : "You can't cover it.",
                can ? Formatting.YELLOW : Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack empty() {
        ItemStack tag = new ItemStack(Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Nothing on the table").formatted(Formatting.GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("A stall sells whatever is in the chest", Formatting.GRAY),
                line("directly underneath it.", Formatting.GRAY),
                Text.empty(),
                line("Only things the market has a price for.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack sign(int lines) {
        ItemStack tag = new ItemStack(Items.OAK_SIGN);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(stall.ownerName() + "'s stall").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(lines + " line" + (lines == 1 ? "" : "s") + " for sale",
                        Formatting.GRAY),
                Text.empty(),
                line("Everything here is " + Math.round((1 - TrapMath.STALL_RATE) * 100)
                        + "% under the market.", Formatting.WHITE),
                line("They keep most of it; the rest is the", Formatting.DARK_GRAY),
                line("pitch fee. You both do better than", Formatting.DARK_GRAY),
                line("either of you would at the counter.", Formatting.DARK_GRAY),
                Text.empty(),
                line("/stalls to find the others.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack purse() {
        ItemStack tag = new ItemStack(Items.EMERALD);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(TrapMarket.wealthOf(shopper) + "e on you")
                        .formatted(Formatting.GREEN, Formatting.BOLD));
        return tag;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index >= shown.size()) {
            return;
        }
        String no = TrapStalls.buy(shopper, stall, shown.get(index));
        if (no != null) {
            shopper.sendMessage(Text.literal(no).formatted(Formatting.GRAY), true);
            shopper.getWorld().playSound(null, shopper.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
        }
        paint();
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        if (index < SIZE) {
            onSlotClick(index, 0, SlotActionType.PICKUP, player);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == shopper;
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
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
        public boolean canTakeItems(PlayerEntity player) {
            return false;
        }
    }
}
