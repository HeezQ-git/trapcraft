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
     * The cap on people went up to match. Nine jobs against five hands is ten
     * slots for nine jobs, so a full operation is reachable -- but it is five
     * wages, and the wage is what stops that being free.
     */
    public static final int SLOTS = 2;
    public static final int MAX_HANDS = 5;
    /** How close a hand has to be to a job to do it. */
    private static final int ARM = 4;
    /** Passes of getting nowhere before we accept they can't path there. */
    private static final int STUCK_PASSES = 8;
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
        PICK("Picking", "minecraft:wheat", 0, 0,
                "Your mature plants, into the nearest chest.",
                "a ripe plant in the patch"),
        CURE("Curing", "trapcraft:drying_rack", 480, 8,
                "Loads the racks and pulls them at peak.",
                "a rack, and fresh buds in the chest"),
        REFINE("Refining", "trapcraft:refiner", 1400, 20,
                "Runs the refiner and pulls it at PEAK.",
                "a refiner, paste and blaze powder"),
        PRESS("Pressing", "trapcraft:leaf_press", 750, 12,
                "Leaves into paste, batch after batch.",
                "a press and a batch of coca leaves"),
        ROLL("Rolling", "minecraft:paper", 600, 10,
                "Cured buds and paper into joints.",
                "CURED buds AND paper in the chest"),
        FARM("Farmhand", "minecraft:carrot", 260, 5,
                "Wheat, carrots, anything else that ripens.",
                "a ripe food crop in the patch"),
        FEED("Fertilising", "minecraft:bone_meal", 400, 6,
                "Bone meal on food crops. Never on yours.",
                "bone meal in the chest"),
        SOW("Sowing", "minecraft:wheat_seeds", 340, 6,
                "Plants seeds out of the chest into empty rows.",
                "seeds in the chest and empty farmland"),
        TILL("Tilling", "minecraft:iron_hoe", 220, 4,
                "Turns bare ground near water into farmland.",
                "bare ground near water");

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
    public static final String[] PACE_NAME = {"Plodding", "Steady", "Brisk", "Quick", "Flat out"};

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
        /** Bit per {@link Job} ordinal. Blank on hire -- see SLOTS. */
        int jobs;
        /** Passes since anything actually got done. Not saved -- it's a mood. */
        int idle;
        /** What they did last, so the other job they know gets a turn. */
        Job lastJob;
        /** Working nights. Costs more and never stops. */
        boolean nights;
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

        void teach(Job job) {
            jobs |= 1 << job.ordinal();
        }

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

        /** Top of both ladders and both slots filled. */
        boolean maxed() {
            return pace >= PACE_TICKS.length - 1
                    && reach >= REACH_BLOCKS.length - 1 && full();
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
    public record Card(int index, int pace, int reach, int reachBlocks, int wage,
                       String tempo, boolean present, List<Job> taught,
                       int done, int paid, int missed, int owed,
                       String dimension, int x, int y, int z,
                       String chest, List<Job> starved, boolean nights) {
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
            for (Job job : Job.values()) {
                if (hand.can(job)) {
                    taught.add(job);
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
            out.add(new Card(i, hand.pace, hand.reach, hand.reachBlocks(), hand.wage(),
                    paceLabel(hand.pace), find(boss.getServer(), hand) != null, taught,
                    hand.done, hand.paid, hand.missed, hand.owed, hand.dimension,
                    hand.patch.getX(), hand.patch.getY(), hand.patch.getZ(),
                    box == null || hand.box == null ? null
                            : hand.box.getX() + " " + hand.box.getY() + " " + hand.box.getZ(),
                    starved, hand.nights));
        }
        return out;
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
            return "They're not on the books any more.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "That's not your hand.";
        }

        int cost;
        String bought;
        if (job != null) {
            if (hand.can(job)) {
                return "They already know that one.";
            }
            if (hand.full()) {
                return "Two jobs is all one person will hold. Drop one, or hire somebody.";
            }
            cost = job.cost();
            bought = job.display();
        } else if (pace) {
            if (hand.pace >= PACE_TICKS.length - 1) {
                return "They're already going flat out.";
            }
            cost = PACE_COST[hand.pace + 1];
            bought = PACE_NAME[hand.pace + 1];
        } else {
            if (hand.reach >= REACH_BLOCKS.length - 1) {
                return "That's as much ground as anybody can cover.";
            }
            cost = REACH_COST[hand.reach + 1];
            bought = REACH_BLOCKS[hand.reach + 1] + " blocks";
        }

        if (TrapMarket.wealthOf(boss) < cost) {
            return "That's " + cost + "e, and you haven't got it.";
        }
        // Through the market, like every other emerald this mod moves. A wage
        // that skipped circulate() would be a hole in the money supply the
        // index could never see.
        TrapMarket.take(boss, cost);
        TrapLedger.record(boss, TrapLedger.Source.CREW, -cost);
        if (job != null) {
            hand.teach(job);
        } else if (pace) {
            hand.pace++;
        } else {
            hand.reach++;
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
        boss.sendMessage(Text.literal("Taught them ").formatted(Formatting.GREEN)
                .append(Text.literal(bought).formatted(Formatting.WHITE))
                .append(Text.literal(". Wages now " + hand.wage() + "e.")
                        .formatted(Formatting.GRAY)), false);
        if (hand.maxed()) {
            TrapAwards.grant(boss, "foreman");
        }
        return null;
    }

    /**
     * Drop a job to free the slot. Nothing comes back.
     *
     * Has to exist, because with only two slots a misclick would otherwise be
     * permanent and the board would be a minefield. No refund, for the same
     * reason firing gives none: what you paid bought the teaching, and the
     * teaching happened.
     */
    public static String forget(ServerPlayerEntity boss, int index, Job job) {
        if (index < 0 || index >= CREW.size()) {
            return "They're not on the books any more.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "That's not your hand.";
        }
        if (!hand.can(job)) {
            return "They never knew that one.";
        }
        hand.forget(job);
        save();
        boss.sendMessage(Text.literal("They've forgotten ").formatted(Formatting.GRAY)
                .append(Text.literal(job.display()).formatted(Formatting.WHITE))
                .append(Text.literal(". Wages now " + hand.wage() + "e.")
                        .formatted(Formatting.GRAY)), false);
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
     */
    private static VillagerEntity put(ServerWorld world, BlockPos patch, float yaw) {
        VillagerEntity mob = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (mob == null) {
            return null;
        }
        mob.refreshPositionAndAngles(patch.up(), yaw, 0.0F);
        mob.setPersistent();
        mob.setAiDisabled(false);
        mob.setCustomName(Text.literal("Hand").formatted(Formatting.YELLOW));
        mob.setCustomNameVisible(true);
        // NITWIT, and not merely "no profession". A professionless villager
        // takes a job from any workstation it wanders past and becomes a
        // trader -- which would undercut the market stall by accident, exactly
        // what this was supposed to avoid. A nitwit never takes one.
        mob.setVillagerData(mob.getVillagerData().withProfession(
                world.getRegistryManager()
                        .getOrThrow(net.minecraft.registry.RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(net.minecraft.village.VillagerProfession.NITWIT)));
        world.spawnEntity(mob);
        return mob;
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
            return "They're not on the books any more.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "That's not your hand.";
        }
        ServerWorld world = worldOf(boss.getServer(), hand);
        if (world == null) {
            return "That patch is in a world that isn't there any more.";
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
            return "That patch is still waking up. Try again in a second.";
        }
        world.getChunkManager().addTicket(TICKET, home, ticketRadius(hand.reachBlocks()));

        boolean fresh = mob == null;
        if (fresh) {
            mob = put(world, hand.patch, boss.getYaw());
            if (mob == null) {
                return "Couldn't put anybody down there.";
            }
            hand.mob = mob.getUuid();
            save();
        }

        mob.refreshPositionAndAngles(hand.patch.up(), mob.getYaw(), 0.0F);
        mob.getNavigation().stop();
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
                ? Text.literal("You'd lost that one. ").formatted(Formatting.YELLOW)
                        .append(Text.literal("Somebody else is on the patch, and they "
                                + "know everything you paid to teach.")
                                .formatted(Formatting.GRAY))
                : Text.literal("Back on the patch.").formatted(Formatting.GREEN), false);
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
            return "They're not on the books any more.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "That's not your hand.";
        }
        ServerWorld world = boss.getWorld();
        String dimension = world.getRegistryKey().getValue().toString();
        BlockPos spot = to.toImmutable();
        if (spot.equals(hand.patch) && dimension.equals(hand.dimension)) {
            return "That's where they already work.";
        }

        VillagerEntity mob = find(boss.getServer(), hand);
        boolean moved = dimension.equals(hand.dimension);
        if (!moved) {
            if (mob != null) {
                mob.discard();
            }
            mob = put(world, spot, boss.getYaw());
            if (mob == null) {
                return "Couldn't put anybody down there.";
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
            if (moved) {
                mob.refreshPositionAndAngles(spot.up(), mob.getYaw(), 0.0F);
                mob.getNavigation().stop();
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
        boss.sendMessage(Text.literal("They work here now. ").formatted(Formatting.GREEN)
                .append(Text.literal(spot.getX() + " " + spot.getY() + " " + spot.getZ()
                        + (moved ? "" : ", and somebody new is stood on it."))
                        .formatted(Formatting.GRAY)), false);
        return null;
    }

    /**
     * Take somebody on at this spot.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String hire(ServerPlayerEntity boss, BlockPos patch) {
        if (sizeOf(boss) >= MAX_HANDS) {
            return MAX_HANDS + " hands is all one operation will carry.";
        }
        if (TrapMarket.wealthOf(boss) < HIRE_COST) {
            return "Taking somebody on costs " + HIRE_COST + "e.";
        }
        ServerWorld world = boss.getWorld();
        VillagerEntity mob = put(world, patch, boss.getYaw());
        if (mob == null) {
            return "Nobody's about.";
        }
        TrapMarket.take(boss, HIRE_COST);
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
        boss.sendMessage(Text.literal("Hired. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("They know nothing yet. Two jobs is all "
                        + "anybody will hold, so pick carefully.")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("\n  /crew").formatted(Formatting.GREEN))
                .append(Text.literal("  to teach them. Picking is free.")
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
                hands.add(new PlanHand(hand.dimension, hand.patch, hand.pace, hand.reach,
                        hand.jobs));
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
            return "Give it a name.";
        }
        if (sizeOf(boss) == 0) {
            return "You haven't got anybody to write down.";
        }
        Plan plan = keepPlan(boss.getUuid(), wanted, false);
        boss.sendMessage(Text.literal("Written down as ").formatted(Formatting.GREEN)
                .append(Text.literal(plan.name()).formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal("  " + plan.hands().size()
                        + (plan.hands().size() == 1 ? " hand, " : " hands, ")
                        + plan.cost() + "e to put back.").formatted(Formatting.GRAY)), false);
        return null;
    }

    public static String forget(ServerPlayerEntity boss, String name) {
        Plan plan = planOf(boss.getUuid(), name.trim());
        if (plan == null) {
            return "No plan by that name.";
        }
        PLANS.remove(plan);
        savePlans();
        boss.sendMessage(Text.literal("Torn up.").formatted(Formatting.GRAY), false);
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
            return "No plan by that name. /crew plans lists them.";
        }
        int room = MAX_HANDS - sizeOf(boss);
        if (plan.hands().size() > room) {
            return "You've room for " + room + " more, and that plan is "
                    + plan.hands().size() + ".";
        }
        int cost = plan.cost();
        if (TrapMarket.wealthOf(boss) < cost) {
            return "Putting that crew back costs " + cost + "e.";
        }
        MinecraftServer server = boss.getServer();
        for (PlanHand wanted : plan.hands()) {
            if (worldNamed(server, wanted.dimension()) == null) {
                return "Part of that crew worked a world that isn't here any more.";
            }
        }

        TrapMarket.take(boss, cost);
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
            hand.pace = clamp(wanted.pace(), PACE_TICKS.length);
            hand.reach = clamp(wanted.reach(), REACH_BLOCKS.length);
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
        boss.sendMessage(Text.literal("Back on. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal(put + (put == 1 ? " hand" : " hands") + " on their old "
                        + "patches, trained. Payroll is now "
                        + payrollOf(boss) + "e.").formatted(Formatting.GRAY)), false);
        return null;
    }

    private static void listPlans(ServerPlayerEntity boss) {
        List<Plan> mine = plansOf(boss);
        if (mine.isEmpty()) {
            boss.sendMessage(Text.literal("Nothing written down. ").formatted(Formatting.GRAY)
                    .append(Text.literal("/crew save <name>").formatted(Formatting.GREEN))
                    .append(Text.literal(" keeps the crew you've got.")
                            .formatted(Formatting.DARK_GRAY)), false);
            return;
        }
        boss.sendMessage(Text.literal("Crews on file").formatted(Formatting.GOLD, Formatting.BOLD),
                false);
        for (Plan plan : mine) {
            boss.sendMessage(Text.literal("  " + plan.name())
                    .formatted(Formatting.WHITE, Formatting.BOLD)
                    .styled(style -> style.withClickEvent(
                            new net.minecraft.text.ClickEvent.SuggestCommand(
                                    "/crew load " + plan.name())))
                    .append(Text.literal("  " + plan.hands().size()
                            + (plan.hands().size() == 1 ? " hand" : " hands"))
                            .formatted(Formatting.GRAY))
                    .append(Text.literal("  " + plan.cost() + "e to put back")
                            .formatted(Formatting.GOLD))
                    .append(Text.literal("  then " + plan.wage() + "e a packet")
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
            return "They're not on the books any more.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "That's not your hand.";
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
                ? Text.literal("On nights. ").formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(Text.literal("They work through the dark and the wage "
                                + "clock never stops. " + hand.wage() + "e a packet now.")
                                .formatted(Formatting.GRAY))
                : Text.literal("Days only. ").formatted(Formatting.GREEN)
                        .append(Text.literal("Back to " + hand.wage()
                                + "e, and nights are free again.").formatted(Formatting.GRAY)),
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
                mob.discard();
            }
            CREW.remove(i);
            save();
            boss.sendMessage(Text.literal("Let them go.").formatted(Formatting.GRAY), false);
            return null;
        }
        return "You haven't got anybody on.";
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
                Text.literal("The Crew").formatted(Formatting.GOLD)));
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
            mob.refreshPositionAndAngles(hand.patch.up(), mob.getYaw(), 0.0F);
            mob.getNavigation().stop();
            world.spawnParticles(ParticleTypes.POOF, hand.patch.getX() + 0.5,
                    hand.patch.getY() + 1.0, hand.patch.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
            return;
        }
        if (strayed > reach) {
            walkTo(mob, hand.patch, hand);
            return;
        }

        net.minecraft.inventory.Inventory box = nearestBox(world, hand);
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
                mob.refreshPositionAndAngles(job.up(), mob.getYaw(), 0.0F);
                mob.getNavigation().stop();
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
                .append(Text.literal(dawn ? "SHIFT ON" : "SHIFT OVER")
                        .formatted(dawn ? Formatting.GOLD : Formatting.BLUE, Formatting.BOLD))
                .append(Text.literal("  ·  ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(hands + (hands == 1 ? " hand" : " hands"))
                        .formatted(Formatting.WHITE))
                .append(Text.literal(dawn ? " out on the patch" : " gone to bed")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("\n   " + (dawn
                        ? "Wages are running again until dusk."
                        : "Nothing picked and nothing charged till dawn."))
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

    private static int counts(net.minecraft.inventory.Inventory box, net.minecraft.item.Item want) {
        if (box == null) {
            return 0;
        }
        int found = 0;
        for (int slot = 0; slot < box.size(); slot++) {
            if (box.getStack(slot).isOf(want)) {
                found += box.getStack(slot).getCount();
            }
        }
        return found;
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
        return new Supplies(holds(box, Items.BONE_MEAL), holdsSeed(box),
                holdsRawBud(box),
                counts(box, TrapContent.cocaLeaves) >= LeafPressBlock.LEAVES_PER_BATCH,
                holds(box, TrapContent.cocaPaste) && holds(box, Items.BLAZE_POWDER),
                rollable(box) != null);
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
            default -> true;
        };
    }

    /** What the chest can back up this pass. */
    private record Supplies(boolean boneMeal, boolean seeds, boolean rawBuds,
                            boolean leaves, boolean paste, boolean rolling) {
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
        if (block instanceof CannabisCropBlock || block instanceof CocaCropBlock) {
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
                feed(box, TrapContent.cocaLeaves,
                        leaves -> LeafPressBlock.load(state, world, at, leaves));
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
        if (box != null && world.getBlockEntity(at) instanceof net.minecraft.inventory.Inventory
                && hand.can(Job.ROLL)) {
            roll(world, box, at);
            return;
        }
        if (block instanceof CannabisCropBlock || block instanceof CocaCropBlock) {
            // Through the block's own harvest, not getDroppedStacks: breaking
            // one of these runs the loot table and returns a SEED. The buds
            // only come off a right-click, and a hand that broke the plant was
            // demolishing the farm and stashing seeds.
            List<ItemStack> picked = block instanceof CannabisCropBlock weed
                    ? weed.harvest(world, at, state)
                    : ((CocaCropBlock) block).harvest(world, at, state);
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
            if (box == null || !store(box, drop)) {
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
        if (hand.box != null
                && world.getBlockEntity(hand.box) instanceof net.minecraft.inventory.Inventory known) {
            return known;
        }
        for (BlockPos pos : BlockPos.iterateOutwards(hand.patch, hand.reachBlocks(), 4,
                hand.reachBlocks())) {
            if (world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory box) {
                hand.box = pos.toImmutable();
                return box;
            }
        }
        hand.box = null;
        return null;
    }

    /** Put a drop away. False if it wouldn't fit. */
    private static boolean store(net.minecraft.inventory.Inventory box, ItemStack drop) {
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack there = box.getStack(slot);
            if (there.isEmpty()) {
                box.setStack(slot, drop.copy());
                box.markDirty();
                return true;
            }
            if (ItemStack.areItemsAndComponentsEqual(there, drop)
                    && there.getCount() + drop.getCount() <= there.getMaxCount()) {
                there.increment(drop.getCount());
                box.markDirty();
                return true;
            }
        }
        return false;
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
            TrapMarket.take(boss, wage);
            TrapLedger.record(boss, TrapLedger.Source.CREW, -wage);
            hand.paid += wage;
            if (hand.missed > 0) {
                boss.sendMessage(Text.literal("Square with the crew again. ")
                        .formatted(Formatting.GREEN)
                        .append(Text.literal("The " + hand.owed + "e you missed is written off.")
                                .formatted(Formatting.GRAY)), false);
                hand.missed = 0;
                hand.owed = 0;
            }
            boss.sendMessage(Text.literal("Wages: ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("-" + wage + "e").formatted(Formatting.RED)), true);
            return true;
        }

        hand.missed++;
        hand.owed += wage;
        int left = GRACE_PACKETS - hand.missed;
        if (left <= 0) {
            return false;
        }
        boss.sendMessage(Text.literal("WAGES DUE  ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal(hand.owed + "e").formatted(Formatting.WHITE))
                .append(Text.literal("  -- they'll work " + left
                        + (left == 1 ? " more payday" : " more paydays")
                        + " on nothing, then walk.").formatted(Formatting.GRAY)), false);
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
            mob.discard();
        }
        CREW.remove(hand);
        if (boss != null) {
            boss.sendMessage(Text.literal("They've walked. ")
                    .formatted(Formatting.RED, Formatting.BOLD)
                    .append(Text.literal("Everything they knew is written down under \"")
                            .formatted(Formatting.GRAY))
                    .append(Text.literal(WALKOUT).formatted(Formatting.WHITE))
                    .append(Text.literal("\" -- ").formatted(Formatting.GRAY))
                    .append(Text.literal("/crew load " + WALKOUT).formatted(Formatting.GREEN)
                            .styled(style -> style.withClickEvent(
                                    new net.minecraft.text.ClickEvent.SuggestCommand(
                                            "/crew load " + WALKOUT))))
                    .append(Text.literal(" buys them back.").formatted(Formatting.GRAY)), false);
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
     * yet, so there is nothing to migrate.
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
                CREW.add(hand);
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the crew list: {}", failure.toString());
        }
        loadPlans(server);
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
                        .append(hand.nights ? 1 : 0).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't write the crew list: {}", failure.toString());
        }
    }
}
