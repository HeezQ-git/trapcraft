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
        ESSENTIALS("Essentials", "Food, seeds, and what grows", 5, 2, 12),
        MATERIALS("Materials", "Building blocks and timber", 8, 4, 16),
        LUXURY("Luxury", "Decoration, tools, enchantments, the rare stuff", 12, 6, 22),
        INCOME("Income", "Anything you are paid -- the counter, contracts, the pawn desk",
                10, 4, 20),
        GAMING("Gaming", "Every stake laid on a casino floor", 6, 2, 14),
        RENT("Rent", "What a tenant pays their landlord", 6, 2, 14);

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
        WATCH("The Watch", "Patrols come round half as often", 4000),
        ROADS("Paved Roads", "Houses near the vault grade one higher", 6000),
        LAMPS("Street Lamps", "Townspeople go shopping far more", 3000),
        EXCHANGE("The Exchange", "The counter pays better for everything", 8000),
        CLINIC("The Clinic", "Tenants put up with more before they leave", 5000),
        TRAM("The Tramway", "More people out shopping at once", 7000),
        SCHOOL("The School", "Everybody in the city is paid better", 12000);

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

    private static final java.util.Set<Work> BUILT = java.util.EnumSet.noneOf(Work.class);

    public static boolean built(Work work) {
        return BUILT.contains(work);
    }

    /** @return why it didn't happen, or null if it did */
    public static String build(ServerPlayerEntity who, Work work) {
        if (!founded()) {
            return "There's no city to build it in.";
        }
        if (BUILT.contains(work)) {
            return "Already built.";
        }
        if (treasury < work.cost()) {
            return work.display() + " costs " + work.cost() + "e and the purse holds "
                    + treasury + "e.";
        }
        treasury -= work.cost();
        BUILT.add(work);
        save();
        announce(who.getServer(), Text.literal(work.display().toUpperCase(java.util.Locale.ROOT))
                .formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("\n  " + who.getGameProfile().getName() + " spent "
                        + work.cost() + "e of the city's money. " + work.blurb() + ".")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("\n  " + treasury + "e left in the purse.")
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
        LEVY("The Levy", "Every rate up while the purse is empty", 3),
        CRACKDOWN("The Crackdown", "Patrols come round far more often", 0),
        STANDARDS("Housing Standards", "A grade one is no longer fit to let", 0),
        DRIVE("The Revenue Drive", "The office takes a harder share of what it finds", 0);

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
                long day = server.getOverworld().getTimeOfDay() / 24000L;
                if (day != lastLogged) {
                    lastLogged = day;
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
            return "The city already has a vault, at " + vaultAt.getX() + " "
                    + vaultAt.getY() + " " + vaultAt.getZ() + ".";
        }
        boolean first = !founded();
        vaultWorld = dimension;
        vaultAt = pos.toImmutable();
        save();
        if (first) {
            announce(who.getServer(), Text.literal("THE CITY IS OPEN")
                    .formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal("\n  " + who.getGameProfile().getName()
                            + " put the vault up. Trade is taxed from now on, and houses "
                            + "can be registered.").formatted(Formatting.GRAY))
                    .append(Text.literal("\n  /city").formatted(Formatting.GREEN))
                    .append(Text.literal("  for the books.").formatted(Formatting.DARK_GRAY)));
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
        announce(world.getServer(), Text.literal("The city vault is down. ")
                .formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal("Nothing is being taxed and no house can be "
                        + "registered until one is stood up again. The "
                        + treasury + "e is safe.").formatted(Formatting.GRAY)));
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

    /** Anybody may spend it, and everybody hears about it. */
    public static String withdraw(ServerPlayerEntity who, int amount) {
        if (!founded()) {
            return "There's no vault to take it from.";
        }
        int wanted = (int) Math.min(amount, Math.min(treasury, Integer.MAX_VALUE));
        if (wanted <= 0) {
            return "The purse is empty.";
        }
        treasury -= wanted;
        TrapMarket.handOver(who, wanted);
        TrapLedger.record(who, TrapLedger.Source.TAX, wanted);
        save();
        announce(who.getServer(), Text.literal(who.getGameProfile().getName())
                .formatted(Formatting.WHITE)
                .append(Text.literal(" took ").formatted(Formatting.GRAY))
                .append(Text.literal(wanted + "e").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" out of the city purse. " + treasury + "e left.")
                        .formatted(Formatting.GRAY)));
        return null;
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
        long day = overworld.getTimeOfDay() / 24000L;
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
            why = "The purse is empty. Rates up.";
            move = 2;
        } else if (treasury > FLUSH) {
            why = "The purse is full. Rates down.";
            move = -1;
        } else {
            why = "The books are balanced. Small adjustments.";
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
                    .append(now == was ? "" : now > was ? "  (up from " + was + "%)"
                            : "  (down from " + was + "%)");
        }
        save();
        announce(server, Text.literal("THE BUDGET").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("\n  " + why).formatted(Formatting.GRAY))
                .append(Text.literal(table.toString()).formatted(Formatting.WHITE))
                .append(Text.literal("\n  /city").formatted(Formatting.GREEN))
                .append(Text.literal("  for the whole table.").formatted(Formatting.DARK_GRAY)));
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
                announce(server, Text.literal("PASSED  ").formatted(Formatting.RED,
                                Formatting.BOLD)
                        .append(Text.literal(act.display()).formatted(Formatting.WHITE,
                                Formatting.BOLD))
                        .append(Text.literal("\n  " + act.blurb() + ".")
                                .formatted(Formatting.GRAY))
                        .append(Text.literal("\n  /law").formatted(Formatting.GREEN))
                        .append(Text.literal("  for the constitution.")
                                .formatted(Formatting.DARK_GRAY)));
            } else {
                ACTS.remove(act);
                PASSED.remove(act);
                announce(server, Text.literal("REPEALED  ").formatted(Formatting.GREEN,
                                Formatting.BOLD)
                        .append(Text.literal(act.display()).formatted(Formatting.WHITE))
                        .append(Text.literal(". The reason for it has gone.")
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
            for (Work work : BUILT) {
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
                    .append(TrapPayroll.incomeTax()).append('\n');
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
            who.sendMessage(Text.literal("There's no city yet. ").formatted(Formatting.GRAY)
                    .append(Text.literal("Craft a city vault and put it down.")
                            .formatted(Formatting.WHITE)), false);
            return;
        }
        who.sendMessage(Text.literal("The City").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("   purse ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(treasury + "e").formatted(Formatting.GREEN,
                        Formatting.BOLD))
                .append(Text.literal("   vault at " + vaultAt.getX() + " " + vaultAt.getY()
                        + " " + vaultAt.getZ()).formatted(Formatting.DARK_GRAY)), false);
        who.sendMessage(Text.literal("  The town has ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(TrapPayroll.purse() + "e").formatted(Formatting.AQUA,
                        Formatting.BOLD))
                .append(Text.literal(" of its own wages left to spend. ")
                        .formatted(Formatting.DARK_GRAY))
                .append(Text.literal(TrapPayroll.purse() > 0 ? "" : "Nobody's shopping.")
                        .formatted(Formatting.RED)), false);
        for (Duty duty : Duty.values()) {
            who.sendMessage(Text.literal("  " + duty.display()).formatted(Formatting.WHITE)
                    .append(Text.literal("  " + rateOf(duty) + "%")
                            .formatted(Formatting.GOLD, Formatting.BOLD))
                    .append(Text.literal("  " + duty.blurb()).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("  raised " + takenBy(duty) + "e")
                            .formatted(Formatting.DARK_GRAY)), false);
        }
        who.sendMessage(Text.literal("  Nothing sold to customers or dealers is taxed.")
                .formatted(Formatting.DARK_GRAY), false);
        for (Work work : Work.values()) {
            who.sendMessage(Text.literal("  " + work.display())
                    .formatted(built(work) ? Formatting.GREEN : Formatting.DARK_GRAY)
                    .append(Text.literal(built(work) ? "  built" : "  " + work.cost() + "e")
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
            who.sendMessage(Text.literal("No books yet. The city writes a row a day.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        List<String> rows;
        try {
            rows = Files.readAllLines(logFile);
        } catch (Exception failure) {
            who.sendMessage(Text.literal("Couldn't read the books: " + failure)
                    .formatted(Formatting.RED), false);
            return;
        }
        if (rows.size() < 2) {
            who.sendMessage(Text.literal("The books have one day in them. Come back tomorrow.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        List<String> head = List.of(rows.get(0).split(",", -1));
        List<String> recent = rows.subList(Math.max(1, rows.size() - HISTORY_DAYS), rows.size());

        long peak = 1;
        for (String row : recent) {
            peak = Math.max(peak, Math.abs(number(head, row, "town_purse")));
        }

        who.sendMessage(Text.literal("The Books").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("   last " + recent.size() + " days")
                        .formatted(Formatting.DARK_GRAY)), false);
        who.sendMessage(Text.literal(String.format("  %-5s %-6s %-8s %-8s %-7s %s",
                        "day", "people", "vault", "purse", "tax", "index"))
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
        who.sendMessage(Text.literal("  Bars are the town purse. Empty means nobody's shopping.")
                .formatted(Formatting.DARK_GRAY), false);
        who.sendMessage(Text.literal("  tax and wages are running totals, not per-day.")
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
                .append("town_purse,wages,income_tax\n");
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
                            BUILT.add(Work.valueOf(parts[1]));
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
            if (founded()) {
                out.append("vault ").append(vaultWorld).append(' ')
                        .append(vaultAt.getX()).append(' ').append(vaultAt.getY())
                        .append(' ').append(vaultAt.getZ()).append('\n');
            }
            for (Work work : BUILT) {
                out.append("built ").append(work.name()).append('\n');
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
