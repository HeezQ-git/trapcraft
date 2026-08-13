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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Behind the counter.
 *
 * A real inventory, unlike every other screen in this mod -- the bar is the
 * one place the casino asks you to put THINGS rather than press buttons, and
 * buttons cannot express "half a stack of Loud and some bread".
 *
 * Anything edible or anything you grew. Product is worth far more to the
 * regulars than food is, which is the whole reason the floor is worth pointing
 * a farm at.
 */
public class BarScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;
    /** The last column is the sign; the other eighteen are stock. */
    private static final int STOCK = TrapMath.BAR_SLOTS;

    private final SimpleInventory shelf = new SimpleInventory(SIZE);
    private final ServerPlayerEntity keeper;
    private final TrapHouse.House house;
    /** Which counter this is. Each one holds its own eighteen stacks. */
    private final String wire;
    /**
     * Set while the sign column is being repainted.
     *
     * paintSign() writes into the same inventory the listener is watching, so
     * without this the first item anybody put on the shelf went setStack ->
     * markDirty -> writeBack -> paintSign -> setStack until the stack ran out
     * and took the server down with it. It did, twice, on the day the bar
     * shipped.
     */
    private boolean painting;

    public BarScreenHandler(int syncId, PlayerInventory playerInventory,
                            TrapHouse.House house, String wire) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.keeper = (ServerPlayerEntity) playerInventory.player;
        this.house = house;
        this.wire = wire;
        TrapHouse.adoptOldStock(house, wire);

        for (int index = 0; index < STOCK; index++) {
            this.addSlot(new StockSlot(shelf, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18));
        }
        for (int index = STOCK; index < SIZE; index++) {
            this.addSlot(new SignSlot(shelf, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        List<ItemStack> standing = house.shelf(wire);
        for (int index = 0; index < STOCK && index < standing.size(); index++) {
            shelf.setStack(index, standing.get(index));
        }
        paintSign();
        // SimpleInventory never calls onContentChanged, so a listener is the
        // only way to notice somebody putting a stack on the shelf.
        shelf.addListener(inventory -> writeBack());
    }

    private void paintSign() {
        painting = true;
        try {
            for (int index = STOCK; index < SIZE; index++) {
                shelf.setStack(index, sign(index - STOCK));
            }
        } finally {
            painting = false;
        }
    }

    private ItemStack sign(int row) {
        int stock = TrapHouse.barStock(house);
        int here = 0;
        for (ItemStack standing : house.shelf(wire)) {
            here += standing.getCount();
        }
        if (row == 0) {
            ItemStack tag = new ItemStack(stock > 0 ? Items.BARREL : Items.GRAY_DYE);
            tag.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Bar").formatted(Formatting.GOLD, Formatting.BOLD));
            List<Text> lore = new ArrayList<>(List.of(
                    line("na tej ladzie: " + here, here > 0
                            ? Formatting.GREEN : Formatting.RED),
                    // The house figure, because a punter is served off
                    // whichever counter has the best thing on it: what keeps
                    // the room open is the total, not this shelf.
                    line("w całym kasynie: " + stock + ", czyli około "
                            + stock * TrapMath.SERVINGS_PER_ITEM + " kolejek",
                            stock > 0 ? Formatting.WHITE : Formatting.RED),
                    Text.empty(),
                    line("Każdy wchodzący dostaje jedną. Jeden", Formatting.GRAY),
                    line("przedmiot z półki to około "
                            + TrapMath.SERVINGS_PER_ITEM + " porcji.", Formatting.GRAY),
                    line("Kolejny podłączony bar to następne " + TrapMath.BAR_SLOTS
                            + " stacków.", Formatting.DARK_GRAY),
                    line("Obsłużeni gracze zostają. Pusty bar:", Formatting.GRAY),
                    line("jedna gra i wychodzą.", Formatting.GRAY),
                    Text.empty(),
                    // Rounded to a whole multiple this read "worth 1x what
                    // food is", which is both wrong and an argument for not
                    // bothering. 1.6 against 1.15 is a percentage, not a
                    // multiple; the multiple is in the habit it builds.
                    line("Twój towar trzyma ich "
                            + Math.round((TrapMath.SERVED_PRODUCT
                            / TrapMath.SERVED_FOOD - 1) * 100) + "% longer",
                            Formatting.WHITE),
                    line("dłużej niż jedzenie i buduje "
                            + TrapMath.BAR_ADDICTION_PRODUCT / TrapMath.BAR_ADDICTION_FOOD
                            + "x mocniejszy nałóg.", Formatting.WHITE),
                    Text.empty(),
                    // The part that pays the bills. A floor on the plate alone
                    // runs at about nothing; this is the difference.
                    line("I gorzej grają. "
                            + Math.round(TrapMath.SERVED_EDGE_PRODUCT * 100)
                            + " punktów mniej odzyskuje", Formatting.LIGHT_PURPLE),
                    line("gracz na towarze, a "
                            + Math.round(TrapMath.SERVED_EDGE_FOOD * 100)
                            + " ten na chlebie.", Formatting.LIGHT_PURPLE),
                    line("Na trzeźwo grają najlepiej.", Formatting.LIGHT_PURPLE),
                    Text.empty(),
                    line("Po to właśnie jest plantacja.", Formatting.WHITE)));
            tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
            return tag;
        }
        if (row == 1) {
            ItemStack tag = new ItemStack(Items.WHEAT);
            tag.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Co można wstawić").formatted(Formatting.WHITE, Formatting.BOLD));
            tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line("Cokolwiek jadalnego i cokolwiek z uprawy.", Formatting.GRAY),
                    Text.empty(),
                    line("Susz, skręty, mieszanki, proszek  ->  najlepsze", Formatting.LIGHT_PURPLE),
                    line("Chleb, gulasz, ciasto, jabłka  ->  ujdzie", Formatting.DARK_GRAY))));
            return tag;
        }
        ItemStack tag = new ItemStack(house.dryBar() ? Items.REDSTONE_TORCH : Items.TORCH);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(house.dryBar() ? "PUSTY" : "Otwarty").formatted(
                        house.dryBar() ? Formatting.RED : Formatting.GREEN, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(house.dryBar()
                                ? "Nic za ladą. Sala się"
                                : "Zaopatrzony. Zostaną na dłużej.",
                        house.dryBar() ? Formatting.RED : Formatting.GRAY),
                line(house.dryBar() ? "wyludnia, a z nią twoja reputacja."
                                : "Utrzymuj ten stan.",
                        house.dryBar() ? Formatting.RED : Formatting.DARK_GRAY))));
        return tag;
    }

    /**
     * Copy the shelf back onto the house.
     *
     * Every change, because a bar is only useful if the punters can see it the
     * moment you put something on it, and the alternative is a save on close
     * that loses everything to a disconnect.
     */
    private void writeBack() {
        if (painting) {
            return;   // our own brush, not somebody putting a stack down
        }
        List<ItemStack> standing = house.shelf(wire);
        standing.clear();
        for (int index = 0; index < STOCK; index++) {
            ItemStack stack = shelf.getStack(index);
            if (!stack.isEmpty()) {
                standing.add(stack);
            }
        }
        TrapHouse.touch();
        paintSign();
    }

    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();
        if (index < SIZE) {
            if (!insertItem(stack, SIZE, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!insertItem(stack, 0, STOCK, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            slot.markDirty();
        }
        writeBack();
        return copy;
    }

    @Override
    public void onClosed(PlayerEntity closer) {
        writeBack();
        super.onClosed(closer);
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == keeper;
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }

    /** Only things somebody would actually want handed to them. */
    private static class StockSlot extends Slot {
        StockSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return TrapContent.isContraband(stack)
                    || stack.get(DataComponentTypes.FOOD) != null;
        }
    }

    private static class SignSlot extends Slot {
        SignSlot(Inventory inventory, int index, int x, int y) {
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
