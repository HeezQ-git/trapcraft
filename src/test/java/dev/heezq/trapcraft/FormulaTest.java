package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three formulas in this mod worth a real check: the paranoia pressure
 * curve, the contract payout, and the ledger's aggregation.
 *
 * They all live in TrapMath, which imports nothing from Minecraft, so this
 * runs as a plain JUnit suite in about a second rather than needing a game.
 */
class FormulaTest {
    private static final int TIERS = 4;

    // --- paranoia -------------------------------------------------------------

    @Test
    void soberAndSafeIsCalm() {
        assertEquals(0.0f, TrapMath.pressure(0, TIERS, 0, 15, false, false), 0.001f);
    }

    @Test
    void heatDominates() {
        float low = TrapMath.pressure(1, TIERS, 0, 15, false, true);
        float high = TrapMath.pressure(4, TIERS, 0, 15, false, true);
        assertTrue(high > low * 2.0f, "tier 4 should dwarf tier 1, got " + low + " -> " + high);
    }

    @Test
    void companyCalmsYouDown() {
        float alone = TrapMath.pressure(3, TIERS, 1, 0, true, true);
        float together = TrapMath.pressure(3, TIERS, 1, 0, true, false);
        assertTrue(together < alone, "a friend nearby must reduce pressure");
    }

    @Test
    void beingHighMultipliesRatherThanAdds() {
        // No heat, lit, daytime, in company: high or not, you are fine.
        assertEquals(0.0f, TrapMath.pressure(0, TIERS, 3, 15, false, false), 0.001f);
    }

    @Test
    void pressureIsAlwaysBounded() {
        assertTrue(TrapMath.pressure(4, TIERS, 4, 0, true, true) <= 1.0f);
        assertTrue(TrapMath.pressure(0, TIERS, 0, 15, false, false) >= 0.0f);
    }

    @Test
    void meterSettlesAtItsTargetRatherThanClimbing() {
        // Half pressure must hold near half, not creep to the top.
        float value = 0.0f;
        for (int second = 0; second < 200; second++) {
            value = TrapMath.approach(value, 50.0f, 1.6f, 2.4f);
        }
        assertEquals(50.0f, value, 0.001f);
    }

    @Test
    void meterFallsWhenThePressureDrops() {
        assertTrue(TrapMath.approach(80.0f, 0.0f, 1.6f, 2.4f) < 80.0f);
    }

    @Test
    void meterNeverOvershootsItsTarget() {
        assertEquals(10.0f, TrapMath.approach(9.0f, 10.0f, 1.6f, 2.4f), 0.001f);
        assertEquals(10.0f, TrapMath.approach(11.0f, 10.0f, 1.6f, 2.4f), 0.001f);
    }

    // --- payout ---------------------------------------------------------------

    @Test
    void payoutRisesWithDistanceQuantityAndGrade() {
        int baseline = TrapMath.payout(100, 8, 1, 0, 0);
        assertTrue(TrapMath.payout(400, 8, 1, 0, 0) > baseline, "further must pay more");
        assertTrue(TrapMath.payout(100, 16, 1, 0, 0) > baseline, "more goods must pay more");
        assertTrue(TrapMath.payout(100, 8, 3, 0, 0) > baseline, "better grade must pay more");
    }

    @Test
    void heatPaysBetter() {
        assertTrue(TrapMath.payout(100, 8, 1, 4, 0) > TrapMath.payout(100, 8, 1, 0, 0),
                "the point of the coupling is that hot work pays");
    }

    @Test
    void payoutStaysInBounds() {
        assertTrue(TrapMath.payout(0, 0, 0, 0, 0) >= 0);
        assertEquals(TrapMath.PAYOUT_CEILING,
                TrapMath.payout(999999, 9999, 9, 99, 999));
    }

    // --- market ---------------------------------------------------------------

    @Test
    void moreMoneyInCirculationMeansHigherPrices() {
        float anchor = TrapMath.MARKET_BASELINE;
        assertTrue(TrapMath.marketIndex(6000f, anchor) > TrapMath.marketIndex(500f, anchor));
    }

    @Test
    void theIndexCannotRunAway() {
        float anchor = TrapMath.MARKET_BASELINE;
        assertEquals(TrapMath.INDEX_MAX, TrapMath.marketIndex(9_000_000f, anchor), 0.001f);
        assertEquals(TrapMath.INDEX_MIN, TrapMath.marketIndex(0f, anchor), 0.001f);
    }

    @Test
    void aRicherWorldIsNotAPermanentlyDearerOne() {
        // The bug this replaced: the anchor was a constant, so an economy that
        // grew past ~7x it sat on INDEX_MAX forever and losing every emerald
        // you had moved prices by nothing. Whatever the money supply settles
        // at, prices must come back to normal on their own.
        for (float settled : new float[]{800f, 2000f, 13_000f, 250_000f}) {
            float anchor = TrapMath.MARKET_BASELINE;
            for (int beat = 0; beat < 4000; beat++) {
                anchor = TrapMath.baselineAfter(anchor, settled);
            }
            assertEquals(1.0f, TrapMath.marketIndex(settled, anchor), 0.01f,
                    "an economy resting at " + settled + "e should read as normal");
        }
    }

    @Test
    void aShockIsFeltAndThenForgotten() {
        // A jackpot has to move the board, or the index is decoration...
        float settled = 13_000f;
        float anchor = settled;
        float shocked = settled * 1.6f;
        float spike = TrapMath.marketIndex(shocked, anchor);
        assertTrue(spike > 1.2f, "a 60% jump in the money supply should bite: " + spike);

        // ...and it has to fade, or it is a ratchet. Half-life is about an
        // hour of play, so two hours should be most of the way home.
        for (int beat = 0; beat < 240; beat++) {
            anchor = TrapMath.baselineAfter(anchor, shocked);
        }
        float after = TrapMath.marketIndex(shocked, anchor);
        assertTrue(after < 1.0f + (spike - 1.0f) * 0.35f,
                "two hours on, the spike should be mostly gone: " + spike + " -> " + after);
        assertTrue(after > 1.0f, "but not gone early: " + after);
    }

    @Test
    void theAnchorNeverCollapses() {
        // Divide-by-nothing guard: an economy that empties out must not make
        // the next emerald anybody mines worth a price spike.
        float anchor = TrapMath.MARKET_BASELINE;
        for (int beat = 0; beat < 10_000; beat++) {
            anchor = TrapMath.baselineAfter(anchor, 0f);
        }
        assertEquals(TrapMath.BASELINE_FLOOR, anchor, 0.001f);
        assertTrue(TrapMath.marketIndex(1f, anchor) >= TrapMath.INDEX_MIN);
    }

    @Test
    void twoPlayersAtTheSameStallSeeTheSamePrice() {
        assertEquals(TrapMath.drift(40, "minecraft:diamond"),
                TrapMath.drift(40, "minecraft:diamond"), 0.0f);
    }

    @Test
    void differentItemsMoveDifferentlyOnTheSameBeat() {
        assertNotEquals(TrapMath.drift(40, "minecraft:diamond"),
                TrapMath.drift(40, "minecraft:bread"));
    }

    @Test
    void aPriceActuallyMovesWhileYouShop() {
        // The whole point of the beat: come back in a couple of minutes and
        // the number is different.
        int moved = 0;
        for (long beat = 0; beat < 40; beat++) {
            if (TrapMath.drift(beat, "minecraft:diamond")
                    != TrapMath.drift(beat + 1, "minecraft:diamond")) {
                moved++;
            }
        }
        assertTrue(moved > 30, "prices barely moved over 40 beats: " + moved);
    }

    @Test
    void priceWalksRatherThanJumps() {
        // Smoothstep means no lurch when a window hands over. A single beat
        // must never cover more than a fraction of the whole band.
        float worst = 0;
        for (long beat = 0; beat < 500; beat++) {
            worst = Math.max(worst, Math.abs(TrapMath.drift(beat, "minecraft:diamond")
                    - TrapMath.drift(beat + 1, "minecraft:diamond")));
        }
        assertTrue(worst < TrapMath.DRIFT * 0.5f, "biggest single step was " + worst);
    }

    @Test
    void driftStaysWithinItsBand() {
        for (long beat = 0; beat < 4000; beat++) {
            float d = TrapMath.drift(beat, "minecraft:diamond");
            assertTrue(d >= 1.0f - TrapMath.DRIFT - 0.001f && d <= 1.0f + TrapMath.DRIFT + 0.001f,
                    "beat " + beat + " drifted to " + d);
        }
    }

    @Test
    void buyingPushesAPriceUpAndSellingPushesItDown() {
        assertTrue(TrapMath.pressureAfter(0f, 5, true) > 0f);
        assertTrue(TrapMath.pressureAfter(0f, 5, false) < 0f);
    }

    @Test
    void deepPocketsCannotBreakAPrice() {
        assertEquals(TrapMath.PRESSURE_CAP, TrapMath.pressureAfter(0f, 100_000, true), 0.001f);
        assertEquals(-TrapMath.PRESSURE_CAP, TrapMath.pressureAfter(0f, 100_000, false), 0.001f);
    }

    @Test
    void orderFlowFadesToNothing() {
        float held = TrapMath.PRESSURE_CAP;
        for (int beat = 0; beat < 500; beat++) {
            held = TrapMath.relax(held);
        }
        assertEquals(0f, held, 0f);   // exactly zero, so the line can be dropped
    }

    @Test
    void aRoundTripThroughTheShopIsNeverProfitable() {
        // Buy a lot, which pushes the price up, then sell it straight back at
        // the pushed-up price. If that ever nets a profit the market is a
        // money printer.
        for (int base : new int[]{3, 16, 42, 300, 1600}) {
            float after = TrapMath.pressureAfter(0f, 1, true);
            int paid = TrapMath.buyPrice(base, 1.0f, 1.0f, TrapMath.flowFactor(0f));
            int back = TrapMath.sellPrice(
                    TrapMath.buyPrice(base, 1.0f, 1.0f, TrapMath.flowFactor(after)));
            assertTrue(back < paid, "base " + base + ": paid " + paid + ", got back " + back);
        }
    }

    @Test
    void spendingMoneyTakesItOutOfCirculation() {
        assertTrue(TrapMath.circulated(2000f, -500) < 2000f);
        assertTrue(TrapMath.circulated(2000f, 500) > 2000f);
    }

    @Test
    void theEconomyCannotGoNegative() {
        assertEquals(0f, TrapMath.circulated(10f, -999_999), 0f);
    }

    @Test
    void sellingAlwaysPaysLessThanBuying() {
        for (int base : new int[]{2, 5, 40, 400, 1200}) {
            int buy = TrapMath.buyPrice(base, 1.0f, 1.0f, 1.0f);
            assertTrue(TrapMath.sellPrice(buy) < buy, "no spread at base " + base);
        }
    }

    @Test
    void pennyGoodsAreNotBoughtBack() {
        // A one-emerald price leaves no room for a spread, so the shop
        // declines rather than handing back exactly what you paid.
        assertEquals(0, TrapMath.sellPrice(1));
    }

    @Test
    void buyingIsNeverFree() {
        assertTrue(TrapMath.buyPrice(1, TrapMath.INDEX_MIN, 1.0f - TrapMath.DRIFT,
                TrapMath.flowFactor(-TrapMath.PRESSURE_CAP)) >= 1);
    }

    @Test
    void payingOutNeverMintsOrBurnsMoney() {
        // The wallet's withdraw button and every shop payout go through this.
        // If blocks*9 + loose ever drifts from the amount, the economy leaks.
        for (int amount = 0; amount <= 5000; amount++) {
            int[] packed = TrapMath.packEmeralds(amount);
            assertEquals(amount, packed[0] * 9 + packed[1],
                    "packing " + amount + " came out as " + packed[0] + " blocks + " + packed[1]);
        }
    }

    @Test
    void smallChangeComesAsLooseEmeralds() {
        assertEquals(0, TrapMath.packEmeralds(63)[0]);
        assertEquals(63, TrapMath.packEmeralds(63)[1]);
        assertTrue(TrapMath.packEmeralds(64)[0] > 0, "a big payout should come in blocks");
    }

    @Test
    void payingOutNothingHandsOverNothing() {
        assertEquals(0, TrapMath.packEmeralds(0)[0]);
        assertEquals(0, TrapMath.packEmeralds(0)[1]);
        assertEquals(0, TrapMath.packEmeralds(-5)[1]);
    }

    // --- the slot machine -----------------------------------------------------

    /**
     * The one that matters.
     *
     * Once wins stack there is no closed form for the return, so the only
     * honest way to know it is to play the machine. This is also the guard on
     * every constant in the paytable: change a pay or an odd without
     * re-measuring and this fails, which it should, because the cabinet quotes
     * these numbers to the player.
     */
    @Test
    void theHouseKeepsItsEdge() {
        float[] rate = new float[1];
        float rtp = TrapMath.slotMeasure(20260808L, 120_000, 5, rate);

        // The one line that must never go: the house has to win long-run, or
        // the machine is an emerald fountain and the market inflates off it.
        // The margin is thin by design -- the complaint was that players were
        // always down -- so this is deterministic from a fixed seed rather
        // than a sample that could stray over the line on a lucky run.
        assertTrue(rtp < 1.0f, "the house must win long-run, got " + rtp);
        assertTrue(rtp > 0.90f, "but the bleed should stay gentle, got " + rtp);
        assertTrue(rate[0] > 0.25f, "wins should still feel reachable, got " + rate[0]);

        // What the cabinet and the guide print. Loose enough for sampling
        // noise, tight enough that an edited pay table trips it.
        assertEquals(TrapMath.slotRtp(5), rtp, 0.02f,
                "the 5x5 return constant is stale -- the machine now returns " + rtp);
        assertEquals(TrapMath.slotWinRate(5), rate[0], 0.02f,
                "the 5x5 win rate constant is stale -- now " + rate[0]);
    }

    @Test
    void aWinIsNeverAlsoALoss() {
        // The smallest win must return at least the stake. Paying less than
        // you put in is a loss wearing a party hat, and a machine full of them
        // is one nobody can tell they are losing at.
        float smallest = Float.MAX_VALUE;
        for (TrapMath.SlotShape shape : TrapMath.slotShapes(5)) {
            smallest = Math.min(smallest, shape.pay());
        }
        smallest = Math.min(smallest, TrapMath.PAY_RUN3);
        assertTrue(smallest >= 1.0f,
                "the cheapest way to win pays " + smallest + "x -- that is a loss with lights on");
    }

    @Test
    void theBigPrizesAreWorthChasing() {
        // Ordering the cabinet advertises. If a rarer shape ever pays less
        // than a commoner one, the paytable is lying to the player.
        assertTrue(TrapMath.PAY_RUN5 > TrapMath.PAY_CORNERS);
        assertTrue(TrapMath.PAY_CORNERS > TrapMath.PAY_DIAMOND);
        assertTrue(TrapMath.PAY_DIAMOND > TrapMath.PAY_ZED);
        assertTrue(TrapMath.PAY_ZED > TrapMath.PAY_RUN4);
        assertTrue(TrapMath.PAY_RUN4 > TrapMath.PAY_CROSS);
        assertTrue(TrapMath.PAY_CROSS >= TrapMath.PAY_PLUS);
        assertTrue(TrapMath.PAY_PLUS > TrapMath.PAY_SQUARE);
        assertTrue(TrapMath.PAY_SQUARE > TrapMath.PAY_RUN3);
    }

    @Test
    void everyDiagonalCountsNotJustTheLongTwo() {
        // 5 rows + 5 columns + 5 down-right + 5 down-left.
        assertEquals(20, TrapMath.slotLines(5).length);
        for (int[] line : TrapMath.slotLines(5)) {
            assertTrue(line.length >= 3, "a line shorter than three can never win");
            for (int cell : line) {
                assertTrue(cell >= 0 && cell < 25, "line ran off the board at " + cell);
            }
        }
    }

    @Test
    void everyShapeFitsOnTheBoard() {
        for (TrapMath.SlotShape shape : TrapMath.slotShapes(5)) {
            assertTrue(shape.pay() > 0, shape.name() + " pays nothing");
            for (int cell : shape.cells()) {
                assertTrue(cell >= 0 && cell < 25,
                        shape.name() + " runs off the board at " + cell);
            }
        }
    }

    @Test
    void aQuietBoardWinsNothing() {
        assertFalse(TrapMath.slotScore(quietGrid(), 5).won(),
                "the no-run grid must not win, or every other slot test is meaningless");
    }

    @Test
    void twoWinsAtOncePayTwice() {
        // Exactly the board from the bug report: three of one symbol along the
        // bottom row and three of another down a column. It paid for one.
        int[] grid = quietGrid();
        for (int col = 0; col < 3; col++) {
            grid[4 * 5 + col] = 7;
        }
        float bottomOnly = TrapMath.slotScore(grid, 5).pay();
        assertTrue(bottomOnly > 0, "three along the bottom should pay");

        for (int row = 0; row < 3; row++) {
            grid[row * 5 + 3] = 6;
        }
        TrapMath.SlotScore both = TrapMath.slotScore(grid, 5);
        assertTrue(both.pay() > bottomOnly,
                "a second line must add to the payout, got " + both.pay()
                        + " for two wins against " + bottomOnly + " for one");
        assertTrue(both.names().size() >= 2, "both wins should be named on the receipt");
    }

    @Test
    void aShapePaysMoreThanALine() {
        int[] grid = quietGrid();
        // A 2x2 block in the top-left corner.
        grid[0] = 7;
        grid[1] = 7;
        grid[5] = 7;
        grid[6] = 7;
        TrapMath.SlotScore score = TrapMath.slotScore(grid, 5);
        assertTrue(score.won(), "a 2x2 block should pay");
        assertTrue(score.names().contains("Block"), "and should say so: " + score.names());
    }

    @Test
    void theHighlightIsExactlyWhatPaid() {
        int[] grid = quietGrid();
        for (int col = 0; col < 4; col++) {
            grid[2 * 5 + col] = 7;
        }
        TrapMath.SlotScore score = TrapMath.slotScore(grid, 5);
        assertTrue(score.won());
        for (int cell : score.cells()) {
            assertEquals(7, grid[cell],
                    "cell " + cell + " glows but isn't part of any winning symbol");
        }
    }

    @Test
    void alosingBoardIsReallyClean() {
        // The worst bug this machine can have is a board that visibly won and
        // paid nothing, so the generator must never hand one back.
        java.util.Random rng = new java.util.Random(99L);
        for (int spin = 0; spin < 3000; spin++) {
            int[] grid = TrapMath.slotBoard(rng, null, 5);
            assertFalse(TrapMath.slotScore(grid, 5).won(),
                    "generator produced a losing board that actually won");
        }
    }

    @Test
    void everyCabinetKeepsItsEdge() {
        // Four windows, four different games, four returns the cabinet quotes
        // to the player. Editing a pay or an odd without re-measuring makes
        // the plate on the front a lie, which is what this catches.
        for (int size : TrapMath.SLOT_SIZES) {
            float[] rate = new float[1];
            float rtp = TrapMath.slotMeasure(20260808L + size, 60_000, size, rate);
            assertTrue(rtp > 0.90f && rtp < 1.0f,
                    size + "x" + size + " returns " + rtp);
            assertEquals(TrapMath.slotRtp(size), rtp, 0.02f,
                    size + "x" + size + " return constant is stale -- now " + rtp);
            assertEquals(TrapMath.slotWinRate(size), rate[0], 0.02f,
                    size + "x" + size + " win rate constant is stale -- now " + rate[0]);
        }
    }

    @Test
    void aWinIsNeverWorseThanTheStake() {
        // The cabinet says so on the lever. A pay below 1.0 would make most
        // "wins" quiet losses, which is the one thing a slot machine must not
        // do to somebody who is watching the lights.
        for (int size : TrapMath.SLOT_SIZES) {
            for (int run = TrapMath.slotRunFloor(size); run <= size; run++) {
                assertTrue(TrapMath.slotPayForRun(run, size) >= 1.0f,
                        size + "x" + size + " pays " + TrapMath.slotPayForRun(run, size)
                                + " for a run of " + run);
            }
            for (TrapMath.SlotShape shape : TrapMath.slotShapes(size)) {
                assertTrue(shape.pay() >= 1.0f, shape.name() + " pays " + shape.pay());
            }
        }
    }

    @Test
    void noCabinetAdvertisesTheSameCellsTwice() {
        // A Diamond on a 3x3 is a Cross, and two names for one event is how a
        // paytable starts lying. Shapes must differ in their CELLS, not just
        // in what they are called.
        for (int size : TrapMath.SLOT_SIZES) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (TrapMath.SlotShape shape : TrapMath.slotShapes(size)) {
                int[] cells = shape.cells().clone();
                java.util.Arrays.sort(cells);
                assertTrue(seen.add(java.util.Arrays.toString(cells)),
                        size + "x" + size + ": " + shape.name()
                                + " covers cells another shape already claims");
            }
        }
    }

    @Test
    void everyWindowFitsItsGrid() {
        for (int size : TrapMath.SLOT_SIZES) {
            int cells = size * size;
            for (int[] line : TrapMath.slotLines(size)) {
                assertTrue(line.length >= TrapMath.slotRunFloor(size),
                        size + "x" + size + " has a line too short to score");
                for (int cell : line) {
                    assertTrue(cell >= 0 && cell < cells, "line cell " + cell + " off the grid");
                }
            }
            for (TrapMath.SlotShape shape : TrapMath.slotShapes(size)) {
                for (int cell : shape.cells()) {
                    assertTrue(cell >= 0 && cell < cells,
                            shape.name() + " cell " + cell + " off a " + size + "x" + size);
                }
            }
            assertTrue(TrapMath.slotFaces(size) >= 2, "a reel needs faces");
            assertTrue(TrapMath.slotWinChance(size) < 1.0f,
                    size + "x" + size + " aims to win more often than it spins");
        }
    }

    @Test
    void aWinningBoardReallyWins() {
        java.util.Random rng = new java.util.Random(1234L);
        for (int size : TrapMath.SLOT_SIZES) {
            for (String plan : TrapMath.slotPlans(size)) {
                for (int spin = 0; spin < 200; spin++) {
                    assertTrue(TrapMath.slotScore(
                                    TrapMath.slotBoard(rng, plan, size), size).won(),
                            size + "x" + size + " plan " + plan
                                    + " produced a board that pays nothing");
                }
            }
        }
    }

    /**
     * A board with nothing on it, for tests that need a blank slate.
     *
     * Every horizontally and vertically adjacent pair differs, so no run, no
     * block and no shape can hide in it. A previous version used `i % 5`,
     * which on a five-wide board makes every column uniform -- five-in-a-line
     * everywhere -- and quietly made several tests assert nothing at all.
     * {@link #aQuietBoardWinsNothing} is the guard against that returning.
     */
    private static int[] quietGrid() {
        int[] grid = new int[25];
        for (int cell = 0; cell < grid.length; cell++) {
            grid[cell] = cell % 10;
        }
        return grid;
    }

    // --- the climb --------------------------------------------------------------

    @Test
    void everyRungIsTheSameBet() {
        // The whole design. If one height ever paid better than another the
        // game would have a correct answer and stop being a gamble.
        for (int ladder = 0; ladder < TrapMath.CLIMB_DOORS.length; ladder++) {
            for (int rung = 1; rung <= TrapMath.CLIMB_RUNGS; rung++) {
                assertEquals(TrapMath.CLIMB_RETURN,
                        TrapMath.climbReturnToPlayer(ladder, rung), 0.0005f,
                        "ladder " + ladder + " rung " + rung + " is priced differently");
            }
        }
    }

    @Test
    void theHouseWinsTheClimbToo() {
        assertTrue(TrapMath.CLIMB_RETURN < 1.0f);
        assertTrue(TrapMath.CLIMB_RETURN > 0.9f);
    }

    @Test
    void climbingHigherPaysMoreAndIsLessLikely() {
        for (int ladder = 0; ladder < TrapMath.CLIMB_DOORS.length; ladder++) {
            for (int rung = 2; rung <= TrapMath.CLIMB_RUNGS; rung++) {
                assertTrue(TrapMath.climbMultiplier(ladder, rung)
                                > TrapMath.climbMultiplier(ladder, rung - 1),
                        "rung " + rung + " must pay more than the one below");
                assertTrue(TrapMath.climbSurvival(ladder, rung)
                                < TrapMath.climbSurvival(ladder, rung - 1),
                        "rung " + rung + " must be harder to reach");
            }
        }
    }

    @Test
    void theRecklessLadderIsWilderNotWorse() {
        // Fewer doors means a worse chance and a bigger prize, and the two
        // have to cancel exactly -- otherwise one ladder is simply the right
        // one to play and the choice is fake.
        int steady = 0;
        int reckless = 1;
        assertTrue(TrapMath.climbSafeChance(reckless) < TrapMath.climbSafeChance(steady));
        assertTrue(TrapMath.climbMultiplier(reckless, TrapMath.CLIMB_RUNGS)
                        > TrapMath.climbMultiplier(steady, TrapMath.CLIMB_RUNGS),
                "the riskier ladder must top out higher");
        assertEquals(TrapMath.climbReturnToPlayer(steady, TrapMath.CLIMB_RUNGS),
                TrapMath.climbReturnToPlayer(reckless, TrapMath.CLIMB_RUNGS), 0.0005f,
                "but both must be the same bet");
    }

    @Test
    void theFirstRungAlreadyBeatsTheStake() {
        // Surviving one door has to be worth more than walking in, or the
        // cash-out button is a trap on the very first press.
        for (int ladder = 0; ladder < TrapMath.CLIMB_DOORS.length; ladder++) {
            assertTrue(TrapMath.climbMultiplier(ladder, 1) > 1.0f,
                    "ladder " + ladder + " pays " + TrapMath.climbMultiplier(ladder, 1)
                            + "x for the first door -- that is a loss for winning");
        }
    }

    // --- plinko -----------------------------------------------------------------

    @Test
    void theBoardIsAFairCoinEightTimes() {
        // The binomial 1:8:28:56:70:56:28:8:1. If this drifts, every multiplier
        // priced against it is wrong and the return quietly moves with it.
        int[] expected = {1, 8, 28, 56, 70, 56, 28, 8, 1};
        int total = 0;
        for (int slot = 0; slot < TrapMath.PLINKO_SLOTS; slot++) {
            assertEquals(expected[slot], TrapMath.plinkoPaths(slot),
                    "wrong number of paths into slot " + slot);
            total += TrapMath.plinkoPaths(slot);
        }
        assertEquals(256, total, "eight coin flips must have 256 outcomes");
    }

    @Test
    void theDropKeepsItsEdge() {
        float rtp = TrapMath.plinkoReturnToPlayer();
        assertTrue(rtp < 1.0f, "the house must win long-run, got " + rtp);
        assertTrue(rtp > 0.90f, "but the bleed should stay gentle, got " + rtp);
    }

    @Test
    void theEdgesPayAndTheMiddleDoesNot() {
        // The whole shape of the game, and the reason anyone watches the last
        // bounce: the likely slot is the worst one.
        float middle = TrapMath.PLINKO_PAYS[TrapMath.PLINKO_SLOTS / 2];
        for (int slot = 0; slot < TrapMath.PLINKO_SLOTS / 2; slot++) {
            assertTrue(TrapMath.PLINKO_PAYS[slot] > middle,
                    "slot " + slot + " should beat the middle");
            assertEquals(TrapMath.PLINKO_PAYS[slot],
                    TrapMath.PLINKO_PAYS[TrapMath.PLINKO_SLOTS - 1 - slot], 0.0001f,
                    "the board must be symmetric");
        }
    }

    @Test
    void rarerSlotsPayBetter() {
        // Nobody should ever be able to find a slot that is both likelier and
        // more generous than another.
        for (int a = 0; a < TrapMath.PLINKO_SLOTS; a++) {
            for (int b = 0; b < TrapMath.PLINKO_SLOTS; b++) {
                if (TrapMath.plinkoPaths(a) < TrapMath.plinkoPaths(b)) {
                    assertTrue(TrapMath.PLINKO_PAYS[a] >= TrapMath.PLINKO_PAYS[b],
                            "slot " + a + " is rarer than " + b + " but pays less");
                }
            }
        }
    }

    @Test
    void everyPathLandsSomewhereReal() {
        for (int mask = 0; mask < 256; mask++) {
            boolean[] path = new boolean[TrapMath.PLINKO_BOUNCES];
            for (int step = 0; step < path.length; step++) {
                path[step] = (mask >> step & 1) == 1;
            }
            int slot = TrapMath.plinkoSlot(path);
            assertTrue(slot >= 0 && slot < TrapMath.PLINKO_SLOTS,
                    "path " + mask + " landed off the board at " + slot);
        }
    }

    @Test
    void volumeSellingDoesNotCrashThePrice() {
        // The complaint this curve exists to answer: three stacks of something
        // used to walk a cheap line into the cap, where it rounded to nothing
        // and the shop stopped buying it at all.
        float after12 = TrapMath.flowFactor(TrapMath.pressureAfter(0, 12, false));
        assertTrue(after12 > 0.88f,
                "a dozen lots should barely move it, got " + after12);

        float after200 = TrapMath.flowFactor(TrapMath.pressureAfter(0, 200, false));
        assertTrue(after200 > 0.60f,
                "even a mountain of stock should leave it worth selling, got " + after200);
    }

    @Test
    void eachLotMovesThePriceLessThanTheLast() {
        // Diminishing impact is the whole mechanism. Linear steps are what
        // made the cliff.
        float first = 1.0f - TrapMath.flowFactor(TrapMath.pressureAfter(0, 1, false));
        float hundredth = TrapMath.flowFactor(TrapMath.pressureAfter(0, 99, false))
                - TrapMath.flowFactor(TrapMath.pressureAfter(0, 100, false));
        assertTrue(hundredth < first,
                "the hundredth lot moved the price " + hundredth
                        + ", the first moved it " + first);
    }

    @Test
    void pressureNeverBreaksItsCap() {
        for (int lots : new int[]{1, 10, 500, 100000}) {
            for (boolean buying : new boolean[]{true, false}) {
                float moved = TrapMath.pressureAfter(0, lots, buying);
                assertTrue(Math.abs(moved) <= TrapMath.PRESSURE_CAP + 0.0001f,
                        lots + " lots pushed pressure to " + moved);
            }
        }
    }

    @Test
    void sellingIsWorthTheWalk() {
        // Wide enough that the shop is a convenience, not so wide that farming
        // a crop and carrying it in feels like a punishment.
        assertTrue(TrapMath.SELL_RATE > 0.4f, "selling should be worth doing");
        assertTrue(TrapMath.SELL_RATE < 0.7f, "but buying must stay the expensive side");
    }

    // --- the coin market --------------------------------------------------------

    @Test
    void everyCoinStaysOnTheBoard() {
        // A price of zero or a NaN would divide by zero somewhere in the
        // screen, and a price is read every repaint.
        for (long beat = 0; beat < 6000; beat += 7) {
            for (float volatility : new float[]{0.10f, 0.30f, 0.65f}) {
                float price = TrapMath.coinPrice(beat, "test", 100.0f, volatility, 0.0f);
                assertTrue(price > 0.0f && Float.isFinite(price),
                        "price went to " + price + " on beat " + beat);
            }
        }
    }

    @Test
    void aWilderCoinSwingsWider() {
        float steady = spread(0.10f);
        float degenerate = spread(0.65f);
        assertTrue(degenerate > steady * 2,
                "a degenerate coin should swing far harder: " + degenerate + " vs " + steady);
    }

    private static float spread(float volatility) {
        float low = Float.MAX_VALUE;
        float high = 0.0f;
        for (long beat = 0; beat < 4000; beat++) {
            float price = TrapMath.coinPrice(beat, "spread" + volatility, 100.0f, volatility, 0.0f);
            low = Math.min(low, price);
            high = Math.max(high, price);
        }
        return high / low;
    }

    @Test
    void aSteadyCoinNeverRugs() {
        for (long beat = 0; beat < 20000; beat += 13) {
            assertFalse(TrapMath.coinDead(beat, "steady", 0.0f),
                    "a coin with no rug chance died on beat " + beat);
        }
    }

    @Test
    void aRuggedCoinIsNearlyWorthless() {
        // Find an era that actually rugs, then check the price after it.
        for (long era = 0; era < 200; era++) {
            long rug = TrapMath.coinRugBeat(era, "doomed", 0.35f);
            if (rug < 0) {
                continue;
            }
            float before = TrapMath.coinPrice(rug - 1, "doomed", 100.0f, 0.3f, 0.35f);
            float after = TrapMath.coinPrice(rug, "doomed", 100.0f, 0.3f, 0.35f);
            assertTrue(after < before * 0.2f,
                    "a rug should be a cliff, not a dip: " + before + " -> " + after);
            assertTrue(TrapMath.coinDead(rug, "doomed", 0.35f));
            return;
        }
        throw new AssertionError("no era rugged in 200 tries at 35% -- the roll is broken");
    }

    @Test
    void theSpreadAlwaysFavoursTheHouse() {
        // Buy and immediately sell must lose money, or the market is a faucet.
        for (float price : new float[]{0.5f, 12.0f, 180.0f, 5000.0f}) {
            int paid = TrapMath.coinBuyCost(price, 10);
            int back = TrapMath.coinSellValue(price, 10);
            assertTrue(back < paid,
                    "round-tripping at " + price + " paid " + paid + " and returned " + back);
        }
    }

    // --- punters ------------------------------------------------------------------

    @Test
    void aPunterLosesAtTheAdvertisedRate() {
        // The whole point of modelling them rather than replaying the game is
        // that the mean has to be the machine's own return. If it drifts, a
        // casino's books quietly stop matching the plate on its cabinets.
        for (float rtp : new float[]{TrapMath.slotRtp(2), TrapMath.slotRtp(5),
                TrapMath.SCRATCH_MEASURED_RTP, TrapMath.CLIMB_RETURN, 0.97f}) {
            float measured = TrapMath.punterMeasure(rtp, 4242L, 400_000);
            assertEquals(rtp, measured, 0.02f,
                    "punters against a " + rtp + " machine returned " + measured);
            assertTrue(measured < 1.0f, "a punter must not be a money printer");
        }
    }

    @Test
    void theFloorFillsUpAtNight() {
        float noon = TrapMath.casinoHourFactor(6000);
        float dusk = TrapMath.casinoHourFactor(12000);
        float midnight = TrapMath.casinoHourFactor(18000);
        assertTrue(midnight > dusk && dusk > noon,
                "noon " + noon + ", dusk " + dusk + ", midnight " + midnight);
        assertTrue(midnight > noon * 5, "night should be a different room entirely");
        // Never actually zero: a casino provably shut for part of every day is
        // one people stop walking past.
        assertTrue(noon > 0.0f, "the door is never locked");
        // Sunrise and sunset are the crossover and must match, or one of them
        // is secretly better for no reason anybody could learn.
        assertEquals(TrapMath.casinoHourFactor(0), TrapMath.casinoHourFactor(12000), 0.001f);
    }

    @Test
    void punterStakesSpanTheRangeAndLeanSmall() {
        java.util.Random rng = new java.util.Random(31337);
        java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
        long total = 0;
        int rounds = 200_000;
        for (int i = 0; i < rounds; i++) {
            int stake = TrapMath.punterStake(rng, 0);
            assertTrue(stake >= TrapMath.PUNTER_MIN_STAKE
                            && stake <= TrapMath.PUNTER_MAX_STAKE,
                    "stake " + stake + " is outside the advertised range");
            seen.merge(stake, 1, Integer::sum);
            total += stake;
        }
        assertEquals(6, seen.size(), "every band should turn up");
        assertTrue(seen.get(TrapMath.PUNTER_MIN_STAKE) > seen.get(TrapMath.PUNTER_MAX_STAKE) * 5,
                "small punters should far outnumber whales");
        double mean = total / (double) rounds;
        assertTrue(mean > 25 && mean < 60, "mean stake is " + mean);
    }

    @Test
    void aBusyRoomIsACheapRoom() {
        // Seven in and nobody plays above sixteen -- that is what keeps a busy
        // night from being simply a bigger night. You trade the size of the
        // bets for the number of them.
        java.util.Random rng = new java.util.Random(8080);
        int previous = Integer.MAX_VALUE;
        for (int crowd : new int[]{0, 2, 4, 6, 7, 12}) {
            int biggest = 0;
            for (int draw = 0; draw < 20_000; draw++) {
                biggest = Math.max(biggest, TrapMath.punterStake(rng, crowd));
            }
            assertTrue(biggest <= previous,
                    "a fuller room should never bet bigger: " + crowd + " reached " + biggest);
            previous = biggest;
        }
        assertEquals(TrapMath.PUNTER_MAX_STAKE,
                TrapMath.PUNTER_MIN_STAKE << TrapMath.punterStakeCeiling(0));
        assertEquals(16, TrapMath.PUNTER_MIN_STAKE << TrapMath.punterStakeCeiling(7),
                "seven in the room should cap at 16e, as advertised");
    }

    @Test
    void aFloorNobodyHasHeardOfDrawsLeast() {
        float unknown = TrapMath.floorPull(0, 0);
        float known = TrapMath.floorPull(100, 0);
        float hooked = TrapMath.floorPull(0, 100);
        float best = TrapMath.floorPull(100, 100);
        assertTrue(unknown < known && unknown < hooked && known < best,
                unknown + " " + known + " " + hooked + " " + best);
        assertTrue(unknown > 0.0f, "even an unknown room gets somebody");
        // Reputation is the bigger lever, because it is the one you buy with
        // payouts -- and that is the loop that stops a casino being a hoard.
        assertTrue(known > hooked, "paying people out should matter most");
        assertTrue(best <= 2.5f, "the draw must not run away: " + best);
    }

    @Test
    void aCasinoRunsOnAThinMargin() {
        // The whole balance question, and the reason it was got wrong once:
        // the cut was first sized against a seven percent edge read off a live
        // floor, which turned out to be the owner losing to their own machines
        // rather than trade. The villagers hand over about three, and at four
        // percent every floor in the simulator lost money.
        //
        // Played out properly here: a day cycle of average trade on ten
        // machines has to leave a real but modest profit, and doubling the
        // machines without doubling the trade has to take almost all of it.
        // Averaged over many cycles, not one. A single night really can lose
        // money -- one seed here came out 835e down -- and that is the feature
        // rather than a bug in it. What has to hold is the long run.
        java.util.Random rng = new java.util.Random(4242);
        int cycles = 400;
        int rounds = (int) Math.round(4 * (60.0 / 3.5) * 20);   // 20 min, 4 punters
        long handle = 0;
        long paid = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (int i = 0; i < rounds; i++) {
                int stake = TrapMath.punterStake(rng, 4);
                handle += stake;
                paid += Math.round(stake * TrapMath.punterRound(0.97f, rng));
            }
        }
        long gross = (handle - paid) / cycles;
        assertTrue(gross > 0, "the villagers must lose in the long run: " + gross);

        long cut = TrapMath.protectionOn(handle / cycles);
        long lean = gross - cut - 10L * TrapMath.MACHINE_UPKEEP * 2 * 20;
        long bloated = gross - cut - 20L * TrapMath.MACHINE_UPKEEP * 2 * 20;
        assertTrue(lean > 150 && lean < 1200,
                "a day cycle should be worth a few hundred, not thousands: " + lean);
        assertTrue(bloated < lean / 3,
                "twice the machines on the same trade should eat the profit: " + bloated);
    }

    @Test
    void theCutScalesWithPlayNotWithLuck() {
        // Taken on the handle, so a bad night genuinely costs money. That is
        // what a running cost is, and the difference between a business and an
        // allowance.
        assertEquals(0, TrapMath.protectionOn(0));
        assertEquals(TrapMath.protectionOn(1000) * 2, TrapMath.protectionOn(2000));
        assertTrue(TrapMath.protectionOn(-500) == 0, "never a rebate");
    }

    @Test
    void aPitBossIsADecisionRatherThanAnUpgrade() {
        // The wage is flat and the skim is proportional, so the answer changes
        // with how busy you are. If it did not, it would not be a choice.
        long wagePerCycle = TrapMath.PIT_BOSS_WAGE * 2L * 20;
        long quiet = 6_000;
        long busy = 40_000;
        assertTrue(TrapMath.skimOn(quiet) < wagePerCycle,
                "a quiet room is better off without one: " + TrapMath.skimOn(quiet));
        assertTrue(TrapMath.skimOn(busy) > wagePerCycle * 2,
                "a busy one is not: " + TrapMath.skimOn(busy));
        // And a cheat has to actually be a problem, or spotting them is not
        // worth anything either.
        assertTrue(TrapMath.CHEAT_RETURN > 1.0f, "an advantage player wins");
        assertTrue(TrapMath.CHEAT_CHANCE > 0.0f && TrapMath.CHEAT_CHANCE < 0.2f,
                "but not so often that the room is all of them");
    }

    @Test
    void aLooseSpellReallyCosts() {
        // It has to be a loss, or it is not a decision -- it is a button you
        // press whenever it is off cooldown.
        assertTrue(TrapMath.LOOSE_RETURN > 1.0f,
                "running loose must lose money: " + TrapMath.LOOSE_RETURN);
        assertTrue(TrapMath.punterMeasure(TrapMath.LOOSE_RETURN, 11L, 200_000) > 1.0f,
                "and lose it in practice, not just on paper");
        assertTrue(TrapMath.LOOSE_COOLDOWN_BEATS > TrapMath.LOOSE_BEATS * 2,
                "and it cannot simply be left on");
    }

    @Test
    void machinesWearOutOftenEnoughToMatter() {
        // A busy ten-machine floor should throw up something to fix every ten
        // minutes or so: a job, not the only job.
        double roundsPerMinutePerMachine = 60.0 / 3.5;
        double wearPerMinute = roundsPerMinutePerMachine / TrapMath.WEAR_PER_ROUNDS;
        double minutesToBreak = TrapMath.WEAR_BROKEN / wearPerMinute;
        double acrossTen = minutesToBreak / 10;
        assertTrue(acrossTen > 4 && acrossTen < 25,
                "one machine down every " + Math.round(acrossTen) + " minutes");
    }

    @Test
    void neitherStatIsARatchet() {
        // Both used to be counters that filled up and stayed there, which made
        // a casino a thing you switch on rather than a thing you run. Left
        // alone, both must come back down on their own.
        int rep = TrapMath.HOUSE_STAT_MAX;
        int addiction = TrapMath.HOUSE_STAT_MAX;
        for (int beat = 0; beat < 200; beat++) {
            rep = TrapMath.repAfter(rep, TrapMath.houseRepTarget(0, 0, 0, 0, 0, 0, false));
            addiction = TrapMath.addictionAfter(addiction, 0);
        }
        assertEquals(0, rep, "an abandoned floor keeps its name forever");
        assertEquals(0, addiction, "the regulars never forget about the place");
    }

    @Test
    void aBusyFloorCannotHoldTheTop() {
        // A hundred should not be a number anybody sits at. However hard the
        // room is worked, the bleed catches up.
        int addiction = 0;
        for (int beat = 0; beat < 500; beat++) {
            addiction = TrapMath.addictionAfter(addiction, 10_000);
        }
        assertTrue(addiction < TrapMath.HOUSE_STAT_MAX,
                "a maximally busy floor settled at " + addiction);
        assertTrue(addiction > 50, "but a busy floor should still be well up: " + addiction);
    }

    @Test
    void aQueueAtTheDoorIsTheWorstThing() {
        int kept = TrapMath.houseRepTarget(7, 10, 8000, 5, 0, 0, false);
        int full = TrapMath.houseRepTarget(7, 10, 8000, 0, 0, 0, false);
        int queued = TrapMath.houseRepTarget(7, 10, 8000, 0, 3, 0, false);
        assertTrue(full < kept, "no room to play should cost something");
        assertTrue(queued < full - 25,
                "turning people away should hurt far more: " + full + " -> " + queued);
        // And every lever is a decision somebody has to keep making.
        assertTrue(TrapMath.houseRepTarget(1, 10, 8000, 5, 0, 0, false) < kept, "variety matters");
        assertTrue(TrapMath.houseRepTarget(7, 10, 100, 5, 0, 0, false) < kept, "the float matters");
        assertEquals(0, TrapMath.houseRepTarget(0, 0, 99999, 9, 0, 0, false),
                "a casino with no machines is not a casino");
        // The two new levers, both of which the owner has to keep pulling.
        assertTrue(TrapMath.houseRepTarget(7, 10, 8000, 5, 0, TrapMath.WEAR_BROKEN, false)
                        < kept - 20,
                "a floor held together with tape should cost you");
        assertTrue(TrapMath.houseRepTarget(7, 10, 8000, 5, 0, 0, true) > kept
                        || kept >= TrapMath.HOUSE_STAT_MAX,
                "running loose should be worth something");
    }

    @Test
    void aNameFallsFasterThanItClimbs() {
        assertEquals(TrapMath.REP_DRIFT, TrapMath.repAfter(50, 100) - 50);
        assertEquals(TrapMath.REP_DRIFT * 2, 50 - TrapMath.repAfter(50, 0));
        // And never overshoots its target in either direction.
        assertEquals(51, TrapMath.repAfter(51, 51));
        assertEquals(52, TrapMath.repAfter(51, 52));
        assertEquals(50, TrapMath.repAfter(51, 50));
    }

    @Test
    void hookedRegularsStayLonger() {
        java.util.Random rng = new java.util.Random(99);
        int cold = 0;
        int hooked = 0;
        for (int visit = 0; visit < 20_000; visit++) {
            cold += TrapMath.punterRounds(0, rng);
            hooked += TrapMath.punterRounds(TrapMath.HOUSE_STAT_MAX, rng);
        }
        assertTrue(hooked > cold * 1.8, "cold " + cold + " vs hooked " + hooked);
        assertTrue(TrapMath.punterRounds(0, rng) > 0, "a visit is at least one round");
    }

    @Test
    void punterRoundsAreMostlyNothing() {
        // A villager who wins every other round is a villager the owner
        // watches drain their vault, which is the opposite of the feature.
        java.util.Random rng = new java.util.Random(7);
        int paying = 0;
        for (int round = 0; round < 100_000; round++) {
            if (TrapMath.punterRound(0.95f, rng) > 0) {
                paying++;
            }
        }
        assertTrue(paying < 40_000, "punters won " + paying + " rounds in 100k");
        assertTrue(paying > 20_000, "punters won only " + paying + " rounds in 100k");
    }

    // --- scratchcards -------------------------------------------------------------

    @Test
    void theScratchersKeepTheirEdge() {
        java.util.Random rng = new java.util.Random(90210);
        double paid = 0;
        int wins = 0;
        int rounds = 400_000;
        for (int card = 0; card < rounds; card++) {
            float pay = TrapMath.scratchPay(TrapMath.scratchCard(rng));
            paid += pay;
            if (pay > 0) {
                wins++;
            }
        }
        double rtp = paid / rounds;
        double rate = wins / (double) rounds;
        // A machine that pays more than it takes is a money printer, and one
        // that pays far less is a machine nobody plays twice.
        assertTrue(rtp > 0.90 && rtp < 0.99, "scratchcard RTP is " + rtp);
        assertEquals(TrapMath.SCRATCH_MEASURED_RTP, rtp, 0.01,
                "the number the counter prints has drifted from the truth");
        assertEquals(TrapMath.SCRATCH_MEASURED_WIN_RATE, rate, 0.01,
                "the win rate the counter prints has drifted from the truth");
    }

    @Test
    void aCardPaysOnceForItsBestFace() {
        // Three nuggets AND three stars is a star card, not both. Paying both
        // is exactly how the slot machine's return got to 2.69.
        int[] both = {1, 1, 1, 5, 5, 5, 0, 0, 0};
        assertEquals(5, TrapMath.scratchWinner(both));
        // Row 1 is 5,5,5 -- three in a line, so double.
        assertEquals(TrapMath.SCRATCH_PRIZES[5] * TrapMath.SCRATCH_LINE_BONUS,
                TrapMath.scratchPay(both), 0.001f);
    }

    @Test
    void twoOfAKindIsNothing() {
        int[] near = {4, 4, 0, 3, 3, 0, 2, 2, 1};
        assertEquals(0.0f, TrapMath.scratchPay(near), 0.0001f);
        assertEquals(-1, TrapMath.scratchWinner(near));
    }

    @Test
    void theLineBonusOnlyAppliesToExactlyThree() {
        // Four of a face is already paid for by the size multiplier. Stacking
        // both put the top of the tail somewhere no vault could cover.
        int[] three = {3, 3, 3, 0, 0, 0, 0, 0, 1};       // a row
        int[] four = {3, 3, 3, 3, 0, 0, 0, 0, 1};        // a row, plus a spare
        assertEquals(TrapMath.SCRATCH_PRIZES[3] * TrapMath.SCRATCH_LINE_BONUS,
                TrapMath.scratchPay(three), 0.001f);
        assertEquals(TrapMath.SCRATCH_PRIZES[3] * TrapMath.SCRATCH_SIZES[4],
                TrapMath.scratchPay(four), 0.001f);
    }

    @Test
    void everyPanelComesFromTheSameBag() {
        // No position is special and no face is missing from the bag, or the
        // published odds are describing a different game to the one on screen.
        java.util.Random rng = new java.util.Random(5);
        int[] seen = new int[TrapMath.SCRATCH_FACES];
        for (int card = 0; card < 60_000; card++) {
            for (int face : TrapMath.scratchCard(rng)) {
                assertTrue(face >= 0 && face < TrapMath.SCRATCH_FACES, "bad face " + face);
                seen[face]++;
            }
        }
        for (int face = 0; face < TrapMath.SCRATCH_FACES; face++) {
            assertTrue(seen[face] > 0, "face " + face + " never came up");
        }
    }

    // --- getting jumped ---------------------------------------------------------

    @Test
    void aStickUpIsAlwaysMoreThanThree() {
        // The floor is the feature. Three raiders is a patrol you walk away
        // from; the point of this is that dealing in person has teeth.
        for (int rep : new int[]{0, 5, 40, 200}) {
            for (int heat : new int[]{0, 1, 3}) {
                for (int grade : new int[]{0, 2, 5}) {
                    for (int units : new int[]{1, 8, 64}) {
                        int[] squad = TrapMath.stickupSquad(rep, heat, grade, units);
                        int bodies = squad[0] + squad[1] + squad[2];
                        assertTrue(bodies > 3, "only " + bodies + " turned up for rep " + rep);
                        assertTrue(bodies <= 13, bodies + " is a siege, not a robbery");
                    }
                }
            }
        }
    }

    @Test
    void aBiggerNameBringsABiggerCrew() {
        int small = total(TrapMath.stickupSquad(0, 0, 0, 4));
        int known = total(TrapMath.stickupSquad(45, 0, 0, 4));
        int hunted = total(TrapMath.stickupSquad(90, 3, 5, 64));
        assertTrue(known > small, "rep should be felt: " + small + " -> " + known);
        assertTrue(hunted > known, "everything at once should be worst: " + hunted);
        assertEquals(0, TrapMath.stickupSquad(0, 0, 0, 1)[2], "no ravager for a nobody");
        assertTrue(TrapMath.stickupSquad(90, 3, 5, 64)[2] > 0, "a ravager at the top end");
    }

    private static int total(int[] squad) {
        return squad[0] + squad[1] + squad[2];
    }

    @Test
    void mostDealsStillGoFine() {
        // A risk you hit half the time is a tax, and nobody would ever deal in
        // person again -- which would delete the feature this is attached to.
        float worst = TrapMath.stickupChance(3, 500, 999, 9, true, true);
        assertEquals(TrapMath.STICKUP_CAP, worst, 0.0001f);
        assertTrue(worst <= 0.35f, "even the worst case has to be a minority: " + worst);
        float quiet = TrapMath.stickupChance(0, 0, 1, 0, false, false);
        assertTrue(quiet < 0.05f, "a small daylight deal with company: " + quiet);
    }

    @Test
    void companyAndDaylightBothHelp() {
        float exposed = TrapMath.stickupChance(1, 30, 8, 3, true, true);
        float withMate = TrapMath.stickupChance(1, 30, 8, 3, false, true);
        float byDay = TrapMath.stickupChance(1, 30, 8, 3, true, false);
        assertTrue(withMate < exposed, "a friend nearby should help: " + withMate);
        assertTrue(byDay < exposed, "daylight should help: " + byDay);
        // Same direction as Paranoia's company rule, so there is one lesson.
        assertTrue(TrapMath.stickupChance(1, 30, 8, 3, false, false) < withMate);
    }

    // --- contract drop-offs -----------------------------------------------------

    @Test
    void everyJobOnTheBoardGoesSomewhereElse() {
        // The bug: one village lookup per player per day meant all five jobs
        // pointed at the same place, so you could stand next to it and sell
        // your whole stash to one man without ever making a delivery.
        int min = 250;
        int max = 800;
        for (long day = 0; day < 200; day++) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int slot = 0; slot < 5; slot++) {
                int[] at = TrapMath.dropOffset(day * 131071L + 4242L * 31L + slot * 7919L,
                        min, max);
                double away = Math.sqrt((double) at[0] * at[0] + (double) at[1] * at[1]);
                assertTrue(away >= min - 1 && away <= max + 1,
                        "drop " + away + " blocks out, band is " + min + ".." + max);
                seen.add(at[0] + "," + at[1]);
            }
            assertEquals(5, seen.size(), "day " + day + " put two jobs in one place");
        }
    }

    @Test
    void theBoardIsTheSameBoardAfterARelog() {
        // Re-seeded from the day and the slot, so closing the phone and
        // reopening it must not reshuffle the work.
        for (int slot = 0; slot < 5; slot++) {
            long seed = 7L * 131071L + 99L * 31L + slot * 7919L;
            assertArrayEquals(TrapMath.dropOffset(seed, 250, 800),
                    TrapMath.dropOffset(seed, 250, 800));
        }
    }

    // --- the casino floor -------------------------------------------------------

    @Test
    void aVaultNeverPromisesMoreThanItHolds() {
        // The one invariant the whole feature rests on: whatever bet a table
        // accepts, the vault can settle it at that game's top multiple. If
        // this ever fails a machine mints emeralds out of nothing.
        int[] tops = {5, 12, 26, 30, 36, 64};
        long[] vaults = {0, 1, 63, 500, 10_000, 4_000_000_000L};
        for (int top : tops) {
            for (long vault : vaults) {
                int most = TrapMath.houseLimit(vault, top);
                assertTrue(most >= 0, "a limit is never negative: " + most);
                assertTrue(TrapMath.houseCovers(vault, most, top),
                        "vault " + vault + " offered " + most + "e at " + top + "x");
                if (most < Integer.MAX_VALUE) {
                    assertFalse(TrapMath.houseCovers(vault, most + 1, top),
                            "vault " + vault + " should have refused "
                                    + (most + 1) + "e at " + top + "x");
                }
            }
        }
    }

    @Test
    void anEmptyVaultTakesNoBets() {
        assertEquals(0, TrapMath.houseLimit(0, TrapMath.ROULETTE_STRAIGHT));
        assertFalse(TrapMath.houseCovers(0, 1, TrapMath.ROULETTE_STRAIGHT));
        // And a vault big enough to overflow an int limit still answers with a
        // usable number rather than a negative one.
        assertTrue(TrapMath.houseLimit(Long.MAX_VALUE, 2) > 0);
    }

    // --- the dealer network -----------------------------------------------------

    @Test
    void dealersSellBestAtNight() {
        float midnight = TrapMath.dealerHourFactor(18000);
        float noon = TrapMath.dealerHourFactor(6000);
        assertTrue(midnight > noon * 2,
                "night should be worth far more than noon: " + midnight + " vs " + noon);
        assertTrue(noon > 0.0f, "but trade never stops entirely");
        // Dawn and dusk are the crossover, and must match -- an asymmetric
        // curve would mean one of them was secretly better for no reason.
        assertEquals(TrapMath.dealerHourFactor(0), TrapMath.dealerHourFactor(12000), 0.02f);
    }

    @Test
    void theHourCurveIsBoundedAllDay() {
        for (long time = 0; time < 24000; time += 137) {
            float factor = TrapMath.dealerHourFactor(time);
            assertTrue(factor > 0.4f && factor < 1.6f,
                    "hour factor went to " + factor + " at " + time);
        }
    }

    @Test
    void betterDealersAreBetterButCostlier() {
        for (int level = 2; level <= TrapMath.DEALER_MAX_LEVEL; level++) {
            assertTrue(TrapMath.dealerRate(level, 1, 0) > TrapMath.dealerRate(level - 1, 1, 0),
                    "level " + level + " should shift more");
            assertTrue(TrapMath.dealerSlots(level) > TrapMath.dealerSlots(level - 1),
                    "level " + level + " should carry more");
            assertTrue(TrapMath.dealerHireCost(level) > TrapMath.dealerHireCost(level - 1),
                    "level " + level + " should cost more");
            assertTrue(TrapMath.dealerCut(level) > TrapMath.dealerCut(level - 1),
                    "level " + level + " should keep a bigger share");
            assertTrue(TrapMath.dealerRobChance(level) < TrapMath.dealerRobChance(level - 1),
                    "level " + level + " should be robbed less");
        }
    }

    @Test
    void aBetterDealerStillLeavesYouAhead() {
        // The cut rises with level, so it has to be checked that the extra
        // throughput more than covers it -- otherwise the expensive ones are a
        // trap and the whole progression is fake.
        for (int level = 2; level <= TrapMath.DEALER_MAX_LEVEL; level++) {
            float better = TrapMath.dealerRate(level, 1, 0) * (1 - TrapMath.dealerCut(level));
            float worse = TrapMath.dealerRate(level - 1, 1, 0)
                    * (1 - TrapMath.dealerCut(level - 1));
            assertTrue(better > worse,
                    "a level " + level + " nets you " + better + " against " + worse);
        }
    }

    @Test
    void everyLevelFitsTheGrid() {
        // Slots are drawn into an 18-slot grid. A level that wanted more
        // would silently lose the overflow.
        assertTrue(TrapMath.dealerSlots(TrapMath.DEALER_MAX_LEVEL) <= 18,
                "the top level wants " + TrapMath.dealerSlots(TrapMath.DEALER_MAX_LEVEL)
                        + " slots and the grid holds 18");
        assertEquals(TrapMath.DEALER_MAX_LEVEL, TrapMath.DEALER_XP.length,
                "every level needs a threshold to reach it");
    }

    @Test
    void reputationMakesTheLadderClimbable() {
        int level = TrapMath.DEALER_MAX_LEVEL;
        int full = TrapMath.dealerHireCost(level, 0);
        assertEquals(full, TrapMath.dealerHireCost(level), "no rep, no discount");
        assertTrue(TrapMath.dealerHireCost(level, 20) < full, "rep should cut the price");
        // Capped: reputation opens the door, it doesn't hand you the keys.
        assertTrue(TrapMath.dealerHireCost(level, 10000)
                        >= Math.round(full * (1 - TrapMath.REP_DISCOUNT_CAP)) - 1,
                "the discount must stay capped however much rep you have");
        assertTrue(TrapMath.dealerLearnRate(40) > TrapMath.dealerLearnRate(0),
                "rep should speed levelling too");
    }

    @Test
    void crowdingThePatchHasDiminishingReturns() {
        float alone = TrapMath.dealerRate(3, 1, 0);
        float four = TrapMath.dealerRate(3, 4, 0) * 4;
        assertTrue(four > alone, "four should still beat one in total");
        assertTrue(four < alone * 2.5f,
                "but four must not be four times as good, got " + four / alone + "x");
    }

    @Test
    void heatSlowsTradeWithoutStoppingIt() {
        assertTrue(TrapMath.dealerRate(3, 1, 3) < TrapMath.dealerRate(3, 1, 0));
        assertTrue(TrapMath.dealerRate(3, 1, 3) > 0.0f, "heat must never freeze the street");
    }

    @Test
    void aFractionalRateStillSellsSometimes() {
        // A rate under one has to mean "sometimes", not "never" -- truncating
        // would make a level one at noon look broken rather than slow.
        int sold = 0;
        for (int i = 0; i < 1000; i++) {
            sold += TrapMath.dealerSold(0.4f, 1.0f, i / 1000.0f);
        }
        assertTrue(sold > 300 && sold < 500,
                "a rate of 0.4 over 1000 rounds sold " + sold + ", expected about 400");
    }

    // --- the coin toss ----------------------------------------------------------

    @Test
    void everyCallOnTheCoinIsPricedTheSame() {
        for (int called = 0; called < 3; called++) {
            float rtp = TrapMath.tossReturnToPlayer(called);
            assertTrue(rtp < 1.0f, "call " + called + " returns " + rtp + " -- the house loses");
            assertTrue(rtp > 0.93f, "call " + called + " returns " + rtp + " -- too mean");
        }
    }

    @Test
    void theCoinLandsOnAllThreeAndOnlyPaysTheCaller() {
        boolean[] seen = new boolean[3];
        for (int i = 0; i < 1000; i++) {
            seen[TrapMath.tossResult(i / 1000.0f)] = true;
        }
        assertTrue(seen[0] && seen[1] && seen[2], "all three outcomes must be reachable");
        for (int called = 0; called < 3; called++) {
            for (int result = 0; result < 3; result++) {
                if (called != result) {
                    assertEquals(0.0f, TrapMath.tossReturn(called, result), 0.0001f);
                }
            }
        }
    }

    // --- blackjack --------------------------------------------------------------

    @Test
    void acesCountTheWayThatHelps() {
        assertEquals(21, TrapMath.handValue(new int[]{1, 13}, 2), "ace and a king is 21");
        assertEquals(12, TrapMath.handValue(new int[]{1, 1}, 2), "two aces is 12, not 22");
        assertEquals(13, TrapMath.handValue(new int[]{1, 1, 1}, 3), "three aces is 13");
        assertEquals(21, TrapMath.handValue(new int[]{1, 5, 5}, 3), "ace five five is 21");
        assertEquals(16, TrapMath.handValue(new int[]{10, 5, 1}, 3),
                "an ace that would bust you counts one");
        assertEquals(30, TrapMath.handValue(new int[]{13, 12, 11}, 3), "no aces, no mercy");
    }

    @Test
    void onlyTwoCardsMakeABlackjack() {
        assertTrue(TrapMath.isBlackjack(new int[]{1, 10}, 2));
        assertFalse(TrapMath.isBlackjack(new int[]{7, 7, 7}, 3), "21 on three cards is just 21");
        assertFalse(TrapMath.isBlackjack(new int[]{10, 10}, 2));
    }

    @Test
    void theDealerStandsWhereItSaysItDoes() {
        assertTrue(TrapMath.dealerHits(new int[]{10, 6}, 2), "sixteen takes another");
        assertFalse(TrapMath.dealerHits(new int[]{10, 7}, 2), "seventeen stands");
        assertFalse(TrapMath.dealerHits(new int[]{1, 6}, 2), "soft seventeen stands too");
    }

    @Test
    void blackjackPaysBetterThanAPlainWin() {
        assertTrue(TrapMath.BLACKJACK_PAY > 2.0f, "a natural must beat an ordinary win");
        assertTrue(TrapMath.BLACKJACK_PAY < 2.5f,
                "but this house pays six to five, not three to two");
    }

    // --- the pawn counter -------------------------------------------------------

    @Test
    void aStackOfAnythingIsWorthSomething() {
        // Individually junk is worth a fraction of an emerald, which is the
        // point -- but a full stack of it must always come to at least one, or
        // the counter refuses ordinary items with no explanation it can give.
        for (int nutrition = 0; nutrition <= 20; nutrition++) {
            for (int rarity = 0; rarity < 4; rarity++) {
                float stack = TrapMath.scrapPrice(nutrition, nutrition * 0.6f, rarity, 0, 64) * 64;
                assertTrue(Math.round(stack) >= 1,
                        "a stack priced at nothing: food " + nutrition + " rarity " + rarity);
            }
        }
    }

    @Test
    void plainJunkIsWorthUnderAnEmeraldEach() {
        // The trap this pricing exists to avoid. One log makes four planks
        // makes eight sticks. Round an ordinary stackable item up to one
        // emerald apiece and a crafting table out-earns every other way of
        // making money in this mod, forever.
        //
        // A whole stack of sticks may be worth a couple of emeralds; a stick
        // may not be worth one.
        float junk = TrapMath.scrapPrice(0, 0, 0, 0, 64);
        assertTrue(junk < 0.5f, "plain junk is priced at " + junk + " each -- that is a money loop");
        assertTrue(junk > 0.0f, "but it must still be worth something in bulk");
    }

    @Test
    void theCounterPaysLessThanTheShelf() {
        // It is a pawn counter. One that paid the market price would make the
        // shelves pointless and every price on them a suggestion.
        assertTrue(TrapMath.SCRAP_RATE < 1.0f);
        assertTrue(TrapMath.SCRAP_RATE > 0.2f, "but not so mean nobody bothers");
    }

    @Test
    void betterThingsFetchMore() {
        float plain = TrapMath.scrapPrice(0, 0, 0, 0, 64);
        assertTrue(TrapMath.scrapPrice(0, 0, 1, 0, 64) > plain, "uncommon should beat common");
        assertTrue(TrapMath.scrapPrice(0, 0, 3, 0, 64)
                > TrapMath.scrapPrice(0, 0, 2, 0, 64), "epic should beat rare");
        assertTrue(TrapMath.scrapPrice(0, 0, 0, 1561, 1)
                > TrapMath.scrapPrice(0, 0, 0, 59, 1), "diamond tools beat wooden ones");
        assertTrue(TrapMath.scrapPrice(8, 12.8f, 0, 0, 64) > plain, "food beats not-food");
    }

    @Test
    void aBetterBookIsWorthMore() {
        assertTrue(TrapMath.scrapBookPrice(5, 1) > TrapMath.scrapBookPrice(1, 1),
                "Sharpness V should beat Sharpness I");
        assertTrue(TrapMath.scrapBookPrice(6, 2) > TrapMath.scrapBookPrice(5, 1),
                "two enchantments should beat one");
        assertTrue(TrapMath.scrapBookPrice(0, 0) >= 2, "even a nothing book is worth something");
    }

    // --- roulette -------------------------------------------------------------

    @Test
    void everyBetOnTheTableHasTheSameEdge() {
        // The nice property of a single-zero wheel, and the one thing a payout
        // typo would silently break: straight up or flat on red, the return is
        // identical, so the choice is only about how you want to lose it.
        float expected = 36.0f / 37.0f;
        for (String bet : TrapMath.rouletteBets()) {
            assertEquals(expected, TrapMath.rouletteReturnToPlayer(bet), 0.0001f,
                    "bet '" + bet + "' is not priced like the rest of the table");
        }
    }

    @Test
    void theWheelIsHalfRedHalfBlackAndOneGreen() {
        int reds = 0;
        int blacks = 0;
        for (int pocket = 0; pocket < TrapMath.ROULETTE_POCKETS; pocket++) {
            if (TrapMath.rouletteRed(pocket)) {
                reds++;
            }
            if (TrapMath.rouletteBlack(pocket)) {
                blacks++;
            }
        }
        assertEquals(18, reds);
        assertEquals(18, blacks);
        assertFalse(TrapMath.rouletteRed(0), "zero is green, not red");
        assertFalse(TrapMath.rouletteBlack(0), "zero is green, not black");
    }

    @Test
    void zeroTakesEveryOutsideBet() {
        // Where the entire house edge comes from. If any of these ever pays,
        // the table is a losing business.
        for (String bet : List.of("red", "black", "odd", "even", "low", "high")) {
            assertEquals(0, TrapMath.rouletteReturn(bet, 0),
                    "'" + bet + "' must lose to zero");
        }
        assertEquals(TrapMath.ROULETTE_STRAIGHT, TrapMath.rouletteReturn("0", 0),
                "but backing zero itself must pay");
    }

    @Test
    void theHouseWinsOnRouletteToo() {
        assertTrue(TrapMath.rouletteReturnToPlayer("red") < 1.0f);
    }

    @Test
    void exactlyOneOfEachOppositePairWins() {
        // No pocket may pay both red and black, or both odd and even. A wheel
        // that pays both sides of a coin flip is a money printer.
        for (int pocket = 1; pocket < TrapMath.ROULETTE_POCKETS; pocket++) {
            assertTrue(TrapMath.rouletteReturn("red", pocket) == 0
                            ^ TrapMath.rouletteReturn("black", pocket) == 0,
                    "pocket " + pocket + " pays both red and black, or neither");
            assertTrue(TrapMath.rouletteReturn("odd", pocket) == 0
                            ^ TrapMath.rouletteReturn("even", pocket) == 0,
                    "pocket " + pocket + " pays both odd and even, or neither");
            assertTrue(TrapMath.rouletteReturn("low", pocket) == 0
                            ^ TrapMath.rouletteReturn("high", pocket) == 0,
                    "pocket " + pocket + " pays both halves, or neither");
        }
    }

    // --- investments ----------------------------------------------------------

    @Test
    void waitingLongerPaysMoreOnAFlatMarket() {
        float shortTerm = TrapMath.investReturn(1, 1.0f, 1.0f, 0.5f);
        float longTerm = TrapMath.investReturn(7, 1.0f, 1.0f, 0.5f);
        assertTrue(longTerm > shortTerm);
    }

    @Test
    void buyingLowAndSellingHighWins() {
        float rose = TrapMath.investReturn(3, 0.8f, 1.4f, 0.5f);
        float fell = TrapMath.investReturn(3, 1.4f, 0.8f, 0.5f);
        assertTrue(rose > fell, "the market direction must matter");
    }

    @Test
    void anInvestmentCanActuallyLose() {
        assertTrue(TrapMath.investReturn(1, 1.6f, 0.7f, 0.0f) < 1.0f,
                "a crash with bad luck has to be able to lose money");
    }

    @Test
    void butNeverWipesYouOut() {
        assertTrue(TrapMath.investReturn(7, 9.0f, 0.1f, 0.0f) >= 0.15f);
    }

    // --- ledger ---------------------------------------------------------------

    @Test
    void aggregateMergesAndCountsContainers() {
        List<TrapMath.Tally<String>> rows = TrapMath.aggregate(List.of(
                Map.entry("iron", 64),
                Map.entry("iron", 320),
                Map.entry("gold", 12)));

        assertEquals(2, rows.size());
        assertEquals("iron", rows.get(0).key());
        assertEquals(384, rows.get(0).total());
        assertEquals(2, rows.get(0).containers());
        assertEquals(1, rows.get(1).containers());
    }

    @Test
    void aggregateSortsBiggestPileFirst() {
        List<TrapMath.Tally<String>> rows = TrapMath.aggregate(List.of(
                Map.entry("dirt", 3),
                Map.entry("stone", 900),
                Map.entry("oak", 40)));

        assertEquals(List.of("stone", "oak", "dirt"),
                rows.stream().map(TrapMath.Tally::key).toList());
    }

    @Test
    void aggregateIgnoresEmptyFinds() {
        assertTrue(TrapMath.aggregate(List.of(Map.entry("ghost", 0))).isEmpty());
    }
}
