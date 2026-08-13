package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repair bill is addressed, not announced.
 *
 * The wear notices went to every player within 48 blocks for a release, so
 * logging in anywhere near somebody else's floor told you their cabinet was
 * getting shabby at coordinates you had no reason to walk to. Ownership is
 * the card, so the notices follow the card.
 *
 * No formula can catch a message reaching the wrong player, and nothing short
 * of two players on a running server can either, so this reads the source --
 * same trade as {@link DealerBodyTest}.
 */
class FloorNoticeTest {

    @Test
    void wearNoticesGoToTheCardAndNobodyElse() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/TrapFloor.java"));
        int wear = source.indexOf("TrapHouse.wearOne(");
        int end = source.indexOf("if (paid > punter.stake)", wear);
        assertTrue(wear >= 0 && end > wear, "play() no longer wears a machine down");

        String block = source.substring(wear, end);
        assertFalse(block.contains("world.getPlayers()"),
                "a wear notice is addressed to whoever holds the card, not read out to "
                        + "every player who happens to be near somebody else's floor");
        assertTrue(block.contains("tellOwner(world, house,"),
                "both wear notices must go through tellOwner, which tests the card");
    }
}
