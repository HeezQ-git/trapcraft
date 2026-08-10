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
        return Math.max(1, Math.round(amount * rateOf(duty) / 100.0f));
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
                                })));
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
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-city.txt");
        RATES.clear();
        TAKEN.clear();
        treasury = 0;
        vaultAt = null;
        vaultWorld = null;
        lastBudget = -1;
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
