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
 * The one-armed bandit, as a real reel window.
 *
 * A 9x6 chest laid out like a cabinet: three reels three symbols tall, with the
 * middle row marked as the payline. The reels scroll a strip of symbols
 * downward and stop left to right, which is the only reason the third reel is
 * ever exciting.
 *
 * The reels are theatre. The outcome comes out of {@link TrapMath#slotPayout}
 * BEFORE any symbol is chosen, and the strips are then built to land on
 * symbols that agree with it -- which is how real machines work, and the only
 * way the return rate is a number anyone can check. It pays back 85% over
 * time, and roughly three spins in four pay nothing.
 */
public class SlotScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    /** Five reels, five rows, filling columns 2..6 of rows 0..4. */
    private static final int REELS = 5;
    private static final int WINDOW_ROWS = 5;
    private static final int WINDOW_LEFT = 2;
    /** Row 2 of the window is the payline. */
    private static final int PAYLINE_ROW = 2;

    private static final int STAKE_SLOT = 47;
    private static final int LEVER_SLOT = 49;
    private static final int PURSE_SLOT = 51;

    /** Reel faces, worst to best. The last is the jackpot. */
    private static final Item[] FACES = {
            Items.COAL, Items.COPPER_INGOT, Items.IRON_INGOT,
            Items.GOLD_INGOT, Items.DIAMOND, Items.NETHER_STAR,
    };
    private static final String[] FACE_NAMES = {
            "Coal", "Copper", "Iron", "Gold", "Diamond", "Star",
    };

    private static final int[] STAKES = {8, 32, 128};

    /** Ticks each reel spins before it locks, first to last. */
    private static final int[] STOPS = {24, 32, 40, 48, 58};
    private static final int SPIN_TICKS = 62;
    /** Flashing after the reels stop, before the machine admits anything. */
    private static final int CELEBRATE_TICKS = 26;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;
    private int stakeChoice = 0;

    private int spinning;
    private float pending;
    /**
     * The whole board, not just a payline.
     *
     * Twenty-five cells, and the win is READ back off them rather than
     * assumed -- so the cells that glow are always the cells that paid. The
     * previous version only made the middle row agree with the outcome and
     * left the other twenty random, which is why the board looked full of
     * matches that paid nothing.
     */
    private final int[] grid = new int[REELS * WINDOW_ROWS];
    private int[] winners = new int[0];

    /** Where each reel has come to rest, once it has. */
    private final int[] landed = new int[REELS];
    /** Scroll offset per reel while it's still moving. */
    private final int[] offset = new int[REELS];
    /** Ticks of noise left after the reels have settled. */
    private int celebrating;
    private int flash;

    public SlotScreenHandler(int syncId, PlayerInventory playerInventory) {
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

        for (int reel = 0; reel < REELS; reel++) {
            landed[reel] = reel % FACES.length;
        }
        repaint();
    }

    // --- painting -------------------------------------------------------------

    private void repaint() {
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, pane(surround(index)));
        }
        for (int cell = 0; cell < grid.length; cell++) {
            int slot = (cell / REELS) * 9 + WINDOW_LEFT + (cell % REELS);
            display.setStack(slot, face(cell));
        }
        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(LEVER_SLOT, leverTag());
        display.setStack(PURSE_SLOT, purseTag());
        sendContentUpdates();
    }

    /**
     * The surround, animated by what was actually won.
     *
     * Rainbow is reserved for the jackpot. Making every spin rainbow taught
     * the eye that the lights mean nothing -- which is exactly what they meant.
     */
    private Item surround(int index) {
        if (spinning > 0) {
            // A slow two-tone sweep while the reels run: busy, but not a claim.
            return ((index / 9) + (index % 9) + flash / 3) % 2 == 0
                    ? Items.GRAY_STAINED_GLASS_PANE : Items.BLACK_STAINED_GLASS_PANE;
        }
        if (celebrating > 0 && lastWon > 0) {
            int tier = winners.length;
            if (tier >= 5) {
                Item[] rainbow = {Items.RED_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE,
                        Items.YELLOW_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE,
                        Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.PURPLE_STAINED_GLASS_PANE};
                return rainbow[Math.floorMod(flash + index / 9 + index % 9, rainbow.length)];
            }
            if (tier == 4) {
                return (flash / 2 + index) % 2 == 0
                        ? Items.ORANGE_STAINED_GLASS_PANE : Items.YELLOW_STAINED_GLASS_PANE;
            }
            return (flash / 3) % 2 == 0
                    ? Items.LIME_STAINED_GLASS_PANE : Items.GREEN_STAINED_GLASS_PANE;
        }
        return (index / 9) % 2 == 0
                ? Items.GRAY_STAINED_GLASS_PANE : Items.BLACK_STAINED_GLASS_PANE;
    }

    /** One cell of the board, lit if it was part of the win. */
    private ItemStack face(int cell) {
        int reel = cell % REELS;
        boolean moving = spinning > 0 && spinning > SPIN_TICKS - STOPS[reel];
        int symbol = moving
                ? Math.floorMod(offset[reel] + cell / REELS, FACES.length)
                : grid[cell];

        ItemStack tag = new ItemStack(FACES[symbol]);
        boolean won = !moving && contains(winners, cell);
        if (won) {
            // Enchantment glint: the winning line is unmistakable without
            // needing the player to work out which line it even was.
            tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(FACE_NAMES[symbol]).formatted(
                        won ? Formatting.GOLD : Formatting.DARK_GRAY,
                        won ? Formatting.BOLD : Formatting.ITALIC));
        return tag;
    }

    private static boolean contains(int[] cells, int cell) {
        for (int candidate : cells) {
            if (candidate == cell) {
                return true;
            }
        }
        return false;
    }

    private ItemStack pane(Item item) {
        ItemStack tag = new ItemStack(item);
        tag.set(DataComponentTypes.CUSTOM_NAME, Text.empty());
        return tag;
    }

    private ItemStack stakeTag() {
        ItemStack tag = new ItemStack(Items.EMERALD, Math.max(1, STAKES[stakeChoice] / 8));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stake ").formatted(Formatting.GRAY)
                        .append(plain(STAKES[stakeChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Click to change.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack leverTag() {
        ItemStack tag = new ItemStack(spinning > 0 ? Items.REDSTONE_TORCH : Items.LEVER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(spinning > 0 ? "Spinning" : "PULL")
                        .formatted(spinning > 0 ? Formatting.GRAY : Formatting.GOLD,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Five in a line", Formatting.WHITE)
                        .append(plain("   x" + (int) TrapMath.SLOT_PAYS[0]).formatted(Formatting.GOLD)),
                line("Four", Formatting.WHITE)
                        .append(plain("   x" + (int) TrapMath.SLOT_PAYS[1]).formatted(Formatting.GOLD)),
                line("Three", Formatting.WHITE)
                        .append(plain("   x" + TrapMath.SLOT_PAYS[2]).formatted(Formatting.GOLD)),
                Text.empty(),
                line("Rows, columns and both diagonals.", Formatting.GRAY),
                line("Winning symbols glow.", Formatting.GRAY),
                Text.empty(),
                line("About " + Math.round(sumOdds() * 100) + " spins in 100 pay.",
                        Formatting.DARK_GRAY),
                line("The house keeps about "
                        + Math.round((1 - TrapMath.slotReturnToPlayer()) * 100)
                        + "%.", Formatting.DARK_GRAY))));
        return tag;
    }

    private static float sumOdds() {
        float total = 0;
        for (float odd : TrapMath.SLOT_ODDS) {
            total += odd;
        }
        return total;
    }

    private ItemStack purseTag() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Purse ").formatted(Formatting.GRAY)
                        .append(plain(TrapMarket.wealthOf(player) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        return tag;
    }

    // --- spinning -------------------------------------------------------------

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity who) {
        if (spinning > 0 || celebrating > 0) {
            return;
        }
        if (slotIndex == STAKE_SLOT) {
            stakeChoice = (stakeChoice + 1) % STAKES.length;
            beep(1.4F);
            repaint();
            return;
        }
        if (slotIndex != LEVER_SLOT) {
            return;
        }

        int stake = STAKES[stakeChoice];
        if (TrapMarket.wealthOf(player) < stake) {
            beep(0.5F);
            player.sendMessage(plain("You can't cover a " + stake + "e spin.")
                    .formatted(Formatting.GRAY), false);
            return;
        }

        TrapMarket.take(player, stake);
        // Outcome first, symbols afterwards.
        pending = TrapMath.slotPayout(player.getWorld().getRandom().nextFloat());
        buildBoard();
        spinning = SPIN_TICKS;
        SlotMachineBlock.watch(this);

        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.PLAYERS, 0.9F, 0.6F);
        repaint();
    }

    /**
     * Pick the three faces the reels will stop on, given a decided payout.
     *
     * Three of a kind for the big tiers, a genuine pair for the small one, and
     * three different faces for a loss -- so what you see on the payline always
     * explains what you were paid.
     */
    /**
     * Build a board that genuinely contains the decided result.
     *
     * Outcome first, board second -- that keeps the return rate an exact,
     * checkable number. But the board is then constructed so the win is really
     * there on a real line, and re-rolled until no LONGER run appears by
     * accident. The payout is finally read back off the finished grid, so what
     * glows and what pays can never disagree.
     */
    private void buildBoard() {
        var random = player.getWorld().getRandom();
        int wantRun = 0;
        for (int tier = 0; tier < TrapMath.SLOT_PAYS.length; tier++) {
            if (pending >= TrapMath.SLOT_PAYS[tier]) {
                wantRun = TrapMath.SLOT_RUNS[tier];
                break;
            }
        }

        int[][] lines = TrapMath.slotLines();
        for (int attempt = 0; attempt < 200; attempt++) {
            for (int cell = 0; cell < grid.length; cell++) {
                grid[cell] = random.nextInt(FACES.length);
            }
            if (wantRun > 0) {
                int[] line = lines[random.nextInt(lines.length)];
                int symbol = random.nextInt(FACES.length);
                int start = random.nextInt(TrapMath.SLOT_SIZE - wantRun + 1);
                for (int i = 0; i < wantRun; i++) {
                    grid[line[start + i]] = symbol;
                }
                // Break the cells either side so the run is exactly as long as
                // it should be, not accidentally longer.
                if (start > 0) {
                    grid[line[start - 1]] = (symbol + 1) % FACES.length;
                }
                if (start + wantRun < TrapMath.SLOT_SIZE) {
                    grid[line[start + wantRun]] = (symbol + 1) % FACES.length;
                }
            }
            int[] found = TrapMath.slotWinningCells(grid);
            if (found.length == wantRun || (wantRun == 0 && found.length == 0)) {
                winners = found;
                return;
            }
        }
        // Couldn't land it cleanly in 200 tries: keep whatever the board says
        // and pay what it shows, rather than paying one thing and showing
        // another.
        winners = TrapMath.slotWinningCells(grid);
    }

    /** Called each server tick while anything is moving. */
    public boolean tick() {
        flash++;

        if (celebrating > 0) {
            celebrating--;
            repaint();
            // Chimes ONLY on a win. Ringing for every spin is how a machine
            // teaches you its sounds mean nothing.
            if (lastWon > 0 && celebrating % 3 == 0) {
                player.getWorld().playSound(null, player.getBlockPos(),
                        SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS,
                        0.6F, 1.0F + (celebrating % 6) * 0.12F);
            }
            if (celebrating == 0) {
                announce();
                return false;
            }
            return true;
        }

        if (spinning <= 0) {
            return false;
        }
        spinning--;

        for (int reel = 0; reel < REELS; reel++) {
            boolean stillMoving = spinning > SPIN_TICKS - STOPS[reel];
            if (stillMoving) {
                offset[reel] = Math.floorMod(offset[reel] + 1, FACES.length);
            } else if (offset[reel] != landed[reel]) {
                offset[reel] = landed[reel];
                beep(0.7F + reel * 0.18F);
                player.getWorld().playSound(null, player.getBlockPos(),
                        SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.PLAYERS,
                        0.4F, 0.8F + reel * 0.2F);
            }
        }
        repaint();

        if (spinning % 2 == 0) {
            player.getWorld().playSound(null, player.getBlockPos(),
                    SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.12F, 1.9F);
        }
        if (spinning == 0) {
            // Every spin celebrates, win or lose. Reading the chat line is the
            // only reliable way to know which -- which is exactly how these
            // machines work.
            celebrating = CELEBRATE_TICKS;
            payOut();
        }
        return true;
    }

    /** How much the last spin paid, held so the receipt can lag the lights. */
    private int lastWon;

    /**
     * Pay the money the instant the reels stop, before the noise.
     *
     * The lights lag the ledger on purpose -- the celebration is theatre, the
     * transaction is not, and tying the payout to an animation is how a
     * disconnect mid-flash turns into somebody's missing emeralds.
     */
    private void payOut() {
        int stake = STAKES[stakeChoice];
        // Read the board, don't trust the intent. If construction had to give
        // up, the player is paid for what is on screen.
        float shown = TrapMath.slotPayForRun(winners.length);
        lastWon = Math.round(stake * shown);
        pending = shown;

        if (lastWon > 0) {
            TrapMarket.pay(player, lastWon);
        }

        var world = player.getWorld();
        if (lastWon <= 0) {
            // A loss is quiet and short. No lights, no fanfare.
            world.playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.PLAYERS, 0.5F, 0.6F);
            celebrating = 6;
            return;
        }
        boolean jackpot = winners.length >= 5;
        world.spawnParticles(jackpot ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.HAPPY_VILLAGER,
                player.getX(), player.getY() + 1.2, player.getZ(),
                jackpot ? 70 : 18, 0.5, 0.6, 0.5, jackpot ? 0.45 : 0.06);
        world.playSound(null, player.getBlockPos(),
                jackpot ? SoundEvents.ENTITY_PLAYER_LEVELUP
                        : SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                SoundCategory.PLAYERS, 1.0F, jackpot ? 1.0F : 1.4F);
    }

    /** The honest bit, once the lights have finished lying. */
    private void announce() {
        int stake = STAKES[stakeChoice];
        if (lastWon <= 0) {
            player.sendMessage(plain("No good. ").formatted(Formatting.GRAY)
                    .append(plain("-" + stake + "e").formatted(Formatting.RED)), false);
        } else {
            int net = lastWon - stake;
            player.sendMessage(plain(lastWon >= stake * 10 ? "JACKPOT.  " : "Paid out.  ")
                            .formatted(lastWon >= stake * 10 ? Formatting.GOLD : Formatting.GREEN,
                                    Formatting.BOLD)
                            .append(plain("+" + lastWon + "e").formatted(Formatting.GREEN))
                            .append(plain(net >= 0 ? "   net +" + net : "   net " + net)
                                    .formatted(net >= 0 ? Formatting.DARK_GRAY : Formatting.RED)),
                    false);
        }
        repaint();
    }

    private void beep(float pitch) {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS, 0.6F, pitch);
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting colour) {
        return plain(text).formatted(colour);
    }

    @Override
    public ItemStack quickMove(PlayerEntity who, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity who) {
        return who == player;
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
        public boolean canTakeItems(PlayerEntity who) {
            return false;
        }
    }
}
