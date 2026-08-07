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
    /**
     * How many times one customer will buy a given offer.
     *
     * Raised from 4 because every offer now costs ONE item rather than a
     * handful, so the per-visit volume had to move to the use count to stay
     * roughly where it was.
     */
    private static final int MAX_USES = 8;

    /**
     * Every offer costs exactly one item. This is a workaround, not a taste.
     *
     * Polymer bug #254: with a Polymer item as a trade's cost, the CLIENT
     * visually rejects the trade and shows a ghost slot whenever the stack
     * placed differs from the required count -- and it only reliably agrees
     * when that count is one. The server completes the trade perfectly either
     * way, which is why the payout was collectable by clicking the blank
     * square while never being drawn.
     *
     * https://github.com/Patbox/polymer/issues/254
     *
     * Per-unit prices below are the old bundle prices divided by the old bundle
     * size, so a visit is worth about what it was.
     */
    private static final int UNIT = 1;

    private record Customer(UUID player, int bornAt, Craving craving) {
    }

    /** Live customers, by entity id. In memory only -- see tick(). */
    private static final Map<UUID, Customer> CUSTOMERS = new HashMap<>();

    /**
     * Customers on their way out, and the tick they started leaving.
     *
     * They walk off rather than popping: a visitor who vanishes the instant
     * the deal closes reads as despawn jank, and the walk away is the part
     * that makes them feel like a person who came and went.
     */
    private static final Map<UUID, Integer> LEAVING = new HashMap<>();
    /** How long they get to walk before they're gone. */
    private static final int LEAVE_TICKS = 20 * 8;
    /** Far enough away to stop being interesting. */
    private static final int LEAVE_DISTANCE = 28;

    private TrapDealing() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TrapDealing::tick);
        registerCommand();

        // The last word before the screen opens.
        //
        // The tick pass can be outraced -- anything that appends to the offer
        // list between the last pass and the right-click wins, and the player
        // sees it. Rebuilding here means the list is correct at the only moment
        // that matters, whatever happened to it beforehand.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
                (player, world, hand, entity, hit) -> {
                    // MAIN_HAND only. The callback fires once per hand, so
                    // without this the offer list is torn down and rebuilt
                    // twice around the instant the screen opens -- replacing
                    // the very TradeOffer objects the open screen is holding.
                    if (!world.isClient() && hand == net.minecraft.util.Hand.MAIN_HAND
                            && entity instanceof WanderingTraderEntity customer
                            && customer.getCommandTags().contains(TAG)) {
                        Customer record = CUSTOMERS.get(customer.getUuid());
                        if (record != null && player instanceof ServerPlayerEntity seller) {
                            enforceOffers(customer, record.craving(), seller);
                        }
                    }
                    return net.minecraft.util.ActionResult.PASS;
                });
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
        enforceOffers(customer, craving);
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
                LEAVING.remove(entry.getKey());
                return true;    // killed, despawned, or unloaded
            }

            // Already walking off: let them finish, then they're gone.
            if (tickLeaving(entity, player, now)) {
                return true;
            }

            // Out of range, out of time, or the player left the world. These
            // vanish outright rather than walking, because there is nobody
            // there to watch them go.
            if (player == null || player.getWorld() != entity.getWorld()
                    || !entity.getBlockPos().isWithinDistance(player.getBlockPos(), GIVE_UP_DISTANCE)) {
                LEAVING.remove(entry.getKey());
                leave(entity);
                return true;
            }

            // Keep the payout visible while the screen is open.
            //
            // The client works out that result slot itself and gets it wrong
            // for our items, painting an empty square over a real payout --
            // click the blank and the emeralds come out. The server's answer
            // is authoritative, so it is simply sent every tick.
            //
            // UNCONDITIONALLY, including when the result is empty. An earlier
            // version skipped empty pushes to save a packet, which left the
            // client showing the last payout after the trade had consumed the
            // inputs: emeralds hanging in the result slot above two empty
            // input slots.
            if (entity.getCustomer() == player) {
                pushResultSlot(player);
            }

            // The deal's done. Wait for the screen to close so they don't
            // evaporate out of the menu mid-trade, then send them off happy.
            if (entity.getCustomer() == null && hasTraded(entity)) {
                startLeaving(entity, player, now, true);
                return false;
            }

            // Nobody's selling. Give up and move on.
            if (now - record.bornAt() > LIFETIME_TICKS) {
                startLeaving(entity, player, now, false);
                return false;
            }

            // Wandering traders drink an invisibility potion at night. That's
            // fine for a vanilla trader wandering the wilds and useless for
            // somebody who is supposed to be walking up to you, so it gets
            // stripped every pass rather than fought at the AI level.
            entity.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.INVISIBILITY);

            // Re-assert what they buy, every pass, unconditionally.
            //
            // WanderingTraderEntity.fillRecipes() APPENDS to the existing list
            // rather than replacing it, so vanilla's stock piles on top of ours
            // whenever it runs -- which is why a Midnight customer ended up
            // selling saplings and dye with one of our rows buried among them.
            // A size check wasn't enough because the totals could coincide;
            // overwriting outright is cheap for one customer and can't be
            // outraced by an append.
            // NOT re-asserted per tick any more.
            //
            // Rebuilding the list every tick replaced the TradeOffer objects
            // continuously, which reset each offer's `uses` counter -- so
            // MAX_USES never limited anything -- and churned the exact objects
            // an open screen holds a reference to. Spawn and right-click are
            // the only two moments the list needs to be right, and both set it.


            // Don't drag them away mid-trade -- or once they're heading off,
            // which would have them trudging back to somebody they're done with.
            if (entity.getCustomer() == null && now % 30 == 0
                    && !LEAVING.containsKey(entry.getKey())) {
                entity.getNavigation().startMovingTo(player, 0.55);
            }
            // Somebody walking away shouldn't still be asking.
            if (now % 80 == 0 && !LEAVING.containsKey(entry.getKey())) {
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

    /** Result slot index in a merchant screen: two inputs, then the payout. */
    private static final int RESULT_SLOT = 2;

    /** Send the server's own view of the result slot, overriding the client's. */
    private static void pushResultSlot(ServerPlayerEntity player) {
        if (!(player.currentScreenHandler
                instanceof net.minecraft.screen.MerchantScreenHandler handler)) {
            return;
        }
        player.networkHandler.sendPacket(
                new net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket(
                        handler.syncId, handler.nextRevision(), RESULT_SLOT,
                        handler.getSlot(RESULT_SLOT).getStack().copy()));
    }

    /** Has this customer actually bought something? */
    private static boolean hasTraded(WanderingTraderEntity customer) {
        for (TradeOffer offer : customer.getOffers()) {
            if (offer.getUses() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Send them on their way, with a word about how it went.
     *
     * Two different departures on purpose: a customer who got what they came
     * for and one who waited around for nothing should not feel the same, and
     * the difference is the only feedback telling you a visit was missed.
     */
    private static void startLeaving(WanderingTraderEntity customer, ServerPlayerEntity player,
                                     int now, boolean satisfied) {
        if (LEAVING.containsKey(customer.getUuid())) {
            return;
        }
        LEAVING.put(customer.getUuid(), now);

        ServerWorld world = (ServerWorld) customer.getWorld();
        world.playSound(null, customer.getBlockPos(),
                satisfied ? SoundEvents.ENTITY_WANDERING_TRADER_YES
                        : SoundEvents.ENTITY_WANDERING_TRADER_NO,
                SoundCategory.NEUTRAL, 0.8F, satisfied ? 1.0F : 0.8F);
        world.spawnParticles(satisfied ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SMOKE,
                customer.getX(), customer.getY() + 1.4, customer.getZ(),
                8, 0.25, 0.25, 0.25, 0.01);

    }

    /** Walk them away from the player until they're far enough to vanish. */
    private static boolean tickLeaving(WanderingTraderEntity customer, ServerPlayerEntity player,
                                       int now) {
        Integer since = LEAVING.get(customer.getUuid());
        if (since == null) {
            return false;
        }
        boolean farEnough = player == null
                || !customer.getBlockPos().isWithinDistance(player.getBlockPos(), LEAVE_DISTANCE);
        if (now - since > LEAVE_TICKS || farEnough) {
            LEAVING.remove(customer.getUuid());
            leave(customer);
            return true;
        }
        if (player != null && now % 20 == 0) {
            // Straight away from the player, so the exit reads as deliberate.
            var away = customer.getPos().subtract(player.getPos()).normalize()
                    .multiply(LEAVE_DISTANCE);
            customer.getNavigation().startMovingTo(
                    customer.getX() + away.x, customer.getY(), customer.getZ() + away.z, 0.6);
        }
        return false;
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
    /**
     * Make the list say exactly what this customer buys, and nothing else.
     *
     * Called both on the tick and the instant the player opens the screen. The
     * second one is what actually guarantees it: whatever appended to the list
     * in between, the list is rebuilt before anyone reads it.
     */
    private static void enforceOffers(WanderingTraderEntity customer, Craving craving) {
        enforceOffers(customer, craving, null);
    }

    /**
     * Show only what this player can actually sell them.
     *
     * A customer has one offer per grade, because a Fire bud must not be
     * bought at Swill prices -- but listing all of them means eight rows that
     * look identical, and picking the wrong one silently does nothing. You
     * can't tell a B+ joint row from an A+ joint row at a glance, so the honest
     * outcome of the full list is a player concluding the trade is broken.
     *
     * Filtering to the grades in your inventory keeps grade pricing intact and
     * makes every visible row one you can complete. With nothing to sell, the
     * full list is shown instead, so you can still see what they came for.
     */
    private static void enforceOffers(WanderingTraderEntity customer, Craving craving,
                                      ServerPlayerEntity seller) {
        if (craving == null) {
            return;
        }
        // Never rebuild after a sale. Rebuilding mints fresh TradeOffer objects
        // with `uses` back at zero, which would both hand back the MAX_USES
        // allowance and erase the only evidence that the deal happened -- so
        // the customer would never realise it was time to leave.
        if (hasTraded(customer)) {
            return;
        }
        // Mutate the live list, do NOT call setOffersFromServer.
        //
        // That method is `{ return; }` on the server -- it exists for the
        // CLIENT to accept offers sent to it, and the name reads the other way
        // round. So every customer since this feature was written has ignored
        // its craving entirely and shown whatever the wandering-trader pool
        // handed it, which is why a Midnight customer offered to buy Purp.
        //
        // getOffers() returns the real field (filling it first if it is null),
        // and TradeOfferList is an ArrayList, so clearing and refilling it in
        // place is the one thing that actually sticks without a mixin.
        TradeOfferList wanted = offersFor(craving);
        if (seller != null) {
            normaliseGrades(seller);
            TradeOfferList sellable = sellableBy(seller, craving);
            if (!sellable.isEmpty()) {
                wanted = sellable;
            }
        }
        TradeOfferList live = customer.getOffers();
        live.clear();
        live.addAll(wanted);
    }


    /**
     * Write down the grade that everything already assumes.
     *
     * Product can exist with no quality component at all -- creative-tab
     * stacks, /give, and anything minted before the grade was stamped. Every
     * reader treats that as Mids (see TrapComponents#get), so the item BEHAVES
     * as Mids everywhere except a trade, where the offer's predicate demands
     * the component actually be there and the trade silently does nothing.
     *
     * Rather than teach the predicate about absent components, which it cannot
     * express, this stamps the grade the code already believes in. It only
     * ever adds data the stack should have carried from the start, and it runs
     * where the mismatch bites: as the customer's screen is about to open.
     */
    private static void normaliseGrades(ServerPlayerEntity seller) {
        var inventory = seller.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()
                    && TrapContent.carriesQuality(stack.getItem())
                    && stack.get(TrapComponents.quality) == null) {
                TrapComponents.apply(stack, TrapComponents.get(stack));
            }
        }
    }

    /**
     * One offer per form, priced for the worst of what the seller is carrying.
     *
     * NO component predicate, and that is forced rather than chosen. The grade
     * lives in a component Polymer hides from clients -- which is what stops a
     * vanilla client being kicked over an unknown registry entry -- so the
     * client can never see it. It draws the merchant result slot itself, and a
     * predicate it cannot evaluate always fails, so it paints an empty slot
     * over a payout the server has already worked out. A grade predicate and a
     * hidden component cannot both exist.
     *
     * Pricing by the LOWEST grade held keeps that honest: matching on the item
     * alone would otherwise let a Swill bud be sold on a Fire row. Paying for
     * the worst of the batch is the same rule the mixing station already
     * applies to blends, so it should read as deliberate rather than mean.
     */
    private static TradeOfferList sellableBy(ServerPlayerEntity seller, Craving craving) {
        TradeOfferList offers = new TradeOfferList();
        if (craving.powder()) {
            Purity worst = lowestPurity(seller);
            if (worst != null) {
                offers.add(buyAny(TrapContent.cocaPowder, UNIT, premium(worst.emeralds())));
            }
            return offers;
        }
        var bud = TrapContent.driedBud(craving.strain());
        var joint = TrapContent.joint(craving.strain());
        Quality worstBud = lowestQuality(seller, bud);
        if (worstBud != null) {
            offers.add(buyAny(bud, UNIT, budPrice(worstBud)));
        }
        Quality worstJoint = lowestQuality(seller, joint);
        if (worstJoint != null) {
            offers.add(buyAny(joint, UNIT, jointPrice(worstJoint)));
        }
        return offers;
    }

    /** Old price for four buds, per bud. Never free. */
    private static int budPrice(Quality grade) {
        return Math.max(1, Math.round(premium(grade.emeralds()) / 4.0F));
    }

    /** Old price for two joints, per joint. */
    private static int jointPrice(Quality grade) {
        return Math.max(1, Math.round(premium(grade.emeralds()) / 2.0F));
    }

    /** The poorest grade of this item the player has on them, or null. */
    private static Quality lowestQuality(ServerPlayerEntity seller, net.minecraft.item.Item item) {
        Quality worst = null;
        var inventory = seller.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }
            Quality grade = TrapComponents.get(stack);
            if (worst == null || grade.index() < worst.index()) {
                worst = grade;
            }
        }
        return worst;
    }

    private static Purity lowestPurity(ServerPlayerEntity seller) {
        Purity worst = null;
        var inventory = seller.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(TrapContent.cocaPowder)) {
                continue;
            }
            Purity grade = TrapComponents.getPurity(stack);
            if (worst == null || grade.index() < worst.index()) {
                worst = grade;
            }
        }
        return worst;
    }

    /** A buy offer that matches on the item alone, so the client can see it. */
    private static TradeOffer buyAny(net.minecraft.item.Item wanted, int count, int emeralds) {
        return new TradeOffer(
                new TradedItem(Registries.ITEM.getEntry(wanted), count,
                        ComponentMapPredicate.EMPTY),
                Optional.empty(),
                new ItemStack(Items.EMERALD, emeralds), MAX_USES, 2, 0.05F);
    }

    /**
     * Does the player carry this item at exactly this grade?
     *
     * The component must be PRESENT, not merely default to zero. Treating an
     * absent grade as zero advertised a row the offer could never accept: the
     * trade's predicate requires the component to exist and equal zero, and a
     * stack carrying no grade at all fails it. The result was a single,
     * correct-looking, permanently dead trade.
     */
    private static boolean holds(ServerPlayerEntity player, net.minecraft.item.Item item,
                                 net.minecraft.component.ComponentType<Integer> type, int value) {
        return player.getInventory().contains(stack ->
                stack.isOf(item) && Integer.valueOf(value).equals(stack.get(type)));
    }

    /**
     * The shop window: what this customer buys, when you have none of it.
     *
     * One row per form, item-only, at the poorest grade's price, so an
     * empty-handed player can still see what the visit is for.
     *
     * Grade predicates are absent here too, and that matters more than it
     * looks: leaving them in the fallback quietly reinstated the dead rows the
     * moment a player sold their last unit. The list sprang back to eight
     * unmatchable offers and looked like the bug had returned -- because it
     * had, by a path nobody was watching.
     *
     * Priced at the floor because it is a floor; once you carry something,
     * {@link #sellableBy} reprices it for what you actually have.
     */
    private static TradeOfferList offersFor(Craving craving) {
        TradeOfferList offers = new TradeOfferList();
        if (craving.powder()) {
            offers.add(buyAny(TrapContent.cocaPowder, UNIT,
                    premium(Purity.byIndex(0).emeralds())));
            return offers;
        }
        Quality floor = Quality.byIndex(0);
        offers.add(buyAny(TrapContent.driedBud(craving.strain()), UNIT, budPrice(floor)));
        offers.add(buyAny(TrapContent.joint(craving.strain()), UNIT, jointPrice(floor)));
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
