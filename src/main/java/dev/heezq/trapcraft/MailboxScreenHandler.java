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
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The survey, on the wall.
 *
 * A checklist rather than a paragraph, because the whole of step two is "build
 * a room and be told what it is missing", and a row of icons that go from grey
 * to real as you tick them off is the difference between a game and a report.
 *
 *   [grade] . [floor][sealed][ways in][built] . . [again]
 *   [bed][craft][store][cook][stall][window][dark][decor] .
 *   [who lives here] . [the post] . [what's next] . . .
 *
 * Nothing here is buyable. The only button is "look again", and the only other
 * thing you can do is take the box down -- which is the block's job, not this
 * screen's, so it just says so.
 */
public class MailboxScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int GRADE_SLOT = 0;
    private static final int FLOOR_SLOT = 2;
    private static final int SEALED_SLOT = 3;
    private static final int WAYS_SLOT = 4;
    private static final int SHELL_SLOT = 5;
    private static final int AGAIN_SLOT = 8;
    private static final int LIST_FROM = 9;
    private static final int NEXT_SLOT = 22;
    private static final int TENANT_SLOT = 18;
    private static final int POST_SLOT = 20;

    /** One line of the checklist: what it is, what it looks like, is it there. */
    private record Tick(String name, Item icon, String blurb) {
    }

    private static final List<Tick> LIST = List.of(
            new Tick("Somewhere to sleep", Items.RED_BED, "A bed. Not optional."),
            new Tick("Somewhere to make things", Items.CRAFTING_TABLE, "A crafting table."),
            new Tick("Somewhere to put things", Items.CHEST, "A chest or a barrel."),
            new Tick("Somewhere to cook", Items.FURNACE, "A furnace, smoker or blast furnace."),
            new Tick("Somewhere to shop", Items.EMERALD, "A market stall, indoors."),
            new Tick("A window", Items.GLASS,
                    "Glass, panes, or anything a mod calls a window."),
            new Tick("Lighting", Items.TORCH, "Head height, brighter than "
                    + HomeSurvey.DARK_AT + ", at night. Ceiling torches count."),
            new Tick("Character", Items.FLOWER_POT, HomeSurvey.DECOR_STEPS[0] + " different "
                    + "kinds of block, up to "
                    + HomeSurvey.DECOR_STEPS[HomeSurvey.DECOR_STEPS.length - 1] + "."));

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;
    private final TrapHomes.Home home;
    private TrapHomes.Readout reading;

    public MailboxScreenHandler(int syncId, PlayerInventory playerInventory,
                                TrapHomes.Home home) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.who = (ServerPlayerEntity) playerInventory.player;
        this.home = home;

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
        survey();
    }

    private void survey() {
        reading = TrapHomes.measure((ServerWorld) who.getWorld(), home);
        paint();
    }

    // --- drawing --------------------------------------------------------------

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        display.setStack(GRADE_SLOT, grade());
        display.setStack(FLOOR_SLOT, count(Items.OAK_PLANKS, "Floor",
                reading.floor() + " squares",
                reading.floor() < HomeSurvey.MIN_FLOOR
                        ? "Under " + HomeSurvey.MIN_FLOOR + ". Too small to be a house."
                        : "Room for grade " + reading.roomFor()
                        + (reading.roomFor() >= HomeSurvey.TOP_TIER ? ". As big as it needs."
                        : "; " + nextStep() + " squares would allow "
                        + (reading.roomFor() + 1) + "."),
                reading.floor() >= HomeSurvey.MIN_FLOOR));
        display.setStack(SEALED_SLOT, count(Items.BRICKS, "Shell",
                reading.sealed() ? "Sealed" : reading.clash() ? "Somebody else's"
                        : reading.buried() ? "Bricked in" : "Open",
                reading.sealed() ? "Walls, floor and a roof, all present."
                        : reading.clash() ? "It runs into another house."
                        : reading.buried()
                        ? "The spot it's measured from is solid now. Stand the "
                        + "box in the room and it will re-measure from there."
                        : "It leaks. Measured from " + where(reading.measuredFrom())
                        + ", got as far as " + where(reading.leak()) + ".",
                reading.sealed()));
        display.setStack(WAYS_SLOT, count(Items.OAK_DOOR, "Ways in",
                reading.exits() + (reading.exits() == 1 ? " door" : " doors"),
                reading.exits() > 0 ? "Onto the street."
                        : "No door to the outside at all.",
                reading.exits() > 0));
        display.setStack(SHELL_SLOT, count(Items.BRICK, "Built, not dug",
                Math.round(reading.finished() * 100) + "%",
                reading.finished() >= HomeSurvey.SHELL_STEPS[1]
                        ? "Properly made."
                        : "Dirt, sand, gravel, plain stone and cobble don't count. "
                        + Math.round(HomeSurvey.SHELL_STEPS[0] * 100) + "% earns a point, "
                        + Math.round(HomeSurvey.SHELL_STEPS[1] * 100) + "% earns two.",
                reading.finished() >= HomeSurvey.SHELL_STEPS[0]));
        display.setStack(AGAIN_SLOT, again());

        boolean[] got = {reading.bed(), reading.crafting(), reading.storage(),
                reading.cooking(), reading.stall(), reading.window(),
                HomeSurvey.lightPoints(reading.dark(), reading.floor()) > 0,
                reading.kinds() >= HomeSurvey.DECOR_STEPS[0]};
        String[] detail = {
                reading.bed() ? "There." : "Missing.",
                reading.crafting() ? "There." : "Missing.",
                reading.storage() ? "There." : "Missing.",
                reading.cooking() ? "There." : "Missing.",
                reading.stall() ? "There." : "Missing.",
                reading.window() ? "There." : "Missing.",
                reading.dark() == 0 ? "Every square lit."
                        : reading.dark() + " dim of " + reading.floor()
                        + (HomeSurvey.lightPoints(reading.dark(), reading.floor()) > 0
                        ? "  -- good enough" : "  -- too many"),
                reading.kinds() + " kinds of block"};
        for (int i = 0; i < LIST.size(); i++) {
            display.setStack(LIST_FROM + i, tick(LIST.get(i), got[i], detail[i]));
        }
        display.setStack(TENANT_SLOT, tenant());
        display.setStack(POST_SLOT, post());
        display.setStack(NEXT_SLOT, next());
        sendContentUpdates();
    }

    private ItemStack grade() {
        int tier = reading.tier();
        ItemStack tag = new ItemStack(tier == 0 ? Items.BARRIER
                : tier >= HomeSurvey.TOP_TIER ? Items.NETHER_STAR
                : tier >= 3 ? Items.DIAMOND : Items.IRON_INGOT);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(home.name()).formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(tier == 0
                ? line("Not fit to live in.", Formatting.RED)
                : line("Grade " + tier + " of " + HomeSurvey.TOP_TIER,
                Formatting.GREEN, Formatting.BOLD));
        lore.add(line(home.ownerName() + "'s", Formatting.DARK_GRAY));
        if (reading.sealed()) {
            lore.add(Text.empty());
            lore.add(line(reading.points() + " of " + HomeSurvey.topPoints()
                    + " points, two to a grade", Formatting.GRAY));
            // The lid, said plainly. Fittings are a shopping list; a shopping
            // list is not a building, and this is the line that says so.
            lore.add(reading.cramped()
                    ? line("Too small for better. " + nextStep()
                    + " squares of floor allows grade " + (reading.roomFor() + 1) + ".",
                    Formatting.YELLOW)
                    : line("Floor allows up to grade " + reading.roomFor() + ".",
                    Formatting.DARK_GRAY));
        }
        lore.add(Text.empty());
        lore.add(line("Surveyed from " + home.anchor().getX() + " "
                + home.anchor().getY() + " " + home.anchor().getZ(), Formatting.DARK_GRAY));
        lore.add(line("That spot is the house. This box", Formatting.DARK_GRAY));
        lore.add(line("can go anywhere you like -- and if you", Formatting.DARK_GRAY));
        lore.add(line("rebuild, stand it inside and it", Formatting.DARK_GRAY));
        lore.add(line("re-measures from there.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack count(Item icon, String title, String value, String blurb, boolean good) {
        ItemStack tag = new ItemStack(good ? icon : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(title)
                .formatted(good ? Formatting.WHITE : Formatting.RED, Formatting.BOLD)
                .append(plain("  " + value).formatted(Formatting.GRAY)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(blurb, good ? Formatting.GRAY : Formatting.RED))));
        return tag;
    }

    private ItemStack tick(Tick item, boolean got, String detail) {
        ItemStack tag = new ItemStack(got ? item.icon() : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(item.name())
                .formatted(got ? Formatting.GREEN : Formatting.DARK_GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(item.blurb(), Formatting.GRAY),
                line(detail, got ? Formatting.GREEN : Formatting.RED))));
        return tag;
    }

    /**
     * Who lives here, and how they are getting on.
     *
     * Mood is printed as a number AND as a sentence, because "48" tells you
     * where you are and "thinking about leaving" tells you what it means.
     */
    private ItemStack tenant() {
        String who = home.tenant();
        int mood = home.mood();
        ItemStack tag = new ItemStack(who == null ? Items.BARRIER
                : mood < HomeSurvey.MOOD_LEAVING ? Items.WITHER_ROSE
                : mood >= HomeSurvey.MOOD_MAX ? Items.CAKE : Items.BREAD);
        tag.set(DataComponentTypes.CUSTOM_NAME, who == null
                ? plain("Nobody lives here").formatted(Formatting.RED, Formatting.BOLD)
                : plain(who).formatted(Formatting.AQUA, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        if (who == null) {
            lore.add(line(reading.tier() > 0
                            ? "Somebody will turn up. Give it a day."
                            : "Nobody will, while it's graded nothing.",
                    reading.tier() > 0 ? Formatting.GRAY : Formatting.RED));
        } else {
            lore.add(line("Mood  ", Formatting.DARK_GRAY)
                    .append(plain(mood + " of " + HomeSurvey.MOOD_MAX)
                            .formatted(mood < HomeSurvey.MOOD_LEAVING ? Formatting.RED
                                    : Formatting.WHITE)));
            lore.add(line(mood < HomeSurvey.MOOD_LEAVING ? "Packing."
                            : mood < 50 ? "Fed up."
                            : mood < HomeSurvey.MOOD_MAX ? "Settled enough."
                            : "Very happy here.",
                    mood < HomeSurvey.MOOD_LEAVING ? Formatting.RED : Formatting.GRAY));
            lore.add(Text.empty());
            int heads = reading.household();
            // Off rateOf, not the bare RENT row: size lifts the rate inside a
            // grade, so the flat table understates what a big house is owed
            // and this line would read as "pays 60e of 42e".
            int full = Math.round(HomeSurvey.rateOf(reading.tier(), reading.floor()) * heads);
            lore.add(line("Pays  ", Formatting.DARK_GRAY)
                    .append(plain(HomeSurvey.rentDue(reading.tier(), mood, heads,
                            reading.floor()) + "e a day").formatted(Formatting.GREEN))
                    .append(plain("  of " + full + "e").formatted(Formatting.DARK_GRAY)));
            // Rent is per person now, so the number on this screen is
            // meaningless without saying how many people are behind it --
            // and "put another bed in" is the most useful thing it can say.
            lore.add(line(heads == 1 ? "One tenant. Another bed and the floor"
                            : heads + " living here, " + HomeSurvey.RENT[Math.min(reading.tier(),
                                    HomeSurvey.RENT.length - 1)] + "e each.",
                    Formatting.DARK_GRAY));
            lore.add(line(heads == 1 ? "for them is another rent."
                            : "More beds and more room, more rent.", Formatting.DARK_GRAY));
            lore.add(line("An unhappy tenant pays less before", Formatting.DARK_GRAY));
            lore.add(line("they pay nothing at all.", Formatting.DARK_GRAY));
            lore.add(Text.empty());
            TrapHomes.Craving wants = home.craving();
            lore.add(wants == null
                    ? line("Not after anything today.", Formatting.DARK_GRAY)
                    : line("Wants ", Formatting.GRAY)
                    .append(plain(wants.count() + "x " + wants.label())
                            .formatted(Formatting.WHITE))
                    .append(plain("  for " + wants.price() + "e")
                            .formatted(Formatting.GREEN)));
            if (wants != null) {
                lore.add(line("Find them and right-click, holding it.",
                        Formatting.DARK_GRAY));
            }
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** The letters, which are the whole tutorial for this system. */
    private ItemStack post() {
        List<String> letters = home.letters();
        ItemStack tag = new ItemStack(letters.isEmpty() ? Items.PAPER : Items.WRITTEN_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("The post").formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        if (letters.isEmpty()) {
            lore.add(line("Nothing through the door.", Formatting.DARK_GRAY));
        } else {
            for (String letter : letters) {
                lore.add(line("\"" + letter + "\"", Formatting.WHITE));
            }
        }
        lore.add(Text.empty());
        lore.add(line("Rent lands in here. Opening this", Formatting.DARK_GRAY));
        lore.add(line("screen already took it.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private static String where(net.minecraft.util.math.BlockPos pos) {
        return pos == null ? "nowhere"
                : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** The floor the next grade up wants. */
    private int nextStep() {
        int at = reading.roomFor();
        return at >= HomeSurvey.FLOOR_STEPS.length
                ? HomeSurvey.FLOOR_STEPS[HomeSurvey.FLOOR_STEPS.length - 1]
                : HomeSurvey.FLOOR_STEPS[at];
    }

    private ItemStack again() {
        ItemStack tag = new ItemStack(Items.SPYGLASS);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Look again").formatted(Formatting.YELLOW, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Walks the walls and re-grades.", Formatting.GRAY),
                Text.empty(),
                line("It does this by itself every couple", Formatting.DARK_GRAY),
                line("of minutes anyway.", Formatting.DARK_GRAY))));
        return tag;
    }

    /**
     * The single most useful sentence: what to do next.
     *
     * One thing, not a list. A checklist already IS the list, and a house that
     * tells you seven things at once tells you nothing -- the point of this
     * slot is that somebody who cannot be bothered to read the grid still
     * knows where to put the next block.
     */
    private ItemStack next() {
        String say;
        if (!reading.sealed()) {
            say = reading.clash()
                    ? "Move over. This runs into a place already on the register."
                    : reading.buried()
                    ? "It's measured from " + where(reading.measuredFrom())
                    + ", and that's solid now. Stand this box inside the room."
                    // "Find the hole" on its own is a shrug. The leak point is
                    // on the far side of whatever gap the fill went through, so
                    // it is a direction rather than a chore.
                    : "It leaks. Measured from " + where(reading.measuredFrom())
                    + " and got out to " + where(reading.leak())
                    + " -- the gap is between those two.";
        } else if (reading.floor() < HomeSurvey.MIN_FLOOR) {
            say = "Make it bigger. " + HomeSurvey.MIN_FLOOR + " squares of floor, minimum.";
        } else if (reading.exits() == 0) {
            say = "Put a door in it.";
        } else if (!reading.bed()) {
            say = "Put a bed in it.";
        } else if (reading.lights() == 0) {
            say = "Light it. Nobody sleeps in the dark.";
        } else if (reading.cramped()) {
            // Size first once it is the binding constraint, because every
            // other suggestion would be a waste of the player's evening.
            say = "Build it BIGGER. " + nextStep() + " squares of floor -- another room, "
                    + "or another storey -- allows grade " + (reading.roomFor() + 1) + ".";
        } else if (reading.finished() < HomeSurvey.SHELL_STEPS[0]) {
            say = "Stop building out of dirt. Planks, bricks, anything you made.";
        } else if (HomeSurvey.lightPoints(reading.dark(), reading.floor()) < 2) {
            say = reading.dark() + " dim " + (reading.dark() == 1 ? "square" : "squares")
                    + " of " + reading.floor() + ". A lamp in the darkest corner will do it.";
        } else if (reading.fittings() < HomeSurvey.FITTINGS) {
            say = "Fit it out -- a table, a chest, a furnace, a stall, a window.";
        } else if (reading.finished() < HomeSurvey.SHELL_STEPS[1]) {
            // Name the blocks. A percentage on its own reads as an accusation
            // nobody can answer -- you look round a house made of stone brick
            // and planks, get told it is 83% worked, and conclude the mod is
            // broken. The three commonest offenders turn it into a job.
            say = "Finish the shell. " + Math.round(HomeSurvey.SHELL_STEPS[1] * 100)
                    + "% worked material, you're at " + Math.round(reading.finished() * 100)
                    + "%." + (reading.roughest().isEmpty() ? ""
                            : " Mostly " + reading.roughest() + ".");
        } else if (reading.kinds() < nextDecor(reading.kinds())) {
            say = "Decorate. " + nextDecor(reading.kinds()) + " kinds of block, you have "
                    + reading.kinds() + ".";
        } else if (reading.roomFor() < HomeSurvey.TOP_TIER) {
            say = "More room. " + nextStep() + " squares of floor is the last step.";
        } else {
            say = "Nothing. This is as good as a house gets.";
        }

        ItemStack tag = new ItemStack(reading.tier() >= HomeSurvey.TOP_TIER
                ? Items.GOLDEN_APPLE : Items.WRITABLE_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Next").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(say, Formatting.WHITE))));
        return tag;
    }

    // --- clicking -------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == AGAIN_SLOT) {
            survey();
            who.getWorld().playSound(null, who.getBlockPos(), SoundEvents.ITEM_SPYGLASS_USE,
                    SoundCategory.PLAYERS, 0.7F, 1.2F);
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        if (index < SIZE) {
            onSlotClick(index, 0, SlotActionType.QUICK_MOVE, player);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == who;
    }

    /**
     * The next decor step this house has not met, or the last one.
     *
     * Was hardcoded to the SECOND step, which was the top when there were two
     * of them and became "you are done" advice two thirds of the way up once
     * there were four.
     */
    private static int nextDecor(int kinds) {
        for (int step : HomeSurvey.DECOR_STEPS) {
            if (kinds < step) {
                return step;
            }
        }
        return HomeSurvey.DECOR_STEPS[HomeSurvey.DECOR_STEPS.length - 1];
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
