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
     * Beats are thirty seconds, so this is a half-life of about seventy
     * minutes of play. Slow enough that a jackpot is still being felt an hour
     * later -- which is the entire point of having an index -- and fast enough
     * that a session can watch a shock arrive and go.
     *
     * Lower means hotter: the anchor lags further behind a growing supply, so
     * a server that keeps earning reads as inflation instead of as the new
     * normal. A supply growing g per beat holds the index at about
     * 1 + g/(2*DRAG), so halving this roughly doubles the reading. Was 0.010
     * until 2026-08-12, where a world growing ~20% an hour sat at 108% and
     * looked to the people living in it like a broken gauge.
     */
    public static final float BASELINE_DRAG = 0.005f;
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
     * The price level: what a day-one price costs in today's money.
     *
     * The index breathes and always comes home -- that is deliberate, and it
     * is also why a server that has earned four hundred thousand emeralds
     * still pays day-one prices for everything and finds the game easy. Mean
     * reversion says "this much money is the new normal"; it has no way to say
     * "and so a loaf costs more than it did in the first week".
     *
     * This is the part that never comes home. It follows the world's REAL
     * wealth -- the money supply divided by the level it has already been
     * pushed to -- so it cannot chase its own tail: prices rising makes wages
     * and takings rise with them, which would otherwise read as more wealth
     * and push prices again. Deflated, a board that has already doubled needs
     * the world to genuinely get richer before it doubles again.
     *
     * Sub-linear on purpose. At {@link #LEVEL_ELASTICITY} an economy has to
     * grow tenfold to roughly double the board, so the treadmill flattens
     * instead of running away, and the cap is a guard rail rather than a
     * design.
     */
    public static final float LEVEL_MAX = 10.0f;
    /** How hard real wealth lifts the board. 1 would be pure inflation. */
    public static final float LEVEL_ELASTICITY = 0.35f;
    /**
     * How fast the board may climb, per beat.
     *
     * Beats are thirty seconds, so this is about a quarter of a flat price an
     * hour of play: fast enough to notice between sessions, slow enough that
     * nobody is quoted a different number for the same stack twice in one
     * shopping trip. Turning this up is how you make a server that has run
     * away from its prices catch up faster.
     */
    public static final float LEVEL_RISE = 0.002f;
    /** And how fast it may fall. Deflation is the slow direction. */
    public static final float LEVEL_FALL = 0.0006f;
    /** How far a whole shelf may wander from the rest of the board. */
    public static final float SECTOR_DRIFT = 0.10f;
    /** Beats one shelf's mood lasts. Forty is about a Minecraft day. */
    public static final int SECTOR_WINDOW = 40;
    /** How far the town's own money may bid the board, either way. */
    public static final float BIDDING = 0.12f;

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
     * Where the board is heading, from the world's real wealth.
     *
     * Real, not nominal: the supply is divided by the level it has already
     * paid for. A world holding 400,000e at a board of 1.0 is rich and gets
     * dearer; the same world holding 2,400,000e at a board of 6.0 is the same
     * world and stops there.
     */
    public static float priceTarget(float level, float baseline) {
        float real = baseline / Math.max(1.0f, level);
        return Math.min(LEVEL_MAX,
                (float) Math.pow(Math.max(1.0f, real / MARKET_BASELINE), LEVEL_ELASTICITY));
    }

    /**
     * Where the board comes to rest for a world of this size.
     *
     * The fixed point of {@link #priceTarget} -- the level at which the target
     * IS the level, so nothing moves any more. Worth deriving rather than
     * quoting the per-beat target at people: that one falls as the board
     * climbs to meet it, which is correct and reads as a broken gauge. "Headed
     * for x6.2" today and x5.1 tomorrow, with nothing having gone wrong.
     */
    public static float priceRest(float baseline) {
        double ratio = Math.max(1.0f, baseline / MARKET_BASELINE);
        return (float) Math.min(LEVEL_MAX,
                Math.pow(ratio, LEVEL_ELASTICITY / (1.0 + LEVEL_ELASTICITY)));
    }

    /**
     * One beat of the board creeping towards that.
     *
     * Never below 1: the flat prices in the catalogue are the floor, because
     * they were written as the price of the thing rather than as a starting
     * offer, and a world that loses all its money should get cheap by the
     * index and then stop.
     */
    public static float levelAfter(float level, float baseline) {
        return Math.max(1.0f,
                approach(level, priceTarget(level, baseline), LEVEL_RISE, LEVEL_FALL));
    }

    /**
     * What the town's own money does to prices.
     *
     * Takes the number the shops already trade on -- see the other
     * {@link #townDemand}, which is emeralds per head against
     * {@link #COMFORTABLE} -- rather than counting heads again here. A town
     * with wages in its pocket is competing with the player for the same
     * shelf and bids it up; a town with an empty purse is not, and prices
     * slide. Two ways of measuring the same town is how a mod ends up
     * arguing with itself.
     *
     * Symmetric around a comfortable town, so this is a swing rather than a
     * tax: the whole point is that funding the payroll and starving it are
     * both visible on the board.
     */
    public static float bidding(float townDemand) {
        return 1.0f + BIDDING
                * (Math.min(DEMAND_CAP, Math.max(0.0f, townDemand)) - 1.0f);
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
        return walk(beat, itemId, DRIFT_WINDOW, DRIFT);
    }

    /**
     * A whole shelf's mood, on a much slower clock.
     *
     * Per-item drift is noise: everything wobbles, nothing means anything, and
     * a board where every line moves independently is a board nobody reads.
     * This is the part that has a story in it -- ore is up this week, timber
     * is down -- because it moves a whole category together over about a
     * Minecraft day, which is long enough to be worth acting on and short
     * enough to catch.
     *
     * Keyed off the category rather than the item, so stone and bricks agree
     * with each other, and namespaced so a category called "food" and an item
     * called "food" can never collide.
     */
    public static float sector(long beat, String categoryId) {
        return walk(beat, "sector:" + categoryId, SECTOR_WINDOW, SECTOR_DRIFT);
    }

    /** One key walking between random targets, eased so nothing lurches. */
    private static float walk(long beat, String key, int window, float amplitude) {
        long era = Math.floorDiv(beat, window);
        float phase = Math.floorMod(beat, (long) window) / (float) window;
        float from = wobbleBy(era, key, amplitude);
        float to = wobbleBy(era + 1, key, amplitude);
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
        return wobbleBy(seed, key, DRIFT);
    }

    private static float wobbleBy(long seed, String key, float amplitude) {
        int hash = mix((int) seed * 31 + key.hashCode());
        // 0..1 from the low bits, then mapped onto +/- the amplitude.
        float unit = (hash >>> 8 & 0xFFFF) / (float) 0xFFFF;
        return 1.0f + (unit * 2.0f - 1.0f) * amplitude;
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

    /**
     * Never free, whatever the market does.
     *
     * Three multipliers rather than a named list of forces, because the forces
     * keep being added to: {@code board} is everything that moves the whole
     * catalogue at once (the index, the price level, the town), {@code own} is
     * everything that moves this line alone (its drift, its shelf's mood), and
     * {@code flow} is what the last few minutes of orders did to it. Composed
     * by the caller in {@link TrapMarket}, which is the only place that knows
     * what exists.
     */
    public static int buyPrice(int base, float board, float own, float flow) {
        return Math.max(1, Math.round(base * board * own * flow));
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

    /**
     * What a hand tips into a laundry drum out of a chest holding both.
     *
     * Blocks first, and whole ones only: a block is nine and there is no such
     * thing as tipping in a third of one, so a drum with four spaces left takes
     * loose emeralds even with a stack of blocks sitting next to them. Greedy
     * on the blocks is never worse than the alternative -- nine in one go
     * cannot overshoot a gap that had room for it.
     *
     * Returns {blocks, loose}, which is what comes OUT of the chest; the drum
     * gets {@code blocks * 9 + loose}. Those two disagreeing is money appearing
     * or vanishing, which is why this is here and not inline at the drum.
     */
    public static int[] drumLoad(int blocks, int loose, int room) {
        if (room <= 0) {
            return new int[]{0, 0};
        }
        int lots = Math.min(Math.max(blocks, 0), room / 9);
        return new int[]{lots, Math.min(Math.max(loose, 0), room - lots * 9)};
    }

    // --- the casino floor -----------------------------------------------------

    /**
     * What every machine on the floor will take, and what roulette stacks.
     *
     * One ladder, in one place, because seven cabinets disagreeing about what a
     * bet is would be seven bugs waiting. The top of it is deliberately out of
     * reach of most houses: {@link #houseCovers} refuses a stake the float
     * cannot pay out, so a 4096e button on a small casino is a button that says
     * no. That is the table limit doing its job, not a broken option.
     *
     * Roulette gets its own, one notch finer and one notch lower, because a
     * chip there is laid per field and a board carries many of them.
     */
    public static final int[] STAKES = {8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096};
    public static final int[] CHIPS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};

    /** Step the stake button: forward on a left click, back on a right one. */
    public static int cycle(int choice, int length, boolean back) {
        return (choice + (back ? length - 1 : 1)) % length;
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

    // --- somebody else's stall --------------------------------------------------

    /**
     * What a player's stall charges, as a share of the market's own price.
     *
     * The number that makes supplying each other worth doing, and it has to
     * beat the counter from BOTH sides at once or nobody bothers. The market
     * buys at {@link #SELL_RATE} and sells at full price, so the spread
     * between those two is dead money -- 55% of everything, paid to nobody.
     * A stall splits that spread between the two players instead.
     *
     * On a 100e line: the counter pays a grower 45e and charges a builder
     * 100e. Through a stall the builder pays 85e and the grower keeps 81e.
     * Both are far better off than they were, neither has taken anything from
     * the other, and the market is still there at full price for anything
     * nobody happens to be stocking. That is the whole design -- the open
     * market is the backstop, and each other is the bargain.
     */
    public static final float STALL_RATE = 0.85f;

    /**
     * The cut that does not reach the seller.
     *
     * Small, and it is a SINK -- the one thing a player-to-player trade would
     * otherwise not have. Every other transfer in this mod either creates
     * money or destroys it and tells {@link TrapMarket#circulate} about it; a
     * pure transfer tells it nothing, so a city trading briskly with itself
     * would inflate the supply by exactly nothing and deflate it by exactly
     * nothing forever. Five percent off the top keeps the stalls attached to
     * the same economy as everything else.
     */
    public static final float STALL_FEE = 0.05f;

    /** What a shopper hands over at somebody's stall. */
    public static int stallPrice(int marketBuyPrice) {
        return Math.max(1, Math.round(marketBuyPrice * STALL_RATE));
    }

    /** What the owner keeps of it. */
    public static int stallTake(int stallPrice) {
        return Math.max(1, stallPrice - Math.round(stallPrice * STALL_FEE));
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

    /**
     * Random ticks an opium poppy waits per stage. The slowest thing you can
     * plant, and flatly slower than a watered cannabis plant.
     *
     * Deliberately the wrong shape for a min-maxer: there is no watering trick
     * and no grade to chase, so the only lever on a poppy field is how big it
     * is. That is the whole design of the long line -- the money is real, and
     * the way you earn it is by committing acreage and daylight to it for an
     * afternoon rather than by playing any single step cleverly. Coca is 6 and
     * a watered weed plant is 13; this is 16, on top of needing light
     * {@link PoppyCropBlock#NEEDS_LIGHT} rather than 9.
     */
    public static final int POPPY_GROWTH_ROLLS = 16;

    // --- the habit --------------------------------------------------------------
    //
    // The formula lives here rather than in TrapAddiction for the same reason
    // everything else in this file does: this class imports nothing from
    // Minecraft, so it is the only part of the habit that can be tested without
    // starting a game. The per-drug numbers stay on Drug, which is a table; what
    // is here is the one piece that is actually arithmetic.

    /** Meter floors, as a fraction of the worst possible pressure. */
    public static final float HABIT_ITCH = 0.20f;
    public static final float HABIT_CRAVE = 0.45f;
    public static final float HABIT_SICK = 0.72f;

    /** Bands, by index, matching TrapAddiction.Band's ordinals. */
    public static final int BAND_CLEAN = 0;
    public static final int BAND_ITCH = 1;
    public static final int BAND_CRAVING = 2;
    public static final int BAND_SICK = 3;

    /**
     * How badly somebody wants it right now.
     *
     * A product, not a timer, and that is the whole design:
     *
     * <pre>pressure = (hooked / max) * min(1, sinceUse / period)</pre>
     *
     * Both factors have to be large for the result to be. A meter of 30 can
     * never exceed 0.30 however long you abstain, which puts every band above
     * it permanently out of reach -- so being ill is something you have to have
     * earned, and a light user genuinely cannot stumble into withdrawal by
     * going on holiday.
     *
     * @param hooked        the meter, 0..max
     * @param max           the top of the meter
     * @param sinceUseTicks ticks since the last hit of this specific thing
     * @param periodMinutes real minutes for the craving to reach full ripeness
     */
    public static float habitPressure(float hooked, float max, long sinceUseTicks,
                                          int periodMinutes) {
        if (hooked <= 0 || max <= 0 || periodMinutes <= 0) {
            return 0f;
        }
        float period = periodMinutes * 60f * 20f;
        float ripeness = Math.min(1f, Math.max(0f, sinceUseTicks / period));
        return Math.min(1f, hooked / max) * ripeness;
    }

    /** Which band a pressure falls in. See the constants above. */
    public static int habitBand(float pressure) {
        if (pressure >= HABIT_SICK) {
            return BAND_SICK;
        }
        if (pressure >= HABIT_CRAVE) {
            return BAND_CRAVING;
        }
        if (pressure >= HABIT_ITCH) {
            return BAND_ITCH;
        }
        return BAND_CLEAN;
    }

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
        //
        // The floor is 0.35 and was 0.12, which was shut in all but name --
        // measured across a live day the noon hours ran 568e of trade a beat
        // against 52e of bill, the worst stretch of the cycle by a distance.
        // A quiet afternoon is the shape that was wanted; an empty one is not.
        return Math.max(0.35f, 1.0f + 0.95f * swing);
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
     * is cheap. Which is both true of real rooms and the thing that keeps a
     * busy night from being simply a bigger night: you trade the size of the
     * bets for the number of them.
     *
     * Rescaled 2026-08-13 for the room this game actually has. The old bands
     * ran out at SEVEN -- they were written when a floor held a handful and
     * the town was a dozen -- and by the time the town reached forty every
     * punter on every night was pinned to the bottom band. Measured across two
     * full days either side of the arrivals fix: the crowd doubled, the takings
     * did not, and the average stake moved 14.3e to 14.4e. A ceiling that is
     * always in force is not a ceiling, it is a price.
     *
     * Same shape, spread over the range that now happens. Twenty in the room
     * is still the cheap floor it is supposed to be.
     */
    public static int punterStakeCeiling(int crowd) {
        if (crowd <= 2) {
            return 5;   // up to 256e, average 39e
        }
        if (crowd <= 5) {
            return 4;   // up to 128e, average 36e
        }
        if (crowd <= 10) {
            return 3;   // up to 64e,  average 29e
        }
        if (crowd <= 18) {
            return 2;   // up to 32e,  average 21e
        }
        return 1;       // up to 16e,  average 14e
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
    /** Housed people it takes to fill a floor. */
    public static final int PULL_AT = 12;
    /** Trade a floor draws off passers-by with no town behind it. */
    public static final float PULL_FLOOR = 0.15f;

    /**
     * How much trade a floor draws, and where that trade comes FROM.
     *
     * The population term is the point. This used to start at 0.55 with no
     * reference to the world at all, so a casino in an empty wilderness pulled
     * over half the trade of one in a city -- and a floor left running earned
     * about fifteen hundred an hour off customers who came from nowhere,
     * which is a faucet with a felt top.
     *
     * Punters are PEOPLE. People live in houses. A town of {@link #PULL_AT}
     * housed grades fills a floor; no town at all leaves you the fifteen
     * percent who were walking past anyway. Reputation and addiction still do
     * the rest of the work, and they still have to be earned.
     *
     * @param population housed grades, from TrapHomes
     */
    public static float floorPull(int rep, int addiction, int population) {
        float known = Math.max(0, Math.min(HOUSE_STAT_MAX, rep)) / (float) HOUSE_STAT_MAX;
        float hooked = Math.max(0, Math.min(HOUSE_STAT_MAX, addiction)) / (float) HOUSE_STAT_MAX;
        float town = PULL_FLOOR + (1.0f - PULL_FLOOR)
                * Math.min(1.0f, Math.max(0, population) / (float) PULL_AT);
        return town * (0.55f + 0.85f * known + 0.60f * hooked);
    }

    /** Emeralds a head in the town purse that means a town is comfortably off. */
    public static final int COMFORTABLE = 200;
    /** The most a flush town can multiply its own custom by. */
    public static final float DEMAND_CAP = 2.0f;

    /**
     * How hard the town is shopping, against how hard it shops when comfortable.
     *
     * The purse alone would make a big poor town look rich, so this is per
     * head: twenty people with 4000e between them are comfortable, and two
     * hundred people with the same 4000e are not.
     *
     * Capped because the alternative is a town that got lucky once and bought
     * out every shop on the server forever. The cap is also what makes the
     * whole loop stable -- spending rises with the purse, which lowers the
     * purse -- so a wrong {@link HomeSurvey#WAGE_MULTIPLE} is a slow town or a
     * busy one, never a runaway one.
     */
    public static float townDemand(long purse, int people) {
        if (people <= 0 || purse <= 0) {
            return 0f;
        }
        return Math.min(DEMAND_CAP, (purse / (float) people) / COMFORTABLE);
    }

    /** Wear at which a cabinet starts letting people down. */
    public static final int JAM_FROM = 45;

    /**
     * Odds a worn cabinet swallows somebody's money and they walk out.
     *
     * Wear used to cost nothing but a rep point, which meant a hammer was a
     * chore with no consequence and a floor of half-dead machines earned the
     * same as a floor of new ones. Now a shabby cabinet turns trade away at
     * the door, so the repair pays for itself in the only currency that
     * matters: punters who actually play.
     */
    public static float jamChance(int wear) {
        if (wear < JAM_FROM) {
            return 0f;
        }
        return 0.45f * (wear - JAM_FROM) / (float) (WEAR_BROKEN - JAM_FROM);
    }

    /** What one wired machine costs to keep lit, per beat, with somebody on it. */
    public static final int MACHINE_UPKEEP = 1;

    /**
     * ...and what one nobody is sitting at costs instead.
     *
     * A flat emerald a cabinet made the machine count a CEILING rather than a
     * decision: the bill is linear in machines and the trade is not, so past
     * some number no floor breaks even at any hour, and the only advice
     * anybody could give an owner was "own fewer machines". Measured on the
     * live floor 2026-08-13: 47 cabinets, 28 ever occupied at the dusk peak,
     * 19 of them never touched across a whole day at an emerald a beat each.
     *
     * A dark cabinet is a dark cabinet. It costs a quarter, so an over-built
     * floor is carrying dead weight rather than bleeding to death, and the
     * answer to "should I build another one" stops being no.
     *
     * A BROKEN one still costs full price -- it is not free, it is out of
     * order, and the hammer is cheap. That is deliberate.
     */
    public static final float IDLE_UPKEEP = 0.25f;

    /**
     * The lights bill for a floor of this many, this many of them standing
     * empty. One place, so the vault screen and the beat cannot disagree.
     */
    public static int upkeepOn(int machines, int free) {
        int dark = Math.max(0, Math.min(machines, free));
        return Math.round(Math.max(0, machines - dark) * MACHINE_UPKEEP
                + dark * IDLE_UPKEEP);
    }
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
     *
     * Was 15, which was sized against a floor turning over sixty rounds a
     * beat. At the seventy-five the live floor now runs, parts were the single
     * biggest line on the bill -- 15e a beat against 27e of lights and wages
     * -- and the repair notices arrived faster than anybody could walk the
     * room. Twenty-five keeps the hammer a job and takes parts down to 9e.
     */
    public static final int WEAR_PER_ROUNDS = 25;
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

    /**
     * Punters one item off the shelf covers.
     *
     * A bud is a few joints and a loaf is a few rounds of sandwiches, not one
     * of each. Taking a whole item per head drank a stack of product in about
     * six minutes of a busy floor -- faster than any farm on the server
     * fills, so the bar was never stocked and the room was always dry.
     */
    public static final int SERVINGS_PER_ITEM = 5;

    /** What one serving is worth to the regulars. */
    public static final int BAR_ADDICTION_PRODUCT = 3;
    public static final int BAR_ADDICTION_FOOD = 1;
    /** What a dry bar takes off the name it was holding. */
    public static final int DRY_BAR_REP = 28;

    /**
     * Points off a punter's return for what they were handed at the door.
     *
     * Somebody four rounds in is not playing the game they walked in playing.
     * They chase, they stay in on a hand they should have dropped, and they
     * cannot count anything. Nothing about the machine changes -- the plate on
     * the cabinet is still honest -- the person in front of it does.
     *
     * This is what makes the bar a business rather than a bar tab. Product
     * bought VOLUME and nothing else (see {@link #SERVED_PRODUCT}), and volume
     * against a three percent plate is nothing at all once the lights, the
     * cut, the parts and the occasional card counter have been paid: a
     * modelled floor of four machines with product behind the counter and
     * nobody watching the door ran at +21e a day, which is zero with a wide
     * error bar round it. Four points of return is the difference between that
     * and a room worth opening, and it is paid for out of the farm -- in
     * product that could have gone to a dealer instead.
     *
     * Never applied to a loose spell. Running loose is a promise to lose money
     * for six minutes, and a promise with an asterisk on it is not one.
     */
    public static final float SERVED_EDGE_PRODUCT = 0.04f;
    public static final float SERVED_EDGE_FOOD = 0.01f;

    /** What the door has taken off this punter's return. */
    public static float servedEdge(int servedTier) {
        return switch (servedTier) {
            case 2 -> SERVED_EDGE_PRODUCT;
            case 1 -> SERVED_EDGE_FOOD;
            default -> 0f;
        };
    }

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
     * Reputation is the boss's, and it multiplies on the same curve the
     * contract board pays on -- up to 1.75x at REP_MAX. It is the same
     * reputation, so it should be worth the same: a dealer working for
     * somebody people have heard of is sent customers rather than having to
     * talk every one of them into it. Rep already made them LEARN faster,
     * which is a promise that pays off in an hour; this is the half that
     * pays off in the next round.
     *
     * @param level    theirs, 1..MAX
     * @param crowd    how many dealers this player has out, including this one
     * @param heatTier 0..3, how much attention the operation is drawing
     * @param rep      the boss's standing, 0..REP_MAX
     */
    public static float dealerRate(int level, int crowd, int heatTier, int rep) {
        // Was 0.25 + 0.25*level, which gave a level one half an item per
        // five-minute round: six an hour, for a dealer who cost 180e. You
        // could watch one for twenty minutes and see two sales, and two sales
        // in twenty minutes does not read as slow, it reads as broken.
        float skill = 0.6f + 0.45f * level;
        float saturation = 1.0f / (1.0f + 0.45f * Math.max(0, crowd - 1));
        // Heat doesn't stop trade, it makes people careful about being seen
        // buying. A raid is the punishment; this is the drag.
        float caution = 1.0f - 0.12f * Math.max(0, Math.min(3, heatTier));
        float name = 1.0f + standing(rep) * REP_STEP;
        return skill * saturation * caution * name;
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
        return 0.00225f / Math.max(1, Math.min(DEALER_MAX_LEVEL, level));
    }

    // --- the crew's clock -------------------------------------------------------

    /**
     * How much of a shift is the breather.
     *
     * A SHARE, not a fixed number of ticks, and the difference is the whole
     * bug. The breather used to be forty-five seconds flat however fast the
     * hand worked, so buying pace bought a worse and worse duty cycle: a
     * plodding hand worked 73% of its shift and a flat-out one worked 29% of
     * its shift. The board advertised "a job every 1.5 seconds" and delivered
     * one every 5.25 -- three and a half times slower -- and the top rung, the
     * one that costs 2200e, was punished hardest for being bought.
     *
     * A quarter of the work done means every rung has the same duty cycle, so
     * the ladder now buys exactly the speed it says it does.
     */
    public static final float CREW_BREAK_SHARE = 0.25f;

    /**
     * What the next rung of a crew ladder costs, given the highest rung ever
     * bought for that hand.
     *
     * A rung is bought once. Dropping back down is free and refunds nothing,
     * so climbing back to somewhere you have already been is free too --
     * otherwise "turn this one down while the farm is idle" would be a
     * decision that costs 2200e to undo, and nobody would ever make it.
     *
     * @param rung the rung being moved TO
     * @param peak the highest rung this hand has ever been sold
     */
    public static int crewRungCost(int[] costs, int rung, int peak) {
        return rung <= peak ? 0 : costs[rung];
    }

    /** Ticks a hand stands about for after a shift at this pace. */
    public static int crewBreak(int interval, int jobsPerShift) {
        return Math.max(20, Math.round(interval * jobsPerShift * CREW_BREAK_SHARE));
    }

    /**
     * Seconds a job REALLY takes, breather included.
     *
     * The only number the board and the handbook are allowed to print. The
     * raw pass interval is a truth about the tick loop and a lie to the
     * player, which is what made a hand feel like it stopped working before
     * it had earned its wage.
     */
    public static float crewJobSeconds(int interval, int jobsPerShift) {
        return (interval * jobsPerShift + crewBreak(interval, jobsPerShift))
                / (jobsPerShift * 20.0f);
    }

    // --- room on the books --------------------------------------------------
    //
    // Two lines of arithmetic, here rather than in the crew, because both of
    // them are read off a SAVED number and a saved number is whatever the
    // file says. A count written by a build with a longer ladder must not
    // hand out places the board cannot show, and asking a spent ladder what
    // the next rung costs must answer "there isn't one" rather than throwing
    // in the middle of somebody's purchase.

    /** How many hands a boss who has bought {@code bought} places may have. */
    public static int crewCap(int free, int bought, int ladder) {
        return free + Math.max(0, Math.min(bought, ladder));
    }

    /** What the next place costs, or 0 when the ladder is spent. */
    public static int crewPlaceCost(int[] ladder, int bought) {
        return bought < 0 || bought >= ladder.length ? 0 : ladder[bought];
    }

    /**
     * A pile of emeralds as blocks and loose change.
     *
     * Nine to the block, which is vanilla's own rate and the one the drum
     * already reads its dirty money at. Here rather than inline because it is
     * the one place in the mod where money changes SHAPE, and a division that
     * quietly drops the remainder is a way of deleting emeralds that no test
     * of the laundry would ever notice.
     *
     * @return {blocks, loose}, and {@code blocks * 9 + loose} is what went in
     */
    public static int[] denominate(int emeralds) {
        int whole = Math.max(0, emeralds);
        return new int[]{whole / 9, whole % 9};
    }

    /**
     * Which bosses have just started or finished a shift, and with how many.
     *
     * Here rather than in the crew because it is a state machine and state
     * machines are what go quietly wrong: the failures are a line every second
     * instead of one at dawn, or a line about people who were fired an hour
     * ago. Both are cheap to check and neither is visible in a screenshot.
     *
     * {@code known} is what the caller last saw -- present means on shift, the
     * value is how many were out -- and it is updated in place. {@code up} is
     * every boss with anybody on the books at all, against how many of them
     * are in daylight this second. The answer is one entry per boss worth
     * telling: positive for a shift starting, negative for one ending.
     *
     * An ending carries the count from {@code known} on purpose. By the time
     * the sun is down, "how many are out" is zero, and zero is not the number
     * anybody wants read back to them.
     */
    public static <K> Map<K, Integer> shiftBells(Map<K, Integer> known, Map<K, Integer> up) {
        // Firing the lot at noon is not a shift ending, so a boss who no
        // longer has anybody is dropped rather than rung.
        known.keySet().retainAll(up.keySet());

        Map<K, Integer> bells = new LinkedHashMap<>();
        for (Map.Entry<K, Integer> boss : up.entrySet()) {
            int now = boss.getValue();
            Integer was = known.get(boss.getKey());
            if (now > 0) {
                // Hiring mid-shift moves the number without ringing anything.
                known.put(boss.getKey(), now);
                if (was == null) {
                    bells.put(boss.getKey(), now);
                }
            } else if (was != null) {
                known.remove(boss.getKey());
                bells.put(boss.getKey(), -was);
            }
        }
        return bells;
    }

    // --- the coin toss ----------------------------------------------------------

    /**
     * Heads, tails, and the third thing.
     *
     * A coin toss is the most boring bet there is, which is exactly why this
     * one has an edge: about three tosses in a hundred the coin comes down on
     * its rim, and anybody who called it takes thirty-two times their stake.
     * Nobody wins it. Everybody tries it once.
     *
     * The two sensible bets and the silly one carry the same house edge to
     * within half a percent, so calling the edge is a genuine choice about
     * variance rather than a trap. Which is why the pay and the chance move
     * TOGETHER: halving the payout to 32x on its own would price the rim at
     * 48% and turn the one bet on the floor that is supposed to be a choice
     * into the trap the paragraph above says it is not. Rim return is
     * CHANCE * PAY and it stays at 0.96.
     *
     * The side pay is 1.99 rather than 1.96 for the same reason from the
     * other end: a rim that comes up twice as often takes three quarters of a
     * percent out of the space heads and tails share, and without the bump
     * the ordinary bet quietly drops from 0.965 to 0.951.
     */
    public static final float TOSS_EDGE_CHANCE = 0.03f;
    public static final float TOSS_SIDE_PAY = 1.99f;
    public static final float TOSS_EDGE_PAY = 32.0f;

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

    /**
     * Turn a box built facing north through `spin` degrees about Y.
     *
     * Returns {x0, y0, z0, x1, y1, z1} in the 0..16 model grid. Lives here
     * rather than in {@link TurnableBlock} for the usual reason: it is the one
     * part of turning a block that can be silently WRONG rather than
     * obviously broken, and here it can be tested without a game.
     *
     * The direction has to match what the model does, which is a vanilla
     * blockstate y-rotation: clockwise seen from above, so north goes to east.
     * Get the sign backwards and a bong you can see poking out to the right is
     * one you have to click on the left -- nothing throws, nothing logs, and
     * the only symptom is players missing the block.
     */
    public static double[] turn(int spin, double x0, double y0, double z0,
                                double x1, double y1, double z1) {
        return switch (((spin % 360) + 360) % 360) {
            case 90 -> new double[] {16 - z1, y0, x0, 16 - z0, y1, x1};
            case 180 -> new double[] {16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0};
            case 270 -> new double[] {z0, y0, 16 - x1, z1, y1, 16 - x0};
            default -> new double[] {x0, y0, z0, x1, y1, z1};
        };
    }

    // --- giving it away ---------------------------------------------------------

    /**
     * Move a donation dial by one step, and keep it inside the purse.
     *
     * Here rather than on the screen for the usual reason: this clamp is the
     * only thing standing between a stepper and a number the city cannot
     * cover, and it does its arithmetic in long precisely so a dial nudged
     * past two billion wraps to nothing instead of to a fortune.
     */
    public static int stepped(int amount, int step, long cap) {
        long ceiling = Math.max(0L, Math.min(cap, Integer.MAX_VALUE));
        return (int) Math.max(0L, Math.min((long) amount + step, ceiling));
    }

    // --- law and disorder -------------------------------------------------------

    /**
     * Rolls {@link TrapCrime} takes at the town in one in-game day.
     *
     * 24000 ticks over a 240-tick round. Written down rather than inlined
     * because it is the conversion between "crimes a day", which is the only
     * number anybody can reason about, and "odds per round", which is the only
     * one the tick loop can use -- and getting it wrong by a factor of a
     * hundred is a town that either never has a burglary or has nothing else.
     */
    public static final float CRIME_ROUNDS_PER_DAY = 100f;

    /**
     * Offences a day per hundred residents, before anything modifies it.
     *
     * Was 9, and 9 was measured wrong. The arithmetic said 1.8 a day in a town
     * of twenty, which sounded fine -- but the modifiers were MULTIPLICATIVE,
     * so a poor town at night with a farm running hot got 1.8 x 2 x 1.7 x 2 =
     * TWELVE, and an in-game day is twenty real minutes. Live result: a
     * murder and two burglaries inside seven minutes, which is not a city with
     * a crime problem, it is a city being demolished.
     *
     * Three and a half is 0.7 a day in that same town of twenty -- one thing
     * happens roughly every half hour of play, and the modifiers below can no
     * longer stack past {@link #CRIME_CEILING}.
     */
    public static final float CRIME_BASE = 3.5f;

    /**
     * The most offences a day a town can produce, however bad it gets.
     *
     * Not a tuning knob -- a backstop. Population is unbounded and the
     * modifiers are not, so without this a big unhappy city walks itself into
     * a rate nothing could police and nobody could enjoy. A town that hits
     * this is already telling its owner something is wrong; it does not also
     * need to be unplayable.
     */
    public static final float CRIME_CEILING = 2.0f;

    /** What the dark does to all of it. */
    public static final float NIGHT_CRIME = 1.5f;

    /**
     * The most poverty and a hot drug trade can add BETWEEN them.
     *
     * Added, not multiplied, and that is the whole of the fix. Two 2x
     * multipliers on top of a night 1.7x is a 6.8x swing, which is not a
     * simulation, it is a dice tower -- and a player cannot reason about a
     * number that moves by seven times for reasons three systems apart.
     */
    public static final float CRIME_HARDSHIP_LIFT = 0.6f;
    public static final float CRIME_HEAT_LIFT = 0.5f;

    /** Server heat at which the drug trade contributes all it is going to. */
    public static final int CRIME_HEAT_FULL = 400;

    /** Share of muggings that put somebody in a hospital bed. */
    public static final float HOSPITALISED = 0.35f;

    /**
     * Share of killings that are killings.
     *
     * The rest are found in time and go to a ward, which is where the two
     * offices meet: a city with a hospital loses fewer people to the same
     * number of stabbings. Under a half on purpose -- if every one of these
     * were fatal the ward would be irrelevant to crime, and if none were then
     * "murder" would be a word for an expensive hospital visit.
     */
    public static final float MURDER_FATAL = 0.45f;

    /** The most of a town's crime a police force can ever hold down. */
    public static final float TOP_DETERRENCE = 0.75f;

    /** A copper's pace, and a runner's, so the chase can be reasoned about. */
    public static final double OFFICER_PACE = 0.5;
    public static final double OFFICER_PACE_PER_GEAR = 0.055;
    /**
     * How fast somebody runs from a crime scene.
     *
     * Deliberately between an unequipped officer (0.50) and a fully kitted one
     * (0.665): a force with no budget can watch a suspect walk away and never
     * close, and one the city pays for runs them down. That gap IS the
     * feature -- it is what "and faster" on the dial buys, in the only place
     * where a player can watch it happen.
     */
    public static final double SUSPECT_PACE = 0.575;

    /**
     * Odds of an offence in one round, from everything that drives it.
     *
     * Every term is something a player can move: build better houses and
     * hardship falls, fund the force and deterrence rises, keep the farms cool
     * and the town stops attracting people who work at night.
     *
     * Poverty and heat ADD into one lift rather than each multiplying the
     * whole thing -- see {@link #CRIME_HARDSHIP_LIFT}. Night is still a
     * multiplier because the dark genuinely is a different world, and
     * deterrence is still a multiplier because it is a share of what would
     * otherwise have happened. Then the whole thing meets
     * {@link #CRIME_CEILING} before it becomes odds, because a rate nothing
     * could police is not a difficulty setting.
     */
    public static float crimeOdds(int population, float hardship, float deterrence,
                                  boolean night, int heat) {
        if (population <= 0) {
            return 0f;
        }
        float perDay = CRIME_BASE * population / 100f;
        perDay *= 1f
                + CRIME_HARDSHIP_LIFT * Math.max(0f, Math.min(1f, hardship))
                + CRIME_HEAT_LIFT * Math.min(1f, Math.max(0, heat) / (float) CRIME_HEAT_FULL);
        perDay *= night ? NIGHT_CRIME : 1f;
        perDay = Math.min(CRIME_CEILING, perDay);
        perDay *= 1f - Math.max(0f, Math.min(TOP_DETERRENCE, deterrence));
        return Math.max(0f, Math.min(1f, perDay / CRIME_ROUNDS_PER_DAY));
    }

    /**
     * How many turn up off the road.
     *
     * Off the town's own size, because a band that would flatten a hamlet is a
     * nuisance to a city and one number cannot be both. Heat adds to it: a
     * place known for its trade attracts the sort of people who have heard of
     * it. Capped, because the answer to "what if the town is enormous" is not
     * "an unkillable wall of crossbows".
     */
    public static int banditBand(int population, int heat) {
        int band = 2 + population / 10 + Math.min(3, Math.max(0, heat) / 200);
        return Math.max(2, Math.min(8, band));
    }

    /** One copper per this many residents is a full complement. */
    public static final int BEAT_PER_OFFICER = 8;

    /**
     * How much crime a force holds down, 0 to {@link #TOP_DETERRENCE}.
     *
     * Per head of population rather than outright, because policing a village
     * with four and policing a city with four are not the same job -- a town
     * that grows without funding its force should feel exactly that.
     */
    public static float deterrence(int officers, int population, int gear) {
        if (officers <= 0) {
            return 0f;
        }
        float perHead = officers
                / (float) Math.max(BEAT_PER_OFFICER, population);
        return Math.min(TOP_DETERRENCE,
                perHead * (1f + 0.12f * Math.max(0, gear)) * BEAT_PER_OFFICER * 0.3f);
    }

    /**
     * What a truncheon does, per grade of kit.
     *
     * Was 4 + 2/grade, and that was measured against the wrong clock. An
     * officer swings once per decision pass -- every thirty ticks -- so ten
     * damage at full kit is ten damage every second and a half, against a
     * zombie that hits back about once a second. Three seconds to kill one
     * zombie is three seconds of taking hits, and the shift was being ground
     * down: measured live at 25, 38, 7, 21, 21 and 38 out of 38.
     *
     * One swing is a second and a half of struggle, so it is allowed to be
     * worth a second and a half. At full kit that is one zombie per swing,
     * which is what a city paying 2100e a day should be buying.
     */
    public static float truncheonHit(int gear) {
        return 6f + 5f * Math.max(0, gear);
    }

    /**
     * What a bolt does, per grade of kit.
     *
     * Under a truncheon on purpose: range is the advantage, and a crossbow
     * that also hit harder would make closing the distance a mistake. This is
     * the answer to a pillager standing eight blocks back, not a better way of
     * killing a zombie.
     */
    public static double boltHit(int gear) {
        return 3.0 + 1.5 * Math.max(0, gear);
    }

    public static double officerPace(int gear) {
        return OFFICER_PACE + OFFICER_PACE_PER_GEAR * Math.max(0, gear);
    }

    /** A copper the town paid for is a copper who survives a zombie. */
    public static double officerHealth(int gear) {
        return 24.0 + 10.0 * Math.max(0, gear);
    }

    /**
     * The vest, which they had none of at all.
     *
     * Armour is the single biggest survivability lever in the game and an
     * officer was wearing exactly as much of it as a farmer. Eleven points at
     * full kit is a little under half of incoming damage -- riot gear, not
     * plate, so an unfunded force is still fodder and a funded one still
     * loses to a horde.
     */
    public static double officerArmour(int gear) {
        return 2.0 + 3.0 * Math.max(0, gear);
    }

    /**
     * How fast they walk it off, per pass, when nobody is swinging at them.
     *
     * The actual cause of the attrition. A villager only regenerates from
     * food and these carry none, so every wound was permanent and death was
     * just a matter of enough nights. Full recovery in a couple of minutes of
     * quiet, which is a shift change without needing to model one.
     */
    public static float officerMend(int gear) {
        return 0.5f + 0.25f * Math.max(0, gear);
    }

    /**
     * Share of health below which an officer breaks off and goes home.
     *
     * The other half of not dying, and the half that reads as a person: the
     * one measured at 7 out of 38 was still walking at zombies because
     * nothing told it not to.
     */
    public static final float OFFICER_RETREAT = 0.35f;

    /** Golems one level of the works puts on the street. */
    public static final int GOLEMS_PER_LEVEL = 3;

    /**
     * How many golems are actually standing, from what the city BOUGHT and
     * from who it is PAYING.
     *
     * The two halves of policing meet here and nowhere else. A public work is
     * capital -- paid once, owned forever -- and the wage dial is the running
     * cost, and a golem is police property rather than a monument, so it walks
     * out with a copper or it does not walk out at all.
     *
     * Without that floor the feature eats itself: buy the works once, cut the
     * budget to nothing the next morning, and the town keeps a permanent free
     * army that never needs another emerald. Every other part of this file is
     * written against a dial the player has to keep feeding, and one purchase
     * that opts out of it would make the rest decoration.
     */
    public static int golemGuard(int worksLevel, int officers) {
        return Math.min(Math.max(0, worksLevel) * GOLEMS_PER_LEVEL, Math.max(0, officers));
    }

    /**
     * What a stop-and-search costs, or 0 for "walk on".
     *
     * The threshold is the whole of the fairness: a joint in a pocket is not
     * a thing anybody gets fined for, and neither is a clean player standing
     * near a station. It takes a POCKETFUL, or heat, or an outstanding
     * assessment -- all three of which are the player having chosen something.
     */
    public static int ticket(int contraband, int heatTiers, int owed, int looksAway) {
        boolean carrying = contraband >= looksAway;
        if (!carrying && heatTiers <= 0 && owed <= 0) {
            return 0;
        }
        int fine = 40;
        if (carrying) {
            fine += Math.min(320, (contraband - looksAway + 1) * 6);
        }
        fine += 90 * Math.max(0, heatTiers);
        fine += owed > 0 ? 60 : 0;
        return Math.min(600, fine);
    }

    /**
     * What somebody walks off with, as a share of what was lying there.
     *
     * A share rather than a flat number, so a burglary hurts a landlord with
     * nine houses of uncollected rent more than one who empties their box
     * every morning -- which makes emptying it the counterplay, and gives
     * {@code /home} something to be for.
     */
    public static int haul(int held, float low, float high, float roll) {
        if (held <= 0 || high <= 0) {
            return 0;
        }
        float share = low + (high - low) * Math.max(0f, Math.min(1f, roll));
        return Math.max(1, Math.min(held, Math.round(held * share)));
    }

    // --- wand tiers -----------------------------------------------------------

    /** What you buy, and the two you can earn on top of it. */
    public static final int WAND_TIERS = 3;
    /** A fifth off the cooldown per tier. */
    private static final float[] WAND_SPEED = {1.00f, 0.80f, 0.60f};
    /** A quarter on to the reach, the radius or the damage per tier. */
    private static final float[] WAND_POWER = {1.00f, 1.25f, 1.50f};

    /**
     * Two multipliers for all five wands, rather than a table per wand.
     *
     * Each of them is a different verb -- one throws you, one reaps a field,
     * one lights up ore -- and a hand-tuned ladder for each would be five sets
     * of numbers to keep honest against five tooltips and a guide book. One
     * promise instead ("a fifth faster, a quarter further, twice over"), which
     * is also the only version of this a player can hold in their head while
     * deciding whether the cores are worth spending.
     *
     * The tier is clamped rather than trusted: it arrives off an itemstack,
     * and a component is whatever the last person to hold a command block
     * wrote into it.
     */
    public static int wandTier(int tier) {
        return Math.max(0, Math.min(WAND_TIERS - 1, tier));
    }

    /** A cooldown at this tier, never under a tick. */
    public static int wandCooldown(int ticks, int tier) {
        return Math.max(1, Math.round(ticks * WAND_SPEED[wandTier(tier)]));
    }

    /** A range, a radius or a block count at this tier. */
    public static int wandReach(int blocks, int tier) {
        return Math.round(blocks * WAND_POWER[wandTier(tier)]);
    }

    /** Damage at this tier, kept as a float because half hearts are a thing. */
    public static float wandDamage(float damage, int tier) {
        return damage * WAND_POWER[wandTier(tier)];
    }

    /**
     * What the next tier costs in emeralds: half the shelf, then all of it.
     *
     * Priced off the wand's own catalogue price rather than in materials. The
     * first version charged cores -- more of the breeze rods and nether stars
     * the wand is crafted from -- which reads well and prices nothing: those
     * are lines on the market like everything else, and a shelf that sells a
     * sniffer egg for pocket change turns "two more eggs" into an upgrade you
     * can buy with an afternoon's rent. Emeralds are the only unit in this mod
     * that the market cannot undercut, because they ARE the market.
     *
     * A full ladder therefore costs half again what the wand did, which keeps
     * the rack the thing you save for after you have already saved for it.
     */
    public static int wandPrice(int shelf, int tier) {
        return Math.max(1, shelf * (wandTier(tier) + 1) / 2);
    }
    // --- the bookmaker ----------------------------------------------------------

    /**
     * How many rating points make a ten-to-one favourite.
     *
     * The same logistic every rating system in sport uses, because it is the
     * one that behaves at both ends: a five point edge is worth a little, a
     * forty point edge is worth a lot, and nobody is ever certain.
     *
     * Tuned against the widest gap that can actually be drawn, which is the
     * spread INSIDE a competition rather than across the board: about eighteen
     * points, top of the Champions League to the bottom of it. That prices the
     * favourite at roughly 1.55 once the draw is carved out, which is what a
     * real book quotes for a good side at home to a poor one. Fixtures are
     * never drawn across competitions, so the fifty-point gap between Real
     * Madryt and Widzew is a number this scale is never asked about.
     */
    public static final float BOOK_SCALE = 45.0f;

    /**
     * The book's cut on every price it prints.
     *
     * Seven percent per outcome, so a two-way market runs at about a 7.5%
     * overround and a three-way at 7.5% as well. This is the whole reason
     * betting blind loses: the prices are short of the truth by this much, and
     * nothing else on the board is rigged.
     */
    public static final float BOOK_MARGIN = 0.07f;

    /** Shortest and longest price the board will print, in hundredths. */
    public static final int BOOK_MIN_ODDS = 102;
    public static final int BOOK_MAX_ODDS = 6000;

    /** Most a single slip can return, whatever the multiplication says. */
    public static final int BOOK_MAX_PAYOUT = 60_000;

    /** Selections allowed on one slip. Four legs, like every coupon. */
    public static final int BOOK_MAX_LEGS = 4;

    /** What the stake buttons offer. Shorter ladder than the casino's. */
    public static final int[] BOOK_STAKES = {8, 16, 32, 64, 128, 256, 512, 1024};

    /**
     * What one recent result is worth, in rating points.
     *
     * Every constant from here down was halved once, and the reason is worth
     * keeping: at twice these numbers the hidden factors outweighed reputation
     * itself, and a punter who read the two panels perfectly returned about
     * thirty percent on turnover. That is not a game with an edge in it, it is
     * a money printer with a television attached. At these numbers the same
     * perfect reader returns roughly a tenth of what they stake and a careless
     * one still loses -- which is the shape this is supposed to have.
     * BookmakerTest simulates both ends and fails if either drifts.
     *
     * Five results are kept, so form swings a competitor by five points either
     * way: about a quarter of the widest gap inside a competition. Enough that
     * a side on a bad run is worth laying off, never enough to turn an
     * outsider into a favourite on its own.
     */
    public static final int BOOK_FORM = 1;

    /** Points lost per key absentee. Three out is a different side. */
    public static final int BOOK_ABSENCE = 2;

    /**
     * What rest is worth, indexed by rounds off since the last outing.
     *
     * Backing up straight after a fixture is a real penalty and a fortnight
     * off is a real edge, which is exactly the sort of thing a board of prices
     * cannot say and a schedule can.
     */
    public static final int[] BOOK_REST = {-3, -1, 0, 1, 2};

    /**
     * Playing at home.
     *
     * The one modifier the book DOES price, because it is on the fixture list
     * -- everybody can see who is at home, so an edge nobody has to look for
     * is not an edge. Everything else in this section is missing from the
     * price and readable on the television, which is the game.
     */
    public static final int BOOK_HOME = 5;

    /** Points per previous win over this exact opponent, and the cap on them. */
    public static final int BOOK_H2H = 1;
    public static final int BOOK_H2H_CAP = 3;

    /** How often a level football match ends level, and how fast that falls off. */
    public static final float BOOK_DRAW_BASE = 0.28f;
    public static final float BOOK_DRAW_FALL = 45.0f;

    /**
     * What a run of recent results is worth.
     *
     * 'W' won, 'R' drew or placed, 'P' lost. Most recent first, though the
     * order does not matter to the arithmetic -- it matters to the person
     * reading it off the screen, which is why it is kept as a string rather
     * than a count.
     */
    public static int formPoints(String form) {
        int points = 0;
        for (int i = 0; i < form.length(); i++) {
            if (form.charAt(i) == 'W') {
                points += BOOK_FORM;
            } else if (form.charAt(i) == 'P') {
                points -= BOOK_FORM;
            }
        }
        return points;
    }

    /** Rounds of rest, clamped into the table above. */
    public static int restPoints(int rounds) {
        return BOOK_REST[Math.max(0, Math.min(BOOK_REST.length - 1, rounds))];
    }

    /** What this pair's own history is worth to the one who has won more. */
    public static int headToHeadPoints(int mine, int theirs) {
        return Math.max(-BOOK_H2H_CAP, Math.min(BOOK_H2H_CAP, (mine - theirs) * BOOK_H2H));
    }

    /**
     * What the result is decided on, as opposed to what the price is set on.
     *
     * The gap between this and {@link #pricedRating} IS the game: the book
     * knows the reputation and who is at home, and nothing else. Form,
     * absences, rest, the going and the pair's own history are all printed on
     * the television and none of them are in the price.
     */
    public static float trueRating(int reputation, String form, int absences, int rest,
                                   int suits, int headToHead, boolean home) {
        return reputation
                + formPoints(form)
                - absences * BOOK_ABSENCE
                + restPoints(rest)
                + suits
                + headToHead
                + (home ? BOOK_HOME : 0);
    }

    /** What the board is priced off. Reputation and the fixture list, no more. */
    public static float pricedRating(int reputation, boolean home) {
        return reputation + (home ? BOOK_HOME : 0);
    }

    /** The chance the first of two beats the second, ignoring draws. */
    public static float duelChance(float mine, float theirs) {
        return 1.0f / (1.0f + (float) Math.pow(10.0, (theirs - mine) / BOOK_SCALE));
    }

    /**
     * How likely a football match is to end level.
     *
     * Highest when the two are matched and falling away as the gap opens,
     * which is what the draw column of any real coupon looks like. Never more
     * than {@link #BOOK_DRAW_BASE}, so the other two always share the rest.
     */
    public static float drawChance(float mine, float theirs) {
        return (float) (BOOK_DRAW_BASE * Math.exp(-Math.abs(mine - theirs) / BOOK_DRAW_FALL));
    }

    /**
     * Home, draw, away -- three chances that sum to one.
     *
     * The draw is carved out first and the remainder split by the duel
     * formula, rather than modelled as a third competitor. A draw is not a
     * team and giving it a rating produces a market where two evenly matched
     * sides are less likely to draw than two mismatched ones.
     */
    public static float[] matchChances(float mine, float theirs) {
        float draw = drawChance(mine, theirs);
        float mineWins = duelChance(mine, theirs);
        return new float[]{(1 - draw) * mineWins, (1 - draw) * (1 - mineWins), draw};
    }

    /**
     * Every runner's chance of winning a field, normalised to one.
     *
     * Exponential in the rating, on the same scale as the duel formula, so a
     * race between two of them prices identically to a match between the same
     * two. Anything else and the board would contradict itself across sports.
     */
    public static float[] fieldChances(float[] ratings) {
        float best = Float.NEGATIVE_INFINITY;
        for (float rating : ratings) {
            best = Math.max(best, rating);
        }
        float[] weights = new float[ratings.length];
        float total = 0;
        for (int i = 0; i < ratings.length; i++) {
            // Shifted by the best before exponentiating: the raw numbers are
            // around a hundred and exp(100/26) overflows nothing but does throw
            // away precision on the short ones.
            weights[i] = (float) Math.exp((ratings[i] - best) / BOOK_SCALE * Math.log(10));
            total += weights[i];
        }
        for (int i = 0; i < weights.length; i++) {
            weights[i] /= total;
        }
        return weights;
    }

    /**
     * The chance of finishing in the first `places`, from the win chances.
     *
     * Harville: the race is run by drawing the winner out of the field in
     * proportion to its chance, then drawing second out of what is left, and
     * so on. Exact rather than approximated -- a field of eight and three
     * places is 336 orderings, which costs nothing and means the place market
     * cannot quietly disagree with the win market it is derived from.
     */
    public static float[] placeChances(float[] win, int places) {
        float[] out = new float[win.length];
        harville(win, new boolean[win.length], 1.0f, 0, Math.min(places, win.length), out);
        return out;
    }

    private static void harville(float[] win, boolean[] taken, float carried,
                                 int depth, int places, float[] out) {
        if (depth >= places) {
            return;
        }
        float left = 0;
        for (int i = 0; i < win.length; i++) {
            if (!taken[i]) {
                left += win[i];
            }
        }
        if (left <= 0) {
            return;
        }
        for (int i = 0; i < win.length; i++) {
            if (taken[i]) {
                continue;
            }
            float here = carried * win[i] / left;
            out[i] += here;
            taken[i] = true;
            harville(win, taken, here, depth + 1, places, out);
            taken[i] = false;
        }
    }

    /**
     * A price, in hundredths, from a chance -- shortened by the book's cut.
     *
     * Clamped at both ends. An unclamped board prints 1.00 against a horse
     * that cannot lose (a free bet) and 400.00 against one that cannot win (a
     * lottery ticket somebody will buy every round until it lands), and
     * neither is a market.
     */
    public static int price(float chance) {
        if (chance <= 0) {
            return BOOK_MAX_ODDS;
        }
        int odds = Math.round(100.0f * (1.0f - BOOK_MARGIN) / chance);
        return Math.max(BOOK_MIN_ODDS, Math.min(BOOK_MAX_ODDS, odds));
    }

    /**
     * What a coupon pays per emerald, in hundredths.
     *
     * Legs multiply, which is why a four-fold at even money is not four times
     * better than a single -- it is fifteen times better and about a third as
     * likely, and it carries the book's cut four times over. Long enough that
     * everybody tries one, short enough that nobody lives on them.
     */
    public static int slipOdds(int[] legs) {
        long odds = 100;
        for (int leg : legs) {
            odds = odds * leg / 100;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(100, odds));
    }

    /** What a winning slip hands over, ceiling included. */
    public static int slipReturn(int stake, int odds) {
        return (int) Math.min(BOOK_MAX_PAYOUT, (long) stake * odds / 100);
    }

    /** A price as people say it out loud: 250 -> "2.50". */
    public static String odds(int hundredths) {
        return String.format(java.util.Locale.ROOT, "%.2f", hundredths / 100.0f);
    }
}
