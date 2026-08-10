package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flood fill, run against drawings instead of a world.
 *
 * This is the whole reason {@link HomeSurvey} imports nothing from Minecraft.
 * Two-phase fill with door probes is the piece of the city design most likely
 * to be subtly wrong, and "subtly wrong" in a world means an evening of
 * walking round a house wondering why the cupboard counts and the kitchen
 * does not. Here it is a drawing and an assertion.
 *
 * Each plan is a list of strings, one per row of Z, characters along X:
 *
 * <pre>
 *   #  wall        .  air         D  door
 *   @  the anchor  X  somebody else's claim
 * </pre>
 *
 * One block high, with an implied floor and ceiling -- enough, because the
 * fill treats all six directions the same and the vertical case has no logic
 * the horizontal one does not.
 *
 * Anything OFF the drawing is open air, out to the survey's own span. That is
 * what makes "outdoors" mean something in a twelve-character picture: a plan
 * that does not wall itself in leaks into a few thousand blocks of nothing,
 * exactly as a house with a hole in it does.
 */
class SurveyTest {

    /** A drawing, as the survey sees it. */
    private static final class Plan implements HomeSurvey.Space {
        private final String[] rows;

        Plan(String... rows) {
            this.rows = rows;
        }

        private char at(int x, int y, int z) {
            if (y != 0) {
                return '#';   // floor and ceiling
            }
            if (z < 0 || z >= rows.length) {
                return '.';   // off the drawing: the great outdoors
            }
            String row = rows[z];
            return x < 0 || x >= row.length() ? '.' : row.charAt(x);
        }

        @Override
        public boolean open(int x, int y, int z) {
            char c = at(x, y, z);
            return c == '.' || c == '@';
        }

        @Override
        public boolean door(int x, int y, int z) {
            return at(x, y, z) == 'D';
        }

        @Override
        public boolean taken(int x, int y, int z) {
            return at(x, y, z) == 'X';
        }

        HomeSurvey.Rooms survey() {
            for (int z = 0; z < rows.length; z++) {
                int x = rows[z].indexOf('@');
                if (x >= 0) {
                    return HomeSurvey.survey(this, x, 0, z);
                }
            }
            throw new IllegalStateException("no anchor in the plan");
        }
    }

    // --- phase one ------------------------------------------------------------

    @Test
    void aSealedRoomIsTheRoom() {
        HomeSurvey.Rooms found = new Plan(
                "#####",
                "#.@.#",
                "#...#",
                "#####").survey();
        assertTrue(found.sealed());
        assertFalse(found.clash());
        assertEquals(6, found.inside().size());
        assertEquals(6, found.floor());
    }

    @Test
    void aHoleInTheWallIsNotAHouse() {
        HomeSurvey.Rooms found = new Plan(
                "##.##",
                "#.@.#",
                "#...#",
                "#####").survey();
        assertFalse(found.sealed());
        assertFalse(found.clash());
    }

    @Test
    void anotherHousesGroundStopsIt() {
        HomeSurvey.Rooms found = new Plan(
                "#####",
                "#.@X#",
                "#####").survey();
        assertFalse(found.sealed());
        assertTrue(found.clash());
    }

    @Test
    void bricksOverTheAnchorReadAsCollapsed() {
        HomeSurvey.Rooms found = HomeSurvey.survey(new Plan(
                "#####",
                "#...#",
                "#####"), 2, 0, 0);
        assertFalse(found.sealed());
    }

    // --- phase two, the case the design was settled on ------------------------

    @Test
    void bedroomHallPorchStreet() {
        // [bedroom]--door--[hall]--door--[porch]--door--[street]
        HomeSurvey.Rooms found = new Plan(
                ".........",   // 0  the street
                "####D####",   // 1  the front door
                "#.......#",   // 2  porch
                "####D####",   // 3
                "#...@...#",   // 4  hall, and the mailbox
                "####D####",   // 5
                "#.......#",   // 6  bedroom, door shut
                "#########").survey();

        assertTrue(found.sealed());
        assertEquals(1, found.exits().size(), "only the one onto the street");
        assertEquals(1, HomeSurvey.cellZ(found.exits().get(0)));
        assertTrue(found.inside().contains(HomeSurvey.cell(2, 0, 6)), "bedroom counts");
        assertTrue(found.inside().contains(HomeSurvey.cell(2, 0, 2)), "porch counts");
        // Three rooms of seven, and the two internal doorways. The front door
        // is not counted, which is the line you would draw by hand: a doorway
        // between two of your rooms is inside, the one onto the street is where
        // the house stops.
        assertEquals(7 * 3 + 2, found.floor());
    }

    @Test
    void twoDoorsSideBySideAreBothFrontDoors() {
        HomeSurvey.Rooms found = new Plan(
                ".........",
                "###DD####",
                "#...@...#",
                "#########").survey();
        assertTrue(found.sealed());
        assertEquals(2, found.exits().size());
    }

    @Test
    void aGarageResolvesWithNoSpecialCase() {
        // Inner door to the house, garage door to the drive. Same shape as the
        // porch, and it wants no code of its own.
        HomeSurvey.Rooms found = new Plan(
                ".........",
                "###DDD###",   // the garage door, three wide
                "#.......#",   // the garage
                "####D####",   // the inner door
                "#...@...#",
                "#########").survey();
        assertTrue(found.sealed());
        assertEquals(3, found.exits().size(), "the garage door, not the inner one");
        assertTrue(found.inside().contains(HomeSurvey.cell(4, 0, 2)), "the garage counts");
    }

    @Test
    void theProbeBudgetIsSpentOnce() {
        // A wall of doors onto the outdoors. Each probe costs the cap, so the
        // budget runs out -- and every door left over is called a front door
        // rather than quietly merging the sky into somebody's living room.
        List<String> rows = new ArrayList<>();
        rows.add(".".repeat(40));
        rows.add("#" + "D".repeat(38) + "#");
        rows.add("#" + ".".repeat(18) + "@" + ".".repeat(19) + "#");
        rows.add("#".repeat(40));
        HomeSurvey.Rooms found = new Plan(rows.toArray(new String[0])).survey();

        assertTrue(found.sealed());
        assertEquals(38, found.exits().size(), "every door is an exit, budget or no budget");
    }

    @Test
    void aBigRoomBehindADoorStillCounts() {
        // Five hundred blocks was the sketched probe budget and it would have
        // failed this: a twelve by twelve room is nobody's idea of outdoors.
        List<String> rows = new ArrayList<>();
        rows.add("#".repeat(14));
        for (int i = 0; i < 12; i++) {
            rows.add("#" + ".".repeat(12) + "#");
        }
        rows.add("######D#######");
        rows.add("#....@.......#");
        rows.add("#".repeat(14));
        HomeSurvey.Rooms found = new Plan(rows.toArray(new String[0])).survey();

        assertTrue(found.sealed());
        assertEquals(0, found.exits().size(), "no way out at all, so no front door");
        assertTrue(found.floor() > 140, "the big room merged, got " + found.floor());
    }

    // --- the grade ------------------------------------------------------------

    @Test
    void aShedIsNotAHome() {
        assertEquals(0, HomeSurvey.tier(true, 4, true, true, 4, 20, 4), "too small");
        assertEquals(0, HomeSurvey.tier(true, 30, false, true, 4, 20, 4), "no bed");
        assertEquals(0, HomeSurvey.tier(true, 30, true, false, 4, 20, 4), "no way in");
        assertEquals(0, HomeSurvey.tier(true, 30, true, true, 4, 20, 0), "pitch dark");
        assertEquals(0, HomeSurvey.tier(false, 30, true, true, 4, 20, 4), "not sealed");
    }

    @Test
    void theBareMinimumIsTierOne() {
        assertEquals(1, HomeSurvey.tier(true, HomeSurvey.MIN_FLOOR, true, true, 0, 0, 1));
    }

    @Test
    void everythingIsTierFive() {
        assertEquals(HomeSurvey.TOP_TIER,
                HomeSurvey.tier(true, 100, true, true, 4, 20, 100));
    }

    @Test
    void theGradeOnlyEverClimbs() {
        int last = 0;
        for (int floor : new int[]{9, 20, 40, 80, 200}) {
            int now = HomeSurvey.tier(true, floor, true, true, 2, 6, floor);
            assertTrue(now >= last, "more room should never grade worse: " + floor);
            last = now;
        }
    }

    @Test
    void topPointsAgreesWithTheLadder() {
        // The guide book prints topPoints(); if it disagreed with what tier()
        // can actually award, the book would be lying about a number nobody
        // could check.
        assertEquals(HomeSurvey.TOP_TIER,
                1 + Math.min(HomeSurvey.TOP_TIER - 1, HomeSurvey.topPoints() / 2));
    }

    // --- claims ---------------------------------------------------------------

    @Test
    void boxesThatShareAWallDoNotOverlap() {
        int[] left = {0, 0, 0, 4, 4, 4};
        assertFalse(HomeSurvey.overlaps(left, new int[]{5, 0, 0, 9, 4, 4}));
        assertTrue(HomeSurvey.overlaps(left, new int[]{4, 0, 0, 9, 4, 4}));
    }

    @Test
    void aFlatAboveAnotherDoesNotOverlap() {
        assertFalse(HomeSurvey.overlaps(
                new int[]{0, 64, 0, 9, 68, 9},
                new int[]{0, 69, 0, 9, 73, 9}));
    }

    @Test
    void boundsCoverEverythingFound() {
        HomeSurvey.Rooms found = new Plan(
                "#####",
                "#.@.#",
                "#...#",
                "#####").survey();
        assertEquals(Arrays.toString(new int[]{1, 0, 1, 3, 0, 2}),
                Arrays.toString(HomeSurvey.bounds(found.inside())));
    }
}
