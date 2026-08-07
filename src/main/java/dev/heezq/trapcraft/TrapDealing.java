package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.predicate.component.ComponentMapPredicate;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Customers find you.
 *
 * The wandering trader already lets you SELL product, but you have to go and
 * find one, and it buys everything at a flat rate. This is the other side of
 * the counter: if you're carrying, somebody eventually turns up wanting it,
 * walks over, and pays well above the market because they're not in a position
 * to shop around.
 *
 * Each customer craves ONE thing. That's what makes a visit an event rather
 * than a vending machine -- sometimes they want exactly the Fire Purp you've
 * got, and sometimes they want Haze and you're holding Kush.
 *
 * Built on WanderingTraderEntity rather than a custom mob or a villager
 * profession. Villagers can't carry custom trades without a profession, and
 * VillagerProfession is a final record in 1.21.8 that can't be made
 * Polymer-safe -- see the note in {@link TrapTrades}. The trader is the one
 * vanilla entity that exists to be a temporary merchant, it already knows how
 * to despawn on a timer, and its trading screen needs no client code at all.
 */
public final class TrapDealing {
    /** Marks our customers so they're distinguishable from real traders. */
    public static final String TAG = "trapcraft_customer";

    /** How often the spawn roll happens. */
    private static final int CHECK_INTERVAL = 20 * 15;
    /** Chance per check, so roughly one visit every 7-8 minutes while holding. */
    private static final int SPAWN_CHANCE = 30;

    /** How long they'll hang around before giving up on you. */
    private static final int LIFETIME_TICKS = 20 * 180;

    private static final int SPAWN_MIN = 11;
    private static final int SPAWN_RANGE = 9;      // 11..20 blocks out
    private static final int GIVE_UP_DISTANCE = 48;

    /** They pay this much over the wandering trader for what they crave. */
    private static final float PREMIUM = 1.9F;
    /** How many times one customer will buy a given offer. */
    private static final int MAX_USES = 4;

    private record Customer(UUID player, int bornAt, Craving craving) {
    }

    /** Live customers, by entity id. In memory only -- see tick(). */
    private static final Map<UUID, Customer> CUSTOMERS = new HashMap<>();

    private TrapDealing() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TrapDealing::tick);
        registerCommand();
    }

    /**
     * /customer [strain|powder] -- summon one on demand.
     *
     * A visit is deliberately rare, which is good for play and terrible for
     * testing: the trade list was carrying vanilla's stock for who knows how
     * long precisely because nobody could reproduce a customer to look at.
     * Ops only, and it still refuses if you already have one.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> {
                    var root = net.minecraft.server.command.CommandManager.literal("customer")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> summon(context.getSource().getPlayer(), null));
                    for (Strain strain : Strain.values()) {
                        root.then(net.minecraft.server.command.CommandManager
                                .literal(strain.id())
                                .executes(context -> summon(context.getSource().getPlayer(),
                                        new Craving(strain, false))));
                    }
                    root.then(net.minecraft.server.command.CommandManager.literal("powder")
                            .executes(context -> summon(context.getSource().getPlayer(),
                                    new Craving(null, true))));
                    dispatcher.register(root);
                });
    }

    private static int summon(ServerPlayerEntity player, Craving craving) {
        if (player == null) {
            return 0;
        }
        if (hasCustomer(player)) {
            player.sendMessage(Text.literal("You've already got one on the way.")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        // No argument means "whatever I'm carrying", the same choice the random
        // path makes, so the default tests the real behaviour rather than a
        // special case.
        Craving wanted = craving != null ? craving : cravingFor(player);
        if (wanted == null) {
            player.sendMessage(Text.literal(
                            "Nobody wants nothing. Carry some product, or name a strain.")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        if (!visit(player, wanted, (int) player.getWorld().getTime())) {
            player.sendMessage(Text.literal("Nowhere for them to walk in from.")
                    .formatted(Formatting.RED), false);
            return 0;
        }
        return 1;
    }

    private static void tick(MinecraftServer server) {
        int now = server.getTicks();
        if (!CUSTOMERS.isEmpty()) {
            shepherd(server, now);
        }
        if (now % CHECK_INTERVAL != 0) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            maybeVisit(player, now);
        }
    }

    // --- arrival -------------------------------------------------------------

    private static void maybeVisit(ServerPlayerEntity player, int now) {
        if (hasCustomer(player)) {
            return;   // one at a time; a queue of junkies is a mob, not a deal
        }
        if (player.getWorld().getRandom().nextInt(SPAWN_CHANCE) != 0) {
            return;
        }

        // They can smell it. No product, no visit -- otherwise this is just a
        // random mob spawner bolted onto the mod.
        Craving craving = cravingFor(player);
        if (craving == null) {
            return;
        }
        visit(player, craving, now);
    }

    /**
     * Actually send somebody over.
     *
     * Split out from the random roll above so /customer can summon one without
     * a second copy of the spawn code -- a visit is rare by design, which is
     * exactly what made the trade list bug survive so long.
     */
    private static boolean visit(ServerPlayerEntity player, Craving craving, int now) {
        ServerWorld world = player.getWorld();
        BlockPos spot = findSpot(world, player.getBlockPos());
        if (spot == null) {
            return false;
        }

        WanderingTraderEntity customer =
                EntityType.WANDERING_TRADER.create(world, SpawnReason.EVENT);
        if (customer == null) {
            return false;
        }
        customer.refreshPositionAndAngles(spot, world.getRandom().nextFloat() * 360.0F, 0.0F);
        customer.setCustomName(Text.literal(craving.title())
                .formatted(Formatting.DARK_GREEN));
        customer.setCustomNameVisible(true);
        customer.addCommandTag(TAG);
        // Vanilla's own timer as a backstop: if the server restarts and our
        // in-memory record is lost, they still wander off on their own instead
        // of standing in a field forever.
        customer.setDespawnDelay(LIFETIME_TICKS + 20 * 30);

        world.spawnEntity(customer);
        // After spawning, not before: spawn can run the entity's own
        // initialisation, and this has to be the last word on what they'll buy.
        customer.setOffersFromServer(offersFor(craving));
        CUSTOMERS.put(customer.getUuid(), new Customer(player.getUuid(), now, craving));

        player.sendMessage(Text.literal(craving.greeting()).formatted(Formatting.GRAY), false);
        world.playSound(null, spot, SoundEvents.ENTITY_WANDERING_TRADER_YES,
                SoundCategory.NEUTRAL, 0.7F, 0.8F);
        return true;
    }

    /** A clear surface spot near the player but not on top of them. */
    private static BlockPos findSpot(ServerWorld world, BlockPos near) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = world.getRandom().nextDouble() * Math.PI * 2;
            int distance = SPAWN_MIN + world.getRandom().nextInt(SPAWN_RANGE);
            int x = near.getX() + (int) (Math.cos(angle) * distance);
            int z = near.getZ() + (int) (Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            BlockPos spot = new BlockPos(x, world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z), z);
            if (world.getBlockState(spot).isAir() && world.getBlockState(spot.up()).isAir()) {
                return spot;
            }
        }
        return null;
    }

    // --- the approach --------------------------------------------------------

    /**
     * Walk them toward their customer, and get rid of them when it's over.
     *
     * Re-issuing a navigation target beats adding an AI goal: goalSelector is
     * protected and would need an accessor mixin for one behaviour that amounts
     * to "walk that way", and a path recomputed every second and a half tracks
     * a moving player perfectly well.
     */
    private static void shepherd(MinecraftServer server, int now) {
        CUSTOMERS.entrySet().removeIf(entry -> {
            Customer record = entry.getValue();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(record.player());
            var entity = findCustomer(server, entry.getKey());

            if (entity == null) {
                return true;    // killed, despawned, or unloaded
            }
            if (player == null || player.getWorld() != entity.getWorld()
                    || now - record.bornAt() > LIFETIME_TICKS
                    || !entity.getBlockPos().isWithinDistance(player.getBlockPos(), GIVE_UP_DISTANCE)) {
                leave(entity);
                return true;
            }

            // Wandering traders drink an invisibility potion at night. That's
            // fine for a vanilla trader wandering the wilds and useless for
            // somebody who is supposed to be walking up to you, so it gets
            // stripped every pass rather than fought at the AI level.
            entity.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.INVISIBILITY);

            // Re-assert what they buy.
            //
            // Setting the offers once at spawn is not enough: the wandering
            // trader fills its OWN stock lazily, after we have had our say, so
            // vanilla's emeralds-for-gold-nuggets trades end up in the list and
            // bury the one offer this customer actually came for. Checking the
            // size each pass is cheap and beats fighting the fill order.
            if (entity.getCustomer() == null) {
                TradeOfferList wanted = offersFor(record.craving());
                if (entity.getOffers().size() != wanted.size()) {
                    entity.setOffersFromServer(wanted);
                }
            }

            // Don't drag them away mid-trade.
            if (entity.getCustomer() == null && now % 30 == 0) {
                entity.getNavigation().startMovingTo(player, 0.55);
            }
            if (now % 80 == 0) {
                beg(entity, player);
            }
            return false;
        });
    }

    private static WanderingTraderEntity findCustomer(MinecraftServer server, UUID id) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getEntity(id) instanceof WanderingTraderEntity trader && trader.isAlive()) {
                return trader;
            }
        }
        return null;
    }

    private static void beg(WanderingTraderEntity customer, ServerPlayerEntity player) {
        ServerWorld world = (ServerWorld) customer.getWorld();
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                customer.getX(), customer.getEyeY() + 0.4, customer.getZ(),
                1, 0.2, 0.1, 0.2, 0.0);
        if (customer.getBlockPos().isWithinDistance(player.getBlockPos(), 6)) {
            world.playSound(null, customer.getBlockPos(),
                    SoundEvents.ENTITY_WANDERING_TRADER_TRADE,
                    SoundCategory.NEUTRAL, 0.5F, 0.7F);
        }
    }

    private static void leave(WanderingTraderEntity customer) {
        ServerWorld world = (ServerWorld) customer.getWorld();
        world.spawnParticles(ParticleTypes.POOF,
                customer.getX(), customer.getY() + 0.6, customer.getZ(),
                12, 0.25, 0.4, 0.25, 0.01);
        customer.discard();
    }

    // --- what they want ------------------------------------------------------

    /** Either a strain of weed or powder. Never both -- one craving each. */
    private record Craving(Strain strain, boolean powder) {
        /** On the nameplate, so you can tell from across a field whether it's worth walking over. */
        String title() {
            return powder ? "Customer (powder)" : "Customer (" + strain.display() + ")";
        }

        String greeting() {
            return powder
                    ? "Somebody's heading your way. They want powder."
                    : "Somebody's heading your way. They're after " + strain.display() + ".";
        }
    }

    /**
     * What this player could sell, picked at random from what they actually
     * hold. Cravings are drawn from your inventory rather than at random so a
     * visit is always worth answering -- a customer who wants something you've
     * never grown is just a mob walking at you.
     */
    private static Craving cravingFor(ServerPlayerEntity player) {
        var options = new java.util.ArrayList<Craving>();
        for (Strain strain : Strain.values()) {
            if (carries(player, TrapContent.driedBud(strain))
                    || carries(player, TrapContent.joint(strain))) {
                options.add(new Craving(strain, false));
            }
        }
        if (carries(player, TrapContent.cocaPowder)) {
            options.add(new Craving(null, true));
        }
        if (options.isEmpty()) {
            return null;
        }
        return options.get(player.getWorld().getRandom().nextInt(options.size()));
    }

    private static boolean carries(ServerPlayerEntity player, net.minecraft.item.Item item) {
        return player.getInventory().contains(stack -> stack.isOf(item));
    }

    /**
     * One offer per grade, so a customer pays properly for what they're given.
     *
     * Same component-predicate trick as {@link TrapTrades}: a plain item match
     * would let a Swill bud satisfy a Fire offer, and the grade system exists
     * precisely so that can't happen.
     */
    private static TradeOfferList offersFor(Craving craving) {
        TradeOfferList offers = new TradeOfferList();
        if (craving.powder()) {
            for (Purity purity : Purity.values()) {
                offers.add(buy(TrapContent.cocaPowder, TrapComponents.purity, purity.index(),
                        2, premium(purity.emeralds() * 2)));
            }
            return offers;
        }
        for (Quality grade : Quality.values()) {
            offers.add(buy(TrapContent.driedBud(craving.strain()), TrapComponents.quality,
                    grade.index(), 4, premium(grade.emeralds())));
            offers.add(buy(TrapContent.joint(craving.strain()), TrapComponents.quality,
                    grade.index(), 2, premium(grade.emeralds())));
        }
        return offers;
    }

    private static int premium(int base) {
        // Always at least one emerald better than the trader, or the low grades
        // round back down to the same price and the premium is invisible.
        return Math.max(base + 1, Math.round(base * PREMIUM));
    }

    private static TradeOffer buy(net.minecraft.item.Item wanted,
                                  net.minecraft.component.ComponentType<Integer> type,
                                  int value, int count, int emeralds) {
        var predicate = ComponentMapPredicate.of(type, value);
        return new TradeOffer(
                new TradedItem(Registries.ITEM.getEntry(wanted), count, predicate),
                Optional.empty(),
                new ItemStack(Items.EMERALD, emeralds), MAX_USES, 2, 0.05F);
    }

    private static boolean hasCustomer(ServerPlayerEntity player) {
        return CUSTOMERS.values().stream()
                .anyMatch(customer -> customer.player().equals(player.getUuid()));
    }
}
