package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A room, a door charge, and everybody in town at once.
 *
 * The last of the three money sinks, and deliberately the last: a business
 * that earns is a faucet with an entry fee, not a drain, and it only stops
 * making the surplus worse once the city rates and the tiered works exist to
 * take the earnings back off you. They do now.
 *
 * <h2>What makes it a club rather than a shop</h2>
 *
 * A shop sells one person one thing. A club sells the same night to everybody
 * who comes, and the whole business is the tension in the door charge: cheap
 * fills the room and earns little a head, dear empties it. That is the only
 * lever, it is the owner's, and it is the reason this is worth building rather
 * than a second till -- the answer changes with how big the town is, and the
 * town grows.
 *
 * <h2>It runs on the people who already live here</h2>
 *
 * The crowd is residents, drawn through {@link TrapHomes#freeResident} exactly
 * as punters and shoppers are, so a night at the club is a night somebody is
 * not at home, at a machine, or at a till. One person, one place, one town --
 * the rule the rest of this mod was taught the hard way, and this arrives
 * already knowing it.
 *
 * <h2>And the police notice</h2>
 *
 * A full room stirs the street. A club is the loudest thing you can own, which
 * is the cost that is not measured in emeralds.
 */
public final class TrapClubs {

    /** Marks somebody on a night out at a club. */
    public static final String TAG = "trapcraft_clubber";
    /** Ticks between one look at the doors. */
    private static final int CHECK_TICKS = 60;
    /** Ticks between one guest's turn on the floor. */
    private static final int BEAT_TICKS = 50;
    /** How far a club will call somebody in from. The same reach as a casino. */
    private static final int REACH = 512;
    /** Past this a villager cannot plan the walk and is stood at the door. */
    private static final int WALKABLE = 40;
    /** How long a guest stays, in beats. */
    private static final int NIGHT_BEATS = 8;
    /** Near enough the floor to be dancing on it. */
    private static final double ARRIVED = 2.0;

    /**
     * What a door charge can be, cheapest first.
     *
     * Bands rather than a free number, for {@link TrapCity.Duty}'s reason: a
     * price somebody can set to anything is a price they set once and forget,
     * and four rungs is a decision you can actually feel. The pull is the
     * inverse -- see {@link #draw}.
     */
    public static final int[] DOOR = {8, 16, 32, 64};
    public static final String[] DOOR_NAME = {
            "Free-for-all", "Cheap night", "Proper door", "Members only"};

    /** One club. */
    public static final class Club {
        final UUID id;
        final UUID owner;
        String ownerName;
        final String dimension;
        final BlockPos pos;
        String name;
        /** Which rung of {@link #DOOR} the door is on. */
        int door = 1;
        /** Takings waiting to be lifted. */
        long till;
        /** Everybody who ever came through. */
        int through;
        long turnover;
        /** In tonight, for the sign over the door. Memory only. */
        transient int inside;

        Club(UUID id, UUID owner, String ownerName, String dimension, BlockPos pos) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.dimension = dimension;
            this.pos = pos;
        }

        public UUID owner() {
            return owner;
        }

        public String ownerName() {
            return ownerName;
        }

        public String name() {
            return name;
        }

        public BlockPos pos() {
            return pos;
        }

        public long till() {
            return till;
        }

        public int through() {
            return through;
        }

        public long turnover() {
            return turnover;
        }

        public int inside() {
            return inside;
        }

        public int door() {
            return DOOR[Math.max(0, Math.min(door, DOOR.length - 1))];
        }

        public String doorName() {
            return DOOR_NAME[Math.max(0, Math.min(door, DOOR_NAME.length - 1))];
        }

        void nextDoor() {
            door = (door + 1) % DOOR.length;
        }
    }

    /** One guest, mid-night. */
    private static final class Guest {
        final UUID id;
        final UUID club;
        final String name;
        final BlockPos floor;
        int beatsLeft = NIGHT_BEATS;
        int wait = BEAT_TICKS;
        boolean walkingIn = true;
        final long deadline;

        Guest(UUID id, UUID club, String name, BlockPos floor, long deadline) {
            this.id = id;
            this.club = club;
            this.name = name;
            this.floor = floor;
            this.deadline = deadline;
        }
    }

    private static final List<Club> CLUBS = new ArrayList<>();
    private static final Map<UUID, Guest> GUESTS = new HashMap<>();
    private static Path saveFile;

    private TrapClubs() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapClubs::load);
        // A guest outlives a restart -- they are somebody's tenant and the
        // night that owned them is gone. Sent home rather than binned, the way
        // the floor learnt to treat its punters.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(
                (entity, world) -> {
                    if (entity instanceof VillagerEntity villager
                            && villager.getCommandTags().contains(TAG)
                            && !GUESTS.containsKey(villager.getUuid())) {
                        villager.removeCommandTag(TAG);
                    }
                });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!GUESTS.isEmpty()) {
                dance(server);
            }
            if (server.getTicks() % CHECK_TICKS == 0) {
                doors(server);
            }
        });
    }

    // --- the register ---------------------------------------------------------

    public static List<Club> all() {
        return CLUBS;
    }

    public static Club at(ServerWorld world, BlockPos pos) {
        String here = world.getRegistryKey().getValue().toString();
        for (Club club : CLUBS) {
            if (club.dimension.equals(here) && club.pos.equals(pos)) {
                return club;
            }
        }
        return null;
    }

    public static Club byId(UUID id) {
        for (Club club : CLUBS) {
            if (club.id.equals(id)) {
                return club;
            }
        }
        return null;
    }

    /** Somebody puts a booth down. */
    public static void open(ServerWorld world, BlockPos pos, ServerPlayerEntity owner) {
        if (at(world, pos) != null) {
            return;
        }
        Club club = new Club(UUID.randomUUID(), owner.getUuid(),
                owner.getGameProfile().getName(),
                world.getRegistryKey().getValue().toString(), pos.toImmutable());
        club.name = spare(owner.getGameProfile().getName() + "'s club");
        CLUBS.add(club);
        save();
        owner.sendMessage(Text.literal("Wstęp za ").formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal(club.name).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("\n  Przychodzą po zmroku. Ustaw cenę biletu i "
                        + "pilnuj oświetlenia.").formatted(Formatting.GRAY)), false);
    }

    /** Taken down: the till spills and the night is over. */
    public static void close(ServerWorld world, BlockPos pos) {
        Club club = at(world, pos);
        if (club == null) {
            return;
        }
        spill(world, pos, (int) Math.min(club.till, Integer.MAX_VALUE));
        for (var guest : new ArrayList<>(GUESTS.values())) {
            if (guest.club.equals(club.id)) {
                leave(world.getServer(), guest);
            }
        }
        CLUBS.remove(club);
        save();
    }

    /** Whatever the anvil called it. */
    public static void rename(Club club, String name) {
        String trimmed = name == null ? "" : name.replace('\n', ' ').trim();
        if (trimmed.isBlank() || trimmed.equals(club.name)) {
            return;
        }
        club.name = spare(trimmed);
        save();
    }

    public static void reprice(Club club) {
        club.nextDoor();
        save();
    }

    /** Empty the till into the owner's pockets. */
    public static int collect(ServerPlayerEntity owner, Club club) {
        int takings = (int) Math.min(club.till, Integer.MAX_VALUE);
        if (takings <= 0) {
            return 0;
        }
        club.till = 0;
        // handOver, not pay: the emeralds entered the world when the town was
        // paid its wages. Paying again here would mint the same night twice.
        TrapMarket.handOver(owner, takings);
        TrapLedger.record(owner, TrapLedger.Source.STALL, takings);
        save();
        return takings;
    }

    /** The night's takings on the floor, the way a broken till spills. */
    private static void spill(ServerWorld world, BlockPos pos, int money) {
        if (money <= 0) {
            return;
        }
        int[] packed = TrapMath.packEmeralds(money);
        for (int i = 0; i < packed[0]; i++) {
            net.minecraft.block.Block.dropStack(world, pos,
                    new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD_BLOCK));
        }
        if (packed[1] > 0) {
            net.minecraft.block.Block.dropStack(world, pos,
                    new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD,
                            packed[1]));
        }
    }

    private static String spare(String wanted) {
        String name = wanted;
        for (int n = 2; taken(name); n++) {
            name = wanted + " " + n;
        }
        return name;
    }

    private static boolean taken(String name) {
        for (Club club : CLUBS) {
            if (name.equals(club.name)) {
                return true;
            }
        }
        return false;
    }

    // --- somebody comes in ----------------------------------------------------

    /**
     * How hard this club is pulling tonight.
     *
     * The door charge inverted, times the hour. A cheap night fills the room
     * and earns a pittance a head; a dear one is a quiet room at four times
     * the money, and which of those is right depends on how many people the
     * town actually has -- which is the decision the whole block exists for.
     */
    private static float draw(Club club, float busy) {
        float price = 1.0f - 0.18f * club.door;
        return price * busy;
    }

    /** Most people this club can hold at once. */
    private static int room(MinecraftServer server) {
        // A share of the town, exactly as the casino floor takes one. The
        // whole town cannot be in one room and also be anywhere else.
        return Math.max(1, Math.round(TrapHomes.population() * 0.3f));
    }

    private static void doors(MinecraftServer server) {
        if (CLUBS.isEmpty() || TrapHomes.population() <= 0) {
            return;
        }
        float busy = TrapMath.casinoHourFactor(
                server.getOverworld().getTimeOfDay() % 24000L);
        // Daylight is not a club. The hour factor floors at 0.12 so a casino
        // is never shut; a dance floor at noon is just a lit room.
        if (busy < 1.0f) {
            return;
        }
        int here = GUESTS.size();
        if (here >= room(server)) {
            return;
        }
        for (Club club : CLUBS) {
            ServerWorld world = worldOf(server, club.dimension);
            if (world == null
                    || !world.isChunkLoaded(club.pos.getX() >> 4, club.pos.getZ() >> 4)) {
                continue;
            }
            if (server.getOverworld().getRandom().nextFloat() > draw(club, busy)) {
                continue;
            }
            letIn(world, club);
        }
    }

    private static void letIn(ServerWorld world, Club club) {
        BlockPos floor = TrapSpawn.near(world, club.pos.up());
        if (floor == null) {
            return;   // nowhere to stand: no room, no night
        }
        // The town cannot afford a night out. Same gate the shops and the
        // floor read, and the reason wages matter at all.
        if (!TrapPayroll.afford(club.door())) {
            return;
        }
        VillagerEntity guest = TrapHomes.freeResident(world, club.pos, REACH);
        if (guest == null) {
            return;
        }
        String who = plainName(guest);
        guest.addCommandTag(TAG);
        guest.setCustomName(Text.literal(who).formatted(Formatting.LIGHT_PURPLE));
        guest.setCustomNameVisible(true);
        guest.wakeUp();
        // Walked if a villager can plan it, stood at the door if not. The same
        // trade the casino and the shops make, for the same reason: a
        // pathfinder gives out past forty blocks, and nobody watches a
        // neighbour cross town.
        if (!guest.getBlockPos().isWithinDistance(club.pos, WALKABLE)) {
            BlockPos door = TrapSpawn.near(world, club.pos.up(), 6);
            if (door == null) {
                return;
            }
            guest.refreshPositionAndAngles(door, world.getRandom().nextFloat() * 360f, 0f);
        }
        TrapHomes.walkTo(guest, floor);
        GUESTS.put(guest.getUuid(), new Guest(guest.getUuid(), club.id, who, floor,
                world.getTime() + 200 + 20L * WALKABLE));
    }

    private static String plainName(VillagerEntity body) {
        if (body.getCustomName() == null) {
            return "Somebody";
        }
        String shown = body.getCustomName().getString();
        int cut = shown.indexOf("  ·  ");
        return cut < 0 ? shown : shown.substring(0, cut);
    }

    // --- and dances -----------------------------------------------------------

    private static void dance(MinecraftServer server) {
        List<Guest> going = new ArrayList<>();
        for (Guest guest : GUESTS.values()) {
            Club club = byId(guest.club);
            ServerWorld world = club == null ? null : worldOf(server, club.dimension);
            if (club == null || world == null
                    || !(world.getEntity(guest.id) instanceof VillagerEntity body)
                    || !body.isAlive()) {
                going.add(guest);
                continue;
            }
            if (--guest.wait > 0) {
                continue;
            }
            if (body.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(guest.floor))
                    > ARRIVED * ARRIVED) {
                guest.wait = 20;
                if (world.getTime() < guest.deadline) {
                    body.wakeUp();
                    TrapHomes.walkTo(body, guest.floor);
                    continue;
                }
                if (TrapSpawn.safe(world, guest.floor)) {
                    body.refreshPositionAndAngles(guest.floor, 0.0F, 0.0F);
                } else {
                    going.add(guest);
                    continue;
                }
            }
            guest.wait = BEAT_TICKS;
            if (guest.walkingIn) {
                guest.walkingIn = false;
                pay(world, club, guest, body);
            }
            move(world, body);
            if (--guest.beatsLeft <= 0) {
                going.add(guest);
            }
        }
        for (Guest gone : going) {
            GUESTS.remove(gone.id);
            leave(server, gone);
        }
    }

    /** The door charge, once, on the way in. */
    private static void pay(ServerWorld world, Club club, Guest guest, VillagerEntity body) {
        int charge = club.door();
        if (!TrapPayroll.spend(charge)) {
            return;   // the town went broke between the street and the door
        }
        TrapCity.Duty duty = TrapCity.Duty.LUXURY;
        int owed = TrapCity.dutyOn(charge, duty);
        club.till += charge - Math.min(charge, owed);
        TrapCity.receive(owed, duty);
        club.through++;
        club.turnover += charge;
        club.inside++;
        body.setCustomName(Text.literal(guest.name).formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal("  ·  " + charge + "e").formatted(Formatting.GRAY)));
        world.playSound(null, club.pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                SoundCategory.RECORDS, 0.6F, 0.8F);
        // A full room is a loud room, and the police can hear it.
        if (club.inside >= 4) {
            TrapHeat.stirTheStreet(world, 1);
        }
        ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(club.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal(guest.name).formatted(Formatting.LIGHT_PURPLE)
                    .append(Text.literal(" zapłacił za wstęp w " + club.name + "  ")
                            .formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("+" + (charge - Math.min(charge, owed)) + "e")
                            .formatted(Formatting.GREEN)), true);
        }
        save();
    }

    /** A beat on the floor: they shuffle, and the room shows it. */
    private static void move(ServerWorld world, VillagerEntity body) {
        var random = world.getRandom();
        BlockPos spot = body.getBlockPos().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
        if (TrapSpawn.safe(world, spot)) {
            TrapHomes.walkTo(body, spot);
        }
        world.spawnParticles(ParticleTypes.NOTE, body.getX(), body.getY() + 2.1,
                body.getZ(), 1, 0.3, 0.1, 0.3, 1.0);
    }

    private static void leave(MinecraftServer server, Guest guest) {
        Club club = byId(guest.club);
        ServerWorld world = club == null ? null : worldOf(server, club.dimension);
        if (club != null) {
            club.inside = Math.max(0, club.inside - 1);
        }
        if (world == null || !(world.getEntity(guest.id) instanceof VillagerEntity body)) {
            return;
        }
        body.removeCommandTag(TAG);
        body.setCustomName(Text.literal(guest.name).formatted(Formatting.AQUA));
        // A night out, then a night in. The same cooldown the casino uses, so
        // one town's worth of people is shared between every venue in it.
        TrapHomes.stayIn(body, world.getTime() + TrapFloor.NIGHT_OFF
                + world.getRandom().nextInt(TrapFloor.NIGHT_OFF));
        // Put home rather than walked, for the reason the shops and the floor
        // are: a bar is indoors, and the walk out of one never lands.
        TrapHomes.putHome(world, body);
    }

    private static ServerWorld worldOf(MinecraftServer server, String dimension) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(dimension)) {
                return world;
            }
        }
        return null;
    }

    // --- the books ------------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-clubs.txt");
        CLUBS.clear();
        GUESTS.clear();
        if (!Files.exists(saveFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.split(" ", 10);
                if (parts.length < 10 || !parts[0].equals("club")) {
                    continue;
                }
                Club club = new Club(UUID.fromString(parts[1]), UUID.fromString(parts[2]),
                        parts[3], parts[4], new BlockPos(Integer.parseInt(parts[5]),
                        Integer.parseInt(parts[6]), Integer.parseInt(parts[7])));
                String[] money = parts[8].split(",");
                club.door = Integer.parseInt(money[0]);
                club.till = money.length > 1 ? Long.parseLong(money[1]) : 0;
                club.through = money.length > 2 ? Integer.parseInt(money[2]) : 0;
                club.turnover = money.length > 3 ? Long.parseLong(money[3]) : 0;
                club.name = parts[9];
                CLUBS.add(club);
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the clubs: {}", failure.toString());
        }
    }

    static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Club club : CLUBS) {
                // The name is the tail of a limited split, so it may contain
                // spaces and nothing may ever be appended after it. See the
                // note on TrapHomes.Home.heads for what that cost last time.
                out.append("club ").append(club.id).append(' ').append(club.owner)
                        .append(' ').append(club.ownerName).append(' ')
                        .append(club.dimension).append(' ')
                        .append(club.pos.getX()).append(' ').append(club.pos.getY())
                        .append(' ').append(club.pos.getZ()).append(' ')
                        .append(club.door).append(',').append(club.till).append(',')
                        .append(club.through).append(',').append(club.turnover)
                        .append(' ').append(club.name.replace('\n', ' ')).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the clubs: {}", failure.toString());
        }
    }
}
