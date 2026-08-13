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
            new Tick("Miejsce do spania", Items.RED_BED, "Łóżko. Obowiązkowe."),
            new Tick("Miejsce do pracy", Items.CRAFTING_TABLE, "Stół rzemieślniczy."),
            new Tick("Miejsce na rzeczy", Items.CHEST, "Skrzynia albo beczka."),
            new Tick("Miejsce do gotowania", Items.FURNACE, "Piec, wędzarnia albo piec hutniczy."),
            new Tick("Miejsce na zakupy", Items.EMERALD, "Stragan, w środku budynku."),
            new Tick("Okno", Items.GLASS,
                    "Szkło, szyby albo cokolwiek, co mod nazywa oknem."),
            new Tick("Oświetlenie", Items.TORCH, "Na wysokości głowy, jaśniej niż "
                    + HomeSurvey.DARK_AT + ", w nocy. Pochodnie na suficie się liczą."),
            new Tick("Wystrój", Items.FLOWER_POT, HomeSurvey.DECOR_STEPS[0] + " różnych "
                    + "rodzajów bloków, maks. "
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
        display.setStack(FLOOR_SLOT, count(Items.OAK_PLANKS, "Podłoga",
                reading.floor() + " kratek",
                reading.floor() < HomeSurvey.MIN_FLOOR
                        ? "Poniżej " + HomeSurvey.MIN_FLOOR + ". Za mało na dom."
                        : "Pozwala na klasę " + reading.roomFor()
                        + (reading.roomFor() >= HomeSurvey.TOP_TIER ? ". Wystarczająco duży."
                        : "; " + nextStep() + " kratek pozwoliłoby na "
                        + (reading.roomFor() + 1) + "."),
                reading.floor() >= HomeSurvey.MIN_FLOOR));
        display.setStack(SEALED_SLOT, count(Items.BRICKS, "Szczelność",
                reading.sealed() ? "Szczelny" : reading.clash() ? "Cudzy dom"
                        : reading.buried() ? "Zamurowany" : "Dziurawy",
                reading.sealed() ? "Ściany, podłoga i dach - wszystko jest."
                        : reading.clash() ? "Nachodzi na inny dom."
                        : reading.buried()
                        ? "Punkt pomiaru jest teraz zamurowany. Postaw skrzynkę "
                        + "w środku pokoju, a zmierzy od nowa."
                        : "Jest dziura. Pomiar od " + where(reading.measuredFrom())
                        + ", wyciek do " + where(reading.leak()) + ".",
                reading.sealed()));
        display.setStack(WAYS_SLOT, count(Items.OAK_DOOR, "Wejścia",
                reading.exits() + (reading.exits() == 1 ? " drzwi" : " drzwi"),
                reading.exits() > 0 ? "Wychodzą na zewnątrz."
                        : "Brak drzwi na zewnątrz.",
                reading.exits() > 0));
        display.setStack(SHELL_SLOT, count(Items.BRICK, "Zbudowane, nie wykopane",
                Math.round(reading.finished() * 100) + "%",
                reading.finished() >= HomeSurvey.SHELL_STEPS[1]
                        ? "Porządnie wykonane."
                        : "Ziemia, piasek, żwir, kamień i bruk się nie liczą. "
                        + Math.round(HomeSurvey.SHELL_STEPS[0] * 100) + "% daje punkt, "
                        + Math.round(HomeSurvey.SHELL_STEPS[1] * 100) + "% daje dwa.",
                reading.finished() >= HomeSurvey.SHELL_STEPS[0]));
        display.setStack(AGAIN_SLOT, again());

        boolean[] got = {reading.bed(), reading.crafting(), reading.storage(),
                reading.cooking(), reading.stall(), reading.window(),
                HomeSurvey.lightPoints(reading.dark(), reading.floor()) > 0,
                reading.kinds() >= HomeSurvey.DECOR_STEPS[0]};
        String[] detail = {
                reading.bed() ? "Jest." : "Brakuje.",
                reading.crafting() ? "Jest." : "Brakuje.",
                reading.storage() ? "Jest." : "Brakuje.",
                reading.cooking() ? "Jest." : "Brakuje.",
                reading.stall() ? "Jest." : "Brakuje.",
                reading.window() ? "Jest." : "Brakuje.",
                reading.dark() == 0 ? "Każda kratka oświetlona."
                        : reading.dark() + " ciemnych z " + reading.floor()
                        + (HomeSurvey.lightPoints(reading.dark(), reading.floor()) > 0
                        ? "  -- wystarczy" : "  -- za dużo"),
                reading.kinds() + " rodzajów bloków"};
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
                ? line("Nie nadaje się do mieszkania.", Formatting.RED)
                : line("Klasa " + tier + " z " + HomeSurvey.TOP_TIER,
                Formatting.GREEN, Formatting.BOLD));
        lore.add(line("Właściciel: " + home.ownerName(), Formatting.DARK_GRAY));
        if (reading.sealed()) {
            lore.add(Text.empty());
            lore.add(line(reading.points() + " z " + HomeSurvey.topPoints()
                    + " punktów, dwa punkty na klasę", Formatting.GRAY));
            // The lid, said plainly. Fittings are a shopping list; a shopping
            // list is not a building, and this is the line that says so.
            lore.add(reading.cramped()
                    ? line("Za mały na wyższą klasę. " + nextStep()
                    + " kratek podłogi pozwoli na klasę " + (reading.roomFor() + 1) + ".",
                    Formatting.YELLOW)
                    : line("Metraż pozwala na klasę do " + reading.roomFor() + ".",
                    Formatting.DARK_GRAY));
        }
        lore.add(Text.empty());
        lore.add(line("Zmierzone od " + home.anchor().getX() + " "
                + home.anchor().getY() + " " + home.anchor().getZ(), Formatting.DARK_GRAY));
        lore.add(line("To miejsce JEST domem. Skrzynkę", Formatting.DARK_GRAY));
        lore.add(line("możesz postawić gdzie chcesz. Jeśli", Formatting.DARK_GRAY));
        lore.add(line("przebudujesz, postaw ją w środku,", Formatting.DARK_GRAY));
        lore.add(line("a zmierzy dom od nowa.", Formatting.DARK_GRAY));
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
        // Notice overrules the mood everywhere on this tag: somebody perfectly
        // happy can still be walking out tomorrow, and a cake over a mailbox
        // that empties in the morning is the screen lying to its landlord.
        boolean going = TrapHomes.leaving(home);
        ItemStack tag = new ItemStack(who == null ? Items.BARRIER
                : going || mood < HomeSurvey.MOOD_LEAVING ? Items.WITHER_ROSE
                : mood >= HomeSurvey.MOOD_MAX ? Items.CAKE : Items.BREAD);
        tag.set(DataComponentTypes.CUSTOM_NAME, who == null
                ? plain("Nikt tu nie mieszka").formatted(Formatting.RED, Formatting.BOLD)
                : plain(who).formatted(Formatting.AQUA, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        if (who == null) {
            lore.add(line(reading.tier() > 0
                            ? "Ktoś może się wprowadzić lada dzień --"
                            : "Nikt się nie wprowadzi bez klasy domu.",
                    reading.tier() > 0 ? Formatting.GRAY : Formatting.RED));
            if (reading.tier() > 0) {
                lore.add(line("im lepszy dom, tym szybciej.", Formatting.DARK_GRAY));
            }
        } else {
            lore.add(line("Nastrój  ", Formatting.DARK_GRAY)
                    .append(plain(mood + " z " + HomeSurvey.MOOD_MAX)
                            .formatted(mood < HomeSurvey.MOOD_LEAVING ? Formatting.RED
                                    : Formatting.WHITE)));
            lore.add(line(going ? "Wypowiedział. Jutro go nie ma."
                            : mood < HomeSurvey.MOOD_LEAVING ? "Pakuje się."
                            : mood < 50 ? "Ma dość."
                            : mood < HomeSurvey.MOOD_MAX ? "W miarę zadowolony."
                            : "Bardzo zadowolony.",
                    going || mood < HomeSurvey.MOOD_LEAVING ? Formatting.RED : Formatting.GRAY));
            lore.add(Text.empty());
            int heads = reading.household();
            // Off rateOf, not the bare RENT row: size lifts the rate inside a
            // grade, so the flat table understates what a big house is owed
            // and this line would read as "pays 60e of 42e".
            int full = Math.round(HomeSurvey.rateOf(reading.tier(), reading.floor()) * heads);
            lore.add(line("Płaci  ", Formatting.DARK_GRAY)
                    .append(plain(HomeSurvey.rentDue(reading.tier(), mood, heads,
                            reading.floor()) + "e dziennie").formatted(Formatting.GREEN))
                    .append(plain("  z " + full + "e").formatted(Formatting.DARK_GRAY)));
            // Rent is per person now, so the number on this screen is
            // meaningless without saying how many people are behind it --
            // and "put another bed in" is the most useful thing it can say.
            lore.add(line(heads == 1 ? "Jeden lokator. Kolejne łóżko i metraż"
                            : "Mieszka tu " + heads + ", po " + HomeSurvey.RENT[Math.min(reading.tier(),
                                    HomeSurvey.RENT.length - 1)] + "e.",
                    Formatting.DARK_GRAY));
            lore.add(line(heads == 1 ? "dla niego to kolejny czynsz."
                            : "Więcej łóżek i miejsca, więcej czynszu.", Formatting.DARK_GRAY));
            lore.add(line("Niezadowolony lokator płaci mniej,", Formatting.DARK_GRAY));
            lore.add(line("a potem przestaje płacić w ogóle.", Formatting.DARK_GRAY));
            // Anybody of theirs in a hospital bed, before the shopping list:
            // it is the reason the rent line above is short this week, and a
            // landlord staring at a number that has halved needs to be told
            // why on the same screen.
            for (TrapHospitals.Patient ill : TrapHospitals.illAt(home.id())) {
                lore.add(Text.empty());
                TrapHospitals.Ward ward = ill.ward() == null ? null
                        : TrapHospitals.byId(ill.ward());
                lore.add(line(ill.who(), Formatting.RED, Formatting.BOLD)
                        .append(plain(ward == null ? " został ugryziony"
                                : " leży w " + ward.name()).formatted(Formatting.GRAY)));
                lore.add(ward == null
                        ? line("Żaden szpital go nie przyjmie. " + ill.untreated() + " z "
                                + TrapHospitals.LOST_DAYS + " dni.", Formatting.RED)
                        : line("Wraca jutro. Do tego czasu nic nie zarabia.",
                                Formatting.DARK_GRAY));
            }
            lore.add(Text.empty());
            TrapHomes.Craving wants = home.craving();
            lore.add(wants == null
                    ? line("Dzisiaj niczego nie chce.", Formatting.DARK_GRAY)
                    : line("Chce ", Formatting.GRAY)
                    .append(plain(wants.count() + "x " + wants.label())
                            .formatted(Formatting.WHITE))
                    .append(plain("  za " + wants.price() + "e")
                            .formatted(Formatting.GREEN)));
            if (wants != null) {
                lore.add(line("Znajdź go i kliknij PPM, trzymając to.",
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
                plain("Poczta").formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        if (letters.isEmpty()) {
            lore.add(line("Nic nie przyszło.", Formatting.DARK_GRAY));
        } else {
            for (String letter : letters) {
                lore.add(line("\"" + letter + "\"", Formatting.WHITE));
            }
        }
        lore.add(Text.empty());
        lore.add(line("Czynsz trafia tutaj. Otwarcie tego", Formatting.DARK_GRAY));
        lore.add(line("okna już go odebrało.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private static String where(net.minecraft.util.math.BlockPos pos) {
        return pos == null ? "nigdzie"
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
                plain("Sprawdź ponownie").formatted(Formatting.YELLOW, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Obchodzi ściany i przelicza klasę.", Formatting.GRAY),
                Text.empty(),
                line("I tak robi to samo co kilka", Formatting.DARK_GRAY),
                line("minut.", Formatting.DARK_GRAY))));
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
                    ? "Przesuń się. Ten dom nachodzi na inny, już zarejestrowany."
                    : reading.buried()
                    ? "Pomiar idzie od " + where(reading.measuredFrom())
                    + ", a tam jest teraz blok. Postaw skrzynkę w środku pokoju."
                    // "Find the hole" on its own is a shrug. The leak point is
                    // on the far side of whatever gap the fill went through, so
                    // it is a direction rather than a chore.
                    : "Jest dziura. Pomiar od " + where(reading.measuredFrom())
                    + " wyszedł aż do " + where(reading.leak())
                    + " -- szpara jest między tymi punktami.";
        } else if (reading.floor() < HomeSurvey.MIN_FLOOR) {
            say = "Powiększ go. Minimum " + HomeSurvey.MIN_FLOOR + " kratek podłogi.";
        } else if (reading.exits() == 0) {
            say = "Wstaw drzwi.";
        } else if (!reading.bed()) {
            say = "Wstaw łóżko.";
        } else if (reading.lights() == 0) {
            say = "Oświetl go. Nikt nie śpi po ciemku.";
        } else if (reading.cramped()) {
            // Size first once it is the binding constraint, because every
            // other suggestion would be a waste of the player's evening.
            say = "Zbuduj WIĘKSZY. " + nextStep() + " kratek podłogi -- kolejny pokój "
                    + "albo piętro -- da klasę " + (reading.roomFor() + 1) + ".";
        } else if (reading.finished() < HomeSurvey.SHELL_STEPS[0]) {
            say = "Przestań budować z ziemi. Deski, cegły, cokolwiek obrobionego.";
        } else if (HomeSurvey.lightPoints(reading.dark(), reading.floor()) < 2) {
            say = "Ciemnych kratek: " + reading.dark() + " z " + reading.floor()
                    + ". Lampa w najciemniejszym kącie załatwi sprawę.";
        } else if (reading.fittings() < HomeSurvey.FITTINGS) {
            say = "Umebluj go -- stół, skrzynia, piec, stragan, okno.";
        } else if (reading.finished() < HomeSurvey.SHELL_STEPS[1]) {
            // Name the blocks. A percentage on its own reads as an accusation
            // nobody can answer -- you look round a house made of stone brick
            // and planks, get told it is 83% worked, and conclude the mod is
            // broken. The three commonest offenders turn it into a job.
            say = "Dokończ ściany. Potrzeba " + Math.round(HomeSurvey.SHELL_STEPS[1] * 100)
                    + "% obrobionych bloków, masz " + Math.round(reading.finished() * 100)
                    + "%." + (reading.roughest().isEmpty() ? ""
                            : " Głównie " + reading.roughest() + ".");
        } else if (reading.kinds() < nextDecor(reading.kinds())) {
            say = "Udekoruj. Potrzeba " + nextDecor(reading.kinds()) + " rodzajów bloków, masz "
                    + reading.kinds() + ".";
        } else if (reading.roomFor() < HomeSurvey.TOP_TIER) {
            say = "Więcej miejsca. " + nextStep() + " kratek podłogi to ostatni próg.";
        } else {
            say = "Nic. Lepszego domu się nie da zrobić.";
        }

        ItemStack tag = new ItemStack(reading.tier() >= HomeSurvey.TOP_TIER
                ? Items.GOLDEN_APPLE : Items.WRITABLE_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Co dalej").formatted(Formatting.GOLD, Formatting.BOLD));
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
