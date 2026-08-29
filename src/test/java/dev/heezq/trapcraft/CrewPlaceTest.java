package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        // \\s* on both sides of the =: the declaration wrapped onto a second
        // line the day it grew to seven rungs, and a regex that only knew
        // one shape reported the constant as GONE rather than as moved.
        Matcher found = Pattern.compile("PLACE_COST\\s*=\\s*\\{([^}]*)}").matcher(crew);
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
        // Both numbers read from source, so this cannot drift: the ladder is
        // the crew's and HEADS is the board's, and the whole failure mode is
        // the two being changed in different sessions.
        String ui = Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/CrewScreenHandler.java"));
        Matcher heads = Pattern.compile("int HEADS = (\\d+)").matcher(ui);
        assertTrue(heads.find(), "CrewScreenHandler.HEADS has gone");
        int room = Integer.parseInt(heads.group(1));
        assertTrue(5 + ladder().size() <= room,
                "5 free places plus " + ladder().size() + " bought is more than the "
                        + room + " heads the crew board can paint");
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

    private static int num(String text, String pattern) {
        Matcher found = Pattern.compile(pattern).matcher(text);
        assertTrue(found.find(), "CrewScreenHandler: no " + pattern);
        return Integer.parseInt(found.group(1));
    }

    /**
     * Two things on the crew board must never want the same square.
     *
     * The board's own static block claims every slot and throws on a second
     * claim, which is the right backstop and the wrong moment: by then the
     * build is on a server and the board does not open. This is the same
     * promise, made at build time.
     *
     * It is not hypothetical. Places widened the head row to nine in one
     * session while another moved the move and round buttons up into it,
     * because the job row had run out of room for a courier. The merge was
     * textually clean. The board painted hands six and eight and then painted
     * a compass and a route over them, and the two hands could not be clicked
     * at all -- found by a player, not by anything here.
     */
    @Test
    void nothingOnTheCrewBoardIsPaintedTwice() throws Exception {
        String ui = Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/CrewScreenHandler.java"));
        int size = num(ui, "int ROWS = (\\d+)") * 9;
        int heads = num(ui, "int HEADS = (\\d+)");
        int block = num(ui, "int HEAD_BLOCK = (\\d+)");
        int top = num(ui, "int HEADS_TOP = (\\d+)");
        int bottom = num(ui, "int HEADS_BOTTOM = (\\d+)");
        int jobsFrom = num(ui, "int JOBS_FROM = (\\d+)");

        Map<Integer, String> taken = new LinkedHashMap<>();
        for (int i = 0; i < heads; i++) {
            claim(taken, size, "head " + (i + 1),
                    i < block ? top + i : bottom + (i - block));
        }
        Matcher button = Pattern.compile("int ([A-Z_]+_SLOT) = (\\d+);").matcher(ui);
        int found = 0;
        while (button.find()) {
            found++;
            claim(taken, size, button.group(1), Integer.parseInt(button.group(2)));
        }
        assertTrue(found >= 10, "only found " + found + " buttons -- did they stop being "
                + "named X_SLOT? this test greps for that name and silently checks "
                + "nothing without it");

        // And the job row, which is the thing on this board that grows.
        int move = num(ui, "int MOVE_SLOT = (\\d+)");
        for (int slot = jobsFrom; slot < move; slot++) {
            String other = taken.get(slot);
            assertTrue(other == null || other.endsWith("_SLOT"),
                    "the job row runs over " + other + " at slot " + slot);
        }
    }

    private static void claim(Map<Integer, String> taken, int size, String what, int slot) {
        assertTrue(slot >= 0 && slot < size,
                what + " sits at " + slot + ", off a " + size + "-slot board");
        String already = taken.put(slot, what);
        assertTrue(already == null,
                what + " lands on slot " + slot + ", already used by " + already);
    }
}
