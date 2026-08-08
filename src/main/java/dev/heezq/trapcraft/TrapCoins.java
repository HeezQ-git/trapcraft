package dev.heezq.trapcraft;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The coin market: the exchange's other window.
 *
 * The term deposits next door are a bank -- you hand money over for a fixed
 * number of days and the index decides what comes back. This is the opposite
 * of a bank. You buy units of something at whatever it costs this minute, you
 * can sell them a minute later or never, and one of them will eventually go to
 * nothing and take your money with it.
 *
 * Prices are a pure function of the market beat, so there is no price file to
 * corrupt, every player sees the same chart, and history can be read backwards
 * for free -- which is where the sparklines come from. See
 * {@link TrapMath#coinPrice}.
 *
 * Holdings ARE saved, in their own file, and the old term-deposit book is left
 * completely alone: it holds emeralds people have already handed over, and the
 * safest migration is no migration.
 */
public final class TrapCoins {

    /** How wild a coin is, and how likely it is to simply die. */
    public enum Risk {
        STEADY("Steady", Formatting.AQUA, 0.10f, 0.00f),
        SWINGY("Swingy", Formatting.GOLD, 0.30f, 0.08f),
        DEGENERATE("Degenerate", Formatting.RED, 0.65f, 0.35f);

        public final String label;
        public final Formatting colour;
        public final float volatility;
        /** Odds this coin rugs inside any one era. */
        public final float rugChance;

        Risk(String label, Formatting colour, float volatility, float rugChance) {
            this.label = label;
            this.colour = colour;
            this.volatility = volatility;
            this.rugChance = rugChance;
        }
    }

    /** One listing. */
    public record Coin(String id, String ticker, String name, Risk risk, float base, Item icon) {
        public float price(long beat) {
            return TrapMath.coinPrice(beat, id, base, risk.volatility, risk.rugChance);
        }

        public boolean dead(long beat) {
            return TrapMath.coinDead(beat, id, risk.rugChance);
        }
    }

    /**
     * The board.
     *
     * Deliberately a spread rather than a ladder: the steady one is dull and
     * safe, the middle two are where most of the play is, and the bottom two
     * exist so somebody can lose everything in an afternoon and tell the story
     * afterwards. A market where the best coin is obvious is a savings account
     * with extra clicking.
     */
    public static final List<Coin> BOARD = List.of(
            new Coin("grind", "GRND", "Grindcoin", Risk.STEADY, 40.0f, Items.IRON_INGOT),
            new Coin("ledger", "LDGR", "Ledger Note", Risk.STEADY, 120.0f, Items.PAPER),
            new Coin("loud", "LOUD", "Loudcoin", Risk.SWINGY, 65.0f, Items.GOLD_INGOT),
            new Coin("kilo", "KILO", "Kilo Token", Risk.SWINGY, 180.0f, Items.DIAMOND),
            new Coin("moon", "MOON", "Moonshot", Risk.DEGENERATE, 12.0f, Items.NETHER_STAR),
            new Coin("rug", "RUG", "Deffo Legit", Risk.DEGENERATE, 3.0f, Items.BROWN_CARPET));

    /** One player's stake in one coin. Averaged, so buying twice reads sanely. */
    public record Holding(String coin, int units, int spent, long lockedUntil) {
        public boolean locked(long beat) {
            return beat < lockedUntil;
        }

        /** What each unit cost on average, for the profit line. */
        public float average() {
            return units <= 0 ? 0.0f : spent / (float) units;
        }
    }

    /** Beats a locked holding stays locked. About two hours. */
    public static final int LOCK_BEATS = 240;
    /** What locking pays extra when you finally sell. */
    public static final float LOCK_BONUS = 0.15f;

    private static final Map<UUID, List<Holding>> BOOK = new HashMap<>();
    private static Path saveFile;

    private TrapCoins() {
    }

    public static Coin byId(String id) {
        for (Coin coin : BOARD) {
            if (coin.id().equals(id)) {
                return coin;
            }
        }
        return null;
    }

    public static List<Holding> of(ServerPlayerEntity player) {
        return BOOK.getOrDefault(player.getUuid(), List.of());
    }

    public static Holding held(ServerPlayerEntity player, Coin coin) {
        for (Holding holding : of(player)) {
            if (holding.coin().equals(coin.id())) {
                return holding;
            }
        }
        return null;
    }

    /** Everything this player's coins are worth if sold right now. */
    public static int portfolio(ServerPlayerEntity player, long beat) {
        int total = 0;
        for (Holding holding : of(player)) {
            Coin coin = byId(holding.coin());
            if (coin != null) {
                total += TrapMath.coinSellValue(coin.price(beat), holding.units());
            }
        }
        return total;
    }

    /**
     * Buy units. Averages into an existing holding rather than stacking rows.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String buy(ServerPlayerEntity player, Coin coin, int units, boolean lock) {
        long beat = TrapMarket.beat();
        if (units <= 0) {
            return null;
        }
        int cost = TrapMath.coinBuyCost(coin.price(beat), units);
        if (TrapMarket.wealthOf(player) < cost) {
            return "That's " + cost + "e and you haven't got it.";
        }
        TrapMarket.take(player, cost);

        List<Holding> mine = BOOK.computeIfAbsent(player.getUuid(), key -> new ArrayList<>());
        Holding existing = held(player, coin);
        long until = lock ? beat + LOCK_BEATS : 0L;
        if (existing != null) {
            mine.remove(existing);
            // Locking any part of a holding locks the lot, and the later of the
            // two dates wins -- otherwise buying one unlocked unit would free
            // a locked stack and the bonus would be a formality.
            until = Math.max(until, existing.lockedUntil());
            mine.add(new Holding(coin.id(), existing.units() + units,
                    existing.spent() + cost, until));
        } else {
            mine.add(new Holding(coin.id(), units, cost, until));
        }
        save();
        return null;
    }

    /**
     * Sell the lot.
     *
     * @return what was paid, or -1 if it is locked, -2 if there is nothing there
     */
    public static int sell(ServerPlayerEntity player, Coin coin) {
        long beat = TrapMarket.beat();
        Holding holding = held(player, coin);
        if (holding == null) {
            return -2;
        }
        if (holding.locked(beat)) {
            return -1;
        }
        int paid = TrapMath.coinSellValue(coin.price(beat), holding.units());
        if (holding.lockedUntil() > 0) {
            // It was locked and the term is up, so the patience gets paid for.
            paid = Math.round(paid * (1.0f + LOCK_BONUS));
        }
        BOOK.get(player.getUuid()).remove(holding);
        save();
        if (paid > 0) {
            TrapMarket.pay(player, paid);
        }
        return paid;
    }

    /**
     * A little chart, in eight block characters.
     *
     * Scaled to the window's own high and low rather than to the coin's base,
     * so a flat hour looks flat and a crash looks like a cliff instead of both
     * looking like a line near the bottom.
     */
    public static String sparkline(Coin coin, long beat, int span, int points) {
        String blocks = "\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588";
        float[] seen = new float[points];
        float low = Float.MAX_VALUE;
        float high = 0.0f;
        for (int i = 0; i < points; i++) {
            long at = Math.max(0, beat - span + (long) span * i / points);
            seen[i] = coin.price(at);
            low = Math.min(low, seen[i]);
            high = Math.max(high, seen[i]);
        }
        StringBuilder out = new StringBuilder();
        float range = Math.max(0.0001f, high - low);
        for (float value : seen) {
            int step = Math.round((value - low) / range * (blocks.length() - 1));
            out.append(blocks.charAt(Math.max(0, Math.min(blocks.length() - 1, step))));
        }
        return out.toString();
    }

    // --- persistence ----------------------------------------------------------

    public static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-coins.txt");
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
                        .add(new Holding(parts[1], Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3]), Long.parseLong(parts[4])));
            }
        } catch (Exception failure) {
            // Loud, not silent: this is somebody's money.
            TrapCraft.LOGGER.error("couldn't read coin holdings -- they may be lost: {}",
                    failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            BOOK.forEach((player, holdings) -> holdings.forEach(holding ->
                    lines.add(player + " " + holding.coin() + " " + holding.units()
                            + " " + holding.spent() + " " + holding.lockedUntil())));
            Files.write(saveFile, lines);
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't save coin holdings: {}", failure.toString());
        }
    }
}
