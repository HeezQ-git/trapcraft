package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One person is in one place.
 *
 * The rule the town runs on, and the one nothing else can check. Every way it
 * has been broken so far was a line that looked local and correct: a census
 * that counted a box instead of the world, a sweep that recognised the head of
 * a household and nobody else, a casino that switched a villager's AI off and
 * never switched it back on. Each of those, on its own, presents identically
 * from inside the game -- residents flickering in and out at their front doors
 * and two of the same person in two places -- and none of them is visible in
 * any formula.
 *
 * So this reads the source, the way {@link DealerBodyTest} does, and for the
 * same reason: ugly, and cheaper than the evening it costs to find out which
 * of the three it was this time.
 */
class ResidentTest {

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/heezq/trapcraft/" + file));
    }

    @Test
    void aPunterIsSomebodyWhoAlreadyLivesHere() throws Exception {
        String floor = source("TrapFloor.java");
        assertFalse(floor.contains("EntityType.VILLAGER.create"),
                "the floor must not conjure punters: a punter is a resident who walked "
                        + "over, which is what makes the casino's crowd and the town's "
                        + "population the same people counted once");
        assertTrue(floor.contains("return TrapHomes.population()"),
                "the most people who can be on a floor at once is the town, so that "
                        + "twenty-four residents is twenty-four punters at the outside");
    }

    @Test
    void theCensusAsksTheWorldRatherThanABoxRoundTheHouse() throws Exception {
        String homes = source("TrapHomes.java");
        int census = homes.indexOf("List<? extends net.minecraft.entity.passive.VillagerEntity> living");
        assertTrue(census >= 0, "keepBodies no longer counts the household");
        assertTrue(homes.indexOf("getEntitiesByType", census) >= 0
                        && homes.indexOf("getEntitiesByType", census)
                        < homes.indexOf("for (var turned", census),
                "the household must be counted wherever its people are standing -- "
                        + "counting a box round the house declares anybody out at a "
                        + "machine missing and spawns a second copy of them at home");
    }

    @Test
    void theStraySweepKnowsTheWholeHouseholdAndNotJustItsHead() throws Exception {
        String homes = source("TrapHomes.java");
        int sweep = homes.indexOf("private static void sweep(ServerWorld world, BlockPos near)");
        assertTrue(sweep >= 0, "the stray sweep has gone");
        String body = homes.substring(sweep, homes.indexOf("public static void touch()", sweep));
        assertTrue(body.contains("TENANT_TAG + \"_\" + home.id"),
                "the sweep must recognise a body by the HOUSE named on it. Matching "
                        + "home.body only knows the head of the household, so everybody "
                        + "else in every house was binned on sight and respawned at the "
                        + "anchor twelve seconds later");
        assertFalse(body.contains("living.contains"),
                "membership by body id is the bug this test exists for");
    }

    @Test
    void aNightOutIsUndoneWhenItEnds() throws Exception {
        String floor = source("TrapFloor.java");
        int leave = floor.indexOf("private static void leave(ServerWorld world");
        assertTrue(leave >= 0, "leave() has gone");
        String body = floor.substring(leave, floor.indexOf("\n    }", leave));
        assertTrue(body.contains("removeCommandTag(PUNTER_TAG)")
                        && body.contains("setAiDisabled(false)"),
                "a punter is rooted at the machine by switching their AI off, so leave() "
                        + "must switch it back on and drop the tag -- otherwise every "
                        + "resident who ever had a night out stands frozen at that cabinet "
                        + "for good while their house makes another one of them");
    }
}
