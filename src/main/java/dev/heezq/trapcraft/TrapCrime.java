package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
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
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the town does to itself when nobody is watching.
 *
 * Every other pressure in this mod comes from OUTSIDE the city -- a pillager
 * patrol that heard about a farm, a mugger who followed the money, a zombie
 * through a window. All of them are the world happening to the town. This is
 * the town happening to itself, and it is the thing a police force can
 * actually be measured against.
 *
 * <h2>The shape of it</h2>
 *
 * A roll on the same clock the wards and the houses run on. When it lands, one
 * offence happens somewhere in the loaded city: money leaves a mailbox or a
 * till, somebody's mood takes a hit, and a suspect stands up at the scene and
 * starts walking away from it. From that moment it is a race -- if a copper
 * gets close enough before the trail goes cold, the case closes, the money
 * comes back and somebody does time. If not, the money is gone.
 *
 * <h2>Where the money actually goes</h2>
 *
 * Into {@link TrapPayroll}'s purse, which is the whole reason this is honest
 * rather than a debuff. A thief is a townsperson: the emeralds they take out
 * of a landlord's mailbox are emeralds they will spend at somebody's counter
 * next week. Nothing is minted and nothing is destroyed -- a burglary MOVES
 * money from the people who hold it to the town that spends it, and a city
 * with no police is a city quietly redistributing its rent into its shops.
 *
 * Restitution runs the same pipe backwards, and can come up short for the same
 * reason any other town payment can: the purse has to have it.
 *
 * <h2>Why murder is rare and never random</h2>
 *
 * It is four percent of a roll that itself only lands a couple of times a day,
 * and half of the daylight ones are downgraded to a beating. That works out at
 * one killing every fortnight or so in a town of twenty -- often enough that
 * everybody has a story, rare enough that it is news when it happens, which is
 * exactly the frequency a thing like this should have.
 *
 * @see TrapPolice for who is out there answering it
 */
public final class TrapCrime {

    /** Ticks between one roll. The wards' clock, and the houses'. */
    private static final int ROUND_TICKS = 240;
    /** Marks somebody the police are looking for. */
    public static final String SUSPECT_TAG = "trapcraft_suspect";

    /** Ticks a suspect stays findable before the trail goes cold. */
    private static final int TRAIL_TICKS = 20 * 60 * 4;
    /** How far a fleeing suspect gets per shove, and how often they are shoved. */
    private static final int FLEE_STEP = 14;
    private static final int FLEE_TICKS = 40;
    /** How far from the scene a suspect will go before they are simply gone. */
    private static final int FLEE_LIMIT = 80;
    /** Mailboxes and tills further than this from a player are not worth simulating. */
    private static final int WITNESS_RANGE = 128;

    // --- the other kind of trouble --------------------------------------------

    /**
     * The one thing in this file that does NOT come from inside the town.
     *
     * Everything else here is the city doing itself harm, which is the whole
     * point of it. This is a band walking in off the road, and it lives here
     * anyway for one reason: it wants the same clock, the same "is anybody
     * awake to see it" gate, the same announcement and the same waypoint that
     * every other bad night already uses. A second copy of all of that in the
     * police file would be worse than one honest exception in this one.
     *
     * Nothing is asked of {@link TrapPolice}: an officer already walks at any
     * hostile it can see, and a pillager is a hostile. The force answering
     * this is emergent, not scripted.
     */
    private static final int RAID_COOLDOWN = 20 * 60 * 18;
    /** Odds per round once the cooldown is up. About one a couple of hours. */
    private static final float RAID_ODDS = 0.004f;
    /** How far out they form up, and how far they scatter over. */
    private static final int RAID_NEAR = 30;
    private static final int RAID_SPREAD = 14;

    /** World time of the last incursion. Memory only: a lost one costs a wait. */
    private static long lastRaid = Long.MIN_VALUE;

    /** What somebody did. */
    public enum Kind {
        // Take rates were 0.10-0.22 / 0.35-0.75 / 0.15-0.30, and the middle
        // one was the complaint: three quarters of a landlord's uncollected
        // rent in one night is not a burglary, it is a wipe. A fifth to a
        // little under a half still stings, still rewards emptying the box,
        // and does not undo a week.
        PICKPOCKET("Kradzież", 40, 90, 1, 0.08f, 0.18f),
        BURGLARY("Włamanie", 26, 260, 2, 0.20f, 0.45f),
        ROBBERY("Rozbój", 20, 420, 3, 0.10f, 0.20f),
        VANDALISM("Zniszczenie mienia", 10, 140, 1, 0f, 0f),
        MURDER("Zabójstwo", 4, 1500, 8, 0f, 0f);

        private final String display;
        private final int weight;
        /** What the court asks for, paid out of the town's own purse. */
        private final int fine;
        /** In-game days in a cell. */
        private final int days;
        /** Share of the victim's held money that goes, low and high. */
        private final float takeLow;
        private final float takeHigh;

        Kind(String display, int weight, int fine, int days, float takeLow, float takeHigh) {
            this.display = display;
            this.weight = weight;
            this.fine = fine;
            this.days = days;
            this.takeLow = takeLow;
            this.takeHigh = takeHigh;
        }

        public String display() {
            return display;
        }

        public int weight() {
            return weight;
        }

        public int fine() {
            return fine;
        }

        public int days() {
            return days;
        }
    }

    /** What an arrest is worth, handed to {@link TrapPolice} at the cuffs. */
    public record Charge(String crime, int days, int restitution) {
    }

    /** One offence, from the moment it happens until somebody closes it. */
    public static final class Case {
        final UUID id;
        final Kind kind;
        final String suspect;
        /** Whose it was -- a house name, a shop name, or a person. */
        final String victim;
        final String dimension;
        final BlockPos where;
        final long day;
        /** Emeralds that went, and that come back if somebody is caught. */
        int loot;
        UUID body;
        /** World time after which nobody is looking any more. */
        long cold;
        /** Which house to pay back, or null when the victim was a shop. */
        UUID home;
        BlockPos till;

        Case(UUID id, Kind kind, String suspect, String victim, String dimension,
             BlockPos where, long day) {
            this.id = id;
            this.kind = kind;
            this.suspect = suspect;
            this.victim = victim;
            this.dimension = dimension;
            this.where = where;
            this.day = day;
        }

        public Kind kind() {
            return kind;
        }

        public String suspect() {
            return suspect;
        }

        public String victim() {
            return victim;
        }

        public BlockPos where() {
            return where;
        }

        public int loot() {
            return loot;
        }

        public long day() {
            return day;
        }
    }

    /**
     * The last few doorsteps, so bad luck cannot land on one of them twice.
     *
     * "Okradli mnie 2 razy" was half the complaint and it was not the RATE --
     * a uniform pick over four houses puts two in a row on the same one about
     * a quarter of the time, and from behind that door it reads as the mod
     * having taken against you. Memory only, and short: three victims of grace
     * is enough to break the pattern without making anybody immune.
     */
    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();
    private static final int SPARED = 3;

    private static final List<Case> OPEN = new ArrayList<>();
    /** Everything that has ever happened, by kind. For the blotter. */
    private static final Map<Kind, Integer> TALLY = new EnumMap<>(Kind.class);
    private static int solved;
    private static int cold;
    private static long stolen;
    private static long recovered;
    private static Path saveFile;

    private TrapCrime() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapCrime::load);
        registerCommand();
        // A suspect from a case that ended when the server did. The body is
        // persistent -- it has to be, or a chase across a chunk border ends
        // with the villain evaporating -- so somebody has to bin the leftovers.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof VillagerEntity body
                    && body.getCommandTags().contains(SUSPECT_TAG)
                    && byBody(body.getUuid()) == null) {
                body.discard();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % ROUND_TICKS == 0) {
                roll(server);
            }
            if (server.getTicks() % FLEE_TICKS == 0 && !OPEN.isEmpty()) {
                flee(server);
            }
        });
    }

    public static List<Case> open() {
        return OPEN;
    }

    public static int solved() {
        return solved;
    }

    public static int wentCold() {
        return cold;
    }

    public static long stolen() {
        return stolen;
    }

    public static long recovered() {
        return recovered;
    }

    public static int countOf(Kind kind) {
        return TALLY.getOrDefault(kind, 0);
    }

    /** Every offence this town has ever produced. */
    public static int total() {
        int total = 0;
        for (int count : TALLY.values()) {
            total += count;
        }
        return total;
    }

    // --- how bad it is --------------------------------------------------------

    /**
     * How hard up the town is, 0 to 1.
     *
     * The share of tenanted houses whose mood is under half. Poverty is the
     * one driver of crime that a player can actually do something about
     * without touching the police at all -- fix the damp, put a light on the
     * landing, drop the rent -- and it is the reason this reads the housing
     * register rather than rolling a mood of its own.
     */
    public static float hardship() {
        int homes = 0;
        int struggling = 0;
        for (TrapHomes.Home home : TrapHomes.all()) {
            if (home.tenant() == null) {
                continue;
            }
            homes++;
            if (home.mood() < HomeSurvey.MOOD_MAX / 2) {
                struggling++;
            }
        }
        return homes == 0 ? 0f : struggling / (float) homes;
    }

    /** What the odds are right now, for the blotter and for the roll. */
    public static float odds(ServerWorld world) {
        return TrapMath.crimeOdds(TrapHomes.population(), hardship(),
                TrapPolice.deterrence(), night(world), TrapLaw.serverHeat());
    }

    private static boolean night(ServerWorld world) {
        long hour = world.getTimeOfDay() % 24000L;
        return hour >= 13000L && hour < 23000L;
    }

    // --- the roll -------------------------------------------------------------

    private static void roll(MinecraftServer server) {
        // Before the guards, not after. A vault broken while a chase was on
        // would otherwise leave the case open and the suspect jogging round
        // the map forever, because the only thing that closes a cold trail is
        // a pass that the "is there a city" test had already turned back.
        chill(server);
        if (!TrapCity.founded() || TrapHomes.population() <= 0) {
            return;
        }
        ServerWorld world = TrapHospitals.worldOf(server, TrapCity.vaultWorld());
        if (world == null || world.getPlayers().isEmpty()) {
            // Nobody is in town. Crime that happens where nothing can see it
            // is a number moving in a text file, and the police cannot be sent
            // to a chunk that is not loaded either.
            return;
        }
        if (world.getTime() - lastRaid > RAID_COOLDOWN
                && world.getRandom().nextFloat() < RAID_ODDS) {
            bandits(world, null);
            return;   // one disaster a round is plenty
        }
        if (world.getRandom().nextFloat() >= odds(world)) {
            return;
        }
        Kind kind = pick(world);
        commit(server, world, kind);
    }

    /**
     * A band off the road, formed up at the edge of town.
     *
     * They are given a patrol target in the middle of it and a first victim to
     * head for, and after that vanilla does the work: a pillager already wants
     * to kill villagers and players, and an officer already walks at anything
     * hostile it can see. The interesting part is not the AI, it is that the
     * town now has a reason to have paid for a police force before tonight.
     *
     * @param at whoever this is being sent at, or null for the town at large
     * @return why it didn't happen, or null if it did
     */
    public static String bandits(ServerWorld world, ServerPlayerEntity at) {
        if (!TrapCity.founded()) {
            return "Nie ma miasta, na które można napaść.";
        }
        BlockPos centre = at != null ? at.getBlockPos()
                : TrapCity.founded() ? TrapCity.vaultAt() : null;
        if (centre == null || !world.isChunkLoaded(centre.getX() >> 4, centre.getZ() >> 4)) {
            return "Tam nikogo nie ma.";
        }
        var random = world.getRandom();
        // Sized off the town, because a band that would flatten a hamlet is a
        // nuisance to a city and the same number cannot be both.
        int heads = TrapMath.banditBand(TrapHomes.population(), TrapLaw.serverHeat());
        List<net.minecraft.entity.mob.MobEntity> band = new ArrayList<>(
                TrapHeat.spawn(world, centre, random, EntityType.PILLAGER,
                        heads, RAID_NEAR, RAID_SPREAD));
        band.addAll(TrapHeat.spawn(world, centre, random, EntityType.VINDICATOR,
                Math.max(1, heads / 3), RAID_NEAR, RAID_SPREAD));
        if (band.isEmpty()) {
            return "Nie ma gdzie ich postawić -- za ciasno albo chunki śpią.";
        }
        lastRaid = world.getTime();

        boolean first = true;
        for (var raider : band) {
            if (raider instanceof net.minecraft.entity.mob.PatrolEntity marcher) {
                marcher.setPatrolTarget(centre);
                marcher.setPatrolLeader(first);
                first = false;
            }
            // A first thing to head for. Without it they form up and admire
            // the view until something wanders into their own detection range,
            // which from thirty blocks out can be a long evening.
            if (at != null) {
                raider.setTarget(at);
            } else {
                var victim = world.getClosestEntity(
                        net.minecraft.entity.passive.VillagerEntity.class,
                        net.minecraft.entity.ai.TargetPredicate.createNonAttackable()
                                .setBaseMaxDistance(48),
                        raider, raider.getX(), raider.getY(), raider.getZ(),
                        raider.getBoundingBox().expand(48));
                if (victim != null) {
                    raider.setTarget(victim);
                }
            }
        }

        world.playSound(null, centre, SoundEvents.EVENT_RAID_HORN.value(),
                SoundCategory.HOSTILE, 1.0F, 0.85F);
        announce(world.getServer(), TrapNotes.headline("NAPAD NA MIASTO", Formatting.DARK_RED)
                .append(TrapNotes.say("   " + band.size() + " bandytów", Formatting.WHITE))
                .append(TrapNotes.say("   " + centre.getX() + ", " + centre.getZ(),
                        Formatting.DARK_GRAY))
                .append(TrapPolice.onDuty() > 0
                        ? TrapNotes.under("Patrol " + TrapPolice.onDuty()
                                + " funkcjonariuszy rusza na miejsce.")
                        : TrapNotes.under("Na ulicy nie ma policji. Nikt po to nie "
                                + "pojedzie.")));
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.getBlockPos().isWithinDistance(centre, WITNESS_RANGE)) {
                TrapWaypoints.offer(player, "Napad", centre, TrapWaypoints.RED);
            }
        }
        TrapCraft.LOGGER.info("bandits: {} on the town at {}", band.size(), centre.toShortString());
        return null;
    }

    /**
     * Which offence, weighted -- and the one rule that is not a weight.
     *
     * Killings happen after dark and only after dark. It was half of the
     * daytime ones downgraded to a beating, which still let somebody lose a
     * tenant at eleven in the morning seven minutes into the feature existing.
     * A hard rule is both more believable and the thing that makes the rate
     * predictable: murder is four percent of the offences of the shorter half
     * of the day, which in a town of twenty is one every few hours of play.
     */
    private static Kind pick(ServerWorld world) {
        int total = 0;
        for (Kind kind : Kind.values()) {
            total += kind.weight;
        }
        int roll = world.getRandom().nextInt(total);
        for (Kind kind : Kind.values()) {
            roll -= kind.weight;
            if (roll < 0) {
                return kind == Kind.MURDER && !night(world) ? Kind.ROBBERY : kind;
            }
        }
        return Kind.PICKPOCKET;
    }

    private static void commit(MinecraftServer server, ServerWorld world, Kind kind) {
        switch (kind) {
            case PICKPOCKET -> againstATill(server, world, kind);
            case BURGLARY, VANDALISM -> againstAHouse(server, world, kind);
            case ROBBERY, MURDER -> againstAPerson(server, world, kind);
        }
    }

    // --- who it happens to ----------------------------------------------------

    /** A hand in a till. The commonest thing, and the cheapest to shrug off. */
    private static void againstATill(MinecraftServer server, ServerWorld world, Kind kind) {
        List<TrapShops.Shop> reachable = new ArrayList<>();
        List<TrapShops.Shop> fresh = new ArrayList<>();
        for (TrapShops.Shop shop : TrapShops.shops()) {
            if (shop.dimension.equals(world.getRegistryKey().getValue().toString())
                    && shop.till() > 0 && watched(world, shop.pos())) {
                reachable.add(shop);
                if (!RECENT.contains(shop.pos().toShortString())) {
                    fresh.add(shop);
                }
            }
        }
        if (reachable.isEmpty()) {
            // No counter worth robbing. A pickpocket goes for a doorstep
            // instead rather than the whole roll being wasted -- a city of
            // houses and no shops should still have crime in it.
            againstAHouse(server, world, kind);
            return;
        }
        List<TrapShops.Shop> pool = fresh.isEmpty() ? reachable : fresh;
        TrapShops.Shop shop = pool.get(world.getRandom().nextInt(pool.size()));
        int took = TrapMath.haul(shop.till(), kind.takeLow, kind.takeHigh,
                world.getRandom().nextFloat());
        Case sprawa = new Case(UUID.randomUUID(), kind, someone(world), shop.name(),
                shop.dimension, shop.pos(), TrapMarket.today(server));
        sprawa.till = shop.pos();
        if (took > 0) {
            TrapShops.robbed(shop, took);
            TrapPayroll.credit(took);
            sprawa.loot = took;
            stolen += took;
        }
        openCase(server, world, sprawa);
        tell(server, shop.owner(), scene(kind, shop.name(), took, "z kasy"));
    }

    /**
     * The line a victim actually reads, and the one the complaint was about.
     *
     * One loud word for WHAT, the name in white for WHERE, the loss in gold
     * for HOW MUCH, and a quiet grey tail for what to do next. The old version
     * was a bold headline with everything appended to it, which meant the
     * whole line inherited the bold and none of it stood out -- see
     * {@link TrapNotes}.
     */
    private static net.minecraft.text.MutableText scene(Kind kind, String where,
                                                        int took, String from) {
        var out = TrapNotes.headline(kind.display().toUpperCase(java.util.Locale.ROOT),
                        Formatting.RED)
                .append(TrapNotes.say("   " + where, Formatting.WHITE));
        if (took > 0) {
            out.append(TrapNotes.say("   -" + took + "e", Formatting.GOLD, Formatting.BOLD))
                    .append(TrapNotes.say(" " + from, Formatting.DARK_GRAY));
        } else {
            out.append(TrapNotes.say("   bez strat", Formatting.GREEN));
        }
        return out;
    }

    /** Somebody's front door, and the rent sitting behind it. */
    private static void againstAHouse(MinecraftServer server, ServerWorld world, Kind kind) {
        TrapHomes.Home home = someHome(world, kind == Kind.BURGLARY);
        if (home == null) {
            return;
        }
        int took = kind == Kind.VANDALISM ? 0
                : TrapMath.haul(home.till(), kind == Kind.PICKPOCKET ? 0.10f : kind.takeLow,
                        kind == Kind.PICKPOCKET ? 0.25f : kind.takeHigh,
                        world.getRandom().nextFloat());
        Case sprawa = new Case(UUID.randomUUID(), kind, someone(world), home.name(),
                home.dimension(), home.anchor(), TrapMarket.today(server));
        sprawa.home = home.id();
        if (took > 0) {
            TrapHomes.robbed(home, took);
            TrapPayroll.credit(took);
            sprawa.loot = took;
            stolen += took;
        }
        TrapHomes.sicken(home, kind == Kind.VANDALISM ? 6 : 9);
        home.write(kind == Kind.VANDALISM
                ? "Ktoś w nocy zdemolował wejście. Nic nie zginęło."
                : took > 0 ? "Włamanie. Ze skrzynki zniknęło " + took + "e."
                : "Ktoś się włamał, ale skrzynka była pusta.");
        openCase(server, world, sprawa);
        tell(server, home.owner(), scene(kind, home.name(), took, "ze skrzynki")
                .append(took > 0
                        ? TrapNotes.under("Zbieraj czynsz częściej -- biorą udział tego, "
                                + "co leży w skrzynce.")
                        : TrapNotes.under("Lokatorom to nie w smak.")));
    }

    /**
     * Somebody, rather than something.
     *
     * The only branch that can end a tenancy, and it goes through exactly the
     * same door a bite does -- a bed in a ward, a household short a wage, and
     * a funeral if the city has nowhere to treat them. That is deliberate:
     * a stabbing and an infection are the same problem from the register's
     * point of view, and giving crime its own death path would have been a
     * second way for a resident to stop existing.
     */
    private static void againstAPerson(MinecraftServer server, ServerWorld world, Kind kind) {
        TrapHomes.Home home = someHome(world, false);
        if (home == null || home.tenant() == null) {
            return;
        }
        Case sprawa = new Case(UUID.randomUUID(), kind, someone(world), home.tenant(),
                home.dimension(), home.anchor(), TrapMarket.today(server));
        sprawa.home = home.id();
        if (kind == Kind.ROBBERY) {
            int took = TrapMath.haul(home.till(), kind.takeLow, kind.takeHigh,
                    world.getRandom().nextFloat());
            if (took > 0) {
                TrapHomes.robbed(home, took);
                TrapPayroll.credit(took);
                sprawa.loot = took;
                stolen += took;
            }
            TrapHomes.sicken(home, 12);
            home.write(home.tenant() + " został napadnięty po drodze do domu.");
        }
        // The one branch that can end a tenancy on the spot. The rest are
        // found in time, and whether they survive is the ward's problem --
        // which is the join: a city with a hospital buries fewer of these.
        boolean died = kind == Kind.MURDER
                && world.getRandom().nextFloat() < TrapMath.MURDER_FATAL;
        String house = home.name();
        if (died) {
            home.write(sprawa.victim + " nie żyje. Znaleźli go przed domem.");
            TrapHomes.moveOut(server, world, home, "został zabity");
        } else if ((kind == Kind.MURDER
                || world.getRandom().nextFloat() < TrapMath.HOSPITALISED)
                && TrapHospitals.awayFrom(home.id()) < home.heads) {
            TrapHospitals.hurt(world, home, sprawa.victim,
                    kind == Kind.MURDER ? "został zaatakowany nożem" : "został pobity");
        }
        openCase(server, world, sprawa);
        if (kind == Kind.MURDER) {
            world.playSound(null, sprawa.where, SoundEvents.ENTITY_VILLAGER_DEATH,
                    SoundCategory.NEUTRAL, 1.0F, 0.7F);
            announce(server, TrapNotes.headline("ZABÓJSTWO", Formatting.DARK_RED)
                    .append(TrapNotes.say("\n  " + sprawa.victim, Formatting.WHITE))
                    .append(TrapNotes.say(" z " + house, Formatting.DARK_GRAY))
                    .append(died
                            ? TrapNotes.say("   zginął na miejscu", Formatting.RED)
                            : TrapNotes.say("   walczy o życie w szpitalu",
                                    Formatting.YELLOW))
                    // Comma, because "-412 88" reads as one negative number.
                    .append(TrapNotes.say("   " + sprawa.where.getX() + ", "
                            + sprawa.where.getZ(), Formatting.DARK_GRAY))
                    .append(TrapNotes.under("Sprawca ucieka. Policja ma "
                            + TRAIL_TICKS / 20 / 60 + " minuty.")));
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (player.getBlockPos().isWithinDistance(sprawa.where, WITNESS_RANGE)) {
                    TrapWaypoints.offer(player, "Zabójstwo", sprawa.where, TrapWaypoints.RED);
                }
            }
        } else {
            tell(server, home.owner(), TrapNotes.headline("ROZBÓJ", Formatting.RED)
                    .append(TrapNotes.say("   " + sprawa.victim, Formatting.WHITE))
                    .append(TrapNotes.say("   z " + house, Formatting.DARK_GRAY))
                    .append(sprawa.loot > 0
                            ? TrapNotes.say("   -" + sprawa.loot + "e",
                                    Formatting.GOLD, Formatting.BOLD)
                            : Text.empty()));
        }
    }

    /** A tenanted house somebody could actually walk up to. */
    private static TrapHomes.Home someHome(ServerWorld world, boolean wantsMoney) {
        String here = world.getRegistryKey().getValue().toString();
        List<TrapHomes.Home> reachable = new ArrayList<>();
        List<TrapHomes.Home> fresh = new ArrayList<>();
        for (TrapHomes.Home home : TrapHomes.all()) {
            if (home.tenant() != null && home.dimension().equals(here)
                    && watched(world, home.anchor())
                    && (!wantsMoney || home.till() > 0)) {
                reachable.add(home);
                if (!RECENT.contains(home.id().toString())) {
                    fresh.add(home);
                }
            }
        }
        if (reachable.isEmpty() && wantsMoney) {
            return someHome(world, false);   // nothing worth taking; still worth trying
        }
        // Anybody but the last few, unless the last few are all there is.
        List<TrapHomes.Home> pool = fresh.isEmpty() ? reachable : fresh;
        return pool.isEmpty() ? null : pool.get(world.getRandom().nextInt(pool.size()));
    }

    /** Is anybody near enough that this would be a thing that happened? */
    private static boolean watched(ServerWorld world, BlockPos where) {
        if (!world.isChunkLoaded(where.getX() >> 4, where.getZ() >> 4)) {
            return false;
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.getBlockPos().isWithinDistance(where, WITNESS_RANGE)) {
                return true;
            }
        }
        return false;
    }

    private static String someone(ServerWorld world) {
        return TrapHomes.nameFor(world.getRandom().nextInt(4096));
    }

    // --- the suspect ----------------------------------------------------------

    /** Put the case on the books and stand somebody up at the scene. */
    private static void openCase(MinecraftServer server, ServerWorld world, Case sprawa) {
        sprawa.cold = world.getTime() + TRAIL_TICKS;
        OPEN.add(sprawa);
        TALLY.merge(sprawa.kind, 1, Integer::sum);
        RECENT.addFirst(sprawa.home != null ? sprawa.home.toString()
                : sprawa.till != null ? sprawa.till.toShortString() : "-");
        while (RECENT.size() > SPARED) {
            RECENT.removeLast();
        }
        spawnSuspect(world, sprawa);
        save();
        world.playSound(null, sprawa.where, SoundEvents.ENTITY_VILLAGER_NO,
                SoundCategory.NEUTRAL, 0.8F, 0.7F);
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, sprawa.where.getX() + 0.5,
                sprawa.where.getY() + 1.4, sprawa.where.getZ() + 0.5, 10, 0.4, 0.4, 0.4, 0.02);
        if (TrapPolice.onDuty() == 0) {
            // The one message that explains the whole system in one line, and
            // it only goes out when there is genuinely nobody to send.
            tellAll(server, TrapNotes.say("  Nikt po to nie pojedzie -- na ulicy nie ma "
                    + "policji.", Formatting.DARK_GRAY));
        }
    }

    private static void spawnSuspect(ServerWorld world, Case sprawa) {
        BlockPos stand = TrapSpawn.near(world, sprawa.where.up(), 6);
        if (stand == null) {
            return;
        }
        VillagerEntity body = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (body == null) {
            return;
        }
        body.refreshPositionAndAngles(stand, world.getRandom().nextFloat() * 360f, 0f);
        body.setPersistent();
        body.setCustomName(Text.literal(sprawa.suspect)
                .formatted(Formatting.DARK_RED, Formatting.BOLD));
        body.setCustomNameVisible(true);
        body.addCommandTag(SUSPECT_TAG);
        body.addCommandTag(SUSPECT_TAG + "_" + sprawa.id);
        // SWAMP for the clothes: the darkest robe on the villager type list,
        // and it costs nothing -- no texture, no model, and not one Polymer
        // carrier. Same trade TrapVisitors makes to dress somebody from away.
        var registries = world.getRegistryManager();
        body.setVillagerData(body.getVillagerData()
                .withType(registries.getOrThrow(RegistryKeys.VILLAGER_TYPE)
                        .getOrThrow(net.minecraft.village.VillagerType.SWAMP))
                .withProfession(registries.getOrThrow(RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(VillagerProfession.NITWIT)));
        // Faster than a copper without kit, slower than one with it. That IS
        // the funding decision: an unequipped force can see a runner and never
        // close the distance, which is exactly what an underfunded police
        // force looks like from a window.
        var speed = body.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(TrapMath.SUSPECT_PACE);
        }
        world.spawnEntity(body);
        sprawa.body = body.getUuid();
    }

    /** One shove each, away from wherever they did it. */
    private static void flee(MinecraftServer server) {
        for (Case sprawa : OPEN) {
            ServerWorld world = TrapHospitals.worldOf(server, sprawa.dimension);
            if (world == null || sprawa.body == null) {
                continue;
            }
            if (!(world.getEntity(sprawa.body) instanceof VillagerEntity body)
                    || !body.isAlive()) {
                continue;
            }
            BlockPos from = body.getBlockPos();
            int away = (int) Math.sqrt(from.getSquaredDistance(sprawa.where));
            var random = world.getRandom();
            BlockPos target;
            if (away >= FLEE_LIMIT) {
                // Far enough. They lie low and circle rather than running to
                // the world border -- a suspect who leaves the map is one no
                // patrol could ever have caught, which makes the whole race
                // decided by geometry instead of by funding.
                target = sprawa.where.add(random.nextInt(FLEE_LIMIT) - FLEE_LIMIT / 2, 0,
                        random.nextInt(FLEE_LIMIT) - FLEE_LIMIT / 2);
            } else {
                int dx = from.getX() - sprawa.where.getX();
                int dz = from.getZ() - sprawa.where.getZ();
                double length = Math.max(1.0, Math.sqrt(dx * (double) dx + dz * (double) dz));
                target = from.add((int) (dx / length * FLEE_STEP) + random.nextInt(7) - 3, 0,
                        (int) (dz / length * FLEE_STEP) + random.nextInt(7) - 3);
            }
            // Same reason a copper does not sleep: most of this happens at
            // night, and a villain who goes to bed at the scene is not one.
            body.wakeUp();
            body.getBrain().forget(net.minecraft.entity.ai.brain.MemoryModuleType.HOME);
            body.getBrain().remember(
                    net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.entity.ai.brain.WalkTarget(
                            TrapPolice.ground(world, target, target),
                            TrapPolice.CHASE_PACE, 1));
        }
    }

    /** Cases nobody got to in time. */
    private static void chill(MinecraftServer server) {
        for (Case sprawa : List.copyOf(OPEN)) {
            ServerWorld world = TrapHospitals.worldOf(server, sprawa.dimension);
            if (world == null || world.getTime() < sprawa.cold) {
                continue;
            }
            OPEN.remove(sprawa);
            cold++;
            if (sprawa.body != null
                    && world.getEntity(sprawa.body) instanceof VillagerEntity body) {
                world.spawnParticles(ParticleTypes.SMOKE, body.getX(), body.getY() + 1.6,
                        body.getZ(), 8, 0.2, 0.3, 0.2, 0.01);
                body.discard();
            }
            save();
            if (sprawa.loot > 0) {
                tellOwner(server, sprawa, Text.empty()
                        .append(TrapNotes.say("Sprawa umorzona.", Formatting.YELLOW))
                        .append(TrapNotes.say("   " + sprawa.kind.display() + " w "
                                + sprawa.victim, Formatting.DARK_GRAY))
                        .append(TrapNotes.say("   " + sprawa.loot + "e przepadło",
                                Formatting.RED)));
            }
        }
    }

    // --- the arrest -----------------------------------------------------------

    /**
     * A copper got a hand on somebody.
     *
     * Called by {@link TrapPolice} and by nothing else. Everything about what
     * they DID lives on this side of the line: the restitution, the fine, the
     * charge sheet. The other file knows how to catch a person and nothing at
     * all about burglary.
     *
     * @return the charge, or null if this body was not a live case
     */
    public static Charge caught(ServerWorld world, VillagerEntity body) {
        Case sprawa = byBody(body.getUuid());
        if (sprawa == null) {
            return null;
        }
        OPEN.remove(sprawa);
        solved++;
        int back = payBack(world.getServer(), sprawa);
        // The fine comes out of the town's own purse, because the person
        // paying it is a townsperson. Nothing is minted: a court moves money
        // from the people to the council, which is what a fine IS.
        if (TrapPayroll.spend(sprawa.kind.fine())) {
            TrapCity.receive(sprawa.kind.fine(), TrapCity.Duty.INCOME);
        }
        save();
        return new Charge(sprawa.kind.display(), sprawa.kind.days(), back);
    }

    /**
     * The money goes home, as far as the purse can manage.
     *
     * Partial is a real outcome and is not papered over: a town that has
     * already spent what the thief took cannot hand all of it back, and the
     * landlord gets what there is.
     */
    private static int payBack(MinecraftServer server, Case sprawa) {
        if (sprawa.loot <= 0) {
            return 0;
        }
        int back = sprawa.loot;
        while (back > 0 && !TrapPayroll.spend(back)) {
            back -= Math.max(1, back / 4);
        }
        if (back <= 0) {
            return 0;
        }
        recovered += back;
        if (sprawa.home != null) {
            TrapHomes.Home home = TrapHomes.byId(sprawa.home);
            if (home != null) {
                TrapHomes.robbed(home, -back);
                home.write("Policja odzyskała " + back + "e. Wróciło do skrzynki.");
                return back;
            }
        }
        if (sprawa.till != null) {
            ServerWorld world = TrapHospitals.worldOf(server, sprawa.dimension);
            TrapShops.Shop shop = world == null ? null : TrapShops.shopAt(world, sprawa.till);
            if (shop != null) {
                TrapShops.robbed(shop, -back);
                return back;
            }
        }
        // Nowhere to put it back. It stays with the town rather than being
        // conjured out of the purse and dropped on the floor.
        TrapPayroll.credit(back);
        recovered -= back;
        return 0;
    }

    public static Case byBody(UUID body) {
        for (Case sprawa : OPEN) {
            if (body.equals(sprawa.body)) {
                return sprawa;
            }
        }
        return null;
    }

    // --- letters --------------------------------------------------------------

    private static void tell(MinecraftServer server, UUID owner, Text what) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(owner);
        if (player != null) {
            player.sendMessage(what, false);
        }
    }

    private static void tellOwner(MinecraftServer server, Case sprawa, Text what) {
        if (sprawa.home != null) {
            TrapHomes.Home home = TrapHomes.byId(sprawa.home);
            if (home != null) {
                tell(server, home.owner(), what);
            }
            return;
        }
        for (TrapShops.Shop shop : TrapShops.shops()) {
            if (shop.pos().equals(sprawa.till)) {
                tell(server, shop.owner(), what);
                return;
            }
        }
    }

    private static void tellAll(MinecraftServer server, Text what) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(what, false);
        }
    }

    private static void announce(MinecraftServer server, Text what) {
        tellAll(server, what);
    }

    // --- the blotter ----------------------------------------------------------

    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> {
                    dispatcher.register(
                            net.minecraft.server.command.CommandManager.literal("crime")
                                    .executes(context -> {
                                        ServerPlayerEntity who = context.getSource().getPlayer();
                                        if (who == null) {
                                            return 0;
                                        }
                                        blotter(who);
                                        return 1;
                                    }));
                    // Deliberately the SAME literal TrapHeat already uses for
                    // the farm raid, with a player argument beside its integer
                    // tier. Brigadier merges siblings on one literal and tries
                    // each parser in turn, so "/raid 2" is still a tier and
                    // "/raid HeezQ" is a band on HeezQ -- one word for "send
                    // trouble", which is what anybody typing it means.
                    //
                    // Op-only, because it puts eight armed illagers next to
                    // somebody.
                    dispatcher.register(net.minecraft.server.command.CommandManager
                            .literal("raid")
                            .requires(source -> source.hasPermissionLevel(2))
                            .then(net.minecraft.server.command.CommandManager
                                    .argument("player", net.minecraft.command.argument
                                            .EntityArgumentType.player())
                                    .executes(context -> {
                                        ServerPlayerEntity on = net.minecraft.command.argument
                                                .EntityArgumentType.getPlayer(context, "player");
                                        String no = bandits(on.getWorld(), on);
                                        context.getSource().sendFeedback(() -> no == null
                                                        ? TrapNotes.say("Wysłano bandę na "
                                                        + on.getGameProfile().getName() + ".",
                                                        Formatting.RED)
                                                        : TrapNotes.say(no, Formatting.GRAY),
                                                false);
                                        return no == null ? 1 : 0;
                                    })));
                });
    }

    /**
     * /crime -- what the town is doing to itself, and why.
     *
     * Every multiplier in {@link TrapMath#crimeOdds} is printed, because the
     * whole design intent is that crime is something the room did to itself:
     * poor housing, no police, a drug trade running hot. A rate with no
     * explanation attached is weather, and nobody plays around weather.
     */
    private static void blotter(ServerPlayerEntity who) {
        ServerWorld world = who.getWorld();
        var out = TrapNotes.headline("Przestępczość", Formatting.DARK_RED);
        if (!TrapCity.founded()) {
            who.sendMessage(out.append(TrapNotes.say("   Nie ma miasta, nie ma statystyk.",
                    Formatting.GRAY)), false);
            return;
        }
        float perDay = odds(world) * TrapMath.CRIME_ROUNDS_PER_DAY;
        // Minutes of real play, not offences per in-game day. Nobody has an
        // instinct for "0.7 dziennie" -- everybody has one for "one every half
        // hour", and getting those two confused is what shipped a town that
        // was robbed three times in seven minutes.
        int everyMin = perDay <= 0 ? 0 : Math.max(1, Math.round(20f / perDay));
        out.append(TrapNotes.say(perDay <= 0 ? "   spokojnie" : "   coś się dzieje co ",
                        Formatting.GRAY))
                .append(perDay <= 0 ? Text.empty()
                        : TrapNotes.say("~" + everyMin + " min",
                                everyMin < 8 ? Formatting.RED : Formatting.WHITE));

        out.append(TrapNotes.figure("\n  ludność ", String.valueOf(TrapHomes.population()),
                        Formatting.WHITE))
                .append(TrapNotes.figure("   bieda ", Math.round(hardship() * 100) + "%",
                        hardship() > 0.4f ? Formatting.RED : Formatting.WHITE))
                .append(TrapNotes.figure("   pora ", night(world)
                                ? "noc x" + TrapMath.NIGHT_CRIME : "dzień",
                        night(world) ? Formatting.RED : Formatting.WHITE))
                .append(TrapNotes.figure("   heat ", String.valueOf(TrapLaw.serverHeat()),
                        Formatting.WHITE));

        out.append(TrapNotes.figure("\n  policja zbija to o ",
                        Math.round(TrapPolice.deterrence() * 100) + "%",
                        TrapPolice.deterrence() > 0 ? Formatting.GREEN : Formatting.RED))
                .append(TrapNotes.say("   " + TrapPolice.onDuty() + " na ulicy",
                        Formatting.DARK_GRAY));

        out.append(TrapNotes.say("\n  od początku", Formatting.DARK_GRAY));
        for (Kind kind : Kind.values()) {
            out.append(TrapNotes.figure("   " + kind.display().toLowerCase(
                            java.util.Locale.ROOT) + " ", String.valueOf(countOf(kind)),
                    kind == Kind.MURDER && countOf(kind) > 0
                            ? Formatting.DARK_RED : Formatting.WHITE));
        }
        out.append(TrapNotes.figure("\n  wykryte ", solved + "/" + total(), Formatting.WHITE))
                .append(TrapNotes.figure("   skradziono ", stolen + "e", Formatting.RED))
                .append(TrapNotes.figure("   odzyskano ", recovered + "e", Formatting.GREEN));

        if (OPEN.isEmpty()) {
            out.append(TrapNotes.say("\n  Żadna sprawa nie jest otwarta.", Formatting.GREEN));
        } else {
            for (Case sprawa : OPEN) {
                long left = Math.max(0, sprawa.cold - world.getTime()) / 20;
                out.append(TrapNotes.say("\n    " + sprawa.kind.display(), Formatting.YELLOW))
                        .append(TrapNotes.say("   " + sprawa.victim, Formatting.WHITE))
                        .append(TrapNotes.say("   " + sprawa.where.getX() + ", "
                                + sprawa.where.getZ(), Formatting.DARK_GRAY))
                        .append(TrapNotes.say("   zostało " + left + "s",
                                left < 30 ? Formatting.RED : Formatting.DARK_GRAY));
            }
        }
        who.sendMessage(out, false);
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-crime.txt");
        OPEN.clear();
        TALLY.clear();
        solved = 0;
        cold = 0;
        stolen = 0;
        recovered = 0;
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                try {
                    read(line);
                } catch (Exception bad) {
                    TrapCraft.LOGGER.warn("skipped an unreadable crime line: {}", bad.toString());
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the crime book: {}", failure.toString());
        }
        TrapCraft.LOGGER.info("crime: {} on the books, {} open, {} solved",
                total(), OPEN.size(), solved);
    }

    private static void read(String line) {
        String[] head = line.trim().split("\\s+", 2);
        if (head.length < 2) {
            return;
        }
        switch (head[0]) {
            case "solved" -> solved = Integer.parseInt(head[1].trim());
            case "cold" -> cold = Integer.parseInt(head[1].trim());
            case "stolen" -> stolen = Long.parseLong(head[1].trim());
            case "recovered" -> recovered = Long.parseLong(head[1].trim());
            case "tally" -> {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) {
                    try {
                        TALLY.put(Kind.valueOf(parts[1]), Integer.parseInt(parts[2]));
                    } catch (IllegalArgumentException gone) {
                        // A kind this version no longer has. Struck off.
                    }
                }
            }
            case "case" -> {
                // Two names ride at the end -- the suspect and the victim --
                // and both can hold a space, so they are the LAST two fields of
                // a limited split with the victim greedy. Anything new goes
                // before them, the housing register's rule.
                String[] parts = line.trim().split("\\s+", 15);
                if (parts.length < 15) {
                    return;
                }
                Case sprawa = new Case(UUID.fromString(parts[1]), Kind.valueOf(parts[2]),
                        parts[13], parts[14], parts[3],
                        new BlockPos(Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                                Integer.parseInt(parts[6])), Long.parseLong(parts[7]));
                sprawa.loot = Integer.parseInt(parts[8]);
                sprawa.cold = Long.parseLong(parts[9]);
                sprawa.body = "-".equals(parts[10]) ? null : UUID.fromString(parts[10]);
                sprawa.home = "-".equals(parts[11]) ? null : UUID.fromString(parts[11]);
                sprawa.till = "-".equals(parts[12]) ? null : spot(parts[12]);
                OPEN.add(sprawa);
            }
            default -> {
            }
        }
    }

    /** "x,y,z" back into a block. Commas, because spaces are the field split. */
    private static BlockPos spot(String packed) {
        String[] parts = packed.split(",");
        return parts.length < 3 ? null : new BlockPos(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            out.append("solved ").append(solved).append('\n');
            out.append("cold ").append(cold).append('\n');
            out.append("stolen ").append(stolen).append('\n');
            out.append("recovered ").append(recovered).append('\n');
            for (Kind kind : Kind.values()) {
                out.append("tally ").append(kind.name()).append(' ')
                        .append(countOf(kind)).append('\n');
            }
            for (Case sprawa : OPEN) {
                out.append("case ").append(sprawa.id).append(' ').append(sprawa.kind.name())
                        .append(' ').append(sprawa.dimension).append(' ')
                        .append(sprawa.where.getX()).append(' ').append(sprawa.where.getY())
                        .append(' ').append(sprawa.where.getZ()).append(' ')
                        .append(sprawa.day).append(' ').append(sprawa.loot).append(' ')
                        .append(sprawa.cold).append(' ')
                        .append(sprawa.body == null ? "-" : sprawa.body).append(' ')
                        .append(sprawa.home == null ? "-" : sprawa.home).append(' ')
                        .append(sprawa.till == null ? "-" : sprawa.till.getX() + ","
                                + sprawa.till.getY() + "," + sprawa.till.getZ()).append(' ')
                        .append(sprawa.suspect.replace(' ', '_')).append(' ')
                        .append(sprawa.victim.replace('\n', ' ')).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the crime book: {}", failure.toString());
        }
    }
}
