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
public class SlotScreenHandler extends ScreenHandler implements TrapTables.Playing {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    /** Five reels, five rows, filling columns 2..6 of rows 0..4. */
    private static final int REELS = 5;
    private static final int WINDOW_ROWS = 5;
    private static final int WINDOW_LEFT = 2;
    /** Row 2 of the window is the payline. */
    private static final int PAYLINE_ROW = 2;

    private static final int STAKE_SLOT = 47;
    private static final int PURSE_SLOT = 51;
    /**
     * The arm, on the right-hand edge level with the middle of the reels.
     *
     * Column 8 of row 2 -- outside the 5x5 window, where the surround panes
     * are -- so it reads as the arm on the side of the cabinet rather than
     * another button in the tray.
     */
    private static final int LEVER_SLOT = 26;

    /**
     * Reel faces, worst to best. The last is the jackpot.
     *
     * Twenty-two of them, not six. On a 5x5 board a narrow reel means most
     * boards contain a three-in-a-line by pure chance, and those accidents
     * were the majority of what the machine paid -- so much of the budget that
     * a deliberate three could only be priced below the stake. Every face
     * added is fewer coincidences and more room in the paytable, which is what
     * paid for the multipliers being what they are.
     *
     * Must stay in step with TrapMath.SLOT_FACES; check_stock.py enforces it.
     */
    private static final Item[] FACES = {
            Items.COAL, Items.FLINT, Items.CLAY_BALL, Items.GUNPOWDER,
            Items.IRON_NUGGET, Items.GLOWSTONE_DUST, Items.REDSTONE, Items.BRICK,
            Items.COPPER_INGOT, Items.GOLD_NUGGET, Items.QUARTZ, Items.LAPIS_LAZULI,
            Items.PRISMARINE_SHARD, Items.IRON_INGOT, Items.PRISMARINE_CRYSTALS,
            Items.AMETHYST_SHARD, Items.PHANTOM_MEMBRANE, Items.BLAZE_ROD,
            Items.GOLD_INGOT, Items.ENDER_PEARL, Items.DIAMOND, Items.NETHER_STAR,
    };
    private static final String[] FACE_NAMES = {
            "Coal", "Flint", "Clay", "Gunpowder", "Iron Nugget", "Glowstone",
            "Redstone", "Brick", "Copper", "Gold Nugget", "Quartz", "Lapis",
            "Prismarine", "Iron", "Crystals", "Amethyst", "Membrane", "Blaze Rod",
            "Gold", "Ender Pearl", "Diamond", "Star",
    };

    private static final int[] STAKES = {8, 32, 128};

    /** Ticks each reel spins before it locks, first to last. */
    private static final int[] STOPS = {24, 32, 40, 48, 58};
    private static final int SPIN_TICKS = 62;
    /** Flashing after the reels stop, before the machine admits anything. */
    private static final int CELEBRATE_TICKS = 26;
    /** A win this big earns the full show. */
    private static final float JACKPOT_PAY = 10.0f;
    /** How long the show runs. Three seconds is long enough to look up. */
    private static final int FANFARE_TICKS = 60;

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
    /** Ticks of jackpot fireworks left to draw. */
    private int fanfare;
    /** What the finished board won, in words, for the receipt. */
    private List<String> ways = List.of();

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
            // Tiered on what it PAID, not on how many cells lit up: a Four
            // Corners is four cells and worth thirty times a lone three, and
            // lighting it the same colour would be a lie the player can see.
            if (pending >= JACKPOT_PAY) {
                Item[] rainbow = {Items.RED_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE,
                        Items.YELLOW_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE,
                        Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.PURPLE_STAINED_GLASS_PANE};
                return rainbow[Math.floorMod(flash + index / 9 + index % 9, rainbow.length)];
            }
            if (pending >= 3.0f) {
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
        List<Text> lore = new ArrayList<>();
        // Best first. A paytable you have to scan for the big number is a
        // paytable nobody reads.
        for (Text row : paytable()) {
            lore.add(row);
        }
        lore.add(Text.empty());
        lore.add(line("Rows, columns and EVERY diagonal.", Formatting.GRAY));
        lore.add(line("Separate wins add up.", Formatting.WHITE));
        lore.add(line("Winning symbols glow.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("About " + Math.round(TrapMath.SLOT_MEASURED_WIN_RATE * 100)
                + " spins in 100 pay, and a", Formatting.DARK_GRAY));
        lore.add(line("win never returns less than the stake.", Formatting.DARK_GRAY));
        lore.add(line("The house keeps about "
                + Math.round((1 - TrapMath.SLOT_MEASURED_RTP) * 100)
                + "% over time.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** Every way to win, biggest multiplier first. */
    private static List<Text> paytable() {
        record Row(String name, float pay) {
        }
        List<Row> rows = new ArrayList<>(List.of(
                new Row("Five in a line", TrapMath.PAY_RUN5),
                new Row("Four Corners", TrapMath.PAY_CORNERS),
                new Row("Diamond", TrapMath.PAY_DIAMOND),
                new Row("Zed  Z", TrapMath.PAY_ZED),
                new Row("Four in a line", TrapMath.PAY_RUN4),
                new Row("Star  X", TrapMath.PAY_CROSS),
                new Row("Cross  +", TrapMath.PAY_PLUS),
                new Row("Block  2x2", TrapMath.PAY_SQUARE),
                new Row("Three in a line", TrapMath.PAY_RUN3)));
        // Sorted rather than hand-ordered, so retuning a pay can never leave
        // the cabinet advertising them out of order.
        rows.sort((a, b) -> Float.compare(b.pay(), a.pay()));

        List<Text> out = new ArrayList<>();
        for (Row row : rows) {
            out.add(pay(row.name(), row.pay()));
        }
        return out;
    }

    /** One paytable row: what it's called, and what it multiplies your stake by. */
    private static MutableText pay(String name, float multiplier) {
        String shown = multiplier == Math.round(multiplier)
                ? "x" + (int) multiplier : "x" + multiplier;
        return line(name, Formatting.WHITE)
                .append(plain("   " + shown).formatted(Formatting.GOLD));
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
     * Draw a board, then read back what it actually won.
     *
     * Outcome first, board second -- that is what keeps the return an exact,
     * checkable number rather than an emergent mystery. But the payout is
     * scored off the FINISHED grid, so whatever the fill adds on top of the
     * planted shape is paid for too. That is the combo: three diamonds along
     * the bottom and three stars down a column is two wins, and the machine
     * pays for both.
     */
    private void buildBoard() {
        var random = new java.util.Random(player.getWorld().getRandom().nextLong());
        grid(TrapMath.slotBoard(random, TrapMath.slotPlan(random.nextFloat())));
    }

    private void grid(int[] drawn) {
        System.arraycopy(drawn, 0, grid, 0, grid.length);
        TrapMath.SlotScore score = TrapMath.slotScore(grid);
        winners = score.cells();
        pending = score.pay();
        ways = score.names();
    }

    /** Called each server tick while anything is moving. */
    @Override
    public boolean tick() {
        flash++;

        if (celebrating > 0) {
            celebrating--;
            if (fanfare > 0) {
                fanfare--;
                fireworks();
            }
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
        // Already scored off the finished grid in buildBoard(), so what glows
        // and what pays cannot disagree.
        lastWon = Math.round(stake * pending);

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
        if (pending >= JACKPOT_PAY) {
            // Long enough to be an event you stop and watch. fireworks() draws
            // the rest of it a tick at a time.
            fanfare = FANFARE_TICKS;
            celebrating = Math.max(celebrating, FANFARE_TICKS);
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, 1.0F, 0.8F);
            return;
        }
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                player.getX(), player.getY() + 1.2, player.getZ(), 18, 0.5, 0.6, 0.5, 0.06);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                SoundCategory.PLAYERS, 1.0F, 1.4F);
    }

    /** The honest bit, once the lights have finished lying. */
    private void announce() {
        int stake = STAKES[stakeChoice];
        if (lastWon <= 0) {
            player.sendMessage(plain("No good. ").formatted(Formatting.GRAY)
                    .append(plain("-" + stake + "e").formatted(Formatting.RED)), false);
        } else {
            int net = lastWon - stake;
            boolean jackpot = pending >= JACKPOT_PAY;
            // Name what won. "Paid out 12e" tells you nothing; "Star + 3 in a
            // row" tells you the shapes are real and worth looking for.
            String named = ways.isEmpty() ? "" : String.join(" + ", ways);
            player.sendMessage(plain(jackpot ? "JACKPOT.  " : "Paid out.  ")
                            .formatted(jackpot ? Formatting.GOLD : Formatting.GREEN,
                                    Formatting.BOLD)
                            .append(plain("+" + lastWon + "e").formatted(Formatting.GREEN))
                            .append(plain(net >= 0 ? "   net +" + net : "   net " + net)
                                    .formatted(net >= 0 ? Formatting.DARK_GRAY : Formatting.RED))
                            .append(plain(named.isEmpty() ? "" : "\n  " + named)
                                    .formatted(ways.size() > 1
                                            ? Formatting.AQUA : Formatting.DARK_GRAY)),
                    false);
        }
        repaint();
    }

    /**
     * The jackpot show, one tick at a time.
     *
     * Three things at once, because a single burst reads as a puff of smoke
     * and is over before you look up: a ring that expands outward across the
     * floor, a double helix climbing past you, and a scatter of totem sparkles
     * overhead. The arpeggio underneath climbs with the helix rather than
     * repeating, so the whole thing sounds like it is going somewhere.
     */
    private void fireworks() {
        var world = player.getWorld();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        int step = FANFARE_TICKS - fanfare;

        // The ring: a circle of flame racing outward along the ground.
        double radius = 0.6 + step * 0.13;
        for (int point = 0; point < 18; point++) {
            double angle = point * (Math.PI * 2 / 18);
            world.spawnParticles(ParticleTypes.FLAME,
                    x + Math.cos(angle) * radius, y + 0.15, z + Math.sin(angle) * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
        }

        // The helix: two strands a half-turn apart, winding upward.
        double climb = (step % 30) * 0.09;
        for (int strand = 0; strand < 2; strand++) {
            double angle = step * 0.42 + strand * Math.PI;
            world.spawnParticles(ParticleTypes.END_ROD,
                    x + Math.cos(angle) * 1.1, y + 0.2 + climb, z + Math.sin(angle) * 1.1,
                    1, 0.0, 0.0, 0.0, 0.0);
        }

        if (step % 4 == 0) {
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    x, y + 1.6, z, 12, 0.6, 0.4, 0.6, 0.35);
            world.spawnParticles(ParticleTypes.FIREWORK,
                    x, y + 2.2, z, 8, 0.8, 0.3, 0.8, 0.12);
        }
        if (step % 6 == 0) {
            // Climbing arpeggio: each note a step up the scale, so it reads as
            // going somewhere rather than as the same chime eight times.
            float pitch = 0.8F + (step / 6) * 0.14F;
            world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(),
                    SoundCategory.PLAYERS, 0.8F, Math.min(2.0F, pitch));
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST,
                    SoundCategory.PLAYERS, 0.5F, 1.2F);
        }
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
