package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
 * The third thing that goes wrong, and the building that answers it.
 *
 * <h2>Why the town needed a third</h2>
 *
 * Two pressures, and both of them are somebody's fault. {@link TrapHeat} sends
 * men with axes because you grew too much where you could be seen, and
 * {@link TrapCrime} sends a burglar because the town has poor people in it.
 * Each is a consequence with an author, and each has a counterplay that is
 * really an apology -- grow less, or fund the police.
 *
 * A fire is the other kind of thing. Nobody did it, nothing caused it, and
 * there is no behaviour to change in response. That is exactly what was
 * missing: the city had no accident. Every bill the council paid was a bill
 * some player's choices had generated, so a treasury was a scoreboard of
 * everybody's decisions rather than a thing you keep money in BECAUSE you do
 * not know what is coming.
 *
 * <h2>Nothing burns down</h2>
 *
 * No fire block is ever placed and no block is ever broken. That is a hard
 * rule and it is the same one {@link TrapParanoia} is written to: a mechanic
 * that can permanently eat a build somebody spent an evening on is not a
 * pressure, it is a reason to turn the mod off. Ask anybody who has lost a
 * base to a lava bucket whether the story was fun.
 *
 * So a fire is a STATE on a building's register entry, drawn as flame and
 * smoke particles and a crackle everybody nearby can hear. What it destroys is
 * the things this mod invented and can therefore honestly take back: a
 * household's mood, a shop's till, and -- through the door
 * {@link TrapHospitals#hurt} already opens for a bite and a beating -- the
 * people inside. It costs you a week of rent and a person in a bed. It does
 * not cost you the house.
 *
 * <h2>The building is the dial</h2>
 *
 * Deliberately unlike {@link TrapPolice}, which has a budget slider because
 * policing is a level of service you buy more or less of. Turning up to a fire
 * is not a level of service. Either an engine reaches the address in time or
 * it does not, so the only questions are how many engines there are and how
 * far they are from the trouble -- and both of those are answered by where
 * somebody chose to build, which is the most legible dial there is.
 *
 * Engines come off the FLOOR of the room, one per {@link #FLOOR_PER_ENGINE},
 * for the reason the ward counts beds and the nick counts cells: a garage
 * holds what fits in it. The city pays {@link #CALLOUT} a run into
 * {@link TrapPayroll}, because a firefighter is a townsperson and their wage
 * comes back through a shop door -- and because a purse that cannot cover the
 * call is a town that watches, which is the whole reason a treasury should
 * ever hold a reserve.
 */
public final class TrapFires {

    /** Ticks between one look at everything alight. */
    private static final int TICK = 20;
    /** Ticks between one roll for a new fire. */
    private static final int ROLL_TICKS = 20 * 60 * 3;
    /**
     * Odds of a fire per roll, per registered building in a loaded chunk.
     *
     * A three-minute roll, so a town of twelve buildings sees roughly one fire
     * every four hours of play. Rare enough to be news, often enough that a
     * player who has never built a remiza will meet one -- which is the only
     * way a building nobody has to build ever gets built.
     */
    private static final float ODDS = 0.004f;

    /** How long a fire burns before it has done its damage. */
    public static final int BURNS_SECONDS = 75;
    private static final int BURNS_FOR = 20 * BURNS_SECONDS;
    /** How long an engine takes to arrive, per block of the run. */
    private static final int TICKS_PER_BLOCK = 2;
    /** Flat turnout time on top of the run: getting the engine out of the shed. */
    private static final int TURNOUT = 20 * 8;

    /** Smallest floor a garage can have, and the floor one engine takes up. */
    public static final int MIN_FLOOR = 28;
    public static final int FLOOR_PER_ENGINE = 22;
    /** Most engines one station will ever hold, however big the shed is. */
    public static final int MAX_ENGINES = 4;
    /** How far an engine will run to a fire. */
    public static final int REACH = 96;

    /** What the city pays for one turnout. */
    public static final int CALLOUT = 260;

    /** How near a player has to be, holding water, to put one out themselves. */
    private static final double BUCKET_RANGE = 4.0;

    /** Mood a household loses to a fire nobody answered. */
    private static final int SCORCHED_MOOD = 22;
    /** Share of a shop's till that goes up with it. */
    private static final float TILL_LOST = 0.35f;

    /** One garage. */
    public static final class Brigade {
        final UUID id;
        final UUID owner;
        String ownerName;
        final String dimension;
        BlockPos sign;
        int floor;
        boolean open;
        /** Turnouts answered, for the plaque. */
        int runs;
        String name;
        /** Engines out right now. Memory only: a restart is everybody back. */
        transient int out;

        Brigade(UUID id, UUID owner, String ownerName, String dimension, BlockPos sign) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.dimension = dimension;
            this.sign = sign;
        }

        public String name() {
            return name;
        }

        public BlockPos sign() {
            return sign;
        }

        public int runs() {
            return runs;
        }

        public boolean open() {
            return open;
        }

        /** How many engines this shed holds. */
        public int engines() {
            return Math.max(1, Math.min(MAX_ENGINES, floor / FLOOR_PER_ENGINE));
        }

        /** Engines standing in the shed right now. */
        public int free() {
            return Math.max(0, engines() - out);
        }
    }

    /**
     * One building alight.
     *
     * Keyed by the HOUSE rather than by a position, the way a patient is,
     * because a register entry survives the things a coordinate does not --
     * and because the damage at the end of it is done to the entry.
     */
    private static final class Fire {
        final String dimension;
        final BlockPos pos;
        /**
         * The home that is burning, or null when it is a shop.
         *
         * A house is held by id and a shop by the position already in this
         * record, and the asymmetry is the registers' rather than a choice: a
         * {@link TrapHomes.Home} has an id because the letters, the rent and
         * the patients are all keyed by it, and a {@link TrapShops.Shop} has
         * never had one -- {@code shopAt} is how everything finds one.
         */
        final UUID home;
        final String what;
        /** World tick the damage lands on, if nobody has got there. */
        final long by;
        /** World tick an engine arrives on, or 0 while nobody is coming. */
        long answered;
        UUID brigade;

        Fire(String dimension, BlockPos pos, UUID home, String what, long by) {
            this.dimension = dimension;
            this.pos = pos;
            this.home = home;
            this.what = what;
            this.by = by;
        }
    }

    private static final List<Brigade> SHEDS = new ArrayList<>();
    /**
     * ponytail: fires are memory only, not saved.
     *
     * A fire is seventy-five seconds long and a restart is minutes. Persisting
     * one would mean the server coming back up and immediately scorching a
     * house over a fire that stopped existing when the process did, which is a
     * worse answer than "it went out while nobody was looking".
     */
    private static final List<Fire> BURNING = new ArrayList<>();
    private static Path saveFile;

    private TrapFires() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapFires::load);
        registerCommand();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % TICK == 0 && !BURNING.isEmpty()) {
                burn(server);
            }
            if (server.getTicks() % ROLL_TICKS == 0) {
                maybeStart(server);
            }
        });
    }

    // --- the register ---------------------------------------------------------

    public static List<Brigade> all() {
        return SHEDS;
    }

    public static Brigade at(ServerWorld world, BlockPos pos) {
        String here = world.getRegistryKey().getValue().toString();
        for (Brigade shed : SHEDS) {
            if (shed.dimension.equals(here) && shed.sign.equals(pos)) {
                return shed;
            }
        }
        return null;
    }

    /** Total engines standing in every shed in town. */
    public static int engines() {
        int total = 0;
        for (Brigade shed : SHEDS) {
            if (shed.open) {
                total += shed.engines();
            }
        }
        return total;
    }

    public static int burning() {
        return BURNING.size();
    }

    /**
     * What this room is short of, or null if it is a garage.
     *
     * Same checklist and the same order as {@link TrapHospitals#fault}, minus
     * the beds and plus the space, because the grammar is the point: a mailbox
     * taught it, a ward reused it, a nick reused it, and this is the fourth
     * building nobody has to learn a new way of registering.
     */
    public static String fault(TrapHomes.Readout reading) {
        if (reading.clash()) {
            return "To wnętrze domu już zapisanego w rejestrze. "
                    + "Remiza potrzebuje własnego budynku.";
        }
        if (reading.buried()) {
            return "Blok remizy stoi w litym bloku. Postaw go w POWIETRZU "
                    + "wewnątrz pomieszczenia, nie w ścianie.";
        }
        if (!reading.sealed()) {
            return "Jest dziura -- ucieka na " + TrapPolice.where(reading.leak())
                    + ", licząc od " + TrapPolice.where(reading.measuredFrom()) + ".";
        }
        if (reading.exits() == 0) {
            return "Nie ma wyjazdu. Potrzebne drzwi albo brama na zewnątrz.";
        }
        if (reading.floor() < MIN_FLOOR) {
            return "Masz " + reading.floor() + " kratek podłogi. Wóz nie wyjedzie "
                    + "z czegoś mniejszego niż " + MIN_FLOOR + ".";
        }
        if (reading.dark() > 0) {
            return reading.dark() + (reading.dark() == 1 ? " ciemny kąt" : " ciemnych kątów")
                    + ". W nocy nikt tu nic nie znajdzie.";
        }
        if (!reading.storage()) {
            return "Nie ma gdzie trzymać sprzętu. Potrzebna skrzynia albo beczka.";
        }
        return null;
    }

    public static TrapHomes.Readout look(ServerWorld world, BlockPos pos) {
        return TrapHomes.look(world, pos, null);
    }

    /** Somebody stands the block up and clicks it. Null means it opened. */
    public static String found(ServerPlayerEntity who, ServerWorld world, BlockPos pos) {
        if (at(world, pos) != null) {
            return "To już jest remiza.";
        }
        TrapHomes.Readout reading = look(world, pos);
        String no = fault(reading);
        if (no != null) {
            return no;
        }
        Brigade shed = new Brigade(UUID.randomUUID(), who.getUuid(),
                who.getGameProfile().getName(),
                world.getRegistryKey().getValue().toString(), pos.toImmutable());
        shed.name = spare("Remiza " + who.getGameProfile().getName());
        shed.open = true;
        shed.floor = reading.floor();
        SHEDS.add(shed);
        save();
        return null;
    }

    /** Taken down: the engines go with it. */
    public static void lost(ServerWorld world, BlockPos pos) {
        Brigade shed = at(world, pos);
        if (shed == null) {
            return;
        }
        SHEDS.remove(shed);
        // Anything this shed was on its way to is nobody's problem again. The
        // fire does not go out -- it simply stops being answered, which is the
        // honest consequence of knocking the garage down mid-run.
        for (Fire fire : BURNING) {
            if (shed.id.equals(fire.brigade)) {
                fire.brigade = null;
                fire.answered = 0;
            }
        }
        save();
    }

    private static String spare(String wanted) {
        String name = wanted;
        for (int n = 2; taken(name); n++) {
            name = wanted + " " + n;
        }
        return name;
    }

    private static boolean taken(String name) {
        for (Brigade shed : SHEDS) {
            if (name.equals(shed.name)) {
                return true;
            }
        }
        return false;
    }

    // --- something catches ----------------------------------------------------

    /**
     * One roll over everything that could go up.
     *
     * Only ever a building in a LOADED chunk, which is to say near somebody.
     * That is the same rule the casino floor is held to and it is here for a
     * blunter reason: a fire nobody can see is a fire nobody can put out, so
     * burning down an empty quarter of the map while everyone is asleep would
     * be a dice roll with no play in it.
     */
    private static void maybeStart(MinecraftServer server) {
        if (!TrapCity.founded()) {
            return;
        }
        List<Fire> candidates = new ArrayList<>();
        long now = server.getOverworld().getTime();
        for (TrapHomes.Home home : TrapHomes.all()) {
            ServerWorld world = worldOf(server, home.dimension());
            if (home.tenant() == null || world == null || !loaded(world, home.anchor())) {
                continue;
            }
            candidates.add(new Fire(home.dimension(), home.anchor(), home.id(),
                    home.name(), now + BURNS_FOR));
        }
        for (TrapShops.Shop shop : TrapShops.shops()) {
            ServerWorld world = worldOf(server, shop.dimension);
            if (world == null || !loaded(world, shop.pos())) {
                continue;
            }
            candidates.add(new Fire(shop.dimension, shop.pos(), null,
                    shop.name(), now + BURNS_FOR));
        }
        if (candidates.isEmpty()) {
            return;
        }
        var random = server.getOverworld().getRandom();
        for (Fire fire : candidates) {
            if (random.nextFloat() >= ODDS || alreadyAlight(fire)) {
                continue;
            }
            start(server, fire);
            return;   // one at a time. A town on fire twice over is a bug, not a night.
        }
    }

    private static boolean alreadyAlight(Fire wanted) {
        for (Fire fire : BURNING) {
            if (fire.pos.equals(wanted.pos) && fire.dimension.equals(wanted.dimension)) {
                return true;
            }
        }
        return false;
    }

    private static void start(MinecraftServer server, Fire fire) {
        BURNING.add(fire);
        ServerWorld world = worldOf(server, fire.dimension);
        if (world != null) {
            world.playSound(null, fire.pos, SoundEvents.ENTITY_BLAZE_SHOOT,
                    SoundCategory.BLOCKS, 1.2F, 0.6F);
        }
        dispatch(server, fire);
        TrapWaypoints.offer(nearestPlayer(server, fire), "Pożar", fire.pos, TrapWaypoints.RED);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(TrapNotes.headline("PALI SIĘ  ", Formatting.RED)
                    .append(TrapNotes.say(fire.what + " na " + TrapPolice.where(fire.pos),
                            Formatting.GRAY))
                    .append(TrapNotes.under(fire.brigade != null
                            ? "Wóz jedzie."
                            : "Nikt nie jedzie. Wiadro wody w ręce i stań obok.")), false);
        }
        TrapCraft.LOGGER.info("fire at {} ({}), answered={}", fire.pos, fire.what,
                fire.brigade != null);
    }

    /**
     * The nearest shed with an engine free, and the city's money to send it.
     *
     * Both halves fail the same way on purpose: an unanswered fire says so in
     * the same sentence whether the reason was distance, a full shed or an
     * empty treasury. From inside a burning house those are one fact -- nobody
     * is coming -- and the reasons are what {@code /fires} is for.
     */
    private static void dispatch(MinecraftServer server, Fire fire) {
        Brigade nearest = null;
        double best = Double.MAX_VALUE;
        for (Brigade shed : SHEDS) {
            if (!shed.open || !shed.dimension.equals(fire.dimension) || shed.free() <= 0) {
                continue;
            }
            double away = shed.sign.getSquaredDistance(fire.pos);
            if (away < best && away <= (double) REACH * REACH) {
                best = away;
                nearest = shed;
            }
        }
        if (nearest == null) {
            return;
        }
        // The city pays the turnout, and pays it UP FRONT. A run billed on
        // arrival could be refused halfway, which is a crew that turns round
        // in the street -- and TrapPayroll.spend fails closed for exactly the
        // reason this must: half a call-out is not a thing that can happen.
        if (!TrapCity.spend(CALLOUT)) {
            return;
        }
        TrapPayroll.credit(CALLOUT);
        nearest.out++;
        nearest.runs++;
        fire.brigade = nearest.id;
        fire.answered = server.getOverworld().getTime() + TURNOUT
                + (long) Math.sqrt(best) * TICKS_PER_BLOCK;
        save();
    }

    // --- and burns ------------------------------------------------------------

    private static void burn(MinecraftServer server) {
        long now = server.getOverworld().getTime();
        List<Fire> done = new ArrayList<>();
        for (Fire fire : BURNING) {
            ServerWorld world = worldOf(server, fire.dimension);
            if (world == null) {
                done.add(fire);
                continue;
            }
            flames(world, fire.pos);
            if (fire.answered > 0 && now >= fire.answered) {
                out(server, world, fire, "Wóz zdążył.");
                done.add(fire);
                continue;
            }
            if (doused(world, fire)) {
                out(server, world, fire, "Ugaszone ręcznie.");
                done.add(fire);
                continue;
            }
            if (now >= fire.by) {
                scorch(server, world, fire);
                done.add(fire);
            }
        }
        for (Fire fire : done) {
            BURNING.remove(fire);
            release(fire);
        }
    }

    /** An engine goes back in the shed whatever the outcome was. */
    private static void release(Fire fire) {
        if (fire.brigade == null) {
            return;
        }
        for (Brigade shed : SHEDS) {
            if (shed.id.equals(fire.brigade)) {
                shed.out = Math.max(0, shed.out - 1);
            }
        }
    }

    private static void flames(ServerWorld world, BlockPos pos) {
        world.spawnParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 1.4,
                pos.getZ() + 0.5, 14, 0.9, 1.1, 0.9, 0.02);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 2.4,
                pos.getZ() + 0.5, 10, 1.0, 0.8, 1.0, 0.01);
        world.playSound(null, pos, SoundEvents.BLOCK_FIRE_AMBIENT,
                SoundCategory.BLOCKS, 1.6F, 0.8F);
    }

    /**
     * Somebody standing close enough with water in their hands.
     *
     * A bucket rather than a button, and it is emptied. There is no new verb
     * to learn -- everybody already knows what a bucket of water is for near a
     * fire -- and paying for it with the water is what keeps this from being a
     * free answer that makes the building pointless. What the remiza actually
     * buys is not being there.
     */
    private static boolean doused(ServerWorld world, Fire fire) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.getBlockPos().isWithinDistance(fire.pos, BUCKET_RANGE)) {
                continue;
            }
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (!stack.isOf(Items.WATER_BUCKET)) {
                    continue;
                }
                player.getInventory().setStack(slot, new ItemStack(Items.BUCKET));
                TrapAwards.grant(player, "fire");
                return true;
            }
        }
        return false;
    }

    private static void out(MinecraftServer server, ServerWorld world, Fire fire, String how) {
        world.playSound(null, fire.pos, SoundEvents.BLOCK_FIRE_EXTINGUISH,
                SoundCategory.BLOCKS, 1.4F, 1.0F);
        world.spawnParticles(ParticleTypes.CLOUD, fire.pos.getX() + 0.5,
                fire.pos.getY() + 1.6, fire.pos.getZ() + 0.5, 30, 1.0, 0.8, 1.0, 0.02);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(TrapNotes.headline("UGASZONE  ", Formatting.GREEN)
                    .append(TrapNotes.say(fire.what + " -- " + how, Formatting.GRAY)), false);
        }
    }

    /**
     * Nobody came.
     *
     * Everything taken here is something this mod invented and can therefore
     * honestly take back. Not one block is broken -- see the class note for
     * why that is a rule rather than a shortcut.
     */
    private static void scorch(MinecraftServer server, ServerWorld world, Fire fire) {
        world.playSound(null, fire.pos, SoundEvents.ENTITY_GENERIC_EXTINGUISH_FIRE,
                SoundCategory.BLOCKS, 1.4F, 0.5F);
        String lost;
        if (fire.home != null) {
            TrapHomes.Home home = TrapHomes.byId(fire.home);
            if (home == null) {
                return;
            }
            TrapHomes.sicken(home, SCORCHED_MOOD);
            home.write("Paliło się. Nikt nie przyjechał.");
            // The same door a bite and a beating come through, which its own
            // note said the next cause should use rather than growing a second
            // casualty ward. Third caller, no new machinery.
            if (home.tenant() != null && !TrapHospitals.tenantAway(home)) {
                TrapHospitals.hurt(world, home, home.tenant(), "zaczadział w pożarze");
            }
            lost = home.name() + " jest osmalony i lokator ma dość.";
        } else {
            TrapShops.Shop shop = TrapShops.shopAt(world, fire.pos);
            if (shop == null) {
                return;
            }
            int burnt = Math.round(shop.till() * TILL_LOST);
            // Through robbed(), which is the door TrapCrime already takes
            // money out of a till by. Reaching into the field would be a
            // second place that has to remember to save the register.
            TrapShops.robbed(shop, burnt);
            // Straight out of the world. Money in a till that burns has not
            // gone to anybody, and reporting it as a transfer would leave the
            // index believing somebody is still holding it.
            TrapMarket.minted(-burnt);
            lost = shop.name() + " stracił " + burnt + "e z kasy.";
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(TrapNotes.headline("SPALONE  ", Formatting.DARK_RED)
                    .append(TrapNotes.say(lost, Formatting.GRAY))
                    .append(TrapNotes.under(SHEDS.isEmpty()
                            // Nobody has BUILT one -- which is a different
                            // sentence from "there are none in this mod", and
                            // the short version was being read as the latter.
                            ? "Nikt jeszcze nie zbudował remizy. Jak: /guide fires"
                            : "Żaden wóz nie zdążył.")), false);
        }
    }

    // --- the readout ----------------------------------------------------------

    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("fires")
                                .executes(context -> {
                                    ServerPlayerEntity who = context.getSource().getPlayer();
                                    if (who == null) {
                                        return 0;
                                    }
                                    report(who);
                                    return 1;
                                })));
    }

    /**
     * /fires -- what is alight, and why nothing is coming.
     *
     * The same reason {@code /visitors} and {@code /police} exist. A brigade
     * that is running perfectly and declining every call because the treasury
     * is empty is, from the street, identical to one that was never written.
     */
    public static void report(ServerPlayerEntity who) {
        Text head = TrapNotes.headline("Straż pożarna  ", Formatting.GOLD)
                .append(TrapNotes.say("remiz: " + SHEDS.size() + ", wozów: " + engines()
                        + ", pali się: " + BURNING.size(), Formatting.GRAY));
        who.sendMessage(head, false);
        for (Brigade shed : SHEDS) {
            who.sendMessage(TrapNotes.figure("  " + shed.name + "  ",
                            shed.free() + "/" + shed.engines() + " w garażu",
                            shed.free() > 0 ? Formatting.GREEN : Formatting.RED)
                    .append(TrapNotes.say("   wyjazdów: " + shed.runs + "   "
                            + TrapPolice.where(shed.sign), Formatting.DARK_GRAY)), false);
        }
        if (SHEDS.isEmpty()) {
            who.sendMessage(TrapNotes.say("  Nikt jeszcze nie zbudował remizy. Wszystko, "
                    + "co się zapali, gasicie sami wiadrem.", Formatting.RED), false);
            who.sendMessage(TrapNotes.under("Blok remizy: cegły, dzwon, 2 bloki miedzi, "
                    + "wiadro wody. Reszta: /guide fires"), false);
        }
        who.sendMessage(TrapNotes.say("  Wyjazd kosztuje miasto " + CALLOUT
                        + "e. Pusta kasa to wóz, który nie wyjeżdża.",
                TrapCity.treasury() >= CALLOUT ? Formatting.DARK_GRAY : Formatting.RED), false);
        for (Fire fire : BURNING) {
            who.sendMessage(TrapNotes.say("  pali się: " + fire.what + "  "
                            + TrapPolice.where(fire.pos)
                            + (fire.brigade == null ? "  -- nikt nie jedzie" : "  -- wóz w drodze"),
                    fire.brigade == null ? Formatting.RED : Formatting.YELLOW), false);
        }
    }

    // --- odds and ends --------------------------------------------------------

    private static ServerPlayerEntity nearestPlayer(MinecraftServer server, Fire fire) {
        ServerPlayerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            double away = player.getBlockPos().getSquaredDistance(fire.pos);
            if (away < best) {
                best = away;
                nearest = player;
            }
        }
        return nearest;
    }

    private static boolean loaded(ServerWorld world, BlockPos pos) {
        return world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
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
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-fires.txt");
        SHEDS.clear();
        BURNING.clear();
        if (!Files.exists(saveFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.split(" ", 10);
                if (parts.length < 10 || !parts[0].equals("shed")) {
                    continue;
                }
                Brigade shed = new Brigade(UUID.fromString(parts[1]),
                        UUID.fromString(parts[2]), parts[3], parts[4],
                        new BlockPos(Integer.parseInt(parts[5]), Integer.parseInt(parts[6]),
                                Integer.parseInt(parts[7])));
                String[] figures = parts[8].split(",");
                shed.floor = Integer.parseInt(figures[0]);
                shed.open = figures.length > 1 && figures[1].equals("1");
                shed.runs = figures.length > 2 ? Integer.parseInt(figures[2]) : 0;
                shed.name = parts[9];
                SHEDS.add(shed);
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the fire stations: {}", failure.toString());
        }
    }

    static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Brigade shed : SHEDS) {
                // The name is the tail of a limited split, so it may contain
                // spaces and nothing may ever be appended after it. Same trap
                // TrapClubs and TrapHomes both carry a note about.
                out.append("shed ").append(shed.id).append(' ').append(shed.owner)
                        .append(' ').append(shed.ownerName).append(' ')
                        .append(shed.dimension).append(' ')
                        .append(shed.sign.getX()).append(' ').append(shed.sign.getY())
                        .append(' ').append(shed.sign.getZ()).append(' ')
                        .append(shed.floor).append(',').append(shed.open ? 1 : 0)
                        .append(',').append(shed.runs)
                        .append(' ').append(shed.name.replace('\n', ' ')).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the fire stations: {}", failure.toString());
        }
    }
}
