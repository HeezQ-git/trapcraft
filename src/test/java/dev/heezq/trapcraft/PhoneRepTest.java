package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Somebody without a phone has no name, and does not take the server with them.
 *
 * {@code findPhone} returns null for anybody not carrying one and six call
 * sites hand its result straight to {@code repOf}, one of which is the dealer
 * round on the server tick. On 2026-08-13 that was a NullPointerException in
 * the tick loop with two players on: crash report, restart, crash report,
 * every two minutes, and it read from the outside as the server running out of
 * memory.
 */
class PhoneRepTest {

    @Test
    void noPhoneIsNoRepRatherThanACrash() {
        assertEquals(0, TrapContracts.repOf(null),
                "repOf(null) has to be zero. findPhone is DOCUMENTED to return null and "
                        + "every caller passes it in unchecked -- guarding at those six "
                        + "call sites instead is six chances to forget the seventh");
    }
}
