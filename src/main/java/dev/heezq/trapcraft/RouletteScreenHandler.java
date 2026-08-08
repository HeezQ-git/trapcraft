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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Roulette, on a chest.
 *
 * The other half of the casino, and deliberately a different kind of game: the
 * slot machine decides your outcome and then shows you a board, while here you
 * choose what you are betting on before anything spins. Same house, same edge,
 * completely different decision.
 *
 * A single-zero wheel, so every bet on the table returns 36/37 -- straight up
 * on one number or flat on red, the edge is identical and the choice is purely
 * about how you want to lose it. See {@link TrapMath#rouletteReturn}.
 *
 *   [0][ the ball track / last eight results        ]
 *   [ 1 .. 9 ]
 *   [10 .. 18]
 *   [19 .. 27]
 *   [28 .. 36]
 *   [RED][BLK][ODD][EVN][LOW][HIGH][chip][SPIN][purse]
 */
public class RouletteScreenHandler extends ScreenHandler implements TrapTables.Playing {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private static final int ZERO_SLOT = 0;
    private static final int TRACK_FROM = 1;
    private static final int TRACK_TO = 8;
    private static final int NUMBERS_FROM = 9;

    private static final int RED_SLOT = 45;
    private static final int BLACK_SLOT = 46;
    private static final int ODD_SLOT = 47;
    private static final int EVEN_SLOT = 48;
    private static final int LOW_SLOT = 49;
    private static final int HIGH_SLOT = 50;
    private static final int CHIP_SLOT = 51;
    private static final int SPIN_SLOT = 52;
    private static final int PURSE_SLOT = 53;

    /** Chip sizes, in emeralds. */
    private static final int[] CHIPS = {1, 8, 32, 128};

    /**
     * The pockets in the order they sit on a real wheel.
     *
     * Not 0..36: the ball travels the wheel, not the betting board, and
     * walking this order is what makes the animation look like a ball losing
     * speed rather than a random number generator flickering.
     */
    private static final int[] WHEEL = {
            0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23,
            10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26,
    };

    private static final int SPIN_TICKS = 70;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;

    /** Bet name to emeralds staked. Insertion-ordered so the receipt reads sanely. */
    private final Map<String, Integer> bets = new LinkedHashMap<>();
    private final Map<String, Integer> lastBets = new LinkedHashMap<>();
    private int chipChoice = 1;

    private int spinning;
    /** Where the ball is on the wheel while it travels. */
    private int ballAt;
    private int result = -1;
    private int won;
    private int celebrating;
    private int flash;
    /** Recent results, newest last, for the board along the top. */
    private final List<Integer> history = new ArrayList<>();

    public RouletteScreenHandler(int syncId, PlayerInventory playerInventory) {
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

    // --- the felt -------------------------------------------------------------

    /** Which pocket a board slot bets on, or -1 if it isn't a number. */
    private static int pocketAt(int slot) {
        if (slot == ZERO_SLOT) {
            return 0;
        }
        int index = slot - NUMBERS_FROM;
        return index >= 0 && index < 36 ? index + 1 : -1;
    }

    private static int slotOf(int pocket) {
        return pocket == 0 ? ZERO_SLOT : NUMBERS_FROM + pocket - 1;
    }

    private static Item paneFor(int pocket) {
        if (pocket == 0) {
            return Items.LIME_STAINED_GLASS_PANE;
        }
        return TrapMath.rouletteRed(pocket)
                ? Items.RED_STAINED_GLASS_PANE : Items.BLACK_STAINED_GLASS_PANE;
    }

    private void repaint() {
        for (int pocket = 0; pocket < TrapMath.ROULETTE_POCKETS; pocket++) {
            display.setStack(slotOf(pocket), numberTag(pocket));
        }
        paintTrack();

        display.setStack(RED_SLOT, outsideTag("red", "RED", Items.RED_WOOL, Formatting.RED));
        display.setStack(BLACK_SLOT,
                outsideTag("black", "BLACK", Items.BLACK_WOOL, Formatting.DARK_GRAY));
        display.setStack(ODD_SLOT, outsideTag("odd", "ODD", Items.BONE, Formatting.WHITE));
        display.setStack(EVEN_SLOT, outsideTag("even", "EVEN", Items.BONE_MEAL, Formatting.WHITE));
        display.setStack(LOW_SLOT,
                outsideTag("low", "1 - 18", Items.IRON_NUGGET, Formatting.AQUA));
        display.setStack(HIGH_SLOT,
                outsideTag("high", "19 - 36", Items.GOLD_NUGGET, Formatting.GOLD));
        display.setStack(CHIP_SLOT, chipTag());
        display.setStack(SPIN_SLOT, spinTag());
        display.setStack(PURSE_SLOT, purseTag());
        sendContentUpdates();
    }

    /**
     * The strip along the top: the ball while it runs, the last eight results
     * when it isn't.
     *
     * Recent numbers are the one piece of information a roulette player always
     * wants and the table never owes them, so it may as well be there.
     */
    private void paintTrack() {
        for (int slot = TRACK_FROM; slot <= TRACK_TO; slot++) {
            display.setStack(slot, pane(Items.GRAY_STAINED_GLASS_PANE, " "));
        }
        if (spinning > 0) {
            int marker = TRACK_FROM + Math.floorMod(ballAt, TRACK_TO - TRACK_FROM + 1);
            int pocket = WHEEL[Math.floorMod(ballAt, WHEEL.length)];
            ItemStack ball = new ItemStack(Items.SNOWBALL);
            ball.set(DataComponentTypes.CUSTOM_NAME,
                    plain(String.valueOf(pocket)).formatted(Formatting.WHITE, Formatting.BOLD));
            display.setStack(marker, ball);
            return;
        }
        int shown = Math.min(history.size(), TRACK_TO - TRACK_FROM + 1);
        for (int i = 0; i < shown; i++) {
            int pocket = history.get(history.size() - shown + i);
            ItemStack tag = new ItemStack(paneFor(pocket));
            tag.set(DataComponentTypes.CUSTOM_NAME,
                    plain(String.valueOf(pocket)).formatted(colourOf(pocket)));
            tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line(i == shown - 1 ? "Last spin" : "Earlier", Formatting.DARK_GRAY))));
            display.setStack(TRACK_FROM + i, tag);
        }
    }

    private static Formatting colourOf(int pocket) {
        return pocket == 0 ? Formatting.GREEN
                : TrapMath.rouletteRed(pocket) ? Formatting.RED : Formatting.DARK_GRAY;
    }

    private ItemStack numberTag(int pocket) {
        String bet = String.valueOf(pocket);
        int staked = bets.getOrDefault(bet, 0);
        boolean landed = result == pocket && celebrating > 0;
        boolean ball = spinning > 0 && WHEEL[Math.floorMod(ballAt, WHEEL.length)] == pocket;

        ItemStack tag = new ItemStack(ball || (landed && flash % 4 < 2)
                ? Items.WHITE_STAINED_GLASS_PANE : paneFor(pocket));
        tag.setCount(Math.max(1, Math.min(64, staked)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(String.valueOf(pocket)).formatted(colourOf(pocket), Formatting.BOLD)
                        .append(plain(staked > 0 ? "   " + staked + "e" : "")
                                .formatted(Formatting.GREEN)));
        if (staked > 0 || landed) {
            tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }

        List<Text> lore = new ArrayList<>();
        lore.add(line(pocket == 0 ? "The house pocket" : "Straight up", Formatting.GRAY)
                .append(plain("   pays " + (TrapMath.ROULETTE_STRAIGHT - 1) + " to 1")
                        .formatted(Formatting.GOLD)));
        if (staked > 0) {
            lore.add(line("Riding: ", Formatting.DARK_GRAY)
                    .append(plain(staked + "e").formatted(Formatting.GREEN))
                    .append(plain("  ->  " + staked * TrapMath.ROULETTE_STRAIGHT + "e")
                            .formatted(Formatting.GOLD)));
        }
        lore.add(Text.empty());
        lore.add(line("Click", Formatting.YELLOW)
                .append(plain(" to put " + CHIPS[chipChoice] + "e on it")
                        .formatted(Formatting.GRAY)));
        if (staked > 0) {
            lore.add(line("Right-click", Formatting.YELLOW)
                    .append(plain(" to take it back").formatted(Formatting.GRAY)));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack outsideTag(String bet, String label, Item icon, Formatting colour) {
        int staked = bets.getOrDefault(bet, 0);
        ItemStack tag = new ItemStack(icon);
        tag.setCount(Math.max(1, Math.min(64, staked)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(label).formatted(colour, Formatting.BOLD)
                        .append(plain(staked > 0 ? "   " + staked + "e" : "")
                                .formatted(Formatting.GREEN)));
        if (staked > 0) {
            tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        List<Text> lore = new ArrayList<>();
        lore.add(line("Pays even money", Formatting.GOLD));
        lore.add(line("Zero takes it. That's the edge.", Formatting.DARK_GRAY));
        if (staked > 0) {
            lore.add(line("Riding: ", Formatting.DARK_GRAY)
                    .append(plain(staked + "e").formatted(Formatting.GREEN))
                    .append(plain("  ->  " + staked * TrapMath.ROULETTE_EVEN_MONEY + "e")
                            .formatted(Formatting.GOLD)));
        }
        lore.add(Text.empty());
        lore.add(line("Click", Formatting.YELLOW)
                .append(plain(" to put " + CHIPS[chipChoice] + "e on it").formatted(Formatting.GRAY)));
        if (staked > 0) {
            lore.add(line("Right-click", Formatting.YELLOW)
                    .append(plain(" to take it back").formatted(Formatting.GRAY)));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack chipTag() {
        ItemStack tag = new ItemStack(Items.EMERALD, Math.max(1, Math.min(64, CHIPS[chipChoice])));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Chip: ").formatted(Formatting.GRAY)
                        .append(plain(CHIPS[chipChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("How much each click puts down.", Formatting.GRAY),
                Text.empty(),
                line("Click", Formatting.YELLOW)
                        .append(plain(" for the next size up").formatted(Formatting.GRAY)))));
        return tag;
    }

    private ItemStack spinTag() {
        int total = staked();
        ItemStack tag = new ItemStack(spinning > 0 ? Items.REDSTONE_TORCH
                : total > 0 ? Items.LEVER : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(spinning > 0 ? "No more bets" : total > 0 ? "SPIN" : "Place a bet")
                        .formatted(spinning > 0 ? Formatting.GRAY
                                : total > 0 ? Formatting.GOLD : Formatting.DARK_GRAY,
                                Formatting.BOLD));

        List<Text> lore = new ArrayList<>();
        if (total > 0) {
            lore.add(line("On the table: ", Formatting.GRAY)
                    .append(plain(total + "e").formatted(Formatting.GREEN, Formatting.BOLD)));
            for (Map.Entry<String, Integer> bet : bets.entrySet()) {
                lore.add(line("  " + pretty(bet.getKey()) + "   ", Formatting.DARK_GRAY)
                        .append(plain(bet.getValue() + "e").formatted(Formatting.GREEN)));
            }
            lore.add(Text.empty());
        }
        lore.add(line("One zero, so every bet on this", Formatting.DARK_GRAY));
        lore.add(line("table returns the same "
                + Math.round(TrapMath.rouletteReturnToPlayer("red") * 100)
                + "%.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Right-click", Formatting.YELLOW)
                .append(plain(" clears the table").formatted(Formatting.GRAY)));
        if (!lastBets.isEmpty() && total == 0) {
            lore.add(line("Shift-click", Formatting.YELLOW)
                    .append(plain(" repeats your last bet").formatted(Formatting.GRAY)));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack purseTag() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Purse: ").formatted(Formatting.GRAY)
                        .append(plain(TrapMarket.wealthOf(player) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Wallets count.", Formatting.DARK_GRAY))));
        return tag;
    }

    private static String pretty(String bet) {
        return switch (bet) {
            case "red" -> "Red";
            case "black" -> "Black";
            case "odd" -> "Odd";
            case "even" -> "Even";
            case "low" -> "1-18";
            case "high" -> "19-36";
            default -> "No. " + bet;
        };
    }

    private int staked() {
        int total = 0;
        for (int amount : bets.values()) {
            total += amount;
        }
        return total;
    }

    // --- placing bets ---------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (spinning > 0) {
            deny();
            return;
        }
        boolean secondary = button == 1 && type == SlotActionType.PICKUP;

        if (index == CHIP_SLOT) {
            chipChoice = (chipChoice + 1) % CHIPS.length;
            click(1.4F);
            repaint();
            return;
        }
        if (index == SPIN_SLOT) {
            if (secondary) {
                clear();
            } else if (type == SlotActionType.QUICK_MOVE && staked() == 0) {
                repeatLast();
            } else {
                spin();
            }
            return;
        }

        String bet = betAt(index);
        if (bet == null) {
            return;
        }
        if (secondary) {
            Integer back = bets.remove(bet);
            if (back == null) {
                deny();
                return;
            }
            TrapMarket.pay(player, back);
            click(0.8F);
            repaint();
            return;
        }
        place(bet);
    }

    /** Which bet a board slot places, or null for scenery. */
    private static String betAt(int slot) {
        int pocket = pocketAt(slot);
        if (pocket >= 0) {
            return String.valueOf(pocket);
        }
        return switch (slot) {
            case RED_SLOT -> "red";
            case BLACK_SLOT -> "black";
            case ODD_SLOT -> "odd";
            case EVEN_SLOT -> "even";
            case LOW_SLOT -> "low";
            case HIGH_SLOT -> "high";
            default -> null;
        };
    }

    /**
     * Put a chip down.
     *
     * The money leaves your pocket the moment it hits the felt, not when the
     * wheel spins -- so a bet you can see on the table is a bet that has been
     * paid for, and taking it back pays you back. Anything else and a
     * disconnect mid-game is an argument about who owes whom.
     */
    private void place(String bet) {
        int chip = CHIPS[chipChoice];
        if (TrapMarket.wealthOf(player) < chip) {
            deny();
            player.sendMessage(plain("You can't cover a " + chip + "e chip.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        TrapMarket.take(player, chip);
        bets.merge(bet, chip, Integer::sum);
        click(1.0F + Math.min(0.6F, bets.size() * 0.05F));
        repaint();
    }

    private void clear() {
        if (bets.isEmpty()) {
            deny();
            return;
        }
        TrapMarket.pay(player, staked());
        bets.clear();
        click(0.7F);
        repaint();
    }

    /**
     * Put the last spin's bets back on the table, to the emerald.
     *
     * Replays the ACTUAL amounts rather than re-clicking at the current chip
     * size -- change the chip between spins and a rebuilt-from-chips version
     * would quietly stake a different number than the one it is repeating.
     */
    private void repeatLast() {
        if (lastBets.isEmpty()) {
            deny();
            return;
        }
        int owed = 0;
        for (int amount : lastBets.values()) {
            owed += amount;
        }
        if (TrapMarket.wealthOf(player) < owed) {
            deny();
            player.sendMessage(plain("That bet was ").formatted(Formatting.GRAY)
                    .append(plain(owed + "e").formatted(Formatting.RED))
                    .append(plain(". You're short.").formatted(Formatting.GRAY)), false);
            return;
        }
        TrapMarket.take(player, owed);
        bets.putAll(lastBets);
        click(1.2F);
        repaint();
    }

    // --- the spin -------------------------------------------------------------

    private void spin() {
        if (bets.isEmpty()) {
            deny();
            player.sendMessage(plain("Put something on the table first.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        result = player.getWorld().getRandom().nextInt(TrapMath.ROULETTE_POCKETS);

        // Wind the ball backwards from where it must finish, so the animation
        // lands on the result with no snap at the end. Deciding the outcome
        // first is what keeps the return an exact number; walking backwards
        // from it is what stops the wheel visibly cheating on the last frame.
        ballAt = indexOnWheel(result) - travel();
        spinning = SPIN_TICKS;
        TrapTables.watch(this);

        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS, 0.9F, 0.7F);
        repaint();
    }

    private static int indexOnWheel(int pocket) {
        for (int i = 0; i < WHEEL.length; i++) {
            if (WHEEL[i] == pocket) {
                return i;
            }
        }
        return 0;
    }

    /** Total pockets the ball crosses, summed from the same easing tick() uses. */
    private static int travel() {
        int total = 0;
        for (int tick = SPIN_TICKS; tick > 0; tick--) {
            total += speedAt(tick);
        }
        return total;
    }

    /** Pockets per tick: fast at the start, one at a time by the end. */
    private static int speedAt(int ticksLeft) {
        return 1 + ticksLeft * 4 / SPIN_TICKS;
    }

    @Override
    public boolean tick() {
        flash++;

        if (celebrating > 0) {
            celebrating--;
            repaint();
            if (celebrating == 0) {
                announce();
                return false;
            }
            return true;
        }
        if (spinning <= 0) {
            return false;
        }

        ballAt += speedAt(spinning);
        spinning--;
        repaint();

        // The tick of the ball, slowing with it. Every crossing near the end
        // so the last few pockets are audible one by one.
        if (spinning < 20 || spinning % 2 == 0) {
            player.getWorld().playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS,
                    0.35F, 1.4F - spinning * 0.008F);
        }
        if (spinning == 0) {
            settle();
            celebrating = 30;
        }
        return true;
    }

    private void settle() {
        won = 0;
        for (Map.Entry<String, Integer> bet : bets.entrySet()) {
            won += bet.getValue() * TrapMath.rouletteReturn(bet.getKey(), result);
        }
        lastBets.clear();
        lastBets.putAll(bets);
        history.add(result);
        if (won > 0) {
            TrapMarket.pay(player, won);
        }

        var world = player.getWorld();
        if (won <= 0) {
            world.playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.6F, 0.6F);
            return;
        }
        TrapCasino.won(player, "roulette");
        if (won >= staked() * 10) {
            TrapAwards.grant(player, "jackpot");
        }
        boolean straight = won >= staked() * 10;
        world.spawnParticles(straight ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.HAPPY_VILLAGER,
                player.getX(), player.getY() + 1.3, player.getZ(),
                straight ? 60 : 16, 0.5, 0.5, 0.5, straight ? 0.4 : 0.05);
        world.playSound(null, player.getBlockPos(),
                straight ? SoundEvents.ENTITY_PLAYER_LEVELUP
                        : SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                SoundCategory.PLAYERS, 1.0F, straight ? 1.0F : 1.5F);
    }

    private void announce() {
        int total = staked();
        MutableText where = plain(result + " " + (result == 0 ? "green"
                        : TrapMath.rouletteRed(result) ? "red" : "black"))
                .formatted(colourOf(result), Formatting.BOLD);

        if (won <= 0) {
            player.sendMessage(plain("The ball settles on ").formatted(Formatting.GRAY)
                    .append(where)
                    .append(plain(".   ").formatted(Formatting.GRAY))
                    .append(plain("-" + total + "e").formatted(Formatting.RED)), false);
        } else {
            int net = won - total;
            player.sendMessage(plain("The ball settles on ").formatted(Formatting.GRAY)
                    .append(where)
                    .append(plain(".   ").formatted(Formatting.GRAY))
                    .append(plain("+" + won + "e").formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(plain(net >= 0 ? "   net +" + net : "   net " + net)
                            .formatted(net >= 0 ? Formatting.DARK_GRAY : Formatting.RED)), false);
        }
        bets.clear();
        result = -1;
        repaint();
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

    /**
     * Chips left on the table when the screen closes go back in your pocket.
     *
     * The alternative is a player closing a menu and losing the stake they
     * hadn't spun yet, which is the sort of thing that stops people playing.
     */
    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        if (!bets.isEmpty() && spinning <= 0) {
            TrapMarket.pay(player, staked());
            bets.clear();
        }
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
