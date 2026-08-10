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
    /** Names the city hands out. Nobody is "Tenant".*/
    private static final String[] NAMES = {
            "Alma", "Bertie", "Cass", "Dot", "Edwin", "Fen", "Greta", "Hal",
            "Isolde", "Jory", "Kit", "Lom", "Maud", "Ned", "Orla", "Pike",
            "Quill", "Rina", "Sef", "Tam", "Ubel", "Vesta", "Wren", "Yorick"};

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
                          int kinds, int dark, float finished, boolean registered,
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
                    false, false, false, 0, 0, 0, 0f, self != null,
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
                kit.lights, kit.kinds, kit.dark, kit.finished(), self != null,
                self == null ? null : self.anchor, null, false);
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
        long day = world.getTimeOfDay() / 24000L;
        if (home.tenant == null) {
            if (home.tier > 0) {
                moveIn(world, home, day);
            }
            return;
        }
        keepBody(world, home);
        if (home.lastRent == day) {
            return;
        }
        home.lastRent = day;

        int heat = TrapHeat.tierAt(world, home.anchor);
        Readout now = look(world, home.anchor, home);
        int target = HomeSurvey.moodTarget(home.tier, now.dark(), heat);
        int was = home.mood;
        home.mood = HomeSurvey.moodDrift(home.mood, target);
        complain(home, now, heat, was);

        if (home.mood <= 0) {
            moveOut(server, world, home, heat >= 0
                    ? "couldn't stand what's growing next door"
                    : home.tier <= 0 ? "the place fell down round them"
                    : "had enough of the state of the place");
            return;
        }

        // A new fancy each day, sometimes none at all. Rolled off the world's
        // random so two people asking the same tenant get the same answer.
        home.craving = world.getRandom().nextFloat() < CRAVING_ODDS
                ? roll(world.getRandom(), home.mood) : null;

        int rent = HomeSurvey.rentDue(home.tier, home.mood, home.heads);
        if (rent > 0) {
            TrapCity.Duty duty = TrapCity.Duty.RENT;
            int owed = TrapCity.dutyOn(rent, duty);
            // Minted, like a shopper's money: a tenant is not a player and
            // their emeralds were never in the world before. Split at once
            // between the mailbox and the purse, both of which the market
            // resample knows to count.
            TrapMarket.minted(rent + owed);
            home.till += rent;
            TrapCity.receive(owed, duty);
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(home.owner);
            if (owner != null) {
                owner.sendMessage(Text.literal("Rent from " + home.name + ": ")
                        .formatted(Formatting.DARK_GRAY)
                        .append(Text.literal("+" + rent + "e").formatted(Formatting.GREEN))
                        .append(Text.literal(owed > 0 ? "   " + owed + "e duty" : "")
                                .formatted(Formatting.DARK_GRAY)), true);
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
     */
    private static Craving roll(net.minecraft.util.math.random.Random random, int mood) {
        Strain strain = Strain.values()[random.nextInt(Strain.values().length)];
        int roll = random.nextInt(10);
        Item item;
        String label;
        int each;
        if (roll < 5) {
            item = TrapContent.joint(strain);
            label = strain.display() + " Joint";
            each = 22 + random.nextInt(14);
        } else if (roll < 8) {
            item = TrapContent.driedBud(strain);
            label = "Cured " + strain.display();
            each = 15 + random.nextInt(10);
        } else {
            item = TrapContent.cocaPowder;
            label = "Powder";
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
            who.sendMessage(Text.literal("Sold. ").formatted(Formatting.GREEN, Formatting.BOLD)
                    .append(Text.literal(wants.count() + "x " + wants.label() + " to "
                            + home.tenant + " for ").formatted(Formatting.GRAY))
                    .append(Text.literal(wants.price() + "e dirty")
                            .formatted(Formatting.DARK_GREEN)), false);
            return;
        }

        if (wants == null) {
            who.sendMessage(Text.literal(home.tenant).formatted(Formatting.AQUA)
                    .append(Text.literal(" isn't after anything today.")
                            .formatted(Formatting.GRAY)), false);
            return;
        }
        who.sendMessage(Text.literal(home.tenant).formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal(" wants ").formatted(Formatting.GRAY))
                .append(Text.literal(wants.count() + "x " + wants.label())
                        .formatted(Formatting.WHITE))
                .append(Text.literal("  and will pay ").formatted(Formatting.GRAY))
                .append(Text.literal(wants.price() + "e").formatted(Formatting.GREEN))
                .append(Text.literal("\n  Hold it and click them again. They pay dirty.")
                        .formatted(Formatting.DARK_GRAY)), false);
        world.playSound(null, who.getBlockPos(), SoundEvents.ENTITY_VILLAGER_AMBIENT,
                SoundCategory.NEUTRAL, 0.7F, 1.0F);
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
            return home.tenant + " isn't after anything today.";
        }
        int held = 0;
        for (int slot = 0; slot < seller.getInventory().size(); slot++) {
            if (seller.getInventory().getStack(slot).isOf(wants.item())) {
                held += seller.getInventory().getStack(slot).getCount();
            }
        }
        if (held < wants.count()) {
            return home.tenant + " wants " + wants.count() + "x " + wants.label()
                    + " for " + wants.price() + "e. You've " + held + ".";
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
        TrapLedger.record(seller, wants.item() == TrapContent.cocaPowder
                ? TrapLedger.Source.COCA : TrapLedger.Source.WEED, wants.price());
        TrapHeat.stirTheStreet(seller.getWorld(), wants.count());
        home.craving = null;
        save();
        return null;
    }

    /** Somebody takes the place on. */
    private static void moveIn(ServerWorld world, Home home, long day) {
        home.tenant = NAMES[Math.floorMod((int) (home.id.getLeastSignificantBits() + day),
                NAMES.length)];
        home.mood = HomeSurvey.MOOD_START;
        home.lastRent = day;
        home.letters.clear();
        home.write(home.tenant + " has moved in. Rent starts tomorrow.");
        keepBody(world, home);
        save();
        ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(home.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal("Somebody's moved into " + home.name + ". ")
                    .formatted(Formatting.GREEN, Formatting.BOLD)
                    .append(Text.literal((home.heads > 1
                            ? home.tenant + " and " + (home.heads - 1) + " more pay "
                            : home.tenant + " pays ")
                            + HomeSurvey.RENT[Math.min(home.tier, HomeSurvey.RENT.length - 1)]
                            * home.heads
                            + "e a day into the mailbox, less if they're unhappy.")
                            .formatted(Formatting.GRAY)), false);
        }
    }

    /** And gives it up. */
    private static void moveOut(MinecraftServer server, ServerWorld world, Home home,
                                String why) {
        String who = home.tenant;
        evict(world, home);
        home.write(who + " has gone. They " + why + ".");
        save();
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(home.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal(home.name + " is empty. ")
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
            home.write("There's something growing next door. I can smell it.");
        } else if (!now.sealed()) {
            home.write("The roof is open to the sky.");
        } else if (now.dark() > 0) {
            home.write("The light on the landing has gone. " + now.dark()
                    + " dark " + (now.dark() == 1 ? "corner" : "corners") + ".");
        } else if (home.mood > was && home.mood >= HomeSurvey.MOOD_MAX) {
            home.write("Lovely here. Thank you.");
        }
    }

    /**
     * Keep a villager standing in the house, if anybody is about to see it.
     *
     * Decoration, and deliberately so. The tenant is the record; this is the
     * body, and it is allowed to be missing, eaten or left in an unloaded
     * chunk without anybody losing a day's rent over it.
     */
    private static void keepBody(ServerWorld world, Home home) {
        if (home.body != null && world.getEntity(home.body) != null) {
            return;
        }
        net.minecraft.entity.passive.VillagerEntity body =
                net.minecraft.entity.EntityType.VILLAGER.create(
                        world, net.minecraft.entity.SpawnReason.EVENT);
        if (body == null) {
            return;
        }
        body.refreshPositionAndAngles(home.anchor.up(), world.getRandom().nextFloat() * 360f, 0f);
        body.setPersistent();
        body.setCustomName(Text.literal(home.tenant).formatted(Formatting.AQUA));
        body.setCustomNameVisible(true);
        body.addCommandTag(TENANT_TAG);
        // NITWIT for the same reason the crew are: a professionless villager
        // takes a job from any workstation it wanders past and starts trading,
        // which would undercut the shop its landlord built downstairs.
        body.setVillagerData(body.getVillagerData().withProfession(
                world.getRegistryManager()
                        .getOrThrow(net.minecraft.registry.RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(net.minecraft.village.VillagerProfession.NITWIT)));
        world.spawnEntity(body);
        home.body = body.getUuid();
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
            if (!rough(state)) {
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
        Home spare = null;
        Home served = null;
        double toSpare = (double) range * range;
        double toServed = (double) range * range;
        for (Home home : HOMES) {
            if (!home.owner.equals(owner.getUuid()) || !home.dimension.equals(dimension)) {
                continue;
            }
            double away = home.anchor.getSquaredDistance(near);
            // Distance FIRST. Reading the block would drag the chunk of a
            // house on the other side of the map into memory to answer a
            // question about one twenty blocks away.
            if (away > Math.max(toSpare, toServed)) {
                continue;
            }
            boolean hasBox = home.mailbox != null && !home.mailbox.equals(near)
                    && world.getChunkManager().isChunkLoaded(
                            home.mailbox.getX() >> 4, home.mailbox.getZ() >> 4)
                    && world.getBlockState(home.mailbox).isOf(TrapContent.mailbox);
            if (hasBox) {
                if (away < toServed) {
                    toServed = away;
                    served = home;
                }
            } else if (away < toSpare) {
                toSpare = away;
                spare = home;
            }
        }
        // A house with no post wins, and a house that already has one is the
        // fallback rather than a refusal. Nailing a second box to the outside
        // wall of your own house is the commonest thing anybody does with one
        // -- the book even tells them to -- and it used to be answered with
        // "this isn't sealed", which is a survey error about the street they
        // were stood on and reads as "your house is broken". The post simply
        // moves to the new box; reattach lets the old one go.
        return spare != null ? spare : served;
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
        String tenant = home.tenant;
        evict(world, home);
        HOMES.remove(home);
        save();
        who.sendMessage(Text.literal("Off the register. ").formatted(Formatting.YELLOW)
                .append(Text.literal(home.name + " is just a room again."
                                + (tenant == null ? ""
                                : " " + tenant + " has been put out."))
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
                        && !TrapCrew.isHand(found.getUuid()))) {
            double away = villager.squaredDistanceTo(who);
            if (away <= closest) {
                closest = away;
                nearest = villager;
            }
        }
        if (nearest == null) {
            return "Nobody here who shouldn't be. Stand next to them.";
        }
        String name = nearest.getCustomName().getString();
        nearest.discard();
        return name + " has been put out. Their house wasn't on the register.";
    }

    /** Put the tenant out and take their body with them. */
    private static void evict(ServerWorld world, Home home) {
        if (home.body != null && world.getEntity(home.body) != null) {
            world.getEntity(home.body).discard();
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
     */
    private static void sweep(ServerWorld world, BlockPos near) {
        java.util.Set<UUID> living = new HashSet<>();
        for (Home home : HOMES) {
            if (home.body != null) {
                living.add(home.body);
            }
        }
        net.minecraft.util.math.Box around = new net.minecraft.util.math.Box(near).expand(24);
        for (net.minecraft.entity.passive.VillagerEntity villager
                : world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
                        around, found -> found.getCommandTags().contains(TENANT_TAG))) {
            if (!living.contains(villager.getUuid())) {
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
                    .append(Text.literal(home.tenant == null ? "  empty"
                                    : "  " + home.tenant + " (" + home.mood + ")")
                            .formatted(home.tenant == null ? Formatting.DARK_GRAY
                                    : home.mood < HomeSurvey.MOOD_LEAVING ? Formatting.RED
                                    : Formatting.AQUA))
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
