package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        assertTrue(TrapMath.marketIndex(6000f) > TrapMath.marketIndex(500f));
    }

    @Test
    void theIndexCannotRunAway() {
        assertEquals(TrapMath.INDEX_MAX, TrapMath.marketIndex(9_000_000f), 0.001f);
        assertEquals(TrapMath.INDEX_MIN, TrapMath.marketIndex(0f), 0.001f);
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
        float rtp = TrapMath.slotMeasure(20260808L, 120_000, rate);

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
        assertEquals(TrapMath.SLOT_MEASURED_RTP, rtp, 0.02f,
                "SLOT_MEASURED_RTP is stale -- the machine now returns " + rtp);
        assertEquals(TrapMath.SLOT_MEASURED_WIN_RATE, rate[0], 0.02f,
                "SLOT_MEASURED_WIN_RATE is stale -- now " + rate[0]);
    }

    @Test
    void aWinIsNeverAlsoALoss() {
        // The smallest win must return at least the stake. Paying less than
        // you put in is a loss wearing a party hat, and a machine full of them
        // is one nobody can tell they are losing at.
        float smallest = Float.MAX_VALUE;
        for (TrapMath.SlotShape shape : TrapMath.slotShapes()) {
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
        assertEquals(20, TrapMath.slotLines().length);
        for (int[] line : TrapMath.slotLines()) {
            assertTrue(line.length >= 3, "a line shorter than three can never win");
            for (int cell : line) {
                assertTrue(cell >= 0 && cell < 25, "line ran off the board at " + cell);
            }
        }
    }

    @Test
    void everyShapeFitsOnTheBoard() {
        for (TrapMath.SlotShape shape : TrapMath.slotShapes()) {
            assertTrue(shape.pay() > 0, shape.name() + " pays nothing");
            for (int cell : shape.cells()) {
                assertTrue(cell >= 0 && cell < 25,
                        shape.name() + " runs off the board at " + cell);
            }
        }
    }

    @Test
    void aQuietBoardWinsNothing() {
        assertFalse(TrapMath.slotScore(quietGrid()).won(),
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
        float bottomOnly = TrapMath.slotScore(grid).pay();
        assertTrue(bottomOnly > 0, "three along the bottom should pay");

        for (int row = 0; row < 3; row++) {
            grid[row * 5 + 3] = 6;
        }
        TrapMath.SlotScore both = TrapMath.slotScore(grid);
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
        TrapMath.SlotScore score = TrapMath.slotScore(grid);
        assertTrue(score.won(), "a 2x2 block should pay");
        assertTrue(score.names().contains("Block"), "and should say so: " + score.names());
    }

    @Test
    void theHighlightIsExactlyWhatPaid() {
        int[] grid = quietGrid();
        for (int col = 0; col < 4; col++) {
            grid[2 * 5 + col] = 7;
        }
        TrapMath.SlotScore score = TrapMath.slotScore(grid);
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
            int[] grid = TrapMath.slotBoard(rng, null);
            assertFalse(TrapMath.slotScore(grid).won(),
                    "generator produced a losing board that actually won");
        }
    }

    @Test
    void aWinningBoardReallyWins() {
        java.util.Random rng = new java.util.Random(1234L);
        for (String plan : TrapMath.SLOT_PLANS) {
            for (int spin = 0; spin < 200; spin++) {
                assertTrue(TrapMath.slotScore(TrapMath.slotBoard(rng, plan)).won(),
                        "plan " + plan + " produced a board that pays nothing");
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
