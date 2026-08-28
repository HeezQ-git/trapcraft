package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cases, checked where being wrong is expensive and silent.
 *
 * Three of these guard money. A case whose contents resell for more than its
 * key cost is a mint, and it would present as nothing at all for weeks --
 * just a market that keeps sagging and a couple of players who are suddenly
 * very rich. The other two guard the feeling: a grade that pays less than the
 * one below it, and weights that don't add up so the last band never lands.
 */
class CaseOddsTest {

    @Test
    void everyCaseAddsUpToOneRoll() {
        int total = 0;
        for (CaseOdds.Grade grade : CaseOdds.Grade.values()) {
            total += grade.weight();
        }
        assertEquals(CaseOdds.DRAWS, total);
    }

    @Test
    void everyDrawLandsSomewhereAndTheRareBandIsReachable() {
        boolean sawExotic = false;
        for (int draw = 0; draw < CaseOdds.DRAWS; draw++) {
            CaseOdds.Grade grade = CaseOdds.gradeFor(draw);
            assertNotNull(grade, "draw " + draw + " landed on nothing");
            sawExotic |= grade == CaseOdds.Grade.EXOTIC;
        }
        // The whole feature is this band existing. An off-by-one in the
        // cumulative walk would hide it and nothing else would look wrong.
        assertTrue(sawExotic, "the gold band is unreachable");
        assertEquals(CaseOdds.Grade.MIL_SPEC, CaseOdds.gradeFor(0));
        assertEquals(CaseOdds.Grade.EXOTIC, CaseOdds.gradeFor(CaseOdds.DRAWS - 1));
    }

    @Test
    void everyGradeOfEveryCaseHasSomethingInIt() {
        for (CaseOdds.Tier tier : CaseOdds.Tier.values()) {
            for (CaseOdds.Grade grade : CaseOdds.Grade.values()) {
                List<CaseOdds.Reward> pool = CaseOdds.pool(tier, grade);
                assertNotNull(pool, tier + " has no " + grade + " pool");
                assertTrue(!pool.isEmpty(), tier + " " + grade + " is empty");
                for (CaseOdds.Reward reward : pool) {
                    assertTrue(!reward.drops().isEmpty(),
                            tier + " " + grade + " " + reward.label() + " drops nothing");
                    assertTrue(reward.worth() > 0,
                            tier + " " + grade + " " + reward.label() + " is worth nothing");
                    for (CaseOdds.Drop drop : reward.drops()) {
                        assertTrue(drop.count() > 0, "a drop of zero " + drop.id());
                        assertTrue(drop.id().contains(":"), "unqualified id " + drop.id());
                    }
                }
            }
        }
    }

    /**
     * The one that stops a crafting-table-with-a-mint.
     *
     * Buy the key, open the case, walk the contents to the counter. If that
     * ever comes back above the key price the market has a hole in it that
     * scales with however many keys somebody can afford.
     */
    @Test
    void noCaseCanBeResoldForMoreThanItsKey() {
        for (CaseOdds.Tier tier : CaseOdds.Tier.values()) {
            double backAtTheCounter = CaseOdds.expectedWorth(tier) * CaseOdds.RESALE_CEILING;
            assertTrue(backAtTheCounter < tier.keyPrice(),
                    tier + " prints money: " + Math.round(backAtTheCounter)
                            + "e back on a " + tier.keyPrice() + "e key");
        }
    }

    /**
     * And the one that stops the opposite mistake.
     *
     * A case that averages less than its key is a slot machine nobody would
     * pull twice; one that averages several times its key is a shop with the
     * prices written backwards. Between 1.15x and 1.6x of the key in SHELF
     * value is the band where opening is clearly worth it and reselling
     * clearly isn't.
     */
    @Test
    void everyCasePaysMoreInGoodsThanTheKeyCost() {
        for (CaseOdds.Tier tier : CaseOdds.Tier.values()) {
            double ratio = CaseOdds.expectedWorth(tier) / tier.keyPrice();
            assertTrue(ratio > 1.15 && ratio < 1.60,
                    tier + " pays " + String.format("%.2f", ratio) + "x its key");
        }
    }

    @Test
    void gradesGetBetterAsTheyGetRarer() {
        for (CaseOdds.Tier tier : CaseOdds.Tier.values()) {
            double below = 0;
            for (CaseOdds.Grade grade : CaseOdds.Grade.values()) {
                double worst = CaseOdds.pool(tier, grade).stream()
                        .mapToInt(CaseOdds.Reward::worth).min().orElseThrow();
                assertTrue(worst > below,
                        tier + " " + grade + " can pay less than the band below it");
                below = CaseOdds.pool(tier, grade).stream()
                        .mapToInt(CaseOdds.Reward::worth).max().orElseThrow();
            }
        }
    }

    @Test
    void biggerCasesArePricedAboveSmallerOnes() {
        CaseOdds.Tier[] tiers = CaseOdds.Tier.values();
        for (int i = 1; i < tiers.length; i++) {
            assertTrue(tiers[i].keyPrice() > tiers[i - 1].keyPrice());
            assertTrue(CaseOdds.expectedWorth(tiers[i]) > CaseOdds.expectedWorth(tiers[i - 1]));
        }
    }

    /**
     * The trade-up cannot undercut the shelf.
     *
     * Four keys into one of the next tier is only fair while four of the
     * cheaper ones cost at least what the dearer one does -- otherwise the
     * craft is the cheapest route to every key above the bottom, and the
     * prices on four shelves stop meaning anything.
     */
    @Test
    void tradingUpIsNeverCheaperThanBuying() {
        for (CaseOdds.Tier tier : CaseOdds.Tier.values()) {
            CaseOdds.Tier above = tier.above();
            if (above == null) {
                continue;
            }
            assertTrue(CaseOdds.TRADE_UP * tier.keyPrice() >= above.keyPrice(),
                    CaseOdds.TRADE_UP + "x " + tier + " undercuts " + above);
        }
        assertNotNull(CaseOdds.Tier.STREET.above());
        assertEquals(null, CaseOdds.Tier.PHANTOM.above());
    }

    @Test
    void idsAreDistinct() {
        for (CaseOdds.Tier tier : CaseOdds.Tier.values()) {
            for (CaseOdds.Tier other : CaseOdds.Tier.values()) {
                if (tier == other) {
                    continue;
                }
                assertTrue(!tier.caseId().equals(other.caseId()));
                assertTrue(!tier.keyId().equals(other.keyId()));
            }
            // The two halves of a tier must never collide either -- they are
            // separate registry entries and separate shelf lines.
            assertTrue(!tier.caseId().equals(tier.keyId()));
            assertEquals("item.trapcraft." + tier.caseId(), tier.caseKey());
            assertEquals("item.trapcraft." + tier.keyId(), tier.keyKey());
        }
    }
}
