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
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The television: six screens, one of which is the whole point.
 *
 * <h2>The studio page</h2>
 *
 * Everything else here is a list. The studio is where a bet is either found or
 * not, and it is built on one rule: <b>it prints the inputs and never the
 * answer.</b> No rating, no percentage, no "value" flag, no tip. It says the
 * going is soft, that this one likes it soft, that the other one has two out
 * and played yesterday -- and it lets the price stand there saying none of
 * that.
 *
 * A screen that printed a computed probability would be a screen that plays
 * the game for you, and the game is reading the two panels. A screen that
 * printed nothing would be a slot machine with team names on it. This is the
 * line between those, and every addition to this file has to stay on it.
 *
 * A vanilla 9x6 container like every other screen in this mod -- the client
 * draws stacks the server sets and computes nothing, which is the only kind of
 * screen that behaves with Polymer items. See docs/TRAPS.md.
 */
public class TelevisionScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int FOOTER = SIZE - 9;

    private static final int BACK_SLOT = FOOTER;
    private static final int PREV_SLOT = FOOTER + 1;
    private static final int NEXT_SLOT = FOOTER + 2;
    private static final int SLIP_SLOT = FOOTER + 4;
    private static final int MINE_SLOT = FOOTER + 5;
    private static final int RESULTS_SLOT = FOOTER + 6;
    private static final int COLLECT_SLOT = FOOTER + 7;
    private static final int PURSE_SLOT = FOOTER + 8;

    private static final int HEADER_SLOT = 4;
    /** Where the eight competitions sit on the channel list. */
    private static final int[] CHANNELS = {10, 12, 14, 16, 19, 21, 23, 25};
    /** Where a coupon's legs sit, and where its own slips sit on the list. */
    private static final int[] FOUR = {20, 22, 24, 26};
    private static final int[] EIGHT = {10, 12, 14, 16, 19, 21, 23, 25};

    private static final int SLIP_SUMMARY = 4;
    private static final int STAKE_SLOT = 30;
    private static final int PLACE_SLOT = 32;

    /** One icon per competition, in the order of {@link TrapSports#LEAGUES}. */
    private static final Item[] ICONS = {
            Items.WHITE_BANNER, Items.RED_BANNER,
            Items.SLIME_BALL, Items.SLIME_BLOCK,
            Items.MINECART, Items.SADDLE,
            Items.ORANGE_CONCRETE, Items.BLUE_CONCRETE,
    };

    private enum View { CHANNELS, BOARD, STUDIO, SLIP, MINE, RESULTS }

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity punter;
    private final List<TrapSports.Leg> slip = new ArrayList<>();

    private View view = View.CHANNELS;
    private int league;
    private int fixture = -1;
    private int page;
    private int stakeChoice = 2;
    /** What each slot in the current painting means. Cleared on every repaint. */
    private final java.util.Map<Integer, int[]> actions = new java.util.HashMap<>();

    /**
     * Every set currently switched on, repainted once a second.
     *
     * Without it the countdown freezes the moment somebody opens the screen
     * and a fixture can run while its price is still sitting there, clickable.
     */
    private static final List<TelevisionScreenHandler> ON = new ArrayList<>();

    /** Repaint every open set. Called from the bookmaker's own tick. */
    public static void refreshAll() {
        ON.removeIf(set -> set.punter.isDisconnected());
        for (TelevisionScreenHandler set : List.copyOf(ON)) {
            set.paint();
        }
    }

    public TelevisionScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.punter = (ServerPlayerEntity) playerInventory.player;
        ON.add(this);

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

    @Override
    public void onClosed(PlayerEntity closer) {
        ON.remove(this);
        super.onClosed(closer);
    }

    // --- painting ---------------------------------------------------------------

    private void paint() {
        actions.clear();
        ItemStack screen = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        screen.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, screen.copy());
        }

        switch (view) {
            case CHANNELS -> paintChannels();
            case BOARD -> paintBoard();
            case STUDIO -> paintStudio();
            case SLIP -> paintSlip();
            case MINE -> paintMine();
            case RESULTS -> paintResults();
        }
        paintFooter();
        sendContentUpdates();
    }

    private void paintFooter() {
        if (view != View.CHANNELS) {
            display.setStack(BACK_SLOT, button(Items.ARROW, "Wstecz",
                    List.of(line("Z powrotem do listy kanałów.", Formatting.GRAY))));
        }
        display.setStack(SLIP_SLOT, button(Items.WRITABLE_BOOK,
                "Kupon (" + slip.size() + "/" + TrapMath.BOOK_MAX_LEGS + ")", List.of(
                        line(slip.isEmpty() ? "Pusty. Kliknij kurs, żeby coś dodać."
                                : "Stawka " + TrapMath.BOOK_STAKES[stakeChoice] + "e, kurs "
                                + TrapMath.odds(TrapMath.slipOdds(prices())) + ".",
                                Formatting.GRAY))));
        display.setStack(MINE_SLOT, button(Items.WRITTEN_BOOK,
                "Moje kupony (" + TrapSports.slipsOf(punter).size() + ")",
                List.of(line("Wszystko, co jeszcze się nie rozstrzygnęło.", Formatting.GRAY))));
        display.setStack(RESULTS_SLOT, button(Items.CLOCK, "Wyniki",
                List.of(line("Co się skończyło w ostatnim czasie.", Formatting.GRAY))));

        int waiting = TrapSports.purse(punter);
        display.setStack(COLLECT_SLOT, waiting > 0
                ? button(Items.CHEST, "Do odbioru: " + waiting + "e", List.of(
                        line("Wygrane, które weszły, gdy cię nie było.", Formatting.GRAY),
                        line("Kliknij, żeby odebrać.", Formatting.GREEN)))
                : button(Items.GRAY_STAINED_GLASS_PANE, "Nic do odbioru",
                        List.of(line("Wszystko rozliczone.", Formatting.DARK_GRAY))));

        ItemStack purse = new ItemStack(Items.GOLD_NUGGET);
        purse.set(DataComponentTypes.CUSTOM_NAME, plain("Kasa: ").formatted(Formatting.GRAY)
                .append(plain(TrapMarket.wealthOf(punter) + "e")
                        .formatted(Formatting.GREEN, Formatting.BOLD)));
        display.setStack(PURSE_SLOT, purse);
    }

    private void paintChannels() {
        display.setStack(HEADER_SLOT, button(Items.PAPER, "Program", List.of(
                line("Osiem rozgrywek, każda ze swoim terminarzem.", Formatting.GRAY),
                line("Spotkania startują same, niezależnie od ciebie.", Formatting.GRAY),
                Text.empty(),
                line("Kurs zna tylko renomę i gospodarza.", Formatting.YELLOW),
                line("Formę, składy, odpoczynek i warunki", Formatting.DARK_GRAY),
                line("znajdziesz w studiu każdego spotkania.", Formatting.DARK_GRAY))));

        for (int i = 0; i < TrapSports.LEAGUES.length && i < CHANNELS.length; i++) {
            TrapSports.League competition = TrapSports.LEAGUES[i];
            List<TrapSports.Fixture> board = TrapSports.board(i);
            List<Text> lore = new ArrayList<>();
            lore.add(line(competition.sport, Formatting.DARK_GRAY));
            lore.add(Text.empty());
            for (TrapSports.Fixture next : board) {
                lore.add(line(headline(next) + "   za " + until(next), Formatting.GRAY));
            }
            display.setStack(CHANNELS[i], button(ICONS[i], competition.name, lore));
            actions.put(CHANNELS[i], new int[]{ACT_LEAGUE, i});
        }
    }

    private void paintBoard() {
        TrapSports.League competition = TrapSports.LEAGUES[league];
        display.setStack(HEADER_SLOT, button(ICONS[league], competition.name, List.of(
                line(competition.sport, Formatting.DARK_GRAY),
                Text.empty(),
                line("Kliknij nazwę spotkania, żeby wejść do studia.", Formatting.GRAY),
                line("Kliknij kurs, żeby dodać go do kuponu.", Formatting.GRAY))));

        List<TrapSports.Fixture> board = TrapSports.board(league);
        for (int row = 0; row < 4 && row < board.size(); row++) {
            TrapSports.Fixture next = board.get(row);
            int origin = (row + 1) * 9;
            display.setStack(origin + 1, fixtureCard(next));
            actions.put(origin + 1, new int[]{ACT_STUDIO, next.id});

            if (next.field.length > 2) {
                display.setStack(origin + 5, button(Items.SPYGLASS,
                        next.field.length + " w stawce", List.of(
                                line("Kursy w studiu.", Formatting.GRAY))));
                actions.put(origin + 5, new int[]{ACT_STUDIO, next.id});
                continue;
            }
            // 1 X 2, in the order every coupon in the world prints it.
            int[] order = competition.draws ? new int[]{0, 2, 1} : new int[]{0, 1};
            int[] spots = competition.draws ? new int[]{origin + 4, origin + 5, origin + 6}
                    : new int[]{origin + 4, origin + 6};
            for (int i = 0; i < order.length; i++) {
                display.setStack(spots[i], priceTag(next, 0, order[i]));
                actions.put(spots[i], new int[]{ACT_PICK, next.id, 0, order[i]});
            }
        }
    }

    private void paintStudio() {
        TrapSports.Fixture match = TrapSports.fixture(fixture);
        if (match == null || match.settled()) {
            view = View.BOARD;
            paintBoard();
            return;
        }
        if (match.field.length == 2) {
            paintDuel(match);
        } else {
            paintField(match);
        }
    }

    private void paintDuel(TrapSports.Fixture match) {
        TrapSports.League competition = match.league();
        display.setStack(HEADER_SLOT, conditionsCard(match));
        display.setStack(11, runnerCard(match, 0));
        display.setStack(15, runnerCard(match, 1));
        display.setStack(13, historyCard(match));

        display.setStack(20, priceTag(match, 0, 0));
        actions.put(20, new int[]{ACT_PICK, match.id, 0, 0});
        display.setStack(24, priceTag(match, 0, 1));
        actions.put(24, new int[]{ACT_PICK, match.id, 0, 1});
        if (competition.draws) {
            display.setStack(22, priceTag(match, 0, 2));
            actions.put(22, new int[]{ACT_PICK, match.id, 0, 2});
        }

        display.setStack(31, button(Items.SPYGLASS, "Jak to czytać", List.of(
                line("Kurs powstał z renomy i tego, kto gra u siebie.", Formatting.GRAY),
                line("Nic więcej bukmacher nie policzył.", Formatting.GRAY),
                Text.empty(),
                line("Forma, absencje, odpoczynek i warunki", Formatting.YELLOW),
                line("stoją na kartach obok i w kursie ich nie ma.", Formatting.YELLOW),
                Text.empty(),
                line("Jeśli trzy z nich mówią to samo,", Formatting.DARK_GRAY),
                line("kurs jest za wysoki. Jeśli się kłócą -- odpuść.",
                        Formatting.DARK_GRAY))));
    }

    private void paintField(TrapSports.Fixture race) {
        display.setStack(HEADER_SLOT, conditionsCard(race));
        int pages = (race.field.length + 3) / 4;
        page = Math.max(0, Math.min(page, pages - 1));
        for (int row = 0; row < 4; row++) {
            int index = page * 4 + row;
            if (index >= race.field.length) {
                break;
            }
            int origin = (row + 1) * 9;
            display.setStack(origin + 1, runnerCard(race, index));
            display.setStack(origin + 5, priceTag(race, 0, index));
            actions.put(origin + 5, new int[]{ACT_PICK, race.id, 0, index});
            if (race.placeOdds != null) {
                display.setStack(origin + 7, priceTag(race, 1, index));
                actions.put(origin + 7, new int[]{ACT_PICK, race.id, 1, index});
            }
        }
        if (pages > 1) {
            display.setStack(PREV_SLOT, button(Items.SPECTRAL_ARROW, "Poprzednia strona",
                    List.of(line("Strona " + (page + 1) + "/" + pages, Formatting.GRAY))));
            display.setStack(NEXT_SLOT, button(Items.SPECTRAL_ARROW, "Następna strona",
                    List.of(line("Strona " + (page + 1) + "/" + pages, Formatting.GRAY))));
        }
    }

    private void paintSlip() {
        display.setStack(SLIP_SUMMARY, slipSummary());
        for (int i = 0; i < slip.size() && i < FOUR.length; i++) {
            TrapSports.Leg leg = slip.get(i);
            TrapSports.Fixture match = TrapSports.fixture(leg.fixture);
            List<Text> lore = new ArrayList<>();
            if (match == null || match.settled()) {
                lore.add(line("Spotkanie już wystartowało. Usuń tę pozycję.", Formatting.RED));
            } else {
                lore.add(line(headline(match), Formatting.GRAY));
                lore.add(line("Start za " + until(match), Formatting.DARK_GRAY));
                lore.add(Text.empty());
                lore.add(line("Kurs " + TrapMath.odds(leg.odds), Formatting.GREEN));
            }
            lore.add(Text.empty());
            lore.add(line("Kliknij, żeby usunąć.", Formatting.DARK_GRAY));
            display.setStack(FOUR[i], button(Items.PAPER, selectionName(match, leg), lore));
            actions.put(FOUR[i], new int[]{ACT_DROP, i});
        }

        int stake = TrapMath.BOOK_STAKES[stakeChoice];
        ItemStack stakeTag = new ItemStack(Items.GOLD_INGOT,
                Math.max(1, Math.min(64, stake / 16)));
        stakeTag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stawka: ").formatted(Formatting.GRAY)
                        .append(plain(stake + "e").formatted(Formatting.GREEN, Formatting.BOLD)));
        stakeTag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Klik: wyżej, prawy klik: niżej.", Formatting.DARK_GRAY))));
        display.setStack(STAKE_SLOT, stakeTag);

        int duty = TrapCity.dutyOn(stake, TrapCity.Duty.GAMING);
        boolean ready = !slip.isEmpty() && TrapMarket.wealthOf(punter) >= stake + duty;
        display.setStack(PLACE_SLOT, button(ready ? Items.LEVER : Items.GRAY_STAINED_GLASS_PANE,
                ready ? "POSTAW" : "Nie teraz", List.of(
                        line(slip.isEmpty() ? "Kupon jest pusty."
                                : !ready ? "Nie stać cię na tę stawkę."
                                : "Do wygrania " + TrapMath.slipReturn(stake,
                                TrapMath.slipOdds(prices())) + "e.",
                                ready ? Formatting.GREEN : Formatting.DARK_GRAY))));
    }

    private ItemStack slipSummary() {
        int stake = TrapMath.BOOK_STAKES[stakeChoice];
        int odds = TrapMath.slipOdds(prices());
        List<Text> lore = new ArrayList<>();
        lore.add(line(slip.size() == 1 ? "Pojedynczy" : slip.size() + " pozycje na kuponie",
                Formatting.GRAY));
        lore.add(line("Kurs łączny " + TrapMath.odds(odds), Formatting.WHITE));
        lore.add(line("Stawka " + stake + "e  ->  " + TrapMath.slipReturn(stake, odds) + "e",
                Formatting.GREEN));
        int duty = TrapCity.dutyOn(stake, TrapCity.Duty.GAMING);
        if (duty > 0) {
            lore.add(line("Danina hazardowa: " + duty + "e ponad stawkę.", Formatting.GRAY));
        }
        if (TrapMath.slipReturn(stake, odds) >= TrapMath.BOOK_MAX_PAYOUT) {
            lore.add(line("Limit wypłaty: " + TrapMath.BOOK_MAX_PAYOUT + "e.", Formatting.RED));
        }
        lore.add(Text.empty());
        lore.add(line("Kursy mnożą się, ale marża też.", Formatting.DARK_GRAY));
        lore.add(line("Czwórka to cztery razy zapłacona prowizja.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Kupon znika po zamknięciu telewizora.", Formatting.DARK_GRAY));
        return button(Items.WRITABLE_BOOK, "Kupon", lore);
    }

    private void paintMine() {
        List<TrapSports.Slip> mine = TrapSports.slipsOf(punter);
        display.setStack(HEADER_SLOT, button(Items.WRITTEN_BOOK,
                "Moje kupony (" + mine.size() + "/" + TrapSports.MAX_SLIPS + ")",
                List.of(line("Rozliczają się same, gdy zejdzie ostatnia pozycja.",
                        Formatting.GRAY))));
        for (int i = 0; i < mine.size() && i < EIGHT.length; i++) {
            TrapSports.Slip open = mine.get(i);
            List<Text> lore = new ArrayList<>();
            for (TrapSports.Leg leg : open.legs) {
                TrapSports.Fixture match = TrapSports.fixture(leg.fixture);
                String mark = leg.result == 1 ? "trafione" : leg.result == 0 ? "przepadło"
                        : match == null ? "w toku" : "za " + until(match);
                lore.add(line(selectionName(match, leg) + "  -- " + mark,
                        leg.result == 1 ? Formatting.GREEN
                                : leg.result == 0 ? Formatting.RED : Formatting.GRAY));
            }
            lore.add(Text.empty());
            lore.add(line("Stawka " + open.stake + "e, kurs " + TrapMath.odds(open.odds),
                    Formatting.GRAY));
            lore.add(line("Do wygrania " + open.returns() + "e", Formatting.GREEN));
            display.setStack(EIGHT[i], button(Items.PAPER,
                    "Kupon #" + open.id, lore));
        }
    }

    private void paintResults() {
        display.setStack(HEADER_SLOT, button(Items.CLOCK, "Wyniki", List.of(
                line("Ostatnie rozstrzygnięcia, najnowsze pierwsze.", Formatting.GRAY),
                line("Wyniki wchodzą do formy. Forma wchodzi do kolejnych spotkań.",
                        Formatting.DARK_GRAY))));
        List<TrapSports.Fixture> done = TrapSports.results();
        for (int i = 0; i < done.size() && i < 18; i++) {
            TrapSports.Fixture past = done.get(i);
            List<Text> lore = new ArrayList<>();
            lore.add(line(past.league().name, Formatting.DARK_GRAY));
            if (past.field.length == 2) {
                lore.add(line(past.winner == 2 ? "Remis"
                        : past.runner(past.winner).name + " wygrał", Formatting.WHITE));
            } else {
                for (int spot = 0; spot < past.podium.length; spot++) {
                    lore.add(line((spot + 1) + ". " + past.runner(past.podium[spot]).name,
                            spot == 0 ? Formatting.GOLD : Formatting.GRAY));
                }
            }
            display.setStack(9 + i, button(Items.PAPER, headline(past), lore));
        }
    }

    // --- the cards ---------------------------------------------------------------

    /**
     * One competitor, as everything the price did not take into account.
     *
     * No rating and no chance, on purpose -- see the class note. Whether the
     * five things listed here add up to a bet is the player's arithmetic, and
     * handing them the sum would be handing them the game.
     */
    private ItemStack runnerCard(TrapSports.Fixture match, int index) {
        TrapSports.League competition = match.league();
        TrapSports.Runner runner = match.runner(index);
        List<Text> lore = new ArrayList<>();
        lore.add(line(runner.note, Formatting.DARK_GRAY));
        lore.add(Text.empty());

        MutableText form = plain("Forma: ").formatted(Formatting.GRAY);
        if (runner.form.isEmpty()) {
            form.append(plain("jeszcze nie grał").formatted(Formatting.DARK_GRAY));
        } else {
            for (int i = 0; i < runner.form.length(); i++) {
                char mark = runner.form.charAt(i);
                form.append(plain(mark + " ").formatted(mark == 'W' ? Formatting.GREEN
                        : mark == 'R' ? Formatting.YELLOW : Formatting.RED));
            }
        }
        lore.add(form);
        lore.add(line(runner.absences == 0 ? "Skład: komplet"
                : "Skład: brakuje " + runner.absences
                + (runner.absences == 1 ? " zawodnika" : " zawodników"),
                runner.absences == 0 ? Formatting.GRAY : Formatting.RED));
        lore.add(line(restNote(runner.rest),
                runner.rest <= 1 ? Formatting.RED : Formatting.GRAY));
        lore.add(line("Styl: " + competition.styles[runner.style], Formatting.AQUA));
        if (match.home(index)) {
            lore.add(line("Gra u siebie.", Formatting.GRAY));
        }
        if (runner.played > 0) {
            lore.add(Text.empty());
            lore.add(line("Bilans tu: " + runner.won + " z " + runner.played,
                    Formatting.DARK_GRAY));
        }
        return button(Items.NAME_TAG, runner.name, lore);
    }

    private static String restNote(int rest) {
        return switch (rest) {
            case 0 -> "Odpoczynek: gra drugi raz z rzędu";
            case 1 -> "Odpoczynek: jedna runda przerwy";
            case 2 -> "Odpoczynek: dwie rundy przerwy";
            case 3 -> "Odpoczynek: trzy rundy przerwy";
            default -> "Odpoczynek: wypoczęty";
        };
    }

    /** Where it is played and what the going is. Never what that is worth. */
    private ItemStack conditionsCard(TrapSports.Fixture match) {
        TrapSports.League competition = match.league();
        List<Text> lore = new ArrayList<>();
        lore.add(line(competition.name, Formatting.DARK_GRAY));
        if (!match.venue.isEmpty()) {
            lore.add(line("Miejsce: " + match.venue, Formatting.WHITE));
        }
        lore.add(line("Warunki: " + competition.conditions[match.condition], Formatting.AQUA));
        lore.add(line("Start za " + until(match), Formatting.YELLOW));
        lore.add(Text.empty());
        lore.add(line("Kursy zamykają się w chwili startu.", Formatting.DARK_GRAY));
        return button(Items.COMPASS, headline(match), lore);
    }

    /** What these two have already done to each other, in this world. */
    private ItemStack historyCard(TrapSports.Fixture match) {
        int[] record = TrapSports.record(match.league, match.field[0], match.field[1]);
        List<Text> lore = new ArrayList<>();
        if (record[0] + record[1] == 0) {
            lore.add(line("Jeszcze się tu nie spotkali.", Formatting.DARK_GRAY));
        } else {
            lore.add(line(match.runner(0).name + "  " + record[0], Formatting.WHITE));
            lore.add(line(match.runner(1).name + "  " + record[1], Formatting.WHITE));
            lore.add(Text.empty());
            lore.add(line("Liczone tylko z tego serwera.", Formatting.DARK_GRAY));
        }
        return button(Items.WRITABLE_BOOK, "Bezpośrednie starcia", lore);
    }

    /**
     * A price, as a pile of emeralds that grows with it.
     *
     * The number is on the label and the size is in the hand: a 1.40 is one
     * emerald and a 12.00 is a dozen, so the board reads as a shape from
     * across the room before anybody has read a single figure off it.
     */
    private ItemStack priceTag(TrapSports.Fixture match, int market, int selection) {
        int odds = TrapSports.priceOf(match, market, selection);
        boolean picked = alreadyOn(match.id, market, selection);
        List<Text> lore = new ArrayList<>();
        lore.add(line(selectionLabel(match, market, selection), Formatting.WHITE));
        if (market == 1) {
            lore.add(line("Miejsce w pierwszej " + match.league().places + ".",
                    Formatting.GRAY));
        }
        lore.add(Text.empty());
        lore.add(line(picked ? "Już na kuponie." : "Kliknij, żeby dodać do kuponu.",
                picked ? Formatting.YELLOW : Formatting.GRAY));

        ItemStack tag = new ItemStack(picked ? Items.EMERALD_BLOCK : Items.EMERALD,
                Math.max(1, Math.min(64, odds / 100)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(TrapMath.odds(odds)).formatted(Formatting.GREEN, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack fixtureCard(TrapSports.Fixture match) {
        List<Text> lore = new ArrayList<>();
        if (!match.venue.isEmpty()) {
            lore.add(line(match.venue, Formatting.WHITE));
        }
        lore.add(line(match.league().conditions[match.condition], Formatting.AQUA));
        lore.add(line("Start za " + until(match), Formatting.YELLOW));
        lore.add(Text.empty());
        lore.add(line("Kliknij, żeby wejść do studia.", Formatting.GRAY));
        return button(Items.PAPER, headline(match), lore);
    }

    // --- names -------------------------------------------------------------------

    private static String headline(TrapSports.Fixture match) {
        if (match.field.length == 2) {
            return match.runner(0).name + " - " + match.runner(1).name;
        }
        return match.league().name + (match.venue.isEmpty() ? "" : ": " + match.venue);
    }

    private static String selectionLabel(TrapSports.Fixture match, int market, int selection) {
        if (market == 0 && selection >= match.field.length) {
            return "Remis";
        }
        String name = match.runner(selection).name;
        return market == 1 ? name + " -- miejsce" : name;
    }

    private static String selectionName(TrapSports.Fixture match, TrapSports.Leg leg) {
        return match == null ? "Zakład #" + leg.fixture
                : selectionLabel(match, leg.market, leg.selection);
    }

    private String until(TrapSports.Fixture match) {
        return TrapSports.untilOff(punter.getServer(), match);
    }

    // --- clicking ---------------------------------------------------------------

    private static final int ACT_LEAGUE = 1;
    private static final int ACT_STUDIO = 2;
    private static final int ACT_PICK = 3;
    private static final int ACT_DROP = 4;

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        switch (index) {
            case BACK_SLOT -> {
                if (view != View.CHANNELS) {
                    view = view == View.STUDIO ? View.BOARD : View.CHANNELS;
                    page = 0;
                    click(1.0F);
                    paint();
                }
                return;
            }
            case SLIP_SLOT -> {
                view = View.SLIP;
                click(1.2F);
                paint();
                return;
            }
            case MINE_SLOT -> {
                view = View.MINE;
                click(1.2F);
                paint();
                return;
            }
            case RESULTS_SLOT -> {
                view = View.RESULTS;
                click(1.2F);
                paint();
                return;
            }
            case COLLECT_SLOT -> {
                int taken = TrapSports.collect(punter);
                if (taken > 0) {
                    punter.sendMessage(plain("Odebrane: ").formatted(Formatting.GRAY)
                            .append(plain(taken + "e")
                                    .formatted(Formatting.GREEN, Formatting.BOLD)), false);
                    sound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2F);
                } else {
                    deny();
                }
                paint();
                return;
            }
            default -> {
            }
        }
        if (view == View.SLIP) {
            if (index == STAKE_SLOT) {
                stakeChoice = TrapMath.cycle(stakeChoice, TrapMath.BOOK_STAKES.length, button == 1);
                click(1.4F);
                paint();
                return;
            }
            if (index == PLACE_SLOT) {
                lodge();
                return;
            }
        }
        if (view == View.STUDIO && (index == PREV_SLOT || index == NEXT_SLOT)) {
            page += index == NEXT_SLOT ? 1 : -1;
            click(1.0F);
            paint();
            return;
        }

        int[] action = actions.get(index);
        if (action == null) {
            return;
        }
        switch (action[0]) {
            case ACT_LEAGUE -> {
                league = action[1];
                view = View.BOARD;
                page = 0;
                click(1.2F);
            }
            case ACT_STUDIO -> {
                fixture = action[1];
                view = View.STUDIO;
                page = 0;
                click(1.2F);
            }
            case ACT_PICK -> pick(action[1], action[2], action[3]);
            case ACT_DROP -> {
                if (action[1] < slip.size()) {
                    slip.remove(action[1]);
                    click(0.8F);
                }
            }
            default -> {
            }
        }
        paint();
    }

    /**
     * Put one selection on the coupon.
     *
     * Two rules, both of which are what a real shop would say: nothing goes on
     * twice, and two selections from the same fixture cannot be combined --
     * backing the winner and the draw on one coupon is a bet that cannot land,
     * and taking money for it would be taking money for nothing.
     */
    private void pick(int fixtureId, int market, int selection) {
        TrapSports.Fixture match = TrapSports.fixture(fixtureId);
        if (match == null || match.settled()) {
            deny();
            punter.sendMessage(plain("To spotkanie już wystartowało.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (alreadyOn(fixtureId, market, selection)) {
            slip.removeIf(leg -> leg.fixture == fixtureId && leg.market == market
                    && leg.selection == selection);
            click(0.8F);
            return;
        }
        for (TrapSports.Leg leg : slip) {
            if (leg.fixture == fixtureId) {
                deny();
                punter.sendMessage(plain("Na jednym kuponie tylko jeden typ z tego spotkania.")
                        .formatted(Formatting.GRAY), false);
                return;
            }
        }
        if (slip.size() >= TrapMath.BOOK_MAX_LEGS) {
            deny();
            punter.sendMessage(plain("Kupon mieści najwyżej "
                    + TrapMath.BOOK_MAX_LEGS + " pozycje.").formatted(Formatting.GRAY), false);
            return;
        }
        slip.add(TrapSports.leg(match, market, selection));
        sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.6F);
    }

    private boolean alreadyOn(int fixtureId, int market, int selection) {
        for (TrapSports.Leg leg : slip) {
            if (leg.fixture == fixtureId && leg.market == market
                    && leg.selection == selection) {
                return true;
            }
        }
        return false;
    }

    private int[] prices() {
        int[] out = new int[slip.size()];
        for (int i = 0; i < slip.size(); i++) {
            out[i] = slip.get(i).odds;
        }
        return out;
    }

    private void lodge() {
        int stake = TrapMath.BOOK_STAKES[stakeChoice];
        String refused = TrapSports.place(punter, slip, stake);
        if (refused != null) {
            deny();
            punter.sendMessage(plain(refused).formatted(Formatting.GRAY), false);
            paint();
            return;
        }
        int odds = TrapMath.slipOdds(prices());
        punter.sendMessage(plain("Kupon przyjęty.  ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(plain(stake + "e po " + TrapMath.odds(odds) + "  ->  "
                        + TrapMath.slipReturn(stake, odds) + "e")
                        .formatted(Formatting.GREEN)), false);
        sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.2F);
        slip.clear();
        view = View.MINE;
        paint();
    }

    // --- trimmings ---------------------------------------------------------------

    private ItemStack button(Item icon, String name, List<Text> lore) {
        ItemStack tag = new ItemStack(icon);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(name)
                .formatted(Formatting.WHITE, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private void sound(net.minecraft.sound.SoundEvent event, float pitch) {
        punter.getWorld().playSound(null, punter.getBlockPos(), event,
                SoundCategory.PLAYERS, 0.6F, pitch);
    }

    // SoundEvents mixes raw SoundEvent and RegistryEntry with no pattern --
    // BLOCK_NOTE_BLOCK_BELL is a Reference, ENTITY_EXPERIENCE_ORB_PICKUP is
    // raw. Both overloads exist so callers don't have to javap each one.
    private void sound(net.minecraft.registry.entry.RegistryEntry<net.minecraft.sound.SoundEvent> event,
                       float pitch) {
        sound(event.value(), pitch);
    }

    private void click(float pitch) {
        punter.getWorld().playSound(null, punter.getBlockPos(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.4F, pitch);
    }

    private void deny() {
        punter.getWorld().playSound(null, punter.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.6F, 0.6F);
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == punter;
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
