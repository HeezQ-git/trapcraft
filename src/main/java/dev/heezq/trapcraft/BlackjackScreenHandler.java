package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
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
import java.util.Collections;
import java.util.List;

/**
 * Blackjack, dealt properly.
 *
 * The sixth machine and the only one where the odds move while you are sitting
 * at it. Everything else on this floor is a fixed bet you either take or don't;
 * here the dealer shows you one card, and what you do about it is the game.
 *
 * A real deck, shuffled every hand -- no shoe, so there is nothing to count and
 * no reason to pretend there is. Dealer stands on seventeen. You may hit, stand
 * or double down.
 *
 * Blackjack pays six to five, not the three to two a proper table pays. That
 * single change is what takes the house edge from about half a percent to about
 * one and a half, and it is exactly what real casinos did when they wanted more
 * money without touching a rule anybody reads. It belongs here.
 *
 *   rows 0-1  the dealer
 *   row 2     the state of play
 *   rows 3-4  you
 *   row 5     stake, hit, stand, double, purse
 */
public class BlackjackScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int FOOTER = SIZE - 9;

    private static final int DEALER_ROW = 0;
    private static final int STATE_SLOT = 22;
    private static final int PLAYER_ROW = 3;

    private static final int STAKE_SLOT = FOOTER;
    private static final int DEAL_SLOT = FOOTER + 2;
    private static final int HIT_SLOT = FOOTER + 4;
    private static final int STAND_SLOT = FOOTER + 5;
    private static final int DOUBLE_SLOT = FOOTER + 6;
    private static final int PURSE_SLOT = FOOTER + 8;

    private static final int[] STAKES = {8, 32, 128};
    private static final String[] RANKS = {
            "", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUITS = {"♠", "♥", "♦", "♣"};

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;
    private int stakeChoice = 0;

    private final List<Integer> deck = new ArrayList<>();
    private final int[] mine = new int[12];
    private final int[] theirs = new int[12];
    private final int[] mineSuit = new int[12];
    private final int[] theirsSuit = new int[12];
    private int mineCount;
    private int theirsCount;
    private boolean playing;
    private boolean showAll;
    private int staked;
    private String outcome = "";
    /** Whose money is on the other side of the table. Null means nobody's. */
    private final TrapHouse.House house;

    public BlackjackScreenHandler(int syncId, PlayerInventory playerInventory,
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
        paint();
    }

    // --- the table ------------------------------------------------------------

    private void paint() {
        ItemStack felt = new ItemStack(Items.GREEN_STAINED_GLASS_PANE);
        felt.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, felt.copy());
        }

        for (int i = 0; i < theirsCount && i < 9; i++) {
            // The hole card stays face down until the dealer plays it.
            boolean hidden = i == 1 && !showAll;
            display.setStack(DEALER_ROW * 9 + i,
                    hidden ? faceDown() : card(theirs[i], theirsSuit[i]));
        }
        for (int i = 0; i < mineCount && i < 9; i++) {
            display.setStack(PLAYER_ROW * 9 + i, card(mine[i], mineSuit[i]));
        }
        display.setStack(STATE_SLOT, stateTag());

        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(DEAL_SLOT, dealTag());
        display.setStack(HIT_SLOT, actionTag("HIT", "Take another card.",
                Items.LIME_STAINED_GLASS_PANE, playing));
        display.setStack(STAND_SLOT, actionTag("STAND", "That'll do. Dealer plays.",
                Items.ORANGE_STAINED_GLASS_PANE, playing));
        display.setStack(DOUBLE_SLOT, actionTag("DOUBLE",
                "Double the stake, take exactly one more card.",
                Items.GOLD_BLOCK, playing && mineCount == 2
                        && TrapMarket.wealthOf(player) >= staked));
        display.setStack(PURSE_SLOT, purseTag());
        sendContentUpdates();
    }

    private ItemStack card(int rank, int suit) {
        boolean red = suit == 1 || suit == 2;
        ItemStack tag = new ItemStack(red ? Items.PINK_STAINED_GLASS_PANE
                : Items.WHITE_STAINED_GLASS_PANE);
        tag.setCount(Math.max(1, Math.min(10, rank)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(RANKS[rank] + SUITS[suit])
                        .formatted(red ? Formatting.RED : Formatting.DARK_GRAY, Formatting.BOLD));
        return tag;
    }

    private ItemStack faceDown() {
        ItemStack tag = new ItemStack(Items.BLUE_STAINED_GLASS_PANE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("?").formatted(Formatting.DARK_GRAY, Formatting.BOLD));
        return tag;
    }

    private ItemStack stateTag() {
        int mineTotal = TrapMath.handValue(mine, mineCount);
        int theirsTotal = showAll ? TrapMath.handValue(theirs, theirsCount)
                : theirsCount > 0 ? TrapMath.handValue(theirs, 1) : 0;

        ItemStack tag = new ItemStack(playing ? Items.PAPER
                : outcome.isEmpty() ? Items.BOOK : Items.GOLD_INGOT);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(playing || !outcome.isEmpty() ? "You " + mineTotal : "Blackjack")
                        .formatted(mineTotal > 21 ? Formatting.RED : Formatting.WHITE,
                                Formatting.BOLD));

        List<Text> lore = new ArrayList<>();
        if (theirsCount > 0) {
            lore.add(line("Dealer " + theirsTotal + (showAll ? "" : " showing"),
                    Formatting.GRAY));
        }
        if (!outcome.isEmpty()) {
            lore.add(Text.empty());
            lore.add(line(outcome, Formatting.GOLD, Formatting.BOLD));
        }
        if (!playing && outcome.isEmpty()) {
            lore.add(line("Dealer stands on " + TrapMath.DEALER_STANDS + ".", Formatting.GRAY));
            lore.add(line("Blackjack pays "
                    + String.format("%.1f", TrapMath.BLACKJACK_PAY) + "x.", Formatting.GRAY));
            lore.add(Text.empty());
            lore.add(line("Six to five, not three to two.", Formatting.DARK_GRAY));
            lore.add(line("Yes, that's worse. It's that kind of place.",
                    Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack actionTag(String name, String blurb,
                                net.minecraft.item.Item icon, boolean live) {
        ItemStack tag = new ItemStack(live ? icon : Items.GRAY_STAINED_GLASS_PANE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(name).formatted(live ? Formatting.WHITE : Formatting.DARK_GRAY,
                        Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(live ? blurb : "Not now.",
                        live ? Formatting.GRAY : Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack dealTag() {
        ItemStack tag = new ItemStack(playing ? Items.GRAY_DYE : Items.LEVER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(playing ? "In hand" : "DEAL")
                        .formatted(playing ? Formatting.DARK_GRAY : Formatting.GOLD,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(playing ? "Finish this one first."
                        : "Fresh deck, two cards each.", Formatting.GRAY))));
        return tag;
    }

    private ItemStack stakeTag() {
        ItemStack tag = new ItemStack(Items.EMERALD,
                Math.max(1, Math.min(64, STAKES[stakeChoice] / 8)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stake: ").formatted(Formatting.GRAY)
                        .append(plain((playing ? staked : STAKES[stakeChoice]) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(playing ? "Locked for this hand." : "Click to change.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack purseTag() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Purse: ").formatted(Formatting.GRAY)
                        .append(plain(TrapMarket.wealthOf(player) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(
                TrapHouse.tableNote(house, TrapHouse.TOP_BLACKJACK)));
        return tag;
    }

    // --- playing --------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == STAKE_SLOT && !playing) {
            stakeChoice = (stakeChoice + 1) % STAKES.length;
            click(1.4F);
            paint();
            return;
        }
        if (index == DEAL_SLOT && !playing) {
            deal();
            return;
        }
        if (!playing) {
            return;
        }
        switch (index) {
            case HIT_SLOT -> hit();
            case STAND_SLOT -> stand();
            case DOUBLE_SLOT -> doubleDown();
            default -> {
            }
        }
    }

    /**
     * A fresh 52-card deck every hand.
     *
     * No shoe on purpose. A persistent shoe would make counting cards a real
     * strategy, and a card counter in a game with no other players is somebody
     * quietly turning the house edge negative in a corner.
     */
    private void deal() {
        int stake = STAKES[stakeChoice];
        // Checked at five times the stake, not two: a hand can be doubled, and
        // a table that lets you double and then can't pay is worse than one
        // that never dealt the hand.
        if (!TrapHouse.covers(house, stake, TrapHouse.TOP_BLACKJACK)) {
            deny();
            player.sendMessage(plain("The house won't deal that -- not enough behind "
                    + "the table to cover a doubled hand.").formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.wealthOf(player) < stake) {
            deny();
            player.sendMessage(plain("You can't cover a " + stake + "e hand.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        TrapHouse.stake(player, house, stake);
        staked = stake;

        deck.clear();
        for (int suit = 0; suit < 4; suit++) {
            for (int rank = 1; rank <= 13; rank++) {
                deck.add(suit * 13 + rank);
            }
        }
        Collections.shuffle(deck, new java.util.Random(
                player.getWorld().getRandom().nextLong()));

        mineCount = 0;
        theirsCount = 0;
        outcome = "";
        showAll = false;
        playing = true;
        drawTo(true);
        drawTo(false);
        drawTo(true);
        drawTo(false);

        sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.2F);
        if (TrapMath.isBlackjack(mine, mineCount)) {
            stand();   // nothing to decide on a natural
            return;
        }
        paint();
    }

    private void drawTo(boolean toMe) {
        int card = deck.remove(deck.size() - 1);
        int rank = (card - 1) % 13 + 1;
        int suit = (card - 1) / 13;
        if (toMe) {
            mine[mineCount] = rank;
            mineSuit[mineCount] = suit;
            mineCount++;
        } else {
            theirs[theirsCount] = rank;
            theirsSuit[theirsCount] = suit;
            theirsCount++;
        }
    }

    private void hit() {
        drawTo(true);
        sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.0F);
        if (TrapMath.handValue(mine, mineCount) > 21) {
            finish();
            return;
        }
        paint();
    }

    private void doubleDown() {
        if (mineCount != 2 || TrapMarket.wealthOf(player) < staked) {
            deny();
            return;
        }
        TrapHouse.stake(player, house, staked);
        staked *= 2;
        drawTo(true);
        sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.0F);
        stand();
    }

    /** Dealer turns the hole card and plays it out. */
    private void stand() {
        showAll = true;
        while (TrapMath.dealerHits(theirs, theirsCount) && theirsCount < theirs.length - 1) {
            drawTo(false);
        }
        finish();
    }

    private void finish() {
        playing = false;
        int me = TrapMath.handValue(mine, mineCount);
        int them = TrapMath.handValue(theirs, theirsCount);
        boolean myNatural = TrapMath.isBlackjack(mine, mineCount);
        boolean theirNatural = TrapMath.isBlackjack(theirs, theirsCount);
        showAll = true;

        int paid;
        if (me > 21) {
            outcome = "Bust.";
            paid = 0;
        } else if (myNatural && !theirNatural) {
            outcome = "Blackjack.";
            paid = Math.round(staked * TrapMath.BLACKJACK_PAY);
        } else if (theirNatural && !myNatural) {
            outcome = "Dealer had it.";
            paid = 0;
        } else if (them > 21) {
            outcome = "Dealer bust.";
            paid = staked * 2;
        } else if (me > them) {
            outcome = "You take it.";
            paid = staked * 2;
        } else if (me == them) {
            outcome = "Push.";
            paid = staked;
        } else {
            outcome = "Dealer takes it.";
            paid = 0;
        }

        if (paid > 0) {
            if (paid == staked) {
                TrapHouse.refund(player, house, paid);
            } else {
                paid = TrapHouse.payout(player, house, paid);
            }
        }
        if (paid > staked) {
            TrapCasino.won(player, "blackjack");
            if (myNatural) {
                TrapAwards.grant(player, "natural");
            }
        }

        var world = player.getWorld();
        if (paid > staked) {
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    player.getX(), player.getY() + 1.2, player.getZ(), 14, 0.4, 0.4, 0.4, 0.04);
            sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.5F);
        } else if (paid == 0) {
            sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS, 0.6F);
        }

        int net = paid - staked;
        player.sendMessage(plain(outcome + "  ").formatted(
                        net > 0 ? Formatting.GREEN : net == 0 ? Formatting.GRAY : Formatting.RED,
                        Formatting.BOLD)
                .append(plain("You " + me + ", dealer " + them + ".   ")
                        .formatted(Formatting.GRAY))
                .append(plain(net >= 0 ? "+" + net + "e" : net + "e")
                        .formatted(net >= 0 ? Formatting.GREEN : Formatting.RED)), false);
        paint();
    }

    // --- trimmings ------------------------------------------------------------

    private void sound(net.minecraft.sound.SoundEvent event, float pitch) {
        player.getWorld().playSound(null, player.getBlockPos(), event,
                SoundCategory.PLAYERS, 0.7F, pitch);
    }

    private void sound(net.minecraft.registry.entry.RegistryEntry<net.minecraft.sound.SoundEvent> event,
                       float pitch) {
        sound(event.value(), pitch);
    }

    private void click(float pitch) {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5F, pitch);
    }

    private void deny() {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }

    /**
     * Walk away mid-hand and it's a push.
     *
     * The stake came out of your pocket when the cards were dealt, and every
     * other machine on this floor hands back what it hasn't resolved. A real
     * table would keep it; a table nobody trusts is a table nobody sits at.
     */
    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        if (playing) {
            TrapHouse.refund(player, house, staked);
            playing = false;
        }
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == player;
    }

    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
        return ItemStack.EMPTY;
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
