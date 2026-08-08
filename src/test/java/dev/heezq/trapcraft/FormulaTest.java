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
