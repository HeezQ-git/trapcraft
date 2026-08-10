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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The back office of one shop.
 *
 *   [register] . [prices] . [shelves] . . . [stock..]
 *   [what's on the shelves, priced as the town sees it]
 *
 * The takings come out on open, like the mailbox's rent, because a screen
 * that makes you find a second button to collect your own money is a screen
 * doing paperwork at somebody.
 */
public class ShopScreen extends ScreenHandler {
    private static final int SIZE = 27;
    private static final int TILL_SLOT = 0;
    private static final int PRICE_SLOT = 2;
    private static final int SHELVES_SLOT = 4;
    private static final int LINES_FROM = 9;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;
    private final TrapShops.Shop shop;

    public ShopScreen(int syncId, PlayerInventory playerInventory, TrapShops.Shop shop) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.who = (ServerPlayerEntity) playerInventory.player;
        this.shop = shop;

        for (int index = 0; index < SIZE; index++) {
            this.addSlot(new ReadOnlySlot(display, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 84 + row * 18));
            }
        }
        int takings = TrapShops.collect(who, shop);
        if (takings > 0) {
            who.sendMessage(Text.literal("Till: ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("+" + takings + "e").formatted(Formatting.GREEN)),
                    false);
        }
        paint();
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }
        display.setStack(TILL_SLOT, till());
        display.setStack(PRICE_SLOT, prices());
        display.setStack(SHELVES_SLOT, shelves());

        List<TrapShops.Line> lines = onSale();
        for (int i = 0; i < lines.size() && LINES_FROM + i < SIZE; i++) {
            display.setStack(LINES_FROM + i, priced(lines.get(i)));
        }
        if (lines.isEmpty()) {
            display.setStack(LINES_FROM, empty());
        }
        sendContentUpdates();
    }

    /** Everything the shop could serve right now, one entry a line. */
    private List<TrapShops.Line> onSale() {
        Map<String, TrapShops.Line> lines = new LinkedHashMap<>();
        ServerWorld world = (ServerWorld) who.getWorld();
        for (Inventory box : TrapShops.stockOf(world, shop)) {
            for (int slot = 0; slot < box.size(); slot++) {
                TrapShops.Line line = TrapShops.lineFor(who.getServer(),
                        box.getStack(slot), shop);
                if (line != null) {
                    lines.putIfAbsent(line.label(), line);
                }
            }
        }
        return new ArrayList<>(lines.values());
    }

    private ItemStack till() {
        ItemStack tag = new ItemStack(Items.EMERALD_BLOCK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(shop.name()).formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(shop.sold() + " sold, " + shop.turnover() + "e through the books",
                        Formatting.GRAY),
                Text.empty(),
                line("The register empties itself when you", Formatting.DARK_GRAY),
                line("open this. It's your money.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Turnover is what the revenue office", Formatting.DARK_GRAY),
                line("will believe about your other money.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack prices() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Prices").formatted(Formatting.AQUA, Formatting.BOLD)
                        .append(plain("   " + shop.markupName()).formatted(Formatting.WHITE)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(shop.markup() + "% of what the town expects to pay.", Formatting.GRAY),
                Text.empty(),
                line("Cheap brings more of them through the", Formatting.DARK_GRAY),
                line("door. Dear takes more off each one.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Click to change it.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack shelves() {
        int count = TrapShops.shelvesOf(shop).size();
        ItemStack tag = new ItemStack(count > 0 ? TrapContent.marketShelfItem : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Shelves").formatted(count > 0 ? Formatting.WHITE : Formatting.RED,
                        Formatting.BOLD)
                        .append(plain("   " + count).formatted(Formatting.GRAY)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(count > 0 ? "Every one within " + TrapShops.REACH + " blocks of this till."
                        : "None within " + TrapShops.REACH + " blocks. Nobody can be served.",
                        count > 0 ? Formatting.GRAY : Formatting.RED),
                Text.empty(),
                line("They join on their own -- a shelf belongs", Formatting.DARK_GRAY),
                line("to whichever till is nearest.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Stock goes in any chest or barrel under", Formatting.DARK_GRAY),
                line("this till or under any of them.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack priced(TrapShops.Line entry) {
        ItemStack tag = entry.sample().copy();
        int duty = TrapCity.dutyOn(entry.price(), entry.duty());
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(entry.count() + "x " + entry.label()).formatted(Formatting.WHITE));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("They pay  ", Formatting.DARK_GRAY)
                        .append(plain((entry.price() + duty) + "e").formatted(Formatting.GREEN)),
                line("You keep  ", Formatting.DARK_GRAY)
                        .append(plain(entry.price() + "e").formatted(Formatting.WHITE))
                        .append(plain(duty > 0 ? "   " + duty + "e duty" : "")
                                .formatted(Formatting.DARK_GRAY)),
                Text.empty(),
                line(entry.duty() == TrapCity.Duty.LUXURY
                                ? "Over a counter, so it's clean and declared."
                                : "Ordinary goods.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack empty() {
        ItemStack tag = new ItemStack(Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Nothing on the shelves").formatted(Formatting.RED, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Put a chest or barrel under this till,", Formatting.GRAY),
                line("or under any shelf, and fill it.", Formatting.GRAY),
                Text.empty(),
                line("Food, blocks, tools -- and joints, buds", Formatting.DARK_GRAY),
                line("and powder, sold clean and taxed.", Formatting.DARK_GRAY))));
        return tag;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == PRICE_SLOT) {
            TrapShops.repricePrices(shop);
            who.getWorld().playSound(null, who.getBlockPos(),
                    SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.7F, 1.3F);
            paint();
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        if (index < SIZE) {
            onSlotClick(index, 0, SlotActionType.QUICK_MOVE, player);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == who;
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
