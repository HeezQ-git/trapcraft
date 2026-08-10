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
 *   [hand][hand][hand] . [book] . . . [hire]
 *   [pace][reach] . [job][job][job][job][job]
 *   . . . . [wages] . . . [fire]
 *
 * The selected hand is the one everything on the bottom two rows applies to,
 * which is why the top row is heads you click rather than a list you read.
 */
public class CrewScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int HELP_SLOT = 4;
    private static final int HIRE_SLOT = 8;
    private static final int PACE_SLOT = 9;
    private static final int REACH_SLOT = 10;
    private static final int JOBS_FROM = 11;
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
        lore.add(line("Works " + card.reachBlocks() + " blocks around the spot",
                Formatting.GRAY));
        lore.add(line("Wages  ", Formatting.DARK_GRAY)
                .append(plain(card.wage() + "e").formatted(Formatting.RED))
                .append(plain(" every five minutes").formatted(Formatting.DARK_GRAY)));
        lore.add(Text.empty());
        StringBuilder knows = new StringBuilder();
        for (TrapCrew.Job job : card.taught()) {
            knows.append(knows.isEmpty() ? "" : ", ").append(job.display());
        }
        lore.add(line("Knows " + card.taught().size() + " of " + TrapCrew.SLOTS
                + (knows.isEmpty() ? " -- nothing yet" : ": " + knows),
                card.taught().isEmpty() ? Formatting.RED : Formatting.WHITE));
        lore.add(Text.empty());
        // "Present" is worth a line of its own: an unloaded or dead hand does
        // no work and takes no wages, and from the outside that is
        // indistinguishable from one that is simply being lazy.
        lore.add(card.present()
                ? line("On the patch.", Formatting.GREEN)
                : line("Nowhere to be seen -- unloaded, or a zombie got them.",
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
            lore.add(line("Shift-click to drop it.", Formatting.YELLOW));
            lore.add(line("Nothing comes back.", Formatting.DARK_GRAY));
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
                Text.empty(),
                line("TWO JOBS EACH. Want a third thing", Formatting.WHITE),
                line("done? Hire a third person.", Formatting.WHITE),
                line("Teaching costs up front AND puts", Formatting.WHITE),
                line("the wage up for good.", Formatting.WHITE),
                Text.empty(),
                line("Miss a wage packet and they walk,", Formatting.DARK_GRAY),
                line("taking everything you taught them.", Formatting.DARK_GRAY))));
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
