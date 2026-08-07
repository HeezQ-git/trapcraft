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
