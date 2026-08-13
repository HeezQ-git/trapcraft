package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
import net.minecraft.util.math.random.Random;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supermarkets: a till, its shelves, and the town that shops at them.
 *
 * The first version was a shelf over a barrel and nothing else, which is a
 * corner shop and does not become a supermarket by being repeated -- twelve
 * shelves meant twelve barrels to keep stocked and twelve tills to empty, and
 * the building had no existence of its own at all.
 *
 * Now a {@link ShopTillBlock} IS the shop. Shelves within {@link #REACH} of it
 * belong to it, so a room full of them is one business with one name, one
 * price policy and one cash register. Stock is every container under the till
 * and under any of its shelves, so you may keep it all in a back room or in
 * the counters themselves and both work.
 *
 * <h2>Why nothing is attached by hand</h2>
 *
 * Because an attachment is a thing that can be wrong. A shelf belongs to the
 * nearest till, full stop -- put one down and it joins the shop, break the
 * till and they all go quiet. The alternative was a wand, a click mode and a
 * saved list of positions that could disagree with the world.
 */
public final class TrapShops {

    /** Marks our shoppers, so they are never confused with a real trader. */
    public static final String TAG = "trapcraft_shopper";

    /** How far a shelf may stand from its till. */
    public static final int REACH = 24;
    /** What a villager pays for ordinary goods, against the market price. */
    public static final float RETAIL = 0.90f;
    /**
     * What a villager pays for weed and coca ACROSS A COUNTER, against the
     * street price.
     *
     * The whole trade-off in one number. Sold over a counter it is clean money,
     * declared, taxed, and nobody carries heat for it. Sold on the street it is
     * dirty, untaxed and worth half as much again. The safest money is the
     * slowest; the fastest is still the one you have to wash.
     */
    public static final float LEGAL_RATE = 0.65f;

    /** What the owner may set their prices to, against the standard rate. */
    public static final int[] MARKUP = {75, 90, 100, 115, 135};
    public static final String[] MARKUP_NAME = {
            "Bardzo tanio", "Tanio", "Cena rynkowa", "Drogo", "Zdzierstwo"};

    private static final int CHECK_INTERVAL = 20 * 20;
    private static final float PULL = 0.06f;
    private static final int MAX_SHOPPERS = 6;
    /**
     * How far a shop will call somebody in from.
     *
     * The same reach the casino uses. A town is not a street: houses go up
     * where there is room and a shop goes up where its owner wanted it, and
     * past this the question answers itself -- a villager in an unloaded chunk
     * is not ticking and cannot be found by any search.
     */
    private static final int REACH_OUT = 512;
    /**
     * The longest trip worth asking a villager to walk.
     *
     * A pathfinder gives out somewhere past forty blocks and the Brain
     * reclaims the walk target between plans, so anything further is a walk
     * target that quietly does nothing while somebody stands in the road.
     * Past it they are stood at the shop door and walk the last of it in.
     */
    private static final int WALKABLE = 40;
    private static final int PATIENCE = 20 * 20;
    private static final int COUNTER = 3;
    private static final int LEAVE_TICKS = 20 * 8;
    /**
     * How long somebody is at work, in world ticks.
     *
     * A quarter of a day, and it is a span rather than a moment because the
     * body is gone for the whole of it -- see {@link TrapHomes#goToWork}. The
     * exact number matters less than it looks: they come back on the housing
     * register's next pass over their house, so a big town is a few seconds
     * over rather than to the tick.
     */
    private static final int SHIFT_TICKS = 20 * 60 * 5;

    /** One business: a till, a name, a price policy and a cash register. */
    public static final class Shop {
        final String dimension;
        final BlockPos pos;
        final UUID owner;
        String ownerName;
        String name;
        int markup = 2;
        int till;
        int sold;
        int turnover;
        /**
         * Is somebody behind the counter?
         *
         * Saved in the comma-packed money field rather than as a new
         * space-separated one, because the shop's NAME is the unsplit tail of
         * the line -- the same trap that once took the whole housing register
         * down. The commas are the only place this format can grow.
         */
        boolean staffed;
        /**
         * Bitten, and not coming back until somebody cures them.
         *
         * Never written down. The zombie standing at the counter IS the
         * record -- it is a persistent entity wearing the keeper's tag, so a
         * restart rediscovers the situation in one entity lookup, and a flag
         * on disk could only ever disagree with it.
         */
        transient boolean sick;
        /**
         * The day they are due back off sick, or -1.
         *
         * Written down, unlike {@link #sick}. Once a bitten keeper has been
         * carried off to a ward there is nothing at the counter to look at any
         * more -- the evidence walked away -- so the ONLY thing that knows
         * they are still off is this number. A transient one would have every
         * restart decide the shop was simply unstaffed and hire over the top
         * of somebody who is lying in a hospital.
         */
        long backOn = -1;
        /** Said so once. Not every ten seconds. */
        transient boolean toldSick;
        /** The last in-game day the keeper was paid, so a restart can't double it. */
        long lastPaid = -1;

        Shop(String dimension, BlockPos pos, UUID owner, String ownerName, String name) {
            this.dimension = dimension;
            this.pos = pos;
            this.owner = owner;
            this.ownerName = ownerName;
            this.name = name;
        }

        public BlockPos pos() {
            return pos;
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

        public int till() {
            return till;
        }

        public int sold() {
            return sold;
        }

        public int turnover() {
            return turnover;
        }

        public boolean staffed() {
            return staffed;
        }

        public int markup() {
            return MARKUP[Math.max(0, Math.min(markup, MARKUP.length - 1))];
        }

        public String markupName() {
            return MARKUP_NAME[Math.max(0, Math.min(markup, MARKUP.length - 1))];
        }

        void nextMarkup() {
            markup = (markup + 1) % MARKUP.length;
        }
    }

    /** One counter people queue at. It belongs to whichever till is nearest. */
    public static final class Shelf {
        final String dimension;
        final BlockPos pos;

        Shelf(String dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }

        public BlockPos pos() {
            return pos;
        }
    }

    /** Something a shelf will sell, however it got there. */
    public record Line(ItemStack sample, int count, int price, String label,
                       TrapCity.Duty duty) {
    }

    /**
     * What this one came out for.
     *
     * A town where every person you ever see is walking at a till is a town
     * that exists to shop. Work trips cost nothing -- payday is aggregate and
     * already ran, off the housing register, whether or not a single chunk was
     * loaded -- so these are a SAMPLE of the economy rather than the economy
     * itself. Nothing depends on one of them arriving.
     */
    private enum Trip { SHOP, WORK }

    private record Shopper(BlockPos target, String dimension, int bornAt, Trip trip) {
    }

    private static final List<Shop> SHOPS = new ArrayList<>();
    private static final List<Shelf> SHELVES = new ArrayList<>();
    private static final Map<UUID, Shopper> SHOPPERS = new HashMap<>();
    private static final Map<UUID, Integer> LEAVING = new HashMap<>();
    private static Path saveFile;

    private TrapShops() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapShops::load);
        registerCommand();
        // Keepers standing at counters that are not there any more.
        //
        // The backstop for every shop that was closed before the register
        // learnt to sweep by tag, and for anybody left behind by a till that
        // was carried off. Guarded on the register being loaded at all: an
        // empty SHOPS means the file has not been read yet, and sweeping then
        // would bin every shopkeeper on the server.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(
                (entity, world) -> {
                    if (SHOPS.isEmpty()
                            || !entity.getCommandTags().contains(KEEPER_TAG)
                            || !(entity instanceof VillagerEntity
                            || entity instanceof net.minecraft.entity.mob.ZombieVillagerEntity)) {
                        return;
                    }
                    for (Shop shop : SHOPS) {
                        if (shop.dimension.equals(world.getRegistryKey().getValue().toString())
                                && entity.getBlockPos().isWithinDistance(shop.pos, SICK_REACH)) {
                            return;   // somebody's, and still employed
                        }
                    }
                    entity.discard();
                });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int now = server.getTicks();
            if (!SHOPPERS.isEmpty() || !LEAVING.isEmpty()) {
                shepherd(server, now);
            }
            if (now % CHECK_INTERVAL == 0) {
                maybeVisit(server);
            }
            if (now % 200 == 0) {
                keepers(server);
            }
        });
    }

    // --- the register ---------------------------------------------------------

    public static List<Shop> shops() {
        return SHOPS;
    }

    public static List<Shelf> all() {
        return SHELVES;
    }

    public static Shop shopAt(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Shop shop : SHOPS) {
            if (shop.pos.equals(pos) && shop.dimension.equals(dimension)) {
                return shop;
            }
        }
        return null;
    }

    public static Shelf at(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Shelf shelf : SHELVES) {
            if (shelf.pos.equals(pos) && shelf.dimension.equals(dimension)) {
                return shelf;
            }
        }
        return null;
    }

    /** The till this shelf answers to: nearest within reach, or nobody. */
    public static Shop ownerOf(Shelf shelf) {
        Shop best = null;
        double closest = (double) REACH * REACH;
        for (Shop shop : SHOPS) {
            if (!shop.dimension.equals(shelf.dimension)) {
                continue;
            }
            double away = shop.pos.getSquaredDistance(shelf.pos);
            if (away <= closest) {
                closest = away;
                best = shop;
            }
        }
        return best;
    }

    public static List<Shelf> shelvesOf(Shop shop) {
        List<Shelf> mine = new ArrayList<>();
        for (Shelf shelf : SHELVES) {
            if (ownerOf(shelf) == shop) {
                mine.add(shelf);
            }
        }
        return mine;
    }

    public static int tillsHeld() {
        int total = 0;
        for (Shop shop : SHOPS) {
            total += shop.till;
        }
        return total;
    }

    // --- putting one up -------------------------------------------------------

    public static void open(ServerWorld world, BlockPos pos, ServerPlayerEntity owner) {
        if (shopAt(world, pos) != null) {
            return;
        }
        SHOPS.add(new Shop(world.getRegistryKey().getValue().toString(), pos.toImmutable(),
                owner.getUuid(), owner.getGameProfile().getName(),
                "Sklep " + owner.getGameProfile().getName()));
        save();
        owner.sendMessage(Text.literal("Sklep otwarty. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("Postaw półki w promieniu " + REACH + " bloków, a same "
                        + "się podłączą. Otwórz półkę i włóż towar.")
                        .formatted(Formatting.GRAY)), false);
        if (!TrapCity.founded()) {
            owner.sendMessage(Text.literal("Nie ma jeszcze miasta, więc nie ma komu "
                    + "tu kupować.").formatted(Formatting.DARK_GRAY), false);
        }
    }

    public static void closeShop(ServerWorld world, BlockPos pos) {
        Shop shop = shopAt(world, pos);
        if (shop == null) {
            return;
        }
        spill(world, pos, shop.till);
        sendHome(world.getServer(), shop);
        SHOPS.remove(shop);
        save();
    }

    public static void claim(ServerWorld world, BlockPos pos, ServerPlayerEntity owner) {
        if (at(world, pos) != null) {
            return;
        }
        Shelf shelf = new Shelf(world.getRegistryKey().getValue().toString(), pos.toImmutable());
        SHELVES.add(shelf);
        save();
        Shop shop = ownerOf(shelf);
        owner.sendMessage(shop == null
                ? Text.literal("Półka bez sklepu. ").formatted(Formatting.YELLOW)
                        .append(Text.literal("Postaw kasę sklepową w promieniu " + REACH
                                + " bloków, a się podłączy.").formatted(Formatting.GRAY))
                : Text.literal("Podłączono do ").formatted(Formatting.GREEN)
                        .append(Text.literal(shop.name).formatted(Formatting.GOLD))
                        .append(Text.literal(". Kliknij PPM, żeby ją zapełnić.")
                                .formatted(Formatting.GRAY)), false);
    }

    public static void release(ServerWorld world, BlockPos pos) {
        Shelf shelf = at(world, pos);
        if (shelf != null) {
            SHELVES.remove(shelf);
            save();
        }
    }

    private static void spill(ServerWorld world, BlockPos pos, int money) {
        if (money <= 0) {
            return;
        }
        int[] packed = TrapMath.packEmeralds(money);
        for (int i = 0; i < packed[0]; i++) {
            net.minecraft.block.Block.dropStack(world, pos,
                    new ItemStack(net.minecraft.item.Items.EMERALD_BLOCK));
        }
        if (packed[1] > 0) {
            net.minecraft.block.Block.dropStack(world, pos,
                    new ItemStack(net.minecraft.item.Items.EMERALD, packed[1]));
        }
    }

    /**
     * Whatever the anvil called it.
     *
     * MailboxItem's rule, for MailboxItem's reason: an anvil is the only text
     * entry this mod has, and a directory of "HeezQ's shop 2", "HeezQ's shop
     * 3" is a directory nobody can read. Blank names are ignored rather than
     * stored -- an unnamed item should not be able to wipe a sign.
     */
    public static void rename(Shop shop, String name) {
        String trimmed = name == null ? "" : name.replace('\n', ' ').trim();
        if (trimmed.isBlank() || trimmed.equals(shop.name)) {
            return;
        }
        shop.name = trimmed;
        save();
    }

    // --- somebody behind the counter ------------------------------------------

    /** What a shopkeeper takes out of the till a day. */
    public static final int KEEPER_WAGE = 45;
    /** What having one does to the custom a shop draws. */
    public static final float KEEPER_PULL = 1.5f;

    /**
     * Our own chunk ticket, deliberately not equal to anybody else's.
     *
     * {@link net.minecraft.server.world.ChunkTicketType} is a record, so two
     * are the same ticket when their three fields match -- registered or not.
     * TrapCrew's is 20*20 and vanilla's PORTAL is 300 and persists; 20*25 is
     * nobody's, which keeps a shop's ticket from silently merging into a
     * hand's or turning up in /forceload.
     */
    private static final net.minecraft.server.world.ChunkTicketType KEEPER_TICKET =
            new net.minecraft.server.world.ChunkTicketType(20 * 25, false,
                    net.minecraft.server.world.ChunkTicketType.Use.LOADING_AND_SIMULATION);

    /** Shopkeepers we have out, by the till they stand at. Memory only. */
    private static final Map<BlockPos, UUID> KEEPERS = new HashMap<>();

    /**
     * Take somebody on, or let them go.
     *
     * @return what to tell the owner
     */
    public static String staff(ServerPlayerEntity owner, Shop shop) {
        shop.staffed = !shop.staffed;
        if (!shop.staffed) {
            shop.lastPaid = -1;
            sendHome(owner.getServer(), shop);
            save();
            return "Poszedł do domu. Lada znowu jest twoja.";
        }
        // Their first day starts NOW. Without this line lastPaid is whatever it
        // was -- the day they last walked out, or -1 -- so the wage round ten
        // seconds later reads "a day is owed", takes 45e off a till that has
        // not earned any yet, and walks them straight back out through the
        // `continue` above stand(). Which meant that on a fresh shop, hiring
        // somebody spawned nobody at all, ever: the only sign of it was two
        // chat lines ten seconds apart.
        shop.lastPaid = TrapMarket.today(owner.getServer());
        // And they are stood up here rather than on the next wage round, so
        // there is somebody behind the counter by the time the screen closes.
        ServerWorld world = worldOf(owner.getServer(), shop.dimension);
        if (world != null) {
            stand(world, shop);
        }
        save();
        return "Zatrudniony. " + KEEPER_WAGE + "e dziennie z twojej kieszeni, a sklep "
                + "handluje, kiedy jesteś gdziekolwiek na serwerze."
                + (TrapMarket.wealthOf(owner) < KEEPER_WAGE
                ? "  Dziś za darmo -- ale masz przy sobie " + TrapMarket.wealthOf(owner)
                + "e i jutro odejdzie, jeśli nie zapłacisz " + KEEPER_WAGE
                + "e." : "");
    }

    /**
     * Pay the keepers, and hold their shops awake.
     *
     * The awake half deliberately copies the crew's rule -- only while the
     * OWNER is logged in. A shop that held its chunk open forever would be a
     * chunk loader you buy for 45e a day, and a server of them is somebody
     * else's tick budget.
     */
    private static void keepers(MinecraftServer server) {
        long day = TrapMarket.today(server);
        for (Shop shop : new ArrayList<>(SHOPS)) {
            if (!shop.staffed) {
                continue;
            }
            ServerPlayerEntity boss = server.getPlayerManager().getPlayer(shop.owner);
            if (boss == null) {
                // Nobody minding it, nobody being paid. The shop only trades
                // while its owner is on the server, so the day is not banked
                // either -- charging for a shift that nobody worked would be
                // the one thing worse than not charging at all.
                sendHome(server, shop);
                continue;
            }
            ServerWorld world = worldOf(server, shop.dimension);
            if (world == null) {
                continue;
            }
            // Nothing there any more.
            //
            // onStateReplaced closes a shop when a player breaks the till, and
            // that is the only way it has ever been closed -- so a till taken
            // by a piston (which is explicitly skipped as `moved`), an
            // explosion, or any other mod left the register pointing at an
            // address with no counter at it, quietly re-hiring a shopkeeper
            // there every ten seconds forever. Asking the world what is
            // actually at the spot costs one blockstate lookup a shop and
            // cannot be got wrong the next time somebody invents a way to
            // move a block. TrapFloor.beat learnt exactly this about wires.
            if (loaded(world, shop.pos)
                    && !(world.getBlockState(shop.pos).getBlock() instanceof ShopTillBlock)) {
                closeShop(world, shop.pos);
                continue;
            }
            // Re-adding the same ticket pushes its expiry out rather than
            // stacking another, so this is a hash lookup a shop every ten
            // seconds.
            world.getChunkManager().addTicket(KEEPER_TICKET,
                    new net.minecraft.util.math.ChunkPos(shop.pos), 2);
            // BEFORE the wage, because this is what decides whether there is
            // anybody to pay. A keeper who was bitten last night is on the
            // books and off the payroll until they are cured.
            stand(world, shop);
            if (shop.sick) {
                continue;
            }
            if (shop.lastPaid != day) {
                shop.lastPaid = day;
                // Out of the owner's own pocket, not the till.
                //
                // A wage off the till meant a shop had to be turning a profit
                // before it could afford the person whose whole job is to make
                // it turn one, so a new shop could never hire anybody: you
                // empty the till the moment you open it, and the keeper walks
                // out the same day. A wage is something the boss pays.
                if (TrapMarket.wealthOf(boss) < KEEPER_WAGE) {
                    shop.staffed = false;
                    sendHome(server, shop);
                    boss.sendMessage(Text.literal("Twój sprzedawca odszedł ze sklepu ")
                            .formatted(Formatting.RED)
                            .append(Text.literal(shop.name).formatted(Formatting.GOLD))
                            .append(Text.literal(" -- nie zapłaciłeś mu "
                                    + KEEPER_WAGE + "e.").formatted(Formatting.GRAY)), false);
                    save();
                    continue;
                }
                TrapMarket.take(boss, KEEPER_WAGE);
                TrapLedger.record(boss, TrapLedger.Source.STALL, -KEEPER_WAGE);
                boss.sendMessage(Text.literal("Pensja sprzedawcy: ")
                        .formatted(Formatting.DARK_GRAY)
                        .append(Text.literal("-" + KEEPER_WAGE + "e")
                                .formatted(Formatting.RED))
                        .append(Text.literal("  " + shop.name)
                                .formatted(Formatting.DARK_GRAY)), true);
                save();
            }
        }
    }

    /** Put the keeper back on their feet if they've wandered off or unloaded. */
    private static void stand(ServerWorld world, Shop shop) {
        BlockPos till = shop.pos;
        long day = TrapMarket.today(world.getServer());
        // Off sick, on the books, and somewhere else entirely.
        if (shop.backOn >= 0) {
            if (day < shop.backOn) {
                shop.sick = true;
                return;
            }
            // Discharged. The body that went in is not the person who comes
            // out -- a zombie cannot be un-bitten without a golden apple, and
            // the whole point of this was that it should not need one -- so
            // the one lying in the ward is cleared and the shop takes its own
            // keeper back on below, under the name it always uses for that
            // till. Same person, as far as anybody in the town can tell.
            discharge(world, shop);
            shop.backOn = -1;
            shop.sick = false;
            shop.toldSick = false;
            save();
        }
        // Off sick before anything else.
        //
        // A villager that gets bitten stops being a VillagerEntity and becomes
        // a ZombieVillagerEntity -- a NEW entity, with a new id, wearing the
        // same name and every tag. So the id in KEEPERS goes stale, the lookup
        // below finds nobody, and this method does the one thing it knows how
        // to do: hire another one. Who has the same night ahead of them. That
        // is how a counter grows a crowd of zombies, and TrapHomes learnt it
        // the same way with ninety-eight of them round one village.
        //
        // They are not replaced and not binned. Somebody who has been bitten
        // is somebody you can cure -- weakness and a golden apple -- and when
        // they turn back they are a VillagerEntity wearing the keeper's tag
        // again, which the adoption below picks straight back up. Their job is
        // held open; they simply are not paid for the days they are a zombie.
        for (var turned : world.getEntitiesByClass(
                net.minecraft.entity.mob.ZombieVillagerEntity.class,
                new net.minecraft.util.math.Box(till).expand(SICK_REACH),
                found -> found.isAlive()
                        && found.getCommandTags().contains(KEEPER_TAG))) {
            shop.sick = true;
            KEEPERS.remove(shop.pos);
            // Off to a ward, under their own steam, because a shopkeeper who
            // can only be saved by a player standing over them with a splash
            // potion is a shopkeeper who stays a zombie. The city has a
            // hospital for exactly this and it is somebody's job to run it.
            TrapHospitals.Ward ward = TrapHospitals.takeIn(world, turned);
            shop.backOn = ward == null ? -1 : day + TrapHospitals.STAY_DAYS;
            ServerPlayerEntity boss = world.getServer()
                    .getPlayerManager().getPlayer(shop.owner);
            if (!shop.toldSick && boss != null) {
                boss.sendMessage(Text.literal(turned.getCustomName() == null
                                ? "Twój sprzedawca" : turned.getCustomName().getString())
                        .formatted(Formatting.GOLD)
                        .append(ward == null
                                ? Text.literal(" został ugryziony, a w mieście nie ma "
                                        + "szpitala. Nie pracuje i nie zarabia, "
                                        + "dopóki go ktoś nie wyleczy.")
                                        .formatted(Formatting.RED)
                                : Text.literal(" został ugryziony. Trafił do " + ward.name()
                                        + " -- wróci za ladę za "
                                        + TrapHospitals.STAY_DAYS + " dzień i do tego "
                                        + "czasu nic nie zarabia.")
                                        .formatted(Formatting.GRAY)), false);
            }
            shop.toldSick = true;
            save();
            return;
        }
        shop.sick = false;
        shop.toldSick = false;
        UUID known = KEEPERS.get(shop.pos);
        if (known != null && world.getEntity(known) instanceof VillagerEntity alive
                && alive.isAlive()) {
            return;
        }
        // Somebody is already stood here that this session has never heard of.
        //
        // KEEPERS is memory only and a keeper is persistent, so every restart
        // of a staffed shop arrived with the old keeper still at the counter
        // and an empty map saying there was nobody -- and this method's whole
        // job is to fix "there is nobody" by spawning one. A shop left staffed
        // would therefore grow a shopkeeper per restart, forever, which is the
        // same crowd-of-phantoms the floor and the shelves have each had.
        // Adopted rather than replaced, and any extras that already piled up
        // are cleared on the way past.
        VillagerEntity standing = null;
        for (var found : world.getEntitiesByClass(VillagerEntity.class,
                new net.minecraft.util.math.Box(till).expand(6),
                other -> other.isAlive() && other.getCommandTags().contains(KEEPER_TAG))) {
            if (standing == null) {
                standing = found;
            } else {
                found.discard();
            }
        }
        if (standing != null) {
            KEEPERS.put(till.toImmutable(), standing.getUuid());
            return;
        }
        BlockPos spot = counterSide(world, shop.pos);
        if (spot == null) {
            return;
        }
        VillagerEntity keeper = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (keeper == null) {
            return;
        }
        // Facing the counter they are stood at. Their AI is off so they will
        // never turn on their own, and a shopkeeper pointed at a random
        // compass direction reads as somebody who wandered in.
        keeper.refreshPositionAndAngles(spot, (float) Math.toDegrees(Math.atan2(
                till.getZ() - spot.getZ(), till.getX() - spot.getX())) - 90f, 0f);
        // Stable per till, so the person behind your counter is the same
        // person after every reload rather than a new hire every morning.
        keeper.setCustomName(Text.literal(TrapHomes.nameFor(shop.pos.hashCode())
                + "  ·  " + shop.name).formatted(Formatting.AQUA));
        keeper.setCustomNameVisible(true);
        keeper.addCommandTag(KEEPER_TAG);
        keeper.setAiDisabled(true);
        keeper.setPersistent();
        world.spawnEntity(keeper);
        KEEPERS.put(shop.pos.toImmutable(), keeper.getUuid());
    }

    /**
     * Clear the keeper who has been lying in a ward.
     *
     * ponytail: the nearest tagged zombie to any ward in this world, not
     * "the one this shop sent". A shop has no id to stamp on them and two
     * bitten shopkeepers are interchangeable anyway -- each till hires back
     * under its own stable name, so nobody can tell which body was whose.
     */
    private static void discharge(ServerWorld world, Shop shop) {
        for (BlockPos sign : TrapHospitals.wards(shop.dimension)) {
            for (var lying : world.getEntitiesByClass(
                    net.minecraft.entity.mob.ZombieVillagerEntity.class,
                    new net.minecraft.util.math.Box(sign).expand(SICK_REACH),
                    found -> found.getCommandTags().contains(KEEPER_TAG))) {
                lying.discard();
                return;
            }
        }
    }

    /**
     * Somewhere to stand at the counter, beside it rather than on it.
     *
     * The search used to start at {@code till.up()}, which is a perfectly good
     * standing spot -- the till has a collision box, so its own lid counts as
     * a floor. The result was a shopkeeper stood on top of the counter looking
     * down at the customers. Beside it first, in a ring, and only if the whole
     * ring is blocked does it fall back to anywhere at all: a keeper on the
     * counter is silly, and a keeper who never appears because the shop is
     * cramped is worse.
     */
    private static BlockPos counterSide(ServerWorld world, BlockPos till) {
        int[][] ring = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
        };
        for (int[] offset : ring) {
            for (int drop = 0; drop <= 1; drop++) {
                BlockPos spot = till.add(offset[0], -drop, offset[1]);
                if (TrapSpawn.safe(world, spot)) {
                    return spot;
                }
            }
        }
        return TrapSpawn.near(world, till.up());
    }

    /** Marks a shopkeeper, so nothing else mistakes one for a wandering local. */
    public static final String KEEPER_TAG = "trapcraft_keeper";

    private static void sendHome(MinecraftServer server, Shop shop) {
        KEEPERS.remove(shop.pos);
        shop.sick = false;
        shop.toldSick = false;
        if (server == null) {
            return;
        }
        // By the TAG at the counter, not by the id we remembered.
        //
        // The remembered id is wrong in both the cases that matter. A keeper
        // who was bitten is a different entity now, so the old id finds
        // nothing and the zombie is left standing at a counter that no longer
        // exists; and a till that was taken up and put down somewhere else
        // leaves its old keeper behind with nothing that will ever look for
        // them again. Either way the bodies pile up at addresses with no shop
        // at them, which is what "they spawn all the time even though the till
        // moved" actually is -- not new ones spawning, old ones never leaving.
        ServerWorld world = worldOf(server, shop.dimension);
        if (world == null) {
            return;
        }
        var counter = new net.minecraft.util.math.Box(shop.pos).expand(SICK_REACH);
        for (var keeper : world.getEntitiesByClass(VillagerEntity.class, counter,
                found -> found.getCommandTags().contains(KEEPER_TAG))) {
            keeper.discard();
        }
        for (var turned : world.getEntitiesByClass(
                net.minecraft.entity.mob.ZombieVillagerEntity.class, counter,
                found -> found.getCommandTags().contains(KEEPER_TAG))) {
            turned.discard();
        }
    }

    /**
     * How far from the counter a keeper still counts as theirs.
     *
     * Wide, because a bitten one walks: the point is to find them at all, and
     * finding somebody else's keeper is impossible -- two tills this close
     * would be one shop.
     */
    private static final int SICK_REACH = 16;

    /** Cycle the price policy and write it down. */
    public static void repricePrices(Shop shop) {
        shop.nextMarkup();
        save();
    }

    /** Empty the register. */
    public static int collect(ServerPlayerEntity owner, Shop shop) {
        int takings = shop.till;
        if (takings <= 0) {
            return 0;
        }
        shop.till = 0;
        TrapMarket.handOver(owner, takings);
        TrapLedger.record(owner, TrapLedger.Source.STALL, takings);
        save();
        return takings;
    }

    // --- what is on the shelves -----------------------------------------------

    /**
     * Every container this shop can sell out of.
     *
     * The shelves themselves first -- a shelf holds its own stock, which is
     * the whole point of a shelf. Then under the till and under every shelf,
     * because a supermarket keeps a back room and telling somebody they may
     * not have one would be the mod deciding what their shop looks like.
     */
    public static List<Inventory> stockOf(ServerWorld world, Shop shop) {
        List<Inventory> boxes = new ArrayList<>();
        if (!world.getRegistryKey().getValue().toString().equals(shop.dimension)) {
            return boxes;
        }
        // TrapBoxes, so a double chest is one 54-slot container rather than
        // the near half of it. A shop stocked out of one used to go quiet the
        // moment the goods sat past slot 27.
        Inventory under = TrapBoxes.at(world, shop.pos.down());
        if (under != null) {
            boxes.add(under);
        }
        for (Shelf shelf : shelvesOf(shop)) {
            // The shelf's own stock, straight off the block entity: it is
            // never half of a double chest and never a minecart, so there is
            // nothing for TrapBoxes to resolve and no entity lookup to pay for.
            if (world.getBlockEntity(shelf.pos) instanceof MarketShelfBlockEntity onIt) {
                boxes.add(onIt);
            }
            Inventory box = TrapBoxes.at(world, shelf.pos.down());
            if (box != null) {
                boxes.add(box);
            }
        }
        return boxes;
    }

    /**
     * What a stack is worth over this counter, or null if nobody would buy it.
     *
     * Two kinds of line. Anything the market has a price for sells at
     * {@link #RETAIL} of it. Weed, coca and what they turn into sell at
     * {@link #LEGAL_RATE} of the STREET price -- clean, declared and taxed,
     * which is worth less than the street and costs none of the trouble.
     */
    public static Line lineFor(MinecraftServer server, ItemStack stack, Shop shop) {
        if (stack.isEmpty()) {
            return null;
        }
        float rate = shop.markup() / 100f;
        ShopStock.Entry entry = ShopStock.matching(stack);
        if (entry != null) {
            int market = TrapMarket.buyPrice(server, entry);
            return new Line(entry.stack(), entry.count(),
                    Math.max(1, Math.round(market * RETAIL * rate)), entry.label(),
                    TrapCity.forGoods(entry.category()));
        }
        int street = TrapDealing.streetPrice(stack);
        if (street > 0 && contraband(stack.getItem())) {
            ItemStack one = stack.copy();
            one.setCount(1);
            return new Line(one, 1, Math.max(1, Math.round(street * LEGAL_RATE * rate)),
                    stack.getName().getString(), TrapCity.Duty.LUXURY);
        }
        return null;
    }

    /** Weed, coca, and everything they become. */
    private static boolean contraband(Item item) {
        if (TrapContent.strainOfDriedBud(item) != null
                || item == TrapContent.cocaPowder
                || item == TrapContent.heroin
                || item == TrapContent.blendJointItem) {
            return true;
        }
        for (Strain strain : Strain.values()) {
            if (TrapContent.joint(strain) == item) {
                return true;
            }
        }
        return false;
    }

    /** A line the shop could serve right now, weighted towards dinner. */
    private static Line wanted(MinecraftServer server, ServerWorld world, Shop shop,
                               Random random) {
        Map<String, Line> lines = new LinkedHashMap<>();
        Map<String, Integer> held = new LinkedHashMap<>();
        for (Inventory box : stockOf(world, shop)) {
            for (int slot = 0; slot < box.size(); slot++) {
                ItemStack stack = box.getStack(slot);
                Line line = lineFor(server, stack, shop);
                if (line == null) {
                    continue;
                }
                lines.putIfAbsent(line.label(), line);
                held.merge(line.label(), stack.getCount(), Integer::sum);
            }
        }
        List<Line> pool = new ArrayList<>();
        for (var row : lines.entrySet()) {
            Line line = row.getValue();
            if (held.getOrDefault(row.getKey(), 0) < line.count()) {
                continue;
            }
            // Dinner far more often than anything else, and contraband rarely
            // -- a town buys bread every day and a joint on a Friday.
            int weight = line.duty() == TrapCity.Duty.ESSENTIALS ? 5
                    : line.duty() == TrapCity.Duty.LUXURY ? 2 : 1;
            for (int i = 0; i < weight; i++) {
                pool.add(line);
            }
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    private static boolean take(ServerWorld world, Shop shop, Line line) {
        int owed = line.count();
        List<Inventory> boxes = stockOf(world, shop);
        int found = 0;
        for (Inventory box : boxes) {
            for (int slot = 0; slot < box.size(); slot++) {
                ItemStack stack = box.getStack(slot);
                if (ItemStack.areItemsAndComponentsEqual(stack, line.sample())
                        || stack.isOf(line.sample().getItem())) {
                    found += stack.getCount();
                }
            }
        }
        if (found < owed) {
            return false;
        }
        for (Inventory box : boxes) {
            for (int slot = 0; slot < box.size() && owed > 0; slot++) {
                ItemStack stack = box.getStack(slot);
                if (!stack.isOf(line.sample().getItem())) {
                    continue;
                }
                int taken = Math.min(owed, stack.getCount());
                stack.decrement(taken);
                owed -= taken;
            }
            box.markDirty();
        }
        return true;
    }

    /**
     * A player over the same counter a townsperson uses.
     *
     * The same price and the same duty, deliberately. A kiosk selling joints is
     * a licensed dispensary for players too, which is the entire point of the
     * legal rate: clean, declared, no heat, and worth less than the street.
     *
     * Nothing here touches {@link TrapPayroll}. A player's emeralds already
     * exist -- they are moved, not made -- and that asymmetry is the reason
     * the town needs a purse and a player does not.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String buy(ServerPlayerEntity buyer, Shop shop, Line line) {
        ServerWorld world = (ServerWorld) buyer.getWorld();
        if (buyer.getUuid().equals(shop.owner)) {
            return "To twój własny sklep.";
        }
        int duty = TrapCity.dutyOn(line.price(), line.duty());
        if (TrapMarket.wealthOf(buyer) < line.price() + duty) {
            return "To kosztuje " + (line.price() + duty) + "e, a tyle nie masz.";
        }
        if (!take(world, shop, line)) {
            return "Tego już nie ma na stanie.";
        }

        // collect, not take: every emerald here is moving, not leaving. The
        // price goes to the owner's register and the duty to the vault, and
        // reporting either as destroyed would have the index feel a shock
        // where nothing happened.
        TrapMarket.collect(buyer, line.price());
        shop.till += line.price();
        shop.sold++;
        shop.turnover += line.price();
        TrapCity.charge(buyer, line.price(), line.duty());
        // The buyer's side only. The owner is credited when they empty the
        // till, not now -- booking both here would count the sale twice.
        TrapLedger.record(buyer, TrapLedger.Source.STALL, -(line.price() + duty));
        save();

        ItemStack bought = line.sample().copy();
        bought.setCount(line.count());
        buyer.getInventory().offerOrDrop(bought);
        world.playSound(null, shop.pos, SoundEvents.BLOCK_BARREL_CLOSE,
                SoundCategory.BLOCKS, 0.7F, 1.3F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, buyer.getX(),
                buyer.getY() + 1.0, buyer.getZ(), 6, 0.3, 0.2, 0.3, 0.01);

        ServerPlayerEntity owner = buyer.getServer().getPlayerManager().getPlayer(shop.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal("Sprzedano ").formatted(Formatting.GREEN)
                    .append(Text.literal(line.count() + "x " + line.label())
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(" dla " + buyer.getGameProfile().getName()
                            + " -- " + line.price() + "e w kasie.")
                            .formatted(Formatting.GRAY)), false);
        }
        return null;
    }

    /** Everything this shop could serve right now, one entry a line. */
    public static List<Line> onSale(MinecraftServer server, ServerWorld world, Shop shop) {
        Map<String, Line> lines = new LinkedHashMap<>();
        for (Inventory box : stockOf(world, shop)) {
            for (int slot = 0; slot < box.size(); slot++) {
                Line line = lineFor(server, box.getStack(slot), shop);
                if (line != null) {
                    lines.putIfAbsent(line.label(), line);
                }
            }
        }
        return new ArrayList<>(lines.values());
    }

    // --- the trip out ---------------------------------------------------------

    private static void maybeVisit(MinecraftServer server) {
        int crowd = TrapCity.built(TrapCity.Work.TRAM) ? TrapCity.TRAM_SHOPPERS : MAX_SHOPPERS;
        if (!TrapCity.founded() || SHOPPERS.size() >= crowd) {
            return;
        }
        int people = TrapHomes.population();
        if (people <= 0) {
            return;
        }
        Random random = server.getOverworld().getRandom();
        // A town with nothing in the purse does not go shopping. This is the
        // line that makes wages matter at all -- without it the purse only
        // ever grows, spend() never once refuses, and payday is a tax line and
        // nothing else.
        float pull = people * PULL
                * (TrapCity.built(TrapCity.Work.LAMPS) ? TrapCity.LAMPS_TRADE : 1f)
                * TrapMath.townDemand(TrapPayroll.purse(), people);
        if (random.nextFloat() > Math.min(0.95f, pull)) {
            return;
        }

        // Roughly one in three is on their way to work rather than to a
        // counter. Nothing is bought and nothing is paid -- payday already
        // happened off the housing register -- but a town where everybody you
        // ever see is queuing at a till is a town that exists to shop.
        if (random.nextInt(3) == 0 && commute(server, random)) {
            return;
        }

        Shop shop = openShop(server, random);
        if (shop == null) {
            // Nothing to buy anywhere. They still have jobs.
            commute(server, random);
            return;
        }
        List<Shelf> counters = shelvesOf(shop);
        arrive(server, shop.dimension, counters.get(random.nextInt(counters.size())).pos,
                Trip.SHOP, random);
    }

    /**
     * A shop that is loaded, stocked, and has something worth crossing for.
     *
     * A cheap shop draws more custom than a dear one, which is the whole point
     * of being allowed to set a price at all. Pulled out of the trip so that
     * somebody from out of town can be sent to the same counters on the same
     * terms -- a visitor should be drawn by a well-run shop exactly as a
     * neighbour is, or the markup only means anything to half the street.
     */
    private static Shop openShop(MinecraftServer server, Random random) {
        List<Shop> open = new ArrayList<>();
        for (Shop shop : SHOPS) {
            ServerWorld world = worldOf(server, shop.dimension);
            if (world == null || !loaded(world, shop.pos)) {
                continue;
            }
            if (shelvesOf(shop).isEmpty() || wanted(server, world, shop, random) == null) {
                continue;
            }
            // A staffed counter draws more custom than an empty one, which is
            // most of what you are paying the wage for.
            int weight = Math.round(Math.max(1, 200 - shop.markup())
                    * (shop.staffed && !shop.sick ? KEEPER_PULL : 1f));
            for (int i = 0; i < weight; i += 20) {
                open.add(shop);
            }
        }
        return open.isEmpty() ? null : open.get(random.nextInt(open.size()));
    }

    /**
     * Send somebody who is already standing in the world to a counter.
     *
     * The visitor half of {@link #arrive}: the body is supplied rather than
     * found, because whoever is passing through is not in the housing register
     * and {@code freeResident} will never hand them over.
     */
    public static boolean sendVisitor(MinecraftServer server, VillagerEntity body) {
        Random random = server.getOverworld().getRandom();
        if (SHOPPERS.containsKey(body.getUuid())) {
            return false;
        }
        Shop shop = openShop(server, random);
        if (shop == null) {
            return false;
        }
        List<Shelf> counters = shelvesOf(shop);
        BlockPos target = counters.get(random.nextInt(counters.size())).pos;
        ServerWorld world = worldOf(server, shop.dimension);
        return world != null && send(server, world, shop.dimension, target,
                Trip.SHOP, random, body);
    }

    /**
     * Somebody sets off for work.
     *
     * A town's jobs ARE whatever players built -- the tills, the stalls, the
     * casino floors and the vault. No new block, no point-of-interest to
     * register, and a village with none of those simply has nobody commuting,
     * which is the honest answer rather than a bug.
     *
     * @return true if anybody actually set off
     */
    private static boolean commute(MinecraftServer server, Random random) {
        List<Shopper> sites = new ArrayList<>();
        for (Shop shop : SHOPS) {
            sites.add(new Shopper(shop.pos, shop.dimension, 0, Trip.WORK));
        }
        for (TrapStalls.Stall stall : TrapStalls.all()) {
            sites.add(new Shopper(stall.pos(), stall.dimension(), 0, Trip.WORK));
        }
        for (String wire : TrapHouse.wires().keySet()) {
            BlockPos pos = TrapHouse.posOf(wire);
            String where = dimensionOf(wire);
            if (pos != null && where != null) {
                sites.add(new Shopper(pos, where, 0, Trip.WORK));
            }
        }
        if (TrapCity.founded()) {
            sites.add(new Shopper(TrapCity.vaultAt(), TrapCity.vaultWorld(), 0, Trip.WORK));
        }
        if (sites.isEmpty()) {
            return false;
        }
        Shopper site = sites.get(random.nextInt(sites.size()));
        ServerWorld world = worldOf(server, site.dimension());
        if (world == null || !loaded(world, site.target())) {
            return false;
        }
        return arrive(server, site.dimension(), site.target(), Trip.WORK, random);
    }

    /**
     * The dimension half of a casino wire key.
     *
     * Same shape as {@link TrapHouse#posOf}, which takes the other three
     * fields: "dimension x y z", four parts, and a dimension id never contains
     * a space. Null rather than a guess if it is not one.
     */
    private static String dimensionOf(String wire) {
        String[] parts = wire.split(" ");
        return parts.length == 4 ? parts[0] : null;
    }

    private static boolean arrive(MinecraftServer server, String dimension, BlockPos target,
                                  Trip kind, Random random) {
        ServerWorld world = worldOf(server, dimension);
        if (world == null) {
            return false;
        }
        // Somebody who lives here, and nobody else.
        //
        // The shoppers used to be wandering traders conjured on the doorstep
        // wearing a name picked at random out of the housing register, which
        // meant Lom could be buying bread at a till while the real Lom was sat
        // at home -- the same phantom the casino floor ran on until the town
        // was made to supply its own punters. A shop's customers are its
        // neighbours; if none of them is free, nobody comes, and the stock
        // stays on the shelf.
        VillagerEntity shopper = TrapHomes.freeResident(world, target, REACH_OUT);
        if (shopper == null) {
            return false;
        }
        return send(server, world, dimension, target, kind, random, shopper);
    }

    /**
     * Walk a body that has already been chosen to a counter.
     *
     * Split from {@link #arrive} so a visitor can be sent on exactly the same
     * trip as a neighbour. Everything below this line is the same for both:
     * the same tag, the same walk, the same patience, the same till. The only
     * thing that turns on where somebody is from is whose money crosses the
     * counter and where they go afterwards, and both are one branch each.
     */
    private static boolean send(MinecraftServer server, ServerWorld world, String dimension,
                                BlockPos target, Trip kind, Random random,
                                VillagerEntity shopper) {
        String who = plainName(shopper);
        shopper.addCommandTag(TAG);
        shopper.setCustomName(Text.literal(kind == Trip.WORK ? who + "  ·  w pracy" : who)
                .formatted(Formatting.AQUA));
        shopper.setCustomNameVisible(true);
        // Up, if they were in bed. A shift starts at dawn and the schedule
        // would otherwise have them asleep through it.
        shopper.wakeUp();
        // Walked if it is a walk a villager can plan, and stood at the door if
        // it is not -- the same trade the casino makes, for the same reason:
        // a pathfinder gives out somewhere past forty blocks, and nobody
        // watches a neighbour cross town. What must never happen is a SECOND
        // copy of somebody, and cannot: this is the one body the register
        // knows about, moved.
        if (!shopper.getBlockPos().isWithinDistance(target, WALKABLE)) {
            BlockPos door = doorstep(world, target, random);
            if (door == null) {
                return false;
            }
            shopper.refreshPositionAndAngles(door, random.nextFloat() * 360.0F, 0.0F);
        }
        TrapHomes.walkTo(shopper, target);
        SHOPPERS.put(shopper.getUuid(),
                new Shopper(target, dimension, server.getTicks(), kind));
        return true;
    }

    /** What they are called with any errand's total taken back off. */
    private static String plainName(VillagerEntity body) {
        if (body.getCustomName() == null) {
            return "Ktoś";
        }
        String shown = body.getCustomName().getString();
        int cut = shown.indexOf("  ·  ");
        return cut < 0 ? shown : shown.substring(0, cut);
    }

    private static BlockPos doorstep(ServerWorld world, BlockPos shelf, Random random) {
        for (int tries = 0; tries < 24; tries++) {
            int dx = random.nextInt(17) - 8;
            int dz = random.nextInt(17) - 8;
            if (dx * dx + dz * dz < 16) {
                continue;
            }
            BlockPos at = new BlockPos(shelf.getX() + dx, shelf.getY(), shelf.getZ() + dz);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos spot = at.up(dy);
                if (TrapSpawn.safe(world, spot)) {
                    return spot;
                }
            }
        }
        // The old fallback was shelf.up() flat, which is the inside of the
        // shop's own ceiling as often as it is a floor. One last look around
        // the counter, and if there is nowhere at all, nobody comes -- a shop
        // with no room to stand in gets no trade, which is fair and visible.
        return TrapSpawn.near(world, shelf.up());
    }

    private static void shepherd(MinecraftServer server, int now) {
        List<UUID> done = new ArrayList<>();
        for (var row : SHOPPERS.entrySet()) {
            VillagerEntity shopper = find(server, row.getKey());
            if (shopper == null) {
                done.add(row.getKey());
                continue;
            }
            Shopper trip = row.getValue();
            BlockPos counter = trip.target();
            double away = shopper.getBlockPos().getSquaredDistance(counter);

            if (away <= COUNTER * COUNTER) {
                if (trip.trip() == Trip.WORK) {
                    clockOn(server, shopper, counter);
                } else {
                    buy(server, shopper, trip);
                }
                done.add(row.getKey());
                continue;
            }
            if (now - trip.bornAt() > PATIENCE) {
                // Out of patience: put them at the counter rather than let
                // them mill about outside forever. Not literally on top of it
                // -- counter.up() is a wall or a lit fireplace often enough --
                // so the nearest square somebody can stand on instead.
                BlockPos stand = TrapSpawn.near(shopper.getWorld(), counter.up());
                if (stand != null) {
                    shopper.refreshPositionAndAngles(stand, shopper.getYaw(), 0.0F);
                }
                continue;
            }
            if (now % 20 == 0) {
                // A walk target, not a navigation call. A villager Brain
                // re-picks its own destination whenever it has none and simply
                // overwrites a path a tick later; given a target the stroll
                // task stands down, that being the memory it waits on being
                // empty. The old raw call was fine for a wandering trader and
                // is not for a resident.
                shopper.wakeUp();
                TrapHomes.walkTo(shopper, counter);
            }
        }
        done.forEach(SHOPPERS::remove);

        List<UUID> gone = new ArrayList<>();
        for (var row : LEAVING.entrySet()) {
            VillagerEntity shopper = find(server, row.getKey());
            if (shopper == null || now - row.getValue() > LEAVE_TICKS) {
                if (shopper != null) {
                    // Untagged, un-named and walked home, never discarded: a
                    // shopper is somebody's tenant, and binning one here would
                    // have the shops quietly eating the town that keeps them.
                    shopper.removeCommandTag(TAG);
                    if (TrapVisitors.isVisitor(shopper.getUuid())) {
                        // ...unless they are not anybody's tenant. Somebody
                        // passing through has no bed to be walked back to, and
                        // sendHome on a body with no house is how a visitor
                        // turns into furniture. Handed back to whoever brought
                        // them instead; they may have another errand.
                        shopper.setCustomName(null);
                        shopper.setCustomNameVisible(false);
                        TrapVisitors.errandDone(shopper.getUuid());
                        gone.add(row.getKey());
                        continue;
                    }
                    shopper.setCustomName(Text.literal(plainName(shopper))
                            .formatted(Formatting.AQUA));
                    TrapHomes.stayIn(shopper, shopper.getWorld().getTime()
                            + TrapFloor.NIGHT_OFF
                            + shopper.getWorld().getRandom().nextInt(TrapFloor.NIGHT_OFF));
                    // Put on their doorstep, not walked out of the shop they
                    // are standing in -- see TrapHomes#putHome for what that
                    // walk actually does to a villager indoors.
                    TrapHomes.putHome((ServerWorld) shopper.getWorld(), shopper);
                }
                gone.add(row.getKey());
            }
        }
        gone.forEach(LEAVING::remove);
    }

    /**
     * They got to work, and then they are at work.
     *
     * No money moves here and none should: the wage was paid at payday off the
     * housing register, and paying again on arrival would mean a town that
     * earns more when somebody happens to be stood in the chunk watching.
     *
     * What DID happen here was eight seconds of standing at the counter and
     * then a walk home, and a walk home that starts inside somebody's shop is
     * a villager pathing at a shelf it cannot get round: they drift through
     * the back of the building and spend the rest of the day lost in it, which
     * from the street looks like the town wandering into your house. Somebody
     * on shift is at work instead -- off the register's books and out of the
     * world until the shift ends.
     */
    private static void clockOn(MinecraftServer server, VillagerEntity shopper,
                                BlockPos site) {
        ServerWorld world = (ServerWorld) shopper.getWorld();
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, site.getX() + 0.5,
                site.getY() + 1.3, site.getZ() + 0.5, 6, 0.4, 0.3, 0.4, 0.01);
        world.playSound(null, site, SoundEvents.ENTITY_VILLAGER_WORK_MASON,
                SoundCategory.NEUTRAL, 0.5F, 1.0F);
        if (TrapHomes.goToWork(shopper, world.getTime() + SHIFT_TICKS)) {
            return;
        }
        leave(server, shopper);   // not one of ours: they walk off like anybody else
    }

    private static void buy(MinecraftServer server, VillagerEntity shopper,
                            Shopper trip) {
        ServerWorld world = (ServerWorld) shopper.getWorld();
        Shelf shelf = at(world, trip.target());
        Shop shop = shelf == null ? null : ownerOf(shelf);
        if (shop == null) {
            leave(server, shopper);
            return;
        }
        Line line = wanted(server, world, shop, world.getRandom());
        if (line == null) {
            leave(server, shopper);
            return;
        }
        int duty = TrapCity.dutyOn(line.price(), line.duty());
        int total = line.price() + duty;
        // Whose money this is. A neighbour shops out of the town's wage bill;
        // somebody passing through brought their own, from outside it -- which
        // is the entire reason visitors exist, because a shop whose only
        // customers are its neighbours can only ever be as busy as the town is
        // populous.
        boolean visitor = TrapVisitors.isVisitor(shopper.getUuid());
        // Afford BEFORE take. take() empties the chest, so a payer that turns
        // out to be broke one line later has walked off with the shopping --
        // and it would look like the stock was miscounted, not like the money
        // ran out.
        boolean afford = visitor ? TrapVisitors.purseOf(shopper.getUuid()) >= total
                : TrapPayroll.afford(total);
        if (!afford || !take(world, shop, line)) {
            leave(server, shopper);
            return;
        }
        if (visitor) {
            TrapVisitors.spend(shopper.getUuid(), total);
        } else {
            TrapPayroll.spend(total);
        }
        shop.till += line.price();
        shop.sold++;
        shop.turnover += line.price();
        TrapCity.receive(duty, line.duty());

        // What they spent, over their head, for the eight seconds they hang
        // about before leaving. A shopper you watched walk in is worth more as
        // "Maud  ·  12e" than as one more anonymous villager at a shelf.
        String named = plainName(shopper);
        shopper.setCustomName(Text.literal(named + "  ·  " + (line.price() + duty) + "e")
                .formatted(Formatting.GREEN));

        world.playSound(null, shelf.pos, SoundEvents.ENTITY_VILLAGER_TRADE,
                SoundCategory.NEUTRAL, 0.8F, 1.0F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, shelf.pos.getX() + 0.5,
                shelf.pos.getY() + 1.2, shelf.pos.getZ() + 0.5, 8, 0.35, 0.3, 0.35, 0.02);

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(shop.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal("Sprzedano ").formatted(Formatting.GRAY)
                    .append(Text.literal(line.count() + "x " + line.label())
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(" -- " + line.price() + "e w kasie")
                            .formatted(Formatting.GREEN))
                    .append(Text.literal(duty > 0 ? ", " + duty + "e podatku" : "")
                            .formatted(Formatting.DARK_GRAY)), true);
        }
        leave(server, shopper);
    }

    private static void leave(MinecraftServer server, VillagerEntity shopper) {
        LEAVING.put(shopper.getUuid(), server.getTicks());
        shopper.getNavigation().stop();
    }

    private static VillagerEntity find(MinecraftServer server, UUID id) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getEntity(id) instanceof VillagerEntity found
                    && found.getCommandTags().contains(TAG)) {
                return found;
            }
        }
        return null;
    }

    private static boolean loaded(ServerWorld world, BlockPos pos) {
        return world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static ServerWorld worldOf(MinecraftServer server, String dimension) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(dimension)) {
                return world;
            }
        }
        return null;
    }

    // --- the directory --------------------------------------------------------

    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("shops")
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
        if (SHOPS.isEmpty()) {
            who.sendMessage(Text.literal("Nikt jeszcze nie otworzył sklepu.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        int people = TrapHomes.population();
        who.sendMessage(Text.literal("Sklepy").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("   mieszkańców: " + people)
                        .formatted(people > 0 ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal(people > 0 ? "" : "  -- zbuduj domy, inaczej nikt nie przyjdzie")
                        .formatted(Formatting.DARK_GRAY)), false);
        for (Shop shop : SHOPS) {
            who.sendMessage(Text.literal("  " + shop.name).formatted(Formatting.WHITE)
                    .append(Text.literal("  półek: " + shelvesOf(shop).size())
                            .formatted(Formatting.GRAY))
                    .append(Text.literal("  " + shop.markupName().toLowerCase(
                            java.util.Locale.ROOT)).formatted(Formatting.AQUA))
                    .append(Text.literal("  sprzedano: " + shop.sold + "  "
                            + shop.pos.getX() + " " + shop.pos.getY() + " " + shop.pos.getZ())
                            .formatted(Formatting.DARK_GRAY)), false);
        }
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-shops.txt");
        SHOPS.clear();
        SHELVES.clear();
        SHOPPERS.clear();
        LEAVING.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+", 10);
                if (parts.length < 5) {
                    continue;
                }
                if (parts[0].equals("shelf")) {
                    SHELVES.add(new Shelf(parts[1], new BlockPos(Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4]))));
                } else if (parts[0].equals("shop") && parts.length >= 10) {
                    Shop shop = new Shop(parts[1], new BlockPos(Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4])),
                            UUID.fromString(parts[5]), parts[6], parts[9]);
                    shop.markup = Math.max(0, Math.min(MARKUP.length - 1,
                            Integer.parseInt(parts[7])));
                    String[] money = parts[8].split(",");
                    shop.till = Integer.parseInt(money[0]);
                    shop.sold = money.length > 1 ? Integer.parseInt(money[1]) : 0;
                    shop.turnover = money.length > 2 ? Integer.parseInt(money[2]) : 0;
                    shop.staffed = money.length > 3 && money[3].equals("1");
                    shop.lastPaid = money.length > 4 ? Long.parseLong(money[4]) : -1;
                    // Appended, and length-guarded like everything before it,
                    // so a register written by an older build still reads.
                    shop.backOn = money.length > 5 ? Long.parseLong(money[5]) : -1;
                    SHOPS.add(shop);
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the shops: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Shop shop : SHOPS) {
                out.append("shop ").append(shop.dimension).append(' ')
                        .append(shop.pos.getX()).append(' ').append(shop.pos.getY())
                        .append(' ').append(shop.pos.getZ()).append(' ')
                        .append(shop.owner).append(' ').append(shop.ownerName).append(' ')
                        .append(shop.markup).append(' ')
                        .append(shop.till).append(',').append(shop.sold).append(',')
                        .append(shop.turnover).append(',')
                        .append(shop.staffed ? 1 : 0).append(',')
                        .append(shop.lastPaid).append(',')
                        .append(shop.backOn).append(' ')
                        .append(shop.name.replace('\n', ' ')).append('\n');
            }
            for (Shelf shelf : SHELVES) {
                out.append("shelf ").append(shelf.dimension).append(' ')
                        .append(shelf.pos.getX()).append(' ').append(shelf.pos.getY())
                        .append(' ').append(shelf.pos.getZ()).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the shops: {}", failure.toString());
        }
    }
}
