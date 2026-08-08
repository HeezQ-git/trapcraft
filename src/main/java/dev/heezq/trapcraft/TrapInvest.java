package dev.heezq.trapcraft;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Money you've put away, and what it's doing while you're not looking.
 *
 * You hand emeralds to the market for a fixed number of days. What comes back
 * depends on where the index went while you waited -- so buying in during a
 * slump and collecting during a boom is the play, and a long position taken at
 * the top can absolutely come back smaller than it went in.
 *
 * Persisted to disk, deliberately and carefully: this holds emeralds people
 * have already handed over. Losing the file loses their savings, so it is
 * written on every change rather than on shutdown, and a corrupt read is
 * logged loudly rather than swallowed.
 */
public final class TrapInvest {

    /** How long you can tie money up for, and what it's called. */
    public enum Term {
        SHORT("Overnight", 1),
        MEDIUM("Three days", 3),
        LONG("A week", 7);

        public final String label;
        public final int days;

        Term(String label, int days) {
            this.label = label;
            this.days = days;
        }
    }

    /** One holding: what went in, when, and what the market looked like then. */
    public record Position(int principal, int days, long maturesOn, float indexAtStart) {
        public boolean matured(long today) {
            return today >= maturesOn;
        }
    }

    /** The most positions one player may hold, so the screen stays readable. */
    public static final int MAX_POSITIONS = 5;

    private static final Map<UUID, List<Position>> BOOK = new HashMap<>();
    private static Path saveFile;

    private TrapInvest() {
    }

    public static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-investments.txt");
        BOOK.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 5) {
                    continue;
                }
                BOOK.computeIfAbsent(UUID.fromString(parts[0]), key -> new ArrayList<>())
                        .add(new Position(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                                Long.parseLong(parts[3]), Float.parseFloat(parts[4])));
            }
        } catch (Exception failure) {
            // Loud, not silent: this is somebody's money.
            TrapCraft.LOGGER.error("couldn't read investments -- positions may be lost: {}",
                    failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            BOOK.forEach((player, positions) -> positions.forEach(position ->
                    lines.add(player + " " + position.principal() + " " + position.days()
                            + " " + position.maturesOn() + " " + position.indexAtStart())));
            Files.write(saveFile, lines);
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't save investments: {}", failure.toString());
        }
    }

    public static List<Position> of(ServerPlayerEntity player) {
        return BOOK.getOrDefault(player.getUuid(), List.of());
    }

    public static boolean canOpen(ServerPlayerEntity player) {
        return of(player).size() < MAX_POSITIONS;
    }

    /** Take the money and write the position down. Saves immediately. */
    public static void open(ServerPlayerEntity player, int principal, Term term) {
        long matures = TrapMarket.today(player.getServer()) + term.days;
        BOOK.computeIfAbsent(player.getUuid(), key -> new ArrayList<>())
                .add(new Position(principal, term.days, matures, TrapMarket.index()));
        save();
    }

    /**
     * Cash in one matured position and hand back what it made.
     *
     * @return what was paid out, or -1 if it isn't ready
     */
    public static int collect(ServerPlayerEntity player, Position position) {
        long today = TrapMarket.today(player.getServer());
        if (!position.matured(today)) {
            return -1;
        }
        List<Position> held = BOOK.get(player.getUuid());
        if (held == null || !held.remove(position)) {
            return -1;
        }
        save();

        // Noise is drawn from the day and the position so a payout is fixed
        // once it matures -- reloading until you like the number isn't a
        // strategy anybody should have.
        float noise = TrapMath.dailyDrift(position.maturesOn(),
                player.getUuid() + ":" + position.principal()) - 1.0f;
        noise = (noise / TrapMath.DRIFT + 1.0f) / 2.0f;

        float multiplier = TrapMath.investReturn(position.days(),
                position.indexAtStart(), TrapMarket.index(), noise);
        int paid = Math.max(1, Math.round(position.principal() * multiplier));
        TrapMarket.pay(player, paid);
        return paid;
    }

    /** What a position would pay if collected now, for display. */
    public static int projected(ServerPlayerEntity player, Position position) {
        float noise = TrapMath.dailyDrift(position.maturesOn(),
                player.getUuid() + ":" + position.principal()) - 1.0f;
        noise = (noise / TrapMath.DRIFT + 1.0f) / 2.0f;
        return Math.max(1, Math.round(position.principal() * TrapMath.investReturn(
                position.days(), position.indexAtStart(), TrapMarket.index(), noise)));
    }
}
