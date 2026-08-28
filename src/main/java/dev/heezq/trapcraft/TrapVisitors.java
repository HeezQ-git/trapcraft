package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
     * How many turn up already unwell.
     *
     * A quarter, so a ward with beds free has a steady trade that is not
     * somebody's neighbour being bitten -- which is the only patient a
     * hospital has ever had, and a thin business to be in.
     */
    private static final float ILL_SHARE = 0.25f;
    /**
     * Ticks between tries at an errand, and how many before giving up on it.
     *
     * Two seconds apart, twenty times: somebody hangs around the best part of
     * a minute waiting for a machine or a counter to come free before writing
     * that errand off. A floor with every cabinet busy is what a floor worth
     * visiting LOOKS like, so a visitor who cannot wait for one is a visitor
     * who never plays.
     */
    private static final int START_RETRY = 40;
    private static final int START_TRIES = 20;
    /** Near enough a ward door to be seen to. */
    private static final double AT_THE_DOOR = 3.0;
    /** Ticks between one shove along the way. See TrapFloor for why. */
    private static final int NUDGE_TICKS = 20;
    /** Patience for a walk, flat and per block, as the floor sizes it. */
    private static final int WALK_GRACE = 200;
    private static final int TICKS_PER_BLOCK = 20;
    /**
     * Ticks a visitor is given to finish the whole trip.
     *
     * A backstop, not a schedule. Every errand has its own patience inside the
     * venue that runs it; this is what stops a visitor whose venue forgot to
     * call {@link #errandDone} from standing in the square until the heat
     * death of the server.
     */
    private static final int TRIP_TICKS = 20 * 60 * 8;

    /**
     * What somebody came to town to do.
     *
     * The display names are here rather than at the one call site because
     * /visitors used to print the deque straight -- {@code [CASINO, SHOP]} --
     * which is the only English left in a readout a player reads, and adding
     * two more constants would have doubled it.
     */
    public enum Errand {
        CASINO("kasyno"),
        SHOP("sklep"),
        WARD("lekarz"),
        CLUB("klub"),
        STALL("stragan");

        private final String display;

        Errand(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    /** An itinerary as somebody would say it out loud. */
    private static String said(ArrayDeque<Errand> itinerary) {
        StringBuilder out = new StringBuilder();
        for (Errand errand : itinerary) {
            out.append(out.isEmpty() ? "" : ", ").append(errand.display());
        }
        return out.toString();
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
        /** Where they are headed under their own steam. Only the ward. */
        BlockPos ward;
        UUID wardId;
        long wardBy;
        int nudge;
        /** Ticks before trying the next errand again, and how often we have. */
        int cooldown;
        int tries;

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

    /**
     * How many visitors the city can hold at once.
     *
     * The baseline is deliberately non-zero: an unimproved city still gets
     * visitors, because "not a rare event" was the requirement and gating the
     * whole feature behind 28,000e of unbuilt works would have failed it.
     *
     * What the works buy is MORE of them, and that is the first return on the
     * treasury a player can stand in the street and watch walk past. Every
     * other effect a public work has is a number you need a spreadsheet to
     * notice; this one is a crowd.
     */
    public static int room() {
        int room = BASE_VISITORS;
        if (TrapCity.built(TrapCity.Work.LAMPS)) {
            room += 2;      // somewhere to shop, lit
        }
        if (TrapCity.built(TrapCity.Work.TRAM)) {
            room += 3;      // the biggest single lift: it is how people arrive
        }
        if (TrapCity.built(TrapCity.Work.ROADS)) {
            room += 1;
        }
        if (TrapCity.built(TrapCity.Work.SCHOOL)) {
            room += 2;      // a city worth visiting rather than passing through
        }
        return room;
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
     *
     * Shared with the treasury shift in {@link TrapShops}, which had the same
     * hole to fall down and fell down it: a clerk sent to the vault's own
     * block is a resident put underground and left there.
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

        // What they came for. A mix, because somebody who only ever gambles is
        // a punter with a suitcase -- the point of a visitor is that they use
        // the CITY, and a floor, a counter and a ward are three different
        // people's businesses.
        //
        // Somebody who turns up unwell sees a doctor FIRST, which is both what
        // a person would do and what keeps the ward from being the errand
        // everybody runs out of money before reaching.
        ArrayDeque<Errand> itinerary = new ArrayDeque<>();
        int errands = MIN_ERRANDS + random.nextInt(MAX_ERRANDS - MIN_ERRANDS + 1);
        boolean ill = random.nextFloat() < ILL_SHARE;
        if (ill) {
            itinerary.add(Errand.WARD);
        }
        // The four a visitor can turn up for, and the clock decides whether
        // the fourth is on the list at all.
        //
        // A club is shut in daylight -- see TrapClubs.hour -- and an errand
        // that cannot succeed still costs its owner the full START_TRIES
        // before it is written off, which is the better part of a minute of
        // somebody standing in the square doing nothing. The retry loop exists
        // for a floor that happens to be full, not for a venue that is shut
        // for the next ten minutes, so a daytime arrival is simply never sold
        // a night out.
        Errand[] open = world.getTimeOfDay() % 24000L >= 12000L
                ? new Errand[] {Errand.CASINO, Errand.SHOP, Errand.STALL, Errand.CLUB}
                : new Errand[] {Errand.CASINO, Errand.SHOP, Errand.STALL};
        while (itinerary.size() < errands) {
            itinerary.add(open[random.nextInt(open.length)]);
        }

        VillagerEntity body = make(world, at);
        if (body == null) {
            return;
        }
        // Low-biased, like a punter's stake: most visitors are somebody on a
        // day out and a whale is worth noticing.
        int purse = PURSE_LOW + Math.min(random.nextInt(PURSE_HIGH - PURSE_LOW + 1),
                random.nextInt(PURSE_HIGH - PURSE_LOW + 1));
        // Registered BEFORE the body goes into the world, and the order is not
        // cosmetic. spawnEntity fires ENTITY_LOAD synchronously, the handler
        // below discards any tourist no live Visit knows about, and a body
        // spawned first is therefore a body binned by its own author about a
        // microsecond later. Caught live: every visitor logged an arrival and
        // /visitors reported nobody in town, over and over.
        VISITS.add(new Visit(body.getUuid(), itinerary, purse, ill,
                world.getTime() + TRIP_TICKS));
        world.spawnEntity(body);
        TrapCraft.LOGGER.info("somebody's in town with {}e, here for {}", purse, said(itinerary));
    }

    /**
     * Make one, dressed as somebody from somewhere else.
     *
     * Deliberately does NOT put them in the world -- see the ordering note in
     * the caller. The body exists and has its uuid the moment it is created,
     * which is all the caller needs to register the visit first.
     */
    private static VillagerEntity make(ServerWorld world, BlockPos at) {
        VillagerEntity body = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (body == null) {
            return null;
        }
        body.refreshPositionAndAngles(at, world.getRandom().nextFloat() * 360.0F, 0.0F);
        var types = world.getRegistryManager().getOrThrow(RegistryKeys.VILLAGER_TYPE);
        body.setVillagerData(body.getVillagerData().withType(
                types.getOrThrow(FROM_AWAY.get(world.getRandom().nextInt(FROM_AWAY.size())))));
        body.addCommandTag(TOURIST_TAG);
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
            if (visit.ward != null) {
                // The one errand they walk themselves. Nobody else is going to
                // call errandDone for a ward, so this does it.
                if (!toWard(server, world, body, visit)) {
                    visit.ward = null;
                    visit.wardId = null;
                    errandDone(visit.body);
                }
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
            if (--visit.cooldown > 0) {
                continue;
            }
            if (start(server, world, body, visit, visit.itinerary.peek())) {
                visit.tries = 0;
                continue;
            }
            // Nowhere to do this one right now. WAIT, and try again shortly.
            //
            // The first version dropped the errand on the spot, which meant a
            // whole itinerary drained in three consecutive ticks and somebody
            // who arrived while the floor happened to be full turned round and
            // went home inside a fifth of a second. Caught live: a visitor
            // arrived with 281e for a shop and two machines and was gone
            // before the next log line. A busy floor is the NORMAL state of a
            // floor worth visiting, so giving up instantly is giving up
            // always.
            visit.cooldown = START_RETRY;
            if (++visit.tries >= START_TRIES) {
                visit.itinerary.poll();
                visit.tries = 0;
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
        if (errand == Errand.SHOP) {
            if (!TrapShops.sendVisitor(server, body)) {
                return false;
            }
            visit.busy = true;
            return true;
        }
        if (errand == Errand.STALL) {
            // Walked by the shops, paid at the stall. TrapShops owns every
            // lesson about crossing a town and TrapStalls owns the till; the
            // seam is deliberate and documented at both ends.
            if (!TrapShops.sendVisitorToStall(server, body)) {
                return false;
            }
            visit.busy = true;
            return true;
        }
        if (errand == Errand.CLUB) {
            if (!TrapClubs.sendVisitor(server, body)) {
                return false;
            }
            visit.busy = true;
            return true;
        }
        if (errand == Errand.WARD) {
            TrapHospitals.Ward ward = TrapHospitals.walkIn(server);
            if (ward == null || visit.purse < TrapHospitals.bill()) {
                return false;
            }
            // The one errand nobody else walks them to. A machine and a till
            // both had a customer-fetching seam already; a ward has never had
            // a walk-in before, so the legwork is here -- and kept to the same
            // shape the others use, a re-asserted walk target and a deadline
            // after which they are simply put at the door.
            visit.ward = ward.sign();
            visit.wardId = ward.id();
            visit.wardBy = world.getTime() + WALK_GRACE
                    + (long) Math.sqrt(body.squaredDistanceTo(
                            Vec3d.ofCenter(ward.sign()))) * TICKS_PER_BLOCK;
            visit.busy = true;
            body.wakeUp();
            TrapHomes.walkTo(body, ward.sign());
            return true;
        }
        return false;
    }

    /**
     * One step of somebody making their own way to a ward.
     *
     * @return true when they are still on their way
     */
    private static boolean toWard(MinecraftServer server, ServerWorld world,
                                  VillagerEntity body, Visit visit) {
        double left = body.squaredDistanceTo(Vec3d.ofCenter(visit.ward));
        if (left > AT_THE_DOOR * AT_THE_DOOR) {
            if (world.getTime() < visit.wardBy) {
                // Re-asserted rather than set once: a villager Brain replaces
                // a walk target with its own the moment it has none, so a
                // target set at the door of the trip is gone within seconds.
                if (++visit.nudge % NUDGE_TICKS == 0) {
                    body.wakeUp();
                    TrapHomes.walkTo(body, visit.ward);
                }
                return true;
            }
            // Out of patience. Put at the door rather than left in the road --
            // the same trade the floor and the shops both make, because a
            // town-length walk is not a thing the engine will do.
            BlockPos door = TrapSpawn.near(world, visit.ward.up());
            if (door == null) {
                return false;
            }
            body.refreshPositionAndAngles(door, world.getRandom().nextFloat() * 360.0F, 0.0F);
        }
        TrapHospitals.Ward ward = TrapHospitals.byId(visit.wardId);
        if (ward == null) {
            return false;
        }
        // Seen to, and paid for out of their own pocket. A resident's bill is
        // met by the city -- that is what the health service IS -- and
        // somebody here for the weekend is not on it. The doctors are paid the
        // same either way, and the city takes its cut of the visit.
        int fee = TrapHospitals.bill();
        int duty = TrapCity.dutyOn(fee, TrapCity.Duty.INCOME);
        if (!spend(visit.body, fee + duty)) {
            return false;
        }
        TrapPayroll.credit(fee);
        TrapCity.receive(duty, TrapCity.Duty.INCOME);
        TrapHospitals.seen(world, ward);
        TrapCraft.LOGGER.info("somebody from out of town paid {}e at {}", fee + duty,
                ward.name() == null ? "oddziału" : ward.name());
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

    /**
     * /visitors -- who is in town, and why nobody is.
     *
     * The same reason {@link TrapFloor#registerCommands} exists: a feature
     * that is running perfectly and quietly declining every errand is, from
     * inside the game, identical to a feature that was never written. Every
     * test that can turn somebody away says so out loud here.
     */
    public static void registerCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("visitors")
                                .executes(context -> report(context.getSource()))));
    }

    private static int report(net.minecraft.server.command.ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        net.minecraft.text.MutableText out = net.minecraft.text.Text.literal("Turyści  ")
                .formatted(net.minecraft.util.Formatting.GOLD,
                        net.minecraft.util.Formatting.BOLD)
                .append(net.minecraft.text.Text.literal("w mieście: " + VISITS.size() + ", limit "
                                + room())
                        .formatted(net.minecraft.util.Formatting.GRAY));
        if (!TrapCity.founded()) {
            out.append(net.minecraft.text.Text.literal(
                            "\n  Brak miasta. Nikt nie odwiedza miejsca bez skarbca.")
                    .formatted(net.minecraft.util.Formatting.RED));
        } else {
            ServerWorld world = null;
            for (ServerWorld candidate : server.getWorlds()) {
                if (candidate.getRegistryKey().getValue().toString()
                        .equals(TrapCity.vaultWorld())) {
                    world = candidate;
                    break;
                }
            }
            BlockPos vault = TrapCity.vaultAt();
            BlockPos step = world == null ? null : doorstep(world, vault);
            out.append(net.minecraft.text.Text.literal("\n  Wchodzą nad skarbcem na "
                            + vault.getX() + ", " + vault.getZ()
                            + (step == null
                            ? " -- ale nie mają tam teraz gdzie stanąć"
                            : ", na poziomie gruntu y" + step.getY()))
                    .formatted(step == null ? net.minecraft.util.Formatting.RED
                            : net.minecraft.util.Formatting.DARK_GRAY));
        }
        int purse = 0;
        for (Visit visit : VISITS) {
            purse += visit.purse;
        }
        out.append(net.minecraft.text.Text.literal("\n  mają łącznie " + purse
                        + "e kasy spoza miasta"
                        + (VISITS.isEmpty() ? "" : ", przyjechali po:"))
                .formatted(net.minecraft.util.Formatting.DARK_GRAY));
        for (Visit visit : VISITS) {
            out.append(net.minecraft.text.Text.literal("\n    " + visit.purse + "e  "
                            + (visit.busy ? "zajęty" : "idzie") + "  " + said(visit.itinerary))
                    .formatted(net.minecraft.util.Formatting.DARK_GRAY));
        }
        out.append(net.minecraft.text.Text.literal(
                        "\n  Jeden przyjeżdża co " + ARRIVE_TICKS / 20 + "s, dopóki jest miejsce."
                                + " Inwestycje przyciągają więcej: latarnie, tramwaje, drogi, szkoła.")
                .formatted(net.minecraft.util.Formatting.DARK_GRAY));
        // What all this custom is worth to somebody with a drum in the cellar.
        //
        // Not a second system: visitor money already lands in shop.turnover
        // and house.handle, which is exactly what TrapLaw.washLimit reads. A
        // room full of people from out of town IS the explanation -- that is
        // what a front is, and it has been true since the first one of them
        // put an emerald in a slot. It was simply invisible, and an
        // explanation nobody can see is one nobody can spend.
        ServerPlayerEntity asking = source.getPlayer();
        if (asking != null) {
            int headroom = TrapLaw.washLimit(asking);
            out.append(net.minecraft.text.Text.literal("\n  jeszcze " + Math.max(0, headroom)
                            + "e twojego utargu ma pokrycie"
                            + (headroom > 0 ? "." : " -- pralnia wyprzedziła sklepy."))
                    .formatted(headroom > 0 ? net.minecraft.util.Formatting.DARK_GRAY
                            : net.minecraft.util.Formatting.RED));
        }
        net.minecraft.text.Text shown = out;
        source.sendFeedback(() -> shown, false);
        return 1;
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
