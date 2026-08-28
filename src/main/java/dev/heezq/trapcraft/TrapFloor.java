package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
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
            // ...but only a punter nobody is running times out. The minute was
            // written when a punter appeared at the door and played four quick
            // rounds; a resident who walks in from three streets away and then
            // settles in is past it before they have finished, and the seat
            // was being handed to somebody else underneath them. Eight of the
            // eighteen people playing held no seat at all the first night this
            // shipped, which is the "one machine, one player" rule quietly off.
            // The session list is the truthful answer; the clock stays as the
            // backstop for a seat whose session died with the server.
            if (!known(seat.who()) && world.getTime() - seat.since() > STALE_TICKS) {
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

    /**
     * Marks somebody who is out at a machine, or on their way to one.
     *
     * Read by {@link TrapHomes}, which must not count them as missing from
     * their house while they are gone and must not walk them back home
     * mid-session. One person, one place: that is the whole rule, and this tag
     * is where the two halves of it agree.
     */
    public static final String PUNTER_TAG = "trapcraft_punter";
    /** Ticks between one attempt to send somebody in. */
    private static final int ARRIVAL_TICKS = 60;
    /**
     * How often an attempt produces a punter, before the clock.
     *
     * Multiplied by {@link TrapMath#casinoHourFactor}, so this is the dusk and
     * dawn figure: about one in three at noon and effectively certain at
     * midnight.
     */
    private static final float ARRIVAL_CHANCE = 0.55f;
    /** Ticks between one punter's rounds. Slow enough to watch. */
    private static final int ROUND_TICKS = 70;
    /**
     * Ticks between one shove along the way.
     *
     * Much shorter than a round. A walk target is a thing the Brain is free to
     * replace with its own -- somebody's bed, the meeting bell, a shove from
     * the villager next to them -- and on a three-and-a-half second cadence
     * that is most of the journey spent walking somewhere else. Re-asserted
     * every second, the wandering is a wobble on the way rather than the
     * destination.
     */
    private static final int NUDGE_TICKS = 20;
    /**
     * Ticks of patience per block of the journey, and a lump to start with.
     *
     * A villager makes about a block a second when the road is clear, so this
     * is roughly twice as long as it should take -- which is about right for a
     * floor with a crowd on it, since most of the delay is other people. A
     * deadline rather than a count of attempts: the two are the same thing
     * only while the attempts are evenly spaced, and they stopped being that
     * the moment walking got its own faster clock.
     */
    private static final int TICKS_PER_BLOCK = 20;
    /** Patience that does not depend on the distance at all. */
    private static final int WALK_GRACE = 200;
    /** How far out they come in from, so they walk the last few blocks. */
    private static final int APPROACH = 6;
    /**
     * How far ahead they are aimed at a time.
     *
     * A villager's pathfinder will not plan further than its follow range, so
     * a walk target three hundred blocks off returns no path at all and they
     * stand in the road. Aimed at a point along the way instead, re-aimed
     * every round -- which also happens to look like somebody walking rather
     * than somebody on rails.
     */
    private static final int HOP = 16;
    /** Ticks between one round of the books. Half a minute, like the market. */
    private static final int BEAT_TICKS = 600;

    /** One punter mid-session. */
    private static final class Punter {
        final UUID id;
        final String at;
        final UUID house;
        final int stake;
        /** Where they stand to play it. Beside the machine, never on it. */
        final BlockPos stand;
        /** What they are called at home, so they can be given it back. */
        final String name;
        /** An advantage player, if nobody was watching the door. */
        boolean cheat;
        /**
         * Somebody from out of town.
         *
         * Decides two things and nothing else: whose pocket the stake comes
         * out of, and where they go when they have had enough. A visitor down
         * the resident departure path is a statue at a cabinet.
         */
        boolean visitor;
        /** What they were handed on the way in: 2 product, 1 food, 0 nothing. */
        final int served;
        int roundsLeft;
        int wait;
        int won;
        int lost;
        /**
         * World time by which they have to have got there.
         *
         * Separate from roundsLeft, because a punter who spent their whole
         * visit walking round a fence has not played anything and the machine
         * they never reached has been locked the entire time.
         */
        long deadline;
        /** Already brought to the door once. Only ever happens the once. */
        boolean shownIn;

        Punter(UUID id, String at, UUID house, int stake, int rounds, int served,
               BlockPos stand, String name, long deadline) {
            this.id = id;
            this.at = at;
            this.house = house;
            this.stake = stake;
            this.served = served;
            this.roundsLeft = rounds;
            this.wait = ROUND_TICKS;
            this.stand = stand;
            this.name = name;
            this.deadline = deadline;
        }

        /** Still on the way in. */
        boolean walkingIn;
    }

    private static final List<Punter> PUNTERS = new ArrayList<>();

    /**
     * Ticks in before somebody fancies another go, doubled at random.
     *
     * Three thousand is an eighth of a day, so a resident is out several
     * times a day -- and because the hour decides when they set off, those
     * goes still land in the evening. The jitter is what stops the whole town
     * leaving the house together.
     *
     * Was six thousand, and THIS was the real cap on how busy a floor ever
     * got: measured across a live day, the midnight allowance was 43 people
     * and the room held ten, because everybody who came at dusk was home
     * sleeping it off for the rest of the night. The town was never the
     * bottleneck the arithmetic in room() says it is -- this was.
     */
    public static final int NIGHT_OFF = 3000;
    /**
     * The share of the town that is out at the very peak of the night.
     *
     * The population is the hard ceiling -- twenty-four residents can never be
     * twenty-five punters -- but the ceiling is not the number. A town where
     * everybody is at a slot machine at all times has no more people in it
     * than one where nobody is; the point of tying the two together was that
     * the floor should fill and empty with the town's own evening.
     *
     * A half, not the quarter this shipped as. The quarter was sized against
     * the shape of the evening and never against the bill at the end of it,
     * and the two are the same knob: a floor's income is punters through the
     * door and its cost is cabinets on the floor, so capping the first at a
     * quarter of a town of twenty-eight put the ceiling UNDER the upkeep of
     * anything bigger than about thirty machines. Measured on the live floor
     * at fifty-one: a quarter bleeds ~1400e an hour and cannot break even at
     * any hour of any day, because {@link TrapMath#punterStakeCeiling} shrinks
     * the bets as the crowd grows, so the handle is near flat however many
     * cabinets are standing there. A half clears it by about the same margin.
     *
     * Still a share and still multiplied by the hour, so the daytime floor is
     * two people and a lot of empty stools and genuinely runs at a loss until
     * the evening -- which is the thing this number is for. It is the peak
     * that moved, not the shape.
     */
    private static final float NIGHT_SHARE = 0.5f;

    /**
     * One beat of every floor paying its bills and being judged on how it is
     * kept.
     *
     * Counted here rather than in TrapHouse because only the floor knows what
     * is standing empty and what kinds of machine are wired -- and both of
     * those are the things the owner is actually being asked to look after.
     */
    private static void beat(MinecraftServer server) {
        List<String> gone = new ArrayList<>();
        for (TrapHouse.House house : TrapHouse.all()) {
            java.util.Set<net.minecraft.block.Block> games = new java.util.HashSet<>();
            int machines = 0;
            int free = 0;
            for (Map.Entry<String, UUID> wire : TrapHouse.wires().entrySet()) {
                if (!wire.getValue().equals(house.id)) {
                    continue;
                }
                ServerWorld world = worldOf(server, wire.getKey());
                BlockPos pos = TrapHouse.posOf(wire.getKey());
                if (world == null || pos == null) {
                    continue;
                }
                // Nothing there any more. See TrapHouse.forget: the wire
                // outlives its machine whenever something other than a player
                // takes it away, and a ghost costs upkeep every beat forever.
                if (!TrapHouse.isFitting(world.getBlockState(pos).getBlock())) {
                    gone.add(wire.getKey());
                    continue;
                }
                // The bar is wired like a machine but is not one: it takes no
                // bets, holds no seat, and must not be counted as either
                // variety or capacity or a floor of ten bars would look
                // magnificent on paper.
                if (!TrapHouse.isMachine(world.getBlockState(pos).getBlock())) {
                    // ...and it must not be worn out either. maybeArrive spent
                    // months seating punters at bars, so the live floor has
                    // counters carrying 86-100 wear that no hammer can touch --
                    // the mend path is gated on isMachine as well. Cleared from
                    // the beat rather than by hand, so a world already carrying
                    // the damage heals itself on the next half-minute.
                    TrapHouse.unwear(wire.getKey());
                    continue;
                }
                machines++;
                games.add(world.getBlockState(pos).getBlock());
                // Out of order is not somewhere to play. A dead cabinet used
                // to score the floor for room it did not have, which paid it
                // rep for the exact thing it was supposed to be losing rep for.
                if (occupant(world, pos) == null && !TrapHouse.broken(world, pos)) {
                    free++;
                }
            }
            TrapHouse.beat(house, games.size(), machines, free);
            if (TrapHouse.owing(house) >= 3) {
                collectors(server, house);
            }
        }
        if (!gone.isEmpty()) {
            TrapHouse.forget(gone);
        }
    }

    /**
     * Three beats behind on the cut, and somebody comes round for it.
     *
     * Aimed at whoever is CARRYING THE CARD, not at whoever happens to be
     * standing on the floor. This used to pick the nearest body to a machine,
     * which was fine while the person on their own floor was the person who
     * owned it and wrong the moment a deed changed hands: a customer playing
     * somebody else's slots got jumped for somebody else's tab, with the
     * house's name in the message reading like an accusation.
     *
     * Anywhere in the world, because the card is the deed and a debt does not
     * stop being yours when you leave the building. If the holder is offline
     * the tab simply keeps running -- and their machines stay dark, which is
     * punishment enough until they come back.
     */
    private static void collectors(MinecraftServer server, TrapHouse.House house) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!holdsDeed(player, house)) {
                continue;
            }
            TrapHouse.settled(house);
            player.sendMessage(Text.literal("Ktoś przyszedł po pieniądze, które "
                            + house.name + " jest winne.")
                    .formatted(Formatting.RED, Formatting.BOLD), false);
            TrapStickup.jump(player, TrapMath.stickupSquad(
                    TrapContracts.repOf(TrapContracts.findPhone(player)),
                    TrapHeat.carryingHeat(player), 3, 16));
            return;
        }
    }

    /** The card is the deed, so whoever is carrying it is who they want. */
    private static boolean holdsDeed(ServerPlayerEntity player, TrapHouse.House house) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (TrapHouse.of(inventory.getStack(slot)) == house) {
                return true;
            }
        }
        return false;
    }

    /**
     * The draw of the best-regarded floor with a machine free.
     *
     * One number for the server rather than one per house, because arrivals
     * are one attempt for everybody -- and taking the best means a good room
     * next door does not have its trade throttled by a bad one.
     */
    /** The draw of whoever owns the machine at this wire. */
    private static float pullOf(String wire) {
        TrapHouse.House house = TrapHouse.byId(TrapHouse.wires().get(wire));
        return (house == null
                ? TrapMath.floorPull(0, 0, TrapHomes.population()) : house.pull())
                * townSpending();
    }

    /**
     * What the town can afford to lose tonight.
     *
     * The floor pulls on population, which is how many people COULD come. This
     * is whether they have anything to come with -- a town that spent its
     * wages on rent and dinner stays home, and the same wage rise that fills
     * the shops fills the floor a day later.
     */
    private static float townSpending() {
        return TrapMath.townDemand(TrapPayroll.purse(), TrapHomes.population());
    }

    /** Has this floor got machines that are loaded but every one of them busy? */
    private static boolean busyHouse(MinecraftServer server, TrapHouse.House house) {
        boolean any = false;
        for (Map.Entry<String, UUID> wire : TrapHouse.wires().entrySet()) {
            if (!wire.getValue().equals(house.id)) {
                continue;
            }
            ServerWorld world = worldOf(server, wire.getKey());
            BlockPos pos = TrapHouse.posOf(wire.getKey());
            if (world == null || pos == null
                    || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            any = true;
            if (occupant(world, pos) == null) {
                return false;   // there was room here after all
            }
        }
        return any;
    }

    private static float bestPull() {
        float best = TrapMath.floorPull(0, 0, TrapHomes.population());
        for (TrapHouse.House house : TrapHouse.all()) {
            best = Math.max(best, house.pull());
        }
        return best * townSpending();
    }

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
            net.minecraft.item.ItemStack held = player.getStackInHand(hand);

            if (held.isOf(TrapContent.hammer)) {
                mend(server, seat, gambler);
                return ActionResult.SUCCESS;
            }
            if (TrapHouse.broken(server, seat)) {
                gambler.sendMessage(Text.literal("Zepsute. Potrzebny młot.")
                        .formatted(Formatting.RED), true);
                world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        SoundCategory.BLOCKS, 0.6F, 0.5F);
                return ActionResult.SUCCESS;
            }
            UUID on = occupant(server, seat);
            if (on != null && !on.equals(gambler.getUuid())) {
                gambler.sendMessage(Text.literal(who(server, on) + " gra na tym automacie.")
                        .formatted(Formatting.GRAY), true);
                world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        SoundCategory.BLOCKS, 0.6F, 0.6F);
                return ActionResult.SUCCESS;
            }
            claim(server, seat, gambler.getUuid(), true);
            return ActionResult.PASS;
        });

        // A punter outlives a restart -- they are a persistent villager and
        // the session that owned them is gone. They are sent home rather than
        // binned: a punter is a resident now, and discarding one would have
        // every restart quietly kill whoever happened to be at a machine.
        // Their evening is over either way, so the tag, the frozen AI and the
        // stake over their head all come off.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(
                (entity, world) -> {
                    if (entity instanceof VillagerEntity villager
                            && villager.getCommandTags().contains(PUNTER_TAG)
                            && !known(villager.getUuid())) {
                        if (villager.getCommandTags().contains(TrapHomes.TENANT_TAG)) {
                            villager.removeCommandTag(PUNTER_TAG);
                            villager.setAiDisabled(false);
                            villager.setCustomName(Text.literal(plainName(villager))
                                    .formatted(Formatting.AQUA));
                        } else {
                            villager.discard();   // a stranger from an older version
                        }
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
            // The books. Same half-minute beat the market runs on.
            if (server.getTicks() % BEAT_TICKS == 0) {
                beat(server);
            }
        });
    }

    /**
     * Put a machine right with a hammer.
     *
     * Anybody can do it, and the house pays -- charging whoever is holding the
     * hammer would make the sensible move never to pick one up, and a floor
     * where fixing things is somebody else's problem is a floor that stays
     * broken.
     */
    private static void mend(ServerWorld world, BlockPos pos, ServerPlayerEntity mender) {
        int worn = TrapHouse.wearAt(world, pos);
        if (worn <= 0) {
            mender.sendMessage(Text.literal("Nic mu nie jest.")
                    .formatted(Formatting.GRAY), true);
            return;
        }
        int cost = TrapHouse.repair(world, pos);
        if (cost < 0) {
            mender.sendMessage(Text.literal("Skarbiec nie ma na części. Zostaje zepsute.")
                    .formatted(Formatting.RED), false);
            world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.BLOCKS, 0.7F, 0.5F);
            return;
        }
        mender.sendMessage(Text.literal("Naprawione.").formatted(Formatting.GREEN)
                .append(Text.literal("  zużycie " + worn + ", części za " + cost + "e.")
                        .formatted(Formatting.DARK_GRAY)), false);
        world.playSound(null, pos, SoundEvents.BLOCK_ANVIL_USE,
                SoundCategory.BLOCKS, 0.7F, 1.3F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.02);
    }

    private static String who(ServerWorld world, UUID id) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
        if (player != null) {
            return player.getGameProfile().getName();
        }
        return world.getEntity(id) instanceof VillagerEntity punter
                && punter.getCustomName() != null
                ? punter.getCustomName().getString() : "Ktoś";
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

    /**
     * Most people who can be on a floor at once: the town, and nothing else.
     *
     * Twenty-four residents in the neighbourhood is twenty-four on the floor
     * at the absolute outside, and since every one of them is a body that
     * walked over, the ones playing are provably not also sat at home. This
     * is the cap in writing; the arithmetic enforces it on its own, because
     * there is no such thing as a punter who is not somebody's tenant.
     *
     * The real ceiling is almost always lower -- a punter needs a machine
     * free, so a floor of four cabinets holds four however big the town gets,
     * which is still the legible answer to "how do I get busier".
     */
    private static int room(MinecraftServer server) {
        int town = TrapHomes.population();
        float busy = TrapMath.casinoHourFactor(
                server.getOverworld().getTimeOfDay() % 24000L);
        return Math.min(town, Math.round(town * NIGHT_SHARE * busy));
    }

    private static void maybeArrive(MinecraftServer server, boolean forced) {
        float busy = TrapMath.casinoHourFactor(
                server.getOverworld().getTimeOfDay() % 24000L);
        if (PUNTERS.size() >= room(server)
                || (!forced && server.getOverworld().getRandom().nextFloat()
                > ARRIVAL_CHANCE * busy * bestPull())) {
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
            // isMachine, because the bar is wired too. Without it a punter was
            // sent to stand at the counter and bet against a shelf: it took
            // their stake at returnOf's 0.97 fallback, held a seat, wore out
            // like a cabinet and eventually read as a broken machine nobody
            // could fix. freeWire and beat both ask this; this was the one
            // path that did not.
            if (world == null || pos == null
                    || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !TrapHouse.isMachine(world.getBlockState(pos).getBlock())) {
                return;
            }
            if (occupant(world, pos) == null && !TrapHouse.broken(world, pos)) {
                open.add(at);
            }
        });
        if (open.isEmpty()) {
            // Somebody came and there was nowhere to play. This is the one the
            // owner is meant to feel: it is what a floor that has outgrown
            // itself looks like from the pavement, and every one of these
            // takes a bite out of the name that brought them.
            for (TrapHouse.House house : TrapHouse.all()) {
                if (busyHouse(server, house)) {
                    TrapHouse.turnedAway(house);
                }
            }
            return;
        }
        // Weighted by each floor's own draw, squared -- so two casinos on one
        // server genuinely compete for the same customers, and the better-run
        // room takes most of them rather than both riding on whichever name is
        // best. Which is the whole point of a friend opening one next door.
        float total = 0;
        for (String candidate : open) {
            float pull = pullOf(candidate);
            total += pull * pull;
        }
        float roll = server.getOverworld().getRandom().nextFloat() * total;
        String at = open.get(open.size() - 1);
        for (String candidate : open) {
            float pull = pullOf(candidate);
            roll -= pull * pull;
            if (roll <= 0) {
                at = candidate;
                break;
            }
        }
        arrive(server, at);
    }

    private static void arrive(MinecraftServer server, String at) {
        ServerWorld world = worldOf(server, at);
        BlockPos pos = TrapHouse.posOf(at);
        if (world == null || pos == null) {
            return;
        }
        // Where they will end up standing, worked out BEFORE anybody is sent
        // for: a machine with nowhere to stand beside it gets no customers at
        // all, which is better than a customer standing on top of it like a
        // hat.
        if (standAt(world, pos) == null) {
            return;
        }
        // Somebody who lives here. That is what an ARRIVAL is -- the strangers
        // who appeared out of nowhere were always a stand-in for a town that
        // did not exist yet, and a stand-in is exactly what makes the floor a
        // lie: the room filled with people the register had never heard of
        // while the tenants two streets away stayed in. A punter is a resident
        // who walked over, one body, in one place, and if nobody in the town
        // is free then nobody comes tonight.
        //
        // Somebody from out of town is a different question, asked by
        // TrapVisitors, which walks its own body in here through seat(). It is
        // the only other caller and it never claims its people live here.
        VillagerEntity punter = resident(world, pos);
        if (punter == null) {
            return;
        }
        seat(server, at, punter, false);
    }

    /**
     * Put a body -- whoever's it is -- on a machine.
     *
     * Split out of {@link #arrive} so that a visitor can be seated on the same
     * terms as a neighbour: the same stake sizing, the same jam at the door,
     * the same drink, the same seat, the same walk. The ONLY thing that turns
     * on where they are from is whose pocket the stake comes out of and where
     * they go afterwards, and both of those are one branch each.
     *
     * @param visitor somebody passing through, whose money is from outside the
     *                town's purse and who has no home to be sent back to
     * @return whether they were actually seated
     */
    public static boolean seat(MinecraftServer server, String at,
                               VillagerEntity punter, boolean visitor) {
        ServerWorld world = worldOf(server, at);
        BlockPos pos = TrapHouse.posOf(at);
        UUID houseId = TrapHouse.wires().get(at);
        TrapHouse.House house = TrapHouse.byId(houseId);
        if (world == null || pos == null || house == null) {
            return false;
        }
        var random = world.getRandom();

        BlockPos stand = standAt(world, pos);
        if (stand == null) {
            return false;
        }

        // Capped by how full the room already is: a busy floor is a cheap
        // floor. See TrapMath.punterStakeCeiling.
        //
        // How full is measured in people AT A MACHINE, not people on the
        // books. This was PUNTERS.size(), which counts everybody who has been
        // sent for -- and on the live floor that was 39 with 21 of them still
        // walking, so the room was priced as heaving while eighteen people
        // played. A punter who has not reached a cabinet has not made the room
        // busy; they have made it crowded on paper.
        int stake = TrapMath.punterStake(new java.util.Random(random.nextLong()),
                seated());
        // Never a stake the vault could not settle: a punter who breaks the
        // bank is a punter who took the owner's money away while they were
        // stood somewhere else entirely.
        // ...and never a stake the payer could not settle either. A punter is
        // somebody who was paid on Friday, not a fountain with a felt top --
        // and a visitor is somebody with a holiday budget, which runs out the
        // same way.
        while (stake > TrapMath.PUNTER_MIN_STAKE
                && (!TrapHouse.covers(house, stake, TrapHouse.TOP_SLOT)
                        || !affords(punter, visitor, stake))) {
            stake /= 2;
        }
        if (!TrapHouse.covers(house, stake, TrapHouse.TOP_SLOT)
                || !affords(punter, visitor, stake)) {
            // Turned away at the smallest bet there is, by the house or by
            // their own pocket. Word gets round either way -- and they stay
            // where they are, because they never left home for this.
            TrapHouse.turnedAway(house);
            return false;
        }
        // A shabby cabinet loses you the punter at the door. Wear used to cost
        // nothing but a rep point, which made the hammer a chore with no
        // consequence -- a floor of half-dead machines earned exactly what a
        // floor of new ones did.
        if (random.nextFloat() < TrapMath.jamChance(TrapHouse.wearAt(world, pos))) {
            TrapHouse.turnedAway(house);
            world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.BLOCKS, 0.5F, 0.5F);
            world.spawnParticles(ParticleTypes.SMOKE, pos.getX() + 0.5,
                    pos.getY() + 1.2, pos.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.01);
            return false;
        }
        // Served at the door, out of your own stash. This is the whole
        // difference between a floor and a faucet: a dry bar means one go and
        // out, and that is most of the trade gone.
        int served = TrapHouse.serve(house, new java.util.Random(random.nextLong()));
        // Their own name, with tonight's stake after it. It used to be a name
        // picked at random out of the register, which meant Alma could walk in
        // and stand there called Bertie -- the exact kind of detail that makes
        // a town read as scenery.
        String who = plainName(punter);
        punter.addCommandTag(PUNTER_TAG);
        punter.setCustomName(named(who, stake, served == 2 ? Formatting.LIGHT_PURPLE
                : served == 1 ? Formatting.WHITE : Formatting.DARK_GRAY));
        punter.setCustomNameVisible(true);
        // Out of bed. The floor is busiest at midnight, which is precisely
        // when a villager Brain would otherwise have them asleep.
        //
        // Their BED is deliberately left alone. It was taken off them for a
        // release on the theory that the walk-home task was what kept punters
        // from reaching a machine -- measured, and it made no difference to
        // the arrival rate at all. What it did do was far worse: a resident
        // with no bed and no meeting point has nothing to go back to, so once
        // their evening ended they simply strolled around the casino they were
        // standing in, forever, and the town read as a place where nobody goes
        // home. A bed is the one thing that makes a villager keep a routine.
        punter.wakeUp();

        // Patience for the walk, worked out from how far they actually live.
        int away = (int) Math.sqrt(punter.squaredDistanceTo(
                stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5));
        Punter session = new Punter(punter.getUuid(), at, houseId, stake,
                TrapMath.punterRoundsServed(house.addiction, served,
                        new java.util.Random(random.nextLong())), served, stand, who,
                world.getTime() + WALK_GRACE + (long) away * TICKS_PER_BLOCK);
        session.walkingIn = true;
        session.visitor = visitor;
        PUNTERS.add(session);

        // Somebody watching the floor spots them on the way in. Without one,
        // about one punter in sixteen is playing at better than even money and
        // the vault just quietly leaks.
        if (!house.pitBoss && random.nextFloat() < TrapMath.CHEAT_CHANCE) {
            session.cheat = true;
            punter.setCustomName(named(who, stake, Formatting.WHITE));
        }
        // The seat is held from here, not from when they arrive. A machine
        // two people are walking to is a machine one of them finds taken, and
        // the walk is bounded -- see Punter.deadline -- so a reservation cannot
        // outlast somebody's patience for getting there.
        step(punter, stand);
        claim(world, pos, punter.getUuid(), false);
        TrapCraft.LOGGER.info("{} {} to a machine at {} {} {}, {}e a go, {} blocks to walk",
                who, visitor ? "in off the road" : "out", pos.getX(), pos.getY(), pos.getZ(),
                stake, away);
        return true;
    }

    /**
     * Can whoever is about to bet actually cover it?
     *
     * The town purse for a neighbour, their own holiday money for somebody
     * passing through. Two pockets, because a visitor's emeralds never came
     * out of the wage bill and putting them through {@link TrapPayroll} would
     * have the town paying for other people's nights out.
     */
    private static boolean affords(VillagerEntity punter, boolean visitor, int stake) {
        return visitor ? TrapVisitors.purseOf(punter.getUuid())
                >= stake + TrapCity.dutyOn(stake, TrapCity.Duty.GAMING)
                : TrapPayroll.afford(stake);
    }

    /**
     * Lay one round's stake, out of whichever pocket this one belongs to.
     *
     * A neighbour's comes out of the town's wage bill and goes back into it
     * when they win. A visitor's is money from outside the town entirely --
     * which is the whole point of them, because a floor whose only customers
     * are its own residents can only ever be as busy as the town is populous,
     * and the upkeep on the cabinets does not care how populous that is.
     *
     * A visitor also pays GAMING duty on top, which a resident on a night out
     * does not. That is the tourist tax and it is deliberate: outside money
     * lifts the floor's ceiling, and the city's cut is the drain welded to
     * that faucet. Without it this is just a tap with better manners.
     */
    private static boolean lay(Punter punter) {
        if (!punter.visitor) {
            return TrapPayroll.spend(punter.stake);
        }
        int duty = TrapCity.dutyOn(punter.stake, TrapCity.Duty.GAMING);
        if (!TrapVisitors.spend(punter.id, punter.stake + duty)) {
            return false;
        }
        TrapCity.receive(duty, TrapCity.Duty.GAMING);
        return true;
    }

    /**
     * A machine somebody from out of town could walk up to right now.
     *
     * The same tests the arrival roll makes -- wired, loaded, free, not
     * broken, behind a vault that can pay -- asked on behalf of a caller who
     * already has a body and only wants somewhere to put it.
     */
    public static String freeWire(MinecraftServer server, int purse) {
        List<String> open = new ArrayList<>();
        TrapHouse.wires().forEach((at, id) -> {
            TrapHouse.House house = TrapHouse.byId(id);
            if (house == null || house.balance <= 0) {
                return;
            }
            ServerWorld world = worldOf(server, at);
            BlockPos pos = TrapHouse.posOf(at);
            if (world == null || pos == null
                    || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !TrapHouse.isMachine(world.getBlockState(pos).getBlock())) {
                return;
            }
            if (occupant(world, pos) == null && !TrapHouse.broken(world, pos)
                    && standAt(world, pos) != null) {
                open.add(at);
            }
        });
        if (open.isEmpty() || purse < TrapMath.PUNTER_MIN_STAKE) {
            return null;
        }
        return open.get(server.getOverworld().getRandom().nextInt(open.size()));
    }

    /** Their name, and what they are playing for. */
    private static net.minecraft.text.MutableText named(String who, int stake, Formatting how) {
        return Text.literal(who).formatted(Formatting.AQUA)
                .append(Text.literal("  ·  " + stake + "e").formatted(how));
    }

    /**
     * What they are called with the stake taken back off.
     *
     * The stake is written into the name so the room can be read at a glance,
     * which means the name has to be undoable -- otherwise an evening out
     * leaves somebody called "Alma  ·  32e" on their own doorstep forever.
     */
    private static String plainName(VillagerEntity body) {
        if (body.getCustomName() == null) {
            return "Ktoś";
        }
        String shown = body.getCustomName().getString();
        int cut = shown.indexOf("  ·  ");
        return cut < 0 ? shown : shown.substring(0, cut);
    }

    /**
     * One leg of the walk over.
     *
     * Aimed at a point along the way rather than at the machine, because a
     * villager will not plan a path past its follow range and answers a target
     * three hundred blocks off by standing still. Re-aimed every round, so the
     * journey is a series of short walks -- which is both what the pathfinder
     * can do and what somebody walking across town looks like.
     */
    private static void step(VillagerEntity body, BlockPos target) {
        Vec3d toward = Vec3d.ofCenter(target).subtract(body.getPos());
        double away = toward.length();
        BlockPos next = away <= HOP ? target
                : BlockPos.ofFloored(body.getPos().add(toward.multiply(HOP / away)));
        TrapHomes.walkTo(body, next);
        body.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.LOOK_TARGET,
                new net.minecraft.entity.ai.brain.BlockPosLookTarget(target));
    }

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

            // At their spot, or still on the way. Measured against the
            // STANDING spot rather than the machine, so "close enough" cannot
            // be satisfied by standing on the lid -- and measured as a
            // DISTANCE rather than as the same block, because a path finishes
            // when it is near enough and almost never on the exact square. On
            // the old equality test somebody who had walked the whole way and
            // stopped one step short stood there running out their patience
            // before being shoved the last block.
            double left = body.squaredDistanceTo(Vec3d.ofCenter(punter.stand));
            if (left > ARRIVED * ARRIVED) {
                // Shoved along on a much shorter clock than a round, and the
                // wait is set before the deadline is tested so a punter who is
                // giving up this tick does not first sit out a full round.
                punter.wait = NUDGE_TICKS;
                if (world.getTime() < punter.deadline) {
                    body.wakeUp();
                    step(body, punter.stand);
                    continue;
                }
                // Out of patience, which for anybody who does not live round
                // the corner is the normal outcome rather than the exception.
                //
                // A villager's pathfinder plans about forty blocks and a Brain
                // reclaims the walk target between plans, so a town-length
                // walk is not a thing the engine will do however politely it
                // is asked -- three restarts went on establishing that, at
                // rates of three trips failing in four with the punters barely
                // moving. So the walk is what happens when somebody lives near
                // enough for it to work, and this is what happens otherwise:
                // they turn up at the door and walk the last of it in.
                //
                // Which is the honest reading anyway. Nobody watches a
                // neighbour cross town; they see somebody come in off the
                // street. What must never happen is a body appearing AT the
                // machine, or a second copy of somebody who was already at
                // home -- and neither can, because this is the same one body
                // the register knows about, moved.
                //
                // ponytail: no waypoint graph, no door-to-door pathing. If
                // that ever matters, the fix is to walk them between loaded
                // chunk anchors, and it is a lot of machinery for a walk
                // nobody is watching.
                if (!punter.shownIn) {
                    BlockPos from = doorway(world, pos, world.getRandom());
                    if (from != null) {
                        punter.shownIn = true;
                        punter.deadline = world.getTime() + WALK_GRACE;
                        body.refreshPositionAndAngles(from,
                                world.getRandom().nextFloat() * 360.0F, 0.0F);
                        step(body, punter.stand);
                        TrapCraft.LOGGER.info("{} came the last of the way to {} {} {} "
                                        + "on foot, {} blocks out", punter.name,
                                pos.getX(), pos.getY(), pos.getZ(), (int) Math.sqrt(left));
                        continue;
                    }
                }
                // Even the doorway did not work: boxed in, or the last few
                // blocks are through a wall. They play from the spot itself if
                // there is still room on it, and go home if there is not --
                // a machine nobody can stand at is a thing you can look at.
                if (TrapSpawn.safe(world, punter.stand)) {
                    body.refreshPositionAndAngles(punter.stand, 0.0F, 0.0F);
                } else {
                    TrapCraft.LOGGER.info("{} couldn't get to the machine at {} {} {}, "
                                    + "{} blocks short, and went home", punter.name,
                            pos.getX(), pos.getY(), pos.getZ(), (int) Math.sqrt(left));
                    leaving.add(punter);
                    leave(world, body, punter);
                    continue;
                }
            }
            punter.wait = ROUND_TICKS;
            punter.walkingIn = false;
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
        // The payer went broke between arriving and playing. They stand there
        // and do nothing rather than play a round nobody paid for.
        if (!lay(punter)) {
            return;
        }
        TrapHouse.punterStaked(house, punter.stake);
        float rtp = returnOf(world.getBlockState(pos).getBlock());
        if (house.loose()) {
            rtp = TrapMath.LOOSE_RETURN;
        } else {
            if (punter.cheat) {
                rtp = TrapMath.CHEAT_RETURN;
            }
            // Whatever was handed to them at the door is still in them --
            // including the one who came in counting. See TrapMath.servedEdge:
            // this is the whole of what makes a stocked bar a business.
            rtp -= TrapMath.servedEdge(punter.served);
        }
        int back = Math.round(punter.stake
                * TrapMath.punterRound(rtp, new java.util.Random(world.getRandom().nextLong())));
        int paid = TrapHouse.punterWon(house, back);
        // Back to the purse it came out of. Without this the town leaks its
        // entire wage bill into casino balances and quietly stops shopping,
        // which presents as "the shops are broken" a long way from the floor
        // that actually ate the money. A visitor's winnings go back to the
        // visitor, and out of the world with them when they go home.
        if (punter.visitor) {
            TrapVisitors.credit(punter.id, paid);
        } else {
            TrapPayroll.credit(paid);
        }

        // Machines wear out. A busy floor throws up something to fix every ten
        // minutes or so, and a shabby one is worth less to its own name --
        // which is what stops the hammer being a chore with no consequence.
        if (world.getRandom().nextInt(TrapMath.WEAR_PER_ROUNDS) == 0) {
            boolean died = TrapHouse.wearOne(TrapHouse.wireAt(world, pos));
            if (died) {
                world.playSound(null, pos, SoundEvents.BLOCK_ANVIL_LAND,
                        SoundCategory.BLOCKS, 0.6F, 0.6F);
                world.spawnParticles(ParticleTypes.LARGE_SMOKE,
                        pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                        25, 0.35, 0.35, 0.35, 0.02);
                tellOwner(world, house, Text.literal("Automat padł na "
                                + pos.getX() + ", " + pos.getZ()
                                + ". Potrzebny młot.")
                        .formatted(Formatting.RED));
            } else if (TrapHouse.wearAt(world, pos) == TrapMath.JAM_FROM) {
                // One word, once, at the exact point it starts costing money.
                // Wear has been climbing on every cabinet since this mod
                // shipped with nothing anywhere showing it, which is how a
                // maintenance system stays invisible for months.
                tellOwner(world, house, Text.literal("Automat się sypie na "
                                + pos.getX() + ", " + pos.getZ() + ". ")
                        .formatted(Formatting.YELLOW)
                        .append(Text.literal("Zaczyna odstraszać graczy.")
                                .formatted(Formatting.GRAY)));
            }
        }

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

    /**
     * A repair bill, read out to whoever is holding the card.
     *
     * The wear notices used to go to every player within 48 blocks, which is
     * somebody else's maintenance read out to the neighbours: log in near a
     * floor you have never set foot in and you are told its cabinets are
     * getting shabby, at coordinates you have no reason to walk to. Ownership
     * here is the card and nothing else -- see {@link TrapHouse#owns} -- so it
     * is the card that gets told, wherever its holder happens to be standing.
     * A machine that wants a hammer wants one whether the owner is on the
     * floor or three thousand blocks away.
     *
     * An owner who is offline simply misses it. The wear is on the block and
     * in the house screen either way, so nothing is lost but the nudge.
     */
    private static void tellOwner(ServerWorld world, TrapHouse.House house, Text line) {
        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
            if (TrapHouse.owns(player, house)) {
                player.sendMessage(line, false);
            }
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
    /**
     * A tenant near enough to fancy a night out, or null.
     *
     * Theirs is the body the register knows about, so it must be given back
     * rather than binned when the evening ends -- see {@link #leave}.
     */
    /**
     * How far a casino will pull somebody who lives locally.
     *
     * Was forty blocks, which is a street. A town is not a street: houses go
     * up where there is room, and the casino goes up where the owner wanted
     * it, and the two are rarely within shouting distance. At forty the floor
     * filled with strangers while the people paying rent two hundred blocks
     * away never once walked in.
     *
     * Five hundred and twelve, which is the whole of anywhere anybody is: a
     * villager in an unloaded chunk is not ticking and cannot be found by any
     * search, so the real limit has always been the view distance rather than
     * this number, and the number's only job is to stop a casino pulling
     * somebody out of a town in another biome that happens to be loaded round
     * a second player. There will never be many residents -- twenty-four is a
     * big neighbourhood -- so the right radius is "as far as a person would
     * plausibly go for a night out", and that is further than a street.
     */
    private static final int RESIDENT_RANGE = 512;

    /**
     * The nearest tenant who is not already out, or nobody.
     *
     * Nearest rather than first-found, which is what the entity list happened
     * to hand back. With one house that is the same answer; with a village it
     * is the difference between the pub filling up from next door and filling
     * up from whichever chunk loaded first -- and it is what keeps the walk
     * over short enough to be worth watching.
     *
     * Asked of the entity index by type rather than of a box, because a box
     * this size is a thousand chunk columns to walk and the index is a list of
     * what actually exists. A town of twenty-four is twenty-four things to
     * look at, once every three seconds.
     */
    private static VillagerEntity resident(ServerWorld world, BlockPos machine) {
        return TrapHomes.freeResident(world, machine, RESIDENT_RANGE);
    }

    /** Near enough the standing spot to be playing the machine at it. */
    private static final double ARRIVED = 1.75;

    /**
     * Punters actually at a machine, which is not everybody on the floor.
     *
     * The walk is thirty to forty seconds and about two in five of the room is
     * doing it at any moment, so "how busy is it" has two honest answers and
     * this is the one that prices a bet. The OTHER one -- everybody on the
     * books -- is still what {@link #room} is checked against, because a
     * walker does hold a seat and there is nowhere to put a second body on it.
     */
    private static int seated() {
        int at = 0;
        for (Punter punter : PUNTERS) {
            if (!punter.walkingIn) {
                at++;
            }
        }
        return at;
    }

    private static void leave(ServerWorld world, VillagerEntity body, Punter punter) {
        int net = punter.won - punter.lost;
        world.spawnParticles(net >= 0 ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
                body.getX(), body.getY() + 1.8, body.getZ(), 8, 0.3, 0.2, 0.3, 0.02);
        world.playSound(null, body.getBlockPos(),
                net >= 0 ? SoundEvents.ENTITY_VILLAGER_YES : SoundEvents.ENTITY_VILLAGER_NO,
                SoundCategory.NEUTRAL, 0.6F, 1.0F);
        // Everything the evening did to them, undone in one place. The AI in
        // particular: it is switched off to root them at the machine, and for
        // a long time nothing ever switched it back on -- so every resident
        // who ever had a night out stood frozen at that cabinet for good,
        // while their house decided they were missing and made another one.
        body.removeCommandTag(PUNTER_TAG);
        body.setAiDisabled(false);
        if (punter.visitor) {
            // Somebody passing through has no bed to be walked back to and no
            // entry in the register to be given back. sendHome and stayIn are
            // the two calls that turn a visitor into furniture -- the exact
            // shape of the bug this method was written to fix, one door along.
            // TrapVisitors owns whatever they do next.
            body.setCustomName(null);
            body.setCustomNameVisible(false);
            TrapVisitors.errandDone(punter.id);
            return;
        }
        TrapHomes.stayIn(body, world.getTime() + NIGHT_OFF
                + world.getRandom().nextInt(NIGHT_OFF));
        body.setCustomName(Text.literal(punter.name).formatted(Formatting.AQUA));
        // And they go home, rather than standing in the doorway forever -- put
        // there rather than walked, because a casino floor is indoors and a
        // walk home from inside one is a path nobody ever finishes.
        TrapHomes.putHome(world, body);
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
                // "Not air below" counted a lava lake as a floor, which is a
                // casino that sets its customers on fire at the door.
                if (TrapSpawn.safe(world, at)) {
                    return at;
                }
            }
        }
        return null;
    }

    /**
     * Somewhere to stand and play, beside the machine.
     *
     * The old version fell back to the machine's own square when nothing
     * horizontal was free, so a punter at a boxed-in cabinet climbed on top of
     * it and played from up there. There is no fallback now: a machine with
     * nowhere to stand at it simply gets no customers, which is a thing you
     * can look at and fix.
     *
     * Two rings, because a slot machine two blocks tall against a wall may
     * have only diagonal room, and a diagonal is still standing at it.
     */
    private static BlockPos standAt(ServerWorld world, BlockPos pos) {
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
        };
        for (int[] offset : offsets) {
            for (int drop = 0; drop <= 1; drop++) {
                BlockPos spot = pos.add(offset[0], -drop, offset[1]);
                if (TrapSpawn.safe(world, spot) && !spoken(spot)) {
                    return spot;
                }
            }
        }
        return null;
    }

    /**
     * Somebody is already standing here, or walking here to stand.
     *
     * Machines two blocks apart share the square between them, and the seat
     * only ever reserved the MACHINE. So a floor packed close -- which is what
     * a floor looks like -- had punters sent to a spot another punter was
     * already rooted on, where they could not get within reach of it, milled
     * about until their patience ran out and went home. Three trips in four
     * ended that way on a thirty-one machine floor, and every one of them read
     * from the outside as a villager that would not walk.
     *
     * ponytail: a scan of the punters on the floor, not an index of spoken-for
     * squares. There are at most a couple of dozen and this runs when somebody
     * sets off.
     */
    private static boolean spoken(BlockPos spot) {
        for (Punter punter : PUNTERS) {
            if (punter.stand.equals(spot)) {
                return true;
            }
        }
        return false;
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
        float busy = TrapMath.casinoHourFactor(
                server.getOverworld().getTimeOfDay() % 24000L);
        int walking = 0;
        for (Punter punter : PUNTERS) {
            if (punter.walkingIn) {
                walking++;
            }
        }
        float chance = Math.min(1.0f, ARRIVAL_CHANCE * busy * bestPull());
        int every = Math.round(ARRIVAL_TICKS / 20.0f / chance);
        net.minecraft.text.MutableText out = Text.literal("Sala  ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("podłączonych: " + wired + ", z kasą: " + loaded
                        + ", wolnych: " + free).formatted(Formatting.GRAY))
                .append(Text.literal("\n  na mieście: " + PUNTERS.size() + " z "
                                + TrapHomes.population() + " mieszkańców, limit o tej porze: "
                                + room(server) + (walking > 0
                                ? ", w drodze: " + walking : "")
                                + ", zajętych miejsc: " + SEATS.size())
                        .formatted(Formatting.DARK_GRAY))
                .append(Text.literal("\n  " + (busy >= 1.4f ? "Tłoczno -- nocny tłum."
                                : busy >= 0.8f ? "Zapełnia się."
                                : "Pusto. Wszyscy w pracy.")
                        + "  (" + String.format("%.2f", busy) + "x)")
                        .formatted(busy >= 1.4f ? Formatting.GREEN
                                : busy >= 0.8f ? Formatting.WHITE : Formatting.DARK_GRAY))
                .append(Text.literal("\n  Ktoś wchodzi mniej więcej co " + every
                                + "s, o ile podłączony automat ma pokrycie w skarbcu")
                        .formatted(Formatting.DARK_GRAY));
        for (TrapHouse.House house : TrapHouse.all()) {
            out.append(Text.literal("\n  " + house.name + "  reputacja "
                            + house.rep + ", bywalcy " + house.addiction
                            + ", zużycie " + TrapHouse.averageWear(house)
                            + ", bar " + TrapHouse.barStock(house) + "  ->  "
                            + String.format("%.2f", house.pull()) + "x"
                            + (house.pitBoss ? "  [boss]" : "")
                            + (house.loose() ? "  [LUZ " + house.looseBeats / 2 + "m]" : ""))
                    .formatted(house.loose() ? Formatting.GOLD : Formatting.DARK_GRAY));
        }
        Text shown = out;
        source.sendFeedback(() -> shown, false);
        return 1;
    }

    private static int force(net.minecraft.server.command.ServerCommandSource source) {
        int before = PUNTERS.size();
        maybeArrive(source.getServer(), true);
        boolean came = PUNTERS.size() > before;
        source.sendFeedback(() -> Text.literal(came
                        ? "Ktoś jest już w drodze."
                        : TrapHomes.population() == 0
                        ? "Nikt nie przyszedł -- w promieniu " + RESIDENT_RANGE
                        + " bloków nikt nie mieszka. Klienci kasyna to sąsiedzi."
                        : "Nikt nie przyszedł -- żaden podłączony automat nie jest wolny "
                        + "i pokryty kasą, albo wszyscy w okolicy już wyszli.")
                .formatted(came ? Formatting.GREEN : Formatting.RED), false);
        return came ? 1 : 0;
    }

    private TrapFloor() {
    }
}
