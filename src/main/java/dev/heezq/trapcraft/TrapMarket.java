package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The market: what things cost today, and why.
 *
 * Two forces. **Supply** is the money in circulation, smoothed over days --
 * the shop is the only sink on this server, so without it every price would
 * fall in real terms each time somebody farms a customer. **Drift** is a
 * per-item daily wobble so the board is worth reading rather than memorising.
 *
 * Both are deterministic for a given day, so two players standing at the same
 * stall are quoted the same number and can argue about it. See
 * {@link TrapMath#marketIndex} and {@link TrapMath#dailyDrift}.
 */
public final class TrapMarket {
    /** How much of today's reading is folded into the running average. */
    private static final float SMOOTHING = 0.25f;

    private static float supply = TrapMath.MARKET_BASELINE;
    private static long lastDay = -1;
    private static Path saveFile;

    private TrapMarket() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ShopStock.build();
            load(server);
            TrapInvest.load(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Once a second is plenty to notice the date changed.
            if (server.getOverworld().getTime() % 20 != 0) {
                return;
            }
            long day = today(server);
            if (day != lastDay) {
                roll(server, day);
            }
        });
    }

    public static long today(MinecraftServer server) {
        return server.getOverworld().getTimeOfDay() / 24000L;
    }

    /** Today's cost multiplier from the money supply. */
    public static float index() {
        return TrapMath.marketIndex(supply);
    }

    public static float supply() {
        return supply;
    }

    public static int buyPrice(MinecraftServer server, ShopStock.Entry entry) {
        return TrapMath.buyPrice(entry.base(), index(),
                TrapMath.dailyDrift(today(server), entry.id()));
    }

    public static int sellPrice(MinecraftServer server, ShopStock.Entry entry) {
        return TrapMath.sellPrice(buyPrice(server, entry));
    }

    /** How today's price compares with a flat day, as a percentage. */
    public static int movement(MinecraftServer server, ShopStock.Entry entry) {
        float d = TrapMath.dailyDrift(today(server), entry.id());
        return Math.round((d - 1.0f) * 100.0f);
    }

    // --- the daily roll -------------------------------------------------------

    /**
     * Re-read the money supply and open the new day.
     *
     * Counts what online players are carrying. That is a sample rather than a
     * census -- offline players' emeralds are invisible without reading their
     * NBT off disk, which is a lot of file IO for a number that only needs to
     * be roughly right. Smoothed so one player logging in with a full shulker
     * doesn't spike the whole economy.
     */
    private static void roll(MinecraftServer server, long day) {
        boolean firstEver = lastDay < 0;
        lastDay = day;

        float counted = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            counted += wealthOf(player);
        }
        // With nobody online there is nothing to sample, so leave it be rather
        // than letting the economy crash to zero overnight.
        if (!server.getPlayerManager().getPlayerList().isEmpty()) {
            supply = supply * (1 - SMOOTHING) + counted * SMOOTHING;
        }
        save();

        if (!firstEver) {
            announce(server);
        }
    }

    /** Emeralds plus emerald blocks, which are just nine emeralds in a coat. */
    public static int wealthOf(ServerPlayerEntity player) {
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.EMERALD)) {
                total += stack.getCount();
            } else if (stack.isOf(Items.EMERALD_BLOCK)) {
                total += stack.getCount() * 9;
            }
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
        return entry.item().getName().getString();
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
        if (owed < 0) {
            pay(player, -owed);   // change from the last broken block
        }
    }

    /**
     * Hand over emeralds, in blocks where it would otherwise be absurd.
     *
     * Selling a stack of diamonds should not bury you in loose emeralds.
     */
    public static void pay(ServerPlayerEntity player, int amount) {
        int blocks = amount / 9;
        int loose = amount % 9;
        // Below a stack of blocks it's friendlier to hand over singles --
        // people spend emeralds, they hoard blocks.
        if (amount < 64) {
            blocks = 0;
            loose = amount;
        }
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
            if (stack.isOf(entry.item())) {
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
            if (!stack.isOf(entry.item())) {
                continue;
            }
            int taken = Math.min(owed, stack.getCount());
            stack.decrement(taken);
            owed -= taken;
        }
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-market.txt");
        try {
            if (Files.exists(saveFile)) {
                String[] parts = Files.readString(saveFile).trim().split("\\s+");
                supply = Float.parseFloat(parts[0]);
                lastDay = Long.parseLong(parts[1]);
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
            Files.writeString(saveFile, supply + " " + lastDay);
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the market: {}", failure.toString());
        }
    }
}
