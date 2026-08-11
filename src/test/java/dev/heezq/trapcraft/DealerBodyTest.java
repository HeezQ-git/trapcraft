package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing about calling a dealer in that no formula can check.
 *
 * A dealer's body is only ever cleaned up by the sweep on ENTITY_LOAD, which
 * bins any tagged villager the book does not claim. That makes the order of
 * two lines in {@code call()} load-bearing: {@code spawnEntity} fires
 * ENTITY_LOAD synchronously, so if the claim lands after the spawn, the sweep
 * asks its question one line too early, gets "no", and every dealer ever
 * called is destroyed at the instant they arrive. The casino floor shipped
 * exactly that for a release.
 *
 * Nothing in a formula can catch it and nothing short of a running server can
 * catch it either, so this reads the source. Ugly, and cheaper than the
 * evening it costs to work out why nobody turns up.
 */
class DealerBodyTest {

    @Test
    void theBookClaimsTheBodyBeforeItIsSpawned() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/TrapDealers.java"));
        int claim = source.indexOf("dealer.mob = body.getUuid()");
        int spawn = source.indexOf("world.spawnEntity(body)");

        assertTrue(claim >= 0, "call() no longer claims the body by UUID");
        assertTrue(spawn >= 0, "call() no longer spawns a body");
        assertTrue(claim < spawn,
                "the book must claim the body BEFORE spawnEntity fires ENTITY_LOAD, "
                        + "or the orphan sweep bins every dealer the moment they turn up");
    }
}
