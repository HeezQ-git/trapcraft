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
    /**
     * The most reputation anybody's name is worth.
     *
     * A cap, because there wasn't one and rep is the input to four separate
     * multipliers: what the board asks for, how much of it, the standing
     * bonus, and the dealer discount. Three of those were unbounded and they
     * multiply, so a name kept climbing forever while the payout ran into
     * {@link #PAYOUT_CEILING} and stopped -- which meant that past about rep 90
     * every job on the board paid exactly the same and simply demanded more
     * product than the last one. Reputation was quietly becoming a punishment
     * at the top and a runaway in the middle.
     *
     * Fifty is roughly three days of taking every job. Past that a name is as
     * good as it gets, which is what a ladder with a top rung means.
     */
    public static final int REP_MAX = 50;
    /**
     * What each point of standing is worth on a payout.
     *
     * Was 0.02, which made rep 40 a 1.8x multiplier on top of bigger and
     * better-graded jobs -- three compounding bonuses off one stat, and the
     * reason forty felt like it broke the game. At 0.015 a maxed name is
     * 1.75x, and it is the last of the three to arrive rather than the first.
     */
    public static final float REP_STEP = 0.015f;

    /** How much hot work pays over cold. */
    public static final float HEAT_STEP = 0.15f;

    public static float heatMultiplier(int heatTier) {
        return 1.0f + Math.max(0, heatTier) * HEAT_STEP;
    }

    /** A name is only ever worth so much, however many jobs you have run. */
    public static int standing(int rep) {
        return Math.max(0, Math.min(rep, REP_MAX));
    }

    public static int payout(int distanceBlocks, int quantity, int gradeIndex,
                             int heatTier, int rep) {
        int base = Math.max(0, quantity) * (2 + Math.max(0, gradeIndex));
        int distance = Math.max(0, distanceBlocks) / 50;
        float heat = heatMultiplier(heatTier);
        float standing = 1.0f + standing(rep) * REP_STEP;

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

    /**
     * What a brand new world's prices are anchored to.
     *
     * A STARTING value, not a constant the market is measured against forever
     * -- see {@link #baselineAfter}. It was the latter until 2026-08-08, and
     * the consequence was that three players with a working farm pushed the
     * supply to seven times this figure inside a fortnight, welded the index
     * to {@link #INDEX_MAX}, and left it there. Losing every emerald you owned
     * in a casino moved the supply by a third and the prices by nothing at
     * all, because a third of the way down from 13,000 is still miles above
     * 2,000. "The market stays very very high and doesn't come back down" is
     * what a saturated clamp feels like from inside the game.
     */
    public static final float MARKET_BASELINE = 2000.0f;
    /**
     * The least the anchor may fall to.
     *
     * Without it, an economy that emptied out would divide by something near
     * zero and the first emerald anybody mined would send prices to the cap.
     */
    public static final float BASELINE_FLOOR = 500.0f;
    /**
     * How much of the gap to the current supply the anchor closes each beat.
     *
     * Beats are thirty seconds, so this is a half-life of about thirty-five
     * minutes of play. Slow enough that a jackpot is still being felt an hour
     * later -- which is the entire point of having an index -- and fast enough
     * that a session can watch a shock arrive and go.
     */
    public static final float BASELINE_DRAG = 0.010f;
    /** How hard a given change in the money supply pushes prices. */
    public static final float INDEX_SENSITIVITY = 0.5f;
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
     * Measured against a MOVING anchor, not a fixed one. What matters to a
     * price is whether there is more money about than there was lately, not
     * whether there is more than there was on the first day -- the second
     * question only ever has one answer, and a market whose answer never
     * changes is a price list.
     *
     * Clamped hard at both ends. An unbounded index turns a good week into
     * prices nobody can pay, and the point is a market that breathes, not one
     * that runs away.
     */
    public static float marketIndex(float supply, float baseline) {
        float anchor = Math.max(BASELINE_FLOOR, baseline);
        float raw = 1.0f + (supply / anchor - 1.0f) * INDEX_SENSITIVITY;
        return Math.max(INDEX_MIN, Math.min(INDEX_MAX, raw));
    }

    /**
     * One beat of the anchor catching up with the money supply.
     *
     * This is the mean reversion, and it is the whole difference between a
     * market and a ratchet. Print money and everything gets dear; leave it
     * alone and an hour later the same amount of money is simply the new
     * normal and prices have come back to where they were. Burn money and the
     * reverse. Nothing has to be capped, reset or nudged by hand.
     */
    public static float baselineAfter(float baseline, float supply) {
        return Math.max(BASELINE_FLOOR, baseline + (supply - baseline) * BASELINE_DRAG);
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

    /**
     * Four cabinets in one.
     *
     * The 5x5 is the machine this started as. The smaller windows are not
     * shrunk versions of it -- they are different games that happen to share a
     * lever. A 2x2 is a coin flip you watch, over in two seconds and paying
     * something one spin in four; a 5x5 is a board you read, paying one in
     * three but hiding a forty-to-one somewhere in it. Which one is in front
     * of you is a button, because the interesting thing about a floor is
     * having a choice on it.
     *
     * Everything below takes the size as an argument rather than reading a
     * constant. The shapes, the lines, the reel width, the odds and the
     * measured return are all per size, and the tests re-measure every one of
     * them on every build.
     */
    public static final int[] SLOT_SIZES = {2, 3, 4, 5};
    public static final String[] SLOT_CABINETS = {"Pocket", "Corner", "Parlour", "Grand"};

    /** The widest grid, and the length of the reel strip the cabinet draws. */
    public static final int SLOT_SIZE = 5;
    /** How many different symbols the biggest reels carry. */
    public static final int SLOT_FACES = 22;

    /**
     * Reel width for one cabinet.
     *
     * Narrower on a small grid, and it has to be. A 2x2 drawing from
     * twenty-two faces matches a line one spin in twenty-two, which is a game
     * where nothing ever happens; a 5x5 drawing from eight would be a board
     * that accidentally wins every time. The width is what keeps the number of
     * coincidences roughly constant as the window grows.
     */
    public static int slotFaces(int size) {
        return switch (size) {
            case 2 -> 8;
            case 3 -> 12;
            case 4 -> 16;
            default -> SLOT_FACES;
        };
    }

    /** Which cabinet this is called, for the plate on the front. */
    public static String slotCabinet(int size) {
        for (int i = 0; i < SLOT_SIZES.length; i++) {
            if (SLOT_SIZES[i] == size) {
                return SLOT_CABINETS[i];
            }
        }
        return size + "x" + size;
    }

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
    public static final float PAY_RUN2 = 1.5f;
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

    private static int cellAt(int row, int col, int size) {
        return row * size + col;
    }

    /**
     * Every line worth reading: rows, columns, and EVERY diagonal long enough
     * to score -- not just the two long ones. The short diagonals either side
     * of the middle are the ones players see and expect to count.
     */
    public static int[][] slotLines(int size) {
        List<int[]> lines = new ArrayList<>();
        for (int row = 0; row < size; row++) {
            int[] cells = new int[size];
            for (int col = 0; col < size; col++) {
                cells[col] = cellAt(row, col, size);
            }
            lines.add(cells);
        }
        for (int col = 0; col < size; col++) {
            int[] cells = new int[size];
            for (int row = 0; row < size; row++) {
                cells[row] = cellAt(row, col, size);
            }
            lines.add(cells);
        }
        int shortest = slotRunFloor(size);
        for (int offset = -(size - shortest); offset <= size - shortest; offset++) {
            List<Integer> down = new ArrayList<>();
            List<Integer> up = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                int right = i + offset;
                int left = size - 1 - i + offset;
                if (right >= 0 && right < size) {
                    down.add(cellAt(i, right, size));
                }
                if (left >= 0 && left < size) {
                    up.add(cellAt(i, left, size));
                }
            }
            if (down.size() >= shortest) {
                lines.add(down.stream().mapToInt(Integer::intValue).toArray());
            }
            if (up.size() >= shortest) {
                lines.add(up.stream().mapToInt(Integer::intValue).toArray());
            }
        }
        return lines.toArray(new int[0][]);
    }

    /**
     * The shortest run that scores on this grid.
     *
     * Three everywhere except the 2x2, where three does not fit and a pair is
     * the whole game. Guarded rather than left to fall out of the arithmetic
     * because paying for pairs on a 5x5 would hand over money on essentially
     * every spin.
     */
    public static int slotRunFloor(int size) {
        return size <= 2 ? 2 : 3;
    }

    /**
     * Every named shape that fits on this grid.
     *
     * Shapes are dropped rather than squashed when the window is too small for
     * them, and dropped when they would land on the same cells as a
     * better-paying shape -- a Diamond on a 3x3 is a Cross, and two names for
     * one event is how a paytable starts lying.
     */
    public static List<SlotShape> slotShapes(int size) {
        List<SlotShape> shapes = new ArrayList<>();
        // A 2x2 block of the same face. On a 2x2 grid that is the whole
        // window, which is what Four Corners already pays for.
        if (size > 2) {
            for (int row = 0; row + 1 < size; row++) {
                for (int col = 0; col + 1 < size; col++) {
                    shapes.add(new SlotShape("Block", new int[]{
                            cellAt(row, col, size), cellAt(row, col + 1, size),
                            cellAt(row + 1, col, size), cellAt(row + 1, col + 1, size)},
                            PAY_SQUARE));
                }
            }
        }
        for (int row = 1; row + 1 < size; row++) {
            for (int col = 1; col + 1 < size; col++) {
                shapes.add(new SlotShape("Cross", new int[]{
                        cellAt(row - 1, col, size), cellAt(row, col - 1, size),
                        cellAt(row, col, size), cellAt(row, col + 1, size),
                        cellAt(row + 1, col, size)}, PAY_PLUS));
                shapes.add(new SlotShape("Star", new int[]{
                        cellAt(row - 1, col - 1, size), cellAt(row - 1, col + 1, size),
                        cellAt(row, col, size),
                        cellAt(row + 1, col - 1, size), cellAt(row + 1, col + 1, size)},
                        PAY_CROSS));
                // A Z: across the top, back down the diagonal, across the
                // bottom. Seven cells, which is why it pays what it does.
                shapes.add(new SlotShape("Zed", new int[]{
                        cellAt(row - 1, col - 1, size), cellAt(row - 1, col, size),
                        cellAt(row - 1, col + 1, size),
                        cellAt(row, col, size),
                        cellAt(row + 1, col - 1, size), cellAt(row + 1, col, size),
                        cellAt(row + 1, col + 1, size)},
                        PAY_ZED));
            }
        }
        // The diamond needs a middle to hang off and room between it and the
        // edge, so it only exists on odd grids of five or more. On a 3x3 its
        // cells are exactly a Cross.
        if (size >= 5 && size % 2 == 1) {
            int mid = size / 2;
            shapes.add(new SlotShape("Diamond", new int[]{
                    cellAt(0, mid, size), cellAt(mid, 0, size), cellAt(mid, mid, size),
                    cellAt(mid, size - 1, size), cellAt(size - 1, mid, size)},
                    PAY_DIAMOND));
        }
        shapes.add(new SlotShape("Four Corners", new int[]{
                cellAt(0, 0, size), cellAt(0, size - 1, size),
                cellAt(size - 1, 0, size), cellAt(size - 1, size - 1, size)},
                PAY_CORNERS));
        return shapes;
    }

    /** What a run of this length pays on this grid. Zero if it isn't long enough. */
    public static float slotPayForRun(int run, int size) {
        if (run >= 5) {
            return PAY_RUN5;
        }
        if (run == 4) {
            return PAY_RUN4;
        }
        if (run == 3) {
            return PAY_RUN3;
        }
        return run == 2 && slotRunFloor(size) == 2 ? PAY_RUN2 : 0.0f;
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
    public static SlotScore slotScore(int[] grid, int size) {
        // Collect every win the board contains, best first.
        List<SlotShape> found = new ArrayList<>();
        for (int[] line : slotLines(size)) {
            int[] run = longestRun(grid, line);
            float worth = slotPayForRun(run[0], size);
            if (worth > 0.0f) {
                int[] cells = new int[run[0]];
                System.arraycopy(line, run[1], cells, 0, run[0]);
                found.add(new SlotShape(run[0] + " in a row", cells, worth));
            }
        }
        for (SlotShape shape : slotShapes(size)) {
            int face = grid[shape.cells()[0]];
            boolean all = true;
            for (int cell : shape.cells()) {
                if (grid[cell] != face) {
                    all = false;
                    break;
                }
            }
            if (all) {
                found.add(shape);
            }
        }

        // Each square pays once. Sorted by worth so the best claim on a cell
        // wins it: without this a Cross scored as a Cross AND as the two
        // three-runs crossing through it, and paying the same cells three
        // times was most of a 2.7x return.
        found.sort((a, b) -> Float.compare(b.pay(), a.pay()));
        boolean[] claimed = new boolean[grid.length];
        float pay = 0.0f;
        List<String> names = new ArrayList<>();
        List<Integer> lit = new ArrayList<>();
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
                if (!claimed[cell]) {
                    claimed[cell] = true;
                    lit.add(cell);
                }
            }
        }
        return new SlotScore(pay, lit.stream().mapToInt(Integer::intValue).toArray(), names);
    }

    /**
     * The longest stretch of one face along a line: {length, where it starts}.
     */
    private static int[] longestRun(int[] grid, int[] line) {
        int best = 0;
        int bestAt = 0;
        int run = 1;
        int at = 0;
        for (int i = 1; i <= line.length; i++) {
            if (i < line.length && grid[line[i]] == grid[line[i - 1]]) {
                run++;
            } else {
                if (run > best) {
                    best = run;
                    bestAt = at;
                }
                run = 1;
                at = i;
            }
        }
        return new int[]{best, bestAt};
    }

    /**
     * What the machine aims at, and how often.
     *
     * The machine picks the outcome first and then draws a board that agrees
     * with it, which is how real ones work and what makes the return a number
     * somebody can check rather than an emergent mystery. Whatever the random
     * fill happens to add on top is a bonus, and {@link #slotScore} pays for
     * it -- so the measured return sits a little above this table. The
     * Monte Carlo in the tests is what says by how much.
     *
     * One row per cabinet, because the same odds on a smaller window are a
     * different game: a plan that can only be planted one way on a 3x3 has
     * sixteen places to land on a 5x5, and the accidental extras that come
     * with the fill scale with the window too.
     */
    public static String[] slotPlans(int size) {
        return switch (size) {
            case 2 -> new String[]{"run2", "corners"};
            case 3 -> new String[]{"run3", "cross", "star", "zed", "corners"};
            case 4 -> new String[]{"run3", "square", "cross", "star", "run4",
                    "zed", "corners"};
            default -> new String[]{"run3", "square", "cross", "star", "run4",
                    "zed", "diamond", "corners", "run5"};
        };
    }

    public static float[] slotPlanOdds(int size) {
        return switch (size) {
            // Solved, not chosen. Each row is the base mix scaled until the
            // cabinet measures ~95%, because the same odds on a smaller window
            // are a different return: shapes overlap far more when there is
            // less board, and a Zed on a 3x3 contains all four corners.
            case 2 -> new float[]{0.3551f, 0.0107f};
            case 3 -> new float[]{0.2018f, 0.0269f, 0.0269f, 0.0040f, 0.0040f};
            case 4 -> new float[]{0.1919f, 0.0640f, 0.0435f, 0.0435f, 0.0256f,
                    0.0102f, 0.0077f};
            default -> new float[]{0.150f, 0.052f, 0.036f, 0.036f, 0.022f,
                    0.010f, 0.008f, 0.006f, 0.0030f};
        };
    }

    /**
     * What each cabinet actually returns, measured not asserted.
     *
     * There is no closed form once wins stack, so these come from
     * {@link #slotMeasure} and the test suite re-measures them on every build.
     * If a pay or an odd is edited without updating these, the test fails --
     * which is the point, because the paytable in the cabinet quotes them to
     * the player. Indexed by SLOT_SIZES.
     */
    public static final float[] SLOT_MEASURED_RTP = {0.951f, 0.962f, 0.954f, 0.971f};
    public static final float[] SLOT_MEASURED_WIN_RATE = {0.367f, 0.264f, 0.386f, 0.324f};

    private static int cabinetIndex(int size) {
        for (int i = 0; i < SLOT_SIZES.length; i++) {
            if (SLOT_SIZES[i] == size) {
                return i;
            }
        }
        return SLOT_SIZES.length - 1;
    }

    public static float slotRtp(int size) {
        return SLOT_MEASURED_RTP[cabinetIndex(size)];
    }

    public static float slotWinRate(int size) {
        return SLOT_MEASURED_WIN_RATE[cabinetIndex(size)];
    }

    /** How often a spin is aimed at paying anything at all. */
    public static float slotWinChance(int size) {
        float chance = 0;
        for (float odds : slotPlanOdds(size)) {
            chance += odds;
        }
        return chance;
    }

    /** Which plan a uniform 0..1 draw selects, or null for a losing board. */
    public static String slotPlan(float roll, int size) {
        String[] plans = slotPlans(size);
        float[] odds = slotPlanOdds(size);
        float floor = 0.0f;
        for (int i = 0; i < odds.length; i++) {
            floor += odds[i];
            if (roll < floor) {
                return plans[i];
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
    public static int[] slotBoard(Random rng, String plan, int size) {
        int[] grid = new int[size * size];
        List<SlotShape> shapes = slotShapes(size);
        int[][] lines = slotLines(size);
        int faces = slotFaces(size);

        for (int attempt = 0; attempt < 400; attempt++) {
            for (int cell = 0; cell < grid.length; cell++) {
                grid[cell] = rng.nextInt(faces);
            }
            if (plan != null) {
                plant(rng, grid, plan, lines, shapes, size);
            }
            boolean won = slotScore(grid, size).won();
            if (plan == null ? !won : won) {
                return grid;
            }
        }
        return grid;
    }

    private static void plant(Random rng, int[] grid, String plan,
                              int[][] lines, List<SlotShape> shapes, int size) {
        int faces = slotFaces(size);
        int symbol = rng.nextInt(faces);
        int run = switch (plan) {
            case "run2" -> 2;
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
                grid[line[start - 1]] = (symbol + 1) % faces;
            }
            if (start + run < line.length) {
                grid[line[start + run]] = (symbol + 1) % faces;
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
        if (matching.isEmpty()) {
            return;   // this cabinet has no such shape; the fill decides it
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
    public static float slotMeasure(long seed, int spins, int size, float[] winRateOut) {
        Random rng = new Random(seed);
        float paid = 0.0f;
        int won = 0;
        for (int spin = 0; spin < spins; spin++) {
            int[] grid = slotBoard(rng, slotPlan(rng.nextFloat(), size), size);
            SlotScore score = slotScore(grid, size);
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

    // --- growing --------------------------------------------------------------

    /**
     * How long one crop stage takes, in minutes, for a plant that waits for
     * this many random ticks.
     *
     * A block gets a random tick with probability randomTickSpeed/4096 each
     * game tick, so on the default 3 that is one every 68 seconds. Here so the
     * guide book and the tests read the same arithmetic the crop does, rather
     * than three places all guessing.
     */
    public static float stageMinutes(int rolls, int randomTickSpeed) {
        return rolls * (4096.0f / randomTickSpeed) / 20.0f / 60.0f;
    }

    /**
     * Random ticks a coca bush waits before moving up a stage.
     *
     * A FLAT number, which is the whole fix. Coca used to grow through
     * vanilla's crop tick, and vanilla scales growth by moisture -- counting
     * only {@code Blocks.FARMLAND}, at 3.0 for wet, 1.0 for dry and nothing
     * at all for dirt or for any of the forty food mods' own farmland. That
     * put a bush on dirt at (int)(25/1)+1 = 26 rolls, times the old gate of
     * four, or about two hours a stage: six to twelve hours from seed to ripe,
     * which is indistinguishable from broken and is exactly what it was
     * reported as.
     *
     * Coca has no grade at all -- its value is in the press and the refiner --
     * so nothing ever told anyone the substrate mattered. Rather than invent a
     * reason to care, the bush simply ignores it.
     *
     * Faster than weed on purpose, which reverses the old intent and is
     * correct: a bush is three quarters of the way to nothing on its own. The
     * leaves still have to go through the press and then the refiner before
     * they are worth anything, so the growing is the short end of that chain
     * and pricing it as the long end just made people stop farming it.
     */
    public static final int COCA_GROWTH_ROLLS = 6;

    /**
     * Random ticks a cannabis plant waits, watered and not.
     *
     * Same fix as the coca bush and the same cause -- vanilla's moisture
     * scaling only recognising {@code Blocks.FARMLAND}, so a plant on any of
     * the pack's forty food mods' farmland crawled at two hours a stage while
     * scoring full marks for quality. Weed keeps a real wet/dry gap because
     * "keep water close" is the oldest rule in this mod and is already worth
     * three quality points; it should be worth time too. Dry is now twice as
     * slow rather than eight times, and {@link CannabisCropBlock#hydrated}
     * decides which applies for BOTH the speed and the grade.
     *
     * Wet is held at what a well-run farm already measured, because that is
     * the case nobody complained about.
     */
    public static final int WEED_GROWTH_ROLLS_WET = 13;
    public static final int WEED_GROWTH_ROLLS_DRY = 26;

    // --- the kitchen ------------------------------------------------------------

    /**
     * What one helping of food is worth flat, from what the game says it does.
     *
     * There are sixteen food mods in this pack and between them several hundred
     * dishes, so food cannot be a hand-written price list -- it was, for the
     * three mods somebody had the patience to type out, and everything else got
     * a formula on a completely different scale. A loaf of bread was 2e and a
     * modded loaf of bread was 8e, which is not a market, it is two markets
     * sharing a counter.
     *
     * So: one curve, and the hand-written lines sit on it too. Nutrition and
     * saturation are the game's own statement of what a food is worth, and they
     * happen to track effort well -- a hearty stew is several ingredients and a
     * cooking step, and it says so in its saturation.
     *
     * Sized against what the rest of this server earns rather than against
     * vanilla's villager trades. A field of wheat is now an afternoon's wage
     * instead of the four emeralds it used to be, which is the whole point:
     * food is the one thing here that renews itself, and it was the one thing
     * not worth carrying to the counter.
     */
    /** What every food is worth before anybody eats it. See {@link #foodPrice}. */
    public static final float COOKING_HEADROOM = 3.0f;

    public static int foodPrice(int nutrition, float saturation, int stackSize) {
        // The constant is load-bearing, and it is not "a minimum price". A
        // furnace adds no ingredient, so cooking may multiply a food's worth by
        // at most 1/SELL_RATE before buying the raw one, smelting it and
        // selling it back is free money -- and raw beef to cooked beef is a
        // 3.1x jump on nutrition alone. A flat term every food carries
        // compresses every raw-to-cooked pair under that ceiling at once,
        // without needing to know which foods smelt into which. See
        // FormulaTest.cookingIsNeverFreeMoney.
        float worth = COOKING_HEADROOM + 0.4f * nutrition + 0.25f * saturation;
        // Stack size stands in for the crafting step nutrition can't see. A
        // thing served in a bowl or on a plate took a kitchen to make; a thing
        // that stacks to 64 was picked up off the floor.
        if (stackSize <= 16) {
            worth *= 1.6f;
        }
        if (stackSize <= 1) {
            worth *= 1.4f;
        }
        // Never under 2: sellPrice() refuses to buy back anything cheaper, and
        // a shelf line the counter won't take is the complaint this all came
        // from. See the sell floor in check_stock.py.
        return Math.max(2, Math.round(worth));
    }

    /**
     * How many of it make one lot, so every food line costs roughly the same.
     *
     * A shelf where one line is 2e and the next is 90e reads as broken however
     * defensible each number is on its own. Sizing the LOT rather than the
     * price keeps the board legible and leaves the per-item worth honest.
     */
    public static int foodLot(int price) {
        if (price >= 25) {
            return 1;
        }
        if (price >= 12) {
            return 2;
        }
        return price >= 5 ? 4 : 8;
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

    // --- punters ------------------------------------------------------------------
    //
    // Villagers who wander in, play a few rounds and leave. What they are
    // doing is MODELLED rather than replayed: a punter does not open a screen,
    // so there is no board to draw and nobody to show it to. What the room
    // sees is somebody standing at a machine, the occasional cheer, and the
    // vault moving -- and for that, a distribution with the right mean and a
    // believable shape is the whole requirement.
    //
    // The mean is the machine's own return, so a floor full of punters drifts
    // the vault upward at exactly the edge the cabinet advertises. Anything
    // else would have the books disagree with the paytable.

    /**
     * How busy the floor is at this hour, as a multiplier.
     *
     * Far steeper than the dealers' clock. A villager with a job is at it in
     * the daytime and the room is nearly empty; the evening crowd is who
     * gambles, and a casino should look like a different building at midnight
     * than it does at noon.
     *
     * @param timeOfDay the world's 0..23999
     */
    public static float casinoHourFactor(long timeOfDay) {
        // 0 is sunrise, 6000 noon, 18000 midnight.
        double phase = (timeOfDay % 24000L) / 24000.0 * Math.PI * 2.0;
        float swing = -(float) Math.sin(phase);
        // Floored rather than allowed to reach zero: a casino that is provably
        // shut for eight minutes every day is a casino people stop checking.
        return Math.max(0.12f, 1.0f + 0.95f * swing);
    }

    /** Least and most a punter will put on one round. */
    public static final int PUNTER_MIN_STAKE = 8;
    public static final int PUNTER_MAX_STAKE = 256;

    /**
     * The biggest band a punter will play when the room already holds this
     * many.
     *
     * A quiet floor is where the money is: two people in, and one of them may
     * be putting 256e on a spin. A packed floor is a packed floor because it
     * is cheap -- seven in and nobody is playing above sixteen. Which is both
     * true of real rooms and the thing that keeps a busy night from being
     * simply a bigger night: you trade the size of the bets for the number of
     * them.
     */
    public static int punterStakeCeiling(int crowd) {
        if (crowd <= 1) {
            return 5;   // up to 256e
        }
        if (crowd <= 3) {
            return 4;   // up to 128e
        }
        if (crowd <= 5) {
            return 3;   // up to 64e
        }
        if (crowd <= 6) {
            return 2;   // up to 32e
        }
        return 1;       // up to 16e
    }

    /**
     * What this punter bets a round.
     *
     * Doubling bands from 8 to 256, drawn low-biased by taking the smaller of
     * two rolls -- so most of the room is playing for small change and a whale
     * turns up about once in thirty-six, which is what makes one worth
     * noticing -- then capped by how busy it already is.
     */
    public static int punterStake(Random rng, int crowd) {
        int band = Math.min(Math.min(rng.nextInt(6), rng.nextInt(6)),
                punterStakeCeiling(crowd));
        return PUNTER_MIN_STAKE << band;
    }

    // --- what brings them in ------------------------------------------------

    /** Both stats run 0..100. */
    public static final int HOUSE_STAT_MAX = 100;

    /**
     * How much the floor's own name and its regulars' habits pull people in.
     *
     * Neither of these is a counter that fills up. Both were, for about a day,
     * and both hit a hundred inside a couple of hours of trade and stayed
     * there -- which made a casino a thing you switch on rather than a thing
     * you run. They are now an EQUILIBRIUM: each is pulled towards a level
     * that describes how the place is being kept, and each falls on its own
     * when it isn't.
     *
     * See {@link #houseRepTarget} for what a name is worth and
     * {@link #addictionAfter} for why the regulars leave.
     */
    public static float floorPull(int rep, int addiction) {
        float known = Math.max(0, Math.min(HOUSE_STAT_MAX, rep)) / (float) HOUSE_STAT_MAX;
        float hooked = Math.max(0, Math.min(HOUSE_STAT_MAX, addiction)) / (float) HOUSE_STAT_MAX;
        return 0.55f + 0.85f * known + 0.60f * hooked;
    }

    /** What one wired machine costs to keep lit, per beat. */
    public static final int MACHINE_UPKEEP = 1;
    /**
     * The cut somebody takes of everything played on your floor.
     *
     * A cash business in this world does not get to keep its whole edge.
     *
     * One percent, and it was briefly four -- which every simulated floor lost
     * money at, because the villagers only actually hand over about three and
     * a bit percent and the lights already eat most of that. The seven percent
     * this was first sized against turned out to be the owner losing to their
     * own machines, which is not income at all. See House.trade.
     *
     * Taken on the HANDLE rather than the profit on purpose: it scales with
     * how much goes through the room and not with how well the night went, so
     * a bad night genuinely costs money. That is what a running cost is, and
     * it is the difference between a business and an allowance.
     */
    public static final float PROTECTION_RATE = 0.01f;

    /**
     * Wear a machine takes per round, as one chance in this many.
     *
     * Sized so a busy ten-machine floor throws up something to fix every ten
     * minutes or so: often enough to be a job, rare enough not to be the only
     * job.
     */
    public static final int WEAR_PER_ROUNDS = 15;
    /** Past this a machine is out of order and takes no bets. */
    public static final int WEAR_BROKEN = 100;
    /** What putting one right takes out of the vault, per point of wear. */
    public static final int REPAIR_COST_PER_POINT = 3;

    /**
     * What a pit boss costs per beat, and what going without costs instead.
     *
     * The wage is FLAT and the skim is PROPORTIONAL, which is the whole point:
     * below about ten thousand emeralds of trade a cycle you are better off
     * without one, and above it you are not. A small quiet room and a big busy
     * one want different answers, and that is a decision rather than an
     * upgrade.
     */
    public static final int PIT_BOSS_WAGE = 4;
    public static final float SKIM_RATE = 0.015f;
    public static final int PIT_BOSS_HIRE = 600;

    /** How often a punter is an advantage player, with nobody watching. */
    public static final float CHEAT_CHANCE = 0.06f;
    /** What a cheat plays at. Over one, which is the problem. */
    public static final float CHEAT_RETURN = 1.18f;

    // --- the bar ------------------------------------------------------------
    //
    // The one thing that stops a casino being a faucet.
    //
    // Punters bring money in from outside, so however the percentages are
    // tuned the vault fills on its own -- which made a floor something you
    // switch on and walk away from. A casino has to CONSUME something the
    // owner produces, the way a dealer consumes what you grew and a contract
    // consumes what you cured, or it is not a business, it is a tap.
    //
    // So the floor runs on your stash. Punters are served on the way in;
    // served punters stay, and a dry bar empties the room.

    /** Stacks the bar behind the counter holds. */
    public static final int BAR_SLOTS = 18;

    /** How long a served punter stays, against how long a dry one does. */
    public static final float SERVED_PRODUCT = 1.6f;
    public static final float SERVED_FOOD = 1.15f;
    public static final float SERVED_NOTHING = 0.18f;

    /** What one serving is worth to the regulars. */
    public static final int BAR_ADDICTION_PRODUCT = 3;
    public static final int BAR_ADDICTION_FOOD = 1;
    /** What a dry bar takes off the name it was holding. */
    public static final int DRY_BAR_REP = 28;

    /**
     * Rounds this punter is good for, given what they were handed at the door.
     *
     * A dry bar is not a small penalty. Somebody who walks into a room with
     * nothing behind the counter has one go and leaves, which takes about
     * eighty percent of the trade with it -- and the reputation hit takes more
     * off the arrivals on top. An unattended floor should earn very close to
     * nothing, because the whole complaint was that it earned a fortune.
     */
    public static int punterRoundsServed(int addiction, int servedTier, Random rng) {
        float multiplier = switch (servedTier) {
            case 2 -> SERVED_PRODUCT;
            case 1 -> SERVED_FOOD;
            default -> SERVED_NOTHING;
        };
        return Math.max(1, Math.round(punterRounds(addiction, rng) * multiplier));
    }

    /** What a round of drinks costs per machine, and what it buys. */
    public static final int COMP_COST_PER_MACHINE = 30;
    public static final int COMP_ADDICTION = 9;
    /** Beats before you can stand another round. */
    public static final int COMP_COOLDOWN_BEATS = 8;

    /** How long a loose spell runs, and what it does. */
    public static final int LOOSE_BEATS = 12;
    public static final float LOOSE_RETURN = 1.03f;
    public static final int LOOSE_REP_BONUS = 22;
    public static final int LOOSE_COOLDOWN_BEATS = 40;

    /** What the skim comes to on this much play. */
    public static int skimOn(long handleThisBeat) {
        return (int) Math.max(0, Math.round(Math.max(0, handleThisBeat) * SKIM_RATE));
    }

    /** What the cut comes to on this much play. */
    public static int protectionOn(long handleThisBeat) {
        return (int) Math.max(0, Math.round(Math.max(0, handleThisBeat) * PROTECTION_RATE));
    }
    /** The float a floor is expected to hold behind each machine. */
    public static final int FLOAT_PER_MACHINE = 800;
    /** How fast a name travels towards what the floor deserves, per beat. */
    public static final int REP_DRIFT = 2;

    /**
     * Where this floor's name settles, given how it is actually being kept.
     *
     * A target rather than a total, so the number describes the place as it
     * stands this minute. Stop looking after it and it comes back down on its
     * own -- which is the whole difference between a business and a counter.
     *
     * Three things, and all three are decisions somebody has to keep making:
     *
     *  - VARIETY. Seven different games is a floor. Seven slot machines is a
     *    room with a slot machine in it seven times.
     *  - FLOAT. A vault holding what its machines are worth is a floor that
     *    can take a bet. One running on fumes is a rumour waiting to start.
     *  - ROOM. Somewhere to actually play. A queue at the door is the fastest
     *    way there is to lose a name, which is why turnedAway bites hardest.
     *
     * @param varieties  how many DIFFERENT games are wired, 0..7
     * @param machines   how many machines in total
     * @param balance    what's in the vault
     * @param free       machines standing empty right now
     * @param turnedAway punters sent away since the last beat
     * @param avgWear    average condition of the floor, 0 fresh .. 100 broken
     * @param loose      whether the floor is running generous right now
     */
    public static int houseRepTarget(int varieties, int machines, long balance,
                                     int free, int turnedAway, int avgWear,
                                     boolean loose, boolean dryBar) {
        if (machines <= 0) {
            return 0;
        }
        int score = Math.min(42, Math.max(0, varieties) * 6);
        long needed = Math.max(1L, (long) machines * FLOAT_PER_MACHINE);
        score += (int) Math.min(33, Math.max(0, balance) * 33 / needed);
        score += Math.min(25, Math.max(0, free) * 9);
        score -= Math.min(60, Math.max(0, turnedAway) * 12);
        // A shabby room is a shabby room. Nobody is impressed by a floor of
        // machines held together with tape, and this is what makes the hammer
        // part of running the place rather than a chore with no consequence.
        score -= Math.min(30, Math.max(0, avgWear) * 30 / WEAR_BROKEN);
        if (loose) {
            score += LOOSE_REP_BONUS;
        }
        // Nothing behind the counter is the single most visible way a floor
        // says nobody is looking after it.
        if (dryBar) {
            score -= DRY_BAR_REP;
        }
        return Math.max(0, Math.min(HOUSE_STAT_MAX, score));
    }

    /** One beat of a name moving towards what the floor has earned. */
    public static int repAfter(int rep, int target) {
        if (rep < target) {
            return Math.min(target, rep + REP_DRIFT);
        }
        // Falls twice as fast as it climbs. A reputation is easier to lose.
        return Math.max(target, rep - REP_DRIFT * 2);
    }

    /**
     * One beat of the regulars, given how much play there has been.
     *
     * Diminishing on the way up and bleeding on the way down, so every level
     * is an equilibrium between how busy the room is and how quickly people
     * forget about it. A maximally busy floor settles somewhere in the
     * seventies; a hundred is not a number anybody holds, and an empty room
     * is back to nothing inside half an hour.
     */
    public static int addictionAfter(int addiction, int roundsThisBeat) {
        int held = Math.max(0, Math.min(HOUSE_STAT_MAX, addiction));
        float headroom = 1.0f - held / (float) HOUSE_STAT_MAX;
        int gain = Math.round(Math.min(10.0f, Math.max(0, roundsThisBeat) * 0.25f) * headroom);
        // Bleeds faster the higher it is, which is what makes the top of the
        // range a thing you hold rather than a thing you reach.
        int bleed = 1 + held / 25;
        return Math.max(0, Math.min(HOUSE_STAT_MAX, held + gain - bleed));
    }

    /** Rounds a punter stays for, given how hooked the regulars are. */
    public static int punterRounds(int addiction, Random rng) {
        float hooked = Math.max(0, Math.min(HOUSE_STAT_MAX, addiction))
                / (float) HOUSE_STAT_MAX;
        int base = 4 + rng.nextInt(6);
        return base + Math.round(hooked * 10.0f);
    }

    /**
     * What one punter round returns, as a multiple of what they staked.
     *
     * Mostly nothing, often a small win, rarely a big one -- the shape every
     * machine on the floor has. Scaled so the mean comes out at `rtp`.
     */
    public static float punterRound(float rtp, Random rng) {
        float roll = rng.nextFloat();
        // Base shape, mean 0.956: 0.25*2 + 0.045*6.8 + 0.005*30 = 0.956.
        //
        // The top is 30 and not higher on purpose: that is the table limit a
        // machine is checked against, so a punter can never win more than the
        // vault was required to cover. Losing your bank to a villager while
        // you stand and watch is a great deal less fun than losing it to
        // somebody who was playing.
        float multiple;
        if (roll < 0.700f) {
            multiple = 0.0f;
        } else if (roll < 0.950f) {
            multiple = 2.0f;
        } else if (roll < 0.995f) {
            multiple = 6.8f;
        } else {
            multiple = 30.0f;
        }
        return multiple * (rtp / 0.956f);
    }

    /** Long-run return of the punter model, so the tests can hold it honest. */
    public static float punterMeasure(float rtp, long seed, int rounds) {
        Random rng = new Random(seed);
        float paid = 0.0f;
        for (int round = 0; round < rounds; round++) {
            paid += punterRound(rtp, rng);
        }
        return paid / rounds;
    }

    // --- scratchcards -----------------------------------------------------------
    //
    // Nine panels, six faces, and no reels. The whole appeal is that you find
    // out one square at a time: two matching and seven still silver is the
    // best thirty seconds on the floor, and it costs nothing to build because
    // the card was already decided the moment you paid for it.
    //
    // It is deliberately the loosest machine on the floor by win rate and the
    // tightest by return -- four cards in ten pay SOMETHING and most of those
    // pay back less than the card cost. That is what a scratchcard is, and it
    // is a different feeling from the slot next to it, which is the only
    // reason to have both.

    public static final int SCRATCH_PANELS = 9;
    /** Blank, nugget, emerald, bell, diamond, star. */
    public static final int SCRATCH_FACES = 6;
    /** Out of 1000. Blanks are half the card, which is what makes a match news. */
    public static final int[] SCRATCH_WEIGHTS = {500, 200, 130, 90, 60, 20};
    /** What three of each is worth, as a multiple of the card. */
    public static final float[] SCRATCH_PRIZES = {0.0f, 0.5f, 1.4f, 4.25f, 11.5f, 35.0f};
    /** And what finding more than three multiplies it by. */
    public static final float[] SCRATCH_SIZES = {0, 0, 0, 1.0f, 3.0f, 6.0f, 12.0f};
    /** Three of a kind that also fall in a line pay double. */
    public static final float SCRATCH_LINE_BONUS = 2.0f;
    /** Measured over two million cards. See FormulaTest. */
    public static final float SCRATCH_MEASURED_RTP = 0.944f;
    public static final float SCRATCH_MEASURED_WIN_RATE = 0.396f;

    /** Rows, columns and both diagonals of the 3x3 face. */
    public static final int[][] SCRATCH_LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6},
    };

    /**
     * Print a card.
     *
     * Every panel drawn independently from the same bag, which is what makes
     * the odds computable rather than tuned by feel -- and what stops the card
     * ever being able to "decide" you have had enough.
     */
    public static int[] scratchCard(Random rng) {
        int total = 0;
        for (int weight : SCRATCH_WEIGHTS) {
            total += weight;
        }
        int[] card = new int[SCRATCH_PANELS];
        for (int panel = 0; panel < SCRATCH_PANELS; panel++) {
            int roll = rng.nextInt(total);
            int face = 0;
            while (roll >= SCRATCH_WEIGHTS[face]) {
                roll -= SCRATCH_WEIGHTS[face];
                face++;
            }
            card[panel] = face;
        }
        return card;
    }

    /** Is this face sitting in a full row, column or diagonal? */
    public static boolean scratchInLine(int[] card, int face) {
        for (int[] line : SCRATCH_LINES) {
            if (card[line[0]] == face && card[line[1]] == face && card[line[2]] == face) {
                return true;
            }
        }
        return false;
    }

    /** How many of one face are on the card. */
    public static int scratchCount(int[] card, int face) {
        int found = 0;
        for (int panel : card) {
            if (panel == face) {
                found++;
            }
        }
        return found;
    }

    /**
     * The best thing on this card, as a multiple of what it cost.
     *
     * One prize per card, not one per matching face. A card holding three
     * nuggets AND three diamonds pays the diamonds and nothing else, the same
     * way a real ticket has one prize printed on it -- and, less romantically,
     * because paying both is how the slot machine's return got to 2.69 before
     * it was rewritten.
     */
    public static float scratchPay(int[] card) {
        float best = 0.0f;
        for (int face = 1; face < SCRATCH_FACES; face++) {
            int count = scratchCount(card, face);
            if (count < 3) {
                continue;
            }
            float pay = SCRATCH_PRIZES[face] * SCRATCH_SIZES[Math.min(count, 6)];
            // The line bonus is for exactly three, where finding them lined up
            // is the whole event. Four or more is already being paid for by
            // the size multiplier, and stacking both put the tail somewhere no
            // player-owned vault could ever cover.
            if (count == 3 && scratchInLine(card, face)) {
                pay *= SCRATCH_LINE_BONUS;
            }
            best = Math.max(best, pay);
        }
        return best;
    }

    /** Which face won, or -1 for a dud. Kept in step with scratchPay by construction. */
    public static int scratchWinner(int[] card) {
        int winner = -1;
        float best = 0.0f;
        for (int face = 1; face < SCRATCH_FACES; face++) {
            int count = scratchCount(card, face);
            if (count < 3) {
                continue;
            }
            float pay = SCRATCH_PRIZES[face] * SCRATCH_SIZES[Math.min(count, 6)];
            if (count == 3 && scratchInLine(card, face)) {
                pay *= SCRATCH_LINE_BONUS;
            }
            if (pay > best) {
                best = pay;
                winner = face;
            }
        }
        return winner;
    }

    // --- getting jumped ---------------------------------------------------------
    //
    // Handing product to somebody yourself is the risky way to make money, and
    // until now it was strictly the profitable one: dealers take a cut and get
    // robbed, customers and contracts paid full and cost nothing. This is the
    // other side of that trade. Selling it yourself means one day somebody
    // follows the money back to you.
    //
    // Deliberately NOT rolled for a dealer's sales. Paying a dealer to carry
    // is paying somebody else to take this risk, which is the whole reason to
    // have one.

    /** Chance of a stick-up on a cold, unknown, small deal. */
    public static final float STICKUP_BASE = 0.025f;
    /**
     * However bad it gets, most deals still go fine.
     *
     * A one-in-five sale ending in a firefight is not a risk, it is a toll,
     * and a toll makes dealing in person the wrong move at every rep -- which
     * would delete the feature this is meant to give teeth to.
     */
    public static final float STICKUP_CAP = 0.22f;

    /**
     * Odds that this deal was the one somebody was watching.
     *
     * Everything that makes you worth robbing pushes it up: being hot, being
     * known, moving a lot at once, and moving good product. Two things push it
     * down -- daylight, and not being on your own, which is the same company
     * rule Paranoia already runs on. Standing next to a friend to make a
     * handover should be the safe way to do it in both systems or it is a
     * rule players cannot learn.
     *
     * @param units    how much changed hands in this one exchange
     * @param gradeIndex the quality handed over, 0..n
     */
    public static float stickupChance(int heatTier, int rep, int units, int gradeIndex,
                                      boolean alone, boolean night) {
        float chance = STICKUP_BASE;
        chance += Math.max(0, heatTier) * 0.022f;
        chance += Math.min(0.040f, rep * 0.0016f);
        chance += Math.min(0.035f, units * 0.0025f);
        chance += Math.max(0, gradeIndex) * 0.006f;
        if (night) {
            chance *= 1.30f;
        }
        if (!alone) {
            chance *= 0.60f;
        }
        return Math.max(0.0f, Math.min(STICKUP_CAP, chance));
    }

    /**
     * Who turns up: {pillagers, vindicators, ravagers}.
     *
     * Never fewer than four bodies. Three is a patrol you walk away from and
     * the point of this is that dealing in person has teeth; the floor is the
     * feature, not a rounding artefact.
     */
    public static int[] stickupSquad(int rep, int heatTier, int gradeIndex, int units) {
        int strength = Math.min(8, Math.max(0,
                rep / 14 + Math.max(0, heatTier) + Math.max(0, gradeIndex) / 2 + units / 16));
        return new int[]{
                3 + strength / 2,
                1 + strength / 3,
                strength >= 8 ? 1 : 0};
    }

    // --- contract drop-offs -----------------------------------------------------

    /**
     * Where one job's buyer is waiting, as an offset from where the board was
     * drawn.
     *
     * A bearing and a distance rather than a random point in a square: a
     * square gives you corners, which means the far jobs cluster diagonally
     * and the near ones cluster on the axes. This is uniform in direction and
     * uniform in distance, which is what "somewhere out there, but not
     * absurdly far" actually means.
     *
     * Seeded rather than free-running so the board is the same board after a
     * relog. See TrapContracts.dropFor for what goes into the seed.
     */
    public static int[] dropOffset(long seed, int min, int max) {
        Random rng = new Random(seed);
        double bearing = rng.nextDouble() * Math.PI * 2.0;
        int range = min + rng.nextInt(max - min + 1);
        return new int[]{
                (int) Math.round(Math.cos(bearing) * range),
                (int) Math.round(Math.sin(bearing) * range)};
    }

    // --- the casino floor -------------------------------------------------------
    //
    // A player-owned machine may not accept a bet its vault cannot settle, or
    // a win would have to be conjured and the whole money supply stops adding
    // up. These two are the entire rule, kept here rather than in TrapHouse so
    // the overflow cases can be tested without a server.

    /** Can a vault holding this much settle this bet at this multiple? */
    public static boolean houseCovers(long balance, int stake, int topMultiple) {
        return balance >= (long) stake * topMultiple;
    }

    /**
     * The biggest bet a vault holding this much will take.
     *
     * Clamped to int because a stake is an int everywhere else, and a vault
     * big enough to overflow one is a vault whose limit is "anything".
     */
    public static int houseLimit(long balance, int topMultiple) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, balance) / topMultiple);
    }

    // --- the dealer network -----------------------------------------------------

    /** Levels a dealer can reach. */
    public static final int DEALER_MAX_LEVEL = 8;
    /**
     * Items sold to earn one level, at each level.
     *
     * Rescaled 2026-08-08. The old curve topped out at 3200 items, which at
     * the rate a dealer actually worked was several hundred hours -- not a
     * ladder, an asymptote, and the honest answer to "do they even level up?"
     * was "technically". A dealer now climbs it in about seventeen hours of
     * working, halving to nine for a boss with a name (see dealerLearnRate),
     * which is a long-term goal you can see moving.
     */
    public static final int[] DEALER_XP = {0, 25, 70, 150, 300, 550, 950, 1500};

    /** Slots a dealer of this level can carry. */
    public static int dealerSlots(int level) {
        return 2 + Math.max(1, Math.min(DEALER_MAX_LEVEL, level)) * 2;
    }

    /**
     * What a dealer takes off the top, as a share of what they sell.
     *
     * Rises with level. A better dealer costs more to hire AND keeps more of
     * every sale -- but shifts so much more product that they still leave you
     * further ahead. Making the cut fall with level would have meant there was
     * never a reason to keep a cheap one, and a market where one option is
     * strictly better isn't a choice.
     */
    public static float dealerCut(int level) {
        return 0.12f + 0.02f * Math.max(1, Math.min(DEALER_MAX_LEVEL, level));
    }

    /**
     * What hiring one costs up front, before your name is worth anything.
     *
     * Squared, so the top of the ladder is a project rather than a purchase.
     */
    public static int dealerHireCost(int level) {
        return 180 * level * level;
    }

    /**
     * What they'll actually take, given your standing.
     *
     * Rep is earned on contracts, so the courier work you did last week is
     * what gets you a good dealer cheap this week -- which is the point of
     * having two systems rather than two menus. Capped at 40% so reputation
     * makes the ladder climbable, not free.
     */
    public static final float REP_DISCOUNT_CAP = 0.40f;

    public static int dealerHireCost(int level, int rep) {
        float off = Math.min(REP_DISCOUNT_CAP, Math.max(0, rep) * 0.01f);
        return Math.max(1, Math.round(dealerHireCost(level) * (1.0f - off)));
    }

    /**
     * How much faster a well-connected boss's dealers learn the streets.
     *
     * Same idea from the other end: rep opens doors, and a dealer working for
     * somebody with a name gets introduced to people a nobody's dealer has to
     * find alone.
     */
    public static float dealerLearnRate(int rep) {
        return 1.0f + Math.min(1.0f, Math.max(0, rep) * 0.015f);
    }

    /**
     * How briskly trade moves at this hour, as a multiplier.
     *
     * Peaks around midnight and bottoms out at noon. Nobody buys anything off
     * a street dealer at lunchtime, and the whole point of the trade running
     * while you are not watching is that it has a rhythm you can plan around:
     * load them up in the evening, collect in the morning.
     *
     * @param timeOfDay the world's 0..23999
     */
    public static float dealerHourFactor(long timeOfDay) {
        // 0 is sunrise in Minecraft, 18000 is midnight.
        double phase = (timeOfDay % 24000L) / 24000.0 * Math.PI * 2.0;
        // Trough at 6000 (noon), peak at 18000 (midnight).
        return 1.0f + 0.55f * (float) -Math.sin(phase);
    }

    /**
     * Everything else that decides how fast a dealer moves product.
     *
     * Saturation is the important one: each extra dealer working the same
     * patch sells less than the last, so a fourth is worth much less than a
     * first. Without it the only strategy is "hire the maximum", which is not
     * a strategy.
     *
     * @param level    theirs, 1..MAX
     * @param crowd    how many dealers this player has out, including this one
     * @param heatTier 0..3, how much attention the operation is drawing
     */
    public static float dealerRate(int level, int crowd, int heatTier) {
        // Was 0.25 + 0.25*level, which gave a level one half an item per
        // five-minute round: six an hour, for a dealer who cost 180e. You
        // could watch one for twenty minutes and see two sales, and two sales
        // in twenty minutes does not read as slow, it reads as broken.
        float skill = 0.6f + 0.45f * level;
        float saturation = 1.0f / (1.0f + 0.45f * Math.max(0, crowd - 1));
        // Heat doesn't stop trade, it makes people careful about being seen
        // buying. A raid is the punishment; this is the drag.
        float caution = 1.0f - 0.12f * Math.max(0, Math.min(3, heatTier));
        return skill * saturation * caution;
    }

    /**
     * Items a dealer shifts in one round, given everything.
     *
     * Rounded stochastically off `roll` rather than truncated: a rate of 0.4
     * has to mean "two rounds in five" and not "nothing, ever", or a level one
     * dealer at noon would look broken rather than slow.
     */
    public static int dealerSold(float rate, float hourFactor, float roll) {
        float expected = rate * hourFactor;
        int whole = (int) expected;
        return whole + (roll < expected - whole ? 1 : 0);
    }

    /**
     * Odds a dealer gets taken off for part of what they're carrying, per round.
     *
     * Per ROUND, and rounds are two minutes apart -- so a level one runs about
     * a nine percent risk over an hour on the street and a level five under
     * two. Often enough that a cheap dealer carrying your best product is a
     * bad idea, rare enough that it reads as bad luck rather than as a tax.
     *
     * Halved when the round interval was, so shortening the clock made them
     * work faster rather than get robbed more.
     */
    public static float dealerRobChance(int level) {
        return 0.003f / Math.max(1, Math.min(DEALER_MAX_LEVEL, level));
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
