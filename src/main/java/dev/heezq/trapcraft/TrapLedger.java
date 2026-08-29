package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where everybody's money actually came from.
 *
 * Four people play this server doing four different jobs, and until now the
 * only way to answer "is the casino out-earning the farm" was for somebody to
 * feel strongly about it. Coca turned out to be paying six times what weed
 * did per plant-minute and nobody noticed for weeks, because there was nothing
 * to notice it with. This is the instrument.
 *
 * <h2>Why this is not a wrapper around take/pay</h2>
 *
 * {@link TrapMarket#take} and {@link TrapMarket#pay} are the choke points every
 * emerald goes through, which makes them the obvious place to hook -- and the
 * wrong one. They are called from twenty files and none of them says WHY the
 * money is moving, so the hook would have to be told, which means threading a
 * reason through every call site anyway. Same diff, worse abstraction, and a
 * pile of rows for wallet withdrawals and vault deposits that are not income
 * at all.
 *
 * So the calls are explicit and there are about fifteen of them: the places
 * that represent somebody actually earning or being charged.
 *
 * <h2>What is deliberately NOT recorded</h2>
 *
 * Moving your own money between your pocket, your wallet and your own casino
 * vault. That is not income, it is carrying, and logging it would double-count
 * every emerald that passes through a wallet on its way somewhere real.
 */
public final class TrapLedger {

    /**
     * Where a payment came from, and whether the revenue office hears about it.
     *
     * The declared flag is the whole reason this enum exists rather than a
     * string. Step 6's audit compares wealth growth against declared income,
     * and "which of these is black market" has to be one fact in one place --
     * a second opinion about whether coca counts would be an exploit rather
     * than a bug.
     */
    public enum Source {
        WEED(false, "weed"),
        COCA(false, "coca"),
        DOPE(false, "dope"),
        FOOD(true, "food/farm"),
        MARKET(true, "market"),
        STALL(true, "stall"),
        CASINO(true, "casino"),
        CONTRACT(true, "contracts"),
        BOUNTY(true, "nagrody"),
        CREW(true, "pensje ekipy"),
        RENT(true, "rent"),
        TAX(true, "tax"),
        INVEST(true, "investments"),
        SCRAP(true, "pawn counter");

        private final boolean declared;
        private final String label;

        Source(boolean declared, String label) {
            this.declared = declared;
            this.label = label;
        }

        /** Whether the revenue office gets told about it. */
        public boolean declared() {
            return declared;
        }

        public String label() {
            return label;
        }
    }

    private TrapLedger() {
    }

    // --- the arithmetic ------------------------------------------------------
    //
    // Static and Minecraft-free so the plain JUnit suite can check it, same
    // rule TrapMath follows. Everything below this line that touches a world
    // is a thin shell over these four.

    /** Add one payment to a running book. */
    public static void tally(Map<String, Map<Source, Integer>> book, String who,
                             Source source, int delta) {
        book.computeIfAbsent(who, ignored -> new EnumMap<>(Source.class))
                .merge(source, delta, Integer::sum);
    }

    /** Everything in and out, added up. */
    public static int net(Map<Source, Integer> row) {
        int total = 0;
        for (int amount : row.values()) {
            total += amount;
        }
        return total;
    }

    /**
     * Declared INCOME, which is not the same as the declared total.
     *
     * Only money coming in counts. Somebody who spent four hundred emeralds at
     * the shop has not thereby declared four hundred emeralds of earnings, and
     * letting spending net off against income would let anybody launder by
     * going shopping.
     */
    public static int declaredOf(Map<Source, Integer> row) {
        return sum(row, true);
    }

    public static int undeclaredOf(Map<Source, Integer> row) {
        return sum(row, false);
    }

    private static int sum(Map<Source, Integer> row, boolean declared) {
        int total = 0;
        for (var entry : row.entrySet()) {
            if (entry.getKey().declared() == declared && entry.getValue() > 0) {
                total += entry.getValue();
            }
        }
        return total;
    }

    // --- the books -----------------------------------------------------------

    /** Today's book, by player name. Flushed and cleared when the day turns. */
    private static final Map<String, Map<Source, Integer>> TODAY = new LinkedHashMap<>();
    private static long day = -1;
    private static Path rows;
    private static Path summary;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            rows = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-ledger.csv");
            summary = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-earnings.txt");
            day = TrapMarket.today(server);
            try {
                if (!Files.exists(rows)) {
                    Files.writeString(rows, "day,player,source,delta\n");
                }
            } catch (Exception failure) {
                TrapCraft.LOGGER.warn("couldn't open the ledger: {}", failure.toString());
            }
        });
        // Once a second is plenty to notice a day rolling over, and it keeps
        // this off the hot path entirely.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0 || rows == null) {
                return;
            }
            long now = TrapMarket.today(server);
            if (now != day) {
                flush(day);
                day = now;
                TODAY.clear();
            }
        });
        registerCommand();
    }

    /**
     * One payment, from somebody, for a reason.
     *
     * @param delta what it did to their pocket -- negative for money going out
     */
    /**
     * Today's book for one player, or an empty row.
     *
     * The revenue office reads this. Handed out as an unmodifiable view
     * because the ledger is the only thing allowed to write to it -- an audit
     * that could edit the evidence is not an audit.
     */
    public static Map<Source, Integer> today(String who) {
        Map<Source, Integer> row = TODAY.get(who);
        return row == null ? Map.of() : java.util.Collections.unmodifiableMap(row);
    }

    public static void record(ServerPlayerEntity who, Source source, int delta) {
        if (who == null || delta == 0 || rows == null) {
            return;
        }
        String name = who.getGameProfile().getName();
        tally(TODAY, name, source, delta);
        try {
            Files.writeString(rows, day + "," + name + "," + source.name() + "," + delta + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) {
            // A ledger that cannot write is a lost measurement, not a lost
            // game. Warn once a day rather than once a sale.
            if (day != warnedOn) {
                warnedOn = day;
                TrapCraft.LOGGER.warn("couldn't append to the ledger: {}", failure.toString());
            }
        }
    }

    private static long warnedOn = -1;

    // --- the readout ---------------------------------------------------------

    /**
     * The file somebody actually reads.
     *
     * A CSV is what you query; this is what you glance at over a cup of tea to
     * see whether one job is quietly four times another.
     */
    private static void flush(long which) {
        if (summary == null || TODAY.isEmpty()) {
            return;
        }
        try {
            Files.writeString(summary, render(which), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't write the earnings summary: {}", failure.toString());
        }
        TrapCraft.LOGGER.info("earnings day {}: {}", which, oneLine());
    }

    /** Day's table as plain text, players across, sources down. */
    static String render(long which) {
        StringBuilder out = new StringBuilder("\nday ").append(which).append('\n');
        var names = TODAY.keySet().stream().sorted().toList();
        out.append("  ").append(" ".repeat(14));
        for (String name : names) {
            out.append(String.format("%12s", name));
        }
        out.append('\n');
        for (Source source : Source.values()) {
            boolean any = names.stream().anyMatch(
                    name -> TODAY.get(name).getOrDefault(source, 0) != 0);
            if (!any) {
                continue;
            }
            out.append("  ").append(String.format("%-14s", source.label()));
            for (String name : names) {
                out.append(String.format("%12d", TODAY.get(name).getOrDefault(source, 0)));
            }
            out.append('\n');
        }
        out.append("  ").append("-".repeat(14 + 12 * names.size())).append('\n');
        out.append("  ").append(String.format("%-14s", "net"));
        for (String name : names) {
            out.append(String.format("%12d", net(TODAY.get(name))));
        }
        out.append('\n');
        out.append("  ").append(String.format("%-14s", "w tym na czarno"));
        for (String name : names) {
            out.append(String.format("%12d", undeclaredOf(TODAY.get(name))));
        }
        return out.append('\n').toString();
    }

    private static String oneLine() {
        StringBuilder out = new StringBuilder();
        TODAY.forEach((name, row) -> out.append(out.isEmpty() ? "" : ", ")
                .append(name).append(' ').append(net(row)).append('e'));
        return out.toString();
    }

    /**
     * /earnings -- today's table, without alt-tabbing to a file.
     *
     * Ops only, and it shows EVERYBODY. The point of the thing is comparing
     * one job against another, which cannot be done one player at a time.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("earnings")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(context -> {
                                    report(context.getSource());
                                    return 1;
                                })));
    }

    private static void report(net.minecraft.server.command.ServerCommandSource source) {
        if (TODAY.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Dzisiaj nikt nic nie zarobił.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        MinecraftServer server = source.getServer();
        Text header = Text.literal("Zarobki, dzień " + TrapMarket.today(server))
                .formatted(Formatting.GOLD, Formatting.BOLD);
        source.sendFeedback(() -> header, false);
        TODAY.forEach((name, row) -> {
            StringBuilder detail = new StringBuilder();
            row.forEach((src, amount) -> {
                if (amount != 0) {
                    detail.append(detail.isEmpty() ? "" : "  ")
                            .append(src.label()).append(' ').append(amount);
                }
            });
            source.sendFeedback(() -> Text.literal("  " + name + "  ")
                    .formatted(Formatting.WHITE)
                    .append(Text.literal(net(row) + "e").formatted(
                            net(row) >= 0 ? Formatting.GREEN : Formatting.RED))
                    .append(Text.literal("  (" + undeclaredOf(row) + "e na czarno)")
                            .formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("\n    " + detail).formatted(Formatting.DARK_GRAY)),
                    false);
        });
    }
}
