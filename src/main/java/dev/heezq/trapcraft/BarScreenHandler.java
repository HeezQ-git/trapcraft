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

    public BarScreenHandler(int syncId, PlayerInventory playerInventory,
                            TrapHouse.House house) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.keeper = (ServerPlayerEntity) playerInventory.player;
        this.house = house;

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

        for (int index = 0; index < STOCK && index < house.bar.size(); index++) {
            shelf.setStack(index, house.bar.get(index));
        }
        paintSign();
        // SimpleInventory never calls onContentChanged, so a listener is the
        // only way to notice somebody putting a stack on the shelf.
        shelf.addListener(inventory -> writeBack());
    }

    private void paintSign() {
        for (int index = STOCK; index < SIZE; index++) {
            shelf.setStack(index, sign(index - STOCK));
        }
    }

    private ItemStack sign(int row) {
        int stock = TrapHouse.barStock(house);
        if (row == 0) {
            ItemStack tag = new ItemStack(stock > 0 ? Items.BARREL : Items.GRAY_DYE);
            tag.set(DataComponentTypes.CUSTOM_NAME,
                    plain("The Bar").formatted(Formatting.GOLD, Formatting.BOLD));
            List<Text> lore = new ArrayList<>(List.of(
                    line(stock + " to hand out", stock > 0 ? Formatting.GREEN : Formatting.RED),
                    Text.empty(),
                    line("Everybody through the door gets one.", Formatting.GRAY),
                    line("Served punters stay. Dry bar, they", Formatting.GRAY),
                    line("have a go and leave.", Formatting.GRAY),
                    Text.empty(),
                    line("Your own product is worth "
                            + Math.round(TrapMath.SERVED_PRODUCT / TrapMath.SERVED_FOOD)
                            + "x what", Formatting.WHITE),
                    line("food is. That's what the farm is for.", Formatting.WHITE)));
            tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
            return tag;
        }
        if (row == 1) {
            ItemStack tag = new ItemStack(Items.WHEAT);
            tag.set(DataComponentTypes.CUSTOM_NAME,
                    plain("What goes on it").formatted(Formatting.WHITE, Formatting.BOLD));
            tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line("Anything edible, and anything you grew.", Formatting.GRAY),
                    Text.empty(),
                    line("Buds, joints, blends, powder  ->  best", Formatting.LIGHT_PURPLE),
                    line("Bread, stew, cake, apples  ->  it'll do", Formatting.DARK_GRAY))));
            return tag;
        }
        ItemStack tag = new ItemStack(house.dryBar() ? Items.REDSTONE_TORCH : Items.TORCH);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(house.dryBar() ? "DRY" : "Open").formatted(
                        house.dryBar() ? Formatting.RED : Formatting.GREEN, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(house.dryBar()
                                ? "Nothing behind the counter. The room is"
                                : "Stocked. They'll stay a while.",
                        house.dryBar() ? Formatting.RED : Formatting.GRAY),
                line(house.dryBar() ? "emptying and your name with it."
                                : "Keep it that way.",
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
        house.bar.clear();
        for (int index = 0; index < STOCK; index++) {
            ItemStack stack = shelf.getStack(index);
            if (!stack.isEmpty()) {
                house.bar.add(stack);
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
