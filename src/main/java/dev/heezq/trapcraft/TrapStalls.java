package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shops that belong to somebody.
 *
 * The market has always been a counter with infinite stock and a 55% spread,
 * which means three people can play this server for a month and never once
 * need each other. The farmer sells wheat to the counter at 45%, the builder
 * buys stone off the counter at 100%, and the fact that they are sat in the
 * same city doing complementary jobs never comes up.
 *
 * A stall is the other side of that. It sells what its owner put under it, at
 * {@link TrapMath#STALL_RATE} of what the market charges -- so buying from a
 * neighbour is always cheaper than buying from the counter, and supplying a
 * neighbour always pays better than selling to it. Nobody loses; the money
 * that used to evaporate into the spread is split between the two of them
 * instead.
 *
 * <h2>Why there is no price editor</h2>
 *
 * Because a price editor is a menu, and what this wants to be is a REASON TO
 * WALK ACROSS TOWN. Prices follow the market automatically, which means a
 * stall is never mispriced, never stale, and needs no interface at all -- you
 * stock it and you are open. The one decision left is what to stock, which is
 * the interesting one.
 *
 * <h2>Why the stock is a chest</h2>
 *
 * A stall sells whatever is in the container DIRECTLY BENEATH it. No inventory
 * to serialise, no components to lose -- a Fire-grade bud keeps its grade
 * because it never leaves a real container -- and restocking is putting things
 * in a barrel, which is a thing people already know how to do. The crew reach
 * for the nearest chest for the same reason.
 */
public final class TrapStalls {
    /** One shop: where it is, whose it is, and what it has taken. */
    public static final class Stall {
        final String dimension;
        final BlockPos pos;
        final UUID owner;
        String ownerName;
        /** Takings waiting to be collected. See {@link #collect}. */
        int till;

        Stall(String dimension, BlockPos pos, UUID owner, String ownerName) {
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

        public String dimension() {
            return dimension;
        }
    }

    private static final List<Stall> STALLS = new ArrayList<>();
    private static Path saveFile;

    private TrapStalls() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapStalls::load);
        registerCommand();
    }

    // --- the register ---------------------------------------------------------

    public static Stall at(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (Stall stall : STALLS) {
            if (stall.pos.equals(pos) && stall.dimension.equals(dimension)) {
                return stall;
            }
        }
        return null;
    }

    public static List<Stall> all() {
        return STALLS;
    }

    /** Takings sat in tills, which are emeralds nobody is carrying. */
    public static int tillsHeld() {
        int total = 0;
        for (Stall stall : STALLS) {
            total += stall.till;
        }
        return total;
    }

    /** Whoever placed it owns it. */
    public static void claim(ServerWorld world, BlockPos pos, ServerPlayerEntity owner) {
        if (at(world, pos) != null) {
            return;
        }
        STALLS.add(new Stall(world.getRegistryKey().getValue().toString(), pos.toImmutable(),
                owner.getUuid(), owner.getGameProfile().getName()));
        save();
        owner.sendMessage(Text.literal("Your stall. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("Put a chest or barrel underneath and whatever is in it "
                        + "is for sale at " + Math.round(TrapMath.STALL_RATE * 100)
                        + "% of the market price.").formatted(Formatting.GRAY)), false);
    }

    /**
     * Taken down. The takings go with it rather than evaporating.
     *
     * Dropped as emeralds at the stall rather than paid to the owner, because
     * whoever broke it is stood right there and may not be them -- and a shop
     * that pays its old owner while somebody else pockets the building is a
     * worse surprise than one that spills its till on the floor.
     */
    public static void release(ServerWorld world, BlockPos pos) {
        Stall stall = at(world, pos);
        if (stall == null) {
            return;
        }
        if (stall.till > 0) {
            int[] packed = TrapMath.packEmeralds(stall.till);
            for (int i = 0; i < packed[0]; i++) {
                net.minecraft.block.Block.dropStack(world, pos,
                        new ItemStack(net.minecraft.item.Items.EMERALD_BLOCK));
            }
            if (packed[1] > 0) {
                net.minecraft.block.Block.dropStack(world, pos,
                        new ItemStack(net.minecraft.item.Items.EMERALD, packed[1]));
            }
        }
        STALLS.remove(stall);
        save();
    }

    // --- the stock ------------------------------------------------------------

    /**
     * What this stall is selling, or null if nobody has put anything under it.
     *
     * Deliberately the block DIRECTLY below and nothing else. A search would
     * mean a stall could quietly start selling out of a chest somebody built
     * near it for another purpose, and "which chest is my shop using" is not a
     * question anybody should have to ask.
     */
    public static Inventory stockOf(ServerWorld world, Stall stall) {
        if (!world.getRegistryKey().getValue().toString().equals(stall.dimension)) {
            return null;
        }
        // Both halves of a double chest, not just the near one.
        return TrapBoxes.at(world, stall.pos.down());
    }

    /** Catalogue lines this stall currently has on it, and how many lots of each. */
    public static Map<ShopStock.Entry, Integer> listing(ServerWorld world, Stall stall) {
        Map<ShopStock.Entry, Integer> out = new java.util.LinkedHashMap<>();
        Inventory box = stockOf(world, stall);
        if (box == null) {
            return out;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ShopStock.Entry entry = ShopStock.matching(stack);
            // Anything the market has no price for cannot be sold here either:
            // the whole price is derived from the market's, so a line it has
            // never heard of has no number to derive from.
            if (entry != null) {
                out.merge(entry, stack.getCount(), Integer::sum);
            }
        }
        out.entrySet().removeIf(row -> row.getValue() < row.getKey().count());
        return out;
    }

    /**
     * The best stall offer for this line anywhere, or null.
     *
     * Only stalls in LOADED chunks are considered, and that is a feature as
     * much as a limitation: reading an unloaded one would drag its chunk into
     * memory every time somebody opened the shop screen. A stall nobody is
     * near is a shop with the shutters down.
     */
    public static Stall sellerOf(MinecraftServer server, ShopStock.Entry entry) {
        for (Stall stall : STALLS) {
            ServerWorld world = worldOf(server, stall);
            if (world == null || !loaded(world, stall.pos)) {
                continue;
            }
            if (listing(world, stall).containsKey(entry)) {
                return stall;
            }
        }
        return null;
    }

    private static boolean loaded(ServerWorld world, BlockPos pos) {
        return world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static ServerWorld worldOf(MinecraftServer server, Stall stall) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(stall.dimension)) {
                return world;
            }
        }
        return null;
    }

    // --- buying ---------------------------------------------------------------

    /**
     * Buy one lot off somebody's stall.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String buy(ServerPlayerEntity shopper, Stall stall, ShopStock.Entry entry) {
        ServerWorld world = shopper.getWorld();
        Inventory box = stockOf(world, stall);
        if (box == null) {
            return "There's nothing under this stall.";
        }
        int price = TrapMath.stallPrice(TrapMarket.buyPrice(shopper.getServer(), entry));
        // A neighbour's stall is still a shop, so it pays the same duty the
        // counter does -- otherwise the city would quietly fund itself out of
        // whoever could not be bothered to walk across town.
        TrapCity.Duty band = TrapCity.forGoods(entry.category());
        int duty = TrapCity.dutyOn(price, band);
        if (TrapMarket.wealthOf(shopper) < price + duty) {
            return "That's " + (price + duty) + "e, and you haven't got it.";
        }
        if (!take(box, entry, entry.count())) {
            return "They've sold out of that.";
        }

        // The fee LEAVES circulation and the rest is a transfer, so only the
        // fee goes through take(). Paying the whole lot through it and handing
        // it on with pay() would report one sale as both a destruction and a
        // creation of the same money, and the index would feel a trade that
        // never changed how much money was about.
        int keeps = TrapMath.stallTake(price);
        TrapMarket.take(shopper, price - keeps);
        TrapMarket.collect(shopper, keeps);
        stall.till += keeps;
        TrapCity.charge(shopper, price, band);
        // The buyer's side. The seller is credited when they empty the till,
        // not now -- crediting both here would book the sale twice.
        TrapLedger.record(shopper, TrapLedger.Source.STALL, -price);
        save();

        shopper.getInventory().offerOrDrop(entry.stack());
        world.playSound(null, stall.pos, net.minecraft.sound.SoundEvents.BLOCK_BARREL_CLOSE,
                net.minecraft.sound.SoundCategory.BLOCKS, 0.7F, 1.3F);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
                stall.pos.getX() + 0.5, stall.pos.getY() + 1.1, stall.pos.getZ() + 0.5,
                6, 0.3, 0.2, 0.3, 0.01);

        ServerPlayerEntity owner = shopper.getServer().getPlayerManager().getPlayer(stall.owner);
        if (owner != null && owner != shopper) {
            owner.sendMessage(Text.literal("Sold ").formatted(Formatting.GREEN)
                    .append(Text.literal(entry.count() + "x " + entry.label())
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(" to " + shopper.getGameProfile().getName()
                            + " -- " + keeps + "e in the till.").formatted(Formatting.GRAY)), false);
        }
        return null;
    }

    /** Pull one lot out of the stall's chest. False if it wasn't all there. */
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

    /** Empty the till into the owner's pockets. Returns what was in it. */
    public static int collect(ServerPlayerEntity owner, Stall stall) {
        int takings = stall.till;
        if (takings <= 0) {
            return 0;
        }
        stall.till = 0;
        // handOver, not pay: this money never left circulation, it was just
        // sat in a shop's till waiting to be picked up.
        TrapMarket.handOver(owner, takings);
        TrapLedger.record(owner, TrapLedger.Source.STALL, takings);
        save();
        return takings;
    }

    // --- the directory --------------------------------------------------------

    /**
     * /stalls -- who is selling, and where.
     *
     * The discovery half, and the reason the market screen can afford to be a
     * backstop rather than the only shop in town. A city of shops nobody can
     * find is a city of sheds.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("stalls")
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
        if (STALLS.isEmpty()) {
            who.sendMessage(Text.literal("Nobody's opened a stall yet.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        who.sendMessage(Text.literal("The Market Square").formatted(Formatting.GOLD, Formatting.BOLD),
                false);
        for (Stall stall : STALLS) {
            ServerWorld world = worldOf(who.getServer(), stall);
            int lines = world != null && loaded(world, stall.pos)
                    ? listing(world, stall).size() : -1;
            who.sendMessage(Text.literal("  " + stall.ownerName + "'s stall")
                    .formatted(Formatting.WHITE)
                    .append(Text.literal("  " + stall.pos.getX() + " " + stall.pos.getY()
                            + " " + stall.pos.getZ()).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(lines < 0 ? "  (shut)"
                                    : lines == 0 ? "  (empty)" : "  " + lines + " lines")
                            .formatted(lines > 0 ? Formatting.GREEN : Formatting.DARK_GRAY)),
                    false);
        }
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-stalls.txt");
        STALLS.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 7) {
                    continue;
                }
                Stall stall = new Stall(parts[0], new BlockPos(Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3])),
                        UUID.fromString(parts[4]), parts[5]);
                stall.till = Integer.parseInt(parts[6]);
                STALLS.add(stall);
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the stalls: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Stall stall : STALLS) {
                out.append(stall.dimension).append(' ')
                        .append(stall.pos.getX()).append(' ')
                        .append(stall.pos.getY()).append(' ')
                        .append(stall.pos.getZ()).append(' ')
                        .append(stall.owner).append(' ')
                        .append(stall.ownerName).append(' ')
                        .append(stall.till).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the stalls: {}", failure.toString());
        }
    }
}
