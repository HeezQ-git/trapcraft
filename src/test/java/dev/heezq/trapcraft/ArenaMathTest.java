package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaMathTest {

    @Test
    void healthGrowsWithTheRoomAndNeverBelowOnePlayer() {
        assertEquals(ArenaMath.BASE_HEALTH, ArenaMath.bossHealth(0));
        assertEquals(ArenaMath.BASE_HEALTH, ArenaMath.bossHealth(1));
        assertTrue(ArenaMath.bossHealth(3) > ArenaMath.bossHealth(2));
        assertEquals(ArenaMath.BASE_HEALTH + 2 * ArenaMath.HEALTH_PER_EXTRA, ArenaMath.bossHealth(3));
    }

    @Test
    void aHitIsCappedAtASliceOfTheBar() {
        // Six percent of a three-player boss, so a modded sword cannot delete it.
        assertEquals(ArenaMath.bossHealth(3) * ArenaMath.HIT_CAP,
                ArenaMath.hitCap(ArenaMath.bossHealth(3)), 0.001F);
        assertEquals(1.0F, ArenaMath.hitCap(0.0F));
    }

    @Test
    void phasesTurnAtTheirPercentages() {
        assertEquals(1, ArenaMath.phaseOf(1.0F));
        assertEquals(1, ArenaMath.phaseOf(0.67F));
        assertEquals(2, ArenaMath.phaseOf(0.66F));
        assertEquals(2, ArenaMath.phaseOf(0.34F));
        assertEquals(3, ArenaMath.phaseOf(0.33F));
        assertEquals(3, ArenaMath.phaseOf(0.0F));
    }

    @Test
    void theBountyNeverExceedsThePoolAndNobodyIsPaidNothing() {
        int pool = ArenaMath.bountyPool(3);
        int[] shares = ArenaMath.bountyShares(new float[]{500, 300, 0}, pool);
        int paid = 0;
        for (int share : shares) {
            paid += share;
            assertTrue(share > 0, "a participant who swung once still gets a share");
        }
        assertTrue(paid <= pool, "shares " + paid + " exceed the pool " + pool);
        assertTrue(shares[0] > shares[1] && shares[1] > shares[2], "damage orders the shares");
        // Half the pool is split evenly, so the one who did nothing gets a sixth.
        assertEquals((int) Math.floor(pool / 6.0), shares[2]);
    }

    @Test
    void anEmptyRoomOrNoDamageStillSplitsEvenly() {
        assertEquals(0, ArenaMath.bountyShares(new float[0], 1000).length);
        int[] shares = ArenaMath.bountyShares(new float[]{0, 0}, 1000);
        assertEquals(500, shares[0]);
        assertEquals(500, shares[1]);
    }

    @Test
    void theGripScalesWithTheRoom() {
        assertEquals(ArenaMath.GRIP_RELEASE_PER_PLAYER, ArenaMath.gripRelease(1));
        assertEquals(ArenaMath.GRIP_RELEASE_PER_PLAYER * 4, ArenaMath.gripRelease(4));
        assertEquals(ArenaMath.GRIP_RELEASE_PER_PLAYER, ArenaMath.gripRelease(0));
    }

    @Test
    void theOmenNeedsCompanyQuietAndLuck() {
        long quiet = ArenaMath.COOLDOWN_SECONDS;
        assertTrue(ArenaMath.omenRolls(quiet, 2, 0.0F));
        assertFalse(ArenaMath.omenRolls(quiet, 1, 0.0F), "alone: never");
        assertFalse(ArenaMath.omenRolls(quiet - 1, 2, 0.0F), "too soon: never");
        assertFalse(ArenaMath.omenRolls(quiet, 2, ArenaMath.ROLL_CHANCE), "unlucky: never");
        assertTrue(ArenaMath.omenRolls(Long.MAX_VALUE, 5, ArenaMath.ROLL_CHANCE - 0.001F));
    }

    @Test
    void theClockReadsLikeAClock() {
        assertEquals("2:00", ArenaMath.clock(ArenaMath.GATHER_TICKS));
        assertEquals("0:05", ArenaMath.clock(100));
        assertEquals("0:00", ArenaMath.clock(-40));
    }
}
