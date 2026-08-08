package dev.heezq.trapcraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three formulas worth checking, kept away from everything else.
 *
 * Nothing here imports Minecraft, and that is the entire point: it means
 * `gradlew test` is a plain one-second JUnit run instead of needing a game
 * environment on the test classpath. Anything in this class that starts
 * wanting a World or an ItemStack belongs in the caller, not here.
 *
 * The callers pass their own constants in (tier counts, grade indices) rather
 * than this class reaching for TrapHeat or Quality -- same reason.
 */
public final class TrapMath {
    /** Nobody gets paid more than this for one job, whatever the maths says. */
    public static final int PAYOUT_CEILING = 256;

    /** Pressure added by standing in the dark, and by it being night. */
    public static final float DARK = 0.20f;
    public static final float NIGHT = 0.10f;
    /** Max of (heat 1.0 + dark + night), used to normalise the sum to 0..1. */
    public static final float WORLD_MAX = 1.0f + DARK + NIGHT;
    /** How much each level of Baked/Wired scales the situation. */
    public static final float HIGH_STEP = 0.35f;
    /** What another player within range multiplies your pressure by. */
    public static final float COMPANY = 0.55f;

    private TrapMath() {
    }

    /**
     * How hard the world is pressing on you right now, 0..1.
     *
     * Heat is the dominant term on purpose: this is a mechanic about running a
     * grow op, not about being high. Being high MULTIPLIES what is already
     * there rather than adding to it, so a stoned player with no heat standing
     * next to a friend stays calm. That is what makes the coupling readable
     * instead of feeling like random noise.
     *
     * Company subtracts. Safety in numbers is the counterplay, and on a server
     * played by friends it is the one that actually gets used.
     *
     * @param heatTier      current tier, 0..heatTierCount
     * @param heatTierCount how many tiers exist (TrapHeat.THRESHOLDS.length)
     * @param highAmplifier Baked/Wired amplifier, 0 when sober
     * @param light         block light at the player, 0..15
     */
    public static float pressure(int heatTier, int heatTierCount, int highAmplifier,
                                 int light, boolean night, boolean alone) {
        float base = heatTierCount <= 0 ? 0.0f : heatTier / (float) heatTierCount;
        float dark = light < 7 ? DARK : 0.0f;
        float nocturnal = night ? NIGHT : 0.0f;

        // Normalised so a maxed-out situation is exactly 1.0 rather than 1.3.
        float world = clamp01((base + dark + nocturnal) / WORLD_MAX);
        // Multiplicative, so no heat still means no paranoia however high you
        // are. Clamped here because the amplifier can push it past 1.
        float withHigh = clamp01(world * (1.0f + highAmplifier * HIGH_STEP));

        // Company is applied LAST, after the clamp, and that ordering is the
        // whole mechanic: fold it into the sum and a maxed-out player saturates
        // at 1.0 whether or not anyone is stood next to them, so the one piece
        // of counterplay silently stops working exactly when it matters most.
        return withHigh * (alone ? 1.0f : COMPANY);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    /**
     * What a contract pays, in emeralds.
     *
     * Heat raising the payout is the whole reason the delivery loop is
     * interesting: the moment you are worth robbing is the moment the work
     * starts paying. Rep is a gentler curve than heat so that a new player
     * taking a hot job still out-earns a veteran taking a cold one.
     *
     * @param gradeIndex quality demanded, higher is better (Quality.index())
     * @param rep        reputation carried on the phone
     */
    public static int payout(int distanceBlocks, int quantity, int gradeIndex,
                             int heatTier, int rep) {
        int base = Math.max(0, quantity) * (2 + Math.max(0, gradeIndex));
        int distance = Math.max(0, distanceBlocks) / 50;
        float heat = 1.0f + Math.max(0, heatTier) * 0.15f;
        float standing = 1.0f + Math.max(0, rep) * 0.02f;

        int total = Math.round((base + distance) * heat * standing);
        return Math.min(PAYOUT_CEILING, Math.max(0, total));
    }

    /**
     * Move a meter toward where the situation says it belongs.
     *
     * The first version only fell when pressure was EXACTLY zero and rose
     * otherwise, which is a ratchet rather than a meter: any darkness at all
     * climbed you to the maximum and pinned you there, so every counterplay --
     * lights, company, sobriety -- did nothing unless it happened to zero the
     * pressure outright. Treating pressure as a target the value settles at is
     * what makes "reduce it a bit" a meaningful thing to do.
     *
     * Rise is slower than fall on purpose: dread should take a while to build
     * and let go as soon as you fix its cause.
     */
    public static float approach(float current, float target, float rise, float fall) {
        if (current < target) {
            return Math.min(target, current + rise);
        }
        return Math.max(target, current - fall);
    }

    // --- the market -----------------------------------------------------------

    /** Emeralds in circulation that the catalogue is priced against. */
    public static final float MARKET_BASELINE = 2000.0f;
    /** How far the index may swing on supply alone. */
    public static final float INDEX_MIN = 0.65f;
    public static final float INDEX_MAX = 1.85f;
    /** How far a single item may drift on a given day, either way. */
    public static final float DRIFT = 0.18f;
    /** What a sale returns, as a share of the buy price. */
    public static final float SELL_RATE = 0.35f;

    /**
     * How expensive everything is right now, from the money in circulation.
     *
     * More emeralds chasing the same goods means higher prices -- the shop is
     * the only sink on this server, so without this the market would be a
     * fixed price list that gets cheaper in real terms every day somebody
     * farms a customer.
     *
     * Clamped hard at both ends. An unbounded index turns a good week into
     * prices nobody can pay, and the point is a market that breathes, not one
     * that runs away.
     */
    public static float marketIndex(float supply) {
        float raw = 1.0f + (supply - MARKET_BASELINE) / (MARKET_BASELINE * 2.0f);
        return Math.max(INDEX_MIN, Math.min(INDEX_MAX, raw));
    }

    /**
     * One item's wobble for one day, as a multiplier around 1.
     *
     * Deterministic from (day, item) so every player is quoted the same price
     * on the same day and it stays put until tomorrow -- a shop whose numbers
     * change while you look at them is a slot machine. Per ITEM rather than
     * per day, so some things are up while others are down and there is a
     * reason to read the board.
     */
    public static float dailyDrift(long day, String itemId) {
        int hash = mix((int) day * 31 + itemId.hashCode());
        // 0..1 from the low bits, then mapped onto +/- DRIFT.
        float unit = (hash >>> 8 & 0xFFFF) / (float) 0xFFFF;
        return 1.0f + (unit * 2.0f - 1.0f) * DRIFT;
    }

    /** Deterministic scramble, so neighbouring days don't produce neighbouring prices. */
    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    /** Never free, whatever the market does. */
    public static int buyPrice(int base, float index, float drift) {
        return Math.max(1, Math.round(base * index * drift));
    }

    /**
     * What the shop pays for one.
     *
     * A wide spread on purpose: the shop is a convenience, not an income. If
     * selling approached the buy price, the profitable play would be watching
     * the daily drift and arbitraging it rather than playing the game.
     */
    public static int sellPrice(int buyPrice) {
        // Zero means "the shop won't buy this". At a one-emerald price there is
        // no room for a spread -- rounding gives back exactly what you paid --
        // and a shop that buys penny goods at cost is a free money loop. Real
        // shops don't buy back a single nail either.
        if (buyPrice < 2) {
            return 0;
        }
        return Math.max(1, Math.min(buyPrice - 1, Math.round(buyPrice * SELL_RATE)));
    }

    // --- the slot machine -----------------------------------------------------

    /**
     * What a spin pays, as a multiple of the stake.
     *
     * Drawn from a table rather than simulated reels. Real machines work this
     * way too: pick the outcome, then show reels that agree with it. It means
     * the return is an exact, tunable number instead of an emergent one nobody
     * can check.
     *
     * The table returns 85% of the stake over time, so the house keeps 15 --
     * and about three spins in four pay nothing at all. That gap between "I win
     * sometimes" and "I lose money overall" is the whole design.
     */
    public static final float[] SLOT_ODDS = {0.008f, 0.030f, 0.080f, 0.140f};
    public static final float[] SLOT_PAYS = {20.0f, 8.0f, 3.0f, 1.5f};

    /** @param roll a uniform 0..1 draw */
    public static float slotPayout(float roll) {
        float floor = 0.0f;
        for (int tier = 0; tier < SLOT_ODDS.length; tier++) {
            floor += SLOT_ODDS[tier];
            if (roll < floor) {
                return SLOT_PAYS[tier];
            }
        }
        return 0.0f;
    }

    /** Long-run return per emerald staked. Below 1 or the house loses money. */
    public static float slotReturnToPlayer() {
        float total = 0.0f;
        for (int tier = 0; tier < SLOT_ODDS.length; tier++) {
            total += SLOT_ODDS[tier] * SLOT_PAYS[tier];
        }
        return total;
    }

    // --- investments ----------------------------------------------------------

    /**
     * What a matured investment returns, as a multiple of the principal.
     *
     * Three inputs. The **term** pays a flat premium for the wait. The
     * **market** decides direction: put money in while prices are low and take
     * it out when they're high and you've done well, which makes the index
     * worth watching rather than just a price tag. **Noise** stops it being an
     * arithmetic exercise, and scales with the term, so the long bet is the
     * volatile one rather than merely the slow one.
     *
     * It can lose. An investment that cannot lose is a savings account, and a
     * savings account nobody can lose is just a slower way to print money.
     */
    public static float investReturn(int days, float indexAtStart, float indexNow, float noise) {
        float termBonus = 0.03f * days;
        float marketMove = (indexNow - indexAtStart) / Math.max(0.1f, indexAtStart);
        float swing = (noise * 2.0f - 1.0f) * (0.05f + 0.02f * days);
        return Math.max(0.15f, 1.0f + termBonus + marketMove + swing);
    }

    /** One row of a ledger search: what it is, how much, and how spread out. */
    public record Tally<T>(T key, int total, int containers) {
    }

    /**
     * Merge a ledger scan into display rows, biggest pile first.
     *
     * Input is one entry per (container, item) pair -- the caller sums the
     * stacks within a single container before calling, so the container count
     * here is just how many entries carried that key. Sorting by total is what
     * makes the screen useful: the thing you have 384 of is the thing you were
     * probably looking for.
     */
    public static <T> List<Tally<T>> aggregate(List<Map.Entry<T, Integer>> found) {
        Map<T, int[]> merged = new LinkedHashMap<>();
        for (Map.Entry<T, Integer> entry : found) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            int[] cell = merged.computeIfAbsent(entry.getKey(), k -> new int[2]);
            cell[0] += entry.getValue();
            cell[1] += 1;
        }

        List<Tally<T>> rows = new ArrayList<>(merged.size());
        merged.forEach((key, cell) -> rows.add(new Tally<>(key, cell[0], cell[1])));
        rows.sort(Comparator.comparingInt((Tally<T> row) -> row.total()).reversed());
        return rows;
    }
}
