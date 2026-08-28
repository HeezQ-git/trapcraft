package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stake button, which seven screens share and none of them can test.
 *
 * Two things here can be quietly wrong. A ladder that is not sorted puts a
 * cheaper bet above a dearer one and makes the button feel broken, and a
 * cycle that does not wrap both ways strands somebody nine clicks from the
 * stake they want. Neither throws.
 */
class StakeLadderTest {
    @Test
    void climbsPastTheOldCeiling() {
        assertEquals(8, TrapMath.STAKES[0]);
        assertTrue(TrapMath.STAKES[TrapMath.STAKES.length - 1] > 128);
        assertTrue(TrapMath.STAKES.length >= 8);
    }

    @Test
    void bothLaddersOnlyGoUp() {
        for (int[] ladder : new int[][]{TrapMath.STAKES, TrapMath.CHIPS}) {
            for (int i = 1; i < ladder.length; i++) {
                assertTrue(ladder[i] > ladder[i - 1]);
            }
        }
    }

    @Test
    void theButtonWrapsBothWays() {
        int last = TrapMath.STAKES.length - 1;
        assertEquals(1, TrapMath.cycle(0, TrapMath.STAKES.length, false));
        assertEquals(last, TrapMath.cycle(0, TrapMath.STAKES.length, true));
        assertEquals(0, TrapMath.cycle(last, TrapMath.STAKES.length, false));
    }
}
