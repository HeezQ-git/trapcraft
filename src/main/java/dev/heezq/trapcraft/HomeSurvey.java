package dev.heezq.trapcraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What counts as a house, and what it is worth.
 *
 * Imports nothing from Minecraft, on purpose and for the same reason
 * {@link TrapMath} does not: the two-phase flood fill is the highest-risk
 * logic in the whole city design, and the only way to be sure of it is to run
 * it against a drawing instead of a world. {@link Space} is the entire world
 * as far as this class is concerned -- three questions about a block.
 *
 * <h2>Doors are walls</h2>
 *
 * The obvious design says the fill should pass through doors so a bedroom with
 * the door shut still counts. That is wrong, and it fails on every house ever
 * built: a front door is a door, so the fill walks out of it and into the
 * world. "Passes through doors" and "sealed room" are the same sentence
 * disagreeing with itself.
 *
 * So doors stop the fill, and then each door gets PROBED with a small fill of
 * its own. A probe that closes found a room -- merge it. A probe that runs out
 * of budget found the outdoors -- that door is a front door. The house learns
 * its own exterior for free, which is what step three needs for tenants.
 *
 * <pre>
 *     [bedroom]--door--[hall]--door--[porch]--door--[street]
 * </pre>
 *
 * Anchor in the hall. The bedroom probe closes and merges. The porch probe
 * closes -- doors are walls to a probe too, so the outer door bounds it -- and
 * merges, queueing the outer door. The outer door's probe escapes, so it is a
 * front door. Two doors side by side as a wide entrance both escape and
 * neither merges. A garage with an inner door and a garage door falls out the
 * same way with no special case anywhere.
 *
 * <h2>It cannot loop</h2>
 *
 * Breadth-first against a visited set, so no position is ever looked at twice
 * whether the fill escapes or not; three caps bound the total work; and every
 * probe is charged against one global budget, so nested rooms cannot multiply
 * into a stall.
 */
public final class HomeSurvey {

    /** The world, reduced to the three questions a survey asks about a block. */
    public interface Space {
        /** Can the fill expand into it: air, or something you can stand in. */
        boolean open(int x, int y, int z);

        /** A door, gate or trapdoor -- a wall the survey is curious about. */
        boolean door(int x, int y, int z);

        /** Inside somebody else's claim. */
        boolean taken(int x, int y, int z);
    }

    /** Blocks one room may hold before the survey calls it "not sealed". */
    public static final int ROOM_CAP = 4096;
    /** How far from the anchor a house may reach on any axis. */
    public static final int SPAN = 48;
    /**
     * Blocks one door probe may look at before it is declared outdoors.
     *
     * A thousand, not the five hundred the design sketched. Five hundred is a
     * ten by ten room three blocks high, which is a perfectly ordinary garage
     * -- and a probe that runs out of budget is called the outdoors, so the
     * cheap number would have quietly refused to count anybody's big rooms and
     * called their internal doors front doors. Outdoors blows any budget
     * immediately, so the only thing a larger one costs is the wasted work on
     * a genuine exterior door: it stops at exactly the cap either way.
     */
    public static final int PROBE_CAP = 1024;
    /** Blocks every probe of one house may look at between them. */
    public static final int TOTAL_CAP = 16384;

    private HomeSurvey() {
    }

    // --- packing --------------------------------------------------------------
    //
    // The same 26/12/26 split Minecraft uses for BlockPos, reimplemented rather
    // than imported so this file stays testable without a game on the
    // classpath. The caller converts; nothing here knows what a BlockPos is.

    public static long cell(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int cellX(long cell) {
        return (int) (cell >> 38);
    }

    public static int cellZ(long cell) {
        return (int) (cell << 26 >> 38);
    }

    public static int cellY(long cell) {
        return (int) (cell << 52 >> 52);
    }

    // --- the survey -----------------------------------------------------------

    /**
     * What one right-click of a mailbox found.
     *
     * @param inside every position that counts as floor space
     * @param exits  doors whose probe escaped -- the way in from the street
     * @param sealed false if the fill leaked; everything else is then noise
     * @param clash  the fill ran into another house's claim
     */
    public record Rooms(Set<Long> inside, List<Long> exits, boolean sealed, boolean clash,
                        long escape, boolean buried) {
        /**
         * Squares you could stand on. Every storey counts, headroom does not.
         *
         * Was distinct COLUMNS, which quietly said that building upwards is
         * worth nothing: a three-storey tower on a six by six footprint
         * measured thirty-six, the same as a bungalow, while a room with a
         * cathedral ceiling measured the same as a crawlspace. A cell with
         * something solid under it is a floor tile, so a staircase adds a
         * whole storey and a high ceiling adds nothing -- which is the way
         * round anybody would count it by eye.
         */
        public int floor() {
            int tiles = 0;
            for (long at : inside) {
                if (!inside.contains(cell(cellX(at), cellY(at) - 1, cellZ(at)))) {
                    tiles++;
                }
            }
            return tiles;
        }
    }

    private static final Rooms CLASHED =
            new Rooms(Set.of(), List.of(), false, true, 0L, false);

    /**
     * It leaked, and WHERE it leaked.
     *
     * "Find the hole" is not advice, it is a shrug -- the one thing somebody
     * staring at a failed survey needs is a direction. The escape is the
     * furthest square the fill got to, which is by definition on the far side
     * of whatever gap it went through.
     */
    private static Rooms leaked(long escape) {
        return new Rooms(Set.of(), List.of(), false, false, escape, false);
    }

    /** What one fill did: closed on itself, ran out of room, or hit a claim. */
    private static final int CLOSED = 0;
    private static final int CAPPED = 1;
    private static final int CLASH = 2;

    /**
     * Fill outward from the anchor and work out where the house ends.
     *
     * The anchor is where the mailbox was standing the first time somebody
     * surveyed, and it never moves afterwards. That is what lets the mailbox
     * itself be carried outside and nailed to the wall by the street without
     * the house forgetting which room it is.
     */
    public static Rooms survey(Space space, int ax, int ay, int az) {
        // Somebody bricked over the spot the house was measured from. There is
        // no room here any more, whatever is on the other side of the bricks.
        if (!space.open(ax, ay, az)) {
            // Not a hole. Somebody built over the spot this was measured from,
            // which is a completely different problem and used to be reported
            // as the same one.
            return new Rooms(Set.of(), List.of(), false, false, cell(ax, ay, az), true);
        }

        Set<Long> visited = new HashSet<>();
        Set<Long> inside = new HashSet<>();
        List<Long> doors = new ArrayList<>();

        int first = fill(space, cell(ax, ay, az), ax, ay, az, ROOM_CAP,
                visited, inside, doors, null);
        if (first != CLOSED) {
            return first == CLASH ? CLASHED : leaked(furthest(inside, ax, ay, az));
        }

        List<Long> exits = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>(doors);
        Set<Long> probed = new HashSet<>();
        int spent = 0;

        while (!queue.isEmpty()) {
            long door = queue.poll();
            if (!probed.add(door)) {
                continue;
            }
            if (spent >= TOTAL_CAP) {
                // Out of budget. Everything left is called outdoors, which is
                // the safe way round: a house that quietly swallowed a cave is
                // a much worse answer than one that thinks it has more front
                // doors than it does.
                exits.add(door);
                continue;
            }

            Set<Long> seen = new HashSet<>();
            Set<Long> room = new HashSet<>();
            List<Long> beyond = new ArrayList<>();
            int probe = fill(space, door, ax, ay, az, Math.min(PROBE_CAP, TOTAL_CAP - spent),
                    seen, room, beyond, inside);
            spent += seen.size();

            if (probe == CLASH) {
                return CLASHED;
            }
            if (probe == CAPPED) {
                exits.add(door);
                continue;
            }
            inside.addAll(room);
            queue.addAll(beyond);
        }

        return new Rooms(inside, exits, true, false, 0L, false);
    }

    /** The square the fill got furthest from home. Through the hole, if there is one. */
    private static long furthest(Set<Long> cells, int ax, int ay, int az) {
        long best = cell(ax, ay, az);
        long away = -1;
        for (long at : cells) {
            long dx = cellX(at) - ax;
            long dy = cellY(at) - ay;
            long dz = cellZ(at) - az;
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance > away) {
                away = distance;
                best = at;
            }
        }
        return best;
    }

    /**
     * One breadth-first fill.
     *
     * @param from   seeded position; passable even if it is a door, which is
     *               what makes a probe start ON the door it is probing
     * @param known  positions already counted as inside, skipped rather than
     *               re-walked -- null on the first pass, when nothing is known
     * @return {@link #CLOSED}, {@link #CAPPED} or {@link #CLASH}
     */
    private static int fill(Space space, long from, int ax, int ay, int az, int cap,
                            Set<Long> visited, Set<Long> found, List<Long> doors,
                            Set<Long> known) {
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(from);
        visited.add(from);
        found.add(from);

        while (!queue.isEmpty()) {
            long at = queue.poll();
            int x = cellX(at);
            int y = cellY(at);
            int z = cellZ(at);

            for (int side = 0; side < 6; side++) {
                int nx = x + (side == 0 ? 1 : side == 1 ? -1 : 0);
                int ny = y + (side == 2 ? 1 : side == 3 ? -1 : 0);
                int nz = z + (side == 4 ? 1 : side == 5 ? -1 : 0);
                if (Math.abs(nx - ax) > SPAN || Math.abs(ny - ay) > SPAN
                        || Math.abs(nz - az) > SPAN) {
                    // Off the edge of what any one house may claim. Treated as
                    // a wall rather than a leak, so a long corridor stops being
                    // a house instead of failing the survey.
                    continue;
                }
                long next = cell(nx, ny, nz);
                if (!visited.add(next)) {
                    continue;
                }
                if (space.taken(nx, ny, nz)) {
                    return CLASH;
                }
                if (space.door(nx, ny, nz)) {
                    doors.add(next);
                    continue;
                }
                if (known != null && known.contains(next)) {
                    continue;   // the room we came from; already counted
                }
                if (!space.open(nx, ny, nz)) {
                    continue;
                }
                found.add(next);
                if (found.size() > cap) {
                    return CAPPED;
                }
                queue.add(next);
            }
        }
        return CLOSED;
    }

    // --- the grade ------------------------------------------------------------

    /** Floor tiles a house needs before anybody will call it a house. */
    public static final int MIN_FLOOR = 9;
    /**
     * Grades. Was five, and five was not enough room at the top.
     *
     * A hundred and fifty squares with a bed and a shopping list of fittings
     * hit the ceiling, and after that there was nothing left to build for --
     * so the best house anybody would ever make was the one they made in an
     * afternoon. Six, seven and eight are mansion country: they need floor
     * nobody puts down by accident, and enough different blocks in the place
     * that decorating is the work rather than an afterthought.
     */
    public static final int TOP_TIER = 8;

    /**
     * Floor a house needs to be ALLOWED each grade. A ceiling, not a bonus.
     *
     * This is the change that made the grade mean something. Floor area used
     * to be worth three points out of ten, so a three-by-four cupboard with a
     * bed, a table, a chest, a furnace and a torch graded four out of five --
     * and the honest reaction to that is the one it got: "I don't have to put
     * any effort in." Fittings are a shopping list and a shopping list is not
     * a building.
     *
     * Now size is a hard lid on top of everything else. A cupboard is a grade
     * one whatever is in it, and the top grade is a build rather than a
     * checklist: a hundred and fifty squares of floor is a proper house, or
     * three storeys of a modest one.
     */
    public static final int[] FLOOR_STEPS = {9, 20, 45, 90, 150, 240, 380, 560};

    /**
     * Distinct block kinds in the place that each earn a point.
     *
     * Two steps became four when the ceiling moved. Twenty-two kinds is a
     * decorated room; fifty-five is somebody who went shopping for the job,
     * which is the point of the top grades and the reason the market now
     * sells two and a half thousand modded lines.
     */
    public static final int[] DECOR_STEPS = {12, 22, 36, 55};
    /**
     * Share of the shell that has to be worked material rather than dug up.
     *
     * The other half of the same complaint. Nothing used to care what a house
     * was MADE of, so a dirt box scored exactly what a brick one did. Dirt,
     * sand, gravel, plain stone and cobble are what the world hands you;
     * planks, bricks, glass, wool and everything a mod ships as decoration
     * are things somebody chose.
     */
    public static final float[] SHELL_STEPS = {0.60f, 0.90f, 0.98f};
    /** Light level below which a square counts as a dark corner. */
    public static final int DARK_AT = 8;
    /**
     * Share of the floor allowed to be dark, for two points and for one.
     *
     * Was "none at all" for the second point, which sounds reasonable and is
     * not: light falls off a level a block, so a ceiling torch lands about ten
     * on the floor directly under it and less between torches. Demanding a
     * perfectly even floor meant tearing up somebody's decoration to bury
     * lamps in it, which is the opposite of what a grade for decoration is
     * supposed to encourage.
     */
    public static final float GLOOM_GOOD = 0.05f;
    public static final float GLOOM_ALLOWED = 0.20f;
    /** Fittings there are to find: crafting, storage, cooking, stall, window. */
    public static final int FITTINGS = 5;

    /** The grade this much floor allows, whatever else is true. */
    public static int sizeTier(int floor) {
        int allowed = 0;
        for (int step : FLOOR_STEPS) {
            if (floor >= step) {
                allowed++;
            }
        }
        return Math.min(TOP_TIER, allowed);
    }

    /** Worked material rather than dug up, 0-2. */
    public static int shellPoints(float finished) {
        int points = 0;
        for (float step : SHELL_STEPS) {
            if (finished >= step) {
                points++;
            }
        }
        return points;
    }

    /** Crafting, storage, cooking, a stall, a window: 0-3. */
    public static int fittingPoints(int fittings) {
        return fittings >= FITTINGS ? 3 : fittings >= 4 ? 2 : fittings >= 2 ? 1 : 0;
    }

    /** Distinct kinds of block in the place, 0-2. */
    public static int decorPoints(int kinds) {
        int points = 0;
        for (int step : DECOR_STEPS) {
            if (kinds >= step) {
                points++;
            }
        }
        return points;
    }

    /**
     * Dark corners, 0-2.
     *
     * Measured off the light actually reaching the floor rather than off a
     * count of torches, because "one lamp per sixteen squares" is a sum and
     * "there is a dark bit at the top of the stairs" is a house. It also
     * gives step three its letters for free.
     */
    public static int lightPoints(int dark, int floor) {
        if (dark <= 0) {
            return 2;
        }
        if (floor <= 0) {
            return 0;
        }
        if (dark <= Math.max(1, (int) (floor * GLOOM_GOOD))) {
            return 2;
        }
        return dark <= Math.max(2, (int) (floor * GLOOM_ALLOWED)) ? 1 : 0;
    }

    /** Everything above the bare minimum, added up. */
    public static int points(float finished, int fittings, int kinds, int dark, int floor) {
        return shellPoints(finished) + fittingPoints(fittings)
                + decorPoints(kinds) + lightPoints(dark, floor);
    }

    /** The most {@link #points} can come to, for the guide book. */
    public static int topPoints() {
        return SHELL_STEPS.length + 3 + DECOR_STEPS.length + 2;
    }

    /**
     * What the place is worth, 0 for "nobody would live here".
     *
     * The five hard requirements are the ones a person would say out loud: it
     * has to be sealed, big enough to turn round in, have a bed, have a way
     * in, and not be pitch dark. Then it is points -- and then size puts a lid
     * on the answer, because no amount of furniture makes a cupboard a house.
     *
     * @param floor    floor tiles, every storey counted
     * @param finished share of the shell that is worked material, 0-1
     * @param fittings how many of crafting, storage, cooking, stall, window
     * @param kinds    distinct block types in the place
     * @param dark     floor squares under light {@link #DARK_AT}
     * @param lights   light sources, only to tell "some" from "none"
     */
    public static int tier(boolean sealed, int floor, boolean bed, boolean exit,
                           float finished, int fittings, int kinds, int dark, int lights) {
        if (!sealed || floor < MIN_FLOOR || !bed || !exit || lights < 1) {
            return 0;
        }
        // Spread across whatever the ceiling is rather than divided by two.
        // The old /2 topped out at grade five because points top out at nine,
        // so raising TOP_TIER on its own would have added three grades nobody
        // could reach -- a ladder with the last rungs sawn off.
        int earned = 1 + Math.min(TOP_TIER - 1,
                points(finished, fittings, kinds, dark, floor) * (TOP_TIER - 1) / topPoints());
        return Math.min(earned, sizeTier(floor));
    }

    // --- somebody lives there -------------------------------------------------

    /**
     * What ONE TENANT pays a day, by grade.
     *
     * Per head, which is the whole rebalance. A grade five used to pay 280 a
     * day for one bed in one room, which is a fat contract every two days for
     * work you did once -- and the honest reaction to that was the one it got:
     * you stop farming and build a house. It pays 62 now.
     *
     * You get back to 280 by housing four people, and housing four people
     * means four beds and the floor to put them in. The money is the same at
     * the top; what changed is that it is now paid for a building rather than
     * for a room, and the way to earn more is to build more.
     */
    public static final int[] RENT = {0, 6, 14, 26, 42, 62, 86, 112, 140};

    /**
     * What a resident of this grade earns a day, before tax.
     *
     * Anchored to {@link #RENT} rather than given a table of its own, so the
     * two can never drift into a grade that costs more to live in than it pays
     * to live in. Somebody would have to notice that, and nobody ever does --
     * it reads as "the tenants keep leaving" three systems away.
     *
     * Mood is deliberately not a term here. Rent bends with how a tenant feels
     * about the place; a wage is paid by an employer who has never seen it.
     */
    public static final int WAGE_MULTIPLE = 3;

    public static int wageDue(int tier, int heads) {
        if (tier <= 0 || tier >= RENT.length || heads <= 0) {
            return 0;
        }
        return RENT[tier] * WAGE_MULTIPLE * heads;
    }

    /**
     * Floor each resident past the first needs.
     *
     * Beds alone would make a dormitory: eight bunks in a grade one and the
     * rent of a mansion. Space is the thing that cannot be crafted in a
     * stack, so space is what gates the household.
     */
    public static final int FLOOR_PER_HEAD = 30;

    /**
     * How many people actually live here.
     *
     * Three things have to agree -- beds to sleep in, floor to stand on, and
     * a house good enough that a family would put up with it. The lowest of
     * the three wins, and one is the floor: somebody lives in a hovel.
     */
    public static int household(int beds, int floor, int tier) {
        if (tier <= 0 || beds <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(Math.min(beds, tier), floor / FLOOR_PER_HEAD));
    }

    /** Mood runs 0 to 100 and moves at most this much a day. */
    public static final int MOOD_MAX = 100;
    public static final int MOOD_STEP = 15;
    /** Mood a tenant starts on, so a new one has a little patience. */
    public static final int MOOD_START = 70;
    /** Each dark square on the floor costs this much of the target. */
    public static final int GLOOM_COST = 4;
    /**
     * What a grow next door costs, and what each tier of it costs on top.
     *
     * A curve rather than a cliff, because heat tier 0 already means a grow
     * big enough to attract a patrol -- so the smallest thing that registers
     * should already be a serious problem, and anything above it should be
     * unliveable. Tier 0 leaves them miserable and paying two fifths; tier 1
     * and up puts the target under {@link #MOOD_LEAVING} and they pack.
     *
     * This is the load-bearing tension of the whole city design: the
     * plantation and the apartment block cannot be the same place.
     */
    public static final int HEAT_COST = 60;
    public static final int HEAT_STEP = 30;
    /** Under this and they are packing. */
    public static final int MOOD_LEAVING = 25;

    /**
     * What the place would settle at if nothing else changed.
     *
     * Separate from the drift so a house can be diagnosed: the target is the
     * verdict on the building, and the mood is how far the person living in it
     * has got round to feeling it.
     *
     * @param tier     0 means condemned, and nobody stays in a condemned house
     * @param dark     floor squares under {@link #DARK_AT}
     * @param heatTier -1 for nothing growing nearby, 0 and up for a grow
     */
    public static int moodTarget(int tier, int dark, int heatTier) {
        if (tier <= 0) {
            return 0;
        }
        int target = MOOD_MAX - Math.max(0, dark) * GLOOM_COST;
        if (heatTier >= 0) {
            target -= HEAT_COST + heatTier * HEAT_STEP;
        }
        return Math.max(0, Math.min(MOOD_MAX, target));
    }

    /** One day of feeling it. */
    public static int moodDrift(int mood, int target) {
        if (mood < target) {
            return Math.min(target, mood + MOOD_STEP);
        }
        return Math.max(target, mood - MOOD_STEP);
    }

    /**
     * What actually lands in the till, mood and all.
     *
     * A slide shows up in the money before it shows up as an empty house,
     * which is the difference between a system that warns you and a system
     * that surprises you.
     */
    public static int rentDue(int tier, int mood, int heads) {
        if (tier <= 0 || tier >= RENT.length || mood <= 0 || heads <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(RENT[tier] * heads
                * Math.min(MOOD_MAX, mood) / (float) MOOD_MAX));
    }

    // --- claims ---------------------------------------------------------------

    /**
     * Do two houses fight over the same ground?
     *
     * Boxes rather than the filled sets, which is coarser than it could be and
     * is the right call anyway: two flats sharing a wall have separate boxes,
     * a flat above another has separate boxes, and the case it does refuse --
     * one house wrapped around another in an L -- is a case where "whose room
     * is this" has no good answer.
     */
    public static boolean overlaps(int[] a, int[] b) {
        for (int axis = 0; axis < 3; axis++) {
            if (a[axis + 3] < b[axis] || b[axis + 3] < a[axis]) {
                return false;
            }
        }
        return true;
    }

    /** Min x, y, z then max x, y, z of everything the survey found. */
    public static int[] bounds(Set<Long> inside) {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long at : inside) {
            int x = cellX(at);
            int y = cellY(at);
            int z = cellZ(at);
            box[0] = Math.min(box[0], x);
            box[1] = Math.min(box[1], y);
            box[2] = Math.min(box[2], z);
            box[3] = Math.max(box[3], x);
            box[4] = Math.max(box[4], y);
            box[5] = Math.max(box[5], z);
        }
        return box;
    }
}
