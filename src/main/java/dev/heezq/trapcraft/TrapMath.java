package dev.heezq.trapcraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Random;
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
    /** How far a single item may drift on its own, either way. */
    public static final float DRIFT = 0.18f;
    /** Beats one wobble lasts before the next one is aimed for. */
    public static final int DRIFT_WINDOW = 8;
    /** How much of the REMAINING headroom one lot eats. See pressureAfter. */
    public static final float IMPACT = 0.02f;
    /** How far order flow may push one item, either way. */
    public static final float PRESSURE_CAP = 0.35f;
    /** What survives one beat: a half-life of about eleven. */
    public static final float PRESSURE_DECAY = 0.94f;
    /** Below this, order flow is called spent and forgotten. */
    public static final float PRESSURE_FLOOR = 0.004f;
    /** How much of a transaction lands on the money supply immediately. */
    public static final float FLOW_WEIGHT = 0.25f;
    /**
     * What a sale returns, as a share of the buy price.
     *
     * Wide enough that the shop is a convenience rather than an income, but
     * not so wide that farming a crop and selling it feels like a punishment.
     * At 35% a bundle of wheat was barely worth the walk.
     */
    public static final float SELL_RATE = 0.45f;

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
     * One item's wobble right now, as a multiplier around 1.
     *
     * Each item is always walking from one random target to the next, a window
     * of beats apart, eased so it arrives and leaves gently. That is the
     * difference between a price list and a market: come back in a minute and
     * copper has moved, and it moved in the direction it was already going.
     *
     * Deterministic from (beat, item), so two players at the same stall are
     * quoted the same number and can argue about it. Per ITEM rather than per
     * beat, so some things are climbing while others slide and the board is
     * worth reading.
     */
    public static float drift(long beat, String itemId) {
        long window = Math.floorDiv(beat, DRIFT_WINDOW);
        float phase = Math.floorMod(beat, (long) DRIFT_WINDOW) / (float) DRIFT_WINDOW;
        float from = wobble(window, itemId);
        float to = wobble(window + 1, itemId);
        // Smoothstep: no kink at the handover, so nothing lurches on the beat
        // a window rolls over.
        return from + (to - from) * (phase * phase * (3.0f - 2.0f * phase));
    }

    /**
     * One deterministic wobble around 1, from any seed and key.
     *
     * Also used for things that need a number nobody can reroll -- an
     * investment's payout is drawn from the day it matures, so reloading the
     * world until you like it isn't a strategy.
     */
    public static float wobble(long seed, String key) {
        int hash = mix((int) seed * 31 + key.hashCode());
        // 0..1 from the low bits, then mapped onto +/- DRIFT.
        float unit = (hash >>> 8 & 0xFFFF) / (float) 0xFFFF;
        return 1.0f + (unit * 2.0f - 1.0f) * DRIFT;
    }

    /**
     * Where one item's price sits after the orders that just went through it.
     *
     * Buying pushes a price up and selling pushes it down, immediately and
     * only for that line. This is the part of the market a player can actually
     * feel: clear the shelf of diamonds and the next diamond costs more, dump
     * wheat and wheat is worth less by the time you walk back.
     *
     * Each lot eats a share of the REMAINING headroom rather than a fixed
     * step, so the price approaches its floor and never reaches it. That is
     * the difference between a market and a cliff: linear steps meant three
     * stacks of something walked a cheap line straight into the cap, where it
     * rounded to nothing and the shop stopped buying it at all. Now the
     * hundredth lot moves the price far less than the first, which is both
     * what real order books do and what stops a good harvest being worthless
     * by the time you have carried it in.
     */
    public static float pressureAfter(float pressure, int lots, boolean buying) {
        if (lots <= 0) {
            return pressure;
        }
        float floor = buying ? PRESSURE_CAP : -PRESSURE_CAP;
        float left = (float) Math.pow(1.0f - IMPACT, lots);
        return floor + (pressure - floor) * left;
    }

    /**
     * One beat of the market forgetting what you did.
     *
     * Snapped to zero at the bottom rather than approaching it forever, so a
     * line that has settled can be dropped from the books instead of being
     * carried as 0.0001 for the rest of the world's life.
     */
    public static float relax(float pressure) {
        float eased = pressure * PRESSURE_DECAY;
        return Math.abs(eased) < PRESSURE_FLOOR ? 0.0f : eased;
    }

    /** Order flow as a price multiplier. */
    public static float flowFactor(float pressure) {
        return 1.0f + pressure;
    }

    /**
     * The money supply after emeralds change hands.
     *
     * Only a share lands at once: the rest shows up when the supply is next
     * sampled from what people are actually carrying. Without the damping a
     * single netherite purchase would move every price on the board by a
     * third, which reads as a bug rather than as a market.
     */
    public static float circulated(float supply, int delta) {
        return Math.max(0.0f, supply + delta * FLOW_WEIGHT);
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
    public static int buyPrice(int base, float index, float drift, float flow) {
        return Math.max(1, Math.round(base * index * drift * flow));
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

    /**
     * How to hand over an amount: blocks and loose emeralds.
     *
     * Below a stack of blocks it's friendlier to give singles -- people spend
     * emeralds and hoard blocks -- but above that, paying 1,150 in loose
     * emeralds is eighteen stacks. Returns {blocks, loose}, which must always
     * be worth exactly what was asked for: this is the wallet's withdraw path
     * and every shop payout, so a rounding slip here quietly mints or burns
     * money.
     */
    public static int[] packEmeralds(int amount) {
        if (amount <= 0) {
            return new int[]{0, 0};
        }
        if (amount < 64) {
            return new int[]{0, amount};
        }
        return new int[]{amount / 9, amount % 9};
    }

    // --- the slot machine -----------------------------------------------------

    /** The grid is square. */
    public static final int SLOT_SIZE = 5;
    /** How many different symbols the reels carry. */
    public static final int SLOT_FACES = 22;

    /**
     * What each way of winning pays, as a multiple of the stake.
     *
     * The floor is your stake back. A win that hands over less than you put
     * in is a loss wearing a party hat, and a machine full of those is one
     * nobody can tell they are losing at -- which was the complaint.
     *
     * Everything above the floor is priced so each tier contributes about the
     * same share of the payout: a Four Corners lands roughly a fortieth as
     * often as a three, so it pays roughly forty times as much. That is why
     * the numbers look the way they do rather than being picked for feel.
     *
     * Raising these means lowering the odds or the return; see the note on
     * SLOT_PLAN_ODDS. There is no third option -- the return is exactly the
     * sum of frequency times pay, and the tests measure it.
     */
    public static final float PAY_RUN3 = 1.0f;
    public static final float PAY_RUN4 = 4.0f;
    public static final float PAY_RUN5 = 30.0f;
    public static final float PAY_SQUARE = 1.5f;
    public static final float PAY_PLUS = 2.5f;
    public static final float PAY_CROSS = 2.5f;
    public static final float PAY_ZED = 8.0f;
    public static final float PAY_DIAMOND = 10.0f;
    public static final float PAY_CORNERS = 15.0f;

    /**
     * One way of winning: which cells, what it's called, what it pays.
     *
     * Shapes rather than a single payline is the whole design. A 5x5 window
     * where only straight runs counted left most of the board as decoration,
     * and decoration full of near-misses reads as a machine that owes you
     * money and won't pay.
     */
    public record SlotShape(String name, int[] cells, float pay) {
    }

    private static int cellAt(int row, int col) {
        return row * SLOT_SIZE + col;
    }

    /**
     * Every line worth reading: rows, columns, and EVERY diagonal of three or
     * more -- not just the two long ones. The short diagonals either side of
     * the middle are the ones players see and expect to count.
     */
    public static int[][] slotLines() {
        List<int[]> lines = new ArrayList<>();
        for (int row = 0; row < SLOT_SIZE; row++) {
            int[] cells = new int[SLOT_SIZE];
            for (int col = 0; col < SLOT_SIZE; col++) {
                cells[col] = cellAt(row, col);
            }
            lines.add(cells);
        }
        for (int col = 0; col < SLOT_SIZE; col++) {
            int[] cells = new int[SLOT_SIZE];
            for (int row = 0; row < SLOT_SIZE; row++) {
                cells[row] = cellAt(row, col);
            }
            lines.add(cells);
        }
        // Both diagonal directions at every offset that still leaves three
        // cells on the board.
        for (int offset = -(SLOT_SIZE - 3); offset <= SLOT_SIZE - 3; offset++) {
            List<Integer> down = new ArrayList<>();
            List<Integer> up = new ArrayList<>();
            for (int i = 0; i < SLOT_SIZE; i++) {
                int right = i + offset;
                int left = SLOT_SIZE - 1 - i + offset;
                if (right >= 0 && right < SLOT_SIZE) {
                    down.add(cellAt(i, right));
                }
                if (left >= 0 && left < SLOT_SIZE) {
                    up.add(cellAt(i, left));
                }
            }
            lines.add(toArray(down));
            lines.add(toArray(up));
        }
        return lines.toArray(new int[0][]);
    }

    private static int[] toArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /**
     * The shapes that are not straight lines, every placement of each.
     *
     * Built rather than written out, so a 7x7 machine would need no new
     * literals and so a typo can't put a shape half off the board.
     */
    public static List<SlotShape> slotShapes() {
        List<SlotShape> shapes = new ArrayList<>();

        for (int row = 0; row + 1 < SLOT_SIZE; row++) {
            for (int col = 0; col + 1 < SLOT_SIZE; col++) {
                shapes.add(new SlotShape("Block", new int[]{
                        cellAt(row, col), cellAt(row, col + 1),
                        cellAt(row + 1, col), cellAt(row + 1, col + 1)}, PAY_SQUARE));
            }
        }
        for (int row = 1; row + 1 < SLOT_SIZE; row++) {
            for (int col = 1; col + 1 < SLOT_SIZE; col++) {
                shapes.add(new SlotShape("Cross", new int[]{
                        cellAt(row - 1, col), cellAt(row, col - 1), cellAt(row, col),
                        cellAt(row, col + 1), cellAt(row + 1, col)}, PAY_PLUS));
                shapes.add(new SlotShape("Star", new int[]{
                        cellAt(row - 1, col - 1), cellAt(row - 1, col + 1), cellAt(row, col),
                        cellAt(row + 1, col - 1), cellAt(row + 1, col + 1)}, PAY_CROSS));
                // A Z: across the top, back down the diagonal, across the
                // bottom. Seven cells, which is why it pays what it does.
                shapes.add(new SlotShape("Zed", new int[]{
                        cellAt(row - 1, col - 1), cellAt(row - 1, col), cellAt(row - 1, col + 1),
                        cellAt(row, col),
                        cellAt(row + 1, col - 1), cellAt(row + 1, col), cellAt(row + 1, col + 1)},
                        PAY_ZED));
            }
        }
        shapes.add(new SlotShape("Diamond", new int[]{
                cellAt(0, 2), cellAt(2, 0), cellAt(2, 2), cellAt(2, 4), cellAt(4, 2)},
                PAY_DIAMOND));
        shapes.add(new SlotShape("Four Corners", new int[]{
                cellAt(0, 0), cellAt(0, SLOT_SIZE - 1),
                cellAt(SLOT_SIZE - 1, 0), cellAt(SLOT_SIZE - 1, SLOT_SIZE - 1)},
                PAY_CORNERS));
        return shapes;
    }

    /** What a run of this length pays. Zero if it isn't long enough. */
    public static float slotPayForRun(int run) {
        if (run >= 5) {
            return PAY_RUN5;
        }
        if (run == 4) {
            return PAY_RUN4;
        }
        return run == 3 ? PAY_RUN3 : 0.0f;
    }

    /** Everything one board won: total multiplier, the cells, and the names. */
    public record SlotScore(float pay, int[] cells, List<String> names) {
        public boolean won() {
            return pay > 0.0f;
        }
    }

    /**
     * Score a board by reading it, counting EVERY way it won.
     *
     * Each line pays for its own longest run and each shape pays once, and the
     * lot is added up -- so three diamonds across the bottom and three stars
     * down a column is two wins, not one. That stacking is where the big
     * numbers come from, and it is the reason a busy board is worth staring at
     * instead of just checking the middle row.
     *
     * Reading the grid rather than trusting what was intended also means the
     * highlight can never disagree with the payout: if a cell lights up, it is
     * a cell that paid.
     */
    public static SlotScore slotScore(int[] grid) {
        // Collect every win the board contains, best first.
        List<SlotShape> found = new ArrayList<>();
        for (int[] line : slotLines()) {
            int[] run = longestRun(grid, line);
            float worth = slotPayForRun(run[0]);
            if (worth > 0.0f) {
                int[] cells = new int[run[0]];
                System.arraycopy(line, run[1], cells, 0, run[0]);
                found.add(new SlotShape(run[0] + " in a row", cells, worth));
            }
        }
        for (SlotShape shape : slotShapes()) {
            if (uniform(grid, shape.cells())) {
                found.add(shape);
            }
        }
        found.sort((a, b) -> Float.compare(b.pay(), a.pay()));

        // Each square pays once. A Cross IS a three-across and a three-down,
        // and paying it as all three made shapes fund their own line wins --
        // which ate so much of the return that a bare three could only be
        // priced at a third of the stake. Taking the best win on a square and
        // moving on is both fairer to read and what frees up the budget for
        // multipliers worth chasing.
        float pay = 0.0f;
        List<String> names = new ArrayList<>();
        boolean[] claimed = new boolean[grid.length];
        for (SlotShape win : found) {
            boolean fresh = false;
            for (int cell : win.cells()) {
                if (!claimed[cell]) {
                    fresh = true;
                    break;
                }
            }
            if (!fresh) {
                continue;
            }
            pay += win.pay();
            names.add(win.name());
            for (int cell : win.cells()) {
                claimed[cell] = true;
            }
        }

        List<Integer> cells = new ArrayList<>();
        for (int cell = 0; cell < claimed.length; cell++) {
            if (claimed[cell]) {
                cells.add(cell);
            }
        }
        return new SlotScore(pay, toArray(cells), names);
    }

    private static boolean uniform(int[] grid, int[] cells) {
        for (int i = 1; i < cells.length; i++) {
            if (grid[cells[i]] != grid[cells[0]]) {
                return false;
            }
        }
        return true;
    }

    /** Longest run of identical symbols on one line, and where it starts. */
    public static int[] longestRun(int[] grid, int[] line) {
        int best = 1;
        int bestStart = 0;
        int run = 1;
        int start = 0;
        for (int i = 1; i < line.length; i++) {
            if (grid[line[i]] == grid[line[i - 1]]) {
                run++;
            } else {
                run = 1;
                start = i;
            }
            if (run > best) {
                best = run;
                bestStart = start;
            }
        }
        return new int[]{best, bestStart};
    }

    /**
     * How often each planted outcome is aimed for, and what it plants.
     *
     * The machine picks the outcome first and then draws a board that agrees
     * with it, which is how real ones work and what makes the return a number
     * somebody can check rather than an emergent mystery. Whatever the random
     * fill happens to add on top is a bonus, and {@link #slotScore} pays for
     * it -- so the measured return sits a little above this table. The
     * Monte Carlo in the tests is what says by how much.
     */
    public static final String[] SLOT_PLANS = {
            "run3", "square", "cross", "star", "run4", "zed", "diamond", "corners", "run5",
    };
    public static final float[] SLOT_PLAN_ODDS = {
            0.150f, 0.052f, 0.036f, 0.036f, 0.022f, 0.010f, 0.008f, 0.006f, 0.0030f,
    };

    /**
     * What the machine actually returns, measured not asserted.
     *
     * There is no closed form once wins stack, so these come from
     * {@link #slotMeasure} over 300k spins and the test suite re-measures them
     * on every build. If a pay or an odd is edited without updating these, the
     * test fails -- which is the point, because the paytable in the cabinet
     * quotes them to the player.
     */
    public static final float SLOT_MEASURED_RTP = 0.976f;
    public static final float SLOT_MEASURED_WIN_RATE = 0.324f;

    /** How often a spin is aimed at paying anything at all. */
    public static float slotWinChance() {
        float chance = 0;
        for (float odds : SLOT_PLAN_ODDS) {
            chance += odds;
        }
        return chance;
    }

    /** Which plan a uniform 0..1 draw selects, or null for a losing board. */
    public static String slotPlan(float roll) {
        float floor = 0.0f;
        for (int i = 0; i < SLOT_PLAN_ODDS.length; i++) {
            floor += SLOT_PLAN_ODDS[i];
            if (roll < floor) {
                return SLOT_PLANS[i];
            }
        }
        return null;
    }

    /**
     * Draw a board.
     *
     * Fills at random, then plants the chosen shape on top so the outcome is
     * the one that was drawn. A losing board is re-rolled until it really does
     * score nothing -- with twenty lines and sixty shapes an accidental win is
     * common, and paying nothing for a board that visibly won is the single
     * worst thing this machine could do.
     */
    public static int[] slotBoard(Random rng, String plan) {
        int[] grid = new int[SLOT_SIZE * SLOT_SIZE];
        List<SlotShape> shapes = slotShapes();
        int[][] lines = slotLines();

        for (int attempt = 0; attempt < 400; attempt++) {
            for (int cell = 0; cell < grid.length; cell++) {
                grid[cell] = rng.nextInt(SLOT_FACES);
            }
            if (plan != null) {
                plant(rng, grid, plan, lines, shapes);
            }
            boolean won = slotScore(grid).won();
            if (plan == null ? !won : won) {
                return grid;
            }
        }
        return grid;
    }

    private static void plant(Random rng, int[] grid, String plan,
                              int[][] lines, List<SlotShape> shapes) {
        int symbol = rng.nextInt(SLOT_FACES);
        int run = switch (plan) {
            case "run3" -> 3;
            case "run4" -> 4;
            case "run5" -> 5;
            default -> 0;
        };
        if (run > 0) {
            List<int[]> wide = new ArrayList<>();
            for (int[] line : lines) {
                if (line.length >= run) {
                    wide.add(line);
                }
            }
            int[] line = wide.get(rng.nextInt(wide.size()));
            int start = rng.nextInt(line.length - run + 1);
            for (int i = 0; i < run; i++) {
                grid[line[start + i]] = symbol;
            }
            // Break the cells either side, so a planted three stays a three.
            // Without this the fill extends it to a four or a five by luck
            // about a third of the time, and those pay seven and forty -- it
            // was most of a 2.7x return, which is to say the house was losing.
            if (start > 0) {
                grid[line[start - 1]] = (symbol + 1) % SLOT_FACES;
            }
            if (start + run < line.length) {
                grid[line[start + run]] = (symbol + 1) % SLOT_FACES;
            }
            return;
        }

        String wanted = switch (plan) {
            case "square" -> "Block";
            case "cross" -> "Cross";
            case "star" -> "Star";
            case "zed" -> "Zed";
            case "diamond" -> "Diamond";
            default -> "Four Corners";
        };
        List<SlotShape> matching = new ArrayList<>();
        for (SlotShape shape : shapes) {
            if (shape.name().equals(wanted)) {
                matching.add(shape);
            }
        }
        for (int cell : matching.get(rng.nextInt(matching.size())).cells()) {
            grid[cell] = symbol;
        }
    }

    /**
     * Long-run return per emerald staked, measured rather than asserted.
     *
     * There is no closed form once wins stack, so this plays the machine.
     * Deterministic from the seed, so the number in the tests is the number
     * you get.
     */
    public static float slotMeasure(long seed, int spins, float[] winRateOut) {
        Random rng = new Random(seed);
        float paid = 0.0f;
        int won = 0;
        for (int spin = 0; spin < spins; spin++) {
            int[] grid = slotBoard(rng, slotPlan(rng.nextFloat()));
            SlotScore score = slotScore(grid);
            paid += score.pay();
            if (score.won()) {
                won++;
            }
        }
        if (winRateOut != null && winRateOut.length > 0) {
            winRateOut[0] = won / (float) spins;
        }
        return paid / spins;
    }

    // --- the climb --------------------------------------------------------------

    /**
     * Six rungs, one bad door on each, and you may stop whenever you like.
     *
     * The fourth machine, and the one axis the other three don't have: a
     * decision DURING the game. The slot machine decides for you, roulette
     * asks what you're backing before anything moves, and the peg board is
     * pure spectacle -- here the only question is when to get off, and it is
     * asked six times.
     *
     * The multipliers are chosen so every rung carries exactly the same house
     * edge. That means there is no correct place to stop: cashing at the first
     * rung and going for all six are the same bet in expectation, and the
     * choice is nerve rather than arithmetic. A test asserts it, because the
     * moment one rung is worth more than another the game becomes a puzzle
     * with an answer and stops being a gamble.
     */
    public static final int CLIMB_RUNGS = 6;
    /** What the house keeps, whichever rung you stop on. */
    public static final float CLIMB_RETURN = 0.965f;

    /** How the two ladders differ: doors per rung, one of which is bad. */
    public static final int[] CLIMB_DOORS = {4, 3};
    public static final String[] CLIMB_NAMES = {"Steady", "Reckless"};

    /** Odds of surviving one rung on this ladder. */
    public static float climbSafeChance(int ladder) {
        int doors = CLIMB_DOORS[ladder];
        return (doors - 1) / (float) doors;
    }

    /**
     * What standing on this rung is worth, as a multiple of the stake.
     *
     * Solved from the odds rather than picked: the multiplier is exactly what
     * makes the return the same at every height.
     *
     * @param rung how many doors have been survived, 1..CLIMB_RUNGS
     */
    public static float climbMultiplier(int ladder, int rung) {
        return CLIMB_RETURN / (float) Math.pow(climbSafeChance(ladder), rung);
    }

    /** Chance of getting this high at all. */
    public static float climbSurvival(int ladder, int rung) {
        return (float) Math.pow(climbSafeChance(ladder), rung);
    }

    /** Long-run return for a player who always stops on this rung. */
    public static float climbReturnToPlayer(int ladder, int rung) {
        return climbSurvival(ladder, rung) * climbMultiplier(ladder, rung);
    }

    // --- plinko -----------------------------------------------------------------

    /**
     * The peg board: a ball dropped down the middle, eight bounces, nine slots.
     *
     * The third machine and a third kind of gamble. The slot machine decides
     * your outcome and draws a board to match; roulette lets you pick what you
     * are backing; here you have no choice at all and simply watch. What makes
     * it worth watching is that the odds are VISIBLE -- everyone knows the ball
     * usually lands in the middle, so everyone knows the edges are where the
     * money is, and everyone watches the last two bounces.
     *
     * Eight fair coin flips gives the binomial 1:8:28:56:70:56:28:8:1 over 256,
     * so the payouts here are not a table somebody tuned by feel. They are
     * solved: the middle lands 70 times in 256 and pays a fifth, the edges land
     * once each and pay twenty-six times.
     */
    public static final int PLINKO_BOUNCES = 8;
    public static final int PLINKO_SLOTS = PLINKO_BOUNCES + 1;
    public static final float[] PLINKO_PAYS = {
            26.0f, 5.0f, 1.0f, 0.4f, 0.2f, 0.4f, 1.0f, 5.0f, 26.0f,
    };

    /** How many of the 256 equally likely paths end in each slot. */
    public static int plinkoPaths(int slot) {
        int paths = 1;
        for (int step = 0; step < slot; step++) {
            paths = paths * (PLINKO_BOUNCES - step) / (step + 1);
        }
        return paths;
    }

    /**
     * Long-run return per emerald. Exact, not sampled.
     *
     * There are only 256 paths, so summing them is both faster and more honest
     * than a Monte Carlo -- and it means a mistyped multiplier shows up as a
     * failing test rather than as a slow leak nobody notices for a week.
     */
    public static float plinkoReturnToPlayer() {
        float total = 0.0f;
        int paths = 0;
        for (int slot = 0; slot < PLINKO_SLOTS; slot++) {
            total += plinkoPaths(slot) * PLINKO_PAYS[slot];
            paths += plinkoPaths(slot);
        }
        return total / paths;
    }

    /** Where a ball ends up, given which way it went at each peg. */
    public static int plinkoSlot(boolean[] bounces) {
        int right = 0;
        for (boolean wentRight : bounces) {
            if (wentRight) {
                right++;
            }
        }
        return right;
    }

    // --- the pawn counter -------------------------------------------------------

    /** What the counter pays, as a share of what a listed item would fetch. */
    public static final float SCRAP_RATE = 0.6f;

    /**
     * What something the catalogue doesn't list is worth, per item.
     *
     * The shop's shelves are a curated list and always will be, but a player
     * with a chest of odds and ends wants to turn it into emeralds without
     * caring whether somebody wrote a line for it. So anything not listed gets
     * valued from what the game itself says about it -- how filling it is, how
     * rare it is, how much use is in it -- which works for modded items nobody
     * has ever heard of just as well as for vanilla.
     *
     * Deliberately worse than the shelves. A listed item has a price the
     * market moves; this is a pawn counter, and a pawn counter that pays full
     * value is just a shop with extra steps.
     *
     * @param nutrition  food value, 0 if it isn't food
     * @param saturation food saturation, 0 if it isn't food
     * @param rarity     0 common, 1 uncommon, 2 rare, 3 epic
     * @param maxDamage  durability, 0 if it isn't a tool
     * @param maxCount   stack size, as a hint at how special the thing is
     * @return emeralds per item, which may be a fraction of one
     */
    public static float scrapPrice(int nutrition, float saturation, int rarity,
                                   int maxDamage, int maxCount) {
        // A FRACTION of an emerald for ordinary stackable junk, and the caller
        // multiplies by the stack. A flat "at least one each" floor would have
        // made a stick worth more than a share of the log it came from, and a
        // log is eight sticks -- an afternoon at a crafting table would have
        // out-earned everything else in this mod put together.
        float worth = 0.05f;
        worth += nutrition * 0.35f + saturation * 0.2f;
        // Durability stands in for "how much work went into it": a netherite
        // pickaxe and a wooden one differ by thirty times here, which is about
        // right without knowing anything about either.
        worth += maxDamage / 60.0f;
        // Something that won't stack and isn't a tool is a one-off.
        if (maxCount <= 1 && maxDamage <= 0) {
            worth += 4.0f;
        }
        float[] byRarity = {1.0f, 2.2f, 5.0f, 11.0f};
        worth *= byRarity[Math.max(0, Math.min(byRarity.length - 1, rarity))];
        return worth * SCRAP_RATE;
    }

    /**
     * What an enchanted book the catalogue doesn't list is worth.
     *
     * Priced off the levels on it, so a modded enchantment or a level nobody
     * wrote a shelf line for still fetches something sensible instead of
     * falling through to "a book, worth nothing".
     */
    public static int scrapBookPrice(int totalLevels, int enchantments) {
        return Math.max(2, Math.round((totalLevels * 22.0f + enchantments * 15.0f) * SCRAP_RATE));
    }

    // --- roulette ---------------------------------------------------------------

    /**
     * A European wheel: one zero, not two.
     *
     * The American wheel's second zero doubles the house edge to 5.3%, which
     * on a server where the slot machine keeps 2.4% would make the table next
     * to it the worse bet for no reason a player could see. One zero puts
     * every bet on this table at the same 2.7%, which is the nice property of
     * roulette: straight up or red-black, the edge is identical and the choice
     * is purely about how you want to lose it.
     */
    public static final int ROULETTE_POCKETS = 37;
    /** A straight-up number pays 35 to 1, so 36 back including the stake. */
    public static final int ROULETTE_STRAIGHT = 36;
    /** The even-money bets pay 1 to 1, so 2 back. */
    public static final int ROULETTE_EVEN_MONEY = 2;

    private static final int[] REDS = {
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36,
    };

    public static boolean rouletteRed(int pocket) {
        for (int red : REDS) {
            if (red == pocket) {
                return true;
            }
        }
        return false;
    }

    public static boolean rouletteBlack(int pocket) {
        return pocket != 0 && !rouletteRed(pocket);
    }

    /**
     * What one bet returns on this pocket, per emerald staked.
     *
     * Zero is the house's pocket: it loses every outside bet, which is the
     * entire edge. A bet named for a number wins only on that number.
     *
     * @param bet "red", "black", "odd", "even", "low", "high", or a number
     * @return emeralds returned per emerald staked, including the stake
     */
    public static int rouletteReturn(String bet, int pocket) {
        if (bet.equals("red")) {
            return rouletteRed(pocket) ? ROULETTE_EVEN_MONEY : 0;
        }
        if (bet.equals("black")) {
            return rouletteBlack(pocket) ? ROULETTE_EVEN_MONEY : 0;
        }
        if (pocket == 0) {
            // Zero beats every outside bet. Straight-up on zero is handled by
            // the number branch below and does win.
            if (!bet.equals("0")) {
                return 0;
            }
        }
        switch (bet) {
            case "odd":
                return pocket % 2 == 1 ? ROULETTE_EVEN_MONEY : 0;
            case "even":
                return pocket != 0 && pocket % 2 == 0 ? ROULETTE_EVEN_MONEY : 0;
            case "low":
                return pocket >= 1 && pocket <= 18 ? ROULETTE_EVEN_MONEY : 0;
            case "high":
                return pocket >= 19 && pocket <= 36 ? ROULETTE_EVEN_MONEY : 0;
            default:
                try {
                    return Integer.parseInt(bet) == pocket ? ROULETTE_STRAIGHT : 0;
                } catch (NumberFormatException notANumber) {
                    return 0;
                }
        }
    }

    /**
     * Long-run return per emerald on one kind of bet.
     *
     * Exact rather than sampled -- there are only 37 outcomes, so summing them
     * is both faster and more honest than a Monte Carlo. Every bet on a
     * single-zero wheel comes out at 36/37; a test asserts exactly that,
     * because a payout typo is otherwise invisible.
     */
    public static float rouletteReturnToPlayer(String bet) {
        int total = 0;
        for (int pocket = 0; pocket < ROULETTE_POCKETS; pocket++) {
            total += rouletteReturn(bet, pocket);
        }
        return total / (float) ROULETTE_POCKETS;
    }

    /** Every bet a player can place, for tests and for the paytable. */
    public static List<String> rouletteBets() {
        List<String> bets = new ArrayList<>(
                List.of("red", "black", "odd", "even", "low", "high"));
        for (int number = 0; number < ROULETTE_POCKETS; number++) {
            bets.add(String.valueOf(number));
        }
        return bets;
    }

    // --- the coin toss ----------------------------------------------------------

    /**
     * Heads, tails, and the third thing.
     *
     * A coin toss is the most boring bet there is, which is exactly why this
     * one has an edge: about three tosses in two hundred the coin comes down
     * on its rim, and anybody who called it takes sixty-four times their
     * stake. Nobody wins it. Everybody tries it once.
     *
     * The two sensible bets and the silly one carry the same house edge to
     * within half a percent, so calling the edge is a genuine choice about
     * variance rather than a trap.
     */
    public static final float TOSS_EDGE_CHANCE = 0.015f;
    public static final float TOSS_SIDE_PAY = 1.96f;
    public static final float TOSS_EDGE_PAY = 64.0f;

    /** 0 heads, 1 tails, 2 on its edge. */
    public static int tossResult(float roll) {
        if (roll < TOSS_EDGE_CHANCE) {
            return 2;
        }
        return roll < TOSS_EDGE_CHANCE + (1.0f - TOSS_EDGE_CHANCE) / 2.0f ? 0 : 1;
    }

    /** What a call returns per emerald staked, including the stake. */
    public static float tossReturn(int called, int result) {
        if (called != result) {
            return 0.0f;
        }
        return called == 2 ? TOSS_EDGE_PAY : TOSS_SIDE_PAY;
    }

    /** Long-run return for one kind of call. Exact: there are three outcomes. */
    public static float tossReturnToPlayer(int called) {
        float chance = called == 2
                ? TOSS_EDGE_CHANCE : (1.0f - TOSS_EDGE_CHANCE) / 2.0f;
        return chance * (called == 2 ? TOSS_EDGE_PAY : TOSS_SIDE_PAY);
    }

    // --- blackjack --------------------------------------------------------------

    /**
     * What a hand is worth, aces counted the way that helps you.
     *
     * Ranks arrive as 1..13. An ace is eleven unless that busts you, which is
     * the only rule in blackjack that people get wrong when they write it, so
     * it is one line here and tested rather than scattered through the screen.
     */
    public static int handValue(int[] ranks, int count) {
        int total = 0;
        int aces = 0;
        for (int i = 0; i < count; i++) {
            int rank = ranks[i];
            if (rank == 1) {
                aces++;
                total += 11;
            } else {
                total += Math.min(10, rank);
            }
        }
        while (total > 21 && aces > 0) {
            total -= 10;
            aces--;
        }
        return total;
    }

    /** Two cards making exactly 21. Pays better than any other 21. */
    public static boolean isBlackjack(int[] ranks, int count) {
        return count == 2 && handValue(ranks, count) == 21;
    }

    /**
     * What blackjack pays.
     *
     * Six to five, not the three to two a real table pays. That is the single
     * change that takes the house edge from about half a percent to about one
     * and a half, and it is what casinos actually did when they wanted more
     * money without changing a rule anybody reads. It fits this place.
     */
    public static final float BLACKJACK_PAY = 2.2f;
    public static final int DEALER_STANDS = 17;

    /** Does the dealer take another card on this hand? */
    public static boolean dealerHits(int[] ranks, int count) {
        return handValue(ranks, count) < DEALER_STANDS;
    }

    // --- the coin market --------------------------------------------------------

    /**
     * What a coin is worth on a given beat.
     *
     * A pure function of (beat, coin) rather than a running price kept in a
     * file. That means the chart is the same for everybody, survives a restart
     * with no state at all, and -- the useful part -- history can be READ
     * BACKWARDS, so a sparkline of the last hour costs nothing to draw.
     *
     * The shape comes from stacked octaves of smoothed noise, which is how you
     * get something that looks like a market rather than like a sine wave: a
     * slow tide, a daily swing, and a jitter on top, each half the size and
     * twice the speed of the one before.
     */
    public static final int COIN_OCTAVES = 3;
    /** Beats in the slowest octave. At a 30s beat this is about half an hour. */
    public static final float COIN_SLOWEST = 64.0f;

    public static float coinNoise(long beat, String coin) {
        float total = 0.0f;
        float amplitude = 1.0f;
        float span = COIN_SLOWEST;
        for (int octave = 0; octave < COIN_OCTAVES; octave++) {
            long step = Math.max(1L, (long) span);
            long at = Math.floorDiv(beat, step);
            float phase = Math.floorMod(beat, step) / (float) step;
            float from = unit(at, coin + octave);
            float to = unit(at + 1, coin + octave);
            float eased = phase * phase * (3.0f - 2.0f * phase);
            total += ((from + (to - from) * eased) - 0.5f) * 2.0f * amplitude;
            amplitude *= 0.5f;
            span *= 0.5f;
        }
        return total;
    }

    /** A deterministic 0..1 from any (number, key) pair. */
    private static float unit(long value, String key) {
        int hash = mix((int) value * 31 + key.hashCode());
        return (hash >>> 8 & 0xFFFF) / (float) 0xFFFF;
    }

    /** How long a coin's life runs before it relists. About a day of beats. */
    public static final int COIN_ERA = 2880;
    /** What a rugged coin is worth: not quite nothing, which is worse. */
    public static final float COIN_RUGGED = 0.04f;

    /**
     * The beat this coin dies on, or -1 if it survives its era.
     *
     * Rug pulls are drawn per ERA rather than rolled per beat. A per-beat roll
     * with any meaningful chance makes a rug a certainty over a long enough
     * era, which is not a risk, it's a countdown.
     */
    public static long coinRugBeat(long era, String coin, float rugChance) {
        if (rugChance <= 0.0f) {
            return -1;
        }
        if (unit(era, coin + ":rug") >= rugChance) {
            return -1;
        }
        // Never in the first eighth of the era: a coin that relists already
        // dead gives nobody a chance to make the mistake.
        float when = 0.125f + unit(era, coin + ":when") * 0.875f;
        return era * COIN_ERA + (long) (when * COIN_ERA);
    }

    /**
     * What one unit costs right now.
     *
     * @param base       what it listed at
     * @param volatility how hard it swings, roughly the log-range
     * @param rugChance  odds of this coin dying inside any one era
     */
    public static float coinPrice(long beat, String coin, float base,
                                  float volatility, float rugChance) {
        long era = Math.floorDiv(beat, (long) COIN_ERA);
        long rug = coinRugBeat(era, coin, rugChance);
        float price = base * (float) Math.exp(volatility * coinNoise(beat, coin));
        if (rug >= 0 && beat >= rug) {
            price *= COIN_RUGGED;
        }
        return Math.max(0.01f, price);
    }

    /** True if this coin is dead right now and waiting to relist. */
    public static boolean coinDead(long beat, String coin, float rugChance) {
        long era = Math.floorDiv(beat, (long) COIN_ERA);
        long rug = coinRugBeat(era, coin, rugChance);
        return rug >= 0 && beat >= rug;
    }

    /** Change over the last `span` beats, as a percentage. */
    public static int coinMove(long beat, String coin, float base, float volatility,
                               float rugChance, int span) {
        float now = coinPrice(beat, coin, base, volatility, rugChance);
        float then = coinPrice(Math.max(0, beat - span), coin, base, volatility, rugChance);
        return Math.round((now / Math.max(0.01f, then) - 1.0f) * 100.0f);
    }

    /** What the market charges to get in and out. The house always eats. */
    public static final float COIN_SPREAD = 0.03f;

    /**
     * Buying rounds UP and selling rounds DOWN, always.
     *
     * Rounding both to nearest looked symmetrical and was not: on a small
     * enough total, 1.03x and 0.97x round to the same integer and a round trip
     * costs nothing at all. Not exploitable for profit -- you cannot round up
     * past the buy price -- but a zero-edge trade is a hole in the one rule
     * this market has, and cheap coins are exactly where somebody would find
     * it. Rounding each way in the house's favour is also simply what a real
     * exchange does.
     */
    public static int coinBuyCost(float price, int units) {
        return Math.max(1, (int) Math.ceil(price * units * (1.0f + COIN_SPREAD)));
    }

    public static int coinSellValue(float price, int units) {
        return Math.max(0, (int) Math.floor(price * units * (1.0f - COIN_SPREAD)));
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
