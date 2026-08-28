package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wand ladder, checked where it can be checked without a game.
 *
 * WandItem needs a World and an ItemStack for everything it does, so the two
 * multipliers behind the tiers live in TrapMath and this reads them there --
 * same arrangement as {@link FormulaTest}. What is actually worth a test is
 * the pair of things that go wrong silently: a tier arriving off a data
 * component that nobody wrote (or that somebody wrote a 7 into) indexing
 * straight off the end of the multiplier array, and a cooldown rounding to
 * zero, which is not a fast wand but an item with no cooldown at all.
 */
class WandTierTest {

    @Test
    void aTierNobodyWroteIsTheOneYouBought() {
        assertEquals(0, TrapMath.wandTier(0));
        assertEquals(200, TrapMath.wandCooldown(200, 0));
        assertEquals(10, TrapMath.wandReach(10, 0));
    }

    @Test
    void nonsenseTiersAreClampedRatherThanThrown() {
        assertEquals(0, TrapMath.wandTier(-4));
        assertEquals(TrapMath.WAND_TIERS - 1, TrapMath.wandTier(99));
        // The point of the clamp: these are array lookups.
        assertEquals(TrapMath.wandCooldown(100, TrapMath.WAND_TIERS - 1),
                TrapMath.wandCooldown(100, 99));
    }

    @Test
    void everyTierIsFasterAndFurtherThanTheOneBelow() {
        for (int tier = 1; tier < TrapMath.WAND_TIERS; tier++) {
            assertTrue(TrapMath.wandCooldown(300, tier) < TrapMath.wandCooldown(300, tier - 1),
                    "tier " + tier + " must come round sooner");
            assertTrue(TrapMath.wandReach(12, tier) > TrapMath.wandReach(12, tier - 1),
                    "tier " + tier + " must go further");
            assertTrue(TrapMath.wandDamage(12.0f, tier) > TrapMath.wandDamage(12.0f, tier - 1),
                    "tier " + tier + " must hit harder");
        }
    }

    @Test
    void aShortCooldownNeverRoundsAwayEntirely() {
        // The builder's is the shortest at 30 ticks and stays well clear, but
        // a cooldown of 1 scaled by 0.6 rounds to 1 rather than to a wand that
        // can be held down.
        for (int tier = 0; tier < TrapMath.WAND_TIERS; tier++) {
            assertTrue(TrapMath.wandCooldown(1, tier) >= 1);
            assertTrue(TrapMath.wandCooldown(30, tier) >= 1);
        }
    }

    @Test
    void theLadderIsWhatTheTooltipsPromise() {
        // -20% a step and +25% a step, which is what the guide book says out
        // loud. If these move, that page moves with them.
        assertEquals(40, TrapMath.wandCooldown(40, 0));
        assertEquals(32, TrapMath.wandCooldown(40, 1));
        assertEquals(24, TrapMath.wandCooldown(40, 2));

        assertEquals(12, TrapMath.wandReach(12, 0));
        assertEquals(15, TrapMath.wandReach(12, 1));
        assertEquals(18, TrapMath.wandReach(12, 2));
    }

    @Test
    void theLadderCostsHalfTheShelfAndThenAllOfIt() {
        // Rush is 25,000e on the rack: 12,500e for II and 25,000e for III, so
        // a finished one has cost half again what it cost to buy.
        assertEquals(12_500, TrapMath.wandPrice(25_000, 0));
        assertEquals(25_000, TrapMath.wandPrice(25_000, 1));
        // Storms, the far end of the same rule.
        assertEquals(60_000, TrapMath.wandPrice(120_000, 0));
        assertEquals(120_000, TrapMath.wandPrice(120_000, 1));
    }

    @Test
    void anUpgradeIsNeverFree() {
        // The price is read off a catalogue line, and a catalogue is data. A
        // free tier is worse than a wrong price: it hands out the top of the
        // market for a right-click.
        assertTrue(TrapMath.wandPrice(0, 0) >= 1);
        assertTrue(TrapMath.wandPrice(1, 0) >= 1);
    }
}
