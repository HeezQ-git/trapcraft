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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Somebody else's kiosk, from the customer's side of the counter.
 *
 * A shelf is the shop WINDOW and the till is the back office, which is also
 * what the two of them look like. The owner clicking either gets
 * {@link ShopScreen}; anybody else gets this.
 *
 * Deliberately the same shape as {@link StallScreenHandler} rather than the
 * market's catalogue: a kiosk is a room you walked into with a few things on
 * the shelves, and it should read like one.
 *
 * <h2>Why a player pays exactly what a townsperson pays</h2>
 *
 * Because the alternative is two prices for one counter, and the interesting
 * thing about a kiosk is that it sells to both. It also means a shop stocking
 * joints is a licensed dispensary for players -- clean, declared, taxed, no
 * heat, and worth less than the street, which is the whole trade-off the legal
 * rate exists to offer.
 */
public class ShelfScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int FOOTER = SIZE - 9;
    private static final int SIGN_SLOT = FOOTER + 4;
    private static final int PURSE_SLOT = FOOTER + 8;
    /** The top five rows, which is more than any shop will fill. */
    private static final int SHELF = FOOTER;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity shopper;
    private final TrapShops.Shop shop;
    private final List<TrapShops.Line> shown = new ArrayList<>();

    public ShelfScreenHandler(int syncId, PlayerInventory playerInventory,
                              TrapShops.Shop shop) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.shopper = (ServerPlayerEntity) playerInventory.player;
        this.shop = shop;

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
        for (TrapShops.Line line : TrapShops.onSale(shopper.getServer(),
                (ServerWorld) shopper.getWorld(), shop)) {
            if (shown.size() >= SHELF) {
                break;
            }
            display.setStack(shown.size(), tag(line));
            shown.add(line);
        }
        if (shown.isEmpty()) {
            display.setStack(SHELF / 2, empty());
        }

        display.setStack(SIGN_SLOT, sign(shown.size()));
        display.setStack(PURSE_SLOT, purse());
        sendContentUpdates();
    }

    private ItemStack tag(TrapShops.Line line) {
        int duty = TrapCity.dutyOn(line.price(), line.duty());
        int total = line.price() + duty;
        boolean can = TrapMarket.wealthOf(shopper) >= total;

        ItemStack tag = line.sample().copy();
        tag.setCount(Math.max(1, line.count()));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(line.count() + "x " + line.label())
                        .formatted(can ? Formatting.WHITE : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line(total + "e", Formatting.GREEN)
                .append(plain(duty == 0 ? "" : "   inc " + duty + "e duty")
                        .formatted(Formatting.DARK_GRAY)));
        lore.add(Text.empty());
        lore.add(line(line.duty() == TrapCity.Duty.LUXURY
                        ? "Over a counter, so it's clean and declared."
                        : "Ordinary goods, at " + shop.markupName().toLowerCase(
                                java.util.Locale.ROOT) + " prices.",
                Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line(can ? "Click to buy one." : "You can't cover it.",
                can ? Formatting.YELLOW : Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack empty() {
        ItemStack tag = new ItemStack(Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Shelves are bare").formatted(Formatting.GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Nothing stocked, or nothing here anybody", Formatting.GRAY),
                line("would put a price on.", Formatting.GRAY),
                Text.empty(),
                line("Come back when they've restocked.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack sign(int lines) {
        ItemStack tag = new ItemStack(Items.OAK_SIGN);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(shop.name()).formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(shop.ownerName() + "'s, " + lines + " line"
                        + (lines == 1 ? "" : "s") + " on the shelves", Formatting.GRAY),
                Text.empty(),
                line("You pay what the town pays, duty and all.", Formatting.WHITE),
                line("Their prices are " + shop.markupName().toLowerCase(
                        java.util.Locale.ROOT) + ".", Formatting.DARK_GRAY),
                Text.empty(),
                line("/shops to find the others.", Formatting.DARK_GRAY))));
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
        String no = TrapShops.buy(shopper, shop, shown.get(index));
        if (no != null) {
            shopper.sendMessage(Text.literal(no).formatted(Formatting.GRAY), true);
            shopper.getWorld().playSound(null, shopper.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS,
                    0.7F, 0.6F);
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
