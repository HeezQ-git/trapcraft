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

    /**
     * The window, which is square and which the player chooses.
     *
     * Four cabinets share one cabinet: 2x2 through 5x5, switched with the
     * button in the tray. They are genuinely different games -- see
     * TrapMath.SLOT_SIZES -- and the reel width, the shapes, the odds and the
     * return all change with the window rather than being the 5x5's numbers
     * stretched over a smaller grid.
     */
    private int size = 5;

    private static final int STAKE_SLOT = 47;
    private static final int PURSE_SLOT = 51;
    /** The button that swaps cabinets, in the tray next to the stake. */
    private static final int SIZE_SLOT = 45;

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
    private int[] grid = new int[25];
    private int[] winners = new int[0];

    /** Where each reel has come to rest, once it has. */
    private int[] landed = new int[5];
    /** Scroll offset per reel while it's still moving. */
    private int[] offset = new int[5];
    /** Ticks of noise left after the reels have settled. */
    private int celebrating;
    private int flash;
    /** Set when the screen closes, so the tick loop lets go. */
    private boolean closed;
    /** Ticks of jackpot fireworks left to draw. */
    private int fanfare;
    /** What the finished board won, in words, for the receipt. */
    private List<String> ways = List.of();
    /** Whose money is on the other side of the table. Null means nobody's. */
    private final TrapHouse.House house;

    public SlotScreenHandler(int syncId, PlayerInventory playerInventory,
                             TrapHouse.House house) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.player = (ServerPlayerEntity) playerInventory.player;
        this.house = house;

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

        fit();
        repaint();
    }

    // --- the window -----------------------------------------------------------

    /** Reshape everything that depends on the window. */
    private void fit() {
        grid = new int[size * size];
        landed = new int[size];
        offset = new int[size];
        for (int reel = 0; reel < size; reel++) {
            landed[reel] = reel % faces();
        }
        winners = new int[0];
        ways = List.of();
        pending = 0.0f;
        lastWon = 0;
    }

    private int faces() {
        return TrapMath.slotFaces(size);
    }

    /** Left-hand column of the window, so a small grid sits centred. */
    private int windowLeft() {
        return (9 - size) / 2;
    }

    /**
     * Which of the twenty-two drawn symbols face n of this cabinet is.
     *
     * Spread across the whole strip rather than truncated, so the narrow reels
     * still run from coal to the star. Truncating would have left the 2x2
     * topping out at iron nugget, and a jackpot symbol you cannot ever see is
     * a jackpot nobody believes in.
     */
    private int drawn(int symbol) {
        int count = faces();
        return count <= 1 ? 0 : symbol * (FACES.length - 1) / (count - 1);
    }

    /** Last reel stops here, plus a beat. Small windows are quick on purpose. */
    private int spinTicks() {
        return STOPS[size - 1] + 4;
    }

    // --- painting -------------------------------------------------------------

    private void repaint() {
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, pane(surround(index)));
        }
        for (int cell = 0; cell < grid.length; cell++) {
            int slot = (cell / size) * 9 + windowLeft() + (cell % size);
            display.setStack(slot, face(cell));
        }
        display.setStack(SIZE_SLOT, sizeTag());
        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(leverSlot(), leverTag());
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

    /**
     * The arm, on the right-hand edge level with the middle of the window.
     *
     * Outside the grid whatever its size, where the surround panes are, so it
     * reads as the arm on the side of the cabinet rather than another button
     * in the tray.
     */
    private int leverSlot() {
        return (size / 2) * 9 + 8;
    }

    /** One cell of the board, lit if it was part of the win. */
    private ItemStack face(int cell) {
        int reel = cell % size;
        boolean moving = spinning > 0 && spinning > spinTicks() - STOPS[reel];
        int symbol = moving
                ? Math.floorMod(offset[reel] + cell / size, faces())
                : grid[cell];

        ItemStack tag = new ItemStack(FACES[drawn(symbol)]);
        boolean won = !moving && contains(winners, cell);
        if (won) {
            // Enchantment glint: the winning line is unmistakable without
            // needing the player to work out which line it even was.
            tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(FACE_NAMES[drawn(symbol)]).formatted(
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

    /** The button that swaps cabinets. */
    private ItemStack sizeTag() {
        ItemStack tag = new ItemStack(switch (size) {
            case 2 -> Items.IRON_NUGGET;
            case 3 -> Items.COPPER_INGOT;
            case 4 -> Items.GOLD_INGOT;
            default -> Items.DIAMOND;
        });
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(TrapMath.slotCabinet(size)).formatted(Formatting.AQUA, Formatting.BOLD)
                        .append(plain("   " + size + "x" + size).formatted(Formatting.WHITE)));
        List<Text> lore = new ArrayList<>();
        lore.add(line(faces() + " symbols on the reels.", Formatting.GRAY));
        lore.add(line(Math.round(TrapMath.slotWinRate(size) * 100)
                + " spins in 100 pay. House keeps "
                + Math.round((1 - TrapMath.slotRtp(size)) * 100) + "%.", Formatting.GRAY));
        lore.add(Text.empty());
        // Said plainly, because the obvious guess -- that a big window is just
        // a small one with more room -- is wrong in both directions. A 2x2
        // pays more often per emerald in the sense that its top prize is
        // reachable, and less often in the sense that most spins are nothing.
        lore.add(line(switch (size) {
            case 2 -> "Two seconds a spin. Pairs pay.";
            case 3 -> "Quick. Lines and the odd shape.";
            case 4 -> "Room for blocks and fours.";
            default -> "The full board. Everything is on it.";
        }, Formatting.WHITE));
        lore.add(Text.empty());
        lore.add(line("Click for the next cabinet.", Formatting.YELLOW));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
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
        for (Text row : paytable(size)) {
            lore.add(row);
        }
        lore.add(Text.empty());
        lore.add(line("Rows, columns and EVERY diagonal.", Formatting.GRAY));
        lore.add(line("Separate wins add up.", Formatting.WHITE));
        lore.add(line("Winning symbols glow.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("About " + Math.round(TrapMath.slotWinRate(size) * 100)
                + " spins in 100 pay, and a", Formatting.DARK_GRAY));
        lore.add(line("win never returns less than the stake.", Formatting.DARK_GRAY));
        lore.add(line("The house keeps about "
                + Math.round((1 - TrapMath.slotRtp(size)) * 100)
                + "% over time.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /**
     * Every way THIS cabinet can win, biggest multiplier first.
     *
     * Built from the shapes the window actually contains rather than from a
     * fixed list, so a 3x3 never advertises a Diamond it has no room for. A
     * paytable quoting a prize the machine cannot pay is worse than no
     * paytable at all.
     */
    private static List<Text> paytable(int size) {
        record Row(String name, float pay) {
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        for (TrapMath.SlotShape shape : TrapMath.slotShapes(size)) {
            present.add(shape.name());
        }
        List<Row> rows = new ArrayList<>();
        if (size >= 5) {
            rows.add(new Row("Five in a line", TrapMath.PAY_RUN5));
        }
        if (present.contains("Four Corners")) {
            rows.add(new Row("Four Corners", TrapMath.PAY_CORNERS));
        }
        if (present.contains("Diamond")) {
            rows.add(new Row("Diamond", TrapMath.PAY_DIAMOND));
        }
        if (present.contains("Zed")) {
            rows.add(new Row("Zed  Z", TrapMath.PAY_ZED));
        }
        if (size >= 4) {
            rows.add(new Row("Four in a line", TrapMath.PAY_RUN4));
        }
        if (present.contains("Star")) {
            rows.add(new Row("Star  X", TrapMath.PAY_CROSS));
        }
        if (present.contains("Cross")) {
            rows.add(new Row("Cross  +", TrapMath.PAY_PLUS));
        }
        if (present.contains("Block")) {
            rows.add(new Row("Block  2x2", TrapMath.PAY_SQUARE));
        }
        if (size >= 3) {
            rows.add(new Row("Three in a line", TrapMath.PAY_RUN3));
        }
        if (TrapMath.slotRunFloor(size) == 2) {
            rows.add(new Row("Two in a line", TrapMath.PAY_RUN2));
        }
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
        tag.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(
                TrapHouse.tableNote(house, TrapHouse.TOP_SLOT)));
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
        if (slotIndex == SIZE_SLOT) {
            int next = 0;
            for (int i = 0; i < TrapMath.SLOT_SIZES.length; i++) {
                if (TrapMath.SLOT_SIZES[i] == size) {
                    next = (i + 1) % TrapMath.SLOT_SIZES.length;
                }
            }
            size = TrapMath.SLOT_SIZES[next];
            fit();
            beep(1.0F + next * 0.2F);
            repaint();
            return;
        }
        if (slotIndex != leverSlot()) {
            return;
        }

        int stake = STAKES[stakeChoice];
        // Limited at a single five-in-a-row. A board that lands several lines
        // at once can beat this and empty the vault -- see TrapHouse -- which
        // is deliberate: limiting at the theoretical 150x would put a 32e spin
        // out of reach of any casino anybody will actually build.
        if (!TrapHouse.covers(house, stake, TrapHouse.TOP_SLOT)) {
            beep(0.5F);
            player.sendMessage(plain("The house won't take a " + stake
                    + "e spin -- there isn't the money behind it.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.wealthOf(player) < stake) {
            beep(0.5F);
            player.sendMessage(plain("You can't cover a " + stake + "e spin.")
                    .formatted(Formatting.GRAY), false);
            return;
        }

        TrapHouse.stake(player, house, stake);
        buildBoard();
        spinning = spinTicks();
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
        grid(TrapMath.slotBoard(random, TrapMath.slotPlan(random.nextFloat(), size), size));
    }

    private void grid(int[] drawn) {
        System.arraycopy(drawn, 0, grid, 0, grid.length);
        TrapMath.SlotScore score = TrapMath.slotScore(grid, size);
        winners = score.cells();
        pending = score.pay();
        ways = score.names();
    }

    /** Called each server tick while anything is moving. */
    @Override
    public boolean tick() {
        flash++;
        if (closed) {
            return false;
        }

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

        for (int reel = 0; reel < size; reel++) {
            boolean stillMoving = spinning > spinTicks() - STOPS[reel];
            if (stillMoving) {
                offset[reel] = Math.floorMod(offset[reel] + 1, faces());
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
            lastWon = TrapHouse.payout(player, house, lastWon);
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
        TrapCasino.won(player, "slot");
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


    /**
     * Stop the tick loop when the player walks away.
     *
     * Without this the handler stays in the casino's tick list repainting a
     * screen nobody is looking at until its animation happens to finish. The
     * money is never at risk -- payout happens the moment the reels stop, not
     * when the lights do -- but it is work done for an audience of nobody.
     */
    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        closed = true;
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
