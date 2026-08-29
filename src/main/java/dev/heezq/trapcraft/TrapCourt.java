package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
 * What happens after the cuffs.
 *
 * For as long as there has been a police force, an arrest has been the end of
 * the story: a copper got a hand on somebody, the money came straight back out
 * of the town's purse, and the only question a player ever had about crime was
 * whether their budget was big enough. That is a burglary alarm, not a justice
 * system -- and it made the whole of {@link TrapCrime} a tax with a chase
 * scene attached.
 *
 * A courthouse turns the arrest into a DATE. The case is parked, the victim
 * gets a hearing a few days out, and in the meantime they may spend money on
 * it. Then it is heard, and they either get everything back with damages on
 * top or they get nothing at all.
 *
 * <h2>Why building one is a real decision</h2>
 *
 * Because it can lose. A town with no courthouse gets whatever the purse can
 * cover, every time, quietly -- which is safe, small and boring. A town with
 * one is gambling every theft: {@link TrapMath#COURT_DAMAGES} on top when it
 * goes your way, and the loot plus whatever you spent on a lawyer when it does
 * not. Nobody is forced to build one, and that is the point of it being a
 * block rather than a rule.
 *
 * <h2>Who the court answers to</h2>
 *
 * Nobody, and deliberately. It is claimed by whoever puts it down, but every
 * case in town is heard here whoever the victim is -- there is no way to run a
 * courthouse that only tries your own cases, because a court you own and
 * control is not a court. The owner gets a plaque and the fees; the town gets
 * a place where thefts are settled.
 *
 * <h2>The police half of it</h2>
 *
 * The evidence term in {@link TrapMath#courtOdds} is the force's kit and
 * funding, and it is the reason the two systems are worth having in one town.
 * An unfunded nick catches fewer people AND loses the cases it does bring,
 * which is what an underfunded police force actually looks like from a
 * shopkeeper's side of the counter. Money spent on the station is money that
 * comes back through this door.
 *
 * @see TrapCrime for what was taken, and where it goes back to
 */
public final class TrapCourt {

    /** In-game days between the collar and the hearing. */
    public static final int LISTING_DAYS = 3;
    /** How often the diary is looked at. Once every few seconds is plenty. */
    private static final int CLOCK_TICKS = 20 * 5;

    // --- the building ---------------------------------------------------------

    /** One courthouse: where it stands, whose name is on it, what it has heard. */
    public static final class Court {
        final UUID id;
        final String dimension;
        final BlockPos pos;
        final UUID owner;
        String ownerName;
        String name;
        /** Cases heard here, and fees taken. For the plaque. */
        int heard;
        int won;
        int fees;

        Court(UUID id, String dimension, BlockPos pos, UUID owner, String ownerName,
              String name) {
            this.id = id;
            this.dimension = dimension;
            this.pos = pos;
            this.owner = owner;
            this.ownerName = ownerName;
            this.name = name;
        }

        public BlockPos pos() {
            return pos;
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

        public int heard() {
            return heard;
        }

        public int won() {
            return won;
        }

        public int fees() {
            return fees;
        }
    }

    // --- one case, from the collar to the verdict ------------------------------

    /**
     * A hearing in the diary.
     *
     * Everything here is a copy rather than a reference into {@link TrapCrime}:
     * the crime that was committed, what it was worth and who lost it are
     * settled facts by the time a case is listed, and holding the live object
     * would mean two files that can put one theft into two states. The only
     * thing that goes back across the line is the id, at the verdict.
     */
    public static final class Trial {
        final UUID caseId;
        final TrapCrime.Kind kind;
        final String suspect;
        /** What was robbed -- a house, a shop, a courier. */
        final String victim;
        /** Who gets paid, or null when the loser was a villager. */
        final UUID owner;
        final int loot;
        final long listed;
        /** Where it happened, so the case is heard at the nearest bench. */
        final String dimension;
        final BlockPos scene;
        long hearing;
        /** Rungs of representation bought, and what they cost so far. */
        int lawyer;
        int spent;
        /** Evidence, frozen at the collar -- the force that turned up, not today's. */
        float evidence;

        Trial(UUID caseId, TrapCrime.Kind kind, String suspect, String victim, UUID owner,
              int loot, long listed, String dimension, BlockPos scene) {
            this.caseId = caseId;
            this.kind = kind;
            this.suspect = suspect;
            this.victim = victim;
            this.owner = owner;
            this.loot = loot;
            this.listed = listed;
            this.dimension = dimension;
            this.scene = scene;
        }

        public UUID caseId() {
            return caseId;
        }

        public TrapCrime.Kind kind() {
            return kind;
        }

        public String suspect() {
            return suspect;
        }

        public String victim() {
            return victim;
        }

        public int loot() {
            return loot;
        }

        public int lawyer() {
            return lawyer;
        }

        public int spent() {
            return spent;
        }

        public long hearing() {
            return hearing;
        }

        /** Days still to wait, or zero when it is due. */
        public long waiting(MinecraftServer server) {
            return Math.max(0, hearing - TrapMarket.today(server));
        }

        /** The odds as they stand, which is what the board has to print. */
        public float odds() {
            return TrapMath.courtOdds(kind.provable(), lawyer, evidence);
        }

        /** What the next rung costs, or zero when there is no next rung. */
        public int nextFee() {
            return lawyer >= TrapMath.LAWYERS ? 0 : TrapMath.lawyerFee(loot, lawyer);
        }
    }

    private static final List<Court> COURTS = new ArrayList<>();
    private static final List<Trial> DIARY = new ArrayList<>();
    private static Path saveFile;

    private TrapCourt() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapCourt::load);
        registerCommand();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % CLOCK_TICKS == 0) {
                sit(server);
            }
        });
    }

    /**
     * /court -- what is listed, and /court hear to sit today.
     *
     * The same reason /raid and /stickup exist. A verdict is one roll three
     * in-game days after an arrest that itself only happens when a patrol
     * gets lucky, so "I built a courthouse and nothing ever happened" is
     * indistinguishable from a bug by playing. The listing half is not
     * privileged; anybody may read the diary, because it is a public court.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("court")
                                .executes(context -> {
                                    docket(context.getSource());
                                    return 1;
                                })
                                .then(net.minecraft.server.command.CommandManager
                                        .literal("hear")
                                        .requires(source -> source.hasPermissionLevel(2))
                                        .executes(context -> {
                                            int listed = DIARY.size();
                                            for (Trial trial : DIARY) {
                                                trial.hearing = 0;
                                            }
                                            sit(context.getSource().getServer());
                                            int left = DIARY.size();
                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("Rozprawy: "
                                                            + (listed - left) + " z " + listed
                                                            + (left > 0 ? ", reszta czeka na "
                                                            + "poszkodowanych" : ""))
                                                            .formatted(Formatting.GRAY),
                                                    false);
                                            return 1;
                                        }))));
    }

    private static void docket(net.minecraft.server.command.ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        var out = TrapNotes.headline("Wokanda", Formatting.GOLD);
        if (COURTS.isEmpty()) {
            source.sendFeedback(() -> out.append(TrapNotes.say(
                    "   W tym mieście nie ma sądu.", Formatting.GRAY))
                    .append(TrapNotes.under("Bez niego policja oddaje odzyskane pieniądze "
                            + "od razu -- tyle, ile miasto ma.")), false);
            return;
        }
        for (Court court : COURTS) {
            out.append(TrapNotes.say("\n  " + court.name, Formatting.WHITE))
                    .append(TrapNotes.figure("   rozpraw ", String.valueOf(court.heard),
                            Formatting.WHITE))
                    .append(TrapNotes.figure("   wygranych ", String.valueOf(court.won),
                            Formatting.GREEN));
        }
        if (DIARY.isEmpty()) {
            out.append(TrapNotes.say("\n  Nic nie czeka na rozprawę.", Formatting.DARK_GRAY));
        } else {
            for (Trial trial : DIARY) {
                out.append(TrapNotes.say("\n    " + trial.kind.display(), Formatting.YELLOW))
                        .append(TrapNotes.say("   " + trial.victim, Formatting.WHITE))
                        .append(TrapNotes.figure("   ", trial.loot + "e", Formatting.RED))
                        .append(TrapNotes.figure("   szanse ", percent(trial.odds()),
                                Formatting.AQUA))
                        .append(TrapNotes.say("   za " + trial.waiting(server) + " dni",
                                Formatting.DARK_GRAY));
            }
        }
        source.sendFeedback(() -> out, false);
    }

    public static List<Court> all() {
        return COURTS;
    }

    public static List<Trial> diary() {
        return DIARY;
    }

    public static boolean any() {
        return !COURTS.isEmpty();
    }

    public static Court at(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Court court : COURTS) {
            if (court.pos.equals(pos) && court.dimension.equals(dimension)) {
                return court;
            }
        }
        return null;
    }

    /** The listings this player is the victim in, soonest first. */
    public static List<Trial> mine(ServerPlayerEntity who) {
        List<Trial> out = new ArrayList<>();
        for (Trial trial : DIARY) {
            if (who.getUuid().equals(trial.owner)) {
                out.add(trial);
            }
        }
        out.sort((a, b) -> Long.compare(a.hearing, b.hearing));
        return out;
    }

    // --- opening one ----------------------------------------------------------

    public static void open(ServerWorld world, BlockPos pos, ServerPlayerEntity owner) {
        if (at(world, pos) != null) {
            return;
        }
        Court court = new Court(UUID.randomUUID(),
                world.getRegistryKey().getValue().toString(), pos.toImmutable(),
                owner.getUuid(), owner.getGameProfile().getName(),
                "Sąd " + owner.getGameProfile().getName());
        COURTS.add(court);
        save();
    }

    public static void closeCourt(ServerWorld world, BlockPos pos) {
        Court court = at(world, pos);
        if (court == null) {
            return;
        }
        COURTS.remove(court);
        save();
        // The diary is NOT thrown away with the building. Somebody knocking a
        // courthouse down mid-listing would otherwise delete every pending
        // case in town, which is a way to steal from your neighbours with a
        // pickaxe. The hearings still happen; they simply happen somewhere
        // else, or nowhere, and either way the money is still owed.
    }

    /**
     * The clerk's takings, into the owner's hands.
     *
     * A till, exactly like a shop's, and for the same reason: money that has
     * been earned but not yet picked up is money somebody has to walk to, and
     * a courthouse is a building the owner should have a reason to visit.
     */
    public static int collect(ServerPlayerEntity owner, Court court) {
        if (!owner.getUuid().equals(court.owner) || court.fees <= 0) {
            return 0;
        }
        int takings = court.fees;
        court.fees = 0;
        TrapMarket.handOver(owner, takings);
        TrapLedger.record(owner, TrapLedger.Source.STALL, takings);
        save();
        return takings;
    }

    public static void rename(Court court, String name) {
        court.name = name;
        save();
    }

    // --- listing a case -------------------------------------------------------

    /**
     * The police bring somebody in. Put it in the diary.
     *
     * Called by {@link TrapCrime#caught} and by nothing else, and the boolean
     * is load-bearing: a false means there is no court in this town and the
     * old behaviour stands, money straight back at the kerb. That is what
     * keeps a courthouse an addition rather than a thing every server is now
     * obliged to build before crime works again.
     *
     * @return true if it was listed
     */
    public static boolean file(MinecraftServer server, UUID caseId, TrapCrime.Kind kind,
                               String suspect, String victim, int loot, UUID owner,
                               String dimension, BlockPos scene) {
        if (COURTS.isEmpty() || loot <= 0) {
            return false;
        }
        long today = TrapMarket.today(server);
        Trial trial = new Trial(caseId, kind, suspect, victim, owner, loot, today,
                dimension, scene);
        trial.hearing = today + LISTING_DAYS;
        // Frozen now rather than read at the hearing. The force that made the
        // arrest is the force whose evidence is in the file, and a council
        // that cut the police budget the day after a collar has not thereby
        // weakened a statement that was already taken.
        trial.evidence = TrapPolice.deterrence();
        DIARY.add(trial);
        save();

        if (owner != null) {
            ServerPlayerEntity victimPlayer = server.getPlayerManager().getPlayer(owner);
            if (victimPlayer != null) {
                victimPlayer.sendMessage(TrapNotes.headline("SPRAWA W SĄDZIE",
                                Formatting.GOLD)
                        .append(TrapNotes.say("   " + kind.display() + " -- " + victim,
                                Formatting.WHITE))
                        .append(TrapNotes.say("   rozprawa za " + LISTING_DAYS + " dni",
                                Formatting.GRAY))
                        .append(TrapNotes.say("   szanse " + percent(trial.odds()),
                                Formatting.AQUA))
                        .append(TrapNotes.under("Idź do sądu i weź prawnika, jeśli chcesz "
                                + "je podnieść.")), false);
            }
        }
        return true;
    }

    /**
     * Buy the next rung of representation.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String hire(ServerPlayerEntity who, UUID caseId) {
        Trial trial = byCase(caseId);
        if (trial == null) {
            return "Tej sprawy nie ma już w wokandzie.";
        }
        if (!who.getUuid().equals(trial.owner)) {
            return "To nie twoja sprawa.";
        }
        if (trial.lawyer >= TrapMath.LAWYERS) {
            return "Lepszego prawnika w tym mieście nie ma.";
        }
        int fee = trial.nextFee();
        if (TrapMarket.wealthOf(who) < fee) {
            return "Potrzebujesz " + fee + "e.";
        }
        TrapMarket.take(who, fee);
        trial.lawyer++;
        trial.spent += fee;
        // The fee is the court's, and the court is a building somebody owns.
        // Split rather than swallowed: the clerk's cut sits in the courthouse
        // like takings in a till, waiting to be collected, and the rest is the
        // city's -- because a court IS a civic service and this is what paying
        // for one looks like.
        Court court = nearest(trial);
        int clerk = court == null ? 0 : Math.max(1, fee / 3);
        if (court != null) {
            court.fees += clerk;
        }
        TrapCity.receive(fee - clerk, TrapCity.Duty.INCOME);
        save();
        who.sendMessage(Text.literal("Prawnik " + trial.lawyer + " z " + TrapMath.LAWYERS
                        + ". ").formatted(Formatting.GREEN)
                .append(Text.literal("Szanse: " + percent(trial.odds()))
                        .formatted(Formatting.AQUA)), false);
        return null;
    }

    // --- the hearing ----------------------------------------------------------

    /**
     * Anything due today gets heard.
     *
     * On the day clock rather than a tick countdown, so a hearing is a DATE --
     * "trzy dni" means three sunrises whether or not anybody was logged in for
     * them, and a server that sat empty over a weekend comes back to a docket
     * that has moved rather than one frozen where it was.
     */
    private static void sit(MinecraftServer server) {
        if (DIARY.isEmpty()) {
            return;
        }
        long today = TrapMarket.today(server);
        for (Trial trial : List.copyOf(DIARY)) {
            if (today < trial.hearing) {
                continue;
            }
            // Adjourned until the plaintiff turns up, and it is not flavour.
            // A won case pays a courier's robbery straight into the victim's
            // hands, and there are no hands to pay it into when they are
            // logged out -- TrapCrime hands it back to the town instead, so
            // hearing this today would quietly cost them everything AND they
            // would never be told. Nobody is tried in their absence.
            if (trial.owner != null
                    && server.getPlayerManager().getPlayer(trial.owner) == null) {
                continue;
            }
            DIARY.remove(trial);
            boolean won = server.getOverworld().getRandom().nextFloat() < trial.odds();
            int back = TrapCrime.verdict(server, trial.caseId, won);
            Court court = nearest(trial);
            if (court != null) {
                court.heard++;
                if (won) {
                    court.won++;
                }
                gavel(server, court, won);
            }
            save();
            tell(server, trial, won, back);
        }
    }

    private static void tell(MinecraftServer server, Trial trial, boolean won, int back) {
        if (trial.owner == null) {
            return;
        }
        ServerPlayerEntity who = server.getPlayerManager().getPlayer(trial.owner);
        if (who == null) {
            return;
        }
        if (won) {
            who.sendMessage(TrapNotes.headline("WYGRANA SPRAWA", Formatting.GREEN)
                    .append(TrapNotes.say("   " + trial.kind.display() + " -- "
                            + trial.victim, Formatting.WHITE))
                    .append(TrapNotes.say("   " + trial.suspect + " skazany",
                            Formatting.GRAY))
                    .append(TrapNotes.say("   odzyskane " + back + "e", Formatting.GREEN))
                    .append(back < trial.loot
                            ? TrapNotes.under("Miasto nie miało na całość. Tyle było.")
                            : TrapNotes.under("Ze skradzionymi " + trial.loot
                                    + "e i odszkodowaniem.")), false);
            TrapAwards.grant(who, "verdict");
        } else {
            who.sendMessage(TrapNotes.headline("PRZEGRANA SPRAWA", Formatting.RED)
                    .append(TrapNotes.say("   " + trial.kind.display() + " -- "
                            + trial.victim, Formatting.WHITE))
                    .append(TrapNotes.say("   " + trial.loot + "e przepadło",
                            Formatting.RED))
                    .append(trial.spent > 0
                            ? TrapNotes.say("   i " + trial.spent + "e na prawnika",
                                    Formatting.DARK_GRAY)
                            : TrapNotes.under("Bez prawnika sąd rzadko wierzy na słowo.")),
                    false);
        }
    }

    /** A bang on the desk, if anybody is there to hear it. */
    private static void gavel(MinecraftServer server, Court court, boolean won) {
        ServerWorld world = TrapHospitals.worldOf(server, court.dimension);
        if (world == null || !world.isChunkLoaded(court.pos.getX() >> 4,
                court.pos.getZ() >> 4)) {
            return;
        }
        world.playSound(null, court.pos, SoundEvents.BLOCK_ANVIL_LAND,
                SoundCategory.BLOCKS, 0.5F, 1.6F);
        world.spawnParticles(won ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SMOKE,
                court.pos.getX() + 0.5, court.pos.getY() + 1.2, court.pos.getZ() + 0.5,
                14, 0.4, 0.4, 0.4, 0.02);
    }

    /**
     * Whichever courthouse is closest to where it happened.
     *
     * Nearest rather than "the town's", because a server with two cities on it
     * has two benches, and a burglary in one of them being heard in the other
     * is a headline nobody in either place understands. Dimension first: a
     * courthouse in the Nether does not sit on an Overworld theft.
     */
    private static Court nearest(Trial trial) {
        Court best = null;
        double closest = Double.MAX_VALUE;
        for (Court court : COURTS) {
            if (!court.dimension.equals(trial.dimension)) {
                continue;
            }
            double away = court.pos.getSquaredDistance(trial.scene);
            if (away < closest) {
                closest = away;
                best = court;
            }
        }
        // A theft in a dimension nobody built a court in is still heard --
        // by whatever bench exists, because the alternative is a case that
        // silently never resolves and money that is owed forever.
        return best != null ? best : COURTS.isEmpty() ? null : COURTS.get(0);
    }

    private static Trial byCase(UUID caseId) {
        for (Trial trial : DIARY) {
            if (trial.caseId.equals(caseId)) {
                return trial;
            }
        }
        return null;
    }

    public static String percent(float odds) {
        return Math.round(odds * 100) + "%";
    }

    // --- the book -------------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-court.txt");
        COURTS.clear();
        DIARY.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                try {
                    read(line);
                } catch (Exception bad) {
                    TrapCraft.LOGGER.warn("skipped an unreadable court line: {}",
                            bad.toString());
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the court book: {}", failure.toString());
        }
        TrapCraft.LOGGER.info("court: {} open, {} in the diary", COURTS.size(), DIARY.size());
    }

    private static void read(String line) {
        // Tab-separated, because both records end in a NAME that may hold
        // spaces -- the shop register's rule, learnt the hard way there.
        String[] parts = line.split("\t");
        if (parts.length < 2) {
            return;
        }
        switch (parts[0]) {
            case "court" -> {
                if (parts.length < 10) {
                    return;
                }
                Court court = new Court(UUID.fromString(parts[1]), parts[2],
                        new BlockPos(Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                                Integer.parseInt(parts[5])),
                        UUID.fromString(parts[6]), parts[8], parts[9]);
                String[] books = parts[7].split(",");
                if (books.length >= 3) {
                    court.heard = Integer.parseInt(books[0]);
                    court.won = Integer.parseInt(books[1]);
                    court.fees = Integer.parseInt(books[2]);
                }
                COURTS.add(court);
            }
            case "trial" -> {
                if (parts.length < 13) {
                    return;
                }
                String[] spot = parts[9].split(",");
                Trial trial = new Trial(UUID.fromString(parts[1]),
                        TrapCrime.Kind.valueOf(parts[2]), parts[11], parts[12],
                        "-".equals(parts[3]) ? null : UUID.fromString(parts[3]),
                        Integer.parseInt(parts[4]), Long.parseLong(parts[5]),
                        parts[10], new BlockPos(Integer.parseInt(spot[0]),
                        Integer.parseInt(spot[1]), Integer.parseInt(spot[2])));
                trial.hearing = Long.parseLong(parts[6]);
                String[] paid = parts[7].split(",");
                if (paid.length >= 2) {
                    trial.lawyer = Integer.parseInt(paid[0]);
                    trial.spent = Integer.parseInt(paid[1]);
                }
                trial.evidence = Float.parseFloat(parts[8]);
                DIARY.add(trial);
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
            for (Court court : COURTS) {
                out.append("court\t").append(court.id).append('\t').append(court.dimension)
                        .append('\t').append(court.pos.getX()).append('\t')
                        .append(court.pos.getY()).append('\t').append(court.pos.getZ())
                        .append('\t').append(court.owner).append('\t')
                        .append(court.heard).append(',').append(court.won).append(',')
                        .append(court.fees).append('\t').append(court.ownerName)
                        .append('\t').append(court.name).append('\n');
            }
            for (Trial trial : DIARY) {
                out.append("trial\t").append(trial.caseId).append('\t')
                        .append(trial.kind.name()).append('\t')
                        .append(trial.owner == null ? "-" : trial.owner).append('\t')
                        .append(trial.loot).append('\t').append(trial.listed).append('\t')
                        .append(trial.hearing).append('\t')
                        .append(trial.lawyer).append(',').append(trial.spent).append('\t')
                        .append(trial.evidence).append('\t')
                        .append(trial.scene.getX()).append(',').append(trial.scene.getY())
                        .append(',').append(trial.scene.getZ()).append('\t')
                        .append(trial.dimension).append('\t')
                        .append(trial.suspect.replace('\t', ' ')).append('\t')
                        .append(trial.victim.replace('\t', ' ')).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the court book: {}", failure.toString());
        }
    }
}
