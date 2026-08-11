package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WanderingTraderEntity;
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
            "Cut-price", "Keen", "Going rate", "Dear", "Daylight robbery"};

    private static final int CHECK_INTERVAL = 20 * 20;
    private static final float PULL = 0.06f;
    private static final int MAX_SHOPPERS = 6;
    private static final int PATIENCE = 20 * 20;
    private static final int COUNTER = 3;
    private static final int LEAVE_TICKS = 20 * 8;

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

    private record Shopper(BlockPos shelf, String dimension, int bornAt) {
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
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int now = server.getTicks();
            if (!SHOPPERS.isEmpty() || !LEAVING.isEmpty()) {
                shepherd(server, now);
            }
            if (now % CHECK_INTERVAL == 0) {
                maybeVisit(server);
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
                owner.getGameProfile().getName() + "'s shop"));
        save();
        owner.sendMessage(Text.literal("Shop open. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("Put market shelves within " + REACH + " blocks and "
                        + "they join it. Stock goes in any chest under the till or under "
                        + "a shelf.").formatted(Formatting.GRAY)), false);
        if (!TrapCity.founded()) {
            owner.sendMessage(Text.literal("There's no city yet, so there's nobody to "
                    + "shop here.").formatted(Formatting.DARK_GRAY), false);
        }
    }

    public static void closeShop(ServerWorld world, BlockPos pos) {
        Shop shop = shopAt(world, pos);
        if (shop == null) {
            return;
        }
        spill(world, pos, shop.till);
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
                ? Text.literal("A shelf with no shop. ").formatted(Formatting.YELLOW)
                        .append(Text.literal("Put a shop till within " + REACH
                                + " blocks and it'll join.").formatted(Formatting.GRAY))
                : Text.literal("Joined ").formatted(Formatting.GREEN)
                        .append(Text.literal(shop.name).formatted(Formatting.GOLD))
                        .append(Text.literal(". Stock a chest under it, or under the till.")
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
     * Under the till and under every shelf, because a supermarket keeps its
     * stock in a back room and a market stall keeps it under the counter, and
     * telling somebody which of those they are allowed to build would be the
     * mod deciding what their shop looks like.
     */
    public static List<Inventory> stockOf(ServerWorld world, Shop shop) {
        List<Inventory> boxes = new ArrayList<>();
        if (!world.getRegistryKey().getValue().toString().equals(shop.dimension)) {
            return boxes;
        }
        if (world.getBlockEntity(shop.pos.down()) instanceof Inventory under) {
            boxes.add(under);
        }
        for (Shelf shelf : shelvesOf(shop)) {
            if (world.getBlockEntity(shelf.pos.down()) instanceof Inventory box) {
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

    // --- the trip out ---------------------------------------------------------

    private static void maybeVisit(MinecraftServer server) {
        if (!TrapCity.founded() || SHOPS.isEmpty() || SHOPPERS.size() >= MAX_SHOPPERS) {
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

        // A cheap shop draws more custom than a dear one, which is the whole
        // point of being allowed to set a price at all.
        List<Shop> open = new ArrayList<>();
        for (Shop shop : SHOPS) {
            ServerWorld world = worldOf(server, shop.dimension);
            if (world == null || !loaded(world, shop.pos)) {
                continue;
            }
            if (shelvesOf(shop).isEmpty() || wanted(server, world, shop, random) == null) {
                continue;
            }
            int weight = Math.max(1, 200 - shop.markup());
            for (int i = 0; i < weight; i += 20) {
                open.add(shop);
            }
        }
        if (open.isEmpty()) {
            return;
        }
        Shop shop = open.get(random.nextInt(open.size()));
        List<Shelf> counters = shelvesOf(shop);
        arrive(server, shop, counters.get(random.nextInt(counters.size())), random);
    }

    private static void arrive(MinecraftServer server, Shop shop, Shelf shelf, Random random) {
        ServerWorld world = worldOf(server, shop.dimension);
        if (world == null) {
            return;
        }
        BlockPos door = doorstep(world, shelf.pos, random);
        if (door == null) {
            return;
        }
        WanderingTraderEntity shopper = EntityType.WANDERING_TRADER.create(world,
                SpawnReason.EVENT);
        if (shopper == null) {
            return;
        }
        shopper.refreshPositionAndAngles(door, random.nextFloat() * 360.0F, 0.0F);
        shopper.setCustomName(Text.literal("Townsperson").formatted(Formatting.AQUA));
        shopper.setCustomNameVisible(true);
        shopper.addCommandTag(TAG);
        shopper.setDespawnDelay(20 * 60 * 3);
        world.spawnEntity(shopper);
        SHOPPERS.put(shopper.getUuid(),
                new Shopper(shelf.pos, shop.dimension, server.getTicks()));
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
            WanderingTraderEntity shopper = find(server, row.getKey());
            if (shopper == null) {
                done.add(row.getKey());
                continue;
            }
            Shopper trip = row.getValue();
            BlockPos counter = trip.shelf();
            double away = shopper.getBlockPos().getSquaredDistance(counter);

            if (away <= COUNTER * COUNTER) {
                buy(server, shopper, trip);
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
                shopper.getNavigation().startMovingTo(counter.getX() + 0.5,
                        counter.getY() + 1.0, counter.getZ() + 0.5, 0.55);
            }
        }
        done.forEach(SHOPPERS::remove);

        List<UUID> gone = new ArrayList<>();
        for (var row : LEAVING.entrySet()) {
            WanderingTraderEntity shopper = find(server, row.getKey());
            if (shopper == null || now - row.getValue() > LEAVE_TICKS) {
                if (shopper != null) {
                    shopper.discard();
                }
                gone.add(row.getKey());
            }
        }
        gone.forEach(LEAVING::remove);
    }

    private static void buy(MinecraftServer server, WanderingTraderEntity shopper,
                            Shopper trip) {
        ServerWorld world = (ServerWorld) shopper.getWorld();
        Shelf shelf = at(world, trip.shelf());
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
        // Afford BEFORE take. take() empties the chest, so a town that turns
        // out to be broke one line later has walked off with the shopping --
        // and it would look like the stock was miscounted, not like the money
        // ran out.
        if (!TrapPayroll.afford(total) || !take(world, shop, line)) {
            leave(server, shopper);
            return;
        }
        TrapPayroll.spend(total);
        shop.till += line.price();
        shop.sold++;
        shop.turnover += line.price();
        TrapCity.receive(duty, line.duty());

        world.playSound(null, shelf.pos, SoundEvents.ENTITY_VILLAGER_TRADE,
                SoundCategory.NEUTRAL, 0.8F, 1.0F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, shelf.pos.getX() + 0.5,
                shelf.pos.getY() + 1.2, shelf.pos.getZ() + 0.5, 8, 0.35, 0.3, 0.35, 0.02);

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(shop.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal("Sold ").formatted(Formatting.GRAY)
                    .append(Text.literal(line.count() + "x " + line.label())
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(" -- " + line.price() + "e in the till")
                            .formatted(Formatting.GREEN))
                    .append(Text.literal(duty > 0 ? ", " + duty + "e duty" : "")
                            .formatted(Formatting.DARK_GRAY)), true);
        }
        leave(server, shopper);
    }

    private static void leave(MinecraftServer server, WanderingTraderEntity shopper) {
        LEAVING.put(shopper.getUuid(), server.getTicks());
        shopper.getNavigation().stop();
    }

    private static WanderingTraderEntity find(MinecraftServer server, UUID id) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getEntity(id) instanceof WanderingTraderEntity found
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
            who.sendMessage(Text.literal("Nobody's opened a shop yet.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        int people = TrapHomes.population();
        who.sendMessage(Text.literal("The Shops").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("   " + people + " townspeople")
                        .formatted(people > 0 ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal(people > 0 ? "" : "  -- build houses, or nobody comes")
                        .formatted(Formatting.DARK_GRAY)), false);
        for (Shop shop : SHOPS) {
            who.sendMessage(Text.literal("  " + shop.name).formatted(Formatting.WHITE)
                    .append(Text.literal("  " + shelvesOf(shop).size() + " shelves")
                            .formatted(Formatting.GRAY))
                    .append(Text.literal("  " + shop.markupName().toLowerCase(
                            java.util.Locale.ROOT)).formatted(Formatting.AQUA))
                    .append(Text.literal("  " + shop.sold + " sold  "
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
                        .append(shop.turnover).append(' ')
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
