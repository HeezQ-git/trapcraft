package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
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
 * The Climb: six doors up, and one question asked six times.
 *
 * The fourth machine, and the only one that asks you anything while it is
 * running. The slot machine decides for you. Roulette wants your bet before
 * the wheel moves. The peg board is pure spectacle. Here you open a door, and
 * then you decide whether to open another -- and the money you are risking is
 * money you have already won.
 *
 * Every rung carries the same house edge by construction, so there is no
 * correct height to stop at. Cashing out on the first door and going for all
 * six are the same bet in expectation. That is the whole design: take the
 * arithmetic away and what is left is nerve.
 *
 *   rows 0-5   the ladder, top rung first, doors in the middle three columns
 *   left       what this rung pays and the odds of reaching it
 *   right      stake, ladder, cash out
 */
public class ClimbScreenHandler extends ScreenHandler implements TrapTables.Playing {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    /** Doors sit in the middle, leaving both edges for the read-outs. */
    private static final int DOOR_LEFT = 3;

    private static final int STAKE_SLOT = 8;
    private static final int LADDER_SLOT = 17;
    private static final int CASH_SLOT = 26;
    private static final int INFO_SLOT = 0;

    private static final int[] STAKES = {8, 32, 128};

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;

    private int stakeChoice = 0;
    private int ladder = 0;
    /** How many doors have been survived. Zero means nothing is staked yet. */
    private int rung;
    private boolean climbing;
    /** Which door on each rung is the bad one, drawn when the climb starts. */
    private int[] traps = new int[0];
    /** The door just opened, and whether it was the wrong one. */
    private int opened = -1;
    private boolean busted;
    private int celebrating;
    private int flash;
    /** Set when the screen closes, so the tick loop lets go. */
    private boolean closed;

    public ClimbScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.player = (ServerPlayerEntity) playerInventory.player;

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
        repaint();
    }

    // --- the ladder -----------------------------------------------------------

    /**
     * Which rung a screen row shows.
     *
     * Row 0 is the TOP of the ladder, so the climb reads upward on screen the
     * way it reads in the head. Rung 1 is the bottom row.
     */
    private static int rungOfRow(int row) {
        return TrapMath.CLIMB_RUNGS - row;
    }

    private static int rowOfRung(int rung) {
        return TrapMath.CLIMB_RUNGS - rung;
    }

    private void repaint() {
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, pane(Items.BLACK_STAINED_GLASS_PANE, " "));
        }
        for (int row = 0; row < ROWS; row++) {
            int atRung = rungOfRow(row);
            for (int door = 0; door < TrapMath.CLIMB_DOORS[ladder]; door++) {
                display.setStack(row * 9 + DOOR_LEFT + door, doorTag(atRung, door));
            }
            display.setStack(row * 9 + 1, rungTag(atRung));
        }
        display.setStack(INFO_SLOT, infoTag());
        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(LADDER_SLOT, ladderTag());
        display.setStack(CASH_SLOT, cashTag());
        sendContentUpdates();
    }

    /** One door. Shut, open, or the one that ended it. */
    private ItemStack doorTag(int atRung, int door) {
        boolean here = climbing && atRung == rung + 1;
        boolean climbed = atRung <= rung;
        boolean thisOne = celebrating > 0 && atRung == rung + 1 && door == opened;

        Item face;
        if (thisOne) {
            face = busted ? Items.RED_STAINED_GLASS_PANE : Items.LIME_STAINED_GLASS_PANE;
        } else if (climbed) {
            face = Items.GREEN_STAINED_GLASS_PANE;
        } else if (here) {
            face = flash % 8 < 4 ? Items.YELLOW_STAINED_GLASS_PANE : Items.ORANGE_STAINED_GLASS_PANE;
        } else {
            face = Items.GRAY_STAINED_GLASS_PANE;
        }

        ItemStack tag = new ItemStack(face);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(thisOne ? (busted ? "That was the one" : "Clear")
                                : climbed ? "Behind you"
                                : here ? "Open it" : "Locked")
                        .formatted(thisOne && busted ? Formatting.RED
                                        : here || climbed ? Formatting.GREEN : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        if (here || thisOne) {
            tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        if (here) {
            tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line("One of these " + TrapMath.CLIMB_DOORS[ladder]
                            + " ends the run.", Formatting.GRAY),
                    line("Survive and you're on "
                            + times(TrapMath.climbMultiplier(ladder, atRung)) + ".",
                            Formatting.DARK_GRAY))));
        }
        return tag;
    }

    /** The read-out beside each rung: what it pays and how likely it is. */
    private ItemStack rungTag(int atRung) {
        float multiplier = TrapMath.climbMultiplier(ladder, atRung);
        boolean reached = atRung <= rung;
        ItemStack tag = new ItemStack(reached ? Items.EMERALD : Items.GOLD_NUGGET);
        tag.setCount(Math.max(1, Math.min(64, Math.round(multiplier))));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Rung " + atRung + "   ").formatted(Formatting.GRAY)
                        .append(plain(times(multiplier))
                                .formatted(reached ? Formatting.GREEN : Formatting.GOLD,
                                        Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Reached by " + Math.round(TrapMath.climbSurvival(ladder, atRung) * 100)
                        + " climbs in 100.", Formatting.DARK_GRAY),
                line("Worth " + Math.round(STAKES[stakeChoice] * multiplier) + "e from here.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack infoTag() {
        ItemStack tag = new ItemStack(Items.BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("The Climb").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Open a door. One on each rung ends it.", Formatting.GRAY),
                line("Survive and you may climb, or take the", Formatting.GRAY),
                line("money and walk away.", Formatting.GRAY),
                Text.empty(),
                line("Every rung carries the same edge, so", Formatting.WHITE),
                line("there is no clever place to stop.", Formatting.WHITE),
                line("It's nerve, not arithmetic.", Formatting.WHITE),
                Text.empty(),
                line("The house keeps about "
                        + Math.round((1 - TrapMath.CLIMB_RETURN) * 100) + "%.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack stakeTag() {
        ItemStack tag = new ItemStack(Items.EMERALD,
                Math.max(1, Math.min(64, STAKES[stakeChoice] / 8)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stake: ").formatted(Formatting.GRAY)
                        .append(plain(STAKES[stakeChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(climbing ? "Locked in until this run ends." : "Click to change.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack ladderTag() {
        ItemStack tag = new ItemStack(ladder == 0 ? Items.IRON_INGOT : Items.BLAZE_POWDER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(TrapMath.CLIMB_NAMES[ladder])
                        .formatted(ladder == 0 ? Formatting.AQUA : Formatting.RED,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(TrapMath.CLIMB_DOORS[ladder] + " doors a rung, one of them bad.",
                        Formatting.GRAY),
                line("Tops out at "
                        + times(TrapMath.climbMultiplier(ladder, TrapMath.CLIMB_RUNGS)) + ".",
                        Formatting.GOLD),
                Text.empty(),
                line("Same house edge either way -- the only", Formatting.DARK_GRAY),
                line("difference is how wild it gets.", Formatting.DARK_GRAY),
                Text.empty(),
                line(climbing ? "Locked in until this run ends." : "Click to switch.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack cashTag() {
        int worth = climbing && rung > 0
                ? Math.round(STAKES[stakeChoice] * TrapMath.climbMultiplier(ladder, rung)) : 0;
        ItemStack tag = new ItemStack(worth > 0 ? Items.GOLD_INGOT : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(worth > 0 ? "TAKE " + worth + "e" : climbing ? "Nothing yet" : "Not climbing")
                        .formatted(worth > 0 ? Formatting.GOLD : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(worth > 0 ? "Walk away with it." : "Open a door first.",
                        worth > 0 ? Formatting.GRAY : Formatting.DARK_GRAY),
                line(worth > 0 && rung < TrapMath.CLIMB_RUNGS
                                ? "Next rung would be "
                                + Math.round(STAKES[stakeChoice]
                                * TrapMath.climbMultiplier(ladder, rung + 1)) + "e."
                                : "",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private static String times(float multiplier) {
        return String.format("%.2fx", multiplier);
    }

    // --- climbing -------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE || celebrating > 0) {
            if (index < 0 || index >= SIZE) {
                super.onSlotClick(index, button, type, clicker);
            }
            return;
        }
        if (index == STAKE_SLOT && !climbing) {
            stakeChoice = (stakeChoice + 1) % STAKES.length;
            click(1.4F);
            repaint();
            return;
        }
        if (index == LADDER_SLOT && !climbing) {
            ladder = (ladder + 1) % TrapMath.CLIMB_DOORS.length;
            click(1.1F);
            repaint();
            return;
        }
        if (index == CASH_SLOT) {
            cashOut();
            return;
        }

        int row = index / 9;
        int door = index % 9 - DOOR_LEFT;
        if (door < 0 || door >= TrapMath.CLIMB_DOORS[ladder]) {
            return;
        }
        if (rungOfRow(row) != rung + 1) {
            deny();   // only the rung you're standing under can be opened
            return;
        }
        open(door);
    }

    /**
     * Open a door.
     *
     * The stake is taken on the FIRST door, not when the screen opens, so
     * browsing the ladder costs nothing and the money leaves your pocket at
     * the moment you commit.
     */
    private void open(int door) {
        if (!climbing) {
            int stake = STAKES[stakeChoice];
            if (TrapMarket.wealthOf(player) < stake) {
                deny();
                player.sendMessage(plain("You can't cover a " + stake + "e climb.")
                        .formatted(Formatting.GRAY), false);
                return;
            }
            TrapMarket.take(player, stake);
            // Every trap drawn up front, so the ladder is fixed before the
            // first door rather than decided as you go. A machine that picks
            // the bad door AFTER you point at one is a machine that can never
            // let you win, and this one can.
            traps = new int[TrapMath.CLIMB_RUNGS];
            var random = player.getWorld().getRandom();
            for (int step = 0; step < traps.length; step++) {
                traps[step] = random.nextInt(TrapMath.CLIMB_DOORS[ladder]);
            }
            climbing = true;
            rung = 0;
        }

        opened = door;
        busted = traps[rung] == door;
        celebrating = busted ? 22 : 12;
        TrapTables.watch(this);

        var world = player.getWorld();
        if (busted) {
            world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.PLAYERS, 0.9F, 0.5F);
            world.spawnParticles(ParticleTypes.SMOKE,
                    player.getX(), player.getY() + 1.2, player.getZ(), 25, 0.4, 0.4, 0.4, 0.02);
        } else {
            world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                    SoundCategory.PLAYERS, 0.7F, 0.9F + rung * 0.12F);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    player.getX(), player.getY() + 1.2, player.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
        }
        repaint();
    }

    private void cashOut() {
        if (!climbing || rung <= 0) {
            deny();
            return;
        }
        int won = Math.round(STAKES[stakeChoice] * TrapMath.climbMultiplier(ladder, rung));
        TrapMarket.pay(player, won);
        TrapCasino.won(player, "climb");
        if (rung >= TrapMath.CLIMB_RUNGS) {
            TrapAwards.grant(player, "nerve");
        }
        if (won >= STAKES[stakeChoice] * 10) {
            TrapAwards.grant(player, "jackpot");
        }
        int net = won - STAKES[stakeChoice];

        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.9F, 1.3F);
        player.getWorld().spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                player.getX(), player.getY() + 1.3, player.getZ(), 30, 0.5, 0.5, 0.5, 0.25);
        player.sendMessage(plain("Walked away from rung " + rung + " with ")
                .formatted(Formatting.GRAY)
                .append(plain(won + "e").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(plain(net >= 0 ? "   net +" + net : "   net " + net)
                        .formatted(net >= 0 ? Formatting.DARK_GRAY : Formatting.RED)), false);
        reset();
        repaint();
    }

    private void reset() {
        climbing = false;
        rung = 0;
        traps = new int[0];
        opened = -1;
        busted = false;
    }

    @Override
    public boolean tick() {
        flash++;
        if (closed || celebrating <= 0) {
            return false;
        }
        celebrating--;
        repaint();
        if (celebrating > 0) {
            return true;
        }

        if (busted) {
            int lost = STAKES[stakeChoice];
            int had = rung > 0
                    ? Math.round(lost * TrapMath.climbMultiplier(ladder, rung)) : 0;
            player.sendMessage(plain("Wrong door on rung " + (rung + 1) + ". ")
                    .formatted(Formatting.GRAY)
                    .append(plain("-" + lost + "e").formatted(Formatting.RED))
                    .append(plain(had > 0 ? "   you were holding " + had + "e" : "")
                            .formatted(Formatting.DARK_GRAY)), false);
            reset();
            repaint();
            return false;
        }

        rung++;
        opened = -1;
        if (rung >= TrapMath.CLIMB_RUNGS) {
            // Top of the ladder: nothing left to risk, so it pays out itself.
            cashOut();
            return false;
        }
        repaint();
        return false;
    }

    /**
     * Close the menu mid-climb and you are paid what you were holding.
     *
     * A door already opened is resolved honestly on the way out rather than
     * forfeited: the result was decided the moment you clicked it, so a
     * survived door still counts and a bad one still ends the run. Without
     * this, closing during the second of flashing lights either lost you the
     * money outright or -- on the last rung, where tick() calls cashOut() --
     * paid you anyway, which is two different answers to the same question.
     */
    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        closed = true;
        if (climbing) {
            if (celebrating > 0 && !busted) {
                rung++;   // the door was already open and already good
            }
            boolean lost = celebrating > 0 && busted;
            if (!lost && rung > 0) {
                TrapMarket.pay(player,
                        Math.round(STAKES[stakeChoice] * TrapMath.climbMultiplier(ladder, rung)));
            }
        }
        celebrating = 0;
        reset();
    }

    // --- trimmings ------------------------------------------------------------

    private void click(float pitch) {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5F, pitch);
    }

    private void deny() {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
    }

    private static ItemStack pane(Item item, String name) {
        ItemStack tag = new ItemStack(item);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(name));
        return tag;
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting colour) {
        return plain(text).formatted(colour);
    }

    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == player;
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
