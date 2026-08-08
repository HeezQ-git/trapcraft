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
    private static final int BOSS_SLOT = 0;
    private static final int COMP_SLOT = 8;
    private static final int LOOSE_SLOT = 22;
    private static final int CONDITION_SLOT = 19;

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
        display.setStack(BOSS_SLOT, bossTag(house));
        display.setStack(COMP_SLOT, compTag(house));
        display.setStack(LOOSE_SLOT, looseTag(house));
        display.setStack(CONDITION_SLOT, conditionTag(house));

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
                line("  Different games, a full vault, and a", Formatting.DARK_GRAY),
                line("  machine free when somebody walks in.", Formatting.DARK_GRAY),
                line("  A queue at the door costs you most.", Formatting.RED),
                bar("Regulars", house.addiction, Formatting.LIGHT_PURPLE),
                line("  Held up by trade. Gone in half an hour", Formatting.DARK_GRAY),
                line("  of quiet. Nobody keeps it at 100.", Formatting.DARK_GRAY),
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
        int machines = TrapHouse.machineCount(house);
        int upkeep = machines * TrapMath.MACHINE_UPKEEP;
        List<Text> lore = new ArrayList<>();
        lore.add(line("Every bet lost on your machines lands", Formatting.GRAY));
        lore.add(line("here. Every win is paid out of it.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(plain("Upkeep  ").formatted(Formatting.GRAY)
                .append(plain(upkeep + "e").formatted(Formatting.RED, Formatting.BOLD))
                .append(plain(" every 30s").formatted(Formatting.DARK_GRAY)));
        lore.add(line("  " + machines + " machines, lit whether or not", Formatting.DARK_GRAY));
        lore.add(line("  anybody is playing them.", Formatting.DARK_GRAY));
        lore.add(plain("Protection  ").formatted(Formatting.GRAY)
                .append(plain(Math.round(TrapMath.PROTECTION_RATE * 100) + "%")
                        .formatted(Formatting.RED, Formatting.BOLD))
                .append(plain(" of everything played").formatted(Formatting.DARK_GRAY)));
        lore.add(line("  Off the vault, win or lose. Miss it", Formatting.DARK_GRAY));
        lore.add(line("  three times and they come round.", Formatting.RED));
        lore.add(line("  Your vault covers " + (upkeep <= 0 ? "forever"
                        : (house.balance / upkeep / 2) + " min") + " of quiet.",
                Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("A full vault is also most of your name:", Formatting.WHITE));
        lore.add(line("  " + TrapMath.FLOAT_PER_MACHINE + "e a machine is what it",
                Formatting.DARK_GRAY));
        lore.add(line("  wants to see. You hold "
                        + (machines <= 0 ? 0 : house.balance / machines) + "e.",
                Formatting.DARK_GRAY));
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

    private ItemStack bossTag(TrapHouse.House house) {
        ItemStack tag = new ItemStack(house.pitBoss ? Items.IRON_HELMET : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(house.pitBoss ? "Pit boss on the floor" : "Nobody watching")
                        .formatted(house.pitBoss ? Formatting.AQUA : Formatting.RED,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(house.pitBoss
                                ? TrapMath.PIT_BOSS_WAGE + "e a beat in wages."
                                : "The staff take "
                                + trim(TrapMath.SKIM_RATE * 100) + "% off the top.",
                        house.pitBoss ? Formatting.GRAY : Formatting.RED),
                line(house.pitBoss
                                ? "Cheats get shown the door."
                                : "About one punter in " + Math.round(1 / TrapMath.CHEAT_CHANCE)
                                + " is counting.",
                        house.pitBoss ? Formatting.GRAY : Formatting.RED),
                Text.empty(),
                // The wage is flat and the skim is proportional, so this is a
                // real decision and not an upgrade you always take.
                line("A wage is the same whatever the night", Formatting.WHITE),
                line("does. A cut isn't. Above about "
                        + (TrapMath.PIT_BOSS_WAGE * 2 * 60
                        / TrapMath.SKIM_RATE / 60) + "e", Formatting.WHITE),
                line("of trade an hour they pay for themselves.", Formatting.WHITE),
                Text.empty(),
                line(house.pitBoss
                                ? "Click to let them go."
                                : "Click to take somebody on. "
                                + TrapMath.PIT_BOSS_HIRE + "e up front.",
                        Formatting.YELLOW))));
        return tag;
    }

    private ItemStack compTag(TrapHouse.House house) {
        int machines = Math.max(1, TrapHouse.machineCount(house));
        int cost = machines * TrapMath.COMP_COST_PER_MACHINE;
        boolean can = house.compCooldown <= 0 && house.balance >= cost;
        ItemStack tag = new ItemStack(can ? Items.HONEY_BOTTLE : Items.GLASS_BOTTLE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stand a round").formatted(can ? Formatting.LIGHT_PURPLE
                        : Formatting.DARK_GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(cost + "e out of the vault for nothing", Formatting.GRAY),
                line("you can point at. +" + TrapMath.COMP_ADDICTION
                        + " regulars.", Formatting.GRAY),
                Text.empty(),
                line(house.compCooldown > 0
                                ? "Another " + house.compCooldown / 2 + " min before the next."
                                : "Click to put one on.",
                        house.compCooldown > 0 ? Formatting.DARK_GRAY : Formatting.YELLOW))));
        return tag;
    }

    private ItemStack looseTag(TrapHouse.House house) {
        boolean running = house.loose();
        boolean can = !running && house.looseCooldown <= 0;
        ItemStack tag = new ItemStack(running ? Items.GLOWSTONE
                : can ? Items.FIREWORK_ROCKET : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(running ? "RUNNING LOOSE" : "Run it loose")
                        .formatted(running ? Formatting.GOLD
                                : can ? Formatting.YELLOW : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("The machines pay over the odds for "
                        + TrapMath.LOOSE_BEATS / 2 + " minutes.", Formatting.GRAY),
                line("You lose money. On purpose.", Formatting.RED),
                Text.empty(),
                line("+" + TrapMath.LOOSE_REP_BONUS + " to your name while it runs,",
                        Formatting.WHITE),
                line("and the regulars build twice as fast.", Formatting.WHITE),
                Text.empty(),
                line(running ? house.looseBeats / 2 + " minutes left."
                                : house.looseCooldown > 0
                                ? "Not for another " + house.looseCooldown / 2 + " min."
                                : "Click to call one.",
                        running ? Formatting.GOLD
                                : can ? Formatting.YELLOW : Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack conditionTag(TrapHouse.House house) {
        int wear = TrapHouse.averageWear(house);
        ItemStack tag = new ItemStack(wear >= 60 ? Items.NETHERITE_SCRAP : Items.IRON_INGOT);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Condition").formatted(Formatting.GRAY, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(bar("Worn", wear, wear >= 60 ? Formatting.RED : Formatting.WHITE));
        lore.add(line(wear >= 60 ? "  A shabby room, and it shows in the name."
                        : "  Holding up.",
                wear >= 60 ? Formatting.RED : Formatting.DARK_GRAY));
        lore.add(Text.empty());
        int broken = 0;
        for (String where : TrapHouse.machinesOf(house)) {
            if (TrapHouse.wearAt(where) >= TrapMath.WEAR_BROKEN) {
                String[] parts = where.split(" ");
                if (broken < 6) {
                    lore.add(line("  OUT OF ORDER  " + parts[1] + ", " + parts[2]
                            + ", " + parts[3], Formatting.RED));
                }
                broken++;
            }
        }
        if (broken == 0) {
            lore.add(line("Nothing out of order.", Formatting.GREEN));
        } else if (broken > 6) {
            lore.add(line("  ...and " + (broken - 6) + " more", Formatting.RED));
        }
        lore.add(Text.empty());
        lore.add(line("Hit a machine with a Miner's Hammer to", Formatting.YELLOW));
        lore.add(line("put it right. The house pays for parts.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** 1.5 rather than 1.5000001, for a percentage in a lore line. */
    private static String trim(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value)
                : String.format("%.1f", value);
    }

    private ItemStack books(TrapHouse.House house) {
        ItemStack tag = new ItemStack(Items.BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("The books").formatted(Formatting.GOLD, Formatting.BOLD));
        long profit = house.profit();
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(house.plays + " bets taken", Formatting.GRAY),
                line(house.handle + "e of trade through the room", Formatting.GRAY),
                line(house.paid + "e paid out to punters", Formatting.GRAY),
                line(house.costs + "e in upkeep and the cut", Formatting.RED),
                Text.empty(),
                plain("Your own play  ").formatted(Formatting.DARK_GRAY)
                        .append(plain((house.ownPlay >= 0 ? "+" : "")
                                        + house.ownPlay + "e")
                                .formatted(house.ownPlay >= 0
                                        ? Formatting.GREEN : Formatting.RED)),
                line("  Kept out of the takings. It's your", Formatting.DARK_GRAY),
                line("  money going round in a circle.", Formatting.DARK_GRAY),
                Text.empty(),
                plain(profit >= 0 ? "Up " : "Down ").formatted(Formatting.WHITE)
                        .append(plain(Math.abs(profit) + "e")
                                .formatted(profit >= 0 ? Formatting.GREEN : Formatting.RED,
                                        Formatting.BOLD))
                        .append(plain("   (" + house.edge() + "% edge)")
                                .formatted(Formatting.DARK_GRAY)),
                Text.empty(),
                line(house.handle < 2000
                                ? "Early days. The margin is noise until"
                                : "Gross " + Math.round(house.grossProfit() * 100.0f
                                / Math.max(1, house.handle)) + "%, and the rest",
                        Formatting.DARK_GRAY),
                line(house.handle < 2000
                                ? "a few thousand emeralds have gone through."
                                : "goes on being open. Volume is the business.",
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
        if (index == BOSS_SLOT) {
            if (house.pitBoss) {
                TrapHouse.sackPitBoss(house);
                owner.sendMessage(plain("Let them go. Watch your own floor.")
                        .formatted(Formatting.GRAY), false);
                chime(0.8F);
            } else {
                String no = TrapHouse.hirePitBoss(house);
                if (no != null) {
                    deny();
                    owner.sendMessage(plain(no).formatted(Formatting.GRAY), false);
                } else {
                    chime(1.3F);
                    owner.sendMessage(plain("They start tonight.")
                            .formatted(Formatting.GREEN), false);
                }
            }
            CasinoCardItem.restamp(card, house);
            paint();
            return;
        }
        if (index == COMP_SLOT) {
            String no = TrapHouse.comp(house, TrapHouse.machineCount(house));
            if (no != null) {
                deny();
                owner.sendMessage(plain(no).formatted(Formatting.GRAY), false);
            } else {
                chime(1.5F);
                owner.getWorld().playSound(null, owner.getBlockPos(),
                        SoundEvents.ENTITY_VILLAGER_CELEBRATE,
                        SoundCategory.PLAYERS, 0.8F, 1.0F);
                owner.sendMessage(plain("Drinks on the house.")
                        .formatted(Formatting.LIGHT_PURPLE), false);
            }
            CasinoCardItem.restamp(card, house);
            paint();
            return;
        }
        if (index == LOOSE_SLOT) {
            String no = TrapHouse.runLoose(house);
            if (no != null) {
                deny();
                owner.sendMessage(plain(no).formatted(Formatting.GRAY), false);
            } else {
                chime(1.7F);
                owner.getWorld().playSound(null, owner.getBlockPos(),
                        SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7F, 1.4F);
                owner.sendMessage(plain("Loose for the next "
                        + TrapMath.LOOSE_BEATS / 2 + " minutes. It'll cost you.")
                        .formatted(Formatting.GOLD), false);
            }
            paint();
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
