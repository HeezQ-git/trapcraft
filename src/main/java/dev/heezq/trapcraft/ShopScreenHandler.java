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

/**
 * The market stall's screen.
 *
 * A vanilla 9x6 container, like every other screen in this mod: the client
 * draws stacks the server sets and computes nothing, which is the only kind of
 * screen that behaves with Polymer items. See docs/TRAPS.md.
 *
 * Two pages -- the shelves, then one shelf's goods -- because six categories
 * of sixty items in one grid is a wall, and a wall is where prices go to be
 * ignored.
 */
public class ShopScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    /** Bottom row is furniture: back, balance, the market's mood. */
    private static final int FOOTER = SIZE - 9;

    private static final int BACK_SLOT = FOOTER;
    private static final int MOOD_SLOT = FOOTER + 4;
    private static final int PURSE_SLOT = FOOTER + 8;
    private static final int PREV_SLOT = FOOTER + 2;
    private static final int NEXT_SLOT = FOOTER + 6;

    /** Two rows of four, centred, so the shelves read as a shopfront. */
    private static final int[] SHELF_SPOTS = {10, 12, 14, 16, 28, 30, 32, 34};

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity shopper;
    private final List<ShopStock.Entry> shown = new ArrayList<>();
    private ShopStock.Category open;
    /** Some shelves carry more than one screenful. */
    private int page;

    public ShopScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.shopper = (ServerPlayerEntity) playerInventory.player;

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

        showShelves();
    }

    // --- pages ----------------------------------------------------------------

    private void showShelves() {
        open = null;
        shown.clear();
        blank();

        // Two rows of three, centred, so the shelves read as a shopfront rather
        // than a list.
        int[] spots = SHELF_SPOTS;
        for (int i = 0; i < ShopStock.CATEGORIES.size() && i < spots.length; i++) {
            ShopStock.Category category = ShopStock.CATEGORIES.get(i);
            ItemStack icon = new ItemStack(category.icon());
            icon.set(DataComponentTypes.CUSTOM_NAME,
                    plain(category.title()).formatted(category.colour(), Formatting.BOLD));
            icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line(category.blurb(), Formatting.GRAY),
                    Text.empty(),
                    line(ShopStock.of(category).size() + " lines", Formatting.DARK_GRAY))));
            display.setStack(spots[i], icon);
        }
        footer();
        sendContentUpdates();
    }

    private void showShelf(ShopStock.Category category, int wanted) {
        open = category;
        shown.clear();
        shown.addAll(ShopStock.of(category));
        page = Math.max(0, Math.min(wanted, lastPage()));
        blank();

        int from = page * FOOTER;
        for (int i = 0; i + from < shown.size() && i < FOOTER; i++) {
            display.setStack(i, priceTag(shown.get(i + from)));
        }
        footer();
        sendContentUpdates();
    }

    /**
     * One item, priced, with what it does spelled out.
     *
     * The stack shows the bundle size, so what you see in the slot is exactly
     * what lands in your inventory.
     */
    private ItemStack priceTag(ShopStock.Entry entry) {
        int buy = TrapMarket.buyPrice(shopper.getServer(), entry);
        int sell = TrapMarket.sellPrice(shopper.getServer(), entry);
        int move = TrapMarket.movement(shopper.getServer(), entry);
        int held = TrapMarket.bundlesHeld(shopper, entry);

        ItemStack tag = new ItemStack(entry.item(), entry.count());
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(entry.item().getName().getString())
                        .formatted(Formatting.WHITE)
                        .append(plain("  x" + entry.count()).formatted(Formatting.DARK_GRAY)));

        List<Text> lore = new ArrayList<>();
        lore.add(line("Buy    ", Formatting.DARK_GRAY)
                .append(plain(buy + "e").formatted(Formatting.GREEN))
                .append(plain(move == 0 ? "" : move > 0 ? "   +" + move + "%" : "   " + move + "%")
                        .formatted(move > 0 ? Formatting.RED : Formatting.AQUA)));
        lore.add(line("Sell   ", Formatting.DARK_GRAY)
                .append(sell > 0
                        ? plain(sell + "e").formatted(Formatting.GOLD)
                        : plain("not bought here").formatted(Formatting.DARK_GRAY)));
        lore.add(Text.empty());
        lore.add(line("Click", Formatting.YELLOW).append(plain(" to buy one lot")
                .formatted(Formatting.GRAY)));
        lore.add(line("Shift-click", Formatting.YELLOW).append(plain(" to buy four")
                .formatted(Formatting.GRAY)));
        if (sell > 0) {
            lore.add(line("Right-click", Formatting.YELLOW)
                    .append(plain(held > 0 ? " to sell one lot" : " to sell (you have none)")
                            .formatted(Formatting.GRAY)));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** Zero-based index of the final page of the open shelf. */
    private int lastPage() {
        return shown.isEmpty() ? 0 : (shown.size() - 1) / FOOTER;
    }

    private void footer() {
        if (open != null) {
            if (page > 0) {
                display.setStack(PREV_SLOT, arrow("Previous page"));
            }
            if (page < lastPage()) {
                display.setStack(NEXT_SLOT, arrow("Next page  ("
                        + (page + 2) + "/" + (lastPage() + 1) + ")"));
            }
        }
        if (open != null) {
            ItemStack back = new ItemStack(Items.ARROW);
            back.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Back to the shelves").formatted(Formatting.WHITE));
            display.setStack(BACK_SLOT, back);
        }

        float index = TrapMarket.index();
        ItemStack mood = new ItemStack(index > 1.15f ? Items.REDSTONE
                : index < 0.9f ? Items.LAPIS_LAZULI : Items.PAPER);
        mood.set(DataComponentTypes.CUSTOM_NAME,
                plain("The Market").formatted(Formatting.GOLD, Formatting.BOLD));
        mood.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(index > 1.15f ? "Prices are up. Too much money about."
                        : index < 0.9f ? "Prices are soft. Money's tight."
                        : "Prices are steady.", Formatting.GRAY),
                Text.empty(),
                line("Everything shifts overnight.", Formatting.DARK_GRAY))));
        display.setStack(MOOD_SLOT, mood);

        int purse = TrapMarket.wealthOf(shopper);
        ItemStack wallet = new ItemStack(Items.EMERALD);
        wallet.set(DataComponentTypes.CUSTOM_NAME,
                plain("Your purse: ").formatted(Formatting.GRAY)
                        .append(plain(purse + "e").formatted(Formatting.GREEN, Formatting.BOLD)));
        wallet.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Emeralds and emerald blocks both count.", Formatting.DARK_GRAY))));
        display.setStack(PURSE_SLOT, wallet);
    }

    private ItemStack arrow(String label) {
        ItemStack stack = new ItemStack(Items.SPECTRAL_ARROW);
        stack.set(DataComponentTypes.CUSTOM_NAME, plain(label).formatted(Formatting.WHITE));
        return stack;
    }

    private void blank() {
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.CUSTOM_NAME, Text.empty());
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, index >= FOOTER ? pane.copy() : ItemStack.EMPTY);
        }
    }

    // --- trading --------------------------------------------------------------

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex < 0 || slotIndex >= SIZE) {
            return;
        }
        boolean shift = actionType == SlotActionType.QUICK_MOVE;
        boolean right = button == 1;

        if (open == null) {
            int[] spots = SHELF_SPOTS;
            for (int i = 0; i < spots.length && i < ShopStock.CATEGORIES.size(); i++) {
                if (spots[i] == slotIndex) {
                    click(0.9F);
                    showShelf(ShopStock.CATEGORIES.get(i), 0);
                    return;
                }
            }
            return;
        }
        if (slotIndex == BACK_SLOT) {
            click(0.7F);
            showShelves();
            return;
        }
        if (slotIndex == PREV_SLOT && page > 0) {
            click(0.8F);
            showShelf(open, page - 1);
            return;
        }
        if (slotIndex == NEXT_SLOT && page < lastPage()) {
            click(1.0F);
            showShelf(open, page + 1);
            return;
        }
        int index = page * FOOTER + slotIndex;
        if (slotIndex >= FOOTER || index >= shown.size()) {
            return;
        }

        ShopStock.Entry entry = shown.get(index);
        if (right) {
            sell(entry);
        } else {
            buy(entry, shift ? 4 : 1);
        }
        showShelf(open, page);
    }

    private void buy(ShopStock.Entry entry, int lots) {
        int each = TrapMarket.buyPrice(shopper.getServer(), entry);
        int purse = TrapMarket.wealthOf(shopper);

        // Buy as many as they can afford rather than refusing outright: asking
        // for four and getting three is a better shop than getting nothing.
        int affordable = Math.min(lots, purse / each);
        if (affordable <= 0) {
            deny();
            shopper.sendMessage(plain("You're ").formatted(Formatting.GRAY)
                    .append(plain((each - purse) + "e").formatted(Formatting.RED))
                    .append(plain(" short of a " + name(entry) + ".").formatted(Formatting.GRAY)), false);
            return;
        }

        int cost = affordable * each;
        TrapMarket.take(shopper, cost);
        for (int i = 0; i < affordable; i++) {
            shopper.getInventory().offerOrDrop(new ItemStack(entry.item(), entry.count()));
        }

        till();
        shopper.sendMessage(plain("Bought ").formatted(Formatting.GRAY)
                .append(plain((affordable * entry.count()) + "x ").formatted(Formatting.WHITE))
                .append(plain(name(entry)).formatted(Formatting.WHITE))
                .append(plain(" for ").formatted(Formatting.GRAY))
                .append(plain(cost + "e").formatted(Formatting.GREEN))
                .append(plain(affordable < lots ? "   that's all you could afford" : "")
                        .formatted(Formatting.DARK_GRAY)), false);
    }

    private void sell(ShopStock.Entry entry) {
        int each = TrapMarket.sellPrice(shopper.getServer(), entry);
        if (each <= 0) {
            deny();
            shopper.sendMessage(plain("Nobody's buying " + name(entry) + " here.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.bundlesHeld(shopper, entry) < 1) {
            deny();
            shopper.sendMessage(plain("You'd need ").formatted(Formatting.GRAY)
                    .append(plain(entry.count() + "x " + name(entry)).formatted(Formatting.WHITE))
                    .append(plain(" to sell a lot.").formatted(Formatting.GRAY)), false);
            return;
        }

        TrapMarket.takeGoods(shopper, entry, 1);
        TrapMarket.pay(shopper, each);

        till();
        shopper.sendMessage(plain("Sold ").formatted(Formatting.GRAY)
                .append(plain(entry.count() + "x ").formatted(Formatting.WHITE))
                .append(plain(name(entry)).formatted(Formatting.WHITE))
                .append(plain(" for ").formatted(Formatting.GRAY))
                .append(plain(each + "e").formatted(Formatting.GOLD)), false);
    }

    // --- trimmings ------------------------------------------------------------

    private void till() {
        shopper.getWorld().playSound(null, shopper.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.6F, 1.6F);
        shopper.getWorld().playSound(null, shopper.getBlockPos(),
                SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 0.4F, 1.2F);
    }

    private void click(float pitch) {
        shopper.getWorld().playSound(null, shopper.getBlockPos(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5F, pitch);
    }

    private void deny() {
        shopper.getWorld().playSound(null, shopper.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
    }

    private static String name(ShopStock.Entry entry) {
        return entry.item().getName().getString();
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting colour) {
        return plain(text).formatted(colour);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == shopper;
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
