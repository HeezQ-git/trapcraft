package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerType;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Somebody who is not from here.
 *
 * <h2>Why there is anybody from away at all</h2>
 *
 * Every customer in this mod is a resident. The casino pulls one, the shops
 * pull one, and that was the right correction when it was made: the strangers
 * who used to appear out of nowhere were a stand-in for a town that did not
 * exist, and deleting them is what made the town real.
 *
 * The town exists now, and the stand-in has become a ceiling. Measured on the
 * live floor: a casino's costs are linear in cabinets and its income is capped
 * by the town -- one arrival attempt every three seconds, {@code room()}
 * punters at a time, and {@link TrapMath#punterStakeCeiling} SHRINKING the
 * bets as the crowd grows, so the handle is near flat however many cabinets
 * are standing there. At fifty-one machines the floor could not break even at
 * any hour of any day. A city whose only customers are its own residents can
 * only ever be as busy as it is populous.
 *
 * So this is the one place in the mod allowed to conjure a villager, and it
 * pays for the privilege by never pretending the result lives here.
 *
 * <h2>One person, one place -- still</h2>
 *
 * A visitor never receives {@link TrapHomes#TENANT_TAG}. That is the
 * load-bearing sentence of the whole file: {@code TrapHomes.population()}
 * feeds rent, payroll, house reputation and the casino's own {@code room()}
 * cap, and a fake resident corrupts all four at once, silently, presenting
 * only as a town quietly richer and busier than anybody built it to be.
 *
 * They are also never sent home, because they have not got one. The failure
 * mode is already written down in {@link TrapFloor}: AI switched off to root
 * somebody at a machine and nothing switching it back on. A visitor's trip
 * ends by leaving the world.
 *
 * <h2>Who walks them</h2>
 *
 * Not this file. An errand hands the body to the venue that already knows how
 * to walk somebody to it -- {@link TrapFloor#seat} for a machine -- and the
 * venue calls {@link #errandDone} when it is finished with them. Re-solving
 * cross-town walking here would be re-learning three restarts' worth of
 * lessons about a pathfinder that gives out past forty blocks.
 */
public final class TrapVisitors {

    /** What marks somebody as passing through. Never TENANT_TAG. */
    public static final String TOURIST_TAG = "trapcraft_tourist";

    /**
     * What they are wearing, against the plains everybody local wears.
     *
     * Chosen over a name tag, a particle or anything custom because a villager
     * type costs no texture, no model and -- the reason it won -- not one
     * Polymer carrier. The pack booted with sixteen left in
     * BIOME_TRANSPARENT_BLOCK, so a feature that needs a new block or item is
     * a bill this one cannot afford to pay.
     */
    private static final List<RegistryKey<VillagerType>> FROM_AWAY = List.of(
            VillagerType.DESERT, VillagerType.JUNGLE, VillagerType.SAVANNA,
            VillagerType.SNOW, VillagerType.SWAMP, VillagerType.TAIGA);

    /** How far around the vault's column somebody turns up. */
    private static final int SCATTER = 8;
    /** Ticks between one attempt to bring somebody into town. */
    private static final int ARRIVE_TICKS = 20 * 15;
    /** Visitors a city gets before it has built anything for them. */
    private static final int BASE_VISITORS = 4;
    /** Least and most somebody arrives carrying. */
    private static final int PURSE_LOW = 150;
    private static final int PURSE_HIGH = 600;
    /** Errands in one trip. */
    private static final int MIN_ERRANDS = 1;
    private static final int MAX_ERRANDS = 3;
    /**
     * Ticks a visitor is given to finish the whole trip.
     *
     * A backstop, not a schedule. Every errand has its own patience inside the
     * venue that runs it; this is what stops a visitor whose venue forgot to
     * call {@link #errandDone} from standing in the square until the heat
     * death of the server.
     */
    private static final int TRIP_TICKS = 20 * 60 * 8;

    /** What somebody came to town to do. */
    public enum Errand {
        CASINO, SHOP, WARD
    }

    /** One visitor, mid-trip. */
    private static final class Visit {
        final UUID body;
        final ArrayDeque<Errand> itinerary;
        /** Their own money, from outside. Not the town's purse. */
        int purse;
        final boolean ill;
        final long expires;
        /** Somebody a venue is currently running. Left alone until it says so. */
        boolean busy;

        Visit(UUID body, ArrayDeque<Errand> itinerary, int purse, boolean ill, long expires) {
            this.body = body;
            this.itinerary = itinerary;
            this.purse = purse;
            this.ill = ill;
            this.expires = expires;
        }
    }

    private static final List<Visit> VISITS = new ArrayList<>();

    // --- who is in town -------------------------------------------------------

    /** How many visitors the city can hold at once. */
    public static int room() {
        // Scaled by what the city has built for them in a later pass. The
        // baseline is deliberately non-zero: an unimproved city still gets
        // visitors, because "not a rare event" was the requirement.
        return BASE_VISITORS;
    }

    public static int inTown() {
        return VISITS.size();
    }

    /** Is this body somebody passing through? */
    public static boolean isVisitor(UUID id) {
        for (Visit visit : VISITS) {
            if (visit.body.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static Visit visitOf(UUID id) {
        for (Visit visit : VISITS) {
            if (visit.body.equals(id)) {
                return visit;
            }
        }
        return null;
    }

    // --- their money ----------------------------------------------------------

    /**
     * Take a stake out of a visitor's own pocket.
     *
     * The counterpart of {@link TrapPayroll#spend} for somebody who is not on
     * the town's books. Fails CLOSED, for the same reason that one does: a
     * half-paid transaction is a duplication bug wearing a hat.
     *
     * {@link TrapMarket#minted} because this is money that arrived without a
     * pocket to arrive in -- it lands in a house's vault rather than on a
     * player, so it cannot ride in on {@code pay}, and an emerald the index
     * never saw is silent inflation.
     */
    public static boolean spend(UUID id, int amount) {
        Visit visit = visitOf(id);
        if (visit == null || amount <= 0 || visit.purse < amount) {
            return false;
        }
        visit.purse -= amount;
        TrapMarket.minted(amount);
        return true;
    }

    /** Winnings, which leave town in their pocket when they do. */
    public static void credit(UUID id, int amount) {
        Visit visit = visitOf(id);
        if (visit == null || amount <= 0) {
            return;
        }
        visit.purse += amount;
        TrapMarket.minted(-amount);
    }

    /** What a visitor could still put on one bet. */
    public static int purseOf(UUID id) {
        Visit visit = visitOf(id);
        return visit == null ? 0 : visit.purse;
    }

    // --- wiring up ------------------------------------------------------------

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!VISITS.isEmpty()) {
                tick(server);
            }
            if (server.getTicks() % ARRIVE_TICKS == 0) {
                maybeArrive(server);
            }
        });

        // A visitor outlives a restart the way a punter does, and the answer is
        // simpler: their trip ended when the server stopped. They are not
        // anybody's tenant and nothing in the register knows their name, so
        // there is nothing to give back.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof VillagerEntity villager
                    && villager.getCommandTags().contains(TOURIST_TAG)
                    && !isVisitor(villager.getUuid())) {
                villager.discard();
            }
        });
    }

    // --- somebody comes to town -----------------------------------------------

    /**
     * Where somebody arrives.
     *
     * The vault's COLUMN, not the vault. It is guaranteed to exist -- no
     * vault, no city -- which makes it the one anchor that cannot be missing,
     * but it is a hidden thing in a hole and nobody arrives in town by
     * climbing out of the treasury.
     *
     * MOTION_BLOCKING_NO_LEAVES so a treetop is not a pavement, and only ever
     * in a loaded chunk: {@code getTopY} on an unloaded one is the mistake
     * {@link TrapHeat} already carries a comment about.
     */
    public static BlockPos doorstep(ServerWorld world, BlockPos vault) {
        var random = world.getRandom();
        for (int tries = 0; tries < 12; tries++) {
            int x = vault.getX() + random.nextInt(SCATTER * 2 + 1) - SCATTER;
            int z = vault.getZ() + random.nextInt(SCATTER * 2 + 1) - SCATTER;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            BlockPos top = new BlockPos(x,
                    world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            BlockPos spot = TrapSpawn.near(world, top, 6);
            if (spot != null) {
                return spot;
            }
        }
        return null;
    }

    private static void maybeArrive(MinecraftServer server) {
        if (VISITS.size() >= room() || !TrapCity.founded()) {
            return;
        }
        ServerWorld world = null;
        for (ServerWorld candidate : server.getWorlds()) {
            if (candidate.getRegistryKey().getValue().toString().equals(TrapCity.vaultWorld())) {
                world = candidate;
                break;
            }
        }
        if (world == null) {
            return;
        }
        BlockPos at = doorstep(world, TrapCity.vaultAt());
        if (at == null) {
            return;
        }
        var random = world.getRandom();

        ArrayDeque<Errand> itinerary = new ArrayDeque<>();
        int errands = MIN_ERRANDS + random.nextInt(MAX_ERRANDS - MIN_ERRANDS + 1);
        boolean ill = random.nextFloat() < 0.25f;
        if (ill) {
            itinerary.add(Errand.WARD);
        }
        while (itinerary.size() < errands) {
            itinerary.add(Errand.CASINO);
        }

        VillagerEntity body = spawn(world, at);
        if (body == null) {
            return;
        }
        // Low-biased, like a punter's stake: most visitors are somebody on a
        // day out and a whale is worth noticing.
        int purse = PURSE_LOW + Math.min(random.nextInt(PURSE_HIGH - PURSE_LOW + 1),
                random.nextInt(PURSE_HIGH - PURSE_LOW + 1));
        VISITS.add(new Visit(body.getUuid(), itinerary, purse, ill,
                world.getTime() + TRIP_TICKS));
        TrapCraft.LOGGER.info("somebody's in town with {}e, here for {}", purse, itinerary);
    }

    /** Conjure one, dressed as somebody from somewhere else. */
    private static VillagerEntity spawn(ServerWorld world, BlockPos at) {
        VillagerEntity body = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (body == null) {
            return null;
        }
        body.refreshPositionAndAngles(at, world.getRandom().nextFloat() * 360.0F, 0.0F);
        var types = world.getRegistryManager().getOrThrow(RegistryKeys.VILLAGER_TYPE);
        body.setVillagerData(body.getVillagerData().withType(
                types.getOrThrow(FROM_AWAY.get(world.getRandom().nextInt(FROM_AWAY.size())))));
        body.addCommandTag(TOURIST_TAG);
        world.spawnEntity(body);
        return body;
    }

    // --- what they came for ---------------------------------------------------

    private static void tick(MinecraftServer server) {
        List<Visit> leaving = new ArrayList<>();
        for (Visit visit : VISITS) {
            ServerWorld world = null;
            VillagerEntity body = null;
            for (ServerWorld candidate : server.getWorlds()) {
                if (candidate.getEntity(visit.body) instanceof VillagerEntity found) {
                    world = candidate;
                    body = found;
                    break;
                }
            }
            if (body == null || !body.isAlive()) {
                leaving.add(visit);
                continue;
            }
            if (visit.busy) {
                continue;
            }
            if (visit.itinerary.isEmpty() || world.getTime() > visit.expires) {
                leaving.add(visit);
                leave(world, body, visit);
                continue;
            }
            if (!start(server, world, body, visit, visit.itinerary.peek())) {
                // Nowhere to do this one right now. Drop it and try whatever
                // else they came for rather than standing in the road; a city
                // with no free machine is not a reason to end somebody's day.
                visit.itinerary.poll();
            }
        }
        VISITS.removeAll(leaving);
    }

    private static boolean start(MinecraftServer server, ServerWorld world,
                                 VillagerEntity body, Visit visit, Errand errand) {
        if (errand == Errand.CASINO) {
            String wire = TrapFloor.freeWire(server, visit.purse);
            if (wire == null) {
                return false;
            }
            if (!TrapFloor.seat(server, wire, body, true)) {
                return false;
            }
            visit.busy = true;
            return true;
        }
        // SHOP and WARD land in the next pass. Until then they are not errands
        // anybody can be sent on, and saying so out loud beats a visitor
        // standing in the square waiting for a shop that will never call back.
        return false;
    }

    /**
     * A venue has finished with somebody.
     *
     * Called instead of the resident paths -- see the branch in
     * {@link TrapFloor#leave}. A visitor down {@code sendHome} or
     * {@code stayIn} is a statue at a cabinet.
     */
    public static void errandDone(UUID id) {
        Visit visit = visitOf(id);
        if (visit == null) {
            return;
        }
        visit.itinerary.poll();
        visit.busy = false;
    }

    /** Their day is over. They go, and their winnings go with them. */
    private static void leave(ServerWorld world, VillagerEntity body, Visit visit) {
        body.setAiDisabled(false);
        body.removeCommandTag(TOURIST_TAG);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
                body.getX(), body.getY() + 1.8, body.getZ(), 6, 0.3, 0.2, 0.3, 0.02);
        TrapCraft.LOGGER.info("somebody left town with {}e", visit.purse);
        // Not sendHome. They have not got one, and the resident departure path
        // is where a visitor turns into furniture.
        body.discard();
    }

    private TrapVisitors() {
    }
}
