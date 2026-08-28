package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The public purse, and what everybody pays into it.
 *
 * Until a vault is placed there is no city: nothing is taxed, the treasury
 * does not exist, and a mailbox will not register a house because there is
 * nobody to register it with. Put one down and all three start at once. That
 * is the only moment in this mod where a single block turns a system on, and
 * it is deliberate -- founding the city should be a thing somebody did on a
 * Tuesday, not a thing that was always true.
 *
 * <h2>Where the money actually is</h2>
 *
 * In this ledger, not in the block. The vault is the counter you queue at, and
 * breaking it does not spend a single emerald -- it just means nobody can
 * reach the money or file a house until one is stood up again. Same reasoning
 * as the casino card: the key is not the safe.
 *
 * Tax moves with {@code collect} and {@code handOver} rather than
 * {@code take} and {@code pay}, because tax is a TRANSFER. Money leaving your
 * pocket for the city has not left the world, and reporting it as destroyed
 * and then re-created would have the market index feel two shocks where
 * nothing happened at all.
 *
 * <h2>What is not taxed</h2>
 *
 * Weed and coca sold to customers and dealers. Not an oversight -- it is the
 * whole shape of the thing: the black market pays better per hour precisely
 * because it pays nothing to anybody, and step six is where that becomes a
 * problem worth having.
 */
public final class TrapCity {

    /**
     * One rate, its band, and what it is for.
     *
     * Bands rather than free numbers because the budget moves these on its
     * own, and a rate that can wander to zero or to sixty is not a tax, it is
     * a weather system. Food is cheapest to buy and stays cheapest: the point
     * of splitting the rates at all is that a city can be expensive to
     * decorate and still cheap to eat in.
     */
    public enum Duty {
        ESSENTIALS("Podstawowe", "Jedzenie, nasiona i plony", 5, 2, 12),
        MATERIALS("Materiały", "Bloki budowlane i drewno", 8, 4, 16),
        LUXURY("Luksus", "Ozdoby, narzędzia, zaklęcia, rzadkie rzeczy", 12, 6, 22),
        INCOME("Dochód", "Wszystko, co ci płacą: lada, zlecenia, lombard",
                10, 4, 20),
        GAMING("Hazard", "Każdy zakład postawiony w kasynie", 6, 2, 14),
        RENT("Czynsz", "To, co lokator płaci właścicielowi", 6, 2, 14);

        private final String display;
        private final String blurb;
        private final int start;
        private final int floor;
        private final int ceiling;

        Duty(String display, String blurb, int start, int floor, int ceiling) {
            this.display = display;
            this.blurb = blurb;
            this.start = start;
            this.floor = floor;
            this.ceiling = ceiling;
        }

        public String display() {
            return display;
        }

        public String blurb() {
            return blurb;
        }

        public int start() {
            return start;
        }

        public int floor() {
            return floor;
        }

        public int ceiling() {
            return ceiling;
        }
    }

    /**
     * What the purse can be spent ON, besides handing it out.
     *
     * A treasury with no sink is a scoreboard, and a scoreboard nobody can
     * spend is a reason to stop collecting. Each of these is bought once,
     * permanently, by anybody, and announced -- the same rule as a withdrawal,
     * for the same reason.
     *
     * All four are things that only make sense for a CITY. The roads reward
     * building near each other, the lamps reward having somewhere to shop, the
     * watch is the city answering the thing that makes farms dangerous, and
     * the exchange is what a market town does to a market.
     */
    public enum Work {
        WATCH("Straż miejska", "Patrole przychodzą dwa razy rzadziej", 4000),
        ROADS("Brukowane drogi", "Domy przy skarbcu dostają klasę wyżej", 6000),
        LAMPS("Latarnie", "Mieszkańcy dużo częściej chodzą na zakupy", 3000),
        EXCHANGE("Giełda", "Lada płaci lepiej za wszystko", 8000),
        CLINIC("Przychodnia", "Lokatorzy dłużej znoszą złe warunki", 5000),
        TRAM("Tramwaje", "Więcej ludzi naraz robi zakupy", 7000),
        SCHOOL("Szkoła", "Wszyscy w mieście zarabiają więcej", 12000),
        GOLEMS("Golemy policyjne", "Komenda wyprowadza żelazne golemy na patrol", 9000);

        private final String display;
        private final String blurb;
        private final int cost;

        Work(String display, String blurb, int cost) {
            this.display = display;
            this.blurb = blurb;
            this.cost = cost;
        }

        public String display() {
            return display;
        }

        public String blurb() {
            return blurb;
        }

        public int cost() {
            return cost;
        }
    }

    /** How far the paving reaches from the vault. */
    public static final int ROADS_REACH = 64;
    /** What the watch does to a raid cooldown. */
    public static final float WATCH_COOLDOWN = 2.0f;
    /** What the lamps do to shop trade. */
    public static final float LAMPS_TRADE = 1.6f;
    /** What the exchange adds to what the counter pays. */
    public static final float EXCHANGE_SELL = 1.12f;
    /** What the clinic adds to every tenant's mood target. */
    public static final int CLINIC_MOOD = 12;
    /** How many townspeople can be out at once once the trams run. */
    public static final int TRAM_SHOPPERS = 10;
    /** What the school does to every wage in the city. */
    public static final float SCHOOL_WAGE = 1.25f;

    private static final java.util.Map<Work, Integer> BUILT =
            new java.util.EnumMap<>(Work.class);

    /**
     * How many times this work has been paid for. Nought to {@link #TOP_TIER}.
     *
     * A work used to be a thing you either had or did not, which made the
     * whole board a shopping list you finish. Twenty-eight thousand emeralds
     * of it, once, and then the city is done forever while its founders sit on
     * more than that each. Levels are the same content charging what it is
     * worth to somebody who already owns everything.
     */
    public static int level(Work work) {
        return BUILT.getOrDefault(work, 0);
    }

    /**
     * Has the city got one at all?
     *
     * Kept exactly as it was, deliberately. Eleven places ask this question --
     * the clinic's discount, the school's wages, the watch's patrols -- and
     * every one of them means "is there one", not "how many". Changing what
     * this returns would switch off every public work in the mod at once, in
     * eleven files, silently.
     */
    public static boolean built(Work work) {
        return level(work) >= 1;
    }

    /** Levels a work can be taken to. Three is a long enough ladder. */
    public static final int TOP_TIER = 3;
    /** What each tier multiplies the last one's price by. */
    private static final double TIER_STEP = 2.5;

    /** What the next level of this work costs, or -1 if it is finished. */
    public static int nextCost(Work work) {
        int level = level(work);
        if (level >= TOP_TIER) {
            return -1;
        }
        return (int) Math.round(work.cost() * Math.pow(TIER_STEP, level));
    }

    /** "The Clinic II", or just "The Clinic" for the first one. */
    public static String titleOf(Work work) {
        int level = level(work);
        return work.display() + (level <= 1 ? "" : level == 2 ? " II" : " III");
    }

    /** @return why it didn't happen, or null if it did */
    public static String build(ServerPlayerEntity who, Work work) {
        if (!founded()) {
            return "Nie ma miasta, w którym można to zbudować.";
        }
        int cost = nextCost(work);
        if (cost < 0) {
            return work.display() + " jest ukończone -- nie ma poziomu " + (TOP_TIER + 1)
                    + ". poziom do zbudowania.";
        }
        if (treasury < cost) {
            return (level(work) == 0 ? work.display() : "Kolejny poziom: " + work.display())
                    + " kosztuje " + cost + "e, a w kasie jest " + treasury + "e.";
        }
        treasury -= cost;
        BUILT.merge(work, 1, Integer::sum);
        save();
        announce(who.getServer(), Text.literal(titleOf(work).toUpperCase(java.util.Locale.ROOT))
                .formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("\n  " + who.getGameProfile().getName() + " wydał "
                        + cost + "e z kasy miasta. " + work.blurb() + ".")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("\n  W kasie zostało " + treasury + "e.")
                        .formatted(Formatting.DARK_GRAY)));
        return null;
    }

    /** Is this spot close enough to the vault for the paving to reach it? */
    public static boolean paved(String dimension, BlockPos pos) {
        return built(Work.ROADS) && founded() && dimension.equals(vaultWorld)
                && Math.sqrt(pos.getSquaredDistance(vaultAt)) <= ROADS_REACH;
    }

    /**
     * Laws the council passes when the city needs them, and repeals when it
     * does not.
     *
     * Reactive, never random. A rule that arrives for no reason is weather,
     * and nobody plays around weather -- but a levy that turns up because the
     * purse is empty is a thing the room did to itself, and the constitution
     * ends up reading as a history of the server.
     */
    public enum Act {
        LEVY("Danina", "Wszystkie stawki w górę, dopóki kasa jest pusta", 3),
        CRACKDOWN("Obława", "Patrole przychodzą dużo częściej", 0),
        STANDARDS("Normy mieszkaniowe", "Domu klasy 1 nie wolno już wynajmować", 0),
        DRIVE("Akcja skarbówki", "Urząd zabiera większą część tego, co wykryje", 0);

        private final String display;
        private final String blurb;
        private final int rateBump;

        Act(String display, String blurb, int rateBump) {
            this.display = display;
            this.blurb = blurb;
            this.rateBump = rateBump;
        }

        public String display() {
            return display;
        }

        public String blurb() {
            return blurb;
        }

        public int rateBump() {
            return rateBump;
        }
    }

    /** Heat across the server that brings the constable out. */
    public static final int CRACKDOWN_HEAT = 260;
    /** Housed grades at which the city starts turning its nose up. */
    public static final int STANDARDS_AT = 30;
    /** Undeclared money a day, server-wide, that sets the office off. */
    public static final int DRIVE_AT = 2500;

    private static final java.util.Set<Act> ACTS = java.util.EnumSet.noneOf(Act.class);
    private static final Map<Act, Long> PASSED = new EnumMap<>(Act.class);

    public static boolean inForce(Act act) {
        return ACTS.contains(act);
    }

    public static long passedOn(Act act) {
        return PASSED.getOrDefault(act, 0L);
    }

    /** What a duty actually charges, acts and all. */
    public static int chargedRate(Duty duty) {
        int rate = rateOf(duty);
        for (Act act : ACTS) {
            rate += act.rateBump();
        }
        return Math.max(0, Math.min(duty.ceiling() + 6, rate));
    }

    /** In-game days between budgets. */
    private static final int BUDGET_DAYS = 2;
    /** Under this and the city puts rates up; over it and they come down. */
    public static final int BROKE = 600;
    public static final int FLUSH = 9000;

    // --- what the council is buying today -------------------------------------

    /**
     * One day's public order: a thing the city wants, and what it will pay.
     *
     * <h2>Why the treasury needed somewhere to leak</h2>
     *
     * Money reached the purse from six directions and left it through two: a
     * public work, and somebody at the counter taking a withdrawal out. The
     * first is a one-off with a floor -- eight works and then nothing -- and
     * the second is not the city spending money, it is a player moving their
     * own between pockets. So a treasury that filled up stayed full, and a
     * number that only ever goes up is a scoreboard however carefully it is
     * calculated.
     *
     * An order is the missing direction. The council wants materials, it pays
     * for them out of the purse the rates fill, and the emeralds land on a
     * player who then pays duty on them. Rates in, wages out, and the loop is
     * closed by a thing somebody carried across town.
     *
     * <h2>Only while there is something to build</h2>
     *
     * The list is gated on an unbuilt {@link Work} existing, which is the
     * honest reading of what an order IS: the city is not buying stone for
     * fun, it is buying stone for the thing it has not built yet. A city that
     * has built everything stops posting work, and the last tier of the last
     * work is therefore the end of the programme rather than an income that
     * runs forever.
     *
     * <h2>The price can never beat the counter</h2>
     *
     * {@link TrapMath#stallPrice} of what the market CHARGES, which is the
     * same premium a neighbour's stall pays and is deliberately under 100%.
     * Anything at or over the counter's own asking price is a machine that
     * turns emeralds into emeralds: buy the lot off the market, carry it eight
     * blocks, sell it to the council, repeat. It would have been the fastest
     * money in the mod and it would have looked like a feature for about a
     * day.
     */
    public record Order(ShopStock.Entry entry, int lots, int paid) {
    }

    /** Days between the council changing its mind about what it wants. */
    private static final int ORDER_DAYS = 1;
    /** Smallest and largest order, in catalogue lots. */
    private static final int ORDER_MIN = 6;
    private static final int ORDER_SPAN = 12;
    /**
     * The purse has to hold this many times the order before it is posted.
     *
     * A council does not spend its last emerald on paving. More usefully: it
     * stops the order existing at all on a treasury that cannot honour it,
     * which is better than one that can be accepted and then refused at the
     * counter.
     */
    private static final int ORDER_COVER = 3;

    /** The day the standing order was filled, so it is only filled once. */
    private static long orderFilled = -1;

    private static final Map<Duty, Integer> RATES = new EnumMap<>(Duty.class);
    private static final Map<Duty, Integer> TAKEN = new EnumMap<>(Duty.class);
    private static long treasury;
    private static String vaultWorld;
    private static BlockPos vaultAt;
    private static long lastBudget = -1;
    private static Path saveFile;
    private static Path logFile;
    private static long lastLogged = -1;

    private TrapCity() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapCity::load);
        registerCommand();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Once every ten seconds is plenty for a thing that happens twice
            // a Minecraft day.
            if (server.getTicks() % 200 == 0) {
                budget(server);
                long day = TrapMarket.today(server);
                if (day != lastLogged) {
                    lastLogged = day;
                    rates(server);
                    logDay(server, day);
                }
            }
        });
    }

    // --- is there a city at all -----------------------------------------------

    public static boolean founded() {
        return vaultAt != null;
    }

    public static BlockPos vaultAt() {
        return vaultAt;
    }

    public static String vaultWorld() {
        return vaultWorld;
    }

    /** @return why it didn't happen, or null if it did */
    public static String found(ServerWorld world, BlockPos pos, ServerPlayerEntity who) {
        String dimension = world.getRegistryKey().getValue().toString();
        if (founded() && !(vaultAt.equals(pos) && dimension.equals(vaultWorld))) {
            return "Miasto ma już skarbiec, na " + vaultAt.getX() + " "
                    + vaultAt.getY() + " " + vaultAt.getZ() + ".";
        }
        boolean first = !founded();
        vaultWorld = dimension;
        vaultAt = pos.toImmutable();
        save();
        if (first) {
            announce(who.getServer(), Text.literal("MIASTO ZAŁOŻONE")
                    .formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal("\n  " + who.getGameProfile().getName()
                            + " postawił skarbiec. Od teraz handel jest opodatkowany, "
                            + "a domy można rejestrować.").formatted(Formatting.GRAY))
                    .append(Text.literal("\n  /city").formatted(Formatting.GREEN))
                    .append(Text.literal("  pokazuje rachunki.").formatted(Formatting.DARK_GRAY)));
        }
        return null;
    }

    /** The vault came down. The money stays exactly where it was. */
    public static void lost(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        if (!founded() || !vaultAt.equals(pos) || !dimension.equals(vaultWorld)) {
            return;
        }
        vaultAt = null;
        vaultWorld = null;
        save();
        announce(world.getServer(), Text.literal("Skarbiec miasta zniknął. ")
                .formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal("Nic nie jest opodatkowane i nie da się "
                        + "zarejestrować domu, dopóki go nie postawisz. "
                        + treasury + "e jest bezpieczne.").formatted(Formatting.GRAY)));
    }

    // --- the money ------------------------------------------------------------

    public static long treasury() {
        return treasury;
    }

    public static int rateOf(Duty duty) {
        return RATES.getOrDefault(duty, duty.start());
    }

    public static int takenBy(Duty duty) {
        return TAKEN.getOrDefault(duty, 0);
    }

    /** What this duty adds to a price, rounded up so a 1e line still pays. */
    public static int dutyOn(int amount, Duty duty) {
        if (!founded() || amount <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(amount * chargedRate(duty) / 100.0f));
    }

    /** The band a shop line falls in. Food is not decoration. */
    public static Duty forGoods(ShopStock.Category category) {
        String id = category == null ? "" : category.id();
        return switch (id) {
            case "food", "farming", "garden" -> Duty.ESSENTIALS;
            case "building", "wood", "materials" -> Duty.MATERIALS;
            default -> Duty.LUXURY;
        };
    }

    /**
     * Take the duty out of somebody's pocket and put it in the purse.
     *
     * collect, not take: the money is moving, not evaporating. Returns what
     * was actually charged so the caller can say so.
     */
    public static int charge(ServerPlayerEntity who, int amount, Duty duty) {
        int owed = dutyOn(amount, duty);
        if (owed <= 0) {
            return 0;
        }
        if (TrapMarket.wealthOf(who) < owed) {
            // Nothing is ever charged that cannot be paid. A tax that can put
            // somebody into a debt this mod has no concept of would be a bug
            // with a very long tail.
            return 0;
        }
        TrapMarket.collect(who, owed);
        treasury += owed;
        TAKEN.merge(duty, owed, Integer::sum);
        TrapLedger.record(who, TrapLedger.Source.TAX, -owed);
        save();
        return owed;
    }

    /**
     * What the council is buying today, or null if it is buying nothing.
     *
     * Seeded off the day and the world, exactly as {@link TrapContracts#board}
     * is and for the same reason: an order that reshuffled every time somebody
     * opened the vault would be a slot machine you play by closing a screen.
     * Two players see the same order, and it is the same one tomorrow morning
     * that it was tonight.
     *
     * Priced live rather than at posting, which is the one deliberate break
     * from the contract board. A materials price moves with the index all day
     * and a quote pinned at midnight would be visibly wrong by evening -- and
     * unlike a delivery job there is no risk being paid for here, so there is
     * nothing that wants freezing.
     */
    public static Order order(MinecraftServer server) {
        if (!founded() || filledToday(server)) {
            return null;
        }
        boolean building = false;
        for (Work work : Work.values()) {
            if (level(work) < TOP_TIER) {
                building = true;
                break;
            }
        }
        if (!building) {
            return null;   // nothing left to build, so nothing left to buy
        }
        List<ShopStock.Entry> shelf = new ArrayList<>();
        shelf.addAll(ShopStock.of(ShopStock.BUILDING));
        shelf.addAll(ShopStock.of(ShopStock.MATERIALS));
        shelf.addAll(ShopStock.of(ShopStock.WOOD));
        if (shelf.isEmpty()) {
            return null;
        }
        long day = TrapMarket.today(server) / ORDER_DAYS;
        var random = net.minecraft.util.math.random.Random.create(
                day * 7919L + server.getOverworld().getSeed());
        ShopStock.Entry entry = shelf.get(random.nextInt(shelf.size()));
        int lots = ORDER_MIN + random.nextInt(ORDER_SPAN);
        int paid = Math.max(1,
                TrapMath.stallPrice(TrapMarket.buyPrice(server, entry))) * lots;
        return treasury >= (long) paid * ORDER_COVER ? new Order(entry, lots, paid) : null;
    }

    /** Has today's order already been filled? */
    public static boolean filledToday(MinecraftServer server) {
        return orderFilled == TrapMarket.today(server) / ORDER_DAYS;
    }

    /**
     * Somebody brings the council what it asked for.
     *
     * Fails closed at every step and in this order: the goods have to be in
     * the bag before the purse is touched, and the purse has to pay before the
     * goods come out of the bag. Getting that backwards is how a player ends
     * up having handed over sixteen stacks of stone to a city that turned out
     * to be broke.
     *
     * {@code handOver}, not {@code pay}: these emeralds have been in the world
     * since payday and are only moving from the treasury to a pocket. Minting
     * them here would have the index feel the council's entire building
     * programme as inflation.
     *
     * @return what was paid, or 0 if nothing happened
     */
    public static int fill(ServerPlayerEntity who, Order order) {
        if (order == null || filledToday(who.getServer())) {
            return 0;
        }
        int wanted = order.lots() * order.entry().count();
        if (countHeld(who, order.entry()) < wanted) {
            return 0;
        }
        if (!spend(order.paid())) {
            return 0;
        }
        takeFrom(who, order.entry(), wanted);
        TrapMarket.handOver(who, order.paid());
        // Declared income, because this is the council paying an invoice. It
        // is the one line of work in the mod that the revenue office was never
        // going to have to come looking for.
        charge(who, order.paid(), Duty.INCOME);
        TrapLedger.record(who, TrapLedger.Source.CONTRACT, order.paid());
        orderFilled = TrapMarket.today(who.getServer()) / ORDER_DAYS;
        save();
        return order.paid();
    }

    /** How many of this line somebody is carrying. */
    private static int countHeld(ServerPlayerEntity who, ShopStock.Entry entry) {
        int found = 0;
        var inventory = who.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (entry.matches(inventory.getStack(slot))) {
                found += inventory.getStack(slot).getCount();
            }
        }
        return found;
    }

    private static void takeFrom(ServerPlayerEntity who, ShopStock.Entry entry, int wanted) {
        int owed = wanted;
        var inventory = who.getInventory();
        for (int slot = 0; slot < inventory.size() && owed > 0; slot++) {
            var stack = inventory.getStack(slot);
            if (!entry.matches(stack)) {
                continue;
            }
            int taken = Math.min(owed, stack.getCount());
            stack.decrement(taken);
            owed -= taken;
        }
    }

    /**
     * Duty on a sale nobody's pocket paid for.
     *
     * A townsperson buying a loaf off a shelf brings emeralds into the world
     * that were never in it before, so there is no player to collect from --
     * the caller mints the whole price and hands this the city's share. Kept
     * separate from {@link #charge} on purpose: one moves money and the other
     * receives money that has just been made, and conflating them is how a
     * treasury quietly starts inventing itself.
     *
     * Also takes transfers out of the town purse -- rent and shelf duty are
     * money {@link TrapPayroll} minted at payday, arriving late.
     */
    public static void receive(int amount, Duty duty) {
        if (!founded() || amount <= 0) {
            return;
        }
        treasury += amount;
        TAKEN.merge(duty, amount, Integer::sum);
        save();
    }

    /**
     * The city pays a bill nobody chose to pay.
     *
     * Kept apart from {@link #withdraw} because that one is a person at the
     * counter taking money out, announced to the server, and this is a
     * standing cost the council cannot decline -- a ward full of people the
     * doctors are treating. Fails closed and says so: an unpaid bill has to be
     * the caller's problem, or the treasury quietly goes negative and the
     * first anybody knows is a purse that will not fund a public work.
     *
     * @return false if the purse could not cover it, and nothing was taken
     */
    public static boolean spend(int amount) {
        if (amount <= 0) {
            return true;
        }
        if (!founded() || treasury < amount) {
            return false;
        }
        treasury -= amount;
        save();
        return true;
    }

    /** Anybody may spend it, and everybody hears about it. */
    /**
     * Somebody puts their own money into the city's.
     *
     * The vault has been a one-way tap since it was built: {@link #withdraw}
     * hands the treasury out, {@link #donate} grants a casino its float, and
     * the only two things that ever put anything IN are duties skimmed off
     * transactions other people made. So the twenty-eight thousand emeralds of
     * unbuilt public works sat behind a purse that fills at four percent of
     * somebody else's payday, while the people who wanted them sat on more
     * than that each and had no way to hand it over.
     *
     * Through {@link TrapMarket#take}, so the emeralds leave circulation the
     * way every other payment does rather than teleporting between two
     * counters. See the note on TrapMarket.take about checking wealth first.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String payIn(ServerPlayerEntity who, int amount) {
        if (!founded()) {
            return "Nie ma miasta, któremu można to dać.";
        }
        if (amount <= 0) {
            return "Nie ma czego przekazać.";
        }
        int held = TrapMarket.wealthOf(who);
        if (held < amount) {
            return "Masz przy sobie " + held + "e, a nie " + amount + "e.";
        }
        TrapMarket.take(who, amount);
        treasury += amount;
        TrapLedger.record(who, TrapLedger.Source.TAX, -amount);
        save();
        announce(who.getServer(), Text.literal(who.getGameProfile().getName())
                .formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(" wpłacił " + amount + "e do kasy miasta. ")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("Teraz jest w niej " + treasury + "e.")
                        .formatted(Formatting.DARK_GRAY)));
        return null;
    }

    public static String withdraw(ServerPlayerEntity who, int amount) {
        if (!founded()) {
            return "Nie ma skarbca, z którego można wziąć.";
        }
        int wanted = (int) Math.min(amount, Math.min(treasury, Integer.MAX_VALUE));
        if (wanted <= 0) {
            return "Kasa miasta jest pusta.";
        }
        treasury -= wanted;
        TrapMarket.handOver(who, wanted);
        TrapLedger.record(who, TrapLedger.Source.TAX, wanted);
        save();
        announce(who.getServer(), Text.literal(who.getGameProfile().getName())
                .formatted(Formatting.WHITE)
                .append(Text.literal(" wypłacił ").formatted(Formatting.GRAY))
                .append(Text.literal(wanted + "e").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" z kasy miasta. Zostało " + treasury + "e.")
                        .formatted(Formatting.GRAY)));
        return null;
    }

    /**
     * Straight out of the purse and into a casino's float.
     *
     * The same rule as {@link #withdraw}, because it is the same purse:
     * anybody may, everybody is told. A donation is a grant, not a loan and
     * not a stake -- the city gets nothing back and no note is written,
     * because a town that wants its money back can walk over and gamble.
     *
     * Nothing is minted. Both ends of this are counted as money in the world
     * already, so it neither goes through {@link TrapMarket} nor near
     * {@link TrapLedger}: no player is a penny better or worse off for it,
     * and a player ledger that logged it would be logging somebody else's
     * emeralds under their name.
     */
    public static String donate(ServerPlayerEntity who, TrapHouse.House house, int amount) {
        if (!founded()) {
            return "Nie ma skarbca, z którego można dać.";
        }
        if (house == null) {
            return "Tego kasyna już nie ma.";
        }
        int given = (int) Math.min(Math.max(amount, 0),
                Math.min(treasury, Integer.MAX_VALUE));
        if (given <= 0) {
            return treasury <= 0 ? "Kasa miasta jest pusta." : "Najpierw ustaw kwotę.";
        }
        treasury -= given;
        TrapHouse.endow(house, given);
        save();
        announce(who.getServer(), Text.literal(who.getGameProfile().getName())
                .formatted(Formatting.WHITE)
                .append(Text.literal(" przekazał ").formatted(Formatting.GRAY))
                .append(Text.literal(given + "e").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" z kasy miasta dla ").formatted(Formatting.GRAY))
                .append(Text.literal(house.name).formatted(Formatting.LIGHT_PURPLE))
                .append(Text.literal(". Zostało " + treasury + "e.").formatted(Formatting.GRAY)));
        return null;
    }

    /** What a landlord pays the city each day, per grade of house they let. */
    public static final int HOUSE_RATE = 15;
    /** And per shop counter they keep. */
    public static final int SHOP_RATE = 60;

    /**
     * What it costs to own things, charged daily.
     *
     * A shop and a let house were the only two businesses in this mod that
     * were pure income: rent and takings arrive, and nothing ever leaves. A
     * casino pays upkeep out of its own vault and a crew wants wages, but a
     * landlord with nine houses simply got richer every morning forever, which
     * is how a server ends up with fifty thousand emeralds and a shopping list
     * of nothing.
     *
     * Scaled by grade, because the thing being taxed is the value of what you
     * hold. It goes into the purse the public works come out of, which closes
     * the loop: the rates fund the works, and the works are what make the
     * houses worth holding.
     *
     * Only the online, and only ever one day of it. Somebody who was away for
     * a week owes a day, exactly like a tenant whose chunk was asleep -- the
     * alternative is logging in to a bill you cannot pay and a debt that never
     * clears.
     */
    private static void rates(MinecraftServer server) {
        if (!founded()) {
            return;
        }
        for (ServerPlayerEntity who : server.getPlayerManager().getPlayerList()) {
            int owed = 0;
            for (TrapHomes.Home home : TrapHomes.all()) {
                if (who.getUuid().equals(home.owner()) && home.tenant() != null) {
                    owed += HOUSE_RATE * Math.max(1, home.tier());
                }
            }
            for (TrapShops.Shop shop : TrapShops.shops()) {
                if (who.getUuid().equals(shop.owner())) {
                    owed += SHOP_RATE;
                }
            }
            if (owed <= 0) {
                continue;
            }
            if (TrapMarket.wealthOf(who) < owed) {
                who.sendMessage(Text.literal("Opłaty miejskie: ").formatted(Formatting.DARK_GRAY)
                        .append(Text.literal("Do zapłaty " + owed + "e, a tyle nie masz. ")
                                .formatted(Formatting.RED))
                        .append(Text.literal("Dziś nic się nie dzieje.")
                                .formatted(Formatting.DARK_GRAY)), false);
                continue;
            }
            TrapMarket.take(who, owed);
            treasury += owed;
            TrapLedger.record(who, TrapLedger.Source.TAX, -owed);
            who.sendMessage(Text.literal("Opłaty miejskie: ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("-" + owed + "e").formatted(Formatting.RED))
                    .append(Text.literal("  za to, co posiadasz").formatted(Formatting.DARK_GRAY)),
                    true);
        }
        save();
    }

    // --- the budget -----------------------------------------------------------

    /**
     * Rates move, and they move for a reason.
     *
     * The design said laws should be reactive rather than random, and it was
     * right, but a table that only ever moves in a crisis is a table nobody
     * looks at twice. So: broke puts everything up, flush brings everything
     * down, and an ordinary week wanders a point either way. The reason is
     * always announced, which is what makes it read as a council rather than
     * as weather.
     */
    private static void budget(MinecraftServer server) {
        if (!founded()) {
            return;
        }
        ServerWorld overworld = server.getOverworld();
        long day = TrapMarket.today(server);
        if (lastBudget < 0) {
            lastBudget = day;
            save();
            return;
        }
        if (day - lastBudget < BUDGET_DAYS) {
            return;
        }
        lastBudget = day;
        sitting(server, overworld, day);

        String why;
        int move;
        if (treasury < BROKE) {
            why = "Kasa miasta pusta. Podatki w górę.";
            move = 2;
        } else if (treasury > FLUSH) {
            why = "Kasa miasta pełna. Podatki w dół.";
            move = -1;
        } else {
            why = "Rachunki się zgadzają. Drobne korekty.";
            move = 0;
        }

        var random = overworld.getRandom();
        StringBuilder table = new StringBuilder();
        for (Duty duty : Duty.values()) {
            int was = rateOf(duty);
            int step = move != 0 ? move : random.nextInt(3) - 1;
            int now = Math.max(duty.floor(), Math.min(duty.ceiling(), was + step));
            RATES.put(duty, now);
            table.append("\n  ").append(duty.display()).append("  ").append(now).append('%')
                    .append(now == was ? "" : now > was ? "  (wzrost z " + was + "%)"
                            : "  (spadek z " + was + "%)");
        }
        save();
        announce(server, Text.literal("BUDŻET MIASTA").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("\n  " + why).formatted(Formatting.GRAY))
                .append(Text.literal(table.toString()).formatted(Formatting.WHITE))
                .append(Text.literal("\n  /city").formatted(Formatting.GREEN))
                .append(Text.literal("  pokazuje całą tabelę.").formatted(Formatting.DARK_GRAY)));
    }

    /**
     * The council sits: what is true right now, and what the law says about it.
     *
     * Every act is re-checked from scratch rather than toggled, so a condition
     * that clears repeals its act without anybody having to remember to. That
     * is the difference between a law and a debuff.
     */
    private static void sitting(MinecraftServer server, ServerWorld overworld, long day) {
        java.util.EnumMap<Act, Boolean> wanted = new java.util.EnumMap<>(Act.class);
        wanted.put(Act.LEVY, treasury < BROKE);
        wanted.put(Act.CRACKDOWN,
                TrapHeat.measureHeat(overworld, overworld.getSpawnPos()) >= CRACKDOWN_HEAT
                        || TrapLaw.serverHeat() >= CRACKDOWN_HEAT);
        wanted.put(Act.STANDARDS, TrapHomes.population() >= STANDARDS_AT);
        wanted.put(Act.DRIVE, TrapLaw.undeclaredToday() >= DRIVE_AT);

        for (var row : wanted.entrySet()) {
            Act act = row.getKey();
            boolean now = row.getValue();
            if (now == ACTS.contains(act)) {
                continue;
            }
            if (now) {
                ACTS.add(act);
                PASSED.put(act, day);
                announce(server, Text.literal("UCHWALONO  ").formatted(Formatting.RED,
                                Formatting.BOLD)
                        .append(Text.literal(act.display()).formatted(Formatting.WHITE,
                                Formatting.BOLD))
                        .append(Text.literal("\n  " + act.blurb() + ".")
                                .formatted(Formatting.GRAY))
                        .append(Text.literal("\n  /law").formatted(Formatting.GREEN))
                        .append(Text.literal("  pokazuje prawo miasta.")
                                .formatted(Formatting.DARK_GRAY)));
            } else {
                ACTS.remove(act);
                PASSED.remove(act);
                announce(server, Text.literal("UCHYLONO  ").formatted(Formatting.GREEN,
                                Formatting.BOLD)
                        .append(Text.literal(act.display()).formatted(Formatting.WHITE))
                        .append(Text.literal(". Powód ustał.")
                                .formatted(Formatting.GRAY)));
            }
        }
        TrapLaw.lawChanged(server);
    }

    /**
     * One row a day of everything worth balancing against.
     *
     * Written whether or not anybody asked, because the alternative is tuning
     * a nine-system economy off a feeling. The earnings ledger already says
     * what each PLAYER made; this says what the CITY did, which is the half
     * nobody could see.
     */
    private static void logDay(MinecraftServer server, long day) {
        if (logFile == null) {
            return;
        }
        try {
            boolean fresh = !Files.exists(logFile);
            StringBuilder row = new StringBuilder();
            if (fresh) {
                row.append(header());
            } else {
                repairHeader();
            }

            int houses = TrapHomes.all().size();
            int housed = 0;
            int grades = 0;
            for (TrapHomes.Home home : TrapHomes.all()) {
                grades += home.tier();
                if (home.tenant() != null) {
                    housed++;
                }
            }
            int shelfSales = 0;
            for (TrapShops.Shop shop : TrapShops.shops()) {
                shelfSales += shop.sold();
            }
            long casinoBalance = 0;
            long casinoHandle = 0;
            long casinoNet = 0;
            int worstWear = 0;
            for (TrapHouse.House house : TrapHouse.all()) {
                casinoBalance += house.balance;
                casinoHandle += house.handle;
                casinoNet += house.handle - house.paid;
                worstWear = Math.max(worstWear, TrapHouse.worstWear(house));
            }
            int crew = 0;
            int payroll = 0;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                crew += TrapCrew.sizeOf(player);
                payroll += TrapCrew.payrollOf(player);
            }

            row.append(day).append(',')
                    .append(server.getPlayerManager().getCurrentPlayerCount()).append(',')
                    .append(TrapHomes.population()).append(',')
                    .append(houses).append(',').append(housed).append(',').append(housed)
                    .append(',')
                    .append(houses == 0 ? 0 : String.format("%.2f", grades / (float) houses))
                    .append(',')
                    .append(treasury).append(',');
            int raised = 0;
            for (Duty duty : Duty.values()) {
                raised += takenBy(duty);
            }
            row.append(raised).append(',');
            for (Duty duty : Duty.values()) {
                row.append(chargedRate(duty)).append(',');
            }
            for (Duty duty : Duty.values()) {
                row.append(takenBy(duty)).append(',');
            }
            StringBuilder acts = new StringBuilder();
            for (Act act : ACTS) {
                acts.append(acts.isEmpty() ? "" : "|").append(act.name());
            }
            StringBuilder works = new StringBuilder();
            for (Work work : BUILT.keySet()) {
                works.append(works.isEmpty() ? "" : "|").append(work.name());
            }
            row.append(acts.isEmpty() ? "-" : acts).append(',')
                    .append(works.isEmpty() ? "-" : works).append(',')
                    .append(TrapShops.all().size()).append(',')
                    .append(shelfSales).append(',')
                    .append(TrapShops.tillsHeld()).append(',')
                    .append(casinoBalance).append(',').append(casinoHandle).append(',')
                    .append(casinoNet).append(',').append(worstWear).append(',')
                    .append(crew).append(',').append(payroll).append(',')
                    .append(TrapDealers.count()).append(',')
                    .append(TrapLaw.serverHeat()).append(',')
                    .append(String.format("%.3f", TrapMarket.index())).append(',')
                    .append(Math.round(TrapMarket.supplyNow())).append(',')
                    .append(TrapLaw.declaredToday()).append(',')
                    .append(TrapLaw.undeclaredToday()).append(',')
                    .append(TrapLaw.washedToday()).append(',')
                    .append(TrapLaw.owedTotal()).append(',')
                    // The three that say whether WAGE_MULTIPLE is wrong. A
                    // purse climbing every day means the town cannot spend
                    // what it earns; a purse pinned at nothing means the
                    // shops are starving.
                    .append(TrapPayroll.purse()).append(',')
                    .append(TrapPayroll.wagesPaid()).append(',')
                    .append(TrapPayroll.incomeTax()).append(',')
                    // The ward is the one thing that takes money out of the
                    // purse without a player deciding to. A treasury sliding
                    // down for no reason anybody chose is exactly what this
                    // file exists to explain.
                    .append(TrapHospitals.all().size()).append(',')
                    .append(TrapHospitals.beds()).append(',')
                    .append(TrapHospitals.patients().size()).append(',')
                    .append(TrapHospitals.spent()).append(',')
                    // The second office, and the one whose numbers have to be
                    // read TOGETHER: a budget that climbs while crimes climb
                    // with it is a force that is being paid to lose, and
                    // neither column says that on its own.
                    .append(TrapPolice.all().size()).append(',')
                    .append(TrapPolice.force()).append(',')
                    .append(TrapPolice.budget()).append(',')
                    .append(TrapPolice.funded()).append(',')
                    .append(TrapPolice.spent()).append(',')
                    .append(TrapPolice.fines()).append(',')
                    .append(TrapCrime.total()).append(',')
                    .append(TrapCrime.solved()).append(',')
                    .append(TrapCrime.stolen()).append(',')
                    // A whole percent, as an integer, deliberately: every
                    // other float in this file goes through String.format,
                    // which writes a comma for a decimal point under a locale
                    // that uses one -- in a comma-separated file.
                    .append(Math.round(TrapCrime.hardship() * 100)).append('\n');
            Files.writeString(logFile, row.toString(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't write the city log: {}", failure.toString());
        }
    }

    private static void announce(MinecraftServer server, Text what) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(what, false);
        }
    }

    // --- the readout ----------------------------------------------------------

    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("city")
                                .executes(context -> {
                                    ServerPlayerEntity who = context.getSource().getPlayer();
                                    if (who == null) {
                                        return 0;
                                    }
                                    books(who);
                                    return 1;
                                })
                                .then(net.minecraft.server.command.CommandManager
                                        .literal("history")
                                        .executes(context -> {
                                            ServerPlayerEntity who =
                                                    context.getSource().getPlayer();
                                            if (who == null) {
                                                return 0;
                                            }
                                            history(who);
                                            return 1;
                                        }))));
    }

    private static void books(ServerPlayerEntity who) {
        if (!founded()) {
            who.sendMessage(Text.literal("Nie ma jeszcze miasta. ").formatted(Formatting.GRAY)
                    .append(Text.literal("Zrób skarbiec miasta i postaw go.")
                            .formatted(Formatting.WHITE)), false);
            return;
        }
        who.sendMessage(Text.literal("Miasto").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("   kasa ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(treasury + "e").formatted(Formatting.GREEN,
                        Formatting.BOLD))
                .append(Text.literal("   skarbiec na " + vaultAt.getX() + " " + vaultAt.getY()
                        + " " + vaultAt.getZ()).formatted(Formatting.DARK_GRAY)), false);
        who.sendMessage(Text.literal("  Mieszkańcy mają jeszcze ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(TrapPayroll.purse() + "e").formatted(Formatting.AQUA,
                        Formatting.BOLD))
                .append(Text.literal(" z własnych pensji do wydania. ")
                        .formatted(Formatting.DARK_GRAY))
                .append(Text.literal(TrapPayroll.purse() > 0 ? "" : "Nikt nie robi zakupów.")
                        .formatted(Formatting.RED)), false);
        for (Duty duty : Duty.values()) {
            who.sendMessage(Text.literal("  " + duty.display()).formatted(Formatting.WHITE)
                    .append(Text.literal("  " + rateOf(duty) + "%")
                            .formatted(Formatting.GOLD, Formatting.BOLD))
                    .append(Text.literal("  " + duty.blurb()).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("  raised " + takenBy(duty) + "e")
                            .formatted(Formatting.DARK_GRAY)), false);
        }
        who.sendMessage(Text.literal("  Sprzedaż klientom z ulicy i dilerom nie jest opodatkowana.")
                .formatted(Formatting.DARK_GRAY), false);
        // The one standing outgoing. Printed whether or not there is a
        // hospital, because "no beds at all" is the reading that matters.
        int ill = TrapHospitals.patients().size();
        who.sendMessage(Text.literal("  Szpitale").formatted(Formatting.WHITE)
                .append(Text.literal("  " + TrapHospitals.all().size() + ", "
                        + TrapHospitals.beds() + " łóżek")
                        .formatted(TrapHospitals.beds() > 0 ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal(ill == 0 ? "  nikt nie choruje"
                                : "  chorych: " + ill + ", po " + TrapHospitals.bill() + "e dziennie")
                        .formatted(ill == 0 ? Formatting.DARK_GRAY : Formatting.YELLOW))
                .append(Text.literal("  wypłacono " + TrapHospitals.spent() + "e")
                        .formatted(Formatting.DARK_GRAY)), false);
        // The other standing outgoing, and the only one the council chooses.
        // Printed whether or not there is a station, because "no police at
        // all" is the reading that explains the burglaries.
        int force = TrapPolice.force();
        who.sendMessage(Text.empty()
                .append(TrapNotes.say("  Policja", Formatting.WHITE))
                .append(TrapNotes.say("  " + TrapPolice.all().size() + " komisariatów, "
                                + force + " na etacie",
                        force > 0 ? Formatting.GREEN : Formatting.RED))
                .append(TrapNotes.say("  " + TrapPolice.budget() + "e dziennie"
                                + (TrapPolice.funded() < TrapPolice.budget()
                                ? ", wypłacono " + TrapPolice.funded() + "e" : ""),
                        TrapPolice.funded() < TrapPolice.budget()
                                ? Formatting.RED : Formatting.GOLD))
                .append(TrapNotes.say("  zatrzymań " + TrapPolice.arrests() + " z "
                        + TrapCrime.total() + " spraw", Formatting.DARK_GRAY)), false);
        for (Work work : Work.values()) {
            who.sendMessage(Text.literal("  " + work.display())
                    .formatted(built(work) ? Formatting.GREEN : Formatting.DARK_GRAY)
                    .append(Text.literal(built(work) ? "  zbudowane" : "  " + work.cost() + "e")
                            .formatted(built(work) ? Formatting.GREEN : Formatting.GOLD))
                    .append(Text.literal("  " + work.blurb()).formatted(Formatting.DARK_GRAY)),
                    false);
        }
    }

    /**
     * The last few days of the books, in chat.
     *
     * The city has written a row a day to trapcraft-city.csv since the day it
     * was founded and nobody could read it without leaving the game and
     * finding the container's filesystem. A number nobody can see is a number
     * nobody tunes.
     *
     * Columns are looked up BY NAME out of the header rather than by index.
     * The file has already grown twice and rows written before each growth are
     * short; reading by position would quietly start printing the wash figure
     * under the heading for tax the next time somebody appends one.
     */
    private static void history(ServerPlayerEntity who) {
        if (logFile == null || !Files.exists(logFile)) {
            who.sendMessage(Text.literal("Nie ma jeszcze rachunków. Miasto zapisuje jeden wiersz dziennie.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        List<String> rows;
        try {
            rows = Files.readAllLines(logFile);
        } catch (Exception failure) {
            who.sendMessage(Text.literal("Nie udało się odczytać rachunków: " + failure)
                    .formatted(Formatting.RED), false);
            return;
        }
        if (rows.size() < 2) {
            who.sendMessage(Text.literal("Rachunki mają jeden dzień. Wróć jutro.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        List<String> head = List.of(rows.get(0).split(",", -1));
        List<String> recent = rows.subList(Math.max(1, rows.size() - HISTORY_DAYS), rows.size());

        long peak = 1;
        for (String row : recent) {
            peak = Math.max(peak, Math.abs(number(head, row, "town_purse")));
        }

        who.sendMessage(Text.literal("Rachunki").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("   ostatnie " + recent.size() + " dni")
                        .formatted(Formatting.DARK_GRAY)), false);
        who.sendMessage(Text.literal(String.format("  %-5s %-6s %-8s %-8s %-7s %s",
                        "dzień", "ludzi", "skarb", "kasa", "podatki", "indeks"))
                .formatted(Formatting.DARK_GRAY), false);
        for (String row : recent) {
            long purse = number(head, row, "town_purse");
            int bars = (int) Math.round(purse * 10.0 / peak);
            who.sendMessage(Text.literal(String.format("  %-5s %-6s %-8s %-8s %-7s %-5s ",
                            text(head, row, "day"), text(head, row, "population"),
                            text(head, row, "purse"), text(head, row, "town_purse"),
                            text(head, row, "income_tax"), text(head, row, "market_index")))
                    .formatted(Formatting.WHITE)
                    .append(Text.literal("█".repeat(Math.max(0, Math.min(10, bars))))
                            .formatted(Formatting.GREEN)), false);
        }
        who.sendMessage(Text.literal("  Słupki to kasa mieszkańców. Pusto = nikt nie kupuje.")
                .formatted(Formatting.DARK_GRAY), false);
        who.sendMessage(Text.literal("  podatki i pensje to sumy narastające, nie dzienne.")
                .formatted(Formatting.DARK_GRAY), false);
    }

    /** How many days {@code /city history} prints. */
    private static final int HISTORY_DAYS = 10;

    /** The column names, in the order {@link #logDay} writes their values. */
    private static String header() {
        StringBuilder out = new StringBuilder();
        out.append("day,online,population,houses,housed,tenants,avg_grade,")
                .append("purse,raised_total,");
        for (Duty duty : Duty.values()) {
            out.append("rate_").append(duty.name().toLowerCase(java.util.Locale.ROOT))
                    .append(',');
        }
        for (Duty duty : Duty.values()) {
            out.append("raised_").append(duty.name().toLowerCase(java.util.Locale.ROOT))
                    .append(',');
        }
        // New columns go on the END, so rows written before them stay readable
        // as short rows rather than having every later field shift along.
        out.append("acts,works,shelves,shelf_sales,shelf_tills,")
                .append("casino_balance,casino_handle,casino_net,worst_wear,")
                .append("crew,crew_payroll,dealers,heat,market_index,supply,")
                .append("declared,undeclared,washed,owed,")
                .append("town_purse,wages,income_tax,")
                // On the END, like every column before them. A row written
                // yesterday has no ward figures and reads as "-" for them;
                // slotting these in beside the other city columns would have
                // every field after them read under the wrong heading.
                .append("wards,ward_beds,ill,ward_spend,")
                // On the END, like every column before them, for the reason
                // the ward's are: a row written yesterday has no police
                // figures and reads as "-" for them, whereas slotting these in
                // beside the other city columns would have every field after
                // them read under the wrong heading.
                .append("stations,officers,police_budget,police_paid,police_spend,")
                .append("fines,crimes,crimes_solved,crime_stolen,hardship_pct\n");
        return out.toString();
    }

    /**
     * Bring an old file's header up to date with the columns being written.
     *
     * The header is written once, when the file is created, and every column
     * added since has been invisible to anything reading by name -- which is
     * how {@code /city history} came to have a purse column that only ever
     * said "-". Rows written under the old header are shorter than the new
     * one, and that is fine and expected: reading by name returns "-" for a
     * field a given row genuinely never had.
     */
    /**
     * The last day already in the log, so a restart does not write the day
     * again.
     *
     * {@code lastLogged} lives in memory and started at -1 every boot, so
     * eight restarts in an afternoon put eight rows in for the same day --
     * and each was written on the tick after load, before the survey pass had
     * recounted households, so they all understated the population too. Both
     * of those make the history read like the town kept collapsing.
     */
    private static long lastLoggedInFile() {
        try {
            if (!Files.exists(logFile)) {
                return -1;
            }
            List<String> rows = Files.readAllLines(logFile);
            for (int at = rows.size() - 1; at > 0; at--) {
                String[] fields = rows.get(at).split(",", -1);
                if (fields.length > 0 && !fields[0].isBlank()) {
                    return Long.parseLong(fields[0].trim());
                }
            }
        } catch (Exception unreadable) {
            // A log we cannot read is a log we will simply append to.
        }
        return -1;
    }

    private static void repairHeader() {
        try {
            List<String> rows = Files.readAllLines(logFile);
            String want = header().stripTrailing();
            if (rows.isEmpty() || rows.get(0).equals(want)) {
                return;
            }
            rows.set(0, want);
            Files.writeString(logFile, String.join("\n", rows) + "\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            TrapCraft.LOGGER.info("city log header brought up to date ({} columns)",
                    want.split(",", -1).length);
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't repair the city log header: {}", failure.toString());
        }
    }

    /** One named field out of a csv row, or "-" if that row predates it. */
    private static String text(List<String> head, String row, String column) {
        int at = head.indexOf(column);
        String[] fields = row.split(",", -1);
        return at < 0 || at >= fields.length || fields[at].isBlank() ? "-" : fields[at];
    }

    private static long number(List<String> head, String row, String column) {
        try {
            return Long.parseLong(text(head, row, column).trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-city.txt");
        logFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-city.csv");
        lastLogged = lastLoggedInFile();
        // At load, not only when a day rolls over. logDay fires once an
        // in-game day, so leaving the repair in there meant /city history
        // printed "-" for the newest columns until tomorrow -- on a feature
        // whose entire job is showing you those columns.
        repairHeader();
        ACTS.clear();
        PASSED.clear();
        RATES.clear();
        TAKEN.clear();
        treasury = 0;
        vaultAt = null;
        vaultWorld = null;
        lastBudget = -1;
        orderFilled = -1;
        BUILT.clear();
        for (Duty duty : Duty.values()) {
            RATES.put(duty, duty.start());
        }
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                switch (parts[0]) {
                    case "purse" -> treasury = Long.parseLong(parts[1]);
                    case "budget" -> lastBudget = Long.parseLong(parts[1]);
                    case "order" -> orderFilled = Long.parseLong(parts[1]);
                    case "vault" -> {
                        if (parts.length >= 5) {
                            vaultWorld = parts[1];
                            vaultAt = new BlockPos(Integer.parseInt(parts[2]),
                                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                        }
                    }
                    case "act" -> {
                        try {
                            ACTS.add(Act.valueOf(parts[1]));
                            PASSED.put(Act.valueOf(parts[1]),
                                    parts.length >= 3 ? Long.parseLong(parts[2]) : 0L);
                        } catch (IllegalArgumentException gone) {
                            // An act this version no longer has. Repealed by
                            // the only authority that outranks the council.
                        }
                    }
                    case "built" -> {
                        try {
                            // Length-guarded: a line written before works had
                            // levels is one that was built once.
                            BUILT.put(Work.valueOf(parts[1]),
                                    parts.length > 2 ? Integer.parseInt(parts[2]) : 1);
                        } catch (IllegalArgumentException gone) {
                            // A public work this version no longer has. The
                            // city keeps the money it spent and forgets it.
                        }
                    }
                    case "duty" -> {
                        if (parts.length >= 4) {
                            Duty duty = Duty.valueOf(parts[1]);
                            RATES.put(duty, Math.max(duty.floor(),
                                    Math.min(duty.ceiling(), Integer.parseInt(parts[2]))));
                            TAKEN.put(duty, Integer.parseInt(parts[3]));
                        }
                    }
                    default -> {
                    }
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the city books: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            out.append("purse ").append(treasury).append('\n');
            out.append("budget ").append(lastBudget).append('\n');
            out.append("order ").append(orderFilled).append('\n');
            if (founded()) {
                out.append("vault ").append(vaultWorld).append(' ')
                        .append(vaultAt.getX()).append(' ').append(vaultAt.getY())
                        .append(' ').append(vaultAt.getZ()).append('\n');
            }
            for (Work work : BUILT.keySet()) {
                out.append("built ").append(work.name()).append(' ')
                        .append(BUILT.get(work)).append('\n');
            }
            for (Act act : ACTS) {
                out.append("act ").append(act.name()).append(' ')
                        .append(passedOn(act)).append('\n');
            }
            for (Duty duty : Duty.values()) {
                out.append("duty ").append(duty.name()).append(' ')
                        .append(rateOf(duty)).append(' ').append(takenBy(duty)).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the city books: {}", failure.toString());
        }
    }
}
