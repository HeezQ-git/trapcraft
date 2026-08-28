package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game reference, as a vanilla written book -- no client mod, no
 * textures, and it survives being dropped in a chest for the next person.
 *
 * Every NUMBER on these pages is read from the constant that actually governs
 * it: grades from Quality, the curing window from DryingRackBlock, tolerance
 * from ToleranceStatusEffect, heat from TrapHeat, breeding pairs from
 * Strain.hybridOf. Prose is hand-written, figures are not. Retune anything and
 * the book retunes with it, so it can never quietly start lying -- which is the
 * whole point of it being a knowledge base rather than a leaflet.
 */
public final class TrapGuide {
    /**
     * Where the wiki lives.
     *
     * Published by the workflow in .github/workflows from the site/ folder,
     * which tools/gen_wiki.py builds out of this same source -- so the page a
     * player opens from here and the book they get from /guide are quoting the
     * same constants and cannot disagree.
     */
    public static final String WIKI = "https://heezq-git.github.io/trapcraft/";

    private TrapGuide() {
    }

    /**
     * One command, five books.
     *
     * Plain Brigadier literals rather than an argument with a
     * SuggestionProvider: literals tab-complete and produce a sensible error
     * on a typo for free, and three separate top-level commands was already
     * two too many to remember.
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                dispatcher.register(CommandManager.literal("guide")
                        .executes(context -> menu(context.getSource()))
                        .then(CommandManager.literal("grower")
                                .executes(context -> give(context.getSource(), createWeed())))
                        .then(CommandManager.literal("refiner")
                                .executes(context -> give(context.getSource(), createCoca())))
                        .then(CommandManager.literal("chemist")
                                .executes(context -> give(context.getSource(), createPoppy())))
                        .then(CommandManager.literal("habit")
                                .executes(context -> give(context.getSource(), createHabit())))
                        .then(CommandManager.literal("street")
                                .executes(context -> give(context.getSource(), createStreet())))
                        .then(CommandManager.literal("crew")
                                .executes(context -> give(context.getSource(), createCrew())))
                        .then(CommandManager.literal("casino")
                                .executes(context -> give(context.getSource(), createCasino())))
                        .then(CommandManager.literal("city")
                                .executes(context -> give(context.getSource(), createCity())))
                        .then(CommandManager.literal("housing")
                                .executes(context -> give(context.getSource(),
                                        createHousing())))
                        .then(CommandManager.literal("police")
                                .executes(context -> give(context.getSource(),
                                        createPolice())))
                        .then(CommandManager.literal("fires")
                                .executes(context -> give(context.getSource(),
                                        createFires())))));
        registerWiki();
    }

    /**
     * /wiki -- the whole thing, on a page, clickable.
     *
     * A separate top-level command rather than a branch of /guide, because
     * somebody who wants the website is not browsing a list of books and
     * should not have to learn that it lives under one.
     */
    private static void registerWiki() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                dispatcher.register(CommandManager.literal("wiki")
                        .executes(context -> {
                            context.getSource().sendFeedback(() -> link(), false);
                            return 1;
                        })));
    }

    /**
     * The clickable line.
     *
     * Underlined and coloured because an unstyled link does not read as one,
     * and carrying its own hover text because the client's "are you sure you
     * want to open this website" prompt shows the raw URL -- somebody should
     * know where they are going before that appears, not because of it.
     */
    private static MutableText link() {
        return Text.empty()
                .append(Text.literal("Poradnik terenowy\n")
                        .formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("  Cała gra opisana w jednym miejscu.\n")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("  " + WIKI)
                        .formatted(Formatting.GREEN, Formatting.UNDERLINE)
                        .styled(style -> style
                                .withClickEvent(new net.minecraft.text.ClickEvent.OpenUrl(
                                        java.net.URI.create(WIKI)))
                                .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(
                                        Text.literal("Otwórz w przeglądarce")))))
                .append(Text.literal("\n  Wolisz książki: ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("/guide").formatted(Formatting.GREEN)
                        .styled(style -> style.withClickEvent(
                                new net.minecraft.text.ClickEvent.RunCommand("/guide"))));
    }

    /** Bare /guide lists them rather than erroring at you. */
    private static int menu(net.minecraft.server.command.ServerCommandSource source) {
        source.sendFeedback(() -> Text.empty()
                .append(Text.literal("Poradniki Trap House\n").formatted(Formatting.DARK_GREEN, Formatting.BOLD))
                .append(pick("grower", "marihuana: uprawa, suszenie, skręty"))
                .append(pick("refiner", "kokaina: liście, pasta, proszek"))
                .append(pick("chemist", "heroina: mak, krok po kroku"))
                .append(pick("habit", "nałóg, głód, klienci na ulicy"))
                .append(pick("street", "rynek, sklep, zlecenia, hazard"))
                .append(pick("crew", "najmowanie ludzi i ich pensje"))
                .append(pick("casino", "prowadzenie kasyna"))
                .append(pick("city", "kasa miasta, podatki, sklepy"))
                .append(pick("housing", "domy, klasy domów i czynsz"))
                .append(pick("police", "policja, przestępczość, mandaty"))
                .append(pick("fires", "pożary, remiza, wozy"))
                .append(Text.literal("  /wiki").formatted(Formatting.GOLD)
                        .styled(style -> style.withClickEvent(
                                new net.minecraft.text.ClickEvent.RunCommand("/wiki")))
                        .append(Text.literal("  to samo na stronie WWW\n")
                                .formatted(Formatting.DARK_GRAY))), false);
        return 1;
    }

    private static MutableText pick(String type, String blurb) {
        return Text.literal("  /guide " + type)
                .formatted(Formatting.GREEN)
                .styled(style -> style
                        .withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/guide " + type))
                        .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(
                                Text.literal("Weź książkę: " + type))))
                .append(Text.literal("  " + blurb + "\n").formatted(Formatting.DARK_GRAY));
    }

    private static int give(net.minecraft.server.command.ServerCommandSource source, ItemStack book) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendFeedback(() -> Text.literal("tylko dla graczy"), false);
            return 0;
        }
        if (!player.giveItemStack(book)) {
            player.dropItem(book, false);
        }
        return 1;
    }

    /**
     * Two books, not one.
     *
     * They're separate product lines with separate mechanics, and a single
     * volume had grown to 19 pages where half was irrelevant to whatever you
     * were actually doing. Splitting also means each stays inside a length
     * anybody will actually read.
     */
    public static ItemStack createWeed() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        cover(pages);
        growing(pages);
        grading(pages);
        curing(pages);
        rolling(pages);
        methods(pages);
        baked(pages);
        strains(pages);
        breeding(pages);
        heat(pages);
        checking(pages);
        crew(pages);
        network(pages);
        supply(pages);
        return book("Poradnik hodowcy", pages);
    }

    public static ItemStack createCoca() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        cocaCover(pages);
        coca(pages);
        return book("Poradnik rafinera", pages);
    }

    /**
     * The third product line, and the only one long enough to need its own
     * volume rather than a section of somebody else's.
     */
    public static ItemStack createPoppy() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        poppyCover(pages);
        poppy(pages);
        return book("Poradnik chemika", pages);
    }

    /**
     * Split from all three product books on purpose.
     *
     * The habit is not a feature of weed, or of coca, or of the poppy -- it is
     * the thing all three feed, and it works the same way whichever one you
     * are on. Filing it under one of them would have made it look like that
     * one's problem, and the whole point is that it is not.
     */
    public static ItemStack createHabit() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        habitCover(pages);
        habit(pages);
        return book("Nałóg", pages);
    }

    /**
     * The fourth book: the people who work for you.
     *
     * Split out for the same reason the casino was. The crew used to be three
     * pages in the middle of the grower's handbook, back when a hand did one
     * thing and cost one number. It is now a ladder, a patch and five jobs
     * that each move the wage, and that is a decision worth ten pages -- but
     * only if somebody can find them.
     */
    public static ItemStack createCrew() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(Text.empty()
                .append(title("EKIPA"))
                .append(Text.literal("\nksiążka brygadzisty\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Najęty robotnik pracuje na wyznaczonej działce "
                        + "i bierze wypłatę. Nie zapłacisz - odchodzi.\n\n"))
                .append(hint("Uprawa: /guide grower"))));
        crewBook(pages);
        return book("Ekipa", pages);
    }

    /**
     * The sixth book: the public half.
     *
     * The vault, what it charges, what it buys, and the shops that pay into
     * it. The houses moved to {@link #createHousing} when this reached
     * thirty-three pages -- they are a different job, done by the same person,
     * and one book covering both was one nobody read to the end of.
     */
    public static ItemStack createCity() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(Text.empty()
                .append(title("MIASTO"))
                .append(Text.literal("\nkasa miejska\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Jakie podatki pobiera miasto, na co je wydaje "
                        + "i jak działają sklepy.\n\n"))
                .append(hint("Domy: /guide housing"))));
        cityBook(pages);
        return book("Miasto", pages);
    }

    /**
     * The tenth book: the office with a dial on it.
     *
     * Its own volume rather than six more pages of MIASTO, and for the reason
     * the houses got theirs: the city book is already thirty-odd pages, and a
     * police force is a job somebody DOES -- set the budget, build the cells,
     * watch the blotter -- rather than a rate they read once. Both halves are
     * in here, the force and what it is out there answering, because reading
     * about one without the other tells you nothing.
     */
    /** Every page below reads its numbers off {@link TrapFires}. */
    public static ItemStack createFires() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(Text.empty()
                .append(title("STRAŻ POŻARNA"))
                .append(Text.literal("\nremiza\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Nalot to twoja wina, złodziej to wina miasta. "
                        + "Pożar nie jest niczyją.\n\n"))
                .append(hint("Komisariat: /guide police"))));

        pages.add(page(Text.empty()
                .append(title("1. CO SIĘ PALI\n\n"))
                .append(body("Domy z lokatorem i sklepy -- tylko tam, "
                        + "gdzie ktoś akurat jest.\n\n"))
                .append(body("Jeden pożar naraz.\n\n"))
                .append(hint("Żaden blok nie znika. Nic nie spłonie."))));

        pages.add(page(Text.empty()
                .append(title("2. ILE MASZ CZASU\n\n"))
                .append(body(BURN_SECONDS + " sekund.\n\n"))
                .append(body("Potem: dom traci nastrój i lokator idzie "
                        + "do szpitala, a sklep traci część kasy.\n\n"))
                .append(hint("Czynsz na tydzień, nie budynek."))));

        pages.add(page(Text.empty()
                .append(title("3. WIADRO\n\n"))
                .append(body("Stań przy pożarze z WIADREM WODY. Gasi od "
                        + "razu i zostaje puste wiadro.\n\n"))
                .append(body("Zawsze działa, nawet bez remizy.\n\n"))
                .append(hint("Remiza kupuje to, żeby cię tam nie było."))));

        pages.add(page(Text.empty()
                .append(title("4. REMIZA\n\n"))
                .append(body("Zrób blok remizy i postaw go w gotowym "
                        + "budynku, tak jak szpital.\n\n"))
                .append(body("Podłoga: " + TrapFires.MIN_FLOOR + " kratek\n"))
                .append(body("Zamknięta, z wyjazdem\n"))
                .append(body("Wszędzie światło\n"))
                .append(body("Skrzynia na sprzęt"))));

        pages.add(page(Text.empty()
                .append(title("4b. WOZY\n\n"))
                .append(body("Jeden wóz na " + TrapFires.FLOOR_PER_ENGINE
                        + " kratek podłogi, najwyżej " + TrapFires.MAX_ENGINES + ".\n\n"))
                .append(body("Wyjeżdżają do " + TrapFires.REACH + " bloków.\n\n"))
                .append(hint("Większy garaż to więcej wozów naraz."))));

        pages.add(page(Text.empty()
                .append(title("5. KTO PŁACI\n\n"))
                .append(body("Miasto: " + TrapFires.CALLOUT + "e za wyjazd, "
                        + "ze skarbca.\n\n"))
                .append(body("Pusta kasa to wóz, który nie wyjeżdża.\n\n"))
                .append(hint("Dlatego skarbiec ma mieć zapas."))));

        pages.add(page(Text.empty()
                .append(title("6. GDZIE BUDOWAĆ\n\n"))
                .append(body("W środku miasta, nie na skraju. Wóz jedzie "
                        + "tyle, ile ma do przejechania.\n\n"))
                .append(hint("/fires pokazuje, co się pali i kto jedzie."))));
        return book("Straż pożarna", pages);
    }

    /**
     * How long a fire burns, in seconds, for the page above.
     *
     * Read off {@link TrapFires} rather than typed, which is the rule this
     * whole file exists to keep: retune the fire and the book retunes with it.
     */
    private static final int BURN_SECONDS = TrapFires.BURNS_SECONDS;

    public static ItemStack createPolice() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(Text.empty()
                .append(title("PRAWO I PORZĄDEK"))
                .append(Text.literal("\nkomisariat\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Miasto kradnie samo sobie. Tu jest napisane, "
                        + "kto ma temu zapobiec i ile to kosztuje.\n\n"))
                .append(hint("Kasa miasta: /guide city"))));
        policeBook(pages);
        return book("Prawo i porządek", pages);
    }

    /** Every page below reads its numbers off {@link TrapPolice}. */
    private static void policeBook(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. KOMISARIAT\n\n"))
                .append(body("Zrób blok komisariatu, wejdź do gotowego "
                        + "budynku i postaw go w środku.\n\n"))
                .append(hint("Sprawdza pokój tak jak szpital."))));

        pages.add(page(Text.empty()
                .append(title("1b. WYMAGANIA\n\n"))
                .append(body("Cele: " + TrapPolice.MIN_CELLS + " łóżka\n"))
                .append(body("Podłoga: " + TrapPolice.MIN_FLOOR + " kratek\n"))
                .append(body("Zamknięty, z drzwiami\n"))
                .append(body("Wszędzie światło\n"))
                .append(body("Skrzynia na zbrojownię\n\n"))
                .append(hint("Zbudowany, nie wykopany."))));

        pages.add(page(Text.empty()
                .append(title("1c. CO ROBI CELA\n\n"))
                .append(body("Każde łóżko to jedna cela.\n\n"))
                .append(body("Cela to też jeden etat. Miasto nie obsadzi "
                        + "więcej funkcjonariuszy, niż ma cel.\n\n"))
                .append(hint("Chcesz większy patrol - dostaw łóżka."))));

        pages.add(page(Text.empty()
                .append(title("2. BUDŻET\n\n"))
                .append(body("Suwak jest przy SKARBCU MIASTA, nie na "
                        + "komisariacie.\n\n"))
                .append(body("LPM +" + TrapPolice.BUDGET_STEP + "e, PPM -"
                        + TrapPolice.BUDGET_STEP + "e. Do "
                        + TrapPolice.MAX_BUDGET + "e dziennie.\n\n"))
                .append(hint("To rada uchwala budżet."))));

        pages.add(page(Text.empty()
                .append(title("2b. ILU ICH BĘDZIE\n\n"))
                .append(body("Jeden funkcjonariusz kosztuje "
                        + TrapPolice.WAGE + "e dziennie.\n\n"))
                .append(body("Płacisz " + TrapPolice.WAGE * 4 + "e - masz "
                        + "czterech, o ile są cele.\n\n"))
                .append(hint("Pensje wracają do miasta przez sklepy."))));

        pages.add(page(Text.empty()
                .append(title("2c. WYPOSAŻENIE\n\n"))
                .append(body("Każde " + TrapPolice.GEAR_AT + "e budżetu to "
                        + "jeden stopień, do " + TrapPolice.TOP_GEAR + ".\n\n"))
                .append(body("Wyżej: szybsi, dalej widzą, mocniej biją "
                        + "i więcej wytrzymają.\n\n"))
                .append(hint("Straż miejska daje stopień gratis."))));

        pages.add(page(Text.empty()
                .append(title("2d. PUSTA KASA\n\n"))
                .append(body("Miasto płaci tyle, ile ma. Brakuje - część "
                        + "patrolu zostaje w domu.\n\n"))
                .append(warn("Nieopłacona komenda to jutrzejsze "
                        + "włamania."))));

        pages.add(page(Text.empty()
                .append(title("3. PATROL\n\n"))
                .append(body("Funkcjonariusze chodzą po mieście: od domu "
                        + "do sklepu, od sklepu do skarbca.\n\n"))
                .append(hint("Trzymają się swojego komisariatu."))));

        pages.add(page(Text.empty()
                .append(title("3b. POTWORY\n\n"))
                .append(body("Co zobaczą wrogiego w zasięgu, to biją "
                        + "pałką.\n\n"))
                .append(body("Zombie potrafi ich zabić. Komenda wystawi "
                        + "kogoś na miejsce poległego.\n\n"))
                .append(hint("Lepsze wyposażenie = dłużej żyją."))));

        pages.add(page(Text.empty()
                .append(title("3c. NAPAD NA MIASTO\n\n"))
                .append(body("Czasem z drogi przychodzi banda grabieżców "
                        + "prosto na mieszkańców.\n\n"))
                .append(body("Większe miasto i gorętszy handel = więcej "
                        + "ich przyjdzie.\n\n"))
                .append(warn("Po to płaci się komendzie."))));

        pages.add(page(Text.empty()
                .append(title("3d. KUSZA\n\n"))
                .append(body("Od " + TrapPolice.SHOOT_AT + ". stopnia wyposażenia "
                        + "funkcjonariusz dostaje kuszę.\n\n"))
                .append(body("Strzela na " + Math.round(TrapPolice.SHOOT_RANGE)
                        + " kratek. Niżej ma samą pałkę.\n\n"))
                .append(hint("Do sprawcy się nie strzela, jego się zakuwa."))));

        pages.add(page(Text.empty()
                .append(title("4. PRZESTĘPCZOŚĆ\n\n"))
                .append(body("Miasto samo produkuje przestępstwa. Nie "
                        + "przychodzą z zewnątrz.\n\n"))
                .append(hint("/crime pokazuje, co je napędza."))));

        MutableText kinds = Text.empty().append(title("4b. RODZAJE\n\n"));
        for (TrapCrime.Kind kind : TrapCrime.Kind.values()) {
            kinds.append(body(kind.display() + "  " + kind.weight() + "%\n"));
        }
        pages.add(page(kinds.append(Text.literal("\n"))
                .append(hint("Zabójstwa są rzadkie i nocne."))));

        pages.add(page(Text.empty()
                .append(title("4c. CO JE NAPĘDZA\n\n"))
                .append(body("Ludność - więcej ludzi, więcej spraw\n"))
                .append(body("Bieda - +" + Math.round(TrapMath.CRIME_HARDSHIP_LIFT * 100)
                        + "%\n"))
                .append(body("Heat - +" + Math.round(TrapMath.CRIME_HEAT_LIFT * 100) + "%\n"))
                .append(body("Noc - x" + TrapMath.NIGHT_CRIME + "\n\n"))
                .append(hint("Napraw domy, a spadnie samo."))));

        pages.add(page(Text.empty()
                .append(title("4d. ILE TEGO JEST\n\n"))
                .append(body("W mieście 20 osób mniej więcej raz na pół godziny gry.\n\n"))
                .append(body("Sufit to " + TrapMath.CRIME_CEILING + " dziennie, cokolwiek "
                        + "by się działo.\n\n"))
                .append(hint("/crime pokazuje aktualne tempo."))));

        pages.add(page(Text.empty()
                .append(title("4e. CO TRACISZ\n\n"))
                .append(body("Kradzież i włamanie zabierają pieniądze ze "
                        + "SKRZYNKI i z KASY sklepu.\n\n"))
                .append(warn("Zbieraj czynsz. Pełna skrzynka to cel."))));

        pages.add(page(Text.empty()
                .append(title("4f. ROZBÓJ\n\n"))
                .append(body("Napadnięty lokator ląduje w szpitalu, tak "
                        + "samo jak ugryziony.\n\n"))
                .append(warn("Bez szpitala może nie przeżyć."))));

        pages.add(page(Text.empty()
                .append(title("5. POŚCIG\n\n"))
                .append(body("Sprawca ucieka z miejsca zdarzenia. Ma "
                        + "czerwoną nazwę.\n\n"))
                .append(body("Biegnie szybciej niż patrol bez kasy i "
                        + "wolniej niż opłacony.\n\n"))
                .append(warn("Tu widać, za co płacisz."))));

        pages.add(page(Text.empty()
                .append(title("5b. ZATRZYMANIE\n\n"))
                .append(body("Złapany oddaje, co zabrał, i idzie do celi "
                        + "na kilka dni.\n\n"))
                .append(body("Grzywna trafia do kasy miasta.\n\n"))
                .append(hint("Cele pełne - wychodzi za kaucją."))));

        pages.add(page(Text.empty()
                .append(title("5c. UMORZENIE\n\n"))
                .append(body("Jeśli nikt go nie dopadnie, po kilku minutach "
                        + "sprawa jest umorzona.\n\n"))
                .append(warn("Pieniądze przepadają na dobre."))));

        pages.add(page(Text.empty()
                .append(title("6. MANDATY\n\n"))
                .append(body("Funkcjonariusz podchodzi też do CIEBIE, "
                        + "jeśli masz się czym tłumaczyć.\n\n"))
                .append(hint("Nie zabiera towaru i nie aresztuje."))));

        pages.add(page(Text.empty()
                .append(title("6b. CZEGO SZUKAJĄ\n\n"))
                .append(body("Ponad " + TrapPolice.LOOKS_AWAY + " sztuk "
                        + "towaru przy sobie\n"))
                .append(body("Świeży heat\n"))
                .append(body("Zaległość w urzędzie\n\n"))
                .append(hint("Skręt w kieszeni nikogo nie obchodzi."))));

        pages.add(page(Text.empty()
                .append(title("6c. JAK UNIKAĆ\n\n"))
                .append(body("Nie noś zapasu przez miasto. Pierz kasę. "
                        + "Płać domiary.\n\n"))
                .append(hint("Jeden mandat na kilka minut, nie więcej."))));

        pages.add(page(Text.empty()
                .append(title("7. KOMENDY\n\n"))
                .append(body("/police - budżet, etaty, komisariaty\n"))
                .append(body("/raid <gracz> - banda na kogoś (op)\n"))
                .append(body("/crime - statystyki i otwarte sprawy\n"))
                .append(body("/city - rachunki miasta\n\n"))
                .append(hint("Tablica na komisariacie mówi to samo."))));
    }

    /** Every page below reads its numbers off {@link HomeSurvey}. */
    private static void cityBook(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. SKARBIEC\n\n"))
                .append(body("Zrób skarbiec i postaw go. To zakłada "
                        + "miasto.\n\n"))
                .append(hint("Może być tylko jeden na serwerze."))));

        pages.add(page(Text.empty()
                .append(title("1b. PO CO ON JEST\n\n"))
                .append(body("Dopóki go nie ma, nikt nie płaci podatków "
                        + "i nie da się zarejestrować domu.\n\n"))
                .append(hint("PPM otwiera kasę miasta."))));

        pages.add(page(Text.empty()
                .append(title("2. KASA MIASTA\n\n"))
                .append(body("Każdy zapłacony podatek trafia do wspólnej "
                        + "kasy.\n\n"))
                .append(body("Wypłacić z niej może KAŻDY gracz.\n\n"))
                .append(warn("Wszyscy widzą, kto co wziął."))));

        pages.add(page(Text.empty()
                .append(title("2b. RACHUNKI\n\n"))
                .append(body("Komenda /city pokazuje stan kasy i historię "
                        + "wpłat oraz wypłat.\n\n"))
                .append(hint("Nic tu nie jest tajne."))));

        pages.add(page(Text.empty()
                .append(title("3. PODATKI\n\n"))
                .append(body("Miasto pobiera trzy rodzaje:\n\n"))
                .append(body("- od zakupów w sklepach\n"))
                .append(body("- od zarobków\n"))
                .append(body("- od zakładów w kasynie\n"))));

        pages.add(page(Text.empty()
                .append(title("3b. OD ZAKUPÓW\n\n"))
                .append(body("Osobne stawki dla jedzenia, materiałów "
                        + "i towarów luksusowych.\n\n"))
                .append(body("Płaci kupujący, przy kasie."))));

        pages.add(page(Text.empty()
                .append(title("3c. KTO NIE PŁACI\n\n"))
                .append(body("Klienci podchodzący na ulicy i twoi "
                        + "dilerzy nie płacą podatku.\n\n"))
                .append(warn("Dlatego ulica daje brudną kasę."))));

        pages.add(page(Text.empty()
                .append(title("4. SKĄD STAWKI\n\n"))
                .append(body("Zmieniają się same, co kilka dni. Serwer "
                        + "ogłasza to na czacie.\n\n"))
                .append(hint("Nikt tym nie steruje ręcznie."))));

        pages.add(page(Text.empty()
                .append(title("4b. CO NA NIE WPŁYWA\n\n"))
                .append(body("Pusta kasa miasta = podatki w górę.\n\n"))
                .append(body("Pełna kasa = podatki w dół.\n\n"))
                .append(hint("Każdy ma swój limit górny i dolny."))));

        pages.add(page(Text.empty()
                .append(title("5. INWESTYCJE\n\n"))
                .append(body("Za pieniądze z kasy miasta kupuje się "
                        + "ulepszenia dla całego miasta.\n\n"))
                .append(body("Kupione raz, zostają na zawsze.\n\n"))
                .append(hint("Kupić może każdy. Wszyscy dostają info."))));

        pages.add(page(Text.empty()
                .append(title("6. DOTACJE\n\n"))
                .append(body("Z kasy miasta można też dorzucić pieniądze "
                        + "do kasyna.\n\n"))
                .append(body("Wybierasz kasyno, ustawiasz kwotę, "
                        + "wysyłasz.\n\n"))
                .append(warn("To prezent. Wraca tylko podatek."))));

        // Off the enum. The prose version named four and went stale the day a
        // fifth was added, which is the whole failure this book reads its
        // numbers from constants to avoid.
        MutableText works = Text.empty()
                .append(title("7. CO MOŻNA KUPIĆ\n\n"));
        for (TrapCity.Work work : TrapCity.Work.values()) {
            works.append(body(work.display() + " " + work.cost() + "e\n"));
        }
        pages.add(page(works));

        pages.add(page(Text.empty()
                .append(title("8. SZPITAL\n\n"))
                .append(body("Jeśli zombie dopadnie lokatora, ten zamienia "
                        + "się w zombie i tracisz go.\n\n"))
                .append(warn("Szpital go leczy i ratuje."))));

        pages.add(page(Text.empty()
                .append(title("8b. JAK ZAŁOŻYĆ\n\n"))
                .append(body("Zrób blok szpitala, wejdź do gotowego "
                        + "budynku i postaw go w środku.\n\n"))
                .append(hint("Działa tak jak skrzynka pocztowa: sprawdza "
                        + "pomieszczenie."))));

        pages.add(page(Text.empty()
                .append(title("8c. WYMAGANIA\n\n"))
                .append(body("Łóżka: " + TrapHospitals.MIN_BEDS + "\n"))
                .append(body("Podłoga: " + TrapHospitals.MIN_FLOOR
                        + " kratek\n"))
                .append(body("Zamknięte, z drzwiami\n"))
                .append(body("Wszędzie światło\n\n"))
                .append(hint("Zbudowane, nie wykopane w ziemi."))));

        pages.add(page(Text.empty()
                .append(title("8d. ILU NARAZ\n\n"))
                .append(body("Tylu, ile jest łóżek. Chcesz leczyć więcej "
                        + "osób jednocześnie - dostaw łóżka.\n\n"))
                .append(hint("Reszta czeka w kolejce."))));

        pages.add(page(Text.empty()
                .append(title("8e. KOSZT LECZENIA\n\n"))
                .append(body("Kasa miasta płaci lekarzom "
                        + TrapHospitals.FEE + "e dziennie za pacjenta.\n\n"))
                .append(body("Chory leży " + TrapHospitals.STAY_DAYS
                        + " dzień i przez ten czas nic nie zarabia.\n\n"))
                .append(warn("Pusta kasa = brak leczenia."))));

        pages.add(page(Text.empty()
                .append(title("8f. BEZ LECZENIA\n\n"))
                .append(body("Nieleczony chory umiera po "
                        + TrapHospitals.LOST_DAYS + " dniach.\n\n"))
                .append(warn("Pilnuj, żeby kasa miasta nie była pusta."))));

        pages.add(page(Text.empty()
                .append(title("9. URZĄD SKARBOWY\n\n"))
                .append(body("Urząd porównuje to, co naprawdę zarobiłeś, "
                        + "z tym, co zgłosiłeś.\n\n"))
                .append(body("Legalne są zarobki ze sklepów i pensje."))));

        pages.add(page(Text.empty()
                .append(title("9b. DOMIAR\n\n"))
                .append(body("Jeśli dziennie masz ponad " + TrapLaw.LOOKS_AWAY
                        + "e z nieznanego źródła, dostajesz rachunek.\n\n"))
                .append(body("Płacisz komendą: /law pay\n\n"))
                .append(warn("Dług = policja ma cię na oku."))));

        pages.add(page(Text.empty()
                .append(title("10. BRUDNA KASA\n\n"))
                .append(body("Za towar sprzedany na ulicy dostajesz "
                        + "BRUDNE SZMARAGDY.\n\n"))
                .append(warn("Żaden sklep ich nie przyjmie. To jeszcze "
                        + "nie są pieniądze."))));

        pages.add(page(Text.empty()
                .append(title("10b. PRZENOSZENIE\n\n"))
                .append(body("Dziewięć brudnych szmaragdów daje blok, "
                        + "a blok rozkłada się z powrotem na dziewięć.\n\n"))
                .append(hint("Duże wypłaty od razu przychodzą w blokach."))));

        pages.add(page(Text.empty()
                .append(title("11. PRALNIA\n\n"))
                .append(body("Zrób BĘBEN PRALNICZY. Zamienia brudne "
                        + "szmaragdy na zwykłe.\n\n"))
                .append(body("Kliknij PPM trzymając brudną kasę.\n\n"))
                .append(hint("Bloki też wchodzą."))));

        pages.add(page(Text.empty()
                .append(title("11b. ILE NARAZ\n\n"))
                .append(body("Minimum " + LaundryBlock.MIN_LOAD
                        + ", maksimum " + LaundryBlock.MAX_LOAD
                        + " na jedno pranie.\n\n"))
                .append(body("8 sztuk: " + LaundryBlock.washLabel(8) + "\n"))
                .append(body("Pełny wsad: "
                        + LaundryBlock.washLabel(LaundryBlock.MAX_LOAD)))));

        pages.add(page(Text.empty()
                .append(title("11c. PROWIZJA\n\n"))
                .append(body("Pranie zabiera do "
                        + Math.round(TrapLaw.WASH_CUT * 100)
                        + "% wsadu.\n\n"))
                .append(warn("Za każdym razem inaczej. Nie da się tego "
                        + "przewidzieć."))));

        pages.add(page(Text.empty()
                .append(title("11d. WAŻNE\n\n"))
                .append(body("Dorzucenie kasy w trakcie prania zeruje "
                        + "licznik czasu.\n\n"))
                .append(body("Wrzuć wszystko naraz i odejdź.\n\n"))
                .append(hint("Mało miejsca? Postaw drugi bęben."))));

        pages.add(page(Text.empty()
                .append(title("11e. UWAGA\n\n"))
                .append(body("Wyprane pieniądze urząd widzi jako "
                        + "twój dochód.\n\n"))
                .append(warn("Jeśli wyprałeś więcej, niż utargowały twoje "
                        + "sklepy, i tak dostaniesz rachunek."))));

        pages.add(page(Text.empty()
                .append(title("12. SKLEP\n\n"))
                .append(body("Postaw KASĘ SKLEPOWĄ. To już jest sklep.\n\n"))
                .append(body("Półki postawione w promieniu "
                        + TrapShops.REACH + " bloków same się do niej "
                        + "podłączą.\n\n"))
                .append(hint("Nie trzeba ich niczym łączyć."))));

        pages.add(page(Text.empty()
                .append(title("12b. TOWAR\n\n"))
                .append(body("Otwórz półkę i włóż do niej przedmioty. "
                        + "To, co w niej leży, jest na sprzedaż.\n\n"))
                .append(hint("Pusta półka nic nie sprzedaje."))));

        pages.add(page(Text.empty()
                .append(title("12c. KASA\n\n"))
                .append(body("Jedna kasa obsługuje cały budynek.\n\n"))
                .append(body("Otwórz ją, żeby wybrać utarg i ustawić "
                        + "ceny.\n\n"))
                .append(hint("Nie stawiaj drugiej kasy obok."))));

        pages.add(page(Text.empty()
                .append(title("12d. CENY\n\n"))
                .append(body("Tanio: przychodzi więcej ludzi, ale każdy "
                        + "zostawia mniej.\n\n"))
                .append(body("Drogo: mniej ludzi, ale każdy płaci "
                        + "więcej.\n\n"))
                .append(hint("Sprawdź obie opcje i policz."))));

        pages.add(page(Text.empty()
                .append(title("13. SPRZEDAWCA\n\n"))
                .append(body("Przy kasie możesz nająć sprzedawcę za "
                        + TrapShops.KEEPER_WAGE + "e dziennie.\n\n"))
                .append(body("Sklep przyciąga wtedy dużo więcej "
                        + "klientów.\n\n"))
                .append(warn("Pusta kasa - odchodzi."))));

        pages.add(page(Text.empty()
                .append(title("13b. PO CO ON JEST\n\n"))
                .append(body("Sklep ze sprzedawcą handluje dalej, kiedy "
                        + "jesteś gdzie indziej na serwerze.\n\n"))
                .append(body("Idź kopać - sklep zarabia."))));

        pages.add(page(Text.empty()
                .append(title("13c. GRANICA\n\n"))
                .append(body("Po twoim wylogowaniu sklep NIE handluje.\n\n"))
                .append(warn("Sprzedawca nie zastąpi cię offline."))));

        pages.add(page(Text.empty()
                .append(title("14. TOWAR Z UPRAWY\n\n"))
                .append(body("Na półkach możesz sprzedawać też skręty, "
                        + "susz i proszek.\n\n"))
                .append(body("Płacą " + Math.round(TrapShops.LEGAL_RATE * 100)
                        + "% ceny ulicznej.\n\n"))
                .append(hint("Czyli mniej niż na ulicy."))));

        pages.add(page(Text.empty()
                .append(title("14b. DLACZEGO WARTO\n\n"))
                .append(body("Ta kasa jest CZYSTA: nie trzeba jej prać, "
                        + "urząd ją akceptuje.\n\n"))
                .append(body("Nie ściąga też policji.\n\n"))
                .append(warn("Wolniejszy zarobek, ale bezpieczny."))));

        pages.add(page(Text.empty()
                .append(title("15. KTO KUPUJE\n\n"))
                .append(body("Lokatorzy z twoich domów.\n\n"))
                .append(body("Więcej domów = więcej klientów w sklepie.\n\n"))
                .append(warn("Brak domów = brak klientów."))));

        pages.add(page(Text.empty()
                .append(title("15b. SKĄD MAJĄ KASĘ\n\n"))
                .append(body("Lokatorzy chodzą do pracy i dostają "
                        + "pensje. To jedyne źródło pieniędzy w "
                        + "mieście.\n\n"))
                .append(hint("Całość: /guide housing"))));

        pages.add(page(Text.empty()
                .append(title("15c. OBIEG KASY\n\n"))
                .append(body("Pensja -> podatek do miasta -> czynsz dla "
                        + "ciebie -> reszta w twoich sklepach.\n\n"))
                .append(hint("Dlatego domy są opłacalne."))));

        pages.add(page(Text.empty()
                .append(title("16. WPŁATY\n\n"))
                .append(body("Do skarbca możesz dołożyć własne "
                        + "pieniądze.\n\n"))
                .append(body("Kliknij \"Wpłać\". PPM wrzuca wszystko "
                        + "naraz.\n\n"))
                .append(hint("Z tej kasy kupuje się inwestycje."))));

        pages.add(page(Text.empty()
                .append(title("17. POZIOMY\n\n"))
                .append(body("Każdą inwestycję można ulepszać do poziomu "
                        + TrapCity.TOP_TIER + ".\n\n"))
                .append(body("Kolejny poziom kosztuje więcej niż "
                        + "poprzedni.\n\n"))
                .append(hint("Przychodnia II to lepsza Przychodnia."))));

        pages.add(page(Text.empty()
                .append(title("17b. W TRAKCIE\n\n"))
                .append(body("Stary poziom działa cały czas, kiedy "
                        + "zbierasz na następny.\n\n"))
                .append(hint("Nic nie jest wyłączane na czas budowy."))));

        pages.add(page(Text.empty()
                .append(title("18. OPŁATY STAŁE\n\n"))
                .append(body("Posiadanie kosztuje:\n\n"))
                .append(body("Sklep: " + TrapCity.SHOP_RATE + "e dziennie\n"))
                .append(body("Dom: " + TrapCity.HOUSE_RATE
                        + "e dziennie za każdą klasę\n"))));

        pages.add(page(Text.empty()
                .append(title("18b. PRZYKŁAD\n\n"))
                .append(body("Dom klasy 4 kosztuje cię "
                        + (TrapCity.HOUSE_RATE * 4) + "e dziennie.\n\n"))
                .append(warn("Idzie z twojej kieszeni do kasy miasta."))));

        pages.add(page(Text.empty()
                .append(title("19. KLUB NOCNY\n\n"))
                .append(body("Postaw BUDKĘ KLUBOWĄ w pomieszczeniu. "
                        + "To już jest klub.\n\n"))
                .append(body("Nic tu nie jest sprawdzane. Buduj jak "
                        + "chcesz, zostaw miejsce do stania."))));

        pages.add(page(Text.empty()
                .append(title("19b. WSTĘP\n\n"))
                .append(body("Bilet od " + TrapClubs.DOOR[0] + "e do "
                        + TrapClubs.DOOR[TrapClubs.DOOR.length - 1]
                        + "e.\n\n"))
                .append(body("Tanio: klub się zapełnia.\nDrogo: pustki.\n\n"))
                .append(hint("Kliknij budkę, żeby zmienić cenę."))));

        pages.add(page(Text.empty()
                .append(title("19c. ILE USTAWIĆ\n\n"))
                .append(body("Zależy, ilu ludzi mieszka w mieście.\n\n"))
                .append(body("Mało mieszkańców - tanie bilety, inaczej "
                        + "nikt nie przyjdzie.\n\n"))
                .append(hint("Dużo mieszkańców - można podnieść."))));

        pages.add(page(Text.empty()
                .append(title("19d. KTO PRZYCHODZI\n\n"))
                .append(body("Twoi właśni lokatorzy. Przychodzą po "
                        + "zmroku.\n\n"))
                .append(warn("Ten, kto jest w klubie, nie siedzi w domu, "
                        + "w sklepie ani przy automacie."))));

        pages.add(page(Text.empty()
                .append(title("19e. HAŁAS\n\n"))
                .append(body("Pełny klub robi hałas i ściąga uwagę "
                        + "policji na okolicę.\n\n"))
                .append(warn("Nie buduj klubu obok plantacji."))));
    }

    /**
     * The seventh book: the half of the city that people live in.
     *
     * Split out of the landlord's handbook once that reached thirty-three
     * pages, which is well past the length this mod's own comment calls
     * readable. The city half is now the purse, the duties and the shops; this
     * is the houses, the people in them, and where their money comes from.
     *
     * Every number is read off {@link HomeSurvey} for the reason the rest of
     * the book is: the grade is worked out in one place and printed in three,
     * and the prose version of this table listed grades two to five and
     * stopped, so three of them went undocumented the day they were added.
     */
    public static ItemStack createHousing() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(Text.empty()
                .append(title("MIESZKANIA"))
                .append(Text.literal("\nrejestr domów\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Jak zbudować dom, w którym ktoś zamieszka "
                        + "i zacznie ci płacić czynsz.\n\n"))
                .append(hint("Podatki: /guide city"))));
        housingBook(pages);
        return book("Mieszkania", pages);
    }

    /**
     * Every page below reads its numbers off {@link HomeSurvey}.
     *
     * Written in plain sentences, which took a second pass to get to. The
     * first version was in this book's house style -- clipped, allusive, "it
     * walks the walls and puts it on the register" -- and that reads as
     * atmosphere to somebody who already knows the system and as a riddle to
     * everybody else. A reference is not the place to be terse. Pages are
     * free; a player rereading one is not.
     */
    private static void housingBook(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. PO CO DOMY\n\n"))
                .append(body("Do domu, który zbudujesz, wprowadza się "
                        + "mieszkaniec.\n\n"))
                .append(body("Płaci ci czynsz KAŻDEGO DNIA.\n\n"))
                .append(hint("Za darmo, bez twojej pracy."))));

        pages.add(page(Text.empty()
                .append(title("1b. I JESZCZE\n\n"))
                .append(body("Mieszkaniec chodzi do pracy i wydaje "
                        + "pensję w twoich sklepach.\n\n"))
                .append(warn("Więcej domów = więcej klientów."))));

        pages.add(page(Text.empty()
                .append(title("2. KROK 1: SKRZYNKA\n\n"))
                .append(body("Zrób SKRZYNKĘ POCZTOWĄ.\n\n"))
                .append(body("Wejdź DO ŚRODKA gotowego pokoju i postaw "
                        + "ją na podłodze.\n\n"))
                .append(hint("Miasto sprawdzi pokój i zapisze go."))));

        pages.add(page(Text.empty()
                .append(title("2b. KROK 2\n\n"))
                .append(body("Kucnij i kliknij PPM pustą ręką w "
                        + "skrzynkę - podniesiesz ją.\n\n"))
                .append(body("Postaw ją na zewnątrz, przy drzwiach.\n\n"))
                .append(hint("Dom zostaje tam, gdzie był."))));

        pages.add(page(Text.empty()
                .append(title("2c. ZGUBIŁEŚ SKRZYNKĘ?\n\n"))
                .append(body("Postaw nową w środku - zastąpi starą.\n\n"))
                .append(body("Postawiona na zewnątrz sama podłączy się "
                        + "do najbliższego domu bez skrzynki."))));

        pages.add(page(Text.empty()
                .append(title("2d. USUWANIE DOMU\n\n"))
                .append(body("Stań w domu i wpisz:\n\n"))
                .append(body("/homes demolish\n\n"))
                .append(warn("Kasuje dom z rejestru. Lokatorzy znikają."))));

        pages.add(page(Text.empty()
                .append(title("3. MUSI BYĆ SZCZELNY\n\n"))
                .append(body("Ściany, podłoga i dach - bez dziur.\n\n"))
                .append(body("Jeśli wpada światło dzienne przez szparę, "
                        + "to jeszcze nie jest dom.\n\n"))
                .append(hint("Zamknięte drzwi liczą się jak ściana."))));

        pages.add(page(Text.empty()
                .append(title("4. PIĘĆ WYMOGÓW\n\n"))
                .append(body("1. Brak dziur\n"))
                .append(body("2. " + HomeSurvey.MIN_FLOOR
                        + " kratek podłogi\n"))
                .append(body("3. Łóżko\n"))
                .append(body("4. Drzwi na zewnątrz\n"))
                .append(body("5. Światło\n"))));

        pages.add(page(Text.empty()
                .append(title("4b. UWAGA\n\n"))
                .append(warn("Brak choćby jednego z tych pięciu i dom "
                        + "NIE zostanie zarejestrowany.\n\n"))
                .append(hint("Skrzynka powie ci, czego brakuje."))));

        pages.add(page(Text.empty()
                .append(title("5. KLASA DOMU\n\n"))
                .append(body("Każdy dom dostaje klasę od 1 do "
                        + HomeSurvey.TOP_TIER + ".\n\n"))
                .append(body("Wyższa klasa = wyższy czynsz dla ciebie."))));

        pages.add(page(Text.empty()
                .append(title("5b. I LEPSI LUDZIE\n\n"))
                .append(body("W lepszym domu mieszkają lepiej opłacani "
                        + "ludzie.\n\n"))
                .append(body("Mają więcej kasy na zakupy u ciebie.\n\n"))
                .append(hint("Skrzynka mówi, co poprawić."))));

        pages.add(page(Text.empty()
                .append(title("6. JAK PODNIEŚĆ KLASĘ\n\n"))
                .append(body("Za dobre budowanie dostajesz punkty.\n\n"))
                .append(body("Każde 2 punkty to jedna klasa wyżej.\n\n"))
                .append(hint("Do zdobycia jest " + HomeSurvey.topPoints()
                        + " punktów."))));

        pages.add(page(Text.empty()
                .append(title("6b. ZA CO PUNKTY\n\n"))
                .append(body("- buduj z obrobionych bloków, nie z ziemi "
                        + "i kamienia\n"))
                .append(body("- używaj wielu różnych bloków\n"))
                .append(body("- oświetl każdy kąt\n"))));

        pages.add(page(Text.empty()
                .append(title("6c. MEBLE\n\n"))
                .append(body("Punkty dają też sprzęty w środku:\n\n"))
                .append(body("stół, skrzynia, piec, stragan, okno.\n\n"))
                .append(hint("Im więcej różnych, tym lepiej."))));

        pages.add(page(Text.empty()
                .append(title("7. WIELKOŚĆ\n\n"))
                .append(body("Mały pokój dostanie niską klasę, choćbyś "
                        + "wykończył go idealnie.\n\n"))
                .append(warn("Metraż to twardy limit."))));

        pages.add(page(Text.empty()
                .append(title("7b. CO SIĘ LICZY\n\n"))
                .append(body("Liczy się każde piętro.\n\n"))
                .append(body("Kratka pod skrzynią też się liczy.\n\n"))
                .append(hint("Balkon również."))));

        // Padded rather than joined with spaces: 9 and 560 are different
        // widths, so a plain concatenation walks the right-hand column
        // sideways down the page and stops reading as a table at all.
        MutableText lid = Text.empty()
                .append(title("8. ILE PODŁOGI\n\n"))
                .append(body("klasa  kratki\n"));
        for (int step = 0; step < HomeSurvey.FLOOR_STEPS.length; step++) {
            lid.append(body(String.format("  %d     %4d\n",
                    step + 1, HomeSurvey.FLOOR_STEPS[step])));
        }
        pages.add(page(lid));

        pages.add(page(Text.empty()
                .append(title("9. ILU LOKATORÓW\n\n"))
                .append(body("Liczą się trzy rzeczy:\n\n"))
                .append(body("- jedno łóżko na osobę\n"))
                .append(body("- " + HomeSurvey.FLOOR_PER_HEAD
                        + " kratki podłogi na osobę\n"))
                .append(body("- klasa domu\n"))));

        pages.add(page(Text.empty()
                .append(title("9b. KTÓRE DECYDUJE\n\n"))
                .append(warn("To NAJMNIEJSZE z tych trzech.\n\n"))
                .append(body("Dziesięć łóżek w małym pokoju nie da ci "
                        + "dziesięciu lokatorów.\n\n"))
                .append(hint("Dokładaj wszystko po równo."))));

        pages.add(page(Text.empty()
                .append(title("10. WPROWADZKA\n\n"))
                .append(body("Znalezienie chętnego trwa kilka dni. Na "
                        + "klasę 1 czeka się prawie tydzień.\n\n"))
                .append(hint("Im wyższa klasa, tym szybciej ktoś przyjdzie."))));

        pages.add(page(Text.empty()
                .append(title("10b. POTEM\n\n"))
                .append(body("Od wprowadzki płacą czynsz codziennie.\n\n"))
                .append(body("Czterech lokatorów to cztery czynsze.\n\n"))
                .append(hint("Kasa leci sama."))));

        pages.add(page(Text.empty()
                .append(title("10c. WYPROWADZKI\n\n"))
                .append(body("Czasem lokator po prostu się wyprowadza, "
                        + "nawet z dobrego domu.\n\n"))
                .append(body("Dzień wcześniej zostawia list w "
                        + "skrzynce.\n\n"))
                .append(warn("Nic na to nie poradzisz. Wynajmij komuś "
                        + "innemu."))));

        pages.add(page(Text.empty()
                .append(title("11. ICH PENSJE\n\n"))
                .append(body("Lokatorzy chodzą do pracy i raz dziennie "
                        + "dostają wypłatę.\n\n"))
                .append(body("Kolejność: podatek dla miasta, potem "
                        + "czynsz dla ciebie.\n\n"))
                .append(hint("Resztę wydają w sklepach i kasynach."))));

        pages.add(page(Text.empty()
                .append(title("12. WIĘKSZY = LEPSZY\n\n"))
                .append(body("Klasa ustala podstawę. Dodatkowa podłoga "
                        + "dokłada do niej bonus.\n\n"))
                .append(body("Duża klasa 4 daje więcej niż mała klasa 4 "
                        + "- i z czynszu, i z pensji."))));

        pages.add(page(Text.empty()
                .append(title("12b. ALE\n\n"))
                .append(warn("Klasa 5 zawsze bije każdą klasę 4.\n\n"))
                .append(hint("Najpierw klasa, potem metraż."))));

        MutableText owed = Text.empty()
                .append(title("13. TWÓJ CZYNSZ\n\n"))
                .append(body("klasa mały  duży\n"));
        MutableText paid = Text.empty()
                .append(title("14. ICH PENSJA\n\n"))
                .append(body("klasa mały  duży\n"));
        for (int tier = 1; tier <= HomeSurvey.TOP_TIER; tier++) {
            int lo = HomeSurvey.FLOOR_STEPS[tier - 1];
            int hi = tier >= HomeSurvey.TOP_TIER ? HomeSurvey.topFloor()
                    : HomeSurvey.FLOOR_STEPS[tier] - 1;
            owed.append(body(String.format("  %d   %4s %5s\n", tier,
                    HomeSurvey.rentDue(tier, HomeSurvey.MOOD_MAX, 1, lo) + "e",
                    HomeSurvey.rentDue(tier, HomeSurvey.MOOD_MAX, 1, hi) + "e")));
            paid.append(body(String.format("  %d   %4s %5s\n", tier,
                    HomeSurvey.wageDue(tier, 1, lo) + "e",
                    HomeSurvey.wageDue(tier, 1, hi) + "e")));
        }
        pages.add(page(owed.append(hint("\nNa osobę, na dzień."))));
        pages.add(page(paid.append(hint("\nNa osobę, na dzień."))));

        pages.add(page(Text.empty()
                .append(title("15. ZADOWOLENIE\n\n"))
                .append(body("Ciemne kąty i rozwalający się dom psują "
                        + "nastrój lokatora.\n\n"))
                .append(body("Niezadowolony płaci MNIEJSZY czynsz, "
                        + "a potem się wyprowadza."))));

        pages.add(page(Text.empty()
                .append(title("15b. SKARGI\n\n"))
                .append(body("Lokatorzy zostawiają listy.\n\n"))
                .append(body("Otwórz skrzynkę pocztową i przeczytaj "
                        + "je - piszą wprost, co jest nie tak.\n\n"))
                .append(hint("Napraw to, zanim odejdą."))));

        pages.add(page(Text.empty()
                .append(title("16. NIE PRZY PLANTACJI\n\n"))
                .append(body("Nikt nie chce mieszkać obok uprawy "
                        + "konopi ani koki.\n\n"))
                .append(body("Mała uprawa: lokatorzy są nieszczęśliwi.\n"))
                .append(body("Duża: wyprowadzają się.\n"))));

        pages.add(page(Text.empty()
                .append(title("16b. WNIOSEK\n\n"))
                .append(warn("Trzymaj plantację z dala od domów.\n\n"))
                .append(hint("Osobna wyspa albo kilkaset bloków dalej."))));

        pages.add(page(Text.empty()
                .append(title("17. KONTROLE\n\n"))
                .append(body("Miasto sprawdza każdy dom co kilka "
                        + "minut.\n\n"))
                .append(body("Rozwalisz ścianę albo zabierzesz łóżko - "
                        + "klasa spada natychmiast."))));

        pages.add(page(Text.empty()
                .append(title("17b. PODGLĄD\n\n"))
                .append(body("Komenda /homes pokazuje wszystkie domy "
                        + "na serwerze.\n\n"))
                .append(hint("Także cudze."))));

        pages.add(page(Text.empty()
                .append(title("18. DWA DOMY\n\n"))
                .append(body("Dwa domy nie mogą dzielić tego samego "
                        + "pokoju.\n\n"))
                .append(body("Mieszkania obok siebie są OK. Jedno nad "
                        + "drugim też."))));

        pages.add(page(Text.empty()
                .append(title("18b. ZASIĘG\n\n"))
                .append(body("Dom sięga " + HomeSurvey.SPAN
                        + " bloków od swojej skrzynki.\n\n"))
                .append(hint("Dalsze pokoje już się nie liczą."))));

        pages.add(page(Text.empty()
                .append(title("19. SPRZEDAŻ WPROST\n\n"))
                .append(body("Kliknij lokatora PPM z pustą ręką - "
                        + "powie, czego chce.\n\n"))
                .append(body("Weź to do ręki, kliknij znowu, a on "
                        + "zapłaci."))));

        pages.add(page(Text.empty()
                .append(title("19b. ALBO SAMI\n\n"))
                .append(body("Lokatorzy i tak sami chodzą po twoich "
                        + "sklepach.\n\n"))
                .append(hint("Sprzedaż z ręki to tylko dodatek."))));
    }

    /**
     * The fifth book: running a floor.
     *
     * Split out because the casino stopped being a machine you place and
     * became a business with staff, running costs, a reputation and a
     * maintenance schedule -- and eleven pages of that buried in the middle of
     * the street handbook is eleven pages nobody finds.
     */
    public static ItemStack createCasino() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(Text.empty()
                .append(title("KASYNO"))
                .append(Text.literal("\nporadnik właściciela\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Jak otworzyć kasyno, ile ono kosztuje "
                        + "i skąd bierze się zysk.\n\n"))
                .append(hint("Zasady gier: /guide street"))));
        casino(pages);
        return book("Kasyno", pages);
    }

    private static void casino(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. OTWARCIE\n\n"))
                .append(body("Zrób przedmiot "))
                .append(item("Licencja kasyna"))
                .append(body(" i kliknij nim PPM w powietrze.\n\n"))
                .append(hint("Kto trzyma licencję, ten jest właścicielem."))));

        pages.add(page(Text.empty()
                .append(title("1b. NAZWA\n\n"))
                .append(body("Chcesz własną nazwę kasyna? Nazwij licencję "
                        + "na kowadle ZANIM jej użyjesz.\n\n"))
                .append(hint("Później się nie da."))));

        pages.add(page(Text.empty()
                .append(title("2. PODŁĄCZANIE\n\n"))
                .append(body("Weź licencję do ręki i kliknij PPM w "
                        + "automat. Od tej chwili należy do kasyna.\n\n"))
                .append(hint("Kliknij drugi raz, żeby odłączyć."))));

        pages.add(page(Text.empty()
                .append(title("2b. PRZEPŁYWY\n\n"))
                .append(body("Wszystko, co gracz przegra, wpada do "
                        + "twojego skarbca kasyna.\n\n"))
                .append(body("Wszystko, co wygra, jest z niego "
                        + "wypłacane."))));

        pages.add(page(Text.empty()
                .append(title("3. ZAPAS W KASIE\n\n"))
                .append(body("Automat nie przyjmie zakładu, którego nie "
                        + "byłby w stanie wypłacić.\n\n"))
                .append(warn("Pusty skarbiec = niskie limity = nikt nie "
                        + "gra."))));

        pages.add(page(Text.empty()
                .append(title("3b. ILE TRZYMAĆ\n\n"))
                .append(body("Licz " + TrapMath.FLOAT_PER_MACHINE
                        + "e na każdy automat.\n\n"))
                .append(body("Ta kwota ustala limit stołu i mocno wpływa "
                        + "na twoją reputację.\n\n"))
                .append(hint("Pięć automatów: "
                        + (TrapMath.FLOAT_PER_MACHINE * 5) + "e."))));

        pages.add(page(Text.empty()
                .append(title("4. KOSZTY STAŁE\n\n"))
                .append(body("Automat z graczem kosztuje "
                        + TrapMath.MACHINE_UPKEEP + "e co 30 sekund.\n\n"))
                .append(warn("Ciemny kosztuje ćwierć tego. Płacisz "
                        + "za niego dalej, ale budowanie na zapas nie "
                        + "zabija sali."))));

        pages.add(page(Text.empty()
                .append(title("4b. HARACZ\n\n"))
                .append(body("Gang zabiera "
                        + Math.round(TrapMath.PROTECTION_RATE * 100)
                        + "% wszystkiego, co zostało obstawione.\n\n"))
                .append(body("Niezależnie od tego, kto wygrał.\n\n"))
                .append(warn("Trzy niezapłacone raty i przychodzą."))));

        pages.add(page(Text.empty()
                .append(title("5. BAR\n\n"))
                .append(body("Podłącz "))
                .append(item("Bar"))
                .append(body(" licencją i wypełnij go jedzeniem.\n\n"))
                .append(body("Każdy gość dostaje jedną kolejkę.\n\n"))
                .append(hint("Jeden przedmiot z półki to ok. "
                        + TrapMath.SERVINGS_PER_ITEM + " porcji."))));

        pages.add(page(Text.empty()
                .append(title("5b. POJEMNOŚĆ\n\n"))
                .append(body("Bar mieści " + TrapMath.BAR_SLOTS
                        + " stacków.\n\n"))
                .append(body("Podłącz drugi bar, żeby mieć dwa razy "
                        + "tyle miejsca.\n\n"))
                .append(warn("Pusty bar: goście wychodzą po jednej grze."))));

        pages.add(page(Text.empty()
                .append(title("5c. CO WSTAWIĆ\n\n"))
                .append(body("Cokolwiek jadalnego.\n\n"))
                .append(body("Ale najlepiej TWÓJ WŁASNY TOWAR z "
                        + "plantacji.\n\n"))
                .append(hint("Po to właśnie jest uprawa."))));

        pages.add(page(Text.empty()
                .append(title("5d. DLACZEGO TOWAR\n\n"))
                .append(body("Goście siedzą przy automatach dużo dłużej "
                        + "niż po chlebie.\n\n"))
                .append(body("I odzyskują o "
                        + Math.round(TrapMath.SERVED_EDGE_PRODUCT * 100)
                        + " punktów mniej wygranych.\n\n"))
                .append(hint("Czysty zysk dla kasyna."))));

        pages.add(page(Text.empty()
                .append(title("6. GRACZE\n\n"))
                .append(body("Do kasyna przychodzą wieśniacy i grają za "
                        + "własne pieniądze.\n\n"))
                .append(body("Tłoczno po zmroku. W południe są w "
                        + "pracy.\n\n"))
                .append(hint("/floor pokazuje, co się dzieje na sali."))));

        pages.add(page(Text.empty()
                .append(title("6b. STAWKI\n\n"))
                .append(body("Pusta sala: pojedyncze osoby, ale grają "
                        + "grubo - do " + TrapMath.PUNTER_MAX_STAKE
                        + "e.\n\n"))
                .append(body("Pełna sala: po " + TrapMath.PUNTER_MIN_STAKE
                        + "e, ale bardzo dużo osób."))));

        pages.add(page(Text.empty()
                .append(title("6c. LIMIT SALI\n\n"))
                .append(warn("Jeden automat obsługuje jedną osobę "
                        + "naraz.\n\n"))
                .append(hint("Więcej gości = potrzeba więcej automatów."))));

        pages.add(page(Text.empty()
                .append(title("7. REPUTACJA\n\n"))
                .append(body("Reputacja rośnie, gdy masz:\n\n"))
                .append(body("- różne rodzaje gier\n"))
                .append(body("- pełny skarbiec\n"))
                .append(body("- wolny automat dla wchodzącego\n"))));

        pages.add(page(Text.empty()
                .append(title("7b. CO JĄ PSUJE\n\n"))
                .append(warn("Najbardziej szkodzi kolejka przy "
                        + "drzwiach.\n\n"))
                .append(body("Reputacja spada dwa razy szybciej, niż "
                        + "rośnie.\n\n"))
                .append(hint("Dostaw automaty, zanim zrobi się tłok."))));

        pages.add(page(Text.empty()
                .append(title("8. STALI BYWALCY\n\n"))
                .append(body("To osobny licznik. Rośnie, kiedy w kasynie "
                        + "ktoś gra.\n\n"))
                .append(body("Spada, kiedy sala stoi pusta.\n\n"))
                .append(warn("Pół godziny bez gry i licznik jest "
                        + "wyzerowany."))));

        pages.add(page(Text.empty()
                .append(title("8b. PO CO ONI\n\n"))
                .append(body("Im wyższy licznik, tym częściej ktoś "
                        + "wchodzi i tym dłużej zostaje.\n\n"))
                .append(hint("Nikt nie utrzyma go na 100."))));

        pages.add(page(Text.empty()
                .append(title("9. ZUŻYCIE\n\n"))
                .append(body("Zepsuty automat nie bierze zakładów.\n\n"))
                .append(body("Napraw PRAWYM klikiem, w ręce "))
                .append(item("Młot górniczy"))
                .append(body(".\n"))
                .append(hint("Lewym go rozwalisz."))));

        // Was "Naprawa przed awarią jest tańsza niż po", which is simply
        // untrue -- the bill is wear x REPAIR_COST_PER_POINT and nothing else,
        // so a hundred repairs at 1 cost exactly what one at 100 costs. The
        // hint was teaching the treadmill: walk the floor, hit everything,
        // get told "Naprawione" every time, come back and do it again.
        pages.add(page(Text.empty()
                .append(title("9b. NAPRAWA\n\n"))
                .append(body("Płaci skarbiec: "
                        + TrapMath.REPAIR_COST_PER_POINT
                        + "e za punkt zużycia.\n\n"))
                .append(hint("Wcześniej nie taniej. Poniżej "
                        + TrapMath.JAM_FROM + " automat gra normalnie."))));

        pages.add(page(Text.empty()
                .append(title("10. SZEF SALI\n\n"))
                .append(body("Koszt: " + TrapMath.PIT_BOSS_HIRE
                        + "e na start, potem " + TrapMath.PIT_BOSS_WAGE
                        + "e co takt.\n\n"))
                .append(hint("Stała pensja, nie procent."))));

        pages.add(page(Text.empty()
                .append(title("10b. PO CO ON JEST\n\n"))
                .append(body("Bez niego obsługa podkrada z kasy.\n\n"))
                .append(body("I mniej więcej co "
                        + Math.round(1 / TrapMath.CHEAT_CHANCE)
                        + " gracz oszukuje.\n\n"))
                .append(warn("Przy dużym obrocie to droższe niż pensja."))));

        pages.add(page(Text.empty()
                .append(title("11. DARMOWA KOLEJKA\n\n"))
                .append(body("Kosztuje " + TrapMath.COMP_COST_PER_MACHINE
                        + "e za automat, prosto ze skarbca.\n\n"))
                .append(body("Daje +" + TrapMath.COMP_ADDICTION
                        + " do stałych bywalców.\n\n"))
                .append(warn("I nic poza tym. To całe działanie."))));

        pages.add(page(Text.empty()
                .append(title("12. LUŹNE AUTOMATY\n\n"))
                .append(body("Na " + TrapMath.LOOSE_BEATS / 2
                        + " minut automaty wypłacają więcej, niż "
                        + "powinny.\n\n"))
                .append(warn("Przez ten czas TRACISZ pieniądze."))));

        pages.add(page(Text.empty()
                .append(title("12b. PO CO TO ROBIĆ\n\n"))
                .append(body("+" + TrapMath.LOOSE_REP_BONUS
                        + " do reputacji.\n\n"))
                .append(body("Stali bywalcy przybywają dwa razy "
                        + "szybciej.\n\n"))
                .append(hint("Reklama, za którą płacisz wypłatami."))));

        pages.add(page(Text.empty()
                .append(title("13. ILE SIĘ ZARABIA\n\n"))
                .append(body("Kasyno zabiera graczom około 3% tego, co "
                        + "obstawią.\n\n"))
                .append(warn("Utrzymanie automatów zjada większość "
                        + "tej marży."))));

        pages.add(page(Text.empty()
                .append(title("13b. TWOJA GRA\n\n"))
                .append(body("Twoje własne zakłady nie liczą się do "
                        + "statystyk kasyna.\n\n"))
                .append(body("To twoje pieniądze w kółko.\n\n"))
                .append(warn("Słaby wieczór naprawdę może wyjść na "
                        + "minus."))));
    }

    /**
     * The third book: everything that isn't a product line.
     *
     * Paranoia, the Ledger and Contracts all cut across weed and coca both, so
     * putting them in either handbook would have meant writing them twice or
     * hiding them in the wrong one.
     */
    public static ItemStack createStreet() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        streetCover(pages);
        paranoia(pages);
        ledger(pages);
        contracts(pages);
        market(pages);
        wands(pages);
        cases(pages);
        street(pages);
        return book("Poradnik uliczny", pages);
    }

    private static void streetCover(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("ULICA"))
                .append(Text.literal("\nporadnik ogólny\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Nerwy, rynek, zlecenia i hazard. Działa tak "
                        + "samo dla każdego towaru.\n\n"))
                .append(hint("Uprawa: /guide grower"))));
    }

    private static void paranoia(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. PARANOJA\n\n"))
                .append(body("Im większą masz uwagę policji, tym bardziej "
                        + "odbija ci na punkcie nerwów.\n\n"))
                .append(body("Pasek NERWY jest na górze ekranu.\n\n"))
                .append(warn("To wszystko są złudzenia. Nic realnego."))));

        // Thresholds read from the code, so retuning the meter retunes the book.
        MutableText tiers = Text.empty().append(title("1b. POZIOMY\n\n"));
        String[] words = {"dźwięki za plecami", "kroki i błyski",
                "bloki, których nie ma", "ktoś patrzy"};
        for (int tier = 0; tier < TrapParanoia.TIERS.length; tier++) {
            tiers.append(body(TrapParanoia.TIERS[tier] + ": " + words[tier] + "\n"));
        }
        pages.add(page(tiers.append(body("\n"))
                .append(hint("Skala do " + (int) TrapParanoia.MAX + "."))));

        pages.add(page(Text.empty()
                .append(title("1c. CO POMAGA\n\n"))
                .append(body("- światło dzienne\n"))
                .append(body("- pochodnie\n"))
                .append(body("- bycie na trzeźwo\n\n"))
                .append(hint("Najlepiej: drugi gracz w promieniu "
                        + TrapParanoia.COMPANY_RANGE + " bloków."))));

        pages.add(page(Text.empty()
                .append(title("1c2. LEK\n\n"))
                .append(item("Lek na nerwy"))
                .append(body(" wycisza nerwy na "
                        + TrapContent.NerveTonicItem.CALM_TICKS / 20
                        + " sekund.\n\n"))
                .append(hint("Miód, cukier, kwiatek.\nKucnij, by postawić butelkę."))));

        pages.add(page(Text.empty()
                .append(title("1d. WYŁĄCZANIE\n\n"))
                .append(body("Nie chcesz tego efektu? Wpisz:\n\n"))
                .append(item("/paranoia\n\n"))
                .append(body("Działa tylko na ciebie.\n\n"))
                .append(hint("Po respawnie i tak masz minutę spokoju."))));
    }

    private static void market(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("4. RYNEK\n\n"))
                .append(body("Postaw STRAGAN, żeby kupować i sprzedawać "
                        + "przedmioty za szmaragdy.\n\n"))
                .append(item("Wełna Wełna Wełna\nKłoda Szmar Kłoda\nKłoda Kłoda Kłoda"))));

        pages.add(page(Text.empty()
                .append(title("4a. HANDEL\n\n"))
                .append(body("LPM kupuje jedną partię. Shift+LPM cztery.\n\n"))
                .append(body("PPM sprzedaje jedną sztukę z powrotem.\n\n"))
                .append(warn("Odkupują za jakąś jedną trzecią ceny "
                        + "sprzedaży."))));

        pages.add(page(Text.empty()
                .append(title("4b. CENY\n\n"))
                .append(body("Ceny zmieniają się co 30 sekund. Stój i "
                        + "patrz, a zobaczysz ruch.\n\n"))
                .append(body("Każdy przedmiot ma własny wykres.\n\n"))
                .append(hint("Wszyscy widzą te same ceny."))));

        pages.add(page(Text.empty()
                .append(title("4b2. TWOJE ZAKUPY\n\n"))
                .append(body("Kupowanie natychmiast podbija cenę tego "
                        + "przedmiotu. Sprzedawanie ją zbija.\n\n"))
                .append(body("Wykupisz całą półkę - ostatnia partia "
                        + "będzie droższa niż pierwsza."))));

        pages.add(page(Text.empty()
                .append(title("4b3. POWRÓT DO NORMY\n\n"))
                .append(body("Ten efekt zanika z czasem.\n\n"))
                .append(hint("Wróć później, cena będzie z powrotem "
                        + "normalna."))));

        pages.add(page(Text.empty()
                .append(title("4c. INDEKS\n\n"))
                .append(body("Im więcej szmaragdów krąży po serwerze, "
                        + "tym wszystko droższe.\n\n"))
                .append(body("Wypłaty dodają szmaragdy. Wydawanie i "
                        + "przegrywanie je usuwa."))));

        pages.add(page(Text.empty()
                .append(title("4c2. SCHOWANE\n\n"))
                .append(warn("Szmaragdy w skrzyniach też się liczą.\n\n"))
                .append(hint("Chomikowanie nie ukrywa ich przed "
                        + "indeksem."))));

        pages.add(page(Text.empty()
                .append(title("4d. PORTFEL\n\n"))
                .append(item("Nić   Bryłka Nić\nSkóra Szmar  Skóra\nSkóra Skóra  Skóra\n\n"))
                .append(body("Mieści dowolną ilość szmaragdów w jednym "
                        + "slocie."))));

        pages.add(page(Text.empty()
                .append(title("4d2. OBSŁUGA\n\n"))
                .append(body("PPM otwiera portfel.\n\n"))
                .append(body("Jeden przycisk wrzuca do niego wszystkie "
                        + "szmaragdy, jakie masz przy sobie.\n\n"))
                .append(hint("Bloki liczą się po dziewięć, w obie "
                        + "strony."))));

        pages.add(page(Text.empty()
                .append(title("4d3. WAŻNE\n\n"))
                .append(body("Pieniędzmi z portfela normalnie płacisz.\n\n"))
                .append(hint("Sklepy i automaty biorą je same z "
                        + "portfela."))));

        pages.add(page(Text.empty()
                .append(title("5. WŁASNY STRAGAN\n\n"))
                .append(body("Postaw skrzynię POD swoim straganem.\n\n"))
                .append(body("Wszystko, co w niej leży, idzie na "
                        + "sprzedaż dla innych graczy.\n\n"))
                .append(hint("PPM na własnym straganie otwiera utarg."))));

        pages.add(page(Text.empty()
                .append(title("5b. PO CO TO KOMU\n\n"))
                .append(body("Lada NPC płaci ci tylko "
                        + Math.round(TrapMath.SELL_RATE * 100)
                        + "% ceny, a od kupującego bierze 100%.\n\n"))
                .append(body("To spora strata na obie strony."))));

        pages.add(page(Text.empty()
                .append(title("5c. PRZEZ STRAGAN\n\n"))
                .append(body("Kupujący płaci tylko "
                        + Math.round(TrapMath.STALL_RATE * 100) + "%.\n\n"))
                .append(body("Ty zatrzymujesz "
                        + Math.round((TrapMath.STALL_RATE
                        - TrapMath.STALL_RATE * TrapMath.STALL_FEE) * 100)
                        + "%.\n\n"))
                .append(hint("Obaj wychodzicie lepiej niż na ladzie."))));

        pages.add(page(Text.empty()
                .append(title("5d. CUDZE STRAGANY\n\n"))
                .append(body("Komenda /stalls pokazuje wszystkie stragany "
                        + "innych graczy.\n\n"))
                .append(hint("Warto zajrzeć przed zakupami u NPC."))));

        pages.add(page(Text.empty()
                .append(title("6. LADA (LEJ)\n\n"))
                .append(body("Lej z przodu straganu przyjmuje WSZYSTKO, "
                        + "nie tylko rzeczy z listy.\n\n"))
                .append(body("Wsyp wszystko i sprzedaj za jednym razem.\n\n"))
                .append(hint("Shift+LPM napełnia go szybko."))));

        pages.add(page(Text.empty()
                .append(title("6b. ILE PŁACI\n\n"))
                .append(body("Rzeczy z listy: pełna cena rynkowa.\n\n"))
                .append(body("Wszystko inne wyceniane na miejscu, po "
                        + Math.round(TrapMath.SCRAP_RATE * 100) + "%.\n\n"))
                .append(warn("Za śmieci grosze. Sprzedawaj stackami."))));

        pages.add(page(Text.empty()
                .append(title("6c. CZEGO NIE WEŹMIE\n\n"))
                .append(body("- pieniędzy\n"))
                .append(body("- pełnego portfela\n"))
                .append(body("- shulkera z zawartością\n"))
                .append(body("- zniszczonego sprzętu\n\n"))
                .append(hint("Wracają do ciebie, z powodem na czacie."))));

        pages.add(page(Text.empty()
                .append(title("7. LOKATY\n\n"))
                .append(body("Na giełdzie możesz odłożyć szmaragdy na "
                        + "dzień, trzy dni albo tydzień.\n\n"))
                .append(hint("Nie da się wypłacić przed terminem."))));

        pages.add(page(Text.empty()
                .append(title("7b. ZYSK LUB STRATA\n\n"))
                .append(body("Jeśli indeks wzrósł w tym czasie - "
                        + "dostajesz więcej.\n\n"))
                .append(warn("Jeśli spadł - dostajesz mniej. To nie jest "
                        + "gwarantowany zysk."))));

        pages.add(page(Text.empty()
                .append(title("8. HISTORIA ZAROBKÓW\n\n"))
                .append(body("Każdy zarobiony szmaragd jest zapisany "
                        + "razem ze źródłem.\n\n"))
                .append(body("/earnings - dzisiejszy dzień, wszyscy.\n\n"))
                .append(hint("Pełna historia leży w folderze świata."))));

        pages.add(page(Text.empty()
                .append(title("8b. STRONA WWW\n\n"))
                .append(body("/wiki\n\n"))
                .append(body("Wszystkie odmiany, ceny, poziomy i "
                        + "receptury na jednej stronie.\n\n"))
                .append(hint("Te same liczby co w tych książkach."))));

        pages.add(page(Text.empty()
                .append(title("9. JEDNORĘKI BANDYTA\n\n"))
                .append(body("Szafa wysoka na dwa bloki - musi mieć "
                        + "miejsce nad sobą.\n\n"))
                .append(hint("Wygrywające pola świecą. Nie świeci - "
                        + "nie ma wygranej."))));

        pages.add(page(Text.empty()
                .append(title("9b. UKŁADY\n\n"))
                .append(body("Płacą: linie, kwadraty, krzyże, gwiazdy, "
                        + "kształty Z i rogi.\n\n"))
                .append(hint("Wszystko jest podświetlane, więc widać, "
                        + "za co dostałeś."))));

        pages.add(page(Text.empty()
                .append(title("9c. KILKA NARAZ\n\n"))
                .append(body("Dwa osobne układy na jednej planszy płacą "
                        + "oba.\n\n"))
                .append(body("Trzy diamenty w poziomie i trzy gwiazdki "
                        + "w pionie to dwie wygrane."))));

        pages.add(page(Text.empty()
                .append(title("9d. WYJĄTEK\n\n"))
                .append(warn("Jeden kształt płaci raz.\n\n"))
                .append(body("Nie dostaniesz dodatkowo za linie, które "
                        + "ten kształt zawiera."))));

        pages.add(page(Text.empty()
                .append(title("9e. WYPŁATY\n\n"))
                .append(body("Około " + Math.round(TrapMath.slotWinRate(5) * 100)
                        + " zakręceń na 100 coś wypłaca - i nigdy mniej "
                        + "niż twoja stawka.\n\n"))
                .append(hint("Tęczowe szybki i fajerwerki = duża "
                        + "wygrana."))));

        pages.add(page(Text.empty()
                .append(title("9f. PRZEWAGA KASYNA\n\n"))
                .append(warn("Kasyno i tak zatrzymuje około "
                        + Math.round((1.0f - TrapMath.slotRtp(5)) * 100)
                        + "%.\n\n"))
                .append(body("Zawsze tak jest. Na dłuższą metę "
                        + "przegrywasz."))));

        pages.add(page(Text.empty()
                .append(title("9g. CZTERY ROZMIARY\n\n"))
                .append(body("Przycisk obok stawki zmienia planszę: "
                        + "2x2, 3x3, 4x4, 5x5.\n\n"))
                .append(body("Mała: szybka, płaci za pary.\n"))
                .append(body("Duża: mieści wszystkie kształty.\n"))));

        pages.add(page(Text.empty()
                .append(title("10. RULETKA\n\n"))
                .append(item("Złoto  Żelazo Złoto\nZielona Zielona Zielona\nDeska  Deska  Deska\n\n"))
                .append(body("Zielona wełna. Deski dowolne."))));

        pages.add(page(Text.empty()
                .append(title("10b. OBSTAWIANIE\n\n"))
                .append(body("Kliknij liczbę albo pole zewnętrzne, żeby "
                        + "położyć żeton. Ile chcesz.\n\n"))
                .append(body("PPM zabiera jeden żeton z powrotem.\n\n"))
                .append(hint("Przycisk żetonu ustala wartość kliknięcia."))));

        pages.add(page(Text.empty()
                .append(title("10c. WYPŁATY\n\n"))
                .append(body("Pojedyncza liczba płaci "
                        + (TrapMath.ROULETTE_STRAIGHT - 1) + " do 1.\n\n"))
                .append(body("Czerwone, czarne, parzyste, nieparzyste "
                        + "i połówki płacą 1 do 1."))));

        pages.add(page(Text.empty()
                .append(title("10d. ZERO\n\n"))
                .append(warn("Na zerze przepadają wszystkie zakłady "
                        + "zewnętrzne.\n\n"))
                .append(body("Na tym polega cała przewaga kasyna w "
                        + "ruletce."))));

        pages.add(page(Text.empty()
                .append(title("10e. KTÓRY ZAKŁAD\n\n"))
                .append(body("Każdy zakład na tym stole zwraca "
                        + Math.round(TrapMath.rouletteReturnToPlayer("red") * 100)
                        + "%.\n\n"))
                .append(body("Pojedyncza liczba czy czerwone - "
                        + "dokładnie ta sama przewaga kasyna.\n\n"))
                .append(hint("Shift+LPM na SPIN powtarza ostatni zakład."))));

        pages.add(page(Text.empty()
                .append(title("11. PLINKO\n\n"))
                .append(item("Deska Żelazo Deska\nSzkło Diament Szkło\nDeska Żelazo Deska\n\n"))
                .append(body("Wysokie na dwa bloki. Zostaw miejsce nad."))));

        pages.add(page(Text.empty()
                .append(title("11b. JAK DZIAŁA\n\n"))
                .append(body("Kulka spada przez kołki i wpada do jednej "
                        + "z dziewięciu przegródek.\n\n"))
                .append(body("Osiem odbić, każde to rzut monetą.\n\n"))
                .append(hint("Nic nie jest ustalane z góry."))));

        pages.add(page(Text.empty()
                .append(title("11c. SZANSE\n\n"))
                .append(body("Środek łapie 70 kulek na 256 i płaci "
                        + "najmniej.\n\n"))
                .append(body("Skrajne przegródki łapią po jednej na 256 "
                        + "i płacą najwięcej."))));

        pages.add(page(Text.empty()
                .append(title("12. WSPINACZKA\n\n"))
                .append(item("Żelazo Żelazo Żelazo\nZłoto  Hak    Złoto\nŻelazo Żelazo Żelazo\n\n"))
                .append(body("Sejf z trzema zamkami."))));

        pages.add(page(Text.empty()
                .append(title("12b. ZASADY\n\n"))
                .append(body("Sześć szczebli. Na każdym otwierasz jedne "
                        + "drzwi - jedne z nich są złe.\n\n"))
                .append(body("Przeżyjesz: wspinasz się dalej albo "
                        + "zabierasz kasę."))));

        pages.add(page(Text.empty()
                .append(title("12c. KIEDY PRZESTAĆ\n\n"))
                .append(body("Każdy szczebel ma DOKŁADNIE taką samą "
                        + "przewagę kasyna.\n\n"))
                .append(warn("Nie ma sprytnej wysokości. To czysta "
                        + "loteria."))));

        pages.add(page(Text.empty()
                .append(title("12d. DWIE DRABINY\n\n"))
                .append(body("Spokojna: 4 drzwi, maks. "
                        + Math.round(TrapMath.climbMultiplier(0, TrapMath.CLIMB_RUNGS))
                        + "x stawki.\n\n"))
                .append(body("Ryzykowna: 3 drzwi, maks. "
                        + Math.round(TrapMath.climbMultiplier(1, TrapMath.CLIMB_RUNGS))
                        + "x stawki.\n\n"))
                .append(hint("Ta sama średnia. Tylko większe wahania."))));

        pages.add(page(Text.empty()
                .append(title("13. RZUT MONETĄ\n\n"))
                .append(item("  -   Złoto   -\nZielona Zielona Zielona\nDeska  Deska  Deska\n\n"))
                .append(body("Obstawiasz orła, reszkę albo kant."))));

        pages.add(page(Text.empty()
                .append(title("13b. KANT\n\n"))
                .append(body("Kant płaci " + (int) TrapMath.TOSS_EDGE_PAY
                        + "x stawki.\n\n"))
                .append(warn("Wypada w około "
                        + Math.round(TrapMath.TOSS_EDGE_CHANCE * 1000) / 10.0
                        + "% rzutów."))));

        pages.add(page(Text.empty()
                .append(title("14. BLACKJACK\n\n"))
                .append(item("Papier Deska Papier\nZielona Zielona Zielona\nDeska  Deska  Deska\n\n"))
                .append(body("Dobierasz, pasujesz albo podwajasz."))));

        pages.add(page(Text.empty()
                .append(title("14b. ZASADY STOŁU\n\n"))
                .append(body("Krupier zatrzymuje się na "
                        + TrapMath.DEALER_STANDS + ".\n\n"))
                .append(warn("Blackjack płaci tu 6 do 5, a nie 3 do 2. "
                        + "To gorzej dla gracza."))));

        pages.add(page(Text.empty()
                .append(title("15. ZDRAPKI\n\n"))
                .append(body("Kup kartę i klikaj dziewięć pól w "
                        + "dowolnej kolejności.\n\n"))
                .append(body("Trzy takie same symbole płacą."))));

        pages.add(page(Text.empty()
                .append(title("15b. WYPŁATY\n\n"))
                .append(body("Cztery symbole: x"
                        + (int) TrapMath.SCRATCH_SIZES[4] + "\n"))
                .append(body("Pięć symboli: x"
                        + (int) TrapMath.SCRATCH_SIZES[5] + "\n\n"))
                .append(hint("Trzy w linii płacą podwójnie."))));

        pages.add(page(Text.empty()
                .append(title("15c. SZANSE\n\n"))
                .append(body("Około "
                        + Math.round(TrapMath.SCRATCH_MEASURED_WIN_RATE * 100)
                        + " kart na 100 coś wypłaca.\n\n"))
                .append(warn("Większość z nich zwraca MNIEJ, niż "
                        + "kosztowała karta."))));

        pages.add(page(Text.empty()
                .append(title("15d. JEDNA NAGRODA\n\n"))
                .append(body("Z karty dostajesz tylko jedną wygraną - "
                        + "tę najwyższą.\n\n"))
                .append(hint("Nagrody się nie sumują."))));

        pages.add(page(Text.empty()
                .append(title("16. KRYPTOWALUTY\n\n"))
                .append(body("Giełda ma drugie okno: sześć monet, które "
                        + "kupujesz i sprzedajesz kiedy chcesz.\n\n"))
                .append(body("Trzy poziomy ryzyka: spokojne, "
                        + "zmienne, hazardowe."))));

        pages.add(page(Text.empty()
                .append(title("16b. RYZYKO\n\n"))
                .append(warn("Te najdziksze NAPRAWDĘ potrafią spaść "
                        + "do zera.\n\n"))
                .append(warn("Na stałe. Kasa przepada."))));

        pages.add(page(Text.empty()
                .append(title("16c. BLOKADA\n\n"))
                .append(body("Kupując z blokadą, nie możesz sprzedać "
                        + "przez " + TrapCoins.LOCK_BEATS / 2 + " minut.\n\n"))
                .append(body("Po tym czasie dostajesz "
                        + Math.round(TrapCoins.LOCK_BONUS * 100)
                        + "% premii."))));

        pages.add(page(Text.empty()
                .append(title("16d. UWAGA\n\n"))
                .append(warn("Blokada nie chroni przed spadkiem do "
                        + "zera.\n\n"))
                .append(body("Jeśli moneta padnie w trakcie blokady, "
                        + "tracisz wszystko i nie możesz uciec."))));

    }


    /**
     * The wand rack.
     *
     * Every number here comes off WandItem, because the whole reason anybody
     * reads this chapter is to decide whether six figures of emeralds is worth
     * it, and a book that quotes a range the wand no longer has is worse than
     * no book.
     */
    private static void wands(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("6. RÓŻDŻKI\n\n"))
                .append(body("Najdroższa półka na rynku.\n\n"))
                .append(body("Pięć sztuk. Każda robi coś, czego nie robi "
                        + "żadne narzędzie w grze.\n\n"))
                .append(hint("Na to właśnie zbierasz."))));

        pages.add(page(Text.empty()
                .append(title("6a. PĘDU\n\n"))
                .append(body("PPM rzuca cię tam, gdzie patrzysz.\n\n"))
                .append(body("Shift+PPM to przeskok o "
                        + WandItem.BLINK_RANGE + " bloków.\n\n"))
                .append(hint("Po skoku " + WandItem.SOFT_LANDING / 20
                        + "s wolnego spadania - nie zabijesz się."))));

        pages.add(page(Text.empty()
                .append(title("6b. ŻNIW\n\n"))
                .append(body("Machnij nią stojąc na polu.\n\n"))
                .append(body("Zbiera wszystko dojrzałe w promieniu "
                        + WandItem.HARVEST_RADIUS + " i zostawia "
                        + "zasadzone.\n\n"))
                .append(hint("Nasze rośliny też."))));

        pages.add(page(Text.empty()
                .append(title("6c. ŻYŁY\n\n"))
                .append(body("Podświetla rudy w promieniu "
                        + WandItem.PROSPECT_RADIUS + " bloków.\n\n"))
                .append(body("Przez kamień.\n\n"))
                .append(hint("Widzisz je tylko ty."))));

        pages.add(page(Text.empty()
                .append(title("6d. BUDOWNICZEGO\n\n"))
                .append(body("PPM w ścianę dokłada do "
                        + WandItem.BUILDER_REACH + " takich samych "
                        + "bloków w bok.\n\n"))
                .append(body("Bierze je z twojego plecaka."))));

        pages.add(page(Text.empty()
                .append(title("6e. BURZY\n\n"))
                .append(body("Piorun tam, gdzie patrzysz. Zasięg "
                        + WandItem.STORM_RANGE + " bloków.\n\n"))
                .append(body(Math.round(WandItem.STORM_DAMAGE)
                        + " obrażeń wszystkiemu obok.\n\n"))
                .append(hint("Nie podpala. Graczy nie tyka."))));

        pages.add(page(Text.empty()
                .append(title("6f. SKĄD JE WZIĄĆ\n\n"))
                .append(body("Kup gotową na półce RÓŻDŻKI.\n\n"))
                .append(item("__ Amet Rdz\n__ Pręt Amet\nPręt __ __\n\n"))
                .append(hint("Ten sam kształt dla wszystkich pięciu."))));

        pages.add(page(Text.empty()
                .append(title("6f2. RDZEŃ\n\n"))
                .append(body("Pęd: pręt bryzy\nŻniwa: 2 jaja\nŻyły: 2 muszle\n"
                        + "Murarze: 3 kompasy\nBurza: 3 gwiazdy\n\n"))
                .append(hint("Jaja snifferów, muszle echa, kompasy "
                        + "powrotu, gwiazdy netheru."))));

        pages.add(page(Text.empty()
                .append(title("6g. UWAGA\n\n"))
                .append(warn("Sklep nie odkupuje różdżek.\n\n"))
                .append(body("Za żadne pieniądze.\n\n"))
                .append(hint("Kupujesz ją raz i jest twoja."))));

        pages.add(page(Text.empty()
                .append(title("6h. ULEPSZENIA\n\n"))
                .append(body("Każda ma trzy poziomy.\n\n"))
                .append(body("Skradanie + PPM w stół zaklęć podnosi o jeden.\n\n"))
                .append(hint("Nie zadziała, gdy różdżka stygnie."))));

        pages.add(page(Text.empty()
                .append(title("6h2. ILE TO KOSZTUJE\n\n"))
                .append(body("II: pół ceny z półki.\nIII: cała cena.\n\n"))
                .append(body("Emki z kieszeni albo z portfela.\n\n"))
                .append(hint("Burza: 60 tys., potem 120 tys."))));

        pages.add(page(Text.empty()
                .append(title("6h3. CO DAJĄ\n\n"))
                .append(body("II: -20% czasu, +25% zasięgu.\n\n"))
                .append(body("III: -40% i +50%.\n\n"))
                .append(hint("Burza zyskuje siłę, nie zasięg."))));
    }

    /**
     * The colours players actually say out loud.
     *
     * The GUI shows {@code Grade.title()} -- "Klasa wojskowa" -- because that
     * is the grade's name. In the book it is the colour, because that is what
     * anybody who has opened one of these before calls it, and because the
     * formal name plus its percentage is two characters wider than a page.
     */
    private static final String[] BANDS =
            {"Niebieski", "Fioletowy", "Różowy", "Czerwony", "Złoty"};

    /**
     * Cases and keys.
     *
     * Every number on these pages comes out of {@link CaseOdds}, for the same
     * reason the wand chapter reads WandItem: this is a chapter somebody opens
     * to decide whether to spend 22,000e, and a book quoting last month's
     * odds would be worse than no book. The names are literals -- renaming a
     * case is a thing somebody does on purpose, in one file, and noticing.
     */
    private static void cases(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("7. SKRZYNKI\n\n"))
                .append(body("Skrzynki są za darmo. Klucze nie.\n\n"))
                .append(body("Skrzynka bez klucza leży w kufrze i czeka.\n\n"))
                .append(hint("W tym cała zabawa."))));

        pages.add(page(Text.empty()
                .append(title("7a. SKĄD SKRZYNKI\n\n"))
                .append(body("Wypadają z potworów, które zabijesz.\n\n"))
                .append(body("Im groźniejszy, tym lepsza.\n\n"))
                .append(hint("Warden, Wither i smok: pewna Widmo."))));

        pages.add(page(Text.empty()
                .append(title("7b. SKĄD KLUCZE\n\n"))
                .append(body("Ze skrzyń w świecie. Wioska da dzielnicowy, "
                        + "end city widmowy.\n\n"))
                .append(body("Albo z półki KLUCZE na rynku.\n\n"))
                .append(hint("Kto zwiedza, płaci butami."))));

        pages.add(page(Text.empty()
                .append(title("7c. CZTERY POZIOMY\n\n"))
                .append(body("Klucz kosztuje:\n"))
                .append(item("Dzielnicowy "
                        + CaseOdds.Tier.STREET.keyPrice() + "e\n"
                        + "Portowy "
                        + CaseOdds.Tier.DOCKS.keyPrice() + "e\n"
                        + "Kartelu "
                        + CaseOdds.Tier.CARTEL.keyPrice() + "e\n"
                        + "Widmo "
                        + CaseOdds.Tier.PHANTOM.keyPrice() + "e\n\n"))
                .append(hint("Klucz pasuje tylko do swojej."))));

        pages.add(page(Text.empty()
                .append(title("7d. SZANSE\n\n"))
                .append(item(BANDS[0] + " " + CaseOdds.Grade.MIL_SPEC.chance() + "\n"
                        + BANDS[1] + " " + CaseOdds.Grade.RESTRICTED.chance() + "\n"
                        + BANDS[2] + " " + CaseOdds.Grade.CLASSIFIED.chance() + "\n"
                        + BANDS[3] + " " + CaseOdds.Grade.COVERT.chance() + "\n"
                        + BANDS[4] + " " + CaseOdds.Grade.EXOTIC.chance() + "\n\n"))
                .append(hint("Te same, co w tamtej grze."))));

        pages.add(page(Text.empty()
                .append(title("7e. CO WYPADA\n\n"))
                .append(body("Netheryt, elytry, jaja smoka.\n\n"))
                .append(body("Na złocie: różdżka.\n\n"))
                .append(hint("Średnio skrzynka daje więcej towaru, "
                        + "niż kosztuje klucz."))));

        pages.add(page(Text.empty()
                .append(title("7f. WYMIANA\n\n"))
                .append(body(CaseOdds.TRADE_UP + " klucze niższego poziomu "
                        + "dają 1 wyższego.\n\n"))
                .append(body("Kwadrat w siatce.\n\n"))
                .append(hint("Dla kluczy, które znalazłeś."))));

        pages.add(page(Text.empty()
                .append(title("7g. UWAGA\n\n"))
                .append(warn("Sklep nie skupuje ani skrzynek, ani "
                        + "kluczy.\n\n"))
                .append(body("Klucz otwiera albo leży.\n\n"))
                .append(hint("Zwiedzanie ma dawać skrzynki, nie emki."))));
    }

    private static void street(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("17. NAPADY\n\n"))
                .append(body("Kiedy sam sprzedajesz towar z ręki, ktoś "
                        + "może pójść za pieniędzmi.\n\n"))
                .append(warn("Napastników bywa od czterech do "
                        + "jedenastu."))));

        pages.add(page(Text.empty()
                .append(title("17b. OD CZEGO ZALEŻY\n\n"))
                .append(body("Głównie od twojej reputacji na ulicy.\n\n"))
                .append(body("Potem: uwaga policji, ile oddajesz naraz "
                        + "i jak dobry jest towar."))));

        pages.add(page(Text.empty()
                .append(title("17c. CO POMAGA\n\n"))
                .append(body("Sprzedawaj w dzień, nie po ciemku.\n\n"))
                .append(body("Miej drugiego gracza w promieniu "
                        + TrapParanoia.COMPANY_RANGE + " bloków - to "
                        + "pomaga najbardziej.\n\n"))
                .append(hint("/heat pokazuje twoje szanse."))));

        pages.add(page(Text.empty()
                .append(title("17d. ALBO WCALE\n\n"))
                .append(body("Towar sprzedany przez twojego dilera NIGDY "
                        + "nie ściąga napadu.\n\n"))
                .append(hint("Za to właśnie płacisz mu prowizję."))));
    }

    private static void ledger(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("2. SPIS SKRZYŃ\n\n"))
                .append(body("Przedmiot, który mówi ci, gdzie co "
                        + "schowałeś.\n\n"))
                .append(body("PPM czyta wszystkie pojemniki w promieniu "
                        + LedgerItem.RADIUS_H + " bloków i "
                        + LedgerItem.RADIUS_V + " w pionie.\n\n"))
                .append(hint("Książka + kompas + 2 ametysty."))));

        pages.add(page(Text.empty()
                .append(title("2b. SZUKANIE\n\n"))
                .append(body("Kliknij dowolną pozycję na liście.\n\n"))
                .append(body("W powietrzu pojawi się świetlna linia "
                        + "prowadząca do skrzyni z tym przedmiotem.\n\n"))
                .append(hint("Naraz do " + LedgerItem.MAX_PINGS
                        + " skrzyń."))));

        pages.add(page(Text.empty()
                .append(title("2c. SHULKERY\n\n"))
                .append(body("Spis zagląda też do środka shulkerów "
                        + "stojących w skrzyniach.\n\n"))
                .append(hint("Nic się przed nim nie schowa."))));
    }

    private static void contracts(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("3. ZLECENIA\n\n"))
                .append(body("Zrób TELEFON NA KARTĘ. Przez niego "
                        + "dostajesz płatne dostawy.\n\n"))
                .append(hint("Miedź + ametyst + redstone."))));

        pages.add(page(Text.empty()
                .append(title("3b. TABLICA\n\n"))
                .append(body("Dziennie pojawia się "
                        + TrapContracts.BOARD_SIZE + " zleceń.\n\n"))
                .append(body("Każde ma swoje miejsce odbioru, "
                        + TrapContracts.MIN_DROP + "-"
                        + TrapContracts.MAX_DROP + " bloków stąd.\n\n"))
                .append(hint("Dostajesz kompas i masz limit czasu."))));

        pages.add(page(Text.empty()
                .append(title("3c. HACZYK\n\n"))
                .append(warn("Przyjęte zlecenie podnosi uwagę policji.\n\n"))
                .append(body("Przez całą dostawę masz podbitą paranoję. "
                        + "Za to właśnie płacą.\n\n"))
                .append(hint("Schodzi po "
                        + TrapContracts.JOB_HEAT_TICKS / 20 / 60 + " min."))));

        pages.add(page(Text.empty()
                .append(title("3d. MIEJSCE ODBIORU\n\n"))
                .append(body("Kompas prowadzi do punktu. Na mapie masz "
                        + "też znacznik do kliknięcia.\n\n"))
                .append(body("Podejdź blisko, a odbiorca się pojawi - "
                        + "będzie świecił."))));

        pages.add(page(Text.empty()
                .append(title("3e. PRZEKAZANIE\n\n"))
                .append(body("Kliknij odbiorcę PPM, będąc maks. "
                        + TrapContracts.DELIVERY_RANGE
                        + " bloków od punktu.\n\n"))
                .append(body("Dostajesz szmaragdy i reputację.\n\n"))
                .append(hint("Z pustymi rękami tylko ci przypomni."))));

        pages.add(page(Text.empty()
                .append(title("3f. NIEPOWODZENIE\n\n"))
                .append(warn("Nie zdążysz - tracisz "
                        + TrapContracts.FAIL_REP + " reputacji.\n\n"))
                .append(hint("Reputacja jest zapisana w telefonie, "
                        + "nie w tobie."))));

        pages.add(page(Text.empty()
                .append(title("3g. CO ODBIERAJĄ\n\n"))
                .append(body("Każde zlecenie mówi wprost, czego chce:\n\n"))
                .append(body("- tylko suszone szyszki\n"))
                .append(body("- tylko skręty\n"))
                .append(body("- obojętnie\n"))));

        pages.add(page(Text.empty()
                .append(title("3g2. PRZELICZNIK\n\n"))
                .append(body("Jeden skręt liczy się jak jedna szyszka.\n\n"))
                .append(warn("Sprawdź zlecenie ZANIM zwiniesz całą "
                        + "partię w skręty."))));
    }

    private static ItemStack book(String title, List<RawFilteredPair<Text>> pages) {
        WrittenBookContentComponent content = new WrittenBookContentComponent(
                RawFilteredPair.of(title), "Trap House", 0, pages, true);
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, content);
        return stack;
    }

    private static void cocaCover(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("KOKAINA"))
                .append(Text.literal("\nporadnik rafinera\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Dłuższa produkcja niż przy trawie, ale "
                        + "towar wart dużo więcej.\n\n"))
                .append(hint("Mak: /guide chemist"))));
    }

    // --- pages ----------------------------------------------------------------

    private static void cover(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("MARIHUANA"))
                .append(Text.literal("\nporadnik hodowcy\n\n").formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                // Paired per line: nine on their own lines plus the header and
                // footer came to ~16, over the 14-line page limit.
                .append(body("1 Uprawa    2 Jakość\n3 Suszenie  4 Skręty\n5 Palenie   6 Odmiany\n7 Efekty    8 Policja\n9 Sprzedaż\n"))
                .append(hint("Koka: /guide refiner"))));
    }

    private static void growing(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. UPRAWA\n\n"))
                .append(body("Posadź nasiona na zaoranej ziemi.\n\n"))
                .append(body("Na zwykłej ziemi i trawie też urosną, "
                        + "tylko wolniej.\n\n"))
                .append(hint("Roślina ma cztery fazy wzrostu."))));

        pages.add(page(Text.empty()
                .append(title("1b. CZAS WZROSTU\n\n"))
                .append(body("Podlana: ok. " + Math.round(TrapMath.stageMinutes(
                        TrapMath.WEED_GROWTH_ROLLS_WET, 3) * 3) + " min\n"))
                .append(body("Sucha: ok. " + Math.round(TrapMath.stageMinutes(
                        TrapMath.WEED_GROWTH_ROLLS_DRY, 3) * 3) + " min\n\n"))
                .append(warn("Woda to +3 punkty jakości I połowa czasu. "
                        + "Zawsze podlewaj."))));

        pages.add(page(Text.empty()
                .append(title("1c. ZBIÓR\n\n"))
                .append(body("Kliknij PPM pustą ręką w wyrośniętą "
                        + "roślinę.\n\n"))
                .append(body("Dostajesz szyszki, czasem nasiono, "
                        + "a roślina zostaje i odrasta."))));

        pages.add(page(Text.empty()
                .append(title("1d. ALBO ZNISZCZ\n\n"))
                .append(body("Rozbicie rośliny też działa i zwraca ci "
                        + "nasiono.\n\n"))
                .append(warn("Ale wtedy trzeba sadzić od nowa."))));
    }

    private static void grading(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("2. JAKOŚĆ\n\n"))
                .append(body("Ustala się w chwili zbioru, na podstawie "
                        + "warunków wzrostu.\n\n"))
                .append(warn("Potem już jej nie poprawisz."))));

        pages.add(page(Text.empty()
                .append(title("2b. PUNKTY\n\n"))
                .append(body("+3 mokra zaorana ziemia\n"))
                .append(body("+2 światło 12 i więcej\n"))
                .append(body("+1 światło 9-11\n"))
                .append(body("+2 otwarte niebo\n"))
                .append(body("+1 bez mączki kostnej\n\n"))
                .append(hint("Maksymalnie 8 punktów."))));

        MutableText table = Text.empty().append(title("2c. CO TO DAJE\n\n"));
        for (Quality grade : Quality.values()) {
            table.append(Text.literal(pad(grade.display(), 6)).formatted(grade.bookColour()))
                    .append(body(String.format("%.1fx  %de\n", grade.potency(), grade.emeralds())));
        }
        table.append(body("\n"))
                .append(hint("moc i cena za sztukę"));
        pages.add(page(table));

        pages.add(page(Text.empty()
                .append(title("2d. PROGI\n\n"))
                .append(body("Punkty potrzebne na kolejne klasy:\n\n"))
                .append(body(joinInts(Quality.THRESHOLDS) + "\n\n"))
                .append(hint("Poniżej pierwszego progu masz najgorszą "
                        + "klasę."))));
    }

    private static void curing(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("3. SUSZENIE\n\n"))
                .append(body("Świeże szyszki są mało warte. Trzeba je "
                        + "wysuszyć.\n\n"))
                .append(body("Zrób SUSZARKĘ: 8 patyków i 2 nici."))));

        pages.add(page(Text.empty()
                .append(title("3b. JAK SUSZYĆ\n\n"))
                .append(body("Kliknij PPM w suszarkę, trzymając świeże "
                        + "szyszki.\n\n"))
                .append(body("Z czasem ciemnieją. Kiedy końcówki są "))
                .append(Text.literal("złote").formatted(Formatting.GOLD))
                .append(body(", są gotowe.\n\n"))
                .append(hint("Wtedy kliknij, żeby je zabrać."))));

        MutableText window = Text.empty().append(title("3c. MOMENT ZBIORU\n\n"));
        for (int stage = 0; stage <= DryingRackBlock.MAX_DRYNESS; stage++) {
            String label;
            if (stage == 0) {
                label = "za mokre";
            } else if (stage == DryingRackBlock.READY_DRYNESS) {
                label = "GOTOWE";
            } else if (stage == DryingRackBlock.MAX_DRYNESS) {
                label = "-1 klasa";
            } else {
                label = "-" + (DryingRackBlock.READY_DRYNESS - stage) + " klasy";
            }
            window.append(body("faza " + stage + "  "))
                    .append(stage == DryingRackBlock.READY_DRYNESS
                            ? Text.literal(label + "\n").formatted(Formatting.GOLD)
                            : body(label + "\n"));
        }
        pages.add(page(window));

        pages.add(page(Text.empty()
                .append(title("3d. PO CO CZEKAĆ\n\n"))
                .append(body("Zbiór w idealnym momencie daje 2 szyszki "
                        + "zamiast 1.\n\n"))
                .append(warn("Za wcześnie albo za późno tracisz klasę "
                        + "ORAZ połowę zbioru."))));
    }

    private static void rolling(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("4. SKRĘTY\n\n"))
                .append(body("Suszona szyszka + papier = skręt.\n\n"))
                .append(body("Klasa szyszki przechodzi na skręt.\n\n"))
                .append(hint("Skręty łatwiej sprzedać niż susz."))));

        pages.add(page(Text.empty()
                .append(title("4b. PALENIE\n\n"))
                .append(body("Przytrzymaj PPM. Postać podnosi skręt do "
                        + "ust i leci dym.\n\n"))
                .append(warn("Wszyscy w pobliżu to widzą."))));
    }

    /** Three ways to smoke, in order of setup cost. Numbers from the blocks. */
    private static void methods(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("5. SPOSOBY PALENIA\n\n"))
                .append(body(String.format("skręt   1.0x\nbong    %.1fx\ntłok    %.1fx\n\n",
                        BongBlock.POTENCY, GravityBongBlock.POTENCY)))
                .append(hint("Liczba to mnożnik siły efektu."))));

        pages.add(page(Text.empty()
                .append(title("5b. KOSZT\n\n"))
                .append(warn("Mocniejsze wejście szybciej podnosi "
                        + "TOLERANCJĘ.\n\n"))
                .append(body("Żaden sposób nie jest darmowy.\n\n"))
                .append(hint("Skręt możesz zabrać ze sobą. Reszty nie."))));

        pages.add(page(Text.empty()
                .append(title("5c. BONG\n\n"))
                .append(body("Zrobisz go ze szkła i bambusa.\n\n"))
                .append(body("Raz wlej wiadro wody, potem wkładaj po "
                        + "jednej suszonej szyszce i klikaj PPM.\n\n"))
                .append(hint("Woda zostaje na stałe. Szyszka nie."))));

        pages.add(page(Text.empty()
                .append(title("5d. TŁOK\n\n"))
                .append(body("Butelka, wiadro, bambus, papier.\n\n"))
                .append(body("Kolejność: woda, szyszka, krzesiwo, "
                        + "potem pociągnij."))));

        pages.add(page(Text.empty()
                .append(title("5d2. UWAGA\n\n"))
                .append(warn("Ciągnij zaraz po podpaleniu.\n\n"))
                .append(body("Jak zostawisz, dym zwietrzeje i stracisz "
                        + "szyszkę."))));

        mixing(pages);
    }

    /**
     * Kept to three pages. The mixing station has a lot going on, but the book
     * truncates silently past ~14 lines a page -- so the named blends get their
     * own page rather than being crammed in as a list.
     */
    private static void mixing(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("6. MIESZANIE\n\n"))
                .append(item("Butelka Miska  Butelka\nMiedź   Żelazo Miedź\nKłoda   Kłoda  Kłoda\n\n"))
                .append(body("Kłody dowolne."))));

        pages.add(page(Text.empty()
                .append(title("6b. OBSŁUGA\n\n"))
                .append(body("Włóż suszone szyszki w cztery sloty, "
                        + "potem kliknij słoik po prawej.\n\n"))
                .append(hint("Zamkniesz okno - szyszki wracają do "
                        + "ciebie."))));

        pages.add(page(Text.empty()
                .append(title("6c. PODGLĄD\n\n"))
                .append(body("Słoik po prawej pokazuje, co dostaniesz, "
                        + "ZANIM to zatwierdzisz.\n\n"))
                .append(hint("Jak się nie da, napisze dlaczego."))));

        pages.add(page(Text.empty()
                .append(title("6d. JAK TO DZIAŁA\n\n"))
                .append(body("Mieszanka ma efekty wszystkich swoich "
                        + "składników, każdy proporcjonalnie słabszy.\n\n"))
                .append(body("Więcej różnych odmian = mocniej i dziwniej "
                        + "wygląda."))));

        pages.add(page(Text.empty()
                .append(title("6e. PUŁAPKA\n\n"))
                .append(warn("Klasa mieszanki to klasa NAJGORSZEJ "
                        + "szyszki, nie średnia.\n\n"))
                .append(body("Jedna słaba szyszka psuje całą partię."))));

        pages.add(page(Text.empty()
                .append(title("6f. NAZWANE MIESZANKI\n\n"))
                .append(body("Niektóre składy dają więcej niż suma "
                        + "części:\n\n"))
                .append(body("Trinity  K+H+P\nVoid  M+M+P\nDaybreak  H+S\nTurbo  D+D+H\nTar  K+K+M\n"))));

        pages.add(page(Text.empty()
                .append(title("6f2. I JESZCZE\n\n"))
                .append(body("Kaleidoscope  P+S+H+D\n\n"))
                .append(hint("Litery to pierwsze litery nazw odmian."))));
    }

    private static void baked(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("7. EFEKT BAKED\n\n"))
                .append(body("Zjada ci jedzenie z paska głodu.\n\n"))
                .append(body("Jeśli jesteś najedzony, w zamian cię "
                        + "leczy."))));

        pages.add(page(Text.empty()
                .append(title("7b. NA PUSTY ŻOŁĄDEK\n\n"))
                .append(warn("Bez jedzenia zabiera ci zdrowie.\n\n"))
                .append(body("Najedz się PRZED paleniem.\n\n"))
                .append(hint("Każda odmiana dokłada swoje efekty."))));

        pages.add(page(Text.empty()
                .append(title("7c. TOLERANCJA\n\n"))
                .append(body("Każdy skręt podnosi ją o jeden poziom.\n\n"))
                .append(body("Każdy poziom zabiera "
                        + Math.round(ToleranceStatusEffect.PER_LEVEL * 100)
                        + "% siły kolejnego efektu."))));

        pages.add(page(Text.empty()
                .append(title("7d. GRANICE\n\n"))
                .append(body("Efekt nie spadnie poniżej "
                        + Math.round(ToleranceStatusEffect.FLOOR * 100)
                        + "% siły.\n\n"))
                .append(body("Jeden poziom schodzi po "
                        + (ToleranceStatusEffect.DURATION_TICKS / 20 / 60)
                        + " min.\n\n"))
                .append(hint("Rozkładaj palenie w czasie."))));
    }

    private static void strains(List<RawFilteredPair<Text>> pages) {
        for (Strain strain : Strain.values()) {
            pages.add(page(Text.empty()
                    .append(title("8. ODMIANY\n\n"))
                    .append(Text.literal(strain.display() + "\n").withColor(strain.bookColour()))
                    .append(Text.literal(strain.isHybrid() ? "krzyżówka\n\n" : "naturalna\n\n")
                            .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                    .append(body(strain.describe()))));
        }
    }

    private static void breeding(List<RawFilteredPair<Text>> pages) {
        MutableText text = Text.empty()
                .append(title("9. KRZYŻOWANIE\n\n"))
                .append(body("Dwie różne odmiany, obie wyrośnięte, "
                        + "posadzone obok siebie.\n\n"));

        // Read straight off hybridOf, so a new pairing can't be missed here.
        for (Strain a : Strain.values()) {
            for (Strain b : Strain.values()) {
                if (a.ordinal() >= b.ordinal()) {
                    continue;
                }
                Strain hybrid = Strain.hybridOf(a, b);
                if (hybrid != null) {
                    text.append(Text.literal(a.display()).withColor(a.bookColour()))
                            .append(body("+"))
                            .append(Text.literal(b.display()).withColor(b.bookColour()))
                            .append(body("="))
                            .append(Text.literal(hybrid.display() + "\n").withColor(hybrid.bookColour()));
                }
            }
        }
        pages.add(page(text));

        pages.add(page(Text.empty()
                .append(title("9b. KONIEC LINII\n\n"))
                .append(warn("Krzyżówek nie da się krzyżować dalej.\n\n"))
                .append(body("Z dwóch krzyżówek nic nie powstanie.\n\n"))
                .append(hint("Do krzyżowania trzymaj zapas odmian "
                        + "naturalnych."))));
    }

    private static void heat(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("10. UWAGA POLICJI\n\n"))
                .append(body("Uprawa na widoku zostaje zauważona. "
                        + "Dostajesz ostrzeżenie na czacie.\n\n"))
                .append(warn("Po ostrzeżeniu przychodzi nalot."))));

        pages.add(page(Text.empty()
                .append(title("10b. CZĘSTOTLIWOŚĆ\n\n"))
                .append(body("Im większa plantacja, tym szybciej wracają "
                        + "po następny nalot.\n\n"))
                .append(hint("Powyżej najwyższego progu - jeszcze "
                        + "szybciej."))));

        pages.add(page(Text.empty()
                .append(title("10c. CO SIĘ LICZY\n\n"))
                .append(body("Liczone są wyrośnięte rośliny w promieniu "
                        + TrapHeat.RADIUS + " bloków.\n\n"))
                .append(body("Pod gołym niebem: 2 pkt każda\n"))
                .append(body("Pod dachem: 1 pkt każda\n"))));

        pages.add(page(Text.empty()
                .append(title("10d. WNIOSEK\n\n"))
                .append(body("Zadaszona plantacja może być dwa razy "
                        + "większa przy tej samej uwadze.\n\n"))
                .append(hint("Dach opłaca się od pierwszej rośliny."))));

        pages.add(page(Text.empty()
                .append(title("10e. NIE TYLKO KONOPIE\n\n"))
                .append(body("Koka liczy się tak samo.\n\n"))
                .append(body("Prasy i rafinerie też dodają uwagę.\n\n"))
                .append(hint("Każdy sprzęt to " + 2 + " punkty."))));

        pages.add(page(Text.empty()
                .append(title("10f. NIE MIESZAJ\n\n"))
                .append(warn("Konopie I koka w jednym miejscu to o "
                        + Math.round((TrapHeat.MIXED_TRADE - 1) * 100)
                        + "% więcej uwagi niż osobno.\n\n"))
                .append(hint("Dwie osobne szopy biją jedną wspólną."))));

        pages.add(page(Text.empty()
                .append(title("10g. MURY\n\n"))
                .append(body("Zamurowanie uprawy kupuje ci czas, ale nie "
                        + "daje bezpieczeństwa.\n\n"))
                .append(warn("Jak nie znajdą wejścia, wejdą przez "
                        + "ścianę."))));

        pages.add(page(Text.empty()
                .append(title("10g2. Z CZEGO BUDOWAĆ\n\n"))
                .append(body("Obsydian ich zatrzymuje.\n\n"))
                .append(warn("Ziemia, drewno i kamień nie."))));

        // Built from the arrays rather than written out, so the book can't
        // drift from the tiers the way the old fixed numbers did.
        net.minecraft.text.MutableText tiers =
                Text.empty().append(title("10h. KTO PRZYCHODZI\n\n"));
        for (int tier = 0; tier < TrapHeat.THRESHOLDS.length; tier++) {
            tiers.append(body(TrapHeat.THRESHOLDS[tier] + ": " + TrapHeat.squadOf(tier)
                    + "  (" + TrapHeat.cooldownMinutes(tier) + "m)\n"));
        }
        pages.add(page(tiers));

        pages.add(page(Text.empty()
                .append(title("10h2. JAK CZYTAĆ\n\n"))
                .append(body("Pierwsza liczba to PUNKTY uwagi, nie "
                        + "liczba roślin.\n\n"))
                .append(body("15 roślin na otwartym to już pierwszy "
                        + "próg.\n\n"))
                .append(hint("W nawiasie: przerwa między nalotami."))));
    }

    /** The second product line. Numbers read from Purity and RefinerBlock. */
    private static void coca(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. CAŁA DROGA\n\n"))
                .append(body("krzak -> liście -> prasa -> rafineria -> "
                        + "proszek\n\n"))
                .append(hint("Cztery kroki, każdy opisany dalej."))));

        pages.add(page(Text.empty()
                .append(title("2. UPRAWA KOKI\n\n"))
                .append(body("Dojrzewa około " + Math.round(TrapMath.stageMinutes(
                        TrapMath.COCA_GROWTH_ROLLS, 3) * 3) + " minut.\n\n"))
                .append(body("Rośnie na dowolnej ziemi, ale potrzebuje "
                        + "światła.\n\n"))
                .append(hint("Nasiona: ciepłe struktury albo handlarz."))));

        pages.add(page(Text.empty()
                .append(title("3. PRASOWANIE\n\n"))
                .append(body("Zrób "))
                .append(item("Prasa do liści"))
                .append(body(": gładki kamień, kłody, żelazo."))));

        pages.add(page(Text.empty()
                .append(title("3b. OBSŁUGA PRASY\n\n"))
                .append(body("Kliknij PPM mając "
                        + LeafPressBlock.LEAVES_PER_BATCH + " liści.\n\n"))
                .append(body("Odczekaj, potem kliknij ponownie, żeby "
                        + "wyjąć pastę.\n\n"))
                .append(hint("Nie ma tu okna czasowego. Po prostu trwa."))));

        MutableText refining = Text.empty()
                .append(title("4. RAFINACJA\n\n"))
                .append(body("Pasta + płonący proszek w "))
                .append(item("Rafineria"))
                .append(body(".\n\n"))
                .append(body("Czystość zależy WYŁĄCZNIE od tego, kiedy "
                        + "to wyjmiesz:\n\n"));
        for (int step = 1; step <= RefinerBlock.BURNT; step++) {
            Purity grade = RefinerBlock.purityFor(step);
            boolean peak = step == RefinerBlock.PEAK;
            refining.append(body("stage " + step + "  "))
                    .append(Text.literal(grade.display() + (peak ? " *\n" : "\n"))
                            .formatted(grade.bookColour()));
        }
        pages.add(page(refining));

        pages.add(page(Text.empty()
                .append(title("4b. GWIAZDKA\n\n"))
                .append(body("Faza oznaczona gwiazdką to szczyt "
                        + "czystości.\n\n"))
                .append(warn("Za długo w rafinerii i towar się psuje."))));

        MutableText worth = Text.empty().append(title("5. ILE TO WARTE\n\n"));
        for (Purity grade : Purity.values()) {
            worth.append(Text.literal(pad(grade.display(), 7)).formatted(grade.bookColour()))
                    .append(body(String.format("%.1fx  %de\n", grade.potency(), grade.emeralds())));
        }
        worth.append(body("\n")).append(hint("moc i cena za sztukę"));
        pages.add(page(worth));

        pages.add(page(Text.empty()
                .append(title("6. EFEKT WIRED\n\n"))
                .append(body("Kokaina daje szybkość i siłę.\n\n"))
                .append(warn("Przez cały czas trwania nic cię nie "
                        + "kosztuje."))));

        pages.add(page(Text.empty()
                .append(title("6b. RACHUNEK\n\n"))
                .append(warn("Kiedy efekt się kończy, cała kara spada "
                        + "na ciebie NARAZ.\n\n"))
                .append(hint("Nie bierz kolejnej działki tuż przed "
                        + "walką."))));
    }

    // --- the chemist's handbook -----------------------------------------------

    private static void poppyCover(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("HEROINA"))
                .append(Text.literal("\nporadnik chemika\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Najdłuższa produkcja w grze. Trzy maszyny "
                        + "i łatwo stracić całą partię.\n\n"))
                .append(hint("Nałóg: /guide habit"))));
    }

    /**
     * Every figure below comes off the block that governs it -- pods per batch
     * off {@link ScoringTableBlock}, lime off {@link WashPotBlock}, the whole
     * purity ladder off {@link AcetylatorBlock#purityFor}. Retune any of them
     * and the book retunes with it.
     */
    private static void poppy(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. CAŁA DROGA\n\n"))
                .append(body("makówka -> nacinanie -> gotowanie -> "
                        + "acetylacja -> towar\n\n"))
                .append(hint("Cztery maszyny, każda opisana dalej."))));

        pages.add(page(Text.empty()
                .append(title("2. UPRAWA MAKU\n\n"))
                .append(body("Dojrzewa około " + Math.round(TrapMath.stageMinutes(
                        TrapMath.POPPY_GROWTH_ROLLS, 3) * 3) + " minut.\n\n"))
                .append(body("Wymaga światła co najmniej "
                        + PoppyCropBlock.NEEDS_LIGHT + ".\n\n"))
                .append(warn("W piwnicy nie urośnie. Musi widzieć "
                        + "niebo."))));

        pages.add(page(Text.empty()
                .append(title("2b. ZBIÓR\n\n"))
                .append(body("Kliknij PPM dojrzałą roślinę.\n\n"))
                .append(body("Dostajesz " + PoppyCropBlock.MIN_PODS + "-"
                        + PoppyCropBlock.MAX_PODS + " makówek, czasem "
                        + "nasiono. Roślina zostaje."))));

        pages.add(page(Text.empty()
                .append(title("2c. KIEDY ZBIERAĆ\n\n"))
                .append(body("Czerwone kwiaty = już prawie.\n\n"))
                .append(body("Widoczne makówki = teraz.\n\n"))
                .append(hint("Nie zbieraj wcześniej, nic nie dostaniesz."))));

        pages.add(page(Text.empty()
                .append(title("3. NACINANIE\n\n"))
                .append(body("Zrób "))
                .append(item("Stół do nacinania"))
                .append(body(": żelazo nad kłodami."))));

        pages.add(page(Text.empty()
                .append(title("3b. OBSŁUGA\n\n"))
                .append(body("Kliknij PPM mając "
                        + ScoringTableBlock.PODS_PER_BATCH + " makówek.\n\n"))
                .append(body("Odczekaj, kliknij ponownie i odbierz "
                        + "opium.\n\n"))
                .append(hint("Nie ma okna czasowego. Po prostu trwa."))));

        pages.add(page(Text.empty()
                .append(title("4. GOTOWANIE\n\n"))
                .append(body("Zrób "))
                .append(item("Garnek do gotowania"))
                .append(body(": miedź, kocioł, cegły."))));

        pages.add(page(Text.empty()
                .append(title("4b. SKŁADNIKI\n\n"))
                .append(body("W ręce: " + WashPotBlock.OPIUM_PER_BATCH
                        + " opium.\n\n"))
                .append(body("W ekwipunku: " + WashPotBlock.LIME_PER_BATCH
                        + " mączki kostnej.\n\n"))
                .append(hint("Mączka jest brana automatycznie."))));

        pages.add(page(Text.empty()
                .append(title("4c. OGIEŃ\n\n"))
                .append(warn("Garnek gotuje TYLKO nad ogniem. Bez ognia "
                        + "nic się nie dzieje.\n\n"))
                .append(body("Liczy się cokolwiek płonącego: ognisko, "
                        + "piec, lawa."))));

        pages.add(page(Text.empty()
                .append(title("4d. JAK ZGAŚNIE\n\n"))
                .append(body("Nic się nie psuje. Po prostu proces stoi.\n\n"))
                .append(hint("Zbuduj laboratorium raz i nie klikaj "
                        + "garnka niepotrzebnie."))));

        MutableText timing = Text.empty()
                .append(title("5. ACETYLACJA\n\n"))
                .append(body("Baza + sfermentowane oko pająka w "))
                .append(item("Acetylator"))
                .append(body(".\n\n"));
        for (int step = 1; step <= AcetylatorBlock.RUINED; step++) {
            Purity grade = AcetylatorBlock.purityFor(step);
            if (grade == null) {
                timing.append(body("faza " + step + "  "))
                        .append(Text.literal("STRACONE\n").formatted(Formatting.DARK_RED));
            } else {
                boolean peak = step == AcetylatorBlock.PEAK;
                timing.append(body("faza " + step + "  "))
                        .append(Text.literal(grade.display() + (peak ? " *\n" : "\n"))
                                .formatted(grade.bookColour()));
            }
        }
        pages.add(page(timing));

        pages.add(page(Text.empty()
                .append(title("5b. OKNO CZASOWE\n\n"))
                .append(body("Rafineria do koki daje pięć faz luzu na "
                        + "szczycie.\n\n"))
                .append(warn("Acetylator daje tylko "
                        + AcetylatorBlock.PEAK_GRACE + "."))));

        pages.add(page(Text.empty()
                .append(title("5c. RYZYKO\n\n"))
                .append(warn("Przegapisz - przepada CAŁA partia.\n\n"))
                .append(body("Baza, kwas, makówki, wszystko.\n\n"))
                .append(hint("Stój przy tej maszynie i patrz."))));

        MutableText worth = Text.empty().append(title("6. ILE TO WARTE\n\n"));
        for (Purity grade : Purity.values()) {
            worth.append(Text.literal(pad(grade.display(), 7)).formatted(grade.bookColour()))
                    .append(body(String.format("%.1fx  %de\n", grade.potency(),
                            Math.round(grade.emeralds() * Drug.DOPE.priceScale()))));
        }
        worth.append(body("\n"))
                .append(hint(String.format("%.0fx tego, co daje proszek.",
                        Drug.DOPE.priceScale())));
        pages.add(page(worth));

        pages.add(page(Text.empty()
                .append(title("7. EFEKT NOD\n\n"))
                .append(body("Nic cię nie boli, regenerujesz zdrowie "
                        + "i nie chce ci się jeść."))));

        pages.add(page(Text.empty()
                .append(title("7b. CENA\n\n"))
                .append(warn("Przez cały czas trwania NIE możesz "
                        + "biegać, walczyć ani kopać.\n\n"))
                .append(body("Jesteś bezbronny. Nie bierz tego w "
                        + "terenie."))));

        pages.add(page(Text.empty()
                .append(title("7c. PORÓWNANIE\n\n"))
                .append(body("Trawa: płacisz od razu.\n"))
                .append(body("Koka: płacisz po efekcie.\n"))
                .append(body("Heroina: płacisz później.\n\n"))
                .append(hint("Cała mechanika nałogu: /guide habit"))));

        pages.add(page(Text.empty()
                .append(title("8. PRZEDAWKOWANIE\n\n"))
                .append(warn("Druga działka, zanim zejdzie pierwsza, "
                        + "kładzie cię na ziemię.\n\n"))
                .append(body("Nie zabija. Kosztuje kilka minut "
                        + "bezradności."))));

        pages.add(page(Text.empty()
                .append(title("8b. JAK UNIKNĄĆ\n\n"))
                .append(body("Poczekaj, aż ikona efektu zniknie z "
                        + "ekranu.\n\n"))
                .append(hint("Dopiero wtedy bierz kolejną."))));

        pages.add(page(Text.empty()
                .append(title("9. NAŁÓG\n\n"))
                .append(warn("Heroina uzależnia " + Math.round(
                        Drug.DOPE.hookPerHit() / Drug.KUSH.hookPerHit())
                        + "x mocniej niż trawa.\n\n"))
                .append(body("I schodzi "
                        + Math.round(Drug.KUSH.decayPerMinute()
                        / Drug.DOPE.decayPerMinute())
                        + "x wolniej."))));

        pages.add(page(Text.empty()
                .append(title("9b. LICZBY\n\n"))
                .append(body("Wystarczy " + Drug.DOPE.hitsToMax()
                        + " działek, żeby wbić licznik na maksa.\n\n"))
                .append(hint("Całość: /guide habit"))));
    }

    // --- the habit ------------------------------------------------------------

    private static void habitCover(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("NAŁÓG"))
                .append(Text.literal("\ni lista klientów\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Ile kosztuje branie i co daje sprzedawanie "
                        + "innym.\n\n"))
                .append(hint("Twoje liczniki: /addiction"))));
    }

    private static void habit(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. LICZNIKI\n\n"))
                .append(body("Każda używka ma WŁASNY licznik nałogu.\n\n"))
                .append(body("Każda odmiana trawy liczy się osobno.\n\n"))
                .append(hint("Sprawdzasz je komendą /addiction"))));

        pages.add(page(Text.empty()
                .append(title("1b. CO Z TEGO WYNIKA\n\n"))
                .append(body("Nałóg na Purp domaga się Purpa.\n\n"))
                .append(warn("Cała szopa Kusha nic wtedy nie da."))));

        MutableText bands = Text.empty()
                .append(title("2. TRZY POZIOMY\n\n"))
                .append(body("Objaw pojawia się, gdy licznik jest wysoko "
                        + "I minęło trochę czasu od ostatniej działki.\n\n"));
        bands.append(Text.literal(pad("swędzi", 8)).formatted(Formatting.DARK_GRAY))
                .append(body("od " + Math.round(TrapAddiction.ITCH_AT * Drug.MAX) + "\n"))
                .append(Text.literal(pad("głód", 8)).formatted(Formatting.GOLD))
                .append(body("od " + Math.round(TrapAddiction.CRAVE_AT * Drug.MAX) + "\n"))
                .append(Text.literal(pad("choroba", 8)).formatted(Formatting.DARK_RED))
                .append(body("od " + Math.round(TrapAddiction.SICK_AT * Drug.MAX) + "\n"));
        pages.add(page(bands));

        pages.add(page(Text.empty()
                .append(title("2b. BEZPIECZNY PRÓG\n\n"))
                .append(body("Trzymaj licznik poniżej pierwszej liczby, "
                        + "a nigdy nie poczujesz objawów.\n\n"))
                .append(hint("Sprawdzaj /addiction przed paleniem."))));

        pages.add(page(Text.empty()
                .append(title("2c. PO JAKIM CZASIE\n\n"))
                .append(body("Od ostatniej działki do objawów:\n\n"))
                .append(body("trawa    " + Drug.KUSH.cravePeriodMinutes() + " min\n"
                        + "kokaina  " + Drug.COKE.cravePeriodMinutes() + " min\n"
                        + "heroina  " + Drug.DOPE.cravePeriodMinutes() + " min\n\n"))
                .append(warn("Heroina wraca po kilku minutach."))));

        pages.add(page(Text.empty()
                .append(title("3. CO ROBIĄ OBJAWY\n\n"))
                .append(body("Swędzi: tylko komunikaty, nic więcej.\n\n"))
                .append(body("Głód: przestają działać ci ręce - nie "
                        + "kopiesz i nie walczysz."))));

        pages.add(page(Text.empty()
                .append(title("3b. CHOROBA\n\n"))
                .append(warn("Wszystko przestaje działać.\n\n"))
                .append(body("Przy heroinie dodatkowo tracisz zdrowie.\n\n"))
                .append(hint("Nałóg cię nie zabije, ale unieruchomi."))));

        pages.add(page(Text.empty()
                .append(title("4. DZIAŁKA\n\n"))
                .append(body("Wzięcie tego, czego ci brakuje, kasuje "
                        + "objawy natychmiast i daje premię."))));

        pages.add(page(Text.empty()
                .append(title("4b. HACZYK\n\n"))
                .append(warn("I jednocześnie podnosi licznik jeszcze "
                        + "wyżej.\n\n"))
                .append(body("Na tym polega pułapka. Tak ma być."))));

        MutableText clean = Text.empty()
                .append(title("5. WYCHODZENIE\n\n"))
                .append(body("Pomaga tylko czas. Od pełnego licznika:\n\n"));
        for (Drug drug : new Drug[]{Drug.KUSH, Drug.COKE, Drug.DOPE}) {
            clean.append(Text.literal(pad(drug.isWeed() ? "trawa" : drug.display(), 8))
                            .formatted(drug.text() == Formatting.WHITE
                                    ? Formatting.DARK_GRAY : Formatting.BLACK))
                    .append(body(drug.minutesToClean() + " min\n"));
        }
        pages.add(page(clean));

        pages.add(page(Text.empty()
                .append(title("5b. SKRÓT\n\n"))
                .append(body("Jeśli przetrzymasz fazę choroby i nie "
                        + "weźmiesz, licznik spada dwa razy szybciej.\n\n"))
                .append(hint("Najgorsze minuty są na początku."))));

        pages.add(page(Text.empty()
                .append(title("5c. LEK\n\n"))
                .append(item("Lek na nerwy"))
                .append(body(" wstrzymuje objawy na "
                        + (TrapContent.NerveTonicItem.CALM_TICKS / 20)
                        + " sekund.\n\n"))
                .append(hint("Miód, cukier, kwiatek."))));

        pages.add(page(Text.empty()
                .append(title("5d. UWAGA\n\n"))
                .append(warn("Lek NIE obniża licznika nałogu.\n\n"))
                .append(body("Kupuje ci tylko spokojne popołudnie "
                        + "pracy."))));

        MutableText hooks = Text.empty()
                .append(title("6. ILE DZIAŁEK\n\n"))
                .append(body("Do zapełnienia licznika, przy zwykłej "
                        + "mocy towaru:\n\n"));
        for (Drug drug : new Drug[]{Drug.KUSH, Drug.COKE, Drug.DOPE}) {
            hooks.append(Text.literal(pad(drug.isWeed() ? "trawa" : drug.display(), 8))
                            .formatted(Formatting.BLACK))
                    .append(body(drug.hitsToMax() + "\n"));
        }
        hooks.append(body("\n"))
                .append(hint("Mocniejsze klasy liczą się za więcej."));
        pages.add(page(hooks));

        pages.add(page(Text.empty()
                .append(title("7. KLIENCI\n\n"))
                .append(body("NPC też się uzależniają - i to od "
                        + "CIEBIE.\n\n"))
                .append(body("Każda sprzedaż buduje twoją listę "
                        + "klientów."))));

        pages.add(page(Text.empty()
                .append(title("7b. TEMPO\n\n"))
                .append(body("Heroina buduje listę zdecydowanie "
                        + "najszybciej.\n\n"))
                .append(hint("Widać ją w /addiction, jak tylko ruszy."))));

        pages.add(page(Text.empty()
                .append(title("7c. CO TO DAJE\n\n"))
                .append(body("Klienci:\n\n"))
                .append(body("- podchodzą częściej\n"))
                .append(body("- proszą o mocniejszy towar\n"))
                .append(body("- kupują więcej za jednym razem\n"))));

        pages.add(page(Text.empty()
                .append(title("7d. I JESZCZE\n\n"))
                .append(body("Twoi właśni lokatorzy też zaczynają "
                        + "pytać o towar.\n\n"))
                .append(warn("Przestaniesz sprzedawać i lista zanika."))));
    }

    private static void checking(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("11. KOMENDA /HEAT\n\n"))
                .append(body("Pokazuje, jak gorąco jest w miejscu, "
                        + "w którym stoisz, i co z tego wyniknie.\n\n"))
                .append(hint("Sprawdza 22 bloki wszerz i 10 w pionie."))));

        pages.add(page(Text.empty()
                .append(title("11b. TARYFIKATOR\n\n"))
                .append(body("dojrzała roślina  3\n"))
                .append(body("ukryta roślina    2\n"))
                .append(body("rosnąca roślina   1\n"))
                .append(body("suszarka          1\n"))
                .append(body("maszyna           2\n"))));
    }

    /**
     * What stayed in the grower's handbook once the crew moved out.
     *
     * The search is a RAID page, not a crew page -- it only ever sat next to
     * them because both were about your farm being looked after or looked at.
     */
    /** Paid jobs to a page of the handbook. See where it is used for why. */
    private static final int JOBS_A_PAGE = 5;

    private static void crew(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("12. EKIPA\n\n"))
                .append(body("Możesz nająć ludzi do pracy na plantacji: "
                        + "zbierają, sadzą, orzą, suszą.\n\n"))
                .append(body("Kosztuje " + TrapCrew.HIRE_COST
                        + "e za osobę, plus pensje.\n\n"))
                .append(hint("Całość: /guide crew"))));

        pages.add(page(Text.empty()
                .append(title("13. NALOT\n\n"))
                .append(body("Napastnicy nie tylko machają toporami.\n\n"))
                .append(warn("Podchodzą do twoich skrzyń i zabierają "
                        + "z nich TOWAR."))));

        pages.add(page(Text.empty()
                .append(title("13b. CO JEST BEZPIECZNE\n\n"))
                .append(body("Biorą wyłącznie towar.\n\n"))
                .append(body("Nasiona i sprzęt zostają.\n\n"))
                .append(hint("Zakop skrzynie, rozdziel zapasy albo "
                        + "stań im na drodze."))));
    }

    // --- the crew handbook ----------------------------------------------------

    /**
     * Every page below reads its numbers off {@link TrapCrew}.
     *
     * Which matters more here than anywhere else in this book, because the
     * crew is the one system in the mod where the whole decision IS the
     * numbers: what a rung costs, what it does to the wage, and whether the
     * two are worth it. A handbook quoting a pace ladder somebody has since
     * retuned would be worse than no handbook at all.
     */
    private static void crewBook(List<RawFilteredPair<Text>> pages) {
        int top = TrapCrew.PACE_TICKS.length - 1;
        int wide = TrapCrew.REACH_BLOCKS.length - 1;

        pages.add(page(Text.empty()
                .append(title("1. ZATRUDNIANIE\n\n"))
                .append(body("Stań dokładnie tam, gdzie ma być "
                        + "wykonywana praca, i wpisz:\n\n"))
                .append(body("/crew hire\n\n"))
                .append(body("Koszt: " + TrapCrew.HIRE_COST + "e"))));

        pages.add(page(Text.empty()
                .append(title("1b. DZIAŁKA\n\n"))
                .append(body("Miejsce, w którym stałeś, staje się jego "
                        + "działką.\n\n"))
                .append(body("Nowy robotnik nie umie nic - wszystkiego "
                        + "trzeba go nauczyć.\n\n"))
                .append(hint("Maksymalnie " + TrapCrew.MAX_HANDS
                        + " osób."))));

        pages.add(page(Text.empty()
                .append(title("2. SKRZYNIA\n\n"))
                .append(body("Wszystko, co zbierze, ląduje w pojemniku "
                        + "NAJBLIŻSZYM jego działki.\n\n"))
                .append(body("Z tej samej skrzyni bierze nasiona "
                        + "i mączkę kostną."))));

        pages.add(page(Text.empty()
                .append(title("2b. BEZ SKRZYNI\n\n"))
                .append(warn("Nie ma skrzyni - plony lecą na ziemię "
                        + "i znikają.\n\n"))
                .append(hint("Postaw skrzynię, zanim zatrudnisz "
                        + "kogokolwiek."))));

        pages.add(page(Text.empty()
                .append(title("3. TABLICA\n\n"))
                .append(body("Wpisz /crew\n\n"))
                .append(body("Górny rząd to twoi ludzie. Kliknij "
                        + "jednego, żeby go wybrać."))));

        pages.add(page(Text.empty()
                .append(title("3b. SZKOLENIE\n\n"))
                .append(body("Po wybraniu kupujesz mu tempo, zasięg "
                        + "działki albo zawód.\n\n"))
                .append(warn("Każdy szkoli się osobno. Kupione u "
                        + "jednego nie działa u drugiego."))));

        pages.add(page(Text.empty()
                .append(title("4. TEMPO\n\n"))
                .append(body(TrapCrew.PACE_NAME[0] + ": jedna czynność "
                        + "co " + TrapCrew.paceLabel(0) + "\n\n"))
                .append(body(TrapCrew.PACE_NAME[top] + ": co "
                        + TrapCrew.paceLabel(top) + ", i szybciej "
                        + "chodzi"))));

        pages.add(page(Text.empty()
                .append(title("4b. CENA TEMPA\n\n"))
                .append(body("Do kupienia " + top + " poziomów.\n\n"))
                .append(body("Od " + TrapCrew.PACE_COST[1] + "e do "
                        + TrapCrew.PACE_COST[top] + "e.\n\n"))
                .append(hint("Tempo kupuj w pierwszej kolejności."))));

        pages.add(page(Text.empty()
                .append(title("5. ZASIĘG DZIAŁKI\n\n"))
                .append(body("Na start pracuje w kwadracie "
                        + TrapCrew.REACH_BLOCKS[0]
                        + " bloków wokół swojego miejsca.\n\n"))
                .append(body("Można rozszerzyć do "
                        + TrapCrew.REACH_BLOCKS[wide] + " bloków."))));

        pages.add(page(Text.empty()
                .append(title("5b. UWAGA\n\n"))
                .append(warn("Szerszy zasięg to więcej terenu i WYŻSZA "
                        + "pensja, ale NIE większa szybkość.\n\n"))
                .append(hint("Bezczynny robotnik i tak bierze kasę."))));

        pages.add(page(Text.empty()
                .append(title("5c. PRZENOSZENIE\n\n"))
                .append(body("Stań tam, gdzie ma teraz pracować, "
                        + "i otwórz /crew.\n\n"))
                .append(body("Kliknij \"Pracuj tutaj\" - przeniesie się "
                        + "razem z działką.\n\n"))
                .append(hint("Działa też między wymiarami."))));

        jobs(pages);

        pages.add(page(Text.empty()
                .append(title("8. PENSJE\n\n"))
                .append(body("Podstawa: " + TrapCrew.WAGE + "e na osobę "
                        + "za każde pięć minut PRACY.\n\n"))
                .append(warn("Każdy poziom tempa, zasięgu i zawodu "
                        + "podnosi tę stawkę."))));

        pages.add(page(Text.empty()
                .append(title("8b. KIEDY LICZNIK STOI\n\n"))
                .append(body("Zegar zatrzymuje się o zmroku i kiedy się "
                        + "wylogujesz.\n\n"))
                .append(hint("Noce są darmowe."))));

        pages.add(page(Text.empty()
                .append(title("8c. BRAK PIENIĘDZY\n\n"))
                .append(body("Nie odchodzą od razu - najpierw dostajesz "
                        + "ostrzeżenie.\n\n"))
                .append(body("Masz " + TrapCrew.GRACE_PACKETS
                        + " niezapłaconych wypłat, czyli około dwóch "
                        + "dni."))));

        pages.add(page(Text.empty()
                .append(title("8d. RATUNEK\n\n"))
                .append(body("Zapłać JEDNĄ wypłatę, a całe zaległości "
                        + "są umarzane.\n\n"))
                .append(hint("Nie musisz spłacać wszystkiego."))));

        pages.add(page(Text.empty()
                .append(title("9. GODZINY PRACY\n\n"))
                .append(body("Domyślnie pracują tylko za dnia.\n\n"))
                .append(body("O zmroku szukają łóżka w obrębie działki "
                        + "i kładą się spać."))));

        pages.add(page(Text.empty()
                .append(title("9b. ŁÓŻKO\n\n"))
                .append(warn("Postaw im łóżko na działce.\n\n"))
                .append(hint("Zegar pensji też staje na noc, więc "
                        + "ciemność nic nie kosztuje."))));

        pages.add(page(Text.empty()
                .append(title("9c. NOCNA ZMIANA\n\n"))
                .append(body("Możesz przestawić kogoś na noce - wtedy "
                        + "nie przerywa pracy w ogóle.\n\n"))
                .append(body("Kosztuje +"
                        + Math.round((TrapCrew.NIGHT_RATE - 1) * 100)
                        + "% do pensji."))));

        pages.add(page(Text.empty()
                .append(title("9c2. CZY WARTO\n\n"))
                .append(warn("Na nocnej zmianie zegar chodzi całą noc, "
                        + "więc płacisz też za noc.\n\n"))
                .append(hint("Dwa razy więcej pracy za trochę ponad "
                        + "dwa razy więcej pieniędzy."))));

        pages.add(page(Text.empty()
                .append(title("10. ZAPIS EKIPY\n\n"))
                .append(body("/crew save <nazwa>\n"))
                .append(body("/crew plans\n"))
                .append(body("/crew load <nazwa>\n\n"))
                .append(hint("Zapisuje cały układ ekipy."))));

        pages.add(page(Text.empty()
                .append(title("10b. WCZYTYWANIE\n\n"))
                .append(body("Wczytanie stawia tych samych ludzi na tych "
                        + "samych działkach, już wyszkolonych.\n\n"))
                .append(body("Za tyle, ile kosztowało pierwszy raz.\n\n"))
                .append(hint("Odejście ekipy zapisuje się samo."))));

        pages.add(page(Text.empty()
                .append(title("11. CZEGO NIE ZROBIĄ\n\n"))
                .append(body("- nie ściągną suszu z suszarki za wcześnie\n"))
                .append(body("- nie użyją mączki na twoich roślinach\n"))
                .append(body("- nie zdepczą zaoranej ziemi\n"))));

        pages.add(page(Text.empty()
                .append(title("11b. DLACZEGO\n\n"))
                .append(body("Dwa pierwsze punkty kosztowałyby cię klasę "
                        + "towaru.\n\n"))
                .append(hint("Nie odchodzą też z działki."))));

        pages.add(page(Text.empty()
                .append(title("12. GDY CIĘ NIE MA\n\n"))
                .append(body("Działka pracuje niezależnie od tego, gdzie "
                        + "jesteś - byle byś był zalogowany.\n\n"))
                .append(hint("Nie musisz nad nikim stać."))));

        pages.add(page(Text.empty()
                .append(title("12b. PO WYLOGOWANIU\n\n"))
                .append(body("Wylogujesz się - pole zasypia.\n\n"))
                .append(hint("Pensje też przestają lecieć."))));

        pages.add(page(Text.empty()
                .append(title("13. ZACIĄŁ SIĘ?\n\n"))
                .append(body("Odszedł gdzieś albo utknął? Otwórz /crew "
                        + "i kliknij bat.\n\n"))
                .append(body("Wraca na miejsce i przerywa przerwę. "
                        + "Za darmo."))));

        pages.add(page(Text.empty()
                .append(title("13b. GDY ZGINĄŁ\n\n"))
                .append(body("Jeśli coś go ZJADŁO, bat stawia na jego "
                        + "miejsce nową osobę.\n\n"))
                .append(hint("Już wyszkoloną tak samo. Nie płacisz "
                        + "za szkolenie drugi raz."))));

        pages.add(page(Text.empty()
                .append(title("14. CZY SIĘ OPŁACA\n\n"))
                .append(warn("Robotnik bez roboty to strata "
                        + "pieniędzy.\n\n"))
                .append(body("Policz, ile działka daje na pięć minut, "
                        + "i porównaj z pensją."))));

        pages.add(page(Text.empty()
                .append(title("14b. GDZIE SPRAWDZIĆ\n\n"))
                .append(body("Komenda /crew pokazuje sumę pensji i to, "
                        + "ile masz przy sobie.\n\n"))
                .append(hint("Zaglądaj tam regularnie."))));
    }

    /** The five you can teach, priced off the enum so the board can't disagree. */
    private static void jobs(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("6. DWA ZAWODY\n\n"))
                .append(body("Jedna osoba może mieć maksymalnie "
                        + TrapCrew.SLOTS + " zawody.\n\n"))
                .append(body("Potrzebujesz trzeciej rzeczy? Zatrudnij "
                        + "trzecią osobę. Limit: " + TrapCrew.MAX_HANDS
                        + "."))));

        pages.add(page(Text.empty()
                .append(title("6b. ZMIANA ZAWODU\n\n"))
                .append(body("Zbieranie plonów jest darmowe i ma "
                        + "każdy.\n\n"))
                .append(hint("Shift+LPM usuwa wykupiony zawód, żeby "
                        + "zwolnić miejsce."))));

        // Five to a page. The whole list on one page was already at the ~14
        // line ceiling this book truncates at, and laundering pushed it over
        // twice: a ninth entry, and a four-figure price that makes
        // "Laundering  1200e  +18e" too wide to sit on one line. Paged rather
        // than shortened, so the tenth job lands on a page with room on it
        // instead of quietly falling off the end of one.
        List<TrapCrew.Job> paid = new ArrayList<>();
        for (TrapCrew.Job job : TrapCrew.Job.values()) {
            if (!job.free()) {
                paid.add(job);
            }
        }
        for (int from = 0; from < paid.size(); from += JOBS_A_PAGE) {
            MutableText list = Text.empty().append(title(from == 0
                    ? "7. ZAWODY\n\n" : "7. ZAWODY, DALEJ\n\n"));
            for (TrapCrew.Job job : paid.subList(from,
                    Math.min(from + JOBS_A_PAGE, paid.size()))) {
                list.append(body(job.display() + "  " + job.cost() + "e  +"
                        + job.wage() + "e\n"));
            }
            pages.add(page(list.append(Text.literal("\n"))
                    .append(hint("cena, potem dodatek do pensji"))));
        }

        pages.add(page(Text.empty()
                .append(title("7b. JEDNA SKRZYNIA\n\n"))
                .append(body("Robotnik korzysta z pojemnika NAJBLIŻSZEGO "
                        + "swojemu miejscu. Tylko z tego jednego.\n\n"))
                .append(body("Wszystko, czego potrzebuje, ma być w "
                        + "środku."))));

        pages.add(page(Text.empty()
                .append(title("7b2. PUŁAPKA\n\n"))
                .append(warn("Postawisz inną skrzynię bliżej - "
                        + "przerzuci się na nią.\n\n"))
                .append(hint("I nagle przestanie mieć swoje nasiona."))));

        pages.add(page(Text.empty()
                .append(title("7c. NIE SKRĘCA?\n\n"))
                .append(body("W skrzyni muszą być SUSZONE szyszki ORAZ "
                        + "papier.\n\n"))
                .append(warn("Świeże szyszki nie wystarczą."))));

        pages.add(page(Text.empty()
                .append(title("7c2. BEZ STOŁU\n\n"))
                .append(body("Stół rzemieślniczy nie jest potrzebny.\n\n"))
                .append(hint("Tablica /crew pokazuje, które zawody "
                        + "skrzynia jest w stanie obsłużyć."))));
    }

    private static void network(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("14. DILERZY\n\n"))
                .append(body("Kucnij i kliknij PPM telefonem na kartę.\n\n"))
                .append(body("Możesz zatrudnić do " + TrapDealers.MAX_DEALERS
                        + " dilerów. Sprzedają towar, kiedy ciebie nie "
                        + "ma."))));

        pages.add(page(Text.empty()
                .append(title("14b. WEZWANIE\n\n"))
                .append(body("Kliknij dilera na liście, żeby wezwać go "
                        + "do siebie.\n\n"))
                .append(body("Kliknij go PPM, żeby otworzyć jego "
                        + "zeszyt.\n\n"))
                .append(hint("Włóż towar, wyjmij pieniądze."))));

        pages.add(page(Text.empty()
                .append(title("14c. WAŻNE\n\n"))
                .append(warn("Diler stojący przed tobą NIC nie "
                        + "sprzedaje.\n\n"))
                .append(body("Odeślij go, jak go załadujesz."))));

        pages.add(page(Text.empty()
                .append(title("14d. PORY DNIA\n\n"))
                .append(body("O północy sprzedają jakieś trzy razy "
                        + "więcej niż w południe.\n\n"))
                .append(hint("Załaduj wieczorem, odbierz rano."))));

        pages.add(page(Text.empty()
                .append(title("14e. UWAGA POLICJI\n\n"))
                .append(body("Wysoka uwaga policji spowalnia dilerów.\n\n"))
                .append(hint("Ale ich nie zatrzymuje."))));

        pages.add(page(Text.empty()
                .append(title("14f. POZIOMY\n\n"))
                .append(body("Diler ma poziom od 1 do 8.\n\n"))
                .append(body("Wyższy = więcej slotów, szybsza sprzedaż, "
                        + "rzadsze napady, ale większa prowizja."))));

        pages.add(page(Text.empty()
                .append(title("14f2. CZY WARTO\n\n"))
                .append(body("Tak. Wyższy przerób z nawiązką pokrywa "
                        + "wyższą prowizję.\n\n"))
                .append(hint("Poziom 8 nosi 18 slotów towaru."))));

        pages.add(page(Text.empty()
                .append(title("14g. TWOJA REPUTACJA\n\n"))
                .append(body("Reputacja ze zleceń obniża koszt "
                        + "zatrudnienia, nawet o 40%.\n\n"))
                .append(body("Na liście pojawiają się też lepsi ludzie."))));

        pages.add(page(Text.empty()
                .append(title("14g2. I SZKOLENIE\n\n"))
                .append(body("Przy wysokiej reputacji twoi dilerzy "
                        + "szybciej się uczą.\n\n"))
                .append(hint("Kurierka opłaca się podwójnie."))));

        pages.add(page(Text.empty()
                .append(title("14h. LISTA CHĘTNYCH\n\n"))
                .append(body("Odświeża się sama co dziesięć minut.\n\n"))
                .append(body("Albo zapłać " + TrapDealers.REROLL_COST
                        + "e, żeby popytać od razu."))));

        pages.add(page(Text.empty()
                .append(title("14i. RYZYKO\n\n"))
                .append(warn("Towar na ulicy przyspiesza naloty.\n\n"))
                .append(body("Im więcej dilerów pracuje, tym częściej "
                        + "ktoś przychodzi do ciebie."))));

        pages.add(page(Text.empty()
                .append(title("14j. NASYCENIE\n\n"))
                .append(body("Każdy kolejny diler w tej samej okolicy "
                        + "sprzedaje mniej niż poprzedni.\n\n"))
                .append(warn("Czterech to MNIEJ niż cztery razy jeden."))));

        pages.add(page(Text.empty()
                .append(title("14j2. CO ROBIĆ\n\n"))
                .append(body("Rozstawiaj dilerów po różnych "
                        + "miejscach.\n\n"))
                .append(hint("Im dalej od siebie, tym lepiej."))));
    }

    private static void supply(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("15. SKĄD NASIONA\n\n"))
                .append(body("- z rozbijania trawy\n"))
                .append(body("- ze skrzyń w strukturach\n"))
                .append(body("- z dzikich krzaków na równinach, "
                        + "sawannie i w dżungli\n"))));

        pages.add(page(Text.empty()
                .append(title("15b. OD HANDLARZY\n\n"))
                .append(body("Wędrowny handlarz sprzedaje jedną odmianę, "
                        + "po 5 szmaragdów.\n\n"))
                .append(hint("Rolnicy sprzedają nasiona i skupują "
                        + "świeże szyszki."))));

        pages.add(page(Text.empty()
                .append(title("16. SPRZEDAŻ NPC\n\n"))
                .append(body("Wieśniacy płacą według klasy towaru "
                        + "i sprawdzają go.\n\n"))
                .append(body("Jedna transakcja to 4 suszone szyszki "
                        + "albo 2 skręty."))));

        pages.add(page(Text.empty()
                .append(title("16b. RÓŻNICA W CENIE\n\n"))
                .append(body(Quality.SWILL.display() + ": "
                        + Quality.SWILL.emeralds() + "e\n"))
                .append(Text.literal(Quality.FIRE.display() + ": ")
                        .formatted(Quality.FIRE.colour()))
                .append(body(Quality.FIRE.emeralds() + "e\n\n"))
                .append(warn("Ta sama praca, "
                        + (Quality.FIRE.emeralds() / Quality.SWILL.emeralds())
                        + "x więcej kasy. Dbaj o jakość."))));

        pages.add(page(Text.empty()
                .append(title("17. KLIENCI Z ULICY\n\n"))
                .append(body("Kiedy nosisz przy sobie towar, podchodzą "
                        + "do ciebie chętni.\n\n"))
                .append(body("Chcą konkretnej odmiany, proszku albo "
                        + "mieszanki."))));

        pages.add(page(Text.empty()
                .append(title("17b. CZEGO CHCĄ\n\n"))
                .append(body("Ich NAZWA mówi wszystko: jaką odmianę "
                        + "i w jakiej postaci.\n\n"))
                .append(body("Susz, skręty albo obojętnie.\n\n"))
                .append(hint("Proszą tylko o to, co masz przy sobie."))));

        pages.add(page(Text.empty()
                .append(title("17c. ZŁA POSTAĆ\n\n"))
                .append(warn("PPM z niewłaściwą postacią towaru nie "
                        + "robi nic.\n\n"))
                .append(hint("Przeczytaj nazwę, zanim klikniesz."))));

        pages.add(page(Text.empty()
                .append(title("17d. MIESZANKI\n\n"))
                .append(body("Jedni chcą dowolnej mieszanki.\n\n"))
                .append(body("Inni proszą o konkretną nazwaną mieszankę."))));

        pages.add(page(Text.empty()
                .append(title("17d2. STAWKI\n\n"))
                .append(body("Obie płacą więcej niż pojedyncza odmiana. "
                        + "Nazwana płaci najwięcej.\n\n"))
                .append(hint("Im więcej składników, tym więcej kasy."))));

        pages.add(page(Text.empty()
                .append(title("17e. TRANSAKCJA\n\n"))
                .append(body("Weź towar do ręki i kliknij klienta PPM. "
                        + "Nie ma żadnego menu.\n\n"))
                .append(body("Bierze do " + TrapDealing.UNITS_WANTED
                        + " sztuk, płacąc za każdą osobno."))));

        pages.add(page(Text.empty()
                .append(title("17f. ODPRAWA\n\n"))
                .append(body("Kucnij i kliknij, żeby go odesłać.\n\n"))
                .append(body("Sam odchodzi po "
                        + TrapDealing.LIFETIME_TICKS / 20 / 60 + " min.\n\n"))
                .append(warn("Sprzedaż z ręki może ściągnąć napad."))));
    }

    // --- text helpers ---------------------------------------------------------

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private static String joinInts(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            out.append(values[i]);
            if (i < values.length - 1) {
                out.append('/');
            }
        }
        return out.toString();
    }

    private static RawFilteredPair<Text> page(Text text) {
        return RawFilteredPair.of(text);
    }

    private static MutableText title(String s) {
        return Text.literal(s).formatted(Formatting.DARK_GREEN, Formatting.BOLD);
    }

    private static MutableText body(String s) {
        return Text.literal(s).formatted(Formatting.BLACK);
    }

    private static MutableText item(String s) {
        return Text.literal(s).formatted(Formatting.DARK_PURPLE);
    }

    private static MutableText hint(String s) {
        return Text.literal(s).formatted(Formatting.DARK_GRAY, Formatting.ITALIC);
    }

    private static MutableText warn(String s) {
        return Text.literal(s).formatted(Formatting.DARK_RED);
    }
}
