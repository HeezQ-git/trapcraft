package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieVillagerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where the bitten go, and what the city pays to get them back.
 *
 * A zombie that gets through a window used to cost a landlord nothing: the
 * body turned, {@link TrapHomes} swept the zombie away, and a replacement was
 * stood up at the front door twelve seconds later. The person was decoration
 * and the register never noticed. This is the system that makes a bite cost
 * something, and the building that makes it survivable.
 *
 * <h2>Why it is built the way a house is</h2>
 *
 * Because the mailbox already taught everybody the grammar: put the block in
 * the room, click it, and be told what the room is missing. A ward has
 * different requirements from a house -- more beds, no dark corners, and
 * walls somebody actually built -- but it is the same {@link HomeSurvey} walk
 * and the same checklist, so nobody has to learn a second way of registering a
 * building.
 *
 * <h2>Who pays</h2>
 *
 * The city, out of {@link TrapCity}'s purse, per patient per day -- and the
 * fee goes into {@link TrapPayroll}, because a doctor is a townsperson and
 * their wage should come back through a shop door. It is the first thing in
 * this mod that takes money out of the treasury without anybody choosing to
 * spend it, which is the whole point: a purse with nothing but voluntary
 * outgoings is a scoreboard. An empty purse now means a ward that has not
 * been paid, and a ward that has not been paid keeps its patients.
 *
 * <h2>The three ways this can go</h2>
 *
 * <ol>
 *   <li>There is a ward with a free bed and the city can pay: one day away,
 *       then home. That is the happy path and it is meant to be boring.
 *   <li>There is a ward and no money: they stay in it, unhappily, until there
 *       is. The letters say so, so the landlord knows to go and shout at the
 *       treasury rather than at their walls.
 *   <li>There is no ward at all: they are ill at home, earning nothing, and
 *       after {@link #LOST_DAYS} they are gone for good. That is the reason to
 *       build the building.
 * </ol>
 *
 * <h2>And two patients who are nobody's tenant</h2>
 *
 * Somebody passing through pays their own bill at the door -- see
 * {@link TrapVisitors} -- and so does a player standing in the room getting
 * clean, which is {@link #detox}. Both are the same rule read from the other
 * side: the health service is a thing a city does for the people who live in
 * it and pay rates into it, and neither of those two is one of them.
 *
 * Between them they are most of why a ward is worth building. Bites are rare,
 * and a building whose only trade is the neighbour who left a window open is a
 * building nobody puts up.
 */
public final class TrapHospitals {

    /** Ticks between one ward being looked at again. Same clock as the houses. */
    private static final int ROUND_TICKS = 240;

    /** Marks a doctor's body, so one that outlived its ward can be found. */
    public static final String DOCTOR_TAG = "trapcraft_doctor";
    /** Marks somebody lying in a ward, so nothing walks them home. */
    public static final String PATIENT_TAG = "trapcraft_patient";

    // --- what a ward has to be ------------------------------------------------

    /** Beds are the ward's capacity, and one bed is not a hospital. */
    public static final int MIN_BEDS = 2;
    /** Floor squares. Deliberately over a house's {@link HomeSurvey#MIN_FLOOR}. */
    public static final int MIN_FLOOR = 24;
    /** How finished the shell has to be. A ward built of dirt is not a ward. */
    public static final float MIN_SHELL = HomeSurvey.SHELL_STEPS[1];

    // --- what it costs --------------------------------------------------------

    /**
     * Out of the city purse, per patient, per day of treatment.
     *
     * ponytail: a flat fee, against day-rates that run 6e to 140e a house.
     * Turn it into a per-grade table if the number ever needs to say something
     * more interesting than "this is expensive".
     *
     * Was 90, which was the wrong side of the line it was aiming at. The
     * intent has always been "hurts a young city, noise to an old one", and at
     * 90 -- 36 once The Clinic is up -- it was noise to BOTH: a treasury of
     * three thousand could cure the whole town twice over without noticing,
     * which makes a hospital a thing you build once and stop thinking about.
     * A ward should be the most expensive thing a city runs, because keeping
     * people alive is the most expensive thing a city does.
     */
    public static final int FEE = 450;
    /** What The Clinic knocks off the bill. A public health service. */
    public static final float CLINIC_OFF = 0.6f;
    /** In-game days in a bed once the doctors are being paid. */
    public static final int STAY_DAYS = 1;
    /** Days ill with nobody treating you before the illness wins. */
    public static final int LOST_DAYS = 4;
    /** What a day of no treatment does to the household's mood. */
    public static final int UNTREATED_MOOD = 9;

    /** One hospital. */
    public static final class Ward {
        final UUID id;
        final UUID owner;
        String ownerName;
        final String dimension;
        /** Where the block stands, which is where the survey is taken from. */
        BlockPos sign;
        /** Beds counted at the last inspection. The ward's capacity. */
        int beds;
        int floor;
        /** Did it pass its last inspection? A closed ward admits nobody. */
        boolean open;
        /** Running count of people sent home well, for the plaque. */
        int treated;
        UUID doctor;
        String name;

        Ward(UUID id, UUID owner, String ownerName, String dimension, BlockPos sign) {
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

        public int beds() {
            return beds;
        }

        public boolean open() {
            return open;
        }

        public int treated() {
            return treated;
        }

        /** Beds with nobody in them. */
        public int free() {
            return Math.max(0, beds - occupants(id));
        }
    }

    /**
     * Somebody who has been bitten.
     *
     * Keyed by the house rather than by a body, because a body is decoration
     * and this has to survive one being eaten, unloaded or discarded. The name
     * is the person -- it comes off the villager that turned, so the letter
     * says who it actually was rather than "a resident".
     */
    public static final class Patient {
        final UUID home;
        final String who;
        /** The ward treating them, or null while nobody is. */
        UUID ward;
        /** The day they are due out, once somebody is paying. */
        long due;
        /** Days that have gone by with no treatment. */
        int untreated;
        /** The last day this patient was processed, so a restart cannot double. */
        long seen = -1;
        UUID body;

        Patient(UUID home, String who) {
            this.home = home;
            this.who = who;
        }

        public UUID home() {
            return home;
        }

        public String who() {
            return who;
        }

        public UUID ward() {
            return ward;
        }

        public long due() {
            return due;
        }

        public int untreated() {
            return untreated;
        }
    }

    private static final List<Ward> WARDS = new ArrayList<>();
    private static final List<Patient> PATIENTS = new ArrayList<>();
    /**
     * Everything the city has ever paid a doctor. Kept for the books.
     *
     * A new outflow from the treasury that nothing logs is a purse that drifts
     * down for no visible reason, three systems away from the ward that ate
     * it. The city writes a row a day about everything else it does; this is
     * the column that stops "the vault keeps emptying" being a mystery.
     */
    private static long spent;
    private static Path saveFile;
    private static int cursor;

    private TrapHospitals() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapHospitals::load);
        // The bite itself. MOB_CONVERSION fires after the zombie has been put
        // in the world, with the villager it was made from still in hand --
        // which is the only moment anything knows both WHO was bitten and that
        // they were one of ours. The alternative is noticing a stray zombie
        // villager on a later pass and guessing.
        ServerLivingEntityEvents.MOB_CONVERSION.register(TrapHospitals::turned);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % ROUND_TICKS == 0) {
                rounds(server);
            }
        });
    }

    public static List<Ward> all() {
        return WARDS;
    }

    /**
     * An open ward with a bed spare, for somebody who is not on the register.
     *
     * The {@link Patient} list is keyed off a {@link TrapHomes.Home} because a
     * patient is a tenant who got bitten, and everything about that flow --
     * the mood, the letters home, the tenancy that ends if they die -- is
     * about a household. Somebody in town for the day has none of those.
     *
     * So a walk-in is deliberately NOT a Patient. They turn up ill, occupy a
     * bed for as long as it takes to be seen, pay, and go. Nothing is written
     * home, because there is no home to write to.
     */
    public static Ward walkIn(MinecraftServer server) {
        List<Ward> open = new ArrayList<>();
        for (Ward ward : WARDS) {
            ServerWorld world = worldOf(server, ward.dimension);
            if (world == null || !ward.open || ward.free() <= 0) {
                continue;
            }
            if (!world.isChunkLoaded(ward.sign.getX() >> 4, ward.sign.getZ() >> 4)) {
                continue;
            }
            open.add(ward);
        }
        return open.isEmpty() ? null
                : open.get(server.getOverworld().getRandom().nextInt(open.size()));
    }

    /** Somebody walked in off the street, paid, and was seen to. */
    public static void seen(ServerWorld world, Ward ward) {
        ward.treated++;
        world.playSound(null, ward.sign, SoundEvents.ENTITY_ZOMBIE_VILLAGER_CURE,
                SoundCategory.BLOCKS, 0.7F, 1.3F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, ward.sign.getX() + 0.5,
                ward.sign.getY() + 1.2, ward.sign.getZ() + 0.5, 20, 0.5, 0.6, 0.5, 0.04);
        save();
    }

    public static List<Patient> patients() {
        return PATIENTS;
    }

    /** Beds the city has, across every open ward. */
    public static int beds() {
        int beds = 0;
        for (Ward ward : WARDS) {
            if (ward.open) {
                beds += ward.beds;
            }
        }
        return beds;
    }

    /** Everything the doctors have been paid since the world was made. */
    public static long spent() {
        return spent;
    }

    // --- the bite -------------------------------------------------------------

    /**
     * A villager became a zombie villager. If it was a resident, admit them.
     *
     * The zombie is discarded rather than left standing, for the reason
     * {@link TrapHomes} discards them today: a tagged zombie that survives is a
     * resident who is dead for good in every system that reads the register,
     * and the last time one was allowed to live the village ended up with
     * ninety-eight of them. The illness moves into the books instead, where
     * something can actually happen to it.
     */
    private static void turned(MobEntity previous, MobEntity converted,
                               net.minecraft.entity.conversion.EntityConversionContext context) {
        if (!(previous instanceof VillagerEntity)
                || !(converted instanceof ZombieVillagerEntity)
                || !(converted.getWorld() instanceof ServerWorld world)) {
            return;
        }
        // Read off the ZOMBIE, not off the villager it was: a conversion
        // copies the name and every tag onto the new body, and the old one is
        // on its way to being discarded by the same call that got us here.
        TrapHomes.Home home = TrapHomes.homeOf(converted);
        if (home == null || home.tenant() == null) {
            return;
        }
        String who = converted.getCustomName() == null
                ? home.tenant() : converted.getCustomName().getString();
        BlockPos where = converted.getBlockPos();
        converted.discard();
        world.playSound(null, where, SoundEvents.ENTITY_ZOMBIE_VILLAGER_CONVERTED,
                SoundCategory.NEUTRAL, 0.9F, 0.8F);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, where.getX() + 0.5,
                where.getY() + 1.0, where.getZ() + 0.5, 20, 0.3, 0.5, 0.3, 0.02);
        admit(world, home, who, "został ugryziony");
    }

    /**
     * Somebody was hurt rather than bitten.
     *
     * The ward has had exactly one kind of patient since it was built, and the
     * reason is that a bite was the only thing in the mod that could hurt a
     * resident. Crime is the second, and it wants the same bed, the same bill
     * and the same four days of not being treated -- so it comes through this
     * door rather than growing a casualty ward of its own.
     *
     * @param what a past-tense phrase for the letters: "został pobity"
     * @see TrapCrime
     */
    public static void hurt(ServerWorld world, TrapHomes.Home home, String who, String what) {
        if (home == null || home.tenant() == null) {
            return;
        }
        admit(world, home, who, what);
    }

    /** Take somebody ill onto the books and find them a bed if there is one. */
    private static void admit(ServerWorld world, TrapHomes.Home home, String who, String what) {
        long day = TrapMarket.today(world.getServer());
        Patient patient = new Patient(home.id(), who);
        // Today is already dealt with: the bite IS today's news. Without this
        // the round that runs a few seconds later bills the city for a day of
        // treatment that has not happened yet.
        patient.seen = day;
        PATIENTS.add(patient);
        Ward ward = nearest(home);
        if (ward != null) {
            bed(world, patient, ward, day);
        }
        save();
        home.write(who + " " + what + "." + (ward == null
                ? " Nie ma szpitala, który by go przyjął."
                : " Leży w " + ward.name + "."));
        tell(world.getServer(), home, Text.literal(who).formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal(" z " + home.name() + " " + what + ". ")
                        .formatted(Formatting.GRAY))
                .append(ward == null
                        ? Text.literal("W tym mieście nie ma go gdzie leczyć -- zbuduj "
                                + "szybko szpital.").formatted(Formatting.RED)
                        : Text.literal("Trafił do " + ward.name + ". Wróci za dzień, "
                                + "a do tego czasu nic nie zarabia.").formatted(Formatting.GRAY)));
    }

    /** Put a patient in a ward: a bed, a due date, and a body standing in it. */
    private static void bed(ServerWorld world, Patient patient, Ward ward, long day) {
        patient.ward = ward.id;
        patient.due = day + STAY_DAYS;
        standIn(world.getServer(), patient, ward);
    }

    /**
     * The body in the bed, if the ward is awake to receive it.
     *
     * Separate from {@link #bed} because a bite in a corner of the map with
     * the hospital's chunks asleep would otherwise put somebody in a ward
     * nobody can see for the whole of their stay. The ward's own round calls
     * this again when it wakes -- and so does the case nobody plans for, which
     * is a patient eaten in their bed by the second zombie through the same
     * hole in the roof.
     */
    private static void standIn(MinecraftServer server, Patient patient, Ward ward) {
        ServerWorld theirs = worldOf(server, ward.dimension);
        if (theirs == null || !theirs.getChunkManager().isChunkLoaded(
                ward.sign.getX() >> 4, ward.sign.getZ() >> 4)) {
            return;   // the ward is asleep; the body turns up when it wakes
        }
        if (patient.body != null && theirs.getEntity(patient.body) != null) {
            return;
        }
        BlockPos stand = TrapSpawn.near(theirs, ward.sign.up());
        if (stand == null) {
            return;
        }
        VillagerEntity body = net.minecraft.entity.EntityType.VILLAGER.create(
                theirs, net.minecraft.entity.SpawnReason.EVENT);
        if (body == null) {
            return;
        }
        body.refreshPositionAndAngles(stand, theirs.getRandom().nextFloat() * 360f, 0f);
        body.setPersistent();
        body.setCustomName(Text.literal(patient.who).formatted(Formatting.GRAY));
        body.setCustomNameVisible(true);
        body.addCommandTag(PATIENT_TAG);
        body.addCommandTag(PATIENT_TAG + "_" + ward.id);
        // NITWIT for the reason every other body in this mod is one: anything
        // else takes a job off the nearest workstation and starts trading.
        body.setVillagerData(body.getVillagerData().withProfession(
                theirs.getRegistryManager()
                        .getOrThrow(net.minecraft.registry.RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(net.minecraft.village.VillagerProfession.NITWIT)));
        // Slow and quiet, because they are ill. A patient who sprints round
        // the ward is a person who does not need one.
        //
        // Amplifier 0, not 2. Slowness III takes about half a villager's pace
        // off, and half of "already slower than you" is a person who leans
        // against the nearest wall and is still there tomorrow -- which read
        // as a bug in the ward rather than as an illness. One level is a
        // shuffle: visibly unwell, and visibly a person.
        body.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.SLOWNESS, Integer.MAX_VALUE, 0,
                false, false, false));
        theirs.spawnEntity(body);
        patient.body = body.getUuid();
        theirs.spawnParticles(ParticleTypes.HAPPY_VILLAGER, stand.getX() + 0.5,
                stand.getY() + 1.0, stand.getZ() + 0.5, 12, 0.3, 0.4, 0.3, 0.01);
    }

    /**
     * The open ward with a free bed nearest this house.
     *
     * Same dimension only, and not out of tidiness: a body standing in an
     * unloaded chunk in another world is a body nothing can find, and the
     * register would spend the rest of the week trying to work out whether it
     * still existed.
     */
    private static Ward nearest(TrapHomes.Home home) {
        Ward best = null;
        double closest = Double.MAX_VALUE;
        for (Ward ward : WARDS) {
            if (!ward.open || !ward.dimension.equals(home.dimension()) || ward.free() <= 0) {
                continue;
            }
            double away = ward.sign.getSquaredDistance(home.anchor());
            if (away < closest) {
                closest = away;
                best = ward;
            }
        }
        return best;
    }

    /**
     * Take somebody who is not a tenant into a ward.
     *
     * The patient register is keyed on a HOUSE -- it exists to know which
     * household is short a wage and which mailbox to write to -- and a
     * shopkeeper has no house. Rather than bend that around somebody who does
     * not have one, this is the plain version: the nearest open ward in their
     * own world, and a spot inside it to stand.
     *
     * ponytail: takes no bed and pays no fee, so a shopkeeper's stay is
     * invisible to {@link #free} and to the treasury. Give them a Patient with
     * a nullable home if the ward ever needs to charge for them.
     *
     * @return the ward that took them, or null if the city has none
     */
    public static Ward takeIn(ServerWorld world, net.minecraft.entity.Entity body) {
        String here = world.getRegistryKey().getValue().toString();
        Ward best = null;
        double closest = Double.MAX_VALUE;
        for (Ward ward : WARDS) {
            if (!ward.open || !ward.dimension.equals(here)) {
                continue;
            }
            double away = ward.sign.getSquaredDistance(body.getBlockPos());
            if (away < closest) {
                closest = away;
                best = ward;
            }
        }
        if (best == null) {
            return null;
        }
        BlockPos spot = TrapSpawn.near(world, best.sign.up());
        if (spot == null) {
            return null;
        }
        body.refreshPositionAndAngles(spot, world.getRandom().nextFloat() * 360f, 0f);
        world.playSound(null, spot, SoundEvents.ENTITY_ZOMBIE_VILLAGER_CONVERTED,
                SoundCategory.NEUTRAL, 0.8F, 0.9F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, spot.getX() + 0.5,
                spot.getY() + 1.0, spot.getZ() + 0.5, 12, 0.3, 0.4, 0.3, 0.01);
        return best;
    }

    /** Everywhere in this world somebody could be lying ill. */
    public static List<BlockPos> wards(String dimension) {
        List<BlockPos> signs = new ArrayList<>();
        for (Ward ward : WARDS) {
            if (ward.dimension.equals(dimension)) {
                signs.add(ward.sign);
            }
        }
        return signs;
    }

    // --- who is away ----------------------------------------------------------

    /** How many of this household are ill, in a bed or waiting for one. */
    public static int awayFrom(UUID home) {
        int away = 0;
        for (Patient patient : PATIENTS) {
            if (patient.home.equals(home)) {
                away++;
            }
        }
        return away;
    }

    /** Everybody off this house's books, for the mailbox to read. */
    public static List<Patient> illAt(UUID home) {
        List<Patient> theirs = new ArrayList<>();
        for (Patient patient : PATIENTS) {
            if (patient.home.equals(home)) {
                theirs.add(patient);
            }
        }
        return theirs;
    }

    /** Is the person the letters are about the one in the bed? */
    public static boolean tenantAway(TrapHomes.Home home) {
        for (Patient patient : PATIENTS) {
            if (patient.home.equals(home.id()) && patient.who.equals(home.tenant())) {
                return true;
            }
        }
        return false;
    }

    private static int occupants(UUID ward) {
        int taken = 0;
        for (Patient patient : PATIENTS) {
            if (ward.equals(patient.ward)) {
                taken++;
            }
        }
        return taken;
    }

    /** The house is gone or empty: its patients go with it. */
    public static void forget(ServerWorld world, UUID home) {
        boolean any = false;
        for (int at = PATIENTS.size() - 1; at >= 0; at--) {
            if (PATIENTS.get(at).home.equals(home)) {
                clearBody(world, PATIENTS.remove(at));
                any = true;
            }
        }
        if (any) {
            save();
        }
    }

    // --- the daily round ------------------------------------------------------

    /**
     * One ward looked at, and every patient moved along a day.
     *
     * The ward half is round-robin for the reason the houses are -- a survey
     * is a flood fill and a city should not get slower as it grows. The
     * patient half is a short list of numbers and runs every pass, gated on
     * the day so a restart cannot bill the treasury twice for one morning.
     */
    private static void rounds(MinecraftServer server) {
        long day = TrapMarket.today(server);
        // Over a copy, and checked still to be on the list. One patient dying
        // evicts their household, which takes every OTHER patient of that
        // house off the register inside this loop -- an index walked down the
        // live list would then read past the end of it and take the tick with
        // it.
        for (Patient patient : List.copyOf(PATIENTS)) {
            if (PATIENTS.contains(patient)) {
                treat(server, patient, day);
            }
        }
        if (WARDS.isEmpty()) {
            return;
        }
        cursor = (cursor + 1) % WARDS.size();
        Ward ward = WARDS.get(cursor);
        ServerWorld world = worldOf(server, ward.dimension);
        if (world == null || !world.getChunkManager().isChunkLoaded(
                ward.sign.getX() >> 4, ward.sign.getZ() >> 4)) {
            return;
        }
        inspect(world, ward);
        keepDoctor(world, ward);
        // Everybody who should be lying in this one, actually lying in it.
        for (Patient patient : PATIENTS) {
            if (ward.id.equals(patient.ward)) {
                standIn(server, patient, ward);
                shuffle(world, ward, patient);
            }
        }
        // A bed that was empty when somebody was bitten in an unloaded corner
        // of the map. Beds go to whoever has been waiting, once there is one.
        if (ward.open && ward.free() > 0) {
            for (Patient waiting : PATIENTS) {
                if (waiting.ward == null && ward.free() > 0
                        && sameWorld(server, waiting, ward)) {
                    bed(world, waiting, ward, day);
                    save();
                }
            }
        }
    }

    /**
     * A patient moves about their ward.
     *
     * They were spawned beside the block and then left entirely to their own
     * devices, and a villager Brain with no bed, no job site and no bell has
     * nothing it wants -- so the stroll task takes them as far as the first
     * wall and the rest of the day is spent leaning on it. Given somewhere to
     * be every round, they cross the room, which is what a ward looks like.
     *
     * Somewhere INSIDE it. The ward keeps no bounds -- the survey is a flood
     * fill that costs too much to run for a walk, and the Readout it hands
     * back does not carry the squares. A roof overhead is the cheap version of
     * the same question and it is the one that matters: it is what stops a
     * patient shuffling out of the front door and off across the fields.
     *
     * ponytail: a radius off the floor count and a ceiling check, not the room
     * set. If a ward is ever built as two rooms round a corner, this keeps
     * them in the half with the block in it, and that is a nicer failure than
     * plumbing a flood fill through the screen's Readout.
     */
    private static void shuffle(ServerWorld world, Ward ward, Patient patient) {
        if (patient.body == null
                || !(world.getEntity(patient.body) instanceof VillagerEntity body)
                || !body.isAlive() || body.isSleeping()) {
            return;   // gone, or resting, and rest is the treatment
        }
        int reach = Math.max(3, (int) Math.sqrt(Math.max(1, ward.floor)));
        var random = world.getRandom();
        for (int tries = 0; tries < 8; tries++) {
            BlockPos spot = ward.sign.add(random.nextInt(reach * 2 + 1) - reach, 0,
                    random.nextInt(reach * 2 + 1) - reach);
            for (int drop = 0; drop <= 1; drop++) {
                BlockPos at = spot.down(drop);
                if (TrapSpawn.safe(world, at) && roofed(world, at)) {
                    TrapHomes.walkTo(body, at);
                    return;
                }
            }
        }
    }

    /** Something over your head within a storey. Indoors, near enough. */
    private static boolean roofed(ServerWorld world, BlockPos spot) {
        for (int up = 2; up <= 6; up++) {
            BlockPos above = spot.up(up);
            if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameWorld(MinecraftServer server, Patient patient, Ward ward) {
        TrapHomes.Home home = TrapHomes.byId(patient.home);
        return home != null && home.dimension().equals(ward.dimension);
    }

    /**
     * A day of somebody's illness.
     *
     * Paid for, and they go home. Not paid for -- no ward, or no money in the
     * purse -- and the day is simply lost, which is the only lever this system
     * has and the reason both halves of it matter.
     */
    private static void treat(MinecraftServer server, Patient patient, long day) {
        if (patient.seen == day) {
            return;
        }
        patient.seen = day;
        TrapHomes.Home home = TrapHomes.byId(patient.home);
        if (home == null || home.tenant() == null) {
            // Their house was struck off or emptied by something that did not
            // come through evict(). The body goes with the record, or it
            // stands in a ward forever with a dead address on it.
            PATIENTS.remove(patient);
            clearBody(server.getOverworld(), patient);
            save();
            return;
        }
        ServerWorld world = TrapHomes.worldOf(server, home);
        if (world == null) {
            return;
        }
        Ward ward = patient.ward == null ? null : byId(patient.ward);
        if (ward == null || !ward.open) {
            // Their ward was knocked down or failed its inspection under them.
            patient.ward = null;
            clearBody(world, patient);
            Ward other = nearest(home);
            if (other != null) {
                bed(world, patient, other, day);
                save();
                return;
            }
            untreated(server, world, home, patient);
            return;
        }

        int fee = bill();
        if (!TrapCity.spend(fee)) {
            // The doctors have not been paid, so nobody is being treated. The
            // stay does not end and the day counts against them.
            patient.due = day + STAY_DAYS;
            untreated(server, world, home, patient);
            home.write("Oddział nie dostał zapłaty. " + patient.who + " wciąż tam leży.");
            return;
        }
        TrapPayroll.credit(fee);
        spent += fee;
        patient.untreated = 0;
        if (day < patient.due) {
            save();
            return;
        }
        discharge(server, world, home, patient, ward);
    }

    /** What one day of treatment costs, with the public health service on. */
    public static int bill() {
        return TrapCity.built(TrapCity.Work.CLINIC) ? Math.round(FEE * CLINIC_OFF) : FEE;
    }

    /**
     * How far a ward's room reaches from its own block.
     *
     * Deliberately generous and deliberately not the survey. {@link HomeSurvey}
     * knows the exact room and answering "is this player inside it" through the
     * flood fill would be a walk of the whole building four times a minute per
     * player, which is a lot of work to decide something a radius decides. What
     * this actually needs to be true is "standing in the ward rather than
     * across the street", and eight blocks is that.
     */
    public static final int ROOM = 8;

    /**
     * The open ward somebody is standing in, or null.
     *
     * Different question from {@link #at}, which wants the block itself and is
     * how the screen and the hammer find one. This is for a person in the room:
     * see {@link TrapAddiction} for the only caller and why a ward is worth
     * standing in.
     */
    public static Ward wardAround(ServerWorld world, BlockPos pos) {
        String here = world.getRegistryKey().getValue().toString();
        for (Ward ward : WARDS) {
            if (ward.open && ward.dimension.equals(here)
                    && ward.sign.isWithinDistance(pos, ROOM)) {
                return ward;
            }
        }
        return null;
    }

    /**
     * Somebody who is not ill, is not a resident, and is here to get clean.
     *
     * The whole of what a ward gets out of a player. Everything else in this
     * file is a bed with a body in it and a household waiting for them to come
     * home; a player has no household, cannot be admitted, and would be a
     * second kind of Patient with none of the fields that make one work. So
     * there is no patient here at all -- the money moves and nothing is
     * remembered, and what a detox actually IS lives in {@link TrapAddiction}
     * where the meters are.
     *
     * Paid by the player, not the city, and that is the same rule a visitor is
     * held to. The health service is a thing the town does for its RESIDENTS,
     * funded out of a treasury they pay rates into; somebody who owns half the
     * city and has been smoking their own stock is not on it.
     *
     * @return true if the fee was paid and the doctors have been seen
     */
    public static boolean detox(ServerPlayerEntity who, Ward ward) {
        int fee = bill();
        int duty = TrapCity.dutyOn(fee, TrapCity.Duty.INCOME);
        if (TrapMarket.wealthOf(who) < fee + duty) {
            return false;
        }
        // collect, not take: the doctors are people and their wage comes back
        // through a shop door. Same pipe the visitors' fee runs down.
        TrapMarket.collect(who, fee + duty);
        TrapPayroll.credit(fee);
        TrapCity.receive(duty, TrapCity.Duty.INCOME);
        TrapLedger.record(who, TrapLedger.Source.TAX, -(fee + duty));
        spent += fee;
        ServerWorld world = worldOf(who.getServer(), ward.dimension);
        if (world != null) {
            seen(world, ward);
        }
        return true;
    }

    /** Nobody treated them today. Eventually that is the end of it. */
    private static void untreated(MinecraftServer server, ServerWorld world,
                                  TrapHomes.Home home, Patient patient) {
        patient.untreated++;
        TrapHomes.sicken(home, UNTREATED_MOOD);
        if (patient.untreated < LOST_DAYS) {
            home.write(patient.who + " nie czuje się lepiej. Dzień " + patient.untreated + ".");
            save();
            return;
        }
        PATIENTS.remove(patient);
        clearBody(world, patient);
        save();
        home.write(patient.who + " nie przeżył. Nikt nie przyszedł.");
        tell(server, home, Text.literal(patient.who).formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal(" z " + home.name() + " zmarł. "
                        + LOST_DAYS + " dni i żaden szpital go nie przyjął.")
                        .formatted(Formatting.GRAY)));
        // Losing the person the letters are about ends the tenancy. Losing
        // somebody else is a household that has had a bad week.
        if (patient.who.equals(home.tenant())) {
            // Generic on purpose: this path is walked by a bite and by a
            // stabbing now, and the letter already said which it was.
            TrapHomes.moveOut(server, world, home, "nie przeżył");
        }
    }

    /** Well again, and walked back through their own front door. */
    private static void discharge(MinecraftServer server, ServerWorld world,
                                  TrapHomes.Home home, Patient patient, Ward ward) {
        PATIENTS.remove(patient);
        ward.treated++;
        ServerWorld theirs = worldOf(server, ward.dimension);
        if (theirs != null) {
            clearBody(theirs, patient);
            theirs.playSound(null, ward.sign, SoundEvents.ENTITY_ZOMBIE_VILLAGER_CURE,
                    SoundCategory.BLOCKS, 0.8F, 1.2F);
            theirs.spawnParticles(ParticleTypes.HAPPY_VILLAGER, ward.sign.getX() + 0.5,
                    ward.sign.getY() + 1.2, ward.sign.getZ() + 0.5, 30, 0.5, 0.6, 0.5, 0.05);
        }
        save();
        home.write(patient.who + " wrócił z " + ward.name + " i czuje się dobrze.");
        TrapHomes.backFromTheWard(world, home);
        tell(server, home, Text.literal(patient.who).formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal(" wyszedł z " + ward.name + " i wrócił do "
                        + home.name() + ".").formatted(Formatting.GRAY)));
    }

    /** The landlord, and whoever runs the ward, hear about their own people. */
    private static void tell(MinecraftServer server, TrapHomes.Home home, Text what) {
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(home.owner());
        if (owner != null) {
            owner.sendMessage(what, false);
        }
    }

    private static void clearBody(ServerWorld world, Patient patient) {
        if (patient.body == null) {
            return;
        }
        for (ServerWorld other : world.getServer().getWorlds()) {
            var body = other.getEntity(patient.body);
            if (body != null) {
                body.discard();
            }
        }
        patient.body = null;
    }

    // --- the building ---------------------------------------------------------

    public static Ward byId(UUID id) {
        for (Ward ward : WARDS) {
            if (ward.id.equals(id)) {
                return ward;
            }
        }
        return null;
    }

    /** The ward whose block is standing at this spot. */
    public static Ward at(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Ward ward : WARDS) {
            if (pos.equals(ward.sign) && ward.dimension.equals(dimension)) {
                return ward;
            }
        }
        return null;
    }

    /**
     * What the room round this block is, and whether it will do.
     *
     * The same walk the mailbox takes, graded against a different list. A
     * hospital is not a house with a plaque on it: it wants beds enough to be
     * a ward, a floor big enough to walk a trolley round, light in every
     * corner, walls somebody built rather than dug, and a cupboard to keep the
     * supplies in.
     */
    public static TrapHomes.Readout look(ServerWorld world, BlockPos pos) {
        return TrapHomes.look(world, pos, null);
    }

    /** Every reason this room is not a hospital, in the order to fix them. */
    public static String fault(TrapHomes.Readout reading) {
        if (reading.clash()) {
            return "To wnętrze domu już zapisanego w rejestrze. "
                    + "Szpital potrzebuje własnego budynku.";
        }
        if (reading.buried()) {
            return "Blok szpitala stoi w litym bloku. Postaw go w POWIETRZU "
                    + "wewnątrz pomieszczenia, nie w ścianie.";
        }
        if (!reading.sealed()) {
            // With the coordinates. "It is not sealed" is a verdict; the spot
            // the fill got out at is a job. The mailbox board has said this
            // properly since it shipped and the ward never learned to.
            return "Jest dziura -- ucieka na " + TrapPolice.where(reading.leak())
                    + ", licząc od " + TrapPolice.where(reading.measuredFrom()) + ".";
        }
        if (reading.exits() == 0) {
            return "Nie ma wejścia. Potrzebne drzwi na zewnątrz.";
        }
        if (reading.floor() < MIN_FLOOR) {
            return "Masz " + reading.floor() + " kratek podłogi. Oddział potrzebuje "
                    + MIN_FLOOR + ".";
        }
        if (reading.beds() < MIN_BEDS) {
            return reading.beds() == 0 ? "Nie ma w środku łóżka."
                    : "Jedno łóżko to pokój gościnny. Oddział potrzebuje " + MIN_BEDS + ".";
        }
        if (reading.dark() > 0) {
            return reading.dark() + " dark " + (reading.dark() == 1 ? "corner" : "corners")
                    + ". Nikt tu nie operuje przy świeczce.";
        }
        if (reading.finished() < MIN_SHELL) {
            return "Wykończenie " + Math.round(reading.finished() * 100) + "%. Oddział musi "
                    + "mieć " + Math.round(MIN_SHELL * 100) + "%, a ten jest głównie z: "
                    + reading.roughest() + ".";
        }
        if (!reading.storage()) {
            return "Nie ma gdzie trzymać zaopatrzenia. Potrzebna skrzynia albo beczka.";
        }
        return null;
    }

    /**
     * Put the room this block is standing in on the books.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String found(ServerPlayerEntity who, ServerWorld world, BlockPos pos) {
        if (at(world, pos) != null) {
            return "To już jest szpital.";
        }
        // No city, no hospital -- there is no purse to pay the doctors out of,
        // and an unpaid ward is a building that does nothing at all.
        if (!TrapCity.founded()) {
            return "Nie ma jeszcze miasta -- nie ma kto płacić lekarzom. "
                    + "Ktoś musi najpierw postawić skarbiec miasta.";
        }
        TrapHomes.Readout reading = look(world, pos);
        String no = fault(reading);
        if (no != null) {
            return no;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        Ward ward = new Ward(UUID.randomUUID(), who.getUuid(),
                who.getGameProfile().getName(), dimension, pos.toImmutable());
        ward.name = spare(who.getGameProfile().getName() + "'s hospital");
        // Graded off the survey already in hand rather than by calling
        // inspect(), which would walk the same walls a second time and then
        // announce an opening the player is being told about in the same
        // breath anyway. Same reasoning as the housing register's found().
        ward.open = true;
        ward.beds = reading.beds();
        ward.floor = reading.floor();
        WARDS.add(ward);
        boolean first = WARDS.size() == 1;
        save();
        keepDoctor(world, ward);
        if (first) {
            announce(who.getServer(), Text.literal("MIASTO MA SZPITAL")
                    .formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal("\n  " + who.getGameProfile().getName() + " otworzył "
                            + ward.name + ", łóżek: " + ward.beds + ". Każdy ugryziony "
                            + "w mieście jest teraz leczony na koszt kasy miasta.")
                            .formatted(Formatting.GRAY)));
        }
        return null;
    }

    /** "Alma's hospital", then "Alma's hospital 2". */
    /**
     * Whatever the anvil called it.
     *
     * TrapShops' rule and TrapShops' reason: the anvil is the only text entry
     * this mod has, and a directory of "HeezQ's hospital 2", "HeezQ's hospital
     * 3" is a directory nobody can read. Blank names are ignored rather than
     * stored -- an unnamed item should not be able to wipe a plaque -- and the
     * name still goes through {@link #spare} so two wards cannot share one.
     */
    public static void rename(Ward ward, String name) {
        String trimmed = name == null ? "" : name.replace('\n', ' ').trim();
        if (trimmed.isBlank() || trimmed.equals(ward.name)) {
            return;
        }
        ward.name = spare(trimmed);
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
        for (Ward ward : WARDS) {
            if (name.equals(ward.name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-measure a ward that is already on the books.
     *
     * A hospital that stops qualifying CLOSES rather than being struck off:
     * the beds it had are no longer offered, its patients are moved on at
     * their next day, and the moment somebody puts the torch back it opens
     * again. Striking it off would mean losing the name, the count of people
     * it has sent home, and any chance of the owner understanding why.
     */
    public static TrapHomes.Readout inspect(ServerWorld world, Ward ward) {
        TrapHomes.Readout reading = look(world, ward.sign);
        // A ward wider than one chunk, half of it asleep, measures as a small
        // sealed room with no beds -- and closes. The station had this and it
        // flapped open and shut all day; the shape here is the same one.
        if (reading.asleep()) {
            return reading;
        }
        boolean was = ward.open;
        ward.open = fault(reading) == null;
        ward.beds = reading.beds();
        ward.floor = reading.floor();
        if (was != ward.open) {
            save();
            ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(ward.owner);
            if (owner != null) {
                owner.sendMessage(ward.open
                        ? Text.literal(ward.name + " znowu działa. ")
                                .formatted(Formatting.GREEN)
                                .append(Text.literal(ward.beds + " łóżek.")
                                        .formatted(Formatting.GRAY))
                        : Text.literal(ward.name + " zostało zamknięte: ")
                                .formatted(Formatting.RED)
                                .append(Text.literal(String.valueOf(fault(reading)))
                                        .formatted(Formatting.GRAY)), false);
            }
        }
        return reading;
    }

    /** The hospital came down. Its patients look for another bed tomorrow. */
    public static void lost(ServerWorld world, BlockPos pos) {
        Ward ward = at(world, pos);
        if (ward == null) {
            return;
        }
        WARDS.remove(ward);
        for (Patient patient : PATIENTS) {
            if (ward.id.equals(patient.ward)) {
                patient.ward = null;
                clearBody(world, patient);
            }
        }
        if (ward.doctor != null && world.getEntity(ward.doctor) != null) {
            world.getEntity(ward.doctor).discard();
        }
        sweep(world, ward);
        save();
        ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(ward.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal(ward.name + " znika z rejestru. ")
                    .formatted(Formatting.YELLOW)
                    .append(Text.literal("Kto w nim leżał, potrzebuje innego łóżka.")
                            .formatted(Formatting.GRAY)), false);
        }
    }

    /** Bodies belonging to a ward that no longer exists. */
    private static void sweep(ServerWorld world, Ward ward) {
        var box = new net.minecraft.util.math.Box(ward.sign).expand(48);
        for (var body : world.getEntitiesByClass(VillagerEntity.class, box,
                found -> found.getCommandTags().contains(PATIENT_TAG + "_" + ward.id)
                        || found.getCommandTags().contains(DOCTOR_TAG + "_" + ward.id))) {
            body.discard();
        }
    }

    /**
     * One doctor, stood in the ward, for as long as it is open.
     *
     * Decoration, like every other body in this mod, and worth the twenty
     * lines: the city is being billed for somebody, and a bill for a person
     * nobody has ever seen is a number in a menu. This is the person.
     */
    private static void keepDoctor(ServerWorld world, Ward ward) {
        String tag = DOCTOR_TAG + "_" + ward.id;
        List<? extends VillagerEntity> standing = world.getEntitiesByType(
                net.minecraft.entity.EntityType.VILLAGER,
                found -> found.isAlive() && found.getCommandTags().contains(tag));
        if (!ward.open) {
            for (var going : standing) {
                going.discard();
            }
            ward.doctor = null;
            return;
        }
        for (int extra = standing.size() - 1; extra >= 1; extra--) {
            standing.get(extra).discard();
        }
        if (!standing.isEmpty()) {
            ward.doctor = standing.get(0).getUuid();
            if (!standing.get(0).getBlockPos().isWithinDistance(ward.sign, 24)) {
                TrapHomes.walkTo(standing.get(0), ward.sign);
            }
            return;
        }
        BlockPos stand = TrapSpawn.near(world, ward.sign.up());
        if (stand == null) {
            return;
        }
        VillagerEntity doctor = net.minecraft.entity.EntityType.VILLAGER.create(
                world, net.minecraft.entity.SpawnReason.EVENT);
        if (doctor == null) {
            return;
        }
        doctor.refreshPositionAndAngles(stand, world.getRandom().nextFloat() * 360f, 0f);
        doctor.setPersistent();
        doctor.setCustomName(Text.literal("Dr " + TrapHomes.nameFor(
                (int) ward.id.getLeastSignificantBits())).formatted(Formatting.WHITE));
        doctor.setCustomNameVisible(true);
        doctor.addCommandTag(DOCTOR_TAG);
        doctor.addCommandTag(tag);
        // CLERIC, unlike everybody else in this mod, and on purpose: it is the
        // one vanilla profession that reads as a person in a coat rather than
        // as a farmer, and a cleric's workstation is a brewing stand -- which
        // is not something a ward has lying about for them to start trading at.
        doctor.setVillagerData(doctor.getVillagerData().withProfession(
                world.getRegistryManager()
                        .getOrThrow(net.minecraft.registry.RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(net.minecraft.village.VillagerProfession.CLERIC)));
        world.spawnEntity(doctor);
        ward.doctor = doctor.getUuid();
        save();
    }

    static ServerWorld worldOf(MinecraftServer server, String dimension) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(dimension)) {
                return world;
            }
        }
        return null;
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-wards.txt");
        WARDS.clear();
        PATIENTS.clear();
        spent = 0;
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                try {
                    read(line);
                } catch (Exception bad) {
                    // One line, not the file. The same rule the housing
                    // register learned the hard way.
                    TrapCraft.LOGGER.warn("skipped an unreadable ward line: {}", bad.toString());
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the wards: {}", failure.toString());
        }
        TrapCraft.LOGGER.info("hospitals: {} wards, {} ill", WARDS.size(), PATIENTS.size());
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
        if ("spent".equals(head[0])) {
            spent = Long.parseLong(head[1].trim());
        } else if ("ward".equals(head[0])) {
            String[] parts = line.trim().split("\\s+", 14);
            if (parts.length < 14) {
                return;
            }
            Ward ward = new Ward(UUID.fromString(parts[1]), UUID.fromString(parts[2]),
                    parts[3], parts[4], new BlockPos(Integer.parseInt(parts[5]),
                    Integer.parseInt(parts[6]), Integer.parseInt(parts[7])));
            ward.beds = Integer.parseInt(parts[8]);
            ward.floor = Integer.parseInt(parts[9]);
            ward.open = "1".equals(parts[10]);
            ward.treated = Integer.parseInt(parts[11]);
            ward.doctor = "-".equals(parts[12]) ? null : UUID.fromString(parts[12]);
            ward.name = parts[13];
            WARDS.add(ward);
        } else if ("patient".equals(head[0])) {
            String[] parts = line.trim().split("\\s+", 8);
            if (parts.length < 8) {
                return;
            }
            Patient patient = new Patient(UUID.fromString(parts[1]), parts[7]);
            patient.ward = "-".equals(parts[2]) ? null : UUID.fromString(parts[2]);
            patient.due = Long.parseLong(parts[3]);
            patient.untreated = Integer.parseInt(parts[4]);
            patient.seen = Long.parseLong(parts[5]);
            patient.body = "-".equals(parts[6]) ? null : UUID.fromString(parts[6]);
            PATIENTS.add(patient);
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            out.append("spent ").append(spent).append('\n');
            for (Ward ward : WARDS) {
                out.append("ward ").append(ward.id).append(' ').append(ward.owner).append(' ')
                        .append(ward.ownerName).append(' ').append(ward.dimension).append(' ')
                        .append(ward.sign.getX()).append(' ').append(ward.sign.getY())
                        .append(' ').append(ward.sign.getZ()).append(' ');
                out.append(ward.beds).append(' ').append(ward.floor).append(' ')
                        .append(ward.open ? 1 : 0).append(' ').append(ward.treated).append(' ')
                        .append(ward.doctor == null ? "-" : ward.doctor).append(' ')
                        .append(ward.name.replace('\n', ' ')).append('\n');
            }
            for (Patient patient : PATIENTS) {
                out.append("patient ").append(patient.home).append(' ')
                        .append(patient.ward == null ? "-" : patient.ward).append(' ')
                        .append(patient.due).append(' ').append(patient.untreated).append(' ')
                        .append(patient.seen).append(' ')
                        .append(patient.body == null ? "-" : patient.body).append(' ')
                        .append(patient.who.replace(' ', '_')).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the wards: {}", failure.toString());
        }
    }

    private static void announce(MinecraftServer server, Text what) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(what, false);
        }
    }
}
