package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is standing at which machine, and the strangers who come to play them.
 *
 * Two things that turn out to be one thing. A floor needs a rule about
 * occupancy the moment anything other than the owner can use a machine, and
 * the reason anything other than the owner uses a machine is the punters
 * below.
 *
 * <h2>One machine, one player</h2>
 *
 * Claimed on the way in, released when the screen goes or the player walks
 * off. The release is POLLED rather than hooked: there is no server-side event
 * for a screen closing, and threading a release call through seven handlers
 * and every path out of them -- closing, disconnecting, dying, being
 * teleported -- is seven places to forget it. Watching what is true is cheaper
 * than being told, and it cannot leave a machine locked forever because
 * somebody's client crashed.
 *
 * <h2>Punters</h2>
 *
 * Villagers wander in to a wired machine, play a few rounds and leave. They
 * bring their own money -- see {@link TrapHouse#punterStaked} -- so a casino
 * with a floor makes a slow, honest income at exactly the edge its cabinets
 * advertise. They only turn up near loaded chunks, which is to say near
 * somebody, so a casino earns while you are around rather than while you sleep.
 * That is deliberate: money that arrives whether or not anybody is playing is
 * not a casino, it is a farm.
 */
public final class TrapFloor {

    // --- occupancy ------------------------------------------------------------

    /** Who has a machine, and since when. */
    private record Seat(UUID who, boolean player, long since) {
    }

    private static final Map<String, Seat> SEATS = new HashMap<>();
    /** How far you can wander before the machine decides you have left. */
    private static final double LEAVE_RANGE = 7.0;
    /** A seat nobody can be found for is freed after this. */
    private static final int STALE_TICKS = 20 * 60;

    private static String key(ServerWorld world, BlockPos pos) {
        return world.getRegistryKey().getValue() + " "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Whoever is on this machine, or null. */
    public static UUID occupant(ServerWorld world, BlockPos pos) {
        Seat seat = SEATS.get(key(world, pos));
        return seat == null ? null : seat.who();
    }

    /** Take a machine, if it's free. */
    public static boolean claim(ServerWorld world, BlockPos pos, UUID who, boolean isPlayer) {
        String at = key(world, pos);
        Seat seat = SEATS.get(at);
        if (seat != null && !seat.who().equals(who)) {
            return false;
        }
        SEATS.put(at, new Seat(who, isPlayer, world.getTime()));
        return true;
    }

    public static void release(ServerWorld world, BlockPos pos, UUID who) {
        String at = key(world, pos);
        Seat seat = SEATS.get(at);
        if (seat != null && seat.who().equals(who)) {
            SEATS.remove(at);
        }
    }

    /**
     * Free the seats nobody is really in.
     *
     * A player counts as still playing while their screen is open AND they are
     * within reach. Both, because a screen can be left open by a client that
     * has stopped talking to us, and standing next to a machine with your
     * inventory open is not playing it.
     */
    private static void sweep(MinecraftServer server) {
        List<String> done = new ArrayList<>();
        SEATS.forEach((at, seat) -> {
            ServerWorld world = worldOf(server, at);
            BlockPos pos = TrapHouse.posOf(at);
            if (world == null || pos == null) {
                done.add(at);
                return;
            }
            if (seat.player()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(seat.who());
                boolean playing = player != null
                        && player.getWorld() == world
                        && player.currentScreenHandler != player.playerScreenHandler
                        && player.getBlockPos().isWithinDistance(pos, LEAVE_RANGE);
                if (!playing) {
                    done.add(at);
                }
                return;
            }
            // A punter's seat lives exactly as long as the punter does.
            if (!(world.getEntity(seat.who()) instanceof VillagerEntity punter)
                    || !punter.isAlive()) {
                done.add(at);
                return;
            }
            if (world.getTime() - seat.since() > STALE_TICKS) {
                done.add(at);
            }
        });
        done.forEach(SEATS::remove);
    }

    private static ServerWorld worldOf(MinecraftServer server, String wire) {
        String name = TrapHouse.worldOf(wire);
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(name)) {
                return world;
            }
        }
        return null;
    }

    // --- punters --------------------------------------------------------------

    private static final String PUNTER_TAG = "trapcraft_punter";
    /** Ticks between one attempt to send somebody in. */
    private static final int ARRIVAL_TICKS = 400;
    /** How often an attempt actually produces a punter. */
    private static final float ARRIVAL_CHANCE = 0.55f;
    /** Most punters on the floor at once, across the whole server. */
    private static final int MAX_PUNTERS = 6;
    /** Ticks between one punter's rounds. Slow enough to watch. */
    private static final int ROUND_TICKS = 45;
    /** How far out they arrive, so they walk the last few blocks in. */
    private static final int APPROACH = 6;
    /** Rounds spent trying to reach the machine before giving up and being there. */
    private static final int WALK_ROUNDS = 3;

    /** One punter mid-session. */
    private static final class Punter {
        final UUID id;
        final String at;
        final UUID house;
        final int stake;
        int roundsLeft;
        int wait;
        int won;
        int lost;
        /**
         * Rounds' worth of patience for getting there.
         *
         * Separate from roundsLeft, because a punter who spent their whole
         * visit walking round a fence has not played anything and the machine
         * they never reached has been locked the entire time.
         */
        int walking = WALK_ROUNDS;

        Punter(UUID id, String at, UUID house, int stake, int rounds) {
            this.id = id;
            this.at = at;
            this.house = house;
            this.stake = stake;
            this.roundsLeft = rounds;
            this.wait = ROUND_TICKS;
        }
    }

    private static final List<Punter> PUNTERS = new ArrayList<>();

    /** Is this one ours, this session? */
    private static boolean known(UUID id) {
        for (Punter punter : PUNTERS) {
            if (punter.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    // --- wiring up ------------------------------------------------------------

    public static void register() {
        // Before the block's own onUse, so a machine somebody else is on never
        // opens in the first place. Same reason TrapHouse wires cards here.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND
                    || !(player instanceof ServerPlayerEntity gambler)
                    || !(world instanceof ServerWorld server)) {
                return ActionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            if (!TrapHouse.isMachine(world.getBlockState(pos).getBlock())) {
                return ActionResult.PASS;
            }
            // The slot machine's upper half is the same machine as its lower.
            BlockPos seat = TrapHouse.floorOf(server, pos);
            UUID on = occupant(server, seat);
            if (on != null && !on.equals(gambler.getUuid())) {
                gambler.sendMessage(Text.literal(who(server, on) + " is on that one.")
                        .formatted(Formatting.GRAY), true);
                world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        SoundCategory.BLOCKS, 0.6F, 0.6F);
                return ActionResult.SUCCESS;
            }
            claim(server, seat, gambler.getUuid(), true);
            return ActionResult.PASS;
        });

        // A punter outlives a restart -- they are a persistent villager and
        // the session that owned them is gone. Without this the floor slowly
        // fills with named strangers standing at machines forever, which is
        // both litter and, since they hold no seat, a lie about who is playing.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(
                (entity, world) -> {
                    if (entity instanceof VillagerEntity villager
                            && villager.getCommandTags().contains(PUNTER_TAG)
                            && !known(villager.getUuid())) {
                        villager.discard();
                    }
                });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 10 == 0) {
                sweep(server);
            }
            if (!PUNTERS.isEmpty()) {
                tickPunters(server);
            }
            if (server.getTicks() % ARRIVAL_TICKS == 0) {
                maybeArrive(server);
            }
        });
    }

    private static String who(ServerWorld world, UUID id) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
        if (player != null) {
            return player.getGameProfile().getName();
        }
        return world.getEntity(id) instanceof VillagerEntity punter
                && punter.getCustomName() != null
                ? punter.getCustomName().getString() : "Somebody";
    }

    // --- somebody walks in ----------------------------------------------------

    /**
     * Pick one wired machine and, maybe, send somebody to it.
     *
     * One attempt for the whole server rather than one per machine, so a floor
     * of twenty cabinets does not draw twenty times the trade of a floor of
     * one. Building more machines gives your customers more to choose from; it
     * does not multiply them.
     */
    private static void maybeArrive(MinecraftServer server) {
        maybeArrive(server, false);
    }

    private static void maybeArrive(MinecraftServer server, boolean forced) {
        if (PUNTERS.size() >= MAX_PUNTERS
                || (!forced && server.getOverworld().getRandom().nextFloat() > ARRIVAL_CHANCE)) {
            return;
        }
        List<String> open = new ArrayList<>();
        TrapHouse.wires().forEach((at, id) -> {
            TrapHouse.House house = TrapHouse.byId(id);
            if (house == null || house.balance <= 0) {
                return;   // a casino that cannot pay has nothing to come for
            }
            ServerWorld world = worldOf(server, at);
            BlockPos pos = TrapHouse.posOf(at);
            if (world == null || pos == null
                    || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return;
            }
            if (occupant(world, pos) == null) {
                open.add(at);
            }
        });
        if (open.isEmpty()) {
            return;
        }
        String at = open.get(server.getOverworld().getRandom().nextInt(open.size()));
        arrive(server, at);
    }

    private static void arrive(MinecraftServer server, String at) {
        ServerWorld world = worldOf(server, at);
        BlockPos pos = TrapHouse.posOf(at);
        UUID houseId = TrapHouse.wires().get(at);
        TrapHouse.House house = TrapHouse.byId(houseId);
        if (world == null || pos == null || house == null) {
            return;
        }
        var random = world.getRandom();

        // A few blocks out, so they walk the last of it and the room sees
        // somebody arrive rather than somebody appear.
        BlockPos from = doorway(world, pos, random);
        if (from == null) {
            return;   // no room to walk in from; nobody comes tonight
        }

        VillagerEntity punter = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (punter == null) {
            return;
        }
        punter.refreshPositionAndAngles(from, random.nextFloat() * 360.0F, 0.0F);
        punter.setPersistent();
        punter.setSilent(true);
        punter.addCommandTag(PUNTER_TAG);
        // NITWIT or they take a job and start trading, which is somebody
        // else's feature turning up inside this one.
        punter.setVillagerData(punter.getVillagerData().withProfession(
                world.getRegistryManager().getOrThrow(RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(VillagerProfession.NITWIT)));

        int stake = STAKES[random.nextInt(STAKES.length)];
        // Never a stake the vault could not settle: a punter who breaks the
        // bank is a punter who took the owner's money away while they were
        // stood somewhere else entirely.
        while (stake > 8 && !TrapHouse.covers(house, stake, TrapHouse.TOP_SLOT)) {
            stake /= 4;
        }
        if (!TrapHouse.covers(house, stake, TrapHouse.TOP_SLOT)) {
            punter.discard();
            return;
        }
        punter.setCustomName(Text.literal("Punter  ·  " + stake + "e a go")
                .formatted(Formatting.WHITE));
        punter.setCustomNameVisible(true);

        // BEFORE spawnEntity, not after. spawnEntity fires ENTITY_LOAD
        // synchronously, the orphan sweep there asks whether this punter is
        // one of ours, and for two lines it was not -- so every punter ever
        // sent in was discarded by our own litter cleanup at the instant it
        // appeared. Nobody saw a single villager for the whole of 1.0.134.
        Punter session = new Punter(punter.getUuid(), at, houseId, stake,
                2 + random.nextInt(5));
        PUNTERS.add(session);
        if (!world.spawnEntity(punter)) {
            PUNTERS.remove(session);
            return;
        }

        // Walk in. If the pathing gives up -- a wall, a roof, a machine on a
        // ledge -- tickPunters puts them at the machine anyway, because a
        // punter stuck on the wrong side of a fence is a machine that never
        // frees up.
        punter.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.5);
        claim(world, pos, punter.getUuid(), false);
        TrapCraft.LOGGER.info("punter in at {} {} {}, {}e a go",
                pos.getX(), pos.getY(), pos.getZ(), stake);
    }

    private static final int[] STAKES = {8, 32, 128};

    // --- and plays ------------------------------------------------------------

    private static void tickPunters(MinecraftServer server) {
        List<Punter> leaving = new ArrayList<>();
        for (Punter punter : PUNTERS) {
            ServerWorld world = worldOf(server, punter.at);
            BlockPos pos = TrapHouse.posOf(punter.at);
            TrapHouse.House house = TrapHouse.byId(punter.house);
            if (world == null || pos == null || house == null
                    || !(world.getEntity(punter.id) instanceof VillagerEntity body)
                    || !body.isAlive()) {
                leaving.add(punter);
                continue;
            }
            if (--punter.wait > 0) {
                continue;
            }
            punter.wait = ROUND_TICKS;

            // Close enough to be playing it, or put there. A few seconds of
            // walking is atmosphere; a minute of failed pathing is a machine
            // locked by somebody stuck behind a fence.
            if (!body.getBlockPos().isWithinDistance(pos, 2.5)) {
                if (punter.walking-- > 0) {
                    body.getNavigation().startMovingTo(
                            pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.5);
                    continue;
                }
                body.refreshPositionAndAngles(nextTo(world, pos), 0.0F, 0.0F);
            }
            // A villager Brain re-picks its own destination every tick and
            // will happily wander off mid-session, so once they are at the
            // machine they are rooted. Same treatment the contract buyer and
            // a called dealer get, and for the same reason.
            body.getNavigation().stop();
            body.setAiDisabled(true);
            body.getLookControl().lookAt(Vec3d.ofCenter(pos));

            play(world, pos, punter, house);
            if (--punter.roundsLeft <= 0) {
                leaving.add(punter);
                leave(world, body, punter);
            }
        }
        for (Punter gone : leaving) {
            PUNTERS.remove(gone);
            ServerWorld world = worldOf(server, gone.at);
            BlockPos pos = TrapHouse.posOf(gone.at);
            if (world != null && pos != null) {
                release(world, pos, gone.id);
            }
        }
    }

    /** One round, played against the machine they are standing at. */
    private static void play(ServerWorld world, BlockPos pos, Punter punter,
                             TrapHouse.House house) {
        TrapHouse.punterStaked(house, punter.stake);
        float rtp = returnOf(world.getBlockState(pos).getBlock());
        int back = Math.round(punter.stake
                * TrapMath.punterRound(rtp, new java.util.Random(world.getRandom().nextLong())));
        int paid = TrapHouse.punterWon(house, back);

        if (paid > punter.stake) {
            punter.won += paid - punter.stake;
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5, pos.getY() + 1.4, pos.getZ() + 0.5, 10, 0.4, 0.3, 0.4, 0.02);
            world.playSound(null, pos, SoundEvents.ENTITY_VILLAGER_CELEBRATE,
                    SoundCategory.NEUTRAL, 0.5F, 1.0F);
            if (paid >= punter.stake * 10) {
                world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                        pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
                        40, 0.5, 0.5, 0.5, 0.3);
                world.playSound(null, pos, SoundEvents.ENTITY_PLAYER_LEVELUP,
                        SoundCategory.NEUTRAL, 0.7F, 1.2F);
            }
        } else {
            punter.lost += punter.stake - paid;
            world.spawnParticles(ParticleTypes.SMOKE,
                    pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0.01);
            world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(),
                    SoundCategory.NEUTRAL, 0.4F, 0.9F);
        }
    }

    /** What the machine under them pays back, so the books match the paytable. */
    private static float returnOf(net.minecraft.block.Block block) {
        if (block == TrapContent.slotMachine) {
            return TrapMath.slotRtp(5);
        }
        if (block == TrapContent.scratch) {
            return TrapMath.SCRATCH_MEASURED_RTP;
        }
        if (block == TrapContent.climb) {
            return TrapMath.CLIMB_RETURN;
        }
        // Roulette, The Drop, the coin toss and blackjack all sit within half a
        // percent of each other; one number for the three is closer than the
        // model's own noise.
        return 0.97f;
    }

    /** They've had enough. Say so, and go. */
    private static void leave(ServerWorld world, VillagerEntity body, Punter punter) {
        int net = punter.won - punter.lost;
        world.spawnParticles(net >= 0 ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
                body.getX(), body.getY() + 1.8, body.getZ(), 8, 0.3, 0.2, 0.3, 0.02);
        world.playSound(null, body.getBlockPos(),
                net >= 0 ? SoundEvents.ENTITY_VILLAGER_YES : SoundEvents.ENTITY_VILLAGER_NO,
                SoundCategory.NEUTRAL, 0.6F, 1.0F);
        body.discard();
    }

    /**
     * Somewhere with room to stand, a short walk from the machine.
     *
     * Checked rather than assumed. Dropping a villager at a fixed offset puts
     * them inside a wall about as often as not indoors, and a villager inside
     * a wall suffocates -- which reads as the casino killing its own
     * customers.
     */
    private static BlockPos doorway(ServerWorld world, BlockPos pos,
                                    net.minecraft.util.math.random.Random random) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int away = 2 + random.nextInt(APPROACH);
            BlockPos spot = new BlockPos(
                    pos.getX() + (int) Math.round(Math.cos(angle) * away),
                    pos.getY(),
                    pos.getZ() + (int) Math.round(Math.sin(angle) * away));
            for (int drop = 0; drop <= 2; drop++) {
                BlockPos at = spot.down(drop);
                if (world.getBlockState(at).isAir() && world.getBlockState(at.up()).isAir()
                        && !world.getBlockState(at.down()).isAir()) {
                    return at;
                }
            }
        }
        return null;
    }

    /** A standable block beside the machine, or the machine's own square. */
    private static BlockPos nextTo(ServerWorld world, BlockPos pos) {
        for (net.minecraft.util.math.Direction side
                : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos beside = pos.offset(side);
            if (world.getBlockState(beside).isAir() && world.getBlockState(beside.up()).isAir()) {
                return beside;
            }
        }
        return pos.up();
    }

    /**
     * /floor -- who is on what, and why nobody came.
     *
     * Written the day a punter bug shipped that nobody could see: the feature
     * was running perfectly and killing its own villagers on the spawn line,
     * and from inside the game that is identical to a feature that was never
     * written. `/floor now` forces an arrival and says out loud which test
     * turned it away.
     */
    public static void registerCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("floor")
                                .executes(context -> report(context.getSource()))
                                .then(net.minecraft.server.command.CommandManager.literal("now")
                                        .requires(source -> source.hasPermissionLevel(2))
                                        .executes(context -> force(context.getSource())))));
    }

    private static int report(net.minecraft.server.command.ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        int wired = TrapHouse.wires().size();
        int loaded = 0;
        int free = 0;
        for (String at : TrapHouse.wires().keySet()) {
            ServerWorld world = worldOf(server, at);
            BlockPos pos = TrapHouse.posOf(at);
            if (world == null || pos == null
                    || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            loaded++;
            if (occupant(world, pos) == null) {
                free++;
            }
        }
        int seconds = ARRIVAL_TICKS / 20;
        Text out = Text.literal("Floor  ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(wired + " machines wired, " + loaded
                        + " loaded, " + free + " free").formatted(Formatting.GRAY))
                .append(Text.literal("\n  " + PUNTERS.size() + " of " + MAX_PUNTERS
                                + " punters in, " + SEATS.size() + " seats taken")
                        .formatted(Formatting.DARK_GRAY))
                .append(Text.literal("\n  One turns up about every "
                                + Math.round(seconds / ARRIVAL_CHANCE) + "s, if a wired "
                                + "machine is loaded and its vault isn't empty")
                        .formatted(Formatting.DARK_GRAY));
        source.sendFeedback(() -> out, false);
        return 1;
    }

    private static int force(net.minecraft.server.command.ServerCommandSource source) {
        int before = PUNTERS.size();
        maybeArrive(source.getServer(), true);
        boolean came = PUNTERS.size() > before;
        source.sendFeedback(() -> Text.literal(came
                        ? "Somebody's on their way."
                        : "Nobody came -- no wired machine is loaded, free and behind a "
                        + "vault with money in it.")
                .formatted(came ? Formatting.GREEN : Formatting.RED), false);
        return came ? 1 : 0;
    }

    private TrapFloor() {
    }
}
