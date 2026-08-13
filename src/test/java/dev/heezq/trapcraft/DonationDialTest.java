package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The donation dial, which is a money path with no server in it.
 *
 * The screen it drives cannot be unit tested, but the one thing on that
 * screen that can quietly be WRONG is this clamp: a stepper that lets
 * somebody dial past the purse is a stepper that offers to give away money
 * the city does not have, and nothing throws when it does.
 */
class DonationDialTest {
    @Test
    void stepsUpAndDown() {
        assertEquals(110, TrapMath.stepped(100, 10, 1000));
        assertEquals(90, TrapMath.stepped(100, -10, 1000));
    }

    @Test
    void neverPastThePurse() {
        assertEquals(500, TrapMath.stepped(400, 1000, 500));
        assertEquals(0, TrapMath.stepped(0, 1000, 0));
    }

    @Test
    void neverBelowNothing() {
        assertEquals(0, TrapMath.stepped(10, -1000, 500));
    }

    @Test
    void aFullPurseComesBackWhole() {
        assertEquals(7321, TrapMath.stepped(0, Integer.MAX_VALUE, 7321));
    }

    /** A purse past two billion must clamp, not wrap to a negative grant. */
    @Test
    void doesNotOverflow() {
        assertEquals(Integer.MAX_VALUE,
                TrapMath.stepped(Integer.MAX_VALUE, 1000, Long.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE,
                TrapMath.stepped(0, Integer.MAX_VALUE, Long.MAX_VALUE));
    }
}
