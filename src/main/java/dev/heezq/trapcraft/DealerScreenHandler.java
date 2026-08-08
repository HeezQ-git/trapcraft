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
 * A dealer's book: what they're carrying, and what they've made you.
 *
 * The top two rows are real slots -- as many as their level allows, the rest
 * barred off -- because handing somebody a stack of product should be dragging
 * a stack of product, not clicking a button labelled "give". The bottom row is
 * the ledger: takings, level, progress, and the door.
 *
 * Only product goes in. A dealer is not a chest, and a dealer holding your
 * pickaxe is a way to lose a pickaxe.
 */
public class DealerScreenHandler extends ScreenHandler {
    private static final int ROWS = 4;
    private static final int SIZE = ROWS * 9;
    private static final int BARS = 18;
    /** Rows two and three: a spacer, then the ledger. */
    private static final int CHROME = 18;
    private static final int WHO = 9;
    private static final int TAKINGS = 12;
    private static final int SEND_OUT = 14;
    private static final int LET_GO = 17;

    private final SimpleInventory pockets;
    private final SimpleInventory chrome = new SimpleInventory(CHROME);
    private final ServerPlayerEntity boss;
    private final TrapDealers.Dealer dealer;

    public DealerScreenHandler(int syncId, PlayerInventory playerInventory,
                               TrapDealers.Dealer dealer) {
        super(ScreenHandlerType.GENERIC_9X4, syncId);
        this.boss = (ServerPlayerEntity) playerInventory.player;
        this.dealer = dealer;
        this.pockets = new SimpleInventory(BARS);

        for (int i = 0; i < dealer.stock.size() && i < BARS; i++) {
            pockets.setStack(i, dealer.stock.get(i));
        }

        for (int index = 0; index < BARS; index++) {
            this.addSlot(new PocketSlot(pockets, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18, dealer));
        }
        // One inventory across both remaining rows, so the ledger row and the
        // spacer above it aren't two views of the same nine stacks.
        for (int index = 0; index < CHROME; index++) {
            this.addSlot(new ReadOnlySlot(chrome, index,
                    8 + (index % 9) * 18, 18 + (2 + index / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 103 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 161));
        }

        pockets.addListener(sender -> {
            writeBack();
            paint();
        });
        paint();
    }

    /**
     * The screen's slots are the truth; push them back to the dealer.
     *
     * ONLY contraband. The locked slots are filled with grey panes so you can
     * see they're locked, and this used to copy those panes into the dealer's
     * stock -- which saved them to disk, filled the poor man's pockets with
     * glass, and let a shift-click pull them out into your inventory. A pane
     * is scenery and can never be stock.
     */
    private void writeBack() {
        dealer.stock.clear();
        for (int index = 0; index < dealer.slots() && index < pockets.size(); index++) {
            ItemStack stack = pockets.getStack(index);
            if (!stack.isEmpty() && TrapContent.isContraband(stack)) {
                dealer.stock.add(stack);
            }
        }
        TrapDealers.touch();
    }

    private void paint() {
        ItemStack bar = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        bar.set(DataComponentTypes.CUSTOM_NAME,
                plain("Locked").formatted(Formatting.DARK_GRAY));
        bar.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Opens at a higher level.", Formatting.DARK_GRAY))));
        for (int index = dealer.slots(); index < BARS; index++) {
            if (pockets.getStack(index).isEmpty()) {
                pockets.setStack(index, bar.copy());
            }
        }
        for (int index = 0; index < chrome.size(); index++) {
            chrome.setStack(index, blank());
        }

        ItemStack who = new ItemStack(Items.PLAYER_HEAD);
        who.set(DataComponentTypes.CUSTOM_NAME,
                plain(dealer.name).formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> about = new ArrayList<>();
        about.add(line("Level " + dealer.level + " of " + TrapMath.DEALER_MAX_LEVEL,
                Formatting.WHITE));
        about.add(line("Carries " + dealer.slots() + " slots  ·  holding "
                + dealer.carrying(), Formatting.GRAY));
        about.add(line("Keeps " + Math.round(TrapMath.dealerCut(dealer.level) * 100)
                + "% of what they sell.", Formatting.GRAY));
        about.add(Text.empty());
        about.add(dealer.level >= TrapMath.DEALER_MAX_LEVEL
                ? line("As good as they get.", Formatting.GOLD)
                : line(dealer.toNextLevel() + " more sales to level "
                        + (dealer.level + 1) + ".", Formatting.DARK_GRAY));
        about.add(line("Robbed about "
                + String.format("%.1f", robbedPerHour() * 100) + "% of hours.",
                dealer.level < 3 ? Formatting.RED : Formatting.DARK_GRAY));
        about.add(Text.empty());
        about.add(line("They sell best at night.", Formatting.DARK_GRAY));
        who.set(DataComponentTypes.LORE, new LoreComponent(about));
        chrome.setStack(WHO, who);

        ItemStack takings = new ItemStack(dealer.earnings > 0 ? Items.EMERALD : Items.GRAY_DYE);
        takings.setCount(Math.max(1, Math.min(64, dealer.earnings)));
        takings.set(DataComponentTypes.CUSTOM_NAME,
                plain("Takings: ").formatted(Formatting.GRAY)
                        .append(plain(dealer.earnings + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        takings.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(dealer.earnings > 0 ? "Click to take it." : "Nothing yet.",
                        dealer.earnings > 0 ? Formatting.YELLOW : Formatting.DARK_GRAY),
                line("Already net of their cut.", Formatting.DARK_GRAY),
                Text.empty(),
                line(dealer.sold + " sold all told.", Formatting.DARK_GRAY))));
        chrome.setStack(TAKINGS, takings);

        ItemStack out = new ItemStack(Items.CLOCK);
        out.set(DataComponentTypes.CUSTOM_NAME,
                plain("Send them out").formatted(Formatting.YELLOW, Formatting.BOLD));
        out.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("They sell nothing while they're stood here.", Formatting.GRAY),
                line("Close the book and they go back to it.", Formatting.DARK_GRAY))));
        chrome.setStack(SEND_OUT, out);

        ItemStack go = new ItemStack(Items.BARRIER);
        go.set(DataComponentTypes.CUSTOM_NAME,
                plain("Let them go").formatted(Formatting.RED, Formatting.BOLD));
        go.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Off the books for good.", Formatting.GRAY),
                line("Stock and takings come back to you.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Shift-click to be sure.", Formatting.DARK_GRAY))));
        chrome.setStack(LET_GO, go);

        sendContentUpdates();
    }

    /** Rough odds over an hour, for the tooltip. Twelve rounds to the hour. */
    private double robbedPerHour() {
        return 1 - Math.pow(1 - TrapMath.dealerRobChance(dealer.level), 12);
    }

    // --- the ledger -----------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        int at = index - BARS;
        if (at >= 0 && at < CHROME) {
            if (at == TAKINGS) {
                collect();
            } else if (at == LET_GO && type == SlotActionType.QUICK_MOVE) {
                TrapDealers.drop(boss, dealer);
                boss.closeHandledScreen();
            } else if (at == LET_GO) {
                boss.sendMessage(plain("Shift-click if you're sure.")
                        .formatted(Formatting.GRAY), true);
            } else if (at == SEND_OUT) {
                // Actually send them. This used to just close the screen and
                // leave the man standing there until his ninety seconds ran
                // out, which read as a button that did nothing.
                writeBack();
                TrapDealers.sendOut(boss, dealer);
                boss.closeHandledScreen();
            }
            return;
        }
        super.onSlotClick(index, button, type, clicker);
    }

    private void collect() {
        if (dealer.earnings <= 0) {
            boss.getWorld().playSound(null, boss.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
            return;
        }
        int paid = dealer.earnings;
        dealer.earnings = 0;
        TrapMarket.pay(boss, paid);
        TrapDealers.touch();
        boss.getWorld().playSound(null, boss.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.6F, 1.6F);
        boss.sendMessage(plain("Took ").formatted(Formatting.GRAY)
                .append(plain(paid + "e").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(plain(" off " + dealer.name + ".").formatted(Formatting.GRAY)), false);
        if (paid >= 1000) {
            TrapAwards.grant(boss, "network");
        }
        paint();
    }

    /**
     * Shift-clicking product from your bag loads them up.
     *
     * Only product, and only into slots their level has opened -- both checks
     * live on the slot, so dragging obeys the same rules as shift-clicking.
     */
    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
        if (index < BARS + CHROME) {
            // A locked slot gives nothing, ever. canTakeItems already refuses a
            // plain click; without the same check here a shift-click walked
            // straight past it and handed the player a glass pane.
            if (index >= dealer.slots()) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = index < BARS ? pockets.getStack(index) : ItemStack.EMPTY;
            if (stack.isEmpty() || !this.insertItem(stack, BARS + CHROME, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
            writeBack();
            paint();
            return ItemStack.EMPTY;
        }
        Slot from = this.slots.get(index);
        ItemStack stack = from.getStack();
        if (!TrapContent.isContraband(stack)) {
            return ItemStack.EMPTY;
        }
        if (!this.insertItem(stack, 0, dealer.slots(), false)) {
            return ItemStack.EMPTY;
        }
        from.markDirty();
        writeBack();
        paint();
        return ItemStack.EMPTY;
    }

    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        writeBack();
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == boss;
    }

    private ItemStack blank() {
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        return pane;
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }

    /** Product only, and only as far as their level reaches. */
    private static class PocketSlot extends Slot {
        private final TrapDealers.Dealer dealer;

        PocketSlot(Inventory inventory, int index, int x, int y, TrapDealers.Dealer dealer) {
            super(inventory, index, x, y);
            this.dealer = dealer;
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return getIndex() < dealer.slots() && TrapContent.isContraband(stack);
        }

        @Override
        public boolean canTakeItems(PlayerEntity player) {
            // The locked panes are scenery, not stock.
            return getIndex() < dealer.slots();
        }
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
