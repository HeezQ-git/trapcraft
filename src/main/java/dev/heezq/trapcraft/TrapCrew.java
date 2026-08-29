package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Somebody to do the picking, and what happens when you train them.
 *
 * A grow big enough to be worth raiding is a grow too big to harvest by hand,
 * and the answer a trap house reaches for is not a hopper -- it is people. Hire
 * one and they work a patch around wherever you put them: mature plants get
 * picked, the drop goes in the nearest chest, and they take a wage out of your
 * pocket every few minutes whether the harvest was good or not.
 *
 * The wage is the point. This mod had no recurring cost at all, so money only
 * ever went one way once you were established. A crew is the first thing you
 * own that can lose you money by existing, which is what makes deciding to
 * hire one an actual decision.
 *
 * <h2>And they come from somewhere</h2>
 *
 * A hand is one of the town's own residents, drawn through
 * {@link TrapHomes#freeResident} exactly as a shopper, a punter and a clubber
 * are -- see {@link #put} for what that changed and why it was the last
 * phantom in the mod. Two consequences worth knowing before you build a farm:
 * a patch out of reach of anybody's house employs nobody, and every hand you
 * take on is somebody who is now not shopping at anyone's counter.
 *
 * Their wages go the same way, into {@link TrapPayroll} rather than out of the
 * world -- see {@link #payTheTown}. The town supplies the labour and the town
 * gets the wage bill back; those are the two halves of the same sentence and
 * they used to be neither.
 *
 * <h2>What was wrong with the first one</h2>
 *
 * It picked about one plant every two minutes, which is not a worker, it is an
 * ornament. Three separate causes, all fixed here:
 *
 * <ul>
 *   <li>It asked the pathfinder to walk somewhere and then let the villager
 *       BRAIN immediately overrule it. A villager decides where to go from
 *       {@code WALK_TARGET}; going round it via the navigator means every
 *       stroll task cancels you. Now the target goes in the memory, which both
 *       moves them and -- because the stroll tasks require that memory to be
 *       ABSENT -- stops them wandering at the same time. One fix, two bugs.
 *   <li>It looked for work from the middle of the patch every time, so the
 *       hand walked past ripe plants to reach the one nearest a fixed point.
 *       Work is now whatever is nearest the HAND.
 *   <li>Ten seconds a pass with the arrival check failing most passes. Pace is
 *       now something you buy, down to a pass and a half.
 * </ul>
 *
 * <h2>Why they are slightly small</h2>
 *
 * Farmland is trampled by {@code FarmlandBlock.onLandedUpon}, which fires for
 * any living entity whose {@code width * width * height} clears 0.512. A
 * villager is 0.6 x 1.95, which is 0.70 -- so every jump was undoing somebody's
 * hoe work and knocking the buds down a grade. Scaled to 0.85 the same sum is
 * 0.43 and the game simply never considers them heavy enough to break
 * anything. No mixin, no gamerule, and it reads as a lad rather than a bug.
 *
 * <h2>Why the patch stays loaded</h2>
 *
 * A hand is a villager, and a villager in an unloaded chunk is not slow -- it
 * does not exist. So a crew only ever worked while somebody stood over them,
 * which is the opposite of the point: you hire people so that you can go and
 * do something else. Every patch whose owner is logged in now holds a chunk
 * ticket, so the field ticks whether or not anybody is watching it.
 *
 * The ticket expires on its own and is re-stamped every few seconds. That is
 * the whole of the cleanup: fire a hand, log out, restart the server, crash
 * the server -- nothing re-stamps it, and within fifteen seconds the chunks go
 * back to sleep. There is no list of forced chunks to leak, and nothing to
 * remove in six different places.
 *
 * It is deliberately gated on the OWNER being online rather than anybody:
 * wages already skip an absent boss, so an overnight farm would be free labour,
 * and a server sat empty should not be ticking five fields.
 */
public final class TrapCrew {
    /**
     * Ticks ON THE CLOCK between wage packets. Five minutes of WORKING.
     *
     * Not five minutes of wall clock, which is what it used to be and is half
     * of why a hand felt like it never earned its keep. They work daylight
     * only, so a wall-clock packet charged full price for a shift that was
     * half night: over a Minecraft day you paid four packets for two packets
     * of work and the game never said so anywhere.
     *
     * Now the clock only turns while somebody is actually on the patch and
     * awake, so a wage packet always buys the same amount of work. Nights are
     * free, which reads as a feature rather than as the arithmetic apology it
     * actually is.
     */
    private static final int WAGE_TICKS = 20 * 60 * 5;
    /** How often the clock is looked at. Once a second is plenty. */
    private static final int CLOCK_TICKS = 20;
    /**
     * What one untrained hand costs per packet.
     *
     * Went up with the clock change, not instead of it. A packet now covers
     * twice the work it used to, so leaving the number alone would have
     * halved the cost of the whole crew by accident.
     */
    public static final int WAGE = 40;
    /**
     * Paydays a hand will work for nothing before walking.
     *
     * They used to leave the instant a packet bounced, taking everything you
     * had taught them, off a single bad minute. Four packets is about two
     * days' work, and every one of them says so out loud -- which turns a
     * disaster into a deadline. Nothing is owed retroactively; the arrears
     * clear the moment one packet goes through.
     */
    public static final int GRACE_PACKETS = 4;
    /** What it costs to take somebody on. */
    public static final int HIRE_COST = 120;
    /**
     * How many jobs one person will hold, and how many people you may have.
     *
     * TWO. Somebody who picks, dries, rolls, presses, refines, sows, tills and
     * fertilises is not a worker, it is a factory with a face -- and once one
     * hand could do everything, the only decision left was whether to buy the
     * whole list. A pair of jobs each makes every hire a question about what
     * this particular person is FOR, and wanting a third thing done means
     * wanting a third person on the books.
     *
     * The free cap on people went up to match. Ten jobs against five hands is
     * ten slots for ten jobs, so a full operation is reachable on the places
     * the game gives you -- but it is five wages, and the wage is what stops
     * that being free.
     *
     * It used to have one slot spare, and laundering took it. Doing everything
     * at once is an exact fit on five hands rather than a comfortable one:
     * there is no longer a hand who can double up on the job you actually care
     * about while the rest of the list is still covered. Wanting that spare
     * back is what {@link #PLACE_COST} is for, and it is a purchase.
     */
    public static final int SLOTS = 2;

    /**
     * Places on the books that come for nothing.
     *
     * Five, which is what the cap used to be full stop. It stays the number
     * you get free, so a player who already ran a full crew wakes up owing
     * nobody anything and loses nothing they had.
     */
    public static final int FREE_HANDS = 5;

    /**
     * What each place past the free five costs, in the order they are bought.
     *
     * Doubling, and deliberately steeper than anything else on this board: the
     * top pace rung is 2200e, so even the FIRST bought place is the largest
     * single purchase in the crew system. That is the point. A place is not an
     * upgrade to somebody, it is permission to run a bigger operation, and the
     * thing that has to stop it being a formality is the five wages already on
     * the books before anybody clicks it.
     *
     * One-off, and it does not come back -- exactly like the hire fee it sits
     * next to. Firing somebody frees the place, not the money.
     */
    public static final int[] PLACE_COST =
            {1500, 3500, 8000, 18000, 36000, 72000, 144000};

    /**
     * The free places plus every one that can be bought.
     *
     * Twelve, and the crew board holds exactly twelve heads -- six along the
     * top, six along the bottom -- which is not a coincidence: a thirteenth
     * place would be a hand on the payroll that no click can reach.
     * Lengthening PLACE_COST means finding room on that board first, and
     * {@link CrewScreenHandler} refuses to open rather than trust anybody to
     * remember that.
     *
     * The last three rungs are a different kind of money from the first four.
     * Twelve maxed hands is roughly 27,000e an hour in wages before anybody
     * has bought a place at all, so the ceiling is the payroll and these
     * numbers only decide how long you stare at it first.
     */
    public static final int MAX_HANDS = FREE_HANDS + PLACE_COST.length;
    /**
     * Slots something needs before a hand will treat it as its chest.
     *
     * A hand takes the NEAREST inventory to its patch, and a furnace, a
     * brewing stand, a hopper, a dropper and a Farmer's Delight skillet are
     * all inventories. Somebody who put a smelter at the edge of their field
     * had a worker quietly stuffing wheat into it -- three slots, then "full",
     * then the rest of the harvest on the floor next to a chest that was
     * empty, and nothing about that reads as "wrong container".
     *
     * Judged on SIZE rather than on a list of block classes, because such a
     * list is a list of VANILLA's machines and there are two hundred and
     * forty mods here. Everything built to store things is a chest's
     * twenty-seven or more; every machine vanilla has is far under it. Getting
     * it wrong costs a hand a container it could have used, which shows up on
     * the board as "BRAK SKRZYNI" -- a line to read, not a harvest on the
     * ground.
     */
    private static final int BOX_SLOTS = 27;
    /** How close a hand has to be to a job to do it. */
    private static final int ARM = 4;
    /**
     * Stops one courier will keep, and how much they carry in one go.
     *
     * Three because a round is a decision. A courier who could serve every
     * counter you own is a pipe, and what this wants to be is a person with a
     * round -- the interesting question is which three of your shops are worth
     * a wage to keep stocked, and a bigger number deletes it.
     *
     * Eight stacks because that is what a villager's own bag holds, and the
     * bag is where the load rides. Nothing to serialise, nothing to leak: a
     * courier caught by a restart mid-run comes back holding the goods.
     */
    public static final int ROUTE_STOPS = 3;
    private static final int LOAD_STACKS = 8;
    /** Passes of getting nowhere before we accept they can't path there. */
    private static final int STUCK_PASSES = 8;
    /**
     * The longest trip worth asking a courier to walk.
     *
     * {@link TrapShops}' number and its reasoning: a pathfinder gives out
     * somewhere past forty blocks, so anything further is a walk target that
     * quietly does nothing while somebody stands in the road. Not shared as a
     * constant because the two are the same answer to the same question
     * rather than one thing -- a change to how shoppers cross town should not
     * silently change how deliveries do.
     */
    private static final int WALKABLE = 40;
    /**
     * Positions one pass will look at before giving up on finding work.
     *
     * ponytail: a flat cap rather than a block index. The scan runs outward
     * from the hand and stops at the first job of each kind, so the common
     * case is a few dozen lookups; this only bites on a big empty patch. If
     * a maxed crew ever shows up in a tick profile, cache the job list per
     * patch and invalidate on block update.
     */
    private static final int SCAN_BUDGET = 3000;

    /** Small enough that the game never counts them heavy enough to trample. */
    private static final double HAND_SCALE = 0.85;

    /**
     * Marks somebody working a patch.
     *
     * Read by {@link TrapHomes#out}, which is the whole point of it: a hand is
     * a resident with a job, and the register has to leave them where they are
     * rather than walk them home at dusk or hand them to a shop that wants a
     * customer. One person, one place.
     */
    public static final String HAND_TAG = "trapcraft_hand";

    /**
     * How far a hand will come from.
     *
     * The same 512 a club calls across and a casino draws a punter over, so
     * "in town" means one thing to every employer in the mod rather than three
     * slightly different things depending on who is asking.
     */
    private static final int HIRE_REACH = 512;

    /** A villager's own walking speed, handed back when somebody is let go. */
    private static final double RESIDENT_PACE = 0.5;

    /**
     * The ticket that keeps a patch awake, and how often it gets re-stamped.
     *
     * Not persisted, on purpose. A saved ticket is a promise somebody has to
     * keep -- some code path has to remember to take it back when the hand is
     * fired, or the boss logs off, or the world is loaded by a version of this
     * mod that no longer has a crew in it. An expiring one keeps itself: it
     * lasts fifteen seconds and something has to actively want it every five,
     * so "stop wanting it" IS the removal. Nothing to leak and nothing to
     * clean up.
     *
     * Deliberately never registered in {@code Registries.TICKET_TYPE} either.
     * Nothing serialises it, the registry is only consulted to print a name in
     * chunk debug output, and vanilla prints "[unregistered]" there rather
     * than falling over -- so registering it would only buy a nicer debug
     * line at the cost of a call that throws if registries are already frozen.
     *
     * Twenty seconds, and the number matters more than it looks.
     * {@code ChunkTicketType} is a RECORD, so two of them are equal when their
     * three fields match -- registered or not, named or not. The game asks
     * that question in two places: whether a new ticket is really the same one
     * (refresh it rather than stack it), and whether a ticket is the FORCED
     * type (and so belongs in the /forceload list). Land on a vanilla type's
     * exact numbers and this quietly becomes that type. Fifteen seconds would
     * have been PORTAL's expiry to the tick, saved from meaning it only by
     * PORTAL persisting and this not. Twenty is nobody's.
     */
    private static final ChunkTicketType TICKET = new ChunkTicketType(
            20 * 20, false, ChunkTicketType.Use.LOADING_AND_SIMULATION);
    private static final int TICKET_TICKS = 20 * 5;

    /**
     * Chunks each side of the patch to hold open.
     *
     * A ticket set at radius r puts the middle chunk at level {@code 33 - r},
     * and the game only ticks entities at 31 or better -- so the first two
     * rungs buy nothing but a loaded chunk, and everything past that is real
     * working room. Two, plus however many chunks the hand's patch actually
     * spans, is the smallest number that has the whole patch ticking.
     */
    private static int ticketRadius(int reachBlocks) {
        return 2 + (reachBlocks + 15) / 16;
    }

    /**
     * Jobs in a stretch before they stop for a breather.
     *
     * A hand that works every ten seconds without pause, all night, is not
     * somebody you hired -- it is a hopper with a face, and the whole point of
     * the crew being people is that people are worse than hoppers in
     * interesting ways. A shift and a breather is the cheapest way to say so
     * that does not turn into a stamina bar nobody asked for.
     *
     * How LONG the breather is comes from {@link TrapMath#crewBreak}, which is
     * a share of the shift rather than the flat forty-five seconds it used to
     * be. See that constant for why the flat number was a bug and not a
     * balance choice.
     */
    public static final int JOBS_PER_SHIFT = 12;

    /**
     * Whether they work nights.
     *
     * They do not, and this is the bigger half of the same nerf: a patch is
     * only worked for the daylight part of the cycle, so a hand's output is
     * roughly halved and the wage is not. Sleeping through the dark also means
     * a farm is quiet at exactly the hours a raid turns up, which is a real
     * consequence rather than a flavour note -- nobody is picking your field
     * while the pillagers are in it.
     */
    private static boolean onTheClock(ServerWorld world, Hand hand) {
        return hand.nights || world.isDay();
    }

    /**
     * What a hand costs once you have asked them to do nights.
     *
     * A premium ON TOP of the doubling that comes for free with the hours:
     * the wage clock only turns while somebody is working, so putting a hand
     * on nights already charges you twice as many packets an hour. The extra
     * quarter is what it costs to ask, and it is the whole reason this is a
     * decision rather than a switch everybody flips once and forgets.
     */
    public static final float NIGHT_RATE = 1.25f;

    // --- what a hand can be taught -------------------------------------------

    /**
     * One thing a hand can be sent to do.
     *
     * Picking is free and is why you hired them. Everything else is bought,
     * and every one of them puts the wage up -- so a fully trained hand is a
     * real payroll line rather than a switch you flip once and forget. That is
     * the same reason the wage exists at all.
     */
    public enum Job {
        // Declaration order IS priority order -- findWork walks values() and
        // takes the first thing it found. Your own crop first because it is
        // what the wage is really for, then racks because a bud left hanging
        // past peak loses a grade, and the ground work last because dirt can
        // wait and a ripe plant can't.
        PICK("Zbieranie", "minecraft:wheat", 0, 0,
                "Zbiera twoje dojrzałe rośliny do najbliższej skrzyni.",
                "dojrzała roślina na działce"),
        CURE("Suszenie", "trapcraft:drying_rack", 480, 8,
                "Ładuje suszarki i ściąga susz w idealnym momencie.",
                "suszarka i świeże szyszki w skrzyni"),
        REFINE("Rafinacja", "trapcraft:refiner", 1400, 20,
                "Obsługuje rafinerię i wyjmuje towar na szczycie czystości.",
                "rafineria, pasta i płonący proszek"),
        PRESS("Prasowanie", "trapcraft:leaf_press", 750, 12,
                "Przerabia liście koki na pastę, partia po partii.",
                "prasa i partia liści koki"),
        ROLL("Skręcanie", "minecraft:paper", 600, 10,
                "Zwija suszone szyszki z papierem w skręty.",
                "SUSZONE szyszki ORAZ papier w skrzyni"),
        FARM("Rolnictwo", "minecraft:carrot", 260, 5,
                "Zbiera pszenicę, marchew i inne dojrzałe uprawy.",
                "dojrzała uprawa jadalna na działce"),
        FEED("Nawożenie", "minecraft:bone_meal", 400, 6,
                "Sypie mączkę kostną na uprawy jadalne. Nigdy na twoje.",
                "mączka kostna w skrzyni"),
        SOW("Sianie", "minecraft:wheat_seeds", 340, 6,
                "Sadzi nasiona ze skrzyni w pustych grządkach.",
                "nasiona w skrzyni i pusta zaorana ziemia"),
        TILL("Oranie", "minecraft:iron_hoe", 220, 4,
                "Zamienia goły grunt przy wodzie w zaoraną ziemię.",
                "goły grunt przy wodzie"),
        // Last, and it belongs last twice over. Nothing in a drum spoils --
        // dirty money in the chest keeps and a finished load sits there all
        // week -- so it is genuinely the thing that can wait longest, which is
        // what this list is ordered by. It is also the newest, and the jobs a
        // hand knows are saved as a bitmask of ORDINALS: slotting this in next
        // to Refining where it reads best would have silently retaught every
        // hand on every server. New jobs go on the end.
        WASH("Pranie kasy", "trapcraft:laundry", 1200, 18,
                "Ładuje bęben brudną kasą i wyjmuje ją czystą.",
                "bęben pralniczy i brudna kasa w skrzyni"),
        // The only job with somewhere to be. Everything above happens inside
        // the patch and is found by walking squares. This one is found by
        // reading a list the boss wrote, which is why work() handles it before
        // findWork rather than through it. On the end for WASH's reason, and
        // the reason is now load-bearing twice over.
        DELIVER("Kurierka", "minecraft:chest_minecart", 900, 14,
                "Rozwozi towar ze skrzyni do twoich sklepów i straganów.",
                "trasa (mapa na tablicy) i towar na sprzedaż w skrzyni");

        private final String display;
        private final String iconId;
        private final int cost;
        private final int wage;
        private final String blurb;
        /**
         * What has to be there before this job can happen at all.
         *
         * On the enum because the answer to "why isn't my hand rolling"
         * should be on the board next to the job, not in a wiki, and
         * certainly not in a conversation with whoever wrote it.
         */
        private final String needs;

        Job(String display, String iconId, int cost, int wage, String blurb, String needs) {
            this.display = display;
            this.iconId = iconId;
            this.cost = cost;
            this.wage = wage;
            this.blurb = blurb;
            this.needs = needs;
        }

        public String needs() {
            return needs;
        }

        public String display() {
            return display;
        }

        public String iconId() {
            return iconId;
        }

        public int cost() {
            return cost;
        }

        public int wage() {
            return wage;
        }

        public String blurb() {
            return blurb;
        }

        /** Picking comes with the hire; the rest have to be taught. */
        public boolean free() {
            return cost == 0;
        }
    }

    /**
     * Pace and patch size, as ladders you climb one rung at a time.
     *
     * The wage numbers are cumulative, not per rung, because what a hand costs
     * should be readable off what they are rather than off their history.
     */
    /**
     * How often a job REALLY gets done at this rung, breather and all.
     *
     * Exists twice over. First because {@code ticks / 20} is integer division
     * and the top rung is thirty ticks, so the board and the handbook were
     * flatly saying "a job every 1s" for something that takes one and a half.
     * Then because even the corrected figure was the raw PASS interval, which
     * ignored the breather -- and the breather used to be a flat forty-five
     * seconds, so the top rung advertised 1.5s and delivered 5.25s.
     *
     * One number now, and it is the one you would measure with a stopwatch.
     * A book that quotes a figure it does not deliver is a book that has
     * started lying, which is the one thing this mod's guide is not allowed
     * to do.
     */
    public static String paceLabel(int level) {
        int ticks = PACE_TICKS[Math.max(0, Math.min(level, PACE_TICKS.length - 1))];
        float seconds = TrapMath.crewJobSeconds(ticks, JOBS_PER_SHIFT);
        return seconds == Math.rint(seconds)
                ? (int) seconds + "s" : String.format("%.1fs", seconds);
    }

    public static final int[] PACE_TICKS = {200, 120, 80, 50, 30};
    // Steeper than it was. A maxed hand is now a project you save for rather
    // than something you buy on the way past, which is what "each tier should
    // cost more" has to mean once the tiers actually matter.
    public static final int[] PACE_COST = {0, 200, 450, 1000, 2200};
    // Raised to follow the work. With the breather now a share of the shift, a
    // flat-out hand does nearly three times the jobs an hour it used to, and a
    // wage that had not moved would have made the top rung free money.
    public static final int[] PACE_WAGE = {0, 10, 26, 52, 96};
    public static final String[] PACE_NAME = {"Ślamazarnie", "Spokojnie", "Żwawo", "Szybko", "Na maksa"};

    public static final int[] REACH_BLOCKS = {12, 16, 20, 26};
    public static final int[] REACH_COST = {0, 200, 450, 900};
    public static final int[] REACH_WAGE = {0, 4, 9, 16};

    /** Kept for the guide book and anything that wants the starting patch. */
    public static final int REACH = REACH_BLOCKS[0];

    // --- one hire -------------------------------------------------------------

    /** Who they work for, where, what they've been taught, and how it's going. */
    private static final class Hand {
        final UUID boss;
        /** Not final: a hand the world lost gets a new body from the whip. */
        UUID mob;
        /**
         * Where this one works. Theirs alone, and it moves.
         *
         * Every hand has had its own spot since the first one was hired --
         * wherever you were standing when you took them on -- but there was no
         * way to change it afterwards, so a farm that outgrew its first corner
         * meant firing people and re-teaching them somewhere else.
         */
        String dimension;
        BlockPos patch;
        int pace;
        int reach;
        /**
         * The highest rung ever paid for, which is not always the one they are
         * on. A hand can be turned back down -- a slower hand costs less every
         * payday, and a farm that is ahead of itself does not need the top
         * rung -- and turning them up again to somewhere they have already
         * been is free. See TrapMath.crewRungCost.
         */
        int paceMax;
        int reachMax;
        /** Bit per {@link Job} ordinal. Blank on hire -- see SLOTS. */
        int jobs;
        /**
         * Everything they have ever been taught, which is a superset of what
         * is switched on. SLOTS caps what a hand can do at once, not what a
         * hand can know: switching one off to make room for another is how you
         * change your mind, and switching it back on costs nothing.
         */
        int owned;
        /** Passes since anything actually got done. Not saved -- it's a mood. */
        int idle;
        /** What they did last, so the other job they know gets a turn. */
        Job lastJob;
        /** Working nights. Costs more and never stops. */
        boolean nights;
        /**
         * Where a courier takes things, in the order they were added.
         *
         * Tills and market stalls, always the boss's own and always in the
         * hand's own dimension -- a handcart does not go through a portal, and
         * the two ends of a run have to be loaded at once for the goods to
         * move at all.
         *
         * Positions rather than a Shop or Stall reference, because both of
         * those are objects {@link TrapShops} and {@link TrapStalls} own and
         * rebuild on load. A till somebody broke is a stop that quietly finds
         * nothing, which is exactly what it should be.
         */
        final List<BlockPos> route = new ArrayList<>();
        /** Which stop is next. Round-robin, so a route is a round. */
        int stop;
        /** Jobs done since the last breather. */
        int worked;
        /** Server tick they are back on the clock. */
        int restUntil;
        /**
         * Ticks of actual working time since the last wage packet.
         *
         * The clock only turns while their boss is logged in, the sun is up
         * and there is a body on the patch, so a packet always buys the same
         * amount of work whatever else is going on.
         */
        int onClock;
        /** Packets in a row that bounced. See GRACE_PACKETS. */
        int missed;
        /** What has gone unpaid, for the notice. */
        int owed;
        /** Jobs done and emeralds taken, ever. The board divides them. */
        int done;
        int paid;
        /** Bed they have been using, so they go back to the same one. */
        BlockPos bed;
        /**
         * Where the chest was last time. Not saved either.
         *
         * Finding it is a scan of up to fourteen thousand positions and the
         * answer almost never changes, so it is worth one hash lookup a pass
         * to check the remembered one is still a container before doing that
         * again. Somebody who moves their chest costs themselves one pass.
         */
        BlockPos box;

        Hand(UUID boss, UUID mob, String dimension, BlockPos patch) {
            this.boss = boss;
            this.mob = mob;
            this.dimension = dimension;
            this.patch = patch;
        }

        boolean can(Job job) {
            return (jobs & (1 << job.ordinal())) != 0;
        }

        /** Paid for, whether or not it is switched on right now. */
        boolean owns(Job job) {
            return (owned & (1 << job.ordinal())) != 0;
        }

        void teach(Job job) {
            jobs |= 1 << job.ordinal();
            owned |= 1 << job.ordinal();
        }

        /** Switch off, keeping the teaching. Nothing paid for is ever lost. */
        void forget(Job job) {
            jobs &= ~(1 << job.ordinal());
        }

        int taught() {
            return Integer.bitCount(jobs);
        }

        boolean full() {
            return taught() >= SLOTS;
        }

        int interval() {
            return PACE_TICKS[pace];
        }

        int reachBlocks() {
            return REACH_BLOCKS[reach];
        }

        int wage() {
            int total = WAGE + PACE_WAGE[pace] + REACH_WAGE[reach];
            for (Job job : Job.values()) {
                if (can(job)) {
                    total += job.wage();
                }
            }
            return nights ? Math.round(total * NIGHT_RATE) : total;
        }

        /**
         * Top of both ladders and both slots paid for -- what was BOUGHT, not
         * what is switched on. Somebody who owns the lot and has turned half
         * of it off to save wages has still bought the lot.
         */
        boolean maxed() {
            return paceMax >= PACE_TICKS.length - 1
                    && reachMax >= REACH_BLOCKS.length - 1
                    && Integer.bitCount(owned) >= SLOTS;
        }
    }

    private static final List<Hand> CREW = new ArrayList<>();
    private static Path saveFile;

    private TrapCrew() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapCrew::load);
        registerCommand();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int tick = server.getTicks();
            if (tick % TICKET_TICKS == 0) {
                keepAwake(server);
            }
            // Per hand rather than one global beat: pace is bought per hand, so
            // a hand somebody paid 1400e to speed up has to actually run faster
            // than the one next to it.
            for (Hand hand : CREW) {
                if (tick % hand.interval() == 0) {
                    work(server, hand);
                }
            }
            if (tick % CLOCK_TICKS == 0) {
                clock(server);
                shiftBell(server);
            }
        });
    }

    /**
     * Re-stamp the ticket on every patch whose boss is logged in.
     *
     * Re-adding an identical ticket does not stack another one -- the game
     * finds the one already there and pushes its expiry back out -- so this is
     * a hash lookup a hand every five seconds, and a patch that stops being
     * wanted simply stops being mentioned.
     */
    private static void keepAwake(MinecraftServer server) {
        for (Hand hand : CREW) {
            if (server.getPlayerManager().getPlayer(hand.boss) == null) {
                continue;
            }
            ServerWorld world = worldOf(server, hand);
            if (world != null) {
                world.getChunkManager().addTicket(TICKET, new ChunkPos(hand.patch),
                        ticketRadius(hand.reachBlocks()));
                // And the shops on their round, or a courier walks to a
                // counter that does not exist yet and comes home with the
                // load. Two chunks is the counter and its back room; a stop
                // is a building, not a patch.
                for (BlockPos stop : hand.route) {
                    world.getChunkManager().addTicket(TICKET, new ChunkPos(stop), 2);
                }
            }
        }
    }

    /** Is this villager on somebody's payroll? Asked before anybody evicts one. */
    public static boolean isHand(UUID id) {
        for (Hand hand : CREW) {
            if (hand.mob.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** How many hands this player is carrying. */
    public static int sizeOf(ServerPlayerEntity boss) {
        return (int) CREW.stream().filter(hand -> hand.boss.equals(boss.getUuid())).count();
    }

    /**
     * Places bought past the free five, per boss.
     *
     * Keyed by player rather than hung off a Hand, because it has to outlive
     * every hand there is: fire the lot and the room you paid for is still
     * yours. Hanging it on a hand would quietly refund nobody and delete the
     * purchase the moment the last one walked.
     */
    private static final Map<UUID, Integer> PLACES = new HashMap<>();

    /** How many places past the free five this player has bought. */
    public static int placesOf(ServerPlayerEntity boss) {
        return PLACES.getOrDefault(boss.getUuid(), 0);
    }

    /** How many hands this player may have at once, bought places included. */
    public static int capOf(ServerPlayerEntity boss) {
        return TrapMath.crewCap(FREE_HANDS, placesOf(boss), PLACE_COST.length);
    }

    /** What one more place would cost, or 0 when there are none left to sell. */
    public static int placeCost(ServerPlayerEntity boss) {
        return TrapMath.crewPlaceCost(PLACE_COST, placesOf(boss));
    }

    /**
     * Buy one more place on the books.
     *
     * Sells the ROOM and nothing else: the hire fee and the wage behind it are
     * still ahead of you. Deliberately not one button that also hires -- that
     * would put the cheap half in front of the expensive half and hide the
     * wage behind the permit, and the wage is the entire decision.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String buyPlace(ServerPlayerEntity boss) {
        int bought = placesOf(boss);
        int cost = TrapMath.crewPlaceCost(PLACE_COST, bought);
        if (cost == 0) {
            return MAX_HANDS + " osób to absolutne maksimum. Więcej miejsc nie ma.";
        }
        if (TrapMarket.wealthOf(boss) < cost) {
            return "Kolejne miejsce kosztuje " + cost + "e.";
        }
        // Same route as the hire fee and the wages: the town is the other side
        // of every crew transaction, so a permit that minted itself would be
        // the one piece of crew money the market never sees.
        payTheTown(boss, cost);
        TrapLedger.record(boss, TrapLedger.Source.CREW, -cost);
        PLACES.put(boss.getUuid(), bought + 1);
        savePlaces();

        ServerWorld world = boss.getWorld();
        world.playSound(null, boss.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES,
                SoundCategory.NEUTRAL, 0.9F, 0.8F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, boss.getX(),
                boss.getY() + 1.2, boss.getZ(), 12, 0.4, 0.4, 0.4, 0.02);
        boss.sendMessage(Text.literal("Miejsce wykupione. ")
                .formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("Ekipa może mieć teraz "
                        + (FREE_HANDS + bought + 1) + " osób. Samo miejsce nikogo "
                        + "nie zatrudnia -- najem i pensja dochodzą osobno.")
                        .formatted(Formatting.GRAY)), false);
        return null;
    }

    /** What this player's whole payroll comes to per packet. */
    public static int payrollOf(ServerPlayerEntity boss) {
        return CREW.stream().filter(hand -> hand.boss.equals(boss.getUuid()))
                .mapToInt(Hand::wage).sum();
    }

    // --- the screen's view of a hand ------------------------------------------
    //
    // The GUI needs to read and change hands without holding one, because a
    // Hand is mutable state this class owns and handing one out would mean two
    // places that can put a crew member in an impossible condition.

    /**
     * One row of the crew screen.
     *
     * {@code done} and {@code paid} are here so the board can print what a job
     * has actually cost rather than what the wage table implies. That single
     * division is the answer to "I don't think they're working their wage":
     * it is either a number you are happy with or it is not, and either way
     * nobody has to guess.
     */
    public record Card(int index, int pace, int reach, int paceMax, int reachMax,
                       int reachBlocks, int wage,
                       String tempo, boolean present, List<Job> taught, List<Job> owned,
                       int done, int paid, int missed, int owed,
                       String dimension, int x, int y, int z,
                       String chest, List<Job> starved, boolean nights,
                       List<String> round, int roadSeconds) {
        /** Where they work, short enough for a tooltip. */
        public String spot() {
            return x + " " + y + " " + z;
        }
        /** Emeralds per job so far, or -1 before they have done anything. */
        public float perJob() {
            return done <= 0 ? -1 : (float) paid / done;
        }

        /**
         * What a job would cost if they never had to walk to one.
         *
         * The board prints both, and the gap between them is the honest
         * answer to "why is my hand slower than the tin says": the pace rung
         * is how often they ACT, and an act that is spent walking to the far
         * corner of a big patch is still an act. A hand costing three times
         * par is a hand with a layout problem, not a wage problem.
         */
        public float parJob() {
            return wage * TrapMath.crewJobSeconds(PACE_TICKS[pace], JOBS_PER_SHIFT) / 300f;
        }
    }

    public static List<Card> cardsFor(ServerPlayerEntity boss) {
        List<Card> out = new ArrayList<>();
        for (int i = 0; i < CREW.size(); i++) {
            Hand hand = CREW.get(i);
            if (!hand.boss.equals(boss.getUuid())) {
                continue;
            }
            List<Job> taught = new ArrayList<>();
            List<Job> owned = new ArrayList<>();
            for (Job job : Job.values()) {
                if (hand.can(job)) {
                    taught.add(job);
                }
                if (hand.owns(job)) {
                    owned.add(job);
                }
            }
            // Read the chest the hand actually uses -- the nearest container
            // to its SPOT, which is the single most misunderstood thing about
            // the crew. Somebody with paper and buds in the wrong chest was
            // doing everything right and getting nothing.
            ServerWorld world = worldOf(boss.getServer(), hand);
            net.minecraft.inventory.Inventory box = world == null ? null
                    : nearestBox(world, hand);
            Supplies stock = suppliesOf(box);
            List<Job> starved = new ArrayList<>();
            for (Job job : taught) {
                if (!backed(job, stock)) {
                    starved.add(job);
                }
            }
            // A courier with nowhere to go is starved in exactly the sense the
            // board already means it: taught, paid for, and unable to do a
            // thing. It cannot come out of backed() because the answer is
            // about the ROUND rather than about the chest.
            if (hand.can(Job.DELIVER) && hand.route.isEmpty()) {
                starved.add(Job.DELIVER);
            }
            out.add(new Card(i, hand.pace, hand.reach, hand.paceMax, hand.reachMax,
                    hand.reachBlocks(), hand.wage(),
                    paceLabel(hand.pace), find(boss.getServer(), hand) != null, taught, owned,
                    hand.done, hand.paid, hand.missed, hand.owed, hand.dimension,
                    hand.patch.getX(), hand.patch.getY(), hand.patch.getZ(),
                    box == null || hand.box == null ? null
                            : hand.box.getX() + " " + hand.box.getY() + " " + hand.box.getZ(),
                    starved, hand.nights, roundOf(world, hand), roadOf(world, hand)));
        }
        return out;
    }

    /**
     * The round as the board should print it: a name, or why the stop is dead.
     *
     * Resolved rather than remembered, because a saved position is only a
     * position -- the shop it pointed at may have been broken, sold, or had
     * its last chest taken out from under it, and all three are things a
     * player wants to read off the board rather than deduce from a courier
     * who has stopped.
     */
    private static List<String> roundOf(ServerWorld world, Hand hand) {
        List<String> out = new ArrayList<>();
        for (BlockPos stop : hand.route) {
            Drop drop = world == null ? null : dropAt(world, hand.boss, stop);
            out.add((drop == null ? "?? " : drop.name() + (drop.mine() ? "  " : " (obcy)  "))
                    + stop.getX() + " " + stop.getY() + " " + stop.getZ());
        }
        return out;
    }

    /** Seconds the longest stop on the round costs, there and back. */
    private static int roadOf(ServerWorld world, Hand hand) {
        int worst = 0;
        for (BlockPos stop : hand.route) {
            worst = Math.max(worst, TrapMath.crewRoadTicks(
                    Math.sqrt(stop.getSquaredDistance(hand.patch)), hand.interval()));
        }
        return worst / 20;
    }

    /**
     * Buy the next rung of something, or teach a job.
     *
     * One method for all three because they are one decision -- "spend on this
     * hand" -- and three near-identical ones would drift the moment somebody
     * added a fourth thing to buy.
     *
     * @param job null to buy pace or reach, named by {@code pace}
     * @return why it didn't happen, or null if it did
     */
    public static String buy(ServerPlayerEntity boss, int index, Job job, boolean pace) {
        if (index < 0 || index >= CREW.size()) {
            return "Tej osoby nie ma już na liście.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "To nie jest twój człowiek.";
        }

        int cost;
        String bought;
        // Whether this is a thing they have already been sold, which is NOT
        // the same as costing nothing: Picking is free the first time too.
        boolean again;
        if (job != null) {
            if (hand.can(job)) {
                return "On już to potrafi.";
            }
            if (hand.full()) {
                return "Jedna osoba uniesie dwa zawody. Wyłącz jeden albo zatrudnij kogoś jeszcze.";
            }
            again = hand.owns(job);
            cost = again ? 0 : job.cost();
            bought = job.display();
        } else if (pace) {
            if (hand.pace >= PACE_TICKS.length - 1) {
                return "Szybciej już nie potrafi.";
            }
            again = hand.pace + 1 <= hand.paceMax;
            cost = TrapMath.crewRungCost(PACE_COST, hand.pace + 1, hand.paceMax);
            bought = PACE_NAME[hand.pace + 1];
        } else {
            if (hand.reach >= REACH_BLOCKS.length - 1) {
                return "Większego zasięgu nikt nie ogarnie.";
            }
            again = hand.reach + 1 <= hand.reachMax;
            cost = TrapMath.crewRungCost(REACH_COST, hand.reach + 1, hand.reachMax);
            bought = REACH_BLOCKS[hand.reach + 1] + " bloków zasięgu";
        }

        if (TrapMarket.wealthOf(boss) < cost) {
            return "To kosztuje " + cost + "e, a tyle nie masz.";
        }
        // Tuition, and somebody is being paid it. See payTheTown. Nothing
        // changes hands when they are only going back to a rung you already
        // bought -- the town was paid for that one the first time.
        if (cost > 0) {
            payTheTown(boss, cost);
            TrapLedger.record(boss, TrapLedger.Source.CREW, -cost);
        }
        if (job != null) {
            hand.teach(job);
        } else if (pace) {
            hand.paceMax = Math.max(hand.paceMax, ++hand.pace);
        } else {
            hand.reachMax = Math.max(hand.reachMax, ++hand.reach);
        }
        save();

        VillagerEntity mob = find(boss.getServer(), hand);
        if (mob != null) {
            equip(mob, hand);
            ServerWorld world = (ServerWorld) mob.getWorld();
            world.playSound(null, mob.getBlockPos(), SoundEvents.ENTITY_VILLAGER_CELEBRATE,
                    SoundCategory.NEUTRAL, 0.9F, 1.0F);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + 1.4,
                    mob.getZ(), 12, 0.35, 0.35, 0.35, 0.02);
        }
        boss.sendMessage(Text.literal(again ? "Z powrotem: " : "Nauczono: ")
                .formatted(Formatting.GREEN)
                .append(Text.literal(bought).formatted(Formatting.WHITE))
                .append(Text.literal((again ? ", za darmo -- już kupione. Pensja teraz "
                        : ". Pensja teraz ") + hand.wage() + "e.")
                        .formatted(Formatting.GRAY)), false);
        if (hand.maxed()) {
            TrapAwards.grant(boss, "foreman");
        }
        return null;
    }

    /**
     * Turn a rung DOWN. Free, refunds nothing, and remembers the peak.
     *
     * The other half of the ladder. A hand bought up to the top rung during a
     * build costs 96e a packet forever afterwards, and the only way out of
     * that used to be firing them and buying the whole ladder again -- so the
     * top rung was a trap and people stopped climbing. Now the wage follows
     * the rung they are ON, and the rung they are on is a dial.
     *
     * @param pace true for tempo, false for reach
     * @return why it didn't happen, or null if it did
     */
    public static String drop(ServerPlayerEntity boss, int index, boolean pace) {
        if (index < 0 || index >= CREW.size()) {
            return "Tej osoby nie ma już na liście.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "To nie jest twój człowiek.";
        }
        if ((pace ? hand.pace : hand.reach) <= 0) {
            return pace ? "Wolniej się nie da." : "Mniejszego zasięgu nie ma.";
        }
        String now;
        if (pace) {
            hand.pace--;
            now = PACE_NAME[hand.pace];
        } else {
            hand.reach--;
            now = REACH_BLOCKS[hand.reach] + " bloków zasięgu";
        }
        save();

        VillagerEntity mob = find(boss.getServer(), hand);
        if (mob != null) {
            equip(mob, hand);
        }
        boss.sendMessage(Text.literal("Zwolniono: ").formatted(Formatting.GRAY)
                .append(Text.literal(now).formatted(Formatting.WHITE))
                .append(Text.literal(". Pensja teraz " + hand.wage()
                        + "e. Powrót w górę za darmo.").formatted(Formatting.GRAY)), false);
        return null;
    }

    /**
     * Switch a job off to free the slot. Free, and it stays taught.
     *
     * Has to exist, because with only two slots a misclick would otherwise be
     * permanent and the board would be a minefield. No refund and no loss
     * either: what you paid bought the teaching, the teaching happened, and
     * switching it back on later costs nothing.
     */
    public static String forget(ServerPlayerEntity boss, int index, Job job) {
        if (index < 0 || index >= CREW.size()) {
            return "Tej osoby nie ma już na liście.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "To nie jest twój człowiek.";
        }
        if (!hand.can(job)) {
            return "On nigdy tego nie umiał.";
        }
        hand.forget(job);
        save();
        VillagerEntity off = find(boss.getServer(), hand);
        if (off != null) {
            equip(off, hand);
        }
        boss.sendMessage(Text.literal("Wyłączono: ").formatted(Formatting.GRAY)
                .append(Text.literal(job.display()).formatted(Formatting.WHITE))
                .append(Text.literal(". Pensja teraz " + hand.wage()
                        + "e. Włączysz z powrotem za darmo.").formatted(Formatting.GRAY)), false);
        return null;
    }

    // --- hiring and firing ----------------------------------------------------

    /**
     * Put a body on the ground at a spot, ready to be somebody's hand.
     *
     * Shared by hiring and by the whip, because the second one only works if
     * the replacement is identical to the original in every way that matters.
     * A replacement that forgot to be a NITWIT would quietly take up a
     * profession and start trading, months after anybody remembers why every
     * line of this mattered.
     *
     * @return null if there is nowhere on that patch to stand -- every caller
     *         already has a "couldn't put anybody down there" to say, which is
     *         a better outcome than a hand suffocating in the wall the boss
     *         set their patch against.
     */
    /**
     * Somebody who already lives here, taken on at this patch.
     *
     * <h2>Where hands used to come from</h2>
     *
     * Nowhere. This method created a villager out of nothing, which made a
     * crew the last phantom left in the mod. The shoppers were conjured
     * traders once and the punters were strangers who appeared at the door;
     * both were replaced by the town supplying its own, and both times that is
     * what made the town real. A farm employing six people who were not from
     * anywhere was the same fiction, and a bigger one -- a crew is the largest
     * standing workforce a player ever has.
     *
     * <h2>What that buys</h2>
     *
     * A labour market, which is what "build houses" has been missing an answer
     * to. Housing paid rent and moved a number in {@code /city}; it never once
     * decided whether you could DO anything. Now the town's population is the
     * supply of hands, one person can only be in one place -- see
     * {@link TrapHomes#out} -- and a farm out of reach of anybody's house is a
     * farm you work yourself.
     *
     * Reach is {@link #HIRE_REACH}, the same 512 a club and a casino call
     * across, so "who is in town" means the same thing to every employer.
     *
     * <h2>Not spawned, not repainted</h2>
     *
     * They are already in the world and already a NITWIT -- {@link TrapHomes}
     * makes them one for exactly the reason this method used to, so a villager
     * with a workstation nearby never starts trading. Nothing here creates a
     * body; it moves one and puts a name on it. The name is theirs with the
     * job appended, in the {@code  ·  } shape the shops and the clubs use, so
     * {@link #release} can hand it back when they are let go.
     */
    private static VillagerEntity put(ServerWorld world, BlockPos patch, float yaw) {
        BlockPos stand = TrapSpawn.near(world, patch.up());
        if (stand == null) {
            return null;
        }
        VillagerEntity mob = TrapHomes.freeResident(world, patch, HIRE_REACH);
        if (mob == null) {
            return null;
        }
        mob.addCommandTag(HAND_TAG);
        mob.refreshPositionAndAngles(stand, yaw, 0.0F);
        mob.setPersistent();
        mob.setAiDisabled(false);
        mob.wakeUp();
        mob.setCustomName(Text.literal(plainName(mob) + "  ·  robotnik")
                .formatted(Formatting.YELLOW));
        mob.setCustomNameVisible(true);
        return mob;
    }

    /** What somebody is called with any job title taken back off. */
    private static String plainName(VillagerEntity body) {
        if (body.getCustomName() == null) {
            return "Ktoś";
        }
        String shown = body.getCustomName().getString();
        int cut = shown.indexOf("  ·  ");
        return cut < 0 ? shown : shown.substring(0, cut);
    }

    /**
     * Let somebody go, and give them back everything the job did to them.
     *
     * All four of these matter and the first three are invisible until they
     * are wrong. A hand keeps {@link #HAND_TAG}, so a released one that kept
     * it would be a person no shop, casino or club could ever call on again --
     * a townsperson permanently deleted from the labour force by having once
     * had a job. The SCALE is {@link #HAND_SCALE}, so a town that hires and
     * fires would slowly fill with small people. The speed is whatever their
     * pace was bought up to, so the neighbours would end up sprinting. And the
     * name still says robotnik.
     *
     * Put home rather than left standing in the field, for {@link TrapHomes}'
     * reason: the walk back from a farm is one a villager will not finish.
     */
    private static void release(VillagerEntity mob) {
        mob.removeCommandTag(HAND_TAG);
        // Whatever they were mid-delivery with. Both ways out of the job come
        // through here -- sacked, and walked out over wages -- and neither had
        // any reason to think about a bag until couriers existed. A townsperson
        // who kept it would be a stack of your goods permanently inside
        // somebody who is now just a neighbour, invisible and unrecoverable.
        // On the floor, because that is where a person who has finished
        // carrying your things puts them down.
        net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
        for (int slot = 0; slot < bag.size(); slot++) {
            ItemStack held = bag.getStack(slot);
            if (!held.isEmpty()) {
                mob.dropStack((ServerWorld) mob.getWorld(), held.copy());
                bag.setStack(slot, ItemStack.EMPTY);
            }
        }
        var scale = mob.getAttributeInstance(EntityAttributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(1.0);
        }
        var speed = mob.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(RESIDENT_PACE);
        }
        mob.setCustomName(Text.literal(plainName(mob)).formatted(Formatting.AQUA));
        if (mob.getWorld() instanceof ServerWorld world) {
            TrapHomes.putHome(world, mob);
        }
    }

    /**
     * Put a live hand back on a square, unless that square would kill them.
     *
     * Four places haul a hand about -- the whip, moving the patch, dragging a
     * stray home, unsticking one from behind a fence -- and all four used
     * X.up() flat, which teleports into whatever happens to be there. Leaving
     * them where they are is always the better failure: a hand stood in the
     * wrong field is one you can see and whip again.
     *
     * @return whether they moved
     */
    private static boolean haul(ServerWorld world, VillagerEntity mob, BlockPos to) {
        BlockPos stand = TrapSpawn.near(world, to.up());
        if (stand == null) {
            return false;
        }
        mob.refreshPositionAndAngles(stand, mob.getYaw(), 0.0F);
        mob.getNavigation().stop();
        return true;
    }

    /**
     * Get them back on the patch, whatever went wrong.
     *
     * Two failures wear the same face from across a field -- somebody who has
     * wandered behind a wall, and somebody a zombie got in the night -- and
     * until now the second one was permanent AND invisible: the hand stayed on
     * the books forever, took no wage, did no work, and held one of the five
     * places. One button covers both, because from where the player is stood
     * they are the same complaint.
     *
     * Free, and no cooldown. It exists to undo the game going wrong, and
     * charging for that would make a bug into a tax.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String whip(ServerPlayerEntity boss, int index) {
        if (index < 0 || index >= CREW.size()) {
            return "Tej osoby nie ma już na liście.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "To nie jest twój człowiek.";
        }
        ServerWorld world = worldOf(boss.getServer(), hand);
        if (world == null) {
            return "Ta działka jest w świecie, którego już nie ma.";
        }

        ChunkPos home = new ChunkPos(hand.patch);
        VillagerEntity mob = find(boss.getServer(), hand);

        // "Not found" only means "dead" once the game is actually keeping
        // entities alive out there. Adding a ticket schedules a load; it does
        // not finish one, so deciding on the spot would put a second villager
        // down next to a perfectly good first one, forever. canSpawnEntitiesAt
        // is the entity manager's own "are the entities in this chunk live",
        // misleading name and all -- ask it, and if the answer is no, hold the
        // patch open and let them try again in a moment.
        if (mob == null && !world.canSpawnEntitiesAt(home)) {
            world.getChunkManager().addTicket(TICKET, home, ticketRadius(hand.reachBlocks()));
            world.getChunk(home.x, home.z);
            return "Działka się jeszcze wczytuje. Spróbuj za sekundę.";
        }
        world.getChunkManager().addTicket(TICKET, home, ticketRadius(hand.reachBlocks()));

        boolean fresh = mob == null;
        if (fresh) {
            mob = put(world, hand.patch, boss.getYaw());
            if (mob == null) {
                return "Nie da się tam nikogo postawić.";
            }
            hand.mob = mob.getUuid();
            save();
        }

        if (!haul(world, mob, hand.patch)) {
            return "Na tej działce nie ma na czym stanąć. Przenieś ją tam, gdzie jest podłoże.";
        }
        if (mob.isSleeping()) {
            mob.wakeUp();
        }
        equip(mob, hand);
        // Straight back on the clock: a break they are serving in a hole
        // somewhere is not a break anybody asked for.
        hand.restUntil = 0;
        hand.worked = 0;
        hand.idle = 0;

        world.playSound(null, hand.patch, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                SoundCategory.NEUTRAL, 1.0F, 0.6F);
        world.playSound(null, hand.patch, SoundEvents.ENTITY_VILLAGER_NO,
                SoundCategory.NEUTRAL, 0.8F, 1.0F);
        world.spawnParticles(ParticleTypes.CRIT, hand.patch.getX() + 0.5,
                hand.patch.getY() + 1.2, hand.patch.getZ() + 0.5, 18, 0.4, 0.4, 0.4, 0.15);

        boss.sendMessage(fresh
                ? Text.literal("Tamten przepadł. ").formatted(Formatting.YELLOW)
                        .append(Text.literal("Na działce stoi ktoś nowy i umie "
                                + "wszystko, za co zapłaciłeś.")
                                .formatted(Formatting.GRAY))
                : Text.literal("Wrócił na działkę.").formatted(Formatting.GREEN), false);
        return null;
    }

    /**
     * Send one of them somewhere else to work.
     *
     * The patch is the whole of a hand's world -- what they harvest, how far
     * they will wander, which chest they fill, where the whip drops them and
     * which chunks stay awake for them. Moving it is therefore one action and
     * not five, and it takes the body with it: a hand whose spot moved but who
     * stayed put would spend the next minute walking and look broken.
     *
     * Across dimensions it is a new body on the same books. A villager cannot
     * be handed through a portal without a lot of ceremony, and everything
     * that matters about a hand -- the training, the pace, the wage, the
     * ledger -- lives on this side anyway.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String move(ServerPlayerEntity boss, int index, BlockPos to) {
        if (index < 0 || index >= CREW.size()) {
            return "Tej osoby nie ma już na liście.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "To nie jest twój człowiek.";
        }
        ServerWorld world = boss.getWorld();
        String dimension = world.getRegistryKey().getValue().toString();
        BlockPos spot = to.toImmutable();
        if (spot.equals(hand.patch) && dimension.equals(hand.dimension)) {
            return "On już tam pracuje.";
        }

        VillagerEntity mob = find(boss.getServer(), hand);
        boolean moved = dimension.equals(hand.dimension);
        if (!moved) {
            if (mob != null) {
                mob.discard();
            }
            mob = put(world, spot, boss.getYaw());
            if (mob == null) {
                return "Nie da się tam nikogo postawić.";
            }
            hand.mob = mob.getUuid();
        }

        hand.dimension = dimension;
        hand.patch = spot;
        // The old bed is somebody else's problem now, and the box they were
        // filling is probably three hundred blocks away. Both are found again
        // on the next pass; remembering either would be remembering a lie.
        hand.bed = null;
        hand.box = null;
        hand.idle = 0;
        hand.restUntil = 0;
        world.getChunkManager().addTicket(TICKET, new ChunkPos(spot),
                ticketRadius(hand.reachBlocks()));
        if (mob != null) {
            if (moved && haul(world, mob, spot)) {
                if (mob.isSleeping()) {
                    mob.wakeUp();
                }
            }
            equip(mob, hand);
        }
        // A hand nothing could find is a hand something ate. The spot still
        // moves -- it is the boss's decision, not the body's -- and the whip
        // will put somebody down on the new one.
        save();

        world.playSound(null, spot, SoundEvents.ENTITY_VILLAGER_YES,
                SoundCategory.NEUTRAL, 0.9F, 1.0F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, spot.getX() + 0.5,
                spot.getY() + 1.2, spot.getZ() + 0.5, 16, 0.4, 0.4, 0.4, 0.02);
        boss.sendMessage(Text.literal("Od teraz pracuje tutaj. ").formatted(Formatting.GREEN)
                .append(Text.literal(spot.getX() + " " + spot.getY() + " " + spot.getZ()
                        + (moved ? "" : ", przyszedł ktoś z miasta."))
                        .formatted(Formatting.GRAY)), false);
        return null;
    }

    /**
     * Take somebody on at this spot.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String hire(ServerPlayerEntity boss, BlockPos patch) {
        int cap = capOf(boss);
        if (sizeOf(boss) >= cap) {
            // Two different fulls. "You have run out of room" is a shop; "there
            // is no more room to be had" is a ceiling, and a player told the
            // second when the first is true never finds the button that fixes it.
            return cap >= MAX_HANDS
                    ? MAX_HANDS + " osób to absolutne maksimum dla jednej ekipy."
                    : cap + " miejsc zajętych. Dokup kolejne na tablicy /crew za "
                            + placeCost(boss) + "e.";
        }
        if (TrapMarket.wealthOf(boss) < HIRE_COST) {
            return "Zatrudnienie kosztuje " + HIRE_COST + "e.";
        }
        ServerWorld world = boss.getWorld();
        VillagerEntity mob = put(world, patch, boss.getYaw());
        if (mob == null) {
            // Two different nothings, and telling them apart is the difference
            // between a player who builds a house and a player who files a bug
            // report. Nobody free is a labour market doing its job; nowhere to
            // stand is a patch against a wall.
            return TrapSpawn.near(world, patch.up()) == null
                    ? "Nie ma tu gdzie stanąć. Odsuń się od ściany."
                    : TrapHomes.population() <= 0
                    ? "Nie ma tu nikogo do wynajęcia. Ludzie mieszkają w domach -- "
                            + "postaw skrzynkę pocztową i wynajmij komuś."
                    : "Wszyscy w okolicy są zajęci albo mieszkają za daleko. "
                            + "Robotnik przyjdzie z " + HIRE_REACH + " bloków, nie dalej.";
        }
        payTheTown(boss, HIRE_COST);
        // Capital, not wages, but the same line: the ledger's job is to answer
        // "what has this crew cost me", and a hire fee missing from it made
        // that question unanswerable from the one file built to answer it.
        TrapLedger.record(boss, TrapLedger.Source.CREW, -HIRE_COST);

        Hand hand = new Hand(boss.getUuid(), mob.getUuid(),
                world.getRegistryKey().getValue().toString(), patch);
        equip(mob, hand);
        CREW.add(hand);
        save();

        world.playSound(null, patch, SoundEvents.ENTITY_VILLAGER_YES,
                SoundCategory.NEUTRAL, 0.9F, 1.1F);
        TrapAwards.grant(boss, "crew");
        boss.sendMessage(Text.literal("Zatrudniony. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("Na razie nic nie umie. Jedna osoba "
                        + "uniesie dwa zawody, więc wybieraj z głową.")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("\n  /crew").formatted(Formatting.GREEN))
                .append(Text.literal("  szkoli go. Zbieranie jest darmowe.")
                        .formatted(Formatting.DARK_GRAY)),
                false);
        return null;
    }

    // --- plans ----------------------------------------------------------------

    /** The plan a walkout writes itself into. */
    public static final String WALKOUT = "walkout";

    /**
     * One hand as a shopping list: where it stood and everything it knew.
     *
     * The patch is saved with it, which is most of the value. A plan that
     * only remembered the training would put five strangers at your feet and
     * leave you to walk each of them back to a field; this one puts them
     * where they were.
     */
    public record PlanHand(String dimension, BlockPos patch, int pace, int reach, int jobs) {
        public int cost() {
            int total = HIRE_COST;
            for (int rung = 1; rung <= pace; rung++) {
                total += PACE_COST[rung];
            }
            for (int rung = 1; rung <= reach; rung++) {
                total += REACH_COST[rung];
            }
            for (Job job : Job.values()) {
                if ((jobs & (1 << job.ordinal())) != 0) {
                    total += job.cost();
                }
            }
            return total;
        }
    }

    /** A whole crew, named. */
    public record Plan(UUID owner, String name, List<PlanHand> hands) {
        public int cost() {
            return hands.stream().mapToInt(PlanHand::cost).sum();
        }

        public int wage() {
            int total = 0;
            for (PlanHand hand : hands) {
                total += WAGE + PACE_WAGE[hand.pace()] + REACH_WAGE[hand.reach()];
                for (Job job : Job.values()) {
                    if ((hand.jobs() & (1 << job.ordinal())) != 0) {
                        total += job.wage();
                    }
                }
            }
            return total;
        }
    }

    private static final List<Plan> PLANS = new ArrayList<>();
    private static Path planFile;

    public static List<Plan> plansOf(ServerPlayerEntity boss) {
        return PLANS.stream().filter(plan -> plan.owner().equals(boss.getUuid())).toList();
    }

    private static Plan planOf(UUID owner, String name) {
        for (Plan plan : PLANS) {
            if (plan.owner().equals(owner) && plan.name().equalsIgnoreCase(name)) {
                return plan;
            }
        }
        return null;
    }

    /**
     * Write the crew down under a name.
     *
     * @param onlyIfBigger used by the walkout snapshot. Hands leave one at a
     *                     time, so a plain overwrite would replace the whole
     *                     crew with whatever was left after the fourth one
     *                     went -- and the thing worth keeping is the crew as
     *                     it stood before any of them did.
     */
    private static Plan keepPlan(UUID owner, String name, boolean onlyIfBigger) {
        List<PlanHand> hands = new ArrayList<>();
        for (Hand hand : CREW) {
            if (hand.boss.equals(owner)) {
                // The PEAKS, not where they happen to be standing today. A
                // plan is what the crew cost to build, and a hand turned down
                // for the winter still cost every rung it was sold.
                hands.add(new PlanHand(hand.dimension, hand.patch,
                        hand.paceMax, hand.reachMax, hand.owned));
            }
        }
        if (hands.isEmpty()) {
            return null;
        }
        Plan already = planOf(owner, name);
        if (already != null) {
            if (onlyIfBigger && already.hands().size() >= hands.size()) {
                return already;
            }
            PLANS.remove(already);
        }
        Plan plan = new Plan(owner, name, hands);
        PLANS.add(plan);
        savePlans();
        return plan;
    }

    /** @return why it didn't happen, or null if it did */
    public static String save(ServerPlayerEntity boss, String name) {
        String wanted = name.trim();
        if (wanted.isEmpty() || wanted.contains("\t")) {
            return "Podaj nazwę.";
        }
        if (sizeOf(boss) == 0) {
            return "Nie masz kogo zapisać.";
        }
        Plan plan = keepPlan(boss.getUuid(), wanted, false);
        boss.sendMessage(Text.literal("Zapisano jako ").formatted(Formatting.GREEN)
                .append(Text.literal(plan.name()).formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal("  " + plan.hands().size()
                        + (plan.hands().size() == 1 ? " osoba, " : " osób, ")
                        + plan.cost() + "e za przywrócenie.").formatted(Formatting.GRAY)), false);
        return null;
    }

    public static String forget(ServerPlayerEntity boss, String name) {
        Plan plan = planOf(boss.getUuid(), name.trim());
        if (plan == null) {
            return "Nie ma zapisu o tej nazwie.";
        }
        PLANS.remove(plan);
        savePlans();
        boss.sendMessage(Text.literal("Zapis skasowany.").formatted(Formatting.GRAY), false);
        return null;
    }

    /**
     * Hire the whole plan back, at what it cost to build the first time.
     *
     * All or nothing on the money: half a crew for half the price would make
     * a plan a way to buy hands one at a time at a discount, and the point of
     * the list is recovery, not a shop.
     */
    public static String load(ServerPlayerEntity boss, String name) {
        Plan plan = planOf(boss.getUuid(), name.trim());
        if (plan == null) {
            return "Nie ma zapisu o tej nazwie. /crew plans pokazuje listę.";
        }
        int room = capOf(boss) - sizeOf(boss);
        if (plan.hands().size() > room) {
            return "Masz miejsce jeszcze na " + room + " osób, a ten zapis ma "
                    + plan.hands().size() + ".";
        }
        int cost = plan.cost();
        if (TrapMarket.wealthOf(boss) < cost) {
            return "Przywrócenie tej ekipy kosztuje " + cost + "e.";
        }
        MinecraftServer server = boss.getServer();
        for (PlanHand wanted : plan.hands()) {
            if (worldNamed(server, wanted.dimension()) == null) {
                return "Część tej ekipy pracowała w świecie, którego już nie ma.";
            }
        }

        payTheTown(boss, cost);
        TrapLedger.record(boss, TrapLedger.Source.CREW, -cost);
        int put = 0;
        for (PlanHand wanted : plan.hands()) {
            ServerWorld world = worldNamed(server, wanted.dimension());
            // The patch has to be awake before anybody can be stood on it.
            world.getChunkManager().addTicket(TICKET, new ChunkPos(wanted.patch()),
                    ticketRadius(REACH_BLOCKS[wanted.reach()]));
            world.getChunk(wanted.patch().getX() >> 4, wanted.patch().getZ() >> 4);
            VillagerEntity mob = put(world, wanted.patch(), boss.getYaw());
            if (mob == null) {
                continue;
            }
            Hand hand = new Hand(boss.getUuid(), mob.getUuid(), wanted.dimension(),
                    wanted.patch());
            hand.paceMax = hand.pace = clamp(wanted.pace(), PACE_TICKS.length);
            hand.reachMax = hand.reach = clamp(wanted.reach(), REACH_BLOCKS.length);
            hand.owned = own(wanted.jobs());
            hand.jobs = trim(wanted.jobs());
            equip(mob, hand);
            CREW.add(hand);
            put++;
            world.playSound(null, wanted.patch(), SoundEvents.ENTITY_VILLAGER_YES,
                    SoundCategory.NEUTRAL, 0.9F, 1.1F);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, wanted.patch().getX() + 0.5,
                    wanted.patch().getY() + 1.2, wanted.patch().getZ() + 0.5,
                    14, 0.4, 0.4, 0.4, 0.02);
        }
        save();
        boss.sendMessage(Text.literal("Ekipa wróciła. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal(put + (put == 1 ? " osoba" : " osób") + " na swoich starych "
                        + "działkach, wyszkolonych. Suma pensji: "
                        + payrollOf(boss) + "e.").formatted(Formatting.GRAY)), false);
        return null;
    }

    private static void listPlans(ServerPlayerEntity boss) {
        List<Plan> mine = plansOf(boss);
        if (mine.isEmpty()) {
            boss.sendMessage(Text.literal("Nic nie jest zapisane. ").formatted(Formatting.GRAY)
                    .append(Text.literal("/crew save <name>").formatted(Formatting.GREEN))
                    .append(Text.literal(" zapisuje twoją obecną ekipę.")
                            .formatted(Formatting.DARK_GRAY)), false);
            return;
        }
        boss.sendMessage(Text.literal("Zapisane ekipy").formatted(Formatting.GOLD, Formatting.BOLD),
                false);
        for (Plan plan : mine) {
            boss.sendMessage(Text.literal("  " + plan.name())
                    .formatted(Formatting.WHITE, Formatting.BOLD)
                    .styled(style -> style.withClickEvent(
                            new net.minecraft.text.ClickEvent.SuggestCommand(
                                    "/crew load " + plan.name())))
                    .append(Text.literal("  " + plan.hands().size()
                            + (plan.hands().size() == 1 ? " osoba" : " osób"))
                            .formatted(Formatting.GRAY))
                    .append(Text.literal("  " + plan.cost() + "e za przywrócenie")
                            .formatted(Formatting.GOLD))
                    .append(Text.literal("  potem " + plan.wage() + "e za wypłatę")
                            .formatted(Formatting.DARK_GRAY)), false);
        }
    }

    private static ServerWorld worldNamed(MinecraftServer server, String dimension) {
        if (server == null) {
            return null;
        }
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(dimension)) {
                return world;
            }
        }
        return null;
    }

    /**
     * Put a hand on nights, or take them off.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String nights(ServerPlayerEntity boss, int index) {
        if (index < 0 || index >= CREW.size()) {
            return "Tej osoby nie ma już na liście.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "To nie jest twój człowiek.";
        }
        hand.nights = !hand.nights;
        // Straight back on or off the clock: a hand asleep in a bed when you
        // put them on nights should get up, not finish the night first.
        hand.restUntil = 0;
        hand.worked = 0;
        save();
        VillagerEntity mob = find(boss.getServer(), hand);
        if (mob != null && hand.nights && mob.isSleeping()) {
            mob.wakeUp();
        }
        boss.sendMessage(hand.nights
                ? Text.literal("Nocna zmiana. ").formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(Text.literal("Pracuje po ciemku, licznik pensji "
                                + "nie staje. Teraz " + hand.wage() + "e za wypłatę.")
                                .formatted(Formatting.GRAY))
                : Text.literal("Tylko za dnia. ").formatted(Formatting.GREEN)
                        .append(Text.literal("Z powrotem " + hand.wage()
                                + "e, a noce znów są darmowe.").formatted(Formatting.GRAY)),
                false);
        return null;
    }

    /** Let somebody go. */
    public static String fire(ServerPlayerEntity boss, int index) {
        for (int i = CREW.size() - 1; i >= 0; i--) {
            Hand hand = CREW.get(i);
            if (!hand.boss.equals(boss.getUuid()) || (index >= 0 && i != index)) {
                continue;
            }
            VillagerEntity mob = find(boss.getServer(), hand);
            if (mob != null) {
                mob.getWorld().playSound(null, mob.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO,
                        SoundCategory.NEUTRAL, 0.9F, 0.8F);
                // Sent home, not discarded. A hand is somebody's tenant now,
                // and binning one is binning a townsperson -- the register
                // would stand a replacement up on their doorstep a few seconds
                // later, which is the same person arriving twice.
                release(mob);
            }
            CREW.remove(i);
            save();
            boss.sendMessage(Text.literal("Zwolniono ekipę. ").formatted(Formatting.GRAY)
                    .append(Text.literal("Wracają do domów i znów są do wzięcia.")
                            .formatted(Formatting.DARK_GRAY)), false);
            return null;
        }
        return "Nie masz nikogo zatrudnionego.";
    }

    /**
     * Everything about the villager that follows from what they've been taught.
     *
     * Re-applied on every work pass rather than only on hire, so an upgrade
     * bought while they're asleep in an unloaded chunk still takes hold the
     * moment somebody walks back into range, and so a hand from a save written
     * before any of this existed quietly acquires it.
     */
    private static void equip(VillagerEntity mob, Hand hand) {
        var scale = mob.getAttributeInstance(EntityAttributes.SCALE);
        if (scale != null && scale.getBaseValue() != HAND_SCALE) {
            scale.setBaseValue(HAND_SCALE);
        }
        var speed = mob.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        double wanted = 0.5 + 0.055 * hand.pace;
        if (speed != null && speed.getBaseValue() != wanted) {
            speed.setBaseValue(wanted);
        }
    }

    // --- the command ----------------------------------------------------------

    /**
     * /crew, /crew hire, /crew fire.
     *
     * A command rather than an item because hiring is about a PLACE -- where
     * you are standing is the patch they work -- and an item you right-click
     * on the ground would need a model, a recipe and a texture to say the same
     * thing a word already says. Bare /crew now opens the board, because a
     * chat readout can't be clicked and everything worth doing to a hand is a
     * purchase.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("crew")
                                .executes(context -> open(context.getSource()))
                                .then(net.minecraft.server.command.CommandManager.literal("hire")
                                        .executes(context -> {
                                            ServerPlayerEntity boss = context.getSource().getPlayer();
                                            if (boss == null) {
                                                return 0;
                                            }
                                            say(boss, hire(boss, boss.getBlockPos()));
                                            return 1;
                                        }))
                                .then(net.minecraft.server.command.CommandManager.literal("fire")
                                        .executes(context -> {
                                            ServerPlayerEntity boss = context.getSource().getPlayer();
                                            if (boss == null) {
                                                return 0;
                                            }
                                            say(boss, fire(boss, -1));
                                            return 1;
                                        }))
                                .then(net.minecraft.server.command.CommandManager.literal("plans")
                                        .executes(context -> {
                                            ServerPlayerEntity boss = context.getSource().getPlayer();
                                            if (boss == null) {
                                                return 0;
                                            }
                                            listPlans(boss);
                                            return 1;
                                        }))
                                .then(named("save", TrapCrew::save))
                                .then(named("load", TrapCrew::load))
                                .then(named("forget", TrapCrew::forget))));
    }

    /**
     * {@code /crew <word> <name...>}, three times over.
     *
     * greedyString rather than a single word, because "the big farm" is a
     * better name for a crew than "bigfarm" and there is no reason a list
     * nobody but its owner reads should be forced to look like a filename.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
            net.minecraft.server.command.ServerCommandSource> named(
            String word, java.util.function.BiFunction<ServerPlayerEntity, String, String> what) {
        return net.minecraft.server.command.CommandManager.literal(word)
                .then(net.minecraft.server.command.CommandManager.argument("name",
                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayerEntity boss = context.getSource().getPlayer();
                            if (boss == null) {
                                return 0;
                            }
                            say(boss, what.apply(boss, com.mojang.brigadier.arguments
                                    .StringArgumentType.getString(context, "name")));
                            return 1;
                        }));
    }

    private static void say(ServerPlayerEntity boss, String no) {
        if (no != null) {
            boss.sendMessage(Text.literal(no).formatted(Formatting.GRAY), false);
        }
    }

    private static int open(net.minecraft.server.command.ServerCommandSource source) {
        ServerPlayerEntity boss = source.getPlayer();
        if (boss == null) {
            return 0;
        }
        boss.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, inventory, p) -> new CrewScreenHandler(syncId, inventory),
                Text.literal("Ekipa").formatted(Formatting.GOLD)));
        return 1;
    }

    // --- the work -------------------------------------------------------------

    /**
     * One pass of one hand: keep them home, find the nearest job, do it.
     *
     * Still one job per pass, not a sweep. A hand that cleared a field in one
     * tick would make the whole growing half of the mod a formality; one that
     * brings in a plant at a time is somebody helping rather than a machine
     * replacing you. Pace is what you buy if you want more, and pace costs
     * wages.
     */
    private static void work(MinecraftServer server, Hand hand) {
        VillagerEntity mob = find(server, hand);
        if (mob == null) {
            return;
        }
        ServerWorld world = (ServerWorld) mob.getWorld();
        equip(mob, hand);

        if (!onTheClock(world, hand)) {
            knockOff(world, mob, hand);
            return;
        }
        if (mob.isSleeping()) {
            mob.wakeUp();
        }
        if (server.getTicks() < hand.restUntil) {
            return;   // on a break; they are stood about on purpose
        }

        // Before the leash, and it has to be: a courier standing in your shop
        // is by definition off their patch, and the stray check below would
        // drag them home holding the delivery. The bag says which of the two
        // they are, so there is no state here that can disagree with the world.
        // Not gated on knowing the job: somebody who is HOLDING your goods has
        // to be able to put them down, and a boss who unteaches Kurierka
        // mid-run would otherwise have buried a load inside a villager. It
        // also quietly tidies up after the vanilla habit of picking bread and
        // carrots up off the floor -- those go in the chest, where they help.
        if (carrying(mob)) {
            dropOff(world, mob, hand);
            return;
        }

        // Keep them on the job. The old one wandered out of the field and came
        // back minutes later because nothing ever told it not to: a villager
        // with no walk target goes and finds one. Handing it a target every
        // pass both moves it and starves the stroll tasks, which only run when
        // that memory is empty.
        int reach = hand.reachBlocks();
        double strayed = Math.sqrt(mob.getBlockPos().getSquaredDistance(hand.patch));
        if (strayed > reach + 12) {
            // Far enough that walking back is its own five-minute errand, and
            // usually means they got pushed, boated or shut out by a door.
            if (haul(world, mob, hand.patch)) {
                world.spawnParticles(ParticleTypes.POOF, hand.patch.getX() + 0.5,
                        hand.patch.getY() + 1.0, hand.patch.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
            }
            return;
        }
        if (strayed > reach) {
            walkTo(mob, hand.patch, hand);
            return;
        }

        net.minecraft.inventory.Inventory box = nearestBox(world, hand);
        // A delivery is a job with no square to stand on, so it cannot come
        // out of findWork. lastJob is set the same way, which is what keeps a
        // courier who also picks from spending the whole day on the road.
        if (hand.can(Job.DELIVER) && hand.lastJob != Job.DELIVER
                && setOff(world, mob, hand, box)) {
            hand.lastJob = Job.DELIVER;
            hand.idle = 0;
            return;
        }
        BlockPos job = findWork(world, mob, hand, box);
        if (job == null) {
            hand.idle = 0;
            // Nothing to do, so stand about in the middle of the patch rather
            // than at whatever edge they finished on -- which is also what
            // stops them drifting towards the door over a quiet hour.
            if (strayed > 3) {
                walkTo(mob, hand.patch, hand);
            }
            return;
        }
        if (!mob.getBlockPos().isWithinDistance(job, ARM)) {
            walkTo(mob, job, hand);
            if (++hand.idle >= STUCK_PASSES) {
                // Eight passes and no closer: there is a fence, a wall or a
                // drop between them and it. Rather than stall on that square
                // forever, put them next to it and let them get on.
                hand.idle = 0;
                haul(world, mob, job);
            }
            return;
        }
        hand.idle = 0;
        doWork(world, mob, hand, job, box);
        hand.done++;
        if (++hand.worked >= JOBS_PER_SHIFT) {
            hand.worked = 0;
            // A share of the shift, not a flat minute. See TrapMath.
            hand.restUntil = server.getTicks()
                    + TrapMath.crewBreak(hand.interval(), JOBS_PER_SHIFT);
            world.spawnParticles(ParticleTypes.SPLASH, mob.getX(), mob.getEyeY(), mob.getZ(),
                    5, 0.2, 0.1, 0.2, 0.01);
        }
    }

    /**
     * Night. Find a bed inside the patch and get in it, or stand at the spot.
     *
     * The bed is looked for once and then remembered, because the search is
     * the same outward scan everything else here uses and running it every
     * night for every hand is a lot of block reads for a question whose answer
     * changes about never. If somebody takes the bed away they get the scan
     * again the next night.
     */
    private static void knockOff(ServerWorld world, VillagerEntity mob, Hand hand) {
        hand.worked = 0;
        if (hand.bed != null && !(world.getBlockState(hand.bed).getBlock()
                instanceof net.minecraft.block.BedBlock)) {
            hand.bed = null;
        }
        if (hand.bed == null) {
            for (BlockPos pos : BlockPos.iterateOutwards(hand.patch, hand.reachBlocks(), 4,
                    hand.reachBlocks())) {
                if (world.getBlockState(pos).getBlock() instanceof net.minecraft.block.BedBlock) {
                    hand.bed = pos.toImmutable();
                    break;
                }
            }
        }
        if (hand.bed == null) {
            // Nowhere to sleep, so they just stop where the work is. Somebody
            // who wants their hand tucked up can build them a room, which is
            // a nicer reason to build one than decoration.
            walkTo(mob, hand.patch, hand);
            return;
        }
        if (mob.getBlockPos().isWithinDistance(hand.bed, 2.0)) {
            if (!mob.isSleeping()) {
                mob.sleep(hand.bed);
            }
        } else {
            walkTo(mob, hand.bed, hand);
        }
    }

    private static void walkTo(VillagerEntity mob, BlockPos target, Hand hand) {
        float speed = 0.55F + 0.08F * hand.pace;
        mob.getBrain().remember(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, speed, 1));
        mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(target));
    }

    // --- the bell -------------------------------------------------------------

    /**
     * Bosses already told the sun is up, and how many of theirs were out in it.
     *
     * Not saved. A restart at noon means the next thing a boss hears is the
     * crew turning in at dusk, which is a missed line rather than a wrong one
     * -- and the alternative is a file that has to be kept honest against
     * firing, walkouts and dimensions that are not there any more.
     */
    private static final Map<UUID, Integer> ON_SHIFT = new HashMap<>();

    /**
     * Tell a boss when their crew starts and stops.
     *
     * They have downed tools at dusk and got up at dawn since the day they
     * were put on daylight only, and the only way to find that out was to walk
     * to the field and look. A hand you cannot see is a wage you forget you
     * are paying, so the two moments that change what it buys are the two
     * moments worth a line.
     *
     * Counted per boss, not per hand: five people knocking off together is one
     * event, and five chat lines is spam. Hiring or firing mid-shift only
     * moves the number quietly -- the bell is rung by the sun, not the payroll.
     *
     * A boss with hands in the Nether never hears it, which is right: fixed
     * time means {@link #onTheClock} is never true there and those hands have
     * never worked a minute.
     */
    private static void shiftBell(MinecraftServer server) {
        Map<UUID, Integer> up = new HashMap<>();
        for (Hand hand : CREW) {
            up.putIfAbsent(hand.boss, 0);
            ServerWorld world = worldOf(server, hand);
            if (world != null && onTheClock(world, hand)) {
                up.merge(hand.boss, 1, Integer::sum);
            }
        }
        // Who to tell, and what about, is in TrapMath so it can be checked
        // without a world. Sign says which way the shift went.
        for (Map.Entry<UUID, Integer> bell : TrapMath.shiftBells(ON_SHIFT, up).entrySet()) {
            ring(server, bell.getKey(), Math.abs(bell.getValue()), bell.getValue() > 0);
        }
    }

    /** One notice, to one player, and only if they are here to read it. */
    private static void ring(MinecraftServer server, UUID who, int hands, boolean dawn) {
        ServerPlayerEntity boss = server.getPlayerManager().getPlayer(who);
        if (boss == null) {
            return;   // the flag still flipped; they just missed the bell
        }
        // Text.empty() as the root on purpose: style inherits down a chain, so
        // hanging the gray half off a BOLD header would print the whole thing
        // bold. Siblings each keep their own.
        boss.sendMessage(Text.empty()
                .append(Text.literal(dawn ? "ZMIANA STARTUJE" : "KONIEC ZMIANY")
                        .formatted(dawn ? Formatting.GOLD : Formatting.BLUE, Formatting.BOLD))
                .append(Text.literal("  ·  ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(hands + (hands == 1 ? " osoba" : " osób"))
                        .formatted(Formatting.WHITE))
                .append(Text.literal(dawn ? " wyszła na działki" : " poszło spać")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("\n   " + (dawn
                        ? "Pensje lecą znowu aż do zmroku."
                        : "Do świtu nikt nic nie zbiera i nic nie płacisz."))
                        .formatted(Formatting.DARK_GRAY)), false);
        boss.playSoundToPlayer(dawn
                ? SoundEvents.BLOCK_NOTE_BLOCK_BELL.value()
                : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                SoundCategory.NEUTRAL, 0.35F, dawn ? 1.4F : 0.7F);
    }

    // --- finding something to do ----------------------------------------------

    /**
     * The nearest square that wants doing, best job first.
     *
     * Scanned outward from the HAND, not from the patch, which is the whole
     * difference between a worker and a tourist: the old one walked back to
     * the same corner every pass because that is where the search started.
     *
     * The first hit of each kind is remembered rather than the first hit
     * overall, so a rack that has finished curing still wins over a square of
     * dirt the hand happens to be standing on -- but the scan still stops the
     * moment it has an answer for the job that outranks everything.
     */
    private static BlockPos findWork(ServerWorld world, VillagerEntity mob, Hand hand,
                                     net.minecraft.inventory.Inventory box) {
        int reach = hand.reachBlocks();
        // Three of the jobs are things the CHEST has to agree to. Read once
        // here rather than at every square, and read at all because a hand
        // that walks to a plant it has no bone meal for does nothing for a
        // whole pass and looks exactly like a hand that is broken.
        Supplies stock = suppliesOf(box);

        Map<Job, BlockPos> found = new EnumMap<>(Job.class);
        int looked = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(mob.getBlockPos(), reach, 5, reach)) {
            // Something for every job they know, or enough dirt for one pass.
            // Used to stop the moment it found PICKING, which meant the second
            // job of a picker was never even LOOKED for.
            if (++looked > SCAN_BUDGET || found.size() >= hand.taught()) {
                break;
            }
            if (!within(pos, hand.patch, reach)) {
                continue;
            }
            Job job = jobAt(world, pos, hand, stock);
            if (job != null) {
                found.putIfAbsent(job, pos.toImmutable());
            }
        }
        // Two jobs a head means both should get turns. Strict priority starved
        // the lower one whenever the higher one had work: a hand taught Curing
        // and Rolling, stood next to a busy rack, cured forever and never
        // rolled a single joint -- which from outside is a job you paid 600e
        // for and never once saw done.
        //
        // So: prefer anything OTHER than what they did last, and fall back to
        // priority when that is all there is. Urgency still wins when it is
        // the only thing going.
        Job chosen = null;
        for (Job job : Job.values()) {
            if (found.containsKey(job) && job != hand.lastJob) {
                chosen = job;
                break;
            }
        }
        if (chosen == null) {
            for (Job job : Job.values()) {
                if (found.containsKey(job)) {
                    chosen = job;
                    break;
                }
            }
        }
        hand.lastJob = chosen;
        return chosen == null ? null : found.get(chosen);
    }

    private static boolean holds(net.minecraft.inventory.Inventory box, net.minecraft.item.Item want) {
        if (box == null) {
            return false;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            if (box.getStack(slot).isOf(want)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsSeed(net.minecraft.inventory.Inventory box) {
        if (box == null) {
            return false;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            if (box.getStack(slot).getItem() instanceof BlockItem seed
                    && seed.getBlock() instanceof CropBlock) {
                return true;
            }
        }
        return false;
    }

    /** A cured bud in the chest that there is also paper for, or null. */
    private static ItemStack rollable(net.minecraft.inventory.Inventory box) {
        if (box == null || !holds(box, Items.PAPER)) {
            return null;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (TrapContent.strainOfDriedBud(stack.getItem()) != null) {
                return stack;
            }
        }
        return null;
    }

    private static boolean holdsRawBud(net.minecraft.inventory.Inventory box) {
        if (box == null) {
            return false;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            if (TrapContent.strainOfRawBud(box.getStack(slot).getItem()) != null) {
                return true;
            }
        }
        return false;
    }

    /** What one chest can back, read once and shared with the board. */
    private static Supplies suppliesOf(net.minecraft.inventory.Inventory box) {
        int[] dirty = dirtyIn(box);
        return new Supplies(holds(box, Items.BONE_MEAL), holdsSeed(box),
                holdsRawBud(box),
                // Asked of the press, not worked out again here. See canLoad.
                LeafPressBlock.canLoad(box),
                holds(box, TrapContent.cocaPaste) && holds(box, Items.BLAZE_POWDER),
                rollable(box) != null,
                // Enough to actually START a drum, not merely some. One dirty
                // emerald in a barrel is under the minimum load, and a hand who
                // walked to the drum for it would stand there doing nothing
                // every pass, which looks exactly like a hand that is broken.
                dirty[0] * 9 + dirty[1] >= LaundryBlock.MIN_LOAD);
    }

    /**
     * Dirty money in the chest, as {blocks, loose}.
     *
     * Both denominations because both turn up: the black market pays out
     * packed, so a week's takings is a stack of blocks and a handful of change
     * rather than a hundred and forty loose emeralds.
     */
    private static int[] dirtyIn(net.minecraft.inventory.Inventory box) {
        if (box == null) {
            return new int[]{0, 0};
        }
        int blocks = 0;
        int loose = 0;
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (stack.isOf(TrapContent.dirtyEmerald)) {
                loose += stack.getCount();
            } else if (stack.isOf(TrapContent.dirtyEmeraldBlockItem)) {
                blocks += stack.getCount();
            }
        }
        return new int[]{blocks, loose};
    }

    /**
     * Is the chest holding what this job needs, right now?
     *
     * Only the jobs the CHEST backs. Picking, farmhand and tilling depend on
     * the ground rather than the box, and reporting "nothing to pick" from a
     * board would mean re-running the whole outward scan every time somebody
     * opened it.
     */
    private static boolean backed(Job job, Supplies stock) {
        return switch (job) {
            case ROLL -> stock.rolling();
            case CURE -> stock.rawBuds();
            case PRESS -> stock.leaves();
            case REFINE -> stock.paste();
            case FEED -> stock.boneMeal();
            case SOW -> stock.seeds();
            case WASH -> stock.dirty();
            // Handled by the board rather than here: what a courier needs is
            // a ROUND and something a shop would take, and neither is a
            // question about this chest alone. See cardsFor.
            default -> true;
        };
    }

    /** What the chest can back up this pass. */
    private record Supplies(boolean boneMeal, boolean seeds, boolean rawBuds,
                            boolean leaves, boolean paste, boolean rolling,
                            boolean dirty) {
    }

    /** Inside the box the hand was hired to work. */
    private static boolean within(BlockPos pos, BlockPos patch, int reach) {
        return Math.abs(pos.getX() - patch.getX()) <= reach
                && Math.abs(pos.getZ() - patch.getZ()) <= reach
                && Math.abs(pos.getY() - patch.getY()) <= 5;
    }

    /** What, if anything, this square is asking for. */
    private static Job jobAt(ServerWorld world, BlockPos pos, Hand hand, Supplies stock) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        if (hand.can(Job.ROLL) && stock.rolling()
                && world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory) {
            return Job.ROLL;
        }
        if (block instanceof DryingRackBlock && hand.can(Job.CURE)) {
            // Ready to come off, or empty with something to hang on it.
            // Anything mid-cure is left strictly alone -- pulling early costs
            // a grade, and a hand that did that would be worse than no hand.
            if (state.get(DryingRackBlock.OCCUPIED)) {
                return state.get(DryingRackBlock.DRYNESS) >= DryingRackBlock.READY_DRYNESS
                        ? Job.CURE : null;
            }
            return stock.rawBuds() ? Job.CURE : null;
        }
        if (block instanceof LeafPressBlock && hand.can(Job.PRESS)) {
            return state.get(LeafPressBlock.LOADED)
                    ? (state.get(LeafPressBlock.PROGRESS) >= LeafPressBlock.DONE ? Job.PRESS : null)
                    : (stock.leaves() ? Job.PRESS : null);
        }
        if (block instanceof RefinerBlock && hand.can(Job.REFINE)) {
            // Loaded runs are only ever pulled AT PEAK. That is the whole of
            // what fourteen hundred emeralds buys: a player has to stand there
            // and time it, and a hand who can do that reliably is worth more
            // than one who can dig.
            return state.get(RefinerBlock.RUNNING)
                    ? (state.get(RefinerBlock.PROGRESS) == RefinerBlock.PEAK ? Job.REFINE : null)
                    : (stock.paste() ? Job.REFINE : null);
        }
        if (block instanceof LaundryBlock && hand.can(Job.WASH)) {
            if (state.get(LaundryBlock.DONE)) {
                // Only when there is somebody to assess for it -- see launder.
                // Asked here rather than only there because a job that cannot
                // be done is worse than no job: the hand walks to the drum,
                // finds it can't, and burns the pass, over and over, looking
                // for all the world like a hand that is broken.
                return world.getServer().getPlayerManager().getPlayer(hand.boss) != null
                        ? Job.WASH : null;
            }
            // A drum already turning is left strictly alone. Tipping more in
            // reschedules the wash from the new total, so a hand who topped up
            // a running load once a pass would keep it going round forever and
            // never once pull it -- the drum equivalent of pulling a rack early.
            return state.get(LaundryBlock.LOAD) == 0 && stock.dirty() ? Job.WASH : null;
        }
        if (block instanceof CannabisCropBlock || block instanceof CocaCropBlock
                || block instanceof PoppyCropBlock) {
            return block instanceof CropBlock crop && crop.isMature(state) ? Job.PICK : null;
        }
        if (block instanceof CropBlock crop) {
            if (crop.isMature(state)) {
                return hand.can(Job.FARM) ? Job.FARM : null;
            }
            return hand.can(Job.FEED) && stock.boneMeal() ? Job.FEED : null;
        }
        if (block instanceof FarmlandBlock && world.getBlockState(pos.up()).isAir()) {
            return hand.can(Job.SOW) && stock.seeds() ? Job.SOW : null;
        }
        if (hand.can(Job.TILL) && tillable(world, pos, state)) {
            return Job.TILL;
        }
        return null;
    }

    /**
     * Ground worth turning over: bare earth, open sky above it, water in reach.
     *
     * The water clause is what stops a hired hand ploughing up your lawn. It
     * is also the same four blocks vanilla farmland uses to decide whether it
     * stays wet, so a hand only ever makes farmland that will actually work.
     */
    private static boolean tillable(ServerWorld world, BlockPos pos, BlockState state) {
        if (!state.isOf(Blocks.GRASS_BLOCK) && !state.isOf(Blocks.DIRT)
                && !state.isOf(Blocks.COARSE_DIRT) && !state.isOf(Blocks.ROOTED_DIRT)
                && !state.isOf(Blocks.DIRT_PATH)) {
            return false;
        }
        if (!world.getBlockState(pos.up()).isAir()) {
            return false;
        }
        for (BlockPos near : BlockPos.iterate(pos.add(-4, 0, -4), pos.add(4, 1, 4))) {
            if (world.getFluidState(near).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    // --- doing it -------------------------------------------------------------

    private static void doWork(ServerWorld world, VillagerEntity mob, Hand hand, BlockPos at,
                               net.minecraft.inventory.Inventory box) {
        BlockState state = world.getBlockState(at);
        Block block = state.getBlock();
        mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(at));

        if (block instanceof DryingRackBlock) {
            rack(world, hand, box, at, state);
            return;
        }
        if (block instanceof LeafPressBlock) {
            ItemStack paste = LeafPressBlock.take(state, world, at);
            if (!paste.isEmpty()) {
                stow(world, box, at, List.of(paste));
                cheer(world, at, SoundEvents.BLOCK_WET_GRASS_BREAK, 0.9F);
            } else if (box != null) {
                // The whole box, not the first stack in it -- which is the
                // same box the "is there work here" count reads. See the note
                // on LeafPressBlock.load: those two disagreeing is what left
                // a barrel of four-leaf stacks and a hand with nothing to do.
                LeafPressBlock.load(state, world, at, box);
            }
            return;
        }
        if (block instanceof RefinerBlock) {
            ItemStack powder = RefinerBlock.take(state, world, at);
            if (!powder.isEmpty()) {
                stow(world, box, at, List.of(powder));
                cheer(world, at, SoundEvents.BLOCK_BREWING_STAND_BREW, 1.0F);
            } else if (box != null) {
                final net.minecraft.inventory.Inventory chest = box;
                feed(box, TrapContent.cocaPaste,
                        paste -> RefinerBlock.load(state, world, at, paste, chest));
            }
            return;
        }
        if (block instanceof LaundryBlock) {
            launder(world, hand, box, at, state);
            return;
        }
        if (box != null && world.getBlockEntity(at) instanceof net.minecraft.inventory.Inventory
                && hand.can(Job.ROLL)) {
            roll(world, box, at);
            return;
        }
        if (block instanceof CannabisCropBlock || block instanceof CocaCropBlock
                || block instanceof PoppyCropBlock) {
            // Through the block's own harvest, not getDroppedStacks: breaking
            // one of these runs the loot table and returns a SEED. The buds
            // only come off a right-click, and a hand that broke the plant was
            // demolishing the farm and stashing seeds.
            //
            // Poppy was missing from both this list and jobAt's, and that
            // omission is the whole reason the line was dead: it is the only
            // crop in the mod a hired hand would not look at, on a server
            // where the money to hire hands is exactly what the players have.
            // The field is now theirs; the scoring table, the wash pot and the
            // acetylator are still yours, which is the half that was ever
            // interesting.
            List<ItemStack> picked = block instanceof CannabisCropBlock weed
                    ? weed.harvest(world, at, state)
                    : block instanceof CocaCropBlock coca
                    ? coca.harvest(world, at, state)
                    : ((PoppyCropBlock) block).harvest(world, at, state);
            stow(world, box, at, picked);
            cheer(world, at, SoundEvents.BLOCK_CROP_BREAK, 1.0F);
            return;
        }
        if (block instanceof CropBlock crop) {
            if (crop.isMature(state)) {
                // Back to age zero rather than broken, exactly like our own
                // crops: the hand harvests the field, it doesn't dismantle it.
                stow(world, box, at, Block.getDroppedStacks(state, world, at, null));
                world.setBlockState(at, crop.withAge(0));
                cheer(world, at, SoundEvents.BLOCK_CROP_BREAK, 1.0F);
            } else if (spend(box, Items.BONE_MEAL)) {
                // Never our own crops. Bone meal sets RUSHED on those and
                // costs a grade at harvest, so a hand doing this unasked would
                // quietly turn a Fire field into a Mids one -- which is why
                // jobAt only ever offers FEED for a plain CropBlock.
                crop.grow(world, world.random, at, state);
                cheer(world, at, SoundEvents.ITEM_BONE_MEAL_USE, 1.2F);
            }
            return;
        }
        if (block instanceof FarmlandBlock) {
            sow(world, box, at);
            return;
        }
        // Re-checked rather than assumed. Several seconds can pass between
        // spotting a square of dirt and standing on it, and turning whatever
        // somebody built there in the meantime into farmland is the one way
        // this could destroy something.
        if (tillable(world, at, state)) {
            world.setBlockState(at, Blocks.FARMLAND.getDefaultState());
            cheer(world, at, SoundEvents.ITEM_HOE_TILL, 1.0F);
        }
    }

    /**
     * A drum, from whichever end it is asking for.
     *
     * The clean money goes in the chest like everything else a hand makes. The
     * WASH itself does not: the office assesses a person, and the person here
     * is the boss, so a load pulled by a hand shows up against their name and
     * eats their cover exactly as if they had clicked the drum themselves.
     * That is the whole reason this is worth 1200e -- it automates the machine,
     * not the crime.
     *
     * A boss who is logged out has nobody to assess, so a finished drum is left
     * standing until they are back. A hand may still fill one and let it run;
     * they simply cannot cash it in behind their boss's back overnight, which
     * is the same rule wages already follow.
     */
    private static void launder(ServerWorld world, Hand hand,
                                net.minecraft.inventory.Inventory box, BlockPos at,
                                BlockState state) {
        if (state.get(LaundryBlock.DONE)) {
            ServerPlayerEntity boss = world.getServer().getPlayerManager().getPlayer(hand.boss);
            if (boss == null) {
                return;
            }
            LaundryBlock.Wash out = LaundryBlock.empty(world, at, state);
            // Paid out in BLOCKS, with the remainder loose. A drum pays in the
            // thousands and a stack of loose emeralds is sixty-four of them,
            // so a washer working overnight filled a double chest with cash
            // nine times faster than it had to and left a wall of identical
            // stacks to sort. The denomination costs nothing: the market
            // values a block at nine (TrapMarket.valueOf), the vault census
            // counts it, a wallet swallows it, and the drum's own INPUT is
            // already read in blocks-and-loose two lines down. This is a
            // denomination, not a conversion -- the same money either way.
            int[] cut = TrapMath.denominate(out.clean());
            List<ItemStack> money = new ArrayList<>();
            int blocks = cut[0];
            while (blocks > 0) {
                int lot = Math.min(blocks, Items.EMERALD_BLOCK.getMaxCount());
                money.add(new ItemStack(Items.EMERALD_BLOCK, lot));
                blocks -= lot;
            }
            if (cut[1] > 0) {
                money.add(new ItemStack(Items.EMERALD, cut[1]));
            }
            stow(world, box, at, money);
            TrapLaw.washed(boss, out.gross(), out.cut());
            cheer(world, at, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 1.4F);
            return;
        }
        if (box == null) {
            return;
        }
        // The whole load in one pass, not an emerald at a time: see jobAt for
        // why a drum is filled once and then left. Counted and taken in the
        // same breath so the two can't disagree about how much moved.
        int[] have = dirtyIn(box);
        int[] take = TrapMath.drumLoad(have[0], have[1],
                LaundryBlock.MAX_LOAD - state.get(LaundryBlock.LOAD));
        int going = take[0] * 9 + take[1];
        if (going < LaundryBlock.MIN_LOAD) {
            return;
        }
        pull(box, TrapContent.dirtyEmeraldBlockItem, take[0]);
        pull(box, TrapContent.dirtyEmerald, take[1]);
        LaundryBlock.start(world, at, state, going);
        cheer(world, at, SoundEvents.ITEM_BUCKET_FILL, 1.1F);
    }

    /** Take exactly this many of something out of the chest. */
    private static void pull(net.minecraft.inventory.Inventory box, net.minecraft.item.Item want,
                             int count) {
        for (int slot = 0; slot < box.size() && count > 0; slot++) {
            ItemStack stack = box.getStack(slot);
            if (!stack.isOf(want)) {
                continue;
            }
            int lot = Math.min(stack.getCount(), count);
            stack.decrement(lot);
            count -= lot;
        }
        box.markDirty();
    }

    /** Hand a machine the first matching stack out of the chest. */
    private static void feed(net.minecraft.inventory.Inventory box, net.minecraft.item.Item want,
                             java.util.function.Predicate<ItemStack> machine) {
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (stack.isOf(want) && machine.test(stack)) {
                box.markDirty();
                return;
            }
        }
    }

    /**
     * One cured bud and one sheet of paper become one joint.
     *
     * The grade rides across, because the joint is the same product in a
     * different shape -- a hand that quietly turned Fire into Mids by rolling
     * it would be a way of losing money by delegating.
     */
    private static void roll(ServerWorld world, net.minecraft.inventory.Inventory box, BlockPos at) {
        ItemStack bud = null;
        ItemStack paper = null;
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (bud == null && TrapContent.strainOfDriedBud(stack.getItem()) != null) {
                bud = stack;
            } else if (paper == null && stack.isOf(Items.PAPER)) {
                paper = stack;
            }
        }
        if (bud == null || paper == null) {
            return;
        }
        Strain strain = TrapContent.strainOfDriedBud(bud.getItem());
        ItemStack joint = TrapComponents.apply(
                new ItemStack(TrapContent.joint(strain), 1), TrapComponents.get(bud));
        bud.decrement(1);
        paper.decrement(1);
        box.markDirty();
        stow(world, box, at, List.of(joint));
        cheer(world, at, SoundEvents.ITEM_CROP_PLANT, 1.4F);
    }

    /** Pull a finished rack into the chest, or hang a fresh bud on an empty one. */
    private static void rack(ServerWorld world, Hand hand, net.minecraft.inventory.Inventory box,
                             BlockPos at, BlockState state) {
        if (state.get(DryingRackBlock.OCCUPIED)) {
            ItemStack out = DryingRackBlock.take(state, world, at);
            if (!out.isEmpty()) {
                stow(world, box, at, List.of(out));
                cheer(world, at, SoundEvents.ITEM_CROP_PLANT, 1.2F);
            }
            return;
        }
        if (box == null) {
            return;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (TrapContent.strainOfRawBud(stack.getItem()) == null) {
                continue;
            }
            if (DryingRackBlock.hang(state, world, at, stack)) {
                box.markDirty();
                cheer(world, at, SoundEvents.BLOCK_GRASS_PLACE, 1.0F);
            }
            return;
        }
    }

    /** Put a seed from the chest into empty farmland. */
    private static void sow(ServerWorld world, net.minecraft.inventory.Inventory box, BlockPos at) {
        if (box == null) {
            return;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            // Every seed in the game is a BlockItem for the crop it plants --
            // vanilla, ours and Farmer's Delight alike -- so this needs no
            // list of ids and works for mods nobody has heard of.
            if (!(stack.getItem() instanceof BlockItem seed)
                    || !(seed.getBlock() instanceof CropBlock crop)) {
                continue;
            }
            if (!crop.getDefaultState().canPlaceAt(world, at.up())) {
                continue;
            }
            world.setBlockState(at.up(), crop.getDefaultState());
            stack.decrement(1);
            box.markDirty();
            cheer(world, at.up(), SoundEvents.ITEM_CROP_PLANT, 1.0F);
            return;
        }
    }

    /** Take one of something out of the chest. False if there wasn't one. */
    private static boolean spend(net.minecraft.inventory.Inventory box, net.minecraft.item.Item want) {
        if (box == null) {
            return false;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (stack.isOf(want)) {
                stack.decrement(1);
                box.markDirty();
                return true;
            }
        }
        return false;
    }

    private static void stow(ServerWorld world, net.minecraft.inventory.Inventory box,
                             BlockPos at, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            // What store() couldn't fit, and nothing else -- it takes as much
            // as there is room for and leaves the rest in `drop`. A full chest
            // still means crops on the floor; a chest with room no longer does.
            if ((box == null || !store(box, drop)) && !drop.isEmpty()) {
                Block.dropStack(world, at, drop);
            }
        }
    }

    private static void cheer(ServerWorld world, BlockPos at, net.minecraft.sound.SoundEvent sound,
                              float pitch) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
        world.playSound(null, at, sound, SoundCategory.NEUTRAL, 0.6F, pitch);
    }

    /** The closest container to the patch, or null if there isn't one. */
    private static net.minecraft.inventory.Inventory nearestBox(ServerWorld world, Hand hand) {
        // Through TrapBoxes, not getBlockEntity: that hands back one half of a
        // double chest, so a hand filled 27 slots, called the chest full, and
        // threw the rest of the harvest on the floor.
        if (hand.box != null) {
            net.minecraft.inventory.Inventory known = TrapBoxes.at(world, hand.box);
            // Re-checked, not trusted: a box remembered before this rule
            // existed can be a furnace, and a saved answer is exactly how a
            // bad one survives the fix.
            if (known != null && known.size() >= BOX_SLOTS) {
                return known;
            }
        }
        for (BlockPos pos : BlockPos.iterateOutwards(hand.patch, hand.reachBlocks(), 4,
                hand.reachBlocks())) {
            // Cheap test to find the candidate, then resolve it once.
            // TrapBoxes.at costs an entity lookup and this scan is thousands
            // of squares wide, so nothing gets resolved until it has already
            // proved it is a container at all.
            if (!(world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory)) {
                continue;
            }
            // Measured AFTER resolving, never before: getBlockEntity hands
            // back one half of a double chest, so a size rule read off it is
            // a rule about the wrong number. A machine standing nearer than
            // the chest costs one lookup and is then skipped -- a handful per
            // pass, not the thousands the cheap test exists to avoid.
            net.minecraft.inventory.Inventory box = TrapBoxes.at(world, pos);
            if (box == null || box.size() < BOX_SLOTS) {
                continue;
            }
            hand.box = pos.toImmutable();
            return box;
        }
        hand.box = null;
        return null;
    }

    /**
     * Put a drop away, across as many slots as it takes.
     *
     * The other half of the leaf press bug, and the one every hand hits: this
     * used to be all-or-nothing into a SINGLE slot. A barrel with twenty
     * stacks of sixty and no empty slot has room for eighty more, and this
     * would look at each slot in turn, find nowhere the whole drop fit, and
     * throw the lot on the floor -- next to a chest that was visibly not full.
     * Everything a hand produces comes through here: picked crops, cured buds,
     * paste, powder, rolled joints. So it was every worker, quietly littering.
     *
     * Partial stacks are topped up before an empty slot is taken, because
     * doing it the other way round fragments a barrel into ten half stacks of
     * wheat and then reports it full.
     *
     * `drop` is decremented to whatever would not go in, so the caller drops
     * that and only that.
     */
    private static boolean store(net.minecraft.inventory.Inventory box, ItemStack drop) {
        for (int slot = 0; slot < box.size() && !drop.isEmpty(); slot++) {
            ItemStack there = box.getStack(slot);
            if (there.isEmpty() || !ItemStack.areItemsAndComponentsEqual(there, drop)) {
                continue;
            }
            int room = Math.min(there.getMaxCount() - there.getCount(), drop.getCount());
            if (room > 0) {
                there.increment(room);
                drop.decrement(room);
                box.markDirty();
            }
        }
        for (int slot = 0; slot < box.size() && !drop.isEmpty(); slot++) {
            // isValid as well as empty. BOX_SLOTS keeps hands out of machines
            // in the first place; this is for the container that is big enough
            // to qualify and still refuses things in particular slots, which
            // several mods' cabinets and drawers do. Writing anyway is how an
            // item goes into a slot that then throws it back out.
            if (!box.getStack(slot).isEmpty() || !box.isValid(slot, drop)) {
                continue;
            }
            int room = Math.min(drop.getMaxCount(), drop.getCount());
            ItemStack put = drop.copy();
            put.setCount(room);
            box.setStack(slot, put);
            drop.decrement(room);
            box.markDirty();
        }
        return drop.isEmpty();
    }

    // --- the round ------------------------------------------------------------
    //
    // The only job with somewhere else to be. Everything above happens on the
    // patch and is found by walking squares; a delivery is found by reading a
    // list the boss wrote, so it is handled here rather than through findWork,
    // and it takes two passes instead of one -- load and set off, then hand
    // over -- because a courier who appeared at the counter and was home again
    // inside the same tick would be a hopper with a face. You are supposed to
    // see them standing in your shop.

    /**
     * One stop on a round: the counter, its stock, and whose it is.
     *
     * {@code mine} is the whole of the difference between the two kinds of
     * stop. Your own shop is a shelf you are filling and a till you are
     * emptying -- the goods stay yours the whole way. Somebody else's is a
     * SALE: their till pays for what arrives at {@link TrapMath#WHOLESALE} of
     * what they will charge for it, the money rides home in the same bag, and
     * their takings are none of your business.
     */
    private record Drop(BlockPos counter, List<net.minecraft.inventory.Inventory> stock,
                        String name, TrapShops.Shop shop, TrapStalls.Stall stall,
                        boolean mine) {
        /**
         * Would this counter actually put that on sale?
         *
         * The whole reason a courier is not a pipe. Without it the first run
         * takes the bone meal, the seed corn and the spare hoe to market and
         * the farm quietly stops working -- and the player's complaint is
         * about the crew rather than about a chest they filled themselves.
         *
         * A shop takes anything it has a price for, weed and coca included --
         * over a counter that is legal, declared and taxed. A stall only ever
         * dealt in catalogue lines, so that is all one may be sent.
         */
        boolean takes(MinecraftServer server, ItemStack stack) {
            return shop != null
                    ? TrapShops.lineFor(server, stack, shop) != null
                    : ShopStock.matching(stack) != null;
        }
    }

    /**
     * Resolve one saved position into a stop, or nobody.
     *
     * Owner-checked every time rather than at the moment it was added. A shop
     * can be sold, a stall can be broken and rebuilt by somebody else, and a
     * courier who kept delivering to it on last week's authority would be
     * stocking a stranger's shelves out of your barrel.
     */
    private static Drop dropAt(ServerWorld world, UUID boss, BlockPos pos) {
        TrapShops.Shop shop = TrapShops.shopAt(world, pos);
        if (shop != null) {
            List<net.minecraft.inventory.Inventory> stock = TrapShops.stockOf(world, shop);
            return stock.isEmpty() ? null : new Drop(pos, stock, shop.name(), shop, null,
                    shop.owner().equals(boss));
        }
        TrapStalls.Stall stall = TrapStalls.at(world, pos);
        if (stall == null) {
            return null;
        }
        net.minecraft.inventory.Inventory under = TrapStalls.stockOf(world, stall);
        return under == null ? null : new Drop(pos, List.of(under),
                "stragan " + stall.ownerName(), null, stall, stall.owner().equals(boss));
    }

    /** The takings sat in this stop's till, or nothing if it is not yours. */
    private static int tillOf(Drop drop) {
        if (!drop.mine()) {
            return 0;
        }
        return drop.shop() != null ? drop.shop().till()
                : drop.stall() != null ? drop.stall().till() : 0;
    }

    /**
     * What a neighbour's counter will pay for one stack that just arrived.
     *
     * Priced off their OWN shelf price, markup and all, so a shop that charges
     * a fortune also pays its suppliers well -- the markup dial finally has a
     * second thing hanging off it. Zero for your own shop: you cannot sell
     * yourself your own tomatoes.
     */
    private static int worth(MinecraftServer server, Drop drop, ItemStack stack) {
        if (drop.mine() || stack.isEmpty()) {
            return 0;
        }
        if (drop.shop() != null) {
            TrapShops.Line line = TrapShops.lineFor(server, stack, drop.shop());
            return line == null ? 0
                    : TrapMath.wholesale(line.price(), stack.getCount() / line.count());
        }
        ShopStock.Entry entry = ShopStock.matching(stack);
        return entry == null ? 0 : TrapMath.wholesale(
                TrapMath.stallPrice(TrapMarket.buyPrice(server, entry)),
                stack.getCount() / entry.count());
    }

    /**
     * Is this one out on a run? The bag is the answer, and the only record.
     *
     * A villager's own eight slots, which vanilla saves and loads with the
     * body. Nothing to serialise, nothing to leak, and a courier caught by a
     * restart halfway to town comes back still holding the goods -- where a
     * flag in our own file would have come back holding nothing and insisting
     * otherwise. It also means a courier a zombie gets DROPS the load, which
     * is the right amount of consequence for sending one out at dusk.
     */
    private static boolean carrying(VillagerEntity mob) {
        net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
        for (int slot = 0; slot < bag.size(); slot++) {
            if (!bag.getStack(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fill the bag from the chest and put them on the road.
     *
     * @return true if a run actually started
     */
    private static boolean setOff(ServerWorld world, VillagerEntity mob, Hand hand,
                                  net.minecraft.inventory.Inventory box) {
        if (box == null || hand.route.isEmpty()) {
            return false;
        }
        MinecraftServer server = world.getServer();
        // Round-robin from wherever they finished, and every stop tried before
        // giving up: a shop that wants nothing this minute must not be able to
        // block the two behind it in the list.
        for (int turn = 0; turn < hand.route.size(); turn++) {
            int at = (hand.stop + turn) % hand.route.size();
            Drop drop = dropAt(world, hand.boss, hand.route.get(at));
            if (drop == null) {
                continue;
            }
            // The chunk at the far end has to be awake for there to be a
            // container in it at all. Stamped before anything is picked up,
            // and re-stamped by keepAwake for as long as the stop is listed.
            world.getChunkManager().addTicket(TICKET, new ChunkPos(drop.counter()), 2);
            // Somewhere to put them down, BEFORE the bag is filled. A shop
            // with no room to stand in gets no delivery -- the same answer
            // TrapShops gives a shopper -- and finding that out afterwards
            // would leave a courier holding goods and walking at a wall.
            if (TrapSpawn.near(world, drop.counter().up()) == null) {
                continue;
            }
            // A neighbour's counter buys with its takings, so an empty till
            // is a wasted trip -- loaded up, walked across town and carried
            // all the way back. Skipped here rather than discovered there.
            if (!drop.mine() && (drop.shop() != null ? drop.shop().till()
                    : drop.stall().till()) <= 0) {
                continue;
            }
            net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
            int taken = 0;
            boolean full = false;
            for (int slot = 0; slot < box.size() && taken < LOAD_STACKS && !full; slot++) {
                ItemStack stack = box.getStack(slot);
                if (stack.isEmpty() || !drop.takes(server, stack)) {
                    continue;
                }
                // addStack takes what it CAN and hands back the rest, so the
                // count is the only honest measure of what moved. Emptying the
                // box slot on anything short of a clean insert duplicated
                // whatever the bag had no room for.
                ItemStack left = bag.addStack(stack.copy());
                int moved = stack.getCount() - left.getCount();
                if (moved <= 0) {
                    break;
                }
                stack.decrement(moved);
                if (stack.isEmpty()) {
                    box.setStack(slot, ItemStack.EMPTY);
                }
                box.markDirty();
                taken++;
                full = !left.isEmpty();
            }
            if (taken == 0 && tillOf(drop) <= 0) {
                continue;   // nothing to bring and nothing to fetch
            }
            hand.stop = at;
            haul(world, mob, drop.counter());
            walkTo(mob, drop.counter(), hand);
            cheer(world, hand.patch, SoundEvents.ENTITY_VILLAGER_YES, 1.1F);
            return true;
        }
        return false;
    }

    /**
     * They are stood at the counter holding it. Put it on the shelf.
     *
     * Anything the shop turns out not to have room for goes home in the bag
     * and back into the chest on the next pass, rather than on the floor of
     * somebody's supermarket.
     */
    private static void dropOff(ServerWorld world, VillagerEntity mob, Hand hand) {
        MinecraftServer server = world.getServer();
        BlockPos where = hand.stop < hand.route.size() ? hand.route.get(hand.stop) : null;
        Drop drop = where == null ? null : dropAt(world, hand.boss, where);
        if (drop == null) {
            // The shop was broken, sold or emptied while they were walking to
            // it. Take it home; the chest they came from is the one place the
            // goods are certainly still wanted.
            goHome(world, mob, hand);
            return;
        }
        if (!mob.getBlockPos().isWithinDistance(drop.counter(), ARM)) {
            // Past what a pathfinder will plan there is nothing to watch and
            // nothing that works, so they are stood at the door instead --
            // TrapShops' trade, for TrapShops' reason. This is the path a
            // load with leftovers takes to the next shop on the round.
            if (!mob.getBlockPos().isWithinDistance(drop.counter(), WALKABLE)
                    && !haul(world, mob, drop.counter())) {
                goHome(world, mob, hand);
                return;
            }
            walkTo(mob, drop.counter(), hand);
            if (++hand.idle >= STUCK_PASSES) {
                hand.idle = 0;
                if (!haul(world, mob, drop.counter())) {
                    // Somebody built over the only place to stand while they
                    // were on their way. Home, with the load, rather than a
                    // courier walking at a wall until they are fired.
                    goHome(world, mob, hand);
                }
            }
            return;
        }
        hand.idle = 0;
        int put = drop.mine() ? unload(mob, drop.stock())
                : sellInto(server, world, hand, mob, drop);
        // And back the other way. Your own till goes in the same bag the goods
        // came out of, which is the whole of "the courier collects": no second
        // trip, no second button, and a day's takings walking down the road is
        // a thing that can be taken off them.
        int lifted = tillOf(drop) <= 0 ? 0 : drop.shop() != null
                ? TrapShops.lift(drop.shop()) : TrapStalls.lift(drop.stall());
        if (lifted > 0) {
            pocket(mob, lifted);
        }
        if (put > 0 || lifted > 0) {
            cheer(world, drop.counter(), SoundEvents.BLOCK_BARREL_CLOSE, 1.0F);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, drop.counter().getX() + 0.5,
                    drop.counter().getY() + 1.2, drop.counter().getZ() + 0.5,
                    12, 0.4, 0.4, 0.4, 0.02);
        }
        hand.stop = (hand.stop + 1) % hand.route.size();
        hand.done++;
        // The road, charged here rather than on the way out. By now the goods
        // are on the shelf, so a boss who logs out mid-breather has had the
        // delivery -- where charging it first would have eaten a load every
        // time somebody quit at the wrong second. A wasted trip still costs
        // it; they made the journey either way. See TrapMath.crewRoadTicks for
        // why distance costs anything at all when the walk is a teleport.
        hand.restUntil = server.getTicks() + TrapMath.crewRoadTicks(
                Math.sqrt(drop.counter().getSquaredDistance(hand.patch)), hand.interval());
        // Always home, never straight on to the next stop. One run is one
        // stop: it ends at the chest, so leftovers, takings and a payment all
        // land somewhere the boss will look, and a courier cannot end up
        // touring a market square forever because one till keeps filling up.
        if (!robbed(world, mob, hand, drop.counter())) {
            goHome(world, mob, hand);
        }
    }

    /**
     * Hand goods over a neighbour's counter and take their money for it.
     *
     * Stack by stack, and only as far as their till stretches -- a shop that
     * has sold nothing today buys nothing today, which is the honest version
     * of "supply your neighbour" and needs no credit, no debt and no ledger
     * between two players who are never online at the same time.
     *
     * Anything they could not pay for stays in the bag and goes home.
     *
     * @return stacks actually sold
     */
    private static int sellInto(MinecraftServer server, ServerWorld world, Hand hand,
                                VillagerEntity mob, Drop drop) {
        net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
        int purse = drop.shop() != null ? drop.shop().till() : drop.stall().till();
        int sold = 0;
        int earned = 0;
        for (int slot = 0; slot < bag.size(); slot++) {
            ItemStack stack = bag.getStack(slot);
            int asking = worth(server, drop, stack);
            // Priced, affordable and shelved, checked in that order and the
            // money moved LAST. Paying first and discovering afterwards that
            // the shelf was full meant a refund path, and a refund path in a
            // trade between two players is three ways to lose an emerald.
            if (asking <= 0 || asking > purse) {
                continue;
            }
            boolean placed = false;
            for (net.minecraft.inventory.Inventory box : drop.stock()) {
                if (store(box, stack)) {
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                continue;   // no room; the remainder rides home
            }
            int paid = drop.shop() != null ? TrapShops.payOut(drop.shop(), asking)
                    : TrapStalls.payOut(drop.stall(), asking);
            purse -= paid;
            bag.setStack(slot, ItemStack.EMPTY);
            sold++;
            earned += paid;
        }
        bag.markDirty();
        if (earned > 0) {
            pocket(mob, earned);
            ServerPlayerEntity boss = world.getServer().getPlayerManager()
                    .getPlayer(hand.boss);
            if (boss != null) {
                TrapLedger.record(boss, TrapLedger.Source.STALL, earned);
                boss.sendMessage(Text.literal("Kurier sprzedał ").formatted(Formatting.GRAY)
                        .append(Text.literal(sold + " partii").formatted(Formatting.WHITE))
                        .append(Text.literal(" do " + drop.name() + " za ")
                                .formatted(Formatting.GRAY))
                        .append(Text.literal(earned + "e").formatted(Formatting.GREEN)),
                        true);
            }
        }
        return sold;
    }

    /**
     * The road home, and who might be waiting on it.
     *
     * Rolled at the stop rather than at the patch, because the bag is at its
     * fullest here -- the goods have gone in, the till has come out, and a
     * courier walking away from a busy shop at midnight with the day's takings
     * is the most obvious target this mod has ever produced. That is the
     * point: every input to {@link TrapMath#courierRobbedChance} is something
     * the boss chose, and the counterplay is to change one of them.
     *
     * A hit empties the bag and opens an ordinary {@link TrapCrime} case, so
     * everything downstream -- the runner, the chase, the arrest, the court --
     * happens without a line of special handling anywhere.
     *
     * @return true if they were done
     */
    private static boolean robbed(ServerWorld world, VillagerEntity mob, Hand hand,
                                  BlockPos from) {
        MinecraftServer server = world.getServer();
        ServerPlayerEntity boss = server.getPlayerManager().getPlayer(hand.boss);
        if (boss == null) {
            return false;
        }
        int value = bagValue(server, mob);
        if (value <= 0) {
            return false;
        }
        float chance = TrapMath.courierRobbedChance(value, TrapPolice.deterrence(),
                !world.isDay(), Math.sqrt(from.getSquaredDistance(hand.patch)));
        if (world.getRandom().nextFloat() >= chance) {
            return false;
        }
        net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
        for (int slot = 0; slot < bag.size(); slot++) {
            bag.setStack(slot, ItemStack.EMPTY);
        }
        bag.markDirty();
        BlockPos scene = mob.getBlockPos();
        haul(world, mob, hand.patch);

        world.playSound(null, scene, SoundEvents.ENTITY_VILLAGER_HURT,
                SoundCategory.NEUTRAL, 1.0F, 0.8F);
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, scene.getX() + 0.5,
                scene.getY() + 1.4, scene.getZ() + 0.5, 16, 0.5, 0.4, 0.5, 0.02);
        TrapCrime.Case sprawa = TrapCrime.mugged(server, world, scene, boss,
                "kurier " + plainName(mob), value);
        boss.sendMessage(TrapNotes.headline("NAPAD NA KURIERA", Formatting.RED)
                .append(TrapNotes.say("   " + plainName(mob), Formatting.WHITE))
                .append(TrapNotes.say("   stracił " + value + "e w towarze i kasie",
                        Formatting.RED))
                .append(TrapNotes.under(sprawa == null
                        ? "Nikt tego nie widział."
                        : "Zgłoszone. Reszta zależy od policji.")), false);
        if (sprawa != null) {
            TrapWaypoints.offer(boss, "Napad na kuriera", scene, TrapWaypoints.RED);
        }
        return true;
    }

    /**
     * What is in the bag, in emeralds.
     *
     * Money at face value and goods at what the market would pay, because a
     * robber does not care which of the two they are walking off with -- and
     * because the case that comes out of this has to be worth ONE number, so
     * that restitution is a payment rather than an inventory to serialise.
     */
    private static int bagValue(MinecraftServer server, VillagerEntity mob) {
        net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
        int total = 0;
        for (int slot = 0; slot < bag.size(); slot++) {
            ItemStack stack = bag.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.EMERALD)) {
                total += stack.getCount();
                continue;
            }
            if (stack.isOf(Items.EMERALD_BLOCK)) {
                total += stack.getCount() * 9;
                continue;
            }
            ShopStock.Entry entry = ShopStock.matching(stack);
            if (entry != null) {
                total += TrapMarket.buyPrice(server, entry)
                        * (stack.getCount() / Math.max(1, entry.count()));
                continue;
            }
            // Weed, coca and what they become. Worth more than anything else
            // a courier carries and priced nowhere else, so the street is the
            // only honest number for it.
            int street = TrapDealing.streetPrice(stack);
            if (street > 0) {
                total += street * stack.getCount();
            }
        }
        return total;
    }

    /** Emeralds into the bag, packed the way a payout always is. */
    private static void pocket(VillagerEntity mob, int amount) {
        int[] packed = TrapMath.packEmeralds(amount);
        net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
        if (packed[0] > 0) {
            bag.addStack(new ItemStack(Items.EMERALD_BLOCK, packed[0]));
        }
        if (packed[1] > 0) {
            bag.addStack(new ItemStack(Items.EMERALD, packed[1]));
        }
        bag.markDirty();
    }

    /**
     * Back to the patch, and the load into the chest it came out of.
     *
     * This ALWAYS ends with an empty bag, on the floor if it has to be. The
     * hand-over branch in {@link #work} runs before everything else, so a load
     * that cannot be put down anywhere is not a stuck delivery -- it is a hand
     * who never picks, cures or rolls again, silently, for as long as the
     * chest stays full. Crops on the floor is what {@link #stow} already
     * decides in exactly this situation, and it is visible.
     */
    private static void goHome(ServerWorld world, VillagerEntity mob, Hand hand) {
        net.minecraft.inventory.Inventory home = nearestBox(world, hand);
        net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
        List<ItemStack> load = new ArrayList<>();
        for (int slot = 0; slot < bag.size(); slot++) {
            if (!bag.getStack(slot).isEmpty()) {
                load.add(bag.getStack(slot).copy());
                bag.setStack(slot, ItemStack.EMPTY);
            }
        }
        bag.markDirty();
        // Only if they are actually away. Any hand can end up in here -- a
        // villager picks bread and carrots up off the floor whatever it was
        // hired for -- and teleporting somebody two blocks to the middle of
        // their own patch for that is a visible twitch with no cause.
        if (!mob.getBlockPos().isWithinDistance(hand.patch, hand.reachBlocks())) {
            haul(world, mob, hand.patch);
        }
        stow(world, home, hand.patch, load);
    }

    /** Empty the bag into the first of these that will have it. */
    private static int unload(VillagerEntity mob,
                              List<net.minecraft.inventory.Inventory> into) {
        net.minecraft.inventory.SimpleInventory bag = mob.getInventory();
        int put = 0;
        for (int slot = 0; slot < bag.size(); slot++) {
            ItemStack stack = bag.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            for (net.minecraft.inventory.Inventory box : into) {
                if (store(box, stack)) {
                    break;
                }
            }
            if (stack.isEmpty()) {
                bag.setStack(slot, ItemStack.EMPTY);
                put++;
            }
        }
        bag.markDirty();
        return put;
    }

    /**
     * Put a shop or a stall on this hand's round, or take it off again.
     *
     * Toggled from where the boss is STOOD, exactly as the spot is moved,
     * because the alternative is a coordinate box and this mod has never asked
     * anybody to type an address. The nearest till or stall of yours within a
     * few blocks is the one you meant; if there are two that close, that is
     * a market square and either is a fine guess.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String round(ServerPlayerEntity boss, int index, BlockPos from) {
        if (index < 0 || index >= CREW.size()) {
            return "Tej osoby nie ma już na liście.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "To nie jest twój człowiek.";
        }
        ServerWorld world = boss.getWorld();
        if (!world.getRegistryKey().getValue().toString().equals(hand.dimension)) {
            return "On pracuje w innym wymiarze. Wózek nie przejdzie portalem.";
        }
        BlockPos found = null;
        for (BlockPos pos : BlockPos.iterateOutwards(from, 6, 4, 6)) {
            if (dropAt(world, boss.getUuid(), pos) != null) {
                found = pos.toImmutable();
                break;
            }
        }
        if (found == null) {
            return "Stań przy kasie albo straganie -- swoim lub cudzym. Musi mieć skrzynię.";
        }
        if (hand.route.remove(found)) {
            save();
            boss.sendMessage(Text.literal("Skreślone z trasy. Zostało "
                    + hand.route.size() + ".").formatted(Formatting.GRAY), false);
            return null;
        }
        if (hand.route.size() >= ROUTE_STOPS) {
            return ROUTE_STOPS + " przystanki to maksimum. Skreśl któryś, stojąc przy nim.";
        }
        hand.route.add(found);
        save();
        world.playSound(null, found, SoundEvents.ENTITY_VILLAGER_YES,
                SoundCategory.NEUTRAL, 0.9F, 1.2F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, found.getX() + 0.5,
                found.getY() + 1.2, found.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.02);
        boss.sendMessage(Text.literal("Dopisane do trasy. ").formatted(Formatting.GREEN)
                .append(Text.literal(hand.route.size() + " z " + ROUTE_STOPS
                        + ", kurs " + TrapMath.crewRoadTicks(Math.sqrt(
                        found.getSquaredDistance(hand.patch)), hand.interval()) / 20
                        + "s w obie strony.").formatted(Formatting.GRAY)), false);
        return null;
    }

    // --- payday ---------------------------------------------------------------

    /**
     * Wages, and what happens when you can't cover them.
     *
     * They walk. Not a warning, not a debt -- a hand who isn't paid stops being
     * your hand, which is the only consequence that makes the wage a real cost
     * rather than a number that accrues somewhere you never look. Everything
     * you taught them walks with them, which is what stops a trained hand being
     * a one-off purchase you can stop feeding.
     */
    private static void clock(MinecraftServer server) {
        List<Hand> quit = new ArrayList<>();
        boolean changed = false;

        for (Hand hand : CREW) {
            ServerPlayerEntity boss = server.getPlayerManager().getPlayer(hand.boss);
            if (boss == null) {
                continue;   // nobody home; the clock is stopped, so are they
            }
            ServerWorld world = worldOf(server, hand);
            if (world == null || !onTheClock(world, hand)) {
                continue;   // night. They are asleep and they are not charging for it
            }
            if (find(server, hand) == null) {
                // Dead. They did no work, so they take no wage -- and a hand a
                // zombie got was otherwise charging forever with nothing to
                // show and no way to notice. The whip puts one back.
                continue;
            }
            hand.onClock += CLOCK_TICKS;
            if (hand.onClock < WAGE_TICKS) {
                continue;
            }
            hand.onClock -= WAGE_TICKS;
            changed = true;
            if (!pay(boss, hand)) {
                quit.add(hand);
            }
        }

        for (Hand hand : quit) {
            walkOut(server, hand);
        }
        if (changed || !quit.isEmpty()) {
            save();
        }
    }

    /**
     * Money that goes to a person rather than out of the world.
     *
     * A hand is a townsperson, and {@link TrapHospitals} already settled what
     * that means: a doctor's fee lands in {@link TrapPayroll} because a wage
     * should come back through a shop door. There is nothing about a farmhand
     * that makes them a different kind of person, but for six versions every
     * emerald a crew was paid went through {@code TrapMarket.take} -- the call
     * for money LEAVING the world -- which made this the one employer in the
     * mod whose staff were paid into a hole.
     *
     * It is not a rounding argument. A crew is the largest recurring outgoing
     * a player has, so a town with three farms on it was quietly having the
     * biggest wage bill in the city deleted rather than spent, and the shops
     * were poorer for exactly the work that should have made them busy. Now
     * the loop closes: you pay somebody, they shop, and the counter they shop
     * at may well be yours.
     *
     * {@code collect} rather than {@code take} for {@link TrapCity#charge}'s
     * reason -- the money is moving, not evaporating, and reporting it
     * destroyed and re-minted would have the index feel two shocks where
     * nothing happened at all.
     *
     * Callers MUST have checked {@link TrapMarket#wealthOf} first, exactly as
     * before; this moves money it assumes is there.
     */
    private static void payTheTown(ServerPlayerEntity boss, int amount) {
        if (amount <= 0) {
            return;
        }
        TrapMarket.collect(boss, amount);
        TrapPayroll.credit(amount);
    }

    /**
     * One packet. False means they have run out of patience.
     *
     * Nothing is owed retroactively when the arrears clear, and that is a
     * choice rather than an oversight: back-pay would mean a player who dug
     * themselves out of a hole gets hit for four packets at once the moment
     * they can finally afford one, which is the same disaster arriving late.
     */
    private static boolean pay(ServerPlayerEntity boss, Hand hand) {
        int wage = hand.wage();
        if (TrapMarket.wealthOf(boss) >= wage) {
            payTheTown(boss, wage);
            TrapLedger.record(boss, TrapLedger.Source.CREW, -wage);
            hand.paid += wage;
            if (hand.missed > 0) {
                boss.sendMessage(Text.literal("Zaległości wyrównane. ")
                        .formatted(Formatting.GREEN)
                        .append(Text.literal("Zaległe " + hand.owed + "e zostaje umorzone.")
                                .formatted(Formatting.GRAY)), false);
                hand.missed = 0;
                hand.owed = 0;
            }
            boss.sendMessage(Text.literal("Pensje: ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("-" + wage + "e").formatted(Formatting.RED)), true);
            return true;
        }

        hand.missed++;
        hand.owed += wage;
        int left = GRACE_PACKETS - hand.missed;
        if (left <= 0) {
            return false;
        }
        boss.sendMessage(Text.literal("ZALEGŁA PENSJA  ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal(hand.owed + "e").formatted(Formatting.WHITE))
                .append(Text.literal("  -- popracuje jeszcze " + left
                        + (left == 1 ? " wypłatę" : " wypłaty")
                        + " za darmo, potem odejdzie.").formatted(Formatting.GRAY)), false);
        return true;
    }

    /**
     * They have gone, and the plan they were part of is written down first.
     *
     * The snapshot is the point. Losing a maxed crew used to mean losing
     * everything you had spent on it with nothing to show but a chat line;
     * now the shape of it is on a list under {@link #WALKOUT} and buying it
     * back is one command and the money.
     */
    private static void walkOut(MinecraftServer server, Hand hand) {
        ServerPlayerEntity boss = server.getPlayerManager().getPlayer(hand.boss);
        keepPlan(hand.boss, WALKOUT, true);
        VillagerEntity mob = find(server, hand);
        if (mob != null) {
            mob.getWorld().playSound(null, mob.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO,
                    SoundCategory.NEUTRAL, 1.0F, 0.7F);
            // Home, not deleted. Somebody who walked out over unpaid wages is
            // still somebody who lives here -- and is available to be hired
            // again tomorrow, by you or by the neighbour who does pay.
            release(mob);
        }
        CREW.remove(hand);
        if (boss != null) {
            boss.sendMessage(Text.literal("Odeszli. ")
                    .formatted(Formatting.RED, Formatting.BOLD)
                    .append(Text.literal("Wszystko, co umieli, jest zapisane pod nazwą \"")
                            .formatted(Formatting.GRAY))
                    .append(Text.literal(WALKOUT).formatted(Formatting.WHITE))
                    .append(Text.literal("\" -- ").formatted(Formatting.GRAY))
                    .append(Text.literal("/crew load " + WALKOUT).formatted(Formatting.GREEN)
                            .styled(style -> style.withClickEvent(
                                    new net.minecraft.text.ClickEvent.SuggestCommand(
                                            "/crew load " + WALKOUT))))
                    .append(Text.literal(" przywraca ekipę.").formatted(Formatting.GRAY)), false);
        }
    }

    private static ServerWorld worldOf(MinecraftServer server, Hand hand) {
        if (server == null) {
            return null;
        }
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(hand.dimension)) {
                return world;
            }
        }
        return null;
    }

    private static VillagerEntity find(MinecraftServer server, Hand hand) {
        ServerWorld world = worldOf(server, hand);
        // getEntity by uuid only finds loaded entities. That used to be most of
        // the crew most of the time; now the patch holds its own ticket, so a
        // hand nobody can find is a hand something ate. The whip is the answer
        // to that, and it is why this staying honest matters.
        return world != null && world.getEntity(hand.mob) instanceof VillagerEntity found
                ? found : null;
    }

    // --- persistence ----------------------------------------------------------

    /**
     * One line a hand: who, which villager, where, and what they know.
     *
     * The three training fields are optional on read. A file written before
     * any of this existed is six fields long and loads as an untrained hand,
     * which is exactly what those hands are -- nobody had bought them anything
     * yet, so there is nothing to migrate. The three peak fields on the end
     * are optional for the same reason and migrate the same way: a hand from
     * before anything could be turned down has been sold precisely what it is
     * standing on.
     */
    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-crew.txt");
        CREW.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 6) {
                    continue;
                }
                Hand hand = new Hand(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                        parts[2], new BlockPos(Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
                if (parts.length >= 9) {
                    hand.pace = clamp(Integer.parseInt(parts[6]), PACE_TICKS.length);
                    hand.reach = clamp(Integer.parseInt(parts[7]), REACH_BLOCKS.length);
                    hand.jobs = trim(Integer.parseInt(parts[8]));
                }
                // Before any of this could be turned down, where a hand stood
                // WAS everything it had been sold, so a file without the three
                // peak fields migrates by saying exactly that. Nobody loses a
                // rung they paid for.
                hand.paceMax = hand.pace;
                hand.reachMax = hand.reach;
                hand.owned = hand.jobs;
                // The books. Optional for the same reason the training was:
                // a file written before anybody counted has nothing to say
                // about it, and a hand with no history is exactly right.
                if (parts.length >= 14) {
                    hand.done = Integer.parseInt(parts[9]);
                    hand.paid = Integer.parseInt(parts[10]);
                    hand.onClock = Integer.parseInt(parts[11]);
                    hand.missed = Integer.parseInt(parts[12]);
                    hand.owed = Integer.parseInt(parts[13]);
                }
                if (parts.length >= 15) {
                    hand.nights = "1".equals(parts[14]);
                }
                if (parts.length >= 18) {
                    hand.paceMax = Math.max(hand.pace,
                            clamp(Integer.parseInt(parts[15]), PACE_TICKS.length));
                    hand.reachMax = Math.max(hand.reach,
                            clamp(Integer.parseInt(parts[16]), REACH_BLOCKS.length));
                    // Not trimmed: SLOTS caps what is switched on, not what
                    // has been paid for.
                    hand.owned = hand.jobs | own(Integer.parseInt(parts[17]));
                }
                // The courier's round, appended after the ladders rather than
                // instead of them: two branches grew this line at the same
                // point and both sets of fields are real. New things still go
                // on the END, which is the only rule this format has ever had.
                if (parts.length >= 20) {
                    if (!"-".equals(parts[18])) {
                        for (String stop : parts[18].split(";")) {
                            String[] bit = stop.split(",");
                            if (bit.length == 3 && hand.route.size() < ROUTE_STOPS) {
                                hand.route.add(new BlockPos(Integer.parseInt(bit[0]),
                                        Integer.parseInt(bit[1]), Integer.parseInt(bit[2])));
                            }
                        }
                    }
                    hand.stop = Math.max(0, Integer.parseInt(parts[19]));
                }
                CREW.add(hand);
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the crew list: {}", failure.toString());
        }
        loadPlans(server);
        loadPlaces(server);
    }

    // --- the plan file --------------------------------------------------------
    //
    // Tab-separated rather than whitespace-split like everything else here,
    // because a crew called "the big farm" is a better name than "bigfarm" and
    // the only thing standing between the two is which character splits the
    // line.

    private static void loadPlans(MinecraftServer server) {
        planFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-crewplans.txt");
        PLANS.clear();
        try {
            if (!Files.exists(planFile)) {
                return;
            }
            for (String line : Files.readAllLines(planFile)) {
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) {
                    continue;
                }
                List<PlanHand> hands = new ArrayList<>();
                for (String one : parts[2].split(";")) {
                    String[] bit = one.trim().split(",");
                    if (bit.length < 7) {
                        continue;
                    }
                    hands.add(new PlanHand(bit[0], new BlockPos(Integer.parseInt(bit[1]),
                            Integer.parseInt(bit[2]), Integer.parseInt(bit[3])),
                            clamp(Integer.parseInt(bit[4]), PACE_TICKS.length),
                            clamp(Integer.parseInt(bit[5]), REACH_BLOCKS.length),
                            trim(Integer.parseInt(bit[6]))));
                }
                if (!hands.isEmpty()) {
                    PLANS.add(new Plan(UUID.fromString(parts[0]), parts[1], hands));
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the crew plans: {}", failure.toString());
        }
    }

    // --- places bought --------------------------------------------------------
    //
    // Its own file rather than a column on the crew list, for the reason
    // PLACES is a map and not a field on Hand: this is the one piece of crew
    // state that belongs to a PLAYER. A boss with nobody on the books still
    // owns what they bought, and a crew file only has rows for people.

    private static Path placeFile;

    private static void loadPlaces(MinecraftServer server) {
        placeFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-crewplaces.txt");
        PLACES.clear();
        try {
            if (!Files.exists(placeFile)) {
                return;
            }
            for (String line : Files.readAllLines(placeFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                // Clamped on the way in, not just on the way out: a file
                // written by a build with a longer ladder would otherwise hand
                // somebody a place this board has no slot for.
                PLACES.put(UUID.fromString(parts[0]),
                        Math.max(0, Math.min(Integer.parseInt(parts[1]), PLACE_COST.length)));
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the crew places: {}", failure.toString());
        }
    }

    private static void savePlaces() {
        if (placeFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<UUID, Integer> entry : PLACES.entrySet()) {
                out.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
            }
            Files.writeString(placeFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't write the crew places: {}", failure.toString());
        }
    }

    private static void savePlans() {
        if (planFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Plan plan : PLANS) {
                out.append(plan.owner()).append('\t').append(plan.name()).append('\t');
                for (int i = 0; i < plan.hands().size(); i++) {
                    PlanHand hand = plan.hands().get(i);
                    out.append(i == 0 ? "" : ";")
                            .append(hand.dimension()).append(',')
                            .append(hand.patch().getX()).append(',')
                            .append(hand.patch().getY()).append(',')
                            .append(hand.patch().getZ()).append(',')
                            .append(hand.pace()).append(',')
                            .append(hand.reach()).append(',')
                            .append(hand.jobs());
                }
                out.append('\n');
            }
            Files.writeString(planFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the crew plans: {}", failure.toString());
        }
    }

    private static int clamp(int value, int rungs) {
        return Math.max(0, Math.min(value, rungs - 1));
    }

    /**
     * Cut a saved hand down to the two jobs it is now allowed.
     *
     * Hands hired before the cap existed could hold all of them. Keeping the
     * FIRST two by declaration order is arbitrary but stable and, since the
     * enum is ordered by priority, it keeps whichever of their jobs mattered
     * most -- and it means a reload never produces a hand the board could not
     * have built.
     */
    /**
     * Keep only bits that name a real job.
     *
     * The taught mask goes through trim, which drops junk as a side effect of
     * capping at SLOTS. This one is not capped -- you may own every job -- so
     * it has to say so itself, or a corrupt digit in the save file would count
     * as owning jobs that do not exist and hand out the foreman badge for it.
     */
    private static int own(int saved) {
        return saved & ((1 << Job.values().length) - 1);
    }

    private static int trim(int saved) {
        int kept = 0;
        int held = 0;
        for (Job job : Job.values()) {
            if ((saved & (1 << job.ordinal())) != 0 && held < SLOTS) {
                kept |= 1 << job.ordinal();
                held++;
            }
        }
        return kept;
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Hand hand : CREW) {
                out.append(hand.boss).append(' ').append(hand.mob).append(' ')
                        .append(hand.dimension).append(' ')
                        .append(hand.patch.getX()).append(' ')
                        .append(hand.patch.getY()).append(' ')
                        .append(hand.patch.getZ()).append(' ')
                        .append(hand.pace).append(' ')
                        .append(hand.reach).append(' ')
                        .append(hand.jobs).append(' ')
                        .append(hand.done).append(' ')
                        .append(hand.paid).append(' ')
                        .append(hand.onClock).append(' ')
                        .append(hand.missed).append(' ')
                        .append(hand.owed).append(' ')
                        .append(hand.nights ? 1 : 0).append(' ')
                        .append(hand.paceMax).append(' ')
                        .append(hand.reachMax).append(' ')
                        .append(hand.owned).append(' ')
                        // The round, and a dash for nobody. Commas inside one
                        // whitespace field, which is the only way this format
                        // has ever been allowed to grow -- see the shop
                        // register for what happens when it grows sideways.
                        .append(hand.route.isEmpty() ? "-" : hand.route.stream()
                                .map(stop -> stop.getX() + "," + stop.getY() + ","
                                        + stop.getZ())
                                .collect(java.util.stream.Collectors.joining(";")))
                        .append(' ').append(hand.stop).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't write the crew list: {}", failure.toString());
        }
    }
}
