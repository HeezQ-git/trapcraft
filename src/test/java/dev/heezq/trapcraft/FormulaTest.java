package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aPriceHoldsStillForTheWholeDay() {
        assertEquals(TrapMath.dailyDrift(40, "minecraft:diamond"),
                TrapMath.dailyDrift(40, "minecraft:diamond"), 0.0f);
    }

    @Test
    void differentItemsMoveDifferentlyOnTheSameDay() {
        assertNotEquals(TrapMath.dailyDrift(40, "minecraft:diamond"),
                TrapMath.dailyDrift(40, "minecraft:bread"));
    }

    @Test
    void driftStaysWithinItsBand() {
        for (long day = 0; day < 400; day++) {
            float d = TrapMath.dailyDrift(day, "minecraft:diamond");
            assertTrue(d >= 1.0f - TrapMath.DRIFT - 0.001f && d <= 1.0f + TrapMath.DRIFT + 0.001f,
                    "day " + day + " drifted to " + d);
        }
    }

    @Test
    void sellingAlwaysPaysLessThanBuying() {
        for (int base : new int[]{2, 5, 40, 400, 1200}) {
            int buy = TrapMath.buyPrice(base, 1.0f, 1.0f);
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
        assertTrue(TrapMath.buyPrice(1, TrapMath.INDEX_MIN, 1.0f - TrapMath.DRIFT) >= 1);
    }

    // --- the slot machine -----------------------------------------------------

    @Test
    void theHouseKeepsItsEdge() {
        float rtp = TrapMath.slotReturnToPlayer();
        assertTrue(rtp < 1.0f, "the house must win long-run, got " + rtp);
        assertTrue(rtp > 0.55f, "but not so hard nobody plays, got " + rtp);
    }

    @Test
    void mostSpinsPayNothing() {
        int losses = 0;
        for (int i = 0; i < 1000; i++) {
            if (TrapMath.slotPayout(i / 1000.0f) == 0.0f) {
                losses++;
            }
        }
        assertTrue(losses > 830, "the great majority of spins must lose, got " + losses);
    }

    @Test
    void theJackpotIsRareAndReal() {
        assertEquals(TrapMath.SLOT_PAYS[0], TrapMath.slotPayout(0.0f), 0.001f);
        assertEquals(0.0f, TrapMath.slotPayout(0.999f), 0.001f);
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
