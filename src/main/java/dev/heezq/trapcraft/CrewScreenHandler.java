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
 *   [job][job][job][move][wages][nights][plans][whip][fire]
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
    // Moved off 20 into the gap the row already had when laundering made a
    // tenth job. The job row is the only thing here that grows, so the spare
    // slot is better spent on it than left as a hole next to the fire button.
    private static final int WHIP_SLOT = 25;
    private static final int MOVE_SLOT = 21;
    private static final int NIGHTS_SLOT = 23;
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
        // The job row is laid out by counting off JOBS_FROM, so an eleventh job
        // would land on the move button and be eaten by the click handler
        // without a word. Better to fall over the first time somebody opens the
        // board than to sell a job that quietly relocates the hand instead.
        if (JOBS_FROM + TEACHABLE.size() > MOVE_SLOT) {
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
            display.setStack(WHIP_SLOT, whipTag(card, selected));
            display.setStack(MOVE_SLOT, moveTag(card));
            display.setStack(NIGHTS_SLOT, nightsTag(card));
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
                plain("Robotnik " + (nth + 1))
                        .formatted(chosen ? Formatting.YELLOW : Formatting.WHITE, Formatting.BOLD)
                        .append(plain(chosen ? "  <" : "").formatted(Formatting.GOLD)));
        List<Text> lore = new ArrayList<>();
        lore.add(line(TrapCrew.PACE_NAME[card.pace()] + " -- czynność co "
                + card.tempo(), Formatting.GRAY));
        lore.add(line("Pracuje " + card.reachBlocks() + " bloków wokół  ", Formatting.GRAY)
                .append(plain(card.spot()).formatted(Formatting.WHITE)));
        // The single most misunderstood thing about the crew: a hand uses ONE
        // container, the nearest one to its spot, and nothing else in the
        // world. Somebody with the right things in the wrong chest was doing
        // everything right and getting nothing.
        lore.add(card.chest() == null
                ? line("BRAK SKRZYNI na działce. Nie może pracować.", Formatting.RED)
                : line("Korzysta ze skrzyni na  ", Formatting.DARK_GRAY)
                .append(plain(card.chest()).formatted(Formatting.WHITE))
                .append(plain("  (najbliższa)").formatted(Formatting.DARK_GRAY)));
        lore.add(line("Pensja  ", Formatting.DARK_GRAY)
                .append(plain(card.wage() + "e").formatted(Formatting.RED))
                .append(plain(" za każde pięć minut PRACY")
                        .formatted(Formatting.DARK_GRAY)));
        lore.add(card.nights()
                ? line("NOCNA ZMIANA. Licznik nie staje.", Formatting.GOLD)
                : line("Noce darmowe. Wtedy śpi.", Formatting.DARK_GRAY));
        // The books, which exist because "are they earning their keep" was a
        // question three people had and nobody could answer.
        if (card.done() > 0) {
            lore.add(line("Wykonał " + card.done() + " czynności za " + card.paid() + "e  ",
                    Formatting.DARK_GRAY)
                    .append(plain(String.format("%.1fe za czynność", card.perJob()))
                            .formatted(Formatting.WHITE))
                    .append(plain(String.format("  (najlepiej %.1fe)", card.parJob()))
                            .formatted(Formatting.DARK_GRAY)));
            if (card.perJob() > card.parJob() * 1.6f) {
                lore.add(line("Głównie chodzi. Zmniejsz działkę",
                        Formatting.YELLOW));
                lore.add(line("albo postaw skrzynię bliżej pracy.", Formatting.YELLOW));
            }
        }
        if (card.missed() > 0) {
            lore.add(line("ZALEGŁE " + card.owed() + "e -- jeszcze "
                    + (TrapCrew.GRACE_PACKETS - card.missed())
                    + " wypłat i odchodzi", Formatting.RED, Formatting.BOLD));
        }
        lore.add(Text.empty());
        StringBuilder knows = new StringBuilder();
        for (TrapCrew.Job job : card.taught()) {
            knows.append(knows.isEmpty() ? "" : ", ").append(job.display());
        }
        lore.add(line("Robi " + card.taught().size() + " z " + TrapCrew.SLOTS
                + (knows.isEmpty() ? " -- na razie nic" : ": " + knows),
                card.taught().isEmpty() ? Formatting.RED : Formatting.WHITE));
        int off = card.owned().size() - card.taught().size();
        if (off > 0) {
            lore.add(line("Umie jeszcze " + off + ", wyłączone. Włączysz za darmo.",
                    Formatting.DARK_GRAY));
        }
        lore.add(Text.empty());
        // "Present" is worth a line of its own: a hand who isn't there does no
        // work and takes no wages, and from the outside that is
        // indistinguishable from one that is simply being lazy. It used to
        // also mean "you are stood too far away", which it no longer can --
        // the patch holds itself open now, so this is a zombie or nothing.
        // "Use the whip" was half an instruction. The whip acts on whoever is
        // SELECTED, and a player who reads this line is by definition looking
        // at a head they have not clicked -- so the obvious next move whipped
        // hand one, which was alive, walked back to its patch, and said so in
        // green while the dead one stayed dead.
        lore.add(card.present()
                ? line("Jest na działce.", Formatting.GREEN)
                : line(chosen ? "Zginął. Użyj bata, żeby postawić nowego."
                : "Zginął. Kliknij go, potem bat.", Formatting.RED));
        lore.add(line(chosen ? "Wybrany." : "Kliknij, żeby wybrać.",
                chosen ? Formatting.DARK_GRAY : Formatting.YELLOW));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack ladder(TrapCrew.Card card, boolean pace) {
        int rung = pace ? card.pace() : card.reach();
        int peak = pace ? card.paceMax() : card.reachMax();
        int rungs = (pace ? TrapCrew.PACE_TICKS : TrapCrew.REACH_BLOCKS).length;
        boolean top = rung >= rungs - 1;
        int cost = top ? 0 : TrapMath.crewRungCost(
                pace ? TrapCrew.PACE_COST : TrapCrew.REACH_COST, rung + 1, peak);
        boolean can = !top && TrapMarket.wealthOf(boss) >= cost;

        ItemStack tag = new ItemStack(top ? Items.GOLD_INGOT
                : can ? (pace ? Items.SUGAR : Items.SPYGLASS) : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(pace ? "Tempo" : "Zasięg")
                        .formatted(top ? Formatting.GOLD : can ? Formatting.AQUA
                                : Formatting.DARK_GRAY, Formatting.BOLD)
                        .append(plain("  " + (rung + 1) + " z " + rungs)
                                .formatted(Formatting.WHITE)));
        List<Text> lore = new ArrayList<>();
        lore.add(pace
                ? line("Teraz: czynność co " + card.tempo() + ".", Formatting.GRAY)
                : line("Teraz: " + card.reachBlocks() + " bloków wokół miejsca.",
                Formatting.GRAY));
        if (top) {
            lore.add(line("Maksymalny poziom.", Formatting.GOLD));
        } else {
            lore.add(pace
                    ? line("Dalej: co " + TrapCrew.paceLabel(rung + 1)
                    + ", i szybciej chodzi.", Formatting.WHITE)
                    : line("Dalej: " + TrapCrew.REACH_BLOCKS[rung + 1] + " bloków.",
                    Formatting.WHITE));
            lore.add(Text.empty());
            lore.add(line(cost == 0 ? "Za darmo -- już kupione." : cost + "e", Formatting.GOLD)
                    .append(plain(", pensja wzrośnie do " + wageAfter(card,
                            pace ? TrapCrew.PACE_WAGE[rung + 1] - TrapCrew.PACE_WAGE[rung]
                                    : TrapCrew.REACH_WAGE[rung + 1] - TrapCrew.REACH_WAGE[rung])
                            + "e.").formatted(Formatting.DARK_GRAY)));
            lore.add(line(can ? "Kliknij, żeby podnieść." : "Nie stać cię.",
                    can ? Formatting.YELLOW : Formatting.DARK_GRAY));
        }
        // The way back down, which is what makes the way up safe to take: the
        // wage follows the rung they are ON, so a hand bought to the top for a
        // build can be turned down for the winter and back up for nothing.
        if (rung > 0) {
            lore.add(Text.empty());
            lore.add(line("Shift+LPM obniża do ", Formatting.YELLOW)
                    .append(plain(pace ? TrapCrew.PACE_NAME[rung - 1]
                            : TrapCrew.REACH_BLOCKS[rung - 1] + " bloków")
                            .formatted(Formatting.WHITE))
                    .append(plain(" -- pensja " + wageAfter(card,
                            pace ? TrapCrew.PACE_WAGE[rung - 1] - TrapCrew.PACE_WAGE[rung]
                                    : TrapCrew.REACH_WAGE[rung - 1] - TrapCrew.REACH_WAGE[rung])
                            + "e.").formatted(Formatting.DARK_GRAY)));
            lore.add(line("Kupione poziomy zostają. Powrót za darmo.",
                    Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /**
     * What the packet becomes if this rung is bought or given up.
     *
     * The night rate has to be applied to the difference too. Adding a raw
     * table figure to a wage that has already been multiplied by 1.25 quietly
     * under-quoted every night worker on the server by a quarter of the rung
     * they were about to buy.
     */
    private int wageAfter(TrapCrew.Card card, int delta) {
        return card.wage() + Math.round(delta * (card.nights() ? TrapCrew.NIGHT_RATE : 1f));
    }

    private ItemStack jobTag(TrapCrew.Card card, TrapCrew.Job job) {
        boolean known = card.taught().contains(job);
        // Paid for once, switched on and off for nothing after that. The two
        // slots cap what a hand DOES, not what it knows.
        boolean owned = card.owned().contains(job);
        boolean full = card.taught().size() >= TrapCrew.SLOTS;
        int cost = owned ? 0 : job.cost();
        boolean can = !known && !full && TrapMarket.wealthOf(boss) >= cost;
        ItemStack tag = new ItemStack(known || can ? icon(job) : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(job.display()).formatted(known ? Formatting.GREEN
                        : can ? Formatting.WHITE : Formatting.DARK_GRAY, Formatting.BOLD)
                        .append(plain(!known && owned ? "  wyłączony" : "")
                                .formatted(Formatting.GOLD)));
        List<Text> lore = new ArrayList<>();
        lore.add(line(job.blurb(), Formatting.GRAY));
        lore.add(Text.empty());
        if (known) {
            lore.add(line("Wyszkolony.", Formatting.GREEN)
                    .append(plain("  +" + job.wage() + "e do pensji.")
                            .formatted(Formatting.DARK_GRAY)));
            // The line that would have saved somebody asking why their roller
            // never rolled. A job with nothing to work on looks identical to a
            // job that is broken, and only one of them is your fault.
            lore.add(line("Potrzebuje: " + job.needs() + ".", Formatting.GRAY));
            if (card.starved().contains(job)) {
                lore.add(line("NIE TERAZ -- w skrzyni tego nie ma.",
                        Formatting.RED, Formatting.BOLD));
                lore.add(line("Zagląda tylko do JEDNEJ skrzyni:", Formatting.RED));
                lore.add(line("najbliższej jego miejscu pracy.", Formatting.RED));
            } else {
                lore.add(line("Gotowe. Skrzynia ma czym pracować.", Formatting.GREEN));
            }
            lore.add(line("Shift+LPM wyłącza go i zwalnia miejsce.", Formatting.YELLOW));
            lore.add(line("Nauka zostaje -- włączysz z powrotem za darmo.",
                    Formatting.DARK_GRAY));
        } else {
            lore.add(owned
                    ? line("Wyuczony, ale wyłączony.", Formatting.GOLD)
                    .append(plain("  Znów +" + job.wage() + "e do pensji.")
                            .formatted(Formatting.DARK_GRAY))
                    : line(cost == 0 ? "Za darmo." : cost + "e", Formatting.GOLD)
                            .append(plain(job.wage() == 0 ? ", bez dodatku do pensji."
                                            : ", potem +" + job.wage() + "e do każdej wypłaty.")
                                    .formatted(Formatting.DARK_GRAY)));
            lore.add(line(full ? "Oba miejsca zajęte. Najpierw wyłącz jeden."
                            : can ? (owned ? "Kliknij, żeby włączyć." : "Kliknij, żeby nauczyć.")
                            : "Nie stać cię.",
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
                plain("Ekipa").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Stań tam, gdzie ma być praca, i wpisz", Formatting.GRAY),
                line("/crew hire. Robotnik pracuje w kwadracie", Formatting.GRAY),
                line("wokół tego miejsca i wszystko wkłada", Formatting.GRAY),
                line("do najbliższej skrzyni.", Formatting.GRAY),
                line("Każdy ma swoje miejsce. Przenieś je", Formatting.GRAY),
                line("kompasem, stojąc gdzie chcesz.", Formatting.GRAY),
                Text.empty(),
                line("DWA ZAWODY NARAZ. Potrzebujesz trzeciej", Formatting.WHITE),
                line("rzeczy? Wyłącz jeden albo zatrudnij kogoś.", Formatting.WHITE),
                line("Szkolenie kosztuje z góry ORAZ podnosi", Formatting.WHITE),
                line("pensję, dopóki jest WŁĄCZONE.", Formatting.WHITE),
                Text.empty(),
                line("Shift+LPM wyłącza zawód albo obniża", Formatting.GOLD),
                line("Tempo/Zasięg -- pensja od razu spada.", Formatting.GOLD),
                line("Powrót w górę zawsze za darmo.", Formatting.GOLD),
                Text.empty(),
                line("Pracują, kiedy jesteś gdzie indziej,", Formatting.GRAY),
                line("o ile jesteś zalogowany.", Formatting.GRAY),
                line("Wylogujesz się - oni też kończą.", Formatting.GRAY),
                Text.empty(),
                line("Za niezapłaconą wypłatę dostajesz", Formatting.DARK_GRAY),
                line("ostrzeżenie. Po " + TrapCrew.GRACE_PACKETS + " odchodzą --", Formatting.DARK_GRAY),
                line("ale ekipa zapisuje się przy wyjściu.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack hireTag() {
        boolean room = crew.size() < TrapCrew.MAX_HANDS;
        boolean can = room && TrapMarket.wealthOf(boss) >= TrapCrew.HIRE_COST;
        ItemStack tag = new ItemStack(can ? Items.EMERALD : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Zatrudnij kogoś").formatted(can ? Formatting.GREEN
                        : Formatting.DARK_GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(TrapCrew.HIRE_COST + "e, potem " + TrapCrew.WAGE
                        + "e co pięć minut.", Formatting.GRAY),
                line(crew.size() + " z " + TrapCrew.MAX_HANDS + " miejsc zajętych.",
                        Formatting.DARK_GRAY),
                Text.empty(),
                line(!room ? "Nie masz już miejsca."
                                : can ? "Kliknij, żeby zatrudnić TAM, GDZIE STOISZ."
                                : "Nie stać cię.",
                        can ? Formatting.YELLOW : Formatting.DARK_GRAY))));
        return tag;
    }

    /**
     * One button for "they aren't working and I don't care why".
     *
     * A lead is the closest thing the game has to a picture of a whip, and it
     * reads at a glance in a row that is otherwise crops and tools.
     *
     * It says WHICH one, like the fire button always has. "Zagoń go" -- whip
     * HIM -- was a pronoun with five possible referents on a board where four
     * of them are alive and one is a corpse, and the whole point of clicking
     * it is usually the corpse.
     */
    private ItemStack whipTag(TrapCrew.Card card, int nth) {
        boolean gone = !card.present();
        ItemStack tag = new ItemStack(Items.LEAD);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain((gone ? "Postaw nowego robotnika " : "Zagoń robotnika ") + (nth + 1))
                        .formatted(gone ? Formatting.RED : Formatting.YELLOW,
                                Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Wraca na swoje miejsce i kończy", Formatting.GRAY));
        lore.add(line("przerwę, na której akurat był.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(gone
                ? line("Ten zginął. Kliknięcie postawi", Formatting.RED)
                : line("Przydaje się, gdy utknął za", Formatting.DARK_GRAY));
        lore.add(gone
                ? line("nowego, już wyszkolonego.", Formatting.RED)
                : line("ścianą albo gdzieś odszedł.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Za darmo. Klikaj ile chcesz.", Formatting.YELLOW));
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
                plain("Zapisane ekipy").formatted(Formatting.AQUA, Formatting.BOLD)
                        .append(plain(saved == 0 ? "" : "  " + saved)
                                .formatted(Formatting.WHITE)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Zapisz, kto gdzie pracuje i co umie,", Formatting.GRAY),
                line("a potem odkup całość później.", Formatting.GRAY),
                Text.empty(),
                line("/crew save <name>", Formatting.GREEN),
                line("/crew plans", Formatting.GREEN),
                line("/crew load <name>", Formatting.GREEN),
                line("/crew forget <name>", Formatting.DARK_GRAY),
                Text.empty(),
                line("Jeśli odejdą przez brak wypłat, ekipa", Formatting.DARK_GRAY),
                line("zapisuje się pod nazwą \"" + TrapCrew.WALKOUT + "\".",
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
                plain("Pracuj tutaj").formatted(here ? Formatting.DARK_GRAY
                        : Formatting.AQUA, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Jego miejsce pracy przenosi się tam,", Formatting.GRAY));
        lore.add(line("gdzie stoisz, i on razem z nim.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Teraz:  ", Formatting.DARK_GRAY)
                .append(plain(card.spot()).formatted(Formatting.WHITE)));
        lore.add(line("Ty:  ", Formatting.DARK_GRAY)
                .append(plain(boss.getBlockPos().getX() + " " + boss.getBlockPos().getY()
                        + " " + boss.getBlockPos().getZ()).formatted(Formatting.WHITE)));
        lore.add(Text.empty());
        lore.add(line(here ? "Już tu stoisz." : "Kliknij, żeby go przenieść.",
                here ? Formatting.DARK_GRAY : Formatting.YELLOW));
        lore.add(line("Zapomina stare łóżko i skrzynię.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /**
     * Round the clock, for a price.
     *
     * The premium is only a quarter, and that is not the real cost: the wage
     * clock turns while they work, so a hand on nights already charges twice
     * as many packets an hour. What the quarter buys is the ASKING, and it is
     * what stops this being a switch everybody flips once and forgets.
     */
    private ItemStack nightsTag(TrapCrew.Card card) {
        boolean on = card.nights();
        ItemStack tag = new ItemStack(on ? Items.CLOCK : Items.RED_BED);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(on ? "Nocna zmiana" : "Tylko za dnia")
                        .formatted(on ? Formatting.GOLD : Formatting.WHITE, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line(on ? "Pracuje po ciemku i nigdy nie" : "O zmroku szuka łóżka na",
                Formatting.GRAY));
        lore.add(line(on ? "kładzie się spać." : "działce i kładzie się spać.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Pensja  ", Formatting.DARK_GRAY)
                .append(plain(card.wage() + "e").formatted(Formatting.RED))
                .append(plain(on ? "  (+" + Math.round((TrapCrew.NIGHT_RATE - 1) * 100)
                        + "% za noce)" : "").formatted(Formatting.DARK_GRAY)));
        lore.add(line(on ? "Licznik chodzi całą noc, więc"
                : "Licznik staje o zmroku, więc noc", Formatting.DARK_GRAY));
        lore.add(line(on ? "płacisz około dwa razy więcej wypłat."
                : "nic cię nie kosztuje.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line(on ? "Kliknij, by wrócić do dnia." : "Kliknij, by przestawić na noce.",
                Formatting.YELLOW));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack wages() {
        int payroll = TrapCrew.payrollOf(boss);
        ItemStack tag = new ItemStack(Items.CLOCK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Suma pensji").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(payroll + "e co pięć minut", Formatting.RED),
                line("czyli około " + payroll * 12 + "e na godzinę", Formatting.DARK_GRAY),
                Text.empty(),
                line("Masz przy sobie " + TrapMarket.wealthOf(boss) + "e.",
                        Formatting.GRAY),
                line("Pensje schodzą z ekwipunku i portfela,", Formatting.DARK_GRAY),
                line("gdziekolwiek jesteś.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack fireTag(int nth) {
        ItemStack tag = new ItemStack(Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Zwolnij robotnika " + (nth + 1))
                        .formatted(Formatting.RED, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Nic nie wraca. Ani opłata za najem,", Formatting.GRAY),
                line("ani koszty szkolenia.", Formatting.GRAY),
                Text.empty(),
                line("Shift+LPM, żeby potwierdzić.", Formatting.YELLOW))));
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
            boolean pace = index == PACE_SLOT;
            answer(type == SlotActionType.QUICK_MOVE
                    ? TrapCrew.drop(boss, card.index(), pace)
                    : TrapCrew.buy(boss, card.index(), null, pace));
            return;
        }
        if (index == WHIP_SLOT) {
            // In chat, unlike every other refusal here. The rest of the board
            // greys out what will not work, so "nothing happened" is already
            // explained by the item you clicked. The whip is never greyed out
            // -- it is free -- so its no is the only one on this screen a
            // player has to actually READ, and the action bar is a grey line
            // under a chest window nobody is looking at the bottom of.
            answer(TrapCrew.whip(boss, card.index()), false);
            return;
        }
        if (index == MOVE_SLOT) {
            answer(TrapCrew.move(boss, card.index(), boss.getBlockPos()));
            return;
        }
        if (index == NIGHTS_SLOT) {
            answer(TrapCrew.nights(boss, card.index()));
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
                boss.sendMessage(Text.literal("Shift+LPM, jeśli na pewno.")
                        .formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            }
        }
    }

    private void answer(String no) {
        answer(no, true);
    }

    /** @param overlay action bar, or false for chat when the reason must be read. */
    private void answer(String no, boolean overlay) {
        if (no == null) {
            click(SoundEvents.ENTITY_VILLAGER_WORK_FARMER, 1.0F);
        } else {
            boss.sendMessage(Text.literal(no).formatted(Formatting.GRAY), overlay);
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
