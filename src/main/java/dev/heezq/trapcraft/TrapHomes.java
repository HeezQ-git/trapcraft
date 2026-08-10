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
                          boolean cooking, boolean stall, boolean window, int lights,
                          int kinds, int dark, float finished, boolean registered) {
        public int fittings() {
            return (crafting ? 1 : 0) + (storage ? 1 : 0) + (cooking ? 1 : 0)
                    + (stall ? 1 : 0) + (window ? 1 : 0);
        }

        public int points() {
            return HomeSurvey.points(finished, fittings(), kinds, dark, floor);
        }

        /** What the floor allows, which is what caps everything else. */
        public int roomFor() {
            return HomeSurvey.sizeTier(floor);
        }

        /** True when the only thing holding the grade back is how small it is. */
        public boolean cramped() {
            return sealed && roomFor() < HomeSurvey.TOP_TIER
                    && 1 + Math.min(HomeSurvey.TOP_TIER - 1, points() / 2) > roomFor();
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

    /**
     * How many people the city holds, near enough.
     *
     * The sum of the grades, which is a stand-in for the tenants step three
     * will actually put in these houses -- a grade five holds more people than
     * a grade one, and a condemned room holds nobody. It is the number the
     * shops read to decide how much custom walks through the door, so building
     * housing already pays before anybody has moved in.
     */
    public static int population() {
        int people = 0;
        for (Home home : HOMES) {
            people += home.tier;
        }
        return people;
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
            return new Readout(name, 0, 0, false, rooms.clash(), 0, false, false, false,
                    false, false, false, 0, 0, 0, 0f, self != null);
        }
        int floor = rooms.floor();
        Fittings kit = fittings(world, rooms.inside());
        int tier = HomeSurvey.tier(true, floor, kit.bed, !rooms.exits().isEmpty(),
                kit.finished(), kit.count(), kit.kinds, kit.dark, kit.lights);
        return new Readout(name, tier, floor, true, false, rooms.exits().size(), kit.bed,
                kit.crafting, kit.storage, kit.cooking, kit.stall, kit.window,
                kit.lights, kit.kinds, kit.dark, kit.finished(), self != null);
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
        // No city, no register. There is nobody to file the deed with, nowhere
        // for the rates to go, and no purse to pay for the road outside.
        if (!TrapCity.founded()) {
            return "There's no city yet -- nobody to register a house with. "
                    + "Somebody has to put a city vault down first.";
        }
        Ground ground = new Ground(world, null);
        HomeSurvey.Rooms rooms = HomeSurvey.survey(ground, pos.getX(), pos.getY(), pos.getZ());
        if (rooms.clash()) {
            // Name it, and say whose. "Somebody else's place" was the message
            // whatever it ran into -- including the player's OWN house, which
            // is the one case where it is flatly untrue and the one case that
            // actually happens.
            Home into = ground.clashed;
            return into == null
                    ? "This runs into a place that's already on the register."
                    : "This runs into " + into.name
                    + (into.owner.equals(owner.getUuid()) ? " -- your own."
                    : ", " + into.ownerName + "'s.")
                    + " Two houses can't share ground.";
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
        boolean window;
        int lights;
        int kinds;
        /** Floor squares sitting under {@link HomeSurvey#DARK_AT}. */
        int dark;
        /** Shell blocks, and how many of them somebody made rather than dug. */
        int shell;
        int worked;

        float finished() {
            return shell <= 0 ? 0f : (float) worked / shell;
        }

        int count() {
            return (crafting ? 1 : 0) + (storage ? 1 : 0) + (cooking ? 1 : 0)
                    + (stall ? 1 : 0) + (window ? 1 : 0);
        }
    }

    /**
     * Blocks the world hands you, as opposed to blocks somebody made.
     *
     * Tags first, because they are the only thing that keeps working when a
     * mod adds its own dirt. The handful of named blocks after them are the
     * ones vanilla leaves out of every useful tag and which are exactly what
     * a thrown-together shelter is built of.
     */
    private static boolean rough(BlockState state) {
        if (state.isIn(net.minecraft.registry.tag.BlockTags.DIRT)
                || state.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                || state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_NETHER)
                || state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                || state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)
                || state.isIn(net.minecraft.registry.tag.BlockTags.SNOW)) {
            return true;
        }
        Block block = state.getBlock();
        return block == Blocks.COBBLESTONE || block == Blocks.MOSSY_COBBLESTONE
                || block == Blocks.COBBLED_DEEPSLATE || block == Blocks.GRAVEL
                || block == Blocks.CLAY || block == Blocks.PACKED_MUD
                || block == Blocks.DIRT_PATH || block == Blocks.ICE
                || block == Blocks.PACKED_ICE || block == Blocks.MAGMA_BLOCK;
    }

    /**
     * Something you can see out of. A house with no windows is a cell.
     *
     * IMPERMEABLE is the tag every glass block in the game is in -- it is
     * what stops water going through -- so modded glass is covered without
     * naming any of it. Panes have no tag of their own and need the class.
     */
    private static boolean window(BlockState state) {
        return state.isIn(net.minecraft.registry.tag.BlockTags.IMPERMEABLE)
                || state.getBlock() instanceof net.minecraft.block.PaneBlock;
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
            consider(world, at, kit, kinds, false);
            int x = HomeSurvey.cellX(at);
            int y = HomeSurvey.cellY(at);
            int z = HomeSurvey.cellZ(at);
            // A dark corner is measured where somebody would be STANDING, so
            // only squares with a floor under them are asked, and only block
            // light is counted -- a room lit through the window is a dark room
            // at midnight, which is when it matters.
            if (!inside.contains(HomeSurvey.cell(x, y - 1, z))
                    && loadedAt(world, x, z)
                    && world.getLightLevel(net.minecraft.world.LightType.BLOCK,
                            new BlockPos(x, y, z)) < HomeSurvey.DARK_AT) {
                kit.dark++;
            }
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
            consider(world, at, kit, kinds, true);
        }
        kit.kinds = kinds.size();
        return kit;
    }

    private static boolean loadedAt(ServerWorld world, int x, int z) {
        return world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    /**
     * One block, and what it counts as.
     *
     * Matched on the vanilla superclasses rather than on exact blocks, because
     * this pack has a hundred and thirty-six mods in it and half of them ship
     * a chest. A modded smoker that extends AbstractFurnaceBlock counts as a
     * kitchen; one that does not is a one-line fix when somebody notices.
     */
    private static void consider(ServerWorld world, long at, Fittings kit, Set<Block> kinds,
                                 boolean shell) {
        BlockPos pos = new BlockPos(HomeSurvey.cellX(at), HomeSurvey.cellY(at),
                HomeSurvey.cellZ(at));
        if (!loadedAt(world, pos.getX(), pos.getZ())) {
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
        if (shell) {
            // Furniture counts towards the shell, and that is fine: a chest is
            // a thing somebody made. What is being asked is "did you build
            // this or did you dig it", and the answer averages honestly.
            kit.shell++;
            if (!rough(state)) {
                kit.worked++;
            }
            if (window(state)) {
                kit.window = true;
            }
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
        /** Whoever the fill ran into, so the refusal can name them. */
        Home clashed;

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
                    clashed = home;
                    return true;
                }
            }
            return false;
        }
    }

    // --- moving and losing the box --------------------------------------------

    /**
     * A mailbox went down at this spot carrying a house's details.
     *
     * Anybody else pointing at the same spot lets go of it first. Two houses
     * whose post arrives at one box is a state the readout cannot show and
     * nobody could untangle, and it is reachable by ordinary play: break a
     * box, build a second house, put ITS box in the old hole.
     */
    public static void reattach(Home home, BlockPos pos) {
        BlockPos at = pos.toImmutable();
        for (Home other : HOMES) {
            if (other != home && at.equals(other.mailbox)
                    && other.dimension.equals(home.dimension)) {
                other.mailbox = null;
            }
        }
        home.mailbox = at;
        save();
    }

    /**
     * Which of this player's houses is missing its post, nearest first.
     *
     * The recovery path for a box that lost its stamp -- and the reason a
     * blank one can be nailed up outside a house you already own and simply
     * work. A house whose mailbox position still has a mailbox standing on it
     * is not spare; anything else is.
     */
    public static Home spareOf(ServerPlayerEntity owner, ServerWorld world, BlockPos near,
                               int range) {
        String dimension = world.getRegistryKey().getValue().toString();
        Home best = null;
        double closest = (double) range * range;
        for (Home home : HOMES) {
            if (!home.owner.equals(owner.getUuid()) || !home.dimension.equals(dimension)) {
                continue;
            }
            double away = home.anchor.getSquaredDistance(near);
            if (away > closest) {
                continue;
            }
            // Distance FIRST. Reading the block would drag the chunk of a
            // house on the other side of the map into memory to answer a
            // question about one twenty blocks away.
            if (home.mailbox != null && !home.mailbox.equals(near)
                    && world.getChunkManager().isChunkLoaded(
                            home.mailbox.getX() >> 4, home.mailbox.getZ() >> 4)
                    && world.getBlockState(home.mailbox).isOf(TrapContent.mailbox)) {
                continue;   // that one still has its box
            }
            closest = away;
            best = home;
        }
        return best;
    }

    /** A house nobody is going to live in. Only its owner may say so. */
    public static String demolish(ServerPlayerEntity who, ServerWorld world, BlockPos where) {
        Home home = covering(world, where);
        if (home == null) {
            return "You're not stood in a house.";
        }
        if (!home.owner.equals(who.getUuid()) && !who.hasPermissionLevel(2)) {
            return "That's " + home.ownerName + "'s. Not yours to knock down.";
        }
        HOMES.remove(home);
        save();
        who.sendMessage(Text.literal("Off the register. ").formatted(Formatting.YELLOW)
                .append(Text.literal(home.name + " is just a room again.")
                        .formatted(Formatting.GRAY)), false);
        return null;
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
                                })
                                // The escape hatch. A survey that grabbed the
                                // wrong room used to be permanent, because the
                                // only thing that could refuse the next one was
                                // the claim it had already made.
                                .then(net.minecraft.server.command.CommandManager
                                        .literal("demolish")
                                        .executes(context -> {
                                            ServerPlayerEntity who = context.getSource().getPlayer();
                                            if (who == null) {
                                                return 0;
                                            }
                                            String no = demolish(who, who.getWorld(),
                                                    who.getBlockPos());
                                            if (no != null) {
                                                who.sendMessage(Text.literal(no)
                                                        .formatted(Formatting.GRAY), false);
                                            }
                                            return 1;
                                        }))));
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
