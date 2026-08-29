package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The bookmaker.
 *
 * <h2>What it is</h2>
 *
 * Eight competitions across five sports, running on their own clock whether
 * anybody is watching or not, priced by a book that is deliberately not as
 * well informed as the person standing in front of the television.
 *
 * <h2>Why it can be beaten</h2>
 *
 * Every price on the board comes from two numbers and only two: the
 * competitor's REPUTATION -- a real-world rating, so Real Madryt is short and
 * Widzew is long and anybody who follows football already knows which -- and
 * whether they are at home. See {@link TrapMath#pricedRating}.
 *
 * The RESULT is decided by rather more than that: recent form, who is missing,
 * how long since they last played, whether today's going suits the way they
 * run, and what this exact pair has done to each other before. See
 * {@link TrapMath#trueRating}.
 *
 * That gap is the entire game, and it is not a trick: every one of those
 * factors is printed on the television, in words, for both sides, before the
 * off. What is never printed is a probability, a rating or a tip. The screen
 * tells you the going is soft and that this one likes it soft; the price does
 * not know either. Reading two panels and remembering which team is which
 * turns a seven percent losing proposition into a winning one, and doing
 * neither loses at exactly the rate the margin says it should.
 *
 * <h2>Where the money goes</h2>
 *
 * Through {@link TrapHouse} with a null house, which is the unowned-machine
 * path: stakes leave the world through {@link TrapMarket#take}, returns enter
 * it through {@link TrapMarket#pay}, the city takes its gaming duty on the
 * handle and the ledger records both ends. So a big Saturday really is
 * inflationary and the tax office really does see it.
 *
 * ponytail: no player-owned bookmaker. The stake/payout calls already take a
 * house, so wiring a shop to a casino card is a two-line change the day
 * somebody wants to run one -- what it would also need is a settlement path
 * that works while the owner is offline, which the pending purse below
 * deliberately does not model.
 */
public final class TrapSports {

    // --- the fixture list -------------------------------------------------------

    /**
     * How long a fixture stands before it is run.
     *
     * Short enough that somebody who sits down for an hour sees a dozen
     * results, long enough that a coupon is a decision rather than a reflex.
     * Randomised per fixture so the board never settles all at once and turns
     * into a wall of chat.
     */
    private static final int MIN_TICKS = 20 * 60 * 5;
    private static final int MAX_TICKS = 20 * 60 * 16;

    /**
     * How many run fixtures the results page remembers.
     *
     * A floor, not a ceiling: anything an open coupon still points at is kept
     * past it, because the coupon names its own legs off the fixture and a leg
     * with nothing behind it draws as a blank row.
     */
    private static final int ARCHIVE = 24;

    /** Slips one player may have running. A coupon each, not a portfolio. */
    public static final int MAX_SLIPS = 8;

    // --- competitors ------------------------------------------------------------

    /**
     * One club, player, driver or horse.
     *
     * `reputation` is the real world's opinion, fixed. Everything under it is
     * this world's: the form string really is what happened here, the head to
     * head really is what these two did to each other, and nothing is flavour
     * text pretending to be data.
     */
    public static final class Runner {
        public final String name;
        public final int reputation;
        public final int style;
        public final String note;
        /** Most recent first. 'W' won, 'R' drew or placed, 'P' beaten. */
        public String form = "";
        /** Key people missing today, 0..3. Re-rolled when drawn into a fixture. */
        public int absences;
        /** Rounds off since the last outing, 0..4. */
        public int rest = 2;
        public int played;
        public int won;

        Runner(String name, int reputation, int style, String note) {
            this.name = name;
            this.reputation = reputation;
            this.style = style;
            this.note = note;
        }
    }

    /**
     * One competition.
     *
     * The unit fixtures are drawn inside, which is the reason it exists: a
     * board that can pair Real Madryt with Motor Lublin is a board with one
     * bettable price on it. See {@link TrapMath#BOOK_SCALE}.
     */
    public static final class League {
        public final String name;
        public final String sport;
        /** What varies from fixture to fixture: going, surface, weather, tempo. */
        public final String[] conditions;
        /** Where it is played, per condition. Null where the venue says nothing. */
        public final String[][] venues;
        public final String[] styles;
        /** [condition][style] -> rating points. The readable edge, in a table. */
        public final int[][] suits;
        public final int fieldSize;
        public final boolean draws;
        public final boolean home;
        /** Places paid by the second market, or 0 where there isn't one. */
        public final int places;
        public final int slots;
        public final Runner[] runners;

        League(String name, String sport, String[] conditions, String[][] venues,
               String[] styles, int[][] suits, int fieldSize, boolean draws,
               boolean home, int places, int slots, Runner[] runners) {
            this.name = name;
            this.sport = sport;
            this.conditions = conditions;
            this.venues = venues;
            this.styles = styles;
            this.suits = suits;
            this.fieldSize = fieldSize;
            this.draws = draws;
            this.home = home;
            this.places = places;
            this.slots = slots;
            this.runners = runners;
        }

        /** What today's conditions are worth to this runner, in rating points. */
        public int suits(int condition, Runner runner) {
            return suits[condition][runner.style];
        }
    }

    private static Runner r(String name, int reputation, int style, String note) {
        return new Runner(name, reputation, style, note);
    }

    // Football: the weather is the condition, and it is the one thing a
    // technical side genuinely hates. Nothing here is subtle -- it is meant to
    // be readable by somebody who has watched one wet away game.
    private static final String[] FOOTBALL_CONDITIONS = {"sucho", "deszcz", "wiatr", "mróz"};
    private static final String[] FOOTBALL_STYLES = {"technicy", "siłowa gra", "zrównoważeni"};
    private static final int[][] FOOTBALL_SUITS = {
            {3, 0, 1},
            {-3, 3, 0},
            {-3, 2, 0},
            {-2, 3, 0},
    };

    private static final League CHAMPIONS = new League(
            "Liga Mistrzów", "Piłka nożna", FOOTBALL_CONDITIONS, null,
            FOOTBALL_STYLES, FOOTBALL_SUITS, 2, true, true, 0, 3, new Runner[]{
            r("Paris Saint-Germain", 91, 0, "Puchar Europy 2025, wygrany bez ani jednej gwiazdy w ataku."),
            r("Real Madryt", 90, 0, "Piętnaście Pucharów Europy. Rekordu nikt nie dogoni."),
            r("FC Barcelona", 89, 0, "La Masia wciąż wystawia połowę pierwszego składu."),
            r("Liverpool", 88, 1, "Mistrz Anglii 2025. Gegenpressing i najgłośniejsza trybuna wyspy."),
            r("Bayern Monachium", 88, 1, "Jedenaście tytułów Bundesligi z rzędu do 2023 roku."),
            r("Manchester City", 88, 0, "Cztery mistrzostwa Anglii z rzędu, 2021-2024."),
            r("Inter Mediolan", 85, 2, "Finał Ligi Mistrzów 2025. Przegrany 0:5."),
            r("Arsenal", 85, 0, "Niepokonani przez cały sezon 2003/04."),
            r("SSC Napoli", 82, 0, "Scudetto 2023 i 2025. Wcześniej czekali od czasów Maradony."),
            r("Atlético Madryt", 82, 1, "Simeone traktuje obronę jak sposób na życie."),
            r("Chelsea", 81, 2, "Dwa Puchary Europy, oba wygrane jako outsider."),
            r("Bayer Leverkusen", 79, 2, "Sezon 2023/24 przeszli bez porażki w lidze."),
            r("Borussia Dortmund", 78, 1, "Żółta Ściana: dwadzieścia pięć tysięcy ludzi na jednej trybunie."),
            r("Juventus", 78, 2, "Dziewięć scudetti z rzędu, 2012-2020."),
            r("AC Milan", 76, 2, "Siedem Pucharów Europy. Drugi wynik w historii."),
            r("Tottenham", 74, 0, "Liga Europy 2025: pierwsze trofeum od siedemnastu lat."),
    });

    private static final League EKSTRAKLASA = new League(
            "Ekstraklasa", "Piłka nożna", FOOTBALL_CONDITIONS, null,
            FOOTBALL_STYLES, FOOTBALL_SUITS, 2, true, true, 0, 3, new Runner[]{
            r("Lech Poznań", 64, 2, "Mistrz Polski 2025. Ósmy tytuł w historii klubu."),
            r("Raków Częstochowa", 62, 1, "Z czwartej ligi do mistrzostwa w dziesięć lat."),
            r("Jagiellonia Białystok", 62, 0, "Mistrz 2024 i ćwierćfinał Ligi Konferencji."),
            r("Legia Warszawa", 61, 2, "Piętnaście mistrzostw. Najwięcej w kraju."),
            r("Pogoń Szczecin", 58, 0, "Dwa finały pucharu w trzy lata i dwie porażki."),
            r("Górnik Zabrze", 57, 1, "Czternaście mistrzostw, wszystkie przed 1989 rokiem."),
            r("Cracovia", 56, 0, "Najstarszy klub w Polsce, założony w 1906."),
            r("Wisła Kraków", 55, 0, "Puchar Polski 2024 zdobyty z pierwszej ligi."),
            r("Widzew Łódź", 55, 1, "Półfinał Pucharu Europy 1983. Wtedy to była potęga."),
            r("Śląsk Wrocław", 54, 2, "Wicemistrz 2024, potem pół ligi w dół."),
            r("Zagłębie Lubin", 53, 2, "Mistrz 2007. Od tamtej pory środek tabeli."),
            r("Motor Lublin", 52, 1, "Wrócił do ekstraklasy po trzydziestu latach."),
    });

    // Tennis: the surface is the oldest readable edge in sport. A clay
    // specialist on grass is a different player and everybody who watches
    // knows it -- which is exactly the sort of thing a reputation cannot say.
    private static final String[] TENNIS_CONDITIONS = {"mączka", "trawa", "twarda"};
    private static final String[][] TENNIS_VENUES = {
            {"Roland Garros", "Monte Carlo", "Madryt", "Rzym"},
            {"Wimbledon", "Queen's Club", "Halle"},
            {"Australian Open", "US Open", "Indian Wells", "Miami", "Szanghaj"},
    };
    private static final String[] TENNIS_STYLES = {"mączkarz", "trawiarz", "twarda nawierzchnia",
            "uniwersalny"};
    private static final int[][] TENNIS_SUITS = {
            {4, -4, -1, 1},
            {-4, 4, -1, 1},
            {-2, -1, 4, 2},
    };

    private static final League ATP = new League(
            "ATP", "Tenis", TENNIS_CONDITIONS, TENNIS_VENUES,
            TENNIS_STYLES, TENNIS_SUITS, 2, false, false, 0, 3, new Runner[]{
            r("Jannik Sinner", 95, 2, "Numer jeden i komplet tytułów wielkoszlemowych z twardej."),
            r("Carlos Alcaraz", 94, 3, "Wygrał szlema na każdej nawierzchni przed dwudziestką."),
            r("Novak Djoković", 88, 3, "Dwadzieścia cztery wielkie szlemy. Rekord otwarty."),
            r("Alexander Zverev", 84, 2, "Złoto olimpijskie 2021 i wciąż ani jednego szlema."),
            r("Daniił Miedwiediew", 81, 2, "US Open 2021. Na mączce gubi się jak turysta."),
            r("Taylor Fritz", 80, 2, "Finał US Open 2024, pierwszy Amerykanin od 2006."),
            r("Casper Ruud", 79, 0, "Trzy finały szlema, dwa na mączce Rolanda Garrosa."),
            r("Jack Draper", 78, 2, "Z trzeciej setki do pierwszej dziesiątki w rok."),
            r("Alex de Minaur", 77, 3, "Najszybsze nogi w tourze i żadnej słabej nawierzchni."),
            r("Lorenzo Musetti", 77, 0, "Jednoręczny bekhend i mączka jak z podręcznika."),
            r("Stefanos Tsitsipas", 76, 0, "Monte Carlo wygrywał tak często, jakby tam mieszkał."),
            r("Andriej Rublow", 75, 2, "Dziesięć ćwierćfinałów szlema i ani jednego półfinału."),
            r("Holger Rune", 75, 3, "Wygrał turniej w Paryżu jako dziewiętnastolatek."),
            r("Ben Shelton", 74, 2, "Serwis dwieście czterdzieści na godzinę i lewa ręka."),
            r("Hubert Hurkacz", 74, 1, "Półfinał Wimbledonu 2021. Serwis jak z armaty."),
            r("Grigor Dimitrow", 72, 3, "Nazywany drugim Federerem od dekady."),
    });

    private static final League WTA = new League(
            "WTA", "Tenis", TENNIS_CONDITIONS, TENNIS_VENUES,
            TENNIS_STYLES, TENNIS_SUITS, 2, false, false, 0, 3, new Runner[]{
            r("Aryna Sabalenka", 94, 2, "Numer jeden i trzy szlemy, wszystkie z twardej."),
            r("Iga Świątek", 93, 0, "Cztery razy Roland Garros. Królowa mączki."),
            r("Coco Gauff", 88, 3, "Roland Garros 2025 i US Open 2023."),
            r("Jelena Rybakina", 84, 1, "Wimbledon 2022. Na trawie nie do zatrzymania."),
            r("Jessica Pegula", 82, 2, "Finał US Open 2024 po sześciu przegranych ćwierćfinałach."),
            r("Qinwen Zheng", 81, 2, "Złoto olimpijskie w Paryżu 2024."),
            r("Mirra Andriejewa", 80, 3, "Dwa turnieje rangi tysiąc przed osiemnastką."),
            r("Madison Keys", 79, 2, "Australian Open 2025, dziesięć lat po pierwszym finale."),
            r("Barbora Krejčíková", 76, 3, "Wimbledon 2024 i Roland Garros 2021."),
            r("Paula Badosa", 76, 0, "Wróciła po kontuzji pleców, która miała ją zatrzymać."),
            r("Karolina Muchová", 75, 3, "Najładniejszy tenis w tourze i najczęstsze kontuzje."),
            r("Emma Navarro", 75, 2, "Z uniwersyteckiego tenisa prosto do dziesiątki."),
            r("Daria Kasatkina", 74, 3, "Slajs, lob i cierpliwość."),
            r("Magdalena Fręch", 68, 0, "Pierwsza trzydziestka po latach w drugim szeregu."),
    });

    // Formula 1: the track is the condition and the track has a name everybody
    // knows. Monza rewards a car with an engine, Monako rewards somebody who
    // does not hit walls, and those are different drivers.
    private static final String[] MOTOR_CONDITIONS = {"tor szybki", "tor kręty", "tor uliczny"};
    private static final String[][] MOTOR_VENUES = {
            {"Monza", "Spa-Francorchamps", "Silverstone", "Red Bull Ring"},
            {"Hungaroring", "Zandvoort", "Suzuka", "Barcelona"},
            {"Monako", "Singapur", "Baku", "Las Vegas"},
    };
    private static final String[] MOTOR_STYLES = {"moc silnika", "docisk", "ulicznik", "uniwersalny"};
    private static final int[][] MOTOR_SUITS = {
            {4, -3, -1, 1},
            {-3, 4, 0, 1},
            {-1, 1, 4, 1},
    };

    private static final League FORMULA = new League(
            "Formuła 1", "Wyścigi", MOTOR_CONDITIONS, MOTOR_VENUES,
            MOTOR_STYLES, MOTOR_SUITS, 10, false, false, 3, 1, new Runner[]{
            r("Max Verstappen", 95, 3, "Cztery tytuły z rzędu, 2021-2024."),
            r("Lando Norris", 90, 1, "McLaren wrócił na szczyt, a on razem z nim."),
            r("Oscar Piastri", 89, 1, "Drugi pełny sezon i już wygrywa wyścigi."),
            r("Charles Leclerc", 87, 2, "Poeta jednego okrążenia. Monako to jego dom."),
            r("Lewis Hamilton", 86, 3, "Siedem tytułów. Rekord dzielony ze Schumacherem."),
            r("George Russell", 84, 3, "Wygrał, zanim Mercedes był na to gotowy."),
            r("Carlos Sainz", 82, 0, "Wygrywał dla Ferrari wtedy, gdy Ferrari nie potrafiło."),
            r("Fernando Alonso", 80, 2, "Dwa tytuły i dwadzieścia lat w stawce."),
            r("Alexander Albon", 76, 3, "Wyciąga z Williamsa punkty, których tam nie ma."),
            r("Pierre Gasly", 74, 1, "Monza 2020. Największy outsider dekady."),
            r("Nico Hülkenberg", 74, 0, "Najwięcej startów bez podium w historii serii."),
            r("Esteban Ocon", 72, 1, "Węgry 2021. Jedno zwycięstwo, za to prawdziwe."),
            r("Yuki Tsunoda", 72, 2, "Szybki na torze i jeszcze szybszy na radiu."),
            r("Liam Lawson", 68, 3, "Wskoczył do Red Bulla i wypadł po dwóch wyścigach."),
            r("Oliver Bearman", 67, 3, "Debiut dla Ferrari w Dżuddzie, z marszu siódme miejsce."),
            r("Lance Stroll", 66, 3, "Trzy podia i ojciec, który kupił cały zespół."),
            r("Isack Hadjar", 66, 1, "Rookie prosto z formuły 2, uczy się na oczach wszystkich."),
            r("Franco Colapinto", 65, 3, "Punkty w drugim wyścigu w karierze."),
            r("Gabriel Bortoleto", 64, 3, "Mistrz F2 i F3 z rzędu. Pierwszy Brazylijczyk od lat."),
            r("Jack Doohan", 63, 3, "Syn mistrza motocyklowego, sam dopiero zaczyna."),
    });

    // Horses: the going. Every stable in the world checks it before declaring
    // and half the results in racing are decided by it.
    // One word each. Prefixed with "podłoże" they read fine on their own and
    // like a stutter in a list -- and they appear in a list on the wiki, in
    // the channel card and next to three other competitions' conditions.
    private static final String[] HORSE_CONDITIONS = {"twarde", "dobre",
            "miękkie", "ciężkie"};
    private static final String[] COURSES = {"Służewiec", "Ascot", "Epsom", "Longchamp",
            "Newmarket", "Chantilly", "Churchill Downs"};
    private static final String[][] HORSE_VENUES = {COURSES, COURSES, COURSES, COURSES};
    private static final String[] HORSE_STYLES = {"lubi twarde", "obojętny", "lubi miękkie"};
    private static final int[][] HORSE_SUITS = {
            {4, 1, -3},
            {1, 2, 1},
            {-2, 1, 3},
            {-4, 1, 4},
    };

    private static final League RACING = new League(
            "Gonitwa", "Konie", HORSE_CONDITIONS, HORSE_VENUES,
            HORSE_STYLES, HORSE_SUITS, 8, false, false, 3, 2, new Runner[]{
            r("Frankel", 95, 0, "Czternaście startów, czternaście zwycięstw. Timeform 147."),
            r("Sea Bird", 92, 1, "Wygrał Łuk Triumfu 1965 o sześć długości."),
            r("Secretariat", 91, 0, "Belmont 1973 wygrany o trzydzieści jeden długości."),
            r("Brigadier Gerard", 90, 0, "Osiemnaście startów, siedemnaście zwycięstw."),
            r("Sea The Stars", 89, 1, "Sześć wyścigów grupy pierwszej w jednym sezonie."),
            r("Ribot", 89, 2, "Szesnaście startów, szesnaście zwycięstw."),
            r("Nijinsky", 88, 1, "Ostatni potrójnie koronowany w Anglii, 1970."),
            r("Enable", 87, 2, "Dwa Łuki Triumfu z rzędu."),
            r("Winx", 87, 1, "Trzydzieści trzy zwycięstwa z rzędu."),
            r("Arkle", 86, 2, "Najlepszy koń przeszkodowy, jakiego widziano."),
            r("Black Caviar", 85, 0, "Dwadzieścia pięć startów, dwadzieścia pięć zwycięstw."),
            r("Kincsem", 84, 1, "Pięćdziesiąt cztery starty i ani jednej porażki."),
            r("Red Rum", 84, 2, "Trzy Grand National. Nikt inny tego nie zrobił."),
            r("Zenyatta", 83, 1, "Dziewiętnaście z dwudziestu. Przegrała ostatni bieg życia."),
            r("Eclipse", 90, 1, "Osiemnaście startów w XVIII wieku i ani jednej porażki."),
            r("Man o' War", 88, 0, "Dwadzieścia jeden startów, dwadzieścia zwycięstw."),
            r("Dancing Brave", 88, 0, "Łuk Triumfu 1986 wygrany z ostatniego miejsca."),
            r("Mill Reef", 87, 1, "Derby, Łuk i King George w jednym roku."),
            r("Phar Lap", 85, 2, "Trzydzieści siedem zwycięstw i pomnik w Melbourne."),
            r("Seabiscuit", 82, 0, "Pobił Triple Crown w pojedynku, w 1938."),
    });

    // Basketball: tempo. A team built to run and a team built to grind are
    // different teams depending on which game breaks out, and the schedule
    // says which one it is before tip-off.
    private static final String[] BASKET_CONDITIONS = {"szybkie tempo", "wolne tempo"};
    private static final String[] BASKET_STYLES = {"biegający", "pozycyjni", "zrównoważeni"};
    private static final int[][] BASKET_SUITS = {
            {3, -3, 1},
            {-3, 3, 1},
    };

    private static final League NBA = new League(
            "NBA", "Koszykówka", BASKET_CONDITIONS, null,
            BASKET_STYLES, BASKET_SUITS, 2, false, true, 0, 3, new Runner[]{
            r("Oklahoma City Thunder", 90, 0, "Mistrz 2025 i najmłodszy skład w lidze."),
            r("Boston Celtics", 88, 2, "Osiemnaście tytułów. Rekord ligi."),
            r("Denver Nuggets", 85, 1, "Jokić rozgrywa akcje z pozycji środkowego."),
            r("Cleveland Cavaliers", 84, 0, "Najlepszy bilans Wschodu w sezonie 2024/25."),
            r("New York Knicks", 83, 2, "Pierwszy finał konferencji od 2000 roku."),
            r("Indiana Pacers", 82, 0, "Najszybsze tempo w lidze i finał 2025."),
            r("Minnesota Timberwolves", 82, 2, "Obrona, która dusi każdy atak pozycyjny."),
            r("Los Angeles Lakers", 81, 2, "Siedemnaście tytułów, a w składzie Dončić."),
            r("Golden State Warriors", 80, 0, "Cztery tytuły w osiem lat, wszystkie zza łuku."),
            r("Houston Rockets", 79, 1, "Najmłodsza obrona w czołówce Zachodu."),
            r("Milwaukee Bucks", 79, 1, "Antetokounmpo i coraz mniej wokół niego."),
            r("Dallas Mavericks", 77, 2, "Finał 2024, a potem transfer, którego nikt nie zrozumiał."),
            r("Memphis Grizzlies", 76, 0, "Tempo i łokcie."),
            r("Los Angeles Clippers", 76, 1, "Ani jednego finału w historii klubu."),
            r("Philadelphia 76ers", 76, 1, "Proces trwa od 2013 roku."),
            r("Miami Heat", 75, 2, "Dwa razy weszli do finału prosto z play-in."),
    });

    private static final League EUROLEAGUE = new League(
            "Euroliga", "Koszykówka", BASKET_CONDITIONS, null,
            BASKET_STYLES, BASKET_SUITS, 2, false, true, 0, 2, new Runner[]{
            r("Real Madryt", 84, 2, "Jedenaście Eurolig. Rekord rozgrywek."),
            r("Panathinaikos Ateny", 84, 2, "Mistrz 2024. Siódmy tytuł."),
            r("Fenerbahce Stambuł", 83, 0, "Mistrz 2025."),
            r("Olympiakos Pireus", 82, 1, "Trzy tytuły i najgłośniejsza hala w Europie."),
            r("FC Barcelona", 80, 1, "Dwa tytuły, dziesięć finałów."),
            r("AS Monako", 79, 0, "Z drugiej ligi francuskiej do finału Euroligi w dekadę."),
            r("Maccabi Tel Awiw", 77, 0, "Sześć tytułów, wszystkie sprzed 2014."),
            r("Anadolu Efes", 76, 2, "Dwa tytuły z rzędu, 2021 i 2022."),
            r("Żalgiris Kowno", 76, 2, "Mistrz 1999 i miasto, które żyje koszykówką."),
            r("Crvena Zvezda Belgrad", 75, 1, "Hala, w której sędziowie nie słyszą własnych gwizdków."),
            r("Virtus Bolonia", 74, 1, "Dwa tytuły w latach dziewięćdziesiątych."),
            r("Baskonia Vitoria", 73, 0, "Mistrz 2005 i fabryka talentów."),
    });

    /** Every competition, in the order the channel list draws them. */
    public static final League[] LEAGUES = {
            CHAMPIONS, EKSTRAKLASA, ATP, WTA, FORMULA, RACING, NBA, EUROLEAGUE,
    };

    // --- the board --------------------------------------------------------------

    /**
     * One fixture, with its prices frozen at the moment it went up.
     *
     * Frozen rather than recomputed on every repaint, and that is not an
     * optimisation: a price that moves between the screen and the click is a
     * bookmaker nobody would bet with twice. The board offers a number and
     * honours it.
     */
    public static final class Fixture {
        public final int id;
        public final int league;
        public final int condition;
        public final String venue;
        public final int[] field;
        /** Per selection: runners in order, then the draw where there is one. */
        public final int[] winOdds;
        /** Per runner, or null where the competition has no place market. */
        public final int[] placeOdds;
        public long kickoff;
        /** Index into `field`, or field.length for a draw. -1 while it is pending. */
        public int winner = -1;
        /** Indices into `field`, longest first is not promised. Empty until run. */
        public int[] podium = new int[0];

        Fixture(int id, int league, int condition, String venue, int[] field,
                int[] winOdds, int[] placeOdds, long kickoff) {
            this.id = id;
            this.league = league;
            this.condition = condition;
            this.venue = venue;
            this.field = field;
            this.winOdds = winOdds;
            this.placeOdds = placeOdds;
            this.kickoff = kickoff;
        }

        public League league() {
            return LEAGUES[league];
        }

        public Runner runner(int index) {
            return league().runners[field[index]];
        }

        public boolean settled() {
            return winner >= 0;
        }

        /** True where this fixture's first runner is the home side. */
        public boolean home(int index) {
            return league().home && index == 0;
        }
    }

    /** One selection on a coupon. */
    public static final class Leg {
        public final int fixture;
        /** 0 = win, 1 = place. */
        public final int market;
        public final int selection;
        public final int odds;
        /** -1 pending, 0 beaten, 1 landed. */
        public int result = -1;

        Leg(int fixture, int market, int selection, int odds) {
            this.fixture = fixture;
            this.market = market;
            this.selection = selection;
            this.odds = odds;
        }
    }

    /** One coupon: a stake, up to four legs, and a price that cannot move. */
    public static final class Slip {
        public final int id;
        public final UUID punter;
        public final int stake;
        public final int odds;
        public final List<Leg> legs;

        Slip(int id, UUID punter, int stake, int odds, List<Leg> legs) {
            this.id = id;
            this.punter = punter;
            this.stake = stake;
            this.odds = odds;
            this.legs = legs;
        }

        public int returns() {
            return TrapMath.slipReturn(stake, odds);
        }
    }

    private static final List<Fixture> BOARD = new ArrayList<>();
    private static final List<Fixture> RESULTS = new ArrayList<>();
    private static final List<Slip> SLIPS = new ArrayList<>();
    /**
     * Head to head, keyed league:lower:higher, counted as {lower, higher}.
     *
     * Sparse on purpose -- only pairs that have actually met are in here, so
     * this is a few dozen entries rather than the eight hundred a full matrix
     * would be, and every number in it happened in this world.
     */
    private static final Map<String, int[]> H2H = new HashMap<>();
    /**
     * Winnings that landed while their owner was somewhere else.
     *
     * Nobody can be handed emeralds while logged out, and a payout that
     * silently does not happen is the one bug an economy cannot survive. So it
     * waits, it is saved, and the television hands it over.
     *
     * {amount owed, most legs on a coupon that landed while away}. The second
     * number is only there so the four-fold advancement survives being won at
     * four in the morning by somebody who had already gone to bed -- granting
     * it only to whoever happens to be logged in makes it an award for being
     * online, which is not what it says on it.
     */
    private static final Map<UUID, int[]> PURSE = new HashMap<>();

    private static final Random RNG = new Random();
    private static int nextFixture = 1;
    private static int nextSlip = 1;
    private static Path saveFile;

    private TrapSports() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapSports::load);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Once a second. Nothing here is frame-accurate and a fixture list
            // walked twenty times a second is twenty times the work for a
            // deadline measured in minutes.
            if (server.getTicks() % 20 != 0) {
                return;
            }
            long now = server.getOverworld().getTime();
            boolean moved = false;
            for (Fixture fixture : List.copyOf(BOARD)) {
                if (now >= fixture.kickoff) {
                    run(server, fixture);
                    moved = true;
                }
            }
            moved |= topUp(now);
            if (moved) {
                save();
            }
            // After the board has moved, not before: a set repainted first
            // would show a price for a fixture that has already been run.
            TelevisionScreenHandler.refreshAll();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            int[] owed = PURSE.get(handler.getPlayer().getUuid());
            int waiting = owed == null ? 0 : owed[0];
            if (waiting > 0) {
                handler.getPlayer().sendMessage(Text.literal("").append(
                                Text.literal("Bukmacher  ").formatted(Formatting.GOLD, Formatting.BOLD))
                        .append(Text.literal("Masz " + waiting + "e do odbioru. "
                                + "Zajrzyj do telewizora.").formatted(Formatting.GRAY)), false);
            }
        });
    }

    // --- putting fixtures up ----------------------------------------------------

    /** Fill every competition back up to its slot count. */
    private static boolean topUp(long now) {
        boolean added = false;
        for (int league = 0; league < LEAGUES.length; league++) {
            int live = 0;
            for (Fixture fixture : BOARD) {
                if (fixture.league == league) {
                    live++;
                }
            }
            while (live < LEAGUES[league].slots) {
                Fixture fixture = draw(league, now);
                if (fixture == null) {
                    break;
                }
                BOARD.add(fixture);
                live++;
                added = true;
            }
        }
        return added;
    }

    /**
     * Draw one fixture: a field, a going, a start time and a board of prices.
     *
     * Duels pull the second runner from those within {@link #NEIGHBOURS} of
     * the first where it can, which is the difference between a competition
     * and a list. Drawn flat, Ekstraklasa serves up Lech against Motor at 1.06
     * about as often as anything worth thinking about, and a board of
     * unbettable prices is a board nobody opens twice.
     */
    private static Fixture draw(int league, long now) {
        League competition = LEAGUES[league];
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < competition.runners.length; i++) {
            if (!busy(league, i)) {
                free.add(i);
            }
        }
        if (free.size() < competition.fieldSize) {
            return null;
        }

        int[] field = new int[competition.fieldSize];
        if (competition.fieldSize == 2) {
            int first = free.remove(RNG.nextInt(free.size()));
            List<Integer> near = new ArrayList<>();
            for (int candidate : free) {
                if (Math.abs(competition.runners[candidate].reputation
                        - competition.runners[first].reputation) <= NEIGHBOURS) {
                    near.add(candidate);
                }
            }
            List<Integer> pool = near.isEmpty() ? free : near;
            field[0] = first;
            field[1] = pool.get(RNG.nextInt(pool.size()));
        } else {
            for (int i = 0; i < field.length; i++) {
                field[i] = free.remove(RNG.nextInt(free.size()));
            }
        }

        int condition = RNG.nextInt(competition.conditions.length);
        String venue = competition.venues == null ? ""
                : competition.venues[condition][RNG.nextInt(competition.venues[condition].length)];

        for (int index : field) {
            // Absences are rolled once, when the runner is declared, and stand
            // until the fixture is run. Rolled per repaint they would flicker;
            // rolled at kickoff they could not be read off the screen, which is
            // the whole point of them.
            competition.runners[index].absences = absences();
        }

        float[] priced = new float[field.length];
        for (int i = 0; i < field.length; i++) {
            priced[i] = TrapMath.pricedRating(competition.runners[field[i]].reputation,
                    competition.home && i == 0);
        }

        int[] winOdds;
        int[] placeOdds = null;
        if (competition.fieldSize == 2 && competition.draws) {
            float[] chances = TrapMath.matchChances(priced[0], priced[1]);
            winOdds = new int[]{TrapMath.price(chances[0]), TrapMath.price(chances[1]),
                    TrapMath.price(chances[2])};
        } else if (competition.fieldSize == 2) {
            float mine = TrapMath.duelChance(priced[0], priced[1]);
            winOdds = new int[]{TrapMath.price(mine), TrapMath.price(1 - mine)};
        } else {
            float[] chances = TrapMath.fieldChances(priced);
            winOdds = new int[field.length];
            for (int i = 0; i < field.length; i++) {
                winOdds[i] = TrapMath.price(chances[i]);
            }
            if (competition.places > 0) {
                float[] placed = TrapMath.placeChances(chances, competition.places);
                placeOdds = new int[field.length];
                for (int i = 0; i < field.length; i++) {
                    placeOdds[i] = TrapMath.price(placed[i]);
                }
            }
        }

        long kickoff = now + MIN_TICKS + RNG.nextInt(MAX_TICKS - MIN_TICKS);
        return new Fixture(nextFixture++, league, condition, venue, field,
                winOdds, placeOdds, kickoff);
    }

    /** How far apart two duellists may be drawn, in reputation points. */
    private static final int NEIGHBOURS = 12;

    private static boolean busy(int league, int runner) {
        for (Fixture fixture : BOARD) {
            if (fixture.league != league) {
                continue;
            }
            for (int index : fixture.field) {
                if (index == runner) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Most sides are at full strength; a few are not. */
    private static int absences() {
        int roll = RNG.nextInt(100);
        return roll < 58 ? 0 : roll < 80 ? 1 : roll < 93 ? 2 : 3;
    }

    // --- running them -----------------------------------------------------------

    /**
     * What the fixture is actually decided on.
     *
     * Everything the board could not see. The gap between this and the price
     * is the edge, and it is readable: {@link TelevisionScreenHandler} prints
     * every term of it in words on the studio page.
     */
    public static float trueRating(Fixture fixture, int index) {
        League competition = fixture.league();
        Runner runner = fixture.runner(index);
        int h2h = 0;
        if (fixture.field.length == 2) {
            int other = 1 - index;
            int[] record = record(fixture.league, fixture.field[index], fixture.field[other]);
            h2h = TrapMath.headToHeadPoints(record[0], record[1]);
        }
        return TrapMath.trueRating(runner.reputation, runner.form, runner.absences,
                runner.rest, competition.suits(fixture.condition, runner), h2h,
                fixture.home(index));
    }

    /** This pair's record, from the caller's point of view: {mine, theirs}. */
    public static int[] record(int league, int mine, int theirs) {
        int low = Math.min(mine, theirs);
        int high = Math.max(mine, theirs);
        int[] kept = H2H.get(league + ":" + low + ":" + high);
        if (kept == null) {
            return new int[]{0, 0};
        }
        return mine == low ? new int[]{kept[0], kept[1]} : new int[]{kept[1], kept[0]};
    }

    private static void noteRecord(int league, int winner, int loser) {
        int low = Math.min(winner, loser);
        int high = Math.max(winner, loser);
        int[] kept = H2H.computeIfAbsent(league + ":" + low + ":" + high, key -> new int[2]);
        kept[winner == low ? 0 : 1]++;
    }

    private static void run(MinecraftServer server, Fixture fixture) {
        League competition = fixture.league();
        float[] ratings = new float[fixture.field.length];
        for (int i = 0; i < ratings.length; i++) {
            ratings[i] = trueRating(fixture, i);
        }

        if (fixture.field.length == 2) {
            float mine = TrapMath.duelChance(ratings[0], ratings[1]);
            float draw = competition.draws ? TrapMath.drawChance(ratings[0], ratings[1]) : 0;
            float roll = RNG.nextFloat();
            if (roll < draw) {
                fixture.winner = 2;
            } else {
                fixture.winner = (roll - draw) / (1 - draw) < mine ? 0 : 1;
            }
        } else {
            // The same draw-without-replacement the place market was priced
            // off, so a horse that was 3.00 to place really does place about a
            // third of the time. Running the field any other way would have
            // the board quoting one race and settling another.
            float[] chances = TrapMath.fieldChances(ratings);
            List<Integer> order = order(chances);
            fixture.winner = order.get(0);
            fixture.podium = new int[Math.min(competition.places, order.size())];
            for (int i = 0; i < fixture.podium.length; i++) {
                fixture.podium[i] = order.get(i);
            }
        }

        writeUp(competition, fixture);
        BOARD.remove(fixture);
        RESULTS.add(0, fixture);
        settle(server, fixture);
        trim();
    }

    /** A finishing order, drawn in proportion to what is left in the field. */
    private static List<Integer> order(float[] chances) {
        List<Integer> left = new ArrayList<>();
        for (int i = 0; i < chances.length; i++) {
            left.add(i);
        }
        List<Integer> out = new ArrayList<>();
        while (!left.isEmpty()) {
            float total = 0;
            for (int index : left) {
                total += chances[index];
            }
            float pick = RNG.nextFloat() * total;
            int chosen = left.get(left.size() - 1);
            for (int index : left) {
                pick -= chances[index];
                if (pick <= 0) {
                    chosen = index;
                    break;
                }
            }
            out.add(chosen);
            left.remove(Integer.valueOf(chosen));
        }
        return out;
    }

    /** Drop the oldest results that nothing is still waiting on. */
    private static void trim() {
        for (int i = RESULTS.size() - 1; i >= 0 && RESULTS.size() > ARCHIVE; i--) {
            if (!wanted(RESULTS.get(i).id)) {
                RESULTS.remove(i);
            }
        }
    }

    private static boolean wanted(int fixture) {
        for (Slip slip : SLIPS) {
            for (Leg leg : slip.legs) {
                if (leg.fixture == fixture) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Write the result into everybody's form, rest and head to head. */
    private static void writeUp(League competition, Fixture fixture) {
        boolean[] ran = new boolean[competition.runners.length];
        for (int i = 0; i < fixture.field.length; i++) {
            Runner runner = fixture.runner(i);
            ran[fixture.field[i]] = true;
            char mark;
            if (fixture.field.length == 2) {
                mark = fixture.winner == 2 ? 'R' : fixture.winner == i ? 'W' : 'P';
            } else if (fixture.winner == i) {
                mark = 'W';
            } else {
                mark = placed(fixture, i) ? 'R' : 'P';
            }
            runner.form = (mark + runner.form);
            if (runner.form.length() > 5) {
                runner.form = runner.form.substring(0, 5);
            }
            runner.played++;
            if (mark == 'W') {
                runner.won++;
            }
            runner.rest = 0;
        }
        for (int i = 0; i < competition.runners.length; i++) {
            if (!ran[i]) {
                competition.runners[i].rest =
                        Math.min(TrapMath.BOOK_REST.length - 1, competition.runners[i].rest + 1);
            }
        }
        if (fixture.field.length == 2 && fixture.winner < 2) {
            noteRecord(fixture.league, fixture.field[fixture.winner],
                    fixture.field[1 - fixture.winner]);
        }
    }

    public static boolean placed(Fixture fixture, int index) {
        for (int spot : fixture.podium) {
            if (spot == index) {
                return true;
            }
        }
        return false;
    }

    /** Did this leg land? */
    public static boolean landed(Fixture fixture, Leg leg) {
        if (leg.market == 1) {
            return placed(fixture, leg.selection);
        }
        return fixture.winner == leg.selection;
    }

    // --- coupons ----------------------------------------------------------------

    private static void settle(MinecraftServer server, Fixture fixture) {
        for (Slip slip : List.copyOf(SLIPS)) {
            boolean touched = false;
            for (Leg leg : slip.legs) {
                if (leg.fixture == fixture.id && leg.result < 0) {
                    leg.result = landed(fixture, leg) ? 1 : 0;
                    touched = true;
                }
            }
            if (!touched) {
                continue;
            }
            boolean lost = false;
            int open = 0;
            for (Leg leg : slip.legs) {
                lost |= leg.result == 0;
                open += leg.result < 0 ? 1 : 0;
            }
            if (!lost && open > 0) {
                tell(server, slip.punter, Text.literal("Kupon: pozycja trafiona. ")
                        .formatted(Formatting.GREEN)
                        .append(Text.literal("Zostało " + open + ".")
                                .formatted(Formatting.GRAY)));
                continue;
            }
            SLIPS.remove(slip);
            if (lost) {
                tell(server, slip.punter, Text.literal("Kupon przepadł. ")
                        .formatted(Formatting.RED, Formatting.BOLD)
                        .append(Text.literal(describe(fixture)).formatted(Formatting.GRAY)));
                continue;
            }
            int paid = slip.returns();
            ServerPlayerEntity punter = server.getPlayerManager().getPlayer(slip.punter);
            if (punter != null) {
                TrapHouse.payout(punter, null, paid);
                punter.sendMessage(Text.literal("Kupon trafiony!  ")
                        .formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(Text.literal("+" + paid + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD))
                        .append(Text.literal("   " + describe(fixture))
                                .formatted(Formatting.GRAY)), false);
                TrapAwards.grant(punter, "punter");
                if (slip.legs.size() >= TrapMath.BOOK_MAX_LEGS) {
                    TrapAwards.grant(punter, "coupon");
                }
            } else {
                int[] owed = PURSE.computeIfAbsent(slip.punter, key -> new int[2]);
                owed[0] += paid;
                owed[1] = Math.max(owed[1], slip.legs.size());
            }
        }
    }

    private static void tell(MinecraftServer server, UUID who, MutableText line) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(who);
        if (player != null) {
            player.sendMessage(line, false);
        }
    }

    /** "Real Madryt 1:0 Arsenal" or "Gonitwa: wygrywa Frankel". */
    public static String describe(Fixture fixture) {
        if (fixture.field.length == 2) {
            if (fixture.winner == 2) {
                return fixture.runner(0).name + " remis " + fixture.runner(1).name;
            }
            return fixture.runner(fixture.winner).name + " pokonał "
                    + fixture.runner(1 - fixture.winner).name;
        }
        return fixture.league().name + ": wygrywa " + fixture.runner(fixture.winner).name;
    }

    /**
     * Take a coupon.
     *
     * Everything is checked here and nothing is trusted from the screen: a
     * fixture can be run between painting the slip and pressing the button,
     * and a bet accepted on a race that has already been won is a bug that
     * pays out.
     */
    public static String place(ServerPlayerEntity punter, List<Leg> legs, int stake) {
        if (legs.isEmpty()) {
            return "Kupon jest pusty.";
        }
        if (legs.size() > TrapMath.BOOK_MAX_LEGS) {
            return "Najwyżej " + TrapMath.BOOK_MAX_LEGS + " pozycje na kuponie.";
        }
        if (slipsOf(punter).size() >= MAX_SLIPS) {
            return "Masz już " + MAX_SLIPS + " kupony w grze.";
        }
        int[] prices = new int[legs.size()];
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            Fixture fixture = fixture(leg.fixture);
            if (fixture == null || fixture.settled()) {
                return "Jedno ze spotkań już się rozpoczęło. Kupon odrzucony.";
            }
            // Off the fixture, not off the leg. The leg was built when the
            // screen was painted and the screen can sit open for a long time;
            // the board is what the bet is actually struck at.
            prices[i] = priceOf(fixture, leg.market, leg.selection);
            if (prices[i] <= 0) {
                return "Tego kursu nie ma na tablicy.";
            }
        }
        // Stake AND duty, not just the stake. TrapHouse.stake charges the
        // city's gaming duty before it takes the wager, so a punter holding
        // exactly the stake has the duty taken first and then TrapMarket.take
        // quietly collects less than it told the money supply it removed. The
        // seven casino machines all check the stake alone and inherit that;
        // this one does not.
        int duty = TrapCity.dutyOn(stake, TrapCity.Duty.GAMING);
        if (TrapMarket.wealthOf(punter) < stake + duty) {
            return duty > 0
                    ? "Nie stać cię na " + stake + "e plus " + duty + "e daniny."
                    : "Nie stać cię na stawkę " + stake + "e.";
        }
        int odds = TrapMath.slipOdds(prices);
        TrapHouse.stake(punter, null, stake);
        SLIPS.add(new Slip(nextSlip++, punter.getUuid(), stake, odds, new ArrayList<>(legs)));
        save();
        return null;
    }

    /** One selection, at the price the board is showing for it. */
    public static Leg leg(Fixture fixture, int market, int selection) {
        return new Leg(fixture.id, market, selection, priceOf(fixture, market, selection));
    }

    /** What the board is offering on one selection right now. */
    public static int priceOf(Fixture fixture, int market, int selection) {
        int[] prices = market == 1 ? fixture.placeOdds : fixture.winOdds;
        if (prices == null || selection < 0 || selection >= prices.length) {
            return 0;
        }
        return prices[selection];
    }

    public static List<Slip> slipsOf(ServerPlayerEntity punter) {
        List<Slip> mine = new ArrayList<>();
        for (Slip slip : SLIPS) {
            if (slip.punter.equals(punter.getUuid())) {
                mine.add(slip);
            }
        }
        return mine;
    }

    public static Fixture fixture(int id) {
        for (Fixture fixture : BOARD) {
            if (fixture.id == id) {
                return fixture;
            }
        }
        for (Fixture fixture : RESULTS) {
            if (fixture.id == id) {
                return fixture;
            }
        }
        return null;
    }

    public static List<Fixture> board(int league) {
        List<Fixture> out = new ArrayList<>();
        for (Fixture fixture : BOARD) {
            if (fixture.league == league) {
                out.add(fixture);
            }
        }
        out.sort((a, b) -> Long.compare(a.kickoff, b.kickoff));
        return out;
    }

    public static List<Fixture> results() {
        return List.copyOf(RESULTS);
    }

    public static int purse(ServerPlayerEntity punter) {
        int[] owed = PURSE.get(punter.getUuid());
        return owed == null ? 0 : owed[0];
    }

    /** Hand over everything that landed while they were away. */
    public static int collect(ServerPlayerEntity punter) {
        int[] owed = PURSE.remove(punter.getUuid());
        if (owed == null || owed[0] <= 0) {
            return 0;
        }
        TrapHouse.payout(punter, null, owed[0]);
        TrapAwards.grant(punter, "punter");
        if (owed[1] >= TrapMath.BOOK_MAX_LEGS) {
            TrapAwards.grant(punter, "coupon");
        }
        save();
        return owed[0];
    }

    /** Minutes and seconds until the off, for the screen. */
    public static String untilOff(MinecraftServer server, Fixture fixture) {
        long ticks = fixture.kickoff - server.getOverworld().getTime();
        if (ticks <= 0) {
            return "start";
        }
        long seconds = ticks / 20;
        return seconds >= 60 ? (seconds / 60) + " min" : seconds + " s";
    }

    // --- keeping it ------------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-bookmaker.txt");
        BOARD.clear();
        RESULTS.clear();
        SLIPS.clear();
        H2H.clear();
        PURSE.clear();
        try {
            if (Files.exists(saveFile)) {
                for (String line : Files.readAllLines(saveFile)) {
                    read(line.trim());
                }
            }
        } catch (Exception failure) {
            // Loud: there are open bets in this file and somebody paid for them.
            TrapCraft.LOGGER.error("couldn't read the bookmaker -- open bets may be lost: {}",
                    failure.toString());
        }
        // Everything that should have been run while the server was down is run
        // now, in the order it was due, before anybody can look at the board.
        long now = server.getOverworld().getTime();
        for (Fixture fixture : List.copyOf(BOARD)) {
            if (now >= fixture.kickoff) {
                run(server, fixture);
            }
        }
        topUp(now);
        save();
    }

    private static void read(String line) {
        if (line.isEmpty()) {
            return;
        }
        String[] parts = line.split(" ");
        switch (parts[0]) {
            case "R" -> {
                Runner runner = LEAGUES[Integer.parseInt(parts[1])]
                        .runners[Integer.parseInt(parts[2])];
                runner.form = parts[3].equals("-") ? "" : parts[3];
                runner.absences = Integer.parseInt(parts[4]);
                runner.rest = Integer.parseInt(parts[5]);
                runner.played = Integer.parseInt(parts[6]);
                runner.won = Integer.parseInt(parts[7]);
            }
            case "H" -> H2H.put(parts[1] + ":" + parts[2] + ":" + parts[3],
                    new int[]{Integer.parseInt(parts[4]), Integer.parseInt(parts[5])});
            case "F", "A" -> {
                Fixture fixture = new Fixture(Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                        parts[4].equals("-") ? "" : parts[4].replace('_', ' '),
                        numbers(parts[5]), numbers(parts[6]),
                        parts[7].equals("-") ? null : numbers(parts[7]),
                        Long.parseLong(parts[8]));
                fixture.winner = Integer.parseInt(parts[9]);
                fixture.podium = parts[10].equals("-") ? new int[0] : numbers(parts[10]);
                (parts[0].equals("F") ? BOARD : RESULTS).add(fixture);
                nextFixture = Math.max(nextFixture, fixture.id + 1);
            }
            case "B" -> {
                List<Leg> legs = new ArrayList<>();
                for (String raw : parts[5].split(",")) {
                    String[] bits = raw.split(":");
                    Leg leg = new Leg(Integer.parseInt(bits[0]), Integer.parseInt(bits[1]),
                            Integer.parseInt(bits[2]), Integer.parseInt(bits[3]));
                    leg.result = Integer.parseInt(bits[4]);
                    legs.add(leg);
                }
                Slip slip = new Slip(Integer.parseInt(parts[1]), UUID.fromString(parts[2]),
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), legs);
                SLIPS.add(slip);
                nextSlip = Math.max(nextSlip, slip.id + 1);
            }
            case "P" -> PURSE.put(UUID.fromString(parts[1]),
                    new int[]{Integer.parseInt(parts[2]), Integer.parseInt(parts[3])});
            default -> {
            }
        }
    }

    private static int[] numbers(String csv) {
        String[] bits = csv.split(",");
        int[] out = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            out[i] = Integer.parseInt(bits[i]);
        }
        return out;
    }

    private static String line(String kind, Fixture fixture) {
        return kind + " " + fixture.id + " " + fixture.league + " " + fixture.condition
                + " " + (fixture.venue.isEmpty() ? "-" : fixture.venue.replace(' ', '_'))
                + " " + csv(fixture.field) + " " + csv(fixture.winOdds)
                + " " + (fixture.placeOdds == null ? "-" : csv(fixture.placeOdds))
                + " " + fixture.kickoff + " " + fixture.winner
                + " " + (fixture.podium.length == 0 ? "-" : csv(fixture.podium));
    }

    private static String csv(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int value : values) {
            out.append(out.length() == 0 ? "" : ",").append(value);
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (int league = 0; league < LEAGUES.length; league++) {
                Runner[] runners = LEAGUES[league].runners;
                for (int i = 0; i < runners.length; i++) {
                    Runner runner = runners[i];
                    if (runner.form.isEmpty() && runner.played == 0
                            && runner.rest == 2 && runner.absences == 0) {
                        continue;   // never run: nothing to remember
                    }
                    lines.add("R " + league + " " + i + " "
                            + (runner.form.isEmpty() ? "-" : runner.form) + " "
                            + runner.absences + " " + runner.rest + " "
                            + runner.played + " " + runner.won);
                }
            }
            H2H.forEach((key, record) -> lines.add("H " + key.replace(':', ' ')
                    + " " + record[0] + " " + record[1]));
            for (Fixture fixture : BOARD) {
                lines.add(line("F", fixture));
            }
            for (Fixture fixture : RESULTS) {
                lines.add(line("A", fixture));
            }
            for (Slip slip : SLIPS) {
                StringBuilder legs = new StringBuilder();
                for (Leg leg : slip.legs) {
                    legs.append(legs.length() == 0 ? "" : ",")
                            .append(leg.fixture).append(':').append(leg.market).append(':')
                            .append(leg.selection).append(':').append(leg.odds)
                            .append(':').append(leg.result);
                }
                lines.add("B " + slip.id + " " + slip.punter + " " + slip.stake
                        + " " + slip.odds + " " + legs);
            }
            PURSE.forEach((who, owed) ->
                    lines.add("P " + who + " " + owed[0] + " " + owed[1]));
            Files.write(saveFile, lines);
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't save the bookmaker: {}", failure.toString());
        }
    }

    static {
        // A sanity check that costs nothing and catches the one mistake this
        // file is shaped to make: a suits table whose rows do not line up with
        // the conditions, or whose columns do not line up with the styles.
        // Wrong here, a runner reads its neighbour's bonus and nothing throws.
        for (League league : LEAGUES) {
            if (league.suits.length != league.conditions.length) {
                throw new IllegalStateException(league.name + ": suits table has "
                        + league.suits.length + " rows for " + league.conditions.length
                        + " conditions");
            }
            for (int[] row : league.suits) {
                if (row.length != league.styles.length) {
                    throw new IllegalStateException(league.name + ": suits row has "
                            + row.length + " columns for " + league.styles.length + " styles");
                }
            }
            for (Runner runner : league.runners) {
                if (runner.style < 0 || runner.style >= league.styles.length) {
                    throw new IllegalStateException(league.name + ": " + runner.name
                            + " has style " + runner.style);
                }
            }
            if (league.venues != null && league.venues.length != league.conditions.length) {
                throw new IllegalStateException(league.name + ": venue table does not match");
            }
            if (league.runners.length < league.fieldSize * league.slots) {
                throw new IllegalStateException(league.name + ": not enough runners for "
                        + league.slots + " fixtures of " + league.fieldSize);
            }
        }
    }
}
