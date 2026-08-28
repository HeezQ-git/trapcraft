package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

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
            // 'B' is a second anchor, for plans with two houses in them.
            return c == '.' || c == '@' || c == 'B';
        }

        @Override
        public boolean door(int x, int y, int z) {
            return at(x, y, z) == 'D';
        }

        @Override
        public boolean taken(int x, int y, int z) {
            return at(x, y, z) == 'X';
        }

        /** 'Z' is wall the survey made up, because nobody had the chunk. */
        @Override
        public boolean asleep(int x, int y, int z) {
            return at(x, y, z) == 'Z';
        }

        HomeSurvey.Rooms survey() {
            return surveyFrom('@');
        }

        HomeSurvey.Rooms surveyFrom(char anchor) {
            for (int z = 0; z < rows.length; z++) {
                int x = rows[z].indexOf(anchor);
                if (x >= 0) {
                    return HomeSurvey.survey(this, x, 0, z);
                }
            }
            throw new IllegalStateException("no anchor '" + anchor + "' in the plan");
        }
    }

    /**
     * A drawing with storeys: levels[y], then row z, then character x.
     *
     * The flat {@link Plan} answers every vertical question with "wall", which
     * is what makes it a clean test of the fill and useless for the two things
     * that are ABOUT the vertical: a wardrobe standing floor to ceiling, and a
     * balcony with the sky over it and a drop off the end.
     *
     * <pre>
     *   #  solid   .  air   D  door   F  furniture   @  the anchor
     * </pre>
     *
     * Below the bottom level is bedrock and above the top one is sky. Off the
     * sides, each level repeats its own character from {@code beyond} -- so a
     * level of ground can go on forever while the one above it is open air,
     * which is the whole difference between a terrace and a field.
     */
    private static final class Tower implements HomeSurvey.Space {
        private final String beyond;
        private final String[][] levels;

        Tower(String beyond, String[]... levels) {
            this.beyond = beyond;
            this.levels = levels;
        }

        private char at(int x, int y, int z) {
            if (y < 0) {
                return '#';
            }
            if (y >= levels.length) {
                return '.';
            }
            String[] rows = levels[y];
            if (z < 0 || z >= rows.length) {
                return beyond.charAt(y);
            }
            String row = rows[z];
            return x < 0 || x >= row.length() ? beyond.charAt(y) : row.charAt(x);
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

        @Override
        public boolean prop(int x, int y, int z) {
            return at(x, y, z) == 'F';
        }

        HomeSurvey.Rooms survey() {
            for (int y = 0; y < levels.length; y++) {
                for (int z = 0; z < levels[y].length; z++) {
                    int x = levels[y][z].indexOf('@');
                    if (x >= 0) {
                        return HomeSurvey.survey(this, x, y, z);
                    }
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

    /**
     * The block of flats: your own walls, your front door onto ground that is
     * somebody else's -- a stairwell, a street, the flat below.
     *
     * The claim is a BOX, so this is not a rare shape. Any house tall enough
     * reaches over the road, and every front door within a few blocks of one
     * used to condemn the building it belonged to and report it as "not a
     * room".
     */
    @Test
    void aFrontDoorOntoSomebodyElsesGroundIsStillAFrontDoor() {
        HomeSurvey.Rooms found = new Plan(
                "#####X",
                "#.@.DX",
                "#####X").survey();
        assertTrue(found.sealed(), "the room is sealed; their ground is outside it");
        assertFalse(found.clash());
        assertEquals(1, found.exits().size(), "the door onto their ground is the way out");
        assertEquals(3, found.inside().size(), "and none of their ground comes with it");
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

    // --- half the building asleep ---------------------------------------------

    /**
     * The bug that made HeezQ's station open and shut all day.
     *
     * A building is bigger than a chunk and a chunk is loaded on its own. When
     * one of them is not about, every square in it answers WALL -- so the fill
     * stops at the chunk line and comes back with a room that is sealed,
     * smaller, and perfectly plausible. No hole, no complaint, just four fewer
     * cells than the place has. Graded, that closes the station; twelve seconds
     * later the chunk is back and it opens again.
     *
     * The lie is unavoidable -- the survey cannot see what is not loaded -- so
     * the fix is that it OWNS UP to it, and here is the assertion that says so.
     */
    @Test
    void aSleepingChunkIsAWallTheSurveyMadeUp() {
        HomeSurvey.Rooms found = new Plan(
                "###########",
                "#..@.#Z#..#",
                "#....DZD..#",
                "###########").survey();

        assertTrue(found.sealed(), "a sleeping chunk seals the room rather than holing it");
        assertTrue(found.floor() < 12, "only the near half was measured, got " + found.floor());
        assertTrue(found.asleep(), "and the reading has to say so");
    }

    @Test
    void anAwakeBuildingIsNotFlagged() {
        HomeSurvey.Rooms found = new Plan(
                "###########",
                "#..@.#.#..#",
                "#....D.D..#",
                "###########").survey();

        assertTrue(found.sealed());
        assertTrue(found.floor() > 12, "the far half joined, got " + found.floor());
        assertFalse(found.asleep(), "nothing was asleep, so nothing to own up to");
    }

    // --- furniture is floor ---------------------------------------------------

    /**
     * A wardrobe standing floor to ceiling does not take that square off you.
     *
     * The fill goes OVER a chest, so in a tall room the square above it was
     * counted and nobody ever noticed. In a two-high room there is no square
     * above it, and furnishing the place quietly made it smaller -- which is
     * exactly backwards.
     */
    @Test
    void furnitureToTheCeilingStillCounts() {
        String[] storey = {
                "#####",
                "#@F.#",
                "#...#",
                "#####"};
        HomeSurvey.Rooms found = new Tower("...",
                new String[]{"#####", "#####", "#####", "#####"},   // the floor
                storey,                                             // knees
                storey,                                             // head
                new String[]{"#####", "#####", "#####", "#####"}    // the ceiling
        ).survey();

        assertTrue(found.sealed());
        assertEquals(6, found.floor(), "three by two, furniture and all -- it was 5");
        assertEquals(2, found.props().size(), "both halves of the wardrobe");
    }

    // --- balconies ------------------------------------------------------------

    /**
     * A platform in the air with a house on one end and a balcony on the other.
     *
     * @param ground what lies off the edge of the platform: '.' is a drop, '#'
     *               is the rest of the world's ground going on forever
     */
    private static Tower withBalcony(char ground) {
        String[] platform = {
                "########...",
                "##########.",
                "##########.",
                "########...",
                "########...",
                "########..."};
        // Everything the platform does NOT cover is the drop, or the rest of
        // the world's ground -- which is the only difference between the two
        // plans and the only thing these two tests are about.
        for (int z = 0; z < platform.length; z++) {
            platform[z] = platform[z].replace('.', ground);
        }
        return new Tower(ground + "..",
                platform,
                new String[]{          // the storey, and the balcony floor
                        "########...",
                        "#......#...",
                        "#..@...D...",
                        "#......#...",
                        "#......#...",
                        "########..."},
                new String[]{          // roof over the house, sky over the balcony
                        "########...",
                        "########...",
                        "########...",
                        "########...",
                        "########...",
                        "########..."});
    }

    @Test
    void aBalconyCountsTowardsTheHouse() {
        HomeSurvey.Rooms found = withBalcony('.').survey();

        assertTrue(found.sealed(), "the house is still sealed; the balcony is outside it");
        assertEquals(1, found.exits().size(), "the balcony door is still a way in");
        assertEquals(24, found.indoors(), "six by four of actual house");
        assertEquals(4, found.terrace().size(), "two by two of balcony, and the drop past it");
        assertEquals(28, found.floor());
    }

    /** The same house on flat ground has a doorstep, not a terrace. */
    @Test
    void theGroundOutsideIsNotABalcony() {
        HomeSurvey.Rooms found = withBalcony('#').survey();

        assertTrue(found.sealed());
        assertEquals(0, found.terrace().size(), "the world is not somebody's patio");
        assertEquals(24, found.floor());
    }

    /**
     * A ring of fence is an afternoon and a storey is a week, so outdoor floor
     * is only ever worth a share of what is already built.
     */
    @Test
    void aBalconyIsWorthAShareOfTheHouse() {
        Set<Long> inside = new java.util.HashSet<>();
        for (int x = 0; x < 8; x++) {
            inside.add(HomeSurvey.cell(x, 1, 0));
        }
        Set<Long> terrace = new java.util.HashSet<>();
        for (int x = 0; x < 20; x++) {
            terrace.add(HomeSurvey.cell(x, 1, 5));
        }
        HomeSurvey.Rooms found = new HomeSurvey.Rooms(inside, Set.of(), terrace,
                List.of(), true, false, 0L, false, false);

        assertEquals(8, found.indoors());
        assertEquals(8 / HomeSurvey.TERRACE_SHARE, found.decking(),
                "twenty squares of decking on an eight-square shack is still a shack");
        assertEquals(10, found.floor());
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

    /** Smallest floor that reaches this grade. Tier 0 is not a house at all. */
    private static int smallestFloor(int tier) {
        return tier <= 0 ? 0 : HomeSurvey.FLOOR_STEPS[tier - 1];
    }

    @Test
    void betterHousesPayMore() {
        int last = -1;
        for (int tier = 0; tier <= HomeSurvey.TOP_TIER; tier++) {
            int rent = HomeSurvey.rentDue(tier, HomeSurvey.MOOD_MAX, 1, smallestFloor(tier));
            assertTrue(rent > last, "grade " + tier + " pays " + rent);
            last = rent;
        }
    }

    @Test
    void anUnhappyTenantPaysLess() {
        int happy = HomeSurvey.rentDue(3, HomeSurvey.MOOD_MAX, 1, 45);
        int fed = HomeSurvey.rentDue(3, HomeSurvey.MOOD_MAX / 2, 1, 45);
        assertTrue(fed < happy, fed + " should be under " + happy);
        assertEquals(0, HomeSurvey.rentDue(3, 0, 1, 45), "nobody pays on the way out");
        assertEquals(0, HomeSurvey.rentDue(0, HomeSurvey.MOOD_MAX, 1, 45), "nor for a condemned room");
    }

    @Test
    void aFamilyPaysMoreThanALodger() {
        // The rebalance in one assertion: a grade five with one bed is a
        // fraction of what it used to pay, and the way back to the old number
        // is four people in it rather than one.
        int lodger = HomeSurvey.rentDue(5, HomeSurvey.MOOD_MAX, 1, 150);
        int family = HomeSurvey.rentDue(5, HomeSurvey.MOOD_MAX, 4, 150);
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
        assertTrue(HomeSurvey.rentDue(5, smallest, 1, 150) < HomeSurvey.RENT[5] / 2);

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

    /** An empty house is a wait, and the grade is how long a wait. */
    @Test
    void betterHousesAreTakenSooner() {
        assertEquals(0f, HomeSurvey.lettingOdds(0), "nobody moves into a condemned house");
        float last = 0f;
        for (int tier = 1; tier <= HomeSurvey.TOP_TIER; tier++) {
            float odds = HomeSurvey.lettingOdds(tier);
            assertTrue(odds > last, "grade " + tier + " must fill faster than the one below");
            assertTrue(odds > 0f && odds < 1f,
                    "it has to stay a roll, not a certainty: grade " + tier + " is " + odds);
            last = odds;
        }
        // The whole point of the wait: a hovel is days behind a palace.
        assertTrue(1f / HomeSurvey.lettingOdds(1) - 1f / HomeSurvey.lettingOdds(
                        HomeSurvey.TOP_TIER) > 2f,
                "the gap between worst and best has to be worth building for");
    }

    /**
     * The notice, which is asked rather than remembered.
     *
     * Two things have to hold or the mechanic is broken in a way no session
     * would show up: the answer must never change for a given house and day --
     * that is what makes it survive a restart when nothing on disk records it
     * -- and consecutive days must be independent, because a seed the RNG
     * barely scrambles would have tenants quitting in runs and whole streets
     * emptying on the same morning.
     */
    @Test
    void aTenantCanJustGiveNotice() {
        int houses = 200;
        int days = 500;
        int quits = 0;
        int runs = 0;
        for (int house = 0; house < houses; house++) {
            long who = house * 7919L - 3;   // ids are all over the place; so is this
            for (int day = 0; day < days; day++) {
                boolean today = HomeSurvey.quitting(who, day);
                assertEquals(today, HomeSurvey.quitting(who, day),
                        "the same house on the same day must answer the same way, or a "
                                + "restart cancels a notice that is already on the mailbox");
                if (today) {
                    quits++;
                    if (HomeSurvey.quitting(who, day + 1)) {
                        runs++;
                    }
                }
            }
        }
        float rate = quits / (float) (houses * days);
        assertTrue(Math.abs(rate - HomeSurvey.QUIT_ODDS) < 0.005f,
                "notices should land near " + HomeSurvey.QUIT_ODDS + " a day, got " + rate);
        assertTrue(runs / (float) (houses * days) < 0.005f,
                "two notices back to back should be as rare as chance, got " + runs
                        + " -- a seed that is not stirred quits in runs");
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

    // --- what a resident earns ------------------------------------------------

    /**
     * The floor a house of this grade is built on, at its smallest -- where
     * the size lift is worth nothing, so these are the bare table rates.
     */
    private static int floorFor(int tier) {
        return smallestFloor(tier);
    }

    @Test
    void everyGradeClearsItsOwnRent() {
        for (int tier = 1; tier < HomeSurvey.RENT.length; tier++) {
            int wage = HomeSurvey.wageDue(tier, 1, floorFor(tier));
            int rent = HomeSurvey.rentDue(tier, HomeSurvey.MOOD_MAX, 1, floorFor(tier));
            assertTrue(wage > rent,
                    "grade " + tier + " earns " + wage + " and owes " + rent);
        }
    }

    @Test
    void aBetterHouseIsBetterPaid() {
        assertTrue(HomeSurvey.wageDue(8, 1, floorFor(8))
                > HomeSurvey.wageDue(1, 1, floorFor(1)));
    }

    @Test
    void aHouseholdEarnsPerHead() {
        assertEquals(HomeSurvey.wageDue(4, 1, 90) * 4, HomeSurvey.wageDue(4, 4, 90));
    }

    @Test
    void nobodyIsPaidForACondemnedRoom() {
        assertEquals(0, HomeSurvey.wageDue(0, 1, 200));
        assertEquals(0, HomeSurvey.wageDue(4, 0, 200));
    }

    // --- size counts too ------------------------------------------------------

    @Test
    void aBiggerHouseOfTheSameGradePaysMore() {
        int small = HomeSurvey.wageDue(4, 1, HomeSurvey.FLOOR_STEPS[3]);
        int large = HomeSurvey.wageDue(4, 1, HomeSurvey.FLOOR_STEPS[4] - 1);
        assertTrue(large > small,
                "149 blocks of floor should out-earn 90, got " + small + " -> " + large);
    }

    /**
     * The property that matters: laying another block of floor must never cost
     * somebody money, band boundaries included. A dip here would mean a house
     * that pays LESS for being finished, which nobody would ever report as a
     * wage bug -- they would report that the tenants were being odd.
     */
    @Test
    void moreFloorIsNeverLessPay() {
        int last = -1;
        for (int floor = 0; floor <= 900; floor++) {
            int tier = HomeSurvey.sizeTier(floor);
            int wage = tier == 0 ? 0 : HomeSurvey.wageDue(tier, 1, floor);
            assertTrue(wage >= last,
                    "pay dipped at " + floor + " blocks: " + last + " -> " + wage);
            last = wage;
        }
    }

    @Test
    void theLiftNeverReachesTheNextGrade() {
        for (int tier = 1; tier < HomeSurvey.TOP_TIER; tier++) {
            int topOfBand = HomeSurvey.wageDue(tier, 1, HomeSurvey.FLOOR_STEPS[tier] - 1);
            int nextGrade = HomeSurvey.wageDue(tier + 1, 1, HomeSurvey.FLOOR_STEPS[tier]);
            assertTrue(topOfBand < nextGrade,
                    "grade " + tier + " at its biggest (" + topOfBand
                            + ") must still earn under grade " + (tier + 1)
                            + " at its smallest (" + nextGrade + ")");
        }
    }

    /** The top grade keeps rewarding size for one more band, then stops. */
    @Test
    void aPalaceOutEarnsAMansion() {
        assertTrue(HomeSurvey.wageDue(8, 1, 740) > HomeSurvey.wageDue(8, 1, 560));
    }

    @Test
    void andThenItIsTrulyTheCeiling() {
        assertEquals(HomeSurvey.wageDue(8, 1, 740), HomeSurvey.wageDue(8, 1, 50_000));
    }

    @Test
    void rentIsLiftedBySizeTheSameWayWagesAre() {
        int small = HomeSurvey.rentDue(4, HomeSurvey.MOOD_MAX, 1, HomeSurvey.FLOOR_STEPS[3]);
        int large = HomeSurvey.rentDue(4, HomeSurvey.MOOD_MAX, 1,
                HomeSurvey.FLOOR_STEPS[4] - 1);
        assertTrue(large > small, "a bigger grade four should be owed more rent, got "
                + small + " -> " + large);
    }

    /**
     * The two sides of the ledger must move together. They are one rate seen
     * from both ends, and the only thing between them is WAGE_MULTIPLE -- if
     * that stops being true, somewhere a tenant is being paid on one size and
     * billed on another.
     */
    @Test
    void wagesStayExactlyAMultipleOfRent() {
        for (int tier = 1; tier <= HomeSurvey.TOP_TIER; tier++) {
            for (int floor : new int[]{smallestFloor(tier), smallestFloor(tier) + 25,
                    HomeSurvey.topFloor()}) {
                int rent = HomeSurvey.rentDue(tier, HomeSurvey.MOOD_MAX, 1, floor);
                int wage = HomeSurvey.wageDue(tier, 1, floor);
                assertEquals(rent * HomeSurvey.WAGE_MULTIPLE, wage, 1,
                        "grade " + tier + " at " + floor + ": rent " + rent
                                + ", wage " + wage);
            }
        }
    }

    @Test
    void aBarnWithNoFurnitureIsStillOnlyItsOwnGrade() {
        assertEquals(1.0f, HomeSurvey.roominess(2, 100_000), 0.001f);
        assertEquals(0.0f, HomeSurvey.roominess(2, 0), 0.001f);
    }

    // --- blocks of flats ------------------------------------------------------

    /**
     * Flats each opening onto the street are separate houses, and their ground
     * does not clash. This is the shape a terrace or a walkway block wants.
     */
    @Test
    void flatsWithTheirOwnFrontDoorAreSeparateHouses() {
        Plan block = new Plan(
                ".........",
                "###D#D###",
                "#.@.#.B.#",
                "#...#...#",
                "#########");
        HomeSurvey.Rooms left = block.surveyFrom('@');
        HomeSurvey.Rooms right = block.surveyFrom('B');

        assertTrue(left.sealed() && right.sealed(), "both flats are sealed");
        assertEquals(1, left.exits().size(), "one door onto the street each");
        assertEquals(1, right.exits().size());
        assertFalse(HomeSurvey.overlaps(HomeSurvey.bounds(left.inside()),
                        HomeSurvey.bounds(right.inside())),
                "flats sharing a wall must not fight over the same ground");
    }

    /**
     * Flats off a SHARED corridor are one building, not several.
     *
     * A door probe that closes merges the room it found and queues that room's
     * doors, so the corridor merges and then carries the survey on through
     * every other flat's door. Worth knowing rather than fixing: the merged
     * result is a bigger house, and rent and wages are per head, so a block
     * surveyed whole out-earns the same flats surveyed apart.
     */
    @Test
    void flatsOffASharedCorridorAreOneBuilding() {
        Plan block = new Plan(
                ".........",
                "####D####",
                "#.......#",
                "##D###D##",
                "#.@.#.B.#",
                "#########");
        HomeSurvey.Rooms whole = block.surveyFrom('@');

        assertTrue(whole.sealed());
        assertEquals(1, whole.exits().size(), "the corridor's street door, and only that");
        assertTrue(whole.inside().contains(HomeSurvey.cell(4, 0, 2)), "corridor is inside");
        assertTrue(whole.inside().contains(HomeSurvey.cell(6, 0, 4)), "so is the far flat");
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
