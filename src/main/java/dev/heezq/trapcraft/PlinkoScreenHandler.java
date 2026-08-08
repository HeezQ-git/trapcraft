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
 * The Drop: a ball, a field of pegs, and nine slots at the bottom.
 *
 * The third machine and a third kind of gamble. The slot machine picks your
 * outcome and then draws a board that agrees with it. Roulette lets you choose
 * what you are backing. Here you choose nothing at all and simply watch -- and
 * that is the point, because the odds are VISIBLE. Everybody can see the ball
 * usually ends up in the middle, so everybody knows the edges are where the
 * money is, and everybody watches the last two bounces.
 *
 * Nothing here is decided in advance. Eight fair coin flips ARE the outcome,
 * which makes this the one machine in the casino that isn't lying to you about
 * anything, and its return exact by construction rather than by tuning.
 *
 *   row 0     the ball, falling
 *   rows 1-3  pegs
 *   row 4     the nine slots and what they pay
 *   row 5     stake, drop, purse
 */
public class PlinkoScreenHandler extends ScreenHandler implements TrapTables.Playing {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int SLOTS_ROW = 4;
    private static final int FOOTER = SIZE - 9;

    private static final int STAKE_SLOT = FOOTER + 2;
    private static final int DROP_SLOT = FOOTER + 4;
    private static final int PURSE_SLOT = FOOTER + 6;

    private static final int[] STAKES = {8, 32, 128};
    /** Ticks between bounces. Slow enough to follow, fast enough to want another. */
    private static final int BOUNCE_TICKS = 5;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;
    private int stakeChoice = 0;

    /** Which way the ball went at each peg, decided when it is dropped. */
    private boolean[] path = new boolean[0];
    private int bounce;
    private int ticks;
    private int landed = -1;
    private int won;
    private int celebrating;
    private int flash;
    /** Set when the screen closes, so the tick loop lets go. */
    private boolean closed;
    private final List<Integer> history = new ArrayList<>();
    /** Whose money is on the other side of the table. Null means nobody's. */
    private final TrapHouse.House house;

    public PlinkoScreenHandler(int syncId, PlayerInventory playerInventory,
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
        repaint();
    }

    // --- the board ------------------------------------------------------------

    /**
     * Where the ball sits after so many bounces.
     *
     * Eight bounces across nine columns means it moves half a column at a
     * time, so two bounces are one visible step -- which is also why the last
     * pair matters so much and why people lean in for them.
     */
    private int ballColumn() {
        int right = 0;
        for (int step = 0; step < bounce && step < path.length; step++) {
            if (path[step]) {
                right++;
            }
        }
        int left = Math.min(bounce, path.length) - right;
        return Math.max(0, Math.min(8, 4 + (right - left) / 2));
    }

    private int ballRow() {
        return Math.min(SLOTS_ROW - 1, bounce * (SLOTS_ROW - 1) / TrapMath.PLINKO_BOUNCES);
    }

    private void repaint() {
        for (int index = 0; index < FOOTER; index++) {
            display.setStack(index, scenery(index));
        }
        if (falling()) {
            display.setStack(ballRow() * 9 + ballColumn(), ball());
        }
        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(DROP_SLOT, dropTag());
        display.setStack(PURSE_SLOT, purseTag());
        for (int index = FOOTER; index < SIZE; index++) {
            if (index != STAKE_SLOT && index != DROP_SLOT && index != PURSE_SLOT) {
                display.setStack(index, pane(Items.GRAY_STAINED_GLASS_PANE, " "));
            }
        }
        sendContentUpdates();
    }

    private boolean falling() {
        return bounce <= TrapMath.PLINKO_BOUNCES && path.length > 0 && landed < 0;
    }

    /** The pegs, and the nine slots along the bottom of the field. */
    private ItemStack scenery(int index) {
        int row = index / 9;
        int col = index % 9;

        if (row == SLOTS_ROW) {
            return slotTag(col);
        }
        // Pegs on alternating cells, so the field reads as something a ball
        // would actually rattle down rather than an empty box.
        boolean peg = row > 0 && (row + col) % 2 == 0;
        return pane(peg ? Items.LIGHT_GRAY_STAINED_GLASS_PANE : Items.BLACK_STAINED_GLASS_PANE,
                " ");
    }

    private ItemStack ball() {
        ItemStack tag = new ItemStack(Items.SNOWBALL);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("*").formatted(Formatting.WHITE, Formatting.BOLD));
        tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return tag;
    }

    private ItemStack slotTag(int slot) {
        float pays = TrapMath.PLINKO_PAYS[slot];
        boolean here = landed == slot && celebrating > 0;
        boolean rich = pays >= 5.0f;

        Item face = here && flash % 4 < 2 ? Items.WHITE_STAINED_GLASS_PANE
                : rich ? Items.ORANGE_STAINED_GLASS_PANE
                : pays >= 1.0f ? Items.LIME_STAINED_GLASS_PANE
                : Items.RED_STAINED_GLASS_PANE;
        ItemStack tag = new ItemStack(face);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(multiplier(pays)).formatted(
                        rich ? Formatting.GOLD : pays >= 1.0f ? Formatting.GREEN : Formatting.RED,
                        Formatting.BOLD));
        if (here) {
            tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        int paths = TrapMath.plinkoPaths(slot);
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Lands here " + paths + " times in 256.", Formatting.GRAY),
                line("Pays " + multiplier(pays) + " of the stake.", Formatting.DARK_GRAY))));
        return tag;
    }

    private static String multiplier(float pays) {
        return pays == Math.round(pays) ? (int) pays + "x" : pays + "x";
    }

    private ItemStack stakeTag() {
        ItemStack tag = new ItemStack(Items.EMERALD,
                Math.max(1, Math.min(64, STAKES[stakeChoice] / 8)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stake: ").formatted(Formatting.GRAY)
                        .append(plain(STAKES[stakeChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Click to change.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack dropTag() {
        ItemStack tag = new ItemStack(falling() ? Items.REDSTONE_TORCH : Items.LEVER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(falling() ? "Falling" : "DROP")
                        .formatted(falling() ? Formatting.GRAY : Formatting.GOLD, Formatting.BOLD));

        List<Text> lore = new ArrayList<>();
        lore.add(line("Nothing is decided in advance here.", Formatting.WHITE));
        lore.add(line("Eight coin flips, and you watch.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("The middle is likely and pays least.", Formatting.DARK_GRAY));
        lore.add(line("The edges land once in 256 each.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("The house keeps about "
                + Math.round((1 - TrapMath.plinkoReturnToPlayer()) * 100)
                + "% over time.", Formatting.DARK_GRAY));
        if (!history.isEmpty()) {
            StringBuilder recent = new StringBuilder();
            for (int i = Math.max(0, history.size() - 8); i < history.size(); i++) {
                recent.append(multiplier(TrapMath.PLINKO_PAYS[history.get(i)])).append(' ');
            }
            lore.add(Text.empty());
            lore.add(line("Last drops: " + recent, Formatting.DARK_GRAY));
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
        tag.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(
                TrapHouse.tableNote(house, TrapHouse.TOP_DROP)));
        return tag;
    }

    // --- dropping -------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (falling()) {
            return;
        }
        if (index == STAKE_SLOT) {
            stakeChoice = (stakeChoice + 1) % STAKES.length;
            click(1.4F);
            repaint();
            return;
        }
        if (index == DROP_SLOT) {
            drop();
        }
    }

    private void drop() {
        int stake = STAKES[stakeChoice];
        if (!TrapHouse.covers(house, stake, TrapHouse.TOP_DROP)) {
            deny();
            player.sendMessage(plain("The house won't take that -- the outside slots pay "
                    + TrapHouse.TOP_DROP + "x and the vault is too thin for it.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.wealthOf(player) < stake) {
            deny();
            player.sendMessage(plain("You can't cover a " + stake + "e drop.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        TrapHouse.stake(player, house, stake);

        // Eight honest coin flips. No table, no target, nothing decided first:
        // the flips ARE the outcome, which is why this machine's return needs
        // no tuning and cannot drift.
        path = new boolean[TrapMath.PLINKO_BOUNCES];
        var random = player.getWorld().getRandom();
        for (int step = 0; step < path.length; step++) {
            path[step] = random.nextBoolean();
        }
        bounce = 0;
        ticks = 0;
        landed = -1;
        celebrating = 0;
        TrapTables.watch(this);

        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.PLAYERS, 0.9F, 1.3F);
        repaint();
    }

    @Override
    public boolean tick() {
        flash++;
        if (closed) {
            return false;
        }

        if (celebrating > 0) {
            celebrating--;
            repaint();
            if (celebrating == 0) {
                announce();
                return false;
            }
            return true;
        }
        if (!falling()) {
            return false;
        }
        if (++ticks < BOUNCE_TICKS) {
            return true;
        }
        ticks = 0;
        bounce++;

        if (bounce <= TrapMath.PLINKO_BOUNCES) {
            // A peg strike, pitched by how far across the ball has drifted, so
            // the sound itself tells you it is heading for an edge.
            player.getWorld().playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS,
                    0.6F, 0.8F + ballColumn() * 0.12F);
            repaint();
            return true;
        }
        settle();
        celebrating = 24;
        return true;
    }

    private void settle() {
        landed = TrapMath.plinkoSlot(path);
        history.add(landed);
        int stake = STAKES[stakeChoice];
        won = Math.round(stake * TrapMath.PLINKO_PAYS[landed]);
        if (won > 0) {
            won = TrapHouse.payout(player, house, won);
        }

        var world = player.getWorld();
        if (landed == 0 || landed == TrapMath.PLINKO_SLOTS - 1) {
            TrapAwards.grant(player, "edge");
        }
        if (won >= STAKES[stakeChoice]) {
            TrapCasino.won(player, "drop");
        }
        if (won >= STAKES[stakeChoice] * 10) {
            TrapAwards.grant(player, "jackpot");
        }
        boolean big = TrapMath.PLINKO_PAYS[landed] >= 5.0f;
        if (big) {
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    player.getX(), player.getY() + 1.3, player.getZ(), 60, 0.5, 0.5, 0.5, 0.4);
            world.spawnParticles(ParticleTypes.FIREWORK,
                    player.getX(), player.getY() + 2.0, player.getZ(), 20, 0.7, 0.3, 0.7, 0.15);
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, 1.0F, 1.0F);
        } else {
            world.playSound(null, player.getBlockPos(),
                    won >= stake ? SoundEvents.BLOCK_NOTE_BLOCK_BELL.value()
                            : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.PLAYERS, 0.7F, won >= stake ? 1.5F : 0.7F);
        }
    }

    private void announce() {
        int stake = STAKES[stakeChoice];
        int net = won - stake;
        player.sendMessage(plain("It drops into ").formatted(Formatting.GRAY)
                .append(plain(multiplier(TrapMath.PLINKO_PAYS[landed]))
                        .formatted(net >= 0 ? Formatting.GOLD : Formatting.RED, Formatting.BOLD))
                .append(plain(".   ").formatted(Formatting.GRAY))
                .append(plain("+" + won + "e").formatted(Formatting.GREEN))
                .append(plain(net >= 0 ? "   net +" + net : "   net " + net)
                        .formatted(net >= 0 ? Formatting.DARK_GRAY : Formatting.RED)), false);
        landed = -1;
        path = new boolean[0];
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

    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
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
