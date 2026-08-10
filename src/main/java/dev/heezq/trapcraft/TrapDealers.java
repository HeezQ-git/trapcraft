package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * People who sell it for you.
 *
 * Everything else in this mod is you standing somewhere doing something. A
 * dealer is the first thing that works while you are not there: you hand them
 * product, they go out and shift it, and the money is waiting when you call
 * them back. That changes what the rest of the mod is FOR -- a harvest stops
 * being something you carry to a stall and starts being inventory.
 *
 * They are not a machine, though, which is the other half. They take a cut,
 * they sell far better at night than at noon, they get in each other's way if
 * you put too many on the same patch, and the cheap ones get robbed.
 */
public final class TrapDealers {
    /** Ticks between selling rounds. Five minutes. */
    /**
     * Ninety seconds. Was two minutes, was five before that.
     *
     * The clock is most of what a dealer FEELS like: at five minutes a level
     * one sold one item every ten minutes, so most of a session went by with
     * the earnings line unchanged, and trade you cannot see moving is trade
     * nobody believes in. Two minutes fixed the worst of it; ninety seconds
     * shifts a third more product an hour and, more to the point, puts a sale
     * on the board often enough to look like a business.
     *
     * Robbery is per round, so {@link TrapMath#dealerRobChance} came down by
     * the same fraction. Shortening the clock has to make them work faster,
     * not get caught more.
     */
    private static final int ROUND_TICKS = 20 * 90;
    /** How long a called dealer waits around before going back to work. */
    private static final int VISIT_TICKS = 20 * 90;
    /** The most anybody can have on the books. */
    public static final int MAX_DEALERS = 4;
    /** Candidates on the board at once. */
    public static final int BOARD_SIZE = 3;
    /** What it costs to send the current lot away and ask around again. */
    public static final int REROLL_COST = 90;
    /** Ticks before the board turns over on its own. Ten minutes. */
    private static final int BOARD_TICKS = 20 * 60 * 10;

    private static final String TAG = "trapcraft_dealer";

    private static final String[] FIRST = {
            "Slim", "Dez", "Marco", "Rae", "Tunde", "Vic", "Nell", "Baz",
            "Kiro", "Ade", "Sana", "Pip", "Ozzy", "Mira", "Fitz", "Lex"};
    private static final String[] LAST = {
            "the Quiet", "Two-Phones", "the Clock", "Nightshift", "No-Show",
            "the Ghost", "Halfpipe", "Sunday", "the Mouse", "Rainman"};

    /**
     * One dealer on the books.
     *
     * Mutable on purpose: this is a long-lived record of somebody's stock and
     * earnings that gets edited from four different screens, and threading an
     * immutable copy through all of them would be ceremony around a HashMap.
     */
    public static final class Dealer {
        public final UUID id;
        public final UUID boss;
        public final String name;
        public int level;
        public int sold;
        public int earnings;
        /** What they're carrying. Product only. */
        public final List<ItemStack> stock = new ArrayList<>();
        /** Set while they're standing in front of you. */
        public UUID mob;
        public int calledAt;

        Dealer(UUID id, UUID boss, String name, int level) {
            this.id = id;
            this.boss = boss;
            this.name = name;
            this.level = level;
        }

        public int slots() {
            return TrapMath.dealerSlots(level);
        }

        public int carrying() {
            int total = 0;
            for (ItemStack stack : stock) {
                total += stack.getCount();
            }
            return total;
        }

        /** Items sold since the last level, and what the next one needs. */
        public int toNextLevel() {
            if (level >= TrapMath.DEALER_MAX_LEVEL) {
                return 0;
            }
            return Math.max(0, TrapMath.DEALER_XP[level] - sold);
        }
    }

    private static final List<Dealer> BOOK = new ArrayList<>();
    /** The current hiring board per player, refreshed when it runs dry. */
    private static final java.util.Map<UUID, List<Dealer>> OFFERS = new java.util.HashMap<>();
    /** When each player's board was last drawn, so it can go stale. */
    private static final java.util.Map<UUID, Integer> DRAWN = new java.util.HashMap<>();
    private static Path saveFile;

    private TrapDealers() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapDealers::load);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % ROUND_TICKS == 0) {
                round(server);
            }
            if (server.getTicks() % 20 == 0) {
                sendHomeIfDone(server);
            }
        });
        // Right-click one to open their book. Same pattern as the customers,
        // and for the same reason: no vanilla trade screen can show this.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
                (player, world, hand, entity, hit) -> {
                    if (world.isClient() || hand != net.minecraft.util.Hand.MAIN_HAND
                            || !(entity instanceof VillagerEntity villager)
                            || !villager.getCommandTags().contains(TAG)
                            || !(player instanceof ServerPlayerEntity boss)) {
                        return net.minecraft.util.ActionResult.PASS;
                    }
                    Dealer dealer = byMob(villager.getUuid());
                    if (dealer == null || !dealer.boss.equals(boss.getUuid())) {
                        return net.minecraft.util.ActionResult.PASS;
                    }
                    open(boss, dealer);
                    return net.minecraft.util.ActionResult.SUCCESS;
                });
    }

    public static void open(ServerPlayerEntity boss, Dealer dealer) {
        boss.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new DealerScreenHandler(syncId, inventory, dealer),
                Text.literal(dealer.name + "  ·  L" + dealer.level)
                        .formatted(Formatting.GOLD, Formatting.BOLD)));
    }

    // --- the books ------------------------------------------------------------

    public static List<Dealer> of(ServerPlayerEntity boss) {
        List<Dealer> mine = new ArrayList<>();
        for (Dealer dealer : BOOK) {
            if (dealer.boss.equals(boss.getUuid())) {
                mine.add(dealer);
            }
        }
        return mine;
    }

    private static Dealer byMob(UUID mob) {
        for (Dealer dealer : BOOK) {
            if (mob.equals(dealer.mob)) {
                return dealer;
            }
        }
        return null;
    }

    /**
     * Who's going tonight, for the phone's hiring board.
     *
     * Held per player until one is hired, so the list doesn't reshuffle under
     * somebody's cursor while they're deciding.
     */
    public static List<Dealer> board(ServerPlayerEntity boss) {
        int now = boss.getServer().getTicks();
        Integer drawn = DRAWN.get(boss.getUuid());
        // Turns over on its own, so the same three faces aren't there all
        // week -- but only while nobody is looking at it, since a board that
        // reshuffles under your cursor is worse than a stale one.
        // The board cannot get BIGGER -- it is drawn as a hopper and a hopper
        // is five slots -- so a busy city turns it over faster instead. Same
        // effect on how much work there is, no new container type.
        int stale = Math.max(BOARD_TICKS / 3,
                BOARD_TICKS - TrapHomes.population() * 20 * 20);
        if (drawn == null || now - drawn > stale) {
            reroll(boss, false);
        }
        return OFFERS.getOrDefault(boss.getUuid(), List.of());
    }

    /**
     * Draw a fresh three.
     *
     * The level spread widens with your reputation: a nobody gets whoever is
     * hanging about, and somebody with a name gets introduced to people worth
     * hiring. That is rep doing work in a second place, which is the point of
     * having contracts and dealers in the same mod rather than side by side.
     */
    public static void reroll(ServerPlayerEntity boss, boolean paid) {
        var random = boss.getWorld().getRandom();
        int rep = TrapContracts.repOf(TrapContracts.findPhone(boss));
        int ceiling = Math.min(TrapMath.DEALER_MAX_LEVEL, 3 + rep / 10);

        List<Dealer> offers = new ArrayList<>();
        for (int i = 0; i < BOARD_SIZE; i++) {
            String name = FIRST[random.nextInt(FIRST.length)] + " "
                    + LAST[random.nextInt(LAST.length)];
            offers.add(new Dealer(UUID.randomUUID(), boss.getUuid(), name,
                    1 + random.nextInt(ceiling)));
        }
        OFFERS.put(boss.getUuid(), offers);
        DRAWN.put(boss.getUuid(), boss.getServer().getTicks());
        if (paid) {
            boss.sendMessage(Text.literal("Asked around again.").formatted(Formatting.GRAY),
                    false);
        }
    }

    /** @return why it didn't happen, or null if the board turned over */
    public static String payToReroll(ServerPlayerEntity boss) {
        if (TrapMarket.wealthOf(boss) < REROLL_COST) {
            return "Asking around costs " + REROLL_COST + "e.";
        }
        TrapMarket.take(boss, REROLL_COST);
        reroll(boss, true);
        return null;
    }

    /** @return why it didn't happen, or null if it did */
    public static String hire(ServerPlayerEntity boss, Dealer offer) {
        if (of(boss).size() >= MAX_DEALERS) {
            return "You can't run more than " + MAX_DEALERS + " at once.";
        }
        int cost = TrapMath.dealerHireCost(offer.level,
                TrapContracts.repOf(TrapContracts.findPhone(boss)));
        if (TrapMarket.wealthOf(boss) < cost) {
            return offer.name + " wants " + cost + "e up front.";
        }
        TrapMarket.take(boss, cost);
        BOOK.add(offer);
        OFFERS.remove(boss.getUuid());
        DRAWN.remove(boss.getUuid());
        save();

        boss.sendMessage(Text.literal(offer.name).formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(" is on. Call them in and load them up.")
                        .formatted(Formatting.GRAY)), false);
        TrapAwards.grant(boss, "network");
        return null;
    }

    /** Let one go. Whatever they're holding comes back with them. */
    public static void drop(ServerPlayerEntity boss, Dealer dealer) {
        for (ItemStack stack : dealer.stock) {
            boss.getInventory().offerOrDrop(stack.copy());
        }
        if (dealer.earnings > 0) {
            TrapLaw.payDirty(boss, dealer.earnings);
        }
        sendHome(boss.getServer(), dealer);
        BOOK.remove(dealer);
        save();
        boss.sendMessage(Text.literal(dealer.name + " is done. Gear and takings returned.")
                .formatted(Formatting.GRAY), false);
    }

    // --- calling them in ------------------------------------------------------

    /** @return why it didn't happen, or null if they're on their way */
    public static String call(ServerPlayerEntity boss, Dealer dealer) {
        if (dealer.mob != null) {
            // Only refuse if he's really standing there. If the body is gone --
            // killed by something before AI was disabled, lost to a chunk
            // wipe, whatever -- the record would otherwise wedge forever with
            // no way to call him back.
            if (findBody(boss.getServer(), dealer) != null) {
                return dealer.name + " is already here.";
            }
            dealer.mob = null;
        }
        ServerWorld world = boss.getWorld();
        VillagerEntity body = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (body == null) {
            return "Couldn't reach them.";
        }
        BlockPos spot = boss.getBlockPos().add(
                world.getRandom().nextInt(7) - 3, 0, world.getRandom().nextInt(7) - 3);
        body.refreshPositionAndAngles(spot, boss.getYaw(), 0.0F);
        body.setPersistent();
        // Standing still and unkillable while they're here. A dealer who
        // wanders off down a ravine with your stock in his pockets is a
        // disaster with no recovery, and he's only here for a minute anyway.
        body.setAiDisabled(true);
        body.setInvulnerable(true);
        body.setSilent(true);
        body.setCustomName(Text.literal(dealer.name + "  L" + dealer.level)
                .formatted(Formatting.GOLD));
        body.setCustomNameVisible(true);
        // NITWIT for the same reason as the crew: anything else takes a job at
        // the first workstation it passes and starts trading.
        body.setVillagerData(body.getVillagerData().withProfession(
                world.getRegistryManager().getOrThrow(RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(VillagerProfession.NITWIT)));
        body.addCommandTag(TAG);
        world.spawnEntity(body);

        dealer.mob = body.getUuid();
        dealer.calledAt = boss.getServer().getTicks();
        world.playSound(null, spot, SoundEvents.ENTITY_VILLAGER_AMBIENT,
                SoundCategory.NEUTRAL, 0.9F, 1.0F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                spot.getX() + 0.5, spot.getY() + 1.5, spot.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.01);
        boss.sendMessage(Text.literal(dealer.name).formatted(Formatting.GOLD)
                .append(Text.literal(" turned up. Right-click to open the book.")
                        .formatted(Formatting.GRAY)), false);
        return null;
    }

    /** Called dealers don't hang about: they've got a round to be getting on with. */
    private static void sendHomeIfDone(MinecraftServer server) {
        for (Dealer dealer : BOOK) {
            if (dealer.mob != null && server.getTicks() - dealer.calledAt > VISIT_TICKS) {
                sendHome(server, dealer);
            }
        }
    }

    private static VillagerEntity findBody(MinecraftServer server, Dealer dealer) {
        if (dealer.mob == null) {
            return null;
        }
        for (ServerWorld world : server.getWorlds()) {
            if (world.getEntity(dealer.mob) instanceof VillagerEntity body) {
                return body;
            }
        }
        return null;
    }

    private static void sendHome(MinecraftServer server, Dealer dealer) {
        VillagerEntity body = findBody(server, dealer);
        if (body != null) {
            body.getWorld().playSound(null, body.getBlockPos(),
                    SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 0.7F, 1.2F);
            ((ServerWorld) body.getWorld()).spawnParticles(ParticleTypes.POOF,
                    body.getX(), body.getY() + 0.8, body.getZ(), 12, 0.25, 0.4, 0.25, 0.01);
            body.discard();
        }
        dealer.mob = null;
    }

    /** Send them back to work now, from the button in their book. */
    public static void sendOut(ServerPlayerEntity boss, Dealer dealer) {
        sendHome(boss.getServer(), dealer);
        save();
        boss.sendMessage(Text.literal(dealer.name).formatted(Formatting.GOLD)
                .append(Text.literal(dealer.stock.isEmpty()
                                ? " went back out. They've got nothing to sell, mind."
                                : " went back out with " + dealer.carrying() + " on them.")
                        .formatted(dealer.stock.isEmpty()
                                ? Formatting.RED : Formatting.GRAY)), false);
    }

    // --- the round ------------------------------------------------------------

    /**
     * Everybody sells a bit.
     *
     * A dealer standing in front of you sells nothing -- they are here, not out
     * there -- which is the quiet cost of calling one in and the reason not to
     * leave them parked.
     */
    /** Dealers on everybody's books, for the city's balance log. */
    public static int count() {
        return BOOK.size();
    }

    private static void round(MinecraftServer server) {
        long timeOfDay = server.getOverworld().getTimeOfDay() % 24000L;
        float hour = TrapMath.dealerHourFactor(timeOfDay);
        var random = server.getOverworld().getRandom();
        boolean changed = false;

        for (Dealer dealer : BOOK) {
            if (dealer.mob != null || dealer.stock.isEmpty()) {
                continue;
            }
            ServerPlayerEntity boss = server.getPlayerManager().getPlayer(dealer.boss);
            int crowd = 0;
            for (Dealer other : BOOK) {
                if (other.boss.equals(dealer.boss) && other.mob == null
                        && !other.stock.isEmpty()) {
                    crowd++;
                }
            }
            int heat = boss == null ? 0 : TrapHeat.carryingHeat(boss);
            // Offline is worth nothing here, the same way heat is: a boss who
            // isn't in the world isn't a name being said in it either.
            int rep = boss == null ? 0 : TrapContracts.repOf(TrapContracts.findPhone(boss));

            float rate = TrapMath.dealerRate(dealer.level, crowd, heat, rep);
            int wanted = TrapMath.dealerSold(rate, hour, random.nextFloat());
            if (wanted > 0) {
                changed |= sell(dealer, wanted, boss);
            }
            if (random.nextFloat() < TrapMath.dealerRobChance(dealer.level)) {
                changed |= robbed(dealer, boss, random);
            }
        }
        if (changed) {
            save();
        }
    }

    /** Shift some product at street prices, minus their cut. */
    private static boolean sell(Dealer dealer, int wanted, ServerPlayerEntity boss) {
        int gross = 0;
        int moved = 0;
        for (int i = 0; i < dealer.stock.size() && moved < wanted; i++) {
            ItemStack stack = dealer.stock.get(i);
            int take = Math.min(wanted - moved, stack.getCount());
            gross += TrapDealing.streetPrice(stack) * take;
            stack.decrement(take);
            moved += take;
        }
        dealer.stock.removeIf(ItemStack::isEmpty);
        if (moved <= 0) {
            return false;
        }
        int cut = Math.round(gross * TrapMath.dealerCut(dealer.level));
        dealer.earnings += Math.max(0, gross - cut);
        // Rep opens doors from this end too: a dealer working for somebody
        // with a name gets introduced to people a nobody's dealer has to find
        // alone, so they learn the streets faster.
        int rep = boss == null ? 0 : TrapContracts.repOf(TrapContracts.findPhone(boss));
        dealer.sold += Math.max(1, Math.round(moved * TrapMath.dealerLearnRate(rep)));

        // Product moving on the street is exactly the sort of thing that gets
        // noticed. Selling shortens the wait before the next patrol, which is
        // what ties this to the raids instead of leaving them two features
        // that happen to share a world.
        if (boss != null) {
            TrapHeat.stirTheStreet(boss.getWorld(), moved);
        }

        // Level up on the way past, so it lands the moment it's earned rather
        // than the next time somebody opens a screen.
        while (dealer.level < TrapMath.DEALER_MAX_LEVEL
                && dealer.sold >= TrapMath.DEALER_XP[dealer.level]) {
            dealer.level++;
            if (boss != null) {
                boss.sendMessage(Text.literal(dealer.name).formatted(Formatting.GOLD,
                                Formatting.BOLD)
                        .append(Text.literal(" moved up to level " + dealer.level + ". Room for "
                                + TrapMath.dealerSlots(dealer.level) + " slots now.")
                                .formatted(Formatting.GREEN)), false);
                TrapAwards.grant(boss, "kingpin");
            }
        }
        return true;
    }

    /** Somebody took a share off them. */
    private static boolean robbed(Dealer dealer, ServerPlayerEntity boss,
                                  net.minecraft.util.math.random.Random random) {
        if (dealer.stock.isEmpty()) {
            return false;
        }
        ItemStack hit = dealer.stock.get(random.nextInt(dealer.stock.size()));
        int taken = Math.max(1, hit.getCount() / 2);
        Text what = hit.getName();
        hit.decrement(taken);
        dealer.stock.removeIf(ItemStack::isEmpty);

        if (boss != null) {
            boss.sendMessage(Text.literal(dealer.name).formatted(Formatting.RED, Formatting.BOLD)
                    .append(Text.literal(" got jumped. Lost ").formatted(Formatting.GRAY))
                    .append(Text.literal(taken + "x ").formatted(Formatting.WHITE))
                    .append(what)
                    .append(Text.literal(dealer.level < 3
                                    ? ".  A better dealer gets robbed less." : ".")
                            .formatted(Formatting.DARK_GRAY)), false);
        }
        return true;
    }

    // --- persistence ----------------------------------------------------------

    /**
     * One line per dealer, stock inlined.
     *
     * Dealers only ever carry product -- item, count and grade -- so a stack
     * fits in three fields and the whole book stays a text file anybody can
     * read, same as everything else this mod saves. A general itemstack would
     * have meant NBT and a binary blob.
     */
    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (Dealer dealer : BOOK) {
                StringBuilder line = new StringBuilder();
                line.append(dealer.id).append(' ').append(dealer.boss).append(' ')
                        .append(dealer.level).append(' ').append(dealer.sold).append(' ')
                        .append(dealer.earnings).append(' ')
                        .append(dealer.name.replace(' ', '_'));
                for (ItemStack stack : dealer.stock) {
                    line.append(' ').append(Registries.ITEM.getId(stack.getItem()))
                            .append('|').append(stack.getCount())
                            .append('|').append(TrapComponents.get(stack).index());
                }
                lines.add(line.toString());
            }
            Files.write(saveFile, lines);
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't save dealers: {}", failure.toString());
        }
    }

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-dealers.txt");
        BOOK.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 6) {
                    continue;
                }
                Dealer dealer = new Dealer(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                        parts[5].replace('_', ' '), Integer.parseInt(parts[2]));
                dealer.sold = Integer.parseInt(parts[3]);
                dealer.earnings = Integer.parseInt(parts[4]);
                for (int i = 6; i < parts.length; i++) {
                    String[] bits = parts[i].split("\\|");
                    if (bits.length != 3) {
                        continue;
                    }
                    Item item = Registries.ITEM.getOptionalValue(Identifier.of(bits[0]))
                            .orElse(null);
                    if (item == null) {
                        continue;
                    }
                    dealer.stock.add(TrapComponents.apply(
                            new ItemStack(item, Integer.parseInt(bits[1])),
                            Quality.byIndex(Integer.parseInt(bits[2]))));
                }
                BOOK.add(dealer);
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't read dealers -- stock may be lost: {}",
                    failure.toString());
        }
    }

    /** Called from the screens whenever a dealer's stock or takings change. */
    public static void touch() {
        save();
    }
}
