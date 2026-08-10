package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The market: what things cost right now, and why.
 *
 * Three forces, multiplied together.
 *
 * **Supply** is the money in circulation. It moves the whole board at once and
 * it moves for real reasons: every emerald spent at the shop, fed to a slot
 * machine or put into the exchange leaves circulation, and every emerald paid
 * out by a customer, a contract, a jackpot or a matured position enters it. So
 * a jackpot really is inflationary, and a quiet week really does make things
 * cheap. It is also re-sampled from what players are carrying, which keeps it
 * anchored to the world rather than to our own bookkeeping.
 *
 * **Drift** is each item walking between random targets, so prices creep while
 * you shop instead of standing still until tomorrow.
 *
 * **Pressure** is order flow: buying an item pushes its own price up, selling
 * pushes it down, and it fades over the following minutes. This is the force
 * players can actually steer, and the reason clearing a shelf costs more at
 * the end than at the start.
 *
 * All three are deterministic given the beat, so two players standing at the
 * same stall are quoted the same number. See {@link TrapMath#marketIndex},
 * {@link TrapMath#drift} and {@link TrapMath#pressureAfter}.
 */
public final class TrapMarket {
    /** How much of a fresh reading is folded into the running average. */
    private static final float SMOOTHING = 0.08f;
    /** Ticks between beats. The market moves twice a minute. */
    private static final int BEAT_TICKS = 600;
    /** An index move worth interrupting people about. */
    private static final float SHOCK = 0.06f;

    private static float supply = TrapMath.MARKET_BASELINE;
    /**
     * What the supply is currently considered "normal", which the index is
     * measured against. Follows the supply at a crawl -- see
     * {@link TrapMath#baselineAfter}.
     */
    private static float baseline = TrapMath.MARKET_BASELINE;
    private static long beat = 0;
    private static long lastDay = -1;
    /** Order flow per item id. Absent means settled. */
    private static final Map<String, Float> PRESSURE = new HashMap<>();
    /**
     * What each chunk was last seen holding in its containers.
     *
     * Keyed by CHUNK rather than by player, which matters as soon as two
     * people share a base: keyed by player they would each report the same
     * chests and the money supply would count them twice.
     *
     * Remembered rather than recomputed on demand, because a stash doesn't
     * stop existing when its owner walks away, and it certainly doesn't stop
     * existing when they log off. Entries survive restarts, so emeralds banked
     * by someone who never logs in again are still part of the pool -- which
     * is the truth of it.
     */
    private static final Map<String, Integer> VAULTS = new HashMap<>();
    /** Beats between container censuses. Four minutes. */
    private static final int CENSUS_EVERY = 8;
    /** How far around a player their stash is looked for. */
    private static final int VAULT_RADIUS = 48;
    private static Path saveFile;

    private TrapMarket() {
    }

    public static void register() {
        registerCommand();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ShopStock.build(server);
            load(server);
            TrapInvest.load(server);
            TrapCoins.load(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % BEAT_TICKS != 0) {
                return;
            }
            beat++;
            if (beat % CENSUS_EVERY == 0) {
                census(server);
            }
            resample(server);
            settle();
            ShopScreenHandler.refreshAll();
            save();

            long day = today(server);
            if (day != lastDay) {
                boolean firstEver = lastDay < 0;
                lastDay = day;
                if (!firstEver) {
                    announce(server);
                }
            }
        });
    }

    /**
     * /market -- why everything costs what it costs.
     *
     * Same reason /heat exists. An index pinned at its cap and an index
     * sitting quietly at 1.0 look identical from inside the game: you see
     * prices, you don't see why, and "the market feels wrong" is not
     * something anybody can act on. This prints the three numbers the whole
     * board is computed from, so the next time it feels wrong there is
     * something to point at.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("market")
                                .executes(context -> {
                                    report(context.getSource());
                                    return 1;
                                })));
    }

    private static void report(net.minecraft.server.command.ServerCommandSource source) {
        float index = index();
        int percent = Math.round((index - 1.0f) * 100.0f);
        boolean pinned = index >= TrapMath.INDEX_MAX - 0.001f
                || index <= TrapMath.INDEX_MIN + 0.001f;

        String mood = percent > 25 ? "Everything's dear."
                : percent < -15 ? "Money's tight, so prices are soft."
                : "Prices are about normal.";

        Text line = Text.literal("Market  ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(mood).formatted(Formatting.GRAY))
                .append(Text.literal("\n  Everything costs ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal((percent >= 0 ? "+" : "") + percent + "%")
                        .formatted(percent > 0 ? Formatting.RED
                                : percent < 0 ? Formatting.GREEN : Formatting.WHITE))
                .append(Text.literal(pinned ? "  (at the limit)" : "")
                        .formatted(Formatting.RED))
                .append(Text.literal("\n  " + Math.round(supply) + "e about, against "
                                + Math.round(baseline) + "e of normal")
                        .formatted(Formatting.DARK_GRAY))
                .append(Text.literal("\n  " + (supply > baseline
                                ? "Easing back as the new normal sets in."
                                : supply < baseline
                                ? "Climbing back as the money drains away."
                                : "Settled."))
                        .formatted(Formatting.DARK_GRAY))
                .append(Text.literal("\n  " + PRESSURE.size() + " lines still moving from trade")
                        .formatted(Formatting.DARK_GRAY));
        source.sendFeedback(() -> line, false);
    }

    public static long today(MinecraftServer server) {
        return server.getOverworld().getTimeOfDay() / 24000L;
    }

    /** The current cost multiplier from the money supply. */
    public static float index() {
        return TrapMath.marketIndex(supply, baseline);
    }

    public static float supply() {
        return supply;
    }

    /** What the market currently thinks a normal amount of money is. */
    public static float baseline() {
        return baseline;
    }

    public static long beat() {
        return beat;
    }

    /** How hard this line has been traded lately, as a multiplier around 1. */
    public static float pressureOf(ShopStock.Entry entry) {
        return PRESSURE.getOrDefault(entry.id(), 0.0f);
    }

    public static int buyPrice(MinecraftServer server, ShopStock.Entry entry) {
        return TrapMath.buyPrice(entry.base(), index(),
                TrapMath.drift(beat, entry.id()),
                TrapMath.flowFactor(pressureOf(entry)));
    }

    public static int sellPrice(MinecraftServer server, ShopStock.Entry entry) {
        return TrapMath.sellPrice(buyPrice(server, entry));
    }

    /**
     * How this line's price compares with its own flat price, as a percentage.
     *
     * Deliberately excludes the index: that moves everything together and is
     * therefore not news about this item. Drift and order flow are.
     */
    public static int movement(MinecraftServer server, ShopStock.Entry entry) {
        float own = TrapMath.drift(beat, entry.id())
                * TrapMath.flowFactor(pressureOf(entry));
        return Math.round((own - 1.0f) * 100.0f);
    }

    // --- what players do to it -----------------------------------------------

    /**
     * Record emeralds entering (+) or leaving (-) players' hands.
     *
     * Called from one place -- {@link #take} and {@link #pay} -- because every
     * emerald this mod moves goes through those two methods. That is what
     * makes the shop, the machines, the exchange, the customers and the
     * contracts one economy instead of five features that happen to use the
     * same currency.
     */
    private static void circulate(int delta) {
        float before = index();
        supply = TrapMath.circulated(supply, delta);
        float after = index();
        if (Math.abs(after - before) >= SHOCK) {
            shock(after > before);
        }
    }

    /** Somebody moved enough money to be felt. Tell the room. */
    private static void shock(boolean up) {
        MinecraftServer server = lastServer;
        if (server == null) {
            return;
        }
        Text line = Text.literal("Market  ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(up
                                ? "Somebody just got paid. Prices are climbing."
                                : "A lot of money just left the room. Prices are easing.")
                        .formatted(Formatting.GRAY));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(line, true);
        }
    }

    /** Log an order against one line so its own price responds. */
    public static void traded(ShopStock.Entry entry, int lots, boolean buying) {
        float moved = TrapMath.pressureAfter(pressureOf(entry), lots, buying);
        if (moved == 0.0f) {
            PRESSURE.remove(entry.id());
        } else {
            PRESSURE.put(entry.id(), moved);
        }
    }

    /** One beat of everybody's orders being forgotten. */
    private static void settle() {
        List<String> spent = new ArrayList<>();
        PRESSURE.replaceAll((id, held) -> TrapMath.relax(held));
        PRESSURE.forEach((id, held) -> {
            if (held == 0.0f) {
                spent.add(id);
            }
        });
        spent.forEach(PRESSURE::remove);
    }

    // --- the daily roll -------------------------------------------------------

    /**
     * Emeralds the world is holding that are not in anybody's inventory.
     *
     * Every one of these is parked player money, exactly like a chest full of
     * emeralds, and every one of them HAS to be here for the resample to
     * close: money moved into a float, a till or a purse goes through
     * {@link #collect} rather than {@link #take}, so it was never reported as
     * destroyed -- and if the resample then failed to find it, the supply
     * would fall by the whole balance the moment somebody opened a casino, a
     * shop or a city.
     *
     * One method rather than four call sites, because this list only ever
     * grows and the failure it prevents is completely silent: the index drifts
     * and everybody's prices move for a reason nobody can name.
     */
    private static float heldElsewhere() {
        return TrapHouse.floatHeld() + TrapCity.treasury()
                + TrapStalls.tillsHeld() + TrapShops.tillsHeld();
    }

    /**
     * Money that arrived without a pocket to arrive in.
     *
     * A townsperson who walks into a shop and buys a loaf brings emeralds into
     * the world the way a customer buying a joint does. The difference is that
     * none of it lands on a player at the time -- it splits between the shop's
     * till and the city's purse -- so it cannot ride in on {@link #pay}.
     */
    public static void minted(int amount) {
        circulate(amount);
    }

    /**
     * Re-read the money supply from what people are carrying.
     *
     * Our own bookkeeping in {@link #circulate} is the fast, reactive half;
     * this is the slow, honest half. Emeralds also arrive by mining, mob drops,
     * villager trades and creative mode, and leave down lava and on death, and
     * none of that goes through us. Without the resample the index would drift
     * away from the world it is supposed to describe.
     *
     * Counts online players only -- offline emeralds mean reading player NBT
     * off disk, which is a lot of IO for a number that need only be roughly
     * right. Smoothed hard, because it now runs twice a minute and one player
     * logging in with a full shulker should not be an economic event.
     */
    private static void resample(MinecraftServer server) {
        lastServer = server;
        var online = server.getPlayerManager().getPlayerList();
        // With nobody online there is nothing to sample, so leave it be rather
        // than letting the economy crash to zero while everyone sleeps.
        if (online.isEmpty()) {
            return;
        }
        float counted = 0;
        for (ServerPlayerEntity player : online) {
            counted += wealthOf(player);
        }
        for (int stashed : VAULTS.values()) {
            counted += stashed;
        }
        counted += heldElsewhere();
        supply = supply * (1 - SMOOTHING) + counted * SMOOTHING;
        // Inside resample so it inherits the nobody-online guard: an anchor
        // that kept drifting overnight would have the whole server wake up to
        // a market that had quietly decided their savings were normal.
        baseline = TrapMath.baselineAfter(baseline, supply);
    }

    /**
     * Count what people have put away in chests.
     *
     * Emeralds in a chest are still emeralds -- they are part of the pool
     * whether they're in your pocket or on a shelf in your base -- so the
     * index has to see them or it is measuring petty cash and calling it the
     * economy.
     *
     * There is no way to walk every loaded chunk in the world, so this walks
     * the chunks around each online player, which is where bases are. The
     * reading is REMEMBERED per player rather than summed fresh, so wandering
     * off doesn't crash the economy and logging off doesn't delete your
     * savings. Run every few minutes, not every beat: it is the expensive one.
     */
    private static void census(MinecraftServer server) {
        int chunksRead = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = player.getWorld();
            String dimension = world.getRegistryKey().getValue().toString();
            BlockPos centre = player.getBlockPos();

            for (int cx = (centre.getX() - VAULT_RADIUS) >> 4;
                 cx <= (centre.getX() + VAULT_RADIUS) >> 4; cx++) {
                for (int cz = (centre.getZ() - VAULT_RADIUS) >> 4;
                     cz <= (centre.getZ() + VAULT_RADIUS) >> 4; cz++) {
                    WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    chunksRead++;
                    int found = 0;
                    for (BlockEntity block : chunk.getBlockEntities().values()) {
                        if (block instanceof Inventory container) {
                            found += moneyIn(container);
                        }
                    }
                    // Recorded per chunk we actually looked at, so emptying a
                    // chest shows up as the money leaving rather than being
                    // remembered forever.
                    String key = dimension + " " + cx + " " + cz;
                    if (found > 0) {
                        VAULTS.put(key, found);
                    } else {
                        VAULTS.remove(key);
                    }
                }
            }
        }
        // Instrumented on purpose: "no stashes found" and "the scan never ran"
        // look identical from outside, and an empty vault ledger is exactly
        // what a broken census produces.
        int stashed = 0;
        for (int held : VAULTS.values()) {
            stashed += held;
        }
        TrapCraft.LOGGER.info("census: {} chunks read, {} chunks holding money, {}e stashed",
                chunksRead, VAULTS.size(), stashed);
    }

    /** What one container is holding, shulker boxes on the shelf included. */
    private static int moneyIn(Inventory container) {
        int total = 0;
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.getStack(slot);
            total += valueOf(stack);
            // A shulker box of emeralds in a chest is still a chest full of
            // emeralds. One level down is enough -- shulkers don't nest.
            var packed = stack.get(DataComponentTypes.CONTAINER);
            if (packed != null) {
                for (ItemStack inside : packed.stream().toList()) {
                    total += valueOf(inside);
                }
            }
        }
        return total;
    }

    /** Whoever ticked us last, so a price shock can find the player list. */
    private static MinecraftServer lastServer;

    /**
     * What one stack is worth as money.
     *
     * An emerald block is nine emeralds in a coat, and a wallet is however
     * many you put in it. Everything that counts money -- the purse, the
     * shelves, the world census -- goes through here, so there is one answer
     * to "is this money" rather than three that can disagree.
     */
    public static int valueOf(ItemStack stack) {
        if (stack.isOf(Items.EMERALD)) {
            return stack.getCount();
        }
        if (stack.isOf(Items.EMERALD_BLOCK)) {
            return stack.getCount() * 9;
        }
        if (stack.isOf(TrapContent.wallet)) {
            return WalletItem.balanceOf(stack);
        }
        return 0;
    }

    /** Everything the player can spend, wallets included. */
    public static int wealthOf(ServerPlayerEntity player) {
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            total += valueOf(inventory.getStack(slot));
        }
        return total;
    }

    /**
     * The morning report.
     *
     * Names the two biggest movers rather than listing everything: a wall of
     * percentages gets skipped, one line about diamonds being up gets read.
     */
    private static void announce(MinecraftServer server) {
        List<ShopStock.Entry> stock = ShopStock.all();
        ShopStock.Entry best = null;
        ShopStock.Entry worst = null;
        for (ShopStock.Entry entry : stock) {
            int move = movement(server, entry);
            if (best == null || move > movement(server, best)) {
                best = entry;
            }
            if (worst == null || move < movement(server, worst)) {
                worst = entry;
            }
        }
        if (best == null || worst == null) {
            return;
        }

        float index = index();
        String mood = index > 1.25f ? "Everything's dear today."
                : index < 0.85f ? "Money's tight, so prices are soft."
                : "Prices are about where you left them.";

        Text line = Text.literal("").append(Text.literal("Market  ")
                        .formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(mood + " ").formatted(Formatting.GRAY))
                .append(Text.literal(name(best)).formatted(Formatting.WHITE))
                .append(Text.literal(" up " + movement(server, best) + "%")
                        .formatted(Formatting.GREEN))
                .append(Text.literal(", ").formatted(Formatting.GRAY))
                .append(Text.literal(name(worst)).formatted(Formatting.WHITE))
                .append(Text.literal(" down " + Math.abs(movement(server, worst)) + "%")
                        .formatted(Formatting.RED))
                .append(Text.literal(".").formatted(Formatting.GRAY));

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(line, false);
        }
    }

    private static String name(ShopStock.Entry entry) {
        return entry.label();
    }

    // --- the wallet -----------------------------------------------------------

    /**
     * Take a price out of a player's pockets, breaking blocks as needed.
     *
     * Emerald blocks count as money because the alternative is carrying
     * twenty-five stacks of emeralds to buy an elytra. Loose emeralds go
     * first, then blocks get broken, and whatever the last block overpays
     * comes straight back as change -- so paying 1150 out of blocks doesn't
     * quietly eat the remainder.
     *
     * Callers MUST check {@link #wealthOf} first; this assumes it can be paid.
     */
    public static void take(ServerPlayerEntity player, int amount) {
        circulate(-amount);
        collect(player, amount);
    }

    /**
     * Move emeralds off a player without touching the economy.
     *
     * Separate from {@link #take} because putting money in your own wallet is
     * not spending it -- the emeralds are still yours and still counted. Only
     * take/pay report to {@link #circulate}.
     */
    public static void collect(ServerPlayerEntity player, int amount) {
        var inventory = player.getInventory();
        int owed = amount;

        for (int slot = 0; slot < inventory.size() && owed > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.EMERALD)) {
                continue;
            }
            int taken = Math.min(owed, stack.getCount());
            stack.decrement(taken);
            owed -= taken;
        }
        for (int slot = 0; slot < inventory.size() && owed > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.EMERALD_BLOCK)) {
                continue;
            }
            int blocks = Math.min((owed + 8) / 9, stack.getCount());
            stack.decrement(blocks);
            owed -= blocks * 9;
        }
        // Then wallets, which is what makes them a purse rather than a
        // shoebox: money you've put away is still money you can spend.
        for (int slot = 0; slot < inventory.size() && owed > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(TrapContent.wallet)) {
                continue;
            }
            int drawn = Math.min(owed, WalletItem.balanceOf(stack));
            WalletItem.setBalance(stack, WalletItem.balanceOf(stack) - drawn);
            owed -= drawn;
        }
        if (owed < 0) {
            handOver(player, -owed);   // change from the last broken block
        }
    }

    /**
     * Hand over emeralds, in blocks where it would otherwise be absurd.
     *
     * Selling a stack of diamonds should not bury you in loose emeralds.
     */
    public static void pay(ServerPlayerEntity player, int amount) {
        circulate(amount);
        handOver(player, amount);
    }

    /** Hand over emeralds without touching the economy. See {@link #collect}. */
    public static void handOver(ServerPlayerEntity player, int amount) {
        int[] packed = TrapMath.packEmeralds(amount);
        int blocks = packed[0];
        int loose = packed[1];
        while (blocks > 0) {
            int give = Math.min(64, blocks);
            player.getInventory().offerOrDrop(new ItemStack(Items.EMERALD_BLOCK, give));
            blocks -= give;
        }
        while (loose > 0) {
            int give = Math.min(64, loose);
            player.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, give));
            loose -= give;
        }
    }

    /** How many whole bundles of this entry the player is carrying. */
    public static int bundlesHeld(ServerPlayerEntity player, ShopStock.Entry entry) {
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (entry.matches(stack)) {
                count += stack.getCount();
            }
        }
        return count / entry.count();
    }

    /** Remove whole bundles of an entry. Assumes bundlesHeld covered it. */
    public static void takeGoods(ServerPlayerEntity player, ShopStock.Entry entry, int bundles) {
        int owed = bundles * entry.count();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size() && owed > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!entry.matches(stack)) {
                continue;
            }
            int taken = Math.min(owed, stack.getCount());
            stack.decrement(taken);
            owed -= taken;
        }
    }

    // --- persistence ----------------------------------------------------------

    /**
     * Header line, then one line per item carrying order flow and one per
     * player's remembered stash.
     *
     * Settled lines aren't written, so the file stays the size of what is
     * actually being traded rather than the size of the catalogue. A file from
     * before pressure existed is a one-line file and still loads.
     */
    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-market.txt");
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            List<String> lines = Files.readAllLines(saveFile);
            String[] header = lines.get(0).trim().split("\\s+");
            supply = Float.parseFloat(header[0]);
            lastDay = Long.parseLong(header[1]);
            beat = header.length > 2 ? Long.parseLong(header[2]) : 0;
            // A file written before the anchor existed leaves it at the old
            // constant, which is exactly right: the market then eases down
            // over the next couple of hours of play instead of halving every
            // price on the board between one tick and the next. Players are
            // holding contracts and stock priced at the old numbers, and an
            // instant 46% correction is a worse bug than the one it fixes.
            baseline = header.length > 3
                    ? Float.parseFloat(header[3]) : TrapMath.MARKET_BASELINE;

            PRESSURE.clear();
            VAULTS.clear();
            for (String held : lines.subList(1, lines.size())) {
                String[] parts = held.trim().split("\\s+");
                if (parts.length == 5 && parts[0].equals("vault")) {
                    VAULTS.put(parts[1] + " " + parts[2] + " " + parts[3],
                            Integer.parseInt(parts[4]));
                } else if (parts.length == 2) {
                    PRESSURE.put(parts[0], Float.parseFloat(parts[1]));
                }
            }
        } catch (Exception failure) {
            // A corrupt file costs the market's memory, not the world.
            TrapCraft.LOGGER.warn("couldn't read the market: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder()
                    .append(supply).append(' ').append(lastDay).append(' ').append(beat)
                    .append(' ').append(baseline);
            PRESSURE.forEach((id, held) ->
                    out.append('\n').append(id).append(' ').append(held));
            // Tagged, because an item id always has a namespace colon in it
            // and so can never be mistaken for the word "vault".
            VAULTS.forEach((where, stashed) ->
                    out.append("\nvault ").append(where).append(' ').append(stashed));
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the market: {}", failure.toString());
        }
    }
}
