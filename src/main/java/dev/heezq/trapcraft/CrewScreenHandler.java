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
import net.minecraft.registry.Registries;
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
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The crew board: who works for you, and what you can teach them.
 *
 * A screen rather than more chat lines, because everything worth doing to a
 * hand is a purchase and a purchase wants a thing to click. It is also the
 * only honest way to show a ladder: "Pace 2 of 4, next rung 320e" is one item
 * with lore, and three paragraphs of tellraw pretending to be one.
 *
 *   [hand][hand][hand][hand][hand] . [book] . [hire]
 *   [pace][reach] [job][job][job][job][job][job][job]
 *   [job][job][whip][move][wages] . [plans] . [fire]
 *
 * The selected hand is the one everything on the bottom two rows applies to,
 * which is why the top row is heads you click rather than a list you read.
 *
 * The book used to sit in the fifth head's slot, which was fine right up until
 * somebody hired a fifth hand: the head was painted, then painted over, and
 * the slot stayed clickable -- so hand five existed, worked, took a wage, and
 * could only be selected by clicking a book.
 */
public class CrewScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int HELP_SLOT = 6;
    private static final int HIRE_SLOT = 8;
    private static final int PACE_SLOT = 9;
    private static final int REACH_SLOT = 10;
    private static final int JOBS_FROM = 11;
    private static final int WHIP_SLOT = 20;
    private static final int MOVE_SLOT = 21;
    private static final int PLANS_SLOT = 24;
    private static final int WAGES_SLOT = 22;
    private static final int FIRE_SLOT = 26;

    /**
     * Every job, Picking included.
     *
     * Picking is free but no longer automatic: it takes one of the two slots
     * like anything else, so it has to be a thing you choose. A hand who does
     * not pick is a perfectly good hand -- a presser and refiner never touches
     * a plant.
     */
    private static final List<TrapCrew.Job> TEACHABLE =
            List.of(TrapCrew.Job.values());

    static {
        // The job row is laid out by counting off JOBS_FROM, so a tenth job
        // would land on the whip and be eaten by the click handler without a
        // word. Better to fall over the first time somebody opens the board.
        if (JOBS_FROM + TEACHABLE.size() > WHIP_SLOT) {
            throw new IllegalStateException(
                    "crew board: " + TEACHABLE.size() + " jobs won't fit before the whip");
        }
    }

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity boss;
    private List<TrapCrew.Card> crew = List.of();
    private int selected = 0;

    public CrewScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.boss = (ServerPlayerEntity) playerInventory.player;

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

    // --- drawing --------------------------------------------------------------

    private void paint() {
        crew = TrapCrew.cardsFor(boss);
        if (selected >= crew.size()) {
            selected = Math.max(0, crew.size() - 1);
        }

        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        for (int i = 0; i < crew.size() && i < TrapCrew.MAX_HANDS; i++) {
            display.setStack(i, head(crew.get(i), i, i == selected));
        }
        display.setStack(HELP_SLOT, help());
        display.setStack(HIRE_SLOT, hireTag());

        if (!crew.isEmpty()) {
            TrapCrew.Card card = crew.get(selected);
            display.setStack(PACE_SLOT, ladder(card, true));
            display.setStack(REACH_SLOT, ladder(card, false));
            for (int i = 0; i < TEACHABLE.size(); i++) {
                display.setStack(JOBS_FROM + i, jobTag(card, TEACHABLE.get(i)));
            }
            display.setStack(WHIP_SLOT, whipTag(card));
            display.setStack(MOVE_SLOT, moveTag(card));
            display.setStack(PLANS_SLOT, plansTag());
            display.setStack(WAGES_SLOT, wages());
            display.setStack(FIRE_SLOT, fireTag(selected));
        }
        sendContentUpdates();
    }

    /**
     * @param nth which of THIS player's hands, not which of everybody's.
     *            Card.index() is a position in the server-wide crew list, so
     *            numbering the heads off it labelled the second player's first
     *            hand "Hand 3".
     */
    private ItemStack head(TrapCrew.Card card, int nth, boolean chosen) {
        ItemStack tag = new ItemStack(chosen ? Items.VILLAGER_SPAWN_EGG : Items.PLAYER_HEAD);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Hand " + (nth + 1))
                        .formatted(chosen ? Formatting.YELLOW : Formatting.WHITE, Formatting.BOLD)
                        .append(plain(chosen ? "  <" : "").formatted(Formatting.GOLD)));
        List<Text> lore = new ArrayList<>();
        lore.add(line(TrapCrew.PACE_NAME[card.pace()] + " -- a job every "
                + card.tempo(), Formatting.GRAY));
        lore.add(line("Works " + card.reachBlocks() + " blocks around  ", Formatting.GRAY)
                .append(plain(card.spot()).formatted(Formatting.WHITE)));
        // The single most misunderstood thing about the crew: a hand uses ONE
        // container, the nearest one to its spot, and nothing else in the
        // world. Somebody with the right things in the wrong chest was doing
        // everything right and getting nothing.
        lore.add(card.chest() == null
                ? line("NO CHEST in the patch. They can't work.", Formatting.RED)
                : line("Uses the chest at  ", Formatting.DARK_GRAY)
                .append(plain(card.chest()).formatted(Formatting.WHITE))
                .append(plain("  (nearest one)").formatted(Formatting.DARK_GRAY)));
        lore.add(line("Wages  ", Formatting.DARK_GRAY)
                .append(plain(card.wage() + "e").formatted(Formatting.RED))
                .append(plain(" every five minutes ON THE CLOCK")
                        .formatted(Formatting.DARK_GRAY)));
        lore.add(line("Nights are free. They're asleep.", Formatting.DARK_GRAY));
        // The books, which exist because "are they earning their keep" was a
        // question three people had and nobody could answer.
        if (card.done() > 0) {
            lore.add(line("Done " + card.done() + " jobs for " + card.paid() + "e  ",
                    Formatting.DARK_GRAY)
                    .append(plain(String.format("%.1fe a job", card.perJob()))
                            .formatted(Formatting.WHITE))
                    .append(plain(String.format("  (best %.1fe)", card.parJob()))
                            .formatted(Formatting.DARK_GRAY)));
            if (card.perJob() > card.parJob() * 1.6f) {
                lore.add(line("Most of that is walking. Tighter patch,",
                        Formatting.YELLOW));
                lore.add(line("or a chest closer to the work.", Formatting.YELLOW));
            }
        }
        if (card.missed() > 0) {
            lore.add(line("OWED " + card.owed() + "e -- "
                    + (TrapCrew.GRACE_PACKETS - card.missed())
                    + " paydays before they walk", Formatting.RED, Formatting.BOLD));
        }
        lore.add(Text.empty());
        StringBuilder knows = new StringBuilder();
        for (TrapCrew.Job job : card.taught()) {
            knows.append(knows.isEmpty() ? "" : ", ").append(job.display());
        }
        lore.add(line("Knows " + card.taught().size() + " of " + TrapCrew.SLOTS
                + (knows.isEmpty() ? " -- nothing yet" : ": " + knows),
                card.taught().isEmpty() ? Formatting.RED : Formatting.WHITE));
        lore.add(Text.empty());
        // "Present" is worth a line of its own: a hand who isn't there does no
        // work and takes no wages, and from the outside that is
        // indistinguishable from one that is simply being lazy. It used to
        // also mean "you are stood too far away", which it no longer can --
        // the patch holds itself open now, so this is a zombie or nothing.
        lore.add(card.present()
                ? line("On the patch.", Formatting.GREEN)
                : line("Gone. Something got them -- whip a new one in.",
                Formatting.RED));
        lore.add(line(chosen ? "Selected." : "Click to select.",
                chosen ? Formatting.DARK_GRAY : Formatting.YELLOW));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack ladder(TrapCrew.Card card, boolean pace) {
        int rung = pace ? card.pace() : card.reach();
        int rungs = (pace ? TrapCrew.PACE_TICKS : TrapCrew.REACH_BLOCKS).length;
        boolean top = rung >= rungs - 1;
        int cost = top ? 0 : (pace ? TrapCrew.PACE_COST : TrapCrew.REACH_COST)[rung + 1];
        boolean can = !top && TrapMarket.wealthOf(boss) >= cost;

        ItemStack tag = new ItemStack(top ? Items.GOLD_INGOT
                : can ? (pace ? Items.SUGAR : Items.SPYGLASS) : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(pace ? "Pace" : "Patch")
                        .formatted(top ? Formatting.GOLD : can ? Formatting.AQUA
                                : Formatting.DARK_GRAY, Formatting.BOLD)
                        .append(plain("  " + (rung + 1) + " of " + rungs)
                                .formatted(Formatting.WHITE)));
        List<Text> lore = new ArrayList<>();
        lore.add(pace
                ? line("Now: a job every " + card.tempo() + ".", Formatting.GRAY)
                : line("Now: " + card.reachBlocks() + " blocks around the spot.",
                Formatting.GRAY));
        if (top) {
            lore.add(line("Top of the ladder.", Formatting.GOLD));
        } else {
            lore.add(pace
                    ? line("Next: every " + TrapCrew.paceLabel(rung + 1)
                    + ", and they walk quicker.", Formatting.WHITE)
                    : line("Next: " + TrapCrew.REACH_BLOCKS[rung + 1] + " blocks.",
                    Formatting.WHITE));
            lore.add(Text.empty());
            lore.add(line(cost + "e", Formatting.GOLD)
                    .append(plain(", and wages go to " + (card.wage()
                            + (pace ? TrapCrew.PACE_WAGE[rung + 1] - TrapCrew.PACE_WAGE[rung]
                            : TrapCrew.REACH_WAGE[rung + 1] - TrapCrew.REACH_WAGE[rung]))
                            + "e.").formatted(Formatting.DARK_GRAY)));
            lore.add(line(can ? "Click to buy." : "You can't cover it.",
                    can ? Formatting.YELLOW : Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack jobTag(TrapCrew.Card card, TrapCrew.Job job) {
        boolean known = card.taught().contains(job);
        boolean full = card.taught().size() >= TrapCrew.SLOTS;
        boolean can = !known && !full && TrapMarket.wealthOf(boss) >= job.cost();
        ItemStack tag = new ItemStack(known ? icon(job) : can ? icon(job) : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(job.display()).formatted(known ? Formatting.GREEN
                        : can ? Formatting.WHITE : Formatting.DARK_GRAY, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line(job.blurb(), Formatting.GRAY));
        lore.add(Text.empty());
        if (known) {
            lore.add(line("Taught.", Formatting.GREEN)
                    .append(plain("  +" + job.wage() + "e on the wage.")
                            .formatted(Formatting.DARK_GRAY)));
            // The line that would have saved somebody asking why their roller
            // never rolled. A job with nothing to work on looks identical to a
            // job that is broken, and only one of them is your fault.
            lore.add(line("Wants " + job.needs() + ".", Formatting.GRAY));
            if (card.starved().contains(job)) {
                lore.add(line("NOT RIGHT NOW -- the chest hasn't got it.",
                        Formatting.RED, Formatting.BOLD));
                lore.add(line("It only ever looks in ONE chest: the", Formatting.RED));
                lore.add(line("nearest one to their spot.", Formatting.RED));
            } else {
                lore.add(line("Ready. The chest can back it.", Formatting.GREEN));
            }
            lore.add(line("Shift-click to drop it.", Formatting.YELLOW));
        } else {
            lore.add(line(job.cost() == 0 ? "Free." : job.cost() + "e", Formatting.GOLD)
                    .append(plain(job.wage() == 0 ? ", and no wage."
                                    : ", then +" + job.wage() + "e every packet.")
                            .formatted(Formatting.DARK_GRAY)));
            lore.add(line(full ? "Both slots are taken. Drop one first."
                            : can ? "Click to teach them." : "You can't cover it.",
                    can ? Formatting.YELLOW : Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private static Item icon(TrapCrew.Job job) {
        Identifier id = Identifier.tryParse(job.iconId());
        return id == null ? Items.PAPER : Registries.ITEM.getOptionalValue(id).orElse(Items.PAPER);
    }

    private ItemStack help() {
        ItemStack tag = new ItemStack(Items.BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("The Crew").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Stand where you want somebody working", Formatting.GRAY),
                line("and run /crew hire. They work a box", Formatting.GRAY),
                line("around that spot and put everything", Formatting.GRAY),
                line("in the nearest chest to it.", Formatting.GRAY),
                line("Each one has their own. Move it with", Formatting.GRAY),
                line("the compass, from wherever you stand.", Formatting.GRAY),
                Text.empty(),
                line("TWO JOBS EACH. Want a third thing", Formatting.WHITE),
                line("done? Hire a third person.", Formatting.WHITE),
                line("Teaching costs up front AND puts", Formatting.WHITE),
                line("the wage up for good.", Formatting.WHITE),
                Text.empty(),
                line("They keep working while you're", Formatting.GRAY),
                line("elsewhere, as long as you're", Formatting.GRAY),
                line("logged in. Log off and so do they.", Formatting.GRAY),
                Text.empty(),
                line("Miss a wage packet and you get a", Formatting.DARK_GRAY),
                line("notice. Miss " + TrapCrew.GRACE_PACKETS + " and they walk --", Formatting.DARK_GRAY),
                line("but the crew is saved on the way out.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack hireTag() {
        boolean room = crew.size() < TrapCrew.MAX_HANDS;
        boolean can = room && TrapMarket.wealthOf(boss) >= TrapCrew.HIRE_COST;
        ItemStack tag = new ItemStack(can ? Items.EMERALD : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Take somebody on").formatted(can ? Formatting.GREEN
                        : Formatting.DARK_GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(TrapCrew.HIRE_COST + "e, then " + TrapCrew.WAGE
                        + "e every five minutes.", Formatting.GRAY),
                line(crew.size() + " of " + TrapCrew.MAX_HANDS + " on the books.",
                        Formatting.DARK_GRAY),
                Text.empty(),
                line(!room ? "Your books are full."
                                : can ? "Click to hire them where YOU are stood."
                                : "You can't cover it.",
                        can ? Formatting.YELLOW : Formatting.DARK_GRAY))));
        return tag;
    }

    /**
     * One button for "they aren't working and I don't care why".
     *
     * A lead is the closest thing the game has to a picture of a whip, and it
     * reads at a glance in a row that is otherwise crops and tools.
     */
    private ItemStack whipTag(TrapCrew.Card card) {
        boolean gone = !card.present();
        ItemStack tag = new ItemStack(Items.LEAD);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Whip them back").formatted(gone ? Formatting.RED : Formatting.YELLOW,
                        Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Drags them to the spot and ends", Formatting.GRAY));
        lore.add(line("whatever break they were on.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(gone
                ? line("This one is gone. Clicking puts", Formatting.RED)
                : line("For when they've got stuck behind", Formatting.DARK_GRAY));
        lore.add(gone
                ? line("somebody new on the patch, trained.", Formatting.RED)
                : line("a wall or wandered off.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Free. Click as often as you like.", Formatting.YELLOW));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /**
     * Where the saved crews live, and why they are commands and not buttons.
     *
     * A plan has a NAME, and a chest screen has no way to type one. Rather
     * than invent a naming scheme nobody asked for -- "Crew 3" -- the naming
     * stays in chat and this slot is the sign that says so.
     */
    private ItemStack plansTag() {
        int saved = TrapCrew.plansOf(boss).size();
        ItemStack tag = new ItemStack(saved > 0 ? Items.WRITTEN_BOOK : Items.WRITABLE_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Crews on file").formatted(Formatting.AQUA, Formatting.BOLD)
                        .append(plain(saved == 0 ? "" : "  " + saved)
                                .formatted(Formatting.WHITE)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Write down who works where and what", Formatting.GRAY),
                line("they know, then buy the lot back later.", Formatting.GRAY),
                Text.empty(),
                line("/crew save <name>", Formatting.GREEN),
                line("/crew plans", Formatting.GREEN),
                line("/crew load <name>", Formatting.GREEN),
                line("/crew forget <name>", Formatting.DARK_GRAY),
                Text.empty(),
                line("If they ever walk over wages, the crew", Formatting.DARK_GRAY),
                line("is filed under \"" + TrapCrew.WALKOUT + "\" on its way out.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    /**
     * Send this one somewhere else.
     *
     * Wherever the player is standing when they click, which is why /crew
     * opens from anywhere: walk to the new field, open the board, click. No
     * coordinates to type and no wand to lose.
     */
    private ItemStack moveTag(TrapCrew.Card card) {
        boolean here = boss.getBlockPos().getX() == card.x()
                && boss.getBlockPos().getY() == card.y()
                && boss.getBlockPos().getZ() == card.z();
        ItemStack tag = new ItemStack(here ? Items.GRAY_DYE : Items.COMPASS);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Work here instead").formatted(here ? Formatting.DARK_GRAY
                        : Formatting.AQUA, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Their spot moves to where you are", Formatting.GRAY));
        lore.add(line("stood, and so do they.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Now:  ", Formatting.DARK_GRAY)
                .append(plain(card.spot()).formatted(Formatting.WHITE)));
        lore.add(line("You:  ", Formatting.DARK_GRAY)
                .append(plain(boss.getBlockPos().getX() + " " + boss.getBlockPos().getY()
                        + " " + boss.getBlockPos().getZ()).formatted(Formatting.WHITE)));
        lore.add(Text.empty());
        lore.add(line(here ? "You're stood on it." : "Click to move them.",
                here ? Formatting.DARK_GRAY : Formatting.YELLOW));
        lore.add(line("They forget the bed and the chest.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack wages() {
        int payroll = TrapCrew.payrollOf(boss);
        ItemStack tag = new ItemStack(Items.CLOCK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Payroll").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(payroll + "e every five minutes", Formatting.RED),
                line("about " + payroll * 12 + "e an hour", Formatting.DARK_GRAY),
                Text.empty(),
                line("You have " + TrapMarket.wealthOf(boss) + "e on you.",
                        Formatting.GRAY),
                line("Wages come out of your pockets and", Formatting.DARK_GRAY),
                line("your wallet, wherever you are.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack fireTag(int nth) {
        ItemStack tag = new ItemStack(Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Let Hand " + (nth + 1) + " go")
                        .formatted(Formatting.RED, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Nothing comes back. Not the hire fee,", Formatting.GRAY),
                line("not what you paid to teach them.", Formatting.GRAY),
                Text.empty(),
                line("Shift-click to be sure.", Formatting.YELLOW))));
        return tag;
    }

    // --- clicking -------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index < crew.size() && index < TrapCrew.MAX_HANDS) {
            selected = index;
            click(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F);
            paint();
            return;
        }
        if (index == HIRE_SLOT) {
            answer(TrapCrew.hire(boss, boss.getBlockPos()));
            return;
        }
        if (crew.isEmpty()) {
            return;
        }
        TrapCrew.Card card = crew.get(selected);
        if (index == PACE_SLOT || index == REACH_SLOT) {
            answer(TrapCrew.buy(boss, card.index(), null, index == PACE_SLOT));
            return;
        }
        if (index == WHIP_SLOT) {
            answer(TrapCrew.whip(boss, card.index()));
            return;
        }
        if (index == MOVE_SLOT) {
            answer(TrapCrew.move(boss, card.index(), boss.getBlockPos()));
            return;
        }
        if (index >= JOBS_FROM && index < JOBS_FROM + TEACHABLE.size()) {
            TrapCrew.Job job = TEACHABLE.get(index - JOBS_FROM);
            answer(card.taught().contains(job) && type == SlotActionType.QUICK_MOVE
                    ? TrapCrew.forget(boss, card.index(), job)
                    : TrapCrew.buy(boss, card.index(), job, false));
            return;
        }
        if (index == FIRE_SLOT) {
            // Deliberately awkward. Firing is the one button here that destroys
            // something you paid for, and a stray click on a 3x9 grid is not a
            // decision.
            if (type == SlotActionType.QUICK_MOVE) {
                answer(TrapCrew.fire(boss, card.index()));
            } else {
                boss.sendMessage(Text.literal("Shift-click if you mean it.")
                        .formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            }
        }
    }

    private void answer(String no) {
        if (no == null) {
            click(SoundEvents.ENTITY_VILLAGER_WORK_FARMER, 1.0F);
        } else {
            boss.sendMessage(Text.literal(no).formatted(Formatting.GRAY), true);
            click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
        }
        paint();
    }

    private void click(net.minecraft.sound.SoundEvent sound, float pitch) {
        boss.getWorld().playSound(null, boss.getBlockPos(), sound,
                SoundCategory.PLAYERS, 0.7F, pitch);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        // Every slot on this screen is a button, and the player's own bag has
        // nowhere to go. Shift-clicking the board is still a click, which is
        // what makes the fire confirmation work.
        if (index < SIZE) {
            onSlotClick(index, 0, SlotActionType.QUICK_MOVE, player);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == boss;
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
        public boolean canTakeItems(PlayerEntity player) {
            return false;
        }
    }
}
