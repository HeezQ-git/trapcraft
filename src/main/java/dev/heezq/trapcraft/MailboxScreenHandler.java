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
 *   . . . . [what's next] . . . .
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

    /** One line of the checklist: what it is, what it looks like, is it there. */
    private record Tick(String name, Item icon, String blurb) {
    }

    private static final List<Tick> LIST = List.of(
            new Tick("Somewhere to sleep", Items.RED_BED, "A bed. Not optional."),
            new Tick("Somewhere to make things", Items.CRAFTING_TABLE, "A crafting table."),
            new Tick("Somewhere to put things", Items.CHEST, "A chest or a barrel."),
            new Tick("Somewhere to cook", Items.FURNACE, "A furnace, smoker or blast furnace."),
            new Tick("Somewhere to shop", Items.EMERALD, "A market stall, indoors."),
            new Tick("A window", Items.GLASS, "Glass or panes in the wall."),
            new Tick("No dark corners", Items.TORCH, "Every square lit above "
                    + HomeSurvey.DARK_AT + ", at night."),
            new Tick("Character", Items.FLOWER_POT, HomeSurvey.DECOR_STEPS[0] + " different "
                    + "kinds of block, then " + HomeSurvey.DECOR_STEPS[1] + "."));

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
                reading.sealed() ? "Sealed" : reading.clash() ? "Somebody else's" : "Open",
                reading.sealed() ? "Walls, floor and a roof, all present."
                        : reading.clash() ? "It runs into another house."
                        : "There's a hole in it somewhere.",
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
                reading.dark() == 0,
                reading.kinds() >= HomeSurvey.DECOR_STEPS[0]};
        String[] detail = {
                reading.bed() ? "There." : "Missing.",
                reading.crafting() ? "There." : "Missing.",
                reading.storage() ? "There." : "Missing.",
                reading.cooking() ? "There." : "Missing.",
                reading.stall() ? "There." : "Missing.",
                reading.window() ? "There." : "Missing.",
                reading.dark() == 0 ? "Every square lit."
                        : reading.dark() + " dark "
                        + (reading.dark() == 1 ? "square" : "squares")
                        + " of " + reading.floor(),
                reading.kinds() + " kinds of block"};
        for (int i = 0; i < LIST.size(); i++) {
            display.setStack(LIST_FROM + i, tick(LIST.get(i), got[i], detail[i]));
        }
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
        lore.add(line("can go anywhere you like.", Formatting.DARK_GRAY));
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
                    : "Find the hole. Walls, floor and roof, no gaps.";
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
        } else if (reading.dark() > 0) {
            say = reading.dark() + " dark " + (reading.dark() == 1 ? "square" : "squares")
                    + " left. Walk it at night and look at the floor.";
        } else if (reading.fittings() < HomeSurvey.FITTINGS) {
            say = "Fit it out -- a table, a chest, a furnace, a stall, a window.";
        } else if (reading.finished() < HomeSurvey.SHELL_STEPS[1]) {
            say = "Finish the shell. " + Math.round(HomeSurvey.SHELL_STEPS[1] * 100)
                    + "% worked material, you're at " + Math.round(reading.finished() * 100) + "%.";
        } else if (reading.kinds() < HomeSurvey.DECOR_STEPS[1]) {
            say = "Decorate. " + HomeSurvey.DECOR_STEPS[1] + " kinds of block, you have "
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
