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
        assertEquals(6, found.floor(), "one storey, six squares of floor");
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

    /**
     * Everything fitted, nothing dug, nothing dark -- so only floor decides.
     *
     * Decor reads the TOP step rather than a number. It was a hardcoded 30,
     * which was full marks when there were two decor steps and two thirds of
     * them once there were four -- so this quietly stopped being a maxed-out
     * house and started capping at grade six, and "size is the lid" was
     * measuring the points ceiling instead.
     */
    private static int graded(int floor) {
        return HomeSurvey.tier(true, floor, true, true, 1.0f, HomeSurvey.FITTINGS,
                HomeSurvey.DECOR_STEPS[HomeSurvey.DECOR_STEPS.length - 1], 0, 8);
    }

    @Test
    void aShedIsNotAHome() {
        assertEquals(0, HomeSurvey.tier(true, 4, true, true, 1f, 5, 30, 0, 8), "too small");
        assertEquals(0, HomeSurvey.tier(true, 60, false, true, 1f, 5, 30, 0, 8), "no bed");
        assertEquals(0, HomeSurvey.tier(true, 60, true, false, 1f, 5, 30, 0, 8), "no way in");
        assertEquals(0, HomeSurvey.tier(true, 60, true, true, 1f, 5, 30, 0, 0), "pitch dark");
        assertEquals(0, HomeSurvey.tier(false, 60, true, true, 1f, 5, 30, 0, 8), "not sealed");
    }

    /**
     * The complaint this was written for.
     *
     * A three-by-four dirt cupboard with a bed, a table, a chest, a furnace
     * and a torch used to grade four out of five, which is another way of
     * saying the grade was a shopping list. Size is a lid now: however
     * perfectly it is fitted out, a cupboard is a one.
     */
    @Test
    void aCupboardIsAlwaysAOne() {
        assertEquals(1, graded(11), "perfectly fitted, still a cupboard");
        assertEquals(1, HomeSurvey.tier(true, 11, true, true, 0.1f, 4, 8, 3, 1),
                "and a dirt one is no worse, because it could not be");
    }

    /** Nothing but floor moves the ceiling, and it moves it one step at a time. */
    @Test
    void sizeIsTheLid() {
        for (int step = 0; step < HomeSurvey.FLOOR_STEPS.length; step++) {
            assertEquals(step + 1, graded(HomeSurvey.FLOOR_STEPS[step]),
                    HomeSurvey.FLOOR_STEPS[step] + " squares should allow grade " + (step + 1));
        }
        assertEquals(HomeSurvey.TOP_TIER, graded(10_000), "and no further");
    }

    /** Room alone is not enough either. A big empty dirt hall is a one. */
    @Test
    void sizeAloneIsNotEnough() {
        assertEquals(1, HomeSurvey.tier(true, 400, true, true, 0f, 0, 3, 200, 1));
    }

    /** What a house is made of has to matter, or dirt wins on effort. */
    @Test
    void dirtGradesWorseThanBrick() {
        // Floor big enough that SIZE is not the lid, or both houses stop at
        // the grade their footprint allows and the material never shows.
        int room = HomeSurvey.FLOOR_STEPS[HomeSurvey.FLOOR_STEPS.length - 1];
        int dirt = HomeSurvey.tier(true, room, true, true, 0.0f, 5, 30, 0, 8);
        int brick = HomeSurvey.tier(true, room, true, true, 1.0f, 5, 30, 0, 8);
        assertTrue(brick > dirt, "brick " + brick + " should beat dirt " + dirt);
        assertEquals(0, HomeSurvey.shellPoints(0.0f));
        assertEquals(1, HomeSurvey.shellPoints(HomeSurvey.SHELL_STEPS[0]));
        assertEquals(2, HomeSurvey.shellPoints(HomeSurvey.SHELL_STEPS[1]));
    }

    /**
     * A few dim patches are forgiven; a gloomy room is not.
     *
     * "None at all" for the top mark sounds reasonable and is not. Light falls
     * off a level a block, so a ceiling torch reads about ten on the floor
     * under it and less between torches -- demanding a perfectly even floor
     * meant tearing up somebody's decoration to bury lamps in it, which is the
     * opposite of what a grade for decoration should encourage.
     */
    @Test
    void darkCornersCost() {
        assertEquals(2, HomeSurvey.lightPoints(0, 100), "a perfect room");
        assertEquals(2, HomeSurvey.lightPoints(5, 100), "a few dim patches still full marks");
        assertEquals(1, HomeSurvey.lightPoints(15, 100), "patchy but liveable");
        assertEquals(0, HomeSurvey.lightPoints(40, 100), "gloomy");
        assertEquals(1, HomeSurvey.lightPoints(2, 4), "a tiny place gets a couple of squares");
    }

    /** The fittings ladder is worth climbing all of, not most of. */
    @Test
    void fittingsRewardTheWholeList() {
        assertEquals(0, HomeSurvey.fittingPoints(1));
        assertEquals(1, HomeSurvey.fittingPoints(3));
        assertEquals(2, HomeSurvey.fittingPoints(4));
        assertEquals(3, HomeSurvey.fittingPoints(HomeSurvey.FITTINGS));
    }

    @Test
    void theGradeOnlyEverClimbs() {
        int last = 0;
        for (int floor : new int[]{9, 20, 45, 90, 150, 400}) {
            int now = graded(floor);
            assertTrue(now >= last, "more room should never grade worse: " + floor);
            last = now;
        }
    }

    @Test
    void topPointsAgreesWithTheLadder() {
        // The guide book prints topPoints(); if it disagreed with what the
        // scoring can actually award, the book would be lying about a number
        // nobody could check.
        assertEquals(HomeSurvey.topPoints(),
                HomeSurvey.points(1.0f, HomeSurvey.FITTINGS,
                        HomeSurvey.DECOR_STEPS[HomeSurvey.DECOR_STEPS.length - 1], 0, 100));
        // Full marks has to come out at the top grade. This mirrors the sum
        // in tier(), and mirroring it is the point: if points stop reaching
        // the ceiling, the last grades quietly become unearnable.
        assertEquals(HomeSurvey.TOP_TIER, 1 + Math.min(HomeSurvey.TOP_TIER - 1,
                HomeSurvey.topPoints() * (HomeSurvey.TOP_TIER - 1) / HomeSurvey.topPoints()));
    }

    // --- somebody lives there -------------------------------------------------

    @Test
    void betterHousesPayMore() {
        int last = -1;
        for (int tier = 0; tier <= HomeSurvey.TOP_TIER; tier++) {
            int rent = HomeSurvey.rentDue(tier, HomeSurvey.MOOD_MAX, 1);
            assertTrue(rent > last, "grade " + tier + " pays " + rent);
            last = rent;
        }
    }

    @Test
    void anUnhappyTenantPaysLess() {
        int happy = HomeSurvey.rentDue(3, HomeSurvey.MOOD_MAX, 1);
        int fed = HomeSurvey.rentDue(3, HomeSurvey.MOOD_MAX / 2, 1);
        assertTrue(fed < happy, fed + " should be under " + happy);
        assertEquals(0, HomeSurvey.rentDue(3, 0, 1), "nobody pays on the way out");
        assertEquals(0, HomeSurvey.rentDue(0, HomeSurvey.MOOD_MAX, 1), "nor for a condemned room");
    }

    @Test
    void aFamilyPaysMoreThanALodger() {
        // The rebalance in one assertion: a grade five with one bed is a
        // fraction of what it used to pay, and the way back to the old number
        // is four people in it rather than one.
        int lodger = HomeSurvey.rentDue(5, HomeSurvey.MOOD_MAX, 1);
        int family = HomeSurvey.rentDue(5, HomeSurvey.MOOD_MAX, 4);
        assertEquals(lodger * 4, family, "rent is per head");
        assertTrue(lodger < 100, "one bed in one house should not be a wage, got " + lodger);
    }

    @Test
    void aHouseholdNeedsBedsAndFloorAndAGrade() {
        // Beds alone are a dormitory, floor alone is a hall, and a hovel is
        // neither however many of each it has. The lowest of the three wins.
        assertEquals(4, HomeSurvey.household(4, 200, 5), "four beds, room, and a grade");
        assertEquals(2, HomeSurvey.household(2, 200, 5), "two beds is two people");
        assertEquals(3, HomeSurvey.household(9, 90, 5),
                "ninety squares holds three at " + HomeSurvey.FLOOR_PER_HEAD + " each");
        assertEquals(2, HomeSurvey.household(9, 900, 2), "a grade two is not a mansion");
        assertEquals(1, HomeSurvey.household(9, 9, 1), "somebody lives in a hovel");
        assertEquals(0, HomeSurvey.household(0, 900, 5), "no bed, nobody");
        assertEquals(0, HomeSurvey.household(4, 900, 0), "condemned holds nobody");
    }

    @Test
    void theTopGradesAreReachable() {
        // Raising TOP_TIER without spreading the points across it would have
        // added three grades nobody could ever earn -- a ladder with the last
        // rungs sawn off. A perfect house has to come out at the top.
        assertEquals(HomeSurvey.TOP_TIER,
                HomeSurvey.tier(true, HomeSurvey.FLOOR_STEPS[HomeSurvey.TOP_TIER - 1],
                        true, true, 1.0f, HomeSurvey.FITTINGS,
                        HomeSurvey.DECOR_STEPS[HomeSurvey.DECOR_STEPS.length - 1], 0, 4),
                "a perfect house should reach grade " + HomeSurvey.TOP_TIER);
        // And the floor steps have to keep up with the grades, or size stops
        // being the lid it was made to be.
        assertEquals(HomeSurvey.TOP_TIER, HomeSurvey.FLOOR_STEPS.length,
                "one floor step per grade");
    }

    /** The tension the whole design was built around. */
    @Test
    void aGrowNextDoorDrivesThemOut() {
        assertEquals(HomeSurvey.MOOD_MAX, HomeSurvey.moodTarget(5, 0, -1),
                "nothing growing, nothing wrong");

        // Tier 0 already means a grow big enough to attract a patrol, so the
        // smallest thing that registers should already hurt: they stay, and
        // they pay well under half.
        int smallest = HomeSurvey.moodTarget(5, 0, 0);
        assertTrue(smallest > 0 && smallest < HomeSurvey.MOOD_MAX / 2,
                "a grow next door should more than halve it, got " + smallest);
        assertTrue(HomeSurvey.rentDue(5, smallest, 1) < HomeSurvey.RENT[5] / 2);

        // Anything bigger and they go.
        for (int tier = 1; tier <= 3; tier++) {
            assertTrue(HomeSurvey.moodTarget(5, 0, tier) < HomeSurvey.MOOD_LEAVING,
                    "heat tier " + tier + " has to empty the place");
        }
    }

    @Test
    void darkCornersMakeThemMiserable() {
        assertTrue(HomeSurvey.moodTarget(3, 5, -1) < HomeSurvey.moodTarget(3, 0, -1));
        assertEquals(0, HomeSurvey.moodTarget(3, 999, -1), "never below nothing");
        assertEquals(0, HomeSurvey.moodTarget(0, 0, -1), "nobody stays in a condemned house");
    }

    @Test
    void moodMovesADayAtATime() {
        assertEquals(HomeSurvey.MOOD_START + HomeSurvey.MOOD_STEP,
                HomeSurvey.moodDrift(HomeSurvey.MOOD_START, HomeSurvey.MOOD_MAX));
        assertEquals(HomeSurvey.MOOD_START - HomeSurvey.MOOD_STEP,
                HomeSurvey.moodDrift(HomeSurvey.MOOD_START, 0));
        assertEquals(40, HomeSurvey.moodDrift(40, 40), "settled is settled");
        assertEquals(45, HomeSurvey.moodDrift(50, 45), "never overshoots");
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
