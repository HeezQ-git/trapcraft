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
 * The counting room.
 *
 * Money in, money out, and the only honest account of how the floor is doing.
 * Same chest-of-buttons pattern as the wallet, and for the same reason: every
 * click is a command against the ledger, nothing here is a real slot, so
 * nothing here can desync.
 *
 * The one number worth reading twice is the table limit. A machine will not
 * take a bet it cannot pay off at the game's top multiple, so a thin vault is
 * a floor full of machines nobody can play. That is the trade the whole
 * feature turns on: money left in the vault is money not in your pocket, and
 * money not in the vault is a casino that is closed.
 */
public class HouseScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int PLAQUE_SLOT = 4;
    private static final int DEPOSIT_SLOT = 9;
    private static final int VAULT_SLOT = 13;
    private static final int TAKE_ALL_SLOT = 17;
    private static final int FLOOR_SLOT = 18;
    private static final int BOOKS_SLOT = 26;

    private static final int[] STEPS = {10, 100, 1000, 10000};
    private static final int[] STEP_SLOTS = {20, 21, 23, 24};

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity owner;
    private final ItemStack card;

    public HouseScreenHandler(int syncId, PlayerInventory playerInventory, ItemStack card) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.owner = (ServerPlayerEntity) playerInventory.player;
        this.card = card;

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
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
        paint();
    }

    private TrapHouse.House house() {
        return TrapHouse.of(card);
    }

    // --- the face -------------------------------------------------------------

    private void paint() {
        TrapHouse.House house = house();
        if (house == null) {
            // Only reachable if the ledger loses the casino out from under an
            // open screen. Draw nothing rather than closing: paint() runs from
            // the constructor, and closing a screen that isn't installed yet
            // shuts the one behind it instead.
            return;
        }
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        long vault = house.balance;
        int loose = TrapMarket.wealthOf(owner);

        display.setStack(PLAQUE_SLOT, plaque(house));
        display.setStack(VAULT_SLOT, vault(house));
        display.setStack(FLOOR_SLOT, floor(house));
        display.setStack(BOOKS_SLOT, books(house));

        ItemStack deposit = new ItemStack(loose > 0 ? Items.HOPPER : Items.GRAY_DYE);
        deposit.set(DataComponentTypes.CUSTOM_NAME,
                plain("Put everything in").formatted(Formatting.YELLOW, Formatting.BOLD));
        deposit.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(loose > 0
                                ? "Sweeps all " + loose + "e into the vault."
                                : "Nothing on you to put in.",
                        loose > 0 ? Formatting.GRAY : Formatting.DARK_GRAY),
                line("Wallets and blocks count.", Formatting.DARK_GRAY),
                Text.empty(),
                line("A fat vault is a high table limit.", Formatting.WHITE))));
        display.setStack(DEPOSIT_SLOT, deposit);

        for (int i = 0; i < STEPS.length; i++) {
            int step = STEPS[i];
            boolean can = vault >= step;
            ItemStack button = new ItemStack(can ? Items.EMERALD : Items.GRAY_DYE);
            button.setCount(Math.min(64, Math.max(1, step / 10)));
            button.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Take " + step + "e")
                            .formatted(can ? Formatting.WHITE : Formatting.DARK_GRAY,
                                    Formatting.BOLD));
            button.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line(can ? "Click to draw it out." : "Not that much in the vault.",
                            can ? Formatting.GRAY : Formatting.DARK_GRAY),
                    line("Shift-click for " + step * 10 + "e.", Formatting.DARK_GRAY))));
            display.setStack(STEP_SLOTS[i], button);
        }

        ItemStack all = new ItemStack(vault > 0 ? Items.CHEST : Items.GRAY_DYE);
        all.set(DataComponentTypes.CUSTOM_NAME,
                plain("Clear the vault").formatted(Formatting.GOLD, Formatting.BOLD));
        all.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(vault > 0 ? "Takes the whole " + vault + "e." : "Already empty.",
                        vault > 0 ? Formatting.GRAY : Formatting.DARK_GRAY),
                Text.empty(),
                line("Your machines stop taking bets", Formatting.RED),
                line("until there's money behind them.", Formatting.RED))));
        display.setStack(TAKE_ALL_SLOT, all);

        sendContentUpdates();
    }

    private ItemStack plaque(TrapHouse.House house) {
        ItemStack tag = new ItemStack(Items.GOLD_BLOCK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(house.name).formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Opened by " + house.founder, Formatting.DARK_GRAY),
                Text.empty(),
                bar("Name", house.rep, Formatting.GOLD),
                line("  Bought with payouts. A room that never", Formatting.DARK_GRAY),
                line("  pays gets talked about as one.", Formatting.DARK_GRAY),
                bar("Regulars", house.addiction, Formatting.LIGHT_PURPLE),
                line("  Built by play, forgotten in the quiet.", Formatting.DARK_GRAY),
                Text.empty(),
                plain("Draws ").formatted(Formatting.GRAY)
                        .append(plain(String.format("%.2fx", house.pull()))
                                .formatted(Formatting.WHITE, Formatting.BOLD))
                        .append(plain(" the trade of an unknown floor.")
                                .formatted(Formatting.GRAY)),
                Text.empty(),
                line("Rename the card in an anvil and the", Formatting.GRAY),
                line("house takes the new name.", Formatting.GRAY))));
        return tag;
    }

    /** A stat as ten pips, because a bare number out of a hundred reads as noise. */
    private MutableText bar(String label, int value, Formatting colour) {
        int filled = Math.max(0, Math.min(10, Math.round(value / 10.0f)));
        return plain(label + "  ").formatted(Formatting.GRAY)
                .append(plain("|".repeat(filled)).formatted(colour, Formatting.BOLD))
                .append(plain("|".repeat(10 - filled)).formatted(Formatting.DARK_GRAY))
                .append(plain("  " + value).formatted(Formatting.DARK_GRAY));
    }

    private ItemStack vault(TrapHouse.House house) {
        ItemStack tag = new ItemStack(house.balance > 0 ? Items.EMERALD_BLOCK : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Vault: ").formatted(Formatting.GRAY)
                        .append(plain(house.balance + "e")
                                .formatted(house.balance > 0 ? Formatting.GREEN : Formatting.RED,
                                        Formatting.BOLD)));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Every bet lost on your machines lands", Formatting.GRAY));
        lore.add(line("here. Every win is paid out of it.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Biggest bet each game will take:", Formatting.WHITE));
        lore.add(limit("Lucky Streak", TrapHouse.TOP_SLOT, house));
        lore.add(limit("Roulette", TrapHouse.TOP_ROULETTE, house));
        lore.add(limit("The Drop", TrapHouse.TOP_DROP, house));
        lore.add(limit("The Climb", TrapHouse.TOP_CLIMB, house));
        lore.add(limit("Coin Toss", TrapHouse.TOP_TOSS, house));
        lore.add(limit("Blackjack", TrapHouse.TOP_BLACKJACK, house));
        lore.add(limit("Scratchers", TrapHouse.TOP_SCRATCH, house));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private MutableText limit(String game, int top, TrapHouse.House house) {
        int most = TrapHouse.limit(house, top);
        return plain("  " + game + "  ").formatted(Formatting.DARK_GRAY)
                .append(plain(most + "e").formatted(most > 0 ? Formatting.GREEN : Formatting.RED));
    }

    private ItemStack floor(TrapHouse.House house) {
        List<String> where = TrapHouse.machinesOf(house);
        ItemStack tag = new ItemStack(where.isEmpty() ? Items.GRAY_DYE : Items.LEVER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("The floor: ").formatted(Formatting.GRAY)
                        .append(plain(where.size() + (where.size() == 1
                                        ? " machine" : " machines"))
                                .formatted(Formatting.WHITE, Formatting.BOLD)));
        List<Text> lore = new ArrayList<>();
        if (where.isEmpty()) {
            lore.add(line("Nothing wired up yet.", Formatting.RED));
            lore.add(Text.empty());
            lore.add(line("Right-click a machine holding this", Formatting.YELLOW));
            lore.add(line("card and it starts paying into you.", Formatting.YELLOW));
        } else {
            // Coordinates rather than names: two Lucky Streaks in one room are
            // told apart by where they are and by nothing else.
            for (int i = 0; i < where.size() && i < 8; i++) {
                String[] parts = where.get(i).split(" ");
                lore.add(line("  " + parts[1] + ", " + parts[2] + ", " + parts[3],
                        Formatting.DARK_GRAY));
            }
            if (where.size() > 8) {
                lore.add(line("  ...and " + (where.size() - 8) + " more", Formatting.DARK_GRAY));
            }
            lore.add(Text.empty());
            lore.add(line("Right-click a wired machine again", Formatting.DARK_GRAY));
            lore.add(line("to cut it loose.", Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack books(TrapHouse.House house) {
        ItemStack tag = new ItemStack(Items.BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("The books").formatted(Formatting.GOLD, Formatting.BOLD));
        long profit = house.profit();
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(house.plays + " bets taken", Formatting.GRAY),
                line(house.handle + "e played through", Formatting.GRAY),
                line(house.paid + "e paid out", Formatting.GRAY),
                Text.empty(),
                plain(profit >= 0 ? "Up " : "Down ").formatted(Formatting.WHITE)
                        .append(plain(Math.abs(profit) + "e")
                                .formatted(profit >= 0 ? Formatting.GREEN : Formatting.RED,
                                        Formatting.BOLD))
                        .append(plain("   (" + house.edge() + "% edge)")
                                .formatted(Formatting.DARK_GRAY)),
                Text.empty(),
                line(house.handle < 2000
                                ? "Early days. The edge is noise until"
                                : "The machines run at 1-4% in your favour.",
                        Formatting.DARK_GRAY),
                line(house.handle < 2000
                                ? "a few thousand emeralds have gone through."
                                : "Give it time and it shows.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    // --- the buttons ----------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        TrapHouse.House house = house();
        // Same guard as the wallet: the card has to still be in the bag of the
        // player looking at this screen. Otherwise you could drop the card,
        // let somebody else pick it up, and keep emptying their vault through
        // a window you already had open.
        if (house == null || !holding()) {
            owner.closeHandledScreen();
            return;
        }
        boolean bulk = type == SlotActionType.QUICK_MOVE;

        if (index == DEPOSIT_SLOT) {
            int put = TrapHouse.deposit(owner, house);
            if (put <= 0) {
                deny();
            } else {
                chime(1.2F);
                owner.sendMessage(plain("Banked ").formatted(Formatting.GRAY)
                        .append(plain(put + "e").formatted(Formatting.GREEN))
                        .append(plain(". The vault holds ").formatted(Formatting.GRAY))
                        .append(plain(house.balance + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD))
                        .append(plain(".").formatted(Formatting.GRAY)), false);
            }
            CasinoCardItem.restamp(card, house);
            paint();
            return;
        }

        if (index == TAKE_ALL_SLOT) {
            draw(house, house.balance);
            return;
        }

        for (int i = 0; i < STEP_SLOTS.length; i++) {
            if (index == STEP_SLOTS[i]) {
                draw(house, (long) STEPS[i] * (bulk ? 10 : 1));
                return;
            }
        }
    }

    private void draw(TrapHouse.House house, long wanted) {
        int got = TrapHouse.withdraw(owner, house, wanted);
        if (got <= 0) {
            deny();
            owner.sendMessage(plain("The vault's empty.").formatted(Formatting.GRAY), false);
        } else {
            chime(0.9F);
            owner.sendMessage(plain("Drew ").formatted(Formatting.GRAY)
                    .append(plain(got + "e").formatted(Formatting.GREEN))
                    .append(plain(got < wanted ? " -- that was the lot. " : " out. ")
                            .formatted(Formatting.GRAY))
                    .append(plain(house.balance + "e behind the tables")
                            .formatted(Formatting.DARK_GRAY)), false);
        }
        CasinoCardItem.restamp(card, house);
        paint();
    }

    private boolean holding() {
        var inventory = owner.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot) == card) {
                return true;
            }
        }
        return false;
    }

    private void chime(float pitch) {
        owner.getWorld().playSound(null, owner.getBlockPos(),
                SoundEvents.BLOCK_VAULT_INSERT_ITEM, SoundCategory.PLAYERS, 0.6F, pitch);
        owner.getWorld().playSound(null, owner.getBlockPos(),
                SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.6F, 1.1F);
    }

    private void deny() {
        owner.getWorld().playSound(null, owner.getBlockPos(),
                SoundEvents.BLOCK_VAULT_INSERT_ITEM_FAIL, SoundCategory.PLAYERS, 0.7F, 0.9F);
    }

    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == owner;
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
        public boolean canTakeItems(PlayerEntity taker) {
            return false;
        }
    }
}
