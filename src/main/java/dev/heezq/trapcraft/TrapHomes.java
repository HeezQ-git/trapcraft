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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
    /**
     * Marks a tenant's body, so one that outlived its house can be found.
     *
     * A villager is a persistent entity and the register is a text file; the
     * two can disagree, and when they do it is always the same way round --
     * the house goes and the person stays, standing in a field forever with
     * somebody's name over their head.
     */
    public static final String TENANT_TAG = "trapcraft_tenant";
    /** Letters kept on a mailbox. Nobody reads the fifth. */
    private static final int LETTERS_KEPT = 3;
    /**
     * Names the city hands out. Nobody is "Tenant".
     *
     * Two registers, because a town has two. There are the names on the old
     * headstones, and there is whatever the kids are called now, and a place
     * where everybody is a Maud is as obviously invented as one where
     * everybody is a Skibidi. Both sets are in one pool on purpose: the joke
     * only works if Yorick lives next door to Rizzler and neither of them
     * thinks it is strange.
     *
     * Twenty-four was exactly the size of the town, which meant the pigeonhole
     * principle ran the naming: every household had a duplicate in it and the
     * street read as four copies of six people. Long enough now that a
     * collision is a coincidence rather than a certainty.
     *
     * Short on purpose -- these sit over a villager's head next to a stake or
     * a receipt ("Pookie  ·  32e"), and a long one pushes the nameplate wider
     * than the thing it is labelling.
     */
    private static final String[] NAMES = {
            // The old families.
            "Alma", "Bertie", "Cass", "Dot", "Edwin", "Fen", "Greta", "Hal",
            "Isolde", "Jory", "Kit", "Lom", "Maud", "Ned", "Orla", "Pike",
            "Quill", "Rina", "Sef", "Tam", "Ubel", "Vesta", "Wren", "Yorick",
            "Ada", "Blythe", "Bram", "Cleo", "Corin", "Dilys", "Esme", "Eira",
            "Fitch", "Gil", "Gwyn", "Hettie", "Hesper", "Ivo", "Inge", "Joss",
            "Kest", "Lark", "Mab", "Nell", "Osric", "Perrin", "Quenna", "Rufe",
            "Sable", "Thea", "Ulla", "Vance", "Wilkin", "Xan", "Yarrow", "Zeb",
            // And whatever the kids are called now.
            "Rizzler", "Skibidi", "Sigma", "Ohio", "Fanum", "Sussy", "Bussin",
            "Sheesh", "Yeet", "Goated", "Ratio", "Delulu", "Pookie", "Bestie",
            "Baddie", "Zoomer", "Doomer", "Aura", "Lore", "NPC", "Mid", "Slay",
            "Drip", "Cheugy", "Clout", "Ick", "Feral", "Deadass", "Moot",
            "Chad", "Karen", "Bet",
            "Gyatt", "Mewing", "Mogger", "Crashout", "Cooked", "Yapper", "Bozo",
            "Simp", "Stan", "Opp", "Blud", "Innit", "Twin", "Chat", "Lowkey",
            "Cringe", "Based", "Zamn", "Banger", "Peak", "Skrrt", "Grimace",
            "Sturdy", "Hawk", "Grindset", "Alpha", "Omega", "Beta", "Fein",
            "Looksmax", "Griddy", "Amogus", "Poggers", "Copium", "Sadge",
            "Grass", "Goblin", "Gremlin", "Bloomer", "Boomer", "Snatched",
            "Shook", "Savage", "Bruh", "Cap", "Yass", "Uwu", "Vibe", "Wojak",
            "Gigachad", "Chungus", "Slop", "Skull", "Brainrot", "Freaky",
            "Bruv", "Toilet", "Tralala", "Tung", "Sahur", "Patapim"};

    /** One address. */
    public static final class Home {
        final UUID id;
        final UUID owner;
        String ownerName;
        final String dimension;
        /**
         * Where it was last successfully measured from.
         *
         * Used to be final, on the theory that a fixed anchor is what lets the
         * mailbox be carried outside. It does -- but it also meant that a
         * house REBUILT somewhere else went on being measured at the old spot
         * forever, and reported the new building as full of holes. It moves
         * now, but only ever to a place that actually surveys.
         */
        BlockPos anchor;
        /** Where the post goes. May be anywhere, or nowhere. */
        BlockPos mailbox;
        int[] box = {0, 0, 0, 0, 0, 0};
        int tier;
        int floor;
        String name;
        /** Who lives here, or null. The body is decoration; this is the person. */
        String tenant;
        /**
         * How many people live here. Held in memory, never written down.
         *
         * Kept off disk on purpose, and the reason is worth the paragraph.
         * The name is the LAST field of a line and it is the tail of a
         * limited split, which is what lets a house be called whatever its
         * owner likes without the file needing quotes. That makes the format
         * unable to grow a field at the end: writing the household before the
         * name and reading it back by counting fields works perfectly until
         * somebody's house is called "HeezQ's place", at which point the two
         * words of the name make an old line look like a new one, the name
         * gets read as a number, and the WHOLE REGISTER fails to parse. Every
         * house on the server disappears, rent stops, and a mailbox nailed up
         * outside reports that the house it cannot find is not sealed.
         *
         * It does not need to be on disk anyway: every survey pass recomputes
         * it, which is a few seconds after a restart, and rent is daily. One
         * is a safe reading for that gap and it corrects itself.
         */
        int heads = 1;
        UUID body;
        int mood;
        /** Rent waiting to be picked up out of the mailbox. */
        int till;
        /** The last in-game day rent was taken, so a restart cannot double it. */
        long lastRent = -1;
        /** Newest first, and short. Nobody reads the fifth letter. */
        final List<String> letters = new ArrayList<>();
        /** What they fancy off the street today, or null. */
        Craving craving;

        public Craving craving() {
            return craving;
        }

        public String tenant() {
            return tenant;
        }

        public int mood() {
            return mood;
        }

        public int till() {
            return till;
        }

        public List<String> letters() {
            return letters;
        }

        void write(String letter) {
            if (!letters.isEmpty() && letters.get(0).equals(letter)) {
                return;   // they already said that
            }
            letters.add(0, letter);
            while (letters.size() > LETTERS_KEPT) {
                letters.remove(letters.size() - 1);
            }
        }

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

        /** Where the post goes, or null if this house has lost its box. */
        public BlockPos mailbox() {
            return mailbox;
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
                          int exits, int beds, boolean crafting, boolean storage,
                          boolean cooking, boolean stall, boolean window, int lights,
                          int kinds, int dark, float finished, String roughest, boolean registered,
                          BlockPos measuredFrom, BlockPos leak, boolean buried) {
        /** One bed is the hard requirement; the rest decide the household. */
        public boolean bed() {
            return beds > 0;
        }

        /** People this place holds, which is what the rent is charged per. */
        public int household() {
            return HomeSurvey.household(beds, floor, tier);
        }

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

    /**
     * Something a tenant wants off the street, and what they'll pay.
     *
     * The city buying drugs from the people who live in it is the last knot
     * this design needed tying. The customers who wander in from nowhere were
     * always a stand-in; these are the same people paying your rent, and
     * selling to them is the reason to walk round your own town.
     *
     * The price is rolled per craving and sits well over the counter, because
     * somebody who has decided they want a Purp joint tonight is not shopping
     * around -- same reasoning as the visiting customers, and the same reason
     * this is worth doing at all.
     */
    public record Craving(Item item, int count, int price, String label) {
    }

    /** Odds a tenant fancies something on any given day. */
    private static final float CRAVING_ODDS = 0.55f;

    private static final List<Home> HOMES = new ArrayList<>();
    private static Path saveFile;
    private static int cursor;

    private TrapHomes() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapHomes::load);
        registerCommand();
        // Every interaction with a tenant, handled here and nowhere else --
        // and consuming it, because a villager whose trade screen opens is a
        // villager offering somebody else's feature inside this one.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
                (player, world, hand, entity, hit) -> {
                    if (world.isClient() || !(player instanceof ServerPlayerEntity who)
                            || !(entity instanceof net.minecraft.entity.passive.VillagerEntity)
                            || !entity.getCommandTags().contains(TENANT_TAG)) {
                        return net.minecraft.util.ActionResult.PASS;
                    }
                    Home home = byBody(entity.getUuid());
                    if (home == null) {
                        return net.minecraft.util.ActionResult.SUCCESS;
                    }
                    talk(who, home, player.getStackInHand(hand));
                    return net.minecraft.util.ActionResult.SUCCESS;
                });
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
     * The households added up. It was the sum of the GRADES, back when a
     * house held one tenant however many beds were in it -- a stand-in that
     * stopped being needed the day beds started counting. A four-bed house is
     * four people through the shop door, which is what it should always have
     * been. It is the number the shops read to decide how much custom walks
     * in, so housing pays twice: rent, and everybody it brings past a till.
     */
    /**
     * The same name every time for the same seed.
     *
     * For somebody who STAYS -- a shopkeeper stood at a till gets respawned
     * whenever their chunk comes back, and rolling a fresh name each time
     * would mean the person behind your counter was a different person every
     * morning.
     */
    public static String nameFor(int seed) {
        return NAMES[Math.floorMod(seed, NAMES.length)];
    }

    public static int population() {
        int people = 0;
        for (Home home : HOMES) {
            if (home.tenant != null) {
                people += home.heads;
            }
        }
        return people;
    }

    /** Rent sat in mailboxes, which is money nobody is carrying. */
    public static int tillsHeld() {
        int total = 0;
        for (Home home : HOMES) {
            total += home.till;
        }
        return total;
    }

    /** Empty the mailbox into the landlord's pockets. */
    public static int collect(ServerPlayerEntity owner, Home home) {
        int takings = home.till;
        if (takings <= 0) {
            return 0;
        }
        home.till = 0;
        // handOver, not pay: the tenant's emeralds entered the world the day
        // they paid. Paying again here would mint the same rent twice.
        TrapMarket.handOver(owner, takings);
        TrapLedger.record(owner, TrapLedger.Source.RENT, takings);
        save();
        return takings;
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
            return new Readout(name, 0, 0, false, rooms.clash(), 0, 0, false, false,
                    false, false, false, 0, 0, 0, 0f, "", self != null,
                    self == null ? null : self.anchor,
                    new BlockPos(HomeSurvey.cellX(rooms.escape()),
                            HomeSurvey.cellY(rooms.escape()),
                            HomeSurvey.cellZ(rooms.escape())), rooms.buried());
        }
        int floor = rooms.floor();
        Fittings kit = fittings(world, rooms.inside());
        int tier = HomeSurvey.tier(true, floor, kit.beds > 0, !rooms.exits().isEmpty(),
                kit.finished(), kit.count(), kit.kinds, kit.dark, kit.lights);
        // Paved Roads. A house on a proper street is worth more than the same
        // house in a field, which is the only thing in this mod that rewards
        // building NEAR each other -- and a city is a lot of people who did.
        if (tier > 0 && self != null && TrapCity.paved(self.dimension, self.anchor)) {
            tier = Math.min(HomeSurvey.TOP_TIER, tier + 1);
        }
        return new Readout(name, tier, floor, true, false, rooms.exits().size(), kit.beds,
                kit.crafting, kit.storage, kit.cooking, kit.stall, kit.window,
                kit.lights, kit.kinds, kit.dark, kit.finished(), kit.roughest(), self != null,
                self == null ? null : self.anchor, null, false);
    }

    /**
     * Take the room the mailbox is standing in and put it on the books.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String found(ServerPlayerEntity owner, ServerWorld world, BlockPos pos) {
        if (atMailbox(world, pos) != null) {
            return "Ta skrzynka należy już do innego domu.";
        }
        // No city, no register. There is nobody to file the deed with, nowhere
        // for the rates to go, and no purse to pay for the road outside.
        if (!TrapCity.founded()) {
            return "Nie ma jeszcze miasta -- nie ma kto zarejestrować domu. "
                    + "Ktoś musi najpierw postawić skarbiec miasta.";
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
                    ? "Ten pokój nachodzi na dom już zapisany w rejestrze."
                    : "Ten pokój nachodzi na " + into.name
                    + (into.owner.equals(owner.getUuid()) ? " -- twój własny."
                    : ", właściciel: " + into.ownerName + ".")
                    + " Dwa domy nie mogą dzielić tego samego miejsca.";
        }
        if (!rooms.sealed()) {
            return "Nie jest szczelny. Ściany, podłoga, sufit i drzwi -- "
                    + "potem spróbuj ponownie.";
        }
        int floor = rooms.floor();
        if (floor < HomeSurvey.MIN_FLOOR) {
            return "Masz " + floor + " kratek podłogi. Nikt nie zamieszka w czymś "
                    + "mniejszym niż " + HomeSurvey.MIN_FLOOR + ".";
        }
        if (rooms.exits().isEmpty()) {
            return "Nie ma wejścia. Potrzebne drzwi na zewnątrz.";
        }

        int[] box = HomeSurvey.bounds(rooms.claimed());
        String dimension = world.getRegistryKey().getValue().toString();
        for (Home other : HOMES) {
            if (other.dimension.equals(dimension) && HomeSurvey.overlaps(box, other.box)) {
                return "To nachodzi na " + other.name + ". Dwa domy nie mogą dzielić miejsca.";
            }
        }

        Home home = new Home(UUID.randomUUID(), owner.getUuid(),
                owner.getGameProfile().getName(), dimension, pos.toImmutable());
        home.mailbox = pos.toImmutable();
        home.box = box;
        home.name = spare("Dom gracza " + owner.getGameProfile().getName());
        HOMES.add(home);
        // Graded off the survey already in hand rather than by calling
        // measure(), which would walk the same walls a second time and then
        // announce a grade change from nothing to something the player is
        // being told about in the same breath anyway.
        Readout now = grade(world, home, rooms);
        home.tier = now.tier();
        home.floor = now.floor();
        home.heads = now.household();
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

    /**
     * Re-measure a house that is already on the books.
     *
     * If the old spot no longer surveys but the MAILBOX is standing somewhere
     * that does, the house moves its anchor there. That is the self-heal for
     * the case that actually happens: somebody knocks the first room about,
     * builds a proper house, and puts the box down in it -- at which point the
     * old anchor is thirteen blocks away in a building that no longer exists
     * and every survey since has been measuring the wrong place and reporting
     * it as holes.
     */
    public static Readout measure(ServerWorld world, Home home) {
        HomeSurvey.Rooms rooms = HomeSurvey.survey(new Ground(world, home),
                home.anchor.getX(), home.anchor.getY(), home.anchor.getZ());
        if (!rooms.sealed() && !rooms.clash() && home.mailbox != null
                && !home.mailbox.equals(home.anchor)) {
            HomeSurvey.Rooms fromBox = HomeSurvey.survey(new Ground(world, home),
                    home.mailbox.getX(), home.mailbox.getY(), home.mailbox.getZ());
            if (fromBox.sealed()) {
                home.anchor = home.mailbox;
                rooms = fromBox;
                save();
            }
        }
        Readout now = grade(world, home, rooms);
        int was = home.tier;
        home.tier = now.tier();
        home.floor = now.floor();
        home.heads = now.household();
        if (now.sealed()) {
            // Only when the survey succeeded. A house that failed keeps the
            // box it had, so a wall knocked through for one pass does not hand
            // its ground to the next person who builds nearby.
            home.box = HomeSurvey.bounds(rooms.claimed());
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
        // Orphans first, and near PLAYERS rather than near houses. The case
        // that needs this most is the LAST house being demolished, at which
        // point there are no houses left to sweep near and the tenant would
        // stand in the field forever.
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            sweep(player.getWorld(), player.getBlockPos());
        }
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
        live(server, world, home);
    }

    // --- somebody lives there -------------------------------------------------

    /**
     * One house, once round the loop: who is in it and how they are getting on.
     *
     * Hung off the same round-robin as the survey rather than a clock of its
     * own, so a city of a hundred houses costs exactly what a city of ten does
     * per tick and every house is looked at in turn. The day number is the
     * gate, so a house whose chunk was asleep for three days is not owed three
     * days of rent -- it is owed one, which is the honest reading of a landlord
     * who was not there.
     */
    private static void live(MinecraftServer server, ServerWorld world, Home home) {
        long day = TrapMarket.today(server);
        if (home.tenant == null) {
            // One roll a day, not one a pass. Gated on the same field the rent
            // is, which for an empty house means "the last day anybody was
            // asked" -- so a house being looked at every twelve seconds is
            // still only offered to the street once.
            if (home.tier > 0 && home.lastRent != day) {
                home.lastRent = day;
                // Nobody takes a place on a day they would already be walking
                // out of. The notice is rolled off the house and the day, so
                // without this a tenant could move in on a notice day and be
                // gone tomorrow having never posted the letter.
                if (!HomeSurvey.quitting(seedOf(home), day)
                        && world.getRandom().nextFloat() < HomeSurvey.lettingOdds(home.tier)) {
                    moveIn(world, home, day);
                }
            }
            return;
        }
        keepBodies(world, home);
        if (home.lastRent == day) {
            return;
        }
        // The day we were last here, before it is overwritten: if THAT day
        // rolled a notice, the letter went up then and this is the morning
        // after. Reading the notice off the last day rather than off yesterday
        // is what makes a house whose chunk slept for a week still honour it.
        long lastSeen = home.lastRent;
        home.lastRent = day;
        if (lastSeen >= 0 && HomeSurvey.quitting(seedOf(home), lastSeen)) {
            moveOut(server, world, home, "wypowiedział i się wyprowadził");
            return;
        }

        int heat = TrapHeat.tierAt(world, home.anchor);
        Readout now = look(world, home.anchor, home);
        int target = HomeSurvey.moodTarget(home.tier, now.dark(), heat);
        if (TrapCity.built(TrapCity.Work.CLINIC)) {
            // A clinic does not fix a dark, crumbling house -- it buys you
            // time to fix it yourself before they pack.
            target = Math.min(HomeSurvey.MOOD_MAX, target + TrapCity.CLINIC_MOOD);
        }
        int was = home.mood;
        home.mood = HomeSurvey.moodDrift(home.mood, target);
        complain(home, now, heat, was);

        // Somebody is in a hospital bed, or waiting for one. They are not at a
        // job, they are not buying anything off the street, and the household
        // is short of their wage -- which is the whole cost of a bite and the
        // reason a city builds a ward.
        int away = TrapHospitals.awayFrom(home.id);
        int working = Math.max(0, home.heads - away);

        if (home.mood <= 0) {
            moveOut(server, world, home, heat >= 0
                    ? "nie zniósł tego, co rośnie po sąsiedzku"
                    : home.tier <= 0 ? "dom rozpadł mu się nad głową"
                    : "miał dość stanu tego domu");
            return;
        }

        // And the other way out, which has nothing to do with the house: a
        // person can simply decide they are done living here. A day's notice,
        // posted on the mailbox, and the pass after this one empties the
        // place -- see HomeSurvey#quitting for why this is asked rather than
        // remembered. They pay today's rent on their way out, because notice
        // is a day you have still paid for.
        if (HomeSurvey.quitting(seedOf(home), day)) {
            home.write("Przykro mi, wyprowadzam się. Jutro mnie nie ma.");
            ServerPlayerEntity landlord = server.getPlayerManager().getPlayer(home.owner);
            if (landlord != null) {
                landlord.sendMessage(Text.literal(home.tenant + " wyprowadza się z " + home.name + ". ")
                        .formatted(Formatting.YELLOW, Formatting.BOLD)
                        .append(Text.literal("Dzień wypowiedzenia. Jutro będzie pusto.")
                                .formatted(Formatting.GRAY)), false);
            }
        }

        // A new fancy each day, sometimes none at all. Rolled off the world's
        // random so two people asking the same tenant get the same answer.
        // Nobody in a ward is out buying anything, and the person the letters
        // are about is the one the door gets knocked for.
        home.craving = !TrapHospitals.tenantAway(home)
                && world.getRandom().nextFloat() < CRAVING_ODDS
                ? roll(world.getRandom(), home.mood, TrapAddiction.street(home.owner)) : null;

        // Paid first, then they pay their landlord out of it. This is the only
        // mint left on the town's behalf -- rent, shelf sales and casino
        // stakes all move money this one line already made.
        // The school lifts every wage in the city. Applied here rather than in
        // HomeSurvey because that class imports nothing from Minecraft and is
        // not going to start knowing what a public work is.
        //
        // Charged per person WORKING rather than per person living here, which
        // is the whole of "an ill resident earns nothing". A household with
        // everybody in a ward pays no rent either -- there is nothing coming
        // in for it to come out of.
        int wage = HomeSurvey.wageDue(home.tier, working, home.floor);
        if (TrapCity.built(TrapCity.Work.SCHOOL)) {
            wage = Math.round(wage * TrapCity.SCHOOL_WAGE);
        }
        TrapPayroll.earned(wage);

        int rent = HomeSurvey.rentDue(home.tier, home.mood, working, home.floor);
        if (rent > 0) {
            TrapCity.Duty duty = TrapCity.Duty.RENT;
            int owed = TrapCity.dutyOn(rent, duty);
            // Out of the purse now, not minted. A tenant who cannot make rent
            // pays none of it rather than part of it -- the mood drift above
            // is what eventually evicts them, and a town this broke has bigger
            // problems than one mailbox.
            if (!TrapPayroll.spend(rent + owed)) {
                save();
                return;
            }
            home.till += rent;
            TrapCity.receive(owed, duty);
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(home.owner);
            if (owner != null) {
                owner.sendMessage(Text.literal("Czynsz z " + home.name + ": ")
                        .formatted(Formatting.DARK_GRAY)
                        .append(Text.literal("+" + rent + "e").formatted(Formatting.GREEN))
                        .append(Text.literal(owed > 0 ? "   " + owed + "e podatku" : "")
                                .formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(away > 0 ? "   chorych: " + away : "")
                                .formatted(Formatting.RED)), true);
            }
        }
        save();
    }

    /**
     * What somebody fancies, and what they will pay for it.
     *
     * Weighted towards joints, which are the thing a person in a house buys
     * of an evening; powder is rarer and dearer, and a raw bud is what you
     * sell to somebody who could not get anything better. Mood shades the
     * price -- a tenant who likes where they live pays a bit over.
     *
     * <h2>The landlord's own client list</h2>
     *
     * {@code demand} is {@link TrapAddiction#street}, the neighbourhood's taste
     * for what this landlord sells. It does two things: it opens a dope bucket
     * that does not exist for somebody who has never sold any, and it widens
     * that bucket as the habit takes hold, so a street a fortnight into being
     * supplied stops asking for joints and starts asking for bags.
     *
     * That is the tenants' half of "more addicting to villagers", and it is
     * deliberately the same number the customers at the door read -- one meter
     * for the town's habit, not two that could disagree about it.
     */
    private static Craving roll(net.minecraft.util.math.random.Random random, int mood,
                                float demand) {
        Strain strain = Strain.values()[random.nextInt(Strain.values().length)];
        // Up to three of the ten buckets go over to dope once the street is
        // properly hooked, taking them off the joints at the bottom of the
        // table rather than off the powder.
        //
        // Truncated rather than rounded, so nothing opens below a client list
        // of 30. Rounding let a demand of 15 -- which a weed-only seller can
        // reach without ever having made a bag -- put a tenant on the doorstep
        // asking for one, and a craving nobody can fill is a dead day.
        int dopeBuckets = Math.min(3, (int) (demand / 30.0F));
        if (dopeBuckets > 0 && random.nextInt(10) < dopeBuckets) {
            int count = 1 + random.nextInt(3);
            // Well above powder's 48-78 a unit, and the mood bonus rides on
            // top exactly as it does for everything else.
            int each = 130 + random.nextInt(70);
            float liked = 0.85f + 0.3f * Math.max(0, Math.min(HomeSurvey.MOOD_MAX, mood))
                    / HomeSurvey.MOOD_MAX;
            return new Craving(TrapContent.heroin, count,
                    Math.max(1, Math.round(each * count * liked)), "Dope");
        }
        int roll = random.nextInt(10);
        Item item;
        String label;
        int each;
        if (roll < 5) {
            item = TrapContent.joint(strain);
            label = "Skręt " + strain.display();
            each = 22 + random.nextInt(14);
        } else if (roll < 8) {
            item = TrapContent.driedBud(strain);
            label = "Susz " + strain.display();
            each = 15 + random.nextInt(10);
        } else {
            item = TrapContent.cocaPowder;
            label = "Proszek";
            each = 48 + random.nextInt(30);
        }
        int count = 1 + random.nextInt(4);
        float liking = 0.85f + 0.3f * Math.max(0, Math.min(HomeSurvey.MOOD_MAX, mood))
                / HomeSurvey.MOOD_MAX;
        return new Craving(item, count, Math.max(1, Math.round(each * count * liking)), label);
    }

    /**
     * Somebody stops a tenant in the street.
     *
     * Empty-handed asks; holding the thing sells it. That is the whole
     * interface, and it is the same one the visiting customers use -- because
     * from where the player is stood these ARE customers, they just happen to
     * pay the rent as well.
     */
    private static void talk(ServerPlayerEntity who, Home home, ItemStack held) {
        Craving wants = home.craving;
        ServerWorld world = who.getWorld();

        if (wants != null && held.isOf(wants.item())) {
            String no = sellTo(who, home);
            if (no != null) {
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), false);
                return;
            }
            world.playSound(null, who.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES,
                    SoundCategory.NEUTRAL, 0.9F, 1.1F);
            who.sendMessage(Text.literal("Sprzedano. ").formatted(Formatting.GREEN, Formatting.BOLD)
                    .append(Text.literal(wants.count() + "x " + wants.label() + " dla "
                            + home.tenant + " za ").formatted(Formatting.GRAY))
                    .append(Text.literal(wants.price() + "e brudnych")
                            .formatted(Formatting.DARK_GREEN)), false);
            return;
        }

        if (wants == null) {
            who.sendMessage(Text.literal(home.tenant).formatted(Formatting.AQUA)
                    .append(Text.literal(" dzisiaj niczego nie potrzebuje.")
                            .formatted(Formatting.GRAY)), false);
            return;
        }
        who.sendMessage(Text.literal(home.tenant).formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal(" chce ").formatted(Formatting.GRAY))
                .append(Text.literal(wants.count() + "x " + wants.label())
                        .formatted(Formatting.WHITE))
                .append(Text.literal("  i zapłaci ").formatted(Formatting.GRAY))
                .append(Text.literal(wants.price() + "e").formatted(Formatting.GREEN))
                .append(Text.literal("\n  Weź to do ręki i kliknij go znowu. Płaci brudną kasą.")
                        .formatted(Formatting.DARK_GRAY)), false);
        world.playSound(null, who.getBlockPos(), SoundEvents.ENTITY_VILLAGER_AMBIENT,
                SoundCategory.NEUTRAL, 0.7F, 1.0F);
    }

    /** Which strain a bud or joint belongs to, for the client list. Null if neither. */
    private static Drug drugOfBud(Item item) {
        for (Strain strain : Strain.values()) {
            if (item == TrapContent.driedBud(strain) || item == TrapContent.joint(strain)) {
                return Drug.of(strain);
            }
        }
        return null;
    }

    /**
     * The house this body belongs to, by the tag it carries, or null.
     *
     * {@link #byBody} only knows the head of the household -- the one id the
     * register keeps. This works for the family too, which is what anybody
     * asking "where does this person live" actually means.
     */
    public static Home homeOf(net.minecraft.entity.Entity body) {
        for (String tag : body.getCommandTags()) {
            if (tag.startsWith(TENANT_TAG + "_")) {
                try {
                    return byId(UUID.fromString(tag.substring(TENANT_TAG.length() + 1)));
                } catch (IllegalArgumentException notOurs) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The tenant this villager is, or null. */
    public static Home byBody(java.util.UUID body) {
        for (Home home : HOMES) {
            if (body.equals(home.body)) {
                return home;
            }
        }
        return null;
    }

    /**
     * Sell a tenant what they asked for.
     *
     * Paid in dirty emeralds, recorded undeclared and stirring the street,
     * exactly like a customer at the door -- because that is what this is. The
     * only difference is that this one pays you rent as well.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String sellTo(ServerPlayerEntity seller, Home home) {
        Craving wants = home.craving;
        if (wants == null) {
            return home.tenant + " dzisiaj niczego nie potrzebuje.";
        }
        int held = 0;
        for (int slot = 0; slot < seller.getInventory().size(); slot++) {
            if (seller.getInventory().getStack(slot).isOf(wants.item())) {
                held += seller.getInventory().getStack(slot).getCount();
            }
        }
        if (held < wants.count()) {
            return home.tenant + " wants " + wants.count() + "x " + wants.label()
                    + " za " + wants.price() + "e. Masz " + held + ".";
        }
        int owed = wants.count();
        for (int slot = 0; slot < seller.getInventory().size() && owed > 0; slot++) {
            ItemStack stack = seller.getInventory().getStack(slot);
            if (stack.isOf(wants.item())) {
                int taken = Math.min(owed, stack.getCount());
                stack.decrement(taken);
                owed -= taken;
            }
        }
        TrapLaw.payDirty(seller, wants.price());
        Drug sold = wants.item() == TrapContent.heroin ? Drug.DOPE
                : wants.item() == TrapContent.cocaPowder ? Drug.COKE : null;
        TrapLedger.record(seller, sold == Drug.DOPE ? TrapLedger.Source.DOPE
                : sold == Drug.COKE ? TrapLedger.Source.COCA : TrapLedger.Source.WEED,
                wants.price());
        // Tenants feed the same client list the door customers do. A weed sale
        // to a neighbour still counts, it just counts for very little.
        TrapAddiction.sold(seller, sold != null ? sold : drugOfBud(wants.item()), wants.count());
        TrapHeat.stirTheStreet(seller.getWorld(), wants.count());
        home.craving = null;
        save();
        return null;
    }

    /**
     * The one number a house's luck hangs off.
     *
     * The low half of the id, which is the half {@link #moveIn} already picks
     * a name out of: one house, one thread of luck, and it outlives every
     * tenant because the id does.
     */
    private static long seedOf(Home home) {
        return home.id.getLeastSignificantBits();
    }

    /**
     * Has the tenant given notice? Asked, not remembered.
     *
     * The mailbox needs the same answer the daily pass does, and deriving it
     * in both places is the point of {@link HomeSurvey#quitting}: there is no
     * flag for the two of them to disagree about.
     */
    public static boolean leaving(Home home) {
        return home.tenant != null && home.lastRent >= 0
                && HomeSurvey.quitting(seedOf(home), home.lastRent);
    }

    /** Somebody takes the place on. */
    private static void moveIn(ServerWorld world, Home home, long day) {
        home.tenant = NAMES[Math.floorMod((int) (home.id.getLeastSignificantBits() + day),
                NAMES.length)];
        home.mood = HomeSurvey.MOOD_START;
        home.lastRent = day;
        home.letters.clear();
        home.write(home.tenant + " wprowadził się. Czynsz leci od jutra.");
        keepBodies(world, home);
        save();
        ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(home.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal("Ktoś wprowadził się do " + home.name + ". ")
                    .formatted(Formatting.GREEN, Formatting.BOLD)
                    .append(Text.literal((home.heads > 1
                            ? home.tenant + " i jeszcze " + (home.heads - 1) + " osób płaci "
                            : home.tenant + " płaci ")
                            + HomeSurvey.RENT[Math.min(home.tier, HomeSurvey.RENT.length - 1)]
                            * home.heads
                            + "e dziennie do skrzynki, mniej gdy są niezadowoleni.")
                            .formatted(Formatting.GRAY)), false);
        }
    }

    /** And gives it up. */
    static void moveOut(MinecraftServer server, ServerWorld world, Home home,
                        String why) {
        String who = home.tenant;
        evict(world, home);
        home.write(who + " odszedł. Powód: " + why + ".");
        save();
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(home.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal(home.name + " jest pusty. ")
                    .formatted(Formatting.RED, Formatting.BOLD)
                    .append(Text.literal(who + " " + why + ".").formatted(Formatting.GRAY)),
                    false);
        }
    }

    /**
     * Say what is wrong, once, in words.
     *
     * The letters are the tutorial and they are the reason this system needs
     * no wiki page: a person who reads "the light on the landing has gone"
     * knows exactly what to do, and nobody had to explain a lighting formula
     * to them.
     */
    private static void complain(Home home, Readout now, int heat, int was) {
        if (heat >= 0) {
            home.write("Obok coś rośnie. Czuć to w powietrzu.");
        } else if (!now.sealed()) {
            home.write("Dach jest dziurawy, widać niebo.");
        } else if (now.dark() > 0) {
            home.write("Zgasło światło na korytarzu. Ciemnych kratek: " + now.dark()
                    + " dark " + (now.dark() == 1 ? "corner" : "corners") + ".");
        } else if (home.mood > was && home.mood >= HomeSurvey.MOOD_MAX) {
            home.write("Przyjemnie tu. Dziękuję.");
        }
    }

    /**
     * Keep a villager standing in the house, if anybody is about to see it.
     *
     * Decoration, and deliberately so. The tenant is the record; this is the
     * body, and it is allowed to be missing, eaten or left in an unloaded
     * chunk without anybody losing a day's rent over it.
     */
    /**
     * A body per person in the house, and no more.
     *
     * One villager per HOUSE was the old rule, back when a house held one
     * tenant however many beds were in it. A family of four that pays four
     * rents and sends four people through the shop door should look like four
     * people, or the village is a row of addresses with one person in it.
     *
     * Counted off the world rather than remembered, and this is the important
     * part: the register cannot grow a field -- see the note on {@link
     * Home#heads} for what that cost last time -- so a list of body ids has
     * nowhere to live. Every body carries a tag naming its house instead, so
     * the answer to "how many of yours are standing here" is a question for
     * the world and survives anything the file does.
     *
     * The head of the household keeps {@link Home#body}, because the stray
     * sweep and the eviction both already know that field.
     *
     * <h2>Counted across the whole world, not near the house</h2>
     *
     * This used to count the bodies standing within {@link #BODY_RANGE} of the
     * building, which quietly meant "a resident who is anywhere else does not
     * exist". Somebody walking to the casino left the box, the house declared
     * them missing and spawned a replacement, and the town ended up with two
     * of the same person -- one at a slot machine and one at home, which is
     * exactly the thing a register is supposed to make impossible. The
     * question is "how many of ours are alive", and that is a question about
     * the world rather than about one box in it.
     */
    private static void keepBodies(ServerWorld world, Home home) {
        if (home.tenant == null) {
            return;
        }
        String tag = TENANT_TAG + "_" + home.id;
        var box = new net.minecraft.util.math.Box(
                home.box[0], home.box[1], home.box[2],
                home.box[3] + 1, home.box[4] + 1, home.box[5] + 1).expand(BODY_RANGE);
        List<? extends net.minecraft.entity.passive.VillagerEntity> living =
                world.getEntitiesByType(net.minecraft.entity.EntityType.VILLAGER,
                        found -> found.isAlive() && found.getCommandTags().contains(tag));

        // A body that gets turned stops being a VillagerEntity and becomes a
        // ZombieVillagerEntity, keeping its name and every tag. The census only
        // ever counted the living, so a turned resident was replaced by a fresh
        // one -- which had the same night ahead of it. Ninety-eight zombies
        // stood round one village before anybody worked out what they were.
        //
        // They are discarded rather than counted. Bodies are decoration and the
        // REGISTER is the person; letting a zombie hold a place would leave the
        // house looking dead for good, and counting one toward the household
        // would just be a slower version of the same crowd.
        for (var turned : world.getEntitiesByClass(
                net.minecraft.entity.mob.ZombieVillagerEntity.class, box,
                found -> found.getCommandTags().contains(tag))) {
            turned.discard();
        }

        // Anybody in a hospital bed is a body TrapHospitals is holding, in a
        // building that may be nowhere near here. They are counted OUT of the
        // house rather than counted among it: their body carries no house tag
        // at all, so the census above cannot see them, and a house that kept
        // spawning to its full household would put a second copy of a patient
        // on its own doorstep every pass.
        //
        // Anybody on shift is counted out for the same reason and by the same
        // arithmetic -- see AT_WORK. Their body was discarded when they clocked
        // on, so a house that kept spawning to its full household would stand a
        // second copy of somebody at home while they are at work.
        int athome = Math.max(0, home.heads - TrapHospitals.awayFrom(home.id)
                - atWork(world, home.id));

        // Fewer beds than there were, or a grade that slipped: the household
        // shrinks. Discarded from the end so the head of it is the last to go,
        // and never somebody who is out -- a person at a machine has a seat
        // held for them and a session running, and binning them mid-round is
        // how a casino used to eat the town that pays for it.
        for (int extra = living.size() - 1; extra >= athome; extra--) {
            var going = living.get(extra);
            if (!going.getUuid().equals(home.body) && !out(going)) {
                going.discard();
            }
        }
        for (int missing = living.size(); missing < athome; missing++) {
            body(world, home, tag, missing);
        }
        // Everybody else goes home. A villager Brain with nothing to do picks
        // a direction and strolls, which over an evening walks the whole
        // household off across the map -- the town looking abandoned while
        // twelve people mill about in a field. Somebody who is out is out on
        // purpose and left alone.
        for (var body : living) {
            if (out(body) || indoors(home, body)) {
                continue;
            }
            // Walked if the walk is one a villager can actually plan, and put
            // back if it is not. The casino brings somebody in from up to five
            // hundred blocks by standing them at its door; without the same
            // courtesy in reverse they finish their evening, fail to path
            // home, and spend the next day strolling around the floor they
            // were left on -- which is precisely what "they are never at home"
            // looks like from the street.
            //
            // ponytail: put back at the door of their own house, not walked
            // across town. Same trade as the arrival and the same reason --
            // nobody watches a neighbour walk home either.
            sendHome(world, body);
        }
    }

    /**
     * Standing in their own house, or in the garden of it.
     *
     * Measured against the surveyed BOX rather than a radius round the anchor.
     * A radius is a circle drawn on a map, and the thing it kept catching was
     * the casino next door: residents stood on a slot machine forty feet from
     * their own front door counted as being at home, so nothing ever sent them
     * back. A house is a shape somebody built, and this is that shape with a
     * garden's worth of margin on it.
     */
    private static boolean indoors(Home home, net.minecraft.entity.Entity body) {
        BlockPos at = body.getBlockPos();
        return at.getX() >= home.box[0] - HOME_EDGE && at.getX() <= home.box[3] + HOME_EDGE
                && at.getZ() >= home.box[2] - HOME_EDGE && at.getZ() <= home.box[5] + HOME_EDGE;
    }

    /** How far outside their own walls still counts as being home. */
    private static final int HOME_EDGE = 6;
    /**
     * The longest walk home worth asking a villager to plan.
     *
     * A pathfinder gives up somewhere past forty blocks and the Brain reclaims
     * the walk target between plans, so anything further is a walk target that
     * quietly does nothing while the person stands where they are.
     */
    private static final int WALK_HOME = 40;

    /**
     * Away on business of their own, whatever that business is.
     *
     * Both errands in one question, and it has to stay that way. The town is
     * one set of people: somebody at a slot machine is not also queuing at a
     * till, and neither of them is at home. Two systems each keeping their own
     * idea of who is out is two systems that will each send the same person
     * somewhere.
     */
    public static boolean out(net.minecraft.entity.Entity body) {
        return body.getCommandTags().contains(TrapFloor.PUNTER_TAG)
                || body.getCommandTags().contains(TrapShops.TAG);
    }

    /**
     * Who is staying in, and until when.
     *
     * Shared by everything that sends somebody out, because a cooldown per
     * errand is no cooldown at all -- a resident would finish at the shop and
     * be walked straight into the casino, which is the same "they are never
     * home" the casino found on its own. One person, one errand, then a while
     * at home.
     *
     * ponytail: a map the size of the town, not saved. A restart lets
     * everybody out again -- a fresh morning rather than a bug -- and entries
     * are dropped as they expire.
     */
    private static final java.util.Map<UUID, Long> STAYING_IN = new java.util.HashMap<>();

    /** Had their go. Send them in for a while. */
    public static void stayIn(net.minecraft.entity.Entity body, long until) {
        STAYING_IN.put(body.getUuid(), until);
    }

    /**
     * Houses with somebody out at a job, and the tick they are due back.
     *
     * A shift is the one errand nobody should be watching. The others end at
     * something -- a purchase, a spin, a bed -- and this one ends at a person
     * standing in somebody's shop with a villager Brain that will spend the
     * afternoon strolling out of the back of it and losing itself in the
     * building. So for the length of a shift the body simply GOES: they are at
     * work, and somebody at work is not anywhere you can see them.
     *
     * Counted rather than kept, the way {@link TrapHospitals#awayFrom} counts
     * patients -- the census takes them off the household, so nothing spawns a
     * replacement while they are out and the first pass after the shift ends
     * puts them back on their own doorstep.
     *
     * ponytail: a list the size of the shift crowd, not saved. A restart is
     * everybody home from work early, which reads as a shift ending rather
     * than as a bug, and entries are dropped as they expire.
     */
    private record Shift(UUID home, long until) {
    }

    private static final List<Shift> AT_WORK = new ArrayList<>();

    /**
     * Somebody clocks on, and their body is gone until the shift is over.
     *
     * @return false if this is nobody on the register -- somebody passing
     *         through town has no house to be away from and no doorstep to
     *         turn up on, so whoever brought them keeps them
     */
    public static boolean goToWork(net.minecraft.entity.passive.VillagerEntity body,
                                   long until) {
        Home home = homeOf(body);
        if (home == null) {
            return false;
        }
        AT_WORK.add(new Shift(home.id, until));
        body.discard();
        return true;
    }

    /** How many of this house are at work, dropping the shifts that have ended. */
    private static int atWork(ServerWorld world, UUID home) {
        AT_WORK.removeIf(shift -> shift.until() <= world.getTime());
        int out = 0;
        for (Shift shift : AT_WORK) {
            if (shift.home().equals(home)) {
                out++;
            }
        }
        return out;
    }

    /**
     * The nearest resident who is free to be asked, or nobody.
     *
     * Nearest rather than first-found, which is what the entity list happens
     * to hand back -- and it is what keeps the walk over short enough to be
     * worth watching. Asked of the entity index by type rather than of a box,
     * because a box this size is a thousand chunk columns to walk and the
     * index is a list of what actually exists; a town of two dozen is two
     * dozen things to look at.
     */
    public static net.minecraft.entity.passive.VillagerEntity freeResident(
            ServerWorld world, BlockPos near, int range) {
        net.minecraft.entity.passive.VillagerEntity nearest = null;
        double closest = (double) range * range;
        for (var villager : world.getEntitiesByType(
                net.minecraft.entity.EntityType.VILLAGER,
                found -> found.isAlive()
                        && found.getCommandTags().contains(TENANT_TAG)
                        && !out(found))) {
            // Dropped as it expires rather than swept, which is the whole of
            // this map's housekeeping.
            Long until = STAYING_IN.get(villager.getUuid());
            if (until != null) {
                if (world.getTime() < until) {
                    continue;
                }
                STAYING_IN.remove(villager.getUuid());
            }
            double away = villager.squaredDistanceTo(
                    near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
            if (away < closest) {
                closest = away;
                nearest = villager;
            }
        }
        return nearest;
    }

    /**
     * Their errand is over: back to the house they pay for.
     *
     * Walked if the walk is one a villager can actually plan, and put on their
     * own doorstep if it is not. The errand brought them across town by
     * standing them at a door; without the same courtesy in reverse they
     * finish, fail to path home, and spend the next day strolling around
     * whatever room they were left in.
     */
    public static void sendHome(ServerWorld world,
                                net.minecraft.entity.passive.VillagerEntity body) {
        Home home = homeOf(body);
        if (home == null) {
            return;
        }
        if (body.getBlockPos().isWithinDistance(home.anchor, WALK_HOME)) {
            walkTo(body, home.anchor);
            return;
        }
        putHome(world, body);
    }

    /**
     * Put them on their own doorstep, wherever they happen to be standing.
     *
     * For the end of an ERRAND, which by construction finishes indoors: a
     * till, a slot machine, a bar. {@link #sendHome} walks anybody inside
     * forty blocks, and a walk that starts in the middle of somebody's shop is
     * a villager asked to path back out of a room it only got into by being
     * stood at the counter. The target is handed out again every pass, never
     * reached, and the person spends the rest of the week strolling round a
     * building they do not live in -- which from the street is the whole town
     * lost indoors.
     *
     * The poof is not decoration. A body that vanishes off a shop floor with
     * no mark reads as a despawn bug; the same body with a puff of smoke reads
     * as somebody who has gone home, which is what happened.
     */
    public static void putHome(ServerWorld world,
                               net.minecraft.entity.passive.VillagerEntity body) {
        Home home = homeOf(body);
        if (home == null) {
            return;
        }
        BlockPos doorstep = TrapSpawn.near(world, home.anchor.up());
        if (doorstep == null) {
            return;
        }
        world.spawnParticles(net.minecraft.particle.ParticleTypes.POOF,
                body.getX(), body.getY() + 0.6, body.getZ(), 4, 0.2, 0.3, 0.2, 0.01);
        body.refreshPositionAndAngles(doorstep,
                world.getRandom().nextFloat() * 360f, 0f);
    }

    /**
     * Send somebody walking, the way the game does it.
     *
     * A walk target rather than a navigation call, because the Brain re-picks
     * its own destination whenever it has none and would simply overwrite the
     * path a tick later. Given one, the stroll task stands down -- that is the
     * memory it waits on being empty.
     */
    static void walkTo(net.minecraft.entity.passive.VillagerEntity body, BlockPos target) {
        body.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET,
                new net.minecraft.entity.ai.brain.WalkTarget(target, 0.5F, 1));
    }

    /**
     * How far from the house a resident still counts as being at home.
     *
     * Not a leash: a resident past it is walked back rather than deleted, and
     * one who is out at a machine is not measured against it at all.
     */
    private static final int BODY_RANGE = 24;

    private static void body(ServerWorld world, Home home, String tag, int index) {
        // The anchor is a block the survey picked, not a doorstep: it is as
        // likely to be under the stairs or inside the chimney as on a floor.
        // No spot means no tenant this pass, and the next pass tries again --
        // better than a household quietly suffocating in its own walls.
        BlockPos stand = TrapSpawn.near(world, home.anchor.up());
        if (stand == null) {
            return;
        }
        net.minecraft.entity.passive.VillagerEntity body =
                net.minecraft.entity.EntityType.VILLAGER.create(
                        world, net.minecraft.entity.SpawnReason.EVENT);
        if (body == null) {
            return;
        }
        body.refreshPositionAndAngles(stand, world.getRandom().nextFloat() * 360f, 0f);
        body.setPersistent();
        // The first one is the tenant the letters and the rent are about. The
        // rest are the family, and they need their own names or the house is
        // four copies of Edwin.
        String name = index == 0 ? home.tenant
                : NAMES[Math.floorMod((int) home.id.getLeastSignificantBits() + index * 7,
                        NAMES.length)];
        body.setCustomName(Text.literal(name).formatted(Formatting.AQUA));
        body.setCustomNameVisible(true);
        body.addCommandTag(TENANT_TAG);
        body.addCommandTag(tag);
        // NITWIT for the same reason the crew are: a professionless villager
        // takes a job from any workstation it wanders past and starts trading,
        // which would undercut the shop its landlord built downstairs.
        body.setVillagerData(body.getVillagerData().withProfession(
                world.getRegistryManager()
                        .getOrThrow(net.minecraft.registry.RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(net.minecraft.village.VillagerProfession.NITWIT)));
        world.spawnEntity(body);
        if (index == 0) {
            home.body = body.getUuid();
        }
    }

    private static void announce(MinecraftServer server, Home home, int was) {
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(home.owner);
        if (owner == null) {
            return;
        }
        boolean better = home.tier > was;
        owner.sendMessage(Text.literal(home.name + ": ").formatted(Formatting.GRAY)
                .append(home.tier == 0
                        ? Text.literal("nie nadaje się już do mieszkania.").formatted(Formatting.RED)
                        : Text.literal((better ? "w górę" : "w dół") + " do klasy " + home.tier)
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
        int beds;
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
        /** What the rough ones actually were, commonest first when asked. */
        final java.util.Map<Block, Integer> roughKinds = new java.util.HashMap<>();

        /**
         * The two or three blocks holding the shell back, in words.
         *
         * "83% worked material" is a number a player cannot act on -- they
         * look round a house built of stone brick and planks and reasonably
         * conclude the mod is broken. Naming the blocks turns it into a job.
         */
        String roughest() {
            return roughKinds.entrySet().stream()
                    .sorted(java.util.Map.Entry.<Block, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(found -> found.getKey().getName().getString())
                    .collect(java.util.stream.Collectors.joining(", "));
        }

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
    /**
     * Did the world hand you this, or did you make it?
     *
     * Logs are NOT on this list, and were until somebody with a spruce cabin
     * asked why their house was 83% built. A log wall is a style; nobody
     * accidentally builds one, the way they accidentally build a dirt box.
     * The rule exists to stop a hole in a hillside grading like a house, and
     * a log cabin is not a hole in a hillside.
     *
     * What is left is the stuff you can pick up with a shovel or a pick and
     * put straight back down in the same shape: earth, sand, gravel, the
     * stone the ground is made of, and the cobble it leaves when you break
     * it. Everything else -- planks, bricks, glass, wool, logs, and anything
     * a mod ships to decorate with -- is a thing somebody chose.
     */
    private static boolean rough(BlockState state) {
        if (state.isIn(net.minecraft.registry.tag.BlockTags.DIRT)
                || state.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                || state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_NETHER)
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
     * Three tests, and the third is the one that matters on a pack this size.
     * IMPERMEABLE catches vanilla glass and anything a mod puts in it; the
     * class catches panes and bars, which have no tag of their own.
     *
     * And then the NAME. Macaw's Windows ships two hundred and two window
     * blocks and puts exactly eighteen of them -- the mosaic glass -- into
     * IMPERMEABLE; the windows themselves live in a tag of the mod's own,
     * which nothing here could be expected to know about. Somebody with a
     * house full of real windows was being told to fit one.
     *
     * Reading the id is not elegant, but a mod that ships a block called
     * "acacia_window" has told us what it is in the only vocabulary every mod
     * shares. It generalises to the next one, which naming a tag would not.
     */
    private static boolean window(BlockState state) {
        if (state.isIn(net.minecraft.registry.tag.BlockTags.IMPERMEABLE)
                || state.getBlock() instanceof net.minecraft.block.PaneBlock) {
            return true;
        }
        String id = net.minecraft.registry.Registries.BLOCK
                .getId(state.getBlock()).getPath();
        return id.contains("window") || id.contains("glass");
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
            // A dark corner is measured where somebody would be STANDING, and
            // at HEAD height rather than at their feet. Light falls off a level
            // a block, so a ceiling torch reads brightest at the top of the
            // room and dimmest on the floor -- measuring at the boots called
            // a well-lit room dark and sent people burying lamps in their own
            // decoration. The brighter of the two squares is the honest answer
            // to "can you see in here".
            //
            // Block light only: a room lit through the window is a dark room at
            // midnight, which is when it matters.
            if (!inside.contains(HomeSurvey.cell(x, y - 1, z)) && loadedAt(world, x, z)) {
                int lit = Math.max(
                        world.getLightLevel(net.minecraft.world.LightType.BLOCK,
                                new BlockPos(x, y, z)),
                        world.getLightLevel(net.minecraft.world.LightType.BLOCK,
                                new BlockPos(x, y + 1, z)));
                if (lit < HomeSurvey.DARK_AT) {
                    kit.dark++;
                }
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
            if (rough(state)) {
                kit.roughKinds.merge(block, 1, Integer::sum);
            } else {
                kit.worked++;
            }
            if (window(state)) {
                kit.window = true;
            }
        }
        // The HEAD half only. A bed is two blocks and counting both would
        // put a family of four in a two-bed cottage.
        if (block instanceof BedBlock
                && state.get(BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD) {
            kit.beds++;
        }
        // Same reasoning as the window, applied to the rest of the list before
        // somebody has to report it: a hundred and thirty-six mods ship
        // furniture, and the ones that do not extend a vanilla class have
        // still called the thing what it is.
        String id = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
        if (block instanceof net.minecraft.block.CraftingTableBlock
                || block instanceof net.minecraft.block.CrafterBlock
                || id.contains("crafting") || id.contains("workbench")) {
            kit.crafting = true;
        }
        if (block instanceof AbstractFurnaceBlock
                || id.contains("furnace") || id.contains("oven")
                || id.contains("stove") || id.contains("kitchen")) {
            kit.cooking = true;
        } else if (block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock
                || id.contains("cabinet") || id.contains("cupboard")) {
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
            // The mailbox and the hospital sign are the two blocks a survey is
            // taken FROM, so both have to read as air: a fill that starts
            // inside a solid block never leaves it.
            if (state.isAir() || state.isOf(TrapContent.mailbox)
                    || state.isOf(TrapContent.hospital)) {
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

        /**
         * A block entity, which is very nearly the definition of furniture.
         *
         * Chests, barrels, beds, furnaces, signs, lecterns, campfires, flower
         * pots, banners and every modded machine and cabinet on the pack have
         * one; walls, floors and ceilings do not. It is one question instead
         * of a list of two hundred blocks, and it stays right when the pack
         * grows.
         *
         * ponytail: a crafting table and a bookshelf are furniture with no
         * block entity, so a column of them floor-to-ceiling still measures
         * nothing. Name them here if anybody ever notices.
         */
        @Override
        public boolean prop(int x, int y, int z) {
            BlockState state = stateAt(x, y, z);
            return state != null && state.hasBlockEntity();
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
            if (hasPost(world, home, near)) {
                continue;   // that one still has its box
            }
            closest = away;
            best = home;
        }
        return best;
    }

    /**
     * A house of yours nearby that ALREADY has its post, for the message only.
     *
     * Never adopted. Letting a box be taken by a house that already had one
     * looked like a kindness -- nail a second box to the wall and the post
     * moves -- and it quietly ate the commonest thing anybody does next: put
     * a box down to register a SECOND house. The room's survey fails for its
     * own reasons, the neighbour swallows the box, and a village becomes one
     * address with two mailboxes on it. What the player gets now is a sentence
     * telling them which house is in the way and how to move its post on
     * purpose.
     */
    public static Home postedNear(ServerPlayerEntity owner, ServerWorld world, BlockPos near,
                                  int range) {
        String dimension = world.getRegistryKey().getValue().toString();
        Home best = null;
        double closest = (double) range * range;
        for (Home home : HOMES) {
            if (!home.owner.equals(owner.getUuid()) || !home.dimension.equals(dimension)) {
                continue;
            }
            double away = home.anchor.getSquaredDistance(near);
            if (away <= closest && hasPost(world, home, near)) {
                closest = away;
                best = home;
            }
        }
        return best;
    }

    /** Is this house's box still standing somewhere other than here? */
    private static boolean hasPost(ServerWorld world, Home home, BlockPos near) {
        return home.mailbox != null && !home.mailbox.equals(near)
                && world.getChunkManager().isChunkLoaded(
                        home.mailbox.getX() >> 4, home.mailbox.getZ() >> 4)
                && world.getBlockState(home.mailbox).isOf(TrapContent.mailbox);
    }

    /** A house nobody is going to live in. Only its owner may say so. */
    public static String demolish(ServerPlayerEntity who, ServerWorld world, BlockPos where) {
        Home home = covering(world, where);
        if (home == null) {
            return "Nie stoisz w żadnym domu.";
        }
        if (!home.owner.equals(who.getUuid()) && !who.hasPermissionLevel(2)) {
            return "To dom gracza " + home.ownerName + ". Nie tobie go burzyć.";
        }
        String tenant = home.tenant;
        evict(world, home);
        HOMES.remove(home);
        save();
        who.sendMessage(Text.literal("Skreślone z rejestru. ").formatted(Formatting.YELLOW)
                .append(Text.literal(home.name + " to znowu zwykły pokój."
                                + (tenant == null ? ""
                                : " " + tenant + " został wyrzucony."))
                        .formatted(Formatting.GRAY)), false);
        return null;
    }

    /** Give up an address for good. */
    public static void demolish(Home home) {
        HOMES.remove(home);
        save();
    }

    /**
     * Clear the nearest villager that looks like one of ours and is not.
     *
     * Every tenant spawned from now on carries a tag and gets swept without
     * anybody asking. This is for the ones that came before it -- and it is a
     * command rather than a sweep because "villager with a name that is not on
     * my books" also describes a villager somebody named themselves, and
     * killing one of those uninvited is worse than leaving a stray.
     */
    private static String stray(ServerPlayerEntity who) {
        ServerWorld world = who.getWorld();
        java.util.Set<UUID> living = new HashSet<>();
        for (Home home : HOMES) {
            if (home.body != null) {
                living.add(home.body);
            }
        }
        net.minecraft.entity.passive.VillagerEntity nearest = null;
        double closest = 8 * 8;
        for (var villager : world.getEntitiesByClass(
                net.minecraft.entity.passive.VillagerEntity.class,
                new net.minecraft.util.math.Box(who.getBlockPos()).expand(8),
                found -> found.getCustomName() != null
                        && !living.contains(found.getUuid())
                        // The tag is what marks a tenant, and a household has
                        // three of them the register has never heard of.
                        && !found.getCommandTags().contains(TENANT_TAG)
                        // A shopkeeper is somebody's employee stood at their
                        // till, not a stray tenant, and evicting one costs the
                        // owner a hire they paid for.
                        && !found.getCommandTags().contains(TrapShops.KEEPER_TAG)
                        // Neither is somebody lying in a hospital bed, nor the
                        // doctor the city is paying to stand over them.
                        && !found.getCommandTags().contains(TrapHospitals.PATIENT_TAG)
                        && !found.getCommandTags().contains(TrapHospitals.DOCTOR_TAG)
                        && !TrapCrew.isHand(found.getUuid()))) {
            double away = villager.squaredDistanceTo(who);
            if (away <= closest) {
                closest = away;
                nearest = villager;
            }
        }
        if (nearest == null) {
            return "Nie ma tu nikogo takiego. Stań obok tej osoby.";
        }
        String name = nearest.getCustomName().getString();
        nearest.discard();
        return name + " został wyrzucony. Jego dom nie był w rejestrze.";
    }

    /**
     * A day of being ill with nobody treating it.
     *
     * Straight at the mood rather than at the target, on purpose: the target
     * is what the BUILDING is worth and a bite is not the landlord's fault.
     * This is a bad week that the house has to make up for afterwards, and it
     * evicts through the ordinary door if it goes on long enough.
     */
    static void sicken(Home home, int cost) {
        home.mood = Math.max(0, home.mood - cost);
        save();
    }

    /** Somebody is back from the ward: put a body in the house for them now. */
    static void backFromTheWard(ServerWorld world, Home home) {
        if (loaded(world, home)) {
            keepBodies(world, home);
        }
    }

    /** Put the tenant out and take their whole household with them. */
    private static void evict(ServerWorld world, Home home) {
        // Anybody of theirs in a hospital bed leaves it with them. A patient
        // whose house is gone is a body nothing owns and a bill nobody is
        // getting a resident back for.
        TrapHospitals.forget(world, home.id);
        if (home.body != null && world.getEntity(home.body) != null) {
            world.getEntity(home.body).discard();
        }
        // The rest of the family, who are not in the file and are found the
        // way they are counted: by the tag that names their house.
        String tag = TENANT_TAG + "_" + home.id;
        var box = new net.minecraft.util.math.Box(
                home.box[0], home.box[1], home.box[2],
                home.box[3] + 1, home.box[4] + 1, home.box[5] + 1).expand(BODY_RANGE);
        for (var body : world.getEntitiesByClass(
                net.minecraft.entity.passive.VillagerEntity.class, box,
                found -> found.getCommandTags().contains(tag))) {
            body.discard();
        }
        home.body = null;
        home.tenant = null;
        home.mood = 0;
    }

    /**
     * Anybody standing about with our name on them and no house behind them.
     *
     * The backstop for every way a house can leave the register that nobody
     * thought of: a command, a file edited by hand, a version of this mod that
     * no longer has the same ideas. Runs on the same round-robin as the
     * survey, in loaded chunks only, and costs one entity sweep of one chunk.
     *
     * <h2>By house, not by body</h2>
     *
     * The test used to be "is this the id in {@link Home#body}", which is the
     * HEAD of the household and nobody else. Every other person in every
     * four-bed house on the server therefore failed it, and was binned the
     * moment a player walked within twenty-four blocks of them -- then respawned
     * at the anchor twelve seconds later by {@link #keepBodies}. That is the
     * flicker: residents vanishing and reappearing at their front door forever,
     * worst exactly where players stand. A body belongs here if the house named
     * on it is on the register and has somebody living in it.
     */
    private static void sweep(ServerWorld world, BlockPos near) {
        Set<String> housed = new HashSet<>();
        for (Home home : HOMES) {
            if (home.tenant != null) {
                housed.add(TENANT_TAG + "_" + home.id);
            }
        }
        net.minecraft.util.math.Box around = new net.minecraft.util.math.Box(near).expand(24);
        for (net.minecraft.entity.passive.VillagerEntity villager
                : world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
                        around, found -> found.getCommandTags().contains(TENANT_TAG))) {
            if (java.util.Collections.disjoint(villager.getCommandTags(), housed)) {
                villager.discard();
            }
        }
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
                                // For the strays that predate the tag. Named
                                // and nearest only, so it cannot take somebody's
                                // crew or a villager they named themselves from
                                // the other side of the room.
                                .then(net.minecraft.server.command.CommandManager
                                        .literal("evict")
                                        .executes(context -> {
                                            ServerPlayerEntity who = context.getSource().getPlayer();
                                            if (who == null) {
                                                return 0;
                                            }
                                            who.sendMessage(Text.literal(stray(who))
                                                    .formatted(Formatting.GRAY), false);
                                            return 1;
                                        }))
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
            who.sendMessage(Text.literal("Nikt jeszcze nie zbudował niczego nadającego się do mieszkania.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        who.sendMessage(Text.literal("Rejestr domów").formatted(Formatting.GOLD, Formatting.BOLD),
                false);
        for (Home home : HOMES) {
            who.sendMessage(Text.literal("  " + home.name).formatted(Formatting.WHITE)
                    .append(Text.literal("  " + home.ownerName).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(home.tier == 0 ? "  do rozbiórki"
                                    : "  klasa " + home.tier)
                            .formatted(home.tier == 0 ? Formatting.RED : Formatting.GREEN))
                    .append(Text.literal(home.tenant == null ? "  pusty"
                                    : "  " + home.tenant + " (" + home.mood + ")"
                                    + (leaving(home) ? " wyprowadza się" : ""))
                            .formatted(home.tenant == null ? Formatting.DARK_GRAY
                                    : leaving(home) || home.mood < HomeSurvey.MOOD_LEAVING
                                    ? Formatting.RED : Formatting.AQUA))
                    .append(Text.literal("  " + home.anchor.getX() + " " + home.anchor.getY()
                                    + " " + home.anchor.getZ())
                            .formatted(Formatting.DARK_GRAY)), false);
        }
    }

    // --- persistence ----------------------------------------------------------

    /** Enough of a bad line to find it in the file, without spamming the log. */
    private static String failureText(String line) {
        String trimmed = line.trim();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60) + "...";
    }

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-homes.txt");
        HOMES.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                // Tenant fields ride BEFORE the name, because the name is the
                // greedy tail and always has been. A file written by step two
                // has 19 fields and loads as an empty house, which is exactly
                // what those houses were.
                //
                // The name being the tail also means this format CANNOT grow a
                // field at the end. A household count written there read back
                // as part of a two-word house name, threw, and -- because the
                // whole file used to be parsed inside one try -- took every
                // house on the server with it. Rent stopped, mailboxes could
                // not find the houses they were nailed to, and the only sign
                // of it was one warning in the log.
                try {
                String[] parts = line.trim().split("\\s+", 24);
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
                if (parts.length >= 24) {
                    home.tenant = "-".equals(parts[18]) ? null : parts[18];
                    home.body = "-".equals(parts[19]) ? null : UUID.fromString(parts[19]);
                    home.mood = Integer.parseInt(parts[20]);
                    home.till = Integer.parseInt(parts[21]);
                    home.lastRent = Long.parseLong(parts[22]);
                    home.name = parts[23];
                } else {
                    // A step-two line, whose name is the greedy tail of a
                    // NINETEEN-field split. Read at 24 it stops being greedy,
                    // and "HeezQ's place" quietly became "HeezQ's" -- so the
                    // old format has to be re-read at its own width rather
                    // than picked out of the wider one.
                    home.name = line.trim().split("\\s+", 19)[18];
                }
                HOMES.add(home);
                } catch (Exception bad) {
                    // One line, not the file. A house lost is a house; a file
                    // lost is everybody's.
                    TrapCraft.LOGGER.warn("skipped an unreadable house: {} -- {}",
                            failureText(line), bad.toString());
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the housing register: {}", failure.toString());
        }
        TrapCraft.LOGGER.info("housing register: {} houses", HOMES.size());
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
                out.append(' ').append(home.tenant == null ? "-" : home.tenant)
                        .append(' ').append(home.body == null ? "-" : home.body)
                        .append(' ').append(home.mood)
                        .append(' ').append(home.till)
                        .append(' ').append(home.lastRent);
                out.append(' ').append(home.name.replace('\n', ' ')).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the housing register: {}", failure.toString());
        }
    }
}
