package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The register of places somebody built and somebody could live in.
 *
 * The city half of this mod starts here. Everything before it earns money in
 * parallel against an infinite counter; a house is the first thing the game
 * measures that you made with your hands, and step three puts a tenant in it
 * who pays for the privilege.
 *
 * <h2>Why a mailbox and not a command</h2>
 *
 * Because the house has to have a front. A command would let you register a
 * cube from the bottom of a mineshaft; a mailbox is a thing you place in the
 * room, right-click, and then -- once it has passed -- carry outside and nail
 * up by the street with the house's details still on it. That last part is
 * the whole reason the anchor and the mailbox are separate fields: the anchor
 * is where the survey was taken from and never moves, and the mailbox is
 * wherever the post goes.
 *
 * <h2>Why the survey lives somewhere else</h2>
 *
 * {@link HomeSurvey} holds the flood fill and the grading and knows nothing
 * about Minecraft, so the hard part is a JUnit test rather than an evening of
 * walking round a house wondering why the cupboard counts and the kitchen does
 * not. This class is the half that needs a world: what a block IS, who owns
 * what, and writing it down.
 *
 * <h2>The filename</h2>
 *
 * {@code trapcraft-homes.txt}. {@link TrapHouse} is the casino and got to
 * {@code trapcraft-houses.txt} first, which is a naming collision this mod is
 * stuck with and would rather have than a rename that orphans everybody's
 * casino.
 */
public final class TrapHomes {

    /** Ticks between one house being looked at again. */
    private static final int SURVEY_TICKS = 240;

    /** One address. */
    public static final class Home {
        final UUID id;
        final UUID owner;
        String ownerName;
        final String dimension;
        /** Where it was measured from. Never moves. */
        final BlockPos anchor;
        /** Where the post goes. May be anywhere, or nowhere. */
        BlockPos mailbox;
        int[] box = {0, 0, 0, 0, 0, 0};
        int tier;
        int floor;
        String name;

        Home(UUID id, UUID owner, String ownerName, String dimension, BlockPos anchor) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.dimension = dimension;
            this.anchor = anchor;
        }

        public UUID id() {
            return id;
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

        public int tier() {
            return tier;
        }

        public BlockPos anchor() {
            return anchor;
        }

        public String dimension() {
            return dimension;
        }

        boolean holds(String world, int x, int y, int z) {
            return dimension.equals(world)
                    && x >= box[0] && x <= box[3]
                    && y >= box[1] && y <= box[4]
                    && z >= box[2] && z <= box[5];
        }
    }

    /**
     * Everything the mailbox screen puts on the wall.
     *
     * A record rather than handing the screen a Home, for the reason the crew
     * board gets Cards: a Home is mutable state this class owns, and two
     * places that can change one is two places that can put a house in a
     * condition the survey never produced.
     */
    public record Readout(String name, int tier, int floor, boolean sealed, boolean clash,
                          int exits, boolean bed, boolean crafting, boolean storage,
                          boolean cooking, boolean stall, int lights, int kinds,
                          boolean lit, boolean registered) {
        public int amenities() {
            return (crafting ? 1 : 0) + (storage ? 1 : 0) + (cooking ? 1 : 0) + (stall ? 1 : 0);
        }
    }

    private static final List<Home> HOMES = new ArrayList<>();
    private static Path saveFile;
    private static int cursor;

    private TrapHomes() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapHomes::load);
        registerCommand();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % SURVEY_TICKS == 0) {
                rounds(server);
            }
        });
    }

    // --- the register ---------------------------------------------------------

    public static List<Home> all() {
        return HOMES;
    }

    public static Home byId(UUID id) {
        for (Home home : HOMES) {
            if (home.id.equals(id)) {
                return home;
            }
        }
        return null;
    }

    /** The house whose mailbox is standing at this spot. */
    public static Home atMailbox(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Home home : HOMES) {
            if (pos.equals(home.mailbox) && home.dimension.equals(dimension)) {
                return home;
            }
        }
        return null;
    }

    /** The house this spot is inside, if any. */
    public static Home covering(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Home home : HOMES) {
            if (home.holds(dimension, pos.getX(), pos.getY(), pos.getZ())) {
                return home;
            }
        }
        return null;
    }

    // --- surveying ------------------------------------------------------------

    /**
     * Look at a room and say what is there. Registers nothing.
     *
     * @param self the house being re-measured, so its own claim is not treated
     *             as somebody else's; null when nobody has claimed this yet
     */
    public static Readout look(ServerWorld world, BlockPos anchor, Home self) {
        return grade(world, self, HomeSurvey.survey(new Ground(world, self),
                anchor.getX(), anchor.getY(), anchor.getZ()));
    }

    private static Readout grade(ServerWorld world, Home self, HomeSurvey.Rooms rooms) {
        String name = self == null ? null : self.name;
        if (!rooms.sealed()) {
            return new Readout(name, 0, 0, false, rooms.clash(),
                    0, false, false, false, false, false, 0, 0, false, self != null);
        }
        int floor = rooms.floor();
        Fittings kit = fittings(world, rooms.inside());
        boolean lit = kit.lights * HomeSurvey.LIGHT_PER >= floor;
        int amenities = (kit.crafting ? 1 : 0) + (kit.storage ? 1 : 0)
                + (kit.cooking ? 1 : 0) + (kit.stall ? 1 : 0);
        int tier = HomeSurvey.tier(true, floor, kit.bed, !rooms.exits().isEmpty(),
                amenities, kit.kinds, kit.lights);
        return new Readout(name, tier, floor, true, false,
                rooms.exits().size(), kit.bed, kit.crafting, kit.storage, kit.cooking,
                kit.stall, kit.lights, kit.kinds, lit, self != null);
    }

    /**
     * Take the room the mailbox is standing in and put it on the books.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String found(ServerPlayerEntity owner, ServerWorld world, BlockPos pos) {
        if (atMailbox(world, pos) != null) {
            return "That box already belongs to a house.";
        }
        HomeSurvey.Rooms rooms = HomeSurvey.survey(new Ground(world, null),
                pos.getX(), pos.getY(), pos.getZ());
        if (rooms.clash()) {
            return "This runs into somebody else's place. Two houses can't share ground.";
        }
        if (!rooms.sealed()) {
            return "This isn't sealed. Walls, a floor, a ceiling, and a door -- "
                    + "then try again.";
        }
        int floor = rooms.floor();
        if (floor < HomeSurvey.MIN_FLOOR) {
            return "That's " + floor + " blocks of floor. Nobody will live in less than "
                    + HomeSurvey.MIN_FLOOR + ".";
        }
        if (rooms.exits().isEmpty()) {
            return "There's no way in. A door to the outside, please.";
        }

        int[] box = HomeSurvey.bounds(rooms.inside());
        String dimension = world.getRegistryKey().getValue().toString();
        for (Home other : HOMES) {
            if (other.dimension.equals(dimension) && HomeSurvey.overlaps(box, other.box)) {
                return "That overlaps " + other.name + ". Two houses can't share ground.";
            }
        }

        Home home = new Home(UUID.randomUUID(), owner.getUuid(),
                owner.getGameProfile().getName(), dimension, pos.toImmutable());
        home.mailbox = pos.toImmutable();
        home.box = box;
        home.name = spare(owner.getGameProfile().getName() + "'s place");
        HOMES.add(home);
        // Graded off the survey already in hand rather than by calling
        // measure(), which would walk the same walls a second time and then
        // announce a grade change from nothing to something the player is
        // being told about in the same breath anyway.
        Readout now = grade(world, home, rooms);
        home.tier = now.tier();
        home.floor = now.floor();
        save();
        return null;
    }

    /** "HeezQ's place", then "HeezQ's place 2". A register has to be readable. */
    private static String spare(String wanted) {
        String name = wanted;
        for (int n = 2; taken(name); n++) {
            name = wanted + " " + n;
        }
        return name;
    }

    private static boolean taken(String name) {
        for (Home home : HOMES) {
            if (name.equals(home.name)) {
                return true;
            }
        }
        return false;
    }

    /** An anvil got to the mailbox. Whatever the box says, the house is called. */
    public static void rename(Home home, String name) {
        home.name = spare(name.trim());
        save();
    }

    /** Re-measure a house that is already on the books. */
    public static Readout measure(ServerWorld world, Home home) {
        HomeSurvey.Rooms rooms = HomeSurvey.survey(new Ground(world, home),
                home.anchor.getX(), home.anchor.getY(), home.anchor.getZ());
        Readout now = grade(world, home, rooms);
        int was = home.tier;
        home.tier = now.tier();
        home.floor = now.floor();
        if (now.sealed()) {
            // Only when the survey succeeded. A house that failed keeps the
            // box it had, so a wall knocked through for one pass does not hand
            // its ground to the next person who builds nearby.
            home.box = HomeSurvey.bounds(rooms.inside());
        }
        if (was != home.tier) {
            save();
            announce(world.getServer(), home, was);
        }
        return now;
    }

    /**
     * One house a pass, round the list.
     *
     * Round-robin rather than everything at once, so the cost per tick does
     * not grow with the size of the city: ten houses means each is looked at
     * every forty seconds, a hundred means every seven minutes, and the tick
     * itself never gets slower.
     */
    private static void rounds(MinecraftServer server) {
        if (HOMES.isEmpty()) {
            return;
        }
        cursor = (cursor + 1) % HOMES.size();
        Home home = HOMES.get(cursor);
        ServerWorld world = worldOf(server, home);
        if (world == null || !loaded(world, home)) {
            return;
        }
        measure(world, home);
    }

    private static void announce(MinecraftServer server, Home home, int was) {
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(home.owner);
        if (owner == null) {
            return;
        }
        boolean better = home.tier > was;
        owner.sendMessage(Text.literal(home.name + ": ").formatted(Formatting.GRAY)
                .append(home.tier == 0
                        ? Text.literal("not fit to live in any more.").formatted(Formatting.RED)
                        : Text.literal((better ? "up" : "down") + " to grade " + home.tier)
                        .formatted(better ? Formatting.GREEN : Formatting.RED)), false);
    }

    /** Every chunk the house touches has to be about, or the survey lies. */
    private static boolean loaded(ServerWorld world, Home home) {
        for (int x : new int[]{home.box[0], home.box[3]}) {
            for (int z : new int[]{home.box[2], home.box[5]}) {
                if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                    return false;
                }
            }
        }
        return world.getChunkManager().isChunkLoaded(
                home.anchor.getX() >> 4, home.anchor.getZ() >> 4);
    }

    // --- what is in the room --------------------------------------------------

    private static final class Fittings {
        boolean bed;
        boolean crafting;
        boolean storage;
        boolean cooking;
        boolean stall;
        int lights;
        int kinds;
    }

    /**
     * Walk the room and its walls, and note what somebody put in it.
     *
     * Two passes over one set: the OPEN blocks the fill counted as floor
     * space, which is where torches and flowers live, and every solid block
     * touching one of them, which is where the bed, the chest and the walls
     * live. Between them that is the whole house and nothing outside it, with
     * no second flood fill and no bounding-box sweep pulling in the garden.
     */
    private static Fittings fittings(ServerWorld world, Set<Long> inside) {
        Fittings kit = new Fittings();
        Set<Block> kinds = new HashSet<>();
        Set<Long> walls = new HashSet<>();

        for (long at : inside) {
            consider(world, at, kit, kinds);
            int x = HomeSurvey.cellX(at);
            int y = HomeSurvey.cellY(at);
            int z = HomeSurvey.cellZ(at);
            for (int side = 0; side < 6; side++) {
                long next = HomeSurvey.cell(
                        x + (side == 0 ? 1 : side == 1 ? -1 : 0),
                        y + (side == 2 ? 1 : side == 3 ? -1 : 0),
                        z + (side == 4 ? 1 : side == 5 ? -1 : 0));
                if (!inside.contains(next)) {
                    walls.add(next);
                }
            }
        }
        for (long at : walls) {
            consider(world, at, kit, kinds);
        }
        kit.kinds = kinds.size();
        return kit;
    }

    /**
     * One block, and what it counts as.
     *
     * Matched on the vanilla superclasses rather than on exact blocks, because
     * this pack has a hundred and thirty-six mods in it and half of them ship
     * a chest. A modded smoker that extends AbstractFurnaceBlock counts as a
     * kitchen; one that does not is a one-line fix when somebody notices.
     */
    private static void consider(ServerWorld world, long at, Fittings kit, Set<Block> kinds) {
        BlockPos pos = new BlockPos(HomeSurvey.cellX(at), HomeSurvey.cellY(at),
                HomeSurvey.cellZ(at));
        if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        Block block = state.getBlock();
        kinds.add(block);
        if (state.getLuminance() > 0) {
            kit.lights++;
        }
        if (block instanceof BedBlock) {
            kit.bed = true;
        }
        if (block instanceof net.minecraft.block.CraftingTableBlock
                || block instanceof net.minecraft.block.CrafterBlock) {
            kit.crafting = true;
        }
        if (block instanceof AbstractFurnaceBlock) {
            kit.cooking = true;
        } else if (block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock) {
            kit.storage = true;
        } else {
            // The catch-all for the modded chests, cabinets and cupboards that
            // extend none of the above. Deliberately after the furnace check,
            // so an oven is a kitchen rather than a cupboard.
            BlockEntity entity = world.getBlockEntity(pos);
            if (entity instanceof Inventory box && box.size() >= 9) {
                kit.storage = true;
            }
        }
        if (block == TrapContent.marketStall) {
            kit.stall = true;
        }
    }

    // --- the world, as the survey sees it -------------------------------------

    /**
     * A {@link ServerWorld} answering the three questions a survey asks.
     *
     * An unloaded chunk answers "wall" to everything. That is the cheap half
     * of the promise that a survey never drags the world into memory -- the
     * other half is {@link #loaded}, which stops a house being re-measured at
     * all while any of it is asleep, so this only ever bites at the far edge
     * of a house somebody built on the horizon.
     */
    private static final class Ground implements HomeSurvey.Space {
        private final ServerWorld world;
        private final String dimension;
        private final UUID self;

        Ground(ServerWorld world, Home self) {
            this.world = world;
            this.dimension = world.getRegistryKey().getValue().toString();
            this.self = self == null ? null : self.id;
        }

        private BlockState stateAt(int x, int y, int z) {
            if (y < world.getBottomY() || y > world.getTopYInclusive()) {
                return null;
            }
            if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            return world.getBlockState(new BlockPos(x, y, z));
        }

        @Override
        public boolean open(int x, int y, int z) {
            BlockState state = stateAt(x, y, z);
            if (state == null) {
                return false;
            }
            if (state.isAir() || state.isOf(TrapContent.mailbox)) {
                return true;
            }
            // Water is walkable to a flood fill and would run a house into the
            // sea. Everything else you can stand in -- torches, flowers,
            // signs, ladders, rails -- has no collision, which is a better
            // list than any list.
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            return state.getCollisionShape(world, new BlockPos(x, y, z)).isEmpty();
        }

        @Override
        public boolean door(int x, int y, int z) {
            BlockState state = stateAt(x, y, z);
            return state != null && (state.getBlock() instanceof DoorBlock
                    || state.getBlock() instanceof FenceGateBlock
                    || state.getBlock() instanceof TrapdoorBlock);
        }

        @Override
        public boolean taken(int x, int y, int z) {
            for (Home home : HOMES) {
                if (!home.id.equals(self) && home.holds(dimension, x, y, z)) {
                    return true;
                }
            }
            return false;
        }
    }

    // --- moving and losing the box --------------------------------------------

    /** A mailbox went down at this spot carrying a house's details. */
    public static void reattach(Home home, BlockPos pos) {
        home.mailbox = pos.toImmutable();
        save();
    }

    /** A mailbox came up. The house keeps everything except its postbox. */
    public static void detach(Home home) {
        home.mailbox = null;
        save();
    }

    /** Give up an address for good. */
    public static void demolish(Home home) {
        HOMES.remove(home);
        save();
    }

    public static void touch() {
        save();
    }

    static ServerWorld worldOf(MinecraftServer server, Home home) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(home.dimension)) {
                return world;
            }
        }
        return null;
    }

    // --- the directory --------------------------------------------------------

    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("homes")
                                .executes(context -> {
                                    ServerPlayerEntity who = context.getSource().getPlayer();
                                    if (who == null) {
                                        return 0;
                                    }
                                    directory(who);
                                    return 1;
                                })));
    }

    private static void directory(ServerPlayerEntity who) {
        if (HOMES.isEmpty()) {
            who.sendMessage(Text.literal("Nobody's built anything worth living in yet.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        who.sendMessage(Text.literal("The Register").formatted(Formatting.GOLD, Formatting.BOLD),
                false);
        for (Home home : HOMES) {
            who.sendMessage(Text.literal("  " + home.name).formatted(Formatting.WHITE)
                    .append(Text.literal("  " + home.ownerName).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(home.tier == 0 ? "  condemned"
                                    : "  grade " + home.tier)
                            .formatted(home.tier == 0 ? Formatting.RED : Formatting.GREEN))
                    .append(Text.literal("  " + home.floor + " blocks  "
                                    + home.anchor.getX() + " " + home.anchor.getY()
                                    + " " + home.anchor.getZ())
                            .formatted(Formatting.DARK_GRAY)), false);
        }
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-homes.txt");
        HOMES.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+", 19);
                if (parts.length < 19) {
                    continue;
                }
                Home home = new Home(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                        parts[2], parts[3], new BlockPos(Integer.parseInt(parts[4]),
                        Integer.parseInt(parts[5]), Integer.parseInt(parts[6])));
                for (int i = 0; i < 6; i++) {
                    home.box[i] = Integer.parseInt(parts[7 + i]);
                }
                home.tier = Integer.parseInt(parts[13]);
                home.floor = Integer.parseInt(parts[14]);
                home.mailbox = "-".equals(parts[15]) ? null
                        : new BlockPos(Integer.parseInt(parts[15]), Integer.parseInt(parts[16]),
                        Integer.parseInt(parts[17]));
                home.name = parts[18];
                HOMES.add(home);
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the housing register: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Home home : HOMES) {
                out.append(home.id).append(' ').append(home.owner).append(' ')
                        .append(home.ownerName).append(' ').append(home.dimension).append(' ')
                        .append(home.anchor.getX()).append(' ').append(home.anchor.getY())
                        .append(' ').append(home.anchor.getZ()).append(' ');
                for (int value : home.box) {
                    out.append(value).append(' ');
                }
                out.append(home.tier).append(' ').append(home.floor).append(' ');
                out.append(home.mailbox == null ? "- - -"
                        : home.mailbox.getX() + " " + home.mailbox.getY()
                        + " " + home.mailbox.getZ());
                // Name last and unsplit, so somebody can call their house
                // whatever they like without the file needing quoting.
                out.append(' ').append(home.name.replace('\n', ' ')).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the housing register: {}", failure.toString());
        }
    }
}
