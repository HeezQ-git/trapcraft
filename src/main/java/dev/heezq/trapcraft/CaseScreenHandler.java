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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The reel, which is the whole reason anybody opens a case by hand.
 *
 * A strip of skins scrolls left under a fixed marker and slows to a stop on
 * one of them. That is Counter-Strike's animation and it survives the port
 * intact, because a chest GUI row is nine slots wide and a reel needs exactly
 * one row and a pointer.
 *
 * <h2>The reel is theatre, and says so</h2>
 *
 * The prize is drawn in the constructor, before a single face is chosen, and
 * the strip is then built with the winner planted where the marker will be
 * when the reel runs out of steps. Nothing that happens during the animation
 * can change it -- the same rule the slot machine works under, for the same
 * reason: it makes the odds a number somebody can check ({@code CaseOddsTest})
 * rather than an emergent property of a scroll.
 *
 * The rest of the strip is drawn from the SAME weighted table, so the tape you
 * watch go past is an honest sample of the case. A reel padded with golds to
 * look exciting is a reel that lies about the odds, and players read tape.
 */
public class CaseScreenHandler extends ScreenHandler implements TrapTables.Playing {

    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    /** The reel row. */
    private static final int WINDOW = 9;
    private static final int REEL_ROW = 9;
    private static final int CASE_SLOT = 0;
    private static final int ODDS_SLOT = 8;

    /** Which slot of the window the winner lands under. Dead centre. */
    private static final int POINTER = 4;

    /**
     * The marker above and below the pointed-at slot.
     *
     * Derived from {@link #POINTER} rather than written as 4 and 22, because
     * the two disagreeing is the worst bug this screen can have: the reel
     * would stop with the arrows over one skin and hand over the one next to
     * it, which is indistinguishable from the server cheating.
     */
    private static final int MARKER_TOP = POINTER;
    private static final int MARKER_BOTTOM = 18 + POINTER;

    /** Faces the tape travels before it stops. */
    private static final int STEPS = 40;

    /** How long the result sits on screen before the window is done. */
    private static final int HOLD_TICKS = 50;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;
    private final CaseOdds.Tier tier;

    private final CaseOdds.Reward prize;
    private final CaseOdds.Grade grade;

    /** The tape. {@code head} is the strip index drawn in window slot 0. */
    private final List<ItemStack> strip = new ArrayList<>();
    private final List<CaseOdds.Grade> stripGrades = new ArrayList<>();
    private int head;
    private int stepsLeft = STEPS;
    private int cooldown;

    private int holding;
    private int flash;
    private boolean settled;
    private boolean closed;

    public CaseScreenHandler(int syncId, PlayerInventory playerInventory, CaseOdds.Tier tier) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.player = (ServerPlayerEntity) playerInventory.player;
        this.tier = tier;

        this.prize = TrapCases.roll(this.player.getRandom(), tier);
        this.grade = TrapCases.gradeOf(tier, prize);
        buildStrip();

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

        TrapTables.watch(this);
        repaint();
    }

    /**
     * Lay the tape out with the winner where the marker will be.
     *
     * The window shows nine faces and the marker sits over the fifth, so after
     * {@link #STEPS} steps the strip index under it is {@code STEPS + POINTER}
     * -- put the prize there and the animation cannot land anywhere else. The
     * strip runs nine past that so the last frame is a full window rather than
     * four faces and a hole.
     */
    private void buildStrip() {
        var random = player.getRandom();
        int winner = STEPS + POINTER;
        for (int index = 0; index < winner + WINDOW; index++) {
            if (index == winner) {
                strip.add(TrapCases.faceOf(prize, grade));
                stripGrades.add(grade);
                continue;
            }
            CaseOdds.Reward filler = TrapCases.roll(random, tier);
            CaseOdds.Grade fillerGrade = TrapCases.gradeOf(tier, filler);
            strip.add(TrapCases.faceOf(filler, fillerGrade));
            stripGrades.add(fillerGrade);
        }
    }

    // --- painting -------------------------------------------------------------

    private void repaint() {
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, pane(index));
        }
        for (int cell = 0; cell < WINDOW; cell++) {
            display.setStack(REEL_ROW + cell, strip.get(head + cell));
        }
        display.setStack(CASE_SLOT, caseTag());
        display.setStack(ODDS_SLOT, oddsTag());
        sendContentUpdates();
    }

    /**
     * The surround.
     *
     * The two markers wear the grade colour ONLY once the tape has stopped.
     * Colouring them while it runs would tell you what you won several seconds
     * before the reel admits it, which is the one thing this animation must
     * not do.
     */
    private ItemStack pane(int index) {
        if (index == MARKER_TOP || index == MARKER_BOTTOM) {
            Item marker = stepsLeft > 0
                    ? Items.YELLOW_STAINED_GLASS_PANE
                    : paneOf(grade);
            ItemStack tag = new ItemStack(marker);
            tag.set(DataComponentTypes.CUSTOM_NAME, stepsLeft > 0
                    ? TrapCases.plain("...").formatted(Formatting.GRAY)
                    : TrapCases.plain(grade.title())
                            .formatted(TrapCases.colour(grade), Formatting.BOLD));
            return tag;
        }
        ItemStack blank = new ItemStack(stepsLeft > 0 && (index + flash / 3) % 2 == 0
                ? Items.GRAY_STAINED_GLASS_PANE : Items.BLACK_STAINED_GLASS_PANE);
        blank.set(DataComponentTypes.CUSTOM_NAME, Text.empty());
        return blank;
    }

    private static Item paneOf(CaseOdds.Grade grade) {
        return switch (grade) {
            case MIL_SPEC -> Items.BLUE_STAINED_GLASS_PANE;
            case RESTRICTED -> Items.PURPLE_STAINED_GLASS_PANE;
            case CLASSIFIED -> Items.MAGENTA_STAINED_GLASS_PANE;
            case COVERT -> Items.RED_STAINED_GLASS_PANE;
            case EXOTIC -> Items.YELLOW_STAINED_GLASS_PANE;
        };
    }

    private ItemStack caseTag() {
        ItemStack tag = new ItemStack(TrapContent.CASES.get(tier));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                TrapCases.name(tier.caseKey()).formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                TrapCases.plain("Klucz zużyty.").formatted(Formatting.DARK_GRAY),
                TrapCases.plain(stepsLeft > 0 ? "Kręci się..." : "Twoje.")
                        .formatted(Formatting.GRAY))));
        return tag;
    }

    /** The paytable, stated up front the way the game this copies states it. */
    private ItemStack oddsTag() {
        ItemStack tag = new ItemStack(Items.PAPER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                TrapCases.plain("Szanse").formatted(Formatting.WHITE, Formatting.BOLD));
        List<Text> lines = new ArrayList<>();
        for (CaseOdds.Grade band : CaseOdds.Grade.values()) {
            lines.add(TrapCases.plain(band.title() + "  " + band.chance())
                    .formatted(TrapCases.colour(band)));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return tag;
    }

    // --- the spin -------------------------------------------------------------

    /**
     * How long to wait before the next face slides past.
     *
     * Five bands rather than a curve, because the eye reads deceleration as
     * "it might stop here" and only needs about four distinct speeds to feel
     * it. Roughly three and a half seconds end to end, which is the length of
     * the animation being imitated.
     */
    private static int gapFor(int left) {
        if (left > 12) {
            return 1;
        }
        if (left > 6) {
            return 2;
        }
        if (left > 3) {
            return 4;
        }
        return left > 1 ? 6 : 9;
    }

    @Override
    public boolean tick() {
        flash++;
        if (closed) {
            return false;
        }
        if (stepsLeft <= 0) {
            if (holding > 0) {
                holding--;
                if (grade == CaseOdds.Grade.COVERT || grade == CaseOdds.Grade.EXOTIC) {
                    sparkle();
                }
                return true;
            }
            return false;
        }
        if (--cooldown > 0) {
            repaint();
            return true;
        }

        head++;
        stepsLeft--;
        cooldown = gapFor(stepsLeft);
        // A click per face, pitched by what just went past: the ear learns the
        // rare bands before the eye does, and a reel that ticks a semitone
        // higher as something purple slides by is most of the tension.
        player.playSoundToPlayer(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS,
                0.35F, 1.0F + stripGrades.get(head + POINTER).ordinal() * 0.18F);
        repaint();

        if (stepsLeft == 0) {
            land();
        }
        return true;
    }

    /** Pay, celebrate, and tell the room if it was worth telling. */
    private void land() {
        holding = HOLD_TICKS;
        settle();
        repaint();

        float pitch = 0.8F + grade.ordinal() * 0.25F;
        player.playSoundToPlayer(grade.ordinal() >= CaseOdds.Grade.COVERT.ordinal()
                        ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
                        : SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                SoundCategory.PLAYERS, 1.0F, pitch);
        player.sendMessage(TrapCases.plain("Wypadło  ").formatted(Formatting.GRAY)
                .append(TrapCases.plain(prize.label())
                        .formatted(TrapCases.colour(grade), Formatting.BOLD))
                .append(TrapCases.plain("  (" + grade.title() + ", ~" + prize.worth() + "e)")
                        .formatted(Formatting.DARK_GRAY)), false);
        TrapCases.announce(player, tier, prize, grade);
    }

    /** A ring of light for the two bands that deserve one. */
    private void sparkle() {
        var world = player.getWorld();
        double angle = (HOLD_TICKS - holding) * 0.4;
        double radius = 0.9;
        world.spawnParticles(
                grade == CaseOdds.Grade.EXOTIC ? ParticleTypes.TOTEM_OF_UNDYING
                        : ParticleTypes.FLAME,
                player.getX() + Math.cos(angle) * radius,
                player.getY() + 1.0,
                player.getZ() + Math.sin(angle) * radius,
                4, 0.15, 0.25, 0.15, 0.02);
    }

    /**
     * Hand the prize over, exactly once.
     *
     * Called from the landing AND from the close, because the case and key
     * were already spent when the window opened: somebody who alt-tabs or
     * closes on the last click has paid and is owed the item. The flag is what
     * stops them being owed it twice.
     */
    private void settle() {
        if (settled) {
            return;
        }
        settled = true;
        TrapCases.grant(player, prize);
    }

    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        closed = true;
        if (!settled) {
            settle();
            player.sendMessage(TrapCases.plain("Skrzynka otwarta bez patrzenia: ")
                    .formatted(Formatting.GRAY)
                    .append(TrapCases.plain(prize.label())
                            .formatted(TrapCases.colour(grade), Formatting.BOLD)), false);
            TrapCases.announce(player, tier, prize, grade);
        }
    }

    // --- a window, not a container -------------------------------------------

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                            PlayerEntity who) {
        // Nothing in here is clickable, including the player's own row: a
        // drag out of the inventory while the reel runs is an item on the
        // floor and a confused player.
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
