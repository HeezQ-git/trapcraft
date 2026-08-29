package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Room on the crew's books, which is money and therefore worth pinning.
 *
 * The two helpers are read off a SAVED count, and a saved count is whatever
 * the file happens to say. A ladder asked for a rung past its end throws in
 * the middle of somebody's purchase; a count from a build with a longer ladder
 * hands out a place the board has no slot for, and that hand is then invisible
 * and unfireable. Neither shows up until it has happened to somebody.
 *
 * The ladder itself is source-read for {@link FormulaTest}'s reason -- TrapCrew
 * touches Minecraft in a static field, so naming it from a plain JUnit run
 * fails to initialise the class.
 */
class CrewPlaceTest {

    private static List<Integer> ladder() throws Exception {
        String crew = Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/TrapCrew.java"));
        Matcher found = Pattern.compile("PLACE_COST = \\{([^}]*)}").matcher(crew);
        assertTrue(found.find(), "TrapCrew.PLACE_COST has gone");
        List<Integer> costs = new ArrayList<>();
        Matcher number = Pattern.compile("\\d+").matcher(found.group(1));
        while (number.find()) {
            costs.add(Integer.parseInt(number.group()));
        }
        return costs;
    }

    @Test
    void everyPlaceCostsMoreThanTheLast() throws Exception {
        List<Integer> costs = ladder();
        assertTrue(!costs.isEmpty(), "a ladder with no rungs sells nothing");
        for (int i = 1; i < costs.size(); i++) {
            assertTrue(costs.get(i) > costs.get(i - 1),
                    "place " + (i + 1) + " is not dearer than the one before it");
        }
    }

    /**
     * The board's head row is nine slots and nothing else goes on it.
     *
     * {@link CrewScreenHandler} throws on open if this is ever false, which is
     * the right backstop and the wrong time to find out: by then the build is
     * on somebody's server and the crew board does not open at all.
     */
    @Test
    void theBoardCanShowEveryPlaceItSells() throws Exception {
        assertTrue(5 + ladder().size() <= 9,
                "5 free places plus " + ladder().size() + " bought is more than the "
                        + "nine heads the crew board's top row can hold");
    }

    @Test
    void theLadderRunsOutRatherThanThrows() {
        int[] rungs = {1500, 3500, 8000};
        assertEquals(1500, TrapMath.crewPlaceCost(rungs, 0));
        assertEquals(8000, TrapMath.crewPlaceCost(rungs, 2));
        // Spent, not broken: the caller reads 0 as "nothing left to sell".
        assertEquals(0, TrapMath.crewPlaceCost(rungs, 3));
        assertEquals(0, TrapMath.crewPlaceCost(rungs, 99));
        assertEquals(0, TrapMath.crewPlaceCost(rungs, -1));
    }

    @Test
    void theCapNeverPassesTheHeadRow() {
        assertEquals(5, TrapMath.crewCap(5, 0, 4));
        assertEquals(7, TrapMath.crewCap(5, 2, 4));
        assertEquals(9, TrapMath.crewCap(5, 4, 4));
        // A file written by a longer-laddered build must not widen the board.
        assertEquals(9, TrapMath.crewCap(5, 40, 4));
        assertEquals(5, TrapMath.crewCap(5, -3, 4));
    }
}
