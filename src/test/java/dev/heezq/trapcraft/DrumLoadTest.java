package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What comes out of the chest has to be what goes into the drum.
 *
 * A hand laundering counts the dirty money in a barrel, decides how much of it
 * fits, and then takes that much back out again -- and the counting and the
 * taking are two different loops over two different denominations. If they ever
 * disagree the mod mints or burns emeralds silently: nothing throws, nothing
 * logs, the drum simply pays out more or less than went in it. That is the one
 * failure here worth a test.
 */
class DrumLoadTest {

    /** What the drum is actually told to wash, given a {blocks, loose} answer. */
    private static int going(int[] take) {
        return take[0] * 9 + take[1];
    }

    @Test
    void looseAloneFillsUpToTheRoomThereIs() {
        assertArrayEquals(new int[]{0, 128}, TrapMath.drumLoad(0, 200, 128));
        assertArrayEquals(new int[]{0, 30}, TrapMath.drumLoad(0, 30, 128));
    }

    @Test
    void blocksAreWorthNineAndGoInWhole() {
        // Fourteen blocks is 126, and the fifteenth will not fit in a 128 drum.
        assertArrayEquals(new int[]{14, 0}, TrapMath.drumLoad(20, 0, 128));
    }

    @Test
    void looseChangeFillsTheGapTheBlocksLeave() {
        int[] take = TrapMath.drumLoad(20, 10, 128);
        assertArrayEquals(new int[]{14, 2}, take);
        assertTrue(going(take) == 128, "a full chest should fill the drum exactly");
    }

    @Test
    void aGapTooSmallForABlockStillTakesChange() {
        // Four spaces and a stack of blocks next to them: you cannot tip in
        // four ninths of a block, so the change is the only thing that fits.
        assertArrayEquals(new int[]{0, 4}, TrapMath.drumLoad(64, 4, 4));
    }

    @Test
    void nothingComesOutOfAFullDrum() {
        assertArrayEquals(new int[]{0, 0}, TrapMath.drumLoad(64, 64, 0));
        assertArrayEquals(new int[]{0, 0}, TrapMath.drumLoad(64, 64, -1));
    }

    @Test
    void anEmptyChestIsAskedForNothing() {
        assertArrayEquals(new int[]{0, 0}, TrapMath.drumLoad(0, 0, 128));
    }

    @Test
    void itNeverAsksForMoreThanIsThereOrMoreThanFits() {
        for (int blocks = 0; blocks <= 20; blocks++) {
            for (int loose = 0; loose <= 20; loose++) {
                // 0..128, the drum's capacity -- written out rather than read
                // off LaundryBlock so this test stays on the Minecraft-free
                // side of the fence with everything else that runs in a second.
                for (int room = 0; room <= 128; room++) {
                    int[] take = TrapMath.drumLoad(blocks, loose, room);
                    assertTrue(take[0] <= blocks && take[1] <= loose,
                            "took more than the chest held: " + blocks + "/" + loose);
                    assertTrue(going(take) <= room,
                            "overfilled a drum with " + room + " spaces left");
                    // Greedy on blocks must never do worse than ignoring them.
                    assertTrue(going(take) >= Math.min(loose, room),
                            "took less than the loose change alone would have");
                }
            }
        }
    }

    /**
     * And the same promise on the way out, now that a wash pays in blocks.
     *
     * The payout used to be loose emeralds and could not be wrong. It is a
     * division now, and a division is where the remainder goes missing: drop
     * it and every wash that is not an exact multiple of nine quietly burns up
     * to eight emeralds, which is small enough that nobody would ever catch it
     * by looking at a chest.
     */
    @Test
    void aWashPaysOutExactlyWhatItCleaned() {
        for (int clean = 0; clean <= 5000; clean++) {
            int[] cut = TrapMath.denominate(clean);
            assertEquals(clean, cut[0] * 9 + cut[1],
                    "denominate(" + clean + ") does not add back up");
            assertTrue(cut[1] >= 0 && cut[1] < 9,
                    "loose change of " + cut[1] + " should have been another block");
        }
    }

    @Test
    void nothingComesOutOfNothing() {
        assertArrayEquals(new int[]{0, 0}, TrapMath.denominate(0));
        // A negative can only arrive from a corrupted save, and paying out a
        // negative number of blocks would be a very expensive way to find out.
        assertArrayEquals(new int[]{0, 0}, TrapMath.denominate(-500));
    }
}
