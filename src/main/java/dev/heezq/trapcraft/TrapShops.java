package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.inventory.Inventory;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shops that people actually walk into.
 *
 * A market stall sells to PLAYERS, and on a server with three of them that is
 * a shop with three possible customers. A shelf sells to the city: villagers
 * come out of the housing, walk to the building, take something off the shelf
 * and pay for it. That is the first demand in this mod that does not come out
 * of somebody's pocket, and it is what turns a farm into a business.
 *
 * <h2>How much custom you get</h2>
 *
 * {@link TrapHomes#population}, which is the sum of the housing grades. So the
 * loop closes: houses make people, people shop, shopping pays the farmer and
 * the city, the city's purse pays for more of the town. Nobody has to be told
 * to build houses -- the shop tells them.
 *
 * <h2>Why the stock is the container underneath</h2>
 *
 * Same reason the stall's is. No inventory to serialise, no components to
 * lose, and restocking is putting things in a barrel. A shelf over a barrel
 * also happens to be exactly what a shop counter looks like, so a supermarket
 * is a row of them and needs no concept of its own.
 *
 * <h2>What the shopkeeper earns</h2>
 *
 * {@link #RETAIL} of the market price, against the {@code SELL_RATE} the
 * counter pays -- roughly double. That is the entire argument for building a
 * shop instead of dumping harvests at the counter, and it is bounded by
 * population rather than by patience, so it can never become a tap.
 */
public final class TrapShops {

    /** Marks our shoppers, so they are never confused with a real trader. */
    public static final String TAG = "trapcraft_shopper";

    /** What a villager pays, against the market price. */
    public static final float RETAIL = 0.90f;
    /** How often the city decides whether somebody fancies a trip out. */
    private static final int CHECK_INTERVAL = 20 * 20;
    /** Chance of a visit per head of population, per roll. */
    private static final float PULL = 0.06f;
    /** Shoppers about at once, over the whole map. */
    private static final int MAX_SHOPPERS = 6;
    /** Ticks a shopper gets to reach the counter before giving up walking. */
    private static final int PATIENCE = 20 * 20;
    /** How close counts as at the counter. */
    private static final int COUNTER = 3;
    /** Ticks a shopper hangs about after paying, so they leave rather than pop. */
    private static final int LEAVE_TICKS = 20 * 8;

    /** One counter: where it is, whose it is, and what it has taken. */
    public static final class Shelf {
        final String dimension;
        final BlockPos pos;
        final UUID owner;
        String ownerName;
        int till;
        int sold;

        Shelf(String dimension, BlockPos pos, UUID owner, String ownerName) {
            this.dimension = dimension;
            this.pos = pos;
            this.owner = owner;
            this.ownerName = ownerName;
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

        public int till() {
            return till;
        }

        public int sold() {
            return sold;
        }
    }

    /** A villager on a shopping trip. */
    private record Shopper(BlockPos shelf, String dimension, int bornAt) {
    }

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

    public static Shelf at(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Shelf shelf : SHELVES) {
            if (shelf.pos.equals(pos) && shelf.dimension.equals(dimension)) {
                return shelf;
            }
        }
        return null;
    }

    public static List<Shelf> all() {
        return SHELVES;
    }

    /** Takings sat in shelves, which are emeralds nobody is carrying. */
    public static int tillsHeld() {
        int total = 0;
        for (Shelf shelf : SHELVES) {
            total += shelf.till;
        }
        return total;
    }

    public static void claim(ServerWorld world, BlockPos pos, ServerPlayerEntity owner) {
        if (at(world, pos) != null) {
            return;
        }
        SHELVES.add(new Shelf(world.getRegistryKey().getValue().toString(), pos.toImmutable(),
                owner.getUuid(), owner.getGameProfile().getName()));
        save();
        owner.sendMessage(Text.literal("Your shelf. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("Put a chest or barrel underneath and stock it. "
                        + "Townspeople will come and buy at "
                        + Math.round(RETAIL * 100) + "% of the market price.")
                        .formatted(Formatting.GRAY)), false);
        if (!TrapCity.founded()) {
            owner.sendMessage(Text.literal("There's no city yet, so there's nobody to "
                    + "shop here. Somebody has to put a vault down.")
                    .formatted(Formatting.DARK_GRAY), false);
        }
    }

    /** Taken down. The takings spill rather than evaporate. */
    public static void release(ServerWorld world, BlockPos pos) {
        Shelf shelf = at(world, pos);
        if (shelf == null) {
            return;
        }
        if (shelf.till > 0) {
            int[] packed = TrapMath.packEmeralds(shelf.till);
            for (int i = 0; i < packed[0]; i++) {
                net.minecraft.block.Block.dropStack(world, pos,
                        new ItemStack(net.minecraft.item.Items.EMERALD_BLOCK));
            }
            if (packed[1] > 0) {
                net.minecraft.block.Block.dropStack(world, pos,
                        new ItemStack(net.minecraft.item.Items.EMERALD, packed[1]));
            }
        }
        SHELVES.remove(shelf);
        save();
    }

    /** Empty the till into the owner's pockets. Returns what was in it. */
    public static int collect(ServerPlayerEntity owner, Shelf shelf) {
        int takings = shelf.till;
        if (takings <= 0) {
            return 0;
        }
        shelf.till = 0;
        // handOver, not pay: the shopper's emeralds entered the world when
        // they were spent. Paying again here would mint them twice.
        TrapMarket.handOver(owner, takings);
        TrapLedger.record(owner, TrapLedger.Source.STALL, takings);
        save();
        return takings;
    }

    // --- what is on the shelf -------------------------------------------------

    public static Inventory stockOf(ServerWorld world, Shelf shelf) {
        if (!world.getRegistryKey().getValue().toString().equals(shelf.dimension)) {
            return null;
        }
        return world.getBlockEntity(shelf.pos.down()) instanceof Inventory box ? box : null;
    }

    /**
     * A line a shopper would actually take, or null if there is nothing.
     *
     * Weighted towards food, and heavily. Townspeople buy dinner far more
     * often than they buy a stack of polished andesite, which is both true and
     * the reason the farmer is the one this system is built for.
     */
    private static ShopStock.Entry wanted(ServerWorld world, Shelf shelf, Random random) {
        Inventory box = stockOf(world, shelf);
        if (box == null) {
            return null;
        }
        Map<ShopStock.Entry, Integer> lines = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ShopStock.Entry entry = ShopStock.matching(stack);
            if (entry != null) {
                lines.merge(entry, stack.getCount(), Integer::sum);
            }
        }
        lines.entrySet().removeIf(row -> row.getValue() < row.getKey().count());
        if (lines.isEmpty()) {
            return null;
        }

        List<ShopStock.Entry> pool = new ArrayList<>();
        for (ShopStock.Entry entry : lines.keySet()) {
            int weight = TrapCity.forGoods(entry.category()) == TrapCity.Duty.ESSENTIALS ? 5 : 1;
            for (int i = 0; i < weight; i++) {
                pool.add(entry);
            }
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private static boolean take(Inventory box, ShopStock.Entry entry, int wanted) {
        int found = 0;
        for (int slot = 0; slot < box.size() && found < wanted; slot++) {
            if (entry.matches(box.getStack(slot))) {
                found += box.getStack(slot).getCount();
            }
        }
        if (found < wanted) {
            return false;
        }
        int owed = wanted;
        for (int slot = 0; slot < box.size() && owed > 0; slot++) {
            ItemStack stack = box.getStack(slot);
            if (!entry.matches(stack)) {
                continue;
            }
            int taken = Math.min(owed, stack.getCount());
            stack.decrement(taken);
            owed -= taken;
        }
        box.markDirty();
        return true;
    }

    // --- the trip out ---------------------------------------------------------

    /**
     * Does anybody fancy going to the shops?
     *
     * Gated on the city existing at all, because the shoppers ARE the city --
     * there is nobody to come out of the houses until somebody has founded
     * one, and a village that materialised customers out of an empty map would
     * be a spawner with extra steps.
     */
    private static void maybeVisit(MinecraftServer server) {
        if (!TrapCity.founded() || SHELVES.isEmpty() || SHOPPERS.size() >= MAX_SHOPPERS) {
            return;
        }
        int people = TrapHomes.population();
        if (people <= 0) {
            return;
        }
        Random random = server.getOverworld().getRandom();
        if (random.nextFloat() > Math.min(0.9f, people * PULL)) {
            return;
        }

        List<Shelf> open = new ArrayList<>();
        for (Shelf shelf : SHELVES) {
            ServerWorld world = worldOf(server, shelf);
            if (world == null || !loaded(world, shelf.pos)) {
                continue;
            }
            if (wanted(world, shelf, random) != null) {
                open.add(shelf);
            }
        }
        if (open.isEmpty()) {
            return;
        }
        Shelf shelf = open.get(random.nextInt(open.size()));
        arrive(server, shelf, random);
    }

    /** Somebody walks in off the street. */
    private static void arrive(MinecraftServer server, Shelf shelf, Random random) {
        ServerWorld world = worldOf(server, shelf);
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
        // Vanilla's own despawn timer as a backstop: if the server restarts and
        // the in-memory record goes with it, they wander off on their own
        // rather than standing in somebody's shop forever.
        shopper.setDespawnDelay(20 * 60 * 3);
        world.spawnEntity(shopper);
        SHOPPERS.put(shopper.getUuid(),
                new Shopper(shelf.pos, shelf.dimension, server.getTicks()));
    }

    /**
     * Somewhere outside to walk in FROM.
     *
     * Tries the ring around the counter and takes the first standable spot
     * with sky above it, so shoppers appear on the street rather than inside
     * the stock room. If the shop is buried, they turn up at the counter and
     * nobody is any the wiser.
     */
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
                if (world.getBlockState(spot).isAir()
                        && world.getBlockState(spot.up()).isAir()
                        && !world.getBlockState(spot.down()).isAir()) {
                    return spot;
                }
            }
        }
        return shelf.up();
    }

    /** Walk them in, sell to them, walk them out. */
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
                // Pathing is not allowed to be load-bearing. Twenty seconds of
                // trying is a good-faith walk; after that they are simply at
                // the counter, which is what a person watching would assume
                // happened anyway.
                shopper.refreshPositionAndAngles(counter.up(), shopper.getYaw(), 0.0F);
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

    /**
     * One lot, off the shelf, paid for.
     *
     * The money is minted here because the shopper is not a player and their
     * emeralds were never in the world before. It is split at once: the shop's
     * share into the till, the duty into the city's purse. Both of those are
     * balances the market resample knows to count, which is the only reason
     * minting the whole lot does not quietly inflate the index.
     */
    private static void buy(MinecraftServer server, WanderingTraderEntity shopper,
                            Shopper trip) {
        ServerWorld world = (ServerWorld) shopper.getWorld();
        Shelf shelf = at(world, trip.shelf());
        if (shelf == null) {
            leave(server, shopper);
            return;
        }
        Inventory box = stockOf(world, shelf);
        ShopStock.Entry entry = box == null ? null
                : wanted(world, shelf, world.getRandom());
        if (entry == null || !take(box, entry, entry.count())) {
            leave(server, shopper);
            return;
        }

        int market = TrapMarket.buyPrice(server, entry);
        int price = Math.max(1, Math.round(market * RETAIL));
        int duty = TrapCity.dutyOn(price, TrapCity.forGoods(entry.category()));

        TrapMarket.minted(price + duty);
        shelf.till += price;
        shelf.sold++;
        TrapCity.receive(duty, TrapCity.forGoods(entry.category()));
        TrapMarket.traded(entry, 1, true);
        save();

        world.playSound(null, shelf.pos, SoundEvents.ENTITY_VILLAGER_TRADE,
                SoundCategory.NEUTRAL, 0.8F, 1.0F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, shelf.pos.getX() + 0.5,
                shelf.pos.getY() + 1.2, shelf.pos.getZ() + 0.5, 8, 0.35, 0.3, 0.35, 0.02);

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(shelf.owner);
        if (owner != null) {
            owner.sendMessage(Text.literal("Sold ").formatted(Formatting.GRAY)
                    .append(Text.literal(entry.count() + "x " + entry.label())
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(" -- " + price + "e in the shelf")
                            .formatted(Formatting.GREEN))
                    .append(Text.literal(duty > 0 ? ", " + duty + "e duty to the city" : "")
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

    private static ServerWorld worldOf(MinecraftServer server, Shelf shelf) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(shelf.dimension)) {
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
        if (SHELVES.isEmpty()) {
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
        for (Shelf shelf : SHELVES) {
            who.sendMessage(Text.literal("  " + shelf.ownerName + "'s shelf")
                    .formatted(Formatting.WHITE)
                    .append(Text.literal("  " + shelf.pos.getX() + " " + shelf.pos.getY()
                            + " " + shelf.pos.getZ()).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("  " + shelf.sold + " sold")
                            .formatted(Formatting.GRAY)), false);
        }
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-shops.txt");
        SHELVES.clear();
        SHOPPERS.clear();
        LEAVING.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 8) {
                    continue;
                }
                Shelf shelf = new Shelf(parts[0], new BlockPos(Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3])),
                        UUID.fromString(parts[4]), parts[5]);
                shelf.till = Integer.parseInt(parts[6]);
                shelf.sold = Integer.parseInt(parts[7]);
                SHELVES.add(shelf);
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
            for (Shelf shelf : SHELVES) {
                out.append(shelf.dimension).append(' ')
                        .append(shelf.pos.getX()).append(' ')
                        .append(shelf.pos.getY()).append(' ')
                        .append(shelf.pos.getZ()).append(' ')
                        .append(shelf.owner).append(' ')
                        .append(shelf.ownerName).append(' ')
                        .append(shelf.till).append(' ')
                        .append(shelf.sold).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the shops: {}", failure.toString());
        }
    }
}
