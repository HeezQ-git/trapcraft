package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The second office the city runs, and the first one it can starve on purpose.
 *
 * The ward is a bill nobody chose: somebody is bitten, the doctors are paid,
 * and the only decision a council ever makes about it is whether there is a
 * building. This is the other kind of public service -- one with a DIAL on it.
 * The city sets what it pays the force, at the vault, and everything else
 * follows from that one number: how many coppers walk the street, how fast
 * they move, how far they can see, and therefore how much of what
 * {@link TrapCrime} throws at the town ever gets answered.
 *
 * <h2>Why the money is one number and not a wage each</h2>
 *
 * Same reason {@link TrapPayroll} keeps one purse. An officer is a townsperson
 * and their wage comes back through a shop door; what the player is buying is
 * not a person, it is a LEVEL OF SERVICE. A per-officer contract would be six
 * menus to say what "1800e a day" already says.
 *
 * <h2>What the dial actually buys</h2>
 *
 * <ol>
 *   <li>{@link #force()} -- one officer per {@link #WAGE} a day, capped by the
 *       cells the city has actually built. Money with nowhere to sleep hires
 *       nobody, which is what makes the BUILDING matter as well as the budget.
 *   <li>{@link #gear()} -- 0 to {@link #TOP_GEAR}, off the same number. Kit is
 *       speed, sight and the weight behind a truncheon, and it is the half of
 *       the dial that answers "and faster". A skeleton force of four is not
 *       the same thing as four coppers with radios.
 * </ol>
 *
 * <h2>Failing poor rather than failing closed</h2>
 *
 * The ward stops treating people the day the purse cannot cover a bill, and
 * that is right: a patient is a discrete thing you either pay for or do not.
 * A police force is not. A city that comes up short does not lose its police,
 * it loses SOME of them -- so {@link #payday} spends down in
 * {@link #BUDGET_STEP} lumps until something clears, and the shortfall is
 * announced. An unpaid force is the reason the burglaries start.
 *
 * @see TrapCrime for what they are out there answering
 * @see TrapHospitals for the office this one is modelled on
 */
public final class TrapPolice {

    /** Ticks between one station being looked at again. Same clock as the wards. */
    private static final int ROUND_TICKS = 240;
    /**
     * Ticks between one shove along the beat.
     *
     * Much faster than the register's round, and it has to be: a villager
     * Brain re-picks its own destination the moment it has none, so a patrol
     * target set once every twelve seconds is a patrol target that survives
     * about one of them. Same lesson {@link TrapVisitors} wrote down.
     */
    private static final int BEAT_TICKS = 30;

    /** Marks an officer's body, so one that outlived its station can be found. */
    public static final String OFFICER_TAG = "trapcraft_officer";
    /** Marks somebody in a cell, so nothing walks them home. */
    public static final String PRISONER_TAG = "trapcraft_prisoner";
    /** Marks a golem, for the same reason and swept the same way. */
    public static final String GOLEM_TAG = "trapcraft_golem";

    // --- what a station has to be ---------------------------------------------

    /**
     * Cells, counted as beds. One bunk is a spare room, not a custody suite.
     *
     * The survey already counts beds and a cell has a bunk in it, so this is
     * the ward's requirement wearing a different word -- which is the point.
     * Nobody should have to learn a second way of registering a building.
     */
    public static final int MIN_CELLS = 2;
    /** Floor squares. Under a ward's, because a nick is offices and cells. */
    public static final int MIN_FLOOR = 20;
    /** How finished the shell has to be. A station built of dirt is a hole. */
    public static final float MIN_SHELL = HomeSurvey.SHELL_STEPS[1];

    // --- what it costs --------------------------------------------------------

    /** Out of the city purse, per officer, per day. */
    public static final int WAGE = 300;
    /** What one click of the dial at the vault is worth. */
    public static final int BUDGET_STEP = 150;
    /** The most a city can put on the force in a day. */
    public static final int MAX_BUDGET = 2400;
    /** Every this much of daily budget is one grade of kit. */
    public static final int GEAR_AT = 600;
    /** Kit stops here. Beyond it the money only buys more bodies. */
    public static final int TOP_GEAR = 3;

    /** What each grade of kit is called, on the nameplate. */
    private static final String[] RANKS = {"post.", "st. post.", "sierż.", "asp."};

    // --- how they behave ------------------------------------------------------

    /** How far an unequipped officer notices anything, in blocks. */
    private static final int SIGHT = 16;
    /** And what each grade of kit adds to that. */
    private static final int SIGHT_PER_GEAR = 6;
    /** Close enough to swing a truncheon, or to put the cuffs on. */
    private static final double REACH = 2.4;
    /**
     * The grade of kit that comes with a crossbow, and how far it carries.
     *
     * A villager cannot work a crossbow on its own -- {@code VillagerEntity}
     * implements {@code InteractionObserver} and {@code VillagerDataContainer}
     * and nothing else, so there is no {@code CrossbowUser} and no aiming,
     * loading or firing AI anywhere on it. Checked by decompiling 1.21.8
     * rather than assumed.
     *
     * What it CAN do is hold the thing where people can see it:
     * {@code VillagerEntityRenderer} carries a
     * {@code VillagerHeldItemFeatureRenderer} and feeds it from the entity's
     * own main hand. So the crossbow is real to look at, and the bolt is fired
     * from here -- which is how their melee already worked.
     */
    public static final int SHOOT_AT = 2;
    public static final double SHOOT_RANGE = 16.0;
    /**
     * Walk multiplier for a chase, matched to {@link TrapCrime}'s runner.
     *
     * A WalkTarget speed is a MULTIPLIER on the movement-speed attribute, not
     * a speed -- so two sides of a chase with different multipliers are not
     * racing the numbers anybody tuned.
     */
    static final float CHASE_PACE = 0.9F;
    /** How far from the officer one leg of the beat can take them. */
    private static final int STRIDE = 22;
    /** Near enough to count as having got there. */
    private static final double ARRIVED = 3.0;
    /**
     * Near enough to count as having reached the far end of the round.
     *
     * Wider than {@link #ARRIVED}, and the width is arithmetic rather than
     * taste. Every leg is aimed with four blocks of slop on each axis so two
     * officers do not walk the same rail, which puts the last leg up to 5.7
     * from the errand, and {@link #ARRIVED} lets them stop 3 short of that.
     * Nine covers both. Judge the errand by the leg's own accuracy instead and
     * an officer stands four blocks from a door for forty seconds, re-aiming
     * and missing again, until patience throws away a round they had walked.
     */
    private static final double AT_POST = 9.0;
    /** How far out the round goes when the town has nothing worth guarding. */
    private static final int RING_MIN = 14;
    private static final int RING_RANGE = 24;
    /**
     * Ticks to give one leg before writing it off and picking another.
     *
     * Generous, because the point of the sticky target is that it SURVIVES --
     * a patience short enough to expire mid-walk is the oscillation this was
     * written to stop, wearing a longer name. Twenty-two blocks at a
     * villager's pace is well under this.
     */
    private static final int BEAT_PATIENCE = 20 * 40;
    /**
     * Walk multiplier for a stroll.
     *
     * Above the 0.5 {@link TrapHomes#walkTo} uses for a tenant pottering
     * around their own front room: a copper on the beat is going somewhere,
     * and at a tenant's pace a leg of the beat outlives the patience for it.
     */
    private static final float BEAT_PACE = 0.65F;
    /** Past this from their own station an officer is walked back. */
    private static final int LEASH = 128;
    /** Past THIS they are simply put back, because no path is that long. */
    private static final int LOST = 256;
    /**
     * How far out on the round an errand may be SET.
     *
     * Under the leash by a full stride, and that gap is the whole reason this
     * constant exists rather than {@link #LEASH} being used twice. A beat was
     * picked from every house and counter in the world, so a station on the
     * edge of a big town handed its shift errands a hundred and forty blocks
     * away -- and the officer walked out, tripped the leash at 128, got sent
     * home, came back inside it, and was handed the same impossible errand
     * again. From the street that is a copper pacing the same line all night,
     * which is the opposite of the complaint it was meant to answer.
     *
     * A stride of margin means arriving at the far end of the round still
     * leaves them inside the leash, slop and all.
     */
    private static final int BEAT_REACH = LEASH - STRIDE;
    /**
     * How long a call-out holds the round before the beat goes back to normal.
     *
     * A minute and a half, which is roughly a raid. Long enough to walk the
     * length of a beat at officer pace and short enough that a farm on the
     * edge of town cannot park the entire shift on itself by being robbed
     * every ten minutes -- the houses and the shops are the round, and a
     * standing call-out that never expired would quietly become one.
     */
    private static final int SHOUT_TICKS = 20 * 90;
    /** How close a copper has to be to stop somebody for a word. */
    private static final int STOP_RANGE = 7;
    /** Ticks before the same player can be stopped again. */
    private static final int STOP_COOLDOWN = 20 * 60 * 5;
    /** Contraband in a pocket that a copper would bother writing up. */
    public static final int LOOKS_AWAY = 8;

    // --- the golems -----------------------------------------------------------

    /**
     * Navigation multiplier for a golem walking its round.
     *
     * Full, not the 0.6 vanilla wanders at: a golem's own movement speed is
     * 0.25 against a villager's 0.5, so even flat out it lumbers along at
     * about three quarters of a copper's beat. Anything less and one leg of
     * the round outlives {@link #BEAT_PATIENCE}, which reads as a golem that
     * never gets anywhere.
     */
    private static final double GOLEM_PACE = 1.0;
    /**
     * Follow range, set on every golem because the default will not walk a leg.
     *
     * This one attribute is doing two jobs, and the second is the trap. It is
     * the radius a target is acquired in -- but it is ALSO the radius the
     * pathfinder is allowed to search, so a golem left on the default cannot
     * be sent {@link #STRIDE} blocks plus slop and simply stands there. Raised
     * to clear a full leg, and no further: vanilla's own monster scan does not
     * check line of sight, so every block of this is another cave under the
     * town whose zombies a golem knows about. See {@link #march} for what
     * stops that becoming a siege.
     */
    private static final double GOLEM_RANGE = 32.0;
    /**
     * Headroom a golem needs to be stood somewhere, in blocks.
     *
     * Three, not the two {@link TrapSpawn#safe} assumes for everything else in
     * this mod: a golem is 2.7 tall, so a villager-sized slot puts its eyes
     * inside the ceiling and the station's first act is to suffocate its own
     * garrison in the yard.
     */
    private static final int GOLEM_TALL = 3;
    /**
     * How far round an address a golem may be stood up, and how far up.
     *
     * The vertical half is the tight one on purpose. A one-storey roof is
     * three blocks over its own floor and has a perfectly good view of the
     * sky, so a taller search answers "outdoors" with somebody's roof and the
     * garrison spends the night up there.
     */
    private static final int GOLEM_SPREAD = 8;
    private static final int GOLEM_RISE = 2;
    /** Addresses tried before the round gives up and looks again in 12s. */
    private static final int GOLEM_TRIES = 6;
    /** Near enough to the sign to count as still inside the building. */
    private static final double WALLED_IN = 12.0;

    /** One station. */
    public static final class Station {
        final UUID id;
        final UUID owner;
        String ownerName;
        final String dimension;
        /** Where the block stands, which is where the survey is taken from. */
        BlockPos sign;
        /** Cells counted at the last inspection. Caps the officers AND the jail. */
        int cells;
        int floor;
        /** Did it pass its last inspection? A closed station has no shift. */
        boolean open;
        /** Running counts, for the plaque. */
        int arrests;
        int calls;
        String name;
        /** Who is on shift, and where each of them is headed. Memory only. */
        final List<Patrol> officers = new ArrayList<>();
        /** And the iron half of the shift, walking the same rounds. */
        final List<Patrol> golems = new ArrayList<>();
        /**
         * An address somebody shouted about, and how long it holds the round.
         *
         * Memory only, deliberately. A call-out that survived a restart would
         * send the whole shift to a raid that finished last Tuesday, and there
         * is nothing to recover: whatever happened there has happened.
         */
        BlockPos shout;
        long shoutBy;

        Station(UUID id, UUID owner, String ownerName, String dimension, BlockPos sign) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.dimension = dimension;
            this.sign = sign;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public UUID owner() {
            return owner;
        }

        public String ownerName() {
            return ownerName;
        }

        public BlockPos sign() {
            return sign;
        }

        public int cells() {
            return cells;
        }

        public boolean open() {
            return open;
        }

        public int arrests() {
            return arrests;
        }

        public int calls() {
            return calls;
        }

        /** Officers actually standing up in this one right now. */
        public int onShift() {
            return officers.size();
        }

        /** Golems actually standing up in this one right now. */
        public int onGuard() {
            return golems.size();
        }

        /** Cells with nobody in them. */
        public int free() {
            return Math.max(0, cells - held(id));
        }
    }

    /**
     * One officer and the leg of the beat they are currently walking.
     *
     * The target has to be REMEMBERED, and that is the whole of this class.
     * The first version picked a fresh random destination on every pass --
     * every thirty ticks -- so an officer got a new place to be one and a half
     * seconds after being sent to the last one, turned round, and spent the
     * shift oscillating. Measured on the live server: six of seven officers
     * inside seven blocks of their own front door, and the seventh only out at
     * seventy-five because it happened to roll the same direction twice.
     *
     * A villager Brain still drops a walk target the moment it has none, so
     * the target is re-asserted on every pass -- but it is the SAME target
     * until they arrive or run out of patience. Same lesson, same shape, as
     * {@link TrapVisitors}' walk to a ward.
     */
    static final class Patrol {
        final UUID body;
        /** Where they are headed, or null before the first leg. */
        BlockPos beat;
        /**
         * The far end of the round: the house, the counter or the vault this
         * whole walk is FOR. Held across legs, and that is the second half of
         * the same lesson.
         *
         * A sticky leg fixed the officer who turned round every second and a
         * half. It did not fix the officer who never left the neighbourhood,
         * because the ERRAND was still re-rolled every time a leg finished --
         * twenty-two blocks toward a shop, arrive, and now roll again, four
         * times in ten landing on a ring drawn round the officer's own front
         * door. Twenty-two blocks is not far enough to reach anything across
         * a town, so the walk never accumulated: it was a drunkard's walk with
         * a spring pulling it back to the nick, and from the street it looked
         * exactly like a copper who would not leave the doorstep.
         *
         * With the post held, the legs add up. The officer keeps walking at
         * the same shop until they are standing at it, and only then picks
         * somewhere new -- which is what "walking the beat" means.
         */
        BlockPos post;
        /** World time after which this leg is abandoned as unreachable. */
        long by;

        Patrol(UUID body) {
            this.body = body;
        }
    }

    /**
     * Somebody doing time.
     *
     * Keyed by nothing but their own name, because unlike a patient there is
     * no household behind them to write to -- a suspect is a person the town
     * produced, not a tenant off the register, and tying one to a house would
     * mean a burglary could evict its own burglar's family.
     */
    public static final class Prisoner {
        final String who;
        final String crime;
        final UUID station;
        /** The day they walk out. */
        long until;
        UUID body;

        Prisoner(String who, String crime, UUID station, long until) {
            this.who = who;
            this.crime = crime;
            this.station = station;
            this.until = until;
        }

        public String who() {
            return who;
        }

        public String crime() {
            return crime;
        }

        public long until() {
            return until;
        }
    }

    private static final List<Station> STATIONS = new ArrayList<>();
    private static final List<Prisoner> CELLS = new ArrayList<>();
    /** When each player was last stopped for a word. Memory only. */
    private static final Map<UUID, Long> STOPPED = new HashMap<>();

    /** What the council has put on the force, per day. The dial at the vault. */
    private static int budget;
    /**
     * What it actually managed to pay this morning.
     *
     * Not the same number as {@link #budget} and the difference is the whole
     * of the failure mode: a council that voted 1800e out of an empty purse
     * has a force of however many 150e lumps the treasury could stand.
     */
    private static int funded;
    /** Everything the coppers have ever been paid, and everything they raised. */
    private static long spent;
    private static long fines;
    /** The last day payday ran, so a restart cannot pay the force twice. */
    private static long paidOn = -1;
    private static Path saveFile;
    private static int cursor;

    private TrapPolice() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapPolice::load);
        registerCommand();
        // An officer bitten on the night shift is an officer, not a zombie
        // standing in the street wearing a rank. TrapHospitals' handler
        // ignores them -- they are nobody's tenant -- so without this the body
        // simply survives as a zombie villager forever.
        ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, context) -> {
            if (!(previous instanceof VillagerEntity)
                    || !converted.getCommandTags().contains(OFFICER_TAG)
                    || !(converted.getWorld() instanceof ServerWorld world)) {
                return;
            }
            BlockPos where = converted.getBlockPos();
            converted.discard();
            world.playSound(null, where, SoundEvents.ENTITY_VILLAGER_DEATH,
                    SoundCategory.NEUTRAL, 0.9F, 0.7F);
            fallen(world.getServer());
        });
        // A body from a shift that ended when the server did. Same rule as a
        // tourist: nothing on disk knows this person, so nothing is lost by
        // binning them and hiring again on the next round.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof IronGolemEntity golem
                    && golem.getCommandTags().contains(GOLEM_TAG)) {
                // A golem whose station came down while its chunk was asleep.
                // Same rule as an officer's body, and it matters more here:
                // this one is a hundred hit points of iron that would otherwise
                // patrol a demolished building forever.
                if (stationOf(golem, GOLEM_TAG) == null) {
                    golem.discard();
                }
                return;
            }
            if (!(entity instanceof VillagerEntity body)) {
                return;
            }
            if (body.getCommandTags().contains(OFFICER_TAG)
                    && stationOf(body, OFFICER_TAG) == null) {
                body.discard();
            } else if (body.getCommandTags().contains(PRISONER_TAG)
                    && prisonerOf(body.getUuid()) == null) {
                body.discard();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % ROUND_TICKS == 0) {
                rounds(server);
            }
            if (server.getTicks() % BEAT_TICKS == 0 && !STATIONS.isEmpty()) {
                shift(server);
            }
        });
    }

    public static List<Station> all() {
        return STATIONS;
    }

    public static List<Prisoner> prisoners() {
        return CELLS;
    }

    // --- the money ------------------------------------------------------------

    /** What the council has voted, per day. */
    public static int budget() {
        return budget;
    }

    /** What it managed to pay this morning. Never above {@link #budget}. */
    public static int funded() {
        return funded;
    }

    public static long spent() {
        return spent;
    }

    public static long fines() {
        return fines;
    }

    /**
     * Move the dial. Clamped, stepped, and saved.
     *
     * Takes nothing out of the purse: the money moves at payday, once a day,
     * like every other standing cost. A dial that charged on the click would
     * let somebody run a whole week's policing out of one morning by nudging
     * it up and down.
     */
    public static void setBudget(int wanted) {
        int now = Math.max(0, Math.min(MAX_BUDGET,
                Math.round(wanted / (float) BUDGET_STEP) * BUDGET_STEP));
        if (now == budget) {
            return;
        }
        budget = now;
        save();
    }

    /** Cells across every station that passed its last inspection. */
    public static int cells() {
        int cells = 0;
        for (Station station : STATIONS) {
            if (station.open) {
                cells += station.cells;
            }
        }
        return cells;
    }

    /**
     * Officers the money is paying for today.
     *
     * Capped by cells, and the cap is deliberate: a budget with nowhere to
     * house anybody hires nobody. It is what stops the dial being the whole
     * feature and keeps the BUILDING in it.
     */
    public static int force() {
        return Math.min(cells(), funded / WAGE);
    }

    /** How well equipped they are, 0 to {@link #TOP_GEAR}. */
    public static int gear() {
        // The Watch is the city buying its own patrols, and it has bought
        // exactly one thing until now: a longer gap between pillager visits.
        // A force that is better kitted out because the city built a watch
        // house is the same purchase saying something a player can see.
        int watch = TrapCity.built(TrapCity.Work.WATCH) ? 1 : 0;
        return Math.max(0, Math.min(TOP_GEAR, funded / GEAR_AT + watch));
    }

    /**
     * Golems the city is entitled to put on the street.
     *
     * Two gates, and they are the two halves of what a city spends money on.
     * The WORKS decide how many were ever built -- a purchase at the vault,
     * levelled like the roads and the school. The FORCE decides how many of
     * them walk out today, because a golem is police property and an unpaid
     * station does not open its yard.
     *
     * Off {@link #onDuty} rather than {@link #force}: the number that matters
     * is coppers actually standing up, so a shift that got eaten in the night
     * takes its golems off the street with it until the station refills.
     *
     * @see TrapMath#golemGuard for why the second gate is there at all
     */
    public static int golems() {
        return TrapMath.golemGuard(TrapCity.level(TrapCity.Work.GOLEMS), onDuty());
    }

    /** Golems actually standing up somewhere. */
    public static int onGuard() {
        int out = 0;
        for (Station station : STATIONS) {
            out += station.golems.size();
        }
        return out;
    }

    /** Officers actually standing up somewhere, which is what the town feels. */
    public static int onDuty() {
        int out = 0;
        for (Station station : STATIONS) {
            out += station.officers.size();
        }
        return out;
    }

    /**
     * How much crime the force is holding down, 0 to {@link TrapMath#TOP_DETERRENCE}.
     *
     * Read by {@link TrapCrime} and by nothing else. Officers per head of
     * population rather than officers outright, because policing a village
     * with four coppers and policing a city with four coppers are not the same
     * job -- and a town that grows without funding its force should feel it.
     */
    public static float deterrence() {
        return TrapMath.deterrence(onDuty(), TrapHomes.population(), gear());
    }

    /**
     * Wages, once a day, out of the purse and into the town's.
     *
     * Spends DOWN rather than failing closed. See the class note: a force is
     * not a discrete bill, and a city that is 200e short should lose a copper,
     * not its police.
     */
    private static void payday(MinecraftServer server, long day) {
        if (paidOn == day) {
            return;
        }
        paidOn = day;
        int was = funded;
        funded = 0;
        if (budget <= 0 || !TrapCity.founded() || STATIONS.isEmpty()) {
            save();
            return;
        }
        int want = budget;
        while (want > 0 && !TrapCity.spend(want)) {
            want -= BUDGET_STEP;
        }
        funded = Math.max(0, want);
        if (funded > 0) {
            // Their wage is a townsperson's wage: it goes into the purse the
            // shops are paid out of, exactly like a doctor's. Credit, not
            // earned -- this is money the treasury already had.
            TrapPayroll.credit(funded);
            spent += funded;
        }
        save();
        if (funded < budget && was >= budget) {
            announce(server, TrapNotes.headline("KOMENDA BEZ PIENIĘDZY", Formatting.RED)
                    .append(TrapNotes.say("\n  Rada uchwaliła ", Formatting.GRAY))
                    .append(TrapNotes.say(budget + "e", Formatting.GOLD))
                    .append(TrapNotes.say(", a kasa dała ", Formatting.GRAY))
                    .append(TrapNotes.say(funded + "e", Formatting.RED))
                    .append(TrapNotes.say(". Na ulicy zostaje ", Formatting.GRAY))
                    .append(TrapNotes.say(force() + " funkcjonariuszy", Formatting.WHITE))
                    .append(TrapNotes.say(".", Formatting.GRAY))
                    .append(TrapNotes.under("Nieopłacony posterunek to jutrzejsze włamania.")));
        } else if (funded >= budget && was < budget && budget > 0) {
            announce(server, TrapNotes.headline("KOMENDA OPŁACONA", Formatting.GREEN)
                    .append(TrapNotes.say("   ", Formatting.GRAY))
                    .append(TrapNotes.say(force() + " funkcjonariuszy", Formatting.WHITE))
                    .append(TrapNotes.say(" wraca na ulice.", Formatting.GRAY)));
        }
    }

    private static void fallen(MinecraftServer server) {
        announce(server, Text.empty()
                .append(TrapNotes.say("Funkcjonariusz zginął na służbie.", Formatting.RED))
                .append(TrapNotes.say("  Komenda wystawi kogoś na jego miejsce.",
                        Formatting.DARK_GRAY)));
    }

    // --- the daily round ------------------------------------------------------

    /**
     * One station looked at, everybody's shift brought up to strength, and the
     * cells emptied of anybody who has done their time.
     *
     * Round-robin over the stations for the reason the houses and the wards
     * are: a survey is a flood fill and a city should not get slower as it
     * grows.
     */
    private static void rounds(MinecraftServer server) {
        long day = TrapMarket.today(server);
        payday(server, day);
        release(server, day);
        if (STATIONS.isEmpty()) {
            return;
        }
        cursor = (cursor + 1) % STATIONS.size();
        Station station = STATIONS.get(cursor);
        ServerWorld world = TrapHospitals.worldOf(server, station.dimension);
        if (world == null || !world.getChunkManager().isChunkLoaded(
                station.sign.getX() >> 4, station.sign.getZ() >> 4)) {
            return;
        }
        inspect(world, station);
        // Every station is brought to strength, not only the one being
        // surveyed: a shift that only refilled once round the register would
        // take a minute a station to come back after a bad night.
        int left = force();
        // Golems are shared out EVENLY rather than first-come, and officers
        // are not, because the two are limited by different things. A copper
        // needs a cell to sleep in, so a station with eight of them should get
        // eight; a golem is stood out on the street, so the only sensible
        // question is which parts of town are covered -- and handing them all
        // to whichever station happens to be first in the register leaves half
        // the city with none.
        int iron = golems();
        int yards = 0;
        for (Station each : STATIONS) {
            if (each.open) {
                yards++;
            }
        }
        for (Station each : STATIONS) {
            ServerWorld theirs = TrapHospitals.worldOf(server, each.dimension);
            int want = each.open ? Math.min(each.cells, left) : 0;
            left -= want;
            int guard = 0;
            if (each.open && yards > 0) {
                guard = (iron + yards - 1) / yards;   // ceiling: no golem is lost to rounding
                iron -= guard;
                yards--;
            }
            if (theirs != null && theirs.getChunkManager().isChunkLoaded(
                    each.sign.getX() >> 4, each.sign.getZ() >> 4)) {
                staff(theirs, each, want);
                muster(theirs, each, guard);
                holdCells(theirs, each);
            }
        }
    }

    /** Anybody whose time is up walks out of the front door. */
    private static void release(MinecraftServer server, long day) {
        for (Prisoner prisoner : List.copyOf(CELLS)) {
            if (day < prisoner.until) {
                continue;
            }
            CELLS.remove(prisoner);
            Station station = byId(prisoner.station);
            ServerWorld world = station == null ? null
                    : TrapHospitals.worldOf(server, station.dimension);
            if (world != null) {
                clearBody(world, prisoner.body);
                world.playSound(null, station.sign, SoundEvents.BLOCK_IRON_DOOR_OPEN,
                        SoundCategory.BLOCKS, 0.7F, 1.1F);
            }
            prisoner.body = null;
            save();
        }
    }

    // --- the shift ------------------------------------------------------------

    /**
     * Everybody who is out there, moved along one step.
     *
     * Kept off the register's round on purpose -- see {@link #BEAT_TICKS}. The
     * order matters and is the whole of the behaviour: a copper who can see a
     * suspect is chasing one, a copper who can see a zombie is fighting it,
     * and a copper who can see neither is walking the beat. Nothing else.
     */
    private static void shift(MinecraftServer server) {
        for (Station station : STATIONS) {
            if (station.officers.isEmpty() && station.golems.isEmpty()) {
                continue;
            }
            ServerWorld world = TrapHospitals.worldOf(server, station.dimension);
            if (world == null) {
                continue;
            }
            for (Patrol patrol : List.copyOf(station.officers)) {
                if (!(world.getEntity(patrol.body) instanceof VillagerEntity officer)
                        || !officer.isAlive()) {
                    station.officers.remove(patrol);
                    continue;
                }
                walk(world, station, officer, patrol);
            }
            for (Patrol patrol : List.copyOf(station.golems)) {
                if (!(world.getEntity(patrol.body) instanceof IronGolemEntity golem)
                        || !golem.isAlive()) {
                    station.golems.remove(patrol);
                    continue;
                }
                march(world, station, golem, patrol);
            }
        }
    }

    /** One officer, one decision. */
    private static void walk(ServerWorld world, Station station, VillagerEntity officer,
                             Patrol patrol) {
        steady(officer);
        int sight = SIGHT + SIGHT_PER_GEAR * gear();
        // Too far from the nick to be on any beat at all. Off the map or in
        // another world entirely gets a lift home, because a two-hundred-block
        // path is not a thing this engine will walk.
        double home = officer.getBlockPos().getSquaredDistance(station.sign);
        if (home > (double) LOST * LOST) {
            BlockPos back = TrapSpawn.near(world, station.sign.up());
            if (back != null) {
                officer.refreshPositionAndAngles(back,
                        world.getRandom().nextFloat() * 360f, 0f);
            }
            return;
        }
        if (home > (double) LEASH * LEASH) {
            TrapHomes.walkTo(officer, station.sign);
            return;
        }

        // Wide but SHORT. A copper does not chase something thirty blocks
        // above their head, and a cube of side 68 is five chunks of entity
        // sections to walk twice a second per officer for nothing.
        Box around = new Box(officer.getBlockPos()).expand(sight, 8, sight);

        // 1. Somebody wanted. This is the job.
        VillagerEntity suspect = null;
        double nearest = Double.MAX_VALUE;
        for (VillagerEntity found : world.getEntitiesByClass(VillagerEntity.class, around,
                body -> body.isAlive() && body.getCommandTags().contains(TrapCrime.SUSPECT_TAG)
                        && officer.canSee(body))) {
            double away = found.squaredDistanceTo(officer);
            if (away < nearest) {
                nearest = away;
                suspect = found;
            }
        }
        if (suspect != null) {
            if (nearest <= REACH * REACH) {
                arrest(world, station, officer, suspect);
            } else {
                chase(world, officer, suspect.getBlockPos());
            }
            return;
        }

        // 1b. Badly hurt. Off the street, back to the nick, and no fighting on
        //     the way -- a wound never heals in a fight, and an officer that
        //     keeps walking at zombies on a tenth of its health is not brave,
        //     it is a replacement being hired in thirty seconds. Suspects are
        //     still worth grabbing on the way past; monsters are not.
        if (officer.getHealth() < officer.getMaxHealth() * TrapMath.OFFICER_RETREAT) {
            patrol.beat = null;
            if (home > ARRIVED * ARRIVED) {
                walkAt(officer, station.sign, CHASE_PACE);
            }
            mend(officer);
            return;
        }

        // 2. Something with teeth. A patrol that walks past a zombie eating
        //    somebody's tenant is decoration, and the tenant is the reason the
        //    city is paying for this at all.
        //
        //    canSee, and it is the single most important word on this line.
        //    The box is 34 blocks a side at full kit, which underground means
        //    every mob in every cave under the town -- and an officer sent at
        //    one walks into the nearest wall and STAYS there, because the
        //    target never goes away. Seven of them doing that at once is the
        //    pile in the corner somebody photographed and asked why the police
        //    were not patrolling. They were not patrolling; they were laying
        //    siege to a floor.
        HostileEntity monster = null;
        nearest = Double.MAX_VALUE;
        for (HostileEntity found : world.getEntitiesByClass(HostileEntity.class, around,
                mob -> mob.isAlive() && !mob.isRemoved() && worthNicking(mob)
                        && officer.canSee(mob))) {
            double away = found.squaredDistanceTo(officer);
            if (away < nearest) {
                nearest = away;
                monster = found;
            }
        }
        if (monster != null) {
            if (nearest <= REACH * REACH) {
                truncheon(world, station, officer, monster);
            } else if (gear() >= SHOOT_AT && nearest <= SHOOT_RANGE * SHOOT_RANGE) {
                // A pillager outranges a truncheon by eight blocks. A force
                // that can only close the distance is a force that arrives
                // already shot, which is exactly what a city raid does to one.
                shoot(world, officer, monster);
                chase(world, officer, monster.getBlockPos());
            } else {
                chase(world, officer, monster.getBlockPos());
            }
            return;
        }

        // 3. Somebody worth a word. Cheap: only players actually nearby, and
        //    only ever one ticket each per STOP_COOLDOWN.
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.getBlockPos().isWithinDistance(officer.getBlockPos(), STOP_RANGE)
                    && stopAndSearch(world, officer, player)) {
                return;
            }
        }

        // 4. Nothing doing. Walk the beat, and walk it off.
        mend(officer);
        beat(world, station, officer, patrol);
    }

    /**
     * A quiet minute is a minute healing.
     *
     * The whole of why the shift kept dying. A villager regenerates from FOOD
     * and an officer carries none -- FoodLevel 0 on every one of them -- so
     * every wound taken was permanent and death was only a question of enough
     * nights. Measured live before this existed: 25, 38, 7, 21, 21 and 38 out
     * of a maximum of 38, on a shift nobody had attacked in an hour.
     *
     * Only ever off-duty: this is not called while there is anything in reach,
     * so it cannot turn a losing fight into an unlosable one.
     */
    private static void mend(VillagerEntity officer) {
        if (officer.getHealth() < officer.getMaxHealth()) {
            officer.heal(TrapMath.officerMend(gear()));
        }
    }

    /**
     * The two villager reflexes a copper cannot have, unlearned every pass.
     *
     * SLEEP, because crime is {@link TrapMath#NIGHT_CRIME}x after dark, so the
     * night shift is the only shift that matters -- and a Brain claims the
     * nearest bed as its HOME at dusk, which at a station is a CELL. Without
     * this the entire force spends the dangerous half of the day asleep in its
     * own custody suite.
     *
     * PANIC, because a villager that gets hit runs, and a villager that can
     * see something hostile runs before it is hit. An officer who charges a
     * zombie and then flees from it is not a police force, it is a joke about
     * one -- and it is what happens by default, because everything else in
     * this mod that wears a villager body is meant to be frightened.
     */
    private static void steady(VillagerEntity officer) {
        officer.wakeUp();
        var brain = officer.getBrain();
        brain.forget(net.minecraft.entity.ai.brain.MemoryModuleType.HOME);
        brain.forget(net.minecraft.entity.ai.brain.MemoryModuleType.HURT_BY);
        brain.forget(net.minecraft.entity.ai.brain.MemoryModuleType.HURT_BY_ENTITY);
        brain.forget(net.minecraft.entity.ai.brain.MemoryModuleType.NEAREST_HOSTILE);
        brain.forget(net.minecraft.entity.ai.brain.MemoryModuleType.AVOID_TARGET);
    }

    /**
     * Hurry.
     *
     * The SAME walk multiplier a suspect flees at, on purpose: it makes the
     * chase a pure race between two movement-speed attributes, which is what
     * {@link TrapMath#SUSPECT_PACE} is written to be read against. Give the
     * two sides different multipliers and the number that decides every arrest
     * in the game stops being the one the constants document.
     */
    private static void chase(ServerWorld world, VillagerEntity officer, BlockPos target) {
        officer.wakeUp();
        walkAt(officer, target, CHASE_PACE);
        if (world.getRandom().nextInt(4) == 0) {
            world.spawnParticles(ParticleTypes.CRIT, officer.getX(), officer.getY() + 1.9,
                    officer.getZ(), 2, 0.2, 0.1, 0.2, 0.01);
        }
    }

    /**
     * Where a copper goes when nothing is happening.
     *
     * ONE destination at a time, held until they get there or give up on it.
     * Re-asserted every pass because a villager Brain drops a walk target the
     * instant it has none -- but re-asserted, not re-rolled. See {@link Patrol}
     * for what re-rolling did.
     */
    private static void beat(ServerWorld world, Station station, VillagerEntity officer,
                             Patrol patrol) {
        BlockPos leg = stride(world, station, officer.getBlockPos(), patrol);
        if (leg != null) {
            walkAt(officer, leg, BEAT_PACE);
        }
    }

    /**
     * One golem, and mostly the decision to stay out of its way.
     *
     * Nothing here fights. A golem already knows how -- vanilla gives it a
     * melee goal, a revenge goal and a target goal that takes anything that is
     * a Monster and is not a creeper, which is the same deny-list
     * {@link #worthNicking} arrived at independently and for the same reason.
     * Re-implementing that on top would be two AIs arguing over one body.
     *
     * What a golem does NOT know is where a town is. Left alone it wanders ten
     * blocks at a time round wherever it was made, so an army of them is an
     * ornament in the station yard. That is the only thing this method does:
     * hand it the same round an officer walks, re-asserted every pass because
     * vanilla's own wander goal takes the navigation back the moment it is let
     * go -- the same argument {@link Patrol} settles for the villagers.
     *
     * The canSee test is the escape hatch, and it is load-bearing. Vanilla's
     * monster scan does NOT check line of sight, so a golem stood over a cave
     * has a target the whole night; if that alone bought it out of the round
     * it would stand there until morning laying siege to a floor, which is
     * exactly what the officers used to do. A monster it can SEE is a fight
     * and is left alone. A monster it cannot is a rumour, and the round wins
     * -- and if there is a path to that rumour the melee goal outranks this
     * anyway and takes the body back on its own.
     */
    private static void march(ServerWorld world, Station station, IronGolemEntity golem,
                              Patrol patrol) {
        double home = golem.getBlockPos().getSquaredDistance(station.sign);
        if (home > (double) LOST * LOST) {
            // Onto the round, not onto the sign: a lift home that lands it in
            // the lobby is the same trap {@link #street} exists to avoid.
            BlockPos back = street(world, station);
            if (back != null) {
                golem.refreshPositionAndAngles(back,
                        world.getRandom().nextFloat() * 360f, 0f);
            }
            return;
        }
        LivingEntity prey = golem.getTarget();
        if (prey != null && prey.isAlive() && golem.canSee(prey)) {
            return;
        }
        BlockPos leg;
        if (home > (double) LEASH * LEASH) {
            patrol.beat = null;
            patrol.post = null;
            leg = station.sign;
        } else {
            leg = stride(world, station, golem.getBlockPos(), patrol);
        }
        if (leg != null) {
            golem.getNavigation().startMovingTo(leg.getX() + 0.5, leg.getY(),
                    leg.getZ() + 0.5, GOLEM_PACE);
        }
    }

    /**
     * The next leg of somebody's round, officer or golem.
     *
     * Two clocks, and keeping them apart is the whole of the behaviour. The
     * LEG is re-picked on arrival, because twenty-two blocks is as far as a
     * path reliably carries. The POST -- what the walk is for -- survives that,
     * and is only re-picked when it has been REACHED or when a leg toward it
     * timed out. See {@link Patrol#post} for what re-rolling it every leg did.
     *
     * Running out of patience throws the errand away as well as the leg, on
     * purpose: a leg that expired is a leg nobody could walk, and the usual
     * reason is that the post itself is somewhere this body cannot get to.
     * Keeping it would be forty seconds of failing, forever.
     */
    private static BlockPos stride(ServerWorld world, Station station, BlockPos from,
                                   Patrol patrol) {
        long now = world.getTime();
        boolean arrived = patrol.beat != null && from.isWithinDistance(patrol.beat, ARRIVED);
        boolean gaveUp = now > patrol.by;
        if (patrol.beat == null || arrived || gaveUp) {
            if (gaveUp || patrol.post == null || from.isWithinDistance(patrol.post, AT_POST)) {
                patrol.post = null;
            }
            patrol.beat = nextLeg(world, station, from, patrol);
            patrol.by = now + BEAT_PATIENCE;
        }
        return patrol.beat;
    }

    /**
     * The next place on the round.
     *
     * Toward something worth guarding -- a house, a counter, the vault -- in
     * {@link #STRIDE}-block legs rather than in one go. The legs are not
     * tidiness: a villager path gives out somewhere past forty blocks, and a
     * target across town is a target nobody ever reaches, which reads exactly
     * like an officer who refuses to leave the doorstep.
     *
     * The errand comes from {@link Patrol#post} when there already is one, so
     * consecutive legs point the same way and the walk accumulates. The ring
     * is the last resort and nothing else: it used to be rolled four times in
     * ten whatever the town looked like, which pulled the shift back toward
     * its own front door as fast as the houses pulled it out.
     */
    private static BlockPos nextLeg(ServerWorld world, Station station, BlockPos from,
                                    Patrol patrol) {
        var random = world.getRandom();
        BlockPos anchor = patrol.post;
        if (anchor == null) {
            anchor = shoutOf(world, station);
        }
        if (anchor == null) {
            anchor = worthGuarding(world, station, random);
        }
        if (anchor == null) {
            // A point on a ring AROUND the nick, never the nick itself. Six of
            // seven officers were sent to the same block four times a minute,
            // and villagers have collision -- so the shift spent its day
            // shoving each other into the corner nearest the door.
            double angle = random.nextDouble() * Math.PI * 2;
            int out = RING_MIN + random.nextInt(RING_RANGE);
            anchor = station.sign.add((int) (Math.cos(angle) * out), 0,
                    (int) (Math.sin(angle) * out));
        }
        patrol.post = anchor;
        int dx = anchor.getX() - from.getX();
        int dz = anchor.getZ() - from.getZ();
        double away = Math.sqrt(dx * (double) dx + dz * (double) dz);
        BlockPos toward = away <= STRIDE ? anchor
                : from.add((int) (dx / away * STRIDE), 0, (int) (dz / away * STRIDE));
        // A few blocks of slop, so two officers heading the same way do not
        // walk the same line, and so a beat looks like a walk rather than a rail.
        BlockPos wanted = toward.add(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
        return ground(world, wanted, toward);
    }

    /**
     * A walk target at a chosen pace.
     *
     * {@link TrapHomes#walkTo} is fixed at 0.5, which is right for a tenant
     * crossing their own front room and wrong for everybody who has somewhere
     * to be. The float is a MULTIPLIER on the movement-speed attribute, which
     * is the number the funding actually moves.
     */
    private static void walkAt(VillagerEntity body, BlockPos target, float pace) {
        body.getBrain().remember(
                net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET,
                new net.minecraft.entity.ai.brain.WalkTarget(target, pace, 1));
    }

    /**
     * A standable spot at ground level, or the fallback if there isn't one.
     *
     * The chunk check is not tidiness. {@code getTopPosition} on an unloaded
     * chunk force-generates terrain -- from a patrol tick, four times a
     * second, at whatever range the beat happens to reach -- which is the trap
     * {@link TrapHeat#findSpot} already carries a comment about.
     */
    static BlockPos ground(ServerWorld world, BlockPos wanted, BlockPos fallback) {
        if (!world.isChunkLoaded(wanted.getX() >> 4, wanted.getZ() >> 4)) {
            return fallback;
        }
        // Around the target's own level, NOT the top of its column. A
        // heightmap lookup is the right answer in a field and the wrong one in
        // a town: aimed at a house, at a shop, or at the station's own doorway
        // it returns the ROOF -- so every leg of the beat was a walk to a spot
        // the officer could not path to, and the whole shift stood still.
        BlockPos spot = TrapSpawn.near(world, wanted, 6);
        return spot == null ? fallback : spot;
    }

    /**
     * Something in this world the town would rather kept its windows, and
     * close enough to this station that walking there is not a punishment.
     *
     * The range test is not a detail. Without it the errand was drawn from
     * every house and counter on the server, so a station on one side of a big
     * town spent its nights sending people at addresses on the other side --
     * past the leash, back again, and out again. See {@link #BEAT_REACH}.
     */
    private static BlockPos worthGuarding(ServerWorld world, Station station,
                                          net.minecraft.util.math.random.Random random) {
        String here = world.getRegistryKey().getValue().toString();
        List<BlockPos> spots = new ArrayList<>();
        for (TrapHomes.Home home : TrapHomes.all()) {
            if (home.dimension().equals(here) && home.tenant() != null
                    && onTheRound(station, home.anchor())) {
                spots.add(home.anchor());
            }
        }
        for (TrapShops.Shop shop : TrapShops.shops()) {
            if (shop.dimension.equals(here) && onTheRound(station, shop.pos())) {
                spots.add(shop.pos());
            }
        }
        if (TrapCity.founded() && here.equals(TrapCity.vaultWorld())
                && onTheRound(station, TrapCity.vaultAt())) {
            spots.add(TrapCity.vaultAt());
        }
        return spots.isEmpty() ? null : spots.get(random.nextInt(spots.size()));
    }

    /** Is this address on this station's round at all? */
    private static boolean onTheRound(Station station, BlockPos spot) {
        return spot != null
                && spot.getSquaredDistance(station.sign) <= (double) BEAT_REACH * BEAT_REACH;
    }

    /** A live call-out, or null once it has run its time. */
    private static BlockPos shoutOf(ServerWorld world, Station station) {
        if (station.shout == null) {
            return null;
        }
        if (world.getTime() > station.shoutBy) {
            station.shout = null;
            return null;
        }
        return station.shout;
    }

    /**
     * Somebody reports something, and the shift walks at it.
     *
     * <h2>What this was for</h2>
     *
     * A pillager raid was the one pressure in this mod that the city could not
     * answer. {@link TrapCrime} has a whole pipeline -- offence, suspect,
     * officer, arrest, restitution -- and a raid had none of it: four
     * pillagers turned up at somebody's farm, took an armful of product and
     * left, and the force the council was funding never heard about it. The
     * town was paying for a police station that policed the town's own
     * burglars and was blind to armed robbery.
     *
     * The officers already knew how to FIGHT a pillager -- see the hostile
     * branch in {@link #walk}, which even shoots at range once the gear is
     * paid for. What they had no way of knowing was where to be. So this is
     * not a new behaviour, it is an address: the round is pointed at the
     * trouble and everything downstream is the shift doing what it already
     * does.
     *
     * <h2>Why it is a post and not a teleport</h2>
     *
     * Because the answer has to be able to arrive too late, or the building
     * and the budget mean nothing. A call-out sets the same {@link Patrol#post}
     * a beat uses, so the shift WALKS -- at whatever pace the gear bought --
     * and a raid four minutes' walk away is over before anybody gets there.
     * That is the fact that makes a nick near the farms worth building.
     *
     * <h2>Not free</h2>
     *
     * Worth knowing before you call them: an officer stood in your grow is an
     * officer within {@link #STOP_RANGE} of you, and a stop-and-search does
     * not care that you are the one who rang. Getting rescued by the police
     * while carrying is its own decision, and it is meant to be.
     *
     * @return true if a station heard it and somebody is on their way
     */
    public static boolean callOut(ServerWorld world, BlockPos where) {
        if (where == null) {
            return false;
        }
        String here = world.getRegistryKey().getValue().toString();
        Station nearest = null;
        double best = Double.MAX_VALUE;
        for (Station station : STATIONS) {
            if (!station.dimension.equals(here) || !station.open
                    || (station.officers.isEmpty() && station.golems.isEmpty())
                    || !onTheRound(station, where)) {
                continue;
            }
            double away = where.getSquaredDistance(station.sign);
            if (away < best) {
                best = away;
                nearest = station;
            }
        }
        if (nearest == null) {
            // No nick in range, none open, or none funded well enough to have
            // anybody standing up in it. All three are the same answer to the
            // player and all three are a thing the council can fix.
            return false;
        }
        nearest.shout = where.toImmutable();
        nearest.shoutBy = world.getTime() + SHOUT_TICKS;
        // Everybody drops the leg they were on. Without this the shout waits
        // for each officer to finish walking to wherever they were already
        // going, which on a fresh post is BEAT_PATIENCE -- forty seconds of
        // the force strolling away from the thing it was just told about.
        for (Patrol patrol : nearest.officers) {
            patrol.post = null;
            patrol.beat = null;
        }
        for (Patrol patrol : nearest.golems) {
            patrol.post = null;
            patrol.beat = null;
        }
        world.playSound(null, nearest.sign, SoundEvents.BLOCK_BELL_USE,
                SoundCategory.NEUTRAL, 1.0F, 1.4F);
        ServerPlayerEntity boss = world.getServer().getPlayerManager().getPlayer(nearest.owner);
        if (boss != null) {
            boss.sendMessage(TrapNotes.headline("Zgłoszenie  ", Formatting.AQUA)
                    .append(TrapNotes.say((nearest.name == null ? "komenda" : nearest.name)
                            + " wysyła patrol na " + where.getX() + " " + where.getY()
                            + " " + where.getZ(), Formatting.GRAY)), false);
        }
        return true;
    }

    // --- the two things they do -----------------------------------------------

    /**
     * Things a beat copper walks past.
     *
     * A creeper punched next to somebody's shop takes the shop with it, an
     * enderman punched anywhere is a fight that follows you home, and a warden
     * is not a policing problem. This is a short deny-list rather than a long
     * allow-list on purpose: the ordinary night mobs are the whole point, and
     * a new one added by a mod or by Mojang should be answered by default
     * rather than quietly ignored until somebody notices.
     */
    private static boolean worthNicking(HostileEntity mob) {
        return !(mob instanceof net.minecraft.entity.mob.CreeperEntity)
                && !(mob instanceof net.minecraft.entity.mob.EndermanEntity)
                && !(mob instanceof net.minecraft.entity.mob.WardenEntity)
                && !(mob instanceof net.minecraft.entity.boss.WitherEntity);
    }

    /**
     * A bolt, aimed and fired by hand.
     *
     * One per decision pass, so the cadence is the pass -- a second and a half
     * between shots, which is about what a crossbow does anyway and needs no
     * cooldown of its own to say so.
     *
     * The arc is the usual one: aim at the middle of the target and add a
     * fifth of the flat distance to the vertical, because an arrow falls.
     */
    private static void shoot(ServerWorld world, VillagerEntity officer,
                              net.minecraft.entity.LivingEntity target) {
        var arrow = new net.minecraft.entity.projectile.ArrowEntity(world, officer,
                new net.minecraft.item.ItemStack(net.minecraft.item.Items.ARROW), null);
        double dx = target.getX() - officer.getX();
        double dy = target.getBodyY(0.4) - arrow.getY();
        double dz = target.getZ() - officer.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        arrow.setVelocity(dx, dy + flat * 0.2, dz, 1.7F, 5.0F);
        arrow.setDamage(TrapMath.boltHit(gear()));
        officer.swingHand(Hand.MAIN_HAND);
        officer.lookAtEntity(target, 30f, 30f);
        world.spawnEntity(arrow);
        world.playSound(null, officer.getBlockPos(), SoundEvents.ITEM_CROSSBOW_SHOOT,
                SoundCategory.NEUTRAL, 0.9F, 1.0F);
    }

    /** A monster, hit. Villagers have no attack of their own, so this is it. */
    private static void truncheon(ServerWorld world, Station station,
                                  VillagerEntity officer, HostileEntity monster) {
        officer.swingHand(Hand.MAIN_HAND);
        officer.lookAtEntity(monster, 30f, 30f);
        monster.damage(world, world.getDamageSources().mobAttack(officer),
                TrapMath.truncheonHit(gear()));
        world.playSound(null, officer.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                SoundCategory.NEUTRAL, 0.8F, 1.0F);
        world.spawnParticles(ParticleTypes.CRIT, monster.getX(), monster.getY() + 1.0,
                monster.getZ(), 6, 0.2, 0.2, 0.2, 0.05);
        if (!monster.isAlive()) {
            station.calls++;
            world.playSound(null, officer.getBlockPos(), SoundEvents.ENTITY_VILLAGER_CELEBRATE,
                    SoundCategory.NEUTRAL, 0.6F, 1.1F);
            save();
        }
    }

    /**
     * The cuffs.
     *
     * The case is closed by {@link TrapCrime}, not here: this file knows how to
     * catch somebody and nothing at all about what they did, which is what
     * keeps a burglary's restitution in one place instead of two.
     */
    private static void arrest(ServerWorld world, Station station,
                               VillagerEntity officer, VillagerEntity suspect) {
        String name = suspect.getCustomName() == null ? "Nieznany sprawca"
                : suspect.getCustomName().getString();
        TrapCrime.Charge charge = TrapCrime.caught(world, suspect);
        if (charge == null) {
            return;   // not a live case any more; somebody else got there first
        }
        station.arrests++;
        officer.swingHand(Hand.MAIN_HAND);
        world.playSound(null, suspect.getBlockPos(), SoundEvents.BLOCK_CHAIN_PLACE,
                SoundCategory.NEUTRAL, 1.0F, 0.8F);
        world.playSound(null, suspect.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO,
                SoundCategory.NEUTRAL, 0.9F, 0.8F);
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, suspect.getX(),
                suspect.getY() + 1.6, suspect.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
        suspect.discard();

        long day = TrapMarket.today(world.getServer());
        boolean cell = station.free() > 0;
        if (cell) {
            Prisoner prisoner = new Prisoner(name, charge.crime(), station.id,
                    day + charge.days());
            CELLS.add(prisoner);
            standIn(world, station, prisoner);
        }
        save();
        ServerPlayerEntity boss = world.getServer().getPlayerManager().getPlayer(station.owner);
        if (boss != null) {
            TrapAwards.grant(boss, "collar");
            TrapWaypoints.offer(boss, "Zatrzymanie", suspect.getBlockPos(),
                    TrapWaypoints.GREEN);
        }
        var note = TrapNotes.headline("ZATRZYMANIE", Formatting.AQUA)
                .append(TrapNotes.say("   " + name, Formatting.WHITE))
                .append(TrapNotes.say("   " + charge.crime(), Formatting.RED))
                .append(TrapNotes.say("\n  " + station.name, Formatting.DARK_GRAY))
                .append(cell
                        ? TrapNotes.say("   " + charge.days() + " dni w celi",
                                Formatting.GRAY)
                        : TrapNotes.say("   cele pełne, wyszedł za kaucją",
                                Formatting.YELLOW));
        if (charge.restitution() > 0) {
            note.append(TrapNotes.say("   odzyskano ", Formatting.DARK_GRAY))
                    .append(TrapNotes.say(charge.restitution() + "e", Formatting.GREEN));
        } else if (TrapCourt.any()) {
            // Nothing came back at the kerb because there is a bench in town
            // now. Said out loud, or a collar with no money next to it reads
            // as the arrest having gone wrong.
            note.append(TrapNotes.say("   sprawa idzie do sądu", Formatting.GOLD));
        }
        announce(world.getServer(), note);
    }

    /**
     * A word with somebody who is carrying.
     *
     * Deliberately NOT an arrest, and deliberately not a confiscation. A copper
     * who could take a player's stash or lock them up would be a mod that plays
     * itself; a copper who writes a ticket is a running cost with a face on it,
     * and the counterplay -- wash the money, keep the heat down, stay off the
     * high street with eight ounces in your pocket -- is the game this mod
     * already asks you to play.
     *
     * @return true if a ticket was written, so the officer's turn is used up
     */
    private static boolean stopAndSearch(ServerWorld world, VillagerEntity officer,
                                         ServerPlayerEntity player) {
        Long last = STOPPED.get(player.getUuid());
        if (last != null && world.getTime() - last < STOP_COOLDOWN) {
            return false;
        }
        int carried = contraband(player);
        int heat = TrapHeat.carryingHeat(player);
        int owed = TrapLaw.owedBy(player);
        int fine = TrapMath.ticket(carried, heat, owed, LOOKS_AWAY);
        if (fine <= 0) {
            return false;
        }
        STOPPED.put(player.getUuid(), world.getTime());
        officer.lookAtEntity(player, 30f, 30f);
        int held = TrapMarket.wealthOf(player);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
                SoundCategory.PLAYERS, 0.7F, 0.6F);
        if (held < fine) {
            // Nothing is ever charged that cannot be paid -- TrapCity's rule,
            // and for the same reason: this mod has no concept of a debt to
            // the police. An unpayable ticket becomes attention instead.
            TrapHeat.addCarriedHeat(player, 1, 20 * 60 * 4);
            player.sendMessage(TrapNotes.headline("KONTROLA", Formatting.RED)
                    .append(TrapNotes.say("   mandat ", Formatting.GRAY))
                    .append(TrapNotes.say(fine + "e", Formatting.GOLD))
                    .append(TrapNotes.say(", a masz przy sobie ", Formatting.GRAY))
                    .append(TrapNotes.say(held + "e", Formatting.RED))
                    .append(TrapNotes.under("Nic nie zabrali. Zapisali cię sobie.")), false);
            return true;
        }
        TrapMarket.collect(player, fine);
        TrapCity.receive(fine, TrapCity.Duty.INCOME);
        TrapLedger.record(player, TrapLedger.Source.TAX, -fine);
        fines += fine;
        save();
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, officer.getX(),
                officer.getY() + 1.9, officer.getZ(), 6, 0.2, 0.2, 0.2, 0.01);
        player.sendMessage(TrapNotes.headline("KONTROLA", Formatting.RED)
                .append(TrapNotes.say("   -" + fine + "e", Formatting.GOLD, Formatting.BOLD))
                .append(TrapNotes.say("   " + (carried >= LOOKS_AWAY
                        ? "za to, co masz w kieszeniach"
                        : owed > 0 ? "urząd cię szuka"
                        : "wyglądasz na zajętego"), Formatting.GRAY)), false);
        return true;
    }

    /**
     * How much of what a player is carrying a copper would not like.
     *
     * Through {@link TrapContent#isContraband}, which is the mod's one
     * definition of "product" and the same one a raid empties a chest by.
     * This file had its own hand-typed list for exactly one version, and it
     * missed the blend line -- so a Trinity joint, the most valuable thing
     * anybody rolls, was legally a stick as far as the police were concerned.
     * Reported live as "mając trinity joint w łapie nic mi nie zrobili".
     *
     * Dirty emeralds are added on top rather than folded into that: they are
     * money, not product, and a raid should not be confiscating somebody's
     * wallet -- but a pocketful of them is still the thing a copper stops you
     * for.
     */
    private static int contraband(ServerPlayerEntity player) {
        int found = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (TrapContent.isContraband(stack)
                    || stack.getItem() == TrapContent.dirtyEmerald) {
                found += stack.getCount();
            } else if (stack.getItem() == TrapContent.dirtyEmeraldBlockItem) {
                found += stack.getCount() * 9;
            }
        }
        return found;
    }

    // --- the bodies -----------------------------------------------------------

    /** Bring one station's shift to the strength the budget bought. */
    private static void staff(ServerWorld world, Station station, int want) {
        String tag = OFFICER_TAG + "_" + station.id;
        List<? extends VillagerEntity> standing = world.getEntitiesByType(
                EntityType.VILLAGER,
                found -> found.isAlive() && found.getCommandTags().contains(tag));
        // Carried across rather than rebuilt. This runs every round -- twelve
        // seconds -- and a fresh Patrol is a forgotten destination, which puts
        // the officer back to picking a new one mid-walk. The oscillation this
        // whole class exists to stop, at a twelfth of the speed.
        Map<UUID, Patrol> was = new HashMap<>();
        for (Patrol patrol : station.officers) {
            was.put(patrol.body, patrol);
        }
        station.officers.clear();
        for (VillagerEntity body : standing) {
            if (station.officers.size() < want) {
                station.officers.add(was.getOrDefault(body.getUuid(),
                        new Patrol(body.getUuid())));
                kit(body);
            } else {
                // Off shift: the budget came down, or the station closed.
                world.spawnParticles(ParticleTypes.POOF, body.getX(), body.getY() + 1.0,
                        body.getZ(), 6, 0.2, 0.3, 0.2, 0.01);
                body.discard();
            }
        }
        for (int missing = want - station.officers.size(); missing > 0; missing--) {
            VillagerEntity fresh = hire(world, station);
            if (fresh == null) {
                return;   // nowhere to stand right now; the next round tries again
            }
            station.officers.add(new Patrol(fresh.getUuid()));
        }
    }

    /**
     * Bring one station's yard to the strength the works bought.
     *
     * {@link #staff}'s shape exactly, down to carrying the Patrol across so a
     * golem halfway along a leg is not handed a fresh destination every twelve
     * seconds. Kept as a second method rather than made generic over the two:
     * the bodies share a register and a round and nothing else -- one is hired
     * against a wage and a cell, the other is unlocked against a public work,
     * and folding them together would mean a type parameter carrying two
     * unrelated staffing rules through every line.
     */
    private static void muster(ServerWorld world, Station station, int want) {
        String tag = GOLEM_TAG + "_" + station.id;
        List<? extends IronGolemEntity> standing = world.getEntitiesByType(
                EntityType.IRON_GOLEM,
                found -> found.isAlive() && found.getCommandTags().contains(tag));
        Map<UUID, Patrol> was = new HashMap<>();
        for (Patrol patrol : station.golems) {
            was.put(patrol.body, patrol);
        }
        station.golems.clear();
        for (IronGolemEntity body : standing) {
            if (station.golems.size() < want) {
                station.golems.add(was.getOrDefault(body.getUuid(),
                        new Patrol(body.getUuid())));
                plate(body);
                unwall(world, station, body);
            } else {
                // Stood down: the works have not been bought, or the station
                // that owned this one lost its shift. No drop, because a golem
                // taken off the register was never made out of anybody's iron.
                world.spawnParticles(ParticleTypes.POOF, body.getX(), body.getY() + 1.2,
                        body.getZ(), 10, 0.4, 0.5, 0.4, 0.01);
                body.discard();
            }
        }
        for (int missing = want - station.golems.size(); missing > 0; missing--) {
            IronGolemEntity fresh = forge(world, station);
            if (fresh == null) {
                return;   // nowhere to stand right now; the next round tries again
            }
            station.golems.add(new Patrol(fresh.getUuid()));
        }
    }

    private static IronGolemEntity forge(ServerWorld world, Station station) {
        var random = world.getRandom();
        BlockPos stand = street(world, station);
        if (stand == null) {
            return null;   // nowhere outdoors tonight; the next round tries again
        }
        IronGolemEntity golem = EntityType.IRON_GOLEM.create(world, SpawnReason.EVENT);
        if (golem == null) {
            return null;
        }
        golem.refreshPositionAndAngles(stand, random.nextFloat() * 360f, 0f);
        golem.setPersistent();
        golem.addCommandTag(GOLEM_TAG);
        golem.addCommandTag(GOLEM_TAG + "_" + station.id);
        plate(golem);
        world.spawnEntity(golem);
        world.playSound(null, stand, SoundEvents.BLOCK_ANVIL_USE,
                SoundCategory.BLOCKS, 0.7F, 0.6F);
        world.spawnParticles(ParticleTypes.END_ROD, stand.getX() + 0.5, stand.getY() + 1.2,
                stand.getZ() + 0.5, 14, 0.4, 0.6, 0.4, 0.02);
        return golem;
    }

    /**
     * A square out in the town a golem can be stood on -- and got off again.
     *
     * This is the whole of the bug, and it is not cosmetic. A golem is 1.4
     * blocks wide, so a one-block doorway is a wall to it, and it cannot open
     * a door either. The station's sign is INSIDE the station -- the
     * inspection is a flood fill from it -- so a search seven blocks round the
     * sign stands the city's entire garrison in the lobby, where it is walled
     * in for good. Live server: five golems in the front corridor of the nick
     * and a town with no patrol at all.
     *
     * So the address comes from the ROUND rather than the yard: the same
     * houses, counters and vault {@link #worthGuarding} sends the beat to, one
     * rolled per golem, which is what puts them across different parts of town
     * instead of in one heap. The square is then the nearest one to that
     * address with sky over it.
     *
     * Sky is the indoor test because it is the only cheap one that is right.
     * {@code isSkyVisible} is sky light 15, and sky light drops a level per
     * step through a doorway -- so it refuses a spot one step inside somebody's
     * front room, which a headroom check happily accepts.
     */
    private static BlockPos street(ServerWorld world, Station station) {
        var random = world.getRandom();
        for (int tries = 0; tries < GOLEM_TRIES; tries++) {
            BlockPos address = worthGuarding(world, station, random);
            if (address == null) {
                // Nothing registered near this nick: a ring round it, on a
                // fresh bearing each try, for {@link #nextLeg}'s reason.
                double angle = random.nextDouble() * Math.PI * 2;
                int out = RING_MIN + random.nextInt(RING_RANGE);
                address = station.sign.add((int) (Math.cos(angle) * out), 0,
                        (int) (Math.sin(angle) * out));
            }
            // Loaded chunks only. The search reads blockstates, and reading
            // one out there force-generates terrain from a tick -- the trap
            // {@link #ground} already carries a comment about.
            if (!world.isChunkLoaded(address.getX() >> 4, address.getZ() >> 4)) {
                continue;
            }
            BlockPos stand = BlockPos.findClosest(address, GOLEM_SPREAD, GOLEM_RISE,
                            spot -> TrapSpawn.safe(world, spot, GOLEM_TALL)
                                    && outdoors(world, spot))
                    .map(BlockPos::toImmutable)
                    .orElse(null);
            if (stand != null) {
                return stand;
            }
        }
        return null;
    }

    /** Sky over it -- in a world that has a sky. */
    private static boolean outdoors(ServerWorld world, BlockPos pos) {
        // The nether has no sky light anywhere, so the test would refuse every
        // square in the dimension and a nick down there would silently never
        // muster. Under a bedrock ceiling the question has no answer worth
        // asking anyway.
        return !world.getDimension().hasSkyLight() || world.isSkyVisible(pos);
    }

    /**
     * A golem that got walled in, stood back out on the street.
     *
     * Every golem the city built before {@link #street} existed was made
     * within seven blocks of the sign, and {@link #muster} re-adopts anything
     * alive and tagged -- so without this the broken ones outlive the fix and
     * stand in that corridor forever.
     *
     * Only ones still at the NICK are moved. A golem under a tree halfway
     * across town fails the sky test too, and yanking one off its own beat
     * every twelve seconds would be the worse bug.
     */
    private static void unwall(ServerWorld world, Station station, IronGolemEntity golem) {
        BlockPos stood = golem.getBlockPos();
        if (!stood.isWithinDistance(station.sign, WALLED_IN) || outdoors(world, stood)) {
            return;
        }
        BlockPos out = street(world, station);
        if (out == null) {
            return;
        }
        golem.refreshPositionAndAngles(out, world.getRandom().nextFloat() * 360f, 0f);
        world.spawnParticles(ParticleTypes.END_ROD, out.getX() + 0.5, out.getY() + 1.2,
                out.getZ() + 0.5, 10, 0.4, 0.6, 0.4, 0.02);
    }

    /**
     * What the city does to a golem, re-applied every round.
     *
     * Two things, and the first is the one that decides whether any of this
     * works at all. playerCreated is what stops a golem turning on a PLAYER:
     * {@code IronGolemEntity.canTarget} refuses EntityType.PLAYER outright
     * once it is set, checked against 1.21.8's own bytecode rather than
     * assumed. Without it these are village golems, and village golems remember
     * who hit a villager -- so the first bar fight in town would end with the
     * city's own army executing a resident over a shove.
     *
     * The follow range is the other, and {@link #GOLEM_RANGE} says why.
     * Re-applied every pass rather than only at spawn for {@link #kit}'s
     * reason: a body that outlived a restart is a body carrying whatever it
     * was born with.
     */
    private static void plate(IronGolemEntity golem) {
        golem.setPlayerCreated(true);
        var range = golem.getAttributeInstance(EntityAttributes.FOLLOW_RANGE);
        if (range == null) {
            TrapCraft.LOGGER.warn("a golem has no follow range -- it cannot be sent "
                    + "anywhere and will stand in the yard");
        } else if (range.getBaseValue() != GOLEM_RANGE) {
            range.setBaseValue(GOLEM_RANGE);
        }
        // Seeded off the body's own uuid, like a copper's name, so the same
        // golem keeps the same number for as long as it is standing.
        String plate = "GOLEM KM-" + String.format("%02d",
                Math.floorMod(golem.getUuid().getLeastSignificantBits(), 100));
        if (golem.getCustomName() == null
                || !plate.equals(golem.getCustomName().getString())) {
            golem.setCustomName(Text.literal(plate).formatted(Formatting.GRAY));
            golem.setCustomNameVisible(true);
        }
    }

    private static VillagerEntity hire(ServerWorld world, Station station) {
        // Scattered, not stacked. A whole shift spawned inside one four-block
        // radius is seven bodies with collision standing on each other before
        // any of them has been given anywhere to go.
        var random = world.getRandom();
        BlockPos stand = null;
        for (int tries = 0; tries < 6 && stand == null; tries++) {
            stand = TrapSpawn.near(world, station.sign.up()
                    .add(random.nextInt(11) - 5, 0, random.nextInt(11) - 5), 4);
        }
        if (stand == null) {
            stand = TrapSpawn.near(world, station.sign.up());
        }
        if (stand == null) {
            return null;
        }
        VillagerEntity officer = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (officer == null) {
            return null;
        }
        officer.refreshPositionAndAngles(stand, world.getRandom().nextFloat() * 360f, 0f);
        officer.setPersistent();
        officer.setCustomNameVisible(true);
        officer.addCommandTag(OFFICER_TAG);
        officer.addCommandTag(OFFICER_TAG + "_" + station.id);
        // ARMORER, and it is the closest thing vanilla has to a uniform: a
        // dark tunic with a visor over the face. The doctor took CLERIC for
        // the same reason -- a coat that reads at twenty blocks in the dark.
        // Anything else on this list is an apron.
        officer.setVillagerData(officer.getVillagerData().withProfession(
                world.getRegistryManager().getOrThrow(RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(VillagerProfession.ARMORER)));
        kit(officer);
        world.spawnEntity(officer);
        world.spawnParticles(ParticleTypes.END_ROD, stand.getX() + 0.5, stand.getY() + 1.0,
                stand.getZ() + 0.5, 8, 0.3, 0.4, 0.3, 0.01);
        return officer;
    }

    /**
     * What the budget does to a body, re-applied every round.
     *
     * On every pass rather than only at hire, for the reason the crew's kit
     * is: the dial moves while somebody's chunk is asleep, and an officer who
     * only ever learned the speed they were born with would be a copper on
     * last week's budget forever.
     */
    private static void kit(VillagerEntity officer) {
        int gear = gear();
        // The rank is the only thing on the street that says what the budget
        // is, so it has to follow the budget. Seeded off the body's own uuid
        // rather than stored, so the PERSON stays the same person across a
        // promotion -- a copper who was Wren yesterday is still Wren today.
        String rank = RANKS[Math.min(gear, RANKS.length - 1)] + " "
                + TrapHomes.nameFor((int) officer.getUuid().getLeastSignificantBits());
        if (officer.getCustomName() == null
                || !rank.equals(officer.getCustomName().getString())) {
            officer.setCustomName(Text.literal(rank).formatted(Formatting.AQUA));
        }
        var speed = officer.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        double wanted = TrapMath.officerPace(gear);
        if (speed != null && speed.getBaseValue() != wanted) {
            speed.setBaseValue(wanted);
        }
        var health = officer.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        double tough = TrapMath.officerHealth(gear);
        if (health != null && health.getBaseValue() != tough) {
            health.setBaseValue(tough);
            officer.setHealth((float) tough);
        }
        set(officer, EntityAttributes.ARMOR, TrapMath.officerArmour(gear));
        // Visible, and only visible: the villager renderer draws a main-hand
        // item, and nothing in a villager knows what a crossbow is for. The
        // shot comes out of shoot(), the swing out of truncheon(). Drop chance
        // zeroed so a dead copper does not litter the street with crossbows.
        var arm = new net.minecraft.item.ItemStack(gear >= SHOOT_AT
                ? net.minecraft.item.Items.CROSSBOW : net.minecraft.item.Items.STICK);
        if (!net.minecraft.item.ItemStack.areItemsEqual(
                officer.getEquippedStack(net.minecraft.entity.EquipmentSlot.MAINHAND), arm)) {
            officer.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, arm);
            officer.setEquipmentDropChance(net.minecraft.entity.EquipmentSlot.MAINHAND, 0f);
        }
        // Not decoration. A villager knocked back is a villager out of its own
        // reach, which turns a fight it wins into a fight it chases across a
        // field taking hits the whole way.
        set(officer, EntityAttributes.KNOCKBACK_RESISTANCE, 0.25 + 0.15 * gear);
    }

    /**
     * One attribute, set once, loudly if the entity has not got it.
     *
     * getAttributeInstance returns null for an attribute an entity's type was
     * never given, and a silent null here is a vest nobody is wearing that
     * every readout still claims they are. Armour in particular is not on
     * every mob's default container, and "they still die" would have been the
     * only symptom.
     */
    private static void set(VillagerEntity officer,
                            net.minecraft.registry.entry.RegistryEntry<
                                    net.minecraft.entity.attribute.EntityAttribute> which,
                            double value) {
        var attribute = officer.getAttributeInstance(which);
        if (attribute == null) {
            TrapCraft.LOGGER.warn("an officer has no {} attribute -- that half of the kit "
                    + "is doing nothing", which.getIdAsString());
            return;
        }
        if (attribute.getBaseValue() != value) {
            attribute.setBaseValue(value);
        }
    }

    /** Everybody who should be in a cell in this one, actually in it. */
    private static void holdCells(ServerWorld world, Station station) {
        for (Prisoner prisoner : CELLS) {
            if (station.id.equals(prisoner.station)) {
                standIn(world, station, prisoner);
            }
        }
    }

    /** The body in the cell, if the station is awake to receive it. */
    private static void standIn(ServerWorld world, Station station, Prisoner prisoner) {
        if (prisoner.body != null && world.getEntity(prisoner.body) != null) {
            return;
        }
        BlockPos stand = TrapSpawn.near(world, station.sign.up());
        if (stand == null) {
            return;
        }
        VillagerEntity body = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (body == null) {
            return;
        }
        body.refreshPositionAndAngles(stand, world.getRandom().nextFloat() * 360f, 0f);
        body.setPersistent();
        body.setCustomName(Text.literal(prisoner.who).formatted(Formatting.DARK_GRAY));
        body.setCustomNameVisible(true);
        body.addCommandTag(PRISONER_TAG);
        body.addCommandTag(PRISONER_TAG + "_" + station.id);
        // NITWIT for the reason every other body in this mod is one: anything
        // else takes a job off the nearest workstation and starts trading.
        body.setVillagerData(body.getVillagerData().withProfession(
                world.getRegistryManager().getOrThrow(RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(VillagerProfession.NITWIT)));
        // Slow, because they are not going anywhere. Amplifier 0 for the
        // reason a patient's is: two levels reads as a bug rather than as a
        // person who has given up.
        body.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.SLOWNESS, Integer.MAX_VALUE, 0,
                false, false, false));
        world.spawnEntity(body);
        prisoner.body = body.getUuid();
    }

    private static void clearBody(ServerWorld world, UUID body) {
        if (body == null) {
            return;
        }
        for (ServerWorld other : world.getServer().getWorlds()) {
            var found = other.getEntity(body);
            if (found != null) {
                found.discard();
            }
        }
    }

    private static Station stationOf(MobEntity body, String tag) {
        for (Station station : STATIONS) {
            if (body.getCommandTags().contains(tag + "_" + station.id)) {
                return station;
            }
        }
        return null;
    }

    private static Prisoner prisonerOf(UUID body) {
        for (Prisoner prisoner : CELLS) {
            if (body.equals(prisoner.body)) {
                return prisoner;
            }
        }
        return null;
    }

    private static int held(UUID station) {
        int taken = 0;
        for (Prisoner prisoner : CELLS) {
            if (station.equals(prisoner.station)) {
                taken++;
            }
        }
        return taken;
    }

    // --- the building ---------------------------------------------------------

    public static Station byId(UUID id) {
        for (Station station : STATIONS) {
            if (station.id.equals(id)) {
                return station;
            }
        }
        return null;
    }

    /** The station whose block is standing at this spot. */
    public static Station at(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Station station : STATIONS) {
            if (pos.equals(station.sign) && station.dimension.equals(dimension)) {
                return station;
            }
        }
        return null;
    }

    /** The same walk the mailbox and the ward take, graded against this list. */
    public static TrapHomes.Readout look(ServerWorld world, BlockPos pos) {
        return TrapHomes.look(world, pos, null);
    }

    /** Every reason this room is not a station, in the order to fix them. */
    public static String fault(TrapHomes.Readout reading) {
        if (reading.clash()) {
            return "To wnętrze domu już zapisanego w rejestrze. "
                    + "Komisariat potrzebuje własnego budynku.";
        }
        // Buried BEFORE leaky, and the order is the whole point. Both come
        // back as "not sealed" and they are opposite problems: one is a hole,
        // the other is no hole at all. Reported as the same thing, a sealed
        // room with glass in the windows gets told to go and find a draught
        // that does not exist -- which is exactly what happened the first time
        // anybody built one of these.
        if (reading.buried()) {
            return "Blok komisariatu stoi w litym bloku. Postaw go w POWIETRZU "
                    + "wewnątrz pomieszczenia, nie w ścianie.";
        }
        if (!reading.sealed()) {
            return "Jest dziura -- ucieka na " + where(reading.leak())
                    + ", licząc od " + where(reading.measuredFrom()) + ".";
        }
        if (reading.exits() == 0) {
            return "Nie ma wejścia. Potrzebne drzwi na zewnątrz.";
        }
        if (reading.floor() < MIN_FLOOR) {
            return "Masz " + reading.floor() + " kratek podłogi. Komisariat potrzebuje "
                    + MIN_FLOOR + ".";
        }
        if (reading.beds() < MIN_CELLS) {
            return reading.beds() == 0
                    ? "Nie ma cel. Każde łóżko w środku to jedna cela -- i jeden "
                    + "funkcjonariusz, którego ma gdzie zakwaterować."
                    : "Jedna cela to za mało. Komisariat potrzebuje " + MIN_CELLS + ".";
        }
        if (reading.dark() > 0) {
            return "Ciemnych kątów: " + reading.dark() + ". Na komendzie pali się "
                    + "światło całą noc.";
        }
        if (reading.finished() < MIN_SHELL) {
            return "Wykończenie " + Math.round(reading.finished() * 100) + "%. Komisariat "
                    + "musi mieć " + Math.round(MIN_SHELL * 100) + "%, a ten jest głównie z: "
                    + reading.roughest() + ".";
        }
        if (!reading.storage()) {
            return "Nie ma zbrojowni. Potrzebna skrzynia albo beczka.";
        }
        return null;
    }

    /**
     * Where something is, in words.
     *
     * A leak with no coordinates is a leak nobody can go and plug -- the whole
     * value of the flood fill knowing where it escaped is lost the moment the
     * checklist reports only that it did.
     */
    static String where(BlockPos pos) {
        return pos == null ? "nieznane miejsce"
                : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /**
     * Put the room this block is standing in on the books.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String found(ServerPlayerEntity who, ServerWorld world, BlockPos pos) {
        if (at(world, pos) != null) {
            return "To już jest komisariat.";
        }
        if (!TrapCity.founded()) {
            return "Nie ma jeszcze miasta -- nie ma kto płacić funkcjonariuszom. "
                    + "Ktoś musi najpierw postawić skarbiec miasta.";
        }
        TrapHomes.Readout reading = look(world, pos);
        String no = fault(reading);
        if (no != null) {
            return no;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        Station station = new Station(UUID.randomUUID(), who.getUuid(),
                who.getGameProfile().getName(), dimension, pos.toImmutable());
        station.name = spare(who.getGameProfile().getName() + "'s posterunek");
        station.open = true;
        station.cells = reading.beds();
        station.floor = reading.floor();
        STATIONS.add(station);
        boolean first = STATIONS.size() == 1;
        // A station on a budget of zero is a building nobody works in, and
        // "why is it empty" is the first question anybody would ask. The first
        // one in town opens the dial at one shift so there is somebody to see.
        if (first && budget <= 0) {
            budget = WAGE;
        }
        save();
        if (first) {
            announce(who.getServer(), TrapNotes.headline("MIASTO MA POLICJĘ", Formatting.GOLD)
                    .append(TrapNotes.say("\n  " + who.getGameProfile().getName(),
                            Formatting.WHITE))
                    .append(TrapNotes.say(" otworzył ", Formatting.GRAY))
                    .append(TrapNotes.say(station.name, Formatting.AQUA))
                    .append(TrapNotes.say(", cel: ", Formatting.GRAY))
                    .append(TrapNotes.say(String.valueOf(station.cells), Formatting.WHITE))
                    .append(TrapNotes.under("Ilu wyjdzie na ulicę, decyduje budżet "
                            + "przy skarbcu miasta.")));
        }
        return null;
    }

    /** Whatever the anvil called it. TrapShops' rule and TrapShops' reason. */
    public static void rename(Station station, String name) {
        String trimmed = name == null ? "" : name.replace('\n', ' ').trim();
        if (trimmed.isBlank() || trimmed.equals(station.name)) {
            return;
        }
        station.name = spare(trimmed);
        save();
    }

    private static String spare(String wanted) {
        String name = wanted;
        for (int n = 2; taken(name); n++) {
            name = wanted + " " + n;
        }
        return name;
    }

    private static boolean taken(String name) {
        for (Station station : STATIONS) {
            if (name.equals(station.name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-measure a station that is already on the books.
     *
     * Closes rather than being struck off, exactly like a ward: the name, the
     * arrest count and the owner's chance of understanding why all survive a
     * torch going out.
     */
    public static TrapHomes.Readout inspect(ServerWorld world, Station station) {
        TrapHomes.Readout reading = look(world, station.sign);
        // The round only checks the chunk the BLOCK is in, and a station is
        // bigger than a chunk. HeezQ's is 23 blocks across a chunk boundary,
        // so the four cells past x=1280 were being read as solid wall every
        // time that chunk happened to be asleep: a sealed 46-square room with
        // no beds in it, which closes the station -- and then the chunk comes
        // back and it opens again, twelve seconds later, all day. Nothing on
        // the shift survives that, because a closed station stands its
        // officers down and a fresh one hires seven strangers.
        if (reading.asleep()) {
            return reading;
        }
        boolean was = station.open;
        station.open = fault(reading) == null;
        station.cells = reading.beds();
        station.floor = reading.floor();
        if (was != station.open) {
            save();
            ServerPlayerEntity owner =
                    world.getServer().getPlayerManager().getPlayer(station.owner);
            if (owner != null) {
                owner.sendMessage(station.open
                        ? Text.empty()
                                .append(TrapNotes.say(station.name, Formatting.AQUA))
                                .append(TrapNotes.say(" znowu działa.", Formatting.GREEN))
                                .append(TrapNotes.say("   cel: " + station.cells,
                                        Formatting.DARK_GRAY))
                        : Text.empty()
                                .append(TrapNotes.say(station.name, Formatting.AQUA))
                                .append(TrapNotes.say(" zamknięty.", Formatting.RED))
                                .append(TrapNotes.under(String.valueOf(fault(reading)))),
                        false);
            }
        }
        return reading;
    }

    /** The station came down. Its shift goes home and its cells open. */
    public static void lost(ServerWorld world, BlockPos pos) {
        Station station = at(world, pos);
        if (station == null) {
            return;
        }
        STATIONS.remove(station);
        for (Prisoner prisoner : List.copyOf(CELLS)) {
            if (station.id.equals(prisoner.station)) {
                CELLS.remove(prisoner);
                clearBody(world, prisoner.body);
            }
        }
        sweep(world, station);
        station.officers.clear();
        station.golems.clear();
        save();
        ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(station.owner);
        if (owner != null) {
            owner.sendMessage(Text.empty()
                    .append(TrapNotes.say(station.name, Formatting.AQUA))
                    .append(TrapNotes.say(" znika z rejestru.", Formatting.YELLOW))
                    .append(TrapNotes.say("   Kto siedział, właśnie wyszedł.",
                            Formatting.DARK_GRAY)), false);
        }
    }

    /** Bodies belonging to a station that no longer exists. */
    private static void sweep(ServerWorld world, Station station) {
        Box box = new Box(station.sign).expand(LEASH);
        // MobEntity rather than VillagerEntity, because the yard is swept by
        // the same pass as the shift and the cells: three tags, one walk of
        // the entity sections, and no way to add a fourth body to a station
        // and quietly forget to clean it up.
        for (MobEntity body : world.getEntitiesByClass(MobEntity.class, box,
                found -> found.getCommandTags().contains(OFFICER_TAG + "_" + station.id)
                        || found.getCommandTags().contains(PRISONER_TAG + "_" + station.id)
                        || found.getCommandTags().contains(GOLEM_TAG + "_" + station.id))) {
            body.discard();
        }
    }

    // --- the readout ----------------------------------------------------------

    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("police")
                                .executes(context -> {
                                    ServerPlayerEntity who = context.getSource().getPlayer();
                                    if (who == null) {
                                        return 0;
                                    }
                                    report(who);
                                    return 1;
                                })));
    }

    /**
     * /police -- what the city is buying, and why nothing is happening.
     *
     * Same reason {@link TrapVisitors#registerCommands} exists: a force that is
     * running perfectly on a budget of nothing is, from inside the game,
     * identical to a feature that was never written. Every test that can leave
     * the street empty says so out loud here.
     */
    private static void report(ServerPlayerEntity who) {
        var out = TrapNotes.headline("Policja", Formatting.AQUA);
        if (!TrapCity.founded()) {
            who.sendMessage(out.append(TrapNotes.say("   Nie ma miasta, więc nie ma komendy.",
                    Formatting.RED)), false);
            return;
        }
        if (STATIONS.isEmpty()) {
            who.sendMessage(out.append(TrapNotes.say("   Nie ma komisariatu.", Formatting.RED))
                    .append(TrapNotes.under("Postaw blok komisariatu w gotowym budynku "
                            + "z celami.")), false);
            return;
        }
        out.append(TrapNotes.say("   " + STATIONS.size() + " komisariatów", Formatting.WHITE))
                .append(TrapNotes.say("   " + cells() + " cel", Formatting.WHITE));

        out.append(TrapNotes.figure("\n  budżet          ", budget + "e/dzień",
                budget > 0 ? Formatting.GOLD : Formatting.RED));
        if (funded < budget) {
            out.append(TrapNotes.say("   wypłacono " + funded + "e", Formatting.RED));
        }

        out.append(TrapNotes.figure("\n  na etacie       ", String.valueOf(force()),
                        force() > 0 ? Formatting.WHITE : Formatting.RED))
                .append(TrapNotes.figure("   na ulicy ", String.valueOf(onDuty()),
                        Formatting.WHITE))
                .append(TrapNotes.figure("   wyposażenie ", gear() + "/" + TOP_GEAR,
                        Formatting.WHITE));

        // The golems get their own line and their own "why not", for the
        // reason the whole of this readout exists: a work that was bought and
        // is doing nothing because the shift died in the night is, from inside
        // the game, the same thing as nine thousand emeralds that vanished.
        int bought = TrapCity.level(TrapCity.Work.GOLEMS);
        out.append(TrapNotes.figure("\n  golemy          ",
                onGuard() + "/" + golems(), onGuard() > 0 ? Formatting.WHITE
                        : bought > 0 ? Formatting.RED : Formatting.DARK_GRAY));
        if (bought <= 0) {
            out.append(TrapNotes.say("   nie kupione", Formatting.DARK_GRAY));
        } else if (golems() < bought * TrapMath.GOLEMS_PER_LEVEL) {
            out.append(TrapNotes.say("   wychodzi tylu, ilu jest funkcjonariuszy",
                    Formatting.YELLOW));
        }
        if (force() == 0) {
            out.append(TrapNotes.under(budget < WAGE
                    ? "Nikogo na ulicy. Jeden funkcjonariusz to " + WAGE
                    + "e dziennie -- podnieś budżet przy skarbcu."
                    : "Nikogo na ulicy. Cele są zajęte albo komisariat zamknięty."));
        }

        out.append(TrapNotes.figure("\n  odstraszanie    ",
                Math.round(deterrence() * 100) + "%",
                deterrence() > 0 ? Formatting.GREEN : Formatting.RED));
        out.append(TrapNotes.figure("\n  zatrzymania     ", String.valueOf(arrests()),
                        Formatting.WHITE))
                .append(TrapNotes.figure("   w celach ", String.valueOf(CELLS.size()),
                        Formatting.WHITE))
                .append(TrapNotes.figure("   mandaty ", fines + "e", Formatting.GOLD))
                .append(TrapNotes.figure("   wypłaty ", spent + "e", Formatting.GOLD));

        for (Station station : STATIONS) {
            out.append(TrapNotes.say("\n    " + station.name,
                            station.open ? Formatting.AQUA : Formatting.RED))
                    .append(station.open
                            ? TrapNotes.say("   " + station.onShift() + " na służbie   "
                                    + station.onGuard() + " golemów   cele "
                                    + station.free() + "/" + station.cells,
                                    Formatting.DARK_GRAY)
                            : TrapNotes.say("   zamknięty", Formatting.DARK_GRAY));
            // Where each of them actually IS. Without this, "the police are
            // not patrolling" and "the police are patrolling and you happened
            // to look at the two by the door" are the same sentence from
            // inside the game -- and answering it meant reading entity data
            // out of the container.
            ServerWorld world = TrapHospitals.worldOf(who.getServer(), station.dimension);
            for (Patrol patrol : station.officers) {
                if (world == null
                        || !(world.getEntity(patrol.body) instanceof VillagerEntity body)) {
                    continue;
                }
                int out_ = (int) Math.sqrt(body.getBlockPos().getSquaredDistance(station.sign));
                out.append(TrapNotes.say("\n      " + (body.getCustomName() == null
                                ? "funkcjonariusz" : body.getCustomName().getString()),
                                Formatting.WHITE))
                        .append(TrapNotes.say("   " + out_ + " kratek od komendy",
                                out_ > 12 ? Formatting.GREEN : Formatting.YELLOW))
                        .append(TrapNotes.say(patrol.beat == null ? "   bez trasy"
                                        : "   idzie na " + where(patrol.beat),
                                Formatting.DARK_GRAY));
            }
        }
        who.sendMessage(out, false);
    }

    public static int arrests() {
        int total = 0;
        for (Station station : STATIONS) {
            total += station.arrests;
        }
        return total;
    }

    private static void announce(MinecraftServer server, Text what) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(what, false);
        }
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-police.txt");
        STATIONS.clear();
        CELLS.clear();
        STOPPED.clear();
        budget = 0;
        funded = 0;
        spent = 0;
        fines = 0;
        paidOn = -1;
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                try {
                    read(line);
                } catch (Exception bad) {
                    // One line, not the file. The housing register's rule.
                    TrapCraft.LOGGER.warn("skipped an unreadable police line: {}",
                            bad.toString());
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the police book: {}", failure.toString());
        }
        TrapCraft.LOGGER.info("police: {} stations, {} in the cells, {}e a day voted",
                STATIONS.size(), CELLS.size(), budget);
    }

    /**
     * One line in, one record out.
     *
     * Names ride at the END and are the greedy tail of a limited split, which
     * is the housing register's rule and the reason this format cannot grow a
     * field after them. Anything new goes BEFORE the name.
     */
    private static void read(String line) {
        String[] head = line.trim().split("\\s+", 2);
        if (head.length < 2) {
            return;
        }
        switch (head[0]) {
            case "budget" -> budget = Integer.parseInt(head[1].trim());
            case "funded" -> funded = Integer.parseInt(head[1].trim());
            case "spent" -> spent = Long.parseLong(head[1].trim());
            case "fines" -> fines = Long.parseLong(head[1].trim());
            case "paid" -> paidOn = Long.parseLong(head[1].trim());
            case "station" -> {
                String[] parts = line.trim().split("\\s+", 14);
                if (parts.length < 14) {
                    return;
                }
                Station station = new Station(UUID.fromString(parts[1]),
                        UUID.fromString(parts[2]), parts[3], parts[4],
                        new BlockPos(Integer.parseInt(parts[5]), Integer.parseInt(parts[6]),
                                Integer.parseInt(parts[7])));
                station.cells = Integer.parseInt(parts[8]);
                station.floor = Integer.parseInt(parts[9]);
                station.open = "1".equals(parts[10]);
                station.arrests = Integer.parseInt(parts[11]);
                station.calls = Integer.parseInt(parts[12]);
                station.name = parts[13];
                STATIONS.add(station);
            }
            case "cell" -> {
                String[] parts = line.trim().split("\\s+", 6);
                if (parts.length < 6) {
                    return;
                }
                Prisoner prisoner = new Prisoner(parts[4], parts[5],
                        UUID.fromString(parts[1]), Long.parseLong(parts[2]));
                prisoner.body = "-".equals(parts[3]) ? null : UUID.fromString(parts[3]);
                CELLS.add(prisoner);
            }
            default -> {
            }
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            out.append("budget ").append(budget).append('\n');
            out.append("funded ").append(funded).append('\n');
            out.append("spent ").append(spent).append('\n');
            out.append("fines ").append(fines).append('\n');
            out.append("paid ").append(paidOn).append('\n');
            for (Station station : STATIONS) {
                out.append("station ").append(station.id).append(' ').append(station.owner)
                        .append(' ').append(station.ownerName).append(' ')
                        .append(station.dimension).append(' ')
                        .append(station.sign.getX()).append(' ').append(station.sign.getY())
                        .append(' ').append(station.sign.getZ()).append(' ')
                        .append(station.cells).append(' ').append(station.floor).append(' ')
                        .append(station.open ? 1 : 0).append(' ').append(station.arrests)
                        .append(' ').append(station.calls).append(' ')
                        .append(station.name.replace('\n', ' ')).append('\n');
            }
            for (Prisoner prisoner : CELLS) {
                // The CRIME is the tail here, not the name: a charge is written
                // by this mod and a name is written by nobody, so the charge is
                // the one that can safely hold a space.
                out.append("cell ").append(prisoner.station).append(' ')
                        .append(prisoner.until).append(' ')
                        .append(prisoner.body == null ? "-" : prisoner.body).append(' ')
                        .append(prisoner.who.replace(' ', '_')).append(' ')
                        .append(prisoner.crime.replace('\n', ' ')).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the police book: {}", failure.toString());
        }
    }
}
