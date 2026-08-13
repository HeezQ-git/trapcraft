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

import java.util.List;

/**
 * The counter you stand at to put money in or take it out.
 *
 * A 9x3 chest of buttons rather than slots. Nothing here is a real slot: the
 * display is read-only and every click is a command, which is the pattern this
 * mod uses everywhere it needs a menu, and the reason none of them can desync
 * the way the crafting and merchant screens did.
 */
public class WalletScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int PURSE_SLOT = 4;
    private static final int DEPOSIT_SLOT = 9;
    private static final int WALLET_SLOT = 13;
    private static final int TAKE_ALL_SLOT = 17;

    /**
     * Withdrawal buttons, in emeralds.
     *
     * Round numbers rather than 1/9/64: you think in emeralds, not in blocks,
     * and any amount you actually want is two or three clicks away. Shift-click
     * takes ten of whatever the button says.
     */
    private static final int[] STEPS = {1, 10, 100, 1000};
    private static final int[] STEP_SLOTS = {20, 21, 23, 24};

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity owner;
    private final ItemStack wallet;

    public WalletScreenHandler(int syncId, PlayerInventory playerInventory, ItemStack wallet) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.owner = (ServerPlayerEntity) playerInventory.player;
        this.wallet = wallet;

        for (int index = 0; index < SIZE; index++) {
            this.addSlot(new ReadOnlySlot(display, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18));
        }
        // The player's own inventory stays a normal inventory, so you can still
        // rearrange your bag while the wallet is open.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        paint();
    }

    // --- the face -------------------------------------------------------------

    private void paint() {
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        int balance = WalletItem.balanceOf(wallet);
        int loose = TrapMarket.wealthOf(owner) - balance;

        ItemStack purse = new ItemStack(Items.EMERALD);
        purse.set(DataComponentTypes.CUSTOM_NAME,
                plain("Przy sobie: ").formatted(Formatting.GRAY)
                        .append(plain(loose + "e").formatted(Formatting.WHITE, Formatting.BOLD)));
        purse.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Luźne szmaragdy i bloki w ekwipunku.", Formatting.DARK_GRAY))));
        display.setStack(PURSE_SLOT, purse);

        ItemStack pouch = new ItemStack(Items.EMERALD_BLOCK);
        pouch.set(DataComponentTypes.CUSTOM_NAME,
                plain("W portfelu: ").formatted(Formatting.GRAY)
                        .append(plain(balance + "e").formatted(Formatting.GREEN, Formatting.BOLD)));
        pouch.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Bez limitu. Włóż ile chcesz.", Formatting.GRAY),
                Text.empty(),
                line("This money is still spendable.", Formatting.WHITE),
                line("Sklepy i automaty biorą z niego, gdy", Formatting.DARK_GRAY),
                line("skończą ci się szmaragdy w ekwipunku.", Formatting.DARK_GRAY))));
        display.setStack(WALLET_SLOT, pouch);

        ItemStack deposit = new ItemStack(loose > 0 ? Items.HOPPER : Items.GRAY_DYE);
        deposit.set(DataComponentTypes.CUSTOM_NAME,
                plain("Włóż wszystko").formatted(Formatting.YELLOW, Formatting.BOLD));
        deposit.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(loose > 0
                                ? "Przenosi wszystkie " + loose + "e z ekwipunku."
                                : "Nie masz nic w ekwipunku.",
                        loose > 0 ? Formatting.GRAY : Formatting.DARK_GRAY),
                line("Blocks count as nine.", Formatting.DARK_GRAY))));
        display.setStack(DEPOSIT_SLOT, deposit);

        for (int i = 0; i < STEPS.length; i++) {
            int step = STEPS[i];
            boolean affordable = balance >= step;
            ItemStack button = new ItemStack(affordable ? Items.EMERALD : Items.GRAY_DYE);
            button.setCount(Math.min(64, Math.max(1, step)));
            button.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Wypłać " + step + "e")
                            .formatted(affordable ? Formatting.WHITE : Formatting.DARK_GRAY,
                                    Formatting.BOLD));
            button.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line(affordable ? "Kliknij, żeby wypłacić." : "Nie ma tam tyle.",
                            affordable ? Formatting.GRAY : Formatting.DARK_GRAY),
                    line("Shift+LPM wypłaca " + step * 10 + "e.", Formatting.DARK_GRAY))));
            display.setStack(STEP_SLOTS[i], button);
        }

        ItemStack all = new ItemStack(balance > 0 ? Items.CHEST : Items.GRAY_DYE);
        all.set(DataComponentTypes.CUSTOM_NAME,
                plain("Opróżnij portfel").formatted(Formatting.GOLD, Formatting.BOLD));
        all.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(balance > 0 ? "Wypłaca całe " + balance + "e." : "Już jest pusty.",
                        balance > 0 ? Formatting.GRAY : Formatting.DARK_GRAY),
                line("Duże kwoty wychodzą w blokach.",
                        Formatting.DARK_GRAY))));
        display.setStack(TAKE_ALL_SLOT, all);

        sendContentUpdates();
    }

    // --- the buttons ----------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity player) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, player);
            return;
        }
        // Guard against the wallet having been dropped, hoppered away or
        // handed to somebody else while its menu was open. Checking it is
        // still IN THE OWNER'S BAG, not just that it is still a wallet:
        // otherwise you could drop a wallet, let a friend pick it up, and keep
        // withdrawing from it through the screen you already had open.
        if (!wallet.isOf(TrapContent.wallet) || !holding()) {
            owner.closeHandledScreen();
            return;
        }
        boolean bulk = type == SlotActionType.QUICK_MOVE;

        if (index == DEPOSIT_SLOT) {
            int put = WalletItem.depositAll(owner, wallet);
            if (put <= 0) {
                deny();
            } else {
                chime(1.2F);
                owner.sendMessage(plain("Włożono ").formatted(Formatting.GRAY)
                        .append(plain(put + "e").formatted(Formatting.GREEN))
                        .append(plain(". Teraz w portfelu jest ").formatted(Formatting.GRAY))
                        .append(plain(WalletItem.balanceOf(wallet) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD))
                        .append(plain(".").formatted(Formatting.GRAY)), false);
            }
            paint();
            return;
        }

        if (index == TAKE_ALL_SLOT) {
            hand(WalletItem.balanceOf(wallet));
            return;
        }

        for (int i = 0; i < STEP_SLOTS.length; i++) {
            if (index == STEP_SLOTS[i]) {
                hand(STEPS[i] * (bulk ? 10 : 1));
                return;
            }
        }
    }

    /** Is the open wallet still the one in this player's inventory? */
    private boolean holding() {
        var inventory = owner.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot) == wallet) {
                return true;
            }
        }
        return false;
    }

    private void hand(int wanted) {
        int got = WalletItem.withdraw(owner, wallet, wanted);
        if (got <= 0) {
            deny();
            owner.sendMessage(plain("W portfelu nie ma nic do wypłaty.")
                    .formatted(Formatting.GRAY), false);
        } else {
            chime(0.9F);
            owner.sendMessage(plain("Took ").formatted(Formatting.GRAY)
                    .append(plain(got + "e").formatted(Formatting.GREEN))
                    .append(plain(got < wanted ? " -- that was the lot. " : " out. ")
                            .formatted(Formatting.GRAY))
                    .append(plain(WalletItem.balanceOf(wallet) + "e left")
                            .formatted(Formatting.DARK_GRAY)), false);
        }
        paint();
    }

    private void chime(float pitch) {
        owner.getWorld().playSound(null, owner.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.5F, pitch);
        owner.getWorld().playSound(null, owner.getBlockPos(),
                SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.6F, 1.1F);
    }

    private void deny() {
        owner.getWorld().playSound(null, owner.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        return ItemStack.EMPTY;   // nothing here moves; the buttons do the work
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == owner;
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting colour) {
        return plain(text).formatted(colour);
    }

    /** Looks like a slot, behaves like a button. */
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
